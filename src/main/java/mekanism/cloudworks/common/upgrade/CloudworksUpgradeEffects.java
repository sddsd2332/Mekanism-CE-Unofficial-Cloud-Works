package mekanism.cloudworks.common.upgrade;

import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineLineUpgradePolicy;
import mekanism.cloudworks.api.module.AxisContribution;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.api.module.MachineUpgradeEffect;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.module.UpgradeAxis;
import mekanism.common.Upgrade;
import mekanism.common.config.MekanismConfig;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import mekanism.cloudworks.api.module.MachineModuleHandle;
import mekanism.cloudworks.api.module.MachineModuleSource;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import net.minecraft.util.ResourceLocation;
import java.util.*;

/** Per-policy effects compiled from the line's shared eligible upgrade fractions. */
public final class CloudworksUpgradeEffects {
    private final double time, energy, capacity, secondaryChanceMultiplier;
    private final boolean configured;

    private CloudworksUpgradeEffects(double time, double energy, double capacity,
            double secondaryChanceMultiplier, boolean configured) {
        this.time = time;
        this.energy = energy;
        this.capacity = capacity;
        this.secondaryChanceMultiplier = secondaryChanceMultiplier;
        this.configured = configured;
    }

    public static CloudworksUpgradeEffects of(ProductionLineData line) {
        // Compatibility helper for callers explicitly asking for the Mek line formula.
        return context(line).forPolicy(MachineUpgradePolicy.MEKANISM);
    }

    public static Context context(ProductionLineData line) {
        int[] settings = settings();
        CloudworksUpgradeLimits.validateBudget(line);
        // Axis-priority denominators: whole-line stable. Every physical machine
        // contributes its declared line units on each accepted axis; node-level
        // takeovers never shrink the denominator and never deduct from the line
        // counters (the line effect layer reads the raw installed counts).
        int speedCapacity = CloudworksUpgradeLimits.lineCapacity(Upgrade.SPEED, line.machines());
        int energyCapacity = CloudworksUpgradeLimits.lineCapacity(Upgrade.ENERGY, line.machines());
        return new Context(fraction(line.speedUpgrades(), speedCapacity),
                fraction(line.energyUpgrades(), energyCapacity), settings);
    }

    /**
     * Declared line participation, or the explicit family default when the
     * profile leaves it null: native full/speed/energy policies map to the
     * matching participation, everything else (fixed, NONE) stays out unless a
     * registration declares otherwise (TE machines, IC2 processors).
     */
    public static MachineLineUpgradePolicy linePolicy(MachineExecutionProfile profile) {
        if (profile == null) return MachineLineUpgradePolicy.NONE;
        MachineLineUpgradePolicy declared = profile.lineUpgradePolicy();
        if (declared != null) return declared;
        MachineUpgradePolicy node = profile.upgradePolicy();
        // withCustomType creates a new policy instance. Family participation
        // must follow its declared axes, never singleton object identity.
        return MachineLineUpgradePolicy.of(node.accepts(Upgrade.SPEED), node.accepts(Upgrade.ENERGY), 8, 8);
    }

    static CloudworksUpgradeEffects mekanism(double speed, double energy, int[] settings) {
        boolean configured = settings[0] != 0;
        // MekanismUtils.time/electricity/capacity, normalized over the whole line.
        double energyExponent = 2 * speed - (configured ? Math.min(energy, Math.max(1D, speed)) : energy);
        return new CloudworksUpgradeEffects(Math.pow(settings[1], -speed),
                Math.pow(settings[1], energyExponent), Math.pow(settings[1], energy), 1D, configured);
    }

    public static CloudworksUpgradeEffects fixed(double time, double energy, double capacity, boolean configured) {
        return fixed(time, energy, capacity, configured, 1D);
    }

    public static CloudworksUpgradeEffects fixed(double time, double energy, double capacity,
            boolean configured, double secondaryChanceMultiplier) {
        if (Double.isNaN(time) || Double.isNaN(energy) || Double.isNaN(capacity)
                || !Double.isFinite(secondaryChanceMultiplier)
                || time < 0 || energy < 0 || capacity < 0 || secondaryChanceMultiplier < 0)
            throw new IllegalArgumentException("Invalid frozen upgrade effect");
        return new CloudworksUpgradeEffects(Math.min(Double.MAX_VALUE, time),
                Math.min(Double.MAX_VALUE, energy), Math.min(Double.MAX_VALUE, capacity),
                Math.min(Double.MAX_VALUE, secondaryChanceMultiplier), configured);
    }

    public static final class Context {
        private final double speed, energy;
        private final int[] settings;

        private Context(double speed, double energy, int[] settings) {
            this.speed = speed;
            this.energy = energy;
            this.settings = settings;
        }

        /** Legacy whole-policy view: the node policy fed the line fractions directly. */
        public CloudworksUpgradeEffects forPolicy(MachineUpgradePolicy policy) {
            return convert(policy.evaluate(speed, energy, settings));
        }

        /**
         * Axis-priority resolution: each axis independently selects NODE (an
         * effective node-level claim), LINE (the shared coverage fraction) or
         * NONE, then the two selected contributions compose once by
         * multiplication with the native configured clamp applied at the
         * synthesis step (same-origin results match a single evaluate exactly).
         */
        public CloudworksUpgradeEffects forNode(MachineNodeData node) {
            MachineExecutionProfile profile = node.capabilities().profile();
            MachineUpgradePolicy nodePolicy = profile == null ? MachineUpgradePolicy.NONE : profile.upgradePolicy();
            MachineLineUpgradePolicy linePolicy = linePolicy(profile);
            AxisResolution speedAxis = resolveAxis(node, nodePolicy, linePolicy, true);
            AxisResolution energyAxis = resolveAxis(node, nodePolicy, linePolicy, false);
            return compose(speedAxis, energyAxis, nodePolicy, settings);
        }

        private AxisResolution resolveAxis(MachineNodeData node, MachineUpgradePolicy nodePolicy,
                MachineLineUpgradePolicy linePolicy, boolean speedAxis) {
            // Axis-priority batch 3: a legally installed axis-declaring module
            // takes the axis over (floor(q/N) effective count, one per machine
            // activation threshold); anything below the threshold keeps the
            // module as an asset without taking the axis (numbers doc §4).
            AxisResolution local = resolveNodeAxis(node, nodePolicy,
                    speedAxis ? UpgradeAxis.SPEED : UpgradeAxis.ENERGY);
            if (local != null) return local;
            boolean accepts = speedAxis ? linePolicy.acceptsSpeed() : linePolicy.acceptsEnergy();
            double line = speedAxis ? speed : energy;
            if (accepts && line > 0) {
                // The line curve is the frozen native exponent family (numbers doc §2).
                double m = settings[1];
                AxisContribution contribution = speedAxis
                        ? AxisContribution.of(Math.pow(m, -line), Math.pow(m, 2 * line), 1D)
                        : AxisContribution.of(1D, Math.pow(m, -line), Math.pow(m, line));
                return new AxisResolution(line, contribution, true, false);
            }
            return new AxisResolution(0D, AxisContribution.NEUTRAL, true, false);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private AxisResolution resolveNodeAxis(MachineNodeData node, MachineUpgradePolicy policy, UpgradeAxis axis) {
            Map<ResourceLocation, Long> installed = new LinkedHashMap<>();
            for (ResourceLocation id : node.customUpgrades().ids()) installed.put(id, node.customUpgrades().count(id));
            List<ResourceLocation> ids = new ArrayList<>(installed.keySet());
            ids.sort(Comparator.comparing(ResourceLocation::toString));
            Set<String> groups = new HashSet<>();
            AxisResolution selected = null;
            for (ResourceLocation id : ids) {
                MachineModuleType type = CloudworksMachineModuleRegistry.type(id);
                if (type == null || !type.takesOver(axis)) continue;
                int effective = CloudworksUpgradeLimits.effectiveModuleCount(type, policy, node.machine(),
                        node.recipeType(), node.machineCount(), installed.get(id), settings);
                if (effective <= 0) continue;
                RecipeContribution definition = CloudworksNodeMechanisms.definition(
                        CloudworksRegistries.identityKey(node.machine()), node.recipeType(), id);
                if (definition != null && !definition.exclusiveGroup().isEmpty()
                        && !groups.add(definition.exclusiveGroup()))
                    throw new IllegalArgumentException("cloudworks.error.node_mechanism_conflict");
                MachineModuleHandle handle = new MachineModuleHandle(type,
                        (int) Math.min(Integer.MAX_VALUE, installed.get(id)), effective, MachineModuleSource.UPGRADE,
                        node.nodeId(), node.nodeId(), node.machine(), node.machineCount(), "", installed,
                        new CloudworksGenerationContext(0, 1, 0, false));
                AxisContribution contribution = type.module().onUpgradeAxis(handle, axis);
                boolean fromPolicy = contribution == null;
                MachineUpgradePolicy curve = policy;
                if (fromPolicy && (MachineModuleType.MEK_SPEED_MODULE_ID.equals(id)
                        || MachineModuleType.MEK_ENERGY_MODULE_ID.equals(id)) && !policy.isNativeFormula())
                    curve = MachineUpgradePolicy.MEKANISM;
                double fraction = (double) effective / CloudworksUpgradeLimits.axisModuleLimit(type, policy, settings);
                if (fromPolicy) {
                    Upgrade nativeAxis = axis == UpgradeAxis.SPEED ? Upgrade.SPEED : Upgrade.ENERGY;
                    if (!curve.accepts(nativeAxis))
                        throw new IllegalArgumentException("Axis module has no declared numerical curve: " + id);
                    contribution = axis == UpgradeAxis.SPEED ? curve.speedAxis(fraction, settings)
                            : curve.energyAxis(fraction, settings);
                }
                AxisResolution value = new AxisResolution(fraction, contribution,
                        fromPolicy && curve.isNativeFormula(), fromPolicy);
                if (selected == null) selected = value;
                else {
                    if (selected.policySourced || value.policySourced)
                        throw new IllegalArgumentException("Multiple modules claim the same machine upgrade curve: " + axis);
                    selected = new AxisResolution(0, AxisContribution.of(
                            selected.contribution.time * contribution.time,
                            selected.contribution.power * contribution.power,
                            selected.contribution.capacity * contribution.capacity), false, false);
                }
            }
            return selected;
        }

        private static CloudworksUpgradeEffects compose(AxisResolution speedAxis, AxisResolution energyAxis,
                MachineUpgradePolicy nodePolicy, int[] settings) {
            boolean clamp = settings[0] != 0 && speedAxis.exponential && energyAxis.exponential
                    && (speedAxis.value > 0 || energyAxis.value > 0);
            // Sub-tick execution is a machine capability. Merely selecting the
            // line's native-shaped curve must not grant it to TE/IC2 executors.
            boolean configured = nodePolicy.isNativeFormula() ? settings[0] != 0
                    && (nodePolicy.accepts(Upgrade.SPEED) || nodePolicy.accepts(Upgrade.ENERGY))
                    : nodePolicy.isFixedConfigured();
            double time = Math.min(Double.MAX_VALUE, speedAxis.contribution.time * energyAxis.contribution.time);
            double capacity = Math.min(Double.MAX_VALUE,
                    speedAxis.contribution.capacity * energyAxis.contribution.capacity);
            double power;
            if (speedAxis.exponential && energyAxis.exponential) {
                // Native configured clamp, synthesized once over the selected
                // values (numbers doc §6); same-origin equals a single evaluate.
                power = Math.pow(settings[1], 2 * speedAxis.value - (clamp
                        ? Math.min(energyAxis.value, Math.max(1D, speedAxis.value)) : energyAxis.value));
            } else if (speedAxis.policySourced && energyAxis.policySourced && !nodePolicy.isNativeFormula()) {
                power = nodePolicy.evaluate(speedAxis.value, energyAxis.value, settings).energy();
            } else {
                power = Math.min(Double.MAX_VALUE,
                        speedAxis.contribution.power * energyAxis.contribution.power);
            }
            return new CloudworksUpgradeEffects(time, power, capacity, 1D, configured);
        }

        private static CloudworksUpgradeEffects convert(MachineUpgradeEffect effect) {
            return fixed(effect.time(), effect.energy(), effect.capacity(), effect.configured(),
                    effect.secondaryChanceMultiplier());
        }
    }

    /** One axis's selected source: the effective value, its contribution and its family. */
    private static final class AxisResolution {
        final double value;
        final AxisContribution contribution;
        final boolean exponential;
        final boolean policySourced;

        AxisResolution(double value, AxisContribution contribution, boolean exponential, boolean policySourced) {
            this.value = value;
            this.contribution = contribution;
            this.exponential = exponential;
            this.policySourced = policySourced;
        }
    }

    public static int[] settings() {
        if (CloudworksNativeContracts.isWorker())
            return CloudworksNativeContracts.upgradeSettings();
        try {
            return new int[]{MekanismConfig.current().mekce.EnableUpgradeConfigure.val() ? 1 : 0,
                    MekanismConfig.current().general.maxUpgradeMultiplier.val(),
                    Upgrade.SPEED.getMaxInstalled(), Upgrade.ENERGY.getMaxInstalled()};
        } catch (Throwable unavailable) {
            // Headless fixture runs do not bootstrap Forge's Loader. Keep the
            // MekCEU defaults so pure data tests remain deterministic; a live
            // server always has the configured path above.
            return new int[]{0, 10, 8, 8};
        }
    }

    private static double fraction(int installed, int maximum) {
        return maximum <= 0 ? 0 : (float) Math.min(installed, maximum) / (float) maximum;
    }

    /** Negative ticks use MekCEU's configured operations-per-tick encoding. */
    public int ticks(int baseTicks) {
        return ticks(baseTicks, time, configured);
    }

    /** Pure arithmetic for detached recipe-specific durations. */
    public static int ticks(int baseTicks, double time, boolean configured) {
        double ticks = Math.max(1, baseTicks) * time;
        return configured && ticks < 1 ? -(int) Math.min(Integer.MAX_VALUE, 1 / ticks) : (int) ticks;
    }

    public double timeMultiplier() {
        return time;
    }

    public boolean configured() {
        return configured;
    }

    public double energyPerTick(double baseEnergy) {
        // Per-machine load; Runtime applies machine count, parallelism and workRate separately.
        return Math.min(Double.MAX_VALUE, baseEnergy * energy);
    }

    public double capacity(double baseCapacity) {
        return Math.min(Double.MAX_VALUE, baseCapacity * capacity);
    }

    public double secondaryChanceMultiplier() {
        return secondaryChanceMultiplier;
    }
}

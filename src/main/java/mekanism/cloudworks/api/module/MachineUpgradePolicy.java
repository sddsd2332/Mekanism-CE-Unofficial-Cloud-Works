package mekanism.cloudworks.api.module;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mekanism.common.Upgrade;
import net.minecraft.util.ResourceLocation;

/** Immutable machine declaration; effects are evaluated only while compiling an owner snapshot. */
public final class MachineUpgradePolicy {
    public static final MachineUpgradePolicy NONE = mekanism(false, false);
    public static final MachineUpgradePolicy MEKANISM = mekanism(true, true);
    public static final MachineUpgradePolicy MEKANISM_SPEED = mekanism(true, false);
    public static final MachineUpgradePolicy MEKANISM_ENERGY = mekanism(false, true);

    private final boolean nativeFormula, configured;
    private final int speedLimit, energyLimit;
    private final double timeBase, energyBase, capacityBase, speedEnergyExponent, capacityPerUpgrade;
    private final Map<ResourceLocation, Integer> customLimits;

    private MachineUpgradePolicy(boolean nativeFormula, int speedLimit, int energyLimit,
            double timeBase, double energyBase, double capacityBase, double speedEnergyExponent,
            double capacityPerUpgrade, boolean configured) {
        this(nativeFormula, speedLimit, energyLimit, timeBase, energyBase, capacityBase,
                speedEnergyExponent, capacityPerUpgrade, configured, Collections.emptyMap());
    }

    private MachineUpgradePolicy(boolean nativeFormula, int speedLimit, int energyLimit,
            double timeBase, double energyBase, double capacityBase, double speedEnergyExponent,
            double capacityPerUpgrade, boolean configured, Map<ResourceLocation, Integer> customLimits) {
        this.nativeFormula = nativeFormula;
        this.speedLimit = speedLimit;
        this.energyLimit = energyLimit;
        this.timeBase = timeBase;
        this.energyBase = energyBase;
        this.capacityBase = capacityBase;
        this.speedEnergyExponent = speedEnergyExponent;
        this.capacityPerUpgrade = capacityPerUpgrade;
        this.configured = configured;
        this.customLimits = Collections.unmodifiableMap(new LinkedHashMap<>(customLimits));
    }

    public static MachineUpgradePolicy mekanism(boolean speed, boolean energy) {
        return new MachineUpgradePolicy(true, speed ? -1 : 0, energy ? -1 : 0, 1, 1, 1, 2, 0, false);
    }

    /**
     * Fixed, native-free curves: timeBase^-speed, energyBase^(speedCost*speed-energy),
     * capacityBase^energy. Fractions are shared by the eligible machines of the line.
     */
    public static MachineUpgradePolicy fixed(int speedLimit, int energyLimit, double timeBase,
            double energyBase, double capacityBase, double speedCost, boolean allowSubTick) {
        if (speedLimit < 0 || energyLimit < 0 || !positive(timeBase) || !positive(energyBase)
                || !positive(capacityBase) || !Double.isFinite(speedCost) || speedCost < 0)
            throw new IllegalArgumentException("Invalid machine upgrade policy");
        return new MachineUpgradePolicy(false, speedLimit, energyLimit,
                timeBase, energyBase, capacityBase, speedCost, 0, allowSubTick);
    }

    public MachineUpgradePolicy withCapacityPerUpgrade(double value) {
        if (!Double.isFinite(value) || value < 0D) throw new IllegalArgumentException("Invalid capacity increment");
        return new MachineUpgradePolicy(nativeFormula, speedLimit, energyLimit, timeBase, energyBase,
                capacityBase, speedEnergyExponent, value, configured, customLimits);
    }

    /**
     * Accepts one custom upgrade type on this machine with a per-machine
     * limit; the curve is evaluated only while freezing an owner snapshot.
     */
    public MachineUpgradePolicy withCustomType(MachineModuleType type, int limitOverride) {
        Objects.requireNonNull(type, "type");
        if (type.slot() != MachineModuleType.Slot.CUSTOM)
            throw new IllegalArgumentException("Only custom upgrade types can be declared on a policy");
        int limit = limitOverride < 0 ? type.maxPerMachine() : limitOverride;
        if (limit < 0) throw new IllegalArgumentException("Negative custom upgrade limit");
        Map<ResourceLocation, Integer> next = new LinkedHashMap<>(customLimits);
        next.put(type.id(), limit);
        return new MachineUpgradePolicy(nativeFormula, speedLimit, energyLimit, timeBase, energyBase,
                capacityBase, speedEnergyExponent, capacityPerUpgrade, configured, next);
    }

    /** Declared limit of one custom type on this machine; 0 when not accepted. */
    public int maxCustom(ResourceLocation id) {
        Integer limit = customLimits.get(Objects.requireNonNull(id, "id"));
        return limit == null ? 0 : limit;
    }

    /** Accepted custom types with their limits, in declaration order. */
    public Map<ResourceLocation, Integer> customLimits() {
        return customLimits;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    public boolean accepts(Upgrade upgrade) {
        return upgrade == Upgrade.SPEED ? speedLimit != 0 : upgrade == Upgrade.ENERGY && energyLimit != 0;
    }

    public int maxInstalled(Upgrade upgrade, int[] nativeSettings) {
        int maximum = upgrade == Upgrade.SPEED ? speedLimit : upgrade == Upgrade.ENERGY ? energyLimit : 0;
        return maximum < 0 ? Math.max(0, nativeSettings[upgrade == Upgrade.SPEED ? 2 : 3]) : maximum;
    }

    public MachineUpgradeEffect evaluate(double speed, double energy, int[] nativeSettings) {
        boolean acceptsSpeed = maxInstalled(Upgrade.SPEED, nativeSettings) > 0;
        boolean acceptsEnergy = maxInstalled(Upgrade.ENERGY, nativeSettings) > 0;
        if (!acceptsSpeed && !acceptsEnergy) return new MachineUpgradeEffect(1, 1, 1, false);
        if (!acceptsSpeed) speed = 0;
        if (!acceptsEnergy) energy = 0;
        if (nativeFormula) {
            boolean isConfigured = nativeSettings[0] != 0;
            double energyExponent = 2 * speed - (isConfigured ? Math.min(energy, Math.max(1D, speed)) : energy);
            return new MachineUpgradeEffect(Math.pow(nativeSettings[1], -speed),
                    Math.pow(nativeSettings[1], energyExponent), Math.pow(nativeSettings[1], energy), isConfigured);
        }
        double capacity = Math.pow(capacityBase, energy);
        if (capacityPerUpgrade > 0D)
            capacity += capacityPerUpgrade * energy * maxInstalled(Upgrade.ENERGY, nativeSettings);
        return new MachineUpgradeEffect(Math.pow(timeBase, -speed),
                Math.pow(energyBase, speedEnergyExponent * speed - energy), capacity, configured);
    }

    /** True when this policy uses Mekanism's native exponent curves (the decomposable family). */
    public boolean isNativeFormula() {
        return nativeFormula;
    }

    /** Whether a fixed curve allows sub-tick encoding (its configured flag). */
    public boolean isFixedConfigured() {
        return !nativeFormula && configured;
    }

    /**
     * Axis-priority decomposition: the speed axis's multiplicative share for a
     * given fraction. Native curves split into {M^-s, M^2s, 1}; fixed curves use
     * their own bases. Composing both axes reproduces {@link #evaluate} exactly
     * when both values come from the same source (same-origin invariant).
     */
    public AxisContribution speedAxis(double speed, int[] nativeSettings) {
        if (maxInstalled(Upgrade.SPEED, nativeSettings) <= 0 || speed <= 0) return AxisContribution.NEUTRAL;
        if (nativeFormula)
            return AxisContribution.of(Math.pow(nativeSettings[1], -speed),
                    Math.pow(nativeSettings[1], 2 * speed), 1D);
        return AxisContribution.of(Math.pow(timeBase, -speed),
                Math.pow(energyBase, speedEnergyExponent * speed), 1D);
    }

    /**
     * Axis-priority decomposition: the energy axis's share. Native curves split
     * into {1, M^-e, M^e}; fixed curves use their own bases and per-upgrade
     * capacity increments.
     */
    public AxisContribution energyAxis(double energy, int[] nativeSettings) {
        if (maxInstalled(Upgrade.ENERGY, nativeSettings) <= 0 || energy <= 0) return AxisContribution.NEUTRAL;
        if (nativeFormula)
            return AxisContribution.of(1D, Math.pow(nativeSettings[1], -energy),
                    Math.pow(nativeSettings[1], energy));
        double capacity = Math.pow(capacityBase, energy);
        if (capacityPerUpgrade > 0D)
            capacity += capacityPerUpgrade * energy * maxInstalled(Upgrade.ENERGY, nativeSettings);
        return AxisContribution.of(1D, Math.pow(energyBase, -energy), capacity);
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof MachineUpgradePolicy)) return false;
        MachineUpgradePolicy other = (MachineUpgradePolicy) value;
        return nativeFormula == other.nativeFormula && configured == other.configured
                && speedLimit == other.speedLimit && energyLimit == other.energyLimit
                && Double.compare(timeBase, other.timeBase) == 0 && Double.compare(energyBase, other.energyBase) == 0
                && Double.compare(capacityBase, other.capacityBase) == 0
                && Double.compare(speedEnergyExponent, other.speedEnergyExponent) == 0
                && Double.compare(capacityPerUpgrade, other.capacityPerUpgrade) == 0
                && customLimits.equals(other.customLimits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nativeFormula, configured, speedLimit, energyLimit,
                timeBase, energyBase, capacityBase, speedEnergyExponent, capacityPerUpgrade, customLimits);
    }
}

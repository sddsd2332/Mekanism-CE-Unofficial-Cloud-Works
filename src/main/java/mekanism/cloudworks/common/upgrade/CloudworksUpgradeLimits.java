package mekanism.cloudworks.common.upgrade;

import java.util.stream.LongStream;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import mekanism.cloudworks.api.module.MachineModuleType;
import mekanism.cloudworks.api.recipe.RecipeContribution;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineLineUpgradePolicy;
import mekanism.cloudworks.api.module.UpgradeAxis;
import mekanism.cloudworks.api.module.MachineUpgradePolicy;
import mekanism.common.Upgrade;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import mekanism.cloudworks.common.registry.CloudworksNodeMechanisms;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;
import net.minecraft.util.ResourceLocation;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.api.module.MachineModuleSource;

public final class CloudworksUpgradeLimits {
    private CloudworksUpgradeLimits() {
    }

    public static long availableCustom(long cloud, long backing, long assigned) {
        if (assigned > backing) return Math.max(0, cloud - (assigned - backing));
        long refund = backing - assigned;
        return cloud > Long.MAX_VALUE - refund ? Long.MAX_VALUE : cloud + refund;
    }

    public static int capacity(Upgrade upgrade, List<MachineNodeData> machines) {
        return capacity(upgrade, machines, CloudworksUpgradeEffects.settings());
    }

    /**
     * Axis-priority line allowance: the sum of every machine's declared line
     * units on the axis (numbers doc §1). Whole-line stable — node takeovers
     * never shrink it, and it is independent of the node policy's own limits.
     */
    public static int lineCapacity(Upgrade upgrade, List<MachineNodeData> machines) {
        if (upgrade != Upgrade.SPEED && upgrade != Upgrade.ENERGY) return 0;
        boolean speed = upgrade == Upgrade.SPEED;
        long total = 0;
        for (MachineNodeData node : machines) {
            MachineLineUpgradePolicy policy =
                    CloudworksUpgradeEffects.linePolicy(node.capabilities().profile());
            long units = speed ? policy.speedUnitsPerMachine() : policy.energyUnitsPerMachine();
            long count = Math.min(Integer.MAX_VALUE, Math.max(0, node.machineCount()));
            total = Math.min(Integer.MAX_VALUE, total + units * count);
        }
        return (int) total;
    }

    static int capacity(Upgrade upgrade, List<MachineNodeData> machines, int[] nativeSettings) {
        return capacity(upgrade, machines, node -> {
            MachineExecutionProfile profile = node.capabilities().profile();
            return profile == null ? nativePolicy(upgrade) : profile.upgradePolicy();
        }, MachineNodeData::machineCount, nativeSettings);
    }

    public static int nodeCapacity(Upgrade upgrade, MachineNodeData node, int[] settings) {
        MachineExecutionProfile profile = node.capabilities().profile();
        return nodeCapacity(upgrade, profile == null ? nativePolicy(upgrade) : profile.upgradePolicy(),
                node.machineCount(), settings);
    }

    public static int nodeCapacity(Upgrade upgrade, CloudworksLineDraft.Machine node) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(node.type);
        return nodeCapacity(upgrade, profile == null ? MachineUpgradePolicy.NONE : profile.upgradePolicy(),
                node.count, CloudworksUpgradeEffects.settings());
    }

    /**
     * Draft-machine capacity for one custom type; 0 when the machine does not accept it (8.5-B5).
     */
    public static long draftCustomCapacity(CloudworksLineDraft.Machine node, ResourceLocation type) {
        MachineExecutionProfile profile = CloudworksRegistries.profile(node.type);
        MachineUpgradePolicy policy = profile == null ? MachineUpgradePolicy.NONE : profile.upgradePolicy();
        int maximum = moduleLimit(policy, type, CloudworksUpgradeEffects.settings());
        return customCapacity(type, maximum, node.count);
    }

    private static long customCapacity(ResourceLocation id, int maximum, long machines) {
        if (maximum <= 0 || machines <= 0) return 0;
        RecipeContribution definition = CloudworksNodeMechanisms.definition(id);
        long units = definition != null
                && definition.installation() == RecipeContribution.Installation.PER_NODE
                ? 1 : machines;
        return Math.min(Long.MAX_VALUE / maximum, units) * maximum;
    }

    public static int nodeCapacity(Upgrade upgrade, MachineUpgradePolicy policy, long count, int[] settings) {
        return (int) Math.min(Integer.MAX_VALUE,
                Math.min(Integer.MAX_VALUE, Math.max(0, count)) * (long) policy.maxInstalled(upgrade, settings));
    }

    /**
     * Effective axis fraction contributed by legally installed axis-declaring
     * modules: floor(installed / machineCount) against the per-machine limit
     * (numbers doc §4). Zero when no module takes the axis over — including the
     * below-threshold case, which keeps the asset without claiming the axis.
     */
    public static double axisModuleFraction(MachineNodeData node, MachineUpgradePolicy policy,
            UpgradeAxis axis, int[] settings) {
        long count = node.machineCount();
        if (count <= 0) return 0;
        for (ResourceLocation id : node.customUpgrades().ids()) {
            long installed = node.customUpgrades().count(id);
            if (installed <= 0) continue;
            MachineModuleType<?> type = CloudworksMachineModuleRegistry.type(id);
            if (type == null || !type.takesOver(axis)) continue;
            int limit = axisModuleLimit(type, policy, settings);
            if (limit <= 0) continue;
            long effective = installed / count;
            if (effective <= 0) continue;
            return Math.min(1D, (double) effective / limit);
        }
        return 0;
    }

    /**
     * Per-machine limit for an axis module: an explicit custom-type declaration
     * wins; the two built-in Mek modules default to the native axis maximum on
     * native-formula machines (numbers doc §4).
     */
    public static int axisModuleLimit(MachineModuleType<?> type, MachineUpgradePolicy policy, int[] settings) {
        return moduleLimit(policy, type.id(), settings);
    }

    public static int effectiveModuleCount(MachineModuleType<?> type, MachineUpgradePolicy policy,
            ItemStack machine, String mode, long machines, long installed, int[] settings) {
        if (machines <= 0 || installed <= 0 || !type.acceptsSource(MachineModuleSource.UPGRADE)) return 0;
        int limit = moduleLimit(policy, type.id(), settings);
        if (limit <= 0) return 0;
        RecipeContribution definition = CloudworksNodeMechanisms.definition(
                CloudworksRegistries.identityKey(machine), mode, type.id());
        if (definition != null && !definition.active(installed, machines)) return 0;
        long units = definition != null && definition.installation() == RecipeContribution.Installation.PER_NODE
                ? 1 : machines;
        return (int) Math.min(limit, installed / units);
    }

    /** Shared admission limit for editor, transactions and module evaluation. */
    public static int moduleLimit(MachineUpgradePolicy policy, ResourceLocation id, int[] settings) {
        if (policy.customLimits().containsKey(id)) return policy.maxCustom(id);
        if (!policy.isNativeFormula()) return 0;
        if (MachineModuleType.MEK_SPEED_MODULE_ID.equals(id))
            return policy.maxInstalled(Upgrade.SPEED, settings);
        if (MachineModuleType.MEK_ENERGY_MODULE_ID.equals(id))
            return policy.maxInstalled(Upgrade.ENERGY, settings);
        return 0;
    }

    /**
     * Total custom capacity of one type across a line's machines; unaccepted machines contribute 0 (8.5-B4).
     */
    public static long customPoolCapacity(ProductionLineData line, ResourceLocation type) {
        long total = 0;
        for (MachineNodeData node : line.machines()) {
            MachineExecutionProfile profile = node.capabilities().profile();
            MachineUpgradePolicy policy = profile == null ? MachineUpgradePolicy.NONE : profile.upgradePolicy();
            int maximum = moduleLimit(policy, type, CloudworksUpgradeEffects.settings());
            if (maximum > 0) total = Math.addExact(total, customCapacity(type, maximum, node.machineCount()));
        }
        return total;
    }

    /**
     * Custom count of one type currently assigned to the line's nodes (8.5-B4).
     */
    public static long assignedCustom(ProductionLineData line, ResourceLocation type) {
        long count = 0;
        for (MachineNodeData node : line.machines())
            count = Math.addExact(count, node.customUpgrades().count(type));
        return count;
    }

    public static Map<ResourceLocation, Long> customAssignments(
            Iterable<MachineNodeData> nodes) {
        Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
        for (MachineNodeData node : nodes)
            for (ResourceLocation id : node.customUpgrades().ids())
                counts.merge(id, node.customUpgrades().count(id), Math::addExact);
        return counts;
    }

    public static Map<ResourceLocation, Long> draftCustomAssignments(
            Iterable<CloudworksLineDraft.Machine> nodes) {
        Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
        for (CloudworksLineDraft.Machine node : nodes)
            for (Map.Entry<String, Long> entry : node.assignedCustom.entrySet())
                counts.merge(new ResourceLocation(entry.getKey()), entry.getValue(), Math::addExact);
        return counts;
    }

    /**
     * Read-time budget validation does not reapply current configuration or discard old assets.
     */
    public static void validateBudget(ProductionLineData line) {
        // Custom typed upgrades follow the same rule per type; unknown ids keep
        // their counts on both sides so a missing integration cannot strand assets.
        Map<ResourceLocation, Long> assignedCustom = new LinkedHashMap<>();
        for (MachineNodeData node : line.machines()) {
            Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
            for (ResourceLocation id : node.customUpgrades().ids()) {
                counts.put(id, node.customUpgrades().count(id));
                assignedCustom.merge(id, node.customUpgrades().count(id), Math::addExact);
            }
            CloudworksModuleInstallations.validate(node.machineCount(), counts);
        }
        for (Map.Entry<ResourceLocation, Long> entry : assignedCustom.entrySet())
            if (entry.getValue() > line.customUpgradePool().count(entry.getKey()))
                throw new IllegalArgumentException("Unbacked custom upgrade assignment: " + entry.getKey());
    }

    public static void validateDraft(CloudworksLineDraft draft) {
        // Native per-node assignments retired (axis-priority batch 3c); custom
        // conservation is validated in validateBudget.
        for (CloudworksLineDraft.Machine node : draft.machines) {
            Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
            node.assignedCustom.forEach((id, count) -> counts.put(new ResourceLocation(id), count));
            CloudworksModuleInstallations.validate(node.count, counts);
        }
    }

    public static void normalizeDraft(CloudworksLineDraft draft) {
        for (CloudworksLineDraft.Machine node : draft.machines) {
            // Custom assignments clamp to the machine's declared capacity; types the
            // machine no longer accepts drop to zero without touching other entries.
            for (String id : new ArrayList<>(node.assignedCustom.keySet())) {
                try {
                    node.assignedCustom(id, Math.min(node.assignedCustom(id),
                            draftCustomCapacity(node, new ResourceLocation(id))));
                } catch (Exception invalidId) {
                    node.assignedCustom.remove(id);
                }
            }
        }
    }

    public static int draftCapacity(Upgrade upgrade, List<CloudworksLineDraft.Machine> machines) {
        if (upgrade != Upgrade.SPEED && upgrade != Upgrade.ENERGY) return 0;
        // Editor/blueprint allowance mirrors the authoritative line allowance:
        // declared line units per machine (A-domain), never the node policy limits.
        boolean speed = upgrade == Upgrade.SPEED;
        long total = 0;
        for (CloudworksLineDraft.Machine machine : machines) {
            MachineLineUpgradePolicy policy =
                    CloudworksUpgradeEffects.linePolicy(CloudworksRegistries.profile(machine.type));
            long units = speed ? policy.speedUnitsPerMachine() : policy.energyUnitsPerMachine();
            long count = Math.min(Integer.MAX_VALUE, Math.max(0, machine.count));
            total = Math.min(Integer.MAX_VALUE, total + units * count);
        }
        return (int) total;
    }

    private static <T> int capacity(Upgrade upgrade, Iterable<T> machines,
                                    Function<T, MachineUpgradePolicy> policy, ToLongFunction<T> counts, int[] nativeSettings) {
        long total = 0;
        for (T machine : machines) {
            long count = Math.min(Integer.MAX_VALUE, Math.max(0, counts.applyAsLong(machine)));
            int maximum = policy.apply(machine).maxInstalled(upgrade, nativeSettings);
            total = Math.min(Integer.MAX_VALUE, total + Math.min(Integer.MAX_VALUE, count * maximum));
        }
        return (int) total;
    }

    public static int capacity(Upgrade upgrade, LongStream machineCounts) {
        long machines = machineCounts.reduce(0L, (total, count) ->
                Math.min(Integer.MAX_VALUE, total + Math.min(Integer.MAX_VALUE, Math.max(0L, count))));
        int maximum;
        if (CloudworksNativeContracts.isWorker()) {
            int[] settings = CloudworksNativeContracts.upgradeSettings();
            maximum = upgrade == Upgrade.SPEED ? settings[2] : upgrade == Upgrade.ENERGY ? settings[3] : 1;
            return (int) Math.min(Integer.MAX_VALUE, machines * maximum);
        }
        try {
            maximum = upgrade.getMaxInstalled();
        } catch (Throwable unavailable) {
            maximum = upgrade == Upgrade.SPEED || upgrade == Upgrade.ENERGY ? 8 : 1;
        }
        return (int) Math.min(Integer.MAX_VALUE, machines * maximum);
    }

    /**
     * Persisted lines can outlive an optional machine integration. Preserve the
     * native Mekanism upgrade ceiling while the machine profile is unavailable,
     * instead of treating every restored node as a zero-capacity machine.
     */
    private static MachineUpgradePolicy nativePolicy(Upgrade upgrade) {
        int maximum;
        try {
            maximum = upgrade.getMaxInstalled();
        } catch (Throwable unavailable) {
            maximum = upgrade == Upgrade.SPEED || upgrade == Upgrade.ENERGY ? 8 : 0;
        }
        return MachineUpgradePolicy.fixed(upgrade == Upgrade.SPEED ? maximum : 0,
                upgrade == Upgrade.ENERGY ? maximum : 0, 1D, 1D, 1D, 0D, false);
    }
}

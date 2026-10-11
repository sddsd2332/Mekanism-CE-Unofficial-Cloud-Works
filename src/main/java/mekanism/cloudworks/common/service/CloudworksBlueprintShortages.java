package mekanism.cloudworks.common.service;

import java.util.*;
import mekanism.cloudworks.common.data.CloudworksMachineCache;
import mekanism.cloudworks.common.data.ProductionLineData;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.network.data.CloudworksLineDraft;
import net.minecraft.item.ItemStack;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineKey;

/** Allocate the live per-type deficit to individual waiting cards without claiming any machines. */
public final class CloudworksBlueprintShortages {
    private CloudworksBlueprintShortages() {}
    public static void apply(CloudworksLineDraft draft, CloudworksMachineCache cache, List<ProductionLineData> lines) {
        Map<MachineKey, Long> available = available(draft, cache, lines), demand = new HashMap<>();
        for (CloudworksLineDraft.Machine machine : draft.machines)
            if (machine.waiting != null) demand.merge(CloudworksRegistries.identityKey(machine.type), machine.required, Math::addExact);
        Map<MachineKey, Long> deficits = new HashMap<>();
        demand.forEach((type, amount) -> deficits.put(type, Math.max(0, amount - Math.max(0, available.getOrDefault(type, 0L)))));
        for (CloudworksLineDraft.Machine machine : draft.machines) if (machine.waiting != null) {
            MachineKey type = CloudworksRegistries.identityKey(machine.type);
            long missing = deficits.getOrDefault(type, 0L);
            machine.missing = Math.min(machine.required, missing);
            deficits.put(type, missing - machine.missing);
        }
    }

    public static List<CloudworksPlayerDataManager.BlueprintRequirement> requirements(CloudworksLineDraft group,
            CloudworksLineDraft current, CloudworksMachineCache cache, List<ProductionLineData> lines) {
        Map<MachineKey, Long> available = available(current, cache, lines), demand = new LinkedHashMap<>();
        Map<MachineKey, ItemStack> stacks = new HashMap<>();
        for (CloudworksLineDraft.Machine machine : group.machines) {
            MachineKey type = CloudworksRegistries.identityKey(machine.type);
            stacks.put(type, machine.type);
            demand.merge(type, machine.count, Math::addExact);
        }
        List<CloudworksPlayerDataManager.BlueprintRequirement> result = new ArrayList<>();
        demand.forEach((type, count) -> result.add(new CloudworksPlayerDataManager.BlueprintRequirement(stacks.get(type),
                count, Math.max(0, count - Math.max(0, available.getOrDefault(type, 0L))))));
        return result;
    }

    public static Map<MachineKey, Long> holdings(CloudworksMachineCache cache) {
        Map<MachineKey, Long> available = new HashMap<>();
        for (CloudworksMachineCache.Entry entry : cache.entries()) for (CloudworksMachineCache.Variant variant : entry.variants()) {
            ItemStack machine = entry.template();
            machine.setTagCompound(variant.tag().getKeySet().isEmpty() ? null : variant.tag());
            available.merge(CloudworksRegistries.identityKey(machine), variant.count(), Math::addExact);
        }
        return available;
    }
    private static Map<MachineKey, Long> available(CloudworksLineDraft draft, CloudworksMachineCache cache, List<ProductionLineData> lines) {
        Map<MachineKey, Long> available = holdings(cache);
        for (ProductionLineData line : lines) {
            if (line.lineId().equals(draft.line)) continue;
            for (MachineNodeData node : line.machines())
                available.merge(CloudworksRegistries.identityKey(node.machine()), -node.machineCount(), Math::addExact);
        }
        for (CloudworksLineDraft.Machine machine : draft.machines) {
            MachineKey type = CloudworksRegistries.identityKey(machine.type);
            available.merge(type, -machine.count, Math::addExact);
        }
        return available;
    }
}

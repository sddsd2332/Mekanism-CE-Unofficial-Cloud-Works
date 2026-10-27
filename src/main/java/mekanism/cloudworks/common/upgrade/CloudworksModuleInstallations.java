package mekanism.cloudworks.common.upgrade;

import java.util.*;
import mekanism.cloudworks.api.module.*;
import net.minecraft.util.ResourceLocation;

/** Shared socket accounting; each installed type occupies its own physical stacks. */
public final class CloudworksModuleInstallations {
    private CloudworksModuleInstallations() {}
    public static void validate(long machines, Map<ResourceLocation, Long> counts) {
        Map<String, Long> occupied = new HashMap<>(), capacities = new HashMap<>();
        counts.forEach((id, amount) -> {
            MachineModuleType<?> type = CloudworksMachineModuleRegistry.type(id);
            if (type == null || amount <= 0) return;
            ICustomMachineModule<?> module = type.module(); String group = module.installationGroup();
            if (group.isEmpty()) return;
            int stack = module.installationItemsPerSlot(), slots = module.installationSlotsPerMachine();
            if (stack < 1 || slots < 1) throw new IllegalArgumentException("Invalid shared installation contract");
            occupied.merge(group, 1 + (amount - 1) / stack, Math::addExact);
            capacities.merge(group, Math.multiplyExact(machines, slots), Math::min);
        });
        occupied.forEach((group, used) -> { if (used > capacities.get(group)) throw new IllegalArgumentException("cloudworks.error.upgrade_slots_full"); });
    }
}

package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.util.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared codec budgets while node buffers are not yet split into physical port records.
 */
public final class CloudworksResourceCapacity {
    private CloudworksResourceCapacity() {
    }

    public static boolean canInput(MachineNodeData node, Map<QIOResourceDescriptor, Long> amounts) {
        return fits(node, amounts, MachineSlotLayout.PortRole.INPUT);
    }

    public static boolean canOutput(MachineNodeData node, List<CloudworksResourceStack> additions) {
        Map<QIOResourceDescriptor, Long> amounts = new LinkedHashMap<>();
        try {
            for (CloudworksResourceStack stack : node.outputs())
                amounts.merge(stack.descriptor(), stack.amount(), Math::addExact);
            for (CloudworksResourceStack stack : additions)
                amounts.merge(stack.descriptor(), stack.amount(), Math::addExact);
            return fits(node, amounts, MachineSlotLayout.PortRole.OUTPUT);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean fits(MachineNodeData node, Map<QIOResourceDescriptor, Long> amounts, MachineSlotLayout.PortRole role) {
        MachineSlotLayout layout = node.capabilities().slots();
        if (layout == null) return true; // Allows standalone adapters; scheduler requires a registered machine.
        Map<ResourceLocation, Long> capacities = new HashMap<>(), totals = new HashMap<>();
        try {
            for (MachineSlotLayout.Slot slot : layout.slots())
                if (slot.role() == role)
                    capacities.merge(slot.codecId(), CloudworksQuantities.multiplyLimit(slot.capacity(), node.machineCount()), CloudworksQuantities::addLimit);
            for (Map.Entry<QIOResourceDescriptor, Long> entry : amounts.entrySet()) {
                if (entry.getValue() < 0) return false;
                totals.merge(entry.getKey().getCodecId(), entry.getValue(), Math::addExact);
            }
            for (Map.Entry<ResourceLocation, Long> entry : totals.entrySet())
                if (entry.getValue() > capacities.getOrDefault(entry.getKey(), 0L)) return false;
            return true;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}

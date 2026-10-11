package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.common.data.MachineNodeData;


public interface SpecialMachineAdapter {
    Object preview(MachineNodeData node);

    boolean canStart(MachineNodeData node);

    default boolean canStart(MachineNodeData node, int operations) {
        return canStart(node);
    }

    boolean reserve(MachineNodeData node);

    void advance(MachineNodeData node, int operations);

    boolean complete(MachineNodeData node);

    void cancel(MachineNodeData node);

    void restore(MachineNodeData node);
}

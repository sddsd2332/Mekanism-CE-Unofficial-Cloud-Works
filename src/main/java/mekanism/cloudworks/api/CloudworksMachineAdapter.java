package mekanism.cloudworks.api;

import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.MachineNodeData;


/**
 * Adapter boundary for native recipe machines and special non-recipe machines.
 */
public interface CloudworksMachineAdapter {
    default CloudworksOperation createOperation(MachineNodeData node, Object recipe, int operations, int duration) {
        return null;
    }

    Object preview(MachineNodeData node);

    boolean canStart(MachineNodeData node, Object recipe);

    default boolean canStart(MachineNodeData node, Object recipe, int operations) {
        return canStart(node, recipe);
    }

    boolean reserve(MachineNodeData node, Object recipe, int operations);

    void advance(MachineNodeData node, Object recipe, int operations);

    boolean complete(MachineNodeData node, Object recipe, int operations);

    void cancel(MachineNodeData node, Object recipe);

    void restore(MachineNodeData node, Object recipe);
}

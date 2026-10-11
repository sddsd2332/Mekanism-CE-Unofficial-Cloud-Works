package mekanism.cloudworks.api;

import mekanism.cloudworks.common.data.MachineNodeData;

/**
 * Optional special-machine contract for nodes that generate cloud energy.
 */
public interface CloudworksEnergyGenerator {
    double generationPerTick(MachineNodeData node, int operations);

    default double generationPerTick(MachineNodeData node, int operations,
                                     CloudworksGenerationContext context) {
        return generationPerTick(node, operations);
    }

    default boolean enabled(MachineNodeData node) {
        return node.active();
    }

    default CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            CloudworksGenerationContext context) {
        return CloudworksGenerationPlan.constant(generationPerTick(node, operations, context));
    }
}

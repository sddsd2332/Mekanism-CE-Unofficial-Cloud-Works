package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksGenerationContext;
import mekanism.cloudworks.api.CloudworksGenerationPlan;
import mekanism.cloudworks.common.data.MachineNodeData;

public final class CloudworksWindGeneratorAdapter implements CloudworksEnergyGenerator {
    private final double minimum, maximum;
    private final int minHeight, maxHeight, rotorOffset;

    public CloudworksWindGeneratorAdapter(double minimum, double maximum, int minHeight, int maxHeight, int rotorOffset) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        this.rotorOffset = rotorOffset;
    }

    @Override
    public double generationPerTick(MachineNodeData node, int operations, CloudworksGenerationContext context) {
        if (operations <= 0 || minimum <= 0 || !Double.isFinite(minimum)
                || !Double.isFinite(maximum) || maxHeight <= minHeight) return 0;
        double height = Math.min(maxHeight, Math.max(minHeight, (long) context.simulatedHeight() + rotorOffset));
        double fraction = (height - minHeight) / ((long) maxHeight - minHeight);
        double generation = ((1 - fraction) * minimum + fraction * maximum) * operations;
        return Double.isFinite(generation) ? Math.max(0, generation) : 0;
    }

    @Override
    public double generationPerTick(MachineNodeData node, int operations) {
        return generationPerTick(node, operations, new CloudworksGenerationContext(0, 1, 0, false));
    }

    @Override
    public CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            CloudworksGenerationContext context) {
        return CloudworksGenerationPlan.wind(generationPerTick(node, operations, context));
    }
}

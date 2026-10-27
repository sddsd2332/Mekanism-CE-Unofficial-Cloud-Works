package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;
import mekanism.cloudworks.api.module.CloudworksMachineContext;

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

    private double generation(int operations, CloudworksGenerationContext context) {
        if (operations <= 0 || minimum <= 0 || !Double.isFinite(minimum)
                || !Double.isFinite(maximum) || maxHeight <= minHeight) return 0;
        double height = Math.min(maxHeight, Math.max(minHeight, (long) context.simulatedHeight() + rotorOffset));
        double fraction = (height - minHeight) / ((long) maxHeight - minHeight);
        double generation = ((1 - fraction) * minimum + fraction * maximum) * operations;
        return Double.isFinite(generation) ? Math.max(0, generation) : 0;
    }

    public int heightLimit() { return (int) Math.max(0, Math.min(256L, (long) maxHeight - rotorOffset)); }
    public int effectiveHeight(int installed) {
        return (int) Math.min(maxHeight, Math.max(minHeight, (long) Math.max(0, installed) + rotorOffset));
    }

    @Override
    public CloudworksGenerationPlan freeze(CloudworksMachineContext node, int operations,
            CloudworksGenerationContext context) {
        return CloudworksGenerationPlan.wind(generation(operations, context));
    }
}

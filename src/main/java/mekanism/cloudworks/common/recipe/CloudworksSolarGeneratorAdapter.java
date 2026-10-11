package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksGenerationContext;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.common.config.MekanismConfig;

public final class CloudworksSolarGeneratorAdapter implements CloudworksEnergyGenerator {
    private final boolean advanced;

    public CloudworksSolarGeneratorAdapter(boolean advanced) {
        this.advanced = advanced;
    }

    @Override
    public double generationPerTick(MachineNodeData node, int operations, CloudworksGenerationContext context) {
        double peak = advanced ? MekanismConfig.current().generators.advancedSolarGeneration.val()
                : MekanismConfig.current().generators.solarGeneration.val();
        int tick = context.clockTick();
        double brightness;
        if (tick <= 12000) brightness = 1;
        else if (tick <= 13800) brightness = 1D - (tick - 12000D) / 1800D;
        else if (tick <= 22200) brightness = 0;
        else brightness = (tick - 22200D) / 1800D;
        return Math.max(0, peak * brightness * Math.max(1, operations));
    }

    @Override
    public double generationPerTick(MachineNodeData node, int operations) {
        return generationPerTick(node, operations, new CloudworksGenerationContext(0, 1, 0, false));
    }

    @Override
    public mekanism.cloudworks.api.CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            CloudworksGenerationContext context) {
        double peak = advanced ? MekanismConfig.current().generators.advancedSolarGeneration.val()
                : MekanismConfig.current().generators.solarGeneration.val();
        return mekanism.cloudworks.api.CloudworksGenerationPlan.solar(Math.max(0, peak) * Math.max(1, operations));
    }
}

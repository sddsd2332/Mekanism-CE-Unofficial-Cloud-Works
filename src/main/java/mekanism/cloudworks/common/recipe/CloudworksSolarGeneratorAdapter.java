package mekanism.cloudworks.common.recipe;

import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.module.CloudworksMachineContext;
import mekanism.common.config.MekanismConfig;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;

public final class CloudworksSolarGeneratorAdapter implements CloudworksEnergyGenerator {
    private final boolean advanced;

    public CloudworksSolarGeneratorAdapter(boolean advanced) {
        this.advanced = advanced;
    }

    @Override
    public CloudworksGenerationPlan freeze(CloudworksMachineContext node, int operations,
            CloudworksGenerationContext context) {
        double peak = advanced ? MekanismConfig.current().generators.advancedSolarGeneration.val()
                : MekanismConfig.current().generators.solarGeneration.val();
        // TileEntitySolarGenerator reduces electrical generation by 80% in a
        // rain-capable biome while it is raining or thundering. CloudWorks has
        // no biome coordinate, so the rain/thunder environment flags are the
        // equivalent frozen condition.
        if (context.rainEnvironment() || context.thunderEnvironment())
            peak *= 0.2D;
        return CloudworksGenerationPlan.solar(Math.max(0, peak) * operations);
    }
}

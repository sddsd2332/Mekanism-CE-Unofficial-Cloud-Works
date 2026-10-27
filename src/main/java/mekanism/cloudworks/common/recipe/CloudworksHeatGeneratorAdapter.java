package mekanism.cloudworks.common.recipe;

import mekanism.api.heat.HeatAPI;
import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.environment.CloudworksGenerationContext;
import mekanism.cloudworks.api.module.CloudworksMachineContext;
import mekanism.common.config.MekanismConfig;
import mekanism.cloudworks.api.environment.CloudworksGenerationPlan;

/** Closed-form environment leg of the Mekanism heat generator. */
public final class CloudworksHeatGeneratorAdapter implements CloudworksEnergyGenerator {
    private static final double EFFICIENCY = 0.5D;

    /** Cold compiler formula: environment baseline or environment plus active fuel heat. */
    public static double output(CloudworksGenerationContext context, boolean burning) {
        double lava = MekanismConfig.current().generators.heatGenerationLava.val();
        double nether = MekanismConfig.current().generators.heatGenerationNether.val();
        double active = MekanismConfig.current().generators.heatGeneration.val();
        double heat = Math.max(0D, lava * context.lavaHeatCards()
                + (context.netherEnvironment() ? nether : 0D) + (burning ? active : 0D));
        if (heat <= 0D || !Double.isFinite(heat)) return 0D;
        double ambient = HeatAPI.AMBIENT_TEMP;
        double equilibrium = ambient + heat / EFFICIENCY;
        double output = heat * Math.max(0D, 1D - ambient / equilibrium);
        double cap = active + 7D * lava + nether;
        return Math.max(0D, Math.min(cap, output));
    }

    @Override
    public CloudworksGenerationPlan freeze(CloudworksMachineContext node, int operations,
            CloudworksGenerationContext context) {
        return CloudworksGenerationPlan.heat(output(context, false) * Math.max(1, operations));
    }
}

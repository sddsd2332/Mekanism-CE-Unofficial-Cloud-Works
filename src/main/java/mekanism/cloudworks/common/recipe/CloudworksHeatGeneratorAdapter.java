package mekanism.cloudworks.common.recipe;

import mekanism.api.heat.HeatAPI;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.CloudworksGenerationContext;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.common.config.MekanismConfig;

/** Closed-form environment leg of the Mekanism heat generator. */
public final class CloudworksHeatGeneratorAdapter implements CloudworksEnergyGenerator {
    private static final double EFFICIENCY = 0.5D;

    @Override
    public double generationPerTick(MachineNodeData node, int operations,
                                    CloudworksGenerationContext context) {
        return output(context, false) * Math.max(1, operations);
    }

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
    public double generationPerTick(MachineNodeData node, int operations) {
        return generationPerTick(node, operations, new CloudworksGenerationContext(0, 1, 0, false));
    }

    @Override
    public mekanism.cloudworks.api.CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            CloudworksGenerationContext context) {
        return mekanism.cloudworks.api.CloudworksGenerationPlan.heat(generationPerTick(node, operations, context));
    }
}

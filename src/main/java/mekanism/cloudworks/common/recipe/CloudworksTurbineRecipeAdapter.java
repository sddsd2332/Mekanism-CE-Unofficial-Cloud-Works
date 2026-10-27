package mekanism.cloudworks.common.recipe;

import java.util.Collections;
import mekanism.common.config.MekanismConfig;
import mekanism.generators.common.content.turbine.TurbineUpdateProtocol;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import java.util.Objects;

public final class CloudworksTurbineRecipeAdapter extends CloudworksMultiblockRecipeAdapter {
    public CloudworksTurbineRecipeAdapter(int steam, int blades, int coils, int condensers) {
        super(Collections.singletonList(new CloudworksContractRecipeAdapter.Contract("turbine",
                batch(steam, blades, coils, condensers), 1)));
    }

    private static CloudworksRecipeBatch batch(int steam, int blades, int coils, int condensers) {
        if (steam < 1 || blades < 1 || blades > TurbineUpdateProtocol.MAX_BLADES || coils < 1 || condensers < 0)
            throw new IllegalArgumentException("Invalid turbine geometry or throughput");
        long supportedBlades = Math.min(blades, (long) coils * MekanismConfig.current().generators.turbineBladesPerCoil.val());
        double energy = steam * (MekanismConfig.current().general.maxEnergyPerSteam.val() / TurbineUpdateProtocol.MAX_BLADES) * supportedBlades;
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch()
                .fluid(new FluidStack(Objects.requireNonNull(FluidRegistry.getFluid("steam"), "steam"), steam)).energy(energy);
        int water = (int) Math.min(steam, (long) condensers * MekanismConfig.current().generators.condenserRate.val());
        if (water > 0) batch.output(new FluidStack(FluidRegistry.WATER, water), "water_output");
        return batch;
    }
}

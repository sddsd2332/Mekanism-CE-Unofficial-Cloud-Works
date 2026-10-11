package mekanism.cloudworks.common.recipe;

import java.util.Collections;
import mekanism.api.gas.GasStack;
import mekanism.common.MekanismFluids;
import mekanism.common.content.boiler.SynchronizedBoilerData;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

public final class CloudworksBoilerRecipeAdapter extends CloudworksMultiblockRecipeAdapter {
    public CloudworksBoilerRecipeAdapter(int waterPerOperation, int ticks) {
        super(Collections.singletonList(new CloudworksContractRecipeAdapter.Contract("boiler",
                batch(waterPerOperation), ticks)));
    }

    private static CloudworksRecipeBatch batch(int water) {
        if (water < 1) throw new IllegalArgumentException("Invalid boiler throughput");
        double required = water * SynchronizedBoilerData.getHeatEnthalpy()
                / SynchronizedBoilerData.getSteamEnergyEfficiency() / SynchronizedBoilerData.SODIUM_THERMAL_ENTHALPY;
        if (!Double.isFinite(required) || required > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Boiler coolant quantity overflow");
        int coolant = Math.max(1, (int) Math.ceil(required));
        return new CloudworksRecipeBatch().fluid(new FluidStack(FluidRegistry.WATER, water))
                .gas(new GasStack(MekanismFluids.SuperheatedSodium, 1), coolant, "heated_coolant_input")
                .output(new FluidStack(java.util.Objects.requireNonNull(FluidRegistry.getFluid("steam"), "steam"), water), "steam_output")
                .output(new GasStack(MekanismFluids.Sodium, coolant), "cooled_coolant_output");
    }
}

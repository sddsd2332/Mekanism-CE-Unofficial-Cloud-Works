package mekanism.cloudworks.common.recipe;

import java.util.Collections;
import mekanism.api.gas.GasStack;
import mekanism.common.MekanismFluids;
import mekanism.common.config.MekanismConfig;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import java.util.Objects;

public final class CloudworksFissionReactorRecipeAdapter extends CloudworksMultiblockRecipeAdapter {
    public CloudworksFissionReactorRecipeAdapter(boolean sodium, int ticks) {
        super(Collections.singletonList(new CloudworksContractRecipeAdapter.Contract(
                sodium ? "fission_sodium" : "fission_water", batch(sodium), ticks)));
    }

    private static CloudworksRecipeBatch batch(boolean sodium) {
        double heat = MekanismConfig.current().generators.energyPerFissionFuel.val();
        double amount = sodium ? heat / MekanismConfig.current().generators.fissionSodiumThermalEnthalpy.val()
                : heat * MekanismConfig.current().generators.fissionSteamEfficiency.val()
                        / MekanismConfig.current().general.maxEnergyPerSteam.val();
        if (!Double.isFinite(amount) || amount < 1 || amount > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid fission coolant yield");
        int coolant = (int) Math.floor(amount);
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().unscaledOutputs()
                .gas(new GasStack(MekanismFluids.FissileFuel, 1), 1, "fuel_input")
                .output(new GasStack(MekanismFluids.NuclearWaste, 1), "waste_output");
        if (sodium) return batch.gas(new GasStack(MekanismFluids.Sodium, 1), coolant, "coolant_input")
                .output(new GasStack(MekanismFluids.SuperheatedSodium, coolant), "heated_coolant_output");
        return batch.fluid(new FluidStack(FluidRegistry.WATER, coolant))
                .output(new FluidStack(Objects.requireNonNull(FluidRegistry.getFluid("steam"), "steam"), coolant), "steam_output");
    }
}

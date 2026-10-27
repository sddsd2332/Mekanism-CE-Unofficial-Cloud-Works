package mekanism.cloudworks.common.recipe;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.gas.GasStack;
import mekanism.common.MekanismFluids;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.boiler.SynchronizedBoilerData;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.recipe.machines.FusionCoolingRecipe;
import net.minecraftforge.fluids.FluidStack;

public final class CloudworksFusionReactorRecipeAdapter extends CloudworksMultiblockRecipeAdapter {
    private final mekanism.cloudworks.api.machine.CloudworksThermalPlan thermal;
    public CloudworksFusionReactorRecipeAdapter(int injection, boolean cooled) {
        super(contracts(injection, cooled));
        thermal = mekanism.cloudworks.common.integration.CloudworksGeneratorsIntegration.fusionPlan(injection, cooled);
    }
    @Override public mekanism.cloudworks.api.machine.CloudworksThermalPlan thermalPlan() { return thermal; }

    private static List<CloudworksContractRecipeAdapter.Contract> contracts(int injection, boolean cooled) {
        if (injection < 2 || injection % 2 != 0
                || injection > MekanismConfig.current().generators.reactorGeneratorInjectionRate.val())
            throw new IllegalArgumentException("Fusion injection must be even and within Mek's configured limit");
        double heat = injection * MekanismConfig.current().generators.energyPerFusionFuel.val();
        double air = MekanismConfig.current().generators.fusionCasingThermalConductivity.val();
        double water = cooled ? MekanismConfig.current().generators.fusionWaterHeatingRatio.val() : 0;
        double coolantShare = water / (water + air);
        double energy = heat * (1 - coolantShare) * MekanismConfig.current().generators.fusionThermocoupleEfficiency.val();
        List<CloudworksContractRecipeAdapter.Contract> result = new ArrayList<>();
        for (int mode = 0; mode < 2; mode++) {
            if (!cooled) {
                result.add(new CloudworksContractRecipeAdapter.Contract("fusion_" + mode,
                        fuel(mode, injection).energy(energy), 1));
                continue;
            }
            double quantity = heat * coolantShare * SynchronizedBoilerData.getSteamEnergyEfficiency()
                    / SynchronizedBoilerData.getHeatEnthalpy();
            if (!Double.isFinite(quantity) || quantity < 1 || quantity > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Invalid fusion coolant yield");
            int fluidAmount = (int) quantity, index = 0;
            for (Object value : RecipeHandler.Recipe.FUSION_COOLING.get().values()) {
                FusionCoolingRecipe recipe = (FusionCoolingRecipe) value;
                FluidStack input = recipe.getInput().ingredient.copy();
                FluidStack output = recipe.getOutput().output.copy();
                input.amount = fluidAmount;
                output.amount = Math.multiplyExact(fluidAmount, output.amount);
                result.add(new CloudworksContractRecipeAdapter.Contract("fusion_" + mode + "_" + index++,
                        fuel(mode, injection).fluid(input).output(output, "fluid_output").energy(energy), 1));
            }
            if (result.isEmpty()) throw new IllegalArgumentException("Fusion cooling recipes are unavailable");
        }
        return result;
    }

    private static CloudworksRecipeBatch fuel(int mode, int injection) {
        if (mode == 0) return new CloudworksRecipeBatch()
                .gas(new GasStack(MekanismFluids.FusionFuel, 1), injection, "fuel_input");
        return new CloudworksRecipeBatch()
                .gas(new GasStack(MekanismFluids.Deuterium, 1), injection / 2, "deuterium_input")
                .gas(new GasStack(MekanismFluids.Tritium, 1), injection / 2, "tritium_input");
    }

}

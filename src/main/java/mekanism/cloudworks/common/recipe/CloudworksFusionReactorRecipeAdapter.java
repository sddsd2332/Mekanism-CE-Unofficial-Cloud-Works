package mekanism.cloudworks.common.recipe;

import java.util.ArrayList;
import java.util.List;
import mekanism.api.gas.GasStack;
import mekanism.common.MekanismFluids;
import mekanism.common.config.MekanismConfig;
import mekanism.common.content.boiler.SynchronizedBoilerData;
import mekanism.common.recipe.RecipeHandler;
import mekanism.common.recipe.machines.FusionCoolingRecipe;

public final class CloudworksFusionReactorRecipeAdapter extends CloudworksMultiblockRecipeAdapter
        implements mekanism.cloudworks.api.CloudworksEnergyGenerator {
    public CloudworksFusionReactorRecipeAdapter(int injection, boolean cooled) {
        super(contracts(injection, cooled));
    }

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
                net.minecraftforge.fluids.FluidStack input = recipe.getInput().ingredient.copy();
                net.minecraftforge.fluids.FluidStack output = recipe.getOutput().output.copy();
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

    @Override
    public double generationPerTick(mekanism.cloudworks.common.data.MachineNodeData node, int operations) {
        CloudworksContractRecipeAdapter.Contract recipe = registered(preview(node));
        if (recipe == null || operations <= 0) return 0;
        int low = 0, high = operations;
        while (low < high) {
            int middle = low + (int) (((long) high - low + 1) / 2);
            if (recipe.batch.allocate(stock(node), middle) != null
                    && mekanism.cloudworks.common.service.CloudworksResourceCapacity.canOutput(node, recipe.batch.outputs(middle)))
                low = middle;
            else high = middle - 1;
        }
        return recipe.batch.energyPerOperation() * low;
    }

    @Override
    public mekanism.cloudworks.api.CloudworksGenerationPlan freezeGeneration(
            mekanism.cloudworks.common.data.MachineNodeData node, int operations,
            mekanism.cloudworks.api.CloudworksGenerationContext context) {
        // Runtime burns the resource contract; a constant plan must not credit the same fuel twice.
        return mekanism.cloudworks.api.CloudworksGenerationPlan.constant(0);
    }
}

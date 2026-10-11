package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.GasAndFluidInput;
import mekanism.common.recipe.machines.WasherRecipe;

import java.util.Map;

public final class CloudworksGasFluidRecipeAdapter extends CloudworksNativeRecipeAdapter<WasherRecipe> {
    public CloudworksGasFluidRecipeAdapter(Map<GasAndFluidInput, ? extends WasherRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(WasherRecipe recipe) {
        return new CloudworksRecipeBatch().gas(recipe.getInput().ingredientGas, recipe.getInput().ingredientGas.amount).fluid(recipe.getInput().ingredientFluid).output(recipe.getOutput().output);
    }
}

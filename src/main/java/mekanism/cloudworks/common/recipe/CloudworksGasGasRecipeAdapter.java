package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.GasInput;
import mekanism.common.recipe.machines.IsotopicRecipe;

import java.util.Map;

/**
 * One gas input to one gas output.
 */
public final class CloudworksGasGasRecipeAdapter extends CloudworksNativeRecipeAdapter<IsotopicRecipe> {
    public CloudworksGasGasRecipeAdapter(Map<GasInput, ? extends IsotopicRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(IsotopicRecipe recipe) {
        return new CloudworksRecipeBatch().gas(recipe.getInput().ingredient, recipe.getInput().ingredient.amount)
                .output(recipe.getOutput().output);
    }
}

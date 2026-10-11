package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.GasInput;
import mekanism.common.recipe.machines.CrystallizerRecipe;

import java.util.Map;

/**
 * One gas input to one item output.
 */
public final class CloudworksGasItemRecipeAdapter extends CloudworksNativeRecipeAdapter<CrystallizerRecipe> {
    public CloudworksGasItemRecipeAdapter(Map<GasInput, ? extends CrystallizerRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(CrystallizerRecipe recipe) {
        return new CloudworksRecipeBatch().gas(recipe.getInput().ingredient, recipe.getInput().ingredient.amount)
                .output(recipe.getOutput().output);
    }
}

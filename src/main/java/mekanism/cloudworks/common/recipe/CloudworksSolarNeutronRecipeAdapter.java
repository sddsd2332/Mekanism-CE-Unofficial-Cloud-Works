package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.GasInput;
import mekanism.common.recipe.machines.SolarNeutronRecipe;

import java.util.Map;

public final class CloudworksSolarNeutronRecipeAdapter extends CloudworksNativeRecipeAdapter<SolarNeutronRecipe> {
    public CloudworksSolarNeutronRecipeAdapter(Map<GasInput, ? extends SolarNeutronRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(SolarNeutronRecipe recipe) {
        return new CloudworksRecipeBatch().requireSunlight().gas(recipe.getInput().ingredient, recipe.getInput().ingredient.amount)
                .output(recipe.getOutput().output);
    }
}

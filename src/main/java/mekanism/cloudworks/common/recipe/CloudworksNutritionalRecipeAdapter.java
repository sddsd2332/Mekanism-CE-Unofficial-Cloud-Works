package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.NutritionalRecipe;

import java.util.Map;

public final class CloudworksNutritionalRecipeAdapter extends CloudworksNativeRecipeAdapter<NutritionalRecipe> {
    public CloudworksNutritionalRecipeAdapter(Map<ItemStackInput, ? extends NutritionalRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(NutritionalRecipe recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().ingredient).output(recipe.getOutput().output);
    }
}

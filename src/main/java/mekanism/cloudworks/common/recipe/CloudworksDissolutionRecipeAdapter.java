package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.DissolutionRecipe;

import java.util.Map;

public final class CloudworksDissolutionRecipeAdapter extends CloudworksNativeRecipeAdapter<DissolutionRecipe> {
    public CloudworksDissolutionRecipeAdapter(Map<ItemStackInput, ? extends DissolutionRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(DissolutionRecipe recipe) {
        return new CloudworksRecipeBatch().item(recipe.getItemInput()).gas(recipe.getGasInput(), 100L * recipe.getGasInput().amount).output(recipe.getOutput().output);
    }
}

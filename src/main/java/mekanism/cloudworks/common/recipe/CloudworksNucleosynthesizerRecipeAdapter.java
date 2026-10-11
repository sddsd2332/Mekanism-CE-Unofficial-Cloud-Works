package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.NucleosynthesizerInput;
import mekanism.common.recipe.machines.NucleosynthesizerRecipe;

import java.util.Map;

/**
 * Item plus gas to item, preserving the native recipe duration.
 */
public final class CloudworksNucleosynthesizerRecipeAdapter extends CloudworksNativeRecipeAdapter<NucleosynthesizerRecipe> {
    public CloudworksNucleosynthesizerRecipeAdapter(Map<NucleosynthesizerInput, ? extends NucleosynthesizerRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(NucleosynthesizerRecipe recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().getSolid())
                .gas(recipe.getInput().getGas(), recipe.getInput().getGas().amount)
                .output(recipe.getOutput().output).extraEnergy(recipe.extraEnergy);
    }

    @Override
    protected int duration(NucleosynthesizerRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

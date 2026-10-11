package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.FluidInput;
import mekanism.common.recipe.machines.FusionCoolingRecipe;

import java.util.Map;

/**
 * Virtual fusion controller cooling adapter; cooling recipes remain native Mekanism objects.
 */
public final class CloudworksFusionCoolingRecipeAdapter extends CloudworksNativeRecipeAdapter<FusionCoolingRecipe> {
    public CloudworksFusionCoolingRecipeAdapter(Map<FluidInput, ? extends FusionCoolingRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(FusionCoolingRecipe recipe) {
        return new CloudworksRecipeBatch().fluid(recipe.getInput().ingredient).output(recipe.getOutput().output);
    }
}

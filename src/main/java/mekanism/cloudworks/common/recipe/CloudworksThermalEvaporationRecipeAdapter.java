package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.FluidInput;
import mekanism.common.recipe.machines.ThermalEvaporationRecipe;

import java.util.Map;

/**
 * Virtual controller adapter for Thermal Evaporation fluid recipes.
 */
public final class CloudworksThermalEvaporationRecipeAdapter extends CloudworksNativeRecipeAdapter<ThermalEvaporationRecipe> {
    public CloudworksThermalEvaporationRecipeAdapter(Map<FluidInput, ? extends ThermalEvaporationRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ThermalEvaporationRecipe recipe) {
        return new CloudworksRecipeBatch().fluid(recipe.getInput().ingredient).output(recipe.getOutput().output);
    }
}

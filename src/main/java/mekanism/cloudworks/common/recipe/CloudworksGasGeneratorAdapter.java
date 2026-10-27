package mekanism.cloudworks.common.recipe;

import java.util.Map;
import mekanism.common.recipe.GasStackFuelToEnergyRecipe;
import mekanism.common.recipe.inputs.GasInput;

/** Fuel consumption and energy yield are settled together by Runtime. */
public final class CloudworksGasGeneratorAdapter extends CloudworksNativeRecipeAdapter<GasStackFuelToEnergyRecipe> {
    public CloudworksGasGeneratorAdapter(Map<GasInput, ? extends GasStackFuelToEnergyRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(GasStackFuelToEnergyRecipe recipe) {
        return new CloudworksRecipeBatch().gas(recipe.getInput().ingredient, recipe.getInput().ingredient.amount)
                .energy(recipe.getOutput().energyOutput);
    }
}

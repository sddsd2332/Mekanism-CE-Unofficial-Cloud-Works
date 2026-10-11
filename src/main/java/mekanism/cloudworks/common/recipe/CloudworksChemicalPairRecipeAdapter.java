package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.ChemicalPairInput;
import mekanism.common.recipe.machines.ChemicalInfuserRecipe;

import java.util.Map;

/**
 * Two gas inputs to one gas output, including the native swapped-input semantics.
 */
public final class CloudworksChemicalPairRecipeAdapter extends CloudworksNativeRecipeAdapter<ChemicalInfuserRecipe> {
    public CloudworksChemicalPairRecipeAdapter(Map<ChemicalPairInput, ? extends ChemicalInfuserRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ChemicalInfuserRecipe recipe) {
        return new CloudworksRecipeBatch()
                .gas(recipe.getInput().leftGas, recipe.getInput().leftGas.amount)
                .gas(recipe.getInput().rightGas, recipe.getInput().rightGas.amount)
                .output(recipe.getOutput().output);
    }
}

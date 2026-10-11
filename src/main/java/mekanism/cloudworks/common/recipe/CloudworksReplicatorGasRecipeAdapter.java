package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.ChemicalGasInput;
import mekanism.common.recipe.machines.ReplicatorGasStackRecipe;

import java.util.Map;

/**
 * Native adapter for gas-template replicator recipes.
 */
public final class CloudworksReplicatorGasRecipeAdapter extends CloudworksNativeRecipeAdapter<ReplicatorGasStackRecipe> {
    public CloudworksReplicatorGasRecipeAdapter(Map<ChemicalGasInput, ? extends ReplicatorGasStackRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ReplicatorGasStackRecipe recipe) {
        return new CloudworksRecipeBatch().template(recipe.getInput().input, "gas_template")
                .gas(recipe.getInput().uu, recipe.getInput().uu.amount, "gas_input")
                .output(recipe.getOutput().output).extraEnergy(recipe.extraEnergy);
    }

    @Override
    protected int duration(ReplicatorGasStackRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

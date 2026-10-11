package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.NucleosynthesizerInput;
import mekanism.common.recipe.machines.ReplicatorItemStackRecipe;

import java.util.Map;

/**
 * Native adapter for item-template replicator recipes.
 */
public final class CloudworksReplicatorItemRecipeAdapter extends CloudworksNativeRecipeAdapter<ReplicatorItemStackRecipe> {
    public CloudworksReplicatorItemRecipeAdapter(Map<NucleosynthesizerInput, ? extends ReplicatorItemStackRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ReplicatorItemStackRecipe recipe) {
        return new CloudworksRecipeBatch().template(recipe.getInput().getSolid(), "item_template")
                .gas(recipe.getInput().getGas(), recipe.getInput().getGas().amount, "gas_input")
                .output(recipe.getOutput().output).extraEnergy(recipe.extraEnergy);
    }

    @Override
    protected int duration(ReplicatorItemStackRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

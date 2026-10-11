package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.GasAndFluidInput;
import mekanism.common.recipe.machines.ReplicatorFluidStackRecipe;

import java.util.Map;

/**
 * Native adapter for fluid-template replicator recipes.
 */
public final class CloudworksReplicatorFluidRecipeAdapter extends CloudworksNativeRecipeAdapter<ReplicatorFluidStackRecipe> {
    public CloudworksReplicatorFluidRecipeAdapter(Map<GasAndFluidInput, ? extends ReplicatorFluidStackRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ReplicatorFluidStackRecipe recipe) {
        return new CloudworksRecipeBatch().gas(recipe.getInput().ingredientGas, recipe.getInput().ingredientGas.amount, "gas_input")
                .template(recipe.getInput().ingredientFluid, "fluid_template").output(recipe.getOutput().output)
                .extraEnergy(recipe.extraEnergy);
    }

    @Override
    protected int duration(ReplicatorFluidStackRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

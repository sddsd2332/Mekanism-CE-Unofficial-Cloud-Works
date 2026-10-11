package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.RotaryInput;
import mekanism.common.recipe.machines.RotaryRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exposes each supported native rotary direction as an independent conversion.
 */
public final class CloudworksRotaryRecipeAdapter extends CloudworksNativeRecipeAdapter<RotaryRecipe> {
    public CloudworksRotaryRecipeAdapter(Map<RotaryInput, ? extends RotaryRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected List<RotaryRecipe> recipeVariants(RotaryRecipe recipe) {
        List<RotaryRecipe> directions = new ArrayList<>(2);
        if (recipe.hasFluidToGas())
            directions.add(new RotaryRecipe(recipe.getInput().fluidInput.copy(), null,
                    recipe.getOutput().gasOutput.copy(), null));
        if (recipe.hasGasToFluid())
            directions.add(new RotaryRecipe(null, recipe.getInput().gasInput.copy(),
                    null, recipe.getOutput().fluidOutput.copy()));
        return directions;
    }

    @Override
    protected CloudworksRecipeBatch describe(RotaryRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
        if (recipe.getInput().fluidInput != null) batch.fluid(recipe.getInput().fluidInput);
        if (recipe.getInput().gasInput != null)
            batch.gas(recipe.getInput().gasInput, recipe.getInput().gasInput.amount);
        if (recipe.getOutput().fluidOutput != null) batch.output(recipe.getOutput().fluidOutput);
        if (recipe.getOutput().gasOutput != null) batch.output(recipe.getOutput().gasOutput);
        return batch;
    }
}

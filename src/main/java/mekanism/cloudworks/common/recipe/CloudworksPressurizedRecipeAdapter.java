package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.PressurizedInput;
import mekanism.common.recipe.machines.PressurizedRecipe;

import java.util.Map;

public final class CloudworksPressurizedRecipeAdapter extends CloudworksNativeRecipeAdapter<PressurizedRecipe> {
    public CloudworksPressurizedRecipeAdapter(Map<PressurizedInput, ? extends PressurizedRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(PressurizedRecipe recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().getSolid()).fluid(recipe.getInput().getFluid()).gas(recipe.getInput().getGas(), recipe.getInput().getGas().amount).output(recipe.getOutput().getItemOutput()).output(recipe.getOutput().getGasOutput());
    }

    @Override
    protected int duration(PressurizedRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

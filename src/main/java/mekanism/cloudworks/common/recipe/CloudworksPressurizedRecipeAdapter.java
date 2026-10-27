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
        // TileEntityPRC:263-264 charges BASE_ENERGY_PER_TICK + recipe.extraEnergy
        // every tick; the profile keeps the base and the recipe carries the extra.
        return new CloudworksRecipeBatch().item(recipe.getInput().getSolid()).fluid(recipe.getInput().getFluid())
                .gas(recipe.getInput().getGas(), recipe.getInput().getGas().amount)
                .output(recipe.getOutput().getItemOutput()).output(recipe.getOutput().getGasOutput())
                .extraEnergy(recipe.extraEnergy);
    }

    @Override
    protected int duration(PressurizedRecipe recipe, int fallback) {
        return recipe.ticks;
    }
}

package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.DoubleMachineInput;
import mekanism.common.recipe.machines.DoubleMachineRecipe;

import java.util.Map;

public final class CloudworksDoubleItemRecipeAdapter extends CloudworksNativeRecipeAdapter<DoubleMachineRecipe<?>> {
    public CloudworksDoubleItemRecipeAdapter(Map<DoubleMachineInput, ? extends DoubleMachineRecipe<?>> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(DoubleMachineRecipe<?> recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().itemStack, "item_input")
                .item(recipe.getInput().extraStack, "extra_item_input").output(recipe.getOutput().output);
    }
}

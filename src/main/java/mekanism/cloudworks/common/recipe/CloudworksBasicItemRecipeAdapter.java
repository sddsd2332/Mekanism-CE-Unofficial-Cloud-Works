package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.BasicMachineRecipe;

import java.util.Map;

public final class CloudworksBasicItemRecipeAdapter extends CloudworksNativeRecipeAdapter<BasicMachineRecipe<?>> {
    public CloudworksBasicItemRecipeAdapter(Map<ItemStackInput, ? extends BasicMachineRecipe<?>> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(BasicMachineRecipe<?> recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().ingredient).output(recipe.getOutput().output);
    }
}

package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.AdvancedMachineInput;
import mekanism.common.recipe.machines.AdvancedMachineRecipe;

import java.util.Map;

public final class CloudworksAdvancedGasItemRecipeAdapter extends CloudworksNativeRecipeAdapter<AdvancedMachineRecipe<?>> {
    public CloudworksAdvancedGasItemRecipeAdapter(Map<AdvancedMachineInput, ? extends AdvancedMachineRecipe<?>> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(AdvancedMachineRecipe<?> recipe) {
        return new CloudworksRecipeBatch().secondaryGasInputs().item(recipe.getItemInput()).gas(recipe.getGasInput(), 200L * recipe.getGasInput().amount).output(recipe.getOutput().output);
    }
}

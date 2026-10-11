package mekanism.cloudworks.common.recipe;

import mekanism.api.gas.GasStack;
import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.OxidationRecipe;

import java.util.Map;

/**
 * Item input to gas output native adapter; transaction behavior is shared by the generic base.
 */
public final class CloudworksItemGasOutputRecipeAdapter extends CloudworksNativeRecipeAdapter<OxidationRecipe> {
    public CloudworksItemGasOutputRecipeAdapter(Map<ItemStackInput, ? extends OxidationRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(OxidationRecipe recipe) {
        GasStack output = recipe.getOutput().output.copy();
        return new CloudworksRecipeBatch().item(recipe.getInput().ingredient).output(output);
    }
}

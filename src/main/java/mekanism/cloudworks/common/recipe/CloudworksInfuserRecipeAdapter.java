package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.InfusionInput;
import mekanism.common.recipe.machines.MetallurgicInfuserRecipe;

import java.util.Map;

/**
 * Item plus InfuseType resource to item.
 */
public final class CloudworksInfuserRecipeAdapter extends CloudworksNativeRecipeAdapter<MetallurgicInfuserRecipe> {
    public CloudworksInfuserRecipeAdapter(Map<InfusionInput, ? extends MetallurgicInfuserRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(MetallurgicInfuserRecipe recipe) {
        return new CloudworksRecipeBatch().item(recipe.getInput().inputStack, "item_input")
                .infuse(recipe.getInput().infuse).output(recipe.getOutput().output);
    }
}

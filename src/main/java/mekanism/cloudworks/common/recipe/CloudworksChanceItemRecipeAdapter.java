package mekanism.cloudworks.common.recipe;

import java.util.Map;
import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.ChanceMachineRecipe;
import mekanism.common.recipe.outputs.ChanceOutput;

public final class CloudworksChanceItemRecipeAdapter extends CloudworksNativeRecipeAdapter<ChanceMachineRecipe<?>> {
    public CloudworksChanceItemRecipeAdapter(Map<ItemStackInput, ? extends ChanceMachineRecipe<?>> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(ChanceMachineRecipe<?> recipe) {
        ChanceOutput output = recipe.getOutput();
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().item(recipe.getInput().ingredient);
        if (output.hasPrimary()) batch.output(output.getMainOutput());
        return batch.chanceOutput(output.getMaxSecondaryOutput(), output.secondaryChance);
    }
}

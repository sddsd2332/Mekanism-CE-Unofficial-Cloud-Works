package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.FluidInput;
import mekanism.common.recipe.machines.SeparatorRecipe;

import java.util.Map;

/**
 * One fluid input to the separator's two gas outputs.
 */
public final class CloudworksSeparatorRecipeAdapter extends CloudworksNativeRecipeAdapter<SeparatorRecipe> {
    private final boolean useRecipeEnergy;

    public CloudworksSeparatorRecipeAdapter(Map<FluidInput, ? extends SeparatorRecipe> recipes) {
        this(recipes, false);
    }

    /** Recipe-based accounting requires a zero base energy rate in the machine profile. */
    public CloudworksSeparatorRecipeAdapter(Map<FluidInput, ? extends SeparatorRecipe> recipes, boolean useRecipeEnergy) {
        super(recipes);
        this.useRecipeEnergy = useRecipeEnergy;
    }

    @Override
    protected CloudworksRecipeBatch describe(SeparatorRecipe recipe) {
        return new CloudworksRecipeBatch().fluid(recipe.getInput().ingredient)
                .output(recipe.getOutput().leftGas, "gas_output_left")
                .output(recipe.getOutput().rightGas, "gas_output_right")
                .extraEnergy(useRecipeEnergy ? recipe.energyUsage : 0);
    }
}

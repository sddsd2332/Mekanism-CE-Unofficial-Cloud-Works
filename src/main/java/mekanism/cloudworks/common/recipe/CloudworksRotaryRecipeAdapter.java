package mekanism.cloudworks.common.recipe;

import mekanism.common.recipe.inputs.RotaryInput;
import mekanism.common.recipe.machines.RotaryRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.cloudworks.api.recipe.CloudworksRecipeMode;
import mekanism.cloudworks.api.machine.MachineSlotLayout;

/**
 * Exposes each supported native rotary direction as an independent conversion.
 */
public final class CloudworksRotaryRecipeAdapter extends CloudworksNativeRecipeAdapter<RotaryRecipe> {
    public static final String FLUID_TO_GAS = "mekanism:fluid_to_gas";
    public static final String GAS_TO_FLUID = "mekanism:gas_to_fluid";
    private final boolean fluidToGas, gasToFluid;

    public CloudworksRotaryRecipeAdapter(Map<RotaryInput, ? extends RotaryRecipe> recipes) {
        this(recipes, true, true);
    }

    private CloudworksRotaryRecipeAdapter(Map<RotaryInput, ? extends RotaryRecipe> recipes,
            boolean fluidToGas, boolean gasToFluid) {
        super(recipes);
        this.fluidToGas = fluidToGas;
        this.gasToFluid = gasToFluid;
    }

    public static List<CloudworksRecipeMode> recipeTypes(Map<RotaryInput, ? extends RotaryRecipe> recipes) {
        return recipeTypes(recipes, 10000);
    }

    public static MachineSlotLayout fluidToGasLayout(int capacity) {
        return new MachineSlotLayout()
                .add("fluid_input", QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, capacity)
                .add("gas_output", QIOResourceCodecs.GAS_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, capacity);
    }

    public static List<CloudworksRecipeMode> recipeTypes(Map<RotaryInput, ? extends RotaryRecipe> recipes, int capacity) {
        return Arrays.asList(
                new CloudworksRecipeMode(FLUID_TO_GAS, 0, fluidToGasLayout(capacity),
                        new CloudworksRotaryRecipeAdapter(recipes, true, false)),
                new CloudworksRecipeMode(GAS_TO_FLUID, 1, new MachineSlotLayout()
                                .add("gas_input", QIOResourceCodecs.GAS_STACK_ID,
                                        MachineSlotLayout.PortRole.INPUT, capacity)
                                .add("fluid_output", QIOResourceCodecs.FLUID_STACK_ID,
                                        MachineSlotLayout.PortRole.OUTPUT, capacity),
                        new CloudworksRotaryRecipeAdapter(recipes, false, true)));
    }

    @Override
    protected List<RotaryRecipe> recipeVariants(RotaryRecipe recipe) {
        List<RotaryRecipe> directions = new ArrayList<>(2);
        if (fluidToGas && recipe.hasFluidToGas())
            directions.add(new RotaryRecipe(recipe.getInput().fluidInput.copy(), null,
                    recipe.getOutput().gasOutput.copy(), null));
        if (gasToFluid && recipe.hasGasToFluid())
            directions.add(new RotaryRecipe(null, recipe.getInput().gasInput.copy(),
                    null, recipe.getOutput().fluidOutput.copy()));
        return directions;
    }

    @Override
    protected CloudworksRecipeBatch describe(RotaryRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().unscaledOutputs();
        if (recipe.getInput().fluidInput != null) batch.fluid(recipe.getInput().fluidInput);
        if (recipe.getInput().gasInput != null)
            batch.gas(recipe.getInput().gasInput, recipe.getInput().gasInput.amount);
        if (recipe.getOutput().fluidOutput != null) batch.output(recipe.getOutput().fluidOutput);
        if (recipe.getOutput().gasOutput != null) batch.output(recipe.getOutput().gasOutput);
        return batch;
    }
}

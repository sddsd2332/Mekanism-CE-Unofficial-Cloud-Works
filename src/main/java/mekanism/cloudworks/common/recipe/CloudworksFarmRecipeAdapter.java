package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.common.recipe.inputs.FarmInput;
import mekanism.common.recipe.machines.FarmRecipe;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import mekanism.api.recipes.FarmChanceOutput;
import net.minecraftforge.fluids.FluidStack;

/**
 * Organic Farm adapter with deterministic, persisted per-operation chance outputs.
 */
public final class CloudworksFarmRecipeAdapter extends CloudworksNativeRecipeAdapter<FarmRecipe> {
    private final int secondaryUsage;
    private final Boolean gasMode;
    public CloudworksFarmRecipeAdapter(Map<FarmInput, ? extends FarmRecipe> recipes) {
        this(recipes, 1);
    }

    public CloudworksFarmRecipeAdapter(Map<FarmInput, ? extends FarmRecipe> recipes, int secondaryUsage) {
        this(recipes, secondaryUsage, null);
    }

    private CloudworksFarmRecipeAdapter(Map<FarmInput, ? extends FarmRecipe> recipes, int secondaryUsage, Boolean gasMode) {
        super(recipes);
        if (secondaryUsage <= 0) throw new IllegalArgumentException("Invalid farm secondary usage");
        this.secondaryUsage = secondaryUsage;
        this.gasMode = gasMode;
    }

    public static mekanism.cloudworks.api.machine.MachineSlotLayout layout(int processes, boolean gas) {
        return new mekanism.cloudworks.api.machine.MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                        mekanism.cloudworks.api.machine.MachineSlotLayout.PortRole.INPUT, Math.multiplyExact(64, processes))
                .add(gas ? "gas_input" : "fluid_input", gas ? QIOResourceCodecs.GAS_STACK_ID : QIOResourceCodecs.FLUID_STACK_ID,
                        mekanism.cloudworks.api.machine.MachineSlotLayout.PortRole.INPUT, Math.multiplyExact(1000, processes))
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                        mekanism.cloudworks.api.machine.MachineSlotLayout.PortRole.OUTPUT, 4096);
    }

    public static List<mekanism.cloudworks.api.recipe.CloudworksRecipeMode> recipeTypes(
            Map<FarmInput, ? extends FarmRecipe> recipes, int processes) {
        return java.util.Arrays.asList(
                new mekanism.cloudworks.api.recipe.CloudworksRecipeMode("mekanism:farm_gas", 0, layout(processes, true),
                        new CloudworksFarmRecipeAdapter(recipes, 200, true)),
                new mekanism.cloudworks.api.recipe.CloudworksRecipeMode("mekanism:farm_fluid", 1, layout(processes, false),
                        new CloudworksFarmRecipeAdapter(recipes, 200, false)));
    }

    @Override
    protected List<FarmRecipe> recipeVariants(FarmRecipe recipe) {
        return gasMode == null || recipe.getInput().isGasInput() == gasMode
                ? java.util.Collections.singletonList(recipe) : java.util.Collections.emptyList();
    }

    @Override
    protected CloudworksRecipeBatch describe(FarmRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().item(recipe.getInput().itemStack);
        if (recipe.getInput().isGasInput())
            batch.secondaryGasInputs().gas(recipe.getInput().gasInput, Math.multiplyExact((long) recipe.getInput().gasInput.amount, secondaryUsage));
        else {
            FluidStack fluid = recipe.getInput().fluidInput.copy();
            fluid.amount = Math.multiplyExact(fluid.amount, secondaryUsage);
            batch.secondaryFluidInputs().fluid(fluid);
        }
        // The guaranteed item is deterministic; every chance entry stays an
        // independently rolled per-operation output (up to 63 of them).
        batch.output(recipe.getOutput().getGuaranteedOutput());
        for (FarmChanceOutput chance : recipe.getOutput().getChanceOutputs())
            batch.chanceOutput(chance.getOutput(), chance.getChance());
        return batch;
    }

}

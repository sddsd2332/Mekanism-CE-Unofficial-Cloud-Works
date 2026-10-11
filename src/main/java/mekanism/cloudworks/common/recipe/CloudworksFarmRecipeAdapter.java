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

/**
 * Organic Farm adapter with deterministic, persisted per-operation chance outputs.
 */
public final class CloudworksFarmRecipeAdapter extends CloudworksNativeRecipeAdapter<FarmRecipe> {
    private final int secondaryUsage;
    public CloudworksFarmRecipeAdapter(Map<FarmInput, ? extends FarmRecipe> recipes) {
        this(recipes, 1);
    }

    public CloudworksFarmRecipeAdapter(Map<FarmInput, ? extends FarmRecipe> recipes, int secondaryUsage) {
        super(recipes);
        if (secondaryUsage <= 0) throw new IllegalArgumentException("Invalid farm secondary usage");
        this.secondaryUsage = secondaryUsage;
    }

    @Override
    protected CloudworksRecipeBatch describe(FarmRecipe recipe) {
        CloudworksRecipeBatch batch = new CloudworksRecipeBatch().item(recipe.getInput().itemStack);
        if (recipe.getInput().isGasInput())
            batch.gas(recipe.getInput().gasInput, Math.multiplyExact((long) recipe.getInput().gasInput.amount, secondaryUsage));
        else {
            net.minecraftforge.fluids.FluidStack fluid = recipe.getInput().fluidInput.copy();
            fluid.amount = Math.multiplyExact(fluid.amount, secondaryUsage);
            batch.fluid(fluid);
        }
        // The guaranteed item is deterministic; every chance entry stays an
        // independently rolled per-operation output (up to 63 of them).
        batch.output(recipe.getOutput().getGuaranteedOutput());
        for (mekanism.api.recipes.FarmChanceOutput chance : recipe.getOutput().getChanceOutputs())
            batch.chanceOutput(chance.getOutput(), chance.getChance());
        return batch;
    }

    @Override
    public CloudworksOperation createOperation(MachineNodeData node, Object value, int operations, int ticks) {
        if (node.operation() != null) return null;
        FarmRecipe recipe = registered(value);
        if (recipe == null) return null;
        CloudworksRecipeBatch batch = describe(recipe);
        Map<QIOResourceDescriptor, Long> allocated = batch.allocate(stock(node), operations);
        if (allocated == null) return null;
        List<CloudworksResourceStack> outputs = new ArrayList<>();
        // See CloudworksChanceItemRecipeAdapter: a constant seed made the roll per node.
        Random random = java.util.concurrent.ThreadLocalRandom.current();
        for (int i = 0; i < operations; i++)
            for (ItemStack output : recipe.getOutput().getOutputs(random)) {
                if (output.isEmpty()) continue;
                QIOResourceDescriptor descriptor = QIOResourceCodecs.item(output);
                CloudworksResourceStack existing = null;
                for (CloudworksResourceStack candidate : outputs)
                    if (candidate.descriptor().equals(descriptor)) {
                        existing = candidate;
                        break;
                    }
                if (existing == null) outputs.add(new CloudworksResourceStack(descriptor, output.getCount()));
                else existing.add(output.getCount());
            }
        if (!CloudworksResourceCapacity.canOutput(node, outputs)) return null;
        List<CloudworksResourceStack> inputs = new ArrayList<>();
        for (Map.Entry<QIOResourceDescriptor, Long> entry : allocated.entrySet())
            inputs.add(new CloudworksResourceStack(entry.getKey(), entry.getValue()));
        return new CloudworksOperation(operations, Math.max(1, ticks), inputs, outputs);
    }
}

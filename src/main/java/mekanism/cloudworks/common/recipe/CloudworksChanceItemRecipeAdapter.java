package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.ChanceMachineRecipe;
import mekanism.common.recipe.outputs.ChanceOutput;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Chance output is rolled once per operation when the operation is created and persisted in its output promise.
 */
public final class CloudworksChanceItemRecipeAdapter implements CloudworksMachineAdapter,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private final Map<ItemStackInput, ? extends ChanceMachineRecipe<?>> recipes;

    public CloudworksChanceItemRecipeAdapter(Map<ItemStackInput, ? extends ChanceMachineRecipe<?>> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    /**
     * Owner-thread freeze for the async worker path: deterministic primary output
     * plus the secondary as an independently rolled per-operation chance entry.
     */
    public Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksFrozenRecipeView.Entry> captured = new ArrayList<>();
            for (ChanceMachineRecipe<?> recipe : recipes.values()) {
                ChanceOutput output = recipe.getOutput();
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch().item(recipe.getInput().ingredient);
                if (output.hasPrimary()) batch.output(output.getMainOutput());
                ItemStack secondary = output.getMaxSecondaryOutput();
                if (!secondary.isEmpty()) batch.chanceOutput(secondary, output.secondaryChance);
                Optional<CloudworksRecipeBatch.FrozenView> frozen = batch.freeze();
                if (!frozen.isPresent()) return null;
                captured.add(new CloudworksFrozenRecipeView.Entry(frozen.get(), 0));
            }
            captured.sort(java.util.Comparator.comparing(CloudworksFrozenRecipeView::signature));
            return captured;
        });
    }

    @Override
    public Object preview(MachineNodeData node) {
        List<CloudworksResourceStack> values = node.operation() == null ? node.inputs() : node.operation().inputs();
        for (CloudworksResourceStack stack : values) {
            ItemStack item = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (item == null) continue;
            for (ChanceMachineRecipe<?> recipe : recipes.values())
                if (ItemStackInput.inputContains(item, recipe.getInput().ingredient)) return recipe;
        }
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node, Object recipe) {
        return recipe instanceof ChanceMachineRecipe && preview(node) == recipe;
    }

    @Override
    public boolean reserve(MachineNodeData node, Object recipe, int operations) {
        CloudworksOperation operation = createOperation(node, recipe, operations, 200);
        return operation != null && node.beginOperation(operation);
    }

    @Override
    public void advance(MachineNodeData node, Object recipe, int operations) {
    }

    @Override
    public CloudworksOperation createOperation(MachineNodeData node, Object value, int operations, int duration) {
        if (!(value instanceof ChanceMachineRecipe<?> recipe)) return null;
        long required = Math.multiplyExact((long) recipe.getInput().ingredient.getCount(), operations);
        List<CloudworksResourceStack> inputStacks = new ArrayList<>();
        long remaining = required;
        for (CloudworksResourceStack stack : node.inputs()) {
            ItemStack item = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (item == null || !ItemStackInput.inputContains(item, recipe.getInput().ingredient)) continue;
            long take = Math.min(remaining, stack.available());
            if (take > 0) {
                inputStacks.add(new CloudworksResourceStack(stack.descriptor(), take));
                remaining -= take;
            }
            if (remaining == 0) break;
        }
        if (remaining > 0) return null;
        List<CloudworksResourceStack> outputs = new ArrayList<>();
        ItemStack primary = recipe.getOutput().getMainOutput();
        if (!primary.isEmpty())
            outputs.add(new CloudworksResourceStack(QIOResourceCodecs.item(primary), (long) primary.getCount() * operations));
        // The secondary output is rolled once per operation, exactly like vanilla's
        // per-craft roll. The old single batch roll made a 1000-operation batch
        // produce either 1000 secondaries or none at all, and reserved capacity for
        // the worst case on every batch.
        ItemStack secondary = recipe.getOutput().getMaxSecondaryOutput();
        if (!secondary.isEmpty()) {
            Random random = java.util.concurrent.ThreadLocalRandom.current();
            long hits = 0;
            for (int i = 0; i < operations; i++)
                if (recipe.getOutput().checkSecondary(random)) hits++;
            if (hits > 0)
                outputs.add(new CloudworksResourceStack(QIOResourceCodecs.item(secondary), hits * secondary.getCount()));
        }
        if (!CloudworksResourceCapacity.canOutput(node, outputs)) return null;
        return new CloudworksOperation(operations, Math.max(1, duration), inputStacks, outputs);
    }

    @Override
    public boolean complete(MachineNodeData node, Object value, int operations) {
        return node.operation() != null
                && CloudworksResourceCapacity.canOutput(node, node.operation().outputs())
                && node.completeOperation(node.operation().id());
    }

    @Override
    public void cancel(MachineNodeData node, Object recipe) {
        node.cancelOperation();
    }

    @Override
    public void restore(MachineNodeData node, Object recipe) {
    }
}

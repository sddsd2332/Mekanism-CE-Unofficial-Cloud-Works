package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.common.recipe.inputs.ItemStackInput;
import mekanism.common.recipe.machines.Chance2MachineRecipe;
import net.minecraft.item.ItemStack;

import java.util.*;

/**
 * Chance2 (single optional primary output) with persisted, one-time per-operation batch rolls.
 */
public final class CloudworksChance2ItemRecipeAdapter implements CloudworksMachineAdapter,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private final Map<ItemStackInput, ? extends Chance2MachineRecipe<?>> recipes;

    public CloudworksChance2ItemRecipeAdapter(Map<ItemStackInput, ? extends Chance2MachineRecipe<?>> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    /**
     * Owner-thread freeze for the async worker path: the primary output becomes an
     * independently rolled per-operation chance entry (there is no deterministic
     * output — an operation may legitimately produce nothing).
     */
    public Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksFrozenRecipeView.Entry> captured = new ArrayList<>();
            for (Chance2MachineRecipe<?> recipe : recipes.values()) {
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch().item(recipe.getInput().ingredient);
                ItemStack primary = recipe.getOutput().getMaxPrimaryOutput();
                if (!primary.isEmpty()) batch.chanceOutput(primary, recipe.getOutput().primaryChance);
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
            for (Chance2MachineRecipe<?> recipe : recipes.values())
                if (ItemStackInput.inputContains(item, recipe.getInput().ingredient)) return recipe;
        }
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node, Object recipe) {
        return recipe instanceof Chance2MachineRecipe && preview(node) == recipe;
    }

    @Override
    public CloudworksOperation createOperation(MachineNodeData node, Object value, int operations, int duration) {
        if (!(value instanceof Chance2MachineRecipe<?> recipe)) return null;
        long required = Math.multiplyExact((long) recipe.getInput().ingredient.getCount(), operations);
        List<CloudworksResourceStack> input = new ArrayList<>();
        long remaining = required;
        for (CloudworksResourceStack stack : node.inputs()) {
            ItemStack item = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (item == null || !ItemStackInput.inputContains(item, recipe.getInput().ingredient)) continue;
            long take = Math.min(remaining, stack.available());
            if (take > 0) {
                input.add(new CloudworksResourceStack(stack.descriptor(), take));
                remaining -= take;
            }
            if (remaining == 0) break;
        }
        if (remaining > 0) return null;
        // See CloudworksChanceItemRecipeAdapter: a constant seed made the roll per node.
        Random random = java.util.concurrent.ThreadLocalRandom.current();
        ItemStack primary = ItemStack.EMPTY;
        for (int i = 0; i < operations; i++) {
            ItemStack result = recipe.getOutput().getPrimaryOutput(random);
            if (!result.isEmpty()) {
                if (primary.isEmpty()) primary = result.copy();
                else primary.grow(result.getCount());
            }
        }
        List<CloudworksResourceStack> output = primary.isEmpty() ? Collections.emptyList()
                : Collections.singletonList(new CloudworksResourceStack(QIOResourceCodecs.item(primary), primary.getCount()));
        if (!CloudworksResourceCapacity.canOutput(node, output)) return null;
        return new CloudworksOperation(operations, Math.max(1, duration), input, output);
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
    public boolean complete(MachineNodeData node, Object recipe, int operations) {
        return node.operation() != null && CloudworksResourceCapacity.canOutput(node, node.operation().outputs())
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

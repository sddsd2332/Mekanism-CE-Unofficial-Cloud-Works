package mekanism.cloudworks.common.recipe;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.common.recipe.inputs.IntegerInput;
import mekanism.common.recipe.machines.AmbientGasRecipe;

import java.util.*;

/**
 * Dimension-driven ambient gas machine; no external input is consumed.
 */
public final class CloudworksAmbientGasRecipeAdapter implements CloudworksMachineAdapter,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private final Map<IntegerInput, ? extends AmbientGasRecipe> recipes;

    public CloudworksAmbientGasRecipeAdapter(Map<IntegerInput, ? extends AmbientGasRecipe> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    /**
     * Owner-thread freeze for the async worker path: the recipe consumes nothing
     * and is selected by dimension, so each entry carries its dimension gate; the
     * gas output is an independently rolled per-operation chance entry.
     */
    public Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksFrozenRecipeView.Entry> captured = new ArrayList<>();
            for (AmbientGasRecipe recipe : recipes.values()) {
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch();
                GasStack output = recipe.getOutput().getMaxOutput();
                if (output != null && output.amount > 0)
                    batch.chanceOutput(output, recipe.getOutput().primaryChance);
                Optional<CloudworksRecipeBatch.FrozenView> frozen = batch.freeze();
                if (!frozen.isPresent()) return null;
                captured.add(new CloudworksFrozenRecipeView.Entry(frozen.get(), 0,
                        recipe.getInput().ingredient));
            }
            captured.sort(java.util.Comparator.comparing(CloudworksFrozenRecipeView::signature));
            return captured;
        });
    }

    @Override
    public Object preview(MachineNodeData node) {
        for (AmbientGasRecipe recipe : recipes.values())
            if (recipe.getInput().ingredient == node.dimensionId()) return recipe;
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node, Object recipe) {
        return recipe instanceof AmbientGasRecipe && preview(node) == recipe;
    }

    @Override
    public CloudworksOperation createOperation(MachineNodeData node, Object value, int operations, int duration) {
        if (!(value instanceof AmbientGasRecipe recipe)) return null;
        // See CloudworksChanceItemRecipeAdapter: a constant seed made the roll per node.
        Random random = java.util.concurrent.ThreadLocalRandom.current();
        Map<QIOResourceDescriptor, Long> outputs = new LinkedHashMap<>();
        for (int i = 0; i < operations; i++) {
            GasStack result = recipe.getOutput().getOutput(random);
            if (result != null && result.amount > 0)
                outputs.merge(QIOResourceCodecs.gas(result), (long) result.amount, Math::addExact);
        }
        List<CloudworksResourceStack> output = new ArrayList<>();
        for (Map.Entry<QIOResourceDescriptor, Long> entry : outputs.entrySet())
            output.add(new CloudworksResourceStack(entry.getKey(), entry.getValue()));
        if (!CloudworksResourceCapacity.canOutput(node, output)) return null;
        return new CloudworksOperation(operations, Math.max(1, duration), Collections.emptyList(), output);
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
        CloudworksOperation operation = node.operation();
        return operation != null && CloudworksResourceCapacity.canOutput(node, operation.outputs()) && node.completeOperation(operation.id());
    }

    @Override
    public void cancel(MachineNodeData node, Object recipe) {
        node.cancelOperation();
    }

    @Override
    public void restore(MachineNodeData node, Object recipe) {
    }
}

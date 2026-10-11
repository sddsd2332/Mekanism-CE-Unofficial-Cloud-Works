package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.common.recipe.ItemStackToEnergyRecipe;
import mekanism.common.recipe.inputs.ItemStackInput;
import net.minecraft.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Special adapter for generator modules whose fuel is an item recipe. No
 * machine registers it yet; the fuel contract below exists so a future
 * registration is transaction-safe from day one (contract first, wiring later).
 */
public final class CloudworksItemGeneratorAdapter implements SpecialMachineAdapter, CloudworksEnergyGenerator,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private final Map<ItemStackInput, ? extends ItemStackToEnergyRecipe> recipes;

    @Override
    public mekanism.cloudworks.api.CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            mekanism.cloudworks.api.CloudworksGenerationContext context) {
        return mekanism.cloudworks.api.CloudworksGenerationPlan.constant(0);
    }

    public CloudworksItemGeneratorAdapter(Map<ItemStackInput, ? extends ItemStackToEnergyRecipe> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    /**
     * Fuel contract freeze, mirroring the gas generator: one item-fuel entry per recipe.
     */
    @Override
    public java.util.Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksFrozenRecipeView.Entry> captured = new java.util.ArrayList<>();
            for (ItemStackToEnergyRecipe recipe : recipes.values()) {
                ItemStack ingredient = recipe.getInput().ingredient;
                double energy = recipe.getOutput().energyOutput;
                if (ingredient == null || ingredient.isEmpty() || !Double.isFinite(energy) || energy <= 0D)
                    return null;
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch()
                        .item(ingredient)
                        .energy(energy);
                java.util.Optional<CloudworksRecipeBatch.FrozenView> frozen = batch.freeze();
                if (!frozen.isPresent()) return null;
                captured.add(new CloudworksFrozenRecipeView.Entry(frozen.get(), 0));
            }
            if (captured.isEmpty()) return null;
            captured.sort(java.util.Comparator.comparing(CloudworksFrozenRecipeView::signature));
            return captured;
        });
    }

    @Override
    public Object preview(MachineNodeData node) {
        for (CloudworksResourceStack stack : node.inputs()) {
            ItemStack item = stack.descriptor().resolve(QIOResourceCodecs.ITEM_STACK);
            if (item == null) continue;
            for (ItemStackToEnergyRecipe recipe : recipes.values())
                if (ItemStackInput.inputContains(item, recipe.getInput().ingredient)) return recipe;
        }
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node) {
        return preview(node) != null;
    }

    @Override
    public boolean canStart(MachineNodeData node, int operations) {
        Object selected = preview(node);
        if (!(selected instanceof ItemStackToEnergyRecipe recipe) || operations <= 0) return false;
        return node.inputAmountForItem(recipe.getInput().ingredient) >= (long) recipe.getInput().ingredient.getCount() * operations;
    }

    @Override
    public boolean reserve(MachineNodeData node) {
        return true;
    }

    @Override
    public void advance(MachineNodeData node, int operations) {
        Object selected = preview(node);
        if (selected instanceof ItemStackToEnergyRecipe recipe)
            node.consumeItem(recipe.getInput().ingredient, (long) recipe.getInput().ingredient.getCount() * operations);
    }

    @Override
    public boolean complete(MachineNodeData node) {
        return true;
    }

    @Override
    public void cancel(MachineNodeData node) {
    }

    @Override
    public void restore(MachineNodeData node) {
    }

    @Override
    public double generationPerTick(MachineNodeData node, int operations) {
        Object selected = preview(node);
        if (!(selected instanceof ItemStackToEnergyRecipe recipe) || operations <= 0) return 0D;
        long available = node.inputAmountForItem(recipe.getInput().ingredient);
        long possible = Math.min(operations, available / Math.max(1, recipe.getInput().ingredient.getCount()));
        return Math.max(0D, recipe.getOutput().energyOutput * possible);
    }
}

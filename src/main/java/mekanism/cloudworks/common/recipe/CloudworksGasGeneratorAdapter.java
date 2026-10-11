package mekanism.cloudworks.common.recipe;

import mekanism.api.gas.GasStack;
import mekanism.api.qio.resource.QIOResourceCodecs;
import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.common.recipe.GasStackFuelToEnergyRecipe;
import mekanism.common.recipe.inputs.GasInput;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cloud generator adapter: gas fuel is consumed from the node cache and the
 * recipe's energy output is reported to the shared energy settlement. The
 * virtual path
 * freezes the fuel contract (fuel per operation + energy per operation) and
 * the worker burns actual fuel for actual generation.
 */
public final class CloudworksGasGeneratorAdapter implements SpecialMachineAdapter, CloudworksEnergyGenerator,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private final Map<GasInput, ? extends GasStackFuelToEnergyRecipe> recipes;

    public CloudworksGasGeneratorAdapter(Map<GasInput, ? extends GasStackFuelToEnergyRecipe> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    /**
     * Fuel contract freeze (GPT design §3.1): each fuel recipe becomes one
     * single-input entry — per-operation fuel is the recipe's gas amount and
     * per-operation energy is its energy output. Workers burn stock-bounded
     * and report actual generation; no resource outputs are promised.
     */
    @Override
    public java.util.Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        return CloudworksFrozenRecipeView.capture(() -> {
            List<CloudworksFrozenRecipeView.Entry> captured = new java.util.ArrayList<>();
            for (GasStackFuelToEnergyRecipe recipe : recipes.values()) {
                mekanism.api.gas.GasStack ingredient = recipe.getInput().ingredient;
                double energy = recipe.getOutput().energyOutput;
                if (ingredient == null || ingredient.amount <= 0 || !Double.isFinite(energy) || energy <= 0D)
                    return null;   // unfreezable fuel recipe: refuse the whole contract set
                CloudworksRecipeBatch batch = new CloudworksRecipeBatch()
                        .gas(ingredient, ingredient.amount)
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
            GasStack gas = stack.descriptor().resolve(QIOResourceCodecs.GAS_STACK);
            if (gas == null) continue;
            for (GasStackFuelToEnergyRecipe recipe : recipes.values())
                if (recipe.getInput().ingredient.isGasEqual(gas)) return recipe;
        }
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node) {
        return preview(node) != null;
    }

    @Override
    public boolean canStart(MachineNodeData node, int operations) {
        if (operations <= 0) return false;
        Object selected = preview(node);
        if (!(selected instanceof GasStackFuelToEnergyRecipe recipe)) return false;
        return node.inputAmountForGas(recipe.getInput().ingredient) >= (long) recipe.getInput().ingredient.amount * operations;
    }

    @Override
    public boolean reserve(MachineNodeData node) {
        return true;
    }

    @Override
    public void advance(MachineNodeData node, int operations) {
        Object selected = preview(node);
        if (!(selected instanceof GasStackFuelToEnergyRecipe recipe)) return;
        node.consumeGas(recipe.getInput().ingredient, (long) recipe.getInput().ingredient.amount * operations);
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
        if (!(selected instanceof GasStackFuelToEnergyRecipe recipe) || operations <= 0) return 0D;
        long available = node.inputAmountForGas(recipe.getInput().ingredient);
        long possible = Math.min(operations, available / Math.max(1, recipe.getInput().ingredient.amount));
        return Math.max(0D, recipe.getOutput().energyOutput * possible);
    }

    @Override
    public mekanism.cloudworks.api.CloudworksGenerationPlan freezeGeneration(MachineNodeData node, int operations,
            mekanism.cloudworks.api.CloudworksGenerationContext context) {
        // The fuel contract is the sole source of generation for this machine.
        return mekanism.cloudworks.api.CloudworksGenerationPlan.constant(0);
    }
}

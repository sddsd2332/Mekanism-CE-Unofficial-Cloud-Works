package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.cloudworks.api.*;
import mekanism.cloudworks.api.card.*;
import mekanism.cloudworks.api.environment.*;
import mekanism.cloudworks.api.machine.*;
import mekanism.cloudworks.api.module.*;
import mekanism.cloudworks.api.node.*;
import mekanism.cloudworks.api.recipe.*;
import mekanism.cloudworks.api.transfer.*;
import mekanism.cloudworks.api.work.*;
import mekanism.common.recipe.inputs.IntegerInput;
import mekanism.common.recipe.machines.AmbientGasRecipe;
import net.minecraftforge.common.DimensionManager;

public final class CloudworksAmbientGasRecipeAdapter implements CloudworksMachineAdapter {
    private final Map<IntegerInput, ? extends AmbientGasRecipe> recipes;

    public CloudworksAmbientGasRecipeAdapter(Map<IntegerInput, ? extends AmbientGasRecipe> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    @Override
    public List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (AmbientGasRecipe recipe : recipes.values()) {
            // Resolve native numeric keys only during lifecycle freezing. Runtime
            // receives the stable environment axis and never a node/world dimension.
            int nativeDimension = recipe.getInput().ingredient;
            if (!DimensionManager.isDimensionRegistered(nativeDimension)) continue;
            EnvironmentAxis axis = DimensionAxes.byType(DimensionManager.getProviderType(nativeDimension));
            if (axis == null) continue;
            CloudworksRecipeSpec base = new CloudworksRecipeBatch()
                    .chanceOutput(recipe.getOutput().getMaxOutput(), recipe.getOutput().primaryChance)
                    .specification("ambient_" + nativeDimension, 0);
            result.add(base.toBuilder(base.id()).environmentAxis(axis.id()).build());
        }
        result.sort(Comparator.comparing(CloudworksRecipeSpec::id));
        return Collections.unmodifiableList(result);
    }
}

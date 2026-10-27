package mekanism.cloudworks.common.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Steady-state resource contracts, never a live multiblock simulation. */
public abstract class CloudworksMultiblockRecipeAdapter extends CloudworksNativeRecipeAdapter<CloudworksContractRecipeAdapter.Contract> {
    protected CloudworksMultiblockRecipeAdapter(List<CloudworksContractRecipeAdapter.Contract> recipes) {
        super(index(recipes));
    }

    private static Map<String, CloudworksContractRecipeAdapter.Contract> index(
            List<CloudworksContractRecipeAdapter.Contract> recipes) {
        Map<String, CloudworksContractRecipeAdapter.Contract> result = new LinkedHashMap<>();
        for (CloudworksContractRecipeAdapter.Contract recipe : recipes)
            if (result.putIfAbsent(recipe.id, recipe) != null) throw new IllegalArgumentException("Duplicate multiblock recipe");
        return result;
    }

    @Override
    protected CloudworksRecipeBatch describe(CloudworksContractRecipeAdapter.Contract recipe) { return recipe.batch; }

    @Override
    protected int duration(CloudworksContractRecipeAdapter.Contract recipe, int fallback) { return recipe.ticks; }

    @Override protected String recipeIdentity(CloudworksContractRecipeAdapter.Contract recipe) { return recipe.id; }
}

package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.api.recipe.CloudworksRecipeSpec;
import mekanism.common.recipe.RecipeHandler;

/** Lifecycle-only native recipe reader; no native object enters Runtime. */
public abstract class CloudworksNativeRecipeAdapter<R> implements CloudworksMachineAdapter {
    private static final int MAX_RECIPES = 10_000;
    protected final Map<?, ? extends R> recipes;

    protected CloudworksNativeRecipeAdapter(Map<?, ? extends R> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    protected abstract CloudworksRecipeBatch describe(R recipe);
    protected boolean supports(R recipe) { return true; }
    protected List<R> recipeVariants(R recipe) { return Collections.singletonList(recipe); }
    protected int duration(R recipe, int fallback) { return fallback; }
    protected String recipeIdentity(R recipe) { return ""; }

    @Override
    public final List<CloudworksRecipeSpec> freeze(CloudworksMachineRegistration registration) {
        int version = RecipeHandler.getGlobalRecipeVersion();
        if (recipes.size() > MAX_RECIPES) throw new IllegalArgumentException("Too many native recipes");
        List<CloudworksRecipeSpec> result = new ArrayList<>();
        for (R original : recipes.values()) {
            if (!supports(original)) continue;
            for (R recipe : recipeVariants(original)) {
                try {
                    String id = recipeIdentity(recipe);
                    result.add(describe(recipe).specification(id.isEmpty() ? "native_" + result.size() : id,
                            Math.max(0, duration(recipe, 0))));
                } catch (IllegalArgumentException unsupported) {
                    // Unsupported ingredients reject one recipe, not the entire native manager.
                }
                if (result.size() > MAX_RECIPES) throw new IllegalArgumentException("Too many native recipe variants");
            }
        }
        result.sort(Comparator.comparing(recipe -> {
            StringBuilder key = new StringBuilder();
            for (CloudworksRecipeSpec.Output output : recipe.outputs())
                key.append(output.descriptor()).append(':').append(output.amount()).append('@').append(output.port());
            return key.toString();
        }));
        if (version != RecipeHandler.getGlobalRecipeVersion())
            throw new IllegalStateException("Native recipes changed during lifecycle freeze");
        return Collections.unmodifiableList(result);
    }
}

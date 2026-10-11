package mekanism.cloudworks.common.recipe;

import mekanism.api.qio.resource.QIOResourceDescriptor;
import mekanism.cloudworks.api.CloudworksMachineAdapter;
import mekanism.cloudworks.common.data.CloudworksOperation;
import mekanism.cloudworks.common.data.CloudworksResourceStack;
import mekanism.cloudworks.common.data.MachineNodeData;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.cloudworks.common.registry.MachineExecutionProfile;
import mekanism.cloudworks.common.service.CloudworksResourceCapacity;
import mekanism.common.recipe.RecipeHandler;

import java.util.*;

/**
 * All native recipes share the same quantity checks, staging and reservation semantics.
 */
public abstract class CloudworksNativeRecipeAdapter<R> implements CloudworksMachineAdapter,
        CloudworksAsyncRecipeAdmission.FreezableView {
    private static final int MAX_FROZEN_RECIPES = 10_000;
    protected final Map<?, ? extends R> recipes;
    private int indexedVersion = -1;
    private List<R> indexedRecipes = Collections.emptyList();
    private Set<R> indexedIdentity = Collections.newSetFromMap(new IdentityHashMap<>());

    protected CloudworksNativeRecipeAdapter(Map<?, ? extends R> recipes) {
        this.recipes = Objects.requireNonNull(recipes);
    }

    protected abstract CloudworksRecipeBatch describe(R recipe);

    protected boolean supports(R recipe) { return true; }

    protected List<R> recipeVariants(R recipe) {
        return Collections.singletonList(recipe);
    }

    protected int duration(R recipe, int fallback) {
        return fallback;
    }

    /**
     * Called on the owning server thread; no native recipe escapes in the result.
     */
    public final Optional<CloudworksFrozenRecipeView> captureFrozenView() {
        int version = RecipeHandler.getGlobalRecipeVersion();
        if (recipes.size() > MAX_FROZEN_RECIPES) return Optional.empty();
        List<CloudworksFrozenRecipeView.Entry> captured = new ArrayList<>();
        try {
            List<R> candidates = orderedRecipes();
            if (candidates.size() > MAX_FROZEN_RECIPES) return Optional.empty();
            for (R recipe : candidates) {
                CloudworksRecipeBatch batch = describe(recipe);
                Optional<CloudworksRecipeBatch.FrozenView> frozen = batch.freeze();
                // An unsupported matcher belongs to this recipe, not every other recipe of the machine.
                if (!frozen.isPresent()) continue;
                int fixedDuration = duration(recipe, 0);
                captured.add(new CloudworksFrozenRecipeView.Entry(frozen.get(), Math.max(0, fixedDuration)));
            }
        } catch (RuntimeException | LinkageError unsafe) {
            return Optional.empty();
        }
        if (version != RecipeHandler.getGlobalRecipeVersion()) return Optional.empty();
        if (!indexedRecipes.isEmpty() && captured.isEmpty()) return Optional.empty();
        return Optional.of(new CloudworksFrozenRecipeView(version, captured));
    }

    protected R registered(Object candidate) {
        if (candidate == null) return null;
        orderedRecipes();
        return indexedIdentity.contains(candidate) ? (R) candidate : null;
    }

    protected Map<QIOResourceDescriptor, Long> stock(MachineNodeData node) {
        Map<QIOResourceDescriptor, Long> result = new TreeMap<>();
        for (CloudworksResourceStack stack : node.inputs())
            if (stack.available() > 0 && stack.descriptor().isResolved())
                result.merge(stack.descriptor(), stack.available(), Math::addExact);
        return result;
    }

    private Map<QIOResourceDescriptor, Long> configuration(MachineNodeData node) {
        Map<QIOResourceDescriptor, Long> result = new TreeMap<>();
        for (CloudworksResourceStack template : node.templates())
            result.merge(template.descriptor(), template.amount(), Math::addExact);
        return result;
    }

    private boolean matches(MachineNodeData node, R recipe, Map<QIOResourceDescriptor, Long> stock, int operations) {
        CloudworksRecipeBatch batch = describe(recipe);
        return batch.matchesConfiguration(configuration(node)) && batch.allocate(stock, operations) != null;
    }

    private List<R> orderedRecipes() {
        int version = RecipeHandler.getGlobalRecipeVersion();
        if (indexedVersion == version) return indexedRecipes;
        // Map iteration order must not decide which native recipe is started.
        List<R> result = new ArrayList<>();
        for (R recipe : recipes.values()) if (supports(recipe)) result.addAll(recipeVariants(recipe));
        result.sort(Comparator.comparing(recipe -> {
            List<CloudworksResourceStack> output = describe(recipe).outputs(1);
            StringBuilder key = new StringBuilder();
            for (CloudworksResourceStack stack : output) key.append(stack.write());
            return key.toString();
        }));
        Set<R> identity = Collections.newSetFromMap(new IdentityHashMap<>());
        identity.addAll(result);
        indexedRecipes = Collections.unmodifiableList(result);
        indexedIdentity = identity;
        indexedVersion = version;
        return indexedRecipes;
    }

    @Override
    public Object preview(MachineNodeData node) {
        int version = RecipeHandler.getGlobalRecipeVersion();
        if (node.operation() != null) {
            Map<QIOResourceDescriptor, Long> required = new TreeMap<>();
            for (CloudworksResourceStack stack : node.operation().inputs())
                required.merge(stack.descriptor(), stack.amount(), Math::addExact);
            R cached = registered(node.cachedRecipe(this, version));
            if (cached != null && matches(node, cached, required, node.operation().operations()))
                return cached;
            for (R recipe : orderedRecipes())
                if (matches(node, recipe, required, node.operation().operations())) return recipe;
            return null;
        }
        Map<QIOResourceDescriptor, Long> available = stock(node);
        R cached = registered(node.cachedRecipe(this, version, available));
        if (cached != null && matches(node, cached, available, 1)) return cached;
        for (R recipe : orderedRecipes())
            if (matches(node, recipe, available, 1)) {
                node.cacheRecipe(this, version, recipe, available);
                return recipe;
            }
        node.clearRecipeCache(this);
        return null;
    }

    @Override
    public boolean canStart(MachineNodeData node, Object recipe) {
        if (node.operation() != null) return registered(recipe) != null && preview(node) == recipe;
        R nativeRecipe = registered(recipe);
        return nativeRecipe != null && matches(node, nativeRecipe, stock(node), 1);
    }

    @Override
    public CloudworksOperation createOperation(MachineNodeData node, Object recipe, int operations, int ticks) {
        if (node.operation() != null) return null;
        R nativeRecipe = registered(recipe);
        if (nativeRecipe == null) return null;
        CloudworksRecipeBatch batch = describe(nativeRecipe);
        Map<QIOResourceDescriptor, Long> allocated = batch.allocate(stock(node), operations);
        List<CloudworksResourceStack> output = batch.outputs(operations);
        if (allocated == null || !batch.matchesConfiguration(configuration(node))
                || !CloudworksResourceCapacity.canOutput(node, output)) return null;
        List<CloudworksResourceStack> input = new ArrayList<>();
        allocated.forEach((type, amount) -> input.add(new CloudworksResourceStack(type, amount)));
        return new CloudworksOperation(operations, Math.max(1, duration(nativeRecipe, ticks)), input, output,
                batch.extraEnergyPerTick());
    }

    @Override
    public boolean reserve(MachineNodeData node, Object recipe, int operations) {
        if (node.operation() != null) return false;
        MachineExecutionProfile profile = CloudworksRegistries.profile(node.machine());
        CloudworksOperation operation = createOperation(node, recipe, operations, profile == null ? 200 : profile.totalTicks());
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
        if (node.operation() == null) throw new IllegalStateException("Missing running operation");
    }
}

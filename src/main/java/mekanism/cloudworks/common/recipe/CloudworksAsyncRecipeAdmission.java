package mekanism.cloudworks.common.recipe;

import java.util.*;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.registry.CloudworksMachineAdapters;
import mekanism.common.recipe.RecipeHandler;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;

/** Lifecycle compiler cache. The only executable addon contract is the public freeze API. */
public final class CloudworksAsyncRecipeAdmission {
    private static final Map<CloudworksMachineAdapter, Optional<CloudworksFrozenRecipeView>> FROZEN =
            new IdentityHashMap<>();
    private static boolean complete;
    private static Thread owner;

    private CloudworksAsyncRecipeAdmission() {}

    public static synchronized Optional<CloudworksFrozenRecipeView> capture(CloudworksMachineAdapter adapter) {
        if (adapter == null) return Optional.empty();
        Optional<CloudworksFrozenRecipeView> cached = FROZEN.get(adapter);
        if (cached != null && (!cached.isPresent() || current(cached.get()))) return cached;
        if (complete || CloudworksNativeContracts.isWorker()
                || owner != null && owner != Thread.currentThread()) return Optional.empty();
        Optional<CloudworksFrozenRecipeView> view = CloudworksRecipeCompiler.capture(
                adapter, CloudworksMachineAdapters.registration(adapter));
        FROZEN.put(adapter, view);
        return view;
    }

    /** Reads an already frozen contract without invoking native adapters or registry callbacks. */
    public static synchronized Optional<CloudworksFrozenRecipeView> peek(CloudworksMachineAdapter adapter) {
        Optional<CloudworksFrozenRecipeView> cached = FROZEN.get(adapter);
        return cached != null && cached.isPresent() && current(cached.get()) ? cached : Optional.empty();
    }

    /** Lifecycle-only derived data; never reopens arbitrary adapter callbacks on Runtime workers. */
    public static synchronized CloudworksFrozenRecipeView admitDerived(CloudworksMachineAdapter adapter,
            CloudworksMachineRegistration registration) {
        if (CloudworksNativeContracts.isWorker())
            throw new IllegalStateException("Derived contracts cannot freeze on Runtime workers");
        CloudworksFrozenRecipeView view = CloudworksRecipeCompiler.capture(adapter, registration)
                .orElseThrow(() -> new IllegalArgumentException("Mechanism recipe freeze refused"));
        FROZEN.put(adapter, Optional.of(view));
        return view;
    }

    public static synchronized void freezeRegisteredAdapters() {
        if (complete) return;
        CloudworksPumpRecipes.freeze();
        owner = Thread.currentThread();
        for (List<CloudworksMachineAdapter> adapters : CloudworksMachineAdapters.snapshot().values())
            for (CloudworksMachineAdapter adapter : adapters) capture(adapter);
        complete = true;
    }

    public static String rejectionReason(CloudworksMachineAdapter adapter) {
        return adapter == null ? "no recipe adapter" : "API recipe freeze refused: " + adapter.getClass().getName();
    }

    public static boolean current(CloudworksFrozenRecipeView view) {
        return view != null && view.sourceVersion() == RecipeHandler.getGlobalRecipeVersion();
    }

    public static synchronized Object checkpoint() {
        return new Snapshot(new IdentityHashMap<>(FROZEN), complete, owner, CloudworksPumpRecipes.checkpoint());
    }

    public static synchronized void restore(Object checkpoint) {
        Snapshot snapshot = (Snapshot) checkpoint;
        FROZEN.clear();
        FROZEN.putAll(snapshot.views);
        complete = snapshot.complete;
        owner = snapshot.owner;
        CloudworksPumpRecipes.restore(snapshot.pumps);
    }

    public static synchronized void resetForTests() {
        FROZEN.clear();
        complete = false;
        owner = null;
        CloudworksPumpRecipes.resetForTests();
    }
    static synchronized void invalidatePumpAdapters() {
        if (complete) throw new IllegalStateException("Pump recipe admission is frozen");
        FROZEN.keySet().removeIf(adapter -> adapter instanceof CloudworksPumpRecipeAdapter);
    }

    private static final class Snapshot {
        final Map<CloudworksMachineAdapter, Optional<CloudworksFrozenRecipeView>> views;
        final boolean complete;
        final Thread owner;
        final Object pumps;
        Snapshot(Map<CloudworksMachineAdapter, Optional<CloudworksFrozenRecipeView>> views,
                boolean complete, Thread owner, Object pumps) {
            this.views = views;
            this.complete = complete;
            this.owner = owner;
            this.pumps = pumps;
        }
    }
}

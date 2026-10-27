package mekanism.cloudworks.common.service;

import mekanism.api.qio.resource.QIOResourceDescriptor;

import java.util.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.function.Predicate;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import mekanism.cloudworks.common.registry.CloudworksRegistries;
import mekanism.common.recipe.RecipeHandler;

/**
 * Owner-thread session symbol table binding QIO semantic resource identity to
 * stable integers for one player world session. Integer ids are assigned in
 * first-seen order, never reused or renumbered while the session lives, and
 * therefore every capture of the same session shares one integer space. The
 * table is append-only: registrations after a capture only extend it, so ids
 * handed to workers by earlier snapshots keep their meaning.
 *
 * <p>The lookup key is the descriptor's stable serialization key, a pure
 * string that never invokes a codec, capability, registry or world object.
 * Two semantically equal descriptors map to one id; two descriptors the codec
 * considers equal but that serialize differently conservatively get two ids.
 * The table is session scoped and never persisted: a process restart mints a
 * fresh table, and in-flight detached results from the previous session are
 * rejected through the transaction identity instead.</p>
 */
public final class CloudworksResourceSymbols {
    private final Map<String, Integer> ids = new HashMap<>();
    private final List<QIOResourceDescriptor> descriptors = new ArrayList<>();
    private final List<String> codecs = new ArrayList<>();
    private final List<String> families = new ArrayList<>();
    private final Map<String, FrozenCacheEntry> freezeCache = new HashMap<>();
    private long generation;
    private long freezeHits, freezeMisses;
    private final Map<Object, Map<Integer, IngredientMatches>> ingredientMatches = new IdentityHashMap<>();

    /** Frozen ingredient predicates only scan the newly appended part of the resource dictionary. */
    public synchronized int[] matchingResources(Object contract, int ingredient,
            Predicate<QIOResourceDescriptor> matcher) {
        IngredientMatches cached = ingredientMatches.computeIfAbsent(contract, ignored -> new HashMap<>())
                .computeIfAbsent(ingredient, ignored -> new IngredientMatches());
        for (int index = cached.scanned; index < descriptors.size(); index++)
            if (matcher.test(descriptors.get(index))) cached.accepted.add(index);
        cached.scanned = descriptors.size();
        return cached.accepted.toIntArray();
    }

    private static final class IngredientMatches {
        int scanned;
        final IntArrayList accepted = new IntArrayList();
    }

    /**
     * Stable serialization identity of the descriptor; performs no codec, registry or world access.
     */
    private static String key(QIOResourceDescriptor descriptor) {
        return descriptor.toString();
    }

    /**
     * Registers the descriptor on first sight and returns its session-stable integer id.
     */
    public synchronized int id(QIOResourceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (!descriptor.isResolved()) throw new IllegalArgumentException("Unresolved detached resource");
        String stableKey = key(descriptor);
        Integer existing = ids.get(stableKey);
        if (existing != null) return existing;
        int next = descriptors.size();
        descriptors.add(descriptor);
        codecs.add(descriptor.getCodecId().toString());
        families.add(descriptor.getFamily());
        ids.put(stableKey, next);
        generation++;
        return next;
    }

    /**
     * Resolves an id registered in this session; ids from other sessions are never passed here.
     */
    public synchronized QIOResourceDescriptor descriptor(int resourceId) {
        return descriptors.get(resourceId);
    }

    public synchronized int size() {
        return descriptors.size();
    }

    /**
     * Increases once per newly registered resource; captures record it as their dictionary generation.
     */
    public synchronized long generation() {
        return generation;
    }

    /**
     * Immutable copy of every descriptor registered so far, indexed by id.
     */
    public synchronized List<QIOResourceDescriptor> descriptors() {
        return Collections.unmodifiableList(new ArrayList<>(descriptors));
    }

    // ------------------- frozen-view cache (owner thread only) -------------------

    /**
     * Immutable codec id strings indexed by the same ids, safe to expose inside a worker View.
     */
    public synchronized List<String> codecs() {
        return Collections.unmodifiableList(new ArrayList<>(codecs));
    }

    /**
     * Immutable resource families indexed by the same ids; per-type caps are family-keyed.
     */
    public synchronized List<String> families() {
        return Collections.unmodifiableList(new ArrayList<>(families));
    }

    /**
     * Session cache for one adapter's frozen recipe view, keyed by machine key and
     * adapter slot. The view holds descriptors and predicates only — no symbol ids —
     * so it is independent of the dictionary generation; the registry and recipe
     * versions invalidate it whenever recipes, layouts or adapters are re-registered.
     */
    public Optional<CloudworksFrozenRecipeView> cachedFreeze(
            String cacheKey, CloudworksMachineAdapter adapter) {
        long registryVersion = CloudworksRegistries.version();
        int recipeVersion = RecipeHandler.getGlobalRecipeVersion();
        synchronized (this) {
            FrozenCacheEntry cached = freezeCache.get(cacheKey);
            if (cached != null && cached.registryVersion == registryVersion && cached.recipeVersion == recipeVersion) {
                freezeHits++;
                return Optional.of(cached.view);
            }
        }
        Optional<CloudworksFrozenRecipeView> view =
                CloudworksNativeContracts.view(adapter);
        synchronized (this) {
            if (view.isPresent())
                freezeCache.put(cacheKey, new FrozenCacheEntry(registryVersion, recipeVersion, view.get()));
            freezeMisses++;
        }
        return view;
    }

    /**
     * Cache hit counter; diagnostics and cache tests.
     */
    public synchronized long freezeHits() {
        return freezeHits;
    }

    /**
     * Cache miss counter; diagnostics and cache tests.
     */
    public synchronized long freezeMisses() {
        return freezeMisses;
    }

    private static final class FrozenCacheEntry {
        final long registryVersion;
        final int recipeVersion;
        final CloudworksFrozenRecipeView view;

        FrozenCacheEntry(long registryVersion, int recipeVersion,
                         CloudworksFrozenRecipeView view) {
            this.registryVersion = registryVersion;
            this.recipeVersion = recipeVersion;
            this.view = view;
        }
    }
}

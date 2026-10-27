package mekanism.cloudworks.common.registry;

import mekanism.cloudworks.api.machine.CloudworksEnergyGenerator;
import mekanism.cloudworks.api.machine.CloudworksMachineCategory;
import mekanism.cloudworks.api.machine.MachineExecutionProfile;
import mekanism.cloudworks.api.machine.MachineIdentityPolicy;
import mekanism.cloudworks.api.machine.MachineSlotLayout;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import net.minecraft.item.ItemStack;

import java.util.*;
import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksThermalPlan;
import mekanism.cloudworks.common.recipe.CloudworksAsyncRecipeAdmission;
import mekanism.cloudworks.common.recipe.CloudworksFrozenRecipeView;
import net.minecraft.item.Item;

public final class CloudworksRegistries {
    private static final Map<MachineKey, List<MachineRecipeTypeEntry>> RECIPES = new LinkedHashMap<>();
    private static final Map<MachineKey, Integer> THREADS = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineExecutionProfile> PROFILES = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineSlotLayout> SLOTS = new LinkedHashMap<>();
    private static final Map<MachineKey, CloudworksEnergyGenerator> GENERATORS = new LinkedHashMap<>();
    private static final Map<MachineKey, CloudworksThermalPlan> THERMAL = new LinkedHashMap<>();
    private static final Map<MachineKey, CloudworksMachineCategory> CATEGORIES = new LinkedHashMap<>();
    private static final Map<BaseMachineKey, MachineIdentityPolicy> POLICIES = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineKey> ALIASES = new LinkedHashMap<>();
    private static boolean frozen;
    private static volatile long version;
    private static boolean layoutFingerprintDirty = true;
    private static long cachedLayoutFingerprint;

    private CloudworksRegistries() {
    }

    /**
     * Package-local fixture scope; restores the pre-existing registry rather than leaking test machines.
     */
    static synchronized AutoCloseable isolateForTests() {
        Object registrations = checkpoint();
        Object adapters = CloudworksMachineAdapters.checkpoint();
        boolean wasFrozen = frozen;
        RECIPES.clear();
        THREADS.clear();
        PROFILES.clear();
        SLOTS.clear();
        layoutFingerprintDirty = true;
        GENERATORS.clear();
        THERMAL.clear();
        CATEGORIES.clear();
        POLICIES.clear();
        ALIASES.clear();
        frozen = false;
        version++;
        CloudworksMachineAdapters.clearForTests();
        return () -> {
            synchronized (CloudworksRegistries.class) {
                restore(registrations);
                frozen = wasFrozen;
                CloudworksMachineAdapters.restore(adapters);
            }
        };
    }

    public static synchronized void bootstrap() {
        // Registrations remain open through module pre-initialization. The caller freezes
        // after built-ins and addon declarations have been collected.
    }

    /**
     * Transactional snapshot of every registration map for the public API drain
     * (audit fix): a failed drain restores this checkpoint before rethrowing,
     * so no partially registered machine survives a refusal.
     */
    public static synchronized Object checkpoint() {
        Map<MachineKey, List<MachineRecipeTypeEntry>> recipes = new LinkedHashMap<>();
        RECIPES.forEach((key, value) -> recipes.put(key, new ArrayList<>(value)));
        return new Checkpoint(recipes, new LinkedHashMap<>(THREADS), new LinkedHashMap<>(PROFILES),
                new LinkedHashMap<>(SLOTS), new LinkedHashMap<>(GENERATORS),
                new LinkedHashMap<>(THERMAL), new LinkedHashMap<>(CATEGORIES),
                new LinkedHashMap<>(POLICIES), new LinkedHashMap<>(ALIASES),
                version);
    }

    /**
     * Restores a {@link #checkpoint()} snapshot; no version bump, no freeze change.
     */
    static synchronized void restore(Object checkpoint) {
        Checkpoint snapshot = (Checkpoint) checkpoint;
        RECIPES.clear();
        snapshot.recipes.forEach((key, value) -> RECIPES.put(key, new ArrayList<>(value)));
        THREADS.clear();
        THREADS.putAll(snapshot.threads);
        PROFILES.clear();
        PROFILES.putAll(snapshot.profiles);
        SLOTS.clear();
        SLOTS.putAll(snapshot.slots);
        layoutFingerprintDirty = true;
        GENERATORS.clear();
        GENERATORS.putAll(snapshot.generators);
        THERMAL.clear();
        THERMAL.putAll(snapshot.thermal);
        CATEGORIES.clear();
        CATEGORIES.putAll(snapshot.categories);
        POLICIES.clear();
        POLICIES.putAll(snapshot.policies);
        ALIASES.clear();
        ALIASES.putAll(snapshot.aliases);
        version = snapshot.version;
    }

    private static final class Checkpoint {
        final Map<MachineKey, List<MachineRecipeTypeEntry>> recipes;
        final Map<MachineKey, Integer> threads;
        final Map<MachineKey, MachineExecutionProfile> profiles;
        final Map<MachineKey, MachineSlotLayout> slots;
        final Map<MachineKey, CloudworksEnergyGenerator> generators;
        final Map<MachineKey, CloudworksThermalPlan> thermal;
        final Map<MachineKey, CloudworksMachineCategory> categories;
        final Map<BaseMachineKey, MachineIdentityPolicy> policies;
        final Map<MachineKey, MachineKey> aliases;
        final long version;

        private Checkpoint(Map<MachineKey, List<MachineRecipeTypeEntry>> recipes,
                           Map<MachineKey, Integer> threads, Map<MachineKey, MachineExecutionProfile> profiles,
                           Map<MachineKey, MachineSlotLayout> slots,
                           Map<MachineKey, CloudworksEnergyGenerator> generators,
                           Map<MachineKey, CloudworksThermalPlan> thermal,
                           Map<MachineKey, CloudworksMachineCategory> categories,
                           Map<BaseMachineKey, MachineIdentityPolicy> policies, Map<MachineKey, MachineKey> aliases,
                           long version) {
            this.recipes = recipes;
            this.threads = threads;
            this.profiles = profiles;
            this.slots = slots;
            this.generators = generators;
            this.thermal = thermal;
            this.categories = categories;
            this.policies = policies;
            this.aliases = aliases;
            this.version = version;
        }
    }

    public static synchronized void registerMachine(ItemStack machine, int threads,
                                                    MachineSlotLayout slots, List<MachineRecipeTypeEntry> recipes) {
        registerMachine(machine, new MachineExecutionProfile(threads, 0D, 200), slots, recipes);
    }

    public static synchronized void registerMachine(ItemStack machine, MachineExecutionProfile profile,
                                                    MachineSlotLayout slots, List<MachineRecipeTypeEntry> recipes) {
        registerMachine(machine, profile, slots, recipes, MachineIdentityPolicy.none());
    }

    public static synchronized void registerMachine(ItemStack machine, MachineExecutionProfile profile,
                                                    MachineSlotLayout slots, List<MachineRecipeTypeEntry> recipes, MachineIdentityPolicy policy) {
        registerMachine(machine, profile, slots, recipes, policy, null);
    }

    public static synchronized void registerMachine(ItemStack machine, MachineExecutionProfile profile,
                                                    MachineSlotLayout slots, List<MachineRecipeTypeEntry> recipes, MachineIdentityPolicy policy,
                                                    CloudworksMachineCategory category) {
        checkMutable();
        if (machine == null || machine.isEmpty()) throw new IllegalArgumentException("Empty machine");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(policy, "policy");
        for (MachineRecipeTypeEntry recipe : recipes) Objects.requireNonNull(recipe, "recipe");
        BaseMachineKey base = BaseMachineKey.of(machine);
        MachineIdentityPolicy previousPolicy = POLICIES.get(base);
        if (previousPolicy != null && !previousPolicy.sameDefinition(policy))
            throw new IllegalArgumentException("Conflicting machine identity policy");
        MachineKey key = MachineKey.of(machine, policy);
        MachineSlotLayout previousSlots = SLOTS.get(key);
        if (previousSlots != null && !previousSlots.sameDefinition(slots))
            throw new IllegalArgumentException("Conflicting machine slot layout");
        MachineExecutionProfile previousProfile = PROFILES.get(key);
        if (previousProfile != null && !previousProfile.sameDefinition(profile))
            throw new IllegalArgumentException("Conflicting machine execution profile");
        CloudworksMachineCategory previousCategory = CATEGORIES.get(key);
        if (previousCategory != null && category != null && !previousCategory.sameDefinition(category))
            throw new IllegalArgumentException("Conflicting machine palette category");
        if (category != null)
            for (CloudworksMachineCategory registered : CATEGORIES.values())
                if (registered.id().equals(category.id()) && !registered.sameDefinition(category))
                    throw new IllegalArgumentException("Conflicting machine category translation key: " + category.id());
        List<MachineRecipeTypeEntry> merged = new ArrayList<>(RECIPES.getOrDefault(key, Collections.emptyList()));
        merged.addAll(recipes);
        Set<String> recipeIds = new HashSet<>();
        for (MachineRecipeTypeEntry recipe : merged)
            if (!recipeIds.add(recipe.typeId())) throw new IllegalArgumentException("Duplicate machine recipe type");
        merged.sort(Comparator.comparingInt(MachineRecipeTypeEntry::priority)
                .thenComparing(MachineRecipeTypeEntry::typeId));
        // Validate the complete declaration before changing any registry map.
        POLICIES.put(base, policy);
        RECIPES.put(key, Collections.unmodifiableList(merged));
        THREADS.put(key, profile.threads());
        PROFILES.put(key, profile);
        SLOTS.put(key, slots.frozenCopy());
        layoutFingerprintDirty = true;
        if (category != null) CATEGORIES.put(key, category);
        version++;
        for (MachineRecipeTypeEntry recipe : recipes)
            if (recipe.adapter() != null && CloudworksMachineAdapters.getAll(key).stream()
                    .noneMatch(adapter -> adapter == recipe.adapter()))
                CloudworksMachineAdapters.register(key, recipe.adapter(), new CloudworksMachineRegistration(
                        machine, profile, policy, recipe.slots() == null ? slots : recipe.slots(), recipe.typeId()));
    }

    public static synchronized void updateProfile(ItemStack machine, MachineExecutionProfile profile) {
        checkMutable();
        MachineKey key = key(machine);
        if (!PROFILES.containsKey(key) || !aliasTarget(machine).isEmpty())
            throw new IllegalArgumentException("Only a registered non-alias machine can change profile");
        PROFILES.put(key, Objects.requireNonNull(profile));
        THREADS.put(key, profile.threads());
        version++;
    }

    public static synchronized void registerEnergyGenerator(ItemStack machine, CloudworksEnergyGenerator generator) {
        checkMutable();
        GENERATORS.put(key(machine), Objects.requireNonNull(generator));
        version++;
    }

    public static synchronized List<MachineRecipeTypeEntry> recipeTypes(ItemStack machine) {
        return recipeTypes(key(machine));
    }

    public static synchronized List<MachineRecipeTypeEntry> recipeTypes(MachineKey machine) {
        return RECIPES.getOrDefault(machine, Collections.emptyList());
    }

    public static synchronized MachineRecipeTypeEntry recipeType(MachineKey machine, String typeId) {
        for (MachineRecipeTypeEntry entry : recipeTypes(machine))
            if (entry.typeId().equals(typeId)) return entry;
        return null;
    }

    public static synchronized boolean acceptsRecipeType(ItemStack machine, String typeId) {
        if (typeId == null) return false;
        if (typeId.isEmpty()) return true;
        MachineRecipeTypeEntry entry = recipeType(key(machine), typeId);
        return entry != null && entry.adapter() != null;
    }

    public static synchronized int threads(ItemStack machine) {
        return THREADS.getOrDefault(key(machine), 0);
    }

    public static synchronized MachineExecutionProfile profile(ItemStack machine) {
        return PROFILES.get(key(machine));
    }

    public static synchronized MachineSlotLayout slots(ItemStack machine) {
        return SLOTS.get(key(machine));
    }

    public static synchronized MachineSlotLayout slots(ItemStack machine, String recipeType) {
        return slots(key(machine), recipeType);
    }

    public static synchronized MachineSlotLayout slots(MachineKey machine, String recipeType) {
        if (recipeType != null && !recipeType.isEmpty()) {
            MachineRecipeTypeEntry entry = recipeType(machine, recipeType);
            if (entry != null && entry.slots() != null) return entry.slots();
        }
        return SLOTS.get(machine);
    }

    public static synchronized CloudworksEnergyGenerator energyGenerator(ItemStack machine) {
        return GENERATORS.get(key(machine));
    }

    public static synchronized CloudworksMachineCategory category(ItemStack machine) {
        return category(key(machine));
    }

    public static synchronized CloudworksMachineCategory category(MachineKey machine) {
        CloudworksMachineCategory declared = CATEGORIES.get(machine);
        if (declared != null) return declared;
        if (GENERATORS.containsKey(machine)) return CloudworksMachineCategory.GENERATION;
        if (THERMAL.containsKey(machine)) return CloudworksMachineCategory.OTHER;
        for (CloudworksMachineAdapter adapter : CloudworksMachineAdapters.getAll(machine)) {
            Optional<CloudworksFrozenRecipeView> recipes =
                    CloudworksAsyncRecipeAdmission.peek(adapter);
            if (recipes.isPresent())
                for (CloudworksFrozenRecipeView.Entry entry : recipes.get().entries())
                    if (entry.energyPerOperation() > 0) return CloudworksMachineCategory.GENERATION;
        }
        if (!RECIPES.getOrDefault(machine, Collections.emptyList()).isEmpty()
                || !CloudworksMachineAdapters.getAll(machine).isEmpty()) {
            return CloudworksMachineCategory.PROCESSING;
        }
        return CloudworksMachineCategory.OTHER;
    }

    static synchronized void inheritCategory(MachineKey machine, MachineKey target) {
        checkMutable();
        CloudworksMachineCategory category = CATEGORIES.get(target);
        if (category == null) CATEGORIES.remove(machine);
        else CATEGORIES.put(machine, category);
    }

    public static synchronized MachineKey identityKey(ItemStack machine) {
        return key(machine);
    }

    public static synchronized long version() {
        return version;
    }

    /**
     * Notifies a registration-affecting change outside the machine maps themselves.
     */
    public static synchronized void adapterRegistered() {
        version++;
    }

    public static synchronized MachineExecutionProfile profile(MachineKey key) {
        return PROFILES.get(key);
    }

    public static synchronized MachineSlotLayout slots(MachineKey key) {
        return SLOTS.get(key);
    }

    public static synchronized CloudworksEnergyGenerator energyGenerator(MachineKey key) {
        return GENERATORS.get(key);
    }

    /**
     * Content fingerprint of registry-owned immutable layouts. Every replacement,
     * clear and rollback invalidates this cache, including rollback without a
     * public version bump. Caller-owned builders never enter the registry.
     */
    public static synchronized long layoutFingerprint() {
        if (!layoutFingerprintDirty) return cachedLayoutFingerprint;
        long hash = 1_000_003L;
        for (Map.Entry<MachineKey, MachineSlotLayout> entry : SLOTS.entrySet()) {
            hash = 31L * hash + entry.getKey().hashCode();
            for (MachineSlotLayout.Slot slot : entry.getValue().slots())
                hash = 31L * hash + Objects.hash(slot.id(), slot.codecId(), slot.role(),
                        slot.capacity(), slot.multipleConnections(), slot.recipeFilteredInput());
        }
        cachedLayoutFingerprint = hash;
        layoutFingerprintDirty = false;
        return hash;
    }

    public static synchronized boolean isRegistered(ItemStack machine) {
        return THREADS.containsKey(key(machine));
    }

    public static synchronized void freeze() {
        if (frozen) return;
        ALIASES.forEach(CloudworksRegistries::copyAlias);
        frozen = true;
        version++;
        CloudworksMachineAdapters.freeze();
    }

    public static synchronized void registerThermal(ItemStack machine, CloudworksThermalPlan plan) {
        checkMutable();
        MachineKey key = key(machine);
        if (!PROFILES.containsKey(key)) throw new IllegalArgumentException("Thermal machine is not registered");
        Objects.requireNonNull(plan);
        MachineSlotLayout slots = CloudworksThermalPlan.ports(SLOTS.get(key)).frozenCopy();
        THERMAL.put(key, plan);
        SLOTS.put(key, slots);
        layoutFingerprintDirty = true;
        version++;
    }

    public static synchronized CloudworksThermalPlan thermal(MachineKey key) {
        return THERMAL.get(key);
    }

    public static synchronized Map<MachineKey, CloudworksEnergyGenerator> generatorSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(GENERATORS));
    }

    public static synchronized Map<MachineKey, MachineExecutionProfile> profileSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(PROFILES));
    }

    public static synchronized MachineIdentityPolicy identityPolicy(ItemStack machine) {
        return POLICIES.getOrDefault(BaseMachineKey.of(machine), MachineIdentityPolicy.none());
    }

    private static void checkMutable() {
        if (frozen) throw new IllegalStateException("Cloudworks machine registries are frozen");
    }

    private static MachineKey key(ItemStack machine) {
        return MachineKey.of(machine, POLICIES.getOrDefault(BaseMachineKey.of(machine), MachineIdentityPolicy.none()));
    }

    private static final class BaseMachineKey {
        private final Item item;
        private final int metadata;

        private BaseMachineKey(Item item, int metadata) {
            this.item = item;
            this.metadata = metadata;
        }

        static BaseMachineKey of(ItemStack stack) {
            return new BaseMachineKey(stack.getItem(), stack.getMetadata());
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof BaseMachineKey key && item == key.item && metadata == key.metadata;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(item) + metadata;
        }
    }

    public static synchronized boolean isFrozen() {
        return frozen;
    }

    public static synchronized ItemStack aliasTarget(ItemStack alias) {
        MachineKey target = ALIASES.get(key(alias));
        return target == null ? ItemStack.EMPTY : target.displayStack();
    }

    public static synchronized void registerAlias(ItemStack alias, ItemStack target) {
        if (alias == null || alias.isEmpty() || target == null || target.isEmpty())
            throw new IllegalStateException("Alias and target must be nonempty item stacks");
        String description = alias.getItem().getRegistryName() + " -> " + target.getItem().getRegistryName();
        MachineIdentityPolicy policy = MachineIdentityPolicy.exactNbt();
        MachineKey aliasKey = MachineKey.of(alias, policy), targetKey = key(target);
        if (aliasKey.equals(targetKey)) throw new IllegalStateException("Self alias: " + description);
        if (ALIASES.containsKey(targetKey))
            throw new IllegalStateException("Alias chains are forbidden: " + description);
        if (!PROFILES.containsKey(targetKey) || CloudworksMachineAdapters.getAll(targetKey).isEmpty()
                && !GENERATORS.containsKey(targetKey) && !THERMAL.containsKey(targetKey))
            throw new IllegalStateException("Target machine has no execution contract: " + description);
        MachineKey existing = ALIASES.get(aliasKey);
        if (existing != null && existing.equals(targetKey)) return;
        if (frozen) throw new IllegalStateException("Aliases require a restart: " + description);
        BaseMachineKey base = BaseMachineKey.of(alias);
        MachineIdentityPolicy previous = POLICIES.get(base);
        if (existing != null || PROFILES.containsKey(aliasKey)
                || previous != null && !previous.sameDefinition(policy))
            throw new IllegalStateException("Alias identity is already registered: " + description);
        POLICIES.put(base, policy);
        ALIASES.put(aliasKey, targetKey);
        copyAlias(aliasKey, targetKey);
        version++;
    }

    private static void copyAlias(MachineKey alias, MachineKey target) {
        RECIPES.put(alias, RECIPES.get(target));
        THREADS.put(alias, THREADS.get(target));
        PROFILES.put(alias, PROFILES.get(target));
        SLOTS.put(alias, SLOTS.get(target));
        layoutFingerprintDirty = true;
        if (GENERATORS.containsKey(target)) GENERATORS.put(alias, GENERATORS.get(target));
        if (THERMAL.containsKey(target)) THERMAL.put(alias, THERMAL.get(target));
        if (CATEGORIES.containsKey(target)) CATEGORIES.put(alias, CATEGORIES.get(target));
        CloudworksMachineAdapters.bindAlias(alias, target);
    }
}

package mekanism.cloudworks.common.registry;

import mekanism.cloudworks.api.CloudworksEnergyGenerator;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import mekanism.cloudworks.common.recipe.SpecialMachineAdapter;
import mekanism.cloudworks.common.slot.MachineSlotLayout;
import net.minecraft.item.ItemStack;

import java.util.*;

public final class CloudworksRegistries {
    private static final Map<MachineKey, List<MachineRecipeTypeEntry>> RECIPES = new LinkedHashMap<>();
    private static final Map<MachineKey, Integer> THREADS = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineExecutionProfile> PROFILES = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineSlotLayout> SLOTS = new LinkedHashMap<>();
    private static final Map<MachineKey, SpecialMachineAdapter> SPECIAL = new LinkedHashMap<>();
    private static final Map<MachineKey, CloudworksEnergyGenerator> GENERATORS = new LinkedHashMap<>();
    private static final Map<MachineKey, mekanism.cloudworks.api.CloudworksThermalPlan> THERMAL = new LinkedHashMap<>();
    private static final Map<BaseMachineKey, MachineIdentityPolicy> POLICIES = new LinkedHashMap<>();
    private static final Map<MachineKey, MachineKey> ALIASES = new LinkedHashMap<>();
    private static boolean frozen;
    private static volatile long version;

    private CloudworksRegistries() {
    }

    public static synchronized void bootstrap() {
        // Registrations remain open through module pre-initialization. The caller freezes
        // after built-ins and addon declarations have been collected.
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
        List<MachineRecipeTypeEntry> merged = new ArrayList<>(RECIPES.getOrDefault(key, Collections.emptyList()));
        merged.addAll(recipes);
        merged.sort(java.util.Comparator.comparingInt(MachineRecipeTypeEntry::priority)
                .thenComparing(MachineRecipeTypeEntry::typeId));
        // Validate the complete declaration before changing any registry map.
        POLICIES.put(base, policy);
        RECIPES.put(key, Collections.unmodifiableList(merged));
        THREADS.put(key, profile.threads());
        PROFILES.put(key, profile);
        SLOTS.put(key, slots.frozenCopy());
        version++;
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

    public static synchronized void registerSpecial(ItemStack machine, SpecialMachineAdapter adapter) {
        checkMutable();
        SPECIAL.put(key(machine), Objects.requireNonNull(adapter));
        version++;
    }

    public static synchronized void registerEnergyGenerator(ItemStack machine, CloudworksEnergyGenerator generator) {
        checkMutable();
        GENERATORS.put(key(machine), Objects.requireNonNull(generator));
        version++;
    }

    public static List<MachineRecipeTypeEntry> recipeTypes(ItemStack machine) {
        return RECIPES.getOrDefault(key(machine), Collections.emptyList());
    }

    public static int threads(ItemStack machine) {
        return THREADS.getOrDefault(key(machine), 0);
    }

    public static MachineExecutionProfile profile(ItemStack machine) {
        return PROFILES.get(key(machine));
    }

    public static MachineSlotLayout slots(ItemStack machine) {
        return SLOTS.get(key(machine));
    }

    public static SpecialMachineAdapter special(ItemStack machine) {
        return SPECIAL.get(key(machine));
    }

    public static CloudworksEnergyGenerator energyGenerator(ItemStack machine) {
        return GENERATORS.get(key(machine));
    }

    public static MachineKey identityKey(ItemStack machine) {
        return key(machine);
    }

    public static long version() {
        return version;
    }

    /**
     * Notifies a registration-affecting change outside the machine maps themselves.
     */
    public static synchronized void adapterRegistered() {
        version++;
    }

    public static MachineExecutionProfile profile(MachineKey key) {
        return PROFILES.get(key);
    }

    public static MachineSlotLayout slots(MachineKey key) {
        return SLOTS.get(key);
    }

    public static SpecialMachineAdapter special(MachineKey key) {
        return SPECIAL.get(key);
    }

    public static CloudworksEnergyGenerator energyGenerator(MachineKey key) {
        return GENERATORS.get(key);
    }

    /**
     * Content fingerprint of every registered slot layout. Transactions record it
     * so a template whose content changed without a registry version bump cannot
     * silently desynchronize a frozen execution view.
     */
    public static synchronized long layoutFingerprint() {
        long hash = 1_000_003L;
        for (Map.Entry<MachineKey, MachineSlotLayout> entry : SLOTS.entrySet()) {
            hash = 31L * hash + entry.getKey().hashCode();
            for (MachineSlotLayout.Slot slot : entry.getValue().slots())
                hash = 31L * hash + Objects.hash(slot.id(), slot.codecId(), slot.role(),
                        slot.capacity(), slot.multipleConnections());
        }
        return hash;
    }

    public static boolean isRegistered(ItemStack machine) {
        return THREADS.containsKey(key(machine));
    }

    public static synchronized void freeze() {
        if (frozen) return;
        ALIASES.forEach(CloudworksRegistries::copyAlias);
        frozen = true;
        version++;
        CloudworksMachineAdapters.freeze();
    }

    public static synchronized void registerThermal(ItemStack machine, mekanism.cloudworks.api.CloudworksThermalPlan plan) {
        checkMutable();
        MachineKey key = key(machine);
        if (!PROFILES.containsKey(key)) throw new IllegalArgumentException("Thermal machine is not registered");
        Objects.requireNonNull(plan);
        MachineSlotLayout slots = mekanism.cloudworks.api.CloudworksThermalPlan.ports(SLOTS.get(key)).frozenCopy();
        THERMAL.put(key, plan);
        SLOTS.put(key, slots);
        version++;
    }

    public static mekanism.cloudworks.api.CloudworksThermalPlan thermal(MachineKey key) { return THERMAL.get(key); }

    public static synchronized Map<MachineKey, SpecialMachineAdapter> specialSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(SPECIAL));
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
        private final net.minecraft.item.Item item;
        private final int metadata;

        private BaseMachineKey(net.minecraft.item.Item item, int metadata) {
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

    public static synchronized boolean isFrozen() { return frozen; }

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
        if (ALIASES.containsKey(targetKey)) throw new IllegalStateException("Alias chains are forbidden: " + description);
        if (!PROFILES.containsKey(targetKey) || CloudworksMachineAdapters.getAll(targetKey).isEmpty()
                && !SPECIAL.containsKey(targetKey) && !GENERATORS.containsKey(targetKey) && !THERMAL.containsKey(targetKey))
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
        if (SPECIAL.containsKey(target)) SPECIAL.put(alias, SPECIAL.get(target));
        if (GENERATORS.containsKey(target)) GENERATORS.put(alias, GENERATORS.get(target));
        if (THERMAL.containsKey(target)) THERMAL.put(alias, THERMAL.get(target));
        CloudworksMachineAdapters.bindAlias(alias, target);
    }
}

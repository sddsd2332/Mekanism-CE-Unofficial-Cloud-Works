package mekanism.cloudworks.common.registry;

import mekanism.cloudworks.api.machine.CloudworksMachineAdapter;
import mekanism.cloudworks.api.machine.CloudworksMachineRegistration;
import mekanism.cloudworks.common.recipe.MachineRecipeTypeEntry;
import net.minecraft.item.ItemStack;

import java.util.*;
import mekanism.cloudworks.common.service.CloudworksNativeContracts;

public final class CloudworksMachineAdapters {
    private static final Map<MachineKey, List<CloudworksMachineAdapter>> ADAPTERS = new LinkedHashMap<>();
    /**
     * Stable per-instance registration ids; multiple instances of one class under one key are legal.
     */
    private static final Map<CloudworksMachineAdapter, Long> REGISTRATION_IDS = new IdentityHashMap<>();
    private static final Map<CloudworksMachineAdapter, CloudworksMachineRegistration> REGISTRATIONS =
            new IdentityHashMap<>();
    private static long nextRegistrationId;
    private static boolean frozen;

    private CloudworksMachineAdapters() {
    }

    public static void register(MachineKey key, CloudworksMachineAdapter adapter) {
        register(key, adapter, new CloudworksMachineRegistration(key.displayStack(),
                CloudworksRegistries.profile(key), CloudworksRegistries.identityPolicy(key.displayStack()),
                CloudworksRegistries.slots(key, ""), ""));
    }

    public static void register(MachineKey key, CloudworksMachineAdapter adapter,
            CloudworksMachineRegistration registration) {
        synchronized (CloudworksMachineAdapters.class) {
            if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
            ADAPTERS.computeIfAbsent(key, ignored -> new ArrayList<>()).add(adapter);
            if (!REGISTRATION_IDS.containsKey(adapter)) REGISTRATION_IDS.put(adapter, ++nextRegistrationId);
            REGISTRATIONS.putIfAbsent(adapter, registration);
        }
        CloudworksRegistries.adapterRegistered();
    }

    public static synchronized CloudworksMachineRegistration registration(CloudworksMachineAdapter adapter) {
        return REGISTRATIONS.get(adapter);
    }

    /**
     * Stable registration id of one adapter instance (GPT review: the freeze
     * cache key must distinguish same-class instances with different recipe
     * sets registered under the same machine key). -1 when unknown.
     */
    public static synchronized long registrationId(CloudworksMachineAdapter adapter) {
        Long id = REGISTRATION_IDS.get(adapter);
        return id == null ? -1L : id;
    }

    public static synchronized void registerDerived(CloudworksMachineAdapter adapter, CloudworksMachineRegistration registration) {
        if (CloudworksNativeContracts.isWorker())
            throw new IllegalStateException("Module recipes cannot be registered by a Runtime worker");
        REGISTRATION_IDS.put(adapter, ++nextRegistrationId);
        REGISTRATIONS.put(adapter, registration);
    }

    static synchronized void clearForTests() {
        ADAPTERS.clear();
        REGISTRATION_IDS.clear();
        REGISTRATIONS.clear();
        nextRegistrationId = 0;
        frozen = false;
    }

    public static CloudworksMachineAdapter get(MachineKey key) {
        List<CloudworksMachineAdapter> adapters = ADAPTERS.get(key);
        return adapters == null || adapters.isEmpty() ? null : adapters.get(0);
    }

    public static CloudworksMachineAdapter get(ItemStack machine) {
        return get(CloudworksRegistries.identityKey(machine));
    }

    public static List<CloudworksMachineAdapter> getAll(ItemStack machine) {
        return getAll(CloudworksRegistries.identityKey(machine));
    }

    public static List<CloudworksMachineAdapter> getAll(MachineKey key) {
        List<CloudworksMachineAdapter> adapters = ADAPTERS.get(key);
        return adapters == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(adapters));
    }

    /** Automatic execution only admits modes whose ports exist in the default layout. */
    public static List<CloudworksMachineAdapter> getAutomatic(MachineKey key) {
        List<CloudworksMachineAdapter> result = new ArrayList<>();
        mekanism.cloudworks.api.machine.MachineSlotLayout base = CloudworksRegistries.slots(key);
        for (CloudworksMachineAdapter adapter : getAll(key)) {
            boolean named = false, compatible = false;
            for (MachineRecipeTypeEntry mode : CloudworksRegistries.recipeTypes(key)) {
                if (mode.adapter() != adapter) continue;
                named = true;
                mekanism.cloudworks.api.machine.MachineSlotLayout layout = mode.slots();
                boolean fits = true;
                if (layout != null) for (var slot : layout.slots()) {
                    var existing = base == null ? null : base.find(slot.id());
                    if (existing == null || existing.role() != slot.role() || !existing.codecId().equals(slot.codecId())
                            || existing.capacity() < slot.capacity()) { fits = false; break; }
                }
                compatible |= fits;
            }
            if (!named || compatible) result.add(adapter);
        }
        return Collections.unmodifiableList(result);
    }

    public static Map<MachineKey, List<CloudworksMachineAdapter>> snapshot() {
        Map<MachineKey, List<CloudworksMachineAdapter>> copy = new LinkedHashMap<>();
        for (Map.Entry<MachineKey, List<CloudworksMachineAdapter>> entry : ADAPTERS.entrySet())
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        return Collections.unmodifiableMap(copy);
    }

    static synchronized Object checkpoint() {
        Map<MachineKey, List<CloudworksMachineAdapter>> adapters = new LinkedHashMap<>();
        ADAPTERS.forEach((key, value) -> adapters.put(key, new ArrayList<>(value)));
        return new Checkpoint(adapters, new IdentityHashMap<>(REGISTRATION_IDS),
                new IdentityHashMap<>(REGISTRATIONS), nextRegistrationId, frozen);
    }

    static synchronized void restore(Object checkpoint) {
        Checkpoint snapshot = (Checkpoint) checkpoint;
        ADAPTERS.clear();
        snapshot.adapters.forEach((key, value) -> ADAPTERS.put(key, new ArrayList<>(value)));
        REGISTRATION_IDS.clear();
        REGISTRATION_IDS.putAll(snapshot.registrationIds);
        REGISTRATIONS.clear();
        REGISTRATIONS.putAll(snapshot.registrations);
        nextRegistrationId = snapshot.nextRegistrationId;
        frozen = snapshot.frozen;
    }

    private static final class Checkpoint {
        final Map<MachineKey, List<CloudworksMachineAdapter>> adapters;
        final IdentityHashMap<CloudworksMachineAdapter, Long> registrationIds;
        final IdentityHashMap<CloudworksMachineAdapter, CloudworksMachineRegistration> registrations;
        final long nextRegistrationId;
        final boolean frozen;

        Checkpoint(Map<MachineKey, List<CloudworksMachineAdapter>> adapters,
                IdentityHashMap<CloudworksMachineAdapter, Long> registrationIds,
                IdentityHashMap<CloudworksMachineAdapter, CloudworksMachineRegistration> registrations,
                long nextRegistrationId, boolean frozen) {
            this.adapters = adapters;
            this.registrationIds = registrationIds;
            this.registrations = registrations;
            this.nextRegistrationId = nextRegistrationId;
            this.frozen = frozen;
        }
    }

    public static synchronized void freeze() {
        frozen = true;
    }

    static synchronized void bindAlias(MachineKey alias, MachineKey target) {
        if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
        List<CloudworksMachineAdapter> adapters = ADAPTERS.get(target);
        if (adapters != null) ADAPTERS.put(alias, new ArrayList<>(adapters));
    }

    public static void replace(MachineKey key, List<CloudworksMachineAdapter> adapters) {
        synchronized (CloudworksMachineAdapters.class) {
            if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
            List<CloudworksMachineAdapter> copy = new ArrayList<>();
            for (CloudworksMachineAdapter adapter : adapters) {
                copy.add(Objects.requireNonNull(adapter));
                if (!REGISTRATION_IDS.containsKey(adapter)) REGISTRATION_IDS.put(adapter, ++nextRegistrationId);
                REGISTRATIONS.putIfAbsent(adapter, new CloudworksMachineRegistration(key.displayStack(),
                        CloudworksRegistries.profile(key), CloudworksRegistries.identityPolicy(key.displayStack()),
                        CloudworksRegistries.slots(key, ""), ""));
            }
            ADAPTERS.put(key, copy);
        }
        CloudworksRegistries.adapterRegistered();
    }
}

package mekanism.cloudworks.common.registry;

import mekanism.cloudworks.api.CloudworksMachineAdapter;
import net.minecraft.item.ItemStack;

import java.util.*;

public final class CloudworksMachineAdapters {
    private static final Map<MachineKey, List<CloudworksMachineAdapter>> ADAPTERS = new LinkedHashMap<>();
    /**
     * Stable per-instance registration ids; multiple instances of one class under one key are legal.
     */
    private static final Map<CloudworksMachineAdapter, Long> REGISTRATION_IDS = new java.util.IdentityHashMap<>();
    private static long nextRegistrationId;
    private static boolean frozen;

    private CloudworksMachineAdapters() {
    }

    public static synchronized void register(MachineKey key, CloudworksMachineAdapter adapter) {
        if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
        ADAPTERS.computeIfAbsent(key, ignored -> new ArrayList<>()).add(adapter);
        REGISTRATION_IDS.put(adapter, ++nextRegistrationId);
        CloudworksRegistries.adapterRegistered();
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

    public static Map<MachineKey, List<CloudworksMachineAdapter>> snapshot() {
        Map<MachineKey, List<CloudworksMachineAdapter>> copy = new LinkedHashMap<>();
        for (Map.Entry<MachineKey, List<CloudworksMachineAdapter>> entry : ADAPTERS.entrySet())
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        return Collections.unmodifiableMap(copy);
    }

    public static synchronized void freeze() {
        frozen = true;
    }

    static synchronized void bindAlias(MachineKey alias, MachineKey target) {
        if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
        List<CloudworksMachineAdapter> adapters = ADAPTERS.get(target);
        if (adapters != null) ADAPTERS.put(alias, new ArrayList<>(adapters));
    }

    public static synchronized void replace(MachineKey key, List<CloudworksMachineAdapter> adapters) {
        if (frozen) throw new IllegalStateException("Cloudworks machine adapters are frozen");
        List<CloudworksMachineAdapter> copy = new ArrayList<>();
        for (CloudworksMachineAdapter adapter : adapters) {
            copy.add(Objects.requireNonNull(adapter));
            if (!REGISTRATION_IDS.containsKey(adapter)) REGISTRATION_IDS.put(adapter, ++nextRegistrationId);
        }
        ADAPTERS.put(key, copy);
        CloudworksRegistries.adapterRegistered();
    }
}

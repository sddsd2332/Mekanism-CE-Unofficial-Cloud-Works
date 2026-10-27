package mekanism.cloudworks.common.service;

import mekanism.cloudworks.api.CloudworksAPI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import mekanism.cloudworks.api.environment.CloudworksLootInjection;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.common.service.CloudworksLootTables.LootDrop;
import net.minecraft.entity.EntityList;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

/**
 * Frozen manual-injection layer of the loot pipeline: probability items
 * declared through the public API roll on top of the frozen spawn/loot
 * results. Declarations are validated at drain time (fail-fast with the
 * offending target named) and the frozen view is immutable afterwards.
 */
public final class CloudworksLootInjections {
    private static final Map<ResourceLocation, List<CloudworksLootInjection>> EMPTY = Collections.emptyMap();

    private static volatile Map<ResourceLocation, List<CloudworksLootInjection>> byEntity = EMPTY;
    private static volatile Map<ResourceLocation, List<CloudworksLootInjection>> byAxis = EMPTY;
    private static final List<CloudworksLootInjection> pending = new ArrayList<>();
    private static boolean frozen;

    private CloudworksLootInjections() {}

    /** Lifecycle drain sink: validates one declaration and stages it. */
    static synchronized void register(CloudworksLootInjection injection) {
        if (frozen) throw new IllegalStateException("CloudWorks loot injections are frozen");
        Objects.requireNonNull(injection);
        if (injection.target() == CloudworksLootInjection.Target.ENTITY) {
            if (!EntityList.isRegistered(injection.targetId())) {
                throw new IllegalStateException("Loot injection targets an unknown entity: "
                        + injection.targetId());
            }
        } else if (EnvironmentAxisRegistry.byId(injection.targetId()) == null) {
            throw new IllegalStateException("Loot injection targets an unknown axis: "
                    + injection.targetId());
        }
        if (Item.REGISTRY.getObject(injection.item()) == null)
            throw new IllegalStateException("Loot injection drops an unregistered item: "
                    + injection.item());
        pending.add(injection);
    }

    /**
     * Atomically drains declarations into this registry. The API restores its
     * queue on a failed declaration; this side rolls back any earlier staged
     * entries so a corrected retry cannot duplicate them.
     */
    public static synchronized void freezeAndDrain() {
        if (frozen) return;
        int checkpoint = pending.size();
        try {
            CloudworksAPI.freezeAndDrainLootInjections(CloudworksLootInjections::register);
            freeze();
        } catch (RuntimeException failure) {
            while (pending.size() > checkpoint) pending.remove(pending.size() - 1);
            throw failure;
        }
    }

    /** Builds the immutable view; the API drain closes the declaration window with this batch. */
    static synchronized void freeze() {
        if (frozen) return;
        Map<ResourceLocation, List<CloudworksLootInjection>> entities = new LinkedHashMap<>();
        Map<ResourceLocation, List<CloudworksLootInjection>> axes = new LinkedHashMap<>();
        for (CloudworksLootInjection injection : pending) {
            Map<ResourceLocation, List<CloudworksLootInjection>> map =
                    injection.target() == CloudworksLootInjection.Target.ENTITY ? entities : axes;
            map.computeIfAbsent(injection.targetId(), ignored -> new ArrayList<>()).add(injection);
        }
        byEntity = frozen(entities);
        byAxis = frozen(axes);
        pending.clear();
        frozen = true;
    }

    private static Map<ResourceLocation, List<CloudworksLootInjection>> frozen(
            Map<ResourceLocation, List<CloudworksLootInjection>> map) {
        Map<ResourceLocation, List<CloudworksLootInjection>> copy = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, List<CloudworksLootInjection>> entry : map.entrySet())
            copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        return Collections.unmodifiableMap(copy);
    }

    /** Appends entity-keyed extras for one simulated kill; nothing when none are declared. */
    public static void appendEntityDrops(ResourceLocation entity, List<LootDrop> out, Random random) {
        append(byEntity.get(entity), out, random);
    }

    /** Appends axis-keyed extras for one simulated event (entity kill or chest roll). */
    public static void appendAxisDrops(ResourceLocation axisId, List<LootDrop> out, Random random) {
        append(byAxis.get(axisId), out, random);
    }

    private static void append(List<CloudworksLootInjection> injections, List<LootDrop> out, Random random) {
        if (injections == null) return;
        for (CloudworksLootInjection injection : injections) {
            if (random.nextDouble() >= injection.chance()) continue;
            int count = injection.minCount() >= injection.maxCount() ? injection.minCount()
                    : injection.minCount() + random.nextInt((int) ((long) injection.maxCount()
                            - injection.minCount() + 1));
            if (count > 0)
                out.add(CloudworksLootTables.drop(injection.item(), injection.meta(), count));
        }
    }

    /** Test-only: drops the frozen view and any staged declarations. */
    public static synchronized void resetForTests() {
        pending.clear();
        byEntity = EMPTY;
        byAxis = EMPTY;
        frozen = false;
    }
}

package mekanism.cloudworks.common.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.EntityList;
import net.minecraft.world.biome.Biome;

/**
 * Pipeline stage 1 (frozen): spawn tables per axis, read once from the live
 * biome registry (biome axes) or the declared axis payload (structure axes)
 * and never touched again at runtime — the cloud runtime performs zero live
 * world access. Entries carry registry entity names, weights and group-size
 * ranges; frequency is deliberately absent (machines are the balancing axis).
 */
public final class CloudworksSpawnTables {
    /** Immutable per-axis spawn table; empty when the axis declares or yields no creatures. */
    public static final class FrozenSpawnTable {
        private final ResourceLocation axisId;
        private final List<EnvironmentAxis.SpawnLoad> entries;
        private final long totalWeight;

        private FrozenSpawnTable(ResourceLocation axisId, List<EnvironmentAxis.SpawnLoad> entries) {
            this.axisId = axisId;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            long total = 0;
            for (EnvironmentAxis.SpawnLoad load : this.entries) total += load.weight();
            this.totalWeight = total;
        }

        public ResourceLocation axisId() { return axisId; }
        public List<EnvironmentAxis.SpawnLoad> entries() { return entries; }
        public long totalWeight() { return totalWeight; }

        /** Weighted entity pick (registry name) or null when the table is empty. */
        public String pick(Random random) {
            if (totalWeight <= 0 || entries.isEmpty()) return null;
            long cursor = (long) (random.nextDouble() * totalWeight);
            for (EnvironmentAxis.SpawnLoad load : entries) {
                cursor -= load.weight();
                if (cursor < 0) return load.entity();
            }
            return entries.get(entries.size() - 1).entity();
        }
    }

    private static final AtomicReference<Map<ResourceLocation, FrozenSpawnTable>> FROZEN = new AtomicReference<>();

    private CloudworksSpawnTables() {}

    /** Freezes every axis' spawn table; idempotent and safe to re-run after registration changes. */
    public static synchronized void freeze() {
        Map<ResourceLocation, FrozenSpawnTable> tables = new LinkedHashMap<>();
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
            tables.put(axis.id(), freezeAxis(axis));
        FROZEN.set(Collections.unmodifiableMap(tables));
    }

    private static FrozenSpawnTable freezeAxis(EnvironmentAxis axis) {
        List<EnvironmentAxis.SpawnLoad> entries = new ArrayList<>();
        if (axis.kind() == EnvironmentAxis.Kind.BIOME && axis.fallback() != null && axis.fallback().biome() != null) {
            // Biome axes read the live MONSTER table at freeze time: mods injecting
            // spawns through EntityRegistry are captured in the snapshot (final state).
            for (Biome.SpawnListEntry entry
                    : axis.fallback().biome().getSpawnableList(EnumCreatureType.MONSTER)) {
                ResourceLocation key = EntityList.getKey(entry.entityClass);
                String name = key == null ? null : fullEntityName(key.toString());
                if (name == null) continue;
                entries.add(new EnvironmentAxis.SpawnLoad(name, entry.itemWeight, entry.minGroupCount, entry.maxGroupCount));
            }
        } else {
            entries.addAll(axis.spawnLoads());
        }
        return new FrozenSpawnTable(axis.id(), entries);
    }

    private static String fullEntityName(String name) {
        if (name == null || name.isEmpty()) return null;
        return name.indexOf(':') >= 0 ? name : "minecraft:" + name;
    }

    /** Frozen table for an axis; lazily freezes on first use (tests, early consumers). */
    public static FrozenSpawnTable tableFor(ResourceLocation axisId) {
        Map<ResourceLocation, FrozenSpawnTable> tables = FROZEN.get();
        if (tables == null) {
            freeze();
            tables = FROZEN.get();
        }
        return tables.get(axisId);
    }

    /** Weighted entity pick for an axis; null when the axis is unknown or its table is empty. */
    public static String pickEntity(ResourceLocation axisId, Random random) {
        FrozenSpawnTable table = tableFor(axisId);
        return table == null ? null : table.pick(random);
    }

    /** Rolls one entity kill from the frozen axis spawn distribution and its frozen loot table. */
    public static List<CloudworksLootTables.LootDrop> rollEntityLoot(ResourceLocation axisId, Random random) {
        String entity = pickEntity(axisId, random);
        if (entity == null) return Collections.emptyList();
        List<CloudworksLootTables.LootDrop> drops =
                new ArrayList<>(CloudworksLootTables.entityTable(entity).roll(random));
        CloudworksLootInjections.appendEntityDrops(new ResourceLocation(entity), drops, random);
        CloudworksLootInjections.appendAxisDrops(axisId, drops, random);
        return drops;
    }

    /** Rolls every declared chest table for a structure axis, preserving independent-table semantics. */
    public static List<CloudworksLootTables.LootDrop> rollChestLoot(ResourceLocation axisId, Random random) {
        EnvironmentAxis axis = EnvironmentAxisRegistry.byId(axisId);
        if (axis == null || axis.chestTables().isEmpty()) return Collections.emptyList();
        List<CloudworksLootTables.LootDrop> drops = new ArrayList<>();
        for (int index = 0; index < axis.chestTables().size(); index++) {
            List<CloudworksLootTables.LootDrop> tableDrops =
                    CloudworksLootTables.table(axis.chestTables().get(index)).roll(random);
            CloudworksLootInjections.appendAxisDrops(axisId, tableDrops, random);
            drops.addAll(tableDrops);
        }
        return drops;
    }

    /** Test-only: drops the frozen snapshot so the next query re-freezes. */
    public static synchronized void resetForTests() {
        FROZEN.set(null);
    }
}

package mekanism.cloudworks.api.environment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.init.Biomes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

/** Immutable declaration for one environment axis. */
public final class EnvironmentAxis {
    public enum Kind { DIMENSION, BIOME, STRUCTURE, WEATHER }

    /** One frozen spawn entry: registry entity name, spawn weight and group size range. */
    public static final class SpawnLoad {
        private final String entity;
        private final int weight;
        private final int minGroup;
        private final int maxGroup;

        public SpawnLoad(String entity, int weight, int minGroup, int maxGroup) {
            this.entity = Objects.requireNonNull(entity, "entity");
            if (!entity.equals(new ResourceLocation(entity).toString()))
                throw new IllegalArgumentException("Spawn entity must be a full registry name: " + entity);
            if (weight < 1 || minGroup < 1 || maxGroup < minGroup)
                throw new IllegalArgumentException("Invalid spawn load for " + entity);
            this.weight = weight;
            this.minGroup = minGroup;
            this.maxGroup = maxGroup;
        }

        public String entity() { return entity; }
        public int weight() { return weight; }
        public int minGroup() { return minGroup; }
        public int maxGroup() { return maxGroup; }
    }

    private final ResourceLocation id;
    private final EnvironmentFallback fallback;
    private final ResourceLocation effectId;
    private final String displayKey;
    private final String structureName;
    private final Set<Biome> structureBiomes;
    private final List<ResourceLocation> chestTables;
    private final List<SpawnLoad> spawnLoads;

    private EnvironmentAxis(Builder builder) {
        id = Objects.requireNonNull(builder.id, "id");
        fallback = builder.fallback;
        effectId = builder.effectId == null ? id : builder.effectId;
        displayKey = builder.displayKey == null
                ? "cloudworks.environment." + id.getPath() : builder.displayKey;
        structureName = builder.structureName;
        structureBiomes = builder.structureBiomes == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(builder.structureBiomes));
        chestTables = builder.chestTables.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.chestTables));
        spawnLoads = builder.spawnLoads.isEmpty() ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(builder.spawnLoads));
    }

    public ResourceLocation id() { return id; }
    public Kind kind() { return fallback == null ? Kind.WEATHER : fallback.kind(); }
    public EnvironmentFallback fallback() { return fallback; }
    public ResourceLocation effectId() { return effectId; }
    public String displayKey() { return displayKey; }
    public String structureName() { return structureName; }
    public Set<Biome> structureBiomes() { return structureBiomes; }
    public List<ResourceLocation> chestTables() { return chestTables; }
    public List<SpawnLoad> spawnLoads() { return spawnLoads; }
    public boolean snowy() {
        return fallback != null && fallback.kind() == Kind.BIOME
                && fallback.biome() != null && fallback.biome().isSnowyBiome();
    }

    @Override
    public String toString() { return id.toString(); }

    public static final class Builder {
        private final ResourceLocation id;
        private EnvironmentFallback fallback;
        private ResourceLocation effectId;
        private String displayKey;
        private String structureName;
        private Set<Biome> structureBiomes;
        private final List<ResourceLocation> chestTables = new ArrayList<>();
        private final List<SpawnLoad> spawnLoads = new ArrayList<>();

        private Builder(ResourceLocation id) { this.id = id; }

        public static Builder axis(String name) {
            return new Builder(new ResourceLocation("cloudworks", name));
        }

        public static Builder weather(String name) {
            return axis(name);
        }

        public Builder fallback(EnvironmentFallback value) {
            if (value == null || value.kind() == EnvironmentAxis.Kind.WEATHER)
                throw new IllegalArgumentException("Axis fallback must be dimension, biome or structure data");
            fallback = value;
            return this;
        }

        public Builder effectId(String name) {
            effectId = new ResourceLocation("cloudworks", name);
            return this;
        }

        public Builder displayKey(String key) {
            displayKey = Objects.requireNonNull(key, "key");
            return this;
        }

        public Builder structureName(String name) {
            structureName = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder structureBiomes(Biome... biomes) {
            if (biomes == null || biomes.length == 0)
                throw new IllegalArgumentException("Structure biome constraint cannot be empty");
            Set<Biome> set = new LinkedHashSet<>(Arrays.asList(biomes));
            if (set.contains(null)) throw new IllegalArgumentException("Structure biome constraint cannot contain null");
            structureBiomes = set;
            return this;
        }

        public Builder chestTables(String... tableIds) {
            for (String tableId : tableIds) chestTables.add(new ResourceLocation(tableId));
            return this;
        }

        public Builder spawnLoad(String entity, int weight, int minGroup, int maxGroup) {
            spawnLoads.add(new SpawnLoad(entity, weight, minGroup, maxGroup));
            return this;
        }

        public EnvironmentAxis build() {
            if (fallback == null) throw new IllegalArgumentException("Axis requires fallback data");
            if (fallback.kind() == Kind.STRUCTURE) {
                if (structureName == null || structureName.isEmpty())
                    throw new IllegalArgumentException("Structure axis requires the vanilla structure name: " + id);
            } else if (structureName != null || structureBiomes != null
                    || !chestTables.isEmpty() || !spawnLoads.isEmpty()) {
                throw new IllegalArgumentException("Structure payloads belong to structure axes: " + id);
            }
            return new EnvironmentAxis(this);
        }

        public EnvironmentAxis buildWeather() {
            if (fallback != null) throw new IllegalArgumentException("Weather axes carry no fallback data");
            return new EnvironmentAxis(this);
        }
    }
}

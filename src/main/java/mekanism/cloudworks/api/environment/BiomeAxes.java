package mekanism.cloudworks.api.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.init.Biomes;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;

/** Biome-axis declarations and registry discovery. */
public final class BiomeAxes {
    public static final EnvironmentAxis OCEAN = EnvironmentAxis.Builder.axis("ocean_environment")
            .fallback(EnvironmentFallback.biome(Biomes.OCEAN)).build();
    public static final EnvironmentAxis FOREST = EnvironmentAxis.Builder.axis("forest_environment")
            .fallback(EnvironmentFallback.biome(Biomes.FOREST)).build();

    private static volatile List<EnvironmentAxis> ALL = discover();
    private static int discoveredCount = Biome.REGISTRY.getKeys().size();

    private BiomeAxes() {}

    private static List<EnvironmentAxis> discover() {
        List<EnvironmentAxis> result = new ArrayList<>();
        result.add(OCEAN);
        result.add(FOREST);
        Set<Biome> claimed = new HashSet<>();
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        claimed.add(Biomes.OCEAN);
        claimed.add(Biomes.FOREST);
        ids.add(OCEAN.id());
        ids.add(FOREST.id());
        for (Biome biome : Biome.REGISTRY) {
            if (biome == null || claimed.contains(biome)) continue;
            ResourceLocation key = Biome.REGISTRY.getNameForObject(biome);
            if (key == null || key.getPath().isEmpty()) continue;
            ResourceLocation id = axisId(key);
            if (!ids.add(id)) continue;
            result.add(EnvironmentAxis.Builder.axis(id.getPath())
                    .fallback(EnvironmentFallback.biome(biome)).build());
        }
        return Collections.unmodifiableList(result);
    }

    public static List<EnvironmentAxis> all() { return ALL; }
    /** Called at the lifecycle boundary after optional mods finish biome registration. */
    public static synchronized void refresh() {
        int count = Biome.REGISTRY.getKeys().size();
        if (count != discoveredCount) {
            ALL = discover(); discoveredCount = count; EnvironmentAxisRegistry.refresh();
        }
    }
    /** Vanilla aliases remain stable; addon names retain their namespace to prevent same-path collisions. */
    public static ResourceLocation axisId(ResourceLocation biome) {
        if (biome.equals(new ResourceLocation("minecraft", "ocean"))) return OCEAN.id();
        if (biome.equals(new ResourceLocation("minecraft", "forest"))) return FOREST.id();
        return new ResourceLocation("cloudworks", biome.getNamespace().equals("minecraft") ? biome.getPath()
                : "biome/" + biome.getNamespace() + "/" + biome.getPath());
    }
    public static EnvironmentAxis byBiome(ResourceLocation biome) {
        for (EnvironmentAxis axis : ALL) if (biome.equals(Biome.REGISTRY.getNameForObject(axis.fallback().biome()))) return axis;
        refresh();
        for (EnvironmentAxis axis : ALL) if (biome.equals(Biome.REGISTRY.getNameForObject(axis.fallback().biome()))) return axis;
        return null;
    }

    public static EnvironmentAxis byId(ResourceLocation id) {
        for (EnvironmentAxis axis : ALL) if (axis.id().equals(id)) return axis;
        return null;
    }
}

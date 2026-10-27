package mekanism.cloudworks.api.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.util.ResourceLocation;

/** Read-only aggregate lookup over the four independent axis declaration sources. */
public final class EnvironmentAxisRegistry {
    private static volatile List<EnvironmentAxis> ALL;

    static { refresh(); }

    /** Lifecycle refresh after optional biome registrations; normal lookups read the frozen list. */
    public static synchronized void refresh() {
        List<EnvironmentAxis> axes = new ArrayList<>();
        axes.addAll(DimensionAxes.all());
        axes.addAll(BiomeAxes.all());
        axes.addAll(StructureAxes.ALL);
        axes.addAll(WeatherAxes.ALL);
        ALL = Collections.unmodifiableList(axes);
    }

    private EnvironmentAxisRegistry() {}

    public static List<EnvironmentAxis> all() { return ALL; }

    public static EnvironmentAxis byId(ResourceLocation id) {
        for (EnvironmentAxis axis : ALL) if (axis.id().equals(id)) return axis;
        return null;
    }

    public static EnvironmentAxis byIdString(String id) {
        if (id == null || id.isEmpty()) return null;
        try { return byId(new ResourceLocation(id)); }
        catch (RuntimeException invalid) { return null; }
    }
}

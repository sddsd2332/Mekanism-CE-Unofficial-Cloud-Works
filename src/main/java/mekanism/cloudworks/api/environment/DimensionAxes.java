package mekanism.cloudworks.api.environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.DimensionType;

/** Dimension-axis declarations and registry discovery. */
public final class DimensionAxes {
    public static final EnvironmentAxis NETHER = EnvironmentAxis.Builder.axis("nether_heat_source")
            .fallback(EnvironmentFallback.dimension(DimensionType.NETHER))
            .effectId("nether_heat_sources")
            .build();

    private static final List<EnvironmentAxis> ALL = discover();

    private DimensionAxes() {}

    private static List<EnvironmentAxis> discover() {
        List<EnvironmentAxis> result = new ArrayList<>();
        result.add(NETHER);
        Set<DimensionType> claimed = new HashSet<>();
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        claimed.add(DimensionType.NETHER);
        ids.add(NETHER.id());
        for (DimensionType dimension : DimensionType.values()) {
            if (claimed.contains(dimension)) continue;
            String path = dimension.getName().toLowerCase(Locale.ROOT);
            ResourceLocation id = new ResourceLocation("cloudworks", path);
            if (!ids.add(id)) continue;
            result.add(EnvironmentAxis.Builder.axis(path)
                    .fallback(EnvironmentFallback.dimension(dimension)).build());
        }
        return Collections.unmodifiableList(result);
    }

    public static List<EnvironmentAxis> all() { return ALL; }

    public static EnvironmentAxis byId(ResourceLocation id) {
        for (EnvironmentAxis axis : ALL) if (axis.id().equals(id)) return axis;
        return null;
    }

    public static EnvironmentAxis byType(DimensionType type) {
        if (type == null) return null;
        for (EnvironmentAxis axis : ALL)
            if (axis.fallback() != null && axis.fallback().dimension() == type) return axis;
        return null;
    }
}

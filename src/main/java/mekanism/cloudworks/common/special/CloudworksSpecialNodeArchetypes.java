package mekanism.cloudworks.common.special;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import java.util.Collections;
import mekanism.cloudworks.api.CloudworksAPI;
import mekanism.cloudworks.api.node.CloudworksSpecialNodeType;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;

/**
 * Special-node archetype registry. Environment archetypes are derived from
 * {@link EnvironmentAxisRegistry} — the single declaration source (B4) — so the axis
 * list exists exactly once. Player-specific resources live in .dat files and
 * are not part of this registry.
 */
public final class CloudworksSpecialNodeArchetypes {
    public static final ResourceLocation INFINITE_RESOURCES = new ResourceLocation("cloudworks", "infinite_resources");
    public static final ResourceLocation NETHER_HEAT_SOURCE = DimensionAxes.NETHER.id();
    public static final ResourceLocation OCEAN_ENVIRONMENT = BiomeAxes.OCEAN.id();
    public static final ResourceLocation FOREST_ENVIRONMENT = BiomeAxes.FOREST.id();
    public static final ResourceLocation RAIN_ENVIRONMENT = WeatherAxes.RAIN.id();
    public static final ResourceLocation THUNDER_ENVIRONMENT = WeatherAxes.THUNDER.id();

    private static CloudworksSpecialNodeArchetype environment(ResourceLocation id, String translationKey) {
        return new CloudworksSpecialNodeArchetype(id, translationKey,
                CloudworksSpecialNodeArchetype.Kind.SOURCE, Collections.emptyList(), config -> {
                    if (!config.isEmpty()) throw new IllegalArgumentException("Environment sources have no editable parameters");
                }, false);
    }

    private static final Map<String, CloudworksSpecialNodeArchetype> TYPES = new LinkedHashMap<>();

    private static void seedBuiltIns(Map<String, CloudworksSpecialNodeArchetype> types) {
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
            types.put(axis.id().toString(), environment(axis.id(), "cloudworks.special." + axis.id().getPath()));
    }

    static {
        // Built-ins are seeded at class load so pre-lifecycle read paths keep
        // seeing them; the lifecycle drain only adds addon declarations.
        TYPES.put(INFINITE_RESOURCES.toString(),
                new CloudworksSpecialNodeArchetype(INFINITE_RESOURCES,
                        "cloudworks.special.infinite_resources", CloudworksSpecialNodeArchetype.Kind.SOURCE));
        seedBuiltIns(TYPES);
    }

    private CloudworksSpecialNodeArchetypes() {
    }

    /** CloudWorks lifecycle entry: drains the API queue, then seeds built-ins; duplicates abort loading. */
    public static void freezeAndRegister() {
        Map<String, CloudworksSpecialNodeArchetype> checkpoint = new LinkedHashMap<>(TYPES);
        try {
            CloudworksAPI.freezeAndDrainSpecialNodes(type -> {
                CloudworksSpecialNodeArchetype converted = new CloudworksSpecialNodeArchetype(type.id(),
                        type.translationKey(),
                        type.kind() == CloudworksSpecialNodeType.Kind.SOURCE
                                ? CloudworksSpecialNodeArchetype.Kind.SOURCE
                                : CloudworksSpecialNodeArchetype.Kind.SINK,
                        type.explicitPorts() ? type.ports() : null, type.configValidator(), type.requiresResourceSelection(), type.module());
                if (TYPES.containsKey(converted.idString()))
                    throw new IllegalArgumentException("Duplicate special node: " + converted.idString());
                TYPES.put(converted.idString(), converted);
            });
            // The built-in infinite-resources source always exists (first consumer of the contract).
            TYPES.putIfAbsent(INFINITE_RESOURCES.toString(),
                    new CloudworksSpecialNodeArchetype(INFINITE_RESOURCES,
                            "cloudworks.special.infinite_resources", CloudworksSpecialNodeArchetype.Kind.SOURCE));
            for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
                TYPES.putIfAbsent(axis.id().toString(),
                        environment(axis.id(), "cloudworks.special." + axis.id().getPath()));
        } catch (RuntimeException failure) {
            // Transactional drain (audit fix): no partial writes survive a refusal.
            TYPES.clear();
            TYPES.putAll(checkpoint);
            throw failure;
        }
    }

    public static CloudworksSpecialNodeArchetype get(String id) {
        return id == null ? null : TYPES.get(id);
    }

    public static CloudworksSpecialNodeArchetype require(String id) {
        CloudworksSpecialNodeArchetype result = get(id);
        if (result == null) throw new IllegalArgumentException("Unknown special node: " + id);
        return result;
    }

    public static boolean known(String id) {
        return get(id) != null;
    }

    public static List<CloudworksSpecialNodeArchetype> values() {
        return new ArrayList<>(TYPES.values());
    }

    public static CloudworksSpecialNodeArchetype infiniteResources() {
        return Objects.requireNonNull(TYPES.get(INFINITE_RESOURCES.toString()),
                "infinite resources archetype is seeded by the lifecycle drain");
    }

    /** Test-only: drops addon declarations and restores the built-in seed. */
    public static void resetForTests() {
        TYPES.clear();
        TYPES.put(INFINITE_RESOURCES.toString(),
                new CloudworksSpecialNodeArchetype(INFINITE_RESOURCES,
                        "cloudworks.special.infinite_resources", CloudworksSpecialNodeArchetype.Kind.SOURCE));
        seedBuiltIns(TYPES);
    }
}

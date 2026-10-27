package mekanism.cloudworks.common.card;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.common.card.CloudworksCardEffect.Target;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import mekanism.cloudworks.common.service.CloudworksOutputMultiplier;

/**
 * Startup registry for built-in and addon card declarations. Environment axis
 * effects are generated from {@link EnvironmentAxisRegistry} — the single declaration
 * source (B4). The generate block sits textually before the byId compat fields
 * so any class-loading entry point (item fields, tests reading OCEAN first)
 * runs generation before the lookups.
 */
public final class CloudworksCardEffects {
    private static final Map<ResourceLocation, CloudworksCardEffect> EFFECTS = new LinkedHashMap<>();
    private static boolean frozen;

    public static final CloudworksCardEffect LINE_EXPANSION = register(CloudworksCardEffect.increase(
            id("line_expansion"), Target.LINE_LIMIT, 1,
            "chat.cloudworks.line_expanded", "chat.cloudworks.line_limit_max"));
    public static final CloudworksCardEffect NODE_EXPANSION = register(CloudworksCardEffect.increase(
            id("node_expansion"), Target.NODE_LIMIT, 1,
            "chat.cloudworks.nodes_expanded", "chat.cloudworks.node_limit_max"));
    public static final CloudworksCardEffect WORK_RATE = register(CloudworksCardEffect.increase(
            id("work_rate"), Target.WORK_RATE, 1,
            "chat.cloudworks.work_rate_upgraded", "chat.cloudworks.work_rate_max"));
    public static final CloudworksCardEffect OUTPUT_MULTIPLIER = register(CloudworksCardEffect.increase(
            id("output_multiplier"), Target.OUTPUT_MULTIPLIER,
            CloudworksOutputMultiplier.SCALE,
            "chat.cloudworks.output_multiplier_upgraded", "chat.cloudworks.output_multiplier_max"));
    public static final CloudworksCardEffect WAREHOUSE_CAPACITY = register(CloudworksCardEffect.increase(
            id("warehouse_capacity"), Target.WAREHOUSE_CAP_MULTIPLIER, 1,
            "chat.cloudworks.warehouse_capacity_upgraded", "chat.cloudworks.warehouse_capacity_max"));
    public static final CloudworksCardEffect OFFLINE = register(CloudworksCardEffect.unlock(
            id("offline_processing"), Target.OFFLINE_PROCESSING,
            "chat.cloudworks.offline_unlocked", "chat.cloudworks.offline_already_unlocked"));

    static {
        // Axis effects are generated from the declaration source: effect ids stay
        // byte-compatible with existing cloud saves. Placement allowances are unbounded
        // (2026-10-23 user ruling): the limit sits at Integer.MAX_VALUE.
        for (EnvironmentAxis axis : EnvironmentAxisRegistry.all())
            register(CloudworksCardEffect.cloudState(axis.effectId(), 1, Integer.MAX_VALUE,
                    "chat.cloudworks.env_source_added", "cloudworks.error.storage",
                    new TextComponentTranslation(axis.displayKey())));
    }

    // Compat fields resolve AFTER the generate block (textual static-init order, §9).
    public static final CloudworksCardEffect NETHER = byId(DimensionAxes.NETHER.effectId());
    public static final CloudworksCardEffect OCEAN = byId(BiomeAxes.OCEAN.effectId());
    public static final CloudworksCardEffect FOREST = byId(BiomeAxes.FOREST.effectId());
    public static final CloudworksCardEffect RAIN = byId(WeatherAxes.RAIN.effectId());
    public static final CloudworksCardEffect THUNDER = byId(WeatherAxes.THUNDER.effectId());

    private CloudworksCardEffects() {}

    private static ResourceLocation id(String name) { return new ResourceLocation("cloudworks", name); }

    private static CloudworksCardEffect byId(ResourceLocation id) {
        CloudworksCardEffect effect = EFFECTS.get(Objects.requireNonNull(id, "id"));
        if (effect == null) throw new IllegalStateException("Environment axis effect not generated: " + id);
        return effect;
    }

    public static synchronized CloudworksCardEffect register(CloudworksCardEffect effect) {
        Objects.requireNonNull(effect, "effect");
        if (frozen) throw new IllegalStateException("Cloudworks card effects are frozen");
        if (EFFECTS.putIfAbsent(effect.id(), effect) != null)
            throw new IllegalArgumentException("Duplicate cloud card effect: " + effect.id());
        return effect;
    }

    public static synchronized CloudworksCardEffect get(ResourceLocation id) {
        CloudworksCardEffect effect = EFFECTS.get(Objects.requireNonNull(id, "id"));
        if (effect == null) throw new IllegalArgumentException("Unknown cloud card effect: " + id);
        return effect;
    }

    public static synchronized Map<ResourceLocation, CloudworksCardEffect> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(EFFECTS));
    }

    public static synchronized void freeze() { frozen = true; }
}

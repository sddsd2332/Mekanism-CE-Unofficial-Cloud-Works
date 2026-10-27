package mekanism.cloudworks.api.environment;

import java.util.Locale;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.util.ResourceLocation;

/** Immutable simulated environment snapshot supplied to virtual machine callbacks. */
public final class CloudworksGenerationContext {
    public static final ResourceLocation NETHER_ENVIRONMENT = new ResourceLocation("cloudworks", "nether_heat_source");
    public static final ResourceLocation OCEAN_ENVIRONMENT = new ResourceLocation("cloudworks", "ocean_environment");
    public static final ResourceLocation FOREST_ENVIRONMENT = new ResourceLocation("cloudworks", "forest_environment");
    public static final ResourceLocation RAIN_ENVIRONMENT = new ResourceLocation("cloudworks", "rain_environment");
    public static final ResourceLocation THUNDER_ENVIRONMENT = new ResourceLocation("cloudworks", "thunder_environment");
    /** Derived state: rain/thunder coverage crossing a snowy biome axis (Biome.isSnowyBiome). */
    public static final ResourceLocation SNOWY_ENVIRONMENT = new ResourceLocation("cloudworks", "snowy_environment");
    /** Derived only from enabled, fulfilled environment source geometry; installed cards alone do not grant it. */
    public static final ResourceLocation ENVIRONMENT_NODE_COVERAGE = new ResourceLocation("cloudworks", "environment_node_coverage");

    private final int simulatedHeight;
    private final int clockTick;
    private final int lavaHeatCards;
    private final Set<ResourceLocation> environmentTypes;

    public CloudworksGenerationContext(int simulatedHeight, int clockTick, int lavaHeatCards,
                                       boolean netherEnvironment) {
        this(simulatedHeight, clockTick, lavaHeatCards,
                types(netherEnvironment, false, false, false, false));
    }

    public CloudworksGenerationContext(int simulatedHeight, int clockTick, int lavaHeatCards,
                                       boolean netherEnvironment, boolean oceanEnvironment,
                                       boolean forestEnvironment, boolean rainEnvironment,
                                       boolean thunderEnvironment) {
        this(simulatedHeight, clockTick, lavaHeatCards,
                types(netherEnvironment, oceanEnvironment, forestEnvironment, rainEnvironment, thunderEnvironment));
    }

    /** Creates a snapshot with built-in or addon-defined environment type identifiers. */
    public CloudworksGenerationContext(int simulatedHeight, int clockTick, int lavaHeatCards,
                                       Collection<ResourceLocation> environmentTypes) {
        this.simulatedHeight = Math.max(0, simulatedHeight);
        this.clockTick = Math.max(1, Math.min(24000, clockTick));
        this.lavaHeatCards = Math.max(0, lavaHeatCards);
        Set<ResourceLocation> copy = new LinkedHashSet<>();
        for (ResourceLocation type : Objects.requireNonNull(environmentTypes, "environmentTypes"))
            copy.add(Objects.requireNonNull(type, "environment type"));
        this.environmentTypes = Collections.unmodifiableSet(copy);
    }

    public int simulatedHeight() { return simulatedHeight; }
    public int clockTick() { return clockTick; }
    public int lavaHeatCards() { return lavaHeatCards; }
    public Set<ResourceLocation> environmentTypes() { return environmentTypes; }
    public boolean hasEnvironment(ResourceLocation type) {
        return environmentTypes.contains(Objects.requireNonNull(type, "type"));
    }
    public CloudworksGenerationContext withEnvironment(ResourceLocation type) {
        Set<ResourceLocation> updated = new LinkedHashSet<>(environmentTypes);
        updated.add(Objects.requireNonNull(type, "type"));
        return new CloudworksGenerationContext(simulatedHeight, clockTick, lavaHeatCards, updated);
    }
    public boolean netherEnvironment() { return hasEnvironment(NETHER_ENVIRONMENT); }
    public boolean oceanEnvironment() { return hasEnvironment(OCEAN_ENVIRONMENT); }
    public boolean forestEnvironment() { return hasEnvironment(FOREST_ENVIRONMENT); }
    public boolean rainEnvironment() { return hasEnvironment(RAIN_ENVIRONMENT); }
    public boolean thunderEnvironment() { return hasEnvironment(THUNDER_ENVIRONMENT); }

    private static Set<ResourceLocation> types(boolean nether, boolean ocean, boolean forest,
            boolean rain, boolean thunder) {
        Set<ResourceLocation> types = new LinkedHashSet<>();
        if (nether) types.add(NETHER_ENVIRONMENT);
        if (ocean) types.add(OCEAN_ENVIRONMENT);
        if (forest) types.add(FOREST_ENVIRONMENT);
        if (rain) types.add(RAIN_ENVIRONMENT);
        if (thunder) types.add(THUNDER_ENVIRONMENT);
        return types;
    }

    public static String timeOfDay(int clockTick) {
        int tick = Math.floorMod(clockTick - 1, 24000) + 1;
        if (tick <= 5000) return "morning";
        if (tick <= 10000) return "noon";
        if (tick <= 13800) return "dusk";
        if (tick <= 18000) return "evening";
        if (tick <= 22200) return "midnight";
        return "dawn";
    }

    public static String clockTime(int clockTick) {
        int minutes = (int) ((Math.floorMod((long) clockTick - 1, 24000L) * 1440 / 24000 + 360) % 1440);
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    public CloudworksGenerationContext nextTick() {
        return new CloudworksGenerationContext(simulatedHeight, clockTick == 24000 ? 1 : clockTick + 1,
                lavaHeatCards, environmentTypes);
    }
}

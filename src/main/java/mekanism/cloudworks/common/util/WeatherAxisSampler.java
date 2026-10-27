package mekanism.cloudworks.common.util;

import java.util.Objects;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import mekanism.cloudworks.api.environment.WeatherAxes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/**
 * Weather sampling entry point. Biome context is resolved before any weather
 * predicate runs so additional weather declarations can make their decision
 * from the current biome without changing card plumbing.
 */
public final class WeatherAxisSampler {
    private WeatherAxisSampler() {}

    public static EnvironmentAxis find(World world, EntityPlayer player) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        EnvironmentAxis biome = BiomeAxisSampler.find(world, player);
        if (biome == null) return null;
        for (EnvironmentAxis axis : WeatherAxes.all()) {
            if (matches(axis, world, player, biome)) return axis;
        }
        return null;
    }

    public static boolean matches(EnvironmentAxis axis, World world, EntityPlayer player,
            EnvironmentAxis biome) {
        if (axis == null || axis.kind() != EnvironmentAxis.Kind.WEATHER || biome == null) return false;
        if (axis == WeatherAxes.THUNDER) return world.isThundering();
        if (axis == WeatherAxes.RAIN) return world.isRaining();
        return false;
    }
}

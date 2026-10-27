package mekanism.cloudworks.common.util;

import java.util.Objects;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/** Shared first stage for environment sampling: resolve the biome at the player. */
public final class BiomeAxisSampler {
    private BiomeAxisSampler() {}

    public static EnvironmentAxis find(World world, EntityPlayer player) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(player, "player");
        BlockPos position = player.getPosition();
        Biome biome = world.getBiome(position);
        if (biome == null) return null;
        for (EnvironmentAxis axis : BiomeAxes.all()) {
            if (axis.fallback() != null && axis.fallback().biome() == biome) return axis;
        }
        return null;
    }
}

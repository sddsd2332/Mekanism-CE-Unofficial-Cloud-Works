package mekanism.cloudworks.common.util;

import mekanism.cloudworks.api.environment.EnvironmentAxisRegistry;
import mekanism.cloudworks.api.environment.DimensionAxes;
import mekanism.cloudworks.api.environment.BiomeAxes;
import mekanism.cloudworks.api.environment.WeatherAxes;
import mekanism.cloudworks.api.environment.EnvironmentAxis;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.IChunkGenerator;

import java.util.Set;

/**
 * Structure-axis sampling (step 3). Vanilla structure data lives on the chunk
 * generator, and {@link IChunkGenerator#isInsideStructure} is the public
 * per-component bounding-box query behind /locate — no access transformer is
 * needed. Scattered features ("Temple") build four different structures from
 * one engine, so the axis biome constraint mirrors the vanilla Start
 * constructor's biome switch to disambiguate the subtype.
 */
public final class CloudworksStructureSampler {
    private CloudworksStructureSampler() {}

    /** True when the position sits inside a generated component of the axis' structure. */
    public static boolean inside(World world, BlockPos pos, EnvironmentAxis axis) {
        if (axis == null || axis.kind() != EnvironmentAxis.Kind.STRUCTURE) return false;
        String structureName = axis.structureName();
        if (structureName == null || !(world instanceof WorldServer)) return false;
        IChunkGenerator generator = ((WorldServer) world).getChunkProvider().chunkGenerator;
        if (generator == null || !generator.isInsideStructure(world, structureName, pos)) return false;
        Set<Biome> allowed = axis.structureBiomes();
        return allowed == null || allowed.contains(world.getBiome(pos));
    }
}

package mekanism.cloudworks.api.environment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.minecraft.init.Biomes;
import net.minecraft.util.ResourceLocation;

/** Curated structure-axis declarations; 1.12 has no structure registry to scan. */
public final class StructureAxes {
    public static final List<EnvironmentAxis> ALL = Collections.unmodifiableList(Arrays.asList(
            EnvironmentAxis.Builder.axis("village").fallback(EnvironmentFallback.structure())
                    .structureName("Village").chestTables("minecraft:chests/village_blacksmith").build(),
            EnvironmentAxis.Builder.axis("desert_temple").fallback(EnvironmentFallback.structure())
                    .structureName("Temple").structureBiomes(Biomes.DESERT, Biomes.DESERT_HILLS)
                    .chestTables("minecraft:chests/desert_pyramid").build(),
            EnvironmentAxis.Builder.axis("jungle_temple").fallback(EnvironmentFallback.structure())
                    .structureName("Temple").structureBiomes(Biomes.JUNGLE, Biomes.JUNGLE_HILLS)
                    .chestTables("minecraft:chests/jungle_temple").build(),
            EnvironmentAxis.Builder.axis("witch_hut").fallback(EnvironmentFallback.structure())
                    .structureName("Temple").structureBiomes(Biomes.SWAMPLAND)
                    .spawnLoad("minecraft:witch", 1, 1, 1).build(),
            EnvironmentAxis.Builder.axis("igloo").fallback(EnvironmentFallback.structure())
                    .structureName("Temple").structureBiomes(Biomes.ICE_PLAINS, Biomes.COLD_TAIGA)
                    .chestTables("minecraft:chests/igloo_chest").build(),
            EnvironmentAxis.Builder.axis("stronghold").fallback(EnvironmentFallback.structure())
                    .structureName("Stronghold")
                    .chestTables("minecraft:chests/stronghold_corridor",
                            "minecraft:chests/stronghold_crossing",
                            "minecraft:chests/stronghold_library").build(),
            EnvironmentAxis.Builder.axis("ocean_monument").fallback(EnvironmentFallback.structure())
                    .structureName("Monument").spawnLoad("minecraft:guardian", 1, 2, 4).build(),
            EnvironmentAxis.Builder.axis("woodland_mansion").fallback(EnvironmentFallback.structure())
                    .structureName("Mansion").chestTables("minecraft:chests/woodland_mansion").build(),
            EnvironmentAxis.Builder.axis("end_city").fallback(EnvironmentFallback.structure())
                    .structureName("EndCity").chestTables("minecraft:chests/end_city_treasure").build(),
            EnvironmentAxis.Builder.axis("nether_fortress").fallback(EnvironmentFallback.structure())
                    .structureName("Fortress").chestTables("minecraft:chests/nether_bridge")
                    .spawnLoad("minecraft:blaze", 10, 2, 3)
                    .spawnLoad("minecraft:zombie_pigman", 5, 4, 4)
                    .spawnLoad("minecraft:wither_skeleton", 8, 5, 5)
                    .spawnLoad("minecraft:skeleton", 2, 5, 5)
                    .spawnLoad("minecraft:magma_cube", 3, 4, 4).build(),
            EnvironmentAxis.Builder.axis("mineshaft").fallback(EnvironmentFallback.structure())
                    .structureName("Mineshaft").chestTables("minecraft:chests/abandoned_mineshaft").build()));

    public static final EnvironmentAxis VILLAGE = ALL.get(0);
    public static final EnvironmentAxis DESERT_TEMPLE = ALL.get(1);
    public static final EnvironmentAxis JUNGLE_TEMPLE = ALL.get(2);
    public static final EnvironmentAxis WITCH_HUT = ALL.get(3);
    public static final EnvironmentAxis IGLOO = ALL.get(4);
    public static final EnvironmentAxis STRONGHOLD = ALL.get(5);
    public static final EnvironmentAxis OCEAN_MONUMENT = ALL.get(6);
    public static final EnvironmentAxis WOODLAND_MANSION = ALL.get(7);
    public static final EnvironmentAxis END_CITY = ALL.get(8);
    public static final EnvironmentAxis NETHER_FORTRESS = ALL.get(9);
    public static final EnvironmentAxis MINESHAFT = ALL.get(10);

    private StructureAxes() {}

    public static EnvironmentAxis byId(ResourceLocation id) {
        for (EnvironmentAxis axis : ALL) if (axis.id().equals(id)) return axis;
        return null;
    }
}

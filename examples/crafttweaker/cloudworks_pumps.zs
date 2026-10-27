// Startup declarations for the shared pump registry. Adapt before copying into scripts.
import mods.cloudworks.Pumps;

// Replace ocean water while retaining native tier scaling and the builtin FILTER/heavy-water rule.
Pumps.remove("cloudworks:pump_water");
Pumps.biome("pack:ocean_water", "minecraft:ocean",
    <liquid:water> * 1000, 20, 100, "native_water", "without");

// Nether lava stays at 1000mB per operation on every tier and ignores FILTER.
Pumps.remove("cloudworks:pump_lava");
Pumps.add("pack:nether_lava", "cloudworks:nether_heat_source",
    <liquid:lava> * 1000, 20, 100, "fixed", "any");

// Optional mod biome/fluid: replace both registry names before uncommenting.
// Pumps.biome("pack:marsh_oil", "biome_mod:marsh",
//     <liquid:oil> * 250, 40, 300, "per_process", "any");

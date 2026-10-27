// 泵的环境配方。复制到 scripts 后按需修改；这是对全服泵规则的启动声明。
import mods.cloudworks.Pumps;

// 替换海洋水规则，保留原生等级水量以及 FILTER 重水规则。
Pumps.remove("cloudworks:pump_water");
Pumps.biome("pack:ocean_water", "minecraft:ocean",
    <liquid:water> * 1000, 20, 100, "native_water", "without");

// 下界熔岩每次1000mB，不额外乘等级；与重水无关。
Pumps.remove("cloudworks:pump_lava");
Pumps.add("pack:nether_lava", "cloudworks:nether_heat_source",
    <liquid:lava> * 1000, 20, 100, "fixed", "any");

// 其他模组群系/流体示意：替换实际注册名后再取消注释。
// Pumps.biome("pack:marsh_oil", "biome_mod:marsh",
//     <liquid:oil> * 250, 40, 300, "per_process", "any");

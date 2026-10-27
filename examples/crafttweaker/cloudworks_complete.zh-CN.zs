// 云工坊完整 CRT 接入示例，适用于 Mekanism CE 1.12.2。
// 本文件仅为示例；模组不会自动加载 examples/ 目录中的脚本。
//
// 重要：以下 minecraft:stick + NBT 仅用于隔离演示机器身份。
// 在真实服务器使用前，应全部替换成整合包专用物品。注册原版物品身份会让
// 全服所有匹配该身份的物品获得对应的云工坊机器能力。
//
// Mekanism、MoreMachine、MultiblockMachine 的默认适配器由 Java 自动注册，
// 并直接读取原生配方表。不要在 CRT 中重复注册这些机器或复制原生配方。

import mods.cloudworks.Machines;
import mods.cloudworks.Slots;
import mods.cloudworks.Resources;
import mods.cloudworks.Recipes;
import mods.cloudworks.Generators;
import mods.cloudworks.Multiblocks;
import mods.cloudworks.Thermal;
import mods.cloudworks.Upgrades;
import mods.cloudworks.LootDrops;
import mods.cloudworks.Pumps;
import mods.cloudworks.Storage;
import crafttweaker.player.IPlayer;
import crafttweaker.item.IItemStack;

// 此函数只有显式调用后才替换全服泵规则；独立完整示例见 cloudworks_pumps.zh-CN.zs。
function cloudworksPumpRulesExample() {
    Pumps.remove("cloudworks:pump_water");
    Pumps.biome("cloudworks_example:ocean_water", "minecraft:ocean", <liquid:water> * 1000, 20, 100, "native_water", "without");
    Pumps.remove("cloudworks:pump_lava");
    Pumps.add("cloudworks_example:nether_lava", "cloudworks:nether_heat_source", <liquid:lava> * 1000, 20, 100, "fixed", "any");
}

// 运行期奖励辅助函数。加载此示例不会自动调用，也不会凭空发放奖励。
// 原料消耗、任务校验和只发一次由整合包负责；接口不扣背包物品。
// terminalItemUploadsEnabled 只限制终端，不影响这些受信任的脚本入库。
function rewardCloudMachines(player as IPlayer, supportedMachine as IItemStack) {
    val request = Storage.addMachine(player, supportedMachine, 4);
    // 后续服务器回调检查 request.done，再读取 accepted/error。
    // 不要阻塞主线程，也不要因请求尚未完成就重复发放。
}

function rewardCloudSpeed(player as IPlayer) {
    val request = Storage.addUpgradeType(player, "speed", 8);
    // 自定义类型：Storage.addUpgradeType(player, "addonmod:module", 1);
    // 按物品绑定：Storage.addUpgrade(player, Upgrades.getItem("energy"), 8);
    // 团队云：Storage.addMachineTo(player, ownerUuid, supportedMachine, 4);
}

// MMCE 等外部多方块的云端传输：整合包自己负责扣输入和放输出。
function mmceCloudTransfer(player as IPlayer, machine as IItemStack) {
    val upload = Storage.uploadMachine(player, machine, 1);
    val download = Storage.downloadMachine(player, machine, 1);
    // 后续检查 upload.done/download.done；下载成功后将 download.machines
    // 放入 MMCE 输出库存，云工坊不会直接操作外部库存。
}
// output() 和 chanceOutput() 受到玩家云端产出倍率影响。
// 容器、催化剂返还请用 unscaledOutput(resource[, port]) 声明。
// 守恒转换配方在构建器上调用 unscaledOutputs()，保持整份输出原量。

// 产线池默认使用云工坊 speed_module/energy_module；Mek 升级作为节点 CUSTOM 模块安装。
// 可选：替换产线池物品，不改变逐轴曲线；最终绑定不能与节点 CUSTOM 物品冲突。
// 上传、下载严格使用相同的物品、元数据和 NBT 身份。
// 存量池计数保留，下载时使用当前定义的物品。
// Upgrades.setItem("speed", <minecraft:paper>.withTag({cloudUpgrade: "speed"}));
// Upgrades.setItem("energy", <minecraft:paper>.withTag({cloudUpgrade: "energy"}));
// 同一 Mek 升级物品已绑定节点模块时，useNativeItem("speed") 会报告冲突。
// Upgrades.getItem("energy");

// -----------------------------------------------------------------------------
// 在冻结实体／箱子战利结果上追加概率掉落
// -----------------------------------------------------------------------------
// 这些是启动期声明，不会改写原战利品 JSON；相同物品的每条注入仍独立计算概率。
// LootDrops.entity("minecraft:zombie", <minecraft:diamond>, 0.05);
// LootDrops.entityRange("minecraft:skeleton", <minecraft:bone>, 1, 2, 0.25);
// LootDrops.entityMeta("minecraft:zombie", <minecraft:coal>, 1, 1, 3, 0.10);
//
// 轴注入按模拟击杀和每张声明的结构箱表事件掷骰。
// 一个结构有多张箱表时，每张箱表都会单独触发一次轴级注入。
// LootDrops.axis("cloudworks:nether_fortress", <minecraft:emerald>, 0.01);
// LootDrops.axisRange("cloudworks:nether_fortress", <minecraft:gold_ingot>, 1, 2, 0.05);
// LootDrops.axisMeta("cloudworks:nether_fortress", <minecraft:coal>, 1, 1, 2, 0.05);

// -----------------------------------------------------------------------------
// 内置接入范围（自动注册；这里只列覆盖范围，不要重复调用注册）
// -----------------------------------------------------------------------------
// Mek 单机：粉碎机、富集仓、融合机、充能冶炼炉、冲压机、轧制机、拉丝机、
// 车床、合金炉、冶金灌注机、精密锯木机、净化仓、锇压缩机、化学压射室、
// 化学氧化机、环境气体收集器、环境气体收集器(电动型)、热力蒸馏控制器、
// 电阻加热装置、木料加热器、加压反应室、化学灌注器、化学结晶器、电解分离器、
// 回旋式气液转换机、化学溶解室、化学清洗机、同位素离心机、太阳能中子活化器、
// 营养液化器、反质子核合成器、种植机、细胞提取机、细胞分离机、回收机、放射性废料桶。
//
// Mek 工厂：以下全部 19 种已注册 RecipeType 模式均覆盖基础/高级/精英/终极四级：
// 熔炼、富集、粉碎、灌注、提纯、压射、融合、合金、压缩、切割、提取、分离、回收、
// 冲压、压延、拉丝、车削、加压反应室、反质子核合成器。
//
// Mek Generators（安装后）：风力、太阳能、高级太阳能、热力、燃气发电机。
// MoreMachine（安装后）：化学灌注器、化学清洗机、电解分离器、同位素离心机、
// 电动环境气体收集器、太阳能中子活化器、回旋式气液转换机、虚空矿物采集机、
// 放射性废料桶、燃气发电机、风力发电机、太阳能发电机、高级太阳能发电机、
// 化学结晶器、化学氧化机、营养液器、化学溶解室、种植工厂，以上均有四级；
// 另有四档电动泵，FILTER 产重水；19 个等级机器族加 3 个复制器，共 79 个默认身份。
// Mek 默认还包含电动泵、SPS、手动矿辞转换器；Generators 包含生物质发电机和聚变控制器。
// MultiblockMachine（安装后）：大型化学灌注器、大型化学清洗机、大型电解分离器、
// 大型太阳能中子活化器、大型风力发电机、大型燃气发电机。
// 所有可选依赖都存在时，固定默认注册总数为 207。实际配方内容在运行时从已安装模组
// 的原生配方表读取，本脚本不再生成一份可能过期的重复配方清单。

// -----------------------------------------------------------------------------
// 资源、端口、参数、配方、模板、概率产物和光照 API
// -----------------------------------------------------------------------------
val demoProcessor = <minecraft:stick>.withTag({cloudworksExample: "processor"});
val processorSlots = Slots.create()
    .input("item_input", "mekanism:item_stack", 64)
    .recipeFilteredInput("item_input", true) // 默认值；只接收有效配方输入
    .input("catalyst_input", "mekanism:item_stack", 64)
    .template("item_template", "mekanism:item_stack", 64)
    .output("item_output", "mekanism:item_stack", 64)
    .add("single_link_input", "mekanism:item_stack", "INPUT", 64, false)
    .recipeFilteredInput("single_link_input", false); // 通用耗材缓存；与单连接开关独立

Machines.register(demoProcessor, 1, 10.0, 64000.0, 40, processorSlots);
Machines.setProfile(demoProcessor, 2, 12.0, 96000.0, 40);
// 2 是单台并行上限；不足两份也能开工，不同配方/到料时间可独立运行。
// 新增通用合同入口（仅按需使用）：
// Recipes.create("pack:cycle", 20).operationsPerCycle(4)...
// Recipes.create("pack:powered", 1).input(一个精确输入).unscaledOutput(一个单位产物).powerDriven(1000000.0, 1000)...
// Recipes.create("pack:secondary", 20).secondaryFuelInputs(true, false)... // 标注已有气体辅料；不自动添加输入
// Recipes.create("pack:fuel", 1).input(一个精确燃料).prepaidFuel(等价资源, 200, 5.0)... // 每单位等价燃料的 J
// 内置 SPS 与 Multiblocks.sps 共用按供能加工，不受云端产出倍率影响；ticks 只保留 API 参数兼容。
// 词典目标在产线编辑窗口手动配置，不需要 CONFIGURATION 实物样板，不由启动脚本覆盖。
Machines.setLineUpgradePolicy(demoProcessor, 8, 8); // 每台真实机器的产线额度；某轴为 0 则拒绝该轴

// ingredient() 会在启动时展开当前矿辞候选。
Recipes.create("cloudworks_example:ore_processor", 40)
    .input(Resources.ingredient(<ore:ingotIron>, 1), "item_input")
    .input(Resources.item(<minecraft:coal>).withAmount(1), "catalyst_input")
    .template(Resources.item(<minecraft:iron_ingot>), "item_template")
    .output(Resources.item(<minecraft:gold_ingot>), "item_output")
    .chanceOutput(Resources.item(<minecraft:diamond>), 0.05)
    .energy(5.0)
    .sunlight()
    .register(demoProcessor);

// descriptor() 接受 QIOResourceDescriptor.write() 的 IData 数据。
// payload 结构由 codec 决定；以下是 1 个铁锭的原生物品栈 descriptor。
val serializedIron = Resources.descriptor({
    codec: "mekanism:item_stack",
    family: "item",
    codecVersion: 1,
    storageUnitsPerUnit: 1000L,
    payload: {id: "minecraft:iron_ingot", Count: 1 as byte, Damage: 0 as short}
}, 1);
val serializedIronCodec = serializedIron.codec;
val serializedIronAmount = serializedIron.amount;

Recipes.create("cloudworks_example:serialized_descriptor", 20)
    .input(serializedIron)
    .output(Resources.item(<minecraft:iron_nugget>))
    .register(demoProcessor);

// 不指定端口的重载适用于只有一个兼容端口，或有意按 codec 汇总寻址的场景。
Recipes.create("cloudworks_example:default_ports", 20)
    .input(Resources.item(<minecraft:coal>))
    .output(Resources.item(<minecraft:charcoal>))
    .register(demoProcessor);

val demoChemicalProcessor = <minecraft:stick>.withTag({cloudworksExample: "chemical_processor"});
Machines.register(demoChemicalProcessor, 1, 20.0, 128000.0, 100,
    Slots.create()
        .input("fluid_input", "mekanism:fluid_stack", 10000)
        .input("gas_input", "mekanism:gas_stack", 10000)
        .input("infuse_input", "cloudworks:infuse", 1000)
        .output("gas_output", "mekanism:gas_stack", 10000));

Recipes.create("cloudworks_example:fluid_gas_infusion", 100)
    .input(Resources.fluid(<liquid:water>), "fluid_input")
    .input(Resources.gas(<gas:oxygen>), "gas_input")
    .input(Resources.infuse("CARBON", 10), "infuse_input")
    .output(Resources.gas(<gas:hydrogen>), "gas_output")
    .register(demoChemicalProcessor);

// -----------------------------------------------------------------------------
// 派生参数、精确 NBT 别名与注册表查询
// -----------------------------------------------------------------------------
val demoFastProcessor = <minecraft:stick>.withTag({cloudworksExample: "fast_processor"});
Machines.registerFrom(demoFastProcessor, demoProcessor, 3, 18.0, 144000.0, 30);

// 别名在脚本配方和派生机器声明完成后统一解析。
val demoProcessorAlias = <minecraft:stick>.withTag({cloudworksExample: "processor_alias"});
Machines.registerAlias(demoProcessorAlias, demoProcessor);

// 立即查询适用于 Java 内置集成已经注册的机器。脚本中的新声明要到启动收尾阶段
// 才应用；要检查最终结果，请使用本文件末尾的 Machines.dump()。
val nativeCrusher = <mekanism:machineblock:3>;
val crusherRegistered = Machines.isRegistered(nativeCrusher);
val crusherPorts = Machines.getPorts(nativeCrusher);
val crusherProfile = Machines.getProfile(nativeCrusher);
val crusherParallel = Machines.getParallel(nativeCrusher);
val crusherEnergyPerTick = Machines.getEnergyPerTick(nativeCrusher);
val crusherEnergyCapacity = Machines.getEnergyCapacity(nativeCrusher);
val crusherTicks = Machines.getTicks(nativeCrusher);
val crusherAliasTarget = Machines.getAliasTarget(nativeCrusher);

// -----------------------------------------------------------------------------
// 固定发电与资源燃料发电
// -----------------------------------------------------------------------------
val demoConstantGenerator = <minecraft:stick>.withTag({cloudworksExample: "constant_generator"});
Machines.register(demoConstantGenerator, 1, 0.0, 100000.0, 1, Slots.create());
Generators.constant(demoConstantGenerator, 250.0);

val demoFuelGenerator = <minecraft:stick>.withTag({cloudworksExample: "fuel_generator"});
Machines.register(demoFuelGenerator, 1, 0.0, 100000.0, 1,
    Slots.create()
        .input("item_input", "mekanism:item_stack", 64)
        .output("item_output", "mekanism:item_stack", 64));
Generators.fuel(demoFuelGenerator, "cloudworks_example:coal_generator",
    [Resources.item(<minecraft:coal>)],
    [Resources.item(<minecraft:charcoal>)],
    5000.0);

// -----------------------------------------------------------------------------
// CRT 扩展的本体多方块合同
// -----------------------------------------------------------------------------
// Multiblocks.getTypes() 返回以下七种类型：fusion、turbine、evaporation、
// boiler、fission_water、fission_sodium、sps。
val supportedMultiblockTypes = Multiblocks.getTypes();

val demoEvaporation = <minecraft:stick>.withTag({cloudworksExample: "evaporation"});
Multiblocks.evaporation(demoEvaporation, 1, 100, 100000, 100000);
Thermal.solarPanels(demoEvaporation, 4);
Thermal.capacitor(demoEvaporation, 50000.0, 10.0, 100.0);

val demoBoiler = <minecraft:stick>.withTag({cloudworksExample: "boiler"});
Multiblocks.boiler(demoBoiler, 100, 20, 100000, 100000);
Thermal.capacitor(demoBoiler, 50000.0, 10.0, 100.0);

val demoSps = <minecraft:stick>.withTag({cloudworksExample: "sps"});
Multiblocks.sps(demoSps, 100, 1000000000.0);

// 以下合同需要 mekanismgenerators。未安装时请保持注释状态。
// 每个控制器请使用不同且稳定的物品身份。
// val demoWaterFission = <minecraft:stick>.withTag({cloudworksExample: "fission_water"});
// Multiblocks.fission(demoWaterFission, false, 20, 100000, 100000);
// Thermal.capacitor(demoWaterFission, 100000.0, 10.0, 100.0);
//
// val demoSodiumFission = <minecraft:stick>.withTag({cloudworksExample: "fission_sodium"});
// Multiblocks.fission(demoSodiumFission, true, 20, 100000, 100000);
// Thermal.capacitor(demoSodiumFission, 100000.0, 10.0, 100.0);
//
// val demoTurbine = <minecraft:stick>.withTag({cloudworksExample: "turbine"});
// Multiblocks.turbine(demoTurbine, 1000, 28, 7, 1, 64000, 64000, 16000000.0);
//
// val demoFusion = <minecraft:stick>.withTag({cloudworksExample: "fusion"});
// Multiblocks.fusion(demoFusion, 2, false, 10000, 1000000, 1000000000.0);
// Thermal.capacitor(demoFusion, 100000.0, 10.0, 100.0);

// -----------------------------------------------------------------------------
// 热力机器及显式维护示例
// -----------------------------------------------------------------------------
val demoResistiveHeater = <minecraft:stick>.withTag({cloudworksExample: "resistive_heater"});
Thermal.resistive(demoResistiveHeater, 100.0, 40000.0);

val demoFuelwoodHeater = <minecraft:stick>.withTag({cloudworksExample: "fuelwood_heater"});
Thermal.fuelwood(demoFuelwoodHeater, 64);

// 配方删除为显式选择；仅在替换同一精确机器身份的旧脚本声明时取消注释。
// Recipes.remove(demoProcessor, "cloudworks_example:ore_processor");
// Recipes.removeAll(demoProcessor);

// 延迟审计会列出 Java 内置机器、可选兼容和本脚本声明的机器。
Machines.dump();

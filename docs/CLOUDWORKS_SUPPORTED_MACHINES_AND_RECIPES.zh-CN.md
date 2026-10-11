# 云工坊已接入机器与配方清单

核对日期：2026-10-11。

本清单依据当前机器注册与适配器源码，不依据 GUI 中是否出现物品、JEI 中是否有配方或原模组是否提供同名机器。未注册的身份不能因为外观相同就当作已接入。

## 1. 名称与配方口径

- Mek 名称取自 `Mekanism-CE-Unofficial-1.12.2/src/main/resources/assets/mekanism/lang/zh_cn.lang`。
- MoreMachine 名称取自 `MekanismCeu-MoreMachine/src/main/resources/assets/mekceumoremachine/lang/zh_cn.lang`。
- MultiblockMachine 名称取自本体 `src/main/resources/assets/mekanismmultiblockmachine/lang/zh_cn.lang`。
- 表中保留 lang 的原文，包括 `反质子核合成器(WIP)`、`营养液器`、`虚空矿物采掘机`、`中型基础太阳能发电机`，不自行改称。
- 工厂采用 lang 中 `tile.<模式>.<等级>.Factory` 的名称。车削工厂原文本带有重复“工厂”，本清单保留，不修改语言文件。
- 配方列中的大写标识默认指 `RecipeHandler.Recipe.<标识>.get()`，表示实际读取的原生配方表。
- 这里列出已接入的配方种类及执行语义，不伪造一份固定的“每条原料/产物”列表。原生注册、整合包脚本和其他 Mod 会改变实际条目数量及内容。
- 已注册机器不等于所有原生行为已完全模拟；可冻结配方、模拟环境和下文限制共同决定实际可执行范围。

## 2. Mek 单机与虚拟控制器

来源：`CloudworksMekanismBuiltins.register()`。

| 机器名称（lang 原文） | lang key | 已接入配方表 / 合同 | 输入 → 输出及说明 |
| --- | --- | --- | --- |
| 粉碎机 | `tile.MachineBlock.Crusher.name` | `CRUSHER` | 物品 → 物品 |
| 富集仓 | `tile.MachineBlock.EnrichmentChamber.name` | `ENRICHMENT_CHAMBER` | 物品 → 物品 |
| 融合机 | `tile.MachineBlock.Combiner.name` | `COMBINER` | 主物品 + 额外物品 → 物品；两个输入端口 |
| 充能冶炼炉 | `tile.MachineBlock.EnergizedSmelter.name` | `ENERGIZED_SMELTER` | 物品 → 物品 |
| 数控冲床 | `tile.MachineBlock4.Stamping.name` | `STAMPING` | 物品 → 物品；原生能耗、储能及 200 tick 基础周期 |
| 数控轧制机 | `tile.MachineBlock4.Rolling.name` | `ROLLING` | 物品 → 物品；原生能耗、储能及 200 tick 基础周期 |
| 数控拉丝机 | `tile.MachineBlock4.Brushed.name` | `BRUSHED` | 物品 → 物品；原生能耗、储能及 200 tick 基础周期 |
| 数控车床 | `tile.MachineBlock4.Turning.name` | `TURNING` | 物品 → 物品；原生能耗、储能及 200 tick 基础周期 |
| 合金炉 | `tile.MachineBlock4.Alloy.name` | `ALLOY` | 主物品 + 额外物品 → 物品；两个输入端口及原生能耗、储能 |
| 冶金灌注机 | `tile.MachineBlock.MetallurgicInfuser.name` | `METALLURGIC_INFUSER` | 物品 + 灌注资源 → 物品；另有灌注材料物品输入 |
| 精密锯木机 | `tile.MachineBlock2.PrecisionSawmill.name` | `PRECISION_SAWMILL` | 物品 → 主产物 + 概率副产物 |
| 净化仓 | `tile.MachineBlock.PurificationChamber.name` | `PURIFICATION_CHAMBER` | 物品 + 辅助气体 → 物品 |
| 锇压缩机 | `tile.MachineBlock.OsmiumCompressor.name` | `OSMIUM_COMPRESSOR` | 物品 + 辅助气体 → 物品 |
| 化学压射室 | `tile.MachineBlock2.ChemicalInjectionChamber.name` | `CHEMICAL_INJECTION_CHAMBER` | 物品 + 辅助气体 → 物品 |
| 化学氧化机 | `tile.MachineBlock2.ChemicalOxidizer.name` | `CHEMICAL_OXIDIZER` | 物品 → 气体 |
| 环境气体收集器 | `tile.MachineBlock3.AmbientAccumulator.name` | `AMBIENT_ACCUMULATOR` | 无资源输入；按模拟维度选择概率气体输出 |
| 环境气体收集器(电动型) | `tile.MachineBlock3.AmbientAccumulatorEnergy.name` | `AMBIENT_ACCUMULATOR_ENERGY` | 无资源输入；按模拟维度选择概率气体输出 |
| 热力蒸馏控制器 | `tile.BasicBlock.ThermalEvaporationController.name` | `THERMAL_EVAPORATION_PLANT` | 温度决定流体处理速度，支持外部热连接，不检查真实塔形状 |
| 电阻加热装置 | `tile.MachineBlock3.ResistiveHeater.name` | `CloudworksThermalPlan.RESISTIVE` | 实际耗电按原生效率产热，可通过热端口供热 |
| 木料加热器 | `tile.MachineBlock3.FuelwoodHeater.name` | `CloudworksThermalPlan.FUELWOOD` | 冻结燃料燃烧时间产热，保存剩余燃烧 tick 与容器返还 |
| 加压反应室 | `tile.MachineBlock2.PressurizedReactionChamber.name` | `PRESSURIZED_REACTION_CHAMBER` | 物品 + 流体 + 气体 → 物品 + 气体；读取原生周期 |
| 化学灌注器 | `tile.MachineBlock2.ChemicalInfuser.name` | `CHEMICAL_INFUSER` | 左气体 + 右气体 → 气体 |
| 化学结晶器 | `tile.MachineBlock2.ChemicalCrystallizer.name` | `CHEMICAL_CRYSTALLIZER` | 气体 → 物品 |
| 电解分离器 | `tile.MachineBlock2.ElectrolyticSeparator.name` | `ELECTROLYTIC_SEPARATOR` | 流体 → 左气体 + 右气体；独立输出端口 |
| 回旋式气液转换机 | `tile.MachineBlock2.RotaryCondensentrator.name` | `ROTARY_CONDENSENTRATOR` | 四端口双向；原生双向配方展开为两个独立转换方向 |
| 化学溶解室 | `tile.MachineBlock2.ChemicalDissolutionChamber.name` | `CHEMICAL_DISSOLUTION_CHAMBER` | 物品 + 辅助气体 → 气体 |
| 化学清洗机 | `tile.MachineBlock2.ChemicalWasher.name` | `CHEMICAL_WASHER` | 气体 + 流体 → 气体 |
| 同位素离心机 | `tile.MachineBlock3.IsotopicCentrifuge.name` | `ISOTOPIC_CENTRIFUGE` | 气体 → 气体 |
| 太阳能中子活化器 | `tile.MachineBlock3.SolarNeutronActivator.name` | `SOLAR_NEUTRON_ACTIVATOR` | 气体 → 气体；受云工坊模拟光照控制 |
| 营养液化器 | `tile.MachineBlock3.NutritionalLiquifier.name` | `NUTRITIONAL_LIQUIFIER` | 物品 → 气体 |
| 反质子核合成器(WIP) | `tile.MachineBlock3.antiprotonicnucleosynthesizer.name` | `ANTIPROTONIC_NUCLEOSYNTHESIZER` | 物品 + 气体 → 物品；读取原生周期及附加能耗 |
| 种植机 | `tile.MachineBlock3.OrganicFarm.name` | `ORGANIC_FARM` | 物品 + 气体或流体 → 确定物品 + 概率产物 |
| 细胞提取机 | `tile.MachineBlock4.CellExtractor.name` | `CELL_EXTRACTOR` | 物品 → 主产物 + 概率副产物 |
| 细胞分离机 | `tile.MachineBlock4.CellSeparator.name` | `CELL_SEPARATOR` | 物品 → 主产物 + 概率副产物 |
| 回收机 | `tile.MachineBlock4.Recycler.name` | `RECYCLER` | 物品 → 原生 `ChanceOutput2` 概率产物 |
| 放射性废料桶 | `tile.MachineBlock4.radioactive_waste_barrel.name` | 放射性气体衰变合同 | 按原生周期清除配置数量；黑名单气体不衰变；无产物 |

共 36 个非工厂基础身份。灌注物品转灌注资源使用 `InfuseRegistry` 的冻结映射，不是将普通物品煤直接作为灌注类型保存。

## 3. Mek 工厂模式

等级原生并行数：基础 3、高级 5、精英 7、终极 9。每个“等级 + 模式”是独立执行身份，模式通过 `FactoryRecipeTypeCodec` 保存。

当前本体实际存在的 19 种 `RecipeType` 均注册四个等级，注册覆盖夹具逐项检查本体枚举，防止遗漏等级或模式。

| 配方表 | 基础名称 | 高级名称 | 精英名称 | 终极名称 |
| --- | --- | --- | --- | --- |
| `ENERGIZED_SMELTER` | 基础熔炼工厂 | 高级熔炼工厂 | 精英熔炼工厂 | 终极熔炼工厂 |
| `ENRICHMENT_CHAMBER` | 基础富集工厂 | 高级富集工厂 | 精英富集工厂 | 终极富集工厂 |
| `CRUSHER` | 基础粉碎工厂 | 高级粉碎工厂 | 精英粉碎工厂 | 终极粉碎工厂 |
| `METALLURGIC_INFUSER` | 基础灌注工厂 | 高级灌注工厂 | 精英灌注工厂 | 终极灌注工厂 |
| `PURIFICATION_CHAMBER` | 基础提纯工厂 | 高级提纯工厂 | 精英提纯工厂 | 终极提纯工厂 |
| `CHEMICAL_INJECTION_CHAMBER` | 基础压射工厂 | 高级压射工厂 | 精英压射工厂 | 终极压射工厂 |
| `COMBINER` | 基础融合工厂 | 高级融合工厂 | 精英融合工厂 | 终极融合工厂 |
| `ALLOY` | 基础合金工厂 | 高级合金工厂 | 精英合金工厂 | 终极合金工厂 |
| `OSMIUM_COMPRESSOR` | 基础压缩工厂 | 高级压缩工厂 | 精英压缩工厂 | 终极压缩工厂 |
| `PRECISION_SAWMILL` | 基础切割工厂 | 高级切割工厂 | 精英切割工厂 | 终极切割工厂 |
| `CELL_EXTRACTOR` | 基础提取工厂 | 高级提取工厂 | 精英提取工厂 | 终极提取工厂 |
| `CELL_SEPARATOR` | 基础分离工厂 | 高级分离工厂 | 精英分离工厂 | 终极分离工厂 |
| `RECYCLER` | 基础回收工厂 | 高级回收工厂 | 精英回收工厂 | 终极回收工厂 |
| `STAMPING` | 基础冲压工厂 | 高级冲压工厂 | 精英冲压工厂 | 终极冲压工厂 |
| `ROLLING` | 基础压延工厂 | 高级压延工厂 | 精英压延工厂 | 终极压延工厂 |
| `BRUSHED` | 基础拉丝工厂 | 高级拉丝工厂 | 精英拉丝工厂 | 终极拉丝工厂 |
| `TURNING` | 基础车削工厂工厂 | 高级车削工厂工厂 | 精英车削工厂工厂 | 终极车削工厂工厂 |
| `PRESSURIZED_REACTION_CHAMBER` | 基础加压工厂 | 高级加压工厂 | 精英加压工厂 | 终极加压工厂 |
| `ANTIPROTONIC_NUCLEOSYNTHESIZER` | 基础反质子工厂 | 高级反质子工厂 | 精英反质子工厂 | 终极反质子工厂 |

名称 key 分别为 `tile.<模式名>.<Basic|Advanced|Elite|Ultimate>.Factory`；模式名依次为 `Smelting`、`Enriching`、`Crushing`、`Infusing`、`Purifying`、`Injecting`、`Combining`、`Alloy`、`Compressing`、`Sawing`、`Extractor`、`Separator`、`Recycler`、`Stamping`、`Rolling`、`Brushed`、`Turning`、`PRC`、`Nucleosynthesizer`。

四个等级各注册 19 种模式，共 76 个工厂身份。反质子工厂使用独立物品/气体输入端口，保留原配方周期及附加能耗。

种植工厂属于 MoreMachine，四级均已接入，见第 5 节。当前 MekCEU 源码和开发 JAR 没有 `FARM` 工厂模式；Mek lang 中残留的农场工厂名称不代表本体仍有该执行模式。

## 4. Mek Generators 可选机器

需要对应 Generators 方块注册可用。前三种环境发电使用云工坊模拟环境，不访问玩家真实机器所在区块。

| 机器名称（lang 原文） | lang key | 已接入配方 / 合同 | 范围 |
| --- | --- | --- | --- |
| 风力发电机 | `tile.Generator.WindGenerator.name` | `CloudworksWindGeneratorAdapter` | 模拟高度与原生风力配置 → 能量 |
| 太阳能发电机 | `tile.Generator.SolarGenerator.name` | `CloudworksSolarGeneratorAdapter(false)` | 模拟时钟和光照 → 能量 |
| 高级太阳能发电机 | `tile.Generator.AdvancedSolarGenerator.name` | `CloudworksSolarGeneratorAdapter(true)` | 同上，读取高级太阳能配置 |
| 热力发电机 | `tile.Generator.HeatGenerator.name` | `CloudworksHeatGeneratorAdapter` + `CloudworksHeatFuelAdapter` | 模拟岩浆/环境发电及物品、流体燃料合同，含容器返还 |
| 燃气发电机 | `tile.Generator.GasGenerator.name` | `GAS_FUEL_TO_ENERGY_RECIPE` | 实际消耗节点气体，按冻结燃料配方结算能量 |

共有 5 个可选默认身份。原有仅冷却的反应控制器默认绑定已取消；聚变燃料发电适配器由 CRT 指定整合包物品，见第 11 节。

## 5. MoreMachine 等级机器

需要安装 `mekceumoremachine`。下表每行均注册基础 / 高级 / 精英 / 终极四个等级，名称直接来自 MoreMachine lang。

| 基础名称 | 高级名称 | 精英名称 | 终极名称 | 配方表 / 合同 |
| --- | --- | --- | --- | --- |
| 基础化学灌注器 | 高级化学灌注器 | 精英化学灌注器 | 终极化学灌注器 | `CHEMICAL_INFUSER`：双气体 → 气体 |
| 基础化学清洗机 | 高级化学清洗机 | 精英化学清洗机 | 终极化学清洗机 | `CHEMICAL_WASHER`：气体 + 流体 → 气体 |
| 基础电解分离器 | 高级电解分离器 | 精英电解分离器 | 终极电解分离器 | `ELECTROLYTIC_SEPARATOR`：流体 → 左右气体 |
| 基础同位素离心机 | 高级同位素离心机 | 精英同位素离心机 | 终极同位素离心机 | `ISOTOPIC_CENTRIFUGE`：气体 → 气体 |
| 基础环境气体收集器(电动型) | 高级环境气体收集器(电动型) | 精英环境气体收集器(电动型) | 终极环境气体收集器(电动型) | 当前读取 `AMBIENT_ACCUMULATOR`：按模拟维度概率产气 |
| 基础太阳能中子活化器 | 高级太阳能中子活化器 | 精英太阳能中子活化器 | 终极太阳能中子活化器 | `SOLAR_NEUTRON_ACTIVATOR`：受模拟光照门控制的气体转换 |
| 基础回旋式气液转换机 | 高级回旋式气液转换机 | 精英回旋式气液转换机 | 终极回旋式气液转换机 | `ROTARY_CONDENSENTRATOR`：每级一个四端口双向身份 |
| 基础虚空矿物采掘机 | 高级虚空矿物采掘机 | 精英虚空矿物采掘机 | 终极虚空矿物采掘机 | `VoidMineralGeneratorUitls.getCanOre()`：每周期互斥抽取一种矿物 |
| 基础放射性废料桶 | 高级放射性废料桶 | 精英放射性废料桶 | 终极放射性废料桶 | 原生衰变周期、黑名单及配置数量 × 等级并行数 × 10 |
| 基础燃气发电机 | 高级燃气发电机 | 精英燃气发电机 | 终极燃气发电机 | `GAS_FUEL_TO_ENERGY_RECIPE`；还需要 Generators |
| 基础风力发电机 | 高级风力发电机 | 精英风力发电机 | 终极风力发电机 | 风力冻结合同；还需要 Generators |
| 基础太阳能发电机 | 高级太阳能发电机 | 精英太阳能发电机 | 终极太阳能发电机 | 普通太阳能冻结合同；还需要 Generators |
| 中型基础太阳能发电机 | 中型高级太阳能发电机 | 中型精英太阳能发电机 | 中型终极太阳能发电机 | 高级太阳能冻结合同；还需要 Generators |
| 基础化学结晶器 | 高级化学结晶器 | 精英化学结晶器 | 终极化学结晶器 | `CHEMICAL_CRYSTALLIZER`：气体 → 物品 |
| 基础化学氧化机 | 高级化学氧化机 | 精英化学氧化机 | 终极化学氧化机 | `CHEMICAL_OXIDIZER`：物品 → 气体 |
| 基础营养液器 | 高级营养液器 | 精英营养液器 | 终极营养液器 | `NUTRITIONAL_LIQUIFIER`：物品 → 气体 |
| 基础化学溶解室 | 高级化学溶解室 | 精英化学溶解室 | 终极化学溶解室 | `CHEMICAL_DISSOLUTION_CHAMBER`：物品 + 辅助气体 → 气体 |
| 基础种植工厂 | 高级种植工厂 | 精英种植工厂 | 终极种植工厂 | `ORGANIC_FARM`：物品 + 气体/流体 → 确定及概率物品 |

对应 lang key 模板是 `tile.<机器名>.<等级>.name`：

| 表中机器族 | `<机器名>` |
| --- | --- |
| 化学灌注器 | `TierChemicalInfuser` |
| 化学清洗机 | `TierChemicalWasher` |
| 电解分离器 | `TierElectrolyticSeparator` |
| 同位素离心机 | `TierIsotopicCentrifuge` |
| 环境气体收集器(电动型) | `TierAmbientAccumulator` |
| 太阳能中子活化器 | `TierSolarNeutronActivator` |
| 回旋式气液转换机 | `TierRotaryCondensentrator` |
| 虚空矿物采掘机 | `VoidMineralGenerator` |
| 放射性废料桶 | `TierRadioactiveWasteBarrel` |
| 燃气发电机 | `TierGasGenerator` |
| 风力发电机 | `TierWindGenerator` |
| 普通太阳能发电机 | `TierSolarGenerator` |
| 中型太阳能发电机 | `TierAdvancedSolarGenerator` |
| 化学结晶器 | `TierChemicalCrystallizer` |
| 化学氧化机 | `TierChemicalOxidizer` |
| 营养液器 | `TierNutritionalLiquifier` |
| 化学溶解室 | `TierChemicalDissolutionChamber` |
| 种植工厂 | `TierOrganicFarm` |

`<等级>` 为 `Basic`、`Advanced`、`Elite`、`Ultimate`。

等级参数和身份：

- 原生并行数为 3 / 5 / 7 / 9。
- 部分机器等级保存在根 NBT 的 `tier`；化学结晶器、化学氧化机、营养液器、化学溶解室、种植工厂使用 metadata 等级身份。
- 回旋式气液转换机只使用 `tier`，忽略原物品的 `mekData.mode`；与本体共用双向适配器，每个方向独立声明输入和输出。
- 虚空矿物采掘机不消耗资源输入；每次只抽取一种矿物并按等级数量产出，保留候选重复权重。
- 种植工厂的基础辅助气体/流体预算按 200 tick 冻结；与 Mek 基础种植机当前预算不同。
- 放射性废料桶不产生资源或能量，也不模拟辐射泄漏。

18 个等级机器族对应 72 个等级组合，本节共 72 个执行身份。

## 6. MoreMachine 复制机

| 机器名称（lang 原文） | lang key | 已接入配方表 | 执行规则 |
| --- | --- | --- | --- |
| 复制机(物品) | `tile.ReplicatorItemStack.name` | `REPLICATOR_ITEMSTACK_RECIPE` | 物品模板 + UU 气体 → 物品 |
| 复制机(气体) | `tile.ReplicatorGases.name` | `REPLICATOR_GASES_RECIPE` | 气体模板 + UU 气体 → 气体 |
| 复制机(流体) | `tile.ReplicatorFluidStack.name` | `REPLICATOR_FLUIDSTACK_RECIPE` | 流体模板 + UU 气体 → 流体 |

- 模板使用独立 `CONFIGURATION` 缓存，不消耗模板。
- UU 使用正常 `INPUT` 缓存；模板与 UU 为同类型时也不会共享数量。
- 使用原配方 `ticks` 和每 tick 附加 `extraEnergy`，在途保存和恢复保持能耗。
- 清空或卸载节点时，模板与其他资源一起走既有回收机制。

加上本节三个身份，MoreMachine 共 21 个机器族、75 个执行身份。其中 16 个身份依赖 Generators。

## 7. Mek MultiblockMachine 可选机器

需要模块 `mekanismmultiblockmachine`，两个发电机还检查 `mekanismgenerators`。名称直接来自该模块中文 lang。

| 机器名称 | lang key | 配方表 / 合同 | 范围 |
| --- | --- | --- | --- |
| 大型化学灌注器 | `tile.LargeChemicalInfuser.name` | `CHEMICAL_INFUSER` | 独立双气体输入及气体输出，配置并行，固定储罐 |
| 大型化学清洗机 | `tile.LargeChemicalWasher.name` | `CHEMICAL_WASHER` | 气体 + 流体 → 气体，配置并行 |
| 大型电解分离器 | `tile.LargeElectrolyticSeparator.name` | `ELECTROLYTIC_SEPARATOR` | 流体 → 左右气体；保留原配方独立 `energyUsage` |
| 大型太阳能中子活化器 | `tile.LargeSolarNeutronActivator.name` | `SOLAR_NEUTRON_ACTIVATOR` | 无能耗，受模拟光照门控制的气体转换 |
| 大型风力发电机 | `tile.LargeWindGenerator.name` | 大型风机冻结合同 | 独立风力配置、模拟主机高度 + 46 格，不限制维度 |
| 大型燃气发电机 | `tile.LargeGasGenerator.name` | `GAS_FUEL_TO_ENERGY_RECIPE` | 配置并行和储能，复用现有气体燃料合同 |

共 6 个身份。原生配置决定基础并行，随后由 Runtime 应用合并机器数、升级和 workRate。固定储罐容量不乘配置并行数；原生独立线程升级暂不增加。

这里只虚拟化这六台大型机器，不模拟真实占位方块、风机距离干扰、叶片伤害、爆炸和天空遮挡，不表示其他反应堆等多方块系统都已支持。

详细参数与边界见 [MultiblockMachine 兼容说明](CLOUDWORKS_MULTIBLOCK_MACHINE_COMPATIBILITY_20261010.zh-CN.md)。

## 8. 注册数量汇总

这是源码声明数量，不是某个实际服务器的已加载或可执行配方条数。

| 范围 | 执行身份数 |
| --- | ---: |
| Mek 非工厂基础机器 | 36 |
| Mek 工厂等级/模式 | 76 |
| Mek Generators 可选机器 | 5 |
| MoreMachine 等级及复制机 | 75 |
| Mek MultiblockMachine 大型机器 | 6 |
| 所有可选依赖可用时的默认声明合计 | 198 |

未安装 Generators 时应扣除其 5 个本体默认身份、MoreMachine 16 个发电身份和 MultiblockMachine 2 个发电身份。未安装 MoreMachine 时其 75 个身份全部不存在；未安装 MultiblockMachine 时其 6 个身份全部不存在。CRT 注册的身份和别名不计入固定默认合计。

可选注册失败、原生配方表为空、配方无法冻结等情况下，声明数量不代表实际可用数量。不要以文档总数代替服务器实际注册结果。

## 9. 已知边界与尚未接入

### 9.1 机器范围

- Mek 本体工厂实际存在的全部模式均已注册四级；种植工厂使用 MoreMachine 的基础/高级/精英/终极机器，不伪造不存在的 Mek `FARM` 模式。
- MoreMachine 基础/高级/精英/终极电动泵未接入；涉及真实世界探测与抽取。
- MoreMachine 无线充电站和无线供能站未接入；操作真实玩家、实体或方块。
- MoreMachine 特殊线程升级按项目约定暂不实现。
- 本体多方块现提供 CRT 可绑定的资源／能量／动态热力合同，不表示真实结构、辐射或爆炸模拟，见第 11 节。
- QIO/AE 网络本身不是产线机器节点；QIO 仅通过端口插件接入，AE 集成留给独立附属。

### 9.2 配方和参数范围

- 普通原生适配器每实例最多冻结 10,000 条候选配方，超限会拒绝冻结。
- 具有自定义物品匹配器或特殊树苗匹配语义等无法安全冻结的条目可能被跳过；不能宣称所有原生条目无条件可用。
- 物品 wildcard 匹配并未整体禁用，冻结匹配保留 wildcard damage 语义。
- 普通 Mek 及 MoreMachine 电解仍使用原有基础能耗；MultiblockMachine 大型电解已单独启用原配方 `energyUsage` 冻结，不重复收取基础能耗。
- 复制机和反质子核合成器/工厂明确保存并应用 `extraEnergy`；加压适配器尚未处理原配方额外能耗字段，不能把“读取了周期”理解为“所有能耗字段都已读取”。
- 本体和 MoreMachine 回旋式气液转换机均使用四端口双向模型；一个双向原生条目展开为两个独立配方，不要求联合输入。它与真实机器一次只选一种 mode 的限制不同，云端不绑定上传时的方向。
- 热力蒸馏控制器保留默认物品并接入动态温度；不检查真实塔高和形状。聚变／裂变等额外类型由 CRT 绑定，不再将仅冷却控制器当作默认聚变机。
- 部分 Mek 内置机器仍使用注册函数中的固定能耗、容量或周期，不是所有参数都跟随原机配置。
- MoreMachine 的环境气体收集器(电动型)当前读取普通 `AMBIENT_ACCUMULATOR` 表；如果整合包单独修改电动型配方表，不能认为这里自动使用该独立表。
- 环境发电和太阳能中子活化器使用云工坊模拟环境，昼夜时间每服务器 tick 跟随主世界；不复刻真实世界的天气、遮挡或每个区块的位置条件。
- 实际配方条目以目标服务器加载后的原生表和冻结结果为准，不在文档中固定其数量。

## 10. 维护本清单

新增机器或配方时应同步：

1. 在相应注册入口中核对物品、等级/模式及 adapter。
2. 从目标 Mod 的 lang 取得准确名称和 key，不根据类名自行翻译。
3. 注明原生配方表、资源类型、特殊合同、可选依赖和未覆盖规则。
4. 更新执行身份数量；不要把模板、资源类型、配方条目和机器身份混为一谈。
5. 核对异步准入及冻结成功条件。

主要源码：

- `src/main/java/mekanism/cloudworks/common/registry/CloudworksMekanismBuiltins.java`。
- `src/main/java/mekanism/cloudworks/common/integration/moremachine/CloudworksMoreMachineIntegration.java`。
- `src/main/java/mekanism/cloudworks/common/integration/multiblockmachine/CloudworksMultiblockMachineIntegration.java`。
- `src/main/java/mekanism/cloudworks/common/recipe/CloudworksAsyncRecipeAdmission.java`。
- `src/main/java/mekanism/cloudworks/common/recipe/CloudworksNativeRecipeAdapter.java`。
- `src/main/java/mekanism/cloudworks/common/recipe/CloudworksRecipeBatch.java`。

接入操作和具体例子见 [开发指南](CLOUDWORKS_DEVELOPER_GUIDE.zh-CN.md)。

## 11. 本体多方块 CRT 类型

以下类型不属于 `mekanismmultiblockmachine`。除原有热力蒸馏控制器外，不提供默认 ItemStack；整合包作者指定物品注册后才成为可上传的机器身份，名称／图标取指定物品本身。

| 原生结构名称 | 名称来源 | 适配器 | 虚拟合同 |
| --- | --- | --- | --- |
| 反应控制器（聚变反应堆） | `tile.Reactor.ReactorController.name` | `CloudworksFusionReactorRecipeAdapter` | D-T 或氘+氚燃料发电；主动冷却时使用原生 `FUSION_COOLING` 产物 |
| 工业涡轮 | `gui.industrialTurbine` | `CloudworksTurbineRecipeAdapter` | 消耗蒸汽发电，按冷凝器容量输出水 |
| 热力蒸馏控制器 | `tile.BasicBlock.ThermalEvaporationController.name` | `CloudworksThermalEvaporationRecipeAdapter` | 原生 `THERMAL_EVAPORATION_PLANT` 流体转换；默认绑定保留，可另注册物品 |
| 热力锅炉 | `gui.thermoelectricBoiler` | `CloudworksBoilerRecipeAdapter` | 水+过热钠 → 蒸汽+钠，按本体焓和效率计算 |
| 裂变反应堆 | `gui.fissionReactor` | `CloudworksFissionReactorRecipeAdapter` | 燃料+水／钠 → 核废料+蒸汽／过热钠，不直接产生电能 |
| 超临界移相器外壳[WIP] | `tile.BasicBlock3.SpsCasing.name` | `CloudworksSPSRecipeAdapter` | 1000 钋 + 基础 1,000,000,000 J → 1 反物质 |

聚变还显式实现 `CloudworksEnergyGenerator`，但冻结常量计划为 0；实际产热、发电和冷却由动态热力系统结算，不能重复计入。蒸汽／冷却剂由当前可用热量限制，不模拟真实结构比例或世界副作用。热力系统详细实现见 [Runtime 热力说明](CLOUDWORKS_THERMAL_RUNTIME_20261011.zh-CN.md)。

CRT 同时提供普通机器、精确 NBT 别名、独立执行 profile、云端专用配方、模板／概率输出、固定及燃料发电。别名是全服有效的完整执行合同继承，不是个人解锁；无 NBT 别名会赋予所有同身份物品机器能力。

接口和可编写的脚本示例见 [CRT 开发指南](CLOUDWORKS_CRAFTTWEAKER_GUIDE.zh-CN.md)。`Cards` 接口暂不实现。

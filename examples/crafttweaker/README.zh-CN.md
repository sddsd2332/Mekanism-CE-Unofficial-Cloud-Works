# 云工坊 CraftTweaker 示例

内置泵/等级泵按环境配方工作：下界熔岩、海洋水/FILTER重水。`Pumps.add/biome/remove/removeAll` 可定义其他模组群系流体；中英文 `cloudworks_pumps*.zs` 是独立示例，完整接口见 [泵规则](../../docs/CLOUDWORKS_PUMP_ENVIRONMENT_RULES.zh-CN.md)。控制器注入率在节点详情手动设置，随蓝图保存。CRT `Multiblocks.fusion` 仍使用注册参数中的注入率。

产线使用自有模块，有效节点模块按轴接管。完整示例包含 `Machines.setLineUpgradePolicy(machine, speedUnits, energyUnits)`，零表示拒绝该轴。Mek 速度／能量物品属于节点 CUSTOM，不能再把同一物品重绑为产线池物品。

所有普通机器节点共用按有效原料缩减批量的并行机制，并可分别运行不同配方。完整示例演示 `Slots.recipeFilteredInput(port, true/false)`：普通输入口默认过滤无效配方输入，模块专用耗材或通用缓存可显式关闭。过滤不会清除已有缓存。规则及 Java API 见 [通用输入过滤与并行](../../docs/CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md)。

English instructions and script: [README.md](README.md).

2026-10-25 扩展包含泵/重水、聚变控制器、SPS 按供能加工、生物质发电、手动矿辞规则及五类 Mek/IC2 节点升级。`Recipes` 新增 `operationsPerCycle`、`powerDriven`、`secondaryFuelInputs`；完整合同和示例见 [扩展说明](../../docs/CLOUDWORKS_MEK_IC2_EXPANSION_20261025.zh-CN.md)。节点词典规则通过游戏内编辑器配置，随蓝图保存。

`cloudworks_complete.zh-CN.zs` 是本项目完整的 CRT API 示例，包含机器注册、资源与端口、配方、发电、多方块、热力、概率战利品注入及注册表查询接口，并列出 Java 自动注册的 Mekanism、MoreMachine 和 MultiblockMachine 机器族。脚本不会由模组或 Gradle 自动加载；复制到实例 `scripts` 目录前，必须将带标签的 `minecraft:stick` 示例身份替换为整合包专用物品。

Mekanism、MoreMachine 和 MultiblockMachine 的机器由 Java 兼容层自动注册，配方从各模组原生配方表冻结读取，因此 CRT 中不要重复注册这些身份。`Machines.dump()` 会将最终机器、参数和适配器信息写入 CraftTweaker 日志。

聚变、涡轮和裂变示例依赖 `mekanismgenerators`；未安装该模块时应保持注释。所有注册操作都在启动阶段应用；修改脚本后重启游戏和服务器。ZenUtils 重载不会重新构建已冻结的云工坊注册表。

`mods.cloudworks.LootDrops` 会在冻结的实体／结构战利品结果上独立追加概率掉落：实体规则按匹配的模拟击杀掷骰；轴规则按该轴的模拟击杀及结构箱表事件掷骰。支持物品 metadata，不接受带 NBT 的物品栈。接口不改 loot JSON，也不负责真实世界战利品收集。

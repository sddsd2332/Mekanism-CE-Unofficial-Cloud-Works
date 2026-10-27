# 云工坊 CraftTweaker 开发指南

日期：2026-10-24。对应当前源码；接口仍处于开发阶段。

## 1. 范围与生命周期

提供以下 ZenScript 类型：

| 类型 | 用途 |
| --- | --- |
| `mods.cloudworks.Machines` | 机器注册、精确 NBT 别名、派生机器、参数和查询 |
| `mods.cloudworks.Slots` | 资源输入、输出、模板端口 |
| `mods.cloudworks.Pumps` | 按环境轴/完整群系名注册泵流体配方，支持等级/FILTER条件及优先级 |
| `mods.cloudworks.Resources` | 物品、流体、气体、灌注及已有 QIO codec 资源 |
| `mods.cloudworks.Recipes` | 云端专用资源配方 |
| `mods.cloudworks.Generators` | 固定发电与多输入燃料发电 |
| `mods.cloudworks.Multiblocks` | 六类本体多方块的虚拟执行合同 |
| `mods.cloudworks.Thermal` | 热容、导热、保温、蒸馏太阳能部件及加热器注册 |
| `mods.cloudworks.Upgrades` | 指定速度／能量升级池的上传、下载物品 |
| `mods.cloudworks.Storage` | 服务器运行期将机器／升级奖励入库，不扣物理背包 |
| `mods.cloudworks.StorageResult` | 非阻塞查询入库是否完成、是否接收、数量与错误 |
| `mods.cloudworks.LootDrops` | 冻结战利品结果上的实体／环境轴概率掉落补充 |

不提供 `Cards`，不注册新的卡等级，不通过脚本创建真实方块或物品。

注册修改通过 CT `IAction` 收集；机器身份、配方、profile、别名、派生关系、发电、热力和概率掉落修改都进入 `CloudworksAPI` 的声明通道，由 `Cloudworks.loadComplete` 统一物化后冻结。运行期 `Storage` 不走启动声明队列，而是调用公共异步入库 API。CrT 门面不直接访问 CloudWorks 内部 registry、adapter registry 或玩家数据管理器。声明机器应先于声明它的配方或派生机器；别名最后解析，因此可以引用同批脚本创建的机器。

`terminalItemUploadsEnabled` 配置默认开启，关闭后只限制终端物品上传；任务脚本仍可使用 `Storage` 发放机器、原生升级或自定义升级。完整签名、非阻塞结果、团队云与奖励消费边界见 [脚本奖励入库](CLOUDWORKS_SCRIPT_DEPOSITS.zh-CN.md)。

普通脚本不要添加 `#reloadable`。动作不标记 ZenUtils `@Reloadable`，其动作重放会被 ZenUtils 忽略；通过其他入口新增或改变冻结注册会被拒绝。修改脚本后必须重启游戏／服务器，退出存档再进入不能重新注册。

无 CT 时核心注册和 Runtime 不引用 CT 类型。服务器和客户端应部署相同脚本及 Mod。

`Machines` 查询读取公开 API 的不可变机器视图，并包含当前脚本阶段已声明的机器和修改。要检查最终注册结果，在脚本末尾调用 `Machines.dump()`；它会在延迟阶段将结果写入 CT 日志。

## 2. 机器身份与别名

产线升级已改为逐轴节点优先。机器注册后可调用 `Machines.setLineUpgradePolicy(machine, speedUnits, energyUnits)` 设置每台机器的 A 域额度；零拒绝该轴，负数报错。默认产线物品是云工坊自有模块，Mek 真实升级物品用于节点 CUSTOM。完整语义见 [产线升级](CLOUDWORKS_LINE_UPGRADES.zh-CN.md)。

```zenscript
import mods.cloudworks.Machines;

// targetMachine 是已注册机器物品，可从 JEI /ct hand 取得准确物品及 NBT。
// Machines.registerAlias(<minecraft:stick>, targetMachine);
```

`registerAlias(alias, target)` 完整继承目标 profile、布局、全部普通适配器、特殊燃料适配器和发电绑定；别名保留自己的图标、名称和节点身份，不与原机器合并。

新脚本机器及别名按 **物品 + metadata + 完整 tagCompound** 匹配；数量不属于身份。不带 NBT 与带 NBT 是不同身份；`MachineIdentityPolicy.none()` 不再用于脚本身份。

```zenscript
val reward = <minecraft:stick>.withTag({cloudMachine: "reward_crusher"});
// Machines.registerAlias(reward, targetMachine);
```

普通木棍不会匹配 `reward`，但添加额外 NBT 的木棍也不匹配它。使用稳定的身份标签；不要把不断变化的能量、库存或进度写进奖励物品的身份 NBT。

禁止自己别名自己、别名链、覆盖已有机器、无执行合同的目标。同一个物品／metadata 不能混用不同身份策略。冻结后，核心 `registerAlias` 对完全相同的绑定是无副作用 no-op，其他调用拒绝。

**全局性警告：别名不是某个玩家的权限。注册无 NBT 木棍为机器后，全服所有相同身份木棍都具有该执行能力。**

删除脚本后重启会移除声明。存档中的物品和资源不自动删除，但未注册节点不再执行。

### 查询 API

```text
Machines.isRegistered(item) -> bool
Machines.getAliasTarget(item) -> IItemStack 或 null
Machines.getPorts(item) -> string[]
Machines.getProfile(item) -> string
Machines.getParallel(item) -> int
Machines.getEnergyPerTick(item) -> double
Machines.getEnergyCapacity(item) -> double
Machines.getTicks(item) -> int
Machines.dump()
```

未注册时参数查询返回 0，文字和端口查询返回空值。

### 升级物品

```zenscript
import mods.cloudworks.Upgrades;

Upgrades.setItem("speed", <minecraft:paper>.withTag({cloudUpgrade: "speed"}));
Upgrades.setItem("energy", <minecraft:paper>.withTag({cloudUpgrade: "energy"}));
// Upgrades.useNativeItem("speed");
// Upgrades.getItem("energy");
```

类型仅接受 `speed`、`energy`。默认使用 Mek 原生升级物品；配置替换后，
该类型只接受指定的物品、元数据和完整 NBT，并且下载、GUI 图标使用同一模板。
数量不参与身份，两个类型不能绑定成同一身份。

升级池保留类型计数，不保存不同物品变体。重启更换注册物品后，已有池和产线升级数量不变，
取出时使用新的注册物品；终端残留的旧物品仍能取回，但不能按旧定义继续上传。
这不是额外创建两种自有升级模块，不改变机器策略、额度、Mek／IC2 配置公式或端口升级体系。
注册定义随 Mek 模块配置包同步到客户端，断开连接清除同步视图。

## 3. 普通脚本机器与端口

普通 `output()`、`chanceOutput()` 使用玩家云端产出倍率。
容器／催化剂返还使用 `unscaledOutput(resource)` 或 `unscaledOutput(resource, port)`；
守恒转换使用 `unscaledOutputs()` 排除整份配方。详见 [产出倍率](OUTPUT_MULTIPLIER.zh-CN.md)。

```zenscript
import mods.cloudworks.Machines;
import mods.cloudworks.Slots;
import mods.cloudworks.Resources;
import mods.cloudworks.Recipes;

val machine = <minecraft:stick>.withTag({cloudMachine: "custom_processor"});
val slots = Slots.create()
    .input("item_input", "mekanism:item_stack", 64)
    .output("item_output", "mekanism:item_stack", 64);

Machines.register(machine, 1, 10.0, 10000.0, 20, slots);
Recipes.create("pack:iron_to_gold", 20)
    .input(Resources.item(<minecraft:iron_ingot>), "item_input")
    .output(Resources.item(<minecraft:gold_ingot>), "item_output")
    .register(machine);
```

注册参数：

```text
Machines.register(item, parallel, energyPerTick, energyCapacity, defaultTicks, slots)
Machines.setProfile(item, parallel, energyPerTick, energyCapacity, defaultTicks)
Machines.registerFrom(item, target, parallel, energyPerTick, energyCapacity, defaultTicks)
```

`registerFrom` 复用目标布局和执行适配器，但 profile 独立，不是同参数别名。原生配方若指定了固定周期，固定周期优先于 profile 的默认周期。派生目标必须先有注册身份，不能是别名。

`parallel` 是单台机器并行，不是 Java 线程数；不要预乘节点合并数量或 workRate。耗能单位 J/t，容量单位 J。非有限值、负能量、非正周期或并行会被拒绝。

对所有普通加工机器，声明的并行是上限，不是同配方最低开工数量。原料只能支持一份时可以开一份；多输入配方按最少可支持次数收缩。不同配方和后到原料共用空闲容量、分别计时、共同检查输出空间。基础加工能耗按实际占用容量收费。热力、直接燃料发电与衰变继续使用专用合同，不能把它们描述为普通配方线程。

端口：

```text
Slots.create()
slots.input(id, codec, capacity)
slots.output(id, codec, capacity)
slots.template(id, codec, capacity)
slots.add(id, codec, role, capacity, multipleConnections)
slots.recipeFilteredInput(id, enabled)
```

`role` 为 `INPUT`、`OUTPUT`、`CONFIGURATION`。普通方法允许多条连接；使用 `add` 指定是否允许多连接。端口数量／容量按单台基础机器定义；布局提交后为冻结副本。

泵的环境流体规则使用 `mods.cloudworks.Pumps`，不需要重新注册 Mek/MoreMachine 机器。例如 `Pumps.biome("pack:river_water", "minecraft:river", <liquid:water> * 1000, 20, 100)` 为河流覆盖添加产水规则。`add/biome` 有等级倍率、FILTER条件和优先级重载；`remove/removeAll` 可替换默认规则。完整参数、冲突顺序及可直接使用的中英文脚本见 [泵环境规则](CLOUDWORKS_PUMP_ENVIRONMENT_RULES.zh-CN.md)。

普通输入口默认 `recipeFilteredInput = true`，新资源须匹配该口的已冻结配方输入；没有显式端口的配方也参与过滤。同 codec 的主料和显式辅料口按配方端口分开。`false` 关闭配方过滤，但保留 codec、方向和容量校验；适用于通用缓存或未列入配方的模块成本口。此开关不修改真实机器，也不清理节点已有无效输入。`template` 按已声明模板规则匹配；热力燃料使用冻结原生燃料规则。Java 对应入口和完整行为见 [通用输入过滤与并行](CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md)。

内置 codec：`mekanism:item_stack`、`mekanism:fluid_stack`、`mekanism:gas_stack`、`cloudworks:infuse`。灌注资源通过公开 `CloudworksInfuseResourceCodec` API 构造；第三方新增资源 codec 仍必须用 Java 注册，CRT 不提供创建新 codec 的回调入口。

## 4. 资源与配方

```text
Resources.item(IItemStack)                         // 数量来自物品栈
Resources.fluid(ILiquidStack)                     // mB
Resources.gas(IGasStack)                          // 复用 Mek 的 <gas:...>
Resources.infuse(typeName, amount)                // 已注册的灌注类型
Resources.ingredient(IIngredient, amount)         // 启动时展开有限候选
Resources.descriptor(IData, amount)               // QIOResourceDescriptor.write() 的完整结构
resource.withAmount(long)
resource.amount
resource.codec
```

`ingredient` 在启动时展开候选并评估匹配；wildcard 子类型通过原物品的子物品列表展开。展开为空会拒绝；不保存脚本条件或变换回调到 worker。输出和模板必须是一个确定资源，不能使用多候选输入集合。

配方构造：

```text
Recipes.create(id, ticks)
    .input(resource[, port])
    .output(resource[, port])
    .chanceOutput(resource, probability)
    .template(resource, configurationPort)
    .energy(additionalEnergyPerTick)
    .sunlight()
    .register(machine)
Recipes.remove(machine, scriptRecipeId)
Recipes.removeAll(machine)
```

资源输入消耗，模板不消耗；概率输出按每个操作独立抽取，沿用 Runtime 的在途承诺与持久化。附加耗能叠加在基础 profile 上，并参与正常升级／workRate 计算。提交后的 builder 不可修改。

`remove` 按 ID 删除本批声明的云端脚本配方；不会按 ID 删除 Mek 原生表。`removeAll` 移除该机器的云工坊普通适配器和待注册脚本配方，**不修改真实 Mek 配方表，也不清除特殊／环境发电绑定**。需要重做普通机器配方时，先 `removeAll`，再注册新配方。不能修改别名配方，应修改其目标或使用独立派生机器。

物品/气体/流体等多个输入可以共用同一 codec，但需要区分连接时应明确指定端口。注册校验会拒绝不存在的端口和无法容纳单次输出的布局。

## 5. 自定义发电

```zenscript
import mods.cloudworks.Generators;

val generator = <minecraft:stick>.withTag({cloudMachine: "fuel_generator"});
Machines.register(generator, 1, 0.0, 100000.0, 1,
    Slots.create()
        .input("item_input", "mekanism:item_stack", 64)
        .output("item_output", "mekanism:item_stack", 64));

Generators.fuel(generator, "pack:fuel",
    [Resources.item(<minecraft:coal>), Resources.item(<minecraft:iron_ingot>)],
    [Resources.item(<minecraft:gold_nugget>)],
    5000.0);
```

```text
Generators.constant(machine, joulesPerTick)
Generators.fuel(machine, id, inputs[], outputs[], joulesPerOperation)
```

固定发电没有资源成本，适合整合包有意设置的能源来源。燃料合同每 tick 消耗最多 `机器数量 × 原生并行 × workRate` 次；同机器的不同燃料配方共享额度，不分别获得整份额度。

所有输入先联合模拟、所有产物先检查空间，然后一起扣减并发电。输出堵塞或辅助资源不足不会扣燃料。空输出使用 `[]`。燃料合同不能混入普通加工配方；为同一物品准备两种机器时请用不同稳定 NBT 身份。

## 6. 本体多方块类型

这些不是 `mekanismmultiblockmachine` 模块的机器。新多方块类型**没有默认机器 ItemStack，也不新增物品**；服务器或整合包自行选择代表整个结构的奖励物品并用 CRT 注册。热力蒸馏控制器是例外：按用户要求保留原有默认绑定，仍可通过额外的 CRT 物品创建独立蒸馏节点。

示例物品都带不同 NBT，互不混节点：

```zenscript
import mods.cloudworks.Multiblocks;

Multiblocks.evaporation(
    <minecraft:stick>.withTag({cloudMachine: "evaporation"}),
    1, 20, 256000, 10000);

Multiblocks.sps(
    <minecraft:stick>.withTag({cloudMachine: "sps"}),
    1000, 1000000000.0);

Multiblocks.boiler(
    <minecraft:stick>.withTag({cloudMachine: "boiler"}),
    100, 1, 1000000, 1000000);

// 下列三类需要安装 mekanismgenerators。
Multiblocks.fission(
    <minecraft:stick>.withTag({cloudMachine: "fission_water"}),
    false, 10, 8000, 1000000);

Multiblocks.turbine(
    <minecraft:stick>.withTag({cloudMachine: "turbine"}),
    1000, 28, 7, 1, 64000, 64000, 16000000.0);

Multiblocks.fusion(
    <minecraft:stick>.withTag({cloudMachine: "fusion"}),
    2, false, 10000, 1000000, 1000000000.0);
```

### 参数及执行合同

| 方法 | 参数（item 后） | 合同 |
| --- | --- | --- |
| `evaporation` | parallel, ticks, inputCapacity, outputCapacity | 原生 `THERMAL_EVAPORATION_PLANT` 流体配方；脚本配置稳态吞吐 |
| `sps` | ticks, energyCapacity | 1000 钋 → 1 反物质；总基础成本 1,000,000,000 J，均分到 ticks |
| `boiler` | waterPerOperation, ticks, fluidCapacity, gasCapacity | 水 + 过热钠 → 蒸汽 + 钠；按本体焓及效率向上取整冷却剂成本 |
| `fission` | sodiumCooling, ticks, fuelCapacity, coolantCapacity | 1 裂变燃料 → 1 核废料，并消耗水／钠生成蒸汽／过热钠；周期决定燃料吞吐 |
| `turbine` | steamPerTick, blades, coils, condensers, steamCapacity, waterCapacity, energyCapacity | 蒸汽 → 电能 + 限量冷凝水；有效叶片受线圈配置约束 |
| `fusion` | injectionRate, activelyCooled, gasCapacity, fluidCapacity, energyCapacity | D-T 或等量氘+氚 → 稳态热电；主动冷却时再消耗冷却流体并输出冷却配方产物 |

`getTypes()` 返回 `fusion`、`turbine`、`evaporation`、`boiler`、`fission_water`、`fission_sodium`、`sps`。裂变两种冷却模式是独立固定合同。

本体常量／配置只在注册和冻结阶段读取。燃料热值、蒸汽焓、效率、叶片/线圈效率、冷凝速率、SPS 成本读取本体，不另设另一套默认物理常量。

### 有意不模拟的部分

聚变／裂变／锅炉／蒸馏已接入动态 Runtime 热力；表中的数量用于冻结资源类型和脚本规模，不再作为全部资源强制整批换算。热量、温度、余热、点火与导热会参与执行；详细规则见 [热力 Runtime](CLOUDWORKS_THERMAL_RUNTIME_20261011.zh-CN.md)。仍不是完整真实世界多方块模拟：

- 不创建真实 TileEntity，不检查形状、体积、塔高、阀门、激光点火或黑体。
- 不模拟损伤、辐射、实体伤害和爆炸；节点温度、余热、外部热连接和热损已经实现。
- 蒸馏默认没有太阳能部件；可通过 `Thermal.solarPanels` 添加 0–4 个，外部加热器可直接连热端口。
- 裂变按真实储热和冷却焓产出，冷却剂／核废料输出满时停止相应处理，不向真实世界排放。
- 锅炉支持过热钠与外部热连接供热，不能无热量免费把水变蒸汽。
- 涡轮按配置的稳态蒸汽流量执行，不模拟本体输入储罐比例引起的动态流速。
- 聚变分离等离子体／外壳热状态，点火需实际支付云端能量；冷却缺液或输出满时暂停冷却，已有燃烧不凭空消失。
- SPS 改为按实际供能累计加工，保存部分输入和小数进度；1000mB 钋固定消耗 10 亿 J 产出 1mB 反物质。内置与 CRT 共用合同，不受产出倍率放大，保留 ticks 参数兼容但不使用它限制供能速率。

这些差异是当前虚拟合同的明确边界，不应将其描述成真实多方块结构模拟。可以调整冻结周期、并行、容量和热容；固定热力机型不混入任意普通配方或固定发电合同，需要这类魔改时注册独立普通云端机器。

## 7. 概率掉落注入

`LootDrops` 在云端生物／结构战利品管线冻结结果上追加概率掉落，不修改原版或模组 loot JSON，也不会合并相同物品的独立概率。实体目标按匹配实体的每次模拟击杀掷骰；轴目标按该轴的每次模拟击杀及每张已声明结构箱表滚动分别掷骰。

```zenscript
import mods.cloudworks.LootDrops;

// 每次模拟击杀僵尸：5% 概率掉 1 个钻石。
LootDrops.entity("minecraft:zombie", <minecraft:diamond>, 0.05);

// 每次骷髅击杀：25% 概率掉 1–2 根骨头。
LootDrops.entityRange("minecraft:skeleton", <minecraft:bone>, 1, 2, 0.25);

// 指定 metadata 的煤每次掷骰掉 1–3 个。
LootDrops.entityMeta("minecraft:zombie", <minecraft:coal>, 1, 1, 3, 0.10);

// 下界要塞轴的每次击杀、每张箱表滚动分别有 1% 概率掉绿宝石。
LootDrops.axis("cloudworks:nether_fortress", <minecraft:emerald>, 0.01);
LootDrops.axisRange("cloudworks:nether_fortress", <minecraft:gold_ingot>, 1, 2, 0.05);
LootDrops.axisMeta("cloudworks:nether_fortress", <minecraft:coal>, 1, 1, 2, 0.05);
```

完整签名：

```text
LootDrops.entity(entityId, item, chance)
LootDrops.entityRange(entityId, item, minCount, maxCount, chance)
LootDrops.entityMeta(entityId, item, meta, minCount, maxCount, chance)
LootDrops.axis(axisId, item, chance)
LootDrops.axisRange(axisId, item, minCount, maxCount, chance)
LootDrops.axisMeta(axisId, item, meta, minCount, maxCount, chance)
```

`entityId` 可写完整注册名，也可省略 `minecraft:` 命名空间；`axisId` 必须使用完整的 `cloudworks:` 注册名。物品必须已注册、metadata 确定且不带 NBT。`chance` 必须在 `(0, 1]`，数量范围必须为正整数且最大值不小于最小值。实体、轴和物品会在 load-complete 排空时校验；无效目标会使该批声明失败，修正脚本后可重试。冻结后不能新增；改动脚本需重启。

天气／维度轴没有击杀或结构箱事件，轴级注入不会触发。没有 spawn 表的轴不会产生击杀事件，没有箱表的轴不会产生箱表事件。该接口的概率由模拟事件独立掷骰，不是 `Recipes.chanceOutput()`，也不表示真实世界会自动收集战利品。完整示例见 [`examples/crafttweaker/cloudworks_complete.zh-CN.zs`](../examples/crafttweaker/cloudworks_complete.zh-CN.zs)。

## 8. 验证与维护

Java 夹具覆盖：精确 NBT 别名、多个适配器与特殊／发电绑定、冻结守卫、无 CT 核心加载、注册顺序、端口与容量校验、多输入发电、产物堵塞、燃料总额度、概率掉落注入及 SPS／锅炉／裂变／涡轮／聚变冻结参数。

额外使用实际 CT `ZenModule` 编译器编译 builder、注册调用、六个 `LootDrops` 入口及中英文完整示例。该夹具不执行 `CraftTweakerMC` 的 Forge 物品转换；真正的脚本加载、物品转换和 ZenUtils 重载仍需游戏内验证。

游戏内还需检查：CT 脚本日志、JEI 给出的物品名和 NBT、上传／下载脚本机器、节点连接、产物和发电，以及 ZenUtils reload 的动作跳过。自动夹具不能代替这些真实游戏验证。

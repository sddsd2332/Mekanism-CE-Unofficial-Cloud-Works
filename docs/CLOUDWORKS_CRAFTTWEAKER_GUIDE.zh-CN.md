# 云工坊 CraftTweaker 开发指南

日期：2026-10-11。对应当前源码；接口仍处于开发阶段。

## 1. 范围与生命周期

提供以下 ZenScript 类型：

| 类型 | 用途 |
| --- | --- |
| `mods.cloudworks.Machines` | 机器注册、精确 NBT 别名、派生机器、参数和查询 |
| `mods.cloudworks.Slots` | 资源输入、输出、模板端口 |
| `mods.cloudworks.Resources` | 物品、流体、气体、灌注及已有 QIO codec 资源 |
| `mods.cloudworks.Recipes` | 云端专用资源配方 |
| `mods.cloudworks.Generators` | 固定发电与多输入燃料发电 |
| `mods.cloudworks.Multiblocks` | 六类本体多方块的虚拟执行合同 |
| `mods.cloudworks.Thermal` | 热容、导热、保温、蒸馏太阳能部件及加热器注册 |

不提供 `Cards`，不注册新的卡等级，不通过脚本创建真实方块或物品。

所有写入均通过 CT `IAction` 收集；可选模组机器注册完成后，在 `Cloudworks.loadComplete` 中统一应用，然后冻结。声明机器应先于声明它的配方或派生机器；别名最后解析，因此可以引用同批脚本创建的机器。

普通脚本不要添加 `#reloadable`。动作不标记 ZenUtils `@Reloadable`，其动作重放会被 ZenUtils 忽略；通过其他入口新增或改变冻结注册会被拒绝。修改脚本后必须重启游戏／服务器，退出存档再进入不能重新注册。

无 CT 时核心注册和 Runtime 不引用 CT 类型。服务器和客户端应部署相同脚本及 Mod。

`Machines` 的立即查询返回调用时已经应用的注册表，不包含尚在队列中的声明。要检查最终注册结果，在脚本末尾调用 `Machines.dump()`；它会在延迟阶段将结果写入 CT 日志。

## 2. 机器身份与别名

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

## 3. 普通脚本机器与端口

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

端口：

```text
Slots.create()
slots.input(id, codec, capacity)
slots.output(id, codec, capacity)
slots.template(id, codec, capacity)
slots.add(id, codec, role, capacity, multipleConnections)
```

`role` 为 `INPUT`、`OUTPUT`、`CONFIGURATION`。普通方法允许多条连接；使用 `add` 指定是否允许多连接。端口数量／容量按单台基础机器定义；布局提交后为冻结副本。

内置 codec：`mekanism:item_stack`、`mekanism:fluid_stack`、`mekanism:gas_stack`、`cloudworks:infuse`。第三方必须先用 Java 注册 QIO codec；CRT 不提供创建新 codec 的回调入口。

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
- 基础 SPS 总能耗守恒；产线能量升级仍沿用云工坊统一倍率规则。

这些差异是当前虚拟合同的明确边界，不应将其描述成真实多方块结构模拟。可以调整冻结周期、并行、容量和热容；固定热力机型不混入任意普通配方或固定发电合同，需要这类魔改时注册独立普通云端机器。

## 7. 验证与维护

Java 夹具覆盖：精确 NBT 别名、多个适配器与特殊／发电绑定、冻结守卫、无 CT 核心加载、注册顺序、端口与容量校验、多输入发电、产物堵塞、燃料总额度、SPS／锅炉／裂变／涡轮／聚变冻结参数。

额外使用实际 CT `ZenModule` 编译器编译 builder 和注册调用。该夹具不执行 `CraftTweakerMC` 的 Forge 物品转换；真正的脚本加载、物品转换和 ZenUtils 重载仍需游戏内验证。

游戏内还需检查：CT 脚本日志、JEI 给出的物品名和 NBT、上传／下载脚本机器、节点连接、产物和发电，以及 ZenUtils reload 的动作跳过。自动夹具不能代替这些真实游戏验证。

# 云工坊开发与其他 Mod 接入指南

更新日期：2026-10-25。本文以当前源码为准，不表示开发阶段 API 已经稳定。

本指南面向 Minecraft 1.12.2 / Forge / MekanismCEU 附属开发者。机器及配方接入清单另见 [已接入机器与配方](CLOUDWORKS_SUPPORTED_MACHINES_AND_RECIPES.zh-CN.md)。

## 1. 先确定接入边界

云工坊执行的是虚拟机器的资源、能量与进度合同，不是在另一个线程上运行真实 `TileEntity`。

2026-10-25 新增泵/重水、SPS 按供能加工、聚变控制器、生物质发电、手动词典规则，以及 FILTER/GAS/THREAD 和 IC2 超频/储能节点模块。合同、Java/CRT 新入口及状态保存规则见 [扩展说明](CLOUDWORKS_MEK_IC2_EXPANSION_20261025.zh-CN.md)。

泵现已改为环境配方：下界熔岩、海洋水/FILTER重水，`CloudworksAPI.registerPumpRule` 与 CRT `Pumps` 共用底层。模组群系按完整注册名区分；批次持久化其环境条件。详见 [泵环境规则与接口](CLOUDWORKS_PUMP_ENVIRONMENT_RULES.zh-CN.md)。

机器及辅助节点的 `dimensionId`/`dimension` 已彻底移除，上传和编辑接口不再接受玩家维度。
`CloudworksMachineContext`、`MachineContextSnapshot.MachineNode` 的构造器和访问器也不再包含它。
配方使用 `CloudworksRecipeSpec.Builder.environmentAxis(axisId)`；生命周期编辑器使用
`environmentAxis(axisId)`，传 `null` 可移除环境条件。原 `dimension(...)`/`requiredDimension()` 已删除。
`CloudworksRecipeBatch.specification` 改为两个参数 `(id, duration)`。
环境气体收集器全家族现按维度环境轴覆盖产气，辅助节点 AREA 绑定只判断画布几何。
完整语义、生命周期边界与示例见 [环境语义与接口](CLOUDWORKS_ENVIRONMENT_SEMANTICS.zh-CN.md)。

| 接入目标 | 当前入口 | 必须遵守的边界 |
| --- | --- | --- |
| 普通配方机器 | `CloudworksAPI.registerMachine` + `CloudworksMachineDefinition` | 把原模组配方转换成可冻结批次，不创建真实机器 |
| 等级、模式机器 | 上述入口 + `MachineIdentityPolicy` | 只选择影响执行的身份 NBT；每种身份独立注册 |
| 概率、随机资源机器 | `CloudworksRecipeBatch` 的概率或互斥输出合同 | 随机产物在操作开始时承诺；节点概率倍率在冻结期确定，保存和重启不能重新抽取 |
| 环境发电机 | `CloudworksMachineDefinition` + `CloudworksEnergyGenerator` | 冻结为数值 `CloudworksGenerationPlan`，不从 worker 查询世界 |
| 燃料发电机 | `CloudworksMachineDefinition` + 已准入的燃料配方合同 | 真实消耗燃料，按实际发电结算；仅注册接口不等于 Runtime 已支持 |
| 新资源类型 | Mek 的 QIO codec 注册 + 云工坊资源描述符 | 通用云仓可以保存；配方、渲染和物理端口仍需各自接入 |
| 外部存储系统 | 真实上传/下载端口或独立集成组件 | 只在服务器主线程访问 QIO、其他网络和能力接口 |
| 玩家机器物品上传源 | `ICloudworksUploadSource` | 这是机器物品预留/确认协议，不是通用资源上传 API |
| CRT 机器别名和云端专用配方 | `common/integration/crafttweaker` | 启动声明、冻结执行，不向 worker 提交脚本回调 |
| Mek 本体多方块的虚拟合同 | `CloudworksMultiblocks` | 不绑定默认物品；由 CRT 或外部附属指定整个结构的代表物品 |

云仓、产线运行、Runtime worker 不直接操作外部箱子、管道、玩家库存或第三方网络。QIO 插件仍通过端口本地缓存接入，不绕过原有云端转移。

普通配方产物支持玩家云端产出倍率；返还物和守恒转换必须由适配器显式排除，
不能根据物品名猜测。接口与冻结、随机恢复规则见 [产出倍率接入](OUTPUT_MULTIPLIER.zh-CN.md)。

当前内置 MoreMachine、Mek MultiblockMachine 可选机器兼容及 QIO 端口插件。AE 集成按项目约定留给独立附属，不在云工坊中新增 AE 实现。

## 2. 依赖与注册时机

### 2.1 构建依赖

外部附属使用匹配当前源码的 Cloudworks 开发 JAR，以及相同版本的 MekCEU 开发 JAR。现有工程的依赖版本以 `gradle/scripts/dependencies.gradle` 为准。

使用项目已有的 RFG 开发依赖写法；不要将 Cloudworks、Mek 或目标模组打进附属自己的 JAR：

```groovy
dependencies {
    // 名称按实际发布的开发 JAR 调整。
    implementation rfg.deobf(files("libs/MekanismCeU-CloudWorks-1.0.0-dev.jar"))
    implementation rfg.deobf(files("libs/Mekanism-CE-Unofficial-All-10.0.5.023-dev.jar"))
}
```

部署时服务器和客户端安装一致版本的附属。生产环境安装正常重混淆产物，不安装 `-dev.jar`。

### 2.2 必需与可选依赖

独立附属必须声明 `required-after:cloudworks;required-after:mekanism`。若直接依赖目标 Mod，可再加 `required-after:目标modid`。

若目标 Mod 是可选的，采用当前 MoreMachine 的模式：

1. 用 `Loader.isModLoaded` 检查目标模组。
2. 将目标模组的类型引用隔离在独立集成类中。
3. 在调用入口使用 `@Optional.Method(modid = "...")`。
4. 在 `@Mod.dependencies` 声明可选的 `after:目标modid`。
5. 使用目标公开 API，不使用反射；也不要在必加载类的静态字段、方法参数或父类中暴露可选模组类型。

现有参考：

- `common/integration/CloudworksHooks.java`。
- `common/integration/moremachine/CloudworksMoreMachineIntegration.java`。

### 2.3 注册必须早于冻结

当前生命周期是：

```text
preInit：注册资源 codec 等基础组件
init：注册 Mek 内置机器
所有 Mod 的 postInit：完成各自原生配方和外部附属声明（四条 API 队列收集）
Cloudworks.loadComplete（组合根 CloudworksBootstrap）：
    CloudworksHooks.register()
    扩展注册表排空（API 扩展队列）
    升级类型注册表排空（API 升级队列）
    CraftTweaker 脚本
    特殊节点原型排空（API 特殊节点队列）
    机器声明排空（同时关闭共享 API 冻结边界）
    配方准入冻结
    finally：卡片效果 / 升级物品 / 机器注册表 / 原生合同快照
```

外部附属应在自己的 `FMLPostInitializationEvent` 中通过 `mekanism.cloudworks.api.CloudworksAPI` 声明，且依赖顺序保证机器物品和配方表已经准备好。不要等到自己的 `FMLLoadCompleteEvent`，因为此时 Cloudworks 可能已经冻结。

项目内部 MoreMachine 集成由 Cloudworks 的 `loadComplete` 在冻结前显式调用，这是内部入口，不等于外部附属也可以任意在加载完成后注册。

冻结后 `CloudworksAPI` 的四个 `register*` 会拒绝新增声明；排空只允许 `mekanism.cloudworks.common.*` 的生命周期调用方触发（栈检查），排空失败按加载终止处理并指名出错声明。外部附属不要调用任何 `freeze*`/排空方法，也不要修改 Runtime 内部表。

## 3. 普通机器最小接入

一次完整接入需要四项声明：

1. 机器物品及身份选择。
2. `MachineExecutionProfile`。
3. `MachineSlotLayout`。
4. 配方适配器；新增适配器类还需要异步准入声明。

### 3.1 执行参数

推荐使用四参数构造：

```java
new MachineExecutionProfile(
        nativeProcesses,       // 一台机器的配方并行数，不是 Java worker 数
        nativeEnergyPerTick,   // 一台机器每个执行单元的基础 J/t
        nativeEnergyCapacity,  // 一台机器计入产线的基础储能容量
        nativeTicks            // 默认配方周期，单位 tick
);
```

不要把一整个合并节点的机器数量预乘进参数。Runtime 会按机器数量、原生并行数、产线升级和 workRate 计算执行规模。

普通加工的 `机器数 × nativeProcesses × workRate` 是并行容量上限，不要求同一配方凑满才开工。Runtime 按可用原料、共享输出空间和可付功率缩小批量；不同配方、后到原料可占用空闲容量，各批次独立推进。单路节点仍只执行一路。基础功率按实际占用并行单位收费，配方附加功率按实际操作份数收费。快速周期折算后的操作份数可以大于占用通道数，兼容层不要再次放大。热力、直接燃料发电和衰变保留各自的执行合同。详见 [通用输入过滤与并行](CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md)。

原机若并行数只放大产量、不按同样比例放大耗能，不能机械地把并行数写入 `threads`。例如虚空矿物采掘机使用 `threads = 1`，等级产量单独放入互斥产物合同。

三参数构造的中间值不是储能：`(threads, energyPerTick, totalTicks)`，默认容量由能耗派生。不要误写成 `(threads, energy, capacity)`。

参数必须是有限非负能量、正容量端口和可表示的数量。使用原模组配置及基础机器参数，不在 worker 中重新读取配置。

### 3.2 端口布局

```java
MachineSlotLayout layout = new MachineSlotLayout()
        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.INPUT, 64)
        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.OUTPUT, 64);
```

- 端口 ID 必须非空、唯一，并与适配器中指定的输入/输出端口一致。
- `INPUT` 是消耗输入；`OUTPUT` 是产物缓存；`CONFIGURATION` 是不消耗的模板缓存。
- `capacity` 是该基础机器端口容量，不是整个云仓容量。
- `.add(..., false)` 禁止这个端口接受多条连接；省略时默认允许。
- `INPUT` 默认按已冻结配方过滤新输入。使用 `.recipeFilteredInput("item_input", false)` 可关闭过滤，只校验方向、codec 和容量；这与多连接开关相互独立。`CONFIGURATION` 使用已声明模板匹配规则，热力原生燃料使用冻结的原生合同。
- 未标端口的配方输入也参与过滤；同 codec 同时存在未显式占用口和显式辅料口时，未标端口主料只进入前者。普通严格输入口没有可用配方时不接收任意同 codec 资源。
- 只用于模块额外成本、且未出现在配方输入中的自定义耗材口，应显式关闭配方过滤。核心不会推断任意 `onPreWork` 回调可能消耗的资源。
- 过滤不删除既有缓存，不改变已有在途承诺；不能处理的矿物需要分流，不会自动丢弃。
- 双物品配方需要主输入及额外输入，例如 `item_input`、`extra_item_input`。
- 双气体配方和电解左右产物应独立声明端口，不合并成无法区分的出口。
- 注册表保存布局的冻结副本，注册后不能通过修改原对象调整已注册容量。

### 3.3 优先使用已有适配器

若原生配方对象就是 Mek 的对应配方类，可以直接复用已有适配器。

例如粉碎机可以通过以下公开 API 形状声明，供阅读参考，**不要在附属中再次执行同一内置注册**：

```java
ItemStack machine = new ItemStack(MekanismBlocks.MachineBlock, 1, 3);
MachineSlotLayout layout = new MachineSlotLayout()
        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.INPUT, 64)
        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.OUTPUT, 64);

CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
        machine,
        new MachineExecutionProfile(1, 50D, 6),
        layout,
        MachineIdentityPolicy.none(),
        Collections.singletonList(new CloudworksRecipeMode(
                "addon:crusher", 0, null, crusherAdapter))));
```

`crusherAdapter` 是附属实现的 `CloudworksMachineAdapter`，必须在生命周期冻结阶段转换出不可变配方合同。这里的 50 J/t 和 6 tick 是当前内置注册值，不是“读取了所有原机参数”的承诺。新接入应对照原机实际语义选值。

`CloudworksBasicItemRecipeAdapter.describe` 的核心是：

```java
return new CloudworksRecipeBatch()
        .item(recipe.getInput().ingredient)
        .output(recipe.getOutput().output);
```

基本物品、双物品、物品加气体、双气体、气液、灌注、概率产物等已有适配器见 `common/recipe`。这些具体内置类已经进入异步准入清单，直接复用无需再次声明准入。

### 3.4 自定义原模组配方

优先继承 `CloudworksNativeRecipeAdapter<R>`，只转换原生配方的输入、产物和特殊参数。下面 `AddonRecipe` 是示例附属自行提供的接口，不是 Cloudworks 自带类型：

```java
public interface AddonRecipe {
    ItemStack input();
    ItemStack output();
    int ticks();
    double extraEnergyPerTick();
}

public final class AddonRecipeAdapter
        extends CloudworksNativeRecipeAdapter<AddonRecipe> {
    public AddonRecipeAdapter(Map<?, ? extends AddonRecipe> recipes) {
        super(recipes);
    }

    @Override
    protected CloudworksRecipeBatch describe(AddonRecipe recipe) {
        return new CloudworksRecipeBatch()
                .item(recipe.input(), "item_input")
                .output(recipe.output(), "item_output")
                .extraEnergy(recipe.extraEnergyPerTick());
    }

    @Override
    protected int duration(AddonRecipe recipe, int fallback) {
        return recipe.ticks();
    }
}
```

`extraEnergy` 是每 tick 的附加能耗，不是单次总能量。需要把原配方的总能量按原机规则转换后再传入，不要混淆单位。

然后通过公开 API 声明具体机器：

```java
CloudworksAPI.registerMachine(new CloudworksMachineDefinition(
        addonMachine,
        new MachineExecutionProfile(1, baseEnergyPerTick, baseCapacity, baseTicks),
        layout,
        MachineIdentityPolicy.none(),
        Collections.singletonList(new CloudworksRecipeMode(
                "addon:processing", 0, null,
                new AddonRecipeAdapter(addonRecipeMap)))));
```

`addonMachine`、参数和 `addonRecipeMap` 由附属自己的物品、配置和原生配方表提供。

`CloudworksMachineAdapter.freeze()` 只在生命周期冻结阶段调用一次；返回的必须是不可变 `CloudworksRecipeSpec` 数据。Runtime worker 不会再次回调附属适配器，也不允许把原模组配方对象、World、TileEntity 或脚本回调带入 worker。

原配方表在加载后应保持稳定。若原模组运行期修改独立配方表，需要在服务器线程变更，并调用 Mek 的 `RecipeHandler.markRecipeCachesInvalid()` 使当前共享版本、排序缓存和冻结合同重新生成。不要只修改 `Map`，也不要仅增加 Cloudworks 注册版本而遗漏原生配方版本。

## 4. 身份 NBT：参考工厂与 MoreMachine

云工坊有两个不同层次：

- 机器缓存保存上传物品的完整 NBT 变体。
- 执行身份只使用注册名、metadata 和 `MachineIdentityPolicy` 选中的字段。

库存、当前能量、owner、安全、升级插槽及上传进度不能直接成为执行身份。把这些字段放进去会产生大量不能复用的身份，甚至使已注册机器无法匹配。

### 4.1 Mek 工厂

工厂用 `FactoryRecipeTypeCodec.write` 写入模式，并选择：

```java
MachineIdentityPolicy.keys(
        "recipeTypeName", "recipeType", "recipeTypeVersion");
```

等级由实际工厂物品的 block/metadata 区分。终极工厂的物品位置不同于基础/高级/精英工厂，不能把旧等级编号直接当作统一 metadata。

参考 `CloudworksMekanismBuiltins.factoryStack`、`factoryMachineType`、`registerFactoryMode`。

当前本体实际存在的 19 个 `RecipeType` 均已注册基础/高级/精英/终极四级，包括富集、粉碎和反质子模式。注册覆盖夹具会遍历本体枚举检查所有等级，避免只注册了某一基础模式。

种植工厂使用 MoreMachine 的四级 `TierOrganicFarm`，不是 Mek 的工厂模式。Mek 语言文件仍有农场工厂名称，但当前源码和开发 JAR 已没有 `FARM` 枚举，不能据语言文件判断机器仍存在。

### 4.2 MoreMachine 的 NBT 等级

现有等级化学灌注器的核心注册可以简化为：

```java
MachineIdentityPolicy tierIdentity = MachineIdentityPolicy.keys("tier");

for (MachineTier tier : MachineTier.values()) {
    ItemStack machine = new ItemStack(MEKCeuMoreMachineBlocks.TierChemicalInfuser);
    NBTTagCompound tag = new NBTTagCompound();
    tag.setInteger("tier", tier.ordinal());
    machine.setTagCompound(tag);
    int processes = tier.processes;
    int tankCapacity = 10_000 * processes;

    MachineSlotLayout layout = new MachineSlotLayout()
            .add("gas_input_left", QIOResourceCodecs.GAS_STACK_ID,
                    MachineSlotLayout.PortRole.INPUT, tankCapacity)
            .add("gas_input_right", QIOResourceCodecs.GAS_STACK_ID,
                    MachineSlotLayout.PortRole.INPUT, tankCapacity)
            .add("gas_output", QIOResourceCodecs.GAS_STACK_ID,
                    MachineSlotLayout.PortRole.OUTPUT, tankCapacity);

    CloudworksAPI.registerMachine(new CloudworksMachineDefinition(machine,
            new MachineExecutionProfile(processes,
                    MachineType.CHEMICAL_INFUSER.getUsage(),
                    MachineType.CHEMICAL_INFUSER.getStorage() * processes, 1),
            layout, tierIdentity, Collections.singletonList(new CloudworksRecipeMode(
                    "addon:processing", 0, null,
                    new CloudworksChemicalPairRecipeAdapter(
                            RecipeHandler.Recipe.CHEMICAL_INFUSER.get())))));
}
```

这个示例摘自已存在的内部兼容，同样不应被附属重复执行。`MachineType` 是 Mek 的 `BlockStateMachine.MachineType`，`MachineTier` 和注册方块来自 MoreMachine。

等级并行数为 3 / 5 / 7 / 9。缺少 `tier` 的基础物品通过默认值归一化为基础身份。

### 4.3 MoreMachine 的等级和被忽略的世界模式

- 回旋式气液转换机只选择 `tier`，默认值为 0；与其他 NBT 等级机器共用 `TIER` 身份规则。
- 原物品可能携带 `mekData.mode`，但云端故意忽略它。四个端口同时暴露气体/流体输入输出，不要求玩家把机器放回世界切换模式再上传。
- 本体与 MoreMachine 共用 `CloudworksRotaryRecipeAdapter`：通过 `recipeVariants` 将原生双向配方展开为两个独立方向，每个方向只消耗自己的输入，不把气体和流体合成联合原料。
- `CloudworksNativeRecipeAdapter.recipeVariants` 默认保留原配方；仅需要展开多方向原生条目时覆盖它，展开后的候选同样受冻结数量上限约束。
- 可以选择嵌套字段，但不要选择整个 `mekData` 复合标签。
- 化学结晶器等 metadata 等级机器用原模组的 `MachineType.getValidMachines()` / `getStack()` 枚举，身份无需再声明 `tier`。
- 同一个注册名 + metadata 的所有身份必须使用相同的身份选择规则。

## 5. 特殊机器：沿用现有冻结合同

### 5.1 虚空矿物采掘机

现有实现位于 `CloudworksMoreMachineIntegration` 和 `CloudworksVoidMineralRecipeAdapter`：

```java
new CloudworksVoidMineralRecipeAdapter(
        VoidMineralGeneratorUitls.getCanOre(),
        tier.processes);
```

它直接复用原模组处理过的矿物候选列表：

- 复制候选物品，归一化数量，但保留重复项，避免改变原列表权重。
- 通过 `chooseItemOutput(candidates, amount)` 表达“每次只选一种产物”。
- 不能用对每种矿物独立调用 `chanceOutput` 来代替互斥抽取。
- 原机周期、能耗、容量来自 MoreMachine 配置；`threads = 1`，等级数量只影响所选产物数量。
- 不创建原生机器，也不扫描真实世界。

如果新机器能够表达为确定输入、概率产物、互斥产物、光照门或定期衰变，优先复用这些批次能力；不需要增加专用 Tick 引擎。

### 5.2 复制机模板

MoreMachine 三种复制机分别有 `CONFIGURATION` 模板端口。气体复制机的描述例如：

```java
return new CloudworksRecipeBatch()
        .template(recipe.getInput().input, "gas_template")
        .gas(recipe.getInput().uu, recipe.getInput().uu.amount, "gas_input")
        .output(recipe.getOutput().output)
        .extraEnergy(recipe.extraEnergy);
```

模板只判断类型存在，不参与耗材预留和扣减。即便模板与 UU 输入属于同一种气体，也必须分别保存，不能共用 `INPUT` 缓存。

模板保存、清空回收、移除节点、在途附加能耗与重启恢复由现有 Runtime 机制处理。适配器的 `duration` 返回原生 `recipe.ticks`。

### 5.3 放射性废料桶

`CloudworksWasteBarrelRecipeAdapter` 冻结放射性资格、黑名单、周期和衰变量，以 `.decay(amount)` 表达周期消耗。

- 不生成输出，不扣能量。
- 黑名单资源能保存，但衰变量为零。
- 不模拟世界辐射泄漏或邻接输出。
- MoreMachine 等级桶复用同一个适配器，仅改变等级倍率和缓存容量。

### 5.4 环境与燃料发电

环境发电机实现 `CloudworksEnergyGenerator.freezeGeneration`，返回已有计划：

```java
CloudworksGenerationPlan.constant(joulesPerTick);
CloudworksGenerationPlan.solar(peakJoulesPerTick);
CloudworksGenerationPlan.wind(joulesPerTick);
CloudworksGenerationPlan.heat(joulesPerTick);
```

冻结阶段可以读取原生配置与云工坊模拟环境；计划本身只能持有数值。主线程在每个服务器 tick END 采样主世界昼夜时间，Runtime 按每个已接收 tick 的不可变时钟快照推进太阳能和光照门，不让 worker 访问 `World` 的白天、天气或区块。正常走时和冻结时间的积压用连续段压缩；睡觉和命令跳时保留真实采样顺序，不伪造跳过时段的加工。

燃气发电机复用 `CloudworksGasGeneratorAdapter`；其机器定义同时声明燃料配方合同和发电计划。燃料、消耗量及能量来自冻结配方，不能将“无燃料时也产生恒定能量”作为近似实现。

现有特殊合同也必须走公开 API 的机器声明和冻结准入。不要依赖已经移除的
`registerAudited` 或内部特殊适配器注册入口；如果新特殊行为不能复用
`CloudworksMachineAdapter`，应先扩展公开合同、补齐生命周期冻结和异步夹具，再接入机器定义。

## 6. 线程与持久化规则

2026-10-26 第二轮 Runtime 优化复用捕获作用域解码、限制冷编译并发，对确定性等待规划使用库存版本失效，固定配方采用批量上限快路径，复用在途产出汇总和不可变版本页。保存复用冻结产线投影，严格恢复仍读磁盘验证。没有加入黑箱/跨 tick 快进；动态模块与概率条目保持原精确语义。实现边界及验证记录见 [Runtime 第二轮优化](CLOUDWORKS_RUNTIME_PHASE2_20261026.zh-CN.md)。

`runtime.durableCheckpoints` 默认 false，仅关闭首次/周期模拟落盘请求；内存 checkpoint、模块事件与 GUI 进度继续发布，显式 flush、空闲驱逐、停服及资源交易仍走原耐久路径。true 时由 `checkpointIntervalTicks` 错峰请求保存，不保证固定墙钟恢复窗口。配置在启动时冻结并同步，详见根目录配置说明。

模块 handle 的公开构造会冻结完整机器身份与升级 Map；`forContext` 等派生视图共享内部快照，`getMachineIdentity()` 每次仍返回独立副本。addon 不应依赖 getter 返回对象的引用恒等性。仅按 `MachineKey` 不能安全驻留完整身份栈，因为身份策略可能排除其他 NBT。

运行阶段的候选、pre/post work、开工、完工和取消回调按类缓存覆写判定；默认实现跳过，包含继承关系的自定义覆写仍执行。纯数值轴模块不再为默认回调创建生命周期事件。空的 `outputResources()` 不影响修改既有产物的数量。`PostWorkContext` 继续支持原 Map 构造，新增 `PostWorkCapacitySnapshot` 构造供 Runtime 复用已冻结的 codec 容量。容量查询保留同 codec 共享 room、未知资源为零的语义；输出声明与端口校验继续执行。

MultiblockMachine 的六台大型机器也沿用这些合同，完整注册例子位于 `common/integration/multiblockmachine/CloudworksMultiblockMachineIntegration.java`：

- 化学灌注/清洗复用现有原生适配器，以模块配置的 `processes` 作为基础并行。
- 大型太阳能中子活化器的基础耗能和储能都为零。
- 大型电解使用 `CloudworksSeparatorRecipeAdapter(recipes, true)` 读取原配方能耗，同时 profile 基础能耗为零，防止重复收费。
- 所有风机共用参数化的 `CloudworksWindGeneratorAdapter`，注册时捕获配置。本体与 MoreMachine 高度偏移为 0；大型风机使用独立风力配置、保留原生风头 46 格高度偏移，不应用维度黑名单，也不调用真实 TileEntity 取天空或区块信息。
- 大型燃气复用现有燃气合同；两个发电机只在 Generators 模块可用时登记。

这类大尺寸机器的固定缓存不能再次乘基础并行，否则会把原生固定容量错误放大。具体容量和未模拟的真实世界行为见 [MultiblockMachine 兼容说明](CLOUDWORKS_MULTIBLOCK_MACHINE_COMPATIBILITY_20261010.zh-CN.md)。

```text
服务器线程：原生注册表/配置/能力读取、配方与资源身份冻结、物理边界交互
    -> 不可变合同和快照
Runtime 编译 worker：转为资源 ID、数值配方、数组表
    -> Runtime 推进/结算
持久化服务：事务日志、checkpoint 和恢复
    -> 发布给客户端的只读状态
```

禁止在运行/编译 worker 中调用：

- `World`、真实 `TileEntity`、玩家背包或外部库存。
- 第三方存储网络、QIO 频率、AE 网格。
- 原生配方重新查询、物品能力解析、矿辞、燃烧时间查询。
- 运行时配置文件读写、直接序列化另一个真实世界库存。

`describe` 在冻结阶段读取原生配方。传入冻结后的匹配器不能捕获这些原生对象，也不能延迟到 worker 才回调它们。

不要在兼容层手工管理 operation 进度、再次扣原料、每 tick 写盘或单独保存一份虚拟机器库存。复用 Runtime 的预留、产物承诺、取消、模板缓存和 checkpoint。

概率产物只抽取一次；取消与重试不能复制或重新抽取已承诺产物。改变原配方不能破坏已在途操作的保存数据。

## 7. 新资源与外部存储

### 7.1 资源 codec

云工坊使用 Mek QIO 的 `QIOResourceDescriptor`，数量与类型身份分离。

- 物品：`QIOResourceCodecs.ITEM_STACK_ID`。
- 流体：`QIOResourceCodecs.FLUID_STACK_ID`。
- 气体：`QIOResourceCodecs.GAS_STACK_ID`。
- 灌注：`CloudworksInfuseResourceCodec.ID`。

新 codec 需要稳定 ID、版本、规范化模板、序列化与反序列化，并保证数量不混入类型身份。参考 `CloudworksInfuseResourceCodec`；客户端还需注册资源渲染器和选择器识别规则。

**当前不是任意资源类型的完整配方 SDK**：`CloudworksRecipeBatch` 的公开输入/输出构造方法主要覆盖物品、流体、气体和灌注；`CloudworksFrozenRecipeView` / `Entry` 的构造入口也未全部公开。单独注册 codec 不能自动获得自定义资源的配方执行能力。必要时先为批次与冻结接口增加正式扩展入口，并为 Runtime、路由、GUI 和持久化补验证，不建议占用 `mekanism.cloudworks` 包名绕过访问控制。

### 7.2 真实端口与 QIO

当前 QIO 端口集成参考：

- `TileEntityCloudworksPort`：安全、侧面配置、原生升级与频率组件。
- `CloudworksPortQIO`：模拟空间、实际提取/接收、只按实际数量调整缓存。
- `PacketCloudworksPortQIO`：过滤标记的容器与权限校验、服务端回包。
- `GuiCloudPortQIOFrequencyTab`：数据适配，本体 `GuiQIOFrequencyTab` 和频率窗口不重写。
- `GuiCloudPortQIOFilterWindow`：复用产线资源列表和 JEI 标记。
- `CloudworksPortBinding` / `CloudworksPortRecord`：UUID 索引的唯一资源库存。

现有物理端口仅存储物品、流体、气体和独立能量缓存，不会因为注册新 codec 自动出现另一种储罐。

若外部模组接入存储网络，应在主线程将网络资源转入/转出这些本地缓存，再沿用云端事务；不能直接从 worker 调网络 API。

`ICloudworksUploadSource` 用于机器物品的预留与持久确认。其旧注释中“尚未接线”的说明不能作为当前状态依据，应参考实际调用方 `CloudworksPlayerDataManager.uploadMachines` 和已有上传源实现。不要将此接口误用成普通产线资源的扣款协议。

## 8. 现有实现定位

路径均相对本仓库：

| 场景 | 参考文件 |
| --- | --- |
| 注册生命周期 | `src/main/java/mekanism/cloudworks/common/Cloudworks.java` |
| Mek 机器与工厂 | `src/main/java/mekanism/cloudworks/common/registry/CloudworksMekanismBuiltins.java` |
| 可选兼容入口 | `src/main/java/mekanism/cloudworks/common/integration/CloudworksHooks.java` |
| MoreMachine 等级、模式及参数 | `src/main/java/mekanism/cloudworks/common/integration/moremachine/CloudworksMoreMachineIntegration.java` |
| 本体大型机器、配置并行与独立发电合同 | `src/main/java/mekanism/cloudworks/common/integration/multiblockmachine/CloudworksMultiblockMachineIntegration.java` |
| 身份规则 | `src/main/java/mekanism/cloudworks/api/MachineIdentityPolicy.java` |
| 公共 API 队列与冻结 | `src/main/java/mekanism/cloudworks/api/CloudworksAPI.java` |
| 机器模块声明/注册 | `src/main/java/mekanism/cloudworks/api/MachineModuleType.java`、`common/upgrade/CloudworksMachineModuleRegistry.java` |
| 配方重写编辑器 | `src/main/java/mekanism/cloudworks/api/CloudworksRecipeEditor.java` |
| 模块生命周期与完成阶段输出策略 | `src/main/java/mekanism/cloudworks/api/ICustomMachineModule.java`、`api/PostWorkContext.java`、`api/MachineOutput.java` |
| 节点概率副产通道 | `src/main/java/mekanism/cloudworks/api/WorkContribution.java`、`common/upgrade/CloudworksUpgradeEffects.java` |
| 扩展 payload | `src/main/java/mekanism/cloudworks/api/CloudworksExtensionType.java`、`common/data/CloudworksExtensionSet.java` |
| 概率掉落注入 | `api/environment/CloudworksLootInjection.java`、`common/service/CloudworksLootInjections.java`、`common/integration/crafttweaker/CloudworksLootDrops.java` |
| 特殊节点注册表 | `src/main/java/mekanism/cloudworks/api/CloudworksSpecialNodeType.java`、`common/special/CloudworksSpecialNodeArchetypes.java` |
| 客户端面板 API | `src/main/java/mekanism/cloudworks/api/client/`（仅客户端加载） |
| 流闸门统一声明 | `src/main/java/mekanism/cloudworks/common/data/CloudworksFlowGate.java` |
| 原生配方基类 | `src/main/java/mekanism/cloudworks/common/recipe/CloudworksNativeRecipeAdapter.java` |
| 批次构造 | `src/main/java/mekanism/cloudworks/common/recipe/CloudworksRecipeBatch.java` |
| 异步准入 | `src/main/java/mekanism/cloudworks/common/recipe/CloudworksAsyncRecipeAdmission.java` |
| 主线程冻结 | `src/main/java/mekanism/cloudworks/common/service/CloudworksNativeContracts.java` |
| 数值编译 | `src/main/java/mekanism/cloudworks/common/runtime/CloudworksRuntimeCatalog.java` |
| 虚空矿物随机产出 | `src/main/java/mekanism/cloudworks/common/recipe/CloudworksVoidMineralRecipeAdapter.java` |
| 模板隔离 | `src/main/java/mekanism/cloudworks/common/recipe/CloudworksReplicatorGasRecipeAdapter.java` |
| 特殊行为验证 | `src/test/java/mekanism/cloudworks/common/runtime/CloudworksTemplateAndDecayTest.java` |
| 主线程冻结验证 | `src/test/java/mekanism/cloudworks/common/service/CloudworksNativeContractsTest.java` |
| MoreMachine 配方验证 | `src/test/java/mekanism/cloudworks/common/recipe/CloudworksMoreMachineRecipeTest.java` |
| Mek 全工厂模式与数控单机注册覆盖 | `src/test/java/mekanism/cloudworks/common/registry/CloudworksMekanismRegistrationCoverageTest.java` |
| MoreMachine 四级种植工厂注册与辅助预算 | `src/test/java/mekanism/cloudworks/common/integration/moremachine/CloudworksMoreMachineFarmRegistrationTest.java` |
| 反质子周期与附加能耗冻结 | `src/test/java/mekanism/cloudworks/common/recipe/CloudworksNucleosynthesizerRecipeTest.java` |
| 大型机器注册与原生配方冻结 | `src/test/java/mekanism/cloudworks/common/integration/multiblockmachine/CloudworksMultiblockMachineIntegrationTest.java` |
| 大型电解 Runtime 能量计费 | `src/test/java/mekanism/cloudworks/common/runtime/CloudworksLargeSeparatorRuntimeTest.java` |

注意：当前 Mek Generators 的旧可选注册入口仍有 `Class.forName` / 字段反射和异常跳过。新兼容应采用 MoreMachine 的隔离类与公开 API 模式，不照搬此处，也不要据此宣称整个项目已无反射。

## 9. 接入验证清单

1. 目标 Mod 缺失时正常加载；类引用不会提前触发 `NoClassDefFoundError`。
2. 注册在冻结前完成，物品不是空栈；没有重复注册同一批适配器。
3. 同一基础物品的身份策略一致，等级和模式区别正确；无默认 NBT 的物品能按预期匹配。
4. 原料、辅助资源、产物数量及端口符合原机；双输入、双输出和模板不会串仓。
5. 新适配器具体类已准入；冻结后 worker 不再次回调原模组 API。
6. 空输入、原料不足、输出满、零能量及停用节点不会继续消耗。
7. 原生并行、节点合并、升级、workRate 的倍率不会重复相乘。
8. 概率输出与互斥输出语义正确；固定种子、checkpoint 和重启不重抽。
9. 在途附加能耗、模板、删除节点、清空回收等生命周期保持一致。
10. 新资源或外部存储同步包校验 owner、当前容器、数据长度及资源合法性。
11. 配方缓存版本失效正确；独立配方表不能无通知地原地变化。
12. 有针对性夹具，并完成安装/卸载目标 Mod 的游戏启动及真实服务器联调。

```text
gradlew.bat test
gradlew.bat build
```

当前工程夹具会通过 Forge 原生测试接口统一初始化环境。测试成功不代表真实网络、区块生命周期或 GUI 已验证；不要为让纯 JVM 夹具通过而修改真实机器规则。

## 10. CraftTweaker 与本体多方块

完整接口、资源 builder、机器别名、独立 profile、云端配方、发电与六类多方块脚本示例见 [CRT 开发指南](CLOUDWORKS_CRAFTTWEAKER_GUIDE.zh-CN.md)。

终端上传开关，以及任务奖励／自定义合成回调中的机器、原生与自定义升级异步入库，见 [脚本奖励入库](CLOUDWORKS_SCRIPT_DEPOSITS.zh-CN.md)。`Storage` 走公共 `CloudworksAPI.deposit`，不触碰玩家物理库存。

普通脚本写入按启动期 `IAction` 延迟应用；不实现 `Cards`。精确 NBT 身份与原生机器的选择性 NBT 身份是两种不同策略，不应混用。

聚变／裂变／锅炉／蒸馏与电阻、木料、热力发电机已接入 [Runtime 动态热力](CLOUDWORKS_THERMAL_RUNTIME_20261011.zh-CN.md)。不复刻真实结构，也不实现损伤、辐射、爆炸或实体伤害。原有热力蒸馏控制器默认绑定保留；其余新多方块类型由整合包作者自行选物品注册适配器。原先仅冷却的聚变控制器默认绑定已取消，不把零燃料冷却节点当成完整聚变反应堆。

## 11. 公共 API 声明通道（2026-10-16 API 化改造）

本节覆盖 `mekanism.cloudworks.api` 提供的附属声明通道：机器、升级类型、扩展 payload、特殊节点与客户端面板。所有通道共享同一注册窗口（mod 构造 → CloudWorks loadComplete 排空）与同一冻结纪律（排空后拒绝、排空失败终止加载）。

### 11.1 机器与发电/热力一体声明

`CloudworksAPI.registerMachine(CloudworksMachineDefinition)` 是附属机器的完整声明入口。一个定义可以同时携带配方模式、环境发电合同与热力合同：

```java
CloudworksMachineDefinition definition = new CloudworksMachineDefinition(
        addonMachine,                                    // 机器物品（计数归一）
        new MachineExecutionProfile(1, 50D, 4_000D, 20), // 并行/J-t/容量/周期
        new MachineSlotLayout()
                .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                        MachineSlotLayout.PortRole.INPUT, 64)
                .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                        MachineSlotLayout.PortRole.OUTPUT, 64),
        MachineIdentityPolicy.none(),
        java.util.Collections.singletonList(new CloudworksRecipeMode(
                "smelt", 0, null, addonRecipeAdapter)),
        environmentGenerator,   // 可为 null；见 5.4 的冻结纪律
        thermalPlan);           // 可为 null；热力合同由内部注册表接线
CloudworksAPI.registerMachine(definition);
```

- `CloudworksRecipeMode.id` 只允许 `[a-z0-9_.:-]`，同一机器内不得重复。
- 空 modes 且既无 generator 又无 thermal 的定义在构造期即被拒绝。
- 发电/热力声明由 `CloudworksApiRegistration` 在排空时桥接到内部注册表，附属不需要（也不允许）再直接调用 `registerEnergyGenerator` 等内部入口。

### 11.2 自定义升级类型

产线 speed/energy 使用云工坊自有模块；节点 Mek 升级及 TE augment 使用 CUSTOM 通路，速度和能量分别本地优先、缺省回退产线。完整合同与 Java／CRT 入口见 [产线升级与节点逐轴优先](CLOUDWORKS_LINE_UPGRADES.zh-CN.md)。一般非轴模块仍可使用以下生命周期接口：

```java
MachineModuleType<Overclock> overclock = MachineModuleType
        .custom(new ResourceLocation("addonmod", "overclock"), 4,
                "upgrade.addonmod.overclock",
                new Overclock())
        .withItem(new ItemStack(addonOverclockItem));  // 可选：物理上传物品
CloudworksAPI.registerMachineModule(overclock);

// 机器策略声明接受度与上限覆盖（否则该机器不吃此类型）
MachineUpgradePolicy policy = MachineUpgradePolicy.fixed(8, 8, 2, 2, 1, 1, false)
        .withCustomType(overclock, 4);

class Overclock implements ICustomMachineModule<Overclock> {
    @Override
    public WorkContribution onPreWork(IMachineModule<Overclock> module, PreWorkContext context) {
        int n = module.getEffectiveInstalledCount();
        return WorkContribution.builder().durationMultiplier(Math.pow(2, -n))
                .energyPerTickMultiplier(Math.pow(2, n)).build();
    }
}
```

- 命名空间 `mekanism`/`cloudworks` 保留；类型数量无上限（网络包读入按剩余字节推导防御，防伪造巨型列表）。
- `withItem` 仅 CUSTOM 槽可用；模板必须是注册物品、确定 metadata、计数归一为 1。一个物品最多识别一个类型，且不得与原生 speed/energy 绑定物品冲突——违者在排空期终止加载。
- 两轴分别选择来源：有效节点 SPEED 只接管速度，ENERGY 只接管能量；未激活时回退产线，不比较强弱、不补差额。
- A 的覆盖率分母按整线参与机器稳定计算，不因本地接管而减少；节点模块不从产线两轴计数扣除。
- 旧节点 `assignedSpeed/assignedEnergy` 分配通路退出，节点安装使用 `assignedCustom`；不做旧档迁移。
- 节点升级窗口使用可用 CUSTOM 模块和节点已装模块两个列表；移除不能实际转移的旧原生产线分配入口，仍通过同一草稿事务提交。
- 观察态节点详情下方复用主界面升级层的图标列表、`GuiCloudUpgradeDetails` 信息面板和支持升级栏，只读取该节点实际安装数量，不显示共享状态。没有输入／输出槽、转移箭头或卸载按钮；详情内容超出屏幕时在窗口内滚动。
- CUSTOM 数值流：终端物品上传到云端缓存 → 节点编辑草稿预占可用数量 → 提交时原子扣云端、更新节点。包括 Mek 节点速度／能量模块在内，卸载、删节点、删线按 CUSTOM 资产账返还，不能再按旧原生轴释放一次。
- 产线 NBT 的 `customUpgradePool` 现在是节点已安装资产的汇总占用台账，不是第二份库存，也不提供产线共享效果。提交时按“新节点总数－旧台账”结算，旧台账中未占用的余量退回云端。未知 ID 继续保留，不能把节点数量与该台账相加统计资产。
- `CloudworksClientActions.installCustomUpgrade/removeCustomUpgrade` 修改面板所绑定机器的本地草稿，不再发送产线自定义升级安装命令。观察态和失效草稿仍拒绝修改。蓝图导入同时检查自定义升级缺口，待补完成后才扣缓存并恢复节点指定数量。
- `onRecipes` 在配置冻结阶段执行，按机器身份、安装数量及完整模块集合缓存；Runtime 不重跑配方回调。`onRecipeCandidate`、`onPreWork`、`onPostWork` 是纯函数，可在 worker 运行。非法贡献拒绝当前计划/提交，不发布部分资源变化。
- CraftTweaker 只读面：`mods.cloudworks.Upgrades.customTypes()` 与 `getCustomItem(id)`；原生两物的重绑仍走 `setItem`/`useNativeItem`。
- 存档：节点 `customUpgrades` / 产线与云端 `customUpgradePool` 均为可选复合标签，未知 ID 计数原样保留。
- 机器草稿上的 `mechanismDefinitions` 已于 2026-10-24 移除，编辑模型挂载与草稿拷贝传播一并清除。`CloudworksNodeMechanisms.slots` 两端共用执行侧组合入口：按机器完整身份、配方模式、机器数量和已装模块集合解析端口；等待蓝图按该机器及模式的 `waitingMechanism` 预览。仅按模块 `id` 查到的定义只适合目录说明，不能代替某个节点的端口合同。
- 云升级目录（`overview.customUpgrades`）条目只有 **id＋count**；displayKey／item／描述均由客户端 `CustomUpgradeInfo.fromRegistry` 从本地注册表解析。已装模块即使耗尽云端和产线池、或按需目录为 `null`，仍按 `assignedCustom` 的 id 显示本地图标与描述；只有本地未知 id 才显示裸 id 和空图标。
- `MachineModuleType.withDescription(键...)` 声明客户端本地化描述键，未翻译键跳过。TE 集成复用本体 `info.thermalexpansion.augment.*` 多行键：CloudWorks 的机器模块短名需映射到 TE 的 `machine*` 前缀，`machine_speed_coil` 对应 TE meta 128 的 `machinePower`。

### 11.2.1 节点完成阶段输出策略

需要在配方完成提交前根据节点状态改写产物时，实现 `ICustomMachineModule.onPostWork`：

```java
class ConditionalModule implements ICustomMachineModule<ConditionalModule> {
    @Override public Collection<QIOResourceDescriptor> outputResources() {
        return Arrays.asList(dirt, gold);
    }
    @Override public List<MachineOutput> onPostWork(IMachineModule<ConditionalModule> module,
            PostWorkContext context, List<MachineOutput> planned) {
        boolean hasCondition = module.getInstalledCount(conditionUpgrade) > 0;
        return planned.stream().map(output -> output.descriptor(hasCondition ? dirt : gold))
                .collect(Collectors.toList());
    }
}
```

完成回调可以读取机器身份、配方 ID、节点机器数量、自定义升级数量和输出容量，并返回新的输出列表。`MachineOutput` 带有 `chanceOutput()` 标记；随机判定由核心按操作序列完成，回调不自行掷骰。新增输出资源身份须在 `outputResources()` 声明，以便在进入 worker 前加入资源字典。

策略在 Runtime worker 中运行一次，位于最终容量检查和输出提交之前。策略不得访问 `World`、`TileEntity`、玩家、第三方网络或非线程安全静态对象。策略返回的输出必须来自当前资源字典；未知资源会拒绝该完成操作。

`context.environment()` 返回该机器节点冻结的 `CloudworksGenerationContext`，可读取模拟高度、时钟、熔岩模块数量，以及 `netherEnvironment()`、`oceanEnvironment()`、`forestEnvironment()`、`rainEnvironment()`、`thunderEnvironment()`。`environmentTypes()` 和 `hasEnvironment(ResourceLocation)` 还允许附属读取或添加自己的环境类型，避免每种兼容都要改核心 API。发电回调收到的第三个参数也是对应机器节点的环境快照。环境标记由同类型特殊节点独立计算：机器节点有超过一半面积被单个启用来源覆盖才生效，多个来源不会叠加；非活动机器和蓝图预览不计入。

`discardsChanceOnOverflow()` 可让模块在准入阶段跳过概率副产物的最坏容量估算；主产物仍必须能容纳。多个 `onPostWork` 按 `postWorkPriority()`、模块 ID、来源节点 ID 排序并链式执行。最终列表统一校验端口、codec 和容量；输出变换结果、配方 ID、运行功率和操作标识随 checkpoint 保存，等待输出期间不重复执行。

129 类可堆叠升级使用 `onPreWork` 返回 `WorkContribution.secondaryChanceMultiplier`；130 类输出焚毁使用 `onPostWork`，两者可以同时安装。`onWorkStarted`、`onWorkCommitted`、`onWorkCancelled` 在对应状态被接受后派发，只作只读通知；安装变化使用 `onAdded` / `onRemoved`。不提供逐 Tick 回调。

### 11.3 扩展 payload（产线级）

附属可为产线挂载自定义持久化数据，值类型不泄漏到 worker 或客户端：

```java
CloudworksExtensionType<MyData> type = new CloudworksExtensionType<>(
        new ResourceLocation("addonmod", "line_config"), 2,
        tag -> new MyData(tag),          // Reader：从 payload 复合标签读出值，不得修改 tag
        (data, tag) -> data.write(tag)); // Writer：把值写入传入的 payload 复合标签
CloudworksAPI.registerExtension(type);
```

- 版本号必须为正；容器按存储的版本判定错配（不靠 Reader 返回值），错配条目原样保留。
- 产线宿主：`ProductionLineData.extensions()`；GUI 编辑往返（草稿应用）不丢 payload——应用路径走 NBT 往返。
- 边界：类型数量无上限 / 单条 16KB / 总量 64KB / 嵌套 6 层；未知 ID 与版本错配条目原样保留，畸形条目隔离，超总量丢弃保护存档。
- 蓝图可移植性（服务端 NBT 侧）已实现：`withExtensions` 导出、入库可选 "extensions" 标签、宽松载体上限（总量的 8 倍）；网络共享 `bytes()` 零携带，放置点在提交期合入（目标胜，越界/坏版本/畸形静默跳过）。

### 11.4 卡片效果通用通道

内置卡片效果（LINE_LIMIT、WORK_RATE 等）保持专有字段为唯一真值。附属效果走 `Target.CLOUD_STATE` 通用等级表：

```java
CloudworksCardEffect luck = CloudworksCardEffect.cloudState(
        new ResourceLocation("addonmod", "luck"), 1, 5, java.util.Collections.emptyList());
CloudworksCardEffect unlock = CloudworksCardEffect.unlockCloudState(
        new ResourceLocation("addonmod", "auto_pin"), java.util.Collections.emptyList());
```

- 数值效果累加到声明上限；解锁效果 0→1 锁存；`increase()` 拒绝 CLOUD_STATE（必须用专用工厂）。
- 等级表 `CloudworksData.cloudEffects()` 是这些效果的唯一载体（类型数量无上限，NBT 可选标签 "cloudEffects"，未知 ID 等级保留）。
- 云状态窗口（GUI 状态屏）与 `api.client` 的 `CloudworksClientCloudView.effectLevels()` 以不透明 ID 显示等级；附属自带翻译。

### 11.5 特殊节点注册

```java
CloudworksAPI.registerSpecialNodeType(CloudworksSpecialNodeType.source(
        new ResourceLocation("addonmod", "mana_well"), "special.addonmod.mana_well"));
```

- 方向只有 SOURCE/SINK；`cloudworks`/`mekanism` 命名空间保留；重复 ID 在排空期终止加载。
- 内置 `cloudworks:infinite_resources` 为首个消费者（静态播种，排水前可读）。
- 内置 `cloudworks:nether_heat_source`、`cloudworks:ocean_environment`、`cloudworks:forest_environment`、`cloudworks:rain_environment`、`cloudworks:thunder_environment` 是不选择资源的环境来源；对应卡片分别解锁各自的节点额度。
- 未知 archetypeId 的存档频道按非特殊降级，不抛异常；玩家无权限时不能通过构造 payload 建立无限资源节点（服务端校验）。
- 注意：SINK 能力仅为合同能力；用户已取消资源汇物品，不要因合同存在把它加回游戏。

### 11.6 客户端面板（`mekanism.cloudworks.api.client`）

附属为自己的特殊节点提供客户端窗口，核心 GUI 不 import 任何特定集成：

```java
// 客户端 mod 初始化阶段
CloudworksClientPanels.register(new CloudworksClientPanelProvider() {
    @Override public ResourceLocation id() { return new ResourceLocation("addonmod", "mana_panel"); }
    @Override public int priority() { return 0; }
    @Override public boolean handles(CloudworksClientNodeView node) {
        return "addonmod:mana_well".equals(node.archetypeId());
    }
    @Override public GuiWindow createWindow(IGuiWrapper gui, CloudworksClientNodeView node,
            CloudworksClientCloudView cloud, CloudworksClientActions actions) {
        // 只读展示 + 受控编辑；观察会话（node.editing()==false）必须保持只读
        return new AddonManaWellWindow(gui, node, cloud, actions);
    }
});
```

- 咨询顺序确定：priority 升序、同优先级按 id 字典序，与注册顺序无关；重复 ID 与保留命名空间注册期拒绝。
- `CloudworksClientNodeView` / `CloudworksClientCloudView` 是不可变快照（节点、自定义升级池、效果等级）；`CloudworksClientActions` 的编辑动作经既有网络通道提交并在服务端 owner 锁下重验。
- 适配发生在 `CloudworksLineWindowFactory` 内部：内部容器、草稿与编辑模型不会跨界传入 provider。
- 专服隔离：common 与核心 API 的类不引用 `api.client`（有常量池扫描测试守卫）；该包只在客户端加载。
- 节点视图是创建面板时的快照，后续修改不会倒写原视图；动作句柄仅对原编辑草稿有效，观察态、提交期间、离开界面或替换草稿后拒绝修改。
- 连线不再伪装为 `kind="link"` 的节点。实现 `CloudworksClientLinkPanelProvider` 并调用 `CloudworksClientPanels.registerLink(provider)`；`CloudworksClientLinkView` 单独提供端点、端口、优先级、比例和过滤列表。该面板入口当前只读，不暴露节点编辑动作。

### 11.7 流闸门（参考）

六个能量闸门与路由开关统一声明于 `CloudworksFlowGate`（云 4 + 线 3）；宿主字段保持唯一真值，GUI/网络协议只暴露声明为可编辑的子集（云侧 r2c/c2s、线侧 r2l/l2s）。附属不需要直接使用该类；了解默认值（收货方向开、发货方向关）与"构造包翻不动未暴露闸门"的语义即可。

### 11.8 机器调色板分类

机器分类是注册元数据，不是玩家权限、机器身份或产线存档的一部分。服务端把分类 ID 与语言键投影到机器缓存 GUI 数据，客户端按同一份数据过滤列表；过滤后的格子仍指向原缓存中的机器身份。

```java
CloudworksAPI.registerMachine(
        new CloudworksMachineDefinition(machine, profile, slots, identity, recipeModes)
                .category("addonmod:mana", "gui.addonmod.machine_category.mana"));
```

- 自定义分类位于“机器”页内，不能追加顶层节点家族。类型筛选依次为全部、加工、发电、其他，再按自定义 ID 排序；基础筛选类型始终保留，无条目时显示空网格。
- ID 必须是规范的命名空间 ID；自定义 ID 不允许占用 `cloudworks` / `mekanism` 命名空间。语言键不能为空，同一分类 ID 的不同机器必须使用一致语言键。
- 未显式声明时，由环境发电合同、已冻结的燃料产能配方、热力合同及配方适配器推导默认分类；查询分类不会调用原生适配器重新冻结。
- 机器别名和模板继承来源分类。注册失败时，分类元数据与其他机器注册一起回滚。
- `CloudworksMachineInfo.category()` 返回当前查询到的分类；声明本身的 `category()` 为可选值，`resolvedCategory()` 提供尚未排空声明的默认分类。
- 编辑器的“节点列表”入口打开独立子窗口；输入/输出节点归“输入输出”，无限资源在玩家解锁后归输入页。
- 四个根页签固定在窗口左侧并始终显示；当前类型通过单个按钮切换，搜索框按节点名称或机器物品 ID 过滤。四页共用同坐标的 5×6 插槽网格。
- 通用 addon 特殊节点解释器仍需单独补齐；辅助节点 Runtime 已支持独立存档、AREA 目标、流体输入和冻结倍率，Thermal Expansion 散热器是首个实现。
### 11.9 概率掉落注入（手动挂载）

三级战利品管线（冻结 spawn 表 → classpath 直读 loot JSON → 逐 kill 逐池滚动）对模组条件/函数整池拒收、装备条目跳过。被拒掉或后续新增的掉落物，附属与整合包作者可通过注入 API 手动补上概率——冻结管线本身永不修改，注入只在管线结果之上追加：

```java
// 每次模拟击杀僵尸，5% 概率掉 1-2 个钻石
CloudworksAPI.registerLootInjection(CloudworksLootInjection.entity(
        "minecraft:zombie", new ResourceLocation("minecraft", "diamond"), 1, 2, 0.05));
// 下界要塞轴的每次模拟事件（实体击杀与结构箱翻阅各掷一次）必掉 1 个绿宝石
CloudworksAPI.registerLootInjection(CloudworksLootInjection.axis(
        StructureAxes.NETHER_FORTRESS.id(), new ResourceLocation("minecraft", "emerald"), 1, 1, 1));
```

- 目标两类：`ENTITY` 按实体注册名（该实体的每次模拟击杀独立掷一次，无论哪个轴抽中它）；`AXIS` 按环境轴（该轴下每次实体击杀与每次结构箱翻阅各掷一次；天气/维度轴没有掉落事件，永不触发）。
- 每条注入独立掷：命中概率 `chance ∈ (0,1]`，掉 `[minCount, maxCount]` 个；带 `meta` 参数的重载支持子类型物品。
- 注入不自己制造事件：轴 spawn 表为空即无击杀事件，未声明 chestTables 即无箱事件。
- 排空期校验（fail-fast 带来源，声明窗口回滚可重试）：实体必须在实体注册表、轴必须在轴注册表、物品必须在物品注册表，否则中止加载。
- 生命周期与其它注册一致：load-complete 冻结后再注册直接抛错。CraftTweaker 入口 `mods.cloudworks.LootDrops` 提供实体级及轴级声明、数量范围和 metadata 重载；整合包作者也可用它补充保守 loot 解析器拒收的掉落。
- 脚本门面不接受带 NBT 的掉落物，避免把 loot entry 扩展成复杂物品序列化格式；掉落物必须已注册且 metadata 确定。接口只影响 CloudWorks 的模拟击杀／箱表结果，不监听真实世界掉落事件。
- ZenScript 示例、参数签名和适用事件见 [CraftTweaker 开发指南 §7](CLOUDWORKS_CRAFTTWEAKER_GUIDE.zh-CN.md) 与 [`examples/crafttweaker/`](../examples/crafttweaker/)。
### 11.10 按需目录协议与 GUI 数据分界

**数据分界原则（2026-10-24 裁定）**：

- **能力（Capability）＝客户端事实**：有哪些升级/模块类型、某机器支持哪些、图标与显示名——来自本地冻结注册表（`GuiCloudSupportedUpgrades.localCustomTypes()`），第一帧即可全量渲染；遮罩语义按层差异化（云端层谓词恒可装、无「不支持」概念；节点层按机器策略遮罩）。
- **状态（State）＝服务器事实**：机器已装数量（`assignedCustom`/graph 推送）、云端池库存（按需目录 count）——随服务器数据到达自然补齐，未到时按 0/空显示。

**按需目录（`overview.customUpgrades`）三态协议**（2026-10-24 瘦身：条目只含 **id＋count**）：

- `null`＝目录未请求（没有任何升级窗口开着，服务器不随推送携带）；
- 空列表＝已请求且云端确无自定义类型；
- 非空列表＝已请求，每条只有类型 id 与云端池数量——displayKey／item／描述键／机制元数据全部是客户端能力，经 `CustomUpgradeInfo.fromRegistry` 从冻结注册表解析（unknown id 渲染为裸 id 空壳）。

请求生命周期：升级类窗口打开时发 `CATALOG_VIEW=1`，最后一个消费窗口关闭时发 `=0`（服务端视图丢弃，客户端下次推送清除陈旧目录）。窗口构建先于请求生效，因此**一切消费方必须容忍 null**（absent＝空目录/数量 0）。

消费方清单（新增消费点必须过此表；2026-10-24 连环 NPE 后固化）：

| 消费方 | 语义 | null 处理 |
| --- | --- | --- |
| `ContainerCloudworks.customUpgradeCount` | 单类型池数量 | 返回 0 |
| `GuiCloudUpgradeWindow` SnapshotHolder | 已安装列表（count>0 过滤） | 跳过自定义段 |
| `CloudworksLineEditorModel` 合并池视图 | 池状态（id＋count） | 跳过 overview 段，graph 段兜底；节点已装 id 单独保留 |
| `CloudworksLineWindowFactory.cloudView` | addon 面板云视图 | 空目录视图 |
| 两处支持条（节点详情/云升级层） | 类型全集 | 已改本地全集，不再消费目录（能力侧） |

相关缓存纪律：引用比较缓存依赖「服务器推送必换引用」的差分语义。草稿不再挂载 `mechanismDefinitions`；端口由本地的节点机制组合入口解析，不向服务器快照倒写目录信息。

### 11.11 升级详情自动滚动与嵌套裁剪

- 两层升级列表及右侧详情底框统一高 **50 像素**，详情宽度沿用本体的 59 像素。云端详情显示 **3 行**并为卸载按钮留出底部位置；机器节点详情显示 **4 行**。
- 名称、数量和全部已本地化描述属于同一连续内容，不固定在视口之外；内容超高时自动上下往返，不依赖鼠标滚轮。某行超宽时独立左右往返，保持固定字号；内容能放下则静止。切换升级后从起点重新计时，数量仍读实时服务器状态。
- 实现对齐本体 `lookingat/theoneprobe/TOPTankListElement`：纵向、横向均复用 `IGuiWrapper.marqueeOffset`，实际文字绘制复用 `IGuiWrapper.drawTextWithScale`。本体源码、依赖 JAR 均不修改。
- `CloudworksGuiScissor` 只承担云工坊的局部裁剪适配。LWJGL 2 的 `glGetInteger` 即使读取四值 `GL_SCISSOR_BOX`，也要求至少 16 个剩余整数；不能照搬四整数缓冲区。裁剪必须和外层窗口取交集，并在正常返回或绘制抛错时恢复原状态。
- 常规回归：`GuiCloudUpgradeDetailsTest`。真实离屏 OpenGL 验证：在仓库根目录执行 `gradlew.bat -I ai/GLM/tools/run_gui_upgrade_probe.init.gradle cloudworksUpgradeRenderProbe --console=plain`；该夹具复现原缓冲区崩溃，并检查嵌套／不相交裁剪、像素边界和异常后的状态恢复，不启动游戏世界。

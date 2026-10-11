# 云工坊开发与其他 Mod 接入指南

更新日期：2026-10-10。本文以当前源码为准，不表示开发阶段 API 已经稳定。

本指南面向 Minecraft 1.12.2 / Forge / MekanismCEU 附属开发者。机器及配方接入清单另见 [已接入机器与配方](CLOUDWORKS_SUPPORTED_MACHINES_AND_RECIPES.zh-CN.md)。

## 1. 先确定接入边界

云工坊执行的是虚拟机器的资源、能量与进度合同，不是在另一个线程上运行真实 `TileEntity`。

| 接入目标 | 当前入口 | 必须遵守的边界 |
| --- | --- | --- |
| 普通配方机器 | `CloudworksRegistries.registerMachine` + `CloudworksMachineAdapters.register` | 把原模组配方转换成可冻结批次，不创建真实机器 |
| 等级、模式机器 | 上述入口 + `MachineIdentityPolicy` | 只选择影响执行的身份 NBT；每种身份独立注册 |
| 概率、随机资源机器 | `CloudworksRecipeBatch` 的概率或互斥输出合同 | 随机产物在操作开始时承诺，保存和重启不能重新抽取 |
| 环境发电机 | `registerEnergyGenerator` + `CloudworksEnergyGenerator` | 冻结为数值 `CloudworksGenerationPlan`，不从 worker 查询世界 |
| 燃料发电机 | `registerSpecial` + `registerEnergyGenerator` + 已审计燃料冻结合同 | 真实消耗燃料，按实际发电结算；仅注册接口不等于 Runtime 已支持 |
| 新资源类型 | Mek 的 QIO codec 注册 + 云工坊资源描述符 | 通用云仓可以保存；配方、渲染和物理端口仍需各自接入 |
| 外部存储系统 | 真实上传/下载端口或独立集成组件 | 只在服务器主线程访问 QIO、其他网络和能力接口 |
| 玩家机器物品上传源 | `ICloudworksUploadSource` | 这是机器物品预留/确认协议，不是通用资源上传 API |
| CRT 机器别名和云端专用配方 | `common/integration/crafttweaker` | 启动声明、冻结执行，不向 worker 提交脚本回调 |
| Mek 本体多方块的虚拟合同 | `CloudworksMultiblocks` | 不绑定默认物品；由 CRT 或外部附属指定整个结构的代表物品 |

云仓、产线运行、Runtime worker 不直接操作外部箱子、管道、玩家库存或第三方网络。QIO 插件仍通过端口本地缓存接入，不绕过原有云端转移。

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
所有 Mod 的 postInit：完成各自原生配方和外部附属机器注册
Cloudworks.loadComplete：
    CloudworksHooks.register()
    CloudworksScriptRegistry.apply()
    CloudworksRegistries.freeze()
    CloudworksNativeContracts.refresh()
```

外部附属应在自己的 `FMLPostInitializationEvent` 中注册，且依赖顺序保证机器物品和配方表已经准备好。不要等到自己的 `FMLLoadCompleteEvent`，因为此时 Cloudworks 可能已经冻结。

项目内部 MoreMachine 集成由 Cloudworks 的 `loadComplete` 在冻结前显式调用，这是内部入口，不等于外部附属也可以任意在加载完成后注册。

冻结后 `registerMachine`、`registerSpecial`、`registerEnergyGenerator` 和 `CloudworksMachineAdapters.register` 会拒绝新增注册。外部附属不要调用 `freeze()` 或修改 Runtime 内部表。

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
- 双物品配方需要主输入及额外输入，例如 `item_input`、`extra_item_input`。
- 双气体配方和电解左右产物应独立声明端口，不合并成无法区分的出口。
- 注册表保存布局的冻结副本，注册后不能通过修改原对象调整已注册容量。

### 3.3 优先使用已有适配器

若原生配方对象就是 Mek 的对应配方类，可以直接复用已有适配器。

例如当前 Mek 的粉碎机注册等价于以下代码，供阅读参考，**不要在附属中再次执行同一内置注册**：

```java
ItemStack machine = new ItemStack(MekanismBlocks.MachineBlock, 1, 3);
MachineSlotLayout layout = new MachineSlotLayout()
        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.INPUT, 64)
        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID,
                MachineSlotLayout.PortRole.OUTPUT, 64);

CloudworksRegistries.registerMachine(
        machine, new MachineExecutionProfile(1, 50D, 6),
        layout, Collections.emptyList());
CloudworksMachineAdapters.register(
        CloudworksRegistries.identityKey(machine),
        new CloudworksBasicItemRecipeAdapter(RecipeHandler.Recipe.CRUSHER.get()));
```

这里的 50 J/t 和 6 tick 是当前内置注册值，不是“读取了所有原机参数”的承诺。新接入应对照原机实际语义选值。

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

然后在冻结前注册具体类：

```java
CloudworksAsyncRecipeAdmission.registerAudited(
        AddonRecipeAdapter.class,
        adapter -> ((AddonRecipeAdapter) adapter).captureFrozenView());

CloudworksRegistries.registerMachine(
        addonMachine,
        new MachineExecutionProfile(1, baseEnergyPerTick, baseCapacity, baseTicks),
        layout,
        Collections.emptyList(),
        MachineIdentityPolicy.none());

CloudworksMachineAdapters.register(
        CloudworksRegistries.identityKey(addonMachine),
        new AddonRecipeAdapter(addonRecipeMap));
```

`addonMachine`、参数和 `addonRecipeMap` 由附属自己的物品、配置和原生配方表提供。

准入按 `adapter.getClass()` 精确查找，不按父类匹配。继承了可冻结基类，并不等于新子类自动获得异步准入。漏掉准入时 Runtime 会拒绝该适配器并保留诊断原因，不保证回退为可工作的主线程机器。

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
MachineIdentityPolicy tierIdentity =
        MachineIdentityPolicy.keys("tier").defaultInt("tier", 0);

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

    CloudworksRegistries.registerMachine(machine,
            new MachineExecutionProfile(processes,
                    MachineType.CHEMICAL_INFUSER.getUsage(),
                    MachineType.CHEMICAL_INFUSER.getStorage() * processes, 1),
            layout, Collections.emptyList(), tierIdentity);
    CloudworksMachineAdapters.register(
            CloudworksRegistries.identityKey(machine),
            new CloudworksChemicalPairRecipeAdapter(
                    RecipeHandler.Recipe.CHEMICAL_INFUSER.get()));
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

燃气发电机复用 `CloudworksGasGeneratorAdapter`，在 `registerSpecial` 和 `registerEnergyGenerator` 同时登记。燃料、消耗量及能量来自冻结配方，不能将“无燃料时也产生恒定能量”作为近似实现。

现有特殊合同需要异步准入。`registerAudited` 当前要求类实现 `CloudworksMachineAdapter`；只实现 `SpecialMachineAdapter` 的新类不能直接使用这个注册方法。若新特殊行为不能复用现有合同，需要先扩展并验证公开准入/冻结接口，不能只注册一个回调就认为接入完成。

## 6. 线程与持久化规则

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
| 身份规则 | `src/main/java/mekanism/cloudworks/common/registry/MachineIdentityPolicy.java` |
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

普通脚本写入按启动期 `IAction` 延迟应用；不实现 `Cards`。精确 NBT 身份与原生机器的选择性 NBT 身份是两种不同策略，不应混用。

聚变／裂变／锅炉／蒸馏与电阻、木料、热力发电机已接入 [Runtime 动态热力](CLOUDWORKS_THERMAL_RUNTIME_20261011.zh-CN.md)。不复刻真实结构，也不实现损伤、辐射、爆炸或实体伤害。原有热力蒸馏控制器默认绑定保留；其余新多方块类型由整合包作者自行选物品注册适配器。原先仅冷却的聚变控制器默认绑定已取消，不把零燃料冷却节点当成完整聚变反应堆。

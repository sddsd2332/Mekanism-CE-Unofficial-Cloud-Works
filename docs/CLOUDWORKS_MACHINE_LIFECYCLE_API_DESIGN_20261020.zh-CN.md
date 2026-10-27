# CloudWorks 机器模块生命周期 API 设计

日期：2026-10-20  
状态：2026-10-20 已实施破坏性 API 迁移；本稿保留设计依据，实际公共签名、执行边界与验证记录见[机器模块 API](CLOUDWORKS_MACHINE_MODULE_API.zh-CN.md)  
范围：统一机器升级、辅助节点及环境节点对机器配方与运行阶段的扩展入口

---

## 1. 设计目标

CloudWorks 目前仍处于开发期。本设计允许对现有扩展 API 做破坏性调整：把影响机器的配方、运行参数、完成输出和范围显示统一到一个模块生命周期模型中。被新模型取代的 API 可以直接删除，不要求保留旧签名、兼容桥或弃用过渡期；CloudWorks 内部调用方、附属示例和测试应在同一变更中迁移。

新 API 参考 Mekanism CE-U 的 `ICustomModule<MODULE>` / `IModule<MODULE>` 关系：

- 附属实现一个模块行为对象，声明它在机器各生命周期阶段如何贡献行为。
- 核心在调用时提供该模块对应的只读机器模块句柄。
- 句柄包含当前模块、安装数量、目标机器和来源节点等信息，不让附属自行查找全局状态。

CloudWorks 的运行线程模型与 Meka 模块不同。这里的生命周期 API 必须适配蓝图事务、机器节点和异步 Runtime：运行阶段只接收不可变快照并返回数据，不允许回调直接访问世界、TileEntity、玩家、库存或修改已发布的 Runtime。

### 1.1 首批生命周期

| 生命周期 | 触发时机 | 用途 |
| --- | --- | --- |
| `onAdded` | 候选安装事务中，该模块的有效安装数增加 | 得知模块安装或堆叠数量增加；使机器合同重新编译 |
| `onRemoved` | 候选安装事务中，该模块的有效安装数减少 | 得知模块卸载或堆叠数量减少；使机器合同重新编译 |
| `onRecipes` | 候选机器合同冻结时 | 识别并派生配方、声明配方模式、机制参数和额外端口 |
| `onRecipeCandidate` | Runtime 正在检查一个具体候选配方时 | 按当前机器配置和冻结环境接受、拒绝或调整候选优先级 |
| `onPreWork` | 某个操作进入执行计划前 | 提交时长、功率、容量、概率等数值贡献及资源成本 |
| `onPostWork` | 操作完成、输出最终提交前 | 变换、删除、拆分或合并本次操作的输出 |
| `onWorkStarted` | 操作通过供能结算并正式开始推进后 | 接收一次开工通知 |
| `onWorkCommitted` | 操作输出原子提交后 | 接收一次完成提交通知 |
| `onWorkCancelled` | 已正式开工的操作被明确取消时 | 接收取消原因；输入/成本回滚仍由核心负责 |

范围显示是可选客户端伴随能力，不加入服务端机器生命周期回调。

### 1.2 非目标

- 不提供逐 Tick 的任意附属回调。运行中的持续效果应编译为工作贡献，由核心按每 Tick 状态机结算。
- 不允许回调自行扣除输入、输出资源或能量。
- 不让客户端范围渲染决定服务端目标判定。
- 不为现有开发期 API 设计兼容桥。
- 不提供每 Tick 的 `onWorkTick`；持续消耗通过工作贡献中的声明式成本策略结算。

## 2. 统一模块与安装来源

“机器模块”是对机器产生影响的可组合行为单元，不限定为物品升级。首批来源包括：

- 机器节点直接安装的自定义升级；
- 命中机器的辅助节点；
- 命中机器的环境特殊节点。

模块来源必须明确，不能让一个回调隐式读取“附近有哪些节点”。核心将符合资格的来源解析成模块实例，并为每个来源与目标机器建立独立句柄。多个辅助节点同时作用于一台机器时，每个来源各自产生一份贡献，再由核心按明确规则合并。

建议类型与接口名称如下，最终命名可以在实现时遵循项目既有命名风格：

```java
public interface ICustomMachineModule<MODULE extends ICustomMachineModule<MODULE>> {
    default void onAdded(IMachineModule<MODULE> module, ModuleChangeContext change) {}
    default void onRemoved(IMachineModule<MODULE> module, ModuleChangeContext change) {}

    default RecipeContribution onRecipes(
            IMachineModule<MODULE> module, RecipeFreezeContext context) {
        return RecipeContribution.none();
    }

    default RecipeCandidateDecision onRecipeCandidate(
            IMachineModule<MODULE> module, RecipeCandidateContext context) {
        return RecipeCandidateDecision.accept();
    }

    default WorkContribution onPreWork(
            IMachineModule<MODULE> module, PreWorkContext context) {
        return WorkContribution.neutral();
    }

    default List<Output> onPostWork(
            IMachineModule<MODULE> module, PostWorkContext context, List<Output> outputs) {
        return outputs;
    }

    default void onWorkStarted(IMachineModule<MODULE> module, WorkStartedContext context) {}
    default void onWorkCommitted(IMachineModule<MODULE> module, WorkCommittedContext context) {}
    default void onWorkCancelled(IMachineModule<MODULE> module, WorkCancelledContext context) {}
}

public interface IMachineModule<MODULE extends ICustomMachineModule<MODULE>> {
    MachineModuleType<MODULE> getData();
    MODULE getCustomInstance();

    int getInstalledCount();
    int getEffectiveInstalledCount();

    MachineModuleSource getSource();
    UUID getSourceNodeId();
    UUID getMachineNodeId();
    MachineIdentity getMachineIdentity();

    long getMachineCount();
    String getRecipeId();
    long getInstalledCount(ResourceLocation moduleId);
    MachineEnvironment getEnvironment();
}
```

`IMachineModule` 是 Meka `IModule` 的概念对应物，不是可变节点对象。所有返回的集合、身份数据和 NBT 视图都必须不可变或防御性复制。句柄不暴露 `World`、`TileEntity`、玩家、库存或 Runtime 内部引用。

`getInstalledCount()` 表示当前来源安装数；`getEffectiveInstalledCount()` 表示核心按该模块声明的计数范围和激活规则计算后、允许参与当前目标机器计算的数量。对辅助节点而言，每个来源分别计算，不会把多个散热器合并成一个虚构的来源。`getInstalledCount(moduleId)` 用于模块检查其他升级，例如判断是否安装 E 升级。

注册入口应将类型元数据与生命周期行为绑定：

```java
MachineModuleType<ExampleModule> type =
        MachineModuleType.builder(new ResourceLocation("addon", "example"),
                        ExampleModule::new)
                .maxPerMachine(8)
                .withItem(exampleUpgrade)
                .allowedSources(MachineModuleSource.UPGRADE)
                .build();

CloudworksAPI.registerMachineModule(type);
CloudworksAPI.acceptMachineUpgrade(machine, type, 8);
```

`allowedSources` 用来限制模块可从何处挂载。例如机器升级可以只允许 `UPGRADE`，TE 散热器可以只允许 `AUXILIARY_NODE`。环境模块可以来自特殊节点。允许的生命周期能力也可由类型限制，避免一个只应该提供时长倍率的辅助节点意外改变机器配方或端口。

## 3. 生命周期合同

### 3.1 `onAdded` / `onRemoved`：候选安装事务

这两个回调表达安装集合变化，不是可直接写入机器 Runtime 的命令。`ModuleChangeContext` 至少提供：

```java
int previousCount();
int candidateCount();
boolean isFirstInstall(); // previousCount == 0 && candidateCount > 0
boolean isLastRemoval(); // previousCount > 0 && candidateCount == 0
ModuleChangeReason reason();
```

核心只对候选安装数确实变化的类型调用回调；一次批量上传或卸载按事务聚合，不逐个堆叠物品重复调用。`onAdded` / `onRemoved` 在候选状态编译期间执行，事务被拒绝时，其候选状态和所有编译结果一并丢弃。回调不得执行外部 I/O、改库存或改写共享单例字段。

配方、机制、端口等实际合同从**事务提交后的完整有效模块集合**重新编译。这样卸载时只需重新计算当前仍存在的贡献，不需要让 `onRemoved` 猜测如何撤销旧 Runtime 中的变更，也不会在崩溃恢复或读档时重放安装副作用。`onAdded` / `onRemoved` 可以用于模块自身的事务通知和候选状态检查，但不能绕过后续生命周期接口直接改配方或端口。

因此，模块安装导致配方、机制或端口改变的标准流程是：

1. 核心构造上传、卸载或蓝图应用的候选模块集合。
2. 对数量变化的模块调用 `onAdded` / `onRemoved`。
3. 对候选集合运行 `onRecipes` 并重新校验有效机器合同。
4. 完成连线、缓存、在途操作和资源守恒检查。
5. 整体提交后发布新合同；失败则保留旧合同和旧资产。

载入存档时，核心从持久化安装集合重建冻结合同，不调用 `onAdded` / `onRemoved`。升级的实际数量仍由 CloudWorks 原有资产事务负责。

### 3.2 `onRecipes`：配方与机器合同冻结

`onRecipes` 在拥有线程的候选冻结阶段调用，不在 Runtime worker、玩家 Tick 或渲染线程调用。上下文提供当前机器身份、基础配方快照、基础端口布局、当前模块集合和配方模式信息。回调返回不可变贡献，不修改传入的基础列表。

```java
public interface RecipeFreezeContext {
    MachineRegistration machine();
    List<CloudworksRecipeSpec> baseRecipes();
    MachineSlotLayout basePorts();
    List<ModuleSnapshot> modules();
}

public final class RecipeContribution {
    RecipeMode mode();                 // NONE / REPLACE / APPEND / PARALLEL
    String exclusiveGroup();
    List<CloudworksRecipeSpec> recipes();
    MachineSlotLayout additionalPorts();
    Optional<MachineExecutionProfile> executionProfile();
    Optional<GenerationContract> generation();
    Optional<ThermalContract> thermal();
}
```

这些模式沿用现有机制 API 已建立的语义：`REPLACE` 替换主配方集，`APPEND` 优先尝试派生集再按既定规则回退，`PARALLEL` 建立独立执行分支。模块可以通过机器句柄读取其他模块的安装数量后，决定是否产生贡献。每个模块都从同一份不可变基础配方快照派生；多个模块之间不隐式传递可变列表。

核心按稳定规则组合贡献：冲突组和主合同唯一性先校验；可并存的贡献按模块 ID、来源节点 ID 排序；所有端口与基础布局走同一套校验。端口同名时，角色、codec、容量和多连线属性必须完全一致，否则候选事务失败。配方引用未知端口、容量不兼容或合同冲突时也拒绝发布候选合同。

`onRecipes` 适用于“这类配方是否参与变换”“为配方添加流体输入”“增加输出端口”“覆盖执行机制”等冻结合同判断。具体某一次操作的时长、功率或输出概率属于 `onPreWork`；某个操作实际生成后的输出更改属于 `onPostWork`。不得在每次 Runtime 查询配方时重新运行冻结回调。

冻结缓存至少按机器身份（包含会影响配方的 NBT）、有效模块类型与来源、安装数量、配方模式及其他参与合同的配置组成键。所有回调结果会立即复制并编译为核心持有的不可变合同。

### 3.2.1 `onRecipeCandidate`：运行期候选判定

`onRecipes` 负责构造冻结配方集合；如果模块的接受条件依赖当前机器配置或本次执行环境，则可实现 `onRecipeCandidate`。核心在配方维度、环境和模板等基础条件通过后，对当前机器准备尝试的候选配方调用该方法，再由核心检查输入分配和输出容量。

```java
public interface RecipeCandidateContext {
    CloudworksRecipeSpec recipe();
    int originalOrder();
    MachineConfiguration configuration();
    MachineEnvironment environment();
    MachineIdentity machine();
}

public final class RecipeCandidateDecision {
    boolean accepted();
    int priority();
}
```

默认结果为接受、优先级为零。任一模块拒绝候选项时，该候选即被拒绝；通过候选的优先级贡献按整数相加，合计优先级高者先尝试，同优先级按冻结配方原始顺序稳定排序。该钩子不能修改冻结配方、读取动态库存、掷随机数或访问 World；原料够不够、输出能否容纳仍由核心分配器判定。对于只取决于安装模块和机器身份的条件，应优先在 `onRecipes` 冻结阶段处理，避免每次计划重复调用。

该回调可能在每个候选配方、每次重新计划时执行，因此必须是轻量、纯函数、线程安全的；输入相同必须返回相同结果。它只影响本次候选顺序和准入，不改变注册配方集或其他机器的合同。

### 3.3 `onPreWork`：工作前贡献

核心在配方和基础时长确定后、原料及能量最终预留前调用 `onPreWork`。它针对一个具体操作返回数据，不得直接推进进度或扣资源。上下文应包含：

- 稳定机器身份、节点与来源节点 ID、机器数量；
- 当前配方 ID、批量操作数、基础时长；
- 安装模块快照及不可变环境标记；
- 该辅助来源的已解析成本资源类型（如适用）；
- 确定性操作标识；随机掷骰仍由核心管理。

推荐工作贡献使用语义明确的字段：

```java
WorkContribution.builder()
        .durationMultiplier(0.5D)
        .energyPerTickMultiplier(2D)
        .capacityMultiplier(1D)
        .secondaryChanceMultiplier(1.25D)
        .cost(ResourceCost.perBaseMachineTick(coolant, 0.01D))
        .build();
```

所有倍率必须有限且非负；副产概率在核心侧按有效配方概率和配置上限裁剪。`durationMultiplier` 表示时长轴，`energyPerTickMultiplier` 表示每 Tick 功率轴；二者不得互相代替。像 @128 这类升级可以同时缩短时长并提高每 Tick 功率，以保持每次操作总能耗符合设计曲线。

成本必须声明明确计量单位，首批支持：

- `PER_OPERATION`：每次操作固定消耗；
- `PER_BASE_MACHINE_TICK`：按基础时长和目标机器数计算，不随本贡献自己的速度倍率变化。

核心根据来源库存聚合成本，统一检查并原子预留。多种贡献的成本可以来自不同辅助节点库存；任何一项不能支付时，该来源的整份 `WorkContribution`（倍率和成本）整体不生效，不允许出现“扣了成本但速度没生效”或“倍率生效但未扣成本”。成本小数余量、预留和回滚由核心按来源节点持久化，不由附属维护。

倍率按乘法汇总；叠乘不依赖回调顺序。资源成本按来源标识稳定排序并一次性做事务校验。核心在成本核验后保存最终工作计划，后续等待输出空间时不重复调用 `onPreWork`，也不重复扣除已承诺成本。

由于 Runtime 可在异步 worker 上执行该阶段，模块实现必须是确定、无副作用且线程安全的。需要访问世界状态的环境数据必须先冻结为快照后传入上下文；回调不得临时查询世界或读取可变配置。

### 3.4 `onPostWork`：最终输出策略

`onPostWork` 在一次操作已经完成配方计算和概率判定后、输出最终提交前执行。它接收不可变操作上下文及当前计划输出，返回替换后的不可变输出列表。输出可被转换资源种类、调整数量或端口、删除、拆分和合并。核心对所有策略执行完成后的最终列表统一检查资源 codec、端口、容量和在途承诺，然后原子提交。

```java
public interface PostWorkContext {
    String recipeId();
    long operationId();
    int operationCount();
    MachineEnvironment environment();
    long upgradeCount(ResourceLocation moduleId);
    long stored(QIOResourceDescriptor resource);
    long capacity(QIOResourceDescriptor resource);
    long room(QIOResourceDescriptor resource);
}
```

多个模块的后处理策略不能依赖未声明的注册顺序。每种策略声明稳定排序键（可选显式 priority，随后按模块 ID 和来源节点 ID 排序）；核心按该顺序链式执行，前一项的输出列表成为后一项输入。策略不得读写库存，`stored/capacity/room` 只是此次提交边界的只读快照。

操作已完成但出口暂时没有容量时，核心必须保存后处理结果及已执行策略位置到 checkpoint；重启或继续 Tick 不得再次随机生成、重复变换或重复产生资源。若回调返回非法资源、数量或未声明端口，拒绝该操作提交并给出可诊断错误，不部分写入输出。

若策略需要在输出空间不足时丢弃概率副产物，应显式声明 `discardsChanceOnOverflow` 能力。核心只允许据此放宽概率副产物的准入占用；确定性主产物仍按正常容量规则校验。

### 3.5 `onWorkStarted`：正式开工通知

`onPreWork` 生成的是候选工作计划，不代表操作已经开始。核心只有在该操作通过能量结算并确认输入预留后，才产生 `onWorkStarted` 事件。供能失败而当 Tick 内取消的临时候选计划不触发开工通知；处于 `WAITING_POWER`、`WAITING_OUTPUT` 等等待状态也不反复触发。

```java
public interface WorkStartedContext {
    String recipeId();
    long operationId();
    int operationCount();
    int duration();
    long machineCount();
    MachineEnvironment environment();
}
```

该方法是只读生命周期通知，不负责扣除成本、预留原料或改变工作计划。需要影响运行数值的行为应由 `onPreWork` 返回；需要修改产物的行为应由 `onPostWork` 返回。这样可以明确区分“计划了操作”和“操作正式开始”。

### 3.6 `onWorkCommitted`：原子提交完成通知

`onPostWork` 运行在输出提交前，`onWorkCommitted` 则表示核心已经成功扣除承诺输入并写入最终输出。它是只读通知，不能再改写输出、库存或 Runtime。

```java
public interface WorkCommittedContext {
    String recipeId();
    long operationId();
    int operationCount();
    List<Output> committedOutputs();
    MachineIdentity machine();
}
```

Runtime 在 worker 中模拟并生成 checkpoint；因此该通知不能在 worker 内直接触发外部副作用。核心应把带稳定 `operationId` 的事件写入 checkpoint，在服务端接受对应 checkpoint 后再派发。接收方应按事件 ID 去重，避免 checkpoint 重试导致重复处理。该钩子面向统计、审计和后续事件集成，不作为机器配方或资源变更入口。

### 3.7 `onWorkCancelled`：已开工操作取消通知

该通知只用于已经触发 `onWorkStarted` 的操作被明确取消，例如所属节点或产线被删除、操作合同经授权事务替换。供能不足导致的未开工候选计划取消不触发；暂时断电、等待出口空间、普通暂停也不属于取消。

```java
public interface WorkCancelledContext {
    String recipeId();
    long operationId();
    int operationCount();
    WorkCancellationReason reason();
    MachineIdentity machine();
}
```

取消通知不承担资源回滚。核心先按事务规则释放已预留输入，并处理已扣除或尚未确认的模块成本，再在取消状态被 checkpoint 接受后派发通知。接收方以 `operationId` 去重。若操作不可安全取消，原有在途操作保护优先，拒绝变更而不是调用钩子强制清理。

三个工作状态通知都不允许模块实现把可变状态保存在单例对象中。若未来需要让模块跨操作持有状态，应另行设计带 codec 和 checkpoint 版本合同的显式状态类型。

## 4. 阶段顺序与数据流

```text
安装/卸载事务
  -> onAdded / onRemoved（仅通知数量变化，候选态）
  -> 从完整有效模块集调用 onRecipes
  -> 端口、配方和执行机制校验
  -> 原子提交并发布冻结合同

一次操作
  -> 基础维度/环境/模板校验
  -> onRecipeCandidate 对动态候选配方判定并排序
  -> 对候选配方调用 onPreWork（为每个有效来源生成工作贡献）
  -> 核心按贡献汇总结果并检查配方输入分配与输出承诺
  -> 汇总倍率，原子核验/预留能源、原料及模块成本
  -> 供能结算通过、操作确认开始后调用 onWorkStarted
  -> 核心推进工作并按确定性随机序列形成计划输出
  -> onPostWork 链式改写计划输出
  -> 最终端口/容量校验
  -> 原子提交输出，记录 onWorkCommitted 事件
  -> 已开工操作被明确取消时先由核心回滚，再记录 onWorkCancelled 事件
```

具体例子：@129 在 `onPreWork` 调高副产概率；核心据此完成该操作的概率判定；@130 在 `onPostWork` 检查最终输出空间，按配置删除可丢弃的副产物；核心再执行最终容量校验和提交。`onPostWork` 不能倒过来重掷概率，也不能消除已经提交的库存。

`onWorkStarted`、`onWorkCommitted`、`onWorkCancelled` 是 checkpoint 事件：只有对应状态变更被服务端接受后才派发。三者都是状态通知，不会在每个模拟 Tick 重复调用。

## 5. 多来源合并、隔离与确定性

一个机器可以同时受到多种升级、多个辅助节点及环境节点影响：

- `onPreWork` 数值倍率相乘；来源贡献独立核验成本，不足时只跳过该来源整份贡献。
- `onPostWork` 使用稳定链式顺序，确保配方输出策略组合结果可复现。
- 配方模式、执行合同和端口冲突按 `onRecipes` 的模式/冲突组规则处理，不通过偶然的回调先后顺序解决冲突。
- 每个来源以稳定 ID 标识，至少包含来源类型、来源节点 ID 和模块类型 ID。分支合同还需包含分支 ID。
- 同一个模块实例不得因节点缓存合并而被重复应用；安装计数范围由注册类型声明，核心统一计算。
- 模块不能读取其他辅助节点的内部库存；可以读取公开的模块计数与冻结状态。资源成本只从该成本声明绑定的来源库存扣除。

附属回调应视为无状态策略。每次执行产生的数据必须通过 `RecipeContribution`、`WorkContribution`、输出列表或核心定义的 checkpoint 数据承载，不能放进模块实现对象的可变字段。

## 6. 范围渲染伴随 API

范围显示独立于服务端机器生命周期回调，以可选客户端接口挂接到支持范围的模块类型。范围几何和目标筛选继续复用 common API；绘制继续复用 `CloudworksAreaRenderer`。该设计不新建一套渲染后端。

建议客户端伴随接口：

```java
@SideOnly(Side.CLIENT)
public interface IAreaRenderModule<MODULE extends ICustomMachineModule<MODULE>> {
    AreaRenderDescriptor getAreaRender(
            IMachineModule<MODULE> module, AreaRenderContext context);
}

public final class AreaRenderDescriptor {
    List<CloudworksAreaGeometry.Rect> areas();
    double targetThreshold();
    MergeMode mergeMode(); // UNION 或 INDEPENDENT
    int fillArgb();
    int borderArgb();
}
```

- `UNION` 使用 `CloudworksAreaRenderer.compile(...)` 合并覆盖区域并隐藏内部重叠边界，适用于具有整体区域语义的下界模拟节点。
- `INDEPENDENT` 使用 `CloudworksAreaRenderer.compileIndependent(...)` 分别绘制每个来源范围，适用于每个散热器范围都需要单独显示的辅助节点。
- 服务端使用 `CloudworksAreaGeometry.targets(...)` 和同一阈值判定目标；客户端 overlay 仅展示服务端投影或相同冻结候选数据，不能决定机器是否受影响。
- 客户端实现必须位于 client 包并做物理侧隔离，common API 和专用服务器加载路径不能引用 Minecraft client 类。

编辑器观察态只传输范围几何、样式、合并模式和必要的目标 ID，不传递模块回调对象。范围移动、节点重排或机器目标变化时重编译 overlay；绘制阶段复用缓存结果。

## 7. 示例

### 7.1 @128：时长与功率语义分开

```java
public final class Coil128 implements ICustomMachineModule<Coil128> {
    @Override
    public WorkContribution onPreWork(IMachineModule<Coil128> module, PreWorkContext context) {
        int n = module.getEffectiveInstalledCount();
        return WorkContribution.builder()
                .durationMultiplier((1D + 0.1D * n) / (1D + n))
                .energyPerTickMultiplier(1D + n)
                .build();
    }
}
```

例如安装 `n` 片时，时长倍率为 `(1 + 0.1n) / (1 + n)`、每 Tick 功率倍率为 `(1 + n)`，二者乘积为 `1 + 0.1n` 的每操作能耗倍率。具体曲线由升级规范和夹具逐项锁定。

### 7.2 @129 与 @130：概率提高后在输出阶段清理

@129 可在工作前提交副产概率倍率以及额外功率贡献：

```java
return WorkContribution.builder()
        .secondaryChanceMultiplier(chanceMultiplierFor(module.getEffectiveInstalledCount()))
        .energyPerTickMultiplier(1D + 0.1D * module.getEffectiveInstalledCount())
        .build();
```

@130 在工作结束阶段根据已判定的副产物和只读出口空间，移除符合其规则的 `chanceOutput`。具体倍率、分母下限和机器例外按升级规格定义，不由生命周期框架硬编码。

### 7.3 @273：配方变换与端口属于同一冻结贡献

```java
@Override
public RecipeContribution onRecipes(
        IMachineModule<StonePowderModule> module, RecipeFreezeContext context) {
    List<CloudworksRecipeSpec> derived = deriveApplicableRecipes(
            context.baseRecipes(), context.machine(), module.getEffectiveInstalledCount());
    return RecipeContribution.builder()
            .mode(RecipeMode.APPEND)
            .recipes(derived)
            .port(new MachineSlotLayout.Slot(
                    "petrotheum_input", PETROTHEUM_CODEC, INPUT, 10_000))
            .build();
}
```

模块只派生符合条件的配方并声明其所需输入端口。端口与配方作为同一次候选合同一起检查，避免配方存在但机器布局没有入口。

### 7.4 TE 散热器：辅助节点作为模块来源

每个散热器是独立 `AUXILIARY_NODE` 来源，范围筛选确定目标机器后，为每个目标建立模块句柄。其 `onPreWork` 读取该散热器自己的冷却流体快照，返回时长贡献和绑定自身库存的成本声明。多个散热器同时覆盖同一机器时分别计算、分别扣除成本，再将有效倍率合并。渲染声明使用 `INDEPENDENT`，不把多个范围 union。

### 7.5 条件式输出改写

D 模块在 `onPostWork` 收到本次输出列表后检查 E 是否安装：有 E 时把指定的铁输出变为泥土，否则变为金。判断依据来自 `PostWorkContext.upgradeCount(E_ID)`；策略不直接访问节点安装库存或修改已经提交的资源。

## 8. 删除与迁移范围

本设计生效后，CloudWorks 可以直接删除被生命周期合同取代的开发期 API。初步归并关系如下，实施时应以实际调用点复核最终删除清单：

| 现有入口/职责 | 新入口 |
| --- | --- |
| `CloudworksUpgradeType.custom(...)` 数值回调 | 模块注册 + `onPreWork` |
| `CloudworksUpgradeType.customForMachine(...)` | `IMachineModule` 机器身份 + `onPreWork` |
| `RecipeContribution` 的附属配方变换、端口及执行合同声明 | `onRecipes` 返回的 `RecipeContribution` |
| `withCompletionStrategy(...)` / `CloudworksNodeCompletionStrategy` | `onPostWork` |
| 辅助节点 `EffectResolver` 直接返回时间/能量/成本倍率 | 辅助来源实现同一模块合同，使用 `onPreWork` |
| 分散的环境时长/工作倍率处理 | 环境节点作为模块来源，使用 `onPreWork` |

以下基础设施不因统一生命周期而自动删除：节点家族及蓝图事务、端口布局/codec、配方数据结构与编辑器、资源分配/escrow/提交内核、`CloudworksAreaGeometry`、客户端 `CloudworksAreaRenderer`。它们是模块合同的底层服务或可复用 API，不是重复的生命周期回调。

删除旧接口时不保留 source compatibility 适配器。所有附属、CloudWorks 内部模块、示例、测试、蓝图/节点合同编译路径和文档需在同一开发阶段迁移完成。已有模块数量、节点资产与 checkpoint 的数据格式是否需要迁移，属于独立的存档协议决策；本 API 设计不暗示自动兼容旧数据。

## 9. 线程、异常和数据合同

- `onAdded` / `onRemoved` / `onRecipes`：在服务端拥有线程的候选事务或冻结阶段调用；只允许读取注册数据与不可变候选快照。
- `onRecipeCandidate` / `onPreWork` / `onPostWork`：可在 Runtime worker 调用；必须线程安全、确定且无外部副作用。
- `onWorkStarted` / `onWorkCommitted` / `onWorkCancelled`：作为 Runtime checkpoint 中的状态事件，在服务端接受对应 checkpoint 后派发；按稳定操作 ID 去重，不在 worker 内执行外部副作用。
- 所有回调都禁止 World、TileEntity、玩家、原始库存、共享可变静态状态、网络访问和直接随机数。
- 核心在调用边界校验有限数值、非负数量、端口/codec 有效性和输出容量；所有输入输出对象防御性复制。
- 生命周期对象不得依赖回调先后顺序维护内部状态；需要持久化的操作状态由核心生成并写入 checkpoint。
- 回调抛异常或返回非法合同时，候选安装/机器合同不得部分发布；运行阶段不得部分扣资源或部分提交输出。错误应关联模块 ID、来源节点 ID、机器/配方身份并可诊断。
- `onWorkCancelled` 只用于显式取消已开工操作；运行暂停、断电和等待输出不能误报为取消。
- 不定义 `onWorkTick`；实际推进 Tick 的持续成本由核心使用已冻结的 `WorkContribution` 声明结算。
- 客户端范围回调只负责构建显示数据，不允许访问服务端模块合同对象或影响运行状态。

## 10. 验收计划

### API 与事务

- 泛型类型注册、模块句柄读取、机器升级/辅助节点/环境节点三种来源可编译并可注册。
- 首次安装、堆叠增加、部分卸载、最后卸载分别触发正确的变化回调；拒绝事务不更改资产、端口、配方或运行合同。
- 读档重建合同不重放 `onAdded` / `onRemoved`。
- 仅从提交后的完整模块集合冻结配方，候选编译失败时保留原合同。
- `onRecipeCandidate` 的拒绝和优先级排序稳定；相同输入在 worker 重试后保持相同选择。

### 配方与端口

- `REPLACE`、`APPEND`、`PARALLEL` 和冲突组按既有语义工作。
- 相同端口可合并，不同属性的同名端口拒绝候选提交。
- 机器身份 NBT、有效模块数或模块来源变化会区分对应冻结合同；Runtime worker 不会调用 `onRecipes`。

### 工作前与成本

- @128 的安装数曲线逐项符合规格，验证时长轴、功率轴和每操作能耗。
- @129 概率倍率只作用于副产概率，不误改主产数量。
- 多个 TE 散热器覆盖同一目标时分别应用；各自成本不足时只跳过对应来源贡献。
- 资源成本原子结算、分数余量恢复、输出阻塞等待和 checkpoint 重建都不造成重复扣除。
- Worker 回调输入相同则输出相同，且不接触 World/TileEntity/玩家/库存。

### 工作结束与渲染

- @130 在概率判定后、最终提交前执行；容量不足时按能力声明清理概率副产，不丢主产或重复执行策略。
- 多个后处理模块按固定优先级和稳定 ID 排序，checkpoint 后输出不重复转换。
- D/E 条件输出变换覆盖已安装/未安装 E 两态。
- 未供能的临时计划不触发 `onWorkStarted`；成功开工、输出提交和显式取消事件在 checkpoint 重试时不重复派发。
- 范围目标判定在服务端与 `CloudworksAreaGeometry` 夹具一致；下界范围使用 union，TE 散热器使用 independent overlay。
- Dedicated Server 启动不加载 client 渲染类。

## 11. 实施阶段建议

1. 新增模块类型、泛型模块行为接口、只读机器模块句柄和注册/挂载来源描述。
2. 接入候选事务中的 `onAdded` / `onRemoved` 与统一 `onRecipes` 冻结编译。
3. 接入 `onRecipeCandidate` 的纯函数准入/优先级判定。
4. 接入 `onPreWork` 贡献汇总、来源成本原子结算和 checkpoint 状态。
5. 将现完成输出策略迁入 `onPostWork`，支持多个策略的稳定链式处理。
6. 为工作开始、提交、取消增加 checkpoint 事件记录与服务端去重派发。
7. 为范围模块添加 client-only 描述接口，继续复用现有几何和 renderer。
8. 迁移 CloudWorks 内部升级、机制、辅助节点和环境节点调用方，删除旧开发期入口，并更新测试、示例和开发文档。

每个阶段都要以完整合同夹具验证后再删除对应的旧实现。阶段间不提供兼容桥；变更作为同一开发期 API 迁移的一部分落地。

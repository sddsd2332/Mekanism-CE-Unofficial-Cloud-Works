# 机器模块 API

更新：2026-10-20。本页描述破坏性迁移后的公共入口；旧 API 不提供兼容桥。

## 注册与宿主句柄

```java
final class Coil implements ICustomMachineModule<Coil> {
    @Override
    public WorkContribution onPreWork(IMachineModule<Coil> module, PreWorkContext context) {
        int n = module.getEffectiveInstalledCount();
        return WorkContribution.builder()
                .durationMultiplier((1D + .1D * n) / (1D + n))
                .energyPerTickMultiplier(1D + n).build();
    }
}

MachineModuleType<Coil> type = MachineModuleType.custom(
        new ResourceLocation("addon", "coil"), 8, "upgrade.addon.coil", new Coil())
        .withItem(upgradeItem);
CloudworksAPI.registerMachineModule(type);
CloudworksAPI.acceptMachineUpgrade(machineItem, type, 8);
```

声明应在 loadComplete 排空前提交。原生速度/能量保持各自的资产存储和 `MachineUpgradePolicy` 公式；自定义行为全部通过模块生命周期提供。

`IMachineModule<MODULE>` 含模块类型/实现、实际数量、有效安装数量、机器物品（防御性复制）、合并机器数、来源家族、来源节点 ID、目标节点 ID、产线 ID、分支 ID、配方 ID、全部节点升级数、只读环境和统一的 `MachineContextSnapshot`。注册期原型尚无实际节点/产线 ID；运行期句柄具有明确来源。源家族为 `UPGRADE`、`AUXILIARY_NODE`、`ENVIRONMENT_NODE` 和 `MACHINE_ENVIRONMENT`。

`getMachineContext()` 返回目标机器的不可变相关节点投影：

- `environment()`：海洋、森林、雨、雷暴、高度和熔岩等环境结果；
- `auxiliaryNodes()`：当前生效的辅助节点、模块 ID、来源节点和资源可用标志；
- `inputOutputNodes()`：与目标机器连通的输入输出节点、方向、原型和 codec；
- `machineNodes()`：同一产线中的其他机器节点；
- `links()`：连接目标机器的连线、端口、启用状态和比例。

该快照不暴露 World、TileEntity、玩家、原始库存或 Runtime 可变对象。模块可以在所有生命周期回调中通过句柄读取它；读取其他节点只能得到冻结后的声明数据，不能直接调用其他节点。

机器自有环境行为可通过 `CloudworksAPI.registerMachineEnvironmentBehavior(machine, moduleType)` 注册。该行为没有上传物品，也不占用玩家升级槽；核心会自动绑定它，并以 `MACHINE_ENVIRONMENT` 来源执行。TE 捕鱼器和水生成器使用此入口读取环境快照。

## 九个钩子

| 钩子 | 执行边界 | 返回/作用 |
| --- | --- | --- |
| `onAdded` | 安装候选事务增加数量 | 纯候选检查/通知；失败可拒绝事务 |
| `onRemoved` | 安装候选事务减少数量 | 包含前后数、首次/最后一件标志 |
| `onRecipes` | 配置合同冻结；禁止 worker 调用 | 返回不可变 `RecipeContribution` |
| `onRecipeCandidate` | 具体操作的候选配方筛选 | 接受/拒绝、整数优先级 |
| `onPreWork` | 原料/成本最终预留之前 | 返回 `WorkContribution` |
| `onPostWork` | 操作完成，输出提交前 | 链式返回新的 `List<MachineOutput>` |
| `onWorkStarted` | 能量结算成功并确认开工 | checkpoint 接受后只读通知 |
| `onWorkCommitted` | 最终输出原子提交成功 | checkpoint 接受后只读通知 |
| `onWorkCancelled` | 已开工操作被明确取消 | 状态事务接受后只读通知 |

不提供 `onWorkTick`。暂停、缺电、等待输出不属于取消；未开工的临时计划被撤销，不触发开工/取消通知。运行计算回调可被候选试算重试，必须同输入同结果，不能借调用次数维护状态。三个工作状态通知通过提交边界的事件队列，在 owner 锁外派发；事件重复发布时同一事件只派发一次。接收方应使用“产线＋目标节点＋分支＋来源节点＋模块 ID＋operationId＋事件种类”作为外部审计的去重键，不把工作通知当作资源变更事务。

生命周期接口不暴露 World、TileEntity、玩家或可写库存。随机输出由核心产生，模块不能使用外部随机数、第三方网络或共享可变状态。读档不重放安装/卸载副作用。配置回调异常拒绝候选合同，运行回调异常不发布部分资源结果；通知异常会记录模块身份，不倒回已经完成的资源提交。

## 配方、端口与分支

`onRecipes` 获得基础 `CloudworksRecipeSpec` 列表、机器注册视图和基础端口，并从实际安装数/完整升级集合返回数据：

```java
return RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE,
        derivedRecipes, extraPorts);
```

`perNode` 的激活数量与安装容量不乘合并机器数；`perMachine` 按每台机器配齐。`NONE` 可仅声明端口/机制参数；`REPLACE` 替换主配方，`APPEND` 优先尝试派生集后回退基础集，`PARALLEL` 建立独立执行分支。`REPLACE` 默认属于 `cloudworks:primary` 互斥组。

冻结变体按机器身份、配方入口、安装数量和模块集合区分；Runtime 冷编译只读预冻结结果，缺少变体时拒绝编译，不回退到 worker 执行配方回调。类型声明使用注册原型；不要在 `onRecipes` 根据尚不存在的实际产线/节点 ID 制造副作用。

同名端口仅在角色、codec、容量和多连线属性完全一致时合并。额外端口最多 64 个。多个配方模块分别从同一基础集合派生，不能靠回调顺序修改对方列表。分支共用物理节点缓存，但有独立预算、在途操作、进度、随机序列、发电余量和热力状态。

`RecipeContribution` 保留发电/热力的基础合同能力：`withGenerator/withoutGenerator`、`withGenerationTransformer`、`withThermal/withoutThermal`、`withProfile`。这些是不可变执行合同声明，发电变换仍在服务器快照边界执行。

工作阶段的候选、工作前、输出后处理及开工/提交/取消通知，当前接入持有在途操作的加工配方路径。直接燃料发电、热力和衰变继续使用各自的数值内核，不经过这组工作阶段钩子；自然风力、日照等连续产能也不制造虚拟配方操作。模块的注册、安装变化、合同冻结及环境投影与上述执行家族共用。

移除仍被连线使用、含缓存或有在途承诺的结构端口会拒绝；改变仍持有超出环境基线热量或预付燃料的热力合同也会拒绝。蓝图待补仍只保存需求和服务器生成的端口预览，不认领未安装资产。

## 运行贡献与成本

`WorkContribution` 分开声明时长、每 Tick 功率、概率副产倍率和成本。来自不同升级/辅助节点的有效倍率相乘；概率在核心裁剪到合法范围。时长与功率是不同轴，TE @128 的两轴乘积为每操作能耗倍率。

能量容量新增绝对加法 `capacityAddition(joules)`（每台机器），在倍率后统一相加，仍需 `affectsCapacity()`，不能附带条件耗材。原生混装升级槽通过 `installationGroup/installationSlotsPerMachine/installationItemsPerSlot` 声明；IC2 超频/储能共用此校验。更多实例见 [Mek/IC2 扩展](CLOUDWORKS_MEK_IC2_EXPANSION_20261025.zh-CN.md)。

资源成本支持 `PER_OPERATION` 和 `PER_BASE_MACHINE_TICK`：后者按基础时长和目标机器数收费，不随该贡献自身的加速变化。每份贡献的资源先按实际来源库存聚合、与配方输入共同检查；付不起时整份可选贡献回落。来源必须明确，不能从一个任意同 codec 的资源替扣。

成本在候选计划中预留，正式开工确认扣除，缺电撤销计划时释放预留。小数成本累计到来源节点的 checkpoint；整数量扣除和小数余量同时跟随已确认操作更新，重启不清零。运行中不重新调用附属 Tick 钩子。

普通加工可同时持有多个独立批次，`PreWorkContext` 的操作份数为当前候选批次的实际份数，并非该节点所有通道的总数。贡献可能在批量收缩时重新求值，仍须保持纯函数。模块专用消耗口若不出现在配方输入中，应在 Java/CRT 端口布局显式设置 `recipeFilteredInput(port, false)`；核心不能从运行期成本回调反推完整的入料白名单。

容量属于配置属性。模块若需修改机器能量容量，应声明 `affectsCapacity() == true`；核心以空配方 ID、操作数 `0` 调用 `onPreWork`，只取 `capacityMultiplier()` 重算产线容量。容量贡献不得依赖消耗资源后才能成立，也不能依据某一个在途配方任意扩缩库存。

候选准入中任一模块拒绝，则该配方被跳过；优先级贡献以整数求和，高者先尝试，同级保留冻结列表顺序。候选回调不能读取动态库存或自行分配原料。

## 完成输出与状态通知

`onPostWork` 按 `postWorkPriority()`（小值先执行）、模块 ID、来源节点 ID 依次执行；前一个结果作为后一个输入。`PostWorkContext` 提供已存输出、资源容量、共享 codec 剩余空间、环境和安装数。`MachineOutput` 保留概率来源标记和端口。

可能新增的输出身份须在 `outputResources()` 声明；核心在派发 worker 前加入资源字典。最终输出必须匹配有效输出口的方向与 codec。只有完整变换及容量检查成功后才提交；等待容量时保存已变换结果，恢复后不再变换第二次。

`discardsChanceOnOverflow()` 明确声明允许丢弃概率副产物，准入阶段可放宽其最坏占用；主产物继续按容量检查。

工作开始、提交和取消通知携带持久化的 `operationId`，开工与提交的 ID 一致。已开工操作删除时由核心释放输入预留，通知自身不能扣资源或返还资源。通知不会在 Runtime worker 中直接调用外部逻辑。

各批次分别保存承诺 UUID、生命周期 ID、产出序列、进度和后处理结果。增量 checkpoint 与恢复不能更换承诺身份、重掷随机输出或重复后处理；机制分支的额外批次取消通知保留原分支上下文。额外加工批次不占用机制分支的类型额度。详见 [通用加工与存档规则](CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md)。

## 辅助节点、环境与范围

`CloudworksAuxiliaryNodeType.area/linked/lineWide` 现在接收 `MachineModuleType<?>`，不再接受独立效果解析器。模块必须通过 `allowedSources(AUXILIARY_NODE)` 允许该来源。每个散热器有自己的库存、来源 ID 和成本余量，可分别覆盖并加速同一机器；缺冷却液只停用该来源贡献。

环境声明使用 `MachineModuleType.withEnvironment(ModuleEnvironment)`：环境旗标、每件高度贡献和每件熔岩模拟量在生成快照前投影。附属特殊节点通过 `CloudworksSpecialNodeType.source(...).withMachineModule(module)` 显式绑定模块，模块必须允许 `ENVIRONMENT_NODE` 来源；特殊节点 ID 与模块 ID 可以不同。该节点不要求资源选择，放置额度对应同名 cloud-state 效果等级。内置海洋/森林/雨/雷暴/下界特殊节点均绑定模块来源；同类型环境来源不叠加。发电和热力继续使用已有数值内核。

编辑器保留“机器节点 / 输入输出 / 环境节点 / 辅助节点”四个分类。无限资源节点位于“输入输出”，并参与输入方向筛选；“环境节点”只显示提供环境模块的节点，独立于输入输出方向筛选。

`withArea(ModuleArea)` 统一范围大小、超过阈值的面积判定、合并模式和默认颜色。`UNION` 用于下界等覆盖框，`INDEPENDENT` 用于 TE 散热器；两者分别调用已有 `CloudworksAreaRenderer.compile` / `compileIndependent`。客户端可在物理 client 生命周期用 `CloudworksModuleAreas.register(moduleId, IAreaRenderModule)` 注册伴随显示提供者；common 类型不引用 client 类。实际判定与客户端使用同一坐标中心、阈值和候选资格，显示提供者不能决定服务端效果。

机器相关环境、辅助节点、输入输出节点和其他机器节点统一通过 `MachineContextSnapshot` 暴露。环境行为模块应读取该快照并返回普通 `onPreWork`/`onRecipeCandidate` 贡献，不应让环境节点直接访问机器或让机器扫描产线。

## 迁移清单

以下开发期入口已移除：`CloudworksUpgradeType`、`CloudworksUpgradeRegistry`、`customForMachine`、`withMechanism`、`CloudworksMechanismDefinition`、`CloudworksNodeCompletionStrategy`、`CloudworksAuxiliaryEffect`、`CloudworksAuxiliaryContext`、`EffectResolver`。

原生 `MachineUpgradePolicy` / `MachineUpgradeEffect`、端口布局、recipe editor、资源 codec、几何/合并渲染 API、蓝图与资源提交内核保留为底层服务。新模块入口为 `MachineModuleType`、`ICustomMachineModule`、`IMachineModule` 与 `CloudworksAPI.registerMachineModule`。

示例：`examples/api/CloudworksFluidMechanismExample.java`、`CloudworksGenerationMechanismExample.java`。测试覆盖 API、TE 曲线与模式、候选排序、实际安装数量配方变体、成本失败回滚/小数恢复、输出链和提交通知。

## 迁移验证（2026-10-20）

- `compileMachineModuleExamples test build` 通过：165 个测试类、943 项测试，0 失败、0 错误、0 跳过；两份公共 API 示例均可编译。
- TE @130 回归覆盖不同物品共用输出容量：先保留全部主产物，再按剩余 codec 容量裁剪副产物；离心机保留物品副产物的原有例外。
- 最终 jar 已确认包含新模块与客户端范围 API，且不再包含六个被删除的旧公共类型及注册表；生产源码、测试及示例没有旧入口引用。
- 构建日志：`build/module-migration-verification.log`；产物：`build/libs/MekanismCeU-CloudWorks-1.0.0.jar`。
- 尚未执行游戏内 GUI 或真实服务器冒烟验证。

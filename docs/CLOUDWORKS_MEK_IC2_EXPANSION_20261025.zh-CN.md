# Mek / IC2 机器与节点升级扩展

更新：2026-10-25。以下功能共用现有注册、模块、资源路由、能量结算与 checkpoint；不修改 Mek、MoreMachine 或 IC2 本体。

## 机器入口

| 机器 | 输入/选择 | 执行合同 |
| --- | --- | --- |
| Mek 电动泵 | 需要匹配配方的环境源覆盖，无资源输入 | 海洋产水/FILTER重水，下界产熔岩；CRT可添加其他维度/群系流体 |
| MoreMachine 四档电动泵 | 同上；等级 p=3/5/7/9 | 默认水/重水保留原生等级关系，熔岩每次1000mB不乘等级；每条CRT规则明确倍率，基础功率/储能按 p 放大一次 |
| 聚变控制器 | D-T 或氘/氚；可选水冷模式、手动注入率 | `cloudworks:fusion_dry` / `cloudworks:fusion_water`，复用 CRT 聚变合同与热力状态；每节点设置 0 到服务器上限的偶数注入率，2 仅为新节点默认值 |
| SPS 单方块身份及 CRT SPS | 钋气体、可用能量 | 按能量累计加工；每 1mB 钋需 100 万 J，累计 1000mB 产生 1mB 反物质，不受云端产出倍率影响 |
| 生物质发电机 | BioFuel 物品或生物乙醇流体 | 一物品预付 200mB 等价燃料；每台每 tick 消耗 1mB，产生原生 `bioGeneration` J；两来源共用速率额度 |
| 矿物词典转换器 | 节点手动规则 | 已启用具体矿辞名称 → 指定合法候选物品，1:1、不缩放，默认不配置规则就不转换 |

泵按环境规则模拟稳定流体源，不读取世界流体块；下界熔岩规则不依赖 FILTER。生物质入口接受物品本身或流体资源，不负责抽取装有液体的物品容器。SPS 和生物燃料的预付状态保存到 `processCredits`；清空或回收节点时把整数量的已付资源还回输入回收链，不能重新产物或重复发电。SPS 不对并行线程/固定周期制造整数配方门槛；CRT `sps(..., ticks, capacity)` 保留参数兼容，ticks 不再限制按供能加工的周期。

### 泵的环境覆盖

Mek 泵和四档等级泵统一按物理节点检查：必须被匹配规则的已启用、已完成放置的环境源覆盖，沿用 `CloudworksNodeEnvironment.covered` 的范围与覆盖阈值。默认海洋水/FILTER重水、下界熔岩，其他源不自动产水。通用 Java/CRT 规则、优先级、等级关系和完整示例见 [泵环境配方](CLOUDWORKS_PUMP_ENVIRONMENT_RULES.zh-CN.md)。

多个源重叠按配方优先级选择一种产物，共享原有并行额度。失去本批次所需的轴覆盖后暂停且不收费，显示 `WAITING_ENVIRONMENT`；保存进度、承诺 ID、产物及原环境条件，不能用下界完成已开工的海洋水批次。覆盖在节点快照重建时冻结，worker 不查询真实世界或逐 tick 扫描源。

### 聚变手动注入率

在产线编辑状态打开聚变控制器详情，点击“聚变注入率设置”，输入偶数并应用；完成产线编辑提交后生效。每个节点独立设置，合并在同一节点的机器共享该注入率。范围是 `0..reactorGeneratorInjectionRate`（服务器配置上限向下取偶数），非法数值由客户端提示并由服务器复核，不悄悄改成 2。

注入率属于节点配置 `fusionInjectionRate`，不加入机器物品身份，随编辑草稿、撤销/重做、复制、存档和蓝图传递。不同注入率/冷却模式使用各自冻结配方、输入白名单、热力速率及冷却缓存容量，避免同机型串缓存。修改注入率保留已有等离子/外壳热量与库存，`0` 暂停新的注入和点火，剩余热量继续走现有导热/冷却/发电结算。

该节点输入框针对内置聚变控制器；CRT 的 `Multiblocks.fusion` 继续使用其注册时明确指定的注入率，二者共用底层聚变配方/热力构造。

## 手动词典规则

在产线编辑状态打开词典转换器详情，进入“矿辞转换规则”。输入具体矿辞名称，用左右按钮选择候选目标，点击“添加 / 更新规则”。上方规则列表支持选择、独立启停、删除和分页。一次节点可配置多个矿辞类别，同一类别只保留一个目标；合并机器共享这份节点规则。

合法名称沿用本体 `TileEntityOredictionificator.possibleFilters`（来源为 `validOredictionificatorFilters`）。服务器复核名称和候选目标，不能通过客户端绕过。原生输入多 tag 的首个合法类别优先级保留。只处理确定 metadata、无物品状态 NBT 的候选，拒绝以模糊/有状态物品作为输出，避免复制容器内容或剥离状态。

规则随节点存档、编辑草稿、复制、撤销、蓝图一起保存；目标保存为物品身份，不保存矿辞候选数组下标。目标模组缺失时该规则不执行，其他规则仍可使用；不会自动改选另一模组物品。

每组节点规则独立冻结配方、入料白名单和产物路由；两个相同机器身份可以配置不同输出，不互串缓存。编辑规则只影响下次开工，已有承诺按旧输出完成。已有不匹配新规则的原料不会被删除。首版不设置 CONFIGURATION 实物样板口。

## 节点模块

| 模块 ID | 原生物品/范围 | 贡献 |
| --- | --- | --- |
| `cloudworks:mek_filter` | FILTER；Mek/MoreMachine 泵 | 每台 1；以 PER_MACHINE 机制替换水配方为重水，不接管升级轴 |
| `cloudworks:mek_gas` | GAS；明确支持该升级的高级化学机、相关工厂、种植机及对应 MoreMachine 家族 | 辅助气体/种植流体用量，含速度交互；不扩大缓存、不直接加速、不接管升级轴 |
| `cloudworks:mek_thread` | MultiblockMachine THREAD；大型加工/发电机及 MoreMachine 虚空矿物发生器 | 大型机并行和储能按原生放大；矿物发生器只放大一次择一矿物的产量，不放大功率/周期/储能；固定储罐不放大 |
| `ic2:overclocker` | IC2 超频；已支持且原生可升级的加工机 | 默认每件时间 ×0.7、功率 ×1.6；接管 SPEED，亚 tick 时冻结多操作周期，同一周期的功率只收一次 |
| `ic2:energy_storage` | IC2 储能；同上支持 EnergyStorage 的机器 | 每件增加 10000 EU，经 EU→J 换算；加法在容量乘法之后，不接管 ENERGY 节能轴 |

GAS 的原生适用家族：净化仓、锇压缩机、化学压射室、化学溶解室、种植机，以及净化/压射工厂、MoreMachine 化学溶解室/种植工厂。没有原生辅助燃料语义的普通气体配方、燃气发电机、复制机不接受该模块。

GAS 的确定总量使用原生速度/GAS 曲线与每操作总量取整，至少保留原生的非零总预算。启用 `randomizedConsumption` 时使用本体 Poisson/StatUtils 数值分布、批次私有确定性种子冻结工作周期预算；原料一次预留，整个批次共享预算比例，不复刻真实机器每 tick 的全局随机流/中途到料。输入预留和随机预算随承诺保存，恢复不重掷。通用附属的 fractional secondary 入口另以小数预付余量保证长期数量守恒。

IC2 使用四个可堆叠升级槽，超频与储能合用。每类型件数按 64 件一堆占槽，两者混装不能超过四槽/台；合并节点按真实机器数归一效果。RandomComplement 存在时沿用 `CloudworksUpgradeCurves` 冻结的超频/储能参数。原生不支持升级的高炉、电解器和物质生成器不开放两模块。储能物品不作为产线 ENERGY 模块；仅装储能不会阻止产线轴正常生效。

输出容量按原机产物槽数量合并：热能离心机和洗矿机为 `3×64=192`，高炉为 `2×64=128`。
这使离心机原生铀富集配方的 `112+7` 件输出可完整保留并兑现。基础注册和超频机制冻结都会执行输出容量检查，
运行期沿用现有背压，不通过截断配方或忽略超量产物规避错误。
修复依据与回归记录见 [输出容量启动崩溃修复](../ai/GLM/CLOUDWORKS_OUTPUT_CAPACITY_CRASH_FIX_20261025.zh-CN.md)。

## Java 与 CRT

继续使用 `CloudworksAPI.registerMachine/acceptMachineUpgrade`、`MachineModuleType` 和 `RecipeContribution`，不会新增按机器族分叉的资源结算入口。

```java
CloudworksRecipeSpec.builder("addon:powered_conversion")
        .input(inputDescriptor, 1, "input")
        .unscaledOutput(outputDescriptor, 1, "output")
        .powerDriven(1_000_000D, 1000).build();
```

`powerDriven` 要求一个精确输入条目、一个输出条目，条目数量可以大于 1；无随机/模板/发电。输入配方数量、已付进度与目标换算份数分开保存。正的 `extraEnergyPerTick` 在该模式下是每执行单元供能上限，不是附加收费。一个机器可以按实际有料的连续配方开始；未兑现进度会锁定其原配方。

`operationsPerCycle(n)` 表示一个并行执行单元的一次周期可处理 n 份，功率按占用单元收取；与物理并行数不同。`prepaidFuel(resource, units)` 把首个物理输入条目预付成多个工作单位，后续输入按工作单位支付。可以生成能量或周期性资源（不可同时生成两者），可附带非消耗配置模板。已付额度及周期进度持久化；燃料/确定性守恒产物不受产出倍率影响。CRT 复用 `prepaidFuel(canonicalFuel, units, joulesPerUnit)`，单位能量 0 且有资源输出即为资源加工。细节及原生容量见 `CLOUDWORKS_NATIVE_PORT_ALIGNMENT_20261026.zh-CN.md`。

`WorkContribution.Builder.capacityAddition(joules)` 是每台机器的绝对储能加法。需声明 `affectsCapacity()`；不允许附带条件资源成本。`ICustomMachineModule.installationGroup/installationSlotsPerMachine/installationItemsPerSlot` 声明混装槽容量，复用统一校验。`CloudworksMachineAdapter.thermalPlan()` 可为明确选中的配方模式提供不可变热力合同，自动/标准模式仍使用机器定义热力合同。

CRT 新增方法：

```text
Recipes.create(id, ticks).operationsPerCycle(count)
Recipes.create(id, ticks).powerDriven(joulesPerInput, inputsPerOutput)
Recipes.create(id, ticks).prepaidFuel(canonicalFuel, units, joulesPerUnit)
Recipes.create(id, ticks).secondaryFuelInputs(gasInputs, fluidInputs)
```

方法均在注册阶段构造不可变合同；`secondaryFuelInputs` 只标注已经声明的输入，不自动添加资源，不用于把所有气体错误视为可节约辅料。手动词典规则是玩家节点配置，不是全服机器身份注册；本版由节点 GUI 设置，不通过启动 CRT 自动覆盖玩家规则。

## 验证与边界

`CloudworksMekExpansionRuntimeTest` 覆盖低功率 SPS/不足整份输入、预付燃料/重载回收、每周期多操作耗能、合并 THREAD、GAS 确定/随机预算、手动词典目标隔离及 worker 冻结、蓝图编码、IC2 混装/容量/超频和水/重水替换。原有注册覆盖、热力和通用并行回归继续保留。

GUI 尚需游戏内目检；纯 JVM/编译回归不能替代实际玩家操作界面验证。真实多方块结构、世界抽取、损伤/辐射/爆炸及物流网络不由此扩展模拟。

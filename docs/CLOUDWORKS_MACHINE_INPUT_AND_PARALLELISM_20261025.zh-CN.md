# 通用机器输入过滤与并行合同

更新：2026-10-25。适用于 CloudWorks 通用机器节点，不限 Mek 工厂；Mek、TE、IC2、MoreMachine、MultiblockMachine 以及附属/CRT 注册的普通加工合同使用同一 Runtime 入口。真实机器本体和玩家现有缓存不由此接口修改。

## 1. 入料是否有效

输入端口新增独立布尔属性 `recipeFilteredInput`。普通 `INPUT` 默认开启。路由在方向、codec、容量检查之外，使用所有者线程预先冻结的配方输入 matcher 判断资源能否进入。worker 不调用真实 TileEntity，也不运行脚本回调探测物品。

Java：

```java
MachineSlotLayout slots = new MachineSlotLayout()
        .add("item_input", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
        .recipeFilteredInput("item_input", true)
        .add("coolant", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.INPUT, 64)
        .recipeFilteredInput("coolant", false)
        .add("item_output", QIOResourceCodecs.ITEM_STACK_ID, MachineSlotLayout.PortRole.OUTPUT, 64);
```

CRT：

```zenscript
val slots = Slots.create()
    .input("item_input", "mekanism:item_stack", 64)
    .recipeFilteredInput("item_input", true)
    .input("coolant", "mekanism:item_stack", 64)
    .recipeFilteredInput("coolant", false)
    .output("item_output", "mekanism:item_stack", 64);
```

- `false` 只关闭配方过滤；方向、codec 和容量仍有效。`multipleConnections` 是另一个独立开关。
- 已标端口的输入只加入该口白名单；未标端口的输入也参与匹配。同 codec 存在未被显式输入占用的口时，未标主料不扩散到显式辅料口。
- 过滤是“能作为配方输入的资源类型”，不要求其余原料已经到齐，不要求当前能量、日照、维度等运行条件满足，也不按最终模块候选优先级逐 tick 重建白名单。
- 普通严格输入没有可用配方时不接受任意同 codec 资源。配置模板按已声明的配置 matcher 过滤。
- 仅被模块 `onPreWork` 成本使用的耗材无法从配方推导。该专用端口须显式关闭过滤；任意附属成本资源不会自动获得白名单。
- 热力原生燃料按冻结的原生燃料集合检查；热力发电机流体口只接受熔岩。显式关闭过滤仍有效。其他热力口遵循各自的热力/配方合同，不套用普通加工的空配方拒绝规则。
- 布局冻结、机制端口合并、工厂容量派生和热力端口装饰保留属性；重复布局声明中该属性不同属于合同冲突。
- 已有无效输入不自动删除、不转换、不退款。产线中无法加工的铀矿、含流体矿等应另行过滤或分流；上游产物不能静默丢弃。

## 2. 并行是上限

普通加工基础容量由 `机器数量 × 声明并行 × workRate` 得出。Runtime 扣除在途批次占用后，在剩余容量内寻找可执行配方：

1. 根据所有输入的联合可用量计算可支持次数；已预留量不可再用。
2. 根据同一物理节点共享的产物缓存，计入所有机制分支及所有在途产物承诺，限制新批量。
3. 按可付加工功率缩小新批量；不能支付的计划不留下输入/耗材预留。
4. 允许同一节点的不同配方占用剩余容量。后到原料也可开新批次。

例如九路熔炼节点只有铁矿 3、金矿 1，且输出空间和能量足够时，可同时执行铁矿三份与金矿一份，不再等待九份相同矿物。两种配方耗时不同就分别完成；单路普通机器仍只能使用一份并行容量。

速度升级的快速周期折算可能使单批操作份数大于占用并行单位。Runtime 分别保存 `operations` 和 `parallelUnits`，不把升级折算误认为额外物理通道。模块可影响配方候选、时间、功率和副产概率，批量收缩时其纯函数贡献按实际份数重新求值。

普通加工基础功率按实际占用并行单位计入，配方附加功率按实际操作份数计入；模块功率贡献应用于该批次。空闲通道不按满负载收费。已有在途批次保留承诺，缺电时等待；当前仍沿用产线统一能源结算，不在缺电后拆分已有批次或让同线部分在途批次抢跑。

热力、直接燃料发电、连续环境发电和衰变保留专用数值合同；它们不制造普通加工进度批次，不经普通加工模块工作钩子。

## 3. 独立进度与持久化

同一时刻、同一配方、同一周期开始的多份操作可合成一条批次记录。不同开始时间/配方的批次分别计时，空闲通道不分配 Runtime 行。合并数千台机器不会按每个空闲通道复制节点。

每批保存输入预留、确定后的输出和概率输出、占用单位、进度、配方 ID、模块功率、承诺 UUID、生命周期 ID、产出随机序列及完成后处理标记。checkpoint 更新进度不替换承诺 UUID；恢复不重掷概率、不重复后处理，也不重复开工收费。

每个机制执行入口的第一批沿用其主 `operation`；额外批次写入节点 `processingBatches`。其键包含执行分支的稳定摘要和序列，独立于机制分支数量限制。所有批次共同参与输入预留校验；取消时统一释放原料，删除额外批次，不生成产物。模块取消事件保留原执行分支。

节点现有 GUI 进度以当前第一批为展示基础；本次不增加逐通道进度面板。

## 4. 回归入口

`CloudworksProcessingConcurrencyTest` 覆盖普通自定义机器的混合配方、合并数量上限、多输入短板、共享输出容量、低功率缩量、后到原料、增量 checkpoint 的身份稳定、超过 64 个错峰批次的概率承诺/重载/取消，以及布局过滤属性保留。

`CloudworksThermalRuntimeTest` 补充原生固体燃料、熔岩过滤及显式关闭过滤；热力、机制分支、模块与升级继续由既有回归验证。CRT 的真实 ZenScript 编译夹具编译公开布尔入口和中英文完整示例。单元/夹具通过不等于已在用户世界做过游戏内目检。

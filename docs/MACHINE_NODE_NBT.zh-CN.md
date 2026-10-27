# 机器节点数据结构(NBT 树)

对应类:`mekanism.cloudworks.common.data.MachineNodeData`。更新于 2026-10-25。
节点存于产线文件 `machines` 列表内,每项一个下述 Compound。

```text
机器节点 MachineNodeData (Compound, schema=7)
├─ 版本号 schema: Int                              当前写入 7
├─ 节点id nodeId: String                           UUID,画布格子的唯一身份
├─ 机器 machine: Compound                          机器物品 NBT(ItemStack 格式)
│   └─ id / Damage / count / tag                   冻结身份模板,用户状态 NBT 已剥离
├─ 机器数量 machineCount: Long                     这格堆几台;0 仅允许"待补"态
├─ 共享占用 pooledClaim: Boolean                   机器是否从 owner 机器缓存占用
│
├─ 待补蓝图 waitingBlueprint: String (UUID)        ┐ 可选,成对出现
├─ 待补数量 waitingCount: Long                     ┘ 蓝图"先立节点后补机器"
├─ 上传事务 transferId: String (UUID)              可选;哪次上传创建的(缺省=命令/旧档节点)
├─ 上传来源 sourceId: String (UUID)                可选;哪个上传源(端口/容器)供的货
│
├─ 横坐标 x: Int                                   画布位置
├─ 纵坐标 z: Int                                   画布位置
├─ 启用 active: Boolean                            节点启停开关
├─ 进度 progress: Int                              当前操作进度,0..totalTicks
├─ 总时长 totalTicks: Int                          ≥1;有在途操作时必须与其 duration 相等
├─ 状态 state: String                              OFF / WAITING_POWER / NO_RECIPE 等
├─ 产出序列 outputSequence: Long                   产出乘法器种子连续性(可复现随机)
│
├─ 配方类型 recipeType: String                     可选;多模式机锁定单模式,缺省=全部合并
├─ 词典规则 oreRules: List<Compound>                可选;ore名称/enabled/target物品身份，非实物库存
├─ 聚变注入率 fusionInjectionRate: Int             默认2;0..服务器上限的偶数，节点配置，不参与物品身份
├─ 预付资源 processCredits: Compound               可选;执行入口/配方ID → descriptor + units(Double)
│                                                  SPS 保存已处理输入及小数进度；生物燃料保存预付流体量
├─ 自定义升级 customUpgrades: Compound             可选;命名空间类型ID → Long计数(≥1),
│                                                  无固定类型数上限;安装数≤机器数×策略上限(超出部分
│                                                  冻结效果零贡献但存储保留);未知类型计数原样保留;
│                                                  效果曲线仅在所有者快照冻结期求值(负清单: worker 不跑附属代码)
├─ 热力状态 thermalState: Compound                 可选,仅热力机器
│   ├─ 热量 heat: Double
│   ├─ 容量 capacity: Double                       ≥1
│   ├─ 等离子 plasma: Double
│   ├─ 燃料刻 fuelTicks: Double
│   ├─ 信用 credit: Double
│   ├─ 已产出 produced: Double
│   ├─ 已消耗 consumed: Double
│   ├─ 损耗 loss: Double
│   └─ 燃烧中 burning: Boolean
│
├─ 输入缓冲 inputs: List<Compound>                 原料暂存
│   └─ 资源堆 ResourceStack (Compound, schema=1)
│       ├─ 资源描述符 descriptor: Compound         QIO 描述符(物品/流体/气体/浸润剂,自带编解码器标识)
│       ├─ 数量 amount: Long
│       └─ 已预留 reserved: Long                   被在途操作占用(可见不可再分配)
├─ 输出缓冲 outputs: List<Compound>                成品暂存
│   └─ (元素同上:资源堆)
├─ 配置模板 templates: List<Compound>              可选;配置样板(如复制机模板)
│   └─ (元素同上:资源堆;读入时 reserved 必须=0)
├─ 执行分支 executionBranches: Compound            可选;机制分支ID → 执行状态,不是加工通道列表
├─ 额外批次 processingBatches: Compound            可选;稳定分支摘要/序列键 → 执行状态
│   └─ progress / duration / state / outputSequence / operation
│                                                  每批独立计时;额外批次不占机制分支额度
├─ 发电余量 generationRemainders: List<Compound>   可选;资源与小数余量
├─ 模块成本 moduleCostRemainders: Compound          可选;来源/模块/资源键 → 小数余量
│
└─ 在途操作 operation: Compound                    可选;主执行入口的第一批快照
    在途操作 CloudworksOperation (Compound, schema=1)
    ├─ 操作id id: String                           UUID,操作流水号
    ├─ 生命周期 lifecycleId: Long                  通知去重身份;随 checkpoint 保留
    ├─ 批量数 operations: Int                      一次开工的操作份数
    ├─ 占用并行 parallelUnits: Int                 1..operations;快速周期折算后可能小于操作份数
    ├─ 批次序列 outputSequence: Long               可选;随机产物/批次键的稳定序列
    ├─ 时长 duration: Int                          必须等于节点 totalTicks
    ├─ 附加能耗 extraEnergyPerTick: Double         可选,≠0 才写;配方级附加功耗
    ├─ 模块功率 modulePower: Double                当前批次冻结的功率倍率
    ├─ 配方ID recipeId: String                     开工时冻结的配方身份
    ├─ 环境轴 requiredEnvironmentAxis: String       可选;本批次必需的覆盖轴，恢复不按新规则替换
    ├─ 后处理完成 completionPrepared: Boolean      true 时恢复后不再重复后处理
    ├─ 输入 inputs: List<Compound>                 开工时扣定的原料快照
    │   └─ (元素:资源堆)
    ├─ 输出 outputs: List<Compound>                完成时要放出的产物快照
    │   └─ (元素:资源堆)
    └─ 概率输出 chanceOutputs: List<Compound>       已确定的概率来源产物,恢复不重掷
```

---

## 不落盘的瞬态字段

节点没有真实世界维度：`dimensionId` 已删除，不再作为来源或诊断字段保留。
辅助节点也不保存 `dimension`。编辑、上传、草稿和蓝图都不采集玩家维度；
环境条件由覆盖节点的环境轴提供，运行中的批次只保存 `requiredEnvironmentAxis`。
当前草稿/蓝图结构版本为 13。详见 [环境语义与接口](CLOUDWORKS_ENVIRONMENT_SEMANTICS.zh-CN.md)。

标 `transient`,**永不进 NBT**,用时重建:

- 配方命中缓存 recipeHits — 每适配器"上次配中的配方"(注册表版本+库存快照双重校验;切配方类型即整表清空)
- 能力解析 capabilities — 按注册表版本缓存的档案/适配器/槽位/特殊/发电/热力解析结果

---

## 读入校验(poison-domain 严格模式)

任何一条不合法即拒读整个节点;坏数据只污染该 owner,磁盘原样保留:

1. progress 越界(0..totalTicks 之外)
2. totalTicks < 1
3. machineCount = 0 且非待补态 / machineCount > 0 且带 waitingBlueprint
4. 在途操作 duration ≠ 节点 totalTicks,或 progress 越过 operation.duration
5. 模板堆 reserved ≠ 0
6. 配方类型 id 不符格式([a-z0-9_.:-]{1,128})
7. 批次 parallelUnits 不在 1..operations、序列为非法负值、额外批次缺少 operation 或含热力/发电余量
8. 遗留键 `teAugments`(已移除的热力膨胀增强件)——例外:静默忽略,下次保存自动清除

旧的 `assignedSpeed`/`assignedEnergy` 已停止读取和写入；节点速度/能量使用 CUSTOM 模块并按轴决定是否覆盖产线贡献。`recipeFilteredInput` 属于注册布局，不是每个存档节点的可变 NBT 开关。通用加工与检查点规则见 [输入过滤与并行合同](CLOUDWORKS_MACHINE_INPUT_AND_PARALLELISM_20261025.zh-CN.md)。

Runtime 冷编译与增量投影还会统一核对主操作、机制分支和额外批次的输入需求总量，要求其与库存 `reserved` 精确一致；不匹配时拒绝发布。

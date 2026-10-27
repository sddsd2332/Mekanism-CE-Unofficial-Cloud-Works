# Runtime 第三轮优化：实现、回归与压力验收

日期：2026-10-26（Asia/Shanghai）。

本轮按用户“完整修复后再跑压测”的授权实现 P1/P2/P3，并补齐测量、真实内容编辑与 `r_real` 档位。打包产线、黑箱、运行时折叠不属于本轮；未修改 Minecraft/Mekanism 本体。

## 1. 结论与边界

- 回归：**200 个套件、1,168 项测试，失败/错误/跳过均为 0**。
- 默认 `build` 成功；发布 JAR 不含压力夹具或 spark。
- 最终矩阵前缀：`cw_phase3_20261026_b`。8 个可测档位功能、资源核账和保存重载通过。
- r3：实际每 owner 模拟速度从 6.870 提高到 **14.950 tick/s**；整轮事件 p99 从 115.619 降到 **11.824ms**；无新增操作的第二段 p99 从 114.897 降到 **5.899ms**。
- **r3 仍不是满速**：正式测量两段分别增加 2,122 / 899 owner-tick 欠账。低主线程延迟不能替代模拟吞吐结论。
- r4 只进行了本轮指定的一次 16G 重试：400 条线全部安装，首次运行阶段发生 GC 停滞，0 个有效测量 tick。容量守护停止进程；不是原生 OOM，不报告虚构的 TPS/p99，也未增加 heap 或再次重试。
- r1/r2 均满足 p50<30ms、p99<250ms 门槛，但部分整轮 p99 高于第二轮。本报告保留回升，不称“全面无回退”。
- 首次启用的 IDLE 样本混有冷启动，所有本轮 `idlePerformancePass` 均未达到 p99<30ms；这是未解决的观察项。

本轮只完成有证据支持的三项优化；没有承诺消除所有分配、达到所有规模满速，或解决 r4 常驻内存容量。

## 2. 备份与证据版本

修改前备份：

```text
backup/runtime-phase3-20261026-173241/before.zip
SHA-256:
3E0D28459E2365F7245BBE83A33B698657913AB5FFECD4BDBABAD661ED1BC2D6
```

备份包含 `src`、`docs`、`ai`、`build.gradle`、`gradle.properties`；修改前的 `gradle/scripts/full-scale-stress.gradle` 另存于同一备份目录。

工作区不是 Git 仓库。最终每个 run 的 `build-manifest.json` 与当前生产/夹具源码及资源核对：**611 个文件，0 项不同**。压测后只新增离线分析工具与验收文档，没有继续更改生产算法。

`cw_phase3_20261026_a_r10k_m10` 是保留的失败尝试：测到 266 tick 后，新增夹具误在 enabled 产线上调用编辑接口，生产门禁正确拒绝 `cloudworks.error.line_active`。修正夹具为明确停线、编辑、恢复后，以新前缀 b 从第一档重跑；没有覆盖失败结果，也没有为通过测试放宽生产门禁。

## 3. P1：不可变运行值发布，边界才生成 NBT

### 3.1 实现形态

- `CloudworksRuntimeCheckpoint` 保存 `CloudworksRuntimeLineSnapshot`，常态捕获和内存提交不再生成机器/通道投影 NBT。
- `CloudworksRuntimeObjectSnapshot` 按 256 项分页保存不可变值；未变化项及整页按引用共享，发生变化的页才复制。不保留前一个检查点引用，不形成无限历史链。
- `MachineNodeData.RuntimeProjection` 固定进度、时长、状态、输出序号、配方 promise、分支/批次、热状态、资源信用和余数；库存数量和 reserved 保存为独立值，只有库存版本变化才重建向量。
- 通道和辅助节点的库存使用已有 copy-on-write 库存页捕获，连线比例余数是独立数值数组。
- 仍存在 worker 私有 `ColdData` 和更新过程；**已发布快照不引用其可变行、节点或运行数组**。本轮是不可变运行值分页，不宣称所有对象都改成纯 primitive 数组。
- 每条快照只持有该条产线的冻结配置基线，不引用整个 owner 捕获对象。基线剥离进度、库存、promise 等动态子树，避免长期保留整批旧运行标签。
- `CloudworksResourceStack` 不再在构造时额外复制 descriptor payload；`value()` 仍通过不可变 descriptor 返回防御性副本，公开复制语义不变。

### 3.2 真实消费者边界

| 消费者 | 行为 |
| --- | --- |
| 常态内存提交、运行资格判断 | 只使用值快照及小元数据，不生成机器 NBT |
| GUI 请求捕获 | 主线程复制快照索引；完整图数据在既有 GUI worker 物化 |
| GUI 产线概览 | 只构造概览、数量和图标所需数据，不物化整条机器运行树 |
| 周期耐久保存、空闲 flush | 现有有界 IO 队列持有固定快照，按 FIFO 执行到该批次时物化 |
| `line(id)`、编辑、真实交易、显式全量 flush | 属于真实读取/交易边界，可物化当前已接受状态 |
| 回归用的 `checkpoint.lineTags()` | 按需输出新 NBT，生产提交路径不调用它 |

管理器只将已接受快照作为最新 Runtime 发布。读取边界生成的标签是派生缓存；下一次该行发布会清除缓存并标记需重建行模型，不构成另一份独立推进的 Runtime。公开图/行读取返回的可变对象不会反向改变已发布值。

“零 NBT”限定为常态机器/通道检查点捕获与非耐久提交；不包括冷配置捕获、小云账本边界标签、GUI、真实交易或落盘。没有通过停止内存进度发布、延长运行步长或跳过事件换性能。

### 3.3 持久化和输入顺序

- `CloudworksPersistenceQueue.submitDeferredIntent` 只接受闭合于不可变发布值的物化函数，不读取运行中的 Runtime 或管理器 live map。
- 行 generation 在主线程入队前确定；NBT 在有序 IO worker 内、写意图日志之前构造。
- 意图 schema、投影补丁、force、原子替换、故障域和回放顺序继续使用原路径。
- 后续 worker 继续推进、GUI 并发查询或新的外部输入，不会改变已排队批次对应的状态。
- 检查点记录外部输入合入水位；同一检查点因入队繁忙重试时不重复合入已有事件。已接受的检查点不能再次提交或重复派发事件。

## 4. P2：按实际 view 去重和资源键缓存

- 一次 `prime` 捕获用身份集合跳过重复的同一 `CloudworksFrozenRecipeView`。
- 不按 `MachineKey` 合并不同 view：配方模式、机制升级和安装数量派生出的 view 仍独立遍历。
- 每个 native-contract 代次缓存 view 对应的预注册资源列表；节点独有的库存、process credits 和发电计划仍分别处理。
- 序列化 NBT 键以 `QIOResourceDescriptor.toString()` 的冻结完整身份作缓存入口，而不是仅用物品种类或 codec 语义相等关系；不同持久化元数据不会被错误合并。
- 缓存属于现有 `Snapshot` 生命周期，注册表/配方等代次更换时重建；没有另加跨世界永久全局缓存。
- 原生世界、能力与 generator 冻结仍在主线程边界完成，没有搬到模拟 worker。

## 5. P3：布局冻结所有权与摘要失效

注册表原来已保存 `frozenCopy()`，`Slot` 为不可变字段，模式布局也冻结。本轮在这个所有权约束下缓存布局摘要：

- 正常注册、热端口替换、别名复制、测试隔离清空和事务回滚都使摘要失效。
- 回滚即使恢复旧公开版本，也显式清除摘要缓存。
- 修改调用者保留的 builder 不会影响注册表；修改已注册布局或返回列表的公开路径拒绝执行。
- 常态 `runtimeCurrent` 仍检查配置、会话、注册表、配方与升级设置凭据，只是不再每次扫描全部布局。

不承诺在完全取消内容观察后，仍能立即检测反射/Unsafe 对私有冻结字段的任意篡改。那不是公开 API 支持的变更路径；若未来要支持热修改，必须提供受控替换和失效通知，不能直接开放可变布局。

## 6. 回归与构建

最终命令与日志：

```text
gradlew cleanTest test -Pcw_full_scale=true fullScaleStressClasses --console=plain
build/qa/runtime-phase3-stable-regression.log

gradlew build --console=plain
build/qa/runtime-phase3-release.log

gradlew cleanTest build --console=plain
build/qa/runtime-phase3-final-release.log
```

结果：200 个套件、1,168 项测试，失败/错误/跳过均为 0；压力 source set 编译通过；默认发布构建成功。

新增/增强覆盖：

- 150 轮固定种子随机化：运行值物化结果与现行 `writeRuntimeDelta` 的 NBT 语义相等。
- 源节点继续变化后旧投影仍不变；库存 reserved、主 promise、独立分支、热状态、资源信用和余数保留。
- 不可变分页保留旧值、只替换变化引用。
- 捕获、内存提交、资格查询和 GUI source 捕获不产生机器 NBT；GUI 读取保持捕获修订。
- GUI/行模型被调用者修改后，已发布快照不变。
- 周期耐久路径确实物化，并在 `Cloudworks-IO` 线程发生；非耐久常态发布不物化。
- 输入事件重试不重复合入。
- 布局摘要反映正式注册，外部 builder 和返回列表无法绕过冻结。

此前仅选部分 suite 的一次定向运行因 Mekanism HeavyWater 未由其他测试初始化而在共享初始化阶段失败；完整套件运行正常。该日志保留为 `runtime-phase3-targeted.log`，没有为掩盖测试初始化顺序修改生产泵规则。

## 7. 压力负载与计时口径

最终顺序：

```text
r10k_m10
r10k_m10000
r1_off / r1_on
r2_off / r2_on
r3_off
r_real_16g
r4_retry16_16g（仅一次）
```

- 使用原生配方、真实 Runtime DAG、有限预置库存和正常端口/库存容量校验。
- spark 继续使用 `curse.maven:spark-361579:3542217`，只在 opt-in 压力 source set 加载。Windows 使用 spark Java sampler，`--interval 1 --thread *`。
- 常规档为 12G，r_real 与 r4 为 16G；正式测量 r3 为 300 tick，其余成功档为 400 tick。
- 第一段仍包含总进度 1/3、1/2、2/3 的三次暂停/恢复及其后重建；它们不是结构编辑。
- 第一段结束、运行排空后，三个不同 owner 分别停线、修改一个连线优先级、恢复。该真实内容编辑在计时外进行，随后核账并重载。
- 第二段经过重载和重新预热，不插入新的操作，单独报告连续运行指标。
- 计时范围为 Forge HIGHEST START 到 LOWEST END 入口；不是完整外循环 MSPT，也不是只计 CloudWorks。原版保存仍可能进入区间。
- 原始 JSON 的旧 `physicalMachines` 字段为兼容分析器保留，但含义是 `sum(node.machineCount)`，另有 `representedMachines` 及解释。未放置这些数量的物理 TileEntity，不是上传/下载方块数量。

`r_real` 为 10 owner，每人 10 条线、1,810 个节点、10,000 等效机器，5 worker、16G：4×32 长链节点，2×128 阻塞输出节点，2×1 合并节点（每个表示 4,096 台），2×712 长链节点。总数 18,100 节点 / 100,000 台。此档不包含真实玩家连接、渲染、开放 GUI 或整机多区服并发，不能外推宿主机 5–6 区服容量。

## 8. 最终结果

| 档位 | 节点 / 等效机器 | 周期落盘 | 整轮 p50 / p99 ms | 第二段 p99 ms | 每 owner 模拟 tick/s | 功能/重载 |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| r10k m10 | 400 / 10,000 | off | 0.682 / 1.560 | 1.174 | 20.002 | 通过 |
| r10k m10000 | 400 / 10,000 | off | 0.652 / 1.519 | 1.191 | 19.999 | 通过 |
| r1 off | 7,116 / 31,680 | off | 0.566 / 10.320 | 18.876 | 19.884 | 通过 |
| r1 on | 7,116 / 31,680 | on | 0.603 / 1.976 | 1.349 | 19.848 | 通过 |
| r2 off | 47,440 / 211,200 | off | 0.855 / 20.977 | 4.982 | 20.006 | 通过 |
| r2 on | 47,440 / 211,200 | on | 0.984 / 12.008 | 6.203 | 20.020 | 通过 |
| r3 off | 210,960 / 538,560 | off | 1.477 / 11.824 | 5.899 | 14.950 | 通过，未满速 |
| r_real | 18,100 / 100,000 | off | 0.611 / 1.359 | 1.051 | 19.996 | 通过 |
| r4 16G | 1,638,400 / 1,802,200 | off | 无有效测量 | 无有效测量 | 无有效测量 | 容量边界 |

略高于 20 的值仍按实际采样报告，含欠账追赶和计时边界效果，不代表持续超过游戏目标速度。

相较第二轮：

- r3 模拟吞吐约 2.18 倍；连续运行第二段 p99 达到 <50ms 的记录型目标，但模拟尚未满速。
- r3 第一段最大事件从 687.585ms 降到 322.427ms，**没有达到原草案 max<100ms 的预测**。
- r1 off 整轮 p99 从 6.546 升到 10.320ms；r2 off/on 分别从 11.016/9.848 升到 20.977/12.008ms。均在门槛内，不能称所有分位数无回退。
- r2 连续运行第二段则从 7.999/11.316 降到 4.982/6.203ms。整轮与分段样本是不同总体，不相互替代。
- 混合档原生矩阵保留未覆盖/未完成列表；本轮不能宣称每个注册原生配方都完成过。r3/r_real 各有 13 条已选配方未记录完成，具体列表保存在原结果；每 owner 有进展和资源核账不等于所有家族逐配方全覆盖。

## 9. spark 与慢 tick / GC 审核

### 9.1 已消除或显著减少的路径

r3 第一段的 inclusive 采样（父子重叠，不能相加）：

| 路径 | 第二轮 | 第三轮 |
| --- | ---: | ---: |
| `CloudworksNativeContracts.prime` | 735ms | 136ms |
| `remember`，位于 prime 内部 | 599ms | 8ms |
| `VirtualSnapshot.lines` | 559ms | 279ms |

第二段 Runtime worker：

- `Checkpoint.capture` 从 33,350 / 73,590ms（45.3%）降至 12,826 / 75,720ms（16.9%）。
- 第二段没有 `MachineNodeData.writeRuntimeDelta` 采样；源码上常态捕获也不再调用它。
- 主线程第二段没有 `layoutFingerprint` 的有效采样；摘要复用已通过源码和回归确认，不只依据“未采到”推断。
- r3 IO 第二段 5,017 / 5,048ms 为等待，不能解释成持续落盘阻塞。
- 剩余 worker 主要转向 `plan`（inclusive 42,894ms，56.6%）、`maximumBatch`（16,953ms，22.4%）和资源/输出空间查找。它们互相嵌套，不相加，也不在本轮末尾继续扩改。

### 9.2 时间配对能证明什么

夹具记录正式段 >20ms 的 tick，以 `System.nanoTime` 对齐 GC 通知的 JVM uptime；时钟对齐不确定度约 1.02ms。成功档 droppedEvents=0。

r3 三个 >20ms tick 均位于第一段：

| 全局 tick 索引 | 事件时长 ms | GC 通知区间重叠 ms | 解释边界 |
| ---: | ---: | ---: | --- |
| 34 | 44.278 | 4.104 | 与 PS Scavenge 相交，但大部分事件时间在通知区间外 |
| 101 | 263.054 | 0 | 暂停/恢复后的重建 tick；无记录到的收集区间重叠 |
| 151 | 322.427 | 72.000 | 暂停/恢复后的重建 tick，并有一次 PS Scavenge 重叠 |

第二段 r3 无 >20ms tick，最大值 7.240ms。

r1 off/on 第二段仍分别有 134.506/147.761ms 的单次尖峰，与 GC 通知无重叠；汇总栈有原版 `saveAllWorlds -> saveAllChunks -> AnvilChunkLoader.writeChunkToNBT`、会话锁文件打开路径。该证据支持保留原版保存为贡献因素，但汇总栈仍不能唯一对应某个事件。

GC 通知的 collection duration 不能对所有 collector 一概当作 STW 时长；重叠是时间相关证据，不是唯一因果证明。没有重叠也不能排除并发 GC 的 CPU 竞争、线程调度或 sampler 自身开销。

### 9.3 r4

本轮 16G 完成 400 条线、1,638,400 节点、1,802,200 等效机器，日志进入 `IDLE phase=1` 后停止有效推进。守护于 2026-10-26 19:20:33（+08:00）记录：

- 两代均为 100%。
- 观察窗口 Full GC/墙钟比 0.98636。
- 心跳超过 300 秒未更新。
- `CAPACITY_GC_THRASH_STALL_GUARD`，0 个正式测量 tick。

心跳在同一长 tick 内仍可能显示 INSTALL，故结合“全部安装”和日志进入 IDLE 判断为安装完成后的首次运行阶段，不能声称已经证明某个冷编译函数是唯一根因。没有获得有效 spark 正式段、重载或 TPS 结论。需要另行处理常驻/同时存活对象体积，不能用这次检查点优化宣布容量问题解决。

## 10. 产物与复现

主要文件：

```text
docs/CLOUDWORKS_RUNTIME_PHASE3_20261026.zh-CN.md
ai/GLM/CLOUDWORKS_RUNTIME_PHASE3_PLAN_20261026.zh-CN.md
build/reports/cloudworks-full-scale/cw_phase3_20261026_b-matrix.json
build/reports/cloudworks-full-scale/cw_phase3_20261026_b-summary.json
build/reports/cloudworks-full-scale/cw_phase3_20261026_b-p99-evidence.json
build/reports/cloudworks-full-scale/cw_phase3_20261026_b-phase3-audit.json
```

各成功档目录保留 `result.json`、`metrics.json`、`slowtick-gc-pairing.json`、两个本地 spark、运行配置和 build manifest；r4 保留 `capacity-guard.json`、GC 容量探针、安装进度和日志。

离线复核：

```text
python src/fullScaleStress/tools/summarize_remediation.py cw_phase3_20261026_b
python src/fullScaleStress/tools/analyze_p99.py cw_phase3_20261026_b
python src/fullScaleStress/tools/analyze_phase3.py cw_phase3_20261026_b
```

发布产物：

```text
build/libs/MekanismCeU-CloudWorks-1.1.0.jar
SHA-256:
f20c8c697bfc873867b1c4cdcbf2b2c800ab91787cd7775200ff3ecbc0b11407
```

JAR 已检查：包含新增快照类；不包含 `mekanism/cloudworks/fullscale/` 或 `me/lucko/spark/`。全部压力 JVM 和本轮容量守护已退出。

## 11. R3 后续优化：AE 附属实现核对与单档复测

### 11.1 AE 附属中可借鉴的部分

核对 `MekanismCeu-AE-Upgrade-SparkFix` 当前源码后，找到三种相关实现：

- `AEFactoryBatchSearch` 把容量估算当作探测起点；每个候选批量仍重新执行 `prepareRecipe`，只有确认谓词向下封闭后，才对剩余范围二分。其 hint 来自当前机器容量估算，并非上次成功准入的授权缓存。
- `AEAutoOutputBatchGate` 在 gate 创建时预聚合下一批输出量，运行时用当前容器余量做闭式比较。该 gate 只决定何时抽取机器输出，实际网络投递和失败重试仍由原 drainer 负责。
- `AEAutoProcessingRefillGate` 用内容 generation 变化触发检查，并每 20 tick 做兜底检查。它是可过期的补料等待提示；不能把该轮询窗口照搬到 Runtime 的输出承诺，因为承诺会参与严格的准入正确性。

### 11.2 本次 Runtime 变化

- 对确定、向下封闭的批量准入条件加入 per-node/per-recipe 上次成功批量 hint。hint 只改变探测顺序；候选仍按当前输入、能量和输出容量重新验证。
- 概率/选择输出、动态模块 hook、二级输入等无法在此处证明单调的路径不使用 hint；继续走原有搜索逻辑。
- 简单确定性配方预聚合每种 codec 的单次输出系数，用库存余量、输出容量和已有 promise 求批量上限。
- pending module work 放到 batch 自身，减少结算时按 batch key 访问两个共享 `HashMap` 的工作。
- 输出 promise 按物理输出 root 维护版本，稀疏缓存聚合量；新建、替换、清除 promise 均即时递增版本。这里不使用 AE 补料 gate 的 20 tick 延迟失效。

### 11.3 只测 R3 的结果

运行 `cw_phase3_20261026_codex_r3`：同一 r3 档位、40 owners、300 个正式测量 tick、12G、modifier 10000、mixed matrix、spark 全线程采样。夹具功能核账与保存重载通过，worker failure 为 0。

| 指标 | 原 Phase 3 r3 | 本次 R3 | 变化 |
| --- | ---: | ---: | ---: |
| 每 owner 模拟速度 | 14.950 tick/s | 15.379 tick/s | +2.9% |
| 测量段新增 owner tick debt | 3,021 | 2,773 | -8.2% |
| 整轮事件 p99 | 11.824ms | 20.302ms | 第一阶段尾延迟回升 |
| 第一阶段 p99 | 44.278ms | 45.848ms | +1.570ms |
| 第二阶段 p99 | 5.899ms | 2.814ms | -52.3% |
| 最大事件 | 322.427ms | 317.804ms | -4.623ms |

整轮 p99 回升由第一阶段慢 tick 拉高：本次 >20ms tick 分布在第一阶段，其中 242.316ms 和 317.804ms 两次与收集器通知没有重叠；317.804ms tick 与通知重叠约 31.072ms。GC 通知不能解释这些事件的大部分时间。第二阶段没有 >20ms tick，最大 2.861ms。模拟速度改善仍有限，R3 尚未满速。

慢 tick 对照记录：

| 第一阶段样本索引 | 时长 | GC 通知重叠 | 备注 |
| ---: | ---: | ---: | --- |
| 77 | 45.848ms | 7.232ms | 其余时间不在收集器通知区间 |
| 103 | 242.316ms | 0ms | 与约 100 tick 的暂停/恢复重建窗口相邻 |
| 151 | 317.804ms | 31.072ms | 与约 150 tick 的暂停/恢复重建窗口相邻 |
| 177 | 20.302ms | 20.302ms | 收集器通知覆盖该事件 |

首段 Spark 的 Server thread 汇总样本中，`CloudworksServerTickService` 为 1,012ms / 9,978ms；`VirtualSnapshot.lines` 为 362ms，`ProductionLineData.read` / `MachineNodeData.read` 各约 305ms。它与暂停/恢复后的重建工作一致；但 profile 覆盖整段约 10 秒，不能当作上述单个慢 tick 的精确调用栈。因此当前证据把 p99 现象定位到“首段编辑后重建窗口”，尚未证明每个长 tick 的唯一具体栈。无操作的第二段既没有 >20ms tick，p99 也下降到 2.814ms。

### 11.4 spark 对照与验收边界

两次 Runtime worker 采样都是汇总 wall-time 栈样本，父子帧重叠，不表示纯 CPU 时间，也不是每个慢 tick 的因果定位。原 R3 profile 的 `finishModuleCosts` / `HashMap.remove` / `Object.hashCode` 热点约占 worker 样本 20.9%；本次这三项均未见采样，`HashMap#hash` self 样本约 3.2%。本次 `plan` inclusive 约 51.1%，`maximumBatch` inclusive 约 17.1%（self 约 10.7%），说明批量规划仍是主要吞吐成本。本次 hint helper 采样约 443ms / 149.7s worker 样本（约 0.3%）；单次对照不能单独证明它带来的吞吐提升，吞吐数字应视为整组 R3 改动的结果。慢 tick 的直接因果仍需单事件级别调用栈证据，不能从这组汇总栈把批量规划直接判为 p99 根因。

本轮完整 `cleanTest test -Pcw_full_scale=true fullScaleStressClasses`、默认 `build` 均成功。R3 原始结果、spark 和慢 tick/GC 配对保存在：

```text
build/reports/cloudworks-full-scale/cw_phase3_20261026_codex_r3/
build/reports/cloudworks-full-scale/cw_phase3_20261026_codex-p99-evidence.json
```

本次仅运行 R3 压测；没有重测 R1/R2/R4。

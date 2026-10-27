# spark 热点整改实施与验证（2026-10-26）

后续用户选定的五项优化已另行完成，见 `CLOUDWORKS_RUNTIME_PHASE2_20261026.zh-CN.md`；其最终矩阵 `cw_phase2_20261026_b_*` 已全部结束并统一完成 p99 审核。该轮 r2 off/on 实际约 20 tick/s，r3 约 6.87；本文件的 `cw_hotfix_*` 结论保留为上一阶段历史，不替代最新记录。

本轮针对 GLM 交接的持久化回压、模块后处理全表扫描、派生 handle 重复复制和操作随机 ID 热点实施整改。Mekanism 本体和 AE 参考仓库未修改。

## 备份

`backup/spark-remediation-20261026-104824/before.zip` 包含修改前 src、docs、ai、examples、gradle 与 build.gradle。SHA-256：`F8FDBA13FC8D1B5723FE32365278F26F2632261E13D39640A933791E1DF5047A`。同目录另存根配置说明及 GLM 并发更新后的交接文档副本。

## 实现

- `runtime.durableCheckpoints=false`：启动快照与服务端配置同步均包含该布尔值。只控制首次/周期模拟落盘请求及周期 pending flush；内存 checkpoint、模拟推进和模块事件继续执行。显式 flush、空闲驱逐、停服、编辑和资源交易的原耐久路径保留。
- 开启时由 `checkpointIntervalTicks` 控制错峰请求。200 tick 不保证严格 10 秒异常恢复窗口，TPS 和 IO 排队仍影响实际落盘时间。关闭时异常退出可能失去上次耐久边界后的模拟进度。
- NBT 读写使用缓冲 DataInputStream/DataOutputStream，仍调用 vanilla 未压缩重载。写流关闭后才执行原 force/原子替换步骤；文件格式、schema、意图日志和故障注入路径保留。
- `MachineModuleHandle` 公开构造复制并冻结外部 ItemStack 与升级 Map；内部派生链共享这些私有冻结数据。getter 每次返回新的 ItemStack。没有按 MachineKey 全局驻留完整机器栈，也没有绕过 Forge capability 初始化。
- 后处理回调按类缓存覆写判定，包含继承实现，默认空实现跳过。不能用空的 `outputResources()` 排除数量变换。编译期共享资源字典并按机器端口类型/容量倍率复用 codec 容量快照；运行时不再为每次完工扫描完整资源表。未声明资源容量仍为零，新增输出仍经过原端口/资源检查。
- 没有模块耗料时跳过耗料 Map、重复输入分配与空预约记账；有耗料及小数余量时沿用原预留、消费和释放过程。
- Runtime batch UUID 用 ThreadLocalRandom 生成两个 long，保留 UUID v4/variant 位和持久化字符串格式。已核对用途为本地 promise 身份、恢复及取消保留集，不是权限令牌；持久身份不被移除或重新映射。既有恢复路径仍覆盖读取原 ID。

`PostWorkCapacitySnapshot` 是新增不可变 API 值；既有 `PostWorkContext` Map 构造保持防御性复制，新增快照构造用于复用编译结果。addon 原有 `onPostWork` 无需迁移。

## 验证口径

全量回归使用 `build/qa/port-upgrade-isolation.gradle`，单测试 JVM 保留共享 Forge 初始化条件。新增覆盖：

- 外部栈/Map 修改不能污染 handle；派生链不会重复初始化能力，公开 getter 结果互不影响。
- 默认和继承后处理区分；不声明新资源的数量变换继续执行；相同容量形态复用快照，codec room 与未知资源语义保留。
- off/on 周期落盘矩阵、内存模拟推进、崩溃时磁盘副本恢复、显式 flush、正常停服、配置启动冻结及服务端同步。
- 大于缓冲区的 NBT 与 vanilla 未压缩文件逐字节等价，双方读取互通。

回归日志：`build/qa/spark-remediation-final-regression.log`。实际服务端采用独立 runId、原规模、原守恒及重载门禁；r1/r2 分开记录开关两种模式。服务端结果完成后在本文件追加，不把回归或启动成功当作规模通过。

全量回归结果：197 套件、1,152 项，失败/错误/跳过均为 0；IDE 构建通过。正常默认构建通过，正式产物为 `build/libs/MekanismCeU-CloudWorks-1.1.0.jar`，已核对无 fullscale/spark/ScaleFixture 类。发布构建日志：`build/qa/spark-remediation-release-build.log`。

## 服务端实测

### 第一轮：共享 handle 与后处理优化

以下为 `cw_hotfix_20261026_b_*` 独立 JVM 结果，12G 堆、全线程本地 spark。延迟口径为 Forge 事件区间，不是完整服务端外循环 MSPT；每玩家推进率为实际模拟 tick/测量墙钟/玩家数。r1 的略高于 20 包含追赶原有债务，不表示持续超过游戏目标。

| 后缀 | 周期落盘 | 节点/物理机器 | 正式 ticks | 核账/重载 | p50 / p99 ms | 每玩家模拟 tick/s |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| r10k_m10 | off | 400 / 10,000 | 400 | 通过 | 1.322 / 3.709 | 19.995 |
| r10k_m10000 | off | 400 / 10,000 | 400 | 通过 | 1.315 / 2.861 | 19.995 |
| r1_off | off | 7,116 / 31,680 | 400 | 通过 | 4.892 / 16.068 | 20.482 |
| r1_on | on | 7,116 / 31,680 | 400 | 通过 | 3.933 / 11.001 | 20.067 |
| r2_off | off | 47,440 / 211,200 | 400 | 通过 | 30.148 / 125.388 | 6.900 |
| r2_on | on | 47,440 / 211,200 | 400 | 通过 | 23.050 / 127.894 | 6.236 |
| r3_off | off | 210,960 / 538,560 | 300 | 通过 | 2.172 / 192.775 | 0.532 |

r1 稳态性能门槛通过，worker 每 owner tick 约 18.5–20.3ms，整改前约 72–77ms。r2 两种模式首次均完成全程及重载，off 测量两段 persistenceWaits 增量均为 0，on 分别为 1,565/781，故不能声称持久化成本已消失。

r2 off 的 p50 略超 30ms 门槛；on 的主线程门槛通过，但两者每玩家推进均远低于 20，不能把功能通过或主线程门槛等同于满速。不同单轮的 p50 有调度/GC 差异，不据此声称开启保存反而加速。r3 仅证明容量及资源/重载功能通过，0.532 tick/s 明确不是性能验收通过。

七组冷启动/空闲阶段 p99 仍未通过 30ms 门槛：依次约 330、1,146、1,025、1,003、7,002、6,306、26,367ms。它们包括首次捕获/编译，不与稳态延迟混合。r4 的极限容量结果另行追加。

### 第二轮追加：默认回调分发

第一轮 r2 off 的 profile-1 中，主线程 `CloudworksModuleEvents.flush` 占 5,536/12,410ms（44.61%）；`forContext` 仍有 5,515ms inclusive，虽然该线程的 ItemStack.copy 仅 37ms。说明共享后仍有默认空事件及派生对象分配，不能把第一轮当作主线程热点全部解决。

追加备份：`backup/spark-remediation-callbacks-20261026-114533/before.zip`，SHA-256：`CAD862784C23B28967F80705915E7CD3AFBFB07E5485B9312773571FF43001D4`。

新增 `CloudworksModuleCallbacks`：按实现类缓存 pre/candidate/post/started/committed/cancelled 是否覆写。反射仅发生在首次绑定；继承覆写仍执行，无法分类时保守保留回调。Runtime 编译每节点的回调标志，纯默认模块跳过候选包装、pre-work 数据构造和生命周期入队，完工时也不再为默认接收者转换输出列表。模块事件以外的 API 事件、安装/卸载回调保持原路径。

小数副燃料即使没有 pre-work 回调，也仍有独立余量记账；已由 `fractionalSecondaryFuelIsPrepaidAndItsRemainderSurvivesReload` 回归抓取并修正，保留可写余量 Map。容量倍率采用既有饱和容量工具，未知/空资源的快照容量保持零。新增纯默认模块不入队而产出正常、继承各类回调独立识别测试。

第二轮采用新的 `cw_hotfix_20261026_c_*` runId；第一轮结果保留，不混作最终源码结果。

第二轮回归：197 套件、1,154 项，失败/错误/跳过均为零；日志 `build/qa/spark-remediation-callback-final-regression.log`。包含 off/on 空闲驱逐恢复与正常关机覆盖。正常发布构建与正式 JAR 隔离再次通过，日志 `build/qa/spark-remediation-callback-release.log`。

| 最终后缀 | 周期落盘 | 节点/物理机器 | ticks | 核账/重载 | p50 / p99 ms | 每玩家模拟 tick/s |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| r10k_m10 | off | 400 / 10,000 | 400 | 通过 | 0.991 / 1.728 | 19.998 |
| r10k_m10000 | off | 400 / 10,000 | 400 | 通过 | 0.981 / 1.900 | 19.998 |
| r1_off | off | 7,116 / 31,680 | 400 | 通过 | 0.863 / 1.629 | 19.995 |
| r1_on | on | 7,116 / 31,680 | 400 | 通过 | 0.865 / 2.007 | 19.999 |
| r2_off | off | 47,440 / 211,200 | 400 | 通过 | 5.507 / 63.064 | 13.595 |
| r2_on | on | 47,440 / 211,200 | 400 | 通过 | 5.985 / 45.419 | 10.304 |
| r3_off | off | 210,960 / 538,560 | 300 | 通过 | 2.657 / 57.244 | 1.048 |

最终 r1/r2 的主线程延迟门槛均通过。r1 worker 每 owner tick 为 11.9–14.2ms；r2 off 为 25.2–27.6ms，on 为 29.7–32.3ms。r2 off 两段测量 persistenceWaits 增量均为 0；on 分别为 2,889/412。off 总计数仍可包含准备和强制事务等待。r2/r3 模拟仍低于 20 tick/s，保持为明确的剩余性能限制。各档冷启动 p99 仍未达到 30ms，不隐去该门槛失败。

最终 spark 两段合计，r2 off 的主线程 `CloudworksModuleEvents.flush` 仅 2ms/20,069ms，`forContext` 未采到；worker 的后处理、空预约及 UUID.randomUUID 观察项未采到。采样为零不代表所有路径永久无调用；真实自定义回调/冷捕获/公开 getter 仍保留。r2 off 的 IO profile-1 约 97.2% 等待；on 的 IO 接近持续工作，`force0`、创建/替换文件与 NBT/投影处理仍存在。worker self 榜已转为 `CloudworksRuntimeRecipe.allocate`、`planningOutputsFit`、`maximumBatch`、`promisedOutputsFit` 等实际数值规划，下一阶段若继续追求 40 玩家满速，应独立处理这些规划与 checkpoint 成本。

完整机器可读汇总：`build/reports/cloudworks-full-scale/cw_hotfix_20261026_c-summary.json`；每轮原始结果、两段 spark 与 `spark-analysis.json` 在各自 runId 目录。`src/fullScaleStress/tools/summarize_remediation.py <prefix>` 可重复生成独立汇总，不覆盖旧矩阵。

### r4 的 12G 失败

`cw_hotfix_20261026_b_r4_off` 在 INSTALL 阶段构建至 1,290,240 节点、1,417,185 台后，`ScaleFixture.installNext → drainPersistence → awaitPersistence(10000)` 超时，原始错误为 `cloudworks.error.storage`，正式测量 0 tick。没有实际 OOM，也没有 `lastIoFailure` 记录；不能称作已证实的磁盘损坏。

失败前老年代连续 100%，若干约 30 秒区间 Full GC 约占 91–99%；失败时一笔 IO 批次耗时 22,147ms。`capacity-diagnosis.json` 单独记录 GC 压力与准备阶段等待超时，原始 result.json 未改。它证明本轮未完成，不能外推为 12G 在任何等待策略下都绝不可能完成。第二轮仅追加一次 16G r4 重试。

### r4 的一次 16G 重试结论

最终代码的 `cw_hotfix_20261026_c_r4_retry16_16g` 在 12:18:01 完成 400 条线、1,638,400 节点、1,802,200 台机器的全部安装。构建 488.149 秒，安装完成采样累计 GC 249.554 秒；12:18:04 进入首次 IDLE/cold runtime 阶段，12:19:15 发生真实 `OutOfMemoryError: GC overhead limit exceeded`。正式测量 0/100 tick，没有有效 TPS、核账或重载通过结论。

OOM 栈：`CloudworksRuntimeService.serverTick → CloudworksNativeContracts.prime → VirtualSnapshot.lines → ProductionLineData.read → ProductionLineLink.read → UUID.fromString`。这表示冷捕获重建连线对象时分配失败，不是本轮 batch UUID 随机源故障，也不是已证实的磁盘损坏。原始 Gradle 日志、原生 server crash report、progress.json、GC 探针与 `capacity-diagnosis.json` 保留；此轮 result.json 是夹具来不及生成正常结果时的 Gradle evidenceFallback，明确 functionalPass=false、measuredTicks=0。

未追加第三次更大内存测试，全部整改压测 JVM 和 watchdog 已退出。本轮交接指定热点已落实并完成验证，但不承诺极限 r4 可用，也不承诺 r2/r3 满速。若继续优化，应作为独立阶段处理冷捕获/解码峰值内存、实际配方规划及周期耐久投影写入；不能靠取消 force、弱化资源合同或把未测量结果填成 TPS 来通过。

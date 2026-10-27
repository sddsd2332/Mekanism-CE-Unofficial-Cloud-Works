# 真实服务端满配规模夹具与实测记录（2026-10-26）

最新第二轮五项优化与最终数据见 `CLOUDWORKS_RUNTIME_PHASE2_20261026.zh-CN.md`，全部测试结束后的定向诊断见 `CLOUDWORKS_RUNTIME_PHASE2_SPARK_P99_20261026.zh-CN.md`，对应 `cw_phase2_20261026_b_*`。r2 off/on 均约 20 tick/s；r3 约 6.87，仍非满速；r4 12G/16G 均未进入测量。以下旧轮记录保持为历史证据。

本轮接续原机端口与升级对齐验收。端口修改与 1,141 项回归结论见 `CLOUDWORKS_NATIVE_PORT_ALIGNMENT_20261026.zh-CN.md`。本文件记录独立服务端规模测试，不把编译成功、启动成功或夹具调试失败当作性能验收。

## 1. 隔离与复现

2026-10-26 热点整改后的最终数据见 `CLOUDWORKS_SPARK_REMEDIATION_20261026.zh-CN.md`，对应 `cw_hotfix_20261026_c_*` 和独立 `cw_hotfix_20261026_c-summary.json`。该轮 r10k/r1/r2/r3 的功能与必要重载通过；r2/r3 仍非满速，r4 的一次 16G 重试在全量安装后真实 OOM，未进入测量。不要用本文件的整改前历史数字替代最新结论。

- 修改前备份：`backup/full-scale-stress-20261026-020449/before.zip`。
- 测试 MOD：`src/fullScaleStress/java/mekanism/cloudworks/fullscale/`；Gradle：`gradle/scripts/full-scale-stress.gradle`。只有 `-Pcw_full_scale=true` 才接入，测试源码和 spark 不进入正常发布物。
- spark 固定使用与 AE 附属相同的 `curse.maven:spark-361579:3542217`。使用本地 `spark profiler --interval 1`，停止命令带 `--stop --save-to-file`；确认 profiler 已停止，并保留两段本地 `.sparkprofile`。不上传。
- 工作目录：`run/full-scale/<runId>`；世界：`full-scale-<runId>`；报告：`build/reports/cloudworks-full-scale/<runId>`。runId 禁止复用。复制已接受的 EULA、config、scripts 后只调整隔离副本。
- 默认 12G 堆。Mek 速度/能量上限及叠数为 32，IC2 式升级开关启用；支持原生 Mek 曲线的机器装满对应 32 速/32 能量，支持 GAS 的机器装满 GAS。`modifier=10` 与 `10000` 都需要实测。可选混合档的 TE 取已注册的最高等级机型，IC2 标准机使用其原生曲线基线，不把 Mek 升级错误安装到固定曲线机器，也不把这些额外机型声称为 256 超频满装对照。
- 隔离配置允许每线 4,096 机器节点、单节点 4,096 台、10 条线、缓存倍率 16（堵塞行 1）。准备阶段一次性装入有限原料，每种最多 `10^12`；使用正常服务端 `warehouseItemTypeCap=10^12` 配置容纳库存，不绕过仓库容量，也不在测量 tick 中无限加料。能量有限、初始每人 `10^14 J`；每 20 tick 检查低于 200J 者加 100J，单独记账。

```powershell
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r10k_m10_唯一编号 -Pcw_full_scale_rung=r10k -Pcw_full_scale_modifier=10 --console=plain
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r10k_m10000_唯一编号 -Pcw_full_scale_rung=r10k -Pcw_full_scale_modifier=10000 --console=plain
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r1_唯一编号 -Pcw_full_scale_rung=r1 -Pcw_full_scale_modifier=10000 --console=plain
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r2_唯一编号 -Pcw_full_scale_rung=r2 -Pcw_full_scale_modifier=10000 --console=plain
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r3_唯一编号 -Pcw_full_scale_rung=r3 -Pcw_full_scale_modifier=10000 -Pcw_full_scale_mixed=true --console=plain
./gradlew.bat runServer -Pcw_full_scale=true -Pcw_full_scale_run_id=cw_r4_唯一编号 -Pcw_full_scale_rung=r4 -Pcw_full_scale_modifier=10000 -Pcw_full_scale_mixed=true --console=plain
```

可选参数：`heap`、`ticks`、`warmup`、`seed`、`upgrades=none|full`、`mixed=true|false`、`spark_threads=main|all`、`durable_checkpoints=false|true`（默认 false）。后者写入隔离配置和 `run-configuration.json`，用于 r1/r2 周期落盘开关对照，显式保存及重载核账继续执行。r1/r2/r10k 默认 400 tick，r3 300，r4 100；超时分别 20/60/180 分钟。r4 的 12G 如果出现 OOM，只追加一次 16G 重试并分别报告，不能把 16G 结论当 12G 结果。

## 2. 规格的算术修正

按规格列出的每玩家结构直接相加，原规格 r1/r2/r3 的约数不准确。夹具采用结构定义，不按约数悄悄减规模。

| 档位 | 玩家 | 每人机器节点 | 总机器节点 | 总物理机器 | 正式 tick |
| --- | ---: | ---: | ---: | ---: | ---: |
| r10k | 10 | 40 | 400 | 10,000 | 400 |
| r1 | 6 | 1,186 | 7,116 | 31,680 | 400 |
| r2 | 40 | 1,186 | 47,440 | 211,200 | 400 |
| r3 | 40 | 5,274 | 210,960 | 538,560 | 300 |
| r4 | 40 | 40,960 | 1,638,400 | 1,802,200 | 100 |

r3 把一条 8 节点链换成 4,096 节点线，仍保留每人 10 条线，大节点数量从 2,048 提到 4,096。r4 每人十条线均 4,096 节点，只有一处节点合并 4,096 台，其他节点一台。以上不把额外的传输通道混算为机器节点，报告另列通道数和连线数。

## 3. 原生配方与拓扑

从实际冻结注册表抽样，不注册替代加工配方。覆盖可用的 13 类单机与四档工厂，以及化学/气液资源族；可选混入 TE 与 IC2。每变体优先选两条不同的真实配方；原生表不足两条时明确列出缺口和冻结表条数，不制造第二条配方。各配方实际推进量写入 `resourceAudit.recipeProgress`，矩阵可选数不等于已完成数。

长线是有限供料通道 → 原生旋转凝缩器正反交替 → 收集通道的**无环有向图**。使用原生 1:1 可逆转换验证守恒。大规模档在准备阶段给每个链节点预装半仓对应真实输入，全部加入初始守恒账，避免 4,096 节点长链在短程采样中绝大多数节点一直空转；测量期间只靠正常连线传输。加工宽线和大节点按抽样配方分组，每组独立供料/收集；每玩家两条堵塞线没有出货通道。`nodesWithCompletedOutput` 与 `nodeStates` 分别记录真正完成过产出的节点数和收尾状态，不能仅凭创建了节点就视为活跃机器。

所有通道复用正常 `TRANSFER_INPUT` 语义；玩家中转仓为空，没有外部补给，没有直接通道对通道连接。每组有限原料位于真实通道缓存中。遵循正常拓扑校验：不允许环、不创建多个普通 RECEIVE_INPUT/SEND_OUTPUT，也不改生产验证器让夹具过关。

## 4. 验证与度量口径

- 正常 Forge 事件驱动 `CloudworksServerTickService`，不重复手动 tick。HIGHEST START 到 LOWEST END 记录实际事件区间；与 AE 的事件区间口径接近，均不等于完整服务端外循环 MSPT。
- 安装、零喂料 40 tick、投料、预热、spark 握手、正式测量、等待任务结束、存读档、核账各自分段。准备阶段批量 API 交易尊重持久化队列背压，等待落盘不计为稳态 tick。三次不同玩家编辑在测量段发起，正常的 busy 拒绝按下一 tick 重试，不改写生产队列。沿用 AE 在 LOWEST END 入口先采样、后做夹具控制的口径，编辑开销计入测量墙钟时间，但不计入已采样的事件时长；不能把这里的时长称为完整服务端外循环 MSPT。
- 使用真实 owner 存档与正常会话策略，在线状态由假 UUID 会话模拟；没有 40 个实际网络客户端、实体玩家、打开的 GUI 或客户端渲染负载。准备/收尾通过正常在线限定产线的登出暂停执行，异步等 worker 和 IO 队列归零，不把活跃队列的 10 秒等待超时误报为磁盘损坏。投料时仍遵守停线编辑 API，投料后才恢复会话。
- 在 2/3 处停线、等任务结束、核账、flush/drain、关闭并从真实世界目录重载。按正常 owner 加载流程重新载入玩家，再恢复会话；前后缓存与输出序号核账完全一致。r4 可以跳过重载，但必须标记。
- 守恒：1:1 链总量精确相等；其他机器以真实配方的主输入/主输出做等价单位核账。保证产出逐项精确，独立概率产出按各自原生条目上下界验证。未完成配方保留在输入缓存的资源只计一次；随机辅助耗料不虚构统一质量换算。
- `functionalPass` 要求完成实际 ticks、完整规模、无 worker/IO 错误、每玩家真实推进、资源核账和必要的重载通过。r1/r2 性能阈值 p50<30ms、p99<250ms 另记 `performancePass`，空闲 p99<30ms 另记 `idlePerformancePass`，不把功能通过等同于性能通过。
- 记录安装/编译/重载耗时、tick 分位数、worker/捕获/提交/冲突/债务/背压计数、堆与 GC、最终玩家阶段、持久化写入次数与字节。`progress.json` 在构建过程中留存进度，失败与 OOM 的部分规模可追溯。
- 每轮保留配置与全输入 SHA-256 清单、结果、metrics、日志和本地 spark 文件。失败轮保留原样，不能重用同一 runId 覆盖证据。

## 5. 修复前实测（历史对照）

| 正式记录 | 堆/倍率 | 节点/物理机器 | 功能与重载 | 事件均值 / p99 | 每玩家实际模拟 tick/s |
| --- | --- | --- | --- | --- | --- |
| `cw_r10k_m10_final_a` | 12G / 10 | 400 / 10,000 | 400 tick、核账、重载通过 | 2.249 / 14.290 ms | 20.000 |
| `cw_r10k_m10000_final_a` | 12G / 10000 | 400 / 10,000 | 400 tick、核账、重载通过 | 2.580 / 6.505 ms | 20.172（包含追赶先前少量债务） |
| `cw_r1_m10000_final_d` | 12G / 10000 | 7,116 / 31,680 | 400 tick、核账、重载通过；全部节点有产出 | 10.139 / 30.595 ms | **8.099** |
| `cw_r2_m10000_final_b` | 12G / 10000 | 47,440 / 211,200，已全部构建 | 首次运行阶段真实 OOM；没有有效测量段 | **无有效值** | **无有效值** |

`r2` 在 08:38:57 抛出 `OutOfMemoryError: GC overhead limit exceeded`；不是外部停滞保护中止。08:38:29 与 08:39:38 的 `jstat -gcutil` 显示年轻代/老年代均 100%，Full GC 时间从 333.986s 增至 399.974s；约 69 秒墙钟时间中约 66 秒用于 Full GC。原始探针、崩溃日志、构建进度及补充字节码快照均保留在该轮报告目录。旧版 finalizer 的原始 result.json 保持不改，metrics.json 明确标注为据原始结果与构建检查点整理的失败证据。

**修复前结论**：r1 虽然满足主线程 p50/p99 阈值，但后台仅约 8.1 模拟 tick/s，不能称为产线满速。r1 第一段 spark 的主线程墙钟栈采样中，模块事件派发约占 22%；此数为包含调用链的采样占比，不是独立 CPU 使用率。r2 出现实际内存边界。随后 r3 在首位玩家的 4,096 节点产线安装中真实 OOM，成功提交的只有先前三条 8 节点线：24 节点/24 台，构建阶段 177.831 秒，其中 GC 累计约 172 秒，未进入测量。原始记录 `cw_r3_m10000_mixed_a` 保留，不能把尝试中的 4,096 节点记为已提交节点。

**空闲门槛未通过**：两个 r10k 正式轮空闲 p99 分别 333.402ms、1,091.377ms，不能因稳态通过而删掉冷启动阶段的超标。r1 的主线程与模拟吞吐也分开报告。

**多配方构成边界**：长链为原生旋转凝缩的两种真实转换，宽线/大节点才循环使用完整变体矩阵。r1/r2 中约 89% 的机器节点属于链路，但大节点合并使多配方加工占约 80% 物理机器台数；r3 约 97.5% 节点是链路。该夹具覆盖多配方资源路径及长链结构，但不是所有节点等权混合 65 个变体的压力测试，不把表面覆盖等同于均匀负载。混合档实际可选 106 个变体、206 个真实配方，含 TE 15 型、IC2 11 型，以及 PRC/核合成各五形态；只有实际进入测量并出现推进时才能宣称这些变体通过压力验收。

### 内存停滞保护

外层 Gradle 20/60/180 分钟超时保留。额外提供 `src/fullScaleStress/tools/watch_capacity.ps1`：每 30 秒只读目标隔离 JVM 的 jstat；在夹具心跳至少 300 秒没有更新、前后两次年轻代/老年代均 ≥99%、两次采样间 Full GC 时间占墙钟 ≥90% 时，保存 `capacity-guard.json` 与原始探针并仅终止已核对 runId、工作区和创建时间的该测试 JVM。该结果写作 **GC 抖动停滞边界**，明确 `actualOutOfMemoryError=false`，不能假称 OOM，也不生成 TPS。它是按规格“不可行可降级但必须带数据回报”增加的提前容量保护，不能冒充原始 20/60/180 分钟超时。若原生 OOM 先发生，仍以原生 OOM 为准。

失败轮没有启动 profiler 时不会制造空的 spark 文件。未来复现可在另一个隐藏 PowerShell 进程运行该脚本，`-Workspace` 指向当前仓库、`-RunId` 与 Gradle 一致；正常完成后脚本自动退出。夹具每 20 个 END 事件写入一个很小的心跳，此控制工作在事件耗时采样点之后。

正式结果以 `*_final_*` 轮为准，待各轮结束后以实际 JSON 回填。前缀 `cw_r10k_m10_full` 的早期 a～h 轮用于修复夹具准备/重载流程，包含非法拓扑、队列背压、库存配置与未显式加载 owner 等失败。`cw_r10k_m10_full_i` 和 `cw_r10k_m10000_full_a` 已完成 400 tick、核账、重载及 spark 文件验证，但重载后出现原版追帧，其耗时不作为最终稳态对照。最终版本在每次预热等待至少 5 秒真实时间，并检查最近 20 个 tick 间隔合计不少于 800ms，再开始正式测量。

配方表已查实：当前完整 dev 环境的 TURNING 五种形态冻结配方表均为 0，BRUSHED 五种形态只有一条可用配方；不修改原模组或制造替代配方。前置功能验证轮的其余 133 个抽样配方均有实际推进。

## 6. AE 基准的可比边界

已核验 AE 的 `ae_online_ic2_32_provider_cache_10000_bg_20261006_193312` 和 `ae_online_ic2_32_no_comparator_5000_ic2split_20261012_233428` 的结果与配置：均 12G、Java 8u504、功能通过，分别测量 2,756 tick/300.018s（实际 9.186 TPS）、5,999 tick/300.012s（实际 19.996 TPS）。

AE 使用真实 tile、AE 网络、stone/cobblestone→dirt 的白名单配方及部分辅助资源；本夹具使用原生配方、有向产线、堵塞线和合并节点。因此即使机器台数、硬件、堆、32/32 升级与 modifier=10 对齐，也**不能把耗时比值解释为纯虚拟化加速倍数**。AE 的 300 秒采样还长于本规格的 400 tick。modifier=10000 没有 AE 同口径现成实测，只列 CloudWorks 结果，不推算 AE TPS。

AE 10k 的 run-configuration 还明确启用了 comparatorUpgrade，CloudWorks 按用户要求没有这些升级；AE 最新 5k 则关闭 comparator。基准环境的这些差异同样保留，不能用一次事件耗时比较归因单一优化。

离线证据工具：`src/fullScaleStress/tools/analyze_profiles.py <report-directory>` 生成各线程本地采样热点；`summarize_results.py <runId> ...` 汇总明确指定的已结束轮次并统计真实世界文件数/字节，输出在报告根目录 `summary.json` 与 `summary.md`，不改原始 result.json。

## 7. 压测发现并修复的平方级上下文内存

修改前单独备份：`backup/module-context-sharing-20261026-0849/before.zip`，同目录保留范围与 SHA-256。

原 `CloudworksModuleBinding.context` 为每台机器构造整条线的其他机器列表，并复制每个机器身份 ItemStack；`sourceBindings`、安装回调和运行时捕获又逐节点调用。N 节点线会产生 N×(N-1) 个机器概览。4,096 节点单线即 **16,773,120** 份概览，正好解释了首条大线安装阶段的爆炸性堆消耗。

修复复用原入口和已有 `MachineContextSnapshot`：新增 `CloudworksModuleContexts` 作为一次捕获内的共用底层，机器身份只冻结一次，邻接连线也只索引一次；各节点以只读、自身排除、保持原顺序的视图共享不可变机器概览。安装回调、来源绑定与 DetachedState 捕获全部接入该底层。没有按可变 ProductionLineData 做全局缓存，修改后下一次捕获仍重新读取状态，旧快照保持隔离。

这里修的是机器概览与邻接连线的平方级复制；不宣称 addon 主动遍历所有机器、辅助节点筛选或其他任意脚本逻辑都变成常数成本。公开列表语义和防御性 ItemStack 访问保持，未改 Mek 本体。

新增回归覆盖 4,096 机器视图共享、顺序、自身排除、只读集合、复制身份、变更后的新旧快照隔离、邻接连线及连接通道语义。2026-10-26 全量验证：**194 套件、1,144 项测试，失败/错误/跳过均为 0**；压力夹具同步编译成功。日志：`build/qa/module-context-sharing-regression.log`。

## 8. 修复后实测

使用新前缀 `cw_ctxfix_20261026_a_*` 重新执行同样的倍率、规模、堆和实际核账；最终结论以这些新记录为准。旧数据只作定位与前后对照，不把修复前的 OOM 当作修复后容量上限。完成后按实际 JSON 回填。

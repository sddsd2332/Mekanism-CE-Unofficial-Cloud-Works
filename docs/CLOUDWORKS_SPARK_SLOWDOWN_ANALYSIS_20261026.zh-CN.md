# 现有 spark 降速定位（2026-10-26）

按用户要求暂停重新压测，先排查已有文件。本轮没有修改以下瓶颈的生产实现，也没有再启动测试服。此前的上下文共享修复已通过 1,144 项回归，它解决平方级内存复制；本报告讨论剩余的推进降速。

## 1. 数据范围与口径

- R1：`build/reports/cloudworks-full-scale/cw_ctxfix_20261026_a_r1/`，两个主线程 profile，400 tick 完整功能验证。每玩家实际模拟推进 9.712 tick/s。
- R2：`build/reports/cloudworks-full-scale/cw_ctxfix_20261026_a_r2/profile-1.sparkprofile`，第一段全线程采样，约 13.324 秒的线程采样预算。用户要求改查原因后已中止此轮，`interruption.json` 记录现场；它不是完整 40 玩家性能验收。
- 离线汇总：`build/reports/cloudworks-full-scale/slowdown-spark-findings-20261026.json`。每轮的 `spark-analysis.json` 给出完整线程分组摘要。
- 下列毫秒数是调用栈采样权重，不是独立 CPU 时间。父子 inclusive 时间重叠，不能相加。Runtime Combined 为 15 个 worker 的合并值，分母与单个 IO/主线程不同。

## 2. 40 玩家：最明显的停顿是持久化回压

IO 线程采样总量 **13,324ms**，几乎全程位于 `CloudworksPersistenceQueue.runBatch`。

| 路径 | inclusive 采样 | IO 线程占比 |
| --- | ---: | ---: |
| `packProjectionPatches` | 10,487ms | 78.71% |
| `readView` → `CompressedStreamTools.read` | 10,433ms | **78.30%** |

叶子热点：`FileInputStream.read0` 8,128ms，`readBytes` 2,296ms；写侧 `FileOutputStream.write/writeBytes` 也出现 1,557/900ms。不能据此直接认定硬盘带宽耗尽，数据更直接指向普通 NBT 文件读写的细粒度原生 IO 调用。

源码对应：

- `CloudworksPlayerDataManager.java:659-668`：`readView` 调用原版 `CompressedStreamTools.read(File)`。
- 当前实际 MC 源码 `build/rfg/minecraft-src/java/net/minecraft/nbt/CompressedStreamTools.java:173-186`：该重载是 `DataInputStream(new FileInputStream(file))`，没有 BufferedInputStream。
- `CloudworksPlayerDataManager.java:690-707`：写侧也使用原版 `write(tag, File)`；MC 对应实现 :158-164 同样没有 BufferedOutputStream。云端之后仍会 force 与原子替换。
- `CloudworksPlayerDataManager.java:3992-4014`：打包补丁前读取各条旧产线 NBT。`IO_READS` 是批次内缓存，不能消除跨事务的旧文件读取。

背压链已经在现场计数中出现：

```text
旧 NBT 读写慢
  → 单 IO 队列积压
  → RuntimeService 在 backlog >= ioCapacity/2 时暂停接收完成结果
  → 已完成 Future 仍占着 player.task
  → 同 owner 不再投下一项工作
  → worker 线程回到取任务等待
```

`CloudworksRuntimeService.java:79-82` 是提交门禁；:170 阻止同 owner 已有 task 时再投递。默认 IO 容量 64，因此半满门槛为 32。

现场从测量 tick=150 到 250：`commits=166`、`simulatedTicks=166` 完全不变，`persistenceWaits` **10,173→14,173**，`tickDebt` **9,946→13,946**，执行队列 `pending=0`。这不是“没有活需要计算”：完成结果被提交门禁拦住了。

Runtime 15 线程合并采样 199,860ms，其中 `Unsafe.park` **175,275ms（87.70%）**，调用栈为 `ThreadPoolExecutor.getTask → ArrayBlockingQueue.take`。真正工作调用链约 24,582ms（12.30%）。主线程同期约 90% 采样在 sleep。因而此片段不能按“worker CPU 已满”解释降速。

注意：此轮的开始阶段还带着初始检查点积压，现有 profile 无法把冷启动积压与持续周期写的成本彻底拆开。它能证明现场的 IO 热点与背压，不能据此给 40 玩家稳态 TPS。

## 3. worker 工作时：通用模块后处理与快照复制最重

以下占比以 Runtime 实际工作调用链的 24,582ms 为分母，仍是重叠的 inclusive 采样。

| 路径 | inclusive 采样 | 工作路径占比 | 源码所做的事 |
| --- | ---: | ---: | --- |
| `transformModuleOutputs` | **9,489ms** | **38.60%** | 每次完工扫玩家 program 的整个资源描述符表，建立容量 Map |
| `MachineModuleHandle.<init>` | **7,294ms** | **29.67%** | 派生 handle 时重新复制 ItemStack 和 installedModules Map |
| `PostWorkContext.<init>` | 2,975ms | 12.10% | 已建立的 capacities/stored/installed Map 又复制一次，是第一行的子成本 |
| `reserveModuleCosts` | 2,234ms | 9.09% | 维护 batch→cost Map，叶子主要是对象 identity hash |
| `RuntimeBatch.<init> → UUID.randomUUID` | 781ms | 3.18% | 每批次创建随机 UUID；其下 SecureRandom 717ms |

### 最具体的全表扫描

`CloudworksRuntimeSimulation.java:1198-1217`：

1. 遍历 `program.resourceDescriptors`，而非仅本次输出。
2. 对每种资源算同 codec 对应的输出容量，填入 descriptors→capacity Map。
3. 还建立 `codecCapacities`，但该方法后续没有使用此 Map。
4. `PostWorkContext.java:29-31` 再防御性复制这些 Map。
5. 只要 `program.modules[node]` 不为空就经过这个流程；普通满装速度/能量模块同样触发，即使它们不改产物。

`UnmodifiableCollection.size` 的 4,651ms self 采样就落在这条扫描调用链中。资源表越大、完工次数越多，额外成本越高。

### 派生模块 handle 的重复复制

`MachineModuleHandle.java:50-53` 每次构造都复制机器身份与升级 Map；:81-97 的 `forContext/forRecipe/forExecution/withCounts/withMachineContext` 又经过这个构造器。现有对象的这些数据已经是不可变快照，派生调用仍重复付出复制成本。

调用分布包含：`candidate` 4,935ms、`pre` 1,974ms、`post` 618ms。它们是子调用，不能再与构造器成本累计。

## 4. R1 的 9.71 tick/s：单次虚拟 tick 已超过预算

R1 现有统计可以排除“它主要被 IO 队列门禁拦住”：两段测量 `persistenceWaits` 增量都是 **0**。

- 第一段 worker 累计 59.045s / 763 个模拟 owner tick = **77.39ms/模拟 tick**。
- 第二段 29.122s / 403 = **72.26ms/模拟 tick**。
- 两段均为一次成功提交只推进一个模拟 tick。
- `CloudworksRuntimeService.java:32` 的 worker 时间片为 **20ms**；:234-237 至少做一次完整 tick，之后才检查时间片。超过时间片后不能在同一任务内继续追赶。
- :170 每个 owner 只保留一项工作；完成结果又由正常服务端 tick 接收。单次推进超过 50ms 后，实际推进容易落到约每两个游戏 tick 一次，与约 9.71 tick/s 的现象对应。

R1 没有 worker 的全线程 profile，因此不能把这 72–77ms 全归给其中一个函数。R2 的 worker 样本已把优先排查点指向模块后处理与 handle 复制，属于有证据的方向，不是完成后的优化收益承诺。

## 5. 主线程也有持续回调成本

R1 两个主线程 profile 合计 20,113ms，`CloudworksModuleEvents.flush` 为 **3,657ms（18.18% 总采样，约 73.88% 非 sleep 采样）**，几乎都落在派生 `MachineModuleHandle` 的构造器（3,627ms）。

这条成本同样来自 `started/committed → forContext/forRecipe → ItemStack.copy + Map.copy`。R2 被 IO 回压时回调减少，所以主线程更空；不能把这当作大规模运行更高效。

## 6. 供实现评估的优先顺序

1. 先处理 NBT 文件读写的流包装；继续复用原版 NBT 编解码即可。必须保留原始存档格式、临时文件、flush/close、force 与原子替换的顺序，不靠取消耐久性或扩大队列掩盖问题。
2. 再评估后处理的全资源容量 Map：区分真正需要输出修改的模块，并考虑不可变容量查询/缓存；保留 addon 查询容量及改产物的语义。
3. 评估派生 handle 如何共享已冻结的身份和升级数据，保留对外 getter 的防御性访问；覆盖 worker 和主线程两条调用路径。
4. 空成本 bookkeeping 与 UUID/SecureRandom 属于后续热点；操作身份、事件恰好一次与重载语义要先核对，不能仅为了性能直接删除。

这里不执行上述实现，也不重新压测，留给用户先评估。

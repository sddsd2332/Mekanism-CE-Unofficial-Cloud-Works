# 第二轮全部压测结束后的 spark / p99 审核

日期：2026-10-26。按用户要求，等待 `cw_phase2_20261026_b_*` 全部结束（含 r4 的一次 16G 重试）后再统一解析。所有本轮压测 JVM/watchdog 已退出。本文件区分采样事实、源码解释和尚未完成的逐 tick 因果验证。

## 1. 最终结果

| 档位 | 节点 / 等效机器台数 | 周期落盘 | 正式 tick | 功能 / 重载 | p50 / p99 ms | 每玩家模拟 tick/s |
| --- | ---: | --- | ---: | --- | ---: | ---: |
| r10k m10 | 400 / 10,000 | off | 400 | 通过 | 1.071 / 3.361 | 20.006 |
| r10k m10000 | 400 / 10,000 | off | 400 | 通过 | 1.057 / 2.611 | 20.000 |
| r1 | 7,116 / 31,680 | off | 400 | 通过 | 0.969 / 6.546 | 19.856 |
| r1 | 7,116 / 31,680 | on | 400 | 通过 | 0.966 / 15.708 | 19.983 |
| r2 | 47,440 / 211,200 | off | 400 | 通过 | 2.964 / 11.016 | 20.004 |
| r2 | 47,440 / 211,200 | on | 400 | 通过 | 3.069 / 9.848 | 20.066 |
| r3 | 210,960 / 538,560 | off | 300 | 通过 | 3.371 / 115.619 | 6.870 |
| r4 12G | 部分规模，见下 | off | 0 | 未通过 | 无有效值 | 无有效值 |
| r4 16G | 完整安装，见下 | off | 0 | 未通过 | 无有效值 | 无有效值 |

略高于 20 的实测值包含少量欠账回落和采样边界差异，不能解释成持续超目标速度。r1/r2 主线程延迟门槛通过；r3 虽功能、资源核账及重载通过，但不是满速。r3 的混合机型夹具已修正同一输入对应多个原生确定性产物的核账，比较旧轮时需保留这个工作负载差异。

与上一轮 `cw_hotfix_20261026_c_*` 相比，r2 off/on 实际推进约从 13.6/10.3 提升至 20.0/20.1，r3 从约 1.05 提升至 6.87；不把吞吐改善等同于所有档位 p99 一定下降。最新夹具还增加了所有 owner 冷编译完成的预热门槛，初轮 a 的冷编译/追赶混合数据不参与稳态结论。

## 2. 首段尾峰：主线程仍有同步冷重建与重复预冻结

更正：夹具在总测量进度的 1/3、1/2、2/3 发起三次暂停/恢复产线。它们触发运行时重建，但没有修改节点、配方或连线，不能作为真实结构编辑覆盖证明。实际主线程执行的链包括：

```text
CloudworksRuntimeService.serverTick
  -> VirtualSnapshot.lines -> NBT 复制 / ProductionLineData.read
  -> CloudworksNativeContracts.prime
     -> 遍历节点 / adapter 的冻结配方
     -> remember -> resource.write().toString()
        -> NBTTagCompound.handleEscape -> 正则匹配
```

解码已经从同作用域多次降为一次，但这一次仍在主线程，不能称为“冷路径成本消失”。`prime` 仍可能对同类型节点重复遍历相同的冻结配方，再重复生成同资源的 NBT 字符串键。

首段主线程 inclusive 采样（父子重叠，不相加）：

| 档位 | prime | remember（其内部） | VirtualSnapshot.lines | 首段最大事件耗时 |
| --- | ---: | ---: | ---: | ---: |
| r1 off | 194ms | 152ms | 32ms | 138.845ms |
| r1 on | 197ms | 142ms | 34ms | 144.169ms |
| r2 off | 181ms | 139ms | 97ms | 144.903ms |
| r2 on | 186ms | 151ms | 45ms | 146.522ms |
| r3 off | 735ms | 599ms | 559ms | 687.585ms |

源码和调用祖先明确支持：结构变动后的同步冷路径是首段长尾的重要贡献者。采样总量不是某一次 tick 的独立精确耗时，因此不声称表中 735ms 就是一个慢 tick。

下一步优先：按冻结 view/资源身份去重预注册，为真正不可变资源缓存序列化键，再评估分阶段的主线程冻结预算。不能把原生世界/能力读取直接搬到 worker。

## 3. 常态提交底噪：反复计算全注册表 layoutFingerprint

第二段（无新的结构编辑）主线程仍有：

```text
commitRuntimeCheckpoint -> runtimeCurrent -> CloudworksRegistries.layoutFingerprint
  -> 全部 SLOTS / MachineKey.hashCode / Objects.hash / NBT.hashCode
```

第二段 `layoutFingerprint` inclusive：r1 off/on 为 37/67ms，r2 off/on 为 228/205ms，r3 为 120ms。r2 off 的 CW 主线程总 inclusive 为 411ms，其中该指纹 228ms，约 55.5%；这是稳定提交中的重复工作，属于可继续优化的明确路径。

当前指纹故意检测“没有增加 registry version、但布局内容被修改”的情况。安全方案需要先建立可靠的布局变更版本或冻结所有权，再复用指纹；不能只缓存旧结果而取消内容变更检测。

## 4. r1 on 第二段：原版世界保存也进入事件区间

r1 on 第二段 p99=52.778ms、max=139.859ms，而该段 CW 主线程全部采样仅 94ms/6,763ms。同期有明确的原版主线程调用祖先：

```text
MinecraftServer.tick -> saveAllWorlds -> WorldServer.saveAllChunks
  -> ChunkProviderServer.saveChunks -> AnvilChunkLoader.writeChunkToNBT
  -> World.checkSessionLock -> FileInputStream.open0

MinecraftServer.updateTimeLightAndEntities -> WorldServer.tick
  -> ChunkProviderServer.tick -> saveChunkData -> AnvilChunkLoader.writeChunkToNBT
```

`writeChunkToNBT` 合计 inclusive 80ms，其中整世界保存分支 50ms、区块 tick 分支 30ms；会话锁文件打开共 33ms。r1 off 第二段也有整世界保存的同类调用。

这些路径在 HIGHEST START 到 LOWEST END 的计时范围内，说明该尾部包含原版世界/区块保存，不能全部归到 CW 的周期落盘。用户要求本体不改，本轮没有改原版/Mek。仍不能仅凭合并栈，把某个具体 52.778ms tick 与其中一条栈一一对应。

## 5. r3 第二段：重点怀疑 GC / 调度，不能伪造逐帧证明

r3 第二段没有 `prime`/`VirtualSnapshot.lines` 采样，也没有 CloudWorks IO 门禁等待，p99 仍为 114.897ms、max=122.834ms：

- CW 主线程总 inclusive 339ms / 4,906ms，普通提交以 layoutFingerprint 等为主。
- IO 线程 4,894ms / 4,906ms 在等待，约 99.76%，不支持“磁盘持续读写把这一段卡住”的解释。
- worker 的 `CloudworksRuntimeCheckpoint.capture` 占 33,350ms / 73,590ms（45.3%，inclusive）。
- 其内部 `MachineNodeData.writeRuntimeDelta` 占 22,191ms（30.2%，inclusive），不能和上一项相加。
- after-warmup-2 到 finish 的 GC 计数从 338 增至 356，累计 GC 从 45,394ms 增至 47,358ms，增加 1,964ms。该区间包含正式测量、停止 profiler、排空和最终核账，不能把 1,964ms 全当作测量段停顿。

这支持把内存检查点/NBT 对象构造及 GC 压力列为下一优先怀疑。现有文件是汇总栈和稀疏 GC 探针，没有每个慢 tick 与具体 GC 事件的配对；也未排除操作系统调度或采样器自身开销。因此结论是“GC/调度型尾延迟需进一步逐 tick 关联”，不是“已证明每个 115ms 都由 GC 导致”。

后续最有价值的对象方向是：进度等热字段尽量留在不可变数值页中，在 GUI/耐久存档/真实交易边界构造必要的 NBT，保留内存进度发布与交易顺序。本轮分页共享主要减少版本数组复制，尚未消除活动节点每次检查点的 NBT 构造。

## 6. 样本和分位数边界

计时器使用 nearest-rank：`ceil(N*p)-1`。400 个样本的 p99 是第 396 个，即第 5 慢样本；第二段只有 134 tick 时，p99 是第 2 慢样本。故第二段 p99 可以明显高于整轮 p99，不能把两个不同样本集的分位数直接当成相同场景的变化。

所有 event duration 都不是完整服务端外循环 MSPT；三次暂停/恢复保留在正式压力流程中，控制本身在 END 采样后执行，其后冷重建会影响后续采样。少量尖峰、原版保存和 GC 都需要单独看，不能以平均主线程很低推断完全没有卡顿。

## 7. r4 容量边界

- 12G：完成 290 条线，1,187,840 节点 / 1,306,595 台后触发 `CAPACITY_GC_THRASH_STALL_GUARD`，0 测量 tick；保护探针的 Full GC/墙钟比约 97.6%。
- 16G 唯一重试：完成全部 400 条线、1,638,400 节点 / 1,802,200 台，但首次运行/编译阶段仍触发同类保护，0 测量 tick。

两次都是测得的 GC 停滞保护，不是假称原生 OOM；没有可用 TPS、p99 或重载通过结论。新冷解码复用和限流没有使该极限规模在本次内存预算下通过，仍需处理常驻对象体积和冷编译的同时存活数据。不会追加第三次更大内存重试。

## 8. 证据与复现

- 汇总：`build/reports/cloudworks-full-scale/cw_phase2_20261026_b-summary.json` / `.md`。
- 完整定向栈与祖先：`build/reports/cloudworks-full-scale/cw_phase2_20261026_b-p99-evidence.json`。
- 各 runId 目录：原始 result.json、metrics、运行配置、源码/字节码清单、两段本地 spark、spark-analysis.json、GC 探针。
- 分析器：`python src/fullScaleStress/tools/analyze_p99.py cw_phase2_20261026_b`。

本次诊断之后未继续更改生产算法，也未为了降低分位数删除编辑、关掉 force 或篡改已有结果。后续若要确定每个尖峰的直接原因，应增加带 tick 时间戳的慢 tick/GC 对照再做针对性复现。

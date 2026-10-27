# 云端记录数据结构(NBT 树)

对应类:`mekanism.cloudworks.common.data.CloudworksData`(read :144 / writeCore :480)
持久化分四个文件:cloud.dat(核心配置)、receiving.dat / transfer.dat / sending.dat(三仓)。
全量事务序列化(write/writeProjection)是 cloud.dat + 三仓内联的合并形态,快照/回滚/持久意图用它。

```text
云端核心 cloud.dat — CloudworksData (Compound, schema=1)
├─ 版本号 schema: Int                                    固定 1
├─ 所有者 owner: String                                  UUID
├─ 启用 enabled: Boolean
│
├─ 云能量池 energy: Double                                加工总池(0..maxEnergy,禁 NaN/Inf)
├─ 云能量上限 maxEnergy: Double
├─ 无限能量 unlimitedEnergy: Boolean                      创造/管理员;true 时存量恒 0
├─ 收货中继能量 receivingEnergy: Double                   上传口富余先落这
├─ 发货中继能量 sendingEnergy: Double                     下载口从此回充
├─ 收货中继容量 receivingMaxEnergy: Double                可选键(旧档回落云池容量)
├─ 发货中继容量 sendingMaxEnergy: Double                  同上
│
├─ 能量流闸门 ×4(Boolean)
│   ├─ flowReceivingToCloud                              收货中继 → 云池
│   ├─ flowReceivingToLines                              收货中继 → 各线池直喂
│   ├─ flowCloudToSending                                云池 → 发货中继(可下载能量)
│   └─ flowLinesToSending                                线池 → 发货中继
│
├─ 云端升级池(未装进任何线的持有数)
│   ├─ speedUpgradePool: Int                             ≥0
│   ├─ energyUpgradePool: Int                            ≥0
│   └─ customUpgradePool: Compound(可选)                 自定义升级池: 命名空间类型ID → Long计数(≥1)
│                                                        64 类型上限;未知集成条目原样保留(资产不灭)
│
├─ 卡片效果落点(散字段 = 内置效果唯一真值)
│   ├─ offlineProcessingUnlocked: Boolean                离线加工解锁
│   ├─ unlockedLines / maxLines: Int ×2                  线数解锁/上限
│   ├─ workRate: Int                                     工作速率(≥1)
│   ├─ outputMultiplierUnits: Int                        产出倍率刻度
│   ├─ maxMachineNodes: Int                              每线节点上限(默认 64)
│   ├─ warehouseCapMultiplier: Int                       仓库容量倍率(≥1)
│   ├─ simulatedHeight: Int                              模拟高度 0..256
│   ├─ lavaHeatCards: Int                                岩浆热卡 0..7
│   └─ netherEnvironmentCard: Boolean                    下界环境
│
├─ cloudEffects: Compound(可选)                          通用通道卡片效果等级表: 效果ID → Long等级(≥1)
│                                                        效果类型数无硬上限;声明于 CloudworksCardEffect.cloudState/
│                                                        unlockCloudState;Runtime 不解释等级(负清单);
│                                                        未知效果ID等级原样保留(已用卡片不灭失)
│                                                        内置效果仍走上方散字段,不进此表(无双真值)
│
├─ recycleToTransfer: Boolean                            回收箱路由:true=转仓(再加工)/false=发仓(下载)
│
└─ 产线清单 lineIds: List<String>                         UUID 列表,重复拒读;产线本体在各自文件

仓库文件 receiving.dat / transfer.dat — (Compound, schema=1)
├─ 仓库 inventory: Compound                              CloudworksInventory
│   ├─ schema: Int = 1
│   ├─ 容量 capacity: Long                               读取时强制 Long.MAX_VALUE(仓不设限,只有每类型软帽)
│   └─ 条目 entries: List<Compound>
│       └─ { 资源描述符 descriptor: Compound, 数量 amount: Long }
└─ 游标 cursor: Int                                      轮转指针(≥0;receivingCursor/transferCursor)

仓库文件 sending.dat — (Compound, schema=1)
└─ 仓库 sending: Compound                                结构同上,无游标
```

---

## 读入校验(poison-domain)

1. 字段集精确分组计数(布尔组 8/双精度组 6/整型组 3)
2. 能量净化:NaN/Inf/负数全拒;unlimited 时存量归零
3. 中继能量/升级池负数拒
4. 限额组:unlockedLines ≥0、maxLines ≥ unlocked、workRate ≥1、maxMachineNodes ≥1、warehouseCapMultiplier ≥1
5. simulatedHeight 钳 0..256、lavaHeatCards 钳 0..7(越界钳制而非拒读)
6. lineIds 重复拒读

---

## 关联的独立文件(同属云端域)

| 文件 | 内容 |
|---|---|
| 机器缓存 | 真机器实体(身份+变体队列,schema 2),详见 MachineCache |
| infinite_resources.dat | 特殊节点档案(无限资源解锁记录) |
| 各产线文件 | 见 PRODUCTION_LINE_NBT 文档 |
| 回收站 | CloudworksRecycleBin(schema 1,128 类型上限) |

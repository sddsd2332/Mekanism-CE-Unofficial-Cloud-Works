# 产线数据结构(NBT 树)

对应类:`mekanism.cloudworks.common.data.ProductionLineData`(read :64 / writeProjection :309)
每个产线一个文件,根标签为下述 Compound。

```text
产线 ProductionLineData (Compound, schema=1)
├─ 版本号 schema: Int                                    固定 1
├─ 产线id lineId: String                                 UUID
├─ 所有者 owner: String                                  UUID
├─ 名称 name: String
├─ 启用 enabled: Boolean                                 运行中标记;编辑必须停线
├─ 离线运行 runWhileOwnerOffline: Boolean                 主人下线后是否继续模拟
├─ 能量 energy: Double                                   线能量池当前值(0..maxEnergy,禁 NaN/Inf)
├─ 能量上限 maxEnergy: Double                            由机器档案+升级重算的缓存值(权威落盘)
├─ 显式路由 explicitRouting: Boolean
│
├─ 速度升级 speedUpgrades: Int                           线级池已装数(0..maxSpeedUpgrades)
├─ 能量升级 energyUpgrades: Int                          同上
├─ 速度升级上限 maxSpeedUpgrades: Int                     = Σ(节点机器数×策略上限),棘轮自愈重算
├─ 能量升级上限 maxEnergyUpgrades: Int                    同上
├─ 节点缓冲倍率 nodeBufferMultiplier: Int                 可选,缺省 1,≥1
├─ 收货→本线 energyFlowReceivingToLine: Boolean           产线级能量门(收货中继直喂本线)
├─ 本线→发货 energyFlowLineToSending: Boolean             产线级能量门(本线池直入发货中继)
├─ customUpgradePool: Compound(可选)                     产线自定义升级池: 类型ID → Long计数(≥1)
│                                                        64 类型上限;读入校验"节点安装≤本池"(逐类型),
│                                                        未背书拒读;未知类型计数原样保留
├─ extensions: Compound(可选)                             命名空间扩展 payload 容器:
│   └─ <命名空间ID>: { version: Int, payload: Compound } 16条/16KB单条/64KB总量/6层嵌套;
│                                                        未知ID与版本错配条目原样保留(不执行);
│                                                        畸形条目隔离;超总量丢弃保护存档
│
├─ 机器节点 machines: List<Compound>                      元素=机器节点(见 MACHINE_NODE_NBT 文档)
│   └─ MachineNodeData (schema=6) ×N                     节点id 全线唯一(含通道),重复即拒读
├─ 连线 links: List<Compound>                             元素=连线
│   └─ 连线 ProductionLineLink (Compound, schema=1)
│       ├─ 连线id linkId: String                         UUID
│       ├─ 源节点 sourceNode: String                     UUID
│       ├─ 源端口 sourcePort: String                     端口名(如 item_output / 流体编解码器名)
│       ├─ 目标节点 targetNode: String                   UUID
│       ├─ 目标端口 targetPort: String
│       ├─ 启用 enabled: Boolean
│       ├─ 优先级 priority: Int
│       ├─ 比例 ratio: Double                            0..1;=1 或 0 时余数清零
│       ├─ 资源过滤 resourceFilters: List<Compound>       白名单;空=全放行
│       │   └─ 条目 { 资源描述符 descriptor: Compound }
│       └─ 比例余数 ratioRemainder: Double                比例分配的确定性连续性
├─ 通道节点 channels: List<Compound>                      元素=通道节点
│   └─ 通道 CloudworksChannelNode (Compound, schema=1)
│       ├─ 通道id id: String                             UUID,与机器节点共用唯一域
│       ├─ 模式 mode: String                             RECEIVE_INPUT(产线输入)/ SEND_OUTPUT(产线输出)/
│       │                                                TRANSFER_INPUT(中转输入)/ TRANSFER_OUTPUT(中转输出)
│       ├─ 档案 archetype: String                        可选;特殊节点档案 id(如无限资源)
│       ├─ 启用 enabled: Boolean
│       ├─ 待补蓝图 waitingBlueprint: String              可选,UUID
│       ├─ 横坐标 x: Int / 纵坐标 z: Int
│       ├─ 缓冲 buffer: Compound                         通道库存(CloudworksInventory)
│       │   ├─ 版本号 schema: Int = 1
│       │   ├─ 容量 capacity: Long
│       │   └─ 条目 entries: List<Compound>
│       │       └─ { 资源描述符 descriptor: Compound, 数量 amount: Long }
│       └─ 资源清单 resources: List<Compound>             通道声明管理的资源及数量
│           └─ { 资源描述符 descriptor: Compound, 数量 amount: Long }
└─ 动态资源显示序 dynamicResourceDisplayList: List<String>  GUI 资源排序(字符串列表)
```

---

## 读入校验(poison-domain 严格模式)

除机器节点自身的 8 条外,产线级还有:

1. **字段集精确校验**:分组计数(lineId/owner/name 各必在;布尔组、升级组、能量组数量精确)——多键少键都拒
2. **升级范围**:0 ≤ 已装 ≤ 上限;负数即拒
3. **节点 id 唯一域**:机器节点之间、通道与机器之间,id 重复 → 整线拒读(P1-12:静默合并会双计机器/资源/产物)
4. **连线 id 唯一**:重复拒读
5. **预算防线**:读入即跑 `validateBudget`——钉选总和超过线池持有 → "Unbacked" 拒读
6. **能量净化**:NaN/Infinity/负数永不入池(结算边界)
7. **缓冲倍率**:≥1

---

## 与其他文件的关系

- **机器节点**:本文档 machines 列表元素,详见 `MACHINE_NODE_NBT.zh-CN.md`
- **owner 云端记录(CloudworksData,schema 1)**:升级池计数、卡片效果、workRate、能量中继、三仓(收/转/发)等,产线文件不含这些
- **机器缓存(machines 缓存,schema 2)**:真机器实体按身份+变体队列存放,产线节点只持有数量声明

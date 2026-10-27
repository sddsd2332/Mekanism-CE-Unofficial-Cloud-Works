# 云工坊原生团队：结构与持久化

更新日期：2026-10-15。

本文描述当前实现的字段。代码入口位于 `common/team`，可选 FTB 适配器位于
`common/integration/ftb`。以下 SNBT 是二进制 NBT 的可读示例，不是 JSON 配置文件。

## 1. 身份边界

- 团队 ID 是云工坊创建的随机 UUID，不是队长的玩家 UUID，也不是 FTB 队伍 UID。
- 团队云的 owner 使用这个团队 UUID；个人云仍使用玩家 UUID，两个数据域独立。
- 队长变更不改变团队 UUID，因此不会换仓库、产线或机器缓存目录。
- 同名团队解散后重建，会得到新的 UUID。
- 无 FTBLib 时，团队终端、端口、卡、团队管理和回收功能仍然存在。
- FTBLib 在场时，只提供成员关系同步。运行时权限和在线判断始终读取云工坊自己的索引。

## 2. 内存数据

`CloudworksTeamRegistry` 发布只读状态；修改操作复制记录，原子写入成功后才发布新状态。

| 索引 | 内容 | 是否持久化 |
| --- | --- | --- |
| `teams` | 团队 UUID → 原生团队记录，包括尚未完成回收的解散团队 | 写入 `teams.dat` |
| `players` | 玩家 UUID → 当前有效团队 UUID | 加载时从成员表重建 |
| `bindings` | FTB 的 `teamId#UID` → 当前绑定团队 UUID | 加载时从团队记录重建 |
| `online` | 本次服务器会话中在线的玩家 UUID 集合 | 不持久化 |
| `epoch` | 当前世界加载代次，拒绝旧世界的晚到异步操作 | 不持久化 |

一个玩家只能属于一个有效团队。已经进入解散阶段的旧团队仍可保留成员历史，
但不进入 `players` 和 `bindings` 有效索引，也不再授予访问权。

`CloudworksTeamSnapshot` 是只读投影，包含团队 ID、名称、队长、成员、权限、
绑定和解散状态。成员投影中的 `online` 来自本次会话的在线集合，不是从磁盘恢复。

## 3. 目录布局

```text
<世界目录>/cloudworks/
  teams.dat
  team-recovery.dat
  <团队 UUID>/
    cloud.dat
    receiving.dat
    transfer.dat
    sending.dat
    machine-cache.dat
    recycle.dat
    production/
      <产线 UUID>.dat
      <产线 UUID>.runtime.dat
    ports/
      <端口 UUID>.dat
    port-journal/
    special_nodes/
    transfer.pending.dat
    team-recovery.pending.dat
  terminals/
    <物理终端 UUID>.dat
```

待提交文件仅在对应事务存在时出现。个人云使用同样的云数据目录结构，
但目录名为玩家 UUID。物理终端文件统一按终端 UUID 保存，不属于某一个团队目录。

## 4. `teams.dat`

根节点字段：

| 字段 | NBT 类型 | 含义 |
| --- | --- | --- |
| `schema` | Int | 当前为 `1` |
| `teams` | List&lt;Compound&gt; | 所有有效团队以及待回收团队 |

一个团队的完整可读示例：

```snbt
{
  schema: 1,
  teams: [
    {
      schema: 1,
      id: "b1e28997-b71f-4ed7-b611-094c1b506912",
      name: "Example Team",
      owner: "18bdd187-165a-4b11-8aaf-0c1ec0510af7",
      members: [
        {
          id: "18bdd187-165a-4b11-8aaf-0c1ec0510af7",
          name: "Leader"
        },
        {
          id: "9efb139a-0a38-4547-964c-a3ca5a82012c",
          name: "Member"
        }
      ],
      denied: [
        "9efb139a-0a38-4547-964c-a3ca5a82012c"
      ],
      invitations: [
        "8491a7e9-2fe8-43fb-a845-76d34d59b774"
      ],
      ftbBinding: "example_team#17",
      ignoredFtbBinding: "",
      dissolving: 0b
    }
  ]
}
```

字段说明：

- `id`：团队 UUID，也是团队云数据域的 owner。
- `owner`：当前队长的玩家 UUID。队长同时必须存在于 `members` 中。
- `members`：成员的玩家 UUID 和缓存名称。队长也在列表中。
- `denied`：被关闭云访问权限的成员 UUID；不代表踢出团队。队长不能被禁止访问。
- `invitations`：已邀请但尚未接受的玩家 UUID。邀请不会直接改变对方的团队关系。
- `ftbBinding`：可选 FTB 绑定，未绑定时为空字符串。
- `ignoredFtbBinding`：主动解绑时记住原绑定，避免下一次自动对账立刻重新绑定。
- `dissolving`：`0b` 表示有效团队；`1b` 表示已解散、等待回收完成。

`online` 不写入成员 NBT。服务器重启后，不能根据旧的在线状态恢复运行资格。

## 5. 团队云 `cloud.dat`

团队成员和 FTB 绑定不放在 `cloud.dat`，只放在 `teams.dat`。
团队云复用个人云的 `CloudworksData` 格式，区别是 `owner` 使用团队 UUID。

当前核心字段和类型如下：

| NBT 类型 | 字段 |
| --- | --- |
| Int | `schema`、`unlockedLines`、`maxLines`、`workRate`、`outputMultiplierUnits`、`maxMachineNodes`、`warehouseCapMultiplier`、`simulatedHeight`、`lavaHeatCards`、`speedUpgradePool`、`energyUpgradePool` |
| String | `owner` |
| Double | `energy`、`maxEnergy`、`receivingMaxEnergy`、`sendingMaxEnergy`、`receivingEnergy`、`sendingEnergy` |
| Byte/Boolean | `enabled`、`offlineProcessingUnlocked`、`unlimitedEnergy`、`netherEnvironmentCard`、`recycleToTransfer`、`flowReceivingToCloud`、`flowReceivingToLines`、`flowCloudToSending`、`flowLinesToSending` |
| List&lt;String&gt; | `lineIds` |

三仓中的资源分别保存在 `receiving.dat`、`transfer.dat` 和 `sending.dat`，
不重复嵌入 `cloud.dat`。机器缓存、回收站、特殊资源授权和产线使用既有独立文件。
事务投影可能增加内部水位字段，不应由整合包脚本修改。

## 6. 解散与回收记录

解散首先将原生团队设为 `dissolving: 1b`，撤销有效成员索引和端口转移权限。
然后由结构命令 worker 建立既有回收记录并停用团队云。
如果两步之间中断，下次启动会根据仍保留的原生解散记录重试。

`cloudworks/team-recovery.dat`：

```snbt
{
  schema: 1,
  entries: [
    {
      owner: "b1e28997-b71f-4ed7-b611-094c1b506912",
      teamId: "Example Team",
      teamUid: 0s,
      lastOwner: "18bdd187-165a-4b11-8aaf-0c1ec0510af7",
      status: "DISSOLVED"
    }
  ]
}
```

这里的 `owner` 是团队 UUID，`lastOwner` 是最后队长的玩家 UUID。
为复用既有回收格式，`teamId` 字段在原生团队流程中保存团队显示名称，
`teamUid` 写 `0`，不再承担 FTB 身份含义。

回收阶段依次使用：

```text
DISSOLVED → RECOVERING → TARGET_COMMITTED → DRAINED
```

`<团队 UUID>/team-recovery.pending.dat` 是回收事务的可重放目标快照：

```text
schema: Int = 1
owner: String = 来源团队 UUID
recipient: String = 接收玩家 UUID
cloud: Compound = 接收方完整云投影
machineCache: Compound = 接收方机器缓存投影
specialNodeDefinitions: Compound = 合并后的特殊节点定义（需要时）
infiniteResources: Compound = 合并后的无限资源授权（需要时）
```

回收包括三仓、回收站、机器节点缓存、团队端口缓存、机器缓存、升级池、
产线已安装升级和存储能量。容量不足时保留来源数据，不删除团队目录。
云端扩容额度不转移。成功完成后，删除来源团队目录、回收索引项及原生解散记录。

## 7. GUI 与网络

团队管理窗口复用 Mek 的 `GuiTextScrollList`、输入框和按钮。
成员、在线状态、访问开关、FTB 绑定及邀请通过 `CloudworksGuiCodec`
的明确字段同步，不由 GUI 直接读取持久化 NBT 或 FTB Universe。

服务端权限只认原生有效成员表与 `denied`。客户端显示快照不能为服务端授权。
排队的蓝图、编辑与结构操作会在 worker 执行前重新检查团队访问权。

手动管理以团队 GUI 为主；`/cloudworks-team info`、`leave` 和
`join <团队 UUID>` 提供成员关系操作的备用入口，禁止访问团队云的成员也能自行离队。
接受邀请仍必须通过服务端邀请校验，不能凭团队 UUID 任意加入。

## 8. FTB 适配器

- 所有 `com.feed_the_beast` 的生产代码引用均在 `common/integration/ftb` 内。
- 不使用反射，不使用 FML 自动事件订阅注解。
- FTBLib 在场时手动注册事件监听，在其 Universe 完成加载后启动对账。
- 加入事件懒创建或更新绑定；离开事件移除成员；队长转移同步队长；
  删除事件只使精确绑定的云工坊团队进入解散回收。
- 绑定使用 `teamId#UID`，不同 UID 不会被当成同一个绑定。
- 未安装 FTBLib 时不对绑定做外部对账，团队仍可原生管理。
- 原 `CloudworksTeamIdentityIndex`、FTB `TeamData` 访问覆盖层及其身份派生链已移除。

开发环境默认不加入 FTB 运行时。需要联动运行时可使用：

```text
gradlew.bat test -PcloudworksFtbRuntime
gradlew.bat runClient -PcloudworksFtbRuntime
```

团队索引只在实际结构变化时写盘；登录和登出更新内存在线集合，
不会因为一个在线标志变化而反复保存整个索引。

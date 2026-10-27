# 特殊节点与无限资源卡

## 当前实现

- 删除资源汇、独立无限水卡及旧特殊节点容量卡。
- 无限水与其他无限资源统一为 `cloudworks:infinite_resources`，入口名称为“无限资源输出”。
- 玩家默认没有该入口。成功使用第一张无限资源卡后，编辑器才显示它。
- 节点可以放置任意多个；每个节点只能选择一种已解锁资源。资源自身可以配置跨产线放置额度。
- 选择资源后，节点名称显示“无限资源输出（气体：氧气）”等形式，左上角图标使用 QIO 资源渲染器显示所选物品、流体或气体。
- 蓝图只保存节点类型和资源选择，不授予权限，也不携带来源玩家的资源规则。导入、等待补足和编辑提交以目标玩家的资源文件为准。

## 卡片 NBT

唯一物品 ID：`cloudworks:cloud_infinite_resource_card`。

```snbt
{Resources:"gas:Oxygen"}
```

```snbt
{Resources:"fluid:water"}
```

```snbt
{Resources:"item:minecraft:coal_ore",data:{}}
```

`data` 可省略，默认为空复合标签。`Data` 是等价写法，但不能同时提供两者。

资源标识：

| 格式 | 含义 |
| --- | --- |
| `item:namespace:item` | 元数据为 0 的物品 |
| `item:namespace:item@1` | 指定物品元数据 |
| `fluid:water` | Forge 流体注册名 |
| `gas:Oxygen` | Mek 气体注册名，不区分大小写 |
| `descriptor:自定义资源标识` | 配合 `Descriptor`，接入其他已注册 QIO 资源编解码器 |

可以提供可解析的完整 QIO `Descriptor` 复合标签，保留物品或流体 NBT 变体。对于 `item`、`fluid`、`gas` 简写，完整描述必须与 `Resources` 指向同一物品或资源；不能用描述替换成另一种资源。

空卡、缺失 `Resources`、错误 NBT 类型、不存在的资源、不可解析的描述或已知控制字段无效时，右键拒绝并提示“卡已损坏，请重新获取”。拒绝不会消耗卡片或修改云端资源数据。

示例命令：

```mcfunction
/give @p cloudworks:cloud_infinite_resource_card 1 0 {Resources:"fluid:water"}
/give @p cloudworks:cloud_infinite_resource_card 1 0 {Resources:"gas:Oxygen",data:{outputAmount:1000L}}
/give @p cloudworks:cloud_infinite_resource_card 1 0 {Resources:"item:minecraft:coal_ore",data:{unlimitedPlacement:0b,nodeLimit:4L,outputAmount:64L}}
```

无限资源卡不提供默认生存合成配方，避免合成出无法使用的空卡。整合包或服务器应通过脚本、奖励或命令发放带 NBT 的卡片。

## 独立发卡配置

启动后生成 `config/cloudworks-infinite-resource-cards.cfg`，不使用主配置 `cloudworks.cfg`。
配置使用 Mek 的 `StringListOption`，只决定创造标签中的预置卡，JEI 可通过物品子类型列表获取它们。
修改后需要重启游戏；多人环境中客户端也应安装相同的发卡配置，配置不会通过服务器参数包同步。

```text
infinite_resource_cards {
    S:infiniteResourceCards <
        fluid:water
        item:minecraft:coal_ore|nodeLimit=1,outputAmount=16
        item:minecraft:coal_ore|nodeLimit=4,outputAmount=256
        gas:oxygen|nodeLimit=4,outputAmount=1000
        item:minecraft:coal@1
    >
}
```

默认列表仅有 `fluid:water`。每行的 `Resources` 使用与卡片完全相同的解析器。
项目内默认配置示例位于 `examples/config/cloudworks-infinite-resource-cards.cfg`。
可选 `|` 后缀仅接受 `nodeLimit`、`outputAmount`，数值必须为正整数且不超过 `Long.MAX_VALUE`。
指定 `nodeLimit` 自动生成 `unlimitedPlacement:0b`；只有 `outputAmount` 后缀时仍为无限放置。
无后缀表示不限放置、最大输出。

创造标签始终先显示空白卡，再按配置顺序显示合法预置卡。空白卡不可使用。
不存在的资源、非法后缀、重复参数或非法数值会记录原条目和原因并跳过，不阻止后续条目。
空列表或全部无效时仍保留空白卡。配置中相同卡片可重复展示。
删除配置条目不会撤销已经发出的卡片，也不会删除玩家已解锁资源或修改产线权限。

## 基础 data 解析

| 字段 | 类型 | 缺省值与行为 |
| --- | --- | --- |
| `outputAmount` | 正整数，支持 byte/short/int/long | 最大值 `Long.MAX_VALUE`，实际转入量仍受下游可接收空间限制 |
| `unlimitedPlacement` | `0b` 或 `1b` | `1b`，该资源可被任意多个节点选用 |
| `nodeLimit` | 正整数 | `unlimitedPlacement:0b` 时必须提供；统计当前玩家全部产线中选用该资源的节点，包括停用和等待蓝图的节点 |

所有控制都在资源条目的 `data` 内，基础定义不新增固定速率、缓存、能耗或离线规则字段。

第一张卡片的未知 `data` 字段会原样保存，但不会自动获得执行语义。新行为需要新增明确的解析器；不能通过任意 NBT 执行代码。

同资源后续卡采用逐字段取优的棘轮升级：

- `outputAmount` 取新旧最大值。
- `nodeLimit` 取新旧最大值，不再累加。
- `unlimitedPlacement` 取逻辑 OR，不能从无限放置降回有限。
- 保留旧条目的未知 `data` 字段，忽略新卡的未知字段。

例如额度 1、输出 16 的卡升级为额度 4、输出 256，最终是额度 4、输出 256，不是额度 5。
混合优劣的卡只提高更优字段；任一实际效果提升才消耗一张卡，并保留已有节点及资源选择。
完全相同或更差的卡拒绝且不消耗，提示“这张卡无法提升当前效果”。
无限放置但输出受限的资源仍可提高输出；无限放置且最大输出才是无法继续提升的上限状态。

输出缓存每 tick 补到资源允许的输出量，下游堵塞时不会无限累积有限配额。Runtime 只读取冻结规则，不在 tick 热路径读取磁盘或解析卡片 NBT。

## 玩家数据文件

```text
<世界存档>/cloudworks/<玩家 UUID>/special_nodes/
  special_nodes.dat
  infinite_resources.dat
```

`.dat` 使用与项目其他文件相同的 NBT 格式。卡片使用通过现有异步云端事务日志写入这两个文件；并发更新使用同一玩家的管理域。

`special_nodes.dat` 的结构：

```snbt
{
  schema:1,
  nodes:[{
    id:"cloudworks:infinite_resources",
    displayName:"cloudworks.special.infinite_resources",
    icon:{id:"minecraft:paper",Count:1b,Damage:0s},
    type:"SOURCE",
    data:{}
  }]
}
```

`infinite_resources.dat` 的结构示意：

```snbt
{
  schema:1,
  id:"cloudworks:infinite_resources",
  displayName:"cloudworks.special.infinite_resources",
  icon:{id:"minecraft:paper",Count:1b,Damage:0s},
  type:"SOURCE",
  data:{},
  resourceTypes:["gas","item"],
  resources:{
    gas:{
      oxygen:{
        resourceId:"oxygen",
        descriptor:{/* QIO 气体资源描述 */},
        data:{outputAmount:1000L}
      }
    },
    item:{
      "minecraft:coal_ore":{
        resourceId:"minecraft:coal_ore",
        descriptor:{/* QIO 物品资源描述，包含原生 NBT */},
        data:{unlimitedPlacement:0b,nodeLimit:4L}
      }
    }
  }
}
```

同注册名的多个原生 NBT 变体使用不同描述保存；文件键会追加 `#1` 等后缀避免覆盖。

`SOURCE` / `SINK` 是数据定义可表达的方向。当前只实现无限资源 `SOURCE` 的基础解析器，不再注册垃圾箱式资源汇节点。修改存档文件应停服后进行，再启动重新加载；运行中的玩家数据使用缓存快照。

## 文件自愈

- 两份文件分别校验。文件损坏时移名为 `<原名>.corrupt-yyyyMMddHHmmss`，保留原始字节并记录玩家、文件和失败原因。
- 一份文件损坏不会丢弃另一份文件中的健康数据。节点定义损坏后已有可用资源仍保留，下一张有效卡会重新生成节点定义。
- 模组移除后不可解析的资源只从本次运行的可用条目中跳过，并记录告警。剩余资源可继续使用；卡片解析仍然严格。
- 恢复后的快照正常缓存，不会每次 GUI 刷新重新读盘。
- 若损坏文件无法移名，查看仍降级为空或健康数据，但用卡写入被阻止，避免覆盖无法备份的原文件。
- 自愈仅处理 `special_nodes/` 下的 `special_nodes.dat` 和 `infinite_resources.dat`，不改变云端其他文件的读取/故障隔离规则。

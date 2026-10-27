# 终端上传权限与脚本奖励入库

## 配置

`config/cloudworks.cfg` 的 `cloudworks` 分类新增：

```cfg
B:terminalItemUploadsEnabled=true
```

默认开启，修改后重启服务器或重新进入世界生效，并同步到客户端。

关闭后：

- 手持终端、方块终端、团队终端都不能上传机器、升级或能量扩容物品。
- 对应输入槽拒绝新物品，上传按钮禁用，升级输入不再自动入库。
- 服务端同时拒绝上传发包及旧的背包添加／合并机器入口，不能只靠客户端绕过。
- 槽内已有物品仍可取回；原有云端资产、下载、产线内升级分配及物理上传／下载端口不受影响。
- 本文的公共 API／CRT 奖励入库不受此开关影响。

## CRT 接口

导入：

```zenscript
import mods.cloudworks.Storage;
import crafttweaker.player.IPlayer;
import crafttweaker.item.IItemStack;
```

这些方法用于服务器运行期间的任务奖励、自定义合成回调等场景，不是启动阶段的机器注册方法：

| 方法 | 作用 |
| --- | --- |
| `Storage.addMachine(player, item)` | 按物品栈数量将已注册机器加入玩家机器缓存 |
| `Storage.addMachine(player, item, amount)` | 指定机器数量，物品栈仅提供身份与 NBT |
| `Storage.addUpgrade(player, item)` | 根据物品绑定识别原生／自定义升级，数量取物品栈数量 |
| `Storage.addUpgrade(player, item, amount)` | 根据物品绑定识别升级并指定数量 |
| `Storage.addUpgradeType(player, type, amount)` | 按升级类型 ID 入库，不要求该类型绑定物理物品 |
| `Storage.uploadMachine/downloadMachine` | 按机器完整 NBT 增加／扣减云端机器缓存 |
| `Storage.uploadUpgradeType/downloadUpgradeType` | 增加／扣减云端升级池 |
| `Storage.uploadResource/downloadResource` | 增加／扣减默认收货仓／发货仓资源 |

原生类型可以写 `"speed"`／`"energy"`，也可以写 `"mekanism:speed"`／`"mekanism:energy"`。自定义升级使用完整命名空间 ID，且必须已经注册。

指定团队云时使用以下方法，`owner` 为云端所有者 UUID 字符串：

```zenscript
Storage.addMachineTo(player, owner, item, amount);
Storage.addUpgradeTo(player, owner, item, amount);
Storage.addUpgradeTypeTo(player, owner, type, amount);
```

不指定所有者时始终入玩家个人云，不会跟随当前打开的团队终端。团队写入会复验该玩家是否有目标云访问权限，不允许任意指定其他玩家 UUID。

## 结果与调用约定

所有方法立即返回 `mods.cloudworks.StorageResult`，实际校验与入库在后台完成：

| 属性 | 含义 |
| --- | --- |
| `done` | 请求是否已结束 |
| `accepted` | 是否全量接收入库 |
| `amount` | 已接收数量；失败或未完成为 0 |
| `status` | `PENDING`、`ACCEPTED`、`DENIED`、`DISABLED`、`INVALID_ITEM`、`INVALID_AMOUNT`、`UNAVAILABLE`、`STORAGE_ERROR` |
| `error` | 失败的语言键；未完成或成功为空字符串 |

`accepted` 表示云端内存与原有持久化队列已经接收，不表示已完成硬盘 fsync。不要在主线程等待结果，更不能在 `done == false` 时反复重发同一奖励；每一次调用都是一笔独立入库，不自带任务奖励去重。

## CRT 云端传输

传输接口同样是非阻塞的，但语义不同于 `add*` 奖励接口：

```zenscript
val uploaded = Storage.uploadMachine(player, <mekanism:basic_smelting_factory>, 4);
val downloaded = Storage.downloadMachine(player, <mekanism:basic_smelting_factory>, 2);
val input = Storage.uploadResource(player, Resources.item(<minecraft:coal> * 16));
val output = Storage.downloadResource(player, Resources.item(<minecraft:diamond> * 4));
val speed = Storage.downloadUpgradeType(player, "speed", 2);
```

- `upload*` 只修改云端增加数量；整合包必须先从 MMCE 的 IOInventory 或其他外部库存扣除原料。
- `download*` 只修改云端扣减数量；整合包收到成功结果后，负责把 `TransferResult.machines` 或资源数量写入 MMCE 输出库存。
- 机器、升级和资源都提供 `...To(player, owner, ...)` 版本，可指定团队云所有者 UUID；仍会执行团队权限校验。
- 机器下载返回完整 NBT 栈列表，最大单次下载量沿用云端机器下载上限；资源下载返回实际数量。
- 资源默认上传到收货仓、下载自发货仓。Java API 可通过 `CloudworksTransferRequest` 指定收货／中转／发货仓。
- 云工坊不会观察、锁定或直接修改 MMCE 的外部库存；两边之间的 exactly-once 物理搬运由整合包负责。若需要跨崩溃重试，整合包应使用自己的事务 ID 和库存日志。
- 传输接口仍检查玩家云端激活、团队权限、服务器世界代次、机器身份、升级类型、容量和库存数量。终端上传开关不影响 CRT 传输。

`add*` 奖励接口的边界：

- 这是**奖励／产出入库**，不是物理库存转移。不会搜索或扣除玩家背包、箱子、管道中的物品，也不会缩减传入的物品栈。
- 整合包负责消费配方原料、任务完成校验与奖励只发一次。不要拿它当作物理上传原子事务。
- 不自动激活云工坊，不创建／开启陌生玩家云端，不安装升级到产线或节点。
- 不注册新机器。机器必须匹配现有注册身份，缓存保留其有效 NBT 变体。
- 数量必须为正；机器与自定义升级使用长整数计数，原生速度／能量升级池仍受 `Integer.MAX_VALUE` 限制。溢出拒绝整笔，不截断。
- 玩家对象必须对应服务器玩家。服务器关闭、换档或目标权限失效时，请求拒绝执行，不把旧任务应用到新世界。

传输接口的边界：

- 传输接口只提交云端一侧的原子变更，不接管外部库存；上传成功后由整合包扣除 MMCE 输入，下载成功后由整合包写入 MMCE 输出。
- 资源上传可能因仓库容量只接受部分数量；整合包必须按 `amount` 扣除外部库存，不能按请求数量盲扣。
- 下载机器的 `TransferResult.machines` 是完整 NBT 的防御性副本；如果外部库存写入失败，云端不会自动回补，整合包必须先做容量预检或自行记录补偿。

可复用的奖励函数示例，函数本身不会自动触发：

```zenscript
function rewardCloudMachines(player as IPlayer, supportedMachine as IItemStack) {
    val request = Storage.addMachine(player, supportedMachine, 4);
    // 在任务系统后续的服务器回调中检查 request.done，再读取 accepted/error。
    // 此处不能用循环等待，也不要因为 accepted 暂时为 false 就重复发送。
}

function rewardCloudSpeed(player as IPlayer) {
    val request = Storage.addUpgradeType(player, "speed", 8);
}
```

## Java 公共 API

附属使用同一套公共入口，CRT 不直接访问内部 Manager 或注册表：

```java
CloudworksAPI.deposit(actorUuid, cloudOwnerUuid,
        CloudworksDepositRequest.machines(machineTemplate, 4));

CloudworksAPI.deposit(actorUuid, cloudOwnerUuid,
        CloudworksDepositRequest.upgrades(upgradeItem, 8));

CloudworksAPI.deposit(actorUuid, cloudOwnerUuid,
        CloudworksDepositRequest.upgradeType(
                new ResourceLocation("addonmod", "custom_module"), 1));
```

返回 `CompletionStage<CloudworksDepositResult>`。请求会复制物品模板；回调不在玩家／世界线程，不应在回调内直接操作玩家背包、世界或 GUI。需要这些操作时由调用方切回服务器线程。

当前验证范围：配置同步、终端拦截、机器 NBT、升级映射、溢出拒绝、未知云、换档／关闭请求及真实 ZenScript 解析夹具已纳入本次全量 `cleanTest test`，148 个测试类、834 项测试全部通过。尚未进行本批实机验证；审核修复细节见 [机制批审核修复记录](CLOUDWORKS_MECHANISM_REVIEW_FIXES_20261017.zh-CN.md)。

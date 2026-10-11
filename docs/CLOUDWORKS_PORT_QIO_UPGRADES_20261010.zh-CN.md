# 云工坊端口 QIO 升级

日期：2026-10-10。

## 创造分类

- 新增“云工坊”创造分类，图标使用手持终端。
- 云工坊物品、方块及两种 QIO 插件统一在注册时加入该分类。

## 原生升级接入

- 上传和下载方块实现 `IUpgradeTile`，使用 `TileComponentUpgrade`。
- `GuiMekanismTile`、`MekanismTileContainer` 自动提供原生升级窗口、安装和卸载插槽。
- 两个端口均支持 `Upgrade.ENERGY`；不增加速度升级。
- 能量容量直接调用 `MekanismUtils.getMaxEnergy(this, BASE_MAX_ENERGY)`，遵守 Mek 普通升级和配置升级两套公式。
- 原有端口转移仍不耗能，能量升级扩展的是端口本地能量缓存。
- 云端下载事务使用端口升级后的实际容量，不再固定使用未升级容量。

## QIO 插件

| 插件 | 注册 ID | 安装目标 | 行为 |
| --- | --- | --- | --- |
| QIO 下载升级插件 | `cloudworks:qio_download_upgrade` | 上传方块 | 按标记从 QIO 提取到本地缓存，再由现有上传流程送入收货仓 |
| QIO 上传升级插件 | `cloudworks:qio_upload_upgrade` | 下载方块 | 将本地缓存内物品、流体和气体存入 QIO，不要求标记 |

- 两种插件各自最多安装一个，只允许安装到对应方向的端口。
- 使用本体 `QIOFrequency.massExtract`、`massInsert` 的模拟与执行接口。
- 按实际提取或接收量增减本地缓存，QIO 已满时保留尚未接收的资源。
- 沿用本体 QIO 输入/输出器的 10 tick 冷却写法，即两次执行检查间隔为 11 tick。
- 不改变可配置的云端上传/下载周期。
- QIO 转移在云端暂停判断之前执行，输出缓存清空会触发原有下载唤醒监听。
- 多个下载标记轮换优先级，每轮每种资源最多提取一个缓存位置的容量，避免首个资源持续占满共享缓存。
- 不处理 QIO 能量类型，也不增加 AE 兼容。

## 频率和资源配置

- 复用本体 `GuiQIOFrequencyTab` 和 `GuiQIOFrequencySelectWindow`，仅为电力端口补充数据适配。
- 频率列表同步沿用 `TileComponentFrequency`，设置和删除沿用 `PacketSetTileFrequency`。
- 自动转移检查频率有效性及端口所有者的访问权限。
- 上传方块的资源过滤窗口复用产线 `GuiLineResourceGrid`、`GuiLineResourcePickerWindow` 和共享客户端资源目录。
- 支持资源搜索、类型过滤、JEI 标记以及从实际 QIO 存储分页补充资源。
- 标记增删通过独立数据包提交服务端，检查当前容器、窗口 ID、交互权限及插件安装状态。
- 服务端回包同步过滤列表，并通知其他正在使用同一端口的玩家。
- 未安装插件时不显示对应 QIO Tab；无下载标记时不从 QIO 提取。

## 持久化

- 普通区块保存由 Mek 原生组件持久化升级和频率；资源标记作为端口配置持久化。
- 掉落物保存原生升级组件数据、频率和标记；重新放置恢复配置。
- 资源库存仍使用原有 PortRecord UUID 挂载，不回退到区块 NBT 保存资源。
- 上传/下载进度不保存。
- 区块卸载时释放端口挂载和频率组件关联。

## 验证

- IDE 构建通过，新增网络和过滤窗口文件错误检查通过。
- `gradlew build` 通过，共 317 个测试，无失败。
- 覆盖资源方向、容量模拟、部分接收、资源竞争轮换、升级公式、拆装恢复、数据包边界及升级后的云端能量下载。
- 夹具使用 Forge 原生 `setupTestHarness` 统一初始化 Loader，无新增反射实现。
- 尚未进行游戏内 GUI 和真实 QIO 存储联调；生存合成配方仍按此前约定延后。

# 云工坊 CraftTweaker 示例

English instructions and script: [README.md](README.md).

`cloudworks_complete.zh-CN.zs` 是本项目完整的 CRT API 示例，包含机器注册、资源与端口、配方、发电、多方块、热力及注册表查询接口，并列出 Java 自动注册的 Mekanism、MoreMachine 和 MultiblockMachine 机器族。脚本不会由模组或 Gradle 自动加载；复制到实例 `scripts` 目录前，必须将带标签的 `minecraft:stick` 示例身份替换为整合包专用物品。

Mekanism、MoreMachine 和 MultiblockMachine 的机器由 Java 兼容层自动注册，配方从各模组原生配方表冻结读取，因此 CRT 中不要重复注册这些身份。`Machines.dump()` 会将最终机器、参数和适配器信息写入 CraftTweaker 日志。

聚变、涡轮和裂变示例依赖 `mekanismgenerators`；未安装该模块时应保持注释。所有注册操作都在启动阶段应用；修改脚本后重启游戏和服务器。ZenUtils 重载不会重新构建已冻结的云工坊注册表。

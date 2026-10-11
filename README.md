# MekanismCeU-CloudWorks

Cloudworks 是面向 Minecraft 1.12.2 的 Mekanism 附属 Mod，提供基于虚拟机器节点的生产线模拟、云端资源与能量管理，以及适配器驱动的 Mekanism、MoreMachine 等机器配方支持。产线由 Cloudworks Runtime 异步推进；上传/下载端口负责与真实世界库存交互，并可通过 QIO 升级接入 QIO 网络。

## 工程边界

- Mod ID：`cloudworks`
- Java 根包：`mekanism.cloudworks`
- 目标 Minecraft：`1.12.2`
- 核心数据包括玩家云端系统、生产线、虚拟机器节点、云端库存、能源和升级。
- QIO、AE 及其他外部存储系统通过上传/下传边界接入，不直接参与云端生产线执行。

Cloudworks 不属于 Mekanism 本体，也不属于 `qioprocessing`。本工程的生产代码、测试和资源应保持独立。

## 开发依赖

开发环境使用本工程 `libs/` 中固定的 Mekanism 及相关开发 JAR。实际依赖以 `gradle/scripts/dependencies.gradle` 和 `gradle.properties` 为准。

## 开发指南

- [其他 Mod 接入与开发指南](docs/CLOUDWORKS_DEVELOPER_GUIDE.zh-CN.md)：依赖与注册时机、机器身份 NBT、资源端口、原生配方适配、异步准入、特殊机器和外部存储边界，包含 Mek 与 MoreMachine 的现有实现示例。
- [已接入机器与配方清单](docs/CLOUDWORKS_SUPPORTED_MACHINES_AND_RECIPES.zh-CN.md)：按中文 lang 名称列出当前已注册的机器、工厂等级/模式、原生配方表及已知限制。
- [云工坊配置说明](docs/CLOUDWORKS_CONFIGURATION_20261010.zh-CN.md)。
- [MoreMachine 可选兼容](docs/CLOUDWORKS_MOREMACHINE_COMPATIBILITY_20261010.zh-CN.md)。
- [Mek MultiblockMachine 大型机器兼容](docs/CLOUDWORKS_MULTIBLOCK_MACHINE_COMPATIBILITY_20261010.zh-CN.md)。
- [上传/下载端口 QIO 升级](docs/CLOUDWORKS_PORT_QIO_UPGRADES_20261010.zh-CN.md)。

接入机器需要同时声明 `MachineExecutionProfile`、`MachineSlotLayout`、`MachineIdentityPolicy` 和配方适配器；新适配器具体类还需声明可冻结的异步合同。外部附属应在 `postInit`、云工坊注册表冻结前完成注册，不直接访问或修改 Runtime 内部库存。

## 常用命令

```text
gradlew.bat clean
gradlew.bat test
gradlew.bat assemble
```

构建或测试通过后，仍应记录测试数量、失败项、生成 JAR 的 SHA-256，以及使用的依赖版本。

## 当前开发顺序

1. 保持工程构建和测试基线可重复。
2. 复核后端调度、持久化、库存和能源一致性。
3. 完成云端输入/输出方块的真实世界边界。
4. 在后端协议稳定后继续 GUI。
5. 以独立适配层接入 QIO、AE 和其他附属。

客户端请求必须由服务端重新校验。虚拟生产 worker 只处理不可变快照，不直接访问世界 TileEntity、QIO 频率或 AE 网格。

## 兼容性说明

开发阶段不兼容旧 Cloudworks 存档。测试世界和正式世界必须隔离，并保留数据版本、UUID、intent 和恢复语义，便于后续格式变更诊断。

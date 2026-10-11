# Mek MultiblockMachine 可选兼容

日期：2026-10-10。

## 1. 接入范围

对应本体模块 `mekanismmultiblockmachine`，不是将所有 Mek 多方块反应堆都纳入云端模拟。

机器名称取自本体 `assets/mekanismmultiblockmachine/lang/zh_cn.lang`：

| 名称 | lang key | 原生配方 / 云端合同 |
| --- | --- | --- |
| 大型化学灌注器 | `tile.LargeChemicalInfuser.name` | `CHEMICAL_INFUSER`，双气体输入、单气体输出 |
| 大型化学清洗机 | `tile.LargeChemicalWasher.name` | `CHEMICAL_WASHER`，气体与流体输入、气体输出 |
| 大型电解分离器 | `tile.LargeElectrolyticSeparator.name` | `ELECTROLYTIC_SEPARATOR`，流体输入、左右气体输出 |
| 大型太阳能中子活化器 | `tile.LargeSolarNeutronActivator.name` | `SOLAR_NEUTRON_ACTIVATOR`，模拟光照门、气体转换 |
| 大型风力发电机 | `tile.LargeWindGenerator.name` | 独立大型风机高度曲线、模拟环境发电 |
| 大型燃气发电机 | `tile.LargeGasGenerator.name` | `GAS_FUEL_TO_ENERGY_RECIPE`，复用现有云端燃料合同 |

共 6 个机器身份，无额外等级或模式 NBT。前四个只要求 MultiblockMachine 模块；两个发电机还检查 Generators 模块是否加载。

## 2. 可选加载

- `CloudworksHooks` 使用 `Loader.isModLoaded`，只在目标模块加载时调用独立兼容类。
- 目标模块的类引用集中在 `common/integration/multiblockmachine`，入口使用 `@Optional.Method`。
- 使用公开注册表、原生配置和配方 API，无新增反射、TileEntity 实例或 World 访问。
- Cloudworks 加入 `after:mekanismmultiblockmachine` 和 `after:mekanismgenerators` 的可选顺序。
- 在 `loadComplete` 的机器注册表冻结之前登记。
- 不将本体模块类打进 Cloudworks 的产物。
- 重复调用完整注册入口不会重复登记适配器。

## 3. 参数对应

本体配置来自 `MekanismConfig.current().multiblock`，基础能耗/储能取本体对应 `MachineType`。

| 机器 | 原生基础并行配置 | 默认并行 | 基础能耗 | 计入产线的基础储能 |
| --- | --- | ---: | --- | --- |
| 大型化学灌注器 | `LargeChemicalInfuserProcesses` | 256 | `CHEMICAL_INFUSER.getUsage()` / 执行单元 | `CHEMICAL_INFUSER.getStorage() × processes` |
| 大型化学清洗机 | `LargeChemicalWasherProcesses` | 256 | `CHEMICAL_WASHER.getUsage()` / 执行单元 | `CHEMICAL_WASHER.getStorage() × processes` |
| 大型电解分离器 | `LargeElectrolyticSeparatorProcesses` | 256 | 原配方 `energyUsage` / 执行单元 | `ELECTROLYTIC_SEPARATOR.getStorage() × processes` |
| 大型太阳能中子活化器 | `LargeSolarNeutronProcesses` | 256 | 0 | 0 |
| 大型风力发电机 | `LargeWindGeneratorProcesses` | 1024 | 0，环境发电 | `generators.windGeneratorStorage × processes` |
| 大型燃气发电机 | `LargeGasGeneratorProcesses` | 1024 | 0，消耗气体发电 | `general.FROM_H2 × 1000 × processes` |

处理机器默认周期为原机的 1 tick。`processes` 是一台机器的原生并行，不是 Java 线程数；Runtime 再按节点合并数量、产线升级和 workRate 放大。

参数在注册时捕获；修改配置后需要重新启动。当前不增加原生 `MultiblockMachineUpgrades.THREAD` 的云端独立升级，沿用此前约定的 workRate 机制。

### 固定缓存容量

- 大型化学灌注器的左、右、输出气罐均为 8,192,000。
- 大型化学清洗机的气体输入、流体输入、气体输出缓存均为 8,192,000。
- 大型电解分离器的流体输入为 81,920,000，左右气体输出各为 8,192,000。
- 大型太阳能中子活化器的气体输入和输出均为 8,192,000。
- 大型燃气发电机的燃料气体缓存为 8,192,000。

这些是原机固定容量，不再次按配置 `processes` 相乘；只有节点合并机器数量由 Runtime 正常放大容量。

## 4. 电解配方能耗

复用 `CloudworksSeparatorRecipeAdapter`，新增可选的原配方能耗读取方式：

```java
new CloudworksSeparatorRecipeAdapter(
        RecipeHandler.Recipe.ELECTROLYTIC_SEPARATOR.get(), true);
```

- 大型电解的 profile 基础能耗设为零。
- `recipe.energyUsage` 冻结到每 tick 配方能耗合同，由现有 Runtime 统一计算。
- 避免同时收取固定基础能耗和整份配方能耗。
- 每条配方可以有不同能耗，不在编译 worker 回读原生配方。
- 原有单参数构造保持原行为，本批不修改普通 Mek / MoreMachine 电解的计费方式。
- 两个气体输出明确使用 `gas_output_left`、`gas_output_right`。

## 5. 大型风机

- 不使用普通风机的 `windGenerationMin/Max` 代替大型风机配置。
- 原生风头比主方块高 46 格，云端按 `simulatedHeight + 46` 再钳制到大型风机高度范围。
- 使用 `LargeWindGenerationMin/Max`、`LargeWindGenerationMinY/MaxY` 的原生高度曲线和浮点运算方式。
- 云工坊风机不读取或应用原生维度黑名单；节点所在维度不限制模拟发电。
- 生成量乘传入执行单元数，不能在适配器内再次乘 `processes`。
- 冻结结果为 `CloudworksGenerationPlan.wind(...)`，worker 只读取数值计划。
- 无效高度范围、非有限值或零基础发电不会发布 NaN、负数或无限能量。

## 6. 资源与身份

- 机器执行身份默认不包含物品库存、当前能量、owner、安全或已安装物品的整份 NBT。
- 机器缓存依然保留上传物品的完整变体；执行身份和物品变体不是同一层。
- 复用现有冻结配方、operation、资源预留、checkpoint、取消和回收机制。
- 大型燃气发电机复用现有云工坊燃气合同，不另增主线程机器 Tick 或第二份燃料库存。

## 7. 云端边界

不模拟真实 World 相关行为：

- 结构占位方块、放置空间和机械朝向。
- 天空遮挡、区块加载和天气。
- 大型风机之间的距离干扰。
- 叶片伤害、损坏与爆炸。
- 邻接能量线、管道弹出和容器物品交互。

大型燃气使用现有云端“消耗配方气体数量并产生配方能量”的合同，不复刻真实方块的 `burnTicks` 余量或按油箱充满比例改变燃烧速度；这与之前等级燃气发电机的接入约定一致。

模块中的组件、堆叠升级、合成材料不是独立产线机器，不单独注册。

## 8. 验证

针对性夹具覆盖：

- 四个处理机器的真实注册、原生并行参数、能耗、储能、固定容量和重复调用。
- 完整库存与安全 NBT 不改变执行身份。
- 双气体、气液和太阳能配方冻结，太阳能光照门与零耗能。
- 电解配方独立能耗、左右输出和冻结后原生数据修改隔离。
- 发电分支、现有燃料合同复用、大型风机配置快照。
- 风头高度偏移、执行规模、不同维度下均可发电与异常配置。
- Runtime 中配方能耗不重复收费，零基础能耗也不能在无电时生产。

本批尚未进行游戏内多方块物品上传、服务器安装/卸载模块和大规模负载联调；夹具不替代这些验证。

本批 `gradlew build` 与 IDE 构建通过，全量 333 项测试，失败、错误、跳过均为零。产物包含兼容代码，不包含本体模块类或测试类。

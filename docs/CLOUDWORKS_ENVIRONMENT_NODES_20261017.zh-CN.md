# 节点环境：下界热源、熔岩模块与风力高度模块

日期：2026-10-17。实现以本次用户裁定为准，取代 GLM 草案中的 5×5、单面熔岩及云全域环境规则。

## 玩家行为

### 下界卡

- 使用一张下界卡，增加一个下界热源的放置额度。个人／团队沿用既有卡片授权入口。
- 额度通过通用 `CLOUD_STATE` API 保存为 `cloudworks:nether_heat_sources`；历史批次曾设 64，
 现按用户裁决直接读取 effect level，不设每轴上限。
- 编辑器环境节点列表显示“下界热源”。删除节点释放占用的额度，不撤销已经获得的授权。
- 节点列表分为“机器 / 输入输出 / 环境节点 / 辅助节点”。无限资源源与普通通道归入“输入输出”，其中无限资源源参与输入方向筛选；下界、海洋、森林、雨和雷暴归入“环境节点”，该页共用搜索、选中和放置入口，不受输入／输出方向筛选影响。
- 每个节点以实际卡片中心为中心，覆盖 `(WIDTH + GRID) × 5 = 780` 边长的正方形。
- 仅影响当前产线。机器卡片与任意一个源的相交面积必须严格超过自身面积的一半；恰好 50% 不生效。
- 两个源分别覆盖 30% 不能合成 60%。多个源命中同一机器只注入一份下界热。
- 接受外部热量的现有热契约可受益，包括热力发电、锅炉、蒸馏塔、燃料／电阻加热器和聚变外壳；裂变排除。
- 默认供热强度冻结自 Mek 的下界环境热设置；目标热计划显式声明下界环境热时使用其声明值。
- 停用源、移出范围或拆除源只停止后续供热，不清除已经储存的热量。

### 熔岩卡

- 上传后进入云端升级库，通过节点升级窗口安装、预留、提交和退还。
- 每台热力发电机最多 7 张，模拟六个邻面加机器自身位置的熔岩。
- 有效份数为 `floor(节点安装数量 / 节点机器数量)`，受接受策略的每台上限限制。
- 10 台机器安装 20 张时，每台获得两份熔岩热；余下不足整档的卡不额外产生热量。
- 每份使用目标热计划的 `lavaEnvironmentHeat`；与一份下界供热相加，燃料热仍独立结算。
- 模块只增加环境注热，不覆写整个热计划或热容。拆装不会重置温度。
- 发电热计划的功率上限已经对齐七个熔岩位置。

### 高度卡

- 上传、安装与退还同样使用通用自定义升级物流，不再右键改变云全域高度。
- 接入普通风力、大型风力和 MoreMachine 各等级风力。
- 每台每张增加 1 格模拟高度；节点有效高度份数按整档分配，不使用画布纵坐标。
- 保留各机型的原生高度发电曲线；大型风力保留转子偏移 `+46`。
- 接受上限取机型达到最高有效高度所需的安装数，并受模块声明的 256 上限约束。
- 升级窗口显示每台有效模块数、实际采样高度和高度上限。

## 渲染

- 红色外轮廓 `0xFFE65353`，半透明暗红底色 `0x306E141B`。
- 对源覆盖范围求并集，填充块的内部互不重叠；重叠处不加深，只保留并集外边界。
- 保留凹角、内部空洞和分离区域；不会把多个范围扩大成外接矩形。
- 位于范围内且可受热的机器显示红色命中标记。
- 支持画布平移、缩放、节点拖动和蓝图源节点的放置预览。
- 并集在源几何改变时重建，正常绘制复用缓存。
- 客户端显示合并不改变服务端逐源的严格半面积判定。
- 观察窗口读取服务端 `Node.areaHits`；编辑窗口通过共享目标筛选 API 计算当前草稿的实时命中数。

## 可复用 API

### 范围判定和并集

`mekanism.cloudworks.api.CloudworksAreaGeometry` 只包含不可变数值几何，不依赖 Minecraft 世界或客户端：

```java
CloudworksAreaGeometry.Rect area = CloudworksAreaGeometry.square(centerX, centerY, side);
CloudworksAreaGeometry.Rect target = new CloudworksAreaGeometry.Rect(left, top, right, bottom);
double fraction = CloudworksAreaGeometry.intersectionRatio(area, target);
boolean hit = CloudworksAreaGeometry.exceeds(area, target, 0.5);
boolean anySource = CloudworksAreaGeometry.covered(areas, target);
CloudworksAreaGeometry.Union union = CloudworksAreaGeometry.union(areas);
int nodeHeight = CloudworksAreaGeometry.nodeHeight(portCount);
CloudworksAreaGeometry.Target candidate =
    new CloudworksAreaGeometry.Target(nodeId, target, eligible);
List<CloudworksAreaGeometry.Target> candidates = Collections.singletonList(candidate);
Set<UUID> selected = CloudworksAreaGeometry.targets(areas, candidates, 0.5);
```

- `exceeds` 使用严格大于阈值，阈值范围为 0～1；零面积目标不命中。
- `covered` 是“至少一个源独立超过一半”，不累加多个来源的相交面积。
- `Union.fill` 是不重叠的填充矩形；`Union.edges` 是合并后的水平／垂直外轮廓线段。
- 输出列表及矩形不可变，可在冻结阶段、校验和客户端缓存之间复用。
- `nodeHeight` 统一空端口行、端口行高和卡片基高；环境源高度由零端口节点推导，服务端与画布不再各写一个字面量。
- `Target` 携带冻结后的资格标志；`targets` 统一筛选、严格阈值判断及多个来源去重。

### 客户端绘制

`mekanism.cloudworks.api.client.CloudworksAreaRenderer` 仅在客户端使用：

```java
CloudworksAreaRenderer.draw(union, fillArgb, borderArgb, borderWidth);
```

- 使用调用方当前的画布变换和裁剪范围。
- ARGB 颜色和边框宽度由调用方指定，不绑定下界效果。
- 保留调用前的 OpenGL 状态。按当前视口比例传入 `1 / scale` 可保持边框显示宽度稳定。
- 其他兼容内容可复用几何和渲染 API，并自行定义目标、阈值、授权及执行效果。

### 特殊节点资源要求

```java
CloudworksSpecialNodeType.source(id, translationKey)
    .withResourceSelection(false)
    .withPorts(Collections.emptyList())
    .withConfigValidator(validator);
```

- 默认要求单资源选择，保留无限资源节点的校验。
- `withResourceSelection(false)` 声明资源清单必须为空，蓝图校验读取原型声明，不再把“特殊节点”一概等同于资源输出源。
- 此声明只规定校验契约，不自动授予额外的运行时行为；环境特殊节点须通过 `withMachineModule(module)` 显式绑定模块。

### 模块声明与接受策略

- 内置类型：`MachineModuleType.lavaHeat()`、`MachineModuleType.height()`。
- 通过 `CloudworksAPI.registerMachineModule(type.withItem(stack))` 声明物品绑定。
- 各集成通过 `CloudworksAPI.acceptMachineUpgrade(machine, type, limit)` 声明接受策略。
- 两类模块复用已有 CUSTOM 升级库、原子扣款、节点分配、退还和蓝图需求；不制造一套独立资产系统。
- 环境标志、高度和熔岩模拟量通过 `ModuleEnvironment` 在快照边界投影；Runtime 不扫描画布或调用物品钩子。运行贡献通过统一模块生命周期计算，仅接收不可变上下文，见[机器模块 API](CLOUDWORKS_MACHINE_MODULE_API.zh-CN.md)。

## 开发期替换与边界

- 不迁移旧云全域 `simulatedHeight`、`lavaHeatCards`、`netherEnvironmentCard` 存档字段；读取忽略，再保存不写回。
- GUI 数据不再携带旧全域环境字段。下界额度通过通用效果级别投影，额外同步其他产线已占用数。
- `Overview` 仅保留基础构造入口和完整构造入口，旧高度／熔岩／下界占位参数及中间重载已删除；节点级高度和热计划系数仍保留。
- 旧个人和团队高度／熔岩物品均识别为对应节点模块；下载使用该模块的标准绑定物品。
- 本地原版多方块模块未提供大型热力发电机实体，未虚构新机器；现有或后续通过 API 接入的热契约可使用共用供热管线。

## 验证记录

- 新增几何、供热、按机器数量缩放、节点独立高度、跨产线额度、蓝图待补料及退还回归。
- IDE 构建及修改文件错误级静态检查通过。
- `gradlew cleanTest build --console=plain` 完整通过：150 个测试类、836 项测试，0 失败、0 错误、0 跳过。
- 已生成 `build/libs/MekanismCeU-CloudWorks-1.0.0.jar`，以及开发包与源码包。
- 客户端实机渲染和真实服务器冒烟尚未执行。

### 清理收尾验证

- 覆盖节点高度共用推导、命中数网络往返、精确半面积边界、拖动草稿即时预览及观察快照不随本地草稿变化。
- 清理后的 `gradlew cleanTest build --console=plain` 通过：150 个测试类、838 项测试，0 失败、0 错误、0 跳过。
- IDE 构建及修改文件错误级静态检查通过；安装包、开发包和源码包已重新生成。

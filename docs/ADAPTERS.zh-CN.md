# CloudWorks 机器适配器（IC2 / Thermal Expansion）

> **2026-10-26 当前端口规则**：见 [原机端口与升级对齐](CLOUDWORKS_NATIVE_PORT_ALIGNMENT_20261026.zh-CN.md)。本文历史批次中的 dynamo/tapper 无界仓、fisher 动态扩仓与独立鱼种概率流、matter 整批物品上限已被原生仓容＋连续进料合同替代；历史验收记录仅保留溯源用途。

> 2026-10-21 ｜ 实现者：GLM/GPT ｜ 状态：**TE 八批全部落地**（…动力学批＋锅炉涡轮批＋**复用冷却批**：@303/320/448/324/496/368/656/672/688 九件）、IC2 14 机全量、heat_sink 辅助节点（GPT）、通用配方编辑器、节点完成策略与概率副产倍率通道均已交付；源码级对账基准见 `ai/GLM/TE5_AUGMENT_SOURCE_AUDIT_20261020.zh-CN.md`；@704 已裁定不做
> 架构铁律：集成代码只住 `common/integration/te`、`common/integration/ic2` 与共享的 `CloudworksUpgradeCurves`；删除目录（连同 Hooks 接线）即移除集成；无对方 mod 环境全功能；编译期引用、运行期 `Loader.isModLoaded` + `@Optional.Method` 双重隔离（`CloudworksHooks`）。
> 实现细节与行号锚点：`ai/GLM/TE_IC2_INTEGRATION_IMPLEMENTATION_20261020.zh-CN.md`。

## 升级曲线（RandomComplement 反射快照）

`CloudworksUpgradeCurves` 启动期一次性反射 `RCConfig.IC2.overclockerTime/overclockerEnergy/energyStorageEnergy` + `RCConfig.COFHCORE.SocketLimitModified`，缺席/字段搬家静默回退默认（0.7/1.6/10000/1），worker 只读冻结值。

- **IC2 超频（Processing 机器）**：`fixed(4槽, 0, 1/0.7, 1.6, 1, 1, false)`——每片 耗时×0.7、能耗×1.6（两片 0.49/2.56，测试锁死）；槽上限 4 源码实证（`InvSlotUpgrade(...,4)`）
- **IC2 储能卡**：有意不进曲线（云池承担缓冲；共用能量轴会给单次能耗打 1.6^-N 折扣的失真）——需要时需核心加纯容量轴
- **三台特种机**（感应炉/电解/物质）显式 NONE（无超频）
- **TE 线圈能耗开关**：`teCoilEnergyPerAugment()`（RC `TE5.IncreasedEnergyConsumption`，默认开=0.1/件）

### 无限能量物品注册

核心提供 `CloudworksAPI.registerInfiniteEnergyItem(Predicate<ItemStack>)`。
TE 创造 Energy Cell 按 `Creative` NBT 注册，IC2 创造发电机按 `ic2:te` 的 meta 86 注册。
命中物品不读取 Forge/Tesla/IC2 能量 capability，直接以 `unlimited=true` 进入云端能量池；
普通能量物品继续沿用 capability 容量路径。

## TE 机器增强件（升级通道承载，替代旧 CONFIGURATION 模板钉选——已全部拆除）

| 件 | meta | 承载 | 语义 |
|---|---|---|---|
| 速度线圈 | 128 | 数值 custom（`machine_speed_coil`） | 时间 1/(1+n)、能耗 1+0.1n/件（RC 可关能耗）；全 15 机 × 5 档，上限=活插槽表 NUM_AUGMENTS[level]×RC socket |
| 副产筛 | 129 | 数值 custom＋chance 第 5 轴（`machine_secondary`） | chance ×100/max(5,100-15n)、能耗 1+0.1n；7 机（磨粉/锯木/感应/灌注/离心/精炼/转置） |
| 副产焚毁 | 130 | 机制＋完成策略（`machine_secondary_null`，恰 1 件） | 无地落副产丢弃、部分截断、主产直通；离心机例外只豁免副产流体（物品照常阻塞）；6 机（无转置） |
| 石之粉剂 | 273 | 机制 REPLACE＋新增端口（`pulverizer_petrotheum`，恰 1 件） | 矿石配方：主产 +max(1,50%)、petrotheum 100mB/操作（新 `petrotheum_input` 口）、副产概率 ×100/75；全部配方能耗 +50%（装机级）；仅磨粉机 |
| 树脂导流 | 288 | 机制 REPLACE＋新增端口（`sawmill_tapper`，恰 1 件） | TapperManager 映射原木额外产树脂（新 `resin_output` 口）＋**全部配方能耗 +50%**（TAPPER_ENERGY_MOD，TileSawmill:458）；仅锯木机 |
| 炽焱 | 304 | 机制 REPLACE＋新增端口（`smelter_pyrotheum`，恰 1 件） | 矿石配方（主/副入任一为矿）：主产 +max(1,50%)、pyrotheum 100mB/操作（新 `pyrotheum_input` 口）、副产概率 ×100/70；全部配方能耗 +50%；仅感应炉 |
| 炉食物/矿物 | 256/257 | 机制 REPLACE（直冻过滤） | TE 自有 isFood/isOre 分类＋主产 +max(1,count/2)＋**能耗 +50%**（FOOD/ORE_ENERGY_MOD）；仅红石炉 |
| 热解 | 258 | 机制 REPLACE（直冻替代列表） | 替代列表＋保底杂酚油到新增 fluid_output 口＋**能耗 +50%**（PYRO_ENERGY_MOD；minPower 慢速档属速率轴挂起） |
| 压缩机币/齿轮 | 336/337 | 机制 REPLACE | 切 COIN/GEAR 列表；PLATE 保持免费基础模式 |
| 精炼药水 | 369 | 机制 REPLACE | 独立药水箱列表 |
| 离心生物 | 416 | 机制 REPLACE | getRecipeListMobs |
| 造石沉积岩 | 497 | 机制 REPLACE | 沉积岩变体列表（热液+冷液双入） |
| 树苗灌注 | 323 | 机制 REPLACE（`insolator_tree`，恰 1 件） | **解锁语义**：基础灌注器列表排除 TREE 类配方（TileInsolator:141 门），装件后 REPLACE 为完整列表（常规+TREE）；仅灌注器 |
| 岩浆 | 352 | 机制 REPLACE（`crucible_lava`，恰 1 件） | 熔岩类子集（isLava 表）＋时长 ÷10 **同时 extraEnergyPerTick+9×档位速率**（LAVA_MULTIPLIER 功率×10，总能量精确守恒＝TE calcEnergy×10）；仅熔岩炉 |
| 发电机效率 | 513 | 机制 REPLACE（`dynamo_efficiency`，可堆叠） | 全部燃料契约 energyPerOperation×(1+0.15n)（n=每台有效件数，计数变体缓存键隔离）；六发电机 × 5 档，上限=反射读 dynamo 槽位表×RC socket |
| 线圈导管/节流阀 | 514/515 | NoopModule（`dynamo_coil_duct`/`dynamo_throttle`） | 云端无语义件：注册可上传但零效果（全部发电机族；处置=无效果而非拒收） |
| 燃料转换 | 673 | 机制 REPLACE（`dynamo_compression_fuel`，恰 1 件） | 燃料锁定 refined_fuel 子集＋能量 ×1.5（energyMod+50，TileDynamoCompression:179/:317-322）；仅压缩发电机 |
| 生物燃料转换 | 674 | 机制 REPLACE（`dynamo_compression_biofuel`，恰 1 件） | 燃料锁定 refined_biofuel 子集＋能量 ×1.25（energyMod+25）；仅压缩发电机 |
| 宝石转换 | 720 | 机制 REPLACE（`dynamo_numismatic_gem`，恰 1 件） | **独立宝石表**整体替换硬币表（getGemFuels/getGemFuelEnergy）＋**能量 ×1.25**（energyMod+25，TileDynamoNumismatic:206）；仅通货发电机 |
| 锅炉转换 | 576 | 机制 REPLACE＋新增端口（`dynamo_boiler`，恰 1 件） | 燃料表换成"燃料＋水→蒸汽"资源契约（energyPerOperation=0，价值走新 `steam_output` 口）：蒸汽机线 SteamManager 表 ×1.15（energyMod+15）、水=ceil(steamRF/250)；岩浆/压缩线 ×0.6（energyMod−40）、水=ceil(steamRF×100/冷却RF100mB)；接受面=蒸汽/岩浆/压缩三族（家族分派按基础契约 id 前缀）；maxPower×4/×2 速率轴挂起 |
| 蒸汽涡轮 | 640 | 机制 REPLACE（`dynamo_steam_turbine`，恰 1 件） | 全表替换为单一契约：1000mB 蒸汽→2000 RF（**2 RF/mB**，TileDynamoSteam:148-154）；仅蒸汽发电机；2000mB 保留线与空转排汽为缓冲机制不入契约 |
| 冶金元素自持 | 303 | 机制 REPLACE 计数变体（`smelter_flux`，BASIC 可叠） | 含助熔剂配方追加 `chanceOutput(助熔剂, 15%×n)`＝逐次真概率返还（TE 同款掷骰）；全部时长 ×(1+0.15n)；仅感应炉 |
| 土壤肥力恢复 | 320 | 机制 REPLACE 计数变体（`insolator_fertilizer`，BASIC 可叠） | 含肥料配方 `chanceOutput(肥料, 20%×n)`；时长 ×(1+0.15n)；基础面=排除 TREE 的灌注器列表 |
| 反应催化试剂 | 448 | 机制 REPLACE 计数变体（`brewer_reagent`，BASIC 可叠） | `chanceOutput(试剂, 15%×n)` 经机制自增 `reagent_return` 口返回（酿造器原生无物品输出口）；时长 ×(1+0.15n) |
| 自动轮作循环 | 324 | 机制 REPLACE（`insolator_monoculture`，恰 1 件） | 种子确定返还（unscaled 输出）＋副产种子条目移除（recipeChance−100）＋肥料 10% chanceOutput＋时长 ×1.5 |
| 火成碎屑注入 | 496 | 机制 REPLACE（`extruder_no_water`，恰 1 件） | 标准火成列表去掉冷水输入（TileExtruder:145/:191-193）；能量/产出不变；零水量原生配方（圆石）与基准线同样排除 |
| 原油回流蒸馏 | 368 | 机制 REPLACE（`refinery_fossil`，恰 1 件） | isFossilFuel 子集＋产出流体 +50mB（SPEC_FLUID_BOOST）＋能量 ×2（SPEC_ENERGY_MOD） |
| 熵增抑制容器 | 656 | 机制 REPLACE（`dynamo_magmatic_coolant`，恰 1 件） | **逐冷却液契约变体**（燃料×冷却液对）：能量 ×(115+冷却液品质−20)%（水 ×1.15/冰霜 ×1.35），冷却液随动消耗 ceil(有效RF×100/RF100mB)——"品质随所灌液体"，与散热器先例同构；仅岩浆发电机 |
| 闭环冷却系统 | 672 | 机制 REPLACE（`dynamo_compression_coolant`，恰 1 件） | 逐冷却液变体：能量 ×(100+品质−20)%（水 ×1.0），冷却液=**固定 100mB 引动量**（TE 停止消耗的逐操作翻译）；仅压缩发电机 |
| 基础反应催化 | 688 | 机制 REPLACE（`dynamo_reactant_elemental`，恰 1 件） | elemental 反应对子集（validReactantElemental×validFluidElemental）＋能量 ×1.25（energyMod+25）；仅反应发电机 |

全部 REPLACE 自动进 `cloudworks:primary` 互斥组＝TE"一机一 MODE 件"；件物品一件一型绑定（`thermalexpansion:` 命名空间）；lang 键 `upgrade.cloudworks.thermalexpansion.*`。

## Thermal Expansion 机器（5.5.7.1，15 型 × 5 级 = 75 身份键）

- 身份：`thermalexpansion:machine` + meta(类型) + NBT `Level` 字节（`keysWithDefaultByte`）；玩家状态 NBT 不入键
- 能量：`energyPerTick = 基础功率(20，熔岩炉/充能机 40) × 等级速度(POWER_SCALING 运行时快照) × FROM_RF`；配方时长 = `ceil(配方RF / (功率×速度))`；容量 480k RF
- 15 型全注册：红石炉/磨粉机/锯木机/感应炉(双入)/有机灌注器(水耗)/压缩机/熔岩炉(守恒 unscaled)/精炼机/转置机(双向两类)/**充能机(双线：配方线＋空→满能量枚举线——五体系探测仿 ChargeUtils，NBT 规范形，实测 J，半电量态不匹配为已接受简化)**/**离心机(副产流体=输出[方向修复]，fluid-only 配方合法)**/酿造机/附魔机(经验精华)/冷冻机/造石机(热液+冷液)
- 机制件时长：按档位从注册 J/t 反推（`ceil(配方RF×rfToJ / energyPerTick)`）

## Thermal Expansion 发电机（6 型 × 5 级，燃料契约路线）

- 身份：`thermalexpansion:dynamo` + meta(0-5) + `Level` 字节，与机器族同规则；储能 = 40×等级速度×1000×FROM_RF；`POWER_SCALING` 反射读活数组（protected 字段，失败回退默认）
- **燃料契约＝严格守恒**（活表枚举，表是运行时数据禁止快照常量）：

| 型 | meta | 契约 |
|---|---|---|
| 蒸汽 | 0 | 物品燃料 + 并行水 250RF/mB（ceil） |
| 热力 | 1 | 热流体按桶（getFuelEnergy(1000mB)） |
| 压缩 | 2 | 燃料流体 + 水冷却剂（CoolantManager RF 表，ceil） |
| 反应 | 3 | 物×液对（getReactionList() 全表，100mB/对） |
| 弱化 | 4 | 表内物品整烧；**表外可充物放电回退**（满态入/空态出/实测 J——充能机探测的镜像） |
| 通货 | 5 | 硬币表（**@720 宝石转换已落地：独立宝石表整体替换**，见增强件表） |

- **速率语义（已裁定 2026-10-21）**：**云端为无上限速率**——燃料条目按"每 tick 燃料单元（机器数×线程×工作速率）"结算，TE 发电机与 Mekanism 燃气机同待遇（源码实证：燃气机同走 burnFuel 燃料循环且 `generationLimit` 无资源输出时为 +∞，`CloudworksRuntimeSimulation:688`；热力发电机的 processRate 速率封顶住在 Thermal 路线，与燃料契约族无关）。**全部 MODE 件的 maxPower 乘数（576×4/640×4/673×4/674×3/656×4/688×5/720×5）正式裁定不做**；能量面已全部落地不受影响。发电增强件现状：**@513/576/640/673/674/720/656/672/688/@514/515 全部落地**；@512 输出功率件、@704 附魔件均裁定不做

## Thermal Expansion 设备（5 台单物品，无 Level、不可增强）

| 台 | meta | 契约 |
|---|---|---|
| 水生成器 | 0 | **空输入**产水，环境三态（2026-10-21 裁定，环境行为模块承载）：雨/雷族旗标→40t 循环 4000mB（雨回退档，TileWaterGen:136-137）；海洋族→20000mB（六源档，时长×0.2＝TileWaterGen:134-135）；**无任何环境旗标→契约拒绝＝停机**（原生 passiveGen=false 不产水） |
| 虚无器 | 1 | **零输出**：anyItem 一条配方（销毁任意物品） |
| 树脂采集器 | 3 | **@324 返还＋饵料式肥料**（2026-10-21 裁定重做，对齐原生"不耗树"）：布局＝原木入/肥料入/原木返还出/液出四口；基础契约＝1 原木→原木返还（unscaledOutput）＋itemMap 表值液/500t；肥料契约＝1 原木＋1 肥料→原木返还＋表值×8×mult/8×500t（TileTapper:166-175 与 TileFisher 同构；fertilizerMap 活表反射，LinkageError 降级）；**原木数＝树数**（1 原木串行 1 棵树产量，N 台满速需 N 原木＝原生每台绑一棵树） |
| 捕鱼器 | 4 | **空输入＋4 独立 chance 流**（cod .6/salmon .25/pufferfish .02/clownfish .13——**2026-10-21 审计修正：原 meta2/3 权重写反**，FisherManager:63-64 实证）；2400t 基准；鱼饵＝8 循环×mult 全量捕获流（饵配方前置防基础配方遮蔽，live baitMap）；输出口 256（原生 4×64 槽）；环境已落地（海洋 `/3`，雨/雷 `×3/4`，可叠加＝0.25；河流 `÷2` 等河流卡） |
| 因式分解器 | 10 | 双向双模式（combine/split 各 16t，FactorizerManager 两方向表） |

- 散热器（meta 2）＝**辅助节点**（GPT 落地，2026-10-21 源码复核通过）：AREA 覆盖 TE 机器/发电机（原生 IAccelerable 三基类），加速＝duration×1/(1+factor/100)（原生概率性额外 processTick 的期望值），冷却剂成本＝chance×USE_FACTOR(5)×每 tick 能耗换算 mB（TileHeatSink:161×TileMachineBase:433-442 返回值实证）；**多散热器为乘法叠乘**（2 个=1.44×）vs 原版线性叠加（1.40×）——建模近似已记录
- 排除 7 台（经验液化器/矿词转换器/流体分配器/药水雾化器/物品分配器/生物捕捉器/真空箱子）＋ chunk_loader 死代码（ rulings 见需求文档 §9.2/§7-11）

## IndustrialCraft 2（2.8.222-ex112，14 身份键）

- 身份：`ic2:te` + meta（数据表实证；**代码零引用 TeBlock**）；能量 `FROM_IC2`（1 EU = 10 J）启动期快照

| meta | 机器 | 契约 |
|---|---|---|
| 43/45/47 | 压缩/提取/打粉 | 2 EU/t × 300t；输入变体收成 alternatives 成分 |
| 44 | 电炉 | manager 活桥 → 直冻原版熔炼表；**通配符键保留**（2026-10-21 审计：CloudworksRecipeBatch:56-57 匹配器双向支持 wildcard damage） |
| 51/52 | 切割/热能离心 | 4×450 / 48×500 |
| 55 | 金属成型 | 10×200，三模式 |
| 56 | 洗矿机 | 16×500 + metadata 水 |
| 50 | 感应炉 | 热驱动拍平（**空气=metadata"fluid"×duration 每 tick drain 的全量**，TileEntityBlastFurnace 实证；空气走 `FluidName.air`＝ic2air；零 EU） |
| 42/49 | 装罐机/固体装罐 | bottle+enrich 双模式；固体装罐走 bottle 子集 |
| 48 | 回收机 | 黑名单 anyItemExcept 回退/白名单逐条（map+flag 表，1/8 概率）；空黑名单=放行任意（审计确认正确） |
| 76 | 电解机 | 每配方 EUaTick/ticks（extraEnergyPerTick）；**六方向输出口**（fluid_output_down/up/n/s/e/w，按 ElectrolyzerOutput.tankDirection 绑定——API 实证 1-5 输出、方向唯一） |
| 61 | 物质生成机 | 放大器经济学（1 点=1 EU 喂入+5 EU 增益=6 EU 储能；**GCD 整批**保留跨操作余能＝整 mB 输出无丢损；512 EU/t 喂入；按配方实际输入量扣料） |

### IndustrialCraft 2 发电机（7 台，2026-10-21 批）

身份同 `ic2:te`+meta（generator 3/地热 4/RTG 6/半流体 7/太阳能 8/水力 10/风 11；动能 5、斯特林 9 裁定不做；**创造 86 已走无限能源注册**）：

| 台 | meta | 契约 |
|---|---|---|
| 发电机 | 3 | **燃料契约**：原版熔炉燃料表（ForgeRegistries 枚举＋`Info.itemInfo.getFuelValue(s,false)`，岩浆罐拒绝）——每物 燃烧值/4 单位 × round(10×cfg) EU（TileEntityGenerator:19/:36）；煤=4000 EU |
| 地热 | 4 | 岩浆按桶：1000mB → 500×round(20×cfg) EU＝10 EU/mB（TileEntityGeoGenerator:17/:42-50） |
| 半流体 | 7 | `Recipes.semiFluidGenerator` 活表（oil 16/fuel 128/biomass 8/bio.ethanol 128/ic2biogas 32/ic2creosote 3 EU/mB）：1000mB → energyPerMb×1000 EU 严格守恒；energyPerTick 速率轴云端不存在 |
| RTG | 6 | 恒定计划＋**物品输入口计数弹丸、零消耗**（TileEntityRTGenerator:19/:23-38）：每台机器 n=min(6, 库存弹丸÷机器数) → 2^(n−1)×eff EU/t；无弹丸＝停机；弹丸＝永久装机语义（freeze 只读 `MachineContext.inputs()`，永不扣减） |
| 太阳能 | 8 | `GenerationPlan.solar`（原生昼夜曲线）＋冻结期天气折减：雨 −5/16、雷暴再 −5/16（MC 雷暴含雨＝(11/16)²）、下界旗标→0（TileEntitySolarGenerator:53-65）；露天 15/15 假设、沙漠免疫无旗标 |
| 水力 | 10 | **桶契约**：1000mB 水 → 1000 EU（2 EU/t×500t，流体无容器=cell 语义，TileEntityWaterGenerator:44-59）；**流量模式（water×=mult 复利蓄能 :83-93）＝经典怪癖不复刻** |
| 风 | 11 | `GenerationPlan.wind`：WindSim 高度三次多项式（峰值 1@159、零@288、地面 0，Cramer 精确解）×稳态强度 15（原生 5-24 随机游走）×2.4×0.1×cfg，雷×1.5/雨×1.25 冻结期读取；高度＝simulatedHeight（接受高度模块上限 256）；遮挡/过热断裂为世界机制不表达 |

- 全部 EU × FROM_IC2（10 J/EU）；发电计划 ×operations（machineCount×threads×workRate）；储能=原生 maxStorage（4000/2400/32000/20000/2/4/32 EU）
- **无限能源项注册**（GPT 2026-10-21 落地 `CloudworksAPI.registerInfiniteEnergyItem(Predicate)`）：TE 创造 Energy Cell（blockCell＋NBT "Creative"，`CloudworksTeDevices`）＋ IC2 创造发电机（`ic2:te` meta 86，`CloudworksIc2Machines`）——终端能量槽 `acceptsEnergy` 短路 capability 检查，`terminalEnergyPayload:4728` 命中即 `Payload(1, unlimited)`

## 测试（纯 JVM，真 mod API）

- TE：67 用例——机器 12（含全量注册/等级身份/离心机流体方向/fluid-only/灌注器 TREE 门）＋增强件 36（六套机制冻结/时长反推/128 曲线/129 表值 0..7/130 四态策略/accept 面/**273/304 矿石派生＋流体成分＋端口/288 树脂流＋装机能耗/513 计数变体曲线/323 解锁方向/352 熔岩÷10＋总能量守恒/673 锁定＋能量缩放/720 独立宝石表×1.25/576 锅炉蒸汽线＋流体线/640 涡轮 2RF/mB/锅炉涡轮接受面/303 复用冷却/320 肥料复用/448 试剂复用/324 单作/496 无水/368 化石/656 熵增容器/672 闭环冷却/688 元素反应**）＋充能机 3＋发电机 7（注册/六燃料契约守恒/回退）＋设备 9（空输入/零输出/4 chance 流/活表/双向/tier-30 基准/鱼饵八循环/**环境快照复合乘积/蓄水器三态门控**）
- IC2 发电机：8 用例（七身份注册/燃烧值÷4 契约/地热 10EU/mB/半流体 EU/mB 守恒/水力桶契约/**RTG 弹丸库存计数曲线＋按机均摊＋上限 6＋ops 缩放**/太阳能雨雷下界三折减＋夜零/风多项式约束锚点＋天气系数＋超顶归零）
- IC2：全假管理器（仅 ic2.api 接口），特种机经济学精确断言
- 曲线快照用例（默认回退/类缺失回退/非法值拒绝）
- 门禁基线：**989/0/168**（2026-10-22 审计清零批后实测；进度 931→…→982→985（GPT 批）→989；不得低于交付时点）

### 2026-10-22 审计清零批（GPT 全项修复＋GLM 复核/补刀）

- **范围**：PRE_RELEASE_AUDIT §1/§2/§3 全清（63 文件 GPT 批，diff 对 10-21 22:44 备份逐段复核；完整勾销表见 `ai/GLM/PRE_RELEASE_AUDIT_20261021.zh-CN.md` 处理结果节）
- **§1 崩溃面修法**：dynamo 流体端口 → `DYNAMO_FLUID_PORT_CAPACITY=Integer.MAX_VALUE`（岩浆鼓风机空气口先例）；tapper 液出端口同法＋`scaledFluidAmount` 溢出跳过；fisher 输出口按**活表鱼种数×最大饵 draws** 动态定容（GLM 修正 GPT 首版 `draws×4` 四鱼种硬编码——包加第五种鱼即回归）；matter 放大器 >256 跳过＋warn；+50% 增益 `boostedItemCount` 统一钳 64；resetForTests 收包私有（测试走 `CloudworksApiTestSupport` 桥）；alias/template `resolving` 链环检测（声明期 fail-loud）；能量窗改核心 `GuiTextureOnlyElement`＋字面量 modid（去 generators 硬引用）
- **§2 落点**：QIO 网格排序缓存／LineCanvas·EditorModel draft 版本缓存／InfuseRegistry identityHashCode+size 代际标记／choice 输出顺序二项批量（等价 chance 位面法）／checkpoint 去全扫与 UUID 拼串／Registries 读路径全 synchronized／PDM `recoveryRequired||closing` 载入门／NBT 断言矛盾 ×3／蒸汽 dynamo 熔炉燃料兜底（manager 表∪全注册表扫描，表条目优先去重）／fisher 鱼种活表反射（fishList/weightList，回落四原生）／fission matches 长度校验／canFit 容器上限／tapperTable·baitTable LinkageError／TE·IC2 燃料取值全 safeEnergy／enervation 截断 warn／结构命令 parse 全捕获给玩家反馈
- **§3**：FusionCooling/ItemGenerator 适配器、creditGeneration、resourceCount、RuntimeSettings.load、ScriptMachines 死分支、CloudworksGuiLayout（连专属布局测试）等全删；**CloudworksCardItem 按用户裁决保留**（addon 便捷基类）
- **本批新增测试 ×6**：fisher 容量纯函数（种数×mult）＋五鱼种×mult9 端到端中央校验器通过、tapper 溢出跳过、furnace 钳 64（44→64 断言）、steam 兜底合并去重、300M RF 蒸汽对无界端口 validateMachineAdapter 通过
- **GPT 复查边界批（12:34）**：fisher 权重总和 long 化、steamFuels 缝空表/空注册表容错、`MAX_BAIT_DRAWS` 1M→4096（adapter 跳过与容量计算共用同一常数，mult>512 饵不出契约；单配方 chance 条目上限=4096×鱼种数）——GLM 逐项核验落位，门禁 989/0/168 复跑一致
- 备份：`E:\minecraft\Dev\MekanismCeu-CloudWorks-bak-20261021T2244`（GPT 批前全树 robocopy，5122 文件）

### 2026-10-21 审计交接批（GPT 起稿＋GLM 核验）

- **游戏内崩溃修复**（客户端 15:24/服务端 15:41 到达 Done 实证）：锅炉 `steam_output` 端口容量改为随最大单配方蒸汽输出（固定 10000 拒了煤=27600mB 的合法报价）＋冷却液输入口冲突
- **锅炉/蒸汽水批量**＝native 逐罐语义（ceil 到 100mB 批次，fluidAmount=100/STEAM_RF=25000 源码实证）；**跨燃料水预算缓冲**为建模近似：配方级无法表达机器级 waterRF 结转，每操作恒超收 ≤99mB（安全方向）
- 锅炉蒸汽比率复核：transferSteam=1mB/RF×maxPower（TileDynamoBase:401-404）×涡轮 2RF/mB ——原生链条即 2×，两契约均照源复刻
- **IC2 十四台加工机常量逐台二次核对**（2026-10-21，jar 构造器实证）：压缩/提取/打粉 2×300、切割 4×450、离心 48×500、成型 10×200、洗矿 16×500（TileEntityOreWashing super(16,500,3)）、装罐 4×200、固体装罐 2×200、回收 1×45、电炉 3×100——**全部吻合**；分解器 16t＝CoreProps.TIME_CONSTANT_HALF(32/2) ✓
- 测试卫生备注：TE manager 静态表跨测试类存活——新增用例须用独占物品（如南瓜载 oreTest 名）隔离，防其它套件遗留配方串扰（CloudworksTeMachinesTest 的 5000RF 铁矿配方先例）；另注意 CloudworksTeamRecoveryTest 存在偶发持久化队列竞态（`cloudworks.error.busy`，复跑即绿，与集成无关）

### 2026-10-22 B1 时长对齐批（GLM，用户裁决方案 A）

- **Builtins 15 台 ticks 全量对齐 CEU 原生 baseTicksRequired**（清单＋双侧行号见 `ai/GLM/B1_DURATION_DEVIATIONS_20261022.zh-CN.md`）：快组 6/16/100→200（粉碎机/冶炼炉/融合机/冶金灌注/锯木）、慢组 100→1（电解分离器/化学灌注器/清洗机/同位素离心机/太阳中子/回旋）、20→1（环境收集器×2）、200→100（PRC）、100→200（结晶器）
- **能量轴三处对齐**：分离器改 per-recipe 能耗＋零基线（MultiblockMM 口径，原生 TileEntityElectrolyticSeparator:188-197 配方能耗替换基线）；太阳中子零能耗（原生纯日光）；被动环境收集器零能耗、电动版活配置 getUsage
- **工厂族 per-family ticks**：PRC/反质子=100、其余 19 族=200（原全族硬编 200）
- 旁证记录：同适配器 Builtins vs MoreMachine 两套数字（结晶器 100/200、分离器 100/1），MoreMachine 侧当时对源——定性 Builtins 移植疏漏；MoreMachine/MultiblockMachine/TE/IC2 四域全量核对本就吻合
- 回归测试 ×4（`CloudworksMekanismBuiltinsDurationTest`：逐台 ticks＋分离器能量轴＋零能耗机＋工厂族），门禁 **996/0/169**
- 平衡影响：粉碎机 33×/冶炼炉 12.5× 削弱、分离器等 6 台 100× 增强吞吐（用户知情裁决）

## 已知留白（待裁决/待核心，2026-10-20 动力学批后）

1. **发电机速率轴**：燃料契约现按吞吐结算；TE 原生 maxPower 限速与全部 MODE 件的功率乘数（576×4、640×4、673×4、674×3、656×4、688/704/720×5）等待核心能量速率轴（GPT 侧）后定夺；**512 已裁定不做**；**513/576/640/673/674/720/514/515 已全部落地**（锅炉/涡轮为守恒契约）；剩 @656/672（冷却剂语义）/688（元素反应物）/704（附魔吸收）待速率轴或独立裁决
2. **fisher 环境除数**：公式定稿（海洋覆盖 ÷3、雨或雷 ×3/4、river 预留 ÷2），卡在"带回调的环境原型模块"核心缝隙（环境标志已到 PreWorkContext，但内置原型模块为 EmptyModule 且 cloudworks: 命名空间保留）；**water_gen 速率档**（现 2 源档）/**river_environment**/鱼饵 → 用户裁决
3. ~~**复用概率族 @303/@320/@448 与 @324 单作**~~ **已落地（2026-10-22 核销，原文陈旧）**：@303 熔剂 15%/件、@320 肥料 20%/件、@448 试剂 15%/件＝消耗＋`chanceOutput` 概率返还（复用 TE 原生 chance 轴，封顶 95% REUSE_MAX），各 +15%/件能耗；@324 单作＝种子 unscaled 确定性返还；测试齐（te_smelter_flux/te_insolator_fertilizer/te_insolator_monoculture/te_brewer_reagent）
4. **分类页签**：custom 分类 lang 落点机制未定（本批全部用默认推断分类规避）
5. TE 按机 `BasePower` config 覆盖；矿词通配扩展（v1=代表栈精确冻结）；机制升级入口的 GUI 上传交互待实机验证；灌注器基础面 TREE 门为行为修复（装 @323 前后行为与 TE 一致），旧档无影响（TREE 配方原本就不可达）
6. **注册期类型引用纪律**：模块注册表（CloudworksMachineModuleRegistry）到 freeze 阶段才填充——注册期的接受/引用必须走直接类型引用（TeAugments.DYNAMO_TYPES 先例），禁止注册表查找（@513 首版接受静默空转的事故记录）

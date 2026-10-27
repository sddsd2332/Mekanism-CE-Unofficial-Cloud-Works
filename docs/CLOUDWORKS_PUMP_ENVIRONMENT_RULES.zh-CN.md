# 泵环境配方与 CraftTweaker

更新：2026-10-25。Mek 电动泵及 MoreMachine 基础/高级/精英/终极泵共用此注册表。根据产线中**覆盖机器的环境源节点**选择配方，不读取真实世界流体方块，也不根据机器物品上传时所在世界自动获得产物。

## 默认规则

| 规则 ID | 必须覆盖的环境轴 | FILTER 条件 | 基础周期与产物 | 等级关系 |
| --- | --- | --- | --- | --- |
| `cloudworks:pump_water` | `cloudworks:ocean_environment`（海洋群系） | 未安装 | 20t / 1000mB 水 | `native_water` |
| `cloudworks:pump_heavy_water` | `cloudworks:ocean_environment` | 已安装 | 20t / 10mB 重水 | `per_process` |
| `cloudworks:pump_lava` | `cloudworks:nether_heat_source`（下界维度） | 无关 | 20t / 1000mB 熔岩 | `fixed` |

基础泵 processes=1，MoreMachine 四档为3/5/7/9。等级只按每条规则的声明放大；合并机器数、workRate、速度/能量升级及云端产出倍率仍由既有 Runtime 统一处理，不在规则中再次预乘。

环境源必须启用、已完成放置，并满足现有范围/覆盖阈值。普通渠道节点、禁用源、待补源和不匹配的森林/天气/结构节点不使默认泵工作。这里的“海洋”指确切的默认海洋轴；河流、深海或模组群系可通过下述入口显式添加规则。

## CRT 入口

```zenscript
import mods.cloudworks.Pumps;

// 输出数量来自 ILiquidStack；优先级越大越先选。
Pumps.add("pack:nether_lava", "cloudworks:nether_heat_source",
    <liquid:lava> * 1000, 20, 200);

// 用原生群系完整注册名寻找已注册的环境轴。
Pumps.biome("pack:river_water", "minecraft:river",
    <liquid:water> * 1000, 20, 100);

// 带等级和 FILTER 条件的完整重载。
Pumps.biome("pack:river_filtered", "minecraft:river",
    <liquid:heavywater> * 10, 20, 150, "per_process", "with");

// 自定义模组群系与流体：仅在对应模组存在时使用，并换成实际注册名。
// Pumps.biome("pack:marsh_oil", "biome_mod:marsh",
//     <liquid:oil> * 250, 40, 300, "fixed", "any");

// 删除/替换内置规则，或完全清空后重建。
// Pumps.remove("cloudworks:pump_water");
// Pumps.removeAll();
```

完整方法：

```text
Pumps.add(ruleId, axisId, ILiquidStack output, int ticks, int priority)
Pumps.add(ruleId, axisId, ILiquidStack output, int ticks, int priority, String scaling, String filter)
Pumps.biome(ruleId, fullBiomeRegistryName, ILiquidStack output, int ticks, int priority)
Pumps.biome(ruleId, fullBiomeRegistryName, ILiquidStack output, int ticks, int priority, String scaling, String filter)
Pumps.remove(ruleId)
Pumps.removeAll()
Pumps.getAxes()                    // String[]：可用维度/群系轴 ID
Pumps.getRuleIds()                 // String[]：当前已应用规则 ID
Pumps.axisForBiome(fullBiomeRegistryName)
```

五参数方法默认 `scaling="fixed"`、`filter="any"`。可用值：

| 参数 | 值 | 含义 |
| --- | --- | --- |
| scaling | `fixed` | 各等级每次抽取相同数量，熔岩默认采用此规则 |
| scaling | `per_process` | 每次数量乘等级 processes 一次 |
| scaling | `native_water` | `pumpWaterSources=false` 时乘 processes，true 时不乘，保留原生等级泵水量关系 |
| filter | `any` | 有无 FILTER 都能执行，不改写产物 |
| filter | `without` | 只有未激活 FILTER 时可执行 |
| filter | `with` | 只有按每台数量激活 FILTER 后可执行 |

自定义规则不根据流体名称自动切成重水。如果需要水/重水互斥，分别声明 `without` 和 `with` 两条规则；上述 river 示例的普通水规则应使用完整重载设置 `without`，否则其默认 `any` 在 FILTER 状态下仍参与竞争。

规则 ID 必须唯一；修改既有规则需先 `remove` 再 `add`，不会静默覆盖。ID、群系名和环境轴名均使用完整命名空间。未知群系、未发现的轴、天气/结构轴、无效流体、非正数量或周期拒绝注册；每次产量还必须能容纳于对应基础机器的输出端口。重复名称或不合法脚本会写入 CT/服务端错误日志，不留下半条规则。

注册操作在启动队列中按顺序应用，最终与机器配方一同冻结；修改脚本需要重启。`getRuleIds()` 返回调用时已经应用的注册表，不包含仍在启动队列中的后续变更。ZenUtils 不能在运行期修改冻结表。

## 选择、并行与已有批次

1. 先筛选当前被覆盖的环境轴和 FILTER 条件。
2. 按显式优先级从高到低选择。同级时群系轴优先于维度轴，同类再按规则 ID 排序，结果不依赖环境节点摆放或注册先后。
3. 同一个泵执行入口只选择一个匹配规则；所有环境/规则共享原有机器并行额度。环境源重叠不会各取得完整产能。
4. 胜出规则输出堵塞或缺电时等待，不偷偷切换成低优先级的另一种流体。

开工时把所需环境轴与产物承诺一起保存到 `CloudworksOperation`。失去该轴覆盖后，原批次暂停、不耗电、保留进度；另一种环境覆盖不能替它完成。例如水已加工10t后撤掉海洋、只留下下界，不能再加工10t得到水。重新获得海洋覆盖才继续原水批次。额外空闲并行容量仍受共享上限控制。

存档恢复不重算已承诺产物，也不因脚本改产物或删规则而重掷。旧批次保存的轴条件仍有效；没有对应源就等待或通过既有清空/取消流程处理。已有流体缓存不自动替换，产物仍由正常连接/资源过滤器输出。

## Java 与底层合同

```java
CloudworksAPI.registerPumpRule(new CloudworksPumpRule(
        new ResourceLocation("addon", "biome_oil"),
        CloudworksAPI.pumpAxisForBiome(new ResourceLocation("biome_mod", "marsh")),
        new FluidStack(oilFluid, 250), 40, 300,
        CloudworksPumpRule.Scaling.FIXED, CloudworksPumpRule.Filter.ANY));
```

Java 与 CRT 经 `CloudworksAPI.registerPumpRule/removePumpRule/clearPumpRules` 共用注册表，CRT 不直接访问内部实现，也不存在第二套脚本加工循环。Java 查询入口为 `pumpEnvironmentAxes/pumpRuleIds/pumpAxisForBiome`。修改规则会在最终冻结前清除旧泵准入缓存，避免先注册机器后加载 CRT 时仍使用旧配方；FILTER 变体从同一表编译。

普通不可变配方也可通过 `CloudworksRecipeSpec.Builder.environmentAxis(axis)` 声明覆盖条件，使用 `exclusiveSelection(group, priority)` 声明候选互斥组。复制/编辑合同保留两项数据，Runtime 和 checkpoint 共用环境匹配与承诺保存。

模组群系环境轴保留完整原生命名空间，例如 `mod_a:marsh`、`mod_b:marsh` 分别得到 `cloudworks:biome/mod_a/marsh`、`cloudworks:biome/mod_b/marsh`。原版海洋/森林别名和其他原版轴 ID 保持现有名称，避免跨模组同名群系被覆盖或丢弃。

## 验证

`CloudworksPumpEnvironmentRulesTest` 覆盖默认水/熔岩、FILTER与熔岩、跨模组同名群系、等级倍率、启动时覆盖旧准入缓存、删除默认规则、重叠选择/共享额度、输出堵塞不回退，以及保存环境条件后的暂停恢复。`CloudworksZenScriptProbe` 使用真实 ZenModule 编译上述所有 CRT 方法及中英文完整示例。

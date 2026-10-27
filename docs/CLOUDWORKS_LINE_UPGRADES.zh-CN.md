# 产线升级与节点逐轴优先

更新日期：2026-10-25。本文描述当前实现；旧的节点 `assignedSpeed/assignedEnergy` 分配通路已退出，不提供旧档迁移。

## 1. 物品与记账

- 产线速度、能量池默认使用 `cloudworks:speed_module`、`cloudworks:energy_module`。它们是独立注册的物品，复用 Mek 原生升级贴图，暂不提供配方。
- Mek 真实升级物品对应节点 CUSTOM 类型 `cloudworks:mekanism_speed_upgrade`、`cloudworks:mekanism_energy_upgrade`，通过节点模块安装／卸载。
- 产线模块计数不扣节点模块。节点 `customUpgrades` 是实际安装量；产线 `customUpgradePool` 是其资产凭据，不是可再消费一遍的库存。
- 安装、卸载、删除节点与删线沿用所有者事务。不能把节点安装数和产线凭据相加统计；删线退款只退一份资产。

## 2. 每轴独立选择

| 节点速度有效 | 节点能量有效 | 速度 | 能量 |
| --- | --- | --- | --- |
| 否 | 否 | 产线 | 产线 |
| 是 | 否 | 节点 | 产线 |
| 否 | 是 | 产线 | 节点 |
| 是 | 是 | 节点 | 节点 |

有效的本地模块优先，不比较谁更强、不补差额、不叠加同轴产线效果。缺少对应本地模块时才回退产线；机器也不接受该产线轴时，该轴为中性效果。

`PER_MACHINE` 按合并机器数折算有效安装量。未满足门槛的模块保留资产，但不接管。机制模块的 `PER_NODE` 单位、激活门槛、互斥组和机器／配方模式使用同一份冻结定义。

TE 速度线圈接管 SPEED，其耗能惩罚随该速度贡献保留，ENERGY 仍可来自产线。普通副产物、配方、环境和辅助模块没有轴声明时不接管。

## 3. 产线参与与额度

`MachineExecutionProfile.withLineUpgradePolicy(...)` 声明参与范围和每台真实机器的单位需求：

```java
MachineExecutionProfile profile = new MachineExecutionProfile(1, 20, 64000, 100)
        .withLineUpgradePolicy(MachineLineUpgradePolicy.of(true, true, 8, 8));
```

- 额度按 `sum(真实机器数 × 每台单位)` 累计；覆盖率为安装量／额度，限于 0～1。
- 本地接管、开关机不会缩小分母。线程、并行配方分支不重复贡献额度。
- 未显式声明时，根据原生策略接受的轴推导加工类默认参与；附加 CUSTOM 接受声明不会使默认参与丢失。TE 机器／设备显式声明参与，发电和热力等内置特殊合同保持不参与。
- A 的单位需求是独立的，不能拿 B 的模块安装上限充当 A 的分母。首版默认 8 单位／台，外部声明可覆写。
- 首版 A 曲线使用冻结快照中的 Mek 指数参数；没有宣称已完全脱离 Mek 配置。亚 tick 仍取决于机器执行能力，不能只因选用 A 就给 `NONE` 执行器开启。
- 参数替换、模板与机制派生保留既有 A；替换 profile 显式带新 A 时采用新声明。

## 4. Java 模块声明

声明接管轴并提供该模块自己的数值；不要把相同倍率再从 `onPreWork` 返回：

```java
class LocalSpeed implements ICustomMachineModule<LocalSpeed> {
    @Override
    public AxisContribution onUpgradeAxis(IMachineModule<LocalSpeed> module, UpgradeAxis axis) {
        return axis == UpgradeAxis.SPEED
                ? AxisContribution.of(1D / (1D + module.getEffectiveInstalledCount()),
                        1D + module.getEffectiveInstalledCount(), 1D)
                : null;
    }
}

MachineModuleType<LocalSpeed> type = MachineModuleType.custom(
        new ResourceLocation("addonmod", "local_speed"), 4, "upgrade.addonmod.local_speed", new LocalSpeed())
        .withUpgradeAxes(UpgradeAxis.SPEED)
        .withItem(addonItem);
CloudworksAPI.registerMachineModule(type);
CloudworksAPI.acceptMachineUpgrade(machine, type, 4);
```

- `onUpgradeAxis` 是配置快照阶段的纯数值回调；`time/power/capacity` 分别是时间、每 tick 功耗和容量倍率，不提供额外资源费用。
- 返回 `null` 表示使用该机器的策略轴曲线；没有可用轴曲线时报告无效配置，不能无声屏蔽产线。
- 本地轴贡献只应用一次。其他配方、概率、产物、资源费用和生命周期行为继续走原模块入口。
- 多个自有贡献按稳定 ID 顺序组合并校验互斥；多个模块同时要求同一份整机策略曲线但未声明组合规则时拒绝，不能任选首条。
- Mek 两轴均为原生曲线时联动一次求值；混合来源保留速度功耗代价及选中能量修正。

## 5. CraftTweaker

在机器注册之后声明每台真实机器的产线单位。零表示不接受对应轴，负数拒绝：

```zenscript
import mods.cloudworks.Machines;
// machine 是已经注册的具体机器物品。
Machines.setLineUpgradePolicy(machine, 8, 8);
// 只接受速度：Machines.setLineUpgradePolicy(machine, 8, 0);
// 不参与产线两轴：Machines.setLineUpgradePolicy(machine, 0, 0);
```

方法通过 `CloudworksAPI.updateMachineProfile` 进入同一注册通路。`setProfile` 不会清掉已声明 A，模板注册也继承 A；不支持运行期热改。

`Upgrades.setItem` 仍可替换产线物品；默认初始化不会覆盖更早的显式 addon 绑定。`useNativeItem` 与同一物品的节点 CUSTOM 绑定冲突时，最终校验拒绝。不能让同一物品同时被产线池和节点池识别。

完整脚本与实际 ZenModule 编译夹具覆盖此方法，见 [`examples/crafttweaker`](../examples/crafttweaker/)。

## 6. 实现与验证入口

- 统一安装上限：`CloudworksUpgradeLimits.moduleLimit`；内置 Mek 模块使用原生机器上限，显式 0 表示禁用。
- 有效安装门槛：`effectiveModuleCount`，使用具体机器／模式的冻结声明。
- 来源和贡献结算：`CloudworksUpgradeEffects.Context.forNode`。
- 回归：`CloudworksAxisPriorityTest`、`CloudworksAxisContractTest`、`TeAugmentsTest`、`CloudworksMachineUpgradePolicyTest`，包括实际编辑提交、Runtime 周期／功耗和退款守恒。
- 游戏内界面效果仍需实际客户端目检；离屏裁剪回归不等于完成游戏内验收。

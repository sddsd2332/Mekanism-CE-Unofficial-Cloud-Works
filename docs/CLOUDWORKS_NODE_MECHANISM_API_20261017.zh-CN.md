# 节点机制：统一模块下的配方、端口与执行合同

更新：2026-10-20。原独立机制、数值升级和完成输出策略已做破坏性迁移；公共入口以[机器模块 API](CLOUDWORKS_MACHINE_MODULE_API.zh-CN.md)为准，无旧 API 兼容桥。

## 声明机制

模块实现 `ICustomMachineModule<MODULE>`，在 `onRecipes` 冻结配方并同时声明额外端口：

```java
final class FluidModule implements ICustomMachineModule<FluidModule> {
    @Override
    public RecipeContribution onRecipes(IMachineModule<FluidModule> module, RecipeFreezeContext context) {
        List<CloudworksRecipeSpec> derived = new ArrayList<>();
        for (CloudworksRecipeSpec base : context.baseRecipes())
            derived.add(base.toBuilder("addon:fluid_" + derived.size())
                    .input(water, 100, "fluid_input").build());
        MachineSlotLayout ports = new MachineSlotLayout().add("fluid_input",
                QIOResourceCodecs.FLUID_STACK_ID, MachineSlotLayout.PortRole.INPUT, 4000);
        return RecipeContribution.perMachine(1, RecipeContribution.RecipeMode.REPLACE, derived, ports);
    }
}

MachineModuleType<FluidModule> module = MachineModuleType.custom(
        new ResourceLocation("addon", "fluid_module"), 1, "upgrade.addon.fluid", new FluidModule())
        .withItem(moduleItem);
CloudworksAPI.registerMachineModule(module);
CloudworksAPI.acceptMachineUpgrade(machine, module, 1);
```

机器身份、档位 NBT、实际安装数及完整模块集合都参与候选合同。`onRecipes` 不在 Runtime worker 执行；进入 worker 前预冻结对应变体，缺少合同拒绝编译。每个模块从原机器同模式基础列表独立派生，不能隐式依赖其他回调的可变中间列表。

`perMachine(required,...)` 要求 `required × 合并机器数` 件激活；`perNode` 要求 `required` 件，安装上限不乘合并机器数。未配齐时保留安装资产但不激活结构贡献。数值和输出行为同样按模块实际有效数量处理。

## 配方编辑与组合

`CloudworksRecipeSpec.toBuilder(...)` 保留基础输入、配置模板、确定/概率/互斥输出、条件、时长、能耗和产出倍率标记；批量修改使用 `CloudworksRecipeEditor`：

```java
CloudworksRecipeSpec derived = original.editor("addon:derived")
        .mapOutputs(output -> output.amount(output.amount() * 2))
        .removeChanceOutputs(output -> isSecondary(output))
        .build();
```

| 模式 | 执行语义 |
| --- | --- |
| `NONE` | 仅贡献端口/执行参数，无派生配方 |
| `REPLACE` | 激活时替换主配方集合；默认属于 `cloudworks:primary` 互斥组 |
| `APPEND` | 优先尝试派生集合，无法执行时回退基础集合 |
| `PARALLEL` | 与主合同独立推进，具有独立处理预算和在途操作 |

同一主合同最多一个 `REPLACE`、执行参数覆盖者、发电覆盖者和热力覆盖者；多个 `APPEND` 和 `PARALLEL` 可并存。显式互斥组即使模块只装了部分数量也不能共存。同名端口只有方向、codec、容量、多连线属性全等才能合并；额外端口最多 64 个。

`APPEND` 共用原来的工作预算和操作槽。`PARALLEL` 共用父节点缓存，但有独立预算、进度、输入预留、随机序列、发电余量和热力状态；分支预算按物理机器数、分支线程数和 workRate 计算。输入争用按核心稳定规则分配，不能重复预留同一原料。一个物理节点只有一套标准热容及端口，因此所有分支合计最多一个热力合同。

## 输出策略与工作参数

运行倍率和资源成本由 `onPreWork` 返回 `WorkContribution`；多件 @129 的副产概率倍率读取 `getEffectiveInstalledCount()`。@130 在 `onPostWork` 读取已判定输出、节点升级和缓存容量，按规则丢弃副产物。多个输出模块按明确优先级及稳定 ID 链式执行。

工作阶段钩子当前接入持有在途操作的加工配方流程。直接燃料发电、热力、衰变及连续环境发电使用各自数值内核；模块合同可调整这些内核的冻结参数，但它们不触发加工操作的工作阶段钩子。

新增输出资源要在 `outputResources()` 声明。核心只在最终资源、方向、端口和共享 codec 容量校验通过后原子提交；等待出口期间缓存变换结果。开工、完成和取消另由 `onWorkStarted/onWorkCommitted/onWorkCancelled` 通知，不用通知回调修改资源。没有逐 Tick 回调。

## 发电与热力

`RecipeContribution` 的 `withGenerator/withoutGenerator` 替换或清除环境发电合同；`withGenerationTransformer` 在服务器快照边界映射基础发电计划；`withThermal/withoutThermal` 替换或清除热力；`withProfile` 覆盖执行参数并保留机器原生升级接纳策略。

发电转换可用：

```java
original.withElectricityFraction(0.25)
        .withResourceOutput(mana, unitsPerJoule, "mana_output")
        .during(13000, 23000);
```

资源倍率以原始生成能量为基准；时间窗口在 Runtime 按模拟时钟判断。共同产物不吃加工产出倍率。小数产出余量随 checkpoint 保存。燃料与共同输出空间先核验后扣取，热力转换先限制可提取热量。同一合同不混合加工与直接燃料发电，混合任务应建成 `PARALLEL` 分支。

## 卸载、蓝图与客户端

结构端口仍被连线使用、有对应缓存或在途操作时拒绝卸载。改变仍有额外储热、等离子体热量或预付燃料的热力合同也拒绝；安装其他不冲突模块不会清空热力状态。普通原生配方模式切换可保留暂时隐藏的原生端口缓存，历史操作按承诺完成。

蓝图中的安装数是需求。待补节点实际安装数保持 0，只保存服务器校验过的端口形态预览；补齐才统一扣机器和模块资产。导出蓝图、checkpoint 或客户端投影不复制资产。GUI 只接收必要的端口、数量和分支状态，不接收服务端回调对象。

辅助节点继续是第四节点家族。`CloudworksAuxiliaryNodeType` 现在绑定同一 `MachineModuleType`，覆盖资格、成本来源和每个来源的安装/卸载通知统一处理。TE 散热器通过 `onPreWork` 提交加速和自己的冷却剂成本；范围显示走模块伴随 API 的 `INDEPENDENT` 模式。环境特殊节点使用 `ModuleEnvironment` 与 `ModuleArea`，下界覆盖框使用 `UNION` 模式。

示例位于 `examples/api/CloudworksFluidMechanismExample.java` 和 `CloudworksGenerationMechanismExample.java`；核心夹具位于 `CloudworksMechanismApiTest`。

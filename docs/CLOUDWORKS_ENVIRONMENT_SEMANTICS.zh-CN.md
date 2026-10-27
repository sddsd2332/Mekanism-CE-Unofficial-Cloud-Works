# 云端节点环境语义与接口

更新：2026-10-25。用户裁定：删除节点 `dimensionId`，不保留来源/诊断用途，不为旧开发存档增加维度迁移逻辑。

## 1. 节点没有真实维度

- 机器、辅助节点只有产线身份和画布位置，不保存玩家上传/编辑时所在的维度。
- 机器 NBT 不再读写 `dimensionId`，辅助节点 NBT 不再读写 `dimension`。
- 草稿、撤销/重做、同步、蓝图、所有者快照、Runtime 程序和公开上下文均不携带节点维度。
- 上传、更新草稿、提交编辑、放置蓝图和补齐蓝图的服务接口均已移除维度参数。
- 草稿/蓝图结构版本由 12 升为 13；当前写入不包含旧的两个节点维度槽位。
  已存在的旧蓝图解码分支仅消费并丢弃旧槽位，不恢复节点维度，也不据此推导任何环境。

环境卡仍在玩家真实位置采样，这是获取相应环境源放置权限的入口，和机器节点的数据无关。
产线运行不查询玩家当前位置、玩家维度或真实 `World`。

## 2. 环境气体收集器

普通、电动型和 MoreMachine 四档环境气体收集器共用 `CloudworksAmbientGasRecipeAdapter`。
冻结流程是：

1. 读取本体原生的 `AmbientGasRecipe` 表，保留各条配方原有气体、数量和概率。
2. 原生表的整数维度键通过 `DimensionManager.getProviderType` 解析成 `DimensionType`。
3. `DimensionAxes.byType` 将其映射到稳定的环境轴 ID；未注册维度或没有对应轴的条目不生成云端配方。
4. 冻结后的配方声明 `requiredEnvironmentAxis`。原生数字维度键只用于冻结时查表和原生配方身份，不进入节点维度判定。

运行时根据机器在画布上被哪些启用的环境源覆盖来选配方：

- 下界维度环境源覆盖 → 允许下界气体配方；主世界维度环境源覆盖 → 允许主世界气体配方。
- 没有匹配维度轴时为 `WAITING_ENVIRONMENT`，不默认主世界，也不耗加工电力。
- 只有海洋等群系轴不能代替某个维度轴；群系不能反推出维度。
- 已开工批次保存自身环境轴，覆盖消失后暂停、不耗加工电力、不改变产物承诺。
  另一维度轴不能完成旧批次，原轴恢复后继续；checkpoint 保留这一条件。
- 多个相同源不增加额外产量。多个可用维度轴形成候选配方，仍共享机器数量 × 并行线程的上限，
  按现有候选排序分配空闲线程，不承诺各轴平均分配，不因环境源数量增加线程。
- 同一 `DimensionType` 的多个原生维度实例共用环境轴；各原生配方条目保持独立，概率不合并。

模组维度支持基于其已声明的维度轴，无需玩家到该维度上传/重新放置机器。

## 3. 辅助节点

AREA 类型辅助节点在客户端范围预览、上下文构建、生命周期来源绑定和 Runtime 快照中的判定一致：
启用状态、目标机器过滤器、画布几何覆盖、覆盖阈值和目标数量上限。
不再比较机器与辅助节点的历史编辑维度。LINKED / LINE_WIDE 的现有目标方式不变。

## 4. Java 接口

原节点 `dimensionId()` / `setDimensionId(...)` 和配方 `requiredDimension()` / `dimension(...)` 均已删除。
`CloudworksMachineContext` 构造器现在是：

```java
new CloudworksMachineContext(nodeId, machine, machineCount, active,
        recipeType, inputs, outputs, configuration);
new MachineContextSnapshot.MachineNode(nodeId, identity, active, machineCount);
```

环境配方直接使用已有环境轴合同，不新增另一套维度字段：

```java
CloudworksRecipeSpec recipe = CloudworksRecipeSpec.builder("ambient_custom")
        .duration(20)
        .environmentAxis(DimensionAxes.NETHER.id())
        .output(gasDescriptor, 1, "gas_output")
        .build();

// 编辑会保留已有环境条件；传入其他轴可以替换，null 可以明确移除。
CloudworksRecipeSpec changed = recipe.editor("ambient_derived")
        .environmentAxis(otherAxisId)
        .build();

// 原批次转换函数不再接受无意义的第三个维度参数。
CloudworksRecipeSpec batchRecipe = batch.specification("custom", 20);
```

CRT `Pumps` 继续使用既有环境轴规则入口，无节点维度参数；原生 AmbientGas 配方改动仍在冻结时读取。
worker 只消费冻结后的轴、数值和资源描述符，不运行脚本或访问 Forge 世界/维度注册表。

## 5. 光照与其他环境

太阳能、SNA 的模拟光照语义不变，不引入无天空维度门禁。
泵、下界热源、生物群系、结构和天气继续按既有环境轴覆盖计算。
删除的是玩家位置写入机器节点的旧链路，不删除维度卡或维度环境源。

## 6. 验证

`CloudworksAmbientEnvironmentTest` 覆盖原生维度到轴映射、未注册维度、无覆盖不默认主世界、
多机不同覆盖产气、重复/重叠源共享吞吐、批次环境 checkpoint 暂停与恢复、辅助节点几何绑定。
`CloudworksRecipeEditorTest` 覆盖环境轴的保留、替换与移除；现有蓝图、草稿和状态夹具一并验证。
本次最终命令与结果记录在维度语义审计文档中。

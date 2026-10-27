# Mekanism CE 物品 GUI 渲染事件扩展交接文档

日期：2026-10-18

本文档只描述对 `Mekanism-CE-Unofficial-1.12.2` 本体的修改要求。
不要求本体工程添加 CloudWorks 依赖、包名、类名或专用判断。

## 目标

扩展本体已有的 `ItemGUIRenderEvent.Pre`，让其他模组可以在 GUI 物品渲染开始前接管一次渲染。

事件未被接管时，`RenderItem` 保持原版行为；事件被接管时，调用方负责完成替代绘制，原版物品绘制必须跳过。

目标入口只包括 GUI 物品渲染：

```text
RenderItem.renderItemAndEffectIntoGUI(...)
```

不修改手持物品渲染和地面掉落物渲染。

## 当前相关代码

本体已有以下代码：

```text
mekanism.common.event.ItemGUIRenderEvent
mekanism.coremod.MekanismCoreMethods
mekanism.coremod.MekanismCoreTransformer
```

当前核心 ASM 已经在 `RenderItem` 中注入：

```text
renderItemAndEffectIntoGUI
renderItemOverlayIntoGUI
```

`ItemGUIRenderEvent.Pre` 当前由 `MekanismCoreMethods.renderItemAndEffectIntoGUI` 发布。

## 事件扩展

将 `ItemGUIRenderEvent.Pre` 改为可以取消的事件，推荐使用 Forge 的 `@Cancelable`。

事件仍然保留以下数据：

```java
ItemStack stack
int xPosition
int yPosition
```

现有 getter 和已有监听器需要保持兼容。

`Post` 事件维持现有语义，不改为可取消，也不改变其触发位置。

## 核心方法语义

修改：

```text
mekanism.coremod.MekanismCoreMethods.renderItemAndEffectIntoGUI
```

当前方法的 JVM 描述符以 `V`（`void`）结尾。直接把返回类型改成 `boolean` 会改变方法描述符，已编译的外部调用方可能因此出现 `NoSuchMethodError`。建议保留现有 `void` 方法作为兼容入口，并新增一个供 ASM 调用的布尔方法，例如：

```text
renderItemAndEffectIntoGUIHook(ItemStack, int, int): boolean
```

布尔方法的 JVM 描述符应为：

```text
(Lnet/minecraft/item/ItemStack;II)Z
```

现有 `renderItemAndEffectIntoGUI(ItemStack, int, int): void` 可以委托给同一内部处理逻辑并忽略返回值，避免重复发布事件。ASM 只调用新的布尔方法。

返回值约定：

```text
true  = Pre 事件已取消，RenderItem 应跳过原版物品本体绘制
false = Pre 事件未取消，RenderItem 继续原版绘制
```

逻辑应为：

```text
stack 为空
    → 返回 false

如果 stack 实现 IRenderEffectIntoGUI
    → 按现有顺序调用 renderItemAndEffectIntoGUI

创建 ItemGUIRenderEvent.Pre
发布事件

事件被取消
    → 返回 true

事件未取消
    → 返回 false
```

`Pre` 当前是在 `IRenderEffectIntoGUI` 回调之后发布。为保持现有行为，本次不调整这个顺序；取消只阻止 `RenderItem` 后续的原版物品本体绘制，不撤销已经由 `IRenderEffectIntoGUI` 完成的绘制。发布后直接检查 `event.isCanceled()`，避免把 `EventBus.post` 的返回值误解为“是否有监听器处理”。

本方法不添加任何 CloudWorks 判断，也不修改 `IRenderEffectIntoGUI` 的现有处理。

## 方法与事件兼容边界

`ItemGUIRenderEvent.Pre` 继续使用现有构造参数和数据类型。保留已有的：

```text
getStack()
getxPosition()
getyPosition()
```

可以额外添加拼写规范的 `getXPosition()`、`getYPosition()`，但不能删除旧 getter。`Pre` 的取消能力只由 `@Cancelable` 提供；不要把基类或 `Post` 标记为可取消，也不要改变 `Post` 的构造参数、发布时机或事件顺序。

事件接收的是当前渲染调用使用的 `ItemStack` 引用和 GUI 坐标。监听器应把栈当作只读数据，不要在渲染期间修改数量、NBT 或物品类型。事件只会从客户端 GUI 渲染路径发布，不应将客户端事件逻辑带入服务端类加载路径。

## 两个重载与事件次数

目标重载的描述符为：

```text
renderItemAndEffectIntoGUI(ItemStack, int, int)
(Lnet/minecraft/item/ItemStack;II)V

renderItemAndEffectIntoGUI(EntityLivingBase, ItemStack, int, int)
(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;II)V
```

当前 Transformer 只在第二个重载入口注入事件回调。工程中的 1.12.2 `RenderItem` 源码已确认：三参数版直接调用带 `EntityLivingBase` 的四参数版，因此只注入第二个重载即可覆盖两种调用并且每次只发布一次 `Pre`。不要同时注入两个重载，否则三参数版委托到四参数版时会重复发布 `Pre`。

验收要求是：从任一重载发起一次顶层 GUI 物品绘制，`Pre` 恰好发布一次；若目标版本或其他 coremod 改变了重载委托关系，Transformer 必须检测到目标方法数量或结构不符合预期并记录清晰警告，不能静默地重复注入。

## ASM 修改

修改：

```text
mekanism.coremod.MekanismCoreTransformer
```

对于 `RenderItem.renderItemAndEffectIntoGUI` 的注入：

1. 在确认的唯一注入点调用新的布尔返回值方法；
2. 返回 `true` 时执行该 `void` 方法的 `RETURN`，跳过原版物品本体绘制；
3. 返回 `false` 时落入原方法原有首条指令，继续原版绘制；
4. 保持开发名和 SRG 名匹配，并精确检查方法描述符；
5. 对两个现有重载按上一节的委托关系保证兼容，不要重复发布事件：

```text
renderItemAndEffectIntoGUI(ItemStack, int, int)
renderItemAndEffectIntoGUI(EntityLivingBase, ItemStack, int, int)
```

实际注入可以使用现有 Transformer 的标签、局部变量和 `InsnList` 风格实现。分支目标应有合法的 StackMapFrame；优先使用专用标签和与入口帧一致的显式帧，不要跳转到未经验证的任意原始指令。该注入无需新增局部变量。

注入后的逻辑等价于：

```text
handled = MekanismCoreMethods.renderItemAndEffectIntoGUIHook(stack, x, y)

if (handled) {
    return
}

执行原版 RenderItem GUI 绘制
```

不要修改以下路径：

```text
RenderItem.renderItemOverlayIntoGUI
RenderItem.renderItem(ItemStack, EntityLivingBase, TransformType, boolean)
RenderItem.renderItem(ItemStack, IBakedModel)
ItemRenderer.renderItemSide
RenderEntityItem
```

### Transformer 失败策略

- 目标类或目标方法未找到、匹配到多个候选方法、方法描述符不匹配或字节码结构无法安全注入时，记录包含类名、方法名和描述符的警告。
- 无法安全注入时保持原版物品绘制可用；此时取消接管功能不可用，但普通物品渲染不能因此失败。
- 不要把“找不到正确注入点”当作成功，也不要把不受验证的 ersatz 注入当作可靠回退。
- 注入应具备幂等性：重复处理同一字节码时不能再次插入回调。检测到已有 hook 时记录为已处理。
- `renderItemOverlayIntoGUI` 的既有转换及其自身回退策略保持独立，不能因 GUI 本体 hook 失败而改变覆盖层转换。

## 事件使用约定

其他模组监听 `ItemGUIRenderEvent.Pre` 时可以：

```text
1. 检查 ItemStack；
2. 检查键盘状态或其他客户端条件；
3. 使用当前坐标完成替代绘制；
4. 取消 Pre 事件；
```

未取消时，本体继续绘制原物品。

取消后，本体不再绘制原物品。监听器必须自己完成替代绘制，否则该物品本次会显示为空白。

推荐监听器骨架：

```java
@SubscribeEvent
public void onItemGuiRender(ItemGUIRenderEvent.Pre event) {
    ItemStack stack = event.getStack();
    if (!shouldHandle(stack, event.getxPosition(), event.getyPosition())) {
        return;
    }
    renderReplacement(stack, event.getxPosition(), event.getyPosition());
    event.setCanceled(true);
}
```

监听器应只在替代绘制已经完成后取消事件。若替代绘制因条件不满足而没有执行，保持事件未取消，让 Mekanism 继续绘制原物品。

若多个模组监听该事件，Forge 事件优先级会影响监听顺序；已取消事件也可能不再发送给未声明接收已取消事件的监听器。接管方应避免依赖不稳定的注册顺序；需要观察已取消事件时，显式使用 `receiveCanceled = true`，并在发现事件已取消时避免重复绘制。

取消事件只接管 `renderItemAndEffectIntoGUI` 的物品本体绘制。物品数量、耐久条、冷却遮罩、tooltip、槽位背景和调用方之后执行的 GUI 绘制不在此次取消范围内；`renderItemOverlayIntoGUI` 及其 `Post` 事件继续按原流程运行。因此，监听器不能把“取消 Pre”理解成接管整个 GUI 槽位或所有物品覆盖层。

接管方负责管理自己引入的 OpenGL 状态：替代绘制应恢复矩阵、颜色、混合、深度、纹理等修改过的状态，避免影响随后绘制的物品和界面。若监听器需要再次调用 `RenderItem` 绘制替代物品，应使用自己的重入保护，避免重新触发同一监听逻辑而递归。

## 递归说明

本体只提供事件接管机制，不添加模组专用的递归保护。

监听器如果在 `Pre` 事件中再次调用 `RenderItem`，需要由监听器自行使用 `ThreadLocal` 或等价机制防止递归。保护状态必须在 `finally` 中清除；不要用全局静态布尔值，因为嵌套绘制和多个渲染线程会互相干扰。

## 兼容要求

- 不添加 CloudWorks 专用包名、类名、依赖或判断。
- 不修改 `Item` 类。
- 不修改 `IOverlayRenderAware`。
- 不修改 `TileEntityItemStackRenderer`。
- 不改变普通物品默认渲染。
- 不改变 `ItemGUIRenderEvent.Post` 的触发行为。
- 不影响手持物品和地面掉落物渲染。
- 保留 `renderItemOverlayIntoGUI` 转换自己的既有回退逻辑；新加的可取消 hook 无法安全注入时应 fail-open 并记录警告，不能用未经验证的注入点冒充成功。
- 保留旧的 `void` 核心回调描述符，避免因返回值变化造成已编译调用方链接失败；新增布尔 hook 供 Transformer 使用。
- 每个顶层物品 GUI 渲染调用最多发布一次 `Pre`。
- 取消只跳过物品本体绘制，覆盖层与 `Post` 仍遵循原流程。

## 验收

本体工程至少验证：

1. Transformer 能成功找到开发名和 SRG 名的 `RenderItem` 方法；
2. 两个重载的调用链只发布一次 `Pre`，空栈不发布事件；
3. 未取消 `Pre` 时普通物品仍走原版绘制；
4. 取消 `Pre` 时原版物品本体绘制被跳过，替代绘制由监听器完成；
5. `IRenderEffectIntoGUI` 仍在 `Pre` 之前按旧顺序运行；
6. 数量、耐久、冷却覆盖层及 `Post` 事件行为不变；
7. 手持物品和地面掉落物不受影响；
8. 现有 Mekanism 的 `IOverlayRenderAware` 和 `IRenderEffectIntoGUI` 仍正常工作；
9. 开发环境和运行时反混淆环境均验证方法名、描述符和注入结果；
10. 编译、测试和已有 coremod 启动验证通过。

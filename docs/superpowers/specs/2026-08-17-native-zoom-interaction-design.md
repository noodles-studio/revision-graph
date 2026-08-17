# IntelliJ 原生缩放与手势交互设计

日期：2026-08-17

## 背景

`RevisionGraphCanvas` 当前直接监听所有 `MouseWheelEvent`，并按照滚轮方向执行固定比例缩放：

```kotlin
addMouseWheelListener { e -> zoomAt(if (e.preciseWheelRotation < 0) 1.12 else .89, e.point) }
```

这会把鼠标滚轮、触摸板双指滑动、横向滚动和惯性滚动全部解释为缩放。在 macOS 上，用户只要双指浏览图内容，画布就会连续缩放，无法获得 IntelliJ 原生的平移体验。

本设计从当前主线重新实现导航交互，不参考或继承 `tmp/xxx` 分支中的实现。

## 调研结论

IntelliJ 平台已经提供滚动和原生手势基础设施，但不会自动为任意自定义 `JComponent` 决定鼠标缩放策略：

- `JBScrollPane` 负责普通滚轮、触摸板滚动、惯性、滚动锁定和滚动边界行为。
- `JBViewport` 实现 `ZoomableViewport`。当直接 view 提供 `Magnificator` client property 时，平台默认 `ZoomingDelegate` 可以处理原生 pinch 的预览和提交。
- macOS 的 `MacGestureAdapter` 在手势开始时寻找光标下的 `ZoomableViewport`，将 magnification 生命周期交给 viewport。
- IntelliJ 编辑器、内置图形组件、图片编辑器以及 PlantUML 等插件采用“普通滚轮滚动，修饰键加滚轮缩放”的交互。
- draw.io 默认关闭无修饰键滚轮缩放，避免滚动和缩放语义冲突。

因此，解决方案应复用平台的滚动容器和 pinch 生命周期，仅补充一层很薄的鼠标修饰键缩放适配。

## 目标

实现以下统一交互：

| 输入 | 行为 |
| --- | --- |
| 鼠标普通滚轮 | 原生纵向滚动 |
| `Shift+滚轮` | 原生横向滚动 |
| 触摸板双指滑动 | 原生二维平移，不缩放 |
| 触摸板 pinch | 使用 IntelliJ 原生 magnification 生命周期缩放 |
| macOS `Cmd+滚轮` | 以光标为中心缩放 |
| Windows/Linux `Ctrl+滚轮` | 以光标为中心缩放 |
| 工具栏缩放和百分比输入 | 以 viewport 中心为锚点缩放 |
| 鼠标拖动画布 | 通过 viewport position 平移 |

所有缩放入口必须共享同一个比例状态、范围和坐标转换，缩放前后锚点对应的世界坐标保持不变。

## 非目标

- 不通过滚动粒度、惯性或事件频率猜测输入设备类型。
- 不让物理鼠标在无修饰键时直接缩放。
- 不自行实现 `ZoomingDelegate`、macOS gesture listener 或惯性滚动。
- 不迁移到 IntelliJ Graph/yFiles 图形框架。
- 不改变图布局、节点视觉样式、选择语义、上下文菜单或数据加载流程。
- 不增加用户设置项来切换滚轮模式。

## 方案选择

采用“平台原生容器加薄适配层”方案：

1. 用 `JBScrollPane/JBViewport` 承载 Canvas。
2. 在 Canvas 上提供平台 `Magnificator` client property。
3. 只对合法的 `Cmd/Ctrl+滚轮`执行自定义缩放。
4. 普通滚轮和双指滚动不被自定义代码消费。

没有选择以下方案：

- 只支持 pinch 和工具栏：鼠标用户缺少高效缩放入口。
- 自动区分鼠标与触摸板：Swing/JBR 没有可靠的逐事件设备类型，启发式判断会再次产生误判。
- 全量自定义手势和滚动：会重复实现 IntelliJ 已有能力，并破坏平台惯性及嵌套滚动行为。

## 组件结构

新的组件树为：

```text
UiDataProvider wrapper
└── JBScrollPane
    └── JBViewport
        └── RevisionGraphCanvas
```

`RevisionGraphCanvas` 必须是 `JBViewport` 的直接 view，因为 `JBViewport` 从直接 view 的 client property 读取 `Magnificator`。`UiDataProvider` 应包装整个滚动区域，不能夹在 Canvas 与 viewport 之间。

### RevisionGraphView

- 创建并组装 Canvas、`JBScrollPane` 和 viewport controller。
- 将工具栏缩放、适应窗口、1:1、百分比输入和定位操作转发给 controller。
- 保留现有 cards、empty、loading、legend、watermark 和 action toolbar 结构。
- 将 `UiDataProvider` 的目标组件移到滚动区域外层。

### RevisionGraphCanvas

- 保存 snapshot、layout、selection 和当前 `scale`。
- 负责绘制、命中检测和世界坐标转换。
- 根据图边界、scale 和留白计算 `preferredSize`。
- 删除承担平移职责的可变 `offsetX/offsetY`。
- 不注册 `MouseWheelListener`，不直接操作 scrollbar 或 viewport position。
- 不在 `paintComponent()` 中执行 focus、修改比例或移动视口。

Canvas 可以保留计算得到的 `contentOrigin`，但它只负责固定留白以及图小于 viewport 时的居中，不能表达用户平移状态。

### RevisionGraphViewportController

新增一个聚焦于导航行为的薄控制层：

- 持有 Canvas、`JBScrollPane` 和 `JBViewport`。
- 安装 `Magnificator.CLIENT_PROPERTY_KEY`。
- 安装修饰键滚轮监听器和拖拽平移行为。
- 提供 `zoomIn`、`zoomOut`、`setZoomPercent`、`resetView`、`fitToView`、`fitWidth`、`fitHeight` 和 `focusRevision`。
- 统一维护缩放锚点、viewport position 裁剪和 zoom changed 通知。

Canvas 是 scale 的唯一状态来源；controller 负责改变这个状态及同步 viewport。

### JBScrollPane 与 JBViewport

以下行为完全交给平台：

- 普通滚轮和触摸板滚动。
- `Shift+滚轮`横向滚动。
- 平滑滚动、惯性和滚动边界。
- macOS pinch 的手势开始、缓存预览、实时 magnification 和结束提交。

实现代码不直接引用 `MacGestureAdapter`、`MouseGestureManager` 或 `ZoomingDelegate`。

## 坐标模型

系统明确区分三套坐标：

1. 世界坐标：`GraphLayout` 中未缩放的节点和连线坐标。
2. 内容坐标：Canvas 内经过缩放和留白后的像素坐标。
3. Viewport 坐标：当前可视区域内的坐标，原点为可视区域左上角。

转换公式为：

```text
content = contentOrigin + (world - graphBounds.min) × scale
world   = graphBounds.min + (content - contentOrigin) ÷ scale
```

公式必须正确处理 `graphBounds.minX/minY` 非零的情况。

### Canvas 尺寸和 contentOrigin

- Preferred width 为缩放后的图宽加左右固定留白。
- Preferred height 为缩放后的图高加上下固定留白。
- Canvas 实际尺寸小于 viewport extent 时，由 viewport 将 view 扩展到可视尺寸。
- 某一方向上图小于 viewport 时，该方向的 `contentOrigin` 居中计算。
- 图大于 viewport 时使用固定左侧或顶部留白。

## 缩放算法

所有缩放入口最终进入同一个底层 scale 更新路径。

### 锚点不变量

缩放前光标或 viewport 中心对应的世界坐标，缩放后仍然位于相同的 viewport 坐标：

```text
anchorWorld = contentToWorld(oldAnchorContent)
apply new scale and preferred size
newAnchorContent = worldToContent(anchorWorld)
newViewportPosition = newAnchorContent - anchorInViewport
```

最终 viewport position 必须裁剪到有效滚动范围。

### 原生 pinch

`Magnificator.magnify(factor, anchorContent)`执行以下操作：

1. 记录锚点对应的世界坐标。
2. 将当前 scale 乘以 factor 并限制到合法范围。
3. 更新 Canvas preferred size 并触发布局。
4. 返回同一世界点在新 scale 下的内容坐标。

该调用不直接设置 viewport position。平台默认 `ZoomingDelegate` 会根据返回坐标调整 scrollbar；自行再移动 viewport 会产生二次偏移。

### 修饰键滚轮

滚轮缩放因子使用完整的高精度旋转量：

```text
factor = 1.12 ^ (-preciseWheelRotation)
```

物理滚轮一个刻度约为 12%，高精度滚轮或 JBR 产生小数旋转量时仍能连续变化。监听器调用与 pinch 相同的底层锚点换算，再自行更新 viewport position。

### 工具栏

- Zoom In：当前比例乘以 `1.18`。
- Zoom Out：当前比例除以 `1.18`，不再使用不完全对称的 `.84`。
- 百分比输入：直接设置目标比例。
- 工具栏操作以 viewport 中心为锚点。
- 合法 scale 范围保持 `12%～350%`。

zoom changed 仅在实际 scale 变化时通知，避免百分比组件自触发循环。

## Fit、Reset 与 Focus

Fit 计算使用 `viewport.extentSize`，不能使用会随内容变化的 Canvas width/height。

- Fit All：同时考虑可视宽高，scale 最大不超过 100%；横向居中，纵向回到顶部留白。
- Fit Width：按可视宽度计算，横向居中，纵向回到顶部。
- Fit Height：按可视高度计算，纵向居中，横向回到左侧留白。
- 所有结果限制在 `12%～350%`。
- Reset：恢复 100%，viewport 回到左上角，Canvas 固定留白仍保留。

Focus 不再通过 `pendingFocusHash` 在绘制阶段修改相机：

- viewport 完成布局后由 controller 执行一次定位。
- 节点横向位于 viewport 中心。
- 纵向继续使用现有 `focusScreenY()`策略，使靠近 HEAD 的节点位于视口偏上区域。
- 第一次加载定位显式 `focusHash`，否则定位 HEAD。
- 后续图刷新保留 scale 和 viewport position，并根据新内容尺寸裁剪；显式 focus 优先。

## 拖拽平移

保留左键和中键拖拽：

```text
newViewportPosition = dragStartViewportPosition - mouseDragDelta
```

- 移动超过现有 2px 阈值才认定为拖拽。
- 未超过阈值的左键释放继续执行节点选择。
- 拖拽位置裁剪到合法滚动范围。
- 拖拽期间显示移动光标。
- 不增加自定义惯性；触摸板惯性由 `JBScrollPane` 负责。

## 事件消费规则

修饰键滚轮监听器安装在 `JBScrollPane`，Canvas 不安装滚轮监听器。

缩放事件精确判定如下：

```text
macOS:          Cmd/Meta，且 Ctrl/Alt/Shift 均未按下
Windows/Linux: Ctrl，且 Meta/Alt/Shift 均未按下
```

此外，滚轮旋转量必须非零。

- 普通滚轮不消费，由 `JBScrollPane` 原生处理。
- `Shift+滚轮`不消费，由平台执行横向滚动。
- `Alt+滚轮`及组合修饰键不触发缩放。
- 合法 `Cmd/Ctrl+滚轮`执行缩放并消费。
- 到达 12% 或 350% 后，合法缩放事件仍消费，避免突然退化为滚动。
- macOS pinch 直接走 `Magnificator`。
- Windows/Linux pinch 如果由 JBR 表示为 `Ctrl+wheel`，走修饰键滚轮路径。

不使用 `MouseGestureManager.hasTrackpad()`判断单次事件来源。它是全局能力状态，无法区分当前事件来自外接鼠标还是触摸板，会伤害同时使用两种设备的用户。

## 状态和异常边界

- 没有图数据时，zoom、fit 和 focus 安全 no-op。
- viewport 尺寸为零时，fit/focus 延迟到下一个 EDT 周期执行一次。
- 组件已 disposed 或延迟后尺寸仍为零时放弃操作，不循环重试。
- 图边界宽高最少按 1px 计算，避免除零。
- scale 永远限制在 `0.12～3.5`。
- viewport position 每次写入前都裁剪到合法范围。
- 缩放达到边界时不发送重复 zoom changed 通知。
- 绘制阶段不修改导航状态。

## 平台兼容性

只直接依赖以下公开 IntelliJ UI API：

- `JBScrollPane`
- `JBViewport`
- `Magnificator`
- `Magnificator.CLIENT_PROPERTY_KEY`

继续支持项目当前声明的版本：

- IntelliJ IDEA 2025.3.6
- IntelliJ IDEA 2026.1.4
- IntelliJ IDEA 2026.2.1
- `sinceBuild = 253`

不直接依赖 Apple gesture 类、IntelliJ action system implementation 或 Graph/yFiles implementation。

## 测试策略

### 纯计算测试

- 世界坐标与内容坐标双向转换。
- 非零 `graphBounds.minX/minY`。
- 小图居中与大图固定留白。
- 从小图状态缩放到大图状态时锚点保持。
- 光标锚点缩放前后的世界坐标不变。
- 12% 和 350% 上下限。
- `preciseWheelRotation`方向、大小和小数值。
- macOS Cmd 与 Windows/Linux Ctrl 判定。
- Shift、Alt 和组合修饰键不触发缩放。
- Fit All、Fit Width、Fit Height 使用 viewport extent。
- focus、reset 和拖拽位置裁剪。

### Swing 组件测试

- `JBViewport` 的直接 view 是 Canvas。
- Canvas 没有 `MouseWheelListener`。
- Canvas 存在 `Magnificator` client property。
- 普通滚轮不改变 scale，并能改变垂直滚动位置。
- `Shift+滚轮`不改变 scale，并能改变水平滚动位置。
- 合法修饰键滚轮改变 scale、保持锚点并消费事件。
- 缩放到边界时事件仍被消费。
- `Magnificator`返回正确的新内容坐标。
- 工具栏和 `Magnificator`共享 scale。
- 现有选择、右键菜单和绘制测试继续通过。

### 人工测试

macOS 触摸板：

- 双指纵向、横向和斜向滑动。
- 惯性滚动。
- pinch 实时预览、提交和焦点保持。
- pinch 后使用外接鼠标，`Cmd+滚轮`仍然可用。

macOS 鼠标：

- 普通滚轮、`Shift+滚轮`和 `Cmd+滚轮`。
- 拖拽平移与单击选择不冲突。

Windows/Linux：

- 普通鼠标、高精度滚轮和 `Ctrl+滚轮`。
- 触摸板双指滚动。
- 支持 pinch 的设备验证 JBR 实际事件路径。

通用场景：

- 大图、小图、空图、最小和最大缩放。
- Fit、1:1、百分比输入和定位 HEAD。
- Legend 可见时只有 legend 区域拦截事件，透明区域不阻断画布。
- 滚动到内容边界后保留 IntelliJ 原生的外层滚动传递行为。

## 验收标准

1. macOS 触摸板双指滑动只平移，不再缩放。
2. macOS pinch 使用平台原生预览和提交，并保持手势中心。
3. 普通鼠标滚轮滚动，macOS Cmd 或 Windows/Linux Ctrl 加滚轮缩放。
4. 缩放、fit、focus 和拖拽均通过 viewport 模型实现，不再修改绘图平移偏移。
5. 现有图绘制、选择、上下文菜单和工具栏功能无回归。
6. 自动化测试、源码样式检查和三组目标 IDE plugin verification 全部通过。

最终验证命令：

```bash
./gradlew test verifySourceStyle
./gradlew verifyPlugin
```

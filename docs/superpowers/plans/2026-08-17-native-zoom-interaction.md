# IntelliJ Native Zoom Interaction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace unconditional wheel zooming with IntelliJ-native scrolling and pinch support while retaining focal modifier-wheel zoom for mouse users.

**Architecture:** Make `RevisionGraphCanvas` the direct view of a `JBScrollPane/JBViewport`, keep rendering state on the Canvas, and introduce a focused `RevisionGraphViewportController` for zoom, fit, focus, drag-pan, and viewport synchronization. Put coordinate and fitting calculations in a small pure Kotlin geometry unit so focal invariants and platform modifier semantics are testable without Swing event delivery.

**Tech Stack:** Kotlin 2.4.10, JVM 21, Swing, IntelliJ Platform UI API (`JBScrollPane`, `JBViewport`, `Magnificator`, `ClientProperty`), kotlin-test/JUnit 4 compatibility runner, Gradle IntelliJ Platform Plugin 2.18.1.

## Global Constraints

- Start from the current mainline worktree; do not reference or copy `tmp/xxx`.
- Keep `sinceBuild = 253` and verify IntelliJ IDEA 2025.3.6, 2026.1.4, and 2026.2.1.
- Directly depend only on public UI APIs: `JBScrollPane`, `JBViewport`, `Magnificator`, and `Magnificator.CLIENT_PROPERTY_KEY`/`ClientProperty`.
- Do not directly reference `MacGestureAdapter`, `MouseGestureManager`, `ZoomingDelegate`, Apple gesture classes, or IntelliJ Graph/yFiles implementation classes.
- Ordinary wheel and trackpad two-finger events must remain unconsumed by plugin zoom code.
- macOS zoom wheel is exact Cmd/Meta only; Windows/Linux zoom wheel is exact Ctrl only.
- Preserve scale bounds `0.12..3.0`, existing graph visuals, selection semantics, context menus, cards, legend, watermark, and loading flow.
- Fit calculations use viewport extent, not Canvas size; painting must not mutate navigation state.
- Follow test-first order and keep each task independently green before continuing.

---

## File Structure

- Create `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometry.kt`: pure coordinate conversion, content sizing, fit-scale, modifier matching, wheel factor, and viewport clamping.
- Create `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometryTest.kt`: exhaustive pure tests for the geometry and event predicates.
- Modify `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvas.kt`: remove wheel zoom and offset-based camera state; expose scale/layout primitives required by the controller; paint in content coordinates.
- Create `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasNavigationTest.kt`: Canvas preferred size, transform, scale, and listener-boundary tests.
- Create `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportController.kt`: native scrollpane composition, Magnificator registration, focal zoom, fit/focus/reset, wheel routing, and drag-pan.
- Create `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportControllerTest.kt`: component topology, Magnificator, wheel consumption, anchor, fit/focus, and panning tests.
- Modify `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphView.kt`: wire the controller into the existing cards, toolbar, locator, and data provider.
- Modify `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasPaintingTest.kt`: adapt painting fixtures to content-coordinate centering without weakening pixel assertions.

---

### Task 1: Pure Viewport Geometry and Input Semantics

**Files:**
- Create: `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometry.kt`
- Create: `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometryTest.kt`

**Interfaces:**
- Consumes: `java.awt.Dimension`, `java.awt.Point`, `java.awt.event.InputEvent`, `java.awt.geom.Point2D`, `java.awt.geom.Rectangle2D`.
- Produces: `GraphViewportGeometry`, `FitMode`, `fitScale(...)`, `clampViewPosition(...)`, `isZoomWheelModifiers(...)`, `wheelZoomFactor(...)`, and shared scale/padding constants.

- [ ] **Step 1: Write failing geometry tests**

Create tests with exact expectations for non-zero graph origins, centering, large content, inverse transforms, and focal invariance:

```kotlin
class RevisionGraphViewportGeometryTest {
    @Test fun `world and content coordinates round trip with non-zero bounds`() {
        val geometry = GraphViewportGeometry(
            Rectangle2D.Double(100.0, 50.0, 200.0, 100.0),
            scale = 2.0,
            viewportExtent = Dimension(300, 200),
        )

        val content = geometry.worldToContent(Point2D.Double(150.0, 75.0))

        assertEquals(Point2D.Double(128.0, 74.0), content)
        assertPointEquals(Point2D.Double(150.0, 75.0), geometry.contentToWorld(content))
    }

    @Test fun `small content is centered and large content keeps fixed padding`() {
        val bounds = Rectangle2D.Double(0.0, 0.0, 100.0, 50.0)
        val small = GraphViewportGeometry(bounds, 1.0, Dimension(400, 300))
        val large = GraphViewportGeometry(bounds, 5.0, Dimension(400, 300))

        assertPointEquals(Point2D.Double(150.0, 125.0), small.contentOrigin)
        assertPointEquals(Point2D.Double(28.0, 25.0), large.contentOrigin)
        assertEquals(Dimension(556, 300), large.viewSize)
    }

    @Test fun `same world anchor survives transition from centered to scrollable`() {
        val bounds = Rectangle2D.Double(0.0, 0.0, 200.0, 100.0)
        val oldGeometry = GraphViewportGeometry(bounds, .5, Dimension(300, 200))
        val anchorWorld = oldGeometry.contentToWorld(Point2D.Double(150.0, 100.0))
        val newGeometry = GraphViewportGeometry(bounds, 2.0, Dimension(300, 200))

        assertPointEquals(Point2D.Double(228.0, 124.0), newGeometry.worldToContent(anchorWorld))
    }
}
```

Use an `assertPointEquals(expected, actual, tolerance = 0.0001)` helper in the test file so floating-point comparisons report both axes.

Define the shared graph fixture in the same test file so no production fixture API is introduced:

```kotlin
internal fun graphFixture(
    graphBounds: Rectangle2D.Double,
    nodeBounds: Rectangle2D.Double = graphBounds,
    hash: String = "a".repeat(40),
): Pair<GraphSnapshot, GraphLayout> {
    val node = NodeLayout(hash, nodeBounds, 0, 0)
    return GraphSnapshot(
        commits = listOf(CommitNode(hash, emptyList(), 0, "test")),
        refsByCommit = emptyMap(),
        head = HeadState(hash, "main", false),
    ) to GraphLayout(listOf(node), emptyList(), graphBounds, SpatialIndex(64.0, listOf(node)))
}
```

Keep `graphFixture` and `assertPointEquals` as `internal` top-level test helpers so the Canvas and controller test files in the same package can reuse them.

- [ ] **Step 2: Write failing fit, wheel, and clamp tests**

Add focused tests:

```kotlin
@Test fun `fit modes use viewport extent and never exceed one hundred percent`() {
    val bounds = Rectangle2D.Double(0.0, 0.0, 800.0, 600.0)
    val extent = Dimension(456, 348)

    assertEquals(.5, fitScale(bounds, extent, FitMode.ALL), .0001)
    assertEquals(.5, fitScale(bounds, extent, FitMode.WIDTH), .0001)
    assertEquals(.5, fitScale(bounds, extent, FitMode.HEIGHT), .0001)
    assertEquals(1.0, fitScale(Rectangle2D.Double(0.0, 0.0, 10.0, 10.0), extent, FitMode.ALL))
}

@Test fun `zoom modifiers are exact for each platform`() {
    assertTrue(isZoomWheelModifiers(InputEvent.META_DOWN_MASK, isMac = true))
    assertFalse(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK, isMac = true))
    assertTrue(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK, isMac = false))
    assertFalse(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, isMac = false))
}

@Test fun `precise wheel rotation controls direction and magnitude`() {
    assertEquals(1.12, wheelZoomFactor(-1.0), .0001)
    assertEquals(1.0 / 1.12, wheelZoomFactor(1.0), .0001)
    assertEquals(kotlin.math.sqrt(1.12), wheelZoomFactor(-.5), .0001)
}

@Test fun `view position is clamped to content bounds`() {
    assertEquals(Point(0, 0), clampViewPosition(Point(-20, -10), Dimension(900, 700), Dimension(300, 200)))
    assertEquals(Point(600, 500), clampViewPosition(Point(800, 900), Dimension(900, 700), Dimension(300, 200)))
}
```

- [ ] **Step 3: Run tests and verify the new API is missing**

Run:

```bash
./gradlew test --tests '*RevisionGraphViewportGeometryTest'
```

Expected: compilation fails because `GraphViewportGeometry`, `FitMode`, and helper functions do not exist.

- [ ] **Step 4: Implement the pure geometry API**

Create the source file with these exact signatures:

```kotlin
internal const val MIN_GRAPH_SCALE = .12
internal const val MAX_GRAPH_SCALE = 3.0
internal const val GRAPH_HORIZONTAL_PADDING = 28.0
internal const val GRAPH_VERTICAL_PADDING = 24.0

internal enum class FitMode { ALL, WIDTH, HEIGHT }

internal data class GraphViewportGeometry(
    val graphBounds: Rectangle2D.Double,
    val scale: Double,
    val viewportExtent: Dimension,
) {
    val viewSize: Dimension
    val contentOrigin: Point2D.Double

    init {
        val graphWidth = graphBounds.width.coerceAtLeast(1.0) * scale
        val graphHeight = graphBounds.height.coerceAtLeast(1.0) * scale
        val preferredWidth = ceil(graphWidth + GRAPH_HORIZONTAL_PADDING * 2).toInt()
        val preferredHeight = ceil(graphHeight + GRAPH_VERTICAL_PADDING * 2).toInt()
        viewSize = Dimension(max(viewportExtent.width, preferredWidth), max(viewportExtent.height, preferredHeight))
        contentOrigin = Point2D.Double(
            max(GRAPH_HORIZONTAL_PADDING, (viewSize.width - graphWidth) / 2.0),
            max(GRAPH_VERTICAL_PADDING, (viewSize.height - graphHeight) / 2.0),
        )
    }

    fun worldToContent(point: Point2D): Point2D.Double = Point2D.Double(
        contentOrigin.x + (point.x - graphBounds.minX) * scale,
        contentOrigin.y + (point.y - graphBounds.minY) * scale,
    )

    fun contentToWorld(point: Point2D): Point2D.Double = Point2D.Double(
        graphBounds.minX + (point.x - contentOrigin.x) / scale,
        graphBounds.minY + (point.y - contentOrigin.y) / scale,
    )
}
```

Implement `fitScale` by subtracting 56 horizontal and 48 vertical pixels from extent, clamping each available dimension to at least 1, taking the requested ratio, limiting fit to 1.0, then clamping to `MIN_GRAPH_SCALE..MAX_GRAPH_SCALE`. Implement the exact modifier masks and `1.12.pow(-preciseRotation)`. Clamp requested view position to `0..max(0, viewSize - extent)` per axis.

- [ ] **Step 5: Run the geometry tests**

Run:

```bash
./gradlew test --tests '*RevisionGraphViewportGeometryTest'
```

Expected: all geometry tests pass.

- [ ] **Step 6: Commit the geometry unit**

```bash
git add src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometry.kt \
  src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportGeometryTest.kt
git commit -m "Add viewport navigation geometry"
```

---

### Task 2: Convert Canvas from Camera to Scrollable Content

**Files:**
- Modify: `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvas.kt:39-254,599-605`
- Create: `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasNavigationTest.kt`
- Modify: `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasPaintingTest.kt:20-74`

**Interfaces:**
- Consumes: `GraphViewportGeometry`, scale bounds and padding constants from Task 1.
- Produces: `graphScale`, `graphBounds`, `graphGeometry(extent)`, `setGraphScale(scale)`, `nodeBounds(hash)`, `showGraph(...)`, `selectAndLocateRevision(...)`, and content/world conversion methods used by Task 3.

- [ ] **Step 1: Write failing Canvas navigation tests**

Create a fixture with a non-zero-bound `GraphLayout` and assert the new content behavior:

```kotlin
class RevisionGraphCanvasNavigationTest {
    @Test fun `canvas has no wheel listener and preferred size follows scaled graph`() {
        val (snapshot, layout) = graphFixture(Rectangle2D.Double(100.0, 50.0, 200.0, 100.0))
        val canvas = RevisionGraphCanvas()

        canvas.showGraph(snapshot, layout)

        assertTrue(canvas.mouseWheelListeners.isEmpty())
        assertEquals(Dimension(256, 148), canvas.preferredSize)
        assertTrue(canvas.setGraphScale(2.0))
        assertEquals(Dimension(456, 248), canvas.preferredSize)
    }

    @Test fun `canvas transformations use graph minimum and actual view size`() {
        val (snapshot, layout) = graphFixture(Rectangle2D.Double(100.0, 50.0, 200.0, 100.0))
        val canvas = RevisionGraphCanvas().apply {
            showGraph(snapshot, layout)
            setSize(400, 300)
        }

        assertPointEquals(Point2D.Double(150.0, 125.0), canvas.worldToContent(Point2D.Double(100.0, 50.0))!!)
        assertPointEquals(Point2D.Double(100.0, 50.0), canvas.contentToWorld(Point2D.Double(150.0, 125.0))!!)
    }

    @Test fun `setting the same or clamped scale reports no duplicate change`() {
        val canvas = canvasWithGraph()
        assertFalse(canvas.setGraphScale(1.0))
        assertTrue(canvas.setGraphScale(10.0))
        assertEquals(3.0, canvas.graphScale)
        assertFalse(canvas.setGraphScale(10.0))
    }
}
```

- [ ] **Step 2: Run the Canvas test and verify it fails**

Run:

```bash
./gradlew test --tests '*RevisionGraphCanvasNavigationTest'
```

Expected: compilation fails because the Canvas content API is not defined.

- [ ] **Step 3: Refactor Canvas state and public navigation primitives**

Make these structural changes:

```kotlin
internal val graphScale: Double get() = scale
internal val graphBounds: Rectangle2D.Double? get() = layout?.bounds

internal fun setGraphScale(requested: Double): Boolean {
    val next = requested.coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
    if (next == scale) return false
    scale = next
    revalidate()
    repaint()
    return true
}

override fun getPreferredSize(): Dimension {
    val bounds = layout?.bounds ?: return super.getPreferredSize()
    return Dimension(
        ceil(bounds.width.coerceAtLeast(1.0) * scale + GRAPH_HORIZONTAL_PADDING * 2).toInt(),
        ceil(bounds.height.coerceAtLeast(1.0) * scale + GRAPH_VERTICAL_PADDING * 2).toInt(),
    )
}

internal fun graphGeometry(extent: Dimension): GraphViewportGeometry? =
    layout?.bounds?.let { GraphViewportGeometry(it, scale, extent) }

internal fun worldToContent(point: Point2D): Point2D.Double? = graphGeometry(size)?.worldToContent(point)
internal fun contentToWorld(point: Point2D): Point2D.Double? = graphGeometry(size)?.contentToWorld(point)
```

Rename `show` to `showGraph` and make it return the requested initial focus hash:

```kotlin
internal fun showGraph(snapshot: GraphSnapshot, layout: GraphLayout, focusHash: String? = null): String? {
    val firstGraph = this.layout == null
    this.snapshot = snapshot
    this.layout = layout
    relationshipCache = null
    selection = selection.retain(layout.byHash.keys)
    retainCompareRevisions()
    revalidate()
    repaint()
    return focusHash?.takeIf(layout.byHash::containsKey)
        ?: snapshot.head.hash?.takeIf { firstGraph && it in layout.byHash }
}
```

Remove `offsetX`, `offsetY`, `pendingFocusHash`, all Canvas zoom/fit/reset methods, the wheel listener, and `applyPendingFocus`. Keep click/context/hover behavior; on drag only update the `dragged` flag and cursor, leaving viewport movement to Task 3.

- [ ] **Step 4: Convert painting and hit testing to content coordinates**

In `paintComponent`, calculate geometry from the actual Canvas size and apply its origin:

```kotlin
val geometry = GraphViewportGeometry(graph.bounds, scale, size)
val g2 = (g.create() as Graphics2D).apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
    translate(geometry.contentOrigin.x, geometry.contentOrigin.y)
    scale(scale, scale)
    translate(-graph.bounds.minX, -graph.bounds.minY)
}
```

Replace `screenToWorld` with `contentToWorld` based on `GraphViewportGeometry(graph.bounds, scale, size)`. Use the converted Canvas clip for spatial-index culling. Update `hitTarget` and context-menu point conversion through the same method.

Add:

```kotlin
internal fun nodeBounds(hash: String): Rectangle2D.Double? = layout?.byHash?.get(hash)?.bounds
internal fun selectAndLocateRevision(hash: String, revision: String): Boolean
```

`selectAndLocateRevision` retains the current selection update but returns `false` when the hash is absent and no longer stores pending focus.

- [ ] **Step 5: Adapt painting tests without weakening assertions**

Because small graphs are now centered, size the test Canvas to its preferred size before painting:

```kotlin
val canvas = RevisionGraphCanvas().apply {
    showGraph(snapshot, layout)
    size = preferredSize
}
```

Update the neutral-underlay scan to `y = 24..54` and keep `x = 28..34`; keep the clipping assertion at image point `(40, 50)`. These coordinates preserve the original assertions with the new fixed 28px horizontal and 24px vertical origin.

- [ ] **Step 6: Run Canvas and existing UI tests**

Run:

```bash
./gradlew test --tests '*RevisionGraphCanvasNavigationTest' \
  --tests '*RevisionGraphCanvasPaintingTest' \
  --tests '*RevisionSelectionTest'
```

Expected: all selected tests pass.

- [ ] **Step 7: Commit the Canvas content refactor**

```bash
git add src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvas.kt \
  src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasNavigationTest.kt \
  src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphCanvasPaintingTest.kt
git commit -m "Make revision graph canvas scrollable content"
```

---

### Task 3: Native Viewport Controller

**Files:**
- Create: `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportController.kt`
- Create: `src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportControllerTest.kt`

**Interfaces:**
- Consumes: Canvas content API from Task 2 and geometry helpers from Task 1.
- Produces: `RevisionGraphViewportController.canvas`, `.scrollPane`, `.onZoomChanged`, `showGraph`, `locateRevision`, `containsRevision`, `zoomPercent`, `zoomIn`, `zoomOut`, `setZoomPercent`, `resetView`, `fitToView`, `fitWidth`, `fitHeight`, and `focusRevision`.

- [ ] **Step 1: Write failing component-topology and Magnificator tests**

```kotlin
class RevisionGraphViewportControllerTest {
    @Test fun `controller installs canvas directly in JBViewport with magnificator`() = onEdt {
        val controller = RevisionGraphViewportController(RevisionGraphCanvas(), isMac = true)

        assertSame(controller.canvas, controller.scrollPane.viewport.view)
        assertTrue(controller.scrollPane.viewport is JBViewport)
        assertNotNull(ClientProperty.get(controller.canvas, Magnificator.CLIENT_PROPERTY_KEY))
    }

    @Test fun `magnificator updates scale and returns remapped anchor`() = onEdt {
        val controller = controllerWithGraph(extent = Dimension(300, 200))
        val anchor = Point(150, 100)
        val before = controller.canvas.contentToWorld(anchor)!!
        val magnificator = ClientProperty.get(controller.canvas, Magnificator.CLIENT_PROPERTY_KEY)!!

        val remapped = magnificator.magnify(2.0, anchor)

        assertEquals(200, controller.zoomPercent())
        assertPointEquals(before, controller.canvas.contentToWorld(remapped)!!)
    }
}
```

The test helper `onEdt` runs the body via `SwingUtilities.invokeAndWait`, captures the result or exception, and rethrows failures on the test thread.

Define the helpers in the same test file:

```kotlin
private fun onEdt(block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
}

private fun controllerWithGraph(
    extent: Dimension = Dimension(300, 200),
    isMac: Boolean = false,
    bounds: Rectangle2D.Double = Rectangle2D.Double(0.0, 0.0, 800.0, 600.0),
): RevisionGraphViewportController {
    val (snapshot, layout) = graphFixture(bounds)
    return RevisionGraphViewportController(RevisionGraphCanvas(), isMac).also { controller ->
        controller.showGraph(snapshot, layout)
        controller.scrollPane.setSize(extent)
        controller.scrollPane.doLayout()
        controller.canvas.size = controller.canvas.preferredSize
    }
}

private fun controllerWithLargeGraph(isMac: Boolean = false) = controllerWithGraph(
    extent = Dimension(300, 200),
    isMac = isMac,
    bounds = Rectangle2D.Double(0.0, 0.0, 1_200.0, 900.0),
)

private fun wheelEvent(
    source: Component,
    point: Point = Point(100, 80),
    modifiers: Int,
    preciseRotation: Double,
): MouseWheelEvent = MouseWheelEvent(
    source,
    MouseEvent.MOUSE_WHEEL,
    System.currentTimeMillis(),
    modifiers,
    point.x,
    point.y,
    0,
    false,
    MouseWheelEvent.WHEEL_UNIT_SCROLL,
    3,
    preciseRotation.toInt(),
    preciseRotation,
)
```

Build the focus fixture with the shared helper so the node center and graph bounds are explicit:

```kotlin
private fun controllerWithGraphContainingNode(center: Point2D.Double): RevisionGraphViewportController {
    val nodeBounds = Rectangle2D.Double(center.x - 30.0, center.y - 15.0, 60.0, 30.0)
    val (snapshot, layout) = graphFixture(
        graphBounds = Rectangle2D.Double(0.0, 0.0, 1_200.0, 900.0),
        nodeBounds = nodeBounds,
        hash = "target",
    )
    return RevisionGraphViewportController(RevisionGraphCanvas(), isMac = false).also { controller ->
        controller.showGraph(snapshot, layout)
        controller.scrollPane.setSize(300, 200)
        controller.scrollPane.doLayout()
        controller.canvas.size = controller.canvas.preferredSize
    }
}
```

- [ ] **Step 2: Write failing wheel-routing and anchor tests**

Construct `MouseWheelEvent` instances with explicit modifier masks:

```kotlin
@Test fun `ordinary wheel is ignored while mac command wheel zooms and consumes`() = onEdt {
    val controller = controllerWithGraph(isMac = true)
    val ordinary = wheelEvent(controller.scrollPane, modifiers = 0, preciseRotation = 1.0)
    val command = wheelEvent(controller.scrollPane, modifiers = InputEvent.META_DOWN_MASK, preciseRotation = -1.0)

    controller.handleWheel(ordinary)
    assertEquals(100, controller.zoomPercent())
    assertFalse(ordinary.isConsumed)

    controller.handleWheel(command)
    assertEquals(112, controller.zoomPercent())
    assertTrue(command.isConsumed)
}

@Test fun `modified wheel keeps world point under cursor`() = onEdt {
    val controller = controllerWithLargeGraph(isMac = false)
    controller.scrollPane.viewport.viewPosition = Point(200, 150)
    val event = wheelEvent(
        controller.scrollPane,
        point = Point(120, 80),
        modifiers = InputEvent.CTRL_DOWN_MASK,
        preciseRotation = -1.0,
    )
    val contentBefore = SwingUtilities.convertPoint(controller.scrollPane, event.point, controller.canvas)
    val worldBefore = controller.canvas.contentToWorld(contentBefore)!!

    controller.handleWheel(event)

    val contentAfter = SwingUtilities.convertPoint(controller.scrollPane, event.point, controller.canvas)
    assertPointEquals(worldBefore, controller.canvas.contentToWorld(contentAfter)!!)
}
```

Also test exact modifier rejection, zero rotation, and consumption at min/max.

- [ ] **Step 3: Write failing fit, reset, focus, and pan tests**

```kotlin
@Test fun `fit uses viewport extent and reset returns to actual size origin`() = onEdt {
    val controller = controllerWithLargeGraph(extent = Dimension(456, 348))

    controller.fitToView()
    assertEquals(50, controller.zoomPercent())

    controller.resetView()
    assertEquals(100, controller.zoomPercent())
    assertEquals(Point(0, 0), controller.scrollPane.viewport.viewPosition)
}

@Test fun `focus centers node horizontally and uses focus screen y`() = onEdt {
    val controller = controllerWithGraphContainingNode(center = Point2D.Double(500.0, 600.0))

    assertTrue(controller.focusRevisionNow("target"))

    val nodeContent = controller.canvas.worldToContent(Point2D.Double(500.0, 600.0))!!
    val viewport = controller.scrollPane.viewport
    assertEquals(viewport.extentSize.width / 2, nodeContent.x.toInt() - viewport.viewPosition.x, 1)
    assertEquals(focusScreenY(viewport.extentSize.height, contentAbove = 600.0).toInt(), nodeContent.y.toInt() - viewport.viewPosition.y, 1)
}

@Test fun `drag pan moves viewport opposite to pointer delta and clamps`() = onEdt {
    val controller = controllerWithLargeGraph()
    controller.scrollPane.viewport.viewPosition = Point(200, 200)

    controller.beginPan(Point(100, 100))
    controller.continuePan(Point(130, 150))

    assertEquals(Point(170, 150), controller.scrollPane.viewport.viewPosition)
}
```

`focusRevisionNow`, `beginPan`, and `continuePan` remain `internal` so tests can exercise deterministic navigation without depending on the system event queue.

- [ ] **Step 4: Run controller tests and verify failure**

Run:

```bash
./gradlew test --tests '*RevisionGraphViewportControllerTest'
```

Expected: compilation fails because the controller does not exist.

- [ ] **Step 5: Implement controller composition and native Magnificator**

Create the controller with this shape:

```kotlin
internal class RevisionGraphViewportController(
    internal val canvas: RevisionGraphCanvas,
    private val isMac: Boolean = SystemInfo.isMac,
) {
    internal val scrollPane = JBScrollPane(canvas).apply {
        border = JBUI.Borders.empty()
        viewport.background = canvas.background
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }
    internal var onZoomChanged: ((Int) -> Unit)? = null

    init {
        ClientProperty.put(canvas, Magnificator.CLIENT_PROPERTY_KEY, Magnificator(::magnify))
        scrollPane.addMouseWheelListener(::handleWheel)
        installPanHandler()
    }
}
```

`magnify(factor, anchorContent)` must calculate the world anchor with old geometry, call `setGraphScale(oldScale * factor)`, validate the viewport layout, and return the same world anchor through new geometry. It must not set `viewPosition`.

- [ ] **Step 6: Implement focal wheel zoom and toolbar zoom**

Add:

```kotlin
internal fun handleWheel(event: MouseWheelEvent) {
    if (event.preciseWheelRotation == 0.0 || !isZoomWheelModifiers(event.modifiersEx, isMac)) return
    event.consume()
    val anchorInViewport = SwingUtilities.convertPoint(scrollPane, event.point, scrollPane.viewport)
    val anchorInContent = SwingUtilities.convertPoint(scrollPane, event.point, canvas)
    val remapped = applyScaleAtContentAnchor(wheelZoomFactor(event.preciseWheelRotation), anchorInContent) ?: return
    validateLayout()
    setViewPosition(Point(remapped.x - anchorInViewport.x, remapped.y - anchorInViewport.y))
}
```

Consume a recognized non-zero event even when the scale is already clamped. `zoomIn`, `zoomOut`, and `setZoomPercent` use the viewport center as the anchor. Zoom out divides by 1.18. Notify `onZoomChanged` only when Canvas scale actually changes.

- [ ] **Step 7: Implement fit, reset, focus, show, locate, and drag-pan**

- `fitToView`, `fitWidth`, and `fitHeight` use `fitScale(canvas.graphBounds, viewport.extentSize, mode)`.
- Apply fit positioning exactly as specified: all/width top-align, height left-align, centered axes come from content geometry.
- `resetView` applies scale 1.0 and view position `(0, 0)`.
- `showGraph` calls `canvas.showGraph`; if it returns a focus hash, schedule one `SwingUtilities.invokeLater { focusRevisionNow(hash) }` when extent is not ready.
- `locateRevision` calls `canvas.selectAndLocateRevision` then `focusRevision`.
- `focusRevisionNow` converts node center to content coordinates, calculates the desired view position using `focusScreenY`, and clamps it.
- Install a `MouseAdapter` on Canvas that records press point and starting viewport position for left/middle buttons, pans only after 2px movement, and clears state on release.

- [ ] **Step 8: Run controller and Canvas tests**

Run:

```bash
./gradlew test --tests '*RevisionGraphViewportControllerTest' \
  --tests '*RevisionGraphCanvasNavigationTest' \
  --tests '*RevisionGraphCanvasPaintingTest'
```

Expected: all selected tests pass.

- [ ] **Step 9: Commit the native controller**

```bash
git add src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportController.kt \
  src/test/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphViewportControllerTest.kt
git commit -m "Add native revision graph viewport controller"
```

---

### Task 4: Integrate the Controller and Verify Supported IDEs

**Files:**
- Modify: `src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphView.kt:119-122,228-234,292,323,357-362,460-486,510-540,623`
- Modify: `src/test/kotlin/io/github/noodles_studio/revisiongraph/ArchitectureBoundaryTest.kt`

**Interfaces:**
- Consumes: complete `RevisionGraphViewportController` API from Task 3.
- Produces: user-visible IntelliJ-native scroll, pinch, focal mouse zoom, fit/focus, and drag-pan behavior in the Revision Graph tool window.

- [ ] **Step 1: Write the view-wiring boundary assertion**

Add a focused source-boundary test to `ArchitectureBoundaryTest` while retaining the runtime topology assertion in `RevisionGraphViewportControllerTest`:

```kotlin
@Test fun `revision graph routes navigation through native viewport controller`() {
    val canvasSource = SOURCE_ROOT.resolve("ui/RevisionGraphCanvas.kt").toFile().readText()
    val viewSource = SOURCE_ROOT.resolve("ui/RevisionGraphView.kt").toFile().readText()

    assertFalse(canvasSource.contains("addMouseWheelListener"))
    assertTrue(viewSource.contains("RevisionGraphViewportController"))
}
```

- [ ] **Step 2: Run the integration assertion and verify failure**

Run:

```bash
./gradlew test --tests '*ArchitectureBoundaryTest'
```

Expected: the new controller wiring assertion fails before `RevisionGraphView` is changed.

- [ ] **Step 3: Replace direct Canvas wiring in RevisionGraphView**

Create and expose the components in this order:

```kotlin
private val canvas = RevisionGraphCanvas(typography)
private val viewportController = RevisionGraphViewportController(canvas)
private val graphContextComponent = UiDataProvider.wrapComponent(viewportController.scrollPane, UiDataProvider { sink ->
    sink.set(CommonDataKeys.PROJECT, project)
})
```

Then replace navigation calls:

```text
canvas.zoomPercent()             -> viewportController.zoomPercent()
canvas.setZoomPercent(value)     -> viewportController.setZoomPercent(value)
canvas.zoomIn()/zoomOut()        -> viewportController.zoomIn()/zoomOut()
canvas.fitToView/Width/Height()  -> viewportController.fitToView/Width/Height()
canvas.focusRevision(hash)       -> viewportController.focusRevision(hash)
canvas.locateRevision(...)       -> viewportController.locateRevision(...)
canvas.show(...)                 -> viewportController.showGraph(...)
canvas.onZoomChanged             -> viewportController.onZoomChanged
```

Selection, context menu, revision callbacks, reference visibility, and `containsRevision` that do not move the viewport may continue to use Canvas directly. Set action-toolbar target components to `graphContextComponent` or `viewportController.scrollPane`, not the raw Canvas.

- [ ] **Step 4: Run all automated tests and source style**

Run:

```bash
./gradlew test verifySourceStyle
```

Expected: all tests and style checks pass.

- [ ] **Step 5: Run plugin verification for all configured IDEs**

Run:

```bash
./gradlew verifyPlugin
```

Expected: plugin verification succeeds for IntelliJ IDEA 2025.3.6, 2026.1.4, and 2026.2.1 with no compatibility errors introduced by the new UI APIs.

- [ ] **Step 6: Inspect final diff for interaction regressions**

Run:

```bash
git diff --check
git diff --stat HEAD~3..HEAD
rg -n "addMouseWheelListener|offsetX|offsetY|pendingFocusHash|MouseGestureManager|ZoomingDelegate" src/main/kotlin
```

Expected:

- No whitespace errors.
- The only added wheel listener is on `JBScrollPane` inside `RevisionGraphViewportController`.
- `offsetX`, `offsetY`, and `pendingFocusHash` are absent from `RevisionGraphCanvas`.
- No direct `MouseGestureManager` or `ZoomingDelegate` dependency exists.

- [ ] **Step 7: Commit the view integration**

```bash
git add src/main/kotlin/io/github/noodles_studio/revisiongraph/ui/RevisionGraphView.kt \
  src/test/kotlin/io/github/noodles_studio/revisiongraph/ArchitectureBoundaryTest.kt
git commit -m "Use IntelliJ native graph zoom interactions"
```

- [ ] **Step 8: Record manual verification still required**

Automated tests cannot synthesize macOS native magnification. In the final handoff, explicitly list these remaining manual checks:

```text
macOS trackpad: two-finger pan, diagonal/inertial scroll, pinch preview and focal commit
macOS mouse: ordinary scroll, Shift horizontal scroll, Cmd-wheel focal zoom
Hybrid Mac: pinch once, then verify external mouse Cmd-wheel still zooms
Windows/Linux: ordinary scroll, Ctrl-wheel zoom, available touchpad pinch path
Common: min/max zoom, fit modes, 1:1, locator focus, selection versus drag threshold, legend overlay
```

Do not claim native pinch was manually verified unless it was actually exercised in a running IDE.

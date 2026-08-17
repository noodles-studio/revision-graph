package io.github.noodles_studio.revisiongraph.ui

import com.intellij.ui.JBColor
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.layout.NodeLayout
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.EdgeKey
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import io.github.noodles_studio.revisiongraph.model.RevisionRelationship
import io.github.noodles_studio.revisiongraph.model.relationship
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** RevisionGraph-style block topology with IDEA-aware colors and interaction. */
internal class RevisionGraphCanvas(private val typography: GraphTypography = GraphTypography.fromIdeaDefaults()) : JComponent() {
    internal var onContextMenu: ((RevisionCompareSelection, Point) -> Unit)? = null
    internal var onRevisionSelected: ((CompareRevision) -> Unit)? = null
    private var snapshot: GraphSnapshot? = null
    private var layout: GraphLayout? = null
    private var scale = 1.0
    private var selection = RevisionSelection.EMPTY
    private var relationshipCache: RelationshipCache? = null
    private val compareRevisionsByHash = mutableMapOf<String, CompareRevision>()
    private var dragStart: Point? = null
    private var dragged = false
    private var pressedButton = MouseEvent.NOBUTTON
    private var popupHandledOnPress = false
    private var visibleRefKinds = RefKind.entries.toSet()

    private val laneColors = arrayOf(
        JBColor(Color(0x397BC2), Color(0x5897D5)), JBColor(Color(0x37865A), Color(0x57A877)),
        JBColor(Color(0x7B5CAD), Color(0x9C7BC8)), JBColor(Color(0xB66A2D), Color(0xD38B4B)),
        JBColor(Color(0xB94E6A), Color(0xD36B83)), JBColor(Color(0x368694), Color(0x55A5B2)),
    )

    init {
        isOpaque = true
        background = JBColor(Color(0xF7F8FA), Color(0x1E1F22))
        ToolTipManager.sharedInstance().registerComponent(this)
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                popupHandledOnPress = isContextTrigger(e)
                if (popupHandledOnPress) {
                    showContextMenu(e)
                    return
                }
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    pressedButton = e.button
                    dragStart = e.point
                    dragged = false
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                if (start.distance(e.point) > 2) dragged = true
            }
            override fun mouseReleased(e: MouseEvent) {
                if (isContextTrigger(e)) {
                    dragStart = null
                    cursor = Cursor.getDefaultCursor()
                    pressedButton = MouseEvent.NOBUTTON
                    if (!popupHandledOnPress) showContextMenu(e)
                    popupHandledOnPress = false
                    return
                }
                dragStart = null
                cursor = Cursor.getDefaultCursor()
                if (!dragged && pressedButton == MouseEvent.BUTTON1) {
                    val target = hitTarget(e.point)
                    updateSelection(selection.click(target?.hash, e.isControlDown || e.isMetaDown), target)
                    target?.let(::notifyRevisionSelected)
                }
                pressedButton = MouseEvent.NOBUTTON
                popupHandledOnPress = false
            }
            override fun mouseMoved(e: MouseEvent) {
                cursor = if (hitTarget(e.point) != null) {
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                } else {
                    Cursor.getDefaultCursor()
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

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

    fun clearSelection() = updateSelection(RevisionSelection.EMPTY)

    fun setVisibleRefKinds(kinds: Set<RefKind>) {
        visibleRefKinds = kinds
        repaint()
    }

    internal fun selectAndLocateRevision(hash: String, revision: String): Boolean {
        val graph = layout ?: return false
        if (hash !in graph.byHash) return false
        updateSelection(
            RevisionSelection(hash, activeHash = hash),
            HitTarget(hash, revision),
        )
        return true
    }

    fun containsRevision(hash: String?): Boolean = hash != null && layout?.byHash?.containsKey(hash) == true

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

    internal fun graphGeometry(extent: Dimension): GraphViewportGeometry? =
        layout?.bounds?.let { GraphViewportGeometry(it, scale, extent) }

    internal fun worldToContent(point: Point2D): Point2D.Double? = graphGeometry(size)?.worldToContent(point)
    internal fun contentToWorld(point: Point2D): Point2D.Double? = graphGeometry(size)?.contentToWorld(point)
    internal fun nodeBounds(hash: String): Rectangle2D.Double? = layout?.byHash?.get(hash)?.bounds

    override fun getPreferredSize(): Dimension {
        val bounds = layout?.bounds ?: return super.getPreferredSize()
        return Dimension(
            ceil(bounds.width.coerceAtLeast(1.0) * scale + GRAPH_HORIZONTAL_PADDING * 2).toInt(),
            ceil(bounds.height.coerceAtLeast(1.0) * scale + GRAPH_VERTICAL_PADDING * 2).toInt(),
        )
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = layout ?: return
        val model = snapshot ?: return
        val geometry = GraphViewportGeometry(graph.bounds, scale, size)
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            translate(geometry.contentOrigin.x, geometry.contentOrigin.y)
            scale(scale, scale)
            translate(-graph.bounds.minX, -graph.bounds.minY)
        }
        val visible = contentToWorld(g.clipBounds ?: Rectangle(0, 0, width, height), geometry)
        drawEdges(g2, graph, visible)
        graph.index.query(expand(visible, 8.0, 8.0)).forEach { drawNode(g2, model, it) }
        g2.dispose()
    }

    private fun drawEdges(g: Graphics2D, graph: GraphLayout, visible: Rectangle2D) {
        val relationship = relationship()
        graph.edges.forEach { edge ->
            val child = graph.byHash[edge.child] ?: return@forEach
            val parent = graph.byHash[edge.parent] ?: return@forEach
            if (!edge.points.zipWithNext().any { (from, to) -> visible.intersectsLine(from.x, from.y, to.x, to.y) } &&
                !child.bounds.intersects(visible) && !parent.bounds.intersects(visible)) return@forEach
            val key = EdgeKey(edge.child, edge.parent)
            val style = when (key) {
                in relationship?.basePath.orEmpty() -> GraphPathStyle.BASE
                in relationship?.targetPath.orEmpty() -> GraphPathStyle.TARGET
                else -> GraphPathStyle.PARENT
            }
            g.color = style.color
            g.stroke = style.stroke()
            val path = straightPolylinePath(edge.points)
            g.draw(path)
            drawArrow(g, edge.points)
        }
    }

    private fun straightPolylinePath(points: List<Point2D.Double>): Path2D.Double {
        val path = Path2D.Double()
        if (points.isEmpty()) return path
        path.moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
        return path
    }

    private fun drawArrow(g: Graphics2D, points: List<Point2D.Double>) {
        if (points.size < 2) return
        val end = points.last()
        val previous = points[points.lastIndex - 1]
        val length = previous.distance(end)
        if (length == 0.0) return
        val ux = (end.x - previous.x) / length
        val uy = (end.y - previous.y) / length
        val arrowSize = 8.0
        val wingAngle = Math.PI / 8.0
        val backX = -ux * arrowSize
        val backY = -uy * arrowSize
        val cos = kotlin.math.cos(wingAngle)
        val sin = kotlin.math.sin(wingAngle)
        val wing1X = backX * cos - backY * sin
        val wing1Y = backX * sin + backY * cos
        val wing2X = backX * cos + backY * sin
        val wing2Y = -backX * sin + backY * cos
        val baseX = end.x - ux * arrowSize * 0.6
        val baseY = end.y - uy * arrowSize * 0.6
        val arrow = Path2D.Double().apply {
            moveTo(baseX, baseY)
            lineTo(end.x + wing1X, end.y + wing1Y)
            lineTo(end.x, end.y)
            lineTo(end.x + wing2X, end.y + wing2Y)
            closePath()
        }
        g.draw(arrow)
    }

    private fun drawNode(g: Graphics2D, model: GraphSnapshot, node: NodeLayout) {
        val commit = model.commitsByHash.getValue(node.hash)
        val refs = visualRefs(model, node.hash)
        val marker = when (node.hash) {
            selection.baseHash -> SelectionMarker.BASE
            selection.targetHash -> SelectionMarker.TARGET
            else -> null
        }
        val b = node.bounds

        g.color = JBColor(Color(0xD8DCE2), Color(0x101113))
        g.fillRoundRect((b.x + 2).toInt(), (b.y + 3).toInt(), b.width.toInt(), b.height.toInt(), 10, 10)
        if (refs.isEmpty()) {
            g.color = JBColor(Color.WHITE, Color(0x303236))
            g.fillRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 10, 10)
            g.color = laneColor(node.lane)
            g.fillRoundRect(b.x.toInt(), b.y.toInt(), 5, b.height.toInt(), 9, 9)
        } else {
            val rowHeight = b.height / refs.size
            val rowShape = Path2D.Double()
            refs.forEachIndexed { index, ref ->
                val rowTop = b.y + index * rowHeight
                val rowBottom = b.y + (index + 1) * rowHeight
                setReferenceRowShape(rowShape, b, rowTop, rowBottom, index == 0, index == refs.lastIndex, b.maxX, true)
                g.color = RevisionGraphColors.refBackground(ref.kind, ref.head)
                g.fill(rowShape)
                setReferenceRowShape(
                    rowShape,
                    b,
                    rowTop,
                    rowBottom,
                    index == 0,
                    index == refs.lastIndex,
                    b.x + REFERENCE_ACCENT_WIDTH,
                    false,
                )
                g.color = RevisionGraphColors.refAccent(ref.kind, ref.head)
                g.fill(rowShape)
            }
        }
        g.color = JBColor(Color(0xC9CDD3), Color(0x4C5055))
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 10, 10)

        if (scale > .3) {
            if (refs.isEmpty()) {
                g.font = typography.font(true)
                g.color = JBColor.foreground()
                val revision = commit.hash.take(8)
                val metrics = g.fontMetrics
                val baseline = b.centerY - metrics.height / 2.0 + metrics.ascent
                g.drawString(revision, (b.centerX - metrics.stringWidth(revision) / 2.0).toFloat(), baseline.toFloat())
            } else {
                val rowHeight = b.height / refs.size
                refs.forEachIndexed { index, ref ->
                    g.font = typography.font(ref.head)
                    g.color = RevisionGraphColors.refText(ref.kind, ref.head)
                    val metrics = g.fontMetrics
                    val baseline = b.y + index * rowHeight + (rowHeight - metrics.height) / 2.0 + metrics.ascent
                    g.drawString(ref.text, graphTextLeft(b.x), baseline.toFloat())
                    if (index > 0) {
                        g.color = JBColor(Color(0xD7DADE), Color(0x4A4D51))
                        g.drawLine((b.x + 6).toInt(), (b.y + index * rowHeight).toInt(), (b.maxX - 5).toInt(), (b.y + index * rowHeight).toInt())
                    }
                }
            }
        }
        marker?.let { drawSelectionMarker(g, b, it) }
    }

    private fun setReferenceRowShape(
        path: Path2D.Double,
        bounds: Rectangle2D.Double,
        rowTop: Double,
        rowBottom: Double,
        first: Boolean,
        last: Boolean,
        right: Double,
        roundRight: Boolean,
    ) {
        val left = bounds.x
        val radius = NODE_CORNER_RADIUS
        val control = radius * CIRCLE_CONTROL
        path.reset()
        if (first) {
            path.moveTo(left + radius, bounds.y)
            if (roundRight) {
                path.lineTo(right - radius, bounds.y)
                path.curveTo(
                    right - radius + control,
                    bounds.y,
                    right,
                    bounds.y + radius - control,
                    right,
                    bounds.y + radius,
                )
            } else {
                path.lineTo(right, bounds.y)
            }
        } else {
            path.moveTo(left, rowTop)
            path.lineTo(right, rowTop)
        }
        if (last) {
            if (roundRight) {
                path.lineTo(right, bounds.maxY - radius)
                path.curveTo(
                    right,
                    bounds.maxY - radius + control,
                    right - radius + control,
                    bounds.maxY,
                    right - radius,
                    bounds.maxY,
                )
            } else {
                path.lineTo(right, bounds.maxY)
            }
            path.lineTo(left + radius, bounds.maxY)
            path.curveTo(
                left + radius - control,
                bounds.maxY,
                left,
                bounds.maxY - radius + control,
                left,
                bounds.maxY - radius,
            )
        } else {
            path.lineTo(right, rowBottom)
            path.lineTo(left, rowBottom)
        }
        if (first) {
            path.lineTo(left, bounds.y + radius)
            path.curveTo(left, bounds.y + radius - control, left + radius - control, bounds.y, left + radius, bounds.y)
        } else {
            path.lineTo(left, rowTop)
        }
        path.closePath()
    }

    private data class VisualRef(
        val text: String,
        val revision: String?,
        val kind: RefKind,
        val head: Boolean,
        val ref: RevisionRef? = null,
    )

    private enum class SelectionMarker(val color: JBColor) {
        BASE(RevisionGraphColors.baseSelection),
        TARGET(RevisionGraphColors.targetSelection),
    }

    private fun drawSelectionMarker(g: Graphics2D, bounds: Rectangle2D.Double, marker: SelectionMarker) {
        val oldStroke = g.stroke
        g.color = marker.color
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawRoundRect(bounds.x.toInt(), bounds.y.toInt(), bounds.width.toInt(), bounds.height.toInt(), 10, 10)
        g.stroke = oldStroke
    }

    private fun visualRefs(model: GraphSnapshot, hash: String): List<VisualRef> = buildList {
        if (model.head.hash == hash && model.head.detached) add(VisualRef(message("node.head.detached"), null, RefKind.OTHER, true))
        model.refsByCommit[hash].orEmpty().filter { ref ->
            ref.kind in visibleRefKinds || model.head.hash == hash && model.head.branch == ref.displayName
        }.forEach { ref ->
            val head = model.head.hash == hash && model.head.branch == ref.displayName
            add(VisualRef(if (head) "HEAD · ${ref.graphLabel}" else ref.graphLabel, compareRevisionName(ref), ref.kind, head, ref))
        }
    }

    override fun getToolTipText(event: MouseEvent): String? = hitTarget(event.point)?.hash?.let { hash ->
        val commit = snapshot?.commitsByHash?.get(hash) ?: return@let null
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochSecond(commit.epochSeconds).atZone(ZoneId.systemDefault()))
        val text = buildString {
            append(commit.hash).append('\n')
            append(commit.author).append(" <").append(commit.email).append("> ").append(date)
            snapshot?.refsByCommit?.get(hash)?.takeIf { it.isNotEmpty() }?.let { refs ->
                append('\n').append(message("tooltip.refs", refs.joinToString { it.fullName }))
            }
            if (snapshot?.head?.hash == hash && snapshot?.head?.detached == true) {
                append('\n').append(message("tooltip.head.detached"))
            }
            append("\n\n").append(commit.subject).append('\n').append(commit.body)
        }.let { if (it.length <= 8000) it else it.take(8000) + "..." }
        "<html><pre>${escape(text)}</pre></html>"
    }

    private data class HitTarget(
        val hash: String,
        val revision: String,
        val ref: RevisionRef? = null,
    )

    private data class RelationshipCache(
        val snapshot: GraphSnapshot,
        val baseHash: String,
        val targetHash: String,
        val relationship: RevisionRelationship?,
    )

    private fun hitTarget(point: Point): HitTarget? {
        val graph = layout ?: return null
        val model = snapshot ?: return null
        val world = contentToWorld(Point2D.Double(point.x.toDouble(), point.y.toDouble())) ?: return null
        val node = graph.index.hit(world) ?: return null
        val refs = visualRefs(model, node.hash)
        if (refs.isEmpty()) return HitTarget(node.hash, node.hash)
        val rowHeight = node.bounds.height / refs.size
        val row = ((world.y - node.bounds.y) / rowHeight).toInt().coerceIn(0, refs.lastIndex)
        return HitTarget(node.hash, refs[row].revision ?: node.hash, refs[row].ref)
    }

    private fun updateSelection(value: RevisionSelection, clicked: HitTarget? = null) {
        val changed = selection != value
        if (selection.baseHash != value.baseHash || selection.targetHash != value.targetHash) {
            relationshipCache = null
        }
        selection = value
        if (clicked != null && (clicked.hash == value.baseHash || clicked.hash == value.targetHash)) {
            compareRevisionsByHash[clicked.hash] = CompareRevision(clicked.hash, clicked.revision)
        }
        retainCompareRevisions()
        if (changed || clicked != null) repaint()
    }

    private fun retainCompareRevisions() {
        val model = snapshot
        val selectedHashes = setOfNotNull(selection.baseHash, selection.targetHash)
        compareRevisionsByHash.keys.retainAll(selectedHashes)
        if (model == null) return
        selectedHashes.forEach { hash ->
            val availableNames = model.refsByCommit[hash].orEmpty().mapNotNull(::compareRevisionName).toSet()
            val current = compareRevisionsByHash[hash]
            if (current == null || current.revision != hash && current.revision !in availableNames) {
                compareRevisionsByHash[hash] = preferredCompareRevision(model, hash)
            }
        }
    }

    private fun notifyRevisionSelected(target: HitTarget) {
        if (target.hash != selection.baseHash && target.hash != selection.targetHash) return
        val model = snapshot ?: return
        val revision = compareRevisionsByHash[target.hash] ?: preferredCompareRevision(model, target.hash)
        onRevisionSelected?.invoke(revision)
    }

    private fun showContextMenu(event: MouseEvent) {
        val target = hitTarget(event.point) ?: return
        updateSelection(selection.contextClick(target.hash), target)
        val baseHash = selection.baseHash ?: return
        val model = snapshot ?: return
        val base = compareRevisionsByHash[baseHash] ?: preferredCompareRevision(model, baseHash)
        val selectedTarget = selection.targetHash?.let { hash ->
            compareRevisionsByHash[hash] ?: preferredCompareRevision(model, hash)
        }
        val activeHash = selection.activeHash ?: baseHash
        val active = compareRevisionsByHash[activeHash] ?: preferredCompareRevision(model, activeHash)
        val compareSelection = RevisionCompareSelection(
            base = base,
            target = selectedTarget,
            active = active,
            activeRef = target.ref,
            activeRefs = model.refsByCommit[activeHash].orEmpty(),
            head = model.head,
        )
        onContextMenu?.invoke(compareSelection, event.point)
    }

    private fun relationship(): RevisionRelationship? {
        val model = snapshot ?: return null
        val base = selection.baseHash ?: return null
        val target = selection.targetHash ?: return null
        relationshipCache?.takeIf { cached ->
            cached.snapshot === model && cached.baseHash == base && cached.targetHash == target
        }?.let { return it.relationship }
        return model.relationship(base, target).also { relationship ->
            relationshipCache = RelationshipCache(model, base, target, relationship)
        }
    }

    private fun isContextTrigger(event: MouseEvent) = event.isPopupTrigger && SwingUtilities.isRightMouseButton(event)

    private fun laneColor(lane: Int) = laneColors[lane.mod(laneColors.size)]
    private fun expand(r: Rectangle2D, x: Double, y: Double) = Rectangle2D.Double(r.x - x, r.y - y, r.width + x * 2, r.height + y * 2)
    private fun contentToWorld(rect: Rectangle, geometry: GraphViewportGeometry): Rectangle2D.Double {
        val p = geometry.contentToWorld(Point2D.Double(rect.x.toDouble(), rect.y.toDouble()))
        return Rectangle2D.Double(p.x, p.y, max(1.0, rect.width / scale), max(1.0, rect.height / scale))
    }
    private fun escape(value: String) = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

internal fun focusScreenY(canvasHeight: Int, contentAbove: Double = 0.0): Double {
    val preferred = min(canvasHeight / 2.0, max(MINIMUM_FOCUS_Y, canvasHeight * FOCUS_HEIGHT_RATIO))
    val maximum = max(preferred, min(canvasHeight - MINIMUM_FOCUS_Y, canvasHeight * MAXIMUM_FOCUS_HEIGHT_RATIO))
    return max(preferred, min(TOP_CONTENT_PADDING + contentAbove.coerceAtLeast(0.0), maximum))
}

private const val MINIMUM_FOCUS_Y = 96.0
private const val FOCUS_HEIGHT_RATIO = .12
private const val MAXIMUM_FOCUS_HEIGHT_RATIO = .35
private const val TOP_CONTENT_PADDING = 24.0
private const val NODE_CORNER_RADIUS = 5.0
private const val REFERENCE_ACCENT_WIDTH = 5.0
private const val CIRCLE_CONTROL = 0.5522847498307936

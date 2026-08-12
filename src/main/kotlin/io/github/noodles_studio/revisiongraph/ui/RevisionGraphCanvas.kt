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
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** RevisionGraph-style block topology with IDEA-aware colors and interaction. */
internal class RevisionGraphCanvas(private val typography: GraphTypography = GraphTypography.fromIdeaDefaults()) : JComponent() {
    internal var onContextMenu: ((RevisionCompareSelection, Point) -> Unit)? = null
    internal var onRevisionSelected: ((CompareRevision) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null
    private var snapshot: GraphSnapshot? = null
    private var layout: GraphLayout? = null
    private var scale = 1.0
    private var offsetX = 28.0
    private var offsetY = 22.0
    private var pendingFocusHash: String? = null
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
        addMouseWheelListener { e -> zoomAt(if (e.preciseWheelRotation < 0) 1.12 else .89, e.point) }
        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                popupHandledOnPress = isContextTrigger(e)
                if (popupHandledOnPress) {
                    showContextMenu(e)
                    return
                }
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    pendingFocusHash = null
                    pressedButton = e.button
                    dragStart = e.point
                    dragged = false
                    cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                if (start.distance(e.point) > 2) dragged = true
                offsetX += e.x - start.x
                offsetY += e.y - start.y
                dragStart = e.point
                repaint()
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

    fun show(snapshot: GraphSnapshot, layout: GraphLayout, focusHash: String? = null) {
        val firstGraph = this.layout == null
        this.snapshot = snapshot
        this.layout = layout
        relationshipCache = null
        selection = selection.retain(layout.byHash.keys)
        retainCompareRevisions()
        val requestedFocus = focusHash?.takeIf(layout.byHash::containsKey)
            ?: snapshot.head.hash?.takeIf { firstGraph && it in layout.byHash }
        when {
            requestedFocus != null -> {
                pendingFocusHash = requestedFocus
                repaint()
            }
            firstGraph -> resetView()
            else -> repaint()
        }
    }

    fun clearSelection() = updateSelection(RevisionSelection.EMPTY)
    fun zoomIn() = zoomAt(1.18, Point(width / 2, height / 2))
    fun zoomOut() = zoomAt(.84, Point(width / 2, height / 2))
    fun zoomPercent() = (scale * 100.0).roundToInt()
    fun setZoomPercent(percent: Double) = zoomAt(percent.coerceIn(12.0, 350.0) / 100.0 / scale, Point(width / 2, height / 2))
    fun resetView() {
        pendingFocusHash = null
        scale = 1.0
        offsetX = 28.0
        offsetY = 22.0
        zoomChanged()
        repaint()
    }

    fun setVisibleRefKinds(kinds: Set<RefKind>) {
        visibleRefKinds = kinds
        repaint()
    }

    fun locateRevision(hash: String, revision: String) {
        val graph = layout ?: return
        if (hash !in graph.byHash) return
        pendingFocusHash = hash
        updateSelection(
            RevisionSelection(hash, activeHash = hash),
            HitTarget(hash, revision),
        )
        repaint()
    }

    fun containsRevision(hash: String?): Boolean = hash != null && layout?.byHash?.containsKey(hash) == true

    fun focusRevision(hash: String): Boolean {
        if (!containsRevision(hash)) return false
        pendingFocusHash = hash
        repaint()
        return true
    }

    fun fitToView() {
        val graph = layout ?: return
        if (width < 50 || height < 50) return
        pendingFocusHash = null
        scale = min(1.0, min((width - 56.0) / graph.bounds.width, (height - 48.0) / graph.bounds.height)).coerceAtLeast(.12)
        offsetX = max(28.0, (width - graph.bounds.width * scale) / 2.0)
        offsetY = 24.0
        zoomChanged()
        repaint()
    }

    fun fitWidth() {
        val graph = layout ?: return
        if (width < 50) return
        pendingFocusHash = null
        scale = min(1.0, (width - 56.0) / graph.bounds.width).coerceAtLeast(.12)
        offsetX = max(28.0, (width - graph.bounds.width * scale) / 2.0)
        offsetY = 24.0
        zoomChanged()
        repaint()
    }

    fun fitHeight() {
        val graph = layout ?: return
        if (height < 50) return
        pendingFocusHash = null
        scale = min(1.0, (height - 48.0) / graph.bounds.height).coerceAtLeast(.12)
        offsetX = 28.0
        offsetY = max(24.0, (height - graph.bounds.height * scale) / 2.0)
        zoomChanged()
        repaint()
    }

    private fun zoomAt(factor: Double, point: Point) {
        pendingFocusHash = null
        val old = scale
        scale = (scale * factor).coerceIn(.12, 3.5)
        offsetX = point.x - (point.x - offsetX) * scale / old
        offsetY = point.y - (point.y - offsetY) * scale / old
        zoomChanged()
        repaint()
    }

    private fun zoomChanged() = onZoomChanged?.invoke(zoomPercent())

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = layout ?: return
        val model = snapshot ?: return
        applyPendingFocus(graph)
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            transform(AffineTransform.getTranslateInstance(offsetX, offsetY))
            scale(scale, scale)
        }
        val visible = screenToWorld(Rectangle(0, 0, width, height))
        drawEdges(g2, graph, visible)
        graph.index.query(expand(visible, 8.0, 8.0)).forEach { drawNode(g2, model, it) }
        g2.dispose()
    }

    private fun applyPendingFocus(graph: GraphLayout) {
        val hash = pendingFocusHash ?: return
        val node = graph.byHash[hash] ?: run {
            pendingFocusHash = null
            return
        }
        if (width < 50 || height < 50) return
        offsetX = width / 2.0 - node.bounds.centerX * scale
        val contentAbove = (node.bounds.centerY - graph.bounds.minY) * scale
        offsetY = focusScreenY(height, contentAbove) - node.bounds.centerY * scale
        pendingFocusHash = null
    }

    private fun drawEdges(g: Graphics2D, graph: GraphLayout, visible: Rectangle2D) {
        val relationship = relationship()
        graph.edges.forEach { edge ->
            val child = graph.byHash[edge.child] ?: return@forEach
            val parent = graph.byHash[edge.parent] ?: return@forEach
            if (!edge.points.zipWithNext().any { (from, to) -> visible.intersectsLine(from.x, from.y, to.x, to.y) } &&
                !child.bounds.intersects(visible) && !parent.bounds.intersects(visible)) return@forEach
            val key = EdgeKey(edge.child, edge.parent)
            g.color = when (key) {
                in relationship?.basePath.orEmpty() -> SelectionMarker.BASE.color
                in relationship?.targetPath.orEmpty() -> SelectionMarker.TARGET.color
                else -> JBColor(Color(0x626A73), Color(0xA4ABB4))
            }
            g.stroke = BasicStroke(1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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
        g.color = JBColor(Color.WHITE, Color(0x303236))
        g.fillRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 10, 10)
        if (refs.isEmpty()) {
            g.color = laneColor(node.lane)
            g.fillRoundRect(b.x.toInt(), b.y.toInt(), 5, b.height.toInt(), 9, 9)
        } else {
            val oldClip = g.clip
            g.clip = java.awt.geom.RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 10.0, 10.0)
            val rowHeight = b.height / refs.size
            refs.forEachIndexed { index, ref ->
                g.color = refBackground(ref.kind, ref.head)
                g.fillRect(b.x.toInt(), (b.y + index * rowHeight).toInt(), b.width.toInt(), kotlin.math.ceil(rowHeight).toInt())
                g.color = refAccent(ref.kind, ref.head)
                g.fillRect(b.x.toInt(), (b.y + index * rowHeight).toInt(), 5, kotlin.math.ceil(rowHeight).toInt())
            }
            g.clip = oldClip
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
                    g.color = refText(ref.kind, ref.head)
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

    private data class VisualRef(
        val text: String,
        val revision: String?,
        val kind: RefKind,
        val head: Boolean,
        val ref: RevisionRef? = null,
    )

    private enum class SelectionMarker(val color: JBColor) {
        BASE(JBColor(Color(0x1E6FB8), Color(0x65B3F3))),
        TARGET(JBColor(Color(0x8A2638), Color(0xE06C75))),
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

    private fun refBackground(kind: RefKind, current: Boolean) = when {
        current -> JBColor(Color(0xDCEEFF), Color(0x1C3A55))
        kind == RefKind.LOCAL_BRANCH -> JBColor(Color(0xDFF5EB), Color(0x1B3D32))
        kind == RefKind.REMOTE_BRANCH -> JBColor(Color(0xEEE6F8), Color(0x493B59))
        kind == RefKind.TAG || kind == RefKind.ANNOTATED_TAG -> JBColor(Color(0xFFF2CC), Color(0x554A2C))
        kind == RefKind.STASH -> JBColor(Color(0xF7E1F8), Color(0x533B58))
        kind == RefKind.BISECT_GOOD -> JBColor(Color(0xDDF2E1), Color(0x2E4D35))
        kind == RefKind.BISECT_BAD || kind == RefKind.BISECT_SKIP -> JBColor(Color(0xF7DFDF), Color(0x573535))
        kind == RefKind.NOTES -> JBColor(Color(0xE0F1F3), Color(0x304E53))
        else -> JBColor(Color(0xECEEF1), Color(0x3D4044))
    }
    private fun refAccent(kind: RefKind, current: Boolean) = when {
        current -> JBColor(Color(0x2376BD), Color(0x65B3F3))
        kind == RefKind.LOCAL_BRANCH -> JBColor(Color(0x25815E), Color(0x59C89A))
        kind == RefKind.REMOTE_BRANCH -> JBColor(Color(0x7B5CAD), Color(0xB493D6))
        kind == RefKind.TAG || kind == RefKind.ANNOTATED_TAG -> JBColor(Color(0xA77B13), Color(0xE1C162))
        kind == RefKind.STASH -> JBColor(Color(0xA857AE), Color(0xDA91DE))
        kind == RefKind.BISECT_GOOD -> JBColor(Color(0x32884C), Color(0x72C58B))
        kind == RefKind.BISECT_BAD || kind == RefKind.BISECT_SKIP -> JBColor(Color(0xB84747), Color(0xDF7979))
        kind == RefKind.NOTES -> JBColor(Color(0x378892), Color(0x75C2CA))
        else -> JBColor(Color(0x68717B), Color(0xA8AFB7))
    }
    private fun refText(kind: RefKind, current: Boolean) = if (current) JBColor(Color(0x125A95), Color(0xC2E3FF)) else when (kind) {
        RefKind.LOCAL_BRANCH -> JBColor(Color(0x176447), Color(0xA9E8CA))
        RefKind.REMOTE_BRANCH -> JBColor(Color(0x65458C), Color(0xD5BCEB))
        RefKind.TAG, RefKind.ANNOTATED_TAG -> JBColor(Color(0x765A12), Color(0xF1D98B))
        RefKind.STASH -> JBColor(Color(0x773D7B), Color(0xEDB8F0))
        RefKind.BISECT_GOOD -> JBColor(Color(0x216537), Color(0xAEE4BB))
        RefKind.BISECT_BAD, RefKind.BISECT_SKIP -> JBColor(Color(0x822F2F), Color(0xF0AAAA))
        RefKind.NOTES -> JBColor(Color(0x286973), Color(0xA9DCE1))
        RefKind.OTHER -> JBColor.foreground()
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
        val world = screenToWorld(Point2D.Double(point.x.toDouble(), point.y.toDouble()))
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
    private fun screenToWorld(point: Point2D) = Point2D.Double((point.x - offsetX) / scale, (point.y - offsetY) / scale)
    private fun screenToWorld(rect: Rectangle): Rectangle2D.Double {
        val p = screenToWorld(Point2D.Double(rect.x.toDouble(), rect.y.toDouble()))
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

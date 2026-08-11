package io.github.fh00126072001.revisiongraph.ui

import com.intellij.ui.JBColor
import io.github.fh00126072001.revisiongraph.layout.GraphLayout
import io.github.fh00126072001.revisiongraph.layout.NodeLayout
import io.github.fh00126072001.revisiongraph.model.GraphSnapshot
import io.github.fh00126072001.revisiongraph.model.RefKind
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min

/** RevisionGraph-style block topology with IDEA-aware colors and interaction. */
class RevisionGraphCanvas : JComponent() {
    var onSelection: ((String) -> Unit)? = null
    var onZoomChanged: ((Int) -> Unit)? = null
    private var snapshot: GraphSnapshot? = null
    private var layout: GraphLayout? = null
    private var scale = .92
    private var offsetX = 28.0
    private var offsetY = 22.0
    private var selected: String? = null
    private var dragStart: Point? = null
    private var dragged = false

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
                if (SwingUtilities.isLeftMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                    dragStart = e.point; dragged = false; cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }
            override fun mouseDragged(e: MouseEvent) {
                val start = dragStart ?: return
                if (start.distance(e.point) > 2) dragged = true
                offsetX += e.x - start.x; offsetY += e.y - start.y; dragStart = e.point; repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                dragStart = null; cursor = Cursor.getDefaultCursor()
                if (!dragged) hit(e.point)?.let { selected = it; repaint(); onSelection?.invoke(it) }
            }
            override fun mouseMoved(e: MouseEvent) { cursor = if (hit(e.point) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor() }
        }
        addMouseListener(mouse); addMouseMotionListener(mouse)
    }

    fun show(snapshot: GraphSnapshot, layout: GraphLayout, keepHash: String? = selected) {
        val firstGraph = this.layout == null
        this.snapshot = snapshot; this.layout = layout
        selected = keepHash?.takeIf { it in layout.byHash }
        if (firstGraph) resetView() else repaint()
    }

    fun selectedHash() = selected
    fun zoomIn() = zoomAt(1.18, Point(width / 2, height / 2))
    fun zoomOut() = zoomAt(.84, Point(width / 2, height / 2))
    fun resetView() { scale = .92; offsetX = 28.0; offsetY = 22.0; zoomChanged(); repaint() }

    fun fitToView() {
        val graph = layout ?: return
        if (width < 50 || height < 50) return
        scale = min(1.0, min((width - 56.0) / graph.bounds.width, (height - 48.0) / graph.bounds.height)).coerceAtLeast(.12)
        offsetX = max(28.0, (width - graph.bounds.width * scale) / 2.0); offsetY = 24.0
        zoomChanged(); repaint()
    }

    private fun zoomAt(factor: Double, point: Point) {
        val old = scale; scale = (scale * factor).coerceIn(.12, 3.5)
        offsetX = point.x - (point.x - offsetX) * scale / old
        offsetY = point.y - (point.y - offsetY) * scale / old
        zoomChanged(); repaint()
    }

    private fun zoomChanged() = onZoomChanged?.invoke((scale * 100).toInt())

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val graph = layout ?: return
        val model = snapshot ?: return
        val g2 = (g.create() as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
            transform(AffineTransform.getTranslateInstance(offsetX, offsetY)); scale(scale, scale)
        }
        val visible = screenToWorld(Rectangle(0, 0, width, height))
        val highlighted = highlightedEdges(model)
        drawEdges(g2, graph, visible, highlighted, false)
        if (selected != null) drawEdges(g2, graph, visible, highlighted, true)
        graph.index.query(expand(visible, 8.0, 8.0)).forEach { drawNode(g2, model, it) }
        g2.dispose()
    }

    private fun drawEdges(g: Graphics2D, graph: GraphLayout, visible: Rectangle2D, highlighted: Set<Pair<String, String>>, highlightPass: Boolean) {
        graph.edges.asSequence().filter { ((it.child to it.parent) in highlighted) == highlightPass }.forEach { edge ->
            val child = graph.byHash[edge.child] ?: return@forEach
            val parent = graph.byHash[edge.parent] ?: return@forEach
            if (!edge.points.zipWithNext().any { (from, to) -> visible.intersectsLine(from.x, from.y, to.x, to.y) } &&
                !child.bounds.intersects(visible) && !parent.bounds.intersects(visible)) return@forEach
            val edgeColor = if (highlightPass) laneColor(child.lane) else JBColor(Color(0x626A73), Color(0xA4ABB4))
            g.color = edgeColor
            g.stroke = BasicStroke(if (highlightPass) 2.25f else 1.55f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
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
        val isSelected = node.hash == selected
        val b = node.bounds

        if (isSelected) {
            g.color = JBColor(Color(0xD6E8FF), Color(0x355373))
            g.fillRoundRect((b.x - 4).toInt(), (b.y - 4).toInt(), (b.width + 8).toInt(), (b.height + 8).toInt(), 13, 13)
        }
        g.color = JBColor(Color(0xD8DCE2), Color(0x101113))
        g.fillRoundRect((b.x + 2).toInt(), (b.y + 3).toInt(), b.width.toInt(), b.height.toInt(), 10, 10)
        g.color = JBColor(Color.WHITE, Color(0x303236)); g.fillRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 10, 10)
        if (refs.isEmpty()) {
            g.color = laneColor(node.lane); g.fillRoundRect(b.x.toInt(), b.y.toInt(), 5, b.height.toInt(), 9, 9)
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
        g.stroke = BasicStroke(1f); g.drawRoundRect(b.x.toInt(), b.y.toInt(), b.width.toInt(), b.height.toInt(), 10, 10)

        if (scale > .3) {
            if (refs.isEmpty()) {
                g.font = font.deriveFont(Font.BOLD, 11f); g.color = JBColor.foreground()
                val revision = commit.hash.take(8)
                g.drawString(revision, (b.centerX - g.fontMetrics.stringWidth(revision) / 2.0).toFloat(), (b.centerY + 4).toFloat())
            } else {
                val rowHeight = b.height / refs.size
                refs.forEachIndexed { index, ref ->
                    g.font = font.deriveFont(if (ref.head) Font.BOLD else Font.PLAIN, 11f)
                    g.color = refText(ref.kind, ref.head)
                    g.drawString(ellipsize(ref.text, 31), (b.x + 13).toFloat(), (b.y + index * rowHeight + rowHeight / 2 + 4).toFloat())
                    if (index > 0) {
                        g.color = JBColor(Color(0xD7DADE), Color(0x4A4D51))
                        g.drawLine((b.x + 6).toInt(), (b.y + index * rowHeight).toInt(), (b.maxX - 5).toInt(), (b.y + index * rowHeight).toInt())
                    }
                }
            }
        }
    }

    private data class VisualRef(val text: String, val kind: RefKind, val head: Boolean)

    private fun visualRefs(model: GraphSnapshot, hash: String): List<VisualRef> = buildList {
        if (model.head.hash == hash && model.head.detached) add(VisualRef("HEAD · detached", RefKind.OTHER, true))
        model.refsByCommit[hash].orEmpty().forEach { ref ->
            val head = model.head.hash == hash && model.head.branch == ref.displayName
            add(VisualRef(if (head) "HEAD · ${ref.displayName}" else ref.displayName, ref.kind, head))
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

    private fun highlightedEdges(model: GraphSnapshot): Set<Pair<String, String>> {
        val start = selected ?: return emptySet()
        val edges = linkedSetOf<Pair<String, String>>(); val seen = hashSetOf<String>(); val queue = ArrayDeque<String>(); queue += start
        while (queue.isNotEmpty()) {
            val hash = queue.removeFirst(); if (!seen.add(hash)) continue
            model.commitsByHash[hash]?.parents?.filter { it in model.commitsByHash }?.forEach { edges += hash to it; queue += it }
        }
        return edges
    }

    override fun getToolTipText(event: MouseEvent): String? = hit(event.point)?.let { hash ->
        val commit = snapshot?.commitsByHash?.get(hash) ?: return@let null
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochSecond(commit.epochSeconds).atZone(ZoneId.systemDefault()))
        val text = buildString {
            append(commit.hash).append('\n')
            append(commit.author).append(" <").append(commit.email).append("> ").append(date)
            snapshot?.refsByCommit?.get(hash)?.takeIf { it.isNotEmpty() }?.let { refs ->
                append("\nRefs: ").append(refs.joinToString { it.fullName })
            }
            if (snapshot?.head?.hash == hash && snapshot?.head?.detached == true) append("\nHEAD: detached")
            append("\n\n").append(commit.subject).append('\n').append(commit.body)
        }.let { if (it.length <= 8000) it else it.take(8000) + "..." }
        "<html><pre>${escape(text)}</pre></html>"
    }

    private fun hit(point: Point): String? = layout?.index?.hit(screenToWorld(Point2D.Double(point.x.toDouble(), point.y.toDouble())))?.hash
    private fun laneColor(lane: Int) = laneColors[lane.mod(laneColors.size)]
    private fun expand(r: Rectangle2D, x: Double, y: Double) = Rectangle2D.Double(r.x - x, r.y - y, r.width + x * 2, r.height + y * 2)
    private fun screenToWorld(point: Point2D) = Point2D.Double((point.x - offsetX) / scale, (point.y - offsetY) / scale)
    private fun screenToWorld(rect: Rectangle): Rectangle2D.Double {
        val p = screenToWorld(Point2D.Double(rect.x.toDouble(), rect.y.toDouble()))
        return Rectangle2D.Double(p.x, p.y, max(1.0, rect.width / scale), max(1.0, rect.height / scale))
    }
    private fun ellipsize(value: String, max: Int) = if (value.length <= max) value else value.take(max - 1) + "…"
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

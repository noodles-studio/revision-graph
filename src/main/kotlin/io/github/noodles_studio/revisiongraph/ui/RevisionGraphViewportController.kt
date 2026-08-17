package io.github.noodles_studio.revisiongraph.ui

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.Magnificator
import com.intellij.util.ui.JBUI
import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

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
    private var panStart: Point? = null
    private var panStartPosition: Point? = null

    init {
        ClientProperty.put(canvas, Magnificator.CLIENT_PROPERTY_KEY, Magnificator(::magnify))
        scrollPane.addMouseWheelListener(::handleWheel)
        installPanHandler()
    }

    internal fun showGraph(snapshot: GraphSnapshot, layout: GraphLayout, focusHash: String? = null) {
        val requestedFocus = canvas.showGraph(snapshot, layout, focusHash)
        validateLayout()
        if (requestedFocus != null) focusWhenReady(requestedFocus)
    }

    internal fun locateRevision(hash: String, revision: String): Boolean {
        if (!canvas.selectAndLocateRevision(hash, revision)) return false
        focusRevision(hash)
        return true
    }

    internal fun containsRevision(hash: String?): Boolean = canvas.containsRevision(hash)

    internal fun zoomPercent(): Int = (canvas.graphScale * 100.0).roundToInt()

    internal fun zoomIn() = zoomAtViewportCenter(1.18)

    internal fun zoomOut() = zoomAtViewportCenter(1.0 / 1.18)

    internal fun setZoomPercent(percent: Double) {
        val target = (percent / 100.0).coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
        zoomAtViewportCenter(target / canvas.graphScale)
    }

    internal fun resetView() {
        val changed = canvas.setGraphScale(1.0)
        validateLayout()
        setViewPosition(Point())
        if (changed) notifyZoomChanged()
    }

    internal fun fitToView() = fit(FitMode.ALL)

    internal fun fitWidth() = fit(FitMode.WIDTH)

    internal fun fitHeight() = fit(FitMode.HEIGHT)

    internal fun focusRevision(hash: String): Boolean {
        if (!canvas.containsRevision(hash)) return false
        if (viewportReady()) focusRevisionNow(hash) else SwingUtilities.invokeLater { focusRevisionNow(hash) }
        return true
    }

    internal fun focusRevisionNow(hash: String): Boolean {
        val node = canvas.nodeBounds(hash) ?: return false
        val bounds = canvas.graphBounds ?: return false
        val extent = scrollPane.viewport.extentSize
        if (extent.width <= 0 || extent.height <= 0) return false
        validateLayout()
        val center = canvas.worldToContent(Point2D.Double(node.centerX, node.centerY)) ?: return false
        val contentAbove = (node.centerY - bounds.minY) * canvas.graphScale
        setViewPosition(
            Point(
                (center.x - extent.width / 2.0).roundToInt(),
                (center.y - focusScreenY(extent.height, contentAbove)).roundToInt(),
            ),
        )
        return true
    }

    internal fun handleWheel(event: MouseWheelEvent) {
        if (event.preciseWheelRotation == 0.0 || !isZoomWheelModifiers(event.modifiersEx, isMac)) return
        event.consume()
        val anchorInViewport = SwingUtilities.convertPoint(scrollPane, event.point, scrollPane.viewport)
        val anchorInContent = SwingUtilities.convertPoint(scrollPane, event.point, canvas)
        val remapped = applyScaleAtContentAnchor(wheelZoomFactor(event.preciseWheelRotation), anchorInContent) ?: return
        setViewPosition(Point(remapped.x - anchorInViewport.x, remapped.y - anchorInViewport.y))
    }

    internal fun beginPan(point: Point) {
        panStart = Point(point)
        panStartPosition = scrollPane.viewport.viewPosition
    }

    internal fun continuePan(point: Point) {
        val start = panStart ?: return
        val startPosition = panStartPosition ?: return
        setViewPosition(
            Point(
                startPosition.x - (point.x - start.x),
                startPosition.y - (point.y - start.y),
            ),
        )
    }

    internal fun endPan() {
        panStart = null
        panStartPosition = null
    }

    private fun magnify(factor: Double, anchorContent: Point): Point {
        if (!factor.isFinite() || factor <= 0.0) return anchorContent
        return applyScaleAtContentAnchor(factor, anchorContent) ?: anchorContent
    }

    private fun applyScaleAtContentAnchor(factor: Double, anchorContent: Point): Point? {
        val oldGeometry = canvas.graphGeometry(scrollPane.viewport.extentSize) ?: return null
        val anchorWorld = oldGeometry.contentToWorld(anchorContent)
        val changed = canvas.setGraphScale(canvas.graphScale * factor)
        validateLayout()
        val newGeometry = canvas.graphGeometry(scrollPane.viewport.extentSize) ?: return null
        if (changed) notifyZoomChanged()
        val remapped = newGeometry.worldToContent(anchorWorld)
        return Point(remapped.x.roundToInt(), remapped.y.roundToInt())
    }

    private fun zoomAtViewportCenter(factor: Double) {
        if (!factor.isFinite() || factor <= 0.0 || canvas.graphBounds == null) return
        val extent = scrollPane.viewport.extentSize
        if (extent.width <= 0 || extent.height <= 0) return
        val anchorInViewport = Point(extent.width / 2, extent.height / 2)
        val viewPosition = scrollPane.viewport.viewPosition
        val anchorInContent = Point(viewPosition.x + anchorInViewport.x, viewPosition.y + anchorInViewport.y)
        val remapped = applyScaleAtContentAnchor(factor, anchorInContent) ?: return
        setViewPosition(Point(remapped.x - anchorInViewport.x, remapped.y - anchorInViewport.y))
    }

    private fun fit(mode: FitMode) {
        val bounds = canvas.graphBounds ?: return
        val extent = scrollPane.viewport.extentSize
        if (extent.width <= 0 || extent.height <= 0) {
            SwingUtilities.invokeLater {
                if (viewportReady()) fit(mode)
            }
            return
        }
        val changed = canvas.setGraphScale(fitScale(bounds, extent, mode))
        validateLayout()
        setViewPosition(Point())
        if (changed) notifyZoomChanged()
    }

    private fun focusWhenReady(hash: String) {
        if (viewportReady()) {
            focusRevisionNow(hash)
        } else {
            SwingUtilities.invokeLater { focusRevisionNow(hash) }
        }
    }

    private fun viewportReady(): Boolean {
        val extent = scrollPane.viewport.extentSize
        return extent.width > 0 && extent.height > 0
    }

    private fun validateLayout() {
        val geometry = canvas.graphGeometry(scrollPane.viewport.extentSize) ?: return
        scrollPane.viewport.viewSize = geometry.viewSize
        scrollPane.validate()
    }

    private fun setViewPosition(requested: Point) {
        val viewport = scrollPane.viewport
        viewport.viewPosition = clampViewPosition(requested, viewport.viewSize, viewport.extentSize)
    }

    private fun notifyZoomChanged() = onZoomChanged?.invoke(zoomPercent())

    private fun installPanHandler() {
        val panHandler = object : MouseAdapter() {
            private var pressedPoint: Point? = null

            override fun mousePressed(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) && !SwingUtilities.isMiddleMouseButton(event)) return
                val point = SwingUtilities.convertPoint(canvas, event.point, scrollPane.viewport)
                pressedPoint = point
                beginPan(point)
            }

            override fun mouseDragged(event: MouseEvent) {
                val pressed = pressedPoint ?: return
                val point = SwingUtilities.convertPoint(canvas, event.point, scrollPane.viewport)
                if (pressed.distance(point) > 2.0) continuePan(point)
            }

            override fun mouseReleased(event: MouseEvent) {
                pressedPoint = null
                endPan()
            }
        }
        canvas.addMouseListener(panHandler)
        canvas.addMouseMotionListener(panHandler)
    }
}

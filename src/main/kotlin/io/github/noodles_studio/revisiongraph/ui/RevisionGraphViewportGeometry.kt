package io.github.noodles_studio.revisiongraph.ui

import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal const val MIN_GRAPH_SCALE = .12
internal const val MAX_GRAPH_SCALE = 3.5
internal const val GRAPH_HORIZONTAL_PADDING = 28.0
internal const val GRAPH_VERTICAL_PADDING = 24.0

private const val WHEEL_ZOOM_BASE = 1.12

internal enum class FitMode {
    ALL,
    WIDTH,
    HEIGHT,
}

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

internal fun fitScale(bounds: Rectangle2D, extent: Dimension, mode: FitMode): Double {
    val availableWidth = (extent.width - GRAPH_HORIZONTAL_PADDING * 2).coerceAtLeast(1.0)
    val availableHeight = (extent.height - GRAPH_VERTICAL_PADDING * 2).coerceAtLeast(1.0)
    val widthScale = availableWidth / bounds.width.coerceAtLeast(1.0)
    val heightScale = availableHeight / bounds.height.coerceAtLeast(1.0)
    val requested = when (mode) {
        FitMode.ALL -> min(widthScale, heightScale)
        FitMode.WIDTH -> widthScale
        FitMode.HEIGHT -> heightScale
    }
    return min(1.0, requested).coerceIn(MIN_GRAPH_SCALE, MAX_GRAPH_SCALE)
}

internal fun clampViewPosition(requested: Point, viewSize: Dimension, extent: Dimension): Point = Point(
    requested.x.coerceIn(0, max(0, viewSize.width - extent.width)),
    requested.y.coerceIn(0, max(0, viewSize.height - extent.height)),
)

internal fun isZoomWheelModifiers(modifiersEx: Int, isMac: Boolean): Boolean {
    val control = modifiersEx and InputEvent.CTRL_DOWN_MASK != 0
    val meta = modifiersEx and InputEvent.META_DOWN_MASK != 0
    val alt = modifiersEx and InputEvent.ALT_DOWN_MASK != 0
    val shift = modifiersEx and InputEvent.SHIFT_DOWN_MASK != 0
    if (alt || shift) return false
    return if (isMac) meta && !control else control && !meta
}

internal fun wheelZoomFactor(preciseRotation: Double): Double = WHEEL_ZOOM_BASE.pow(-preciseRotation)

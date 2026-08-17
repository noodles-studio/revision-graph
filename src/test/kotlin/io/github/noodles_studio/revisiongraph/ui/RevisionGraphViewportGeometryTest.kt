package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.layout.NodeLayout
import io.github.noodles_studio.revisiongraph.layout.SpatialIndex
import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RevisionGraphViewportGeometryTest {
    @Test fun `world and content coordinates round trip with non-zero bounds`() {
        val geometry = GraphViewportGeometry(
            Rectangle2D.Double(100.0, 50.0, 200.0, 100.0),
            scale = 2.0,
            viewportExtent = Dimension(300, 200),
        )

        val content = geometry.worldToContent(Point2D.Double(150.0, 75.0))

        assertPointEquals(Point2D.Double(128.0, 74.0), content)
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

    @Test fun `fit modes use viewport extent and never exceed one hundred percent`() {
        val bounds = Rectangle2D.Double(0.0, 0.0, 800.0, 600.0)
        val extent = Dimension(456, 348)

        assertEquals(.5, fitScale(bounds, extent, FitMode.ALL), .0001)
        assertEquals(.5, fitScale(bounds, extent, FitMode.WIDTH), .0001)
        assertEquals(.5, fitScale(bounds, extent, FitMode.HEIGHT), .0001)
        assertEquals(1.0, fitScale(Rectangle2D.Double(0.0, 0.0, 10.0, 10.0), extent, FitMode.ALL))
    }

    @Test fun `fit modes respect minimum scale and tolerate zero bounds`() {
        val extent = Dimension(10, 10)

        assertEquals(MIN_GRAPH_SCALE, fitScale(Rectangle2D.Double(0.0, 0.0, 10_000.0, 10_000.0), extent, FitMode.ALL))
        assertEquals(1.0, fitScale(Rectangle2D.Double(0.0, 0.0, 0.0, 0.0), Dimension(200, 100), FitMode.ALL))
    }

    @Test fun `zoom modifiers are exact for each platform`() {
        assertTrue(isZoomWheelModifiers(InputEvent.META_DOWN_MASK, isMac = true))
        assertFalse(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK, isMac = true))
        assertFalse(isZoomWheelModifiers(InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, isMac = true))
        assertTrue(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK, isMac = false))
        assertFalse(isZoomWheelModifiers(InputEvent.META_DOWN_MASK, isMac = false))
        assertFalse(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK, isMac = false))
        assertFalse(isZoomWheelModifiers(InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK, isMac = false))
    }

    @Test fun `precise wheel rotation controls direction and magnitude`() {
        assertEquals(1.12, wheelZoomFactor(-1.0), .0001)
        assertEquals(1.0 / 1.12, wheelZoomFactor(1.0), .0001)
        assertEquals(sqrt(1.12), wheelZoomFactor(-.5), .0001)
    }

    @Test fun `view position is clamped to content bounds`() {
        assertEquals(Point(0, 0), clampViewPosition(Point(-20, -10), Dimension(900, 700), Dimension(300, 200)))
        assertEquals(Point(600, 500), clampViewPosition(Point(800, 900), Dimension(900, 700), Dimension(300, 200)))
        assertEquals(Point(0, 0), clampViewPosition(Point(20, 10), Dimension(100, 80), Dimension(300, 200)))
    }
}

internal fun assertPointEquals(expected: Point2D, actual: Point2D, tolerance: Double = .0001) {
    assertEquals(expected.x, actual.x, tolerance, "x")
    assertEquals(expected.y, actual.y, tolerance, "y")
}

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

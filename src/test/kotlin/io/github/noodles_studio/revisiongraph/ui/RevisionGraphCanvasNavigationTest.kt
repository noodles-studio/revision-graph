package io.github.noodles_studio.revisiongraph.ui

import java.awt.Dimension
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        assertPointEquals(Point2D.Double(100.0, 100.0), canvas.worldToContent(Point2D.Double(100.0, 50.0))!!)
        assertPointEquals(Point2D.Double(100.0, 50.0), canvas.contentToWorld(Point2D.Double(100.0, 100.0))!!)
    }

    @Test fun `setting the same or clamped scale reports no duplicate change`() {
        val (snapshot, layout) = graphFixture(Rectangle2D.Double(0.0, 0.0, 200.0, 100.0))
        val canvas = RevisionGraphCanvas().apply { showGraph(snapshot, layout) }

        assertFalse(canvas.setGraphScale(1.0))
        assertTrue(canvas.setGraphScale(10.0))
        assertEquals(3.5, canvas.graphScale)
        assertFalse(canvas.setGraphScale(10.0))
    }

    @Test fun `show graph requests initial head focus only once`() {
        val (snapshot, layout) = graphFixture(Rectangle2D.Double(0.0, 0.0, 200.0, 100.0))
        val canvas = RevisionGraphCanvas()

        assertEquals(snapshot.head.hash, canvas.showGraph(snapshot, layout))
        assertEquals(null, canvas.showGraph(snapshot, layout))
        assertEquals(snapshot.head.hash, canvas.showGraph(snapshot, layout, snapshot.head.hash))
    }
}

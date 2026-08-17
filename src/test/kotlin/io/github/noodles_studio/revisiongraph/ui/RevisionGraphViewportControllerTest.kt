package io.github.noodles_studio.revisiongraph.ui

import com.intellij.ui.ClientProperty
import com.intellij.ui.components.JBViewport
import com.intellij.ui.components.Magnificator
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        assertPointEquals(before, controller.canvas.contentToWorld(remapped)!!, tolerance = 1.0)
    }

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

    @Test fun `ordinary wheel remains available to native scroll pane`() = onEdt {
        val controller = controllerWithLargeGraph()
        controller.scrollPane.viewport.viewPosition = Point(200, 150)
        val event = wheelEvent(controller.scrollPane, modifiers = 0, preciseRotation = 1.0)

        controller.scrollPane.dispatchEvent(event)

        assertEquals(100, controller.zoomPercent())
        assertTrue(controller.scrollPane.viewport.viewPosition.y > 150)
    }

    @Test fun `recognized wheel remains consumed at scale boundary`() = onEdt {
        val controller = controllerWithGraph(isMac = false)
        controller.setZoomPercent(350.0)
        val event = wheelEvent(
            controller.scrollPane,
            modifiers = InputEvent.CTRL_DOWN_MASK,
            preciseRotation = -1.0,
        )

        controller.handleWheel(event)

        assertEquals(350, controller.zoomPercent())
        assertTrue(event.isConsumed)
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
        assertPointEquals(worldBefore, controller.canvas.contentToWorld(contentAfter)!!, tolerance = 1.0)
    }

    @Test fun `fit uses viewport extent and reset returns to actual size origin`() = onEdt {
        val controller = controllerWithLargeGraph(extent = Dimension(456, 348))
        val expected = fitScale(controller.canvas.graphBounds!!, controller.scrollPane.viewport.extentSize, FitMode.ALL)

        controller.fitToView()
        assertEquals((expected * 100).roundToInt(), controller.zoomPercent())

        controller.resetView()
        assertEquals(100, controller.zoomPercent())
        assertEquals(Point(0, 0), controller.scrollPane.viewport.viewPosition)
    }

    @Test fun `focus centers node horizontally and uses focus screen y`() = onEdt {
        val controller = controllerWithGraphContainingNode(Point2D.Double(500.0, 600.0))

        assertTrue(controller.focusRevisionNow("target"))

        val nodeContent = controller.canvas.worldToContent(Point2D.Double(500.0, 600.0))!!
        val viewport = controller.scrollPane.viewport
        val contentAbove = 600.0 * controller.canvas.graphScale
        assertTrue(
            abs(viewport.extentSize.width / 2 - (nodeContent.x.toInt() - viewport.viewPosition.x)) <= 1,
        )
        assertTrue(
            abs(
                focusScreenY(viewport.extentSize.height, contentAbove).toInt() -
                    (nodeContent.y.toInt() - viewport.viewPosition.y),
            ) <= 1,
        )
    }

    @Test fun `drag pan moves viewport opposite to pointer delta and clamps`() = onEdt {
        val controller = controllerWithLargeGraph()
        controller.scrollPane.viewport.viewPosition = Point(200, 200)

        controller.beginPan(Point(100, 100))
        controller.continuePan(Point(130, 150))

        assertEquals(Point(170, 150), controller.scrollPane.viewport.viewPosition)
        controller.continuePan(Point(1_000, 1_000))
        assertEquals(Point(0, 0), controller.scrollPane.viewport.viewPosition)
    }
}

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
        controller.canvas.showGraph(snapshot, layout)
        layoutController(controller, extent)
    }
}

private fun controllerWithLargeGraph(
    extent: Dimension = Dimension(300, 200),
    isMac: Boolean = false,
) = controllerWithGraph(
    extent = extent,
    isMac = isMac,
    bounds = Rectangle2D.Double(0.0, 0.0, 1_200.0, 900.0),
)

private fun controllerWithGraphContainingNode(center: Point2D.Double): RevisionGraphViewportController {
    val nodeBounds = Rectangle2D.Double(center.x - 30.0, center.y - 15.0, 60.0, 30.0)
    val (snapshot, layout) = graphFixture(
        graphBounds = Rectangle2D.Double(0.0, 0.0, 1_200.0, 900.0),
        nodeBounds = nodeBounds,
        hash = "target",
    )
    return RevisionGraphViewportController(RevisionGraphCanvas(), isMac = false).also { controller ->
        controller.canvas.showGraph(snapshot, layout)
        layoutController(controller, Dimension(300, 200))
    }
}

private fun layoutController(controller: RevisionGraphViewportController, extent: Dimension) {
    controller.scrollPane.setSize(extent)
    controller.scrollPane.doLayout()
    controller.canvas.size = GraphViewportGeometry(
        controller.canvas.graphBounds!!,
        controller.canvas.graphScale,
        controller.scrollPane.viewport.extentSize,
    ).viewSize
}

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
    point.x,
    point.y,
    0,
    false,
    MouseWheelEvent.WHEEL_UNIT_SCROLL,
    3,
    preciseRotation.toInt(),
    preciseRotation,
)

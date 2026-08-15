package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.layout.NodeLayout
import io.github.noodles_studio.revisiongraph.layout.SpatialIndex
import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import java.awt.Color
import java.awt.Rectangle
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevisionGraphCanvasPaintingTest {
    @Test fun `reference edge does not expose its neutral underlay`() {
        val hash = "b".repeat(40)
        val node = NodeLayout(hash, Rectangle2D.Double(0.0, 0.0, 60.0, 30.0), 0, 0)
        val layout = GraphLayout(listOf(node), emptyList(), node.bounds, SpatialIndex(64.0, listOf(node)))
        val snapshot = GraphSnapshot(
            commits = listOf(CommitNode(hash, emptyList(), 0, "test")),
            refsByCommit = mapOf(hash to listOf(RevisionRef("refs/remotes/origin/test", hash, RefKind.REMOTE_BRANCH))),
            head = HeadState(null, null, false),
        )
        val canvas = RevisionGraphCanvas().apply {
            setSize(120, 80)
            show(snapshot, layout)
        }
        val image = BufferedImage(canvas.width, canvas.height, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                canvas.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }

        val neutralUnderlays = setOf(Color.WHITE.rgb, Color(0x303236).rgb)
        val exposedUnderlay = (22..52).any { y -> (28..34).any { x -> image.getRGB(x, y) in neutralUnderlays } }
        assertTrue(!exposedUnderlay, "The reference fill should meet the left rounded border without exposing its neutral underlay")
    }

    @Test fun `reference rows stay inside the canvas clip`() {
        val hash = "a".repeat(40)
        val node = NodeLayout(hash, Rectangle2D.Double(0.0, 10.0, 60.0, 30.0), 0, 0)
        val layout = GraphLayout(listOf(node), emptyList(), node.bounds, SpatialIndex(64.0, listOf(node)))
        val snapshot = GraphSnapshot(
            commits = listOf(CommitNode(hash, emptyList(), 0, "test")),
            refsByCommit = mapOf(hash to listOf(RevisionRef("refs/heads/test", hash, RefKind.LOCAL_BRANCH))),
            head = HeadState(null, null, false),
        )
        val canvas = RevisionGraphCanvas().apply {
            setSize(100, 40)
            show(snapshot, layout)
        }
        val untouched = Color.MAGENTA.rgb
        val image = BufferedImage(100, 80, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            try {
                graphics.color = Color(untouched, true)
                graphics.fillRect(0, 0, width, height)
                graphics.clip = Rectangle(0, 0, canvas.width, canvas.height)
                canvas.paint(graphics)
            } finally {
                graphics.dispose()
            }
        }

        assertEquals(untouched, image.getRGB(40, 50))
    }
}

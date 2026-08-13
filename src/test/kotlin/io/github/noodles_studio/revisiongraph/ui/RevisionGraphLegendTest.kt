package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.model.RefKind
import java.awt.BasicStroke
import java.util.Locale
import java.util.ResourceBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class RevisionGraphLegendTest {
    @Test
    fun `canvas and legend path styles share the actual graph stroke`() {
        val expectedWidth = 1.55f

        GraphPathStyle.entries.forEach { style ->
            val stroke = style.stroke()
            assertEquals(expectedWidth, stroke.lineWidth)
            assertEquals(BasicStroke.CAP_ROUND, stroke.endCap)
            assertEquals(BasicStroke.JOIN_ROUND, stroke.lineJoin)
        }
        assertSame(RevisionGraphColors.baseSelection, GraphPathStyle.BASE.color)
        assertSame(RevisionGraphColors.targetSelection, GraphPathStyle.TARGET.color)
        assertSame(RevisionGraphColors.parentPath, GraphPathStyle.PARENT.color)
    }

    @Test
    fun `legend covers every reference kind exactly once`() {
        val representedKinds = referenceLegendItems.flatMap(GraphLegendItem::refKinds)

        assertEquals(RefKind.entries.toSet(), representedKinds.toSet())
        assertEquals(representedKinds.size, representedKinds.distinct().size)
    }

    @Test
    fun `legend keeps the approved English terminology`() {
        val bundle = ResourceBundle.getBundle("messages.RevisionGraphBundle", Locale.ROOT)

        assertEquals("HEAD", bundle.getString("legend.reference.current"))
        assertEquals("Local", bundle.getString("legend.reference.local"))
        assertEquals("Remote", bundle.getString("legend.reference.remote"))
        assertEquals("Tag", bundle.getString("legend.reference.tag"))
    }

    @Test
    fun `legend keeps the approved Chinese terminology`() {
        val bundle = ResourceBundle.getBundle("messages.RevisionGraphBundle", Locale.SIMPLIFIED_CHINESE)

        assertEquals("当前", bundle.getString("legend.reference.current"))
        assertEquals("本地", bundle.getString("legend.reference.local"))
        assertEquals("远端", bundle.getString("legend.reference.remote"))
        assertEquals("标签", bundle.getString("legend.reference.tag"))
    }
}

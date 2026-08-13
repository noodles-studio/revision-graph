package io.github.noodles_studio.revisiongraph.ui

import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.RefKind
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder

internal data class GraphLegendItem(
    val messageKey: String,
    val swatch: GraphLegendSwatch,
    val refKinds: Set<RefKind> = emptySet(),
)

internal enum class GraphLegendSwatch {
    CURRENT,
    LOCAL,
    REMOTE,
    TAG,
    STASH,
    BISECT_GOOD,
    BISECT_BAD_SKIP,
    NOTES,
    OTHER,
    BASE_SELECTION,
    TARGET_SELECTION,
    BASE_PATH,
    TARGET_PATH,
    PARENT_PATH,
}

internal val referenceLegendItems = listOf(
    GraphLegendItem("legend.reference.current", GraphLegendSwatch.CURRENT),
    GraphLegendItem("legend.reference.local", GraphLegendSwatch.LOCAL, setOf(RefKind.LOCAL_BRANCH)),
    GraphLegendItem("legend.reference.remote", GraphLegendSwatch.REMOTE, setOf(RefKind.REMOTE_BRANCH)),
    GraphLegendItem("legend.reference.tag", GraphLegendSwatch.TAG, setOf(RefKind.TAG, RefKind.ANNOTATED_TAG)),
    GraphLegendItem("legend.reference.stash", GraphLegendSwatch.STASH, setOf(RefKind.STASH)),
    GraphLegendItem("legend.reference.bisect.good", GraphLegendSwatch.BISECT_GOOD, setOf(RefKind.BISECT_GOOD)),
    GraphLegendItem(
        "legend.reference.bisect.bad.skip",
        GraphLegendSwatch.BISECT_BAD_SKIP,
        setOf(RefKind.BISECT_BAD, RefKind.BISECT_SKIP),
    ),
    GraphLegendItem("legend.reference.notes", GraphLegendSwatch.NOTES, setOf(RefKind.NOTES)),
    GraphLegendItem("legend.reference.other", GraphLegendSwatch.OTHER, setOf(RefKind.OTHER)),
)

internal val selectionLegendItems = listOf(
    GraphLegendItem("legend.selection.base", GraphLegendSwatch.BASE_SELECTION),
    GraphLegendItem("legend.selection.target", GraphLegendSwatch.TARGET_SELECTION),
)

internal val pathLegendItems = listOf(
    GraphLegendItem("legend.path.base", GraphLegendSwatch.BASE_PATH),
    GraphLegendItem("legend.path.target", GraphLegendSwatch.TARGET_PATH),
    GraphLegendItem("legend.path.parent", GraphLegendSwatch.PARENT_PATH),
)

internal class RevisionGraphLegend : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        minimumSize = JBUI.size(230, 0)
        isOpaque = true
        border = CompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1),
            EmptyBorder(JBUI.scale(12), JBUI.scale(12), JBUI.scale(12), JBUI.scale(12)),
        )

        addGroup("legend.references", referenceLegendItems)
        addGroup("legend.selection", selectionLegendItems)
        addGroup("legend.path.status", pathLegendItems)
        add(Box.createVerticalGlue())
    }

    override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(286), super.getPreferredSize().height)

    private fun addGroup(titleKey: String, items: List<GraphLegendItem>) {
        add(TitledSeparator(message(titleKey)).apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        items.forEach { item -> add(legendRow(item)) }
        add(Box.createVerticalStrut(JBUI.scale(7)))
    }

    private fun legendRow(item: GraphLegendItem): JComponent = JPanel(BorderLayout(JBUI.scale(7), 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
        add(LegendSwatchComponent(item.swatch), BorderLayout.WEST)
        add(JBLabel(message(item.messageKey)), BorderLayout.CENTER)
    }
}

internal class RevisionGraphLegendOverlay : JPanel(null) {
    private val legend = RevisionGraphLegend().apply { isVisible = false }

    var legendVisible: Boolean
        get() = legend.isVisible
        set(value) {
            legend.isVisible = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        add(legend)
    }

    override fun doLayout() {
        val margin = JBUI.scale(12)
        val size = legend.preferredSize
        val legendWidth = size.width.coerceAtMost((width - margin * 2).coerceAtLeast(0))
        val legendHeight = size.height.coerceAtMost((height - margin * 2).coerceAtLeast(0))
        legend.setBounds(
            (width - legendWidth - margin).coerceAtLeast(0),
            margin,
            legendWidth,
            legendHeight,
        )
    }

    override fun contains(x: Int, y: Int): Boolean = legendVisible && legend.bounds.contains(x, y)
}

private class LegendSwatchComponent(private val swatch: GraphLegendSwatch) : JComponent() {
    init {
        preferredSize = JBUI.size(34, 20)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val graphics = g.create() as Graphics2D
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        when (swatch) {
            GraphLegendSwatch.BASE_SELECTION -> drawSelection(graphics, RevisionGraphColors.baseSelection)
            GraphLegendSwatch.TARGET_SELECTION -> drawSelection(graphics, RevisionGraphColors.targetSelection)
            GraphLegendSwatch.BASE_PATH -> drawPath(graphics, GraphPathStyle.BASE)
            GraphLegendSwatch.TARGET_PATH -> drawPath(graphics, GraphPathStyle.TARGET)
            GraphLegendSwatch.PARENT_PATH -> drawPath(graphics, GraphPathStyle.PARENT)
            else -> drawReference(graphics)
        }
        graphics.dispose()
    }

    private fun drawReference(g: Graphics2D) {
        val (kind, current) = when (swatch) {
            GraphLegendSwatch.CURRENT -> RefKind.LOCAL_BRANCH to true
            GraphLegendSwatch.LOCAL -> RefKind.LOCAL_BRANCH to false
            GraphLegendSwatch.REMOTE -> RefKind.REMOTE_BRANCH to false
            GraphLegendSwatch.TAG -> RefKind.TAG to false
            GraphLegendSwatch.STASH -> RefKind.STASH to false
            GraphLegendSwatch.BISECT_GOOD -> RefKind.BISECT_GOOD to false
            GraphLegendSwatch.BISECT_BAD_SKIP -> RefKind.BISECT_BAD to false
            GraphLegendSwatch.NOTES -> RefKind.NOTES to false
            else -> RefKind.OTHER to false
        }
        val x = JBUI.scale(2)
        val y = (height - JBUI.scale(14)) / 2
        val width = JBUI.scale(28)
        val height = JBUI.scale(14)
        g.color = RevisionGraphColors.refBackground(kind, current)
        g.fillRoundRect(x, y, width, height, JBUI.scale(5), JBUI.scale(5))
        g.color = RevisionGraphColors.refAccent(kind, current)
        g.fillRoundRect(x, y, JBUI.scale(6), height, JBUI.scale(5), JBUI.scale(5))
        g.color = JBColor.border()
        g.stroke = BasicStroke(1f)
        g.drawRoundRect(x, y, width, height, JBUI.scale(5), JBUI.scale(5))
    }

    private fun drawSelection(g: Graphics2D, color: JBColor) {
        val x = JBUI.scale(2)
        val y = (height - JBUI.scale(14)) / 2
        g.color = JBColor(Color(0xFFFFFF), Color(0x303236))
        g.fillRoundRect(x, y, JBUI.scale(28), JBUI.scale(14), JBUI.scale(5), JBUI.scale(5))
        g.color = color
        g.stroke = BasicStroke(JBUI.scale(3).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.drawRoundRect(x, y, JBUI.scale(28), JBUI.scale(14), JBUI.scale(5), JBUI.scale(5))
    }

    private fun drawPath(g: Graphics2D, style: GraphPathStyle) {
        g.color = style.color
        g.stroke = style.stroke()
        g.drawLine(JBUI.scale(2), height / 2, JBUI.scale(30), height / 2)
    }
}

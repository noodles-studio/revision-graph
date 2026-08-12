package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.layout.GraphTextMetrics
import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.geom.AffineTransform
import javax.swing.UIManager
import kotlin.math.ceil
import kotlin.math.max

/** Font configuration captured on the IDEA UI thread and reused by background layout. */
internal class GraphTypography private constructor(
    private val plainFont: Font,
    private val boldFont: Font,
) : GraphTextMetrics {
    private val renderContext = FontRenderContext(AffineTransform(), true, true)

    fun font(bold: Boolean): Font = if (bold) boldFont else plainFont

    override fun textWidth(text: String, bold: Boolean): Double =
        font(bold).getStringBounds(text, renderContext).width

    override fun rowHeight(): Double {
        val plain = plainFont.getLineMetrics("Ag", renderContext).height.toDouble()
        val bold = boldFont.getLineMetrics("Ag", renderContext).height.toDouble()
        return ceil(max(plain, bold) + VERTICAL_PADDING)
    }

    companion object {
        private const val FONT_SIZE = 11f
        private const val VERTICAL_PADDING = 10.0

        fun fromIdeaDefaults(): GraphTypography {
            val base = UIManager.getFont("Label.font") ?: Font(Font.DIALOG, Font.PLAIN, FONT_SIZE.toInt())
            return GraphTypography(
                base.deriveFont(Font.PLAIN, FONT_SIZE),
                base.deriveFont(Font.BOLD, FONT_SIZE),
            )
        }
    }
}

internal fun graphTextLeft(nodeX: Double): Float = (nodeX + 20.0).toFloat()

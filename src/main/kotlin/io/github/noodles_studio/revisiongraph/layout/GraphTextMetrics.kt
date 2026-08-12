package io.github.noodles_studio.revisiongraph.layout

import kotlin.math.ceil

internal interface GraphTextMetrics {
    fun textWidth(text: String, bold: Boolean): Double
    fun rowHeight(): Double
}

/** Pure fallback used by layout tests and non-UI callers. */
internal object EstimatedGraphTextMetrics : GraphTextMetrics {
    override fun textWidth(text: String, bold: Boolean): Double = text.length * if (bold) 7.5 else 7.2
    override fun rowHeight(): Double = 24.0
}

internal fun GraphTextMetrics.nodeWidth(labels: List<Pair<String, Boolean>>): Double {
    val widest = labels.maxOfOrNull { (text, bold) -> textWidth(text, bold) }
        ?: textWidth("00000000", true)
    return ceil(widest + 40.0)
}

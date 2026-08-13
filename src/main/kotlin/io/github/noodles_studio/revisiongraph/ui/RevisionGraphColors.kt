package io.github.noodles_studio.revisiongraph.ui

import com.intellij.ui.JBColor
import io.github.noodles_studio.revisiongraph.model.RefKind
import java.awt.BasicStroke
import java.awt.Color

/** Shared graph palette used by both the canvas and its legend. */
internal object RevisionGraphColors {
    val parentPath = JBColor(Color(0x626A73), Color(0xA4ABB4))
    val baseSelection = JBColor(Color(0x1E6FB8), Color(0x65B3F3))
    val targetSelection = JBColor(Color(0x8A2638), Color(0xE06C75))

    fun refBackground(kind: RefKind, current: Boolean) = when {
        current -> JBColor(Color(0xDCEEFF), Color(0x1C3A55))
        kind == RefKind.LOCAL_BRANCH -> JBColor(Color(0xDFF5EB), Color(0x1B3D32))
        kind == RefKind.REMOTE_BRANCH -> JBColor(Color(0xEEE6F8), Color(0x493B59))
        kind == RefKind.TAG || kind == RefKind.ANNOTATED_TAG -> JBColor(Color(0xFFF2CC), Color(0x554A2C))
        kind == RefKind.STASH -> JBColor(Color(0xF7E1F8), Color(0x533B58))
        kind == RefKind.BISECT_GOOD -> JBColor(Color(0xDDF2E1), Color(0x2E4D35))
        kind == RefKind.BISECT_BAD || kind == RefKind.BISECT_SKIP -> JBColor(Color(0xF7DFDF), Color(0x573535))
        kind == RefKind.NOTES -> JBColor(Color(0xE0F1F3), Color(0x304E53))
        else -> JBColor(Color(0xECEEF1), Color(0x3D4044))
    }

    fun refAccent(kind: RefKind, current: Boolean) = when {
        current -> JBColor(Color(0x2376BD), Color(0x65B3F3))
        kind == RefKind.LOCAL_BRANCH -> JBColor(Color(0x25815E), Color(0x59C89A))
        kind == RefKind.REMOTE_BRANCH -> JBColor(Color(0x7B5CAD), Color(0xB493D6))
        kind == RefKind.TAG || kind == RefKind.ANNOTATED_TAG -> JBColor(Color(0xA77B13), Color(0xE1C162))
        kind == RefKind.STASH -> JBColor(Color(0xA857AE), Color(0xDA91DE))
        kind == RefKind.BISECT_GOOD -> JBColor(Color(0x32884C), Color(0x72C58B))
        kind == RefKind.BISECT_BAD || kind == RefKind.BISECT_SKIP -> JBColor(Color(0xB84747), Color(0xDF7979))
        kind == RefKind.NOTES -> JBColor(Color(0x378892), Color(0x75C2CA))
        else -> JBColor(Color(0x68717B), Color(0xA8AFB7))
    }

    fun refText(kind: RefKind, current: Boolean) = if (current) JBColor(Color(0x125A95), Color(0xC2E3FF)) else when (kind) {
        RefKind.LOCAL_BRANCH -> JBColor(Color(0x176447), Color(0xA9E8CA))
        RefKind.REMOTE_BRANCH -> JBColor(Color(0x65458C), Color(0xD5BCEB))
        RefKind.TAG, RefKind.ANNOTATED_TAG -> JBColor(Color(0x765A12), Color(0xF1D98B))
        RefKind.STASH -> JBColor(Color(0x773D7B), Color(0xEDB8F0))
        RefKind.BISECT_GOOD -> JBColor(Color(0x216537), Color(0xAEE4BB))
        RefKind.BISECT_BAD, RefKind.BISECT_SKIP -> JBColor(Color(0x822F2F), Color(0xF0AAAA))
        RefKind.NOTES -> JBColor(Color(0x286973), Color(0xA9DCE1))
        RefKind.OTHER -> JBColor.foreground()
    }
}

internal enum class GraphPathStyle(val color: JBColor) {
    BASE(RevisionGraphColors.baseSelection),
    TARGET(RevisionGraphColors.targetSelection),
    PARENT(RevisionGraphColors.parentPath),
    ;

    fun stroke() = BasicStroke(PATH_WIDTH, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

    private companion object {
        const val PATH_WIDTH = 1.55f
    }
}

package io.github.noodles_studio.revisiongraph.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import io.github.noodles_studio.revisiongraph.platform.RevisionCheckoutService
import io.github.noodles_studio.revisiongraph.platform.RevisionCompareService
import io.github.noodles_studio.revisiongraph.platform.RevisionLogService
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/** Owns context-menu construction and native IntelliJ revision workflows. */
internal class RevisionGraphContextActions(
    private val project: Project,
    private val currentRoot: () -> Path?,
) {
    private val comparisons = RevisionCompareService(project)
    private val checkouts = RevisionCheckoutService(project)
    private val logs = RevisionLogService(project)

    fun showMenu(selection: RevisionCompareSelection, point: Point, canvas: RevisionGraphCanvas) {
        val base = selection.base
        val group = DefaultActionGroup()
        val target = selection.target
        if (target == null) {
            group.add(object : DumbAwareAction(message("menu.show.history", selection.active.displayName)) {
                override fun actionPerformed(e: AnActionEvent) = showLog(selection.active)
            })
            selection.activeRef?.takeIf { checkoutAvailable(it, selection.head) }?.let { ref ->
                group.add(object : DumbAwareAction(checkoutLabel(ref)) {
                    override fun actionPerformed(e: AnActionEvent) = checkout(ref, e)
                })
            }
            group.addSeparator()
            group.add(object : DumbAwareAction(message("menu.compare.workspace", base.displayName)) {
                override fun actionPerformed(e: AnActionEvent) = compareWithWorkspace(base)
            })
            group.add(object : DumbAwareAction(message("menu.compare.head", base.displayName, headDisplayName(selection.head))) {
                override fun actionPerformed(e: AnActionEvent) = compareWithHead(base)
            })
            group.addSeparator()
            val copyText = copyableRevisionText(selection.activeRefs, selection.active.hash)
            val copyMessage = if (selection.activeRefs.isEmpty()) "menu.copy.hash" else "menu.copy.refs"
            group.add(object : DumbAwareAction(message(copyMessage)) {
                override fun actionPerformed(e: AnActionEvent) {
                    CopyPasteManager.getInstance().setContents(StringSelection(copyText))
                }
            })
        } else {
            group.add(object : DumbAwareAction(message("menu.show.range", base.displayName, target.displayName)) {
                override fun actionPerformed(e: AnActionEvent) = showLogRange(base, target)
            })
            group.addSeparator()
            group.add(object : DumbAwareAction(message("menu.compare.revisions", base.displayName, target.displayName)) {
                override fun actionPerformed(e: AnActionEvent) = compareRevisions(base, target)
            })
        }
        ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, group)
            .component
            .show(canvas, point.x, point.y)
    }

    fun showSharedLog(revision: CompareRevision) {
        val root = currentRoot()
        if (root == null || !logs.showShared(root, revision)) showRepositoryUnavailable()
    }

    private fun showLog(revision: CompareRevision) {
        val root = currentRoot()
        if (root == null || !logs.show(root, revision)) showRepositoryUnavailable()
    }

    private fun showLogRange(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot()
        if (root == null || !logs.showRange(root, base, target)) showRepositoryUnavailable()
    }

    private fun compareWithWorkspace(revision: CompareRevision) {
        val root = currentRoot()
        if (root == null || !comparisons.compareWithWorkspace(root, revision)) showRepositoryUnavailable()
    }

    private fun compareWithHead(revision: CompareRevision) {
        val root = currentRoot()
        if (root == null || !comparisons.compareWithHead(root, revision)) showRepositoryUnavailable()
    }

    private fun compareRevisions(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot()
        if (root == null || !comparisons.compareRevisions(root, base, target)) showRepositoryUnavailable()
    }

    private fun checkout(ref: RevisionRef, event: AnActionEvent) {
        val root = currentRoot()
        if (root == null || !checkouts.checkout(root, ref, event)) showRepositoryUnavailable()
    }

    private fun checkoutLabel(ref: RevisionRef): String = when (ref.kind) {
        RefKind.LOCAL_BRANCH -> message("menu.switch.branch", ref.displayName)
        RefKind.REMOTE_BRANCH -> message("menu.checkout.remote", ref.displayName)
        RefKind.TAG, RefKind.ANNOTATED_TAG -> message("menu.checkout.tag", ref.displayName)
        else -> message("menu.checkout.ref", ref.displayName)
    }

    private fun showRepositoryUnavailable() {
        Messages.showWarningDialog(project, message("warning.repository.unavailable"), message("dialog.title"))
    }
}

package io.github.noodles_studio.revisiongraph.platform

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message

/** Presents IntelliJ's project-wide Git4Idea Fetch action with RevisionGraph-localized text. */
internal object RevisionFetchAction {
    fun create(actionManager: ActionManager = ActionManager.getInstance()): AnAction? =
        actionManager.getAction("Git.Fetch")?.let(::LocalizedFetchAction)

    private class LocalizedFetchAction(
        private val delegate: AnAction,
    ) : DumbAwareAction(message("toolbar.fetch"), message("toolbar.fetch.tooltip"), AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread(): ActionUpdateThread = delegate.actionUpdateThread

        override fun update(e: AnActionEvent) {
            delegate.update(e)
            e.presentation.text = message("toolbar.fetch")
            e.presentation.description = message("toolbar.fetch.tooltip")
            e.presentation.icon = AllIcons.Actions.Refresh
        }

        override fun actionPerformed(e: AnActionEvent) = delegate.actionPerformed(e)
    }
}

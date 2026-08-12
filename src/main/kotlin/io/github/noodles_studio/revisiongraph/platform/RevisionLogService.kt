package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.Content
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.RevisionLogTarget
import io.github.noodles_studio.revisiongraph.model.revisionLogTarget
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JComponent
import javax.swing.SwingUtilities

internal class RevisionLogService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)
    private val openedTabs = mutableMapOf<LogTabKey, WeakReference<Content>>()
    private val openingTabs = mutableSetOf<LogTabKey>()
    private val sharedGeneration = AtomicLong()
    private var sharedTab: SharedLogTab? = null

    fun show(root: Path, selected: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.log"), true) {
            private var commit: Hash? = null
            private var target: RevisionLogTarget = RevisionLogTarget.Commit(selected.hash)

            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                commit = runCatching { Git.getInstance().resolveReference(repository, selected.hash) }.getOrNull()
                if (selected.revision != selected.hash) {
                    indicator.checkCanceled()
                    val namedHash = runCatching {
                        Git.getInstance().resolveReference(repository, "${selected.revision}^{commit}")?.asString()
                    }.getOrNull()
                    target = revisionLogTarget(selected, namedHash)
                }
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                if (!VcsProjectLog.isAvailable(project)) return showLogUnavailable()
                if (target is RevisionLogTarget.Reference) {
                    val reference = (target as RevisionLogTarget.Reference).name
                    val filters = VcsLogFilterObject.collection(
                        VcsLogFilterObject.fromRoot(repository.root),
                        VcsLogFilterObject.fromBranch(reference),
                    )
                    openOrActivateLogTab(repository, reference, filters)
                } else {
                    commit?.let { VcsProjectLog.showRevisionInMainLog(project, repository.root, it) }
                        ?: showLogUnavailable()
                }
            }
        })
        return true
    }

    fun showShared(root: Path, selected: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        val requestId = sharedGeneration.incrementAndGet()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.log"), true) {
            private var commitExists = false
            private var target: RevisionLogTarget = RevisionLogTarget.Commit(selected.hash)

            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                commitExists = runCatching { Git.getInstance().resolveReference(repository, selected.hash) }.getOrNull() != null
                if (selected.revision != selected.hash) {
                    indicator.checkCanceled()
                    val namedHash = runCatching {
                        Git.getInstance().resolveReference(repository, "${selected.revision}^{commit}")?.asString()
                    }.getOrNull()
                    target = revisionLogTarget(selected, namedHash)
                }
            }

            override fun onSuccess() {
                if (project.isDisposed || sharedGeneration.get() != requestId) return
                if (!VcsProjectLog.isAvailable(project) || !commitExists) return showLogUnavailable()
                openOrUpdateSharedLogTab(filters(repository, target), requestId)
            }
        })
        return true
    }

    fun showRange(root: Path, base: CompareRevision, target: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.log"), true) {
            private var baseRevision = base.hash
            private var targetRevision = target.hash

            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                baseRevision = verifiedRevision(repository, base)
                indicator.checkCanceled()
                targetRevision = verifiedRevision(repository, target)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                if (!VcsProjectLog.isAvailable(project)) return showLogUnavailable()
                val filters = VcsLogFilterObject.collection(
                    VcsLogFilterObject.fromRoot(repository.root),
                    VcsLogFilterObject.fromRange(baseRevision, targetRevision),
                )
                openOrActivateLogTab(repository, "range:${base.hash}..${target.hash}", filters)
            }
        })
        return true
    }

    private fun filters(repository: GitRepository, target: RevisionLogTarget): VcsLogFilterCollection =
        VcsLogFilterObject.collection(
            VcsLogFilterObject.fromRoot(repository.root),
            when (target) {
                is RevisionLogTarget.Reference -> VcsLogFilterObject.fromBranch(target.name)
                is RevisionLogTarget.Commit -> VcsLogFilterObject.fromHash(target.hash)
            },
        )

    private fun openOrUpdateSharedLogTab(filters: VcsLogFilterCollection, requestId: Long) {
        VcsProjectLog.runInMainLog(project) {
            if (project.isDisposed || sharedGeneration.get() != requestId) return@runInMainLog
            val existing = sharedTab
            val existingUi = existing?.ui?.get()
            val existingContent = existing?.content?.get()
            if (existingUi != null && isUsable(existingContent)) {
                existingUi.filterUi.setFilters(filters)
                activate(existingContent)
                return@runInMainLog
            }

            sharedTab = null
            val ui = VcsProjectLog.getInstance(project).openLogTab(filters) ?: return@runInMainLog
            val content = findContent(ui.mainComponent) ?: return@runInMainLog
            sharedTab = SharedLogTab(WeakReference(ui), WeakReference(content))
        }
    }

    private fun openOrActivateLogTab(
        repository: GitRepository,
        reference: String,
        filters: VcsLogFilterCollection,
    ) {
        val key = LogTabKey(repository.root.path, reference)
        if (activate(openedTabs[key]?.get())) return
        openedTabs.remove(key)
        if (!openingTabs.add(key)) return

        VcsProjectLog.runInMainLog(project) {
            try {
                if (activate(openedTabs[key]?.get())) return@runInMainLog
                val ui = VcsProjectLog.getInstance(project).openLogTab(filters) ?: return@runInMainLog
                findContent(ui.mainComponent)?.let { openedTabs[key] = WeakReference(it) }
            } finally {
                openingTabs.remove(key)
            }
        }
    }

    private fun activate(content: Content?): Boolean {
        if (!isUsable(content)) return false
        val manager = content!!.manager ?: return false
        manager.setSelectedContent(content, true)
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS)?.activate(null, true)
        return true
    }

    private fun isUsable(content: Content?): Boolean {
        if (content == null || !content.isValid) return false
        val manager = content.manager ?: return false
        return !manager.isDisposed
    }

    private fun findContent(component: JComponent): Content? {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.VCS) ?: return null
        return toolWindow.contentManager.contents.firstOrNull { content ->
            content.component === component || SwingUtilities.isDescendingFrom(component, content.component)
        }
    }

    private fun showLogUnavailable() {
        Messages.showWarningDialog(project, message("warning.log.unavailable"), message("dialog.title"))
    }

    private fun verifiedRevision(repository: GitRepository, selected: CompareRevision): String {
        if (selected.revision == selected.hash) return selected.hash
        val resolved = runCatching {
            Git.getInstance().resolveReference(repository, "${selected.revision}^{commit}")?.asString()
        }.getOrNull()
        return if (resolved.equals(selected.hash, ignoreCase = true)) selected.revision else selected.hash
    }

    private data class LogTabKey(val root: String, val reference: String)
    private data class SharedLogTab(
        val ui: WeakReference<MainVcsLogUi>,
        val content: WeakReference<Content>,
    )
}

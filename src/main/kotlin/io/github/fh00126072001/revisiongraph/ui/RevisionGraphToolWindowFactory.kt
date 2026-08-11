package io.github.fh00126072001.revisiongraph.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.*
import com.intellij.ui.content.Content
import com.intellij.util.Alarm
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.fh00126072001.revisiongraph.git.GitClient
import io.github.fh00126072001.revisiongraph.layout.GraphLayout
import io.github.fh00126072001.revisiongraph.layout.LayeredDagLayoutEngine
import io.github.fh00126072001.revisiongraph.model.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Point
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.border.EmptyBorder

class RevisionGraphToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val view = RevisionGraphView(project)
        val content = toolWindow.contentManager.factory.createContent(view.component, "", false)
        content.setDisposer(view)
        toolWindow.contentManager.addContent(content)
    }
}

class OpenRevisionGraphAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Revision Graph")?.show() }
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
}

private class RevisionGraphView(private val project: Project) : Disposable {
    private val git = GitClient(project)
    private val comparisons = RevisionCompareService(project)
    private val logs = RevisionLogService(project)
    private val generation = AtomicLong()
    private val cache = mutableMapOf<Path, Pair<GraphSnapshot, GraphLayout>>()
    private var roots = emptyList<Path>()
    private val rootBox = ComboBox<Path>()
    private val refresh = JButton("Refresh")
    private val graphSummary = JBLabel()
    private val zoomLabel = JBLabel("100%", SwingConstants.CENTER)
    private val status = JBLabel("Loading…", SwingConstants.CENTER)
    private val retry = JButton("Retry")
    private val canvas = RevisionGraphCanvas()
    private val cards = JPanel(CardLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentRoot: Path? = null
    private var graphIndicator: ProgressIndicator? = null
    private var updatingRoots = false

    val component: JComponent

    init {
        val toolbar = JPanel(BorderLayout()).apply {
            border = EmptyBorder(7, 10, 7, 10)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 7, 0)).apply {
                isOpaque = false
                add(JBLabel("Repository")); add(rootBox); add(refresh)
                add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(1, 22) })
                add(graphSummary)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(toolbarButton("−", "Zoom out") { canvas.zoomOut() })
                add(zoomLabel.apply { preferredSize = java.awt.Dimension(48, 26) })
                add(toolbarButton("+", "Zoom in") { canvas.zoomIn() })
                add(toolbarButton("Fit", "Fit graph to window") { canvas.fitToView() })
                add(toolbarButton("1:1", "Reset to 100%") { canvas.resetView() })
            }, BorderLayout.EAST)
        }
        cards.add(canvas, "graph")
        cards.add(JPanel(BorderLayout()).apply { add(status, BorderLayout.CENTER); add(JPanel().apply { add(retry) }, BorderLayout.SOUTH) }, "status")
        component = JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(cards, BorderLayout.CENTER) }
        rootBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean) =
                super.getListCellRendererComponent(list, (value as? Path)?.toString() ?: value, index, isSelected, cellHasFocus)
        }
        rootBox.addActionListener {
            if (updatingRoots) return@addActionListener
            val selected = rootBox.selectedItem as? Path
            if (selected != null && selected != currentRoot) {
                canvas.clearSelection()
                currentRoot = selected
                load(false)
            }
        }
        refresh.addActionListener { refreshRoots(); load(true) }
        retry.addActionListener { refreshRoots(); load(true) }
        canvas.onContextMenu = ::showContextMenu
        canvas.onRevisionSelected = ::showSharedLog
        canvas.onZoomChanged = { zoomLabel.text = "$it%" }
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
            if (repository.root.toNioPath() == currentRoot) {
                alarm.cancelAllRequests(); alarm.addRequest({ if (component.isShowing) load(true) }, 500)
            }
        })
        refreshRoots()
        if (roots.isEmpty()) {
            showStatus("Waiting for Git repositories…", true)
            alarm.addRequest({ refreshRoots(); if (currentRoot != null) load(false) else showStatus("No Git roots are configured for this project", true) }, 800)
        } else load(false)
    }

    private fun refreshRoots() {
        val repositoryRoots = GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }
        val mappedRoots = ProjectLevelVcsManager.getInstance(project).getAllVcsRoots().asSequence()
            .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
            .map { it.path.toNioPath() }
        val discovered = (repositoryRoots.asSequence() + mappedRoots).distinct().sortedBy(Path::toString).toList()
        if (discovered == roots) return
        val previous = currentRoot
        roots = discovered
        updatingRoots = true
        try {
            rootBox.model = DefaultComboBoxModel(discovered.toTypedArray())
            currentRoot = previous?.takeIf { it in discovered } ?: discovered.firstOrNull()
            if (currentRoot != previous) canvas.clearSelection()
            rootBox.selectedItem = currentRoot
            rootBox.isEnabled = discovered.size > 1
        } finally { updatingRoots = false }
    }

    private fun load(force: Boolean) {
        if (currentRoot == null) refreshRoots()
        val root = currentRoot ?: return showStatus("No Git root selected. Refresh after IDEA finishes Git initialization.", true)
        val id = generation.incrementAndGet(); graphIndicator?.cancel()
        if (!force) cache[root]?.let { (snapshot, layout) -> publish(snapshot, layout); return }
        showStatus("Loading revision graph…", false)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Revision Graph", true) {
            private var result: LoadResult<Pair<GraphSnapshot, GraphLayout>>? = null
            override fun run(indicator: ProgressIndicator) {
                graphIndicator = indicator
                result = when (val loaded = git.loadGraph(root, indicator)) {
                    is LoadResult.Success -> try { LoadResult.Success(loaded.value to LayeredDagLayoutEngine().layout(loaded.value) { indicator.isCanceled }) }
                        catch (_: InterruptedException) { LoadResult.Cancelled }
                    is LoadResult.Empty -> loaded; is LoadResult.Failure -> loaded; LoadResult.Cancelled -> LoadResult.Cancelled
                }
            }
            override fun onFinished() {
                if (generation.get() != id || project.isDisposed) return
                when (val value = result) {
                    is LoadResult.Success -> { cache[root] = value.value; publish(value.value.first, value.value.second) }
                    is LoadResult.Empty -> showStatus(value.reason, true)
                    is LoadResult.Failure -> showStatus("${value.summary}${value.details?.let { ": $it" }.orEmpty()}", true)
                    else -> if (cache[root] == null) showStatus("Loading cancelled", true)
                }
            }
        })
    }

    private fun publish(snapshot: GraphSnapshot, layout: GraphLayout) {
        canvas.show(snapshot, layout); (cards.layout as CardLayout).show(cards, "graph")
        graphSummary.text = "${snapshot.commits.size} commits  ·  ${snapshot.refsByCommit.values.sumOf { it.size }} refs"
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        status.text = "<html><div style='text-align:center'>${escape(text)}</div></html>"; retry.isVisible = canRetry
        (cards.layout as CardLayout).show(cards, "status")
    }

    private fun showContextMenu(selection: RevisionCompareSelection, point: Point) {
        val base = selection.base
        val group = DefaultActionGroup()
        val target = selection.target
        group.add(object : DumbAwareAction("查看 ${selection.active.displayName} 的提交记录") {
            override fun actionPerformed(e: AnActionEvent) = showLog(selection.active)
        })
        group.addSeparator()
        if (target == null) {
            group.add(object : DumbAwareAction("${base.displayName} 与当前工作区比较差异") {
                override fun actionPerformed(e: AnActionEvent) = compareWithWorkspace(base)
            })
        } else {
            group.add(object : DumbAwareAction("比较 Ⅰ ${base.displayName} → Ⅱ ${target.displayName}") {
                override fun actionPerformed(e: AnActionEvent) = compareRevisions(base, target)
            })
        }
        ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group).component.show(canvas, point.x, point.y)
    }

    private fun showLog(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !logs.show(root, revision)) showRepositoryUnavailable()
    }

    private fun showSharedLog(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !logs.showShared(root, revision)) showRepositoryUnavailable()
    }

    private fun compareWithWorkspace(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareWithWorkspace(root, revision)) showRepositoryUnavailable()
    }

    private fun compareRevisions(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareRevisions(root, base, target)) showRepositoryUnavailable()
    }

    private fun showRepositoryUnavailable() {
        Messages.showWarningDialog(project, "The selected Git repository is no longer available. Refresh the revision graph and try again.", "Revision Graph")
    }

    override fun dispose() { generation.incrementAndGet(); graphIndicator?.cancel(); cache.clear() }
    private fun toolbarButton(text: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip; isFocusable = false; margin = java.awt.Insets(2, 9, 2, 9); addActionListener { action() }
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

internal class RevisionCompareService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)

    fun compareWithWorkspace(root: Path, selected: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Revision Comparison", true) {
            private var revision = selected.hash
            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                revision = verifiedRevision(repository, selected)
            }
            override fun onSuccess() {
                if (!project.isDisposed) GitBrancher.getInstance(project).showDiffWithLocal(revision, listOf(repository))
            }
        })
        return true
    }

    fun compareRevisions(root: Path, base: CompareRevision, target: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Revision Comparison", true) {
            private var baseRevision = base.hash
            private var targetRevision = target.hash
            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                baseRevision = verifiedRevision(repository, base)
                indicator.checkCanceled()
                targetRevision = verifiedRevision(repository, target)
            }
            override fun onSuccess() {
                if (!project.isDisposed) GitBrancher.getInstance(project).showDiff(baseRevision, targetRevision, listOf(repository))
            }
        })
        return true
    }

    private fun verifiedRevision(repository: GitRepository, selected: CompareRevision): String {
        if (selected.revision == selected.hash) return selected.hash
        val resolved = runCatching {
            Git.getInstance().resolveReference(repository, "${selected.revision}^{commit}")?.asString()
        }.getOrNull()
        return if (resolved.equals(selected.hash, ignoreCase = true)) selected.revision else selected.hash
    }

    private fun findRepository(root: Path): GitRepository? {
        val expected = root.toAbsolutePath().normalize()
        return repositoryManager.repositories.firstOrNull { it.root.toNioPath().toAbsolutePath().normalize() == expected }
    }
}

internal class RevisionLogService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)
    private val openedTabs = mutableMapOf<LogTabKey, WeakReference<Content>>()
    private val openingTabs = mutableSetOf<LogTabKey>()
    private val sharedGeneration = AtomicLong()
    private var sharedTab: SharedLogTab? = null

    fun show(root: Path, selected: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Git Log", true) {
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
        val repository = findRepository(root) ?: return false
        val requestId = sharedGeneration.incrementAndGet()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Preparing Git Log", true) {
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
        Messages.showWarningDialog(project, "IDEA's Git Log is not available for the selected revision.", "Revision Graph")
    }

    private fun findRepository(root: Path): GitRepository? {
        val expected = root.toAbsolutePath().normalize()
        return repositoryManager.repositories.firstOrNull { it.root.toNioPath().toAbsolutePath().normalize() == expected }
    }

    private data class LogTabKey(val root: String, val reference: String)
    private data class SharedLogTab(
        val ui: WeakReference<MainVcsLogUi>,
        val content: WeakReference<Content>,
    )
}

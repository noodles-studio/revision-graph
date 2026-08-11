package io.github.noodles_studio.revisiongraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
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
import com.intellij.util.ui.JBUI
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.VcsLogFilterCollection
import com.intellij.vcs.log.impl.VcsProjectLog
import com.intellij.vcs.log.ui.MainVcsLogUi
import com.intellij.vcs.log.visible.filters.VcsLogFilterObject
import git4idea.GitReference
import git4idea.GitTag
import git4idea.actions.ref.GitCheckoutAction
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.git.GitClient
import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.layout.LayeredDagLayoutEngine
import io.github.noodles_studio.revisiongraph.model.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.datatransfer.StringSelection
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.lang.ref.WeakReference
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.border.EmptyBorder

class RevisionGraphToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.setStripeTitleProvider { message("toolwindow.title") }
        toolWindow.title = message("toolwindow.title")
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
    private val checkouts = RevisionCheckoutService(project)
    private val logs = RevisionLogService(project)
    private val generation = AtomicLong()
    private val cache = mutableMapOf<Path, Pair<GraphSnapshot, GraphLayout>>()
    private var roots = emptyList<Path>()
    private val rootBox = ComboBox<Path>().apply {
        isVisible = false
        preferredSize = Dimension(180, 26)
        toolTipText = message("toolbar.repository")
    }
    private val zoomBox = ComboBox(arrayOf("25%", "50%", "75%", "100%", "125%", "150%", "200%", "300%")).apply {
        isEditable = true
        isFocusable = true
        prototypeDisplayValue = "300%"
        preferredSize = JBUI.size(88, 26)
        minimumSize = preferredSize
        toolTipText = message("toolbar.zoom.percent")
    }
    private val graphSummary = JBLabel()
    private val status = JBLabel(message("status.loading"), SwingConstants.CENTER)
    private val retry = JButton(message("status.retry"))
    private val canvas = RevisionGraphCanvas()
    private val cards = JPanel(CardLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentRoot: Path? = null
    private var publishedRoot: Path? = null
    private var publishedHead: HeadState? = null
    private var graphIndicator: ProgressIndicator? = null
    private var updatingRoots = false
    private var updatingZoom = false
    private var graphLoading = false
    private val refreshAction = object : DumbAwareAction(
        message("toolbar.refresh"),
        message("toolbar.refresh.tooltip"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = refreshGraph()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = !graphLoading }
    }
    private val refreshToolbar: ActionToolbar

    val component: JComponent

    init {
        refreshToolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLBAR,
            DefaultActionGroup(refreshAction),
            true,
        ).apply {
            targetComponent = canvas
            setMiniMode(true)
            component.isOpaque = false
        }
        val toolbar = JPanel(BorderLayout()).apply {
            border = EmptyBorder(7, 10, 7, 10)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 7, 0)).apply {
                isOpaque = false
                add(refreshToolbar.component); add(rootBox)
                add(JSeparator(SwingConstants.VERTICAL).apply { preferredSize = java.awt.Dimension(1, 22) })
                add(graphSummary)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(toolbarButton("−", message("toolbar.zoom.out")) { canvas.zoomOut() })
                add(zoomBox)
                add(toolbarButton("+", message("toolbar.zoom.in")) { canvas.zoomIn() })
                add(fitButton())
            }, BorderLayout.EAST)
        }
        cards.add(canvas, "graph")
        cards.add(JPanel(BorderLayout()).apply { add(status, BorderLayout.CENTER); add(JPanel().apply { add(retry) }, BorderLayout.SOUTH) }, "status")
        component = JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(cards, BorderLayout.CENTER) }
        rootBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): java.awt.Component {
                val path = value as? Path
                return super.getListCellRendererComponent(list, path?.let(::repositoryDisplayName) ?: value, index, isSelected, cellHasFocus).also {
                    (it as? JComponent)?.toolTipText = path?.toString()
                }
            }
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
        zoomBox.addActionListener { if (!updatingZoom) applyZoomFromEditor() }
        (zoomBox.editor.editorComponent as? JTextField)?.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = applyZoomFromEditor()
        })
        retry.addActionListener { refreshGraph() }
        canvas.onContextMenu = ::showContextMenu
        canvas.onRevisionSelected = ::showSharedLog
        canvas.onZoomChanged = ::updateZoomBox
        updateZoomBox(canvas.zoomPercent())
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "refreshGraph")
        component.actionMap.put("refreshGraph", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) { if (!graphLoading) refreshGraph() }
        })
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repository ->
            if (repository.root.toNioPath() == currentRoot) {
                alarm.cancelAllRequests(); alarm.addRequest({ if (component.isShowing) load(true) }, 500)
            }
        })
        refreshRoots()
        if (roots.isEmpty()) {
            showStatus(message("status.waiting.repositories"), true)
            alarm.addRequest({
                refreshRoots()
                if (currentRoot != null) load(false) else showStatus(message("status.no.repositories"), true)
            }, 800)
        } else load(false)
    }

    private fun refreshRoots() {
        val repositoryRoots = GitRepositoryManager.getInstance(project).repositories.map { it.root.toNioPath() }
        val mappedRoots = ProjectLevelVcsManager.getInstance(project).getAllVcsRoots().asSequence()
            .filter { it.vcs?.name.equals("Git", ignoreCase = true) }
            .map { it.path.toNioPath() }
        val discovered = (repositoryRoots.asSequence() + mappedRoots).distinct().sortedBy(Path::toString).toList()
        if (discovered == roots) {
            rootBox.isVisible = discovered.size > 1
            return
        }
        val previous = currentRoot
        roots = discovered
        updatingRoots = true
        try {
            rootBox.model = DefaultComboBoxModel(discovered.toTypedArray())
            currentRoot = previous?.takeIf { it in discovered } ?: discovered.firstOrNull()
            if (currentRoot != previous) canvas.clearSelection()
            rootBox.selectedItem = currentRoot
            rootBox.isVisible = discovered.size > 1
        } finally { updatingRoots = false }
    }

    private fun refreshGraph() {
        refreshRoots()
        load(true)
    }

    private fun repositoryDisplayName(path: Path): String {
        val shortName = path.fileName?.toString() ?: path.toString()
        if (roots.count { it.fileName?.toString() == shortName } <= 1) return shortName
        val base = project.basePath?.let(Path::of) ?: return path.toString()
        return runCatching { base.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("..") } ?: path.toString()
    }

    private fun applyZoomFromEditor() {
        val percent = parseZoomPercent(zoomBox.editor.item)
        if (percent == null) updateZoomBox(canvas.zoomPercent()) else canvas.setZoomPercent(percent)
    }

    private fun updateZoomBox(percent: Int) {
        updatingZoom = true
        try { zoomBox.editor.item = "$percent%" } finally { updatingZoom = false }
    }

    private fun fitButton() = JButton("${message("toolbar.fit")} ▾").apply {
        toolTipText = message("toolbar.fit.tooltip")
        isFocusable = false
        margin = java.awt.Insets(2, 9, 2, 9)
        addActionListener { showFitMenu(this) }
    }

    private fun showFitMenu(anchor: JComponent) {
        val group = DefaultActionGroup().apply {
            add(object : DumbAwareAction(message("toolbar.fit.graph")) {
                override fun actionPerformed(e: AnActionEvent) = canvas.fitToView()
            })
            add(object : DumbAwareAction(message("toolbar.fit.width")) {
                override fun actionPerformed(e: AnActionEvent) = canvas.fitWidth()
            })
            add(object : DumbAwareAction(message("toolbar.fit.height")) {
                override fun actionPerformed(e: AnActionEvent) = canvas.fitHeight()
            })
        }
        ActionManager.getInstance().createActionPopupMenu(ActionPlaces.POPUP, group).component.show(anchor, 0, anchor.height)
    }

    private fun setGraphLoading(value: Boolean) {
        graphLoading = value
        refreshToolbar.updateActionsAsync()
    }

    private fun load(force: Boolean) {
        if (currentRoot == null) refreshRoots()
        val root = currentRoot ?: run {
            generation.incrementAndGet(); graphIndicator?.cancel(); setGraphLoading(false)
            return showStatus(message("status.no.selected.repository"), true)
        }
        val id = generation.incrementAndGet(); graphIndicator?.cancel()
        if (!force) cache[root]?.let { (snapshot, layout) -> publish(snapshot, layout); return }
        val keepGraphVisible = publishedRoot == root && cache[root] != null
        setGraphLoading(true)
        if (keepGraphVisible) graphSummary.text = message("status.refreshing.graph")
        else showStatus(message("status.loading.graph"), false)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.loading.graph"), true) {
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
                setGraphLoading(false)
                when (val value = result) {
                    is LoadResult.Success -> { cache[root] = value.value; publish(value.value.first, value.value.second) }
                    is LoadResult.Empty -> showStatus(value.reason, true)
                    is LoadResult.Failure -> {
                        val details = "${value.summary}${value.details?.let { ": $it" }.orEmpty()}"
                        if (keepGraphVisible) graphSummary.text = message("status.refresh.failed", details)
                        else showStatus(details, true)
                    }
                    else -> if (keepGraphVisible) cache[root]?.first?.let(::updateGraphSummary)
                        else showStatus(message("status.loading.cancelled"), true)
                }
            }
        })
    }

    private fun publish(snapshot: GraphSnapshot, layout: GraphLayout) {
        val root = currentRoot
        val focusHead = shouldFocusHead(publishedRoot, publishedHead, root, snapshot.head)
        publishedRoot = root
        publishedHead = snapshot.head
        canvas.show(snapshot, layout, snapshot.head.hash?.takeIf { focusHead })
        (cards.layout as CardLayout).show(cards, "graph")
        updateGraphSummary(snapshot)
    }

    private fun updateGraphSummary(snapshot: GraphSnapshot) {
        graphSummary.text = message(
            "status.graph.summary",
            snapshot.commits.size,
            snapshot.refsByCommit.values.sumOf { it.size },
        )
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        status.text = "<html><div style='text-align:center'>${escape(text)}</div></html>"; retry.isVisible = canRetry
        (cards.layout as CardLayout).show(cards, "status")
    }

    private fun showContextMenu(selection: RevisionCompareSelection, point: Point) {
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

    private fun showLogRange(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot
        if (root == null || !logs.showRange(root, base, target)) showRepositoryUnavailable()
    }

    private fun compareWithWorkspace(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareWithWorkspace(root, revision)) showRepositoryUnavailable()
    }

    private fun compareWithHead(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareWithHead(root, revision)) showRepositoryUnavailable()
    }

    private fun compareRevisions(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareRevisions(root, base, target)) showRepositoryUnavailable()
    }

    private fun checkout(ref: RevisionRef, event: AnActionEvent) {
        val root = currentRoot
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

    override fun dispose() { generation.incrementAndGet(); graphIndicator?.cancel(); cache.clear() }
    private fun toolbarButton(text: String, tooltip: String, action: () -> Unit) = JButton(text).apply {
        toolTipText = tooltip; isFocusable = false; margin = java.awt.Insets(2, 9, 2, 9); addActionListener { action() }
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

internal fun parseZoomPercent(value: Any?): Double? = value?.toString()
    ?.trim()
    ?.removeSuffix("%")
    ?.trim()
    ?.replace(',', '.')
    ?.toDoubleOrNull()
    ?.takeIf { it.isFinite() && it > 0.0 }

internal fun shouldFocusHead(
    previousRoot: Path?,
    previousHead: HeadState?,
    currentRoot: Path?,
    currentHead: HeadState,
): Boolean = currentHead.hash != null && (previousRoot != currentRoot || previousHead != currentHead)

internal class RevisionCompareService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)

    fun compareWithWorkspace(root: Path, selected: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.comparison"), true) {
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

    fun compareWithHead(root: Path, selected: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.comparison"), true) {
            private var selectedRevision = selected.hash
            private var headRevision = "HEAD"
            override fun run(indicator: ProgressIndicator) {
                indicator.checkCanceled()
                selectedRevision = verifiedRevision(repository, selected)
                indicator.checkCanceled()
                headRevision = repository.currentBranch?.name ?: "HEAD"
            }
            override fun onSuccess() {
                if (!project.isDisposed) GitBrancher.getInstance(project).showDiff(selectedRevision, headRevision, listOf(repository))
            }
        })
        return true
    }

    fun compareRevisions(root: Path, base: CompareRevision, target: CompareRevision): Boolean {
        val repository = findRepository(root) ?: return false
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.preparing.comparison"), true) {
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

internal class RevisionCheckoutService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)
    private val checkoutAction = GitCheckoutAction()

    fun checkout(root: Path, selected: RevisionRef, event: AnActionEvent): Boolean {
        val repository = findRepository(root) ?: return false
        val reference: GitReference = (when (selected.kind) {
            RefKind.LOCAL_BRANCH -> repository.branches.findLocalBranch(selected.displayName)
            RefKind.REMOTE_BRANCH -> repository.branches.findRemoteBranch(selected.displayName)
            RefKind.TAG, RefKind.ANNOTATED_TAG -> GitTag(selected.displayName)
            else -> null
        }) ?: return false
        checkoutAction.actionPerformed(event, project, listOf(repository), reference)
        return true
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
        val repository = findRepository(root) ?: return false
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
        val repository = findRepository(root) ?: return false
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

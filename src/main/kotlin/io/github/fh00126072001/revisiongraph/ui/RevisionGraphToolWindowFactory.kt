package io.github.fh00126072001.revisiongraph.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
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
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
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
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

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
    private val generation = AtomicLong()
    private val detailGeneration = AtomicLong()
    private val cache = mutableMapOf<Path, Pair<GraphSnapshot, GraphLayout>>()
    private var roots = emptyList<Path>()
    private val rootBox = ComboBox<Path>()
    private val refresh = JButton("Refresh")
    private val graphSummary = JBLabel()
    private val zoomLabel = JBLabel("100%", SwingConstants.CENTER)
    private val status = JBLabel("Loading…", SwingConstants.CENTER)
    private val retry = JButton("Retry")
    private val canvas = RevisionGraphCanvas()
    private val details = DetailsPanel(::openDiff)
    private val cards = JPanel(CardLayout())
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentRoot: Path? = null
    private var graphIndicator: ProgressIndicator? = null
    private var detailIndicator: ProgressIndicator? = null
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
        val split = JBSplitter(true, .72f).apply {
            firstComponent = cards; secondComponent = details.component
            dividerWidth = 2
        }
        component = JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(split, BorderLayout.CENTER) }
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
        canvas.onSelection = { hash -> if (hash == null) clearDetails() else loadDetails(hash) }
        canvas.onContextMenu = ::showContextMenu
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
        val id = generation.incrementAndGet(); graphIndicator?.cancel(); detailIndicator?.cancel()
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
        canvas.activeHash()?.let(::loadDetails) ?: clearDetails()
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        status.text = "<html><div style='text-align:center'>${escape(text)}</div></html>"; retry.isVisible = canRetry
        (cards.layout as CardLayout).show(cards, "status")
    }

    private fun loadDetails(hash: String) {
        val root = currentRoot ?: return
        val id = detailGeneration.incrementAndGet(); detailIndicator?.cancel(); details.loading(hash)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Commit Details", true) {
            private var value: CommitDetails? = null; private var failure: String? = null
            override fun run(indicator: ProgressIndicator) {
                detailIndicator = indicator
                try { value = git.loadDetails(root, hash, indicator) } catch (e: Exception) { failure = e.message }
            }
            override fun onFinished() {
                if (detailGeneration.get() != id || project.isDisposed) return
                value?.let(details::show) ?: details.error(failure ?: "Details loading cancelled")
            }
        })
    }

    private fun clearDetails() {
        detailGeneration.incrementAndGet()
        detailIndicator?.cancel()
        details.clear()
    }

    private fun showContextMenu(selection: RevisionCompareSelection, point: Point) {
        val base = selection.base
        val group = DefaultActionGroup()
        val target = selection.target
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

    private fun compareWithWorkspace(revision: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareWithWorkspace(root, revision)) showComparisonUnavailable()
    }

    private fun compareRevisions(base: CompareRevision, target: CompareRevision) {
        val root = currentRoot
        if (root == null || !comparisons.compareRevisions(root, base, target)) showComparisonUnavailable()
    }

    private fun showComparisonUnavailable() {
        Messages.showWarningDialog(project, "The selected Git repository is no longer available. Refresh the revision graph and try again.", "Revision Graph")
    }

    private fun openDiff(change: FileChange, details: CommitDetails) {
        val root = currentRoot ?: return
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading Diff", true) {
            private var old: ByteArray? = null; private var new: ByteArray? = null
            override fun run(indicator: ProgressIndicator) {
                old = git.readBlob(root, details.parents.firstOrNull(), change.oldPath, indicator)
                new = git.readBlob(root, details.hash, change.newPath, indicator)
            }
            override fun onSuccess() {
                if (old?.any { it == 0.toByte() } == true || new?.any { it == 0.toByte() } == true) return this@RevisionGraphView.details.error("Binary content is not supported by the MVP text diff")
                val factory = DiffContentFactory.getInstance()
                val request = SimpleDiffRequest(change.newPath ?: change.oldPath ?: "Revision Graph Diff",
                    factory.create(project, old?.toString(Charsets.UTF_8).orEmpty()), factory.create(project, new?.toString(Charsets.UTF_8).orEmpty()),
                    "${details.parents.firstOrNull()?.take(8) ?: "Empty tree"}: ${change.oldPath.orEmpty()}", "${details.hash.take(8)}: ${change.newPath.orEmpty()}")
                DiffManager.getInstance().showDiff(project, request)
            }
        })
    }

    override fun dispose() { generation.incrementAndGet(); detailGeneration.incrementAndGet(); graphIndicator?.cancel(); detailIndicator?.cancel(); cache.clear() }
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

private class DetailsPanel(private val diff: (FileChange, CommitDetails) -> Unit) {
    private val title = JBLabel("Select a commit").apply { font = font.deriveFont(java.awt.Font.BOLD, 15f) }
    private val identity = JBLabel("Click a commit in the graph to inspect it")
    private val hash = JBLabel()
    private val parents = JBLabel()
    private val message = JBTextArea().apply {
        isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
        border = EmptyBorder(8, 2, 8, 8)
    }
    private val model = ChangeTableModel()
    private val table = JBTable(model).apply {
        setShowGrid(false); intercellSpacing = java.awt.Dimension(0, 0); rowHeight = 26
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(0).preferredWidth = 92; columnModel.getColumn(0).maxWidth = 110
        columnModel.getColumn(0).cellRenderer = ChangeStatusRenderer()
        columnModel.getColumn(1).cellRenderer = PathRenderer()
        emptyText.text = "No changed files"
    }
    private var current: CommitDetails? = null
    private val tabs = JBTabbedPane().apply {
        addTab("Details", JPanel(BorderLayout()).apply {
            border = EmptyBorder(10, 12, 8, 12)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(title); add(Box.createVerticalStrut(4)); add(identity); add(Box.createVerticalStrut(5)); add(hash); add(parents)
            }, BorderLayout.NORTH)
            add(JBScrollPane(message).apply { border = null }, BorderLayout.CENTER)
        })
        addTab("Changes", JBScrollPane(table))
    }
    val component = JPanel(BorderLayout()).apply {
        minimumSize = java.awt.Dimension(200, 130)
        add(tabs, BorderLayout.CENTER)
    }
    init { table.addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) {
        if (e.clickCount == 2) model.changeAt(table.rowAtPoint(e.point))?.let { change -> current?.let { diff(change, it) } }
    } }) }
    fun loading(hash: String) {
        title.text = "Loading commit…"; identity.text = hash.take(12); this.hash.text = ""; parents.text = ""; message.text = ""; model.set(emptyList())
    }
    fun clear() {
        current = null
        title.text = "Select a commit"
        identity.text = "Click a commit in the graph to inspect it"
        hash.text = ""
        parents.text = ""
        message.text = ""
        model.set(emptyList())
        tabs.setTitleAt(1, "Changes")
    }
    fun error(message: String) { title.text = "Unable to load commit"; identity.text = message; this.message.text = "" }
    fun show(value: CommitDetails) {
        current = value
        val date = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochSecond(value.epochSeconds).atZone(ZoneId.systemDefault()))
        title.text = value.subject
        identity.text = "${value.author} <${value.email}>  ·  $date"
        hash.text = "Commit  ${value.hash}"
        parents.text = "Parents  ${value.parents.joinToString("  ") { it.take(12) }.ifBlank { "Empty tree" }}"
        message.text = value.message.trim(); message.caretPosition = 0
        model.set(value.changes)
        tabs.setTitleAt(1, "Changes (${value.changes.size})")
    }
}

private class ChangeTableModel : AbstractTableModel() {
    private var changes = emptyList<FileChange>()
    fun set(value: List<FileChange>) { changes = value; fireTableDataChanged() }
    fun changeAt(row: Int) = changes.getOrNull(row)
    override fun getRowCount() = changes.size
    override fun getColumnCount() = 2
    override fun getColumnName(column: Int) = if (column == 0) "Status" else "Path — double-click to open diff"
    override fun getValueAt(row: Int, column: Int): Any = if (column == 0) changes[row].kind else changes[row].newPath ?: changes[row].oldPath.orEmpty()
}

private class ChangeStatusRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
        super.getTableCellRendererComponent(table, value, selected, focus, row, column)
        val kind = value as? ChangeKind ?: ChangeKind.UNKNOWN
        text = when (kind) {
            ChangeKind.ADDED -> "+  Added"; ChangeKind.MODIFIED -> "●  Modified"; ChangeKind.DELETED -> "−  Deleted"
            ChangeKind.RENAMED -> "→  Renamed"; ChangeKind.COPIED -> "⧉  Copied"; ChangeKind.TYPE_CHANGED -> "◆  Type"; ChangeKind.UNKNOWN -> "?  Unknown"
        }
        if (!selected) foreground = when (kind) {
            ChangeKind.ADDED -> JBColor(Color(0x2E7D32), Color(0x70C784)); ChangeKind.DELETED -> JBColor(Color(0xB33434), Color(0xE06C75))
            ChangeKind.RENAMED, ChangeKind.COPIED -> JBColor(Color(0x7B5AA6), Color(0xC39BE8)); else -> JBColor.foreground()
        }
        border = EmptyBorder(0, 10, 0, 4)
        return this
    }
}

private class PathRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, column: Int): java.awt.Component {
        super.getTableCellRendererComponent(table, value, selected, focus, row, column)
        border = EmptyBorder(0, 8, 0, 8); toolTipText = value?.toString()
        return this
    }
}

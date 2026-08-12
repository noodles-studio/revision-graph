package io.github.noodles_studio.revisiongraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.application.RevisionGraphDocument
import io.github.noodles_studio.revisiongraph.application.RevisionGraphLoader
import io.github.noodles_studio.revisiongraph.git.RevisionGraphFilter
import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.LoadResult
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import io.github.noodles_studio.revisiongraph.platform.RevisionCheckoutService
import io.github.noodles_studio.revisiongraph.platform.RevisionCompareService
import io.github.noodles_studio.revisiongraph.platform.RevisionLogService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener


internal class RevisionGraphView(private val project: Project) : Disposable {
    private val comparisons = RevisionCompareService(project)
    private val checkouts = RevisionCheckoutService(project)
    private val logs = RevisionLogService(project)
    private val generation = AtomicLong()
    private val cache = mutableMapOf<Path, RevisionGraphDocument>()
    private val revisionNamesByRoot = mutableMapOf<Path, List<String>>()
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
    private val toolbarStatus = JBLabel().apply { isVisible = false }
    private val locatorField = SearchTextField(false).apply {
        textEditor.emptyText.text = message("toolbar.locate.placeholder")
        preferredSize = JBUI.size(250, 26)
        minimumSize = JBUI.size(180, 26)
        toolTipText = message("toolbar.locate.tooltip")
    }
    private val status = JBLabel(message("status.loading"), SwingConstants.CENTER)
    private val retry = JButton(message("status.retry"))
    private val typography = GraphTypography.fromIdeaDefaults()
    private val loader = RevisionGraphLoader(project, typography)
    private val canvas = RevisionGraphCanvas(typography)
    private val emptyTitle = JBLabel("", SwingConstants.CENTER).apply {
        font = font.deriveFont(Font.BOLD, 15f)
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val emptyDescription = JBLabel("", SwingConstants.CENTER).apply {
        foreground = JBColor.GRAY
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val adjustFilterButton = JButton(message("empty.filter.adjust"))
    private val resetFilterButton = JButton(message("empty.filter.reset"))
    private val emptyActions = JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply {
        isOpaque = false
        alignmentX = Component.CENTER_ALIGNMENT
        add(adjustFilterButton)
        add(resetFilterButton)
    }
    private val emptyState = JPanel(GridBagLayout()).apply {
        background = canvas.background
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(emptyTitle)
            add(Box.createVerticalStrut(8))
            add(emptyDescription)
            add(Box.createVerticalStrut(16))
            add(emptyActions)
        })
    }
    private val cards = JPanel(CardLayout())
    private val watermark = object : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(message("watermark.powered.by")).apply {
                alignmentX = Component.RIGHT_ALIGNMENT
                foreground = JBColor(Color(0x8C949E), Color(0x6F737A))
                font = font.deriveFont(Font.PLAIN, (font.size2D - 1f).coerceAtLeast(10f))
            })
            add(Box.createVerticalStrut(JBUI.scale(1)))
            add(JBLabel(message("watermark.built.with.codex")).apply {
                alignmentX = Component.RIGHT_ALIGNMENT
                foreground = JBColor(Color(0xA5ABB3), Color(0x5E6268))
                font = font.deriveFont(Font.PLAIN, (font.size2D - 2f).coerceAtLeast(9f))
            })
        }

        override fun contains(x: Int, y: Int): Boolean = false
    }
    private val graphArea = object : JLayeredPane() {
        init {
            add(cards, DEFAULT_LAYER)
            add(watermark, PALETTE_LAYER)
        }

        override fun doLayout() {
            cards.setBounds(0, 0, width, height)
            val size = watermark.preferredSize
            watermark.setBounds(
                (width - size.width - JBUI.scale(12)).coerceAtLeast(0),
                (height - size.height - JBUI.scale(10)).coerceAtLeast(0),
                size.width,
                size.height,
            )
        }
    }
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var currentRoot: Path? = null
    private var publishedRoot: Path? = null
    private var publishedHead: HeadState? = null
    private var graphIndicator: ProgressIndicator? = null
    private var updatingRoots = false
    private var updatingZoom = false
    private var graphLoading = false
    private var locatorSnapshot: GraphSnapshot? = null
    private var locatorQuery = ""
    private var locatorMatches = emptyList<RevisionLocatorResult>()
    private var locatorMatchIndex = -1
    private var graphFilter = RevisionGraphFilter.NONE
    private var filterFocusPending = false
    private val refreshAction = object : DumbAwareAction(
        message("toolbar.refresh"),
        message("toolbar.refresh.tooltip"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(e: AnActionEvent) = refreshGraph()
        override fun update(e: AnActionEvent) { e.presentation.isEnabled = !graphLoading }
    }
    private val filterAction = object : ToggleAction(
        message("toolbar.filter"),
        message("toolbar.filter.tooltip"),
        AllIcons.General.Filter,
    ), DumbAware {
        override fun isSelected(e: AnActionEvent): Boolean = graphFilter.isActive
        override fun setSelected(e: AnActionEvent, state: Boolean) = showFilterDialog()
        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = !graphLoading && currentRoot != null
            e.presentation.description = message(if (graphFilter.isActive) "toolbar.filter.active.tooltip" else "toolbar.filter.tooltip")
        }
    }
    private val locateHeadAction = object : DumbAwareAction(
        message("toolbar.locate.head"),
        message("toolbar.locate.head.tooltip"),
        AllIcons.General.Locate,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            publishedHead?.hash?.let(canvas::focusRevision)
        }

        override fun update(e: AnActionEvent) {
            val available = !graphLoading && canvas.containsRevision(publishedHead?.hash)
            e.presentation.isEnabled = available
            e.presentation.description = message(if (available) "toolbar.locate.head.tooltip" else "toolbar.locate.head.unavailable")
        }
    }
    private val refreshToolbar: ActionToolbar

    val component: JComponent

    init {
        refreshToolbar = ActionManager.getInstance().createActionToolbar(
            ActionPlaces.TOOLBAR,
            DefaultActionGroup().apply {
                add(refreshAction)
                addSeparator()
                add(filterAction)
                add(locateHeadAction)
            },
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
                add(toolbarStatus)
            }, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.LEADING, 7, 0)).apply {
                isOpaque = false
                add(locatorField)
            }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.TRAILING, 4, 0)).apply {
                isOpaque = false
                add(toolbarButton("−", message("toolbar.zoom.out")) { canvas.zoomOut() })
                add(zoomBox)
                add(toolbarButton("+", message("toolbar.zoom.in")) { canvas.zoomIn() })
                add(toolbarButton("1:1", message("toolbar.zoom.actual")) { canvas.setZoomPercent(100.0) })
                add(fitButton())
            }, BorderLayout.EAST)
        }
        cards.add(canvas, "graph")
        cards.add(emptyState, "empty")
        cards.add(JPanel(BorderLayout()).apply { add(status, BorderLayout.CENTER); add(JPanel().apply { add(retry) }, BorderLayout.SOUTH) }, "status")
        component = JPanel(BorderLayout()).apply { add(toolbar, BorderLayout.NORTH); add(graphArea, BorderLayout.CENTER) }
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
                clearLocator()
                clearGraphFilter()
                currentRoot = selected
                load(false)
            }
        }
        zoomBox.addActionListener { if (!updatingZoom) applyZoomFromEditor() }
        (zoomBox.editor.editorComponent as? JTextField)?.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = applyZoomFromEditor()
        })
        locatorField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = resetLocatorSearch()
            override fun removeUpdate(e: DocumentEvent) = resetLocatorSearch()
            override fun changedUpdate(e: DocumentEvent) = resetLocatorSearch()
        })
        locatorField.addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> {
                        locateNextRevision(reverse = e.isShiftDown)
                        e.consume()
                    }
                    KeyEvent.VK_ESCAPE -> {
                        locatorField.text = ""
                        e.consume()
                    }
                }
            }
        })
        retry.addActionListener { refreshGraph() }
        adjustFilterButton.addActionListener { showFilterDialog() }
        resetFilterButton.addActionListener { applyGraphFilter(RevisionGraphFilter.NONE) }
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
            if (currentRoot != previous) {
                canvas.clearSelection()
                clearLocator()
                clearGraphFilter()
            }
            rootBox.selectedItem = currentRoot
            rootBox.isVisible = discovered.size > 1
        } finally { updatingRoots = false }
    }

    private fun refreshGraph() {
        refreshRoots()
        load(true)
    }

    private fun showFilterDialog() {
        val root = currentRoot
        val revisionNames = (root?.let(revisionNamesByRoot::get).orEmpty() + revisionFilterSuggestions(locatorSnapshot))
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
        val dialog = RevisionGraphFilterDialog(project, graphFilter, revisionNames)
        if (!dialog.showAndGet()) {
            refreshToolbar.updateActionsAsync()
            return
        }
        applyGraphFilter(dialog.selectedFilter())
    }

    private fun applyGraphFilter(selected: RevisionGraphFilter) {
        if (selected == graphFilter) {
            refreshToolbar.updateActionsAsync()
            return
        }
        graphFilter = selected
        filterFocusPending = true
        cache.clear()
        canvas.clearSelection()
        clearLocator()
        refreshToolbar.updateActionsAsync()
        load(true)
    }

    private fun clearGraphFilter() {
        if (graphFilter == RevisionGraphFilter.NONE) return
        graphFilter = RevisionGraphFilter.NONE
        filterFocusPending = false
        cache.clear()
        refreshToolbar.updateActionsAsync()
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

    private fun locateNextRevision(reverse: Boolean) {
        val query = locatorField.text.trim()
        if (query.isEmpty()) return
        val normalized = query.lowercase(Locale.ROOT)
        if (normalized != locatorQuery) {
            locatorQuery = normalized
            locatorMatches = findRevisionRefs(locatorSnapshot, query)
            locatorMatchIndex = -1
        }
        if (locatorMatches.isEmpty()) {
            setLocatorError(true, query)
            return
        }
        setLocatorError(false)
        locatorMatchIndex = cyclicLocatorIndex(locatorMatches.size, locatorMatchIndex, reverse)
        val result = locatorMatches[locatorMatchIndex]
        canvas.locateRevision(result.hash, result.revision)
        locatorField.addCurrentTextToHistory()
    }

    private fun resetLocatorSearch() {
        locatorQuery = ""
        locatorMatches = emptyList()
        locatorMatchIndex = -1
        setLocatorError(false)
    }

    private fun setLocatorError(error: Boolean, query: String = "") {
        locatorField.textEditor.putClientProperty("JComponent.outline", if (error) "error" else null)
        locatorField.toolTipText = if (error) message("toolbar.locate.no.matches", query) else message("toolbar.locate.tooltip")
        locatorField.textEditor.toolTipText = locatorField.toolTipText
        locatorField.repaint()
    }

    private fun clearLocator() {
        locatorSnapshot = null
        locatorField.text = ""
        resetLocatorSearch()
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
        val requestedFilter = graphFilter
        if (!force) cache[root]?.let { document -> publish(document.snapshot, document.layout); return }
        val keepGraphVisible = publishedRoot == root && cache[root] != null
        setGraphLoading(true)
        if (keepGraphVisible) setToolbarStatus(message("status.refreshing.graph"))
        else showStatus(message("status.loading.graph"), false)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, message("task.loading.graph"), true) {
            private var result: LoadResult<RevisionGraphDocument>? = null
            override fun run(indicator: ProgressIndicator) {
                graphIndicator = indicator
                result = loader.load(root, requestedFilter) { indicator.isCanceled }
            }
            override fun onFinished() {
                if (generation.get() != id || project.isDisposed) return
                setGraphLoading(false)
                when (val value = result) {
                    is LoadResult.Success -> {
                        cache[root] = value.value
                        publish(value.value.snapshot, value.value.layout)
                    }
                    is LoadResult.Empty -> showStatus(value.reason, true)
                    is LoadResult.Failure -> {
                        val details = "${value.summary}${value.details?.let { ": $it" }.orEmpty()}"
                        if (keepGraphVisible) setToolbarStatus(message("status.refresh.failed", details))
                        else showStatus(details, true)
                    }
                    else -> if (keepGraphVisible) setToolbarStatus(null) else showStatus(message("status.loading.cancelled"), true)
                }
            }
        })
    }

    private fun publish(snapshot: GraphSnapshot, layout: GraphLayout) {
        val root = currentRoot
        val focusHead = shouldFocusHead(publishedRoot, publishedHead, root, snapshot.head)
        val filterFocus = if (filterFocusPending) preferredFilterFocusHash(snapshot, graphFilter) else null
        filterFocusPending = false
        publishedRoot = root
        publishedHead = snapshot.head
        if (root != null) {
            val names = (revisionNamesByRoot[root].orEmpty() + revisionFilterSuggestions(snapshot))
                .distinct()
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
            revisionNamesByRoot[root] = names
        }
        locatorSnapshot = snapshot
        resetLocatorSearch()
        canvas.show(snapshot, layout, filterFocus ?: snapshot.head.hash?.takeIf { focusHead })
        refreshToolbar.updateActionsAsync()
        setToolbarStatus(null)
        if (snapshot.commits.isEmpty()) showEmptyState() else (cards.layout as CardLayout).show(cards, "graph")
    }

    private fun showEmptyState() {
        val filtered = graphFilter.isActive
        emptyTitle.text = message(if (filtered) "empty.filter.title" else "empty.repository.title")
        emptyDescription.text = "<html><div style='text-align:center'>${message(if (filtered) "empty.filter.description" else "empty.repository.description")}</div></html>"
        emptyActions.isVisible = filtered
        (cards.layout as CardLayout).show(cards, "empty")
    }

    private fun setToolbarStatus(text: String?) {
        toolbarStatus.text = text.orEmpty()
        toolbarStatus.isVisible = !text.isNullOrBlank()
    }

    private fun showStatus(text: String, canRetry: Boolean) {
        setToolbarStatus(null)
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

    override fun dispose() { generation.incrementAndGet(); graphIndicator?.cancel(); cache.clear(); revisionNamesByRoot.clear() }
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

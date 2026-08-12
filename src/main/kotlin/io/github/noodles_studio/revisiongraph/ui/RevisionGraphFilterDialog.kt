package io.github.noodles_studio.revisiongraph.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.TitledSeparator
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.git.RevisionGraphFilter
import io.github.noodles_studio.revisiongraph.git.parseRevisionList
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.RefKind
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent

internal class RevisionGraphFilterDialog(
    project: Project,
    initial: RevisionGraphFilter,
    initialVisibleRefKinds: Set<RefKind>,
    private val revisionNames: List<String>,
) : DialogWrapper(project) {
    private val fromField = TextFieldWithBrowseButton()
    private val toField = TextFieldWithBrowseButton()
    private val currentBranchBox = JBCheckBox(message("filter.current.branch"), initial.currentBranchOnly)
    private val localBranchesBox = JBCheckBox(message("filter.local.branches"), initial.localBranchesOnly)
    private val referenceBoxes = ReferenceCategory.entries.associateWith { category ->
        JBCheckBox(message(category.messageKey), category.kinds.all { it in initialVisibleRefKinds })
    }
    private var resetRequested = false

    init {
        title = message("filter.dialog.title")
        fromField.text = initial.excludedRevisions.joinToString(" ")
        toField.text = initial.includedRevisions.joinToString(" ")
        fromField.textField.toolTipText = message("filter.revisions.placeholder")
        toField.textField.toolTipText = message("filter.revisions.placeholder")
        fromField.addActionListener { showRevisionChooser(fromField) }
        toField.addActionListener { showRevisionChooser(toField) }
        currentBranchBox.addActionListener {
            if (currentBranchBox.isSelected) {
                localBranchesBox.isSelected = false
                toField.text = ""
            }
            updateRangeControls()
        }
        localBranchesBox.addActionListener {
            if (localBranchesBox.isSelected) {
                currentBranchBox.isSelected = false
                toField.text = ""
            }
            updateRangeControls()
        }
        updateRangeControls()
        init()
        setOKButtonText(message("filter.apply"))
    }

    fun selectedFilter(): RevisionGraphFilter {
        if (resetRequested) return RevisionGraphFilter.NONE
        val fixedScope = currentBranchBox.isSelected || localBranchesBox.isSelected
        return RevisionGraphFilter(
            excludedRevisions = parseRevisionList(fromField.text),
            includedRevisions = if (fixedScope) emptyList() else parseRevisionList(toField.text),
            currentBranchOnly = currentBranchBox.isSelected,
            localBranchesOnly = localBranchesBox.isSelected,
        )
    }

    fun selectedRefKinds(): Set<RefKind> {
        if (resetRequested) return ALL_REFERENCE_KINDS
        return referenceBoxes.asSequence()
            .filter { (_, box) -> box.isSelected }
            .flatMap { (category, _) -> category.kinds.asSequence() }
            .toSet()
    }

    override fun createCenterPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(message("filter.from"), fromField)
            .addLabeledComponent(message("filter.to"), toField)
            .addComponent(currentBranchBox)
            .addComponent(localBranchesBox)
            .addComponent(TitledSeparator(message("filter.references")))
        ReferenceCategory.entries.forEach { category -> form.addComponent(referenceBoxes.getValue(category)) }
        return form.panel.apply { preferredSize = JBUI.size(540, 245) }
    }

    override fun createLeftSideActions(): Array<Action> = arrayOf(object : DialogWrapperAction(message("filter.reset")) {
        override fun doAction(e: ActionEvent) {
            resetRequested = true
            close(OK_EXIT_CODE)
        }
    })

    private fun updateRangeControls() {
        val enabled = !currentBranchBox.isSelected && !localBranchesBox.isSelected
        currentBranchBox.isEnabled = !localBranchesBox.isSelected
        localBranchesBox.isEnabled = !currentBranchBox.isSelected
        toField.isEnabled = enabled
    }

    private fun showRevisionChooser(field: TextFieldWithBrowseButton) {
        if (revisionNames.isEmpty()) return
        JBPopupFactory.getInstance().createPopupChooserBuilder(revisionNames)
            .setTitle(message("filter.choose.revision"))
            .setItemChosenCallback { selected: String -> field.text = selected }
            .createPopup()
            .showUnderneathOf(field)
    }
}

internal val ALL_REFERENCE_KINDS: Set<RefKind> = RefKind.entries.toSet()

internal enum class ReferenceCategory(val messageKey: String, val kinds: Set<RefKind>) {
    LOCAL("references.local", setOf(RefKind.LOCAL_BRANCH)),
    REMOTE("references.remote", setOf(RefKind.REMOTE_BRANCH)),
    TAGS("references.tags", setOf(RefKind.TAG, RefKind.ANNOTATED_TAG)),
    SPECIAL("references.special", setOf(RefKind.STASH, RefKind.BISECT_GOOD, RefKind.BISECT_BAD, RefKind.BISECT_SKIP, RefKind.NOTES)),
    OTHER("references.other", setOf(RefKind.OTHER)),
}

internal fun revisionFilterSuggestions(snapshot: GraphSnapshot?): List<String> = buildList {
    add("HEAD")
    snapshot?.refsByCommit?.values.orEmpty().asSequence().flatten().forEach { ref ->
        add(ref.displayName)
        add(ref.fullName)
        add(ref.fullName.removePrefix("refs/"))
    }
}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

internal fun preferredFilterFocusHash(snapshot: GraphSnapshot, filter: RevisionGraphFilter): String? {
    filter.includedRevisions.forEach { revision ->
        if (revision in snapshot.commitsByHash) return revision
        snapshot.refsByCommit.forEach { (hash, refs) ->
            if (refs.any { ref ->
                    revision == ref.displayName || revision == ref.fullName || revision == ref.fullName.removePrefix("refs/")
                }) return hash
        }
    }
    return snapshot.head.hash?.takeIf { it in snapshot.commitsByHash } ?: snapshot.commits.firstOrNull()?.hash
}

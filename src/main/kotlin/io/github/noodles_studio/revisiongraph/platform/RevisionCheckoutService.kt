package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import git4idea.GitReference
import git4idea.GitTag
import git4idea.actions.ref.GitCheckoutAction
import git4idea.repo.GitRepositoryManager
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import java.nio.file.Path

internal class RevisionCheckoutService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)
    private val checkoutAction = GitCheckoutAction()

    fun checkout(root: Path, selected: RevisionRef, event: AnActionEvent): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        val reference: GitReference = (when (selected.kind) {
            RefKind.LOCAL_BRANCH -> repository.branches.findLocalBranch(selected.displayName)
            RefKind.REMOTE_BRANCH -> repository.branches.findRemoteBranch(selected.displayName)
            RefKind.TAG, RefKind.ANNOTATED_TAG -> GitTag(selected.displayName)
            else -> null
        }) ?: return false
        checkoutAction.actionPerformed(event, project, listOf(repository), reference)
        return true
    }

}

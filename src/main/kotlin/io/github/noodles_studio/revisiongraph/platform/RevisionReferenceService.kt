package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import git4idea.validators.GitRefNameValidator
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import java.nio.file.Path

/** Delegates reference mutations and validation to Git4Idea. */
internal class RevisionReferenceService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)

    fun createBranch(root: Path, selected: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        val name = requestName("dialog.create.branch.title", "dialog.create.branch.prompt", selected) ?: return true
        GitBrancher.getInstance(project).createBranch(name, mapOf(repository to selected.hash))
        return true
    }

    fun createTag(root: Path, selected: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
        val name = requestName("dialog.create.tag.title", "dialog.create.tag.prompt", selected) ?: return true
        GitBrancher.getInstance(project).createNewTag(name, selected.hash, listOf(repository), null)
        return true
    }

    private fun requestName(titleKey: String, promptKey: String, selected: CompareRevision): String? = Messages.showInputDialog(
        project,
        message(promptKey, selected.displayName),
        message(titleKey),
        null,
        "",
        GitRefNameValidator.getInstance(),
    )
}

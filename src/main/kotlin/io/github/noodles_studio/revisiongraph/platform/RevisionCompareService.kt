package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import git4idea.branch.GitBrancher
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import java.nio.file.Path

internal class RevisionCompareService(private val project: Project) {
    private val repositoryManager = GitRepositoryManager.getInstance(project)

    fun compareWithWorkspace(root: Path, selected: CompareRevision): Boolean {
        val repository = repositoryManager.findByRoot(root) ?: return false
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
        val repository = repositoryManager.findByRoot(root) ?: return false
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
        val repository = repositoryManager.findByRoot(root) ?: return false
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

}

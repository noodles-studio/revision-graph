package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

internal class RevisionRepositoryService(private val project: Project) {
    fun roots(): List<Path> {
        val repositoryRoots = GitRepositoryManager.getInstance(project).repositories.asSequence()
            .map { repository -> repository.root.toNioPath() }
        val mappedRoots = ProjectLevelVcsManager.getInstance(project).getAllVcsRoots().asSequence()
            .filter { root -> root.vcs?.name.equals("Git", ignoreCase = true) }
            .map { root -> root.path.toNioPath() }
        return (repositoryRoots + mappedRoots)
            .distinct()
            .sortedBy(Path::toString)
            .toList()
    }

    fun subscribe(parent: Disposable, repositoryChanged: (Path) -> Unit) {
        project.messageBus.connect(parent).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { repository ->
                repositoryChanged(repository.root.toNioPath())
            },
        )
    }
}

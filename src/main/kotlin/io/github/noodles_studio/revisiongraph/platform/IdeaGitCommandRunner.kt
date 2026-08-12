package io.github.noodles_studio.revisiongraph.platform

import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import io.github.noodles_studio.revisiongraph.git.GitCommandRunner
import io.github.noodles_studio.revisiongraph.git.ProcessGitCommandRunner
import java.io.InputStream
import java.nio.file.Path

internal class IdeaGitCommandRunner(project: Project) : GitCommandRunner {
    private val delegate = ProcessGitCommandRunner {
        GitExecutableManager.getInstance().getPathToGit(project)
    }

    override fun <T> run(
        root: Path,
        arguments: List<String>,
        cancelled: () -> Boolean,
        readOutput: (InputStream) -> T,
    ): T = delegate.run(root, arguments, cancelled, readOutput)
}

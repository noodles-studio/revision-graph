package io.github.noodles_studio.revisiongraph.platform

import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.nio.file.Path

internal fun GitRepositoryManager.findByRoot(root: Path): GitRepository? {
    val expected = root.toAbsolutePath().normalize()
    return repositories.firstOrNull { repository ->
        repository.root.toNioPath().toAbsolutePath().normalize() == expected
    }
}

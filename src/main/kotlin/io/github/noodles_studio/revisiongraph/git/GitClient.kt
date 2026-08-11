package io.github.noodles_studio.revisiongraph.git

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import git4idea.config.GitExecutableManager
import io.github.noodles_studio.revisiongraph.RevisionGraphBundle.message
import io.github.noodles_studio.revisiongraph.model.*
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.Executors

class GitClient(private val project: Project) {
    fun loadGraph(root: Path, indicator: ProgressIndicator): LoadResult<GraphSnapshot> = try {
        val historyBytes = run(root, indicator, "log", "--all", "--parents", "--simplify-by-decoration",
            "--topo-order", "--format=%H%x00%P%x00%at%x00%an%x00%ae%x00%s%x00%b", "-z")
        if (historyBytes.isEmpty()) return LoadResult.Empty(message("git.repository.empty"))
        val parsedCommits = GitParsers.history(ByteArrayInputStream(historyBytes)) { indicator.isCanceled }
        val loadedHashes = parsedCommits.mapTo(hashSetOf()) { it.hash }
        val boundaryParents = parsedCommits.asSequence().flatMap { it.parents.asSequence() }.filter { it !in loadedHashes }.distinct()
            .map { CommitNode(it, emptyList(), 0, "") }.toList()
        val commits = parsedCommits + boundaryParents
        val refBytes = run(root, indicator, "for-each-ref", "--format=%(refname)%00%(objectname)%00%(*objectname)%00")
        val valid = commits.mapTo(hashSetOf()) { it.hash }
        val refs = GitParsers.refs(ByteArrayInputStream(refBytes)).filter { it.target in valid }
            .groupBy { it.target }.mapValues { (_, values) -> values.sortedBy { it.fullName } }
        val headHash = runText(root, indicator, true, "rev-parse", "--verify", "HEAD")?.trim()?.ifBlank { null }
        val branch = runText(root, indicator, true, "symbolic-ref", "--quiet", "--short", "HEAD")?.trim()?.ifBlank { null }
        LoadResult.Success(GraphSnapshot(commits, refs, HeadState(headHash, branch, headHash != null && branch == null)))
    } catch (_: InterruptedException) {
        LoadResult.Cancelled
    } catch (e: GitCommandException) {
        LoadResult.Failure(message("git.command.failed"), e.message)
    } catch (e: Exception) {
        LoadResult.Failure(message("git.history.parse.failed"), e.message)
    }

    private fun runText(root: Path, indicator: ProgressIndicator, allowFailure: Boolean, vararg args: String): String? =
        try { run(root, indicator, *args).toString(Charsets.UTF_8) } catch (e: GitCommandException) { if (allowFailure) null else throw e }

    private fun run(root: Path, indicator: ProgressIndicator, vararg args: String): ByteArray {
        indicator.checkCanceled()
        val git = GitExecutableManager.getInstance().getPathToGit(project)
        val process = ProcessBuilder(listOf(git) + args).directory(root.toFile()).start()
        val stderrPool = Executors.newSingleThreadExecutor()
        val stderr = stderrPool.submit<String> { process.errorStream.bufferedReader().use { it.readText() } }
        val output = try {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                if (indicator.isCanceled) { process.destroyForcibly(); throw InterruptedException() }
                val count = process.inputStream.read(chunk)
                if (count < 0) break
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        } finally { stderrPool.shutdown() }
        val exit = process.waitFor()
        val error = stderr.get()
        if (exit != 0) throw GitCommandException("git ${args.firstOrNull().orEmpty()} exited $exit: ${error.trim()}")
        return output
    }
}

class GitCommandException(message: String) : RuntimeException(message)

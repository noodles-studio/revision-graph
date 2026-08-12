package io.github.noodles_studio.revisiongraph.git

import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.LoadResult
import java.nio.file.Path

internal data class GitClientText(
    val repositoryEmpty: String,
    val filterEmpty: String,
    val commandFailed: String,
    val historyParseFailed: String,
    val outputTooLarge: String,
)

internal class GitClient(
    private val runner: GitCommandRunner,
    private val text: GitClientText,
) {
    internal fun loadGraph(
        root: Path,
        filter: RevisionGraphFilter = RevisionGraphFilter.NONE,
        cancelled: () -> Boolean = { false },
    ): LoadResult<GraphSnapshot> = try {
        if (filter.revisionsToValidate.any { revision ->
                runText(root, cancelled, true, "rev-parse", "--verify", "--end-of-options", "$revision^{commit}") == null
            }) {
            return LoadResult.Empty(text.filterEmpty)
        }
        val historyArguments = buildList {
            add("log")
            addAll(filter.revisionArguments())
            addAll(listOf(
                "--parents",
                "--simplify-by-decoration",
                "--topo-order",
                "--format=%H%x00%P%x00%at%x00%an%x00%ae%x00%s%x00%b",
                "-z",
            ))
        }
        val parsedCommits = run(root, cancelled, historyArguments) { input ->
            GitParsers.history(input, cancelled)
        }
        if (parsedCommits.isEmpty()) {
            return LoadResult.Empty(if (filter.isActive) text.filterEmpty else text.repositoryEmpty)
        }
        val loadedHashes = parsedCommits.mapTo(hashSetOf()) { it.hash }
        val boundaryParents = parsedCommits.asSequence().flatMap { it.parents.asSequence() }.filter { it !in loadedHashes }.distinct()
            .map { CommitNode(it, emptyList(), 0, "") }.toList()
        val commits = parsedCommits + boundaryParents
        val refsFromGit = run(
            root,
            cancelled,
            listOf(
                "for-each-ref",
                "--format=%(refname)%00%(objectname)%00%(*objectname)%00%(upstream:short)%00%(upstream:track,nobracket)%00",
            ),
        ) { input -> GitParsers.refs(input, cancelled) }
        val valid = commits.mapTo(hashSetOf()) { it.hash }
        val refs = refsFromGit.filter { it.target in valid }
            .groupBy { it.target }.mapValues { (_, values) -> values.sortedBy { it.fullName } }
        val headHash = runText(root, cancelled, true, "rev-parse", "--verify", "HEAD")?.trim()?.ifBlank { null }
        val branch = runText(root, cancelled, true, "symbolic-ref", "--quiet", "--short", "HEAD")?.trim()?.ifBlank { null }
        LoadResult.Success(GraphSnapshot(commits, refs, HeadState(headHash, branch, headHash != null && branch == null)))
    } catch (_: InterruptedException) {
        LoadResult.Cancelled
    } catch (e: GitOutputLimitException) {
        LoadResult.Failure(text.outputTooLarge, e.message)
    } catch (e: GitCommandException) {
        LoadResult.Failure(text.commandFailed, e.message)
    } catch (e: Exception) {
        LoadResult.Failure(text.historyParseFailed, e.message)
    }

    private fun runText(root: Path, cancelled: () -> Boolean, allowFailure: Boolean, vararg args: String): String? =
        try {
            run(root, cancelled, args.toList()) { input -> input.readLimitedText(MAX_TEXT_BYTES) }
        } catch (e: GitCommandException) {
            if (allowFailure) null else throw e
        }

    private fun <T> run(
        root: Path,
        cancelled: () -> Boolean,
        arguments: List<String>,
        readOutput: (java.io.InputStream) -> T,
    ): T = runner.run(root, arguments, cancelled, readOutput)

    private companion object {
        const val MAX_TEXT_BYTES = 64 * 1024
    }
}

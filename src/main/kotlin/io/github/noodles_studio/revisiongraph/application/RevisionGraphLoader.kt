package io.github.noodles_studio.revisiongraph.application

import com.intellij.openapi.project.Project
import io.github.noodles_studio.revisiongraph.git.GitClient
import io.github.noodles_studio.revisiongraph.git.RevisionGraphFilter
import io.github.noodles_studio.revisiongraph.layout.GraphLayout
import io.github.noodles_studio.revisiongraph.layout.GraphTextMetrics
import io.github.noodles_studio.revisiongraph.layout.LayeredDagLayoutEngine
import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.LoadResult
import java.nio.file.Path

internal data class RevisionGraphDocument(
    val snapshot: GraphSnapshot,
    val layout: GraphLayout,
)

internal class RevisionGraphLoader(
    project: Project,
    textMetrics: GraphTextMetrics,
) {
    private val gitClient = GitClient(project)
    private val layoutEngine = LayeredDagLayoutEngine(textMetrics = textMetrics)

    fun load(
        root: Path,
        filter: RevisionGraphFilter,
        cancelled: () -> Boolean,
    ): LoadResult<RevisionGraphDocument> = when (val loaded = gitClient.loadGraph(root, filter, cancelled)) {
        is LoadResult.Success -> layout(loaded.value, cancelled)
        is LoadResult.Empty -> layout(EMPTY_SNAPSHOT, cancelled)
        is LoadResult.Failure -> loaded
        LoadResult.Cancelled -> LoadResult.Cancelled
    }

    private fun layout(
        snapshot: GraphSnapshot,
        cancelled: () -> Boolean,
    ): LoadResult<RevisionGraphDocument> = try {
        LoadResult.Success(RevisionGraphDocument(snapshot, layoutEngine.layout(snapshot, cancelled)))
    } catch (_: InterruptedException) {
        LoadResult.Cancelled
    }

    private companion object {
        val EMPTY_SNAPSHOT = GraphSnapshot(emptyList(), emptyMap(), HeadState(null, null, false))
    }
}

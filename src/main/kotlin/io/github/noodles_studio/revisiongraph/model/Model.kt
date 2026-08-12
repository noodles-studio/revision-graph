package io.github.noodles_studio.revisiongraph.model

data class CommitNode(
    val hash: String,
    val parents: List<String>,
    val epochSeconds: Long,
    val subject: String,
    val author: String = "",
    val email: String = "",
    val body: String = "",
)

enum class RefKind { LOCAL_BRANCH, REMOTE_BRANCH, TAG, ANNOTATED_TAG, STASH, BISECT_GOOD, BISECT_BAD, BISECT_SKIP, NOTES, OTHER }

data class RevisionRef(
    val fullName: String,
    val target: String,
    val kind: RefKind,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
) {
    val displayName: String = when (kind) {
        RefKind.LOCAL_BRANCH -> fullName.removePrefix("refs/heads/")
        RefKind.REMOTE_BRANCH -> fullName.removePrefix("refs/remotes/")
        RefKind.TAG, RefKind.ANNOTATED_TAG -> fullName.removePrefix("refs/tags/").removeSuffix("^{}")
        RefKind.STASH -> "stash"
        RefKind.BISECT_GOOD -> "good"
        RefKind.BISECT_BAD -> "bad"
        RefKind.BISECT_SKIP -> "skip"
        RefKind.NOTES -> fullName.removePrefix("refs/notes/")
        RefKind.OTHER -> fullName.removePrefix("refs/")
    }

    val graphLabel: String = buildString {
        append(displayName)
        upstream?.let {
            if (!sameBranchName(displayName, it)) append(" ↔ ").append(it)
            if (ahead > 0) append(" ↑").append(ahead)
            if (behind > 0) append(" ↓").append(behind)
        }
    }

    private fun sameBranchName(local: String, tracked: String): Boolean =
        tracked == local || tracked.substringAfter('/', tracked) == local
}

data class EdgeKey(val child: String, val parent: String)

data class HeadState(val hash: String?, val branch: String?, val detached: Boolean)

data class GraphSnapshot(
    val commits: List<CommitNode>,
    val refsByCommit: Map<String, List<RevisionRef>>,
    val head: HeadState,
) {
    val commitsByHash = commits.associateBy { it.hash }
}

sealed interface LoadResult<out T> {
    data class Success<T>(val value: T) : LoadResult<T>
    data class Empty(val reason: String) : LoadResult<Nothing>
    data class Failure(val summary: String, val details: String? = null) : LoadResult<Nothing>
    data object Cancelled : LoadResult<Nothing>
}

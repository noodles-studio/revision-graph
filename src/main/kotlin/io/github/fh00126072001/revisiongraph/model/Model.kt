package io.github.fh00126072001.revisiongraph.model

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

data class RevisionRef(val fullName: String, val target: String, val kind: RefKind) {
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
}

data class HeadState(val hash: String?, val branch: String?, val detached: Boolean)

data class GraphSnapshot(
    val commits: List<CommitNode>,
    val refsByCommit: Map<String, List<RevisionRef>>,
    val head: HeadState,
) {
    val commitsByHash = commits.associateBy { it.hash }
}

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED, UNKNOWN }

data class FileChange(
    val kind: ChangeKind,
    val oldPath: String?,
    val newPath: String?,
)

data class CommitDetails(
    val hash: String,
    val parents: List<String>,
    val author: String,
    val email: String,
    val epochSeconds: Long,
    val subject: String,
    val message: String,
    val changes: List<FileChange>,
)

sealed interface LoadResult<out T> {
    data class Success<T>(val value: T) : LoadResult<T>
    data class Empty(val reason: String) : LoadResult<Nothing>
    data class Failure(val summary: String, val details: String? = null) : LoadResult<Nothing>
    data object Cancelled : LoadResult<Nothing>
}

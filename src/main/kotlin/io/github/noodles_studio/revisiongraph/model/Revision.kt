package io.github.noodles_studio.revisiongraph.model

internal data class CompareRevision(
    val hash: String,
    val revision: String,
) {
    val displayName: String = if (revision == hash) hash.take(8) else revision
}

internal sealed interface RevisionLogTarget {
    data class Reference(val name: String) : RevisionLogTarget
    data class Commit(val hash: String) : RevisionLogTarget
}

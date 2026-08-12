package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.RefKind
import java.util.Locale

internal data class RevisionLocatorResult(
    val displayName: String,
    val fullName: String,
    val hash: String,
    val revision: String,
    val kind: RefKind,
    val current: Boolean,
)

internal fun findRevisionRefs(snapshot: GraphSnapshot?, rawQuery: String): List<RevisionLocatorResult> {
    val model = snapshot ?: return emptyList()
    val query = rawQuery.trim().lowercase(Locale.ROOT)
    if (query.isEmpty()) return emptyList()
    return model.refsByCommit.values.asSequence().flatten().map { ref ->
        RevisionLocatorResult(
            ref.displayName,
            ref.fullName,
            ref.target,
            compareRevisionName(ref) ?: ref.target,
            ref.kind,
            model.head.hash == ref.target && model.head.branch == ref.displayName,
        )
    }.filter { result ->
        result.displayName.lowercase(Locale.ROOT).contains(query) ||
            result.fullName.lowercase(Locale.ROOT).contains(query)
    }.sortedWith(
        compareBy<RevisionLocatorResult> { locatorExactRank(it, query) }
            .thenBy { if (it.current) 0 else 1 }
            .thenBy { locatorKindRank(it.kind) }
            .thenBy { locatorMatchRank(it, query) }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy { it.fullName },
    ).toList()
}
internal fun cyclicLocatorIndex(size: Int, current: Int, reverse: Boolean): Int {
    require(size > 0)
    return if (reverse) {
        if (current <= 0) size - 1 else current - 1
    } else {
        if (current < 0 || current >= size - 1) 0 else current + 1
    }
}

private fun locatorExactRank(result: RevisionLocatorResult, query: String): Int =
    if (result.displayName.equals(query, ignoreCase = true) || result.fullName.equals(query, ignoreCase = true)) 0 else 1

private fun locatorMatchRank(result: RevisionLocatorResult, query: String): Int = when {
    result.displayName.lowercase(Locale.ROOT).startsWith(query) -> 0
    result.fullName.lowercase(Locale.ROOT).startsWith(query) -> 1
    else -> 2
}

private fun locatorKindRank(kind: RefKind): Int = when (kind) {
    RefKind.LOCAL_BRANCH -> 0
    RefKind.REMOTE_BRANCH -> 1
    RefKind.TAG, RefKind.ANNOTATED_TAG -> 2
    else -> 3
}

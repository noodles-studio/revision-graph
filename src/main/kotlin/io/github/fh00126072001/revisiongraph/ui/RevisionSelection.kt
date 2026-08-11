package io.github.fh00126072001.revisiongraph.ui

import io.github.fh00126072001.revisiongraph.model.GraphSnapshot
import io.github.fh00126072001.revisiongraph.model.RefKind
import io.github.fh00126072001.revisiongraph.model.RevisionRef

internal data class RevisionSelection(
    val baseHash: String? = null,
    val targetHash: String? = null,
    val activeHash: String? = null,
) {
    fun click(hash: String?, additive: Boolean): RevisionSelection {
        if (hash == null) return if (additive) this else EMPTY
        if (!additive) return if (baseHash == hash) EMPTY else RevisionSelection(hash, activeHash = hash)

        return when (hash) {
            baseHash -> if (targetHash == null) EMPTY else RevisionSelection(targetHash, activeHash = targetHash)
            targetHash -> RevisionSelection(baseHash, activeHash = baseHash)
            else -> if (baseHash == null) RevisionSelection(hash, activeHash = hash) else copy(targetHash = hash, activeHash = hash)
        }
    }

    fun contextClick(hash: String): RevisionSelection = when (hash) {
        baseHash, targetHash -> copy(activeHash = hash)
        else -> RevisionSelection(hash, activeHash = hash)
    }

    fun retain(validHashes: Set<String>): RevisionSelection {
        val validBase = baseHash?.takeIf { it in validHashes }
        val validTarget = targetHash?.takeIf { it in validHashes && it != validBase }
        if (validBase == null) return validTarget?.let { RevisionSelection(it, activeHash = it) } ?: EMPTY
        val validActive = activeHash?.takeIf { it == validBase || it == validTarget } ?: validTarget ?: validBase
        return RevisionSelection(validBase, validTarget, validActive)
    }

    companion object {
        val EMPTY = RevisionSelection()
    }
}

internal data class CompareRevision(val hash: String, val revision: String) {
    val displayName: String = if (revision == hash) hash.take(8) else revision
}

internal data class RevisionCompareSelection(
    val base: CompareRevision,
    val target: CompareRevision? = null,
    val active: CompareRevision = target ?: base,
)

internal sealed interface RevisionLogTarget {
    data class Reference(val name: String) : RevisionLogTarget
    data class Commit(val hash: String) : RevisionLogTarget
}

internal fun revisionLogTarget(selected: CompareRevision, resolvedRevisionHash: String?): RevisionLogTarget =
    if (selected.revision != selected.hash && resolvedRevisionHash.equals(selected.hash, ignoreCase = true)) {
        RevisionLogTarget.Reference(selected.revision)
    } else {
        RevisionLogTarget.Commit(selected.hash)
    }

internal fun preferredCompareRevision(snapshot: GraphSnapshot, hash: String): CompareRevision {
    val revision = snapshot.refsByCommit[hash].orEmpty()
        .mapNotNull { ref -> compareRevisionName(ref)?.let { Triple(refPriority(snapshot, ref), ref.fullName, it) } }
        .minWithOrNull(compareBy<Triple<Int, String, String>> { it.first }.thenBy { it.second })
        ?.third
    return CompareRevision(hash, revision ?: hash)
}

internal fun compareRevisionName(ref: RevisionRef): String? = when (ref.kind) {
    RefKind.LOCAL_BRANCH, RefKind.REMOTE_BRANCH, RefKind.TAG, RefKind.ANNOTATED_TAG -> ref.displayName
    else -> null
}

private fun refPriority(snapshot: GraphSnapshot, ref: RevisionRef): Int = when {
    ref.kind == RefKind.LOCAL_BRANCH && ref.displayName == snapshot.head.branch -> 0
    ref.kind == RefKind.LOCAL_BRANCH -> 1
    ref.kind == RefKind.REMOTE_BRANCH -> 2
    ref.kind == RefKind.TAG || ref.kind == RefKind.ANNOTATED_TAG -> 3
    else -> 4
}

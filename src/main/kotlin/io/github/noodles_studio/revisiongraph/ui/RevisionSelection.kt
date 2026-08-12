package io.github.noodles_studio.revisiongraph.ui

import io.github.noodles_studio.revisiongraph.model.GraphSnapshot
import io.github.noodles_studio.revisiongraph.model.HeadState
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import io.github.noodles_studio.revisiongraph.model.CompareRevision
import io.github.noodles_studio.revisiongraph.model.RevisionLogTarget

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

internal data class RevisionCompareSelection(
    val base: CompareRevision,
    val target: CompareRevision? = null,
    val active: CompareRevision = target ?: base,
    val activeRef: RevisionRef? = null,
    val activeRefs: List<RevisionRef> = emptyList(),
    val head: HeadState? = null,
)

internal fun copyableRevisionText(refs: List<RevisionRef>, hash: String): String = refs
    .map(RevisionRef::displayName)
    .distinct()
    .joinToString("\n")
    .ifEmpty { hash }

internal fun headDisplayName(head: HeadState?): String = head?.branch
    ?: head?.hash?.take(8)?.let { "HEAD · $it" }
    ?: "HEAD"

internal fun checkoutAvailable(ref: RevisionRef, head: HeadState?): Boolean = when (ref.kind) {
    RefKind.LOCAL_BRANCH -> ref.displayName != head?.branch
    RefKind.REMOTE_BRANCH, RefKind.TAG, RefKind.ANNOTATED_TAG -> true
    else -> false
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

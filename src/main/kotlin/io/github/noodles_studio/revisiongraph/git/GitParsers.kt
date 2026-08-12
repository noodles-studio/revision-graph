package io.github.noodles_studio.revisiongraph.git

import io.github.noodles_studio.revisiongraph.model.CommitNode
import io.github.noodles_studio.revisiongraph.model.RefKind
import io.github.noodles_studio.revisiongraph.model.RevisionRef
import java.io.InputStream

object GitParsers {
    fun history(input: InputStream, cancelled: () -> Boolean = { false }): List<CommitNode> = buildList {
        NulRecordParser(7).parse(input, cancelled) { fields ->
            val hash = fields[0]
            require(hash.matches(Regex("[0-9a-fA-F]{40,64}"))) { "Invalid commit hash: $hash" }
            add(CommitNode(hash, fields[1].split(' ').filter(String::isNotBlank), fields[2].toLong(), fields[5], fields[3], fields[4], fields[6]))
        }
    }

    fun refs(input: InputStream, cancelled: () -> Boolean = { false }): List<RevisionRef> = buildList {
        var totalBytes = 0L
        input.bufferedReader().forEachLine { line ->
            if (cancelled()) throw InterruptedException("Git output parsing cancelled")
            totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            if (totalBytes > MAX_REF_BYTES) {
                throw GitOutputLimitException("Git reference output exceeded $MAX_REF_BYTES bytes")
            }
            val parts = line.split('\u0000')
            if (parts.size < 3) return@forEachLine
            val name = parts[0]
            val direct = parts[1]
            val peeled = parts[2]
            val upstream = parts.getOrNull(3)?.ifBlank { null }
            val tracking = parts.getOrNull(4).orEmpty()
            val target = peeled.ifBlank { direct }
            if (target.isNotBlank()) {
                val (ahead, behind) = parseTracking(tracking)
                add(RevisionRef(name, target, refKind(name, peeled.isNotBlank()), upstream, ahead, behind))
            }
        }
    }

    private fun refKind(name: String, peeled: Boolean) = when {
        name.startsWith("refs/heads/") -> RefKind.LOCAL_BRANCH
        name.startsWith("refs/remotes/") -> RefKind.REMOTE_BRANCH
        name.startsWith("refs/tags/") -> if (peeled) RefKind.ANNOTATED_TAG else RefKind.TAG
        name == "refs/stash" -> RefKind.STASH
        name.startsWith("refs/bisect/good") -> RefKind.BISECT_GOOD
        name.startsWith("refs/bisect/bad") -> RefKind.BISECT_BAD
        name.startsWith("refs/bisect/skip") -> RefKind.BISECT_SKIP
        name.startsWith("refs/notes/") -> RefKind.NOTES
        else -> RefKind.OTHER
    }

    private fun parseTracking(value: String): Pair<Int, Int> {
        var ahead = 0
        var behind = 0
        AHEAD_PATTERN.find(value)?.groupValues?.get(1)?.toIntOrNull()?.let { ahead = it }
        BEHIND_PATTERN.find(value)?.groupValues?.get(1)?.toIntOrNull()?.let { behind = it }
        return ahead to behind
    }

    private val AHEAD_PATTERN = Regex("ahead (\\d+)")
    private val BEHIND_PATTERN = Regex("behind (\\d+)")
    private const val MAX_REF_BYTES = 16L * 1024L * 1024L
}

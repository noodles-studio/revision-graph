package io.github.fh00126072001.revisiongraph.git

import io.github.fh00126072001.revisiongraph.model.*
import java.io.InputStream

object GitParsers {
    fun history(input: InputStream, cancelled: () -> Boolean = { false }): List<CommitNode> = buildList {
        NulRecordParser(7).parse(input, cancelled) { fields ->
            val hash = fields[0]
            require(hash.matches(Regex("[0-9a-fA-F]{40,64}"))) { "Invalid commit hash: $hash" }
            add(CommitNode(hash, fields[1].split(' ').filter(String::isNotBlank), fields[2].toLong(), fields[5], fields[3], fields[4], fields[6]))
        }
    }

    fun refs(input: InputStream): List<RevisionRef> = buildList {
        input.bufferedReader().forEachLine { line ->
            val parts = line.split('\u0000')
            if (parts.size < 3) return@forEachLine
            val (name, direct, peeled) = parts
            val target = peeled.ifBlank { direct }
            if (target.isNotBlank()) add(RevisionRef(name, target, refKind(name, peeled.isNotBlank())))
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

    fun nameStatus(input: InputStream): List<FileChange> {
        val fields = mutableListOf<String>()
        NulRecordParser(1).parse(input) { fields += it.single() }
        val changes = mutableListOf<FileChange>()
        var index = 0
        while (index < fields.size) {
            val status = fields[index++]
            if (status.isBlank()) continue
            val code = status.first()
            val first = fields.getOrNull(index++) ?: error("Missing path after $status")
            if (code == 'R' || code == 'C') {
                val second = fields.getOrNull(index++) ?: error("Missing destination after $status")
                changes += FileChange(if (code == 'R') ChangeKind.RENAMED else ChangeKind.COPIED, first, second)
            } else changes += FileChange(kind(code), if (code == 'A') null else first, if (code == 'D') null else first)
        }
        return changes
    }

    private fun kind(code: Char) = when (code) {
        'A' -> ChangeKind.ADDED; 'M' -> ChangeKind.MODIFIED; 'D' -> ChangeKind.DELETED
        'T' -> ChangeKind.TYPE_CHANGED; else -> ChangeKind.UNKNOWN
    }
}

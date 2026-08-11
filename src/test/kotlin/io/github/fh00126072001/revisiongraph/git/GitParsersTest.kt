package io.github.fh00126072001.revisiongraph.git

import io.github.fh00126072001.revisiongraph.model.ChangeKind
import io.github.fh00126072001.revisiongraph.model.RefKind
import java.io.ByteArrayInputStream
import kotlin.test.*

class GitParsersTest {
    private fun bytes(vararg fields: String) = fields.joinToString("\u0000", postfix = "\u0000").toByteArray()

    @Test fun `history preserves parents unicode and newlines`() {
        val a = "a".repeat(40); val b = "b".repeat(40); val c = "c".repeat(40)
        val result = GitParsers.history(ByteArrayInputStream(bytes(
            a, "$b $c", "123", "Alice", "alice@example.com", "主题\nline", "正文",
            b, "", "100", "Bob", "bob@example.com", "root", "",
        )))
        assertEquals(listOf(b, c), result[0].parents)
        assertEquals("主题\nline", result[0].subject)
        assertEquals("Alice", result[0].author)
        assertEquals("正文", result[0].body)
        assertTrue(result[1].parents.isEmpty())
    }

    @Test fun `truncated history fails atomically`() {
        val broken = ("a".repeat(40) + "\u0000\u0000").toByteArray()
        assertFailsWith<IllegalArgumentException> { GitParsers.history(ByteArrayInputStream(broken)) }
    }

    @Test fun `annotated tag uses peeled commit`() {
        val commit = "c".repeat(40); val tagObject = "d".repeat(40)
        val input = "refs/tags/v1\u0000$tagObject\u0000$commit\u0000\nrefs/heads/main\u0000$commit\u0000\u0000\n"
        val refs = GitParsers.refs(ByteArrayInputStream(input.toByteArray()))
        assertEquals(commit, refs[0].target); assertEquals(RefKind.ANNOTATED_TAG, refs[0].kind)
        assertEquals(RefKind.LOCAL_BRANCH, refs[1].kind)
    }

    @Test fun `original revision graph ref kinds use friendly names`() {
        val hash = "e".repeat(40)
        val input = listOf(
            "refs/stash\u0000$hash\u0000\u0000",
            "refs/bisect/good-1\u0000$hash\u0000\u0000",
            "refs/bisect/bad\u0000$hash\u0000\u0000",
            "refs/bisect/skip-2\u0000$hash\u0000\u0000",
            "refs/notes/commits\u0000$hash\u0000\u0000",
        ).joinToString("\n", postfix = "\n")
        val refs = GitParsers.refs(ByteArrayInputStream(input.toByteArray()))
        assertEquals(listOf(RefKind.STASH, RefKind.BISECT_GOOD, RefKind.BISECT_BAD, RefKind.BISECT_SKIP, RefKind.NOTES), refs.map { it.kind })
        assertEquals(listOf("stash", "good", "bad", "skip", "commits"), refs.map { it.displayName })
    }

    @Test fun `name status parses rename and deletion`() {
        val changes = GitParsers.nameStatus(ByteArrayInputStream(bytes("R100", "old name", "新 name", "D", "gone")))
        assertEquals(ChangeKind.RENAMED, changes[0].kind); assertEquals("新 name", changes[0].newPath)
        assertEquals(ChangeKind.DELETED, changes[1].kind); assertNull(changes[1].newPath)
    }
}

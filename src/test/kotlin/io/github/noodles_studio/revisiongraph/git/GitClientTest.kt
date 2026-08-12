package io.github.noodles_studio.revisiongraph.git

import io.github.noodles_studio.revisiongraph.model.LoadResult
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GitClientTest {
    @Test
    fun `client streams history refs and head through injected runner`() {
        val commit = "a".repeat(40)
        val runner = QueueGitCommandRunner(
            response(historyRecord(commit, "main subject")),
            response("refs/heads/main\u0000$commit\u0000\u0000\n"),
            response("$commit\n"),
            response("main\n"),
        )

        val result = GitClient(runner, TEST_TEXT).loadGraph(Path.of("/repository"))

        val loaded = assertIs<LoadResult.Success<*>>(result).value
        val snapshot = assertIs<io.github.noodles_studio.revisiongraph.model.GraphSnapshot>(loaded)
        assertEquals("main subject", snapshot.commits.single().subject)
        assertEquals("main", snapshot.head.branch)
        assertEquals("main", snapshot.refsByCommit.getValue(commit).single().displayName)
        assertEquals(
            listOf("log", "for-each-ref", "rev-parse", "symbolic-ref"),
            runner.commands.map { arguments -> arguments.first() },
        )
    }

    @Test
    fun `client reports cancellation without running another command`() {
        val runner = QueueGitCommandRunner(response("unused"))

        val result = GitClient(runner, TEST_TEXT).loadGraph(Path.of("/repository"), cancelled = { true })

        assertIs<LoadResult.Cancelled>(result)
        assertTrue(runner.commands.isEmpty())
    }

    private fun historyRecord(hash: String, subject: String): ByteArray = listOf(
        hash,
        "",
        "123",
        "Alice",
        "alice@example.com",
        subject,
        "body",
    ).joinToString("\u0000", postfix = "\u0000").toByteArray()

    private fun response(text: String): ByteArray = text.toByteArray()
    private fun response(bytes: ByteArray): ByteArray = bytes

    private companion object {
        val TEST_TEXT = GitClientText(
            repositoryEmpty = "empty repository",
            filterEmpty = "empty filter",
            commandFailed = "command failed",
            historyParseFailed = "parse failed",
            outputTooLarge = "output too large",
        )
    }

    private class QueueGitCommandRunner(
        private vararg val responses: ByteArray,
    ) : GitCommandRunner {
        val commands = mutableListOf<List<String>>()
        private var nextResponse = 0

        override fun <T> run(
            root: Path,
            arguments: List<String>,
            cancelled: () -> Boolean,
            readOutput: (InputStream) -> T,
        ): T {
            if (cancelled()) throw InterruptedException("cancelled")
            commands += arguments
            val response = responses[nextResponse++]
            return ByteArrayInputStream(response).use(readOutput)
        }
    }
}

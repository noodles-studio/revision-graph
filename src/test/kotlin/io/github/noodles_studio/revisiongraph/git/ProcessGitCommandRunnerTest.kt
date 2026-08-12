package io.github.noodles_studio.revisiongraph.git

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessGitCommandRunnerTest {
    @Test
    fun `runner forcibly stops a silent process after cancellation`() {
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        ).toString()
        val runner = ProcessGitCommandRunner { javaExecutable }
        val started = System.nanoTime()

        assertFailsWith<InterruptedException> {
            runner.run(
                Path.of("."),
                listOf("-cp", System.getProperty("java.class.path"), BlockingProcess::class.java.name),
                cancelled = { System.nanoTime() - started > TimeUnit.MILLISECONDS.toNanos(150) },
                readOutput = { input -> input.readBytes() },
            )
        }

        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue(elapsedMillis < 5_000, "Cancellation took $elapsedMillis ms")
    }

    @Test
    fun `runner classifies executable startup failures as command failures`() {
        val runner = ProcessGitCommandRunner { "definitely-missing-revision-graph-git" }

        val error = assertFailsWith<GitCommandException> {
            runner.run(
                Path.of("."),
                listOf("version"),
                cancelled = { false },
                readOutput = { input -> input.readBytes() },
            )
        }

        assertContains(error.message.orEmpty(), "Unable to start")
    }

    object BlockingProcess {
        @JvmStatic
        fun main(args: Array<String>) {
            Thread.sleep(30_000)
        }
    }
}

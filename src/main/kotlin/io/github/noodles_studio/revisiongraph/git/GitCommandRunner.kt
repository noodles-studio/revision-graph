package io.github.noodles_studio.revisiongraph.git

import java.io.InputStream
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal interface GitCommandRunner {
    fun <T> run(
        root: Path,
        arguments: List<String>,
        cancelled: () -> Boolean,
        readOutput: (InputStream) -> T,
    ): T
}

/** Executes Git without coupling the data layer to IntelliJ Platform classes. */
internal class ProcessGitCommandRunner(
    private val executable: () -> String,
) : GitCommandRunner {
    override fun <T> run(
        root: Path,
        arguments: List<String>,
        cancelled: () -> Boolean,
        readOutput: (InputStream) -> T,
    ): T {
        if (cancelled()) throw InterruptedException("Git command cancelled")

        val process = try {
            ProcessBuilder(listOf(executable()) + arguments)
                .directory(root.toFile())
                .start()
        } catch (exception: Exception) {
            throw GitCommandException("Unable to start the configured Git executable", exception)
        }
        val ioPool = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "RevisionGraph Git I/O").apply { isDaemon = true }
        }
        val output = ioPool.submit<T> { process.inputStream.use(readOutput) }
        val error = ioPool.submit<String> {
            process.errorStream.use { stream -> stream.readLimitedText(MAX_ERROR_BYTES) }
        }

        try {
            while (!process.waitFor(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)) {
                output.rethrowFailureIfCompleted()
                error.rethrowFailureIfCompleted()
                if (cancelled()) throw InterruptedException("Git command cancelled")
            }
            if (cancelled()) throw InterruptedException("Git command cancelled")

            val errorText = error.awaitResult()
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val command = arguments.firstOrNull().orEmpty()
                throw GitCommandException("git $command exited $exitCode: ${errorText.trim()}")
            }
            return output.awaitResult()
        } finally {
            runCatching { process.outputStream.close() }
            if (process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor(1, TimeUnit.SECONDS) }
            }
            ioPool.shutdownNow()
        }
    }

    private fun Future<*>.rethrowFailureIfCompleted() {
        if (!isDone) return
        @Suppress("UNCHECKED_CAST")
        (this as Future<Any?>).awaitResult()
    }

    private fun <T> Future<T>.awaitResult(): T = try {
        get()
    } catch (exception: ExecutionException) {
        when (val cause = exception.cause ?: exception) {
            is InterruptedException -> throw cause
            is RuntimeException -> throw cause
            is Error -> throw cause
            else -> throw GitCommandException(cause.message ?: cause.javaClass.simpleName, cause)
        }
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 50L
        const val MAX_ERROR_BYTES = 64 * 1024
    }
}

internal class GitCommandException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal class GitOutputLimitException(message: String) : RuntimeException(message)

internal fun InputStream.readLimitedText(maxBytes: Int): String {
    require(maxBytes > 0)
    val buffer = ByteArray(minOf(8192, maxBytes))
    val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 8192))
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maxBytes) throw GitOutputLimitException("Git text output exceeded $maxBytes bytes")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8)
}

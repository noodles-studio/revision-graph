package io.github.noodles_studio.revisiongraph

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureBoundaryTest {
    @Test
    fun `package imports follow documented dependency direction`() {
        val violations = RULES.flatMap { rule ->
            sourceFiles(rule.packageName).flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    val imported = IMPORT.matchEntire(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                    val forbidden = rule.forbiddenPrefixes.firstOrNull(imported::startsWith)
                    forbidden?.let {
                        "${source.name}:${index + 1} imports $imported (forbidden prefix: $it)"
                    }
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(prefix = "Architecture boundary violations:\n", separator = "\n"),
        )
    }

    private fun sourceFiles(packageName: String): List<Path> {
        val directory = SOURCE_ROOT.resolve(packageName)
        if (!Files.exists(directory)) return emptyList()
        return Files.walk(directory).use { paths ->
            paths.filter { path -> path.isRegularFile() && path.extension in setOf("kt", "java") }
                .toList()
        }
    }

    private data class Rule(
        val packageName: String,
        val forbiddenPrefixes: List<String>,
    )

    private companion object {
        val SOURCE_ROOT: Path = Path.of("src/main/kotlin/io/github/noodles_studio/revisiongraph")
        val IMPORT = Regex("import\\s+([A-Za-z0-9_.*]+)")
        const val PROJECT_PACKAGE = "io.github.noodles_studio.revisiongraph"

        val RULES = listOf(
            Rule(
                "model",
                listOf(
                    "com.intellij",
                    "git4idea",
                    "$PROJECT_PACKAGE.application",
                    "$PROJECT_PACKAGE.git",
                    "$PROJECT_PACKAGE.layout",
                    "$PROJECT_PACKAGE.platform",
                    "$PROJECT_PACKAGE.ui",
                ),
            ),
            Rule(
                "git",
                listOf("com.intellij", "git4idea", "$PROJECT_PACKAGE.application", "$PROJECT_PACKAGE.platform", "$PROJECT_PACKAGE.ui"),
            ),
            Rule(
                "layout",
                listOf("com.intellij", "git4idea", "$PROJECT_PACKAGE.application", "$PROJECT_PACKAGE.git", "$PROJECT_PACKAGE.platform", "$PROJECT_PACKAGE.ui"),
            ),
            Rule(
                "application",
                listOf("com.intellij", "git4idea", "$PROJECT_PACKAGE.platform", "$PROJECT_PACKAGE.ui"),
            ),
            Rule("platform", listOf("$PROJECT_PACKAGE.ui")),
        )
    }
}

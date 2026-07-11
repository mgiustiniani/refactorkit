package org.refactorkit.treesitter

import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * Lightweight multi-language project scanner for structural support.
 *
 * This scanner is intentionally shallow: it maps known source file extensions to
 * language IDs and excludes common generated/build directories. It does not
 * replace language-specific scanners such as [org.refactorkit.java.JavaProjectScanner].
 */
class GenericProjectScanner(
    private val extensionLanguageIds: Map<String, String> = DEFAULT_EXTENSION_LANGUAGE_IDS,
) {
    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val files: List<SourceFile> = if (!normalizedRoot.exists()) emptyList()
        else normalizedRoot.toFile().walkTopDown()
            .onEnter { dir -> !isIgnoredDir(dir.name) }
            .filter { it.isFile }
            .mapNotNull { file ->
                val path = file.toPath()
                val languageId = languageIdFor(path) ?: return@mapNotNull null
                SourceFile(normalizedRoot.relativize(path), path.readText(), languageId)
            }
            .sortedBy { it.path.toString() }
            .toList()

        val sourceRoots = detectSourceRoots(normalizedRoot)
        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = listOf(Module(
                name = normalizedRoot.fileName?.toString() ?: "root",
                root = normalizedRoot,
                sourceRoots = sourceRoots.map(normalizedRoot::relativize),
            )),
            files = files,
            sourceExtensions = extensionLanguageIds.keys,
            ignoredDirectories = IGNORED_DIRECTORIES,
        )
    }

    private fun languageIdFor(path: Path): String? {
        val fileName = path.fileName?.toString() ?: return null
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        return extensionLanguageIds[extension]
    }

    private fun detectSourceRoots(root: Path): List<Path> = listOf(
        root.resolve("src/main/java"),
        root.resolve("src/test/java"),
        root.resolve("src"),
        root.resolve("lib"),
    ).filter { it.exists() && it.isDirectory() }
        .ifEmpty { listOf(root) }

    private fun isIgnoredDir(dirName: String): Boolean = dirName in IGNORED_DIRECTORIES

    companion object {
        val DEFAULT_EXTENSION_LANGUAGE_IDS = mapOf(
            "java" to "java",
            "kt" to "kotlin",
            "kts" to "kotlin",
            "ts" to "typescript",
            "tsx" to "typescript",
            "js" to "javascript",
            "jsx" to "javascript",
            "py" to "python",
            "rs" to "rust",
            "go" to "go",
            "cs" to "csharp",
        )

        private val IGNORED_DIRECTORIES = setOf(
            ".git", ".gradle", ".idea", ".refactorkit",
            "build", "target", "dist", "out", "coverage", "node_modules", "__pycache__",
        )
    }
}

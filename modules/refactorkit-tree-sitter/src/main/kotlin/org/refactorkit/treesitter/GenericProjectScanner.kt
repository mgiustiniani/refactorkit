package org.refactorkit.treesitter

import org.refactorkit.core.Module
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceCapturePolicy
import org.refactorkit.core.Workspace
import java.nio.file.Path

/** Bounded structural inventory. Source reads use owned handles; language intelligence remains separate. */
class GenericProjectScanner private constructor(
    private val capturePolicy: SourceCapturePolicy,
    extensionLanguageIds: Map<String, String>,
) {
    constructor(extensionLanguageIds: Map<String, String> = DEFAULT_EXTENSION_LANGUAGE_IDS) :
        this(SourceCapturePolicy(), extensionLanguageIds)

    constructor(extensionLanguageIds: Map<String, String>, capturePolicy: SourceCapturePolicy) :
        this(capturePolicy, extensionLanguageIds)

    constructor() : this(DEFAULT_EXTENSION_LANGUAGE_IDS)

    private val extensionLanguageIds = extensionLanguageIds.toMap()

    fun scan(root: Path): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val inventory = OwnedSourceInventoryReader(
            extensionLanguageIds, capturePolicy, IGNORED_DIRECTORIES,
            allowMissingRoot = true, ignoreRootDirectory = true,
        ).capture(normalizedRoot)
        val sourceRoots = listOf("src/main/java", "src/test/java", "src", "lib")
            .map(Path::of).filter { it in inventory.directories }.ifEmpty { listOf(Path.of("")) }
        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = listOf(Module(
                name = normalizedRoot.fileName?.toString() ?: "root",
                root = normalizedRoot,
                sourceRoots = sourceRoots,
            )),
            files = inventory.sources,
            sourceExtensions = extensionLanguageIds.keys.toSet(),
            ignoredDirectories = IGNORED_DIRECTORIES,
        )
    }

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

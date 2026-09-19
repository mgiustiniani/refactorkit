package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.nio.file.Files
import java.nio.file.Path

/** Ownership classification for a C source/header file. */
enum class CFileOwnership {
    PUBLIC,
    PRIVATE,
    EXTERNAL,
    GENERATED,
    UNKNOWN,
}

/** One `#include` directive parsed from a source file. */
data class CIncludeDirective(
    val line: Int,
    val target: String,
    val kind: CIncludeKind,
    val resolved: Path?,
    val ownership: CFileOwnership,
    val diagnostics: List<Diagnostic>,
) {
    init {
        require(line >= 1) { "include directive line must be positive" }
        require(target.isNotBlank() && target.length <= 512) { "include target is invalid" }
    }
}

enum class CIncludeKind { QUOTED, ANGLED, MACRO }

/** Bounded include-graph model over a set of source files. */
data class CIncludeGraph(
    val files: List<Path>,
    val edges: List<CIncludeEdge>,
    val ownership: Map<Path, CFileOwnership>,
    val diagnostics: List<Diagnostic>,
) {
    init {
        require(files.distinct().size == files.size) { "include graph has duplicate files" }
        require(edges.map(CIncludeEdge::from).distinct().size == edges.map(CIncludeEdge::from).distinct().size) { "include graph edges are invalid" }
    }
}

data class CIncludeEdge(
    val from: Path,
    val to: Path,
    val line: Int,
    val kind: CIncludeKind,
)

/** Bounded policy for building an include graph. */
data class CIncludeGraphPolicy(
    val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxDirectivesPerFile: Int = DEFAULT_MAX_DIRECTIVES_PER_FILE,
    val maxFiles: Int = DEFAULT_MAX_FILES,
) {
    init {
        require(maxFileBytes in 1..DEFAULT_MAX_FILE_BYTES) { "file size bound is outside the safe range" }
        require(maxDirectivesPerFile in 1..DEFAULT_MAX_DIRECTIVES_PER_FILE) { "directive count bound is outside the safe range" }
        require(maxFiles in 1..DEFAULT_MAX_FILES) { "file count bound is outside the safe range" }
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_MAX_DIRECTIVES_PER_FILE = 1_024
        const val DEFAULT_MAX_FILES = 4_096
    }
}

/**
 * Parses `#include` directives from C source text. Non-executing and bounded.
 * Only the include line is parsed; macros, guards and other directives are ignored.
 */
class CIncludeDirectiveParser(
    private val policy: CIncludeGraphPolicy = CIncludeGraphPolicy(),
) {
    fun parse(text: String): List<CIncludeDirective> {
        if (text.toByteArray(Charsets.UTF_8).size.toLong() > policy.maxFileBytes) {
            return listOf(CIncludeDirective(1, "__limit__", CIncludeKind.MACRO, null, CFileOwnership.UNKNOWN, listOf(
                refusal("c.includeFileLimit", "source file exceeds ${policy.maxFileBytes} bytes"),
            )))
        }
        val directives = mutableListOf<CIncludeDirective>()
        val lines = text.lines()
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#include")) continue
            if (directives.size >= policy.maxDirectivesPerFile) break
            val parsed = parseDirective(index + 1, trimmed)
            if (parsed != null) directives += parsed
        }
        return directives
    }

    private fun parseDirective(line: Int, trimmed: String): CIncludeDirective? {
        // A trailing comment (or other trailing text) after the include must not hide
        // the directive; the include itself is matched, the rest is ignored here.
        val match = INCLUDE_PATTERN.find(trimmed) ?: return null
        val quoted = match.groupValues.getOrNull(2)
        val angled = match.groupValues.getOrNull(3)
        val macro = match.groupValues.getOrNull(4)
        val (target, kind) = when {
            quoted != null && quoted.isNotEmpty() -> quoted to CIncludeKind.QUOTED
            angled != null && angled.isNotEmpty() -> angled to CIncludeKind.ANGLED
            macro != null && macro.isNotEmpty() -> macro to CIncludeKind.MACRO
            else -> return null
        }
        if (target.isBlank() || target.length > 512) return null
        return CIncludeDirective(line, target, kind, null, CFileOwnership.UNKNOWN, emptyList())
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )

    private companion object {
        // #include "x" | #include <x> | #include MACRO
        val INCLUDE_PATTERN = Regex("""#include\s+("([^"]+)"|<([^>]+)>|(\S+))""")
    }
}

/** Builds a bounded include graph and classifies file ownership. */
class CIncludeGraphBuilder(
    private val policy: CIncludeGraphPolicy = CIncludeGraphPolicy(),
    private val directiveParser: CIncludeDirectiveParser = CIncludeDirectiveParser(),
) {
    /**
     * Builds the include graph for the given source files, resolving includes
     * against the workspace and the compilation database's include directories.
     */
    fun build(
        workspaceRoot: Path,
        files: List<Path>,
        includeDirectories: List<Path>,
    ): CIncludeGraph {
        val diagnostics = mutableListOf<Diagnostic>()
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val boundedFiles = files.distinct().take(policy.maxFiles)
        if (files.size > policy.maxFiles) {
            diagnostics += refusal("c.includeFileLimit", "source files exceed ${policy.maxFiles}")
        }

        val ownership = mutableMapOf<Path, CFileOwnership>()
        val edges = mutableListOf<CIncludeEdge>()
        val fileSet = boundedFiles.map { resolve(workspace, it) }.toSet()

        for (file in boundedFiles) {
            val resolved = resolve(workspace, file)
            val text = readBounded(resolved, diagnostics)
            if (text == null) continue
            val directives = directiveParser.parse(text)
            for (directive in directives) {
                if (directive.resolved == null) {
                    val target = resolveInclude(workspace, resolved.parent, directive.target, directive.kind, includeDirectories)
                    if (target != null && target in fileSet) {
                        edges += CIncludeEdge(resolved, target, directive.line, directive.kind)
                    }
                }
            }
            ownership[resolved] = classify(workspace, resolved, includeDirectories)
        }

        for (file in fileSet) {
            ownership.putIfAbsent(file, classify(workspace, file, includeDirectories))
        }

        return CIncludeGraph(
            files = fileSet.toList(),
            edges = edges,
            ownership = ownership,
            diagnostics = diagnostics,
        )
    }

    private fun readBounded(path: Path, diagnostics: MutableList<Diagnostic>): String? {
        if (!Files.isRegularFile(path)) {
            diagnostics += refusal("c.includeFileMissing", "include file '$path' is missing")
            return null
        }
        val size = runCatching { Files.size(path) }.getOrNull()
        if (size == null || size > policy.maxFileBytes) {
            diagnostics += refusal("c.includeFileLimit", "include file '$path' exceeds the bounded size")
            return null
        }
        return runCatching { Files.readString(path, Charsets.UTF_8) }.getOrElse {
            diagnostics += refusal("c.includeFileRead", "include file '$path' could not be read")
            null
        }
    }

    private fun resolve(workspace: Path, path: Path): Path {
        val normalized = if (path.isAbsolute) path.normalize() else workspace.resolve(path).normalize()
        return normalized
    }

    private fun resolveInclude(workspace: Path, includingDirectory: Path?, target: String, kind: CIncludeKind, includeDirectories: List<Path>): Path? {
        val candidates = buildList {
            if (kind == CIncludeKind.QUOTED && includingDirectory != null) add(includingDirectory.resolve(target))
            addAll(includeDirectories.map { it.resolve(target) })
            add(workspace.resolve(target))
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    private fun classify(workspace: Path, path: Path, includeDirectories: List<Path>): CFileOwnership {
        val name = path.fileName.toString()
        val normalized = path.toAbsolutePath().normalize()
        val insideWorkspace = normalized.startsWith(workspace.toAbsolutePath().normalize())
        val insideIncludeRoot = includeDirectories.any { normalized.startsWith(it.toAbsolutePath().normalize()) }
        return when {
            name.endsWith(".generated.c") || name.endsWith(".generated.h") -> CFileOwnership.GENERATED
            // Headers outside the workspace and every include root are read-only external evidence.
            name.endsWith(".h") && !insideWorkspace && !insideIncludeRoot -> CFileOwnership.EXTERNAL
            name.endsWith(".h") && insideIncludeRoot -> CFileOwnership.PUBLIC
            name.endsWith(".h") -> CFileOwnership.PRIVATE
            name.endsWith(".c") -> CFileOwnership.PRIVATE
            else -> CFileOwnership.UNKNOWN
        }
    }

    private fun refusal(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.PROJECT_STRUCTURE,
    )
}

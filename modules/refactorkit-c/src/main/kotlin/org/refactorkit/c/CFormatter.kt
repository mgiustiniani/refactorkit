package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** C formatting result: accepted edits (idempotent) or an explicit refusal. */
sealed interface CFormatResult {
    data class Accepted(val edits: List<TextEdit>, val idempotent: Boolean) : CFormatResult
    data class Refused(val diagnostics: List<Diagnostic>) : CFormatResult
}

/**
 * Bounded clang-format formatter using a captured project style.
 *
 * Formats a whole file or a bounded range, producing minimal edits that preserve
 * unchanged content. Idempotence is demonstrated by re-formatting the formatted
 * output and checking for further changes. Missing style evidence, invalid ranges
 * or unintended edits are refused. Formatting never implicitly organizes includes.
 */
class CFormatter(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val style: String = STYLE_FILE,
) {
    fun formatWholeFile(snapshot: ProjectSnapshot, file: Path): CFormatResult {
        val source = readSource(snapshot, file) ?: return refused("clang.formatSourceMissing", "Format source is missing or outside the snapshot")
        return formatText(snapshot, file, source, null)
    }

    fun formatRange(snapshot: ProjectSnapshot, file: Path, range: SourceRange): CFormatResult {
        val source = readSource(snapshot, file) ?: return refused("clang.formatSourceMissing", "Format source is missing or outside the snapshot")
        if (range.start > range.end) return refused("clang.formatInvalidRange", "Format range is invalid")
        return formatText(snapshot, file, source, range)
    }

    private fun formatText(snapshot: ProjectSnapshot, file: Path, source: String, range: SourceRange?): CFormatResult {
        if (!Files.isRegularFile(toolchain.clangFormatExecutable)) {
            return refused("clang.formatUnavailable", "Hash-bound clang-format executable is unavailable")
        }
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        if (!normalized.startsWith(root) || normalized == root) {
            return refused("clang.formatSourceMissing", "Format source is outside the snapshot")
        }
        val arguments = buildList {
            add("-style=$style")
            if (range != null) add("-lines=${range.start.line + 1}:${range.end.line + 1}")
            add(normalized.toString())
        }
        val formatted = runFormat(snapshot, arguments) ?: return refused("clang.formatFailed", "clang-format failed")
        val edits = diff(source, formatted)
        if (edits.isEmpty()) return CFormatResult.Accepted(emptyList(), idempotent = true)
        val idempotent = verifyIdempotent(snapshot, file, formatted, range)
        if (!idempotent) return refused("clang.formatNonIdempotent", "clang-format was not idempotent")
        return CFormatResult.Accepted(edits, idempotent = true)
    }

    private fun runFormat(snapshot: ProjectSnapshot, arguments: List<String>): String? {
        val execution = processManager.launch(SemanticProcessSpec(
            id = "clang-format-${SEQUENCE.getAndIncrement()}",
            executable = toolchain.clangFormatExecutable,
            arguments = arguments,
            workingDirectory = snapshot.workspace.root.toAbsolutePath().normalize(),
            limits = SemanticProcessLimits(
                maxStdoutBytes = MAX_OUTPUT_BYTES,
                maxStderrBytes = MAX_STDERR_BYTES,
                gracefulShutdownMillis = 1_000,
            ),
        ))
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refactorkit-clang-format-output").apply { isDaemon = true }
        }
        try {
            val future = reader.submit<String> { execution.output.bufferedReader(Charsets.UTF_8).readText() }
            if (!execution.awaitExit(REQUEST_TIMEOUT_MILLIS)) {
                execution.cancel()
                return null
            }
            return future.get(2, TimeUnit.SECONDS)
        } finally {
            reader.shutdownNow()
            execution.close()
        }
    }

    private fun verifyIdempotent(snapshot: ProjectSnapshot, file: Path, formatted: String, range: SourceRange?): Boolean {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        val arguments = buildList {
            add("-style=$style")
            if (range != null) add("-lines=${range.start.line + 1}:${range.end.line + 1}")
            add(normalized.toString())
        }
        // Re-format the already-formatted text by writing it to a scratch file.
        val scratch = root.resolve(".refactorkit-format-${SEQUENCE.getAndIncrement()}.c")
        return try {
            Files.writeString(scratch, formatted)
            val second = runFormat(snapshot, arguments.map { if (it == normalized.toString()) scratch.toString() else it })
            second == formatted
        } finally {
            runCatching { Files.deleteIfExists(scratch) }
        }
    }

    private fun readSource(snapshot: ProjectSnapshot, file: Path): String? {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        if (!normalized.startsWith(root) || normalized == root) return null
        val source = snapshot.files.firstOrNull { it.path.normalize() == file.normalize() }
        return source?.content ?: runCatching { Files.readString(normalized) }.getOrNull()
    }

    internal fun diff(original: String, formatted: String): List<TextEdit> {
        var i = 0
        while (i < original.length && i < formatted.length && original[i] == formatted[i]) i++
        var j = original.length - 1
        var k = formatted.length - 1
        while (j >= i && k >= i && original[j] == formatted[k]) { j--; k-- }
        if (j < i && k < i) return emptyList()
        val start = positionOf(original, i)
        val end = positionOf(original, j + 1)
        val newText = formatted.substring(i, k + 1)
        return listOf(TextEdit(SourceRange(start, end), newText))
    }

    private fun positionOf(text: String, index: Int): SourcePosition {
        var line = 0
        var lineStart = 0
        var i = 0
        while (i < index) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
            i++
        }
        return SourcePosition(line, index - lineStart)
    }

    private fun refused(code: String, message: String) = CFormatResult.Refused(listOf(
        Diagnostic(message = message, severity = Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
    ))

    companion object {
        private val SEQUENCE = AtomicLong(1)
        private const val STYLE_FILE = "file"
        private const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 4 * 1024 * 1024
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}

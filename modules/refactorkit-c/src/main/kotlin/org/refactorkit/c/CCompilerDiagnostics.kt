package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SemanticWorkspaceOverlay
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** C diagnostics result: available diagnostics or an explicit unavailable failure. */
sealed interface CDiagnosticsResult {
    data class Available(
        val diagnostics: List<Diagnostic>,
        val processProvenance: SemanticProcessProvenance? = null,
    ) : CDiagnosticsResult

    data class Unavailable(val diagnostic: Diagnostic) : CDiagnosticsResult
}

/**
 * Read-only Clang syntax diagnostics for the exact old and previewed contexts.
 *
 * Runs `clang -fsyntax-only` (no emission, no build execution) on a captured
 * translation unit with the bounded flags from the compilation database. The
 * source is hash-bound through a materialized overlay; missing inputs, malformed
 * output, timeout or changed context are reported as unavailable, never as clean.
 */
class CCompilerDiagnostics(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) {
    /**
     * Analyzes [file] with the captured [flags] (from the compilation database).
     * Returns available diagnostics or an explicit unavailable failure.
     */
    fun analyze(snapshot: ProjectSnapshot, file: Path, flags: List<String> = emptyList()): CDiagnosticsResult {
        if (!Files.isRegularFile(toolchain.clangExecutable)) {
            return unavailable("clang.compilerDiagnosticsUnavailable", "Hash-bound clang executable is unavailable")
        }
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (file.isAbsolute) file.toAbsolutePath().normalize() else root.resolve(file).normalize()
        if (!normalized.startsWith(root) || normalized == root) {
            return unavailable("clang.compilerSourceMembershipInvalid", "Compiler source must belong to the snapshot")
        }
        if (flags.size > MAX_FLAGS) {
            return unavailable("clang.compilerDiagnosticsFlagLimit", "Compiler flags exceed $MAX_FLAGS entries")
        }
        val overlay = runCatching { SemanticWorkspaceOverlay.create(snapshot) }.getOrElse {
            return unavailable("clang.compilerDiagnosticsOverlayFailed", it.message ?: "Compiler diagnostics overlay failed")
        }
        return try {
            val overlayFile = overlay.toOverlayPath(normalized) ?: return unavailable(
                "clang.compilerSourceMembershipInvalid", "Compiler source is outside the overlay",
            )
            val arguments = buildList {
                add("-fsyntax-only")
                addAll(flags)
                add(overlayFile.toString())
            }
            val execution = processManager.launch(SemanticProcessSpec(
                id = "clang-syntax-${SEQUENCE.getAndIncrement()}",
                executable = toolchain.clangExecutable,
                arguments = arguments,
                workingDirectory = overlay.root,
                limits = SemanticProcessLimits(
                    maxStdoutBytes = MAX_OUTPUT_BYTES,
                    maxStderrBytes = MAX_STDERR_BYTES,
                    gracefulShutdownMillis = 1_000,
                ),
            ))
            val reader = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "refactorkit-clang-syntax-output").apply { isDaemon = true }
            }
            try {
                val future = reader.submit<String> { execution.output.bufferedReader(Charsets.UTF_8).readText() }
                if (!execution.awaitExit(REQUEST_TIMEOUT_MILLIS)) {
                    execution.cancel()
                    return unavailable("clang.compilerDiagnosticsTimeout", "Exact compiler diagnostics timed out")
                }
                val stdoutText = future.get(2, TimeUnit.SECONDS)
                val stderrText = execution.stderrText()
                val text = when {
                    stdoutText.isNotBlank() && stderrText.isNotBlank() -> stdoutText + "\n" + stderrText
                    stdoutText.isNotBlank() -> stdoutText
                    else -> stderrText
                }
                val mutations = overlay.verifySourcesUnchanged()
                if (mutations.isNotEmpty()) return CDiagnosticsResult.Unavailable(mutations.first())
                parse(text, overlay.toWorkspacePath(overlayFile) ?: normalized, execution.provenance, execution.exitCode ?: 0)
            } finally {
                reader.shutdownNow()
                execution.close()
            }
        } catch (failure: Exception) {
            unavailable("clang.compilerDiagnosticsFailed", failure.message ?: "Exact compiler diagnostics failed")
        } finally {
            overlay.close()
        }
    }

    internal fun parseForTest(
        output: String,
        file: Path,
        provenance: SemanticProcessProvenance,
        exitCode: Int = 0,
    ): CDiagnosticsResult = parse(output, file, provenance, exitCode)

    private fun parse(
        output: String,
        file: Path,
        provenance: SemanticProcessProvenance,
        exitCode: Int,
    ): CDiagnosticsResult {
        val parsed = mutableListOf<Diagnostic>()
        var sawErrorSummary = false
        var sawWarningSummary = false
        output.lineSequence().forEach { line ->
            val match = DIAGNOSTIC_LINE.find(line)
            if (match != null) {
                val groups = match.groupValues
                val severity = when (groups[4]) {
                    "error", "fatal error" -> Diagnostic.Severity.ERROR
                    "warning" -> Diagnostic.Severity.WARNING
                    else -> Diagnostic.Severity.INFO
                }
                val message = groups[5].take(MAX_DIAGNOSTIC_MESSAGE)
                val reportedFile = groups[1]
                val attributed = attributeFile(reportedFile, file)
                parsed += Diagnostic(
                    message = message,
                    severity = severity,
                    location = SourceLocation(attributed, SourceRange(
                        SourcePosition(groups[2].toInt() - 1, groups[3].toInt() - 1),
                        SourcePosition(groups[2].toInt() - 1, groups[3].toInt() - 1),
                    )),
                    code = "clang.${groups[4]}",
                    evidence = DiagnosticEvidence.COMPILER,
                    category = DiagnosticCategory.TYPE_RESOLUTION,
                )
            } else if (line.contains("errors generated") || line.contains("error generated") ||
                line.contains("warnings generated") || line.contains("warning generated")) {
                if (line.contains("error")) sawErrorSummary = true
                if (line.contains("warning")) sawWarningSummary = true
            }
        }
        if (parsed.size > MAX_DIAGNOSTICS) {
            return unavailable("clang.compilerDiagnosticsLimit", "Compiler diagnostics exceed $MAX_DIAGNOSTICS entries")
        }
        if (output.isBlank()) {
            // No output: clean iff the syntax check exited successfully.
            return if (exitCode == 0) CDiagnosticsResult.Available(emptyList(), provenance)
            else unavailable("clang.compilerDiagnosticsIncomplete", "Compiler diagnostics were incomplete")
        }
        if (parsed.isEmpty()) {
            return unavailable("clang.compilerDiagnosticsMalformed", "Compiler diagnostics output was malformed")
        }
        if (!sawErrorSummary && !sawWarningSummary) {
            return unavailable("clang.compilerDiagnosticsIncomplete", "Compiler diagnostics were incomplete")
        }
        return CDiagnosticsResult.Available(parsed, provenance)
    }

    /**
     * Attributes a reported diagnostic to the file clang actually names. clang
     * prints the owning file, which may be an included header; the including
     * translation unit is only the fallback for a blank/relative report.
     */
    private fun attributeFile(reportedFile: String, includingFile: Path): Path {
        val trimmed = reportedFile.trim()
        if (trimmed.isBlank()) return includingFile
        val candidate = runCatching { Path.of(trimmed) }.getOrNull() ?: return includingFile
        return when {
            candidate.isAbsolute -> candidate.normalize()
            else -> (includingFile.parent ?: Path.of("")).resolve(candidate).normalize()
        }
    }

    private fun unavailable(code: String, message: String) = CDiagnosticsResult.Unavailable(Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    ))

    companion object {
        private val SEQUENCE = AtomicLong(1)
        private const val MAX_FLAGS = 512
        private const val MAX_OUTPUT_BYTES = 8L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 4 * 1024 * 1024
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private const val MAX_DIAGNOSTICS = 512
        private const val MAX_DIAGNOSTIC_MESSAGE = 512
        private val DIAGNOSTIC_LINE = Regex("""^(.+):(\d+):(\d+): (error|warning|note|fatal error): (.+)$""")
    }
}

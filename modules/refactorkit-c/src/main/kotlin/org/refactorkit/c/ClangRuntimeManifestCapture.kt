package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.nio.file.Path

sealed interface ClangRuntimeManifestCaptureResult {
    class Captured internal constructor(val manifest: ClangRuntimeManifest) : ClangRuntimeManifestCaptureResult
    data class Unavailable(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangRuntimeManifestCaptureResult
}

sealed interface ClangRuntimeManifestCurrency {
    data object Current : ClangRuntimeManifestCurrency
    data class NotCurrent(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangRuntimeManifestCurrency
}

/** Captures an explicit selected inventory. No loader search, tool execution, or semantic admission. */
class ClangRuntimeManifestCapture {
    private val paths = ClangNativePathCapture()

    fun capture(executable: Path, role: ClangArtifactRole, interpreter: Path, libraries: List<Path>,
        bindings: List<ClangRuntimeDependencyBinding>): ClangRuntimeManifestCaptureResult {
        if (libraries.size > 254 || bindings.size > 4096) return unavailable("inputLimit", "Selected runtime graph exceeds its input limit")
        val selectedLibraries = libraries.toList()
        val selectedEdges = bindings.toList()
        var remainingBytes = 2L * 1024 * 1024 * 1024
        fun read(path: Path, artifactRole: ClangArtifactRole): ClangNativePathCaptureResult {
            if (remainingBytes < 64) return ClangNativePathCaptureResult.Unavailable(
                diagnostic("inputLimit", "Selected runtime graph exhausts its byte budget"))
            val result = ClangNativePathCapture(minOf(512L * 1024 * 1024, remainingBytes).toInt()).capture(path, artifactRole)
            if (result is ClangNativePathCaptureResult.Captured) remainingBytes -= result.artifact.evidence.size
            return result
        }
        val root = when (val result = read(executable, role)) {
            is ClangNativePathCaptureResult.Captured -> result
            is ClangNativePathCaptureResult.Unavailable -> return unavailable("inputUnavailable", "Runtime executable could not be captured", result.diagnostic)
        }
        val loader = when (val result = read(interpreter, ClangArtifactRole.INTERPRETER)) {
            is ClangNativePathCaptureResult.Captured -> result
            is ClangNativePathCaptureResult.Unavailable -> return unavailable("inputUnavailable", "Runtime interpreter could not be captured", result.diagnostic)
        }
        val objects = mutableListOf<ClangNativePathCaptureResult.Captured>()
        for (library in selectedLibraries) {
            when (val result = read(library, ClangArtifactRole.SHARED_LIBRARY)) {
                is ClangNativePathCaptureResult.Captured -> objects += result
                is ClangNativePathCaptureResult.Unavailable -> return unavailable("inputUnavailable", "Runtime library could not be captured", result.diagnostic)
            }
        }
        return when (val composed = ClangRuntimeManifest.compose(root, loader, objects, selectedEdges)) {
            is ClangRuntimeManifestComposition.Refused -> unavailable(composed.reason.name.lowercase(),
                "Selected runtime graph was refused: ${composed.reason.name}")
            is ClangRuntimeManifestComposition.Composed -> {
                val captured = ClangRuntimeManifestCaptureResult.Captured(composed.manifest)
                when (val currency = checkCurrent(captured)) {
                    ClangRuntimeManifestCurrency.Current -> captured
                    is ClangRuntimeManifestCurrency.NotCurrent -> ClangRuntimeManifestCaptureResult.Unavailable(currency.diagnostic, currency.inputDiagnostic)
                }
            }
        }
    }

    fun checkCurrent(captured: ClangRuntimeManifestCaptureResult.Captured): ClangRuntimeManifestCurrency {
        for (obj in captured.manifest.objects) {
            when (val current = paths.checkCurrent(obj)) {
                ClangNativePathCurrency.Current -> Unit
                is ClangNativePathCurrency.NotCurrent -> return ClangRuntimeManifestCurrency.NotCurrent(
                    diagnostic("changed", "A runtime object or alias differs from its captured graph"), current.diagnostic)
            }
        }
        return ClangRuntimeManifestCurrency.Current
    }

    private fun unavailable(code: String, message: String, input: Diagnostic? = null) =
        ClangRuntimeManifestCaptureResult.Unavailable(diagnostic(code, message), input)
    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.runtimeManifest.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)
}

package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

sealed interface ClangToolchainRuntimeCurrency {
    data object Current : ClangToolchainRuntimeCurrency
    data class NotCurrent(val diagnostic: Diagnostic, val inputDiagnostic: Diagnostic? = null) : ClangToolchainRuntimeCurrency
}

/** Explicit IO port; injected simulations do not constitute native qualification. */
interface ClangToolchainRuntimeProvider {
    fun capture(clang: Path, clangd: Path, formatter: Path): ClangToolchainRuntimeCaptureResult
    fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured): ClangToolchainRuntimeCurrency

    /**
     * Consumer admission includes the retained capture and its binding to the
     * selected tool paths. Custom providers are trusted ports; simulations do
     * not count as native qualification.
     */
    fun checkCurrent(toolchain: ClangSemanticToolchain): ClangToolchainRuntimeCurrency {
        val captured = toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured
            ?: return ClangToolchainRuntimeCurrency.NotCurrent(Diagnostic(
                message = "Complete native runtime inputs have not been captured",
                severity = Diagnostic.Severity.ERROR,
                code = "clang.toolchainRuntime.changed",
                evidence = DiagnosticEvidence.STRUCTURAL,
                category = DiagnosticCategory.SAFETY,
            ))
        return checkCurrent(captured)
    }
}

/** Read-only native acquisition/currency; delegates graph and identity rules to the domain. */
class ClangToolchainRuntimeCapture : ClangToolchainRuntimeProvider {
    override fun capture(clang: Path, clangd: Path, formatter: Path) = inspect(clang, clangd, formatter,
        ClangGlibcRuntimeInputCapture.SYSTEM_CACHE, ClangGlibcRuntimeInputCapture.SYSTEM_PRELOAD)

    /** Alternate selectors inspect data only; they never authorize actual-host tool probes. */
    fun inspect(clang: Path, clangd: Path, formatter: Path, cache: Path, preload: Path): ClangToolchainRuntimeCaptureResult {
        val resolved = mutableListOf<ClangGlibcRuntimeResolution.Captured>()
        val resolver = ClangGlibcRuntimeResolver()
        for ((path, role) in listOf(clang to ClangArtifactRole.CLANG, clangd to ClangArtifactRole.CLANGD, formatter to ClangArtifactRole.FORMATTER)) {
            when (val result = resolver.capture(path, role, cache, preload)) {
                is ClangGlibcRuntimeResolution.Captured -> resolved += result
                is ClangGlibcRuntimeResolution.Unavailable -> return ClangToolchainRuntimeCaptureResult.Unavailable(
                    diagnostic("inputUnavailable", "One configured tool runtime could not be captured"), result.diagnostic)
            }
        }
        return when (val composition = ClangToolchainRuntimeFacts.compose(resolved[0], resolved[1], resolved[2])) {
            is ClangToolchainRuntimeComposition.Refused -> ClangToolchainRuntimeCaptureResult.Unavailable(
                diagnostic(composition.reason.name.lowercase(), "Configured tool runtimes are incoherent"))
            is ClangToolchainRuntimeComposition.Composed -> {
                val captured = ClangToolchainRuntimeCaptureResult.Captured(composition.facts)
                when (val current = checkCurrent(captured)) {
                    ClangToolchainRuntimeCurrency.Current -> captured
                    is ClangToolchainRuntimeCurrency.NotCurrent -> ClangToolchainRuntimeCaptureResult.Unavailable(current.diagnostic, current.inputDiagnostic)
                }
            }
        }
    }

    /** Revalidate the retained inputs and their binding to this consumer's configured toolchain. */
    override fun checkCurrent(toolchain: ClangSemanticToolchain): ClangToolchainRuntimeCurrency {
        val captured = toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured
            ?: return notCurrent("Complete native runtime inputs have not been captured")
        val runtimes = captured.facts.runtimes
        if (toolchain.provenance.nativeRuntimeIdentity != captured.facts.identity ||
            runtimes.map { it.manifest.executable.requestedPath } != listOf(
                toolchain.clangExecutable, toolchain.clangdExecutable, toolchain.clangFormatExecutable)) {
            return notCurrent("Retained runtime identity or executable selectors do not match the toolchain")
        }
        for (runtime in runtimes) {
            if (runtime.inputs.cacheEvidence.path != ClangGlibcRuntimeInputCapture.SYSTEM_CACHE ||
                runtime.inputs.preloadPath != ClangGlibcRuntimeInputCapture.SYSTEM_PRELOAD) {
                return notCurrent("Inspection selectors cannot authorize actual host execution")
            }
            val retained = runtime.manifest.executable.artifact.evidence
            if (toolchain.provenance.evidence.singleOrNull { it.role == retained.role } != retained) {
                return notCurrent("Configured executable evidence does not match its retained native object")
            }
        }
        return checkCurrent(captured)
    }

    override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured): ClangToolchainRuntimeCurrency {
      return try {
        val input = captured.facts.clang.inputs
        val inputs = ClangGlibcRuntimeInputCapture()
        fun dataCurrent(): ClangToolchainRuntimeCurrency? = when (val result = inputs.checkCurrent(input)) {
            ClangGlibcRuntimeInputCurrency.Current -> null
            is ClangGlibcRuntimeInputCurrency.NotCurrent -> notCurrent("Retained runtime data is unavailable or changed", result.diagnostic)
        }
        dataCurrent()?.let { return it }
        val seen = mutableMapOf<Pair<Path, ClangArtifactRole>, ClangNativePathCaptureResult.Captured>()
        val absences = linkedSetOf<Path>()
        for (runtime in captured.facts.runtimes) {
            var remaining = 2L * 1024 * 1024 * 1024
            for (obj in runtime.manifest.objects) {
                val available = remaining
                if (obj.artifact.evidence.size > available || available < 64) return notCurrent("Retained runtime exceeds its verification budget")
                remaining -= obj.artifact.evidence.size
                val key = obj.requestedPath to obj.artifact.role
                val earlier = seen.putIfAbsent(key, obj)
                if (earlier != null) {
                    if (earlier != obj) return notCurrent("Retained shared runtime selectors have conflicting snapshots")
                } else {
                    when (val fresh = ClangNativePathCapture(minOf(512L * 1024 * 1024, available).toInt()).capture(obj.requestedPath, obj.artifact.role)) {
                        is ClangNativePathCaptureResult.Unavailable -> return notCurrent("Retained native object is unavailable", fresh.diagnostic)
                        is ClangNativePathCaptureResult.Captured -> if (fresh != obj) return notCurrent("Retained native object or alias changed")
                    }
                }
            }
            runtime.observations.filter { it.kind in ABSENCES }.mapTo(absences) { it.path }
        }
        for (path in absences) if (!absenceHolds(path)) return notCurrent("An unsuccessful runtime search input is unavailable or changed")
        dataCurrent() ?: ClangToolchainRuntimeCurrency.Current
    } catch (_: Exception) {
        notCurrent("Retained runtime inputs could not be inspected completely")
    }
    }

    private fun absenceHolds(path: Path): Boolean {
        if (!path.isAbsolute || path.normalize() != path || path.fileName == null || path.nameCount > 256) return false
        val keys = mutableListOf<Pair<Path, Any>>()
        var current = path.root
        fun directory(path: Path): Boolean {
            val attrs = attributes(path)
            if (!attrs.isDirectory || attrs.isSymbolicLink) return false
            val key = attrs.fileKey() ?: return false
            keys += path to key; return true
        }
        if (!directory(current)) return false
        for (part in path.parent) { current = current.resolve(part); if (!directory(current)) return false }
        try { attributes(path); return false } catch (_: NoSuchFileException) { /* Exact no-follow absence only. */ }
        return keys.all { (parent, key) -> val fresh = attributes(parent); fresh.isDirectory && !fresh.isSymbolicLink && fresh.fileKey() == key }
    }
    private fun attributes(path: Path) = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    private fun notCurrent(message: String, input: Diagnostic? = null) = ClangToolchainRuntimeCurrency.NotCurrent(diagnostic("changed", message), input)
    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.toolchainRuntime.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)

    companion object {
        private val ABSENCES = setOf(ClangGlibcSearchKind.DIRECTORY_ABSENT, ClangGlibcSearchKind.HARDWARE_ABSENT, ClangGlibcSearchKind.CANDIDATE_ABSENT)
    }
}

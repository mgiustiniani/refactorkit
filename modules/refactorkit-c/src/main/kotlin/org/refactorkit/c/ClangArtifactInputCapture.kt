package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

enum class ClangArtifactRole(val evidenceRole: String) {
    CLANG("clang-executable"), CLANGD("clangd-executable"), FORMATTER("clang-format-executable"),
    INTERPRETER("program-interpreter"), SHARED_LIBRARY("shared-library"),
}

sealed interface ClangArtifactCaptureResult {
    data class Captured(val role: ClangArtifactRole, val evidence: ClangToolchainFileEvidence,
        val facts: CElfDynamicInputFacts) : ClangArtifactCaptureResult
    data class Unavailable(val diagnostic: Diagnostic) : ClangArtifactCaptureResult
}

sealed interface ClangArtifactCurrency {
    data object Current : ClangArtifactCurrency
    data class NotCurrent(val diagnostic: Diagnostic) : ClangArtifactCurrency
}

/** Read-only artifact boundary; capture does not authorize execution or certify runtime closure. */
class ClangArtifactInputCapture(maxFileBytes: Int = 512 * 1024 * 1024) {
    private val limits = CElfInputLimits(maxFileBytes = maxFileBytes)

    fun capture(file: Path, role: ClangArtifactRole): ClangArtifactCaptureResult {
        return try {
            val path = file.toAbsolutePath().normalize()
            val parentsBefore = parents(path) ?: return unavailable("pathInvalid", "Native artifact path must be direct")
            val before = attributes(path)
            if (!before.isRegularFile || before.isSymbolicLink) {
                return unavailable("pathInvalid", "Native artifact must be a direct regular file")
            }
            if (before.size() > limits.maxFileBytes) return unavailable("inputLimit", "Native artifact exceeds the input limit")
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(limits.maxFileBytes + 1) }
            if (bytes.size > limits.maxFileBytes) return unavailable("inputLimit", "Native artifact exceeds the input limit")
            val after = attributes(path)
            if (!after.isRegularFile || after.isSymbolicLink || parents(path) != parentsBefore ||
                before.fileKey() != after.fileKey() || before.size() != after.size() ||
                before.lastModifiedTime() != after.lastModifiedTime() || before.creationTime() != after.creationTime() ||
                bytes.size.toLong() != before.size()) {
                return unavailable("changed", "Native artifact changed during capture")
            }
            when (val readout = CElfDynamicInputs.read(bytes, limits)) {
                is CElfDynamicInputReadout.Found -> ClangArtifactCaptureResult.Captured(role,
                    ClangToolchainFileEvidence(role.evidenceRole, path, readout.facts.sha256, bytes.size.toLong()), readout.facts)
                is CElfDynamicInputReadout.Refused -> unavailable(readout.reason.name.lowercase(),
                    "Native artifact dependency declarations were refused: ${readout.reason.name}")
            }
        } catch (_: IOException) {
            unavailable("unavailable", "Native artifact could not be read")
        } catch (_: SecurityException) {
            unavailable("unavailable", "Native artifact access was refused")
        } catch (_: UnsupportedOperationException) {
            unavailable("unavailable", "Native artifact requires unsupported file operations")
        }
    }

    fun checkCurrent(captured: ClangArtifactCaptureResult.Captured): ClangArtifactCurrency =
        when (val fresh = capture(captured.evidence.path, captured.role)) {
            is ClangArtifactCaptureResult.Unavailable -> ClangArtifactCurrency.NotCurrent(fresh.diagnostic)
            is ClangArtifactCaptureResult.Captured -> if (fresh == captured) ClangArtifactCurrency.Current
                else ClangArtifactCurrency.NotCurrent(diagnostic("changed", "Native artifact differs from its captured input"))
        }

    private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun parents(path: Path): List<Any?>? {
        var current = path.root ?: return null
        val identities = mutableListOf<Any?>()
        for (part in path.parent ?: return null) {
            current = current.resolve(part)
            val attrs = attributes(current)
            if (!attrs.isDirectory || attrs.isSymbolicLink) return null
            identities += attrs.fileKey()
        }
        return identities
    }

    private fun unavailable(code: String, message: String) = ClangArtifactCaptureResult.Unavailable(diagnostic(code, message))

    private fun diagnostic(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR,
        code = "clang.artifact.$code", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY)
}

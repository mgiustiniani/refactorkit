package org.refactorkit.typescript

import org.refactorkit.core.AdapterExecutionMode
import org.refactorkit.core.DiagnosticRangeCapability
import org.refactorkit.core.DiagnosticSnapshotMode
import org.refactorkit.core.LanguageAdapterResourceLimits
import org.refactorkit.core.LanguageAdapterRuntime
import java.security.MessageDigest

/** Public capability truth for the exact TypeScript compiler diagnostics path. */
object TypeScriptDiagnosticsContract {
    const val BACKEND = "typescript-compiler-exact-v1"
    const val CONTRACT_VERSION = 2
    const val REQUEST_TIMEOUT_MILLIS = 30_000L
    const val MAX_INPUT_BYTES = 536_870_912L
    const val MAX_OUTPUT_BYTES = 8_388_608L
    const val MAX_STDERR_BYTES = 65_536
    const val MAX_PROCESSES = 1
    const val MAX_DIAGNOSTICS = 500
    const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 4_096
    const val COMPILER_HEAP_MIB = 512

    val snapshotModes = setOf(
        DiagnosticSnapshotMode.SAVED_DISK,
        DiagnosticSnapshotMode.IMMUTABLE_EDITOR_OVERLAY,
    )
    val rangeCapability = DiagnosticRangeCapability.EXACT_UTF16_OR_EXPLICIT_PARTIAL
    val runtime = LanguageAdapterRuntime(
        executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
        supportsTimeout = true,
        supportsCancellation = true,
        usesWorkspaceOverlay = true,
        recordsProcessProvenance = true,
        limits = LanguageAdapterResourceLimits(
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
            maxInputBytes = MAX_INPUT_BYTES,
            maxOutputBytes = MAX_OUTPUT_BYTES,
            maxProcesses = MAX_PROCESSES,
        ),
    )
}

data class TypeScriptCompilerAttestation(
    val toolchainProvider: String,
    val nodeVersion: String,
    val typeScriptVersion: String,
    val compilerSha256: String,
    val toolchainEvidenceSha256: String,
)

fun TypeScriptSemanticToolchain.compilerAttestation(): TypeScriptCompilerAttestation {
    val compiler = provenance.evidence.single { it.role == "typescript-compiler-entrypoint" }
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any) {
        digest.update(value.toString().toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    add(provenance.providerId)
    add(provenance.nodeVersion)
    add(provenance.languageServerVersion)
    add(provenance.typeScriptVersion)
    provenance.evidence.sortedBy { it.role }.forEach { evidence ->
        add(evidence.role)
        add(evidence.path.toAbsolutePath().normalize())
        add(evidence.sha256)
        add(evidence.size)
    }
    return TypeScriptCompilerAttestation(
        toolchainProvider = provenance.providerId,
        nodeVersion = provenance.nodeVersion,
        typeScriptVersion = provenance.typeScriptVersion,
        compilerSha256 = compiler.sha256,
        toolchainEvidenceSha256 = digest.digest().joinToString("") { "%02x".format(it) },
    )
}

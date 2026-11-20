package org.refactorkit.c

import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessSpec

/** Result captured by the existing CLI Story runner; this fixture is test-fixtures-only. */
data class CFormatterStoryObservation(
    val plan: PatchPlan,
    val runtimeCurrentBefore: Boolean,
    val runtimeCurrentAfter: Boolean,
    val formatterRequestCount: Int,
    val processArguments: List<List<String>>,
    val noManagedProcessRemains: Boolean,
)

/**
 * Bridges the cross-module Cucumber glue to C's internal bounded process-response
 * seam. This models transport replies only and never claims native process qualification.
 */
class CFormatterStoryHarness {
    fun previewNative(
        toolchain: ClangSemanticToolchain,
        snapshot: ProjectSnapshot,
        startLine: String? = null,
        endLine: String? = null,
        ranges: String? = null,
    ): CFormatterStoryObservation {
        val runtime = ClangToolchainRuntimeCapture()
        val before = runtime.checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current
        val manager = ExternalSemanticProcessManager()
        val facade = CRefactoringFacade(toolchain, manager)
        val requests = mutableListOf<List<String>>()
        facade.observeFormatterProcessForTest { spec: SemanticProcessSpec -> requests += spec.arguments }
        val plan = try {
            facade.preview(snapshot, "formatFile", buildMap {
                put("file", "main.c")
                startLine?.let { put("startLine", it) }
                endLine?.let { put("endLine", it) }
                ranges?.let { put("ranges", it) }
            })
        } finally {
            facade.close()
        }
        val after = runtime.checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current
        val noProcesses = manager.activeProvenance().isEmpty()
        manager.close()
        return CFormatterStoryObservation(plan, before, after, requests.size, requests.toList(), noProcesses)
    }

    fun previewResponse(
        toolchain: ClangSemanticToolchain,
        snapshot: ProjectSnapshot,
        stdout: String,
        exitCode: Int,
    ): CFormatterStoryObservation {
        val runtime = ClangToolchainRuntimeCapture()
        val before = runtime.checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current
        val manager = ExternalSemanticProcessManager()
        val facade = CRefactoringFacade(toolchain, manager)
        var requests = 0
        facade.useFormatterProcessTransportForTest(CFormatProcessTransport { _, _ ->
            requests++
            CFormatProcessResponse(stdout = stdout, exitCode = exitCode, stderrBytes = 13)
        })
        val plan = try {
            facade.preview(snapshot, "formatFile", mapOf("file" to "main.c"))
        } finally {
            facade.close()
        }
        val after = runtime.checkCurrent(toolchain) == ClangToolchainRuntimeCurrency.Current
        val noProcesses = manager.activeProvenance().isEmpty()
        manager.close()
        return CFormatterStoryObservation(plan, before, after, requests, emptyList(), noProcesses)
    }
}

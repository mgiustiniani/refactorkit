package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEditIdentity
import java.nio.file.Path

/** Retains one exact native statement-helper preview throughout the core transaction. */
internal class CManagedStatementContext(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager,
) {
    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val lease = plan.authorityLease
        if (plan.status != PatchStatus.PREVIEW || !plan.requiresUserApproval ||
            plan.evidence != RefactoringEvidence.COMPILER_PROVEN || plan.operation != "extractStatements" ||
            plan.snapshotHash != before.hash || before.copy().hash != before.hash ||
            lease?.kind != "c.statement-extract-v1" || lease.operation != plan.operation ||
            lease.snapshotHash != before.hash ||
            lease.workspaceEditSha256 != WorkspaceEditIdentity.sha256(plan.workspaceEdit)) return refusedGate()
        val currentRuntimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        }
        if (currentRuntimeIdentity == null || lease.attributes["c.nativeRuntimeIdentity"] != currentRuntimeIdentity) {
            return refusedGate()
        }
        val file: Path
        val functionName: String
        val helperName: String
        try {
            fun attribute(key: String, limit: Int): String = requireNotNull(lease.attributes[key]).also { require(it.length <= limit) }
            fun offset(key: String): Int {
                val raw = attribute(key, 10)
                return raw.toInt().also { require(it >= 0 && it.toString() == raw) }
            }
            file = Path.of(attribute("c.file", 4096))
            functionName = attribute("c.functionName", 128)
            helperName = attribute("c.helperName", 128)
            val selection = CFunctionByteSpan(offset("c.selectionStart"), offset("c.selectionEndExclusive"))
            val rebuilt = CStatementExtractionPlanner(toolchain, processManager)
                .preview(before, file, functionName, selection, helperName)
            require(rebuilt.status == PatchStatus.PREVIEW && rebuilt.authorityLease == lease)
        } catch (_: Exception) { return refusedGate() }
        val root = before.workspace.root.toAbsolutePath().normalize()
        val service = CFunctionFactsService(toolchain, processManager)
        return DiagnosticsGate.enabled("clang-statement-extract-v1") { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == root && candidate.copy().hash == candidate.hash) {
                "c.statementExtract.authorityInvalid: candidate workspace or immutable content identity differs"
            }
            val result = when (candidate.hash) {
                before.hash -> service.analyze(candidate, file, functionName)
                lease.evidenceHash -> if (CCompilationSnapshotCapture.isCurrent(before)) {
                    service.analyzePreview(before, candidate, file, helperName)
                } else {
                    service.analyze(candidate, file, helperName)
                }
                else -> error("c.statementExtract.authorityInvalid: candidate differs from the approved before/after images")
            }
            when (result) {
                is CFunctionFactsResult.Available -> {
                    require(result.snapshotHash == candidate.hash) { "c.statementExtract.authorityInvalid: compiler facts identify a different candidate" }
                    emptyList<Diagnostic>()
                }
                is CFunctionFactsResult.NotFound -> error("c.statementExtract.familyUnavailable: approved function family disappeared")
                is CFunctionFactsResult.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
            }
        }
    }

    private fun refusedGate() = DiagnosticsGate.enabled("clang-statement-extract-v1") {
        error("c.statementExtract.authorityInvalid: unchanged compiler-bound preview, explicit approval, current runtime identity and exact helper identity are required")
    }
}

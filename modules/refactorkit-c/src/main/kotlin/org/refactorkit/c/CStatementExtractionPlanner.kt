package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Path
import java.util.Locale

class CStatementExtractionPlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) {
    fun preview(snapshot: ProjectSnapshot, file: Path, functionName: String, selection: CFunctionByteSpan, helperName: String): PatchPlan {
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        } ?: return refused(snapshot, "c.statementExtract.runtimeUnavailable", "A current resolved native runtime identity is required")
        val service = CFunctionFactsService(toolchain, processManager)
        val before = when (val result = service.analyzeStatements(snapshot, file, functionName, selection)) {
            is CStatementExtractionFactsResult.Available -> result.facts
            is CStatementExtractionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.code ?: "c.statementExtract.factsUnavailable", result.diagnostic.message)
        }
        val source = snapshot.files.single()
        val rendered = when (val result = CStatementExtractionEdits.render(source.content, before, helperName)) {
            is CStatementExtractionEditsResult.Accepted -> result
            is CStatementExtractionEditsResult.Refused -> return refused(snapshot, "c.statementExtract.${result.reason.name.lowercase(Locale.ROOT)}", "Statement helper edits refused: ${result.reason}")
        }
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), rendered.edits)))
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, edit) }
        catch (_: Exception) { return refused(snapshot, "c.statementExtract.previewInvalid", "The helper edits could not be staged") }
        val after = when (val result = service.analyzeStatementsPreview(snapshot, candidate, file, helperName, rendered.helperSelection)) {
            is CStatementExtractionFactsResult.Available -> result.facts
            is CStatementExtractionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.code ?: "c.statementExtract.previewUnavailable", result.diagnostic.message)
        }
        val family = when (val result = service.analyzePreview(snapshot, candidate, file, helperName)) {
            is CFunctionFactsResult.Available -> result.facts
            is CFunctionFactsResult.NotFound -> return refused(snapshot, "c.statementExtract.helperMissing", "Staged helper declaration is unavailable")
            is CFunctionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.code ?: "c.statementExtract.familyUnavailable", result.diagnostic.message)
        }
        when (val result = CStatementExtractionEdits.validateCandidate(source.content, before, candidate.files.single().content, after, family, helperName)) {
            CStatementExtractionValidation.Accepted -> Unit
            is CStatementExtractionValidation.Refused -> return refused(snapshot, "c.statementExtract.${result.reason.name.lowercase(Locale.ROOT)}", "Native helper post-model refused: ${result.reason}")
        }
        return PatchPlan(operation = "extractStatements", snapshotHash = snapshot.hash, confidence = 1.0, requiresUserApproval = true,
            summary = "Extract ${before.statements.size} integer declarations from '$functionName' to private helper '$helperName' with ${before.inputs.size} inputs and result '${before.outputName}'",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, evidence = RefactoringEvidence.COMPILER_PROVEN, riskLevel = RiskLevel.MEDIUM,
            warnings = listOf("Captured one-file static integer family; copied arithmetic declarations have proven pure inputs and one ordinary result. Broader type/control/lifetime/context/resource qualification remains separate."),
            authorityLease = OperationAuthorityLease(kind = "c.statement-extract-v1", operation = "extractStatements", snapshotHash = snapshot.hash,
                evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit), attributes = mapOf(
                    "c.file" to source.path.normalize().toString(), "c.functionName" to functionName, "c.helperName" to helperName,
                    "c.selectionStart" to selection.start.toString(), "c.selectionEndExclusive" to selection.endExclusive.toString(),
                    "c.resourceDirectoryIdentity" to (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                    "c.compilerSha256" to toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)),
        )
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = "extractStatements", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        requiresUserApproval = false, summary = message, refusalCode = code, evidence = RefactoringEvidence.COMPILER_PROVEN, riskLevel = RiskLevel.HIGH,
        diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code,
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY)),
    )
}

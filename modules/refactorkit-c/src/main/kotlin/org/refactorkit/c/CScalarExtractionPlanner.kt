package org.refactorkit.c

import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import java.nio.file.Path
import java.util.Locale

class CScalarExtractionPlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) {
    fun preview(snapshot: ProjectSnapshot, file: Path, selection: CFunctionByteSpan, tempName: String,
        functionName: String? = null): PatchPlan {
        val runtimeIdentity = toolchain.provenance.nativeRuntimeIdentity?.takeIf { identity ->
            (toolchain.runtime as? ClangToolchainRuntimeCaptureResult.Captured)?.facts?.identity == identity
        } ?: return refused(snapshot, "c.scalarExtract.runtimeUnavailable", "A current resolved native runtime identity is required")
        val service = CFunctionFactsService(toolchain, processManager)
        val before = when (val result = if (functionName == null) service.analyzeScalarExpression(snapshot, file, selection)
            else service.analyzeScalarExpression(snapshot, file, functionName, selection)) {
            is CScalarExpressionFactsResult.Available -> result.facts
            is CScalarExpressionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.code ?: "c.scalarExtract.factsUnavailable", result.diagnostic.message)
        }
        val source = snapshot.files.single()
        val rendered = when (val result = CScalarExtractionEdits.render(source.content, before, tempName)) {
            is CScalarExtractionEditsResult.Accepted -> result
            is CScalarExtractionEditsResult.Refused -> return refused(snapshot, "c.scalarExtract.${result.reason.name.lowercase(Locale.ROOT)}", "Scalar edits refused: ${result.reason}")
        }
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), rendered.edits)))
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, edit) }
            catch (_: Exception) { return refused(snapshot, "c.scalarExtract.previewInvalid", "The scalar edits could not be staged") }
        val after = when (val result = service.analyzeScalarExpressionPreview(snapshot, candidate, file, before.functionName, rendered.initializerSelection)) {
            is CScalarExpressionFactsResult.Available -> result.facts
            is CScalarExpressionFactsResult.Unavailable -> return refused(snapshot, result.diagnostic.code ?: "c.scalarExtract.previewUnavailable", result.diagnostic.message)
        }
        when (val result = CScalarExtractionEdits.validateCandidate(source.content, before, candidate.files.single().content, after, tempName)) {
            CScalarExtractionValidation.Accepted -> Unit
            is CScalarExtractionValidation.Refused -> return refused(snapshot, "c.scalarExtract.${result.reason.name.lowercase(Locale.ROOT)}", "Native scalar post-model refused: ${result.reason}")
        }
        return PatchPlan(operation = "extractExpression", snapshotHash = snapshot.hash, confidence = 1.0, requiresUserApproval = true,
            summary = "Extract native int expression in '${before.functionName}' into const temporary '$tempName' while preserving '${before.assignmentTargetType}' initializer conversion",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, evidence = RefactoringEvidence.COMPILER_PROVEN, riskLevel = RiskLevel.MEDIUM,
            warnings = listOf("Source-owned eager integer initializer; broader type/control/lifetime/context/tool-resource qualification remains separate."),
            authorityLease = OperationAuthorityLease(kind = "c.scalar-extract-v1", operation = "extractExpression", snapshotHash = snapshot.hash,
                evidenceHash = candidate.hash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit), attributes = mapOf(
                    "c.file" to source.path.normalize().toString(), "c.functionName" to before.functionName, "c.tempName" to tempName,
                    "c.selectionStart" to selection.start.toString(), "c.selectionEndExclusive" to selection.endExclusive.toString(),
                    "c.candidateSelectionStart" to rendered.initializerSelection.start.toString(), "c.candidateSelectionEndExclusive" to rendered.initializerSelection.endExclusive.toString(),
                    "c.resourceDirectoryIdentity" to (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
                    "c.nativeRuntimeIdentity" to runtimeIdentity,
                    "c.compilerSha256" to toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)),
        )
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, message: String) = PatchPlan(
        operation = "extractExpression", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash, confidence = 0.0,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), requiresUserApproval = false, summary = message, refusalCode = code,
        evidence = RefactoringEvidence.COMPILER_PROVEN, riskLevel = RiskLevel.HIGH,
        diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code,
            evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY)),
    )
}

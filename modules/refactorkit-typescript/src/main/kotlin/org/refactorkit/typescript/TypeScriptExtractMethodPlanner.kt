package org.refactorkit.typescript

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/** Compatibility refusal: use extractFunction/extractConstant with an exact returned action. */
class TypeScriptExtractMethodPlanner(private val client: TypeScriptSemanticClient) {
    fun preview(snapshot: ProjectSnapshot, filePath: Path, startLine: Int, endLine: Int, methodName: String): PatchPlan {
        if (!client.isRunning) return refused(snapshot, "typescript.semanticNotStarted", "TypeScript semantic adapter is not running")
        if (snapshot.files.none { it.path.normalize() == filePath.normalize() }) {
            return refused(snapshot, "typescript.fileNotFound", "File not found: $filePath")
        }
        return refused(snapshot, "typescript.extractActionRequired",
            "Use extractFunction or extractConstant with an exact UTF-16 selection and returned refactor/action identity")
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, reason: String) = PatchPlan(
        operation = "extractMethod", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = reason,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = listOf(Diagnostic(reason, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(reason), riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER, refusalCode = code,
    )
}

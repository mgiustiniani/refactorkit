package org.refactorkit.typescript

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/** Compatibility refusal: a spelling alone cannot identify a compiler declaration move. */
class TypeScriptMoveSymbolPlanner(private val client: TypeScriptSemanticClient) {
    fun preview(snapshot: ProjectSnapshot, filePath: Path, symbolName: String, targetFilePath: Path): PatchPlan {
        if (!client.isRunning) return refused(snapshot, "typescript.semanticNotStarted", "TypeScript semantic adapter is not running")
        if (snapshot.files.none { it.path.normalize() == filePath.normalize() }) {
            return refused(snapshot, "typescript.fileNotFound", "File not found: $filePath")
        }
        return refused(snapshot, "typescript.moveActionRequired",
            "Use moveDeclaration with an exact UTF-16 selection, returned refactor/action identity and existing targetFile")
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, reason: String) = PatchPlan(
        operation = "moveSymbol", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = reason,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = listOf(Diagnostic(reason, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(reason), riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER, refusalCode = code,
    )
}

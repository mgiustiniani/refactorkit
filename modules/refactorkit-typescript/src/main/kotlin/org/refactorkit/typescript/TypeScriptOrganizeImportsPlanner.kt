package org.refactorkit.typescript

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/** Explicit tsserver organizeImports modes; no invented LSP method or direct writes. */
internal class TypeScriptOrganizeImportsPlanner(
    private val client: TypeScriptSemanticClient,
    private val compilerPreview: TypeScriptCompilerPreview,
) {
    fun preview(snapshot: ProjectSnapshot, filePath: Path, arguments: Map<String, String>): PatchPlan {
        if (!client.isRunning) return refused(snapshot, "typescript.semanticNotStarted", "TypeScript semantic adapter is not running")
        if (snapshot.files.none { it.path.normalize() == filePath.normalize() }) {
            return refused(snapshot, "typescript.fileNotFound", "File not found: $filePath")
        }
        val mode = TypeScriptOrganizeImportsMode.entries.singleOrNull { it.protocolName == (arguments["mode"] ?: "All") }
            ?: return refused(snapshot, "typescript.organizeImportsModeUnsupported", "Unsupported organizeImports mode")
        val formatting = runCatching { TypeScriptCompilerFormatting.from(arguments) }.getOrElse {
            return refused(snapshot, "typescript.formattingUnsupported", it.message ?: "Unsupported formatting preferences")
        }
        return compilerPreview.preview(snapshot, "organizeImports", "organizeImports",
            formatting.evidence() + mapOf("file" to filePath.normalize().toString(), "mode" to mode.protocolName)) {
            when (val result = client.requestOrganizeImportsEdit(filePath, mode, formatting, snapshot)) {
                is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot,
                    result.diagnostics.firstOrNull()?.code ?: "typescript.organizeImportsRefused",
                    result.diagnostics.joinToString("; ") { it.message })
                is ExternalWorkspaceEditNormalization.Accepted -> {
                    val edit = result.normalized.workspaceEdit
                    if (edit.edits.isEmpty()) refused(snapshot, "typescript.organizeImportsNoChange", "No compiler import changes")
                    else PatchPlan(
                        operation = "organizeImports", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
                        confidence = 1.0, requiresUserApproval = true,
                        summary = "Organize imports in $filePath using ${mode.protocolName}",
                        affectedFiles = edit.affectedFiles(), workspaceEdit = edit, riskLevel = RiskLevel.LOW,
                        evidence = RefactoringEvidence.COMPILER_PROVEN,
                    )
                }
            }
        }
    }

    private fun refused(snapshot: ProjectSnapshot, code: String, reason: String) = PatchPlan(
        operation = "organizeImports", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = reason,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = listOf(Diagnostic(reason, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(reason), riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER, refusalCode = code,
    )
}

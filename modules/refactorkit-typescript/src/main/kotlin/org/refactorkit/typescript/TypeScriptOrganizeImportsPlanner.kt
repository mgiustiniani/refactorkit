package org.refactorkit.typescript

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import java.nio.file.Path

/**
 * Organize imports in a TypeScript/JavaScript file using the TypeScript language server.
 */
class TypeScriptOrganizeImportsPlanner(
    private val client: TypeScriptSemanticClient,
) {
    fun preview(snapshot: ProjectSnapshot, filePath: Path): PatchPlan {
        if (!client.isRunning) return refused(snapshot, "typescript.semanticNotStarted",
            "TypeScript semantic adapter is not running")

        val file = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() }
            ?: return refused(snapshot, "typescript.fileNotFound", "File not found: $filePath")

        val paramsJson = buildString {
            append("""{"method":"textDocument/organizeImports","params":{"textDocument":{"uri":""")
            append(filePath.toAbsoluteUri())
            append("""}}}""")
        }
        val normalizer = ExternalWorkspaceEditNormalizer()
        val result = client.requestWorkspaceEdit(paramsJson, snapshot, normalizer)

        return when (result) {
            is ExternalWorkspaceEditNormalization.Accepted -> {
                val workspaceEdit = result.normalized.workspaceEdit
                PatchPlan(
                    operation = "organizeImports",
                    status = PatchStatus.PREVIEW,
                    snapshotHash = snapshot.hash,
                    confidence = 1.0,
                    requiresUserApproval = true,
                    summary = "Organize imports in ${filePath.fileName}",
                    affectedFiles = setOf(filePath),
                    workspaceEdit = workspaceEdit,
                    diagnosticsBefore = emptyList(),
                    diagnosticsAfterPreview = emptyList(),
                    warnings = listOf("TypeScript organize imports applied."),
                    riskLevel = RiskLevel.LOW,
                    evidence = RefactoringEvidence.LANGUAGE_SERVER,
                )
            }
            is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot,
                "typescript.organizeImportsRefused", result.diagnostics.joinToString("; ") { it.message })
        }
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        reason: String,
    ) = PatchPlan(
        operation = "organizeImports",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER,
        refusalCode = code,
    )

    companion object {
        private fun Path.toAbsoluteUri(): String = "file://${toAbsolutePath().normalize().toString().replace('\\', '/')}"
    }
}

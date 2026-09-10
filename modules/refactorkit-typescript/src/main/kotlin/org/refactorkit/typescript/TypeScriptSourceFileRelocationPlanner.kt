package org.refactorkit.typescript

import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.FileEdit
import org.refactorkit.core.SourceFile
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Relocate a TypeScript/JavaScript source file to a new file path using the
 * exact `getEditsForFileRename` compiler-server authority.
 *
 * The planner is a thin extract -> delegate -> response adapter: it validates
 * the recognized source file and the relocation target, delegates to the raw
 * pinned tsserver via [TypeScriptSemanticClient.requestFileRenameEdit], and
 * returns the compiler-proven [PatchPlan] only within source-only relocation
 * authority. No invented LSP shapes are used; `getRefactorEdits` is never used.
 */
internal class TypeScriptSourceFileRelocationPlanner(
    private val client: TypeScriptSemanticClient,
    private val compilerPreview: TypeScriptCompilerPreview,
) {
    fun preview(snapshot: ProjectSnapshot, filePath: Path, targetFilePath: Path): PatchPlan {
        if (!client.isRunning) return refused(snapshot, "TypeScript semantic adapter is not running")

        val file = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() }
            ?: return refused(snapshot, "Source file not found")

        if (!recognizedSource(file)) return refused(snapshot, "Source file is not a recognized TypeScript or JavaScript source")
        if (targetFilePath.fileName?.toString()?.substringAfterLast('.', "") !in
            TypeScriptSemanticAdapter.descriptor(file.languageId).extensions) {
            return refused(snapshot, "Relocation target is not a recognized source in the source language")
        }

        if (snapshot.files.any { it.path.normalize() == targetFilePath.normalize() }) {
            return refused(snapshot, "Relocation target collides with an existing file")
        }

        return compilerPreview.preview(
            snapshot, "sourceFileRelocation", "getEditsForFileRename",
            mapOf("oldFilePath" to filePath.normalize().toString(), "newFilePath" to targetFilePath.normalize().toString()),
            requiredSourcesBefore = setOf(file.path.normalize()),
            requiredSourcesAfter = setOf(targetFilePath.normalize()),
        ) { proposal(snapshot, file.path, targetFilePath) }
    }

    private fun proposal(snapshot: ProjectSnapshot, filePath: Path, targetFilePath: Path): PatchPlan {
        val result = client.requestFileRenameEdit(filePath, targetFilePath, snapshot, ExternalWorkspaceEditNormalizer())
        return when (result) {
            is ExternalWorkspaceEditNormalization.Accepted -> {
                val workspaceEdit = result.normalized.workspaceEdit
                val sources = snapshot.trackedFiles.associateBy { it.path.normalize() }
                val rename = FileEdit.Rename(filePath.normalize(), targetFilePath.normalize())
                if (workspaceEdit.edits.filterIsInstance<FileEdit.Rename>() != listOf(rename) ||
                    workspaceEdit.edits.any { edit -> when (edit) {
                        is FileEdit.Modify -> sources[edit.path.normalize()]?.let(::recognizedSource) != true
                        is FileEdit.Rename -> edit != rename
                        else -> true
                    } }) {
                    return refused(snapshot, "Compiler relocation edits exceed source-only authority")
                }
                PatchPlan(
                    operation = "sourceFileRelocation",
                    status = PatchStatus.PREVIEW,
                    snapshotHash = snapshot.hash,
                    confidence = 1.0,
                    requiresUserApproval = true,
                    summary = "Relocate source file $filePath to $targetFilePath",
                    affectedFiles = workspaceEdit.affectedFiles(),
                    workspaceEdit = workspaceEdit,
                    diagnosticsBefore = emptyList(),
                    diagnosticsAfterPreview = emptyList(),
                    warnings = listOf(
                        "TypeScript getEditsForFileRename applied compiler-proven import and export updates.",
                    ),
                    riskLevel = RiskLevel.LOW,
                    evidence = RefactoringEvidence.COMPILER_PROVEN,
                )
            }
            is ExternalWorkspaceEditNormalization.Refused -> {
                val first = result.diagnostics.firstOrNull()
                if (first?.code?.startsWith("typescript.compilerServer") == true) {
                    refused(snapshot, "TypeScript compiler server is unavailable or its evidence is not clean")
                } else {
                    refused(snapshot, result.diagnostics.joinToString("; ") { it.message })
                }
            }
        }
    }

    private fun recognizedSource(file: SourceFile): Boolean =
        file.languageId in setOf("typescript", "javascript") &&
            file.path.fileName?.toString()?.substringAfterLast('.', "") in
            TypeScriptSemanticAdapter.descriptor(file.languageId).extensions

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "sourceFileRelocation",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
    )
}

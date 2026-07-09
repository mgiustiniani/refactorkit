package org.refactorkit.treesitter

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Preview planner for Level 1 structural local rename.
 *
 * This is intentionally local and textual: it replaces whole identifier tokens
 * in one file, skipping comments and string/char literals for common languages.
 * It is not semantic and must not be used as a project-wide symbol refactoring.
 */
class GenericLocalRenamePlanner {

    fun preview(
        snapshot: ProjectSnapshot,
        filePath: Path,
        from: String,
        to: String,
    ): PatchPlan {
        if (!isValidIdentifier(from)) return refused(snapshot, "Invalid source identifier: $from")
        if (!isValidIdentifier(to)) return refused(snapshot, "Invalid target identifier: $to")
        if (from == to) return refused(snapshot, "Source and target identifiers are the same: $from")

        val file = snapshot.files.find { it.path == filePath }
            ?: return refused(snapshot, "File not found in snapshot: $filePath")

        val matches = CommentLiteralFilter.filter(
            GenericStructuralSearch.findIdentifier(file.content, from, file.languageId),
            file.content, file.languageId,
        )

        val edits = matches.map { match ->
            val startOffset = CommentLiteralFilter.offsetFor(file.content, match.line, match.character)
            TextEdit(
                range = TextEdits.rangeForOffset(file.content, startOffset, match.length),
                newText = to,
            )
        }

        if (edits.isEmpty()) {
            return PatchPlan(
                operation = "localRename",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = false,
                summary = "No occurrences of $from found in $filePath.",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                warnings = listOf("No edits generated."),
                riskLevel = RiskLevel.LOW,
            )
        }

        return PatchPlan(
            operation = "localRename",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.75,
            requiresUserApproval = true,
            summary = "Local rename $from \u2192 $to in $filePath (${edits.size} occurrence(s)).",
            affectedFiles = setOf(filePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(filePath, edits))),
            warnings = listOf(
                "Structural local rename is textual and file-scoped; it is not a semantic project-wide refactoring.",
                "Comments and string/char literals are skipped via CommentLiteralFilter (heuristic).",
            ),
            riskLevel = RiskLevel.MEDIUM,
        )
    }

    private fun refused(snapshot: ProjectSnapshot, reason: String): PatchPlan = PatchPlan(
        operation = "localRename",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    private fun isValidIdentifier(value: String): Boolean =
        value.isNotEmpty() && (value.first().isLetter() || value.first() == '_' || value.first() == '$') &&
            value.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}

package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Move a C source/header file to a new path and update proven literal includes
 * in one preview plan.
 *
 * Literal `#include "..."` / `#include <...>` directives that resolve to the
 * moved file are rewritten to the new path. Refusals cover destination/path
 * collisions, macro-computed includes that could target the moved file,
 * generated files, and unsupported build bindings. No blind textual replacement.
 */
class CMovePlanner {
    fun preview(snapshot: ProjectSnapshot, filePath: Path, targetFilePath: Path): PatchPlan {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val oldPath = filePath.normalize()
        val newPath = targetFilePath.normalize()
        val oldResolved = root.resolve(oldPath).normalize()
        val newResolved = root.resolve(newPath).normalize()

        val file = snapshot.files.singleOrNull { it.path.normalize() == oldPath }
            ?: return refused(snapshot, "Source file to move was not found")
        if (file.languageId !in setOf("c", "cpp", "objective-c")) {
            return refused(snapshot, "Source file is not a recognized C source")
        }
        if (snapshot.files.any { it.path.normalize() == newPath }) {
            return refused(snapshot, "Move target collides with an existing file")
        }
        if (!isCSourceOrHeader(newPath)) {
            return refused(snapshot, "Move target is not a recognized C source or header path")
        }
        if (!newResolved.startsWith(root) || newResolved == root) {
            return refused(snapshot, "Move target is outside the workspace")
        }

        // Refuse generated files and macro-computed includes that could target the moved file.
        val generated = snapshot.files.firstOrNull { it.path.normalize() == oldPath && isGenerated(oldPath) }
        if (generated != null) return refused(snapshot, "Move target is a generated file")

        val includeEdits = buildIncludeEdits(snapshot, oldResolved, newResolved, oldPath, newPath)
            ?: return refused(snapshot, "A macro-computed include could target the moved file")

        val edits = mutableListOf<FileEdit>()
        edits += FileEdit.Rename(oldPath, newPath)
        includeEdits.forEach { (file, textEdits) ->
            edits += FileEdit.Modify(file, textEdits)
        }

        return PatchPlan(
            operation = "moveSource",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Move source file $oldPath to $newPath and update ${includeEdits.values.sumOf { it.size }} literal include(s)",
            affectedFiles = WorkspaceEdit(edits).affectedFiles(),
            workspaceEdit = WorkspaceEdit(edits),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Literal includes updated; macro-computed includes and build bindings are not rewritten."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.LANGUAGE_SERVER,
        )
    }

    private fun buildIncludeEdits(
        snapshot: ProjectSnapshot,
        oldResolved: Path,
        newResolved: Path,
        oldPath: Path,
        newPath: Path,
    ): Map<Path, List<TextEdit>>? {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        for (source in snapshot.files) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val directives = CIncludeDirectiveParser().parse(source.content)
            for (directive in directives) {
                if (directive.kind == CIncludeKind.MACRO) {
                    // Macro-computed includes cannot be proven safe to rewrite; conservative refusal.
                    return null
                }
                val includingDir = root.resolve(source.path.parent ?: Path.of("")).normalize()
                val targetResolved = if (directive.kind == CIncludeKind.QUOTED) {
                    includingDir.resolve(directive.target).normalize()
                } else {
                    root.resolve(directive.target).normalize()
                }
                if (targetResolved != oldResolved) continue
                val newTarget = includingDir.relativize(newResolved).toString().replace('\\', '/')
                val lineText = source.content.lines().getOrNull(directive.line - 1) ?: continue
                val idx = lineText.indexOf(directive.target)
                if (idx < 0) continue
                val start = SourcePosition(directive.line - 1, idx)
                val end = SourcePosition(directive.line - 1, idx + directive.target.length)
                editsByFile.getOrPut(source.path.normalize()) { mutableListOf() } += TextEdit(SourceRange(start, end), newTarget)
            }
        }
        return editsByFile
    }

    private fun isCSourceOrHeader(path: Path): Boolean {
        val ext = path.fileName?.toString()?.substringAfterLast('.', "") ?: return false
        return ext in setOf("c", "h", "cpp", "hpp", "cc", "hh", "cxx", "hxx")
    }

    private fun isGenerated(path: Path): Boolean {
        val name = path.fileName.toString()
        return name.endsWith(".generated.c") || name.endsWith(".generated.h")
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "moveSource",
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

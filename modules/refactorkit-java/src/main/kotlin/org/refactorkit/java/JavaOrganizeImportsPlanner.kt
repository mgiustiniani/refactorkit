package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Generates a [PatchPlan] that organises the import block of one or more Java files.
 *
 * Rules applied:
 * - Remove duplicate imports.
 * - Remove same-package imports (they are never needed).
 * - Sort imports: java.* → javax.* → jakarta.* → org.* → com.* → everything else.
 * - Static imports are placed last in their own group.
 * - Unused import detection is NOT performed (requires full type resolution).
 */
class JavaOrganizeImportsPlanner {

    fun preview(snapshot: ProjectSnapshot, filePaths: List<Path>): PatchPlan {
        val edits = mutableListOf<FileEdit>()
        val affected = mutableSetOf<Path>()

        for (relativePath in filePaths) {
            val file = snapshot.files.find { it.path == relativePath } ?: continue
            val modify = organizeFile(file) ?: continue
            edits += modify
            affected.add(relativePath)
        }

        return PatchPlan(
            operation = "organizeImports",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = false,
            summary = "Organize imports in ${affected.size} file(s).",
            affectedFiles = affected,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = listOf("Unused import detection requires full type resolution and is not performed in this MVP."),
            riskLevel = RiskLevel.LOW,
        )
    }

    fun previewSingleFile(snapshot: ProjectSnapshot, relativePath: Path): PatchPlan =
        preview(snapshot, listOf(relativePath))

    // ── private ──────────────────────────────────────────────────────────────

    private fun organizeFile(file: SourceFile): FileEdit.Modify? {
        val content = file.content
        val imports = JavaLexer.extractImports(content)
        if (imports.isEmpty()) return null

        val filePackage = JavaPackageUtil.extractPackage(content)

        // Remove same-package and duplicates
        val seen = linkedSetOf<String>()
        val kept = imports.filter { imp ->
            val pkg = imp.name.substringBeforeLast('.', "")
            val isSamePkg = !imp.isStatic && pkg == filePackage && filePackage.isNotEmpty()
            val isNew = seen.add(imp.name + if (imp.isStatic) ":static" else "")
            !isSamePkg && isNew
        }

        val sorted = kept.sortedWith(compareBy({ if (it.isStatic) 1 else 0 }, { groupOf(it.name) }, { it.name }))
        val newImportBlock = sorted.joinToString("\n") { it.text }

        // Compute the full range of the existing import block
        val blockStart = imports.minOf { it.startOffset }
        val blockEnd = imports.maxOf { it.endOffset }

        // Trim trailing newline that belongs to the block
        val adjustedEnd = if (blockEnd < content.length && content[blockEnd] == '\n') blockEnd + 1 else blockEnd

        val startPos = TextEdits.positionForOffset(content, blockStart)
        val endPos = TextEdits.positionForOffset(content, adjustedEnd)

        val replacement = if (newImportBlock.isEmpty()) "" else "$newImportBlock\n"

        if (content.substring(blockStart, adjustedEnd) == replacement) return null // nothing changed

        return FileEdit.Modify(file.path, listOf(TextEdit(SourceRange(startPos, endPos), replacement)))
    }

    private fun groupOf(importName: String): Int = when {
        importName.startsWith("java.") -> 0
        importName.startsWith("javax.") -> 1
        importName.startsWith("jakarta.") -> 2
        importName.startsWith("org.") -> 3
        importName.startsWith("com.") -> 4
        else -> 5
    }
}

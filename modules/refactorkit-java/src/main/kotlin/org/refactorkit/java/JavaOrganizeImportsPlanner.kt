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
 * - Unused exact imports are removed when clean JDT binding evidence proves that
 *   the imported binding has no non-import use in the file.
 * - Wildcard and unresolved imports are preserved.
 */
class JavaOrganizeImportsPlanner {

    fun preview(snapshot: ProjectSnapshot, filePaths: List<Path>): PatchPlan {
        val edits = mutableListOf<FileEdit>()
        val affected = mutableSetOf<Path>()
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        var removedUnused = 0
        var semanticFiles = 0
        var fallbackFiles = 0

        for (relativePath in filePaths) {
            val file = snapshot.files.find { it.path == relativePath } ?: continue
            val hasFileWarnings = analysis.warnings.any { it.path == file.path }
            val bindingUses = if (hasFileWarnings) null else analysis.bindingUses.filter { it.path == file.path }
            if (bindingUses == null) fallbackFiles++ else semanticFiles++
            val result = organizeFile(file, bindingUses)
            removedUnused += result.removedUnused
            val modify = result.edit ?: continue
            edits += modify
            affected.add(relativePath)
        }

        val warnings = buildList {
            if (semanticFiles > 0) {
                add("JDT binding evidence checked exact imports in $semanticFiles file(s) and removed $removedUnused unused import(s).")
            }
            if (fallbackFiles > 0) {
                add("JDT evidence was unclean in $fallbackFiles file(s); unused imports were preserved while lexical sorting, deduplication, and same-package removal continued.")
            }
            add("Wildcard and unresolved imports are preserved because their usage cannot be proven safely.")
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
            warnings = warnings,
            riskLevel = RiskLevel.LOW,
        )
    }

    fun previewSingleFile(snapshot: ProjectSnapshot, relativePath: Path): PatchPlan =
        preview(snapshot, listOf(relativePath))

    // ── private ──────────────────────────────────────────────────────────────

    private fun organizeFile(
        file: SourceFile,
        bindingUses: List<JdtJavaSemanticBindingUse>?,
    ): OrganizeResult {
        val content = file.content
        val imports = JavaLexer.extractImports(content)
        if (imports.isEmpty()) return OrganizeResult(null, 0)

        val filePackage = JavaPackageUtil.extractPackage(content)

        // Remove same-package imports, duplicates, and binding-proven unused exact imports.
        val seen = linkedSetOf<String>()
        var removedUnused = 0
        val kept = imports.filter { imp ->
            val pkg = imp.name.substringBeforeLast('.', "")
            val isSamePkg = !imp.isStatic && pkg == filePackage && filePackage.isNotEmpty()
            val isNew = seen.add(imp.name + if (imp.isStatic) ":static" else "")
            val isUnused = isNew && !isSamePkg && isProvenUnused(file, imp, bindingUses)
            if (isUnused) removedUnused++
            !isSamePkg && isNew && !isUnused
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

        if (content.substring(blockStart, adjustedEnd) == replacement) {
            return OrganizeResult(null, removedUnused)
        }

        return OrganizeResult(
            FileEdit.Modify(file.path, listOf(TextEdit(SourceRange(startPos, endPos), replacement))),
            removedUnused,
        )
    }

    private fun isProvenUnused(
        file: SourceFile,
        importStatement: ImportStatement,
        bindingUses: List<JdtJavaSemanticBindingUse>?,
    ): Boolean {
        if (bindingUses == null || importStatement.name.endsWith(".*")) return false
        val importedSimpleName = importStatement.name.substringAfterLast('.')
        val importLine = TextEdits.positionForOffset(file.content, importStatement.startOffset).line
        val importBinding = bindingUses.singleOrNull { use ->
            use.isImport && use.simpleName == importedSimpleName && use.line == importLine
        }?.bindingKey ?: return false
        return bindingUses.none { use -> !use.isImport && use.bindingKey == importBinding }
    }

    private data class OrganizeResult(
        val edit: FileEdit.Modify?,
        val removedUnused: Int,
    )

    private fun groupOf(importName: String): Int = when {
        importName.startsWith("java.") -> 0
        importName.startsWith("javax.") -> 1
        importName.startsWith("jakarta.") -> 2
        importName.startsWith("org.") -> 3
        importName.startsWith("com.") -> 4
        else -> 5
    }
}

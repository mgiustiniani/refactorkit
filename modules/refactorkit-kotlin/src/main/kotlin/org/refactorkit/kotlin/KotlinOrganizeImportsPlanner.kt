package org.refactorkit.kotlin

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import java.nio.file.Path

/** First bounded K5 Kotlin/JVM organize-imports row for explicit type imports. */
class KotlinOrganizeImportsPlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = kotlin.diagnostics(snapshot)

    fun preview(snapshot: ProjectSnapshot, path: Path): PatchPlan {
        val normalizedPath = path.normalize()
        val source = snapshot.files.singleOrNull { it.path.normalize() == normalizedPath }
            ?: return refused(snapshot, "kotlin.organizeImportsFileMissing", "Kotlin organize imports target is absent")
        if (source.languageId != "kotlin" || source.path.toString().endsWith(".kts")) return refused(
            snapshot, "kotlin.organizeImportsFileUnsupported", "Kotlin organize imports requires one saved .kt file",
        )
        val ownership = snapshot.owningBuildSourceRoots(source.path)
        if (ownership.isEmpty() || ownership.map { it.root.normalize() }.distinct().size != 1 ||
            ownership.any { it.generated || it.modelStatus != BuildModelStatus.AVAILABLE }) return refused(
            snapshot, "kotlin.organizeImportsSourceOwnershipUnavailable",
            "Kotlin organize imports requires one authoritative non-generated source root",
        )
        val before = when (val result = kotlin.compilerDiagnostics(snapshot)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.organizeImportsEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.organizeImportsEvidenceUnavailable", result.failure.message,
            )
        }
        if (before.symbolFailure != null || before.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.organizeImportsBaselineIncomplete",
            "Kotlin organize imports requires complete error-free K2 evidence", before.diagnostics,
        )
        val block = importBlock(source) ?: return refused(
            snapshot, "kotlin.organizeImportsShapeUnsupported",
            "Kotlin organize imports requires one uncommented contiguous explicit type-import block",
        )
        val declarationsByIdentity = before.declarations.entries.associateBy { it.value.jvmIdentity }
        val unused = mutableSetOf<ImportLine>()
        for (importLine in block.imports) {
            val internal = declarationsByIdentity[importLine.identity]
            val locations = if (internal != null) {
                before.usages.filter { it.targetId == internal.key }.map { it.location }
            } else {
                before.externalTypeUsages.filter { it.jvmBinaryName == importLine.identity }.map { it.location }
            }.filter { it.path.normalize() == normalizedPath }
            if (locations.count { it.range.start.line == importLine.line } != 1) return refused(
                snapshot, "kotlin.organizeImportsEvidenceMismatch",
                "K2 import evidence does not map one-to-one to the exact import line",
            )
            if (locations.all { it.range.start.line == importLine.line }) unused += importLine
        }
        val retained = block.imports.filterNot { it in unused }
            .sortedBy { it.directive.trim() }
        val newline = if ("\r\n" in source.content) "\r\n" else "\n"
        val replacement = retained.joinToString(newline) { it.directive }
        val original = source.content.substring(block.startOffset, block.endOffset)
        if (replacement == original) return refused(
            snapshot, "kotlin.organizeImportsNoChange", "Kotlin imports are already organized",
        )
        val edit = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(source.content, block.startOffset),
                TextEdits.positionForOffset(source.content, block.endOffset),
            ),
            replacement,
        )
        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path, listOf(edit))))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.organizeImportsPreviewInvalid", it.message ?: "Invalid import preview")
        }
        val after = when (val result = kotlin.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, result.reason.code ?: "kotlin.organizeImportsStagedEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, result.failure.code ?: "kotlin.organizeImportsStagedEvidenceUnavailable", result.failure.message,
            )
        }
        if (after.symbolFailure != null || after.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.organizeImportsDiagnosticsRegression",
            "Organized imports do not retain complete error-free K2 evidence", after.diagnostics,
        )
        return PatchPlan(
            operation = "organizeImports", status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash,
            confidence = 0.98, requiresUserApproval = true,
            summary = "Remove ${unused.size} unused Kotlin import(s) and sort ${retained.size} retained import(s).",
            affectedFiles = workspaceEdit.affectedFiles(), workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics, diagnosticsAfterPreview = after.diagnostics,
            riskLevel = RiskLevel.LOW, evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun importBlock(source: SourceFile): ImportBlock? {
        val packageMatch = Regex("(?m)^[ \\t]*package\\s+[A-Za-z_][A-Za-z0-9_.]*[ \\t]*$").find(source.content)
            ?: return null
        val importRegex = Regex(
            "(?m)^([ \\t]*import\\s+([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+)" +
                "(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?[ \\t]*)$",
        )
        val matches = importRegex.findAll(source.content).toList()
        if (matches.isEmpty()) return null
        val lines = source.content.lineSequence().toList()
        if (lines.any { it.trimStart().startsWith("import ") && !IMPORT_LINE.matches(it) }) return null
        val aliases = matches.mapNotNull { it.groups[3]?.value }
        if (aliases.size != aliases.distinct().size) return null
        val imports = matches.map { match ->
            val start = match.range.first
            val line = TextEdits.positionForOffset(source.content, start).line
            ImportLine(match.groups[1]!!.value, match.groups[2]!!.value, line, start, match.range.last + 1)
        }
        if (imports.first().line <= TextEdits.positionForOffset(source.content, packageMatch.range.first).line ||
            imports.zipWithNext().any { (left, right) -> right.line != left.line + 1 }) return null
        val packageLine = TextEdits.positionForOffset(source.content, packageMatch.range.first).line
        if (lines.subList(packageLine + 1, imports.first().line).any { it.isNotBlank() }) return null
        val nextNonBlank = lines.drop(imports.last().line + 1).firstOrNull { it.isNotBlank() }
        if (nextNonBlank?.trimStart()?.let { it.startsWith("//") || it.startsWith("/*") } == true) return null
        return ImportBlock(imports, imports.first().startOffset, imports.last().endOffset)
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "organizeImports", status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = diagnostics, warnings = listOf(message),
        riskLevel = RiskLevel.LOW, evidence = RefactoringEvidence.NATIVE_AST, refusalCode = code,
    )

    private data class ImportBlock(
        val imports: List<ImportLine>,
        val startOffset: Int,
        val endOffset: Int,
    )

    private data class ImportLine(
        val directive: String,
        val identity: String,
        val line: Int,
        val startOffset: Int,
        val endOffset: Int,
    )

    companion object {
        private val IMPORT_LINE = Regex(
            "[ \\t]*import\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+" +
                "(?:\\s+as\\s+[A-Za-z_][A-Za-z0-9_]*)?[ \\t]*",
        )
    }
}

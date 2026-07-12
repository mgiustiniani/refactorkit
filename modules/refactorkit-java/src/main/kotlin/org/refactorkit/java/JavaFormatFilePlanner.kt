package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.ToolFactory
import org.eclipse.jdt.core.formatter.CodeFormatter
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants
import org.eclipse.text.edits.DeleteEdit
import org.eclipse.text.edits.InsertEdit
import org.eclipse.text.edits.ReplaceEdit
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** Plans deterministic Eclipse-JDT formatting of one Java compilation unit. */
class JavaFormatFilePlanner(
    private val diagnosticsAdapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun preview(snapshot: ProjectSnapshot, relativePath: Path): PatchPlan {
        val normalizedPath = relativePath.normalize()
        val file = snapshot.files.singleOrNull { it.path.normalize() == normalizedPath }
            ?: return refused(snapshot, "Java source file not found in snapshot: $normalizedPath", "format.fileNotFound")
        if (file.languageId != "java" || !normalizedPath.toString().endsWith(".java")) {
            return refused(snapshot, "File is not a Java compilation unit: $normalizedPath", "format.unsupportedLanguage")
        }
        JavaGeneratedSourcePolicy.reason(file)?.let { reason ->
            return refused(snapshot, "Generated source cannot be formatted: $normalizedPath ($reason)", "java.generatedSource")
        }

        val beforeDiagnostics = diagnosticsAdapter.diagnostics(snapshot)
        val syntaxErrors = beforeDiagnostics.filter { diagnostic ->
            diagnostic.severity == Diagnostic.Severity.ERROR &&
                diagnostic.category == org.refactorkit.core.DiagnosticCategory.SYNTAX &&
                diagnostic.location?.path?.normalize() == normalizedPath
        }
        if (syntaxErrors.isNotEmpty()) {
            return PatchPlan(
                operation = "formatFile",
                status = PatchStatus.REFUSED,
                snapshotHash = snapshot.hash,
                confidence = 0.0,
                summary = "Cannot format $normalizedPath: ${syntaxErrors.size} syntax error(s).",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                diagnosticsBefore = syntaxErrors,
                warnings = listOf("Fix Java syntax errors before formatting."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val style = formatterOptions(snapshot, normalizedPath)
        val hasBom = file.content.startsWith('\uFEFF')
        val source = if (hasBom) file.content.substring(1) else file.content
        val lineSeparator = if (source.contains("\r\n")) "\r\n" else "\n"
        val formatter = ToolFactory.createCodeFormatter(style.options)
        val formatterEdit = formatter.format(
            CodeFormatter.K_COMPILATION_UNIT,
            source,
            0,
            source.length,
            0,
            lineSeparator,
        ) ?: return refused(snapshot, "Eclipse JDT could not format $normalizedPath", "format.unavailable")
        val formattedBody = applyFormatterEdits(source, formatterEdit)
        val formatted = (if (hasBom) "\uFEFF" else "") + formattedBody
        val warnings = listOf(
            "Formatter backend: Eclipse JDT ${JavaCore::class.java.`package`.implementationVersion ?: "3.44.0"}; style=${style.description}.",
            "Formatting does not organize imports; run organize-imports as a separate reviewed operation.",
        )
        if (formatted == file.content) {
            return PatchPlan(
                operation = "formatFile",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "$normalizedPath is already formatted.",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(),
                diagnosticsBefore = beforeDiagnostics,
                diagnosticsAfterPreview = beforeDiagnostics,
                warnings = warnings,
                riskLevel = RiskLevel.LOW,
                evidence = RefactoringEvidence.STRUCTURAL,
            )
        }

        val bomOffset = if (hasBom) 1 else 0
        val minimalEdits = formatterLeafEdits(formatterEdit).mapNotNull { formatterLeaf ->
            val replacement = when (formatterLeaf) {
                is ReplaceEdit -> formatterLeaf.text
                is InsertEdit -> formatterLeaf.text
                is DeleteEdit -> ""
                else -> return@mapNotNull null
            }
            TextEdit(
                TextEdits.rangeForOffset(file.content, formatterLeaf.offset + bomOffset, formatterLeaf.length),
                replacement,
            )
        }
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(normalizedPath, minimalEdits)))
        val staged = WorkspaceEditSimulator.apply(snapshot, edit)
        val afterDiagnostics = diagnosticsAdapter.diagnostics(staged)
        return PatchPlan(
            operation = "formatFile",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Format Java compilation unit $normalizedPath.",
            affectedFiles = setOf(normalizedPath),
            workspaceEdit = edit,
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            warnings = warnings,
            riskLevel = RiskLevel.LOW,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun formatterOptions(snapshot: ProjectSnapshot, path: Path): FormatterStyle {
        val module = snapshot.modules
            .filter { module -> module.sourceRoots.any { root -> path.startsWith(root.normalize()) } }
            .maxByOrNull { module -> module.sourceRoots.maxOfOrNull { it.nameCount } ?: 0 }
        val options = DefaultCodeFormatterConstants.getEclipseDefaultSettings().toMutableMap()
        val sourceLevel = module?.languageSettings?.get("java.sourceLevel") ?: JavaCore.VERSION_1_8
        options[JavaCore.COMPILER_SOURCE] = sourceLevel
        options[JavaCore.COMPILER_COMPLIANCE] = sourceLevel
        options[JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM] = sourceLevel

        val moduleRoot = module?.root?.toAbsolutePath()?.normalize()
        val prefs = moduleRoot?.resolve(".settings/org.eclipse.jdt.core.prefs")
            ?.takeIf { Files.isRegularFile(it) }
        if (prefs != null) {
            val properties = Properties()
            Files.newBufferedReader(prefs, Charsets.UTF_8).use(properties::load)
            properties.stringPropertyNames()
                .filter { it.startsWith("org.eclipse.jdt.core.formatter.") }
                .sorted()
                .forEach { key -> options[key] = properties.getProperty(key) }
            return FormatterStyle(options, snapshot.workspace.root.relativize(prefs).toString())
        }
        return FormatterStyle(options, "RefactorKit versioned Eclipse default")
    }

    private fun formatterLeafEdits(root: org.eclipse.text.edits.TextEdit): List<org.eclipse.text.edits.TextEdit> {
        val leaves = mutableListOf<org.eclipse.text.edits.TextEdit>()
        fun collect(edit: org.eclipse.text.edits.TextEdit) {
            if (edit.hasChildren()) edit.children.forEach(::collect) else leaves += edit
        }
        collect(root)
        return leaves
    }

    private fun applyFormatterEdits(source: String, root: org.eclipse.text.edits.TextEdit): String {
        val result = StringBuilder(source)
        formatterLeafEdits(root).sortedByDescending { it.offset }.forEach { edit ->
            when (edit) {
                is ReplaceEdit -> result.replace(edit.offset, edit.offset + edit.length, edit.text)
                is InsertEdit -> result.insert(edit.offset, edit.text)
                is DeleteEdit -> result.delete(edit.offset, edit.offset + edit.length)
            }
        }
        return result.toString()
    }

    private fun refused(snapshot: ProjectSnapshot, message: String, code: String) = PatchPlan(
        operation = "formatFile",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code)),
        warnings = listOf(message),
        riskLevel = RiskLevel.MEDIUM,
        evidence = RefactoringEvidence.STRUCTURAL,
    )

    private data class FormatterStyle(
        val options: MutableMap<String, String>,
        val description: String,
    )
}

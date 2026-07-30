package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import java.nio.file.Path

/**
 * Bounded Kotlin extract method using K2 compiler evidence.
 * Extracts a selected range of statements/expressions into a new private method.
 */
class KotlinExtractMethodPlanner(
    private val kotlin: KotlinLanguageAdapter,
) {
    fun preview(
        snapshot: ProjectSnapshot,
        filePath: Path,
        startLine: Int,
        endLine: Int,
        methodName: String,
    ): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path.normalize() == filePath.normalize() }
            ?: return refused(snapshot, "kotlin.extractFileMissing", "File not found: $filePath")
        if (source.languageId != "kotlin") return refused(
            snapshot, "kotlin.extractUnsupported", "Kotlin extract requires one saved .kt file",
        )
        if (!isValidKotlinIdentifier(methodName)) return refused(
            snapshot, "kotlin.extractInvalidName", "Invalid Kotlin method name: $methodName",
        )

        val before = when (val result = kotlin.compilerDiagnostics(snapshot)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, "kotlin.extractEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, "kotlin.extractEvidenceUnavailable", result.failure.message,
            )
        }
        if (before.symbolFailure != null || before.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.extractBaselineIncomplete",
            "Kotlin extract requires complete error-free K2 evidence", before.diagnostics,
        )

        val content = source.content
        val lines = content.lineSequence().toList()
        if (startLine < 1 || endLine > lines.size || startLine > endLine) return refused(
            snapshot, "kotlin.extractInvalidRange",
            "Extract range $startLine-$endLine is outside the file (${lines.size} lines)",
        )

        // Find the enclosing function/block
        val extractStart = content.linesOffset(startLine)
        val extractEnd = content.linesOffset(endLine)
        val extractedCode = content.substring(extractStart, extractEnd)

        // Check for return statements that would need refactoring
        val hasReturn = Regex("""\breturn\b""").find(extractedCode) != null
        if (hasReturn) return refused(
            snapshot, "kotlin.extractReturnUnsupported",
            "Extract method does not yet support code with return statements",
        )

        // Determine visibility and enclosing class
        val classRegex = Regex("""(?m)^\s*(?:public|internal|private|protected)?\s*(?:class|object|fun)\s+(\w+)""")
        val classMatch = classRegex.find(content) ?: return refused(
            snapshot, "kotlin.extractNoEnclosingClass",
            "No enclosing class or function found for extraction",
        )

        // Build the extracted method
        val visibility = "private"
        val returnType = "Unit"
        val extractedMethod = buildString {
            append("\n")
            append("    $visibility fun $methodName(): $returnType {\n")
            append("        ")
            append(extractedCode.trimEnd().replace("\n", "\n        "))
            append("\n    }\n")
        }

        // Insert the extracted method before the enclosing class end
        val classEnd = content.lastIndexOf('}')
        if (classEnd < 0) return refused(snapshot, "kotlin.extractNoClassEnd",
            "Cannot locate enclosing class closing brace")

        val edits = mutableListOf<TextEdit>()

        // Replace selected code with call to extracted method
        val startPos = TextEdits.positionForOffset(content, extractStart)
        val endPos = TextEdits.positionForOffset(content, extractEnd)
        edits.add(TextEdit(
            SourceRange(startPos, endPos),
            "$methodName()",
        ))

        // Insert extracted method before closing brace
        val classEndLine = TextEdits.positionForOffset(content, classEnd).line
        val classEndCol = classEnd - content.substring(0, classEnd).lastIndexOf('\n') - 1
        edits.add(TextEdit(
            SourceRange(SourcePosition(classEndLine, classEndCol), SourcePosition(classEndLine, classEndCol)),
            extractedMethod,
        ))

        val workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path, edits)))
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, workspaceEdit) }.getOrElse {
            return refused(snapshot, "kotlin.extractPreviewInvalid", it.message ?: "Invalid preview")
        }
        val after = when (val result = kotlin.compilerDiagnostics(staged)) {
            is KotlinCompilerDiagnosticsResult.Available -> result
            is KotlinCompilerDiagnosticsResult.Refused -> return refused(
                snapshot, "kotlin.extractStagedEvidenceUnavailable", result.reason.message,
            )
            is KotlinCompilerDiagnosticsResult.Error -> return refused(
                snapshot, "kotlin.extractStagedEvidenceUnavailable", result.failure.message,
            )
        }
        if (after.symbolFailure != null || after.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }) return refused(
            snapshot, "kotlin.extractDiagnosticsRegression",
            "Extract method introduces compiler diagnostics", after.diagnostics,
        )

        return PatchPlan(
            operation = "extractMethod",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.9,
            requiresUserApproval = true,
            summary = "Extract method '$methodName' in ${filePath.fileName}",
            affectedFiles = setOf(filePath),
            workspaceEdit = workspaceEdit,
            diagnosticsBefore = before.diagnostics,
            diagnosticsAfterPreview = after.diagnostics,
            warnings = listOf(
                "Kotlin extract method creates a private method. Verify call site and variable references.",
            ),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.NATIVE_AST,
        )
    }

    private fun isValidKotlinIdentifier(name: String): Boolean = name.isNotBlank() &&
        name.all { it.isLetterOrDigit() || it == '_' } &&
        name[0].isLetter() || name[0] == '_'

    private fun String.linesOffset(line: Int): Int {
        val lines = this.lineSequence().toList()
        return lines.take(line - 1).sumOf { it.length + 1 }
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        message: String,
        diagnostics: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "extractMethod",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsAfterPreview = diagnostics,
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = code,
    )
}

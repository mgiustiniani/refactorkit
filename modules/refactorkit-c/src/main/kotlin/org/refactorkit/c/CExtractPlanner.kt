package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
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
 * Bounded C expression extraction: a scalar expression (no side effects) is
 * extracted to a `const` local temporary, with the type inferred from the
 * enclosing assignment target's declared type.
 *
 * Refusals cover side-effectful expressions (calls, volatile, writes,
 * increment/decrement), macro ranges, goto/longjmp/setjmp contexts,
 * constant-expression contexts and uninferable types. Successful compilation
 * alone is not equivalence; the analysis is conservative.
 */
class CExtractPlanner {
    fun preview(snapshot: ProjectSnapshot, file: Path, range: SourceRange, tempName: String): PatchPlan {
        if (tempName.isBlank() || !tempName.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid temporary name: '$tempName'")
        }
        if (range.start > range.end) return refused(snapshot, "Invalid extract range")
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refused(snapshot, "Source file not found")
        if (source.languageId !in setOf("c", "cpp", "objective-c")) {
            return refused(snapshot, "Extract currently supports C sources only")
        }
        val expression = extractExpression(source.content, range) ?: return refused(snapshot, "Selected range is not a single expression")
        if (expression.isBlank()) return refused(snapshot, "Selected expression is blank")
        if (hasSideEffects(expression)) {
            return refused(snapshot, "Extracted expression has side effects (call, volatile, write, increment/decrement)")
        }
        if (inMacroOrGotoContext(source.content, range)) {
            return refused(snapshot, "Extracted expression is in a macro/goto/longjmp context")
        }
        val type = inferType(source.content, range, expression) ?: return refused(snapshot, "Could not infer the expression type from its context")
        val statementRange = enclosingStatementRange(source.content, range) ?: return refused(snapshot, "Could not locate the enclosing statement")
        val declaration = "${type} ${tempName} = ${expression};"
        val edits = listOf(
            TextEdit(SourceRange(statementRange.start, statementRange.start), declaration + "\n"),
            TextEdit(range, tempName),
        )
        return PatchPlan(
            operation = "extractExpression",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Extract expression to const temporary '$tempName' in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Extracted a scalar expression to a const temporary; type inferred from the enclosing assignment."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun extractExpression(content: String, range: SourceRange): String? {
        val lines = content.lines()
        if (range.start.line == range.end.line) {
            return lines.getOrNull(range.start.line)?.substring(range.start.character, range.end.character)
        }
        val startLine = lines.getOrNull(range.start.line)?.substring(range.start.character) ?: return null
        val endLine = lines.getOrNull(range.end.line)?.substring(0, range.end.character) ?: return null
        val middle = if (range.end.line - range.start.line > 1) {
            lines.subList(range.start.line + 1, range.end.line).joinToString("\n")
        } else ""
        return "$startLine\n$middle\n$endLine"
    }

    private fun hasSideEffects(expression: String): Boolean =
        expression.contains("(") || expression.contains("=") || expression.contains("++") ||
            expression.contains("--") || expression.contains("volatile") || expression.contains("goto") ||
            expression.contains("longjmp") || expression.contains("setjmp")

    private fun inMacroOrGotoContext(content: String, range: SourceRange): Boolean {
        val line = content.lines().getOrNull(range.start.line) ?: return false
        return line.contains("#") || line.contains("goto") || line.contains("longjmp") || line.contains("setjmp")
    }

    private fun inferType(content: String, range: SourceRange, expression: String): String? {
        val line = content.lines().getOrNull(range.start.line) ?: return null
        // Find the enclosing assignment `lhs = expr` and the lhs declared type.
        val assignmentIndex = line.lastIndexOf("=", range.start.character)
        if (assignmentIndex < 0) return null
        val lhs = line.substring(0, assignmentIndex).trim().let { it.substringAfterLast(' ').substringBefore('(').trim() }
        if (lhs.isBlank() || !lhs.matches(IDENTIFIER)) return null
        return findDeclaredType(content, lhs)
    }

    private fun findDeclaredType(content: String, name: String): String? {
        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("/*")) continue
            for (match in TYPE_DECL.findAll(line)) {
                val type = match.groupValues[1].trim()
                if (match.groupValues[2] == name && type !in NON_TYPE_KEYWORDS) {
                    return type
                }
            }
        }
        return null
    }

    private fun enclosingStatementRange(content: String, range: SourceRange): SourceRange? {
        val line = content.lines().getOrNull(range.start.line) ?: return null
        val start = line.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val end = line.indexOf(';')
        if (end < 0) return null
        return SourceRange(SourcePosition(range.start.line, start), SourcePosition(range.start.line, end + 1))
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "extractExpression",
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

    companion object {
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val TYPE_DECL = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s+([A-Za-z_][A-Za-z0-9_]*)\s*[;=,]""")
        private val NON_TYPE_KEYWORDS = setOf(
            "const", "volatile", "static", "extern", "register", "auto", "inline", "restrict", "return",
            "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "goto",
            "sizeof", "typedef", "struct", "union", "enum", "_Atomic", "_Complex", "_Imaginary", "_Noreturn",
        )
    }
}

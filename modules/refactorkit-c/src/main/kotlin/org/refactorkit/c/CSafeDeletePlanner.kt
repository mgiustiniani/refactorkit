package org.refactorkit.c

import org.refactorkit.core.ExternalSemanticProcessManager
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
 * Bounded C safe delete: a C symbol (function, variable, or type) is deleted
 * only when no semantic references exist. `extern`/public symbols are refused
 * when a binary or external consumer is unavailable. Macro references, string
 * references and annotation-like references are not counted as proof.
 */
class CSafeDeletePlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C safe-delete planner is already started" }
        client.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { client.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            error("clangd did not open every C source file")
        }
        started = true
    }

    fun preview(snapshot: ProjectSnapshot, symbol: String): PatchPlan {
        if (!started) return refused(snapshot, "C safe-delete planner is not started")
        if (symbol.isBlank() || !symbol.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid symbol name: '$symbol'")
        }
        val definition = findDefinition(snapshot, symbol)
            ?: return refused(snapshot, "Symbol '$symbol' has no definition in this snapshot")
        if (definition.externOrPublic) {
            return refused(snapshot, "Symbol '$symbol' is extern/public; a binary or external consumer is unavailable, so deletion is refused")
        }
        val references = when (val result = client.references(definition.file, definition.line, definition.character)) {
            is CReferenceResult.Unavailable -> return refused(
                snapshot,
                "Semantic reference analysis for '$symbol' is unavailable; safe delete is refused",
            )
            is CReferenceResult.NotFound -> emptyList()
            is CReferenceResult.Found -> result.references
        }.filter { ref ->
            !(ref.file.normalize() == definition.file.normalize() &&
                ref.startLine >= definition.line && ref.startLine <= definition.endLine &&
                ref.startCharacter >= definition.character && ref.endCharacter <= definition.endCharacter)
        }
        if (references.isNotEmpty()) {
            return refused(snapshot, "Symbol '$symbol' has ${references.size} semantic reference(s); safe delete is refused")
        }
        val relFile = snapshot.workspace.root.relativize(definition.file).normalize()
        val edits = listOf(
            TextEdit(SourceRange(SourcePosition(definition.line, definition.character), SourcePosition(definition.endLine, definition.endCharacter)), ""),
        )
        return PatchPlan(
            operation = "safeDelete",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Safe delete symbol '$symbol' in $relFile",
            affectedFiles = setOf(relFile),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(relFile, edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Deleted a symbol with no semantic references; extern/public symbols are refused when a binary/external consumer is unavailable."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
        )
    }

    override fun close() {
        client.close()
        started = false
    }

    private fun findDefinition(snapshot: ProjectSnapshot, symbol: String): Definition? {
        for (file in snapshot.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }.sortedBy { it.path.toString() }) {
            val tokens = CTokenizer().tokenize(file.content)
            for (i in tokens.indices) {
                val t = tokens[i]
                if (t.type != CTokenType.IDENTIFIER || t.text != symbol) continue
                val prev = tokens.getOrNull(i - 1)
                val prev2 = tokens.getOrNull(i - 2)
                val next = tokens.getOrNull(i + 1)
                val isExtern = (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text == "extern") ||
                    (prev2 != null && prev2.type == CTokenType.IDENTIFIER && prev2.text == "extern")
                val isStatic = (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text == "static") ||
                    (prev2 != null && prev2.type == CTokenType.IDENTIFIER && prev2.text == "static")
                val externOrPublic = isExtern || !isStatic
                val isFunction = next != null && next.text == "("
                val isVariable = next != null && (next.text in setOf("=", ";", ",", ")")) ||
                    (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text in TYPE_KEYWORDS)
                if (isFunction || isVariable) {
                    val line = t.line - 1 // 0-based for clangd and SourcePosition
                    val char = lineStartCharacter(file.content, t.line, symbol)
                    val absFile = snapshot.workspace.root.resolve(file.path).normalize()
                    return Definition(absFile, line, char, line, lineEndCharacter(file.content, t.line), externOrPublic)
                }
            }
        }
        return null
    }

    private fun lineStartCharacter(content: String, line: Int, symbol: String): Int {
        val lineText = content.lines().getOrNull(line - 1) ?: return 0
        val idx = lineText.indexOf(symbol)
        return if (idx >= 0) idx else 0
    }

    private fun lineEndCharacter(content: String, line: Int): Int {
        val lineText = content.lines().getOrNull(line - 1) ?: return 0
        return lineText.length
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "safeDelete",
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

    private data class Definition(
        val file: Path,
        val line: Int, val character: Int,
        val endLine: Int, val endCharacter: Int,
        val externOrPublic: Boolean,
    )

    companion object {
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val TYPE_KEYWORDS = setOf(
            "int", "char", "long", "short", "unsigned", "signed", "float", "double", "void", "_Bool",
            "const", "volatile", "static", "extern", "register", "auto", "inline", "restrict",
        )
    }
}

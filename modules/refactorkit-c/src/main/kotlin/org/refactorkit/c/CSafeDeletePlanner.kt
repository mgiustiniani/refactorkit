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
    private var startFailure: String? = null

    /**
     * Computes the exact deletion range for a source-owned definition of [symbol].
     *
     * The range spans the whole declaration from the first storage/type token to the
     * terminating `;` (or the closing `}` of a function body), so no `static int` prefix
     * and no multiline body is left behind. Returns null when no definition is found.
     */
    internal fun deletionRange(content: String, symbol: String): SourceRange? {
        val tokens = CTokenizer().tokenize(content)
        val lines = content.lines()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type != CTokenType.IDENTIFIER || t.text != symbol) continue
            val next = tokens.getOrNull(i + 1)
            val prev = tokens.getOrNull(i - 1)
            val prev2 = tokens.getOrNull(i - 2)
            val isFunction = next != null && next.text == "("
            val isVariable = next != null && (next.text in setOf("=", ";", ",", ")")) ||
                (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text in TYPE_KEYWORDS)
            if (!isFunction && !isVariable) continue

            val startLine = declarationStartLine(tokens, i)
            val startChar = declarationStartChar(lines, startLine, i, tokens)
            if (isFunction) {
                val open = tokens.getOrNull(i + 1) ?: continue
                val closeParen = matchingToken(tokens, i + 1, "(", ")") ?: continue
                val afterParams = tokens.getOrNull(closeParen + 1)
                if (afterParams != null && afterParams.text == "{") {
                    val bodyClose = matchingToken(tokens, closeParen + 1, "{", "}") ?: continue
                    val endLine = tokens[bodyClose].line - 1
                    val endChar = (lines.getOrNull(endLine)?.length ?: 0)
                    return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
                }
                val endLine = tokens[closeParen].line - 1
                val endChar = semicolonEndChar(lines, tokens, closeParen)
                return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
            }
            val endLine = t.line - 1
            val endChar = semicolonEndChar(lines, tokens, i)
            return SourceRange(SourcePosition(startLine, startChar), SourcePosition(endLine, endChar))
        }
        return null
    }

    private fun declarationStartLine(tokens: List<CToken>, symbolIndex: Int): Int {
        var start = symbolIndex
        var j = symbolIndex - 1
        while (j >= 0) {
            val token = tokens[j]
            val isPrefix = token.type == CTokenType.IDENTIFIER &&
                (token.text in TYPE_KEYWORDS || token.text.matches(IDENTIFIER))
            if (!isPrefix) break
            start = j
            j--
        }
        return tokens[start].line - 1
    }

    private fun declarationStartChar(lines: List<String>, startLine: Int, symbolIndex: Int, tokens: List<CToken>): Int {
        val lineText = lines.getOrNull(startLine) ?: return 0
        val first = lineText.indexOfFirst { !it.isWhitespace() }
        return if (first >= 0) first else 0
    }

    private fun semicolonEndChar(lines: List<String>, tokens: List<CToken>, fromIndex: Int): Int {
        var j = fromIndex + 1
        while (j < tokens.size) {
            if (tokens[j].text == ";") {
                val line = tokens[j].line - 1
                val lineText = lines.getOrNull(line) ?: return 0
                return lineText.length
            }
            j++
        }
        val line = tokens[fromIndex].line - 1
        return lines.getOrNull(line)?.length ?: 0
    }

    private fun matchingToken(tokens: List<CToken>, openIndex: Int, open: String, close: String): Int? {
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].text) {
                open -> depth++
                close -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C safe-delete planner is already started" }
        client.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { client.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            startFailure = "clangd did not open every C source file"
            return
        }
        started = true
    }

    /** Returns the typed start failure, or null when the planner started successfully. */
    fun startFailure(): String? = startFailure

    fun preview(snapshot: ProjectSnapshot, symbol: String): PatchPlan {
        if (!started) return refused(snapshot, startFailure ?: "C safe-delete planner is not started")
        if (symbol.isBlank() || !symbol.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid symbol name: '$symbol'")
        }
        val seed = findDefinition(snapshot, symbol)
            ?: return refused(snapshot, "Symbol '$symbol' has no definition in this snapshot")
        if (seed.externOrPublic) {
            return refused(snapshot, "Symbol '$symbol' is extern/public; a binary or external consumer is unavailable, so deletion is refused")
        }
        // Canonicalize the token seed to clangd's binding-matched declaration. If clangd
        // cannot confirm a single declaration, the deletion target is not proven and the
        // operation is refused rather than trusting a token-coincidence position.
        val resolved = when (val res = client.definition(seed.file, seed.line, seed.character)) {
            is CClangdSemanticResult.Found -> res.definition
            is CClangdSemanticResult.NotFound -> return refused(
                snapshot,
                "clangd could not resolve a declaration for '$symbol'; safe delete is refused",
            )
            is CClangdSemanticResult.Refused -> return refused(
                snapshot,
                "clangd refused to resolve '$symbol': ${res.diagnostics.firstOrNull()?.message ?: "unavailable"}",
            )
        }
        val references = when (val result = client.references(resolved.file, resolved.startLine, resolved.startCharacter)) {
            is CReferenceResult.Unavailable -> return refused(
                snapshot,
                "Semantic reference analysis for '$symbol' is unavailable; safe delete is refused",
            )
            is CReferenceResult.NotFound -> emptyList()
            is CReferenceResult.Found -> result.references
        }.filter { ref ->
            !(ref.file.normalize() == resolved.file.normalize() &&
                ref.startLine >= resolved.startLine && ref.startLine <= resolved.endLine &&
                ref.startCharacter >= resolved.startCharacter && ref.endCharacter <= resolved.endCharacter)
        }
        if (references.isNotEmpty()) {
            return refused(snapshot, "Symbol '$symbol' has ${references.size} semantic reference(s); safe delete is refused")
        }
        val relFile = snapshot.workspace.root.relativize(resolved.file).normalize()
        val sourceContent = snapshot.files.singleOrNull { it.path.normalize() == relFile.normalize() ||
            snapshot.workspace.root.resolve(it.path).normalize() == resolved.file.normalize() }?.content
        val range = sourceContent?.let { deletionRange(it, symbol) }
            ?: SourceRange(SourcePosition(resolved.startLine, resolved.startCharacter), SourcePosition(resolved.endLine, resolved.endCharacter))
        val edits = listOf(
            TextEdit(range, ""),
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
                    val char = t.column // token's own column, not a first-substring match
                    val absFile = snapshot.workspace.root.resolve(file.path).normalize()
                    return Definition(absFile, line, char, line, lineEndCharacter(file.content, t.line), externOrPublic)
                }
            }
        }
        return null
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

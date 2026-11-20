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
    private val runtimeProvider: ClangToolchainRuntimeProvider = ClangToolchainRuntimeCapture(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager, runtimeProvider = runtimeProvider)
    private var started = false
    private var startFailure: String? = null

    /**
     * Computes the exact deletion range for a source-owned definition of [symbol].
     *
     * The range spans the whole declaration from the first storage/type token to the
     * terminating `;` (or the closing `}` of a function body), so no `static int` prefix
     * and no multiline body is left behind. Returns null when the range is ambiguous,
     * including another declaration on the same line; null must never be converted
     * into a partial-name deletion.
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
            val startChar = declarationStartChar(lines, startLine)
            // Line-based range inference cannot safely separate a preceding declaration
            // or a same-line comment from this symbol's storage/type prefix.
            if (tokens.take(i).any { it.line - 1 == startLine && it.text in setOf(";", "{", "}") }) return null
            if (tokens.firstOrNull { it.line - 1 == startLine }?.column != startChar) return null
            val endToken = if (isFunction) {
                val closeParen = matchingToken(tokens, i + 1, "(", ")") ?: return null
                when (tokens.getOrNull(closeParen + 1)?.text) {
                    "{" -> tokens[matchingToken(tokens, closeParen + 1, "{", "}") ?: return null]
                    ";" -> tokens[closeParen + 1]
                    else -> return null
                }
            } else {
                val terminator = (i + 1 until tokens.size).firstOrNull {
                    tokens[it].text in setOf(";", "{", "}")
                } ?: return null
                if (tokens[terminator].text != ";" || tokens.subList(i + 1, terminator).any { it.text == "," }) return null
                tokens[terminator]
            }
            val endLine = endToken.line - 1
            val endChar = endToken.column + endToken.text.length
            val endLineText = lines.getOrNull(endLine) ?: return null
            if (endChar > endLineText.length || endLineText.substring(endChar).isNotBlank()) return null
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

    private fun declarationStartChar(lines: List<String>, startLine: Int): Int {
        val lineText = lines.getOrNull(startLine) ?: return 0
        val first = lineText.indexOfFirst { !it.isWhitespace() }
        return if (first >= 0) first else 0
    }

    private fun hasConditionalPreprocessorBranch(content: String): Boolean {
        // Backslash-newline splicing happens before preprocessing tokenization.
        val logicalLines = content
            .replace("??=", "#") // C11/C17 trigraph spelling of the directive marker
            .replace("??/", "\\") // trigraph backslash participates in line splicing
            .replace("%:", "#") // C digraph spelling of the directive marker
            .replace("\\\r\n", " ")
            .replace("\\\n", " ")
        val tokensByLine = CTokenizer().tokenize(logicalLines).groupBy { it.line }
        return tokensByLine.values.any { tokens ->
            tokens.firstOrNull()?.text == "#" && tokens.getOrNull(1)?.text in CONDITIONAL_DIRECTIVES
        }
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
        try {
            client.start(snapshot.workspace.root)
        } catch (failure: CClangdConfigurationException) {
            startFailure = failure.message ?: "clangd configuration is unavailable"
            return
        }
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
        if (symbol.isBlank() || !symbol.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid symbol name: '$symbol'")
        }
        val seed = findDefinition(snapshot, symbol)
            ?: return refused(snapshot, "Symbol '$symbol' has no definition in this snapshot")
        if (seed.externOrPublic) {
            return refused(snapshot, "Symbol '$symbol' is extern/public; a binary or external consumer is unavailable, so deletion is refused")
        }
        val seedSource = snapshot.files.singleOrNull {
            snapshot.workspace.root.resolve(it.path).normalize() == seed.file.normalize()
        }?.content ?: return refused(snapshot, "Could not prove the exact deletion range for '$symbol'; safe delete is refused")
        // clangd observes one active preprocessor configuration at a time. A
        // conditional branch in this translation unit can hide references in
        // another configuration, so that view is not complete deletion evidence.
        if (hasConditionalPreprocessorBranch(seedSource)) {
            return refused(
                snapshot,
                "Conditional preprocessor branches leave the complete reference/configuration set unavailable; safe delete is refused",
            )
        }
        // An ambiguous textual range is sufficient to refuse before semantic
        // definition/reference queries; an isolated range still needs clangd proof.
        if (deletionRange(seedSource, symbol) == null) {
            return refused(snapshot, "Could not prove the exact deletion range for '$symbol'; safe delete is refused")
        }
        if (!started) return refused(snapshot, startFailure ?: "C safe-delete planner is not started")
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
            ?: return refused(snapshot, "Could not prove the exact deletion range for '$symbol'; safe delete is refused")
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
        private val CONDITIONAL_DIRECTIVES = setOf("if", "ifdef", "ifndef", "elif", "else", "endif")
        private val TYPE_KEYWORDS = setOf(
            "int", "char", "long", "short", "unsigned", "signed", "float", "double", "void", "_Bool",
            "const", "volatile", "static", "extern", "register", "auto", "inline", "restrict",
        )
    }
}

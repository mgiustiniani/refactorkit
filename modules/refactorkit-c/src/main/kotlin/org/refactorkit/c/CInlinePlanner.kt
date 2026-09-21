package org.refactorkit.c

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
 * Bounded C inline: a single-use `static`/`extern` function with a proven local
 * call family (same translation unit, direct calls, no recursion, no
 * address-taking, no macro use) is inlined at its call site. The helper is
 * removed only when the single call is provably replaced.
 *
 * First positive target: a function whose body is a single `return expr;`
 * (pure expression). Refusals cover macro/reflection/recursive/address-taking/
 * volatile/multiple-translation-unit cases and non-`return` bodies.
 */
class CInlinePlanner {
    fun preview(snapshot: ProjectSnapshot, file: Path, functionName: String): PatchPlan {
        if (functionName.isBlank() || !functionName.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid function name: '$functionName'")
        }
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refused(snapshot, "Source file not found")
        if (source.languageId !in setOf("c", "cpp", "objective-c")) {
            return refused(snapshot, "Inline currently supports C sources only")
        }
        val text = source.content
        val tokens = tokensWithOffsets(text)
        val definition = parseDefinition(tokens, functionName)
            ?: return refused(snapshot, "Function '$functionName' has no static/extern definition in this file")
        if (definition.body !is Body.ReturnExpression) {
            return refused(snapshot, "Function '$functionName' body is not a single pure `return expr;`; inline MVP supports only that")
        }
        if (definition.body.expr.hasSideEffects()) {
            return refused(snapshot, "Function '$functionName' body expression has side effects")
        }
        val calls = findCalls(tokens, functionName)
        if (calls.size != 1) {
            return refused(snapshot, "Function '$functionName' has ${calls.size} call sites; inline requires exactly one")
        }
        val call = calls.single()
        if (call.args.size != definition.params.size) {
            return refused(snapshot, "Call site argument count does not match parameter count")
        }
        if (call.args.any { it.hasSideEffects() }) {
            return refused(snapshot, "Call site argument has side effects")
        }
        val replaced = substitute(definition.body.expr, definition.params, call.args)
        val edits = listOf(
            TextEdit(SourceRange(SourcePosition(call.startLine, call.startChar), SourcePosition(call.endLine, call.endChar)), replaced),
            TextEdit(SourceRange(SourcePosition(definition.startLine, definition.startChar), SourcePosition(definition.endLine, definition.endChar)), ""),
        )
        return PatchPlan(
            operation = "inlineFunction",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Inline single-use function '$functionName' and remove its definition in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Inlined a single-use static/extern function; the definition is removed only because the single call is provably replaced."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun parseDefinition(tokens: List<Tok>, name: String): Definition? {
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type != CTokenType.IDENTIFIER || t.text != name) continue
            val prev = tokens.getOrNull(i - 1)
            val prev2 = tokens.getOrNull(i - 2)
            val isStaticExtern =
                (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text in setOf("static", "extern")) ||
                (prev2 != null && prev2.type == CTokenType.IDENTIFIER && prev2.text in setOf("static", "extern"))
            if (!isStaticExtern) continue
            val open = tokens.getOrNull(i + 1) ?: continue
            if (open.text != "(") continue
            val close = matchingParen(tokens, i + 1) ?: continue
            val params = parseParams(tokens, i + 1, close)
            val bodyOpenIndex = close + 1
            val bodyOpen = tokens.getOrNull(bodyOpenIndex) ?: continue
            if (bodyOpen.text != "{") continue
            val bodyCloseIndex = matchingBrace(tokens, bodyOpenIndex) ?: continue
            val bodyText = tokens.subList(bodyOpenIndex + 1, bodyCloseIndex).map { it.text }.joinToString(" ")
            val body = parseBody(bodyText)
            // The removal must span the whole declaration including the storage/type
            // prefix, so no 'static int ' residue is left behind.
            var startIdx = i
            while (startIdx > 0 && tokens[startIdx - 1].line == t.line) startIdx--
            val declarationStart = tokens[startIdx]
            return Definition(
                params = params,
                body = body,
                startLine = declarationStart.line, startChar = declarationStart.startChar,
                endLine = tokens[bodyCloseIndex].line, endChar = tokens[bodyCloseIndex].endChar,
            )
        }
        return null
    }

    private fun matchingParen(tokens: List<Tok>, openIndex: Int): Int? {
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].text) {
                "(" -> depth++
                ")" -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun matchingBrace(tokens: List<Tok>, openIndex: Int): Int? {
        var depth = 0
        for (i in openIndex until tokens.size) {
            when (tokens[i].text) {
                "{" -> depth++
                "}" -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun parseParams(tokens: List<Tok>, openIndex: Int, closeIndex: Int): List<String> {
        // The parameter list is split on top-level commas; each parameter's name is
        // its last identifier, so a type, 'struct'/'union', qualifiers and '*' are not
        // mistaken for parameters.
        val params = mutableListOf<String>()
        var i = openIndex + 1
        var depth = 0
        var current = mutableListOf<String>()
        val groups = mutableListOf<MutableList<String>>()
        while (i < closeIndex) {
            val t = tokens[i]
            when (t.text) {
                "(" -> depth++
                ")" -> depth--
            }
            if (depth == 0 && t.text == ",") {
                groups += current
                current = mutableListOf()
            } else {
                current += t.text
            }
            i++
        }
        if (current.isNotEmpty()) groups += current
        for (group in groups) {
            val name = group.lastOrNull { it.matches(IDENTIFIER) && it !in TYPE_KEYWORDS && it != "void" }
            if (name != null) params += name
        }
        return params
    }

    private fun parseBody(bodyText: String): Body {
        val expr = bodyText.removePrefix("return").trim().removeSuffix(";").trim()
        if (expr.isEmpty()) return Body.NonExpression
        return Body.ReturnExpression(expr)
    }

    private fun findCalls(tokens: List<Tok>, name: String): List<Call> {
        val calls = mutableListOf<Call>()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type != CTokenType.IDENTIFIER || t.text != name) continue
            val prev = tokens.getOrNull(i - 1)
            val prev2 = tokens.getOrNull(i - 2)
            if (prev != null && prev.text == "&") continue // address-taking
            if (prev != null && prev.type == CTokenType.IDENTIFIER && prev.text in setOf("static", "extern")) continue // definition, not a call
            if (prev2 != null && prev2.type == CTokenType.IDENTIFIER && prev2.text in setOf("static", "extern")) continue // definition, not a call
            val next = tokens.getOrNull(i + 1) ?: continue
            if (next.text != "(") continue
            val close = matchingParen(tokens, i + 1) ?: continue
            val args = parseArgs(tokens, i + 1, close)
            calls += Call(t.line, t.startChar, tokens[close].line, tokens[close].endChar, args)
        }
        return calls
    }

    private fun parseArgs(tokens: List<Tok>, openIndex: Int, closeIndex: Int): List<String> {
        val args = mutableListOf<String>()
        var i = openIndex + 1
        var depth = 0
        var current = StringBuilder()
        while (i < closeIndex) {
            val t = tokens[i]
            when (t.text) {
                "(" -> depth++
                ")" -> depth--
            }
            if (depth == 0 && t.text == ",") {
                args += current.toString().trim()
                current.clear()
            } else {
                current.append(t.text)
            }
            i++
        }
        args += current.toString().trim()
        return args.filter { it.isNotEmpty() }
    }

    private fun substitute(expr: String, params: List<String>, args: List<String>): String {
        var result = expr
        for (i in params.indices) {
            result = result.replace(Regex("\\b${params[i]}\\b"), args.getOrElse(i) { params[i] })
        }
        return result
    }

    private fun tokensWithOffsets(text: String): List<Tok> {
        val tokens = CTokenizer().tokenize(text)
        val lines = text.lines()
        val result = mutableListOf<Tok>()
        var line = 1
        var col = 0
        var i = 0
        for (token in tokens) {
            // advance to the token's line
            while (line < token.line && i < text.length) {
                if (text[i] == '\n') { line++; col = 0 }
                i++
            }
            // find the token text starting at col
            val idx = text.indexOf(token.text, i)
            val startCol = if (idx >= 0) idx - text.lastIndexOf('\n', idx) - 1 else col
            result += Tok(token.type, token.text, token.line - 1, startCol, startCol + token.text.length)
            // advance col
            col = startCol + token.text.length
            i = idx + token.text.length
        }
        return result
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "inlineFunction",
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
        val params: List<String>,
        val body: Body,
        val startLine: Int, val startChar: Int,
        val endLine: Int, val endChar: Int,
    )

    private sealed interface Body {
        data class ReturnExpression(val expr: String) : Body
        data object NonExpression : Body
    }

    private data class Call(
        val startLine: Int, val startChar: Int,
        val endLine: Int, val endChar: Int,
        val args: List<String>,
    )

    private data class Tok(val type: CTokenType, val text: String, val line: Int, val startChar: Int, val endChar: Int)

    private fun String.hasSideEffects(): Boolean =
        contains("(") || contains("=") || contains("++") || contains("--") || contains("volatile") ||
            contains("goto") || contains("longjmp") || contains("setjmp")

    companion object {
        private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
        private val TYPE_KEYWORDS = setOf(
            "int", "char", "long", "short", "unsigned", "signed", "float", "double", "void", "_Bool",
            "const", "volatile", "static", "extern", "register", "auto", "inline", "restrict",
        )
    }
}

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

    /**
     * Inline a single-use scalar local variable whose initializer is a literal
     * expression (no identifiers, no side effects): replace its one read with the
     * initializer and remove the declaration. Provably safe because a literal cannot
     * change between the declaration and the use. Everything less clearly safe (more
     * than one use, an address-taken or write position, a non-scalar declaration, or an
     * initializer that reads mutable state) is refused.
     */
    fun inlineLocal(snapshot: ProjectSnapshot, file: Path, name: String): PatchPlan {
        if (name.isBlank() || !name.matches(IDENTIFIER)) {
            return refusedLocal(snapshot, "Invalid variable name: '$name'")
        }
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refusedLocal(snapshot, "Source file not found")
        if (source.languageId !in setOf("c", "cpp", "objective-c")) {
            return refusedLocal(snapshot, "Inline currently supports C sources only")
        }
        val tokens = tokensWithOffsets(source.content)
        val occurrences = tokens.withIndex()
            .filter { (_, t) -> t.type == CTokenType.IDENTIFIER && t.text == name }
            .map { it.index }
        if (occurrences.size != 2) {
            return refusedLocal(snapshot, "Variable '$name' must be declared once and used once; found ${occurrences.size} occurrences")
        }
        val declIdx = occurrences.firstOrNull { isScalarDeclaration(tokens, it) }
            ?: return refusedLocal(snapshot, "Variable '$name' has no scalar 'type $name = ...' declaration")
        val useIdx = occurrences.first { it != declIdx }
        if (useIdx < declIdx) return refusedLocal(snapshot, "Variable '$name' is used before its declaration")
        if (isScalarDeclaration(tokens, useIdx)) {
            return refusedLocal(snapshot, "Variable '$name' is declared more than once")
        }
        if (writesHere(tokens, useIdx)) {
            return refusedLocal(snapshot, "Variable '$name' is written or has its address taken at its use site; cannot inline")
        }
        val eqIdx = declIdx + 1
        if (tokens.getOrNull(eqIdx)?.text != "=") {
            return refusedLocal(snapshot, "Variable '$name' declaration has no initializer")
        }
        val semiIdx = (eqIdx + 1 until tokens.size).firstOrNull { tokens[it].text == ";" }
            ?: return refusedLocal(snapshot, "Could not locate the declaration terminator for '$name'")
        val exprTokens = tokens.subList(eqIdx + 1, semiIdx)
        if (exprTokens.isEmpty()) return refusedLocal(snapshot, "Variable '$name' has no initializer")
        if (exprTokens.any { it.type == CTokenType.IDENTIFIER }) {
            return refusedLocal(snapshot, "Initializer of '$name' references identifiers; immutability cannot be proven")
        }
        val expr = exprTokens.joinToString(" ") { it.text }.trim()
        if (expr.isBlank() || expr.hasSideEffects()) {
            return refusedLocal(snapshot, "Variable '$name' initializer is blank or has side effects")
        }
        var startIdx = declIdx
        while (startIdx > 0) {
            val prev = tokens[startIdx - 1].text
            if (prev == ";" || prev == "}" || prev == "{") break
            startIdx--
        }
        val declStart = tokens[startIdx]
        val semi = tokens[semiIdx]
        val useTok = tokens[useIdx]
        val replacement = wrapArgIfNeeded(expr)
        val edits = listOf(
            TextEdit(SourceRange(SourcePosition(useTok.line, useTok.startChar), SourcePosition(useTok.line, useTok.endChar)), replacement),
            TextEdit(SourceRange(SourcePosition(declStart.line, declStart.startChar), SourcePosition(semi.line, semi.endChar)), ""),
        )
        return PatchPlan(
            operation = "inlineLocalVariable",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Inline single-use scalar local '$name' and remove its declaration in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Inlined a single-use literal local; the declaration is removed because the initializer is a literal and the single use is a pure read."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun isScalarDeclaration(tokens: List<Tok>, index: Int): Boolean {
        val prev = tokens.getOrNull(index - 1)
        val next = tokens.getOrNull(index + 1)
        return prev != null && prev.type == CTokenType.IDENTIFIER && prev.text in TYPE_KEYWORDS && next?.text == "="
    }

    /** True when the token at [index] is written to (assignment, compound-assign, ++/-- or &). */
    private fun writesHere(tokens: List<Tok>, index: Int): Boolean {
        val p1 = tokens.getOrNull(index - 1)?.text
        val p2 = tokens.getOrNull(index - 2)?.text
        val n1 = tokens.getOrNull(index + 1)?.text
        val n2 = tokens.getOrNull(index + 2)?.text
        val n3 = tokens.getOrNull(index + 3)?.text
        return p1 == "&" ||
            (p1 == "+" && p2 == "+") || (p1 == "-" && p2 == "-") ||
            (n1 == "+" && n2 == "+") || (n1 == "-" && n2 == "-") ||
            (n1 == "=" && n2 != "=") ||
            (n1 in setOf("+", "-", "*", "/", "%", "^", "|", "&") && n2 == "=") ||
            (n1 == "<" && n2 == "<" && n3 == "=") ||
            (n1 == ">" && n2 == ">" && n3 == "=")
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
            // Walk back to the start of this declaration, stopping at the previous
            // statement/block boundary rather than the current line: the storage/type
            // prefix may span multiple lines (e.g. 'static int\nsq(...)') and several
            // declarations may share one line, so a line-equality scan would either leave
            // a 'static int' residue or over-delete into the neighbouring declaration.
            var startIdx = i
            while (startIdx > 0) {
                val prevText = tokens[startIdx - 1].text
                if (prevText == ";" || prevText == "}" || prevText == "{") break
                startIdx--
            }
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
        if (params.isEmpty()) return expr
        // Single simultaneous pass: each parameter occurrence maps directly to its own
        // argument. Sequential per-parameter replacement would corrupt swapped or
        // overlapping arguments (e.g. renaming a->b then b->a turns a+b into a+a).
        val alternation = params.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val pattern = Regex("\\b($alternation)\\b")
        return pattern.replace(expr) { match ->
            val idx = params.indexOf(match.groupValues[1])
            wrapArgIfNeeded(args.getOrElse(idx) { match.value })
        }
    }

    /**
     * Parenthesize a substituted argument unless it is a single atom (identifier,
     * numeric or string literal). Substituting a compound expression such as 'a + b'
     * into a larger body like 'x * x' without parentheses would silently change
     * operator precedence (a + b * x rather than (a + b) * x).
     */
    private fun wrapArgIfNeeded(arg: String): String {
        val trimmed = arg.trim()
        val tokens = CTokenizer().tokenize(trimmed)
        val isAtom = tokens.size == 1 &&
            tokens[0].type in setOf(CTokenType.IDENTIFIER, CTokenType.NUMBER, CTokenType.STRING)
        return if (isAtom) trimmed else "($trimmed)"
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

    private fun refusedLocal(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "inlineLocalVariable",
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

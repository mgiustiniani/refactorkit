package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path
import java.security.MessageDigest

/** Bounded inline-function proposals and compiler-proven single-use local inline. */
class CInlinePlanner(
    private val toolchain: ClangSemanticToolchain? = null,
    private val processManager: ExternalSemanticProcessManager? = null,
) {
    fun preview(snapshot: ProjectSnapshot, file: Path, functionName: String): PatchPlan {
        if (functionName.isBlank() || !functionName.matches(IDENTIFIER)) {
            return refused(snapshot, "Invalid function name: '$functionName'")
        }
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val selected = (if (file.isAbsolute) file else root.resolve(file)).toAbsolutePath().normalize()
        if (!selected.startsWith(root) || selected == root) {
            return refusedLocal(snapshot, "c.inlineLocal.sourceUnavailable", "Selected source escapes the captured workspace")
        }
        val relativeFile = root.relativize(selected)
        val source = snapshot.files.singleOrNull { it.path.normalize() == relativeFile }
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
        if (definition.returnType != "int") {
            return refused(snapshot, "Return conversion for '$functionName' is unproven without compiler types")
        }
        val bodyTokens = CTokenizer().tokenize(definition.body.expr)
        if (bodyTokens.withIndex().any { (index, token) ->
                token.type == CTokenType.IDENTIFIER && token.text !in definition.params &&
                    bodyTokens.getOrNull(index - 1)?.text !in setOf(".", "->")
            }) {
            return refused(snapshot, "Function '$functionName' reads an unbound or captured name; binding is unproven")
        }
        if (tokens.withIndex().any { (index, token) ->
                token.text == functionName && tokens.getOrNull(index - 1)?.text == "&"
            }) {
            return refused(snapshot, "Address-taking of '$functionName' prevents consumer closure")
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
        // The substituted expression is itself an operand at the call site.
        // Parenthesizing only arguments does not preserve 2 * add(3, 4).
        val replaced = "(${substitute(definition.body.expr, definition.params, call.args)})"
        val edits = listOf(
            TextEdit(SourceRange(SourcePosition(call.startLine, call.startChar), SourcePosition(call.endLine, call.endChar)), replaced),
            TextEdit(SourceRange(SourcePosition(definition.startLine, definition.startChar), SourcePosition(definition.endLine, definition.endChar)), ""),
        )
        return PatchPlan(
            operation = "inlineFunction",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.5,
            requiresUserApproval = true,
            summary = "Inline single-use function '$functionName' and remove its definition in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Bounded inline-function preview; full binding, conversion, configuration and consumer closure are not yet qualified."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    /** Compiler-proven inline for one direct int literal, one bound read and one return. */
    fun inlineLocal(snapshot: ProjectSnapshot, file: Path, name: String): PatchPlan {
        if (name.isBlank() || !name.matches(IDENTIFIER)) {
            return refusedLocal(snapshot, "c.inlineLocal.invalidName", "Invalid variable name: '$name'")
        }
        val compiler = toolchain ?: return refusedLocal(snapshot, "c.inlineLocal.compilerUnavailable",
            "A current captured Clang context is required to prove inline-local bindings and effects")
        val manager = processManager ?: return refusedLocal(snapshot, "c.inlineLocal.compilerUnavailable",
            "A managed compiler process boundary is required to prove inline-local bindings and effects")
        val before = when (val result = CFunctionFactsService(compiler, manager).analyzeInlineLocal(snapshot, file, name)) {
            is CInlineLocalFactsResult.LocalAvailable -> result.facts.takeIf { result.snapshotHash == snapshot.hash }
            is CInlineLocalFactsResult.Unavailable -> return refusedLocal(snapshot,
                result.diagnostic.code ?: "c.inlineLocal.factsUnavailable", result.diagnostic.message,
                RefactoringEvidence.COMPILER_PROVEN)
            is CInlineLocalFactsResult.CandidateAvailable -> null
        } ?: return refusedLocal(snapshot, "c.inlineLocal.factsUnavailable", "Compiler facts do not identify this captured snapshot",
            RefactoringEvidence.COMPILER_PROVEN)
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refusedLocal(snapshot, "c.inlineLocal.sourceUnavailable", "Source file is not uniquely captured")
        val bytes = source.content.toByteArray(Charsets.UTF_8)
        if (before.sourceSha256 != MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } ||
            before.declaration.endExclusive > bytes.size || before.use.endExclusive > bytes.size) {
            return refusedLocal(snapshot, "c.inlineLocal.factsStale", "Compiler inline-local spans do not match the captured source",
                RefactoringEvidence.COMPILER_PROVEN)
        }
        var declarationEnd = before.declaration.endExclusive
        while (declarationEnd < source.content.length && source.content[declarationEnd] in setOf(' ', '\t')) declarationEnd++
        val edits = listOf(
            TextEdit(TextEdits.rangeForOffset(source.content, before.use.start, before.use.endExclusive - before.use.start), before.literalValue.toString()),
            TextEdit(TextEdits.rangeForOffset(source.content, before.declaration.start,
                declarationEnd - before.declaration.start), ""),
        )
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), edits)))
        val candidate = try { WorkspaceEditSimulator.apply(snapshot, edit) }
        catch (_: Exception) { return refusedLocal(snapshot, "c.inlineLocal.previewInvalid", "Compiler-bound edits could not be staged",
            RefactoringEvidence.COMPILER_PROVEN) }
        val compilerService = CFunctionFactsService(compiler, manager)
        val after = when (val result = compilerService.analyzeInlineLocalPreview(snapshot, candidate, file, before.functionName,
            name, before.literalValue)) {
            is CInlineLocalFactsResult.CandidateAvailable -> result.takeIf { it.snapshotHash == candidate.hash }
            is CInlineLocalFactsResult.Unavailable -> return refusedLocal(snapshot,
                result.diagnostic.code ?: "c.inlineLocal.candidateUnavailable", result.diagnostic.message,
                RefactoringEvidence.COMPILER_PROVEN)
            is CInlineLocalFactsResult.LocalAvailable -> null
        } ?: return refusedLocal(snapshot, "c.inlineLocal.candidateUnavailable", "Staged compiler facts do not match the proposed inline",
            RefactoringEvidence.COMPILER_PROVEN)
        val resourceIdentity = (compiler.resources as? ClangResourceCaptureResult.Captured)?.snapshot?.identity
            ?: return refusedLocal(snapshot, "c.inlineLocal.resourceUnavailable", "A captured Clang resource identity is required",
                RefactoringEvidence.COMPILER_PROVEN)
        val clangIdentity = compiler.provenance.evidence.singleOrNull { it.role == "clang-executable" }?.sha256
            ?: return refusedLocal(snapshot, "c.inlineLocal.compilerUnavailable", "The Clang executable identity is unavailable",
                RefactoringEvidence.COMPILER_PROVEN)
        return PatchPlan(
            operation = "inlineLocalVariable",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Inline single-use scalar local '$name' and remove its declaration in $file",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Qualified only for one compiler-bound int decimal literal returned by a single-use local in a straight-line main function."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.COMPILER_PROVEN,
            authorityLease = OperationAuthorityLease(
                kind = "c.inline-local-int-literal-v1", operation = "inlineLocalVariable", snapshotHash = snapshot.hash,
                evidenceHash = after.snapshotHash, workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
                attributes = mapOf("c.file" to source.path.normalize().toString(), "c.name" to name,
                    "c.functionName" to before.functionName, "c.literalValue" to before.literalValue.toString(),
                    "c.clangSha256" to clangIdentity, "c.resourceDirectoryIdentity" to resourceIdentity),
            ),
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
            val body = parseBody(tokens.subList(bodyOpenIndex + 1, bodyCloseIndex))
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
                returnType = prev?.text.orEmpty(),
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

    private fun parseBody(bodyTokens: List<Tok>): Body {
        // The tokenizer may split ++/-- into adjacent punctuation tokens. Reject
        // before joining with spaces, which would hide the effect as `x + +`.
        if (bodyTokens.firstOrNull()?.text != "return" || bodyTokens.lastOrNull()?.text != ";" ||
            bodyTokens.count { it.text == ";" } != 1 ||
            bodyTokens.zipWithNext().any { (a, b) -> a.text == b.text && a.text in setOf("+", "-") }
        ) return Body.NonExpression
        val expr = bodyTokens.subList(1, bodyTokens.lastIndex).joinToString(" ") { it.text }.trim()
        return if (expr.isEmpty()) Body.NonExpression else Body.ReturnExpression(expr)
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
        // A single token pass is simultaneous and cannot rewrite identifiers inside
        // string or character literals (or substrings of other identifiers).
        return CTokenizer().tokenize(expr).joinToString(" ") { token ->
            val index = if (token.type == CTokenType.IDENTIFIER) params.indexOf(token.text) else -1
            if (index >= 0) wrapArgIfNeeded(args[index]) else token.text
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

    private fun refusedLocal(snapshot: ProjectSnapshot, code: String, message: String,
        evidence: RefactoringEvidence = RefactoringEvidence.STRUCTURAL) = PatchPlan(
        operation = "inlineLocalVariable",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code,
            evidence = if (evidence == RefactoringEvidence.COMPILER_PROVEN) DiagnosticEvidence.COMPILER else DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY)),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = evidence,
        refusalCode = code,
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
        val returnType: String,
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

/** Revalidates both compiler images under PatchEngine's managed transaction. */
internal class CManagedInlineLocalContext(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager,
) {
    fun gate(before: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate {
        val lease = plan.authorityLease
        val resourceIdentity = (toolchain.resources as? ClangResourceCaptureResult.Captured)?.snapshot?.identity
        val clangIdentity = toolchain.provenance.evidence.singleOrNull { it.role == "clang-executable" }?.sha256
        if (plan.status != PatchStatus.PREVIEW || !plan.requiresUserApproval ||
            plan.evidence != RefactoringEvidence.COMPILER_PROVEN || plan.operation != "inlineLocalVariable" ||
            plan.snapshotHash != before.hash || before.copy().hash != before.hash ||
            lease?.kind != "c.inline-local-int-literal-v1" || lease.operation != plan.operation ||
            lease.snapshotHash != before.hash || lease.workspaceEditSha256 != WorkspaceEditIdentity.sha256(plan.workspaceEdit) ||
            resourceIdentity == null || clangIdentity == null ||
            lease.attributes["c.resourceDirectoryIdentity"] != resourceIdentity ||
            lease.attributes["c.clangSha256"] != clangIdentity || !CCompilationSnapshotCapture.compilerIsCurrent(toolchain)) {
            return refusedGate()
        }
        val file: Path
        val name: String
        val functionName: String
        val literalValue: Int
        try {
            fun attribute(key: String, limit: Int): String = requireNotNull(lease.attributes[key]).also { require(it.length in 1..limit) }
            file = Path.of(attribute("c.file", 4096)).also { require(!it.isAbsolute && it.normalize() == it) }
            name = attribute("c.name", 128)
            functionName = attribute("c.functionName", 128)
            val rawValue = attribute("c.literalValue", 11)
            literalValue = rawValue.toInt().also { require(it >= 0 && it.toString() == rawValue) }
            val rebuilt = CInlinePlanner(toolchain, processManager).inlineLocal(before, file, name)
            require(rebuilt.status == PatchStatus.PREVIEW && rebuilt.authorityLease == lease)
            val staged = WorkspaceEditSimulator.apply(before, plan.workspaceEdit)
            require(staged.hash == lease.evidenceHash)
        } catch (_: Exception) {
            return refusedGate()
        }
        val root = before.workspace.root.toAbsolutePath().normalize()
        val service = CFunctionFactsService(toolchain, processManager)
        return DiagnosticsGate.enabled("clang-inline-local-v1") { candidate ->
            require(candidate.workspace.root.toAbsolutePath().normalize() == root && candidate.copy().hash == candidate.hash) {
                "c.inlineLocal.authorityInvalid: candidate workspace or immutable content identity differs"
            }
            val result = when (candidate.hash) {
                before.hash -> service.analyzeInlineLocal(candidate, file, name)
                lease.evidenceHash -> if (CCompilationSnapshotCapture.isCurrent(before)) {
                    service.analyzeInlineLocalPreview(before, candidate, file, functionName, name, literalValue)
                } else {
                    service.analyzeInlineLocalCandidate(candidate, file, functionName, name, literalValue)
                }
                else -> error("c.inlineLocal.authorityInvalid: candidate differs from the approved before/after images")
            }
            when (result) {
                is CInlineLocalFactsResult.LocalAvailable -> {
                    require(candidate.hash == before.hash && result.snapshotHash == candidate.hash &&
                        result.facts.functionName == functionName && result.facts.literalValue == literalValue) {
                        "c.inlineLocal.authorityInvalid: compiler facts differ from the retained local declaration"
                    }
                    require(result.processProvenance.executableSha256 == clangIdentity) {
                        "c.inlineLocal.authorityInvalid: compiler process identity changed"
                    }
                }
                is CInlineLocalFactsResult.CandidateAvailable -> {
                    require(candidate.hash == lease.evidenceHash && result.snapshotHash == candidate.hash &&
                        result.facts.functionName == functionName && result.facts.literalValue == literalValue) {
                        "c.inlineLocal.authorityInvalid: staged compiler facts differ from the retained inline candidate"
                    }
                    require(result.processProvenance.executableSha256 == clangIdentity) {
                        "c.inlineLocal.authorityInvalid: compiler process identity changed"
                    }
                }
                is CInlineLocalFactsResult.Unavailable -> error("${result.diagnostic.code}: ${result.diagnostic.message}")
            }
            emptyList<Diagnostic>()
        }
    }

    private fun refusedGate() = DiagnosticsGate.enabled("clang-inline-local-v1") {
        error("c.inlineLocal.authorityInvalid: unchanged compiler-bound preview and current Clang evidence are required")
    }
}

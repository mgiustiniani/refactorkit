package org.refactorkit.c

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticWorkspaceOverlay
import org.refactorkit.core.SourceFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Captured compiler execution outcome; unavailable facts are never an empty family. */
sealed interface CFunctionFactsResult {
    data class Available(val snapshotHash: String, val facts: CFunctionFamilyFacts, val processProvenance: SemanticProcessProvenance) : CFunctionFactsResult
    data class NotFound(val snapshotHash: String, val processProvenance: SemanticProcessProvenance) : CFunctionFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CFunctionFactsResult
}

internal sealed interface CVariableRenameFactsResult {
    data class Available(val snapshotHash: String, val facts: CVariableRenameFacts,
        val provenance: SemanticProcessProvenance) : CVariableRenameFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CVariableRenameFactsResult
}

sealed interface CStatementExtractionFactsResult {
    data class Available(val snapshotHash: String, val facts: CStatementExtractionFacts, val processProvenance: SemanticProcessProvenance) : CStatementExtractionFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CStatementExtractionFactsResult
}

sealed interface CScalarExpressionFactsResult {
    data class Available(val snapshotHash: String, val facts: CScalarExpressionFacts, val processProvenance: SemanticProcessProvenance) : CScalarExpressionFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CScalarExpressionFactsResult
}

data class CInlineLocalFacts(
    val sourcePath: Path,
    val sourceSha256: String,
    val functionName: String,
    val declaration: CFunctionByteSpan,
    val use: CFunctionByteSpan,
    val literalValue: Int,
)

data class CInlineLocalCandidateFacts(
    val sourcePath: Path,
    val sourceSha256: String,
    val functionName: String,
    val literalValue: Int,
)

enum class CInlineLocalFactsRefusal { INPUT_LIMIT, INVALID_AST, UNSUPPORTED_SHAPE, UNBOUND_USE, UNOWNED_RANGE }

sealed interface CInlineLocalFactsReadout {
    data class Local(val facts: CInlineLocalFacts) : CInlineLocalFactsReadout
    data class Candidate(val facts: CInlineLocalCandidateFacts) : CInlineLocalFactsReadout
    data class Refused(val reason: CInlineLocalFactsRefusal) : CInlineLocalFactsReadout
}

sealed interface CInlineLocalFactsResult {
    data class LocalAvailable(
        val snapshotHash: String,
        val facts: CInlineLocalFacts,
        val processProvenance: SemanticProcessProvenance,
    ) : CInlineLocalFactsResult
    data class CandidateAvailable(
        val snapshotHash: String,
        val facts: CInlineLocalCandidateFacts,
        val processProvenance: SemanticProcessProvenance,
    ) : CInlineLocalFactsResult
    data class Unavailable(val diagnostic: Diagnostic) : CInlineLocalFactsResult
}

/** Pure, narrow readout for a compiler-bound `int name = decimal; return name;` cell. */
internal object CInlineLocalFactsDecoder {
    fun decodeLocal(ast: JsonObject, file: Path, source: ByteArray, name: String): CInlineLocalFactsReadout = try {
        if (source.size > 1_048_576 || name.length !in 1..128 || !name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            reject(CInlineLocalFactsRefusal.INPUT_LIMIT)
        }
        if (string(ast, "kind") != "TranslationUnitDecl" || source.any { (it.toInt() and 0xff) > 0x7f }) {
            reject(CInlineLocalFactsRefusal.INVALID_AST)
        }
        val functions = flatten(ast).filter { string(it, "kind") == "FunctionDecl" &&
            children(it).any { child -> string(child, "kind") == "CompoundStmt" } }
        val function = functions.singleOrNull() ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val functionName = string(function, "name") ?: reject(CInlineLocalFactsRefusal.INVALID_AST)
        if (functionName != "main" || string(function["type"] as? JsonObject, "qualType") != "int (void)" ||
            children(function).any { (string(it, "kind") ?: "").endsWith("Attr") }) reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val body = children(function).single { string(it, "kind") == "CompoundStmt" }
        val statements = children(body)
        if (statements.size != 2 || string(statements[0], "kind") != "DeclStmt" ||
            string(statements[1], "kind") != "ReturnStmt") reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val declaration = statements[0]
        val variable = children(declaration).singleOrNull()?.takeIf { string(it, "kind") == "VarDecl" }
            ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val variableId = string(variable, "id") ?: reject(CInlineLocalFactsRefusal.INVALID_AST)
        if (string(variable, "name") != name || string(variable["type"] as? JsonObject, "qualType") != "int" ||
            string(variable, "init") != "c" || variable.containsKey("storageClass") || variable.containsKey("tls") ||
            children(variable).size != 1) reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val literal = children(variable).single()
        if (string(literal, "kind") != "IntegerLiteral" ||
            string(literal["type"] as? JsonObject, "qualType") != "int") reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val literalText = primitiveText(literal["value"]) ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        if (!literalText.matches(Regex("0|[1-9][0-9]*"))) reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val value = literalText.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE }?.toInt()
            ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val declarationSpan = span(declaration, file, source)
        val literalSpan = span(literal, file, source)
        if (!text(source, declarationSpan).matches(Regex("int\\s+${Regex.escape(name)}\\s*=\\s*${Regex.escape(literalText)}\\s*;")) ||
            text(source, literalSpan) != literalText) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val returned = children(statements[1]).singleOrNull() ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val reference = if (string(returned, "kind") == "ImplicitCastExpr" &&
            string(returned, "castKind") == "LValueToRValue" &&
            string(returned["type"] as? JsonObject, "qualType") == "int") {
            children(returned).singleOrNull()
        } else returned
        if (string(reference, "kind") != "DeclRefExpr" ||
            string(reference?.get("type") as? JsonObject, "qualType") != "int") reject(CInlineLocalFactsRefusal.UNBOUND_USE)
        val referenced = reference?.get("referencedDecl") as? JsonObject ?: reject(CInlineLocalFactsRefusal.UNBOUND_USE)
        if (string(referenced, "kind") != "VarDecl" || string(referenced, "id") != variableId ||
            string(referenced, "name") != name || string(referenced["type"] as? JsonObject, "qualType") != "int") {
            reject(CInlineLocalFactsRefusal.UNBOUND_USE)
        }
        val useSpan = span(requireNotNull(reference), file, source)
        if (text(source, useSpan) != name) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val references = flatten(ast).filter { node -> string(node, "kind") == "DeclRefExpr" &&
            string(node["referencedDecl"] as? JsonObject, "id") == variableId }
        if (references.size != 1 || span(references.single(), file, source) != useSpan) {
            reject(CInlineLocalFactsRefusal.UNBOUND_USE)
        }
        CInlineLocalFactsReadout.Local(CInlineLocalFacts(file.normalize(), sha256(source), functionName,
            declarationSpan, useSpan, value))
    } catch (failure: RejectedInlineLocalFacts) {
        CInlineLocalFactsReadout.Refused(failure.reason)
    } catch (_: Exception) {
        CInlineLocalFactsReadout.Refused(CInlineLocalFactsRefusal.INVALID_AST)
    }

    fun decodeCandidate(ast: JsonObject, file: Path, source: ByteArray, functionName: String,
        localName: String, value: Int): CInlineLocalFactsReadout = try {
        if (source.size > 1_048_576 || value < 0 || source.any { (it.toInt() and 0xff) > 0x7f }) {
            reject(CInlineLocalFactsRefusal.INPUT_LIMIT)
        }
        if (string(ast, "kind") != "TranslationUnitDecl") reject(CInlineLocalFactsRefusal.INVALID_AST)
        val functions = flatten(ast).filter { string(it, "kind") == "FunctionDecl" &&
            children(it).any { child -> string(child, "kind") == "CompoundStmt" } }
        val function = functions.singleOrNull()?.takeIf { string(it, "name") == functionName }
            ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        if (string(function["type"] as? JsonObject, "qualType") != "int (void)") reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        val body = children(function).single { string(it, "kind") == "CompoundStmt" }
        val statements = children(body)
        if (statements.size != 1 || string(statements.single(), "kind") != "ReturnStmt") {
            reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        }
        if (flatten(ast).any { string(it, "kind") == "VarDecl" && string(it, "name") == localName }) {
            reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        }
        val literal = children(statements.single()).singleOrNull()?.takeIf { string(it, "kind") == "IntegerLiteral" }
            ?: reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        if (string(literal["type"] as? JsonObject, "qualType") != "int" ||
            primitiveText(literal["value"])?.toIntOrNull() != value) reject(CInlineLocalFactsRefusal.UNSUPPORTED_SHAPE)
        if (text(source, span(literal, file, source)) != value.toString()) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        CInlineLocalFactsReadout.Candidate(CInlineLocalCandidateFacts(file.normalize(), sha256(source), functionName, value))
    } catch (failure: RejectedInlineLocalFacts) {
        CInlineLocalFactsReadout.Refused(failure.reason)
    } catch (_: Exception) {
        CInlineLocalFactsReadout.Refused(CInlineLocalFactsRefusal.INVALID_AST)
    }

    private fun flatten(root: JsonObject): List<JsonObject> = buildList {
        val pending = ArrayDeque<JsonObject>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            add(node)
            children(node).asReversed().forEach(pending::addLast)
            if (size > 100_000) reject(CInlineLocalFactsRefusal.INPUT_LIMIT)
        }
    }

    private fun children(node: JsonObject): List<JsonObject> = (node["inner"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun string(node: JsonObject?, key: String): String? = (node?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun primitiveText(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.content?.takeIf { it.length <= 32 }

    private fun span(node: JsonObject, file: Path, source: ByteArray): CFunctionByteSpan {
        val range = node["range"] as? JsonObject ?: reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val begin = coordinate(range["begin"] as? JsonObject, file, source.size)
        val end = coordinate(range["end"] as? JsonObject, file, source.size)
        if (end.start < begin.start) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        return CFunctionByteSpan(begin.start, end.endExclusive)
    }

    private data class Coordinate(val start: Int, val endExclusive: Int)
    private fun coordinate(value: JsonObject?, file: Path, size: Int): Coordinate {
        val location = value ?: reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        if (location.containsKey("spellingLoc") || location.containsKey("expansionLoc")) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val reportedFile = string(location, "file")?.let(Path::of)?.normalize()
        if (reportedFile != null && reportedFile != file.normalize()) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val start = (location["offset"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        val length = (location["tokLen"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        if (start < 0 || length !in 1..256 || start > size - length) reject(CInlineLocalFactsRefusal.UNOWNED_RANGE)
        return Coordinate(start, start + length)
    }

    private fun text(source: ByteArray, span: CFunctionByteSpan): String =
        source.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    private fun sha256(source: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(source).joinToString("") { "%02x".format(it) }
    private fun reject(reason: CInlineLocalFactsRefusal): Nothing = throw RejectedInlineLocalFacts(reason)
    private class RejectedInlineLocalFacts(val reason: CInlineLocalFactsRefusal) : RuntimeException()
}

/** Application boundary for read-only function facts; no refactoring is advertised here. */
class CFunctionFactsService(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
) {
    internal fun analyzeVariable(snapshot: ProjectSnapshot, file: Path, offset: Int): CVariableRenameFactsResult =
        variableReadout(snapshot, snapshot, file, offset)

    internal fun analyzeVariablePreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path,
        offset: Int): CVariableRenameFactsResult {
        if (!validPreviewBoundary(before, candidate)) return variableUnavailable("previewBoundaryInvalid",
            "Variable rename may change captured source content only")
        return variableReadout(before, candidate, file, offset)
    }

    private fun variableReadout(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path,
        offset: Int): CVariableRenameFactsResult = evaluate(before, candidate, file, ::variableUnavailable) {
            ast, owned, source, selected, provenance ->
        when (val decoded = CVariableRenameFactsDecoder.decode(ast, owned, source, offset)) {
            is CVariableRenameReadout.Found -> decoded.facts.let { facts ->
                CVariableRenameFactsResult.Available(candidate.hash, CVariableRenameFacts(selected,
                    facts.sourceSha256, facts.name, facts.type, facts.kind, facts.declaration,
                    facts.declarationAstPath, facts.scopeAstPath, facts.references, facts.referenceAstPaths), provenance)
            }
            is CVariableRenameReadout.Refused -> variableUnavailable(decoded.reason.name.lowercase(Locale.ROOT),
                "Compiler variable binding facts refused: ${decoded.reason}")
        }
    }

    private fun variableUnavailable(code: String, message: String) = CVariableRenameFactsResult.Unavailable(Diagnostic(
        message, Diagnostic.Severity.ERROR, code = "c.variableFacts.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))

    fun analyzeInlineLocal(snapshot: ProjectSnapshot, file: Path, name: String): CInlineLocalFactsResult =
        evaluate(snapshot, snapshot, file, ::inlineLocalUnavailable) { ast, owned, source, selected, provenance ->
            when (val facts = CInlineLocalFactsDecoder.decodeLocal(ast, owned, source, name)) {
                is CInlineLocalFactsReadout.Local -> CInlineLocalFactsResult.LocalAvailable(snapshot.hash,
                    facts.facts.copy(sourcePath = selected), provenance)
                is CInlineLocalFactsReadout.Refused -> inlineLocalUnavailable("${facts.reason.name.lowercase(Locale.ROOT)}",
                    "Compiler inline-local facts refused: ${facts.reason}")
                is CInlineLocalFactsReadout.Candidate -> inlineLocalUnavailable("invalidAst", "Unexpected inline-local candidate facts")
            }
        }

    fun analyzeInlineLocalPreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path,
        functionName: String, localName: String, literalValue: Int): CInlineLocalFactsResult {
        if (!validPreviewBoundary(before, candidate)) return inlineLocalUnavailable("previewBoundaryInvalid",
            "An inline-local candidate may change source content only; paths and captured configuration remain fixed")
        return analyzeInlineLocalCandidate(before, candidate, file, functionName, localName, literalValue)
    }

    /** Rechecks the post-apply source after the original on-disk image has been replaced. */
    fun analyzeInlineLocalCandidate(snapshot: ProjectSnapshot, file: Path, functionName: String,
        localName: String, literalValue: Int): CInlineLocalFactsResult =
        analyzeInlineLocalCandidate(snapshot, snapshot, file, functionName, localName, literalValue)

    private fun analyzeInlineLocalCandidate(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path,
        functionName: String, localName: String, literalValue: Int): CInlineLocalFactsResult =
        evaluate(before, candidate, file, ::inlineLocalUnavailable) { ast, owned, source, selected, provenance ->
            when (val facts = CInlineLocalFactsDecoder.decodeCandidate(ast, owned, source, functionName, localName, literalValue)) {
                is CInlineLocalFactsReadout.Candidate -> CInlineLocalFactsResult.CandidateAvailable(candidate.hash,
                    facts.facts.copy(sourcePath = selected), provenance)
                is CInlineLocalFactsReadout.Refused -> inlineLocalUnavailable("${facts.reason.name.lowercase(Locale.ROOT)}",
                    "Compiler inline-local candidate facts refused: ${facts.reason}")
                is CInlineLocalFactsReadout.Local -> inlineLocalUnavailable("invalidAst", "Unexpected inline-local declaration facts")
            }
        }

    internal fun analyzeInlineFunction(snapshot: ProjectSnapshot, file: Path, functionName: String): CInlineFunctionFactsResult =
        inlineFunctionReadout(snapshot, snapshot, file, functionName, false)

    internal fun analyzeInlineFunctionPreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path,
        functionName: String): CInlineFunctionFactsResult {
        if (!validPreviewBoundary(before, candidate)) return inlineFunctionUnavailable("previewBoundaryInvalid",
            "A function-inline candidate may change captured source content only")
        return inlineFunctionReadout(before, candidate, file, functionName, true)
    }

    internal fun analyzeInlineFunctionCandidate(snapshot: ProjectSnapshot, file: Path, functionName: String): CInlineFunctionFactsResult =
        inlineFunctionReadout(snapshot, snapshot, file, functionName, true)

    private fun inlineFunctionReadout(before: ProjectSnapshot, snapshot: ProjectSnapshot, file: Path,
        functionName: String, candidate: Boolean): CInlineFunctionFactsResult =
        evaluate(before, snapshot, file, ::inlineFunctionUnavailable) { ast, owned, source, _, _ ->
            try {
                CInlineFunctionFactsResult.Available(snapshot.hash,
                    CInlineFunctionFactsDecoder.decode(ast, owned, source, functionName, candidate))
            } catch (_: Exception) {
                inlineFunctionUnavailable("unsupportedSemantics", "Compiler facts do not prove the admitted int expression and complete internal function family")
            }
        }

    fun analyzeScalarExpression(snapshot: ProjectSnapshot, file: Path, selection: CFunctionByteSpan): CScalarExpressionFactsResult =
        evaluate(snapshot, snapshot, file, ::scalarUnavailable) { ast, owned, source, selected, provenance ->
            scalarReadout(snapshot, ast, owned, source, selected, provenance, null, selection)
        }

    fun analyzeScalarExpression(snapshot: ProjectSnapshot, file: Path, functionName: String,
        selection: CFunctionByteSpan): CScalarExpressionFactsResult =
        evaluate(snapshot, snapshot, file, ::scalarUnavailable) { ast, owned, source, selected, provenance ->
            scalarReadout(snapshot, ast, owned, source, selected, provenance, functionName, selection)
        }

    fun analyzeScalarExpressionPreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path, functionName: String,
        selection: CFunctionByteSpan): CScalarExpressionFactsResult {
        if (!validPreviewBoundary(before, candidate)) return scalarUnavailable("previewBoundaryInvalid", "A scalar preview may change source content only; paths and captured configuration remain fixed")
        return evaluate(before, candidate, file, ::scalarUnavailable) { ast, owned, source, selected, provenance ->
            scalarReadout(candidate, ast, owned, source, selected, provenance, functionName, selection)
        }
    }

    fun analyzeStatements(snapshot: ProjectSnapshot, file: Path, functionName: String, selection: CFunctionByteSpan): CStatementExtractionFactsResult =
        evaluate(snapshot, snapshot, file, ::statementUnavailable) { ast, owned, source, selected, provenance ->
            statementReadout(snapshot, ast, owned, source, selected, provenance, functionName, selection)
        }

    fun analyzeStatementsPreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path, functionName: String,
        selection: CFunctionByteSpan): CStatementExtractionFactsResult {
        if (!validPreviewBoundary(before, candidate)) return statementUnavailable("previewBoundaryInvalid", "A statement preview may change source content only; paths and captured configuration remain fixed")
        return evaluate(before, candidate, file, ::statementUnavailable) { ast, owned, source, selected, provenance ->
            statementReadout(candidate, ast, owned, source, selected, provenance, functionName, selection)
        }
    }

    /** Evaluate source-only candidate content while retaining the saved snapshot's authority. */
    fun analyzePreview(before: ProjectSnapshot, candidate: ProjectSnapshot, file: Path, functionName: String): CFunctionFactsResult {
        if (!validPreviewBoundary(before, candidate)) {
            return unavailable("previewBoundaryInvalid", "A function preview may change source content only; paths and captured configuration remain fixed")
        }
        return functionReadout(before, candidate, file, functionName)
    }

    fun analyze(snapshot: ProjectSnapshot, file: Path, functionName: String): CFunctionFactsResult =
        functionReadout(snapshot, snapshot, file, functionName)

    private fun validPreviewBoundary(before: ProjectSnapshot, candidate: ProjectSnapshot): Boolean =
        before.workspace.root.toAbsolutePath().normalize() == candidate.workspace.root.toAbsolutePath().normalize() &&
            before.files.map { it.path to it.languageId } == candidate.files.map { it.path to it.languageId } &&
            candidate.copy(files = before.files).hash == before.hash

    private fun functionReadout(before: ProjectSnapshot, snapshot: ProjectSnapshot, file: Path, functionName: String): CFunctionFactsResult =
        evaluate(before, snapshot, file, ::unavailable) { ast, owned, source, selected, provenance ->
            when (val facts = CFunctionFamilyFactsDecoder.decode(ast, owned, source, functionName)) {
                is CFunctionFamilyFactsReadout.Found -> CFunctionFactsResult.Available(snapshot.hash, facts.facts.copy(sourcePath = selected), provenance)
                CFunctionFamilyFactsReadout.NotFound -> CFunctionFactsResult.NotFound(snapshot.hash, provenance)
                is CFunctionFamilyFactsReadout.Refused -> unavailable("astUnavailable", "Compiler function facts refused: ${facts.reason}")
            }
        }

    private fun statementReadout(snapshot: ProjectSnapshot, ast: JsonObject, owned: Path, source: ByteArray, selected: Path,
        provenance: SemanticProcessProvenance, functionName: String, selection: CFunctionByteSpan): CStatementExtractionFactsResult =
        when (val facts = CStatementExtractionFactsDecoder.decode(ast, owned, source, functionName, selection)) {
            is CStatementExtractionReadout.Found -> CStatementExtractionFactsResult.Available(snapshot.hash, facts.facts.copy(sourcePath = selected), provenance)
            is CStatementExtractionReadout.Refused -> statementUnavailable(facts.reason.name.lowercase(Locale.ROOT), "Compiler statement facts refused: ${facts.reason}")
        }

    private fun scalarReadout(snapshot: ProjectSnapshot, ast: JsonObject, owned: Path, source: ByteArray, selected: Path,
        provenance: SemanticProcessProvenance, functionName: String?, selection: CFunctionByteSpan): CScalarExpressionFactsResult {
        val decoded = if (functionName == null) CScalarExpressionFactsDecoder.decodeSelection(ast, owned, source, selection)
            else CScalarExpressionFactsDecoder.decode(ast, owned, source, functionName, selection)
        return when (val facts = decoded) {
            is CScalarExpressionReadout.Found -> CScalarExpressionFactsResult.Available(snapshot.hash, facts.facts.copy(sourcePath = selected), provenance)
            is CScalarExpressionReadout.Refused -> scalarUnavailable(facts.reason.name.lowercase(Locale.ROOT), "Compiler scalar facts refused: ${facts.reason}")
        }
    }

    private fun <T> evaluate(before: ProjectSnapshot, snapshot: ProjectSnapshot, file: Path, fail: (String, String) -> T,
        readout: (JsonObject, Path, ByteArray, Path, SemanticProcessProvenance) -> T): T {
        val workspace = snapshot.workspace.root.toAbsolutePath().normalize()
        val selected = (if (file.isAbsolute) file else workspace.resolve(file)).normalize()
        val source = snapshot.files.singleOrNull()
        if (!selected.startsWith(workspace) || selected == workspace || source == null || source.languageId != "c" ||
            source.path.normalize() != workspace.relativize(selected) || !selected.fileName.toString().endsWith(".c") ||
            source.content.toByteArray(Charsets.UTF_8).size > 1_048_576 ||
            snapshot.auxiliaryFiles.count { it.languageId != CFormattingStyleSnapshot.LANGUAGE_ID } > 4 ||
            source.content.any { it == '#' || it == '\\' } || "??" in source.content || "_Pragma" in source.content) {
            return fail("sourceUnsupported", "This compiler-facts cell requires one owned C source without preprocessing inputs")
        }
        if (!CCompilationSnapshotCapture.isCurrent(before)) return fail("snapshotChanged", "Captured source or compilation configuration changed or is unavailable")
        if (toolchain.provenance.clangVersion != "22.1.8" || !CCompilationSnapshotCapture.compilerIsCurrent(toolchain)) {
            return fail("compilerUnavailable", "The captured explicit Clang 22.1.8 executable or complete resource inputs are unavailable or changed")
        }
        val context = try { CClangdLaunchContext.capture(workspace, toolchain.clangExecutable, toolchain.readOnlySdkInputs.requests) }
        catch (_: Exception) { return fail("configurationRefused", "Compiler input configuration is outside the admitted boundary") }
        return try { context.use {
            val database = Json.parseToJsonElement(Files.readString(context.root.resolve("compile_commands.json"))) as JsonArray
            val unit = database.singleOrNull() as? JsonObject
                ?: return fail("configurationRefused", "Exactly one captured translation unit is required")
            if ((unit["file"] as? JsonPrimitive)?.content != selected.toString()) {
                return fail("configurationRefused", "The captured translation unit does not match the selected source")
            }
            val arguments = (unit["arguments"] as JsonArray).map { (it as JsonPrimitive).content }.drop(1)
            val directory = Path.of((unit["directory"] as JsonPrimitive).content)
            try {
                SemanticWorkspaceOverlay.create(snapshot, maxFiles = 1, maxBytes = 1_048_576).use { overlay ->
                    fun remap(path: Path): Path = if (path == workspace) overlay.root else
                        requireNotNull(overlay.toOverlayPath(path)) { "Captured compiler input escapes the source overlay" }
                    val working = remap(directory)
                    Files.createDirectories(working)
                    val mappedArguments = arguments.map { argument ->
                        if (argument.startsWith(workspace.toString() + workspace.fileSystem.separator) || argument == workspace.toString()) {
                            val path = Path.of(argument)
                            val mapped = remap(path)
                            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(mapped)
                            mapped.toString()
                        } else argument
                    }
                    if (!context.isCurrent() || !CCompilationSnapshotCapture.isCurrent(before) ||
                        !CCompilationSnapshotCapture.compilerIsCurrent(toolchain)) {
                        return fail("snapshotChanged", "Captured source, compiler or resource inputs changed before execution")
                    }
                    val captured = ClangFunctionAstBridge.read(toolchain, mappedArguments, working, processManager)
                    if (captured.provenance.executableSha256 != toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256 ||
                        !CCompilationSnapshotCapture.compilerIsCurrent(toolchain) || !context.isCurrent() ||
                        !CCompilationSnapshotCapture.isCurrent(before) || overlay.verifySourcesUnchanged().isNotEmpty()) {
                        return fail("snapshotChanged", "Compiler or captured inputs changed during analysis")
                    }
                    readout(captured.ast, remap(selected), source.content.toByteArray(Charsets.UTF_8), selected, captured.provenance)
                }
            } catch (_: Exception) {
                fail("compilerUnavailable", "The admitted compiler could not produce complete bounded facts")
            }
        } } catch (_: Exception) {
            fail("compilerUnavailable", "Private compiler configuration could not be read completely")
        }
    }

    private fun unavailable(code: String, message: String) = CFunctionFactsResult.Unavailable(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = "c.functionFacts.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))

    private fun statementUnavailable(code: String, message: String) = CStatementExtractionFactsResult.Unavailable(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = "c.statementFacts.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))

    private fun scalarUnavailable(code: String, message: String) = CScalarExpressionFactsResult.Unavailable(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = "c.scalarFacts.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))

    private fun inlineFunctionUnavailable(code: String, message: String) = CInlineFunctionFactsResult.Unavailable(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = "c.inlineFunction.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))

    private fun inlineLocalUnavailable(code: String, message: String) = CInlineLocalFactsResult.Unavailable(Diagnostic(
        message = message, severity = Diagnostic.Severity.ERROR, code = "c.inlineLocal.$code",
        evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.SAFETY,
    ))
}

/** Bounded workspace-input capture. Uncaptured present databases never pass the current-input check. */
object CCompilationSnapshotCapture {
    private val DATABASES = listOf(Path.of("compile_commands.json"), Path.of("build/compile_commands.json"))
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun capture(snapshot: ProjectSnapshot): ProjectSnapshot {
        if (snapshot.files.none { it.languageId == "c" }) return snapshot
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        // A language-neutral inventory may already own this JSON input. Retain
        // its bytes and specialize only an unambiguous, still-current auxiliary
        // record; never refresh stale bytes or conceal duplicate path evidence.
        val specialized = snapshot.auxiliaryFiles.map { file ->
            val path = file.path.normalize()
            if (path !in DATABASES || file.languageId == "c-compilation-database" ||
                snapshot.trackedFiles.count { it.path.normalize() == path } != 1) file
            else when (val observed = read(root, path)) {
                is CapturedInput.Bytes -> if (observed.value.contentEquals(file.content.toByteArray(Charsets.UTF_8)))
                    file.copy(languageId = "c-compilation-database") else file
                CapturedInput.Absent, CapturedInput.Unavailable -> file
            }
        }
        val captured = if (specialized == snapshot.auxiliaryFiles) snapshot else snapshot.copy(auxiliaryFiles = specialized)
        val additions = DATABASES.filter { candidate -> captured.trackedFiles.none { it.path.normalize() == candidate } }.mapNotNull { path ->
            when (val captured = read(root, path)) {
                is CapturedInput.Bytes -> {
                    val text = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(captured.value)).toString() }
                    text.getOrNull()?.let { SourceFile(path, it, "c-compilation-database") }
                }
                CapturedInput.Absent, CapturedInput.Unavailable -> null
            }
        }
        return if (additions.isEmpty()) captured else captured.copy(auxiliaryFiles = captured.auxiliaryFiles + additions)
    }

    internal fun isCurrent(snapshot: ProjectSnapshot): Boolean {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        if (snapshot.trackedFiles.size > 5 || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return false
        if (!snapshot.trackedFiles.all { file ->
            val observed = read(root, file.path.normalize())
            observed is CapturedInput.Bytes && observed.value.contentEquals(file.content.toByteArray(Charsets.UTF_8))
        }) return false
        return DATABASES.all { candidate ->
            val expected = snapshot.trackedFiles.singleOrNull { it.path.normalize() == candidate }
            val observed = read(root, candidate)
            if (expected == null) observed == CapturedInput.Absent
            else observed is CapturedInput.Bytes && observed.value.contentEquals(expected.content.toByteArray(Charsets.UTF_8))
        }
    }

    internal fun compilerIsCurrent(toolchain: ClangSemanticToolchain): Boolean = runCatching {
        if (toolchain.provenance.readOnlySdkIdentity != toolchain.readOnlySdkInputs.identity ||
            !toolchain.readOnlySdkInputs.isCurrent()) return@runCatching false
        if (ClangToolchainRuntimeCapture().checkCurrent(toolchain) != ClangToolchainRuntimeCurrency.Current) return@runCatching false
        val resources = (toolchain.resources as? ClangResourceCaptureResult.Captured)?.snapshot ?: return@runCatching false
        val declaredRoot = Path.of(toolchain.provenance.resourceDir ?: return@runCatching false)
        if (!declaredRoot.isAbsolute || declaredRoot.normalize() != resources.root ||
            ClangResourceDirectoryCapture().checkCurrent(resources) != ClangResourceCurrency.Current) return@runCatching false
        val evidence = toolchain.provenance.evidence.single { it.role == "clang-executable" }
        val path = toolchain.clangExecutable
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != evidence.size || evidence.size > 512L * 1024L * 1024L) return@runCatching false
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
            val buffer = ByteArray(65_536)
            var count = 0L
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                count += size
                if (count > evidence.size) return@runCatching false
                digest.update(buffer, 0, size)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == evidence.sha256
    }.getOrDefault(false)

    private sealed interface CapturedInput {
        data class Bytes(val value: ByteArray) : CapturedInput
        data object Absent : CapturedInput
        data object Unavailable : CapturedInput
    }

    private fun read(root: Path, relative: Path): CapturedInput = try {
        when (val input = org.refactorkit.core.OwnedSourceInventoryReader.readWorkspaceTextInput(root, relative, MAX_BYTES)) {
            org.refactorkit.core.OwnedWorkspaceTextReadout.Absent -> CapturedInput.Absent
            is org.refactorkit.core.OwnedWorkspaceTextReadout.Captured ->
                CapturedInput.Bytes(input.content.toByteArray(Charsets.UTF_8))
        }
    } catch (_: Exception) { CapturedInput.Unavailable }
}

/** Thin native transport: bounded process/output extraction, then pure AST delegation by the service. */
internal object ClangFunctionAstBridge {
    data class CapturedAst(val ast: JsonObject, val provenance: SemanticProcessProvenance, val stdoutBytes: Int)

    fun read(toolchain: ClangSemanticToolchain, arguments: List<String>, directory: Path, manager: ExternalSemanticProcessManager): CapturedAst {
        val resourceRoot = (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.root
        val execution = manager.launch(SemanticProcessSpec(id = "clang-function-${UUID.randomUUID()}",
            executable = toolchain.clangExecutable, arguments = listOf("-fsyntax-only", "--no-default-config", "-resource-dir", resourceRoot.toString(),
                "-Xclang", "-ast-dump=json") + arguments,
            workingDirectory = directory, limits = SemanticProcessLimits(maxStdoutBytes = 16 * 1024 * 1024, maxStderrBytes = 64 * 1024, gracefulShutdownMillis = 500)))
        val reader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "refactorkit-clang-function-output").apply { isDaemon = true } }
        try {
            val future = reader.submit<String> { execution.output.bufferedReader(Charsets.UTF_8).readText() }
            check(execution.awaitExit(30_000)) { "Compiler function analysis timed out" }
            val text = future.get(2, TimeUnit.SECONDS)
            check(execution.exitCode == 0 && !execution.stderrTruncated()) { "Compiler function analysis did not complete cleanly" }
            requireBoundedJsonNesting(text)
            return CapturedAst(Json.parseToJsonElement(text) as JsonObject, execution.provenance, text.toByteArray(Charsets.UTF_8).size)
        } finally {
            reader.shutdownNow()
            execution.close()
        }
    }

    private fun requireBoundedJsonNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 256) { "Compiler JSON nesting limit exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Unbalanced compiler JSON" } }
            }
        }
        require(depth == 0 && !quoted) { "Incomplete compiler JSON" }
    }
}

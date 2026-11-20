package org.refactorkit.c

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Collections
import java.security.MessageDigest

data class CScalarExpressionInput(val name: String, val declarationSpan: CFunctionByteSpan)
data class CScalarExpressionFacts(
    val sourcePath: Path,
    val sourceSha256: String,
    val functionDefinitionSpan: CFunctionByteSpan,
    val selection: CFunctionByteSpan,
    val enclosingStatement: CFunctionByteSpan,
    val expressionType: String,
    val assignmentTargetType: String,
    val inputs: List<CScalarExpressionInput>,
    val ordinaryNames: Set<String>,
    val functionName: String = "",
)
enum class CScalarExpressionRefusal {
    INPUT_LIMIT, INVALID_ENCODING, INVALID_AST, UNAVAILABLE_FAMILY, INVALID_SELECTION,
    UNSUPPORTED_CONTEXT, UNSUPPORTED_EXPRESSION, UNAVAILABLE_INPUT, AMBIGUOUS_OWNER,
}
sealed interface CScalarExpressionReadout {
    data class Found(val facts: CScalarExpressionFacts) : CScalarExpressionReadout
    data class Refused(val reason: CScalarExpressionRefusal) : CScalarExpressionReadout
}

/** Pure source-bound facts supplied by a compiler AST; no native execution or edits. */
internal object CScalarExpressionFactsDecoder {
    fun decodeSelection(ast: JsonObject, file: Path, source: ByteArray,
        selection: CFunctionByteSpan): CScalarExpressionReadout = try {
        guardSource(source, selection)
        if (kind(ast) != "TranslationUnitDecl") reject(CScalarExpressionRefusal.INVALID_AST)
        val owners = CFunctionFamilyFactsDecoder.flatten(ast).filter { node ->
            kind(node) == "FunctionDecl" && children(node).any { kind(it) == "CompoundStmt" } && span(node, file, source).contains(selection)
        }
        if (owners.isEmpty()) reject(CScalarExpressionRefusal.INVALID_SELECTION)
        if (owners.size != 1) reject(CScalarExpressionRefusal.AMBIGUOUS_OWNER)
        decode(ast, file, source, name(owners.single()), selection)
    } catch (failure: Rejected) { CScalarExpressionReadout.Refused(failure.reason) }
      catch (_: IllegalArgumentException) { CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_AST) }
      catch (_: IllegalStateException) { CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_AST) }

    fun decode(ast: JsonObject, file: Path, source: ByteArray, functionName: String,
        selection: CFunctionByteSpan): CScalarExpressionReadout = try {
        guardSource(source, selection)
        if (functionName.length > 128) reject(CScalarExpressionRefusal.INPUT_LIMIT)
        if (kind(ast) != "TranslationUnitDecl") reject(CScalarExpressionRefusal.INVALID_AST)
        val nodes = CFunctionFamilyFactsDecoder.flatten(ast)
        val definition = nodes.singleOrNull { kind(it) == "FunctionDecl" && string(it, "name") == functionName &&
            children(it).any { child -> kind(child) == "CompoundStmt" } } ?: reject(CScalarExpressionRefusal.INVALID_AST)
        id(definition)
        verifyName(definition, file, source)
        if (children(definition).any { kind(it).endsWith("Attr") }) reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
        val body = children(definition).single { kind(it) == "CompoundStmt" }
        val direct = children(body)
        if (direct.size > 65) reject(CScalarExpressionRefusal.INPUT_LIMIT)
        val bodyNodes = CFunctionFamilyFactsDecoder.flatten(body)
        if (direct.any { kind(it) !in setOf("DeclStmt", "ReturnStmt") } || direct.count { kind(it) == "ReturnStmt" } != 1 ||
            kind(direct.last()) != "ReturnStmt" || bodyNodes.any { kind(it) in CONTEXT_BOUNDARIES }) reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
        if (bodyNodes.any { kind(it) == "CompoundAssignOperator" || kind(it) == "BinaryOperator" && string(it, "opcode") == "=" ||
            kind(it) == "UnaryOperator" && string(it, "opcode") !in setOf("+", "-") }) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
        val owningStatement = direct.singleOrNull { kind(it) == "DeclStmt" && span(it, file, source).contains(selection) }
            ?: reject(CScalarExpressionRefusal.INVALID_SELECTION)
        val owningVariable = children(owningStatement).singleOrNull()?.takeIf { kind(it) == "VarDecl" }
            ?: reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
        val owningInitializer = children(owningVariable).singleOrNull() ?: reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
        val selected = CFunctionFamilyFactsDecoder.flatten(owningInitializer).filter {
            kind(it) in EXPLICIT_EXPRESSIONS && span(it, file, source) == selection
        }.singleOrNull() ?: reject(CScalarExpressionRefusal.INVALID_SELECTION)
        if (type(selected) != "int" || string(selected, "valueCategory") != "prvalue") reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
        val available = linkedMapOf<String, JsonObject>()
        val used = linkedSetOf<String>()
        children(definition).filter { kind(it) == "ParmVarDecl" }.forEach { parameter ->
            if (type(parameter) !in setOf("int", "const int") || children(parameter).isNotEmpty()) reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
            verifyName(parameter, file, source)
            if (available.put(id(parameter), parameter) != null) reject(CScalarExpressionRefusal.INVALID_AST)
        }

        fun expression(node: JsonObject, owner: CFunctionByteSpan, integerSelection: Boolean) {
            val location = span(node, file, source)
            if (!owner.contains(location)) reject(CScalarExpressionRefusal.INVALID_AST)
            val nested = children(node)
            val nativeType = type(node)
            if (integerSelection && nativeType != "int" && !(kind(node) == "DeclRefExpr" && nativeType == "const int") ||
                !integerSelection && nativeType !in VALUE_TYPES) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
            when (kind(node)) {
                "IntegerLiteral" -> if (nested.isNotEmpty() || string(node, "value")?.toIntOrNull() == null) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                "CharacterLiteral" -> if (nested.isNotEmpty() || (node["value"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == null) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                "FloatingLiteral" -> if (integerSelection || nested.isNotEmpty() || string(node, "value")?.toDoubleOrNull()?.isFinite() != true) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                "ParenExpr" -> {
                    if (nested.size != 1) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location, integerSelection)
                }
                "ImplicitCastExpr" -> {
                    val allowed = if (integerSelection) setOf("LValueToRValue") else setOf("LValueToRValue", "IntegralToFloating", "FloatingToIntegral")
                    if (nested.size != 1 || string(node, "castKind") !in allowed) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location, integerSelection)
                }
                "BinaryOperator" -> {
                    if (nested.size != 2 || string(node, "opcode") !in setOf("+", "-", "*", "/", "%")) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                    nested.forEach { expression(it, location, integerSelection) }
                }
                "UnaryOperator" -> {
                    if (nested.size != 1 || string(node, "opcode") !in setOf("+", "-")) reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location, integerSelection)
                }
                "DeclRefExpr" -> {
                    if (nested.isNotEmpty()) reject(CScalarExpressionRefusal.INVALID_AST)
                    val referenced = node["referencedDecl"] as? JsonObject ?: reject(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
                    val referenceId = id(referenced)
                    val declared = available[referenceId] ?: reject(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
                    if (kind(referenced) != kind(declared) || name(referenced) != name(declared) || type(referenced) != type(declared) ||
                        fragment(source, location) != name(declared)) reject(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
                    if (integerSelection) used += referenceId
                }
                else -> reject(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
            }
        }

        direct.filter { kind(it) == "DeclStmt" }.forEach { statement ->
            val variable = children(statement).singleOrNull()?.takeIf { kind(it) == "VarDecl" }
                ?: reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
            if (type(variable) !in VALUE_TYPES || variable.containsKey("storageClass") || variable.containsKey("tls") ||
                string(variable, "init") != "c") reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
            verifyName(variable, file, source)
            val initializer = children(variable).singleOrNull() ?: reject(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
            expression(initializer, span(variable, file, source), false)
            if (variable == owningVariable) expression(selected, span(variable, file, source), true)
            if (available.values.any { name(it) == name(variable) } || available.put(id(variable), variable) != null) reject(CScalarExpressionRefusal.INVALID_AST)
        }
        val inputs = available.filterKeys { it in used }.values.map { CScalarExpressionInput(name(it), span(it, file, source)) }
        val names = nodes.filter { kind(it) in setOf("FunctionDecl", "VarDecl", "ParmVarDecl", "TypedefDecl", "EnumConstantDecl") }.map(::name).toSortedSet()
        val hash = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        CScalarExpressionReadout.Found(CScalarExpressionFacts(file.normalize(), hash, span(definition, file, source),
            selection, span(owningStatement, file, source), type(selected)!!, type(owningVariable)!!,
            Collections.unmodifiableList(inputs), Collections.unmodifiableSet(names), name(definition)))
    } catch (failure: Rejected) { CScalarExpressionReadout.Refused(failure.reason) }
      catch (_: IllegalArgumentException) { CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_AST) }
      catch (_: IllegalStateException) { CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_AST) }

    private val VALUE_TYPES = setOf("int", "const int", "double", "const double")
    private val EXPLICIT_EXPRESSIONS = setOf("IntegerLiteral", "CharacterLiteral", "FloatingLiteral", "ParenExpr", "BinaryOperator", "UnaryOperator", "DeclRefExpr")
    private val CONTEXT_BOUNDARIES = setOf("GotoStmt", "IndirectGotoStmt", "LabelStmt", "AddrLabelExpr", "GCCAsmStmt", "MSAsmStmt", "StmtExpr", "CallExpr",
        "ConditionalOperator", "BinaryConditionalOperator", "UnaryExprOrTypeTraitExpr", "GenericSelectionExpr", "CompoundLiteralExpr", "InitListExpr", "VAArgExpr")
    private fun string(node: JsonObject?, key: String) = CFunctionFamilyFactsDecoder.string(node, key)
    private fun kind(node: JsonObject) = string(node, "kind") ?: reject(CScalarExpressionRefusal.INVALID_AST)
    private fun id(node: JsonObject) = string(node, "id")?.takeIf { it.length <= 128 } ?: reject(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
    private fun name(node: JsonObject) = string(node, "name")?.takeIf { it.length <= 128 } ?: reject(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
    private fun type(node: JsonObject) = string(node["type"] as? JsonObject, "qualType")
    private fun children(node: JsonObject) = CFunctionFamilyFactsDecoder.children(node)
    private fun span(node: JsonObject, file: Path, source: ByteArray) = CFunctionFamilyFactsDecoder.span(node, file, source)
    private fun CFunctionByteSpan.contains(other: CFunctionByteSpan) = start <= other.start && endExclusive >= other.endExclusive
    private fun fragment(source: ByteArray, range: CFunctionByteSpan) = source.copyOfRange(range.start, range.endExclusive).toString(Charsets.UTF_8)
    private fun guardSource(source: ByteArray, selection: CFunctionByteSpan) {
        if (source.size > 1_048_576) reject(CScalarExpressionRefusal.INPUT_LIMIT)
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)) }
        catch (_: java.nio.charset.CharacterCodingException) { reject(CScalarExpressionRefusal.INVALID_ENCODING) }
        if (selection.start < 0 || selection.start >= selection.endExclusive || selection.endExclusive > source.size) reject(CScalarExpressionRefusal.INVALID_SELECTION)
    }
    private fun verifyName(node: JsonObject, file: Path, source: ByteArray) {
        val location = node["loc"] ?: reject(CScalarExpressionRefusal.INVALID_AST)
        val token = span(JsonObject(mapOf("range" to JsonObject(mapOf("begin" to location, "end" to location)))), file, source)
        if (fragment(source, token) != name(node)) reject(CScalarExpressionRefusal.INVALID_AST)
    }
    private class Rejected(val reason: CScalarExpressionRefusal) : IllegalArgumentException()
    private fun reject(reason: CScalarExpressionRefusal): Nothing = throw Rejected(reason)
}

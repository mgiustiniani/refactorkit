package org.refactorkit.c

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.Collections

data class CStatementExtractionInput(val name: String, val parameterOrdinal: Int, val declarationSpan: CFunctionByteSpan)
data class CStatementExtractionFacts(
    val sourcePath: Path,
    val sourceSha256: String,
    val functionDefinitionSpan: CFunctionByteSpan,
    val selection: CFunctionByteSpan,
    val statements: List<CFunctionByteSpan>,
    val inputs: List<CStatementExtractionInput>,
    val localNames: List<String>,
    val outputName: String,
    val ordinaryNames: Set<String>,
)
enum class CStatementExtractionRefusal {
    INPUT_LIMIT, INVALID_AST, INVALID_ENCODING, UNAVAILABLE_FAMILY, INVALID_SELECTION,
    UNSUPPORTED_DECLARATION, UNSUPPORTED_EXPRESSION, UNAVAILABLE_INPUT, NONFINAL_LIVE_OUTPUT, CONTROL_FLOW_UNAVAILABLE,
}
sealed interface CStatementExtractionReadout {
    data class Found(val facts: CStatementExtractionFacts) : CStatementExtractionReadout
    data class Refused(val reason: CStatementExtractionRefusal) : CStatementExtractionReadout
}

/** Pure decoding of supplied compiler AST; native invocation and edit authority are separate. */
internal object CStatementExtractionFactsDecoder {
    fun decode(ast: JsonObject, file: Path, source: ByteArray, functionName: String,
        selection: CFunctionByteSpan): CStatementExtractionReadout = try {
        if (source.size > 1_048_576 || functionName.length > 128) reject(CStatementExtractionRefusal.INPUT_LIMIT)
        try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)) }
        catch (_: java.nio.charset.CharacterCodingException) { reject(CStatementExtractionRefusal.INVALID_ENCODING) }
        if (selection.start < 0 || selection.start >= selection.endExclusive || selection.endExclusive > source.size) {
            reject(CStatementExtractionRefusal.INVALID_SELECTION)
        }
        val family = when (val result = CFunctionFamilyFactsDecoder.decode(ast, file, source, functionName)) {
            is CFunctionFamilyFactsReadout.Found -> result.facts
            else -> reject(CStatementExtractionRefusal.UNAVAILABLE_FAMILY)
        }
        if (family.family != CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC) reject(CStatementExtractionRefusal.UNAVAILABLE_FAMILY)
        val nodes = CFunctionFamilyFactsDecoder.flatten(ast)
        val definition = nodes.singleOrNull { kind(it) == "FunctionDecl" && string(it, "name") == functionName &&
            children(it).any { nested -> kind(nested) == "CompoundStmt" } } ?: reject(CStatementExtractionRefusal.INVALID_AST)
        if (children(definition).any { kind(it).endsWith("Attr") }) reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
        val body = children(definition).single { kind(it) == "CompoundStmt" }
        val bodyNodes = CFunctionFamilyFactsDecoder.flatten(body)
        val direct = children(body)
        if (bodyNodes.any { kind(it) in setOf("GotoStmt", "IndirectGotoStmt", "LabelStmt", "AddrLabelExpr", "GCCAsmStmt", "MSAsmStmt", "StmtExpr", "CallExpr") } ||
            direct.any { kind(it) !in setOf("DeclStmt", "ReturnStmt") } ||
            direct.count { kind(it) == "ReturnStmt" } != 1 || kind(direct.last()) != "ReturnStmt") {
            reject(CStatementExtractionRefusal.CONTROL_FLOW_UNAVAILABLE)
        }
        val ranges = direct.map { span(it, file, source) }
        val first = ranges.indexOfFirst { it.start == selection.start }
        val last = ranges.indexOfFirst { it.endExclusive == selection.endExclusive }
        if (first < 0 || last < first || direct.subList(first, last + 1).any { kind(it) != "DeclStmt" }) {
            reject(CStatementExtractionRefusal.INVALID_SELECTION)
        }
        if (last - first + 1 > 16) reject(CStatementExtractionRefusal.INPUT_LIMIT)
        val chosen = direct.subList(first, last + 1)
        val parameterNodes = children(definition).filter { kind(it) == "ParmVarDecl" }
        if (parameterNodes.any { children(it).isNotEmpty() }) reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
        val parameters = parameterNodes.withIndex().associate { (ordinal, node) ->
            val id = id(node)
            verifyName(node, file, source)
            id to CStatementExtractionInput(name(node), ordinal, span(node, file, source))
        }
        if (parameters.size != parameterNodes.size) reject(CStatementExtractionRefusal.INVALID_AST)
        val values = nodes.filter { kind(it) in setOf("ParmVarDecl", "VarDecl") }
        val indexedValues = values.associateBy(::id)
        if (indexedValues.size != values.size) reject(CStatementExtractionRefusal.INVALID_AST)
        val selectedLocals = linkedMapOf<String, String>()
        val usedInputs = linkedSetOf<String>()

        fun expression(node: JsonObject, enclosing: CFunctionByteSpan) {
            val location = span(node, file, source)
            if (location.start < enclosing.start || location.endExclusive > enclosing.endExclusive) reject(CStatementExtractionRefusal.INVALID_AST)
            if (type(node) != "int") reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
            val nested = children(node)
            when (kind(node)) {
                "IntegerLiteral" -> if (nested.isNotEmpty() || string(node, "value")?.toIntOrNull() == null) reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                "CharacterLiteral" -> if (nested.isNotEmpty() || (node["value"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull == null) reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                "ParenExpr" -> {
                    if (nested.size != 1) reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location)
                }
                "ImplicitCastExpr" -> {
                    if (nested.size != 1 || string(node, "castKind") != "LValueToRValue") reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location)
                }
                "BinaryOperator" -> {
                    if (nested.size != 2 || string(node, "opcode") !in setOf("+", "-", "*")) reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                    nested.forEach { expression(it, location) }
                }
                "UnaryOperator" -> {
                    if (nested.size != 1 || string(node, "opcode") !in setOf("+", "-")) reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
                    expression(nested.single(), location)
                }
                "DeclRefExpr" -> {
                    if (nested.isNotEmpty()) reject(CStatementExtractionRefusal.INVALID_AST)
                    val referenced = node["referencedDecl"] as? JsonObject ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
                    val referenceId = id(referenced)
                    val declared = indexedValues[referenceId] ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
                    if (name(referenced) != name(declared) || kind(referenced) != kind(declared) || type(referenced) != "int" ||
                        source.copyOfRange(location.start, location.endExclusive).toString(Charsets.UTF_8) != name(declared)) {
                        reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
                    }
                    when (referenceId) {
                        in parameters -> usedInputs += referenceId
                        in selectedLocals -> Unit
                        else -> reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
                    }
                }
                else -> reject(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
            }
        }

        chosen.forEach { statement ->
            val variable = children(statement).singleOrNull()?.takeIf { kind(it) == "VarDecl" }
                ?: reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
            if (type(variable) != "int" || variable.containsKey("storageClass") || variable.containsKey("tls") || string(variable, "init") != "c") {
                reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
            }
            val initializer = children(variable).singleOrNull() ?: reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
            verifyName(variable, file, source)
            if (name(variable) in selectedLocals.values || name(variable) in parameters.values.map { it.name }) reject(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
            expression(initializer, span(variable, file, source))
            if (selectedLocals.put(id(variable), name(variable)) != null) reject(CStatementExtractionRefusal.INVALID_AST)
        }
        val outputId = selectedLocals.keys.last()
        bodyNodes.filter { kind(it) == "DeclRefExpr" }.forEach { reference ->
            val referenced = reference["referencedDecl"] as? JsonObject ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
            val referenceId = id(referenced)
            val declared = indexedValues[referenceId] ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
            val location = span(reference, file, source)
            if (kind(referenced) != kind(declared) || name(referenced) != name(declared) ||
                source.copyOfRange(location.start, location.endExclusive).toString(Charsets.UTF_8) != name(declared)) {
                reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
            }
            val outside = location.start < selection.start || location.endExclusive > selection.endExclusive
            if (outside && referenceId in selectedLocals && referenceId != outputId) reject(CStatementExtractionRefusal.NONFINAL_LIVE_OUTPUT)
        }
        val names = nodes.filter { kind(it) in setOf("FunctionDecl", "VarDecl", "ParmVarDecl", "TypedefDecl", "EnumConstantDecl") }.map(::name).toSortedSet()
        CStatementExtractionReadout.Found(CStatementExtractionFacts(file.normalize(), family.sourceSha256,
            span(definition, file, source), selection, immutable(ranges.subList(first, last + 1)),
            immutable(parameters.filterKeys { it in usedInputs }.values.sortedBy { it.parameterOrdinal }),
            immutable(selectedLocals.values.toList()), selectedLocals.getValue(outputId), Collections.unmodifiableSet(names)))
    } catch (failure: Rejected) {
        CStatementExtractionReadout.Refused(failure.reason)
    } catch (_: IllegalArgumentException) {
        CStatementExtractionReadout.Refused(CStatementExtractionRefusal.INVALID_AST)
    } catch (_: IllegalStateException) {
        CStatementExtractionReadout.Refused(CStatementExtractionRefusal.INVALID_AST)
    }

    private fun string(node: JsonObject?, key: String) = CFunctionFamilyFactsDecoder.string(node, key)
    private fun kind(node: JsonObject) = string(node, "kind") ?: reject(CStatementExtractionRefusal.INVALID_AST)
    private fun id(node: JsonObject) = string(node, "id") ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
    private fun name(node: JsonObject) = string(node, "name") ?: reject(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
    private fun type(node: JsonObject) = string(node["type"] as? JsonObject, "qualType")
    private fun children(node: JsonObject) = CFunctionFamilyFactsDecoder.children(node)
    private fun span(node: JsonObject, file: Path, source: ByteArray) = CFunctionFamilyFactsDecoder.span(node, file, source)
    private fun verifyName(node: JsonObject, file: Path, source: ByteArray) {
        val location = node["loc"] ?: reject(CStatementExtractionRefusal.INVALID_AST)
        val token = span(JsonObject(mapOf("range" to JsonObject(mapOf("begin" to location, "end" to location)))), file, source)
        if (source.copyOfRange(token.start, token.endExclusive).toString(Charsets.UTF_8) != name(node)) reject(CStatementExtractionRefusal.INVALID_AST)
    }
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    private class Rejected(val reason: CStatementExtractionRefusal) : IllegalArgumentException()
    private fun reject(reason: CStatementExtractionRefusal): Nothing = throw Rejected(reason)
}

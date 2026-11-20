package org.refactorkit.c

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

/** Half-open UTF-8 byte span in captured source material. */
data class CFunctionByteSpan(val start: Int, val endExclusive: Int)
data class CFunctionParameterFact(val name: String, val span: CFunctionByteSpan)
sealed interface CFunctionParameterClauseEvidence {
    data object Unavailable : CFunctionParameterClauseEvidence
    data class NonEmpty(val span: CFunctionByteSpan) : CFunctionParameterClauseEvidence
    data class ExplicitVoid(val span: CFunctionByteSpan) : CFunctionParameterClauseEvidence
}
sealed interface CFunctionParameterScopeEvidence {
    data object Unavailable : CFunctionParameterScopeEvidence
    data class Available(val ordinaryNames: Set<String>) : CFunctionParameterScopeEvidence
}
data class CFunctionDeclarationFact(
    val nameSpan: CFunctionByteSpan,
    val parameters: List<CFunctionParameterFact>,
    val isDefinition: Boolean,
    val parameterClause: CFunctionParameterClauseEvidence = CFunctionParameterClauseEvidence.Unavailable,
    val parameterScope: CFunctionParameterScopeEvidence = CFunctionParameterScopeEvidence.Unavailable,
)
data class CFunctionArgumentFact(val span: CFunctionByteSpan, val effect: CArgumentEffectEvidence)
data class CFunctionCallFact(val arguments: List<CFunctionArgumentFact>, val span: CFunctionByteSpan)
data class CFunctionFamilyFacts(
    val name: String,
    val sourcePath: Path,
    val sourceSha256: String,
    val family: CDirectCallFamilyEvidence,
    val declarations: List<CFunctionDeclarationFact>,
    val calls: List<CFunctionCallFact>,
)
enum class CFunctionFactsRefusal { INVALID_AST, INPUT_LIMIT, INCOMPLETE_FAMILY, AMBIGUOUS_FAMILY, UNOWNED_OR_MACRO_RANGE, UNSUPPORTED_SIGNATURE }
sealed interface CFunctionFamilyFactsReadout {
    data class Found(val facts: CFunctionFamilyFacts) : CFunctionFamilyFactsReadout
    data object NotFound : CFunctionFamilyFactsReadout
    data class Refused(val reason: CFunctionFactsRefusal) : CFunctionFamilyFactsReadout
}

/** Pure decoding of supplied AST facts, not authority for a compiler execution or patch. */
internal object CFunctionFamilyFactsDecoder {
    private val scalarSignatureTypes = setOf("int", "long", "double")

    fun decode(ast: JsonObject, file: Path, source: ByteArray, functionName: String): CFunctionFamilyFactsReadout = try {
        if (source.size > 1_048_576) reject(CFunctionFactsRefusal.INPUT_LIMIT)
        if (string(ast, "kind") != "TranslationUnitDecl") reject(CFunctionFactsRefusal.INVALID_AST)
        val nodes = flatten(ast)
        // This first cell owns one source file. File elision cannot hide header authority.
        nodes.forEach { node ->
            listOfNotNull(node["loc"], (node["range"] as? JsonObject)?.get("begin"),
                (node["range"] as? JsonObject)?.get("end")).forEach { checkCoordinateFile(it, file) }
        }
        val functions = nodes.filter { string(it, "kind") == "FunctionDecl" }
        val indexed = functions.associateBy { string(it, "id") ?: reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY) }
        if (indexed.size != functions.size) reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
        val selected = functions.filter { string(it, "name") == functionName }
        if (selected.isEmpty()) CFunctionFamilyFactsReadout.NotFound else {
            if (selected.size > 64) reject(CFunctionFactsRefusal.INPUT_LIMIT)
            val roots = selected.map { declaration ->
                var current = declaration
                val visited = mutableSetOf<String>()
                while (true) {
                    val id = string(current, "id") ?: reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
                    if (!visited.add(id) || string(current, "name") != functionName) reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
                    if (visited.size > 64) reject(CFunctionFactsRefusal.INPUT_LIMIT)
                    val previous = current["previousDecl"] ?: break
                    val previousId = (previous as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?: reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
                    current = indexed[previousId] ?: reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
                }
                string(current, "id")!!
            }.toSet()
            if (roots.size != 1) reject(CFunctionFactsRefusal.AMBIGUOUS_FAMILY)
            if (selected.map { string(it["type"] as? JsonObject, "qualType") }.toSet().size != 1) {
                reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
            }
            val typedefs = nodes.filter { string(it, "kind") == "TypedefDecl" }.associate {
                (string(it, "id") ?: reject(CFunctionFactsRefusal.INVALID_AST)) to
                    (string(it, "name") ?: reject(CFunctionFactsRefusal.INVALID_AST))
            }
            val declarations = selected.map { declaration -> declarationFact(declaration, file, source, typedefs) }.sortedBy { it.nameSpan.start }
            if (declarations.count { it.isDefinition } != 1) reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
            if (declarations.map { it.parameters.size }.toSet().size != 1) reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
            val familyIds = selected.map { string(it, "id")!! }.toSet()
            val references = nodes.filter { string(it, "kind") == "DeclRefExpr" && string(it["referencedDecl"] as? JsonObject, "kind") == "FunctionDecl" }
            references.forEach { reference ->
                if (string(reference["referencedDecl"] as? JsonObject, "id") !in indexed) reject(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
            }
            val directReferenceIds = mutableSetOf<String>()
            val callNodes = nodes.filter { string(it, "kind") == "CallExpr" }
            val incompleteConsumers = nodes.any { string(it, "kind") in setOf("GCCAsmStmt", "MSAsmStmt", "AliasAttr", "AsmLabelAttr", "IFuncAttr") } ||
                callNodes.any { children(it).firstOrNull()?.let(::directCallee) == null }
            val calls = callNodes.mapNotNull { call ->
                val children = children(call)
                val callee = children.firstOrNull()?.let(::directCallee)
                if (callee == null || string(callee["referencedDecl"] as? JsonObject, "id") !in familyIds) null else {
                    directReferenceIds += string(callee, "id") ?: reject(CFunctionFactsRefusal.INVALID_AST)
                    if (children.size - 1 != declarations.first().parameters.size) reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
                    val arguments = children.drop(1).map { argument -> CFunctionArgumentFact(span(argument, file, source), effect(argument)) }
                    CFunctionCallFact(immutable(arguments), span(call, file, source))
                }
            }.sortedBy { it.span.start }
            if (calls.size > 8_192) reject(CFunctionFactsRefusal.INPUT_LIMIT)
            val addressTaken = references.any { reference ->
                string(reference["referencedDecl"] as? JsonObject, "id") in familyIds && string(reference, "id") !in directReferenceIds
            }
            val family = when {
                addressTaken -> CDirectCallFamilyEvidence.ADDRESS_TAKEN
                incompleteConsumers -> CDirectCallFamilyEvidence.UNAVAILABLE
                selected.any { string(it, "storageClass") != "static" } -> CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS
                else -> CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC
            }
            CFunctionFamilyFactsReadout.Found(CFunctionFamilyFacts(functionName, file.normalize(),
                MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) },
                family, immutable(declarations), immutable(calls)))
        }
    } catch (failure: Rejected) {
        CFunctionFamilyFactsReadout.Refused(failure.reason)
    } catch (_: IllegalArgumentException) {
        CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INVALID_AST)
    }

    private fun declarationFact(node: JsonObject, file: Path, source: ByteArray, typedefs: Map<String, String>): CFunctionDeclarationFact {
        val nameSpan = coordinate(node["loc"], file, source)
        val name = string(node, "name") ?: reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        if (text(source, nameSpan) != name) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        val nested = children(node)
        val parameters = nested.filter { string(it, "kind") == "ParmVarDecl" }.map { parameter ->
            val parameterName = string(parameter, "name") ?: reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
            if (string(parameter["type"] as? JsonObject, "qualType") !in scalarSignatureTypes) reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
            if (text(source, coordinate(parameter["loc"], file, source)) != parameterName) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
            CFunctionParameterFact(parameterName, span(parameter, file, source))
        }
        if (parameters.size > 64) reject(CFunctionFactsRefusal.INPUT_LIMIT)
        val parameterTypes = nested.filter { string(it, "kind") == "ParmVarDecl" }
            .map { string(it["type"] as? JsonObject, "qualType") ?: reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE) }
        val parameterClauseType = if (parameterTypes.isEmpty()) "void" else parameterTypes.joinToString(", ")
        val expectedTypes = scalarSignatureTypes.map { "$it ($parameterClauseType)" }
        val variadic = node["variadic"]
        if (variadic != null && ((variadic as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != false)) {
            reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        }
        if (string(node["type"] as? JsonObject, "qualType") !in expectedTypes) reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        span(node, file, source)
        val clause = parameterClause(nameSpan, parameters, source)
        if (clause == CFunctionParameterClauseEvidence.Unavailable) reject(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        return CFunctionDeclarationFact(nameSpan, immutable(parameters), nested.any { string(it, "kind") == "CompoundStmt" },
            clause, parameterScope(node, parameters, typedefs))
    }

    private fun parameterScope(node: JsonObject, parameters: List<CFunctionParameterFact>, typedefs: Map<String, String>): CFunctionParameterScopeEvidence {
        val names = parameters.mapTo(mutableSetOf(), CFunctionParameterFact::name)
        val body = children(node).singleOrNull { string(it, "kind") == "CompoundStmt" }
        body?.let { flatten(it) }?.forEach { part ->
            if (string(part, "kind") in setOf("VarDecl", "ParmVarDecl", "TypedefDecl", "EnumConstantDecl", "FunctionDecl")) {
                names += string(part, "name") ?: return CFunctionParameterScopeEvidence.Unavailable
            }
            if (string(part, "kind") == "DeclRefExpr") {
                val declaration = part["referencedDecl"] as? JsonObject ?: return CFunctionParameterScopeEvidence.Unavailable
                names += string(declaration, "name") ?: return CFunctionParameterScopeEvidence.Unavailable
            }
            listOf("type", "argType").forEach { key ->
                val type = part[key] as? JsonObject
                if (type?.containsKey("typeAliasDeclId") == true) {
                    val identity = string(type, "typeAliasDeclId") ?: return CFunctionParameterScopeEvidence.Unavailable
                    names += typedefs[identity] ?: return CFunctionParameterScopeEvidence.Unavailable
                }
            }
        }
        return CFunctionParameterScopeEvidence.Available(Collections.unmodifiableSet(names.toSortedSet()))
    }

    /** Validate delimiters adjacent to the selected compiler name; never select by source spelling. */
    private fun parameterClause(name: CFunctionByteSpan, parameters: List<CFunctionParameterFact>, source: ByteArray): CFunctionParameterClauseEvidence {
        val open = skipTrivia(source, name.endExclusive)
        if (open < 0 || open >= source.size || source[open].toInt() != '('.code) return CFunctionParameterClauseEvidence.Unavailable
        if (parameters.isEmpty()) {
            val start = skipTrivia(source, open + 1)
            if (start < 0 || start + 4 > source.size || source.copyOfRange(start, start + 4).toString(Charsets.UTF_8) != "void") return CFunctionParameterClauseEvidence.Unavailable
            val close = skipTrivia(source, start + 4)
            return if (close >= 0 && close < source.size && source[close].toInt() == ')'.code) {
                CFunctionParameterClauseEvidence.ExplicitVoid(CFunctionByteSpan(start, start + 4))
            } else CFunctionParameterClauseEvidence.Unavailable
        }
        var cursor = open + 1
        var depth = 1
        while (cursor < source.size) {
            cursor = skipTrivia(source, cursor)
            if (cursor < 0 || cursor >= source.size) break
            when (source[cursor].toInt()) {
                '('.code -> if (++depth > 64) return CFunctionParameterClauseEvidence.Unavailable
                ')'.code -> if (--depth == 0) {
                    if (parameters.any { it.span.start < open + 1 || it.span.endExclusive > cursor }) return CFunctionParameterClauseEvidence.Unavailable
                    return CFunctionParameterClauseEvidence.NonEmpty(CFunctionByteSpan(open + 1, cursor))
                }
                '"'.code, '\''.code, ';'.code, '{'.code, '}'.code -> return CFunctionParameterClauseEvidence.Unavailable
            }
            cursor++
        }
        return CFunctionParameterClauseEvidence.Unavailable
    }

    private fun skipTrivia(source: ByteArray, from: Int): Int {
        var cursor = from
        while (cursor < source.size) {
            if (source[cursor].toInt() in listOf(9, 10, 11, 12, 13, 32)) { cursor++; continue }
            if (cursor + 1 < source.size && source[cursor].toInt() == '/'.code) {
                if (source[cursor + 1].toInt() == '/'.code) {
                    cursor += 2
                    while (cursor < source.size && source[cursor].toInt() != 10) cursor++
                    continue
                }
                if (source[cursor + 1].toInt() == '*'.code) {
                    cursor += 2
                    while (cursor + 1 < source.size && !(source[cursor].toInt() == '*'.code && source[cursor + 1].toInt() == '/'.code)) cursor++
                    if (cursor + 1 >= source.size) return -1
                    cursor += 2
                    continue
                }
            }
            return cursor
        }
        return cursor
    }

    private fun directCallee(node: JsonObject): JsonObject? {
        var current = node
        repeat(256) {
            when (string(current, "kind")) {
                "DeclRefExpr" -> return current.takeIf { string(it["referencedDecl"] as? JsonObject, "kind") == "FunctionDecl" }
                "ParenExpr" -> current = children(current).singleOrNull() ?: return null
                "ImplicitCastExpr" -> {
                    if (string(current, "castKind") != "FunctionToPointerDecay") return null
                    current = children(current).singleOrNull() ?: return null
                }
                else -> return null
            }
        }
        return null
    }

    private fun effect(node: JsonObject): CArgumentEffectEvidence {
        val kind = string(node, "kind")
        if (kind in setOf("CallExpr", "CompoundAssignOperator") ||
            kind == "UnaryOperator" && string(node, "opcode") in setOf("++", "--") ||
            kind == "BinaryOperator" && string(node, "opcode") == "=") return CArgumentEffectEvidence.OBSERVABLE_EFFECT
        if (string(node["type"] as? JsonObject, "qualType") != "int") return CArgumentEffectEvidence.UNAVAILABLE
        return when (kind) {
            "IntegerLiteral", "CharacterLiteral" -> CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT
            "DeclRefExpr" -> {
                val declaration = node["referencedDecl"] as? JsonObject
                if (string(declaration, "kind") in setOf("VarDecl", "ParmVarDecl") &&
                    string(declaration?.get("type") as? JsonObject, "qualType") == "int") CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT
                else CArgumentEffectEvidence.UNAVAILABLE
            }
            "ParenExpr", "ImplicitCastExpr" -> {
                if (kind == "ImplicitCastExpr" && string(node, "castKind") !in setOf("LValueToRValue", "NoOp", "IntegralCast")) CArgumentEffectEvidence.UNAVAILABLE
                else children(node).singleOrNull()?.let(::effect) ?: CArgumentEffectEvidence.UNAVAILABLE
            }
            else -> CArgumentEffectEvidence.UNAVAILABLE
        }
    }

    internal fun flatten(root: JsonObject): List<JsonObject> {
        val pending = ArrayDeque<Pair<JsonObject, Int>>()
        val nodes = mutableListOf<JsonObject>()
        pending.addLast(root to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeLast()
            if (depth > 256 || nodes.size >= 100_000) reject(CFunctionFactsRefusal.INPUT_LIMIT)
            nodes += node
            val nested = children(node)
            if (nested.size > 100_000 - nodes.size - pending.size) reject(CFunctionFactsRefusal.INPUT_LIMIT)
            nested.asReversed().forEach { pending.addLast(it to depth + 1) }
        }
        return nodes
    }

    internal fun children(node: JsonObject): List<JsonObject> {
        val element = node["inner"] ?: return emptyList()
        val array = element as? JsonArray ?: reject(CFunctionFactsRefusal.INVALID_AST)
        return array.map { it as? JsonObject ?: reject(CFunctionFactsRefusal.INVALID_AST) }
    }

    internal fun span(node: JsonObject, file: Path, source: ByteArray): CFunctionByteSpan {
        val range = node["range"] as? JsonObject ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        val begin = coordinate(range["begin"], file, source)
        val end = coordinate(range["end"], file, source)
        if (end.endExclusive < begin.start) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        return CFunctionByteSpan(begin.start, end.endExclusive)
    }

    private fun coordinate(value: JsonElement?, file: Path, source: ByteArray): CFunctionByteSpan {
        val location = value as? JsonObject ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        checkCoordinateFile(location, file)
        if (location.containsKey("spellingLoc") || location.containsKey("expansionLoc") || location.containsKey("includedFrom")) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        val offset = integer(location, "offset") ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        val length = integer(location, "tokLen") ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        if (offset < 0 || offset > source.size || length <= 0 || length > source.size - offset) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        val end = offset + length
        if (source[offset].toInt() and 0xc0 == 0x80 || end < source.size && source[end].toInt() and 0xc0 == 0x80) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        return CFunctionByteSpan(offset, end)
    }

    private fun checkCoordinateFile(value: JsonElement, file: Path) {
        val location = value as? JsonObject ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        if (location.containsKey("file")) {
            val owned = string(location, "file") ?: reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
            if (Path.of(owned).normalize() != file.normalize()) reject(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        }
        listOf("spellingLoc", "expansionLoc").forEach { key -> location[key]?.let { checkCoordinateFile(it, file) } }
    }

    private fun text(source: ByteArray, span: CFunctionByteSpan) = source.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    internal fun string(node: JsonObject?, key: String) = (node?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun integer(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
    private class Rejected(val reason: CFunctionFactsRefusal) : IllegalArgumentException()
    private fun reject(reason: CFunctionFactsRefusal): Nothing = throw Rejected(reason)
}

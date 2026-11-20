package org.refactorkit.c

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

internal enum class CVariableRenameKind { PARAMETER, LOCAL, INTERNAL_OBJECT }
internal enum class CVariableRenameRefusal {
    INPUT_LIMIT, INVALID_AST, UNSUPPORTED_SOURCE, SELECTION_UNAVAILABLE,
    EXTERNAL_LINKAGE, UNBOUND_REFERENCE, UNOWNED_OR_MACRO_RANGE, UNSUPPORTED_DECLARATION,
}

/** Source-owned compiler binding facts; these do not authorize a process or patch. */
internal class CVariableRenameFacts(
    val sourcePath: Path,
    val sourceSha256: String,
    val name: String,
    val type: String,
    val kind: CVariableRenameKind,
    val declaration: CFunctionByteSpan,
    val declarationAstPath: String,
    val scopeAstPath: String,
    references: List<CFunctionByteSpan>,
    referenceAstPaths: List<String>,
) {
    val references: List<CFunctionByteSpan> = Collections.unmodifiableList(ArrayList(references))
    val referenceAstPaths: List<String> = Collections.unmodifiableList(ArrayList(referenceAstPaths))
}

internal sealed interface CVariableRenameReadout {
    data class Found(val facts: CVariableRenameFacts) : CVariableRenameReadout
    data class Refused(val reason: CVariableRenameRefusal) : CVariableRenameReadout
}

internal enum class CVariableRenameTransitionRefusal { REPLACEMENT_NAME, SOURCE_IDENTITY, DECLARATION_IDENTITY, REFERENCE_FAMILY, REFERENCE_RANGES }
internal sealed interface CVariableRenameTransition {
    data object Verified : CVariableRenameTransition
    data class Refused(val reason: CVariableRenameTransitionRefusal) : CVariableRenameTransition
}

/** Pure post-model rule; native acquisition and workspace edits remain separate. */
internal object CVariableRenameBinding {
    fun check(before: CVariableRenameFacts, after: CVariableRenameFacts, replacement: String): CVariableRenameTransition {
        fun refused(reason: CVariableRenameTransitionRefusal) = CVariableRenameTransition.Refused(reason)
        if (replacement.length !in 1..128 || !replacement.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) ||
            replacement == before.name || after.name != replacement) return refused(CVariableRenameTransitionRefusal.REPLACEMENT_NAME)
        if (before.sourcePath != after.sourcePath || before.sourceSha256 == after.sourceSha256) {
            return refused(CVariableRenameTransitionRefusal.SOURCE_IDENTITY)
        }
        if (before.type != after.type || before.kind != after.kind || before.declarationAstPath != after.declarationAstPath ||
            before.scopeAstPath != after.scopeAstPath) return refused(CVariableRenameTransitionRefusal.DECLARATION_IDENTITY)
        if (before.referenceAstPaths != after.referenceAstPaths || before.references.size != after.references.size ||
            before.references.size != before.referenceAstPaths.size) return refused(CVariableRenameTransitionRefusal.REFERENCE_FAMILY)
        val spans = (listOf(before.declaration) + before.references).sortedBy { it.start }
        if (spans.any { it.start < 0 || it.endExclusive - it.start != before.name.length } ||
            spans.zipWithNext().any { (first, second) -> first.endExclusive > second.start }) {
            return refused(CVariableRenameTransitionRefusal.REFERENCE_RANGES)
        }
        val delta = replacement.length - before.name.length
        fun shifted(span: CFunctionByteSpan): CFunctionByteSpan {
            val start = span.start + delta * spans.count { it.endExclusive <= span.start }
            return CFunctionByteSpan(start, start + replacement.length)
        }
        if (after.declaration != shifted(before.declaration) || after.references != before.references.map(::shifted)) {
            return refused(CVariableRenameTransitionRefusal.REFERENCE_RANGES)
        }
        return CVariableRenameTransition.Verified
    }
}

internal object CVariableRenameFactsDecoder {
    fun decode(ast: JsonObject, file: Path, source: ByteArray, selectedByteOffset: Int): CVariableRenameReadout = try {
        if (source.size > 1_048_576) reject(CVariableRenameRefusal.INPUT_LIMIT)
        if (source.any { (it.toInt() and 0xff) > 0x7f }) reject(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        val text = source.toString(Charsets.US_ASCII)
        if (text.any { it == '#' || it == '\\' } || "??" in text || "_Pragma" in text) {
            reject(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        }
        if (string(ast, "kind") != "TranslationUnitDecl") reject(CVariableRenameRefusal.INVALID_AST)
        val nodes = flatten(ast)
        if (nodes.any { string(it.node, "kind") in setOf("GCCAsmStmt", "MSAsmStmt", "FileScopeAsmDecl",
                "AsmLabelAttr", "AliasAttr", "IFuncAttr") }) reject(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        nodes.forEach { entry ->
            val node = entry.node
            listOfNotNull(node["loc"], (node["range"] as? JsonObject)?.get("begin"),
                (node["range"] as? JsonObject)?.get("end")).forEach { raw ->
                val coordinate = raw as? JsonObject ?: reject(CVariableRenameRefusal.INVALID_AST)
                if (coordinate.containsKey("spellingLoc") || coordinate.containsKey("expansionLoc") ||
                    string(coordinate, "file")?.let { Path.of(it).normalize() != file.normalize() } == true) {
                    reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
                }
            }
        }
        val indexed = linkedMapOf<String, Entry>()
        nodes.forEach { entry ->
            string(entry.node, "id")?.let { id ->
                if (indexed.put(id, entry) != null) reject(CVariableRenameRefusal.INVALID_AST)
            }
        }
        val selected = nodes.filter { entry ->
            string(entry.node, "kind") in setOf("VarDecl", "ParmVarDecl") &&
                (entry.node["loc"] as? JsonObject)?.let { location ->
                    val offset = number(location, "offset")
                    val length = number(location, "tokLen")
                    offset != null && length != null && selectedByteOffset.toLong() >= offset.toLong() &&
                        selectedByteOffset.toLong() < offset.toLong() + length.toLong()
                } == true
        }.singleOrNull() ?: reject(CVariableRenameRefusal.SELECTION_UNAVAILABLE)
        val declaration = selected.node
        val id = string(declaration, "id") ?: reject(CVariableRenameRefusal.INVALID_AST)
        val name = string(declaration, "name")?.takeIf { it.length in 1..128 && it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }
            ?: reject(CVariableRenameRefusal.UNSUPPORTED_DECLARATION)
        val type = string(declaration["type"] as? JsonObject, "qualType")?.takeIf { it.length in 1..4096 }
            ?: reject(CVariableRenameRefusal.UNSUPPORTED_DECLARATION)
        if (declaration.containsKey("previousDecl") || nodes.any { string(it.node, "previousDecl") == id }) {
            reject(CVariableRenameRefusal.UNSUPPORTED_DECLARATION)
        }
        if (string(declaration, "storageClass") == "extern") reject(CVariableRenameRefusal.EXTERNAL_LINKAGE)
        val function = selected.function
        val kind = when {
            string(declaration, "kind") == "ParmVarDecl" -> {
                if (function == null || selected.parentPath != function.path ||
                    children(function.node).none { string(it, "kind") == "CompoundStmt" }) {
                    reject(CVariableRenameRefusal.UNSUPPORTED_DECLARATION)
                }
                CVariableRenameKind.PARAMETER
            }
            function != null -> CVariableRenameKind.LOCAL
            string(declaration, "storageClass") == "static" -> CVariableRenameKind.INTERNAL_OBJECT
            else -> reject(CVariableRenameRefusal.EXTERNAL_LINKAGE)
        }
        val declarationSpan = token(declaration["loc"] as? JsonObject, name, source)
        val references = mutableListOf<Pair<CFunctionByteSpan, String>>()
        // An unavailable reference anywhere in this closed source must not hide a
        // selected use and turn into an apparently unused declaration.
        nodes.filter { string(it.node, "kind") == "DeclRefExpr" }.forEach { entry ->
            val referenced = entry.node["referencedDecl"] as? JsonObject
                ?: reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
            val referenceId = string(referenced, "id") ?: reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
            val bound = indexed[referenceId]?.node ?: reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
            if (string(referenced, "kind") != string(bound, "kind") || string(referenced, "name") != string(bound, "name")) {
                reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
            }
            if (referenceId == id) {
                if (string(referenced["type"] as? JsonObject, "qualType") != type || entry.function?.path != function?.path &&
                    kind != CVariableRenameKind.INTERNAL_OBJECT) reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
                val range = entry.node["range"] as? JsonObject ?: reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
                val begin = token(range["begin"] as? JsonObject, name, source)
                if (token(range["end"] as? JsonObject, name, source) != begin) reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
                references += begin to entry.path
                if (references.size > 4096) reject(CVariableRenameRefusal.INPUT_LIMIT)
            }
        }
        val ordered = references.sortedBy { it.first.start }
        if (ordered.map { it.first }.distinct().size != ordered.size || ordered.any { it.first == declarationSpan }) {
            reject(CVariableRenameRefusal.UNBOUND_REFERENCE)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        CVariableRenameReadout.Found(CVariableRenameFacts(file.normalize(), digest, name, type, kind,
            declarationSpan, selected.path, function?.path ?: "TU", ordered.map { it.first }, ordered.map { it.second }))
    } catch (failure: Rejected) {
        CVariableRenameReadout.Refused(failure.reason)
    } catch (_: Exception) {
        CVariableRenameReadout.Refused(CVariableRenameRefusal.INVALID_AST)
    }

    private data class FunctionScope(val node: JsonObject, val path: String)
    private data class Entry(val node: JsonObject, val path: String, val parentPath: String,
        val function: FunctionScope?, val depth: Int)

    private fun flatten(root: JsonObject): List<Entry> = buildList {
        val pending = ArrayDeque<Entry>()
        pending.add(Entry(root, "TU", "", null, 0))
        while (pending.isNotEmpty()) {
            val entry = pending.removeLast()
            add(entry)
            if (size > 100_000 || entry.depth > 256) reject(CVariableRenameRefusal.INPUT_LIMIT)
            val function = if (string(entry.node, "kind") == "FunctionDecl") FunctionScope(entry.node, entry.path) else entry.function
            children(entry.node).withIndex().reversed().forEach { (index, child) ->
                pending.add(Entry(child, "${entry.path}/$index", entry.path, function, entry.depth + 1))
            }
        }
    }

    private fun children(node: JsonObject): List<JsonObject> {
        val nested = node["inner"] ?: return emptyList()
        return (nested as? JsonArray ?: reject(CVariableRenameRefusal.INVALID_AST)).map {
            it as? JsonObject ?: reject(CVariableRenameRefusal.INVALID_AST)
        }
    }
    private fun string(node: JsonObject?, key: String): String? =
        (node?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun number(node: JsonObject, key: String): Int? = (node[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun token(location: JsonObject?, name: String, source: ByteArray): CFunctionByteSpan {
        val coordinate = location ?: reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        val start = number(coordinate, "offset") ?: reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        val length = number(coordinate, "tokLen") ?: reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        if (start < 0 || length != name.length || start > source.size - length ||
            source.copyOfRange(start, start + length).toString(Charsets.US_ASCII) != name) {
            reject(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        }
        return CFunctionByteSpan(start, start + length)
    }
    private fun reject(reason: CVariableRenameRefusal): Nothing = throw Rejected(reason)
    private class Rejected(val reason: CVariableRenameRefusal) : RuntimeException()
}

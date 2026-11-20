package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Collections

internal data class CRecordNavigationSite(val translationUnit: Path, val file: Path, val span: CFunctionByteSpan)
internal class CRecordNavigationFacts(definitions: List<CRecordNavigationSite>, declarations: List<CRecordNavigationSite>,
    references: List<CRecordNavigationSite>) {
    val definitions: List<CRecordNavigationSite> = Collections.unmodifiableList(ArrayList(definitions))
    val declarations: List<CRecordNavigationSite> = Collections.unmodifiableList(ArrayList(declarations))
    val references: List<CRecordNavigationSite> = Collections.unmodifiableList(ArrayList(references))
}
internal enum class CRecordNavigationRefusal { INPUT_LIMIT, INCOMPLETE_TUS, INVALID_AST, SELECTION_UNAVAILABLE,
    UNSUPPORTED_SOURCE, AMBIGUOUS_FAMILY, UNBOUND_REFERENCE }
internal sealed interface CRecordNavigationReadout {
    data class Found(val facts: CRecordNavigationFacts) : CRecordNavigationReadout
    data class Refused(val reason: CRecordNavigationRefusal) : CRecordNavigationReadout
}

/** Read-only canonical record and enum families; incomplete records never acquire mutation authority. */
internal object CRecordNavigationFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        selectedTranslationUnit: Path, prototypeTags: CPrototypeTagNavigationFacts? = null): CRecordNavigationReadout = try {
        if (prototypeTags != null && !prototypeTags.matchesNativeInputs(asts, sources)) fail(CRecordNavigationRefusal.INVALID_AST)
        if (asts.isEmpty() || asts.keys != sources.keys.filter { it.toString().endsWith(".c") }.toSet())
            fail(CRecordNavigationRefusal.INCOMPLETE_TUS)
        if (asts.size > 16) fail(CRecordNavigationRefusal.INPUT_LIMIT)
        if (CHeaderFieldSourcePolicy.check(sources) !is CHeaderFieldSourceAdmission.Admitted)
            fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        if (selectedTranslationUnit !in asts || selectedFile !in sources)
            fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
        val streams = asts.mapValues { (tu, tree) -> CClangSourceGraph.read(tree, tu, sources) }
        fun declarations(entries: List<CClangSourceEntry>): Map<String, CClangSourceEntry> {
            val found = linkedMapOf<String, CClangSourceEntry>()
            entries.filter { string(it.node, "kind")?.endsWith("Decl") == true }.forEach { entry ->
                string(entry.node, "id")?.let { if (found.put(it, entry) != null) fail(CRecordNavigationRefusal.INVALID_AST) }
            }
            return found
        }
        val initial = streams.getValue(selectedTranslationUnit)
        val selected = initial.filter { string(it.node, "kind") in setOf("RecordDecl", "EnumDecl") && it.location?.let { point ->
            point.file == selectedFile && selectedOffset >= point.start && selectedOffset < point.end
        } == true }.singleOrNull() ?: fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
        val name = string(selected.node, "name") ?: fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        val keyword = if (string(selected.node, "kind") == "EnumDecl") "enum"
            else string(selected.node, "tagUsed")?.takeIf { it in setOf("struct", "union") }
                ?: fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        fun canonical(entry: CClangSourceEntry, indexed: Map<String, CClangSourceEntry>, scopes: CRecordScopeIndex): CClangSourceEntry {
            val kind = string(entry.node, "kind")
            val tag = string(entry.node, "tagUsed")
            val scope = scopes.declarationScope(entry)
            var current = entry
            val seen = mutableSetOf<String>()
            while (true) {
                val id = string(current.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                if (!seen.add(id)) fail(CRecordNavigationRefusal.INVALID_AST)
                if (scopes.declarationScope(current) != scope || string(current.node, "kind") != kind ||
                    string(current.node, "name") != name || string(current.node, "tagUsed") != tag)
                    fail(CRecordNavigationRefusal.AMBIGUOUS_FAMILY)
                val previous = string(current.node, "previousDecl") ?: return current
                current = indexed[previous] ?: fail(CRecordNavigationRefusal.INVALID_AST)
            }
        }
        val initialScopes = CRecordScopeIndex(initial)
        val initialRoot = canonical(selected, declarations(initial), initialScopes)
        val root = initialRoot.location ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
        val localScope = initialScopes.declarationScope(initialRoot) != "TU"
        if (localScope && root.file != selectedTranslationUnit) fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        fun owned(tu: Path, point: CClangSourceCoordinate?): CRecordNavigationSite {
            val value = point ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
            if (sources[value.file]?.substring(value.start, value.end) != name) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
            return CRecordNavigationSite(tu, value.file, CFunctionByteSpan(value.start, value.end))
        }
        val definitions = mutableListOf<CRecordNavigationSite>()
        val declared = mutableListOf<CRecordNavigationSite>()
        val references = mutableListOf<CRecordNavigationSite>()
        val tokenized = sources.mapValues { (_, source) ->
            val starts = buildList { add(0); source.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            CTokenizer().tokenize(source).map { token ->
                val start = starts[token.line - 1] + token.column
                Token(token.text, start, start + token.text.length, token.type)
            }
        }
        fun tagType(type: JsonObject): Boolean {
            val qualified = string(type, "qualType") ?: fail(CRecordNavigationRefusal.INVALID_AST)
            return listOfNotNull(qualified, string(type, "desugaredQualType")).any { text ->
                CTokenizer().tokenize(text).zipWithNext().any { (a, b) -> a.text == keyword && b.text == name }
            }
        }
        for ((tu, entries) in streams.entries.sortedBy { it.key.toString() }) {
            if (localScope && tu != selectedTranslationUnit) continue
            val tags = entries.filter { string(it.node, "kind") in setOf("RecordDecl", "EnumDecl") && string(it.node, "name") == name }
            if (tags.none { it.location == root }) continue
            if (entries.any { string(it.node, "kind") in setOf("CXXRecordDecl", "ObjCInterfaceDecl", "GCCAsmStmt", "MSAsmStmt", "AliasAttr", "IFuncAttr") })
                fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
            val indexed = declarations(entries)
            val scopes = CRecordScopeIndex(entries)
            val roots = tags.map { canonical(it, indexed, scopes) }.distinctBy { string(it.node, "id") }
            val familyRoot = roots.singleOrNull { it.location == root }
                ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
            val rootId = string(familyRoot.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
            val family = tags.filter { string(canonical(it, indexed, scopes).node, "id") == rootId }
            val points = tags.map { it.location ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE) }.toSet()
            family.forEach { entry ->
                val point = owned(tu, entry.location); declared += point
                val enumBody = string(entry.node, "kind") == "EnumDecl" &&
                    (entry.node["inner"] as? JsonArray)?.any { child ->
                        (child as? JsonObject)?.let { string(it, "kind") == "EnumConstantDecl" } == true
                    } == true
                if (enumBody || entry.node["completeDefinition"]?.jsonPrimitive?.content == "true") definitions += point
            }
            val anchors = entries.filter { entry ->
                listOf("type", "argType").any { property ->
                    val raw = entry.node[property]
                    raw != null && tagType(raw as? JsonObject ?: fail(CRecordNavigationRefusal.INVALID_AST))
                }
            }
            val seenFiles = entries.flatMap { listOfNotNull(it.location, it.begin, it.end).map { p -> p.file } }.toSet()
            for (file in seenFiles) {
                val tokens = tokenized.getValue(file)
                tokens.zipWithNext().filter { (a, token) -> a.text == keyword && token.text == name && token.kind == CTokenType.IDENTIFIER }
                    .forEach { (_, token) ->
                        val point = CClangSourceCoordinate(file, token.start, token.end)
                        if (point !in points) {
                            if (prototypeTags?.excludes(CRecordNavigationSite(tu, file, CFunctionByteSpan(point.start, point.end))) == true)
                                return@forEach
                            val visible = scopes.visible(roots, point) ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            if (string(visible.node, "id") != rootId) return@forEach
                            val witness = anchors.any { entry -> scopes.precedes(familyRoot, entry) && entry.begin?.let { begin -> entry.end?.let { end ->
                                begin.file == file && end.file == file && begin.start <= point.start && end.end >= point.end
                            } } == true }
                            if (!witness) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            references += owned(tu, point)
                        }
                    }
                if (definitions.size + declared.size + references.size > 8192) fail(CRecordNavigationRefusal.INPUT_LIMIT)
            }
        }
        if (declared.isEmpty()) fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
        if (definitions.map { it.file to it.span }.distinct().size > 1) fail(CRecordNavigationRefusal.AMBIGUOUS_FAMILY)
        fun ordered(values: List<CRecordNavigationSite>) = values.distinct().sortedWith(compareBy(
            { it.translationUnit.toString() }, { it.file.toString() }, { it.span.start }))
        CRecordNavigationReadout.Found(CRecordNavigationFacts(ordered(definitions), ordered(declared), ordered(references)))
    } catch (failure: CRecordScopeFailure) {
        CRecordNavigationReadout.Refused(failure.reason)
    } catch (failure: ReadFailure) {
        CRecordNavigationReadout.Refused(failure.reason)
    } catch (failure: CClangSourceGraphFailure) {
        CRecordNavigationReadout.Refused(if (failure.reason == CClangSourceGraphRefusal.INPUT_LIMIT)
            CRecordNavigationRefusal.INPUT_LIMIT else CRecordNavigationRefusal.INVALID_AST)
    } catch (_: Exception) {
        CRecordNavigationReadout.Refused(CRecordNavigationRefusal.INVALID_AST)
    }
    private data class Token(val text: String, val start: Int, val end: Int, val kind: CTokenType)
    private fun string(node: JsonObject, key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private class ReadFailure(val reason: CRecordNavigationRefusal) : RuntimeException()
    private fun fail(reason: CRecordNavigationRefusal): Nothing = throw ReadFailure(reason)
}

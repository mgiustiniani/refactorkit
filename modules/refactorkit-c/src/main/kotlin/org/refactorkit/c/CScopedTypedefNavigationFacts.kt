package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path

internal sealed interface CScopedTypedefNavigationReadout {
    data class Found(val facts: CRecordNavigationFacts) : CScopedTypedefNavigationReadout
    data object NotScopedFamily : CScopedTypedefNavigationReadout
    data class Refused(val reason: CRecordNavigationRefusal) : CScopedTypedefNavigationReadout
}

/** Read-only native alias families; mutation authority remains in its existing decoder. */
internal object CScopedTypedefNavigationFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, file: Path, offset: Int,
        selectedTu: Path): CScopedTypedefNavigationReadout = try {
        if (asts.isEmpty() || asts.keys != sources.keys.filter { it.toString().endsWith(".c") }.toSet())
            fail(CRecordNavigationRefusal.INCOMPLETE_TUS)
        if (asts.size > 16) fail(CRecordNavigationRefusal.INPUT_LIMIT)
        if (CHeaderFieldSourcePolicy.check(sources) !is CHeaderFieldSourceAdmission.Admitted)
            fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
        if (selectedTu !in asts || file !in sources) fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
        val streams = asts.mapValues { (tu, ast) -> CClangSourceGraph.read(ast, tu, sources) }
        val initial = streams.getValue(selectedTu)
        val selected = initial.singleOrNull { kind(it) == "TypedefDecl" && it.location?.let { p ->
            p.file == file && offset >= p.start && offset < p.end
        } == true } ?: fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
        val name = string(selected.node, "name") ?: fail(CRecordNavigationRefusal.INVALID_AST)
        if (initial.none { kind(it) == "TypedefDecl" && string(it.node, "name") == name && it.parent != "TU" })
            CScopedTypedefNavigationReadout.NotScopedFamily
        else {
            fun index(entries: List<CClangSourceEntry>): Map<String, CClangSourceEntry> {
                val result = linkedMapOf<String, CClangSourceEntry>()
                for (entry in entries.filter { kind(it)?.endsWith("Decl") == true }) {
                    val id = string(entry.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                    if (result.put(id, entry) != null) fail(CRecordNavigationRefusal.INVALID_AST)
                }
                return result
            }
            fun canonical(entry: CClangSourceEntry, byId: Map<String, CClangSourceEntry>, scopes: CRecordScopeIndex): CClangSourceEntry {
                val owner = scopes.declarationScope(entry)
                var current = entry
                val seen = mutableSetOf<String>()
                fun underlying(e: CClangSourceEntry): String {
                    val type = e.node["type"] as? JsonObject ?: fail(CRecordNavigationRefusal.INVALID_AST)
                    return string(type, "desugaredQualType") ?: string(type, "qualType") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                }
                val expectedType = underlying(entry)
                while (true) {
                    val id = string(current.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                    if (!seen.add(id)) fail(CRecordNavigationRefusal.INVALID_AST)
                    if (kind(current) != "TypedefDecl" || string(current.node, "name") != name ||
                        scopes.declarationScope(current) != owner || underlying(current) != expectedType)
                        fail(CRecordNavigationRefusal.AMBIGUOUS_FAMILY)
                    val previous = string(current.node, "previousDecl") ?: return current
                    current = byId[previous] ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                }
            }
            val initialScopes = CRecordScopeIndex(initial)
            val root = canonical(selected, index(initial), initialScopes)
            val rootPoint = root.location ?: fail(CRecordNavigationRefusal.INVALID_AST)
            val sourcePrivate = rootPoint.file.toString().endsWith(".c")
            if (initialScopes.declarationScope(root) != "TU" && rootPoint.file != selectedTu)
                fail(CRecordNavigationRefusal.UNSUPPORTED_SOURCE)
            fun owned(tu: Path, point: CClangSourceCoordinate?): CRecordNavigationSite {
                val p = point ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                if (sources.getValue(p.file).substring(p.start, p.end) != name) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                return CRecordNavigationSite(tu, p.file, CFunctionByteSpan(p.start, p.end))
            }
            val definitions = mutableListOf<CRecordNavigationSite>()
            val declarations = mutableListOf<CRecordNavigationSite>()
            val references = mutableListOf<CRecordNavigationSite>()
            val tokens = sources.mapValues { (_, text) ->
                val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
                CTokenizer().tokenize(text).map { t -> Token(t.text, starts[t.line - 1] + t.column,
                    starts[t.line - 1] + t.column + t.text.length, t.type) }
            }
            for ((tu, entries) in streams) {
                if (sourcePrivate && tu != selectedTu) continue
                val aliases = entries.filter { kind(it) == "TypedefDecl" && string(it.node, "name") == name }
                if (aliases.none { it.location == rootPoint }) continue
                val byId = index(entries)
                val scopes = CRecordScopeIndex(entries)
                val roots = aliases.map { canonical(it, byId, scopes) }.distinctBy { string(it.node, "id") }
                val familyRoot = roots.singleOrNull { it.location == rootPoint } ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                val rootId = string(familyRoot.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                val family = aliases.filter { string(canonical(it, byId, scopes).node, "id") == rootId }
                definitions += owned(tu, familyRoot.location)
                declarations += family.map { owned(tu, it.location) }
                val nonTypes = entries.filter { kind(it)?.endsWith("Decl") == true && string(it.node, "name") == name }
                    .mapNotNull { it.location }.toMutableSet()
                val labels = entries.filter { kind(it) == "LabelStmt" }.associateBy { string(it.node, "declId") }
                for (entry in entries) {
                    fun spelled(p: CClangSourceCoordinate?) = p?.let { sources.getValue(it.file).substring(it.start, it.end) == name } == true
                    when (kind(entry)) {
                        "DeclRefExpr" -> if (spelled(entry.begin) || string(entry.node["referencedDecl"] as? JsonObject, "name") == name) {
                            val ref = entry.node["referencedDecl"] as? JsonObject ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            val target = byId[string(ref, "id")] ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            if (string(target.node, "name") != name || string(ref, "name") != name || string(ref, "kind") != kind(target) || entry.begin != entry.end)
                                fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            nonTypes += entry.begin ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        }
                        "MemberExpr" -> if (string(entry.node, "name") == name) {
                            val field = byId[string(entry.node, "referencedMemberDecl")] ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            if (kind(field) != "FieldDecl" || string(field.node, "name") != name) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            nonTypes += entry.end ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        }
                        "LabelStmt" -> if (string(entry.node, "name") == name) nonTypes += entry.begin ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        "GotoStmt" -> if (spelled(entry.end)) {
                            val target = labels[string(entry.node, "targetLabelDeclId")] ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            if (string(target.node, "name") != name) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                            nonTypes += entry.end ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        }
                    }
                }
                val anchors = entries.flatMap { entry -> listOf("type", "argType").mapNotNull { property ->
                    val type = entry.node[property] as? JsonObject ?: return@mapNotNull null
                    val spelling = string(type, "qualType") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                    val words = CTokenizer().tokenize(spelling)
                    if (words.indices.none { i -> words[i].text == name && (i == 0 || words[i - 1].text !in setOf("struct", "union", "enum")) }) null
                    else entry to type
                } }
                val seenFiles = entries.flatMap { listOfNotNull(it.location, it.begin, it.end).map { p -> p.file } }.toSet()
                for (sourceFile in seenFiles) {
                    val stream = tokens.getValue(sourceFile)
                    for ((i, token) in stream.withIndex()) {
                        if (token.kind != CTokenType.IDENTIFIER || token.text != name) continue
                        val point = CClangSourceCoordinate(sourceFile, token.start, token.end)
                        if (point in nonTypes || i > 0 && stream[i - 1].text in setOf("struct", "union", "enum", ".", "->")) continue
                        val visible = scopes.visible(roots, point) ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        val visibleId = string(visible.node, "id") ?: fail(CRecordNavigationRefusal.INVALID_AST)
                        val witnesses = anchors.filter { (entry, _) -> scopes.precedes(visible, entry) && entry.begin?.let { begin -> entry.end?.let { end ->
                            begin.file == sourceFile && end.file == sourceFile && begin.start <= point.start && end.end >= point.end
                        } } == true }
                        if (witnesses.isEmpty()) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        if (witnesses.none { (_, type) ->
                            val id = string(type, "typeAliasDeclId")
                            if (id == null) true else {
                                val alias = byId[id] ?: fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                                string(alias.node, "name") == name && string(canonical(alias, byId, scopes).node, "id") == visibleId
                            }
                        }) fail(CRecordNavigationRefusal.UNBOUND_REFERENCE)
                        if (visibleId == rootId) references += owned(tu, point)
                        if (definitions.size + declarations.size + references.size > 8192) fail(CRecordNavigationRefusal.INPUT_LIMIT)
                    }
                }
            }
            if (definitions.isEmpty()) fail(CRecordNavigationRefusal.SELECTION_UNAVAILABLE)
            fun ordered(values: List<CRecordNavigationSite>) = values.distinct().sortedWith(compareBy(
                { it.translationUnit.toString() }, { it.file.toString() }, { it.span.start }))
            CScopedTypedefNavigationReadout.Found(CRecordNavigationFacts(ordered(definitions), ordered(declarations), ordered(references)))
        }
    } catch (failure: CRecordScopeFailure) {
        CScopedTypedefNavigationReadout.Refused(failure.reason)
    } catch (failure: Failure) {
        CScopedTypedefNavigationReadout.Refused(failure.reason)
    } catch (_: Exception) {
        CScopedTypedefNavigationReadout.Refused(CRecordNavigationRefusal.INVALID_AST)
    }
    private data class Token(val text: String, val start: Int, val end: Int, val kind: CTokenType)
    private fun string(node: JsonObject?, key: String): String? {
        val value = node?.get(key) ?: return null
        if (value !is JsonPrimitive || !value.isString) fail(CRecordNavigationRefusal.INVALID_AST)
        return value.content
    }
    private fun kind(entry: CClangSourceEntry) = string(entry.node, "kind")
    private class Failure(val reason: CRecordNavigationRefusal) : RuntimeException()
    private fun fail(reason: CRecordNavigationRefusal): Nothing = throw Failure(reason)
}

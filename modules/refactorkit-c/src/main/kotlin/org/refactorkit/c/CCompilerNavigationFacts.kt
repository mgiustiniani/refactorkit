package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.Collections

internal data class CCompilerNavigationSite(val file: Path, val range: SourceRange, val translationUnits: Set<Path>)
internal class CCompilerNavigationFacts(val selected: CCompilerDeclaration,
    definitions: List<CCompilerNavigationSite>, declarations: List<CCompilerNavigationSite>, references: List<CCompilerNavigationSite>) {
    val definitions: List<CCompilerNavigationSite> = Collections.unmodifiableList(ArrayList(definitions))
    val declarations: List<CCompilerNavigationSite> = Collections.unmodifiableList(ArrayList(declarations))
    val references: List<CCompilerNavigationSite> = Collections.unmodifiableList(ArrayList(references))
}
internal enum class CCompilerNavigationRefusal { PROJECTION_UNAVAILABLE, INPUT_LIMIT, INVALID_AST, UNBOUND_REFERENCE, UNSUPPORTED_FAMILY, DEFINITION_UNAVAILABLE, AMBIGUOUS_DEFINITION }
internal sealed interface CCompilerNavigationReadout {
    data class Found(val facts: CCompilerNavigationFacts) : CCompilerNavigationReadout
    data object NotFound : CCompilerNavigationReadout
    data class Refused(val reason: CCompilerNavigationRefusal, val detail: String = "") : CCompilerNavigationReadout
}
private class NavigationFailure(val reason: CCompilerNavigationRefusal, val detail: String = "") : RuntimeException()
private data class NavigationObservation(val tu: Path, val file: Path, val span: CFunctionByteSpan)
private data class NavigationFamily(val definitions: List<NavigationObservation>, val declarations: List<NavigationObservation>, val uses: List<NavigationObservation>)

/** Native binding facts only. Existing header readers supply proven type/member designator authority. */
internal object CCompilerNavigationFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, symbolId: String,
        logicalPaths: Map<Path, Path> = sources.keys.associateWith { it },
        prototypeTags: CPrototypeTagNavigationFacts? = null): CCompilerNavigationReadout = try {
        if (prototypeTags != null && (!prototypeTags.matchesNativeInputs(asts, sources) || !prototypeTags.matchesLogicalPaths(logicalPaths)))
            reject(CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE, "Prototype facts are stale or belong to another context")
        val supplemental = prototypeTags?.family(symbolId)
        if (supplemental != null) CCompilerNavigationReadout.Found(supplemental) else {
        val index = when (val result = CCompilerDeclarationIndex.analyze(asts, sources, logicalPaths)) {
            is CCompilerDeclarationReadout.Found -> result.index
            is CCompilerDeclarationReadout.Refused -> reject(CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE, result.reason.name)
        }
        val selected = index.declarations.singleOrNull { it.id == symbolId }
        if (selected == null) CCompilerNavigationReadout.NotFound else {
            val physicalFile = logicalPaths.entries.single { it.value == selected.file }.key
            val physicalTu = logicalPaths.entries.single { it.value == selected.translationUnit }.key
            val offset = TextEdits.offsetOf(sources.getValue(physicalFile), selected.range.start)
            val entries = CClangSourceGraph.read(asts.getValue(physicalTu), physicalTu, sources)
            val node = entries.singleOrNull { entry ->
                val point = if (selected.kind == CCompilerDeclarationKind.LABEL) entry.begin else entry.location
                point?.file == physicalFile && point.start == offset && string(entry.node, "name") == selected.name &&
                    (if (selected.kind == CCompilerDeclarationKind.LABEL) kind(entry) == "LabelStmt" else kind(entry)?.endsWith("Decl") == true)
            } ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            val byId = declarations(entries)
            val root = if (selected.kind in setOf(CCompilerDeclarationKind.FUNCTION, CCompilerDeclarationKind.VARIABLE))
                canonical(node, byId) else node
            val rootPoint = root.location
            val headerFile = when {
                physicalFile.toString().endsWith(".h") -> physicalFile
                rootPoint?.file?.toString()?.endsWith(".h") == true && selected.linkage == CCompilerLinkage.EXTERNAL -> rootPoint.file
                else -> null
            }
            val family = if (headerFile != null && selected.linkage != CCompilerLinkage.INTERNAL &&
                selected.kind !in setOf(CCompilerDeclarationKind.PARAMETER, CCompilerDeclarationKind.LABEL)) {
                header(asts, sources, headerFile, if (headerFile == physicalFile) offset else rootPoint!!.start, selected, selectedTu = physicalTu, prototypeTags = prototypeTags)
            } else if (physicalFile.toString().endsWith(".c") && selected.kind in setOf(
                    CCompilerDeclarationKind.STRUCT, CCompilerDeclarationKind.UNION, CCompilerDeclarationKind.ENUM,
                    CCompilerDeclarationKind.TYPEDEF, CCompilerDeclarationKind.FIELD)) {
                header(asts, sources, physicalFile, offset, selected, sourceSelection = true, selectedTu = physicalTu, prototypeTags = prototypeTags)
            } else local(entries, byId, physicalTu, sources, node, selected, index)

            fun normalized(observations: List<NavigationObservation>): List<CCompilerNavigationSite> = observations
                .groupBy { it.file to it.span }.entries.map { (point, values) ->
                    CCompilerNavigationSite(logicalPaths.getValue(point.first),
                        TextEdits.rangeForOffset(sources.getValue(point.first), point.second.start, point.second.endExclusive - point.second.start),
                        Collections.unmodifiableSet(values.map { logicalPaths.getValue(it.tu) }.sortedBy { it.toString() }.toCollection(linkedSetOf())))
                }.sortedWith(compareBy({ it.file.toString() }, { it.range.start.line }, { it.range.start.character }))
            if (family.definitions.size + family.declarations.size + family.uses.size > 8192) reject(CCompilerNavigationRefusal.INPUT_LIMIT)
            val definitions = normalized(family.definitions)
            // Known references do not require an existing complete definition.
            if (definitions.size > 1) reject(CCompilerNavigationRefusal.AMBIGUOUS_DEFINITION)
            CCompilerNavigationReadout.Found(CCompilerNavigationFacts(selected, definitions, normalized(family.declarations), normalized(family.uses)))
        }
        }
    } catch (failure: NavigationFailure) {
        CCompilerNavigationReadout.Refused(failure.reason, failure.detail)
    } catch (failure: CClangSourceGraphFailure) {
        CCompilerNavigationReadout.Refused(CCompilerNavigationRefusal.INVALID_AST, failure.reason.name)
    }

    private fun header(asts: Map<Path, JsonObject>, sources: Map<Path, String>, file: Path, offset: Int,
        selected: CCompilerDeclaration, sourceSelection: Boolean = false, selectedTu: Path? = null,
        prototypeTags: CPrototypeTagNavigationFacts? = null): NavigationFamily {
        fun observation(tu: Path, path: Path, span: CFunctionByteSpan) = NavigationObservation(tu, path, span)
        val units = asts.keys
        return when (selected.kind) {
            CCompilerDeclarationKind.FUNCTION, CCompilerDeclarationKind.VARIABLE, CCompilerDeclarationKind.ENUMERATOR -> {
                val facts = when (val result = COrdinaryFamilyRenameFactsDecoder.decode(asts, sources, file, offset, units)) {
                    is COrdinaryFamilyReadout.Found -> result.facts
                    is COrdinaryFamilyReadout.Refused -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, result.reason.name)
                }
                fun observations(filter: (COrdinaryObservation) -> Boolean) = facts.observations.filter(filter)
                    .map { observation(it.translationUnit, it.site.file, it.site.span) }
                NavigationFamily(observations { it.declaration?.definition == true },
                    observations { it.site.kind == COrdinarySiteKind.DECLARATION }, observations { it.site.kind == COrdinarySiteKind.USE })
            }
            CCompilerDeclarationKind.STRUCT, CCompilerDeclarationKind.UNION, CCompilerDeclarationKind.ENUM -> {
                val facts = when (val result = CRecordNavigationFactsDecoder.decode(asts, sources, file, offset,
                    selectedTu ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE), prototypeTags)) {
                    is CRecordNavigationReadout.Found -> result.facts
                    is CRecordNavigationReadout.Refused -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, result.reason.name)
                }
                fun sites(values: List<CRecordNavigationSite>) = values.map { observation(it.translationUnit, it.file, it.span) }
                NavigationFamily(sites(facts.definitions), sites(facts.declarations), sites(facts.references))
            }
            CCompilerDeclarationKind.TYPEDEF -> {
                when (val scoped = CScopedTypedefNavigationFactsDecoder.decode(asts, sources, file, offset,
                    selectedTu ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE))) {
                    is CScopedTypedefNavigationReadout.Found -> {
                        fun sites(values: List<CRecordNavigationSite>) = values.map { observation(it.translationUnit, it.file, it.span) }
                        return NavigationFamily(sites(scoped.facts.definitions), sites(scoped.facts.declarations), sites(scoped.facts.references))
                    }
                    is CScopedTypedefNavigationReadout.Refused -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, scoped.reason.name)
                    CScopedTypedefNavigationReadout.NotScopedFamily -> Unit
                }
                val decoded = if (sourceSelection) CTypedefRenameFactsDecoder.decodeSource(asts, sources, file, offset, units)
                    else CTypedefRenameFactsDecoder.decode(asts, sources, file, offset, units)
                val facts = when (val result = decoded) {
                    is CTypedefReadout.Found -> result.facts
                    is CTypedefReadout.Refused -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, result.reason.name)
                }
                val declarations = facts.observations.filter { it.site.kind == CTypedefSiteKind.DECLARATION }
                    .map { observation(it.translationUnit, it.site.file, it.site.span) }
                NavigationFamily(declarations, declarations, facts.observations.filter { it.site.kind == CTypedefSiteKind.TYPE_USE }
                    .map { observation(it.translationUnit, it.site.file, it.site.span) })
            }
            CCompilerDeclarationKind.FIELD -> {
                val firstTu = selectedTu ?: asts.keys.first()
                val entries = CClangSourceGraph.read(asts.getValue(firstTu), firstTu, sources)
                val field = entries.singleOrNull { it.location?.let { p -> p.file == file && offset in p.start until p.end } == true && kind(it) == "FieldDecl" }
                    ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                val record = entries.singleOrNull { it.path == field.parent } ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                if (string(record.node, "tagUsed") == "union") return unionField(asts, sources, file, offset, sourceSelection)
                val decoded = if (sourceSelection) CHeaderFieldRenameFactsDecoder.decodeSource(asts, sources, file, offset, units)
                    else CHeaderFieldRenameFactsDecoder.decode(asts, sources, file, offset, units)
                val facts = when (val result = decoded) {
                    is CHeaderFieldReadout.Found -> result.facts
                    is CHeaderFieldReadout.Refused -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, result.reason.name)
                }
                val declarations = facts.observations.filter { it.site.kind == CHeaderFieldSiteKind.DECLARATION }
                    .map { observation(it.translationUnit, it.site.file, it.site.span) }
                NavigationFamily(declarations, declarations, facts.observations.filter { it.site.kind != CHeaderFieldSiteKind.DECLARATION }
                    .map { observation(it.translationUnit, it.site.file, it.site.span) })
            }
            else -> reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
        }
    }

    private fun local(entries: List<CClangSourceEntry>, byId: Map<String, CClangSourceEntry>, tu: Path,
        sources: Map<Path, String>, selected: CClangSourceEntry, symbol: CCompilerDeclaration,
        index: CCompilerDeclarationIndex): NavigationFamily {
        if (symbol.kind !in setOf(CCompilerDeclarationKind.FUNCTION, CCompilerDeclarationKind.VARIABLE,
                CCompilerDeclarationKind.PARAMETER, CCompilerDeclarationKind.ENUMERATOR, CCompilerDeclarationKind.LABEL)) {
            reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
        }
        if (symbol.linkage == CCompilerLinkage.EXTERNAL && index.declarations.any { candidate ->
                candidate.translationUnit != symbol.translationUnit && candidate.name == symbol.name && candidate.kind == symbol.kind &&
                    candidate.linkage == CCompilerLinkage.EXTERNAL && candidate.file != symbol.file
            }) reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY, "Unconnected external declaration contexts")
        val rootId = if (symbol.kind == CCompilerDeclarationKind.LABEL) string(selected.node, "declId")
            else string(canonical(selected, byId).node, "id")
        if (rootId == null) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
        val family = if (symbol.kind == CCompilerDeclarationKind.LABEL) listOf(selected) else entries.filter {
            kind(it) == kind(selected) && string(it.node, "name") == symbol.name && string(canonical(it, byId).node, "id") == rootId
        }
        val ids = family.map { string(it.node, if (symbol.kind == CCompilerDeclarationKind.LABEL) "declId" else "id") }.toSet()
        fun site(point: CClangSourceCoordinate?): NavigationObservation = owned(point, symbol.name, tu, sources)
        val declared = family.map { site(if (symbol.kind == CCompilerDeclarationKind.LABEL) it.begin else it.location) }
        val defined = family.filter { entry -> when (symbol.kind) {
            CCompilerDeclarationKind.FUNCTION -> entries.any { it.parent == entry.path && kind(it) == "CompoundStmt" }
            CCompilerDeclarationKind.VARIABLE -> string(entry.node, "storageClass") != "extern" || entry.node.containsKey("init")
            else -> true
        } }.map { site(if (symbol.kind == CCompilerDeclarationKind.LABEL) it.begin else it.location) }
        val uses = mutableListOf<NavigationObservation>()
        for (entry in entries) {
            if (kind(entry) == "DeclRefExpr") {
                val ref = entry.node["referencedDecl"] as? JsonObject ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                val target = byId[string(ref, "id")] ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                if (string(ref, "kind") != kind(target) || string(ref, "name") != string(target.node, "name") || type(ref) != type(target.node))
                    reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                if (string(ref, "id") in ids) {
                    if (entry.begin != entry.end) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    uses.add(site(entry.begin))
                }
            }
            if (symbol.kind == CCompilerDeclarationKind.LABEL && kind(entry) == "GotoStmt") {
                val target = string(entry.node, "targetLabelDeclId") ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                if (target == rootId) uses.add(site(entry.end))
            }
            if (symbol.kind == CCompilerDeclarationKind.LABEL && kind(entry) in setOf("IndirectGotoStmt", "AddrLabelExpr"))
                reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
        }
        return NavigationFamily(defined, declared, uses)
    }

    private fun unionField(asts: Map<Path, JsonObject>, sources: Map<Path, String>, file: Path, offset: Int,
        sourceSelection: Boolean = false): NavigationFamily {
        val declared = mutableListOf<NavigationObservation>(); val uses = mutableListOf<NavigationObservation>()
        var shape: List<String>? = null
        for ((tu, ast) in asts) {
            val entries = CClangSourceGraph.read(ast, tu, sources); val indexed = declarations(entries)
            val selected = entries.singleOrNull { kind(it) == "FieldDecl" && it.location?.let { p -> p.file == file && offset in p.start until p.end } == true }
            if (selected == null) {
                if (sourceSelection && entries.none { entry -> listOfNotNull(entry.location, entry.begin, entry.end).any { it.file == file } }) continue
                reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            }
            val record = entries.singleOrNull { it.path == selected.parent && kind(it) == "RecordDecl" }
                ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            if (string(record.node, "tagUsed") != "union" || record.node["completeDefinition"] != JsonPrimitive(true))
                reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
            val name = string(selected.node, "name") ?: reject(CCompilerNavigationRefusal.INVALID_AST)
            val id = string(selected.node, "id") ?: reject(CCompilerNavigationRefusal.INVALID_AST)
            val members = entries.filter { it.parent == record.path && kind(it) == "FieldDecl" }
            if (members.size > 256) reject(CCompilerNavigationRefusal.INPUT_LIMIT)
            val description = listOf(string(record.node, "name").orEmpty(), selected.location.toString()) + members.flatMap { listOf(string(it.node, "name").orEmpty(), type(it.node)) }
            if (shape != null && shape != description) reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
            shape = description
            declared.add(owned(selected.location, name, tu, sources))
            for (entry in entries) {
                if (kind(entry) == "MemberExpr") {
                    val target = indexed[string(entry.node, "referencedMemberDecl")] ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (kind(target) != "FieldDecl" || string(target.node, "name") != string(entry.node, "name")) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (string(target.node, "id") == id) uses.add(owned(entry.end, name, tu, sources))
                }
                if (kind(entry) == "InitListExpr" && entry.node.containsKey("field")) {
                    val ref = entry.node["field"] as? JsonObject ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    val target = indexed[string(ref, "id")] ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (kind(target) != "FieldDecl" || string(ref, "name") != string(target.node, "name") || type(ref) != type(target.node))
                        reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (string(ref, "id") != id) continue
                    val begin = entry.begin ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    val end = entry.end ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (begin.file != end.file) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    val raw = sources.getValue(begin.file).substring(begin.start, end.end)
                    val starts = buildList { add(0); raw.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
                    val tokens = CTokenizer().tokenize(raw)
                    if (tokens.firstOrNull()?.text != "{" || tokens.lastOrNull()?.text != "}") reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                    if (tokens.getOrNull(1)?.text == ".") {
                        if (tokens.getOrNull(2)?.text != name || tokens.getOrNull(3)?.text != "=") reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
                        val children = entries.filter { it.parent == entry.path }
                        val value = tokens.subList(4, tokens.lastIndex).let { if (it.lastOrNull()?.text == ",") it.dropLast(1) else it }
                        if (children.size != 1 || value.isEmpty()) reject(CCompilerNavigationRefusal.UNSUPPORTED_FAMILY)
                        fun start(token: CToken) = begin.start + starts[token.line - 1] + token.column
                        if (children.single().begin?.file != begin.file || children.single().end?.file != begin.file ||
                            children.single().begin?.start != start(value.first()) || children.single().end?.end != start(value.last()) + value.last().text.length)
                            reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
                        val token = tokens[2]
                        uses.add(owned(CClangSourceCoordinate(begin.file, start(token), start(token) + name.length), name, tu, sources))
                    }
                }
            }
        }
        return NavigationFamily(declared, declared, uses)
    }

    private fun declarations(entries: List<CClangSourceEntry>): Map<String, CClangSourceEntry> {
        val indexed = linkedMapOf<String, CClangSourceEntry>()
        entries.filter { kind(it)?.endsWith("Decl") == true }.forEach { entry -> string(entry.node, "id")?.let { id ->
            if (indexed.putIfAbsent(id, entry) != null) reject(CCompilerNavigationRefusal.INVALID_AST)
        } }
        return indexed
    }

    private fun canonical(entry: CClangSourceEntry, indexed: Map<String, CClangSourceEntry>): CClangSourceEntry {
        var current = entry; val seen = mutableSetOf<String>()
        while (true) {
            val id = string(current.node, "id") ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            if (!seen.add(id)) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            if (seen.size > 256) reject(CCompilerNavigationRefusal.INPUT_LIMIT)
            val previous = string(current.node, "previousDecl") ?: return current
            val ancestor = indexed[previous] ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            if (kind(ancestor) != kind(entry) || string(ancestor.node, "name") != string(entry.node, "name") || type(ancestor.node) != type(entry.node))
                reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
            current = ancestor
        }
    }

    private fun owned(point: CClangSourceCoordinate?, name: String, tu: Path, sources: Map<Path, String>): NavigationObservation {
        val p = point ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
        if (sources.getValue(p.file).substring(p.start, p.end) != name) reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
        return NavigationObservation(tu, p.file, CFunctionByteSpan(p.start, p.end))
    }
    private fun string(node: JsonObject, key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun kind(entry: CClangSourceEntry): String? = string(entry.node, "kind")
    private fun type(node: JsonObject): String = (node["type"] as? JsonObject)?.let { string(it, "desugaredQualType") ?: string(it, "qualType") }
        ?: reject(CCompilerNavigationRefusal.UNBOUND_REFERENCE)
    private fun reject(reason: CCompilerNavigationRefusal, detail: String = ""): Nothing = throw NavigationFailure(reason, detail)
}

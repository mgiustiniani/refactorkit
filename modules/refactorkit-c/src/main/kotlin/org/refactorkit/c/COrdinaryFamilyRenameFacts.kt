package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Collections
import java.security.MessageDigest

internal enum class COrdinaryFamilyKind { FUNCTION, GLOBAL_OBJECT, ENUMERATOR }
internal enum class COrdinarySiteKind { DECLARATION, USE }
internal data class COrdinarySite(val file: Path, val span: CFunctionByteSpan, val kind: COrdinarySiteKind)
internal data class COrdinaryDeclaration(val type: String, val storage: String, val parentKind: String,
    val parentName: String, val definition: Boolean)
internal data class COrdinaryObservation(val translationUnit: Path, val astPath: String,
    val site: COrdinarySite, val declaration: COrdinaryDeclaration? = null)
internal class COrdinaryFamilyRenameFacts(val name: String, val kind: COrdinaryFamilyKind,
    observations: List<COrdinaryObservation>, translationUnits: Set<Path>, sourceHashes: Map<Path, String>) {
    val observations: List<COrdinaryObservation> = Collections.unmodifiableList(ArrayList(observations))
    val translationUnits: Set<Path> = Collections.unmodifiableSet(LinkedHashSet(translationUnits))
    val sourceHashes: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sourceHashes))
    val sites: List<COrdinarySite> = Collections.unmodifiableList(observations.map { it.site }.distinct()
        .sortedWith(compareBy({ it.file.toString() }, { it.span.start }, { it.span.endExclusive })))
}
internal enum class COrdinaryFamilyRefusal {
    INPUT_LIMIT, INVALID_AST, INCOMPLETE_TUS, SELECTION_UNAVAILABLE, MACRO_OR_UNOWNED_RANGE,
    UNBOUND_REFERENCE, UNSUPPORTED_SOURCE, INCONSISTENT_DECLARATION, DEFINITION_UNAVAILABLE,
}
internal sealed interface COrdinaryFamilyReadout {
    data class Found(val facts: COrdinaryFamilyRenameFacts) : COrdinaryFamilyReadout
    data class Refused(val reason: COrdinaryFamilyRefusal) : COrdinaryFamilyReadout
}
internal enum class COrdinaryTransitionRefusal { DECLARATION_IDENTITY, REFERENCE_FAMILY, REFERENCE_RANGES, SOURCE_IDENTITY }
internal sealed interface COrdinaryFamilyTransition {
    data object Verified : COrdinaryFamilyTransition
    data class Refused(val reason: COrdinaryTransitionRefusal) : COrdinaryFamilyTransition
}
internal object COrdinaryFamilyRenameFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path,
        selectedOffset: Int, expectedTranslationUnits: Set<Path>): COrdinaryFamilyReadout = try {
        if (expectedTranslationUnits.isEmpty() || asts.keys != expectedTranslationUnits ||
            sources.keys.filter { it.toString().endsWith(".c") }.toSet() != expectedTranslationUnits) {
            reject(COrdinaryFamilyRefusal.INCOMPLETE_TUS)
        }
        if (asts.size > 16) reject(COrdinaryFamilyRefusal.INPUT_LIMIT)
        when (val admission = CHeaderFieldSourcePolicy.check(sources)) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> reject(COrdinaryFamilyRefusal.valueOf(admission.reason.name))
        }
        if (!selectedFile.toString().endsWith(".h")) reject(COrdinaryFamilyRefusal.SELECTION_UNAVAILABLE)
        val observations = mutableListOf<COrdinaryObservation>()
        var identity: Triple<String, COrdinaryFamilyKind, Pair<COrdinarySite, COrdinaryDeclaration>>? = null
        asts.entries.sortedBy { it.key.toString() }.forEach { (tu, ast) ->
            val entries = CClangSourceGraph.read(ast, tu, sources)
            if (entries.any { kind(it.node) in setOf("GCCAsmStmt", "MSAsmStmt", "FileScopeAsmDecl", "AsmLabelAttr",
                    "AliasAttr", "IFuncAttr") }) reject(COrdinaryFamilyRefusal.UNSUPPORTED_SOURCE)
            val byId = linkedMapOf<String, CClangSourceEntry>()
            entries.forEach { entry -> string(entry.node, "id")?.let {
                if (byId.put(it, entry) != null) reject(COrdinaryFamilyRefusal.INVALID_AST)
            } }
            val byPath = entries.associateBy { it.path }
            val selected = entries.filter { kind(it.node) in DECLARATION_KINDS && it.location?.let { c ->
                c.file == selectedFile && selectedOffset >= c.start && selectedOffset < c.end
            } == true }.singleOrNull() ?: reject(COrdinaryFamilyRefusal.SELECTION_UNAVAILABLE)
            val name = identifier(selected.node)
            val familyKind = when (kind(selected.node)) {
                "FunctionDecl" -> COrdinaryFamilyKind.FUNCTION
                "VarDecl" -> COrdinaryFamilyKind.GLOBAL_OBJECT
                "EnumConstantDecl" -> COrdinaryFamilyKind.ENUMERATOR
                else -> reject(COrdinaryFamilyRefusal.SELECTION_UNAVAILABLE)
            }
            fun canonical(entry: CClangSourceEntry): String {
                var current = entry
                val seen = mutableSetOf<String>()
                while (true) {
                    val id = string(current.node, "id") ?: reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                    if (!seen.add(id)) reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                    val previous = string(current.node, "previousDecl") ?: return id
                    val parent = byId[previous] ?: reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                    if (kind(parent.node) != kind(entry.node) || string(parent.node, "name") != name ||
                        type(parent.node) != type(entry.node)) reject(COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION)
                    current = parent
                }
            }
            val rootId = canonical(selected)
            val root = byId.getValue(rootId)
            if (root.location != selected.location || root.parent != selected.parent) {
                reject(COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION)
            }
            fun declaration(entry: CClangSourceEntry): COrdinaryDeclaration {
                val parent = byPath[entry.parent] ?: reject(COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION)
                val parentKind = kind(parent.node) ?: reject(COrdinaryFamilyRefusal.INVALID_AST)
                val parentName = string(parent.node, "name").orEmpty()
                if (familyKind == COrdinaryFamilyKind.ENUMERATOR) {
                    if (parentKind != "EnumDecl" || parent.parent != "TU") reject(COrdinaryFamilyRefusal.UNSUPPORTED_SOURCE)
                } else if (parentKind != "TranslationUnitDecl") reject(COrdinaryFamilyRefusal.UNSUPPORTED_SOURCE)
                val storage = string(entry.node, "storageClass").orEmpty()
                if (storage !in setOf("", "extern")) reject(COrdinaryFamilyRefusal.UNSUPPORTED_SOURCE)
                val definition = when (familyKind) {
                    COrdinaryFamilyKind.FUNCTION -> entries.any { it.parent == entry.path && kind(it.node) == "CompoundStmt" }
                    COrdinaryFamilyKind.GLOBAL_OBJECT -> storage != "extern" || entry.node.containsKey("init")
                    COrdinaryFamilyKind.ENUMERATOR -> true
                }
                return COrdinaryDeclaration(type(entry.node), storage, parentKind, parentName, definition)
            }
            val rootSite = site(root.location, name, sources, COrdinarySiteKind.DECLARATION)
            val rootDescription = declaration(root)
            val found = Triple(name, familyKind, rootSite to rootDescription)
            if (identity != null && identity != found) reject(COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION)
            identity = found
            val family = entries.filter { kind(it.node) == kind(selected.node) && string(it.node, "name") == name }
                .filter { canonical(it) == rootId }
            val familyIds = family.map { string(it.node, "id")!! }.toSet()
            family.forEach { entry ->
                val description = declaration(entry)
                if (description.type != rootDescription.type) reject(COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION)
                observations += COrdinaryObservation(tu, entry.path,
                    site(entry.location, name, sources, COrdinarySiteKind.DECLARATION), description)
            }
            entries.filter { kind(it.node) == "DeclRefExpr" }.forEach { entry ->
                val reference = entry.node["referencedDecl"] as? JsonObject ?: reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                val id = string(reference, "id") ?: reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                val target = byId[id] ?: reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                if (kind(reference) != kind(target.node) || string(reference, "name") != string(target.node, "name") ||
                    type(reference) != type(target.node)) reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                if (id in familyIds) {
                    if (entry.begin != entry.end) reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
                    observations += COrdinaryObservation(tu, entry.path, site(entry.begin, name, sources, COrdinarySiteKind.USE))
                }
            }
        }
        val found = identity ?: reject(COrdinaryFamilyRefusal.SELECTION_UNAVAILABLE)
        if (observations.size > 8192) reject(COrdinaryFamilyRefusal.INPUT_LIMIT)
        val definitions = observations.filter { it.declaration?.definition == true }.map { it.site }.distinct()
        if (definitions.size != 1) reject(COrdinaryFamilyRefusal.DEFINITION_UNAVAILABLE)
        val sites = observations.map { it.site }.distinct().groupBy { it.file }
        if (sites.values.any { list -> list.sortedBy { it.span.start }.zipWithNext().any { (a, b) ->
                a.span.endExclusive > b.span.start
            } }) reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
        val hashes = sources.mapValues { (_, text) -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) } }
        COrdinaryFamilyReadout.Found(COrdinaryFamilyRenameFacts(found.first, found.second, observations, expectedTranslationUnits, hashes))
    } catch (failure: Rejected) { COrdinaryFamilyReadout.Refused(failure.reason) }
      catch (failure: CClangSourceGraphFailure) { COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.valueOf(failure.reason.name)) }
      catch (_: Exception) { COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.INVALID_AST) }

    private val DECLARATION_KINDS = setOf("FunctionDecl", "VarDecl", "EnumConstantDecl")
    private fun site(c: CClangSourceCoordinate?, name: String, sources: Map<Path, String>, kind: COrdinarySiteKind): COrdinarySite {
        val point = c ?: reject(COrdinaryFamilyRefusal.MACRO_OR_UNOWNED_RANGE)
        if (sources.getValue(point.file).substring(point.start, point.end) != name) reject(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
        return COrdinarySite(point.file, CFunctionByteSpan(point.start, point.end), kind)
    }
    private fun string(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun kind(node: JsonObject) = string(node, "kind")
    private fun identifier(node: JsonObject) = string(node, "name")?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) }
        ?: reject(COrdinaryFamilyRefusal.UNSUPPORTED_SOURCE)
    private fun type(node: JsonObject): String {
        val value = node["type"] as? JsonObject ?: reject(COrdinaryFamilyRefusal.INVALID_AST)
        return (string(value, "desugaredQualType") ?: string(value, "qualType"))?.takeIf { it.length in 1..4096 }
            ?: reject(COrdinaryFamilyRefusal.INVALID_AST)
    }
    private fun reject(reason: COrdinaryFamilyRefusal): Nothing = throw Rejected(reason)
    private class Rejected(val reason: COrdinaryFamilyRefusal) : RuntimeException()
}
internal object COrdinaryFamilyRenameBinding {
    fun check(before: COrdinaryFamilyRenameFacts, after: COrdinaryFamilyRenameFacts,
        replacement: String): COrdinaryFamilyTransition {
        fun no(reason: COrdinaryTransitionRefusal) = COrdinaryFamilyTransition.Refused(reason)
        if (replacement == before.name || !replacement.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) ||
            after.name != replacement || before.kind != after.kind ||
            before.observations.mapNotNull { it.declaration } != after.observations.mapNotNull { it.declaration }) {
            return no(COrdinaryTransitionRefusal.DECLARATION_IDENTITY)
        }
        if (before.translationUnits != after.translationUnits || before.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) } !=
            after.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) }) return no(COrdinaryTransitionRefusal.REFERENCE_FAMILY)
        val sites = before.sites.groupBy { it.file }
        if (before.sourceHashes.keys != after.sourceHashes.keys || before.sourceHashes.any { (file, hash) ->
                (hash == after.sourceHashes[file]) == sites.containsKey(file)
            }) return no(COrdinaryTransitionRefusal.SOURCE_IDENTITY)
        val delta = replacement.length - before.name.length
        val expected = before.observations.map { observation ->
            val site = observation.site
            val start = site.span.start + delta * sites.getValue(site.file).count { it.span.endExclusive <= site.span.start }
            observation.copy(site = site.copy(span = CFunctionByteSpan(start, start + replacement.length)))
        }
        if (expected != after.observations) return no(COrdinaryTransitionRefusal.REFERENCE_RANGES)
        return COrdinaryFamilyTransition.Verified
    }
}

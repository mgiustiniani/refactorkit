package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Collections
import java.security.MessageDigest

internal enum class CTagKind(val keyword: String) { STRUCT("struct"), UNION("union"), ENUM("enum") }
internal enum class CTagSiteKind { DECLARATION, TYPE_USE }
internal data class CTagSite(val file: Path, val span: CFunctionByteSpan, val kind: CTagSiteKind)
internal data class CTagObservation(val translationUnit: Path, val witnessPath: String, val site: CTagSite)
internal data class CTagTypeObservation(val translationUnit: Path, val astPath: String, val property: String,
    val qualified: String, val desugared: String?)
internal data class CTagShapeNode(val path: String, val kind: String, val name: String?,
    val qualified: String?, val desugared: String?, val value: String?)
internal class CTagRenameFacts(val name: String, val kind: CTagKind, observations: List<CTagObservation>,
    typeObservations: List<CTagTypeObservation>, declarationShape: List<CTagShapeNode>, otherTagNames: Set<String>,
    translationUnits: Set<Path>, sourceHashes: Map<Path, String>) {
    val observations: List<CTagObservation> = Collections.unmodifiableList(ArrayList(observations))
    val typeObservations: List<CTagTypeObservation> = Collections.unmodifiableList(ArrayList(typeObservations))
    val declarationShape: List<CTagShapeNode> = Collections.unmodifiableList(ArrayList(declarationShape))
    val otherTagNames: Set<String> = Collections.unmodifiableSet(LinkedHashSet(otherTagNames))
    val translationUnits: Set<Path> = Collections.unmodifiableSet(LinkedHashSet(translationUnits))
    val sourceHashes: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sourceHashes))
    val sites: List<CTagSite> = Collections.unmodifiableList(observations.map { it.site }.distinct()
        .sortedWith(compareBy({ it.file.toString() }, { it.span.start }, { it.span.endExclusive })))
}
internal enum class CTagFactsRefusal { INPUT_LIMIT, INVALID_AST, INCOMPLETE_TUS, SELECTION_UNAVAILABLE,
    MACRO_OR_UNOWNED_RANGE, UNSUPPORTED_SOURCE, AMBIGUOUS_TAG_FAMILY, UNBOUND_TYPE_USE, INCONSISTENT_DECLARATION,
    SOURCE_TYPE_EXPOSURE_UNQUALIFIED }
internal sealed interface CTagReadout {
    data class Found(val facts: CTagRenameFacts) : CTagReadout
    data class Refused(val reason: CTagFactsRefusal) : CTagReadout
}
internal enum class CTagTransitionRefusal { DECLARATION_IDENTITY, REFERENCE_FAMILY, REFERENCE_RANGES, SOURCE_IDENTITY }
internal sealed interface CTagTransition {
    data object Verified : CTagTransition
    data class Refused(val reason: CTagTransitionRefusal) : CTagTransition
}
internal object CTagRenameFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>): CTagReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, sourceSelection = false)

    fun decodeSource(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>): CTagReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, sourceSelection = true)

    private fun decodeProfile(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>, sourceSelection: Boolean): CTagReadout = try {
        if (expectedTranslationUnits.isEmpty() || asts.keys != expectedTranslationUnits ||
            sources.keys.filter { it.toString().endsWith(".c") }.toSet() != expectedTranslationUnits) reject(CTagFactsRefusal.INCOMPLETE_TUS)
        if (asts.size > 16) reject(CTagFactsRefusal.INPUT_LIMIT)
        when (val source = CHeaderFieldSourcePolicy.check(sources)) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> reject(CTagFactsRefusal.valueOf(source.reason.name))
        }
        if ((if (sourceSelection) selectedFile !in expectedTranslationUnits else !selectedFile.toString().endsWith(".h")) || sources.keys.any {
                !it.toString().endsWith(".h") && !it.toString().endsWith(".c")
            }) reject(CTagFactsRefusal.SELECTION_UNAVAILABLE)
        val observations = mutableListOf<CTagObservation>()
        val types = mutableListOf<CTagTypeObservation>()
        val otherTags = linkedSetOf<String>()
        var selectedName: String? = null
        var selectedKind: CTagKind? = null
        var selectedDeclaration: CTagSite? = null
        var selectedShape: List<CTagShapeNode>? = null
        var candidates: List<Candidate>? = null
        val relevantFiles = linkedSetOf<Path>()
        asts.entries.sortedBy { it.key.toString() }.forEach unit@ { (tu, ast) ->
            val entries = CClangSourceGraph.read(ast, tu, sources)
            if (entries.any { kind(it.node) in setOf("GCCAsmStmt", "MSAsmStmt", "FileScopeAsmDecl", "AsmLabelAttr",
                    "AliasAttr", "IFuncAttr", "CXXRecordDecl", "ObjCInterfaceDecl", "ObjCMethodDecl") }) {
                reject(CTagFactsRefusal.UNSUPPORTED_SOURCE)
            }
            val byId = linkedMapOf<String, CClangSourceEntry>()
            entries.forEach { entry -> string(entry.node, "id")?.let {
                if (byId.put(it, entry) != null) reject(CTagFactsRefusal.INVALID_AST)
            } }
            val allTags = entries.filter { kind(it.node) in TAG_DECLARATIONS && string(it.node, "name") != null }
            val selected = allTags.filter { entry -> entry.location?.let {
                it.file == selectedFile && selectedOffset >= it.start && selectedOffset < it.end
            } == true }.singleOrNull() ?: run {
                if (sourceSelection && entries.none { entry -> listOfNotNull(entry.location, entry.begin, entry.end).any { it.file == selectedFile } })
                    return@unit
                reject(CTagFactsRefusal.SELECTION_UNAVAILABLE)
            }
            val name = identifier(selected.node)
            val tagKind = tagKind(selected.node)
            fun canonical(entry: CClangSourceEntry): String {
                var current = entry
                val seen = mutableSetOf<String>()
                while (true) {
                    val id = string(current.node, "id") ?: reject(CTagFactsRefusal.INVALID_AST)
                    if (!seen.add(id)) reject(CTagFactsRefusal.INVALID_AST)
                    if (string(current.node, "name") != name || tagKind(current.node) != tagKind || current.parent != "TU") {
                        reject(CTagFactsRefusal.AMBIGUOUS_TAG_FAMILY)
                    }
                    val previous = string(current.node, "previousDecl") ?: return id
                    current = byId[previous] ?: reject(CTagFactsRefusal.INVALID_AST)
                }
            }
            val rootId = canonical(selected)
            val root = byId.getValue(rootId)
            if (root.location != selected.location) reject(CTagFactsRefusal.SELECTION_UNAVAILABLE)
            val family = allTags.filter { string(it.node, "name") == name }
            if (family.any { canonical(it) != rootId }) reject(CTagFactsRefusal.AMBIGUOUS_TAG_FAMILY)
            val complete = if (tagKind == CTagKind.ENUM) entries.any {
                it.parent == root.path && kind(it.node) == "EnumConstantDecl"
            } else root.node["completeDefinition"]?.jsonPrimitive?.content == "true"
            if (!complete || root.node.containsKey("fixedUnderlyingType")) reject(CTagFactsRefusal.UNSUPPORTED_SOURCE)
            val declaration = site(root.location, name, sources, CTagSiteKind.DECLARATION)
            fun normalized(text: String): String = normalizeType(text, tagKind, name)
            val shape = entries.filter { it.path == root.path || it.path.startsWith(root.path + "/") }.map { entry ->
                val type = entry.node["type"] as? JsonObject
                CTagShapeNode(entry.path.removePrefix(root.path), kind(entry.node) ?: reject(CTagFactsRefusal.INVALID_AST),
                    if (entry.path == root.path) null else string(entry.node, "name"),
                    type?.let { normalized(requireType(it)) }, type?.let { string(it, "desugaredQualType") }?.let(::normalized),
                    (entry.node["value"] as? JsonPrimitive)?.content)
            }
            if (shape.size > 8192) reject(CTagFactsRefusal.INPUT_LIMIT)
            if (selectedName != null && (selectedName != name || selectedKind != tagKind ||
                    selectedDeclaration != declaration || selectedShape != shape)) reject(CTagFactsRefusal.INCONSISTENT_DECLARATION)
            selectedName = name; selectedKind = tagKind; selectedDeclaration = declaration; selectedShape = shape
            otherTags += allTags.map { identifier(it.node) }.filter { it != name }
            val physical = candidates ?: sourceCandidates(sources, tagKind, name).also { candidates = it }
            val familyLocations = family.map { it.location ?: reject(CTagFactsRefusal.MACRO_OR_UNOWNED_RANGE) }.toSet()
            val anchors = mutableListOf<CClangSourceEntry>()
            entries.forEach { entry ->
                var contains = false
                for (property in listOf("type", "argType")) {
                    val raw = entry.node[property] ?: continue
                    val type = raw as? JsonObject ?: reject(CTagFactsRefusal.INVALID_AST)
                    val qualified = requireType(type)
                    val desugared = string(type, "desugaredQualType")
                    if (hasTag(qualified, tagKind, name) || desugared?.let { hasTag(it, tagKind, name) } == true) {
                        contains = true
                        types += CTagTypeObservation(tu, entry.path, property, normalized(qualified), desugared?.let(::normalized))
                        if (types.size > 8192) reject(CTagFactsRefusal.INPUT_LIMIT)
                    }
                }
                if (contains) anchors += entry
            }
            val seenFiles = entries.flatMap { listOfNotNull(it.location, it.begin, it.end).map { point -> point.file } }.toSet()
            if (sourceSelection) relevantFiles += seenFiles
            physical.filter { it.file in seenFiles }.forEach { candidate ->
                val coordinate = CClangSourceCoordinate(candidate.file, candidate.span.start, candidate.span.endExclusive)
                val declarationEntry = family.firstOrNull { it.location == coordinate }
                if (coordinate in familyLocations) {
                    observations += CTagObservation(tu, requireNotNull(declarationEntry).path,
                        CTagSite(candidate.file, candidate.span, CTagSiteKind.DECLARATION))
                } else {
                    val witness = anchors.filter { entry ->
                        val begin = entry.begin; val end = entry.end
                        begin != null && end != null && begin.file == candidate.file && end.file == candidate.file &&
                            begin.start <= candidate.span.start && end.end >= candidate.span.endExclusive
                    }.minWithOrNull(compareBy<CClangSourceEntry>({ it.end!!.end - it.begin!!.start }, { it.path }))
                        ?: reject(CTagFactsRefusal.UNBOUND_TYPE_USE)
                    observations += CTagObservation(tu, witness.path, CTagSite(candidate.file, candidate.span, CTagSiteKind.TYPE_USE))
                }
                if (observations.size > 8192) reject(CTagFactsRefusal.INPUT_LIMIT)
            }
        }
        val allCandidates = candidates ?: reject(CTagFactsRefusal.SELECTION_UNAVAILABLE)
        val expected = if (sourceSelection) allCandidates.filter { it.file in relevantFiles } else allCandidates
        if (expected.map { it.file to it.span }.toSet() != observations.map { it.site.file to it.site.span }.toSet()) {
            reject(CTagFactsRefusal.UNBOUND_TYPE_USE)
        }
        val hashes = sources.mapValues { (_, text) -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) } }
        CTagReadout.Found(CTagRenameFacts(requireNotNull(selectedName), requireNotNull(selectedKind), observations, types,
            requireNotNull(selectedShape), otherTags, expectedTranslationUnits, hashes))
    } catch (failure: Rejected) { CTagReadout.Refused(failure.reason) }
      catch (failure: CClangSourceGraphFailure) { CTagReadout.Refused(CTagFactsRefusal.valueOf(failure.reason.name)) }
      catch (_: Exception) { CTagReadout.Refused(CTagFactsRefusal.INVALID_AST) }

    private val TAG_DECLARATIONS = setOf("RecordDecl", "EnumDecl")
    private data class Candidate(val file: Path, val span: CFunctionByteSpan)
    private fun sourceCandidates(sources: Map<Path, String>, kind: CTagKind, name: String): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        sources.entries.sortedBy { it.key.toString() }.forEach { (file, text) ->
            val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            val tokens = CTokenizer().tokenize(text)
            tokens.zipWithNext().forEach { (keyword, identifier) ->
                if (keyword.text == kind.keyword && identifier.type == CTokenType.IDENTIFIER && identifier.text == name) {
                    val start = starts[identifier.line - 1] + identifier.column
                    candidates += Candidate(file, CFunctionByteSpan(start, start + name.length))
                    if (candidates.size > 8192) reject(CTagFactsRefusal.INPUT_LIMIT)
                }
            }
        }
        return candidates
    }
    private fun typeTokens(text: String): List<CToken> {
        if (text.length !in 1..4096) reject(CTagFactsRefusal.INPUT_LIMIT)
        return CTokenizer().tokenize(text)
    }
    private fun hasTag(text: String, kind: CTagKind, name: String) = typeTokens(text).zipWithNext().any {
        (keyword, identifier) -> keyword.text == kind.keyword && identifier.text == name
    }
    private fun normalizeType(text: String, kind: CTagKind, name: String): String {
        val tokens = typeTokens(text)
        return tokens.mapIndexed { index, token ->
            if (index > 0 && tokens[index - 1].text == kind.keyword && token.text == name) "__refactorkit_selected_tag__" else token.text
        }.joinToString("\u0000")
    }
    private fun site(point: CClangSourceCoordinate?, name: String, sources: Map<Path, String>, kind: CTagSiteKind): CTagSite {
        val p = point ?: reject(CTagFactsRefusal.MACRO_OR_UNOWNED_RANGE)
        if (sources.getValue(p.file).substring(p.start, p.end) != name) reject(CTagFactsRefusal.UNBOUND_TYPE_USE)
        return CTagSite(p.file, CFunctionByteSpan(p.start, p.end), kind)
    }
    private fun requireType(node: JsonObject) = string(node, "qualType") ?: reject(CTagFactsRefusal.INVALID_AST)
    private fun string(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun kind(node: JsonObject) = string(node, "kind")
    private fun identifier(node: JsonObject) = string(node, "name")?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) }
        ?: reject(CTagFactsRefusal.UNSUPPORTED_SOURCE)
    private fun tagKind(node: JsonObject): CTagKind = when {
        kind(node) == "EnumDecl" -> CTagKind.ENUM
        kind(node) == "RecordDecl" -> CTagKind.entries.singleOrNull { it.keyword == string(node, "tagUsed") }
            ?: reject(CTagFactsRefusal.UNSUPPORTED_SOURCE)
        else -> reject(CTagFactsRefusal.INVALID_AST)
    }
    private fun reject(reason: CTagFactsRefusal): Nothing = throw Rejected(reason)
    private class Rejected(val reason: CTagFactsRefusal) : RuntimeException()
}
internal object CTagRenameBinding {
    fun check(before: CTagRenameFacts, after: CTagRenameFacts, replacement: String): CTagTransition {
        fun no(reason: CTagTransitionRefusal) = CTagTransition.Refused(reason)
        if (replacement == before.name || !replacement.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) ||
            after.name != replacement || before.kind != after.kind || before.declarationShape != after.declarationShape ||
            before.otherTagNames != after.otherTagNames || replacement in before.otherTagNames) return no(CTagTransitionRefusal.DECLARATION_IDENTITY)
        if (before.translationUnits != after.translationUnits || before.typeObservations != after.typeObservations ||
            before.observations.map { Triple(it.translationUnit, it.witnessPath, it.site.kind) } !=
            after.observations.map { Triple(it.translationUnit, it.witnessPath, it.site.kind) }) return no(CTagTransitionRefusal.REFERENCE_FAMILY)
        val sites = before.sites.groupBy { it.file }
        if (before.sourceHashes.keys != after.sourceHashes.keys || before.sourceHashes.any { (file, hash) ->
                (hash == after.sourceHashes[file]) == sites.containsKey(file)
            }) return no(CTagTransitionRefusal.SOURCE_IDENTITY)
        val delta = replacement.length - before.name.length
        val expected = before.observations.map { observation ->
            val old = observation.site
            val start = old.span.start + delta * sites.getValue(old.file).count { it.span.endExclusive <= old.span.start }
            observation.copy(site = old.copy(span = CFunctionByteSpan(start, start + replacement.length)))
        }
        if (expected != after.observations) return no(CTagTransitionRefusal.REFERENCE_RANGES)
        return CTagTransition.Verified
    }
}

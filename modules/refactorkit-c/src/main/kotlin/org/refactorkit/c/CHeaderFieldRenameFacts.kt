package org.refactorkit.c

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.nio.file.Path
import java.util.Collections

internal enum class CHeaderFieldSiteKind { DECLARATION, MEMBER, DESIGNATOR }
internal data class CHeaderFieldSite(val file: Path, val span: CFunctionByteSpan, val kind: CHeaderFieldSiteKind)
internal data class CHeaderFieldObservation(val translationUnit: Path, val astPath: String, val site: CHeaderFieldSite)
internal data class CHeaderRecordField(val name: String, val type: String)
internal class CHeaderFieldRenameFacts(
    val name: String, val recordName: String, val recordKind: String, val fieldIndex: Int,
    fields: List<CHeaderRecordField>, observations: List<CHeaderFieldObservation>,
    translationUnits: Set<Path>, sourceHashes: Map<Path, String>,
) {
    val fields: List<CHeaderRecordField> = Collections.unmodifiableList(ArrayList(fields))
    val observations: List<CHeaderFieldObservation> = Collections.unmodifiableList(ArrayList(observations))
    val translationUnits: Set<Path> = Collections.unmodifiableSet(LinkedHashSet(translationUnits))
    val sourceHashes: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sourceHashes))
    val sites: List<CHeaderFieldSite> = Collections.unmodifiableList(observations.map { it.site }.distinct()
        .sortedWith(compareBy({ it.file.toString() }, { it.span.start }, { it.span.endExclusive })))
}
internal enum class CHeaderFieldRefusal {
    INPUT_LIMIT, INVALID_AST, INCOMPLETE_TUS, SELECTION_UNAVAILABLE, MACRO_OR_UNOWNED_RANGE,
    UNBOUND_REFERENCE, DESIGNATOR_BINDING, UNSUPPORTED_SOURCE, INCONSISTENT_DECLARATION,
}
internal sealed interface CHeaderFieldReadout {
    data class Found(val facts: CHeaderFieldRenameFacts) : CHeaderFieldReadout
    data class Refused(val reason: CHeaderFieldRefusal) : CHeaderFieldReadout
}
internal enum class CHeaderFieldTransitionRefusal { DECLARATION_IDENTITY, REFERENCE_FAMILY, REFERENCE_RANGES, SOURCE_IDENTITY }
internal sealed interface CHeaderFieldTransition {
    data object Verified : CHeaderFieldTransition
    data class Refused(val reason: CHeaderFieldTransitionRefusal) : CHeaderFieldTransition
}
internal object CHeaderFieldRenameBinding {
    fun check(before: CHeaderFieldRenameFacts, after: CHeaderFieldRenameFacts, replacement: String): CHeaderFieldTransition {
        fun no(reason: CHeaderFieldTransitionRefusal) = CHeaderFieldTransition.Refused(reason)
        if (replacement == before.name || !replacement.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) ||
            after.name != replacement || before.recordName != after.recordName || before.recordKind != after.recordKind ||
            before.fieldIndex != after.fieldIndex || before.fields.mapIndexed { index, field ->
                if (index == before.fieldIndex) field.copy(name = replacement) else field
            } != after.fields) return no(CHeaderFieldTransitionRefusal.DECLARATION_IDENTITY)
        if (before.translationUnits != after.translationUnits ||
            before.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) } !=
            after.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) }) {
            return no(CHeaderFieldTransitionRefusal.REFERENCE_FAMILY)
        }
        if (before.sourceHashes.keys != after.sourceHashes.keys) return no(CHeaderFieldTransitionRefusal.SOURCE_IDENTITY)
        val byFile = before.sites.groupBy { it.file }
        if (before.sourceHashes.any { (file, hash) -> (hash == after.sourceHashes[file]) == byFile.containsKey(file) }) {
            return no(CHeaderFieldTransitionRefusal.SOURCE_IDENTITY)
        }
        val delta = replacement.length - before.name.length
        fun shifted(site: CHeaderFieldSite): CHeaderFieldSite {
            val spans = byFile.getValue(site.file).map { it.span }
            val start = site.span.start + delta * spans.count { it.endExclusive <= site.span.start }
            return site.copy(span = CFunctionByteSpan(start, start + replacement.length))
        }
        if (before.observations.map { it.copy(site = shifted(it.site)) } != after.observations) {
            return no(CHeaderFieldTransitionRefusal.REFERENCE_RANGES)
        }
        return CHeaderFieldTransition.Verified
    }
}

internal sealed interface CHeaderFieldSourceAdmission {
    data object Admitted : CHeaderFieldSourceAdmission
    data class Refused(val reason: CHeaderFieldRefusal) : CHeaderFieldSourceAdmission
}
/** Shared pure pre-execution guard; native acquisition delegates this rule. */
internal object CHeaderFieldSourcePolicy {
    fun check(sources: Map<Path, String>): CHeaderFieldSourceAdmission = check(sources) { false }

    fun check(sources: Map<Path, String>, additionalHeaderDirective: (String) -> Boolean): CHeaderFieldSourceAdmission {
        if (sources.size > 32 || sources.values.sumOf { it.length.toLong() } > 8_388_608L ||
            sources.values.any { it.length > 1_048_576 }) return CHeaderFieldSourceAdmission.Refused(CHeaderFieldRefusal.INPUT_LIMIT)
        if (sources.keys.any { it != it.normalize() } || sources.any { (path, text) ->
            fun admitted(line: String) = qualifiedDirective(line) ||
                (path.fileName.toString().endsWith(".h") && additionalHeaderDirective(line))
            text.any { it.code > 127 || it == '\\' } || "??" in text || "%:" in text || "_Pragma" in text ||
                text.lineSequence().any { line -> line.trimStart().startsWith('#') &&
                    !admitted(line) } || unqualifiedTokens(text, ::admitted)
        }) return CHeaderFieldSourceAdmission.Refused(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
        return CHeaderFieldSourceAdmission.Admitted
    }
    private fun qualifiedDirective(line: String) = line.matches(Regex("\\s*#\\s*include\\s+\"[A-Za-z_0-9./-]+\"\\s*")) ||
        line.matches(Regex("\\s*#\\s*pragma\\s+once\\s*"))

    private val ISO_C_WORDS = setOf("_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary",
        "_Noreturn", "_Static_assert", "_Thread_local", "__func__")

    private fun unqualifiedTokens(text: String, directiveAdmitted: (String) -> Boolean): Boolean {
        // Preprocessing replaces comments with whitespace before recognizing directives.
        // Reject forms the literal include-ownership reader does not itself acquire.
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val observedLines = hashSetOf<Int>()
        return CTokenizer().tokenize(normalized).any { token ->
            val reservedExtension = token.type == CTokenType.IDENTIFIER && token.text.length > 1 &&
                token.text[0] == '_' && (token.text[1] == '_' || token.text[1].isUpperCase()) && token.text !in ISO_C_WORDS
            if (reservedExtension) true
            else if (token.type == CTokenType.STRING && (token.text.length < 2 || token.text.last() != token.text.first())) true
            else observedLines.add(token.line) && token.text == "#" && !directiveAdmitted(lines[token.line - 1])
        }
    }
}

/** Pure source-owned binding closure, including syntax discarded from semantic InitListExpr. */
internal object CHeaderFieldRenameFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path,
        selectedOffset: Int, expectedTranslationUnits: Set<Path>, readOnlySources: Map<Path, String> = emptyMap()): CHeaderFieldReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, sourceSelection = false, readOnlySources)

    fun decodeSource(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path,
        selectedOffset: Int, expectedTranslationUnits: Set<Path>): CHeaderFieldReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, sourceSelection = true)

    private fun decodeProfile(asts: Map<Path, JsonObject>, writableSources: Map<Path, String>, selectedFile: Path,
        selectedOffset: Int, expectedTranslationUnits: Set<Path>, sourceSelection: Boolean,
        readOnlySources: Map<Path, String> = emptyMap()): CHeaderFieldReadout = try {
        if (readOnlySources.keys.any { it in writableSources || !it.isAbsolute || it != it.normalize() ||
                !it.toString().endsWith(".h") }) reject(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
        if (selectedFile in readOnlySources) reject(CHeaderFieldRefusal.SELECTION_UNAVAILABLE)
        val sources = writableSources + readOnlySources
        if (expectedTranslationUnits.isEmpty() || asts.keys != expectedTranslationUnits ||
            sources.keys.filter { it.toString().endsWith(".c") }.toSet() != expectedTranslationUnits) {
            reject(CHeaderFieldRefusal.INCOMPLETE_TUS)
        }
        if (asts.size > 16) reject(CHeaderFieldRefusal.INPUT_LIMIT)
        if (sourceSelection && selectedFile !in expectedTranslationUnits) reject(CHeaderFieldRefusal.SELECTION_UNAVAILABLE)
        when (val admission = CHeaderFieldSourcePolicy.check(sources)) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> reject(admission.reason)
        }
        val observations = mutableListOf<CHeaderFieldObservation>()
        var signature: Signature? = null
        asts.entries.sortedBy { it.key.toString() }.forEach unit@ { (tu, ast) ->
            val entries = try { CClangSourceGraph.read(ast, tu, sources) }
            catch (failure: CClangSourceGraphFailure) { reject(CHeaderFieldRefusal.valueOf(failure.reason.name)) }
            if (entries.any { kind(it.node) in setOf("GCCAsmStmt", "MSAsmStmt", "FileScopeAsmDecl", "AsmLabelAttr",
                    "AliasAttr", "IFuncAttr", "OffsetOfExpr", "IndirectFieldDecl", "DesignatedInitUpdateExpr") }) {
                reject(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
            }
            val indexed = linkedMapOf<String, CClangSourceEntry>()
            // Native type nodes can be shared; declaration IDs remain unique binding targets.
            entries.filter { kind(it.node)?.endsWith("Decl") == true }.forEach { entry -> string(entry.node, "id")?.let {
                if (indexed.put(it, entry) != null) reject(CHeaderFieldRefusal.INVALID_AST)
            } }
            val fields = entries.filter { kind(it.node) == "FieldDecl" }
            val selected = fields.filter { entry -> entry.location?.let {
                it.file == selectedFile && selectedOffset >= it.start && selectedOffset < it.end
            } == true }.singleOrNull() ?: run {
                if (sourceSelection && entries.none { entry -> listOfNotNull(entry.location, entry.begin, entry.end).any { it.file == selectedFile } })
                    return@unit
                reject(CHeaderFieldRefusal.SELECTION_UNAVAILABLE)
            }
            val name = identifier(selected.node, "name")
            val record = entries.singleOrNull { it.path == selected.parent && kind(it.node) == "RecordDecl" }
                ?: reject(CHeaderFieldRefusal.INCONSISTENT_DECLARATION)
            val recordName = identifier(record.node, "name")
            if (string(record.node, "tagUsed") != "struct" || record.node["completeDefinition"]?.jsonPrimitive?.content != "true") {
                reject(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
            }
            val records = entries.filter { kind(it.node) == "RecordDecl" && string(it.node, "name") == recordName &&
                it.node["completeDefinition"]?.jsonPrimitive?.content == "true" }
            if (records.size != 1) reject(CHeaderFieldRefusal.INCONSISTENT_DECLARATION)
            val members = fields.filter { it.parent == record.path }
            val descriptions = members.map { CHeaderRecordField(identifier(it.node, "name"), type(it.node)) }
            if (descriptions.map { it.name }.distinct().size != descriptions.size || members.size > 256) {
                reject(CHeaderFieldRefusal.INCONSISTENT_DECLARATION)
            }
            val declaration = site(selected.location, name, sources, CHeaderFieldSiteKind.DECLARATION)
            val observed = Signature(name, recordName, "struct", members.indexOf(selected), descriptions, declaration)
            if (signature != null && signature != observed) reject(CHeaderFieldRefusal.INCONSISTENT_DECLARATION)
            signature = observed
            observations += CHeaderFieldObservation(tu, selected.path, declaration)
            val selectedId = string(selected.node, "id") ?: reject(CHeaderFieldRefusal.INVALID_AST)
            entries.filter { kind(it.node) == "MemberExpr" }.forEach { member ->
                val id = string(member.node, "referencedMemberDecl") ?: reject(CHeaderFieldRefusal.UNBOUND_REFERENCE)
                val field = indexed[id]?.takeIf { kind(it.node) == "FieldDecl" }
                    ?: reject(CHeaderFieldRefusal.UNBOUND_REFERENCE)
                if (string(member.node, "name") != string(field.node, "name")) reject(CHeaderFieldRefusal.UNBOUND_REFERENCE)
                if (id == selectedId) observations += CHeaderFieldObservation(tu, member.path,
                    site(member.end, name, sources, CHeaderFieldSiteKind.MEMBER))
            }
            entries.filter { kind(it.node) == "InitListExpr" && type(it.node) == "struct $recordName" }.forEach { init ->
                observations += designators(tu, init, entries.filter { it.parent == init.path }, observed, sources)
            }
        }
        val found = signature ?: reject(CHeaderFieldRefusal.SELECTION_UNAVAILABLE)
        if (observations.size > 8192) reject(CHeaderFieldRefusal.INPUT_LIMIT)
        val sites = observations.map { it.site }.distinct().groupBy { it.file }
        if (sites.values.any { group -> group.sortedBy { it.span.start }.zipWithNext().any { (a, b) ->
                a.span.endExclusive > b.span.start
            } }) reject(CHeaderFieldRefusal.UNBOUND_REFERENCE)
        if (observations.any { it.site.file !in writableSources }) reject(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
        val hashes = writableSources.mapValues { (_, text) -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) } }
        CHeaderFieldReadout.Found(CHeaderFieldRenameFacts(found.name, found.recordName, found.recordKind,
            found.index, found.fields, observations, expectedTranslationUnits, hashes))
    } catch (failure: Refusal) { CHeaderFieldReadout.Refused(failure.reason) }
      catch (_: Exception) { CHeaderFieldReadout.Refused(CHeaderFieldRefusal.INVALID_AST) }

    private data class Signature(val name: String, val recordName: String, val recordKind: String,
        val index: Int, val fields: List<CHeaderRecordField>, val declaration: CHeaderFieldSite)
    private data class Token(val text: String, val start: Int, val end: Int)
    private fun designators(tu: Path, init: CClangSourceEntry, children: List<CClangSourceEntry>, signature: Signature,
        sources: Map<Path, String>): List<CHeaderFieldObservation> {
        val begin = init.begin ?: reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        val end = init.end ?: reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        if (begin.file != end.file || begin.start >= end.end || children.size != signature.fields.size) {
            reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        }
        val raw = sources.getValue(begin.file).substring(begin.start, end.end)
        val lineStarts = buildList { add(0); raw.forEachIndexed { index, c -> if (c == '\n') add(index + 1) } }
        val tokens = CTokenizer().tokenize(raw).map {
            val start = begin.start + lineStarts[it.line - 1] + it.column
            Token(it.text, start, start + it.text.length)
        }
        if (tokens.size < 2 || tokens.first().text != "{" || tokens.last().text != "}") reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        val segments = mutableListOf<List<Token>>()
        var start = 1
        val stack = ArrayDeque<String>()
        for (i in 1 until tokens.lastIndex) {
            val token = tokens[i].text
            when (token) {
                "(", "[", "{" -> stack.addLast(token)
                ")", "]", "}" -> {
                    val expected = mapOf(")" to "(", "]" to "[", "}" to "{").getValue(token)
                    if (stack.removeLastOrNull() != expected) reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
                }
                "," -> if (stack.isEmpty()) { segments += tokens.subList(start, i); start = i + 1 }
            }
        }
        if (stack.isNotEmpty()) reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        if (start < tokens.lastIndex) segments += tokens.subList(start, tokens.lastIndex)
        val seen = mutableSetOf<Int>()
        val observed = mutableListOf<CHeaderFieldObservation>()
        var next = 0
        segments.forEachIndexed { segmentIndex, segment ->
            if (segment.isEmpty()) reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
            val designated = segment.first().text == "."
            val index = if (designated) {
                if (segment.size < 4 || segment[2].text != "=") reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
                signature.fields.indexOfFirst { it.name == segment[1].text }
            } else next
            if (index !in signature.fields.indices || !seen.add(index)) reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
            next = index + 1
            val value = if (designated) segment.drop(3) else segment
            val child = children[index]
            if (value.isEmpty() || child.begin?.file != begin.file || child.end?.file != begin.file ||
                child.begin?.start != value.first().start || child.end?.end != value.last().end) {
                reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
            }
            if (designated && index == signature.index) {
                val token = segment[1]
                observed += CHeaderFieldObservation(tu, init.path + "/designator-$segmentIndex",
                    CHeaderFieldSite(begin.file, CFunctionByteSpan(token.start, token.end), CHeaderFieldSiteKind.DESIGNATOR))
            }
        }
        if (children.indices.any { it !in seen && (children[it].begin != null || children[it].end != null) }) {
            reject(CHeaderFieldRefusal.DESIGNATOR_BINDING)
        }
        return observed
    }
    private fun site(coordinate: CClangSourceCoordinate?, name: String, sources: Map<Path, String>, kind: CHeaderFieldSiteKind): CHeaderFieldSite {
        val c = coordinate ?: reject(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
        if (sources.getValue(c.file).substring(c.start, c.end) != name) reject(CHeaderFieldRefusal.UNBOUND_REFERENCE)
        return CHeaderFieldSite(c.file, CFunctionByteSpan(c.start, c.end), kind)
    }
    private fun kind(node: JsonObject) = string(node, "kind")
    private fun type(node: JsonObject): String {
        val type = node["type"] as? JsonObject ?: reject(CHeaderFieldRefusal.INVALID_AST)
        return (string(type, "desugaredQualType") ?: string(type, "qualType"))?.takeIf { it.length in 1..4096 }
            ?: reject(CHeaderFieldRefusal.INVALID_AST)
    }
    private fun identifier(node: JsonObject, key: String) = string(node, key)?.takeIf {
        it.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}"))
    } ?: reject(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
    private fun number(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun string(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun reject(reason: CHeaderFieldRefusal): Nothing = throw Refusal(reason)
    private class Refusal(val reason: CHeaderFieldRefusal) : RuntimeException()
}

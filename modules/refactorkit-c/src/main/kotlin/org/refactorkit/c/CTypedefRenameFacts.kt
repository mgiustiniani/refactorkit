package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Collections
import java.security.MessageDigest

internal enum class CTypedefSiteKind { DECLARATION, TYPE_USE }
internal data class CTypedefSite(val file: Path, val span: CFunctionByteSpan, val kind: CTypedefSiteKind)
internal data class CTypedefObservation(val translationUnit: Path, val astPath: String, val site: CTypedefSite)
internal enum class CTypedefOtherSpace { VALUE_DECLARATION, VALUE_REFERENCE, MEMBER_DECLARATION, MEMBER_REFERENCE,
    TAG_DECLARATION, TAG_USE, LABEL_DECLARATION, LABEL_REFERENCE }
internal data class CTypedefOtherObservation(val translationUnit: Path, val astPath: String, val targetPath: String,
    val space: CTypedefOtherSpace, val file: Path, val span: CFunctionByteSpan)
internal data class CTypedefTypeObservation(val translationUnit: Path, val astPath: String, val property: String,
    val qualified: String, val desugared: String?)
internal class CTypedefRenameFacts(val name: String, val underlyingQualified: String, val underlyingCanonical: String,
    observations: List<CTypedefObservation>, typeObservations: List<CTypedefTypeObservation>,
    otherObservations: List<CTypedefOtherObservation>, otherOrdinaryNames: Set<String>,
    translationUnits: Set<Path>, sourceHashes: Map<Path, String>) {
    val observations: List<CTypedefObservation> = Collections.unmodifiableList(ArrayList(observations))
    val typeObservations: List<CTypedefTypeObservation> = Collections.unmodifiableList(ArrayList(typeObservations))
    val otherObservations: List<CTypedefOtherObservation> = Collections.unmodifiableList(ArrayList(otherObservations))
    val otherOrdinaryNames: Set<String> = Collections.unmodifiableSet(LinkedHashSet(otherOrdinaryNames))
    val translationUnits: Set<Path> = Collections.unmodifiableSet(LinkedHashSet(translationUnits))
    val sourceHashes: Map<Path, String> = Collections.unmodifiableMap(LinkedHashMap(sourceHashes))
    val sites: List<CTypedefSite> = Collections.unmodifiableList(observations.map { it.site }.distinct()
        .sortedWith(compareBy({ it.file.toString() }, { it.span.start }, { it.span.endExclusive })))
}
internal enum class CTypedefFactsRefusal { INPUT_LIMIT, INVALID_AST, INCOMPLETE_TUS, SELECTION_UNAVAILABLE,
    MACRO_OR_UNOWNED_RANGE, UNSUPPORTED_SOURCE, AMBIGUOUS_TYPE_ALIAS, UNBOUND_REFERENCE,
    UNBOUND_TYPE_REFERENCE, UNBOUND_SOURCE_TOKEN, UNSUPPORTED_TYPE_SHAPE, INCONSISTENT_DECLARATION }
internal sealed interface CTypedefReadout {
    data class Found(val facts: CTypedefRenameFacts) : CTypedefReadout
    data class Refused(val reason: CTypedefFactsRefusal) : CTypedefReadout
}
internal enum class CTypedefTransitionRefusal { DECLARATION_IDENTITY, REFERENCE_FAMILY, REFERENCE_RANGES, SOURCE_IDENTITY }
internal sealed interface CTypedefTransition {
    data object Verified : CTypedefTransition
    data class Refused(val reason: CTypedefTransitionRefusal) : CTypedefTransition
}
internal object CTypedefRenameFactsDecoder {
    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>, preservedSpelling: String? = null): CTypedefReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, preservedSpelling, sourceSelection = false)

    fun decodeSource(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>, preservedSpelling: String? = null): CTypedefReadout =
        decodeProfile(asts, sources, selectedFile, selectedOffset, expectedTranslationUnits, preservedSpelling, sourceSelection = true)

    private fun decodeProfile(asts: Map<Path, JsonObject>, sources: Map<Path, String>, selectedFile: Path, selectedOffset: Int,
        expectedTranslationUnits: Set<Path>, preservedSpelling: String?, sourceSelection: Boolean): CTypedefReadout = try {
        if (expectedTranslationUnits.isEmpty() || asts.keys != expectedTranslationUnits ||
            sources.keys.filter { it.toString().endsWith(".c") }.toSet() != expectedTranslationUnits) reject(CTypedefFactsRefusal.INCOMPLETE_TUS)
        if (asts.size > 16) reject(CTypedefFactsRefusal.INPUT_LIMIT)
        when (val admission = CHeaderFieldSourcePolicy.check(sources)) {
            CHeaderFieldSourceAdmission.Admitted -> Unit
            is CHeaderFieldSourceAdmission.Refused -> reject(CTypedefFactsRefusal.valueOf(admission.reason.name))
        }
        if (if (sourceSelection) selectedFile !in expectedTranslationUnits else !selectedFile.toString().endsWith(".h"))
            reject(CTypedefFactsRefusal.SELECTION_UNAVAILABLE)
        val sourceTokens = sources.mapValues { (_, text) ->
            val starts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            CTokenizer().tokenize(text).map { token ->
                val start = starts[token.line - 1] + token.column
                Token(token.text, start, start + token.text.length, token.type)
            }
        }
        val observations = mutableListOf<CTypedefObservation>()
        val types = mutableListOf<CTypedefTypeObservation>()
        val others = mutableListOf<CTypedefOtherObservation>()
        val ordinary = linkedSetOf<String>()
        var identity: Identity? = null
        var candidates: Set<Point>? = null
        val relevantFiles = linkedSetOf<Path>()
        asts.entries.sortedBy { it.key.toString() }.forEach unit@ { (tu, ast) ->
            val entries = CClangSourceGraph.read(ast, tu, sources)
            if (entries.any { kind(it.node) in setOf("GCCAsmStmt", "MSAsmStmt", "FileScopeAsmDecl", "AsmLabelAttr", "AliasAttr",
                    "IFuncAttr", "CXXRecordDecl", "ObjCInterfaceDecl", "ObjCMethodDecl") }) reject(CTypedefFactsRefusal.UNSUPPORTED_SOURCE)
            // Clang reuses type-node IDs in multiple subtrees; only declaration IDs are indexed uniquely.
            val declarations = linkedMapOf<String, CClangSourceEntry>()
            entries.filter { kind(it.node)?.endsWith("Decl") == true }.forEach { entry -> string(entry.node, "id")?.let {
                if (declarations.put(it, entry) != null) reject(CTypedefFactsRefusal.INVALID_AST)
            } }
            val aliases = entries.filter { kind(it.node) == "TypedefDecl" }
            val selected = aliases.filter { entry -> entry.location?.let {
                it.file == selectedFile && selectedOffset >= it.start && selectedOffset < it.end
            } == true }.singleOrNull() ?: run {
                if (sourceSelection && entries.none { entry -> listOfNotNull(entry.location, entry.begin, entry.end).any { it.file == selectedFile } })
                    return@unit
                reject(CTypedefFactsRefusal.SELECTION_UNAVAILABLE)
            }
            val name = identifier(selected.node)
            val retainedSpelling = preservedSpelling ?: name
            if (!retainedSpelling.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}"))) reject(CTypedefFactsRefusal.UNSUPPORTED_SOURCE)
            // Preserve all captured source tag names in both images, including a future alias destination.
            val otherNames = declarations.values.filter { kind(it.node) in setOf("RecordDecl", "EnumDecl") && it.location != null }
                .map { identifier(it.node) }.toSet() + retainedSpelling
            fun canonical(entry: CClangSourceEntry): String {
                var current = entry
                val seen = mutableSetOf<String>()
                while (true) {
                    val id = string(current.node, "id") ?: reject(CTypedefFactsRefusal.INVALID_AST)
                    if (!seen.add(id)) reject(CTypedefFactsRefusal.INVALID_AST)
                    if (kind(current.node) != "TypedefDecl" || string(current.node, "name") != name || current.parent != "TU") {
                        reject(CTypedefFactsRefusal.AMBIGUOUS_TYPE_ALIAS)
                    }
                    val previous = string(current.node, "previousDecl") ?: return id
                    current = declarations[previous] ?: reject(CTypedefFactsRefusal.UNBOUND_TYPE_REFERENCE)
                }
            }
            val rootId = canonical(selected)
            val root = declarations.getValue(rootId)
            if (root.location != selected.location) reject(CTypedefFactsRefusal.SELECTION_UNAVAILABLE)
            val family = aliases.filter { string(it.node, "name") == name }
            if (family.any { canonical(it) != rootId }) reject(CTypedefFactsRefusal.AMBIGUOUS_TYPE_ALIAS)
            val familyIds = family.map { string(it.node, "id")!! }.toSet()
            val rootType = root.node["type"] as? JsonObject ?: reject(CTypedefFactsRefusal.INVALID_AST)
            val underlying = qualified(rootType)
            val found = Identity(name, normalize(underlying, name), normalize(string(rootType, "desugaredQualType") ?: underlying, name),
                point(root.location, name, sources))
            if (identity != null && identity != found) reject(CTypedefFactsRefusal.INCONSISTENT_DECLARATION)
            identity = found
            val physical = candidates ?: sourceTokens.flatMap { (file, tokens) ->
                tokens.filter { it.kind == CTokenType.IDENTIFIER && (it.text == name || it.text in otherNames) }.map { Point(file, CFunctionByteSpan(it.start, it.end)) }
            }.toSet().also { candidates = it }
            if (physical.size > 8192) reject(CTypedefFactsRefusal.INPUT_LIMIT)
            ordinary += declarations.values.filter { kind(it.node) in ORDINARY_DECLARATIONS && string(it.node, "id") !in familyIds }
                .mapNotNull { string(it.node, "name") }
            val renamed = linkedMapOf<Point, CTypedefObservation>()
            val nonTypes = linkedMapOf<Point, CTypedefOtherObservation>()
            fun rename(p: Point, path: String, role: CTypedefSiteKind) {
                if (p !in physical || p in nonTypes) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                val value = CTypedefObservation(tu, path, CTypedefSite(p.file, p.span, role))
                val old = renamed.putIfAbsent(p, value)
                if (old != null && old.site.kind != role) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
            }
            fun preserve(p: Point, entry: CClangSourceEntry, target: CClangSourceEntry, space: CTypedefOtherSpace) {
                if (p !in physical || p in renamed) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                val value = CTypedefOtherObservation(tu, entry.path, target.path, space, p.file, p.span)
                val old = nonTypes.putIfAbsent(p, value)
                if (old != null && old != value) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
            }
            family.forEach { rename(point(it.location, name, sources), it.path, CTypedefSiteKind.DECLARATION) }
            declarations.values.filter { string(it.node, "name") in otherNames && string(it.node, "id") !in familyIds }.forEach { entry ->
                val role = when (kind(entry.node)) {
                    "RecordDecl", "EnumDecl" -> CTypedefOtherSpace.TAG_DECLARATION
                    "FieldDecl" -> CTypedefOtherSpace.MEMBER_DECLARATION
                    in VALUE_DECLARATIONS -> CTypedefOtherSpace.VALUE_DECLARATION
                    else -> reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                }
                preserve(point(entry.location, identifier(entry.node), sources), entry, entry, role)
            }
            val labels = linkedMapOf<String, CClangSourceEntry>()
            entries.filter { kind(it.node) == "LabelStmt" }.forEach { entry ->
                val id = string(entry.node, "declId") ?: reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                if (labels.put(id, entry) != null) reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                if (string(entry.node, "name") in otherNames) preserve(point(entry.begin, identifier(entry.node), sources), entry, entry, CTypedefOtherSpace.LABEL_DECLARATION)
            }
            entries.forEach { entry -> when (kind(entry.node)) {
                "DeclRefExpr" -> {
                    val ref = entry.node["referencedDecl"] as? JsonObject
                    if (ref != null && string(ref, "name") in otherNames) {
                        val target = declarations[string(ref, "id")] ?: reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                        if (kind(target.node) !in VALUE_DECLARATIONS || string(target.node, "name") != string(ref, "name") || entry.begin != entry.end) {
                            reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                        }
                        preserve(point(entry.begin, identifier(target.node), sources), entry, target, CTypedefOtherSpace.VALUE_REFERENCE)
                    }
                }
                "MemberExpr" -> if (string(entry.node, "name") in otherNames) {
                    val target = declarations[string(entry.node, "referencedMemberDecl")] ?: reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                    if (kind(target.node) != "FieldDecl" || string(target.node, "name") != string(entry.node, "name")) reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                    preserve(point(entry.end, identifier(target.node), sources), entry, target, CTypedefOtherSpace.MEMBER_REFERENCE)
                }
                "GotoStmt" -> if (entry.end?.let { text(it, sources) in otherNames } == true) {
                    val target = labels[string(entry.node, "targetLabelDeclId")] ?: reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                    if (string(target.node, "name") != entry.end?.let { text(it, sources) }) reject(CTypedefFactsRefusal.UNBOUND_REFERENCE)
                    preserve(point(entry.end, identifier(target.node), sources), entry, target, CTypedefOtherSpace.LABEL_REFERENCE)
                }
            } }
            entries.forEach { entry ->
                var selectedType = false
                var selectedArgumentType = false
                for (property in listOf("type", "argType")) {
                    val raw = entry.node[property] ?: continue
                    val type = raw as? JsonObject ?: reject(CTypedefFactsRefusal.INVALID_AST)
                    val q = qualified(type)
                    val d = string(type, "desugaredQualType")
                    val id = string(type, "typeAliasDeclId")
                    val matches = hasAlias(q, name) || d?.let { hasAlias(it, name) } == true || id in familyIds
                    if (matches) {
                        if (id != null && id !in familyIds) reject(CTypedefFactsRefusal.UNBOUND_TYPE_REFERENCE)
                        if (kind(entry.node) == "TypedefType") {
                            val reference = entry.node["decl"] as? JsonObject ?: reject(CTypedefFactsRefusal.UNBOUND_TYPE_REFERENCE)
                            if (string(reference, "name") == name && string(reference, "id") !in familyIds) reject(CTypedefFactsRefusal.UNBOUND_TYPE_REFERENCE)
                        }
                        types += CTypedefTypeObservation(tu, entry.path, property, normalize(q, name), d?.let { normalize(it, name) })
                        if (types.size > 8192) reject(CTypedefFactsRefusal.INPUT_LIMIT)
                        if (property == "argType") selectedArgumentType = true else selectedType = true
                    }
                }
                fun markType(prefix: List<Token>, file: Path) {
                    val selectedTokens = prefix.filterIndexed { index, token -> token.text == name &&
                        (index == 0 || prefix[index - 1].text !in TAG_WORDS) }
                    if (selectedTokens.isEmpty()) return
                    if (prefix.any { it.text != name && it.text !in FLAT_TYPE_WORDS }) reject(CTypedefFactsRefusal.UNSUPPORTED_TYPE_SHAPE)
                    selectedTokens.forEach { rename(Point(file, CFunctionByteSpan(it.start, it.end)), entry.path, CTypedefSiteKind.TYPE_USE) }
                }
                if (selectedType && kind(entry.node) in TYPE_DECLARATIONS) {
                    val begin = entry.begin ?: reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
                    val end = if (kind(entry.node) == "ParmVarDecl" && string(entry.node, "name") == null) entry.end?.end else entry.location?.start
                    if (end == null || entry.location?.file?.let { it != begin.file } == true) reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
                    markType(sourceTokens.getValue(begin.file).filter { it.start >= begin.start && it.end <= end }, begin.file)
                }
                if (selectedType && kind(entry.node) == "CStyleCastExpr") {
                    val begin = entry.begin ?: reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
                    val tokens = sourceTokens.getValue(begin.file).filter { it.start >= begin.start && it.end <= (entry.end?.end ?: -1) }
                    markType(parenthesized(tokens, 0), begin.file)
                }
                if (selectedArgumentType && kind(entry.node) == "UnaryExprOrTypeTraitExpr") {
                    val begin = entry.begin ?: reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
                    val tokens = sourceTokens.getValue(begin.file).filter { it.start >= begin.start && it.end <= (entry.end?.end ?: -1) }
                    if (tokens.firstOrNull()?.text !in setOf("sizeof", "_Alignof")) reject(CTypedefFactsRefusal.UNSUPPORTED_TYPE_SHAPE)
                    markType(parenthesized(tokens, 1), begin.file)
                }
            }
            val seenFiles = entries.flatMap { listOfNotNull(it.location, it.begin, it.end).map { point -> point.file } }.toSet()
            if (sourceSelection) {
                relevantFiles += seenFiles
                // Recover only native-proven designators; unknown sites remain uncovered and refuse below.
                declarations.values.filter { kind(it.node) == "FieldDecl" && string(it.node, "name") in otherNames }
                    .forEach { field ->
                        val location = field.location ?: reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
                        val memberReadout = if (location.file in expectedTranslationUnits)
                            CHeaderFieldRenameFactsDecoder.decodeSource(asts, sources, location.file, location.start, expectedTranslationUnits)
                        else CHeaderFieldRenameFactsDecoder.decode(asts, sources, location.file, location.start, expectedTranslationUnits)
                        when (memberReadout) {
                            is CHeaderFieldReadout.Refused -> Unit
                            is CHeaderFieldReadout.Found -> memberReadout.facts.observations.filter {
                                it.translationUnit == tu && it.site.kind == CHeaderFieldSiteKind.DESIGNATOR
                            }.forEach { witness ->
                                val p = Point(witness.site.file, witness.site.span)
                                if (p in physical) {
                                    if (p in renamed) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                                    val value = CTypedefOtherObservation(tu, witness.astPath, field.path,
                                        CTypedefOtherSpace.MEMBER_REFERENCE, p.file, p.span)
                                    val previous = nonTypes.putIfAbsent(p, value)
                                    if (previous != null && previous != value) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                                }
                            }
                        }
                    }
            }
            physical.filter { it.file in seenFiles && it !in renamed && it !in nonTypes }.forEach { p ->
                val tokens = sourceTokens.getValue(p.file)
                val index = tokens.indexOfFirst { it.start == p.span.start }
                val spelling = tokens.getOrNull(index)?.text ?: reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                if (spelling !in otherNames) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                val keyword = tokens.getOrNull(index - 1)?.text
                if (keyword !in TAG_WORDS) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                val witness = entries.filter { entry ->
                    val begin = entry.begin; val end = entry.end
                    begin != null && end != null && begin.file == p.file && end.file == p.file && begin.start <= p.span.start && end.end >= p.span.endExclusive &&
                        listOf("type", "argType").any { property -> (entry.node[property] as? JsonObject)?.let { type ->
                            tagType(qualified(type), requireNotNull(keyword), spelling) || string(type, "desugaredQualType")?.let { tagType(it, keyword, spelling) } == true
                        } == true }
                }.minWithOrNull(compareBy<CClangSourceEntry>({ it.end!!.end - it.begin!!.start }, { it.path }))
                    ?: reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
                preserve(p, witness, witness, CTypedefOtherSpace.TAG_USE)
            }
            observations += renamed.values.sortedWith(compareBy({ it.site.file.toString() }, { it.site.span.start }))
            others += nonTypes.values.sortedWith(compareBy({ it.file.toString() }, { it.span.start }))
            if (observations.size + others.size > 16384) reject(CTypedefFactsRefusal.INPUT_LIMIT)
        }
        val allCandidates = candidates ?: reject(CTypedefFactsRefusal.SELECTION_UNAVAILABLE)
        val expected = if (sourceSelection) allCandidates.filter { it.file in relevantFiles }.toSet() else allCandidates
        val covered = observations.map { Point(it.site.file, it.site.span) } + others.map { Point(it.file, it.span) }
        if (expected != covered.toSet()) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
        val found = identity ?: reject(CTypedefFactsRefusal.SELECTION_UNAVAILABLE)
        val hashes = sources.mapValues { (_, text) -> MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) } }
        CTypedefReadout.Found(CTypedefRenameFacts(found.name, found.qualified, found.canonical, observations, types, others,
            ordinary, expectedTranslationUnits, hashes))
    } catch (failure: Rejected) { CTypedefReadout.Refused(failure.reason) }
      catch (failure: CClangSourceGraphFailure) { CTypedefReadout.Refused(CTypedefFactsRefusal.valueOf(failure.reason.name)) }
      catch (_: Exception) { CTypedefReadout.Refused(CTypedefFactsRefusal.INVALID_AST) }

    private data class Point(val file: Path, val span: CFunctionByteSpan)
    private data class Token(val text: String, val start: Int, val end: Int, val kind: CTokenType)
    private data class Identity(val name: String, val qualified: String, val canonical: String, val declaration: Point)
    private val TAG_WORDS = setOf("struct", "union", "enum")
    private val VALUE_DECLARATIONS = setOf("VarDecl", "ParmVarDecl", "FunctionDecl", "EnumConstantDecl")
    private val ORDINARY_DECLARATIONS = VALUE_DECLARATIONS + "TypedefDecl"
    private val TYPE_DECLARATIONS = setOf("VarDecl", "ParmVarDecl", "FieldDecl", "TypedefDecl", "FunctionDecl")
    private val FLAT_TYPE_WORDS = setOf("*", "const", "volatile", "restrict", "_Atomic", "typedef", "static", "extern", "auto", "register", "inline", "_Noreturn", "_Thread_local")
    private fun parenthesized(tokens: List<Token>, start: Int): List<Token> {
        if (tokens.getOrNull(start)?.text != "(") reject(CTypedefFactsRefusal.UNSUPPORTED_TYPE_SHAPE)
        var depth = 0
        for (i in start until tokens.size) {
            when (tokens[i].text) { "(" -> depth++; ")" -> { depth--; if (depth == 0) return tokens.subList(start + 1, i) } }
        }
        reject(CTypedefFactsRefusal.UNSUPPORTED_TYPE_SHAPE)
    }
    private fun typeTokens(text: String): List<CToken> {
        if (text.length !in 1..4096) reject(CTypedefFactsRefusal.INPUT_LIMIT)
        return CTokenizer().tokenize(text)
    }
    private fun aliasIndexes(tokens: List<CToken>, name: String): Set<Int> {
        val indexes = mutableSetOf<Int>(); var brackets = 0
        tokens.forEachIndexed { i, token -> when (token.text) {
            "[" -> brackets++
            "]" -> { brackets--; if (brackets < 0) reject(CTypedefFactsRefusal.INVALID_AST) }
            else -> if (brackets == 0 && token.text == name && tokens.getOrNull(i - 1)?.text !in TAG_WORDS) indexes += i
        } }
        if (brackets != 0) reject(CTypedefFactsRefusal.INVALID_AST)
        return indexes
    }
    private fun hasAlias(text: String, name: String) = aliasIndexes(typeTokens(text), name).isNotEmpty()
    private fun normalize(text: String, name: String): String {
        val tokens = typeTokens(text); val indexes = aliasIndexes(tokens, name)
        return tokens.mapIndexed { i, token -> if (i in indexes) "__refactorkit_selected_alias__" else token.text }.joinToString("\u0000")
    }
    private fun tagType(text: String, keyword: String, name: String) = typeTokens(text).zipWithNext().any { (a, b) -> a.text == keyword && b.text == name }
    private fun text(point: CClangSourceCoordinate, sources: Map<Path, String>) = sources.getValue(point.file).substring(point.start, point.end)
    private fun point(value: CClangSourceCoordinate?, name: String, sources: Map<Path, String>): Point {
        val p = value ?: reject(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
        if (text(p, sources) != name) reject(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
        return Point(p.file, CFunctionByteSpan(p.start, p.end))
    }
    private fun qualified(node: JsonObject) = string(node, "qualType") ?: reject(CTypedefFactsRefusal.INVALID_AST)
    private fun string(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun kind(node: JsonObject) = string(node, "kind")
    private fun identifier(node: JsonObject) = string(node, "name")?.takeIf { it.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) }
        ?: reject(CTypedefFactsRefusal.UNSUPPORTED_SOURCE)
    private fun reject(reason: CTypedefFactsRefusal): Nothing = throw Rejected(reason)
    private class Rejected(val reason: CTypedefFactsRefusal) : RuntimeException()
}
internal object CTypedefRenameBinding {
    fun check(before: CTypedefRenameFacts, after: CTypedefRenameFacts, replacement: String): CTypedefTransition {
        fun no(reason: CTypedefTransitionRefusal) = CTypedefTransition.Refused(reason)
        if (replacement == before.name || !replacement.matches(Regex("[A-Za-z_][A-Za-z_0-9]{0,127}")) || after.name != replacement ||
            before.underlyingQualified != after.underlyingQualified || before.underlyingCanonical != after.underlyingCanonical ||
            replacement in before.otherOrdinaryNames || before.otherOrdinaryNames != after.otherOrdinaryNames) return no(CTypedefTransitionRefusal.DECLARATION_IDENTITY)
        if (before.translationUnits != after.translationUnits || before.typeObservations != after.typeObservations ||
            before.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) } != after.observations.map { Triple(it.translationUnit, it.astPath, it.site.kind) } ||
            before.otherObservations.map { listOf(it.translationUnit, it.astPath, it.targetPath, it.space, it.file) } !=
            after.otherObservations.map { listOf(it.translationUnit, it.astPath, it.targetPath, it.space, it.file) }) return no(CTypedefTransitionRefusal.REFERENCE_FAMILY)
        val sites = before.sites.groupBy { it.file }
        if (before.sourceHashes.keys != after.sourceHashes.keys || before.sourceHashes.any { (file, hash) ->
                (hash == after.sourceHashes[file]) == sites.containsKey(file)
            }) return no(CTypedefTransitionRefusal.SOURCE_IDENTITY)
        val delta = replacement.length - before.name.length
        fun shifted(file: Path, span: CFunctionByteSpan, renamed: Boolean): CFunctionByteSpan {
            val start = span.start + delta * sites[file].orEmpty().count { it.span.endExclusive <= span.start }
            return CFunctionByteSpan(start, start + if (renamed) replacement.length else span.endExclusive - span.start)
        }
        if (before.observations.map { it.copy(site = it.site.copy(span = shifted(it.site.file, it.site.span, true))) } != after.observations ||
            before.otherObservations.map { it.copy(span = shifted(it.file, it.span, false)) } != after.otherObservations) return no(CTypedefTransitionRefusal.REFERENCE_RANGES)
        return CTypedefTransition.Verified
    }
}

package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

internal data class CPrototypeTagCursor(val translationUnit: Path, val file: Path, val range: SourceRange)
internal data class CPrototypeTagQuery(val cursor: CPrototypeTagCursor, val name: String,
    val kind: CCompilerDeclarationKind, val functionName: String, val scopeId: String)
internal enum class CPrototypeTagRefusal { INPUT_LIMIT, INCOMPLETE_CONTEXT, INVALID_AST,
    UNSUPPORTED_SOURCE, NATIVE_EVIDENCE_UNAVAILABLE, BINDING_MISMATCH }
internal sealed interface CPrototypeTagQueriesReadout {
    data class Found(val queries: List<CPrototypeTagQuery>) : CPrototypeTagQueriesReadout
    data class Refused(val reason: CPrototypeTagRefusal) : CPrototypeTagQueriesReadout
}
internal sealed interface CPrototypeTagReadout {
    data class Found(val facts: CPrototypeTagNavigationFacts) : CPrototypeTagReadout
    data class Refused(val reason: CPrototypeTagRefusal) : CPrototypeTagReadout
}

/** Snapshot-bound read facts only; these never authorize a mutation. */
internal class CPrototypeTagNavigationFacts internal constructor(
    private val nativeIdentity: String, logicalPaths: Map<Path, Path>,
    families: List<CCompilerNavigationFacts>, excluded: Set<CRecordNavigationSite>,
) {
    private val paths = Collections.unmodifiableMap(LinkedHashMap(logicalPaths))
    private val families = Collections.unmodifiableMap(families.associateBy { it.selected.id })
    private val excluded = Collections.unmodifiableSet(LinkedHashSet(excluded))
    val declarations: List<CCompilerDeclaration> = Collections.unmodifiableList(families.map { it.selected })
    fun matchesNativeInputs(asts: Map<Path, JsonObject>, sources: Map<Path, String>): Boolean =
        nativeIdentity == prototypeInputIdentity(asts, sources)
    fun matchesLogicalPaths(paths: Map<Path, Path>): Boolean = this.paths == paths
    fun family(symbolId: String): CCompilerNavigationFacts? = families[symbolId]
    fun excludes(site: CRecordNavigationSite): Boolean = site in excluded
}

private class PrototypeFailure(val reason: CPrototypeTagRefusal) : RuntimeException()
private fun prototypeInputIdentity(asts: Map<Path, JsonObject>, sources: Map<Path, String>): String {
    val hash = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        hash.update("${bytes.size}:".toByteArray(Charsets.UTF_8)); hash.update(bytes)
    }
    sources.entries.sortedBy { it.key.toString() }.forEach { (path, content) -> field(path.toString()); field(content) }
    asts.entries.sortedBy { it.key.toString() }.forEach { (path, ast) -> field(path.toString()); field(ast.toString()) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

/** Compiler-owned type spans select requests; native source/scope identities prove families. */
internal object CPrototypeTagFactsDecoder {
    fun queries(asts: Map<Path, JsonObject>, sources: Map<Path, String>,
        logicalPaths: Map<Path, Path> = sources.keys.associateWith { it }): CPrototypeTagQueriesReadout = try {
        when (val index = CCompilerDeclarationIndex.analyze(asts, sources, logicalPaths)) {
            is CCompilerDeclarationReadout.Found -> Unit
            is CCompilerDeclarationReadout.Refused -> fail(when (index.reason) {
                CCompilerDeclarationRefusal.INPUT_LIMIT -> CPrototypeTagRefusal.INPUT_LIMIT
                CCompilerDeclarationRefusal.INCOMPLETE_CONTEXT -> CPrototypeTagRefusal.INCOMPLETE_CONTEXT
                else -> CPrototypeTagRefusal.INVALID_AST
            })
        }
        val found = mutableListOf<CPrototypeTagQuery>()
        for ((tu, ast) in asts) {
            val entries = CClangSourceGraph.read(ast, tu, sources)
            val byPath = entries.associateBy { it.path }
            for (parameter in entries.filter { string(it.node, "kind") == "ParmVarDecl" }) {
                val function = byPath[parameter.parent] ?: fail(CPrototypeTagRefusal.INVALID_AST)
                if (string(function.node, "kind") != "FunctionDecl") continue
                val definition = entries.any { it.parent == function.path && string(it.node, "kind") == "CompoundStmt" }
                val functionFile = function.location?.file ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val type = parameter.node["type"] as? JsonObject ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val spelling = string(type, "qualType") ?: fail(CPrototypeTagRefusal.INVALID_AST)
                if (spelling.length > 4096) fail(CPrototypeTagRefusal.INPUT_LIMIT)
                val typePairs = CTokenizer().tokenize(spelling).zipWithNext().filter { it.first.text in setOf("struct", "union", "enum") }
                if (typePairs.isEmpty()) continue
                // Already visible global tags retain the existing compiler-backed reader.
                if (definition && typePairs.all { pair -> entries.takeWhile { it.path != function.path }.any {
                    it.parent == "TU" && string(it.node, "name") == pair.second.text &&
                        string(it.node, "kind") == if (pair.first.text == "enum") "EnumDecl" else "RecordDecl"
                } }) continue
                val begin = parameter.begin ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val end = parameter.end ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val functionBegin = function.begin ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val functionEnd = function.end ?: fail(CPrototypeTagRefusal.INVALID_AST)
                require(begin.file == functionFile && end.file == functionFile &&
                    functionBegin.file == functionFile && functionEnd.file == functionFile &&
                    functionBegin.start <= begin.start && end.end <= functionEnd.end && begin.start < end.end,
                    CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                require(end.end - begin.start <= 65536, CPrototypeTagRefusal.INPUT_LIMIT)
                val text = sources.getValue(functionFile).substring(begin.start, end.end)
                val tokens = CTokenizer().tokenize(text)
                val pairs = tokens.zipWithNext().filter { it.first.text in setOf("struct", "union", "enum") }
                require(pairs.size == 1, CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                val (keyword, tag) = pairs.single()
                require(tag.type == CTokenType.IDENTIFIER && typePairs.any { it.first.text == keyword.text && it.second.text == tag.text },
                    CPrototypeTagRefusal.BINDING_MISMATCH)
                val parameterName = string(parameter.node, "name")
                require(tokens.all { it.text in setOf(keyword.text, tag.text, "*", "const", "volatile", "restrict", parameterName) },
                    CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                val functionName = string(function.node, "name") ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val offset = begin.start + TextEdits.offsetOf(text, SourcePosition(tag.line - 1, tag.column))
                val logicalTu = logicalPaths.getValue(tu)
                val logicalFile = logicalPaths.getValue(functionFile)
                val cursor = CPrototypeTagCursor(logicalTu, logicalFile,
                    TextEdits.rangeForOffset(sources.getValue(functionFile), offset, tag.text.length))
                val kind = when (keyword.text) { "struct" -> CCompilerDeclarationKind.STRUCT; "union" -> CCompilerDeclarationKind.UNION; else -> CCompilerDeclarationKind.ENUM }
                found += CPrototypeTagQuery(cursor, tag.text, kind, functionName,
                    "${ProtocolPath.serialize(logicalTu)}#${if (definition) "function" else "prototype"}:" +
                        (if (logicalFile == logicalTu) "" else "${ProtocolPath.serialize(logicalFile)}:") +
                        "${functionBegin.start}:${functionEnd.end}")
                require(found.size <= 32, CPrototypeTagRefusal.INPUT_LIMIT)
            }
            for (function in entries.filter { string(it.node, "kind") == "FunctionDecl" &&
                entries.any { child -> child.parent == it.path && string(child.node, "kind") == "CompoundStmt" } }) {
                val functionFile = function.location?.file ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val begin = function.begin ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val end = function.end ?: fail(CPrototypeTagRefusal.INVALID_AST)
                val scope = "${ProtocolPath.serialize(logicalPaths.getValue(tu))}#function:" +
                    (if (functionFile == tu) "" else "${ProtocolPath.serialize(logicalPaths.getValue(functionFile))}:") +
                    "${begin.start}:${end.end}"
                val parameters = found.filter { it.scopeId == scope }
                if (parameters.isEmpty()) continue
                require(begin.file == functionFile && end.file == functionFile && begin.start < end.end,
                    CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                require(end.end - begin.start <= 65536, CPrototypeTagRefusal.INPUT_LIMIT)
                val source = sources.getValue(functionFile)
                val names = parameters.map { it.name }.toSet()
                val children = entries.filter { it.path.startsWith("${function.path}/") }
                for (entry in children) {
                    val kind = string(entry.node, "kind")
                    if (kind !in setOf("VarDecl", "CStyleCastExpr", "UnaryExprOrTypeTraitExpr")) continue
                    val type = entry.node[if (kind == "UnaryExprOrTypeTraitExpr") "argType" else "type"] as? JsonObject ?: continue
                    val spelling = string(type, "qualType") ?: fail(CPrototypeTagRefusal.INVALID_AST)
                    require(spelling.length <= 4096, CPrototypeTagRefusal.INPUT_LIMIT)
                    val nativePairs = CTokenizer().tokenize(spelling).zipWithNext().filter {
                        it.first.text in setOf("struct", "union", "enum") && it.second.text in names
                    }
                    if (nativePairs.isEmpty()) continue
                    val start = entry.begin ?: fail(CPrototypeTagRefusal.INVALID_AST)
                    val stop = when (kind) {
                        "VarDecl" -> entry.location ?: fail(CPrototypeTagRefusal.INVALID_AST)
                        "CStyleCastExpr" -> children.filter { it.parent == entry.path }.mapNotNull { it.begin }
                            .minByOrNull { it.start } ?: fail(CPrototypeTagRefusal.INVALID_AST)
                        else -> entry.end ?: fail(CPrototypeTagRefusal.INVALID_AST)
                    }
                    val stopOffset = if (kind == "CStyleCastExpr") stop.start else stop.end
                    require(start.file == functionFile && stop.file == functionFile &&
                        begin.start <= start.start && start.start < stopOffset && stopOffset <= end.end,
                        CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                    val text = source.substring(start.start, stopOffset)
                    val tokens = CTokenizer().tokenize(text)
                    val pairs = tokens.zipWithNext().filter { it.first.text in setOf("struct", "union", "enum") }
                    require(pairs.size == 1, CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                    val (keyword, tag) = pairs.single()
                    val parameter = parameters.firstOrNull { it.name == tag.text && when (it.kind) {
                        CCompilerDeclarationKind.STRUCT -> keyword.text == "struct"
                        CCompilerDeclarationKind.UNION -> keyword.text == "union"
                        CCompilerDeclarationKind.ENUM -> keyword.text == "enum"
                        else -> false
                    } } ?: fail(CPrototypeTagRefusal.BINDING_MISMATCH)
                    require(nativePairs.any { it.first.text == keyword.text && it.second.text == tag.text },
                        CPrototypeTagRefusal.BINDING_MISMATCH)
                    require(tokens.all { it.text in setOf(keyword.text, tag.text, "*", "const", "volatile", "restrict",
                        "(", ")", if (kind == "VarDecl") string(entry.node, "name") else "sizeof") },
                        CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
                    val offset = start.start + TextEdits.offsetOf(text, SourcePosition(tag.line - 1, tag.column))
                    found += parameter.copy(cursor = parameter.cursor.copy(range = TextEdits.rangeForOffset(source, offset, tag.text.length)))
                    require(found.size <= 32, CPrototypeTagRefusal.INPUT_LIMIT)
                }
                // A whole-function closure check prevents unmodeled explicit type spellings
                // (for example compound literals or nested declarations) from disappearing.
                val text = source.substring(begin.start, end.end)
                val explicitSites = CTokenizer().tokenize(text).zipWithNext().filter {
                    it.first.text in setOf("struct", "union", "enum") && it.second.text in names
                }.map { (_, tag) ->
                    val offset = begin.start + TextEdits.offsetOf(text, SourcePosition(tag.line - 1, tag.column))
                    TextEdits.rangeForOffset(source, offset, tag.text.length)
                }.toSet()
                require(explicitSites == found.filter { it.scopeId == scope }.map { it.cursor.range }.toSet(),
                    CPrototypeTagRefusal.UNSUPPORTED_SOURCE)
            }
        }
        require(found.map { it.cursor }.toSet().size == found.size, CPrototypeTagRefusal.INVALID_AST)
        CPrototypeTagQueriesReadout.Found(Collections.unmodifiableList(found.sortedWith(compareBy(
            { it.cursor.translationUnit.toString() }, { it.cursor.range.start.line }, { it.cursor.range.start.character }))))
    } catch (failure: PrototypeFailure) { CPrototypeTagQueriesReadout.Refused(failure.reason) }
      catch (_: Exception) { CPrototypeTagQueriesReadout.Refused(CPrototypeTagRefusal.INVALID_AST) }

    fun decode(asts: Map<Path, JsonObject>, sources: Map<Path, String>, workspaceRoot: Path,
        responses: Map<CPrototypeTagCursor, String>,
        logicalPaths: Map<Path, Path> = sources.keys.associateWith { it }): CPrototypeTagReadout = try {
        val queries = when (val result = queries(asts, sources, logicalPaths)) {
            is CPrototypeTagQueriesReadout.Found -> result.queries
            is CPrototypeTagQueriesReadout.Refused -> fail(result.reason)
        }
        require(workspaceRoot.isAbsolute && workspaceRoot == workspaceRoot.normalize(), CPrototypeTagRefusal.INCOMPLETE_CONTEXT)
        require(responses.keys == queries.map { it.cursor }.toSet(), CPrototypeTagRefusal.NATIVE_EVIDENCE_UNAVAILABLE)
        val index = (CCompilerDeclarationIndex.analyze(asts, sources, logicalPaths) as CCompilerDeclarationReadout.Found).index
        val nativePaths = logicalPaths.map { (physical, logical) -> workspaceRoot.resolve(logical) to physical }.toMap()
        val nativeSources = nativePaths.mapValues { (_, physical) -> sources.getValue(physical) }
        val logicalToPhysical = logicalPaths.entries.associate { it.value to it.key }
        data class Observation(val query: CPrototypeTagQuery, val info: CClangdSymbolInfo, val declaration: CPrototypeTagCursor)
        val introduced = mutableListOf<Observation>()
        val headerGraphs = mutableMapOf<Path, List<CClangSourceEntry>>()
        for (query in queries) {
            val info = when (val result = CClangdSymbolInfoDecoder.decode(responses.getValue(query.cursor), nativeSources)) {
                is CClangdSymbolInfoReadout.Found -> result.info
                else -> fail(CPrototypeTagRefusal.NATIVE_EVIDENCE_UNAVAILABLE)
            }
            require(info.name == query.name, CPrototypeTagRefusal.BINDING_MISMATCH)
            val declaration = CPrototypeTagCursor(query.cursor.translationUnit,
                logicalPaths.getValue(nativePaths.getValue(info.declaration.file)), info.declaration.range)
            val existing = index.declarations.filter { it.translationUnit == declaration.translationUnit &&
                it.file == declaration.file && it.range == declaration.range && it.kind == query.kind && it.name == query.name }
            if (existing.size == 1) continue // Existing compiler declarations retain their independent binding proof.
            require(existing.isEmpty() && info.definition == null && info.containerName == query.functionName &&
                declaration.file == query.cursor.file, CPrototypeTagRefusal.BINDING_MISMATCH)
            require(queries.any { it.cursor == declaration && it.scopeId == query.scopeId && it.name == query.name && it.kind == query.kind },
                CPrototypeTagRefusal.BINDING_MISMATCH)
            if (query.cursor.file != query.cursor.translationUnit) {
                // Standalone header analysis cannot override an includer's earlier tag lookup.
                val physicalTu = logicalToPhysical.getValue(query.cursor.translationUnit)
                val physicalFile = logicalToPhysical.getValue(query.cursor.file)
                val entries = headerGraphs.getOrPut(physicalTu) {
                    CClangSourceGraph.read(asts.getValue(physicalTu), physicalTu, sources)
                }
                val offset = TextEdits.offsetOf(sources.getValue(physicalFile), query.cursor.range.start)
                val function = entries.singleOrNull { entry ->
                    string(entry.node, "kind") == "FunctionDecl" && string(entry.node, "name") == query.functionName &&
                        entry.location?.file == physicalFile && entry.begin?.file == physicalFile &&
                        entry.end?.file == physicalFile && entry.begin.start <= offset && offset < entry.end.end
                } ?: fail(CPrototypeTagRefusal.BINDING_MISMATCH)
                require(function.parent == "TU", CPrototypeTagRefusal.BINDING_MISMATCH)
                require(entries.takeWhile { it.path != function.path }.none { entry ->
                    entry.parent == "TU" && string(entry.node, "name") == query.name &&
                        string(entry.node, "kind") in setOf("RecordDecl", "EnumDecl")
                }, CPrototypeTagRefusal.BINDING_MISMATCH)
            }
            introduced += Observation(query, info, declaration)
        }
        require(introduced.groupBy { Triple(it.query.cursor.translationUnit, it.info.usr, it.info.id) }.values.all {
            it.map { observation -> observation.declaration to observation.query.scopeId }.distinct().size == 1
        }, CPrototypeTagRefusal.BINDING_MISMATCH)
        val families = mutableListOf<CCompilerNavigationFacts>()
        val excluded = mutableSetOf<CRecordNavigationSite>()
        val groups = introduced.groupBy { it.declaration to it.query.scopeId }
        require(index.declarations.size + groups.size <= 16_384, CPrototypeTagRefusal.INPUT_LIMIT)
        for ((key, observations) in groups) {
            val (cursor, scope) = key
            require(observations.map { it.info.usr to it.info.id }.distinct().size == 1 &&
                observations.any { it.query.cursor == cursor }, CPrototypeTagRefusal.BINDING_MISMATCH)
            val first = observations.first { it.query.cursor == cursor }.query
            val physicalFile = logicalToPhysical.getValue(cursor.file)
            val source = sources.getValue(physicalFile)
            val start = TextEdits.offsetOf(source, cursor.range.start)
            val end = TextEdits.offsetOf(source, cursor.range.end)
            require(observations.all { TextEdits.offsetOf(source, it.query.cursor.range.start) >= start }, CPrototypeTagRefusal.BINDING_MISMATCH)
            val declaration = CCompilerDeclaration(CCompilerDeclarationIndex.identity(cursor.translationUnit, cursor.file,
                first.kind, start, end, first.name), cursor.translationUnit, cursor.file, cursor.range, first.name,
                first.kind, CCompilerLookupSpace.TAG, CCompilerLinkage.NONE, scope)
            require(index.declarations.none { it.id == declaration.id }, CPrototypeTagRefusal.BINDING_MISMATCH)
            fun site(value: CPrototypeTagCursor) = CCompilerNavigationSite(value.file, value.range, setOf(value.translationUnit))
            val references = observations.map { it.query.cursor }.filter { it != cursor }.distinct().map(::site)
            families += CCompilerNavigationFacts(declaration, emptyList(), listOf(site(cursor)), references)
            observations.forEach { observation ->
                val point = observation.query.cursor
                excluded += CRecordNavigationSite(logicalToPhysical.getValue(point.translationUnit),
                    logicalToPhysical.getValue(point.file), CFunctionByteSpan(TextEdits.offsetOf(source, point.range.start),
                        TextEdits.offsetOf(source, point.range.end)))
            }
        }
        CPrototypeTagReadout.Found(CPrototypeTagNavigationFacts(prototypeInputIdentity(asts, sources), logicalPaths, families, excluded))
    } catch (failure: PrototypeFailure) { CPrototypeTagReadout.Refused(failure.reason) }
      catch (_: Exception) { CPrototypeTagReadout.Refused(CPrototypeTagRefusal.INVALID_AST) }

    private fun string(node: JsonObject, key: String): String? = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun require(value: Boolean, reason: CPrototypeTagRefusal) { if (!value) fail(reason) }
    private fun fail(reason: CPrototypeTagRefusal): Nothing = throw PrototypeFailure(reason)
}

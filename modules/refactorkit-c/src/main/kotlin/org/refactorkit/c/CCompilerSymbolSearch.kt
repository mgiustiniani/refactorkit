package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.Path

private enum class CCompilerReadKind(val prefix: String) { SEARCH("c.search"), NAVIGATION("c.navigation") }

/** Application acquisition shared by all C reads. Adapters only extract, delegate and project responses. */
private object CCompilerRead {
    fun string(params: JsonObject, key: String): String? = params[key]?.let { value ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C query $key must be a string")
    }
    fun limit(params: JsonObject): Int {
        val value = if ("limit" !in params) 200 else (params["limit"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C query limit must be an integer")
        if (value !in 1..200) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C query limit must be in 1..200")
        return value
    }
    fun <T> execute(snapshot: ProjectSnapshot, params: JsonObject, kind: CCompilerReadKind,
        discover: () -> ClangSemanticToolchain,
        action: (CBoundedTranslationUnitCompilerResult.Available, CCompilerDeclarationIndex, ClangSemanticToolchain, CPrototypeTagNavigationFacts?, CCompilerReadSources) -> T): T {
        val expected = string(params, "expectedSnapshotHash")
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C query requires expectedSnapshotHash")
        if (!expected.matches(Regex("[0-9a-f]{64}"))) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C query snapshot hash is invalid")
        if (expected != snapshot.hash || snapshot.copy().hash != expected) refuse("${kind.prefix}.snapshotStale", "Expected C query snapshot is stale", JsonRpcErrorCodes.SNAPSHOT_CHANGED)
        val toolchain = discover()
        return ExternalSemanticProcessManager().use { processes ->
            val acquisition = CBoundedTranslationUnitFactsService(toolchain, processes, kind.prefix)
            val captured = when (val result = acquisition.analyze(snapshot, snapshot)) {
                is CBoundedTranslationUnitCompilerResult.Available -> result
                is CBoundedTranslationUnitCompilerResult.Unavailable -> refuse(result.diagnostic)
            }
            val readSources = when (val result = CCompilerReadSources.create(snapshot.workspace.root,
                captured.facts.sources, captured.facts.logicalPaths, captured.facts.readOnlySources,
                toolchain.readOnlySdkInputs.provenance)) {
                is CCompilerReadSourceResult.Available -> result.sources
                is CCompilerReadSourceResult.Refused -> refuse("${kind.prefix}.projectionUnavailable", result.reason.name,
                    details = mapOf("projectionFailure" to result.reason.name))
            }
            val queryCapture = captured.copy(facts = CBoundedTranslationUnitAsts(captured.facts.asts,
                readSources.sources, readSources.logicalPaths, captured.facts.readOnlySources))
            val index = when (val result = CCompilerDeclarationIndex.analyze(queryCapture.facts.asts, queryCapture.facts.sources, queryCapture.facts.logicalPaths)) {
                is CCompilerDeclarationReadout.Found -> result.index
                is CCompilerDeclarationReadout.Refused -> refuse("${kind.prefix}.projectionUnavailable", result.reason.name,
                    details = mapOf("projectionFailure" to result.reason.name))
            }
            // Expected not-found/refusal responses are also gated by final input/tool currency.
            val output = try {
                val prototypeTags = when (val result = CPrototypeTagFactsAcquisition.capture(snapshot, queryCapture.facts, toolchain, processes, kind.prefix)) {
                    CPrototypeTagAcquisitionResult.NotRequired -> null
                    is CPrototypeTagAcquisitionResult.Available -> result.facts
                    is CPrototypeTagAcquisitionResult.Unavailable -> refuse(result.diagnostic)
                }
                val composed = if (prototypeTags == null) index else index.withPrototypeTags(prototypeTags)
                Result.success(action(queryCapture, composed, toolchain, prototypeTags, readSources))
            } catch (failure: JsonRpcException) { Result.failure(failure) }
            try { acquisition.requireCurrent(snapshot) }
            catch (failure: CBoundedTranslationUnitFailure) { refuse(failure.diagnostic) }
            if (!CCompilationSnapshotCapture.compilerIsCurrent(toolchain)) refuse("${kind.prefix}.compilerUnavailable", "Retained compiler identity changed during query")
            output.getOrThrow()
        }
    }
    fun refuse(code: String, message: String, rpcCode: Int = JsonRpcErrorCodes.PLAN_REFUSED,
        details: Map<String, String> = emptyMap()): Nothing =
        throw JsonRpcException(rpcCode, "$code: $message", buildJsonObject {
            put("refusalCode", code); details.forEach { (key, value) -> put(key, value) }
        })
    private fun refuse(diagnostic: Diagnostic): Nothing {
        val code = diagnostic.code ?: "c.query.unavailable"
        refuse(code, diagnostic.message, if (code.endsWith(".snapshotChanged")) JsonRpcErrorCodes.SNAPSHOT_CHANGED else JsonRpcErrorCodes.PLAN_REFUSED)
    }
}

object CCompilerSymbolSearch {
    fun search(snapshot: ProjectSnapshot, params: JsonObject, discover: () -> ClangSemanticToolchain): JsonArray {
        val query = CCompilerRead.string(params, "query").orEmpty(); val limit = CCompilerRead.limit(params)
        if (query.length > 512) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C search query exceeds its bounded profile")
        return CCompilerRead.execute(snapshot, params, CCompilerReadKind.SEARCH, discover) { captured, index, toolchain, prototypeTags, readSources ->
            val selected = index.search(query, limit = limit)
            buildJsonArray { selected.items.forEach { declaration -> add(buildJsonObject {
                put("id", declaration.id); declarationMetadata(declaration, snapshot, captured.context.identity, toolchain, "clang.c-declarations.v1", "declarations", prototypeTags, readSources)
                location(declaration.file, declaration.range, snapshot, readSources)
                put("queryTotal", selected.total); put("queryTruncated", selected.truncated)
            }) } }
        }
    }
}

object CCompilerSymbolNavigation {
    fun definition(snapshot: ProjectSnapshot, params: JsonObject, discover: () -> ClangSemanticToolchain): JsonObject =
        navigate(snapshot, params, discover, references = false).jsonObject

    fun references(snapshot: ProjectSnapshot, params: JsonObject, discover: () -> ClangSemanticToolchain): JsonArray =
        navigate(snapshot, params, discover, references = true).jsonArray

    private fun navigate(snapshot: ProjectSnapshot, params: JsonObject, discover: () -> ClangSemanticToolchain,
        references: Boolean): JsonElement {
        val symbol = CCompilerRead.string(params, "symbol")
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C navigation requires symbol")
        if (!symbol.matches(Regex("c-decl:[0-9a-f]{64}"))) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,
            "C navigation requires an opaque c-decl symbol ID returned by symbol.search")
        val limit = CCompilerRead.limit(params)
        val includeDeclaration = if ("includeDeclaration" !in params) false else
            (params["includeDeclaration"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be a boolean")
        return CCompilerRead.execute(snapshot, params, CCompilerReadKind.NAVIGATION, discover) { captured, index, toolchain, prototypeTags, readSources ->
            project(snapshot, captured, index, symbol, limit, includeDeclaration, references, toolchain, prototypeTags = prototypeTags, readSources = readSources)
        }
    }

    internal fun project(snapshot: ProjectSnapshot, captured: CBoundedTranslationUnitCompilerResult.Available,
        index: CCompilerDeclarationIndex, symbol: String, limit: Int, includeDeclaration: Boolean,
        references: Boolean, toolchain: ClangSemanticToolchain, missingDefinitionAsNull: Boolean = false,
        prototypeTags: CPrototypeTagNavigationFacts? = null, readSources: CCompilerReadSources): JsonElement {
        val facts = when (val result = CCompilerNavigationFactsDecoder.decode(captured.facts.asts, captured.facts.sources, symbol, captured.facts.logicalPaths, prototypeTags)) {
            is CCompilerNavigationReadout.Found -> result.facts
            CCompilerNavigationReadout.NotFound -> CCompilerRead.refuse("c.navigation.symbolNotFound", "The symbol is absent from this captured native index", JsonRpcErrorCodes.SYMBOL_NOT_FOUND)
            is CCompilerNavigationReadout.Refused -> CCompilerRead.refuse("c.navigation.familyUnavailable", "${result.reason.name}: ${result.detail}",
                details = mapOf("familyFailure" to result.reason.name, "bindingDetail" to result.detail))
        }
        return if (!references) {
            val site = facts.definitions.singleOrNull()
                ?: if (missingDefinitionAsNull) return JsonNull else CCompilerRead.refuse("c.navigation.definitionUnavailable", "The captured declaration has no complete definition",
                    details = mapOf("familyFailure" to CCompilerNavigationRefusal.DEFINITION_UNAVAILABLE.name))
            val candidates = index.declarations.filter { it.file == site.file && it.range == site.range && it.kind == facts.selected.kind }
            val target = candidates.singleOrNull { it.translationUnit == facts.selected.translationUnit } ?: candidates.singleOrNull()
                ?: CCompilerRead.refuse("c.navigation.definitionAmbiguous", "Definition lacks a unique captured compiler context")
            buildJsonObject {
                put("id", target.id); put("requestedSymbolId", symbol)
                declarationMetadata(target, snapshot, captured.context.identity, toolchain, "clang.c-navigation.v1", "captured-family", prototypeTags, readSources)
                location(site.file, site.range, snapshot, readSources); observations(site.translationUnits)
            }
        } else {
            val all = (facts.references + if (includeDeclaration) facts.declarations else emptyList())
                .groupBy { it.file to it.range }.map { (_, sites) -> sites.first().copy(translationUnits = sites.flatMap { it.translationUnits }.toSet()) }
                .sortedWith(compareBy({ it.file.toString() }, { it.range.start.line }, { it.range.start.character }))
            buildJsonArray { all.take(limit).forEach { site -> add(buildJsonObject {
                put("symbol", symbol)
                declarationMetadata(facts.selected, snapshot, captured.context.identity, toolchain, "clang.c-navigation.v1", "captured-family", prototypeTags, readSources)
                location(site.file, site.range, snapshot, readSources); observations(site.translationUnits)
                put("queryTotal", all.size); put("queryTruncated", all.size > limit)
            }) } }
        }
    }
}

/** Standard editor reads use the same compiler family projection as managed ID queries. */
enum class CCompilerEditorQuery { WORKSPACE_SYMBOLS, DOCUMENT_SYMBOLS, DEFINITION, REFERENCES }

object CCompilerEditorQueries {
    fun execute(root: Path, operation: CCompilerEditorQuery, params: JsonObject,
        documents: List<ImmutableEditorOverlayDocument>, discover: () -> ClangSemanticToolchain): JsonElement {
        val snapshot = try { CProjectScanner().scan(root) } catch (failure: CProjectScanException) {
            CCompilerRead.refuse("c.sourceCapture.unavailable", "C editor input capture is unavailable",
                details = mapOf("sourceCaptureFailure" to failure.code.name))
        }
        val editors = documents.filter { it.path.toString().substringAfterLast('.') in setOf("c", "h") }
        if (editors.isNotEmpty()) {
            val overlay = runCatching { ImmutableEditorOverlay.create(snapshot, editors, expectedLanguageId = "c") }
                .getOrElse { CCompilerRead.refuse("c.editor.documentUnavailable", "Open C documents are outside the captured saved input") }
            if (overlay.providerSnapshot.hash != snapshot.hash) CCompilerRead.refuse("c.editor.unsavedUnsupported",
                "C queries require open C documents to match their captured saved contents")
        }
        val values = JsonObject(params + ("expectedSnapshotHash" to
            (params["expectedSnapshotHash"] ?: JsonPrimitive(snapshot.hash))))
        if (operation == CCompilerEditorQuery.WORKSPACE_SYMBOLS)
            return CCompilerSymbolSearch.search(snapshot, values, discover)
        val rawFile = CCompilerRead.string(values, "file")
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C editor query requires a file")
        val file = runCatching { Path.of(rawFile) }.getOrElse {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid C editor file")
        }
        if (file.isAbsolute || file != file.normalize() || file.startsWith("..") || file.toString().isBlank())
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C editor file must be workspace-relative")
        val source = snapshot.files.singleOrNull { it.path == file && it.languageId == "c" }
            ?: CCompilerRead.refuse("c.editor.fileUnavailable", "C editor file is not captured")
        if (operation == CCompilerEditorQuery.DOCUMENT_SYMBOLS) return CCompilerRead.execute(snapshot, values,
            CCompilerReadKind.SEARCH, discover) { captured, index, toolchain, prototypeTags, readSources ->
            val selected = index.declarations.filter { it.file == file }.distinctBy { listOf(it.file, it.range, it.kind, it.name) }
            if (selected.size > 200) CCompilerRead.refuse("c.editor.resultLimit", "Complete C outline exceeds the editor result limit")
            buildJsonArray { selected.forEach { declaration -> add(buildJsonObject {
                put("id", declaration.id)
                declarationMetadata(declaration, snapshot, captured.context.identity, toolchain, "clang.c-declarations.v1", "declarations", prototypeTags, readSources)
                location(declaration.file, declaration.range, snapshot, readSources)
            }) } }
        }
        fun coordinate(key: String) = (values[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C editor $key must be an integer")
        val line = coordinate("line"); val character = coordinate("character")
        runCatching { TextEdits.offsetOf(source.content, SourcePosition(line, character)) }.getOrElse {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C editor position is outside the captured source")
        }
        val includeDeclaration = if ("includeDeclaration" !in values) false else
            (values["includeDeclaration"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be a boolean")
        val references = operation == CCompilerEditorQuery.REFERENCES
        return CCompilerRead.execute(snapshot, values, CCompilerReadKind.NAVIGATION, discover) cursor@ { captured, index, toolchain, prototypeTags, readSources ->
            val recordBinding = CRecordCursorBinding.resolve(captured.facts.asts, captured.facts.sources, index,
                file, SourcePosition(line, character), captured.facts.logicalPaths, prototypeTags)
            val selected = when (recordBinding) {
                is CRecordCursorReadout.Found -> recordBinding.declaration
                is CRecordCursorReadout.Refused -> CCompilerRead.refuse("c.editor.bindingUnavailable",
                    "Original C record cursor binding is unavailable", details = mapOf("nativeRefusal" to recordBinding.reason.name))
                CRecordCursorReadout.NotRecordCursor -> {
                    val anchor = ExternalSemanticProcessManager().use { manager ->
                        CSymbolNavigation(toolchain, manager).use { navigation ->
                            navigation.start(snapshot)
                            navigation.definition(snapshot.workspace.root.resolve(file), line, character)
                        }
                    }
                    val point = when (anchor) {
                        is CSymbolNavigationResult.Found -> anchor.definition
                        is CSymbolNavigationResult.NotFound -> return@cursor if (references) JsonArray(emptyList()) else JsonNull
                        is CSymbolNavigationResult.Unavailable -> CCompilerRead.refuse("c.editor.bindingUnavailable",
                            "Native cursor binding is unavailable", details = mapOf("nativeRefusal" to
                                (anchor.diagnostics.firstOrNull()?.code ?: "unknown")))
                    }
                    val absolute = point.file.toAbsolutePath().normalize()
                    val relative = if (absolute.startsWith(snapshot.workspace.root)) snapshot.workspace.root.relativize(absolute)
                        else readSources.logicalSdkPath(absolute)
                            ?: CCompilerRead.refuse("c.editor.bindingUnavailable", "Native binding escapes captured inputs")
                    val candidates = index.declarations.filter { it.file == relative &&
                        it.range.start == SourcePosition(point.startLine, point.startCharacter) }
                    if (candidates.isEmpty() || candidates.map { listOf(it.file, it.range, it.kind, it.name, it.lookupSpace, it.linkage) }.distinct().size != 1)
                        CCompilerRead.refuse("c.editor.bindingUnavailable", "Native cursor and compiler declaration identities do not agree")
                    candidates.singleOrNull { it.translationUnit == file }
                        ?: candidates.sortedBy { it.translationUnit.toString() }.first()
                }
            }
            val result = CCompilerSymbolNavigation.project(snapshot, captured, index, selected.id,
                CCompilerRead.limit(values), includeDeclaration, references, toolchain, missingDefinitionAsNull = !references, prototypeTags = prototypeTags, readSources = readSources)
            if (references && result.jsonArray.any { it.jsonObject["queryTruncated"]?.jsonPrimitive?.booleanOrNull == true })
                CCompilerRead.refuse("c.editor.resultLimit", "Complete C references exceed the editor result limit")
            result
        }
    }
}

private fun JsonObjectBuilder.location(file: Path, range: SourceRange, snapshot: ProjectSnapshot,
    readSources: CCompilerReadSources) {
    val sdkPath = readSources.sdkPath(file)
    put("file", ProtocolPath.serialize(sdkPath ?: file))
    put("line", range.start.line + 1); put("character", range.start.character)
    put("endLine", range.end.line + 1); put("endCharacter", range.end.character)
    if (readSources.sdkIdentity != null) {
        put("uri", (sdkPath ?: snapshot.workspace.root.resolve(file)).toUri().toString())
        put("sourceOwnership", if (sdkPath == null) "WORKSPACE" else "SDK")
        put("readOnly", sdkPath != null)
        readSources.sdkRecord(file)?.let { record -> put("sdk", buildJsonObject {
            put("root", record.root.toString()); put("identity", record.identity)
            put("provenance", record.provenance); put("license", record.license)
        }) }
    }
}
private fun JsonObjectBuilder.observations(units: Set<Path>) {
    put("translationUnits", JsonArray(units.sortedBy { it.toString() }.map { JsonPrimitive(ProtocolPath.serialize(it)) }))
}
private fun JsonObjectBuilder.declarationMetadata(declaration: CCompilerDeclaration, snapshot: ProjectSnapshot, contextHash: String,
    toolchain: ClangSemanticToolchain, provider: String, completeness: String,
    prototypeTags: CPrototypeTagNavigationFacts? = null, readSources: CCompilerReadSources) {
    put("name", declaration.name); put("kind", when (declaration.kind) {
        CCompilerDeclarationKind.FUNCTION -> Symbol.Kind.FUNCTION
        CCompilerDeclarationKind.PARAMETER -> Symbol.Kind.PARAMETER
        CCompilerDeclarationKind.VARIABLE -> Symbol.Kind.VARIABLE
        CCompilerDeclarationKind.STRUCT, CCompilerDeclarationKind.UNION -> Symbol.Kind.RECORD
        CCompilerDeclarationKind.ENUM -> Symbol.Kind.ENUM
        CCompilerDeclarationKind.TYPEDEF -> Symbol.Kind.TYPE_ALIAS
        CCompilerDeclarationKind.ENUMERATOR -> Symbol.Kind.CONSTANT
        CCompilerDeclarationKind.FIELD -> Symbol.Kind.FIELD
        CCompilerDeclarationKind.LABEL -> Symbol.Kind.UNKNOWN
    }.name)
    put("cKind", declaration.kind.name); put("languageId", "c")
    put("translationUnit", ProtocolPath.serialize(declaration.translationUnit))
    put("lookupSpace", declaration.lookupSpace.name); put("linkage", declaration.linkage.name)
    put("providerId", provider); put("evidence", "compiler"); put("completeness", completeness)
    put("snapshotHash", snapshot.hash); put("compilationContextHash", contextHash)
    readSources.sdkIdentity?.let { put("sdkInputsIdentity", it) }
    put("compilerVersion", toolchain.provenance.clangVersion)
    put("compilerSha256", toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
    if (prototypeTags?.declarations?.isNotEmpty() == true) {
        put("supplementalBindingProviderId", "clangd.symbolInfo.v22")
        put("clangdVersion", toolchain.provenance.clangdVersion)
        put("clangdSha256", toolchain.provenance.evidence.single { it.role == "clangd-executable" }.sha256)
    }
}

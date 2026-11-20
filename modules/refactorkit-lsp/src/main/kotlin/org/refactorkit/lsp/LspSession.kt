package org.refactorkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.ImmutableEditorOverlayDocument
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.LanguageCapabilityProtocol
import org.refactorkit.core.ManagedRollbackExecutor
import org.refactorkit.core.ManagedRollbackOutcome
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchDiffRenderer
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PendingPlanStore
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.RefactoringApplyIdentity
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RollbackLookupVisibility
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.RollbackPreflightDecision
import org.refactorkit.core.RollbackPreflightGuard
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdits
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.WorkspaceRefreshCoordinator
import org.refactorkit.core.WorkspaceRefreshResult
import org.refactorkit.c.CCompilerEditorQueries
import org.refactorkit.c.CCompilerEditorQuery
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CProjectScanException
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.CReadOnlySdkRequestJson
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaChangeVisibilityPlanner
import org.refactorkit.java.JavaExtractInterfacePlanner
import org.refactorkit.java.JavaExtractInterfaceRequest
import org.refactorkit.java.JavaExtractSuperclassPlanner
import org.refactorkit.java.JavaExtractSuperclassRequest
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaExtractParameterPlanner
import org.refactorkit.java.JavaExtractParameterRequest
import org.refactorkit.java.JavaFormatFilePlanner
import org.refactorkit.java.JavaEditorOverlaySession
import org.refactorkit.java.JavaEditorQueryKind
import org.refactorkit.java.JavaEditorQueryPayload
import org.refactorkit.java.JavaEditorQueryRequest
import org.refactorkit.java.JavaEditorQueryResult
import org.refactorkit.java.JavaEditorReadResult
import org.refactorkit.java.JavaExtractVariablePlanner
import org.refactorkit.java.JavaExtractVariableRequest
import org.refactorkit.java.JavaEncapsulateFieldPlanner
import org.refactorkit.java.JavaMutationSelectionService
import org.refactorkit.java.JavaMakeStaticPlanner
import org.refactorkit.java.JavaJdtNavigationKind
import org.refactorkit.java.JavaJdtNavigationProjection
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.treesitter.GenericOutline
import org.refactorkit.treesitter.TreeSitterAdapter
import org.refactorkit.typescript.TypeScriptAdapterDescriptors
import org.refactorkit.java.JavaLexer
import org.refactorkit.java.JavaMoveClassLexicalFallbackReviewJsonProjection
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPromotionAttemptMetadata
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaProjectContextRequest
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaCreateMavenModulePlanner
import org.refactorkit.java.JavaMoveAcrossMavenModulesPlanner
import org.refactorkit.java.JavaMoveSourceRootPlanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector
import org.refactorkit.kotlin.KotlinAdapterRegistration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.max

/**
 * LSP session — business logic for the Language Server Protocol server.
 *
 * Translates LSP requests to RefactorKit operations and converts results back
 * to LSP-shaped JSON.  Does NOT handle framing (Content-Length) — that is done
 * in [RefactorKitLsp].
 */
class LspSession {
    private val adapter = JavaLanguageAdapter()
    private val kotlinAdapter = KotlinLanguageAdapter()
    private val moveClassDispatcher = JavaMoveClassOperationDispatcher(adapter)
    private val scanner = JavaProjectScanner()
    private val structuralAdapter = TreeSitterAdapter()

    @Volatile private var rootUri: String? = null
    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var supportsDocumentChanges: Boolean = false
    @Volatile private var supportsCreateFile: Boolean = false
    private val openDocuments = linkedMapOf<String, OpenDocument>()
    private var cQueryConfiguration: JsonObject? = null
    private val javaEditorOverlaySession = JavaEditorOverlaySession()
    private val pendingPlans = PendingPlanStore<PatchPlan>()
    private val pendingCPlans = PendingPlanStore<PendingCPlan>()

    private data class PendingCPlan(
        val plan: PatchPlan,
        val snapshot: ProjectSnapshot,
        val toolchain: ClangSemanticToolchain,
    )

    var onExit: () -> Unit = {}
    var onNotification: (method: String, params: JsonElement) -> Unit = { _, _ -> }

    // ── dispatcher ────────────────────────────────────────────────────────────

    /**
     * Returns a result [JsonElement] or throws [JsonRpcException].
     * Notifications return [JsonNull].
     */
    fun dispatch(method: String, params: JsonObject?): JsonElement = when (method) {
        "initialize"                    -> initialize(params)
        "initialized"                   -> { refreshSnapshot(); JsonNull }
        "shutdown"                      -> JsonNull
        "exit"                          -> { onExit(); JsonNull }
        "textDocument/didOpen"          -> { didOpen(params); JsonNull }
        "textDocument/didChange"        -> { didChange(params); JsonNull }
        "textDocument/didSave"          -> { didSave(params); JsonNull }
        "textDocument/didClose"         -> { didClose(params); JsonNull }
        "workspace/symbol"             -> cEditorQuery(CCompilerEditorQuery.WORKSPACE_SYMBOLS, params)
        "textDocument/definition"       -> definition(params)
        "textDocument/implementation"   -> implementation(params)
        "textDocument/references"       -> references(params)
        "textDocument/prepareRename"    -> prepareRename(params)
        "textDocument/rename"           -> rename(params)
        "textDocument/codeAction"       -> codeAction(params)
        "textDocument/documentSymbol"   -> documentSymbol(params)
        "textDocument/semanticTokens/full" -> semanticTokensFull(params)
        "textDocument/diagnostic"       -> documentDiagnostic(params)
        "textDocument/formatting"       -> formatting(params)
        "workspace/executeCommand"      -> executeCommand(params)
        else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
    }

    // ── LSP methods ───────────────────────────────────────────────────────────

    private fun initialize(params: JsonObject?): JsonElement {
        cQueryConfiguration = params?.obj("initializationOptions")?.obj("c")
        supportsDocumentChanges = params?.obj("capabilities")?.obj("workspace")
            ?.obj("workspaceEdit")?.get("documentChanges")?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        supportsCreateFile = (params?.obj("capabilities")?.obj("workspace")
            ?.obj("workspaceEdit")?.get("resourceOperations") as? JsonArray)
            ?.any { (it as? JsonPrimitive)?.content == "create" } == true
        openDocuments.clear()
        javaEditorOverlaySession.clear()
        pendingPlans.clear()
        pendingCPlans.clear()
        moveClassDispatcher.clearLexicalReviewAudit()
        rootUri = params?.string("rootUri") ?: (params?.get("workspaceFolders") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.string("uri")
        rootUri?.let { uri ->
            val root = Paths.get(URI(uri)).toAbsolutePath().normalize()
            val recoveryErrors = PatchEngine(root).inspectRecovery()
            if (recoveryErrors.isNotEmpty()) {
                throw JsonRpcException(
                    JsonRpcErrorCodes.INTERNAL_ERROR,
                    "Workspace recovery required; run explicit patch recovery before LSP initialization: ${recoveryErrors.joinToString("; ") { it.message }}",
                )
            }
            refreshSnapshotFromUri(uri)
        }
        return buildJsonObject {
            put("capabilities", buildJsonObject {
                put("textDocumentSync", buildJsonObject {
                    put("openClose", true)
                    put("change", 1) // Full sync
                    put("save", buildJsonObject { put("includeText", true) })
                })
                put("workspaceSymbolProvider", cQueryConfiguration != null)
                put("definitionProvider", true)
                put("implementationProvider", buildJsonObject {
                    put("documentSelector", buildJsonArray {
                        add(buildJsonObject { put("language", "java"); put("scheme", "file") })
                    })
                })
                put("referencesProvider", true)
                put("documentSymbolProvider", true)
                put("codeActionProvider", true)
                put("renameProvider", buildJsonObject { put("prepareProvider", true) })
                put("documentFormattingProvider", true)
                put("semanticTokensProvider", buildJsonObject {
                    put("legend", buildJsonObject {
                        put("tokenTypes", buildJsonArray { TOKEN_TYPES.forEach { add(JsonPrimitive(it)) } })
                        put("tokenModifiers", buildJsonArray { TOKEN_MODIFIERS.forEach { add(JsonPrimitive(it)) } })
                    })
                    put("full", true)
                    put("range", false)
                })
                put("diagnosticProvider", buildJsonObject {
                    put("identifier", "refactorkit")
                    put("interFileDependencies", true)
                    put("workspaceDiagnostics", false)
                })
                put("experimental", buildJsonObject {
                    put("refactorkitLanguageKernel", LanguageCapabilityProtocol.render(
                        listOf(
                            javaLspAdapterDescriptor(),
                            KotlinAdapterRegistration.descriptor(),
                        ) + TypeScriptAdapterDescriptors.descriptors(),
                    ))
                    put("refactorkitSemanticOwnership", buildJsonObject {
                        put("java", "refactorkit-lsp")
                        put("kotlin", "refactorkit-external-compiler-read-only-via-cli-daemon-mcp")
                        put("typescript", "client-managed-native-lsp")
                        put("javascript", "client-managed-native-lsp")
                        // C mutations are managed RefactorKit commands only; the LSP never
                        // promises WAL/rollback for editor-applied C edits.
                        put("c", "refactorkit-managed-clang-semantic-via-cli-daemon-mcp-lsp")
                        put("managedMutationSurfaces", buildJsonArray {
                            add(JsonPrimitive("cli")); add(JsonPrimitive("daemon")); add(JsonPrimitive("mcp")); add(JsonPrimitive("lsp"))
                        })
                    })
                })
                put("executeCommandProvider", buildJsonObject {
                    put("commands", buildJsonArray {
                        add(JsonPrimitive("refactorkit.renameClass"))
                        add(JsonPrimitive("refactorkit.moveClass"))
                        add(JsonPrimitive("refactorkit.extractMethod"))
                        add(JsonPrimitive("refactorkit.changeSignature.renameParameter"))
                        add(JsonPrimitive("refactorkit.changeSignature.addParameter"))
                        add(JsonPrimitive("refactorkit.changeSignature.reorderParameters"))
                        add(JsonPrimitive("refactorkit.changeSignature.removeParameter"))
                        add(JsonPrimitive("refactorkit.changeType"))
                        add(JsonPrimitive("refactorkit.changeVisibility"))
                        add(JsonPrimitive("refactorkit.extractVariable"))
                        add(JsonPrimitive("refactorkit.extractField"))
                        add(JsonPrimitive("refactorkit.extractConstant"))
                        add(JsonPrimitive("refactorkit.extractInterface"))
                        add(JsonPrimitive("refactorkit.extractParameter"))
                        add(JsonPrimitive("refactorkit.extractSuperclass"))
                        add(JsonPrimitive("refactorkit.encapsulateField"))
                        add(JsonPrimitive("refactorkit.organizeImports"))
                        add(JsonPrimitive("refactorkit.formatFile"))
                        add(JsonPrimitive("refactorkit.safeDelete"))
                        add(JsonPrimitive("refactorkit.moveSourceRoot"))
                        add(JsonPrimitive("refactorkit.moveAcrossMavenModules"))
                        add(JsonPrimitive("refactorkit.createMavenModule"))
                        add(JsonPrimitive("refactorkit.renameMavenModule"))
                        add(JsonPrimitive("refactorkit.applyPlan"))
                        add(JsonPrimitive("refactorkit.rollback"))
                        add(JsonPrimitive("refactorkit.c.preview"))
                    })
                })
            })
            put("serverInfo", buildJsonObject {
                put("name", RefactorKitVersion.NAME)
                put("version", RefactorKitVersion.VERSION)
            })
        }
    }

    /** LSP-specific runtime projection: response output is bounded; synchronous reads are not cancellable. */
    private fun javaLspAdapterDescriptor(): org.refactorkit.core.LanguageAdapterDescriptor {
        val descriptor = JavaAdapterRegistration.create().descriptor
        fun lspRuntime(runtime: org.refactorkit.core.LanguageAdapterRuntime) = runtime.copy(
            supportsCancellation = false,
            limits = runtime.limits.copy(
                maxOutputBytes = ProtocolLimits.MAX_LSP_RESPONSE_BYTES.toLong(),
            ),
        )
        return descriptor.copy(
            runtime = lspRuntime(descriptor.runtime),
            capabilities = descriptor.capabilities.map { capability ->
                capability.copy(runtime = lspRuntime(capability.runtime ?: descriptor.runtime))
            },
        )
    }

    private fun didOpen(params: JsonObject?) {
        val document = params?.obj("textDocument") ?: missing("textDocument")
        val uri = document.string("uri") ?: missing("textDocument.uri")
        val version = document.int("version") ?: missing("textDocument.version")
        val text = document.string("text") ?: missing("textDocument.text")
        val path = relativePathForUri(uri)
        openDocuments[uri] = OpenDocument(path, version, text)
        pendingPlans.clear()
        pendingCPlans.clear()
        refreshSnapshot()
    }

    private fun didChange(params: JsonObject?) {
        val document = params?.obj("textDocument") ?: missing("textDocument")
        val uri = document.string("uri") ?: missing("textDocument.uri")
        val version = document.int("version") ?: missing("textDocument.version")
        val current = openDocuments[uri]
            ?: documentVersionError("Document is not open: $uri")
        if (version <= current.version) {
            documentVersionError(
                "Document version must increase for $uri: current=${current.version}, received=$version",
            )
        }
        val changes = params["contentChanges"] as? JsonArray ?: missing("contentChanges")
        if (changes.size != 1 || changes.single().jsonObject.containsKey("range")) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                "RefactorKit advertises full document sync and requires one range-free content change",
            )
        }
        val text = changes.single().jsonObject.string("text") ?: missing("contentChanges[0].text")
        openDocuments[uri] = current.copy(version = version, content = text)
        pendingPlans.clear()
        pendingCPlans.clear()
        refreshSnapshot()
    }

    private fun didSave(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: missing("textDocument.uri")
        val current = openDocuments[uri] ?: return
        val savedContent = params.string("text") ?: runCatching {
            Files.readString(snapshotRoot().resolve(current.path))
        }.getOrDefault(current.content)
        openDocuments[uri] = current.copy(content = savedContent)
        pendingPlans.clear()
        pendingCPlans.clear()
        refreshSnapshot()
    }

    private fun didClose(params: JsonObject?) {
        val uri = params?.obj("textDocument")?.string("uri") ?: missing("textDocument.uri")
        openDocuments.remove(uri)
        javaEditorOverlaySession.clear()
        pendingPlans.clear()
        pendingCPlans.clear()
        refreshSnapshot()
    }

    private fun isCDocument(uri: String): Boolean = runCatching {
        Paths.get(URI(uri)).fileName.toString().substringAfterLast('.') in setOf("c", "h")
    }.getOrDefault(false)

    /** Standard C LSP reads only extract protocol inputs and project the C application response. */
    private fun cEditorQuery(operation: CCompilerEditorQuery, params: JsonObject?): JsonElement {
        val root = snapshotRoot()
        val configuration = cQueryConfiguration ?: throw typeRepairRefusal("c.editor.toolchainRequired",
            "C editor queries require initializationOptions.c with explicit clang, clangd and clangFormat")
        if (listOf("clang", "clangd", "clangFormat").any { configuration.string(it).isNullOrBlank() })
            throw typeRepairRefusal("c.editor.toolchainRequired", "C editor toolchain paths must be explicit")
        val arguments = buildJsonObject {
            configuration.forEach { (key, value) -> put(key, value) }
            if (operation == CCompilerEditorQuery.WORKSPACE_SYMBOLS) put("query", params?.string("query").orEmpty())
            else {
                val uri = params?.obj("textDocument")?.string("uri") ?: missing("textDocument.uri")
                put("file", ProtocolPath.serialize(relativePathForUri(uri)))
                if (operation != CCompilerEditorQuery.DOCUMENT_SYMBOLS) {
                    val (_, line, character) = extractPosition(params) ?: missing("position")
                    put("line", line); put("character", character)
                    params?.obj("context")?.get("includeDeclaration")?.let { put("includeDeclaration", it) }
                }
            }
        }
        val documents = openDocuments.values.map { ImmutableEditorOverlayDocument(it.path, it.version.toLong(), it.content) }
        val result = CCompilerEditorQueries.execute(root, operation, arguments, documents) { discoverCToolchain(root, configuration) }
        if (result == JsonNull) return JsonNull
        return when (operation) {
            CCompilerEditorQuery.DEFINITION -> cQueryLocation(root, result.jsonObject)
            CCompilerEditorQuery.REFERENCES -> buildJsonArray { result.jsonArray.forEach { add(cQueryLocation(root, it.jsonObject)) } }
            CCompilerEditorQuery.DOCUMENT_SYMBOLS -> buildJsonArray {
                result.jsonArray.forEach { raw ->
                    val item = raw.jsonObject; val range = cQueryLocation(root, item).getValue("range")
                    add(buildJsonObject {
                        put("name", item.getValue("name")); put("kind", lspSymbolKind(org.refactorkit.core.Symbol.Kind.valueOf(item.getValue("kind").jsonPrimitive.content)))
                        put("range", range); put("selectionRange", range)
                    })
                }
            }
            CCompilerEditorQuery.WORKSPACE_SYMBOLS -> buildJsonArray {
                result.jsonArray.map { it.jsonObject }.distinctBy { listOf(it["file"], it["line"], it["character"], it["name"], it["kind"]) }.forEach { item ->
                    add(buildJsonObject {
                        put("name", item.getValue("name")); put("kind", lspSymbolKind(org.refactorkit.core.Symbol.Kind.valueOf(item.getValue("kind").jsonPrimitive.content)))
                        put("location", cQueryLocation(root, item))
                    })
                }
            }
        }
    }

    private fun cQueryLocation(root: Path, item: JsonObject): JsonObject = buildJsonObject {
        fun number(key: String) = item.getValue(key).jsonPrimitive.content.toInt()
        put("uri", root.resolve(item.getValue("file").jsonPrimitive.content).toUri().toString())
        put("range", rangeJson(number("line") - 1, number("character"), number("endLine") - 1, number("endCharacter")))
    }

    private fun definition(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
        if (isCDocument(fileUri)) return cEditorQuery(CCompilerEditorQuery.DEFINITION, params)
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
        val symbol = adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol ?: return JsonNull
        val absPath = snap.workspace.root.resolve(symbol.location.path).toAbsolutePath()
        return buildJsonObject {
            put("uri", absPath.toUri().toString())
            put("range", rangeJson(symbol.location.range.start.line, symbol.location.range.start.character,
                symbol.location.range.end.line, symbol.location.range.end.character))
        }
    }

    private fun implementation(params: JsonObject?): JsonElement {
        val (fileUri, line, character) = extractPosition(params) ?: return JsonArray(emptyList())
        val snap = snapshot ?: return JsonArray(emptyList())
        val file = snap.files.singleOrNull { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonArray(emptyList())
        if (file.languageId != "java") return JsonArray(emptyList())
        return when (val projection = adapter.semanticNavigation(
            snap,
            JavaJdtNavigationKind.IMPLEMENTATIONS,
            pointLocation(file.path, line, character),
            direction = "all",
            limit = ProtocolLimits.MAX_SYMBOL_RESULTS,
        )) {
            is JavaJdtNavigationProjection.Implementations -> {
                if (!projection.complete || projection.truncated) throw JsonRpcException(
                    JsonRpcErrorCodes.INTERNAL_ERROR,
                    "Java implementation lookup is incomplete (${projection.warningCount} warning(s))",
                )
                buildJsonArray {
                    projection.items.forEach { item ->
                        val symbol = item.symbol
                        add(buildJsonObject {
                            put("uri", snap.workspace.root.resolve(symbol.location.path).toAbsolutePath().toUri().toString())
                            put("range", rangeJson(
                                symbol.location.range.start.line,
                                symbol.location.range.start.character,
                                symbol.location.range.end.line,
                                symbol.location.range.end.character,
                            ))
                        })
                    }
                }
            }
            is JavaJdtNavigationProjection.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INTERNAL_ERROR,
                "Java implementation lookup refused (${projection.diagnostic.code}): ${projection.diagnostic.message}",
            )
            else -> throw IllegalStateException("Unexpected Java implementation projection")
        }
    }

    private fun references(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
        if (isCDocument(fileUri)) return cEditorQuery(CCompilerEditorQuery.REFERENCES, params)
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
        val symbol = adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol ?: return JsonNull
        val refs = adapter.findReferences(snap, symbol.id)
        return buildJsonArray {
            refs.forEach { ref ->
                val absPath = snap.workspace.root.resolve(ref.location.path).toAbsolutePath()
                add(buildJsonObject {
                    put("uri", absPath.toUri().toString())
                    put("range", rangeJson(
                        ref.location.range.start.line,
                        ref.location.range.start.character,
                        ref.location.range.end.line,
                        ref.location.range.end.character,
                    ))
                })
            }
        }
    }

    private fun prepareRename(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
        if (file.languageId == "c") return JsonNull // editor-managed C rename is not offered; use managed commands
        val symbol = adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol ?: return JsonNull
        return buildJsonObject {
            put("range", rangeJson(
                symbol.location.range.start.line, symbol.location.range.start.character,
                symbol.location.range.end.line, symbol.location.range.end.character,
            ))
            put("placeholder", symbol.name)
        }
    }

    private fun rename(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
        val newName = params?.string("newName") ?: return JsonNull
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
        if (file.languageId == "c") throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "C mutations are managed RefactorKit commands (cli/daemon/mcp); the LSP does not offer editor-applied C rename",
        )
        val symbol = adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol ?: return JsonNull
        val plan = when (symbol.kind) {
            org.refactorkit.core.Symbol.Kind.METHOD,
            org.refactorkit.core.Symbol.Kind.FIELD,
            org.refactorkit.core.Symbol.Kind.RECORD_COMPONENT ->
                JavaRenameMemberPlanner(adapter).preview(snap, symbol.id.value, newName)
            else ->
                JavaRenameClassPlanner(adapter).preview(snap, symbol.id.value, newName)
        }
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, plan.summary)
        }
        return planToLspWorkspaceEdit(plan, snap)
    }

    private fun codeAction(params: JsonObject?): JsonElement {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return JsonArray(emptyList())
        val snap = snapshot ?: return JsonArray(emptyList())
        val file = fileForUri(snap, fileUri) ?: return JsonArray(emptyList())
        if (file.languageId == "c") throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "C code actions are managed RefactorKit commands (cli/daemon/mcp); the LSP does not offer editor-applied C edits",
        )
        if (file.languageId != "java") return JsonArray(emptyList())
        val relPath = file.path.toString()
        val actions = mutableListOf<JsonObject>()

        actions += buildJsonObject {
            put("title", "RefactorKit: Organize imports")
            put("kind", "source.organizeImports")
            put("command", commandJson("RefactorKit: Organize imports", "refactorkit.organizeImports", buildJsonObject {
                put("file", relPath)
            }))
        }

        val range = params?.obj("range")
        val line = range?.obj("start")?.get("line")?.jsonPrimitive?.content?.toIntOrNull()
        val char = range?.obj("start")?.get("character")?.jsonPrimitive?.content?.toIntOrNull()
        val symbol = if (line != null && char != null) {
            adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol
        } else {
            null
        }
        if (symbol != null) {
                val renameCommand = when (symbol.kind) {
                    org.refactorkit.core.Symbol.Kind.METHOD,
                    org.refactorkit.core.Symbol.Kind.FIELD,
                    org.refactorkit.core.Symbol.Kind.RECORD_COMPONENT -> "refactorkit.renameMember"
                    else -> "refactorkit.renameClass"
                }
                actions += buildJsonObject {
                    put("title", "RefactorKit: Rename ${symbol.name}")
                    put("kind", "refactor.rename")
                    put("command", commandJson("RefactorKit: Rename ${symbol.name}", renameCommand, buildJsonObject {
                        put("symbol", symbol.id.value)
                        put("newName", symbol.name)
                    }))
                }
                actions += buildJsonObject {
                    put("title", "RefactorKit: Move ${symbol.name}")
                    put("kind", "refactor.move")
                    put("command", commandJson("RefactorKit: Move ${symbol.name}", "refactorkit.moveClass", buildJsonObject {
                        put("symbol", symbol.id.value)
                        put("targetPackage", JavaPackageUtil.extractPackage(file.content))
                    }))
                }
                if (symbol.kind == org.refactorkit.core.Symbol.Kind.METHOD) {
                    // Signature commands need explicit arguments. Clients with an input flow can
                    // invoke workspace/executeCommand; do not offer placeholder code actions.
                    val makeStatic = JavaMakeStaticPlanner(adapter).preview(snap, symbol.id.value)
                    actions += buildJsonObject {
                        val title = "RefactorKit: Make ${symbol.name} static"
                        put("title", title)
                        put("kind", "refactor.rewrite")
                        if (makeStatic.status == PatchStatus.REFUSED) {
                            put("disabled", buildJsonObject {
                                put("reason", makeStatic.summary.take(1024))
                            })
                        } else {
                            val projection = planToLspWorkspaceEdit(makeStatic, snap, retainPlan = false)
                            put("edit", buildJsonObject {
                                projection["changes"]?.let { put("changes", it) }
                                projection["documentChanges"]?.let { put("documentChanges", it) }
                            })
                            put("data", buildJsonObject {
                                put("operation", makeStatic.operation)
                                put("summary", makeStatic.summary)
                                put("riskLevel", makeStatic.riskLevel.name)
                                put("evidence", makeStatic.evidence.name)
                                put("warnings", buildJsonArray {
                                    makeStatic.warnings.forEach { add(JsonPrimitive(it)) }
                                })
                                put("editOwnership", "client-managed")
                                put("rollbackAvailable", false)
                            })
                        }
                    }
                }
                actions += buildJsonObject {
                    put("title", "RefactorKit: Safe delete ${symbol.name}")
                    put("kind", "refactor.remove")
                    put("command", commandJson("RefactorKit: Safe delete ${symbol.name}", "refactorkit.safeDelete", buildJsonObject {
                        put("symbol", symbol.id.value)
                    }))
                }
        }

        return JsonArray(actions)
    }

    private fun documentSymbol(params: JsonObject?): JsonElement {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return JsonNull
        if (isCDocument(fileUri)) return cEditorQuery(CCompilerEditorQuery.DOCUMENT_SYMBOLS, params)
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
        if (file.languageId in setOf("typescript", "javascript")) {
            val extension = file.path.fileName.toString().substringAfterLast('.', "")
            if (extension !in setOf("ts", "js")) return JsonArray(emptyList())
            return buildJsonArray {
                structuralAdapter.outline(file.content, file.languageId).forEach { item ->
                    add(buildJsonObject {
                        put("name", item.name)
                        put("kind", structuralLspSymbolKind(item.kind))
                        put("range", rangeJson(item.line, item.character, item.line, item.character + item.name.length))
                        put("selectionRange", rangeJson(item.line, item.character, item.line, item.character + item.name.length))
                    })
                }
            }
        }
        val index = adapter.buildSymbols(snap)
        val symbols = index.symbols.filter { it.location.path == file.path }
        return buildJsonArray {
            symbols.forEach { sym ->
                add(buildJsonObject {
                    put("name", sym.name)
                    put("kind", lspSymbolKind(sym.kind))
                    put("range", rangeJson(
                        sym.location.range.start.line, sym.location.range.start.character,
                        sym.location.range.end.line, sym.location.range.end.character,
                    ))
                    put("selectionRange", rangeJson(
                        sym.location.range.start.line, sym.location.range.start.character,
                        sym.location.range.end.line, sym.location.range.end.character,
                    ))
                })
            }
        }
    }

    private fun semanticTokensFull(params: JsonObject?): JsonElement {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return buildJsonObject { put("data", JsonArray(emptyList())) }
        val snap = snapshot ?: return buildJsonObject { put("data", JsonArray(emptyList())) }
        val file = fileForUri(snap, fileUri) ?: return buildJsonObject { put("data", JsonArray(emptyList())) }
        if (file.languageId != "java") return buildJsonObject { put("data", JsonArray(emptyList())) }
        val symbols = adapter.buildSymbols(snap).symbols
            .filter { it.location.path == file.path }
            .sortedWith(compareBy({ it.location.range.start.line }, { it.location.range.start.character }))

        var prevLine = 0
        var prevStart = 0
        val data = mutableListOf<Int>()
        symbols.forEach { sym ->
            val start = sym.location.range.start
            val end = sym.location.range.end
            val length = max(1, end.character - start.character)
            val deltaLine = start.line - prevLine
            val deltaStart = if (deltaLine == 0) start.character - prevStart else start.character
            data += deltaLine
            data += deltaStart
            data += length
            data += semanticTokenType(sym.kind)
            data += 0 // token modifiers bitset
            prevLine = start.line
            prevStart = start.character
        }

        return buildJsonObject {
            put("data", buildJsonArray { data.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun documentDiagnostic(params: JsonObject?): JsonElement {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return buildJsonObject {
            put("kind", "full")
            put("items", JsonArray(emptyList()))
        }
        val snap = snapshot ?: return buildJsonObject { put("kind", "full"); put("items", JsonArray(emptyList())) }
        val file = fileForUri(snap, fileUri) ?: return buildJsonObject { put("kind", "full"); put("items", JsonArray(emptyList())) }
        if (file.languageId == "c") throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "C diagnostics require an explicit clang toolchain and are managed RefactorKit commands (cli/daemon/mcp); the LSP does not report editor C diagnostics",
        )
        if (file.languageId != "java") return buildJsonObject { put("kind", "full"); put("items", JsonArray(emptyList())) }
        val items = diagnosticsForFile(snap, file.path)
        return buildJsonObject {
            put("kind", "full")
            put("items", JsonArray(items))
        }
    }

    private fun formatting(params: JsonObject?): JsonElement {
        val uri = params?.obj("textDocument")?.string("uri") ?: missing("textDocument.uri")
        val snap = snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val file = fileForUri(snap, uri)
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Java source not found: $uri")
        if (file.languageId == "c") throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "C formatting is a managed RefactorKit command; the LSP does not offer editor-applied C formatting",
        )
        if (file.languageId != "java") return JsonArray(emptyList())
        val plan = JavaFormatFilePlanner(adapter).preview(snap, file.path)
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
        }
        val modify = WorkspaceEditSimulator.normalize(plan.workspaceEdit).edits
            .filterIsInstance<FileEdit.Modify>()
            .singleOrNull { it.path.normalize() == file.path.normalize() }
            ?: return JsonArray(emptyList())
        return JsonArray(modify.textEdits.map { edit ->
            buildJsonObject {
                put("range", rangeJson(
                    edit.range.start.line,
                    edit.range.start.character,
                    edit.range.end.line,
                    edit.range.end.character,
                ))
                put("newText", edit.newText)
            }
        })
    }

    private fun executeCommand(params: JsonObject?): JsonElement {
        val command = params?.string("command") ?: missing("command")
        val args = (params?.get("arguments") as? JsonArray)?.let { arr ->
            (arr.firstOrNull() as? JsonObject)
        }
        val snap = snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val root = snap.workspace.root
        return when (command) {
            "refactorkit.renameClass" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val newName = args?.string("newName") ?: missing("newName")
                val plan = JavaRenameClassPlanner(adapter).preview(snap, symbol, newName)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.extractMethod" -> {
                val file = args?.string("file") ?: missing("file")
                val startLine = args.string("startLine")?.toIntOrNull() ?: missing("startLine")
                val endLine = args.string("endLine")?.toIntOrNull() ?: missing("endLine")
                val methodName = args.string("methodName") ?: missing("methodName")
                val plan = JavaExtractMethodPlanner().preview(snap, Paths.get(file), startLine, endLine, methodName)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.renameParameter", "refactorkit.renameParameter" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val oldName = args.string("oldName") ?: args.string("oldParameterName") ?: missing("oldName")
                val newName = args.string("newName") ?: args.string("newParameterName") ?: missing("newName")
                val plan = JavaChangeSignaturePlanner(adapter).previewRenameParameter(snap, symbol, oldName, newName)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                val proposal = planToLspWorkspaceEdit(plan, snap, retainPlan = false)
                JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
            }
            "refactorkit.changeSignature.addParameter" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val type = args.string("type") ?: args.string("parameterType") ?: missing("type")
                val name = args.string("name") ?: args.string("parameterName") ?: missing("name")
                val default = args.string("default") ?: args.string("defaultExpression") ?: missing("default")
                val plan = JavaChangeSignaturePlanner(adapter).previewAddParameter(snap, symbol, type, name, default)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.reorderParameters" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val order = args.string("order") ?: missing("order")
                val plan = JavaChangeSignaturePlanner(adapter).previewReorderParameters(snap, symbol, order.split(','))
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.removeParameter" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val name = args.string("name") ?: args.string("parameterName") ?: missing("name")
                val plan = JavaChangeSignaturePlanner(adapter).previewRemoveParameter(snap, symbol, name)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeType" -> executeChangeType(args, snap)
            "refactorkit.changeVisibility" -> executeChangeVisibility(args, snap)
            "refactorkit.extractVariable" -> executeExtractVariable(args, snap)
            "refactorkit.extractField" -> executeExtractField(args, snap)
            "refactorkit.extractConstant" -> executeExtractConstant(args, snap)
            "refactorkit.extractInterface" -> executeExtractInterface(args, snap)
            "refactorkit.extractParameter" -> executeExtractParameter(args, snap)
            "refactorkit.extractSuperclass" -> executeExtractSuperclass(args, snap)
            "refactorkit.encapsulateField" -> executeEncapsulateField(args, snap)
            "refactorkit.moveClass" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val pkg = args?.string("targetPackage") ?: missing("targetPackage")
                when (val outcome = moveClassDispatcher.preview(
                    snap,
                    symbol,
                    pkg,
                    moveClassPromotionAttempt(args),
                )) {
                    is JavaMoveClassOperationOutcome.Plan -> planToLspWorkspaceEdit(outcome.preview.plan, snap)
                    is JavaMoveClassOperationOutcome.Guidance -> buildJsonObject {
                        put("resultType", outcome.guidance.resultType.name)
                        put("schemaVersion", outcome.guidance.schemaVersion)
                        put("managedWriteEligibility", "INELIGIBLE")
                        put("blockerCode", "guidance.nonManaged")
                    }
                    is JavaMoveClassOperationOutcome.LexicalReview ->
                        JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(outcome.envelope)
                }
            }
            "refactorkit.organizeImports" -> {
                val file = args?.string("file") ?: missing("file")
                val plan = JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(file))
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.formatFile" -> {
                val file = args?.string("file") ?: missing("file")
                val plan = JavaFormatFilePlanner(adapter).preview(snap, Paths.get(file))
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.renameMember" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val newName = args?.string("newName") ?: missing("newName")
                val plan = JavaRenameMemberPlanner(adapter).preview(snap, symbol, newName)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.safeDelete" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val plan = JavaSafeDeletePlanner(adapter).preview(snap, symbol)
                if (plan.status == PatchStatus.REFUSED || plan.evidence == RefactoringEvidence.LEXICAL_FALLBACK) {
                    throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                }
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.moveSourceRoot" -> {
                val from = args?.string("from") ?: missing("from")
                val to = args?.string("to") ?: missing("to")
                val plan = JavaMoveSourceRootPlanner(adapter).preview(snap, Paths.get(from), Paths.get(to))
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.moveAcrossMavenModules" -> {
                val from = args?.string("from") ?: missing("from")
                val to = args?.string("to") ?: missing("to")
                val plan = JavaMoveAcrossMavenModulesPlanner(adapter).preview(snap, Paths.get(from), Paths.get(to), emptyList())
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.createMavenModule" -> {
                val moduleName = args?.string("moduleName") ?: missing("moduleName")
                val parentPom = args?.string("parentPom") ?: missing("parentPom")
                val plan = JavaCreateMavenModulePlanner().preview(snap, moduleName, Paths.get(parentPom))
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.renameMavenModule" -> {
                val oldModuleDir = args?.string("oldModuleDir") ?: missing("oldModuleDir")
                val newModuleDir = args?.string("newModuleDir") ?: missing("newModuleDir")
                val newArtifactId = args?.string("newArtifactId")
                val plan = JavaRenameMavenModulePlanner().preview(snap, oldModuleDir, newModuleDir, newArtifactId)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.c.preview" -> cManagedPreview(args, root)
            "refactorkit.applyPlan" -> {
                val values = args ?: JsonObject(emptyMap())
                val planId = when (val identity = RefactoringApplyIdentity.parse(values)) {
                    is RefactoringApplyIdentity.OperationCorrelation -> {
                        val operationId = identity.operationId
                        val envelope = moveClassDispatcher.findLexicalReview(operationId)
                            ?: throw JsonRpcException(
                                JsonRpcErrorCodes.INVALID_PARAMS,
                                "Operation not found: $operationId",
                            )
                        throw JsonRpcException(
                            JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
                            envelope.blockerCode,
                            JavaMoveClassLexicalFallbackReviewJsonProjection.refusalData(envelope),
                        )
                    }
                    is RefactoringApplyIdentity.ManagedPlan -> identity.planId
                }
                val cPending = pendingCPlans.lookup(planId)
                val plan = pendingPlans.lookup(planId) ?: cPending?.plan
                    ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: ${planId.value}")
                requireManagedWriteSafe(plan.affectedFiles)
                val cGateOwner = cPending?.let { CRefactoringFacade(it.toolchain) }
                try {
                    val scannedSnapshot = scanner.scan(root)
                    val current = if (cPending == null) scannedSnapshot else cProjectSnapshot(scannedSnapshot, root)
                    val diagnosticsGate = if (cPending != null) {
                        requireNotNull(cGateOwner).diagnosticsGate(cPending.snapshot, plan)
                    } else {
                        ManagedApplyDiagnosticsGateSelector.select(
                            plan = plan,
                            languageId = "java",
                            javaAdapter = adapter,
                            kotlinAdapter = kotlinAdapter,
                            externalGateResolver = { languageId ->
                                throw JsonRpcException(
                                    JsonRpcErrorCodes.INVALID_PARAMS,
                                    "Managed apply diagnostics are unavailable for language: $languageId",
                                )
                            },
                        )
                    }
                    when (val result = PatchEngine(root).apply(
                        plan,
                        current,
                        ApplyAuthorization.explicit("lsp-managed-command"),
                        diagnosticsGate,
                    )) {
                        is ApplyResult.Applied -> {
                            if (cPending == null) pendingPlans.remove(planId) else pendingCPlans.remove(planId)
                            refreshSnapshot()
                            buildJsonObject { put("transactionId", result.transaction.id.value) }
                        }
                        is ApplyResult.Refused -> throw JsonRpcException(
                            JsonRpcErrorCodes.applyRefusalCode(result.diagnostics),
                            result.diagnostics.joinToString("; ") { it.message },
                        )
                    }
                } finally {
                    cGateOwner?.close()
                }
            }
            "refactorkit.rollback" -> {
                val transactionId = args?.string("transactionId") ?: missing("transactionId")
                val mode = if (args?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
                    RollbackMode.FORCE
                } else RollbackMode.NORMAL
                val log = TransactionLog(root.resolve(".refactorkit/transactions"))
                val outcome = ManagedRollbackExecutor(log, PatchEngine(root)).execute(
                    transactionId,
                    RollbackLookupVisibility.APPLIED_ONLY,
                    mode,
                    RollbackPreflightGuard<JsonRpcException> { record ->
                        try {
                            requireManagedWriteSafe(record.transaction.rollbackEdit.affectedFiles())
                            RollbackPreflightDecision.Allow
                        } catch (rejection: JsonRpcException) {
                            RollbackPreflightDecision.Reject(rejection)
                        }
                    },
                )
                when (outcome) {
                    is ManagedRollbackOutcome.InvalidTransactionId ->
                        throw JsonRpcException(
                            JsonRpcErrorCodes.INVALID_PARAMS,
                            "Invalid transaction ID: $transactionId",
                        )
                    is ManagedRollbackOutcome.TransactionNotFound ->
                        throw JsonRpcException(
                            JsonRpcErrorCodes.INVALID_PARAMS,
                            "Transaction not found: $transactionId",
                        )
                    is ManagedRollbackOutcome.JournalLookupFailed -> throw outcome.failure
                    is ManagedRollbackOutcome.PreflightRejected -> throw outcome.surfaceRejection
                    is ManagedRollbackOutcome.RollbackCallJournalFailed -> throw outcome.failure
                    is ManagedRollbackOutcome.RolledBack -> {
                        refreshSnapshot()
                        buildJsonObject {
                            put("status", "rolledBack")
                            put("transactionId", transactionId)
                        }
                    }
                    is ManagedRollbackOutcome.Refused -> {
                        throw JsonRpcException(
                            JsonRpcErrorCodes.rollbackRefusalCode(outcome.diagnostics),
                            "Rollback refused: ${outcome.diagnostics.joinToString("; ") { it.message }}",
                        )
                    }
                }
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown command: $command")
        }
    }

    private fun executeChangeType(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeType arguments are required")
        if (!supportsDocumentChanges) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java changeType requires versioned documentChanges support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeType file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeType file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target)) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeType source is not a saved workspace file")
        }
        if (!target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeType source resolves outside the workspace")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.typeRepair.documentNotOpen", "changeType requires one open, versioned Java document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.typeRepair.snapshotStale", "Workspace inputs changed; refresh the Java project before requesting type repair")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.typeRepair.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val line = commandArgs.obj("position")?.int("line") ?: missing("position.line")
        val character = commandArgs.obj("position")?.int("character") ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")
        val newType = commandArgs.string("newType")?.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: missing("newType")

        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val scan = { scanner.scan(root) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.typeRepair.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val contextRequest = JavaProjectContextRequest(savedSnapshot.hash, sdkHome)
        val selectionResult = javaEditorOverlaySession.query(
            contextRequest,
            JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION, path, SourcePosition(line, character), 1),
            scan,
            overlayFactory,
        )
        if (selectionResult is JavaEditorQueryResult.Refused) {
            throw typeRepairRefusal(selectionResult.code, selectionResult.message)
        }
        val available = selectionResult as JavaEditorQueryResult.Available
        val selected = available.payload as? JavaEditorQueryPayload.MutationSelection
            ?: throw typeRepairRefusal("java.typeRepair.selectionUnavailable", "JDT did not return an exact Java field selection")
        val selection = try {
            JavaMutationSelectionService.validate(
                selected.boundContext,
                selected.value.selection,
                "changeType",
                selected.value.selection.symbolId,
                mapOf("file" to path.toString(), "newType" to newType),
            )
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        }
        val plan = org.refactorkit.java.JavaTypeRepairPlanner(adapter).preview(
            selected.boundContext.snapshot,
            selection.symbolId,
            selection.path,
            selection.bindingKey,
            newType,
        )
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.typeRepair.previewRefused", plan.summary)
        }
        available.context.verifyAfterQuery(scan)?.let { throw typeRepairRefusal(it.code, it.message) }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the type-repair proposal was being planned")
        }
        val edit = WorkspaceEditSimulator.normalize(plan.workspaceEdit).edits.singleOrNull() as? FileEdit.Modify
        if (edit == null || edit.path.normalize() != path || edit.textEdits.size != 1) {
            throw typeRepairRefusal(
                "java.typeRepair.workspaceEditShapeUnsupported",
                "The bounded field repair must produce exactly one text edit in the selected open document",
            )
        }
        val overlaySource = available.overlay.document(path)?.content
            ?: throw typeRepairRefusal("java.overlay.selectionMissing", "The selected Java document is absent from the immutable editor overlay")
        try {
            TextEdits.apply(overlaySource, edit.textEdits)
        } catch (_: RuntimeException) {
            throw typeRepairRefusal("java.overlay.editInvalid", "The proposed type edit is not valid against the current editor buffer")
        }
        val proposal = planToLspWorkspaceEdit(plan, savedSnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun executeChangeVisibility(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeVisibility arguments are required")
        if (!supportsDocumentChanges) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java changeVisibility requires versioned documentChanges support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeVisibility file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeVisibility file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "changeVisibility source is not a saved workspace file")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.changeVisibility.documentNotOpen", "changeVisibility requires one open, versioned Java declaration document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.changeVisibility.snapshotStale", "Workspace inputs changed; refresh the Java project before changing method visibility")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.changeVisibility.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val line = commandArgs.obj("position")?.int("line") ?: missing("position.line")
        val character = commandArgs.obj("position")?.int("character") ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")
        val visibility = commandArgs.string("visibility") ?: missing("visibility")

        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val scan = { scanner.scan(root) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.changeVisibility.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val selectionResult = javaEditorOverlaySession.query(
            JavaProjectContextRequest(savedSnapshot.hash, sdkHome),
            JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION, path, SourcePosition(line, character), 1),
            scan,
            overlayFactory,
        )
        if (selectionResult is JavaEditorQueryResult.Refused) {
            throw typeRepairRefusal(selectionResult.code, selectionResult.message)
        }
        val available = selectionResult as JavaEditorQueryResult.Available
        val selected = available.payload as? JavaEditorQueryPayload.MutationSelection
            ?: throw typeRepairRefusal("java.changeVisibility.selectionUnavailable", "JDT did not return an exact Java method selection")
        val selection = try {
            JavaMutationSelectionService.validate(
                selected.boundContext,
                selected.value.selection,
                "changeVisibility",
                selected.value.selection.symbolId,
                mapOf("file" to path.toString()),
            )
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        }
        val overlaySnapshot = selected.boundContext.snapshot
        val plan = JavaChangeVisibilityPlanner(adapter).preview(overlaySnapshot, selection.symbolId, visibility)
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.changeVisibility.previewRefused", plan.summary)
        }
        if (plan.snapshotHash != overlaySnapshot.hash) {
            throw typeRepairRefusal("java.changeVisibility.snapshotMismatch", "The visibility proposal is not bound to the current editor overlay")
        }
        available.context.verifyAfterQuery(scan)?.let { throw typeRepairRefusal(it.code, it.message) }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the changeVisibility proposal was being planned")
        }
        val proposal = planToLspWorkspaceEdit(plan, overlaySnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun executeExtractSuperclass(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractSuperclass arguments are required")
        if (!supportsDocumentChanges || !supportsCreateFile) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java extractSuperclass requires versioned documentChanges and CreateFile resource-operation support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractSuperclass file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractSuperclass file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractSuperclass source is not a saved workspace file")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.extractSuperclass.documentNotOpen", "extractSuperclass requires one open, versioned Java declaration document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.extractSuperclass.snapshotStale", "Workspace inputs changed; refresh the Java project before requesting superclass extraction")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.extractSuperclass.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val line = commandArgs.obj("position")?.int("line") ?: missing("position.line")
        val character = commandArgs.obj("position")?.int("character") ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")
        val superclassName = commandArgs.string("superclassName") ?: missing("superclassName")
        val methodName = commandArgs.string("methodName") ?: missing("methodName")

        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val scan = { scanner.scan(root) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.extractSuperclass.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val selectionResult = javaEditorOverlaySession.query(
            JavaProjectContextRequest(savedSnapshot.hash, sdkHome),
            JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION, path, SourcePosition(line, character), 1),
            scan,
            overlayFactory,
        )
        if (selectionResult is JavaEditorQueryResult.Refused) {
            throw typeRepairRefusal(selectionResult.code, selectionResult.message)
        }
        val available = selectionResult as JavaEditorQueryResult.Available
        val selected = available.payload as? JavaEditorQueryPayload.MutationSelection
            ?: throw typeRepairRefusal("java.extractSuperclass.selectionUnavailable", "JDT did not return an exact Java type selection")
        val selection = try {
            JavaMutationSelectionService.validate(
                selected.boundContext,
                selected.value.selection,
                "extractSuperclass",
                selected.value.selection.symbolId,
                mapOf("file" to path.toString()),
            )
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        }
        val overlaySnapshot = selected.boundContext.snapshot
        val plan = JavaExtractSuperclassPlanner(adapter).preview(
            overlaySnapshot,
            JavaExtractSuperclassRequest(path, selection.symbolId, superclassName, methodName),
        )
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.extractSuperclass.previewRefused", plan.summary)
        }
        if (plan.snapshotHash != overlaySnapshot.hash) {
            throw typeRepairRefusal("java.extractSuperclass.snapshotMismatch", "The superclass proposal is not bound to the current editor overlay")
        }
        available.context.verifyAfterQuery(scan)?.let { throw typeRepairRefusal(it.code, it.message) }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the extractSuperclass proposal was being planned")
        }
        val proposal = planToLspWorkspaceEdit(plan, overlaySnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun executeExtractInterface(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractInterface arguments are required")
        if (!supportsDocumentChanges || !supportsCreateFile) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java extractInterface requires versioned documentChanges and CreateFile resource-operation support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractInterface file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractInterface file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "extractInterface source is not a saved workspace file")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.extractInterface.documentNotOpen", "extractInterface requires one open, versioned Java declaration document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.extractInterface.snapshotStale", "Workspace inputs changed; refresh the Java project before requesting interface extraction")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.extractInterface.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val line = commandArgs.obj("position")?.int("line") ?: missing("position.line")
        val character = commandArgs.obj("position")?.int("character") ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")
        val interfaceName = commandArgs.string("interfaceName") ?: missing("interfaceName")
        val methodName = commandArgs.string("methodName") ?: missing("methodName")

        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val scan = { scanner.scan(root) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.extractInterface.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val selectionResult = javaEditorOverlaySession.query(
            JavaProjectContextRequest(savedSnapshot.hash, sdkHome),
            JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION, path, SourcePosition(line, character), 1),
            scan,
            overlayFactory,
        )
        if (selectionResult is JavaEditorQueryResult.Refused) {
            throw typeRepairRefusal(selectionResult.code, selectionResult.message)
        }
        val available = selectionResult as JavaEditorQueryResult.Available
        val selected = available.payload as? JavaEditorQueryPayload.MutationSelection
            ?: throw typeRepairRefusal("java.extractInterface.selectionUnavailable", "JDT did not return an exact Java type selection")
        val selection = try {
            JavaMutationSelectionService.validate(
                selected.boundContext,
                selected.value.selection,
                "extractInterface",
                selected.value.selection.symbolId,
                mapOf("file" to path.toString()),
            )
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        }
        val overlaySnapshot = selected.boundContext.snapshot
        val plan = JavaExtractInterfacePlanner(adapter).preview(
            overlaySnapshot,
            JavaExtractInterfaceRequest(path, selection.symbolId, interfaceName, methodName),
        )
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.extractInterface.previewRefused", plan.summary)
        }
        if (plan.snapshotHash != overlaySnapshot.hash) {
            throw typeRepairRefusal("java.extractInterface.snapshotMismatch", "The interface proposal is not bound to the current editor overlay")
        }
        available.context.verifyAfterQuery(scan)?.let { throw typeRepairRefusal(it.code, it.message) }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the extractInterface proposal was being planned")
        }
        val proposal = planToLspWorkspaceEdit(plan, overlaySnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun executeEncapsulateField(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "encapsulateField arguments are required")
        if (!supportsDocumentChanges) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java encapsulateField requires versioned documentChanges support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "encapsulateField file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "encapsulateField file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "encapsulateField source is not a saved workspace file")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.encapsulateField.documentNotOpen", "encapsulateField requires an open, versioned Java declaration document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.encapsulateField.snapshotStale", "Workspace inputs changed; refresh the Java project before requesting field encapsulation")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.encapsulateField.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val line = commandArgs.obj("position")?.int("line") ?: missing("position.line")
        val character = commandArgs.obj("position")?.int("character") ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")

        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val scan = { scanner.scan(root) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.encapsulateField.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val contextRequest = JavaProjectContextRequest(savedSnapshot.hash, sdkHome)
        val selectionResult = javaEditorOverlaySession.query(
            contextRequest,
            JavaEditorQueryRequest(JavaEditorQueryKind.MUTATION_SELECTION, path, SourcePosition(line, character), 1),
            scan,
            overlayFactory,
        )
        if (selectionResult is JavaEditorQueryResult.Refused) {
            throw typeRepairRefusal(selectionResult.code, selectionResult.message)
        }
        val available = selectionResult as JavaEditorQueryResult.Available
        val selected = available.payload as? JavaEditorQueryPayload.MutationSelection
            ?: throw typeRepairRefusal("java.encapsulateField.selectionUnavailable", "JDT did not return an exact Java field selection")
        val selection = try {
            JavaMutationSelectionService.validate(
                selected.boundContext,
                selected.value.selection,
                "encapsulateField",
                selected.value.selection.symbolId,
                mapOf("file" to path.toString()),
            )
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        }
        val overlaySnapshot = selected.boundContext.snapshot
        val plan = JavaEncapsulateFieldPlanner(adapter).preview(overlaySnapshot, SymbolId(selection.symbolId))
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.encapsulateField.previewRefused", plan.summary)
        }
        if (plan.snapshotHash != overlaySnapshot.hash) {
            throw typeRepairRefusal("java.encapsulateField.snapshotMismatch", "The encapsulation proposal is not bound to the current editor overlay")
        }
        available.context.verifyAfterQuery(scan)?.let { throw typeRepairRefusal(it.code, it.message) }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the encapsulateField proposal was being planned")
        }
        val proposal = planToLspWorkspaceEdit(plan, savedSnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun executeExtractVariable(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject =
        executeJavaExpressionExtraction(args, sessionSnapshot, "extractVariable") { staged, path, selection, name ->
            JavaExtractVariablePlanner(adapter).preview(staged, JavaExtractVariableRequest(path, selection, name))
        }

    private fun executeExtractField(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject =
        executeJavaExpressionExtraction(args, sessionSnapshot, "extractField") { staged, path, selection, name ->
            org.refactorkit.java.JavaExtractConstantPlanner(adapter).previewInstanceField(
                staged,
                org.refactorkit.java.JavaExtractConstantRequest(path, selection, name),
            )
        }

    private fun executeExtractConstant(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject =
        executeJavaExpressionExtraction(args, sessionSnapshot, "extractConstant") { staged, path, selection, name ->
            org.refactorkit.java.JavaExtractConstantPlanner(adapter).preview(
                staged,
                org.refactorkit.java.JavaExtractConstantRequest(path, selection, name),
            )
        }

    private fun executeExtractParameter(args: JsonObject?, sessionSnapshot: ProjectSnapshot): JsonObject =
        executeJavaExpressionExtraction(
            args,
            sessionSnapshot,
            "extractParameter",
            expectedTextEditCount = 3,
        ) { staged, path, selection, name ->
            JavaExtractParameterPlanner(adapter).preview(
                staged,
                JavaExtractParameterRequest(path, selection, name),
            )
        }

    private fun executeJavaExpressionExtraction(
        args: JsonObject?,
        sessionSnapshot: ProjectSnapshot,
        operation: String,
        expectedTextEditCount: Int = 2,
        planner: (ProjectSnapshot, Path, SourceRange, String) -> PatchPlan,
    ): JsonObject {
        val commandArgs = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$operation arguments are required")
        if (!supportsDocumentChanges) throw JsonRpcException(
            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
            "Java $operation requires versioned documentChanges support",
        )
        val root = sessionSnapshot.workspace.root.toAbsolutePath().normalize()
        val rawPath = commandArgs.string("file") ?: missing("file")
        val requestedPath = try { Paths.get(rawPath) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$operation file must be a workspace-relative Java path")
        }
        val path = requestedPath.normalize()
        if (requestedPath.isAbsolute || path.toString().isBlank() || path.startsWith(Paths.get(".."))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$operation file must stay inside the workspace")
        }
        val target = root.resolve(path).normalize()
        if (!target.startsWith(root) || !Files.isRegularFile(target) || !target.toRealPath().startsWith(root.toRealPath())) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$operation source is not a saved workspace file")
        }
        openDocuments.values.singleOrNull { it.path.normalize() == path }
            ?: throw typeRepairRefusal("java.$operation.documentNotOpen", "$operation requires one open, versioned Java document")
        val savedSnapshot = scanner.scan(root)
        if (overlayOpenDocuments(savedSnapshot).hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.$operation.snapshotStale", "Workspace inputs changed; refresh the Java project before requesting extraction")
        }
        savedSnapshot.files.singleOrNull { it.path.normalize() == path && it.languageId == "java" }
            ?: throw typeRepairRefusal("java.$operation.selectionUnavailable", "The selected source is not a saved Java file")
        val sdkHome = try { Paths.get(commandArgs.string("sdkHome") ?: missing("sdkHome")) } catch (_: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        }
        if (!sdkHome.isAbsolute) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sdkHome must be an absolute configured JDK path")
        val rangeObject = commandArgs.obj("range") ?: missing("range")
        fun position(field: String): SourcePosition {
            val value = rangeObject.obj(field) ?: missing("range.$field")
            val line = value.int("line") ?: missing("range.$field.line")
            val character = value.int("character") ?: missing("range.$field.character")
            if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection positions must be non-negative")
            return SourcePosition(line, character)
        }
        val selection = SourceRange(position("start"), position("end"))
        val name = commandArgs.string("name")?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: missing("name")
        val editorDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        val overlayFactory: (ProjectSnapshot) -> ImmutableEditorOverlay = { base ->
            try {
                ImmutableEditorOverlay.create(base, editorDocuments, expectedLanguageId = "java")
            } catch (failure: Exception) {
                throw org.refactorkit.java.JavaContextAuthorityException(
                    "java.$operation.overlayUnavailable",
                    failure.message ?: "Open Java documents do not form a valid immutable editor overlay",
                )
            }
        }
        val scan = { scanner.scan(root) }
        val read = try {
            javaEditorOverlaySession.read(
                JavaProjectContextRequest(savedSnapshot.hash, sdkHome),
                path,
                ImmutableEditorOverlay.computeHash(editorDocuments),
                scan,
                overlayFactory,
                org.refactorkit.core.SemanticCancellationToken.NONE,
            ) { _, _, staged -> planner(staged, path, selection, name) }
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw typeRepairRefusal(failure.code, failure.message.orEmpty())
        } catch (_: Exception) {
            throw typeRepairRefusal("java.overlay.analysisUnavailable", "Java editor overlay analysis could not be completed")
        }
        if (read is JavaEditorReadResult.Refused) throw typeRepairRefusal(read.code, read.message)
        val available = read as JavaEditorReadResult.Available
        val plan = available.value
        if (plan.status == PatchStatus.REFUSED) {
            throw typeRepairRefusal(plan.refusalCode ?: "java.$operation.previewRefused", plan.summary)
        }
        val currentJavaDocuments = openDocuments.values
            .filter { it.path.fileName.toString().endsWith(".java", ignoreCase = true) }
            .map { ImmutableEditorOverlayDocument(it.path.normalize(), it.version.toLong(), it.content) }
            .sortedBy { ProtocolPath.serialize(it.path) }
        if (currentJavaDocuments != editorDocuments || snapshot?.hash != sessionSnapshot.hash) {
            throw typeRepairRefusal("java.overlay.versionStale", "An editor document changed while the $operation proposal was being planned")
        }
        val edit = WorkspaceEditSimulator.normalize(plan.workspaceEdit).edits.singleOrNull() as? FileEdit.Modify
        if (edit == null || edit.path.normalize() != path || edit.textEdits.size != expectedTextEditCount) {
            throw typeRepairRefusal(
                "java.$operation.workspaceEditShapeUnsupported",
                "The bounded $operation plan must produce $expectedTextEditCount text edits in the selected open document",
            )
        }
        val overlaySource = available.overlay.document(path)?.content
            ?: throw typeRepairRefusal("java.overlay.selectionMissing", "The selected Java document is absent from the immutable editor overlay")
        try {
            TextEdits.apply(overlaySource, edit.textEdits)
        } catch (_: RuntimeException) {
            throw typeRepairRefusal("java.overlay.editInvalid", "The proposed $operation edit is not valid against the current editor buffer")
        }
        val proposal = planToLspWorkspaceEdit(plan, savedSnapshot, retainPlan = false)
        return JsonObject(proposal - "refactorkitPlanId" + ("refactorkitManagedApplyAvailable" to JsonPrimitive(false)))
    }

    private fun typeRepairRefusal(code: String, message: String) = JsonRpcException(
        JsonRpcErrorCodes.PLAN_REFUSED,
        message,
        buildJsonObject { put("refusalCode", code) },
    )

    private fun cManagedPreview(args: JsonObject?, root: Path): JsonObject {
        val values = args ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C preview arguments are required")
        val operation = values.string("operation")
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing C operation")
        val rawArguments = values.obj("arguments") ?: JsonObject(emptyMap())
        val operationArguments = rawArguments.mapValues { (name, value) ->
            val primitive = value as? JsonPrimitive
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "C argument '$name' must be a scalar")
            primitive.content
        }
        val baseSnapshot = snapshot ?: scanner.scan(root)
        val cSnapshot = cProjectSnapshot(baseSnapshot, root)
        val toolchain = discoverCToolchain(root, values)
        val facade = CRefactoringFacade(toolchain)
        try {
            val plan = facade.previewForManagedApply(cSnapshot, operation, operationArguments)
            if (plan.status == PatchStatus.REFUSED) {
                throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED,
                    plan.summary,
                    buildJsonObject { plan.refusalCode?.let { put("refusalCode", it) } },
                )
            }
            pendingCPlans.insert(plan.id, PendingCPlan(plan, cSnapshot, toolchain))
            return cPlanPreviewJson(plan, cSnapshot)
        } finally {
            facade.close()
        }
    }

    private fun cProjectSnapshot(base: ProjectSnapshot, root: Path): ProjectSnapshot {
        val cSnapshot = try {
            CProjectScanner().scan(root)
        } catch (failure: CProjectScanException) {
            throw JsonRpcException(
                JsonRpcErrorCodes.PLAN_REFUSED,
                failure.message ?: "C source capture is unavailable",
                buildJsonObject {
                    put("refusalCode", "c.sourceCapture.unavailable")
                    put("sourceCaptureFailure", failure.code.name)
                },
            )
        }
        val cPaths = cSnapshot.files.mapTo(mutableSetOf()) { it.path.normalize() }
        val sources = (base.files.filterNot { it.path.normalize() in cPaths } + cSnapshot.files)
            .distinctBy { it.path.normalize() }
            .sortedBy { it.path.toString() }
        val auxiliaryByPath = linkedMapOf<Path, SourceFile>()
        base.auxiliaryFiles.filterNot { it.path.normalize() in cPaths }.forEach {
            auxiliaryByPath[it.path.normalize()] = it
        }
        cSnapshot.auxiliaryFiles.forEach { auxiliaryByPath[it.path.normalize()] = it }
        val auxiliary = auxiliaryByPath.values.filterNot { source ->
            sources.any { it.path.normalize() == source.path.normalize() }
        }.sortedBy { it.path.toString() }
        return base.copy(
            files = sources,
            sourceExtensions = ProjectSnapshot.inferSourceExtensions(sources),
            auxiliaryFiles = auxiliary,
        )
    }

    private fun discoverCToolchain(root: Path, args: JsonObject): ClangSemanticToolchain {
        val request = ClangToolchainRequest(
            workspaceRoot = root,
            clangExecutable = args.string("clang")?.let(Paths::get),
            clangdExecutable = args.string("clangd")?.let(Paths::get),
            clangFormatExecutable = args.string("clangFormat")?.let(Paths::get),
            readOnlySdks = CReadOnlySdkRequestJson.decode(args["readOnlySdks"]),
        )
        return when (val discovery = ClangToolchainDiscoverer().discover(request)) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                discovery.diagnostics.joinToString("; ") { it.message },
            )
        }
    }

    private fun cPlanPreviewJson(plan: PatchPlan, snap: ProjectSnapshot): JsonObject {
        val renderable = snap.copy(files = snap.trackedFiles, auxiliaryFiles = emptyList())
        val diff = PatchDiffRenderer.render(renderable, plan.workspaceEdit)
        return buildJsonObject {
            put("planId", plan.id.value)
            put("operation", plan.operation)
            put("status", plan.status.name)
            put("summary", plan.summary)
            put("confidence", plan.confidence)
            put("riskLevel", plan.riskLevel.name)
            put("evidence", plan.evidence.name)
            put("affectedFiles", buildJsonArray {
                plan.affectedFiles.sortedBy(ProtocolPath::serialize).forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
            })
            put("renderedDiff", diff.renderedDiff)
            put("diffTruncated", diff.truncated)
            put("warnings", buildJsonArray { plan.warnings.forEach { add(JsonPrimitive(it)) } })
            putCPreviewDiagnostics("diagnosticsBefore", plan.diagnosticsBefore, snap)
            putCPreviewDiagnostics("diagnosticsAfterPreview", plan.diagnosticsAfterPreview, snap)
        }
    }

    private fun JsonObjectBuilder.putCPreviewDiagnostics(
        key: String,
        diagnostics: List<Diagnostic>,
        snapshot: ProjectSnapshot,
    ) {
        if (diagnostics.isEmpty()) return
        val items = mutableListOf<JsonObject>()
        var remainingBytes = ProtocolLimits.MAX_PREVIEW_DIAGNOSTIC_BYTES - 2
        for (diagnostic in diagnostics.take(ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS)) {
            val lspDiagnostic = diagnosticJson(diagnostic)
            val item = diagnostic.location?.let {
                JsonObject(lspDiagnostic + ("uri" to JsonPrimitive(uriForPath(snapshot, it.path))))
            } ?: lspDiagnostic
            val itemBytes = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (itemBytes > remainingBytes) break
            items += item
            remainingBytes -= itemBytes
        }
        put(key, JsonArray(items))
        put("${key}Truncated", items.size < diagnostics.size)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun refreshSnapshot() {
        rootUri?.let { refreshSnapshotFromUri(it) }
    }

    private fun refreshSnapshotFromUri(uri: String) {
        try {
            val path = Paths.get(URI(uri))
            val current = snapshot
            if (current == null) {
                snapshot = overlayOpenDocuments(scanner.scan(path))
            } else when (val refresh = WorkspaceRefreshCoordinator.refresh(current) {
                overlayOpenDocuments(scanner.scan(path))
            }) {
                is WorkspaceRefreshResult.Unchanged -> snapshot = refresh.snapshot
                is WorkspaceRefreshResult.Changed -> {
                    moveClassDispatcher.clearLexicalReviewAudit()
                    snapshot = refresh.snapshot
                }
            }
            publishDiagnostics()
        } catch (e: Exception) {
            System.err.println("RefactorKit LSP: failed to scan workspace: ${e.message}")
        }
    }

    private fun publishDiagnostics() {
        val snap = snapshot ?: return
        val diagnosticsByPath = adapter.diagnostics(snap)
            .groupBy { it.location?.path }
        snap.files.filter { it.languageId == "java" }.forEach { file ->
            val uri = snap.workspace.root.resolve(file.path).toAbsolutePath().toUri().toString()
            val diagnostics = diagnosticsByPath[file.path].orEmpty().map { diagnosticJson(it) }
            onNotification("textDocument/publishDiagnostics", buildJsonObject {
                put("uri", uri)
                put("diagnostics", JsonArray(diagnostics))
            })
        }
    }

    private fun extractPosition(params: JsonObject?): Triple<String, Int, Int>? {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return null
        val pos = params.obj("position") ?: return null
        val line = pos["line"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val char = pos["character"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        return Triple(fileUri, line, char)
    }

    private fun pointLocation(path: Path, line: Int, character: Int): SourceLocation {
        val position = SourcePosition(line, character)
        return SourceLocation(path, SourceRange(position, position))
    }

    private fun wordAt(content: String, line: Int, character: Int): String? {
        val lines = content.lines()
        if (line >= lines.size) return null
        val lineContent = lines[line]
        if (character > lineContent.length) return null
        var start = character
        var end = character
        while (start > 0 && JavaLexer.isIdentChar(lineContent[start - 1])) start--
        while (end < lineContent.length && JavaLexer.isIdentChar(lineContent[end])) end++
        return if (start < end) lineContent.substring(start, end) else null
    }

    private fun planToLspWorkspaceEdit(
        plan: PatchPlan,
        snap: ProjectSnapshot,
        retainPlan: Boolean = true,
    ): JsonObject {
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
        }
        val normalizedEdit = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        val structural = normalizedEdit.edits.any { it !is FileEdit.Modify }
        val openPaths = openDocuments.values.map { it.path.normalize() }.toSet()
        val modifiesOpenDocument = normalizedEdit.edits.filterIsInstance<FileEdit.Modify>()
            .any { it.path.normalize() in openPaths }
        if (!supportsDocumentChanges && (structural || modifiesOpenDocument)) {
            throw JsonRpcException(
                JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                "LSP client must support versioned documentChanges for structural or open-document edits",
            )
        }
        if (normalizedEdit.edits.any { it is FileEdit.Create } && !supportsCreateFile) {
            throw JsonRpcException(
                JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                "LSP client must support CreateFile resource operations for file-creating edits",
            )
        }
        if (retainPlan) pendingPlans.insert(plan.id, plan)
        val changes = mutableMapOf<String, MutableList<JsonObject>>()
        val documentChanges = mutableListOf<JsonObject>()
        for (edit in normalizedEdit.edits) {
            when (edit) {
                is FileEdit.Modify -> {
                    val uri = uriForPath(snap, edit.path)
                    val edits = edit.textEdits.map { te ->
                        buildJsonObject {
                            put("range", rangeJson(te.range.start.line, te.range.start.character,
                                te.range.end.line, te.range.end.character))
                            put("newText", te.newText)
                        }
                    }
                    changes.getOrPut(uri) { mutableListOf() }.addAll(edits)
                    documentChanges += buildJsonObject {
                        put("textDocument", buildJsonObject {
                            put("uri", uri)
                            openDocuments.values.firstOrNull { it.path.normalize() == edit.path.normalize() }
                                ?.let { put("version", it.version) } ?: put("version", JsonNull)
                        })
                        put("edits", JsonArray(edits))
                    }
                }
                is FileEdit.Rename -> documentChanges += buildJsonObject {
                    put("kind", "rename")
                    put("oldUri", uriForPath(snap, edit.path))
                    put("newUri", uriForPath(snap, edit.newPath))
                }
                is FileEdit.Create -> {
                    if (edit.path.normalize() in openPaths) {
                        throw JsonRpcException(
                            JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                            "LSP cannot create a path that is already open in the client: ${edit.path}",
                        )
                    }
                    val uri = uriForPath(snap, edit.path)
                    documentChanges += buildJsonObject {
                        put("kind", "create")
                        put("uri", uri)
                        put("options", buildJsonObject {
                            put("overwrite", edit.overwrite)
                            put("ignoreIfExists", false)
                        })
                    }
                    if (edit.content.isNotEmpty()) {
                        documentChanges += buildJsonObject {
                            put("textDocument", buildJsonObject {
                                put("uri", uri)
                                put("version", JsonNull)
                            })
                            put("edits", buildJsonArray {
                                add(buildJsonObject {
                                    put("range", rangeJson(0, 0, 0, 0))
                                    put("newText", edit.content)
                                })
                            })
                        }
                    }
                }
                is FileEdit.Delete -> documentChanges += buildJsonObject {
                    put("kind", "delete")
                    put("uri", uriForPath(snap, edit.path))
                }
            }
        }
        return buildJsonObject {
            put("refactorkitPlanId", plan.id.value)
            put("operation", plan.operation)
            put("status", plan.status.name)
            put("summary", plan.summary)
            put("riskLevel", plan.riskLevel.name)
            put("evidence", plan.evidence.name)
            put("warnings", buildJsonArray { plan.warnings.forEach { add(JsonPrimitive(it)) } })
            put("refactorkitEditOwnership", "client-managed")
            put("refactorkitRollbackAvailable", false)
            put("refactorkitDocumentVersionsChecked", supportsDocumentChanges)
            if (supportsDocumentChanges) {
                put("documentChanges", JsonArray(documentChanges))
            } else {
                put("changes", buildJsonObject {
                    changes.forEach { (uri, edits) -> put(uri, JsonArray(edits)) }
                })
            }
        }
    }

    private fun moveClassPromotionAttempt(args: JsonObject?): JavaMoveClassPromotionAttemptMetadata =
        try {
            JavaMoveClassPromotionAttemptMetadata.fromRaw(
                approval = args?.string("approval"),
                warningAcknowledgement = args?.string("warningAcknowledgement"),
                confidence = args?.string("confidence"),
                force = args?.string("force"),
            )
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                failure.message ?: "Promotion metadata is invalid",
            )
        }

    private fun commandJson(title: String, command: String, argument: JsonObject): JsonObject = buildJsonObject {
        put("title", title)
        put("command", command)
        put("arguments", buildJsonArray { add(argument) })
    }

    private fun overlayOpenDocuments(diskSnapshot: ProjectSnapshot): ProjectSnapshot {
        if (openDocuments.isEmpty()) return diskSnapshot
        val files = diskSnapshot.files.associateBy { it.path }.toMutableMap()
        openDocuments.values.forEach { document ->
            val languageId = files[document.path]?.languageId ?: when (
                document.path.fileName.toString().substringAfterLast('.', "java").lowercase()
            ) {
                "ts", "tsx" -> "typescript"
                "js", "jsx" -> "javascript"
                "c", "h" -> "c"
                else -> "java"
            }
            files[document.path] = SourceFile(document.path, document.content, languageId)
        }
        return diskSnapshot.copy(files = files.values.sortedBy { it.path.toString() })
    }

    private fun snapshotRoot(): Path = snapshot?.workspace?.root
        ?: rootUri?.let { Paths.get(URI(it)).toAbsolutePath().normalize() }
        ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")

    private fun relativePathForUri(uri: String): Path {
        val root = snapshotRoot()
        val absolute = try {
            Paths.get(URI(uri)).toAbsolutePath().normalize()
        } catch (error: Exception) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid document URI: $uri")
        }
        if (!absolute.startsWith(root)) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Document is outside workspace: $uri")
        }
        val relative = root.relativize(absolute)
        val extension = relative.fileName.toString().substringAfterLast('.', "").lowercase()
        if (extension !in setOf("java", "ts", "tsx", "js", "jsx", "c", "h")) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unsupported LSP document type: $uri")
        }
        return relative
    }

    private fun dirtyOpenDocuments(): List<OpenDocument> {
        val root = snapshotRoot()
        return openDocuments.values.filter { document ->
            runCatching {
                !Files.isRegularFile(root.resolve(document.path)) ||
                    Files.readString(root.resolve(document.path)) != document.content
            }.getOrDefault(true)
        }
    }

    private fun requireManagedWriteSafe(affectedFiles: Set<Path>) {
        val dirty = dirtyOpenDocuments()
        if (dirty.isNotEmpty()) {
            throw JsonRpcException(
                JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                "Managed apply is refused while documents have unsaved content: ${dirty.map { it.path }}",
            )
        }
        val affected = affectedFiles.map(Path::normalize).toSet()
        val openAffected = openDocuments.values.filter { it.path.normalize() in affected }
        if (openAffected.isNotEmpty()) {
            throw JsonRpcException(
                JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                "Managed apply is refused for open documents; use the versioned client-managed edit or close them: ${openAffected.map { it.path }}",
            )
        }
    }

    private fun fileForUri(snap: ProjectSnapshot, uri: String) = runCatching {
        val path = Paths.get(URI(uri)).toAbsolutePath().normalize()
        val rel = snap.workspace.root.relativize(path)
        snap.files.find { it.path == rel }
    }.getOrNull()

    private fun uriForPath(snap: ProjectSnapshot, path: Path): String =
        snap.workspace.root.resolve(path).toAbsolutePath().normalize().toUri().toString()

    private fun diagnosticsForFile(snap: ProjectSnapshot, path: Path): List<JsonObject> =
        adapter.diagnostics(snap)
            .filter { it.location?.path == path }
            .map { diagnosticJson(it) }

    private fun diagnosticJson(diagnostic: Diagnostic): JsonObject = buildJsonObject {
        val loc = diagnostic.location
        put("range", if (loc != null) rangeJson(
            loc.range.start.line, loc.range.start.character,
            loc.range.end.line, loc.range.end.character,
        ) else rangeJson(0, 0, 0, 0))
        put("severity", lspDiagnosticSeverity(diagnostic.severity))
        put("source", "refactorkit")
        diagnostic.code?.let { put("code", it) }
        if (diagnostic.evidence != null || diagnostic.category != null) {
            put("data", buildJsonObject {
                diagnostic.evidence?.let { put("evidence", it.name) }
                diagnostic.category?.let { put("category", it.name) }
            })
        }
        put("message", diagnostic.message)
    }

    private fun rangeJson(sl: Int, sc: Int, el: Int, ec: Int): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", sl); put("character", sc) })
        put("end", buildJsonObject { put("line", el); put("character", ec) })
    }

    private fun structuralLspSymbolKind(kind: GenericOutline.OutlineItem.Kind): Int = when (kind) {
        GenericOutline.OutlineItem.Kind.CLASS, GenericOutline.OutlineItem.Kind.RECORD -> 5
        GenericOutline.OutlineItem.Kind.INTERFACE -> 11
        GenericOutline.OutlineItem.Kind.ENUM -> 10
        GenericOutline.OutlineItem.Kind.FUNCTION -> 12
        GenericOutline.OutlineItem.Kind.METHOD -> 6
        GenericOutline.OutlineItem.Kind.PROPERTY -> 7
        GenericOutline.OutlineItem.Kind.STRUCT -> 23
        GenericOutline.OutlineItem.Kind.TRAIT -> 11
        GenericOutline.OutlineItem.Kind.IMPL -> 5
        GenericOutline.OutlineItem.Kind.MODULE -> 2
        GenericOutline.OutlineItem.Kind.NAMESPACE -> 3
        GenericOutline.OutlineItem.Kind.PACKAGE -> 4
        GenericOutline.OutlineItem.Kind.CONSTANT -> 14
        GenericOutline.OutlineItem.Kind.VARIABLE, GenericOutline.OutlineItem.Kind.UNKNOWN -> 13
    }

    private fun lspSymbolKind(kind: org.refactorkit.core.Symbol.Kind): Int = when (kind) {
        org.refactorkit.core.Symbol.Kind.CLASS       -> 5
        org.refactorkit.core.Symbol.Kind.OBJECT      -> 19
        org.refactorkit.core.Symbol.Kind.INTERFACE   -> 11
        org.refactorkit.core.Symbol.Kind.ENUM        -> 10
        org.refactorkit.core.Symbol.Kind.RECORD      -> 5
        org.refactorkit.core.Symbol.Kind.METHOD      -> 6
        org.refactorkit.core.Symbol.Kind.FUNCTION    -> 12
        org.refactorkit.core.Symbol.Kind.CONSTANT    -> 14
        org.refactorkit.core.Symbol.Kind.FIELD       -> 8
        org.refactorkit.core.Symbol.Kind.RECORD_COMPONENT -> 8
        org.refactorkit.core.Symbol.Kind.CONSTRUCTOR -> 9
        org.refactorkit.core.Symbol.Kind.PACKAGE     -> 4
        else                                          -> 13
    }

    private fun semanticTokenType(kind: org.refactorkit.core.Symbol.Kind): Int = when (kind) {
        org.refactorkit.core.Symbol.Kind.CLASS,
        org.refactorkit.core.Symbol.Kind.OBJECT,
        org.refactorkit.core.Symbol.Kind.RECORD      -> TOKEN_TYPES.indexOf("class")
        org.refactorkit.core.Symbol.Kind.INTERFACE   -> TOKEN_TYPES.indexOf("interface")
        org.refactorkit.core.Symbol.Kind.ENUM        -> TOKEN_TYPES.indexOf("enum")
        org.refactorkit.core.Symbol.Kind.METHOD      -> TOKEN_TYPES.indexOf("method")
        org.refactorkit.core.Symbol.Kind.FIELD,
        org.refactorkit.core.Symbol.Kind.RECORD_COMPONENT -> TOKEN_TYPES.indexOf("property")
        org.refactorkit.core.Symbol.Kind.CONSTRUCTOR -> TOKEN_TYPES.indexOf("constructor")
        else                                          -> TOKEN_TYPES.indexOf("variable")
    }

    private fun lspDiagnosticSeverity(severity: Diagnostic.Severity): Int = when (severity) {
        Diagnostic.Severity.ERROR -> 1
        Diagnostic.Severity.WARNING -> 2
        Diagnostic.Severity.INFO -> 3
    }

    private fun documentVersionError(message: String): Nothing {
        onNotification("window/showMessage", buildJsonObject {
            put("type", 1)
            put("message", "RefactorKit rejected document synchronization: $message")
        })
        throw JsonRpcException(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, message)
    }

    private fun missing(field: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing required field: $field")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private data class OpenDocument(
        val path: Path,
        val version: Int,
        val content: String,
    )

    companion object {
        private val TOKEN_TYPES = listOf(
            "namespace",
            "class",
            "enum",
            "interface",
            "struct",
            "typeParameter",
            "type",
            "parameter",
            "variable",
            "property",
            "enumMember",
            "event",
            "function",
            "method",
            "macro",
            "keyword",
            "modifier",
            "comment",
            "string",
            "number",
            "regexp",
            "operator",
            "decorator",
            "constructor",
        )
        private val TOKEN_MODIFIERS = listOf("declaration", "definition", "readonly", "static", "deprecated")
    }
}

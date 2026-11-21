package org.refactorkit.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.CompletionTrigger
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileChangeKind
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.ImmutableEditorOverlayDocument
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.LanguageCapabilityProtocol
import org.refactorkit.core.ManagedRollbackExecutor
import org.refactorkit.core.ManagedRollbackOutcome
import org.refactorkit.core.PatchDiffRenderer
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PendingPlanStore
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.WorkspaceSnapshotComposer
import org.refactorkit.c.CMakeManifestCapture
import org.refactorkit.c.CCompilationSnapshotCapture
import org.refactorkit.c.CFormattingStyleSnapshot
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CCompilerSymbolSearch
import org.refactorkit.c.CCompilerSymbolNavigation
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.CReadOnlySdkRequestJson
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.WorkspaceIndex
import org.refactorkit.core.WorkspaceIndexCompleteness
import org.refactorkit.core.WorkspaceIndexSession
import org.refactorkit.core.WorkspaceRefreshCoordinator
import org.refactorkit.core.WorkspaceRefreshResult
import org.refactorkit.core.WorkspaceSymbolContribution
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.RefactoringApplyIdentity
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RollbackLookupVisibility
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.RollbackPreflightDecision
import org.refactorkit.core.RollbackPreflightGuard
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaCreateMavenModulePlanner
import org.refactorkit.java.JavaCreateGradleModulePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.refactorkit.java.JavaRenameGradleModulePlanner
import org.refactorkit.java.JavaFormatFilePlanner
import org.refactorkit.java.JavaImportTargetResolution
import org.refactorkit.java.JavaImportTargetResolver
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaSafeDeleteManagedApplyPolicy
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaJdtDefinitionProjection
import org.refactorkit.java.JavaJdtHoverProjection
import org.refactorkit.java.JavaJdtReferencesProjection
import org.refactorkit.java.JdtJavaAnalysisCache
import org.refactorkit.kotlin.KotlinAdapterRegistration
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinExtractMethodPlanner
import org.refactorkit.kotlin.KotlinInlineMethodPlanner
import org.refactorkit.kotlin.KotlinOrganizeImportsPlanner
import org.refactorkit.jvm.JavaKotlinPublicTypeRenamePlanner
import org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector
import org.refactorkit.jvm.KotlinJavaPublicTypeRenamePlanner
import org.refactorkit.jvm.KotlinJvmChangeSignaturePlanner
import org.refactorkit.jvm.KotlinJvmMoveDeclarationPlanner
import org.refactorkit.jvm.KotlinManagedDeclarationRenamePlanner
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainDiscoveryPolicy
import org.refactorkit.kotlin.KotlinToolchainRequest
import org.refactorkit.treesitter.GenericProjectScanner
import org.refactorkit.typescript.TypeScriptAdapterDescriptors
import org.refactorkit.typescript.TypeScriptBuildModelIntegration
import org.refactorkit.typescript.TypeScriptDiagnosticsProtocol
import org.refactorkit.typescript.TypeScriptProjectModelBuilder
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.java.recipe.ManagedRecipeContext
import org.refactorkit.java.recipe.ManagedRecipePreview
import org.refactorkit.typescript.TypeScriptRefactoringProtocol
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptCompletionProjection
import org.refactorkit.typescript.TypeScriptHoverProjection
import org.refactorkit.typescript.TypeScriptSignatureHelpProjection
import org.refactorkit.typescript.TypeScriptSemanticStart
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import org.refactorkit.typescript.TypeScriptSymbolProjection
import org.refactorkit.typescript.TypeScriptToolchainProvenance
import org.refactorkit.typescript.TypeScriptToolchainDiscoverer
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainDiscoveryPolicy
import org.refactorkit.typescript.TypeScriptToolchainRequest
import org.refactorkit.java.JavaMoveAcrossMavenModulesPlanner
import org.refactorkit.java.JavaMoveClassLexicalFallbackReviewJsonProjection
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPromotionAttemptMetadata
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRefactoringPreviewCommand
import org.refactorkit.java.JavaRefactoringPreviewDispatcher
import org.refactorkit.webimporter.ExternalImportPreview
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Stateful RefactorKit daemon session.
 *
 * One session per daemon process. Each call is synchronous; the stdio loop
 * serialises concurrent requests naturally.
 */
private data class WorkspaceRefreshOutcome(
    val status: String,
    val generation: Long,
    val changes: org.refactorkit.core.WorkspaceIndexChanges? = null,
    val invalidatedProviders: Set<String> = emptySet(),
    val stoppedSemanticLanguages: Set<String> = emptySet(),
    val message: String? = null,
)

class DaemonSession(
    private val toolchainDiscovery: (TypeScriptToolchainRequest, TypeScriptToolchainDiscoveryPolicy) -> TypeScriptToolchainDiscovery =
        { request, policy -> TypeScriptToolchainDiscoverer(policy).discover(request) },
    private val semanticAdapterFactory: (String, TypeScriptSemanticToolchain, org.refactorkit.typescript.TypeScriptProjectModel) -> TypeScriptSemanticAdapter =
        { languageId, toolchain, model -> TypeScriptSemanticAdapter(languageId, toolchain, model) },
    private val semanticLeaseFactory: () -> String = { "semantic-${UUID.randomUUID()}" },
    private val kotlinToolchainDiscovery: (KotlinToolchainRequest, KotlinToolchainDiscoveryPolicy) -> KotlinToolchainDiscovery =
        { request, policy -> KotlinToolchainDiscoverer(policy).discover(request) },
) : AutoCloseable {
    private val adapter = JavaLanguageAdapter()
    private val moveClassDispatcher = JavaMoveClassOperationDispatcher(adapter)
    private var scanJava: (Path) -> ProjectSnapshot = JavaProjectScanner()::scan
    private val gradleContextSession = org.refactorkit.java.JavaGradleContextSession()
    private val javaEditorOverlaySession = org.refactorkit.java.JavaEditorOverlaySession()
    private val javaEditorMutationSession = org.refactorkit.java.JavaEditorMutationSession(javaEditorOverlaySession)
    private val javaEditorTransactionSession = org.refactorkit.java.JavaEditorTransactionSession(javaEditorMutationSession,javaEditorOverlaySession)

    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var workspaceRoot: Path? = null
    private val semanticAdapters = linkedMapOf<String, TypeScriptSemanticAdapter>()
    private val semanticLeases = linkedMapOf<String, String>()
    private var kotlinAdapter = KotlinLanguageAdapter()
    private var kotlinToolchain: KotlinSemanticToolchain? = null
    private var kotlinSemanticLease: String? = null
    private val workspaceIndex = WorkspaceIndexSession()
    private var savedWorkspaceWatcher: SavedWorkspaceWatcher? = null
    private var savedWorkspaceWatcherFailure: String? = null
    private var workspaceRefreshCount: Long = 0
    private var lastWorkspaceRefresh: WorkspaceRefreshOutcome? = null

    private val pendingPlans = PendingPlanStore<PendingPlan>()

    // ── public dispatcher ─────────────────────────────────────────────────────

    fun dispatch(
        method: String,
        params: JsonObject?,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JsonElement {
        if (method !in setOf(
                "server.version", "server.capabilities", "project.open", "workspace.watch.status",
                "workspace.refresh", "patch.recover",
            )) refreshSavedWorkspaceIfDirty()
        return when (method) {
        "server.version"      -> serverVersion()
        "server.capabilities" -> serverCapabilities()
        "project.open"        -> projectOpen(params)
        "project.summary"   -> projectSummary()
        "project.context"   -> projectContext(params, cancellation)
        "project.context.aggregate" -> projectContext(params, cancellation, "java-project-context-v3", aggregate = true)
        "project.context.v4" -> projectContext(params, cancellation, "java-project-context-v4")
        "project.context.v5" -> projectContext(params, cancellation, "java-project-context-v5")
        "project.context.v6" -> projectContext(params, cancellation, "java-project-context-v6")
        "project.context.v7" -> projectContext(params, cancellation, "java-project-context-v7")
        "project.context.v7.testJvmSelection" -> projectContext(
            params, cancellation, "java-project-context-v7", includeMavenTestJvmSelection = true,
        )
        "java.platformSource.lookup" -> javaPlatformSource(params, cancellation, org.refactorkit.java.JavaPlatformSourceOperation.LOOKUP)
        "java.platformSource.declaration" -> javaPlatformSource(params, cancellation, org.refactorkit.java.JavaPlatformSourceOperation.DECLARATION)
        "java.platformSource.read" -> javaPlatformSource(params, cancellation, org.refactorkit.java.JavaPlatformSourceOperation.READ)
        "java.dependencySource.lookup" -> javaDependencySource(params, cancellation, false)
        "java.dependencySource.read" -> javaDependencySource(params, cancellation, true)
        "java.dependencySource.declaration" -> javaDependencySourceDeclaration(params, cancellation)
        "java.dependencySource.editor.lookup" -> javaEditorDependencySource(params, cancellation, org.refactorkit.java.JavaEditorDependencySourceOperation.LOOKUP)
        "java.dependencySource.editor.declaration" -> javaEditorDependencySource(params, cancellation, org.refactorkit.java.JavaEditorDependencySourceOperation.DECLARATION)
        "java.dependencySource.editor.read" -> javaEditorDependencySource(params, cancellation, org.refactorkit.java.JavaEditorDependencySourceOperation.READ)
        "project.sync"      -> projectSync(params, cancellation)
        "index.status"      -> indexStatus()
        "workspace.watch.status" -> workspaceWatchStatus()
        "workspace.refresh" -> workspaceRefresh()
        "intelligence.query" -> intelligenceQuery(params, cancellation)
        "typescript.semantic.start" -> typeScriptSemanticStart(params)
        "typescript.semantic.restart" -> typeScriptSemanticRestart(params)
        "typescript.semantic.stop" -> typeScriptSemanticStop(params)
        "kotlin.semantic.start" -> kotlinSemanticStart(params)
        "kotlin.semantic.stop" -> kotlinSemanticStop()
        "kotlin.diagnostics" -> kotlinDiagnostics(params)
        "kotlin.symbols" -> kotlinSymbols(params)
        "kotlin.definition" -> kotlinDefinition(params)
        TypeScriptDiagnosticsProtocol.METHOD -> diagnosticsV2(params)
        "symbol.search"     -> symbolSearch(params)
        "symbol.definition" -> symbolDefinition(params)
        "symbol.references" -> symbolReferences(params)
        "diagnostics"       -> diagnostics(params)
        "typescript.refactorings" -> TypeScriptRefactoringProtocol.catalogue(requireSemanticAdapter(params?.string("languageId") ?: "typescript"))
        "refactor.preview"  -> refactorPreview(params)
        "refactor.editor.preview" -> javaEditorPreview(params,cancellation)
        "refactor.editor.prepare", "refactor.editor.confirm", "refactor.editor.undo.prepare", "refactor.editor.undo.confirm", "refactor.editor.recover" -> javaEditorTransaction(method,params)
        "refactor.editor.transactions" -> { requireSnapshot();org.refactorkit.java.JavaEditorMutationProtocol.renderTransactions(javaEditorTransactionSession.transactions()) }
        "refactor.apply"    -> refactorApply(params)
        "refactor.discard"  -> refactorDiscard(params)
        "patch.recover"     -> patchRecover(params)
        "patch.rollback"    -> patchRollback(params)
        "java.importExternalClass" -> javaImportExternalClass(params)
        "c.preview"         -> cPreview(params)
        else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    // ── methods ───────────────────────────────────────────────────────────────

    private fun serverVersion(): JsonElement = buildJsonObject {
        put("name", RefactorKitVersion.NAME)
        put("version", RefactorKitVersion.VERSION)
        put("apiVersion", RefactorKitVersion.API_VERSION)
    }

    private fun serverCapabilities(): JsonElement = buildJsonObject {
        put("name", RefactorKitVersion.NAME)
        put("version", RefactorKitVersion.VERSION)
        put("apiVersion", RefactorKitVersion.API_VERSION)
        put("protocol", "json-rpc-2.0")
        put("transport", "stdio")
        put("methods", buildJsonArray {
            DAEMON_METHODS.forEach { capability ->
                add(buildJsonObject {
                    put("name", capability.name)
                    put("stability", capability.stability)
                    put("requiresProject", capability.requiresProject)
                    put("writesWorkspace", capability.writesWorkspace)
                    if (capability.features.isNotEmpty()) put("features", buildJsonObject {
                        capability.features.forEach { (name, supported) -> put(name, supported) }
                    })
                })
            }
        })
        put("languageKernel", LanguageCapabilityProtocol.render(
            listOf(
                javaDaemonAdapterDescriptor(),
                KotlinAdapterRegistration.descriptor(),
            ) + TypeScriptAdapterDescriptors.descriptors(),
        ))
        put("safety", buildJsonObject {
            put("previewBeforeApply", true)
            put("snapshotValidation", true)
            put("transactionRollback", true)
            put("workspaceScopedWrites", true)
        })
        put("javaContextRefactoring", buildJsonObject {
            put("schemaVersion", 1)
            put("operations", buildJsonArray { org.refactorkit.java.JavaBoundContext.supportedMutationOperations().forEach { add(JsonPrimitive(it)) } })
            put("operationCapabilities", buildJsonArray {
                org.refactorkit.java.JavaAdapterRegistration.mutationCapabilities()
                    .sortedBy { it.operation }
                    .forEach { capability ->
                        add(buildJsonObject {
                            put("operation", capability.operation)
                            put("stability", capability.stability.name.lowercase().replace('_', '-'))
                            put("mutationAuthority", capability.mutationAuthority.name.lowercase().replace('_', '-'))
                            put("evidence", capability.evidence.name.lowercase().replace('_', '-'))
                            if (capability.operation == "moveClass") {
                                put("targetModuleSelection", buildJsonObject {
                                    put("schemaVersion", "java-move-class-target-module-v1")
                                    put("argument", "targetModuleId")
                                    put("identity", "module-id-within-source-provider")
                                    put("requiresAvailableContext", true)
                                    put("sourceSetKind", "main")
                                    put("requiresUniqueNonGeneratedRoot", true)
                                })
                            }
                            if (capability.operation == org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.OPERATION) {
                                put("referenceAnalysis", safeDeleteReferenceAnalysisCapabilityJson())
                            }
                        })
                    }
            })
            put("requiresExplicitContext", true)
            put("sourceAuthority", "saved-snapshot")
            put("rollbackAuthority", true)
            put("renderedDiff", true)
            put("dirtyEditorMutationAuthority", true)
            put("editorMutationOperations",buildJsonArray {org.refactorkit.java.JavaEditorPreviewOperation.entries.forEach {add(JsonPrimitive(it.wire))}})
            put("editorMutationModes",buildJsonArray {org.refactorkit.java.JavaEditorMutationMode.entries.forEach {add(JsonPrimitive(it.wire))}})
            put("dirtyEditorPreview",true)
            put("editorPreviewMethod","refactor.editor.preview")
            put("editorPreviewSchemaVersion",org.refactorkit.java.JavaEditorMutationProtocol.VERSION)
            put("editorPreviewOperations",buildJsonArray { org.refactorkit.java.JavaEditorPreviewOperation.entries.forEach { add(JsonPrimitive(it.wire)) } })
            put("selectionQueryKind", "mutationSelection")
            put("selectionSchemaVersion", "java-mutation-selection-v1")
            put("editorSelectionSchemaVersion",org.refactorkit.java.JavaEditorMutationSelectionWire.VERSION)
            put("editorTransactionSchemaVersion","java-editor-transaction-v1")
            put("durableEditorRecovery",true)
            put("editorJournalSchemaVersion","java-editor-journal-v1")
            put("editorTransactionDiscoveryMethod","refactor.editor.transactions")
            put("editorRecoveryMethod","refactor.editor.recover")
        })
    }

    /** Daemon-only runtime projection: only scheduled JDT reads accept cooperative cancellation. */
    private fun javaDaemonAdapterDescriptor(): org.refactorkit.core.LanguageAdapterDescriptor {
        val descriptor = JavaAdapterRegistration.create().descriptor
        fun daemonRuntime(runtime: org.refactorkit.core.LanguageAdapterRuntime, cancellable: Boolean) = runtime.copy(
            supportsCancellation = cancellable,
            limits = runtime.limits.copy(maxOutputBytes = ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES.toLong()),
        )
        val cancellableReads = setOf("definition", "hover", "references", "typeDefinition", "typeHierarchy", "callHierarchy", "implementations")
        val daemonCapabilities = descriptor.capabilities.map { capability ->
            val runtime = capability.runtime ?: descriptor.runtime
            capability.copy(runtime = daemonRuntime(runtime, capability.operation in cancellableReads))
        }
        return descriptor.copy(
            runtime = daemonRuntime(descriptor.runtime, cancellable = false),
            capabilities = daemonCapabilities,
        )
    }

    private fun projectOpen(params: JsonObject?): JsonElement {
        closeSavedWorkspaceWatcher()
        closeSemanticAdapters()
        adapter.clearSemanticCache()
        pendingPlans.clear()
        moveClassDispatcher.clearLexicalReviewAudit()
        javaEditorOverlaySession.clear()
        javaEditorMutationSession.clear()
        javaEditorTransactionSession.clear()
        snapshot = null
        workspaceRoot = null
        workspaceIndex.clear()
        val root = params?.string("root") ?: missing("root")
        val resolveDependencies = params.string("resolveDependencies")?.toBooleanStrictOrNull() ?: false
        val (selection, mavenRuntime) = try {
            fun profiles(key: String): Set<String> {
                val value = params[key] ?: return emptySet()
                val entries = value as? JsonArray ?: throw IllegalArgumentException("$key must be an array")
                return entries.map { entry ->
                    val profile = entry as? JsonPrimitive ?: throw IllegalArgumentException("$key entries must be strings")
                    require(profile.isString) { "$key entries must be strings" }
                    profile.content
                }.toSet()
            }
            val runtime = params["mavenRuntime"]?.let { value ->
                require(value is JsonObject && value.keys == setOf("coreJar", "sha256")) {
                    "mavenRuntime must contain coreJar and sha256"
                }
                fun field(name: String): String {
                    val field = value[name]
                    require(field is JsonPrimitive && field.isString) { "mavenRuntime.$name must be a string" }
                    return field.content
                }
                org.refactorkit.java.MavenRuntimeSelection(Paths.get(field("coreJar")), field("sha256"))
            }
            org.refactorkit.core.BuildModelSelection(profiles("activeMavenProfiles"), profiles("inactiveMavenProfiles")) to runtime
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid Maven project selection")
        }
        gradleContextSession.clear()
        val scanner = JavaProjectScanner(
            allowNetworkDependencyResolution = resolveDependencies,
            localMavenRepository = params.string("mavenRepository")?.let(Paths::get)
                ?: Path.of(System.getProperty("user.home"), ".m2", "repository"),
            activeMavenProfiles = selection.activeProfiles,
            inactiveMavenProfiles = selection.inactiveProfiles,
            effectiveGradleModelProvider = gradleContextSession::modelFor,
            effectiveGradleEvidenceProvider = gradleContextSession::evidenceFor,
        )
        scanJava = { path -> scanner.scan(path, mavenRuntime) }
        val path = Paths.get(root).toAbsolutePath().normalize()
        withSourceCaptureRefusal { OwnedSourceInventoryReader.requireOwnedRoot(path) }
        val recoveryErrors = PatchEngine(path).inspectRecovery()
        if (recoveryErrors.isNotEmpty()) {
            throw JsonRpcException(
                JsonRpcErrorCodes.RECOVERY_REQUIRED,
                "Workspace recovery required; call patch.recover explicitly: ${recoveryErrors.joinToString("; ") { it.message }}",
            )
        }
        val snap = scanWorkspace(path)
        try { javaEditorTransactionSession.open(path) }
        catch(failure:org.refactorkit.java.JavaContextAuthorityException) {
            throw JsonRpcException(JsonRpcErrorCodes.RECOVERY_REQUIRED,failure.message.orEmpty(),buildJsonObject {put("refusalCode",failure.code)})
        }
        snapshot = snap
        workspaceRoot = path
        val index = openWorkspaceIndex(snap)
        val watcher = SavedWorkspaceWatcher.start(path)
        savedWorkspaceWatcher = watcher.getOrNull()
        savedWorkspaceWatcherFailure = watcher.exceptionOrNull()?.javaClass?.simpleName
        return buildJsonObject {
            put("root", workspaceRoot.toString())
            put("fileCount", snap.files.size)
            put("moduleCount", snap.modules.size)
            put("snapshotHash", snap.hash)
            put("indexGeneration", index.generation)
            put("indexedSourceCount", index.sources.size)
            put("indexedSymbolCount", index.symbolCount)
            put("watcherState", if (savedWorkspaceWatcher != null) "active" else "unavailable")
        }
    }

    private fun openWorkspaceIndex(snap: ProjectSnapshot): WorkspaceIndex {
        workspaceIndex.open(snap)
        val discovered = adapter.buildSymbols(snap).symbols
        val limit = ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS
        return workspaceIndex.contribute(WorkspaceSymbolContribution(
            providerId = "java-source-declarations-v1",
            languageId = "java",
            backend = "java-source-declarations-v1",
            evidence = SemanticEvidenceKind.LEXICAL,
            completeness = WorkspaceIndexCompleteness.DECLARATIONS,
            snapshotHash = snap.hash,
            symbols = discovered.take(limit),
            truncated = discovered.size > limit,
        ))
    }

    private fun indexStatus(): JsonElement = workspaceIndexStatus(
        workspaceIndex.snapshot() ?: throw JsonRpcException(
            JsonRpcErrorCodes.PROJECT_NOT_OPEN,
            "No project open",
        ),
    )

    private fun workspaceRefresh(): JsonElement = workspaceRefreshToJson(refreshSavedWorkspace(force = true))

    private fun workspaceWatchStatus(): JsonElement {
        requireSnapshot()
        val status = savedWorkspaceWatcher?.status()
        return buildJsonObject {
            put("schemaVersion", 1)
            put("state", status?.state ?: "unavailable")
            put("dirty", status?.dirty ?: false)
            put("watchedDirectories", status?.watchedDirectories ?: 0)
            put("observedEvents", status?.observedEvents ?: 0)
            put("overflowed", status?.overflowed ?: false)
            put("refreshCount", workspaceRefreshCount)
            (status?.failure ?: savedWorkspaceWatcherFailure)?.let { put("failure", it) }
            lastWorkspaceRefresh?.let { put("lastRefresh", workspaceRefreshToJson(it)) }
        }
    }

    private fun refreshSavedWorkspaceIfDirty() {
        if (snapshot == null) return
        val watcher = savedWorkspaceWatcher
        val status = watcher?.status()
        if (watcher == null || watcher.consumeDirty() || status?.overflowed == true || status?.state != "active") {
            refreshSavedWorkspace(force = true)
        }
    }

    private fun refreshSavedWorkspace(force: Boolean): WorkspaceRefreshOutcome {
        if (force) savedWorkspaceWatcher?.consumeDirty()
        val current = snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        if (!force) return WorkspaceRefreshOutcome(
            "unchanged", workspaceIndex.snapshot()?.generation ?: 0,
        )
        val refresh = try {
            WorkspaceRefreshCoordinator.refresh(current, { scanWorkspace(root) }) { _, next ->
                workspaceIndex.reconcile(next)
            }
        } catch (problem: Exception) {
            savedWorkspaceWatcher?.markDirty()
            return WorkspaceRefreshOutcome(
                "refused", workspaceIndex.snapshot()?.generation ?: 0,
                message = problem.javaClass.simpleName,
            ).also { lastWorkspaceRefresh = it }
        }
        workspaceRefreshCount++
        if (refresh is WorkspaceRefreshResult.Unchanged) return WorkspaceRefreshOutcome(
            "unchanged", workspaceIndex.snapshot()?.generation ?: 0,
        ).also { lastWorkspaceRefresh = it }

        val changed = refresh as WorkspaceRefreshResult.Changed
        val next = changed.snapshot
        val reconciliation = changed.preparedState
        snapshot = next
        moveClassDispatcher.clearLexicalReviewAudit()
        val stoppedLanguages = semanticAdapters.keys.toSortedSet()
        semanticAdapters.values.forEach(TypeScriptSemanticAdapter::close)
        semanticAdapters.clear()
        semanticLeases.clear()
        if (kotlinSemanticLease != null || kotlinToolchain != null) {
            stoppedLanguages += "kotlin"
            kotlinSemanticLease = null
            kotlinToolchain = null
            kotlinAdapter = KotlinLanguageAdapter()
        }
        var refreshed = reconciliation.index
        if ("java-source-declarations-v1" in reconciliation.invalidatedProviders) {
            val symbols = adapter.buildSymbols(next).symbols
            val limit = ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS
            refreshed = workspaceIndex.contribute(WorkspaceSymbolContribution(
                providerId = "java-source-declarations-v1",
                languageId = "java",
                backend = "java-source-declarations-v1",
                evidence = SemanticEvidenceKind.LEXICAL,
                completeness = WorkspaceIndexCompleteness.DECLARATIONS,
                snapshotHash = next.hash,
                symbols = symbols.take(limit),
                truncated = symbols.size > limit,
            ))
        }
        return WorkspaceRefreshOutcome(
            status = "ready",
            generation = refreshed.generation,
            changes = reconciliation.changes,
            invalidatedProviders = reconciliation.invalidatedProviders,
            stoppedSemanticLanguages = stoppedLanguages,
        ).also { lastWorkspaceRefresh = it }
    }

    private fun workspaceRefreshToJson(outcome: WorkspaceRefreshOutcome): JsonElement = buildJsonObject {
        put("status", outcome.status)
        put("snapshotHash", workspaceIndex.snapshot()?.snapshotHash ?: "")
        put("generation", outcome.generation)
        outcome.message?.let { put("message", it) }
        put("invalidatedProviders", buildJsonArray {
            outcome.invalidatedProviders.sorted().forEach { add(JsonPrimitive(it)) }
        })
        put("stoppedSemanticLanguages", buildJsonArray {
            outcome.stoppedSemanticLanguages.sorted().forEach { add(JsonPrimitive(it)) }
        })
        outcome.changes?.let { changes ->
            put("changes", buildJsonObject {
                put("added", changes.added.size); put("modified", changes.modified.size)
                put("deleted", changes.deleted.size); put("unchanged", changes.unchanged.size)
                val paths = changes.changed.sortedBy(Path::toString)
                put("paths", buildJsonArray {
                    paths.take(ProtocolLimits.MAX_SYMBOL_RESULTS).forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
                })
                put("pathsTruncated", paths.size > ProtocolLimits.MAX_SYMBOL_RESULTS)
            })
        }
    }

    private fun workspaceIndexStatus(index: WorkspaceIndex): JsonElement = buildJsonObject {
        put("schemaVersion", 1)
        put("status", "ready")
        put("snapshotHash", index.snapshotHash)
        put("generation", index.generation)
        put("sourceCount", index.sources.size)
        put("symbolCount", index.symbolCount)
        put("languages", buildJsonArray {
            index.sources.map { it.languageId }.distinct().sorted().forEach { add(JsonPrimitive(it)) }
        })
        put("providers", buildJsonArray {
            index.contributions.forEach { contribution ->
                add(buildJsonObject {
                    put("providerId", contribution.providerId)
                    put("languageId", contribution.languageId)
                    put("backend", contribution.backend)
                    put("evidence", contribution.evidence.name.lowercase().replace('_', '-'))
                    put("completeness", contribution.completeness.name.lowercase().replace('_', '-'))
                    put("symbolCount", contribution.symbols.size)
                    put("truncated", contribution.truncated)
                    contribution.provenanceHash?.let { put("provenanceHash", it) }
                })
            }
        })
    }

    private fun intelligenceQuery(
        params: JsonObject?,
        cancellation: SemanticCancellationToken,
        boundJavaSnapshot: ProjectSnapshot? = null,
        javaAdapter: JavaLanguageAdapter = adapter,
    ): JsonElement {
        val p = params ?: missing("params")
        if (p.string("kind") == "entryPoints") return javaEntryPointQuery(p, cancellation)
        if ("javaContext" in p) return javaContextQuery(p, cancellation)
        val requestId = p.string("requestId") ?: missing("requestId")
        if (requestId.isBlank() || requestId.length > ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "requestId is invalid")
        }
        val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        val index = workspaceIndex.snapshot() ?: throw JsonRpcException(
            JsonRpcErrorCodes.PROJECT_NOT_OPEN,
            "No project open",
        )
        val kind = p.string("kind") ?: missing("kind")
        if (p.string("priority") !in setOf(null, "interactive", "normal", "background")) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "intelligence query priority is invalid")
        }
        if (cancellation.isCancellationRequested()) return intelligenceRefusal(
            requestId, kind, index, "intelligence.cancelled", "Interactive semantic query was cancelled",
        )
        if (expected != index.snapshotHash) return intelligenceRefusal(
            requestId,
            kind,
            index,
            "intelligence.snapshotStale",
            "Expected workspace index snapshot is stale",
        )
        val expectedGeneration = p.string("expectedIndexGeneration")?.let { raw ->
            raw.toLongOrNull()?.takeIf { it >= 1 } ?: throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                "expectedIndexGeneration must be a positive integer",
            )
        }
        if (expectedGeneration != null && expectedGeneration != index.generation) return intelligenceRefusal(
            requestId,
            kind,
            index,
            "intelligence.indexStale",
            "Expected workspace index generation is stale",
        )
        if (kind !in setOf("workspaceSymbols", "documentSymbols", "completion", "hover", "signatureHelp", "definition", "references", "typeDefinition", "typeHierarchy", "callHierarchy", "implementations")) return intelligenceRefusal(
            requestId,
            kind,
            index,
            "intelligence.queryUnsupported",
            "Semantic query kind is not implemented: $kind",
        )
        val query = p.string("query").orEmpty()
        if (query.length > ProtocolLimits.MAX_INTELLIGENCE_QUERY_CHARS) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "query is too long")
        }
        val languageId = p.string("languageId")
        val maximumLimit = if (kind in setOf("references", "callHierarchy")) ProtocolLimits.MAX_REFERENCE_RESULTS else ProtocolLimits.MAX_SYMBOL_RESULTS
        val requestedLimit = p.string("limit")?.toIntOrNull() ?: maximumLimit
        if (requestedLimit !in 1..maximumLimit) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                "limit must be between 1 and $maximumLimit",
            )
        }
        val path = p.string("path")?.let(::protocolRelativePath)
        if (kind in setOf("documentSymbols", "definition", "references", "typeDefinition", "typeHierarchy", "callHierarchy", "implementations") && path == null) missing("path")
        val sourceAuthority = p["sourceAuthority"] as? JsonObject
        if (sourceAuthority?.string("kind") == "immutable-editor-overlay") {
            if (kind !in setOf("documentSymbols", "completion", "hover", "signatureHelp") || path == null) return intelligenceRefusal(
                requestId, kind, index, "intelligence.overlayKindUnsupported",
                "Editor overlay authority currently supports documentSymbols, completion, hover and signatureHelp",
            )
            val overlayLanguage = languageId ?: missing("languageId")
            if (overlayLanguage !in setOf("typescript", "javascript")) return intelligenceRefusal(
                requestId, kind, index, "intelligence.overlayProviderUnsupported",
                "Editor overlay semantic queries require TypeScript or JavaScript provider authority",
            )
            val lease = p.string("semanticLease") ?: missing("semanticLease")
            if (semanticLeases[overlayLanguage] != lease) return intelligenceRefusal(
                requestId, kind, index, "intelligence.semanticLeaseStale",
                "Editor overlay semantic lease is missing or stale",
            )
            val overlay = parseIntelligenceOverlay(sourceAuthority, (boundJavaSnapshot ?: requireSnapshot()), overlayLanguage)
            if (overlay.document(path) == null) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "$kind path must be present in the editor overlay",
            )
            val semantic = semanticAdapters[overlayLanguage] ?: return intelligenceRefusal(
                requestId, kind, index, "intelligence.semanticSessionNotReady",
                "TypeScript semantic provider is not started",
            )
            if (kind in setOf("completion", "hover", "signatureHelp")) {
                val positionObject = p["position"] as? JsonObject ?: missing("position")
                val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
                val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
                if (line < 0 || character < 0) throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "$kind position must be non-negative",
                )
                val position = SourcePosition(line, character)
                if (kind == "completion") {
                    val trigger = when (p.string("trigger") ?: "invoked") {
                        "invoked" -> CompletionTrigger.INVOKED
                        "trigger-character" -> CompletionTrigger.TRIGGER_CHARACTER
                        "incomplete-retrigger" -> CompletionTrigger.INCOMPLETE_RETRIGGER
                        else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "completion trigger is invalid")
                    }
                    val triggerCharacter = p.string("triggerCharacter")
                    if ((trigger == CompletionTrigger.TRIGGER_CHARACTER) != (triggerCharacter != null) ||
                        triggerCharacter != null && (triggerCharacter.isEmpty() || triggerCharacter.length > 8)) {
                        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "completion triggerCharacter is invalid")
                    }
                    val completion = semantic.overlayCompletion(
                        (boundJavaSnapshot ?: requireSnapshot()), overlay, path, position, trigger, triggerCharacter, requestedLimit, cancellation,
                    )
                    invalidateStoppedTypeScriptProvider(overlayLanguage, semantic)
                    return when (completion) {
                        is TypeScriptCompletionProjection.Available -> overlayCompletionResponse(
                            requestId, index, overlayLanguage, overlay, completion,
                        )
                        is TypeScriptCompletionProjection.Refused -> intelligenceRefusal(
                            requestId, kind, index,
                            completion.diagnostic.code ?: "intelligence.completionUnavailable",
                            completion.diagnostic.message,
                        )
                    }
                }
                if (kind == "signatureHelp") {
                    val triggerCharacter = p.string("triggerCharacter")
                    if (triggerCharacter != null && (triggerCharacter.isEmpty() || triggerCharacter.length > 8)) {
                        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "signatureHelp triggerCharacter is invalid")
                    }
                    val retrigger = p.string("retrigger")?.toBooleanStrictOrNull()
                        ?: if ("retrigger" in p) throw JsonRpcException(
                            JsonRpcErrorCodes.INVALID_PARAMS, "signatureHelp retrigger must be boolean",
                        ) else false
                    val signature = semantic.overlaySignatureHelp(
                        (boundJavaSnapshot ?: requireSnapshot()), overlay, path, position, triggerCharacter, retrigger, cancellation,
                    )
                    invalidateStoppedTypeScriptProvider(overlayLanguage, semantic)
                    return when (signature) {
                        is TypeScriptSignatureHelpProjection.Available -> overlaySignatureHelpResponse(
                            requestId, index, overlayLanguage, overlay, signature,
                        )
                        is TypeScriptSignatureHelpProjection.Refused -> intelligenceRefusal(
                            requestId, kind, index,
                            signature.diagnostic.code ?: "intelligence.signatureHelpUnavailable",
                            signature.diagnostic.message,
                        )
                    }
                }
                val hover = semantic.overlayHover(
                    (boundJavaSnapshot ?: requireSnapshot()), overlay, path, position, cancellation,
                )
                invalidateStoppedTypeScriptProvider(overlayLanguage, semantic)
                return when (hover) {
                    is TypeScriptHoverProjection.Available -> overlayHoverResponse(
                        requestId, index, overlayLanguage, overlay, hover,
                    )
                    is TypeScriptHoverProjection.Refused -> intelligenceRefusal(
                        requestId, kind, index, hover.diagnostic.code ?: "intelligence.hoverUnavailable", hover.diagnostic.message,
                    )
                }
            }
            val projection = semantic.overlayDocumentSymbols(
                (boundJavaSnapshot ?: requireSnapshot()), overlay, path, requestedLimit, cancellation,
            )
            invalidateStoppedTypeScriptProvider(overlayLanguage, semantic)
            return when (projection) {
                is TypeScriptSymbolProjection.Available -> overlayDocumentSymbolsResponse(requestId, index, overlayLanguage, overlay, projection)
                is TypeScriptSymbolProjection.Refused -> intelligenceRefusal(
                    requestId, kind, index, projection.diagnostic.code ?: "intelligence.overlayUnavailable", projection.diagnostic.message,
                )
            }
        } else if (sourceAuthority != null) {
            if (sourceAuthority.string("kind") != "saved-snapshot" || sourceAuthority.keys != setOf("kind")) {
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "saved-snapshot sourceAuthority accepts only kind")
            }
        }
        if (kind == "definition" && languageId == "kotlin") {
            return kotlinUsageNavigation(p, requestId, kind, index, requireNotNull(path), requestedLimit)
        }
        if (kind == "definition") {
            if (languageId != "java") return intelligenceRefusal(
                requestId, kind, index, "intelligence.definitionProviderUnsupported",
                "Saved-snapshot definition currently requires Java JDT provider authority",
            )
            if (sourceAuthority?.string("kind") != "saved-snapshot") return intelligenceRefusal(
                requestId, kind, index, "intelligence.definitionSavedSnapshotRequired",
                "Java definition requires saved-snapshot source authority",
            )
            val targetPath = requireNotNull(path)
            val positionObject = p["position"] as? JsonObject ?: missing("position")
            val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
            val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
            if (line < 0 || character < 0) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "definition position must be non-negative",
            )
            val location = SourceLocation(
                targetPath, SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
            )
            return when (val definition = javaAdapter.semanticDefinition((boundJavaSnapshot ?: requireSnapshot()), location, cancellation)) {
                is JavaJdtDefinitionProjection.Available -> {
                    if (cancellation.isCancellationRequested()) return intelligenceRefusal(
                        requestId, kind, workspaceIndex.snapshot() ?: index,
                        "intelligence.cancelled", "Interactive semantic query was cancelled",
                    )
                    val updated = ensureJavaJdtContribution(
                        (boundJavaSnapshot ?: requireSnapshot()), definition.symbols, definition.provenanceHash,
                    )
                    javaDefinitionResponse(requestId, updated, targetPath, definition, javaAdapter)
                }
                is JavaJdtDefinitionProjection.Refused -> intelligenceRefusal(
                    requestId, kind, workspaceIndex.snapshot() ?: index,
                    definition.diagnostic.code ?: "intelligence.definitionUnavailable",
                    definition.diagnostic.message,
                )
            }
        }
        if (kind == "hover" && languageId == "java") {
            if (sourceAuthority?.string("kind") != "saved-snapshot") return intelligenceRefusal(
                requestId, kind, index, "intelligence.hoverSavedSnapshotRequired",
                "Java hover requires saved-snapshot source authority",
            )
            val targetPath = path ?: missing("path")
            val positionObject = p["position"] as? JsonObject ?: missing("position")
            val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
            val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
            if (line < 0 || character < 0) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "hover position must be non-negative",
            )
            val location = SourceLocation(
                targetPath, SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
            )
            return when (val hover = javaAdapter.semanticHover((boundJavaSnapshot ?: requireSnapshot()), location, cancellation)) {
                is JavaJdtHoverProjection.Available -> {
                    if (cancellation.isCancellationRequested()) return intelligenceRefusal(
                        requestId, kind, workspaceIndex.snapshot() ?: index,
                        "intelligence.cancelled", "Interactive semantic query was cancelled",
                    )
                    val updated = ensureJavaJdtContribution(
                        (boundJavaSnapshot ?: requireSnapshot()), hover.symbols, hover.provenanceHash,
                    )
                    javaHoverResponse(requestId, updated, targetPath, hover, javaAdapter)
                }
                is JavaJdtHoverProjection.Refused -> intelligenceRefusal(
                    requestId, kind, workspaceIndex.snapshot() ?: index,
                    hover.diagnostic.code ?: "intelligence.hoverUnavailable", hover.diagnostic.message,
                )
            }
        }
        if (kind == "references" && languageId == "kotlin") {
            return kotlinUsageNavigation(p, requestId, kind, index, requireNotNull(path), requestedLimit)
        }
        if (kind == "references") {
            if (languageId != "java") return intelligenceRefusal(
                requestId, kind, index, "intelligence.referencesProviderUnsupported",
                "Saved-snapshot references currently require Java JDT provider authority",
            )
            if (sourceAuthority?.string("kind") != "saved-snapshot") return intelligenceRefusal(
                requestId, kind, index, "intelligence.referencesSavedSnapshotRequired",
                "Java references require saved-snapshot source authority",
            )
            val includeDeclaration = p.string("includeDeclaration")?.toBooleanStrictOrNull()
                ?: if ("includeDeclaration" in p) throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be boolean",
                ) else true
            val targetPath = requireNotNull(path)
            val positionObject = p["position"] as? JsonObject ?: missing("position")
            val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
            val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
            if (line < 0 || character < 0) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "references position must be non-negative",
            )
            val location = SourceLocation(
                targetPath, SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
            )
            return when (val references = javaAdapter.semanticReferences(
                (boundJavaSnapshot ?: requireSnapshot()), location, includeDeclaration, requestedLimit, cancellation,
            )) {
                is JavaJdtReferencesProjection.Available -> {
                    if (cancellation.isCancellationRequested()) return intelligenceRefusal(
                        requestId, kind, workspaceIndex.snapshot() ?: index,
                        "intelligence.cancelled", "Interactive semantic query was cancelled",
                    )
                    val updated = ensureJavaJdtContribution(
                        (boundJavaSnapshot ?: requireSnapshot()), references.symbols, references.provenanceHash,
                    )
                    javaReferencesResponse(requestId, updated, targetPath, references, javaAdapter)
                }
                is JavaJdtReferencesProjection.Refused -> intelligenceRefusal(
                    requestId, kind, workspaceIndex.snapshot() ?: index,
                    references.diagnostic.code ?: "intelligence.referencesUnavailable",
                    references.diagnostic.message,
                )
            }
        }
        if (kind in setOf("typeDefinition", "typeHierarchy", "callHierarchy", "implementations")) {
            if (languageId != "java") return intelligenceRefusal(
                requestId, kind, index, "intelligence.${kind}ProviderUnsupported",
                "Saved-snapshot $kind currently requires Java JDT binding authority",
            )
            if (sourceAuthority?.string("kind") != "saved-snapshot") return intelligenceRefusal(
                requestId, kind, index, "intelligence.${kind}SavedSnapshotRequired",
                "Java $kind requires saved-snapshot source authority",
            )
            val targetPath = requireNotNull(path)
            val positionObject = p["position"] as? JsonObject ?: missing("position")
            val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
            val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
            if (line < 0 || character < 0) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "$kind position must be non-negative",
            )
            val direction = javaNavigationDirection(kind, p)
            val location = SourceLocation(
                targetPath, SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
            )
            return when (val projection = javaAdapter.semanticNavigation(
                boundJavaSnapshot ?: requireSnapshot(), javaNavigationKind(kind), location, direction,
                requestedLimit, cancellation,
            )) {
                is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> intelligenceRefusal(
                    requestId, kind, workspaceIndex.snapshot() ?: index,
                    projection.diagnostic.code ?: "intelligence.${kind}Unavailable", projection.diagnostic.message,
                )
                else -> {
                    val symbols = javaNavigationSymbols(projection)
                    val provenanceHash = javaNavigationProvenance(projection)
                    val updated = ensureJavaJdtContribution(
                        boundJavaSnapshot ?: requireSnapshot(), symbols, provenanceHash,
                    )
                    val source = updated.sources.singleOrNull { it.path.normalize() == targetPath.normalize() }
                        ?: return intelligenceRefusal(
                            requestId, kind, updated, "intelligence.${kind}SourceMissing",
                            "Java navigation source disappeared from the indexed snapshot",
                        )
                    javaSemanticNavigationResponse(
                        requestId, updated, targetPath, projection, javaAdapter,
                        buildJsonObject {
                            put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(targetPath))
                            put("contentSha256", source.contentSha256)
                        },
                    )
                }
            }
        }
        if (kind in setOf("completion", "hover", "signatureHelp")) return intelligenceRefusal(
            requestId, kind, index, "intelligence.${kind}OverlayRequired",
            "TypeScript $kind currently requires immutable-editor-overlay authority",
        )
        val relevantLanguages = index.sources.asSequence().map { it.languageId }
            .filter { languageId == null || it == languageId }.toSortedSet()
        val relevantProviders = index.contributions.filter { it.languageId in relevantLanguages }
        val missingProviderLanguages = relevantLanguages - relevantProviders.map { it.languageId }.toSet()
        val coverage = when {
            missingProviderLanguages.isNotEmpty() || relevantProviders.any { it.truncated } -> "partial"
            relevantProviders.isNotEmpty() && relevantProviders.all {
                it.completeness == WorkspaceIndexCompleteness.SEMANTIC
            } -> "semantic"
            relevantProviders.isNotEmpty() -> "declarations"
            else -> "source-inventory"
        }
        if (cancellation.isCancellationRequested()) return intelligenceRefusal(
            requestId, kind, index, "intelligence.cancelled", "Interactive semantic query was cancelled",
        )
        val all = index.searchSymbols(query, languageId, path)
        val returned = all.take(requestedLimit)
        return buildJsonObject {
            put("schemaVersion", 1)
            put("requestId", requestId)
            put("status", "ready")
            put("kind", kind)
            put("snapshotHash", index.snapshotHash)
            put("indexGeneration", index.generation)
            put("coverage", coverage)
            put("missingProviderLanguages", buildJsonArray {
                missingProviderLanguages.forEach { add(JsonPrimitive(it)) }
            })
            put("total", all.size)
            put("returned", returned.size)
            put("truncated", all.size > returned.size)
            put("items", buildJsonArray {
                returned.forEach { indexed ->
                    val symbol = indexed.symbol
                    add(buildJsonObject {
                        put("id", symbol.id.value)
                        put("name", symbol.name)
                        put("symbolKind", symbol.kind.name.lowercase().replace('_', '-'))
                        put("languageId", symbol.languageId)
                        put("providerId", indexed.providerId)
                        put("backend", indexed.backend)
                        put("evidence", indexed.evidence.name.lowercase().replace('_', '-'))
                        put("completeness", indexed.completeness.name.lowercase().replace('_', '-'))
                        index.contributions.singleOrNull { it.providerId == indexed.providerId }
                            ?.provenanceHash?.let { put("provenanceHash", it) }
                        put("location", intelligenceLocationToJson(symbol.location))
                    })
                }
            })
        }
    }

    private fun javaContextRequest(params: JsonObject): org.refactorkit.java.JavaProjectContextRequest {
        val value = params["javaContext"] as? JsonObject ?: missing("javaContext")
        try {
            require(value.keys.containsAll(setOf("sdkHome", "expectedContextHash")) &&
                value.keys.all { it in setOf("sdkHome", "expectedContextHash", "contextSchemaVersion") }) {
                "javaContext requires sdkHome and expectedContextHash, with optional contextSchemaVersion"
            }
            fun field(key: String): String {
                val item = value[key]
                require(item is JsonPrimitive && item.isString) { "javaContext.$key must be a string" }
                return item.content
            }
            return org.refactorkit.java.JavaProjectContextRequest(
                expectedSnapshotHash = params.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash"),
                sdkHome = Paths.get(field("sdkHome")), expectedContextHash = field("expectedContextHash"),
                contextSchemaVersion = if ("contextSchemaVersion" in value) field("contextSchemaVersion")
                    else org.refactorkit.java.JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION,
            )
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid Java context")
        }
    }

    private fun javaEntryPointQuery(params: JsonObject, cancellation: SemanticCancellationToken): JsonElement {
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val index = workspaceIndex.snapshot() ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        return try {
            org.refactorkit.java.JavaEntryPointQueryProtocol.query(params, javaContextRequest(params),
                { scanWorkspace(root) }, cancellation, index.snapshotHash, index.generation)
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid entry-point request")
        }
    }

    private fun javaContextQuery(params: JsonObject, cancellation: SemanticCancellationToken): JsonElement {
        if (params.string("languageId") != "java") throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "javaContext requires languageId=java")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val requestId = params.string("requestId") ?: missing("requestId")
        val kind = params.string("kind") ?: missing("kind")
        val index = workspaceIndex.snapshot() ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        if (kind == "mutationSelection" && (params["sourceAuthority"] as? JsonObject)?.string("kind")!="immutable-editor-overlay") return javaMutationSelectionQuery(params, cancellation, requestId, index, root)
        if ((params["sourceAuthority"] as? JsonObject)?.string("kind") == "immutable-editor-overlay") {
            return javaEditorOverlayQuery(params, cancellation, requestId, kind, index, root)
        }
        val result = org.refactorkit.java.JavaBoundContext.read(javaContextRequest(params), { scanWorkspace(root) }, cancellation) { context ->
            if (kind in setOf("workspaceSymbols", "documentSymbols")) {
                val location = context.snapshot.files.firstOrNull { it.languageId == "java" }?.let {
                    SourceLocation(it.path, SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)))
                }
                if (location != null) when (val projected = context.adapter.semanticDefinition(context.snapshot, location, cancellation)) {
                    is JavaJdtDefinitionProjection.Available -> ensureJavaJdtContribution(context.snapshot, projected.symbols, projected.provenanceHash)
                    is JavaJdtDefinitionProjection.Refused -> return@read intelligenceRefusal(requestId, kind, index,
                        projected.diagnostic.code ?: "java.context.analysisUnavailable", projected.diagnostic.message).jsonObject
                }
            }
            val result = intelligenceQuery(JsonObject(params - "javaContext" - "kotlinDefinitionToolchain"),
                cancellation, context.snapshot, context.adapter).jsonObject
            if (kind == "definition" && "kotlinDefinitionToolchain" in params &&
                (result["error"] as? JsonObject)?.string("code") == "java.definitionKotlinSourceUnqualified"
            ) javaKotlinDefinition(params, context, requestId, index, cancellation)
            else result
        }
        return when (result) {
            is org.refactorkit.java.JavaBoundReadResult.Refused -> intelligenceRefusal(requestId, kind,
                workspaceIndex.snapshot() ?: index, result.code, result.message)
            is org.refactorkit.java.JavaBoundReadResult.Available -> {
                if (result.value.string("status") != "ready") result.value else JsonObject(result.value + mapOf(
                    "contextHash" to JsonPrimitive(result.context.view.contextHash),
                    "contextSchemaVersion" to JsonPrimitive(result.context.contextSchemaVersion),
                    "platformIdentities" to JsonArray(result.context.view.sdk.map { JsonPrimitive(it.identitySha256) }),
                ))
            }
        }
    }

    private fun javaKotlinDefinition(
        params: JsonObject,
        context: org.refactorkit.java.JavaBoundContext,
        requestId: String,
        index: WorkspaceIndex,
        cancellation: SemanticCancellationToken,
    ): JsonObject {
        val key = "kotlinDefinitionToolchain"
        val configuration = params[key] as? JsonObject
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$key must be an object")
        val request = try {
            require(configuration.keys == setOf("jdkHome", "compilerJar", "compilerClasspath")) {
                "$key requires jdkHome, compilerJar and compilerClasspath"
            }
            fun absolutePath(value: JsonElement?): Path {
                require(value is JsonPrimitive && value.isString && value.content.length in 1..4096) {
                    "$key paths must be bounded strings"
                }
                return Paths.get(value.content).also { require(it.isAbsolute) { "$key paths must be absolute" } }
            }
            val classpath = configuration["compilerClasspath"] as? JsonArray
            require(classpath != null && classpath.size in 1..64) { "$key compilerClasspath requires 1..64 paths" }
            KotlinToolchainRequest(context.snapshot.workspace.root, absolutePath(configuration["jdkHome"]),
                absolutePath(configuration["compilerJar"]), classpath.map(::absolutePath))
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid Kotlin definition toolchain")
        }
        // The ordinary Java definition route has already validated this saved cursor.
        val path = protocolRelativePath(requireNotNull(params.string("path")))
        val position = params.getValue("position").jsonObject
        val point = SourcePosition(requireNotNull(position.string("line")).toInt(),
            requireNotNull(position.string("character")).toInt())
        val cursor = SourceLocation(path, SourceRange(point, point))
        return when (val resolved = org.refactorkit.jvm.GradleKotlinDefinitionService.resolve(context, cursor, request, cancellation)) {
            is org.refactorkit.jvm.GradleKotlinDefinition.Available -> javaDefinitionResponse(requestId, index, path,
                JavaJdtDefinitionProjection.Available(resolved.location, emptyList(), resolved.provenanceHash), context.adapter)
            is org.refactorkit.jvm.GradleKotlinDefinition.Refused -> intelligenceRefusal(requestId, "definition", index,
                resolved.code, resolved.message)
        }.jsonObject
    }

    private fun javaMutationSelectionQuery(params: JsonObject, cancellation: SemanticCancellationToken,
                                           requestId: String, index: WorkspaceIndex, root: Path): JsonElement {
        if ((params["sourceAuthority"] as? JsonObject)?.let { it.keys == setOf("kind") && it.string("kind") == "saved-snapshot" } != true) {
            return intelligenceRefusal(requestId, "mutationSelection", index, "java.selection.savedSourceRequired", "Mutation selection requires exact saved-source authority")
        }
        val path = protocolRelativePath(params.string("path") ?: missing("path"))
        val value = params["position"] as? JsonObject ?: missing("position")
        val line = value.string("line")?.toIntOrNull() ?: missing("position.line")
        val character = value.string("character")?.toIntOrNull() ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Selection position must be non-negative")
        return when (val result = org.refactorkit.java.JavaBoundContext.read(javaContextRequest(params), { scanWorkspace(root) }, cancellation) { context ->
            org.refactorkit.java.JavaMutationSelectionService.select(context, path, SourcePosition(line, character))
        }) {
            is org.refactorkit.java.JavaBoundReadResult.Refused -> intelligenceRefusal(requestId, "mutationSelection", index, result.code, result.message)
            is org.refactorkit.java.JavaBoundReadResult.Available -> buildJsonObject {
                put("schemaVersion", 1);put("requestId", requestId);put("status", "ready");put("kind", "mutationSelection")
                put("snapshotHash", result.value.snapshotHash);put("indexGeneration", index.generation)
                put("providerId", JdtJavaAnalysisCache.PROVIDER_ID);put("backend", JdtJavaAnalysisCache.BACKEND)
                put("evidence", "compiler");put("completeness", "semantic");put("provenanceHash", result.value.provenanceHash)
                put("contextHash", result.context.view.contextHash);put("contextSchemaVersion", result.context.contextSchemaVersion)
                put("platformIdentities", buildJsonArray { result.context.view.sdk.forEach { add(JsonPrimitive(it.identitySha256)) } })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot");put("path", ProtocolPath.serialize(path));put("contentSha256", result.value.contentSha256) })
                put("selection", org.refactorkit.java.JavaMutationSelectionWire.render(result.value))
            }
        }
    }

    private fun javaEditorOverlayQuery(params: JsonObject, cancellation: SemanticCancellationToken,
                                       requestId: String, kind: String, index: WorkspaceIndex, root: Path): JsonElement {
        if (kind == "diagnostics" && (requestId.isBlank() || requestId.length > ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS ||
                params.string("priority") !in setOf(null, "interactive", "normal", "background"))) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Java editor diagnostics request identity or priority is invalid")
        val queryKind = when (kind) {
            "diagnostics" -> org.refactorkit.java.JavaEditorQueryKind.DIAGNOSTICS
            "hover" -> org.refactorkit.java.JavaEditorQueryKind.HOVER
            "definition" -> org.refactorkit.java.JavaEditorQueryKind.DEFINITION
            "references" -> org.refactorkit.java.JavaEditorQueryKind.REFERENCES
            "typeDefinition", "typeHierarchy", "callHierarchy", "implementations" -> org.refactorkit.java.JavaEditorQueryKind.DOCUMENT_SYMBOLS
            "documentSymbols" -> org.refactorkit.java.JavaEditorQueryKind.DOCUMENT_SYMBOLS
            "workspaceSymbols" -> org.refactorkit.java.JavaEditorQueryKind.WORKSPACE_SYMBOLS
            "mutationSelection" -> org.refactorkit.java.JavaEditorQueryKind.MUTATION_SELECTION
            else -> return intelligenceRefusal(requestId, kind, index, "intelligence.overlayKindUnsupported", "Java editor query kind is not supported")
        }
        val path = protocolRelativePath(params.string("path") ?: missing("path"))
        val position = if (kind in setOf("hover", "definition", "references", "typeDefinition", "typeHierarchy", "callHierarchy", "implementations", "mutationSelection")) {
            val value = params["position"] as? JsonObject ?: missing("position")
            val line = value.string("line")?.toIntOrNull() ?: missing("position.line")
            val character = value.string("character")?.toIntOrNull() ?: missing("position.character")
            if (line < 0 || character < 0) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "position must be non-negative")
            SourcePosition(line, character)
        } else SourcePosition(0, 0)
        val maximum = if (kind == "diagnostics") ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS else if (kind in setOf("references", "callHierarchy")) {
            ProtocolLimits.MAX_REFERENCE_RESULTS
        } else ProtocolLimits.MAX_SYMBOL_RESULTS
        val limit = if (kind == "diagnostics" && "limit" in params) {
            val value = params["limit"] as? JsonPrimitive
            value?.takeUnless { it.isString }?.intOrNull ?: throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "Java editor diagnostics limit must be an integer")
        } else params.string("limit")?.toIntOrNull() ?: maximum
        val query = params.string("query").orEmpty()
        if (limit !in 1..maximum || query.length > ProtocolLimits.MAX_INTELLIGENCE_QUERY_CHARS) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Java overlay query limit or search text exceeds its bound")
        val includeDeclaration = params.string("includeDeclaration")?.toBooleanStrictOrNull()
            ?: if ("includeDeclaration" in params) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be boolean") else true
        val expectedGeneration = params.string("expectedIndexGeneration")?.toLongOrNull()
        if (kind == "diagnostics" && "expectedIndexGeneration" in params && (expectedGeneration == null || expectedGeneration < 1)) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Expected index generation must be a positive integer")
        if (expectedGeneration != null && expectedGeneration != index.generation) return intelligenceRefusal(requestId, kind, index, "intelligence.indexStale", "Expected index generation is stale")
        return when (val result = javaEditorOverlaySession.query(javaContextRequest(params),
            org.refactorkit.java.JavaEditorQueryRequest(queryKind, path, position, limit, includeDeclaration, query),
            { scanWorkspace(root) }, { saved -> parseIntelligenceOverlay(params.getValue("sourceAuthority").jsonObject, saved, "java") }, cancellation)) {
            is org.refactorkit.java.JavaEditorQueryResult.Refused -> intelligenceRefusal(requestId, kind, index, result.code, result.message)
            is org.refactorkit.java.JavaEditorQueryResult.Available -> {
                val response = if (kind in setOf("typeDefinition", "typeHierarchy", "callHierarchy", "implementations")) {
                    val direction = javaNavigationDirection(kind, params)
                    val location = SourceLocation(path, SourceRange(position, position))
                    when (val projection = result.adapter.semanticNavigation(
                        result.overlay.providerSnapshot, javaNavigationKind(kind), location, direction,
                        limit, cancellation,
                    )) {
                        is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> intelligenceRefusal(
                            requestId, kind, index,
                            projection.diagnostic.code ?: "intelligence.${kind}Unavailable",
                            projection.diagnostic.message,
                        ).jsonObject
                        else -> javaSemanticNavigationResponse(
                            requestId, index, path, projection, result.adapter,
                            overlaySourceAuthority(result.overlay).jsonObject,
                        )
                    }
                } else when (val payload = result.payload) {
                    is org.refactorkit.java.JavaEditorQueryPayload.Diagnostics -> javaEditorDiagnosticsResponse(requestId, index, payload)
                    is org.refactorkit.java.JavaEditorQueryPayload.Hover -> javaHoverResponse(requestId, index, path, payload.value, result.adapter).jsonObject
                    is org.refactorkit.java.JavaEditorQueryPayload.Definition -> javaDefinitionResponse(requestId, index, path, payload.value, result.adapter).jsonObject
                    is org.refactorkit.java.JavaEditorQueryPayload.References -> javaReferencesResponse(requestId, index, path, payload.value, result.adapter).jsonObject
                    is org.refactorkit.java.JavaEditorQueryPayload.Symbols -> javaEditorSymbolsResponse(requestId, kind, index, payload)
                    is org.refactorkit.java.JavaEditorQueryPayload.MutationSelection -> buildJsonObject {
                        put("schemaVersion",1);put("requestId",requestId);put("status","ready");put("kind","mutationSelection")
                        put("snapshotHash",index.snapshotHash);put("indexGeneration",index.generation)
                        put("providerId",JdtJavaAnalysisCache.PROVIDER_ID);put("backend",JdtJavaAnalysisCache.BACKEND)
                        put("evidence","compiler");put("completeness","semantic")
                        put("provenanceHash",payload.value.selection.provenanceHash)
                        put("selection",org.refactorkit.java.JavaEditorMutationSelectionWire.render(payload.value))
                    }
                }
                JsonObject(response + mapOf(
                    "contextHash" to JsonPrimitive(result.context.view.contextHash),
                    "contextSchemaVersion" to JsonPrimitive(result.context.contextSchemaVersion),
                    "platformIdentities" to JsonArray(result.context.view.sdk.map { JsonPrimitive(it.identitySha256) }),
                    "providerSnapshotHash" to JsonPrimitive(result.overlay.providerSnapshot.hash),
                    "sourceAuthority" to overlaySourceAuthority(result.overlay),
                ))
            }
        }
    }

    private fun javaEditorSymbolsResponse(requestId: String, kind: String, index: WorkspaceIndex,
                                          payload: org.refactorkit.java.JavaEditorQueryPayload.Symbols): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", kind)
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation); put("coverage", "semantic")
        put("missingProviderLanguages", buildJsonArray { }); put("total", payload.total); put("returned", payload.values.size)
        put("truncated", payload.total > payload.values.size)
        put("items", buildJsonArray { payload.values.forEach { symbol -> add(buildJsonObject {
            put("id", symbol.id.value); put("name", symbol.name); put("symbolKind", symbol.kind.name.lowercase().replace('_', '-'))
            put("languageId", "java"); put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
            put("evidence", "compiler"); put("completeness", "semantic"); put("provenanceHash", payload.provenanceHash)
            put("location", intelligenceLocationToJson(symbol.location))
        }) } })
    }

    private fun javaEditorDiagnosticsResponse(requestId: String, index: WorkspaceIndex,
                                             payload: org.refactorkit.java.JavaEditorQueryPayload.Diagnostics): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "diagnostics")
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation)
        put("providerId", payload.providerId); put("backend", payload.backend)
        put("evidence", "compiler"); put("coverage", "compiler-errors"); put("complete", true)
        put("provenanceHash", payload.provenanceHash); put("analyzedSourceCount", payload.analyzedSourceCount)
        put("total", payload.values.size)
        put("items", buildJsonArray { payload.values.forEach { diagnostic -> add(buildJsonObject {
            put("severity", "error"); put("code", diagnostic.code); put("message", diagnostic.message)
            put("location", intelligenceLocationToJson(requireNotNull(diagnostic.location)))
        }) } })
    }

    private fun javaNavigationKind(kind: String) = when (kind) {
        "typeDefinition" -> org.refactorkit.java.JavaJdtNavigationKind.TYPE_DEFINITION
            "typeHierarchy" -> org.refactorkit.java.JavaJdtNavigationKind.TYPE_HIERARCHY
            "callHierarchy" -> org.refactorkit.java.JavaJdtNavigationKind.CALL_HIERARCHY
            "implementations" -> org.refactorkit.java.JavaJdtNavigationKind.IMPLEMENTATIONS
        else -> error("unsupported Java navigation kind")
    }

    private fun javaNavigationDirection(kind: String, params: JsonObject): String {
        val direction = when (kind) {
            "typeDefinition" -> "target"
            "implementations" -> "all"
            else -> params.string("direction") ?: missing("direction")
        }
        val valid = when (kind) {
            "typeDefinition" -> direction == "target"
            "typeHierarchy" -> direction in setOf("supertypes", "subtypes", "both")
            "callHierarchy" -> direction in setOf("incoming", "outgoing")
            "implementations" -> direction == "all"
            else -> false
        }
        if (!valid) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$kind direction is invalid")
        return direction
    }

    private fun javaNavigationSymbols(projection: org.refactorkit.java.JavaJdtNavigationProjection) = when (projection) {
        is org.refactorkit.java.JavaJdtNavigationProjection.TypeDefinition -> projection.symbols
        is org.refactorkit.java.JavaJdtNavigationProjection.TypeHierarchy -> projection.symbols
        is org.refactorkit.java.JavaJdtNavigationProjection.CallHierarchy -> projection.symbols
        is org.refactorkit.java.JavaJdtNavigationProjection.Implementations -> projection.symbols
        is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> emptyList()
    }

    private fun javaNavigationProvenance(projection: org.refactorkit.java.JavaJdtNavigationProjection) = when (projection) {
        is org.refactorkit.java.JavaJdtNavigationProjection.TypeDefinition -> projection.provenanceHash
        is org.refactorkit.java.JavaJdtNavigationProjection.TypeHierarchy -> projection.provenanceHash
        is org.refactorkit.java.JavaJdtNavigationProjection.CallHierarchy -> projection.provenanceHash
        is org.refactorkit.java.JavaJdtNavigationProjection.Implementations -> projection.provenanceHash
        is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> ""
    }

    private fun javaSemanticNavigationResponse(
        requestId: String,
        index: WorkspaceIndex,
        path: Path,
        projection: org.refactorkit.java.JavaJdtNavigationProjection,
        javaAdapter: JavaLanguageAdapter,
        sourceAuthority: JsonObject,
    ): JsonObject = buildJsonObject {
        val kind = when (projection) {
            is org.refactorkit.java.JavaJdtNavigationProjection.TypeDefinition -> "typeDefinition"
            is org.refactorkit.java.JavaJdtNavigationProjection.TypeHierarchy -> "typeHierarchy"
            is org.refactorkit.java.JavaJdtNavigationProjection.CallHierarchy -> "callHierarchy"
            is org.refactorkit.java.JavaJdtNavigationProjection.Implementations -> "implementations"
            is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> error("refused navigation has no ready response")
        }
        val provenanceHash = javaNavigationProvenance(projection)
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", kind)
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation)
        put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
        put("evidence", "compiler"); put("provenanceHash", provenanceHash)
        put("sourceAuthority", sourceAuthority)
        when (projection) {
            is org.refactorkit.java.JavaJdtNavigationProjection.TypeDefinition -> {
                put("completeness", "semantic")
                put("target", javaSemanticNavigationSymbolJson(projection.target, provenanceHash))
                put("locations", buildJsonArray { add(intelligenceLocationToJson(projection.target.location)) })
                put("scope", "workspace-source")
            }
            is org.refactorkit.java.JavaJdtNavigationProjection.TypeHierarchy -> {
                put("completeness", if (projection.complete) "semantic" else "partial")
                put("root", javaSemanticNavigationSymbolJson(projection.root, provenanceHash))
                put("direction", projection.direction); put("scope", "workspace-source")
                put("complete", projection.complete); put("warningCount", projection.warningCount)
                put("total", projection.total); put("returned", projection.items.size); put("truncated", projection.truncated)
                put("items", buildJsonArray { projection.items.forEach { item -> add(buildJsonObject {
                    put("relation", item.relation); put("symbol", javaSemanticNavigationSymbolJson(item.symbol, provenanceHash))
                }) } })
            }
            is org.refactorkit.java.JavaJdtNavigationProjection.CallHierarchy -> {
                put("completeness", "partial")
                put("root", javaSemanticNavigationSymbolJson(projection.root, provenanceHash))
                put("direction", projection.direction); put("scope", "workspace-direct-invocations")
                put("targetScope", "workspace-source+resolved-classpath")
                put("complete", false); put("warningCount", projection.warningCount)
                put("total", projection.total); put("returned", projection.items.size); put("truncated", projection.truncated)
                put("items", buildJsonArray { projection.items.forEach { item ->
                    val sourceMethod = item.method
                    add(buildJsonObject {
                    put("method", sourceMethod?.let { javaSemanticNavigationSymbolJson(it, provenanceHash) } ?: JsonNull)
                    put("methodIdentity", buildJsonObject {
                        put("schemaVersion", "java-method-binding-identity-v1")
                        put("name", item.methodIdentity.name)
                        put("qualifiedName", item.methodIdentity.qualifiedName)
                        put("bindingKey", item.methodIdentity.bindingKey)
                        item.methodIdentity.jvmIdentity?.let { identity ->
                            put("jvmIdentity", buildJsonObject {
                                put("kind", identity.kind.name.lowercase())
                                put("ownerBinaryName", identity.ownerBinaryName)
                                put("memberName", identity.memberName)
                                put("descriptor", identity.descriptor)
                            })
                        } ?: put("jvmIdentity", JsonNull)
                    })
                    put("sourceNavigation", if (sourceMethod != null) buildJsonObject {
                        put("status", "available")
                        put("location", intelligenceLocationToJson(sourceMethod.location))
                    } else buildJsonObject {
                        put("status", "unavailable")
                        put("reason", "classpathSourceUnavailable")
                        put("location", JsonNull)
                    })
                    put("callSites", buildJsonArray { item.callSites.forEach { add(intelligenceLocationToJson(it)) } })
                }) } })
            }
            is org.refactorkit.java.JavaJdtNavigationProjection.Implementations -> {
                put("completeness", if (projection.complete) "semantic" else "partial")
                put("root", javaSemanticNavigationSymbolJson(projection.root, provenanceHash))
                put("targetKind", projection.targetKind); put("scope", "workspace-source")
                put("complete", projection.complete); put("warningCount", projection.warningCount)
                put("total", projection.total); put("returned", projection.items.size); put("truncated", projection.truncated)
                put("items", buildJsonArray { projection.items.forEach { item -> add(buildJsonObject {
                    put("relation", item.relation)
                    put("symbol", javaSemanticNavigationSymbolJson(item.symbol, provenanceHash))
                }) } })
            }
            is org.refactorkit.java.JavaJdtNavigationProjection.Refused -> Unit
        }
        val source = index.sources.singleOrNull { it.path.normalize() == path.normalize() }
        if (source != null && sourceAuthority.string("kind") == "saved-snapshot") {
            put("sourceAuthority", buildJsonObject {
                put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(path))
                put("contentSha256", source.contentSha256)
            })
        }
        val cache = javaAdapter.semanticCacheStatus()
        put("cache", buildJsonObject { put("entries", cache.entries); put("hits", cache.hits); put("misses", cache.misses) })
    }

    private fun javaSemanticNavigationSymbolJson(symbol: Symbol, provenanceHash: String) = buildJsonObject {
        put("id", symbol.id.value); put("name", symbol.name)
        put("symbolKind", symbol.kind.name.lowercase().replace('_', '-'))
        put("languageId", "java"); put("providerId", JdtJavaAnalysisCache.PROVIDER_ID)
        put("backend", JdtJavaAnalysisCache.BACKEND); put("evidence", "compiler")
        put("completeness", "semantic"); put("provenanceHash", provenanceHash)
        put("location", intelligenceLocationToJson(symbol.location))
    }

    private fun ensureJavaJdtContribution(
        snapshot: ProjectSnapshot,
        symbols: List<org.refactorkit.core.Symbol>,
        provenanceHash: String,
    ): WorkspaceIndex {
        val current = workspaceIndex.snapshot() ?: error("workspace index is not open")
        val existing = current.contributions.singleOrNull { it.providerId == JdtJavaAnalysisCache.PROVIDER_ID }
        if (existing?.provenanceHash == provenanceHash && existing.snapshotHash == snapshot.hash) return current
        val limit = ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS
        return workspaceIndex.contribute(WorkspaceSymbolContribution(
            providerId = JdtJavaAnalysisCache.PROVIDER_ID,
            languageId = "java",
            backend = JdtJavaAnalysisCache.BACKEND,
            evidence = SemanticEvidenceKind.COMPILER,
            completeness = WorkspaceIndexCompleteness.SEMANTIC,
            snapshotHash = snapshot.hash,
            symbols = symbols.take(limit),
            truncated = symbols.size > limit,
            provenanceHash = provenanceHash,
        ))
    }

    private fun javaHoverResponse(
        requestId: String,
        index: WorkspaceIndex,
        path: Path,
        hover: JavaJdtHoverProjection.Available,
        javaAdapter: JavaLanguageAdapter = adapter,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "hover")
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation)
        put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
        put("evidence", "compiler"); put("completeness", "semantic"); put("provenanceHash", hover.provenanceHash)
        put("range", hover.range?.let(::rangeToJson) ?: kotlinx.serialization.json.JsonNull)
        put("contents", buildJsonArray { hover.sections.forEach { section -> add(buildJsonObject {
            put("format", section.format.name.lowercase().replace('_', '-')); put("value", section.value)
        }) } })
        put("complete", hover.complete); put("warningCount", hover.warningCount)
        val source = index.sources.single { it.path == path.normalize() }
        put("sourceAuthority", buildJsonObject {
            put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(source.path))
            put("contentSha256", source.contentSha256)
        })
        val cache = javaAdapter.semanticCacheStatus()
        put("cache", buildJsonObject {
            put("entries", cache.entries); put("hits", cache.hits); put("misses", cache.misses)
        })
    }

    private fun kotlinUsageNavigation(
        params: JsonObject,
        requestId: String,
        kind: String,
        index: WorkspaceIndex,
        path: Path,
        limit: Int,
    ): JsonElement {
        if ((params["sourceAuthority"] as? JsonObject)?.let { it.string("kind") == "saved-snapshot" && it.keys == setOf("kind") } != true) {
            return intelligenceRefusal(
                requestId, kind, index, "intelligence.${kind}SavedSnapshotRequired",
                "Kotlin $kind requires saved-snapshot source authority",
            )
        }
        val requestedSource = index.sources.singleOrNull {
            it.languageId == "kotlin" && it.path.normalize() == path.normalize()
        } ?: return intelligenceRefusal(
            requestId, kind, index, "kotlin.usageSourceMissing",
            "Kotlin usage navigation path is not an attested Kotlin source",
        )
        val lease = params.string("semanticLease") ?: missing("semanticLease")
        if (kotlinToolchain == null || lease != kotlinSemanticLease) return intelligenceRefusal(
            requestId, kind, index, "intelligence.semanticLeaseStale", "Kotlin semantic lease is missing or stale",
        )
        val positionObject = params["position"] as? JsonObject ?: missing("position")
        val line = positionObject.string("line")?.toIntOrNull() ?: missing("position.line")
        val character = positionObject.string("character")?.toIntOrNull() ?: missing("position.character")
        if (line < 0 || character < 0) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "$kind position must be non-negative",
        )
        val point = SourcePosition(line, character)
        val result = kotlinAdapter.compilerSymbols(requireSnapshot())
        if (result !is KotlinCompilerSymbolsResult.Available) {
            val failure = when (result) {
                is KotlinCompilerSymbolsResult.Refused -> result.reason
                is KotlinCompilerSymbolsResult.Error -> result.failure
                else -> error("unreachable")
            }
            return intelligenceRefusal(
                requestId, kind, index, failure.code ?: "kotlin.usagesUnavailable", failure.message,
            )
        }
        workspaceIndex.contribute(WorkspaceSymbolContribution(
            providerId = "kotlin-k2-jvm-declarations-v1",
            languageId = "kotlin",
            backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
            evidence = SemanticEvidenceKind.COMPILER,
            completeness = WorkspaceIndexCompleteness.DECLARATIONS,
            snapshotHash = requireSnapshot().hash,
            symbols = result.index.symbols,
            provenanceHash = result.attestation.toolchainProjectionHash,
        ))
        fun contains(location: SourceLocation): Boolean {
            if (location.path.normalize() != path.normalize()) return false
            val start = location.range.start
            val end = location.range.end
            val atOrAfterStart = point.line > start.line || point.line == start.line && point.character >= start.character
            val beforeEnd = point.line < end.line || point.line == end.line && point.character < end.character
            return atOrAfterStart && beforeEnd
        }
        val targetIds = buildList {
            result.index.symbols.filter { contains(it.location) }.forEach { add(it.id) }
            result.usages.filter { contains(it.location) }.forEach { add(it.targetId) }
        }.distinct()
        if (targetIds.size != 1) return intelligenceRefusal(
            requestId, kind, workspaceIndex.snapshot() ?: index,
            if (targetIds.isEmpty()) "kotlin.usageNotFound" else "kotlin.usageAmbiguous",
            if (targetIds.isEmpty()) "No compiler-proven Kotlin declaration or usage exists at the requested position"
            else "Kotlin declaration or usage position resolved ambiguously",
        )
        val target = result.index.symbols.singleOrNull { it.id == targetIds.single() }
            ?: return intelligenceRefusal(
                requestId, kind, workspaceIndex.snapshot() ?: index,
                "kotlin.usageTargetMissing", "Resolved Kotlin target is absent from the declaration index",
            )
        val updated = workspaceIndex.snapshot() ?: index
        val source = updated.sources.singleOrNull { it.path == requestedSource.path }
            ?: return intelligenceRefusal(
                requestId, kind, updated, "kotlin.usageSourceMissing",
                "Kotlin usage navigation source disappeared during analysis",
            )
        return buildJsonObject {
            put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", kind)
            put("snapshotHash", updated.snapshotHash); put("indexGeneration", updated.generation)
            put("providerId", "kotlin-k2-fir-usages-v1"); put("backend", KotlinCompilerDiagnostics.SYMBOL_BACKEND)
            put("evidence", "compiler"); put("provenanceHash", result.attestation.toolchainProjectionHash)
            if (kind == "definition") {
                put("completeness", "semantic")
                put("locations", buildJsonArray { add(intelligenceLocationToJson(target.location)) })
            } else {
                val includeDeclaration = params.string("includeDeclaration")?.toBooleanStrictOrNull()
                    ?: if ("includeDeclaration" in params) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be boolean",
                    ) else true
                val all = buildList {
                    if (includeDeclaration) add(target.location)
                    result.usages.filter { it.targetId == target.id }.forEach { add(it.location) }
                }.distinct().sortedWith(compareBy({ ProtocolPath.serialize(it.path) },
                    { it.range.start.line }, { it.range.start.character }))
                val returned = all.take(limit)
                put("completeness", "partial"); put("total", all.size); put("returned", returned.size)
                put("truncated", all.size > returned.size); put("complete", false); put("warningCount", 1)
                put("references", buildJsonArray { returned.forEach { add(intelligenceLocationToJson(it)) } })
            }
            put("sourceAuthority", buildJsonObject {
                put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(source.path))
                put("contentSha256", source.contentSha256)
            })
            put("cache", buildJsonObject { put("entries", 1); put("hits", 0); put("misses", 1) })
        }
    }

    private fun javaReferencesResponse(
        requestId: String,
        index: WorkspaceIndex,
        path: Path,
        references: JavaJdtReferencesProjection.Available,
        javaAdapter: JavaLanguageAdapter = adapter,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "references")
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation)
        put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
        put("evidence", "compiler"); put("completeness", "semantic"); put("provenanceHash", references.provenanceHash)
        put("total", references.total); put("returned", references.references.size); put("truncated", references.truncated)
        put("complete", references.complete); put("warningCount", references.warningCount)
        put("references", buildJsonArray { references.references.forEach { reference ->
            add(intelligenceLocationToJson(reference.location))
        } })
        val source = index.sources.single { it.path == path.normalize() }
        put("sourceAuthority", buildJsonObject {
            put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(source.path))
            put("contentSha256", source.contentSha256)
        })
        val cache = javaAdapter.semanticCacheStatus()
        put("cache", buildJsonObject {
            put("entries", cache.entries); put("hits", cache.hits); put("misses", cache.misses)
        })
    }

    private fun javaDefinitionResponse(
        requestId: String,
        index: WorkspaceIndex,
        path: Path,
        definition: JavaJdtDefinitionProjection.Available,
        javaAdapter: JavaLanguageAdapter = adapter,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "definition")
        put("snapshotHash", index.snapshotHash); put("indexGeneration", index.generation)
        put("providerId", JdtJavaAnalysisCache.PROVIDER_ID); put("backend", JdtJavaAnalysisCache.BACKEND)
        put("evidence", "compiler"); put("completeness", "semantic"); put("provenanceHash", definition.provenanceHash)
        put("locations", buildJsonArray { definition.location?.let { add(intelligenceLocationToJson(it)) } })
        val source = index.sources.single { it.path == path.normalize() }
        put("sourceAuthority", buildJsonObject {
            put("kind", "saved-snapshot"); put("path", ProtocolPath.serialize(source.path))
            put("contentSha256", source.contentSha256)
        })
        val cache = javaAdapter.semanticCacheStatus()
        put("cache", buildJsonObject {
            put("entries", cache.entries); put("hits", cache.hits); put("misses", cache.misses)
        })
    }

    private fun parseIntelligenceOverlay(
        authority: JsonObject,
        saved: ProjectSnapshot,
        languageId: String,
    ): ImmutableEditorOverlay {
        if (authority.keys != setOf("kind", "documents")) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "immutable-editor-overlay requires only kind and documents",
        )
        val values = authority["documents"] as? JsonArray ?: missing("sourceAuthority.documents")
        val documents = values.map { element ->
            val value = element as? JsonObject ?: throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "editor overlay document must be an object",
            )
            if (value.keys != setOf("path", "version", "content")) throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS, "editor overlay document fields are invalid",
            )
            val path = protocolRelativePath(value.string("path") ?: missing("sourceAuthority.documents.path"))
            val version = (value["version"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "editor overlay version must be an integer")
            val content = value.string("content") ?: missing("sourceAuthority.documents.content")
            ImmutableEditorOverlayDocument(path, version, content)
        }
        return try {
            ImmutableEditorOverlay.create(saved, documents, expectedLanguageId = languageId)
        } catch (failure: RuntimeException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "editor overlay is invalid")
        }
    }

    private fun overlayDocumentSymbolsResponse(
        requestId: String,
        index: WorkspaceIndex,
        languageId: String,
        overlay: ImmutableEditorOverlay,
        projection: TypeScriptSymbolProjection.Available,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1)
        put("requestId", requestId)
        put("status", "ready")
        put("kind", "documentSymbols")
        put("snapshotHash", index.snapshotHash)
        put("providerSnapshotHash", overlay.providerSnapshot.hash)
        put("indexGeneration", index.generation)
        put("coverage", "declarations")
        put("missingProviderLanguages", buildJsonArray { })
        put("total", projection.index.symbols.size)
        put("returned", projection.index.symbols.size)
        put("truncated", projection.truncated)
        put("sourceAuthority", buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("baseSnapshotHash", overlay.baseSnapshotHash)
            put("overlayHash", overlay.overlayHash)
            put("documents", buildJsonArray {
                overlay.documents.forEach { document -> add(buildJsonObject {
                    put("path", ProtocolPath.serialize(document.path))
                    put("version", document.version)
                    put("contentSha256", overlay.authority(document.path)!!.contentSha256)
                }) }
            })
        })
        put("items", buildJsonArray {
            projection.index.symbols.forEach { symbol -> add(buildJsonObject {
                put("id", symbol.id.value)
                put("name", symbol.name)
                put("symbolKind", symbol.kind.name.lowercase().replace('_', '-'))
                put("languageId", languageId)
                put("providerId", typeScriptIndexProviderId(languageId))
                put("backend", TypeScriptToolchainProvenance.PROVIDER_ID)
                put("evidence", "language-server")
                put("completeness", "declarations")
                put("provenanceHash", projection.provenanceHash)
                put("location", intelligenceLocationToJson(symbol.location))
            }) }
        })
    }

    private fun overlayCompletionResponse(
        requestId: String,
        index: WorkspaceIndex,
        languageId: String,
        overlay: ImmutableEditorOverlay,
        completion: TypeScriptCompletionProjection.Available,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "completion")
        put("snapshotHash", index.snapshotHash); put("providerSnapshotHash", overlay.providerSnapshot.hash)
        put("indexGeneration", index.generation); put("incomplete", completion.incomplete)
        put("providerId", typeScriptIndexProviderId(languageId)); put("backend", TypeScriptToolchainProvenance.PROVIDER_ID)
        put("evidence", "language-server"); put("provenanceHash", completion.provenanceHash)
        put("items", buildJsonArray { completion.items.forEach { item -> add(buildJsonObject {
            put("label", item.label); put("symbolKind", item.kind.name.lowercase().replace('_', '-'))
            item.detail?.let { put("detail", it) }; item.documentation?.let { put("documentation", it) }
            item.sortText?.let { put("sortText", it) }; item.filterText?.let { put("filterText", it) }
            item.insertText?.let { put("insertText", it) }
            put("insertTextFormat", item.insertTextFormat.name.lowercase().replace('_', '-'))
            put("commitCharacters", buildJsonArray { item.commitCharacters.forEach { add(JsonPrimitive(it)) } })
            item.replacementRange?.let { put("replacementRange", rangeToJson(it)) }
            put("additionalTextEdits", buildJsonArray { item.additionalTextEdits.forEach { edit -> add(buildJsonObject {
                put("range", rangeToJson(edit.range)); put("newText", edit.newText)
            }) } })
        }) } })
        put("sourceAuthority", overlaySourceAuthority(overlay))
    }

    private fun overlaySourceAuthority(overlay: ImmutableEditorOverlay): JsonElement = buildJsonObject {
        put("kind", "immutable-editor-overlay"); put("baseSnapshotHash", overlay.baseSnapshotHash)
        put("overlayHash", overlay.overlayHash)
        put("documents", buildJsonArray { overlay.documents.forEach { document -> add(buildJsonObject {
            put("path", ProtocolPath.serialize(document.path)); put("version", document.version)
            put("contentSha256", overlay.authority(document.path)!!.contentSha256)
        }) } })
    }

    private fun overlaySignatureHelpResponse(
        requestId: String,
        index: WorkspaceIndex,
        languageId: String,
        overlay: ImmutableEditorOverlay,
        signature: TypeScriptSignatureHelpProjection.Available,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "signatureHelp")
        put("snapshotHash", index.snapshotHash); put("providerSnapshotHash", overlay.providerSnapshot.hash)
        put("indexGeneration", index.generation)
        put("providerId", typeScriptIndexProviderId(languageId)); put("backend", TypeScriptToolchainProvenance.PROVIDER_ID)
        put("evidence", "language-server"); put("provenanceHash", signature.provenanceHash)
        put("activeSignature", signature.activeSignature?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("activeParameter", signature.activeParameter?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull)
        put("signatures", buildJsonArray { signature.signatures.forEach { item -> add(buildJsonObject {
            put("label", item.label); item.documentation?.let { put("documentation", it) }
            put("parameters", buildJsonArray { item.parameters.forEach { parameter -> add(buildJsonObject {
                put("labelStart", parameter.labelStart); put("labelEnd", parameter.labelEnd)
                parameter.documentation?.let { put("documentation", it) }
            }) } })
        }) } })
        put("sourceAuthority", overlaySourceAuthority(overlay))
    }

    private fun overlayHoverResponse(
        requestId: String,
        index: WorkspaceIndex,
        languageId: String,
        overlay: ImmutableEditorOverlay,
        hover: TypeScriptHoverProjection.Available,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("status", "ready"); put("kind", "hover")
        put("snapshotHash", index.snapshotHash); put("providerSnapshotHash", overlay.providerSnapshot.hash)
        put("indexGeneration", index.generation)
        put("providerId", typeScriptIndexProviderId(languageId)); put("backend", TypeScriptToolchainProvenance.PROVIDER_ID)
        put("evidence", "language-server"); put("provenanceHash", hover.provenanceHash)
        put("range", hover.range?.let(::rangeToJson) ?: kotlinx.serialization.json.JsonNull)
        put("contents", buildJsonArray { hover.sections.forEach { section -> add(buildJsonObject {
            put("format", section.format.name.lowercase().replace('_', '-')); put("value", section.value)
        }) } })
        put("sourceAuthority", overlaySourceAuthority(overlay))
    }

    private fun rangeToJson(range: SourceRange): JsonElement = buildJsonObject {
        put("start", buildJsonObject { put("line", range.start.line); put("character", range.start.character) })
        put("end", buildJsonObject { put("line", range.end.line); put("character", range.end.character) })
    }

    private fun intelligenceRefusal(
        requestId: String,
        kind: String,
        index: WorkspaceIndex,
        code: String,
        message: String,
    ): JsonElement = buildJsonObject {
        put("schemaVersion", 1)
        put("requestId", requestId)
        put("status", "refused")
        put("kind", kind)
        put("snapshotHash", index.snapshotHash)
        put("indexGeneration", index.generation)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun intelligenceLocationToJson(location: SourceLocation): JsonElement = buildJsonObject {
        put("path", ProtocolPath.serialize(location.path))
        put("range", buildJsonObject {
            put("start", buildJsonObject {
                put("line", location.range.start.line)
                put("character", location.range.start.character)
            })
            put("end", buildJsonObject {
                put("line", location.range.end.line)
                put("character", location.range.end.character)
            })
        })
    }

    private fun protocolRelativePath(raw: String): Path = try {
        ProtocolPath.parseRelative(raw)
    } catch (failure: IllegalArgumentException) {
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "path is invalid")
    }

    private fun projectSummary(): JsonElement {
        val snap = requireSnapshot()
        val sortedModels = snap.buildModels.sortedBy { it.providerId }
        var remainingModules = ProtocolLimits.MAX_BUILD_MODULES
        var remainingDiagnostics = ProtocolLimits.MAX_BUILD_MODEL_DIAGNOSTICS
        val models = sortedModels.take(ProtocolLimits.MAX_BUILD_MODELS).map { model ->
            val sortedDiagnostics = model.diagnostics.sortedWith(compareBy({ it.moduleId.orEmpty() }, { it.code }))
            val diagnostics = sortedDiagnostics.take(remainingDiagnostics).map { diagnostic ->
                BuildModelDiagnosticSummaryDto(
                    diagnostic.code,
                    diagnostic.severity.name.lowercase(),
                    diagnostic.moduleId,
                )
            }
            remainingDiagnostics -= diagnostics.size
            val sortedModules = model.modules.sortedBy { it.id }
            val modules = sortedModules.take(remainingModules).map { module ->
                val sortedSourceSets = module.sourceSets.sortedBy { it.id }
                val sourceSets = sortedSourceSets.take(ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE).map { sourceSet ->
                    val sourceRoots = sourceSet.sourceRoots.sortedBy(ProtocolPath::serialize)
                    val generatedRoots = sourceSet.generatedSourceRoots.sortedBy(ProtocolPath::serialize)
                    val outputs = sourceSet.outputDirectories.sortedBy(ProtocolPath::serialize)
                    val dependencies = sourceSet.moduleDependencies
                        .sortedWith(compareBy({ it.targetModuleId }, { it.scope.name }))
                    BuildSourceSetSummaryDto(
                        id = sourceSet.id,
                        kind = sourceSet.kind.name.lowercase().replace('_', '-'),
                        sourceRoots = sourceRoots.take(ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET)
                            .map(ProtocolPath::serialize),
                        generatedSourceRoots = generatedRoots.take(ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET)
                            .map(ProtocolPath::serialize),
                        outputDirectories = outputs.take(ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET)
                            .map(ProtocolPath::serialize),
                        moduleDependencies = dependencies
                            .take(ProtocolLimits.MAX_BUILD_MODULE_DEPENDENCIES_PER_SOURCE_SET)
                            .map { BuildModuleDependencySummaryDto(it.targetModuleId, it.scope.name.lowercase()) },
                        truncated = sourceRoots.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET ||
                            generatedRoots.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET ||
                            outputs.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET ||
                            dependencies.size > ProtocolLimits.MAX_BUILD_MODULE_DEPENDENCIES_PER_SOURCE_SET,
                    )
                }
                BuildModuleSummaryDto(
                    id = module.id,
                    name = module.name,
                    root = relativeModuleRoot(snap, module.root),
                    sourceSets = sourceSets,
                    truncated = sortedSourceSets.size > ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE ||
                        sourceSets.any(BuildSourceSetSummaryDto::truncated),
                    buildRoot = module.attributes["gradle.buildRoot"],
                    projectPath = module.attributes["gradle.projectPath"],
                )
            }
            remainingModules -= modules.size
            BuildModelSummaryDto(
                providerId = model.providerId,
                status = model.status.name.lowercase().replace('_', '-'),
                ecosystem = model.attributes["ecosystem"].orEmpty(),
                strategy = model.attributes["strategy"].orEmpty(),
                providers = model.attributes["providers"] ?: model.attributes["ecosystem"].orEmpty(),
                buildCodeExecution = model.attributes["buildCodeExecution"].orEmpty(),
                credentialsAccess = model.attributes["credentialsAccess"].orEmpty(),
                networkDefault = model.attributes["networkDefault"].orEmpty(),
                networkAccess = model.attributes["networkAccess"].orEmpty(),
                activeProfiles = csvAttribute(model.attributes["activeProfiles"]),
                inactiveProfiles = csvAttribute(model.attributes["inactiveProfiles"]),
                diagnostics = diagnostics,
                modules = modules,
                truncated = sortedDiagnostics.size > diagnostics.size || sortedModules.size > modules.size ||
                    modules.any(BuildModuleSummaryDto::truncated),
            )
        }
        val sortedModules = snap.modules.sortedBy { it.name }
        val buildModelOwnersByRoot = snap.buildModels.flatMap { model ->
            model.modules.map { module ->
                module.root.toAbsolutePath().normalize() to (model.providerId to module)
            }
        }.groupBy({ it.first }, { it.second })
        val legacyModules = sortedModules.take(ProtocolLimits.MAX_BUILD_MODULES).map { module ->
            val owner = buildModelOwnersByRoot[module.root.toAbsolutePath().normalize()]?.singleOrNull()
            val identity = owner?.second?.attributes?.let { attributes ->
                val buildRoot = attributes["gradle.buildRoot"]
                val projectPath = attributes["gradle.projectPath"]
                if (buildRoot != null && projectPath != null) buildRoot to projectPath else null
            }
            ProjectModuleSummaryDto(
                name = module.name,
                root = relativeModuleRoot(snap, module.root),
                id = owner?.second?.id,
                providerId = owner?.first,
                buildRoot = identity?.first,
                projectPath = identity?.second,
            )
        }
        return PROTOCOL_JSON.encodeToJsonElement(ProjectSummaryResponseDto(
            root = workspaceRoot.toString(),
            fileCount = snap.files.size,
            snapshotHash = snap.hash,
            buildModels = models,
            buildModelsTruncated = sortedModels.size > models.size || models.any(BuildModelSummaryDto::truncated),
            buildModelLimits = BuildModelSummaryLimitsDto(
                maxModels = ProtocolLimits.MAX_BUILD_MODELS,
                maxModules = ProtocolLimits.MAX_BUILD_MODULES,
                maxSourceSetsPerModule = ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE,
                maxRootsPerSourceSet = ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET,
                maxModuleDependenciesPerSourceSet = ProtocolLimits.MAX_BUILD_MODULE_DEPENDENCIES_PER_SOURCE_SET,
                maxDiagnostics = ProtocolLimits.MAX_BUILD_MODEL_DIAGNOSTICS,
            ),
            modules = legacyModules,
            modulesTruncated = sortedModules.size > legacyModules.size,
        ))
    }

    private fun projectSync(params: JsonObject?, cancellation: SemanticCancellationToken): JsonElement {
        val p = params ?: missing("params")
        requireSnapshot()
        val request = try {
            require(p.keys.all { it in setOf("buildSystem", "expectedSnapshotHash", "sdkHome", "gradleExecutable",
                "allowBuildExecution", "projectProperties", "gradleUserHome", "gradleRuntimeHome") }) { "project.sync contains unsupported parameters" }
            fun field(key: String): String {
                val value = p[key] ?: missing(key)
                require(value is JsonPrimitive && value.isString) { "$key must be a string" }
                return value.content
            }
            require(field("buildSystem") == "gradle") { "project.sync currently accepts buildSystem=gradle" }
            val execution = (p["allowBuildExecution"] as? JsonPrimitive)?.booleanOrNull
                ?: throw IllegalArgumentException("allowBuildExecution must be an explicit boolean")
            val properties = p["projectProperties"]?.let { element ->
                require(element is JsonObject) { "projectProperties must be an object" }
                element.mapValues { (_, value) -> require(value is JsonPrimitive && value.isString); value.content }
            }.orEmpty()
            org.refactorkit.java.GradleJavaSyncRequest(field("expectedSnapshotHash"), Paths.get(field("sdkHome")),
                Paths.get(field("gradleExecutable")), execution, properties,
                if ("gradleUserHome" in p) Paths.get(field("gradleUserHome")) else null,
                if ("gradleRuntimeHome" in p) Paths.get(field("gradleRuntimeHome")) else null)
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid Gradle sync request")
        }
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        return when (val result = gradleContextSession.synchronize(request, { scanWorkspace(root) }, cancellation)) {
            is org.refactorkit.java.GradleJavaSyncResult.Refused -> buildJsonObject {
                put("schemaVersion", "java-gradle-sync-v1"); put("status", "refused")
                put("error", buildJsonObject {
                    put("code", result.code); put("message", result.message)
                    result.cause?.let { cause -> put("cause", buildJsonObject {
                        put("code", cause.code); put("message", cause.message); put("evidence", "process-output")
                    }) }
                })
            }
            is org.refactorkit.java.GradleJavaSyncResult.Available -> {
                workspaceRefresh()
                buildJsonObject {
                    put("schemaVersion", "java-gradle-sync-v1"); put("status", "synchronized")
                    put("snapshotHash", requireSnapshot().hash); put("providerId", result.model.providerId)
                    put("gradleVersion", result.model.attributes.getValue("gradle.version"))
                    put("sdkHome", result.model.attributes.getValue("gradle.compilerSdkHome"))
                    put("gradleRuntimeHome", result.model.attributes.getValue("gradle.runtimeJdkHome"))
                }
            }
        }
    }

    private fun javaPlatformSource(params: JsonObject?, cancellation: SemanticCancellationToken,
                                   operation: org.refactorkit.java.JavaPlatformSourceOperation): JsonElement {
        val request = decodeJavaPlatformSource(params, operation)
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val sources = org.refactorkit.java.JavaPlatformSources()
        return if (request.editor) encodeJavaEditorPlatformSource(sources.readEditor(javaEditorOverlaySession, operation,
            request.request, { scanWorkspace(root) }, { saved -> parseIntelligenceOverlay(request.sourceAuthority, saved, "java") }, cancellation), operation)
        else encodeJavaPlatformSource(sources.read(operation, request.request, { scanWorkspace(root) }, cancellation), operation)
    }

    private fun javaDependencySource(params: JsonObject?, cancellation: SemanticCancellationToken, read: Boolean): JsonElement {
        val request = decodeJavaDependencySource(params, read)
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        return encodeJavaDependencySource(org.refactorkit.java.JavaDependencySources().read(request, { scanWorkspace(root) }, cancellation))
    }

    private fun javaDependencySourceDeclaration(params: JsonObject?, cancellation: SemanticCancellationToken): JsonElement {
        val request = decodeJavaDependencySource(params, false, optionalAssociation = true)
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        return encodeJavaDependencySourceDeclaration(org.refactorkit.java.JavaDependencySourceDeclarations().read(request, { scanWorkspace(root) }, cancellation))
    }

    private fun javaEditorDependencySource(params: JsonObject?, cancellation: SemanticCancellationToken,
                                          operation: org.refactorkit.java.JavaEditorDependencySourceOperation): JsonElement {
        val request = decodeJavaEditorDependencySource(params, operation)
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val result = org.refactorkit.java.JavaDependencySourceDeclarations().readEditor(javaEditorOverlaySession, operation,
            request.request, request.expectedOverlayHash, { scanWorkspace(root) },
            { saved -> parseIntelligenceOverlay(request.sourceAuthority, saved, "java") }, cancellation)
        return encodeJavaEditorDependencySource(result, operation)
    }

    private fun projectContext(
        params: JsonObject?,
        cancellation: SemanticCancellationToken,
        responseSchemaVersion: String? = null,
        aggregate: Boolean = false,
        includeMavenTestJvmSelection: Boolean = false,
    ): JsonElement {
        val p = params ?: missing("params")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val supportsGradleBuildTreeProjectPaths = aggregate || responseSchemaVersion == "java-project-context-v7" ||
            responseSchemaVersion == null
        val includeGradleBuildTreeProjectPaths = supportsGradleBuildTreeProjectPaths && when (p["includeGradleBuildTreeProjectPaths"]) {
            null, JsonPrimitive(false) -> false
            JsonPrimitive(true) -> true
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,
                "includeGradleBuildTreeProjectPaths must be a boolean")
        }
        val request = try {
            val fields = setOf("expectedSnapshotHash", "sdkHome", "providerId", "moduleId", "sourceSetId", "sourceSetKind",
                "sourceRelativePath", "symbol", "expectedContextHash", "requiredFacts")
            val contextFields = if (aggregate) setOf("expectedSnapshotHash", "sdkHome", "requiredFacts") else fields
            val acceptedFields = if (supportsGradleBuildTreeProjectPaths) {
                contextFields + "includeGradleBuildTreeProjectPaths"
            } else contextFields
            require(p.keys.all { it in acceptedFields }) {
                if (aggregate) "project.context.aggregate contains unsupported parameters"
                else "project.context contains unsupported parameters"
            }
            fun field(key: String): String? {
                val value = p[key] ?: return null
                require(value is JsonPrimitive && value.isString) { "$key must be a string" }
                return value.content
            }
            val requiredFacts = when (val raw = p["requiredFacts"]) {
                null -> emptySet()
                is JsonArray -> {
                    require(raw.size <= org.refactorkit.java.JavaProjectContextFact.values().size) {
                        "requiredFacts exceeds the supported fact limit"
                    }
                    val names = raw.map { item ->
                        require(item is JsonPrimitive && item.isString) { "requiredFacts entries must be strings" }
                        item.content
                    }
                    require(names.distinct().size == names.size) { "requiredFacts entries must be unique" }
                    names.map { name -> when (name) {
                        "source-root-provenance" -> org.refactorkit.java.JavaProjectContextFact.SOURCE_ROOT_PROVENANCE
                        "compiler-options" -> org.refactorkit.java.JavaProjectContextFact.COMPILER_OPTIONS
                        "artifact-coordinates" -> org.refactorkit.java.JavaProjectContextFact.ARTIFACT_COORDINATES
                        "annotation-processors" -> org.refactorkit.java.JavaProjectContextFact.ANNOTATION_PROCESSORS
                        "jpms" -> org.refactorkit.java.JavaProjectContextFact.JPMS
                        else -> throw IllegalArgumentException("Unsupported required project fact '$name'")
                    } }.toSet()
                }
                else -> throw IllegalArgumentException("requiredFacts must be an array of strings")
            }
            org.refactorkit.java.JavaProjectContextRequest(
                expectedSnapshotHash = field("expectedSnapshotHash") ?: missing("expectedSnapshotHash"),
                sdkHome = field("sdkHome")?.let(Paths::get),
                providerId = field("providerId"), moduleId = field("moduleId"), sourceSetId = field("sourceSetId"),
                sourceSetKind = field("sourceSetKind")?.let { value ->
                    SourceSetKind.values().singleOrNull { it.name.lowercase().replace('_', '-') == value }
                        ?: throw IllegalArgumentException("sourceSetKind must be main, test, integration-test, or custom")
                },
                sourceRelativePath = field("sourceRelativePath")?.let(::protocolRelativePath),
                symbolQualifiedName = field("symbol"), expectedContextHash = field("expectedContextHash"),
                requiredFacts = requiredFacts,
                contextSchemaVersion = when (responseSchemaVersion) {
                    "java-project-context-v6" -> "java-project-context-v6"
                    "java-project-context-v7" -> "java-project-context-v7"
                    else -> org.refactorkit.java.JavaProjectContextRequest.DEFAULT_CONTEXT_SCHEMA_VERSION
                },
            )
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid project context request")
        }
        val contextMethod = when (responseSchemaVersion) {
                "java-project-context-v5" -> "project.context.v5"
                "java-project-context-v6" -> "project.context.v6"
                "java-project-context-v7" -> if (includeMavenTestJvmSelection) "project.context.v7.testJvmSelection"
                    else "project.context.v7"
                "java-project-context-v4" -> "project.context.v4"
                else -> "project.context.v4"
            }
        if (responseSchemaVersion in setOf("java-project-context-v4", "java-project-context-v5", "java-project-context-v6", "java-project-context-v7") && request.providerId == null) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "$contextMethod requires providerId")
        }
        if (responseSchemaVersion in setOf("java-project-context-v6", "java-project-context-v7") &&
            (request.moduleId == null || (request.sourceSetId == null && request.sourceSetKind == null))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,
                "$contextMethod requires provider-qualified module and source-set selection")
        }
        val schemaVersion = responseSchemaVersion
            ?: if (request.providerId == null) "java-project-context-v2" else "java-project-context-v3"
        if (includeGradleBuildTreeProjectPaths && schemaVersion == "java-project-context-v2") {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,
                "includeGradleBuildTreeProjectPaths requires providerId on project.context")
        }
        val result = org.refactorkit.java.JavaEffectiveProjectContextProvider().read(request, { scanWorkspace(root) }, cancellation)
        val legacyJpmsRefusal = result as? org.refactorkit.java.JavaProjectContextResolution.Available
        val response = if (schemaVersion !in setOf("java-project-context-v5", "java-project-context-v6", "java-project-context-v7") &&
            org.refactorkit.java.JavaProjectContextFact.JPMS in request.requiredFacts &&
            legacyJpmsRefusal?.view?.environments?.values?.any { environment ->
                environment.jpms.descriptors.any { descriptor ->
                    descriptor.opens.isNotEmpty() || descriptor.uses.isNotEmpty() || descriptor.provides.isNotEmpty()
                }
            } == true) {
            org.refactorkit.java.JavaProjectContextResolution.Refused(
                legacyJpmsRefusal.view.snapshot.hash,
                "java.context.jpmsDescriptorIncomplete",
                "The requested project-context schema cannot represent opens, uses, or provides directives",
            )
        } else result
        return encodeJavaProjectContext(
            response, PROTOCOL_JSON, schemaVersion, includeGradleBuildTreeProjectPaths,
            includeMavenTestJvmSelection = includeMavenTestJvmSelection,
        )
    }

    private inline fun <T> withSourceCaptureRefusal(action: () -> T): T = try {
        action()
    } catch (failure: OwnedSourceCaptureException) {
        throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,
            "workspace.sourceCapture.unavailable: ${failure.code.name}", buildJsonObject {
                put("refusalCode", "workspace.sourceCapture.unavailable")
                put("sourceCaptureFailure", failure.code.name)
            })
    }

    private fun scanWorkspace(root: Path): ProjectSnapshot = withSourceCaptureRefusal {
        val currentKotlinToolchain = kotlinToolchain
        val snapshot = WorkspaceSnapshotComposer().composeInventoryFirst(
            root = root,
            authoritativeBaseScanner = scanJava,
            sourceInventoryScanner = GenericProjectScanner(SCRIPT_EXTENSIONS)::scan,
            conditionalEvidenceAttacher = { snapshot -> TypeScriptBuildModelIntegration.attach(snapshot) },
            finalEvidenceAttacher = currentKotlinToolchain?.let { toolchain ->
                { snapshot: ProjectSnapshot -> KotlinJvmBuildModelIntegration.attach(snapshot, toolchain) }
            },
        )
        CFormattingStyleSnapshot.captureForSources(CCompilationSnapshotCapture.capture(CMakeManifestCapture.capture(snapshot)))
    }

    private fun csvAttribute(value: String?): List<String> = value.orEmpty().split(',')
        .map(String::trim).filter(String::isNotEmpty).sorted()

    private fun relativeModuleRoot(snapshot: ProjectSnapshot, moduleRoot: Path): String {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val module = moduleRoot.toAbsolutePath().normalize()
        if (!module.startsWith(root)) return "<outside-workspace>"
        val relative = ProtocolPath.serialize(root.relativize(module))
        return relative.ifBlank { "." }
    }

    private fun symbolSearch(params: JsonObject?): JsonElement {
        val query = params?.string("query") ?: ""
        val snap = requireSnapshot()
        val languageId = params?.string("languageId") ?: "java"
        if (languageId == "c") {
            val values = params ?: missing("params")
            return CCompilerSymbolSearch.search(snap, values) { discoverCToolchain(values) }
        }
        val results = if (languageId == "java") adapter.searchSymbols(snap, query) else
            requireSemanticAdapter(languageId).searchWorkspaceSymbols(snap, query)
        return buildJsonArray {
            results.take(200).forEach { sym ->
                add(buildJsonObject {
                    put("id", sym.id.value)
                    put("name", sym.name)
                    put("kind", sym.kind.name)
                    put("file", protocolSourcePath(snap, sym.location.path))
                    put("line", sym.location.range.start.line + 1)
                })
            }
        }
    }

    private fun symbolDefinition(params: JsonObject?): JsonElement {
        val symbolId = params?.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val languageId = params?.string("languageId") ?: "java"
        if (languageId == "c") {
            val values = params ?: missing("params")
            return CCompilerSymbolNavigation.definition(snap, values) { discoverCToolchain(values) }
        }
        val symbol = if (languageId == "java") adapter.findSymbol(snap, SymbolId(symbolId)) else
            requireSemanticAdapter(languageId).buildSymbols(snap).symbols.singleOrNull { it.id == SymbolId(symbolId) }
        symbol ?: throw JsonRpcException(JsonRpcErrorCodes.SYMBOL_NOT_FOUND, "Symbol not found: $symbolId")
        return buildJsonObject {
            put("id", symbol.id.value)
            put("name", symbol.name)
            put("kind", symbol.kind.name)
            put("file", protocolSourcePath(snap, symbol.location.path))
            put("line", symbol.location.range.start.line + 1)
            put("character", symbol.location.range.start.character)
        }
    }

    private fun symbolReferences(params: JsonObject?): JsonElement {
        val symbolId = params?.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val languageId = params?.string("languageId") ?: "java"
        if (languageId == "c") {
            val values = params ?: missing("params")
            return CCompilerSymbolNavigation.references(snap, values) { discoverCToolchain(values) }
        }
        val refs = if (languageId == "java") adapter.findReferences(snap, SymbolId(symbolId)) else {
            val semantic = requireSemanticAdapter(languageId)
            semantic.buildSymbols(snap)
            semantic.findReferences(SymbolId(symbolId))
        }
        return buildJsonArray {
            refs.take(ProtocolLimits.MAX_REFERENCE_RESULTS).forEach { ref ->
                add(buildJsonObject {
                    put("file", protocolSourcePath(snap, ref.location.path))
                    put("line", ref.location.range.start.line + 1)
                    put("character", ref.location.range.start.character)
                })
            }
        }
    }

    private fun diagnostics(params: JsonObject?): JsonElement {
        val snap = requireSnapshot()
        val verbose = params?.string("verbose")?.toBooleanStrictOrNull() ?: false
        val languageId = params?.string("languageId") ?: "java"
        val platformSelection = org.refactorkit.java.JavaDiagnosticsPlatformSelection(
            projectSdkHome = params?.string("jdkHome")?.let(Paths::get),
            releaseFallbackHome = (System.getProperty("refactorkit.java.platform.home")
                ?: System.getenv("REFACTORKIT_JAVA_PLATFORM_HOME"))?.let(Paths::get),
        )
        val javaDiagnostics = lazy {
            val all = adapter.authoritativeDiagnosticsWithPlatformSelection(snap, platformSelection, verbose)
            params?.string("module")?.let { adapter.filterDiagnosticsForModule(snap, all, it) } ?: all
        }
        val diagnosticsByLanguage = when (languageId) {
            "java" -> javaDiagnostics.value.map { "java" to it }
            "kotlin" -> kotlinAdapter.diagnostics(snap).map { "kotlin" to it }
            "jvm" -> (javaDiagnostics.value.map { "java" to it } +
                kotlinAdapter.diagnostics(snap).map { "kotlin" to it })
                .distinctBy { (owner, diagnostic) -> listOf(
                    owner,
                    diagnostic.code.orEmpty(),
                    diagnostic.severity.name,
                    diagnostic.location?.path?.normalize()?.toString().orEmpty(),
                    diagnostic.location?.range?.toString().orEmpty(),
                    diagnostic.message,
                ) }
                .sortedWith(compareBy({ it.first }, { it.second.location?.path?.toString().orEmpty() },
                    { it.second.code.orEmpty() }, { it.second.message }))
            else -> requireSemanticAdapter(languageId).diagnostics(snap).map { languageId to it }
        }
        return buildJsonArray {
            diagnosticsByLanguage.forEach { (owner, d) ->
                add(buildJsonObject {
                    if (languageId == "jvm") put("languageId", owner)
                    put("severity", d.severity.name)
                    put("message", d.message)
                    d.code?.let { put("code", it) }
                    d.evidence?.let { put("evidence", it.name) }
                    d.category?.let { put("category", it.name) }
                    d.location?.let { loc ->
                        put("file", protocolSourcePath(snap, loc.path))
                        put("line", loc.range.start.line + 1)
                    }
                })
            }
        }
    }

    private fun javaEditorPreview(params:JsonObject?,cancellation:SemanticCancellationToken):JsonElement {
        val p=params ?: missing("params")
        if(p.keys.any { it !in setOf("languageId","operation","expectedSnapshotHash","javaContext","sourceAuthority","symbol","arguments","javaEditorSelection","editorMutationMode","formatterProfile") })
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Unsupported editor preview fields")
        if(p.string("languageId")!="java") throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Editor preview requires languageId=java")
        val authority=p["sourceAuthority"] as? JsonObject ?: missing("sourceAuthority")
        if(authority.string("kind")!="immutable-editor-overlay") throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Editor preview requires immutable-editor-overlay")
        val args=(p["arguments"] as? JsonObject)?.mapValues { (_,v) ->
            (v as? JsonPrimitive)?.content ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Editor arguments must be primitive values")
        }.orEmpty()
        val operation=p.string("operation") ?: missing("operation")
        val formatterProfile=parseJavaFormatterProfile(p["formatterProfile"],"java",operation)
        val operationArgs=if(formatterProfile==null)args else args+formatterProfile.arguments()
        val root=workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN,"No project open")
        val selection=if("javaEditorSelection" !in p)null else try { org.refactorkit.java.JavaEditorMutationSelectionWire.parse(p.getValue("javaEditorSelection").jsonObject) }
            catch(_:Exception){throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Invalid javaEditorSelection")}
        val mode=try {
            val value=p["editorMutationMode"]
            if(value==null)org.refactorkit.java.JavaEditorMutationMode.READ_ONLY_PREVIEW else {
                val text=value as? JsonPrimitive ?: throw IllegalArgumentException("Admission mode must be a string")
                require(text.isString)
                org.refactorkit.java.JavaEditorMutationMode.fromWire(text.content)
            }
        }catch(_:IllegalArgumentException){throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Invalid editorMutationMode")}
        return try {
            val preview=javaEditorMutationSession.preview(javaContextRequest(p),
                org.refactorkit.java.JavaEditorPreviewOperation.fromWire(operation),
                p.string("symbol"),operationArgs,{ scanWorkspace(root) },{ saved -> parseIntelligenceOverlay(authority,saved,"java") },cancellation,selection)
            val response=org.refactorkit.java.JavaEditorMutationProtocol.render(preview,mode)
            val additions=buildMap<String,JsonElement> {
                if(selection!=null)put("selectionHash",JsonPrimitive(selection.selectorHash))
                preview.formatterProfilePath?.let { put("formatterProfilePath",JsonPrimitive(it)) }
                preview.formatterProfileName?.let { put("formatterProfileName",JsonPrimitive(it)) }
                preview.formatterProfileSha256?.let { put("formatterProfileSha256",JsonPrimitive(it)) }
            }
            JsonObject(response+additions)
        } catch(failure:org.refactorkit.java.JavaContextAuthorityException) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,failure.message.orEmpty(),buildJsonObject { put("refusalCode",failure.code) })
        }
    }

    private fun javaEditorTransaction(method: String, params: JsonObject?): JsonElement {
        val p=params ?: missing("params")
        if(p.keys.any { it !in setOf("languageId","planId","approvedPlanHash","transactionId","expectedSnapshotHash","javaContext","sourceAuthority","formatterProfileSha256") })
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Unsupported editor transaction fields")
        if(p.string("languageId")!="java")throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Editor transaction requires languageId=java")
        val root=workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN,"No project open")
        val authority=p["sourceAuthority"] as? JsonObject ?: missing("sourceAuthority")
        if(authority.string("kind")!="immutable-editor-overlay")throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"Editor transaction requires a closed overlay")
        val request=javaContextRequest(p)
        val hash=p.string("approvedPlanHash") ?: missing("approvedPlanHash")
        val formatterProfileSha256=if("formatterProfileSha256" in p) {
            val value=p["formatterProfileSha256"] as? JsonPrimitive
            if(value==null || !value.isString)throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"formatterProfileSha256 must be a string")
            value.content
        } else null
        if(method !in setOf("refactor.editor.prepare","refactor.editor.confirm") && formatterProfileSha256!=null)
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,"formatterProfileSha256 applies only to editor prepare and confirm")
        val scan={ scanWorkspace(root) }
        val factory={ saved:ProjectSnapshot -> parseIntelligenceOverlay(authority,saved,"java") }
        return try {
            val result=when(method) {
                "refactor.editor.prepare" -> javaEditorTransactionSession.prepare(PlanId(p.string("planId") ?: missing("planId")),hash,request,scan,factory,formatterProfileSha256)
                "refactor.editor.confirm" -> javaEditorTransactionSession.confirm(p.string("transactionId") ?: missing("transactionId"),hash,request,scan,factory,formatterProfileSha256)
                "refactor.editor.undo.prepare" -> javaEditorTransactionSession.prepareUndo(p.string("transactionId") ?: missing("transactionId"),hash,request,scan,factory)
                "refactor.editor.undo.confirm" -> javaEditorTransactionSession.confirmUndo(p.string("transactionId") ?: missing("transactionId"),hash,request,scan,factory)
                "refactor.editor.recover" -> javaEditorTransactionSession.recover(p.string("transactionId") ?: missing("transactionId"),hash,request,scan,factory)
                else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND,"Unknown editor transaction phase")
            }
            val response=org.refactorkit.java.JavaEditorMutationProtocol.renderTransaction(result)
            val preview=result.transaction.preview
            JsonObject(response+buildMap {
                preview.formatterProfilePath?.let { put("formatterProfilePath",JsonPrimitive(it)) }
                preview.formatterProfileName?.let { put("formatterProfileName",JsonPrimitive(it)) }
                preview.formatterProfileSha256?.let { put("formatterProfileSha256",JsonPrimitive(it)) }
            })
        } catch(failure:org.refactorkit.java.JavaContextAuthorityException) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,failure.message.orEmpty(),buildJsonObject {put("refusalCode",failure.code)})
        }
    }

    private fun refactorPreview(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val operation = p.string("operation") ?: missing("operation")
        var symbol = p.string("symbol")
        val requestedLanguage = p.string("languageId") ?: "java"
        if (requestedLanguage == "java" && "sourceAuthority" in p) {
            val source = p["sourceAuthority"] as? JsonObject ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "sourceAuthority must be an object")
            val mode = source.string("kind") ?: missing("sourceAuthority.kind")
            if (mode == "saved-snapshot" && source.keys != setOf("kind")) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "saved-snapshot sourceAuthority accepts only kind")
            try { org.refactorkit.java.JavaEditorOverlaySession.admitMutationSourceAuthority(mode) }
            catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
                throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, failure.message.orEmpty(), buildJsonObject { put("refusalCode", failure.code) })
            }
        }
        val formatterProfile = parseJavaFormatterProfile(p["formatterProfile"], requestedLanguage, operation, allowBatchCleanup = true)
        val args = if (operation == "introduceParameterObject") {
            val arguments = p["arguments"] as? JsonObject
            val typeName = (arguments?.get("typeName") as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
            val parameters = arguments?.get("parameters") as? JsonArray
            if (arguments?.keys != setOf("typeName", "parameters") || typeName == null ||
                parameters == null || parameters.size != 2 || parameters.any { it !is JsonPrimitive || !it.isString }) {
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS,
                    "introduceParameterObject requires a string typeName and an array of two parameter names")
            }
            mapOf("typeName" to typeName.content, "parameters" to parameters.toString())
        } else (p["arguments"] as? JsonObject)?.let { obj ->
            obj.entries.associate { (k, v) ->
                k to if (operation == "batchCleanup" && k == "actions") {
                    (v as? JsonArray)?.toString() ?: throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "batchCleanup arguments.actions must be an array",
                    )
                } else (v as? JsonPrimitive)?.content.orEmpty()
            }
        } ?: emptyMap()
        val operationArgs = if (formatterProfile == null) args else args + formatterProfile.arguments()

        if (operation == "batchCleanup" && requestedLanguage != "java") {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "batchCleanup requires languageId=java")
        }
        val freshBatchSnapshot = if (operation == "batchCleanup" && requestedLanguage == "java") {
            val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
            scanWorkspace(root)
        } else null
        freshBatchSnapshot?.let { fresh ->
            org.refactorkit.java.JavaBatchCleanupPlanner.snapshotRefusal(fresh, p.string("expectedSnapshotHash"))?.let { refusal ->
                throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, refusal.message,
                    buildJsonObject { put("refusalCode", refusal.code) })
            }
        }

        val structuralJavaContext = requestedLanguage == "java" &&
            operation in setOf("createMavenModule", "createGradleModule", "renameGradleModule") && "javaContext" in p
        if (requestedLanguage == "java" && operation in setOf("createGradleModule", "renameGradleModule") && !structuralJavaContext) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,
                "Gradle module transformations require explicit project-context authority",
                buildJsonObject { put("refusalCode", "java.context.authorityRequired") })
        }
        val structuralContext = if (structuralJavaContext) {
            val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
            when (val acquired = org.refactorkit.java.JavaEffectiveProjectContextProvider().read(
                javaContextRequest(p), { scanWorkspace(root) },
            )) {
                is org.refactorkit.java.JavaProjectContextResolution.Available -> acquired.view
                is org.refactorkit.java.JavaProjectContextResolution.Refused -> throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED, acquired.message,
                    buildJsonObject { put("refusalCode", acquired.code) },
                )
            }
        } else null
        val javaContext = if ("javaContext" in p && !structuralJavaContext) {
            if (requestedLanguage != "java") throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "javaContext requires languageId=java")
            val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
            when (val acquired = org.refactorkit.java.JavaBoundContext.acquire(javaContextRequest(p), { scanWorkspace(root) })) {
                is org.refactorkit.java.JavaBoundContextResolution.Available -> acquired.context
                is org.refactorkit.java.JavaBoundContextResolution.Refused -> throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED, acquired.message, buildJsonObject { put("refusalCode", acquired.code) })
            }
        } else null
        val javaContextView = javaContext?.view ?: structuralContext
        val javaContextSchemaVersion = javaContext?.contextSchemaVersion
            ?: if (structuralContext != null) javaContextRequest(p).contextSchemaVersion else null
        if (requestedLanguage == "java" && operation == "moveClass" &&
            "targetModuleId" in operationArgs && javaContext == null
        ) {
            throw JsonRpcException(
                JsonRpcErrorCodes.PLAN_REFUSED,
                "Selecting a moveClass target module requires an available explicit Java project context",
                buildJsonObject { put("refusalCode", "java.context.authorityRequired") },
            )
        }
        val javaSelection = if ("javaSelection" in p) {
            val context = javaContext ?: throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, "Mutation selector requires explicit SDK authority", buildJsonObject { put("refusalCode", "java.context.authorityRequired") })
            val claim = try { org.refactorkit.java.JavaMutationSelectionWire.parse(p.getValue("javaSelection").jsonObject) }
            catch (_: Exception) { throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid javaSelection") }
            try { org.refactorkit.java.JavaMutationSelectionService.validate(context, claim, operation, symbol, args) }
            catch (failure: org.refactorkit.java.JavaContextAuthorityException) { throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, failure.message.orEmpty(), buildJsonObject { put("refusalCode", failure.code) }) }
        } else null
        if (javaSelection != null) symbol = javaSelection.symbolId
        try { if (!structuralJavaContext) javaContext?.admitOperation(operation) } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, failure.message.orEmpty(), buildJsonObject { put("refusalCode", failure.code) })
        }
        val snap = javaContext?.snapshot ?: structuralContext?.snapshot ?: freshBatchSnapshot ?: requireSnapshot()
        if (operation == "batchCleanup") {
            org.refactorkit.java.JavaBatchCleanupPlanner.snapshotRefusal(snap, p.string("expectedSnapshotHash"))?.let { refusal ->
                throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, refusal.message,
                    buildJsonObject { put("refusalCode", refusal.code) })
            }
        }
        val semanticOwner = if (requestedLanguage in setOf("typescript", "javascript") && operation != "renameSymbol") requireSemanticAdapter(requestedLanguage) else null
        var retainedGate = semanticOwner?.diagnosticsGate()
        var plan = if (semanticOwner != null) {
            val request = RefactoringRequest(operation, symbolId = symbol?.let(::SymbolId), arguments = args, snapshot = snap)
            if (operation == "recipe") ManagedRecipePreview.preview(request, ManagedRecipeContext(snap, semanticOwner, requireNotNull(retainedGate)),
                p.string("expectedSnapshotHash"), p.string("semanticLease"), semanticLeases[requestedLanguage])
            else TypeScriptRefactoringProtocol.preview(semanticOwner, request,
                p.string("expectedSnapshotHash"), p.string("semanticLease"), semanticLeases[requestedLanguage])
        } else when (operation) {
            "renameSymbol" -> {
                if (requestedLanguage == "java") {
                    throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "renameSymbol requires kotlin, typescript or javascript languageId")
                }
                if (requestedLanguage == "kotlin") {
                    val lease = p.string("semanticLease") ?: missing("semanticLease")
                    val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
                    val generation = p.string("expectedIndexGeneration")?.toLongOrNull()
                        ?: missing("expectedIndexGeneration")
                    val currentGeneration = workspaceIndex.snapshot()?.generation
                        ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin workspace index is unavailable")
                    if (lease != kotlinSemanticLease) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.renameSessionStale",
                    )
                    if (expected != snap.hash || generation != currentGeneration) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.renameAuthorityStale",
                    )
                    KotlinManagedDeclarationRenamePlanner(kotlinAdapter).preview(
                        snap,
                        SymbolId(symbol ?: missing("symbol")),
                        args["newName"] ?: missing("arguments.newName"),
                        args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                    )
                } else {
                    val semantic = requireSemanticAdapter(requestedLanguage)
                    val indexed = semantic.buildSymbols(snap)
                    val selectedSymbol = symbol?.let { requested -> indexed.symbols.singleOrNull { it.id.value == requested } }
                    val location = selectedSymbol?.location ?: run {
                        val file = args["file"] ?: missing("arguments.file")
                        val line = args["line"]?.toIntOrNull() ?: missing("arguments.line")
                        val character = args["character"]?.toIntOrNull() ?: missing("arguments.character")
                        if (line < 0 || character < 0) {
                            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Rename coordinates must be non-negative")
                        }
                        SourceLocation(
                            Paths.get(file),
                            SourceRange(SourcePosition(line, character), SourcePosition(line, character)),
                        )
                    }
                    semantic.applyRefactoring(RefactoringRequest(
                        operation = "renameSymbol",
                        symbolId = selectedSymbol?.id,
                        selection = CodeSelection(location),
                        arguments = args,
                        snapshot = snap,
                    ))
                }
            }
            "moveDeclaration" -> {
                if (requestedLanguage != "kotlin") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "moveDeclaration requires languageId=kotlin",
                )
                val lease = p.string("semanticLease") ?: missing("semanticLease")
                val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
                val generation = p.string("expectedIndexGeneration")?.toLongOrNull()
                    ?: missing("expectedIndexGeneration")
                val currentGeneration = workspaceIndex.snapshot()?.generation
                    ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin workspace index is unavailable")
                if (lease != kotlinSemanticLease) throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.moveSessionStale",
                )
                if (expected != snap.hash || generation != currentGeneration) throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.moveAuthorityStale",
                )
                KotlinJvmMoveDeclarationPlanner(kotlinAdapter).preview(
                    snap,
                    SymbolId(symbol ?: missing("symbol")),
                    args["targetPackage"] ?: missing("arguments.targetPackage"),
                    args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "renameClass" -> {
                val newName = args["newName"] ?: missing("arguments.newName")
                val command = JavaRefactoringPreviewCommand.RenameClass(
                    SymbolId(symbol ?: missing("symbol")),
                    newName,
                )
                JavaRefactoringPreviewDispatcher().preview(snap, adapter, command)
            }
            "renameMember" -> {
                val newName = args["newName"] ?: missing("arguments.newName")
                val command = JavaRefactoringPreviewCommand.RenameMember(
                    SymbolId(symbol ?: missing("symbol")),
                    newName,
                )
                JavaRefactoringPreviewDispatcher().preview(snap, adapter, command)
            }
            "makeStatic" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "makeStatic requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractVariable" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractVariable requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractInterface" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractInterface requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractSuperclass" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractSuperclass requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "pullUpMember" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "pullUpMember requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "pushDownMember" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "pushDownMember requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "moveMember" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "moveMember requires languageId=java",
                )
                val selection = javaSelection ?: throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED,
                    "moveMember requires an exact saved-source JDT member selection",
                    buildJsonObject { put("refusalCode", "java.moveMember.selectionRequired") },
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(selection.symbolId),
                    arguments = args + mapOf(
                        "file" to ProtocolPath.serialize(selection.declaration.path),
                        "bindingKey" to selection.bindingKey,
                    ),
                    snapshot = snap,
                ))
            }
            "extractConstant" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractConstant requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "inlineConstant" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "inlineConstant requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "inlineVariable" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "inlineVariable requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractField" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractField requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "encapsulateField" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "encapsulateField requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    symbolId = SymbolId(symbol ?: missing("symbol")),
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractParameter" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "extractParameter requires languageId=java",
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation,
                    arguments = args,
                    snapshot = snap,
                ))
            }
            "extractMethod" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                val startLine = args["startLine"]?.toIntOrNull() ?: missing("arguments.startLine")
                val endLine = args["endLine"]?.toIntOrNull() ?: missing("arguments.endLine")
                val methodName = args["methodName"] ?: missing("arguments.methodName")
                if (requestedLanguage == "kotlin") {
                    requireKotlinMutationAuthority(p, snap, "extract")
                    KotlinExtractMethodPlanner(kotlinAdapter).preview(
                        snap, Paths.get(file), startLine, endLine, methodName,
                    )
                } else JavaExtractMethodPlanner().preview(snap, Paths.get(file), startLine, endLine, methodName)
            }
            "introduceParameterObject" -> {
                if (requestedLanguage != "java") throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS, "introduceParameterObject requires languageId=java",
                )
                val selection = javaSelection ?: throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED, "Introduce Parameter Object requires an exact saved Java method selection",
                    buildJsonObject { put("refusalCode", "java.introduceParameterObject.selectionRequired") },
                )
                adapter.applyRefactoring(RefactoringRequest(
                    operation = operation, symbolId = SymbolId(selection.symbolId), snapshot = snap,
                    arguments = args + mapOf("file" to ProtocolPath.serialize(selection.declaration.path), "bindingKey" to selection.bindingKey),
                ))
            }
            "inlineMethod" -> {
                if (requestedLanguage == "java") {
                    val selection = javaSelection ?: throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,
                        "Inline Method requires an exact saved Java method selection",
                        buildJsonObject { put("refusalCode", "java.inlineMethod.unsupported") })
                    adapter.applyRefactoring(RefactoringRequest(
                        operation = operation, symbolId = SymbolId(selection.symbolId), snapshot = snap,
                        arguments = mapOf("file" to ProtocolPath.serialize(selection.declaration.path), "bindingKey" to selection.bindingKey),
                    ))
                } else {
                    if (requestedLanguage != "kotlin") missing("languageId=kotlin")
                    requireKotlinMutationAuthority(p, snap, "inline")
                    KotlinInlineMethodPlanner(kotlinAdapter).preview(snap, SymbolId(symbol ?: missing("symbol")))
                }
            }
            "changeSignature.renameParameter", "renameParameter" -> {
                val oldName = args["oldName"] ?: args["oldParameterName"] ?: missing("arguments.oldName")
                val newName = args["newName"] ?: args["newParameterName"] ?: missing("arguments.newName")
                if (requestedLanguage == "kotlin") {
                    val lease = p.string("semanticLease") ?: missing("semanticLease")
                    val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
                    val generation = p.string("expectedIndexGeneration")?.toLongOrNull()
                        ?: missing("expectedIndexGeneration")
                    val currentGeneration = workspaceIndex.snapshot()?.generation
                        ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin workspace index is unavailable")
                    if (lease != kotlinSemanticLease) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.changeSignatureSessionStale",
                    )
                    if (expected != snap.hash || generation != currentGeneration) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.changeSignatureAuthorityStale",
                    )
                    KotlinJvmChangeSignaturePlanner(kotlinAdapter).previewRenameParameter(
                        snap,
                        SymbolId(symbol ?: missing("symbol")),
                        oldName,
                        newName,
                        acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                    )
                } else JavaChangeSignaturePlanner(adapter).previewRenameParameter(
                    snap, symbol ?: missing("symbol"), oldName, newName,
                    acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "changeSignature.changeParameterType", "changeParameterType" -> {
                val name = args["name"] ?: args["parameterName"] ?: missing("arguments.name")
                val type = args["type"] ?: args["newType"] ?: missing("arguments.type")
                JavaChangeSignaturePlanner(adapter).previewChangeParameterType(
                    snap, symbol ?: missing("symbol"), name, type,
                    includeHierarchy = args["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
                    acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "changeSignature.addParameter", "addParameter" -> {
                val type = args["type"] ?: args["parameterType"] ?: missing("arguments.type")
                val name = args["name"] ?: args["parameterName"] ?: missing("arguments.name")
                val defaultExpression = args["default"] ?: args["defaultExpression"] ?: missing("arguments.default")
                if (args["includeKotlinCallers"]?.toBooleanStrictOrNull() == true) {
                    JavaKotlinPublicTypeRenamePlanner(kotlinAdapter).previewAddParameter(
                        snap, org.refactorkit.core.SymbolId(symbol ?: missing("symbol")), type, name, defaultExpression,
                        acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                    )
                } else JavaChangeSignaturePlanner(adapter).previewAddParameter(
                    snap,
                    symbol ?: missing("symbol"),
                    type,
                    name,
                    defaultExpression,
                    includeHierarchy = args["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
                    acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                    migrateFunctionalReferences = args["migrateFunctionalReferences"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "changeSignature.reorderParameters", "reorderParameters" -> {
                val order = args["order"] ?: args["newOrder"] ?: missing("arguments.order")
                JavaChangeSignaturePlanner(adapter).previewReorderParameters(
                    snap, symbol ?: missing("symbol"), order.split(','),
                    includeHierarchy = args["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
                    acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "changeSignature.removeParameter", "removeParameter" -> {
                val name = args["name"] ?: args["parameterName"] ?: missing("arguments.name")
                JavaChangeSignaturePlanner(adapter).previewRemoveParameter(
                    snap,
                    symbol ?: missing("symbol"),
                    name,
                    includeHierarchy = args["includeHierarchy"]?.toBooleanStrictOrNull() ?: false,
                    acceptExternalConsumerRisk = args["acceptExternalConsumerRisk"]?.toBooleanStrictOrNull() ?: false,
                )
            }
            "moveClass" -> {
                val pkg = args["targetPackage"] ?: missing("arguments.targetPackage")
                when (val outcome = moveClassDispatcher.preview(
                    snap,
                    symbol ?: missing("symbol"),
                    pkg,
                    moveClassPromotionAttempt(args),
                    args["targetModuleId"],
                )) {
                    is JavaMoveClassOperationOutcome.Plan -> outcome.preview.plan
                    is JavaMoveClassOperationOutcome.Guidance -> return buildJsonObject {
                        put("resultType", outcome.guidance.resultType.name)
                        put("schemaVersion", outcome.guidance.schemaVersion)
                        put("managedWriteEligibility", "INELIGIBLE")
                        put("blockerCode", "guidance.nonManaged")
                    }
                    is JavaMoveClassOperationOutcome.LexicalReview ->
                        return JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(outcome.envelope)
                }
            }
            "movePackage" -> adapter.applyRefactoring(
                RefactoringRequest(operation = operation, arguments = args, snapshot = snap),
            )
            "moveSourceRoot" -> {
                val from = args["from"] ?: missing("arguments.from")
                val to = args["to"] ?: missing("arguments.to")
                val command = JavaRefactoringPreviewCommand.MoveSourceRoot(Paths.get(from), Paths.get(to))
                JavaRefactoringPreviewDispatcher().preview(snap, adapter, command)
            }
            JavaMoveAcrossMavenModulesPlanner.OPERATION -> adapter.applyRefactoring(
                RefactoringRequest(operation = operation, arguments = args, snapshot = snap),
            )
            "organizeImports" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                if (requestedLanguage == "kotlin") {
                    val lease = p.string("semanticLease") ?: missing("semanticLease")
                    val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
                    val generation = p.string("expectedIndexGeneration")?.toLongOrNull()
                        ?: missing("expectedIndexGeneration")
                    val currentGeneration = workspaceIndex.snapshot()?.generation
                        ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin workspace index is unavailable")
                    if (lease != kotlinSemanticLease) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.organizeImportsSessionStale",
                    )
                    if (expected != snap.hash || generation != currentGeneration) throw JsonRpcException(
                        JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.organizeImportsAuthorityStale",
                    )
                    KotlinOrganizeImportsPlanner(kotlinAdapter).preview(snap, Paths.get(file))
                } else {
                    val command = JavaRefactoringPreviewCommand.OrganizeImports(Paths.get(file))
                    JavaRefactoringPreviewDispatcher().preview(snap, adapter, command)
                }
            }
            "batchCleanup" -> adapter.applyRefactoring(RefactoringRequest(
                operation = operation,
                arguments = operationArgs,
                snapshot = snap,
            ))
            "formatFile" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                JavaFormatFilePlanner(adapter).preview(snap, Paths.get(file), operationArgs)
            }
            "changeVisibility" -> adapter.applyRefactoring(
                RefactoringRequest(
                    operation = operation,
                    symbolId = symbol?.let(::SymbolId),
                    arguments = args,
                    snapshot = snap,
                ),
            )
            "changeType" -> adapter.applyRefactoring(
                RefactoringRequest(
                    operation = operation,
                    symbolId = symbol?.let(::SymbolId),
                    arguments = args + (javaSelection ?: throw JsonRpcException(
                        JsonRpcErrorCodes.PLAN_REFUSED,
                        "changeType requires an exact saved-source JDT field selection",
                        buildJsonObject { put("refusalCode", "java.typeRepair.selectionRequired") },
                    )).let { selection ->
                        mapOf(
                            "file" to selection.declaration.path.toString(),
                            "bindingKey" to selection.bindingKey,
                        )
                    },
                    snapshot = snap,
                ),
            )
            "safeDelete" -> {
                val force = args["force"]?.toBoolean() ?: false
                val command = JavaRefactoringPreviewCommand.SafeDelete(
                    SymbolId(symbol ?: missing("symbol")),
                    force,
                )
                JavaRefactoringPreviewDispatcher().preview(snap, adapter, command)
            }
            "createMavenModule" -> {
                val moduleName = args["moduleName"] ?: missing("arguments.moduleName")
                val parentPom = args["parentPom"] ?: missing("arguments.parentPom")
                JavaCreateMavenModulePlanner().preview(snap, moduleName, Paths.get(parentPom))
            }
            "createGradleModule" -> {
                val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
                val model = gradleContextSession.modelFor(root)
                    ?: throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,
                        "An explicitly synchronized current Gradle context is required",
                        buildJsonObject { put("refusalCode", "java.createGradleModule.synchronizedContextRequired") })
                JavaCreateGradleModulePlanner().preview(
                    snap,
                    model,
                    args["buildRoot"] ?: missing("arguments.buildRoot"),
                    args["projectPath"] ?: missing("arguments.projectPath"),
                )
            }
            "renameGradleModule" -> {
                val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
                val model = gradleContextSession.modelFor(root)
                    ?: throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED,
                        "An explicitly synchronized current Gradle context is required",
                        buildJsonObject { put("refusalCode", "java.renameGradleModule.synchronizedContextRequired") })
                JavaRenameGradleModulePlanner().preview(
                    snap,
                    model,
                    args["buildRoot"] ?: missing("arguments.buildRoot"),
                    args["oldProjectPath"] ?: missing("arguments.oldProjectPath"),
                    args["newProjectPath"] ?: missing("arguments.newProjectPath"),
                )
            }
            "renameMavenModule" -> {
                val oldModuleDir = args["oldModuleDir"] ?: missing("arguments.oldModuleDir")
                val newModuleDir = args["newModuleDir"] ?: missing("arguments.newModuleDir")
                val newArtifactId = args["newArtifactId"]
                JavaRenameMavenModulePlanner().preview(snap, oldModuleDir, newModuleDir, newArtifactId)
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown operation: $operation")
        }

        if (structuralContext != null && plan.status != PatchStatus.REFUSED) {
            try {
                if (operation == "createGradleModule") {
                    val planner = JavaCreateGradleModulePlanner()
                    plan = planner.bindStructuralContext(plan, structuralContext,
                        requireNotNull(javaContextSchemaVersion))
                    retainedGate = planner.diagnosticsGate(plan, snap)
                } else if (operation == "renameGradleModule") {
                    val planner = JavaRenameGradleModulePlanner()
                    plan = planner.bindStructuralContext(plan, structuralContext,
                        requireNotNull(javaContextSchemaVersion))
                    retainedGate = planner.diagnosticsGate(plan, snap)
                } else {
                    plan = org.refactorkit.java.JavaProjectStructuralContextAuthority.bindMavenModulePlan(
                        plan, structuralContext, requireNotNull(javaContextSchemaVersion),
                    ) {
                        scanWorkspace(snap.workspace.root)
                    }
                    retainedGate = JavaCreateMavenModulePlanner().diagnosticsGate(plan)
                }
            } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
                throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, failure.message.orEmpty(),
                    buildJsonObject { put("refusalCode", failure.code) })
            }
        } else if (javaContext != null) try {
            val attested = javaContext.attestPreview(plan) { scanWorkspace(snap.workspace.root) }
            plan = attested.plan
            if (javaSelection != null && plan.status != PatchStatus.REFUSED) plan = org.refactorkit.java.JavaMutationSelectionService.attest(
                plan,
                javaSelection,
                if (operation == "moveMember") args["targetBindingKey"] else null,
            )
            retainedGate = attested.diagnosticsGate
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, failure.message.orEmpty(), buildJsonObject { put("refusalCode", failure.code) })
        }

        val safeDeleteReferenceAnalysis = if (requestedLanguage == "java" && operation == "safeDelete") {
            org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.from(snap)
        } else null
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(
                JsonRpcErrorCodes.PLAN_REFUSED,
                plan.summary,
                buildJsonObject {
                    plan.refusalCode?.let { put("refusalCode", it) }
                    safeDeleteReferenceAnalysis?.let { put("referenceAnalysis", it.toJson()) }
                },
            )
        }
        pendingPlans.insert(plan.id, PendingPlan(
            plan,
            languageId = requestedLanguage,
            semanticLease = if (requestedLanguage == "kotlin") p.string("semanticLease") else null,
            indexGeneration = if (requestedLanguage == "kotlin") workspaceIndex.snapshot()?.generation else null,
            diagnosticsGate = retainedGate,
            formatterProfileSha256 = formatterProfile?.sha256,
        ))
        val safeDeleteAnalysisJson = safeDeleteReferenceAnalysis?.let { analysis ->
            analysis.toJson().let { base ->
                val qualified = JavaSafeDeleteManagedApplyPolicy.isSnapshotBoundMavenClosure(plan)
                JsonObject(base + mapOf(
                    "referenceClosure" to JsonPrimitive(
                        if (plan.authorityLease?.attributes?.get(org.refactorkit.java.JavaSafeDeletePlanner.REFERENCE_CLOSURE_ATTRIBUTE) ==
                            org.refactorkit.java.JavaSafeDeletePlanner.MANAGED_REFERENCE_CLOSURE
                        ) org.refactorkit.java.JavaSafeDeletePlanner.MANAGED_REFERENCE_CLOSURE else "incomplete",
                    ),
                    "managedApplyAuthority" to JsonPrimitive(
                        if (qualified) org.refactorkit.java.JavaSafeDeletePlanner.SNAPSHOT_BOUND_AUTHORITY else "none",
                    ),
                    "completeProject" to JsonPrimitive(
                        plan.authorityLease?.attributes?.get(org.refactorkit.java.JavaSafeDeletePlanner.COMPLETE_PROJECT_ATTRIBUTE) == "true",
                    ),
                ))
            }
        }
        val planResponse = planToJson(plan, safeDeleteAnalysisJson)
        val response = if (formatterProfile == null) planResponse else JsonObject(planResponse + buildMap {
            put("formatterProfilePath", JsonPrimitive(formatterProfile.path))
            put("formatterProfileName", JsonPrimitive(formatterProfile.profileName))
            put("formatterProfileSha256", JsonPrimitive(formatterProfile.sha256))
        })
        return if (javaContextView == null) response else JsonObject(response + mapOf(
            "contextHash" to JsonPrimitive(javaContextView.contextHash),
            "contextSchemaVersion" to JsonPrimitive(requireNotNull(javaContextSchemaVersion)),
            "platformIdentities" to JsonArray(javaContextView.sdk.map { JsonPrimitive(it.identitySha256) }),
        ))
    }

    private fun refactorDiscard(params: JsonObject?): JsonElement {
        val planId = params?.string("planId") ?: missing("planId")
        val discarded = planId.isNotBlank() && pendingPlans.remove(PlanId(planId)) != null
        return PROTOCOL_JSON.encodeToJsonElement(DiscardResponseDto(planId, discarded))
    }

    private fun cPreview(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val operation = p.string("operation") ?: missing("operation")
        val args = (p["arguments"] as? JsonObject)?.let { obj ->
            obj.entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.content.orEmpty() }
        } ?: emptyMap()
        val snap = requireSnapshot()
        val toolchain = discoverCToolchain(p)
        val facade = CRefactoringFacade(toolchain)
        try {
            val plan = facade.previewForManagedApply(snap, operation, args)
            if (plan.status == PatchStatus.REFUSED) {
                throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_REFUSED,
                    plan.summary,
                    buildJsonObject { plan.refusalCode?.let { put("refusalCode", it) } },
                )
            }
            pendingPlans.insert(plan.id, PendingPlan(plan, languageId = "c", diagnosticsGate = cDiagnosticsGate(toolchain, snap, plan)))
            return planToJson(plan, providerName = "refactorkit-c")
        } finally {
            facade.close()
        }
    }

    private fun cDiagnosticsGate(toolchain: ClangSemanticToolchain, snapshot: ProjectSnapshot, plan: PatchPlan): DiagnosticsGate =
        // The C facade binds its admitted gate to this exact preview and snapshot.
        // The daemon never interprets captured compiler commands as executable code.
        CRefactoringFacade(toolchain).diagnosticsGate(snapshot, plan)

    private fun cDiagnosticsGate(toolchain: ClangSemanticToolchain): DiagnosticsGate =
        // A missing retained preview gate never inherits authority from a later request.
        CRefactoringFacade(toolchain).diagnosticsGate()

    private fun discoverCToolchain(p: JsonObject): ClangSemanticToolchain {
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val request = ClangToolchainRequest(
            workspaceRoot = root,
            clangExecutable = p.string("clang")?.let(Paths::get),
            clangdExecutable = p.string("clangd")?.let(Paths::get),
            clangFormatExecutable = p.string("clangFormat")?.let(Paths::get),
            readOnlySdks = CReadOnlySdkRequestJson.decode(p["readOnlySdks"]),
        )
        return when (val discovery = ClangToolchainDiscoverer().discover(request)) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                discovery.diagnostics.joinToString("; ") { it.message },
            )
        }
    }

    private fun refactorApply(params: JsonObject?): JsonElement {
        val values = params ?: JsonObject(emptyMap())
        val planId = when (val identity = RefactoringApplyIdentity.parse(values)) {
            is RefactoringApplyIdentity.OperationCorrelation -> {
                val operationId = identity.operationId
                val envelope = moveClassDispatcher.findLexicalReview(operationId)
                    ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Operation not found: $operationId")
                throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
                    envelope.blockerCode,
                    JavaMoveClassLexicalFallbackReviewJsonProjection.refusalData(envelope),
                )
            }
            is RefactoringApplyIdentity.ManagedPlan -> identity.planId
        }
        val pending = pendingPlans.lookup(planId)
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: ${planId.value}")
        val plan = pending.plan
        pending.formatterProfileSha256?.let { expectedProfileHash ->
            val suppliedProfileHash = values.string("formatterProfileSha256")
            if (suppliedProfileHash != expectedProfileHash) {
                pendingPlans.remove(planId)
                val refusalCode = if (suppliedProfileHash == null) {
                    "java.formatterProfile.approvalMissing"
                } else {
                    "java.formatterProfile.identityStale"
                }
                throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
                    "Apply requires the exact Eclipse formatter profile identity from its preview",
                    buildJsonObject { put("refusalCode", refusalCode) },
                )
            }
        }
        if (pending.languageId == "java" && plan.operation == "safeDelete" &&
            !JavaSafeDeleteManagedApplyPolicy.isSnapshotBoundMavenClosure(plan)
        ) {
            pendingPlans.remove(planId)
            throw JsonRpcException(
                JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
                JavaSafeDeleteManagedApplyPolicy.REFUSAL_MESSAGE,
                buildJsonObject { put("refusalCode", JavaSafeDeleteManagedApplyPolicy.REFUSAL_CODE) },
            )
        }
        if (pending.languageId == "java") {
            // Project/build transforms have independent operation contracts and are not Java adapter refactoring capabilities.
            val capability = org.refactorkit.java.JavaAdapterRegistration.capability(plan.operation)
            if (capability != null && capability.mutationAuthority != org.refactorkit.core.MutationAuthority.MANAGED_STABLE) {
                pendingPlans.remove(planId)
                val refusalCode = if (capability.mutationAuthority == org.refactorkit.core.MutationAuthority.PROPOSAL_ONLY) {
                    "java.refactoring.proposalOnly"
                } else {
                    "java.refactoring.notManaged"
                }
                throw JsonRpcException(
                    JsonRpcErrorCodes.PLAN_VALIDATION_FAILED,
                    "${plan.operation} is preview-only and cannot be applied as a managed Java refactoring",
                    buildJsonObject { put("refusalCode", refusalCode) },
                )
            }
        }
        if (pending.languageId == "kotlin") {
            val lease = values.string("semanticLease") ?: missing("semanticLease")
            val generation = values.string("expectedIndexGeneration")?.toLongOrNull()
                ?: missing("expectedIndexGeneration")
            if (lease != pending.semanticLease || lease != kotlinSemanticLease ||
                generation != pending.indexGeneration || generation != workspaceIndex.snapshot()?.generation) {
                pendingPlans.remove(planId)
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.renameAuthorityStale")
            }
        }
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        try {
            org.refactorkit.java.JavaBoundContext.validateApply(plan,
                if ("javaContext" in values) javaContextRequest(JsonObject(values + ("expectedSnapshotHash" to (values["expectedSnapshotHash"] ?: JsonPrimitive(plan.snapshotHash))))) else null,
                { scanWorkspace(root) })
        } catch (failure: org.refactorkit.java.JavaContextAuthorityException) {
            pendingPlans.remove(planId)
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, failure.message.orEmpty(), buildJsonObject { put("refusalCode", failure.code) })
        }
        val currentSnap = scanWorkspace(root)
        val applyDiagnosticsGate = if (plan.authorityLease?.attributes?.containsKey("javaContextHash") == true) {
            pending.diagnosticsGate ?: throw JsonRpcException(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, "Retained Java SDK gate is unavailable",
                buildJsonObject { put("refusalCode", "java.context.authorityRequired") })
        } else ManagedApplyDiagnosticsGateSelector.select(
            plan = plan,
            languageId = pending.languageId,
            javaAdapter = adapter,
            kotlinAdapter = kotlinAdapter,
            externalGateResolver = { languageId ->
                pending.diagnosticsGate ?: when (languageId) {
                    "c", "cpp", "objective-c" -> cDiagnosticsGate(discoverCToolchain(values))
                    else -> requireSemanticAdapter(languageId).diagnosticsGate()
                }
            },
        )
        return when (val result = PatchEngine(root).apply(
            plan,
            currentSnap,
            ApplyAuthorization.explicit("daemon-json-rpc"),
            applyDiagnosticsGate,
        )) {
            is ApplyResult.Applied -> {
                val refreshed = WorkspaceRefreshCoordinator.refresh(requireSnapshot()) {
                    scanWorkspace(root)
                }.snapshot
                val diagnostics = boundedDiagnostics(
                    when (pending.languageId) {
                        "java" -> if (plan.authorityLease?.attributes?.containsKey("javaContextHash") == true)
                            applyDiagnosticsGate.provider?.invoke(refreshed).orEmpty() else adapter.diagnostics(refreshed)
                        "kotlin" -> applyDiagnosticsGate.provider?.invoke(refreshed).orEmpty()
                        else -> emptyList()
                    },
                )
                closeSemanticAdapters()
                snapshot = refreshed
                openWorkspaceIndex(refreshed)
                pendingPlans.clear()
                moveClassDispatcher.clearLexicalReviewAudit()
                val primary = pending.importPreview?.primaryFile
                val changes = fileChanges(plan.workspaceEdit, primary)
                val appliedResponse = PROTOCOL_JSON.encodeToJsonElement(ApplyResponseDto(
                    status = "applied",
                    planId = planId.value,
                    transactionId = result.transaction.id.value,
                    changedFiles = changes,
                    changedFilePaths = changes.map(ProtocolFileChangeDto::path),
                    primaryFile = primary?.let(ProtocolPath::serialize),
                    diagnostics = diagnostics.items,
                    diagnosticsTruncated = diagnostics.truncated,
                    snapshotHash = refreshed.hash,
                    provider = ProtocolProviderDto(RefactorKitVersion.NAME, RefactorKitVersion.VERSION),
                )).jsonObject
                val contextHash = plan.authorityLease?.attributes?.get("javaContextHash")
                val selectionHash = plan.authorityLease?.attributes?.get("javaSelectionHash")
                JsonObject(appliedResponse + buildMap {
                    contextHash?.let { put("appliedContextHash", JsonPrimitive(it)) }
                    selectionHash?.let { put("appliedSelectionHash", JsonPrimitive(it)) }
                })
            }
            is ApplyResult.Refused -> {
                pendingPlans.remove(planId)
                val msg = result.diagnostics.joinToString("; ") { it.message }
                val data = result.diagnostics.singleOrNull()?.code
                    ?.takeIf { it == "referenceAnalysis.incomplete" }
                    ?.let { refusalCode -> buildJsonObject { put("refusalCode", refusalCode) } }
                throw JsonRpcException(JsonRpcErrorCodes.applyRefusalCode(result.diagnostics), msg, data)
            }
        }
    }

    private fun javaImportExternalClass(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val code = p.string("code") ?: missing("code")
        val requestedPackage = p.string("targetPackage")
        val targetDirectory = p.string("targetDirectory")
        if (targetDirectory == null && requestedPackage == null) missing("targetDirectory or targetPackage")
        val snap = requireSnapshot()
        val target = if (targetDirectory != null) {
            when (val resolution = JavaImportTargetResolver().resolve(
                snap,
                targetDirectory,
                requestedPackage,
                p.string("targetModule"),
            )) {
                is JavaImportTargetResolution.Resolved -> resolution.target
                is JavaImportTargetResolution.Refused -> {
                    val detail = refusedImportTarget(snap, resolution.refusal)
                    return importPreviewToJson(detail, snap)
                }
            }
        } else null
        val imported = ExternalJavaClassImporter().previewDetailed(ImportRequest(
            code = code,
            targetPackage = target?.packageName ?: requestedPackage.orEmpty(),
            targetModule = p.string("targetModule"),
            sourceUrl = p.string("sourceUrl"),
            sourceKind = parseSourceKind(p.string("sourceKind")),
            licensePolicy = parseLicensePolicy(p.string("licensePolicy")),
            snapshot = snap,
            resolvedTarget = target,
        ))
        val detail = withPreviewDiagnostics(imported, snap)
        if (detail.applyEligible) pendingPlans.insert(detail.plan.id, PendingPlan(detail.plan, detail))
        return importPreviewToJson(detail, snap)
    }

    private fun patchRecover(params: JsonObject?): JsonElement {
        val root = params?.string("root") ?: missing("root")
        val path = Paths.get(root).toAbsolutePath().normalize()
        val errors = PatchEngine(path).recover()
        if (errors.isNotEmpty()) throw JsonRpcException(
            JsonRpcErrorCodes.RECOVERY_REQUIRED,
            "Workspace recovery failed: ${errors.joinToString("; ") { it.message }}",
        )
        return buildJsonObject {
            put("root", path.toString())
            put("recovered", true)
        }
    }

    private fun patchRollback(params: JsonObject?): JsonElement {
        val txId = params?.string("transactionId") ?: missing("transactionId")
        val javaAuthorityRequest = if (params?.containsKey("javaContext") == true) javaContextRequest(params) else null
        val mode = if (params?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
            RollbackMode.FORCE
        } else RollbackMode.NORMAL
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val outcome = ManagedRollbackExecutor(log, PatchEngine(root), rollbackCall = { engine, transaction, rollbackMode ->
            engine.rollback(transaction, rollbackMode) { record ->
                org.refactorkit.java.JavaBoundContext.rollbackAuthority(record, javaAuthorityRequest) { scanWorkspace(root) }
            }
        }).execute(
            txId,
            RollbackLookupVisibility.JOURNAL_RECORD,
            mode,
            RollbackPreflightGuard<Nothing> { RollbackPreflightDecision.Allow },
        )
        return when (outcome) {
            is ManagedRollbackOutcome.InvalidTransactionId ->
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid transaction ID: $txId")
            is ManagedRollbackOutcome.TransactionNotFound ->
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Transaction not found: $txId")
            is ManagedRollbackOutcome.JournalLookupFailed -> throw outcome.failure
            is ManagedRollbackOutcome.PreflightRejected ->
                error("Allow rollback preflight guard unexpectedly rejected")
            is ManagedRollbackOutcome.RollbackCallJournalFailed -> throw outcome.failure
            is ManagedRollbackOutcome.RolledBack -> {
                val refreshed = WorkspaceRefreshCoordinator.refresh(requireSnapshot()) {
                    scanWorkspace(root)
                }.snapshot
                val diagnostics = boundedDiagnostics(org.refactorkit.java.JavaBoundContext.rollbackDiagnostics(outcome.resolvedRecord, refreshed)
                    ?: adapter.diagnostics(refreshed))
                snapshot = refreshed
                openWorkspaceIndex(refreshed)
                pendingPlans.clear()
                moveClassDispatcher.clearLexicalReviewAudit()
                val changes = fileChanges(outcome.resolvedRecord.forwardEdit, rollback = true)
                val response = PROTOCOL_JSON.encodeToJsonElement(RollbackResponseDto(
                    status = "rolledBack",
                    transactionId = txId,
                    rolledBack = true,
                    changedFiles = changes,
                    changedFilePaths = changes.map(ProtocolFileChangeDto::path),
                    diagnostics = diagnostics.items,
                    diagnosticsTruncated = diagnostics.truncated,
                    snapshotHash = refreshed.hash,
                    provider = ProtocolProviderDto(RefactorKitVersion.NAME, RefactorKitVersion.VERSION),
                )).jsonObject
                val contextHash = outcome.resolvedRecord.authorityAttributes["javaContextHash"]
                if (contextHash == null) response else JsonObject(response + ("rolledBackContextHash" to JsonPrimitive(contextHash)))
            }
            is ManagedRollbackOutcome.Refused -> {
                val msg = outcome.diagnostics.joinToString("; ") { it.message }
                throw JsonRpcException(
                    JsonRpcErrorCodes.rollbackRefusalCode(outcome.diagnostics),
                    "Rollback refused: $msg",
                    buildJsonObject { outcome.diagnostics.firstOrNull()?.code?.let { put("refusalCode", it) } },
                )
            }
        }
    }

    private fun refreshTypeScriptIndex(
        languageId: String,
        semantic: TypeScriptSemanticAdapter,
        current: ProjectSnapshot,
    ): TypeScriptIndexRefresh = when (val projection = semantic.symbolProjection(current)) {
        is TypeScriptSymbolProjection.Available -> try {
            val updated = workspaceIndex.contribute(WorkspaceSymbolContribution(
                providerId = typeScriptIndexProviderId(languageId),
                languageId = languageId,
                backend = TypeScriptToolchainProvenance.PROVIDER_ID,
                evidence = SemanticEvidenceKind.LANGUAGE_SERVER,
                completeness = WorkspaceIndexCompleteness.DECLARATIONS,
                snapshotHash = current.hash,
                symbols = projection.index.symbols,
                truncated = projection.truncated,
                provenanceHash = projection.provenanceHash,
            ))
            TypeScriptIndexRefresh(
                status = "ready",
                generation = updated.generation,
                symbolCount = projection.index.symbols.size,
                truncated = projection.truncated,
            )
        } catch (_: RuntimeException) {
            removeTypeScriptIndex(languageId)
            TypeScriptIndexRefresh(
                status = "refused",
                generation = workspaceIndex.snapshot()?.generation ?: 0,
                failure = Diagnostic(
                    "TypeScript provider returned an invalid workspace symbol projection",
                    Diagnostic.Severity.ERROR,
                    code = "typescript.symbolIndexInvalid",
                ),
            )
        }
        is TypeScriptSymbolProjection.Refused -> {
            removeTypeScriptIndex(languageId)
            TypeScriptIndexRefresh(
                status = "refused",
                generation = workspaceIndex.snapshot()?.generation ?: 0,
                failure = projection.diagnostic,
            )
        }
    }

    private fun requireTypeScriptProviderRunning(
        languageId: String,
        semantic: TypeScriptSemanticAdapter,
        refresh: TypeScriptIndexRefresh,
    ) {
        if (semantic.isRunning()) return
        semanticAdapters.remove(languageId)
        semanticLeases.remove(languageId)
        removeTypeScriptIndex(languageId)
        semantic.close()
        val failure = refresh.failure
        throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "${failure?.code ?: "typescript.semanticProcessStopped"}: " +
                (failure?.message ?: "TypeScript semantic process stopped during index projection"),
        )
    }

    private fun invalidateStoppedTypeScriptProvider(languageId: String, semantic: TypeScriptSemanticAdapter) {
        if (semantic.isRunning()) return
        semanticAdapters.remove(languageId, semantic)
        semanticLeases.remove(languageId)
        removeTypeScriptIndex(languageId)
        semantic.close()
    }

    private fun removeTypeScriptIndex(languageId: String) {
        val providerId = typeScriptIndexProviderId(languageId)
        if (workspaceIndex.snapshot()?.contributions?.any { it.providerId == providerId } == true) {
            workspaceIndex.removeProvider(providerId)
        }
    }

    private fun typeScriptIndexProviderId(languageId: String) = "$languageId-lsp-symbols-v1"

    private fun typeScriptIndexRefreshJson(refresh: TypeScriptIndexRefresh): JsonElement = buildJsonObject {
        put("status", refresh.status)
        put("generation", refresh.generation)
        put("symbolCount", refresh.symbolCount)
        put("truncated", refresh.truncated)
        put("failure", refresh.failure?.let { diagnostic -> buildJsonObject {
            put("code", diagnostic.code ?: "typescript.symbolIndexUnavailable")
            put("message", diagnostic.message)
        } } ?: kotlinx.serialization.json.JsonNull)
    }

    private fun typeScriptSemanticStart(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val snap = requireSnapshot()
        val root = workspaceRoot ?: error("workspace root missing")
        val languageId = p.string("languageId") ?: "typescript"
        if (languageId !in setOf("typescript", "javascript")) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "languageId must be typescript or javascript")
        }
        if (semanticAdapters[languageId]?.let { true } == true) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Semantic adapter is already configured for $languageId")
        }
        fun configuredPath(name: String): Path? = p.string(name)?.let { raw ->
            val candidate = Paths.get(raw)
            if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        }
        val policy = TypeScriptToolchainDiscoveryPolicy(
            allowPathNodeDiscovery = p.string("allowPathNodeDiscovery")?.toBooleanStrictOrNull() ?: false,
            allowWorkspaceLocalToolchain = p.string("allowWorkspaceLocalToolchain")?.toBooleanStrictOrNull() ?: false,
        )
        val discovery = toolchainDiscovery(TypeScriptToolchainRequest(
            workspaceRoot = root,
            nodeExecutable = configuredPath("nodeExecutable"),
            languageServerPackageRoot = configuredPath("languageServerPackageRoot"),
            typeScriptPackageRoot = configuredPath("typeScriptPackageRoot"),
        ), policy)
        val toolchain = when (discovery) {
            is TypeScriptToolchainDiscovery.Available -> discovery.toolchain
            is TypeScriptToolchainDiscovery.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                discovery.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
            )
        }
        val model = TypeScriptProjectModelBuilder().build(root)
        val semantic = semanticAdapterFactory(languageId, toolchain, model)
        when (val started = semantic.start(snap)) {
            is TypeScriptSemanticStart.Refused -> {
                semantic.close()
                throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS,
                    started.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
                )
            }
            is TypeScriptSemanticStart.Started -> {
                semanticAdapters[languageId] = semantic
                val lease = semanticLeaseFactory()
                semanticLeases[languageId] = lease
                val indexRefresh = refreshTypeScriptIndex(languageId, semantic, snap)
                requireTypeScriptProviderRunning(languageId, semantic, indexRefresh)
                return buildJsonObject {
                    put("languageId", languageId)
                    put("status", "started")
                    put("semanticLease", lease)
                    put("snapshotHash", snap.hash)
                    put("completeness", semantic.semanticCompleteness().mode.name.lowercase().replace('_', '-'))
                    put("index", typeScriptIndexRefreshJson(indexRefresh))
                    started.provenance?.let { provenance ->
                        put("serverName", provenance.serverName ?: "")
                        put("serverVersion", provenance.serverVersion ?: "")
                        put("capabilitiesSha256", provenance.capabilitiesSha256)
                        put("executableSha256", provenance.process.executableSha256)
                        put("argumentsSha256", provenance.process.argumentsSha256)
                        put("processId", provenance.process.pid)
                    }
                }
            }
        }
    }

    private fun typeScriptSemanticRestart(params: JsonObject?): JsonElement {
        val languageId = params?.string("languageId") ?: "typescript"
        val semantic = semanticAdapters[languageId] ?: throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "Semantic adapter for $languageId is not started",
        )
        return when (val restarted = semantic.restart(requireSnapshot())) {
            is TypeScriptSemanticStart.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                restarted.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
            )
            is TypeScriptSemanticStart.Started -> buildJsonObject {
                val lease = semanticLeaseFactory()
                semanticLeases[languageId] = lease
                val indexRefresh = refreshTypeScriptIndex(languageId, semantic, requireSnapshot())
                requireTypeScriptProviderRunning(languageId, semantic, indexRefresh)
                put("languageId", languageId)
                put("status", "restarted")
                put("semanticLease", lease)
                put("snapshotHash", requireSnapshot().hash)
                put("index", typeScriptIndexRefreshJson(indexRefresh))
                restarted.provenance?.let { provenance ->
                    put("serverName", provenance.serverName ?: "")
                    put("serverVersion", provenance.serverVersion ?: "")
                    put("capabilitiesSha256", provenance.capabilitiesSha256)
                    put("executableSha256", provenance.process.executableSha256)
                    put("argumentsSha256", provenance.process.argumentsSha256)
                    put("processId", provenance.process.pid)
                }
            }
        }
    }

    private fun typeScriptSemanticStop(params: JsonObject?): JsonElement {
        val languageId = params?.string("languageId") ?: "typescript"
        val hadPartition = workspaceIndex.snapshot()?.contributions?.any {
            it.providerId == typeScriptIndexProviderId(languageId)
        } == true
        val adapter = semanticAdapters.remove(languageId)
        adapter?.close()
        val hadLease = semanticLeases.remove(languageId) != null
        removeTypeScriptIndex(languageId)
        val stopped = adapter != null || hadLease || hadPartition
        return buildJsonObject {
            put("languageId", languageId)
            put("stopped", stopped)
        }
    }

    private fun kotlinSemanticStart(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        if (kotlinToolchain != null) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin semantic toolchain is already configured",
        )
        val current = requireSnapshot()
        val root = workspaceRoot ?: error("workspace root missing")
        fun configuredPath(name: String): Path {
            val raw = p.string(name) ?: missing(name)
            val candidate = Paths.get(raw)
            return if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        }
        val classpath = (p["compilerClasspath"] as? JsonArray)?.map { element ->
            val raw = (element as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "compilerClasspath must contain strings")
            val candidate = Paths.get(raw)
            if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        } ?: emptyList()
        val policy = KotlinToolchainDiscoveryPolicy(
            allowWorkspaceLocalToolchain = p.string("allowWorkspaceLocalToolchain")?.toBooleanStrictOrNull() ?: false,
        )
        val discovered = kotlinToolchainDiscovery(KotlinToolchainRequest(
            workspaceRoot = root,
            jdkHome = configuredPath("jdkHome"),
            compilerJar = configuredPath("compilerJar"),
            compilerClasspath = classpath,
        ), policy)
        val toolchain = when (discovered) {
            is KotlinToolchainDiscovery.Available -> discovered.toolchain
            is KotlinToolchainDiscovery.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                discovered.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
            )
        }
        val attached = KotlinJvmBuildModelIntegration.attach(current, toolchain)
        val model = attached.buildModels.single { it.providerId == org.refactorkit.kotlin.KotlinJvmBuildModelProjector.PROVIDER_ID }
        if (model.status != org.refactorkit.core.BuildModelStatus.AVAILABLE) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            model.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" }
                .ifBlank { "kotlin.buildModelUnavailable: Kotlin/JVM build projection is unavailable" },
            buildJsonObject {
                put("diagnosticCodes", JsonArray(model.diagnostics.map { JsonPrimitive(it.code) }))
            },
        )
        val lease = semanticLeaseFactory()
        kotlinToolchain = toolchain
        kotlinAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        kotlinSemanticLease = lease
        snapshot = attached
        moveClassDispatcher.clearLexicalReviewAudit()
        openWorkspaceIndex(attached)
        return buildJsonObject {
            put("languageId", "kotlin")
            put("status", "started")
            put("semanticLease", lease)
            put("snapshotHash", attached.hash)
            put("backend", KotlinCompilerDiagnostics.BACKEND)
            put("toolchainProvider", toolchain.provenance.providerId)
            put("toolchainProjectionHash", toolchain.provenance.projectionHash)
            put("buildProjectionHash", model.attributes.getValue("projectionHash"))
            put("kotlinVersion", toolchain.provenance.kotlinVersion)
            put("javaVersion", toolchain.provenance.javaVersion)
        }
    }

    private fun kotlinSemanticStop(): JsonElement {
        val stopped = kotlinToolchain != null
        snapshot = snapshot?.let { current ->
            current.copy(buildModels = current.buildModels.filterNot {
                it.providerId == org.refactorkit.kotlin.KotlinJvmBuildModelProjector.PROVIDER_ID
            })
        }
        snapshot?.let(::openWorkspaceIndex)
        moveClassDispatcher.clearLexicalReviewAudit()
        kotlinAdapter = KotlinLanguageAdapter()
        kotlinToolchain = null
        kotlinSemanticLease = null
        return buildJsonObject { put("languageId", "kotlin"); put("stopped", stopped) }
    }

    private fun kotlinDiagnostics(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val current = requireSnapshot()
        val requestId = p.string("requestId") ?: missing("requestId")
        val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        val requestedLease = p.string("semanticLease") ?: missing("semanticLease")
        if (expected != current.hash) return kotlinDiagnosticsRefusal(
            requestId, requestedLease, current.hash, "kotlin.diagnosticsSnapshotStale", "Expected Kotlin snapshot is stale",
        )
        if (kotlinToolchain == null || requestedLease != kotlinSemanticLease) return kotlinDiagnosticsRefusal(
            requestId, requestedLease, current.hash, "kotlin.diagnosticsSessionStale", "Kotlin semantic lease is missing or stale",
        )
        val result = kotlinAdapter.compilerDiagnostics(current)
        val status = when (result) {
            is KotlinCompilerDiagnosticsResult.Available -> "ready"
            is KotlinCompilerDiagnosticsResult.Refused -> "refused"
            is KotlinCompilerDiagnosticsResult.Error -> "error"
        }
        return buildJsonObject {
            put("schemaVersion", 1)
            put("requestId", requestId)
            put("languageId", "kotlin")
            put("status", status)
            put("semanticLease", requestedLease)
            put("snapshotHash", current.hash)
            put("backend", result.attestation.backend)
            put("toolchainProjectionHash", result.attestation.toolchainProjectionHash)
            put("buildProjectionHash", result.attestation.buildProjectionHash)
            put("kotlinVersion", result.attestation.kotlinVersion)
            put("javaVersion", result.attestation.javaVersion)
            put("runtime", buildJsonObject {
                put("requestTimeoutMillis", KotlinCompilerDiagnostics.REQUEST_TIMEOUT_MILLIS)
                put("maxOutputBytes", KotlinCompilerDiagnostics.MAX_OUTPUT_BYTES)
                put("maxProcesses", 1)
                put("compilerHeapMiB", KotlinCompilerDiagnostics.COMPILER_HEAP_MIB)
            })
            put("process", result.attestation.process?.let { provenance -> buildJsonObject {
                put("executableSha256", provenance.executableSha256)
                put("argumentsSha256", provenance.argumentsSha256)
                put("processId", provenance.pid)
            } } ?: kotlinx.serialization.json.JsonNull)
            val failure = when (result) {
                is KotlinCompilerDiagnosticsResult.Available -> null
                is KotlinCompilerDiagnosticsResult.Refused -> result.reason
                is KotlinCompilerDiagnosticsResult.Error -> result.failure
            }
            put("failure", failure?.let { diagnostic -> buildJsonObject {
                put("code", diagnostic.code ?: "kotlin.diagnosticsUnavailable")
                put("message", diagnostic.message)
            } } ?: kotlinx.serialization.json.JsonNull)
            put("diagnostics", buildJsonArray {
                if (result is KotlinCompilerDiagnosticsResult.Available) result.diagnostics.forEach { diagnostic ->
                    add(renderKotlinDiagnostic(current, diagnostic))
                }
            })
        }
    }

    private fun kotlinSymbols(params: JsonObject?): JsonElement = kotlinSymbolRead(params, null)

    private fun kotlinDefinition(params: JsonObject?): JsonElement {
        val symbol = params?.string("symbol") ?: missing("symbol")
        if (!Regex("kotlin-jvm-(?:type|callable|constructor|property|parameter|type-parameter)-v1:[0-9a-f]{64}").matches(symbol)) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin definition requires a valid opaque JVM declaration ID")
        }
        return kotlinSymbolRead(params, SymbolId(symbol))
    }

    private fun kotlinSymbolRead(params: JsonObject?, requestedSymbol: SymbolId?): JsonElement {
        val p = params ?: missing("params")
        val current = requireSnapshot()
        val requestId = p.string("requestId") ?: missing("requestId")
        if (requestId.length !in 1..ProtocolLimits.MAX_DIAGNOSTICS_REQUEST_ID_CHARS) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol requestId is invalid")
        }
        val expected = p.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        val requestedLease = p.string("semanticLease") ?: missing("semanticLease")
        val query = if (requestedSymbol == null) p.string("query").orEmpty() else ""
        if (query.length > 512) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol query is too long")
        val requestedFile = p.string("file")?.let { raw ->
            if (raw.length > 4_096) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol file is too long")
            val path = runCatching { Paths.get(raw) }.getOrNull()
            if (path == null || path.isAbsolute || raw.contains('\\') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(raw)) {
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol file must be a normalized workspace-relative '/' path")
            }
            val normalized = path.normalize()
            if (normalized.toString().isBlank() || normalized.startsWith("..") ||
                current.files.none { it.languageId == "kotlin" && it.path.normalize() == normalized }) {
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol file is not an attested Kotlin source")
            }
            normalized
        }
        fun refusal(code: String, message: String) = buildJsonObject {
            val toolchain = kotlinToolchain
            val model = current.buildModels.singleOrNull {
                it.providerId == org.refactorkit.kotlin.KotlinJvmBuildModelProjector.PROVIDER_ID
            }
            put("schemaVersion", 1); put("requestId", requestId); put("languageId", "kotlin")
            put("status", "refused"); put("semanticLease", requestedLease); put("snapshotHash", current.hash)
            put("backend", KotlinCompilerDiagnostics.SYMBOL_BACKEND)
            put("toolchainProjectionHash", toolchain?.provenance?.projectionHash.orEmpty())
            put("buildProjectionHash", model?.attributes?.get("projectionHash").orEmpty())
            put("kotlinVersion", toolchain?.provenance?.kotlinVersion ?: "unconfigured")
            put("javaVersion", toolchain?.provenance?.javaVersion ?: "unconfigured")
            put("process", kotlinx.serialization.json.JsonNull)
            put("failure", buildJsonObject { put("code", code); put("message", message) })
            put("symbols", buildJsonArray { })
            put("truncated", false)
        }
        if (expected != current.hash) return refusal(
            "kotlin.symbolsSnapshotStale", "Expected Kotlin snapshot is stale",
        )
        if (kotlinToolchain == null || requestedLease != kotlinSemanticLease) return refusal(
            "kotlin.symbolsSessionStale", "Kotlin semantic lease is missing or stale",
        )
        val result = kotlinAdapter.compilerSymbols(current)
        if (result is KotlinCompilerSymbolsResult.Available) {
            workspaceIndex.contribute(WorkspaceSymbolContribution(
                providerId = "kotlin-k2-jvm-declarations-v1",
                languageId = "kotlin",
                backend = KotlinCompilerDiagnostics.SYMBOL_BACKEND,
                evidence = SemanticEvidenceKind.COMPILER,
                completeness = WorkspaceIndexCompleteness.DECLARATIONS,
                snapshotHash = current.hash,
                symbols = result.index.symbols,
                provenanceHash = result.attestation.toolchainProjectionHash,
            ))
        }
        val status = when (result) {
            is KotlinCompilerSymbolsResult.Available -> "ready"
            is KotlinCompilerSymbolsResult.Refused -> "refused"
            is KotlinCompilerSymbolsResult.Error -> "error"
        }
        val selected = if (result is KotlinCompilerSymbolsResult.Available) {
            val candidates = result.index.symbols.filter {
                it.name.contains(query, ignoreCase = true) && (requestedFile == null || it.location.path == requestedFile)
            }
            if (requestedSymbol == null) candidates else candidates.filter { it.id == requestedSymbol }
        } else emptyList()
        return buildJsonObject {
            put("schemaVersion", 1)
            put("requestId", requestId)
            put("languageId", "kotlin")
            put("status", if (
                result is KotlinCompilerSymbolsResult.Available && requestedSymbol != null && selected.isEmpty()
            ) "refused" else status)
            put("semanticLease", requestedLease)
            put("snapshotHash", current.hash)
            put("backend", result.attestation.backend)
            put("toolchainProjectionHash", result.attestation.toolchainProjectionHash)
            put("buildProjectionHash", result.attestation.buildProjectionHash)
            put("kotlinVersion", result.attestation.kotlinVersion)
            put("javaVersion", result.attestation.javaVersion)
            put("process", result.attestation.process?.let { provenance -> buildJsonObject {
                put("executableSha256", provenance.executableSha256)
                put("argumentsSha256", provenance.argumentsSha256)
                put("processId", provenance.pid)
            } } ?: kotlinx.serialization.json.JsonNull)
            val failure = when (result) {
                is KotlinCompilerSymbolsResult.Available -> if (requestedSymbol != null && selected.isEmpty()) Diagnostic(
                    message = "Kotlin symbol was not found in the attested snapshot",
                    severity = Diagnostic.Severity.ERROR,
                    code = "kotlin.symbolNotFound",
                ) else null
                is KotlinCompilerSymbolsResult.Refused -> result.reason
                is KotlinCompilerSymbolsResult.Error -> result.failure
            }
            put("failure", failure?.let { diagnostic -> buildJsonObject {
                put("code", diagnostic.code ?: "kotlin.symbolsUnavailable")
                put("message", diagnostic.message)
            } } ?: kotlinx.serialization.json.JsonNull)
            put("symbols", buildJsonArray {
                selected.take(ProtocolLimits.MAX_SYMBOL_RESULTS).forEach { symbol -> add(buildJsonObject {
                    put("id", symbol.id.value)
                    put("name", symbol.name)
                    put("kind", symbol.kind.name.lowercase())
                    put("file", protocolSourcePath(current, symbol.location.path))
                    put("startLine", symbol.location.range.start.line)
                    put("startCharacter", symbol.location.range.start.character)
                    put("endLine", symbol.location.range.end.line)
                    put("endCharacter", symbol.location.range.end.character)
                }) }
            })
            put("truncated", selected.size > ProtocolLimits.MAX_SYMBOL_RESULTS)
        }
    }

    private fun kotlinDiagnosticsRefusal(
        requestId: String,
        lease: String,
        snapshotHash: String,
        code: String,
        message: String,
    ) = buildJsonObject {
        put("schemaVersion", 1); put("requestId", requestId); put("languageId", "kotlin")
        put("status", "refused"); put("semanticLease", lease); put("snapshotHash", snapshotHash)
        put("backend", KotlinCompilerDiagnostics.BACKEND)
        put("failure", buildJsonObject { put("code", code); put("message", message) })
        put("diagnostics", buildJsonArray { })
    }

    private fun renderKotlinDiagnostic(snapshot: ProjectSnapshot, diagnostic: Diagnostic) = buildJsonObject {
        put("severity", diagnostic.severity.name.lowercase())
        put("message", diagnostic.message)
        diagnostic.code?.let { put("code", it) }
        put("locationPrecision", diagnostic.locationPrecision.name.lowercase().replace('_', '-'))
        diagnostic.location?.let { location ->
            put("file", protocolSourcePath(snapshot, location.path))
            put("line", location.range.start.line)
            if (diagnostic.locationPrecision == org.refactorkit.core.DiagnosticLocationPrecision.EXACT_RANGE) {
                put("character", location.range.start.character)
                put("endLine", location.range.end.line)
                put("endCharacter", location.range.end.character)
            }
        }
    }

    private fun diagnosticsV2(params: JsonObject?): JsonElement {
        val request = try {
            TypeScriptDiagnosticsProtocol.parse(params ?: missing("params"))
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid diagnostics.v2 request")
        }
        val snap = requireSnapshot()
        TypeScriptDiagnosticsProtocol.snapshotRefusal(
            request,
            snap.hash,
            semanticLeases[request.languageId],
        )?.let { return it }
        val semantic = semanticAdapters[request.languageId]
            ?: return TypeScriptDiagnosticsProtocol.notReady(request, snap.hash, semanticLeases[request.languageId])
        val lease = semanticLeases[request.languageId]
            ?: return TypeScriptDiagnosticsProtocol.notReady(request, snap.hash)
        return TypeScriptDiagnosticsProtocol.execute(request, snap, lease, semantic)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun closeSavedWorkspaceWatcher() {
        savedWorkspaceWatcher?.close()
        savedWorkspaceWatcher = null
        savedWorkspaceWatcherFailure = null
        lastWorkspaceRefresh = null
        workspaceRefreshCount = 0
    }

    private fun closeSemanticAdapters() {
        semanticAdapters.values.forEach { runCatching { it.close() } }
        semanticAdapters.keys.toList().forEach(::removeTypeScriptIndex)
        semanticAdapters.clear()
        semanticLeases.clear()
        kotlinAdapter = KotlinLanguageAdapter()
        kotlinToolchain = null
        kotlinSemanticLease = null
    }

    private fun protocolSourcePath(snapshot: ProjectSnapshot, path: Path): String {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val normalized = if (path.isAbsolute) path.toAbsolutePath().normalize() else root.resolve(path).normalize()
        return if (normalized.startsWith(root)) ProtocolPath.serialize(root.relativize(normalized))
        else "<outside-workspace>"
    }

    private fun requireSemanticAdapter(languageId: String): TypeScriptSemanticAdapter {
        if (languageId !in setOf("typescript", "javascript")) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unsupported semantic languageId: $languageId")
        }
        return semanticAdapters[languageId] ?: throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "Semantic adapter for $languageId is not started; call typescript.semantic.start",
        )
    }

    private fun requireKotlinMutationAuthority(
        params: JsonObject,
        current: ProjectSnapshot,
        operation: String,
    ) {
        val lease = params.string("semanticLease") ?: missing("semanticLease")
        val expected = params.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        val generation = params.string("expectedIndexGeneration")?.toLongOrNull()
            ?: missing("expectedIndexGeneration")
        val currentGeneration = workspaceIndex.snapshot()?.generation
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin workspace index is unavailable")
        if (lease != kotlinSemanticLease) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.${operation}SessionStale",
        )
        if (expected != current.hash || generation != currentGeneration) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "kotlin.${operation}AuthorityStale",
        )
    }

    private fun requireSnapshot(): ProjectSnapshot =
        snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open. Call project.open first.")

    private fun missing(field: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing required field: $field")

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    private fun parseLicensePolicy(value: String?): LicensePolicy = when (value) {
        "block-unknown", "BLOCK_UNKNOWN" -> LicensePolicy.BLOCK_UNKNOWN
        "allow", "ALLOW" -> LicensePolicy.ALLOW
        else -> LicensePolicy.WARN
    }

    private fun parseSourceKind(value: String?): SourceKind = when (value) {
        "clipboard", "CLIPBOARD" -> SourceKind.CLIPBOARD
        "url", "URL" -> SourceKind.URL
        "file", "FILE" -> SourceKind.FILE
        "llm", "LLM" -> SourceKind.LLM
        else -> SourceKind.SNIPPET
    }

    private fun withPreviewDiagnostics(detail: ExternalImportPreview, snapshot: ProjectSnapshot): ExternalImportPreview {
        if (detail.plan.status != PatchStatus.PREVIEW) return detail.copy(applyEligible = false)
        return try {
            val before = adapter.diagnostics(snapshot)
            val staged = WorkspaceEditSimulator.apply(snapshot, detail.plan.workspaceEdit)
            val after = adapter.diagnostics(staged)
            val existingErrors = before.filter { it.severity == Diagnostic.Severity.ERROR }
                .groupingBy(::diagnosticIdentity).eachCount().toMutableMap()
            val regressions = after.filter { diagnostic ->
                if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
                val identity = diagnosticIdentity(diagnostic)
                val count = existingErrors[identity] ?: 0
                if (count > 0) {
                    existingErrors[identity] = count - 1
                    false
                } else true
            }
            val blockers = regressions.map { diagnostic ->
                "${diagnostic.code ?: "diagnostics.regression"}: ${diagnostic.message}"
            }.distinct()
            detail.copy(
                diagnosticsAfterPreview = after,
                applyBlockers = blockers,
                applyEligible = detail.applyEligible && blockers.isEmpty(),
            )
        } catch (error: Exception) {
            detail.copy(
                diagnosticsAfterPreview = emptyList(),
                applyBlockers = listOf("diagnostics.unavailable: virtual preview diagnostics failed"),
                applyEligible = false,
            )
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String =
        "${diagnostic.code.orEmpty()}\u0000${diagnostic.message}"

    private fun importPreviewToJson(detail: ExternalImportPreview, snapshot: ProjectSnapshot): JsonElement {
        val plan = detail.plan
        val diff = PatchDiffRenderer.render(snapshot, plan.workspaceEdit)
        val diagnostics = boundedDiagnostics(detail.diagnosticsAfterPreview)
        val changes = fileChanges(plan.workspaceEdit, detail.primaryFile)
        val provenance = detail.provenance
        val response = ImportPreviewResponseDto(
            planId = plan.id.value,
            operation = plan.operation,
            status = plan.status.name.lowercase(),
            legacyStatus = plan.status.name,
            summary = plan.summary,
            confidence = plan.confidence,
            riskLevel = plan.riskLevel.name.lowercase(),
            legacyRiskLevel = plan.riskLevel.name,
            evidence = listOf(plan.evidence.name.lowercase()),
            legacyEvidence = plan.evidence.name,
            affectedFiles = changes,
            affectedFilePaths = changes.map(ProtocolFileChangeDto::path),
            primaryFile = detail.primaryFile?.let(ProtocolPath::serialize),
            placement = PlacementDto(
                moduleName = detail.resolvedModule,
                sourceRoot = detail.resolvedSourceRoot?.let(ProtocolPath::serialize),
                sourceSet = detail.sourceSet?.name?.lowercase(),
                packageName = detail.resolvedPackage,
            ),
            resolvedModule = detail.resolvedModule,
            resolvedSourceRoot = detail.resolvedSourceRoot?.let(ProtocolPath::serialize),
            sourceSet = detail.sourceSet?.name,
            resolvedPackage = detail.resolvedPackage,
            packageChanges = detail.packageChanges.map { PackageChangeDto(it.from, it.to) },
            renderedDiff = diff.renderedDiff,
            structuredDiff = diff.files.map { file -> ProtocolFileDiffDto(
                path = ProtocolPath.serialize(file.path),
                change = file.change.name.lowercase(),
                previousPath = file.previousPath?.let(ProtocolPath::serialize),
                hunks = file.hunks.map { hunk -> ProtocolDiffHunkDto(
                    hunk.oldStart, hunk.oldLines, hunk.newStart, hunk.newLines, hunk.lines, hunk.truncated,
                ) },
                truncated = file.truncated,
            ) },
            diffTruncated = diff.truncated,
            diffTruncationReasons = diff.truncationReasons,
            diffLimits = ProtocolDiffLimitsDto(
                ProtocolLimits.MAX_PREVIEW_DIFF_BYTES,
                ProtocolLimits.MAX_PREVIEW_DIFF_FILES,
                ProtocolLimits.MAX_PREVIEW_HUNKS_PER_FILE,
                ProtocolLimits.MAX_PREVIEW_LINES_PER_HUNK,
            ),
            warnings = plan.warnings.map(::portableEvidence),
            diagnosticsAfterPreview = diagnostics.items,
            diagnosticsTruncated = diagnostics.truncated,
            provenance = ImportProvenanceDto(
                sourceKind = provenance?.sourceKind?.name?.lowercase(),
                legacySourceKind = provenance?.sourceKind?.name,
                sourceUrl = provenance?.sourceUrl,
                retrievedAt = provenance?.retrievedAt,
                detectedLicense = provenance?.licenseDetected?.takeUnless { it == "unknown" },
                licenseDetected = provenance?.licenseDetected,
                licenseRisk = provenance?.licenseRisk?.name?.lowercase(),
                licensePolicy = detail.licensePolicy.name.lowercase().replace('_', '-'),
                originalHash = provenance?.originalHash,
                notices = detail.acknowledgementRequirements,
            ),
            unresolvedDependencies = detail.unresolvedDependencies,
            conflicts = detail.conflicts.map(::portableEvidence),
            refusalReasons = detail.refusalReasons.map(::portableEvidence),
            applyEligibility = ApplyEligibilityDto(
                detail.applyEligible,
                detail.applyBlockers.map(::portableEvidence),
                detail.acknowledgementRequirements,
            ),
            applyEligible = detail.applyEligible,
            staleness = StalenessDto(false, emptyList()),
            snapshot = SnapshotEvidenceDto(plan.snapshotHash, true),
            provider = ProtocolProviderDto("refactorkit-java-external-importer", RefactorKitVersion.VERSION),
        )
        return PROTOCOL_JSON.encodeToJsonElement(response)
    }

    private fun boundedDiagnostics(diagnostics: List<Diagnostic>): BoundedDiagnostics {
        val items = mutableListOf<ProtocolDiagnosticDto>()
        var bytes = 0
        for (diagnostic in diagnostics.take(ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS)) {
            val item = diagnosticDto(diagnostic)
            val itemBytes = PROTOCOL_JSON.encodeToString(item).toByteArray(Charsets.UTF_8).size
            if (bytes + itemBytes > ProtocolLimits.MAX_PREVIEW_DIAGNOSTIC_BYTES) break
            items += item
            bytes += itemBytes
        }
        return BoundedDiagnostics(
            items,
            diagnostics.size > items.size || diagnostics.size > ProtocolLimits.MAX_PREVIEW_DIAGNOSTICS,
        )
    }

    private fun diagnosticDto(diagnostic: Diagnostic): ProtocolDiagnosticDto = ProtocolDiagnosticDto(
        severity = when (diagnostic.severity) {
            Diagnostic.Severity.ERROR -> "error"
            Diagnostic.Severity.WARNING -> "warning"
            Diagnostic.Severity.INFO -> "information"
        },
        message = if (diagnostic.message.length <= ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS) {
            diagnostic.message
        } else {
            diagnostic.message.take(ProtocolLimits.MAX_DIAGNOSTIC_MESSAGE_CHARS) + "… [truncated]"
        },
        code = diagnostic.code,
        path = diagnostic.location?.path?.let(ProtocolPath::serialize),
        line = diagnostic.location?.range?.start?.line?.plus(1),
        column = diagnostic.location?.range?.start?.character?.plus(1),
        evidence = diagnostic.evidence?.name?.lowercase(),
        category = diagnostic.category?.name?.lowercase(),
    )

    private data class BoundedDiagnostics(
        val items: List<ProtocolDiagnosticDto>,
        val truncated: Boolean,
    )

    private fun fileChanges(
        edit: WorkspaceEdit,
        primaryFile: Path? = null,
        rollback: Boolean = false,
    ): List<ProtocolFileChangeDto> = edit.edits.map { fileEdit ->
        val (kind, path, previous) = if (!rollback) {
            when (fileEdit) {
                is FileEdit.Create -> Triple(FileChangeKind.CREATE, fileEdit.path, null)
                is FileEdit.Modify -> Triple(FileChangeKind.MODIFY, fileEdit.path, null)
                is FileEdit.Delete -> Triple(FileChangeKind.DELETE, fileEdit.path, null)
                is FileEdit.Rename -> Triple(FileChangeKind.MOVE, fileEdit.newPath, fileEdit.path)
            }
        } else {
            when (fileEdit) {
                is FileEdit.Create -> Triple(FileChangeKind.DELETE, fileEdit.path, null)
                is FileEdit.Modify -> Triple(FileChangeKind.MODIFY, fileEdit.path, null)
                is FileEdit.Delete -> Triple(FileChangeKind.CREATE, fileEdit.path, null)
                is FileEdit.Rename -> Triple(FileChangeKind.MOVE, fileEdit.path, fileEdit.newPath)
            }
        }
        ProtocolFileChangeDto(
            change = kind.name.lowercase(),
            path = ProtocolPath.serialize(path),
            previousPath = previous?.let(ProtocolPath::serialize),
            primary = primaryFile?.normalize() == path.normalize(),
        )
    }.distinctBy { "${it.change}\u0000${it.previousPath.orEmpty()}\u0000${it.path}" }
        .sortedWith(compareBy(ProtocolFileChangeDto::path, ProtocolFileChangeDto::change))

    private fun portableEvidence(value: String): String = value.replace('\\', '/')

    private fun parseJavaFormatterProfile(
        value: JsonElement?,
        languageId: String,
        operation: String,
        allowBatchCleanup: Boolean = false,
    ): org.refactorkit.java.JavaFormatterProfile? {
        if (value == null) return null
        if (languageId != "java" || (operation != "formatFile" && !(allowBatchCleanup && operation == "batchCleanup"))) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "formatterProfile is supported only for Java formatFile previews")
        }
        val fields = value as? JsonObject
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "formatterProfile must be an object")
        if (fields.keys != setOf("path", "profileName", "xml")) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "formatterProfile requires exactly path, profileName, and xml")
        }
        fun stringField(name: String): String {
            val primitive = fields[name] as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "formatterProfile.$name must be a string")
            }
            return primitive.content
        }
        return try {
            org.refactorkit.java.JavaFormatterProfile.create(
                stringField("path"), stringField("profileName"), stringField("xml"),
            )
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid formatterProfile")
        }
    }

    private fun moveClassPromotionAttempt(args: Map<String, String>): JavaMoveClassPromotionAttemptMetadata =
        try {
            JavaMoveClassPromotionAttemptMetadata.fromRaw(
                approval = args["approval"],
                warningAcknowledgement = args["warningAcknowledgement"],
                confidence = args["confidence"],
                force = args["force"],
            )
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                "arguments.${failure.message ?: "promotion metadata is invalid"}",
            )
        }

    private fun planToJson(
        plan: PatchPlan,
        referenceAnalysis: JsonObject? = null,
        providerName: String = "refactorkit-java",
    ): JsonObject = buildJsonObject {
        val saved = requireSnapshot()
        val diff = PatchDiffRenderer.render(saved.copy(files = saved.trackedFiles, auxiliaryFiles = emptyList()), plan.workspaceEdit)
        put("renderedDiff", diff.renderedDiff)
        put("diffTruncated", diff.truncated)
        put("diffTruncationReasons", buildJsonArray { diff.truncationReasons.forEach { add(JsonPrimitive(it)) } })
        put("planId", plan.id.value)
        plan.authorityLease?.attributes?.get("javaSelectionHash")?.let { put("selectionHash", it) }
        put("operation", plan.operation)
        put("status", plan.status.name)
        put("summary", plan.summary)
        put("confidence", plan.confidence)
        put("riskLevel", plan.riskLevel.name)
        put("evidence", plan.evidence.name)
        put("affectedFiles", buildJsonArray {
            plan.affectedFiles.sortedBy(ProtocolPath::serialize).forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
        })
        put("structuredDiff", buildJsonArray {
            plan.workspaceEdit.edits.forEach { edit ->
                add(buildJsonObject {
                    put("type", when (edit) {
                        is FileEdit.Create -> "createFile"
                        is FileEdit.Modify -> "modifyFile"
                        is FileEdit.Delete -> "deleteFile"
                        is FileEdit.Rename -> "renameFile"
                    })
                    put("path", ProtocolPath.serialize(edit.path))
                    if (edit is FileEdit.Rename) put("newPath", ProtocolPath.serialize(edit.newPath))
                })
            }
        })
        put("warnings", buildJsonArray { plan.warnings.forEach { add(JsonPrimitive(it)) } })
        referenceAnalysis?.let { put("referenceAnalysis", it) }
        plan.referenceAnalysis?.let { analysis ->
            put("referenceAnalysis", buildJsonObject {
                put("schemaVersion", analysis.schemaVersion)
                put("scope", analysis.scope)
                put("completeness", analysis.completeness.name)
                put("completeProject", analysis.completeProject)
                put("managedWriteAuthority", analysis.managedWriteAuthority)
                put("warnings", buildJsonArray { analysis.warnings.forEach { add(JsonPrimitive(it)) } })
                put("references", buildJsonArray {
                    analysis.references.forEach { reference ->
                        add(buildJsonObject {
                            put("languageId", reference.languageId)
                            put("kind", reference.kind.id)
                            put("targetOwner", reference.targetOwner)
                            put("targetMember", reference.targetMember)
                            put("resourceKind", reference.resourceKind.id)
                            put("provenance", reference.provenance.value)
                            put("completeness", reference.completeness.name)
                            put("location", buildJsonObject {
                                put("path", ProtocolPath.serialize(reference.location.path))
                                put("range", buildJsonObject {
                                    put("start", buildJsonObject {
                                        put("line", reference.location.range.start.line)
                                        put("character", reference.location.range.start.character)
                                    })
                                    put("end", buildJsonObject {
                                        put("line", reference.location.range.end.line)
                                        put("character", reference.location.range.end.character)
                                    })
                                })
                            })
                        })
                    }
                })
            })
        }
        if (!plan.managedWriteEligible || plan.referenceAnalysis?.let {
                !it.completeProject || it.completeness != org.refactorkit.core.PlanReferenceCompleteness.COMPLETE
            } == true
        ) put("managedWriteEligibility", "INELIGIBLE")
        put("diagnosticsAfterPreview", buildJsonArray {
            plan.diagnosticsAfterPreview.forEach { d ->
                add(buildJsonObject {
                    put("severity", d.severity.name)
                    put("message", d.message)
                    d.code?.let { put("code", it) }
                    d.evidence?.let { put("evidence", it.name) }
                    d.category?.let { put("category", it.name) }
                    d.location?.let { loc ->
                        put("file", ProtocolPath.serialize(loc.path))
                        put("line", loc.range.start.line + 1)
                    }
                })
            }
        })
        put("snapshot", buildJsonObject {
            put("hash", plan.snapshotHash)
            put("validatedOnApply", true)
        })
        put("provider", buildJsonObject {
            put("name", if (plan.operation == "importExternalJavaClass") "refactorkit-java-external-importer" else providerName)
            put("version", RefactorKitVersion.VERSION)
        })
    }

    private fun safeDeleteReferenceAnalysisCapabilityJson(): JsonObject = buildJsonObject {
        put("schemaVersion", org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.SCHEMA_VERSION)
        put("scope", org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.SCOPE)
        put("resultField", "referenceAnalysis")
        put("statusValues", buildJsonArray {
            add(JsonPrimitive("complete"))
            add(JsonPrimitive("incomplete"))
        })
        put("managedApplyAuthority", "conditional-snapshot-bound")
        put("completeProject", false)
        put("referenceClosureValues", buildJsonArray {
            add(JsonPrimitive("incomplete"))
            add(JsonPrimitive(org.refactorkit.java.JavaSafeDeletePlanner.MANAGED_REFERENCE_CLOSURE))
        })
        put("managedApplyRequirements", buildJsonArray {
            listOf("maven-effective-v1", "complete-source-ownership", "complete-text-scan", "clean-jdt-bindings",
                "no-generated-sources", "no-binary-resources", "no-unmodeled-inputs", "no-dynamic-lookup",
                "sdk-bound-java-context")
                .forEach { add(JsonPrimitive(it)) }
        })
    }

    private fun org.refactorkit.java.JavaSafeDeleteReferenceAnalysis.toJson(): JsonObject = buildJsonObject {
        put("schemaVersion", schemaVersion)
        put("operation", operation)
        put("scope", scope)
        put("status", status.wire)
        if (inputCount == null) put("inputCount", JsonNull) else put("inputCount", inputCount)
        if (verifiedBytes == null) put("verifiedBytes", JsonNull) else put("verifiedBytes", verifiedBytes)
        put("snapshotHash", snapshotHash)
        put("managedApplyAuthority", managedApplyAuthority)
        put("completeProject", completeProject)
        put("referenceClosure", "incomplete")
    }

    private fun refusedImportTarget(
        snapshot: ProjectSnapshot,
        refusal: org.refactorkit.java.JavaImportTargetRefusal,
    ): ExternalImportPreview {
        val plan = PatchPlan(
            operation = "importExternalJavaClass",
            status = PatchStatus.REFUSED,
            snapshotHash = snapshot.hash,
            confidence = 0.0,
            requiresUserApproval = false,
            summary = refusal.message,
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            warnings = listOf("${refusal.code}: ${refusal.message}", "Next action: ${refusal.nextAction}") + refusal.evidence,
            riskLevel = RiskLevel.HIGH,
        )
        val evidence = refusal.evidence.associate { item ->
            item.substringBefore('=') to item.substringAfter('=', "")
        }
        return ExternalImportPreview(
            plan = plan,
            primaryFile = null,
            resolvedModule = evidence["resolvedModule"],
            resolvedSourceRoot = evidence["sourceRoot"]?.let(Path::of),
            sourceSet = evidence["sourceSet"]?.let { value ->
                runCatching { org.refactorkit.java.JavaSourceSet.valueOf(value) }.getOrNull()
            },
            resolvedPackage = evidence["resolvedPackage"].orEmpty(),
            packageChanges = emptyList(),
            provenance = null,
            unresolvedDependencies = emptyList(),
            conflicts = emptyList(),
            refusalReasons = listOf(refusal.code),
            applyEligible = false,
        )
    }

    override fun close() {
        closeSavedWorkspaceWatcher()
        closeSemanticAdapters()
        adapter.clearSemanticCache()
        pendingPlans.clear()
        moveClassDispatcher.clearLexicalReviewAudit()
        snapshot = null
        workspaceRoot = null
        workspaceIndex.clear()
    }

    private data class TypeScriptIndexRefresh(
        val status: String,
        val generation: Long,
        val symbolCount: Int = 0,
        val truncated: Boolean = false,
        val failure: Diagnostic? = null,
    )

    private data class PendingPlan(
        val plan: PatchPlan,
        val importPreview: ExternalImportPreview? = null,
        val languageId: String = "java",
        val semanticLease: String? = null,
        val indexGeneration: Long? = null,
        val diagnosticsGate: DiagnosticsGate? = null,
        val formatterProfileSha256: String? = null,
    )

    private data class DaemonMethodCapability(
        val name: String,
        val stability: String,
        val requiresProject: Boolean,
        val writesWorkspace: Boolean,
        val features: Map<String, Boolean> = emptyMap(),
    )

    companion object {
        private val PROTOCOL_JSON = Json { encodeDefaults = true; explicitNulls = true }

        private val SCRIPT_EXTENSIONS = mapOf(
            "ts" to "typescript", "tsx" to "typescript",
            "js" to "javascript", "jsx" to "javascript",
            // C is the admitted 0.8.0 language: its sources/headers must enter the
            // workspace inventory so c.preview runs against the real scanner snapshot.
            "c" to "c", "h" to "c",
        )

        private val DAEMON_METHODS = listOf(
            DaemonMethodCapability("java.platformSource.lookup", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "selectedSdkPlatform" to true, "immutableEditorContext" to true, "readOnly" to true)),
            DaemonMethodCapability("java.platformSource.declaration", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "selectedSdkPlatform" to true, "immutableEditorContext" to true, "exactJvmDeclarationRanges" to true, "readOnly" to true)),
            DaemonMethodCapability("java.platformSource.read", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "selectedSdkPlatform" to true, "immutableEditorContext" to true, "associationHashGuard" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.editor.lookup", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "immutableEditorContext" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.editor.declaration", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "immutableEditorContext" to true, "exactJvmDeclarationRanges" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.editor.read", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "immutableEditorContext" to true, "associationHashGuard" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.declaration", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "associationHashGuard" to true, "exactJvmDeclarationRanges" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.lookup", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "providerOwnedAttachments" to true, "readOnly" to true)),
            DaemonMethodCapability("java.dependencySource.read", "experimental", true, false,
                mapOf("typedSchemaV1" to true, "sdkContextBound" to true, "associationHashGuard" to true, "readOnly" to true)),
            DaemonMethodCapability("server.version", "beta-contract", false, false),
            DaemonMethodCapability("server.capabilities", "beta-contract", false, false),
            DaemonMethodCapability("project.open", "beta-contract", false, false,
                mapOf("mavenRuntime" to true)),
            DaemonMethodCapability("project.sync", "experimental", true, true,
                mapOf("effectiveGradleContext" to true, "explicitBuildExecution" to true, "offlineDependencyResolution" to true,
                    "configuredSdk" to true, "separateGradleRuntimeAndCompilerSdk" to true,
                    "boundedMetadataExport" to true, "noRequestedCompilationTask" to true)),
            DaemonMethodCapability(
                "project.context", "experimental", true, false,
                mapOf("typedSchemaV2" to true, "typedSchemaV3" to true, "providerQualifiedSourceAssociation" to true,
                    "sourceSetKindSelection" to true,
                    "snapshotBound" to true, "configuredSdkEvidence" to true,
                    "orderedCompileRuntimeClasspaths" to true, "sharedJdtBuildContext" to true,
                    "mavenEffectiveContext" to true, "gradleEffectiveContext" to true,
                    "gradlePartialContext" to true, "gradleCompositeBuildIdentity" to true,
                    "gradleBuildTreeProjectPath" to true,
                    "sourceAssociation" to true, "staleEvidenceRefusal" to true,
                    "sourceRootProvenance" to true, "compilerOptions" to true,
                    "resolvedArtifactCoordinates" to true, "annotationProcessorContext" to true,
                    "jpmsModulePath" to true, "requiredFactRefusals" to true,
                    "javaQueriesUseContextAuthority" to true, "javaRefactoringsUseContextAuthority" to true,
                    "immutableJavaEditorOverlays" to true, "managedMutationAuthority" to true),
            ),
            DaemonMethodCapability(
                "project.context.v4", "experimental", true, false,
                mapOf("typedSchemaV4" to true, "providerQualifiedSourceAssociation" to true,
                    "sourceSetKindSelection" to true, "mavenFailsafeExecutionMetadata" to true,
                    "explicitSelectorAndReportValuesOnly" to true, "readOnlyBuildDiscovery" to true),
            ),
            DaemonMethodCapability(
                "project.context.v5", "experimental", true, false,
                mapOf("typedSchemaV5" to true, "providerQualifiedSourceAssociation" to true,
                    "sourceSetKindSelection" to true, "mavenFailsafeExecutionMetadata" to true,
                    "standardJpmsDirectives" to true, "closedLegacySchemas" to true,
                    "explicitSelectorAndReportValuesOnly" to true, "readOnlyBuildDiscovery" to true),
            ),
            DaemonMethodCapability(
                "project.context.v6", "experimental", true, false,
                mapOf("typedSchemaV6" to true, "providerQualifiedSourceAssociation" to true,
                    "sourceSetKindSelection" to true, "mavenFailsafeExecutionMetadata" to true,
                    "standardJpmsDirectives" to true, "explicitSelectorAndReportValuesOnly" to true,
                    "readOnlyBuildDiscovery" to true, "metadataOnlyUnqualifiedJavaSourceAnalysis" to true),
            ),
            DaemonMethodCapability(
                "project.context.v7", "experimental", true, false,
                mapOf("typedSchemaV7" to true, "providerQualifiedSourceAssociation" to true,
                    "gradleBuildTreeProjectPath" to true,
                    "sourceSetKindSelection" to true, "mavenFailsafeExecutionMetadata" to true,
                    "mavenTestJvmSettings" to true, "testJvmSettingsAreSourceSetBound" to true,
                    "explicitOriginsOnly" to true,
                    "preservesArgLineLateTokens" to true, "standardJpmsDirectives" to true,
                    "readOnlyBuildDiscovery" to true,
                    "metadataOnlyUnqualifiedJavaSourceAnalysis" to true),
            ),
            DaemonMethodCapability(
                "project.context.v7.testJvmSelection", "experimental", true, false,
                mapOf("typedSchemaV7" to true, "mavenTestJvmSelection" to true,
                    "testJvmSelectionIsExecutionBound" to true, "mavenTestJvmToolchainPrerequisite" to true,
                    "readOnlyBuildDiscovery" to true),
            ),
            DaemonMethodCapability(
                "project.context.aggregate", "experimental", true, false,
                mapOf("typedSchemaV3" to true, "aggregateGradleCompositeBuildIdentity" to true,
                    "gradleBuildTreeProjectPath" to true,
                    "requiresExplicitGradleSync" to true, "snapshotBound" to true, "readOnly" to true),
            ),
            DaemonMethodCapability(
                "project.summary", "beta-contract", true, false,
                mapOf(
                    "buildModelSummary" to true,
                    "sourceSets" to true,
                    "credentialRedaction" to true,
                    "gradleCompositeBuildIdentity" to true,
                    "boundedBuildModelSummary" to true,
                    "typedBuildModelSchema" to true,
                    "offlineMissingStatus" to true,
                    "executionRefusedStatus" to true,
                ),
            ),
            DaemonMethodCapability(
                "index.status", "additive-api-0.2", true, false,
                mapOf(
                    "wholeWorkspaceSourceInventory" to true,
                    "providerContributions" to true,
                    "snapshotBound" to true,
                    "bounded" to true,
                ),
            ),
            DaemonMethodCapability(
                "workspace.watch.status", "experimental", true, false,
                mapOf("recursiveSavedFiles" to true, "boundedDirectories" to true, "readOnly" to true),
            ),
            DaemonMethodCapability(
                "workspace.refresh", "experimental", true, false,
                mapOf(
                    "reconcile" to true, "providerInvalidation" to true,
                    "semanticLeaseInvalidation" to true, "typedSchema" to true,
                ),
            ),
            DaemonMethodCapability(
                "intelligence.query", "experimental", true, false,
                mapOf(
                    "workspaceSymbols" to true,
                    "documentSymbols" to true,
                    "immutableEditorOverlayDocumentSymbols" to true,
                    "semanticLease" to true,
                    "completion" to true,
                    "immutableEditorOverlayCompletion" to true,
                    "hover" to true,
                    "immutableEditorOverlayHover" to true,
                    "javaSavedSnapshotHover" to true,
                    "javaImmutableEditorOverlayDiagnostics" to true,
                    "javaEntryPointsClassicDeclared" to true,
                    "javaEntryPointsEnclosingType" to true,
                    "javaEntryPointsBoundedFrame" to true,
                    "javaEntryPointsCancellation" to true,
                    "javaEntryPointsInherited" to false,
                    "javaEntryPointsJava25" to false,
                    "javaEntryPointsV2" to true,
                    "javaEntryPointsV2Inherited" to true,
                    "javaEntryPointsV2Java25" to true,
                    "javaEntryPointsV2SavedSnapshot" to true,
                    "signatureHelp" to true,
                    "immutableEditorOverlaySignatureHelp" to true,
                    "definitionAtPosition" to true,
                    "javaSavedSnapshotDefinition" to true,
                    "kotlinDefinitionToolchain" to true,
                    "typeDefinitionAtPosition" to true,
                    "javaSavedSnapshotTypeDefinition" to true,
                    "javaImmutableEditorOverlayTypeDefinition" to true,
                    "typeHierarchyAtPosition" to true,
                    "javaSavedSnapshotTypeHierarchy" to true,
                    "javaImmutableEditorOverlayTypeHierarchy" to true,
                    "callHierarchyAtPosition" to true,
                    "javaSavedSnapshotCallHierarchy" to true,
                    "javaImmutableEditorOverlayCallHierarchy" to true,
                    "javaResolvedClasspathCallHierarchyTargets" to true,
                    "implementationsAtPosition" to true,
                    "javaSavedSnapshotImplementations" to true,
                    "javaImmutableEditorOverlayImplementations" to true,
                    "snapshotKeyedJdtCache" to true,
                    "referencesAtPosition" to true,
                    "javaSavedSnapshotReferences" to true,
                    "snapshotBound" to true,
                    "typedRefusal" to true,
                    "cooperativeCancellation" to true,
                    "barrierAwarePriorityScheduling" to true,
                    "bounded" to true,
                ),
            ),
            DaemonMethodCapability(
                "intelligence.cancel", "experimental", false, false,
                mapOf("requestIdCancellation" to true, "jsonRpcCancellationError" to true),
            ),
            DaemonMethodCapability(
                "typescript.semantic.start", "experimental", true, false,
                mapOf(
                    "explicitToolchain" to true,
                    "hashBoundProvenance" to true,
                    "noPackageScripts" to true,
                    "workspaceIndexContribution" to true,
                    "aggregateSymbolDeadline" to true,
                    "explicitSymbolTruncation" to true,
                ),
            ),
            DaemonMethodCapability(
                "typescript.semantic.restart", "experimental", true, false,
                mapOf("workspaceIndexRefresh" to true, "semanticLeaseRotation" to true),
            ),
            DaemonMethodCapability("typescript.semantic.stop", "experimental", true, false),
            DaemonMethodCapability("symbol.search", "beta-contract", true, false),
            DaemonMethodCapability("symbol.definition", "beta-contract", true, false),
            DaemonMethodCapability("symbol.references", "beta-contract", true, false),
            DaemonMethodCapability("diagnostics", "beta-contract", true, false),
            DaemonMethodCapability(
                TypeScriptDiagnosticsProtocol.METHOD, "additive-api-0.2", true, false,
                mapOf(
                    "requestCorrelation" to true,
                    "semanticLease" to true,
                    "savedDiskAuthority" to true,
                    "immutableEditorOverlay" to true,
                    "exactUtf16Ranges" to true,
                    "structuredReadiness" to true,
                    "compilerAttestation" to true,
                    "boundedResponse" to true,
                ),
            ),
            DaemonMethodCapability(
                "kotlin.semantic.start", "experimental", true, false,
                mapOf("explicitToolchain" to true, "hashBoundProvenance" to true, "noBuildExecution" to true),
            ),
            DaemonMethodCapability("kotlin.semantic.stop", "experimental", true, false),
            DaemonMethodCapability(
                "kotlin.diagnostics", "experimental", true, false,
                mapOf("compilerBacked" to true, "semanticLease" to true, "processAttestation" to true),
            ),
            DaemonMethodCapability(
                "kotlin.symbols", "experimental", true, false,
                mapOf(
                    "compilerBacked" to true, "exactUtf16Range" to true,
                    "documentFilter" to true, "semanticLease" to true,
                ),
            ),
            DaemonMethodCapability(
                "kotlin.definition", "experimental", true, false,
                mapOf("compilerBacked" to true, "opaqueSymbolId" to true, "semanticLease" to true),
            ),
            DaemonMethodCapability("typescript.refactorings", "additive-api-0.2", true, false),
            DaemonMethodCapability("refactor.preview", "beta-contract", true, false),
            DaemonMethodCapability("c.preview", "experimental", true, false),
            DaemonMethodCapability("refactor.editor.preview", "experimental", true, false, mapOf("sdkAuthority" to true,"immutableEditorOverlay" to true,"versionedDocumentChanges" to true,"fileRename" to true,"renderedDiff" to true,"retainedPlanHash" to true,"apply" to false,"exactEditorCursorSelection" to true,"durableEditorMutationMode" to true,"qualifiedDirtyApply" to true)),
            DaemonMethodCapability("refactor.editor.prepare", "experimental", true, false, mapOf("editorOwnedEdits" to true,"planHashApproval" to true,"sdkAuthority" to true,"versionedDocuments" to true,"durableRecovery" to true,"qualifiedDirtyApply" to true)),
            DaemonMethodCapability("refactor.editor.confirm", "experimental", true, false, mapOf("editorOwnedEdits" to true,"planHashApproval" to true,"sdkAuthority" to true,"versionedDocuments" to true,"durableRecovery" to true,"qualifiedDirtyApply" to true)),
            DaemonMethodCapability("refactor.editor.undo.prepare", "experimental", true, false, mapOf("editorOwnedEdits" to true,"planHashApproval" to true,"sdkAuthority" to true,"versionedDocuments" to true,"durableRecovery" to true,"qualifiedDirtyApply" to true)),
            DaemonMethodCapability("refactor.editor.undo.confirm", "experimental", true, false, mapOf("editorOwnedEdits" to true,"planHashApproval" to true,"sdkAuthority" to true,"versionedDocuments" to true,"durableRecovery" to true,"qualifiedDirtyApply" to true)),
            DaemonMethodCapability("refactor.editor.recover","experimental",true,false,mapOf("durableRecovery" to true,"guardedInverse" to true)),
            DaemonMethodCapability("refactor.editor.transactions","experimental",true,false,mapOf("durableDiscovery" to true)),
            DaemonMethodCapability("refactor.apply", "beta-contract", true, true),
            DaemonMethodCapability("refactor.discard", "beta-contract", false, false),
            DaemonMethodCapability("patch.recover", "experimental", false, true),
            DaemonMethodCapability("patch.rollback", "beta-contract", true, true),
            DaemonMethodCapability(
                "java.importExternalClass",
                "experimental",
                true,
                false,
                mapOf(
                    "targetDirectory" to true,
                    "preview" to true,
                    "renderedDiff" to true,
                    "structuredDiff" to true,
                    "previewDiagnostics" to true,
                    "apply" to true,
                    "discard" to true,
                    "rollback" to true,
                ),
            ),
            DaemonMethodCapability(
                "java.createMavenModule",
                "stable",
                true,
                false,
                mapOf(
                    "preview" to true,
                    "renderedDiff" to true,
                    "apply" to true,
                    "rollback" to true,
                ),
            ),
            DaemonMethodCapability(
                "java.createGradleModule",
                "stable",
                true,
                false,
                mapOf(
                    "preview" to true,
                    "renderedDiff" to true,
                    "apply" to true,
                    "rollback" to true,
                    "workspaceRelativeBuildRoot" to true,
                    "canonicalNestedProjectPath" to true,
                    "settingsInferredDsl" to true,
                ),
            ),
            DaemonMethodCapability(
                "java.renameMavenModule",
                "experimental",
                true,
                false,
                mapOf(
                    "preview" to true,
                    "renderedDiff" to true,
                    "apply" to true,
                    "rollback" to true,
                    "nestedModuleDirectoryMove" to true,
                ),
            ),
            DaemonMethodCapability(
                "java.renameGradleModule",
                "experimental",
                true,
                false,
                mapOf(
                    "preview" to true,
                    "renderedDiff" to true,
                    "apply" to true,
                    "rollback" to true,
                    "literalSettingsOnly" to true,
                    "preservePhysicalProjectDirectory" to true,
                    "explicitGradleSyncRequired" to true,
                    // Keep the original discovery gate for settings-only clients.
                    "inboundProjectDependenciesRefused" to true,
                    "literalInboundProjectDependencies" to true,
                    "unqualifiedInboundProjectDependenciesRefused" to true,
                ),
            ),
        )
    }
}

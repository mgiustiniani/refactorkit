package org.refactorkit.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.LanguageCapabilityProtocol
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaFormatFilePlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaMoveSourceRootPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.kotlin.KotlinAdapterRegistration
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerDiagnosticsResult
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinPrivateDeclarationRenamePlanner
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
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptSemanticStart
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import org.refactorkit.typescript.TypeScriptToolchainDiscoverer
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainDiscoveryPolicy
import org.refactorkit.typescript.TypeScriptToolchainRequest
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.stream.Collectors

private const val PROTOCOL_VERSION = "2024-11-05"

/**
 * MCP (Model Context Protocol) session for RefactorKit.
 *
 * Exposes deterministic refactoring tools to local LLM agents via the MCP protocol
 * (JSON-RPC 2.0 over stdio with NDJSON framing).
 */
class McpSession(
    private val toolchainDiscovery: (TypeScriptToolchainRequest, TypeScriptToolchainDiscoveryPolicy) -> TypeScriptToolchainDiscovery =
        { request, policy -> TypeScriptToolchainDiscoverer(policy).discover(request) },
    private val semanticAdapterFactory: (String, TypeScriptSemanticToolchain, org.refactorkit.typescript.TypeScriptProjectModel) -> TypeScriptSemanticAdapter =
        { languageId, toolchain, model -> TypeScriptSemanticAdapter(languageId, toolchain, model) },
    private val kotlinToolchainDiscovery: (KotlinToolchainRequest, KotlinToolchainDiscoveryPolicy) -> KotlinToolchainDiscovery =
        { request, policy -> KotlinToolchainDiscoverer(policy).discover(request) },
) : AutoCloseable {
    private val adapter = JavaLanguageAdapter()
    private val scanner = JavaProjectScanner()

    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var workspaceRoot: Path? = null
    private val semanticAdapters = linkedMapOf<String, TypeScriptSemanticAdapter>()
    private val semanticLeases = linkedMapOf<String, String>()
    private var kotlinAdapter = KotlinLanguageAdapter()
    private var kotlinToolchain: KotlinSemanticToolchain? = null
    private var kotlinSemanticLease: String? = null
    private val pendingPlans = object : LinkedHashMap<String, PendingPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingPlan>?) = size > ProtocolLimits.MAX_PENDING_PLANS
    }

    var onExit: () -> Unit = {}

    // ── protocol dispatcher ───────────────────────────────────────────────────

    fun dispatch(method: String, params: JsonObject?): JsonElement = when (method) {
        "initialize"              -> initialize(params)
        "notifications/initialized" -> JsonNull
        "ping"                    -> buildJsonObject {}
        "tools/list"              -> toolsList()
        "tools/call"              -> toolsCall(params)
        "resources/list"          -> resourcesList()
        "resources/templates/list" -> resourcesTemplatesList()
        "resources/read"          -> resourcesRead(params)
        "prompts/list"            -> promptsList()
        "prompts/get"             -> promptsGet(params)
        else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
    }

    // ── MCP lifecycle ─────────────────────────────────────────────────────────

    private fun initialize(params: JsonObject?): JsonElement = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject { put("listChanged", false) })
            put("resources", buildJsonObject { put("subscribe", false); put("listChanged", false) })
            put("prompts", buildJsonObject { put("listChanged", false) })
        })
        put("serverInfo", buildJsonObject {
            put("name", "refactorkit")
            put("version", RefactorKitVersion.VERSION)
        })
        put("refactorkitLanguageKernel", LanguageCapabilityProtocol.render(
            listOf(
                JavaAdapterRegistration.create().descriptor,
                KotlinAdapterRegistration.descriptor(),
            ) + TypeScriptAdapterDescriptors.descriptors(),
        ))
    }

    // ── tools ─────────────────────────────────────────────────────────────────

    private fun toolsList(): JsonElement = buildJsonObject {
        put("tools", buildJsonArray {
            add(tool("project_scan", "Scan and index a Java project.",
                required = listOf("root"),
                props = mapOf("root" to "string: absolute path to project root")))
            add(tool("project_summary", "Return summary of the currently open project.",
                required = emptyList(), props = emptyMap()))
            add(tool("typescript_semantic_start", "Start an explicit bounded TypeScript/JavaScript semantic session.",
                required = listOf("languageId", "nodeExecutable", "languageServerPackageRoot", "typeScriptPackageRoot"),
                props = mapOf(
                    "languageId" to "string: typescript | javascript",
                    "nodeExecutable" to "string: explicit Node executable path",
                    "languageServerPackageRoot" to "string: explicit typescript-language-server package root",
                    "typeScriptPackageRoot" to "string: explicit typescript package root",
                    "allowWorkspaceLocalToolchain" to "boolean: explicit workspace executable trust (default false)",
                )))
            add(tool("typescript_semantic_stop", "Stop a TypeScript/JavaScript semantic session.",
                required = listOf("languageId"), props = mapOf("languageId" to "string: typescript | javascript")))
            add(tool("kotlin_semantic_start", "Configure an explicit bounded Kotlin/JVM compiler diagnostics session.",
                required = listOf("jdkHome", "compilerJar", "compilerClasspath"),
                props = mapOf(
                    "jdkHome" to "string: explicit qualified JDK 21 home",
                    "compilerJar" to "string: kotlin-compiler-embeddable 2.0.21 JAR",
                    "compilerClasspath" to "array: explicit compiler runtime JAR paths",
                    "allowWorkspaceLocalToolchain" to "boolean: explicit workspace-local trust (default false)",
                )))
            add(tool("kotlin_semantic_stop", "Stop the configured Kotlin compiler diagnostics session.",
                required = emptyList(), props = emptyMap()))
            add(tool("kotlin_diagnostics", "Return compiler-backed Kotlin diagnostics and process attestation.",
                required = listOf("semanticLease", "expectedSnapshotHash"),
                props = mapOf(
                    "semanticLease" to "string: lease returned by kotlin_semantic_start",
                    "expectedSnapshotHash" to "string: configured Kotlin snapshot hash",
                )))
            add(tool("kotlin_symbols", "Return compiler-proven Kotlin/JVM type and function symbols with exact UTF-16 declaration ranges.",
                required = listOf("semanticLease", "expectedSnapshotHash"),
                props = mapOf(
                    "semanticLease" to "string: lease returned by kotlin_semantic_start",
                    "expectedSnapshotHash" to "string: configured Kotlin snapshot hash",
                    "query" to "string: optional case-insensitive declaration-name filter",
                    "file" to "string: optional normalized workspace-relative Kotlin source",
                )))
            add(tool("kotlin_definition", "Resolve an opaque Kotlin symbol ID against the attested saved snapshot.",
                required = listOf("semanticLease", "expectedSnapshotHash", "symbol"),
                props = mapOf(
                    "semanticLease" to "string: lease returned by kotlin_semantic_start",
                    "expectedSnapshotHash" to "string: configured Kotlin snapshot hash",
                    "symbol" to "string: opaque ID returned by kotlin_symbols",
                )))
            add(tool("kotlin_usage_definition", "Resolve a compiler-proven Kotlin function or type usage to its declaration.",
                required = listOf("semanticLease", "expectedSnapshotHash", "file", "line", "character"),
                props = mapOf(
                    "semanticLease" to "string: lease returned by kotlin_semantic_start",
                    "expectedSnapshotHash" to "string: configured Kotlin snapshot hash",
                    "file" to "string: normalized workspace-relative Kotlin source",
                    "line" to "integer: zero-based line", "character" to "integer: zero-based UTF-16 character",
                )))
            add(tool("kotlin_references", "Return bounded partial references for a compiler-proven Kotlin function or type usage.",
                required = listOf("semanticLease", "expectedSnapshotHash", "file", "line", "character"),
                props = mapOf(
                    "semanticLease" to "string: lease returned by kotlin_semantic_start",
                    "expectedSnapshotHash" to "string: configured Kotlin snapshot hash",
                    "file" to "string: normalized workspace-relative Kotlin source",
                    "line" to "integer: zero-based line", "character" to "integer: zero-based UTF-16 character",
                    "includeDeclaration" to "boolean: include the declaration (default true)",
                    "limit" to "integer: maximum returned locations",
                )))
            add(tool("symbol_search", "Search for symbols in the project by name.",
                required = listOf("query"),
                props = mapOf(
                    "query" to "string: name or partial FQN to search",
                    "languageId" to "string: java | typescript | javascript (default java)",
                )))
            add(tool("symbol_definition", "Return the definition location of a symbol.",
                required = listOf("symbol"),
                props = mapOf(
                    "symbol" to "string: fully-qualified or opaque symbol ID",
                    "languageId" to "string: java | typescript | javascript (default java)",
                )))
            add(tool("symbol_references", "Find all references to a symbol.",
                required = listOf("symbol"),
                props = mapOf("symbol" to "string: fully-qualified symbol name")))
            add(tool("diagnostics", "Return legacy human-readable diagnostics for the project.",
                required = emptyList(), props = emptyMap()))
            add(tool("diagnostics_v2", "Return IDE-grade exact compiler diagnostics with snapshot, lease, authority, ranges, and attestation.",
                required = listOf("requestId", "languageId", "expectedSnapshotHash", "semanticLease", "sourceAuthority"),
                props = mapOf(
                    "requestId" to "string: caller correlation ID",
                    "languageId" to "string: typescript | javascript",
                    "expectedSnapshotHash" to "string: opened project SHA-256",
                    "semanticLease" to "string: lease returned by typescript_semantic_start",
                    "sourceAuthority" to "object: saved-disk or immutable-editor-overlay authority",
                )))
            add(tool("available_refactorings", "List refactoring operations available for a selection.",
                required = listOf("symbol"),
                props = mapOf("symbol" to "string: fully-qualified symbol name")))
            add(tool("preview_refactoring", "Preview a refactoring operation without applying it.",
                required = listOf("operation", "symbol"),
                props = mapOf(
                    "operation" to "string: renameSymbol | renameClass | renameMember | extractMethod | changeSignature.renameParameter | changeSignature.addParameter | changeSignature.reorderParameters | changeSignature.removeParameter | moveClass | moveSourceRoot | organizeImports | formatFile | safeDelete",
                    "symbol" to "string: fully-qualified symbol name",
                    "languageId" to "string: java | kotlin | typescript | javascript (default java)",
                    "expectedSnapshotHash" to "string: required for Kotlin rename",
                    "semanticLease" to "string: required for Kotlin rename",
                    "arguments" to "object: operation-specific arguments (newName, targetPackage, file/line/character, safety overrides, etc.)",
                )))
            add(tool("apply_refactoring", "Apply a previously previewed plan.",
                required = listOf("planId"),
                props = mapOf(
                    "planId" to "string: plan ID returned by preview_refactoring",
                    "semanticLease" to "string: required for Kotlin rename apply",
                )))
            add(tool("rollback_refactoring", "Roll back a previously applied transaction; normal mode refuses post-apply changes.",
                required = listOf("transactionId"),
                props = mapOf(
                    "transactionId" to "string: transaction ID returned by apply_refactoring",
                    "force" to "boolean: explicitly overwrite post-apply changes (default false)",
                )))
            add(tool("import_external_java_class", "Import an external Java class into the project with license/conflict checks.",
                required = listOf("code", "targetPackage"),
                props = mapOf(
                    "code" to "string: Java source code to import (may contain markdown fences)",
                    "targetPackage" to "string: fully-qualified target package (e.g. com.example.util)",
                    "sourceUrl" to "string: optional URL where the code was obtained",
                    "licensePolicy" to "string: warn | block-unknown | allow (default: warn)",
                )))
            add(tool("generate_context_bundle", "Generate a structured context bundle for AI assistance.",
                required = emptyList(),
                props = mapOf(
                    "query" to "string: optional symbol search query to focus the bundle",
                    "maxSymbols" to "number: maximum symbols to include (default 50)",
                    "includeSnippets" to "boolean: include declaration snippets (default true)",
                )))
        })
    }

    private fun toolsCall(params: JsonObject?): JsonElement {
        val name = params?.string("name") ?: missing("name")
        val args = (params?.get("arguments") as? JsonObject) ?: JsonObject(emptyMap())
        return textContent(runCatching { callTool(name, args) }.fold(
            onSuccess = { it },
            onFailure = { e ->
                return buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Error: ${e.message}") })
                    })
                    put("isError", true)
                }
            },
        ))
    }

    private fun callTool(name: String, args: JsonObject): String = when (name) {
        "project_scan"          -> toolProjectScan(args)
        "project_summary"       -> toolProjectSummary()
        "typescript_semantic_start" -> toolTypeScriptSemanticStart(args)
        "typescript_semantic_stop" -> toolTypeScriptSemanticStop(args)
        "kotlin_semantic_start" -> toolKotlinSemanticStart(args)
        "kotlin_semantic_stop" -> toolKotlinSemanticStop()
        "kotlin_diagnostics" -> toolKotlinDiagnostics(args)
        "kotlin_symbols" -> toolKotlinSymbols(args)
        "kotlin_definition" -> toolKotlinDefinition(args)
        "kotlin_usage_definition" -> toolKotlinUsageNavigation(args, references = false)
        "kotlin_references" -> toolKotlinUsageNavigation(args, references = true)
        "symbol_search"         -> toolSymbolSearch(args)
        "symbol_definition"     -> toolSymbolDefinition(args)
        "symbol_references"     -> toolSymbolReferences(args)
        "diagnostics"           -> toolDiagnostics(args)
        "diagnostics_v2"        -> toolDiagnosticsV2(args)
        "available_refactorings"-> toolAvailableRefactorings(args)
        "preview_refactoring"   -> toolPreviewRefactoring(args)
        "apply_refactoring"     -> toolApplyRefactoring(args)
        "rollback_refactoring"  -> toolRollbackRefactoring(args)
        "import_external_java_class" -> toolImportExternalJavaClass(args)
        "generate_context_bundle" -> toolGenerateContextBundle(args)
        else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown tool: $name")
    }

    private fun toolTypeScriptSemanticStart(args: JsonObject): String {
        val snap = requireSnapshot()
        val root = workspaceRoot ?: error("workspace root missing")
        val languageId = args.string("languageId") ?: missing("languageId")
        if (languageId !in setOf("typescript", "javascript")) missing("languageId=typescript|javascript")
        if (languageId in semanticAdapters) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Semantic adapter is already started for $languageId",
        )
        fun configuredPath(name: String): Path {
            val raw = args.string(name) ?: missing(name)
            val candidate = Paths.get(raw)
            return if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        }
        val policy = TypeScriptToolchainDiscoveryPolicy(
            allowPathNodeDiscovery = false,
            allowWorkspaceLocalToolchain = args.string("allowWorkspaceLocalToolchain")?.toBooleanStrictOrNull() ?: false,
        )
        val discovery = toolchainDiscovery(TypeScriptToolchainRequest(
            root,
            configuredPath("nodeExecutable"),
            configuredPath("languageServerPackageRoot"),
            configuredPath("typeScriptPackageRoot"),
        ), policy)
        val toolchain = when (discovery) {
            is TypeScriptToolchainDiscovery.Available -> discovery.toolchain
            is TypeScriptToolchainDiscovery.Refused -> throw JsonRpcException(
                JsonRpcErrorCodes.INVALID_PARAMS,
                discovery.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
            )
        }
        val semantic = semanticAdapterFactory(languageId, toolchain, TypeScriptProjectModelBuilder().build(root))
        return when (val started = semantic.start(snap)) {
            is TypeScriptSemanticStart.Refused -> {
                semantic.close()
                throw JsonRpcException(
                    JsonRpcErrorCodes.INVALID_PARAMS,
                    started.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
                )
            }
            is TypeScriptSemanticStart.Started -> {
                semanticAdapters[languageId] = semantic
                val lease = "semantic-${UUID.randomUUID()}"
                semanticLeases[languageId] = lease
                "Started $languageId semantic session. Completeness: ${semantic.semanticCompleteness().mode}. " +
                    "Semantic lease: $lease. Snapshot: ${snap.hash}. " +
                    "Capabilities SHA-256: ${started.provenance?.capabilitiesSha256 ?: "test-provider"}"
            }
        }
    }

    private fun toolTypeScriptSemanticStop(args: JsonObject): String {
        val languageId = args.string("languageId") ?: missing("languageId")
        val stopped = semanticAdapters.remove(languageId)?.let {
            it.close()
            semanticLeases.remove(languageId)
            true
        } ?: false
        return if (stopped) "Stopped $languageId semantic session." else "No $languageId semantic session was running."
    }

    private fun toolKotlinSemanticStart(args: JsonObject): String {
        if (kotlinToolchain != null) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin semantic toolchain is already configured",
        )
        val current = requireSnapshot()
        val root = workspaceRoot ?: error("workspace root missing")
        fun configured(name: String): Path {
            val value = args.string(name) ?: missing(name)
            val candidate = Paths.get(value)
            return if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        }
        val classpath = (args["compilerClasspath"] as? JsonArray)?.map { value ->
            val raw = (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: missing("compilerClasspath")
            val candidate = Paths.get(raw)
            if (candidate.isAbsolute) candidate.normalize() else root.resolve(candidate).normalize()
        } ?: missing("compilerClasspath")
        val discovered = kotlinToolchainDiscovery(KotlinToolchainRequest(
            workspaceRoot = root,
            jdkHome = configured("jdkHome"),
            compilerJar = configured("compilerJar"),
            compilerClasspath = classpath,
        ), KotlinToolchainDiscoveryPolicy(
            allowWorkspaceLocalToolchain = args.boolean("allowWorkspaceLocalToolchain") ?: false,
        ))
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
            model.diagnostics.joinToString("; ") { "${it.code}: ${it.message}" },
        )
        val lease = "semantic-${UUID.randomUUID()}"
        kotlinToolchain = toolchain
        kotlinAdapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        kotlinSemanticLease = lease
        snapshot = attached
        return "Started Kotlin compiler diagnostics. Semantic lease: $lease. Snapshot: ${attached.hash}. " +
            "Toolchain SHA-256: ${toolchain.provenance.projectionHash}. Build SHA-256: ${model.attributes["projectionHash"]}."
    }

    private fun toolKotlinSemanticStop(): String {
        val stopped = kotlinToolchain != null
        snapshot = snapshot?.let { current -> current.copy(buildModels = current.buildModels.filterNot {
            it.providerId == org.refactorkit.kotlin.KotlinJvmBuildModelProjector.PROVIDER_ID
        }) }
        kotlinAdapter = KotlinLanguageAdapter()
        kotlinToolchain = null
        kotlinSemanticLease = null
        return if (stopped) "Stopped Kotlin compiler diagnostics." else "No Kotlin compiler diagnostics session was configured."
    }

    private fun toolKotlinDiagnostics(args: JsonObject): String {
        val current = requireSnapshot()
        val lease = args.string("semanticLease") ?: missing("semanticLease")
        val expected = args.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        if (expected != current.hash) return "Refused [kotlin.diagnosticsSnapshotStale]: expected Kotlin snapshot is stale."
        if (kotlinToolchain == null || lease != kotlinSemanticLease) {
            return "Refused [kotlin.diagnosticsSessionStale]: Kotlin semantic lease is missing or stale."
        }
        val result = kotlinAdapter.compilerDiagnostics(current)
        return buildJsonObject {
            put("status", when (result) {
                is KotlinCompilerDiagnosticsResult.Available -> "ready"
                is KotlinCompilerDiagnosticsResult.Refused -> "refused"
                is KotlinCompilerDiagnosticsResult.Error -> "error"
            })
            put("snapshotHash", current.hash)
            put("backend", result.attestation.backend)
            put("toolchainProjectionHash", result.attestation.toolchainProjectionHash)
            put("buildProjectionHash", result.attestation.buildProjectionHash)
            put("process", result.attestation.process?.let { provenance -> buildJsonObject {
                put("executableSha256", provenance.executableSha256)
                put("argumentsSha256", provenance.argumentsSha256)
                put("processId", provenance.pid)
            } } ?: JsonNull)
            put("diagnostics", buildJsonArray {
                result.diagnostics.forEach { diagnostic -> add(buildJsonObject {
                    put("severity", diagnostic.severity.name.lowercase())
                    put("message", diagnostic.message)
                    diagnostic.code?.let { put("code", it) }
                    diagnostic.location?.let { location ->
                        put("file", ProtocolPath.serialize(location.path))
                        put("line", location.range.start.line)
                    }
                }) }
            })
        }.toString()
    }

    private fun toolKotlinSymbols(args: JsonObject): String = toolKotlinSymbolRead(args, null)

    private fun toolKotlinDefinition(args: JsonObject): String {
        val symbol = args.string("symbol") ?: missing("symbol")
        if (!Regex("kotlin-jvm-(?:type|callable|property)-v1:[0-9a-f]{64}").matches(symbol)) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin definition requires a valid opaque JVM declaration ID")
        }
        return toolKotlinSymbolRead(args, org.refactorkit.core.SymbolId(symbol))
    }

    private fun toolKotlinUsageNavigation(args: JsonObject, references: Boolean): String {
        val current = requireSnapshot()
        val lease = args.string("semanticLease") ?: missing("semanticLease")
        val expected = args.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        if (expected != current.hash) return "Refused [kotlin.symbolsSnapshotStale]: expected Kotlin snapshot is stale."
        if (kotlinToolchain == null || lease != kotlinSemanticLease) {
            return "Refused [kotlin.symbolsSessionStale]: Kotlin semantic lease is missing or stale."
        }
        val path = runCatching { ProtocolPath.parseRelative(args.string("file") ?: missing("file")) }.getOrElse {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, it.message ?: "Kotlin usage file is invalid")
        }
        if (current.files.none { it.languageId == "kotlin" && it.path.normalize() == path }) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin usage file is not an attested source")
        }
        val line = (args["line"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin usage line must be an integer")
        val character = (args["character"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin usage character must be an integer")
        if (line < 0 || character < 0) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin usage position must be non-negative",
        )
        val point = SourcePosition(line, character)
        val result = kotlinAdapter.compilerSymbols(current)
        if (result !is KotlinCompilerSymbolsResult.Available) return when (result) {
            is KotlinCompilerSymbolsResult.Refused -> "Refused [${result.reason.code}]: ${result.reason.message}"
            is KotlinCompilerSymbolsResult.Error -> "Error [${result.failure.code}]: ${result.failure.message}"
            else -> error("unreachable")
        }
        fun contains(location: SourceLocation): Boolean {
            if (location.path.normalize() != path) return false
            val start = location.range.start; val end = location.range.end
            return (point.line > start.line || point.line == start.line && point.character >= start.character) &&
                (point.line < end.line || point.line == end.line && point.character < end.character)
        }
        val ids = buildList {
            result.index.symbols.filter { contains(it.location) }.forEach { add(it.id) }
            result.usages.filter { contains(it.location) }.forEach { add(it.targetId) }
        }.distinct()
        if (ids.size != 1) return "Refused [kotlin.usageNotFound]: no unique compiler-proven Kotlin declaration or usage at position."
        val target = result.index.symbols.single { it.id == ids.single() }
        val includeDeclaration = if ("includeDeclaration" in args) {
            (args["includeDeclaration"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "includeDeclaration must be boolean")
        } else true
        val limit = if ("limit" in args) {
            (args["limit"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
                ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin reference limit must be an integer")
        } else ProtocolLimits.MAX_REFERENCE_RESULTS
        if (limit !in 1..ProtocolLimits.MAX_REFERENCE_RESULTS) throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin reference limit is invalid",
        )
        val locations = if (!references) listOf(target.location) else buildList {
            if (includeDeclaration) add(target.location)
            result.usages.filter { it.targetId == target.id }.forEach { add(it.location) }
        }.distinct().sortedWith(compareBy({ ProtocolPath.serialize(it.path) },
            { it.range.start.line }, { it.range.start.character }))
        val returned = locations.take(limit)
        return buildJsonObject {
            put("status", "ready"); put("backend", result.attestation.backend)
            put("targetId", target.id.value); put("targetName", target.name)
            put("complete", !references); put("completeness", if (references) "partial" else "semantic")
            put("total", locations.size); put("returned", returned.size); put("truncated", locations.size > returned.size)
            put("locations", buildJsonArray { returned.forEach { location -> add(buildJsonObject {
                put("path", ProtocolPath.serialize(location.path)); put("startLine", location.range.start.line)
                put("startCharacter", location.range.start.character); put("endLine", location.range.end.line)
                put("endCharacter", location.range.end.character)
            }) } })
        }.toString()
    }

    private fun toolKotlinSymbolRead(args: JsonObject, requestedSymbol: org.refactorkit.core.SymbolId?): String {
        val current = requireSnapshot()
        val lease = args.string("semanticLease") ?: missing("semanticLease")
        val expected = args.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
        if (expected != current.hash) return "Refused [kotlin.symbolsSnapshotStale]: expected Kotlin snapshot is stale."
        if (kotlinToolchain == null || lease != kotlinSemanticLease) {
            return "Refused [kotlin.symbolsSessionStale]: Kotlin semantic lease is missing or stale."
        }
        val requestedFile = args.string("file")?.let { raw ->
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
        val result = kotlinAdapter.compilerSymbols(current)
        val query = if (requestedSymbol == null) args.string("query").orEmpty() else ""
        if (query.length > 512) throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Kotlin symbol query is too long")
        val selected = if (result is KotlinCompilerSymbolsResult.Available) {
            result.index.symbols.filter { symbol ->
                symbol.name.contains(query, ignoreCase = true) &&
                    (requestedFile == null || symbol.location.path == requestedFile) &&
                    (requestedSymbol == null || symbol.id == requestedSymbol)
            }
        } else emptyList()
        return buildJsonObject {
            put("status", when {
                result is KotlinCompilerSymbolsResult.Available && requestedSymbol != null && selected.isEmpty() -> "refused"
                result is KotlinCompilerSymbolsResult.Available -> "ready"
                result is KotlinCompilerSymbolsResult.Refused -> "refused"
                else -> "error"
            })
            put("snapshotHash", current.hash)
            put("backend", result.attestation.backend)
            put("toolchainProjectionHash", result.attestation.toolchainProjectionHash)
            put("buildProjectionHash", result.attestation.buildProjectionHash)
            val failure = when (result) {
                is KotlinCompilerSymbolsResult.Available -> if (requestedSymbol != null && selected.isEmpty()) {
                    "kotlin.symbolNotFound"
                } else null
                is KotlinCompilerSymbolsResult.Refused -> result.reason.code
                is KotlinCompilerSymbolsResult.Error -> result.failure.code
            }
            put("failureCode", failure?.let(::JsonPrimitive) ?: JsonNull)
            put("process", result.attestation.process?.let { provenance -> buildJsonObject {
                put("executableSha256", provenance.executableSha256)
                put("argumentsSha256", provenance.argumentsSha256)
                put("processId", provenance.pid)
            } } ?: JsonNull)
            put("symbols", buildJsonArray {
                selected.take(ProtocolLimits.MAX_SYMBOL_RESULTS).forEach { symbol -> add(buildJsonObject {
                    put("id", symbol.id.value)
                    put("name", symbol.name)
                    put("kind", symbol.kind.name.lowercase())
                    put("file", ProtocolPath.serialize(symbol.location.path))
                    put("startLine", symbol.location.range.start.line)
                    put("startCharacter", symbol.location.range.start.character)
                    put("endLine", symbol.location.range.end.line)
                    put("endCharacter", symbol.location.range.end.character)
                }) }
            })
            put("truncated", selected.size > ProtocolLimits.MAX_SYMBOL_RESULTS)
        }.toString()
    }

    private fun toolProjectScan(args: JsonObject): String {
        val root = args.string("root") ?: missing("root")
        val path = Paths.get(root).toAbsolutePath().normalize()
        val recoveryErrors = PatchEngine(path).inspectRecovery()
        if (recoveryErrors.isNotEmpty()) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INTERNAL_ERROR,
                "Workspace recovery required; run explicit CLI/daemon patch recovery first: ${recoveryErrors.joinToString("; ") { it.message }}",
            )
        }
        closeSemanticAdapters()
        val snap = scanWorkspace(path)
        snapshot = snap
        workspaceRoot = path
        return "Scanned ${snap.workspace.root}\nFiles: ${snap.files.size}\nModules: ${snap.modules.size}\nSnapshot: ${snap.hash}"
    }

    private fun toolProjectSummary(): String {
        val snap = requireSnapshot()
        return buildString {
            appendLine("Project: ${snap.workspace.root}")
            appendLine("Files  : ${snap.files.size}")
            appendLine("Modules: ${snap.modules.size}")
            snap.modules.forEach { appendLine("  - ${it.name} (${it.root})") }
            appendLine("Build models:")
            snap.buildModels.sortedBy { it.providerId }.forEach { model ->
                val ecosystem = model.attributes["ecosystem"] ?: model.attributes["providers"].orEmpty()
                appendLine("  - ${model.providerId}: ${model.status} ecosystem=$ecosystem strategy=${model.attributes["strategy"].orEmpty()}")
                appendLine("    execution=${model.attributes["buildCodeExecution"].orEmpty()} credentials=${model.attributes["credentialsAccess"].orEmpty()} networkDefault=${model.attributes["networkDefault"].orEmpty()} networkAccess=${model.attributes["networkAccess"].orEmpty()}")
                model.modules.sortedBy { it.id }.forEach { module ->
                    appendLine("    module ${module.id}: ${module.sourceSets.joinToString { "${it.id}:${it.kind}" }}")
                }
                if (model.diagnostics.isNotEmpty()) {
                    appendLine("    diagnostics: ${model.diagnostics.joinToString { "${it.code}:${it.moduleId.orEmpty()}" }}")
                }
            }
            appendLine("Snapshot: ${snap.hash}")
        }.trim()
    }

    private fun toolSymbolSearch(args: JsonObject): String {
        val query = args.string("query") ?: ""
        val snap = requireSnapshot()
        val languageId = args.string("languageId") ?: "java"
        val results = if (languageId == "java") adapter.searchSymbols(snap, query) else
            requireSemanticAdapter(languageId).searchWorkspaceSymbols(snap, query)
        if (results.isEmpty()) return "No symbols found for query: $query"
        return results.take(100).joinToString("\n") { "${it.kind} ${it.id.value} at ${it.location.path}:${it.location.range.start.line + 1}" }
    }

    private fun toolSymbolDefinition(args: JsonObject): String {
        val symbolId = args.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val languageId = args.string("languageId") ?: "java"
        val sym = if (languageId == "java") adapter.findSymbol(snap, org.refactorkit.core.SymbolId(symbolId)) else
            requireSemanticAdapter(languageId).buildSymbols(snap).symbols.singleOrNull { it.id.value == symbolId }
        sym ?: return "Symbol not found: $symbolId"
        return "${sym.kind} ${sym.id.value}\nFile: ${sym.location.path}:${sym.location.range.start.line + 1}"
    }

    private fun toolSymbolReferences(args: JsonObject): String {
        val symbolId = args.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val languageId = args.string("languageId") ?: "java"
        val refs = if (languageId == "java") adapter.findReferences(snap, org.refactorkit.core.SymbolId(symbolId)) else {
            val semantic = requireSemanticAdapter(languageId)
            semantic.buildSymbols(snap)
            semantic.findReferences(org.refactorkit.core.SymbolId(symbolId))
        }
        if (refs.isEmpty()) return "No references found for: $symbolId"
        return refs.joinToString("\n") { "${it.location.path}:${it.location.range.start.line + 1}" }
    }

    private fun toolDiagnostics(args: JsonObject = JsonObject(emptyMap())): String {
        val snap = requireSnapshot()
        val languageId = args.string("languageId") ?: "java"
        val diags = if (languageId == "java") adapter.diagnostics(snap) else requireSemanticAdapter(languageId).diagnostics(snap)
        if (diags.isEmpty()) return "No diagnostics."
        return diags.joinToString("\n") { "[${it.severity}] ${it.message}${it.location?.let { loc -> " at ${loc.path}:${loc.range.start.line + 1}" } ?: ""}" }
    }

    private fun toolDiagnosticsV2(args: JsonObject): String {
        val request = try {
            TypeScriptDiagnosticsProtocol.parse(args)
        } catch (failure: IllegalArgumentException) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, failure.message ?: "Invalid diagnostics_v2 request")
        }
        val snap = requireSnapshot()
        val semantic = semanticAdapters[request.languageId]
            ?: return TypeScriptDiagnosticsProtocol.notReady(request, snap.hash, semanticLeases[request.languageId]).toString()
        val lease = semanticLeases[request.languageId]
            ?: return TypeScriptDiagnosticsProtocol.notReady(request, snap.hash).toString()
        return TypeScriptDiagnosticsProtocol.execute(request, snap, lease, semantic).toString()
    }

    private fun toolAvailableRefactorings(args: JsonObject): String {
        val symbolId = args.string("symbol") ?: missing("symbol")
        return "Available refactorings for $symbolId:\n" +
            "- renameClass: rename to a new simple name\n" +
            "- renameMember: rename a method or field\n" +
            "- extractMethod: extract selected Java lines into a private void method\n" +
            "- changeSignature.renameParameter: rename a method parameter\n" +
            "- changeSignature.addParameter: add a method parameter with a default call-site expression\n" +
            "- changeSignature.reorderParameters: reorder method parameters and call-site arguments\n" +
            "- changeSignature.removeParameter: remove an unused method parameter and call-site argument\n" +
            "- moveClass: move to a different package\n" +
            "- safeDelete: delete if no references exist\n" +
            "- organizeImports: sort and deduplicate imports in the declaring file"
    }

    private fun toolPreviewRefactoring(args: JsonObject): String {
        val operation = args.string("operation") ?: missing("operation")
        val symbol = args.string("symbol")
        val languageId = args.string("languageId") ?: "java"
        val opArgs = (args["arguments"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to (v as? JsonPrimitive)?.content.orEmpty()
        } ?: emptyMap()
        val snap = requireSnapshot()

        val plan = when (operation) {
            "renameSymbol" -> {
                if (languageId == "java") missing("languageId=kotlin|typescript|javascript")
                if (languageId == "kotlin") {
                    val lease = args.string("semanticLease") ?: missing("semanticLease")
                    val expected = args.string("expectedSnapshotHash") ?: missing("expectedSnapshotHash")
                    if (lease != kotlinSemanticLease || expected != snap.hash) return "Refused [kotlin.renameAuthorityStale]: Kotlin rename authority is stale."
                    KotlinPrivateDeclarationRenamePlanner(kotlinAdapter).preview(
                        snap, org.refactorkit.core.SymbolId(symbol ?: missing("symbol")),
                        opArgs["newName"] ?: missing("arguments.newName"),
                    )
                } else {
                val semantic = requireSemanticAdapter(languageId)
                val index = semantic.buildSymbols(snap)
                val selected = symbol?.let { id -> index.symbols.singleOrNull { it.id.value == id } }
                val location = selected?.location ?: SourceLocation(
                    Paths.get(opArgs["file"] ?: missing("arguments.file")),
                    SourceRange(
                        SourcePosition(opArgs["line"]?.toIntOrNull() ?: missing("arguments.line"),
                            opArgs["character"]?.toIntOrNull() ?: missing("arguments.character")),
                        SourcePosition(opArgs["line"]?.toIntOrNull() ?: missing("arguments.line"),
                            opArgs["character"]?.toIntOrNull() ?: missing("arguments.character")),
                    ),
                )
                semantic.applyRefactoring(RefactoringRequest(
                    "renameSymbol", selected?.id, CodeSelection(location), opArgs, snap,
                ))
                }
            }
            "renameClass"  -> JavaRenameClassPlanner(adapter).preview(snap, symbol ?: missing("symbol"), opArgs["newName"] ?: missing("arguments.newName"))
            "renameMember" -> JavaRenameMemberPlanner(adapter).preview(snap, symbol ?: missing("symbol"), opArgs["newName"] ?: missing("arguments.newName"))
            "extractMethod" -> JavaExtractMethodPlanner().preview(
                snap,
                Paths.get(opArgs["file"] ?: symbol ?: missing("arguments.file")),
                opArgs["startLine"]?.toIntOrNull() ?: missing("arguments.startLine"),
                opArgs["endLine"]?.toIntOrNull() ?: missing("arguments.endLine"),
                opArgs["methodName"] ?: missing("arguments.methodName"),
            )
            "changeSignature.renameParameter", "renameParameter" -> JavaChangeSignaturePlanner(adapter).previewRenameParameter(
                snap,
                symbol ?: missing("symbol"),
                opArgs["oldName"] ?: opArgs["oldParameterName"] ?: missing("arguments.oldName"),
                opArgs["newName"] ?: opArgs["newParameterName"] ?: missing("arguments.newName"),
            )
            "changeSignature.addParameter", "addParameter" -> JavaChangeSignaturePlanner(adapter).previewAddParameter(
                snap,
                symbol ?: missing("symbol"),
                opArgs["type"] ?: opArgs["parameterType"] ?: missing("arguments.type"),
                opArgs["name"] ?: opArgs["parameterName"] ?: missing("arguments.name"),
                opArgs["default"] ?: opArgs["defaultExpression"] ?: missing("arguments.default"),
            )
            "changeSignature.reorderParameters", "reorderParameters" -> JavaChangeSignaturePlanner(adapter).previewReorderParameters(
                snap,
                symbol ?: missing("symbol"),
                (opArgs["order"] ?: opArgs["newOrder"] ?: missing("arguments.order")).split(','),
            )
            "changeSignature.removeParameter", "removeParameter" -> JavaChangeSignaturePlanner(adapter).previewRemoveParameter(
                snap,
                symbol ?: missing("symbol"),
                opArgs["name"] ?: opArgs["parameterName"] ?: missing("arguments.name"),
            )
            "moveClass"    -> JavaMoveClassPlanner(adapter).preview(snap, symbol ?: missing("symbol"), opArgs["targetPackage"] ?: missing("arguments.targetPackage"))
            "moveSourceRoot" -> JavaMoveSourceRootPlanner(adapter).preview(
                snap, Paths.get(opArgs["from"] ?: missing("arguments.from")), Paths.get(opArgs["to"] ?: missing("arguments.to")),
            )
            "organizeImports" -> JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(opArgs["file"] ?: symbol ?: missing("arguments.file")))
            "formatFile" -> JavaFormatFilePlanner(adapter).preview(snap, Paths.get(opArgs["file"] ?: symbol ?: missing("arguments.file")))
            "safeDelete"   -> JavaSafeDeletePlanner(adapter).preview(snap, symbol ?: missing("symbol"), opArgs["force"]?.toBoolean() ?: false)
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown operation: $operation")
        }

        if (plan.status == PatchStatus.PREVIEW) pendingPlans[plan.id.value] = PendingPlan(
            plan, languageId, if (languageId == "kotlin") args.string("semanticLease") else null,
        )
        return buildString {
            appendLine("Plan ID  : ${plan.id.value}")
            appendLine("Status   : ${plan.status}")
            appendLine("Summary  : ${plan.summary}")
            appendLine("Risk     : ${plan.riskLevel}")
            appendLine("Evidence : ${plan.evidence}")
            plan.refusalCode?.let { appendLine("Refusal  : $it") }
            appendLine("Confidence: ${plan.confidence}")
            appendLine("Affected : ${plan.affectedFiles.size} file(s)")
            plan.affectedFiles.forEach { appendLine("  $it") }
            if (plan.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                plan.warnings.forEach { appendLine("  - $it") }
            }
            if (plan.diagnosticsAfterPreview.isNotEmpty()) {
                appendLine("Post-edit diagnostics:")
                plan.diagnosticsAfterPreview.forEach { d ->
                    val loc = d.location?.let { " at ${it.path}:${it.range.start.line + 1}" } ?: ""
                    appendLine("  [${d.severity}]${d.code?.let { " (${it})" } ?: ""} ${d.message}$loc")
                }
            }
            if (plan.status == PatchStatus.REFUSED) appendLine("\nRefused. Do NOT apply.")
            else appendLine("\nTo apply: use tool apply_refactoring with planId=${plan.id.value}")
        }.trim()
    }

    private fun toolApplyRefactoring(args: JsonObject): String {
        val planId = args.string("planId") ?: missing("planId")
        val pending = pendingPlans[planId] ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: $planId")
        val plan = pending.plan
        if (pending.languageId == "kotlin") {
            val lease = args.string("semanticLease") ?: missing("semanticLease")
            if (lease != pending.semanticLease || lease != kotlinSemanticLease) {
                pendingPlans.remove(planId)
                return "Apply refused [kotlin.renameAuthorityStale]: Kotlin semantic lease is stale."
            }
        }
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val current = scanWorkspace(root)
        return when (val result = PatchEngine(root).apply(
            plan,
            current,
            ApplyAuthorization.explicit("mcp-tool"),
            when (pending.languageId) {
                "java" -> DiagnosticsGate.enabled("java-jdt", adapter::diagnostics)
                "kotlin" -> DiagnosticsGate.enabled("kotlin-k2") { candidate ->
                    kotlinAdapter.compilerDiagnostics(candidate).diagnostics
                }
                else -> requireSemanticAdapter(pending.languageId).diagnosticsGate()
            },
        )) {
            is ApplyResult.Applied -> {
                pendingPlans.remove(planId)
                // Refresh snapshot and close sessions bound to the pre-apply image.
                snapshot = scanWorkspace(root)
                closeSemanticAdapters()
                "Applied successfully.\nTransaction ID: ${result.transaction.id.value}\nTo rollback: use tool rollback_refactoring with transactionId=${result.transaction.id.value}"
            }
            is ApplyResult.Refused -> {
                val code = JsonRpcErrorCodes.applyRefusalCode(result.diagnostics)
                "Apply refused [$code]: ${result.diagnostics.joinToString("; ") { it.message }}"
            }
        }
    }

    private fun toolRollbackRefactoring(args: JsonObject): String {
        val txId = args.string("transactionId") ?: missing("transactionId")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val mode = if (args["force"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
            RollbackMode.FORCE
        } else RollbackMode.NORMAL
        val transactionId = TransactionId.parseOrNull(txId)
            ?: return "Invalid transaction ID: $txId"
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val tx = log.load(transactionId)
            ?: return "Transaction not found: $txId"
        return when (val result = PatchEngine(root).rollback(tx, mode)) {
            is ApplyResult.Applied -> {
                snapshot = scanWorkspace(root)
                "${if (mode == RollbackMode.FORCE) "Force rolled back" else "Rolled back"} transaction $txId."
            }
            is ApplyResult.Refused -> {
                val code = JsonRpcErrorCodes.rollbackRefusalCode(result.diagnostics)
                "Rollback refused [$code]: ${result.diagnostics.joinToString("; ") { it.message }}"
            }
        }
    }

    private fun toolImportExternalJavaClass(args: JsonObject): String {
        val code = args.string("code") ?: missing("code")
        val targetPackage = args.string("targetPackage") ?: missing("targetPackage")
        val licensePolicy = when (args.string("licensePolicy")) {
            "block-unknown" -> LicensePolicy.BLOCK_UNKNOWN
            "allow" -> LicensePolicy.ALLOW
            else -> LicensePolicy.WARN
        }
        val snap = requireSnapshot()
        val plan = ExternalJavaClassImporter().preview(ImportRequest(
            code = code,
            targetPackage = targetPackage,
            sourceUrl = args.string("sourceUrl"),
            sourceKind = SourceKind.LLM,
            licensePolicy = licensePolicy,
            snapshot = snap,
        ))

        pendingPlans[plan.id.value] = PendingPlan(plan)
        return buildString {
            appendLine("Plan ID  : ${plan.id.value}")
            appendLine("Status   : ${plan.status}")
            appendLine("Summary  : ${plan.summary}")
            appendLine("Risk     : ${plan.riskLevel}")
            appendLine("Evidence : ${plan.evidence}")
            appendLine("Confidence: ${plan.confidence}")
            appendLine("Affected : ${plan.affectedFiles.size} file(s)")
            plan.affectedFiles.forEach { appendLine("  $it") }
            if (plan.warnings.isNotEmpty()) {
                appendLine("Warnings:")
                plan.warnings.forEach { appendLine("  - $it") }
            }
            if (plan.status == PatchStatus.REFUSED) appendLine("\nRefused. Do NOT apply.")
            else appendLine("\nTo apply: use tool apply_refactoring with planId=${plan.id.value}")
        }.trim()
    }

    private fun toolGenerateContextBundle(args: JsonObject): String {
        val snap = requireSnapshot()
        val query = args.string("query") ?: ""
        val maxSymbols = args.string("maxSymbols")?.toIntOrNull() ?: 50
        val includeSnippets = args.string("includeSnippets")?.toBooleanStrictOrNull() ?: true
        val index = adapter.buildSymbols(snap)
        val symbols = if (query.isBlank()) index.symbols.take(maxSymbols) else index.search(query).take(maxSymbols)
        val diags = adapter.diagnostics(snap)
        return buildString {
            appendLine("## Project")
            appendLine("Root: ${snap.workspace.root}")
            appendLine("Files: ${snap.files.size}")
            appendLine("Modules: ${snap.modules.joinToString { it.name }}")
            appendLine("Build files: ${buildFiles(snap).joinToString().ifBlank { "none detected" }}")
            appendLine()
            appendLine("## Symbols (${symbols.size}/${index.symbols.size})")
            symbols.forEach { sym ->
                appendLine("${sym.kind} ${sym.id.value} at ${sym.location.path}:${sym.location.range.start.line + 1}")
                if (includeSnippets) snippetFor(snap, sym.location.path, sym.location.range.start.line)?.let { snippet ->
                    appendLine("```java")
                    appendLine(snippet)
                    appendLine("```")
                }
            }
            if (symbols.size == 1) {
                val refs = adapter.findReferences(snap, symbols.first().id).take(25)
                appendLine()
                appendLine("## References (${refs.size}${if (refs.size == 25) "+" else ""})")
                refs.forEach { appendLine("${it.location.path}:${it.location.range.start.line + 1}") }
            }
            appendLine()
            if (diags.isNotEmpty()) {
                appendLine("## Diagnostics")
                diags.forEach { appendLine("[${it.severity}] ${it.message}") }
            } else {
                appendLine("## Diagnostics: none")
            }
        }.trim()
    }

    // ── resources ─────────────────────────────────────────────────────────────

    private fun resourcesList(): JsonElement = buildJsonObject {
        val snap = snapshot
        put("resources", buildJsonArray {
            add(resource("project://summary", "Project Summary"))
            add(resource("project://symbols", "Symbol Index"))
            add(resource("project://dependencies", "Project Dependencies / Build Files"))
            add(resource("diagnostics://latest", "Latest Diagnostics"))
            if (snap != null) {
                adapter.buildSymbols(snap).symbols.take(200).forEach { sym ->
                    add(resource("symbol://${sym.id.value}", "Symbol ${sym.id.value}"))
                }
                snap.files.filter { it.languageId == "java" }.take(200).forEach { file ->
                    add(resource(snap.workspace.root.resolve(file.path).toUri().toString(), "File ${file.path}"))
                }
            }
        })
    }

    private fun resourcesTemplatesList(): JsonElement = buildJsonObject {
        put("resourceTemplates", buildJsonArray {
            add(buildJsonObject {
                put("uriTemplate", "symbol://{fullyQualifiedName}")
                put("name", "Java Symbol")
                put("description", "Definition and references for a Java symbol in the open project.")
                put("mimeType", "text/plain")
            })
            add(buildJsonObject {
                put("uriTemplate", "file://{absolutePathWithinWorkspace}")
                put("name", "Workspace File")
                put("description", "Read a file that belongs to the currently scanned workspace snapshot.")
                put("mimeType", "text/plain")
            })
        })
    }

    private fun resourcesRead(params: JsonObject?): JsonElement {
        val uri = params?.string("uri") ?: missing("uri")
        val content = when {
            uri == "project://summary" -> toolProjectSummary()
            uri == "project://symbols" -> toolSymbolSearch(JsonObject(emptyMap()))
            uri == "project://dependencies" -> projectDependencies()
            uri == "diagnostics://latest" -> toolDiagnostics()
            uri.startsWith("symbol://") -> readSymbolResource(uri)
            uri.startsWith("file://") -> readFile(uri)
            else -> "Unknown resource: $uri"
        }
        return buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject { put("uri", uri); put("mimeType", "text/plain"); put("text", content) })
            })
        }
    }

    private fun readSymbolResource(uri: String): String {
        val snap = requireSnapshot()
        val symbolId = URLDecoder.decode(uri.removePrefix("symbol://"), "UTF-8")
        val sym = adapter.findSymbol(snap, org.refactorkit.core.SymbolId(symbolId))
            ?: return "Symbol not found: $symbolId"
        val refs = adapter.findReferences(snap, sym.id)
        return buildString {
            appendLine("${sym.kind} ${sym.id.value}")
            appendLine("Definition: ${sym.location.path}:${sym.location.range.start.line + 1}:${sym.location.range.start.character + 1}")
            snippetFor(snap, sym.location.path, sym.location.range.start.line)?.let {
                appendLine()
                appendLine("```java")
                appendLine(it)
                appendLine("```")
            }
            appendLine()
            appendLine("References: ${refs.size}")
            refs.take(100).forEach { appendLine("  ${it.location.path}:${it.location.range.start.line + 1}:${it.location.range.start.character + 1}") }
        }.trim()
    }

    private fun readFile(uri: String): String {
        val snap = requireSnapshot()
        return try {
            val root = snap.workspace.root.toAbsolutePath().normalize()
            val path = Paths.get(URI(uri)).toAbsolutePath().normalize()
            if (!path.startsWith(root)) return "Access denied: file is outside the workspace root."
            val rel = root.relativize(path)
            val file = snap.files.find { it.path.normalize() == rel }
                ?: return "Access denied: file is not part of the scanned workspace snapshot."
            file.content
        } catch (e: Exception) {
            "Error reading file: ${e.message}"
        }
    }

    // ── prompts ───────────────────────────────────────────────────────────────

    private fun promptsList(): JsonElement = buildJsonObject {
        put("prompts", buildJsonArray {
            add(buildJsonObject { put("name", "refactor_safely"); put("description", "Perform a safe, previewable refactoring.") })
            add(buildJsonObject { put("name", "import_external_class_safely"); put("description", "Import an external Java class safely.") })
            add(buildJsonObject { put("name", "explain_patch"); put("description", "Explain a patch plan in detail.") })
            add(buildJsonObject { put("name", "generate_tests_for_refactor"); put("description", "Plan tests that validate a previewed or applied refactoring.") })
        })
    }

    private fun promptsGet(params: JsonObject?): JsonElement {
        val name = params?.string("name") ?: missing("name")
        val text = when (name) {
            "refactor_safely" -> "Use RefactorKit tools to perform a safe, previewable, rollbackable refactoring.\n1. Call project_scan.\n2. Call symbol_search to locate the target.\n3. Call preview_refactoring.\n4. Inspect the plan, affected files, and warnings.\n5. Call apply_refactoring only if the plan is safe and the risk level is acceptable.\n6. If apply causes issues, call rollback_refactoring."
            "import_external_class_safely" -> "When importing external Java code:\n1. Record the source URL and license.\n2. Parse the code.\n3. Detect top-level types.\n4. Determine target package.\n5. Preview the import as a patch.\n6. Apply only after confirming no naming conflicts and acceptable license."
            "explain_patch" -> "Examine the plan: id, operation, summary, affectedFiles, warnings, riskLevel, confidence. Explain what will change and why, and identify any risks."
            "generate_tests_for_refactor" -> "Generate tests for a RefactorKit refactoring safely. Use project_scan, inspect symbol_definition and symbol_references, preview_refactoring, then propose unit/golden/integration tests that verify affected files, diagnostics, rollback, and no unrelated edits. Do not apply changes unless explicitly requested."
            else -> "Unknown prompt: $name"
        }
        return buildJsonObject {
            put("description", name)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonObject { put("type", "text"); put("text", text) })
                })
            })
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun textContent(text: String): JsonElement = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        })
        put("isError", false)
    }

    private fun tool(name: String, description: String, required: List<String>, props: Map<String, String>): JsonElement =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    props.forEach { (k, v) ->
                        val type = v.substringBefore(":", "string").trim().takeIf { it in JSON_SCHEMA_TYPES } ?: "string"
                        val descriptionText = v.substringAfter(":", v).trim()
                        put(k, buildJsonObject { put("type", type); put("description", descriptionText) })
                    }
                })
                put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            })
        }

    private fun resource(uri: String, name: String): JsonObject = buildJsonObject {
        put("uri", uri)
        put("name", name)
        put("mimeType", "text/plain")
    }

    private fun projectDependencies(): String {
        val snap = requireSnapshot()
        return buildString {
            appendLine("Project: ${snap.workspace.root}")
            appendLine("Modules:")
            snap.modules.forEach { module ->
                appendLine("  - ${module.name}")
                appendLine("    root: ${module.root}")
                appendLine("    sourceRoots: ${module.sourceRoots.joinToString().ifBlank { "none" }}")
            }
            appendLine("Build files:")
            buildFiles(snap).forEach { appendLine("  - $it") }
        }.trim()
    }

    private fun buildFiles(snap: ProjectSnapshot): List<Path> {
        val names = setOf("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        val root = snap.workspace.root
        return if (!Files.exists(root)) emptyList() else Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() in names }
                .filter { !root.relativize(it).toString().replace('\\', '/').split('/').any { part -> part in IGNORED_RESOURCE_DIRS } }
                .map { root.relativize(it) }
                .sorted { a, b -> a.toString().compareTo(b.toString()) }
                .collect(Collectors.toList())
        }
    }

    private fun snippetFor(snap: ProjectSnapshot, path: Path, line: Int, radius: Int = 2): String? {
        val file = snap.files.find { it.path == path } ?: return null
        val lines = file.content.lines()
        if (line !in lines.indices) return null
        val start = (line - radius).coerceAtLeast(0)
        val end = (line + radius).coerceAtMost(lines.lastIndex)
        return (start..end).joinToString("\n") { idx ->
            val marker = if (idx == line) ">" else " "
            "$marker ${idx + 1}: ${lines[idx]}"
        }
    }

    private fun scanWorkspace(root: Path): ProjectSnapshot {
        val javaSnapshot = scanner.scan(root)
        val scriptSnapshot = GenericProjectScanner(SCRIPT_EXTENSIONS).scan(root)
        val merged = javaSnapshot.copy(
            files = (javaSnapshot.files + scriptSnapshot.files).associateBy { it.path.normalize() }
                .values.sortedBy { it.path.toString() },
            sourceExtensions = javaSnapshot.sourceExtensions + SCRIPT_EXTENSIONS.keys,
            ignoredDirectories = javaSnapshot.ignoredDirectories + scriptSnapshot.ignoredDirectories,
        )
        val languageAttached = if (scriptSnapshot.files.isEmpty()) merged else TypeScriptBuildModelIntegration.attach(merged)
        return kotlinToolchain?.let { KotlinJvmBuildModelIntegration.attach(languageAttached, it) } ?: languageAttached
    }

    private fun closeSemanticAdapters() {
        semanticAdapters.values.forEach { runCatching { it.close() } }
        semanticAdapters.clear()
        semanticLeases.clear()
        kotlinAdapter = KotlinLanguageAdapter()
        kotlinToolchain = null
        kotlinSemanticLease = null
    }

    private fun requireSemanticAdapter(languageId: String): TypeScriptSemanticAdapter =
        semanticAdapters[languageId] ?: throw JsonRpcException(
            JsonRpcErrorCodes.INVALID_PARAMS,
            "Semantic adapter for $languageId is not started; call typescript_semantic_start",
        )

    private fun requireSnapshot(): ProjectSnapshot =
        snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open. Call project_scan first.")

    private fun missing(field: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing required field: $field")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    override fun close() {
        closeSemanticAdapters()
        pendingPlans.clear()
        snapshot = null
        workspaceRoot = null
    }

    private data class PendingPlan(
        val plan: PatchPlan,
        val languageId: String = "java",
        val semanticLease: String? = null,
    )

    companion object {
        private val SCRIPT_EXTENSIONS = mapOf(
            "ts" to "typescript", "tsx" to "typescript",
            "js" to "javascript", "jsx" to "javascript",
        )
        private val JSON_SCHEMA_TYPES = setOf("string", "number", "integer", "boolean", "object", "array")
        private val IGNORED_RESOURCE_DIRS = setOf("build", "target", ".gradle", ".git", ".refactorkit")
    }
}

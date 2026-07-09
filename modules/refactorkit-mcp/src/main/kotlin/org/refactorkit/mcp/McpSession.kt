package org.refactorkit.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

private const val PROTOCOL_VERSION = "2024-11-05"

/**
 * MCP (Model Context Protocol) session for RefactorKit.
 *
 * Exposes deterministic refactoring tools to local LLM agents via the MCP protocol
 * (JSON-RPC 2.0 over stdio with NDJSON framing).
 */
class McpSession {
    private val adapter = JavaLanguageAdapter()
    private val scanner = JavaProjectScanner()

    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var workspaceRoot: Path? = null
    private val pendingPlans = object : LinkedHashMap<String, PatchPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PatchPlan>?) = size > 128
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
            put("version", "0.1.0")
        })
    }

    // ── tools ─────────────────────────────────────────────────────────────────

    private fun toolsList(): JsonElement = buildJsonObject {
        put("tools", buildJsonArray {
            add(tool("project_scan", "Scan and index a Java project.",
                required = listOf("root"),
                props = mapOf("root" to "string: absolute path to project root")))
            add(tool("project_summary", "Return summary of the currently open project.",
                required = emptyList(), props = emptyMap()))
            add(tool("symbol_search", "Search for symbols in the project by name.",
                required = listOf("query"),
                props = mapOf("query" to "string: name or partial FQN to search")))
            add(tool("symbol_definition", "Return the definition location of a symbol.",
                required = listOf("symbol"),
                props = mapOf("symbol" to "string: fully-qualified symbol name")))
            add(tool("symbol_references", "Find all references to a symbol.",
                required = listOf("symbol"),
                props = mapOf("symbol" to "string: fully-qualified symbol name")))
            add(tool("diagnostics", "Return diagnostics for the project.",
                required = emptyList(), props = emptyMap()))
            add(tool("available_refactorings", "List refactoring operations available for a selection.",
                required = listOf("symbol"),
                props = mapOf("symbol" to "string: fully-qualified symbol name")))
            add(tool("preview_refactoring", "Preview a refactoring operation without applying it.",
                required = listOf("operation", "symbol"),
                props = mapOf(
                    "operation" to "string: renameClass | renameMember | extractMethod | changeSignature.renameParameter | changeSignature.addParameter | changeSignature.reorderParameters | changeSignature.removeParameter | moveClass | organizeImports | safeDelete",
                    "symbol" to "string: fully-qualified symbol name",
                    "arguments" to "object: operation-specific arguments (newName, targetPackage, etc.)",
                )))
            add(tool("apply_refactoring", "Apply a previously previewed plan.",
                required = listOf("planId"),
                props = mapOf("planId" to "string: plan ID returned by preview_refactoring")))
            add(tool("rollback_refactoring", "Roll back a previously applied transaction.",
                required = listOf("transactionId"),
                props = mapOf("transactionId" to "string: transaction ID returned by apply_refactoring")))
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
        "symbol_search"         -> toolSymbolSearch(args)
        "symbol_definition"     -> toolSymbolDefinition(args)
        "symbol_references"     -> toolSymbolReferences(args)
        "diagnostics"           -> toolDiagnostics()
        "available_refactorings"-> toolAvailableRefactorings(args)
        "preview_refactoring"   -> toolPreviewRefactoring(args)
        "apply_refactoring"     -> toolApplyRefactoring(args)
        "rollback_refactoring"  -> toolRollbackRefactoring(args)
        "import_external_java_class" -> toolImportExternalJavaClass(args)
        "generate_context_bundle" -> toolGenerateContextBundle(args)
        else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown tool: $name")
    }

    private fun toolProjectScan(args: JsonObject): String {
        val root = args.string("root") ?: missing("root")
        val path = Paths.get(root)
        val snap = scanner.scan(path)
        snapshot = snap
        workspaceRoot = path.toAbsolutePath().normalize()
        return "Scanned ${snap.workspace.root}\nFiles: ${snap.files.size}\nModules: ${snap.modules.size}\nSnapshot: ${snap.hash}"
    }

    private fun toolProjectSummary(): String {
        val snap = requireSnapshot()
        return buildString {
            appendLine("Project: ${snap.workspace.root}")
            appendLine("Files  : ${snap.files.size}")
            appendLine("Modules: ${snap.modules.size}")
            snap.modules.forEach { appendLine("  - ${it.name} (${it.root})") }
            appendLine("Snapshot: ${snap.hash}")
        }.trim()
    }

    private fun toolSymbolSearch(args: JsonObject): String {
        val query = args.string("query") ?: ""
        val snap = requireSnapshot()
        val results = adapter.buildSymbols(snap).search(query)
        if (results.isEmpty()) return "No symbols found for query: $query"
        return results.take(100).joinToString("\n") { "${it.kind} ${it.id.value} at ${it.location.path}:${it.location.range.start.line + 1}" }
    }

    private fun toolSymbolDefinition(args: JsonObject): String {
        val symbolId = args.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val sym = adapter.buildSymbols(snap).symbols.find { it.id.value == symbolId }
            ?: return "Symbol not found: $symbolId"
        return "${sym.kind} ${sym.id.value}\nFile: ${sym.location.path}:${sym.location.range.start.line + 1}"
    }

    private fun toolSymbolReferences(args: JsonObject): String {
        val symbolId = args.string("symbol") ?: missing("symbol")
        val refs = adapter.findReferences(requireSnapshot(), org.refactorkit.core.SymbolId(symbolId))
        if (refs.isEmpty()) return "No references found for: $symbolId"
        return refs.joinToString("\n") { "${it.location.path}:${it.location.range.start.line + 1}" }
    }

    private fun toolDiagnostics(): String {
        val snap = requireSnapshot()
        val diags = adapter.diagnostics(snap)
        if (diags.isEmpty()) return "No diagnostics."
        return diags.joinToString("\n") { "[${it.severity}] ${it.message}${it.location?.let { loc -> " at ${loc.path}:${loc.range.start.line + 1}" } ?: ""}" }
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
        val opArgs = (args["arguments"] as? JsonObject)?.entries?.associate { (k, v) ->
            k to (v as? JsonPrimitive)?.content.orEmpty()
        } ?: emptyMap()
        val snap = requireSnapshot()

        val plan = when (operation) {
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
            "organizeImports" -> JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(opArgs["file"] ?: symbol ?: missing("arguments.file")))
            "safeDelete"   -> JavaSafeDeletePlanner(adapter).preview(snap, symbol ?: missing("symbol"), opArgs["force"]?.toBoolean() ?: false)
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown operation: $operation")
        }

        pendingPlans[plan.id.value] = plan
        return buildString {
            appendLine("Plan ID  : ${plan.id.value}")
            appendLine("Status   : ${plan.status}")
            appendLine("Summary  : ${plan.summary}")
            appendLine("Risk     : ${plan.riskLevel}")
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
        val plan = pendingPlans[planId] ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: $planId")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val current = scanner.scan(root)
        return when (val result = PatchEngine(root).apply(plan, current.hash)) {
            is ApplyResult.Applied -> {
                val log = TransactionLog(root.resolve(".refactorkit/transactions"))
                log.save(result.transaction)
                pendingPlans.remove(planId)
                // Refresh snapshot
                snapshot = scanner.scan(root)
                "Applied successfully.\nTransaction ID: ${result.transaction.id.value}\nTo rollback: use tool rollback_refactoring with transactionId=${result.transaction.id.value}"
            }
            is ApplyResult.Refused -> {
                "Apply refused: ${result.diagnostics.joinToString("; ") { it.message }}"
            }
        }
    }

    private fun toolRollbackRefactoring(args: JsonObject): String {
        val txId = args.string("transactionId") ?: missing("transactionId")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val tx = log.load(TransactionId(txId))
            ?: return "Transaction not found: $txId"
        return when (val result = PatchEngine(root).rollback(tx)) {
            is ApplyResult.Applied -> {
                log.delete(TransactionId(txId))
                snapshot = scanner.scan(root)
                "Rolled back transaction $txId."
            }
            is ApplyResult.Refused -> "Rollback refused: ${result.diagnostics.joinToString("; ") { it.message }}"
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

        pendingPlans[plan.id.value] = plan
        return buildString {
            appendLine("Plan ID  : ${plan.id.value}")
            appendLine("Status   : ${plan.status}")
            appendLine("Summary  : ${plan.summary}")
            appendLine("Risk     : ${plan.riskLevel}")
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
        val sym = adapter.buildSymbols(snap).symbols.find { it.id.value == symbolId }
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

    private fun requireSnapshot(): ProjectSnapshot =
        snapshot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open. Call project_scan first.")

    private fun missing(field: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing required field: $field")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

    companion object {
        private val JSON_SCHEMA_TYPES = setOf("string", "number", "integer", "boolean", "object", "array")
        private val IGNORED_RESOURCE_DIRS = setOf("build", "target", ".gradle", ".git", ".refactorkit")
    }
}

package org.refactorkit.daemon

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.webimporter.ExternalJavaClassImporter
import org.refactorkit.webimporter.ImportRequest
import org.refactorkit.webimporter.LicensePolicy
import org.refactorkit.webimporter.SourceKind
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Stateful RefactorKit daemon session.
 *
 * One session per daemon process. Each call is synchronous; the stdio loop
 * serialises concurrent requests naturally.
 */
class DaemonSession {
    private val adapter = JavaLanguageAdapter()
    private val scanner = JavaProjectScanner()

    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var workspaceRoot: Path? = null

    private val pendingPlans = object : LinkedHashMap<String, PatchPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PatchPlan>?) = size > MAX_PENDING_PLANS
    }

    // ── public dispatcher ─────────────────────────────────────────────────────

    fun dispatch(method: String, params: JsonObject?): JsonElement = when (method) {
        "server.version"      -> serverVersion()
        "server.capabilities" -> serverCapabilities()
        "project.open"        -> projectOpen(params)
        "project.summary"   -> projectSummary()
        "symbol.search"     -> symbolSearch(params)
        "symbol.definition" -> symbolDefinition(params)
        "symbol.references" -> symbolReferences(params)
        "diagnostics"       -> diagnostics()
        "refactor.preview"  -> refactorPreview(params)
        "refactor.apply"    -> refactorApply(params)
        "patch.rollback"    -> patchRollback(params)
        "java.importExternalClass" -> javaImportExternalClass(params)
        else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
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
                })
            }
        })
        put("safety", buildJsonObject {
            put("previewBeforeApply", true)
            put("snapshotValidation", true)
            put("transactionRollback", true)
            put("workspaceScopedWrites", true)
        })
    }

    private fun projectOpen(params: JsonObject?): JsonElement {
        val root = params?.string("root") ?: missing("root")
        val path = Paths.get(root).toAbsolutePath().normalize()
        val recoveryErrors = PatchEngine(path).recover()
        if (recoveryErrors.isNotEmpty()) {
            throw JsonRpcException(
                JsonRpcErrorCodes.INTERNAL_ERROR,
                "Workspace recovery required: ${recoveryErrors.joinToString("; ") { it.message }}",
            )
        }
        val snap = scanner.scan(path)
        snapshot = snap
        workspaceRoot = path
        return buildJsonObject {
            put("root", workspaceRoot.toString())
            put("fileCount", snap.files.size)
            put("moduleCount", snap.modules.size)
            put("snapshotHash", snap.hash)
        }
    }

    private fun projectSummary(): JsonElement {
        val snap = requireSnapshot()
        return buildJsonObject {
            put("root", workspaceRoot.toString())
            put("fileCount", snap.files.size)
            put("snapshotHash", snap.hash)
            put("modules", buildJsonArray {
                snap.modules.forEach { mod ->
                    add(buildJsonObject {
                        put("name", mod.name)
                        put("root", mod.root.toString())
                    })
                }
            })
        }
    }

    private fun symbolSearch(params: JsonObject?): JsonElement {
        val query = params?.string("query") ?: ""
        val snap = requireSnapshot()
        val results = adapter.searchSymbols(snap, query)
        return buildJsonArray {
            results.take(200).forEach { sym ->
                add(buildJsonObject {
                    put("id", sym.id.value)
                    put("name", sym.name)
                    put("kind", sym.kind.name)
                    put("file", sym.location.path.toString())
                    put("line", sym.location.range.start.line + 1)
                })
            }
        }
    }

    private fun symbolDefinition(params: JsonObject?): JsonElement {
        val symbolId = params?.string("symbol") ?: missing("symbol")
        val snap = requireSnapshot()
        val symbol = adapter.findSymbol(snap, SymbolId(symbolId))
            ?: throw JsonRpcException(JsonRpcErrorCodes.SYMBOL_NOT_FOUND, "Symbol not found: $symbolId")
        return buildJsonObject {
            put("id", symbol.id.value)
            put("name", symbol.name)
            put("kind", symbol.kind.name)
            put("file", symbol.location.path.toString())
            put("line", symbol.location.range.start.line + 1)
            put("character", symbol.location.range.start.character)
        }
    }

    private fun symbolReferences(params: JsonObject?): JsonElement {
        val symbolId = params?.string("symbol") ?: missing("symbol")
        val refs = adapter.findReferences(requireSnapshot(), SymbolId(symbolId))
        return buildJsonArray {
            refs.forEach { ref ->
                add(buildJsonObject {
                    put("file", ref.location.path.toString())
                    put("line", ref.location.range.start.line + 1)
                    put("character", ref.location.range.start.character)
                })
            }
        }
    }

    private fun diagnostics(): JsonElement {
        val snap = requireSnapshot()
        val diags = adapter.diagnostics(snap)
        return buildJsonArray {
            diags.forEach { d ->
                add(buildJsonObject {
                    put("severity", d.severity.name)
                    put("message", d.message)
                    d.code?.let { put("code", it) }
                    d.location?.let { loc ->
                        put("file", loc.path.toString())
                        put("line", loc.range.start.line + 1)
                    }
                })
            }
        }
    }

    private fun refactorPreview(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val operation = p.string("operation") ?: missing("operation")
        val symbol = p.string("symbol")
        val args = (p["arguments"] as? JsonObject)?.let { obj ->
            obj.entries.associate { (k, v) -> k to (v as? JsonPrimitive)?.content.orEmpty() }
        } ?: emptyMap()

        val snap = requireSnapshot()
        val plan = when (operation) {
            "renameClass" -> {
                val newName = args["newName"] ?: missing("arguments.newName")
                JavaRenameClassPlanner(adapter).preview(snap, symbol ?: missing("symbol"), newName)
            }
            "renameMember" -> {
                val newName = args["newName"] ?: missing("arguments.newName")
                JavaRenameMemberPlanner(adapter).preview(snap, symbol ?: missing("symbol"), newName)
            }
            "extractMethod" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                val startLine = args["startLine"]?.toIntOrNull() ?: missing("arguments.startLine")
                val endLine = args["endLine"]?.toIntOrNull() ?: missing("arguments.endLine")
                val methodName = args["methodName"] ?: missing("arguments.methodName")
                JavaExtractMethodPlanner().preview(snap, Paths.get(file), startLine, endLine, methodName)
            }
            "changeSignature.renameParameter", "renameParameter" -> {
                val oldName = args["oldName"] ?: args["oldParameterName"] ?: missing("arguments.oldName")
                val newName = args["newName"] ?: args["newParameterName"] ?: missing("arguments.newName")
                JavaChangeSignaturePlanner(adapter).previewRenameParameter(snap, symbol ?: missing("symbol"), oldName, newName)
            }
            "changeSignature.addParameter", "addParameter" -> {
                val type = args["type"] ?: args["parameterType"] ?: missing("arguments.type")
                val name = args["name"] ?: args["parameterName"] ?: missing("arguments.name")
                val defaultExpression = args["default"] ?: args["defaultExpression"] ?: missing("arguments.default")
                JavaChangeSignaturePlanner(adapter).previewAddParameter(snap, symbol ?: missing("symbol"), type, name, defaultExpression)
            }
            "changeSignature.reorderParameters", "reorderParameters" -> {
                val order = args["order"] ?: args["newOrder"] ?: missing("arguments.order")
                JavaChangeSignaturePlanner(adapter).previewReorderParameters(snap, symbol ?: missing("symbol"), order.split(','))
            }
            "changeSignature.removeParameter", "removeParameter" -> {
                val name = args["name"] ?: args["parameterName"] ?: missing("arguments.name")
                JavaChangeSignaturePlanner(adapter).previewRemoveParameter(snap, symbol ?: missing("symbol"), name)
            }
            "moveClass" -> {
                val pkg = args["targetPackage"] ?: missing("arguments.targetPackage")
                JavaMoveClassPlanner(adapter).preview(snap, symbol ?: missing("symbol"), pkg)
            }
            "organizeImports" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(file))
            }
            "safeDelete" -> {
                val force = args["force"]?.toBoolean() ?: false
                JavaSafeDeletePlanner(adapter).preview(snap, symbol ?: missing("symbol"), force)
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown operation: $operation")
        }

        pendingPlans[plan.id.value] = plan
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
        }
        return planToJson(plan)
    }

    private fun refactorApply(params: JsonObject?): JsonElement {
        val planId = params?.string("planId") ?: missing("planId")
        val plan = pendingPlans[planId]
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: $planId")
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val currentSnap = scanner.scan(root)
        return when (val result = PatchEngine(root).apply(plan, currentSnap)) {
            is ApplyResult.Applied -> {
                val refreshed = scanner.scan(root)
                snapshot = refreshed
                pendingPlans.clear()
                buildJsonObject {
                    put("status", "applied")
                    put("transactionId", result.transaction.id.value)
                    put("planId", planId)
                    put("snapshotHash", refreshed.hash)
                }
            }
            is ApplyResult.Refused -> {
                val msg = result.diagnostics.joinToString("; ") { it.message }
                throw JsonRpcException(JsonRpcErrorCodes.SNAPSHOT_CHANGED, msg)
            }
        }
    }

    private fun javaImportExternalClass(params: JsonObject?): JsonElement {
        val p = params ?: missing("params")
        val code = p.string("code") ?: missing("code")
        val targetPackage = p.string("targetPackage") ?: missing("targetPackage")
        val snap = requireSnapshot()
        val plan = ExternalJavaClassImporter().preview(ImportRequest(
            code = code,
            targetPackage = targetPackage,
            targetModule = p.string("targetModule"),
            sourceUrl = p.string("sourceUrl"),
            sourceKind = parseSourceKind(p.string("sourceKind")),
            licensePolicy = parseLicensePolicy(p.string("licensePolicy")),
            snapshot = snap,
        ))
        pendingPlans[plan.id.value] = plan
        return planToJson(plan)
    }

    private fun patchRollback(params: JsonObject?): JsonElement {
        val txId = params?.string("transactionId") ?: missing("transactionId")
        val mode = if (params?.get("force")?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) {
            RollbackMode.FORCE
        } else RollbackMode.NORMAL
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val transactionId = TransactionId.parseOrNull(txId)
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid transaction ID: $txId")
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val tx = log.load(transactionId)
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Transaction not found: $txId")
        return when (val result = PatchEngine(root).rollback(tx, mode)) {
            is ApplyResult.Applied -> {
                val refreshed = scanner.scan(root)
                snapshot = refreshed
                pendingPlans.clear()
                buildJsonObject {
                    put("status", "rolledBack")
                    put("transactionId", txId)
                    put("snapshotHash", refreshed.hash)
                }
            }
            is ApplyResult.Refused -> {
                val msg = result.diagnostics.joinToString("; ") { it.message }
                val code = when {
                    result.diagnostics.any { it.code == "rollback.conflict" } -> JsonRpcErrorCodes.ROLLBACK_CONFLICT
                    result.diagnostics.any { it.code == "transaction.recoveryRequired" } -> JsonRpcErrorCodes.RECOVERY_REQUIRED
                    else -> JsonRpcErrorCodes.INTERNAL_ERROR
                }
                throw JsonRpcException(code, "Rollback refused: $msg")
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

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

    private fun planToJson(plan: PatchPlan): JsonObject = buildJsonObject {
        put("planId", plan.id.value)
        put("operation", plan.operation)
        put("status", plan.status.name)
        put("summary", plan.summary)
        put("confidence", plan.confidence)
        put("riskLevel", plan.riskLevel.name)
        put("affectedFiles", buildJsonArray { plan.affectedFiles.forEach { add(JsonPrimitive(it.toString())) } })
        put("warnings", buildJsonArray { plan.warnings.forEach { add(JsonPrimitive(it)) } })
        put("diagnosticsAfterPreview", buildJsonArray {
            plan.diagnosticsAfterPreview.forEach { d ->
                add(buildJsonObject {
                    put("severity", d.severity.name)
                    put("message", d.message)
                    d.code?.let { put("code", it) }
                    d.location?.let { loc ->
                        put("file", loc.path.toString())
                        put("line", loc.range.start.line + 1)
                    }
                })
            }
        })
    }

    private data class DaemonMethodCapability(
        val name: String,
        val stability: String,
        val requiresProject: Boolean,
        val writesWorkspace: Boolean,
    )

    companion object {
        private const val MAX_PENDING_PLANS = 128
        private val DAEMON_METHODS = listOf(
            DaemonMethodCapability("server.version", "beta-contract", false, false),
            DaemonMethodCapability("server.capabilities", "beta-contract", false, false),
            DaemonMethodCapability("project.open", "beta-contract", false, false),
            DaemonMethodCapability("project.summary", "beta-contract", true, false),
            DaemonMethodCapability("symbol.search", "beta-contract", true, false),
            DaemonMethodCapability("symbol.definition", "beta-contract", true, false),
            DaemonMethodCapability("symbol.references", "beta-contract", true, false),
            DaemonMethodCapability("diagnostics", "beta-contract", true, false),
            DaemonMethodCapability("refactor.preview", "beta-contract", true, false),
            DaemonMethodCapability("refactor.apply", "beta-contract", true, true),
            DaemonMethodCapability("patch.rollback", "beta-contract", true, true),
            DaemonMethodCapability("java.importExternalClass", "experimental", true, false),
        )
    }
}

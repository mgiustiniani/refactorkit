package org.refactorkit.daemon

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileChangeKind
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchDiffRenderer
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaFormatFilePlanner
import org.refactorkit.java.JavaImportTargetResolution
import org.refactorkit.java.JavaImportTargetResolver
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaMoveSourceRootPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import org.refactorkit.webimporter.ExternalImportPreview
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
class DaemonSession : AutoCloseable {
    private val adapter = JavaLanguageAdapter()
    private var scanner = JavaProjectScanner()

    @Volatile private var snapshot: ProjectSnapshot? = null
    @Volatile private var workspaceRoot: Path? = null

    private val pendingPlans = object : LinkedHashMap<String, PendingPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PendingPlan>?) = size > ProtocolLimits.MAX_PENDING_PLANS
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
        "diagnostics"       -> diagnostics(params)
        "refactor.preview"  -> refactorPreview(params)
        "refactor.apply"    -> refactorApply(params)
        "refactor.discard"  -> refactorDiscard(params)
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
                    if (capability.features.isNotEmpty()) put("features", buildJsonObject {
                        capability.features.forEach { (name, supported) -> put(name, supported) }
                    })
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
        pendingPlans.clear()
        snapshot = null
        workspaceRoot = null
        val root = params?.string("root") ?: missing("root")
        val resolveDependencies = params.string("resolveDependencies")?.toBooleanStrictOrNull() ?: false
        scanner = JavaProjectScanner(allowNetworkDependencyResolution = resolveDependencies)
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
            put("buildModels", buildJsonArray {
                snap.buildModels.sortedBy { it.providerId }.forEach { model ->
                    add(buildJsonObject {
                        put("providerId", model.providerId)
                        put("status", model.status.name.lowercase().replace('_', '-'))
                        put("ecosystem", model.attributes["ecosystem"].orEmpty())
                        put("strategy", model.attributes["strategy"].orEmpty())
                        put("providers", model.attributes["providers"] ?: model.attributes["ecosystem"].orEmpty())
                        put("buildCodeExecution", model.attributes["buildCodeExecution"].orEmpty())
                        put("credentialsAccess", model.attributes["credentialsAccess"].orEmpty())
                        put("networkDefault", model.attributes["networkDefault"].orEmpty())
                        put("networkAccess", model.attributes["networkAccess"].orEmpty())
                        put("diagnostics", buildJsonArray {
                            model.diagnostics.sortedWith(compareBy({ it.moduleId.orEmpty() }, { it.code })).forEach { diagnostic ->
                                add(buildJsonObject {
                                    put("code", diagnostic.code)
                                    put("severity", diagnostic.severity.name.lowercase())
                                    diagnostic.moduleId?.let { put("moduleId", it) }
                                })
                            }
                        })
                        put("modules", buildJsonArray {
                            model.modules.sortedBy { it.id }.forEach { module ->
                                add(buildJsonObject {
                                    put("id", module.id)
                                    put("name", module.name)
                                    val relativeRoot = snap.workspace.root.toAbsolutePath().normalize()
                                        .relativize(module.root.toAbsolutePath().normalize())
                                    put("root", ProtocolPath.serialize(relativeRoot))
                                    put("sourceSets", buildJsonArray {
                                        module.sourceSets.sortedBy { it.id }.forEach { sourceSet ->
                                            add(buildJsonObject {
                                                put("id", sourceSet.id)
                                                put("kind", sourceSet.kind.name.lowercase())
                                                put("sourceRoots", buildJsonArray {
                                                    sourceSet.sourceRoots.sortedBy(ProtocolPath::serialize)
                                                        .forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
                                                })
                                                put("generatedSourceRoots", buildJsonArray {
                                                    sourceSet.generatedSourceRoots.sortedBy(ProtocolPath::serialize)
                                                        .forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
                                                })
                                                put("outputDirectories", buildJsonArray {
                                                    sourceSet.outputDirectories.sortedBy(ProtocolPath::serialize)
                                                        .forEach { add(JsonPrimitive(ProtocolPath.serialize(it))) }
                                                })
                                                put("moduleDependencies", buildJsonArray {
                                                    sourceSet.moduleDependencies.sortedWith(compareBy({ it.targetModuleId }, { it.scope.name }))
                                                        .forEach { dependency -> add(buildJsonObject {
                                                            put("moduleId", dependency.targetModuleId)
                                                            put("scope", dependency.scope.name.lowercase())
                                                        }) }
                                                })
                                            })
                                        }
                                    })
                                })
                            }
                        })
                    })
                }
            })
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
                    put("file", ProtocolPath.serialize(sym.location.path))
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
            put("file", ProtocolPath.serialize(symbol.location.path))
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
                    put("file", ProtocolPath.serialize(ref.location.path))
                    put("line", ref.location.range.start.line + 1)
                    put("character", ref.location.range.start.character)
                })
            }
        }
    }

    private fun diagnostics(params: JsonObject?): JsonElement {
        val snap = requireSnapshot()
        val verbose = params?.string("verbose")?.toBooleanStrictOrNull() ?: false
        val diags = adapter.diagnostics(snap, verbose)
        return buildJsonArray {
            diags.forEach { d ->
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
            "moveSourceRoot" -> {
                val from = args["from"] ?: missing("arguments.from")
                val to = args["to"] ?: missing("arguments.to")
                JavaMoveSourceRootPlanner(adapter).preview(snap, Paths.get(from), Paths.get(to))
            }
            "organizeImports" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(file))
            }
            "formatFile" -> {
                val file = args["file"] ?: symbol ?: missing("arguments.file")
                JavaFormatFilePlanner(adapter).preview(snap, Paths.get(file))
            }
            "safeDelete" -> {
                val force = args["force"]?.toBoolean() ?: false
                JavaSafeDeletePlanner(adapter).preview(snap, symbol ?: missing("symbol"), force)
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown operation: $operation")
        }

        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(
                JsonRpcErrorCodes.PLAN_REFUSED,
                plan.summary,
                buildJsonObject { plan.refusalCode?.let { put("refusalCode", it) } },
            )
        }
        pendingPlans[plan.id.value] = PendingPlan(plan)
        return planToJson(plan)
    }

    private fun refactorDiscard(params: JsonObject?): JsonElement {
        val planId = params?.string("planId") ?: missing("planId")
        val discarded = pendingPlans.remove(planId) != null
        return PROTOCOL_JSON.encodeToJsonElement(DiscardResponseDto(planId, discarded))
    }

    private fun refactorApply(params: JsonObject?): JsonElement {
        val planId = params?.string("planId") ?: missing("planId")
        val pending = pendingPlans[planId]
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: $planId")
        val plan = pending.plan
        val root = workspaceRoot ?: throw JsonRpcException(JsonRpcErrorCodes.PROJECT_NOT_OPEN, "No project open")
        val currentSnap = scanner.scan(root)
        return when (val result = PatchEngine(root).apply(
            plan,
            currentSnap,
            ApplyAuthorization.explicit("daemon-json-rpc"),
            DiagnosticsGate.enabled("java-jdt", adapter::diagnostics),
        )) {
            is ApplyResult.Applied -> {
                val refreshed = scanner.scan(root)
                val diagnostics = boundedDiagnostics(adapter.diagnostics(refreshed))
                snapshot = refreshed
                pendingPlans.clear()
                val primary = pending.importPreview?.primaryFile
                val changes = fileChanges(plan.workspaceEdit, primary)
                PROTOCOL_JSON.encodeToJsonElement(ApplyResponseDto(
                    status = "applied",
                    planId = planId,
                    transactionId = result.transaction.id.value,
                    changedFiles = changes,
                    changedFilePaths = changes.map(ProtocolFileChangeDto::path),
                    primaryFile = primary?.let(ProtocolPath::serialize),
                    diagnostics = diagnostics.items,
                    diagnosticsTruncated = diagnostics.truncated,
                    snapshotHash = refreshed.hash,
                    provider = ProtocolProviderDto(RefactorKitVersion.NAME, RefactorKitVersion.VERSION),
                ))
            }
            is ApplyResult.Refused -> {
                pendingPlans.remove(planId)
                val msg = result.diagnostics.joinToString("; ") { it.message }
                throw JsonRpcException(JsonRpcErrorCodes.applyRefusalCode(result.diagnostics), msg)
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
        if (detail.applyEligible) pendingPlans[detail.plan.id.value] = PendingPlan(detail.plan, detail)
        return importPreviewToJson(detail, snap)
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
        val record = log.loadRecord(transactionId)
            ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Transaction not found: $txId")
        return when (val result = PatchEngine(root).rollback(record.transaction, mode)) {
            is ApplyResult.Applied -> {
                val refreshed = scanner.scan(root)
                val diagnostics = boundedDiagnostics(adapter.diagnostics(refreshed))
                snapshot = refreshed
                pendingPlans.clear()
                val changes = fileChanges(record.forwardEdit, rollback = true)
                PROTOCOL_JSON.encodeToJsonElement(RollbackResponseDto(
                    status = "rolledBack",
                    transactionId = txId,
                    rolledBack = true,
                    changedFiles = changes,
                    changedFilePaths = changes.map(ProtocolFileChangeDto::path),
                    diagnostics = diagnostics.items,
                    diagnosticsTruncated = diagnostics.truncated,
                    snapshotHash = refreshed.hash,
                    provider = ProtocolProviderDto(RefactorKitVersion.NAME, RefactorKitVersion.VERSION),
                ))
            }
            is ApplyResult.Refused -> {
                val msg = result.diagnostics.joinToString("; ") { it.message }
                throw JsonRpcException(
                    JsonRpcErrorCodes.rollbackRefusalCode(result.diagnostics),
                    "Rollback refused: $msg",
                )
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

    private fun planToJson(plan: PatchPlan): JsonObject = buildJsonObject {
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
            put("name", if (plan.operation == "importExternalJavaClass") "refactorkit-java-external-importer" else "refactorkit-java")
            put("version", RefactorKitVersion.VERSION)
        })
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
        pendingPlans.clear()
        snapshot = null
        workspaceRoot = null
    }

    private data class PendingPlan(val plan: PatchPlan, val importPreview: ExternalImportPreview? = null)

    private data class DaemonMethodCapability(
        val name: String,
        val stability: String,
        val requiresProject: Boolean,
        val writesWorkspace: Boolean,
        val features: Map<String, Boolean> = emptyMap(),
    )

    companion object {
        private val PROTOCOL_JSON = Json { encodeDefaults = true; explicitNulls = true }

        private val DAEMON_METHODS = listOf(
            DaemonMethodCapability("server.version", "beta-contract", false, false),
            DaemonMethodCapability("server.capabilities", "beta-contract", false, false),
            DaemonMethodCapability("project.open", "beta-contract", false, false),
            DaemonMethodCapability(
                "project.summary", "beta-contract", true, false,
                mapOf(
                    "buildModelSummary" to true,
                    "sourceSets" to true,
                    "credentialRedaction" to true,
                    "offlineMissingStatus" to true,
                    "executionRefusedStatus" to true,
                ),
            ),
            DaemonMethodCapability("symbol.search", "beta-contract", true, false),
            DaemonMethodCapability("symbol.definition", "beta-contract", true, false),
            DaemonMethodCapability("symbol.references", "beta-contract", true, false),
            DaemonMethodCapability("diagnostics", "beta-contract", true, false),
            DaemonMethodCapability("refactor.preview", "beta-contract", true, false),
            DaemonMethodCapability("refactor.apply", "beta-contract", true, true),
            DaemonMethodCapability("refactor.discard", "beta-contract", false, false),
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
        )
    }
}

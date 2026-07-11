package org.refactorkit.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import org.refactorkit.java.JavaChangeSignaturePlanner
import org.refactorkit.java.JavaExtractMethodPlanner
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaLexer
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaPackageUtil
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameClassPlanner
import org.refactorkit.java.JavaRenameMemberPlanner
import org.refactorkit.java.JavaSafeDeletePlanner
import java.net.URI
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
    private val scanner = JavaProjectScanner()

    @Volatile private var rootUri: String? = null
    @Volatile private var snapshot: ProjectSnapshot? = null
    private val pendingPlans = object : LinkedHashMap<String, PatchPlan>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PatchPlan>?) = size > 128
    }

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
        "textDocument/didOpen",
        "textDocument/didChange",
        "textDocument/didSave",
        "textDocument/didClose"         -> { refreshSnapshot(); JsonNull }
        "textDocument/definition"       -> definition(params)
        "textDocument/references"       -> references(params)
        "textDocument/prepareRename"    -> prepareRename(params)
        "textDocument/rename"           -> rename(params)
        "textDocument/codeAction"       -> codeAction(params)
        "textDocument/documentSymbol"   -> documentSymbol(params)
        "textDocument/semanticTokens/full" -> semanticTokensFull(params)
        "textDocument/diagnostic"       -> documentDiagnostic(params)
        "workspace/executeCommand"      -> executeCommand(params)
        else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, "Method not found: $method")
    }

    // ── LSP methods ───────────────────────────────────────────────────────────

    private fun initialize(params: JsonObject?): JsonElement {
        rootUri = params?.string("rootUri") ?: (params?.get("workspaceFolders") as? JsonArray)
            ?.firstOrNull()?.jsonObject?.string("uri")
        rootUri?.let { uri ->
            val root = Paths.get(URI(uri)).toAbsolutePath().normalize()
            val recoveryErrors = PatchEngine(root).recover()
            if (recoveryErrors.isNotEmpty()) {
                throw JsonRpcException(
                    JsonRpcErrorCodes.INTERNAL_ERROR,
                    "Workspace recovery required: ${recoveryErrors.joinToString("; ") { it.message }}",
                )
            }
            refreshSnapshotFromUri(uri)
        }
        return buildJsonObject {
            put("capabilities", buildJsonObject {
                put("textDocumentSync", 1) // Full sync
                put("definitionProvider", true)
                put("referencesProvider", true)
                put("documentSymbolProvider", true)
                put("codeActionProvider", true)
                put("renameProvider", buildJsonObject { put("prepareProvider", true) })
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
                put("executeCommandProvider", buildJsonObject {
                    put("commands", buildJsonArray {
                        add(JsonPrimitive("refactorkit.renameClass"))
                        add(JsonPrimitive("refactorkit.moveClass"))
                        add(JsonPrimitive("refactorkit.extractMethod"))
                        add(JsonPrimitive("refactorkit.changeSignature.renameParameter"))
                        add(JsonPrimitive("refactorkit.changeSignature.addParameter"))
                        add(JsonPrimitive("refactorkit.changeSignature.reorderParameters"))
                        add(JsonPrimitive("refactorkit.changeSignature.removeParameter"))
                        add(JsonPrimitive("refactorkit.organizeImports"))
                        add(JsonPrimitive("refactorkit.safeDelete"))
                        add(JsonPrimitive("refactorkit.applyPlan"))
                        add(JsonPrimitive("refactorkit.rollback"))
                    })
                })
            })
            put("serverInfo", buildJsonObject {
                put("name", RefactorKitVersion.NAME)
                put("version", RefactorKitVersion.VERSION)
            })
        }
    }

    private fun definition(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
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

    private fun references(params: JsonObject?): JsonElement {
        val (fileUri, line, char) = extractPosition(params) ?: return JsonNull
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
        val symbol = adapter.resolveSymbol(snap, pointLocation(file.path, line, char)).symbol ?: return JsonNull
        val plan = when (symbol.kind) {
            org.refactorkit.core.Symbol.Kind.METHOD,
            org.refactorkit.core.Symbol.Kind.FIELD ->
                JavaRenameMemberPlanner(adapter).preview(snap, symbol.id.value, newName)
            else ->
                JavaRenameClassPlanner(adapter).preview(snap, symbol.id.value, newName)
        }
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, plan.summary)
        }
        pendingPlans[plan.id.value] = plan
        return planToLspWorkspaceEdit(plan, snap)
    }

    private fun codeAction(params: JsonObject?): JsonElement {
        val fileUri = params?.obj("textDocument")?.string("uri") ?: return JsonArray(emptyList())
        val snap = snapshot ?: return JsonArray(emptyList())
        val file = fileForUri(snap, fileUri) ?: return JsonArray(emptyList())
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
                    org.refactorkit.core.Symbol.Kind.FIELD -> "refactorkit.renameMember"
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
                    actions += buildJsonObject {
                        put("title", "RefactorKit: Change signature of ${symbol.name}")
                        put("kind", "refactor.rewrite")
                        put("command", commandJson("RefactorKit: Change signature of ${symbol.name}",
                            "refactorkit.changeSignature.renameParameter", buildJsonObject {
                                put("symbol", symbol.id.value)
                                put("oldName", "")
                                put("newName", "")
                            }))
                    }
                    actions += buildJsonObject {
                        put("title", "RefactorKit: Add parameter to ${symbol.name}")
                        put("kind", "refactor.rewrite")
                        put("command", commandJson("RefactorKit: Add parameter to ${symbol.name}",
                            "refactorkit.changeSignature.addParameter", buildJsonObject {
                                put("symbol", symbol.id.value)
                                put("type", "")
                                put("name", "")
                                put("default", "")
                            }))
                    }
                    actions += buildJsonObject {
                        put("title", "RefactorKit: Reorder parameters of ${symbol.name}")
                        put("kind", "refactor.rewrite")
                        put("command", commandJson("RefactorKit: Reorder parameters of ${symbol.name}",
                            "refactorkit.changeSignature.reorderParameters", buildJsonObject {
                                put("symbol", symbol.id.value)
                                put("order", "")
                            }))
                    }
                    actions += buildJsonObject {
                        put("title", "RefactorKit: Remove parameter from ${symbol.name}")
                        put("kind", "refactor.rewrite")
                        put("command", commandJson("RefactorKit: Remove parameter from ${symbol.name}",
                            "refactorkit.changeSignature.removeParameter", buildJsonObject {
                                put("symbol", symbol.id.value)
                                put("name", "")
                            }))
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
        val snap = snapshot ?: return JsonNull
        val file = snap.files.find { snap.workspace.root.resolve(it.path).toUri().toString() == fileUri }
            ?: return JsonNull
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
        val items = diagnosticsForFile(snap, file.path)
        return buildJsonObject {
            put("kind", "full")
            put("items", JsonArray(items))
        }
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
                pendingPlans[plan.id.value] = plan
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.addParameter" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val type = args.string("type") ?: args.string("parameterType") ?: missing("type")
                val name = args.string("name") ?: args.string("parameterName") ?: missing("name")
                val default = args.string("default") ?: args.string("defaultExpression") ?: missing("default")
                val plan = JavaChangeSignaturePlanner(adapter).previewAddParameter(snap, symbol, type, name, default)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                pendingPlans[plan.id.value] = plan
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.reorderParameters" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val order = args.string("order") ?: missing("order")
                val plan = JavaChangeSignaturePlanner(adapter).previewReorderParameters(snap, symbol, order.split(','))
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                pendingPlans[plan.id.value] = plan
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.changeSignature.removeParameter" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val name = args.string("name") ?: args.string("parameterName") ?: missing("name")
                val plan = JavaChangeSignaturePlanner(adapter).previewRemoveParameter(snap, symbol, name)
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                pendingPlans[plan.id.value] = plan
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.moveClass" -> {
                val symbol = args?.string("symbol") ?: missing("symbol")
                val pkg = args?.string("targetPackage") ?: missing("targetPackage")
                val plan = JavaMoveClassPlanner(adapter).preview(snap, symbol, pkg)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.organizeImports" -> {
                val file = args?.string("file") ?: missing("file")
                val plan = JavaOrganizeImportsPlanner().previewSingleFile(snap, Paths.get(file))
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
                if (plan.status == PatchStatus.REFUSED) throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
                planToLspWorkspaceEdit(plan, snap)
            }
            "refactorkit.applyPlan" -> {
                val planId = args?.string("planId") ?: missing("planId")
                val plan = pendingPlans[planId] ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Plan not found: $planId")
                val current = scanner.scan(root)
                when (val result = PatchEngine(root).apply(plan, current)) {
                    is ApplyResult.Applied -> {
                        pendingPlans.remove(planId)
                        refreshSnapshot()
                        buildJsonObject { put("transactionId", result.transaction.id.value) }
                    }
                    is ApplyResult.Refused -> throw JsonRpcException(JsonRpcErrorCodes.SNAPSHOT_CHANGED, result.diagnostics.first().message)
                }
            }
            "refactorkit.rollback" -> {
                val transactionId = args?.string("transactionId") ?: missing("transactionId")
                val parsedTransactionId = TransactionId.parseOrNull(transactionId)
                    ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Invalid transaction ID: $transactionId")
                val log = TransactionLog(root.resolve(".refactorkit/transactions"))
                val tx = log.load(parsedTransactionId)
                    ?: throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Transaction not found: $transactionId")
                when (val result = PatchEngine(root).rollback(tx)) {
                    is ApplyResult.Applied -> {
                        refreshSnapshot()
                        buildJsonObject {
                            put("status", "rolledBack")
                            put("transactionId", transactionId)
                        }
                    }
                    is ApplyResult.Refused -> throw JsonRpcException(
                        JsonRpcErrorCodes.INTERNAL_ERROR,
                        "Rollback refused: ${result.diagnostics.joinToString("; ") { it.message }}",
                    )
                }
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Unknown command: $command")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun refreshSnapshot() {
        rootUri?.let { refreshSnapshotFromUri(it) }
    }

    private fun refreshSnapshotFromUri(uri: String) {
        try {
            val path = Paths.get(URI(uri))
            snapshot = scanner.scan(path)
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

    private fun planToLspWorkspaceEdit(plan: PatchPlan, snap: ProjectSnapshot): JsonObject {
        if (plan.status == PatchStatus.REFUSED) {
            throw JsonRpcException(JsonRpcErrorCodes.PLAN_REFUSED, plan.summary)
        }
        pendingPlans[plan.id.value] = plan
        val changes = mutableMapOf<String, MutableList<JsonObject>>()
        val documentChanges = mutableListOf<JsonObject>()
        for (edit in plan.workspaceEdit.edits) {
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
                            put("version", JsonNull)
                        })
                        put("edits", JsonArray(edits))
                    }
                }
                is FileEdit.Rename -> documentChanges += buildJsonObject {
                    put("kind", "rename")
                    put("oldUri", uriForPath(snap, edit.path))
                    put("newUri", uriForPath(snap, edit.newPath))
                }
                is FileEdit.Create -> documentChanges += buildJsonObject {
                    put("kind", "create")
                    put("uri", uriForPath(snap, edit.path))
                    put("options", buildJsonObject {
                        put("overwrite", edit.overwrite)
                        put("ignoreIfExists", false)
                    })
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
            put("warnings", buildJsonArray { plan.warnings.forEach { add(JsonPrimitive(it)) } })
            put("changes", buildJsonObject {
                changes.forEach { (uri, edits) -> put(uri, JsonArray(edits)) }
            })
            put("documentChanges", JsonArray(documentChanges))
        }
    }

    private fun commandJson(title: String, command: String, argument: JsonObject): JsonObject = buildJsonObject {
        put("title", title)
        put("command", command)
        put("arguments", buildJsonArray { add(argument) })
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
        put("message", diagnostic.message)
    }

    private fun rangeJson(sl: Int, sc: Int, el: Int, ec: Int): JsonObject = buildJsonObject {
        put("start", buildJsonObject { put("line", sl); put("character", sc) })
        put("end", buildJsonObject { put("line", el); put("character", ec) })
    }

    private fun lspSymbolKind(kind: org.refactorkit.core.Symbol.Kind): Int = when (kind) {
        org.refactorkit.core.Symbol.Kind.CLASS       -> 5
        org.refactorkit.core.Symbol.Kind.INTERFACE   -> 11
        org.refactorkit.core.Symbol.Kind.ENUM        -> 10
        org.refactorkit.core.Symbol.Kind.RECORD      -> 5
        org.refactorkit.core.Symbol.Kind.METHOD      -> 6
        org.refactorkit.core.Symbol.Kind.FIELD       -> 8
        org.refactorkit.core.Symbol.Kind.CONSTRUCTOR -> 9
        org.refactorkit.core.Symbol.Kind.PACKAGE     -> 4
        else                                          -> 13
    }

    private fun semanticTokenType(kind: org.refactorkit.core.Symbol.Kind): Int = when (kind) {
        org.refactorkit.core.Symbol.Kind.CLASS,
        org.refactorkit.core.Symbol.Kind.RECORD      -> TOKEN_TYPES.indexOf("class")
        org.refactorkit.core.Symbol.Kind.INTERFACE   -> TOKEN_TYPES.indexOf("interface")
        org.refactorkit.core.Symbol.Kind.ENUM        -> TOKEN_TYPES.indexOf("enum")
        org.refactorkit.core.Symbol.Kind.METHOD      -> TOKEN_TYPES.indexOf("method")
        org.refactorkit.core.Symbol.Kind.FIELD       -> TOKEN_TYPES.indexOf("property")
        org.refactorkit.core.Symbol.Kind.CONSTRUCTOR -> TOKEN_TYPES.indexOf("constructor")
        else                                          -> TOKEN_TYPES.indexOf("variable")
    }

    private fun lspDiagnosticSeverity(severity: Diagnostic.Severity): Int = when (severity) {
        Diagnostic.Severity.ERROR -> 1
        Diagnostic.Severity.WARNING -> 2
        Diagnostic.Severity.INFO -> 3
    }

    private fun missing(field: String): Nothing =
        throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "Missing required field: $field")

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

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

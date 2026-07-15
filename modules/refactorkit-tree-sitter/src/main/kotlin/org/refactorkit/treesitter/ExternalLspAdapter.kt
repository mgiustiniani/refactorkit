package org.refactorkit.treesitter

import org.refactorkit.core.AdapterExecutionMode
import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.CompletionTrigger
import org.refactorkit.core.LanguageAdapterDescriptor
import org.refactorkit.core.LanguageAdapterResourceLimits
import org.refactorkit.core.LanguageAdapterRuntime
import org.refactorkit.core.LanguageCapability
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.SemanticCompletionItem
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ManagedSemanticProcess
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.SemanticWorkspaceOverlay
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.ParseResult
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringDescriptor
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Language adapter that delegates to an external LSP server process.
 *
 * This is the Level 2 "LSP-backed" multi-language support described in AGENTS.md §23.2.
 *
 * Lifecycle:
 * ```kotlin
 * val adapter = ExternalLspAdapter(
 *     languageId = "typescript",
 *     command = listOf("typescript-language-server", "--stdio"),
 * )
 * adapter.start(rootUri = "file:///path/to/project")
 * val index = adapter.buildSymbols(snapshot)
 * val refs  = adapter.findReferences(someSymbolId)
 * adapter.stop()
 * ```
 *
 * Notification handling:
 * LSP servers may send `window/logMessage`, `$/progress`, and
 * `textDocument/publishDiagnostics` notifications between responses.
 * [readMatchingFrame] skips up to [MAX_SKIP_FRAMES] frames before giving up,
 * so spurious notifications do not corrupt the request/response pairing.
 *
 * Thread safety: single-threaded use only (one request at a time).
 */
data class ExternalSemanticSessionProvenance(
    val process: SemanticProcessProvenance,
    val serverName: String?,
    val serverVersion: String?,
    val capabilitiesSha256: String,
    val advertisedCapabilities: Map<String, Boolean>,
)

data class ExternalSemanticFailure(val code: String, val message: String)

sealed interface ExternalSymbolProjection {
    data class Available(val index: SymbolIndex, val truncated: Boolean) : ExternalSymbolProjection
    data class Unavailable(val failure: ExternalSemanticFailure) : ExternalSymbolProjection
}

sealed interface ExternalCompletionProjection {
    data class Available(
        val items: List<SemanticCompletionItem>,
        val incomplete: Boolean,
    ) : ExternalCompletionProjection
    data class Unavailable(val failure: ExternalSemanticFailure) : ExternalCompletionProjection
}

sealed interface ExternalHoverProjection {
    data class Available(
        val range: SourceRange?,
        val sections: List<org.refactorkit.core.SemanticHoverSection>,
    ) : ExternalHoverProjection
    data class Unavailable(val failure: ExternalSemanticFailure) : ExternalHoverProjection
}

sealed interface ExternalSemanticDiagnostics {
    data class Available(
        val diagnostics: List<Diagnostic>,
        val processProvenance: SemanticProcessProvenance? = null,
    ) : ExternalSemanticDiagnostics
    data class Unavailable(val diagnostic: Diagnostic) : ExternalSemanticDiagnostics
}

class ExternalLspAdapter(
    private val languageId: String,
    private val command: List<String>,
    private val initializationOptionsJson: String? = null,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val environment: Map<String, String> = safeEnvironment(),
) : LanguageAdapter, AutoCloseable {

    init {
        require(command.isNotEmpty()) { "external LSP command must not be empty" }
        require(initializationOptionsJson == null || LspJson.isObject(initializationOptionsJson)) {
            "external LSP initialization options must be one strict JSON object"
        }
        require(requestTimeoutMillis in 100..MAX_REQUEST_TIMEOUT_MILLIS) { "external LSP timeout is outside the safe range" }
    }

    private val nextId  = AtomicInteger(1)
    private val rootUri = AtomicReference<String>("")
    private var managedProcess: ManagedSemanticProcess? = null
    private var writer: OutputStream? = null
    private var reader: InputStream? = null
    private var requestExecutor: ExecutorService? = null
    private var workspaceOverlay: SemanticWorkspaceOverlay? = null

    @Volatile var sessionProvenance: ExternalSemanticSessionProvenance? = null
        private set
    @Volatile var lastFailure: ExternalSemanticFailure? = null
        private set

    /** Last symbol index built by [buildSymbols]; used by [findReferences]. */
    private val lastIndex = AtomicReference<SymbolIndex?>(null)

    /** Pending `textDocument/publishDiagnostics` payloads (params JSON). */
    private val pendingDiagParams = ArrayDeque<String>()
    private val structuralAdapter = TreeSitterAdapter()
    private val openDocuments = linkedMapOf<Path, OpenDocumentState>()

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /** Starts against a source-only overlay so the real workspace is never the LSP root. */
    @Synchronized
    fun start(snapshot: ProjectSnapshot) {
        require(workspaceOverlay == null) { "external LSP overlay is already active" }
        val overlay = SemanticWorkspaceOverlay.create(snapshot)
        workspaceOverlay = overlay
        try {
            start(overlay.root.toUri().toString())
        } catch (failure: Throwable) {
            workspaceOverlay = null
            overlay.close()
            throw failure
        }
    }

    /** Prototype direct-root start. Production callers should use [start] with a snapshot overlay. */
    @Synchronized
    fun start(rootUri: String) {
        require(!isRunning) { "external LSP adapter is already running" }
        val workspace = workspacePath(rootUri)
        this.rootUri.set(rootUri)
        lastFailure = null
        sessionProvenance = null
        val executable = resolveExecutable(command.first())
        val process = processManager.launch(SemanticProcessSpec(
            id = "lsp-${languageId}-${PROCESS_SEQUENCE.getAndIncrement()}",
            executable = executable,
            arguments = command.drop(1),
            workingDirectory = workspace,
            environment = environment,
            limits = SemanticProcessLimits(
                maxStdoutBytes = MAX_SESSION_OUTPUT_BYTES,
                maxStderrBytes = MAX_STDERR_BYTES,
                gracefulShutdownMillis = SHUTDOWN_TIMEOUT_MILLIS,
            ),
        ))
        managedProcess = process
        writer = process.input
        reader = process.output
        requestExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "refactorkit-lsp-read-$languageId").apply { isDaemon = true }
        }

        val initializeParams = buildString {
            append("{\"processId\":").append(ProcessHandle.current().pid())
            append(",\"rootUri\":").append(LspJson.quote(rootUri))
            append(",\"capabilities\":{\"textDocument\":{\"documentSymbol\":{\"hierarchicalDocumentSymbolSupport\":true},\"publishDiagnostics\":{\"versionSupport\":true}}}")
            initializationOptionsJson?.let { append(",\"initializationOptions\":").append(it) }
            append('}')
        }
        val initialize = sendRequest("initialize", initializeParams)
        val result = initialize?.let { LspJson.extractField(it, "result") }
        val capabilities = result?.let { LspJson.extractField(it, "capabilities") }
        if (result == null || capabilities == null) {
            if (lastFailure == null) {
                failAndStop("semantic.initializeInvalid", "LSP initialize response is missing result/capabilities")
            }
            throw IllegalStateException(lastFailure!!.message)
        }
        val serverInfo = LspJson.extractField(result, "serverInfo")
        sessionProvenance = ExternalSemanticSessionProvenance(
            process = process.provenance,
            serverName = serverInfo?.let { LspJson.extractField(it, "name") }?.let(LspJson::unquote),
            serverVersion = serverInfo?.let { LspJson.extractField(it, "version") }?.let(LspJson::unquote),
            capabilitiesSha256 = sha256(capabilities),
            advertisedCapabilities = KNOWN_SERVER_CAPABILITIES.associateWith { capability ->
                val raw = if (capability == "prepareRenameProvider") {
                    val renameProvider = LspJson.extractField(capabilities, "renameProvider")
                    renameProvider?.let { LspJson.extractField(it, "prepareProvider") }
                        ?: renameProvider
                } else LspJson.extractField(capabilities, capability)
                capabilityAdvertised(capability, raw)
            }.toSortedMap(),
        )
        sendNotification("initialized", "{}")
    }

    @Synchronized
    fun stop() {
        if (isRunning) {
            runCatching { sendRequest("shutdown", null) }
            runCatching { sendNotification("exit", null) }
        }
        managedProcess?.close()
        requestExecutor?.shutdownNow()
        val overlay = workspaceOverlay
        val overlayDiagnostics = overlay?.verifySourcesUnchanged().orEmpty()
        if (overlayDiagnostics.isNotEmpty() && lastFailure == null) {
            lastFailure = ExternalSemanticFailure(
                overlayDiagnostics.first().code ?: "semantic.overlaySourceModified",
                overlayDiagnostics.first().message,
            )
        }
        overlay?.close()
        workspaceOverlay = null
        openDocuments.clear()
        writer = null
        reader = null
        managedProcess = null
        requestExecutor = null
    }

    override fun close() = stop()

    val isRunning: Boolean get() = managedProcess?.isAlive == true

    // ── LanguageAdapter ───────────────────────────────────────────────────────

    override fun languageId(): String = languageId

    override fun parse(file: SourceFile): ParseResult = ParseResult(file)

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex {
        val files = project.files.filter { it.languageId == languageId }.sortedBy { it.path.toString() }
        val items = if (isRunning && supportsServerCapability("documentSymbolProvider")) {
            if (files.size > MAX_DOCUMENT_SYMBOL_FILES) {
                lastFailure = ExternalSemanticFailure(
                    "semantic.documentSymbolFileLimit",
                    "Document-symbol request exceeds $MAX_DOCUMENT_SYMBOL_FILES files",
                )
                emptyList()
            } else {
                val synchronized = files.all { file ->
                    openDocuments[file.path.normalize()] != null || openDocument(file, 1)
                }
                if (!synchronized) emptyList() else files.flatMap(::requestDocumentSymbols)
            }
        } else {
            files.flatMap { file ->
                structuralAdapter.outline(file.content, languageId).map { item ->
                    Symbol(
                        id = SymbolId("${file.path}::${item.name}"),
                        name = item.name,
                        kind = mapOutlineKind(item.kind),
                        location = SourceLocation(
                            file.path,
                            SourceRange(
                                SourcePosition(item.line, item.character),
                                SourcePosition(item.line, item.character + item.name.length),
                            ),
                        ),
                        languageId = languageId,
                    )
                }
            }
        }
        val index = SymbolIndex(items.distinctBy { it.id }.sortedBy { it.id.value })
        lastIndex.set(index)
        return index
    }

    /**
     * Build an all-document declaration projection under one aggregate deadline.
     * Provider failures never publish a partial index; symbol-cap truncation is
     * explicit and deterministic.
     */
    fun buildSymbolProjection(
        project: ProjectSnapshot,
        maxSymbols: Int,
        timeoutMillis: Long = requestTimeoutMillis,
    ): ExternalSymbolProjection {
        require(maxSymbols in 1..org.refactorkit.core.ProtocolLimits.MAX_WORKSPACE_INDEX_PROVIDER_SYMBOLS) {
            "symbol projection limit is outside the safe range"
        }
        require(timeoutMillis in 100..MAX_REQUEST_TIMEOUT_MILLIS) { "symbol projection timeout is outside the safe range" }
        lastFailure = null
        if (!isRunning || !supportsServerCapability("documentSymbolProvider")) {
            return ExternalSymbolProjection.Unavailable(ExternalSemanticFailure(
                "semantic.documentSymbolsUnavailable",
                "External semantic document symbols are unavailable",
            ))
        }
        val files = project.files.filter { it.languageId == languageId }.sortedBy { it.path.toString() }
        if (files.size > MAX_DOCUMENT_SYMBOL_FILES) {
            val failure = ExternalSemanticFailure(
                "semantic.documentSymbolFileLimit",
                "Document-symbol request exceeds $MAX_DOCUMENT_SYMBOL_FILES files",
            )
            lastFailure = failure
            return ExternalSymbolProjection.Unavailable(failure)
        }
        val started = System.nanoTime()
        fun deadlineExceeded(): Boolean = (System.nanoTime() - started) / 1_000_000L >= timeoutMillis
        val symbols = linkedMapOf<SymbolId, Symbol>()
        for (file in files) {
            if (deadlineExceeded()) {
                val failure = ExternalSemanticFailure(
                    "semantic.documentSymbolTimeout",
                    "Document-symbol projection exceeded ${timeoutMillis}ms",
                )
                lastFailure = failure
                return ExternalSymbolProjection.Unavailable(failure)
            }
            if (openDocuments[file.path.normalize()] == null && !openDocument(file, 1)) {
                val failure = lastFailure ?: ExternalSemanticFailure(
                    "semantic.documentSynchronizationFailed",
                    "Document-symbol source synchronization failed",
                )
                lastFailure = failure
                return ExternalSymbolProjection.Unavailable(failure)
            }
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            val remainingMillis = timeoutMillis - elapsedMillis
            if (remainingMillis <= 0) {
                val failure = ExternalSemanticFailure(
                    "semantic.documentSymbolTimeout",
                    "Document-symbol projection exceeded ${timeoutMillis}ms",
                )
                lastFailure = failure
                return ExternalSymbolProjection.Unavailable(failure)
            }
            val returned = requestDocumentSymbols(file, minOf(requestTimeoutMillis, remainingMillis))
            if (lastFailure?.code == "semantic.requestTimeout") {
                val failure = ExternalSemanticFailure(
                    "semantic.documentSymbolTimeout",
                    "Document-symbol projection exceeded ${timeoutMillis}ms",
                )
                lastFailure = failure
                return ExternalSymbolProjection.Unavailable(failure)
            }
            if (deadlineExceeded()) {
                val failure = ExternalSemanticFailure(
                    "semantic.documentSymbolTimeout",
                    "Document-symbol projection exceeded ${timeoutMillis}ms",
                )
                lastFailure = failure
                return ExternalSymbolProjection.Unavailable(failure)
            }
            lastFailure?.let { return ExternalSymbolProjection.Unavailable(it) }
            for (symbol in returned) {
                symbols.putIfAbsent(symbol.id, symbol)
                if (symbols.size > maxSymbols) {
                    val bounded = SymbolIndex(symbols.values.take(maxSymbols).sortedBy { it.id.value })
                    lastIndex.set(bounded)
                    return ExternalSymbolProjection.Available(bounded, truncated = true)
                }
            }
        }
        val index = SymbolIndex(symbols.values.sortedBy { it.id.value })
        lastIndex.set(index)
        return ExternalSymbolProjection.Available(index, truncated = false)
    }

    /** Query one temporary immutable editor view and restore saved documents before returning. */
    fun buildOverlayDocumentSymbolProjection(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        maxSymbols: Int,
        timeoutMillis: Long = requestTimeoutMillis,
    ): ExternalSymbolProjection {
        require(overlay.baseSnapshotHash == savedSnapshot.hash) { "editor overlay snapshot is stale" }
        require(maxSymbols in 1..org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS) {
            "document symbol projection limit is outside the safe range"
        }
        require(timeoutMillis in 100..MAX_REQUEST_TIMEOUT_MILLIS) { "document symbol timeout is outside the safe range" }
        val target = targetPath.normalize()
        require(overlay.document(target) != null) { "target document is not part of the editor overlay" }
        val saved = savedSnapshot.files.associateBy { it.path.normalize() }
        val provider = overlay.providerSnapshot.files.associateBy { it.path.normalize() }
        lastFailure = null
        if (!isRunning || !supportsServerCapability("documentSymbolProvider")) return ExternalSymbolProjection.Unavailable(
            ExternalSemanticFailure("semantic.documentSymbolsUnavailable", "External semantic document symbols are unavailable"),
        )
        val changed = overlay.documents.map { it.path.normalize() }.sortedBy(Path::toString)
        var result: ExternalSymbolProjection? = null
        try {
            for (document in overlay.documents) {
                val path = document.path.normalize()
                closeDocument(path)
                val source = provider.getValue(path)
                if (!openDocument(source, document.version)) {
                    result = ExternalSymbolProjection.Unavailable(lastFailure ?: ExternalSemanticFailure(
                        "semantic.overlaySynchronizationFailed", "Editor overlay document synchronization failed",
                    ))
                    break
                }
            }
            if (result == null) {
                val targetFile = provider.getValue(target)
                val returned = requestDocumentSymbols(targetFile, timeoutMillis)
                result = lastFailure?.let(ExternalSymbolProjection::Unavailable) ?: run {
                    val distinct = returned.distinctBy { it.id }.sortedBy { it.id.value }
                    ExternalSymbolProjection.Available(
                        SymbolIndex(distinct.take(maxSymbols)),
                        truncated = distinct.size > maxSymbols,
                    )
                }
            }
        } finally {
            var restored = true
            changed.forEach { path ->
                closeDocument(path)
                restored = openDocument(saved.getValue(path), 1) && restored
            }
            if (!restored) {
                failAndStop("semantic.overlayRestoreFailed", "Saved document state could not be restored after overlay query")
                result = ExternalSymbolProjection.Unavailable(requireNotNull(lastFailure))
            }
        }
        return result ?: ExternalSymbolProjection.Unavailable(ExternalSemanticFailure(
            "semantic.documentSymbolsUnavailable", "Editor overlay document symbols are unavailable",
        ))
    }

    fun buildOverlayCompletion(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
        trigger: CompletionTrigger,
        triggerCharacter: String?,
        limit: Int,
        timeoutMillis: Long = requestTimeoutMillis,
    ): ExternalCompletionProjection {
        require(overlay.baseSnapshotHash == savedSnapshot.hash) { "editor overlay snapshot is stale" }
        require(limit in 1..org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS) { "completion limit is invalid" }
        val target = targetPath.normalize()
        require(overlay.document(target) != null) { "completion target is not part of the editor overlay" }
        val saved = savedSnapshot.files.associateBy { it.path.normalize() }
        val provider = overlay.providerSnapshot.files.associateBy { it.path.normalize() }
        lastFailure = null
        if (!isRunning || !supportsServerCapability("completionProvider")) return ExternalCompletionProjection.Unavailable(
            ExternalSemanticFailure("semantic.completionUnavailable", "External semantic completion is unavailable"),
        )
        val changed = overlay.documents.map { it.path.normalize() }.sortedBy(Path::toString)
        var result: ExternalCompletionProjection? = null
        try {
            for (document in overlay.documents) {
                val path = document.path.normalize()
                closeDocument(path)
                if (!openDocument(provider.getValue(path), document.version)) {
                    result = ExternalCompletionProjection.Unavailable(lastFailure ?: ExternalSemanticFailure(
                        "semantic.overlaySynchronizationFailed", "Editor overlay document synchronization failed",
                    ))
                    break
                }
            }
            if (result == null) {
                val semantic = semanticPath(target) ?: error("completion target has no semantic path")
                val triggerKind = when (trigger) {
                    CompletionTrigger.INVOKED -> 1
                    CompletionTrigger.TRIGGER_CHARACTER -> 2
                    CompletionTrigger.INCOMPLETE_RETRIGGER -> 3
                }
                val context = buildString {
                    append("{\"triggerKind\":").append(triggerKind)
                    triggerCharacter?.let { append(",\"triggerCharacter\":").append(LspJson.quote(it)) }
                    append('}')
                }
                val response = sendRequest(
                    "textDocument/completion",
                    """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}},"position":{"line":${position.line},"character":${position.character}},"context":$context}""",
                    timeoutMillis,
                )
                val parsed = response?.let { LspJson.extractField(it, "result") }
                    ?.let { LspCompletionParser.parse(it, limit) }
                result = when {
                    lastFailure != null -> ExternalCompletionProjection.Unavailable(requireNotNull(lastFailure))
                    parsed == null -> ExternalCompletionProjection.Unavailable(ExternalSemanticFailure(
                        "semantic.completionResponseInvalid", "External semantic completion response is invalid",
                    ))
                    else -> ExternalCompletionProjection.Available(parsed.items, parsed.incomplete)
                }
            }
        } finally {
            var restored = true
            changed.forEach { path ->
                closeDocument(path)
                restored = openDocument(saved.getValue(path), 1) && restored
            }
            if (!restored) {
                failAndStop("semantic.overlayRestoreFailed", "Saved document state could not be restored after completion query")
                result = ExternalCompletionProjection.Unavailable(requireNotNull(lastFailure))
            }
        }
        return result ?: ExternalCompletionProjection.Unavailable(
            ExternalSemanticFailure("semantic.completionUnavailable", "External semantic completion is unavailable"),
        )
    }

    fun buildOverlayHover(
        savedSnapshot: ProjectSnapshot,
        overlay: ImmutableEditorOverlay,
        targetPath: Path,
        position: SourcePosition,
        timeoutMillis: Long = requestTimeoutMillis,
    ): ExternalHoverProjection {
        require(overlay.baseSnapshotHash == savedSnapshot.hash) { "editor overlay snapshot is stale" }
        val target = targetPath.normalize()
        require(overlay.document(target) != null) { "hover target is not part of the editor overlay" }
        val saved = savedSnapshot.files.associateBy { it.path.normalize() }
        val provider = overlay.providerSnapshot.files.associateBy { it.path.normalize() }
        lastFailure = null
        if (!isRunning || !supportsServerCapability("hoverProvider")) return ExternalHoverProjection.Unavailable(
            ExternalSemanticFailure("semantic.hoverUnavailable", "External semantic hover is unavailable"),
        )
        val changed = overlay.documents.map { it.path.normalize() }.sortedBy(Path::toString)
        var result: ExternalHoverProjection? = null
        try {
            for (document in overlay.documents) {
                val path = document.path.normalize()
                closeDocument(path)
                if (!openDocument(provider.getValue(path), document.version)) {
                    result = ExternalHoverProjection.Unavailable(lastFailure ?: ExternalSemanticFailure(
                        "semantic.overlaySynchronizationFailed", "Editor overlay document synchronization failed",
                    ))
                    break
                }
            }
            if (result == null) {
                val semantic = semanticPath(target) ?: error("hover target has no semantic path")
                val response = sendRequest(
                    "textDocument/hover",
                    """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}},"position":{"line":${position.line},"character":${position.character}}}""",
                    timeoutMillis,
                )
                val parsed = response?.let { LspJson.extractField(it, "result") }?.let(LspHoverParser::parse)
                result = when {
                    lastFailure != null -> ExternalHoverProjection.Unavailable(requireNotNull(lastFailure))
                    parsed == null -> ExternalHoverProjection.Unavailable(ExternalSemanticFailure(
                        "semantic.hoverResponseInvalid", "External semantic hover response is invalid",
                    ))
                    else -> ExternalHoverProjection.Available(parsed.range, parsed.sections)
                }
            }
        } finally {
            var restored = true
            changed.forEach { path ->
                closeDocument(path)
                restored = openDocument(saved.getValue(path), 1) && restored
            }
            if (!restored) {
                failAndStop("semantic.overlayRestoreFailed", "Saved document state could not be restored after hover query")
                result = ExternalHoverProjection.Unavailable(requireNotNull(lastFailure))
            }
        }
        return result ?: ExternalHoverProjection.Unavailable(
            ExternalSemanticFailure("semantic.hoverUnavailable", "External semantic hover is unavailable"),
        )
    }

    fun searchWorkspaceSymbols(query: String): List<Symbol> {
        if (!isRunning || !supportsServerCapability("workspaceSymbolProvider") ||
            query.length > 1_024 || '\u0000' in query) return emptyList()
        val response = sendRequest("workspace/symbol", """{"query":${LspJson.quote(query)}}""") ?: return emptyList()
        val result = LspJson.extractField(response, "result") ?: return emptyList()
        if (result.trim() == "null") return emptyList()
        return LspDocumentSymbolParser.parse(result, Path.of("."), languageId) { returned ->
            workspaceOverlay?.toWorkspacePath(returned) ?: returned
        }.take(org.refactorkit.core.ProtocolLimits.MAX_SYMBOL_RESULTS)
    }

    private fun requestDocumentSymbols(
        file: SourceFile,
        timeoutMillis: Long = requestTimeoutMillis,
    ): List<Symbol> {
        val semantic = semanticPath(file.path) ?: return emptyList()
        val response = sendRequest(
            "textDocument/documentSymbol",
            """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}}}""",
            timeoutMillis,
        ) ?: return emptyList()
        val result = LspJson.extractField(response, "result") ?: return emptyList()
        if (result.trim() == "null") return emptyList()
        return LspDocumentSymbolParser.parse(result, file.path.normalize(), languageId) { returned ->
            workspaceOverlay?.toWorkspacePath(returned) ?: returned
        }
    }

    /**
     * Forward `textDocument/definition` to the external LSP server and parse the result.
     * Returns [SymbolResolution] with a best-effort [Symbol] constructed from the
     * returned location. Returns `SymbolResolution(null)` when not running or no result.
     */
    override fun resolveSymbol(location: SourceLocation): SymbolResolution {
        if (!isRunning) return SymbolResolution(null)
        val uri = LspJson.pathToUri(semanticPath(location.path) ?: return SymbolResolution(null))
        val response = sendRequest(
            "textDocument/definition",
            """{"textDocument":{"uri":"$uri"},"position":{"line":${location.range.start.line},"character":${location.range.start.character}}}""",
        ) ?: return SymbolResolution(null)

        val resultJson = LspJson.extractField(response, "result") ?: return SymbolResolution(null)
        val lspLocations = LspJson.parseLocations(resultJson)
        val first = lspLocations.firstOrNull() ?: return SymbolResolution(null)

        val semanticFilePath = LspJson.uriToPath(first.uri)
        val filePath = workspaceOverlay?.toWorkspacePath(semanticFilePath) ?: semanticFilePath
        val baseName  = filePath.fileName?.toString()?.substringBeforeLast('.') ?: ""
        val startPos  = SourcePosition(first.startLine, first.startChar)
        val endPos    = SourcePosition(first.endLine, first.endChar)

        // Try to look up a richer Symbol from the cached index
        val cached = lastIndex.get()?.symbols?.find { sym ->
            (sym.location.path == filePath || filePath.endsWith(sym.location.path.normalize())) &&
            sym.location.range.start.line == first.startLine &&
            first.startChar in sym.location.range.start.character..sym.location.range.end.character
        }

        val sym = cached ?: Symbol(
            id = SymbolId("${filePath}::$baseName"),
            name = baseName,
            kind = Symbol.Kind.UNKNOWN,
            location = SourceLocation(filePath, SourceRange(startPos, endPos)),
            languageId = languageId,
        )
        return SymbolResolution(symbol = sym)
    }

    /**
     * Forward `textDocument/references` to the external LSP server using the
     * symbol's location from the last [buildSymbols] call.
     *
     * Returns an empty list when the adapter is not running or the symbol was
     * not found in the cached index.
     */
    override fun findReferences(symbolId: SymbolId): List<Reference> {
        if (!isRunning) return emptyList()
        val symbol = lastIndex.get()?.symbols?.find { it.id == symbolId }
            ?: return emptyList()

        val uri = LspJson.pathToUri(semanticPath(symbol.location.path) ?: return emptyList())
        val loc = symbol.location
        val response = sendRequest(
            "textDocument/references",
            """{"textDocument":{"uri":"$uri"},"position":{"line":${loc.range.start.line},"character":${loc.range.start.character}},"context":{"includeDeclaration":true}}""",
        ) ?: return emptyList()

        val resultJson = LspJson.extractField(response, "result") ?: return emptyList()
        return LspJson.parseLocations(resultJson).take(org.refactorkit.core.ProtocolLimits.MAX_REFERENCE_RESULTS).map { lspLoc ->
            Reference(
                symbolId = symbolId,
                location = SourceLocation(
                    LspJson.uriToPath(lspLoc.uri).let { workspaceOverlay?.toWorkspacePath(it) ?: it },
                    SourceRange(
                        SourcePosition(lspLoc.startLine, lspLoc.startChar),
                        SourcePosition(lspLoc.endLine,   lspLoc.endChar),
                    ),
                ),
            )
        }
    }

    /**
     * Returns diagnostics from pending `textDocument/publishDiagnostics` notifications
     * collected during earlier requests.
     */
    override fun diagnostics(project: ProjectSnapshot): List<Diagnostic> {
        if (pendingDiagParams.isEmpty()) return emptyList()
        val result = mutableListOf<Diagnostic>()
        while (pendingDiagParams.isNotEmpty()) {
            val paramsJson = pendingDiagParams.removeFirst()
            val diagsJson = LspJson.extractField(paramsJson, "diagnostics") ?: continue
            val uri = LspJson.extractField(paramsJson, "uri")?.let(LspJson::unquote)
            result += parseDiagnosticsArray(diagsJson, uri).take(MAX_DIAGNOSTICS - result.size)
            if (result.size >= MAX_DIAGNOSTICS) break
        }
        return result
    }

    /**
     * Synchronizes every source document to the supplied immutable image and
     * accepts diagnostics only when every document publishes the exact version.
     * Missing or unversioned publications fail closed for managed apply gates.
     */
    @Synchronized
    fun synchronizedDiagnostics(project: ProjectSnapshot): ExternalSemanticDiagnostics {
        if (!isRunning) return unavailableDiagnostics("semantic.notRunning", "External semantic server is not running")
        if (!supportsServerCapability("documentSymbolProvider")) return unavailableDiagnostics(
            "semantic.diagnosticsBarrierUnavailable", "Exact diagnostics require documentSymbolProvider",
        )
        val files = project.files.filter { it.languageId == languageId }.sortedBy { it.path.toString() }
        if (files.size > MAX_OPEN_DOCUMENTS) return unavailableDiagnostics(
            "semantic.diagnosticsDocumentLimit", "Exact diagnostics exceed $MAX_OPEN_DOCUMENTS source documents",
        )
        val desired = files.associateBy { it.path.normalize() }
        openDocuments.keys.filter { it !in desired }.toList().forEach(::closeDocument)
        pendingDiagParams.clear()
        for (file in files) {
            val state = openDocuments[file.path.normalize()]
            val synchronized = if (state == null) openDocument(file, 1) else {
                if (state.version == Long.MAX_VALUE) false else forceChangeDocument(file, state.version + 1)
            }
            if (!synchronized) return unavailableDiagnostics(
                "semantic.diagnosticsSynchronizationFailed",
                "Exact diagnostics could not synchronize '${file.path}'",
            )
        }
        if (files.isEmpty()) return ExternalSemanticDiagnostics.Available(emptyList())

        // Requests are ordered after didChange notifications and act as bounded
        // protocol barriers while publishDiagnostics notifications are consumed.
        repeat(2) {
            files.forEach(::requestDocumentSymbols)
            if (!isRunning) return unavailableDiagnostics(
                lastFailure?.code ?: "semantic.diagnosticsBarrierFailed",
                lastFailure?.message ?: "Exact diagnostics barrier failed",
            )
        }
        // typescript-language-server debounces publishDiagnostics. Continue with
        // bounded protocol barriers so queued notifications are consumed without
        // accepting an unbounded or timing-only wait.
        repeat(MAX_DIAGNOSTIC_BARRIER_ATTEMPTS) { attempt ->
            if (pendingDiagParams.size >= files.size) return@repeat
            try {
                Thread.sleep(DIAGNOSTIC_BARRIER_DELAY_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return unavailableDiagnostics(
                    "semantic.diagnosticsBarrierInterrupted", "Exact diagnostics barrier was interrupted",
                )
            }
            requestDocumentSymbols(files[attempt % files.size])
            if (!isRunning) return unavailableDiagnostics(
                lastFailure?.code ?: "semantic.diagnosticsBarrierFailed",
                lastFailure?.message ?: "Exact diagnostics barrier failed",
            )
        }

        val expectedRoot = project.workspace.root.toAbsolutePath().normalize()
        val latest = linkedMapOf<Path, String>()
        while (pendingDiagParams.isNotEmpty()) {
            val params = pendingDiagParams.removeFirst()
            val rawUri = LspJson.extractField(params, "uri")?.let(LspJson::unquote) ?: continue
            val overlayPath = runCatching { LspJson.uriToPath(rawUri) }.getOrNull() ?: continue
            val workspacePath = workspaceOverlay?.toWorkspacePath(overlayPath) ?: continue
            if (!workspacePath.startsWith(expectedRoot)) continue
            val relative = expectedRoot.relativize(workspacePath).normalize()
            val state = openDocuments[relative] ?: continue
            val version = LspJson.extractField(params, "version")?.trim()?.toLongOrNull()
            if (version == state.version) latest[relative] = params
        }
        val missing = desired.keys - latest.keys
        if (missing.isNotEmpty()) return unavailableDiagnostics(
            "semantic.diagnosticsIncomplete",
            "Exact versioned diagnostics were not published for ${missing.size} source document(s)",
        )
        val result = mutableListOf<Diagnostic>()
        latest.toSortedMap(compareBy(Path::toString)).values.forEach { params ->
            val diagnostics = LspJson.extractField(params, "diagnostics") ?: return@forEach
            val uri = LspJson.extractField(params, "uri")?.let(LspJson::unquote)
            result += parseDiagnosticsArray(diagnostics, uri).take(MAX_DIAGNOSTICS - result.size)
        }
        return ExternalSemanticDiagnostics.Available(result.take(MAX_DIAGNOSTICS))
    }

    fun supportsServerCapability(name: String): Boolean =
        sessionProvenance?.advertisedCapabilities?.get(name) == true

    @Synchronized
    fun openDocument(file: SourceFile, version: Long = 1): Boolean {
        if (!isRunning || version < 0 || openDocuments.size >= MAX_OPEN_DOCUMENTS && file.path.normalize() !in openDocuments) return false
        if (file.content.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return false
        val path = file.path.normalize()
        if (path in openDocuments) return changeDocument(file, version)
        val semantic = semanticPath(path) ?: return false
        sendNotification("textDocument/didOpen", """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))},"languageId":${LspJson.quote(file.languageId)},"version":$version,"text":${LspJson.quote(file.content)}}}""")
        if (!isRunning) return false
        openDocuments[path] = OpenDocumentState(version, sha256(file.content))
        return true
    }

    @Synchronized
    fun changeDocument(file: SourceFile, version: Long): Boolean {
        val current = openDocuments[file.path.normalize()] ?: return false
        if (current.contentSha256 == sha256(file.content)) return false
        return forceChangeDocument(file, version)
    }

    private fun forceChangeDocument(file: SourceFile, version: Long): Boolean {
        if (!isRunning || file.content.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return false
        val path = file.path.normalize()
        val current = openDocuments[path] ?: return false
        if (version <= current.version) return false
        val semantic = semanticPath(path) ?: return false
        sendNotification("textDocument/didChange", """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))},"version":$version},"contentChanges":[{"text":${LspJson.quote(file.content)}}]}""")
        if (!isRunning) return false
        openDocuments[path] = OpenDocumentState(version, sha256(file.content))
        return true
    }

    @Synchronized
    fun closeDocument(path: Path): Boolean {
        val normalized = path.normalize()
        if (normalized !in openDocuments) return false
        val semantic = semanticPath(normalized) ?: return false
        sendNotification("textDocument/didClose", """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}}}""")
        openDocuments.remove(normalized)
        return true
    }

    fun requestRename(
        snapshot: ProjectSnapshot,
        location: SourceLocation,
        newName: String,
    ): ExternalWorkspaceEditNormalization {
        if (!supportsServerCapability("renameProvider")) return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "semantic.capabilityUnavailable", "External semantic server does not advertise renameProvider",
        )))
        if (newName.isBlank() || newName.length > 1_024 || '\u0000' in newName) return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "externalEdit.renameInvalid", "External semantic rename target is invalid",
        )))
        val locationPath = if (location.path.isAbsolute) {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            val normalized = location.path.toAbsolutePath().normalize()
            if (!normalized.startsWith(root)) null else root.relativize(normalized)
        } else location.path.normalize()
        val file = locationPath?.let { target -> snapshot.files.singleOrNull { it.path.normalize() == target } }
            ?: return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
                "externalEdit.documentMissing", "Rename document is not present in the snapshot",
            )))
        if (!validPosition(file.content, location.range.start)) return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "externalEdit.positionInvalid", "Rename position is outside the document or splits a UTF-16 surrogate pair",
        )))
        val documentState = openDocuments[file.path.normalize()]
        val contentHash = sha256(file.content)
        val synchronized = when {
            documentState == null -> openDocument(file, 1)
            documentState.contentSha256 != contentHash && documentState.version < Long.MAX_VALUE ->
                changeDocument(file, documentState.version + 1)
            documentState.contentSha256 != contentHash -> false
            else -> true
        }
        if (!synchronized) return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "semantic.documentOpenFailed", "Rename document could not be synchronized with the semantic server",
        )))
        val semantic = semanticPath(file.path) ?: return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "externalEdit.pathOutsideOverlay", "Rename document is outside the semantic overlay",
        )))
        val position = location.range.start
        val prepared = prepareRename(file, semantic, position)
        if (prepared != null) return ExternalWorkspaceEditNormalization.Refused(listOf(prepared))
        return requestWorkspaceEdit(
            "textDocument/rename",
            """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}},"position":{"line":${position.line},"character":${position.character}},"newName":${LspJson.quote(newName)}}""",
            snapshot,
        )
    }

    private fun prepareRename(file: SourceFile, semantic: Path, position: SourcePosition): Diagnostic? {
        if (!supportsServerCapability("prepareRenameProvider")) return externalDiagnostic(
            "semantic.prepareRenameUnavailable", "External semantic server does not advertise prepareRename support",
        )
        val response = sendRequest(
            "textDocument/prepareRename",
            """{"textDocument":{"uri":${LspJson.quote(LspJson.pathToUri(semantic))}},"position":{"line":${position.line},"character":${position.character}}}""",
        ) ?: return externalDiagnostic(
            lastFailure?.code ?: "semantic.prepareRenameMissing",
            lastFailure?.message ?: "External semantic server returned no prepareRename response",
        )
        val result = LspJson.extractField(response, "result")
            ?: return externalDiagnostic("semantic.prepareRenameInvalid", "prepareRename response has no result")
        if (result.trim() == "null") return externalDiagnostic(
            "semantic.prepareRenameRefused", "External semantic server refused prepareRename at the selected position",
        )
        val rangeJson = LspJson.extractField(result, "range") ?: result
        val parsed = LspJson.parseLocation(
            """{"uri":${LspJson.quote(LspJson.pathToUri(semantic))},"range":$rangeJson}""",
        ) ?: return externalDiagnostic("semantic.prepareRenameInvalid", "prepareRename result has no valid range")
        val start = SourcePosition(parsed.startLine, parsed.startChar)
        val end = SourcePosition(parsed.endLine, parsed.endChar)
        if (!validPosition(file.content, start) || !validPosition(file.content, end) ||
            comparePosition(position, start) < 0 || comparePosition(position, end) > 0) {
            return externalDiagnostic(
                "semantic.prepareRenameInvalid", "prepareRename range is outside the exact document image",
            )
        }
        return null
    }

    private fun comparePosition(left: SourcePosition, right: SourcePosition): Int =
        compareValuesBy(left, right, SourcePosition::line, SourcePosition::character)

    override fun availableRefactorings(selection: CodeSelection): List<RefactoringDescriptor> = listOf(
        RefactoringDescriptor("localRename", "Local rename (textual)", RiskLevel.LOW),
    )

    override fun applyRefactoring(request: RefactoringRequest): PatchPlan = PatchPlan(
        operation = request.operation,
        status = PatchStatus.REFUSED,
        snapshotHash = request.snapshot.hash,
        confidence = 0.0,
        summary = "External LSP refactoring not yet implemented for language: $languageId",
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        riskLevel = RiskLevel.HIGH,
    )

    override fun formatEdits(edits: List<TextEdit>): List<TextEdit> = edits

    // ── JSON-RPC framing ──────────────────────────────────────────────────────

    /**
     * Send a JSON-RPC request and return the raw response body whose `id` matches
     * the generated request `id`. Notifications and foreign responses are buffered
     * and handled via [handleFrame].
     */
    fun sendRequest(
        method: String,
        paramsJson: String?,
        timeoutMillis: Long = requestTimeoutMillis,
    ): String? {
        require(LSP_METHOD.matches(method)) { "LSP method name is invalid" }
        require(paramsJson == null || LspJson.isValidValue(paramsJson)) { "LSP request params are invalid JSON" }
        require(timeoutMillis in 1..requestTimeoutMillis) { "LSP request timeout is outside the session bound" }
        val id   = nextId.getAndIncrement()
        val body = buildString {
            append("""{"jsonrpc":"2.0","id":$id,"method":${LspJson.quote(method)}""")
            if (paramsJson != null) append(""","params":$paramsJson""")
            append('}')
        }
        writeFramed(body)
        val executor = requestExecutor ?: return null
        val pending = executor.submit<String?> { readMatchingFrame(id) }
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true)
            failAndStop("semantic.requestTimeout", "LSP request '$method' exceeded ${timeoutMillis}ms")
            null
        } catch (failure: ExecutionException) {
            failAndStop("semantic.protocolFailure", failure.cause?.message ?: "LSP protocol read failed")
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failAndStop("semantic.requestCancelled", "LSP request '$method' was cancelled")
            null
        }
    }

    /**
     * Requests an external WorkspaceEdit, parses its standard LSP schema, and
     * normalizes it against the immutable snapshot. The accepted result remains
     * an unapproved proposal and must still pass PatchPlan/diagnostics/apply gates.
     */
    fun requestWorkspaceEdit(
        method: String,
        paramsJson: String,
        snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
    ): ExternalWorkspaceEditNormalization {
        if (!isRunning) return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "semantic.notRunning", "External semantic process is not running",
        )))
        val response = sendRequest(method, paramsJson)
            ?: return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
                lastFailure?.code ?: "semantic.responseMissing",
                lastFailure?.message ?: "External semantic process returned no response",
            )))
        val result = LspJson.extractField(response, "result")
            ?: return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
                "externalEdit.resultMissing", "External semantic response has no result",
            )))
        if (result.trim() == "null") return ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
            "externalEdit.resultNull", "External semantic server returned no workspace edit",
        )))
        return when (val parsed = ExternalLspWorkspaceEditParser.parse(
            result,
            providerId = "lsp-$languageId",
            providerVersion = sessionProvenance?.serverVersion,
        )) {
            is ExternalLspWorkspaceEditParsing.Accepted -> {
                val overlayDiagnostics = workspaceOverlay?.verifySourcesUnchanged().orEmpty()
                if (overlayDiagnostics.isNotEmpty()) {
                    ExternalWorkspaceEditNormalization.Refused(overlayDiagnostics)
                } else {
                    val overlay = workspaceOverlay
                    val proposal = if (overlay == null) parsed.proposal else remapOverlayProposal(parsed.proposal, overlay)
                    if (proposal == null) ExternalWorkspaceEditNormalization.Refused(listOf(externalDiagnostic(
                        "externalEdit.pathOutsideOverlay", "External semantic edit contains a path outside its workspace overlay",
                    ))) else when (val normalized = normalizer.normalize(snapshot, proposal)) {
                        is ExternalWorkspaceEditNormalization.Refused -> normalized
                        is ExternalWorkspaceEditNormalization.Accepted -> validateDocumentVersions(normalized)
                    }
                }
            }
            is ExternalLspWorkspaceEditParsing.Refused -> ExternalWorkspaceEditNormalization.Refused(parsed.diagnostics)
        }
    }

    private fun validateDocumentVersions(
        accepted: ExternalWorkspaceEditNormalization.Accepted,
    ): ExternalWorkspaceEditNormalization {
        accepted.normalized.documentVersions.forEach { (path, returnedVersion) ->
            val current = openDocuments[path.normalize()] ?: return ExternalWorkspaceEditNormalization.Refused(listOf(
                externalDiagnostic(
                    "externalEdit.documentNotSynchronized",
                    "External edit returned a version for a document that is not synchronized",
                ),
            ))
            if (returnedVersion != current.version) return ExternalWorkspaceEditNormalization.Refused(listOf(
                externalDiagnostic(
                    "externalEdit.documentVersionStale",
                    "External edit document version $returnedVersion does not match synchronized version ${current.version}",
                ),
            ))
        }
        return accepted
    }

    /** Send a JSON-RPC notification (no `id`, no response expected). */
    fun sendNotification(method: String, paramsJson: String?) {
        require(LSP_METHOD.matches(method)) { "LSP method name is invalid" }
        require(paramsJson == null || LspJson.isValidValue(paramsJson)) { "LSP notification params are invalid JSON" }
        val body = buildString {
            append("""{"jsonrpc":"2.0","method":${LspJson.quote(method)}""")
            if (paramsJson != null) append(""","params":$paramsJson""")
            append('}')
        }
        writeFramed(body)
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun writeFramed(body: String) {
        val output = writer ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) {
            failAndStop("semantic.frameTooLarge", "outbound LSP frame exceeds $MAX_FRAME_BYTES bytes")
            return
        }
        synchronized(output) {
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    /**
     * Read frames from the LSP server until one matches [expectedId].
     * Notifications and responses for other IDs are forwarded to [handleFrame].
     * Gives up after [MAX_SKIP_FRAMES] frames to avoid blocking indefinitely.
     */
    private fun readMatchingFrame(expectedId: Int): String? {
        repeat(MAX_SKIP_FRAMES) {
            val json = readSingleFrame() ?: return null
            if (isMatchingResponse(json, expectedId)) return json
            handleFrame(json)
        }
        return null
    }

    /** Returns true if [json] is a JSON-RPC response with the given [expectedId]. */
    private fun isMatchingResponse(json: String, expectedId: Int): Boolean {
        // Fast check before full parse: look for the id field
        val idField = LspJson.extractField(json, "id") ?: return false
        return idField.trim() == expectedId.toString()
    }

    /** Handle a frame that is not our expected response. */
    private fun handleFrame(json: String) {
        val method = LspJson.extractField(json, "method") ?: return
        val cleanMethod = LspJson.unquote(method)
        if (cleanMethod == "textDocument/publishDiagnostics") {
            LspJson.extractField(json, "params")?.let {
                if (pendingDiagParams.size >= MAX_PENDING_DIAGNOSTIC_NOTIFICATIONS) pendingDiagParams.removeFirst()
                pendingDiagParams.addLast(it)
            }
        }
        // Other notifications ($/progress, window/logMessage, etc.) are silently dropped
    }

    /** Read one byte-counted Content-Length frame from the LSP server. */
    private fun readSingleFrame(): String? {
        val input = reader ?: return null
        var contentLength: Int? = null
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - headerBytes) ?: return null
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw IllegalStateException("LSP headers exceed $MAX_HEADER_BYTES bytes")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                if (contentLength != null) throw IllegalStateException("duplicate LSP Content-Length header")
                contentLength = line.substringAfter(':').trim().toIntOrNull()
                    ?: throw IllegalStateException("invalid LSP Content-Length header")
            }
        }
        val length = contentLength ?: throw IllegalStateException("missing LSP Content-Length header")
        if (length !in 0..MAX_FRAME_BYTES) throw IllegalStateException("LSP frame exceeds $MAX_FRAME_BYTES bytes")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IllegalStateException("truncated LSP frame")
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readAsciiLine(input: InputStream, remaining: Int): String? {
        if (remaining <= 0) throw IllegalStateException("LSP headers exceed $MAX_HEADER_BYTES bytes")
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < remaining) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IllegalStateException("truncated LSP header")
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val size = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, size, Charsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IllegalStateException("LSP headers exceed $MAX_HEADER_BYTES bytes")
    }

    private data class OpenDocumentState(val version: Long, val contentSha256: String)

    private fun validPosition(content: String, position: SourcePosition): Boolean {
        if (position.line < 0 || position.character < 0) return false
        val lines = content.split('\n')
        if (position.line >= lines.size) return false
        val line = lines[position.line].removeSuffix("\r")
        if (position.character > line.length) return false
        return position.character == 0 || position.character == line.length ||
            !(line[position.character].isLowSurrogate() && line[position.character - 1].isHighSurrogate())
    }

    private fun semanticPath(path: Path): Path? = workspaceOverlay?.toOverlayPath(path) ?: path

    private fun remapOverlayProposal(
        proposal: ExternalWorkspaceEditProposal,
        overlay: SemanticWorkspaceOverlay,
    ): ExternalWorkspaceEditProposal? {
        val remapped = proposal.edits.map { edit ->
            val source = overlay.toWorkspacePath(edit.path) ?: return null
            when (edit) {
                is ExternalFileEditProposal.Modify -> edit.copy(path = source)
                is ExternalFileEditProposal.Create -> edit.copy(path = source)
                is ExternalFileEditProposal.Delete -> edit.copy(path = source)
                is ExternalFileEditProposal.Rename -> edit.copy(
                    path = source,
                    newPath = overlay.toWorkspacePath(edit.newPath) ?: return null,
                )
            }
        }
        return proposal.copy(edits = remapped)
    }

    private fun unavailableDiagnostics(code: String, message: String) = ExternalSemanticDiagnostics.Unavailable(
        externalDiagnostic(code, message),
    )

    private fun externalDiagnostic(code: String, message: String) = Diagnostic(
        message = message,
        severity = Diagnostic.Severity.ERROR,
        code = code,
        evidence = org.refactorkit.core.DiagnosticEvidence.STRUCTURAL,
        category = org.refactorkit.core.DiagnosticCategory.SAFETY,
    )

    @Synchronized
    private fun failAndStop(code: String, message: String) {
        lastFailure = ExternalSemanticFailure(code, message)
        managedProcess?.cancel()
        requestExecutor?.shutdownNow()
        workspaceOverlay?.close()
        workspaceOverlay = null
        openDocuments.clear()
        writer = null
        reader = null
        managedProcess = null
        requestExecutor = null
    }

    private fun workspacePath(uri: String): Path {
        val parsed = runCatching { URI(uri) }.getOrElse { throw IllegalArgumentException("invalid LSP root URI", it) }
        require(parsed.scheme.equals("file", ignoreCase = true)) { "external LSP root URI must use file scheme" }
        val path = Path.of(parsed).toAbsolutePath().normalize()
        require(Files.isDirectory(path)) { "external LSP workspace must be an existing directory" }
        return path
    }

    private fun resolveExecutable(value: String): Path {
        val candidate = Path.of(value)
        if (candidate.isAbsolute) return candidate
        val pathEntries = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        val names = if (System.getProperty("os.name").startsWith("Windows") && candidate.fileName.toString().substringAfterLast('.', "").isEmpty()) {
            listOf(candidate.toString() + ".exe", candidate.toString() + ".cmd", candidate.toString() + ".bat", candidate.toString())
        } else listOf(candidate.toString())
        return pathEntries.asSequence().filter(String::isNotBlank).flatMap { directory ->
            names.asSequence().map { Path.of(directory).resolve(it).toAbsolutePath().normalize() }
        }.firstOrNull(Files::isRegularFile)
            ?: throw IllegalArgumentException("external LSP executable was not found: $value")
    }

    private fun capabilityAdvertised(name: String, rawValue: String?): Boolean {
        val value = rawValue?.trim() ?: return false
        if (value in setOf("false", "null")) return false
        if (name == "textDocumentSync" && value == "0") return false
        return true
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ── diagnostics helper ────────────────────────────────────────────────────

    private fun parseDiagnosticsArray(json: String, uri: String?): List<Diagnostic> {
        val path = uri?.let { runCatching { LspJson.uriToPath(it) }.getOrNull() }
            ?.let { workspaceOverlay?.toWorkspacePath(it) ?: it }
        return LspJson.parseArray(json).take(MAX_DIAGNOSTICS).mapNotNull { obj ->
            val message = LspJson.extractField(obj, "message")?.let(LspJson::unquote)?.take(MAX_DIAGNOSTIC_MESSAGE_CHARS)
                ?: return@mapNotNull null
            val severity = when (LspJson.extractField(obj, "severity")?.trim()?.toIntOrNull()) {
                1 -> Diagnostic.Severity.ERROR
                2 -> Diagnostic.Severity.WARNING
                else -> Diagnostic.Severity.INFO
            }
            val range = LspJson.extractField(obj, "range")?.let { rangeJson ->
                uri?.let { locationUri -> LspJson.parseLocation("""{"uri":${LspJson.quote(locationUri)},"range":$rangeJson}""") }
            }
            val location = if (path != null && range != null) SourceLocation(
                path,
                SourceRange(
                    SourcePosition(range.startLine, range.startChar),
                    SourcePosition(range.endLine, range.endChar),
                ),
            ) else null
            val codeValue = LspJson.extractField(obj, "code")
            val code = codeValue?.let { if (it.startsWith('"')) LspJson.unquote(it) else it.take(128) }
            Diagnostic(
                message = message,
                severity = severity,
                location = location,
                code = code,
                evidence = org.refactorkit.core.DiagnosticEvidence.COMPILER,
                category = org.refactorkit.core.DiagnosticCategory.TYPE_RESOLUTION,
            )
        }
    }

    // ── kind mapping ──────────────────────────────────────────────────────────

    private fun mapOutlineKind(kind: GenericOutline.OutlineItem.Kind): Symbol.Kind = when (kind) {
        GenericOutline.OutlineItem.Kind.CLASS     -> Symbol.Kind.CLASS
        GenericOutline.OutlineItem.Kind.INTERFACE -> Symbol.Kind.INTERFACE
        GenericOutline.OutlineItem.Kind.ENUM      -> Symbol.Kind.ENUM
        GenericOutline.OutlineItem.Kind.RECORD    -> Symbol.Kind.RECORD
        GenericOutline.OutlineItem.Kind.FUNCTION,
        GenericOutline.OutlineItem.Kind.METHOD    -> Symbol.Kind.METHOD
        GenericOutline.OutlineItem.Kind.PROPERTY,
        GenericOutline.OutlineItem.Kind.CONSTANT,
        GenericOutline.OutlineItem.Kind.VARIABLE  -> Symbol.Kind.FIELD
        else                                      -> Symbol.Kind.UNKNOWN
    }

    companion object {
        /** Maximum number of LSP frames to skip while waiting for a matching response. */
        const val MAX_SKIP_FRAMES = 50
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        const val MAX_HEADER_BYTES = 8 * 1024
        const val MAX_SESSION_OUTPUT_BYTES = 64L * 1024L * 1024L
        const val MAX_STDERR_BYTES = 64 * 1024
        const val MAX_PENDING_DIAGNOSTIC_NOTIFICATIONS = 500
        const val MAX_DIAGNOSTICS = 500
        private const val MAX_DIAGNOSTIC_BARRIER_ATTEMPTS = 20
        private const val DIAGNOSTIC_BARRIER_DELAY_MILLIS = 50L
        const val MAX_DIAGNOSTIC_MESSAGE_CHARS = 4_096
        const val MAX_OPEN_DOCUMENTS = 256
        const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
        const val MAX_DOCUMENT_SYMBOL_FILES = 256
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 120_000L
        const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
        private val PROCESS_SEQUENCE = AtomicInteger(1)
        private val KNOWN_SERVER_CAPABILITIES = sortedSetOf(
            "definitionProvider", "referencesProvider", "renameProvider", "prepareRenameProvider",
            "documentSymbolProvider", "workspaceSymbolProvider", "hoverProvider", "completionProvider", "textDocumentSync",
        )
        private val LSP_METHOD = Regex("[A-Za-z0-9_" + '$' + "./-]{1,128}")

        fun descriptor(languageId: String, extensions: Set<String>) = LanguageAdapterDescriptor(
            languageId = languageId,
            extensions = extensions,
            backend = "external-lsp",
            capabilities = listOf(
                LanguageCapability("definition", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability("references", CapabilityStability.EXPERIMENTAL, SemanticEvidenceKind.LANGUAGE_SERVER),
                LanguageCapability(
                    "workspaceEditProposal",
                    CapabilityStability.EXPERIMENTAL,
                    SemanticEvidenceKind.LANGUAGE_SERVER,
                    MutationAuthority.PROPOSAL_ONLY,
                ),
            ),
            runtime = LanguageAdapterRuntime(
                executionMode = AdapterExecutionMode.EXTERNAL_PROCESS,
                supportsTimeout = true,
                supportsCancellation = true,
                usesWorkspaceOverlay = true,
                recordsProcessProvenance = true,
                limits = LanguageAdapterResourceLimits(
                    requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MILLIS,
                    maxInputBytes = MAX_FRAME_BYTES.toLong(),
                    maxOutputBytes = MAX_SESSION_OUTPUT_BYTES,
                    maxProcesses = org.refactorkit.core.ProtocolLimits.MAX_SEMANTIC_PROCESSES,
                ),
            ),
        )

        private fun safeEnvironment(): Map<String, String> = listOf(
            "HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP", "SystemRoot", "LANG", "LC_ALL",
        ).mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
    }
}

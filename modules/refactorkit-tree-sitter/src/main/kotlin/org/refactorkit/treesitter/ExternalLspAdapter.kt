package org.refactorkit.treesitter

import org.refactorkit.core.CodeSelection
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
)

data class ExternalSemanticFailure(val code: String, val message: String)

class ExternalLspAdapter(
    private val languageId: String,
    private val command: List<String>,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val environment: Map<String, String> = safeEnvironment(),
) : LanguageAdapter, AutoCloseable {

    init {
        require(command.isNotEmpty()) { "external LSP command must not be empty" }
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

        val initialize = sendRequest(
            "initialize",
            """{"processId":${ProcessHandle.current().pid()},"rootUri":${LspJson.quote(rootUri)},"capabilities":{}}""",
        )
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
        val items = project.files.filter { it.languageId == languageId }
            .flatMap { file ->
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
        val index = SymbolIndex(items)
        lastIndex.set(index)
        return index
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
            sym.location.path == filePath &&
            sym.location.range.start.line == first.startLine
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
        return LspJson.parseLocations(resultJson).map { lspLoc ->
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
            val diagsJson  = LspJson.extractField(paramsJson, "diagnostics") ?: continue
            // Parse the diagnostics array — each element has "message" and "severity" (1=error,2=warn,3=info,4=hint)
            val elements   = LspJson.extractValue(diagsJson, 0)?.let { _ ->
                parseDiagnosticsArray(diagsJson)
            } ?: continue
            result += elements
        }
        return result
    }

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
    fun sendRequest(method: String, paramsJson: String?): String? {
        require(LSP_METHOD.matches(method)) { "LSP method name is invalid" }
        require(paramsJson == null || LspJson.isValidValue(paramsJson)) { "LSP request params are invalid JSON" }
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
            pending.get(requestTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true)
            failAndStop("semantic.requestTimeout", "LSP request '$method' exceeded ${requestTimeoutMillis}ms")
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
                    ))) else normalizer.normalize(snapshot, proposal)
                }
            }
            is ExternalLspWorkspaceEditParsing.Refused -> ExternalWorkspaceEditNormalization.Refused(parsed.diagnostics)
        }
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

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    // ── diagnostics helper ────────────────────────────────────────────────────

    private fun parseDiagnosticsArray(json: String): List<Diagnostic> {
        val results = mutableListOf<Diagnostic>()
        // Extract the raw array content and iterate top-level objects
        val raw = json.trim()
        if (!raw.startsWith('[')) return emptyList()
        var i = 1
        while (i < raw.length - 1) {
            while (i < raw.length && (raw[i].isWhitespace() || raw[i] == ',')) i++
            if (i >= raw.length - 1) break
            val obj = LspJson.extractValue(raw, i) ?: break
            i += obj.length
            val msgField  = LspJson.extractField(obj, "message") ?: continue
            val msg       = LspJson.unquote(msgField)
            val sevField  = LspJson.extractField(obj, "severity")
            val severity  = when (sevField?.trim()?.toIntOrNull()) {
                1    -> Diagnostic.Severity.ERROR
                2    -> Diagnostic.Severity.WARNING
                else -> Diagnostic.Severity.INFO
            }
            results += Diagnostic(message = msg, severity = severity)
        }
        return results
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
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        const val MAX_REQUEST_TIMEOUT_MILLIS = 120_000L
        const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
        private val PROCESS_SEQUENCE = AtomicInteger(1)
        private val LSP_METHOD = Regex("[A-Za-z0-9_" + '$' + "./-]{1,128}")

        private fun safeEnvironment(): Map<String, String> = listOf(
            "HOME", "USERPROFILE", "TMPDIR", "TMP", "TEMP", "SystemRoot", "LANG", "LC_ALL",
        ).mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
    }
}

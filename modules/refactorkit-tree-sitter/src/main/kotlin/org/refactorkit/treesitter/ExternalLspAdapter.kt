package org.refactorkit.treesitter

import org.refactorkit.core.CodeSelection
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
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
class ExternalLspAdapter(
    private val languageId: String,
    private val command: List<String>,
) : LanguageAdapter {

    private val nextId  = AtomicInteger(1)
    private val rootUri = AtomicReference<String>("")

    // mutable for testing injection
    internal var process: Process?       = null
    internal var writer:  PrintWriter?   = null
    internal var reader:  BufferedReader? = null

    /** Last symbol index built by [buildSymbols]; used by [findReferences]. */
    private val lastIndex = AtomicReference<SymbolIndex?>(null)

    /** Pending `textDocument/publishDiagnostics` payloads (params JSON). */
    private val pendingDiagParams = ArrayDeque<String>()

    // ── lifecycle ─────────────────────────────────────────────────────────────

    fun start(rootUri: String) {
        this.rootUri.set(rootUri)
        val pb = ProcessBuilder(command).redirectErrorStream(false)
        process = pb.start()
        writer  = PrintWriter(process!!.outputStream.bufferedWriter(Charsets.UTF_8), true)
        reader  = BufferedReader(InputStreamReader(process!!.inputStream, Charsets.UTF_8))

        // initialize / initialized handshake
        sendRequest(
            "initialize",
            """{"processId":${ProcessHandle.current().pid()},"rootUri":"$rootUri","capabilities":{}}""",
        )
        sendNotification("initialized", "{}")
    }

    fun stop() {
        runCatching { sendRequest("shutdown", null) }
        runCatching { sendNotification("exit", null) }
        runCatching { process?.destroy() }
        writer  = null
        reader  = null
        process = null
    }

    val isRunning: Boolean get() = process?.isAlive == true

    // ── LanguageAdapter ───────────────────────────────────────────────────────

    override fun languageId(): String = languageId

    override fun parse(file: SourceFile): ParseResult = ParseResult(file)

    override fun buildSymbols(project: ProjectSnapshot): SymbolIndex {
        val items = project.files.filter { it.languageId == languageId }
            .flatMap { file ->
                GenericOutline.outline(file.content, languageId).map { item ->
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
        val uri = LspJson.pathToUri(location.path)
        val response = sendRequest(
            "textDocument/definition",
            """{"textDocument":{"uri":"$uri"},"position":{"line":${location.range.start.line},"character":${location.range.start.character}}}""",
        ) ?: return SymbolResolution(null)

        val resultJson = LspJson.extractField(response, "result") ?: return SymbolResolution(null)
        val lspLocations = LspJson.parseLocations(resultJson)
        val first = lspLocations.firstOrNull() ?: return SymbolResolution(null)

        val filePath  = LspJson.uriToPath(first.uri)
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

        val uri = LspJson.pathToUri(symbol.location.path)
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
                    LspJson.uriToPath(lspLoc.uri),
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
        val id   = nextId.getAndIncrement()
        val body = buildString {
            append("""{"jsonrpc":"2.0","id":$id,"method":"$method"""")
            if (paramsJson != null) append(""","params":$paramsJson""")
            append('}')
        }
        writeFramed(body)
        return readMatchingFrame(id)
    }

    /** Send a JSON-RPC notification (no `id`, no response expected). */
    fun sendNotification(method: String, paramsJson: String?) {
        val body = buildString {
            append("""{"jsonrpc":"2.0","method":"$method"""")
            if (paramsJson != null) append(""","params":$paramsJson""")
            append('}')
        }
        writeFramed(body)
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun writeFramed(body: String) {
        val w = writer ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        w.print("Content-Length: ${bytes.size}\r\n\r\n")
        w.print(body)
        w.flush()
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
            LspJson.extractField(json, "params")?.let { pendingDiagParams.addLast(it) }
        }
        // Other notifications ($/progress, window/logMessage, etc.) are silently dropped
    }

    /** Read one Content-Length-framed body from the LSP server. */
    private fun readSingleFrame(): String? {
        val r = reader ?: return null
        var contentLength = -1
        while (true) {
            val line = r.readLine() ?: return null
            if (line.isBlank()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) return null
        val buf = CharArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = r.read(buf, offset, contentLength - offset)
            if (read < 0) return null
            offset += read
        }
        return String(buf)
    }

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
    }
}

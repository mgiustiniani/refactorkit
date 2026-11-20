package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ManagedSemanticProcess
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** A semantic definition result from clangd. */
data class CSymbolDefinition(
    val file: Path,
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val name: String?,
)

/** A semantic reference result from clangd. */
data class CSymbolReference(
    val file: Path,
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
)

/** A single rename text edit from clangd. */
data class CRenameEdit(
    val file: Path,
    val startLine: Int,
    val startCharacter: Int,
    val endLine: Int,
    val endCharacter: Int,
    val newText: String,
)

/** Bounded clangd rename result. */
sealed interface CRenameResult {
    data class Found(val edits: List<CRenameEdit>) : CRenameResult
    data class NotFound(val diagnostics: List<Diagnostic>) : CRenameResult
    data class Refused(val diagnostics: List<Diagnostic>) : CRenameResult
}

/**
 * Bounded clangd reference result.
 *
 * Distinguishes an absent reference set (NotFound, zero uses) from an
 * unavailable analysis (Unavailable). Callers must never convert Unavailable
 * into "zero references": safe delete depends on this distinction.
 */
sealed interface CReferenceResult {
    data class Found(val references: List<CSymbolReference>) : CReferenceResult
    data class NotFound(val diagnostics: List<Diagnostic>) : CReferenceResult
    data class Unavailable(val diagnostics: List<Diagnostic>) : CReferenceResult
}

/** Bounded clangd semantic response. */
sealed interface CClangdSemanticResult {
    data class Found(val definition: CSymbolDefinition) : CClangdSemanticResult
    data class NotFound(val diagnostics: List<Diagnostic>) : CClangdSemanticResult
    data class Refused(val diagnostics: List<Diagnostic>) : CClangdSemanticResult
}

internal sealed interface CClangdSymbolInfoResponse {
    data class Available(val body: String) : CClangdSymbolInfoResponse
    data class Unavailable(val diagnostics: List<Diagnostic>) : CClangdSymbolInfoResponse
}

/**
 * Bounded clangd LSP client for textDocument/definition and textDocument/references.
 * clangd is launched through the shared bounded process manager; all requests are
 * deadline-bound. Unavailable, malformed or timed-out responses are refused.
 */
class ClangdSemanticClient(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val initializeTimeoutMillis: Long = DEFAULT_INITIALIZE_TIMEOUT_MILLIS,
    /** Explicit simulation ports are for tests/controlled hosts, never native qualification. */
    private val runtimeProvider: ClangToolchainRuntimeProvider = ClangToolchainRuntimeCapture(),
    readOnlySdks: List<CReadOnlySdkRequest> = emptyList(),
) : AutoCloseable {
    private class StartAttempt {
        @Volatile var cancelled = false
    }

    private val sdkRequests = (toolchain.readOnlySdkInputs.requests + readOnlySdks.take(CNativeSysrootPolicy.MAX_ROOTS + 1))
        .distinct().take(CNativeSysrootPolicy.MAX_ROOTS + 1).toList()
    private val lifecycleLock = Any()
    private var activeStart: StartAttempt? = null
    @Volatile private var managedProcess: ManagedSemanticProcess? = null
    @Volatile private var writer: OutputStream? = null
    @Volatile private var reader: InputStream? = null
    @Volatile private var requestExecutor: ExecutorService? = null
    private var workspaceRoot: Path? = null
    @Volatile private var launchContext: CClangdLaunchContext? = null
    private val nextId = AtomicInteger(1)

    init {
        require(requestTimeoutMillis in 1..300_000L) { "request timeout is outside the safe range" }
        require(initializeTimeoutMillis in 1..300_000L) { "initialize timeout is outside the safe range" }
    }

    fun processProvenance(): org.refactorkit.core.SemanticProcessProvenance? =
        synchronized(lifecycleLock) { managedProcess?.provenance }

    /** Captured caller-declared SDK metadata; live queries independently check input currency. */
    fun readOnlySdkProvenance(): List<CReadOnlySdkProvenance> = launchContext?.readOnlySdkProvenance().orEmpty()

    /** Launches clangd and completes the LSP initialize handshake. */
    fun start(workspaceRoot: Path) {
        val attempt = synchronized(lifecycleLock) {
            require(managedProcess == null && activeStart == null) { "clangd is already running or starting" }
            StartAttempt().also { activeStart = it }
        }
        try {
            ensureStartActive(attempt)
            runtimeAuthorityDiagnostic()?.let { throw CClangdConfigurationException(it) }
            ensureStartActive(attempt)
            if (!resourcesAreCurrent()) throw CClangdConfigurationException(resourceDiagnostic(
                "c.clangdResourcesUnavailable", "Complete retained Clang resources are unavailable or changed before startup"))
            ensureStartActive(attempt)
            val normalizedWorkspace = workspaceRoot.toAbsolutePath().normalize()
            val context = CClangdLaunchContext.capture(normalizedWorkspace, toolchain.clangExecutable, sdkRequests)
            val contextAccepted = synchronized(lifecycleLock) {
                if (!isStartActive(attempt)) {
                    false
                } else {
                    this.workspaceRoot = normalizedWorkspace
                    launchContext = context
                    true
                }
            }
            if (!contextAccepted) {
                context.close()
                ensureStartActive(attempt)
            }
            configurationChanged()?.let { throw CClangdConfigurationException(it) }
            ensureStartActive(attempt)
            val resourceRoot = (toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.root
            val clangd = toolchain.clangdExecutable.toAbsolutePath().normalize()
            val process = synchronized(lifecycleLock) {
                ensureStartActive(attempt)
                processManager.launch(SemanticProcessSpec(
                    id = "clangd-${PROCESS_SEQUENCE.getAndIncrement()}",
                    executable = clangd,
                    arguments = listOf("--enable-config=false", "--background-index=false", "--pch-storage=memory",
                        "--compile-commands-dir=${context.root}", "--resource-dir=$resourceRoot"),
                    workingDirectory = context.root,
                    limits = SemanticProcessLimits(
                        maxStdoutBytes = MAX_SESSION_OUTPUT_BYTES,
                        maxStderrBytes = MAX_STDERR_BYTES,
                        gracefulShutdownMillis = SHUTDOWN_TIMEOUT_MILLIS,
                    ),
                )).also { started ->
                    managedProcess = started
                    writer = started.input
                    reader = started.output
                    requestExecutor = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "refactorkit-clangd-${started.provenance.id}").apply { isDaemon = true }
                    }
                }
            }
            ensureStartActive(attempt)
            val initialize = withDeadline(initializeTimeoutMillis) {
                send("initialize", """{"processId":null,"rootUri":${quote(uri(normalizedWorkspace))},"capabilities":{},"workspaceFolders":[{"uri":${quote(uri(normalizedWorkspace))},"name":"root"}]}""")
            }
            if (initialize == null || parseMessage(initialize).success != true) {
                error("clangd did not complete the initialize handshake")
            }
            val initialized = withDeadline(requestTimeoutMillis) { notify("initialized", "{}") }
            if (initialized != true) error("clangd did not accept initialized")
            configurationChanged()?.let { throw CClangdConfigurationException(it) }
            ensureStartActive(attempt)
        } catch (failure: Exception) {
            close()
            throw failure
        } finally {
            synchronized(lifecycleLock) {
                if (activeStart === attempt) activeStart = null
            }
        }
    }

    private fun isStartActive(attempt: StartAttempt): Boolean = activeStart === attempt && !attempt.cancelled

    private fun ensureStartActive(attempt: StartAttempt) {
        if (!synchronized(lifecycleLock) { isStartActive(attempt) }) {
            throw IllegalStateException("clangd startup was cancelled")
        }
    }

    /** Opens a source document in clangd so definition/references can resolve. */
    fun didOpen(file: Path, content: String): Boolean {
        val process = managedProcess ?: return false
        if (!process.isAlive || configurationChanged() != null) return false
        val response = withDeadline(requestTimeoutMillis) {
            notify("textDocument/didOpen", """{"textDocument":{"uri":${quote(uri(file))},"languageId":"c","version":1,"text":${quote(content)}}}""")
        }
        return response == true && configurationChanged() == null
    }

    /** Requests native identity without treating a declaration location as a complete definition. */
    internal fun symbolInfo(file: Path, line: Int, character: Int): CClangdSymbolInfoResponse {
        fun unavailable(code: String, message: String) = CClangdSymbolInfoResponse.Unavailable(listOf(resourceDiagnostic(code, message)))
        val process = managedProcess ?: return CClangdSymbolInfoResponse.Unavailable(listOf(notRunningDiagnostic()))
        if (!process.isAlive) return CClangdSymbolInfoResponse.Unavailable(listOf(unavailableDiagnostic()))
        configurationChanged()?.let { return CClangdSymbolInfoResponse.Unavailable(listOf(it)) }
        val response = withDeadline(requestTimeoutMillis) {
            send("textDocument/symbolInfo", """{"textDocument":{"uri":${quote(uri(file))}},"position":{"line":$line,"character":$character}}""")
        } ?: return unavailable("clangd.unavailable", "clangd returned no symbol identity response")
        configurationChanged()?.let { return CClangdSymbolInfoResponse.Unavailable(listOf(it)) }
        val message = parseMessage(response)
        if (message.success != true) return unavailable("clangd.refused", "clangd refused symbol identity acquisition")
        val body = message.body ?: return unavailable("clangd.resultInvalid", "clangd returned no symbol identity result")
        return CClangdSymbolInfoResponse.Available(body)
    }

    /** Requests the definition for a symbol at the given position. */
    fun definition(file: Path, line: Int, character: Int): CClangdSemanticResult {
        val process = managedProcess ?: return refusal("clangd.notRunning", "clangd is not running")
        if (!process.isAlive) return refusal("clangd.unavailable", "clangd is unavailable")
        configurationChanged()?.let { return CClangdSemanticResult.Refused(listOf(it)) }
        val response = withDeadline(requestTimeoutMillis) {
            send("textDocument/definition", """{"textDocument":{"uri":${quote(uri(file))}},"position":{"line":$line,"character":$character}}""")
        } ?: return refusal("clangd.unavailable", "clangd returned no definition response")
        configurationChanged()?.let { return CClangdSemanticResult.Refused(listOf(it)) }
        val message = parseMessage(response)
        if (message.success != true) return refusal("clangd.refused", message.error ?: "clangd refused definition")
        val body = message.body ?: return refusal("clangd.resultInvalid", "clangd returned no definition result")
        val result = runCatching { Json.parseToJsonElement(body) }.getOrNull()
        if (result == JsonNull || (result is JsonArray && result.isEmpty())) {
            return CClangdSemanticResult.NotFound(listOf(notFoundDiagnostic()))
        }
        if (result is JsonArray && result.size > 1) {
            return refusal("clangd.definitionAmbiguous", "clangd returned multiple definition locations; explicit selection is required")
        }
        val location = parseLocation(body)
            ?: return refusal("clangd.resultInvalid", "clangd returned malformed or unsupported definition evidence")
        return CClangdSemanticResult.Found(CSymbolDefinition(location.file, location.startLine, location.startCharacter, location.endLine, location.endCharacter, location.name))
    }

    /** Requests a semantic rename; returns the exact binding-matched edits. */
    fun rename(file: Path, line: Int, character: Int, newName: String): CRenameResult {
        val process = managedProcess ?: return CRenameResult.Refused(listOf(notRunningDiagnostic()))
        if (!process.isAlive) return CRenameResult.Refused(listOf(unavailableDiagnostic()))
        configurationChanged()?.let { return CRenameResult.Refused(listOf(it)) }
        if (newName.isBlank() || newName.length > MAX_RENAME_NAME) return CRenameResult.Refused(listOf(
            Diagnostic(message = "Rename target name is invalid", severity = Diagnostic.Severity.ERROR, code = "clang.renameInvalidName", evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.TYPE_RESOLUTION),
        ))
        val sdkContext = launchContext?.takeIf { it.readOnlySdkProvenance().isNotEmpty() }
        if (sdkContext != null) {
            if (sdkContext.sdkRenameDecision(file, null) == CReadOnlySdkRename.READ_ONLY)
                return sdkRenameRefusal(CReadOnlySdkRename.READ_ONLY)
            val ownership = when (val target = definition(file, line, character)) {
                is CClangdSemanticResult.Found -> sdkContext.sdkRenameDecision(file, target.definition.file)
                is CClangdSemanticResult.Refused -> return CRenameResult.Refused(target.diagnostics)
                is CClangdSemanticResult.NotFound -> CReadOnlySdkRename.UNAVAILABLE
            }
            if (ownership != CReadOnlySdkRename.SOURCE_OWNED) return sdkRenameRefusal(ownership)
        }
        val response = withDeadline(requestTimeoutMillis) {
            send("textDocument/rename", """{"textDocument":{"uri":${quote(uri(file))}},"position":{"line":$line,"character":$character},"newName":${quote(newName)}}""")
        } ?: return CRenameResult.Refused(listOf(unavailableDiagnostic()))
        configurationChanged()?.let { return CRenameResult.Refused(listOf(it)) }
        val message = parseMessage(response)
        if (message.success != true) return CRenameResult.Refused(listOf(
            Diagnostic(message = message.error ?: "clangd refused rename", severity = Diagnostic.Severity.ERROR, code = "clangd.refused", evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
        ))
        val body = message.body ?: return CRenameResult.NotFound(listOf(notFoundDiagnostic()))
        val edits = parseRenameEdits(body)
        if (edits == null) return CRenameResult.Refused(listOf(
            Diagnostic(message = "clangd returned a malformed rename result", severity = Diagnostic.Severity.ERROR, code = "clangd.resultInvalid", evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
        ))
        if (edits.isEmpty()) return CRenameResult.NotFound(listOf(notFoundDiagnostic()))
        if (sdkContext != null) {
            val ownership = sdkContext.sdkEditDecision(edits.map { it.file })
            if (ownership != CReadOnlySdkRename.SOURCE_OWNED) return sdkRenameRefusal(ownership)
        }
        return CRenameResult.Found(edits)
    }

    /** Requests all references for a symbol at the given position. */
    fun references(file: Path, line: Int, character: Int): CReferenceResult {
        val process = managedProcess ?: return CReferenceResult.Unavailable(listOf(notRunningDiagnostic()))
        if (!process.isAlive) return CReferenceResult.Unavailable(listOf(unavailableDiagnostic()))
        configurationChanged()?.let { return CReferenceResult.Unavailable(listOf(it)) }
        val response = withDeadline(requestTimeoutMillis) {
            send("textDocument/references", """{"textDocument":{"uri":${quote(uri(file))}},"position":{"line":$line,"character":$character},"context":{"includeDeclaration":true}}""")
        } ?: return CReferenceResult.Unavailable(listOf(unavailableDiagnostic()))
        configurationChanged()?.let { return CReferenceResult.Unavailable(listOf(it)) }
        val message = parseMessage(response)
        if (message.success != true) return CReferenceResult.Unavailable(listOf(
            Diagnostic(message = message.error ?: "clangd refused references", severity = Diagnostic.Severity.ERROR, code = "clangd.refused", evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
        ))
        val body = message.body ?: return CReferenceResult.Unavailable(listOf(invalidResultDiagnostic()))
        // LSP permits null to mean no references. All other results must be a
        // complete array; malformed entries or an over-limit array are unavailable,
        // never evidence that the symbol has no uses.
        if (body == "null") return CReferenceResult.NotFound(listOf(notFoundDiagnostic()))
        val locations = parseLocations(body) ?: return CReferenceResult.Unavailable(listOf(invalidResultDiagnostic()))
        return if (locations.isEmpty()) CReferenceResult.NotFound(listOf(notFoundDiagnostic()))
        else CReferenceResult.Found(locations)
    }

    private fun sdkRenameRefusal(ownership: CReadOnlySdkRename): CRenameResult.Refused = CRenameResult.Refused(listOf(
        resourceDiagnostic(if (ownership == CReadOnlySdkRename.READ_ONLY) "c.sdkReadOnly" else "c.sdkRenameContextUnavailable",
            if (ownership == CReadOnlySdkRename.READ_ONLY) "SDK declarations and files are read only" else "Complete source-owned rename evidence is unavailable"),
    ))

    private fun configurationChanged(): Diagnostic? {
        val context = launchContext ?: return null
        runtimeAuthorityDiagnostic()?.let {
            close()
            return it
        }
        if (!resourcesAreCurrent()) {
            close()
            return resourceDiagnostic("c.clangdResourcesChanged",
                "Retained Clang resources changed or are unavailable; start a fresh semantic session")
        }
        if (context.isCurrent()) return null
        close()
        return Diagnostic(
            message = "Captured compilation configuration changed or is unavailable; start a fresh semantic session",
            severity = Diagnostic.Severity.ERROR, code = "c.clangdConfigurationChanged",
            evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.SAFETY,
        )
    }

    private fun runtimeAuthorityDiagnostic(): Diagnostic? {
        if (toolchain.provenance.readOnlySdkIdentity != toolchain.readOnlySdkInputs.identity)
            return resourceDiagnostic("c.clangdSdkUnavailable", "Declared SDK identity does not match retained SDK inputs")
        if (!toolchain.readOnlySdkInputs.isCurrent())
            return resourceDiagnostic("c.clangdSdkChanged", "Retained SDK inputs changed; discover a fresh toolchain")
        return when (runtimeProvider.checkCurrent(toolchain)) {
            ClangToolchainRuntimeCurrency.Current -> null
            is ClangToolchainRuntimeCurrency.NotCurrent -> {
                val code = if (toolchain.runtime !is ClangToolchainRuntimeCaptureResult.Captured) {
                    "c.clangdRuntimeUnavailable"
                } else {
                    "c.clangdRuntimeChanged"
                }
                resourceDiagnostic(code,
                    "Retained native runtime inputs are unavailable, changed, or no longer match the selected toolchain")
            }
        }
    }

    private fun resourcesAreCurrent(): Boolean {
        val resources = toolchain.resources as? ClangResourceCaptureResult.Captured ?: return false
        val declared = runCatching { Path.of(toolchain.provenance.resourceDir ?: return false) }.getOrNull() ?: return false
        return declared.isAbsolute && declared.normalize() == resources.snapshot.root &&
            ClangResourceDirectoryCapture().checkCurrent(resources.snapshot) == ClangResourceCurrency.Current
    }

    private fun resourceDiagnostic(code: String, message: String) = Diagnostic(message,
        Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.SAFETY)

    override fun close() {
        val resources = synchronized(lifecycleLock) {
            activeStart?.cancelled = true
            val process = managedProcess
            val executor = requestExecutor
            val context = launchContext
            managedProcess = null
            requestExecutor = null
            writer = null
            reader = null
            launchContext = null
            Triple(process, executor, context)
        }
        resources.first?.close()
        resources.second?.shutdownNow()
        resources.third?.close()
    }

    private fun send(method: String, paramsJson: String): String? {
        val id = nextId.getAndIncrement()
        val body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"id\":").append(id)
            append(",\"method\":").append(quote(method))
            append(",\"params\":").append(paramsJson)
            append('}')
        }
        writeFrame(body)
        return readResponse(id)
    }

    /** Sends a notification (no id, no response expected). Returns true when the frame was written. */
    private fun notify(method: String, paramsJson: String): Boolean {
        val output = writer ?: return false
        val body = buildString {
            append("{\"jsonrpc\":\"2.0\",\"method\":").append(quote(method))
            append(",\"params\":").append(paramsJson)
            append('}')
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) { close(); return false }
        synchronized(output) {
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
        return true
    }

    private fun writeFrame(body: String) {
        val output = writer ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) { close(); return }
        synchronized(output) {
            output.write("Content-Length: ${bytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    private fun readResponse(expectedId: Int): String? {
        repeat(MAX_SKIP_FRAMES) {
            val json = readSingleFrame() ?: return null
            val message = parseMessage(json)
            if (message.id == expectedId) return json
        }
        return null
    }

    private fun <T> withDeadline(timeoutMillis: Long, exchange: () -> T): T? {
        val pending = requestExecutor?.submit<T> { exchange() } ?: return null
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true); close(); null
        } catch (_: ExecutionException) {
            close(); null
        } catch (_: InterruptedException) {
            pending.cancel(true); close(); Thread.currentThread().interrupt(); null
        }
    }

    private fun readSingleFrame(): String? {
        val input = reader ?: return null
        var contentLength: Int? = null
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - headerBytes) ?: return null
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw IllegalStateException("clangd headers exceed $MAX_HEADER_BYTES bytes")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                if (contentLength != null) throw IllegalStateException("duplicate clangd Content-Length header")
                contentLength = line.substringAfter(':').trim().toIntOrNull()
                    ?: throw IllegalStateException("invalid clangd Content-Length header")
            }
        }
        val length = contentLength ?: throw IllegalStateException("missing clangd Content-Length header")
        if (length !in 0..MAX_FRAME_BYTES) throw IllegalStateException("clangd frame exceeds $MAX_FRAME_BYTES bytes")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IllegalStateException("truncated clangd frame")
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readAsciiLine(input: InputStream, remaining: Int): String? {
        if (remaining <= 0) throw IllegalStateException("clangd headers exceed $MAX_HEADER_BYTES bytes")
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < remaining) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IllegalStateException("truncated clangd header")
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val size = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, size, Charsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IllegalStateException("clangd headers exceed $MAX_HEADER_BYTES bytes")
    }

    private data class Message(
        val id: Int?,
        val success: Boolean?,
        val error: String?,
        val body: String?,
    )

    private fun parseMessage(json: String): Message {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (root == null) return Message(null, null, null, null)
        return Message(
            id = (root["id"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull(),
            // Missing result, explicit error and malformed result types never count
            // as successful JSON-RPC, even when no result member was supplied.
            success = !root.containsKey("error") &&
                (root["result"] is JsonObject || root["result"] is JsonArray || root["result"] == JsonNull),
            error = root["error"]?.toString(),
            body = root["result"]?.toString(),
        )
    }

    internal data class Location(val file: Path, val startLine: Int, val startCharacter: Int, val endLine: Int, val endCharacter: Int, val name: String?)

    internal fun parseLocation(body: String): Location? {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val objectValue = when (element) {
            is JsonObject -> element
            is JsonArray -> element.singleOrNull() as? JsonObject
            else -> null
        } ?: return null
        val uri = objectValue.string("uri") ?: return null
        val file = uriToPath(uri) ?: return null
        val range = objectValue["range"] as? JsonObject ?: return null
        val start = range["start"] as? JsonObject ?: return null
        val end = range["end"] as? JsonObject ?: return null
        val startLine = start.integer("line") ?: return null
        val startCharacter = start.integer("character") ?: return null
        val endLine = end.integer("line") ?: return null
        val endCharacter = end.integer("character") ?: return null
        if (startLine < 0 || startCharacter < 0 || endLine < 0 || endCharacter < 0) return null
        if (endLine < startLine || (endLine == startLine && endCharacter < startCharacter)) return null
        return Location(file, startLine, startCharacter, endLine, endCharacter, objectValue.string("name"))
    }

    internal fun parseLocations(body: String): List<CSymbolReference>? {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val array = element as? JsonArray ?: return null
        if (array.size > MAX_REFERENCES) return null
        return array.map { entry ->
            val objectValue = entry as? JsonObject ?: return null
            val uri = objectValue.string("uri") ?: return null
            val file = runCatching { Path.of(URI(uri)).normalize() }.getOrNull()
                ?.takeIf(Path::isAbsolute) ?: return null
            val range = objectValue["range"] as? JsonObject ?: return null
            val start = range["start"] as? JsonObject ?: return null
            val end = range["end"] as? JsonObject ?: return null
            val startLine = start.integer("line") ?: return null
            val startCharacter = start.integer("character") ?: return null
            val endLine = end.integer("line") ?: return null
            val endCharacter = end.integer("character") ?: return null
            if (startLine < 0 || startCharacter < 0 || endLine < 0 || endCharacter < 0) return null
            if (endLine < startLine || (endLine == startLine && endCharacter <= startCharacter)) return null
            CSymbolReference(file, startLine, startCharacter, endLine, endCharacter)
        }
    }

    private fun invalidResultDiagnostic() = Diagnostic(
        message = "clangd reference result is incomplete or malformed",
        severity = Diagnostic.Severity.ERROR,
        code = "clangd.resultInvalid",
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun refusal(code: String, message: String) = CClangdSemanticResult.Refused(listOf(
        Diagnostic(message = message, severity = Diagnostic.Severity.ERROR, code = code, evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.TYPE_RESOLUTION),
    ))

    private fun notRunningDiagnostic() = Diagnostic(
        message = "clangd is not running", severity = Diagnostic.Severity.ERROR, code = "clangd.notRunning",
        evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun unavailableDiagnostic() = Diagnostic(
        message = "clangd is unavailable", severity = Diagnostic.Severity.ERROR, code = "clangd.unavailable",
        evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun notFoundDiagnostic() = Diagnostic(
        message = "No definition found for the requested symbol",
        severity = Diagnostic.Severity.INFO,
        code = "clangd.definitionNotFound",
        evidence = DiagnosticEvidence.STRUCTURAL,
        category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun parseRenameEdits(body: String): List<CRenameEdit>? {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrNull() ?: return null
        val objectValue = element as? JsonObject ?: return null
        val changes = objectValue["changes"] as? JsonObject ?: return null
        val edits = mutableListOf<CRenameEdit>()
        for ((uri, value) in changes) {
            val file = uriToPath(uri) ?: return null
            val array = value as? JsonArray ?: return null
            for (entry in array) {
                val edit = entry as? JsonObject ?: return null
                val range = edit["range"] as? JsonObject ?: return null
                val start = range["start"] as? JsonObject ?: return null
                val end = range["end"] as? JsonObject ?: return null
                val startLine = start.integer("line") ?: return null
                val startCharacter = start.integer("character") ?: return null
                val endLine = end.integer("line") ?: return null
                val endCharacter = end.integer("character") ?: return null
                val newText = edit.string("newText") ?: return null
                if (startLine < 0 || startCharacter < 0 || endLine < 0 || endCharacter < 0) return null
                if (endLine < startLine || (endLine == startLine && endCharacter < startCharacter)) return null
                edits += CRenameEdit(file, startLine, startCharacter, endLine, endCharacter, newText)
                if (edits.size > MAX_RENAME_EDITS) return null
            }
        }
        return edits
    }

    private fun uri(path: Path): String {
        val root = workspaceRoot ?: path.toAbsolutePath().normalize()
        val resolved = if (path.isAbsolute) path.toAbsolutePath().normalize() else root.resolve(path).normalize()
        return resolved.toUri().toASCIIString()
    }
    private fun uriToPath(uri: String): Path? = runCatching {
        Path.of(URI(uri)).normalize().takeIf(Path::isAbsolute)
    }.getOrNull()
    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.contentOrNull?.toIntOrNull()

    companion object {
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = true }
        private val PROCESS_SEQUENCE = AtomicInteger(1)
        private const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 10_000L
        private const val DEFAULT_INITIALIZE_TIMEOUT_MILLIS = 20_000L
        private const val MAX_FRAME_BYTES = 16L * 1024L * 1024L
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_SESSION_OUTPUT_BYTES = 512L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 4 * 1024 * 1024
        private const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
        private const val MAX_SKIP_FRAMES = 10_000
        private const val MAX_REFERENCES = 256
        private const val MAX_RENAME_EDITS = 512
        private const val MAX_RENAME_NAME = 128
    }
}

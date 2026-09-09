package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.ManagedSemanticProcess
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * A raw TypeScript compiler-server client for the tsserver-native
 * `getEditsForFileRename` command.
 *
 * The `typescript-language-server` LSP wrapper does not expose
 * `getEditsForFileRename`, so RefactorKit talks to the pinned tsserver
 * directly using the tsserver protocol: requests are newline-delimited JSON
 * (`{seq, type: "request", command, arguments}`) and responses are
 * Content-Length framed (`Content-Length: N\r\n\r\n` + JSON + newline).
 *
 * The client opens every recognized project source file so the compiler
 * acknowledges the open requests before `getEditsForFileRename(oldFilePath,
 * newFilePath)`. Both startup and rename exchanges are deadline-bound. It parses the returned
 * `FileTextChanges[]` into a normalized, unapproved edit proposal.
 */
class TypeScriptCompilerServerClient(
    private val toolchain: TypeScriptSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val requestTimeoutMillis: Long = TypeScriptSemanticAdapter.SYMBOL_AGGREGATE_TIMEOUT_MILLIS,
    private val projectLoadTimeoutMillis: Long = TypeScriptSemanticAdapter.SYMBOL_AGGREGATE_TIMEOUT_MILLIS,
) : AutoCloseable {
    private var managedProcess: ManagedSemanticProcess? = null
    private var writer: OutputStream? = null
    private var reader: InputStream? = null
    private var requestExecutor: ExecutorService? = null
    private val nextSeq = AtomicInteger(1)
    private var returnedActionHash: String? = null
    fun returnedActionSha256(): String? = returnedActionHash

    init {
        require(requestTimeoutMillis in 1..300_000L) { "request timeout is outside the safe range" }
        require(projectLoadTimeoutMillis in 1..300_000L) { "project-load timeout is outside the safe range" }
    }

    fun processProvenance(): org.refactorkit.core.SemanticProcessProvenance? = managedProcess?.provenance

    /** Spawns the pinned tsserver and opens every recognized project source file. */
    fun start(snapshot: ProjectSnapshot) {
        require(managedProcess == null) { "TypeScript compiler server is already running" }
        val node = toolchain.nodeExecutable.toAbsolutePath().normalize()
        val tsserver = toolchain.typeScriptServerEntrypoint.toAbsolutePath().normalize()
        val process = processManager.launch(SemanticProcessSpec(
            id = "ts-compiler-server-${PROCESS_SEQUENCE.getAndIncrement()}",
            executable = node,
            arguments = listOf(tsserver.toString(), "--stdio", "--disableAutomaticTypingAcquisition"),
            workingDirectory = snapshot.workspace.root.toAbsolutePath().normalize(),
            limits = SemanticProcessLimits(
                maxStdoutBytes = MAX_SESSION_OUTPUT_BYTES,
                maxStderrBytes = MAX_STDERR_BYTES,
                gracefulShutdownMillis = SHUTDOWN_TIMEOUT_MILLIS,
            ),
        ))
        managedProcess = process
        writer = process.input
        reader = process.output
        requestExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "refactorkit-ts-compiler-${process.provenance.id}").apply { isDaemon = true }
        }
        val opened = withDeadline(projectLoadTimeoutMillis) {
            snapshot.files
                .filter { it.languageId in setOf("typescript", "javascript") }
                .sortedBy { it.path.toString() }
                .all { source ->
                    val response = send("open", """{"file":${quote(absolutePath(snapshot, source.path))},"fileContent":${quote(source.content)}}""")
                    response != null && parseMessage(response).success == true
                }
        }
        if (opened != true) {
            close()
            error("TypeScript compiler server did not finish opening the project")
        }
    }

    /**
     * Sends the real `getEditsForFileRename` command with the exact old and new
     * file paths to the pinned tsserver and normalizes the returned
     * `FileTextChanges[]` into an unapproved proposal.
     */
    fun getEditsForFileRename(
        oldFilePath: Path,
        newFilePath: Path,
        snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer,
        projectDirectory: Boolean = false,
    ): ExternalWorkspaceEditNormalization {
        val process = managedProcess ?: return refusal(
            "typescript.compilerServerNotRunning", "TypeScript compiler server is not running",
        )
        if (!process.isAlive) return refusal(
            "typescript.compilerServerUnavailable", "TypeScript compiler server is unavailable",
        )
        val oldPath = absolutePath(snapshot, oldFilePath)
        val newPath = absolutePath(snapshot, newFilePath)
        val params = buildString {
            append("{\"oldFilePath\":").append(quote(oldPath))
            append(",\"newFilePath\":").append(quote(newPath))
            append('}')
        }
        val response = withDeadline(requestTimeoutMillis) {
            send("getEditsForFileRename", params)
        } ?: return refusal(
            "typescript.compilerServerUnavailable",
            "TypeScript compiler server returned no getEditsForFileRename response",
        )
        val message = parseMessage(response)
        if (message.success == false) return refusal(
            "typescript.compilerServerRefused",
            message.error ?: "TypeScript compiler server refused getEditsForFileRename",
        )
        if (message.success != true) return invalidResult()
        val body = message.body ?: return invalidResult()
        val fileChanges = parseFileRenameEdits(body) ?: return invalidResult()
        val proposal = buildProposal(snapshot, oldFilePath, newFilePath, fileChanges, projectDirectory)
        return normalizer.normalize(snapshot, proposal)
    }

    fun organizeImports(
        file: Path,
        mode: TypeScriptOrganizeImportsMode,
        formatting: TypeScriptCompilerFormatting,
        snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer,
    ): ExternalWorkspaceEditNormalization {
        if (managedProcess?.isAlive != true) return refusal("typescript.compilerServerNotRunning", "TypeScript compiler server is not running")
        if (!configure(formatting)) return invalidResult()
        val response = withDeadline(requestTimeoutMillis) {
            send("organizeImports", """{"scope":{"type":"file","args":{"file":${quote(absolutePath(snapshot, file))}}},"mode":${quote(mode.protocolName)}}""")
        } ?: return refusal("typescript.compilerServerUnavailable", "No organizeImports response")
        val message = parseMessage(response)
        if (message.success != true) return invalidResult()
        val changes = message.body?.let(::parseFileRenameEdits) ?: return invalidResult()
        return normalizer.normalize(snapshot, ExternalWorkspaceEditProposal(
            providerId = "ts-compiler-server", providerVersion = toolchain.provenance.typeScriptVersion,
            edits = modificationProposals(changes),
        ))
    }

    fun getEditsForRefactor(
        request: TypeScriptCompilerRefactorRequest, snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer,
    ): ExternalWorkspaceEditNormalization {
        returnedActionHash = null
        if (managedProcess?.isAlive != true) return refusal("typescript.compilerServerNotRunning", "TypeScript compiler server is not running")
        if (!configure(request.formatting, request.compilerPreferences)) return invalidResult()
        val range = request.range
        val coordinates = """"file":${quote(absolutePath(snapshot, request.file))},"startLine":${range.start.line + 1},"startOffset":${range.start.character + 1},"endLine":${range.end.line + 1},"endOffset":${range.end.character + 1}"""
        val discovery = withDeadline(requestTimeoutMillis) {
            send("getApplicableRefactors", """{$coordinates,"triggerReason":"invoked","kind":${quote(request.kind.protocolKind)},"includeInteractiveActions":${request.targetFile != null}}""")
        } ?: return refusal("typescript.compilerServerUnavailable", "No getApplicableRefactors response")
        val message = parseMessage(discovery)
        if (message.success != true) return invalidResult()
        val actions = message.body?.let(::parseRefactorActions) ?: return invalidResult()
        val matches = actions.filter { (name, action) ->
            val interactive = (action["isInteractive"] as? JsonPrimitive)?.booleanOrNull
            // The pinned 5.9.3 Move to file implementation omits isInteractive.
            val interactiveMatches = if (request.targetFile == null) interactive != true else
                interactive == true || (interactive == null && toolchain.provenance.typeScriptVersion == "5.9.3" &&
                    request.kind == TypeScriptCompilerRefactorKind.MOVE_DECLARATION)
            name == request.refactor && action.string("name") == request.action && action.string("kind") == request.kind.protocolKind &&
                action["notApplicableReason"] == null && interactiveMatches
        }
        if (matches.size != 1) return refusal("typescript.refactorActionUnavailable", "Requested exact compiler refactor action is unavailable or ambiguous")
        val selected = JsonObject(mapOf("refactor" to JsonPrimitive(matches.single().first), "action" to matches.single().second)).toString()
        returnedActionHash = java.security.MessageDigest.getInstance("SHA-256").digest(selected.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val interactive = request.targetFile?.let {
            """, "interactiveRefactorArguments":{"targetFile":${quote(absolutePath(snapshot, it))}}"""
        }.orEmpty()
        val response = withDeadline(requestTimeoutMillis) {
            send("getEditsForRefactor", """{$coordinates,"refactor":${quote(request.refactor)},"action":${quote(request.action)}$interactive}""")
        } ?: return refusal("typescript.compilerServerUnavailable", "No getEditsForRefactor response")
        val editsMessage = parseMessage(response)
        if (editsMessage.success != true) return refusal("typescript.refactorActionRefused", "Compiler refused the selected action")
        val body = runCatching { JSON.parseToJsonElement(editsMessage.body ?: "null") as? JsonObject }.getOrNull() ?: return invalidResult()
        if (body["notApplicableReason"] != null || body["commands"] != null) return refusal(
            "typescript.refactorAdditionalAuthorityRequired", "Refactor is not applicable or requires an external command",
        )
        val changes = body["edits"]?.toString()?.let(::parseFileRenameEdits) ?: return invalidResult()
        return normalizer.normalize(snapshot, ExternalWorkspaceEditProposal(
            providerId = "ts-compiler-server", providerVersion = toolchain.provenance.typeScriptVersion,
            edits = modificationProposals(changes),
        ))
    }

    private fun configure(formatting: TypeScriptCompilerFormatting, preferences: String = formatting.preferences): Boolean {
        val response = withDeadline(requestTimeoutMillis) {
            send("configure", """{"formatOptions":${formatting.formatOptions},"preferences":$preferences}""")
        }
        return response != null && parseMessage(response).success == true
    }

    private fun parseRefactorActions(body: String): List<Pair<String, JsonObject>>? {
        val groups = runCatching { JSON.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        if (groups.size > 64) return null
        val result = mutableListOf<Pair<String, JsonObject>>()
        for (entry in groups) {
            val group = entry as? JsonObject ?: return null
            val name = group.string("name")?.takeIf { it.isNotBlank() } ?: return null
            group.string("description") ?: return null
            val actions = group["actions"] as? JsonArray ?: return null
            for (item in actions) {
                val action = item as? JsonObject ?: return null
                action.string("name")?.takeIf { it.isNotBlank() } ?: return null
                action.string("description") ?: return null
                action.string("kind") ?: return null
                if ("isInteractive" in action && (action["isInteractive"] as? JsonPrimitive)
                        ?.takeUnless { it.isString }?.booleanOrNull == null) return null
                if ("notApplicableReason" in action && action.string("notApplicableReason") == null) return null
                result += name to action
                if (result.size > 256) return null
            }
        }
        return result
    }

    override fun close() {
        // Interrupt alone cannot unblock a pipe read; terminate the owned process first.
        managedProcess?.close()
        requestExecutor?.shutdownNow()
        requestExecutor = null
        managedProcess = null
        writer = null
        reader = null
    }

    // ── protocol ──────────────────────────────────────────────────────────────

    private fun send(command: String, argumentsJson: String): String? {
        val seq = nextSeq.getAndIncrement()
        val body = buildString {
            append("{\"seq\":").append(seq)
            append(",\"type\":\"request\"")
            append(",\"command\":").append(quote(command))
            append(",\"arguments\":").append(argumentsJson)
            append('}')
        }
        writeNewline(body)
        return readResponse(seq)
    }

    private fun writeNewline(body: String) {
        val output = writer ?: return
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) {
            failAndStop("semantic.frameTooLarge", "outbound tsserver frame exceeds $MAX_FRAME_BYTES bytes")
            return
        }
        synchronized(output) {
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
        }
    }

    /**
     * Reads Content-Length framed tsserver messages until one matches the
     * requested [seq]. Events are consumed but not used as a readiness barrier:
     * projectLoadingFinish may precede the open response or be absent for inferred projects.
     * Gives up after [MAX_SKIP_FRAMES] frames; the enclosing deadline bounds all I/O.
     */
    private fun readResponse(expectedSeq: Int): String? {
        repeat(MAX_SKIP_FRAMES) {
            val json = readSingleFrame() ?: return null
            val message = parseMessage(json)
            if (message.type == "response" && message.requestSeq == expectedSeq) return json
        }
        return null
    }

    /** One aggregate deadline covers writes, skipped events, headers and partial bodies. */
    private fun <T> withDeadline(timeoutMillis: Long, exchange: () -> T): T? {
        val pending = requestExecutor?.submit<T> { exchange() } ?: return null
        return try {
            pending.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            pending.cancel(true)
            close()
            null
        } catch (_: ExecutionException) {
            close()
            null
        } catch (_: InterruptedException) {
            pending.cancel(true)
            close()
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun readSingleFrame(): String? {
        val input = reader ?: return null
        var contentLength: Int? = null
        var headerBytes = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_BYTES - headerBytes) ?: return null
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADER_BYTES) throw IllegalStateException("tsserver headers exceed $MAX_HEADER_BYTES bytes")
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                if (contentLength != null) throw IllegalStateException("duplicate tsserver Content-Length header")
                contentLength = line.substringAfter(':').trim().toIntOrNull()
                    ?: throw IllegalStateException("invalid tsserver Content-Length header")
            }
        }
        val length = contentLength ?: throw IllegalStateException("missing tsserver Content-Length header")
        if (length !in 0..MAX_FRAME_BYTES) throw IllegalStateException("tsserver frame exceeds $MAX_FRAME_BYTES bytes")
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw IllegalStateException("truncated tsserver frame")
            offset += count
        }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun readAsciiLine(input: InputStream, remaining: Int): String? {
        if (remaining <= 0) throw IllegalStateException("tsserver headers exceed $MAX_HEADER_BYTES bytes")
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < remaining) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else throw IllegalStateException("truncated tsserver header")
            if (value == '\n'.code) {
                val raw = bytes.toByteArray()
                val size = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                return String(raw, 0, size, Charsets.US_ASCII)
            }
            bytes.write(value)
        }
        throw IllegalStateException("tsserver headers exceed $MAX_HEADER_BYTES bytes")
    }

    private fun failAndStop(code: String, message: String) {
        close()
    }

    // ── response parsing ──────────────────────────────────────────────────────

    private data class Message(
        val type: String?,
        val requestSeq: Int?,
        val success: Boolean?,
        val error: String?,
        val event: String?,
        val body: String?,
    )

    private fun parseMessage(json: String): Message {
        val root = runCatching { JSON.parseToJsonElement(json) as? JsonObject }.getOrNull()
        if (root == null) return Message(null, null, null, null, null, null)
        return Message(
            type = root.string("type"),
            requestSeq = root.integer("request_seq"),
            success = (root["success"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull,
            error = root.string("message"),
            event = root.string("event"),
            body = root["body"]?.toString(),
        )
    }

    private data class CompilerTextEdit(
        val startLine: Int,
        val startOffset: Int,
        val endLine: Int,
        val endOffset: Int,
        val newText: String,
    )

    private data class FileRenameChanges(
        val fileName: String,
        val textChanges: List<CompilerTextEdit>,
    )

    private fun parseFileRenameEdits(body: String): List<FileRenameChanges>? {
        val array = runCatching { JSON.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        // A malformed entry invalidates the entire result; never normalize a partial edit set.
        return array.map { element ->
            val file = element as? JsonObject ?: return null
            val fileName = file.string("fileName") ?: return null
            if (fileName.isBlank() || runCatching { Path.of(fileName) }.isFailure) return null
            val changes = file["textChanges"] as? JsonArray ?: return null
            val textChanges = changes.map { change ->
                val textChange = change as? JsonObject ?: return null
                val start = textChange["start"] as? JsonObject ?: return null
                val end = textChange["end"] as? JsonObject ?: return null
                val startLine = start.integer("line") ?: return null
                val startOffset = start.integer("offset") ?: return null
                val endLine = end.integer("line") ?: return null
                val endOffset = end.integer("offset") ?: return null
                if (startLine < 1 || startOffset < 1 || endLine < 1 || endOffset < 1) return null
                if (endLine < startLine || (endLine == startLine && endOffset < startOffset)) return null
                val newText = textChange.string("newText") ?: return null
                CompilerTextEdit(startLine, startOffset, endLine, endOffset, newText)
            }
            FileRenameChanges(fileName, textChanges)
        }
    }

    private fun buildProposal(
        snapshot: ProjectSnapshot,
        oldFilePath: Path,
        newFilePath: Path,
        fileChanges: List<FileRenameChanges>,
        projectDirectory: Boolean,
    ): ExternalWorkspaceEditProposal {
        val oldPath = Path.of(absolutePath(snapshot, oldFilePath)).normalize()
        val newPath = Path.of(absolutePath(snapshot, newFilePath)).normalize()
        val renames = if (projectDirectory) {
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            snapshot.trackedFiles.filter { root.resolve(it.path).startsWith(oldPath) }.map {
                val source = root.resolve(it.path)
                ExternalFileEditProposal.Rename(source, newPath.resolve(oldPath.relativize(source)))
            }
        } else listOf(ExternalFileEditProposal.Rename(oldPath, newPath))
        // Compiler coordinates refer to the original sources, including the files being moved.
        return ExternalWorkspaceEditProposal(
            providerId = "ts-compiler-server",
            providerVersion = toolchain.provenance.typeScriptVersion,
            edits = modificationProposals(fileChanges) + renames,
        )
    }

    private fun modificationProposals(changes: List<FileRenameChanges>): List<ExternalFileEditProposal> = changes.map { change ->
        ExternalFileEditProposal.Modify(Path.of(change.fileName).normalize(), change.textChanges.map { text ->
            TextEdit(SourceRange(
                SourcePosition(text.startLine - 1, text.startOffset - 1),
                SourcePosition(text.endLine - 1, text.endOffset - 1),
            ), text.newText)
        })
    }

    private fun invalidResult(): ExternalWorkspaceEditNormalization.Refused {
        close()
        return refusal(
            "typescript.compilerServerResultInvalid",
            "TypeScript compiler server returned a malformed edit result",
        )
    }

    private fun refusal(code: String, message: String) = ExternalWorkspaceEditNormalization.Refused(listOf(
        Diagnostic(message = message, severity = Diagnostic.Severity.ERROR, code = code),
    ))

    private fun absolutePath(snapshot: ProjectSnapshot, path: Path): String {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val absolute = if (path.isAbsolute) path.toAbsolutePath().normalize() else root.resolve(path).normalize()
        return absolute.toString().replace('\\', '/')
    }

    private fun quote(value: String): String = JsonPrimitive(value).toString()

    private fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.intOrNull

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.contentOrNull

    companion object {
        private val JSON = Json { isLenient = false; ignoreUnknownKeys = true }
        private val PROCESS_SEQUENCE = AtomicInteger(1)
        private const val MAX_FRAME_BYTES = 16L * 1024L * 1024L
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_SESSION_OUTPUT_BYTES = 512L * 1024L * 1024L
        private const val MAX_STDERR_BYTES = 4 * 1024 * 1024
        private const val SHUTDOWN_TIMEOUT_MILLIS = 2_000L
        private const val MAX_SKIP_FRAMES = 10_000
    }
}

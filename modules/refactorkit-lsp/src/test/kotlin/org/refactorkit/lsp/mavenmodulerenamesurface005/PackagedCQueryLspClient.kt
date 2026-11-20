package org.refactorkit.lsp.mavenmodulerenamesurface005

import kotlinx.serialization.json.*
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Bounded real packaged LSP transport; semantic assertions remain in the existing query fixture. */
internal class PackagedCQueryLspClient(private val runtime: Path, workspace: Path) : AutoCloseable {
    private val manager = ExternalSemanticProcessManager()
    private val io = Executors.newSingleThreadExecutor()
    private val evidence = Path.of(assertNotNull(System.getProperty("refactorkit.c.lsp.packaged.evidence")))
        .resolve(UUID.randomUUID().toString())
    private val launcher = runtime.resolve("bin/refactorkit-lsp")
    private val java = runtime.resolve("runtime/bin/java").toRealPath()
    private var sequence = 0
    private var recordSequence = 0
    private val child: org.refactorkit.core.ManagedSemanticProcess

    init {
        require(runtime.isAbsolute && Files.isExecutable(launcher) && Files.isExecutable(java))
        Files.createDirectories(evidence)
        child = manager.launch(SemanticProcessSpec("packaged-c-query-lsp", launcher, emptyList(), workspace,
            environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to workspace.toString()),
            limits = SemanticProcessLimits(maxStdoutBytes = 16L * 1024 * 1024, maxStderrBytes = 64 * 1024)))
        record("launch", buildJsonObject {
            put("mode", "packaged"); put("pid", child.provenance.pid)
            put("executable", child.provenance.executable.toString()); put("executableSha256", child.provenance.executableSha256)
            put("argumentsSha256", child.provenance.argumentsSha256); put("runtime", runtime.toString())
        })
    }

    private fun record(kind: String, value: JsonElement) {
        Files.writeString(evidence.resolve("${++recordSequence}-$kind.json"), value.toString() + "\n")
    }

    private fun <T> bounded(action: () -> T): T {
        val future = io.submit<T> { action() }
        return try { future.get(120, TimeUnit.SECONDS) } catch (failure: Exception) {
            child.close(); future.cancel(true)
            throw AssertionError("Packaged LSP exchange failed: ${child.stderrText()}", failure)
        }
    }

    private fun write(value: JsonObject) {
        val body = value.toString().toByteArray(Charsets.UTF_8)
        require(body.size <= ProtocolLimits.MAX_LSP_FRAME_BYTES)
        child.input.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        child.input.write(body); child.input.flush()
    }

    private fun read(): JsonObject {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            check(header.length < 8192) { "Unbounded LSP response header" }
            val byte = child.output.read(); check(byte >= 0) { "EOF in LSP response header" }; header.append(byte.toChar())
        }
        val lengths = header.lines().filter { it.startsWith("Content-Length:", ignoreCase = true) }
        check(lengths.size == 1)
        val size = lengths.single().substringAfter(':').trim().toInt()
        check(size in 0..ProtocolLimits.MAX_LSP_RESPONSE_BYTES)
        val bytes = ByteArray(size); var offset = 0
        while (offset < size) {
            val count = child.output.read(bytes, offset, size - offset)
            check(count > 0) { "EOF in LSP response body" }; offset += count
        }
        return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    }

    fun dispatch(method: String, params: JsonObject): JsonElement {
        if (method in setOf("initialized", "textDocument/didOpen", "textDocument/didChange", "textDocument/didClose", "exit")) {
            val notification = buildJsonObject { put("jsonrpc", "2.0"); put("method", method); put("params", params) }
            record("notification", notification); bounded { write(notification) }; return JsonNull
        }
        val id = ++sequence
        val request = buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }
        record("request", request)
        val response = bounded {
            write(request)
            var result: JsonObject
            do {
                result = read(); record("response", result)
                if (!result.containsKey("id")) check(result["method"] != null) { "Invalid LSP notification" }
            } while (!result.containsKey("id"))
            result
        }
        assertEquals("2.0", response.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals(id, response.getValue("id").jsonPrimitive.int)
        val actualJava = Path.of(ProcessHandle.of(child.provenance.pid).orElseThrow().info().command().orElseThrow()).toRealPath()
        assertEquals(java, actualJava)
        record("embedded-java", buildJsonObject { put("pid", child.provenance.pid); put("executable", actualJava.toString()) })
        response["error"]?.takeIf { it != JsonNull }?.jsonObject?.let { failure ->
            throw JsonRpcException(failure.getValue("code").jsonPrimitive.int, failure.getValue("message").jsonPrimitive.content, failure["data"])
        }
        if (method == "initialize") dispatch("initialized", buildJsonObject {})
        return response.getValue("result")
    }

    override fun close() {
        try {
            if (child.isAlive) {
                dispatch("shutdown", buildJsonObject {})
                dispatch("exit", buildJsonObject {})
                child.input.close(); assertTrue(child.awaitExit(10_000), "LSP did not exit after shutdown/exit")
            }
            assertEquals(0, child.exitCode, child.stderrText()); assertFalse(child.stderrTruncated())
        } finally {
            child.close(); manager.close(); io.shutdownNow(); assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
            record("closed", buildJsonObject { put("allChildrenExited", !child.isAlive); put("exit", child.exitCode) })
        }
    }
}

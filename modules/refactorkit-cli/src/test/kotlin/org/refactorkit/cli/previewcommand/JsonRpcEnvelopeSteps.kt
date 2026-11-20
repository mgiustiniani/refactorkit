package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class JsonRpcEnvelopeSteps {
    private val fixture = JsonRpcEnvelopeFixture()
    @Given("a JSON RPC envelope producer uses {string}")
    fun capture(producer: String) = fixture.capture(producer)
    @When("its response envelopes cross the selected serialization boundary")
    fun produce() = fixture.produce()
    @Then("every response publishes one outcome with exact payload and correlation")
    fun verify() = fixture.verify()
    @Then("envelope qualification preserves owned inputs and closes its native processes")
    fun intact() = fixture.verifyInputs()
    @After fun close() = fixture.close()
}

/** Scenario-owned original frames and independent envelope expectations. */
@OptIn(ExperimentalSerializationApi::class)
private class JsonRpcEnvelopeFixture : AutoCloseable {
    private data class Expected(val id: JsonElement, val result: JsonElement? = null,
        val code: Int? = null, val message: String? = null, val data: JsonElement? = null)
    private lateinit var producer: String
    private lateinit var owned: Path
    private lateinit var workspace: Path
    private lateinit var baseline: Map<String, String>
    private val observations = mutableListOf<Pair<JsonObject, Expected>>()
    private var process: Process? = null
    private var stdoutReader: Thread? = null
    private var writer: java.io.BufferedWriter? = null
    private val output = LinkedBlockingQueue<String>()
    private var nativeClosed = false
    private var ambiguousRejected = false
    private val payload = buildJsonObject {
        put("opaque", "payload 💡")
        put("nullable", JsonNull)
        put("values", buildJsonArray { add(false); add(0); add("") })
    }

    fun capture(value: String) {
        require(value in setOf("library compact", "library defaults", "library omit nulls",
            "legacy decode", "daemon stdio", "MCP stdio"))
        producer = value
        owned = Files.createTempDirectory("rk-jsonrpc-envelope-")
        workspace = Files.createDirectory(owned.resolve("workspace"))
        Files.writeString(workspace.resolve("owned-input.txt"), "unchanged 💡\n")
        baseline = inventory()
    }

    fun produce() {
        if (producer.endsWith("stdio")) produceNative() else produceLibrary()
    }

    private fun produceLibrary() {
        val json = when (producer) {
            "library compact" -> Json
            "library omit nulls" -> Json { encodeDefaults = true; explicitNulls = false }
            else -> Json { encodeDefaults = true; ignoreUnknownKeys = true }
        }
        val models = listOf(
            successResponse(JsonPrimitive("success-💡"), payload) to Expected(JsonPrimitive("success-💡"), payload),
            successResponse(JsonPrimitive(42), JsonNull) to Expected(JsonPrimitive(42), JsonNull),
            errorResponse(JsonPrimitive("error-💡"), JsonRpcErrorCodes.INVALID_PARAMS, "Typed error", payload) to
                Expected(JsonPrimitive("error-💡"), code = JsonRpcErrorCodes.INVALID_PARAMS, message = "Typed error", data = payload),
            errorResponse(JsonNull, JsonRpcErrorCodes.PARSE_ERROR, "Parse error") to
                Expected(JsonNull, code = JsonRpcErrorCodes.PARSE_ERROR, message = "Parse error"),
            successResponse(JsonPrimitive("false"), JsonPrimitive(false)) to Expected(JsonPrimitive("false"), JsonPrimitive(false)),
            successResponse(JsonPrimitive("zero"), JsonPrimitive(0)) to Expected(JsonPrimitive("zero"), JsonPrimitive(0)),
            successResponse(JsonPrimitive("empty"), JsonPrimitive("")) to Expected(JsonPrimitive("empty"), JsonPrimitive("")),
        )
        for ((model, expected) in models) {
            val value = if (producer == "legacy decode") {
                val legacy = buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", expected.id)
                    put("result", expected.result ?: JsonNull)
                    put("error", if (expected.code == null) JsonNull else buildJsonObject {
                        put("code", expected.code); put("message", requireNotNull(expected.message))
                        put("data", expected.data ?: JsonNull)
                    })
                }
                json.decodeFromJsonElement(JsonRpcResponse.serializer(), legacy)
            } else model
            observations += json.parseToJsonElement(json.encodeToString(JsonRpcResponse.serializer(), value)).jsonObject to expected
        }
        ambiguousRejected = try {
            json.encodeToString(JsonRpcResponse.serializer(),
                JsonRpcResponse(id = JsonPrimitive(1), result = payload,
                    error = JsonRpcError(JsonRpcErrorCodes.INTERNAL_ERROR, "Ambiguous response")))
            false
        } catch (_: SerializationException) { true }
    }

    private fun produceNative() {
        val classpath = requireNotNull(System.getProperty("refactorkit.lsp.test.classpath"))
        val main = if (producer == "daemon stdio") "org.refactorkit.daemon.RefactorKitDaemonKt"
            else "org.refactorkit.mcp.RefactorKitMcpKt"
        val stderr = owned.resolve("native.stderr")
        val active = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "-Xmx512m", "-XX:-UsePerfData", "-cp", classpath, main)
            .directory(workspace.toFile()).redirectError(stderr.toFile()).start().also { process = it }
        writer = active.outputStream.bufferedWriter(Charsets.UTF_8)
        stdoutReader = thread(isDaemon = true, name = "jsonrpc-envelope-stdout") {
            try { active.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { output.put(it) }
            } } finally { output.offer("\u0000EOF") }
        }
        val successMethod = if (producer == "daemon stdio") "server.version" else "ping"
        val expectedBody = if (producer == "daemon stdio") buildJsonObject {
            put("name", RefactorKitVersion.NAME); put("version", RefactorKitVersion.VERSION)
            put("apiVersion", RefactorKitVersion.API_VERSION)
        } else buildJsonObject {}
        request(JsonPrimitive("wire-💡"), successMethod)
        captureReply(Expected(JsonPrimitive("wire-💡"), expectedBody))
        request(JsonPrimitive(41), "refactorkit.__missing_envelope_method")
        captureReply(Expected(JsonPrimitive(41), code = JsonRpcErrorCodes.METHOD_NOT_FOUND))
        write("{\"jsonrpc\":\"2.0\",\"private\":\"do-not-echo-envelope-input\",\"method\":")
        captureReply(Expected(JsonNull, code = JsonRpcErrorCodes.PARSE_ERROR, message = "Parse error"))
        if (producer == "MCP stdio") {
            request(JsonPrimitive("internal"), "ping", buildJsonArray { add("opaque-input") })
            captureReply(Expected(JsonPrimitive("internal"), code = JsonRpcErrorCodes.INTERNAL_ERROR, message = "Internal error"))
        }
        request(null, successMethod)
        request(JsonPrimitive("follow-up"), successMethod)
        captureReply(Expected(JsonPrimitive("follow-up"), expectedBody))
        closeNative()
    }

    private fun request(id: JsonElement?, method: String, params: JsonElement? = null) = write(buildJsonObject {
        put("jsonrpc", "2.0"); if (id != null) put("id", id); put("method", method)
        if (params != null) put("params", params)
    }.toString())

    private fun write(line: String) { requireNotNull(writer).apply { write(line); newLine(); flush() } }

    private fun captureReply(expected: Expected) {
        val line = output.poll(60, TimeUnit.SECONDS) ?: error("Native response timed out")
        assertNotEquals("\u0000EOF", line, "Native producer closed before the reply")
        assertTrue(line.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_DAEMON_RESPONSE_BYTES)
        observations += Json.parseToJsonElement(line).jsonObject to expected
    }

    fun verify() {
        assertEquals(if (producer == "MCP stdio") 5 else if (producer.endsWith("stdio")) 4 else 7, observations.size)
        for ((response, expected) in observations) {
            assertEquals("2.0", response.getValue("jsonrpc").jsonPrimitive.content, response.toString())
            assertEquals(expected.id, response.getValue("id"), response.toString())
            assertEquals(setOf("jsonrpc", "id", if (expected.code == null) "result" else "error"),
                response.keys, "Exactly one outcome is allowed, including explicit null results: " + response)
            if (expected.code == null) assertEquals(expected.result, response.getValue("result"))
            else {
                val error = response.getValue("error").jsonObject
                assertEquals(expected.code, error.getValue("code").jsonPrimitive.int)
                if (expected.message != null) assertEquals(expected.message, error.getValue("message").jsonPrimitive.content)
                if (expected.data != null) assertEquals(expected.data, error.getValue("data"))
                assertFalse(error.toString().contains("do-not-echo-envelope-input"))
                assertFalse(error.toString().contains("opaque-input"))
            }
        }
        if (!producer.endsWith("stdio")) assertTrue(ambiguousRejected, "Two meaningful outcomes must fail before output")
        println("JSONRPC_ENVELOPE producer=" + producer + " frames=" + observations.size)
    }

    private fun closeNative() {
        if (nativeClosed || process == null) return
        writer?.close()
        val active = requireNotNull(process)
        if (!active.waitFor(15, TimeUnit.SECONDS)) {
            active.destroyForcibly(); active.waitFor(); error("Native envelope producer did not close")
        }
        stdoutReader?.join(2_000)
        assertEquals(0, active.exitValue())
        assertFalse(requireNotNull(stdoutReader).isAlive)
        val remaining = mutableListOf<String>(); output.drainTo(remaining)
        assertEquals(listOf("\u0000EOF"), remaining, "Notification produced an extra reply or framing was not closed")
        nativeClosed = true
    }

    private fun inventory(): Map<String, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { path ->
            workspace.relativize(path).toString() to MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
        }
    }

    fun verifyInputs() {
        closeNative()
        assertEquals(baseline, inventory())
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        closeNative()
        if (::owned.isInitialized) Files.walk(owned).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

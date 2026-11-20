package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.ProtocolLimits
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspRuntimeBoundsSteps {
    private data class Frame(val body: JsonObject, val payloadBytes: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var workspace: Path
    private var process: Process? = null
    private var frames: List<Frame> = emptyList()
    private var exitCode: Int? = null
    private var stderrText: String = ""

    @Given("an LSP Java workspace has a document-symbol response larger than its output limit")
    fun workspaceHasOversizedDocumentSymbols() {
        workspace = Files.createTempDirectory("rk-lsp-runtime-bounds-")
        val source = workspace.resolve("src/main/java/sample/LargeSymbolResponse.java")
        Files.createDirectories(source.parent)
        val longMethodPrefix = "method" + "x".repeat(350)
        Files.newBufferedWriter(source).use { writer ->
            writer.append("package sample;\npublic class LargeSymbolResponse {\n")
            repeat(5_000) { index ->
                writer.append("public void ")
                    .append(longMethodPrefix)
                    .append(index.toString().padStart(5, '0'))
                    .append("() {}\n")
            }
            writer.append("}\n")
        }
        assertTrue(Files.size(source) < ProtocolLimits.MAX_SOURCE_FILE_BYTES.toLong())
    }

    @When("the JSON-RPC client requests that document's symbols over stdio")
    fun requestLargeDocumentSymbols() {
        val classpath = assertNotNull(
            System.getProperty("refactorkit.lsp.test.classpath"),
            "CLI test task must provide the LSP runtime classpath",
        )
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            java,
            "-cp",
            classpath,
            "org.refactorkit.lsp.RefactorKitLspKt",
        ).start()
        process = child

        val stdout = CompletableFuture.supplyAsync {
            child.inputStream.use { it.readAllBytes() }
        }
        val stderr = CompletableFuture.supplyAsync {
            child.errorStream.use { it.readAllBytes() }
        }
        val documentUri = workspace.resolve("src/main/java/sample/LargeSymbolResponse.java").toUri().toString()
        val requests = listOf(
            request("initialize", JsonPrimitive("initialize"), buildJsonObject {
                put("rootUri", JsonPrimitive(workspace.toUri().toString()))
                put("capabilities", buildJsonObject {})
            }),
            request("textDocument/documentSymbol", JsonPrimitive("large-symbols"), buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", JsonPrimitive(documentUri)) })
            }),
            request("shutdown", JsonPrimitive("shutdown"), JsonObject(emptyMap())),
            request("exit", JsonNull, null),
        )
        child.outputStream.use { output ->
            requests.forEach { output.write(frame(it)) }
            output.flush()
        }

        if (!child.waitFor(60, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            throw AssertionError("LSP process did not exit after the exit notification")
        }
        exitCode = child.exitValue()
        val outputBytes = stdout.get(5, TimeUnit.SECONDS)
        stderrText = stderr.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        frames = parseFrames(outputBytes)
    }

    @Then("the LSP advertises its response limit and returns a bounded JSON-RPC error with the original request id")
    fun lspAdvertisesAndEnforcesResponseBound() {
        val initialized = response("initialize")
        val kernel = initialized.getValue("result").jsonObject
            .getValue("capabilities").jsonObject
            .getValue("experimental").jsonObject
            .getValue("refactorkitLanguageKernel").jsonObject
        val javaAdapter = kernel.getValue("adapters").jsonArray.single {
            it.jsonObject.getValue("languageId").jsonPrimitive.content == "java"
        }.jsonObject
        val runtime = javaAdapter.getValue("runtime").jsonObject
        assertFalse(runtime.getValue("supportsCancellation").jsonPrimitive.content.toBoolean())
        assertEquals(
            ProtocolLimits.MAX_LSP_RESPONSE_BYTES.toLong(),
            runtime.getValue("limits").jsonObject.getValue("maxOutputBytes").jsonPrimitive.content.toLong(),
        )
        javaAdapter.getValue("capabilities").jsonArray.forEach { capability ->
            assertEquals(
                ProtocolLimits.MAX_LSP_RESPONSE_BYTES.toLong(),
                capability.jsonObject.getValue("runtime").jsonObject
                    .getValue("limits").jsonObject.getValue("maxOutputBytes").jsonPrimitive.content.toLong(),
            )
        }

        val oversized = response("large-symbols")
        assertTrue(
            oversized["error"] != null && oversized["error"] != JsonNull,
            "Expected the oversized response to be replaced; frame summaries=${frames.map { it.body.toString().take(300) }}; stderr=$stderrText",
        )
        assertEquals(JsonPrimitive("large-symbols"), oversized.getValue("id"))
        val error = oversized.getValue("error").jsonObject
        assertEquals(-32603, error.getValue("code").jsonPrimitive.content.toInt())
        assertEquals(
            "LSP response exceeds ${ProtocolLimits.MAX_LSP_RESPONSE_BYTES} bytes",
            error.getValue("message").jsonPrimitive.content,
        )
        assertTrue(frames.all { it.payloadBytes <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES })
    }

    @And("a subsequent shutdown response remains valid and the server exits cleanly")
    fun subsequentResponseAndExitRemainValid() {
        val shutdown = response("shutdown")
        assertEquals(JsonNull, shutdown.getValue("result"))
        assertEquals(0, exitCode)
    }

    private fun response(id: String): JsonObject = frames.single {
        it.body["id"] == JsonPrimitive(id)
    }.body

    private fun request(method: String, id: JsonElement?, params: JsonElement?): ByteArray =
        json.encodeToString(JsonRpcRequest(id = id, method = method, params = params)).toByteArray(Charsets.UTF_8)

    private fun frame(body: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.toByteArray()
    }

    private fun parseFrames(bytes: ByteArray): List<Frame> {
        val result = mutableListOf<Frame>()
        var offset = 0
        while (offset < bytes.size) {
            val boundary = findHeaderBoundary(bytes, offset)
            assertTrue(boundary >= 0, "LSP output ended inside a frame header")
            val header = bytes.copyOfRange(offset, boundary).toString(Charsets.US_ASCII)
            val length = assertNotNull(header.lineSequence()
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull(),
                "Every LSP output frame must have Content-Length",
            )
            assertTrue(length in 0..ProtocolLimits.MAX_LSP_RESPONSE_BYTES, "LSP output frame exceeded its bound")
            val bodyStart = boundary + HEADER_DELIMITER.size
            val bodyEnd = bodyStart + length
            assertTrue(bodyEnd <= bytes.size, "LSP output was truncated inside a JSON body")
            val bodyBytes = bytes.copyOfRange(bodyStart, bodyEnd)
            result += Frame(json.decodeFromString<JsonObject>(bodyBytes.toString(Charsets.UTF_8)), length)
            offset = bodyEnd
        }
        return result
    }

    private fun findHeaderBoundary(bytes: ByteArray, start: Int): Int {
        for (index in start..bytes.size - HEADER_DELIMITER.size) {
            if (HEADER_DELIMITER.indices.all { bytes[index + it] == HEADER_DELIMITER[it] }) return index
        }
        return -1
    }

    @After
    fun cleanup() {
        process?.let { child ->
            if (child.isAlive) child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
        }
        if (::workspace.isInitialized) Files.walk(workspace).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private val HEADER_DELIMITER = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}

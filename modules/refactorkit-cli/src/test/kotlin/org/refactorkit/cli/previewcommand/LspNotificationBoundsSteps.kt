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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspNotificationBoundsSteps {
    private data class Frame(val body: JsonObject, val payloadBytes: Int)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var workspace: Path
    private var process: Process? = null
    private var frames: List<Frame> = emptyList()
    private var exitCode: Int? = null
    private var stderrText: String = ""

    @Given("an LSP Java workspace produces diagnostics larger than its notification output limit")
    fun workspaceProducesOversizedDiagnostics() {
        workspace = Files.createTempDirectory("rk-lsp-notification-bounds-")
        val source = workspace.resolve("src/main/java/sample/NotificationOverflow.java")
        Files.createDirectories(source.parent)
        val unresolvedMethodPrefix = "notImplemented" + "x".repeat(24_000)
        Files.newBufferedWriter(source).use { writer ->
            writer.append("package sample;\npublic class NotificationOverflow { void check() {\n")
            repeat(120) { index -> writer.append(unresolvedMethodPrefix).append(index.toString()).append("();\n") }
            writer.append("}\n}\n")
        }
        assertTrue(Files.size(source) < ProtocolLimits.MAX_SOURCE_FILE_BYTES.toLong())
    }

    @When("the client initializes the LSP over stdio")
    fun initializeLsp() {
        val classpath = assertNotNull(
            System.getProperty("refactorkit.lsp.test.classpath"),
            "CLI test task must provide the LSP runtime classpath",
        )
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(java, "-cp", classpath, "org.refactorkit.lsp.RefactorKitLspKt").start()
        process = child
        val stdout = CompletableFuture.supplyAsync { child.inputStream.use { it.readAllBytes() } }
        val stderr = CompletableFuture.supplyAsync { child.errorStream.use { it.readAllBytes() } }

        val requests = listOf(
            request("initialize", JsonPrimitive("initialize"), buildJsonObject {
                put("rootUri", JsonPrimitive(workspace.toUri().toString()))
                put("capabilities", buildJsonObject {})
            }),
            request("shutdown", JsonPrimitive("shutdown"), JsonObject(emptyMap())),
            request("exit", JsonNull, null),
        )
        child.outputStream.use { output -> requests.forEach { output.write(frame(it)) } }
        if (!child.waitFor(90, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            throw AssertionError("LSP process did not exit after the exit notification")
        }
        exitCode = child.exitValue()
        val outputBytes = stdout.get(5, TimeUnit.SECONDS)
        stderrText = stderr.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        frames = parseFrames(outputBytes)
    }

    @Then("the oversized diagnostics notification is replaced by a bounded warning")
    fun notificationIsReplaced() {
        val warnings = frames.filter { it.body["method"]?.jsonPrimitive?.content == "window/logMessage" }
        assertEquals(
            1,
            warnings.size,
            "Expected one bounded warning; frames=${frames.map { it.body["method"]?.jsonPrimitive?.content to it.payloadBytes }}; stderr=$stderrText",
        )
        val warning = warnings.single()
        val params = warning.body.getValue("params").jsonObject
        assertEquals(2, params.getValue("type").jsonPrimitive.content.toInt())
        assertEquals(
            "RefactorKit omitted an oversized LSP notification",
            params.getValue("message").jsonPrimitive.content,
        )
        assertTrue(frames.none {
            it.body["method"]?.jsonPrimitive?.content == "textDocument/publishDiagnostics"
        }, "Oversized diagnostics must not be truncated or partially published")
        assertTrue(frames.all { it.payloadBytes <= ProtocolLimits.MAX_LSP_RESPONSE_BYTES })
        val warningIndex = frames.indexOfFirst { it.body == warning.body }
        val initializeIndex = frames.indexOfFirst { it.body["id"] == JsonPrimitive("initialize") }
        assertTrue(warningIndex in 0 until initializeIndex)
    }

    @And("the initialize response remains valid and the server exits cleanly")
    fun initializeResponseAndExitRemainValid() {
        assertTrue(response("initialize").containsKey("result"))
        assertEquals(JsonNull, response("shutdown").getValue("result"))
        assertEquals(0, exitCode, "LSP stderr: $stderrText")
    }

    private fun response(id: String): JsonObject = frames.single { it.body["id"] == JsonPrimitive(id) }.body

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
                ?.substringAfter(':')?.trim()?.toIntOrNull(), "Every LSP output frame must have Content-Length")
            assertTrue(length in 0..ProtocolLimits.MAX_LSP_RESPONSE_BYTES, "LSP output frame exceeded its bound")
            val bodyStart = boundary + HEADER_DELIMITER.size
            val bodyEnd = bodyStart + length
            assertTrue(bodyEnd <= bytes.size, "LSP output was truncated inside a JSON body")
            val body = json.decodeFromString<JsonObject>(bytes.copyOfRange(bodyStart, bodyEnd).toString(Charsets.UTF_8))
            result += Frame(body, length)
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

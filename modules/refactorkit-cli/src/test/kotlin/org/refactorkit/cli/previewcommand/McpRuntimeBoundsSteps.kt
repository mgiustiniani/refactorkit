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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcRequest
import org.refactorkit.core.ProtocolLimits
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class McpRuntimeBoundsSteps {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var workspace: Path
    private var process: Process? = null
    private var responses: List<JsonObject> = emptyList()
    private var exitCode: Int? = null
    private var stderrText: String = ""

    @Given("an MCP workspace contains a scanned Java file larger than the response output limit")
    fun workspaceContainsOversizedFileResource() {
        workspace = Files.createTempDirectory("rk-mcp-runtime-bounds-")
        val source = workspace.resolve("src/main/java/sample/LargeResource.java")
        Files.createDirectories(source.parent)
        Files.writeString(
            source,
            "package sample; public class LargeResource {} /*" +
                "x".repeat(ProtocolLimits.MAX_MCP_RESPONSE_BYTES + 4096) + "*/\n",
        )
        assertTrue(Files.size(source) < ProtocolLimits.MAX_SOURCE_FILE_BYTES.toLong())
    }

    @When("the MCP client reads that file resource over stdio")
    fun readOversizedResourceOverStdio() {
        val classpath = assertNotNull(
            System.getProperty("refactorkit.mcp.test.classpath"),
            "CLI test task must provide the MCP runtime classpath",
        )
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(java, "-cp", classpath, "org.refactorkit.mcp.RefactorKitMcpKt").start()
        process = child

        val stdout = CompletableFuture.supplyAsync { child.inputStream.use { it.readAllBytes() } }
        val stderr = CompletableFuture.supplyAsync { child.errorStream.use { it.readAllBytes() } }
        val source = workspace.resolve("src/main/java/sample/LargeResource.java")
        val requests = listOf(
            request("initialize", "initialize", buildJsonObject {
                put("protocolVersion", JsonPrimitive("2024-11-05"))
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", JsonPrimitive("runtime-bounds-test"))
                    put("version", JsonPrimitive("1"))
                })
            }),
            request("project-scan", "tools/call", buildJsonObject {
                put("name", JsonPrimitive("project_scan"))
                put("arguments", buildJsonObject { put("root", JsonPrimitive(workspace.toString())) })
            }),
            request("large-file", "resources/read", buildJsonObject {
                put("uri", JsonPrimitive(source.toUri().toString()))
            }),
            request("ping", "ping", buildJsonObject {}),
        )

        child.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            requests.forEach { body ->
                writer.write(json.encodeToString(body))
                writer.newLine()
                writer.flush()
            }
        }

        if (!child.waitFor(90, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor(5, TimeUnit.SECONDS)
            throw AssertionError("MCP process did not exit after stdio closed")
        }
        exitCode = child.exitValue()
        val outputBytes = stdout.get(5, TimeUnit.SECONDS)
        stderrText = stderr.get(5, TimeUnit.SECONDS).toString(Charsets.UTF_8)
        responses = outputBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank).map {
            json.decodeFromString<JsonObject>(it)
        }.toList()
        assertTrue(
            outputBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
                .all { it.toByteArray(Charsets.UTF_8).size <= ProtocolLimits.MAX_MCP_RESPONSE_BYTES },
            "MCP emitted an oversized NDJSON response; stderr=$stderrText",
        )
    }

    @Then("MCP advertises its response limit and returns a bounded JSON-RPC error preserving the request id")
    fun mcpAdvertisesAndEnforcesResponseBound() {
        val initialize = response("initialize")
        val kernel = initialize.getValue("result").jsonObject.getValue("refactorkitLanguageKernel").jsonObject
        val javaAdapter = kernel.getValue("adapters").jsonArray.single {
            it.jsonObject.getValue("languageId").jsonPrimitive.content == "java"
        }.jsonObject
        assertEquals(
            ProtocolLimits.MAX_MCP_RESPONSE_BYTES.toLong(),
            javaAdapter.getValue("runtime").jsonObject.getValue("limits").jsonObject
                .getValue("maxOutputBytes").jsonPrimitive.content.toLong(),
        )
        assertFalse(javaAdapter.getValue("runtime").jsonObject
            .getValue("supportsCancellation").jsonPrimitive.content.toBoolean())
        javaAdapter.getValue("capabilities").jsonArray.forEach { capability ->
            assertEquals(
                ProtocolLimits.MAX_MCP_RESPONSE_BYTES.toLong(),
                capability.jsonObject.getValue("runtime").jsonObject.getValue("limits").jsonObject
                    .getValue("maxOutputBytes").jsonPrimitive.content.toLong(),
            )
        }

        val scan = response("project-scan")
        assertEquals(JsonPrimitive("project-scan"), scan.getValue("id"))
        val oversized = response("large-file")
        assertEquals(JsonPrimitive("large-file"), oversized.getValue("id"))
        val error = oversized.getValue("error").jsonObject
        assertEquals(-32603, error.getValue("code").jsonPrimitive.content.toInt())
        assertEquals(
            "MCP response exceeds ${ProtocolLimits.MAX_MCP_RESPONSE_BYTES} bytes",
            error.getValue("message").jsonPrimitive.content,
        )
    }

    @And("a following ping response remains valid and the server exits cleanly")
    fun laterResponseAndCleanExit() {
        val ping = response("ping")
        assertEquals(JsonPrimitive("ping"), ping.getValue("id"))
        assertTrue(ping.getValue("result").jsonObject.isEmpty())
        assertEquals(0, exitCode)
    }

    private fun request(id: String, method: String, params: JsonElement): JsonRpcRequest =
        JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)

    private fun response(id: String): JsonObject = responses.single { it["id"] == JsonPrimitive(id) }

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
}

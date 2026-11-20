package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Actual source-child NDJSON proof; this fixture is not packaged release evidence. */
class CMcpRefusalSteps {
    private var workspace: Path? = null
    private var wire: TypeScriptPackagedSurface? = null
    private var originals: Map<Path, String> = emptyMap()
    private var sdkInput: JsonElement = JsonNull
    private var response: JsonObject? = null

    @Given("a C {string} MCP workspace captures malformed SDK input {string}")
    fun capture(standard: String, malformed: String) {
        require(standard in setOf("c11", "c17"))
        val root = Files.createTempDirectory("rk-c-mcp-sdk-error-"); workspace = root
        val unavailable = root.resolve("unavailable")
        val sdkRoot = root.resolveSibling(root.fileName.toString() + "-absent-sdk")
        assertFalse(Files.exists(sdkRoot)); assertFalse(Files.exists(unavailable))
        fun entry(license: JsonElement? = JsonPrimitive("LicenseRef-RefactorKitFixture")) = buildJsonObject {
            put("root", sdkRoot.toString()); put("provenance", "fixture:mcp-sdk-refusal")
            license?.let { put("license", it) }
        }
        sdkInput = when (malformed) {
            "null" -> JsonNull
            "object" -> entry()
            "nine roots" -> JsonArray(List(9) { entry() })
            "non-object entry" -> JsonArray(listOf(JsonPrimitive("not-an-sdk-object")))
            "missing license" -> JsonArray(listOf(entry(null)))
            "non-string license" -> JsonArray(listOf(entry(JsonPrimitive(7))))
            else -> error("Unknown malformed SDK fixture: $malformed")
        }
        val database = buildJsonArray { add(buildJsonObject {
            put("directory", root.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(unavailable.resolve("clang").toString(), "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
        }) }.toString()
        originals = mapOf(Path.of("main.c") to "static int value = 1;\nint main(void) { return value; }\n",
            Path.of("compile_commands.json") to database)
        originals.forEach { (path, text) -> Files.writeString(root.resolve(path), text) }
    }

    @When("the MCP stdio client calls {string} with that SDK input")
    fun call(tool: String) {
        require(tool in setOf("symbol_search", "symbol_definition", "symbol_references", "preview_refactoring"))
        val root = requireNotNull(workspace)
        val child = TypeScriptPackagedSurface(Path.of(System.getProperty("java.home")), root,
            assertNotNull(System.getProperty("refactorkit.mcp.test.classpath")), sourceMcp = true)
        wire = child
        child.dispatch("MCP", "initialize", buildJsonObject {
            put("protocolVersion", "2024-11-05"); put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "c-sdk-refusal-story"); put("version", "1") })
        })
        child.notifyInitialized()
        val scan = child.dispatch("MCP", "tools/call", buildJsonObject {
            put("name", "project_scan"); put("arguments", buildJsonObject { put("root", root.toString()) })
        }).jsonObject
        assertFalse(scan["isError"]?.jsonPrimitive?.booleanOrNull == true, scan.toString())
        val text = scan.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
        val snapshot = assertNotNull(Regex("Snapshot: ([0-9a-f]{64})").find(text)).groupValues[1]
        val arguments = buildJsonObject {
            put("languageId", "c"); put("expectedSnapshotHash", snapshot); put("readOnlySdks", sdkInput)
            for (name in listOf("clang", "clangd", "clangFormat")) put(name, root.resolve("unavailable").resolve(name).toString())
            if (tool == "preview_refactoring") {
                put("operation", "renameSymbol"); put("symbol", "value")
                put("arguments", buildJsonObject {
                    put("newName", "renamed"); put("file", "main.c"); put("line", 0); put("character", 11)
                })
            } else if (tool == "symbol_search") put("query", "value")
            else put("symbol", "c-decl:" + "0".repeat(64))
        }
        response = child.dispatch("MCP", "tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }).jsonObject
    }

    @Then("the MCP tool error retains INVALID_PARAMS and SDK field {string}")
    fun verify(field: String) {
        val result = assertNotNull(response)
        assertEquals(true, result["isError"]?.jsonPrimitive?.booleanOrNull)
        val text = result.getValue("content").jsonArray.single().jsonObject
        assertEquals("text", text.getValue("type").jsonPrimitive.content)
        val structured = assertNotNull(result["structuredContent"], "Typed RPC details must survive the MCP tool boundary").jsonObject
        val error = structured.getValue("error").jsonObject
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error.getValue("code").jsonPrimitive.int)
        val message = error.getValue("message").jsonPrimitive.content
        assertEquals("Error: $message", text.getValue("text").jsonPrimitive.content)
        assertEquals("c.sdkConfigurationInvalid: invalid $field in readonly SDK configuration", message)
        assertEquals(buildJsonObject { put("refusalCode", "c.sdkConfigurationInvalid"); put("field", field) }, error.getValue("data"))
        assertFalse(listOf("planId", "workspaceEdit", "transactionId", "stackTrace").any { it in structured.toString() })
    }

    @Then("the MCP SDK refusal preserves inputs and leaves a usable cleanly closed server")
    fun intact() {
        val child = requireNotNull(wire)
        assertEquals(buildJsonObject {}, child.dispatch("MCP", "ping", buildJsonObject {}))
        child.close(); wire = null
        val root = requireNotNull(workspace)
        val saved = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
            .associate { root.relativize(it) to Files.readString(it) } }
        assertEquals(originals, saved)
        assertFalse(Files.exists(root.resolve(".refactorkit")))
        assertFalse(Files.exists(root.resolve("unavailable")))
    }

    @After
    fun close() {
        try { wire?.close() } finally {
            wire = null
            workspace?.let { root -> Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        }
    }
}

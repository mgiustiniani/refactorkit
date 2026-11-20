package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.core.JsonRpcException
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterReorderingSurfaceSteps {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var reference: Path
    private lateinit var surface: String
    private lateinit var condition: String
    private lateinit var original: String
    private lateinit var expected: String
    private lateinit var database: String
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private var outcome = ""
    private var detail = ""

    @Given("the C parameter reorder protocol {string} has condition {string}")
    fun open(route: String, case: String) {
        surface = route; condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-reorder-surface-")
        root = workspace
        original = "int required[REQUIRED - 16];\n" +
            "static int weighted(int x, int y, int z);\n" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(9, 8, 7); }\n"
        expected = "int required[REQUIRED - 16];\n" +
            "static int weighted(int z, int y, int x);\n" +
            "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(7, 8, 9); }\n"
        Files.writeString(workspace.resolve("main.c"), original)
        reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        beforeHash = CProjectScanner().scan(workspace).hash
        when (route) {
            "daemon" -> { daemon = DaemonSession(); daemon!!.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }) }
            "MCP" -> { mcp = McpSession(); call("project_scan", buildJsonObject { put("root", workspace.toString()) }) }
            else -> error("Unknown protocol: $route")
        }
        privateBefore = privateDirectories()
    }

    @When("that protocol requests the parameter reorder lifecycle")
    fun lifecycle() {
        val request = buildJsonObject {
            put("operation", "reorderParameters"); put("languageId", "c")
            put("arguments", buildJsonObject {
                put("file", "main.c"); put("symbol", "weighted")
                put("originalIndices", if (condition == "duplicate original index") "2,2,0" else "2,1,0")
            })
            put("clang", reference.resolve("usr/bin/clang-22").toString())
            put("clangd", reference.resolve("usr/bin/clangd").toString())
            put("clangFormat", reference.resolve("usr/bin/clang-format").toString())
        }
        try {
            val id = if (surface == "daemon") {
                val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
                assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
                assertEquals("reorderParameters", preview.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
                preview.getValue("planId").jsonPrimitive.content
            } else {
                val preview = call("preview_refactoring", request)
                detail = preview
                if (preview.contains("Status   : REFUSED")) { outcome = "mapping refused"; return }
                assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: reorderParameters") && preview.contains("COMPILER_PROVEN"), preview)
                Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            }
            assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
            if (condition == "database changed") {
                database += " \n"
                Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database)
            }
            val transaction = if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", id) }).jsonObject
                detail = applied.toString()
                if (applied.getValue("status").jsonPrimitive.content != "applied") { outcome = "changed input refused"; return }
                applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", id) })
                detail = applied
                if (applied.contains("Apply refused")) { outcome = "changed input refused"; return }
                assertTrue(applied.contains("Applied successfully"), applied)
                Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
            assertEquals(expected, Files.readString(requireNotNull(root).resolve("main.c")))
            if (surface == "daemon") {
                val undo = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", transaction) }).jsonObject
                assertEquals("rolledBack", undo.getValue("status").jsonPrimitive.content, undo.toString())
            } else assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", transaction) }).contains("Rolled back"))
            outcome = "applied and rolled back"
        } catch (refused: JsonRpcException) {
            detail = refused.data?.toString().orEmpty() + " " + refused.message.orEmpty()
            outcome = if (condition == "duplicate original index") "mapping refused" else "changed input refused"
        }
    }

    @Then("the parameter reorder protocol reports {string}")
    fun report(expectedOutcome: String) {
        assertEquals(expectedOutcome, outcome, detail)
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        when (expectedOutcome) {
            "applied and rolled back" -> {
                assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
                assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
            }
            "mapping refused" -> {
                assertTrue(detail.contains("c.signatureReorder.invalid_mapping"), detail)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "changed input refused" -> {
                assertTrue(detail.contains("snapshot") || detail.contains("authority") || detail.contains("c.functionFacts"), detail)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
        }
    }

    @Then("the parameter reorder protocol preserves configuration and disposes private compiler material")
    fun intact() {
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertEquals(privateBefore, privateDirectories())
    }

    private fun call(name: String, args: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", args)
    }).jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    @After
    fun cleanup() {
        daemon?.close(); mcp?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

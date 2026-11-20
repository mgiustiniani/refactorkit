package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CInlineFunctionSurfaceSteps {
    private val fixture = CInlineFunctionSurfaceFixture()

    @Given("the C function-inline surface {string} captures {string} inputs with {string}")
    fun capture(surface: String, standard: String, condition: String) = fixture.capture(surface, standard, condition)

    @When("that surface requests the managed C function-inline lifecycle")
    fun lifecycle() = fixture.execute()

    @Then("the C function-inline surface reports {string}")
    fun outcome(expected: String) = fixture.assertOutcome(expected)

    @Then("the C function-inline surface restores captured inputs and releases compiler material")
    fun intact() = fixture.assertIntact()

    @After
    fun cleanup() = fixture.close()
}

/** Scenario resources and independent images live behind the transport glue. */
private class CInlineFunctionSurfaceFixture : AutoCloseable {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var sdk: Path
    private lateinit var surface: String
    private lateinit var condition: String
    private lateinit var original: String
    private lateinit var database: String
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var privateBefore: Set<Path>
    private var result = ""
    private var detail = ""
    private val expected = "int main(void) { int left = 3; int right = 4; return 2 * (left + right); }\n"

    fun capture(route: String, standard: String, scenarioCondition: String) {
        require(route in setOf("daemon", "MCP", "CLI") && standard in setOf("c11", "c17"))
        require(scenarioCondition in setOf("pure local reads", "argument effect", "database changed"))
        surface = route; condition = scenarioCondition
        val workspace = Files.createTempDirectory("refactorkit-c-inline-surface-").also { root = it }
        original = "static int add(int a, int b) { return a + b; }\n" +
            "int main(void) { int left = 3; int right = 4; return 2 * add(" +
            (if (condition == "argument effect") "left++, right" else "left, right") + "); }\n"
        Files.writeString(workspace.resolve("main.c"), original)
        sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        if (surface == "daemon") {
            daemon = DaemonSession().also { it.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }) }
        } else if (surface == "MCP") {
            mcp = McpSession()
            call("project_scan", buildJsonObject { put("root", workspace.toString()) })
        }
        privateBefore = privateDirectories()
    }

    fun execute() {
        if (surface == "CLI") { executeCli(); return }
        val workspace = requireNotNull(root)
        val request = buildJsonObject {
            put("operation", "inlineFunction"); put("languageId", "c")
            put("arguments", buildJsonObject { put("file", "main.c"); put("symbol", "add") })
            put("clang", sdk.resolve("usr/bin/clang-22").toString())
            put("clangd", sdk.resolve("usr/bin/clangd").toString())
            put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
        }
        val plan: String
        try {
            plan = if (surface == "daemon") {
                val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
                detail = preview.toString()
                assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, detail)
                assertEquals("inlineFunction", preview.getValue("operation").jsonPrimitive.content, detail)
                assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content, detail)
                preview.getValue("planId").jsonPrimitive.content
            } else {
                val preview = call("preview_refactoring", request); detail = preview
                if (preview.contains("Status   : REFUSED")) {
                    assertTrue(preview.contains("c.inlineFunction.unsupportedSemantics"), preview)
                    result = "effects refused"; return
                }
                assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: inlineFunction") &&
                    preview.contains("COMPILER_PROVEN"), preview)
                Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            }
        } catch (refusal: JsonRpcException) {
            detail = refusal.data.toString() + " " + refusal.message
            assertEquals("argument effect", condition, detail)
            assertTrue(detail.contains("c.inlineFunction.unsupportedSemantics"), detail)
            result = "effects refused"; return
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + " \n")
        val transaction: String
        try {
            transaction = if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", plan) }).jsonObject
                detail = applied.toString()
                if (applied.getValue("status").jsonPrimitive.content != "applied") {
                    assertEquals("database changed", condition, detail)
                    result = "changed input refused"; return
                }
                applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", plan) }); detail = applied
                if (condition == "database changed") {
                    assertTrue(applied.startsWith("Apply refused [${JsonRpcErrorCodes.SNAPSHOT_CHANGED}]"), applied)
                    assertTrue(applied.contains("changed since preview"), applied)
                    result = "changed input refused"; return
                }
                assertTrue(applied.contains("Applied successfully"), applied)
                Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
        } catch (refusal: JsonRpcException) {
            detail = refusal.data.toString() + " " + refusal.message
            assertEquals("database changed", condition, detail)
            result = "changed input refused"; return
        }
        assertEquals("pure local reads", condition, "A non-admitted image must never be applied: $detail")
        assertEquals(expected, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        if (surface == "daemon") {
            val rollback = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", transaction) }).jsonObject
            assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content, rollback.toString())
        } else assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", transaction) }).contains("Rolled back"))
        result = "applied and rolled back"
    }

    private fun executeCli() {
        val workspace = requireNotNull(root)
        val command = listOf("c", "inline", workspace.toString(), "--file", "main.c", "--symbol", "add",
            "--clang", sdk.resolve("usr/bin/clang-22").toString(), "--clangd", sdk.resolve("usr/bin/clangd").toString(),
            "--clang-format", sdk.resolve("usr/bin/clang-format").toString())
        val preview = cli(command + "--preview")
        detail = preview.output
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "argument effect") {
            assertEquals(1, preview.code, detail)
            assertTrue(detail.contains("c.inlineFunction.unsupportedSemantics"), detail)
            val denied = cli(command + "--apply")
            assertEquals(1, denied.code, denied.output)
            assertTrue(denied.output.contains("c.inlineFunction.unsupportedSemantics"), denied.output)
            detail = denied.output; result = "effects refused"; return
        }
        assertEquals("pure local reads", condition)
        assertEquals(0, preview.code, detail)
        val plan = Json.parseToJsonElement(preview.output).jsonObject
        assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content, detail)
        assertEquals("inlineFunction", plan.getValue("operation").jsonPrimitive.content, detail)
        assertEquals("COMPILER_PROVEN", plan.getValue("evidence").jsonPrimitive.content, detail)
        assertTrue(plan.getValue("renderedDiff").jsonPrimitive.content.contains("(left + right)"), detail)
        val applied = cli(command + "--apply")
        detail = applied.output; assertEquals(0, applied.code, detail)
        val response = Json.parseToJsonElement(applied.output).jsonObject
        assertEquals("applied", response.getValue("status").jsonPrimitive.content, detail)
        val transaction = response.getValue("transactionId").jsonPrimitive.content
        assertEquals(expected, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        val rollback = cli(listOf("patch", "rollback", transaction, "--root", workspace.toString()))
        assertEquals(0, rollback.code, rollback.output)
        assertTrue(rollback.output.contains("Rolled back transaction $transaction"), rollback.output)
        result = "applied and rolled back"
    }

    private data class CliResult(val code: Int, val output: String)

    private fun cli(arguments: List<String>): CliResult = synchronized(RefactorKitCli::class.java) {
        val previousOut = System.out; val previousErr = System.err
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        PrintStream(stdout, true, Charsets.UTF_8).use { out ->
            PrintStream(stderr, true, Charsets.UTF_8).use { err ->
                try {
                    System.setOut(out); System.setErr(err)
                    val code = RefactorKitCli().run(arguments)
                    CliResult(code, stdout.toString(Charsets.UTF_8) + stderr.toString(Charsets.UTF_8))
                } finally { System.setOut(previousOut); System.setErr(previousErr) }
            }
        }
    }

    fun assertOutcome(expected: String) {
        assertEquals(expected, result, detail)
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        if (expected == "applied and rolled back") {
            assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        } else {
            val code = if (expected == "effects refused") "c.inlineFunction.unsupportedSemantics" else "changed since preview"
            assertTrue(detail.contains(code), detail)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
        if (condition == "database changed") {
            assertEquals(database + " \n", Files.readString(workspace.resolve("compile_commands.json")))
            Files.writeString(workspace.resolve("compile_commands.json"), database)
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun call(name: String, arguments: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", arguments)
    }).jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    override fun close() {
        daemon?.close(); mcp?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

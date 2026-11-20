package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CCMakeIncludeMoveSample
import org.refactorkit.c.CProjectScanner
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CCMakeIncludeTransportSteps {
    private val fixture = CCMakeIncludeTransportFixture()
    @Given("the include-aware CMake surface {string} captures {string} {string} inputs with {string}")
    fun capture(surface: String, standard: String, operation: String, condition: String) = fixture.capture(surface, standard, operation, condition)
    @When("that surface requests the include-aware CMake preview apply and rollback lifecycle")
    fun execute() = fixture.execute()
    @Then("the include-aware CMake surface reports {string}")
    fun result(expected: String) = fixture.assertResult(expected)
    @Then("the include-aware CMake surface preserves expected project images and transaction history")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CCMakeIncludeTransportFixture : AutoCloseable {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var sdk: Path
    private lateinit var surface: String
    private lateinit var mode: String
    private lateinit var condition: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var database: String
    private lateinit var expectedDatabase: String
    private lateinit var snapshotHash: String
    private var transaction: String? = null
    private var result = ""
    private var detail = ""

    fun capture(route: String, standard: String, operation: String, variant: String) {
        require(route in setOf("CLI", "daemon", "MCP") && standard in setOf("c11", "c17"))
        require(operation in setOf("component", "header") && variant in setOf("matching", "database changed", "missing flag"))
        surface = route; mode = operation; condition = variant
        val workspace = Files.createTempDirectory("rk-cmake-include-surface-").also { root = it }
        sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        original = CCMakeIncludeMoveSample.material(mode, "PUBLIC", false)
        expected = CCMakeIncludeMoveSample.material(mode, "PUBLIC", true)
        original.forEach { (path, text) -> Files.createDirectories(workspace.resolve(path).parent); Files.writeString(workspace.resolve(path), text) }
        fun commands(image: Map<Path, String>): String = JsonArray(image.keys.filter { it.toString().endsWith(".c") }.map { path ->
            val directory = if (path.startsWith(Path.of("library"))) "library" else "component"
            buildJsonObject {
                put("directory", workspace.toString()); put("file", path.toString())
                put("arguments", JsonArray((listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard") +
                    (if (variant == "missing flag") emptyList() else listOf("-I", "$directory/include")) + listOf("-c", path.toString())).map(::JsonPrimitive)))
            }
        }).toString()
        database = commands(original); expectedDatabase = commands(expected)
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(workspace).hash
        when (route) {
            "daemon" -> daemon = DaemonSession().also { it.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }) }
            "MCP" -> { mcp = McpSession(); call("project_scan", buildJsonObject { put("root", workspace.toString()) }) }
        }
    }

    private fun arguments(): JsonObject = if (mode == "component") buildJsonObject {
        put("componentDir", "component"); put("newDir", "library")
    } else buildJsonObject { put("file", "component/include/api.h"); put("target", "component/include/public/api.h") }
    private fun request() = buildJsonObject {
        put("operation", if (mode == "component") "relocateComponent" else "moveSource"); put("languageId", "c")
        put("arguments", arguments())
        put("clang", sdk.resolve("usr/bin/clang-22").toString()); put("clangd", sdk.resolve("usr/bin/clangd").toString())
        put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
    }

    fun execute() {
        if (surface == "CLI") { executeCli(); return }
        val workspace = requireNotNull(root)
        val plan = if (surface == "daemon") {
            val preview = requireNotNull(daemon).dispatch("c.preview", request()).jsonObject
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
            assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
            preview.getValue("planId").jsonPrimitive.content
        } else {
            val preview = call("preview_refactoring", request())
            assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("COMPILER_PROVEN"), preview)
            Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
        }
        assertImage(original, database)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        try {
            if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", plan) }).jsonObject
                detail = applied.toString(); assertEquals("applied", applied.getValue("status").jsonPrimitive.content, detail)
                transaction = applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", plan) }); detail = applied
                if (condition == "database changed") {
                    assertTrue(applied.startsWith("Apply refused [${JsonRpcErrorCodes.SNAPSHOT_CHANGED}]"), applied)
                    result = "changed input refused"; return
                }
                assertTrue(applied.contains("Applied successfully"), applied)
                transaction = Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
        } catch (failure: JsonRpcException) {
            detail = failure.message.orEmpty(); assertEquals("database changed", condition, detail)
            assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, failure.code, detail)
            result = "changed input refused"; return
        }
        assertEquals("matching", condition)
        assertImage(expected, expectedDatabase)
        val id = assertNotNull(transaction)
        if (surface == "daemon") {
            val undo = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", id) }).jsonObject
            assertEquals("rolledBack", undo.getValue("status").jsonPrimitive.content, undo.toString())
        } else assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", id) }).contains("Rolled back"))
        result = "applied and rolled back"
    }

    private fun executeCli() {
        val workspace = requireNotNull(root)
        val command = listOf("c", if (mode == "component") "relocate" else "move", workspace.toString()) +
            (if (mode == "component") listOf("--component-dir", "component", "--new-dir", "library") else
                listOf("--file", "component/include/api.h", "--target", "component/include/public/api.h")) +
            listOf("--clang", sdk.resolve("usr/bin/clang-22").toString(), "--clangd", sdk.resolve("usr/bin/clangd").toString(),
                "--clang-format", sdk.resolve("usr/bin/clang-format").toString())
        val (previewCode, output) = cli(command + "--preview"); detail = output
        assertEquals(0, previewCode, output); assertImage(original, database)
        val preview = Json.parseToJsonElement(output).jsonObject
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        if (condition != "missing flag") assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
        val (code, appliedOutput) = cli(command + "--apply"); detail = appliedOutput
        if (condition == "missing flag") {
            assertEquals(1, code, appliedOutput); assertTrue(appliedOutput.isNotBlank())
            result = "unavailable refused"; return
        }
        assertEquals(0, code, appliedOutput)
        val applied = Json.parseToJsonElement(appliedOutput).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        transaction = applied.getValue("transactionId").jsonPrimitive.content
        assertImage(expected, expectedDatabase)
        val (undoCode, undo) = cli(listOf("patch", "rollback", assertNotNull(transaction), "--root", workspace.toString()))
        assertEquals(0, undoCode, undo); assertTrue(undo.contains("Rolled back transaction"), undo)
        result = "applied and rolled back"
    }

    fun assertResult(expected: String) = assertEquals(expected, result, detail)
    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertImage(original, database + if (condition == "database changed") "\n" else "")
        val id = transaction
        if (id == null) assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        else {
            assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(TransactionId(id))).state)
        }
        Files.walk(workspace).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().endsWith(".o") || it.fileName.toString().endsWith(".a") }) }
    }
    private fun assertImage(image: Map<Path, String>, db: String) {
        val workspace = requireNotNull(root)
        val actual = CProjectScanner().scan(workspace)
        assertEquals(image, actual.trackedFiles.filter { it.languageId in setOf("c", "cmake") }.associate { it.path to it.content })
        assertEquals(db, Files.readString(workspace.resolve("compile_commands.json")))
    }
    private fun call(name: String, args: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", args)
    }).jsonObject.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
    private fun cli(args: List<String>): Pair<Int, String> = synchronized(RefactorKitCli::class.java) {
        val out = System.out; val err = System.err; val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { stream ->
            try { System.setOut(stream); System.setErr(stream); RefactorKitCli().run(args) to bytes.toString(Charsets.UTF_8) }
            finally { System.setOut(out); System.setErr(err) }
        }
    }
    override fun close() {
        daemon?.close(); mcp?.close()
        root?.let { path -> Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

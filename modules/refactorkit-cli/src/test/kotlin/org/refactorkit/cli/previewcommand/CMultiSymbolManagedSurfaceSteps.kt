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
import org.refactorkit.c.CProjectScanner
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
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

class CMultiSymbolManagedSurfaceSteps {
    private val fixture = CMultiSymbolManagedSurfaceFixture()

    @Given("the C multi-symbol surface {string} captures {string} API families")
    fun capture(surface: String, standard: String) = fixture.capture(surface, standard)

    @Given("the C multi-symbol surface {string} captures an unqualified {string} consumer")
    fun captureMixed(surface: String, consumer: String) = fixture.capture(surface, "c17", consumer)

    @When("that surface requests both C API mappings and managed Apply")
    fun execute() = fixture.execute()

    @Then("both C API families share one approved transaction and exact post-images")
    fun applied() = fixture.assertApplied()

    @Then("rollback through that surface restores all C inputs and releases compiler material")
    fun rollback() = fixture.rollbackAndAssertRestored()

    @Then("the mixed C consumer is refused with {string} before any transaction")
    fun refused(code: String) = fixture.assertRefused(code)

    @Then("all multi-symbol source inputs and compiler material remain unchanged")
    fun untouched() = fixture.assertUntouched()

    @After
    fun cleanup() = fixture.close()
}

/** Scenario-owned transport observations and independently authored source images. */
private class CMultiSymbolManagedSurfaceFixture : AutoCloseable {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var surface: String
    private lateinit var tools: Path
    private lateinit var original: Map<String, String>
    private lateinit var expected: Map<String, String>
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private var planId = ""
    private var transactionId = ""
    private lateinit var command: List<String>
    private var consumerLanguage: String? = null
    private var refusalCode: String? = null
    private var refusalDetail = ""
    private var previewRefused = false

    fun capture(route: String, standard: String, consumer: String? = null) {
        require(route in setOf("CLI", "daemon", "MCP") && standard in setOf("c11", "c17"))
        require(consumer == null || consumer in setOf("cpp", "objective-c"))
        consumerLanguage = consumer
        surface = route
        val workspace = Files.createTempDirectory("refactorkit-c-multi-prefix-").also { root = it }
        tools = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val header = "#ifndef API_H\n#define API_H\nint calculate(int value);\nint load_item(int value);\n#endif\n"
        val implementation = "#include \"api.h\"\nint calculate(int value) { return value * 2; }\n" +
            "int load_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
            "/* calculate load_item stay in comments */\n"
        val main = "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
            "int main(void) { return calculate(21) + load_item(1) == 44 ? 0 : 1; }\n"
        val database = JsonArray(listOf("api.c", "main.c").map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray(listOf(tools.resolve("usr/bin/clang-22").toString(),
                "-std=$standard", "-I", ".", "-c", file).map(::JsonPrimitive)))
        } }).toString()
        val foreignSources = when (consumer) {
            "cpp" -> mapOf("consumer.cpp" to "extern \"C\" {\n#include \"api.h\"\n}\n" +
                "int foreign_use(void) { return calculate(1) + load_item(2); }\n")
            "objective-c" -> mapOf("consumer.m" to "#include \"api.h\"\n" +
                "int foreign_use(void) { return calculate(1) + load_item(2); }\n")
            else -> emptyMap()
        }
        original = mapOf("api.h" to header, "api.c" to implementation, "main.c" to main,
            "compile_commands.json" to database) + foreignSources
        expected = mapOf(
            "api.h" to "#ifndef API_H\n#define API_H\nint compute(int value);\nint read_item(int value);\n#endif\n",
            "api.c" to "#include \"api.h\"\nint compute(int value) { return value * 2; }\n" +
                "int read_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
                "/* calculate load_item stay in comments */\n",
            "main.c" to "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
                "int main(void) { return compute(21) + read_item(1) == 44 ? 0 : 1; }\n",
            "compile_commands.json" to database,
        ) + foreignSources
        original.forEach { (name, content) -> Files.writeString(workspace.resolve(name), content) }
        beforeHash = CProjectScanner().scan(workspace).hash
        when (surface) {
            "daemon" -> daemon = DaemonSession().also {
                it.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) })
            }
            "MCP" -> {
                mcp = McpSession()
                call("project_scan", buildJsonObject { put("root", workspace.toString()) })
            }
        }
        command = listOf("c", "rename-prefix", workspace.toString(),
            "--mapping", "calculate=compute,load_item=read_item",
            "--clang", tools.resolve("usr/bin/clang-22").toString(),
            "--clangd", tools.resolve("usr/bin/clangd").toString(),
            "--clang-format", tools.resolve("usr/bin/clang-format").toString())
        privateBefore = privateDirectories()
    }

    fun execute() {
        val request = buildJsonObject {
            put("operation", "renamePrefix"); put("languageId", "c")
            put("arguments", buildJsonObject { put("rename.calculate", "compute"); put("rename.load_item", "read_item") })
            put("clang", tools.resolve("usr/bin/clang-22").toString())
            put("clangd", tools.resolve("usr/bin/clangd").toString())
            put("clangFormat", tools.resolve("usr/bin/clang-format").toString())
        }
        if (consumerLanguage != null) {
            executeRefusedPreview(request)
            return
        }
        planId = when (surface) {
            "CLI" -> {
                val preview = cli(command + "--preview")
                assertEquals(0, preview.code, preview.output)
                inspectPreview(Json.parseToJsonElement(preview.output).jsonObject)
            }
            "daemon" -> inspectPreview(requireNotNull(daemon).dispatch("c.preview", request).jsonObject)
            else -> {
                val preview = call("preview_refactoring", request)
                assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: renameCSymbolPrefix") &&
                    preview.contains("COMPILER_PROVEN"), preview)
                assertTrue(preview.contains("external ABI consumers"), preview)
                Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            }
        }
        val workspace = requireNotNull(root)
        original.forEach { (name, content) -> assertEquals(content, Files.readString(workspace.resolve(name)), name) }
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        transactionId = when (surface) {
            "CLI" -> {
                val applied = cli(command + "--apply")
                assertEquals(0, applied.code, applied.output)
                val response = Json.parseToJsonElement(applied.output).jsonObject
                assertEquals("applied", response.getValue("status").jsonPrimitive.content, response.toString())
                response.getValue("transactionId").jsonPrimitive.content
            }
            "daemon" -> {
                val response = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", planId) }).jsonObject
                assertEquals("applied", response.getValue("status").jsonPrimitive.content, response.toString())
                response.getValue("transactionId").jsonPrimitive.content
            }
            else -> {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", planId) })
                assertTrue(applied.contains("Applied successfully"), applied)
                Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
        }
    }

    private fun executeRefusedPreview(request: JsonObject) {
        when (surface) {
            "CLI" -> {
                val response = cli(command + "--preview")
                refusalDetail = response.output
                previewRefused = response.code == 1 && response.output.contains("C command refused")
                refusalCode = Regex("C command refused \\[([^]]+)\\]").find(response.output)?.groupValues?.get(1)
            }
            "daemon" -> try {
                refusalDetail = requireNotNull(daemon).dispatch("c.preview", request).toString()
            } catch (refused: JsonRpcException) {
                refusalDetail = refused.message.orEmpty() + " " + refused.data
                previewRefused = refused.code == JsonRpcErrorCodes.PLAN_REFUSED
                refusalCode = refused.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content
            }
            else -> {
                val response = call("preview_refactoring", request)
                refusalDetail = response
                previewRefused = response.contains("Status   : REFUSED")
                refusalCode = Regex("Refusal\\s+: (\\S+)").find(response)?.groupValues?.get(1)
            }
        }
    }

    fun assertRefused(expectedCode: String) {
        assertTrue(previewRefused, "Unqualified $consumerLanguage consumer accepted: $refusalDetail")
        assertEquals(expectedCode, refusalCode, refusalDetail)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }

    fun assertUntouched() {
        val workspace = requireNotNull(root)
        original.forEach { (name, content) -> assertEquals(content, Files.readString(workspace.resolve(name)), name) }
        assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        assertEquals(privateBefore, privateDirectories())
    }

    private fun inspectPreview(preview: JsonObject): String {
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
        assertEquals("renameCSymbolPrefix", preview.getValue("operation").jsonPrimitive.content)
        assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
        assertEquals(setOf("api.h", "api.c", "main.c"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(preview.getValue("warnings").jsonArray.any { it.jsonPrimitive.content.contains("external ABI consumers") })
        return preview.getValue("planId").jsonPrimitive.content
    }

    fun assertApplied() {
        val workspace = requireNotNull(root)
        expected.forEach { (name, content) -> assertEquals(content, Files.readString(workspace.resolve(name)), name) }
        assertJournal("APPLIED")
    }

    fun rollbackAndAssertRestored() {
        val workspace = requireNotNull(root)
        when (surface) {
            "CLI" -> {
                val rollback = cli(listOf("patch", "rollback", transactionId, "--root", workspace.toString()))
                assertEquals(0, rollback.code, rollback.output)
                assertTrue(rollback.output.contains("Rolled back transaction $transactionId"), rollback.output)
            }
            "daemon" -> {
                val rollback = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", transactionId) }).jsonObject
                assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
            }
            else -> assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", transactionId) }).contains("Rolled back"))
        }
        original.forEach { (name, content) -> assertEquals(content, Files.readString(workspace.resolve(name)), name) }
        assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
        assertJournal("ROLLED_BACK")
        assertEquals(privateBefore, privateDirectories())
    }

    private fun assertJournal(state: String) {
        val directory = requireNotNull(root).resolve(".refactorkit/transactions")
        val records = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.toList() }
        assertEquals(listOf(directory.resolve("$transactionId.json")), records)
        val record = Json.parseToJsonElement(Files.readString(records.single())).jsonObject
        assertEquals(state, record.getValue("state").jsonPrimitive.content)
        assertEquals("renameCSymbolPrefix", record.getValue("operation").jsonPrimitive.content)
        val transaction = record.getValue("transaction").jsonObject
        assertEquals(transactionId, transaction.getValue("id").jsonPrimitive.content)
        if (surface != "CLI") assertEquals(planId, transaction.getValue("planId").jsonPrimitive.content)
        val approval = transaction.getValue("approval").jsonObject
        assertTrue(approval.getValue("surface").jsonPrimitive.content.isNotBlank())
        assertTrue(approval.getValue("kind").jsonPrimitive.content.isNotBlank())
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

    private fun call(name: String, arguments: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", arguments)
    }).jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    override fun close() {
        daemon?.close(); mcp?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

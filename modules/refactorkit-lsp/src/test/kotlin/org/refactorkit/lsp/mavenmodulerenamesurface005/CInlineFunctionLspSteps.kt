package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CInlineFunctionLspSteps {
    private val fixture = CInlineFunctionLspFixture()

    @Given("a managed C function-inline LSP workspace captures {string} and {string}")
    fun capture(standard: String, condition: String) = fixture.capture(standard, condition)

    @When("the LSP client executes the managed C function-inline commands")
    fun execute() = fixture.execute()

    @Then("the managed C function-inline LSP outcome is {string}")
    fun outcome(expected: String) = fixture.assertOutcome(expected)

    @Then("the managed C function-inline LSP inputs and compiler material are restored")
    fun restored() = fixture.assertRestored()

    @After
    fun cleanup() = fixture.close()
}

private class CInlineFunctionLspFixture : AutoCloseable {
    private var root: Path? = null
    private var session: LspSession? = null
    private lateinit var sdk: Path
    private lateinit var condition: String
    private lateinit var original: String
    private lateinit var database: String
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var privateBefore: Set<Path>
    private var outcome = ""
    private var refusal: JsonRpcException? = null
    private val expected = "int main(void) { int left = 3; int right = 4; return 2 * (left + right); }\n"

    fun capture(standard: String, case: String) {
        require(standard in setOf("c11", "c17") && case in setOf("pure local reads", "argument effect", "database changed"))
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-inline-lsp-").also { root = it }
        original = "static int add(int a, int b) { return a + b; }\n" +
            "int main(void) { int left = 3; int right = 4; return 2 * add(" +
            (if (condition == "argument effect") "left++, right" else "left, right") + "); }\n"
        Files.writeString(workspace.resolve("main.c"), original)
        sdk = Path.of(assertNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        session = LspSession().also { it.dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            put("capabilities", buildJsonObject { put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            }) })
        }) }
        privateBefore = privateDirectories()
    }

    fun execute() {
        val workspace = requireNotNull(root)
        val preview: JsonObject
        try {
            preview = command("refactorkit.c.preview", buildJsonObject {
                put("operation", "inlineFunction")
                put("clang", sdk.resolve("usr/bin/clang-22").toString())
                put("clangd", sdk.resolve("usr/bin/clangd").toString())
                put("clangFormat", sdk.resolve("usr/bin/clang-format").toString())
                put("arguments", buildJsonObject { put("file", "main.c"); put("symbol", "add") })
            })
        } catch (rejected: JsonRpcException) {
            refusal = rejected; outcome = "effects refused"; return
        }
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals("inlineFunction", preview.getValue("operation").jsonPrimitive.content)
        assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
        assertFalse("changes" in preview || "documentChanges" in preview, "The preview must be a managed plan, not an editor-applied edit")
        assertTrue(preview.getValue("renderedDiff").jsonPrimitive.content.contains("(left + right)"))
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + " \n")
        val applied: JsonObject
        try {
            applied = command("refactorkit.applyPlan", buildJsonObject { put("planId", preview.getValue("planId")) })
        } catch (rejected: JsonRpcException) {
            refusal = rejected; outcome = "changed input refused"; return
        }
        assertEquals("pure local reads", condition, "A non-admitted plan must not be applied")
        val transaction = applied.getValue("transactionId").jsonPrimitive.content
        assertTrue(transaction.isNotBlank())
        assertEquals(expected, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        val rollback = command("refactorkit.rollback", buildJsonObject { put("transactionId", transaction) })
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(transaction, rollback.getValue("transactionId").jsonPrimitive.content)
        outcome = "applied and rolled back"
    }

    fun assertOutcome(expected: String) {
        assertEquals(expected, outcome, refusal?.message)
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        if (expected == "applied and rolled back") {
            assertEquals(null, refusal)
            assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        } else {
            val rejected = assertNotNull(refusal)
            if (expected == "effects refused") {
                assertEquals("argument effect", condition)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, rejected.code)
                assertTrue(rejected.data.toString().contains("c.inlineFunction.unsupportedSemantics"), rejected.data.toString())
            } else {
                assertEquals("database changed", condition)
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, rejected.code)
                assertTrue(rejected.message.orEmpty().contains("changed since preview"), rejected.message)
            }
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
        if (condition == "database changed") {
            assertEquals(database + " \n", Files.readString(workspace.resolve("compile_commands.json")))
            Files.writeString(workspace.resolve("compile_commands.json"), database)
        }
    }

    fun assertRestored() {
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun command(name: String, arguments: JsonObject): JsonObject = requireNotNull(session)
        .dispatch("workspace/executeCommand", buildJsonObject {
            put("command", name); put("arguments", JsonArray(listOf(arguments)))
        }).jsonObject

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    override fun close() {
        session?.dispatch("shutdown", null)
        session?.dispatch("exit", null)
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

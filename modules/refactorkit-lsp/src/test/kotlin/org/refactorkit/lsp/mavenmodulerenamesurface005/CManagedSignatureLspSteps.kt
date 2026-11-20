package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CManagedSignatureLspSteps {
    private var fixture: SignatureLspFixture? = null

    @Given("a managed LSP C signature fixture uses {string} and {string}")
    fun prepare(standard: String, operation: String) { fixture = SignatureLspFixture(standard, operation) }

    @When("the LSP client issues the C signature lifecycle with {string}")
    fun execute(input: String) = requireNotNull(fixture).execute(input)

    @Then("the managed C signature response is {string}")
    fun verify(expected: String) = requireNotNull(fixture).verify(expected)

    @And("the managed C signature workspace has its original content and expected transaction state")
    fun workspace() = requireNotNull(fixture).workspace()

    @After
    fun close() { fixture?.close(); fixture = null }
}

private class SignatureLspFixture(standard: String, private val operation: String) : AutoCloseable {
    private val workspace = Files.createTempDirectory("rk-c-signature-lsp-")
    private val source = workspace.resolve("main.c")
    private val database = workspace.resolve("compile_commands.json")
    private val clangBin = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val session = LspSession()
    private val original: String
    private val expected: String
    private val databaseImage: String
    private var preview: JsonObject? = null
    private var rollback: JsonObject? = null
    private var applied: JsonObject? = null
    private var rejection: JsonRpcException? = null

    init {
        require(standard in setOf("c11", "c17"))
        val prefix = "int required[REQUIRED - 16];\n"
        when (operation) {
            "addParameter" -> {
                original = prefix + "static int weighted(int x, int y);\n" +
                    "static int weighted(int a, int b) { return a * 10 + b; }\n" +
                    "int main(void) { return weighted(9, 8); }\n"
                expected = prefix + "static int weighted(int x, int limit, int y);\n" +
                    "static int weighted(int a, int limit, int b) { return a * 10 + b; }\n" +
                    "int main(void) { return weighted(9, (7), 8); }\n"
            }
            "reorderParameters" -> {
                original = prefix + "static int weighted(int x, int y, int z);\n" +
                    "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
                    "int main(void) { return weighted(9, 8, 7); }\n"
                expected = prefix + "static int weighted(int z, int y, int x);\n" +
                    "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }\n" +
                    "int main(void) { return weighted(7, 8, 9); }\n"
            }
            else -> error("Unexpected fixture operation: $operation")
        }
        Files.writeString(source, original)
        databaseImage = buildJsonArray {
            add(buildJsonObject {
                put("directory", workspace.toString())
                put("file", "main.c")
                putJsonArray("arguments") {
                    listOf(clangBin.resolve("clang-22").toString(), "-std=$standard", "-DREQUIRED=17", "-c", "main.c").forEach { add(it) }
                }
            })
        }.toString()
        Files.writeString(database, databaseImage)
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", workspace.toUri().toString())
            putJsonObject("capabilities") { putJsonObject("workspace") { putJsonObject("workspaceEdit") { put("documentChanges", true) } } }
        })
    }

    fun execute(input: String) {
        val request = buildJsonObject {
            put("operation", operation)
            put("clang", clangBin.resolve("clang-22").toString())
            put("clangd", clangBin.resolve("clangd").toString())
            put("clangFormat", clangBin.resolve("clang-format").toString())
            putJsonObject("arguments") {
                put("file", "main.c")
                put("symbol", "weighted")
                if (operation == "addParameter") {
                    put("parameterName", "limit")
                    put("parameterOrdinal", if (input == "valid") "1" else "invalid")
                    put("callerExpression", "7")
                } else {
                    put("originalIndices", if (input == "valid") "2,1,0" else "2,invalid,0")
                }
            }
        }
        try {
            preview = command("refactorkit.c.preview", request)
        } catch (error: JsonRpcException) {
            rejection = error
            return
        }
        assertEquals(original, Files.readString(source))
        assertEquals(databaseImage, Files.readString(database))
        val plan = requireNotNull(preview)
        val edit = command("refactorkit.applyPlan", buildJsonObject { put("planId", plan.getValue("planId")) })
        applied = edit
        assertEquals(expected, Files.readString(source))
        assertEquals(databaseImage, Files.readString(database))
        rollback = command("refactorkit.rollback", buildJsonObject { put("transactionId", edit.getValue("transactionId")) })
    }

    fun verify(expected: String) {
        if (expected != "applied and rolled back") {
            assertNull(preview)
            assertNull(applied)
            assertNull(rollback)
            val refusal = assertNotNull(rejection)
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refusal.code)
            assertEquals(expected, refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
            return
        }
        val plan = assertNotNull(preview)
        assertFalse("changes" in plan || "documentChanges" in plan)
        val edits = assertNotNull(applied)
        assertTrue(edits.getValue("transactionId").jsonPrimitive.content.isNotBlank())
        val undone = assertNotNull(rollback)
        assertEquals("rolledBack", undone.getValue("status").jsonPrimitive.content)
        assertEquals(edits.getValue("transactionId"), undone.getValue("transactionId"))
    }

    fun workspace() {
        assertEquals(original, Files.readString(source))
        assertEquals(databaseImage, Files.readString(database))
        assertEquals(applied != null, Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
    }

    private fun command(name: String, request: JsonObject) =
        session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", name)
            put("arguments", JsonArray(listOf(request)))
        }).jsonObject

    override fun close() {
        try {
            session.dispatch("shutdown", null)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }
}

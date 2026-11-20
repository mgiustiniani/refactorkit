package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CManagedFormatLspSteps {
    private var fixture: ManagedFormatLspFixture? = null

    @Given("the LSP C formatting workspace captures {string} and LLVM style")
    fun prepare(standard: String) {
        fixture = ManagedFormatLspFixture(standard)
    }

    @When("the client requests C formatting with {string} and {string}")
    fun format(selection: String, style: String) = requireNotNull(fixture).format(selection, style)

    @Then("the managed C formatting result is {string}")
    fun result(outcome: String) = requireNotNull(fixture).verify(outcome)

    @And("the C formatting workspace retains its expected source and transaction history")
    fun workspace() = requireNotNull(fixture).verifyWorkspace()

    @After
    fun teardown() {
        fixture?.close()
        fixture = null
    }
}

private class ManagedFormatLspFixture(standard: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-c-format-lsp-")
    private val source = root.resolve("main.c")
    private val styleFile = root.resolve(".clang-format")
    private val database = root.resolve("compile_commands.json")
    private val journal = root.resolve(".refactorkit/transactions")
    private val tools = Path.of(requireNotNull(System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN"))).resolve("usr/bin")
    private val session = LspSession()
    private val original = "int add(int a,int b){return a+b;}\nint main(void){return add(1,2)!=3;}\n"
    private val originalStyle = "BasedOnStyle: LLVM\n"
    private val changedStyle = "BasedOnStyle: LLVM\nIndentWidth: 4\n"
    private val databaseImage = buildJsonArray {
        add(buildJsonObject {
            put("directory", root.toString())
            put("file", "main.c")
            putJsonArray("arguments") {
                listOf(tools.resolve("clang-22").toString(), "-std=$standard", "-c", "main.c").forEach {
                    add(JsonPrimitive(it))
                }
            }
        })
    }.toString()
    private var expectedStyle = originalStyle
    private var preview: JsonObject? = null
    private var transactionId: String? = null
    private var rollback: JsonObject? = null
    private var refusal: JsonRpcException? = null

    init {
        require(standard in setOf("c11", "c17"))
        Files.writeString(source, original)
        Files.writeString(styleFile, originalStyle)
        Files.writeString(database, databaseImage)
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            putJsonObject("capabilities") {}
        })
    }

    fun format(selection: String, style: String) {
        val request = buildJsonObject {
            put("operation", "formatFile")
            put("clang", tools.resolve("clang-22").toString())
            put("clangd", tools.resolve("clangd").toString())
            put("clangFormat", tools.resolve("clang-format").toString())
            putJsonObject("arguments") {
                put("file", "main.c")
                when (selection) {
                    "whole" -> Unit
                    "range" -> put("ranges", "0:0")
                    "invalid" -> put("ranges", "2:2")
                    else -> error("Unknown formatting selection $selection")
                }
            }
        }
        try {
            preview = command("refactorkit.c.preview", request)
            val plan = requireNotNull(preview)
            assertEquals("PREVIEW", plan.getValue("status").jsonPrimitive.content)
            assertEquals(listOf("main.c"), plan.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(plan.getValue("warnings").jsonArray.any {
                it.jsonPrimitive.content.startsWith("c.previewDiagnosticsVerified:")
            })
            assertFalse("changes" in plan || "documentChanges" in plan)
            assertEquals(original, Files.readString(source))
            assertFalse(Files.exists(journal))
            if (style == "changed") {
                expectedStyle = changedStyle
                Files.writeString(styleFile, expectedStyle)
            }
            val applied = command("refactorkit.applyPlan", buildJsonObject { put("planId", plan.getValue("planId")) })
            val id = applied.getValue("transactionId").jsonPrimitive.content
            transactionId = id
            val formattedMain = if (selection == "range") "int main(void){return add(1,2)!=3;}\n"
            else "int main(void) { return add(1, 2) != 3; }\n"
            assertEquals("int add(int a, int b) { return a + b; }\n$formattedMain", Files.readString(source))
            assertEquals(databaseImage, Files.readString(database))
            assertEquals(expectedStyle, Files.readString(styleFile))
            rollback = command("refactorkit.rollback", buildJsonObject { put("transactionId", id) })
        } catch (rejection: JsonRpcException) {
            refusal = rejection
        }
    }

    fun verify(outcome: String) {
        when (outcome) {
            "applied and rolled back" -> {
                assertNull(refusal)
                assertNotNull(preview)
                val id = assertNotNull(transactionId)
                val restored = assertNotNull(rollback)
                assertEquals("rolledBack", restored.getValue("status").jsonPrimitive.content)
                assertEquals(id, restored.getValue("transactionId").jsonPrimitive.content)
            }
            "invalid range refused" -> {
                assertNull(preview)
                assertNull(transactionId)
                val rejected = assertNotNull(refusal)
                assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, rejected.code)
                assertEquals("clang.formatInvalidRange", rejected.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
            }
            "changed style refused" -> {
                assertNotNull(preview)
                assertNull(transactionId)
                val rejected = assertNotNull(refusal)
                assertEquals(JsonRpcErrorCodes.SNAPSHOT_CHANGED, rejected.code)
            }
            else -> error("Unknown formatting outcome $outcome")
        }
    }

    fun verifyWorkspace() {
        assertEquals(original, Files.readString(source))
        assertEquals(databaseImage, Files.readString(database))
        assertEquals(expectedStyle, Files.readString(styleFile))
        assertFalse(Files.exists(root.resolve("main.o")))
        val id = transactionId
        if (id == null) {
            assertFalse(Files.exists(journal))
        } else {
            val record = TransactionLog(journal).listRecordsReadOnly().single()
            assertEquals(id, record.transaction.id.value)
            assertEquals("ROLLED_BACK", record.state.name)
        }
    }

    private fun command(name: String, request: JsonObject) = assertIs<JsonObject>(
        session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", name)
            put("arguments", JsonArray(listOf(request)))
        }),
    )

    override fun close() {
        try {
            session.dispatch("shutdown", null)
        } finally {
            check(root.toFile().deleteRecursively())
        }
    }
}

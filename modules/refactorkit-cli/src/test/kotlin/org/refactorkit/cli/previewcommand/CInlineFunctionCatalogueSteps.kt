package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.mcp.McpSession
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CInlineFunctionCatalogueSteps {
    private val fixture = CInlineCatalogueFixture()

    @Given("a C MCP refactoring catalogue session")
    fun session() = fixture.open()

    @When("the C inline-function catalogue is requested through MCP")
    fun request() = fixture.request()

    @Then("the C function-inline entry declares its captured compiler boundary")
    fun boundary() = fixture.assertBoundary()

    @Then("the C function-inline entry declares its inputs without external-linkage claims")
    fun inputs() = fixture.assertInputs()

    @After
    fun cleanup() = fixture.close()
}

private class CInlineCatalogueFixture : AutoCloseable {
    private var session: McpSession? = null
    private lateinit var entry: String

    fun open() { session = McpSession() }

    fun request() {
        val response = assertNotNull(session).dispatch("tools/call", buildJsonObject {
            put("name", "available_refactorings")
            put("arguments", buildJsonObject { put("languageId", "c") })
        }).jsonObject
        assertFalse(response["isError"]?.jsonPrimitive?.content == "true", response.toString())
        val catalogue = response.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
        entry = catalogue.lineSequence().single { it.startsWith("- inlineFunction:") }
    }

    fun assertBoundary() {
        for (term in listOf("compiler-bound", "static int", "captured C11/C17", "one direct call", "atomic int arguments")) {
            assertTrue(entry.contains(term), "C inline catalogue omits '$term': $entry")
        }
    }

    fun assertInputs() {
        assertTrue(entry.contains("supply file and symbol explicitly"), entry)
        assertFalse(entry.contains("extern"), "External-linkage function inline is unqualified: $entry")
    }

    override fun close() { session?.close() }
}

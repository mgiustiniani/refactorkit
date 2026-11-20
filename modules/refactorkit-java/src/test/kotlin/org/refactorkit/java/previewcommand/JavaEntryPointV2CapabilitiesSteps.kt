package org.refactorkit.java.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.daemon.DaemonSession
import kotlin.test.*

class JavaEntryPointV2CapabilitiesSteps {
    private var fixture: NativeCapabilityFixture? = null
    @Given("a Java entrypoint capability consumer reads the source daemon catalogue")
    fun create() { fixture = NativeCapabilityFixture() }
    @When("the daemon reports its versioned Java entrypoint capabilities")
    fun read() = requireNotNull(fixture).read()
    @Then("v2 capabilities distinguish opt-in native profiles from retained classic flags")
    fun verify() = requireNotNull(fixture).verify()
    @Then("entrypoint capability discovery preserves daemon workspace and request state")
    fun unchanged() = requireNotNull(fixture).unchanged()
    @After
    fun close() { fixture?.close(); fixture = null }
}

private class NativeCapabilityFixture : AutoCloseable {
    private val daemon = DaemonSession()
    private val initial = requireNotNull(runCatching { daemon.dispatch("project.summary", null) }.exceptionOrNull())
    private val children = ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() }
    private lateinit var response: JsonObject
    fun read() { response = daemon.dispatch("server.capabilities", null).jsonObject }
    fun verify() {
        val method = response.getValue("methods").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }
        assertFalse(method.getValue("writesWorkspace").jsonPrimitive.boolean)
        val flags = method.getValue("features").jsonObject
        for (name in listOf("javaEntryPointsV2", "javaEntryPointsV2Inherited", "javaEntryPointsV2Java25", "javaEntryPointsV2SavedSnapshot"))
            assertTrue(flags[name]?.jsonPrimitive?.boolean == true, "Missing source capability: $name")
        assertTrue(flags.getValue("javaEntryPointsClassicDeclared").jsonPrimitive.boolean)
        assertTrue(flags.getValue("javaEntryPointsEnclosingType").jsonPrimitive.boolean)
        assertFalse(flags.getValue("javaEntryPointsInherited").jsonPrimitive.boolean)
        assertFalse(flags.getValue("javaEntryPointsJava25").jsonPrimitive.boolean)
    }
    fun unchanged() {
        assertEquals(response, daemon.dispatch("server.capabilities", null))
        val after = requireNotNull(runCatching { daemon.dispatch("project.summary", null) }.exceptionOrNull())
        assertEquals(initial::class, after::class); assertEquals(initial.message, after.message)
        assertTrue(ProcessHandle.current().descendants().use { it.map(ProcessHandle::pid).toList().toSet() - children }.isEmpty())
    }
    override fun close() = daemon.close()
}

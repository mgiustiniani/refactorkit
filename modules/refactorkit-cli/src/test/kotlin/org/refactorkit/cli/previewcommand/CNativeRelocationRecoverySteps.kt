package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CNativeRelocationRecoverySteps {
    private lateinit var fixture: Path
    private lateinit var case: String

    @Given("the packaged C relocation recovery fixture uses {string}")
    fun prepare(selectedCase: String) {
        case = selectedCase
        fixture = Files.createTempDirectory("rk-native-c-recovery-story-")
    }

    @When("the packaged daemon is interrupted after replacing a C component image")
    fun execute() {
        val root = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
        val runtime = requireNotNull(System.getenv("REFACTORKIT_C_QUERY_CANDIDATE_ROOT"))
        val toolchain = requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain"))
        val output = fixture.resolve("smoke.log")
        val process = ProcessBuilder(
            "python3", root.resolve("scripts/smoke-packaged-c-recovery.py").toString(),
            "--runtime", runtime, "--toolchain", toolchain, "--case", case,
            "--output", fixture.resolve("receipt.json").toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(480, TimeUnit.SECONDS), "Packaged C recovery timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }

    @Then("the published recovery command reports {string} and restores the original snapshot")
    fun verify(expected: String) {
        val receipt = Json.parseToJsonElement(Files.readString(fixture.resolve("receipt.json"))).jsonObject
        assertEquals("PASS_PACKAGED_C_RELOCATION_CRASH_RECOVERY", receipt.getValue("status").jsonPrimitive.content)
        val observed = receipt.getValue("cases").jsonArray.single().jsonObject
        assertEquals(case, observed.getValue("case").jsonPrimitive.content)
        assertEquals("APPLYING", observed.getValue("beforeRecoveryState").jsonPrimitive.content)
        assertEquals("ROLLED_BACK", observed.getValue("afterRecoveryState").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), observed.getValue("explicitRecoveryRequired"))
        assertEquals(JsonPrimitive(true), observed.getValue("originalWorkspaceAndSnapshotRestored"))
        assertEquals(JsonPrimitive(expected == "refusal preserves the foreign file"),
            observed.getValue("foreignEditRefusedAndPreserved"))
    }

    @After
    fun close() {
        if (::fixture.isInitialized) {
            Files.walk(fixture).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

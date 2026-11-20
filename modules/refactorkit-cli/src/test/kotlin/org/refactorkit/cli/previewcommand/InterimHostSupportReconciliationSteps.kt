package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InterimHostSupportReconciliationSteps {
    private lateinit var repository: Path
    private var fixture: Path? = null
    private lateinit var originals: Map<Path, String>
    private lateinit var capturedFixture: Map<Path, String>
    private lateinit var condition: String
    private lateinit var receipt: JsonObject
    private var exit = -1

    @Given("the support reconciler observes {string} in an interim workflow")
    fun workflow(condition: String) {
        this.condition = condition
        repository = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("scripts/verify-v070-roadmap-support-claims.py")) }
        val paths = listOf(".github/workflows/ci.yml", "docs/releases/v0.7.0-plan.md", "docs/releases/v0.7.0-support-matrix.md")
        originals = paths.associate { Path.of(it) to Files.readString(repository.resolve(it)) }
        val root = Files.createTempDirectory("refactorkit-interim-support-"); fixture = root
        originals.forEach { (relative, text) -> Files.createDirectories(root.resolve(relative).parent); Files.writeString(root.resolve(relative), text) }
        val file = root.resolve(".github/workflows/ci.yml")
        val text = Files.readString(file)
        val marker = "  k1-k2-shared-foundations-native:\n"
        val prefix = text.substringBefore(marker) + marker
        val body = text.substringAfter(marker).substringBefore("  k5-move-next-native:")
        val suffix = "  k5-move-next-native:" + text.substringAfter("  k5-move-next-native:")
        val changed = when (condition) {
            "intact Linux scope", "widened support claim" -> body
            "missing source identity" -> body.replace("ref: \${{ github.event.pull_request.head.sha || github.sha }}", "ref: \${{ github.sha }}")
            "missing finalizer" -> body.replace("scripts/finalize-native-k1-k2-shared-foundations.py", "scripts/missing-finalizer.py")
            "foreign interim host" -> body.replace("            gradle: ./gradlew\n", "            gradle: ./gradlew\n          - os: windows-2025\n            platform: windows-x86_64\n            gradle: ./gradlew.bat\n")
            "bypassed prerequisite" -> body.replace("    needs: qualification-scope\n", "")
            else -> error("Unexpected condition: $condition")
        }
        if (condition !in setOf("intact Linux scope", "widened support claim")) assertNotEquals(body, changed)
        Files.writeString(file, prefix + changed + suffix)
        if (condition == "widened support claim") {
            Files.writeString(root.resolve("docs/releases/v0.7.0-support-matrix.md"), originals.getValue(Path.of("docs/releases/v0.7.0-support-matrix.md")) + "\ngeneral Kotlin managed support is qualified\n")
        }
        capturedFixture = files(root)
    }

    @When("support claims are reconciled against that workflow")
    fun reconcile() {
        val process = ProcessBuilder("/usr/bin/python3", "-B", repository.resolve("scripts/verify-v070-roadmap-support-claims.py").toString(), "--repository-root", requireNotNull(fixture).toString())
            .redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(); error("Reconciler did not terminate")
        }
        val output = process.inputStream.bufferedReader().readText()
        exit = process.exitValue(); receipt = Json.parseToJsonElement(output) as JsonObject
    }

    @Then("the reconciliation is {string} without relabelling historical host evidence")
    fun expected(disposition: String) {
        assertEquals(JsonPrimitive(4), receipt["nativeRows"])
        assertEquals(JsonPrimitive("PASSED"), receipt["nativeEvidenceState"])
        assertEquals(JsonPrimitive(false), receipt["nativeParentRowsOpen"])
        when (disposition) {
            "accepted" -> {
                assertEquals(0, exit)
                assertEquals(JsonPrimitive("PASSED"), receipt["status"])
                assertEquals(JsonPrimitive("INTERIM_LINUX"), receipt["prospectiveK1HostScope"])
                assertEquals("[\"linux-x86_64\"]", receipt["prospectiveK1Platforms"].toString())
            }
            "refused" -> {
                assertNotEquals(0, exit)
                assertEquals(JsonPrimitive("FAILED"), receipt["status"])
                val expected = when (condition) {
                    "missing source identity" -> "dedicated K1/K2 native workflow token is missing: exact-source-head-checkout"
                    "missing finalizer" -> "dedicated K1/K2 native workflow token is missing: revision-bound-finalizer"
                    "foreign interim host" -> "interim K1/K2 qualification matrix must select only linux-x86_64"
                    "bypassed prerequisite" -> "interim K1/K2 job must depend on qualification-scope"
                    "widened support claim" -> "support projection contains prohibited claim: general Kotlin managed support is qualified"
                    else -> error("Unexpected refusal condition")
                }
                assertTrue(receipt.getValue("failures").toString().contains(expected), "Required refusal absent: $receipt")
            }
            else -> error("Unexpected disposition: $disposition")
        }
    }

    @Then("reconciliation preserves the original repository and historical records")
    fun preserved() {
        originals.forEach { (relative, text) -> assertEquals(text, Files.readString(repository.resolve(relative))) }
        assertEquals(capturedFixture, files(requireNotNull(fixture)))
    }

    private fun files(root: Path): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { root.relativize(it) to Files.readString(it) }
    }

    @After
    fun cleanup() {
        fixture?.let { root -> Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

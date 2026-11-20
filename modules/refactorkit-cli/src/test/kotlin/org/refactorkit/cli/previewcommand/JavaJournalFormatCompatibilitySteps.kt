package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaJournalFormatCompatibilitySteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var initial: String
    private lateinit var applied: JsonObject
    private var authority: JsonObject? = null
    @Given("a Java format-compatibility workspace using {string} mutation authority")
    fun fixture(kind: String) {
        root = Files.createTempDirectory("rk-journal-format-")
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write("src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner { public Runner() {} }\n")
        session = DaemonSession();initial = open()
        if (kind == "SDK") {
            val c = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", initial);put("sdkHome", "/usr/lib/jvm/java-21-openjdk") }).jsonObject
            assertEquals("available", c.getValue("status").jsonPrimitive.content)
            authority = buildJsonObject { put("sdkHome", "/usr/lib/jvm/java-21-openjdk");put("expectedContextHash", c.getValue("contextHash")) }
        }
    }
    @When("a retained Java rename is applied and its journal is reopened")
    fun apply() {
        val preview = session!!.dispatch("refactor.preview", buildJsonObject { put("operation", "renameClass");put("symbol", "pkg.Runner");put("languageId", "java");put("expectedSnapshotHash", initial);authority?.let { put("javaContext", it) };put("arguments", buildJsonObject { put("newName", "Worker") }) }).jsonObject
        applied = session!!.dispatch("refactor.apply", buildJsonObject { put("planId", preview.getValue("planId"));authority?.let { put("javaContext", it) } }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content);session!!.close();session = DaemonSession();assertEquals(applied.getValue("snapshotHash").jsonPrimitive.content, open())
    }
    @Then("the reopened transaction has schema {string} and {string} authority attributes")
    fun journal(version: String, evidence: String) {
        val id = TransactionId(applied.getValue("transactionId").jsonPrimitive.content);val record = assertNotNull(TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(id))
        assertEquals(version.toInt(), record.schemaVersion)
        assertEquals(evidence == "absent", Json.parseToJsonElement(Files.readString(root.resolve(".refactorkit/transactions/${id.value}.json"))).jsonObject["authorityAttributes"] == null)
    }
    @Then("format-compatible rollback restores the original snapshot")
    fun rollback() {
        val rolled = session!!.dispatch("patch.rollback", buildJsonObject { put("transactionId", applied.getValue("transactionId"));put("expectedSnapshotHash", applied.getValue("snapshotHash"));authority?.let { put("javaContext", it) } }).jsonObject
        assertEquals("rolledBack", rolled.getValue("status").jsonPrimitive.content);assertEquals(initial, rolled.getValue("snapshotHash").jsonPrimitive.content)
        assertTrue(Files.exists(root.resolve("src/main/java/pkg/Runner.java")));assertFalse(Files.exists(root.resolve("src/main/java/pkg/Worker.java")))
    }
    private fun open() = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
    private fun write(path: String, text: String) { val file = root.resolve(path);Files.createDirectories(file.parent);Files.writeString(file, text) }
    @After fun close() { session?.close();if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

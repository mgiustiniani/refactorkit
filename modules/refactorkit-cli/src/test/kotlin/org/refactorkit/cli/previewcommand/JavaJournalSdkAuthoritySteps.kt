package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaJournalSdkAuthoritySteps {
    private lateinit var root: Path
    private var session: DaemonSession? = null
    private lateinit var hash: String
    private lateinit var context: JsonObject
    private var transaction = TransactionId.new()
    private lateinit var appliedBytes: Map<String, String>
    private lateinit var persisted: JsonObject
    @Given("a Java journal fixture with explicit SDK authority")
    fun fixture() {
        root = Files.createTempDirectory("rk-sdk-journal-")
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write("src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner { public Runner() {} }\n")
        write("src/main/java/pkg/Consumer.java", "package pkg; public class Consumer { Runner value = new Runner(); }\n")
        session = DaemonSession();hash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", hash);put("sdkHome", "/usr/lib/jvm/java-21-openjdk") }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
    }
    @When("an SDK rename is applied and its journal is {string} after daemon restart")
    fun apply(journal: String) {
        val authority = buildJsonObject { put("sdkHome", "/usr/lib/jvm/java-21-openjdk");put("expectedContextHash", context.getValue("contextHash")) }
        val preview = session!!.dispatch("refactor.preview", buildJsonObject { put("operation", "renameClass");put("symbol", "pkg.Runner");put("languageId", "java");put("expectedSnapshotHash", hash);put("javaContext", authority);put("arguments", buildJsonObject { put("newName", "Worker") }) }).jsonObject
        val result = session!!.dispatch("refactor.apply", buildJsonObject { put("planId", preview.getValue("planId"));put("javaContext", authority) }).jsonObject
        assertEquals("applied", result.getValue("status").jsonPrimitive.content);transaction = TransactionId(result.getValue("transactionId").jsonPrimitive.content)
        appliedBytes = contents();session!!.close();session = null
        val file = root.resolve(".refactorkit/transactions/${transaction.value}.json");persisted = Json.parseToJsonElement(Files.readString(file)).jsonObject
        if (journal == "tampered") { val attributes = (persisted["authorityAttributes"] as? JsonObject).orEmpty() + ("javaContextHash" to JsonPrimitive("0".repeat(64)));Files.writeString(file, JsonObject(persisted + ("authorityAttributes" to JsonObject(attributes))).toString()) }
    }
    @Then("the SDK authority journal is {string} with original context and platform evidence")
    fun authority(expected: String) {
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        if (expected == "corrupt") {
            val failure = assertFailsWith<TransactionLogException> { log.loadRecord(transaction) }
            assertEquals("transaction.quarantined", failure.code)
            return
        }
        val record = log.loadRecord(transaction)
        assertNotNull(record);assertEquals(9, persisted.getValue("schemaVersion").jsonPrimitive.int)
        val attributes = assertNotNull(persisted["authorityAttributes"], "Durable SDK authority must be present").jsonObject
        assertEquals(context.getValue("contextHash"), attributes["javaContextHash"])
        assertEquals("java-project-context-v1", attributes.getValue("rollbackAuthorityProvider").jsonPrimitive.content)
        assertEquals("/usr/lib/jvm/java-21-openjdk", attributes.getValue("javaSdkHome").jsonPrimitive.content)
        val platforms = context.getValue("sdk").jsonArray.map { it.jsonObject }.sortedBy { it.getValue("sourceRelease").jsonPrimitive.int }
        assertEquals(platforms.joinToString(",") { "${it.getValue("sourceRelease").jsonPrimitive.content}:${it.getValue("identitySha256").jsonPrimitive.content}" }, attributes.getValue("javaPlatformIdentities").jsonPrimitive.content)
        assertEquals(hash, record.transaction.snapshotHashBefore)
    }
    @Then("journal inspection does not change applied source bytes") fun unchanged() { assertEquals(appliedBytes, contents()) }
    private fun write(path: String, text: String) { val file = root.resolve(path);Files.createDirectories(file.parent);Files.writeString(file, text) }
    private fun contents() = Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::root.isInitialized) Files.walk(root).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaRollbackOriginalDiagnosticsSteps {
    private lateinit var owned: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private var session: DaemonSession? = null
    private lateinit var original: Map<String, String>
    private lateinit var committed: Map<String, String>
    private lateinit var originalHash: String
    private lateinit var committedHash: String
    private lateinit var authority: JsonObject
    private lateinit var transactionId: String
    private lateinit var originalDiagnostics: List<Diagnostic>
    private var result: JsonObject? = null
    private var refusal: JsonRpcException? = null

    @Given("an SDK {string} rollback project with original {string} errors")
    fun fixture(version: String, diagnostic: String) {
        owned = Files.createTempDirectory("rk-rollback-baseline-");root = owned.resolve("project");sdk = owned.resolve("sdk")
        val sdkSource = Path.of(if (version == "25") "/home/paperboy/.local/share/graalvm/graalvm-jdk-25-latest" else "/usr/lib/jvm/java-21-openjdk")
        Files.createDirectories(sdk.resolve("lib"));Files.copy(sdkSource.resolve("release"), sdk.resolve("release"))
        listOf("ct.sym", "modules", "jrt-fs.jar").forEach { Files.createSymbolicLink(sdk.resolve("lib/$it"), sdkSource.resolve("lib/$it")) }
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><modules><module>app</module><module>unrelated</module></modules><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        for (module in listOf("app", "unrelated")) write("$module/pom.xml", "<project><modelVersion>4.0.0</modelVersion><parent><groupId>pkg</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>$module</artifactId></project>")
        val secondary = if (diagnostic == "duplicate secondary declaration") "class Peer {}\n" else ""
        val field = if (diagnostic == "unresolved type") " Missing value;\n" else ""
        write("app/src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner {\n$field}\n$secondary")
        if (diagnostic == "duplicate secondary declaration") {
            write("app/src/main/java/pkg/Peer.java", "package pkg;\nclass Peer {}\n")
        }
        write("app/src/main/java/pkg/Consumer.java", "package pkg;\npublic class Consumer { Runner value = new Runner(); }\n")
        write("unrelated/src/main/java/pkg/Peer.java", "package pkg;\npublic class Peer {}\n")
        original = sources();session = DaemonSession();originalHash = open()
        val c = rpc("project.context", buildJsonObject { put("expectedSnapshotHash", originalHash);put("sdkHome", sdk.toString()) })
        assertEquals("available", c.getValue("status").jsonPrimitive.content)
        authority = buildJsonObject { put("sdkHome", sdk.toString());put("expectedContextHash", c.getValue("contextHash")) }
        originalDiagnostics = diagnostics();assertTrue(originalDiagnostics.any { it.severity == Diagnostic.Severity.ERROR })
    }
    @When("its retained rename is applied and restored after {string}")
    fun restore(event: String) {
        val p = rpc("refactor.preview", buildJsonObject { put("operation", "renameClass");put("symbol", "pkg.Runner");put("languageId", "java");put("javaContext", authority);put("expectedSnapshotHash", originalHash);put("arguments", buildJsonObject { put("newName", "Worker") }) })
        val a = rpc("refactor.apply", buildJsonObject { put("planId", p.getValue("planId"));put("javaContext", authority);put("expectedSnapshotHash", originalHash) })
        assertEquals("applied", a.getValue("status").jsonPrimitive.content);transactionId = a.getValue("transactionId").jsonPrimitive.content;committedHash = a.getValue("snapshotHash").jsonPrimitive.content;committed = sources()
        session!!.close();session = DaemonSession();assertEquals(committedHash, open())
        if (event == "SDK drift") Files.writeString(sdk.resolve("release"), Files.readString(sdk.resolve("release")) + "\nBASELINE_DRIFT=true\n")
        try { result = rpc("patch.rollback", buildJsonObject { put("transactionId", transactionId);put("expectedSnapshotHash", committedHash);put("javaContext", if (event == "other context") JsonObject(authority + ("expectedContextHash" to JsonPrimitive("0".repeat(64)))) else authority) }) }
        catch (failure: JsonRpcException) { refusal = failure }
    }
    @Then("original-baseline rollback reports {string}")
    fun outcome(expected: String) {
        if (expected == "rolledBack") {
            assertNull(refusal, refusal?.message);assertEquals(expected, result?.getValue("status")?.jsonPrimitive?.content)
            assertEquals(originalHash, result?.getValue("snapshotHash")?.jsonPrimitive?.content)
            assertEquals(authority.getValue("expectedContextHash"), result?.get("rolledBackContextHash"))
        } else { assertNotNull(refusal);assertEquals(expected, refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content) }
    }
    @Then("rollback preserves the exact source and diagnostic images admitted by the journal")
    fun images() {
        val journal = assertNotNull(TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(TransactionId(transactionId)))
        if (refusal == null) {
            assertEquals(original, sources());assertEquals(JournalState.ROLLED_BACK, journal.state)
            assertEquals(originalDiagnostics, diagnostics())
            val expected = originalDiagnostics.map { listOf(it.code, it.message, it.location?.path?.let(ProtocolPath::serialize), it.severity.name.lowercase()) }
            val actual = result!!.getValue("diagnostics").jsonArray.map { d -> val v=d.jsonObject;listOf(v["code"]?.jsonPrimitive?.content, v["message"]?.jsonPrimitive?.content, v["path"]?.jsonPrimitive?.content, v["severity"]?.jsonPrimitive?.content) }
            assertEquals(expected, actual)
        } else { assertEquals(committed, sources());assertEquals(JournalState.APPLIED, journal.state) }
    }
    private fun diagnostics(): List<Diagnostic> {
        val scanner=org.refactorkit.java.JavaProjectScanner()
        val c=org.refactorkit.java.JavaBoundContext.acquire(org.refactorkit.java.JavaProjectContextRequest(originalHash, sdk, expectedContextHash=authority.getValue("expectedContextHash").jsonPrimitive.content), { scanner.scan(root) })
        val context=(c as org.refactorkit.java.JavaBoundContextResolution.Available).context
        return context.adapter.diagnostics(context.snapshot)
    }
    private fun open() = rpc("project.open", buildJsonObject { put("root", root.toString()) }).getValue("snapshotHash").jsonPrimitive.content
    private fun rpc(method: String, params: JsonObject) = session!!.dispatch(method, params).jsonObject
    private fun write(relative: String, content: String) { val p=root.resolve(relative);Files.createDirectories(p.parent);Files.writeString(p, content) }
    private fun sources() = Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::owned.isInitialized) Files.walk(owned).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

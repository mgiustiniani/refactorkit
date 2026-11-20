package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.*
import kotlinx.serialization.json.*
import org.refactorkit.core.*
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class JavaProtectedSdkRollbackSteps {
    private lateinit var owned: Path
    private lateinit var root: Path
    private lateinit var sdk: Path
    private var session: DaemonSession? = null
    private lateinit var initial: String
    private lateinit var committed: String
    private lateinit var authority: JsonObject
    private var tx = TransactionId.new()
    private lateinit var before: Map<String, String>
    private lateinit var expected: Map<String, String>
    private var response: JsonObject? = null
    private var refusal: JsonRpcException? = null
    private var sdkVersion = "21"
    @Given("a committed Java SDK {string} rename transaction with durable authority")
    fun fixture(version: String) {
        sdkVersion = version
        owned = Files.createTempDirectory("rk-sdk-rollback-");root = owned.resolve("app");sdk = owned.resolve("sdk")
        val originalSdk = Path.of(if (version == "25") "/home/paperboy/.local/share/graalvm/graalvm-jdk-25-latest" else "/usr/lib/jvm/java-21-openjdk")
        Files.createDirectories(root);Files.createDirectories(sdk.resolve("lib"));Files.copy(originalSdk.resolve("release"), sdk.resolve("release"))
        for (entry in listOf("ct.sym", "modules", "jrt-fs.jar")) Files.createSymbolicLink(sdk.resolve("lib/$entry"), originalSdk.resolve("lib/$entry"))
        write("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>")
        write("src/main/java/pkg/Runner.java", "package pkg;\npublic class Runner { public Runner() {} }\n")
        write("src/main/java/pkg/Consumer.java", "package pkg; public class Consumer { Runner value = new Runner(); }\n")
        before = contents();session = DaemonSession();initial = open()
        val context = session!!.dispatch("project.context", buildJsonObject { put("expectedSnapshotHash", initial);put("sdkHome", sdk.toString()) }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
        authority = buildJsonObject { put("sdkHome", sdk.toString());put("expectedContextHash", context.getValue("contextHash")) }
        val preview = session!!.dispatch("refactor.preview", buildJsonObject { put("operation", "renameClass");put("symbol", "pkg.Runner");put("languageId", "java");put("expectedSnapshotHash", initial);put("javaContext", authority);put("arguments", buildJsonObject { put("newName", "Worker") }) }).jsonObject
        val applied = session!!.dispatch("refactor.apply", buildJsonObject { put("planId", preview.getValue("planId"));put("javaContext", authority) }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content);tx = TransactionId(applied.getValue("transactionId").jsonPrimitive.content);committed = applied.getValue("snapshotHash").jsonPrimitive.content;expected = contents()
    }
    @When("protected SDK rollback is requested after {string}")
    fun rollback(event: String) {
        session!!.close();session = DaemonSession();assertEquals(committed, open())
        if (event == "SDK drift") Files.writeString(sdk.resolve("release"), Files.readString(sdk.resolve("release")) + "\nSDK_DRIFT=true\n")
        if (event == "build drift") { write("pom.xml", Files.readString(root.resolve("pom.xml")).replace("<version>1</version>", "<version>2</version>"));expected = contents() }
        try { response = session!!.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", tx.value);put("expectedSnapshotHash", committed)
            if (event != "missing context") put("javaContext", if (event == "other context") JsonObject(authority + ("expectedContextHash" to JsonPrimitive("0".repeat(64)))) else authority)
        }).jsonObject } catch (failure: JsonRpcException) { refusal = failure }
    }
    @Then("SDK rollback reports {string} with exact snapshot authority")
    fun outcome(expectedOutcome: String) {
        if (expectedOutcome == "rolledBack") {
            assertNull(refusal, refusal?.message);assertEquals(expectedOutcome, response?.getValue("status")?.jsonPrimitive?.content)
            assertEquals(initial, response?.getValue("snapshotHash")?.jsonPrimitive?.content)
            assertEquals(authority.getValue("expectedContextHash"), response?.get("rolledBackContextHash"))
        } else { assertNotNull(refusal, "SDK rollback must refuse authority drift");assertEquals(expectedOutcome, refusal!!.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content);assertNull(response) }
    }
    @Then("restored or refused rollback preserves the expected source and journal images")
    fun images() {
        val record = assertNotNull(TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(tx))
        if (refusal == null) {
            assertEquals(before, contents());assertEquals(JournalState.ROLLED_BACK, record.state)
            if (sdkVersion == "21") currencyFaults()
        }
        else { assertEquals(expected, contents());assertEquals(JournalState.APPLIED, record.state) }
    }
    private fun currencyFaults() {
        val checkRoot = owned.resolve("faults");Files.createDirectories(checkRoot)
        before.forEach { (relative, content) ->
            val file = checkRoot.resolve(relative);Files.createDirectories(file.parent)
            Files.writeString(file, if (relative.endsWith("Runner.java")) content.replace("public class", "import java.util.Set;\npublic class") else content)
        }
        val scanner = org.refactorkit.java.JavaProjectScanner();val saved = scanner.scan(checkRoot)
        val acquired = org.refactorkit.java.JavaBoundContext.acquire(org.refactorkit.java.JavaProjectContextRequest(saved.hash, sdk), { scanner.scan(checkRoot) })
        val context = (acquired as org.refactorkit.java.JavaBoundContextResolution.Available).context
        val raw = org.refactorkit.java.JavaOrganizeImportsPlanner().previewSingleFile(context.snapshot, Path.of("src/main/java/pkg/Runner.java"))
        val plan = context.attestPreview(raw) { scanner.scan(checkRoot) }
        val applied = PatchEngine(checkRoot).apply(plan.plan, saved, ApplyAuthorization.explicit("sdk-rollback-story"), plan.diagnosticsGate)
        assertIs<ApplyResult.Applied>(applied)
        val committed = scanner.scan(checkRoot);val request = org.refactorkit.java.JavaProjectContextRequest(committed.hash, sdk, expectedContextHash = context.view.contextHash)
        val guard: (TransactionJournalRecord) -> RollbackAuthorityResolution = { record -> org.refactorkit.java.JavaBoundContext.rollbackAuthority(record, request) { scanner.scan(checkRoot) } }
        val unqualified = PatchEngine(checkRoot).rollback(applied.transaction)
        assertIs<ApplyResult.Refused>(unqualified);assertEquals("rollback.authorityRequired", unqualified.diagnostics.single().code)
        val forced = PatchEngine(checkRoot).rollback(applied.transaction, RollbackMode.FORCE, guard)
        assertIs<ApplyResult.Refused>(forced);assertEquals("rollback.forceAuthorityUnsupported", forced.diagnostics.single().code)
        val release = Files.readString(sdk.resolve("release"))
        try {
            val engine = PatchEngine(checkRoot, faultInjector = PatchFaultInjector { point, _, _ ->
                if (point == PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) Files.writeString(sdk.resolve("release"), release + "\nROLLBACK_LOCK_DRIFT=true\n")
            })
            val refused = engine.rollback(applied.transaction, authorityProvider = guard)
            assertIs<ApplyResult.Refused>(refused);assertEquals("java.context.contextStale", refused.diagnostics.single().code)
            assertEquals(committed.files, scanner.scan(checkRoot).files)
            assertEquals(JournalState.APPLIED, TransactionLog(checkRoot.resolve(".refactorkit/transactions")).loadRecord(applied.transaction.id)?.state)
        } finally { Files.writeString(sdk.resolve("release"), release) }
        var commits = 0
        try {
            val engine = PatchEngine(checkRoot, faultInjector = PatchFaultInjector { point, _, _ ->
                if (point == PatchFaultPoint.AFTER_COMMITTED_IMAGE) {
                    commits++;Files.writeString(sdk.resolve("release"), if (commits == 1) release + "\nROLLBACK_POST_DRIFT=true\n" else release)
                }
            })
            val refused = engine.rollback(applied.transaction, authorityProvider = guard)
            assertIs<ApplyResult.Refused>(refused);assertTrue(refused.diagnostics.any { it.code == "authoritative.postApplyUnavailable" })
            assertEquals(2, commits);assertEquals(committed.files, scanner.scan(checkRoot).files)
            assertEquals(JournalState.APPLIED, TransactionLog(checkRoot.resolve(".refactorkit/transactions")).loadRecord(applied.transaction.id)?.state)
        } finally { Files.writeString(sdk.resolve("release"), release) }
        assertIs<ApplyResult.Applied>(PatchEngine(checkRoot).rollback(applied.transaction, authorityProvider = guard))
        assertEquals(saved.hash, scanner.scan(checkRoot).hash)
    }
    private fun open() = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
    private fun write(path: String, text: String) { val file = root.resolve(path);Files.createDirectories(file.parent);Files.writeString(file, text) }
    private fun contents() = Files.walk(root).use { p -> p.filter { Files.isRegularFile(it) && (it.toString().endsWith(".java") || it.fileName.toString() == "pom.xml") }.toList().associate { root.relativize(it).toString() to Files.readString(it) } }
    @After fun close() { session?.close();if (::owned.isInitialized) Files.walk(owned).use { p -> p.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
}

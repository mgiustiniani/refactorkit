package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaBatchFormatterProfileSteps {
    private val fixture = JavaBatchFormatterProfileFixture()

    @Given("two saved Java files and a selected Eclipse profile for cleanup case {string}")
    fun capture(case: String) = fixture.capture(case)

    @When("the daemon previews that cleanup batch and receives {string} profile approval")
    fun execute(approval: String) = fixture.execute(approval)

    @Then("the cleanup batch returns {string} with profile identity and exact saved images")
    fun result(expected: String) = fixture.assertResult(expected)

    @Then("the cleanup batch preserves other inputs and expected transaction history")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class JavaBatchFormatterProfileFixture : AutoCloseable {
    private var root: Path? = null
    private var session: GradleContextRpcSession? = null
    private lateinit var case: String
    private lateinit var snapshot: String
    private lateinit var context: JsonObject
    private lateinit var profileHash: String
    private lateinit var profileXml: String
    private lateinit var outcome: String
    private var preview: JsonObject? = null
    private var transaction: String? = null
    private var appliedSnapshot: String? = null
    private var restored = false
    private val a = "src/main/java/sample/A.java"
    private val b = "src/main/java/sample/B.java"
    private val profilePath = "profiles/eclipse-ide.xml"
    private val profileName = "IDE batch selected profile"
    private val validXml = "<profiles version=\"23\"><profile kind=\"CodeFormatterProfile\" name=\"IDE batch selected profile\" version=\"23\">" +
        "<setting id=\"org.eclipse.jdt.core.formatter.tabulation.char\" value=\"space\"/>" +
        "<setting id=\"org.eclipse.jdt.core.formatter.tabulation.size\" value=\"2\"/>" +
        "<setting id=\"org.eclipse.jdt.core.formatter.indentation.size\" value=\"2\"/></profile></profiles>"
    private val originalA = "package sample;\npublic class A{void run(){}}\n"
    private val originalB = "package sample;\npublic class B{void work(){}}\n"
    private val importsB = "package sample;\n\nimport java.util.List;\nimport java.util.ArrayList;\n\n" +
        "public class B {\n        List<String> values = new ArrayList<>();\n}\n"
    private val formattedA = "package sample;\n\npublic class A {\n  void run() {\n  }\n}\n"
    private val formattedB = "package sample;\n\npublic class B {\n  void work() {\n  }\n}\n"
    private val organizedB = "package sample;\n\nimport java.util.ArrayList;\nimport java.util.List;\n\n" +
        "public class B {\n        List<String> values = new ArrayList<>();\n}\n"
    private lateinit var original: Map<String, String>

    fun capture(selectedCase: String) {
        require(selectedCase in setOf("two formatters", "format plus imports", "invalid XML", "organizers only"))
        case = selectedCase
        val workspace = Files.createTempDirectory("refactorkit-batch-profile-").also { root = it }
        original = linkedMapOf(a to originalA, b to if (case == "format plus imports") importsB else originalB,
            "pom.xml" to "<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>batch</artifactId>" +
                "<version>1</version><properties><maven.compiler.release>17</maven.compiler.release></properties></project>\n",
            ".settings/org.eclipse.jdt.core.prefs" to "org.eclipse.jdt.core.formatter.tabulation.char=space\n" +
                "org.eclipse.jdt.core.formatter.tabulation.size=8\norg.eclipse.jdt.core.formatter.indentation.size=8\n")
        original.forEach { (path, text) ->
            val file = workspace.resolve(path); Files.createDirectories(file.parent); Files.writeString(file, text)
        }
        profileXml = if (case == "invalid XML") "<profiles" else validXml
        profileHash = MessageDigest.getInstance("SHA-256").digest(
            listOf("java-formatter-profile-v1", profilePath, profileName, profileXml).joinToString("\u0000").toByteArray(),
        ).joinToString("") { "%02x".format(it) }
        val rpc = newGradleContextRpcSession().also { session = it }
        snapshot = rpc.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val sdk = Path.of(System.getenv("REFACTORKIT_JAVA_BATCH_SDK_HOME")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.home")).toRealPath()
        val view = rpc.dispatch("project.context", buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedSnapshotHash", snapshot)
        }).jsonObject
        assertEquals("available", view.getValue("status").jsonPrimitive.content, view.toString())
        val selectedSdk = view.getValue("sdk").jsonArray.single().jsonObject
        assertEquals(sdk, Path.of(selectedSdk.getValue("home").jsonPrimitive.content).toRealPath())
        fun hash(path: Path) = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
        assertEquals(hash(sdk.resolve("release")), selectedSdk.getValue("releaseMetadataSha256").jsonPrimitive.content)
        assertEquals(hash(sdk.resolve("lib/ct.sym")), selectedSdk.getValue("signatureArchiveSha256").jsonPrimitive.content)
        assertEquals("17", selectedSdk.getValue("sourceRelease").jsonPrimitive.content)
        context = buildJsonObject {
            put("sdkHome", sdk.toString()); put("expectedContextHash", view.getValue("contextHash"))
        }
    }

    fun execute(approval: String) {
        require(approval in setOf("matching", "missing", "changed"))
        val rpc = requireNotNull(session)
        try {
            val result = rpc.dispatch("refactor.preview", buildJsonObject {
                put("languageId", "java"); put("operation", "batchCleanup")
                put("expectedSnapshotHash", snapshot); put("javaContext", context)
                put("formatterProfile", buildJsonObject {
                    put("path", profilePath); put("profileName", profileName); put("xml", profileXml)
                })
                put("arguments", buildJsonObject {
                    put("actions", buildJsonArray {
                        fun action(operation: String, file: String) = add(buildJsonObject {
                            put("operation", operation); put("arguments", buildJsonObject { put("file", file) })
                        })
                        action(if (case == "organizers only") "organizeImports" else "formatFile", a)
                        action(if (case in setOf("format plus imports", "organizers only")) "organizeImports" else "formatFile", b)
                    })
                })
            }).jsonObject.also { preview = it }
            assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content)
            original.forEach { (path, text) -> assertEquals(text, Files.readString(requireNotNull(root).resolve(path))) }
            assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
            val applied = rpc.dispatch("refactor.apply", buildJsonObject {
                put("planId", result.getValue("planId"))
                put("expectedSnapshotHash", snapshot)
                put("javaContext", context)
                when (approval) {
                    "matching" -> put("formatterProfileSha256", profileHash)
                    "changed" -> put("formatterProfileSha256", "0".repeat(64))
                }
            }).jsonObject
            assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
            transaction = applied.getValue("transactionId").jsonPrimitive.content
            appliedSnapshot = applied.getValue("snapshotHash").jsonPrimitive.content
            outcome = "applied and rolled back"
        } catch (failure: JsonRpcException) {
            outcome = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: "rpc.${failure.code}"
        }
    }

    fun assertResult(expected: String) {
        assertEquals(expected, outcome)
        assertContractMetadata(expected)
        val workspace = requireNotNull(root)
        preview?.let { result ->
            assertEquals(profilePath, result.getValue("formatterProfilePath").jsonPrimitive.content)
            assertEquals(profileName, result.getValue("formatterProfileName").jsonPrimitive.content)
            assertEquals(profileHash, result.getValue("formatterProfileSha256").jsonPrimitive.content)
            assertEquals(snapshot, result.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
            assertEquals(context.getValue("expectedContextHash"), result.getValue("contextHash"))
        }
        if (expected == "applied and rolled back") {
            assertNotNull(preview)
            assertEquals(formattedA, Files.readString(workspace.resolve(a)))
            assertEquals(if (case == "format plus imports") organizedB else formattedB, Files.readString(workspace.resolve(b)))
            val result = requireNotNull(session).dispatch("patch.rollback", buildJsonObject {
                put("transactionId", requireNotNull(transaction))
                put("expectedSnapshotHash", requireNotNull(appliedSnapshot))
                put("javaContext", context)
            }).jsonObject
            assertEquals("rolledBack", result.getValue("status").jsonPrimitive.content)
            assertEquals(snapshot, result.getValue("snapshotHash").jsonPrimitive.content)
            assertEquals(context.getValue("expectedContextHash"), result.getValue("rolledBackContextHash"))
            restored = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(TransactionId(requireNotNull(transaction)))).state)
        } else {
            assertEquals(null, transaction)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        original.forEach { (path, text) -> assertEquals(text, Files.readString(workspace.resolve(path))) }
        assertFalse(Files.exists(workspace.resolve(profilePath)))
        assertEquals(restored, transaction != null)
    }

    private fun assertContractMetadata(expected: String) {
        val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("docs/api-0.2-contract.json")) }
        val document = Json.parseToJsonElement(Files.readString(project.resolve("docs/api-0.2-contract.json"))).jsonObject
        assertEquals("0.2", document.getValue("apiVersion").jsonPrimitive.content)
        val api = document.getValue("daemon").jsonObject.getValue("javaContextRefactoring").jsonObject
        val profile = assertNotNull(api["formatterProfileContract"], "API metadata must declare the selected formatter-profile wire contract").jsonObject
        fun strings(key: String) = profile.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        assertEquals("formatterProfile", profile.getValue("optionalRequestField").jsonPrimitive.content)
        assertEquals(listOf("path", "profileName", "xml"), strings("requestFields"))
        assertEquals(listOf("formatFile", "batchCleanup"), strings("savedOperations"))
        assertEquals(listOf("formatFile"), strings("editorOperations"))
        assertEquals("formatterProfileSha256", profile.getValue("applyApprovalField").jsonPrimitive.content)
        assertEquals("true", profile.getValue("snapshotIndependent").jsonPrimitive.content)
        assertEquals("false", profile.getValue("writesSelectedProfileFile").jsonPrimitive.content)
        val responseFields = strings("responseFields")
        assertEquals(listOf("formatterProfilePath", "formatterProfileName", "formatterProfileSha256"), responseFields)
        preview?.let { result -> responseFields.forEach { assertTrue(it in result, "Missing advertised response field: $it") } }
        val batch = profile.getValue("batch").jsonObject
        assertEquals(listOf("formatFile"), batch.getValue("appliesToChildren").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", batch.getValue("requiresFormatFileAction").jsonPrimitive.content)
        if (case == "organizers only") assertEquals(expected, batch.getValue("unusedProfileRefusal").jsonPrimitive.content)
        if (case == "invalid XML") assertEquals(expected, batch.getValue("invalidConstituentRefusal").jsonPrimitive.content)
        val refusals = profile.getValue("approvalRefusals").jsonObject
        if (expected == "java.formatterProfile.approvalMissing") assertEquals(expected, refusals.getValue("missing").jsonPrimitive.content)
        if (expected == "java.formatterProfile.identityStale") assertEquals(expected, refusals.getValue("changed").jsonPrimitive.content)
    }

    override fun close() {
        session?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

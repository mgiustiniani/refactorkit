package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JavaFormatterProfileSteps {
    private lateinit var root: Path
    private lateinit var source: Path
    private lateinit var settings: Path
    private lateinit var session: DaemonSession
    private lateinit var originalSource: String
    private lateinit var originalSettings: String
    private lateinit var snapshotHash: String
    private lateinit var formatterProfileSha256: String
    private lateinit var previewResponse: JsonObject
    private lateinit var owned: Path
    private lateinit var sdk: Path
    private lateinit var editorContext: JsonObject
    private lateinit var dirtySource: String
    private var dirtyVersion = 1L

    private val profilePath = "profiles/eclipse-ide.xml"
    private val profileName = "IDE selected profile"
    private val profileXml = """<?xml version="1.0" encoding="UTF-8"?>
        <profiles version="23">
          <profile kind="CodeFormatterProfile" name="IDE selected profile" version="23">
            <setting id="org.eclipse.jdt.core.formatter.tabulation.char" value="space"/>
            <setting id="org.eclipse.jdt.core.formatter.tabulation.size" value="2"/>
            <setting id="org.eclipse.jdt.core.formatter.indentation.size" value="2"/>
          </profile>
        </profiles>
    """.trimIndent()

    @Given("a Java workspace and an Eclipse profile selected in the IDE")
    fun openWorkspaceAndProfile() {
        root = Files.createTempDirectory("refactorkit-java-formatter-profile-")
        source = root.resolve("src/main/java/sample/Example.java")
        Files.createDirectories(source.parent)
        originalSource = "package sample;\npublic class Example{void run(){}}\n"
        source.writeText(originalSource)
        settings = root.resolve(".settings/org.eclipse.jdt.core.prefs")
        Files.createDirectories(settings.parent)
        originalSettings = "org.eclipse.jdt.core.formatter.tabulation.char=space\n" +
            "org.eclipse.jdt.core.formatter.tabulation.size=8\n" +
            "org.eclipse.jdt.core.formatter.indentation.size=8\n"
        settings.writeText(originalSettings)
        session = DaemonSession()
        snapshotHash = session.dispatch("project.open", buildJsonObject {
            put("root", root.toString())
        }).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        formatterProfileSha256 = profileIdentitySha256(profilePath, profileName, profileXml)
    }

    @When("the saved Java formatter previews using the selected Eclipse profile")
    fun previewSavedFormatting() {
        previewResponse = preview()
    }

    @Then("the saved preview exposes the exact profile identity separately from the project snapshot")
    fun assertProfileIdentity() {
        assertEquals("PREVIEW", previewResponse.getValue("status").jsonPrimitive.content)
        assertEquals(snapshotHash, previewResponse.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        assertNotEquals(snapshotHash, formatterProfileSha256)
        assertEquals(profilePath, previewResponse.getValue("formatterProfilePath").jsonPrimitive.content)
        assertEquals(profileName, previewResponse.getValue("formatterProfileName").jsonPrimitive.content)
        assertEquals(formatterProfileSha256, previewResponse.getValue("formatterProfileSha256").jsonPrimitive.content)
        assertTrue(previewResponse.getValue("renderedDiff").jsonPrimitive.content.contains("+  void run() {"))
        assertEquals(originalSource, Files.readString(source))
        assertEquals(originalSettings, Files.readString(settings))
    }

    @Then("managed apply refuses missing or changed profile approval and accepts the matching hash")
    fun applyRequiresMatchingProfileIdentity() {
        assertApplyRefused(previewResponse, null, "java.formatterProfile.approvalMissing")
        assertEquals(originalSource, Files.readString(source))

        previewResponse = preview()
        assertApplyRefused(previewResponse, "0".repeat(64), "java.formatterProfile.identityStale")
        assertEquals(originalSource, Files.readString(source))

        previewResponse = preview()
        val applied = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", previewResponse.getValue("planId").jsonPrimitive.content)
            put("formatterProfileSha256", formatterProfileSha256)
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertTrue(Files.readString(source).contains("  void run() {"))
        assertEquals(originalSettings, Files.readString(settings), "Apply must not materialize the selected IDE profile in .settings")
        val rollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
        }).jsonObject
        assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content)
        assertEquals(originalSource, Files.readString(source))
        assertEquals(originalSettings, Files.readString(settings))
    }

    @Given("a dirty Java editor workspace and a selected Eclipse profile")
    fun openDirtyEditorWorkspaceAndProfile() {
        owned = Files.createTempDirectory("refactorkit-java-formatter-editor-")
        root = owned.resolve("project")
        sdk = owned.resolve("sdk")
        Files.createDirectories(sdk.resolve("lib"))
        val originalSdk = Path.of("/usr/lib/jvm/java-21-openjdk")
        Files.copy(originalSdk.resolve("release"), sdk.resolve("release"))
        listOf("ct.sym", "modules", "jrt-fs.jar").forEach {
            Files.createSymbolicLink(sdk.resolve("lib/$it"), originalSdk.resolve("lib/$it"))
        }
        source = root.resolve("src/main/java/sample/Example.java")
        Files.createDirectories(source.parent)
        originalSource = "package sample;\npublic class Example { void run(){} }\n"
        dirtySource = "package sample;\npublic class Example{void run(){int answer=42;}}\n"
        source.writeText(originalSource)
        settings = root.resolve(".settings/org.eclipse.jdt.core.prefs")
        Files.createDirectories(settings.parent)
        originalSettings = "org.eclipse.jdt.core.formatter.tabulation.char=space\n" +
            "org.eclipse.jdt.core.formatter.tabulation.size=8\n" +
            "org.eclipse.jdt.core.formatter.indentation.size=8\n"
        settings.writeText(originalSettings)
        Files.writeString(root.resolve("pom.xml"), """<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>8</maven.compiler.release></properties></project>""")
        session = DaemonSession()
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val acquired = session.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        assertEquals("available", acquired.getValue("status").jsonPrimitive.content)
        editorContext = buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", acquired.getValue("contextHash"))
        }
        formatterProfileSha256 = profileIdentitySha256(profilePath, profileName, profileXml)
    }

    @When("the editor previews Java formatting with that profile")
    fun previewDirtyEditorFormatting() {
        previewResponse = session.dispatch("refactor.editor.preview", editorPreviewRequest()).jsonObject
    }

    @Then("the dirty preview exposes the exact profile identity and binds it into the editor plan hash")
    fun assertDirtyEditorProfileBinding() {
        assertEquals("preview", previewResponse.getValue("status").jsonPrimitive.content)
        assertEquals(profilePath, previewResponse.getValue("formatterProfilePath").jsonPrimitive.content)
        assertEquals(profileName, previewResponse.getValue("formatterProfileName").jsonPrimitive.content)
        assertEquals(formatterProfileSha256, previewResponse.getValue("formatterProfileSha256").jsonPrimitive.content)
        val planHash = previewResponse.getValue("planHash").jsonPrimitive.content
        assertTrue(planHash.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(formatterProfileSha256, planHash)
        assertEquals(originalSource, Files.readString(source))
        assertEquals(originalSettings, Files.readString(settings))
        assertTrue(previewResponse.getValue("renderedDiff").jsonPrimitive.content.contains("answer = 42"))
    }

    @Then("editor prepare and confirm require the current matching profile hash")
    fun editorPrepareAndConfirmBindProfileIdentity() {
        assertEditorProfileRefused("refactor.editor.prepare", null, "java.formatterProfile.approvalMissing")
        assertEditorProfileRefused("refactor.editor.prepare", "0".repeat(64), "java.formatterProfile.identityStale")

        previewResponse = session.dispatch("refactor.editor.preview", editorPreviewRequest()).jsonObject
        val planHash = previewResponse.getValue("planHash").jsonPrimitive.content
        val prepared = session.dispatch("refactor.editor.prepare", editorTransactionRequest(planHash, null, formatterProfileSha256)).jsonObject
        assertEquals("prepared", prepared.getValue("status").jsonPrimitive.content)
        assertEquals(formatterProfileSha256, prepared.getValue("formatterProfileSha256").jsonPrimitive.content)
        dirtySource = applyEditorTextChanges(prepared.getValue("workspaceEdit").jsonObject)
        dirtyVersion++
        val transactionId = prepared.getValue("transactionId").jsonPrimitive.content
        assertEditorProfileRefused("refactor.editor.confirm", null, "java.formatterProfile.approvalMissing", transactionId)
        assertEditorProfileRefused("refactor.editor.confirm", "0".repeat(64), "java.formatterProfile.identityStale", transactionId)
        val confirmed = session.dispatch("refactor.editor.confirm", editorTransactionRequest(planHash, transactionId, formatterProfileSha256)).jsonObject
        assertEquals("applied", confirmed.getValue("status").jsonPrimitive.content)
        assertEquals(formatterProfileSha256, confirmed.getValue("formatterProfileSha256").jsonPrimitive.content)
        assertTrue(dirtySource.contains("answer = 42"))
        assertEquals(originalSource, Files.readString(source), "Editor-owned confirmation must leave saved bytes untouched")
        assertEquals(originalSettings, Files.readString(settings))

        session.close()
        session = DaemonSession()
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        val recoveredContext = session.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
        }).jsonObject
        assertEquals("available", recoveredContext.getValue("status").jsonPrimitive.content)
        editorContext = buildJsonObject {
            put("sdkHome", sdk.toString())
            put("expectedContextHash", recoveredContext.getValue("contextHash"))
        }
        val undo = session.dispatch("refactor.editor.undo.prepare", editorTransactionRequest(planHash, transactionId, null)).jsonObject
        assertEquals("undo-prepared", undo.getValue("status").jsonPrimitive.content)
        dirtySource = applyEditorTextChanges(undo.getValue("workspaceEdit").jsonObject)
        dirtyVersion++
        val undone = session.dispatch("refactor.editor.undo.confirm", editorTransactionRequest(planHash, transactionId, null)).jsonObject
        assertEquals("undone", undone.getValue("status").jsonPrimitive.content)
        assertEquals("java-editor-transaction-v1", undone.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(formatterProfileSha256, undone.getValue("formatterProfileSha256").jsonPrimitive.content)
        assertEquals("package sample;\npublic class Example{void run(){int answer=42;}}\n", dirtySource)
        assertEquals(originalSource, Files.readString(source))
        assertEquals(originalSettings, Files.readString(settings))
    }

    @After
    fun closeAndDeleteWorkspace() {
        if (::session.isInitialized) runCatching { session.close() }
        if (::owned.isInitialized) Files.walk(owned).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } else if (::root.isInitialized) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun preview(): JsonObject = session.dispatch("refactor.preview", buildJsonObject {
        put("operation", "formatFile")
        put("arguments", buildJsonObject { put("file", "src/main/java/sample/Example.java") })
        put("formatterProfile", buildJsonObject {
            put("path", profilePath)
            put("profileName", profileName)
            put("xml", profileXml)
        })
    }).jsonObject

    private fun assertApplyRefused(preview: JsonObject, suppliedHash: String?, expectedCode: String) {
        val failure = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId").jsonPrimitive.content)
                suppliedHash?.let { put("formatterProfileSha256", it) }
            })
        }
        assertEquals(expectedCode, failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
    }

    private fun editorPreviewRequest() = buildJsonObject {
        put("languageId", "java")
        put("operation", "formatFile")
        put("expectedSnapshotHash", snapshotHash)
        put("javaContext", editorContext)
        put("editorMutationMode", "durable-editor-owned")
        put("sourceAuthority", buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("documents", buildJsonArray {
                add(buildJsonObject {
                    put("path", "src/main/java/sample/Example.java")
                    put("version", dirtyVersion)
                    put("content", dirtySource)
                })
            })
        })
        put("arguments", buildJsonObject { put("file", "src/main/java/sample/Example.java") })
        put("formatterProfile", buildJsonObject {
            put("path", profilePath)
            put("profileName", profileName)
            put("xml", profileXml)
        })
    }

    private fun editorTransactionRequest(planHash: String, transactionId: String?, profileHash: String?) = buildJsonObject {
        put("languageId", "java")
        put("planId", previewResponse.getValue("planId"))
        transactionId?.let { put("transactionId", it) }
        put("approvedPlanHash", planHash)
        put("expectedSnapshotHash", snapshotHash)
        put("javaContext", editorContext)
        put("sourceAuthority", buildJsonObject {
            put("kind", "immutable-editor-overlay")
            put("documents", buildJsonArray {
                add(buildJsonObject {
                    put("path", "src/main/java/sample/Example.java")
                    put("version", dirtyVersion)
                    put("content", dirtySource)
                })
            })
        })
        profileHash?.let { put("formatterProfileSha256", it) }
    }

    private fun assertEditorProfileRefused(method: String, profileHash: String?, code: String, transactionId: String? = null) {
        val failure = assertFailsWith<JsonRpcException> {
            session.dispatch(method, editorTransactionRequest(previewResponse.getValue("planHash").jsonPrimitive.content, transactionId, profileHash))
        }
        assertEquals(code, failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        assertEquals(originalSource, Files.readString(source))
        if(method=="refactor.editor.prepare") assertFalse(Files.exists(root.resolve(".refactorkit/editor-transactions")))
    }

    private fun applyEditorTextChanges(workspaceEdit: JsonObject): String {
        val changes=workspaceEdit.getValue("documentChanges").jsonArray
        assertEquals(1, changes.size)
        val change=changes.single().jsonObject
        assertEquals("src/main/java/sample/Example.java", root.relativize(Path.of(java.net.URI(change.getValue("textDocument").jsonObject.getValue("uri").jsonPrimitive.content))).toString())
        val edits=change.getValue("edits").jsonArray.map { item ->
            val edit=item.jsonObject
            val range=edit.getValue("range").jsonObject
            fun point(key:String):SourcePosition {
                val value=range.getValue(key).jsonObject
                return SourcePosition(value.getValue("line").jsonPrimitive.int,value.getValue("character").jsonPrimitive.int)
            }
            TextEdit(SourceRange(point("start"),point("end")),edit.getValue("newText").jsonPrimitive.content)
        }
        return TextEdits.apply(dirtySource,edits)
    }

    private fun profileIdentitySha256(path: String, name: String, xml: String): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf("java-formatter-profile-v1", path, name, xml).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

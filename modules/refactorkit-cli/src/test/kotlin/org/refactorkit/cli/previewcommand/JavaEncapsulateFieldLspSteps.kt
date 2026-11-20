package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.lsp.LspSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaEncapsulateFieldLspSteps {
    private lateinit var root: Path
    private lateinit var ownerPath: Path
    private lateinit var readerPath: Path
    private lateinit var sdkHome: Path
    private lateinit var ownerSaved: String
    private lateinit var readerSaved: String
    private lateinit var pomBefore: String
    private lateinit var ownerBuffer: String
    private lateinit var readerBuffer: String
    private lateinit var lsp: LspSession
    private var response: JsonObject? = null
    private var status = ""
    private var refusal = "none"

    @Given("a Java Maven LSP fixture with dirty owner and reader buffers")
    fun openDirtyBuffers() {
        root = Files.createTempDirectory("rk-java-encapsulate-field-lsp-")
        sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>encapsulate-field-lsp</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        pomBefore = Files.readString(root.resolve("pom.xml"))
        ownerPath = root.resolve("src/main/java/com/acme/Counter.java")
        readerPath = root.resolve("src/main/java/com/acme/CounterReader.java")
        Files.createDirectories(ownerPath.parent)
        Files.writeString(ownerPath, """
            package com.acme;
            public class Counter {
                public final int count = 1;
                public int value() { return count; }
            }
        """.trimIndent() + "\n")
        Files.writeString(readerPath, """
            package com.acme;
            public class CounterReader {
                public int read(Counter counter) {
                    return counter.count;
                }
            }
        """.trimIndent() + "\n")
        ownerSaved = Files.readString(ownerPath)
        readerSaved = Files.readString(readerPath)
        ownerBuffer = ownerSaved.replace(
            "    public final int count = 1;",
            "    // unsaved owner marker\n    public final int count = 1;",
        )
        readerBuffer = readerSaved.replace(
            "        return counter.count;",
            "        // unsaved reader marker\n        return counter.count;",
        )
        assertTrue(ownerBuffer != ownerSaved)
        assertTrue(readerBuffer != readerSaved)

        lsp = LspSession()
        val initialized = lsp.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
                })
            })
        }).jsonObject
        val commands = initialized.getValue("capabilities").jsonObject
            .getValue("executeCommandProvider").jsonObject.getValue("commands").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("refactorkit.encapsulateField" in commands, commands.toString())
        lsp.dispatch("initialized", JsonObject(emptyMap()))
        openDocument(ownerPath, ownerBuffer, 18)
        openDocument(readerPath, readerBuffer, 27)
    }

    @When("the LSP previews encapsulateField at the selected public final field")
    fun previewEncapsulation() {
        val declarationLine = ownerBuffer.lines().indexOfFirst { "public final int count" in it }
        assertTrue(declarationLine >= 0, ownerBuffer)
        val args = buildJsonObject {
            put("file", root.relativize(ownerPath).toString())
            put("sdkHome", sdkHome.toString())
            put("position", buildJsonObject {
                put("line", declarationLine)
                put("character", ownerBuffer.lines()[declarationLine].indexOf("count") + 1)
            })
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.encapsulateField")
                put("arguments", kotlinx.serialization.json.buildJsonArray { add(args) })
            }).jsonObject
        }
        if (outcome.isSuccess) {
            response = outcome.getOrThrow()
            status = response!!.getValue("status").jsonPrimitive.content
            refusal = "none"
        } else {
            val failure = outcome.exceptionOrNull() as? org.refactorkit.core.JsonRpcException
                ?: throw outcome.exceptionOrNull()!!
            response = null
            status = "REFUSED"
            refusal = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: failure.message.orEmpty()
        }
    }

    @Then("the client receives versioned encapsulateField edits for both dirty documents without writing workspace files")
    fun verifyClientManagedEncapsulation() {
        assertEquals("PREVIEW", status, response?.toString())
        val proposal = response ?: error("Expected an encapsulateField preview")
        assertEquals("encapsulateField", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val changes = proposal.getValue("documentChanges").jsonArray.map { it.jsonObject }
        assertEquals(2, changes.size, proposal.toString())
        val ownerChange = changes.single { it.getValue("textDocument").jsonObject.getValue("uri").jsonPrimitive.content == ownerPath.toUri().toString() }
        val readerChange = changes.single { it.getValue("textDocument").jsonObject.getValue("uri").jsonPrimitive.content == readerPath.toUri().toString() }
        assertEquals(18, ownerChange.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(27, readerChange.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.content.toInt())

        val ownerProjection = applyDocumentChange(ownerBuffer, ownerChange)
        val readerProjection = applyDocumentChange(readerBuffer, readerChange)
        assertTrue("private final int count = 1;" in ownerProjection, ownerProjection)
        assertTrue("public int getCount() { return this.count; }" in ownerProjection, ownerProjection)
        assertTrue("// unsaved owner marker" in ownerProjection, ownerProjection)
        assertTrue("counter.getCount()" in readerProjection, readerProjection)
        assertTrue("// unsaved reader marker" in readerProjection, readerProjection)
        assertEquals(ownerSaved, Files.readString(ownerPath))
        assertEquals(readerSaved, Files.readString(readerPath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @After
    fun cleanup() {
        if (::root.isInitialized) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun openDocument(path: Path, content: String, version: Int) {
        lsp.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", path.toUri().toString())
                put("languageId", "java")
                put("version", version)
                put("text", content)
            })
        })
    }

    private fun applyDocumentChange(source: String, change: JsonObject): String {
        val edits = change.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(
                    it.getValue("line").jsonPrimitive.content.toInt(),
                    it.getValue("character").jsonPrimitive.content.toInt(),
                )
            }
            TextEdit(
                SourceRange(position("start"), position("end")),
                edit.getValue("newText").jsonPrimitive.content,
            )
        }
        return TextEdits.apply(source, edits)
    }
}

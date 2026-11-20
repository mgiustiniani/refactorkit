package org.refactorkit.cli.navigation

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaLspChangeSignatureSteps {
    private lateinit var root: Path
    private lateinit var sourcePath: Path
    private lateinit var session: LspSession
    private lateinit var savedSource: String
    private lateinit var dirtyBuffer: String
    private lateinit var proposal: JsonObject
    private var openVersion: Int = 0
    private val offeredSignatureCommands = linkedSetOf<String>()

    @Given("a Java LSP workspace has a saved method and an open dirty buffer at version 7")
    fun openDirtyJavaMethod() {
        root = Files.createTempDirectory("rk-lsp-signature-rename-")
        sourcePath = root.resolve("src/main/java/com/example/Calculator.java")
        Files.createDirectories(sourcePath.parent)
        savedSource = """
            package com.example;
            class Calculator {
              int add(int left, int right) { return left + right; }
            }
        """.trimIndent() + "\n"
        dirtyBuffer = savedSource.replace("class Calculator {", "// editor-only marker\nclass Calculator {")
        Files.writeString(sourcePath, savedSource)
        openVersion = 7

        session = LspSession()
        session.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
                })
            })
        })
        session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", sourcePath.toUri().toString())
                put("languageId", "java")
                put("version", openVersion)
                put("text", dirtyBuffer)
            })
        })
    }

    @When("the client requests method code actions and sends the custom rename command from {string} to {string}")
    fun requestAndExecuteRename(oldName: String, newName: String) {
        val methodLine = dirtyBuffer.lines().indexOfFirst { "int add(" in it }
        check(methodLine >= 0) { "The dirty Java fixture has no add method" }
        val methodText = dirtyBuffer.lines()[methodLine]
        val methodCharacter = methodText.indexOf("add")
        val actions = session.dispatch("textDocument/codeAction", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", sourcePath.toUri().toString()) })
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", methodLine); put("character", methodCharacter) })
                put("end", buildJsonObject { put("line", methodLine); put("character", methodCharacter + 3) })
            })
            put("context", buildJsonObject { put("diagnostics", JsonArray(emptyList())) })
        }) as JsonArray
        offeredSignatureCommands.clear()
        actions.forEach { action ->
            val command = action.jsonObject["command"]?.jsonObject?.get("command")?.jsonPrimitive?.content
            if (command?.startsWith("refactorkit.changeSignature.") == true) {
                offeredSignatureCommands += command
            }
        }

        proposal = session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", "refactorkit.changeSignature.renameParameter")
            put("arguments", buildJsonArray {
                add(buildJsonObject {
                    put("symbol", "com.example.Calculator#add(int,int)")
                    put("oldName", oldName)
                    put("newName", newName)
                })
            })
        }) as JsonObject
    }

    @Then("the client is offered no incomplete signature code action and the custom command returns a version-7 client-managed edit without managed Apply or rollback authority")
    fun verifyClientManagedVersionedProposal() {
        assertTrue(offeredSignatureCommands.isEmpty(), "Signature actions must not omit required arguments: $offeredSignatureCommands")
        assertEquals("changeSignature.renameParameter", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("PREVIEW", proposal.getValue("status").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("true", proposal.getValue("refactorkitDocumentVersionsChecked").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val documentChange = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = documentChange.getValue("textDocument").jsonObject
        assertEquals(sourcePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        assertEquals(openVersion, textDocument.getValue("version").jsonPrimitive.content.toInt())
    }

    @Then("the proposal renames only the bound parameter declaration and reads while saved bytes remain unchanged")
    fun verifyParameterEditsAndReadOnlyWorkspace() {
        val edits = proposal.getValue("documentChanges").jsonArray.single().jsonObject
            .getValue("edits").jsonArray.map { element ->
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
        assertEquals(2, edits.size)
        val projected = TextEdits.apply(dirtyBuffer, edits)
        assertEquals(dirtyBuffer.replace("left", "amount"), projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
    }

    @After
    fun close() {
        if (::session.isInitialized) {
            session.dispatch("shutdown", null)
            session.dispatch("exit", null)
        }
        if (::root.isInitialized) root.toFile().deleteRecursively()
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
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

class JavaExtractVariableLspSteps {
    private lateinit var root: Path
    private lateinit var sourcePath: Path
    private lateinit var lsp: LspSession
    private lateinit var sdkHome: Path
    private lateinit var savedSource: String
    private lateinit var pomBefore: String
    private lateinit var buffer: String
    private var advertisedCommands: List<String> = emptyList()
    private var response: JsonObject? = null
    private var status = ""
    private var refusal = "none"

    @Given("a Java Maven LSP fixture with a dirty buffer before a direct return expression")
    fun openDirtyBufferFixture() {
        root = Files.createTempDirectory("rk-java-extract-variable-lsp-")
        sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>extract-variable</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        pomBefore = Files.readString(root.resolve("pom.xml"))
        sourcePath = root.resolve("src/main/java/com/acme/Calculator.java")
        Files.createDirectories(sourcePath.parent)
        Files.writeString(sourcePath, """
            package com.acme;
            public class Calculator {
                public int calculate() {
                    return 6 * 7 + 0;
                }

                private static int helper(int base) {
                    return base + 1;
                }

                public static int useHelper() {
                    int value = 2;
                    return helper(value);
                }

                public int compute(int left) {
                    return left + 1;
                }
            }
        """.trimIndent() + "\n")
        savedSource = Files.readString(sourcePath)
        buffer = savedSource.replace("        return 6 * 7 + 0;", "        // unsaved editor line\n        return 6 * 7 + 0;")
        assertTrue(buffer != savedSource)

        lsp = LspSession()
        val initialized = lsp.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject {
                        put("documentChanges", true)
                        put("resourceOperations", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("create"))
                        })
                    })
                })
            })
        }).jsonObject
        val commands = initialized.getValue("capabilities").jsonObject
            .getValue("executeCommandProvider").jsonObject.getValue("commands").jsonArray
            .map { it.jsonPrimitive.content }
        advertisedCommands = commands
        assertTrue("refactorkit.extractVariable" in commands, commands.toString())
        assertTrue("refactorkit.extractField" in commands, commands.toString())
        assertTrue("refactorkit.extractConstant" in commands, commands.toString())
        lsp.dispatch("initialized", JsonObject(emptyMap()))
        lsp.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", sourcePath.toUri().toString())
                put("languageId", "java")
                put("version", 8)
                put("text", buffer)
            })
        })
    }

    @When("the LSP previews extractVariable {string} for expression {string} in that buffer")
    fun previewExtractVariable(name: String, expression: String) {
        val startOffset = buffer.indexOf(expression)
        assertTrue(startOffset >= 0, buffer)
        val range = SourceRange(
            TextEdits.positionForOffset(buffer, startOffset),
            TextEdits.positionForOffset(buffer, startOffset + expression.length),
        )
        val args = buildJsonObject {
            put("file", root.relativize(sourcePath).toString())
            put("sdkHome", sdkHome.toString())
            put("name", name)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", range.start.line); put("character", range.start.character) })
                put("end", buildJsonObject { put("line", range.end.line); put("character", range.end.character) })
            })
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.extractVariable")
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

    @When("the LSP previews extractField {string} for expression {string} in that buffer")
    fun previewExtractField(name: String, expression: String) {
        previewExtraction("refactorkit.extractField", name, expression)
    }

    @When("the LSP previews extractConstant {string} for expression {string} in that buffer")
    fun previewExtractConstant(name: String, expression: String) {
        previewExtraction("refactorkit.extractConstant", name, expression)
    }

    @When("the LSP previews extractParameter {string} for expression {string} in that buffer")
    fun previewExtractParameter(name: String, expression: String) {
        previewExtraction("refactorkit.extractParameter", name, expression)
    }

    @When("the LSP previews extractSuperclass as {string} for {string} at the Calculator declaration")
    fun previewExtractSuperclass(superclassName: String, methodName: String) {
        val classLine = buffer.lines().indexOfFirst { "public class Calculator" in it }
        assertTrue(classLine >= 0, buffer)
        val args = buildJsonObject {
            put("file", root.relativize(sourcePath).toString())
            put("sdkHome", sdkHome.toString())
            put("superclassName", superclassName)
            put("methodName", methodName)
            put("position", buildJsonObject {
                put("line", classLine)
                put("character", buffer.lines()[classLine].indexOf("Calculator") + 1)
            })
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.extractSuperclass")
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

    @When("the LSP previews extractInterface for Calculator.calculate at the Calculator declaration")
    fun previewExtractInterface() {
        val classLine = buffer.lines().indexOfFirst { "public class Calculator" in it }
        assertTrue(classLine >= 0, buffer)
        val args = buildJsonObject {
            put("file", root.relativize(sourcePath).toString())
            put("sdkHome", sdkHome.toString())
            put("interfaceName", "CalculatorOperations")
            put("methodName", "calculate")
            put("position", buildJsonObject {
                put("line", classLine)
                put("character", buffer.lines()[classLine].indexOf("Calculator") + 1)
            })
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.extractInterface")
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

    private fun previewExtraction(command: String, name: String, expression: String) {
        val startOffset = buffer.indexOf(expression)
        assertTrue(startOffset >= 0, buffer)
        val range = SourceRange(
            TextEdits.positionForOffset(buffer, startOffset),
            TextEdits.positionForOffset(buffer, startOffset + expression.length),
        )
        val args = buildJsonObject {
            put("file", root.relativize(sourcePath).toString())
            put("sdkHome", sdkHome.toString())
            put("name", name)
            put("range", buildJsonObject {
                put("start", buildJsonObject { put("line", range.start.line); put("character", range.start.character) })
                put("end", buildJsonObject { put("line", range.end.line); put("character", range.end.character) })
            })
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", command)
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

    @Then("the client receives one versioned extractVariable edit for the current buffer without writing workspace files")
    fun verifyClientManagedEdit() {
        assertEquals("PREVIEW", status, response?.toString())
        val proposal = response ?: error("Expected an extractVariable preview")
        assertEquals("extractVariable", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())
        val documentChange = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = documentChange.getValue("textDocument").jsonObject
        assertEquals(8, textDocument.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(sourcePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        val returnLine = buffer.lines().indexOfFirst { it.contains("return 6 * 7") }
        assertTrue(returnLine >= 0, buffer)
        val edits = documentChange.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(SourceRange(position("start"), position("end")), edit.getValue("newText").jsonPrimitive.content)
        }
        assertEquals(2, edits.size)
        assertTrue(edits.all { it.range.start.line == returnLine && it.range.end.line == returnLine }, edits.toString())
        val projected = TextEdits.apply(buffer, edits)
        assertTrue(projected.contains("int answer = 6 * 7;"), projected)
        assertTrue(projected.contains("return answer + 0;"), projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @Then("the client receives one versioned extractField edit for the current buffer without writing workspace files")
    fun verifyClientManagedFieldEdit() {
        assertEquals("PREVIEW", status, response?.toString())
        val proposal = response ?: error("Expected an extractField preview")
        assertEquals("extractField", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())
        val documentChange = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = documentChange.getValue("textDocument").jsonObject
        assertEquals(8, textDocument.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(sourcePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        val edits = documentChange.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(SourceRange(position("start"), position("end")), edit.getValue("newText").jsonPrimitive.content)
        }
        assertEquals(2, edits.size)
        val projected = TextEdits.apply(buffer, edits)
        assertTrue(projected.contains("private final int answer = 6 * 7 + 0;"), projected)
        assertTrue(projected.contains("return this.answer;"), projected)
        assertTrue(projected.contains("// unsaved editor line"), projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @Then("the client receives one versioned extractConstant edit for the current buffer without writing workspace files")
    fun verifyClientManagedConstantEdit() {
        assertEquals("PREVIEW", status, response?.toString())
        val proposal = response ?: error("Expected an extractConstant preview")
        assertEquals("extractConstant", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())
        val documentChange = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = documentChange.getValue("textDocument").jsonObject
        assertEquals(8, textDocument.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(sourcePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        val edits = documentChange.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(SourceRange(position("start"), position("end")), edit.getValue("newText").jsonPrimitive.content)
        }
        assertEquals(2, edits.size)
        val projected = TextEdits.apply(buffer, edits)
        assertTrue(projected.contains("private static final int answer = 6 * 7 + 0;"), projected)
        assertTrue(projected.contains("return com.acme.Calculator.answer;"), projected)
        assertTrue(projected.contains("// unsaved editor line"), projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @Then("the client receives a versioned create and implementation edit for the current dirty buffer without workspace writes")
    fun verifyClientManagedInterfaceExtraction() {
        assertEquals("PREVIEW", status, response?.toString() ?: refusal)
        val proposal = response ?: error("Expected an extractInterface preview")
        assertTrue("refactorkit.extractInterface" in advertisedCommands, advertisedCommands.toString())
        assertEquals("extractInterface", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val changes = proposal.getValue("documentChanges").jsonArray.map { it.jsonObject }
        assertEquals(3, changes.size, proposal.toString())
        val interfaceUri = root.resolve("src/main/java/com/acme/CalculatorOperations.java").toUri().toString()
        val create = changes.single { it["kind"]?.jsonPrimitive?.content == "create" }
        assertEquals(interfaceUri, create.getValue("uri").jsonPrimitive.content)
        assertEquals("false", create.getValue("options").jsonObject.getValue("overwrite").jsonPrimitive.content)
        assertEquals("false", create.getValue("options").jsonObject.getValue("ignoreIfExists").jsonPrimitive.content)

        val createdDocument = changes.single { it["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content == interfaceUri }
        val createdTextDocument = createdDocument.getValue("textDocument").jsonObject
        assertTrue(createdTextDocument.getValue("version") is JsonNull, createdTextDocument.toString())
        val creationEdit = createdDocument.getValue("edits").jsonArray.single().jsonObject
        assertEquals(
            "package com.acme;\n\npublic interface CalculatorOperations {\n    int calculate();\n}\n",
            creationEdit.getValue("newText").jsonPrimitive.content,
        )
        val creationRange = creationEdit.getValue("range").jsonObject
        assertEquals(0, creationRange.getValue("start").jsonObject.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(0, creationRange.getValue("start").jsonObject.getValue("character").jsonPrimitive.content.toInt())
        assertEquals(0, creationRange.getValue("end").jsonObject.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(0, creationRange.getValue("end").jsonObject.getValue("character").jsonPrimitive.content.toInt())

        val sourceChange = changes.single { it["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content == sourcePath.toUri().toString() }
        assertEquals(8, sourceChange.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.content.toInt())
        val sourceEdit = sourceChange.getValue("edits").jsonArray.single().jsonObject
        assertEquals("implements CalculatorOperations ", sourceEdit.getValue("newText").jsonPrimitive.content)
        val range = sourceEdit.getValue("range").jsonObject
        fun position(key: String) = range.getValue(key).jsonObject.let {
            SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
        }
        val projected = TextEdits.apply(buffer, listOf(TextEdit(SourceRange(position("start"), position("end")), sourceEdit.getValue("newText").jsonPrimitive.content)))
        assertTrue("public class Calculator implements CalculatorOperations {" in projected, projected)
        assertTrue("// unsaved editor line" in projected, projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve("src/main/java/com/acme/CalculatorOperations.java")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @Then("the client receives a versioned client-managed parameter proposal without workspace writes")
    fun verifyClientManagedParameterExtraction() {
        assertEquals("PREVIEW", status, response?.toString() ?: refusal)
        val proposal = response ?: error("Expected an extractParameter preview")
        assertTrue("refactorkit.extractParameter" in advertisedCommands, advertisedCommands.toString())
        assertEquals("extractParameter", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val change = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = change.getValue("textDocument").jsonObject
        assertEquals(8, textDocument.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(sourcePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        val edits = change.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(
                SourceRange(position("start"), position("end")),
                edit.getValue("newText").jsonPrimitive.content,
            )
        }
        assertEquals(3, edits.size)
        val projected = TextEdits.apply(buffer, edits)
        assertTrue("private static int helper(int base, int delta)" in projected, projected)
        assertTrue("return delta;" in projected, projected)
        assertTrue("return helper(value, value + 1);" in projected, projected)
        assertTrue("// unsaved editor line" in projected, projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @Then("the client receives a versioned superclass create and implementation edit without workspace writes")
    fun verifyClientManagedSuperclassExtraction() {
        assertEquals("PREVIEW", status, response?.toString() ?: refusal)
        val proposal = response ?: error("Expected an extractSuperclass preview")
        assertTrue("refactorkit.extractSuperclass" in advertisedCommands, advertisedCommands.toString())
        assertEquals("extractSuperclass", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val changes = proposal.getValue("documentChanges").jsonArray.map { it.jsonObject }
        val superclassUri = root.resolve("src/main/java/com/acme/CalculatorBase.java").toUri().toString()
        val create = changes.single { it["kind"]?.jsonPrimitive?.content == "create" }
        assertEquals(superclassUri, create.getValue("uri").jsonPrimitive.content)
        assertEquals("false", create.getValue("options").jsonObject.getValue("overwrite").jsonPrimitive.content)
        val createdDocument = changes.single {
            it["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content == superclassUri
        }
        assertTrue(createdDocument.getValue("textDocument").jsonObject.getValue("version") is JsonNull)
        val createText = createdDocument.getValue("edits").jsonArray.single().jsonObject.getValue("newText").jsonPrimitive.content
        assertTrue("package com.acme;" in createText, createText)
        assertTrue("public class CalculatorBase" in createText, createText)
        assertTrue("public int compute(int left)" in createText, createText)
        assertTrue("return left + 1;" in createText, createText)

        val sourceChange = changes.single {
            it["textDocument"]?.jsonObject?.get("uri")?.jsonPrimitive?.content == sourcePath.toUri().toString()
        }
        assertEquals(8, sourceChange.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.content.toInt())
        val sourceEdits = sourceChange.getValue("edits").jsonArray.map { element ->
            val edit = element.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(
                SourceRange(position("start"), position("end")),
                edit.getValue("newText").jsonPrimitive.content,
            )
        }
        assertEquals(2, sourceEdits.size)
        val projected = TextEdits.apply(buffer, sourceEdits)
        assertTrue("public class Calculator extends CalculatorBase {" in projected, projected)
        assertFalse("public int compute(int left)" in projected, projected)
        assertTrue("// unsaved editor line" in projected, projected)
        assertEquals(savedSource, Files.readString(sourcePath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", refusal)
    }

    @After
    fun cleanup() {
        if (::lsp.isInitialized) {
            runCatching { lsp.dispatch("shutdown", JsonObject(emptyMap())) }
            runCatching { lsp.dispatch("exit", JsonObject(emptyMap())) }
        }
        if (::root.isInitialized) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JournalState
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaBoundContext
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaExtractVariableManagedSteps {
    private lateinit var root: Path
    private lateinit var calculatorPath: Path
    private lateinit var sdkHome: Path
    private lateinit var session: DaemonSession
    private lateinit var snapshotHash: String
    private lateinit var javaContextResult: JsonObject
    private lateinit var calculatorOriginal: String
    private lateinit var calculatorExpected: String
    private lateinit var daemonPreview: JsonObject
    private lateinit var daemonApply: JsonObject
    private lateinit var daemonRollback: JsonObject
    private lateinit var daemonPartial: JsonObject
    private lateinit var refusalCodes: Map<String, String>
    private lateinit var mcpPreviewText: String
    private lateinit var mcpApplyText: String
    private lateinit var mcpRollbackText: String
    private lateinit var mcpPartialText: String

    @Given("a Maven Java extraction fixture with a JDT-bound integer return and explicit SDK context")
    fun openExtractionFixture() {
        root = Files.createTempDirectory("rk-java-extract-variable-managed-")
        sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>extract-variable</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        calculatorPath = root.resolve("src/main/java/com/acme/Calculator.java")
        Files.createDirectories(calculatorPath.parent)
        Files.writeString(calculatorPath, """
            package com.acme;
            public class Calculator {
                public int calculate(int input) {
                    return input * 2 + 1;
                }
            }
        """.trimIndent() + "\n")
        session = DaemonSession()
        openCurrentJavaContext()
    }

    @When("I preview apply and roll back extractVariable for a bound return subexpression with safety refusals through daemon and MCP")
    fun exerciseManagedExtractionSurfaces() {
        calculatorOriginal = Files.readString(calculatorPath)
            .replace("return input * 2 + 1;", "return 6 * 7 + 0;")
        assertTrue(calculatorOriginal.contains("return 6 * 7 + 0;"))
        val classEnd = calculatorOriginal.lastIndexOf('}')
        assertTrue(classEnd >= 0)
        calculatorOriginal = calculatorOriginal.substring(0, classEnd) + """

                private int touch() { return 9; }
                private int effectfulPrefix() { return touch() + 6 * 7; }
                private boolean shortCircuit(boolean enabled) { return enabled && 6 * 7 > 0; }
                private Object nonDenotable() { return null; }
                private java.util.function.Supplier<String> poly() { return String::new; }
            }
        """.trimIndent() + "\n"
        Files.writeString(calculatorPath, calculatorOriginal)
        val returnAnchor = "return 6 * 7 + 0;"
        val returnOffset = calculatorOriginal.indexOf(returnAnchor)
        val lineStart = calculatorOriginal.lastIndexOf('\n', returnOffset - 1) + 1
        val indentation = calculatorOriginal.substring(lineStart, returnOffset)
        calculatorExpected = calculatorOriginal.replace(
            returnAnchor,
            "int doubled = 6 * 7;\n${indentation}return doubled + 0;",
        )

        openCurrentJavaContext()
        val fullArguments = extractVariableArguments(calculatorOriginal, returnAnchor, "6 * 7", "doubled")
        daemonPreview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "extractVariable")
            put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext())
            put("arguments", fullArguments)
        }).jsonObject
        assertEquals("PREVIEW", daemonPreview.getValue("status").jsonPrimitive.content)
        daemonApply = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", daemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", javaContext())
        }).jsonObject
        assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content)
        assertEquals(calculatorExpected, Files.readString(calculatorPath))
        val daemonErrors = daemonApply.getValue("diagnostics").jsonArray.filter { diagnostic ->
            diagnostic.jsonObject["severity"]?.jsonPrimitive?.content?.equals("error", ignoreCase = true) == true
        }
        assertTrue(daemonErrors.isEmpty(), daemonErrors.toString())
        daemonRollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", javaContext())
        }).jsonObject
        assertEquals("rolledBack", daemonRollback.getValue("status").jsonPrimitive.content)
        assertEquals(calculatorOriginal, Files.readString(calculatorPath))

        val unsafeSelections = linkedMapOf(
            "effectful-prefix" to extractVariableArguments(calculatorOriginal, "return touch() + 6 * 7;", "6 * 7", "unsafe"),
            "short-circuit" to extractVariableArguments(calculatorOriginal, "return enabled && 6 * 7 > 0;", "6 * 7", "unsafe"),
            "null-type" to extractVariableArguments(calculatorOriginal, "return null;", "null", "unsafe"),
            "poly-method-reference" to extractVariableArguments(calculatorOriginal, "return String::new;", "String::new", "unsafe"),
            "partial-ast-range" to extractVariableArguments(calculatorOriginal, returnAnchor, "6 *", "unsafe"),
        )
        refusalCodes = unsafeSelections.mapValues { (_, arguments) -> daemonRefusalCode(arguments) }
        daemonPartial = buildJsonObject {
            put("status", "REFUSED")
            put("refusalCode", refusalCodes.getValue("partial-ast-range"))
        }

        val mcp = McpSession()
        try {
            val scan = mcp.dispatch("tools/call", buildJsonObject {
                put("name", "project_scan")
                put("arguments", buildJsonObject { put("root", root.toString()) })
            }).jsonObject
            assertFalse(scan.getValue("isError").jsonPrimitive.content.toBooleanStrict(), scan.toString())

            val preview = mcp.dispatch("tools/call", mcpExtractVariableRequest(fullArguments)).jsonObject
            mcpPreviewText = toolText(preview)
            val planId = mcpPreviewText.lineSequence().singleOrNull { it.trimStart().startsWith("Plan ID") }
                ?.substringAfter(':')?.trim()?.takeIf(String::isNotBlank)
                ?: error("MCP extractVariable preview did not return a plan ID: $mcpPreviewText")
            val apply = mcp.dispatch("tools/call", buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", buildJsonObject { put("planId", planId) })
            }).jsonObject
            mcpApplyText = toolText(apply)
            assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
            val transactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractVariable Apply did not return its transaction ID: $mcpApplyText")
            assertEquals(calculatorExpected, Files.readString(calculatorPath))

            val diagnostics = toolText(mcp.dispatch("tools/call", buildJsonObject {
                put("name", "diagnostics")
                put("arguments", buildJsonObject {
                    put("languageId", "java")
                    put("jdkHome", sdkHome.toString())
                })
            }).jsonObject)
            assertFalse(diagnostics.lineSequence().any { it.startsWith("[ERROR]") }, diagnostics)
            mcpRollbackText = toolText(mcp.dispatch("tools/call", buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject)
            assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
            assertEquals(calculatorOriginal, Files.readString(calculatorPath))

            val partial = mcp.dispatch("tools/call", mcpExtractVariableRequest(unsafeSelections.getValue("effectful-prefix"))).jsonObject
            mcpPartialText = toolText(partial)
        } finally {
            mcp.close()
        }
    }

    @Then("the managed extractVariable plan inserts a typed local before return and refuses hazardous selections while rollback restores exact source")
    fun verifyManagedExtractionContract() {
        assertEquals("PREVIEW", daemonPreview.getValue("status").jsonPrimitive.content)
        assertEquals("extractVariable", daemonPreview.getValue("operation").jsonPrimitive.content)
        assertTrue(daemonPreview.toString().contains("int doubled = 6 * 7"), daemonPreview.toString())
        assertFalse(Regex("(?i)\\\"severity\\\"\\s*:\\s*\\\"error\\\"").containsMatchIn(daemonPreview.toString()))
        assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content)
        assertEquals("rolledBack", daemonRollback.getValue("status").jsonPrimitive.content)
        assertEquals("REFUSED", daemonPartial.getValue("status").jsonPrimitive.content)
        assertEquals("java.extractVariable.selectionNotExpression", daemonPartial.getValue("refusalCode").jsonPrimitive.content)
        assertEquals("java.extractVariable.evaluationOrderUnsupported", refusalCodes.getValue("effectful-prefix"))
        assertEquals("java.extractVariable.conditionalEvaluationUnsupported", refusalCodes.getValue("short-circuit"))
        assertEquals("java.extractVariable.expressionTypeUnsupported", refusalCodes.getValue("null-type"))
        assertEquals("java.extractVariable.expressionTypeUnsupported", refusalCodes.getValue("poly-method-reference"))

        assertTrue(mcpPreviewText.contains("Operation: extractVariable"), mcpPreviewText)
        assertTrue(mcpPreviewText.contains("Status   : PREVIEW"), mcpPreviewText)
        assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
        assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
        assertTrue(mcpPartialText.contains("java.extractVariable.evaluationOrderUnsupported"), mcpPartialText)
        assertEquals(calculatorOriginal, Files.readString(calculatorPath))

        val capabilities = session.dispatch("server.capabilities", JsonObject(emptyMap())).jsonObject
        val javaAdapter = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = javaAdapter.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractVariable" }
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        assertTrue(JavaBoundContext.supportedMutationOperations().contains("extractVariable"))
        val descriptor = JavaAdapterRegistration.create().adapter.availableRefactorings(
            org.refactorkit.core.CodeSelection(
                org.refactorkit.core.SourceLocation(
                    calculatorPath,
                    org.refactorkit.core.SourceRange(
                        org.refactorkit.core.SourcePosition(0, 0),
                        org.refactorkit.core.SourcePosition(0, 0),
                    ),
                ),
            ),
        ).single { it.id == "extractVariable" }
        assertEquals("extractVariable", descriptor.capability?.operation)

        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.operation == "extractVariable" })
        assertTrue(records.all { it.state == JournalState.ROLLED_BACK })
    }

    private fun openCurrentJavaContext() {
        val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        snapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        javaContextResult = session.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdkHome.toString())
        }).jsonObject
        assertEquals("available", javaContextResult.getValue("status").jsonPrimitive.content, javaContextResult.toString())
    }

    private fun javaContext() = buildJsonObject {
        put("sdkHome", sdkHome.toString())
        put("expectedContextHash", javaContextResult.getValue("contextHash").jsonPrimitive.content)
    }

    private fun daemonRefusalCode(arguments: JsonObject): String {
        openCurrentJavaContext()
        val refusal = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "extractVariable")
                put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", javaContext())
                put("arguments", arguments)
            })
        }
        return refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty()
    }

    private fun extractVariableArguments(source: String, anchor: String, selected: String, name: String) = buildJsonObject {
        val anchorStart = source.indexOf(anchor)
        assertTrue(anchorStart >= 0, "Could not find source anchor: $anchor")
        val startOffset = source.indexOf(selected, anchorStart)
        assertTrue(startOffset >= anchorStart && startOffset < anchorStart + anchor.length,
            "Could not find selection '$selected' inside anchor '$anchor'")
        val endOffset = startOffset + selected.length
        fun line(offset: Int) = source.substring(0, offset).count { it == '\n' }
        fun column(offset: Int): Int = offset - (source.lastIndexOf('\n', (offset - 1).coerceAtLeast(0)) + 1)
        put("file", "src/main/java/com/acme/Calculator.java")
        put("startLine", line(startOffset))
        put("startCharacter", column(startOffset))
        put("endLine", line(endOffset))
        put("endCharacter", column(endOffset))
        put("name", name)
    }

    private fun mcpExtractVariableRequest(arguments: JsonObject) = buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", buildJsonObject {
            put("operation", "extractVariable")
            put("languageId", "java")
            put("arguments", arguments)
        })
    }

    private fun toolText(result: JsonObject): String = result.getValue("content").jsonArray.single().jsonObject
        .getValue("text").jsonPrimitive.content

    @After
    fun cleanup() {
        if (::session.isInitialized) runCatching { session.close() }
        if (::root.isInitialized) Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}

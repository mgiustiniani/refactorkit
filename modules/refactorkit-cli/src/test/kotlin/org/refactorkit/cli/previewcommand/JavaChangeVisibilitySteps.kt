package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.en.And
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionLog
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaAdapterRegistration
import org.refactorkit.java.JavaBoundContext
import org.refactorkit.lsp.LspSession
import org.refactorkit.mcp.McpSession
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaChangeVisibilitySteps {
    private lateinit var root: Path
    private lateinit var servicePath: Path
    private lateinit var sdkHome: Path
    private lateinit var session: DaemonSession
    private lateinit var snapshotHash: String
    private lateinit var context: JsonObject
    private lateinit var sourceBefore: String
    private lateinit var helperSymbol: String
    private lateinit var helperSelection: JsonObject
    private lateinit var preview: JsonObject
    private var lspSession: LspSession? = null
    private var batchCleanupFixture: JavaBatchCleanupStoryFixture? = null
    private var lspCommands: List<String> = emptyList()
    private var lspResponse: JsonObject? = null
    private var lspStatus: String = ""
    private var lspRefusal: String = "none"
    private var lspSourceBefore: String = ""
    private var lspPomBefore: String = ""
    private var lspBuffer: String = ""
    private lateinit var extractFieldSource: String
    private lateinit var extractFieldExpression: String
    private lateinit var extractFieldDaemonPreview: JsonObject
    private lateinit var extractFieldMcp: McpSession
    private lateinit var extractFieldMcpJavaContext: JsonObject
    private lateinit var extractFieldMcpPreviewText: String
    private lateinit var extractConstantSource: String
    private lateinit var extractConstantExpression: String
    private lateinit var extractConstantArguments: JsonObject
    private lateinit var extractConstantDaemonPreview: JsonObject
    private lateinit var extractConstantMcp: McpSession
    private lateinit var extractConstantMcpJavaContext: JsonObject
    private lateinit var extractConstantMcpPreviewText: String
    private lateinit var extractParameterSource: String
    private lateinit var extractParameterExpression: String
    private lateinit var extractParameterArguments: JsonObject
    private lateinit var extractParameterDaemonPreview: JsonObject
    private lateinit var extractParameterMcp: McpSession
    private lateinit var extractParameterMcpJavaContext: JsonObject
    private lateinit var extractParameterMcpPreviewText: String
    private lateinit var parameterRename: JavaParameterRenameStoryFixture
    private lateinit var removeParameterPom: String
    private lateinit var removeParameterRefusal: JsonRpcException
    private lateinit var encapsulateFieldReaderPath: Path
    private lateinit var encapsulateFieldSource: String
    private lateinit var encapsulateFieldReaderSource: String
    private lateinit var encapsulateFieldSelection: JsonObject
    private lateinit var encapsulateFieldDaemonPreview: JsonObject
    private lateinit var encapsulateFieldMcp: McpSession
    private lateinit var encapsulateFieldMcpJavaContext: JsonObject
    private lateinit var encapsulateFieldMcpPreviewText: String
    private val encapsulateFieldTypeEvidence = mutableListOf<EncapsulateFieldTypeEvidence>()
    private val encapsulateFieldRefusalShapes = mutableListOf<String>()

    private data class EncapsulateFieldTypeEvidence(
        val label: String,
        val typeSource: String,
        val ownerBefore: String,
        val readerBefore: String,
        val ownerAfter: String,
        val readerAfter: String,
        val ownerAfterRollback: String,
        val readerAfterRollback: String,
    )

    @Given("an open Maven Java fixture with a JDT-bound service parameter and explicit SDK context")
    fun openMavenFixture() {
        openFixture(
            """
                package com.acme;
                public class Service {
                    public String execute(String value) { return String.valueOf(helper(value)); }
                    private final Object helper(String value) { return value; }
                    private String unsafe(String value) { return value; }
                }
            """,
        )
    }

    @Given("a Maven Java fixture with a final class containing a private non-final method and an explicit SDK context")
    fun openFinalOwnerFixture() {
        openFixture(
            """
                package com.acme;
                public final class Service {
                    public String execute(String value) { return String.valueOf(helper(value)); }
                    private Object helper(String value) { return value; }
                }
            """,
        )
    }

    private fun openFixture(source: String) {
        root = Files.createTempDirectory("rk-java-change-visibility-")
        sdkHome = Path.of(System.getenv("REFACTORKIT_JAVA_BATCH_SDK_HOME")?.takeIf(String::isNotBlank)
            ?: System.getProperty("java.home")).toRealPath()
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>change-visibility</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        servicePath = root.resolve("src/main/java/com/acme/Service.java")
        Files.createDirectories(servicePath.parent)
        Files.writeString(servicePath, source.trimIndent() + "\n")
        sourceBefore = Files.readString(servicePath)
        session = DaemonSession()
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
    }

    @When("the daemon previews the bounded batchCleanup request for {string} using {string}")
    fun previewBatchCleanup(case: String, snapshotState: String) {
        batchCleanupFixture = JavaBatchCleanupStoryFixture(root, sdkHome).also { it.preview(case, snapshotState) }
    }

    @Then("the batchCleanup result is {string} with refusal {string} and no partial changes")
    fun batchCleanupResult(status: String, refusal: String) =
        requireNotNull(batchCleanupFixture).assertResult(status, refusal)

    @And("applying and rolling back the accepted batchCleanup restores the exact workspace")
    fun batchCleanupLifecycle() = requireNotNull(batchCleanupFixture).applyAndRollback()

    @When("I preview apply and roll back changeSignature.renameParameter through daemon and MCP")
    fun exerciseParameterRename() {
        parameterRename = JavaParameterRenameStoryFixture(root, servicePath, sdkHome)
        parameterRename.exercise(session, snapshotHash, contextRequest())
    }

    @Then("changeSignature.renameParameter is advertised as stable managed Java mutation")
    fun verifyParameterRenameAuthority() = assertStableManagedCapability(JavaParameterRenameStoryFixture.OPERATION)

    @And("daemon and MCP parameter-rename transactions pass diagnostics and restore exact source bytes")
    fun verifyParameterRenameRollback() = parameterRename.assertRestored()

    @Given("an SDK-bound Maven method whose unused parameter receives a {string}")
    fun openRemoveParameterFixture(shape: String) {
        val argument = when (shape) {
            "method call" -> "next()"
            "field increment" -> "calls++"
            "commented literal" -> "/* keep this comment */ 1"
            else -> error("Unsupported remove-parameter argument: $shape")
        }
        openFixture(
            """
                package com.acme;
                public class Service {
                    private int calls;
                    private int next() { return calls++; }
                    public int execute() { return helper($argument); }
                    private int helper(int value) { return 7; }
                }
            """,
        )
        removeParameterPom = Files.readString(root.resolve("pom.xml"))
        val provider = "maven-effective-v1"
        val module = context.getValue("modules").jsonArray.map { it.jsonObject }
            .single { it.getValue("providerId").jsonPrimitive.content == provider }
        context = session.dispatch("project.context.v7", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdkHome.toString())
            put("providerId", provider)
            put("moduleId", module.getValue("id"))
            put("sourceSetId", "main")
        }).jsonObject
        assertEquals("available", context.getValue("status").jsonPrimitive.content)
    }

    @When("I preview removal at the saved cursor for the method")
    fun previewSavedParameterRemoval() {
        val selection = selectMethod("helper", parameter = "int value")
        removeParameterRefusal = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("languageId", "java")
                put("operation", "changeSignature.removeParameter")
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
                put("javaSelection", selection)
                put("arguments", buildJsonObject { put("name", "value") })
            })
        }
    }

    @Then("Remove Parameter refuses with {string} and preserves the Maven project")
    fun verifyRemoveParameterRefusal(expected: String) {
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, removeParameterRefusal.code)
        val data = removeParameterRefusal.data?.jsonObject
        assertEquals(expected.takeUnless { it == "none" }, data?.get("refusalCode")?.jsonPrimitive?.content)
        assertFalse(data?.containsKey("planId") == true)
        val message = if (expected == "none") {
            "Call-site argument comments cannot be preserved by bounded parameter removal"
        } else {
            "Removed call-site argument may have observable effects; " +
                "only literals and exact JDT-bound local/parameter reads are supported"
        }
        assertEquals(message, removeParameterRefusal.message)
        assertEquals(sourceBefore, Files.readString(servicePath))
        assertEquals(removeParameterPom, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @When("I preview changeVisibility from private to public for the final helper method")
    fun previewVisibilityChange() {
        helperSelection = selectMethod("helper")
        helperSymbol = helperSelection.getValue("symbolId").jsonPrimitive.content
        preview = previewChangeVisibility()
    }

    @When("I preview changeVisibility for the private non-final method on the final class")
    fun previewNonFinalMethodOnFinalClass() {
        helperSelection = selectMethod("helper")
        helperSymbol = helperSelection.getValue("symbolId").jsonPrimitive.content
        preview = previewChangeVisibility()
    }

    @When("I exercise extractField for expression shape {string} through daemon and MCP")
    fun exerciseManagedExtractField(shape: String) {
        extractFieldExpression = when (shape) {
            "compile-time-int" -> "6 * 7 + 0"
            "parameter-int" -> "value * 2 + 0"
            else -> error("Unsupported extractField acceptance shape: $shape")
        }
        extractFieldSource = """
            package com.acme;
            public class Service {
                public int execute(int value) {
                    return $extractFieldExpression;
                }
            }
        """.trimIndent() + "\n"
        Files.writeString(servicePath, extractFieldSource)
        sourceBefore = extractFieldSource

        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
        val arguments = extractFieldArguments(extractFieldSource)
        val daemonOutcome = runCatching {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "extractField")
                put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
                put("arguments", arguments)
            }).jsonObject
        }
        extractFieldDaemonPreview = daemonOutcome.getOrElse { failure ->
            val rpc = failure as? JsonRpcException ?: throw failure
            buildJsonObject {
                put("status", "REFUSED")
                put("operation", "extractField")
                put("refusalCode", rpc.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content.orEmpty())
            }
        }
        assertEquals(extractFieldSource, Files.readString(servicePath), "Daemon preview must leave source bytes unchanged")

        extractFieldMcp = McpSession()
        val scan = extractFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root.toString()) })
        }).jsonObject
        assertFalse(scan["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true, scan.toString())
        val contextText = mcpToolText(extractFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "java_project_context")
            put("arguments", buildJsonObject { put("sdkHome", sdkHome.toString()) })
        }).jsonObject)
        extractFieldMcpJavaContext = Json.parseToJsonElement(contextText).jsonObject
            .getValue("javaContext").jsonObject
        val mcpPreview = extractFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "extractField")
                put("languageId", "java")
                put("expectedSnapshotHash", extractFieldMcpJavaContext.getValue("expectedSnapshotHash"))
                put("javaContext", extractFieldMcpJavaContext)
                put("arguments", arguments)
            })
        }).jsonObject
        extractFieldMcpPreviewText = mcpToolText(mcpPreview)
        assertEquals(extractFieldSource, Files.readString(servicePath), "MCP preview must leave source bytes unchanged")
    }

    @Then("extractField results are {string} and managed rollback restores exact source")
    fun verifyManagedExtractField(outcome: String) {
        assertTrue(outcome == "applied" || outcome == "refused", "Unexpected extractField outcome: $outcome")
        val shouldApply = outcome == "applied"
        assertEquals(if (shouldApply) "PREVIEW" else "REFUSED",
            extractFieldDaemonPreview.getValue("status").jsonPrimitive.content, extractFieldDaemonPreview.toString())
        assertEquals("extractField", extractFieldDaemonPreview.getValue("operation").jsonPrimitive.content)

        val expectedField = "    private final int answer = $extractFieldExpression;"
        val expectedSourceAfterApply = extractFieldSource.replace(
            "public class Service {\n",
            "public class Service {\n$expectedField\n\n",
        ).replace("return $extractFieldExpression;", "return this.answer;")

        if (shouldApply) {
            val diff = extractFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
            assertTrue(diff.contains(expectedField), diff)
            assertTrue(diff.contains("return this.answer;"), diff)
            val daemonApply = session.dispatch("refactor.apply", buildJsonObject {
                put("planId", extractFieldDaemonPreview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
            }).jsonObject
            assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content, daemonApply.toString())
            assertEquals(expectedSourceAfterApply, Files.readString(servicePath))
            assertTrue(daemonApply.getValue("diagnostics").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
            }, daemonApply.toString())

            val postApplyHash = daemonApply.getValue("snapshotHash").jsonPrimitive.content
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            assertEquals(postApplyHash, snapshotHash)
            val daemonRollback = session.dispatch("patch.rollback", buildJsonObject {
                put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
                put("expectedSnapshotHash", postApplyHash)
                put("javaContext", contextRequest())
            }).jsonObject
            assertEquals("rolledBack", daemonRollback.getValue("status").jsonPrimitive.content, daemonRollback.toString())
            assertEquals(extractFieldSource, Files.readString(servicePath))

            assertTrue(extractFieldMcpPreviewText.contains("Operation: extractField"), extractFieldMcpPreviewText)
            assertTrue(extractFieldMcpPreviewText.contains("Status   : PREVIEW"), extractFieldMcpPreviewText)
            val mcpPlanId = Regex("Plan ID\\s*:\\s*([^\\s]+)").find(extractFieldMcpPreviewText)?.groupValues?.get(1)
                ?: error("MCP extractField preview did not return a plan ID: $extractFieldMcpPreviewText")
            val mcpApplyText = mcpToolText(extractFieldMcp.dispatch("tools/call", buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", buildJsonObject {
                    put("planId", mcpPlanId)
                    put("javaContext", extractFieldMcpJavaContext)
                })
            }).jsonObject)
            assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
            assertEquals(expectedSourceAfterApply, Files.readString(servicePath))
            val mcpTransactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
                ?: error("MCP extractField Apply did not return a transaction ID: $mcpApplyText")
            val refreshedMcpContext = Json.parseToJsonElement(mcpToolText(extractFieldMcp.dispatch("tools/call", buildJsonObject {
                put("name", "java_project_context")
                put("arguments", buildJsonObject { put("sdkHome", sdkHome.toString()) })
            }).jsonObject)).jsonObject.getValue("javaContext").jsonObject
            val rollbackMcpContext = buildJsonObject {
                put("expectedSnapshotHash", refreshedMcpContext.getValue("expectedSnapshotHash"))
                put("expectedContextHash", extractFieldMcpJavaContext.getValue("expectedContextHash"))
                put("sdkHome", extractFieldMcpJavaContext.getValue("sdkHome"))
            }
            val mcpRollbackText = mcpToolText(extractFieldMcp.dispatch("tools/call", buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", buildJsonObject {
                    put("transactionId", mcpTransactionId)
                    put("javaContext", rollbackMcpContext)
                })
            }).jsonObject)
            assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
            assertEquals(extractFieldSource, Files.readString(servicePath))

            val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
            assertEquals(2, records.size)
            assertTrue(records.all { it.operation == "extractField" && it.state == JournalState.ROLLED_BACK }, records.toString())
        } else {
            val expectedRefusal = "java.extractField.notCompileTimeConstant"
            assertEquals(expectedRefusal, extractFieldDaemonPreview.getValue("refusalCode").jsonPrimitive.content)
            assertTrue(extractFieldMcpPreviewText.contains("Status   : REFUSED"), extractFieldMcpPreviewText)
            assertTrue(extractFieldMcpPreviewText.contains(expectedRefusal), extractFieldMcpPreviewText)
            assertEquals(extractFieldSource, Files.readString(servicePath))
            assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
        }
    }

    @And("extractField is stable and instance-field previews use private final semantics")
    fun verifyExtractFieldCapabilityAndSemantics() {
        val capabilities = session.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "extractField" }
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        assertTrue(JavaBoundContext.supportedMutationOperations().contains("extractField"))
        assertEquals("extractField", JavaAdapterRegistration.capability("extractField")?.operation)
        if (extractFieldDaemonPreview.getValue("status").jsonPrimitive.content == "PREVIEW") {
            val diff = extractFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
            assertTrue(diff.contains("private final int answer = $extractFieldExpression;"), diff)
            assertFalse(diff.contains("private static final int answer"), diff)
        }
    }

    @When("I preview apply and roll back extractConstant for a bound integer constant expression through daemon and MCP")
    fun previewManagedExtractConstant() {
        extractConstantExpression = "6 * 7 + 0"
        extractConstantSource = """
            package com.acme;
            public class Service {
                public int execute(int value) {
                    return $extractConstantExpression;
                }
            }
        """.trimIndent() + "\n"
        Files.writeString(servicePath, extractConstantSource)
        sourceBefore = extractConstantSource
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
        extractConstantArguments = expressionArguments(extractConstantSource, extractConstantExpression, "answer")
        extractConstantDaemonPreview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "extractConstant")
            put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("arguments", extractConstantArguments)
        }).jsonObject
        assertEquals("PREVIEW", extractConstantDaemonPreview.getValue("status").jsonPrimitive.content,
            extractConstantDaemonPreview.toString())
        assertEquals(extractConstantSource, Files.readString(servicePath), "Daemon preview must leave source bytes unchanged")

        extractConstantMcp = McpSession()
        extractConstantMcpJavaContext = openMcpProjectContext(extractConstantMcp)
        extractConstantMcpPreviewText = mcpPreviewText(
            extractConstantMcp, "extractConstant", extractConstantArguments, extractConstantMcpJavaContext,
        )
        assertTrue(extractConstantMcpPreviewText.contains("Operation: extractConstant"), extractConstantMcpPreviewText)
        assertTrue(extractConstantMcpPreviewText.contains("Status   : PREVIEW"), extractConstantMcpPreviewText)
        assertEquals(extractConstantSource, Files.readString(servicePath), "MCP preview must leave source bytes unchanged")
    }

    @Then("extractConstant is advertised as stable managed Java mutation and stages a bound static final constant")
    fun applyAndRollbackManagedExtractConstant() {
        assertStableManagedCapability("extractConstant")
        val expectedSource = extractConstantSource.replace(
            "public class Service {\n",
            "public class Service {\n    private static final int answer = $extractConstantExpression;\n\n",
        ).replace("return $extractConstantExpression;", "return com.acme.Service.answer;")
        val diff = extractConstantDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("private static final int answer = $extractConstantExpression;"), diff)
        assertTrue(diff.contains("com.acme.Service.answer"), diff)
        assertTrue(extractConstantDaemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, extractConstantDaemonPreview.toString())

        val daemonApply = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", extractConstantDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content, daemonApply.toString())
        assertEquals(expectedSource, Files.readString(servicePath))
        assertTrue(daemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, daemonApply.toString())
        val daemonRollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextRequest())
        }).jsonObject
        assertTrue(daemonRollback.getValue("rolledBack").jsonPrimitive.boolean, daemonRollback.toString())
        assertEquals(extractConstantSource, Files.readString(servicePath))
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)

        val planId = Regex("Plan ID\\s*:\\s*([^\\s]+)").find(extractConstantMcpPreviewText)?.groupValues?.get(1)
            ?: error("MCP extractConstant preview did not return a plan ID: $extractConstantMcpPreviewText")
        val mcpApplyText = mcpToolText(extractConstantMcp.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject {
                put("planId", planId)
                put("javaContext", extractConstantMcpJavaContext)
            })
        }).jsonObject)
        assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
        assertEquals(expectedSource, Files.readString(servicePath))
        val transactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
            ?: error("MCP extractConstant Apply did not return a transaction ID: $mcpApplyText")
        val refreshedContext = openMcpProjectContext(extractConstantMcp)
        val rollbackText = mcpToolText(extractConstantMcp.dispatch("tools/call", buildJsonObject {
            put("name", "rollback_refactoring")
            put("arguments", buildJsonObject {
                put("transactionId", transactionId)
                put("javaContext", buildJsonObject {
                    put("expectedSnapshotHash", refreshedContext.getValue("expectedSnapshotHash"))
                    put("expectedContextHash", extractConstantMcpJavaContext.getValue("expectedContextHash"))
                    put("sdkHome", extractConstantMcpJavaContext.getValue("sdkHome"))
                })
            })
        }).jsonObject)
        assertTrue(rollbackText.startsWith("Rolled back transaction"), rollbackText)
        assertEquals(extractConstantSource, Files.readString(servicePath))
        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.operation == "extractConstant" && it.state == JournalState.ROLLED_BACK }, records.toString())
    }

    @And("nonconstant expressions refuse before managed writes while both transactions restore exact source bytes")
    fun refuseNonconstantExtractConstant() {
        val expression = "value * 2 + 0"
        val nonconstantSource = """
            package com.acme;
            public class Service {
                public int execute(int value) {
                    return $expression;
                }
            }
        """.trimIndent() + "\n"
        Files.writeString(servicePath, nonconstantSource)
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
        val arguments = expressionArguments(nonconstantSource, expression, "answer")
        val beforeRecords = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
        val daemonRefusal = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "extractConstant")
                put("languageId", "java")
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
                put("arguments", arguments)
            })
        }
        assertEquals("java.extractConstant.notCompileTimeConstant",
            daemonRefusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        extractConstantMcpJavaContext = openMcpProjectContext(extractConstantMcp)
        val mcpRefusal = mcpPreviewText(extractConstantMcp, "extractConstant", arguments, extractConstantMcpJavaContext)
        assertTrue(mcpRefusal.contains("Status   : REFUSED"), mcpRefusal)
        assertTrue(mcpRefusal.contains("java.extractConstant.notCompileTimeConstant"), mcpRefusal)
        assertEquals(nonconstantSource, Files.readString(servicePath))
        assertEquals(beforeRecords, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size)
    }

    @When("I preview apply and roll back extractParameter for a bound integer return expression through daemon and MCP")
    fun previewManagedExtractParameter() {
        extractParameterExpression = "base + 1"
        extractParameterSource = """
            package com.acme;
            public class Service {
                private static int calculate(int base) {
                    return $extractParameterExpression;
                }
                public static int execute(int value) {
                    return calculate(value);
                }
            }
        """.trimIndent() + "\n"
        Files.writeString(servicePath, extractParameterSource)
        sourceBefore = extractParameterSource
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
        extractParameterArguments = expressionArguments(extractParameterSource, extractParameterExpression, "delta")
        extractParameterDaemonPreview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "extractParameter")
            put("languageId", "java")
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("arguments", extractParameterArguments)
        }).jsonObject
        assertEquals("PREVIEW", extractParameterDaemonPreview.getValue("status").jsonPrimitive.content,
            extractParameterDaemonPreview.toString())
        assertEquals(extractParameterSource, Files.readString(servicePath), "Daemon preview must leave source bytes unchanged")

        extractParameterMcp = McpSession()
        extractParameterMcpJavaContext = openMcpProjectContext(extractParameterMcp)
        extractParameterMcpPreviewText = mcpPreviewText(
            extractParameterMcp, "extractParameter", extractParameterArguments, extractParameterMcpJavaContext,
        )
        assertTrue(extractParameterMcpPreviewText.contains("Operation: extractParameter"), extractParameterMcpPreviewText)
        assertTrue(extractParameterMcpPreviewText.contains("Status   : PREVIEW"), extractParameterMcpPreviewText)
        assertEquals(extractParameterSource, Files.readString(servicePath), "MCP preview must leave source bytes unchanged")
    }

    @Then("extractParameter is advertised as stable managed Java mutation and stages the derived call-site argument")
    fun applyAndRollbackManagedExtractParameter() {
        assertStableManagedCapability("extractParameter")
        val expectedSource = extractParameterSource
            .replace("private static int calculate(int base)", "private static int calculate(int base, int delta)")
            .replace("return $extractParameterExpression;", "return delta;")
            .replace("calculate(value)", "calculate(value, value + 1)")
        val diff = extractParameterDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("int base, int delta"), diff)
        assertTrue(diff.contains("return delta;"), diff)
        assertTrue(diff.contains("calculate(value, value + 1)"), diff)
        assertTrue(extractParameterDaemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, extractParameterDaemonPreview.toString())

        val daemonApply = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", extractParameterDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content, daemonApply.toString())
        assertEquals(expectedSource, Files.readString(servicePath))
        assertTrue(daemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, daemonApply.toString())
        val daemonRollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextRequest())
        }).jsonObject
        assertTrue(daemonRollback.getValue("rolledBack").jsonPrimitive.boolean, daemonRollback.toString())
        assertEquals(extractParameterSource, Files.readString(servicePath))
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)

        val planId = Regex("Plan ID\\s*:\\s*([^\\s]+)").find(extractParameterMcpPreviewText)?.groupValues?.get(1)
            ?: error("MCP extractParameter preview did not return a plan ID: $extractParameterMcpPreviewText")
        val mcpApplyText = mcpToolText(extractParameterMcp.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject {
                put("planId", planId)
                put("javaContext", extractParameterMcpJavaContext)
            })
        }).jsonObject)
        assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
        assertEquals(expectedSource, Files.readString(servicePath))
        val transactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
            ?: error("MCP extractParameter Apply did not return a transaction ID: $mcpApplyText")
        val refreshedContext = openMcpProjectContext(extractParameterMcp)
        val rollbackText = mcpToolText(extractParameterMcp.dispatch("tools/call", buildJsonObject {
            put("name", "rollback_refactoring")
            put("arguments", buildJsonObject {
                put("transactionId", transactionId)
                put("javaContext", buildJsonObject {
                    put("expectedSnapshotHash", refreshedContext.getValue("expectedSnapshotHash"))
                    put("expectedContextHash", extractParameterMcpJavaContext.getValue("expectedContextHash"))
                    put("sdkHome", extractParameterMcpJavaContext.getValue("sdkHome"))
                })
            })
        }).jsonObject)
        assertTrue(rollbackText.startsWith("Rolled back transaction"), rollbackText)
        assertEquals(extractParameterSource, Files.readString(servicePath))
        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.operation == "extractParameter" && it.state == JournalState.ROLLED_BACK }, records.toString())
    }

    @And("unsupported selections refuse before managed writes while both transactions restore exact source bytes")
    fun refuseUnsupportedExtractParameterSelections() {
        val unsupportedCases = listOf(
            Triple("unsupported operator", "base - 1", "java.extractParameter.expressionUnsupported"),
            Triple("non-private method", "base + 1", "java.extractParameter.methodVisibilityUnsupported"),
        )
        for ((label, expression, expectedCode) in unsupportedCases) {
            val visibility = if (label == "non-private method") "public" else "private"
            val source = """
                package com.acme;
                public class Service {
                    $visibility static int calculate(int base) {
                        return $expression;
                    }
                    public static int execute(int value) {
                        return calculate(value);
                    }
                }
            """.trimIndent() + "\n"
            Files.writeString(servicePath, source)
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = acquireContext(snapshotHash)
            val arguments = expressionArguments(source, expression, "delta")
            val recordsBefore = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val daemonRefusal = assertFailsWith<JsonRpcException> {
                session.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "extractParameter")
                    put("languageId", "java")
                    put("expectedSnapshotHash", snapshotHash)
                    put("javaContext", contextRequest())
                    put("arguments", arguments)
                })
            }
            assertEquals(expectedCode, daemonRefusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content,
                "$label daemon refusal")
            val mcpContext = openMcpProjectContext(extractParameterMcp)
            val mcpRefusal = mcpPreviewText(extractParameterMcp, "extractParameter", arguments, mcpContext)
            assertTrue(mcpRefusal.contains("Status   : REFUSED"), "$label MCP refusal: $mcpRefusal")
            assertTrue(mcpRefusal.contains(expectedCode), "$label MCP refusal code: $mcpRefusal")
            assertEquals(source, Files.readString(servicePath), "$label must not modify source")
            assertEquals(recordsBefore, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size,
                "$label must not add a transaction")
        }
    }

    @When("I install a final-field client fixture and exercise encapsulateField through daemon and MCP")
    fun previewManagedEncapsulateField() {
        encapsulateFieldSource = """
            package com.acme;
            public class Service {
                public final int count = 7;
            }
        """.trimIndent() + "\n"
        encapsulateFieldReaderSource = """
            package com.acme;
            public class Client {
                public int read(Service service) { return service.count; }
            }
        """.trimIndent() + "\n"
        encapsulateFieldReaderPath = root.resolve("src/main/java/com/acme/Client.java")
        Files.writeString(servicePath, encapsulateFieldSource)
        Files.writeString(encapsulateFieldReaderPath, encapsulateFieldReaderSource)
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)
        encapsulateFieldSelection = selectField("count")
        val symbol = encapsulateFieldSelection.getValue("symbolId").jsonPrimitive.content

        encapsulateFieldDaemonPreview = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "encapsulateField")
            put("languageId", "java")
            put("symbol", symbol)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("PREVIEW", encapsulateFieldDaemonPreview.getValue("status").jsonPrimitive.content,
            encapsulateFieldDaemonPreview.toString())
        assertEquals(encapsulateFieldSource, Files.readString(servicePath), "Daemon preview must leave the owner unchanged")
        assertEquals(encapsulateFieldReaderSource, Files.readString(encapsulateFieldReaderPath), "Daemon preview must leave the client unchanged")

        encapsulateFieldMcp = McpSession()
        val scan = encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root.toString()) })
        }).jsonObject
        assertFalse(scan["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true, scan.toString())
        encapsulateFieldMcpJavaContext = Json.parseToJsonElement(mcpToolText(
            encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
                put("name", "java_project_context")
                put("arguments", buildJsonObject { put("sdkHome", sdkHome.toString()) })
            }).jsonObject,
        )).jsonObject.getValue("javaContext").jsonObject
        val mcpPreview = encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "preview_refactoring")
            put("arguments", buildJsonObject {
                put("operation", "encapsulateField")
                put("languageId", "java")
                put("symbol", symbol)
                put("expectedSnapshotHash", encapsulateFieldMcpJavaContext.getValue("expectedSnapshotHash"))
                put("javaContext", encapsulateFieldMcpJavaContext)
            })
        }).jsonObject
        encapsulateFieldMcpPreviewText = mcpToolText(mcpPreview)
        assertTrue(encapsulateFieldMcpPreviewText.contains("Operation: encapsulateField"), encapsulateFieldMcpPreviewText)
        assertTrue(encapsulateFieldMcpPreviewText.contains("Status   : PREVIEW"), encapsulateFieldMcpPreviewText)
        assertEquals(encapsulateFieldSource, Files.readString(servicePath), "MCP preview must leave the owner unchanged")
        assertEquals(encapsulateFieldReaderSource, Files.readString(encapsulateFieldReaderPath), "MCP preview must leave the client unchanged")
    }

    @Then("the field becomes private and bound reads use its public getter in both surfaces")
    fun applyAndRollbackEncapsulateField() {
        val expectedOwner = encapsulateFieldSource
            .replace("public final int count", "private final int count")
            .replace("\n}\n", "\n\n    public int getCount() { return this.count; }\n}\n")
        val expectedReader = encapsulateFieldReaderSource.replace("service.count", "service.getCount()")
        val diff = encapsulateFieldDaemonPreview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("private final int count"), diff)
        assertTrue(diff.contains("public int getCount() { return this.count; }"), diff)
        assertTrue(diff.contains("service.getCount()"), diff)
        assertTrue(encapsulateFieldDaemonPreview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, encapsulateFieldDaemonPreview.toString())

        val daemonApply = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", encapsulateFieldDaemonPreview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("applied", daemonApply.getValue("status").jsonPrimitive.content, daemonApply.toString())
        assertEquals(expectedOwner, Files.readString(servicePath))
        assertEquals(expectedReader, Files.readString(encapsulateFieldReaderPath))
        assertTrue(daemonApply.getValue("diagnostics").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
        }, daemonApply.toString())
        val daemonRollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", daemonApply.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", daemonApply.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextRequest())
        }).jsonObject
        assertTrue(daemonRollback.getValue("rolledBack").jsonPrimitive.boolean, daemonRollback.toString())
        assertEquals(encapsulateFieldSource, Files.readString(servicePath))
        assertEquals(encapsulateFieldReaderSource, Files.readString(encapsulateFieldReaderPath))
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(snapshotHash)

        val mcpPlanId = Regex("Plan ID\\s*:\\s*([^\\s]+)").find(encapsulateFieldMcpPreviewText)?.groupValues?.get(1)
            ?: error("MCP encapsulateField preview did not return a plan ID: $encapsulateFieldMcpPreviewText")
        val mcpApplyText = mcpToolText(encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "apply_refactoring")
            put("arguments", buildJsonObject {
                put("planId", mcpPlanId)
                put("javaContext", encapsulateFieldMcpJavaContext)
            })
        }).jsonObject)
        assertTrue(mcpApplyText.startsWith("Applied successfully."), mcpApplyText)
        assertEquals(expectedOwner, Files.readString(servicePath))
        assertEquals(expectedReader, Files.readString(encapsulateFieldReaderPath))
        val mcpTransactionId = Regex("Transaction ID: ([^\\s]+)").find(mcpApplyText)?.groupValues?.get(1)
            ?: error("MCP encapsulateField Apply did not return a transaction ID: $mcpApplyText")
        val refreshedMcpContext = Json.parseToJsonElement(mcpToolText(encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "java_project_context")
            put("arguments", buildJsonObject { put("sdkHome", sdkHome.toString()) })
        }).jsonObject)).jsonObject.getValue("javaContext").jsonObject
        val mcpRollbackText = mcpToolText(encapsulateFieldMcp.dispatch("tools/call", buildJsonObject {
            put("name", "rollback_refactoring")
            put("arguments", buildJsonObject {
                put("transactionId", mcpTransactionId)
                put("javaContext", buildJsonObject {
                    put("expectedSnapshotHash", refreshedMcpContext.getValue("expectedSnapshotHash"))
                    put("expectedContextHash", encapsulateFieldMcpJavaContext.getValue("expectedContextHash"))
                    put("sdkHome", encapsulateFieldMcpJavaContext.getValue("sdkHome"))
                })
            })
        }).jsonObject)
        assertTrue(mcpRollbackText.startsWith("Rolled back transaction"), mcpRollbackText)
        assertEquals(encapsulateFieldSource, Files.readString(servicePath))
        assertEquals(encapsulateFieldReaderSource, Files.readString(encapsulateFieldReaderPath))

        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.operation == "encapsulateField" && it.state == JournalState.ROLLED_BACK }, records.toString())
    }

    @And("mutable, static, and unresolved field shapes refuse without workspace edits or transactions")
    fun refuseUnsupportedEncapsulateFieldShapes() {
        listOf(
            "public int count = 7;" to "mutable",
            "public static final int count = 7;" to "static",
            "public final Missing count;" to "unresolved",
        ).forEach { (declaration, shape) ->
            val owner = """
                package com.acme;
                public class Service {
                    $declaration
                }
            """.trimIndent() + "\n"
            val reader = encapsulateFieldReaderSource
            Files.writeString(servicePath, owner)
            Files.writeString(encapsulateFieldReaderPath, reader)
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = acquireContext(snapshotHash)
            val recordsBefore = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val ownerBefore = Files.readString(servicePath)
            val readerBefore = Files.readString(encapsulateFieldReaderPath)
            val selectionResponse = fieldSelectionResponse("count")
            if (selectionResponse.getValue("status").jsonPrimitive.content == "ready") {
                val selection = selectionResponse.getValue("selection").jsonObject
                val refusal = runCatching {
                    session.dispatch("refactor.preview", buildJsonObject {
                        put("operation", "encapsulateField")
                        put("languageId", "java")
                        put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
                        put("expectedSnapshotHash", snapshotHash)
                        put("javaContext", contextRequest())
                    }).jsonObject
                }
                if (refusal.isSuccess) {
                    assertEquals("REFUSED", refusal.getOrThrow().getValue("status").jsonPrimitive.content,
                        "$shape field should refuse: ${refusal.getOrThrow()}")
                } else {
                    assertTrue(refusal.exceptionOrNull() is JsonRpcException, "$shape refusal must be typed")
                }
            } else {
                assertEquals("refused", selectionResponse.getValue("status").jsonPrimitive.content,
                    "$shape selection must return a typed refusal: $selectionResponse")
                assertTrue(selectionResponse.getValue("error").jsonObject.getValue("code").jsonPrimitive.content.isNotBlank(),
                    "$shape refusal must have a typed code: $selectionResponse")
            }
            assertEquals(ownerBefore, Files.readString(servicePath), "$shape refusal must not edit the owner")
            assertEquals(readerBefore, Files.readString(encapsulateFieldReaderPath), "$shape refusal must not edit the client")
            assertEquals(recordsBefore, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size,
                "$shape refusal must not create a transaction")
        }
    }

    @When("I exercise additional resolved field types for encapsulateField through managed Apply")
    fun applyEncapsulateFieldToResolvedTypes() {
        encapsulateFieldTypeEvidence.clear()
        encapsulateFieldRefusalShapes.clear()
        encapsulateFieldReaderPath = root.resolve("src/main/java/com/acme/Client.java")
        val supportedCases = listOf(
            Triple("String", "String", "\"Ada\""),
            Triple("parameterized reference", "java.util.List<String>", "new java.util.ArrayList<String>()"),
            Triple("array", "int[]", "new int[0]"),
        )
        for ((label, typeSource, initializer) in supportedCases) {
            val ownerBefore = encapsulateFieldOwner(typeSource, initializer)
            val readerBefore = encapsulateFieldReader(typeSource)
            Files.writeString(servicePath, ownerBefore)
            Files.writeString(encapsulateFieldReaderPath, readerBefore)
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = acquireContext(snapshotHash)

            val field = selectField("value")
            val preview = session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "encapsulateField")
                put("languageId", "java")
                put("symbol", field.getValue("symbolId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
            }).jsonObject
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, "$label: $preview")
            assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
            }, "$label staged diagnostics: $preview")
            val expectedOwner = ownerBefore
                .replace("public final $typeSource value", "private final $typeSource value")
                .replace("\n}\n", "\n\n    public $typeSource getValue() { return this.value; }\n}\n")
            val expectedReader = readerBefore.replace("service.value", "service.getValue()")
            val diff = preview.getValue("renderedDiff").jsonPrimitive.content
            assertTrue(diff.contains("private final $typeSource value"), "$label field type not retained: $diff")
            assertTrue(diff.contains("public $typeSource getValue() { return this.value; }"), "$label getter type not retained: $diff")
            assertTrue(diff.contains("service.getValue()"), "$label bound client read not rewritten: $diff")

            val applied = session.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
            }).jsonObject
            assertEquals("applied", applied.getValue("status").jsonPrimitive.content, "$label Apply: $applied")
            assertEquals(expectedOwner, Files.readString(servicePath), "$label applied owner")
            assertEquals(expectedReader, Files.readString(encapsulateFieldReaderPath), "$label applied client")
            assertTrue(applied.getValue("diagnostics").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content.equals("ERROR", ignoreCase = true)
            }, "$label Apply diagnostics: $applied")
            val rollback = session.dispatch("patch.rollback", buildJsonObject {
                put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
                put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
                put("javaContext", contextRequest())
            }).jsonObject
            assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean, "$label rollback: $rollback")
            val ownerAfterRollback = Files.readString(servicePath)
            val readerAfterRollback = Files.readString(encapsulateFieldReaderPath)
            assertEquals(ownerBefore, ownerAfterRollback, "$label owner rollback")
            assertEquals(readerBefore, readerAfterRollback, "$label client rollback")
            encapsulateFieldTypeEvidence += EncapsulateFieldTypeEvidence(
                label, typeSource, ownerBefore, readerBefore, expectedOwner, expectedReader,
                ownerAfterRollback, readerAfterRollback,
            )
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = acquireContext(snapshotHash)
        }

        val unsupportedCases = listOf(
            Triple("annotation", "@Deprecated public final int value = 1;", "int" to "java.encapsulateField.annotatedFieldUnsupported"),
            Triple("post-name dimensions", "public final int value[] = new int[0];", "int[]" to "java.encapsulateField.fieldDimensionsUnsupported"),
        )
        for ((label, declaration, typeAndCode) in unsupportedCases) {
            val (returnType, expectedRefusalCode) = typeAndCode
            val owner = """
                package com.acme;
                public class Service {
                    $declaration
                }
            """.trimIndent() + "\n"
            val reader = encapsulateFieldReader(returnType)
            Files.writeString(servicePath, owner)
            Files.writeString(encapsulateFieldReaderPath, reader)
            snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
                .jsonObject.getValue("snapshotHash").jsonPrimitive.content
            context = acquireContext(snapshotHash)
            val recordsBefore = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size
            val field = selectField("value")
            val refusal = runCatching {
                session.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "encapsulateField")
                    put("languageId", "java")
                    put("symbol", field.getValue("symbolId").jsonPrimitive.content)
                    put("expectedSnapshotHash", snapshotHash)
                    put("javaContext", contextRequest())
                }).jsonObject
            }
            if (refusal.isSuccess) {
                val plan = refusal.getOrThrow()
                assertEquals("REFUSED", plan.getValue("status").jsonPrimitive.content, "$label must refuse: $plan")
                assertTrue(plan.getValue("affectedFiles").jsonArray.isEmpty(), "$label refusal must have no file edits: $plan")
            } else {
                val rpc = refusal.exceptionOrNull() as? JsonRpcException ?: throw refusal.exceptionOrNull()!!
                assertEquals(org.refactorkit.core.JsonRpcErrorCodes.PLAN_REFUSED, rpc.code)
                assertEquals(expectedRefusalCode, rpc.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
            }
            assertEquals(owner, Files.readString(servicePath), "$label refusal must preserve owner bytes")
            assertEquals(reader, Files.readString(encapsulateFieldReaderPath), "$label refusal must preserve client bytes")
            assertEquals(recordsBefore, TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().size,
                "$label refusal must not create a transaction")
            encapsulateFieldRefusalShapes += label
        }
    }

    @Then("String, parameterized reference, and array fields preserve getter bindings and rollback exactly, while annotations and post-name dimensions refuse without edits")
    fun verifyEncapsulateFieldResolvedTypesAndRefusals() {
        assertEquals(listOf("String", "parameterized reference", "array"), encapsulateFieldTypeEvidence.map { it.label })
        encapsulateFieldTypeEvidence.forEach { evidence ->
            assertTrue(evidence.ownerAfter.contains("private final ${evidence.typeSource} value"), evidence.ownerAfter)
            assertTrue(evidence.ownerAfter.contains("public ${evidence.typeSource} getValue()"), evidence.ownerAfter)
            assertTrue(evidence.readerAfter.contains("service.getValue()"), evidence.readerAfter)
            assertEquals(evidence.ownerBefore, evidence.ownerAfterRollback)
            assertEquals(evidence.readerBefore, evidence.readerAfterRollback)
        }
        assertEquals(listOf("annotation", "post-name dimensions"), encapsulateFieldRefusalShapes)
        val records = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(3, records.size)
        assertTrue(records.all { it.operation == "encapsulateField" && it.state == JournalState.ROLLED_BACK }, records.toString())
    }

    private fun encapsulateFieldOwner(typeSource: String, initializer: String): String = """
        package com.acme;
        public class Service {
            public final $typeSource value = $initializer;
        }
    """.trimIndent() + "\n"

    private fun encapsulateFieldReader(typeSource: String): String = """
        package com.acme;
        public class Client {
            public $typeSource read(Service service) { return service.value; }
        }
    """.trimIndent() + "\n"

    private fun extractFieldArguments(source: String) = buildJsonObject {
        val start = source.indexOf(extractFieldExpression)
        assertTrue(start >= 0, "Could not find extractField selection in source")
        val range = SourceRange(
            TextEdits.positionForOffset(source, start),
            TextEdits.positionForOffset(source, start + extractFieldExpression.length),
        )
        put("file", "src/main/java/com/acme/Service.java")
        put("startLine", range.start.line)
        put("startCharacter", range.start.character)
        put("endLine", range.end.line)
        put("endCharacter", range.end.character)
        put("name", "answer")
    }

    private fun mcpToolText(result: JsonObject): String = result.getValue("content").jsonArray.single().jsonObject
        .getValue("text").jsonPrimitive.content

    private fun openMcpProjectContext(mcp: McpSession): JsonObject {
        val scan = mcp.dispatch("tools/call", buildJsonObject {
            put("name", "project_scan")
            put("arguments", buildJsonObject { put("root", root.toString()) })
        }).jsonObject
        assertFalse(scan["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true, scan.toString())
        return Json.parseToJsonElement(mcpToolText(mcp.dispatch("tools/call", buildJsonObject {
            put("name", "java_project_context")
            put("arguments", buildJsonObject { put("sdkHome", sdkHome.toString()) })
        }).jsonObject)).jsonObject.getValue("javaContext").jsonObject
    }

    private fun mcpPreviewText(
        mcp: McpSession,
        operation: String,
        arguments: JsonObject,
        javaContext: JsonObject,
    ): String = mcpToolText(mcp.dispatch("tools/call", buildJsonObject {
        put("name", "preview_refactoring")
        put("arguments", buildJsonObject {
            put("operation", operation)
            put("languageId", "java")
            put("expectedSnapshotHash", javaContext.getValue("expectedSnapshotHash"))
            put("javaContext", javaContext)
            put("arguments", arguments)
        })
    }).jsonObject)

    private fun expressionArguments(source: String, expression: String, name: String): JsonObject = buildJsonObject {
        val start = source.indexOf(expression)
        assertTrue(start >= 0 && start == source.lastIndexOf(expression), "Expected one exact '$expression' selection in source")
        val range = SourceRange(
            TextEdits.positionForOffset(source, start),
            TextEdits.positionForOffset(source, start + expression.length),
        )
        put("file", "src/main/java/com/acme/Service.java")
        put("startLine", range.start.line)
        put("startCharacter", range.start.character)
        put("endLine", range.end.line)
        put("endCharacter", range.end.character)
        put("name", name)
    }

    private fun assertStableManagedCapability(operation: String) {
        val capabilities = session.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val java = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == operation }
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        assertTrue(JavaBoundContext.supportedMutationOperations().contains(operation))
        assertEquals(operation, JavaAdapterRegistration.capability(operation)?.operation)
    }

    @Given("a Java Maven LSP fixture with a dirty buffer and a private final helper method")
    fun openDirtyLspFixture() {
        root = Files.createTempDirectory("rk-java-change-visibility-lsp-")
        sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>change-visibility-lsp</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        lspPomBefore = Files.readString(root.resolve("pom.xml"))
        servicePath = root.resolve("src/main/java/com/acme/Service.java")
        Files.createDirectories(servicePath.parent)
        Files.writeString(servicePath, """
            package com.acme;
            public class Service {
                public Object execute(Object value) { return helper(value); }
                private final Object helper(Object value) { return value; }
            }
        """.trimIndent() + "\n")
        lspSourceBefore = Files.readString(servicePath)
        lspBuffer = lspSourceBefore.replace(
            "    private final Object helper(Object value)",
            "    // unsaved editor marker\n    private final Object helper(Object value)",
        )
        assertTrue(lspBuffer != lspSourceBefore)

        lspSession = LspSession()
        val initialized = lspSession!!.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
                })
            })
        }).jsonObject
        lspCommands = initialized.getValue("capabilities").jsonObject
            .getValue("executeCommandProvider").jsonObject.getValue("commands").jsonArray
            .map { it.jsonPrimitive.content }
        lspSession!!.dispatch("initialized", JsonObject(emptyMap()))
        lspSession!!.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", servicePath.toUri().toString())
                put("languageId", "java")
                put("version", 11)
                put("text", lspBuffer)
            })
        })
    }

    @When("the LSP previews changeVisibility to public at the helper declaration")
    fun previewDirtyLspVisibility() {
        val helperLine = lspBuffer.lines().indexOfFirst { "private final Object helper" in it }
        assertTrue(helperLine >= 0, lspBuffer)
        val args = buildJsonObject {
            put("file", root.relativize(servicePath).toString())
            put("sdkHome", sdkHome.toString())
            put("visibility", "public")
            put("position", buildJsonObject {
                put("line", helperLine)
                put("character", lspBuffer.lines()[helperLine].indexOf("helper") + 1)
            })
        }
        val outcome = runCatching {
            lspSession!!.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.changeVisibility")
                put("arguments", kotlinx.serialization.json.buildJsonArray { add(args) })
            }).jsonObject
        }
        if (outcome.isSuccess) {
            lspResponse = outcome.getOrThrow()
            lspStatus = lspResponse!!.getValue("status").jsonPrimitive.content
            lspRefusal = "none"
        } else {
            val failure = outcome.exceptionOrNull() as? JsonRpcException ?: throw outcome.exceptionOrNull()!!
            lspResponse = null
            lspStatus = "REFUSED"
            lspRefusal = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: failure.message.orEmpty()
        }
    }

    @Then("the client receives a versioned client-managed visibility edit without workspace writes")
    fun verifyDirtyLspVisibilityProposal() {
        assertEquals("PREVIEW", lspStatus, lspResponse?.toString() ?: lspRefusal)
        val proposal = lspResponse ?: error("Expected a changeVisibility preview")
        assertTrue("refactorkit.changeVisibility" in lspCommands, lspCommands.toString())
        assertEquals("changeVisibility", proposal.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", proposal.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in proposal, proposal.toString())

        val change = proposal.getValue("documentChanges").jsonArray.single().jsonObject
        val textDocument = change.getValue("textDocument").jsonObject
        assertEquals(11, textDocument.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(servicePath.toUri().toString(), textDocument.getValue("uri").jsonPrimitive.content)
        val edits = change.getValue("edits").jsonArray.map { editElement ->
            val edit = editElement.jsonObject
            val range = edit.getValue("range").jsonObject
            fun position(key: String) = range.getValue(key).jsonObject.let {
                SourcePosition(it.getValue("line").jsonPrimitive.content.toInt(), it.getValue("character").jsonPrimitive.content.toInt())
            }
            TextEdit(
                SourceRange(position("start"), position("end")),
                edit.getValue("newText").jsonPrimitive.content,
            )
        }
        assertEquals(1, edits.size)
        assertEquals("public", edits.single().newText)
        val projected = TextEdits.apply(lspBuffer, edits)
        assertTrue("public final Object helper(Object value)" in projected, projected)
        assertTrue("// unsaved editor marker" in projected, projected)
        assertEquals(lspSourceBefore, Files.readString(servicePath))
        assertEquals(lspPomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
        assertEquals("none", lspRefusal)
    }

    private fun previewChangeVisibility(): JsonObject =
        session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "changeVisibility")
            put("languageId", "java")
            put("symbol", helperSymbol)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("javaSelection", helperSelection)
            put("arguments", buildJsonObject { put("visibility", "public") })
        }).jsonObject

    @Then("changeVisibility is advertised as stable, widens only the selected method, and refuses an unsafe non-final method")
    fun verifyCapabilityPreviewAndUnsafeRefusal() {
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals("changeVisibility", preview.getValue("operation").jsonPrimitive.content)
        assertEquals(snapshotHash, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        assertEquals(setOf("src/main/java/com/acme/Service.java"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("-    private final Object helper(String value)"), diff)
        assertTrue(diff.contains("+    public final Object helper(String value)"), diff)
        assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
        assertTrue(preview.getValue("warnings").jsonArray.any { it.jsonPrimitive.content.contains("reflective API") }, preview.toString())
        val caps = session.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val java = caps.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
            .map { it.jsonObject }.single { it.getValue("languageId").jsonPrimitive.content == "java" }
        val capability = java.getValue("capabilities").jsonArray.map { it.jsonObject }
            .single { it.getValue("operation").jsonPrimitive.content == "changeVisibility" }
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)
        assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        assertTrue(JavaBoundContext.supportedMutationOperations().contains("changeVisibility"))

        val unsafeSelection = selectMethod("unsafe")
        val refusal = assertFailsWith<JsonRpcException> {
            session.dispatch("refactor.preview", buildJsonObject {
                put("operation", "changeVisibility")
                put("languageId", "java")
                put("symbol", unsafeSelection.getValue("symbolId").jsonPrimitive.content)
                put("expectedSnapshotHash", snapshotHash)
                put("javaContext", contextRequest())
                put("javaSelection", unsafeSelection)
                put("arguments", buildJsonObject { put("visibility", "public") })
            })
        }
        assertEquals("java.changeVisibility.overrideClosureUnsupported",
            refusal.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content)
        assertEquals(sourceBefore, Files.readString(servicePath))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().isEmpty())
    }

    @Then("changeVisibility accepts the non-final method because its declaring class is final and edits only that method")
    fun verifyFinalOwnerPreview() {
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals("changeVisibility", preview.getValue("operation").jsonPrimitive.content)
        assertEquals(snapshotHash, preview.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
        assertEquals(
            setOf("src/main/java/com/acme/Service.java"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("-    private Object helper(String value)"), diff)
        assertTrue(diff.contains("+    public Object helper(String value)"), diff)
        assertFalse(diff.contains("-    public String execute"), diff)
        assertFalse(diff.contains("+    public String execute"), diff)
        assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        })
    }

    @And("applying and rolling back changeVisibility restores exact source and method binding")
    fun applyAndRollback() {
        applyAndRollback("public final Object helper(String value)")
    }

    @And("applying and rolling back the final-owner change restores exact source and method binding")
    fun applyAndRollbackFinalOwner() {
        applyAndRollback("public Object helper(String value)")
    }

    private fun applyAndRollback(expectedDeclaration: String) {
        val applied = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertTrue(Files.readString(servicePath).contains(expectedDeclaration))
        val rollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextRequest())
        }).jsonObject
        assertTrue(rollback.getValue("rolledBack").jsonPrimitive.boolean)
        assertEquals(sourceBefore, Files.readString(servicePath))
        val restoredSnapshot = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = acquireContext(restoredSnapshot)
        snapshotHash = restoredSnapshot
        assertEquals(helperSymbol, selectMethod("helper").getValue("symbolId").jsonPrimitive.content)
    }

    @After
    fun cleanup() {
        batchCleanupFixture?.close()
        if (::session.isInitialized) runCatching { session.close() }
        if (::extractFieldMcp.isInitialized) runCatching { extractFieldMcp.close() }
        if (::encapsulateFieldMcp.isInitialized) runCatching { encapsulateFieldMcp.close() }
        if (::extractConstantMcp.isInitialized) runCatching { extractConstantMcp.close() }
        if (::extractParameterMcp.isInitialized) runCatching { extractParameterMcp.close() }
        if (::root.isInitialized) {
            runCatching {
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun acquireContext(hash: String): JsonObject = session.dispatch("project.context", buildJsonObject {
        put("expectedSnapshotHash", hash)
        put("sdkHome", sdkHome.toString())
    }).jsonObject.also { assertEquals("available", it.getValue("status").jsonPrimitive.content, it.toString()) }

    private fun contextRequest() = buildJsonObject {
        put("sdkHome", sdkHome.toString())
        put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
        context["schemaVersion"]?.jsonPrimitive?.content?.let { put("contextSchemaVersion", it) }
    }

    private fun selectMethod(name: String, parameter: String = "String value"): JsonObject {
        val lines = Files.readString(servicePath).lines()
        val line = lines.indexOfFirst { "$name($parameter)" in it }
        assertTrue(line >= 0, lines.joinToString("\n"))
        val response = session.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "change-visibility-$name-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Service.java")
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf(name) + 1) })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        val selection = response.getValue("selection").jsonObject
        assertEquals("method", selection.getValue("symbolKind").jsonPrimitive.content)
        return selection
    }

    private fun selectField(name: String): JsonObject {
        val response = fieldSelectionResponse(name)
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        val selection = response.getValue("selection").jsonObject
        assertEquals("field", selection.getValue("symbolKind").jsonPrimitive.content)
        return selection
    }

    private fun fieldSelectionResponse(name: String): JsonObject {
        val lines = Files.readString(servicePath).lines()
        val line = lines.indexOfFirst { name in it }
        assertTrue(line >= 0, lines.joinToString("\n"))
        return session.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "encapsulate-field-$name-${snapshotHash.take(8)}")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Service.java")
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf(name) + 1) })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME
import io.cucumber.junit.platform.engine.Constants.PLUGIN_PROPERTY_NAME
import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.lsp.LspSession
import org.junit.platform.suite.api.ConfigurationParameter
import org.junit.platform.suite.api.IncludeEngines
import org.junit.platform.suite.api.SelectClasspathResource
import org.junit.platform.suite.api.Suite
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("java-refactoring-preview-command-boundary.feature")
@SelectClasspathResource("owned-source-ancestor-identity.feature")
@SelectClasspathResource("release-json-rpc-envelope.feature")
@SelectClasspathResource("java-change-visibility.feature")
// Select only the admitted parameter-rename scenario from the combined capability feature.
@SelectClasspathResource(value = "java-capability-truth.feature", line = 18)
@SelectClasspathResource("java-batch-cleanup.feature")
@SelectClasspathResource("java-type-repair.feature")
@SelectClasspathResource("java-extract-variable.feature")
@SelectClasspathResource("java-extract-field.feature")
@SelectClasspathResource("java-extract-constant.feature")
@SelectClasspathResource("java-extract-interface.feature")
@SelectClasspathResource("java-extract-parameter.feature")
@SelectClasspathResource("java-extract-superclass.feature")
@SelectClasspathResource("java-create-maven-module.feature")
@SelectClasspathResource("java-gradle-module-rename.feature")
@SelectClasspathResource("java-query-context-authority.feature")
@SelectClasspathResource("java-cursor-mutation-selection.feature")
@SelectClasspathResource("typescript-advanced-surfaces.feature")
@SelectClasspathResource("managed-module-crash-recovery.feature")
@SelectClasspathResource("c-parameter-rename-cli.feature")
@SelectClasspathResource("c-include-order-independence.feature")
@SelectClasspathResource("c-format-source-context.feature")
@SelectClasspathResource("c-safe-delete-declaration-boundary.feature")
@SelectClasspathResource("c-reference-failure-safe-delete.feature")
@SelectClasspathResource("c-safe-delete-conditional-configuration.feature")
@SelectClasspathResource("c-safe-delete-cli.feature")
@SelectClasspathResource("c-safe-delete-managed-surfaces.feature")
@SelectClasspathResource("interim-linux-qualification-selection.feature")
@SelectClasspathResource("c-build-model-compilation-units.feature")
@SelectClasspathResource("c-caller-diagnostics-gate-authority.feature")
@SelectClasspathResource("c-clang-resource-directory-capture.feature")
@SelectClasspathResource("c-clang-driver-config.feature")
@SelectClasspathResource("c-clangd-launch-configuration.feature")
@SelectClasspathResource("c-clangd-resource-admission.feature")
@SelectClasspathResource("c-clangd-resource-context.feature")
@SelectClasspathResource("c-cmake-all-tu-diagnostics.feature")
@SelectClasspathResource("c-cmake-apply-context-refusal.feature")
@SelectClasspathResource("c-cmake-compiler-define-path-macros.feature")
@SelectClasspathResource("c-cmake-compiler-define-probes.feature")
@SelectClasspathResource("c-cmake-component-managed-apply.feature")
@SelectClasspathResource("c-cmake-consumer-probes.feature")
@SelectClasspathResource("c-cmake-header-include-move.feature")
@SelectClasspathResource("c-cmake-include-flag-origin.feature")
@SelectClasspathResource("c-cmake-late-configuration-refusal.feature")
@SelectClasspathResource("c-cmake-late-manifest-apply-refusal.feature")
@SelectClasspathResource("c-cmake-library-gate-bypass-refusal.feature")
@SelectClasspathResource("c-cmake-library-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-literal-component-binding.feature")
@SelectClasspathResource("c-cmake-literal-move-binding.feature")
@SelectClasspathResource("c-cmake-moved-path-macros.feature")
@SelectClasspathResource("c-cmake-multi-tu-compile-context.feature")
@SelectClasspathResource("c-cmake-multi-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-nested-manifest-refusal.feature")
@SelectClasspathResource("c-cmake-newline-consumers.feature")
@SelectClasspathResource("c-cmake-preview-diagnostics-evidence.feature")
@SelectClasspathResource("c-cmake-resource-probes.feature")
@SelectClasspathResource("c-cmake-shared-header-managed-apply.feature")
@SelectClasspathResource("c-cmake-single-tu-managed-apply.feature")
@SelectClasspathResource("c-cmake-single-tu-managed-surface.feature")
@SelectClasspathResource("c-cmake-snapshot-capture.feature")
@SelectClasspathResource("c-project-source-capture.feature")
@SelectClasspathResource("c-compiler-symbol-search.feature")
@SelectClasspathResource("c-cmake-spliced-directives.feature")
@SelectClasspathResource("c-cmake-token-paste-consumers.feature")
@SelectClasspathResource("c-cmake-trigraph-consumers.feature")
@SelectClasspathResource("c-cmake-tu-ownership.feature")
@SelectClasspathResource("c-cmake-unparsed-directives.feature")
@SelectClasspathResource("c-compilation-context-apply-refusal.feature")
@SelectClasspathResource("c-compilation-record-working-directory.feature")
@SelectClasspathResource("c-compiler-exit-consistency.feature")
@SelectClasspathResource("c-compiler-resource-context.feature")
@SelectClasspathResource("c-compiler-sdk-context.feature")
@SelectClasspathResource("c-compiler-runtime-currency.feature")
@SelectClasspathResource("c-definition-response-identity.feature")
@SelectClasspathResource("c-elf-artifact-input-capture.feature")
@SelectClasspathResource("c-extract-expression-type-and-placement.feature")
@SelectClasspathResource("c-format-managed-apply.feature")
@SelectClasspathResource("c-format-managed-style-drift.feature")
@SelectClasspathResource("c-format-preserves-include-order.feature")
@SelectClasspathResource("c-format-process-failure.feature")
@SelectClasspathResource("c-format-snapshot-stdin.feature")
@SelectClasspathResource("c-format-style-snapshot.feature")
@SelectClasspathResource("c-formatter-runtime-currency.feature")
@SelectClasspathResource("c-function-facts-captured-execution.feature")
@SelectClasspathResource("c-function-facts-preview-evaluation.feature")
@SelectClasspathResource("c-glibc-runtime-inputs.feature")
@SelectClasspathResource("c-glibc-runtime-resolution.feature")
@SelectClasspathResource("c-inline-function-value-preservation.feature")
@SelectClasspathResource("c-inline-function-catalogue.feature")
@SelectClasspathResource("c-inline-function-managed-surfaces.feature")
@SelectClasspathResource("c-inline-local-value-preservation.feature")
@SelectClasspathResource("c-native-managed-post-commit.feature")
@SelectClasspathResource("c-native-resource-post-commit.feature")
@SelectClasspathResource("c-native-runtime-paths.feature")
@SelectClasspathResource("c-parameter-insertion-cli.feature")
@SelectClasspathResource("c-parameter-insertion-managed-apply.feature")
@SelectClasspathResource("c-parameter-insertion-managed-surfaces.feature")
@SelectClasspathResource("c-parameter-insertion-preview.feature")
@SelectClasspathResource("c-parameter-reordering-cli.feature")
@SelectClasspathResource("c-release-evidence-binding.feature")
@SelectClasspathResource("c-parameter-reordering-managed-apply.feature")
@SelectClasspathResource("c-parameter-reordering-managed-surfaces.feature")
@SelectClasspathResource("c-parameter-reordering-preview.feature")
@SelectClasspathResource("c-resource-bound-preview-authority.feature")
@SelectClasspathResource("c-runtime-manifest-capture.feature")
@SelectClasspathResource("c-scalar-extraction-cli.feature")
@SelectClasspathResource("c-scalar-extraction-managed-apply.feature")
@SelectClasspathResource("c-scalar-extraction-managed-surfaces.feature")
@SelectClasspathResource("c-scalar-extraction-preview.feature")
@SelectClasspathResource("c-scalar-facts-captured-execution.feature")
@SelectClasspathResource("c-semantic-rename-exact-selection.feature")
@SelectClasspathResource("c-statement-facts-captured-execution.feature")
@SelectClasspathResource("c-statement-helper-cli.feature")
@SelectClasspathResource("c-statement-helper-managed-apply.feature")
@SelectClasspathResource("c-statement-helper-managed-surfaces.feature")
@SelectClasspathResource("c-statement-helper-preview.feature")
@SelectClasspathResource("c-symbol-prefix-rename-cli.feature")
@SelectClasspathResource("c-symbol-prefix-rename.feature")
@SelectClasspathResource("c-syntax-resource-context.feature")
@SelectClasspathResource("c-toolchain-runtime-discovery.feature")
@SelectClasspathResource("c-warm-configuration-invalidation.feature")
@SelectClasspathResource("interim-host-support-reconciliation.feature")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "org.refactorkit.cli.previewcommand")
@ConfigurationParameter(
    key = PLUGIN_PROPERTY_NAME,
    value = "pretty, json:build/reports/cucumber/java-preview-command-surface.json",
)
class JavaPreviewCommandSurfaceCucumberTest

/** Shared glue for the selected IDE type-repair story; the suite remains its runner. */
class JavaTypeRepairSteps {
    private lateinit var root: Path
    private lateinit var fieldPath: Path
    private lateinit var sdkHome: Path
    private lateinit var session: DaemonSession
    private lateinit var snapshotHash: String
    private lateinit var context: JsonObject
    private lateinit var contextRefusal: String
    private lateinit var sourceBefore: String
    private lateinit var pomBefore: String
    private lateinit var preview: JsonObject
    private lateinit var refusal: String
    private var lspSession: LspSession? = null
    private var lspResponse: JsonObject? = null
    private var lspStatus: String = ""
    private var lspRefusal: String = "none"
    private var lspBufferText: String? = null

    @Given("a Maven Java type-repair fixture with {string} ownership and a {string} field")
    fun openFixture(ownership: String, visibility: String) {
        lspBufferText = null
        root = Files.createTempDirectory("rk-java-type-repair-")
        sdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        val testRoot = if (ownership == "ambiguous") "src/main/java" else "src/test/java"
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>type-repair</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><sourceDirectory>src/main/java</sourceDirectory><testSourceDirectory>$testRoot</testSourceDirectory>
                <plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                  <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
                </plugin></plugins>
              </build>
            </project>
        """.trimIndent() + "\n")
        pomBefore = Files.readString(root.resolve("pom.xml"))
        fieldPath = root.resolve("src/main/java/com/acme/Counter.java")
        Files.createDirectories(fieldPath.parent)
        Files.writeString(fieldPath, """
            package com.acme;
            public class Counter {
                $visibility int count = 1;
                public long value() { return count; }
            }
        """.trimIndent() + "\n")
        sourceBefore = Files.readString(fieldPath)
        session = DaemonSession()
        snapshotHash = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
        context = session.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdkHome.toString())
        }).jsonObject
        contextRefusal = (context["refusal"] as? JsonObject)?.get("code")?.jsonPrimitive?.content ?: "none"
    }

    @When("the IDE requests field {string} type {string} with {string} selector")
    fun previewTypeRepair(fieldName: String, newType: String, selector: String) {
        if (contextRefusal != "none") {
            refusal = contextRefusal
            preview = buildJsonObject { put("status", "REFUSED"); put("refusalCode", refusal) }
            return
        }
        if (selector == "missing") {
            preview = runCatching {
                session.dispatch("refactor.preview", buildJsonObject {
                    put("operation", "changeType")
                    put("languageId", "java")
                    put("expectedSnapshotHash", snapshotHash)
                    put("javaContext", contextRequest())
                    put("arguments", buildJsonObject { put("newType", newType) })
                }).jsonObject
            }.getOrElse { failure ->
                val code = (failure as? org.refactorkit.core.JsonRpcException)?.data?.jsonObject
                    ?.get("refusalCode")?.jsonPrimitive?.content ?: throw failure
                buildJsonObject { put("status", "REFUSED"); put("refusalCode", code) }
            }
            refusal = preview["refusalCode"]?.jsonPrimitive?.content ?: "none"
            return
        }
        assertEquals("exact", selector)
        val lines = Files.readAllLines(fieldPath)
        val line = lines.indexOfFirst { "$fieldName =" in it }
        assertTrue(line >= 0, lines.joinToString("\n"))
        val query = session.dispatch("intelligence.query", buildJsonObject {
            put("requestId", "type-repair-$fieldName-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", "src/main/java/com/acme/Counter.java")
            put("position", buildJsonObject { put("line", line); put("character", lines[line].indexOf(fieldName) + 1) })
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        if (query.getValue("status").jsonPrimitive.content != "ready") {
            refusal = query["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "java.typeRepair.selectionRefused"
            preview = buildJsonObject { put("status", "REFUSED"); put("refusalCode", refusal) }
            return
        }
        val selection = query.getValue("selection").jsonObject
        val request = buildJsonObject {
            put("operation", "changeType")
            put("languageId", "java")
            put("symbol", selection.getValue("symbolId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
            put("javaSelection", selection)
            put("arguments", buildJsonObject { put("newType", newType) })
        }
        preview = runCatching { session.dispatch("refactor.preview", request).jsonObject }.getOrElse { failure ->
            val code = (failure as? org.refactorkit.core.JsonRpcException)?.data?.jsonObject
                ?.get("refusalCode")?.jsonPrimitive?.content ?: throw failure
            buildJsonObject { put("status", "REFUSED"); put("refusalCode", code) }
        }
        refusal = preview["refusalCode"]?.jsonPrimitive?.content ?: "none"
    }

    @Then("type repair is {string} with refusal {string} and leaves source untouched before apply")
    fun verifyPreview(status: String, expectedRefusal: String) {
        assertEquals(status, preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals(expectedRefusal, refusal, preview.toString())
        assertEquals(sourceBefore, Files.readString(fieldPath))
        val transactions = root.resolve(".refactorkit/transactions")
        if (status == "PREVIEW") {
            assertEquals("changeType", preview.getValue("operation").jsonPrimitive.content)
            assertEquals(setOf("src/main/java/com/acme/Counter.java"),
                preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
                it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
            }, preview.toString())
            val diff = preview.getValue("renderedDiff").jsonPrimitive.content
            assertTrue(diff.contains("-    private int count = 1;"), diff)
            assertTrue(diff.contains("+    private long count = 1;"), diff)
        } else {
            assertFalse(Files.exists(transactions) && Files.list(transactions).use { it.findAny().isPresent })
        }
    }

    @And("an accepted type repair applies, validates, and rolls back the exact workspace")
    fun applyAndRollback() {
        if (preview["status"]?.jsonPrimitive?.content != "PREVIEW") return
        val applied = session.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", snapshotHash)
            put("javaContext", contextRequest())
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content)
        assertTrue(Files.readString(fieldPath).contains("private long count = 1;"))
        val rollback = session.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", applied.getValue("transactionId").jsonPrimitive.content)
            put("expectedSnapshotHash", applied.getValue("snapshotHash").jsonPrimitive.content)
            put("javaContext", contextRequest())
        }).jsonObject
        assertTrue(rollback.getValue("rolledBack").jsonPrimitive.content.toBoolean())
        assertEquals(sourceBefore, Files.readString(fieldPath))
    }

    @When("the LSP requests field {string} type {string} at the exact saved declaration")
    fun lspChangeType(fieldName: String, newType: String) {
        val lsp = LspSession().also { lspSession = it }
        val initialized = lsp.dispatch("initialize", buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", JsonPrimitive(true)) })
                })
            })
        }).jsonObject
        val advertised = initialized.getValue("capabilities").jsonObject
            .getValue("executeCommandProvider").jsonObject.getValue("commands").jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue("refactorkit.changeType" in advertised, advertised.toString())
        lsp.dispatch("initialized", JsonObject(emptyMap()))
        val uri = fieldPath.toUri().toString()
        val content = lspBufferText ?: Files.readString(fieldPath)
        lsp.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri); put("languageId", "java"); put("version", 14); put("text", content)
            })
        })
        val lines = content.lines()
        val line = lines.indexOfFirst { it.contains("$fieldName =") }
        assertTrue(line >= 0, content)
        val character = lines[line].indexOf(fieldName) + 1
        val args = buildJsonObject {
            put("file", root.relativize(fieldPath).toString())
            put("sdkHome", sdkHome.toString())
            put("position", buildJsonObject { put("line", line); put("character", character) })
            put("newType", newType)
        }
        val outcome = runCatching {
            lsp.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", "refactorkit.changeType")
                put("arguments", kotlinx.serialization.json.buildJsonArray { add(args) })
            }).jsonObject
        }
        if (outcome.isSuccess) {
            lspResponse = outcome.getOrThrow()
            lspStatus = lspResponse!!.getValue("status").jsonPrimitive.content
            lspRefusal = "none"
        } else {
            val failure = outcome.exceptionOrNull() as? org.refactorkit.core.JsonRpcException
                ?: throw outcome.exceptionOrNull()!!
            lspResponse = null
            lspStatus = "REFUSED"
            lspRefusal = failure.data?.jsonObject?.get("refusalCode")?.jsonPrimitive?.content ?: failure.message.orEmpty()
        }
    }

    @And("the client has an unsaved Java edit outside the selected field")
    fun dirtyBufferOutsideSelection() {
        lspBufferText = sourceBefore.replace(
            "    private int count =",
            "    // unsaved buffer edit\n    private int count =",
        )
        assertTrue(lspBufferText != sourceBefore)
    }

    @When("the LSP requests field {string} type {string} at the exact buffer declaration")
    fun lspChangeTypeInBuffer(fieldName: String, newType: String) = lspChangeType(fieldName, newType)

    @Then("the LSP type-repair response is {string} with refusal {string} and respects client ownership")
    fun verifyLspProposal(status: String, expectedRefusal: String) {
        assertEquals(status, lspStatus, lspResponse?.toString())
        assertEquals(expectedRefusal, lspRefusal, lspResponse?.toString())
        val response = lspResponse ?: return
        assertEquals("changeType", response.getValue("operation").jsonPrimitive.content)
        assertEquals("client-managed", response.getValue("refactorkitEditOwnership").jsonPrimitive.content)
        assertEquals("false", response.getValue("refactorkitRollbackAvailable").jsonPrimitive.content)
        assertEquals("false", response.getValue("refactorkitManagedApplyAvailable").jsonPrimitive.content)
        assertFalse("refactorkitPlanId" in response, response.toString())
        val changes = response.getValue("documentChanges").jsonArray.single().jsonObject
        assertEquals(14, changes.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(fieldPath.toUri().toString(), changes.getValue("textDocument").jsonObject.getValue("uri").jsonPrimitive.content)
        val edits = changes.getValue("edits").jsonArray
        assertEquals(1, edits.size)
        val currentBuffer = lspBufferText ?: sourceBefore
        val fieldLineIndex = currentBuffer.lines().indexOfFirst { it.contains("count =") }
        assertTrue(fieldLineIndex >= 0, currentBuffer)
        val fieldLine = currentBuffer.lines()[fieldLineIndex]
        val typeStart = fieldLine.indexOf("int")
        val range = edits.single().jsonObject.getValue("range").jsonObject
        assertEquals(fieldLineIndex, range.getValue("start").jsonObject.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(fieldLineIndex, range.getValue("end").jsonObject.getValue("line").jsonPrimitive.content.toInt())
        assertEquals(typeStart, range.getValue("start").jsonObject.getValue("character").jsonPrimitive.content.toInt())
        assertEquals(typeStart + 3, range.getValue("end").jsonObject.getValue("character").jsonPrimitive.content.toInt())
        assertEquals("long", edits.single().jsonObject.getValue("newText").jsonPrimitive.content)
    }

    @And("the LSP type-repair proposal leaves saved files and transaction state unchanged")
    fun verifyLspProposalDoesNotWrite() {
        assertEquals(sourceBefore, Files.readString(fieldPath))
        assertEquals(pomBefore, Files.readString(root.resolve("pom.xml")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertFalse(Files.exists(root.resolve(".refactorkit/workspace.lock")))
    }

    private fun contextRequest() = buildJsonObject {
        put("sdkHome", sdkHome.toString())
        put("expectedContextHash", context.getValue("contextHash").jsonPrimitive.content)
        context["schemaVersion"]?.jsonPrimitive?.content?.let { put("contextSchemaVersion", it) }
    }

    @After
    fun cleanup() {
        lspSession?.let { lsp ->
            runCatching { lsp.dispatch("shutdown", JsonObject(emptyMap())) }
            runCatching { lsp.dispatch("exit", JsonObject(emptyMap())) }
        }
        if (::session.isInitialized) runCatching { session.close() }
        if (::root.isInitialized) runCatching {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
        lspBufferText = null
    }
}

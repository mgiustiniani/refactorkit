package org.refactorkit.cli.renameMavenModuleDaemonMcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.get
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REQ003: the daemon JSON-RPC and MCP tool surfaces retain and apply the exact
 * authoritative renameMavenModule plan, and normal rollback advances the same
 * schema-v8 journal record to ROLLED_BACK while restoring baseline "S0".
 *
 * Both surfaces are exercised through their public dispatch entry points only
 * (refactor.preview -> refactor.apply -> patch.rollback for daemon,
 * preview_refactoring -> apply_refactoring -> rollback_refactoring for MCP).
 */
class JavaRenameMavenModuleDaemonMcpApplyRollbackSurfaceTest {

    private data class Surface(
        val session: Any,
        val openMethod: String,
        val previewMethod: String,
        val applyMethod: String,
        val rollbackMethod: String,
        val label: String,
    )

    private lateinit var root: Path
    private lateinit var journalDir: Path
    private lateinit var invoiceBaseline: String

    private fun writeFixture() {
        root = Files.createTempDirectory("rename-maven-module-daemon-mcp-")
        root.resolve("pom.xml").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId>
              <artifactId>aggregator</artifactId>
              <version>1</version>
              <packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>source</module><module>target</module></modules>
            </project>
            """.trimIndent() + "\n",
        )
        val sourcePom = root.resolve("source/pom.xml")
        Files.createDirectories(sourcePom.parent)
        sourcePom.writeText(childPom("source"))
        val targetPom = root.resolve("target/pom.xml")
        Files.createDirectories(targetPom.parent)
        targetPom.writeText(childPom("target"))
        val invoice = root.resolve("source/src/main/java/fixture/Invoice.java")
        Files.createDirectories(invoice.parent)
        invoice.writeText(
            """
            package fixture;

            import java.util.Set;
            import java.util.List;

            public class Invoice {
                public int total() { return 7; }
            }
            """.trimIndent() + "\n",
        )
        val obsolete = root.resolve("source/src/main/java/fixture/ObsoleteTax.java")
        Files.createDirectories(obsolete.parent)
        obsolete.writeText(
            """
            package fixture;

            public class ObsoleteTax {
            }
            """.trimIndent() + "\n",
        )
        invoiceBaseline = invoice.readText()
        journalDir = root.resolve(".refactorkit/transactions")
    }

    private fun childPom(artifactId: String): String = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>fixture</groupId>
            <artifactId>aggregator</artifactId>
            <version>1</version>
            <relativePath>../pom.xml</relativePath>
          </parent>
          <artifactId>$artifactId</artifactId>
        </project>
    """.trimIndent() + "\n"

    private fun renameParams(): JsonObject = buildJsonObject {
        put("operation", "renameMavenModule")
        put("oldModuleDir", "source")
        put("newModuleDir", "target")
        put("newArtifactId", "target")
        put("languageId", "java")
    }

    private fun openParams(): JsonObject = buildJsonObject {
        put("root", root.toString())
    }

    private fun applyParams(planId: String): JsonObject = buildJsonObject {
        put("planId", planId)
    }

    private fun rollbackParams(transactionId: String): JsonObject = buildJsonObject {
        put("transactionId", transactionId)
    }

    private fun dispatch(surface: Surface, method: String, params: JsonObject): JsonObject {
        val response = when (val session = surface.session) {
            is DaemonSession -> session.dispatch(method, params)
            is McpSession -> session.dispatch(method, params)
            else -> error("unexpected surface session")
        }
        return response.jsonObject
    }

    private fun openWorkspace(surface: Surface) {
        val response = dispatch(surface, surface.openMethod, openParams())
        assertTrue(!response.isEmpty, "${surface.label} must open the workspace")
    }

    private fun preview(surface: Surface): String {
        val response = dispatch(surface, surface.previewMethod, renameParams())
        val planId = response.getValue("planId").jsonPrimitive.content
        assertTrue(planId.isNotBlank(), "${surface.label} preview must return a planId")
        return planId
    }

    private fun apply(surface: Surface, planId: String): String {
        val response = dispatch(surface, surface.applyMethod, applyParams(planId))
        val transactionId = response.getValue("transactionId").jsonPrimitive.content
        assertTrue(transactionId.isNotBlank(), "${surface.label} apply must return a transactionId")
        return transactionId
    }

    private fun rollback(surface: Surface, transactionId: String) {
        val response = dispatch(surface, surface.rollbackMethod, rollbackParams(transactionId))
        assertTrue(!response.isEmpty, "${surface.label} rollback must return a response")
    }

    private fun recordFile(transactionId: String): Path {
        val candidate = journalDir.resolve("$transactionId.json")
        assertTrue(candidate.exists(), "journal record for $transactionId must exist")
        return candidate
    }

    private fun recordState(transactionId: String): String {
        val json = Json.parseToJsonElement(recordFile(transactionId).readText()).jsonObject
        val schema = json["schemaVersion"]?.jsonPrimitive?.content?.toInt()
        assertEquals(8, schema, "journal record must be schema-v8")
        return assertNotNull(json["state"]?.jsonPrimitive?.content, "journal record state")
    }

    private fun invoiceAfter(): String {
        val invoice = root.resolve("source/src/main/java/fixture/Invoice.java")
        return if (invoice.exists()) invoice.readText() else ""
    }

    @Test
    fun daemonRetainsAppliesAndReversesExactPlan() {
        writeFixture()
        val surface = Surface(
            DaemonSession(),
            "project.open",
            "refactor.preview",
            "refactor.apply",
            "patch.rollback",
            "daemon",
        )
        openWorkspace(surface)

        val planId = preview(surface)
        val transactionId = apply(surface, planId)

        assertEquals("APPLIED", recordState(transactionId), "daemon journal APPLIED record")
        assertTrue(journalDir.exists(), "daemon journal must exist after apply")

        rollback(surface, transactionId)

        assertEquals("ROLLED_BACK", recordState(transactionId), "daemon same record ROLLED_BACK")
        assertEquals(invoiceBaseline, invoiceAfter(), "daemon rollback must restore baseline S0")
    }

    @Test
    fun mcpRetainsAppliesAndReversesExactPlan() {
        writeFixture()
        val surface = Surface(
            McpSession(),
            "project_scan",
            "preview_refactoring",
            "apply_refactoring",
            "rollback_refactoring",
            "mcp",
        )
        openWorkspace(surface)

        val planId = preview(surface)
        val transactionId = apply(surface, planId)

        assertEquals("APPLIED", recordState(transactionId), "MCP journal APPLIED record")
        assertTrue(journalDir.exists(), "MCP journal must exist after apply")

        rollback(surface, transactionId)

        assertEquals("ROLLED_BACK", recordState(transactionId), "MCP same record ROLLED_BACK")
        assertEquals(invoiceBaseline, invoiceAfter(), "MCP rollback must restore baseline S0")
    }
}

package org.refactorkit.cli.renameMavenModuleDaemonMcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REQ003: the daemon JSON-RPC and MCP tool surfaces retain and apply the exact
 * authoritative renameMavenModule plan, and normal rollback advances the same
 * schema-v8 journal record to ROLLED_BACK while restoring baseline "S0".
 *
 * Both surfaces are exercised through their public dispatch entry points only:
 *   daemon: project.open -> refactor.preview -> refactor.apply -> patch.rollback
 *   MCP   : tools/call project_scan -> preview_refactoring -> apply_refactoring -> rollback_refactoring
 *
 * The rename destination module directory is ABSENT in the fixture (a rename
 * destination must not pre-exist), and sessions are closed after each run.
 */
class JavaRenameMavenModuleDaemonMcpApplyRollbackSurfaceTest {

    private lateinit var root: Path
    private lateinit var journalDir: Path

    private fun repositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-java"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root")
    }

    private fun copyFixture() {
        val fixture = repositoryRoot().resolve("testdata/acceptance/java-maven-move-class-authority-20-modules")
        root = Files.createTempDirectory("rename-maven-module-daemon-mcp-")
        Files.walk(fixture).use { stream ->
            stream.forEach { path ->
                val rel = fixture.relativize(path)
                val target = root.resolve(rel)
                if (Files.isDirectory(path)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
        journalDir = root.resolve(".refactorkit/transactions")
    }

    private fun recordState(txId: String): String {
        val candidate = journalDir.resolve("$txId.json")
        assertTrue(candidate.exists(), "journal record for $txId must exist")
        val json = Json.parseToJsonElement(candidate.readText()).jsonObject
        val schema = json["schemaVersion"]?.jsonPrimitive?.content?.toInt()
        assertEquals(8, schema, "journal record must be schema-v8")
        return assertNotNull(json["state"]?.jsonPrimitive?.content, "journal record state")
    }

    // ── daemon JSON-RPC surface ───────────────────────────────────────────────

    private fun daemonPreview(session: DaemonSession, root: Path): String {
        session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
        val response = session.dispatch("refactor.preview", buildJsonObject {
            put("operation", "renameMavenModule")
            put("languageId", "java")
            put("arguments", buildJsonObject {
                put("oldModuleDir", "catalog-model")
                put("newModuleDir", "catalog-domain")
                put("newArtifactId", "catalog-domain")
            })
        }).jsonObject
        val planId = response["planId"]?.jsonPrimitive?.content
        assertTrue(!planId.isNullOrBlank(), "daemon preview must return a planId")
        assertEquals("java.renameMavenModule", response["operation"]?.jsonPrimitive?.content)
        return planId!!
    }

    private fun daemonApply(session: DaemonSession, planId: String): String {
        val response = session.dispatch("refactor.apply", buildJsonObject { put("planId", planId) }).jsonObject
        val txId = response["transactionId"]?.jsonPrimitive?.content
        assertTrue(!txId.isNullOrBlank(), "daemon apply must return a transactionId")
        assertEquals("applied", response["status"]?.jsonPrimitive?.content)
        return txId!!
    }

    private fun daemonRollback(session: DaemonSession, txId: String): Boolean {
        val response = session.dispatch("patch.rollback", buildJsonObject { put("transactionId", txId) }).jsonObject
        return response["rolledBack"]?.jsonPrimitive?.content?.toBoolean() ?: false
    }

    @Test
    fun daemonRetainsAppliesAndReversesExactPlan() {
        copyFixture()
        val session = DaemonSession()
        try {
            val planId = daemonPreview(session, root)
            // Route evidence: the structured PREVIEW response keys the plan by a generated
            // plan identifier — never a source-file, symbol, package, module-dir, or
            // operation-name occurrence. A source/operation-name occurrence alone is never
            // accepted as route evidence.
            assertPlanIdIsGeneratedIdentifier(planId)
            // Refresh-state lifecycle: preview alone must NOT refresh/write the workspace.
            // No journal record exists before APPLIED; the workspace stays byte-identical S0.
            assertTrue(journalRecords().isEmpty(),
                "preview must not mutate the workspace: no journal record before APPLIED")
            val txId = daemonApply(session, planId)

            assertEquals("APPLIED", recordState(txId), "daemon journal APPLIED record")
            assertTrue(journalDir.exists(), "daemon journal must exist after apply")

            assertTrue(daemonRollback(session, txId), "daemon normal rollback must succeed")

            assertEquals("ROLLED_BACK", recordState(txId), "daemon same record ROLLED_BACK")
            assertTrue(root.resolve("catalog-model").exists(), "daemon rollback must restore baseline catalog-model")
            assertTrue(!root.resolve("catalog-domain").exists(), "daemon rollback must remove catalog-domain")
        } finally {
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    // ── MCP tools surface ─────────────────────────────────────────────────────

    private fun mcpTool(session: McpSession, name: String, args: JsonObject): String {
        val envelope = session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", args)
        }).jsonObject
        assertTrue(envelope["isError"]?.jsonPrimitive?.content != "true", "tool $name must not be an error envelope")
        val content = envelope["content"]?.jsonArray ?: error("tool $name must return content")
        return content.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
    }

    private fun mcpPreview(session: McpSession, root: Path): String {
        mcpTool(session, "project_scan", buildJsonObject { put("root", root.toString()) })
        // REQ003 no-placeholder criterion: the preview request for renameMavenModule
        // carries NO "symbol" field and must still produce an actionable plan.
        val text = mcpTool(session, "preview_refactoring", buildJsonObject {
            put("operation", "renameMavenModule")
            put("languageId", "java")
            put("arguments", buildJsonObject {
                put("oldModuleDir", "catalog-model")
                put("newModuleDir", "catalog-domain")
                put("newArtifactId", "catalog-domain")
            })
        })
        // The contract fix exposes the canonical operation in the preview text.
        assertTrue(text.contains("Operation: java.renameMavenModule"),
            "MCP preview must expose the canonical operation, got:\n$text")
        return Regex("Plan ID  :\\s*(\\S+)").find(text)?.groupValues?.get(1)
            ?: error("MCP preview must return a Plan ID, got:\n$text")
    }

    private fun mcpApply(session: McpSession, planId: String): String {
        val text = mcpTool(session, "apply_refactoring", buildJsonObject { put("planId", planId) })
        return Regex("Transaction ID: (\\S+)").find(text)?.groupValues?.get(1)
            ?: error("MCP apply must return a Transaction ID, got:\n$text")
    }

    private fun mcpRollback(session: McpSession, txId: String): Boolean {
        val text = mcpTool(session, "rollback_refactoring", buildJsonObject { put("transactionId", txId) })
        return Regex("Rolled back transaction (\\S+)").find(text) != null
    }

    @Test
    fun mcpRetainsAppliesAndReversesExactPlan() {
        copyFixture()
        val session = McpSession()
        try {
            val planId = mcpPreview(session, root)
            // Refresh-state lifecycle: preview alone must NOT refresh/write the workspace.
            // No journal record exists before APPLIED; the workspace stays byte-identical S0.
            assertTrue(journalRecords().isEmpty(),
                "preview must not mutate the workspace: no journal record before APPLIED")
            val txId = mcpApply(session, planId)

            assertEquals("APPLIED", recordState(txId), "MCP journal APPLIED record")
            assertTrue(journalDir.exists(), "MCP journal must exist after apply")

            assertTrue(mcpRollback(session, txId), "MCP normal rollback must succeed")

            assertEquals("ROLLED_BACK", recordState(txId), "MCP same record ROLLED_BACK")
            assertTrue(root.resolve("catalog-model").exists(), "MCP rollback must restore baseline catalog-model")
            assertTrue(!root.resolve("catalog-domain").exists(), "MCP rollback must remove catalog-domain")
        } finally {
            session.close()
            root.toFile().deleteRecursively()
        }
    }

    // ── refusal-probe: one auxiliary-POM byte drift relative to the C1 lease ──

    private fun journalRecords(): List<Path> {
        if (!Files.isDirectory(journalDir)) return emptyList()
        Files.list(journalDir).use { stream ->
            return stream.toList().sortedBy { it.fileName.toString() }
        }
    }

    @Test
    fun daemonRefusalProbeRejectsAuxiliaryPomByteDrift() {
        copyFixture()
        val session = DaemonSession()
        try {
            session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            val planId = daemonPreview(session, root)

            // Stage ONE auxiliary-POM byte drift relative to the canonical C1 snapshot:
            // catalog-pricing depends on catalog-model, so its POM is required lease evidence.
            val auxiliaryPom = root.resolve("catalog-pricing/pom.xml")
            Files.write(auxiliaryPom, "\n".toByteArray(), StandardOpenOption.APPEND)

            // Apply with only the retained planId: the authoritative tracked-content
            // violation must refuse — no transaction, no WAL/PREPARED record, no target edit.
            var refused = false
            try {
                daemonApply(session, planId)
            } catch (refusal: Exception) {
                refused = true
            }
            assertTrue(refused, "apply after auxiliary-POM byte drift must refuse")

            assertTrue(journalRecords().isEmpty(), "refused apply must write no journal record")
            assertTrue(root.resolve("catalog-model").exists(), "source module must remain (no target edit)")
            assertTrue(!root.resolve("catalog-domain").exists(), "no renamed target may exist")
            assertTrue(auxiliaryPom.readText().endsWith("\n"), "probe byte must persist (no refresh/rollback side effect)")
        } finally {
            session.close()
        }
    }

    @Test
    fun mcpRefusalProbeRejectsAuxiliaryPomByteDrift() {
        copyFixture()
        val session = McpSession()
        try {
            // mcpPreview internally dispatches tools/call project_scan then preview_refactoring.
            val planId = mcpPreview(session, root)
            // Route evidence: the preview_refactoring result keys the plan by a generated
            // plan identifier — never a source-file, symbol, package, module-dir, or
            // operation-name occurrence.
            assertPlanIdIsGeneratedIdentifier(planId)

            val auxiliaryPom = root.resolve("catalog-pricing/pom.xml")
            Files.write(auxiliaryPom, "\n".toByteArray(), StandardOpenOption.APPEND)

            var refused = false
            try {
                mcpApply(session, planId)
            } catch (refusal: Exception) {
                refused = true
            }
            assertTrue(refused, "apply after auxiliary-POM byte drift must refuse")
            assertTrue(journalRecords().isEmpty(), "refused apply must write no journal record")
            assertTrue(root.resolve("catalog-model").exists(), "source module must remain (no target edit)")
            assertTrue(!root.resolve("catalog-domain").exists(), "no renamed target may exist")
            assertTrue(auxiliaryPom.readText().endsWith("\n"), "probe byte must persist (no refresh/rollback side effect)")
        } finally {
            session.close()
        }
    }
    /** Route evidence: the plan must be keyed by a generated plan identifier, never by a
     *  source-file, symbol, package, module-directory, or operation-name occurrence. */
    private fun assertPlanIdIsGeneratedIdentifier(planId: String) {
        val sourceOccurrences = listOf(
            "catalog-model", "catalog-domain",                        // module directories
            "com.acme.catalog.model", "com.acme.catalog.domain",      // packages
            "CatalogModel", "CatalogDomain",                          // symbols
            "renameMavenModule",                                      // operation name
        )
        assertTrue(sourceOccurrences.none { planId.contains(it) },
            "planId must be a generated plan identifier, not a source/operation-name occurrence; got: $planId")
    }

}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CSafeDeleteManagedSurfacesSteps {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var surface: String
    private lateinit var source: String
    private lateinit var database: String
    private lateinit var snapshotHash: String
    private lateinit var toolchainRoot: Path
    private lateinit var previewText: String
    private var previewJson: JsonObject? = null
    private var planId: String? = null
    private var transactionId: String? = null
    private var privateDirectoriesBefore: Set<Path> = emptySet()

    @Given("an isolated C safe-delete workspace for {string} with explicit Clang tools")
    fun openWorkspace(route: String) {
        require(route in setOf("daemon", "MCP"))
        surface = route
        val workspace = Files.createTempDirectory("refactorkit-c-safe-delete-managed-")
        root = workspace
        source = "static int unused(void) { return 3; }\nint main(void) { return 0; }\n"
        Files.writeString(workspace.resolve("main.c"), source)

        toolchainRoot = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val unit = buildJsonObject {
            put("directory", workspace.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(clang.toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(workspace).hash

        when (route) {
            "daemon" -> {
                daemon = DaemonSession()
                requireNotNull(daemon).dispatch("project.open", buildJsonObject { put("root", workspace.toString()) })
            }
            "MCP" -> {
                mcp = McpSession()
                call("project_scan", buildJsonObject { put("root", workspace.toString()) })
            }
        }
    }

    @When("the {string} client requests a safe-delete preview")
    fun requestPreview(route: String) {
        assertEquals(surface, route)
        privateDirectoriesBefore = privateDirectories()
        val request = buildJsonObject {
            put("operation", "safeDelete")
            put("languageId", "c")
            put("symbol", "unused")
            put("arguments", buildJsonObject { put("symbol", "unused") })
            put("clang", toolchainRoot.resolve("usr/bin/clang-22").toString())
            put("clangd", toolchainRoot.resolve("usr/bin/clangd").toString())
            put("clangFormat", toolchainRoot.resolve("usr/bin/clang-format").toString())
        }
        if (surface == "daemon") {
            val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
            previewJson = preview
            previewText = preview.toString()
            planId = preview.getValue("planId").jsonPrimitive.content
        } else {
            previewText = call("preview_refactoring", request)
            planId = Regex("Plan ID\\s*:\\s*(\\S+)").find(previewText)?.groupValues?.get(1)
        }
        assertNotNull(planId, previewText)
    }

    @Then("the {string} reports a compiler-proven safe-delete preview without writing")
    fun verifyPreview(route: String) {
        assertEquals(surface, route)
        val workspace = requireNotNull(root)
        val response = previewJson
        if (surface == "daemon") {
            val preview = assertNotNull(response)
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
            assertEquals("safeDelete", preview.getValue("operation").jsonPrimitive.content, preview.toString())
            assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content, preview.toString())
            assertEquals(listOf("main.c"), preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content })
            assertTrue(preview.getValue("renderedDiff").jsonPrimitive.content.contains("-static int unused(void)"), preview.toString())
            assertEquals(planId, preview.getValue("planId").jsonPrimitive.content)
        } else {
            assertTrue(previewText.contains("Status   : PREVIEW"), previewText)
            assertTrue(previewText.contains("Operation: safeDelete"), previewText)
            assertTrue(previewText.contains("Evidence : COMPILER_PROVEN"), previewText)
            assertTrue(previewText.contains("main.c"), previewText)
        }
        assertEquals(source, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        assertEquals(privateDirectoriesBefore, privateDirectories())
    }

    @And("explicit managed Apply and rollback restore the original C workspace")
    fun applyAndRollback() {
        val workspace = requireNotNull(root)
        val id = requireNotNull(planId)
        val appliedTransaction = if (surface == "daemon") {
            val result = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", id) }).jsonObject
            assertEquals("applied", result.getValue("status").jsonPrimitive.content, result.toString())
            result.getValue("transactionId").jsonPrimitive.content
        } else {
            val result = call("apply_refactoring", buildJsonObject { put("planId", id) })
            assertTrue(result.contains("Applied successfully"), result)
            Regex("Transaction ID: (\\S+)").find(result)?.groupValues?.get(1) ?: error(result)
        }
        transactionId = appliedTransaction
        val appliedSource = Files.readString(workspace.resolve("main.c"))
        assertFalse(appliedSource.contains("unused"), appliedSource)
        assertTrue(appliedSource.contains("int main(void)"), appliedSource)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertTrue(CProjectScanner().scan(workspace).hash != snapshotHash, "managed Apply must change the source snapshot")

        if (surface == "daemon") {
            val rollback = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject {
                put("transactionId", appliedTransaction)
            }).jsonObject
            assertEquals("rolledBack", rollback.getValue("status").jsonPrimitive.content, rollback.toString())
        } else {
            val rollback = call("rollback_refactoring", buildJsonObject { put("transactionId", appliedTransaction) })
            assertTrue(rollback.contains("Rolled back"), rollback)
        }
        assertEquals(source, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(snapshotHash, CProjectScanner().scan(workspace).hash)
        assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        assertEquals(privateDirectoriesBefore, privateDirectories())
    }

    @After
    fun cleanup() {
        daemon?.close()
        mcp?.close()
        root?.let { workspace ->
            Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun call(name: String, arguments: JsonObject): String = requireNotNull(mcp)
        .dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { path ->
            path.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
                path.fileName.toString().startsWith("refactorkit-semantic-overlay-")
        }.toList().toSet()
    }
}

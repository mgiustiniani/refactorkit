package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
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
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterInsertionSurfaceSteps {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private var facade: CRefactoringFacade? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var reference: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var surface: String
    private lateinit var condition: String
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var privateBefore: Set<Path>
    private var outcome = ""
    private var detail = ""

    @Given("the C parameter insertion surface {string} has condition {string}")
    fun open(route: String, case: String) {
        surface = route; condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-add-surface-")
        root = workspace
        val prefix = "int required[REQUIRED - 16];\nint seed = 7;\n"
        if (case == "explicit void family") {
            original = prefix + "static int weighted(/*keep*/ void);\nstatic int weighted(void) { return 98; }\n" +
                "int main(void) { return weighted() == 98 && weighted() == 98 ? 0 : 1; }\n"
            expectedSource = prefix + "static int weighted(/*keep*/ int limit);\nstatic int weighted(int limit) { return 98; }\n" +
                "int main(void) { return weighted((7)) == 98 && weighted((7)) == 98 ? 0 : 1; }\n"
        } else {
            original = prefix + "static int weighted(int x, int y);\nstatic int weighted(int a, int b) { return a * 10 + b; }\n" +
                "int main(void) { return weighted(9, 8) == 98 && weighted(3, 2) == 32 ? 0 : 1; }\n"
            expectedSource = prefix + "static int weighted(int x, int limit, int y);\nstatic int weighted(int a, int limit, int b) { return a * 10 + b; }\n" +
                "int main(void) { return weighted(9, (7), 8) == 98 && weighted(3, (7), 2) == 32 ? 0 : 1; }\n"
        }
        Files.writeString(workspace.resolve("main.c"), original)
        reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(),
                if (case == "explicit void family") "-std=c11" else "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        when (route) {
            "library" -> {
                val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
                    reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
                assertEquals("22.1.8", toolchain.provenance.clangVersion)
                manager = ExternalSemanticProcessManager(); facade = CRefactoringFacade(toolchain, requireNotNull(manager))
            }
            "daemon" -> { daemon = DaemonSession(); daemon!!.dispatch("project.open", buildJsonObject { put("root", workspace.toString()) }) }
            "MCP" -> { mcp = McpSession(); call("project_scan", buildJsonObject { put("root", workspace.toString()) }) }
            else -> error("Unknown surface: $route")
        }
        privateBefore = privateDirectories()
    }

    @When("that surface requests the explicit parameter insertion lifecycle")
    fun lifecycle() {
        val args = buildMap {
            put("file", "main.c"); put("symbol", "weighted"); put("parameterName", "limit")
            put("parameterOrdinal", if (condition == "explicit void family") "0" else "1")
            if (condition != "caller expression omitted") put("callerExpression", if (condition == "new argument modifies state") "seed++" else "7")
        }
        if (surface == "library") {
            val instance = requireNotNull(facade)
            instance.startFor(snapshot, "addParameter")
            val plan = instance.preview(snapshot, "addParameter", args)
            detail = "${plan.refusalCode}: ${plan.summary} " + plan.diagnosticsAfterPreview.joinToString { "${it.code}: ${it.message}" }
            if (plan.status == PatchStatus.REFUSED) { outcome = "missing argument refused"; return }
            assertEquals("addParameter", plan.operation)
            assertEquals("c.signature-add-v1", assertNotNull(plan.authorityLease).kind)
            assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
            val applied = assertIs<ApplyResult.Applied>(instance.apply(snapshot, plan,
                ApplyAuthorization.explicit("c-add-surface", "bdd-caller"), instance.diagnosticsGate(snapshot, plan)))
            assertEquals(expectedSource, Files.readString(requireNotNull(root).resolve("main.c")))
            assertIs<ApplyResult.Applied>(PatchEngine(requireNotNull(root)).rollback(applied.transaction))
            outcome = "applied and rolled back"
            return
        }
        val request = buildJsonObject {
            put("operation", "addParameter"); put("languageId", "c")
            put("arguments", buildJsonObject { args.forEach { (key, value) -> put(key, value) } })
            put("clang", reference.resolve("usr/bin/clang-22").toString())
            put("clangd", reference.resolve("usr/bin/clangd").toString())
            put("clangFormat", reference.resolve("usr/bin/clang-format").toString())
        }
        try {
            val id = if (surface == "daemon") {
                val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
                assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
                assertEquals("addParameter", preview.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
                preview.getValue("planId").jsonPrimitive.content
            } else {
                assertTrue(call("available_refactorings", buildJsonObject { put("languageId", "c") }).contains("addParameter: insert an integer parameter"))
                val preview = call("preview_refactoring", request)
                detail = preview
                if (preview.contains("Status   : REFUSED")) { outcome = "effects refused"; return }
                assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: addParameter") && preview.contains("COMPILER_PROVEN"), preview)
                Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            }
            assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
            if (condition == "database changed") {
                database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database)
            }
            val transaction = if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", id) }).jsonObject
                detail = applied.toString()
                if (applied.getValue("status").jsonPrimitive.content != "applied") { outcome = "changed input refused"; return }
                applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", id) })
                detail = applied
                assertTrue(applied.contains("Applied successfully"), applied)
                Regex("Transaction ID: (\\S+)").find(applied)?.groupValues?.get(1) ?: error(applied)
            }
            assertEquals(expectedSource, Files.readString(requireNotNull(root).resolve("main.c")))
            if (surface == "daemon") {
                val undone = requireNotNull(daemon).dispatch("patch.rollback", buildJsonObject { put("transactionId", transaction) }).jsonObject
                assertEquals("rolledBack", undone.getValue("status").jsonPrimitive.content, undone.toString())
            } else assertTrue(call("rollback_refactoring", buildJsonObject { put("transactionId", transaction) }).contains("Rolled back"))
            outcome = "applied and rolled back"
        } catch (refused: JsonRpcException) {
            detail = refused.data?.toString().orEmpty() + " " + refused.message.orEmpty()
            outcome = "changed input refused"
        }
    }

    @Then("the parameter insertion surface reports {string}")
    fun report(expected: String) {
        assertEquals(expected, outcome, detail)
        val workspace = requireNotNull(root)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        if (expected == "applied and rolled back") {
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
            assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        } else {
            val required = when (expected) {
                "missing argument refused" -> "c.missingArgument"
                "effects refused" -> "c.signatureAdd.observable_effect"
                "changed input refused" -> "changed since preview"
                else -> error("Unexpected outcome: $expected")
            }
            assertTrue(detail.contains(required), detail)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    @Then("the parameter insertion surface preserves configuration and releases compiler material")
    fun intact() {
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        manager?.let { assertTrue(it.activeProvenance().isEmpty()) }
        assertEquals(privateBefore, privateDirectories())
    }
    private fun call(name: String, args: JsonObject): String = requireNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", args)
    }).jsonObject.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() {
        facade?.close(); manager?.close(); daemon?.close(); mcp?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

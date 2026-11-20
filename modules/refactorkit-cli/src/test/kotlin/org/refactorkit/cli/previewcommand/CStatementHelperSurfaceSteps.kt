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

class CStatementHelperSurfaceSteps {
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
    private var start = 0
    private var end = 0
    private var outcome = ""
    private var detail = ""

    @Given("the C statement helper surface {string} has condition {string}")
    fun open(route: String, case: String) {
        surface = route; condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-helper-surface-"); root = workspace
        val zero = case == "zero inputs Unicode CRLF"
        val prefix = "int required[REQUIRED - 16];\n" + if (zero) "/* π 😀 */\n" else ""
        val selected = if (zero) "int sum = 21;\n    int doubled = sum * 2;" else
            "int sum = ${if (case == "initializer changes state") "a++ + b" else "a + b"};\n    int doubled = sum * 2;"
        val header = if (zero) "static int calculate(void)" else "static int calculate(int a, int b)"
        val main = "int main(void) { return calculate(${if (zero) "" else "7, 14"}) == 42 ? 0 : 1; }\n"
        original = prefix + "$header {\n    $selected\n    return doubled;\n}\n" + main
        expectedSource = prefix + "static int compute(${if (zero) "void" else "int a, int b"}) {\n    $selected\n    return doubled;\n}\n\n" +
            "$header {\n    int doubled = compute(${if (zero) "" else "a, b"});\n    return doubled;\n}\n" + main
        if (zero) { original = original.replace("\n", "\r\n"); expectedSource = expectedSource.replace("\n", "\r\n") }
        val startChar = original.indexOf("int sum"); val endChar = original.indexOf(";", original.indexOf("int doubled")) + 1
        start = original.substring(0, startChar).toByteArray(Charsets.UTF_8).size
        end = original.substring(0, endChar).toByteArray(Charsets.UTF_8).size
        Files.writeString(workspace.resolve("main.c"), original)
        reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), if (zero) "-std=c11" else "-std=c17",
                "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database); snapshot = CProjectScanner().scan(workspace)
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

    @When("that surface requests the selected statement helper lifecycle")
    fun lifecycle() {
        val args = buildMap {
            put("file", "main.c"); put("symbol", "calculate"); put("helperName", "compute")
            if (condition != "selection omitted") { put("startByte", start.toString()); put("endByte", end.toString()) }
        }
        if (surface == "library") {
            val instance = requireNotNull(facade); instance.startFor(snapshot, "extractStatements")
            val plan = instance.preview(snapshot, "extractStatements", args)
            detail = "${plan.refusalCode}: ${plan.summary} " + plan.diagnosticsAfterPreview.joinToString { "${it.code}: ${it.message}" }
            if (plan.status == PatchStatus.REFUSED) { outcome = "missing argument refused"; return }
            assertEquals("extractStatements", plan.operation)
            assertEquals("c.statement-extract-v1", assertNotNull(plan.authorityLease).kind)
            assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
            val applied = assertIs<ApplyResult.Applied>(instance.apply(snapshot, plan, ApplyAuthorization.explicit("c-helper-surface", "bdd-caller"),
                instance.diagnosticsGate(snapshot, plan)))
            assertEquals(expectedSource, Files.readString(requireNotNull(root).resolve("main.c")))
            assertIs<ApplyResult.Applied>(PatchEngine(requireNotNull(root)).rollback(applied.transaction))
            outcome = "applied and rolled back"; return
        }
        val request = buildJsonObject {
            put("operation", "extractStatements"); put("languageId", "c")
            put("arguments", buildJsonObject { args.forEach { (key, value) -> put(key, value) } })
            put("clang", reference.resolve("usr/bin/clang-22").toString())
            put("clangd", reference.resolve("usr/bin/clangd").toString())
            put("clangFormat", reference.resolve("usr/bin/clang-format").toString())
        }
        try {
            val id = if (surface == "daemon") {
                val preview = requireNotNull(daemon).dispatch("c.preview", request).jsonObject
                assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
                assertEquals("extractStatements", preview.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content)
                preview.getValue("planId").jsonPrimitive.content
            } else {
                assertTrue(call("available_refactorings", buildJsonObject { put("languageId", "c") }).contains("extractStatements: extract integer declarations"))
                val preview = call("preview_refactoring", request); detail = preview
                if (preview.contains("Status   : REFUSED")) { outcome = "effects refused"; return }
                assertTrue(preview.contains("Status   : PREVIEW") && preview.contains("Operation: extractStatements") && preview.contains("COMPILER_PROVEN"), preview)
                Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            }
            assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
            if (condition == "database changed") { database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database) }
            val transaction = if (surface == "daemon") {
                val applied = requireNotNull(daemon).dispatch("refactor.apply", buildJsonObject { put("planId", id) }).jsonObject; detail = applied.toString()
                if (applied.getValue("status").jsonPrimitive.content != "applied") { outcome = "changed input refused"; return }
                applied.getValue("transactionId").jsonPrimitive.content
            } else {
                val applied = call("apply_refactoring", buildJsonObject { put("planId", id) }); detail = applied
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
            detail = refused.data?.toString().orEmpty() + " " + refused.message.orEmpty(); outcome = "changed input refused"
        }
    }

    @Then("the statement helper surface reports {string}")
    fun report(expected: String) {
        assertEquals(expected, outcome, detail)
        val workspace = requireNotNull(root); assertEquals(original, Files.readString(workspace.resolve("main.c")))
        if (expected == "applied and rolled back") {
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
            assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
        } else {
            val required = when (expected) {
                "missing argument refused" -> "c.missingArgument"
                "effects refused" -> "c.statementFacts.unsupported_expression"
                "changed input refused" -> "changed since preview"
                else -> error("Unexpected outcome: $expected")
            }
            assertTrue(detail.contains(required), detail); assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    @Then("the statement helper surface preserves configuration and releases compiler material")
    fun intact() {
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        manager?.let { assertTrue(it.activeProvenance().isEmpty()) }; assertEquals(privateBefore, privateDirectories())
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

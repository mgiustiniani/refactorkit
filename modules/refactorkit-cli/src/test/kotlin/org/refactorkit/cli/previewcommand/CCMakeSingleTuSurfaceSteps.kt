package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CCMakeSingleTuSurfaceSteps {
    private var root: Path? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private lateinit var surface: String
    private lateinit var source: ByteArray
    private lateinit var manifest: ByteArray
    private lateinit var database: ByteArray
    private lateinit var transactionId: String
    private lateinit var qualifiedClang: Path
    private lateinit var qualifiedClangd: Path
    private lateinit var qualifiedClangFormat: Path
    private var applied = false
    private var rolled = false
    private lateinit var previewPlan: JsonObject
    private lateinit var conflictingModes: Pair<Int, String>

    @Given("an isolated single-TU CMake workspace opened through {string}")
    fun open(route: String) {
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        listOf(qualifiedClang, qualifiedClangd, qualifiedClangFormat).forEach {
            assertTrue(Files.isExecutable(it), "Configured LLVM executable is required: $it")
        }
        surface = route
        val dir = Files.createTempDirectory("refactorkit-cmake-surface-")
        root = dir
        source = "#ifndef ENABLED\n#error missing captured definition\n#endif\nint main(void) { return 0; }\n".toByteArray()
        manifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray()
        database = """[{"directory":"$dir","file":"main.c","arguments":["$qualifiedClang","-std=c17","-D","ENABLED=1","-c","main.c"]}]""".toByteArray()
        Files.write(dir.resolve("main.c"), source)
        Files.write(dir.resolve("CMakeLists.txt"), manifest)
        Files.write(dir.resolve("compile_commands.json"), database)
        when (route) {
            "daemon" -> {
                daemon = DaemonSession()
                daemon!!.dispatch("project.open", buildJsonObject { put("root", dir.toString()) })
            }
            "MCP" -> {
                mcp = McpSession()
                mcp!!.dispatch("tools/call", buildJsonObject {
                    put("name", "project_scan")
                    put("arguments", buildJsonObject { put("root", dir.toString()) })
                })
            }
            "CLI" -> Unit
            else -> error("Unknown surface: $route")
        }
    }

    @When("the C source move is previewed approved and rolled back through that surface")
    fun transact() {
        val dir = requireNotNull(root)
        if (surface == "daemon") {
            val session = requireNotNull(daemon)
            val preview = session.dispatch("c.preview", previewArguments()).jsonObject
            assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content)
            assertTrue(preview.getValue("warnings").jsonArray.any {
                it.jsonPrimitive.content.contains("c.previewDiagnosticsVerified")
            }, "daemon preview must expose verified staged diagnostics before approval")
            val result = session.dispatch("refactor.apply", buildJsonObject {
                put("planId", preview.getValue("planId").jsonPrimitive.content)
            }).jsonObject
            assertEquals("applied", result.getValue("status").jsonPrimitive.content, result.toString())
            applied = true
            transactionId = result.getValue("transactionId").jsonPrimitive.content
            assertStaged(dir)
            val undo = session.dispatch("patch.rollback", buildJsonObject { put("transactionId", transactionId) }).jsonObject
            assertEquals("rolledBack", undo.getValue("status").jsonPrimitive.content, undo.toString())
            rolled = true
        } else {
            val session = requireNotNull(mcp)
            val preview = text(session.dispatch("tools/call", buildJsonObject {
                put("name", "preview_refactoring")
                put("arguments", buildJsonObject {
                    put("operation", "moveSource")
                    put("languageId", "c")
                    put("arguments", buildJsonObject { put("file", "main.c"); put("target", "src/main.c") })
                    put("clang", qualifiedClang.toString())
                    put("clangd", qualifiedClangd.toString())
                    put("clangFormat", qualifiedClangFormat.toString())
                })
            }).jsonObject)
            assertTrue(preview.contains("Status   : PREVIEW"), preview)
            assertTrue(preview.contains("c.previewDiagnosticsVerified"),
                "MCP preview must expose verified staged diagnostics before approval: $preview")
            val planId = Regex("Plan ID\\s+: (\\S+)").find(preview)?.groupValues?.get(1) ?: error(preview)
            val result = text(session.dispatch("tools/call", buildJsonObject {
                put("name", "apply_refactoring")
                put("arguments", buildJsonObject { put("planId", planId) })
            }).jsonObject)
            assertTrue(result.contains("Applied successfully"), result)
            applied = true
            transactionId = Regex("Transaction ID: (\\S+)").find(result)?.groupValues?.get(1) ?: error(result)
            assertStaged(dir)
            val undo = text(session.dispatch("tools/call", buildJsonObject {
                put("name", "rollback_refactoring")
                put("arguments", buildJsonObject { put("transactionId", transactionId) })
            }).jsonObject)
            assertTrue(undo.contains("Rolled back"), undo)
            rolled = true
        }
    }

    private fun previewArguments() = buildJsonObject {
        put("operation", "moveSource")
        put("arguments", buildJsonObject { put("file", "main.c"); put("target", "src/main.c") })
        put("clang", qualifiedClang.toString())
        put("clangd", qualifiedClangd.toString())
        put("clangFormat", qualifiedClangFormat.toString())
    }

    @When("the caller requests a read-only managed C source move preview")
    fun preview() {
        previewPlan = if (surface == "daemon") {
            requireNotNull(daemon).dispatch("c.preview", previewArguments()).jsonObject
        } else {
            check(surface == "CLI")
            cliPreview()
        }
    }

    @When("the caller requests the C move with conflicting flags {string} and {string}")
    fun requestConflictingModes(first: String, second: String) {
        conflictingModes = runCliMove(listOf(first, second))
    }

    @Then("the CLI refuses the conflicting C modes before changing the workspace")
    fun refusesConflictingModes() {
        val (exit, output) = conflictingModes
        assertEquals(2, exit, "the conflicting C modes must be refused with exit 2")
        assertTrue(output.contains("C command cannot combine --preview and --apply"), output)
        assertPreviewDidNotWrite()
    }

    private fun cliPreview(): JsonObject {
        val (exit, output) = runCliMove(listOf("--preview"))
        assertEquals(0, exit, output)
        return Json.parseToJsonElement(output).jsonObject
    }

    private fun runCliMove(flags: List<String>): Pair<Int, String> = synchronized(RefactorKitCli::class.java) {
        val command = listOf(
            "c", "move", requireNotNull(root).toString(), "--file", "main.c", "--target", "src/main.c",
            "--clang", qualifiedClang.toString(), "--clangd", qualifiedClangd.toString(),
            "--clang-format", qualifiedClangFormat.toString(),
        ) + flags
        val stdout = System.out
        val stderr = System.err
        val output = ByteArrayOutputStream()
        PrintStream(output, true, Charsets.UTF_8).use { stream ->
            val exit = try {
                System.setOut(stream)
                System.setErr(stream)
                RefactorKitCli().run(command)
            } finally {
                System.setOut(stdout)
                System.setErr(stderr)
            }
            exit to output.toString(Charsets.UTF_8)
        }
    }

    @Then("the preview identifies the C provider for the captured plan")
    fun identifiesProvider() {
        assertEquals("PREVIEW", previewPlan.getValue("status").jsonPrimitive.content)
        assertEquals("moveSource", previewPlan.getValue("operation").jsonPrimitive.content)
        assertTrue(previewPlan.getValue("planId").jsonPrimitive.content.isNotBlank())
        val provider = previewPlan.getValue("provider").jsonObject
        assertEquals("refactorkit-c", provider.getValue("name").jsonPrimitive.content)
        assertEquals(RefactorKitVersion.VERSION, provider.getValue("version").jsonPrimitive.content)
        assertPreviewDidNotWrite()
    }

    private fun assertPreviewDidNotWrite() {
        val workspace = requireNotNull(root)
        assertContentEquals(source, Files.readAllBytes(workspace.resolve("main.c")))
        assertContentEquals(manifest, Files.readAllBytes(workspace.resolve("CMakeLists.txt")))
        assertFalse(Files.exists(workspace.resolve("src/main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    @Then("the daemon advertises the executed C preview as a read-only project method")
    fun advertisesPreview() {
        identifiesProvider()
        val methods = DaemonSession().use { session ->
            session.dispatch("server.capabilities", null).jsonObject.getValue("methods").jsonArray
        }
        val advertised = methods.filter { it.jsonObject.getValue("name").jsonPrimitive.content == "c.preview" }
        val expected = buildJsonObject {
            put("name", "c.preview")
            put("stability", "experimental")
            put("requiresProject", true)
            put("writesWorkspace", false)
        }
        assertEquals(listOf(expected), advertised, "server.capabilities must advertise the executable c.preview method")
    }

    @Then("the surface reports managed apply and byte-exact rollback of C and CMake files")
    fun result() {
        val dir = requireNotNull(root)
        assertTrue(applied && rolled && transactionId.isNotBlank())
        assertContentEquals(source, Files.readAllBytes(dir.resolve("main.c")))
        assertContentEquals(manifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        assertFalse(Files.exists(dir.resolve("src/main.c")))
        assertTrue(Files.isDirectory(dir.resolve(".refactorkit/transactions")), "managed WAL/rollback must remain available")
    }

    @Then("no project build command or unowned file has been executed or written")
    fun noBuild() {
        val dir = requireNotNull(root)
        assertContentEquals(database, Files.readAllBytes(dir.resolve("compile_commands.json")))
        assertFalse(Files.exists(dir.resolve("main.o")))
        assertFalse(Files.exists(dir.resolve("src/main.o")))
    }

    private fun assertStaged(dir: Path) {
        assertFalse(Files.exists(dir.resolve("main.c")))
        assertContentEquals(source, Files.readAllBytes(dir.resolve("src/main.c")))
        assertEquals("""cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app src/main.c)
""", Files.readString(dir.resolve("CMakeLists.txt")))
    }

    private fun text(result: JsonObject): String =
        result.getValue("content").jsonArray.first().jsonObject.getValue("text").jsonPrimitive.content

    @After
    fun cleanup() {
        daemon?.close()
        mcp?.close()
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

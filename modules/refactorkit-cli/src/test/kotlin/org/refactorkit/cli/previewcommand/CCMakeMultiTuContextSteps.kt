package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CCMakeMultiTuContextSteps {
    private var root: Path? = null
    private lateinit var state: String
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private lateinit var plan: PatchPlan
    private lateinit var outcome: ApplyResult

    @Given("an isolated multi-TU CMake project in compile-context state {string}")
    fun project(state: String) {
        require(state in setOf("all-understood", "missing-entry", "target-flag", "alternate-compiler",
            "multi-source-command", "non-snapshot-entry")) { "Unknown context state: $state" }
        this.state = state
        val qualifiedToolchainRoot = Path.of(assertNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
            "The configured LLVM 22.1.8 qualification toolchain is required",
        ))
        val clangExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        val clangdExecutable = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        val clangFormatExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        listOf(clangExecutable, clangdExecutable, clangFormatExecutable).forEach {
            assertTrue(Files.isExecutable(it), "Reference toolchain required: $it")
        }
        val dir = Files.createTempDirectory("refactorkit-cmake-multi-tu-context-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        write("src/app/main.c", "int main(void) { return 0; }\n")
        write("src/app/helper.c", "int helper(void) { return 1; }\n")
        val sources = if (state == "multi-source-command") {
            write("src/app/helper-extra.c", "int extra(void) { return 2; }\n")
            "src/app/main.c src/app/helper.c src/app/helper-extra.c"
        } else "src/app/main.c src/app/helper.c"
        write("CMakeLists.txt", "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app $sources)\n")
        val clang = clangExecutable.toString()
        val helperArguments = buildList {
            add(if (state == "alternate-compiler") "/usr/bin/gcc" else clang)
            add("-std=c17")
            add("-c")
            if (state == "target-flag") {
                add("-target")
                add("x86_64-unknown-linux-gnu")
            }
            add("src/app/helper.c")
            if (state == "multi-source-command") add("src/app/helper-extra.c")
        }
        val database = buildJsonArray {
            add(entry(dir, "src/app/main.c", listOf(clang, "-std=c17", "-c", "src/app/main.c")))
            if (state != "missing-entry") add(entry(dir, "src/app/helper.c", helperArguments))
            if (state == "multi-source-command") {
                add(entry(dir, "src/app/helper-extra.c", listOf(clang, "-std=c17", "-c", "src/app/helper-extra.c")))
            }
            if (state == "non-snapshot-entry") {
                // All snapshot TUs still have entries; this extra source is not captured.
                add(entry(dir, "src/app/uncaptured.c", listOf(clang, "-std=c17", "-c", "src/app/uncaptured.c")))
            }
        }
        write("compile_commands.json", database.toString())
    }

    @When("the multi-TU compile-context source is previewed and explicitly approved for managed apply")
    fun apply() {
        val dir = requireNotNull(root)
        val qualifiedToolchainRoot = Path.of(assertNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
            "The configured LLVM 22.1.8 qualification toolchain is required",
        ))
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-22"),
            clangdExecutable = qualifiedToolchainRoot.resolve("usr/bin/clangd"),
            clangFormatExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Clang discovery failed: ${discovered.diagnostics}")
        }
        val snapshot = CProjectScanner().scan(dir)
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(snapshot, "moveSource",
                mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertOriginalFiles()
            assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")), "preview is read-only")
            assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")), "preview must not create WAL")
            outcome = facade.apply(snapshot, plan,
                ApplyAuthorization.explicit("cmake-multi-tu-context-story"), facade.diagnosticsGate(snapshot, plan))
        }
    }

    @Then("the multi-TU compile-context apply is {string}")
    fun result(disposition: String) {
        val dir = requireNotNull(root)
        when (disposition) {
            "applied" -> {
                assertIs<ApplyResult.Applied>(outcome)
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") }, plan.warnings.toString())
                assertFalse(Files.exists(dir.resolve("src/app/main.c")))
                assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(dir.resolve("src/app/moved/main.c")))
                assertEquals("cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app src/app/moved/main.c src/app/helper.c)\n",
                    Files.readString(dir.resolve("CMakeLists.txt")))
            }
            "refused" -> {
                val refused = assertIs<ApplyResult.Refused>(outcome)
                val reason = when (state) {
                    "missing-entry" -> "every captured C TU must have exactly one database entry"
                    "target-flag" -> "target, sysroot and include flags are not admitted"
                    "alternate-compiler" -> "compiler command differs from configured Clang"
                    "multi-source-command" -> "unexpected compiler argument"
                    "non-snapshot-entry" -> "database entries must exactly cover captured C translation units"
                    else -> error("Unexpected refused state: $state")
                }
                assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains("c.compilationCommandUnsupported") && it.message.contains(reason) },
                    refused.diagnostics.toString())
                assertNull(plan.authorityLease, "unsupported project context cannot grant a preview lease")
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsUnavailable:") && it.contains(reason) },
                    plan.warnings.toString())
                assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("the multi-TU compile-context WAL state is consistent")
    fun wal() {
        val dir = requireNotNull(root)
        if (outcome is ApplyResult.Applied) {
            assertTrue(Files.exists(dir.resolve(".refactorkit/transactions")), "apply must record WAL")
            val rollback = PatchEngine(dir).rollback((outcome as ApplyResult.Applied).transaction)
            assertIs<ApplyResult.Applied>(rollback)
        } else {
            assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")), "refusal must precede WAL")
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")), "refusal/rollback must leave no moved source")
        assertFalse(Files.exists(dir.resolve("src/app/uncaptured.c")), "a database entry must never create a source")
    }

    private fun entry(dir: Path, file: String, arguments: List<String>): JsonObject = buildJsonObject {
        put("directory", dir.toString())
        put("file", file)
        put("arguments", buildJsonArray { arguments.forEach { add(it) } })
    }

    private fun assertOriginalFiles() {
        val dir = requireNotNull(root)
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)), path) }
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

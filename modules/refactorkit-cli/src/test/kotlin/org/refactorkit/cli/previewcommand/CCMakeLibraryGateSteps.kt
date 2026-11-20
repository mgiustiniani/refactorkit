package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
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
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCMakeLibraryGateSteps {
    private var root: Path? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var source: ByteArray
    private lateinit var manifest: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var result: ApplyResult

    @Given("an isolated CMake source with a compiler-verified move preview")
    fun preview() {
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        val clang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        val clangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        val clangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        val dir = Files.createTempDirectory("refactorkit-cmake-gate-bypass-")
        root = dir
        source = "int main(void) { return 0; }\n".toByteArray()
        manifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray()
        Files.write(dir.resolve("main.c"), source)
        Files.write(dir.resolve("CMakeLists.txt"), manifest)
        Files.writeString(dir.resolve("compile_commands.json"), buildJsonArray {
            add(buildJsonObject {
                put("directory", dir.toString())
                put("file", "main.c")
                put("arguments", buildJsonArray {
                    add(clang.toString())
                    add("-std=c17")
                    add("-c")
                    add("main.c")
                })
            })
        }.toString())
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = clang,
            clangdExecutable = clangd,
            clangFormatExecutable = clangFormat,
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference Clang discovery failed: ${discovered.diagnostics}")
        }
        assertEquals(clang.toRealPath(), toolchain.clangExecutable.toRealPath())
        assertEquals(clangd.toRealPath(), toolchain.clangdExecutable.toRealPath())
        assertEquals(clangFormat.toRealPath(), toolchain.clangFormatExecutable.toRealPath())
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        snapshot = CProjectScanner().scan(dir)
        facade = CRefactoringFacade(toolchain)
        plan = requireNotNull(facade).preview(snapshot, "moveSource",
            mapOf("file" to "main.c", "target" to "src/main.c"))
        assertTrue(plan.status == PatchStatus.PREVIEW && plan.authorityLease?.kind == "c.cmake-single-tu",
            plan.summary)
    }

    @When("the compilation database changes and library apply receives a disabled caller gate")
    fun disabledGate() {
        val dir = requireNotNull(root)
        Files.write(dir.resolve("compile_commands.json"), Files.readAllBytes(dir.resolve("compile_commands.json")) + " \n".toByteArray())
        result = requireNotNull(facade).apply(snapshot, plan,
            ApplyAuthorization.explicit("c-library-gate-story"), DiagnosticsGate.disabled("caller-disabled"))
    }

    @Then("protected CMake apply refuses the stale database without a transaction")
    fun refusedBeforeWal() {
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any { diagnostic ->
            diagnostic.code == "snapshot.scopeChanged" ||
                (diagnostic.code == "diagnostics.unavailable" &&
                    diagnostic.message.contains("c.compilationDatabaseChanged"))
        }, refused.diagnostics.toString())
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }

    @Then("the original source and CMake manifest remain byte-exact")
    fun originals() {
        val dir = requireNotNull(root)
        assertContentEquals(source, Files.readAllBytes(dir.resolve("main.c")))
        assertContentEquals(manifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        assertFalse(Files.exists(dir.resolve("src/main.c")))
    }

    @After
    fun cleanup() {
        facade?.close()
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

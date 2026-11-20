package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainProvenance
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCMakeApplyContextSteps {
    private var root: Path? = null
    private lateinit var originalSource: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var lateManifest: ByteArray
    private lateinit var outcome: ApplyResult

    @Given("an isolated CMake-backed C source with a literal target binding")
    fun project() {
        val dir = Files.createTempDirectory("refactorkit-cmake-apply-")
        root = dir
        originalSource = "int main(void) { return 0; }\n".toByteArray(Charsets.UTF_8)
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve("main.c"), originalSource)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
    }

    @When("a managed CMake source move preview is approved for apply without captured TU context")
    fun apply() {
        val dir = requireNotNull(root)
        val snapshot = CProjectScanner().scan(dir)
        val toolchain = testToolchain(dir)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                val plan = facade.preview(snapshot, "moveSource", mapOf("file" to "main.c", "target" to "src/main.c"))
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.affectedFiles().contains(Path.of("CMakeLists.txt")))
                outcome = facade.apply(snapshot, plan, ApplyAuthorization.explicit("cmake-context-story"), facade.diagnosticsGate())
            }
        }
    }

    @Given("an isolated C source in a directory with no CMake manifest")
    fun noManifestInitially() {
        val dir = Files.createTempDirectory("refactorkit-cmake-late-")
        root = dir
        Files.createDirectories(dir.resolve("src/component"))
        originalSource = "int main(void) { return 0; }\n".toByteArray(Charsets.UTF_8)
        Files.write(dir.resolve("src/component/main.c"), originalSource)
    }

    @When("a nested CMake manifest is created after a source move preview and before approved apply")
    fun manifestAppearsAfterPreview() {
        val dir = requireNotNull(root)
        val snapshot = CProjectScanner().scan(dir)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(testToolchain(dir), manager).use { facade ->
                val plan = facade.preview(snapshot, "moveSource", mapOf(
                    "file" to "src/component/main.c", "target" to "src/other/main.c"))
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                lateManifest = "add_library(component STATIC main.c)\n".toByteArray(Charsets.UTF_8)
                Files.write(dir.resolve("src/component/CMakeLists.txt"), lateManifest)
                outcome = facade.apply(snapshot, plan, ApplyAuthorization.explicit("cmake-late-context-story"), facade.diagnosticsGate())
            }
        }
    }

    @Then("managed apply refuses because CMake translation unit context is unavailable")
    fun refusal() {
        val refused = assertIs<ApplyResult.Refused>(outcome)
        assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
            it.message.contains("c.compilationContextUnavailable") }, refused.diagnostics.toString())
    }

    @Then("the source and CMake manifest remain byte-exact with no transaction journal")
    fun noTransaction() {
        val dir = requireNotNull(root)
        assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
        assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        assertFalse(Files.exists(dir.resolve("src/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @Then("the original source and late CMake manifest remain unchanged with no transaction journal")
    fun lateManifestUnchanged() {
        val dir = requireNotNull(root)
        assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/component/main.c")))
        assertContentEquals(lateManifest, Files.readAllBytes(dir.resolve("src/component/CMakeLists.txt")))
        assertFalse(Files.exists(dir.resolve("src/other/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    private fun testToolchain(dir: Path) = ClangSemanticToolchain(
        clangExecutable = dir.resolve("unused-clang"),
        clangdExecutable = dir.resolve("unused-clangd"),
        clangFormatExecutable = dir.resolve("unused-clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = null, evidence = emptyList(),
        ),
    )

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

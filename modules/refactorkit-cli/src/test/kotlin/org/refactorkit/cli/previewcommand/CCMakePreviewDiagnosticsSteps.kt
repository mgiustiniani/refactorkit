package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CCMakePreviewDiagnosticsSteps {
    private var root: Path? = null
    private lateinit var originalSource: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var originalDatabase: ByteArray
    private lateinit var plan: PatchPlan
    private lateinit var qualifiedClang: Path
    private lateinit var qualifiedClangd: Path
    private lateinit var qualifiedClangFormat: Path

    @Given("an isolated CMake single-TU project with a {string} C source")
    fun project(kind: String) {
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        listOf(qualifiedClang, qualifiedClangd, qualifiedClangFormat).forEach {
            assertTrue(Files.isExecutable(it), "Configured LLVM executable is required: $it")
        }
        val dir = Files.createTempDirectory("refactorkit-cmake-preview-diag-")
        root = dir
        originalSource = (if (kind == "broken") "#error deliberate_preview_failure\n" else "")
            .plus("int main(void) { return 0; }\n").toByteArray()
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray()
        originalDatabase = """[{"directory":"$dir","file":"main.c","arguments":["$qualifiedClang","-std=c17","-c","main.c"]}]""".toByteArray()
        Files.write(dir.resolve("main.c"), originalSource)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
        Files.write(dir.resolve("compile_commands.json"), originalDatabase)
    }

    @When("the library previews the C source move with an exact compilation database")
    fun preview() {
        val dir = requireNotNull(root)
        val toolchain = when (val result = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedClang,
            clangdExecutable = qualifiedClangd,
            clangFormatExecutable = qualifiedClangFormat,
        ))) {
            is ClangToolchainDiscovery.Available -> result.toolchain
            is ClangToolchainDiscovery.Refused -> error("Clang toolchain not qualified: ${result.diagnostics}")
        }
        assertEquals(qualifiedClang.toRealPath(), toolchain.clangExecutable.toRealPath())
        assertEquals(qualifiedClangd.toRealPath(), toolchain.clangdExecutable.toRealPath())
        assertEquals(qualifiedClangFormat.toRealPath(), toolchain.clangFormatExecutable.toRealPath())
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(CProjectScanner().scan(dir), "moveSource",
                mapOf("file" to "main.c", "target" to "src/main.c"))
        }
    }

    @Then("the preview is {string} with explicit staged compiler evidence")
    fun outcome(disposition: String) {
        when (disposition) {
            "preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertTrue(plan.warnings.any { it.contains("c.previewDiagnosticsVerified") }, plan.warnings.toString())
                val lease = assertNotNull(plan.authorityLease, "pre-approval context evidence must bind the plan")
                assertEquals("c.cmake-single-tu", lease.kind)
                assertTrue(lease.attributes["c.compilationDatabaseSha256"]?.matches(Regex("[a-f0-9]{64}")) == true)
                assertTrue(lease.attributes["c.compilerSha256"]?.matches(Regex("[a-f0-9]{64}")) == true)
                assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            }
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.isEmpty(), "compiler-error preview must not carry an applicable edit")
                assertTrue((plan.diagnosticsBefore + plan.diagnosticsAfterPreview).any { it.message.contains("deliberate_preview_failure") },
                    plan.diagnosticsBefore.toString() + plan.diagnosticsAfterPreview.toString())
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("the preview leaves original source manifest and database bytes unchanged")
    fun noWrites() {
        val dir = requireNotNull(root)
        assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
        assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        assertContentEquals(originalDatabase, Files.readAllBytes(dir.resolve("compile_commands.json")))
        assertFalse(Files.exists(dir.resolve("src/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

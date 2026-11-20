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
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CCMakeLibraryTuApplySteps {
    private var root: Path? = null
    private lateinit var variant: String
    private lateinit var originalSource: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var database: ByteArray
    private lateinit var outcome: ApplyResult
    private lateinit var qualifiedClang: Path
    private lateinit var qualifiedClangd: Path
    private lateinit var qualifiedClangFormat: Path

    @Given("an isolated CMake library workspace with a {string} configuration")
    fun project(variant: String) {
        this.variant = variant
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        val dir = Files.createTempDirectory("refactorkit-cmake-library-tu-")
        root = dir
        originalSource = "int component(void) { return 7; }\n".toByteArray()
        val kind = when (variant) {
            "static-single" -> "STATIC"
            "shared" -> "SHARED"
            else -> "STATIC"
        }
        val extraSource = if (variant == "two-sources") "helper.c" to "int helper(void) { return 1; }\n" else null
        val srcList = if (extraSource != null) "src/component/a.c src/component/helper.c" else "src/component/a.c"
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component $kind $srcList)
""".toByteArray()
        database = buildJsonArray {
            add(buildJsonObject {
                put("directory", dir.toString())
                put("file", "src/component/a.c")
                put("arguments", buildJsonArray {
                    add(qualifiedClang.toString())
                    add("-std=c17")
                    add("-c")
                    add("src/component/a.c")
                })
            })
        }.toString().toByteArray()
        Files.createDirectories(dir.resolve("src/component"))
        Files.write(dir.resolve("src/component/a.c"), originalSource)
        if (extraSource != null) Files.writeString(dir.resolve("src/component/helper.c"), extraSource.second)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
        Files.write(dir.resolve("compile_commands.json"), database)
    }

    @When("the library source is previewed and explicitly approved for managed apply")
    fun apply() {
        val dir = requireNotNull(root)
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedClang,
            clangdExecutable = qualifiedClangd,
            clangFormatExecutable = qualifiedClangFormat,
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Clang discovery failed: ${discovered.diagnostics}")
        }
        assertEquals(qualifiedClang.toRealPath(), toolchain.clangExecutable.toRealPath())
        assertEquals(qualifiedClangd.toRealPath(), toolchain.clangdExecutable.toRealPath())
        assertEquals(qualifiedClangFormat.toRealPath(), toolchain.clangFormatExecutable.toRealPath())
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        val snapshot = CProjectScanner().scan(dir)
        CRefactoringFacade(toolchain).use { facade ->
            val plan = facade.preview(snapshot, "moveSource",
                mapOf("file" to "src/component/a.c", "target" to "src/lib/a.c"))
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertTrue(plan.workspaceEdit.affectedFiles().contains(Path.of("CMakeLists.txt")),
                "library move must include manifest binding update")
            outcome = facade.apply(snapshot, plan,
                ApplyAuthorization.explicit("cmake-library-tu-story"), facade.diagnosticsGate(snapshot, plan))
        }
    }

    @Then("managed apply is {string} and the CMake binding updates atomically")
    fun result(disposition: String) {
        val dir = requireNotNull(root)
        when (disposition) {
            "applied" -> {
                assertIs<ApplyResult.Applied>(outcome)
                assertFalse(Files.exists(dir.resolve("src/component/a.c")))
                assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/lib/a.c")))
                val manifest = Files.readString(dir.resolve("CMakeLists.txt"))
                assertTrue(manifest.contains("src/lib/a.c"), manifest)
                assertFalse(manifest.contains("src/component/a.c"), manifest)
            }
            "refused" -> {
                val refused = assertIs<ApplyResult.Refused>(outcome)
                assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains("c.compilationContextUnavailable") || it.message.contains("c.compilationCommandUnsupported") },
                    refused.diagnostics.toString())
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("the applied source and manifest roll back byte-exact or refusal leaves no transaction")
    fun rollbackOrNoWal() {
        val dir = requireNotNull(root)
        if (outcome is ApplyResult.Applied) {
            val transaction = (outcome as ApplyResult.Applied).transaction
            val result = PatchEngine(dir).rollback(transaction)
            assertIs<ApplyResult.Applied>(result)
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/component/a.c")))
            assertFalse(Files.exists(dir.resolve("src/lib/a.c")))
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        } else {
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/component/a.c")))
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
            assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
        }
        assertFalse(Files.exists(dir.resolve("src/lib/a.c")), if (outcome is ApplyResult.Refused) "refused apply must not write" else "rollback must remove")
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

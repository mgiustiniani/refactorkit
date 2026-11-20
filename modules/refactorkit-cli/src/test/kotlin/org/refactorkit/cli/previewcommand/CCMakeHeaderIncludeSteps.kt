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

class CCMakeHeaderIncludeSteps {
    private var root: Path? = null
    private lateinit var includeType: String
    private lateinit var originalSource: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var outcome: ApplyResult
    private lateinit var qualifiedClang: Path
    private lateinit var qualifiedClangd: Path
    private lateinit var qualifiedClangFormat: Path

    @Given("an isolated CMake project with a {string} include in the source")
    fun project(kind: String) {
        includeType = kind
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        val dir = Files.createTempDirectory("refactorkit-cmake-header-")
        root = dir
        val includeLine = when (kind) {
            "system-angled" -> "#include <stdio.h>\n"

            "workspace-quoted" -> "#include \"local.h\"\n"
            "include-next" -> "#include_next <stdlib.h>\n"
            "file-macro" -> "const char* p = __FILE__;\n"
            else -> ""
        }
        val body = if (kind == "file-macro")
            "${includeLine}int main(void) { return 0; }\n"
        else
            "${includeLine}int main(void) { return 0; }\n"
        originalSource = body.toByteArray()
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray()
        val database = buildJsonArray {
            add(buildJsonObject {
                put("directory", dir.toString())
                put("file", "main.c")
                put("arguments", buildJsonArray {
                    add(qualifiedClang.toString())
                    add("-std=c17")
                    add("-c")
                    add("main.c")
                })
            })
        }.toString().toByteArray()
        Files.write(dir.resolve("main.c"), originalSource)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
        Files.write(dir.resolve("compile_commands.json"), database)
        if (kind == "workspace-quoted") {
            Files.writeString(dir.resolve("local.h"), "#define LOCAL 1\n")
        }
    }

    @When("the source is previewed and explicitly approved for managed apply")
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
                mapOf("file" to "main.c", "target" to "src/main.c"))
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            outcome = facade.apply(snapshot, plan,
                ApplyAuthorization.explicit("cmake-header-story"), facade.diagnosticsGate(snapshot, plan))
        }
    }

    @Then("managed apply is {string} with staged compiler verification")
    fun result(disposition: String) {
        val dir = requireNotNull(root)
        when (disposition) {
            "applied" -> {
                assertIs<ApplyResult.Applied>(outcome)
                assertFalse(Files.exists(dir.resolve("main.c")))
                assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/main.c")))
            }
            "refused" -> {
                val refused = assertIs<ApplyResult.Refused>(outcome)
                assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" },
                    refused.diagnostics.toString())
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("the header move WAL state is consistent with byte-exact restoration")
    fun walOrNoWal() {
        val dir = requireNotNull(root)
        if (outcome is ApplyResult.Applied) {
            val transaction = (outcome as ApplyResult.Applied).transaction
            val result = PatchEngine(dir).rollback(transaction)
            assertIs<ApplyResult.Applied>(result)
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
            assertFalse(Files.exists(dir.resolve("src/main.c")))
        } else {
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
            assertFalse(Files.exists(dir.resolve("src/main.c")))
            assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
        }
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

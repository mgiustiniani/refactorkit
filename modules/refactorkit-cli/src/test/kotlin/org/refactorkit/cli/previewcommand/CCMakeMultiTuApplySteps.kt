package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.opentest4j.TestAbortedException
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
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

class CCMakeMultiTuApplySteps {
    private var root: Path? = null
    private lateinit var condition: String
    private lateinit var movedSource: ByteArray
    private lateinit var componentSources: Map<Path, ByteArray>
    private lateinit var otherSource: ByteArray
    private lateinit var originalMain: ByteArray
    private lateinit var originalHelper: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var outcome: ApplyResult
    private lateinit var clangExecutable: Path
    private lateinit var clangdExecutable: Path
    private lateinit var clangFormatExecutable: Path

    @Given("an isolated multi-TU CMake project with {string}")
    fun project(condition: String) {
        this.condition = condition
        val toolchainRoot = System.getenv("REFACTORKIT_C_QUALIFICATION_TOOLCHAIN")?.let(Path::of)
        val toolchainBin = toolchainRoot?.resolve("usr/bin") ?: Path.of("/usr/bin")
        clangExecutable = toolchainBin.resolve("clang-22").toAbsolutePath().normalize()
        clangdExecutable = toolchainBin.resolve("clangd").toAbsolutePath().normalize()
        clangFormatExecutable = toolchainBin.resolve("clang-format").toAbsolutePath().normalize()
        if (listOf(clangExecutable, clangdExecutable, clangFormatExecutable).any { !Files.isExecutable(it) }) {
            throw TestAbortedException("Reference Clang 22.1.8 toolchain absent; multi-TU case unexercised")
        }
        val dir = Files.createTempDirectory("refactorkit-cmake-multi-tu-")
        root = dir
        if (condition == "component-relocation") {
            originalMain = "int component_first(void) { return 7; }\n".toByteArray()
            originalHelper = "int component_second(void) { return 11; }\n".toByteArray()
            movedSource = originalMain
            otherSource = originalHelper
            componentSources = mapOf(
                Path.of("src/component/first.c") to originalMain,
                Path.of("src/component/second.c") to originalHelper,
            )
            originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC src/component/first.c src/component/second.c)
""".toByteArray()
            val database = """[
  {"directory":"$dir","file":"src/component/first.c","arguments":["$clangExecutable","-std=c17","-c","src/component/first.c"]},
  {"directory":"$dir","file":"src/component/second.c","arguments":["$clangExecutable","-std=c17","-c","src/component/second.c"]}
]""".toByteArray()
            Files.createDirectories(dir.resolve("src/component"))
            componentSources.forEach { (path, content) -> Files.write(dir.resolve(path), content) }
            Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
            Files.write(dir.resolve("compile_commands.json"), database)
            return
        }
        if (condition == "shared-header") {
            movedSource = "#define COMPONENT_VALUE 7\n".toByteArray()
            originalMain = "#include \"../../include/api.h\"\nint main(void) { return COMPONENT_VALUE; }\n".toByteArray()
            originalHelper = "#include \"../../include/api.h\"\nint helper(void) { return COMPONENT_VALUE; }\n".toByteArray()
            otherSource = originalHelper
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC src/component/a.c src/component/b.c)
target_sources(component PRIVATE include/api.h)
""".toByteArray()
            val database = """[
  {"directory":"$dir","file":"src/component/a.c","arguments":["$clangExecutable","-std=c17","-c","src/component/a.c"]},
  {"directory":"$dir","file":"src/component/b.c","arguments":["$clangExecutable","-std=c17","-c","src/component/b.c"]}
]""".toByteArray()
            Files.createDirectories(dir.resolve("src/component"))
            Files.createDirectories(dir.resolve("include"))
            Files.write(dir.resolve("src/component/a.c"), originalMain)
            Files.write(dir.resolve("src/component/b.c"), originalHelper)
            Files.write(dir.resolve("include/api.h"), movedSource)
            Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
            Files.write(dir.resolve("compile_commands.json"), database)
            return
        }
        movedSource = "int component(void) { return 7; }\n".toByteArray()
        originalMain = movedSource
        otherSource = when (condition) {
            "other-file-includes-moved" -> "#include \"a.c\"\nint helper(void) { return 1; }\n".toByteArray()
            else -> "int helper(void) { return 1; }\n".toByteArray()
        }
        originalHelper = otherSource
        val movedContent = when (condition) {
            "moved-file-has-include" -> "#include \"header.h\"\nint component(void) { return 7; }\n".toByteArray()
            else -> movedSource
        }
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC src/component/a.c src/component/b.c)
""".toByteArray()
        val database = """[
  {"directory":"$dir","file":"src/component/a.c","arguments":["$clangExecutable","-std=c17","-c","src/component/a.c"]},
  {"directory":"$dir","file":"src/component/b.c","arguments":["$clangExecutable","-std=c17","-c","src/component/b.c"]}
]""".toByteArray()
        Files.createDirectories(dir.resolve("src/component"))
        Files.write(dir.resolve("src/component/a.c"), movedContent)
        Files.write(dir.resolve("src/component/b.c"), otherSource)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
        Files.write(dir.resolve("compile_commands.json"), database)
        if (condition == "moved-file-has-include") Files.writeString(dir.resolve("src/component/header.h"), "#define HDR 1\n")
        movedSource = movedContent
    }

    @When("a leaf source is previewed and explicitly approved for managed apply")
    fun apply() {
        val dir = requireNotNull(root)
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = clangExecutable,
            clangdExecutable = clangdExecutable,
            clangFormatExecutable = clangFormatExecutable,
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Clang discovery failed: ${discovered.diagnostics}")
        }
        val snapshot = CProjectScanner().scan(dir)
        CRefactoringFacade(toolchain).use { facade ->
            val plan = when (condition) {
                "shared-header" -> facade.preview(snapshot, "moveSource",
                    mapOf("file" to "include/api.h", "target" to "include/public/api.h"))
                "component-relocation" -> facade.preview(snapshot, "relocateComponent",
                    mapOf("componentDir" to "src/component", "newDir" to "src/library"))
                else -> facade.preview(snapshot, "moveSource",
                    mapOf("file" to "src/component/a.c", "target" to "src/lib/a.c"))
            }
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            if (condition == "component-relocation") {
                assertEquals(setOf(
                    Path.of("src/component/first.c") to Path.of("src/library/first.c"),
                    Path.of("src/component/second.c") to Path.of("src/library/second.c"),
                ), plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().map { it.path to it.newPath }.toSet())
                assertEquals("2", plan.authorityLease?.attributes?.get("c.translationUnitCount"))
                assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR }, plan.diagnosticsBefore.toString())
                assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR }, plan.diagnosticsAfterPreview.toString())
            }
            outcome = facade.apply(snapshot, plan,
                ApplyAuthorization.explicit("cmake-multi-tu-story"), facade.diagnosticsGate(snapshot, plan))
        }
    }

    @When("a captured shared header is previewed and approved for managed apply")
    fun applySharedHeader() = apply()

    @When("the captured C component is previewed and approved for managed apply")
    fun applyComponent() = apply()

    @Then("managed apply is {string} with correct staged diagnostics")
    fun result(disposition: String) {
        val dir = requireNotNull(root)
        when (disposition) {
            "applied" -> {
                assertIs<ApplyResult.Applied>(outcome,
                    (outcome as? ApplyResult.Refused)?.diagnostics?.joinToString { "${it.code}: ${it.message}" }.orEmpty())
                if (condition == "shared-header") {
                    assertFalse(Files.exists(dir.resolve("include/api.h")))
                    assertContentEquals(movedSource, Files.readAllBytes(dir.resolve("include/public/api.h")))
                    assertContentEquals("#include \"../../include/public/api.h\"\nint main(void) { return COMPONENT_VALUE; }\n".toByteArray(),
                        Files.readAllBytes(dir.resolve("src/component/a.c")))
                    assertContentEquals("#include \"../../include/public/api.h\"\nint helper(void) { return COMPONENT_VALUE; }\n".toByteArray(),
                        Files.readAllBytes(dir.resolve("src/component/b.c")))
                    val manifest = Files.readString(dir.resolve("CMakeLists.txt"))
                    assertTrue(manifest.contains("target_sources(component PRIVATE include/public/api.h)"), manifest)
                } else if (condition == "component-relocation") {
                    componentSources.forEach { (old, bytes) ->
                        val moved = Path.of("src/library").resolve(Path.of("src/component").relativize(old))
                        assertFalse(Files.exists(dir.resolve(old)), "old component source $old must be absent")
                        assertContentEquals(bytes, Files.readAllBytes(dir.resolve(moved)))
                    }
                    val manifest = Files.readString(dir.resolve("CMakeLists.txt"))
                    assertTrue(manifest.contains("src/library/first.c"), manifest)
                    assertTrue(manifest.contains("src/library/second.c"), manifest)
                    assertFalse(manifest.contains("src/component/"), manifest)
                } else {
                    assertFalse(Files.exists(dir.resolve("src/component/a.c")))
                    assertContentEquals(movedSource, Files.readAllBytes(dir.resolve("src/lib/a.c")))
                    assertTrue(Files.exists(dir.resolve("src/component/b.c")), "other TU must remain")
                    val manifest = Files.readString(dir.resolve("CMakeLists.txt"))
                    assertTrue(manifest.contains("src/lib/a.c"), manifest)
                    assertTrue(manifest.contains("src/component/b.c"), manifest)
                }
            }
            "refused" -> {
                val refused = assertIs<ApplyResult.Refused>(outcome)
                assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains("c.compilationContextUnavailable") },
                    refused.diagnostics.toString())
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("applied or refused state is consistent with WAL and rollback")
    fun walConsistency() {
        val dir = requireNotNull(root)
        if (outcome is ApplyResult.Applied) {
            val transaction = (outcome as ApplyResult.Applied).transaction
            val result = PatchEngine(dir).rollback(transaction)
            assertIs<ApplyResult.Applied>(result)
            if (condition == "shared-header") {
                assertContentEquals(movedSource, Files.readAllBytes(dir.resolve("include/api.h")))
                assertFalse(Files.exists(dir.resolve("include/public/api.h")))
                assertContentEquals(originalMain, Files.readAllBytes(dir.resolve("src/component/a.c")))
                assertContentEquals(originalHelper, Files.readAllBytes(dir.resolve("src/component/b.c")))
            } else if (condition == "component-relocation") {
                componentSources.forEach { (path, bytes) ->
                    assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)))
                    val moved = Path.of("src/library").resolve(Path.of("src/component").relativize(path))
                    assertFalse(Files.exists(dir.resolve(moved)), "rollback must remove $moved")
                }
            } else {
                assertContentEquals(movedSource, Files.readAllBytes(dir.resolve("src/component/a.c")))
                assertFalse(Files.exists(dir.resolve("src/lib/a.c")))
            }
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        } else {
            if (condition == "component-relocation") {
                componentSources.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path))) }
                assertFalse(Files.exists(dir.resolve("src/library")))
            } else {
                assertContentEquals(movedSource, Files.readAllBytes(dir.resolve("src/component/a.c")))
            }
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
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

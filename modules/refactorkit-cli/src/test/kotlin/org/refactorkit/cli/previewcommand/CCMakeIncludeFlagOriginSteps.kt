package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CCompilerDiagnostics
import org.refactorkit.c.CDiagnosticsResult
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CCMakeIncludeFlagOriginSteps {
    private var root: Path? = null
    private lateinit var state: String
    private lateinit var disposition: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var expectedManifest: String
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private val sources = listOf("src/app/main.c", "src/app/helper.c")

    @Given("an isolated CMake include-flag fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("no-roots", "moved-owner-root", "other-owner-root",
            "source-parent-root", "empty-unused-root", "matching-command-root"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-include-flag-")
        root = dir
        listOf("src/app", "deps/app", "deps/support", "deps/empty").forEach { Files.createDirectories(dir.resolve(it)) }
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        write("src/app/main.c", """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_include_flag_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201112L, "main_dialect_not_exact");
int main(void) { return 0; }
""")
        write("src/app/helper.c", """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_include_flag_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
int helper(void) { return 1; }
""")
        val include = when (state) {
            "moved-owner-root", "matching-command-root" -> "target_include_directories(app PRIVATE deps/app)\n"
            "other-owner-root" -> "target_include_directories(support PRIVATE deps/support)\n"
            "source-parent-root" -> "target_include_directories(app PRIVATE src/app)\n"
            "empty-unused-root" -> "target_include_directories(support PRIVATE deps/empty)\n"
            else -> ""
        }
        fun manifest(main: String) = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n" +
            "add_executable(app $main)\nadd_library(support STATIC src/app/helper.c)\n" + include
        write("CMakeLists.txt", manifest("src/app/main.c"))
        expectedManifest = manifest("src/app/moved/main.c")
        val qualifiedToolchainRoot = Path.of(assertNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
            "The configured LLVM 22.1.8 qualification toolchain is required",
        ))
        val qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        val qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        val qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        toolchain = when (val discovery = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedClang,
            clangdExecutable = qualifiedClangd,
            clangFormatExecutable = qualifiedClangFormat,
        ))) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain required: ${discovery.diagnostics}")
        }
        assertEquals(qualifiedClang.toRealPath(), toolchain.clangExecutable.toRealPath())
        assertEquals(qualifiedClangd.toRealPath(), toolchain.clangdExecutable.toRealPath())
        assertEquals(qualifiedClangFormat.toRealPath(), toolchain.clangFormatExecutable.toRealPath())
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        write("compile_commands.json", buildJsonArray {
            sources.forEach { source ->
                add(buildJsonObject {
                    put("directory", dir.toString())
                    put("file", source)
                    put("arguments", buildJsonArray {
                        add(toolchain.clangExecutable.toString())
                        flags(source).filter { it != "-pedantic-errors" }.forEach { add(it) }
                        if (state == "matching-command-root" && source.endsWith("main.c")) {
                            add("-I")
                            add("deps/app")
                        }
                        add("-c")
                        add(source)
                    })
                })
            }
        }.toString())
    }

    @When("that include-flag fixture previews its main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(sources.toSet(), snapshot.files.map { it.path.toString() }.toSet())
        // Isolate unconsumed manifest origins from compiler/header-error guards.
        // These are direct configured syntax checks, never database command execution.
        val compiler = CCompilerDiagnostics(toolchain)
        sources.forEach { source ->
            val checked = assertIs<CDiagnosticsResult.Available>(compiler.analyze(snapshot, Path.of(source), flags(source)))
            assertTrue(checked.diagnostics.isEmpty(), checked.diagnostics.toString())
            assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                checked.processProvenance?.executableSha256)
        }
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(snapshot, "moveSource", mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @Then("its CMake include-flag disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertEquals(PatchStatus.PREVIEW, plan.status, "unavailable structural preview remains inspectable: ${plan.summary}")
        if (disposition == "applicable") {
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("literal-root-no-include-flags-v1", lease.attributes["c.cmakeFlagPolicy"])
            assertEquals("literal-root-tu-ownership-v1", lease.attributes["c.targetOwnershipScope"])
            assertEquals("captured-project-tus-v1", lease.attributes["c.diagnosticsScope"])
            assertEquals("2", lease.attributes["c.translationUnitCount"])
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertTrue(disposition in setOf("unavailable-origin", "unavailable-command"))
            assertNull(plan.authorityLease, "unconsumed include-flag origins must not get a managed lease")
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsUnavailable:") &&
                it.contains(refusalReason()) }, plan.warnings.toString())
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained no-include flag policy permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-include-flag-origin-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-include-flag-policy")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.cmakeFlagPolicy",
                    lease.attributes + ("c.cmakeFlagPolicy" to "literal-root-no-include-flags-v0")).forEach { attributes ->
                    val altered = plan.copy(authorityLease = OperationAuthorityLease(
                        kind = lease.kind, operation = lease.operation, snapshotHash = lease.snapshotHash,
                        evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                        workspaceEditSha256 = lease.workspaceEditSha256,
                        requiredClasspathEvidence = lease.requiredClasspathEvidence,
                        requiredFileEvidence = lease.requiredFileEvidence, attributes = attributes,
                    ))
                    val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, altered, authorization, callerGate))
                    assertTrue(denied.diagnostics.any { it.code == "diagnostics.unavailable" &&
                        it.message.contains("captured identity") }, denied.diagnostics.toString())
                    assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
                    assertOriginalFiles()
                }
                val applied = assertIs<ApplyResult.Applied>(facade.apply(snapshot, plan, authorization, callerGate))
                try {
                    assertTrue(Files.exists(dir.resolve(".refactorkit/transactions")))
                    assertFalse(Files.exists(dir.resolve("src/app/main.c")))
                    assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(dir.resolve("src/app/moved/main.c")))
                    assertEquals(expectedManifest, Files.readString(dir.resolve("CMakeLists.txt")))
                    assertContentEquals(originalFiles.getValue("src/app/helper.c"), Files.readAllBytes(dir.resolve("src/app/helper.c")))
                } finally {
                    assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
                }
            } else {
                val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, plan, authorization, callerGate))
                assertTrue(denied.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains(refusalReason()) }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
    }

    private fun flags(source: String): List<String> = if (source.endsWith("main.c"))
        listOf("-std=c11", "-pedantic-errors", "-DMAIN_ONLY=3")
    else listOf("-std=c17", "-pedantic-errors", "-DHELPER_ONLY=7")

    private fun refusalReason(): String = if (state == "matching-command-root")
        "c.compilationCommandUnsupported: target, sysroot and include flags are not admitted"
    else "c.cmakeFlagsUnconsumed: literal target include roots are not admitted by no-include command policy"

    private fun assertOriginalFiles() {
        val dir = requireNotNull(root)
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)), path) }
        if (::snapshot.isInitialized) assertEquals(snapshot.hash, CProjectScanner().scan(dir).hash)
    }

    @After
    fun cleanup() {
        root?.let { dir -> Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } }
    }
}

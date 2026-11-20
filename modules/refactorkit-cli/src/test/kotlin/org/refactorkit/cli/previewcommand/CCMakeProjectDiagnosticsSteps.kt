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
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
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

class CCMakeProjectDiagnosticsSteps {
    private var root: Path? = null
    private lateinit var qualifiedToolchainRoot: Path
    private lateinit var state: String
    private lateinit var disposition: String
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan

    @Given("an isolated bounded CMake project in diagnostics state {string}")
    fun project(state: String) {
        require(state in setOf("clean-distinct-contexts", "helper-error", "helper-define-error",
            "third-tu-error", "staged-helper-dependency", "project-limit")) { "Unknown diagnostics state: $state" }
        this.state = state
        qualifiedToolchainRoot = Path.of(assertNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
            "The configured LLVM 22.1.8 qualification toolchain is required",
        ))
        listOf("usr/bin/clang-22", "usr/bin/clangd", "usr/bin/clang-format").forEach {
            assertTrue(Files.isExecutable(qualifiedToolchainRoot.resolve(it)), "Reference toolchain required: $it")
        }
        val dir = Files.createTempDirectory("refactorkit-cmake-project-diagnostics-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        write("src/app/main.c", """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201112L, "main_dialect_not_exact");
#pragma message("rk_main_context")
int main(void) { return 0; }
""")
        val failure = when (state) {
            "helper-error" -> "#error rk_helper_syntax_error\n"
            "helper-define-error" -> "#ifdef HELPER_ONLY\n#error rk_helper_define_error\n#endif\n"
            "staged-helper-dependency" -> "#if !__has_include(\"main.c\")\n#error rk_helper_staged_dependency_lost\n#endif\n"
            else -> ""
        }
        write("src/app/helper.c", """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
${failure}#pragma message("rk_helper_context")
int helper(void) { return 1; }
""")
        val extraSources = when (state) {
            "third-tu-error" -> listOf("src/app/zz_extra.c")
            "project-limit" -> (1..7).map { "src/app/extra$it.c" }
            else -> emptyList()
        }
        extraSources.forEachIndexed { index, path ->
            write(path, (if (state == "third-tu-error") "#error rk_third_tu_failure\n" else "") +
                "int extra$index(void) { return $index; }\n")
        }
        val sources = listOf("src/app/main.c", "src/app/helper.c") + extraSources
        write("CMakeLists.txt", "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app ${sources.joinToString(" ")})\n")
        val database = buildJsonArray {
            sources.forEach { source ->
                add(buildJsonObject {
                    put("directory", dir.toString())
                    put("file", source)
                    put("arguments", buildJsonArray {
                        add(qualifiedToolchainRoot.resolve("usr/bin/clang-22").toString())
                        add(if (source == "src/app/main.c") "-std=c11" else "-std=c17")
                        when (source) {
                            "src/app/main.c" -> add("-DMAIN_ONLY=3")
                            "src/app/helper.c" -> add("-DHELPER_ONLY=7")
                        }
                        add("-c")
                        add(source)
                    })
                })
            }
        }
        write("compile_commands.json", database.toString())
    }

    @When("the captured CMake project is previewed for moving its main translation unit")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        CRefactoringFacade(toolchain()).use { facade ->
            plan = facade.preview(snapshot, "moveSource",
                mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")), "preview is read-only")
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")), "preview must not write WAL")
    }

    @Then("the project-wide CMake diagnostics disposition is {string}")
    fun result(disposition: String) {
        this.disposition = disposition
        when (disposition) {
            "applicable" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertWarnings(plan.diagnosticsBefore, "src/app/main.c")
                assertWarnings(plan.diagnosticsAfterPreview, "src/app/moved/main.c")
                val lease = assertNotNull(plan.authorityLease)
                assertEquals("c.cmake-single-tu", lease.kind, "historical kind is retained")
                assertEquals("captured-project-tus-v1", lease.attributes["c.diagnosticsScope"])
                assertEquals("2", lease.attributes["c.translationUnitCount"])
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") &&
                    it.contains("all 2 captured CMake TUs") }, plan.warnings.toString())
            }
            "refused-baseline", "refused-staged" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertEquals("c.previewDiagnosticsUnavailable", plan.refusalCode)
                assertTrue(plan.workspaceEdit.edits.isEmpty() && plan.affectedFiles.isEmpty())
                assertFalse(plan.requiresUserApproval)
                val marker = when (state) {
                    "helper-error" -> "rk_helper_syntax_error"
                    "helper-define-error" -> "rk_helper_define_error"
                    "third-tu-error" -> "rk_third_tu_failure"
                    "staged-helper-dependency" -> "rk_helper_staged_dependency_lost"
                    else -> error("Unexpected compiler failure: $state")
                }
                val source = if (state == "third-tu-error") "src/app/zz_extra.c" else "src/app/helper.c"
                val errors = if (disposition == "refused-baseline") {
                    assertTrue(plan.diagnosticsAfterPreview.isEmpty())
                    plan.diagnosticsBefore
                } else {
                    assertWarnings(plan.diagnosticsBefore, "src/app/main.c")
                    plan.diagnosticsAfterPreview
                }
                assertTrue(errors.any { it.severity == Diagnostic.Severity.ERROR &&
                    it.message.contains(marker) && it.message.contains(source) }, errors.toString())
                assertNull(plan.authorityLease)
                assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
            }
            "unavailable" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, "structural preview stays inspectable")
                assertNull(plan.authorityLease)
                assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
                assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsUnavailable:") &&
                    it.contains("project exceeds managed diagnostics TU limit 8") }, plan.warnings.toString())
                assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
            }
            else -> error("Unknown disposition: $disposition")
        }
    }

    @Then("the project-wide diagnostics outcome permits byte-exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain()).use { facade ->
            val outcome = facade.apply(snapshot, plan, ApplyAuthorization.explicit("cmake-project-diagnostics-story"),
                DiagnosticsGate.disabled("caller-cannot-bypass-project-diagnostics"))
            if (disposition == "applicable") {
                val applied = assertIs<ApplyResult.Applied>(outcome)
                assertTrue(Files.exists(dir.resolve(".refactorkit/transactions")))
                assertFalse(Files.exists(dir.resolve("src/app/main.c")))
                assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(dir.resolve("src/app/moved/main.c")))
                assertEquals("cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app src/app/moved/main.c src/app/helper.c)\n",
                    Files.readString(dir.resolve("CMakeLists.txt")))
                assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
            } else {
                val refused = assertIs<ApplyResult.Refused>(outcome)
                if (disposition == "unavailable") {
                    assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
                        it.message.contains("project exceeds managed diagnostics TU limit 8") }, refused.diagnostics.toString())
                } else {
                    assertTrue(refused.diagnostics.any { it.code == "plan.notPreview" }, refused.diagnostics.toString())
                }
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")), "refusal must precede WAL")
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
    }

    private fun assertWarnings(diagnostics: List<Diagnostic>, mainPath: String) {
        val dir = requireNotNull(root).toAbsolutePath().normalize()
        assertEquals(2, diagnostics.size, diagnostics.toString())
        mapOf("rk_main_context" to mainPath, "rk_helper_context" to "src/app/helper.c").forEach { (marker, source) ->
            val diagnostic = diagnostics.single { it.message.contains(marker) }
            assertEquals(Diagnostic.Severity.WARNING, diagnostic.severity)
            assertEquals(DiagnosticEvidence.COMPILER, diagnostic.evidence)
            assertEquals(dir.resolve(source), diagnostic.location?.path, "diagnostic must name the workspace TU, not a deleted overlay")
        }
    }

    private fun assertOriginalFiles() {
        val dir = requireNotNull(root)
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)), path) }
    }

    private fun toolchain() = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
        workspaceRoot = requireNotNull(root),
        clangExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-22"),
        clangdExecutable = qualifiedToolchainRoot.resolve("usr/bin/clangd"),
        clangFormatExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-format"),
    ))) {
        is ClangToolchainDiscovery.Available -> discovered.toolchain
        is ClangToolchainDiscovery.Refused -> error("Clang discovery failed: ${discovered.diagnostics}")
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

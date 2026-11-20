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
import org.refactorkit.c.CMovePlanner
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainFileEvidence
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.c.ClangToolchainRuntimeProvider
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scenario-scoped fault injection, not wrapper/discovery or native-crash qualification. */
class CCompilerExitConsistencySteps {
    private var fixture: Path? = null
    private lateinit var root: Path
    private lateinit var trigger: Path
    private lateinit var trace: Path
    private lateinit var state: String
    private lateinit var disposition: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var compilerRuntimeProvider: ClangToolchainRuntimeProvider
    private lateinit var wrapperHash: String
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var staged: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private val helperFlags = listOf("-std=c17", "-pedantic-errors", "-DHELPER_ONLY=7")

    @Given("an isolated CMake compiler exit fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("successful-warnings", "baseline-warning-exit", "staged-warning-exit",
            "late-warning-exit", "unpaired-error-summary", "genuine-compiler-error"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-compiler-exit-")
        fixture = dir
        root = Files.createDirectory(dir.resolve("workspace"))
        Files.createDirectory(dir.resolve("control"))
        trigger = dir.resolve("control/fail-live")
        trace = dir.resolve("control/syntax-trace")
        Files.createDirectories(root.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(root.resolve(path), bytes)
        }
        write("src/app/main.c", """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_exit_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201112L, "main_dialect_not_exact");
#pragma message("rk_exit_main_warning")
int main(void) { return 0; }
""")
        write("src/app/helper.c", """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_exit_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
${if (state == "genuine-compiler-error") "#error rk_exit_real_error\n" else ""}#pragma message("rk_exit_helper_warning")
int helper(void) { return 1; }
""")
        write("CMakeLists.txt", "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app src/app/main.c src/app/helper.c)\n")
        val toolchainBin = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
            "The C qualification toolchain root must be configured for this acceptance scenario"
        }).resolve("usr/bin")
        val real = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = root, clangExecutable = toolchainBin.resolve("clang-22"),
            clangdExecutable = toolchainBin.resolve("clangd"), clangFormatExecutable = toolchainBin.resolve("clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain required: ${discovered.diagnostics}")
        }
        assertEquals("22.1.8", real.provenance.clangVersion)
        val runtimeCapture = ClangToolchainRuntimeCapture()
        val retainedRuntime = assertIs<ClangToolchainRuntimeCaptureResult.Captured>(real.runtime)
        val wrapper = dir.resolve("clang-exit-fixture")
        Files.writeString(wrapper, """#!/bin/sh
# Test-only: run the real configured compiler, then inject a process-exit fault.
if [ "${'$'}{1:-}" != '-fsyntax-only' ]; then exec ${quote(real.clangExecutable)} "${'$'}@"; fi
source=''
for arg in "${'$'}@"; do
  [ "${'$'}arg" = '-c' ] && exit 91
  source="${'$'}arg"
done
phase='baseline'
[ -f src/app/moved/main.c ] && phase='staged'
${quote(real.clangExecutable)} "${'$'}@"
reference=${'$'}?
code="${'$'}reference"
if [ "${'$'}reference" = 0 ] && [ "${'$'}{source##*/}" = 'helper.c' ]; then
  case '$state' in
    baseline-warning-exit) code=86 ;;
    staged-warning-exit) [ "${'$'}phase" = 'staged' ] && code=87 ;;
    late-warning-exit) [ -f ${quote(trigger)} ] && code=88 ;;
    unpaired-error-summary) printf '1 error generated.\n' >&2; code=89 ;;
  esac
fi
printf '%s|%s|reference=%s|exit=%s\n' "${'$'}{source##*/}" "${'$'}phase" "${'$'}reference" "${'$'}code" >> ${quote(trace)}
exit "${'$'}code"
""")
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"))
        wrapperHash = hash(Files.readAllBytes(wrapper))
        val witness = ClangToolchainFileEvidence("clang-executable", wrapper, wrapperHash, Files.size(wrapper))
        // Explicit test injection at the compiler port, not production wrapper discovery.
        toolchain = real.copy(clangExecutable = wrapper, provenance = real.provenance.copy(
            evidence = real.provenance.evidence.map { if (it.role == "clang-executable") witness else it } +
                real.provenance.evidence.single { it.role == "clang-executable" }
                    .copy(role = "fault-injection-reference-clang-executable"),
        ))
        compilerRuntimeProvider = object : ClangToolchainRuntimeProvider {
            override fun capture(clang: Path, clangd: Path, formatter: Path) =
                runtimeCapture.capture(clang, clangd, formatter)

            override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) =
                runtimeCapture.checkCurrent(captured)

            override fun checkCurrent(selected: ClangSemanticToolchain): ClangToolchainRuntimeCurrency {
                val configuredHash = selected.provenance.evidence.singleOrNull { it.role == "clang-executable" }?.sha256
                if (selected.clangExecutable != wrapper || configuredHash != wrapperHash ||
                    !Files.isRegularFile(wrapper) || hash(Files.readAllBytes(wrapper)) != wrapperHash) {
                    return ClangToolchainRuntimeCurrency.NotCurrent(Diagnostic(
                        "Test fault-injection compiler wrapper changed", Diagnostic.Severity.ERROR,
                        code = "clang.testFaultInjectionRuntimeChanged",
                        evidence = DiagnosticEvidence.STRUCTURAL,
                    ))
                }
                return runtimeCapture.checkCurrent(retainedRuntime)
            }
        }
        write("compile_commands.json", buildJsonArray {
            listOf("src/app/main.c", "src/app/helper.c").forEach { source ->
                add(buildJsonObject {
                    put("directory", root.toString())
                    put("file", source)
                    put("arguments", buildJsonArray {
                        add(wrapper.toString())
                        add(if (source.endsWith("main.c")) "-std=c11" else "-std=c17")
                        add(if (source.endsWith("main.c")) "-DMAIN_ONLY=3" else "-DHELPER_ONLY=7")
                        add("-c")
                        add(source)
                    })
                })
            }
        }.toString())
    }

    @When("that compiler exit fixture previews its main translation unit move")
    fun preview() {
        snapshot = CProjectScanner().scan(root)
        val structural = CMovePlanner().preview(snapshot, Path.of("src/app/main.c"), Path.of("src/app/moved/main.c"))
        assertEquals(PatchStatus.PREVIEW, structural.status)
        staged = WorkspaceEditSimulator.apply(snapshot, structural.workspaceEdit)
        CRefactoringFacade(toolchain, compilerRuntimeProvider = compilerRuntimeProvider).use { facade ->
            plan = facade.preview(snapshot, "moveSource", mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(root.resolve("src/app/moved/main.c")))
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    @Then("its compiler evidence and managed preview disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        println("compiler-exit fault fixture: state=$state preview=${plan.status}/${plan.refusalCode} summary=${plan.summary} " +
            "before=${plan.diagnosticsBefore} after=${plan.diagnosticsAfterPreview} traceExists=${Files.exists(trace)}")
        println("compiler-exit fault fixture: trace=${Files.readString(trace).trim()}")
        if (disposition in setOf("applicable", "unavailable-apply")) {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertWarnings(plan.diagnosticsBefore, "src/app/main.c")
            assertWarnings(plan.diagnosticsAfterPreview, "src/app/moved/main.c")
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("captured-project-tus-v1", lease.attributes["c.diagnosticsScope"])
            assertEquals("2", lease.attributes["c.translationUnitCount"])
            assertEquals(wrapperHash, lease.attributes["c.compilerSha256"])
            assertTrue(plan.requiresUserApproval && plan.workspaceEdit.edits.isNotEmpty())
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertEquals("c.previewDiagnosticsUnavailable", plan.refusalCode)
            assertTrue(plan.workspaceEdit.edits.isEmpty() && plan.affectedFiles.isEmpty())
            assertNull(plan.authorityLease)
            assertFalse(plan.requiresUserApproval)
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
            val failure = if (disposition == "unavailable-staged") {
                assertWarnings(plan.diagnosticsBefore, "src/app/main.c")
                plan.diagnosticsAfterPreview.single()
            } else {
                assertTrue(plan.diagnosticsAfterPreview.isEmpty())
                plan.diagnosticsBefore.single()
            }
            assertTrue(failure.message.contains("TU 'src/app/helper.c'"), failure.message)
            if (disposition == "reported-error-baseline") {
                assertTrue(failure.message.contains("rk_exit_real_error"), failure.message)
                assertFalse(failure.message.contains("clang.compilerDiagnosticsIncomplete"))
            } else assertUnavailableMessage(failure.message)
        }
        val candidate = if (disposition == "unavailable-staged") staged else snapshot
        val direct = CCompilerDiagnostics(toolchain, runtimeProvider = compilerRuntimeProvider)
            .analyze(candidate, Path.of("src/app/helper.c"), helperFlags)
        if (disposition in setOf("unavailable-baseline", "unavailable-staged")) {
            assertUnavailableMessage(assertIs<CDiagnosticsResult.Unavailable>(direct).diagnostic.message)
        } else {
            val available = assertIs<CDiagnosticsResult.Available>(direct)
            assertEquals(wrapperHash, available.processProvenance?.executableSha256)
            if (disposition == "reported-error-baseline") {
                assertTrue(available.diagnostics.any { it.severity == Diagnostic.Severity.ERROR && it.message.contains("rk_exit_real_error") })
            } else assertEquals(Diagnostic.Severity.WARNING, available.diagnostics.single().severity)
        }
    }

    @Then("its retained compiler exit gate preserves byte-exact rollback or no WAL")
    fun applyOrRefuse() {
        if (disposition == "unavailable-apply") {
            Files.writeString(trigger, "inject late compiler failure\n")
            assertOriginalFiles() // Control changes neither the project nor compiler/database identity.
        }
        CRefactoringFacade(toolchain, compilerRuntimeProvider = compilerRuntimeProvider).use { facade ->
            val outcome = facade.apply(snapshot, plan, ApplyAuthorization.explicit("compiler-exit-consistency-story"),
                DiagnosticsGate.disabled("caller-cannot-bypass-compiler-exit-failure"))
            println("compiler-exit fault fixture: state=$state apply=${outcome::class.simpleName} trace=${Files.readString(trace).trim()}")
            try {
                if (disposition == "applicable") {
                    assertIs<ApplyResult.Applied>(outcome)
                    assertTrue(Files.exists(root.resolve(".refactorkit/transactions")))
                    assertFalse(Files.exists(root.resolve("src/app/main.c")))
                    assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(root.resolve("src/app/moved/main.c")))
                    assertEquals("cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\nadd_executable(app src/app/moved/main.c src/app/helper.c)\n",
                        Files.readString(root.resolve("CMakeLists.txt")))
                } else {
                    val refused = assertIs<ApplyResult.Refused>(outcome)
                    if (disposition == "unavailable-apply") {
                        assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" &&
                            it.message.contains("TU 'src/app/helper.c'") && it.message.contains("clang.compilerDiagnosticsIncomplete") }, refused.diagnostics.toString())
                        assertUnavailableMessage(refused.diagnostics.single { it.code == "diagnostics.unavailable" }.message)
                        val direct = CCompilerDiagnostics(toolchain, runtimeProvider = compilerRuntimeProvider)
                            .analyze(snapshot, Path.of("src/app/helper.c"), helperFlags)
                        assertUnavailableMessage(assertIs<CDiagnosticsResult.Unavailable>(direct).diagnostic.message)
                    } else assertTrue(refused.diagnostics.any { it.code == "plan.notPreview" }, refused.diagnostics.toString())
                    assertFalse(Files.exists(root.resolve(".refactorkit/transactions")), "compiler failure must refuse before WAL")
                }
            } finally {
                // RED may actually apply the late fault under the old parser: retain
                // that wrong outcome, but always undo it rather than leaving a fixture mutated.
                if (outcome is ApplyResult.Applied) assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(outcome.transaction))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(root.resolve("src/app/moved/main.c")))
    }

    private fun assertUnavailableMessage(message: String) {
        val code = when (state) {
            "baseline-warning-exit" -> 86
            "staged-warning-exit" -> 87
            "late-warning-exit" -> 88
            "unpaired-error-summary" -> 89
            else -> error("Not an injected exit state: $state")
        }
        assertTrue(message.contains("Compiler exited with code $code without an error diagnostic"), message)
        assertTrue(Files.readString(trace).contains("reference=0|exit=$code"), "must observe the injected process exit")
    }

    private fun assertWarnings(values: List<Diagnostic>, mainPath: String) {
        assertEquals(2, values.size, values.toString())
        mapOf("rk_exit_main_warning" to mainPath, "rk_exit_helper_warning" to "src/app/helper.c").forEach { (marker, source) ->
            val diagnostic = values.single { it.message.contains(marker) }
            assertEquals(Diagnostic.Severity.WARNING, diagnostic.severity)
            assertEquals(DiagnosticEvidence.COMPILER, diagnostic.evidence)
            assertEquals(root.resolve(source), diagnostic.location?.path)
        }
    }

    private fun assertOriginalFiles() {
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(path)), path) }
        assertEquals(wrapperHash, hash(Files.readAllBytes(toolchain.clangExecutable)))
        if (::snapshot.isInitialized) assertEquals(snapshot.hash, CProjectScanner().scan(root).hash)
    }

    private fun quote(path: Path) = "'" + path.toString().replace("'", "'\\''") + "'"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @After
    fun cleanup() {
        fixture?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

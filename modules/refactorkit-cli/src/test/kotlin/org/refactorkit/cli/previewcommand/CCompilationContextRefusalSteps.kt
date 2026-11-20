package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.COrganizeIncludesPlanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class CCompilationContextRefusalSteps {
    private var workspace: Path? = null
    private lateinit var original: ByteArray
    private var originalDatabase: ByteArray? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var result: ApplyResult
    private var selectedToolchain: ClangSemanticToolchain? = null
    private var diagnosticsGateEvaluated = false

    @Given("an isolated C project with a captured ENABLED compilation configuration")
    fun configuredProject() {
        selectedToolchain = null
        val root = Files.createTempDirectory("refactorkit-c-context-gate-")
        workspace = root
        val code = "#include <stdlib.h>\n#include <stdio.h>\n#ifdef ENABLED\n#error enabled configuration must not pass\n#endif\nint main(void) { return 0; }\n"
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(root.resolve("main.c"), original)
        val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")) {
            "C compilation-context tests require the configured qualification toolchain"
        })
        selectedToolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(
                workspaceRoot = root,
                clangExecutable = reference.resolve("usr/bin/clang-22"),
                clangdExecutable = reference.resolve("usr/bin/clangd"),
                clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
            ),
        )).toolchain
        val compiler = requireNotNull(selectedToolchain).clangExecutable
        Files.writeString(root.resolve("compile_commands.json"), """[
  {"directory":"$root","file":"main.c","arguments":["$compiler","-std=c17","-DENABLED=1","-c","main.c"]}
]
""")
        originalDatabase = Files.readAllBytes(root.resolve("compile_commands.json"))
        snapshot = CProjectScanner().scan(root)
        assertContentEquals(originalDatabase, snapshot.auxiliaryFiles.single {
            it.path == Path.of("compile_commands.json")
        }.content.toByteArray(Charsets.UTF_8))
        assertTrue(requireNotNull(originalDatabase).toString(Charsets.UTF_8).contains("-DENABLED=1"))
    }

    @Given("an isolated source-only C project with sortable includes and no declared compiler context")
    fun sourceOnlyProject() {
        originalDatabase = null
        val root = Files.createTempDirectory("refactorkit-c-source-only-context-")
        workspace = root
        val code = "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(root.resolve("main.c"), original)
        snapshot = CProjectScanner().scan(root)
        assertTrue(snapshot.files.any { it.path == Path.of("main.c") })
        assertFalse(Files.exists(root.resolve("compile_commands.json")))
        assertFalse(Files.exists(root.resolve("CMakeLists.txt")))

        val reference = requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain"))
        selectedToolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(
                root,
                Path.of("$reference/usr/bin/clang-22"),
                Path.of("$reference/usr/bin/clangd"),
                Path.of("$reference/usr/bin/clang-format"),
            ),
        )).toolchain
    }

    @When("a managed C include preview is approved for apply")
    fun approvedApply() {
        val root = requireNotNull(workspace)
        val toolchain = requireNotNull(selectedToolchain)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                val preview = facade.preview(snapshot, "organizeIncludes", mapOf("file" to "main.c"))
                if (originalDatabase == null) {
                    assertEquals(PatchStatus.REFUSED, preview.status)
                } else {
                    assertEquals(PatchStatus.PREVIEW, preview.status, preview.toString())
                    assertTrue(preview.diagnosticsBefore.any {
                        it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR &&
                            it.message.contains("enabled configuration must not pass")
                    }, preview.toString())
                    assertTrue(preview.diagnosticsAfterPreview.any {
                        it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR &&
                            it.message.contains("enabled configuration must not pass")
                    }, preview.toString())
                }
                val plan = callerProposedIncludePreview()
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                if (selectedToolchain != null) assertTrue(plan.workspaceEdit.edits.isNotEmpty())
                result = facade.apply(snapshot, plan, ApplyAuthorization.explicit("c-context-story"), facade.diagnosticsGate())
            }
        }
    }

    @When("the C include preview is applied without caller approval")
    fun applyWithoutCallerApproval() {
        val root = requireNotNull(workspace)
        val toolchain = requireNotNull(selectedToolchain)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                assertEquals(PatchStatus.REFUSED,
                    facade.preview(snapshot, "organizeIncludes", mapOf("file" to "main.c")).status)
                val plan = callerProposedIncludePreview()
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                diagnosticsGateEvaluated = false
                val diagnosticsGate = DiagnosticsGate.enabled("must-not-run-without-approval") {
                    diagnosticsGateEvaluated = true
                    error("diagnostics must not run before explicit caller approval")
                }
                result = facade.apply(snapshot, plan, ApplyAuthorization.missing("c-context-story"), diagnosticsGate)
            }
        }
    }

    @When("a managed C include preview is applied with a caller-supplied clean diagnostics gate")
    fun applyWithCallerSuppliedCleanDiagnosticsGate() {
        val toolchain = requireNotNull(selectedToolchain)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                val refusedPreview = facade.preview(snapshot, "organizeIncludes", mapOf("file" to "main.c"))
                assertEquals(PatchStatus.REFUSED, refusedPreview.status)
                assertEquals("c.includeOrderCompilationContextUnavailable", refusedPreview.refusalCode)
                val plan = callerProposedIncludePreview()
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                diagnosticsGateEvaluated = false
                val callerGate = DiagnosticsGate.enabled("caller-clean-untrusted") {
                    diagnosticsGateEvaluated = true
                    emptyList()
                }
                result = facade.apply(snapshot, plan, ApplyAuthorization.explicit("c-context-story"), callerGate)
            }
        }
    }

    @Then("the apply is refused because exact compilation context is unavailable")
    fun refusal() {
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any {
            it.code == "diagnostics.unavailable" && it.message.contains("c.includeOrderCompilationContextUnavailable") &&
                it.message.contains("root compile_commands.json is not captured")
        }, refused.diagnostics.toString())
    }

    @Then("the caller-proposed apply is refused because the captured compiler authority was not retained")
    fun capturedAuthorityRefusal() {
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any {
            it.code == "diagnostics.unavailable" && it.message.contains("c.includeOrderAuthorityChanged")
        }, refused.diagnostics.toString())
    }

    @Then("the apply refuses for missing approval before diagnostics are evaluated")
    fun missingApprovalRefusal() {
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "approval.required" }, refused.diagnostics.toString())
        assertFalse(diagnosticsGateEvaluated, "the diagnostic gate must not be evaluated without approval")
    }

    @Then("the caller-supplied diagnostics gate is not evaluated")
    fun callerSuppliedGateNotEvaluated() {
        assertFalse(diagnosticsGateEvaluated, "a caller-created clean gate cannot authorize a managed C apply")
    }

    /** Deliberately untrusted caller input; this is not a facade-issued compiler authority. */
    private fun callerProposedIncludePreview(): PatchPlan {
        assertTrue(original.toString(Charsets.UTF_8).startsWith("#include <stdlib.h>\n#include <stdio.h>\n"))
        return COrganizeIncludesPlanner().preview(snapshot, Path.of("main.c")).also { plan ->
            assertEquals(PatchStatus.PREVIEW, plan.status)
            assertTrue(plan.workspaceEdit.edits.isNotEmpty())
            assertEquals(null, plan.authorityLease)
        }
    }

    @Then("source bytes remain unchanged with no transaction journal")
    fun unchanged() {
        val root = requireNotNull(workspace)
        assertContentEquals(original, Files.readAllBytes(root.resolve("main.c")))
        originalDatabase?.let { assertContentEquals(it, Files.readAllBytes(root.resolve("compile_commands.json"))) }
        // PatchEngine may create a workspace.lock before evaluating the gate;
        // a refusal must not create a transaction/WAL record.
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")), "no WAL/transaction on a pre-apply gate refusal")
    }

    @After
    fun cleanup() {
        workspace?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

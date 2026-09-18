package org.refactorkit.c

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CRefactoringFacadeTest {
    @Test
    fun previewsOrganizeIncludes() {
        val snap = snapshot("#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val plan = facade().preview(snap, "organizeIncludes", mapOf("file" to "src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun previewsMoveSource() {
        val snap = snapshot("int a(void) { return 1; }\n")
        val plan = facade().preview(snap, "moveSource", mapOf("file" to "src/main.c", "target" to "src/other.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun previewsExtractExpression() {
        val snap = snapshot("int compute(int x) { int y = x + 1; return y; }\n")
        val plan = facade().preview(snap, "extractExpression", mapOf(
            "file" to "src/main.c", "startLine" to "0", "startChar" to "30", "endLine" to "0", "endChar" to "35", "tempName" to "tmp",
        ))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun applyWithoutApprovalIsRefused() {
        val workspace = Files.createTempDirectory("refactorkit-c-facade-approval")
        val content = "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n"
        Files.createDirectories(workspace.resolve("src"))
        Files.writeString(workspace.resolve("src/main.c"), content)
        val snap = ProjectSnapshot(
            Workspace(workspace),
            emptyList(),
            listOf(SourceFile(Path.of("src/main.c"), content, "c")),
        )
        val plan = facade().preview(snap, "organizeIncludes", mapOf("file" to "src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val result = facade().apply(
            snap,
            plan,
            ApplyAuthorization.missing("test-surface"),
            DiagnosticsGate.enabled("test-clean") { emptyList() },
        )
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "approval.required" })
    }

    @Test
    fun applyWithApprovalAndCleanGateIsApplied() {
        val clang = Path.of("/usr/bin/clang")
        if (!Files.isExecutable(clang)) return // clang not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-facade-apply")
        val content = "int compute(int x) { return x + 1; }\n"
        Files.writeString(workspace.resolve("main.c"), content)
        val snap = ProjectSnapshot(
            Workspace(workspace),
            emptyList(),
            listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        val facade = CRefactoringFacade(toolchain())
        val result = facade.apply(
            snap,
            PatchPlan(
                operation = "organizeIncludes",
                status = PatchStatus.PREVIEW,
                snapshotHash = snap.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "no-op apply probe",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(emptyList()),
            ),
            ApplyAuthorization.explicit("test-surface"),
            facade.diagnosticsGate(),
        )
        assertIs<ApplyResult.Applied>(result)
    }

    @Test
    fun gateSurfacesUnavailableInsteadOfClean() {
        val unavailable = CCompilerDiagnostics(
            toolchain().copy(clangExecutable = Path.of("/nonexistent/clang")),
        )
        val snap = snapshot("int compute(int x) { return x + 1; }\n")
        val gate = DiagnosticsGate.enabled("clang-exact-v1") { candidate ->
            val sources = candidate.files.filter { it.languageId in setOf("c", "cpp", "objective-c") }
            sources.flatMap { source ->
                when (val result = unavailable.analyze(candidate, source.path)) {
                    is CDiagnosticsResult.Available -> result.diagnostics
                    is CDiagnosticsResult.Unavailable ->
                        error("${result.diagnostic.code}: ${result.diagnostic.message}")
                }
            }
        }
        val result = facade().apply(
            snap,
            PatchPlan(
                operation = "organizeIncludes",
                status = PatchStatus.PREVIEW,
                snapshotHash = snap.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "unavailable gate probe",
                affectedFiles = emptySet(),
                workspaceEdit = WorkspaceEdit(emptyList()),
            ),
            ApplyAuthorization.explicit("test-surface"),
            gate,
        )
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.isNotEmpty(), "unavailable diagnostics must not be reported as clean")
    }

    @Test
    fun startForSkipsClangdForNonSemanticOperations() {
        // A toolchain whose clangd path cannot be launched: any clangd start would fail.
        val facade = CRefactoringFacade(toolchain().copy(clangdExecutable = Path.of("/nonexistent/clangd")))
        val snap = snapshot("int compute(int x) { return x + 1; }\n")
        // organizeIncludes never consults clangd, so no semantic process is started.
        facade.startFor(snap, "organizeIncludes")
        facade.close()
    }

    @Test
    fun failedStartDoesNotLeakStartedClangdPlanners() {
        // clangd cannot launch, so the first clangd-backed start fails. The facade must
        // close whatever it already started instead of leaving a process behind.
        val facade = CRefactoringFacade(toolchain().copy(clangdExecutable = Path.of("/nonexistent/clangd")))
        val snap = snapshot("int compute(int x) { return x + 1; }\n")
        val failure = runCatching { facade.start(snap) }
        assertTrue(failure.isFailure, "start must fail when clangd is unavailable")
        // close() stays safe and idempotent after a failed start.
        facade.close()
        facade.close()
    }

    @Test
    fun renameSymbolRefusesMissingSymbolInsteadOfThrowing() {
        val snap = snapshot("int compute(int x) { return x + 1; }\n")
        val plan = facade().preview(snap, "renameSymbol", mapOf("symbol" to "absent", "newName" to "renamed"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("no definition"))
    }

    @Test
    fun renameSymbolRefusesAmbiguousSameNameAcrossFiles() {
        val content = "int compute(int x) { return x + 1; }\n"
        val snap = ProjectSnapshot(
            workspace = Workspace(Path.of("/workspace")),
            modules = emptyList(),
            files = listOf(
                SourceFile(Path.of("src/a.c"), content, "c"),
                SourceFile(Path.of("src/b.c"), content, "c"),
            ),
        )
        val plan = facade().preview(snap, "renameSymbol", mapOf("symbol" to "compute", "newName" to "computeTotal"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("ambiguous"))
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun facade() = CRefactoringFacade(toolchain())

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = Path.of("/usr/bin/clangd"),
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}

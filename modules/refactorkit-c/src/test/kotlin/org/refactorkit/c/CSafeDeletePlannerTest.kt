package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSafeDeletePlannerTest {
    @Test
    fun refusesWhenNotStarted() {
        val plan = planner().preview(snapshot(), "compute")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesInvalidSymbol() {
        val plan = planner().preview(snapshot(), "bad-name")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesExternSymbol() {
        val snap = snapshot("extern int compute(int x);\nint main(void) { return compute(2); }\n")
        val plan = planner().preview(snap, "compute")
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesStaticSymbolWhenReferenceAnalysisIsUnavailable() {
        // A static (non-extern) symbol would be deletable only with a closed reference set.
        // With clangd unavailable, unavailable analysis must never become "zero references".
        val snap = snapshot("static int helper(void) { return 1; }\nint main(void) { return helper(); }\n")
        val plan = planner().preview(snap, "helper")
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("unavailable") || plan.summary.contains("not started"))
    }

    private fun snapshot(content: String = "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n") = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun planner() = CSafeDeletePlanner(toolchain())

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

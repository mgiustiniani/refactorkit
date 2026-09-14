package org.refactorkit.c

import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CRenamePlannerTest {
    @Test
    fun refusesWhenNotStarted() {
        val planner = CRenamePlanner(toolchain())
        val result = planner.rename(Path.of("src/main.c"), 0, 4, "computeTotal", snapshot())
        val refused = assertIs<CRenamePlannerResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "clang.renameNotStarted" })
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), "int compute(int x) { return x + 1; }", "c")),
    )

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

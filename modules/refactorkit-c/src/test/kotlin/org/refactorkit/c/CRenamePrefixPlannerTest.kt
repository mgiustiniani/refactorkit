package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CRenamePrefixPlannerTest {
    @Test
    fun refusesWhenNotStarted() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), mapOf("old" to "new"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesEmptyMapping() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), emptyMap())
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesInvalidMapping() {
        val planner = CRenamePrefixPlanner(toolchain())
        val plan = planner.preview(snapshot(), mapOf("old" to "old"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), "int old_fn(void) { return 0; }\nint main(void) { return old_fn(); }\n", "c")),
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

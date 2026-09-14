package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** Real clangd safe-delete: refuse when references exist, preview when none. */
class CSafeDeletePlannerIntegrationTest {
    private val clangd = Path.of("/usr/bin/clangd")

    @Test
    fun refusesWhenReferencesExist() {
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-safe-delete")
        val mainC = workspace.resolve("main.c")
        val content = "static int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CSafeDeletePlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            val plan = planner.preview(snapshot, "compute")
            assertEquals(PatchStatus.REFUSED, plan.status)
        }
    }

    @Test
    fun deletesUnreferenced() {
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-safe-delete")
        val mainC = workspace.resolve("main.c")
        val content = "static int unused(int x) { return x + 1; }\nint main(void) { return 0; }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CSafeDeletePlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            val plan = planner.preview(snapshot, "unused")
            assertEquals(PatchStatus.PREVIEW, plan.status)
        }
    }

    private fun toolchain() = ClangSemanticToolchain(
        clangExecutable = Path.of("/usr/bin/clang"),
        clangdExecutable = clangd,
        clangFormatExecutable = Path.of("/usr/bin/clang-format"),
        provenance = ClangToolchainProvenance(
            clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/usr/lib/clang/22", evidence = emptyList(),
        ),
    )
}

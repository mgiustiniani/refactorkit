package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real clangd prefix rename on an explicit symbol mapping. */
class CRenamePrefixPlannerIntegrationTest {
    private val clangd = Path.of("/usr/bin/clangd")

    @Test
    fun renamesExplicitSymbolMapping() {
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-prefix")
        val mainC = workspace.resolve("main.c")
        val content = "int old_fn(void) { return 0; }\nint main(void) { return old_fn(); }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CRenamePrefixPlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            val plan = planner.preview(snapshot, mapOf("old_fn" to "new_fn"))
            assertEquals(PatchStatus.PREVIEW, plan.status)
            assertTrue(plan.workspaceEdit.edits.isNotEmpty())
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

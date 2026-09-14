package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real clangd semantic rename on an actual translation unit. */
class CRenamePlannerIntegrationTest {
    private val clangd = Path.of("/usr/bin/clangd")

    @Test
    fun renamesFunctionWithBindingMatchedUses() {
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-rename")
        val mainC = workspace.resolve("main.c")
        val content = "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CRenamePlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            val result = planner.rename(Path.of("main.c"), 0, 4, "computeTotal", snapshot)
            val accepted = assertIs<CRenamePlannerResult.Accepted>(result)
            assertTrue(accepted.normalized.workspaceEdit.edits.isNotEmpty())
            assertTrue(accepted.normalized.workspaceEdit.edits.any { it is FileEdit.Modify && it.textEdits.size == 2 })
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

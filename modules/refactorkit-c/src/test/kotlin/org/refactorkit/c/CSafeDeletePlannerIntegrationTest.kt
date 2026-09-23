package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun deletesUnreferencedRemovesExactDeclaration() {
        // Edit oracle, not status-only: applying the binding-matched plan must remove the
        // exact declaration while preserving the rest of the translation unit.
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
        val plan = CSafeDeletePlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            planner.preview(snapshot, "unused")
        }
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val applied = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val result = applied.files.single { it.path.toString() == "main.c" }.content
        assertTrue(!result.contains("unused"), "declaration must be removed; got: $result")
        assertTrue(result.contains("int main(void) { return 0; }"), "main must be preserved; got: $result")
    }

    @Test
    fun ignoresSameNameLocalVariableWhenDeletingFunction() {
        // Binding-matched references: a different symbol with the same name (a local
        // variable) must not be counted as a consumer of the file-scope function.
        if (!Files.isExecutable(clangd)) return // clangd not installed; integration not run
        val workspace = Files.createTempDirectory("refactorkit-c-safe-delete")
        val mainC = workspace.resolve("main.c")
        val content =
            "static int helper(void) { return 1; }\n" +
                "int other(void) { int helper = 5; return helper; }\n" +
                "int main(void) { return 0; }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CSafeDeletePlanner(toolchain()).use { planner ->
            planner.start(snapshot)
            val plan = planner.preview(snapshot, "helper")
            // The function 'helper' has no consumers of its own binding; the unrelated
            // local variable is a distinct symbol, so deletion is previewed, not refused.
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

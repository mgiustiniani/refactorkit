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

/** Real clangd parameter rename on an actual function. */
class CSignaturePlannerIntegrationTest {
    @Test
    fun renamesParameter() {
        val toolchain = capturedClangTestToolchainOrNull() ?: return // compatible Clang toolchain unavailable
        val workspace = Files.createTempDirectory("refactorkit-c-signature")
        val mainC = workspace.resolve("main.c")
        val content = "int compute(int x) { return x + 1; }\nint main(void) { return compute(2); }\n"
        Files.writeString(mainC, content)
        val snapshot = ProjectSnapshot(
            workspace = Workspace(workspace),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of("main.c"), content, "c")),
        )
        CSignaturePlanner(toolchain).use { planner ->
            planner.start(snapshot)
            val plan = planner.renameParameter(snapshot, Path.of("main.c"), "x", "value")
            assertEquals(PatchStatus.PREVIEW, plan.status)
            assertTrue(plan.workspaceEdit.edits.isNotEmpty())
        }
    }

}

package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CRelocateComponentPlannerTest {
    @Test
    fun refusesBuildManifestTarget() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "int a(void) { return 1; }\n", "c"),
                SourceFile(Path.of("CMakeLists.txt"), "add_library(component src/component/a.c)\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesMacroInclude() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "#include <HEADER>\nint a(void) { return 1; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun relocatesComponent() {
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/component/a.c"), "int a(void) { return 1; }\n", "c"),
                SourceFile(Path.of("src/component/sub/b.c"), "int b(void) { return 2; }\n", "c"),
            ),
        )
        val plan = planner().preview(snap, Path.of("src/component"), Path.of("src/other"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.workspaceEdit.edits.isNotEmpty())
    }

    private fun snapshot(files: List<SourceFile>) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = files,
    )

    private fun planner() = CRelocateComponentPlanner()
}

package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class COrganizeIncludesPlannerTest {
    @Test
    fun refusesMacroInclude() {
        val snap = snapshot("#include <stdio.h>\n#include <HEADER>\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesConditionalInclude() {
        val snap = snapshot("#ifdef FEATURE\n#include <stdio.h>\n#endif\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesConflict() {
        val snap = snapshot("#include \"config.h\"\n#include <config.h>\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun organizesSortedIncludes() {
        val snap = snapshot("#include <stdio.h>\n#include <stdlib.h>\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun planner() = COrganizeIncludesPlanner()
}

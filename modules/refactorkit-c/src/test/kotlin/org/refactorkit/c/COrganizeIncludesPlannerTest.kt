package org.refactorkit.c

import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        val snap = snapshot("#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    @Test
    fun refusesOrderSensitiveQuotedIncludes() {
        // Quoted includes can be order-sensitive; sorting without proof is refused.
        val snap = snapshot("#include \"b.h\"\n#include \"a.h\"\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun keepsCommentsOnIncludeLines() {
        val snap = snapshot("#include <stdlib.h> // needed\n#include <stdio.h> // needed\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val modify = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>().single()
        assertTrue(modify.textEdits.any { it.newText.contains("// needed") })
    }

    @Test
    fun keepsRequiredHeaderUsedThroughItsSymbols() {
        // <stdio.h> is used through printf/FILE, not through its basename, so a
        // basename heuristic must never remove it.
        val snap = snapshot("#include <stdlib.h>\n#include <stdio.h>\nint main(void) { printf(\"x\"); return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val modify = plan.workspaceEdit.edits.filterIsInstance<org.refactorkit.core.FileEdit.Modify>().single()
        val rewritten = modify.textEdits.joinToString("\n") { it.newText }
        assertTrue(rewritten.contains("stdio.h"), "a required header must not be removed by the basename heuristic")
    }

    @Test
    fun refusesRepeatedHeadersWithoutProof() {
        // A repeated include may have macro effects; it must be refused, not deduplicated.
        val snap = snapshot("#include <config.h>\n#include <config.h>\nint main(void) { return 0; }\n")
        val plan = planner().preview(snap, Path.of("src/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main.c"), content, "c")),
    )

    private fun planner() = COrganizeIncludesPlanner()
}

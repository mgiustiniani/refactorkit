package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CMovePlannerTest {
    @Test
    fun movesSourceAndUpdatesLiteralInclude() {
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/main.c"), "#include \"util.h\"\nint main(void) { return 0; }\n", "c"),
            SourceFile(Path.of("src/util.h"), "#define UTIL_H\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/util.h"), Path.of("src/inc/util.h"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertTrue(plan.workspaceEdit.edits.any { it is FileEdit.Rename && it.newPath == Path.of("src/inc/util.h") })
        val modify = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().single()
        assertEquals(Path.of("src/main.c"), modify.path)
        assertTrue(modify.textEdits.any { it.newText == "inc/util.h" })
    }

    @Test
    fun refusesCollisionWithExistingFile() {
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/main.c"), "int x;\n", "c"),
            SourceFile(Path.of("src/inc/main.c"), "int y;\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/main.c"), Path.of("src/inc/main.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.summary.contains("collides"))
    }

    @Test
    fun refusesMacroIncludeTargetingMovedFile() {
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/main.c"), "#include HEADER_H\n", "c"),
            SourceFile(Path.of("src/header.h"), "#define HEADER_H\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/header.h"), Path.of("src/inc/header.h"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    @Test
    fun refusesGeneratedFile() {
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/gen.generated.c"), "int x;\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/gen.generated.c"), Path.of("src/inc/gen.generated.c"))
        assertEquals(PatchStatus.REFUSED, plan.status)
    }

    private fun snapshot(files: List<SourceFile>) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = files,
    )
}

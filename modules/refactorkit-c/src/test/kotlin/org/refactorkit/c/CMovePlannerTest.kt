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
    fun updatesMovedFileOutgoingSiblingInclude() {
        // a.h includes sibling b.h; moving a.h to src/other must re-relativize its own
        // include to ../lib/b.h, otherwise the moved file points at a nonexistent path.
        val snap = snapshot(
            listOf(
                SourceFile(Path.of("src/lib/a.h"), "#include \"b.h\"\n#define A_H\n", "c"),
                SourceFile(Path.of("src/lib/b.h"), "#define B_H\n", "c"),
            ),
        )
        val plan = CMovePlanner().preview(snap, Path.of("src/lib/a.h"), Path.of("src/other/a.h"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val edits = plan.workspaceEdit.edits
        assertTrue(edits.any { it is FileEdit.Rename && it.newPath == Path.of("src/other/a.h") }, "must rename the moved file")
        val modify = edits.filterIsInstance<FileEdit.Modify>().single { it.path == Path.of("src/other/a.h") }
        assertTrue(modify.textEdits.any { it.newText == "../lib/b.h" },
            "outgoing include must be re-relativized; got: " + modify.textEdits.map { it.newText })
        val applied = org.refactorkit.core.WorkspaceEditSimulator.apply(snap, plan.workspaceEdit)
        val moved = applied.files.single { it.path.toString() == "src/other/a.h" }.content
        assertTrue(moved.contains("#include \"../lib/b.h\""), "applied moved file must reference the sibling by the new relative path; got: $moved")
        assertTrue(applied.files.any { it.path.toString() == "src/lib/b.h" }, "the sibling must stay in place")
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

    @Test
    fun moveEvidenceIsStructuralNotLanguageServer() {
        // The move is planned from the snapshot's literal includes; no language-server
        // operation produced this plan, so the evidence must not claim one.
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/main.c"), "#include \"util.h\"\nint main(void) { return 0; }\n", "c"),
            SourceFile(Path.of("src/util.h"), "#define UTIL_H\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/util.h"), Path.of("src/inc/util.h"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(org.refactorkit.core.RefactoringEvidence.STRUCTURAL, plan.evidence)
    }

    @Test
    fun movesWhenMacroIncludeCannotTargetMovedFile() {
        // A macro-computed include that cannot resolve to the moved file must not block
        // an unrelated, proven literal move.
        val snapshot = snapshot(listOf(
            SourceFile(Path.of("src/other.c"), "#include SOME_MACRO\n", "c"),
            SourceFile(Path.of("src/main.c"), "#include \"util.h\"\n", "c"),
            SourceFile(Path.of("src/util.h"), "#define UTIL_H\n", "c"),
        ))
        val plan = CMovePlanner().preview(snapshot, Path.of("src/util.h"), Path.of("src/inc/util.h"))
        assertEquals(PatchStatus.PREVIEW, plan.status)
    }

    private fun snapshot(files: List<SourceFile>) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = files,
    )
}

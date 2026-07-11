package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkspaceEditSimulatorTest {
    @Test
    fun usesOneOriginalCoordinateSpaceForSeparateModifyEntries() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val path = Path.of("Example.java")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(path, "alpha beta gamma\n", "java")),
        )
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset("alpha beta gamma\n", 0, 5), "a"))),
            FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset("alpha beta gamma\n", 11, 5), "g"))),
        ))

        val staged = WorkspaceEditSimulator.apply(snapshot, edit)

        assertEquals("a beta g\n", staged.files.single().content)
        assertEquals("alpha beta gamma\n", snapshot.files.single().content)
    }

    @Test
    fun appliesStructuralStepsToAnEvolvingSnapshot() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val source = Path.of("Old.java")
        val target = Path.of("new/New.java")
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(source, "class Old {}\n", "java")),
        )

        val renamed = WorkspaceEditSimulator.apply(
            snapshot,
            WorkspaceEdit(listOf(FileEdit.Rename(source, target))),
        )
        val modified = WorkspaceEditSimulator.apply(
            renamed,
            WorkspaceEdit(listOf(FileEdit.Modify(
                target,
                listOf(TextEdit(TextEdits.rangeForOffset("class Old {}\n", 6, 3), "New")),
            ))),
        )

        assertEquals(target, modified.files.single().path)
        assertEquals("class New {}\n", modified.files.single().content)
    }

    @Test
    fun rejectsCrossEntryOverlapsBeforeFilesystemApply() {
        val root = Files.createTempDirectory("refactorkit-simulator")
        val path = Path.of("Example.java")
        val content = "abcdef\n"
        val snapshot = ProjectSnapshot(
            Workspace(root),
            emptyList(),
            listOf(SourceFile(path, content, "java")),
        )

        assertFailsWith<IllegalArgumentException> {
            WorkspaceEditSimulator.apply(
                snapshot,
                WorkspaceEdit(listOf(
                    FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset(content, 1, 3), "x"))),
                    FileEdit.Modify(path, listOf(TextEdit(TextEdits.rangeForOffset(content, 2, 3), "y"))),
                )),
            )
        }
    }
}

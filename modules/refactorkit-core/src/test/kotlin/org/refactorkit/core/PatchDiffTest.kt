package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchDiffTest {
    @Test
    fun rendersRealCreateDiffAndStructuredHunk() {
        val snapshot = snapshot()
        val edit = WorkspaceEdit(listOf(FileEdit.Create(
            Path.of("src/main/java/com/example/Foo.java"),
            "package com.example;\n\npublic class Foo {}\n",
        )))

        val diff = PatchDiffRenderer.render(snapshot, edit)

        assertFalse(diff.truncated)
        assertTrue(diff.renderedDiff.startsWith("--- /dev/null\n+++ b/src/main/java/com/example/Foo.java\n"))
        val file = diff.files.single()
        assertEquals(FileChangeKind.CREATE, file.change)
        assertEquals(0, file.hunks.single().oldLines)
        assertEquals(3, file.hunks.single().newLines)
        assertEquals(
            listOf("+package com.example;", "+", "+public class Foo {}"),
            file.hunks.single().lines,
        )
    }

    @Test
    fun representsModifyMoveAndDeleteWithPortableDeterministicPaths() {
        val snapshot = snapshot(
            SourceFile(Path.of("z\\Legacy.java"), "class Legacy {}\n", "java"),
            SourceFile(Path.of("a/Delete.java"), "class Delete {}\n", "java"),
        )
        val edit = WorkspaceEdit(listOf(
            FileEdit.Rename(Path.of("z\\Legacy.java"), Path.of("z/New.java")),
            FileEdit.Modify(
                Path.of("z/New.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 12)), "Current")),
            ),
            FileEdit.Delete(Path.of("a/Delete.java")),
        ))

        val diff = PatchDiffRenderer.render(snapshot, edit)

        assertEquals(listOf(FileChangeKind.DELETE, FileChangeKind.MODIFY, FileChangeKind.MOVE), diff.files.map { it.change })
        assertTrue(diff.renderedDiff.contains("a/z/Legacy.java"))
        assertTrue(diff.renderedDiff.contains("b/z/New.java"))
        assertTrue(diff.files.flatMap { it.hunks }.flatMap { it.lines }.contains("+class Current {}"))
    }

    @Test
    fun declaresLineAndFileTruncationDeterministically() {
        val snapshot = snapshot()
        val edit = WorkspaceEdit((1..3).map { index ->
            FileEdit.Create(Path.of("src/F$index.java"), (1..20).joinToString("\n") { "line-$it" })
        })

        val first = PatchDiffRenderer.render(snapshot, edit, maxBytes = 80, maxFiles = 2, maxLinesPerHunk = 3)
        val second = PatchDiffRenderer.render(snapshot, edit, maxBytes = 80, maxFiles = 2, maxLinesPerHunk = 3)

        assertEquals(first, second)
        assertTrue(first.truncated)
        assertEquals(2, first.files.size)
        assertTrue(first.truncationReasons.any { it.startsWith("fileLimit:") })
        assertTrue(first.truncationReasons.any { it.startsWith("lineLimit:") })
        assertTrue(first.renderedDiff.isNotEmpty())
        val representedBytes = first.renderedDiff.toByteArray().size + first.files
            .flatMap(PatchFileDiff::hunks).flatMap(PatchDiffHunk::lines)
            .sumOf { it.toByteArray().size + 1 }
        assertTrue(representedBytes <= 80, "bounded diff used $representedBytes bytes")
    }

    private fun snapshot(vararg files: SourceFile): ProjectSnapshot {
        val root = Files.createTempDirectory("rk-diff")
        return ProjectSnapshot(Workspace(root), emptyList(), files.toList(), setOf("java"))
    }
}

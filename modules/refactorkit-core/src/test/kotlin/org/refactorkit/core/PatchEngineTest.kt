package org.refactorkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class PatchEngineTest {
    @Test
    fun refusesApplyWhenSnapshotChanged() {
        val root = Files.createTempDirectory("refactorkit-test")
        val file = root.resolve("Example.java")
        Files.writeString(file, "class Example {}\n")

        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot-before-preview",
            confidence = 1.0,
            summary = "stale snapshot test",
            affectedFiles = setOf(Path.of("Example.java")),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Modify(
                    Path.of("Example.java"),
                    listOf(TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 5)), "interface")),
                ),
            )),
        )

        val result = PatchEngine(root).apply(plan, "snapshot-after-change")

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.changed" })
        assertEquals("class Example {}\n", Files.readString(file))
    }

    @Test
    fun refusesApplyForPathOutsideWorkspace() {
        val root = Files.createTempDirectory("refactorkit-test")
        val outsidePath = Path.of("..", "rk-escape-${System.nanoTime()}.txt")
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "outside workspace test",
            affectedFiles = setOf(outsidePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(outsidePath, "secret"))),
        )

        val result = PatchEngine(root).apply(plan, "snapshot")

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "path.outsideWorkspace" })
        assertFalse(Files.exists(root.resolve(outsidePath).normalize()))
    }

    @Test
    fun refusesSymbolicLinkTraversalWithoutWritingOutsideWorkspace() {
        val root = Files.createTempDirectory("refactorkit-test")
        val outside = Files.createTempDirectory("refactorkit-outside")
        Files.createSymbolicLink(root.resolve("linked"), outside)
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "symbolic link escape test",
            affectedFiles = setOf(Path.of("linked", "escaped.txt")),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Create(Path.of("linked", "escaped.txt"), "must not escape"),
            )),
        )

        val result = PatchEngine(root).apply(plan, "snapshot")

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "path.symbolicLink" })
        assertFalse(Files.exists(outside.resolve("escaped.txt")))
    }

    @Test
    fun preflightRefusalLeavesEarlierEditsUnapplied() {
        val root = Files.createTempDirectory("refactorkit-test")
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "preflight all edits",
            affectedFiles = setOf(Path.of("Created.java"), Path.of("Missing.java")),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Create(Path.of("Created.java"), "class Created {}\n"),
                FileEdit.Delete(Path.of("Missing.java")),
            )),
        )

        val result = PatchEngine(root).apply(plan, "snapshot")

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "file.missing" })
        assertFalse(Files.exists(root.resolve("Created.java")))
    }

    @Test
    fun preflightSupportsSequentialModifyThenRename() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("Old.java"), "class Old {}\n")
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("Old.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 9)), "New")),
            ),
            FileEdit.Rename(Path.of("Old.java"), Path.of("New.java")),
        ))
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "sequential virtual state",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val result = PatchEngine(root).apply(plan, "snapshot")

        assertIs<ApplyResult.Applied>(result)
        assertFalse(Files.exists(root.resolve("Old.java")))
        assertEquals("class New {}\n", Files.readString(root.resolve("New.java")))
    }

    @Test
    fun rollbackRestoresModifyCreateRenameAndDeleteEdits() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("Modify.java"), "hello world\n")
        Files.writeString(root.resolve("OldName.java"), "class OldName {}\n")
        Files.writeString(root.resolve("DeleteMe.java"), "class DeleteMe {}\n")

        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("Modify.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 11)), "RefactorKit")),
            ),
            FileEdit.Create(Path.of("Created.java"), "class Created {}\n"),
            FileEdit.Rename(Path.of("OldName.java"), Path.of("NewName.java")),
            FileEdit.Delete(Path.of("DeleteMe.java")),
        ))
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "rollback coverage",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val apply = PatchEngine(root).apply(plan, "snapshot")
        assertIs<ApplyResult.Applied>(apply)
        assertEquals("hello RefactorKit\n", Files.readString(root.resolve("Modify.java")))
        assertTrue(Files.exists(root.resolve("Created.java")))
        assertTrue(Files.exists(root.resolve("NewName.java")))
        assertFalse(Files.exists(root.resolve("OldName.java")))
        assertFalse(Files.exists(root.resolve("DeleteMe.java")))

        val rollback = PatchEngine(root).rollback(apply.transaction)

        assertIs<ApplyResult.Applied>(rollback)
        assertEquals("hello world\n", Files.readString(root.resolve("Modify.java")))
        assertFalse(Files.exists(root.resolve("Created.java")))
        assertTrue(Files.exists(root.resolve("OldName.java")))
        assertFalse(Files.exists(root.resolve("NewName.java")))
        assertEquals("class DeleteMe {}\n", Files.readString(root.resolve("DeleteMe.java")))
    }

    @Test
    fun rejectsOverlappingTextEdits() {
        val root = Files.createTempDirectory("refactorkit-test")
        val file = root.resolve("Example.java")
        Files.writeString(file, "class Example {}\n")

        val plan = PatchPlan(
            operation = "test",
            snapshotHash = "snapshot",
            confidence = 1.0,
            summary = "overlap test",
            affectedFiles = setOf(Path.of("Example.java")),
            workspaceEdit = WorkspaceEdit(
                listOf(
                    FileEdit.Modify(
                        Path.of("Example.java"),
                        listOf(
                            TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 5)), "interface"),
                            TextEdit(SourceRange(SourcePosition(0, 3), SourcePosition(0, 7)), "object"),
                        ),
                    ),
                ),
            ),
        )

        val diagnostics = PatchEngine(root).validate(plan, "snapshot")
        assertTrue(diagnostics.any { it.code == "edit.overlap" })
    }
}

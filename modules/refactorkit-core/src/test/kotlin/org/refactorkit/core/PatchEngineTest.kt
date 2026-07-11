package org.refactorkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class PatchEngineTest {
    @Test
    fun refusesApplyWhenSnapshotChanged() {
        val root = Files.createTempDirectory("refactorkit-test")
        val file = root.resolve("Example.java")
        Files.writeString(file, "class Example {}\n")

        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
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

        val changedSnapshot = snapshot.copy(
            files = snapshot.files + SourceFile(Path.of("Other.java"), "class Other {}\n", "java"),
        )
        val result = PatchEngine(root).apply(plan, changedSnapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.changed" })
        assertEquals("class Example {}\n", Files.readString(file))
    }

    @Test
    fun refusesApplyForPathOutsideWorkspace() {
        val root = Files.createTempDirectory("refactorkit-test")
        val outsidePath = Path.of("..", "rk-escape-${System.nanoTime()}.txt")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "outside workspace test",
            affectedFiles = setOf(outsidePath),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(outsidePath, "secret"))),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "path.outsideWorkspace" })
        assertFalse(Files.exists(root.resolve(outsidePath).normalize()))
    }

    @Test
    fun refusesSymbolicLinkTraversalWithoutWritingOutsideWorkspace() {
        val root = Files.createTempDirectory("refactorkit-test")
        val outside = Files.createTempDirectory("refactorkit-outside")
        Files.createSymbolicLink(root.resolve("linked"), outside)
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "symbolic link escape test",
            affectedFiles = setOf(Path.of("linked", "escaped.txt")),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Create(Path.of("linked", "escaped.txt"), "must not escape"),
            )),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "path.symbolicLink" })
        assertFalse(Files.exists(outside.resolve("escaped.txt")))
    }

    @Test
    fun preflightRefusalLeavesEarlierEditsUnapplied() {
        val root = Files.createTempDirectory("refactorkit-test")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "preflight all edits",
            affectedFiles = setOf(Path.of("Created.java"), Path.of("Missing.java")),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Create(Path.of("Created.java"), "class Created {}\n"),
                FileEdit.Delete(Path.of("Missing.java")),
            )),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "file.missing" })
        assertFalse(Files.exists(root.resolve("Created.java")))
    }

    @Test
    fun preflightSupportsSequentialModifyThenRename() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("Old.java"), "class Old {}\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("Old.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 9)), "New")),
            ),
            FileEdit.Rename(Path.of("Old.java"), Path.of("New.java")),
        ))
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "sequential virtual state",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val result = PatchEngine(root).apply(plan, snapshot)

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
        val snapshot = projectSnapshot(root)

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
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "rollback coverage",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val apply = PatchEngine(root).apply(plan, snapshot)
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
    fun refusesWhenAffectedFileChangesBetweenScanAndLock() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        val file = root.resolve(relative)
        Files.writeString(file, "class Example {}\n")
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = listOf(SourceFile(relative, Files.readString(file), "java")),
        )
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "affected file precondition",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                relative,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        Files.writeString(file, "class ExternalChange {}\n")

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "file.preconditionChanged" })
        assertEquals("class ExternalChange {}\n", Files.readString(file))
    }

    @Test
    fun refusesConcurrentWriterWhileWorkspaceLockIsHeld() {
        val root = Files.createTempDirectory("refactorkit-test")
        val metadata = Files.createDirectories(root.resolve(".refactorkit"))
        val lockPath = metadata.resolve("workspace.lock")
        val snapshot = ProjectSnapshot(Workspace(root), emptyList(), emptyList())
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "workspace lock",
            affectedFiles = setOf(Path.of("Created.java")),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(Path.of("Created.java"), "class Created {}\n"))),
        )

        val result = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { PatchEngine(root).apply(plan, snapshot) }
        }

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "workspace.locked" })
        assertFalse(Files.exists(root.resolve("Created.java")))
    }

    @Test
    fun refusesSymbolicLinkWorkspaceMetadataWithoutWritingOutside() {
        val root = Files.createTempDirectory("refactorkit-test")
        val outside = Files.createTempDirectory("refactorkit-lock-outside")
        Files.createSymbolicLink(root.resolve(".refactorkit"), outside)
        val snapshot = ProjectSnapshot(Workspace(root), emptyList(), emptyList())
        val plan = PatchPlan(
            operation = "test",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "unsafe workspace lock",
            affectedFiles = setOf(Path.of("Created.java")),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(Path.of("Created.java"), "class Created {}\n"))),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "workspace.lockUnsafe" })
        assertFalse(Files.exists(outside.resolve("workspace.lock")))
        assertFalse(Files.exists(root.resolve("Created.java")))
    }

    private fun projectSnapshot(root: Path): ProjectSnapshot {
        val files = Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".refactorkit")) }
                .map { SourceFile(root.relativize(it), Files.readString(it), "test") }
                .toList()
        }
        return ProjectSnapshot(Workspace(root), emptyList(), files)
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

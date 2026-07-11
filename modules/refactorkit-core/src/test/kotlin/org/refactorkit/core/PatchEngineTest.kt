package org.refactorkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

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
        val record = TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(apply.transaction.id)
        assertEquals(JournalState.ROLLED_BACK, record?.state)
    }

    @Test
    fun normalRollbackRefusesPostApplyChangesAndForceRestoresPreImages() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("Modify.java"), "before modify\n")
        Files.writeString(root.resolve("Old.java"), "before rename\n")
        Files.writeString(root.resolve("Delete.java"), "before delete\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("Modify.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 6)), "after")),
            ),
            FileEdit.Create(Path.of("Created.java"), "created\n"),
            FileEdit.Rename(Path.of("Old.java"), Path.of("New.java")),
            FileEdit.Delete(Path.of("Delete.java")),
        ))
        val plan = PatchPlan(
            operation = "rollbackConflict",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "rollback conflict coverage",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))
        Files.writeString(root.resolve("Modify.java"), "external modify\n")
        Files.writeString(root.resolve("Created.java"), "external created\n")
        Files.writeString(root.resolve("Old.java"), "external recreated source\n")
        Files.writeString(root.resolve("New.java"), "external rename target\n")
        Files.writeString(root.resolve("Delete.java"), "external recreated delete\n")

        val refused = PatchEngine(root).rollback(applied.transaction)

        assertIs<ApplyResult.Refused>(refused)
        assertTrue(refused.diagnostics.all { it.code == "rollback.conflict" })
        assertEquals("external modify\n", Files.readString(root.resolve("Modify.java")))
        assertEquals("external created\n", Files.readString(root.resolve("Created.java")))
        assertEquals("external recreated source\n", Files.readString(root.resolve("Old.java")))
        assertEquals("external rename target\n", Files.readString(root.resolve("New.java")))
        assertEquals("external recreated delete\n", Files.readString(root.resolve("Delete.java")))
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        assertEquals(JournalState.APPLIED, log.loadRecord(applied.transaction.id)?.state)

        val forced = PatchEngine(root).rollback(applied.transaction, RollbackMode.FORCE)

        assertIs<ApplyResult.Applied>(forced)
        assertEquals("before modify\n", Files.readString(root.resolve("Modify.java")))
        assertFalse(Files.exists(root.resolve("Created.java")))
        assertEquals("before rename\n", Files.readString(root.resolve("Old.java")))
        assertFalse(Files.exists(root.resolve("New.java")))
        assertEquals("before delete\n", Files.readString(root.resolve("Delete.java")))
        val record = log.loadRecord(applied.transaction.id)
        assertEquals(JournalState.ROLLED_BACK, record?.state)
        assertEquals("Forced rollback explicitly requested", record?.failure)
    }

    @Test
    fun reportsWorkspaceFilesystemReplacementCapabilities() {
        val root = Files.createTempDirectory("refactorkit-test")

        val capabilities = PatchEngine(root).filesystemCapabilities()

        assertTrue(capabilities.fileStoreType.isNotBlank())
        assertEquals(
            capabilities.atomicMoveSupported &&
                capabilities.durableFileForceSupported &&
                capabilities.durableDirectoryForceSupported,
            capabilities.supportsDurableAtomicReplacement,
        )
        assertEquals("same-directory-temp-file+atomic-move+directory-force", capabilities.replacementStrategy)
        assertTrue(Files.walk(root).use { stream ->
            stream.noneMatch { it.fileName.toString().startsWith(".capability-") }
        })
    }

    @Test
    fun stagesAtomicReplacementsAndPreservesPosixPermissions() {
        val root = Files.createTempDirectory("refactorkit-test")
        val modify = root.resolve("Modify.java")
        val oldName = root.resolve("Old.java")
        Files.writeString(modify, "class Modify {}\n")
        Files.writeString(oldName, "class Old {}\n")
        val posix = Files.getFileAttributeView(modify, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null
        val modifyPermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val renamePermissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ)
        if (posix) {
            Files.setPosixFilePermissions(modify, modifyPermissions)
            Files.setPosixFilePermissions(oldName, renamePermissions)
        }
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("Modify.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 12)), "Changed")),
            ),
            FileEdit.Rename(Path.of("Old.java"), Path.of("New.java")),
            FileEdit.Create(Path.of("nested/Created.java"), "class Created {}\n"),
        ))
        val plan = PatchPlan(
            operation = "durableReplacement",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "stage durable replacements",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))

        assertEquals("class Changed {}\n", Files.readString(modify))
        assertFalse(Files.exists(oldName))
        assertEquals("class Old {}\n", Files.readString(root.resolve("New.java")))
        assertTrue(Files.exists(root.resolve("nested/Created.java")))
        assertNoWorkspaceStageFiles(root)
        if (posix) {
            assertEquals(modifyPermissions, Files.getPosixFilePermissions(modify))
            assertEquals(renamePermissions, Files.getPosixFilePermissions(root.resolve("New.java")))
        }

        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(applied.transaction))
        assertNoWorkspaceStageFiles(root)
        assertEquals("class Modify {}\n", Files.readString(modify))
        assertEquals("class Old {}\n", Files.readString(oldName))
        assertFalse(Files.exists(root.resolve("nested")))
        if (posix) {
            assertEquals(modifyPermissions, Files.getPosixFilePermissions(modify))
            assertEquals(renamePermissions, Files.getPosixFilePermissions(oldName))
        }
    }

    @Test
    fun rollbackRefusesExternalFilesInsideTransactionCreatedDirectory() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        Files.writeString(root.resolve(source), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "createdDirectoryConflict",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "create nested source",
            affectedFiles = setOf(Path.of("generated/New.java")),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(
                Path.of("generated/New.java"),
                "class New {}\n",
            ))),
        )
        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))
        Files.writeString(root.resolve("generated/external.txt"), "external\n")

        val rollback = PatchEngine(root).rollback(applied.transaction)

        assertIs<ApplyResult.Refused>(rollback)
        assertTrue(rollback.diagnostics.any { it.code == "rollback.conflict" })
        assertTrue(Files.exists(root.resolve("generated/New.java")))
        assertTrue(Files.exists(root.resolve("generated/external.txt")))
    }

    private fun assertNoWorkspaceStageFiles(root: Path) {
        assertTrue(Files.walk(root).use { stream ->
            stream.noneMatch { it.fileName.toString().startsWith(".refactorkit-stage-") }
        })
    }

    @Test
    fun stagingDiskFailureLeavesWorkspaceUnchangedAndCleansTemporaryFiles() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(FileEdit.Modify(
            relative,
            listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
        )))
        val plan = PatchPlan(
            operation = "faultInjectedStaging",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "inject disk full after staged force",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )
        var injected = false
        val injector = PatchFaultInjector { point, _, _ ->
            if (!injected && point == PatchFaultPoint.AFTER_STAGED_FILE_FORCE) {
                injected = true
                throw java.io.IOException("injected disk full")
            }
        }

        val result = PatchEngine(root, faultInjector = injector).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "transaction.applyFailedCompensated" })
        assertEquals("class Example {}\n", Files.readString(root.resolve(relative)))
        assertNoWorkspaceStageFiles(root)
        assertEquals(
            JournalState.ROLLED_BACK,
            TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single().state,
        )
    }

    @Test
    fun compensatesAWriteFailureInjectedAfterPartialWorkspaceCommit() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("First.java"), "class First {}\n")
        Files.writeString(root.resolve("Second.java"), "class Second {}\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("First.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 11)), "ChangedFirst")),
            ),
            FileEdit.Modify(
                Path.of("Second.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 12)), "ChangedSecond")),
            ),
        ))
        val plan = PatchPlan(
            operation = "faultInjectedPartialCommit",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "inject failure after first committed image",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )
        var injected = false
        val injector = PatchFaultInjector { point, _, sequence ->
            if (!injected && point == PatchFaultPoint.AFTER_COMMITTED_IMAGE && sequence == 1) {
                injected = true
                throw java.io.IOException("injected disk failure")
            }
        }

        val result = PatchEngine(root, faultInjector = injector).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "transaction.applyFailedCompensated" })
        assertEquals("class First {}\n", Files.readString(root.resolve("First.java")))
        assertEquals("class Second {}\n", Files.readString(root.resolve("Second.java")))
        val record = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.ROLLED_BACK),
            record.history.map(JournalEvent::state),
        )
        assertTrue(record.failure?.contains("injected disk failure") == true)
    }

    @Test
    fun restartRecoveryCompletesAfterInjectedCompensationFailure() {
        val root = Files.createTempDirectory("refactorkit-test")
        Files.writeString(root.resolve("First.java"), "class First {}\n")
        Files.writeString(root.resolve("Second.java"), "class Second {}\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(
                Path.of("First.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 11)), "ChangedFirst")),
            ),
            FileEdit.Modify(
                Path.of("Second.java"),
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 12)), "ChangedSecond")),
            ),
        ))
        val plan = PatchPlan(
            operation = "faultInjectedRecovery",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "inject apply and compensation failures",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )
        var failures = 0
        val injector = PatchFaultInjector { point, _, sequence ->
            if (point == PatchFaultPoint.AFTER_COMMITTED_IMAGE && sequence == 1 && failures < 2) {
                failures++
                throw java.io.IOException("injected failure $failures")
            }
        }

        val failed = PatchEngine(root, faultInjector = injector).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(failed)
        assertTrue(failed.diagnostics.any { it.code == "transaction.recoveryRequired" })
        assertEquals(
            JournalState.RECOVERY_REQUIRED,
            TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single().state,
        )

        assertTrue(PatchEngine(root).recover().isEmpty())
        assertEquals("class First {}\n", Files.readString(root.resolve("First.java")))
        assertEquals("class Second {}\n", Files.readString(root.resolve("Second.java")))
        val recovered = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(JournalState.ROLLED_BACK, recovered.state)
        assertTrue(recovered.history.map(JournalEvent::state).contains(JournalState.RECOVERY_REQUIRED))
    }

    @Test
    fun persistsAppliedWriteAheadJournalLifecycle() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "testJournal",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "journal lifecycle",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                relative,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )

        val applied = assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))
        val record = TransactionLog(root.resolve(".refactorkit/transactions")).loadRecord(applied.transaction.id)

        assertEquals(JournalState.APPLIED, record?.state)
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, record?.schemaVersion)
        assertEquals(RefactorKitVersion.VERSION, record?.implementationVersion)
        assertEquals(RefactorKitVersion.API_VERSION, record?.apiVersion)
        assertEquals(snapshot.hash, record?.preSnapshotHash)
        assertEquals(projectSnapshot(root).hash, record?.postSnapshotHash)
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED),
            record?.history?.map(JournalEvent::state),
        )
        assertTrue(record?.history?.zipWithNext()?.all { (left, right) -> !right.at.isBefore(left.at) } == true)
        assertEquals("class Example {}\n", record?.preImages?.single()?.content)
        assertEquals("class Changed {}\n", record?.postImages?.single()?.content)
        assertEquals("testJournal", record?.operation)
    }

    @Test
    fun startupRecoveryRestoresInterruptedApplyingTransaction() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        val preContent = "class Example {}\n"
        val postContent = "class Changed {}\n"
        Files.writeString(root.resolve(source), postContent)
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val transaction = Transaction(
            planId = PlanId("plan-interrupted"),
            snapshotHashBefore = "before",
            rollbackEdit = WorkspaceEdit(listOf(FileEdit.Create(source, preContent, overwrite = true))),
        )
        val interrupted = TransactionJournalRecord(
            transaction = transaction,
            operation = "interrupted",
            forwardEdit = WorkspaceEdit(),
            preImages = listOf(FileImage(source, preContent)),
            postImages = listOf(FileImage(source, postContent)),
            state = JournalState.PREPARED,
        )
        log.prepare(interrupted)
        log.update(interrupted.copy(state = JournalState.APPLYING))

        val recoveryErrors = PatchEngine(root).recover()

        assertTrue(recoveryErrors.isEmpty(), recoveryErrors.toString())
        assertEquals(preContent, Files.readString(root.resolve(source)))
        assertEquals(JournalState.ROLLED_BACK, log.loadRecord(transaction.id)?.state)
    }

    @Test
    fun startupRecoveryBlocksOnConflictingWorkspaceState() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        Files.writeString(root.resolve(source), "class External {}\n")
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        val transaction = Transaction(
            planId = PlanId("plan-conflict"),
            snapshotHashBefore = "before",
            rollbackEdit = WorkspaceEdit(),
        )
        val interrupted = TransactionJournalRecord(
            transaction = transaction,
            operation = "interrupted",
            forwardEdit = WorkspaceEdit(),
            preImages = listOf(FileImage(source, "class Example {}\n")),
            postImages = listOf(FileImage(source, "class Changed {}\n")),
            state = JournalState.PREPARED,
        )
        log.prepare(interrupted)
        log.update(interrupted.copy(state = JournalState.APPLYING))
        val recoveryErrors = PatchEngine(root).recover()

        assertTrue(recoveryErrors.any { it.code == "transaction.recoveryRequired" })
        assertEquals("class External {}\n", Files.readString(root.resolve(source)))
        assertEquals(JournalState.RECOVERY_REQUIRED, log.loadRecord(transaction.id)?.state)
    }

    @Test
    fun diagnosticsGateRefusesNewErrorsBeforeJournaling() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        Files.writeString(root.resolve(source), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "diagnosticsGate",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "introduce diagnostics regression",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                source,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Broken")),
            ))),
        )
        val gate = DiagnosticsGate.enabled("test") { staged ->
            if (staged.files.any { "Broken" in it.content }) listOf(Diagnostic(
                "new compiler error",
                Diagnostic.Severity.ERROR,
                code = "compiler.test",
            )) else emptyList()
        }

        val result = PatchEngine(root).apply(
            plan,
            snapshot,
            ApplyAuthorization.explicit("test"),
            gate,
        )

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "diagnostics.regression" })
        assertEquals("class Example {}\n", Files.readString(root.resolve(source)))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())
    }

    @Test
    fun diagnosticsGateAllowsPreExistingErrorsWithoutRegression() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        Files.writeString(root.resolve(source), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "diagnosticsGateExisting",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "preserve existing diagnostic",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                source,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        val existing = Diagnostic("existing compiler error", Diagnostic.Severity.ERROR, code = "compiler.existing")

        val result = PatchEngine(root).apply(
            plan,
            snapshot,
            ApplyAuthorization.explicit("test"),
            DiagnosticsGate.enabled("test") { listOf(existing) },
        )

        assertIs<ApplyResult.Applied>(result)
        assertEquals("class Changed {}\n", Files.readString(root.resolve(source)))
    }

    @Test
    fun requiresAndAuditsExplicitApproval() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        Files.writeString(root.resolve(source), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "approval",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "require explicit approval",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
        )
        val engine = PatchEngine(root)

        val refused = engine.apply(plan, snapshot, ApplyAuthorization.missing("test", "reviewer"))

        assertIs<ApplyResult.Refused>(refused)
        assertTrue(refused.diagnostics.any { it.code == "approval.required" })
        assertTrue(Files.exists(root.resolve(source)))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())

        val applied = assertIs<ApplyResult.Applied>(
            engine.apply(plan, snapshot, ApplyAuthorization.explicit("test", "reviewer")),
        )
        assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
        assertEquals("test", applied.transaction.approval.surface)
        assertEquals("reviewer", applied.transaction.approval.actor)
        val record = TransactionLog(root.resolve(".refactorkit/transactions"))
            .loadRecord(applied.transaction.id)
        assertEquals(applied.transaction.approval, record?.transaction?.approval)
    }

    @Test
    fun refusesSnapshotWithoutDeclaredSourceExtensions() {
        val root = Files.createTempDirectory("refactorkit-test")
        val snapshot = ProjectSnapshot(Workspace(root), emptyList(), emptyList())
        val plan = PatchPlan(
            operation = "missingScope",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "refuse undeclared source scope",
            affectedFiles = setOf(Path.of("Created.java")),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(Path.of("Created.java"), "class Created {}\n"))),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.scopeInvalid" }, result.diagnostics.toString())
        assertFalse(Files.exists(root.resolve("Created.java")))
    }

    @Test
    fun refusesChangedClasspathEntryBeforeJournaling() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        val dependency = Path.of("lib/dependency.jar")
        Files.createDirectories(root.resolve(dependency).parent)
        Files.writeString(root.resolve(source), "class Example {}\n")
        Files.writeString(root.resolve(dependency), "jar-before")
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("root", root, classpathEntries = listOf(dependency))),
            files = listOf(SourceFile(source, Files.readString(root.resolve(source)), "java")),
            sourceExtensions = setOf("java"),
            classpathEvidence = listOf(ClasspathEvidence.capture(root, dependency, ClasspathEvidenceKind.ENTRY)),
        )
        val plan = PatchPlan(
            operation = "classpathEvidence",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "detect changed dependency",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                source,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        Files.writeString(root.resolve(dependency), "jar-after")

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.classpathChanged" }, result.diagnostics.toString())
        assertEquals("class Example {}\n", Files.readString(root.resolve(source)))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())
    }

    @Test
    fun refusesNewConventionalClasspathOutputWatchedAsMissing() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        val output = Path.of("target/classes")
        Files.writeString(root.resolve(source), "class Example {}\n")
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("root", root)),
            files = listOf(SourceFile(source, Files.readString(root.resolve(source)), "java")),
            sourceExtensions = setOf("java"),
            classpathEvidence = listOf(ClasspathEvidence.capture(root, output, ClasspathEvidenceKind.ENTRY)),
        )
        val plan = PatchPlan(
            operation = "classpathDiscoveryEvidence",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "detect new compiled output",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                source,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        Files.createDirectories(root.resolve(output))
        Files.write(root.resolve(output).resolve("Dependency.class"), byteArrayOf(1, 2, 3))

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.classpathChanged" }, result.diagnostics.toString())
        assertEquals("class Example {}\n", Files.readString(root.resolve(source)))
    }

    @Test
    fun refusesClasspathEntryWithoutContentEvidence() {
        val root = Files.createTempDirectory("refactorkit-test")
        val source = Path.of("Example.java")
        val dependency = Path.of("lib/dependency.jar")
        Files.createDirectories(root.resolve(dependency).parent)
        Files.writeString(root.resolve(source), "class Example {}\n")
        Files.writeString(root.resolve(dependency), "jar")
        val snapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("root", root, classpathEntries = listOf(dependency))),
            files = listOf(SourceFile(source, Files.readString(root.resolve(source)), "java")),
            sourceExtensions = setOf("java"),
        )
        val plan = PatchPlan(
            operation = "missingClasspathEvidence",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "refuse unverified dependency",
            affectedFiles = setOf(source),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Delete(source))),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.scopeInvalid" }, result.diagnostics.toString())
        assertTrue(Files.exists(root.resolve(source)))
    }

    @Test
    fun refusesNewSourceOmittedFromCallerSnapshot() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "engineOwnedScope",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "detect omitted source",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                relative,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        Files.writeString(root.resolve("Added.java"), "class Added {}\n")

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "snapshot.scopeChanged" }, result.diagnostics.toString())
        assertEquals("class Example {}\n", Files.readString(root.resolve(relative)))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())
    }

    @Test
    fun ignoresNewSourcesInsideDeclaredIgnoredDirectories() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "ignoredScope",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "ignore generated build source",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(
                relative,
                listOf(TextEdit(SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)), "Changed")),
            ))),
        )
        Files.createDirectories(root.resolve("build/generated"))
        Files.writeString(root.resolve("build/generated/Generated.java"), "class Generated {}\n")

        assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))
        assertEquals("class Changed {}\n", Files.readString(root.resolve(relative)))
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
    fun mergesMultipleModifyEntriesInOneOriginalCoordinateSpace() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "alpha beta gamma\n")
        val snapshot = projectSnapshot(root)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(relative, listOf(
                TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 5)), "A-LONG"),
            )),
            FileEdit.Modify(relative, listOf(
                TextEdit(SourceRange(SourcePosition(0, 11), SourcePosition(0, 16)), "G"),
            )),
        ))
        val plan = PatchPlan(
            operation = "mergeModifyCoordinates",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "merge same-file modifies",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )

        assertIs<ApplyResult.Applied>(PatchEngine(root).apply(plan, snapshot))
        assertEquals("A-LONG beta G\n", Files.readString(root.resolve(relative)))
        val record = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(1, record.forwardEdit.edits.filterIsInstance<FileEdit.Modify>().size)
        assertEquals(2, (record.forwardEdit.edits.single() as FileEdit.Modify).textEdits.size)
    }

    @Test
    fun rejectsOverlapAcrossSeparateModifyEntries() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "class Example {}\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "crossEntryOverlap",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "reject cross-entry overlap",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(
                FileEdit.Modify(relative, listOf(
                    TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 5)), "interface"),
                )),
                FileEdit.Modify(relative, listOf(
                    TextEdit(SourceRange(SourcePosition(0, 3), SourcePosition(0, 7)), "object"),
                )),
            )),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "edit.overlap" })
        assertEquals("class Example {}\n", Files.readString(root.resolve(relative)))
    }

    @Test
    fun refusesCharacterOutsideLineBeforeJournalOrWorkspaceMutation() {
        val root = Files.createTempDirectory("refactorkit-test")
        val relative = Path.of("Example.java")
        Files.writeString(root.resolve(relative), "abc\ndef\n")
        val snapshot = projectSnapshot(root)
        val plan = PatchPlan(
            operation = "invalidCharacterBounds",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "reject invalid character",
            affectedFiles = setOf(relative),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(relative, listOf(
                TextEdit(SourceRange(SourcePosition(0, 4), SourcePosition(0, 4)), "x"),
            )))),
        )

        val result = PatchEngine(root).apply(plan, snapshot)

        assertIs<ApplyResult.Refused>(result)
        assertTrue(result.diagnostics.any { it.code == "edit.rangeOutOfBounds" }, result.diagnostics.toString())
        assertEquals("abc\ndef\n", Files.readString(root.resolve(relative)))
        assertTrue(TransactionLog(root.resolve(".refactorkit/transactions")).list().isEmpty())
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

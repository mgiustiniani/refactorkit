package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

class ProcessKillRecoveryTest {
    @Test
    @Timeout(30)
    fun processKillAfterFirstCommitIsRecoveredOnRestart() {
        val root = Files.createTempDirectory("refactorkit-process-kill")
        Files.writeString(root.resolve("First.java"), "class First {}\n")
        Files.writeString(root.resolve("Second.java"), "class Second {}\n")
        val marker = root.resolve("kill-ready.marker")
        val output = root.resolve("child-output.log")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            ProcessKillApplyMain::class.java.name,
            root.toString(),
            marker.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()

        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!Files.exists(marker) && child.isAlive && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(Files.exists(marker), "Child did not reach partial commit boundary: ${readOutput(output)}")
        child.destroyForcibly()
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Killed child did not terminate")

        val beforeRecovery = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(JournalState.APPLYING, beforeRecovery.state)
        assertEquals("class ChangedFirst {}\n", Files.readString(root.resolve("First.java")))
        assertEquals("class Second {}\n", Files.readString(root.resolve("Second.java")))

        assertTrue(PatchEngine(root).recover().isEmpty())
        assertEquals("class First {}\n", Files.readString(root.resolve("First.java")))
        assertEquals("class Second {}\n", Files.readString(root.resolve("Second.java")))
        val recovered = TransactionLog(root.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(JournalState.ROLLED_BACK, recovered.state)
        assertTrue(recovered.failure?.contains("interrupted applying") == true)
    }

    @Test
    @Timeout(30)
    fun processKillDuringJournalTempWritePreservesPreviousRecord() {
        val root = Files.createTempDirectory("refactorkit-journal-kill")
        val logDir = root.resolve(".refactorkit/transactions")
        val transaction = Transaction(
            id = TransactionId.new(),
            planId = PlanId("plan-journal-kill"),
            snapshotHashBefore = "hash",
            rollbackEdit = WorkspaceEdit(),
        )
        TransactionLog(logDir).prepare(TransactionJournalRecord(
            transaction = transaction,
            operation = "journalProcessKill",
            forwardEdit = WorkspaceEdit(),
            preImages = emptyList(),
            postImages = emptyList(),
            state = JournalState.PREPARED,
        ))
        val marker = root.resolve("journal-kill-ready.marker")
        val output = root.resolve("journal-child-output.log")
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            ProcessKillJournalUpdateMain::class.java.name,
            logDir.toString(),
            transaction.id.value,
            marker.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start()

        val deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos()
        while (!Files.exists(marker) && child.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(Files.exists(marker), "Child did not force journal temp: ${readOutput(output)}")
        child.destroyForcibly()
        assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Killed journal child did not terminate")

        val retained = TransactionLog(logDir).loadRecord(transaction.id)
        assertEquals(JournalState.PREPARED, retained?.state)
        assertEquals(listOf(JournalState.PREPARED), retained?.history?.map(JournalEvent::state))
        assertTrue(Files.list(logDir).use { stream ->
            stream.anyMatch { it.fileName.toString().startsWith(".${transaction.id.value}.json.tmp-") }
        })
        TransactionLog(logDir).update(requireNotNull(retained).copy(state = JournalState.ROLLED_BACK))
        assertEquals(JournalState.ROLLED_BACK, TransactionLog(logDir).loadRecord(transaction.id)?.state)
    }

    private fun readOutput(path: Path): String =
        if (Files.exists(path)) Files.readString(path) else "<no child output>"
}

object ProcessKillJournalUpdateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val logDir = Path.of(args[0])
        val id = requireNotNull(TransactionId.parseOrNull(args[1]))
        val marker = Path.of(args[2])
        val log = TransactionLog(logDir, JournalFaultInjector { point, _ ->
            if (point == JournalFaultPoint.AFTER_UPDATE_TEMP_FORCE) {
                Files.writeString(
                    marker,
                    "ready\n",
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC,
                )
                Thread.sleep(Duration.ofMinutes(5).toMillis())
            }
        })
        val record = requireNotNull(log.loadRecord(id))
        log.update(record.copy(state = JournalState.APPLYING))
    }
}

object ProcessKillApplyMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val marker = Path.of(args[1])
        val snapshot = snapshot(root)
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
            operation = "processKillAfterPartialCommit",
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            summary = "wait for process kill after first commit",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
        )
        val injector = PatchFaultInjector { point, _, sequence ->
            if (point == PatchFaultPoint.AFTER_COMMITTED_IMAGE && sequence == 1) {
                Files.writeString(
                    marker,
                    "ready\n",
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.SYNC,
                )
                Thread.sleep(Duration.ofMinutes(5).toMillis())
            }
        }
        PatchEngine(root, faultInjector = injector).apply(plan, snapshot)
    }

    private fun snapshot(root: Path): ProjectSnapshot {
        val files = listOf("First.java", "Second.java").map { name ->
            SourceFile(Path.of(name), Files.readString(root.resolve(name)), "test")
        }
        return ProjectSnapshot(Workspace(root), emptyList(), files)
    }
}

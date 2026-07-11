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

    private fun readOutput(path: Path): String =
        if (Files.exists(path)) Files.readString(path) else "<no child output>"
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

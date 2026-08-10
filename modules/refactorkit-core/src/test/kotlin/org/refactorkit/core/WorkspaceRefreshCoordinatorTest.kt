package org.refactorkit.core

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class WorkspaceRefreshCoordinatorTest {
    @Test
    fun unchangedScanReturnsExactCurrentInstanceWithoutPreparingState() {
        val current = snapshot("class Stable {}")
        val preparations = AtomicInteger()

        val result = WorkspaceRefreshCoordinator.refresh(current, { current.copy() }) { _, _ ->
            preparations.incrementAndGet()
        }

        val unchanged = assertIs<WorkspaceRefreshResult.Unchanged>(result)
        assertSame(current, unchanged.snapshot)
        assertEquals(0, preparations.get())
    }

    @Test
    fun changedScanPreparesExactlyOnceBeforePublishingNextSnapshot() {
        val current = snapshot("class Before {}")
        val next = snapshot("class After {}")
        val trace = mutableListOf<String>()

        val result = WorkspaceRefreshCoordinator.refresh(current, {
            trace += "scan"
            next
        }) { observedCurrent, observedNext ->
            trace += "prepare"
            assertSame(current, observedCurrent)
            assertSame(next, observedNext)
            "prepared-index"
        }

        val changed = assertIs<WorkspaceRefreshResult.Changed<String>>(result)
        assertEquals(listOf("scan", "prepare"), trace)
        assertEquals(current.hash, changed.previousSnapshotHash)
        assertSame(next, changed.snapshot)
        assertEquals("prepared-index", changed.preparedState)
    }

    @Test
    fun collaboratorFailureEscapesByIdentityAndChangedRootRefuses() {
        val current = snapshot("class Current {}")
        val sentinel = RefreshFailure()

        val escaped = assertFailsWith<RefreshFailure> {
            WorkspaceRefreshCoordinator.refresh(current, { throw sentinel })
        }
        assertSame(sentinel, escaped)

        val otherRoot = ProjectSnapshot(
            workspace = Workspace(Path.of("/other")),
            modules = emptyList(),
            files = current.files,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkspaceRefreshCoordinator.refresh(current, { otherRoot })
        }
    }

    private fun snapshot(content: String) = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(SourceFile(Path.of("src/main/java/Value.java"), content, "java")),
    )

    private class RefreshFailure : RuntimeException("sentinel")
}

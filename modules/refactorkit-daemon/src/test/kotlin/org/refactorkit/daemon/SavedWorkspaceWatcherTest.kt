package org.refactorkit.daemon

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedWorkspaceWatcherTest {
    @Test
    fun recursivelyDetectsSavedFileChangesWithoutWritingWorkspaceMetadata() {
        val root = Files.createTempDirectory("refactorkit-watch")
        Files.createDirectories(root.resolve("src"))
        val watcher = SavedWorkspaceWatcher.start(root).getOrThrow()
        try {
            assertEquals("active", watcher.status().state)
            root.resolve("src/App.ts").writeText("export const answer = 42\n")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!watcher.status().dirty && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(watcher.consumeDirty())
            assertFalse(Files.exists(root.resolve(".refactorkit")))
        } finally {
            watcher.close()
        }
        assertEquals("closed", watcher.status().state)
    }
}

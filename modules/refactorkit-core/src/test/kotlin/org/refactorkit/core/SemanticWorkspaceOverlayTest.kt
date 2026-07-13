package org.refactorkit.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SemanticWorkspaceOverlayTest {
    @Test
    fun materializesBoundedSourcesAndMapsPathsBackToWorkspace() {
        val workspace = Files.createTempDirectory("refactorkit-overlay-workspace")
        val snapshot = snapshot(workspace, listOf(
            SourceFile(Path.of("src/main.ts"), "const value = 'é';\n", "typescript"),
        ))
        val overlay = SemanticWorkspaceOverlay.create(snapshot)
        val root = overlay.root

        assertEquals("const value = 'é';\n", Files.readString(root.resolve("src/main.ts")))
        assertEquals(workspace.resolve("src/main.ts"), overlay.toWorkspacePath(root.resolve("src/main.ts")))
        assertEquals(null, overlay.toWorkspacePath(workspace.resolve("src/main.ts")))
        assertTrue(overlay.verifySourcesUnchanged().isEmpty())

        overlay.close()
        assertFalse(Files.exists(root))
    }

    @Test
    fun detectsSourceMutationAndDeletionWithoutTouchingRealWorkspace() {
        val workspace = Files.createTempDirectory("refactorkit-overlay-workspace")
        val realSource = workspace.resolve("src/main.ts")
        Files.createDirectories(realSource.parent)
        Files.writeString(realSource, "real bytes")
        val snapshot = snapshot(workspace, listOf(SourceFile(Path.of("src/main.ts"), "snapshot bytes", "typescript")))
        val overlay = SemanticWorkspaceOverlay.create(snapshot)
        val overlaySource = overlay.root.resolve("src/main.ts")
        overlaySource.toFile().setWritable(true)
        Files.writeString(overlaySource, "server mutation")

        assertEquals(listOf("semantic.overlaySourceModified"), overlay.verifySourcesUnchanged().map(Diagnostic::code))
        assertEquals("real bytes", Files.readString(realSource))

        Files.delete(overlaySource)
        assertEquals(listOf("semantic.overlaySourceModified"), overlay.verifySourcesUnchanged().map(Diagnostic::code))
        overlay.close()
    }

    @Test
    fun refusesUnsafeDuplicateAndOverLimitSnapshots() {
        val workspace = Files.createTempDirectory("refactorkit-overlay-workspace")
        assertFailsWith<IllegalArgumentException> {
            SemanticWorkspaceOverlay.create(snapshot(workspace, listOf(SourceFile(Path.of("../escape.ts"), "x", "typescript"))))
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticWorkspaceOverlay.create(snapshot(workspace, listOf(
                SourceFile(Path.of("a.ts"), "x", "typescript"),
                SourceFile(Path.of("a.ts"), "y", "typescript"),
            )))
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticWorkspaceOverlay.create(
                snapshot(workspace, listOf(SourceFile(Path.of("large.ts"), "large", "typescript"))),
                maxFiles = 1,
                maxBytes = 2,
            )
        }
    }

    private fun snapshot(root: Path, files: List<SourceFile>) = ProjectSnapshot(
        workspace = Workspace(root), modules = emptyList(), files = files,
    )
}

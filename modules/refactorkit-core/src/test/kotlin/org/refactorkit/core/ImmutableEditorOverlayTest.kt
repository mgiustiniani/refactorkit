package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ImmutableEditorOverlayTest {
    private val path = Path.of("src/app.ts")
    private val saved = ProjectSnapshot(
        workspace = Workspace(Path.of("/workspace")),
        modules = emptyList(),
        files = listOf(
            SourceFile(path, "export const value = 1;\n", "typescript"),
            SourceFile(Path.of("src/App.java"), "class App {}\n", "java"),
        ),
    )

    @Test
    fun `derives deterministic versioned provider snapshot without mutating saved content`() {
        val document = ImmutableEditorOverlayDocument(path, 7L, "export const value = 2;\n")
        val overlay = ImmutableEditorOverlay.create(saved, listOf(document), "typescript")
        val repeated = ImmutableEditorOverlay.create(saved, listOf(document), "typescript")

        assertEquals(saved.hash, overlay.baseSnapshotHash)
        assertNotEquals(saved.hash, overlay.providerSnapshot.hash)
        assertEquals("export const value = 1;\n", saved.files.single { it.path == path }.content)
        assertEquals("export const value = 2;\n", overlay.providerSnapshot.files.single { it.path == path }.content)
        assertEquals(overlay.overlayHash, repeated.overlayHash)
        assertEquals(7L, overlay.authority(path)?.version)
        assertEquals(SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY, overlay.authority(path)?.mode)
    }

    @Test
    fun `rejects duplicate unsafe missing wrong-language and invalid documents`() {
        val valid = ImmutableEditorOverlayDocument(path, 1L, "value\n")
        assertFailsWith<IllegalArgumentException> { ImmutableEditorOverlay.create(saved, listOf(valid, valid)) }
        assertFailsWith<IllegalArgumentException> {
            ImmutableEditorOverlay.create(saved, listOf(ImmutableEditorOverlayDocument(Path.of("../app.ts"), 1L, "x")))
        }
        assertFailsWith<IllegalStateException> {
            ImmutableEditorOverlay.create(saved, listOf(ImmutableEditorOverlayDocument(Path.of("src/missing.ts"), 1L, "x")))
        }
        assertFailsWith<IllegalArgumentException> { ImmutableEditorOverlay.create(saved, listOf(valid), "java") }
        assertFailsWith<IllegalArgumentException> {
            ImmutableEditorOverlay.create(saved, listOf(ImmutableEditorOverlayDocument(path, -1L, "x")))
        }
        assertFailsWith<IllegalArgumentException> {
            ImmutableEditorOverlay.create(saved, listOf(ImmutableEditorOverlayDocument(path, 1L, "bad\u0000content")))
        }
    }

    @Test
    fun `semantic query envelope binds authority to exact overlay content and version`() {
        val overlay = ImmutableEditorOverlay.create(
            saved,
            listOf(ImmutableEditorOverlayDocument(path, 4L, "export class App {}\n")),
            "typescript",
        )
        val authority = requireNotNull(overlay.authority(path))
        val envelope = SemanticQueryEnvelope(
            requestId = "overlay-query-1",
            expectedSnapshotHash = saved.hash,
            document = authority,
            overlay = overlay,
            query = SemanticQueryRequest.Hover(SourcePosition(0, 7)),
        )
        assertEquals(overlay.overlayHash, envelope.overlay?.overlayHash)

        assertFailsWith<IllegalArgumentException> {
            SemanticQueryEnvelope(
                requestId = "overlay-query-2",
                expectedSnapshotHash = saved.hash,
                document = authority.copy(version = 5),
                overlay = overlay,
                query = SemanticQueryRequest.Hover(SourcePosition(0, 7)),
            )
        }
    }
}

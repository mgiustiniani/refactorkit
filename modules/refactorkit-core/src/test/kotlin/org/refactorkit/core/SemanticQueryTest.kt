package org.refactorkit.core

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SemanticQueryTest {
    private val hash = "a".repeat(64)

    @Test
    fun `interactive requests require versioned document authority`() {
        assertFailsWith<IllegalArgumentException> {
            SemanticQueryEnvelope(
                requestId = "completion-1",
                expectedSnapshotHash = hash,
                query = SemanticQueryRequest.Completion(SourcePosition(1, 2)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SemanticDocumentAuthority(
                Paths.get("src/App.kt"),
                SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY,
                hash,
            )
        }

        val authority = SemanticDocumentAuthority(
            Paths.get("src/App.kt"),
            SemanticDocumentMode.IMMUTABLE_EDITOR_OVERLAY,
            hash,
            version = 7,
        )
        val envelope = SemanticQueryEnvelope(
            requestId = "completion-2",
            expectedSnapshotHash = hash,
            expectedIndexGeneration = 3,
            document = authority,
            query = SemanticQueryRequest.Completion(SourcePosition(1, 2)),
        )
        assertEquals(SemanticQueryKind.COMPLETION, envelope.query.kind)
        assertEquals(7, envelope.document?.version)
    }

    @Test
    fun `symbol search remains a typed non-document query`() {
        val envelope = SemanticQueryEnvelope(
            requestId = "symbols-1",
            expectedSnapshotHash = hash,
            query = SemanticQueryRequest.WorkspaceSymbols("User", "java", limit = 20),
        )
        assertEquals(SemanticQueryKind.WORKSPACE_SYMBOLS, envelope.query.kind)
        assertEquals(false, envelope.query.requiresDocument)
    }
}

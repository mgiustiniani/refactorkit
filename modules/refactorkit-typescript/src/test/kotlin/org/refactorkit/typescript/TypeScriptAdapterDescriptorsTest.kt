package org.refactorkit.typescript

import org.refactorkit.core.AdapterExecutionMode
import org.refactorkit.core.DiagnosticRangeCapability
import org.refactorkit.core.DiagnosticSnapshotMode
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.SemanticEvidenceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypeScriptAdapterDescriptorsTest {
    @Test
    fun composesStructuralAndSemanticCapabilityLayers() {
        val descriptor = TypeScriptAdapterDescriptors.descriptor("typescript")

        assertEquals(setOf("ts", "tsx"), descriptor.extensions)
        assertEquals(
            listOf(
                "definition", "diagnostics", "identifierSearch", "localRename", "outline", "references",
                "renameSymbol", "workspaceSymbols",
            ),
            descriptor.capabilities.map { it.operation },
        )
        val outline = descriptor.capabilities.single { it.operation == "outline" }
        assertEquals(SemanticEvidenceKind.NATIVE_AST, outline.evidence)
        assertEquals(AdapterExecutionMode.IN_PROCESS, outline.runtime?.executionMode)
        assertEquals(setOf("ts"), outline.extensions)
        val diagnostics = descriptor.capabilities.single { it.operation == "diagnostics" }
        assertEquals(SemanticEvidenceKind.COMPILER, diagnostics.evidence)
        assertEquals(TypeScriptDiagnosticsContract.BACKEND, diagnostics.backend)
        assertEquals(TypeScriptDiagnosticsContract.runtime, diagnostics.runtime)
        assertEquals(MutationAuthority.NONE, diagnostics.mutationAuthority)
        assertEquals(
            setOf(DiagnosticSnapshotMode.SAVED_DISK, DiagnosticSnapshotMode.IMMUTABLE_EDITOR_OVERLAY),
            diagnostics.diagnosticSnapshotModes,
        )
        assertEquals(DiagnosticRangeCapability.EXACT_UTF16_OR_EXPLICIT_PARTIAL, diagnostics.diagnosticRangeCapability)
        val rename = descriptor.capabilities.single { it.operation == "renameSymbol" }
        assertEquals(SemanticEvidenceKind.LANGUAGE_SERVER, rename.evidence)
        assertEquals(MutationAuthority.PROPOSAL_ONLY, rename.mutationAuthority)
        assertEquals(AdapterExecutionMode.EXTERNAL_PROCESS, rename.runtime?.executionMode)
        assertEquals(setOf("ts", "tsx"), rename.extensions)
        assertTrue(rename.runtime?.usesWorkspaceOverlay == true)
    }
}

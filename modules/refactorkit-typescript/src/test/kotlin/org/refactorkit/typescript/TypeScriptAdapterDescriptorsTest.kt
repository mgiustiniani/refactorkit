package org.refactorkit.typescript

import org.refactorkit.core.AdapterExecutionMode
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
            listOf("definition", "diagnostics", "identifierSearch", "localRename", "outline", "references", "renameSymbol"),
            descriptor.capabilities.map { it.operation },
        )
        val outline = descriptor.capabilities.single { it.operation == "outline" }
        assertEquals(SemanticEvidenceKind.NATIVE_AST, outline.evidence)
        assertEquals(AdapterExecutionMode.IN_PROCESS, outline.runtime?.executionMode)
        assertEquals(setOf("ts"), outline.extensions)
        val rename = descriptor.capabilities.single { it.operation == "renameSymbol" }
        assertEquals(SemanticEvidenceKind.LANGUAGE_SERVER, rename.evidence)
        assertEquals(MutationAuthority.PROPOSAL_ONLY, rename.mutationAuthority)
        assertEquals(AdapterExecutionMode.EXTERNAL_PROCESS, rename.runtime?.executionMode)
        assertEquals(setOf("ts", "tsx"), rename.extensions)
        assertTrue(rename.runtime?.usesWorkspaceOverlay == true)
    }
}

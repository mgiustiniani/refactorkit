package org.refactorkit.kotlin

import org.refactorkit.core.CapabilityStability
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.LanguageAdapterRegistry
import org.refactorkit.core.LanguageRoute
import org.refactorkit.core.MutationAuthority
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.SemanticEvidenceKind
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KotlinLanguageAdapterTest {
    @Test
    fun descriptorPromotesBoundedCompilerReadsAndPrivateTypeRenameProposalOnly() {
        val descriptor = KotlinAdapterRegistration.descriptor()

        assertEquals("kotlin", descriptor.languageId)
        assertEquals(setOf("kt", "kts"), descriptor.extensions)
        assertEquals("kotlin-analysis-unavailable-v1", descriptor.backend)
        assertTrue(descriptor.capabilities.isNotEmpty())
        val diagnostics = descriptor.capabilities.single { it.operation == "diagnostics" }
        assertEquals(CapabilityStability.EXPERIMENTAL, diagnostics.stability)
        assertEquals(SemanticEvidenceKind.COMPILER, diagnostics.evidence)
        assertEquals(KotlinCompilerDiagnostics.BACKEND, diagnostics.backend)
        assertEquals(1, diagnostics.runtime?.limits?.maxProcesses)
        val symbolReads = descriptor.capabilities.filter {
            it.operation in setOf("workspaceSymbols", "documentSymbols", "definition")
        }
        assertTrue(symbolReads.all {
            it.stability == CapabilityStability.EXPERIMENTAL &&
                it.evidence == SemanticEvidenceKind.COMPILER &&
                it.backend == KotlinCompilerDiagnostics.SYMBOL_BACKEND
        })
        assertTrue(descriptor.capabilities.filter {
            it.operation !in setOf("diagnostics", "workspaceSymbols", "documentSymbols", "definition", "renameSymbol")
        }.all { it.stability == CapabilityStability.REFUSED && it.evidence == SemanticEvidenceKind.NONE })
        val rename = descriptor.capabilities.single { it.operation == "renameSymbol" }
        assertEquals(CapabilityStability.EXPERIMENTAL, rename.stability)
        assertEquals(SemanticEvidenceKind.COMPILER, rename.evidence)
        assertEquals(MutationAuthority.PROPOSAL_ONLY, rename.mutationAuthority)
        assertTrue(descriptor.capabilities.filter { it.operation != "renameSymbol" }
            .all { it.mutationAuthority == MutationAuthority.NONE })
        assertEquals(setOf("kts"), descriptor.capabilities.single { it.operation == "scriptSemantics" }.extensions)
        assertTrue(descriptor.capabilities.filter { it.operation != "scriptSemantics" }.all {
            it.extensions == setOf("kt")
        })
    }

    @Test
    fun adapterReturnsTypedBackendUnavailableResultsWithoutEdits() {
        val snapshot = snapshot("Example.kt", "class Example\n")
        val adapter = KotlinLanguageAdapter()
        val location = SourceLocation(
            Path.of("Example.kt"),
            SourceRange(SourcePosition(0, 6), SourcePosition(0, 13)),
        )

        assertTrue(adapter.buildSymbols(snapshot).symbols.isEmpty())
        assertEquals(
            listOf(KotlinLanguageAdapter.BACKEND_UNAVAILABLE_CODE),
            adapter.resolveSymbol(location).diagnostics.map { it.code },
        )
        assertEquals(
            listOf("kotlin.toolchainNotConfigured"),
            adapter.diagnostics(snapshot).map { it.code },
        )
        assertTrue(adapter.availableRefactorings(CodeSelection(location)).isEmpty())

        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = CodeSelection(location),
            arguments = mapOf("newName" to "Renamed"),
            snapshot = snapshot,
        ))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals(KotlinLanguageAdapter.BACKEND_UNAVAILABLE_CODE, plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(plan.affectedFiles.isEmpty())
    }

    @Test
    fun registryRoutesKotlinScriptsButUnconfiguredAdapterStillRefusesBeforeMutation() {
        val snapshot = snapshot("Example.kts", "val example = 1\n")
        val registry = LanguageAdapterRegistry(listOf(KotlinAdapterRegistration.create()))

        val route = assertIs<LanguageRoute.Resolved>(registry.route(Path.of("Example.kts")))
        assertEquals("kotlin", route.registration.descriptor.languageId)

        val plan = registry.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = CodeSelection(SourceLocation(
                Path.of("Example.kts"),
                SourceRange(SourcePosition(0, 4), SourcePosition(0, 11)),
            )),
            arguments = mapOf("newName" to "renamed"),
            snapshot = snapshot,
        ))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals(KotlinLanguageAdapter.BACKEND_UNAVAILABLE_CODE, plan.refusalCode)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    private fun snapshot(fileName: String, content: String): ProjectSnapshot {
        val root = Files.createTempDirectory("refactorkit-kotlin-adapter")
        return ProjectSnapshot(
            workspace = Workspace(root),
            modules = emptyList(),
            files = listOf(SourceFile(Path.of(fileName), content, "kotlin")),
        )
    }
}

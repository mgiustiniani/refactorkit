package org.refactorkit.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LanguageAdapterRegistryTest {
    @Test
    fun rejectsAmbiguousIdsExtensionsAndUnsafeStableAuthority() {
        val java = registration("java", setOf("java"))
        val kotlin = registration("kotlin", setOf("kt", "java"))
        assertFailsWith<IllegalArgumentException> { LanguageAdapterRegistry(listOf(java, kotlin)) }
        assertFailsWith<IllegalArgumentException> { LanguageAdapterRegistry(listOf(java, java)) }
        assertFailsWith<IllegalArgumentException> {
            LanguageCapability(
                "rename",
                CapabilityStability.STABLE,
                SemanticEvidenceKind.LEXICAL,
                MutationAuthority.MANAGED_STABLE,
            )
        }
    }

    @Test
    fun routesScannedLanguageAuthoritativelyAndPathByUniqueExtension() {
        val registry = LanguageAdapterRegistry(listOf(
            registration("java", setOf("java")),
            registration("kotlin", setOf("kt", "kts")),
        ))

        val scanned = assertIs<LanguageRoute.Resolved>(registry.route(SourceFile(
            Path.of("generated/name.kt"), "", "java",
        )))
        assertEquals("java", scanned.registration.descriptor.languageId)
        val path = assertIs<LanguageRoute.Resolved>(registry.route(Path.of("src/App.kts")))
        assertEquals("kotlin", path.registration.descriptor.languageId)
        val unsupported = assertIs<LanguageRoute.Refused>(registry.route(Path.of("README")))
        assertEquals("language.unsupported", unsupported.diagnostic.code)
    }

    @Test
    fun aggregatesMixedSymbolsAndDiagnosticsDeterministically() {
        val snapshot = snapshot()
        val registry = LanguageAdapterRegistry(listOf(
            registration("kotlin", setOf("kt"), symbol = "fixture.KotlinType"),
            registration("java", setOf("java"), symbol = "fixture.JavaType"),
        ))

        val symbols = registry.buildSymbols(snapshot).symbols
        val diagnostics = registry.diagnostics(snapshot)

        assertEquals(listOf("java", "kotlin"), symbols.map(Symbol::languageId))
        assertEquals(listOf("java.fixture", "kotlin.fixture"), diagnostics.mapNotNull(Diagnostic::code))
    }

    @Test
    fun refactoringRoutingRequiresEvidenceAndDeclaredCapability() {
        val snapshot = snapshot()
        val java = registration("java", setOf("java"), operations = setOf("rename"))
        val kotlin = registration("kotlin", setOf("kt"), operations = emptySet())
        val registry = LanguageAdapterRegistry(listOf(java, kotlin))
        val selected = RefactoringRequest(
            operation = "rename",
            selection = CodeSelection(SourceLocation(
                Path.of("src/Main.java"), SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)),
            )),
            snapshot = snapshot,
        )
        assertEquals(PatchStatus.PREVIEW, registry.applyRefactoring(selected).status)

        val unsupported = selected.copy(
            selection = CodeSelection(SourceLocation(
                Path.of("src/App.kt"), SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)),
            )),
        )
        assertEquals("language.operationUnsupported", registry.applyRefactoring(unsupported).refusalCode)
        val missing = selected.copy(selection = null)
        assertEquals("language.routeMissing", registry.applyRefactoring(missing).refusalCode)
    }

    @Test
    fun stableAuthorityRefusesWeakerPlanEvidence() {
        val adapter = FixtureAdapter("java", "java.Type")
        val registry = LanguageAdapterRegistry(listOf(RegisteredLanguageAdapter(
            LanguageAdapterDescriptor("java", setOf("java"), "fixture", listOf(
                LanguageCapability(
                    "rename",
                    CapabilityStability.STABLE,
                    SemanticEvidenceKind.COMPILER,
                    MutationAuthority.MANAGED_STABLE,
                ),
            )),
            adapter,
        )))
        val plan = registry.applyRefactoring(RefactoringRequest(
            operation = "rename",
            arguments = mapOf("languageId" to "java"),
            snapshot = snapshot(),
        ))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("language.evidenceInsufficient", plan.refusalCode)
    }

    @Test
    fun symbolRoutingRefusesCrossLanguageIdentityAmbiguity() {
        val snapshot = snapshot()
        val registry = LanguageAdapterRegistry(listOf(
            registration("java", setOf("java"), symbol = "shared.Type"),
            registration("kotlin", setOf("kt"), symbol = "shared.Type"),
        ))
        val route = registry.route(RefactoringRequest(
            operation = "rename",
            symbolId = SymbolId("shared.Type"),
            snapshot = snapshot,
        ))
        assertEquals("language.symbolAmbiguous", assertIs<LanguageRoute.Refused>(route).diagnostic.code)
    }

    private fun registration(
        language: String,
        extensions: Set<String>,
        symbol: String = "$language.Type",
        operations: Set<String> = setOf("rename"),
    ): RegisteredLanguageAdapter {
        val capabilities = operations.map {
            LanguageCapability(it, CapabilityStability.STRUCTURAL, SemanticEvidenceKind.STRUCTURAL_PARSE, MutationAuthority.PROPOSAL_ONLY)
        }
        return RegisteredLanguageAdapter(
            LanguageAdapterDescriptor(language, extensions, "fixture-$language", capabilities),
            FixtureAdapter(language, symbol),
        )
    }

    private fun snapshot() = ProjectSnapshot(
        workspace = Workspace(Path.of(".")),
        modules = emptyList(),
        files = listOf(
            SourceFile(Path.of("src/Main.java"), "class Main {}", "java"),
            SourceFile(Path.of("src/App.kt"), "class App", "kotlin"),
        ),
    )

    private class FixtureAdapter(
        private val language: String,
        private val symbolName: String,
    ) : LanguageAdapter {
        override fun languageId() = language
        override fun parse(file: SourceFile) = ParseResult(file)
        override fun buildSymbols(project: ProjectSnapshot): SymbolIndex {
            val file = project.files.firstOrNull { it.languageId == language } ?: return SymbolIndex(emptyList())
            return SymbolIndex(listOf(Symbol(
                SymbolId(symbolName),
                symbolName.substringAfterLast('.'),
                Symbol.Kind.CLASS,
                SourceLocation(file.path, SourceRange(SourcePosition(0, 0), SourcePosition(0, 1))),
                language,
            )))
        }
        override fun resolveSymbol(location: SourceLocation) = SymbolResolution(null)
        override fun findReferences(symbolId: SymbolId) = emptyList<Reference>()
        override fun diagnostics(project: ProjectSnapshot) = listOf(Diagnostic(
            "$language diagnostic",
            Diagnostic.Severity.WARNING,
            code = "$language.fixture",
        ))
        override fun availableRefactorings(selection: CodeSelection) = emptyList<RefactoringDescriptor>()
        override fun applyRefactoring(request: RefactoringRequest) = PatchPlan(
            operation = request.operation,
            snapshotHash = request.snapshot.hash,
            confidence = 0.5,
            summary = "$language proposal",
            affectedFiles = emptySet(),
            workspaceEdit = WorkspaceEdit(),
            evidence = RefactoringEvidence.STRUCTURAL,
        )
        override fun formatEdits(edits: List<TextEdit>) = edits
    }
}

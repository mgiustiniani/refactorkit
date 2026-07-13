package org.refactorkit.typescript

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.TextEdit
import org.refactorkit.core.Workspace
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.treesitter.ExternalSemanticSessionProvenance
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypeScriptSemanticAdapterTest {
    @Test
    fun reportsTypedCheckedAndDynamicCompletenessAndBlocksDynamicManagedGate() {
        val fixture = fixture()
        val typed = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, FakeClient())
        assertEquals(TypeScriptSemanticCompletenessMode.FULL_TYPESCRIPT, typed.semanticCompleteness().mode)
        assertTrue(typed.semanticCompleteness().managedMutationEligible)

        val checkedModel = fixture.model.copy(projects = fixture.model.projects.map { project ->
            project.copy(compilerOptions = project.compilerOptions.copy(checkJs = true, allowJs = true))
        })
        val checked = TypeScriptSemanticAdapter("javascript", fixture.toolchain, checkedModel, FakeClient())
        assertEquals(TypeScriptSemanticCompletenessMode.CHECKED_JAVASCRIPT, checked.semanticCompleteness().mode)
        assertTrue(checked.semanticCompleteness().managedMutationEligible)

        val dynamic = TypeScriptSemanticAdapter("javascript", fixture.toolchain, fixture.model, FakeClient())
        assertEquals(TypeScriptSemanticCompletenessMode.DYNAMIC_JAVASCRIPT, dynamic.semanticCompleteness().mode)
        assertFalse(dynamic.semanticCompleteness().managedMutationEligible)
        assertIs<TypeScriptSemanticStart.Started>(dynamic.start(fixture.snapshot))
        val failure = assertFailsWith<IllegalStateException> {
            dynamic.diagnosticsGate().provider!!(fixture.snapshot)
        }
        assertTrue(failure.message.orEmpty().contains("typescript.semanticCompletenessInsufficient"))
    }

    @Test
    fun startsOnlyWithHashBoundModelToolchainAndRequiredCapabilities() {
        val fixture = fixture()
        val client = FakeClient()
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)

        val result = adapter.start(fixture.snapshot)

        assertIs<TypeScriptSemanticStart.Started>(result)
        assertTrue(client.startedSnapshot!!.files.any { it.path == Path.of("tsconfig.json") && it.languageId == "jsonc" })
        assertTrue(client.startedSnapshot!!.files.any { it.path == Path.of("package.json") })
        assertEquals(listOf("Service"), adapter.buildSymbols(fixture.snapshot).symbols.map(Symbol::name))
        assertEquals("Service", adapter.resolveSymbol(location()).symbol?.name)
        assertEquals(1, adapter.findReferences(SymbolId("src/service.ts::Service")).size)
        assertTrue(adapter.diagnostics(fixture.snapshot).isEmpty())
        adapter.close()
        assertFalse(client.isRunning)
    }

    @Test
    fun crashRestartIsExplicitBoundedAndWindowed() {
        val fixture = fixture()
        val client = FakeClient()
        var now = 10_000L
        val adapter = TypeScriptSemanticAdapter(
            "typescript", fixture.toolchain, fixture.model, client,
            currentTimeMillis = { now },
        )
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))
        client.crash()
        assertEquals(
            "typescript.semanticRestartRequired",
            assertIs<TypeScriptSemanticStart.Refused>(adapter.start(fixture.snapshot)).diagnostics.single().code,
        )

        repeat(TypeScriptSemanticAdapter.MAX_RESTARTS_PER_WINDOW) {
            assertIs<TypeScriptSemanticStart.Started>(adapter.restart(fixture.snapshot))
            client.crash()
        }
        val limited = assertIs<TypeScriptSemanticStart.Refused>(adapter.restart(fixture.snapshot))
        assertEquals("typescript.restartLimitExceeded", limited.diagnostics.single().code)

        now += TypeScriptSemanticAdapter.RESTART_WINDOW_MILLIS
        assertIs<TypeScriptSemanticStart.Started>(adapter.restart(fixture.snapshot))
        assertEquals(5, client.startCount)
    }

    @Test
    fun restartRefusesChangedServerProvenance() {
        val fixture = fixture()
        val initial = sessionProvenance(fixture.toolchain.nodeExecutable, "1.0.0")
        val client = FakeClient(sessionProvenance = initial)
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))
        client.crash()
        client.sessionProvenance = sessionProvenance(fixture.toolchain.nodeExecutable, "2.0.0")

        val refused = assertIs<TypeScriptSemanticStart.Refused>(adapter.restart(fixture.snapshot))
        assertEquals("typescript.serverProvenanceChanged", refused.diagnostics.single().code)
        assertFalse(client.isRunning)
    }

    @Test
    fun refusesMissingCapabilitiesAndSnapshotModelMismatch() {
        val fixture = fixture()
        val missing = FakeClient(capabilities = setOf("definitionProvider"))
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, missing)
        val refused = assertIs<TypeScriptSemanticStart.Refused>(adapter.start(fixture.snapshot))
        assertEquals(listOf("typescript.serverCapabilityMissing"), refused.diagnostics.map(Diagnostic::code))
        assertFalse(missing.isRunning)

        val withoutModel = fixture.snapshot.copy(buildModels = emptyList())
        val mismatch = assertIs<TypeScriptSemanticStart.Refused>(
            TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, FakeClient()).start(withoutModel),
        )
        assertEquals(listOf("typescript.modelSnapshotMismatch"), mismatch.diagnostics.map(Diagnostic::code))
    }

    @Test
    fun refusesProjectOrToolchainEvidenceDriftBeforeServerStart() {
        val configDrift = fixture()
        configDrift.root.resolve("tsconfig.json").writeText("""{"compilerOptions":{"checkJs":true}}""")
        val configClient = FakeClient()
        val configResult = assertIs<TypeScriptSemanticStart.Refused>(
            TypeScriptSemanticAdapter("typescript", configDrift.toolchain, configDrift.model, configClient).start(configDrift.snapshot),
        )
        assertEquals(listOf("typescript.modelEvidenceChanged"), configResult.diagnostics.map(Diagnostic::code))
        assertFalse(configClient.isRunning)

        val toolchainDrift = fixture()
        toolchainDrift.toolchain.languageServerEntrypoint.writeText("changed")
        val toolchainClient = FakeClient()
        val toolchainResult = assertIs<TypeScriptSemanticStart.Refused>(
            TypeScriptSemanticAdapter("typescript", toolchainDrift.toolchain, toolchainDrift.model, toolchainClient).start(toolchainDrift.snapshot),
        )
        assertEquals(listOf("typescript.toolchainEvidenceChanged"), toolchainResult.diagnostics.map(Diagnostic::code))
        assertFalse(toolchainClient.isRunning)
    }

    @Test
    fun producesLanguageServerRenameProposalWithoutApplying() {
        val fixture = fixture()
        val client = FakeClient()
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))

        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = org.refactorkit.core.CodeSelection(location()),
            arguments = mapOf("newName" to "AccountService"),
            snapshot = fixture.snapshot,
        ))

        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RefactoringEvidence.LANGUAGE_SERVER, plan.evidence)
        assertEquals(RiskLevel.MEDIUM, plan.riskLevel)
        assertTrue(plan.requiresUserApproval)
        assertEquals(setOf(Path.of("src/service.ts")), plan.affectedFiles)
        val modification = assertIs<FileEdit.Modify>(plan.workspaceEdit.edits.single())
        assertEquals("AccountService", modification.textEdits.single().newText)
        assertEquals(
            listOf("export class Service {}\n", "export class AccountService {}\n"),
            client.synchronizedSnapshots.map { it.files.single { file -> file.languageId == "typescript" }.content },
        )
        assertEquals("export class Service {}\n", Files.readString(fixture.root.resolve("src/service.ts")))
    }

    @Test
    fun renameRefusesMissingOrAmbiguousTypeScriptProjectOwnership() {
        val fixture = fixture()
        val buildModel = fixture.snapshot.buildModels.single { it.providerId == TypeScriptProjectModel.PROVIDER_ID }
        val missingSnapshot = fixture.snapshot.copy(buildModels = listOf(buildModel.copy(modules = emptyList())))
        val missingAdapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, FakeClient())
        assertIs<TypeScriptSemanticStart.Started>(missingAdapter.start(missingSnapshot))
        val missing = missingAdapter.applyRefactoring(renameRequest(missingSnapshot))
        assertEquals(PatchStatus.REFUSED, missing.status)
        assertEquals("typescript.projectOwnershipMissing", missing.refusalCode)
        missingAdapter.close()

        val originalModule = buildModel.modules.single()
        val duplicate = originalModule.copy(id = originalModule.id + ":duplicate", name = originalModule.name + " duplicate")
        val ambiguousSnapshot = fixture.snapshot.copy(
            buildModels = listOf(buildModel.copy(modules = buildModel.modules + duplicate)),
        )
        val ambiguousAdapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, FakeClient())
        assertIs<TypeScriptSemanticStart.Started>(ambiguousAdapter.start(ambiguousSnapshot))
        val ambiguous = ambiguousAdapter.applyRefactoring(renameRequest(ambiguousSnapshot))
        assertEquals(PatchStatus.REFUSED, ambiguous.status)
        assertEquals("typescript.projectOwnershipAmbiguous", ambiguous.refusalCode)
    }

    @Test
    fun managedRenamePassesExactDiagnosticsAuthorizationWalAndRollback() {
        val fixture = fixture()
        val client = FakeClient()
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))
        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = org.refactorkit.core.CodeSelection(location()),
            arguments = mapOf("newName" to "AccountService"),
            snapshot = fixture.snapshot,
        ))

        val engine = PatchEngine(fixture.root)
        val applied = assertIs<ApplyResult.Applied>(engine.apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("typescript-test"), adapter.diagnosticsGate(),
        ))
        assertEquals("export class AccountService {}\n", Files.readString(fixture.root.resolve("src/service.ts")))
        assertTrue(Files.isRegularFile(fixture.root.resolve(".refactorkit/transactions/${applied.transaction.id.value}.json")))

        assertIs<ApplyResult.Applied>(engine.rollback(applied.transaction))
        assertEquals("export class Service {}\n", Files.readString(fixture.root.resolve("src/service.ts")))
    }

    @Test
    fun managedApplyRevalidatesToolchainEvidenceUnderWorkspaceLock() {
        val fixture = fixture()
        val client = FakeClient()
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))
        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = org.refactorkit.core.CodeSelection(location()),
            arguments = mapOf("newName" to "AccountService"),
            snapshot = fixture.snapshot,
        ))
        fixture.toolchain.languageServerEntrypoint.writeText("drift after preview")

        val refused = assertIs<ApplyResult.Refused>(PatchEngine(fixture.root).apply(
            plan, fixture.snapshot, ApplyAuthorization.explicit("typescript-test"), adapter.diagnosticsGate(),
        ))
        assertEquals(listOf("diagnostics.unavailable"), refused.diagnostics.map(Diagnostic::code))
        assertTrue(refused.diagnostics.single().message.contains("typescript.toolchainEvidenceChanged"))
        assertEquals("export class Service {}\n", Files.readString(fixture.root.resolve("src/service.ts")))
        val transactionDirectory = fixture.root.resolve(".refactorkit/transactions")
        assertTrue(!Files.exists(transactionDirectory) || Files.list(transactionDirectory).use { it.findAny().isEmpty })
    }

    @Test
    fun exactDiagnosticsUnavailabilityRefusesRenamePreview() {
        val fixture = fixture()
        val unavailable = Diagnostic("missing exact version", Diagnostic.Severity.ERROR, code = "semantic.diagnosticsIncomplete")
        val client = FakeClient(exactDiagnostics = ExternalSemanticDiagnostics.Unavailable(unavailable))
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))

        val plan = adapter.applyRefactoring(RefactoringRequest(
            operation = "renameSymbol",
            selection = org.refactorkit.core.CodeSelection(location()),
            arguments = mapOf("newName" to "AccountService"),
            snapshot = fixture.snapshot,
        ))
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("semantic.diagnosticsIncomplete", plan.refusalCode)
    }

    @Test
    fun renameRefusalAndStaleSnapshotFailClosed() {
        val fixture = fixture()
        val client = FakeClient(renameRefused = true)
        val adapter = TypeScriptSemanticAdapter("typescript", fixture.toolchain, fixture.model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(fixture.snapshot))
        val request = RefactoringRequest(
            "renameSymbol",
            selection = org.refactorkit.core.CodeSelection(location()),
            arguments = mapOf("newName" to "X"),
            snapshot = fixture.snapshot,
        )
        val refused = adapter.applyRefactoring(request)
        assertEquals(PatchStatus.REFUSED, refused.status)
        assertEquals("externalEdit.testRefusal", refused.refusalCode)

        val stale = fixture.snapshot.copy(files = fixture.snapshot.files.map { it.copy(content = it.content + "// drift") })
        val stalePlan = adapter.applyRefactoring(request.copy(snapshot = stale))
        assertEquals(PatchStatus.REFUSED, stalePlan.status)
        assertEquals("typescript.semanticNotStarted", stalePlan.refusalCode)
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("refactorkit-ts-semantic")
        Files.createDirectories(root.resolve("src"))
        root.resolve("src/service.ts").writeText("export class Service {}\n")
        root.resolve("tsconfig.json").writeText("""{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""")
        root.resolve("package.json").writeText("""{"type":"module"}""")
        val model = TypeScriptProjectModelBuilder().build(root)
        val base = ProjectSnapshot(
            Workspace(root), modules = emptyList(),
            files = listOf(SourceFile(Path.of("src/service.ts"), "export class Service {}\n", "typescript")),
        )
        val snapshot = TypeScriptBuildModelIntegration.attach(base)
        return Fixture(root, model, snapshot, toolchain())
    }

    private fun toolchain(): TypeScriptSemanticToolchain {
        val root = Files.createTempDirectory("refactorkit-ts-semantic-toolchain")
        val node = root.resolve("node").also { it.writeText("node") }
        val serverManifest = root.resolve("server-package.json").also { it.writeText("{}") }
        val server = root.resolve("cli.mjs").also { it.writeText("server") }
        val compilerManifest = root.resolve("typescript-package.json").also { it.writeText("{}") }
        val tsserver = root.resolve("tsserver.js").also { it.writeText("compiler") }
        val evidence = listOf(
            evidence("node-executable", node), evidence("language-server-package", serverManifest),
            evidence("language-server-entrypoint", server), evidence("typescript-package", compilerManifest),
            evidence("typescript-server-entrypoint", tsserver),
        )
        return TypeScriptSemanticToolchain(
            node, server, tsserver, listOf(node.toString(), server.toString(), "--stdio"),
            TypeScriptToolchainProvenance(
                nodeVersion = "22.1.0", languageServerVersion = "4.3.3",
                typeScriptVersion = "5.7.2", evidence = evidence,
            ),
        )
    }

    private fun sessionProvenance(executable: Path, version: String) = ExternalSemanticSessionProvenance(
        SemanticProcessProvenance(
            "typescript-lsp", executable, "a".repeat(64), "b".repeat(64), executable.parent,
            1L, Instant.EPOCH,
        ),
        "typescript-language-server", version, "c".repeat(64),
        mapOf(
            "definitionProvider" to true, "referencesProvider" to true, "renameProvider" to true,
            "documentSymbolProvider" to true, "textDocumentSync" to true,
        ),
    )

    private fun evidence(role: String, path: Path): ToolchainFileEvidence {
        val bytes = Files.readAllBytes(path)
        return ToolchainFileEvidence(
            role, path,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            bytes.size.toLong(),
        )
    }

    private fun renameRequest(snapshot: ProjectSnapshot) = RefactoringRequest(
        operation = "renameSymbol",
        selection = org.refactorkit.core.CodeSelection(location()),
        arguments = mapOf("newName" to "AccountService"),
        snapshot = snapshot,
    )

    private fun location() = SourceLocation(
        Path.of("src/service.ts"),
        SourceRange(SourcePosition(0, 13), SourcePosition(0, 20)),
    )

    private data class Fixture(
        val root: Path,
        val model: TypeScriptProjectModel,
        val snapshot: ProjectSnapshot,
        val toolchain: TypeScriptSemanticToolchain,
    )

    private class FakeClient(
        private val capabilities: Set<String> = setOf(
            "definitionProvider", "referencesProvider", "renameProvider",
            "documentSymbolProvider", "textDocumentSync",
        ),
        private val renameRefused: Boolean = false,
        private val exactDiagnostics: ExternalSemanticDiagnostics = ExternalSemanticDiagnostics.Available(emptyList()),
        sessionProvenance: ExternalSemanticSessionProvenance? = null,
    ) : TypeScriptSemanticClient {
        override var isRunning: Boolean = false
        var sessionProvenance: ExternalSemanticSessionProvenance? = sessionProvenance
        override val provenance: ExternalSemanticSessionProvenance? get() = sessionProvenance
        var startedSnapshot: ProjectSnapshot? = null
        val synchronizedSnapshots = mutableListOf<ProjectSnapshot>()
        var startCount: Int = 0

        override fun start(snapshot: ProjectSnapshot) {
            startedSnapshot = snapshot
            startCount++
            isRunning = true
        }

        fun crash() { isRunning = false }

        override fun supports(capability: String): Boolean = capability in capabilities
        override fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex = SymbolIndex(listOf(symbol()))
        override fun resolveSymbol(location: SourceLocation): SymbolResolution = SymbolResolution(symbol())
        override fun findReferences(symbolId: SymbolId): List<Reference> = listOf(Reference(symbolId, symbolLocation()))
        override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = emptyList()
        override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics {
            synchronizedSnapshots += snapshot
            return exactDiagnostics
        }
        override fun requestRename(
            snapshot: ProjectSnapshot,
            location: SourceLocation,
            newName: String,
        ): ExternalWorkspaceEditNormalization {
            if (renameRefused) return ExternalWorkspaceEditNormalization.Refused(listOf(Diagnostic(
                "test refusal", Diagnostic.Severity.ERROR, code = "externalEdit.testRefusal",
            )))
            return ExternalWorkspaceEditNormalizer().normalize(snapshot, ExternalWorkspaceEditProposal(
                "lsp-typescript", "test",
                listOf(ExternalFileEditProposal.Modify(
                    Path.of("src/service.ts"),
                    listOf(TextEdit(SourceRange(SourcePosition(0, 13), SourcePosition(0, 20)), newName)),
                    documentVersion = 1,
                )),
            ))
        }

        override fun close() { isRunning = false }

        private fun symbol() = Symbol(
            SymbolId("src/service.ts::Service"), "Service", Symbol.Kind.CLASS,
            symbolLocation(), "typescript",
        )

        private fun symbolLocation() = SourceLocation(
            Path.of("src/service.ts"),
            SourceRange(SourcePosition(0, 13), SourcePosition(0, 20)),
        )
    }
}

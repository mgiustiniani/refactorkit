package org.refactorkit.typescript.relocation

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ImmutableEditorOverlay
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.Reference
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.SymbolIndex
import org.refactorkit.core.SymbolResolution
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.treesitter.ExternalSemanticSessionProvenance
import org.refactorkit.typescript.TypeScriptBuildModelIntegration
import org.refactorkit.typescript.TypeScriptProjectModel
import org.refactorkit.typescript.TypeScriptProjectModelBuilder
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptSemanticStart
import org.refactorkit.typescript.TypeScriptSemanticToolchain
import org.refactorkit.typescript.TypeScriptToolchainDiscoverer
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Story BDD glue for REQ-TS-SOURCE-FILE-RELOCATION-001. Scenarios 1 and 2
 * drive the real TypeScript semantic adapter against the pinned typescript
 * 5.9.3 compiler server (`getEditsForFileRename`). Scenario 3 exercises the
 * adapter's deterministic refusal logic under each stated condition.
 */
class TypeScriptSourceFileRelocationSteps {
    private lateinit var scenario: Scenario
    private var fixture: Fixture? = null
    private var importsFixture: TypeScriptRelocationImportsFixture? = null
    private var adapter: TypeScriptSemanticAdapter? = null
    private var plan: PatchPlan? = null

    @Before
    fun before(scenario: Scenario) {
        this.scenario = scenario
        cleanup()
    }

    @After
    fun after() = cleanup()

    // ── Scenario 1 ────────────────────────────────────────────────────────────

    @Given("a TypeScript project snapshot that contains a recognized non-generated source file with an old file path")
    fun snapshotWithSourceFile() {
        fixture = fixture()
    }

    @And("the TypeScript semantic adapter is running with the pinned typescript 5.9.3 compiler server")
    fun adapterRunning() {
        val f = fixture!!
        val adapter = TypeScriptSemanticAdapter("typescript", f.toolchain, f.model)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(f.snapshot))
        this.adapter = adapter
    }

    @And("the compiler server is clean and available with no unreadable or stale project evidence")
    fun compilerClean() {
        assertTrue(adapter!!.isRunning())
        assertEquals(fixture!!.snapshot.hash, adapter!!.activeSnapshotHash())
    }

    @When("the caller requests a source-file relocation preview for that source file from its old file path to a new file path")
    fun requestRelocation() {
        plan = request()
    }

    @Then("the adapter returns a PREVIEW patch plan for the operation sourceFileRelocation")
    fun previewPlan() {
        val plan = assertPlan()
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals("sourceFileRelocation", plan.operation)
    }

    @And("the adapter sends getEditsForFileRename with the exact old and new file paths to the pinned typescript 5.9.3 compiler server")
    fun sendsExactPaths() {
        val plan = assertPlan()
        val rename = assertIs<FileEdit.Rename>(plan.workspaceEdit.edits.single { it is FileEdit.Rename })
        assertEquals(OLD_PATH, rename.path)
        assertEquals(NEW_PATH, rename.newPath)
        assertTrue(plan.summary.contains(OLD_PATH.toString()))
        assertTrue(plan.summary.contains(NEW_PATH.toString()))
    }

    @And("the plan applies the returned FileRenameEdit as the source-file rename")
    fun appliesRename() {
        val plan = assertPlan()
        assertIs<FileEdit.Rename>(plan.workspaceEdit.edits.single { it is FileEdit.Rename })
    }

    @And("the plan has confidence 1.0")
    fun confidence() = assertEquals(1.0, assertPlan().confidence)

    @And("the plan requires user approval")
    fun approval() = assertTrue(assertPlan().requiresUserApproval)

    @And("the plan risk is LOW")
    fun risk() = assertEquals(RiskLevel.LOW, assertPlan().riskLevel)

    @And("the plan evidence is COMPILER_PROVEN")
    fun evidence() = assertEquals(RefactoringEvidence.COMPILER_PROVEN, assertPlan().evidence)

    @And("the plan carries a WorkspaceEdit with exactly one FileEdit.Rename for the source file")
    fun renameEdit() {
        val plan = assertPlan()
        assertEquals(1, plan.workspaceEdit.edits.count { it is FileEdit.Rename })
        val rename = assertIs<FileEdit.Rename>(plan.workspaceEdit.edits.single { it is FileEdit.Rename })
        assertEquals(OLD_PATH, rename.path)
        assertEquals(NEW_PATH, rename.newPath)
    }

    @And("the plan summary names the old and new file paths")
    fun summaryNamesPaths() {
        val plan = assertPlan()
        assertTrue(plan.summary.contains(OLD_PATH.toString()))
        assertTrue(plan.summary.contains(NEW_PATH.toString()))
    }

    // ── Scenario 2 ────────────────────────────────────────────────────────────

    @Given("a TypeScript project snapshot whose source file is imported or exported by other files in the project")
    fun snapshotWithImporters() {
        val imports = TypeScriptRelocationImportsFixture()
        importsFixture = imports
        fixture = fixture(imports.sources, imports.targetPath)
        adapter = startAdapter(fixture!!)
    }

    @And("the compiler server returns FileRenameEdit updates that rewrite those import and export declarations")
    fun compilerReturnsUpdates() {
        val f = fixture!!
        importsFixture!!.assertReady(f.snapshot, f.model, f.toolchain)
    }

    @When("the caller requests a source-file relocation preview for that source file")
    fun requestRelocation2() {
        plan = request()
    }

    @And("the plan rewrites every import and export declaration across the project that the compiler proved must change")
    fun rewritesImports() {
        val f = fixture!!
        importsFixture!!.assertRewrites(assertPlan(), f.snapshot, f.model, f.toolchain)
    }

    @And("the plan affected-file set lists the renamed source file and each import or export updated file")
    fun affectedFiles() = importsFixture!!.assertAffectedFiles(assertPlan())

    @And("the plan carries one FileEdit.Modify per import or export updated file")
    fun oneModifyPerFile() = importsFixture!!.assertOneModifyPerFile(assertPlan())

    @And("each FileEdit.Modify carries exactly the compiler-returned text edits")
    fun exactTextEdits() = importsFixture!!.assertExactEdits(assertPlan())

    @And("the plan does not modify files whose import or export declarations the compiler proved unchanged")
    fun noUnchangedFiles() = importsFixture!!.assertUnchangedFiles(assertPlan(), fixture!!.root)

    // ── Scenario 3 ────────────────────────────────────────────────────────────

    @Given("^a TypeScript workspace for a source-file relocation preview under the condition (.+)$")
    fun condition(condition: String) {
        when (condition) {
            "the old file path is absent from the project snapshot" -> {
                val f = fixture()
                val withoutSource = f.snapshot.copy(files = f.snapshot.files.filterNot { it.path == OLD_PATH })
                fixture = f.copy(snapshot = withoutSource)
                adapter = startAdapter(fixture!!)
            }
            "the semantic adapter has no active compiler-server session" -> {
                fixture = fixture()
                adapter = TypeScriptSemanticAdapter(
                    "typescript", fixture!!.toolchain, fixture!!.model,
                )
            }
            "the new file path already exists in the project snapshot" -> {
                val f = fixture()
                val withTarget = f.snapshot.copy(files = f.snapshot.files + SourceFile(
                    NEW_PATH, "export const Existing = 1;\n", "typescript",
                ))
                fixture = f.copy(snapshot = withTarget)
                adapter = startAdapter(fixture!!)
            }
            "the compiler server cannot be reached or its project evidence is stale or unclean" -> {
                val f = fixture()
                fixture = f
                adapter = TypeScriptSemanticAdapter(
                    "typescript", f.toolchain, f.model, CompilerUnavailableClient(),
                )
                assertIs<TypeScriptSemanticStart.Started>(adapter!!.start(f.snapshot))
            }
            else -> error("Unknown relocation refusal condition: $condition")
        }
    }

    @When("the caller requests a source-file relocation preview")
    fun requestRefused() {
        plan = request()
    }

    @Then("the adapter returns a REFUSED patch plan for the operation sourceFileRelocation")
    fun refusedPlan() {
        val plan = assertPlan()
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertEquals("sourceFileRelocation", plan.operation)
    }

    @And("the refusal carries an empty WorkspaceEdit and an empty affected-file set")
    fun emptyEdit() {
        val plan = assertPlan()
        assertEquals(WorkspaceEdit(), plan.workspaceEdit)
        assertEquals(emptySet<Path>(), plan.affectedFiles)
    }

    @And("the refusal grants no approval and no managed-write eligibility")
    fun noApproval() {
        val plan = assertPlan()
        assertTrue(!plan.requiresUserApproval)
    }

    @And("the relocation refusal claims no compiler-proven evidence or authority lease")
    fun refusedEvidence() {
        assertTrue(assertPlan().evidence != RefactoringEvidence.COMPILER_PROVEN, "Refusal without compiler proof: ${assertPlan().evidence}")
        assertEquals(null, assertPlan().authorityLease)
    }

    @And("the refusal carries confidence 0.0 and risk HIGH")
    fun confidenceRisk() {
        val plan = assertPlan()
        assertEquals(0.0, plan.confidence)
        assertEquals(RiskLevel.HIGH, plan.riskLevel)
    }

    @And("the refusal summary and warning carry the message {string}")
    fun message(message: String) {
        val plan = assertPlan()
        assertEquals(message, plan.summary)
        assertTrue(plan.warnings.contains(message))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertPlan(): PatchPlan {
        val plan = this.plan
        if (plan == null) error("no patch plan was produced")
        return plan
    }

    private fun request(): PatchPlan {
        val adapter = this.adapter ?: error("no adapter")
        val snapshot = fixture!!.snapshot
        return adapter.applyRefactoring(RefactoringRequest(
            operation = "sourceFileRelocation",
            arguments = mapOf(
                "file" to OLD_PATH.toString(),
                "targetFile" to fixture!!.targetPath.toString(),
            ),
            snapshot = snapshot,
        ))
    }

    private fun startAdapter(f: Fixture): TypeScriptSemanticAdapter {
        val adapter = TypeScriptSemanticAdapter("typescript", f.toolchain, f.model)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(f.snapshot))
        return adapter
    }

    private fun fixture(
        sources: Map<Path, String> = linkedMapOf(
            OLD_PATH to "export const A = 1;\n",
            Path.of("src/b.ts") to "import { A } from './a';\nconsole.log(A);\n",
        ),
        targetPath: Path = NEW_PATH,
    ): Fixture {
        val root = Files.createTempDirectory("refactorkit-ts-relocation")
        sources.forEach { (path, content) ->
            Files.createDirectories(root.resolve(path).parent)
            root.resolve(path).writeText(content)
        }
        root.resolve("tsconfig.json").writeText(
            """{"compilerOptions":{"rootDir":"src"},"include":["src/**/*.ts"]}""",
        )
        root.resolve("package.json").writeText("""{"type":"module"}""")
        val model = TypeScriptProjectModelBuilder().build(root)
        val base = ProjectSnapshot(
            Workspace(root), modules = emptyList(),
            files = sources.map { (path, content) -> SourceFile(path, content, "typescript") },
        )
        val snapshot = TypeScriptBuildModelIntegration.attach(base)
        return Fixture(root, model, snapshot, realToolchain(root), targetPath)
    }

    private fun realToolchain(workspaceRoot: Path): TypeScriptSemanticToolchain {
        val repoRoot = locateRepositoryRoot()
        val result = TypeScriptToolchainDiscoverer().discover(TypeScriptToolchainRequest(
            workspaceRoot = workspaceRoot,
            nodeExecutable = findNode(),
            languageServerPackageRoot = repoRoot.resolve("qualification/typescript-toolchain/node_modules/typescript-language-server"),
            typeScriptPackageRoot = repoRoot.resolve("qualification/typescript-toolchain/node_modules/typescript"),
        ))
        return assertIs<TypeScriptToolchainDiscovery.Available>(result).toolchain.also {
            assertEquals("5.9.3", it.provenance.typeScriptVersion)
        }
    }

    private fun findNode(): Path {
        val names = listOf("node")
        val resolved = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
            .asSequence().filter(String::isNotBlank)
            .flatMap { directory -> names.asSequence().map { Path.of(directory).resolve(it) } }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: error("Cannot locate a Node executable on PATH")
        return resolved
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-typescript"))
            ) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root")
    }

    private fun cleanup() {
        adapter?.close()
        adapter = null
        plan = null
        fixture?.root?.let { runCatching { it.toFile().deleteRecursively() } }
        fixture = null
        importsFixture = null
    }

    private data class Fixture(
        val root: Path,
        val model: TypeScriptProjectModel,
        val snapshot: ProjectSnapshot,
        val toolchain: TypeScriptSemanticToolchain,
        val targetPath: Path,
    )

    companion object {
        val OLD_PATH: Path = Path.of("src/a.ts")
        val NEW_PATH: Path = Path.of("src/renamed.ts")
    }
}

/**
 * Minimal client seam that advertises a running compiler-server session but
 * refuses every getEditsForFileRename delegation, so the planner's
 * compiler-unavailable refusal is exercised deterministically.
 */
private class CompilerUnavailableClient : TypeScriptSemanticClient {
    override var isRunning: Boolean = false
    override val provenance: ExternalSemanticSessionProvenance? get() = null

    override fun start(snapshot: ProjectSnapshot) {
        isRunning = true
    }

    override fun supports(capability: String): Boolean = capability in REQUIRED_CAPABILITIES
    override fun buildSymbols(snapshot: ProjectSnapshot): SymbolIndex = SymbolIndex(emptyList())
    override fun searchWorkspaceSymbols(query: String): List<Symbol> = emptyList()
    override fun resolveSymbol(location: SourceLocation): SymbolResolution = SymbolResolution(null)
    override fun findReferences(symbolId: SymbolId): List<Reference> = emptyList()
    override fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = emptyList()
    override fun synchronizedDiagnostics(snapshot: ProjectSnapshot): ExternalSemanticDiagnostics =
        ExternalSemanticDiagnostics.Available(emptyList())
    override fun requestRename(
        snapshot: ProjectSnapshot,
        location: SourceLocation,
        newName: String,
    ): ExternalWorkspaceEditNormalization = ExternalWorkspaceEditNormalization.Refused(
        listOf(Diagnostic("test stub", Diagnostic.Severity.ERROR, code = "externalEdit.testStub")),
    )
    override fun requestWorkspaceEdit(
        paramsJson: String,
        snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer,
    ): ExternalWorkspaceEditNormalization = ExternalWorkspaceEditNormalization.Refused(
        listOf(Diagnostic("test stub", Diagnostic.Severity.ERROR, code = "externalEdit.testStub")),
    )
    override fun requestFileRenameEdit(
        oldFilePath: Path,
        newFilePath: Path,
        snapshot: ProjectSnapshot,
        normalizer: ExternalWorkspaceEditNormalizer,
    ): ExternalWorkspaceEditNormalization = ExternalWorkspaceEditNormalization.Refused(
        listOf(Diagnostic(
            message = "TypeScript compiler server is unavailable",
            severity = Diagnostic.Severity.ERROR,
            code = "typescript.compilerServerUnavailable",
        )),
    )
    override fun close() {
        isRunning = false
    }

    companion object {
        private val REQUIRED_CAPABILITIES = setOf(
            "definitionProvider", "referencesProvider", "renameProvider", "prepareRenameProvider",
            "documentSymbolProvider", "workspaceSymbolProvider", "textDocumentSync",
        )
    }
}

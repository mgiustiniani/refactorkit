package org.refactorkit.typescript.relocation

import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import org.refactorkit.typescript.TypeScriptBuildModelIntegration
import org.refactorkit.typescript.TypeScriptCompilerDiagnostics
import org.refactorkit.typescript.TypeScriptConfigPattern
import org.refactorkit.typescript.TypeScriptProjectModel
import org.refactorkit.typescript.TypeScriptProjectModelBuilder
import org.refactorkit.typescript.TypeScriptProjectModelStatus
import org.refactorkit.typescript.ManagedNodeVersionProbe
import org.refactorkit.typescript.NodeVersionProbe
import org.refactorkit.typescript.ExternalTypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptSemanticStart
import org.refactorkit.typescript.TypeScriptToolchainDiscoverer
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Small authored reference graph; compiler inputs are borrowed, and no build code is executed. */
internal class TypeScriptProjectMigrationFixture : AutoCloseable {
    private val root = Files.createTempDirectory("rk-ts-project-migration-")
    private val texts = linkedMapOf(
        "package.json" to """{"name":"reference-migration-fixture","private":true,"scripts":{"build":"node -e \"require('fs').writeFileSync('BUILD_EXECUTED','forbidden')\""}}""",
        "tsconfig.json" to """
            {
              // Keep this comment and control string: 🧭 ./packages/lib
              "files": [],
              "references": [{"path":"./packages/lib"}, {"path":"./packages/app"}]
            }
        """.trimIndent(),
        "packages/lib/tsconfig.json" to """
            {"compilerOptions":{"strict":true,"composite":true,"declaration":true,"rootDir":".","outDir":"../../dist/lib","target":"ES2020","module":"commonjs"},"include":["*.ts"]}
        """.trimIndent(),
        "packages/lib/library.ts" to "export function twice(input: number): number { return input * 2; }\n",
        "packages/app/tsconfig.json" to """
            {"compilerOptions":{"strict":true,"composite":true,"incremental":true,"tsBuildInfoFile":"../../state/app.tsbuildinfo","declaration":true,"rootDir":".","outDir":"../../dist/app","target":"ES2020","module":"commonjs"},"references":[{"path":"../lib"}],"include":["*.ts"]}
        """.trimIndent(),
        "packages/app/app.ts" to "import { twice } from '../lib/library';\nexport const result = twice(3) + 1;\n",
    )
    init {
        try {
            texts.forEach { (path, text) ->
                Files.createDirectories(root.resolve(path).parent)
                Files.writeString(root.resolve(path), text)
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    // Declarative model/refusal checks do not need Node or a semantic toolchain.
    private val toolchain by lazy {
        var repo = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(repo.resolve("settings.gradle.kts"))) repo = requireNotNull(repo.parent)
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").split(System.getProperty("path.separator"))
            .map { Path.of(it).resolve(executable) }.first { Files.isRegularFile(it) && Files.isExecutable(it) }
        var probeFailure: Throwable? = null
        val probe = ManagedNodeVersionProbe()
        val discovery = TypeScriptToolchainDiscoverer(nodeVersionProbe = NodeVersionProbe { path ->
            probe.probe(path).onFailure { probeFailure = it }
        }).discover(TypeScriptToolchainRequest(root, nodeExecutable = node,
            typeScriptPackageRoot = repo.resolve("qualification/typescript-toolchain/node_modules/typescript"),
            languageServerPackageRoot = repo.resolve("qualification/typescript-toolchain/node_modules/typescript-language-server")))
        assertIs<TypeScriptToolchainDiscovery.Available>(discovery,
            "$discovery; interrupted=${Thread.currentThread().isInterrupted}; probe=${probeFailure?.stackTraceToString()}").toolchain
    }
    private var result: ExternalSemanticDiagnostics? = null
    private var baseline = image()
    private var staged: ProjectSnapshot? = null
    private var stagedModel: TypeScriptProjectModel? = null
    private var migrationAdapter: TypeScriptSemanticAdapter? = null
    private lateinit var migrationSnapshot: ProjectSnapshot
    private lateinit var migrationPlan: PatchPlan
    private var nativeDirectoryProposal: WorkspaceEdit? = null
    private var refusalImage: Map<String, String>? = null
    private var refusedApply: ApplyResult? = null
    private var applyRoot: Path? = null

    fun previewMigration(fault: String? = null, auxiliary: Boolean = false, capture: Boolean = false) {
        when (fault) {
            "duplicate reference origins" -> changeText("packages/app/tsconfig.json", texts.getValue("packages/app/tsconfig.json")
                .replace("\"path\":\"../lib\"", "\"path\":\"../absent\",\"path\":\"../lib\""))
            "an uncaptured project file" -> Files.writeString(root.resolve("packages/lib/notes.txt"), "Do not drop me")
            "an existing destination" -> Files.createDirectories(root.resolve("packages/domain"))
            "path aliases needing other authority" -> changeText("packages/app/tsconfig.json", texts.getValue("packages/app/tsconfig.json")
                .replace("\"strict\":true", "\"strict\":true,\"paths\":{\"@library\":[\"../lib/library.ts\"]}"))
            "unchecked JavaScript" -> {
                changeText("packages/lib/unchecked.js", "export const value = 1;\n")
                changeText("packages/lib/tsconfig.json", texts.getValue("packages/lib/tsconfig.json")
                    .replace("\"strict\":true", "\"strict\":true,\"allowJs\":true,\"checkJs\":false")
                    .replace("\"*.ts\"", "\"*.ts\",\"*.js\""))
            }
            null, "configuration changed after start", "a corrupted compiler proposal" -> Unit
            else -> error("Unknown project migration fault: $fault")
        }
        val inputs = ProjectSnapshot(Workspace(root), emptyList(), texts.map { (p, t) ->
            SourceFile(Path.of(p), t, when { p.endsWith(".ts") -> "typescript"; p.endsWith(".js") -> "javascript"; else -> "jsonc" })
        })
        migrationSnapshot = TypeScriptBuildModelIntegration.attach(if (capture) inputs.copy(
            files = inputs.files.filter { it.languageId == "typescript" }, sourceExtensions = setOf("ts")) else inputs)
        if (capture) assertEquals(texts.keys.map(Path::of).toSet(), migrationSnapshot.trackedFiles.map { it.path }.toSet())
        if (auxiliary) migrationSnapshot = migrationSnapshot.copy(
            files = migrationSnapshot.files.filter { it.languageId == "typescript" },
            auxiliaryFiles = migrationSnapshot.files.filter { it.languageId != "typescript" }, sourceExtensions = setOf("ts"))
        val model = TypeScriptProjectModelBuilder().build(root)
        if (auxiliary) assertEquals(model.projectionHash, TypeScriptProjectModelBuilder().build(migrationSnapshot).projectionHash)
        val realClient = ExternalTypeScriptSemanticClient("typescript", toolchain, model)
        val client = object : TypeScriptSemanticClient by realClient {
            override fun requestProjectDirectoryEdit(from: Path, to: Path, snapshot: ProjectSnapshot): ExternalWorkspaceEditNormalization {
                val answer = realClient.requestProjectDirectoryEdit(from, to, snapshot)
                if (answer is ExternalWorkspaceEditNormalization.Accepted) nativeDirectoryProposal = answer.normalized.workspaceEdit
                if (fault != "a corrupted compiler proposal") return answer
                val accepted = assertIs<ExternalWorkspaceEditNormalization.Accepted>(answer)
                return accepted.copy(normalized = accepted.normalized.copy(workspaceEdit = accepted.normalized.workspaceEdit.copy(
                    edits = accepted.normalized.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>())))
            }
        }
        val adapter = TypeScriptSemanticAdapter("typescript", toolchain, model, client)
        migrationAdapter = adapter
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(migrationSnapshot))
        if (auxiliary) {
            val exact = assertIs<ExternalSemanticDiagnostics.Available>(adapter.exactDiagnostics(migrationSnapshot))
            assertTrue(exact.diagnostics.isEmpty(), exact.toString())
        }
        if (fault == "configuration changed after start") Files.writeString(root.resolve("tsconfig.json"), texts.getValue("tsconfig.json") + "\n")
        if (fault != null) refusalImage = image()
        migrationPlan = adapter.applyRefactoring(RefactoringRequest("projectReferenceMigration", arguments = mapOf(
            "fromDirectory" to "packages/lib", "toDirectory" to "packages/domain",
        ), snapshot = migrationSnapshot))
    }

    private fun changeText(path: String, text: String) { texts[path] = text; Files.writeString(root.resolve(path), text) }

    fun assertMigrationRefused(code: String) {
        assertEquals(PatchStatus.REFUSED, migrationPlan.status, migrationPlan.toString())
        assertEquals(code, migrationPlan.refusalCode, migrationPlan.toString())
        assertTrue(migrationPlan.workspaceEdit.edits.isEmpty())
        assertTrue(migrationPlan.affectedFiles.isEmpty())
        assertFalse(migrationPlan.requiresUserApproval)
        assertEquals(0.0, migrationPlan.confidence)
        assertEquals(null, migrationPlan.authorityLease)
        assertEquals(0, transactionCount())
        assertEquals(requireNotNull(refusalImage), image())
    }

    fun applyWithChangedAuthority(change: String) {
        assertEquals(PatchStatus.PREVIEW, migrationPlan.status, migrationPlan.toString())
        var candidate = migrationSnapshot
        var plan = migrationPlan
        val gate = requireNotNull(migrationAdapter).diagnosticsGate()
        when (change) {
            "configuration drift" -> Files.writeString(root.resolve("tsconfig.json"), texts.getValue("tsconfig.json") + "\n")
            "closing the semantic session" -> requireNotNull(migrationAdapter).close()
            "modifying the retained edit" -> {
                val edit = plan.workspaceEdit.copy(edits = plan.workspaceEdit.edits + FileEdit.Create(Path.of("extra.ts"), "export const extra = 1;"))
                plan = plan.copy(workspaceEdit = edit, affectedFiles = edit.affectedFiles())
            }
            "selecting another workspace" -> {
                val other = Files.createDirectories(root.resolve("other-workspace"))
                texts.forEach { (p, t) -> Files.createDirectories(other.resolve(p).parent); Files.writeString(other.resolve(p), t) }
                candidate = candidate.copy(workspace = Workspace(other))
                assertEquals(migrationSnapshot.hash, candidate.hash, "The gate must also bind the owning root, not only byte hashes")
            }
            else -> error("Unknown authority change: $change")
        }
        applyRoot = candidate.workspace.root
        refusalImage = nonTransactionImage()
        refusedApply = PatchEngine(candidate.workspace.root).apply(plan, candidate, ApplyAuthorization.explicit("migration-story"), gate)
    }

    fun assertApplyRefused() {
        val refusal = assertIs<ApplyResult.Refused>(refusedApply, refusedApply.toString())
        assertTrue(refusal.diagnostics.any { it.severity == Diagnostic.Severity.ERROR })
        assertEquals(requireNotNull(refusalImage), nonTransactionImage())
        val transactions = requireNotNull(applyRoot).resolve(".refactorkit/transactions")
        if (Files.exists(transactions)) Files.list(transactions).use { paths -> assertFalse(paths.anyMatch { it.toString().endsWith(".json") }) }
    }

    private fun nonTransactionImage() = image().filterKeys { ".refactorkit" !in Path.of(it).map(Path::toString) }

    fun assertMigration() {
        assertEquals(PatchStatus.PREVIEW, migrationPlan.status, migrationPlan.toString())
        val lease = assertNotNull(migrationPlan.authorityLease)
        assertEquals("projectReferenceMigration", lease.operation)
        assertEquals("getEditsForFileRename", lease.attributes["command"])
        assertEquals("jsonc-reference-path-v1", lease.attributes["configurationOrigin"])
        assertTrue(lease.attributes.getValue("compilerEditsSha256").matches(Regex("[a-f0-9]{64}")))
        assertTrue(lease.attributes.getValue("configurationEditsSha256").matches(Regex("[a-f0-9]{64}")))
        assertTrue(lease.attributes["modelProjectionHash"] != lease.attributes["stagedModelProjectionHash"])
        assertEquals(5, migrationPlan.workspaceEdit.edits.size)
        assertEquals(setOf(FileEdit.Rename(Path.of("packages/lib/library.ts"), Path.of("packages/domain/library.ts")),
            FileEdit.Rename(Path.of("packages/lib/tsconfig.json"), Path.of("packages/domain/tsconfig.json"))),
            migrationPlan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().toSet())
        fun edit(line: Int, start: Int, end: Int, text: String) =
            TextEdit(SourceRange(SourcePosition(line, start), SourcePosition(line, end)), text)
        val expectedModifications = mapOf(
            Path.of("tsconfig.json") to listOf(edit(3, 25, 41, "\"./packages/domain\"")),
            Path.of("packages/app/tsconfig.json") to listOf(edit(0, 238, 246, "\"../domain\"")),
            Path.of("packages/app/app.ts") to listOf(edit(0, 23, 37, "../domain/library")),
        )
        val modifications = migrationPlan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(3, modifications.size, "exactly one native source and two independently located JSONC modifications")
        assertEquals(expectedModifications, modifications.associate { it.path to it.textEdits }, "authored edit paths, UTF-16 ranges and replacement text")
        assertEquals(expectedModifications.keys + setOf(Path.of("packages/lib/library.ts"), Path.of("packages/lib/tsconfig.json"),
            Path.of("packages/domain/library.ts"), Path.of("packages/domain/tsconfig.json")), migrationPlan.affectedFiles)
        val beforeModel = TypeScriptProjectModelBuilder().build(migrationSnapshot)
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, beforeModel.status)
        assertEquals(setOf(Path.of("tsconfig.json"), Path.of("packages/lib/tsconfig.json"), Path.of("packages/app/tsconfig.json")),
            beforeModel.projects.map { it.configPath }.toSet())
        assertEquals(listOf(Path.of("packages/lib/tsconfig.json")),
            beforeModel.projects.single { it.configPath == Path.of("packages/app/tsconfig.json") }.references)
        assertEquals(beforeModel.projectionHash, lease.attributes["modelProjectionHash"])
        checkDiagnostics()
        assertClean()
        assertEquals(emptyList(), migrationPlan.diagnosticsBefore, "authored baseline diagnostics are empty, not merely error-free")
        modelStagedGraph()
        val candidate = WorkspaceEditSimulator.apply(migrationSnapshot, migrationPlan.workspaceEdit)
        assertEquals(requireNotNull(staged).trackedFiles.associate { it.path to it.content }, candidate.trackedFiles.associate { it.path to it.content })
        assertEquals(candidate.hash, lease.attributes["stagedSnapshotHash"])
        val candidateModel = TypeScriptProjectModelBuilder().build(candidate)
        assertEquals(requireNotNull(stagedModel).projects, candidateModel.projects, "actual staged graph equals the independently authored graph")
        assertEquals(candidateModel.projectionHash, lease.attributes["stagedModelProjectionHash"])
        staged = candidate
        stagedModel = candidateModel
        assertStagedGraph()
        assertEquals(emptyList(), migrationPlan.diagnosticsAfterPreview, "authored staged diagnostics agree with the independent compiler run")
        assertUnchanged()
    }

    fun assertNativeConfigurationExcluded() {
        val native = assertNotNull(nativeDirectoryProposal, "Capture the real unmodified native exchange, not a synthesized proposal")
        val configPaths = setOf(Path.of("tsconfig.json"), Path.of("packages/lib/tsconfig.json"), Path.of("packages/app/tsconfig.json"))
        val sourcePaths = setOf(Path.of("packages/lib/library.ts"), Path.of("packages/app/app.ts"))
        val configs = native.edits.filterIsInstance<FileEdit.Modify>().filter { it.path in configPaths }
        val sources = native.edits.filterIsInstance<FileEdit.Modify>().filter { it.path in sourcePaths }
        assertTrue(configs.isNotEmpty(), "The pinned real compiler must emit a configuration counterexample: $native")
        assertTrue(sources.isNotEmpty(), "The configuration counterexample must retain real source edits")
        assertEquals(sources, migrationPlan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().filter { it.path in sourcePaths })
        val attributes = assertNotNull(migrationPlan.authorityLease).attributes
        assertEquals(WorkspaceEditIdentity.sha256(native), attributes["compilerEditsSha256"])
        assertEquals(WorkspaceEditIdentity.sha256(WorkspaceEdit(configs)), attributes["compilerConfigurationProposalSha256"])
        assertEquals(WorkspaceEditIdentity.sha256(WorkspaceEdit(sources)), attributes["compilerSourceEditsSha256"])
        // Replace only the proposed config paths; keep the real source edits and proven
        // incoming origins on all other paths, so a missing reference rewrite cannot be
        // mistaken for a bad native configuration suggestion.
        val suggestedPaths = configs.map { it.path }.toSet()
        val modifications = migrationPlan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().filter { it.path !in suggestedPaths }
        val counterfactual = WorkspaceEditSimulator.apply(migrationSnapshot, WorkspaceEdit(modifications + configs +
            migrationPlan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>()))
        val nativeModel = TypeScriptProjectModelBuilder().build(counterfactual)
        assertTrue(nativeModel.status != TypeScriptProjectModelStatus.AVAILABLE || nativeModel.projects != requireNotNull(stagedModel).projects,
            "Native configuration suggestions unexpectedly satisfy the independently authored moved graph; update the counterexample, not the authority rule")
        val movedConfig = Path.of("packages/domain/tsconfig.json")
        assertEquals(texts.getValue("packages/lib/tsconfig.json"), requireNotNull(staged).trackedFiles.single { it.path == movedConfig }.content)
        println("Native configuration counterexample: configurations=${configs.size}, sources=${sources.size}, counterfactualModel=${nativeModel.status}, nativeConfigHash=${attributes["compilerConfigurationProposalSha256"]}")
    }

    fun applyMigrationAndRollback() {
        val engine = PatchEngine(root)
        val gate = requireNotNull(migrationAdapter).diagnosticsGate()
        val missing = assertIs<ApplyResult.Refused>(engine.apply(migrationPlan, migrationSnapshot, ApplyAuthorization.missing("migration-story"), gate))
        assertTrue(missing.diagnostics.any { it.code == "approval.required" })
        assertEquals(0, transactionCount())
        val applied = assertIs<ApplyResult.Applied>(engine.apply(migrationPlan, migrationSnapshot, ApplyAuthorization.explicit("migration-story"), gate))
        assertEquals(1, transactionCount())
        assertEquals(requireNotNull(staged).trackedFiles.associate { it.path to it.content },
            requireNotNull(staged).trackedFiles.associate { it.path to Files.readString(root.resolve(it.path)) })
        assertIs<ApplyResult.Applied>(engine.rollback(applied.transaction))
        texts.forEach { (p, t) -> assertEquals(t, Files.readString(root.resolve(p))) }
        assertFalse(Files.exists(root.resolve("packages/domain/library.ts")))
        assertFalse(Files.exists(root.resolve("packages/domain/tsconfig.json")))
    }

    fun modelStagedGraph(fault: String? = null) {
        // Authored post-image for the model contract, not a compiler-owned migration proposal.
        staged = ProjectSnapshot(Workspace(root), emptyList(), texts.map { (name, content) ->
            val path = Path.of(name)
            val text = when (name) {
                "tsconfig.json" -> content.replace("\"path\":\"./packages/lib\"", "\"path\":\"./packages/domain\"")
                "packages/app/tsconfig.json" -> content.replace("\"path\":\"../lib\"", "\"path\":\"../domain\"")
                "packages/app/app.ts" -> content.replace("'../lib/library'", "'../domain/library'")
                else -> content
            }
            val moved = if (path.startsWith(Path.of("packages/lib"))) Path.of("packages/domain").resolve(Path.of("packages/lib").relativize(path)) else path
            SourceFile(moved, text, if (name.endsWith(".ts")) "typescript" else "jsonc")
        })
        if (fault != null) {
            val snapshot = requireNotNull(staged)
            staged = snapshot.copy(files = when (fault) {
                "omitted project configuration" -> snapshot.files.filter { it.path != Path.of("packages/domain/tsconfig.json") }
                else -> snapshot.files.map { file ->
                    if (file.path != Path.of("packages/app/tsconfig.json")) file else file.copy(content = when (fault) {
                        "a project-reference cycle" -> file.content.replace("\"path\":\"../domain\"", "\"path\":\"../..\"")
                        "an escaping reference" -> file.content.replace("\"path\":\"../domain\"", "\"path\":\"../../../outside\"")
                        "malformed project JSONC" -> "{ broken"
                        else -> error("Unknown snapshot fixture fault: $fault")
                    })
                }
            })
        }
        stagedModel = TypeScriptProjectModelBuilder().build(requireNotNull(staged))
    }

    fun assertModelRefused(code: String) {
        val model = requireNotNull(stagedModel)
        assertEquals(TypeScriptProjectModelStatus.REFUSED, model.status)
        assertEquals(emptyList(), model.projects)
        assertTrue(model.diagnostics.isNotEmpty())
        assertTrue(model.diagnostics.all { it.code == code }, model.diagnostics.toString())
    }

    fun assertStagedGraph() {
        val snapshot = requireNotNull(staged)
        val model = requireNotNull(stagedModel)
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        assertEquals(setOf(Path.of("tsconfig.json"), Path.of("packages/domain/tsconfig.json"), Path.of("packages/app/tsconfig.json")),
            model.projects.map { it.configPath }.toSet())
        assertEquals(listOf(Path.of("packages/domain/tsconfig.json")),
            model.projects.single { it.configPath == Path.of("packages/app/tsconfig.json") }.references)
        assertEquals(setOf(Path.of("packages/domain/tsconfig.json"), Path.of("packages/app/tsconfig.json")),
            model.projects.single { it.configPath == Path.of("tsconfig.json") }.references.toSet())
        val library = model.projects.single { it.configPath == Path.of("packages/domain/tsconfig.json") }
        assertEquals(Path.of("packages/domain"), library.compilerOptions.rootDirectory)
        assertEquals(Path.of("dist/lib"), library.compilerOptions.outputDirectory)
        assertEquals(true, library.compilerOptions.composite)
        assertEquals(true, library.compilerOptions.declaration)
        assertEquals(listOf(TypeScriptConfigPattern(Path.of("packages/domain"), "*.ts")), library.include)
        assertEquals(emptyList(), library.exclude)
        val files = snapshot.trackedFiles.associateBy { it.path }
        model.evidence.forEach { evidence ->
            val content = files.getValue(evidence.path).content.toByteArray()
            assertEquals(content.size.toLong(), evidence.size)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, evidence.sha256)
        }
        assertTrue(model.projectionHash != TypeScriptProjectModelBuilder().build(root).projectionHash)
        assertEquals(model.projectionHash, TypeScriptProjectModelBuilder().build(snapshot).projectionHash)
        assertTrue(files.getValue(Path.of("tsconfig.json")).content.contains("🧭 ./packages/lib"))
        result = TypeScriptCompilerDiagnostics(toolchain, model).analyze(snapshot, emptyList())
        assertClean()
    }

    fun inputFault(fault: String) {
        val (path, before, after) = when (fault) {
            "explicitly disabled incremental" -> Triple("packages/app/tsconfig.json", "\"incremental\":true", "\"incremental\":false")
            "explicitly disabled source redirect" -> Triple("packages/app/tsconfig.json", "\"strict\":true", "\"strict\":true,\"disableSourceOfProjectReferenceRedirect\":true")
            "a type error in a referenced source" -> Triple("packages/lib/library.ts", "return input * 2", "return 'wrong type'")
            "an unresolved consumer import" -> Triple("packages/app/app.ts", "../lib/library", "../lib/absent")
            else -> error("Unknown reference-graph fixture fault: $fault")
        }
        val original = texts.getValue(path)
        check(original.indexOf(before) >= 0 && original.indexOf(before) == original.lastIndexOf(before))
        texts[path] = original.replace(before, after)
        Files.writeString(root.resolve(path), texts.getValue(path))
        baseline = image()
    }

    fun assertErrors(code: String) {
        val diagnostics = assertIs<ExternalSemanticDiagnostics.Available>(result).diagnostics
        assertTrue(diagnostics.isNotEmpty())
        assertTrue(diagnostics.all { it.code == code && it.severity == Diagnostic.Severity.ERROR }, diagnostics.toString())
    }

    fun checkDiagnostics() {
        val model = TypeScriptProjectModelBuilder().build(root)
        assertEquals(TypeScriptProjectModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        assertEquals(3, model.projects.size)
        assertEquals(2, model.projects.count { it.compilerOptions.composite == true })
        val snapshot = TypeScriptBuildModelIntegration.attach(ProjectSnapshot(Workspace(root), emptyList(), texts.map { (path, text) ->
            SourceFile(Path.of(path), text, if (path.endsWith(".ts")) "typescript" else "jsonc")
        }))
        result = TypeScriptCompilerDiagnostics(toolchain, model).analyze(snapshot, emptyList())
    }

    fun assertClean() {
        val available = assertIs<ExternalSemanticDiagnostics.Available>(result)
        assertEquals(emptyList(), available.diagnostics, "The unbuilt reference graph must be checked from its sources without disabling incremental/composite options")
    }

    fun assertUnchanged() {
        assertEquals(baseline, image(), "Diagnostics must neither emit build files nor execute package scripts")
        assertFalse(Files.exists(root.resolve(".refactorkit")))
        assertFalse(Files.exists(root.resolve("dist")))
        assertFalse(Files.exists(root.resolve("state")))
        assertFalse(Files.exists(root.resolve("BUILD_EXECUTED")))
    }

    private fun transactionCount(): Int {
        val directory = root.resolve(".refactorkit/transactions")
        return if (!Files.exists(directory)) 0 else Files.list(directory).use { files ->
            files.filter { it.toString().endsWith(".json") }.count().toInt()
        }
    }

    private fun image(): Map<String, String> = Files.walk(root).use { stream ->
        stream.toList().associate { path ->
            assertFalse(Files.isSymbolicLink(path))
            root.relativize(path).toString() to if (Files.isDirectory(path)) "directory" else Files.readString(path)
        }
    }

    override fun close() {
        migrationAdapter?.close()
        if (Files.exists(root)) Files.walk(root).use { stream ->
            stream.toList().sortedByDescending(Path::getNameCount).forEach {
                check(!Files.isSymbolicLink(it))
                Files.delete(it)
            }
        }
    }
}

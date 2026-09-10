package org.refactorkit.typescript.relocation

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.java.recipe.ManagedRecipeContext
import org.refactorkit.java.recipe.RecipeEngine
import org.refactorkit.java.recipe.RecipeLoader
import org.refactorkit.java.recipe.RecipeResult
import org.refactorkit.java.recipe.StepDef
import org.refactorkit.core.CodeSelection
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.LanguageAdapter
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.typescript.ExternalTypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptBuildModelIntegration
import org.refactorkit.typescript.TypeScriptCompilerMutationEvidence
import org.refactorkit.typescript.TypeScriptProjectModelBuilder
import org.refactorkit.typescript.TypeScriptSemanticAdapter
import org.refactorkit.typescript.TypeScriptSemanticClient
import org.refactorkit.typescript.TypeScriptSemanticStart
import org.refactorkit.typescript.TypeScriptToolchainDiscoverer
import org.refactorkit.typescript.TypeScriptToolchainDiscovery
import org.refactorkit.typescript.TypeScriptToolchainRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Scenario-owned real compiler fixture; the broken proposal is negative evidence only. */
internal class TypeScriptAdvancedFixture(private val condition: String) : AutoCloseable {
    private val root = Files.createTempDirectory("rk-ts-authority-")
    private val oracle = TypeScriptRelocationImportsFixture()
    private val language = if (condition in setOf("unchecked JavaScript source", "checked JavaScript source")) "javascript" else "typescript"
    private val extension = if (language == "javascript") "js" else "ts"
    private val old = Path.of("src/a.$extension")
    private val target = Path.of(when (condition) {
        "relocation to text without rootDir", "relocation to text with rootDir" -> "src/a.txt"
        "relocation changes source language" -> "src/a.js"
        "relocation target excluded from compiler" -> "src/ignored/renamed.ts"
        else -> "src/nested/renamed.$extension"
    })
    private val toolchain = run {
        var repo = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(repo.resolve("settings.gradle.kts"))) repo = requireNotNull(repo.parent)
        val nodeName = if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node"
        val node = System.getenv("PATH").split(System.getProperty("path.separator"))
            .map { Path.of(it).resolve(nodeName) }.first { Files.isRegularFile(it) && Files.isExecutable(it) }
        assertIs<TypeScriptToolchainDiscovery.Available>(TypeScriptToolchainDiscoverer().discover(
            TypeScriptToolchainRequest(root, nodeExecutable = node,
                typeScriptPackageRoot = repo.resolve("qualification/typescript-toolchain/node_modules/typescript"),
                languageServerPackageRoot = repo.resolve("qualification/typescript-toolchain/node_modules/typescript-language-server")),
        )).toolchain
    }
    private val snapshot: ProjectSnapshot
    private val model: org.refactorkit.typescript.TypeScriptProjectModel
    private val adapter: TypeScriptSemanticAdapter
    private val baseline: Map<Path, String>
    private lateinit var plan: PatchPlan
    private var expectedOutcome = ""
    private var requestedOperation = "sourceFileRelocation"
    private var selectedRefactor = ""
    private var selectedAction = ""
    private var recipeResult: Result<RecipeResult> = Result.failure(IllegalStateException("No recipe preview was requested"))
    private val recipeChildren = mutableListOf<PatchPlan>()
    private var retainedRecipeGate: DiagnosticsGate? = null
    private var beforeRestart: Pair<PatchPlan, DiagnosticsGate>? = null
    private val selectionCoordinates = when (condition) {
        "a declaration with callers and an existing target" -> listOf(0, 0, 1, 0)
        "an inlineable local variable" -> listOf(1, 8, 1, 15)
        else -> listOf(1, 9, 1, 18)
    }

    init {
        val sourceTexts = if (condition.startsWith("relocation ")) mapOf(
            Path.of("src/a.ts") to "export const value = 2;\n",
            Path.of("src/keep.ts") to "export const keep = 1;\n",
        ) else if (condition == "signature and inline function targets") oracle.sources + mapOf(
            Path.of("src/function.ts") to "export function inc(x: number): number { return x + 1; }\nconst first = inc(1);\n",
        ) else if (condition == "imports with used and unused bindings") oracle.sources + mapOf(
            Path.of("src/imports.ts") to "import { zebra, unused, alpha } from './dep';\nexport const total = zebra + alpha;\n",
            Path.of("src/dep.ts") to "export const value = 1;\nexport const alpha = 1;\nexport const zebra = 2;\nexport const unused = 3;\n",
        ) else if (condition == "an extractable expression") oracle.sources + mapOf(
            Path.of("src/action.ts") to "export function evaluate(input: number): number {\n  return input * 2 + 1;\n}\n",
        ) else if (condition == "an inlineable local variable") oracle.sources + mapOf(
            Path.of("src/action.ts") to "export function evaluate(input: number): number {\n  const doubled = input * 2;\n  return doubled + 1;\n}\n",
        ) else if (condition == "a declaration with callers and an existing target") oracle.sources + mapOf(
            Path.of("src/action.ts") to "export function twice(input: number): number { return input * 2; }\nexport function evaluate(input: number): number { return twice(input) + 1; }\n",
            Path.of("src/target.ts") to "export const stable = 3;\n",
            Path.of("src/consumer.ts") to "import { twice } from './action';\nexport const consumed = twice(3);\n",
        ) else oracle.sources
        val sources = sourceTexts.map { (path, content) ->
            val text = if (condition == "compiler errors in the input snapshot" && path == Path.of("src/a.ts")) {
                "import { value } from './dep';\nexport const A: string = value;\n"
            } else content
            SourceFile(Path.of(path.toString().removeSuffix("ts") + extension), text, language)
        }
        for (file in sources) {
            Files.createDirectories(root.resolve(file.path).parent)
            Files.writeString(root.resolve(file.path), file.content)
        }
        val configuration = when (condition) {
            "relocation to text without rootDir", "relocation changes source language" ->
                """{"compilerOptions":{"strict":true},"include":["src/**/*.ts"]}"""
            "relocation changes explicit files config" ->
                """{"compilerOptions":{"strict":true},"files":["src/a.ts","src/keep.ts"]}"""
            "relocation target excluded from compiler" ->
                """{"compilerOptions":{"strict":true},"include":["src/**/*.ts"],"exclude":["src/ignored"]}"""
            "relocation source excluded from compiler" ->
                """{"compilerOptions":{"strict":true},"include":["src/**/*.ts"],"exclude":["src/a*.ts"]}"""
            else -> """{"compilerOptions":{"strict":true,"rootDir":"src","allowJs":${language == "javascript"},"checkJs":${condition == "checked JavaScript source"}},"include":["src/**/*"]}"""
        }
        Files.writeString(root.resolve("tsconfig.json"), configuration)
        Files.writeString(root.resolve("package.json"), """{"name":"owned-t5-authority-fixture","private":true}""")
        model = TypeScriptProjectModelBuilder().build(root)
        val projectInputs = model.evidence.map { SourceFile(it.path, Files.readString(root.resolve(it.path)), "jsonc") }
        snapshot = TypeScriptBuildModelIntegration.attach(ProjectSnapshot(Workspace(root), emptyList(), sources + projectInputs))
        val real = ExternalTypeScriptSemanticClient(language, toolchain, model)
        val client = if (condition == "a deliberately broken edit proposal") object : TypeScriptSemanticClient by real {
            override fun requestFileRenameEdit(oldFilePath: Path, newFilePath: Path, snapshot: ProjectSnapshot,
                normalizer: ExternalWorkspaceEditNormalizer) = normalizer.normalize(snapshot, ExternalWorkspaceEditProposal(
                "negative-test-proposal", "not-compiler-authority", listOf(ExternalFileEditProposal.Rename(oldFilePath, newFilePath)),
            ))
        } else if (condition == "missing compiler exchange evidence") object : TypeScriptSemanticClient by real {
            override fun compilerMutationEvidence(): TypeScriptCompilerMutationEvidence? = null
        } else real
        adapter = TypeScriptSemanticAdapter(language, toolchain, model, client)
        assertIs<TypeScriptSemanticStart.Started>(adapter.start(snapshot))
        if (condition == "changed project evidence after startup") {
            Files.writeString(root.resolve("tsconfig.json"), """{"compilerOptions":{"strict":false},"include":["src/**/*"]}""")
        }
        if (condition == "disk source differs from the snapshot") Files.writeString(root.resolve("src/b.ts"), "export {};\n")
        baseline = image()
    }

    fun preview() {
        plan = adapter.applyRefactoring(RefactoringRequest("sourceFileRelocation", arguments = mapOf(
            "file" to old.toString(), "targetFile" to target.toString(),
        ), snapshot = snapshot))
    }

    fun crashSemanticChildAndRestart() {
        assertEquals(PatchStatus.PREVIEW, plan.status)
        val original = assertNotNull(adapter.sessionProvenance()).process
        val lease = assertNotNull(plan.authorityLease)
        assertEquals(original.id, lease.attributes["semanticSession"])
        assertTrue(original.id != lease.attributes["compilerProcess"], "the semantic session is not the per-exchange compiler child")
        beforeRestart = plan to adapter.diagnosticsGate()
        val child = ProcessHandle.of(original.pid).orElseThrow { AssertionError("Owned semantic child is absent") }
        val descendants = child.descendants().use { it.toList() }
        try {
            assertTrue(child.isAlive, "must crash the actual live semantic child")
            assertTrue(child.destroyForcibly(), "owned semantic crash injection failed")
            child.onExit().get(5, TimeUnit.SECONDS)
        } finally {
            // Capture ownership before the parent exits; never leave reparented test descendants alive.
            descendants.asReversed().forEach { process ->
                if (process.isAlive) process.destroyForcibly()
                process.onExit().get(5, TimeUnit.SECONDS)
            }
        }
        assertEquals(false, adapter.isRunning())
        val restarted = assertIs<TypeScriptSemanticStart.Started>(adapter.restart(snapshot))
        val current = assertNotNull(restarted.provenance).process
        assertTrue(current.id != original.id, "restart must create a different real semantic session")
        assertTrue(current.pid != original.pid, "restart must launch a new semantic process")
        assertEquals(snapshot.hash, adapter.activeSnapshotHash())
        println("SEMANTIC_RESTART old=${original.id}/${original.pid}/${original.startedAt} new=${current.id}/${current.pid}/${current.startedAt} compiler=${lease.attributes["compilerProcess"]}/${lease.attributes["compilerPid"]}")
        assertEquals(baseline, image())
        assertEquals(0, transactionCount())
    }

    fun verifyPreRestartApplyRefused(code: String) {
        val (oldPlan, oldGate) = requireNotNull(beforeRestart)
        val engine = PatchEngine(root)
        val result = engine.apply(oldPlan, snapshot, ApplyAuthorization.explicit("t5-restart-story"), oldGate)
        val journals = transactionCount()
        println("PRE_RESTART_APPLY result=${result.javaClass.simpleName} journals=$journals unchanged=${baseline == image()}")
        // Preserve rollback even when the genuine RED demonstrates an unauthorized accepted apply.
        if (result is ApplyResult.Applied) assertIs<ApplyResult.Applied>(engine.rollback(result.transaction))
        val refused = assertIs<ApplyResult.Refused>(result, "Old session authority must not survive a real crash/restart")
        assertTrue(refused.diagnostics.any { it.code == code }, refused.toString())
        assertEquals(0, journals, "stale authority must be refused before WAL creation")
        assertEquals(baseline, image(), "stale apply must not modify the workspace")
    }

    fun previewFunctionOperation(operation: String) {
        requestedOperation = operation
        plan = adapter.applyRefactoring(RefactoringRequest(operation, arguments = mapOf(
            "file" to "src/function.ts", "symbolName" to "inc", "newSignature" to "(x: number, step: number)",
            "startLine" to "0", "endLine" to "1", "methodName" to "inc",
        ) + if (operation == "moveSymbol") mapOf("targetFile" to "src/dep.ts") else emptyMap(), snapshot = snapshot))
    }

    fun previewAction(operation: String, refactor: String, action: String) {
        requestedOperation = operation
        selectedRefactor = refactor
        selectedAction = action
        plan = adapter.applyRefactoring(RefactoringRequest(operation, arguments = mapOf(
            "file" to "src/action.ts", "refactor" to refactor, "action" to action,
            "startLine" to selectionCoordinates[0].toString(), "startCharacter" to selectionCoordinates[1].toString(),
            "endLine" to selectionCoordinates[2].toString(), "endCharacter" to selectionCoordinates[3].toString(),
            "indentSize" to "2", "tabSize" to "2", "quotePreference" to "single",
        ) + if (operation == "moveDeclaration") mapOf("targetFile" to "src/target.ts") else emptyMap(), snapshot = snapshot))
    }

    fun verifyAction() {
        expectedOutcome = "PREVIEW"
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(requestedOperation, plan.operation)
        val lease = assertNotNull(plan.authorityLease)
        assertEquals("getEditsForRefactor", lease.attributes["command"])
        assertEquals(selectedRefactor, lease.attributes["refactor"])
        assertEquals(selectedAction, lease.attributes["action"])
        assertEquals(selectionCoordinates[0].toString(), lease.attributes["startLine"])
        assertEquals(selectionCoordinates[1].toString(), lease.attributes["startCharacter"])
        assertEquals(selectionCoordinates[2].toString(), lease.attributes["endLine"])
        assertEquals(selectionCoordinates[3].toString(), lease.attributes["endCharacter"])
        if (requestedOperation == "moveDeclaration") assertEquals("src/target.ts", lease.attributes["targetFile"])
        assertTrue(lease.attributes.getValue("returnedActionSha256").matches(Regex("[a-f0-9]{64}")))
        assertTrue((plan.diagnosticsBefore + plan.diagnosticsAfterPreview).none { it.severity == Diagnostic.Severity.ERROR })
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        val affected = if (requestedOperation == "moveDeclaration") setOf(Path.of("src/action.ts"), Path.of("src/target.ts"), Path.of("src/consumer.ts"))
            else setOf(Path.of("src/action.ts"))
        assertEquals(affected, plan.affectedFiles)
        assertEquals(snapshot.files.filter { it.path !in affected }.associate { it.path to it.content },
            staged.files.filter { it.path !in affected }.associate { it.path to it.content })
        assertEquals("[-5,1,5]", evaluate(snapshot))
        assertEquals("[-5,1,5]", evaluate(staged))
    }

    /** Executes only authored arithmetic fixture modules in an isolated VM, never project build code. */
    private fun evaluate(value: ProjectSnapshot): String {
        val sources = buildJsonObject {
            value.files.filter { it.languageId == "typescript" }.forEach { put(it.path.toString().replace('\\', '/'), it.content) }
        }.toString()
        val script = """
            const ts = require(process.argv[1]);
            const vm = require('node:vm');
            const path = require('node:path').posix;
            const files = JSON.parse(require('node:fs').readFileSync(0, 'utf8'));
            const modules = Object.create(null);
            function load(file) {
                if (modules[file]) return modules[file];
                if (!Object.hasOwn(files, file)) throw new Error('No external fixture modules');
                const exports = modules[file] = {};
                const code = ts.transpileModule(files[file], {compilerOptions: {module: ts.ModuleKind.CommonJS}}).outputText;
                vm.runInNewContext(code, {exports, require: name => {
                    if (!name.startsWith('./') && !name.startsWith('../')) throw new Error('No external fixture modules');
                    return load(path.normalize(path.join(path.dirname(file), name)) + '.ts');
                }}, {timeout: 1000});
                return exports;
            }
            process.stdout.write(JSON.stringify([-3, 0, 2].map(load('src/action.ts').evaluate)));
        """.trimIndent()
        return ExternalSemanticProcessManager().use { manager ->
            val process = manager.launch(SemanticProcessSpec("owned-arithmetic-oracle", toolchain.nodeExecutable,
                listOf("-e", script, toolchain.typeScriptCompilerEntrypoint.toString()), root))
            process.input.bufferedWriter().use { it.write(sources) }
            assertTrue(process.awaitExit(15_000), "Arithmetic fixture exceeded its watchdog")
            assertEquals(0, process.exitCode, process.stderrText())
            process.output.bufferedReader().readText()
        }
    }

    fun previewRecipe(fault: String? = null) {
        val original = RecipeLoader.load(Path.of("../../recipes/typescript/relocate-source.yml")).copy(language = language)
        val recipe = when (fault) {
            "a second mutation" -> original.copy(steps = original.steps + original.steps.first())
            "an external command step" -> original.copy(steps = listOf(StepDef("executeCommand", mapOf("command" to "must-not-run"))))
            else -> original
        }
        val candidate = if (fault == "a stale semantic snapshot") snapshot.copy(files = snapshot.files.map {
            if (it.path == old) it.copy(content = it.content + "\n") else it
        }) else snapshot
        val gate = when (fault) {
            "a disabled diagnostics gate" -> DiagnosticsGate.disabled("typescript-compiler-exact-v1")
            "a generic diagnostics gate" -> DiagnosticsGate.enabled("generic-recipe") { emptyList() }
            else -> adapter.diagnosticsGate()
        }
        retainedRecipeGate = gate
        val recordingAdapter = object : LanguageAdapter by adapter {
            override fun applyRefactoring(request: RefactoringRequest): PatchPlan =
                adapter.applyRefactoring(request).also { recipeChildren += it }
        }
        recipeResult = runCatching { RecipeEngine().run(recipe,
            mapOf("file" to old.toString(), "targetFile" to target.toString()), root,
            dryRun = fault != "apply without a retained plan",
            managedContext = ManagedRecipeContext(candidate, recordingAdapter, gate)) }
    }

    fun verifyRecipeRefusal(code: String) {
        val result = assertIs<RecipeResult.Failed>(recipeResult.getOrThrow())
        assertTrue(result.reason.startsWith("$code:"), result.toString())
        assertEquals(null, result.recipePlan)
        assertTrue(result.stepPlans.all { it.plan == null }, "A failed recipe exposed an applicable child")
        assertTrue(recipeChildren.none { it.status == PatchStatus.PREVIEW })
        expectedOutcome = "REFUSED"
    }

    fun verifyRecipe() {
        val result = assertIs<RecipeResult.Preview>(recipeResult.getOrThrow())
        assertSame(recipeChildren.single(), result.recipePlan)
        assertSame(result.stepPlans.single { it.plan != null }.plan, result.recipePlan)
        assertEquals("sourceFileRelocation", result.recipePlan.operation)
        assertNotNull(result.recipePlan.authorityLease)
        assertEquals(listOf("sourceFileRelocation", "runDiagnostics", "summarizePatch"), result.stepPlans.map { it.stepType })
        assertTrue(result.stepPlans.flatMap { it.diagnostics }.none { it.severity == Diagnostic.Severity.ERROR })
        plan = result.recipePlan
        expectedOutcome = "PREVIEW"
    }

    fun previewOrganize(mode: String, quotes: String) {
        requestedOperation = "organizeImports"
        plan = adapter.applyRefactoring(RefactoringRequest(requestedOperation, arguments = mapOf(
            "file" to "src/imports.ts", "mode" to mode, "quotePreference" to quotes,
            "indentSize" to "2", "tabSize" to "2", "newLine" to "lf", "convertTabsToSpaces" to "true",
        ), snapshot = snapshot))
    }

    fun verifyOrganize(mode: String, quotes: String) {
        expectedOutcome = "PREVIEW"
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(requestedOperation, plan.operation)
        assertTrue((plan.diagnosticsBefore + plan.diagnosticsAfterPreview).none { it.severity == Diagnostic.Severity.ERROR })
        val lease = assertNotNull(plan.authorityLease)
        assertEquals("organizeImports", lease.attributes["command"])
        assertEquals(mode, lease.attributes["mode"])
        assertTrue(lease.attributes.getValue("preferences").contains("\"quotePreference\":\"$quotes\""))
        assertTrue(lease.attributes.getValue("formatOptions").contains("\"indentSize\":2"))
        val names = when (mode) {
            "All" -> "alpha, zebra"
            "SortAndCombine" -> "alpha, unused, zebra"
            "RemoveUnused" -> "zebra, alpha"
            else -> error("Unexpected oracle mode")
        }
        // tsserver updates the import clause but preserves the original module literal.
        // quotePreference is sent and bound; it is not a separate quote-style rewrite.
        val quote = "'"
        val expected = snapshot.files.associate { it.path to it.content } + mapOf(Path.of("src/imports.ts") to
            "import { $names } from ${quote}./dep${quote};\nexport const total = zebra + alpha;\n")
        assertEquals(setOf(Path.of("src/imports.ts")), plan.affectedFiles)
        assertEquals(expected, WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.associate { it.path to it.content })
    }

    fun verifyOutcome(outcome: String, code: String) {
        expectedOutcome = outcome
        assertEquals(requestedOperation, plan.operation)
        assertEquals(if (outcome == "REFUSED") PatchStatus.REFUSED else PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(if (code == "none") null else code, plan.refusalCode)
        if (outcome == "REFUSED") {
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            assertTrue(plan.affectedFiles.isEmpty())
            assertEquals(0.0, plan.confidence)
            assertEquals(false, plan.requiresUserApproval)
        } else {
            assertTrue((plan.diagnosticsBefore + plan.diagnosticsAfterPreview).none { it.severity == Diagnostic.Severity.ERROR })
            val lease = assertNotNull(plan.authorityLease, "A managed advanced preview must retain its exact operation authority")
            assertEquals("sourceFileRelocation", lease.operation)
            assertEquals(snapshot.hash, lease.snapshotHash)
            assertEquals(WorkspaceEditIdentity.sha256(plan.workspaceEdit), lease.workspaceEditSha256)
            assertEquals("getEditsForFileRename", lease.attributes["command"])
            oracle.assertRewrites(plan, snapshot.copy(files = snapshot.files.filter { it.languageId == "typescript" }), model, toolchain)
            oracle.assertExactEdits(plan)
        }
    }

    fun verifyRelocationRefusal(message: String) {
        assertEquals(message, plan.summary)
        assertEquals(null, plan.authorityLease)
        assertTrue(plan.evidence != org.refactorkit.core.RefactoringEvidence.COMPILER_PROVEN)
        assertTrue(!Files.exists(root.resolve(".refactorkit")), "Refused preview must not create metadata")
    }

    private var catalogueQueried = false

    fun verifyCatalogue() {
        val position = SourcePosition(0, 0)
        val catalogue = adapter.availableRefactorings(CodeSelection(SourceLocation(old, SourceRange(position, position))))
        assertEquals(setOf("renameSymbol", "sourceFileRelocation", "organizeImports", "extractFunction", "extractConstant", "inlineVariable", "moveDeclaration", "projectReferenceMigration"),
            catalogue.map { it.id }.toSet())
        assertEquals(8, catalogue.size)
        catalogueQueried = true
    }

    fun verifyWriteAuthority() {
        assertEquals(baseline, image(), "Preview changed original workspace bytes")
        if (catalogueQueried) {
            check(!::plan.isInitialized) { "Catalogue inspection must not generate a mutation plan" }
            assertEquals(0, transactionCount())
            return
        }
        if (expectedOutcome == "REFUSED") {
            assertEquals(0, transactionCount())
            return
        }
        val engine = PatchEngine(root)
        val noApproval = assertIs<ApplyResult.Refused>(engine.apply(
            plan, snapshot, ApplyAuthorization.missing("t5-story"), retainedRecipeGate ?: adapter.diagnosticsGate(),
        ))
        assertTrue(noApproval.diagnostics.any { it.code == "approval.required" })
        assertEquals(0, transactionCount())
        val applied = engine.apply(plan, snapshot, ApplyAuthorization.explicit("t5-story"), retainedRecipeGate ?: adapter.diagnosticsGate())
        if (expectedOutcome == "PREVIEW_ONLY") {
            val refused = assertIs<ApplyResult.Refused>(applied)
            assertTrue(refused.diagnostics.any { it.code == "snapshot.scopeChanged" || it.code == "file.preconditionChanged" })
            assertEquals(0, transactionCount())
        } else {
            val success = assertIs<ApplyResult.Applied>(applied, applied.toString())
            assertEquals(1, transactionCount())
            val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
            for (file in staged.files) assertEquals(file.content, Files.readString(root.resolve(file.path)))
            assertIs<ApplyResult.Applied>(engine.rollback(success.transaction))
        }
        assertEquals(baseline, image(), "Refusal or rollback failed to preserve the complete file image")
    }

    private fun image(): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !it.startsWith(root.resolve(".refactorkit")) }.toList()
            .associate { root.relativize(it) to Files.readString(it) }
    }

    private fun transactionCount(): Int {
        val path = root.resolve(".refactorkit/transactions")
        return if (!Files.exists(path)) 0 else Files.list(path).use { it.filter { p -> p.toString().endsWith(".json") }.count().toInt() }
    }

    override fun close() {
        adapter.close()
        root.toFile().deleteRecursively()
    }
}

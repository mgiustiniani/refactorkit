package org.refactorkit.cli.workspacesnapshotcomposer

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceSnapshotComposer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class WorkspaceSnapshotComposerSteps {
    private data class IdentityCall(
        val name: String,
        val base: ProjectSnapshot,
        val originalPaths: List<Path>,
        val originalExtensions: Set<String>,
        val originalIgnoredDirectories: Set<String>,
        val finalPortName: String?,
        var sourceInvocations: Int = 0,
        var conditionalInvocations: Int = 0,
        var conditionalInput: ProjectSnapshot? = null,
        var finalInvocations: Int = 0,
        var finalInput: ProjectSnapshot? = null,
        var finalOutput: ProjectSnapshot? = null,
        var result: ProjectSnapshot? = null,
    )

    private data class TraceCall(
        val name: String,
        val primaryName: String,
        val sourceName: String,
        val conditionalName: String,
        val finalName: String?,
        val baseOutput: ProjectSnapshot,
        val sourceOutput: ProjectSnapshot,
        val trace: MutableList<String> = mutableListOf(),
        var primaryInvocations: Int = 0,
        var sourceInvocations: Int = 0,
        var conditionalInvocations: Int = 0,
        var finalInvocations: Int = 0,
        var conditionalInput: ProjectSnapshot? = null,
        var conditionalOutput: ProjectSnapshot? = null,
        var finalInput: ProjectSnapshot? = null,
        var finalOutput: ProjectSnapshot? = null,
        var result: ProjectSnapshot? = null,
    )

    private interface CodedSentinel {
        val code: String
    }

    private class PrimarySentinel(cause: Throwable) : RuntimeException("sentinel-E_primary", cause), CodedSentinel {
        override val code: String = "E_primary"
    }

    private class SecondarySentinel(cause: Throwable) : RuntimeException("sentinel-E_secondary", cause), CodedSentinel {
        override val code: String = "E_secondary"
    }

    private class ConditionalSentinel(cause: Throwable) : RuntimeException("sentinel-E_conditional", cause), CodedSentinel {
        override val code: String = "E_conditional"
    }

    private class FinalSentinel(cause: Throwable) : RuntimeException("sentinel-E_final", cause), CodedSentinel {
        override val code: String = "E_final"
    }

    private data class FailureCall(
        val collaborator: String,
        val stage: Int,
        val failureName: String,
        val sentinel: RuntimeException,
        val base: ProjectSnapshot,
        val source: ProjectSnapshot,
        val trace: MutableList<Int> = mutableListOf(),
        var escaped: Throwable? = null,
    )

    private data class ProcessActivity(
        val currentProcessId: Long,
        val descendantProcessIds: Set<Long>,
    )

    private data class WorkspaceLockState(
        val exists: Boolean,
        val exclusivelyAvailable: Boolean,
    )

    private val composer = WorkspaceSnapshotComposer()
    private val temporaryRoots = mutableListOf<Path>()
    private val identityCalls = linkedMapOf<String, IdentityCall>()
    private val traceCalls = linkedMapOf<String, TraceCall>()
    private val globalTrace = mutableListOf<String>()

    private lateinit var workspaceRoot: Path
    private var overlayBase: ProjectSnapshot? = null
    private var overlaySource: ProjectSnapshot? = null
    private var overlayResult: ProjectSnapshot? = null
    private var overlayConditionalInput: ProjectSnapshot? = null
    private var overlayMappings: Map<String, String> = emptyMap()
    private var overlayFiles: List<SourceFile> = emptyList()
    private var overlayIgnoredDirectories: Set<String> = emptySet()

    private var failureCall: FailureCall? = null
    private var beforeWorkspaceState: Map<String, String> = emptyMap()
    private var beforeRefactorKitState: Map<String, String> = emptyMap()
    private var beforeProcessActivity: ProcessActivity? = null
    private var beforeWorkspaceLockState: WorkspaceLockState? = null
    private val callerState = linkedMapOf("authority" to "caller-owned", "generation" to "17")
    private var beforeCallerState: Map<String, String> = emptyMap()

    private lateinit var repositoryRoot: Path
    private var coreSource: String = ""
    private var daemonSource: String = ""
    private var mcpSource: String = ""

    @After
    fun cleanTemporaryWorkspaces() {
        temporaryRoots.asReversed().forEach(::deleteRecursively)
        temporaryRoots.clear()
    }

    @Given("two composition calls receive these exact authoritative base snapshots and optional final evidence ports:")
    fun exactAuthoritativeBases(table: DataTable) {
        workspaceRoot = temporaryWorkspace()
        table.asMaps().forEach { row ->
            val name = row.getValue("call")
            val baseName = row.getValue("exact base snapshot")
            val paths = csv(row.getValue("original file order")).map(Path::of)
            val extensions = csv(row.getValue("source extensions")).toSet()
            val ignored = csv(row.getValue("ignored directories")).toSet()
            val files = paths.mapIndexed { index, path -> SourceFile(path, "$baseName-$index", "java") }
            val base = snapshot(
                id = baseName,
                files = files,
                sourceExtensions = extensions,
                ignoredDirectories = ignored,
            )
            identityCalls[name] = IdentityCall(
                name = name,
                base = base,
                originalPaths = paths,
                originalExtensions = extensions,
                originalIgnoredDirectories = ignored,
                finalPortName = row.getValue("optional final evidence port").takeUnless { it == "absent" },
            )
        }
        assertEquals(setOf("without final", "with final"), identityCalls.keys)
    }

    @Given("each source-inventory port returns zero files even though the fixture workspace contains {string}")
    fun emptySourceInventoriesWithFixture(configName: String) {
        workspaceRoot.resolve(configName).writeText("{\"compilerOptions\":{}}\n")
        assertTrue(workspaceRoot.resolve(configName).exists())
    }

    @Given("each surface-supplied TypeScript evidence port and optional final evidence port records exact input identity and invocation count")
    fun identityRecordingEvidencePorts() {
        assertTrue(identityCalls.isNotEmpty())
        assertEquals(1, identityCalls.values.count { it.finalPortName != null })
    }

    @When("the stateless composer handles both calls")
    fun composeBothIdentityCalls() {
        identityCalls.values.forEach { call ->
            val emptyInventory = snapshot(
                id = "empty-${call.name}",
                files = emptyList(),
                sourceExtensions = setOf("ts", "tsx", "js", "jsx"),
                ignoredDirectories = setOf("node_modules", "dist"),
            )
            val finalPort: ((ProjectSnapshot) -> ProjectSnapshot)? = call.finalPortName?.let { portName ->
                { input ->
                    call.finalInvocations++
                    call.finalInput = input
                    input.copy(buildModels = listOf(model("$portName-output"))).also { call.finalOutput = it }
                }
            }
            call.result = composer.compose(
                workspaceRoot,
                { root -> assertSame(workspaceRoot, root); call.base },
                { root -> assertSame(workspaceRoot, root); call.sourceInvocations++; emptyInventory },
                { input -> call.conditionalInvocations++; call.conditionalInput = input; input },
                finalPort,
            )
        }
    }

    @Then("the TypeScript evidence ports are invoked zero times")
    fun conditionalPortsSkippedForEmptyInventory() {
        identityCalls.values.forEach { call ->
            assertEquals(0, call.conditionalInvocations, call.name)
            assertEquals(null, call.conditionalInput, call.name)
            assertEquals(1, call.sourceInvocations, call.name)
        }
    }

    @Then("the call without a final port returns the same B0 instance and hash, with its original file order, extensions, and ignored directories unchanged")
    fun exactEmptyPassThroughWithoutFinal() {
        val call = identityCalls.getValue("without final")
        val result = assertNotNull(call.result)
        assertSame(call.base, result)
        assertEquals(call.base.hash, result.hash)
        assertEquals(call.originalPaths, result.files.map(SourceFile::path))
        assertEquals(call.originalExtensions, result.sourceExtensions)
        assertEquals(call.originalIgnoredDirectories, result.ignoredDirectories)
        assertEquals(0, call.finalInvocations)
    }

    @Then("current-final-1 receives the same B1 instance rather than a copy and the call returns its exact output unchanged")
    fun exactEmptyPassThroughWithFinal() {
        val call = identityCalls.getValue("with final")
        assertEquals("current-final-1", call.finalPortName)
        assertEquals(1, call.finalInvocations)
        assertSame(call.base, call.finalInput)
        assertSame(call.finalOutput, call.result)
    }

    @Given("the authoritative base snapshot has these distinct field values:")
    fun authoritativeOverlayBase(table: DataTable) {
        workspaceRoot = temporaryWorkspace()
        val values = table.asMaps().associate { it.getValue("field") to it.getValue("exact base value") }
        assertEquals("workspace-base", values.getValue("workspace"))
        assertEquals("modules-base", values.getValue("modules"))
        assertEquals("classpath-base", values.getValue("classpath evidence"))
        val buildModelIds = csv(values.getValue("build models"))
        val auxiliaryPaths = csv(values.getValue("auxiliary files"))
        overlayBase = ProjectSnapshot(
            workspace = Workspace(workspaceRoot.resolve(values.getValue("workspace"))),
            modules = listOf(Module(values.getValue("modules"), workspaceRoot.resolve("modules-base"))),
            files = emptyList(),
            sourceExtensions = csv(values.getValue("source extensions")).toSet(),
            ignoredDirectories = csv(values.getValue("ignored directories")).toSet(),
            classpathEvidence = listOf(ClasspathEvidence(Path.of("classpath-base"), ClasspathEvidenceKind.ENTRY, "classpath-base")),
            buildModels = buildModelIds.map(::model),
            auxiliaryFiles = auxiliaryPaths.map { path -> SourceFile(Path.of(path), "aux-$path", "build") },
        )
    }

    @Given("its source files are:")
    fun authoritativeSourceFiles(table: DataTable) {
        val base = assertNotNull(overlayBase)
        overlayBase = base.copy(files = table.asMaps().map(::sourceFile))
    }

    @Given("the later source-inventory port is configured with these exact mappings and files:")
    fun laterSourceInventory(table: DataTable) {
        val rows = table.asMaps()
        overlayMappings = rows.associate { it.getValue("extension") to it.getValue("language ID") }
        assertEquals(
            mapOf("ts" to "typescript", "tsx" to "typescript", "js" to "javascript", "jsx" to "javascript"),
            overlayMappings,
        )
        overlayFiles = rows.map(::sourceFile)
    }

    @Given("that contribution declares ignored directories {string} and {string}")
    fun contributionIgnoredDirectories(first: String, second: String) {
        overlayIgnoredDirectories = linkedSetOf(first, second)
        overlaySource = snapshot(
            id = "later-source",
            files = overlayFiles,
            sourceExtensions = overlayMappings.keys,
            ignoredDirectories = overlayIgnoredDirectories,
        )
    }

    @When("the composer overlays the contribution before an identity evidence attacher")
    fun composeBoundedOverlay() {
        val base = assertNotNull(overlayBase)
        val source = assertNotNull(overlaySource)
        overlayResult = composer.compose(
            workspaceRoot,
            { base },
            { source },
            { input -> overlayConditionalInput = input; input },
        )
    }

    @Then("normalized path {string} retains the later {string} source and discards {string}")
    fun normalizedLaterPrecedence(path: String, retained: String, discarded: String) {
        val normalized = Path.of(path).normalize()
        val matches = assertNotNull(overlayResult).files.filter { it.path.normalize() == normalized }
        assertEquals(1, matches.size)
        assertEquals(retained, matches.single().content)
        assertFalse(assertNotNull(overlayResult).files.any { it.content == discarded })
    }

    @Then("the surviving source values have this exact `path.toString\\(\\)` order:")
    fun deterministicPathStringOrder(table: DataTable) {
        val expectedRows = table.asMaps().sortedBy { it.getValue("order").toInt() }
        val result = assertNotNull(overlayResult)
        val expectedPaths = expectedRows.map { it.getValue("path") }
        val actualPaths = result.files.map { it.path.invariantSeparatorsPathString }
        assertEquals(
            expectedPaths,
            actualPaths,
            "Canonical source ordering must retain the exact invariant path order",
        )
        assertEquals(expectedRows.map { it.getValue("content marker") }, result.files.map(SourceFile::content))
    }

    @Then("source extensions are exactly the union {string} and ignored directories are exactly the union {string}")
    fun exactInventoryUnions(extensions: String, ignoredDirectories: String) {
        val result = assertNotNull(overlayResult)
        assertEquals(csv(extensions).toSet(), result.sourceExtensions)
        assertEquals(csv(ignoredDirectories).toSet(), result.ignoredDirectories)
    }

    @Then("workspace, modules, classpath evidence, pre-existing build models, and auxiliary files retain their exact authoritative base values")
    fun preserveAllNonInventoryBaseFields() {
        val base = assertNotNull(overlayBase)
        val result = assertNotNull(overlayResult)
        assertSame(base.workspace, result.workspace)
        assertSame(base.modules, result.modules)
        assertSame(base.classpathEvidence, result.classpathEvidence)
        assertSame(base.buildModels, result.buildModels)
        assertSame(base.auxiliaryFiles, result.auxiliaryFiles)
        assertSame(overlayConditionalInput, result)
    }

    @Then("repeating composition with identical port evidence returns equal canonical content and hash without a clock, UUID, or composer identity")
    fun deterministicRepeatComposition() {
        val base = assertNotNull(overlayBase)
        val source = assertNotNull(overlaySource)
        val repeat = composer.compose(workspaceRoot, { base }, { source }, { it })
        val first = assertNotNull(overlayResult)
        assertEquals(first, repeat)
        assertEquals(first.hash, repeat.hash)
        val sourceText = productionSource("modules/refactorkit-core/src/main/kotlin/org/refactorkit/core/WorkspaceSnapshotComposer.kt")
        listOf("Instant", "Clock", "UUID", "composerId").forEach { forbidden ->
            assertFalse(sourceText.contains(forbidden), "composer must not use $forbidden")
        }
    }

    @Given("three non-empty composition calls supply these language-neutral ports for that call only:")
    fun threePerCallPortSets(table: DataTable) {
        workspaceRoot = temporaryWorkspace()
        table.asMaps().forEachIndexed { index, row ->
            val name = row.getValue("call")
            val base = snapshot(
                id = "base-$name",
                files = listOf(SourceFile(Path.of("$name/Base.java"), "base-$name", "java")),
                sourceExtensions = setOf("java"),
            )
            val source = snapshot(
                id = "source-$name",
                files = listOf(SourceFile(Path.of("$name/input.ts"), "source-$name", "typescript")),
                sourceExtensions = setOf("ts"),
                ignoredDirectories = setOf("source-ignore-$index"),
            )
            traceCalls[name] = TraceCall(
                name = name,
                primaryName = row.getValue("base scanner"),
                sourceName = row.getValue("source-inventory scanner"),
                conditionalName = row.getValue("TypeScript-configured conditional attacher"),
                finalName = row.getValue("optional Kotlin-configured final attacher").takeUnless { it == "absent" },
                baseOutput = base,
                sourceOutput = source,
            )
        }
        assertEquals(listOf("first", "second", "third"), traceCalls.keys.toList())
    }

    @Given("every port returns a distinct immutable snapshot and records exact input and output identity")
    fun distinctTracePortSnapshots() {
        traceCalls.values.forEach { call -> assertNotSame(call.baseOutput, call.sourceOutput) }
    }

    @When("the same stateless composer handles first, second, and third in order")
    fun composeThreeCallsInOrder() {
        traceCalls.values.forEachIndexed { index, call ->
            val conditional: (ProjectSnapshot) -> ProjectSnapshot = { input ->
                call.trace += call.conditionalName
                globalTrace += call.conditionalName
                call.conditionalInvocations++
                call.conditionalInput = input
                val status = if (index == 0) BuildModelStatus.AVAILABLE else BuildModelStatus.UNAVAILABLE
                input.copy(buildModels = listOf(model("conditional-provider-$index", status))).also {
                    call.conditionalOutput = it
                }
            }
            val finalPort: ((ProjectSnapshot) -> ProjectSnapshot)? = call.finalName?.let { finalName ->
                { input ->
                    call.trace += finalName
                    globalTrace += finalName
                    call.finalInvocations++
                    call.finalInput = input
                    input.copy(auxiliaryFiles = listOf(SourceFile(Path.of("$finalName.evidence"), finalName, "evidence"))).also {
                        call.finalOutput = it
                    }
                }
            }
            call.result = composer.compose(
                workspaceRoot,
                {
                    call.trace += call.primaryName
                    globalTrace += call.primaryName
                    call.primaryInvocations++
                    call.baseOutput
                },
                {
                    call.trace += call.sourceName
                    globalTrace += call.sourceName
                    call.sourceInvocations++
                    call.sourceOutput
                },
                conditional,
                finalPort,
            )
        }
    }

    @Then("their exact collaborator traces are:")
    fun exactCollaboratorTraces(table: DataTable) {
        table.asMaps().forEach { row ->
            assertEquals(csv(row.getValue("ordered trace")), traceCalls.getValue(row.getValue("call")).trace)
        }
        assertEquals(traceCalls.values.flatMap(TraceCall::trace), globalTrace)
    }

    @Then("each conditional attacher is invoked exactly once with that call's overlaid snapshot")
    fun eachConditionalGetsItsOwnOverlayOnce() {
        traceCalls.values.forEach { call ->
            assertEquals(1, call.conditionalInvocations, call.name)
            val input = assertNotNull(call.conditionalInput)
            assertEquals(
                listOf("${call.name}/Base.java", "${call.name}/input.ts"),
                input.files.map { it.path.invariantSeparatorsPathString },
                "${call.name} overlay must retain its exact invariant path order",
            )
            assertEquals(setOf("java", "ts"), input.sourceExtensions)
        }
    }

    @Then("final-1 and final-2 are each invoked exactly once with that call's exact conditional output, while the third call returns conditional-3's exact output")
    fun exactFinalInputsAndOutputs() {
        traceCalls.values.take(2).forEach { call ->
            assertEquals(1, call.finalInvocations, call.name)
            assertSame(call.conditionalOutput, call.finalInput)
            assertSame(call.finalOutput, call.result)
        }
        val third = traceCalls.getValue("third")
        assertEquals(0, third.finalInvocations)
        assertSame(third.conditionalOutput, third.result)
    }

    @Then("no scanner or attacher from an earlier call is retained, reused, or invoked by a later call")
    fun noStalePortRetention() {
        traceCalls.values.forEach { call ->
            assertEquals(1, call.primaryInvocations, call.name)
            assertEquals(1, call.sourceInvocations, call.name)
            assertEquals(1, call.conditionalInvocations, call.name)
            assertEquals(if (call.finalName == null) 0 else 1, call.finalInvocations, call.name)
        }
        val expectedNames = traceCalls.values.flatMap { call ->
            listOfNotNull(call.primaryName, call.sourceName, call.conditionalName, call.finalName)
        }
        assertEquals(expectedNames, globalTrace)
    }

    @Then("the composer passes through each attacher's provider replacement or typed unavailability result without reinterpretation")
    fun exactTypedAttacherPassThrough() {
        val first = traceCalls.getValue("first")
        assertSame(first.finalOutput, first.result)
        assertEquals(BuildModelStatus.AVAILABLE, assertNotNull(first.result).buildModels.single().status)
        traceCalls.values.drop(1).forEach { call ->
            assertSame(call.finalOutput ?: call.conditionalOutput, call.result)
            assertEquals(BuildModelStatus.UNAVAILABLE, assertNotNull(call.result).buildModels.single().status)
            assertEquals("evidence-unavailable", assertNotNull(call.result).buildModels.single().diagnostics.single().code)
        }
    }

    @Given("the per-call {string} port throws the exact sentinel object {string} with a distinct type, code, message, and cause")
    fun configureSentinelFailure(collaborator: String, failureName: String) {
        workspaceRoot = temporaryWorkspace()
        workspaceRoot.resolve("workspace.bin").writeBytes(byteArrayOf(0, 1, 2, 3, -1))
        workspaceRoot.resolve("nested/path.txt").also { it.parent.createDirectories(); it.writeText("unchanged\n") }
        workspaceRoot.resolve(".refactorkit").createDirectories()
        workspaceRoot.resolve(".refactorkit/state.bin").writeBytes(byteArrayOf(9, 8, 7))
        workspaceRoot.resolve(".refactorkit/workspace.lock").writeBytes(byteArrayOf(6, 5, 4))
        val cause = IllegalStateException("cause-$failureName")
        val sentinel = when (failureName) {
            "E_primary" -> PrimarySentinel(cause)
            "E_secondary" -> SecondarySentinel(cause)
            "E_conditional" -> ConditionalSentinel(cause)
            "E_final" -> FinalSentinel(cause)
            else -> fail("unexpected sentinel $failureName")
        }
        val stage = when (collaborator) {
            "authoritative base scanner" -> 1
            "secondary source-inventory scanner" -> 2
            "TypeScript-configured conditional evidence attacher" -> 3
            "current Kotlin-configured final evidence attacher" -> 4
            else -> fail("unexpected collaborator $collaborator")
        }
        val sentinelTypes = setOf(
            PrimarySentinel::class.java,
            SecondarySentinel::class.java,
            ConditionalSentinel::class.java,
            FinalSentinel::class.java,
        )
        assertEquals(4, sentinelTypes.size)
        assertEquals(failureName, (sentinel as CodedSentinel).code)
        failureCall = FailureCall(
            collaborator,
            stage,
            failureName,
            sentinel,
            snapshot("failure-base", listOf(SourceFile(Path.of("Base.java"), "base", "java")), setOf("java")),
            snapshot("failure-source", listOf(SourceFile(Path.of("source.ts"), "source", "typescript")), setOf("ts")),
        )
    }

    @Given("every earlier stage succeeds with a non-empty source contribution and every later port records whether it was invoked")
    fun configuredFailurePipelineIsNonEmpty() {
        val call = assertNotNull(failureCall)
        assertTrue(call.base.files.isNotEmpty())
        assertTrue(call.source.files.isNotEmpty())
        assertTrue(call.stage in 1..4)
        assertTrue(call.trace.isEmpty())
        assertTrue(call.escaped == null)
    }

    @Given("workspace bytes, paths, caller state, process activity, locks, and {string} contents are recorded before composition")
    fun recordFailureSideEffectBaseline(refactorKitDirectory: String) {
        assertEquals(".refactorkit", refactorKitDirectory)
        beforeWorkspaceState = captureTree(workspaceRoot)
        beforeRefactorKitState = captureTree(workspaceRoot.resolve(refactorKitDirectory))
        beforeProcessActivity = captureProcessActivity()
        beforeWorkspaceLockState = captureWorkspaceLockState(workspaceRoot.resolve(refactorKitDirectory).resolve("workspace.lock"))
        beforeCallerState = callerState.toMap()
        assertTrue(beforeRefactorKitState.isNotEmpty())
        assertTrue(assertNotNull(beforeWorkspaceLockState).exclusivelyAvailable)
    }

    @When("the composer handles the call")
    fun composeFailingCall() {
        val call = assertNotNull(failureCall)
        fun failAt(stage: Int) {
            if (call.stage == stage) throw call.sentinel
        }
        call.escaped = runCatching {
            composer.compose(
                workspaceRoot,
                {
                    call.trace += 1
                    failAt(1)
                    call.base
                },
                {
                    call.trace += 2
                    failAt(2)
                    call.source
                },
                { input ->
                    call.trace += 3
                    failAt(3)
                    input.copy(buildModels = listOf(model("conditional-success")))
                },
                { input ->
                    call.trace += 4
                    failAt(4)
                    input.copy(buildModels = listOf(model("final-success")))
                },
            )
        }.exceptionOrNull()
        assertNotNull(call.escaped, "sentinel failure must escape")
    }

    @Then("the same {string} object escapes by reference with its type, code, message, and cause unchanged")
    fun exactSentinelEscapes(failureName: String) {
        val call = assertNotNull(failureCall)
        assertEquals(call.failureName, failureName)
        val escaped = assertNotNull(call.escaped)
        assertSame(call.sentinel, escaped)
        assertSame(call.sentinel.cause, escaped.cause)
        assertEquals(call.sentinel::class.java, escaped::class.java)
        assertEquals(call.sentinel.message, escaped.message)
        assertEquals((call.sentinel as CodedSentinel).code, (escaped as CodedSentinel).code)
    }

    @Then("no stage after {string} is invoked")
    fun noStageAfterFailure(stage: String) {
        val call = assertNotNull(failureCall)
        assertEquals(call.stage, stage.toInt())
        assertEquals((1..call.stage).toList(), call.trace)
    }

    @Then("the composer performs no workspace write, build execution, process lifecycle action, lock, WAL, transaction, PatchEngine call, recovery, or rollback")
    fun noCompositionSideEffects() {
        val source = productionSource("modules/refactorkit-core/src/main/kotlin/org/refactorkit/core/WorkspaceSnapshotComposer.kt")
        assertEquals(
            listOf("import java.nio.file.Path"),
            source.lineSequence().filter { it.startsWith("import ") }.toList(),
            "composer must depend only on the immutable snapshot model and Path input",
        )
        listOf(
            "java.nio.file.Files", "PatchEngine", "ProcessBuilder", "TransactionLog", "FileChannel",
            "writeText", "writeBytes", "newOutputStream", "lock()", "rollback",
        ).forEach { forbidden -> assertFalse(source.contains(forbidden), "composer contains $forbidden") }
    }

    @Then("all recorded workspace, caller, process, lock, and {string} state remains unchanged")
    fun allFailureStateUnchanged(refactorKitDirectory: String) {
        assertEquals(".refactorkit", refactorKitDirectory)
        assertEquals(beforeWorkspaceState, captureTree(workspaceRoot))
        assertEquals(beforeRefactorKitState, captureTree(workspaceRoot.resolve(refactorKitDirectory)))
        assertEquals(beforeProcessActivity, captureProcessActivity())
        assertEquals(
            beforeWorkspaceLockState,
            captureWorkspaceLockState(workspaceRoot.resolve(refactorKitDirectory).resolve("workspace.lock")),
        )
        assertEquals(beforeCallerState, callerState)
    }

    @Given("the shared composer is a stateless service in core with primary-scan, source-inventory, conditional-evidence, and optional-final-evidence ports supplied per call")
    fun sharedStatelessCoreService() {
        loadAdoptionSources()
        assertTrue(coreSource.contains("class WorkspaceSnapshotComposer"))
        assertTrue(coreSource.contains("authoritativeBaseScanner"))
        assertTrue(coreSource.contains("sourceInventoryScanner"))
        assertTrue(coreSource.contains("conditionalEvidenceAttacher"))
        assertTrue(coreSource.contains("finalEvidenceAttacher"))
        assertTrue(WorkspaceSnapshotComposer::class.java.declaredFields.none { !it.isSynthetic })
    }

    @Given("daemon and MCP retain the exact script-extension configuration in their adapter or surface wiring rather than in core")
    fun scriptMappingsRemainSurfaceOwned() {
        listOf(daemonSource, mcpSource).forEach { source ->
            mapOf("ts" to "typescript", "tsx" to "typescript", "js" to "javascript", "jsx" to "javascript")
                .forEach { (extension, languageId) ->
                    assertTrue(
                        Regex("\\\"$extension\\\"\\s+to\\s+\\\"$languageId\\\"").containsMatchIn(source),
                        "$extension mapping missing",
                    )
                }
            assertTrue(source.contains("GenericProjectScanner(SCRIPT_EXTENSIONS)"))
        }
        listOf("\"ts\"", "\"tsx\"", "\"js\"", "\"jsx\"").forEach { mapping ->
            assertFalse(coreSource.contains(mapping), "core must not own $mapping")
        }
    }

    @When("only daemon and MCP replace their duplicate mixed-workspace composition with the shared composer")
    fun boundedSurfaceAdoption() {
        assertTrue(daemonSource.contains("WorkspaceSnapshotComposer"))
        assertTrue(mcpSource.contains("WorkspaceSnapshotComposer"))
        assertEquals(1, Regex("WorkspaceSnapshotComposer\\(\\)\\.compose\\(").findAll(daemonSource).count())
        assertEquals(1, Regex("WorkspaceSnapshotComposer\\(\\)\\.compose\\(").findAll(mcpSource).count())
        assertMainSourcesExcludeComposer("modules/refactorkit-cli/src/main")
        assertMainSourcesExcludeComposer("modules/refactorkit-lsp/src/main")
    }

    @Then("they invoke it at their existing composition points:")
    fun existingCompositionPointsRemain(table: DataTable) {
        val points = table.asMaps().associate { it.getValue("surface") to csv(it.getValue("existing composition points")) }
        assertEquals(
            listOf("project open", "saved-workspace refresh", "apply baseline", "apply post-image", "rollback refresh"),
            points.getValue("daemon"),
        )
        assertEquals(
            listOf("project scan", "apply baseline", "apply post-image", "rollback refresh"),
            points.getValue("MCP"),
        )
        assertEquals(6, Regex("\\bscanWorkspace\\(").findAll(daemonSource).count())
        assertTrue(functionText(daemonSource, "projectOpen").contains("scanWorkspace(path)"))
        assertTrue(functionText(daemonSource, "refreshSavedWorkspace").contains("scanWorkspace(root)"))
        assertEquals(2, Regex("\\bscanWorkspace\\(root\\)").findAll(functionText(daemonSource, "refactorApply")).count())
        assertTrue(functionText(daemonSource, "patchRollback").contains("scanWorkspace(root)"))

        assertEquals(5, Regex("\\bscanWorkspace\\(").findAll(mcpSource).count())
        assertTrue(functionText(mcpSource, "toolProjectScan").contains("scanWorkspace(path)"))
        assertEquals(2, Regex("\\bscanWorkspace\\(root\\)").findAll(functionText(mcpSource, "toolApplyRefactoring")).count())
        assertTrue(functionText(mcpSource, "toolRollbackRefactoring").contains("scanWorkspace(root)"))

        val daemonApply = functionText(daemonSource, "refactorApply")
        assertTrue(daemonApply.indexOf("val refreshed = scanWorkspace(root)") < daemonApply.indexOf("closeSemanticAdapters()"))
        val mcpApply = functionText(mcpSource, "toolApplyRefactoring")
        assertTrue(mcpApply.indexOf("snapshot = scanWorkspace(root)") < mcpApply.indexOf("closeSemanticAdapters()"))
    }

    @Then("each surface retains these policies outside composition:")
    fun surfacePoliciesRemainOutsideComposer(table: DataTable) {
        table.asMaps().forEach { row ->
            when (row.getValue("policy boundary")) {
                "refresh and saved state" -> {
                    assertTrue(daemonSource.contains("SavedWorkspaceWatcher"))
                    assertTrue(daemonSource.contains("workspaceRefreshCount"))
                    assertTrue(mcpSource.contains("workspaceRoot"))
                }
                "workspace index" -> {
                    assertTrue(daemonSource.contains("WorkspaceIndexSession"))
                    assertTrue(daemonSource.contains("workspaceIndex.reconcile"))
                }
                "pending and authority state" -> {
                    assertTrue(daemonSource.contains("PendingPlanStore"))
                    assertTrue(mcpSource.contains("PendingPlanStore"))
                    assertTrue(daemonSource.contains("clearLexicalReviewAudit"))
                    assertTrue(mcpSource.contains("clearLexicalReviewAudit"))
                }
                "semantic lifecycle" -> {
                    assertTrue(daemonSource.contains("closeSemanticAdapters"))
                    assertTrue(mcpSource.contains("closeSemanticAdapters"))
                    assertTrue(daemonSource.contains("semanticLeases"))
                    assertTrue(mcpSource.contains("semanticLeases"))
                }
                "PatchEngine authority" -> {
                    assertTrue(daemonSource.contains("PatchEngine(root).apply"))
                    assertTrue(mcpSource.contains("PatchEngine(root).apply"))
                    assertTrue(daemonSource.contains("ManagedRollbackExecutor"))
                    assertTrue(mcpSource.contains("ManagedRollbackExecutor"))
                }
                "protocol policy" -> {
                    assertTrue(daemonSource.contains("JsonElement"))
                    assertTrue(mcpSource.contains("structuredToolContent"))
                }
                else -> fail("unexpected policy boundary ${row.getValue("policy boundary")}")
            }
        }
        listOf(
            "SavedWorkspaceWatcher", "WorkspaceIndex", "PendingPlanStore", "semanticLeases",
            "PatchEngine", "JsonElement", "TransactionLog",
        ).forEach { outsideType -> assertFalse(coreSource.contains(outsideType), "$outsideType leaked into composer") }
    }

    @Then("CLI and LSP keep their existing scan and lifecycle behavior and do not adopt this composer in this extraction")
    fun cliAndLspRemainExcluded() {
        assertMainSourcesExcludeComposer("modules/refactorkit-cli/src/main")
        assertMainSourcesExcludeComposer("modules/refactorkit-lsp/src/main")
    }

    @Then("core imports no concrete Java, Tree-sitter, TypeScript, Kotlin, daemon, or MCP type and gains no generalized scanner registry or new orchestration module")
    fun coreRemainsLanguageNeutral() {
        val imports = coreSource.lineSequence().filter { it.startsWith("import ") }.toList()
        listOf(
            "org.refactorkit.java", "org.refactorkit.treesitter", "org.refactorkit.typescript",
            "org.refactorkit.kotlin", "org.refactorkit.daemon", "org.refactorkit.mcp",
        ).forEach { namespace -> assertTrue(imports.none { it.contains(namespace) }, namespace) }
        assertFalse(coreSource.contains("Registry"))
        val settings = repositoryRoot.resolve("settings.gradle.kts").readText()
        assertFalse(settings.contains("workspace-snapshot-composer"))
        val jvmBuild = repositoryRoot.resolve("modules/refactorkit-jvm/build.gradle.kts").readText()
        assertFalse(jvmBuild.contains("refactorkit-tree-sitter"))
        assertFalse(jvmBuild.contains("refactorkit-typescript"))
    }

    @Then("composition returns only an immutable snapshot and owns no stored state, index, protocol, persistence, or mutation authority")
    fun immutableStatelessCompositionBoundary() {
        val composeMethods = WorkspaceSnapshotComposer::class.java.declaredMethods.filter { it.name == "compose" }
        assertTrue(composeMethods.isNotEmpty())
        assertTrue(composeMethods.all { it.returnType == ProjectSnapshot::class.java })
        assertTrue(WorkspaceSnapshotComposer::class.java.declaredFields.none { !it.isSynthetic })
        listOf(
            "var ", "Mutable", "WorkspaceIndex", "Json", "PatchEngine", "Transaction", "FileEdit",
            "Files.", "Process", "Lock", "store", "persist",
        ).forEach { forbidden -> assertFalse(coreSource.contains(forbidden), "composer owns $forbidden") }
    }

    private fun sourceFile(row: Map<String, String>): SourceFile = SourceFile(
        Path.of(row.getValue("path")),
        row.getValue("content marker"),
        row.getValue("language ID"),
    )

    private fun snapshot(
        id: String,
        files: List<SourceFile> = emptyList(),
        sourceExtensions: Set<String> = ProjectSnapshot.inferSourceExtensions(files),
        ignoredDirectories: Set<String> = emptySet(),
        buildModels: List<BuildModel> = emptyList(),
    ): ProjectSnapshot {
        val root = if (::workspaceRoot.isInitialized) workspaceRoot else Path.of(".").toAbsolutePath().normalize()
        return ProjectSnapshot(
            workspace = Workspace(root.resolve(id)),
            modules = listOf(Module(id, root.resolve(id))),
            files = files,
            sourceExtensions = sourceExtensions,
            ignoredDirectories = ignoredDirectories,
            buildModels = buildModels,
        )
    }

    private fun model(providerId: String, status: BuildModelStatus = BuildModelStatus.AVAILABLE): BuildModel = BuildModel(
        providerId = providerId,
        status = status,
        modules = emptyList(),
        diagnostics = if (status == BuildModelStatus.UNAVAILABLE) listOf(
            BuildModelDiagnostic(
                code = "evidence-unavailable",
                message = "typed evidence is unavailable",
                severity = Diagnostic.Severity.ERROR,
            ),
        ) else emptyList(),
    )

    private fun csv(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun temporaryWorkspace(): Path = Files.createTempDirectory("refactorkit-workspace-composer-")
        .toAbsolutePath().normalize().also(temporaryRoots::add)

    private fun captureTree(root: Path): Map<String, String> {
        if (!root.exists()) return emptyMap()
        val entries = linkedMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.sorted().forEach { path ->
                val relative = root.relativize(path).toString().ifBlank { "." }
                entries[relative] = if (Files.isDirectory(path)) {
                    "directory"
                } else {
                    "file:${Base64.getEncoder().encodeToString(Files.readAllBytes(path))}"
                }
            }
        }
        return entries
    }

    private fun captureProcessActivity(): ProcessActivity {
        val current = ProcessHandle.current()
        val descendants = sortedSetOf<Long>()
        current.descendants().use { stream -> stream.forEach { descendants += it.pid() } }
        return ProcessActivity(current.pid(), descendants)
    }

    private fun captureWorkspaceLockState(path: Path): WorkspaceLockState {
        val exists = path.exists()
        val available = exists && runCatching {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: return@use false
                try {
                    true
                } finally {
                    lock.release()
                }
            }
        }.getOrDefault(false)
        return WorkspaceLockState(exists, available)
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").exists() && candidate.resolve("modules/refactorkit-core").exists()) {
                return candidate
            }
            candidate = candidate.parent
        }
        fail("repository root not found")
    }

    private fun productionSource(relative: String): String {
        if (!::repositoryRoot.isInitialized) repositoryRoot = locateRepositoryRoot()
        return repositoryRoot.resolve(relative).readText()
    }

    private fun loadAdoptionSources() {
        repositoryRoot = locateRepositoryRoot()
        coreSource = productionSource("modules/refactorkit-core/src/main/kotlin/org/refactorkit/core/WorkspaceSnapshotComposer.kt")
        daemonSource = productionSource("modules/refactorkit-daemon/src/main/kotlin/org/refactorkit/daemon/DaemonSession.kt")
        mcpSource = productionSource("modules/refactorkit-mcp/src/main/kotlin/org/refactorkit/mcp/McpSession.kt")
    }

    private fun assertMainSourcesExcludeComposer(relativeDirectory: String) {
        val directory = repositoryRoot.resolve(relativeDirectory)
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { source ->
                assertFalse(source.readText().contains("WorkspaceSnapshotComposer"), source.toString())
            }
        }
    }

    private fun functionText(source: String, name: String): String {
        val declaration = Regex("(?m)^    private fun ${Regex.escape(name)}[ \\t]*\\(")
        val matches = declaration.findAll(source).toList()
        assertEquals(1, matches.size, "expected exactly one private function named $name")
        val start = matches.single().range.first
        val end = Regex("(?m)^    private fun ").find(source, matches.single().range.last + 1)
            ?.range?.first ?: source.length
        return source.substring(start, end)
    }
}

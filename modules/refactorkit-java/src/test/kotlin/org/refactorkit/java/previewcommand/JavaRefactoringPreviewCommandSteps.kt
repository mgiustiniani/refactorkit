package org.refactorkit.java.previewcommand

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.Module
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SymbolId
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.java.ExistingJavaRefactoringPreviewPlannerInvoker
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaRefactoringPreviewCommand
import org.refactorkit.java.JavaRefactoringPreviewDispatcher
import org.refactorkit.java.JavaRefactoringPreviewPlannerInvoker
import java.lang.reflect.Modifier
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class JavaRefactoringPreviewCommandSteps {
    private data class PlannerInvocation(
        val route: String,
        val snapshot: ProjectSnapshot,
        val adapter: JavaLanguageAdapter?,
        val values: List<Any>,
    )

    private class RecordingPlannerInvoker(
        private val returnedPlan: PatchPlan? = null,
        private val thrownFailure: RuntimeException? = null,
    ) : JavaRefactoringPreviewPlannerInvoker {
        val invocations = mutableListOf<PlannerInvocation>()

        override fun renameClass(
            snapshot: ProjectSnapshot,
            javaAdapter: JavaLanguageAdapter,
            symbolId: SymbolId,
            newName: String,
        ): PatchPlan = record("RenameClass", snapshot, javaAdapter, symbolId, newName)

        override fun renameMember(
            snapshot: ProjectSnapshot,
            javaAdapter: JavaLanguageAdapter,
            symbolId: SymbolId,
            newName: String,
        ): PatchPlan = record("RenameMember", snapshot, javaAdapter, symbolId, newName)

        override fun moveSourceRoot(
            snapshot: ProjectSnapshot,
            javaAdapter: JavaLanguageAdapter,
            from: Path,
            to: Path,
        ): PatchPlan = record("MoveSourceRoot", snapshot, javaAdapter, from, to)

        override fun organizeImports(snapshot: ProjectSnapshot, file: Path): PatchPlan =
            record("OrganizeImports", snapshot, null, file)

        override fun safeDelete(
            snapshot: ProjectSnapshot,
            javaAdapter: JavaLanguageAdapter,
            symbolId: SymbolId,
            force: Boolean,
        ): PatchPlan = record("SafeDelete", snapshot, javaAdapter, symbolId, force)

        private fun record(
            route: String,
            snapshot: ProjectSnapshot,
            adapter: JavaLanguageAdapter?,
            vararg values: Any,
        ): PatchPlan {
            invocations += PlannerInvocation(route, snapshot, adapter, values.toList())
            thrownFailure?.let { throw it }
            return assertNotNull(returnedPlan, "recording invoker requires a configured result")
        }
    }

    private interface CodedPlannerFailure {
        val code: String
    }

    private class PlannerSentinelFailure(
        override val code: String,
        cause: Throwable,
    ) : RuntimeException("sentinel-$code", cause), CodedPlannerFailure

    private data class ProcessActivity(
        val processId: Long,
        val descendants: Set<Long>,
    )

    private data class LockState(
        val exists: Boolean,
        val exclusivelyAvailable: Boolean,
    )

    private val temporaryRoots = mutableListOf<Path>()

    private lateinit var currentSnapshot: ProjectSnapshot
    private lateinit var currentAdapter: JavaLanguageAdapter
    private lateinit var currentCommand: JavaRefactoringPreviewCommand
    private lateinit var expectedPlan: PatchPlan
    private lateinit var recordingInvoker: RecordingPlannerInvoker
    private var returnedPlan: PatchPlan? = null
    private var expectedPlannerCall: String = ""

    private lateinit var genericSnapshot: ProjectSnapshot
    private lateinit var genericAdapter: JavaLanguageAdapter
    private val genericRequests = linkedMapOf<String, RefactoringRequest>()
    private val genericCommands = linkedMapOf<String, JavaRefactoringPreviewCommand>()
    private val genericResults = linkedMapOf<String, PatchPlan>()
    private val canonicalResults = linkedMapOf<String, PatchPlan>()
    private val genericRefusals = linkedMapOf<String, PatchPlan>()
    private var genericFalseDefaultResult: PatchPlan? = null
    private var canonicalFalseDefaultResult: PatchPlan? = null

    private lateinit var repositoryRoot: Path
    private var commandSource: String = ""
    private var adapterSource: String = ""

    private lateinit var isolatedPlan: PatchPlan
    private lateinit var isolatedSuccessSnapshot: ProjectSnapshot
    private lateinit var isolatedFailureSnapshot: ProjectSnapshot
    private lateinit var isolatedSuccessAdapter: JavaLanguageAdapter
    private lateinit var isolatedFailureAdapter: JavaLanguageAdapter
    private lateinit var isolatedSuccessInvoker: RecordingPlannerInvoker
    private lateinit var isolatedFailureInvoker: RecordingPlannerInvoker
    private lateinit var plannerFailure: PlannerSentinelFailure
    private var isolatedReturned: PatchPlan? = null
    private var isolatedEscaped: Throwable? = null
    private lateinit var sideEffectRoot: Path
    private var beforeWorkspace: Map<String, String> = emptyMap()
    private var beforeProcess: ProcessActivity? = null
    private var beforeLock: LockState? = null

    @After
    fun deleteTemporaryWorkspaces() {
        temporaryRoots.asReversed().forEach(::deleteRecursively)
        temporaryRoots.clear()
    }

    @Given("one planning call supplies the exact immutable snapshot {string} and current Java adapter {string}")
    fun exactSnapshotAndAdapter(snapshotName: String, adapterName: String) {
        assertEquals("S-current", snapshotName)
        assertEquals("A-current", adapterName)
        currentSnapshot = snapshot(snapshotName)
        currentAdapter = JavaLanguageAdapter()
    }

    @Given("^one immutable complete typed \"([^\"]+)\" command contains exactly (.+)$")
    fun completeTypedCommand(commandName: String, completeValues: String) {
        currentCommand = command(commandName)
        assertTrue(completeValues.isNotBlank())
        assertEquals(commandName, route(currentCommand))
        assertCompleteCommandValues(currentCommand)
    }

    @Given("^the exact existing planner call (.+) will return the distinct immutable PatchPlan \"([^\"]+)\"$")
    fun exactPlannerResult(plannerCall: String, planName: String) {
        expectedPlannerCall = plannerCall
        assertTrue(plannerCall.contains(expectedPlannerClass(route(currentCommand))))
        expectedPlan = sentinelPlan(planName, currentSnapshot, route(currentCommand))
        recordingInvoker = RecordingPlannerInvoker(returnedPlan = expectedPlan)
    }

    @When("the command is previewed through the Java refactoring preview command boundary")
    fun previewCommandThroughBoundary() {
        returnedPlan = JavaRefactoringPreviewDispatcher().preview(
            currentSnapshot,
            currentAdapter,
            currentCommand,
            recordingInvoker,
        )
    }

    @Then("exactly that existing planner is selected and invoked exactly once")
    fun exactlyOneSelectedPlanner() {
        val invocation = recordingInvoker.invocations.single()
        assertEquals(route(currentCommand), invocation.route)
        assertTrue(expectedPlannerCall.contains(expectedPlannerClass(invocation.route)))
    }

    @Then("no other Java planner is invoked")
    fun noOtherPlannerInvocation() {
        assertEquals(1, recordingInvoker.invocations.size)
    }

    @Then("the planner receives the same {string} snapshot instance and every command value exactly as supplied")
    fun exactSnapshotAndCommandValues(snapshotName: String) {
        assertEquals("S-current", snapshotName)
        val invocation = recordingInvoker.invocations.single()
        assertSame(currentSnapshot, invocation.snapshot)
        val supplied = commandValues(currentCommand)
        assertEquals(supplied, invocation.values)
        supplied.zip(invocation.values).forEach { (expected, actual) ->
            if (expected is Path || expected is String) assertSame(expected, actual)
        }
    }

    @Then("every adapter-backed planner is constructed with the same {string} Java adapter instance")
    fun exactAdapterForAdapterBackedPlanner(adapterName: String) {
        assertEquals("A-current", adapterName)
        val invocation = recordingInvoker.invocations.single()
        if (invocation.route != "OrganizeImports") assertSame(currentAdapter, invocation.adapter)
    }

    @Then("no replacement Java adapter is created for an adapter-independent planner")
    fun noAdapterForIndependentPlanner() {
        val invocation = recordingInvoker.invocations.single()
        if (invocation.route == "OrganizeImports") assertEquals(null, invocation.adapter)
        loadProductionSources()
        val organizeBody = functionText(commandSource, "organizeImports")
        assertFalse(organizeBody.contains("JavaLanguageAdapter("))
    }

    @Then("the boundary does not alias, default, parse, convert, normalize, sort, deduplicate, or semantically reinterpret an argument")
    fun boundaryDoesNotTransformArguments() {
        val invocation = recordingInvoker.invocations.single()
        assertEquals(commandValues(currentCommand), invocation.values)
        loadProductionSources()
        listOf("normalize()", "sorted", "distinct", "deduplicate", "Paths.get", "Path.of", "toBoolean")
            .forEach { forbidden -> assertFalse(dispatcherBody().contains(forbidden), forbidden) }
    }

    @Then("the returned value is the same {string} PatchPlan instance without wrapping, copying, or regenerating its identity")
    fun exactReturnedPlan(planName: String) {
        assertEquals(planName, expectedPlan.id.value)
        assertSame(expectedPlan, returnedPlan)
    }

    @Then("its operation, status, snapshot hash, summary, evidence, warnings, diagnostic order, affected files, workspace edit, authority data, confidence, risk, and refusal code remain unchanged")
    fun allPlanFieldsRemainUnchanged() {
        val actual = assertNotNull(returnedPlan)
        assertSame(expectedPlan, actual)
        assertEquals(expectedPlan.operation, actual.operation)
        assertEquals(expectedPlan.status, actual.status)
        assertEquals(expectedPlan.snapshotHash, actual.snapshotHash)
        assertEquals(expectedPlan.summary, actual.summary)
        assertEquals(expectedPlan.evidence, actual.evidence)
        assertEquals(expectedPlan.warnings, actual.warnings)
        assertEquals(expectedPlan.diagnosticsBefore, actual.diagnosticsBefore)
        assertEquals(expectedPlan.diagnosticsAfterPreview, actual.diagnosticsAfterPreview)
        assertEquals(expectedPlan.affectedFiles, actual.affectedFiles)
        assertEquals(expectedPlan.workspaceEdit, actual.workspaceEdit)
        assertSame(expectedPlan.authorityLease, actual.authorityLease)
        assertEquals(expectedPlan.confidence, actual.confidence)
        assertEquals(expectedPlan.riskLevel, actual.riskLevel)
        assertEquals(expectedPlan.refusalCode, actual.refusalCode)
        assertDefaultPlannerMapping(route(currentCommand))
    }

    @Given("complete generic RefactoringRequest values map to these complete typed commands:")
    fun completeGenericMappings(table: DataTable) {
        assertEquals(
            listOf("renameClass", "renameMember", "moveSourceRoot", "organizeImports", "safeDelete"),
            table.asMaps().map { it.getValue("operation") },
        )
        createGenericFixture()
        genericAdapter = JavaLanguageAdapter()
        genericCommands += linkedMapOf(
            "renameClass" to JavaRefactoringPreviewCommand.RenameClass(
                SymbolId("com.acme.billing.Invoice"),
                "Statement",
            ),
            "renameMember" to JavaRefactoringPreviewCommand.RenameMember(
                SymbolId("com.acme.billing.Invoice#total()"),
                "grandTotal",
            ),
            "moveSourceRoot" to JavaRefactoringPreviewCommand.MoveSourceRoot(
                Path.of("modules/legacy/src/main/java"),
                Path.of("modules/billing/src/main/java"),
            ),
            "organizeImports" to JavaRefactoringPreviewCommand.OrganizeImports(
                Path.of("src/main/java/com/acme/billing/Invoice.java"),
            ),
            "safeDelete" to JavaRefactoringPreviewCommand.SafeDelete(
                SymbolId("com.acme.legacy.ObsoleteTax"),
                true,
            ),
        )
        genericRequests += linkedMapOf(
            "renameClass" to RefactoringRequest(
                "renameClass",
                SymbolId("com.acme.billing.Invoice"),
                arguments = mapOf("newName" to "Statement"),
                snapshot = genericSnapshot,
            ),
            "renameMember" to RefactoringRequest(
                "renameMember",
                SymbolId("com.acme.billing.Invoice#total()"),
                arguments = mapOf("newName" to "grandTotal"),
                snapshot = genericSnapshot,
            ),
            "moveSourceRoot" to RefactoringRequest(
                "moveSourceRoot",
                arguments = mapOf(
                    "from" to "modules/legacy/src/main/java",
                    "to" to "modules/billing/src/main/java",
                ),
                snapshot = genericSnapshot,
            ),
            "organizeImports" to RefactoringRequest(
                "organizeImports",
                arguments = mapOf("file" to "src/main/java/com/acme/billing/Invoice.java"),
                snapshot = genericSnapshot,
            ),
            "safeDelete" to RefactoringRequest(
                "safeDelete",
                SymbolId("com.acme.legacy.ObsoleteTax"),
                arguments = mapOf("force" to "TrUe"),
                snapshot = genericSnapshot,
            ),
        )
    }

    @When("^JavaLanguageAdapter\\.applyRefactoring receives each complete generic request$")
    fun genericAdapterReceivesCompleteRequests() {
        genericRequests.forEach { (operation, request) ->
            genericResults[operation] = genericAdapter.applyRefactoring(request)
            canonicalResults[operation] = JavaRefactoringPreviewDispatcher().preview(
                genericSnapshot,
                genericAdapter,
                genericCommands.getValue(operation),
            )
        }
    }

    @Then("its existing generic string, path, and boolean decoding occurs before the typed boundary")
    fun genericDecodingPrecedesBoundary() {
        loadProductionSources()
        assertEquals(Path.of("modules/legacy/src/main/java"),
            (genericCommands.getValue("moveSourceRoot") as JavaRefactoringPreviewCommand.MoveSourceRoot).from)
        assertTrue((genericCommands.getValue("safeDelete") as JavaRefactoringPreviewCommand.SafeDelete).force)
        genericHelperNames().forEach { helper -> assertDecodeBeforeDispatch(functionText(adapterSource, helper)) }
    }

    @Then("it dispatches the resulting complete command through the same canonical mapping from {string}")
    fun genericUsesCanonicalMapping(requirement: String) {
        assertEquals("REQ-JAVA-PREVIEW-COMMAND-001", requirement)
        genericResults.forEach { (operation, plan) ->
            assertEquivalentIgnoringId(canonicalResults.getValue(operation), plan)
        }
        assertDefaultPlannerMappings()
    }

    @Then("it has no parallel direct-planner path for any of the five operations")
    fun noGenericParallelPlannerPath() {
        loadProductionSources()
        expectedPlannerClasses().forEach { planner ->
            assertFalse(adapterSource.contains(planner), "$planner remains directly reachable from JavaLanguageAdapter")
        }
        assertEquals(5, dispatcherCallCount(adapterSource))
    }

    @Then("it returns the exact PatchPlan returned by that canonical downstream invocation")
    fun genericReturnsCanonicalPlan() {
        genericResults.forEach { (operation, plan) ->
            assertEquivalentIgnoringId(canonicalResults.getValue(operation), plan)
        }
        genericHelperNames().forEach { helper ->
            val body = functionText(adapterSource, helper)
            assertFalse(body.contains(".copy("))
            assertFalse(body.contains("PatchPlan("))
        }
    }

    @When("the generic adapter instead receives an admitted operation with a missing required value or an unknown operation")
    fun genericMissingAndUnknownRequests() {
        val requests = linkedMapOf(
            "renameClass" to RefactoringRequest(
                "renameClass", arguments = mapOf("newName" to "Statement"), snapshot = genericSnapshot,
            ),
            "renameMember" to RefactoringRequest(
                "renameMember", SymbolId("com.acme.billing.Invoice#total()"), snapshot = genericSnapshot,
            ),
            "moveSourceRoot" to RefactoringRequest(
                "moveSourceRoot", arguments = mapOf("from" to "modules/legacy/src/main/java"), snapshot = genericSnapshot,
            ),
            "organizeImports" to RefactoringRequest("organizeImports", snapshot = genericSnapshot),
            "safeDelete" to RefactoringRequest("safeDelete", snapshot = genericSnapshot),
            "unknownJavaOperation" to RefactoringRequest("unknownJavaOperation", snapshot = genericSnapshot),
        )
        requests.forEach { (name, request) -> genericRefusals[name] = genericAdapter.applyRefactoring(request) }
        val falseDefault = RefactoringRequest(
            "safeDelete",
            SymbolId("com.acme.legacy.ObsoleteTax"),
            snapshot = genericSnapshot,
        )
        genericFalseDefaultResult = genericAdapter.applyRefactoring(falseDefault)
        canonicalFalseDefaultResult = JavaRefactoringPreviewDispatcher().preview(
            genericSnapshot,
            genericAdapter,
            JavaRefactoringPreviewCommand.SafeDelete(SymbolId("com.acme.legacy.ObsoleteTax"), false),
        )
    }

    @Then("its existing operation-specific missing-request and unknown-operation behavior remains generic Java adapter behavior")
    fun genericMissingAndUnknownBehavior() {
        val expectedSummaries = mapOf(
            "renameClass" to "renameClass requires symbolId",
            "renameMember" to "renameMember requires arguments.newName",
            "moveSourceRoot" to "moveSourceRoot requires arguments.to",
            "organizeImports" to "organizeImports requires arguments.file",
            "safeDelete" to "safeDelete requires symbolId",
            "unknownJavaOperation" to "Unknown operation: unknownJavaOperation",
        )
        expectedSummaries.forEach { (operation, summary) ->
            assertEquals(summary, genericRefusals.getValue(operation).summary)
        }
        assertEquivalentIgnoringId(
            assertNotNull(canonicalFalseDefaultResult),
            assertNotNull(genericFalseDefaultResult),
        )
    }

    @Then("any resulting generic refused PatchPlan retains its existing operation, summary, warnings, snapshot binding, and refusal semantics")
    fun genericRefusedPlansRemainBound() {
        genericRefusals.forEach { (operation, plan) ->
            assertEquals(operation, plan.operation)
            assertEquals(PatchStatus.REFUSED, plan.status)
            assertEquals(genericSnapshot.hash, plan.snapshotHash)
            assertEquals(listOf(plan.summary), plan.warnings)
            assertEquals(0.0, plan.confidence)
            assertFalse(plan.requiresUserApproval)
        }
    }

    @Then("no incomplete or unknown generic request is reclassified as a daemon or MCP protocol failure by the typed boundary")
    fun genericRefusalsStayOutOfProtocols() {
        loadProductionSources()
        assertFalse(commandSource.contains("JsonRpc"))
        assertFalse(commandSource.contains("Mcp"))
        genericRefusals.values.forEach { assertEquals(PatchStatus.REFUSED, it.status) }
    }

    @Then("no typed command is constructed until the generic decoder has produced every required typed value")
    fun completeValuesBeforeGenericCommandConstruction() {
        genericHelperNames().forEach { helper ->
            val body = functionText(adapterSource, helper)
            val constructor = body.indexOf("JavaRefactoringPreviewCommand.")
            assertTrue(constructor > 0, "$helper must construct a complete command")
            assertTrue(body.lastIndexOf("?: return notImplemented", constructor) in 0 until constructor, helper)
        }
    }

    @Given("one isolated admitted planner invocation returns the exact PatchPlan {string}")
    fun isolatedPlannerReturns(planName: String) {
        isolatedSuccessSnapshot = snapshot("isolated-success")
        isolatedSuccessAdapter = JavaLanguageAdapter()
        isolatedPlan = sentinelPlan(planName, isolatedSuccessSnapshot, "RenameClass")
        isolatedSuccessInvoker = RecordingPlannerInvoker(returnedPlan = isolatedPlan)
    }

    @Given("another isolated admitted planner invocation throws the exact exception object {string} with distinct type, code, message, and cause")
    fun isolatedPlannerThrows(failureName: String) {
        isolatedFailureSnapshot = snapshot("isolated-failure")
        isolatedFailureAdapter = JavaLanguageAdapter()
        plannerFailure = PlannerSentinelFailure(failureName, IllegalStateException("cause-$failureName"))
        isolatedFailureInvoker = RecordingPlannerInvoker(thrownFailure = plannerFailure)
        assertEquals(failureName, plannerFailure.code)
    }

    @Given("workspace bytes, process activity, pending plans, diagnostics calls, locks, journals, transactions, and lifecycle calls are recorded")
    fun recordNoSideEffectBaseline() {
        sideEffectRoot = temporaryWorkspace("side-effects")
        sideEffectRoot.resolve("workspace.bin").writeBytes(byteArrayOf(0, 1, 2, -1))
        sideEffectRoot.resolve("nested/source.java").also {
            it.parent.createDirectories()
            it.writeText("class Source {}\n")
        }
        sideEffectRoot.resolve(".refactorkit").createDirectories()
        sideEffectRoot.resolve(".refactorkit/workspace.lock").writeBytes(byteArrayOf(4, 5, 6))
        sideEffectRoot.resolve(".refactorkit/journal.bin").writeBytes(byteArrayOf(7, 8, 9))
        beforeWorkspace = captureTree(sideEffectRoot)
        beforeProcess = captureProcessActivity()
        beforeLock = captureLockState(sideEffectRoot.resolve(".refactorkit/workspace.lock"))
        assertTrue(assertNotNull(beforeLock).exclusivelyAvailable)
    }

    @When("both commands are previewed through the fieldless boundary")
    fun previewSuccessfulAndFailingCommands() {
        isolatedReturned = JavaRefactoringPreviewDispatcher().preview(
            isolatedSuccessSnapshot,
            isolatedSuccessAdapter,
            JavaRefactoringPreviewCommand.RenameClass(SymbolId("isolated.Type"), "Renamed"),
            isolatedSuccessInvoker,
        )
        isolatedEscaped = runCatching {
            JavaRefactoringPreviewDispatcher().preview(
                isolatedFailureSnapshot,
                isolatedFailureAdapter,
                JavaRefactoringPreviewCommand.SafeDelete(SymbolId("isolated.Obsolete"), false),
                isolatedFailureInvoker,
            )
        }.exceptionOrNull()
    }

    @Then("the first invocation returns the same {string} instance unchanged")
    fun isolatedPlanIdentity(planName: String) {
        assertEquals(planName, isolatedPlan.id.value)
        assertSame(isolatedPlan, isolatedReturned)
    }

    @Then("the same {string} object escapes the second invocation with its type, code, message, and cause unchanged")
    fun isolatedExceptionIdentity(failureName: String) {
        val escaped = assertNotNull(isolatedEscaped)
        assertEquals(failureName, plannerFailure.code)
        assertSame(plannerFailure, escaped)
        assertSame(plannerFailure.cause, escaped.cause)
        assertEquals(plannerFailure::class.java, escaped::class.java)
        assertEquals(plannerFailure.message, escaped.message)
        assertEquals(plannerFailure.code, (escaped as CodedPlannerFailure).code)
    }

    @Then("after each call the boundary retains no snapshot, adapter, command, plan, exception, cache, lease, session, or other state")
    fun boundaryRetainsNoState() {
        assertTrue(JavaRefactoringPreviewDispatcher::class.java.declaredFields.none(::isRetainedInstanceField))
        assertTrue(ExistingJavaRefactoringPreviewPlannerInvoker::class.java.declaredFields.none(::isRetainedInstanceField))
        assertEquals(1, isolatedSuccessInvoker.invocations.size)
        assertEquals(1, isolatedFailureInvoker.invocations.size)
    }

    @Then("the command catalogue contains exactly:")
    fun exactCommandCatalogue(table: DataTable) {
        val expected = table.asMaps().map { it.getValue("admitted plan-only Java command") }
        assertEquals(
            listOf("RenameClass", "RenameMember", "MoveSourceRoot", "OrganizeImports for one file", "SafeDelete"),
            expected,
        )
        val actual = JavaRefactoringPreviewCommand::class.java.declaredClasses
            .filter { JavaRefactoringPreviewCommand::class.java.isAssignableFrom(it) }
            .map(Class<*>::getSimpleName)
            .sorted()
        assertEquals(listOf("MoveSourceRoot", "OrganizeImports", "RenameClass", "RenameMember", "SafeDelete"), actual)
        JavaRefactoringPreviewCommand::class.java.declaredClasses
            .filter { JavaRefactoringPreviewCommand::class.java.isAssignableFrom(it) }
            .flatMap { it.declaredFields.toList() }
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .forEach { field -> assertTrue(Modifier.isFinal(field.modifiers), "${field.declaringClass.simpleName}.${field.name}") }
    }

    @Then("it has no command variant, route, or adoption ownership for:")
    fun excludedCommandScope(table: DataTable) {
        loadProductionSources()
        assertEquals(7, table.asMaps().size)
        listOf(
            "MoveClass", "Guidance", "LexicalReview", "Kotlin", "TypeScript", "JavaScript",
            "ExtractMethod", "ChangeSignature", "Maven", "MultiFile", "CLI", "LSP",
        ).forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
        assertMainSourcesExcludeBoundary("modules/refactorkit-cli/src/main")
        assertMainSourcesExcludeBoundary("modules/refactorkit-lsp/src/main")
    }

    @Then("it performs no protocol parsing or rendering and no pending-plan admission, lookup, mutation, removal, or clearing")
    fun noProtocolOrPendingOwnership() {
        loadProductionSources()
        listOf("Json", "Protocol", "render", "PendingPlan", "insert", "lookup", "remove", "clear")
            .forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
    }

    @Then("it performs no filesystem write and independently executes no diagnostics")
    fun noWriteOrDiagnostics() {
        loadProductionSources()
        listOf("java.nio.file.Files", "write", "diagnostics(", "WorkspaceEditSimulator")
            .forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
        assertEquals(beforeWorkspace, captureTree(sideEffectRoot))
        assertEquals(beforeLock, captureLockState(sideEffectRoot.resolve(".refactorkit/workspace.lock")))
        val afterProcess = captureProcessActivity()
        assertEquals(assertNotNull(beforeProcess).processId, afterProcess.processId)
        assertTrue(afterProcess.descendants.minus(assertNotNull(beforeProcess).descendants).isEmpty())
    }

    @Then("it invokes no PatchEngine, workspace lock, write-ahead log, transaction, recovery, managed apply, automatic rollback, or explicit rollback")
    fun noManagedWriteAuthority() {
        listOf("PatchEngine", "workspace.lock", "write-ahead", "Transaction", "recovery", "apply(", "rollback")
            .forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
    }

    @Then("it owns no snapshot refresh, adapter or session lifecycle, watcher, index, process, broad operation catalogue, or persistence mechanism")
    fun noLifecycleOrPersistenceOwnership() {
        listOf("refresh", "close(", "start(", "watch", "Index", "Process", "Repository", "Store", "persist", "cache")
            .forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
        assertTrue(JavaRefactoringPreviewDispatcher::class.java.declaredFields.none(::isRetainedInstanceField))
    }

    @Then("preview dispatch ends at PatchPlan return or unchanged planner exception without producing an approval, apply identity, or combined preview-and-apply request")
    fun dispatchEndsAtPlanOrException() {
        assertSame(isolatedPlan, isolatedReturned)
        assertSame(plannerFailure, isolatedEscaped)
        listOf("ApplyAuthorization", "RefactoringApplyIdentity", "Approval", "Transaction")
            .forEach { forbidden -> assertFalse(commandSource.contains(forbidden), forbidden) }
    }

    private fun command(name: String): JavaRefactoringPreviewCommand = when (name) {
        "RenameClass" -> JavaRefactoringPreviewCommand.RenameClass(
            SymbolId("com.acme.billing.Invoice"),
            distinctString("Statement"),
        )
        "RenameMember" -> JavaRefactoringPreviewCommand.RenameMember(
            SymbolId("com.acme.billing.Invoice#total()"),
            distinctString("grandTotal"),
        )
        "MoveSourceRoot" -> JavaRefactoringPreviewCommand.MoveSourceRoot(
            Path.of("modules/legacy/src/main/java"),
            Path.of("modules/billing/src/main/java"),
        )
        "OrganizeImports" -> JavaRefactoringPreviewCommand.OrganizeImports(
            Path.of("src/main/java/com/acme/billing/Invoice.java"),
        )
        "SafeDelete" -> JavaRefactoringPreviewCommand.SafeDelete(
            SymbolId("com.acme.legacy.ObsoleteTax"),
            true,
        )
        else -> fail("unexpected command $name")
    }

    private fun route(command: JavaRefactoringPreviewCommand): String = when (command) {
        is JavaRefactoringPreviewCommand.RenameClass -> "RenameClass"
        is JavaRefactoringPreviewCommand.RenameMember -> "RenameMember"
        is JavaRefactoringPreviewCommand.MoveSourceRoot -> "MoveSourceRoot"
        is JavaRefactoringPreviewCommand.OrganizeImports -> "OrganizeImports"
        is JavaRefactoringPreviewCommand.SafeDelete -> "SafeDelete"
    }

    private fun commandValues(command: JavaRefactoringPreviewCommand): List<Any> = when (command) {
        is JavaRefactoringPreviewCommand.RenameClass -> listOf(command.symbolId, command.newName)
        is JavaRefactoringPreviewCommand.RenameMember -> listOf(command.symbolId, command.newName)
        is JavaRefactoringPreviewCommand.MoveSourceRoot -> listOf(command.from, command.to)
        is JavaRefactoringPreviewCommand.OrganizeImports -> listOf(command.file)
        is JavaRefactoringPreviewCommand.SafeDelete -> listOf(command.symbolId, command.force)
    }

    private fun assertCompleteCommandValues(command: JavaRefactoringPreviewCommand) {
        when (command) {
            is JavaRefactoringPreviewCommand.RenameClass -> {
                assertEquals("com.acme.billing.Invoice", command.symbolId.value)
                assertEquals("Statement", command.newName)
            }
            is JavaRefactoringPreviewCommand.RenameMember -> {
                assertEquals("com.acme.billing.Invoice#total()", command.symbolId.value)
                assertEquals("grandTotal", command.newName)
            }
            is JavaRefactoringPreviewCommand.MoveSourceRoot -> {
                assertEquals("modules/legacy/src/main/java", command.from.toString())
                assertEquals("modules/billing/src/main/java", command.to.toString())
            }
            is JavaRefactoringPreviewCommand.OrganizeImports ->
                assertEquals("src/main/java/com/acme/billing/Invoice.java", command.file.toString())
            is JavaRefactoringPreviewCommand.SafeDelete -> {
                assertEquals("com.acme.legacy.ObsoleteTax", command.symbolId.value)
                assertTrue(command.force)
            }
        }
    }

    private fun sentinelPlan(name: String, snapshot: ProjectSnapshot, route: String): PatchPlan = PatchPlan(
        id = PlanId(name),
        operation = "sentinel-$route",
        status = PatchStatus.PREVIEW,
        snapshotHash = snapshot.hash,
        confidence = 0.37,
        requiresUserApproval = true,
        summary = "distinct summary for $name",
        affectedFiles = linkedSetOf(Path.of("z-$name.java"), Path.of("a-$name.java")),
        workspaceEdit = WorkspaceEdit(listOf(FileEdit.Create(Path.of("created-$name.java"), "// $name\n"))),
        diagnosticsBefore = listOf(
            Diagnostic("before-second-$name", Diagnostic.Severity.WARNING, code = "before-2"),
            Diagnostic("before-first-$name", Diagnostic.Severity.INFO, code = "before-1"),
        ),
        diagnosticsAfterPreview = listOf(
            Diagnostic("after-second-$name", Diagnostic.Severity.ERROR, code = "after-2"),
            Diagnostic("after-first-$name", Diagnostic.Severity.WARNING, code = "after-1"),
        ),
        warnings = listOf("warning-z-$name", "warning-a-$name"),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.NATIVE_AST,
        refusalCode = "sentinel-code-$name",
        authorityLease = OperationAuthorityLease(
            kind = "sentinel",
            operation = "sentinel-$route",
            snapshotHash = snapshot.hash,
            evidenceHash = "a".repeat(64),
            attributes = mapOf("plan" to name),
        ),
    )

    private fun createGenericFixture() {
        val root = temporaryWorkspace("generic")
        val files = listOf(
            SourceFile(
                Path.of("src/main/java/com/acme/billing/Invoice.java"),
                """package com.acme.billing;
                    |
                    |import java.util.List;
                    |import java.io.File;
                    |
                    |public class Invoice {
                    |    public int total() { return 1; }
                    |}
                    |""".trimMargin(),
                "java",
            ),
            SourceFile(
                Path.of("src/main/java/com/acme/legacy/ObsoleteTax.java"),
                "package com.acme.legacy;\npublic class ObsoleteTax {}\n",
                "java",
            ),
            SourceFile(
                Path.of("src/main/java/com/acme/app/TaxUse.java"),
                "package com.acme.app;\nimport com.acme.legacy.ObsoleteTax;\nclass TaxUse { ObsoleteTax value; }\n",
                "java",
            ),
        )
        files.forEach { file ->
            root.resolve(file.path).also { target ->
                target.parent.createDirectories()
                target.writeText(file.content)
            }
        }
        genericSnapshot = ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module("fixture", root, sourceRoots = listOf(Path.of("src/main/java")))),
            files = files,
        )
    }

    private fun snapshot(name: String): ProjectSnapshot {
        val root = temporaryWorkspace(name)
        return ProjectSnapshot(
            workspace = Workspace(root),
            modules = listOf(Module(name, root)),
            files = emptyList(),
        )
    }

    private fun assertEquivalentIgnoringId(expected: PatchPlan, actual: PatchPlan) {
        assertEquals(expected.copy(id = actual.id), actual)
    }

    private fun genericHelperNames(): List<String> = listOf(
        "applyRenameClass",
        "applyRenameMember",
        "applyMoveSourceRoot",
        "applyOrganizeImports",
        "applySafeDelete",
    )

    private fun assertDecodeBeforeDispatch(body: String) {
        val commandIndex = body.indexOf("JavaRefactoringPreviewCommand.")
        val dispatchIndex = body.indexOf("JavaRefactoringPreviewDispatcher")
        assertTrue(commandIndex > 0)
        assertTrue(dispatchIndex > commandIndex)
        assertTrue(body.lastIndexOf("?: return notImplemented", commandIndex) in 0 until commandIndex)
    }

    private fun expectedPlannerClass(route: String): String = when (route) {
        "RenameClass" -> "JavaRenameClassPlanner"
        "RenameMember" -> "JavaRenameMemberPlanner"
        "MoveSourceRoot" -> "JavaMoveSourceRootPlanner"
        "OrganizeImports" -> "JavaOrganizeImportsPlanner"
        "SafeDelete" -> "JavaSafeDeletePlanner"
        else -> fail("unexpected route $route")
    }

    private fun expectedPlannerClasses(): List<String> = listOf(
        "JavaRenameClassPlanner",
        "JavaRenameMemberPlanner",
        "JavaMoveSourceRootPlanner",
        "JavaOrganizeImportsPlanner",
        "JavaSafeDeletePlanner",
    )

    private fun assertDefaultPlannerMapping(route: String) {
        loadProductionSources()
        val expected = when (route) {
            "RenameClass" -> "JavaRenameClassPlanner(javaAdapter).preview(snapshot, symbolId.value, newName)"
            "RenameMember" -> "JavaRenameMemberPlanner(javaAdapter).preview(snapshot, symbolId.value, newName)"
            "MoveSourceRoot" -> "JavaMoveSourceRootPlanner(javaAdapter).preview(snapshot, from, to)"
            "OrganizeImports" -> "JavaOrganizeImportsPlanner().previewSingleFile(snapshot, file)"
            "SafeDelete" -> "JavaSafeDeletePlanner(javaAdapter).preview(snapshot, symbolId.value, force)"
            else -> fail("unexpected route $route")
        }
        assertTrue(compact(commandSource).contains(compact(expected)), "missing default mapping for $route")
    }

    private fun assertDefaultPlannerMappings() {
        listOf("RenameClass", "RenameMember", "MoveSourceRoot", "OrganizeImports", "SafeDelete")
            .forEach(::assertDefaultPlannerMapping)
    }

    private fun dispatcherCallCount(source: String): Int =
        Regex("JavaRefactoringPreviewDispatcher\\s*\\(\\s*\\)\\s*\\.preview\\s*\\(").findAll(source).count()

    private fun dispatcherBody(): String {
        loadProductionSources()
        val publicPreview = Regex("(?m)^    fun preview\\(").find(commandSource)
            ?: fail("public dispatcher preview not found")
        val internalPreview = Regex("(?m)^    internal fun preview\\(").find(commandSource, publicPreview.range.last + 1)
            ?: fail("internal dispatcher preview not found")
        return commandSource.substring(internalPreview.range.first)
    }

    private fun loadProductionSources() {
        if (::repositoryRoot.isInitialized) return
        repositoryRoot = locateRepositoryRoot()
        commandSource = source("modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaRefactoringPreviewCommand.kt")
        adapterSource = source("modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaLanguageAdapter.kt")
    }

    private fun functionText(source: String, name: String): String {
        val declaration = Regex("(?m)^    (?:private |internal |override )?fun ${Regex.escape(name)}[ \\t]*\\(")
        val match = declaration.find(source) ?: fail("function $name not found")
        val next = Regex("(?m)^    (?:private |internal |override )?fun ").find(source, match.range.last + 1)
        return source.substring(match.range.first, next?.range?.first ?: source.length)
    }

    private fun source(relative: String): String = repositoryRoot.resolve(relative).toFile().readText()

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").exists() && candidate.resolve("modules/refactorkit-java").exists()) {
                return candidate
            }
            candidate = candidate.parent
        }
        fail("repository root not found")
    }

    private fun assertMainSourcesExcludeBoundary(relativeDirectory: String) {
        val directory = repositoryRoot.resolve(relativeDirectory)
        Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.forEach { path ->
                val text = path.toFile().readText()
                assertFalse(text.contains("JavaRefactoringPreviewCommand"), path.toString())
                assertFalse(text.contains("JavaRefactoringPreviewDispatcher"), path.toString())
            }
        }
    }

    private fun temporaryWorkspace(name: String): Path = Files.createTempDirectory("refactorkit-preview-$name-")
        .toAbsolutePath().normalize().also(temporaryRoots::add)

    private fun captureTree(root: Path): Map<String, String> {
        val result = linkedMapOf<String, String>()
        Files.walk(root).use { stream ->
            stream.sorted().forEach { path ->
                val relative = root.relativize(path).toString().ifBlank { "." }
                result[relative] = if (Files.isDirectory(path)) "directory" else
                    "file:${Base64.getEncoder().encodeToString(Files.readAllBytes(path))}"
            }
        }
        return result
    }

    private fun captureProcessActivity(): ProcessActivity {
        val current = ProcessHandle.current()
        val descendants = sortedSetOf<Long>()
        current.descendants().use { stream -> stream.forEach { descendants += it.pid() } }
        return ProcessActivity(current.pid(), descendants)
    }

    private fun captureLockState(path: Path): LockState {
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
        return LockState(exists, available)
    }

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun isRetainedInstanceField(field: java.lang.reflect.Field): Boolean =
        !field.isSynthetic && !Modifier.isStatic(field.modifiers)

    private fun compact(value: String): String = value.filterNot(Char::isWhitespace)

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun distinctString(value: String): String = java.lang.String(value) as String
}

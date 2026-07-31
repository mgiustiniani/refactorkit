package org.refactorkit.cli.managedrollback

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.ApprovalRecord
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.FileImage
import org.refactorkit.core.JournalEvent
import org.refactorkit.core.JournalState
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ManagedRollbackExecutor
import org.refactorkit.core.ManagedRollbackOutcome
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PlanId
import org.refactorkit.core.RollbackLookupVisibility
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.RollbackPreflightDecision
import org.refactorkit.core.RollbackPreflightGuard
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.TransactionLogException
import org.refactorkit.core.WorkspaceEdit
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ManagedRollbackExecutorSteps {
    private data class ObservedFile(
        val content: List<Byte>?,
        val permissions: Set<PosixFilePermission>?,
        val lastModifiedMillis: Long?,
        val ownerName: String?,
        val groupName: String?,
    )

    private data class WorkspaceObservation(
        val files: Map<String, ObservedFile>,
        val existingCreatedDirectories: Set<String>,
    )

    private class ExecutionProbe {
        val events = mutableListOf<String>()
        val parserInputs = mutableListOf<String>()
        val outerLookupIds = mutableListOf<TransactionId>()
        val outerLookupResults = mutableListOf<TransactionJournalRecord?>()
        val lookupBeforeWorkspaceLock = mutableListOf<Boolean>()
        val guardRecords = mutableListOf<TransactionJournalRecord>()
        val guardBeforeWorkspaceLock = mutableListOf<Boolean>()
        val rollbackTransactions = mutableListOf<Transaction>()
        val rollbackModes = mutableListOf<RollbackMode>()
        var rollbackCalls = 0
        var realPatchEngineDelegations = 0
        var workspaceLockAcquisitions = 0
        var engineResult: ApplyResult? = null
        var workspaceAtRollbackCall: WorkspaceObservation? = null
        var journalBytesAtRollbackCall: ByteArray? = null
    }

    private val temporaryRoots = mutableListOf<Path>()
    private lateinit var workspaceRoot: Path
    private lateinit var transactionLog: TransactionLog
    private lateinit var patchEngine: PatchEngine
    private var rawTransactionId: String = ""
    private var journalCondition: String = ""
    private var guardBehavior: String = ""
    private var visibility: RollbackLookupVisibility = RollbackLookupVisibility.APPLIED_ONLY
    private var rollbackMode: RollbackMode = RollbackMode.NORMAL
    private var expectedRecord: TransactionJournalRecord? = null
    private var expectedPreflightRejection: JsonRpcException? = null
    private var expectedLookupFailure: TransactionLogException? = null
    private var expectedRollbackCallFailure: TransactionLogException? = null
    private var outcome: ManagedRollbackOutcome<JsonRpcException>? = null
    private var refusalDiagnosticsSourceAlias: MutableList<Diagnostic>? = null
    private var refusalDiagnosticsBaseline: List<Diagnostic>? = null
    private var probe = ExecutionProbe()
    private var workspaceBeforeExecution: WorkspaceObservation? = null
    private var journalBytesBeforeExecution: ByteArray? = null
    private var callerPolicySentinelsBefore: Map<String, List<Byte>> = emptyMap()

    @After
    fun deleteTemporaryWorkspaces() {
        temporaryRoots.asReversed().forEach(::deleteTree)
        temporaryRoots.clear()
    }

    @Given("the normalized workspace journal contains one complete {string} record for raw ID {string}")
    fun normalizedJournalContainsCompleteRecord(state: String, rawId: String) {
        assertEquals("APPLIED", state)
        initializeWorkspace("managed-rollback-success")
        rawTransactionId = rawId
        journalCondition = "one complete ID-matching APPLIED record exists"
        prepareCompleteRecord(JournalState.APPLIED)
        captureExecutionBaseline()

        assertEquals(
            workspaceRoot.resolve(".refactorkit/transactions").toAbsolutePath().normalize(),
            transactionLog.logDir,
        )
        assertEquals(rawId, assertNotNull(expectedRecord).transaction.id.value)
    }

    @Given("the record has distinct fixture values for every complete-record component:")
    fun recordHasDistinctCompleteValues(table: DataTable) {
        val expectedComponents = listOf(
            "schema, implementation, and API versions",
            "transaction, including its rollback edit",
            "operation and forward edit",
            "pre-images, post-images, and created directories",
            "pre-snapshot and post-snapshot hashes",
            "state, history, updated timestamp, and failure detail",
        )
        assertEquals(
            expectedComponents,
            table.asMaps().map { row -> row.getValue("complete-record component") },
        )
        assertCompleteRecord(assertNotNull(expectedRecord), JournalState.APPLIED)
    }

    @Given("the caller supplies {string}, mode {string}, and an allow guard")
    fun callerSuppliesVisibilityModeAndAllowGuard(visibility: String, mode: String) {
        this.visibility = RollbackLookupVisibility.valueOf(visibility)
        rollbackMode = RollbackMode.valueOf(mode)
        guardBehavior = "allow"
    }

    @When("the language-neutral executor handles the raw ID with TransactionLog and the real PatchEngine for that workspace")
    fun executorHandlesWithRealJournalAndEngine() {
        executeManagedRollback()
    }

    @Then("the executor-owned sequence is exactly:")
    fun executorOwnedSequenceIsExact(table: DataTable) {
        val rows = table.asMaps()
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), rows.map { it.getValue("order") })
        assertEquals(6, rows.map { it.getValue("observable action") }.distinct().size)

        assertEquals(listOf(rawTransactionId), probe.parserInputs)
        assertEquals(listOf(assertNotNull(TransactionId.parseOrNull(rawTransactionId))), probe.outerLookupIds)
        assertEquals(1, probe.outerLookupResults.size)
        assertEquals(JournalState.APPLIED, assertNotNull(probe.outerLookupResults.single()).state)
        assertEquals(listOf(true), probe.lookupBeforeWorkspaceLock)
        assertEquals(listOf(true), probe.guardBeforeWorkspaceLock)
        assertEquals(listOf(rollbackMode), probe.rollbackModes)
        assertSame(assertNotNull(probe.outerLookupResults.single()).transaction, probe.rollbackTransactions.single())
        assertEquals(1, probe.rollbackCalls)
        assertEquals(1, probe.realPatchEngineDelegations)
        assertEquals(1, probe.workspaceLockAcquisitions)
        assertEquals(
            listOf("parseOrNull", "outer loadRecord", "guard", "rollback", "outcome"),
            probe.events,
        )
    }

    @Then("PatchEngine alone performs its under-lock reload, recovery, state, image, conflict, force, metadata, and journal-transition rules")
    fun patchEngineAloneOwnsRollbackRules() {
        assertEquals(1, probe.realPatchEngineDelegations)
        assertIs<ApplyResult.Applied>(assertNotNull(probe.engineResult))
        assertEquals(workspaceBeforeExecution, probe.workspaceAtRollbackCall)
        assertContentEquals(
            assertNotNull(journalBytesBeforeExecution),
            assertNotNull(probe.journalBytesAtRollbackCall),
        )

        val freshRecord = loadFreshRecord()
        assertEquals(1, freshRecord.history.count { it.state == JournalState.ROLLING_BACK })
        assertEquals(1, freshRecord.history.count { it.state == JournalState.ROLLED_BACK })
        assertEquals(
            listOf(JournalState.ROLLING_BACK, JournalState.ROLLED_BACK),
            freshRecord.history.takeLast(2).map(JournalEvent::state),
        )
    }

    @Then("before the outcome returns, a newly opened journal reads {string} and the workspace paths, bytes, metadata, and created-directory state match the recorded pre-apply image")
    fun durableRollbackMatchesPreApplyImage(state: String) {
        val freshRecord = loadFreshRecord()
        assertEquals(JournalState.valueOf(state), freshRecord.state)
        assertEquals(JournalState.ROLLED_BACK, freshRecord.state)

        val resolved = assertNotNull(expectedRecord)
        resolved.preImages.forEach(::assertFileMatchesImage)
        resolved.createdDirectories.forEach { relative ->
            assertFalse(Files.exists(workspaceRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS), relative.toString())
        }
    }

    @Then("the outcome is {string} with the exact transaction from ApplyResult.Applied and the same complete resolved pre-lock record value")
    fun successfulOutcomeRetainsExactEngineAndLookupValues(expectedType: String) {
        assertEquals("RolledBack", expectedType)
        val actual = assertIs<ManagedRollbackOutcome.RolledBack>(requireOutcome())
        val applied = assertIs<ApplyResult.Applied>(assertNotNull(probe.engineResult))
        assertSame(applied.transaction, actual.appliedTransaction)
        assertSame(probe.guardRecords.single(), actual.resolvedRecord)
        assertSame(assertNotNull(probe.outerLookupResults.single()), actual.resolvedRecord)
        assertEquals(assertNotNull(expectedRecord), actual.resolvedRecord)
        assertCompleteRecord(actual.resolvedRecord, JournalState.APPLIED)
    }

    @Then("the retained resolved record still says {string} and is described as lookup evidence rather than current or post-rollback state")
    fun retainedRecordIsPreLockEvidence(state: String) {
        val actual = assertIs<ManagedRollbackOutcome.RolledBack>(requireOutcome())
        assertEquals(JournalState.valueOf(state), actual.resolvedRecord.state)
        val freshRecord = loadFreshRecord()
        assertEquals(JournalState.ROLLED_BACK, freshRecord.state)
        assertTrue(actual.resolvedRecord !== freshRecord)
        assertNotEquals(actual.resolvedRecord.state, freshRecord.state)
    }

    @Then("the executor neither reloads nor reconstructs that resolved record for the outcome")
    fun executorDoesNotReloadOrReconstructOutcomeRecord() {
        val actual = assertIs<ManagedRollbackOutcome.RolledBack>(requireOutcome())
        assertEquals(1, probe.outerLookupResults.size)
        assertSame(assertNotNull(probe.outerLookupResults.single()), probe.guardRecords.single())
        assertSame(probe.guardRecords.single(), actual.resolvedRecord)
    }

    @Then("the executor performs none of these caller-owned policies:")
    fun executorPerformsNoCallerOwnedPolicies(table: DataTable) {
        val expectedPolicies = listOf(
            "select NORMAL or FORCE mode",
            "render a response, error, exit code, tool text, or changed-file projection",
            "refresh a project, snapshot, symbol index, or semantic adapter",
            "run, publish, or render diagnostics",
            "remove or clear pending plans, audit state, or semantic sessions",
        )
        assertEquals(
            expectedPolicies,
            table.asMaps().map { row -> row.getValue("caller-owned policy") },
        )
        assertNoCallerPolicyEffects()
        assertEquals(RollbackMode.NORMAL, rollbackMode)
        assertEquals(listOf(RollbackMode.NORMAL), probe.rollbackModes)
    }

    @Given("surface {string} submits raw transaction ID {string}")
    fun surfaceSubmitsRawTransactionId(surface: String, rawId: String) {
        assertTrue(
            surface in SUPPORTED_SURFACE_LABELS,
            "Unsupported rollback surface example label: $surface",
        )
        initializeWorkspace("managed-rollback-outline")
        rawTransactionId = rawId
    }

    @Given("its initial journal condition is {string} with visibility {string} and guard behavior {string}")
    fun initialJournalConditionVisibilityAndGuard(
        condition: String,
        visibility: String,
        guard: String,
    ) {
        journalCondition = condition
        this.visibility = when (visibility) {
            "APPLIED_ONLY" -> RollbackLookupVisibility.APPLIED_ONLY
            "JOURNAL_RECORD" -> RollbackLookupVisibility.JOURNAL_RECORD
            "caller-selected" -> RollbackLookupVisibility.APPLIED_ONLY
            else -> error("Unknown rollback lookup visibility: $visibility")
        }
        guardBehavior = guard

        when {
            condition == "journal must not be accessed" -> Unit
            condition == "no ID-matching record exists" -> Unit
            condition.startsWith("outer loadRecord throws exact TransactionLogException E_lookup") -> {
                val cause = IllegalStateException("lookup-cause-fixture")
                expectedLookupFailure = TransactionLogException(
                    "transaction.lookupFixture",
                    "E_lookup exact lookup failure",
                    cause,
                )
            }
            condition.startsWith("outer loadRecord resolves one complete APPLIED record") -> {
                prepareCompleteRecord(JournalState.APPLIED)
                val cause = IllegalStateException("rollback-call-cause-fixture")
                expectedRollbackCallFailure = TransactionLogException(
                    "transaction.rollbackCallFixture",
                    "E_call exact rollback-call journal failure",
                    cause,
                )
            }
            condition.contains("one complete ID-matching ROLLED_BACK record exists") -> {
                prepareCompleteRecord(JournalState.ROLLED_BACK)
            }
            condition.contains("one complete ID-matching APPLIED record exists") -> {
                prepareCompleteRecord(JournalState.APPLIED)
            }
            else -> error("Unknown journal fixture condition: $condition")
        }

        if (guard.contains("dirty-document-first")) {
            expectedPreflightRejection = JsonRpcException(
                JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH,
                "Managed apply is refused while documents have unsaved content: [unrelated/Dirty.java]",
            )
        }
        captureExecutionBaseline()
    }

    @When("the executor handles the request with the caller-supplied {string} mode")
    fun executorHandlesCallerSuppliedMode(mode: String) {
        rollbackMode = RollbackMode.valueOf(mode)
        executeManagedRollback()
    }

    @Then("its observable work is exactly {string}")
    fun observableWorkIsExact(expectedWork: String) {
        assertEquals(expectedCount(expectedWork, "parseOrNull"), probe.parserInputs.size)
        assertEquals(expectedCount(expectedWork, "outer loadRecord"), probe.outerLookupIds.size)
        assertEquals(expectedCount(expectedWork, "guard"), probe.guardRecords.size)
        assertEquals(expectedCount(expectedWork, "real rollback"), probe.rollbackCalls)
        assertEquals(expectedCount(expectedWork, "workspace lock"), probe.workspaceLockAcquisitions)

        if (probe.parserInputs.isNotEmpty()) assertEquals(listOf(rawTransactionId), probe.parserInputs)
        if (expectedWork.contains("before lock")) assertTrue(probe.lookupBeforeWorkspaceLock.all { it })
        if (expectedWork.contains("guard=1 before lock")) {
            assertEquals(listOf(true), probe.guardBeforeWorkspaceLock)
        }
        if (expectedWork.contains("visibility hides record")) {
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(probe.outerLookupResults.single()).state)
            assertIs<ManagedRollbackOutcome.TransactionNotFound>(requireOutcome())
        }

        val expectedLocks = expectedCount(expectedWork, "workspace lock")
        assertEquals(expectedLocks > 0, Files.exists(workspaceLockPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("its exact typed outcome is {string}")
    fun exactTypedOutcomeIs(expected: String) {
        when (expected) {
            "InvalidTransactionId carrying the exact unnormalized raw ID" -> {
                val actual = assertIs<ManagedRollbackOutcome.InvalidTransactionId>(requireOutcome())
                assertEquals(rawTransactionId, actual.rawId)
            }
            "TransactionNotFound with the parsed ID and APPLIED_ONLY visibility" -> {
                val actual = assertIs<ManagedRollbackOutcome.TransactionNotFound>(requireOutcome())
                assertEquals(assertNotNull(TransactionId.parseOrNull(rawTransactionId)), actual.parsedId)
                assertEquals(RollbackLookupVisibility.APPLIED_ONLY, actual.visibility)
            }
            "Refused with the exact resolved record and exact ordered transaction.notApplied diagnostic" -> {
                val actual = assertIs<ManagedRollbackOutcome.Refused>(requireOutcome())
                assertSame(assertNotNull(probe.outerLookupResults.single()), actual.resolvedRecord)
                assertIs<ApplyResult.Refused>(assertNotNull(probe.engineResult))

                val baseline = assertNotNull(refusalDiagnosticsBaseline)
                val sourceAlias = assertNotNull(refusalDiagnosticsSourceAlias)
                assertEquals(EXPECTED_REFUSAL_DIAGNOSTICS, baseline)
                assertEquals(baseline + SOURCE_ALIAS_MUTATION_DIAGNOSTIC, sourceAlias)
                assertEquals(
                    baseline,
                    actual.diagnostics,
                    "Refused diagnostics changed after execute returned when its mutable source alias was mutated",
                )
                assertFailsWith<UnsupportedOperationException>(
                    "Refused diagnostics must expose an unmodifiable list",
                ) {
                    (actual.diagnostics as MutableList<Diagnostic>).add(OUTCOME_MUTATION_DIAGNOSTIC)
                }
            }
            "PreflightRejected with the exact resolved record and unchanged surface rejection payload" -> {
                val actual = assertIs<ManagedRollbackOutcome.PreflightRejected<*>>(requireOutcome())
                assertSame(assertNotNull(probe.outerLookupResults.single()), actual.resolvedRecord)
                val rejection = assertNotNull(expectedPreflightRejection)
                assertSame(rejection, actual.surfaceRejection)
                assertEquals(JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH, rejection.code)
                assertEquals(
                    "Managed apply is refused while documents have unsaved content: [unrelated/Dirty.java]",
                    rejection.message,
                )
            }
            "JournalLookupFailed with the parsed ID and the same E_lookup" -> {
                val actual = assertIs<ManagedRollbackOutcome.JournalLookupFailed>(requireOutcome())
                assertEquals(assertNotNull(TransactionId.parseOrNull(rawTransactionId)), actual.parsedId)
                assertExactJournalFailure(assertNotNull(expectedLookupFailure), actual.failure)
            }
            "RollbackCallJournalFailed with the exact resolved record and the same E_call" -> {
                val actual = assertIs<ManagedRollbackOutcome.RollbackCallJournalFailed>(requireOutcome())
                assertSame(assertNotNull(probe.outerLookupResults.single()), actual.resolvedRecord)
                assertExactJournalFailure(assertNotNull(expectedRollbackCallFailure), actual.failure)
            }
            else -> error("Unknown expected managed rollback outcome: $expected")
        }
    }

    @Then("its resolved-record and failure evidence is {string}")
    fun resolvedRecordAndFailureEvidenceIs(expected: String) {
        when (expected) {
            "no parsed ID and no resolved record" -> {
                assertNull(TransactionId.parseOrNull(rawTransactionId))
                assertTrue(probe.outerLookupResults.isEmpty())
                assertNull(resolvedRecordOf(requireOutcome()))
            }
            "no resolved record" -> {
                assertEquals(listOf<TransactionJournalRecord?>(null), probe.outerLookupResults)
                assertNull(resolvedRecordOf(requireOutcome()))
            }
            "hidden non-APPLIED record is not exposed" -> {
                assertEquals(JournalState.ROLLED_BACK, assertNotNull(expectedRecord).state)
                assertEquals(JournalState.ROLLED_BACK, assertNotNull(probe.outerLookupResults.single()).state)
                assertNull(resolvedRecordOf(requireOutcome()))
            }
            "same complete ROLLED_BACK record; no reload or reconstruction by executor" -> {
                val record = assertNotNull(resolvedRecordOf(requireOutcome()))
                assertSame(assertNotNull(probe.outerLookupResults.single()), record)
                assertSame(probe.guardRecords.single(), record)
                assertCompleteRecord(record, JournalState.ROLLED_BACK)
                assertEquals(1, probe.outerLookupResults.size)
            }
            "same complete APPLIED record; rejection precedes engine lock" -> {
                val record = assertNotNull(resolvedRecordOf(requireOutcome()))
                assertSame(assertNotNull(probe.outerLookupResults.single()), record)
                assertSame(probe.guardRecords.single(), record)
                assertCompleteRecord(record, JournalState.APPLIED)
                assertEquals(listOf(true), probe.guardBeforeWorkspaceLock)
                assertEquals(0, probe.rollbackCalls)
                assertEquals(0, probe.workspaceLockAcquisitions)
            }
            "no resolved record; exact code, message, and cause retained" -> {
                assertNull(resolvedRecordOf(requireOutcome()))
                val failure = assertNotNull(expectedLookupFailure)
                val actual = assertIs<ManagedRollbackOutcome.JournalLookupFailed>(requireOutcome())
                assertExactJournalFailure(failure, actual.failure)
            }
            "same complete APPLIED record; exact code, message, and cause retained" -> {
                val record = assertNotNull(resolvedRecordOf(requireOutcome()))
                assertSame(assertNotNull(probe.outerLookupResults.single()), record)
                assertCompleteRecord(record, JournalState.APPLIED)
                val failure = assertNotNull(expectedRollbackCallFailure)
                val actual = assertIs<ManagedRollbackOutcome.RollbackCallJournalFailed>(requireOutcome())
                assertExactJournalFailure(failure, actual.failure)
            }
            else -> error("Unknown expected managed rollback evidence: $expected")
        }
    }

    @Then("visible absence, preflight rejection, engine refusal, and journal failure are not reclassified as one another")
    fun outcomeCategoriesRemainDistinct() {
        val expectedName = when {
            rawTransactionId.contains(Regex("[A-F]")) -> "InvalidTransactionId"
            journalCondition == "no ID-matching record exists" -> "TransactionNotFound"
            visibility == RollbackLookupVisibility.APPLIED_ONLY && expectedRecord?.state == JournalState.ROLLED_BACK ->
                "TransactionNotFound"
            expectedPreflightRejection != null -> "PreflightRejected"
            expectedLookupFailure != null -> "JournalLookupFailed"
            expectedRollbackCallFailure != null -> "RollbackCallJournalFailed"
            expectedRecord?.state == JournalState.ROLLED_BACK -> "Refused"
            else -> error("No expected outcome category for fixture")
        }
        assertEquals(expectedName, requireOutcome()::class.simpleName)
    }

    @Then("the executor performs no response projection, session lifecycle, refresh, or diagnostics policy")
    fun executorPerformsNoSurfacePolicy() {
        assertNoCallerPolicyEffects()
        assertEquals(RollbackMode.NORMAL, rollbackMode)
    }

    private fun executeManagedRollback() {
        probe = ExecutionProbe()
        refusalDiagnosticsSourceAlias = null
        refusalDiagnosticsBaseline = null
        val parser: (String) -> TransactionId? = { suppliedRawId ->
            probe.events += "parseOrNull"
            probe.parserInputs += suppliedRawId
            TransactionId.parseOrNull(suppliedRawId)
        }
        val loader: (TransactionLog, TransactionId) -> TransactionJournalRecord? = { journal, id ->
            probe.events += "outer loadRecord"
            probe.outerLookupIds += id
            probe.lookupBeforeWorkspaceLock += !Files.exists(workspaceLockPath(), LinkOption.NOFOLLOW_LINKS)
            val result = when {
                journalCondition == "journal must not be accessed" ->
                    error("The journal was accessed for an invalid raw transaction ID")
                expectedLookupFailure != null -> throw assertNotNull(expectedLookupFailure)
                else -> journal.loadRecord(id)
            }
            probe.outerLookupResults += result
            result
        }
        val rollbackCall: (PatchEngine, Transaction, RollbackMode) -> ApplyResult = {
                engine,
                transaction,
                suppliedMode,
            ->
            probe.events += "rollback"
            probe.rollbackCalls += 1
            probe.rollbackTransactions += transaction
            probe.rollbackModes += suppliedMode
            probe.workspaceAtRollbackCall = observeFixtureWorkspace()
            probe.journalBytesAtRollbackCall = journalBytes(transaction.id)

            val callFailure = expectedRollbackCallFailure
            if (callFailure != null) {
                withFixtureWorkspaceLock {
                    probe.workspaceLockAcquisitions += 1
                    throw callFailure
                }
            } else {
                probe.realPatchEngineDelegations += 1
                val engineResult = engine.rollback(transaction, suppliedMode)
                probe.workspaceLockAcquisitions += 1
                val seamResult = if (usesMutableRefusalDiagnosticsSeam()) {
                    val refusal = assertIs<ApplyResult.Refused>(engineResult)
                    assertEquals(EXPECTED_REFUSAL_DIAGNOSTICS, refusal.diagnostics)
                    refusal.diagnostics.toMutableList().let { sourceAlias ->
                        refusalDiagnosticsSourceAlias = sourceAlias
                        refusalDiagnosticsBaseline = sourceAlias.toList()
                        ApplyResult.Refused(sourceAlias)
                    }
                } else {
                    engineResult
                }
                probe.engineResult = seamResult
                seamResult
            }
        }
        val guard = RollbackPreflightGuard<JsonRpcException> { record ->
            probe.events += "guard"
            probe.guardRecords += record
            probe.guardBeforeWorkspaceLock += !Files.exists(workspaceLockPath(), LinkOption.NOFOLLOW_LINKS)
            when {
                guardBehavior == "allow" -> RollbackPreflightDecision.Allow
                guardBehavior == "must not run" -> error("The guard ran despite an earlier short circuit")
                guardBehavior.contains("dirty-document-first") ->
                    RollbackPreflightDecision.Reject(assertNotNull(expectedPreflightRejection))
                else -> error("Unknown rollback preflight guard behavior: $guardBehavior")
            }
        }

        val executor = ManagedRollbackExecutor(
            transactionLog,
            patchEngine,
            parser,
            loader,
            rollbackCall,
        )
        outcome = executor.execute(
            rawTransactionId,
            visibility,
            rollbackMode,
            guard,
        )
        refusalDiagnosticsSourceAlias?.let { sourceAlias ->
            assertEquals(assertNotNull(refusalDiagnosticsBaseline), sourceAlias)
            sourceAlias += SOURCE_ALIAS_MUTATION_DIAGNOSTIC
        }
        probe.events += "outcome"
    }

    private fun usesMutableRefusalDiagnosticsSeam(): Boolean =
        visibility == RollbackLookupVisibility.JOURNAL_RECORD &&
            expectedRecord?.state == JournalState.ROLLED_BACK

    private fun initializeWorkspace(prefix: String) {
        workspaceRoot = Files.createTempDirectory(prefix).also(temporaryRoots::add)
            .toAbsolutePath().normalize()
        transactionLog = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions"))
        patchEngine = PatchEngine(workspaceRoot, transactionLog)
        createCallerPolicySentinels()
        callerPolicySentinelsBefore = readCallerPolicySentinels()
    }

    private fun prepareCompleteRecord(state: JournalState) {
        val id = assertNotNull(TransactionId.parseOrNull(VALID_TRANSACTION_ID))
        assertEquals(id.value, rawTransactionId.lowercase())
        installPostApplyWorkspaceState()

        val metadataSource = workspaceRoot.resolve(EXISTING_PATH)
        val posix = Files.getFileAttributeView(
            metadataSource,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val owner = Files.getOwner(metadataSource, LinkOption.NOFOLLOW_LINKS).name
        val group = posix?.readAttributes()?.group()?.name
        val ownerOnly = posix?.let {
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
        val ownerAndGroupRead = posix?.let {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
            )
        }
        val worldReadable = posix?.let {
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            )
        }

        val transaction = Transaction(
            id = id,
            planId = PlanId("plan-managed-rollback-complete-fixture"),
            appliedAt = Instant.parse("2026-02-03T04:05:06Z"),
            snapshotHashBefore = "transaction-snapshot-before-fixture",
            rollbackEdit = WorkspaceEdit(listOf(
                FileEdit.Delete(CREATED_PATH),
                FileEdit.Create(DELETED_PATH, PRE_DELETED_CONTENT, overwrite = true),
                FileEdit.Create(EXISTING_PATH, PRE_EXISTING_CONTENT, overwrite = true),
            )),
            approval = ApprovalRecord(
                ApprovalKind.EXPLICIT_APPLY,
                "fixture-surface",
                "fixture-actor",
                Instant.parse("2026-02-03T04:04:00Z"),
            ),
        )
        val appliedRecord = TransactionJournalRecord(
            schemaVersion = TransactionJournalRecord.CURRENT_SCHEMA_VERSION,
            implementationVersion = "fixture-implementation-version-91",
            apiVersion = "fixture-api-version-47",
            transaction = transaction,
            operation = "fixture.managedRollback.forwardOperation",
            forwardEdit = WorkspaceEdit(listOf(
                FileEdit.Modify(
                    EXISTING_PATH,
                    listOf(TextEdit(
                        SourceRange(SourcePosition(0, 0), SourcePosition(0, PRE_EXISTING_CONTENT.length)),
                        POST_EXISTING_CONTENT,
                    )),
                ),
                FileEdit.Create(CREATED_PATH, POST_CREATED_CONTENT),
                FileEdit.Delete(DELETED_PATH),
            )),
            preImages = listOf(
                FileImage(
                    EXISTING_PATH,
                    PRE_EXISTING_CONTENT,
                    posixPermissions = ownerOnly,
                    lastModifiedMillis = PRE_EXISTING_MTIME,
                    ownerName = owner,
                    groupName = group,
                ),
                FileImage(CREATED_PATH, null),
                FileImage(
                    DELETED_PATH,
                    PRE_DELETED_CONTENT,
                    posixPermissions = ownerAndGroupRead,
                    lastModifiedMillis = PRE_DELETED_MTIME,
                    ownerName = owner,
                    groupName = group,
                ),
            ),
            postImages = listOf(
                FileImage(
                    EXISTING_PATH,
                    POST_EXISTING_CONTENT,
                    posixPermissions = worldReadable,
                    lastModifiedMillis = POST_EXISTING_MTIME,
                    ownerName = owner,
                    groupName = group,
                ),
                FileImage(
                    CREATED_PATH,
                    POST_CREATED_CONTENT,
                    posixPermissions = ownerOnly,
                    lastModifiedMillis = POST_CREATED_MTIME,
                    ownerName = owner,
                    groupName = group,
                ),
                FileImage(DELETED_PATH, null),
            ),
            createdDirectories = listOf(Path.of("generated"), Path.of("generated/nested")),
            preSnapshotHash = "pre-snapshot-hash-fixture-11",
            postSnapshotHash = "post-snapshot-hash-fixture-29",
            state = JournalState.APPLIED,
            history = listOf(
                JournalEvent(
                    JournalState.PREPARED,
                    Instant.parse("2026-02-03T04:04:30Z"),
                    "fixture-prepared-detail",
                ),
                JournalEvent(
                    JournalState.APPLYING,
                    Instant.parse("2026-02-03T04:04:45Z"),
                    "fixture-applying-detail",
                ),
                JournalEvent(
                    JournalState.APPLIED,
                    Instant.parse("2026-02-03T04:05:06Z"),
                    "fixture-applied-detail",
                ),
            ),
            updatedAt = Instant.parse("2026-02-03T04:05:07Z"),
            failure = "fixture-pre-lock-failure-detail",
        )

        transactionLog.prepare(appliedRecord)
        if (state == JournalState.ROLLED_BACK) {
            transactionLog.update(appliedRecord.copy(
                state = JournalState.ROLLED_BACK,
                failure = "fixture-already-rolled-back-detail",
            ))
        } else {
            assertEquals(JournalState.APPLIED, state)
        }
        expectedRecord = assertNotNull(transactionLog.loadRecord(id))
        assertCompleteRecord(assertNotNull(expectedRecord), state)
    }

    private fun installPostApplyWorkspaceState() {
        val existing = workspaceRoot.resolve(EXISTING_PATH)
        Files.createDirectories(assertNotNull(existing.parent))
        Files.writeString(existing, POST_EXISTING_CONTENT)
        setPostMetadata(
            existing,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
            POST_EXISTING_MTIME,
        )

        val created = workspaceRoot.resolve(CREATED_PATH)
        Files.createDirectories(assertNotNull(created.parent))
        Files.writeString(created, POST_CREATED_CONTENT)
        setPostMetadata(
            created,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            POST_CREATED_MTIME,
        )
        Files.deleteIfExists(workspaceRoot.resolve(DELETED_PATH))
    }

    private fun setPostMetadata(path: Path, permissions: Set<PosixFilePermission>, mtime: Long) {
        if (Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ) != null
        ) {
            Files.setPosixFilePermissions(path, permissions)
        }
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtime))
    }

    private fun captureExecutionBaseline() {
        workspaceBeforeExecution = observeFixtureWorkspace()
        journalBytesBeforeExecution = expectedRecord?.transaction?.id?.let(::journalBytes)
    }

    private fun observeFixtureWorkspace(): WorkspaceObservation {
        val paths = listOf(EXISTING_PATH, CREATED_PATH, DELETED_PATH)
        val files = paths.associate { relative ->
            relative.toString().replace('\\', '/') to observeFile(workspaceRoot.resolve(relative))
        }
        val directories = listOf(Path.of("generated"), Path.of("generated/nested"))
            .filter { Files.exists(workspaceRoot.resolve(it), LinkOption.NOFOLLOW_LINKS) }
            .map { it.toString().replace('\\', '/') }
            .toSet()
        return WorkspaceObservation(files, directories)
    }

    private fun observeFile(path: Path): ObservedFile {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return ObservedFile(null, null, null, null, null)
        }
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        return ObservedFile(
            content = Files.readAllBytes(path).toList(),
            permissions = posix?.readAttributes()?.permissions(),
            lastModifiedMillis = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(),
            ownerName = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name,
            groupName = posix?.readAttributes()?.group()?.name,
        )
    }

    private fun assertFileMatchesImage(image: FileImage) {
        val absolute = workspaceRoot.resolve(image.path)
        val expectedContent = image.content
        if (expectedContent == null) {
            assertFalse(Files.exists(absolute, LinkOption.NOFOLLOW_LINKS), image.path.toString())
            return
        }
        assertTrue(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS), image.path.toString())
        assertContentEquals(expectedContent.toByteArray(Charsets.UTF_8), Files.readAllBytes(absolute))
        image.posixPermissions?.let { expected ->
            assertEquals(expected, Files.getPosixFilePermissions(absolute, LinkOption.NOFOLLOW_LINKS))
        }
        image.lastModifiedMillis?.let { expected ->
            assertEquals(expected, Files.getLastModifiedTime(absolute, LinkOption.NOFOLLOW_LINKS).toMillis())
        }
        image.ownerName?.let { expected ->
            assertEquals(expected, Files.getOwner(absolute, LinkOption.NOFOLLOW_LINKS).name)
        }
        image.groupName?.let { expected ->
            val view = assertNotNull(Files.getFileAttributeView(
                absolute,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ))
            assertEquals(expected, view.readAttributes().group().name)
        }
    }

    private fun assertCompleteRecord(record: TransactionJournalRecord, state: JournalState) {
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, record.schemaVersion)
        assertEquals("fixture-implementation-version-91", record.implementationVersion)
        assertEquals("fixture-api-version-47", record.apiVersion)
        assertEquals(VALID_TRANSACTION_ID, record.transaction.id.value)
        assertEquals("plan-managed-rollback-complete-fixture", record.transaction.planId.value)
        assertTrue(record.transaction.rollbackEdit.edits.isNotEmpty())
        assertEquals("fixture.managedRollback.forwardOperation", record.operation)
        assertTrue(record.forwardEdit.edits.isNotEmpty())
        assertNotEquals(record.forwardEdit, record.transaction.rollbackEdit)
        assertEquals(3, record.preImages.size)
        assertEquals(3, record.postImages.size)
        assertTrue(record.preImages.zip(record.postImages).all { (before, after) ->
            before.path == after.path && before.content != after.content
        })
        assertEquals(listOf(Path.of("generated"), Path.of("generated/nested")), record.createdDirectories)
        assertEquals("pre-snapshot-hash-fixture-11", record.preSnapshotHash)
        assertEquals("post-snapshot-hash-fixture-29", record.postSnapshotHash)
        assertNotEquals(record.preSnapshotHash, record.postSnapshotHash)
        assertEquals(state, record.state)
        assertTrue(record.history.isNotEmpty())
        assertTrue(record.history.map(JournalEvent::state).contains(state))
        assertTrue(record.updatedAt.isAfter(Instant.EPOCH))
        assertTrue(assertNotNull(record.failure).startsWith("fixture-"))
    }

    private fun loadFreshRecord(): TransactionJournalRecord {
        val id = assertNotNull(expectedRecord).transaction.id
        return assertNotNull(TransactionLog(transactionLog.logDir).loadRecord(id))
    }

    private fun journalBytes(id: TransactionId): ByteArray =
        Files.readAllBytes(transactionLog.logDir.resolve("${id.value}.json"))

    private fun workspaceLockPath(): Path = workspaceRoot.resolve(".refactorkit/workspace.lock")

    private fun <T> withFixtureWorkspaceLock(action: () -> T): T {
        val lockPath = workspaceLockPath()
        Files.createDirectories(assertNotNull(lockPath.parent))
        return FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { action() }
        }
    }

    private fun createCallerPolicySentinels() {
        val directory = workspaceRoot.resolve("caller-owned-policy")
        Files.createDirectories(directory)
        POLICY_SENTINELS.forEachIndexed { index, name ->
            Files.writeString(directory.resolve(name), "caller-policy-sentinel-$index\n")
        }
    }

    private fun readCallerPolicySentinels(): Map<String, List<Byte>> {
        val directory = workspaceRoot.resolve("caller-owned-policy")
        return POLICY_SENTINELS.associateWith { name -> Files.readAllBytes(directory.resolve(name)).toList() }
    }

    private fun assertNoCallerPolicyEffects() {
        assertEquals(callerPolicySentinelsBefore, readCallerPolicySentinels())
        assertFalse(Files.exists(workspaceRoot.resolve("caller-owned-policy/response-rendered")))
        assertFalse(Files.exists(workspaceRoot.resolve("caller-owned-policy/project-refreshed")))
        assertFalse(Files.exists(workspaceRoot.resolve("caller-owned-policy/diagnostics-published")))
        assertFalse(Files.exists(workspaceRoot.resolve("caller-owned-policy/session-cleared")))
    }

    private fun expectedCount(work: String, label: String): Int {
        val match = Regex("\\b${Regex.escape(label)}=(\\d+)").find(work)
        return assertNotNull(match, "Missing '$label' count in '$work'").groupValues[1].toInt()
    }

    private fun assertExactJournalFailure(
        expected: TransactionLogException,
        actual: TransactionLogException,
    ) {
        assertSame(expected, actual)
        assertEquals(expected.code, actual.code)
        assertEquals(expected.message, actual.message)
        assertSame(expected.cause, actual.cause)
    }

    private fun resolvedRecordOf(
        actual: ManagedRollbackOutcome<JsonRpcException>,
    ): TransactionJournalRecord? = when (actual) {
        is ManagedRollbackOutcome.PreflightRejected<*> -> actual.resolvedRecord
        is ManagedRollbackOutcome.RollbackCallJournalFailed -> actual.resolvedRecord
        is ManagedRollbackOutcome.RolledBack -> actual.resolvedRecord
        is ManagedRollbackOutcome.Refused -> actual.resolvedRecord
        else -> null
    }

    private fun requireOutcome(): ManagedRollbackOutcome<JsonRpcException> = assertNotNull(outcome)

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path ->
                runCatching { Files.deleteIfExists(path) }
            }
        }
    }

    private companion object {
        val SUPPORTED_SURFACE_LABELS = setOf(
            "any rollback surface",
            "CLI",
            "CLI, LSP, and MCP",
            "daemon",
            "LSP",
        )
        const val VALID_TRANSACTION_ID = "transaction-123e4567-e89b-42d3-a456-426614174000"
        val EXISTING_PATH: Path = Path.of("src/main/java/example/Managed.txt")
        val CREATED_PATH: Path = Path.of("generated/nested/Created.txt")
        val DELETED_PATH: Path = Path.of("src/main/java/example/Deleted.txt")
        const val PRE_EXISTING_CONTENT = "pre-existing-bytes-α\n"
        const val POST_EXISTING_CONTENT = "post-existing-bytes-β\n"
        const val PRE_DELETED_CONTENT = "pre-deleted-bytes-γ\n"
        const val POST_CREATED_CONTENT = "post-created-bytes-δ\n"
        val EXPECTED_REFUSAL_DIAGNOSTICS = listOf(Diagnostic(
            "Transaction is not in APPLIED state: ROLLED_BACK",
            Diagnostic.Severity.ERROR,
            code = "transaction.notApplied",
        ))
        val SOURCE_ALIAS_MUTATION_DIAGNOSTIC = Diagnostic(
            "Fixture mutation after ManagedRollbackExecutor.execute returned",
            Diagnostic.Severity.ERROR,
            code = "fixture.mutableDiagnosticsSourceAlias",
        )
        val OUTCOME_MUTATION_DIAGNOSTIC = Diagnostic(
            "Attempted mutation through ManagedRollbackOutcome.Refused",
            Diagnostic.Severity.ERROR,
            code = "fixture.mutableDiagnosticsOutcome",
        )
        const val PRE_EXISTING_MTIME = 1_700_000_001_000L
        const val POST_EXISTING_MTIME = 1_700_000_002_000L
        const val PRE_DELETED_MTIME = 1_700_000_003_000L
        const val POST_CREATED_MTIME = 1_700_000_004_000L
        val POLICY_SENTINELS = listOf(
            "mode-selection.state",
            "response-projection.state",
            "refresh.state",
            "diagnostics.state",
            "session-lifecycle.state",
        )
    }
}

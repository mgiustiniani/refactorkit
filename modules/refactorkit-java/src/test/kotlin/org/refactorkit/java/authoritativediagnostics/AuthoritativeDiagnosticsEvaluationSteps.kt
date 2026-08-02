package org.refactorkit.java.authoritativediagnostics

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticDetails
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.Module
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Direct-library Story-BDD glue for the 12 absent authoritative-evaluation cases. */
class AuthoritativeDiagnosticsEvaluationSteps {
    private lateinit var scenario: Scenario
    private lateinit var temporaryBoundary: Path

    private var modelWorkspace: ModelWorkspace? = null
    private var providerBehavior = ProviderBehavior.EXACT
    private var providerBehaviorText: String? = null
    private var expectedViolatedBoundary: String? = null
    private var authoritativeProvider: AuthoritativeDiagnosticsProvider? = null
    private var authoritativeGate: DiagnosticsGate? = null
    private val authoritativeTrace = mutableListOf<AuthoritativeInvocation>()
    private val detachmentObservations = mutableListOf<DetachmentObservation>()
    private var deepDetachmentRequired = false
    private var mutationExerciseRequired = false
    private var authoritativeResult: ApplyResult? = null

    private var compatibility: CompatibilityWorld? = null

    @Before(REQ_TAG_EXPRESSION)
    fun createBoundedScenario(scenario: Scenario) {
        this.scenario = scenario
        temporaryBoundary = Files.createTempDirectory("refactorkit-authoritative-diagnostics-")
        assertTrue(temporaryBoundary.isAbsolute)
        assertFalse(Files.isSymbolicLink(temporaryBoundary))
        scenario.attach(
            "This scenario invokes only the public PatchEngine, DiagnosticsGate, and TransactionLog APIs " +
                "over synthetic immutable snapshots inside one disposable boundary.",
            "text/plain",
            "direct-library-boundary",
        )
    }

    @After(REQ_TAG_EXPRESSION)
    fun removeBoundedScenario() {
        if (this::temporaryBoundary.isInitialized) deleteTreeNoFollow(temporaryBoundary)
    }

    // REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001: 1
    @Given("a bounded temporary workspace has exact immutable baseline ProjectSnapshot {string}, diagnostic multiset {string}, synthetic build evidence, and no transaction record")
    fun exactImmutableBaseline(snapshotName: String, diagnosticsName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("D0", diagnosticsName)
        val world = createModelWorkspace("exact-authoritative-post-image")
        modelWorkspace = world

        assertEquals(world.s0, rehydrateSemanticModel(world.s0))
        assertEquals(world.d0, diagnosticsFor(world.s0))
        assertTrue(world.s0.modules.isNotEmpty())
        assertTrue(world.s0.classpathEvidence.isNotEmpty())
        assertTrue(world.s0.buildModels.isNotEmpty())
        assertEquals(emptyList(), world.log.listRecordsReadOnly())
        assertSnapshotCollectionsRejectMutation(world.s0)
    }

    // 2
    @Given("an explicitly approved model-changing PatchPlan is bound to {string} and normalizes without writing to exact staged file candidate {string}")
    fun approvedModelChangingPlan(snapshotName: String, candidateName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("C1", candidateName)
        val world = requireModelWorkspace()
        val before = captureWorkspaceBytes(world.root, world.s0.trackedFiles.map(SourceFile::path))
        val normalized = WorkspaceEditSimulator.normalize(world.plan.workspaceEdit)
        val simulated = WorkspaceEditSimulator.apply(world.s0, normalized)

        assertEquals(PatchStatus.PREVIEW, world.plan.status)
        assertTrue(world.plan.requiresUserApproval)
        assertEquals(world.s0.hash, world.plan.snapshotHash)
        assertNotNull(world.plan.authorityLease)
        assertEquals(world.plan.operation, world.plan.authorityLease?.operation)
        assertEquals(world.c1, simulated)
        assertEquals(before, captureWorkspaceBytes(world.root, world.s0.trackedFiles.map(SourceFile::path)))
        assertFalse(Files.exists(world.root.resolve(CREATED_FILE), LinkOption.NOFOLLOW_LINKS))
    }

    // 3
    @Given("the language-neutral provider returns one immutable ProjectSnapshot and diagnostic multiset evaluated from that same supplied candidate")
    fun exactLanguageNeutralProvider() {
        configureAuthoritativeProvider(ProviderBehavior.EXACT, "exact candidate")
        assertEquals(0, authoritativeTrace.size, "Constructing the provider and gate must be lazy")
    }

    @Given("{string} and each otherwise exact provider result include one explicitly declared external regular-file classpathEvidence whose fingerprint matches an engine-controlled no-follow recomputation from its current bytes for its path and kind")
    fun genuineExternalClasspathEvidence(snapshotName: String) {
        assertEquals("S0", snapshotName)
        val world = requireModelWorkspace()
        val evidence = exactExternalEvidence(world.s0)
        val external = evidence.path.toAbsolutePath().normalize()
        assertTrue(evidence.path.isAbsolute)
        assertFalse(external.startsWith(world.root.toAbsolutePath().normalize()))
        assertTrue(Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(external))
        assertEquals(evidence, ClasspathEvidence.capture(world.root, external, evidence.kind))
        assertEquals(1, world.s0.classpathEvidence.count { it.path.isAbsolute })
    }

    @Given("each provider result is constructed from caller-owned mutable collections with a distinct sentinel at every captured semantic boundary:")
    fun callerOwnedMutableSemanticBoundaries(table: DataTable) {
        assertEquals(
            DETACHMENT_BOUNDARIES,
            table.asMaps().map { it.getValue("caller-owned collection boundary") },
        )
        deepDetachmentRequired = true
        assertEquals(0, authoritativeTrace.size, "The provider remains lazy until PatchEngine.apply")
    }

    @Given("immediately after constructing each AuthoritativeDiagnosticsEvaluation, the provider mutates every supplied collection and attempts the same sentinel changes through all outer and nested collections exposed by the returned value")
    fun requestImmediateAliasAndViewMutationExercise() {
        assertTrue(deepDetachmentRequired)
        mutationExerciseRequired = true
        assertEquals(0, authoritativeTrace.size, "No evaluation may be constructed before apply")
    }

    // 4
    @When("the caller invokes the real public PatchEngine.apply with authoritative evaluation selected through the additive DiagnosticsGate boundary")
    fun applyWithAuthoritativeEvaluation() {
        invokeAuthoritativeApply()
    }

    // 5
    @Then("the authoritative evaluation trace is exactly:")
    fun exactAuthoritativeTrace(table: DataTable) {
        assertEquals(
            listOf(
                listOf("order", "phase", "exact input", "required result"),
                listOf("1", "baseline", "S0", "ProjectSnapshot S0 and diagnostics D0"),
                listOf("2", "staged", "C1", "ProjectSnapshot S1 and diagnostics D1"),
                listOf("3", "committed", "committed candidate derived from S1", "snapshot hash S1.hash and diagnostics D1"),
            ),
            table.asLists(),
        )
        val world = requireModelWorkspace()
        assertEquals(listOf("baseline", "staged", "committed"), authoritativeTrace.map(AuthoritativeInvocation::phase))
        assertEquals(world.s0, authoritativeTrace[0].input)
        assertEquals(world.s0, authoritativeTrace[0].outputSnapshot)
        assertEquals(world.d0, authoritativeTrace[0].diagnostics)
        assertEquals(world.c1, authoritativeTrace[1].input)
        assertEquals(world.s1, authoritativeTrace[1].outputSnapshot)
        assertEquals(world.d1, authoritativeTrace[1].diagnostics)
        assertEquals(world.s1.hash, authoritativeTrace[2].input.hash)
        assertEquals(world.s1, authoritativeTrace[2].outputSnapshot)
        assertEquals(world.d1, authoritativeTrace[2].diagnostics)
        assertTrue(authoritativeTrace.all(AuthoritativeInvocation::workspaceLockHeld))
    }

    @Then("after every caller and exposed-view mutation attempt, each returned evaluation still exposes its exact construction-time ProjectSnapshot value and complete diagnostic multiset")
    fun evaluationsRemainAtConstructionValues() {
        assertEquals(3, detachmentObservations.size, "Baseline, staged, and committed evaluations must be exercised")
        detachmentObservations.forEach { observation ->
            assertEquals(observation.expectedSnapshot, observation.evaluation.snapshot, observation.phase)
            assertEquals(observation.expectedDiagnostics, observation.evaluation.diagnostics, observation.phase)
            assertEquals(observation.expectedHash, observation.evaluation.snapshot.hash, observation.phase)
            val callerAttempts = observation.attempts.filter { it.access == MutationAccess.CALLER_ALIAS }
            assertTrue(callerAttempts.isNotEmpty(), "No caller aliases were exercised for ${observation.phase}")
            assertTrue(
                callerAttempts.all { !it.rejected && !it.changedDuringAttempt && it.exactAfterCleanup },
                "Caller-owned collection aliases leaked into ${observation.phase}: ${callerAttempts.filterNot { !it.rejected && !it.changedDuringAttempt && it.exactAfterCleanup }}",
            )
            REQUIRED_MUTATION_LABELS.forEach { required ->
                assertTrue(
                    callerAttempts.any { it.label.startsWith(required) },
                    "The ${observation.phase} evaluation did not exercise caller alias '$required'",
                )
            }
        }
    }

    @Then("no attempted mutation through an exposed outer or nested collection changes any subsequently observed evaluation value or produces a partial change")
    fun exposedViewsRejectEveryMutationAtomically() {
        detachmentObservations.forEach { observation ->
            val exposedAttempts = observation.attempts.filter { it.access == MutationAccess.EXPOSED_VIEW }
            assertTrue(exposedAttempts.isNotEmpty(), "No exposed views were exercised for ${observation.phase}")
            assertTrue(
                exposedAttempts.all {
                    it.rejected && !it.changedDuringAttempt && it.cleanupFailure == null && it.exactAfterCleanup
                },
                "Mutable or partially mutable evaluation views in ${observation.phase}: " +
                    exposedAttempts.filterNot {
                        it.rejected && !it.changedDuringAttempt && it.cleanupFailure == null && it.exactAfterCleanup
                    },
            )
        }
    }

    @Then("each evaluation's reported snapshot hash and a fresh canonical hash recomputed from its complete post-attempt exposed snapshot state both equal its construction-time hash")
    fun exposedSnapshotHashesRemainCanonical() {
        detachmentObservations.forEach { observation ->
            assertEquals(observation.expectedHash, observation.evaluation.snapshot.hash, observation.phase)
            assertEquals(observation.expectedHash, freshSnapshotHash(observation.evaluation.snapshot), observation.phase)
            assertTrue(observation.attempts.all(MutationAttempt::exactAfterCleanup), observation.attempts.toString())
        }
    }

    @Then("the matching external classpathEvidence is accepted on fingerprint authenticity and is not refused solely because its path is outside the workspace")
    fun genuineExternalEvidenceIsAccepted() {
        assertIs<ApplyResult.Applied>(authoritativeResult)
        val world = requireModelWorkspace()
        detachmentObservations.forEach { observation ->
            val evidence = exactExternalEvidence(observation.evaluation.snapshot)
            assertFalse(evidence.path.toAbsolutePath().normalize().startsWith(world.root.toAbsolutePath().normalize()))
            assertEquals(evidence, ClasspathEvidence.capture(world.root, evidence.path, evidence.kind))
        }
    }

    // 6
    @Then("{string} preserves {string} workspace root, source and auxiliary partition, tracked paths, language IDs, contents, sourceExtensions, and ignoredDirectories exactly, while only modules, classpathEvidence, and buildModels may be rehydrated")
    fun authoritativeSnapshotPreservesCandidateShell(snapshotName: String, candidateName: String) {
        assertEquals("S1", snapshotName)
        assertEquals("C1", candidateName)
        val world = requireModelWorkspace()
        assertCandidateShellEquals(world.c1, world.s1)
        assertNotEquals(world.c1.modules, world.s1.modules)
        assertNotEquals(world.c1.buildModels, world.s1.buildModels)
        assertEquals(world.c1.classpathEvidence, world.s1.classpathEvidence)
        assertEquals(
            world.c1,
            world.s1.copy(
                modules = world.c1.modules,
                classpathEvidence = world.c1.classpathEvidence,
                buildModels = world.c1.buildModels,
            ),
            "No ProjectSnapshot field outside the three semantic fields may change",
        )
    }

    // 7
    @Then("after staged evaluation returns, PatchEngine revalidates the live workspace and operation-authority lease as exact {string} under the same lock before WAL")
    fun postProviderRevalidationUnderSameLock(snapshotName: String) {
        assertEquals("S0", snapshotName)
        assertTrue(authoritativeTrace.take(2).all(AuthoritativeInvocation::workspaceLockHeld))
        verifyPostProviderLeaseRevalidationBeforeWal()
    }

    // 8
    @Then("the rendered transaction images equal the tracked image of both {string} and {string}")
    fun renderedImagesEqualCandidateAndAuthority(candidateName: String, snapshotName: String) {
        assertEquals("C1", candidateName)
        assertEquals("S1", snapshotName)
        val world = requireModelWorkspace()
        val record = world.log.listRecordsReadOnly().single()
        val affected = world.plan.workspaceEdit.affectedFiles().map(Path::normalize).toSet()
        val expectedPre = expectedImages(world.s0, affected)
        val expectedPost = expectedImages(world.c1, affected)
        assertEquals(expectedPre, record.preImages.associate { it.path.normalize() to it.content })
        assertEquals(expectedPost, record.postImages.associate { it.path.normalize() to it.content })
        assertEquals(expectedPost, expectedImages(world.s1, affected))
    }

    // 9
    @Then("the sole TransactionLog record first becomes PREPARED at schema version 8 with preSnapshotHash {string} and postSnapshotHash {string}")
    fun preparedSchemaV8Record(preHashName: String, postHashName: String) {
        assertEquals("S0.hash", preHashName)
        assertEquals("S1.hash", postHashName)
        val world = requireModelWorkspace()
        val record = world.log.listRecordsReadOnly().single()
        assertEquals(8, record.schemaVersion)
        assertEquals(JournalState.PREPARED, record.history.first().state)
        assertEquals(world.s0.hash, record.preSnapshotHash)
        assertEquals(world.s1.hash, record.postSnapshotHash)
    }

    // 10
    @Then("PatchEngine returns Applied only after committed evaluation reproduces exact {string} and the complete {string} multiset")
    fun appliedAfterCommittedEvaluation(snapshotHashName: String, diagnosticsName: String) {
        assertEquals("S1.hash", snapshotHashName)
        assertEquals("D1", diagnosticsName)
        val world = requireModelWorkspace()
        val applied = assertIs<ApplyResult.Applied>(authoritativeResult)
        assertEquals(world.log.listRecordsReadOnly().single().transaction.id, applied.transaction.id)
        assertEquals(3, authoritativeTrace.size)
        assertEquals(world.s1.hash, authoritativeTrace.last().outputSnapshot.hash)
        assertEquals(world.d1, authoritativeTrace.last().diagnostics)
        assertWorkspaceMatchesSnapshot(world.root, world.s1)
    }

    // REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-002: 11
    @Given("an approved model-changing plan over exact live {string} stages exact candidate {string} and TransactionLog.listRecordsReadOnly is empty")
    fun authorityViolationBaseline(snapshotName: String, candidateName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("C1", candidateName)
        val world = createModelWorkspace("provider-authority-violation")
        modelWorkspace = world
        assertEquals(world.c1, WorkspaceEditSimulator.apply(world.s0, world.plan.workspaceEdit))
        assertEquals(emptyList(), world.log.listRecordsReadOnly())
        assertWorkspaceMatchesSnapshot(world.root, world.s0)
    }

    // 12
    @Given("the authoritative provider {string}")
    fun violatingProvider(providerBehavior: String) {
        val behavior = PROVIDER_BEHAVIORS[providerBehavior]
            ?: fail("Unrecognized provider behavior: $providerBehavior")
        providerBehaviorText = providerBehavior
        expectedViolatedBoundary = VIOLATED_BOUNDARIES.getValue(providerBehavior)
        configureAuthoritativeProvider(behavior, providerBehavior)
        assertEquals(0, authoritativeTrace.size)
    }

    // 13
    @When("the caller submits the plan through the real public PatchEngine.apply and additive DiagnosticsGate boundary")
    fun submitThroughAuthoritativeGate() {
        invokeAuthoritativeApply()
    }

    // 14
    @Then("PatchEngine reports {string} as an authority violation before a PREPARED WAL record and before any managed plan edit")
    fun authorityViolationBeforeWal(violatedBoundary: String) {
        assertEquals(expectedViolatedBoundary, violatedBoundary)
        val world = requireModelWorkspace()
        val refused = assertIs<ApplyResult.Refused>(authoritativeResult)
        assertTrue(
            refused.diagnostics.any { diagnosticDescribesBoundary(it, violatedBoundary) },
            "No refusal diagnostic described '$violatedBoundary': ${refused.diagnostics}",
        )
        if (providerBehavior in NEW_SEMANTIC_BOUNDARY_BEHAVIORS) {
            assertEquals(
                listOf(violatedBoundary),
                refused.diagnostics.mapNotNull { it.details["violatedBoundary"] }.distinct(),
                "The new provider boundary must be reported exactly in structured refusal details",
            )
        }
        assertEquals(emptyList(), world.log.listRecordsReadOnly())
        assertManagedTargetsRemainUnapplied(world)
    }

    // 15
    @Then("TransactionLog.listRecordsReadOnly remains empty, so no write transaction exists")
    fun noWriteTransactionExists() {
        assertEquals(emptyList(), requireModelWorkspace().log.listRecordsReadOnly())
    }

    // 16
    @Then("no planned edit is applied; a provider-induced live mutation remains external drift rather than a managed transaction")
    fun noManagedEditAndExternalDriftRemains() {
        val world = requireModelWorkspace()
        assertManagedTargetsRemainUnapplied(world)
        val source = Files.readString(world.root.resolve(SOURCE_PATH))
        if (providerBehavior == ProviderBehavior.LIVE_WORKSPACE_MUTATION) {
            assertEquals(SOURCE_CONTENT + EXTERNAL_DRIFT_SUFFIX, source)
        } else {
            assertEquals(SOURCE_CONTENT, source)
            assertWorkspaceMatchesSnapshot(world.root, world.s0)
        }
        assertEquals(emptyList(), world.log.listRecordsReadOnly())
    }

    // 17
    @Then("neither the provider result nor its diagnostics can approve or authorize a write")
    fun providerHasNoWriteAuthority() {
        val world = requireModelWorkspace()
        assertIs<ApplyResult.Refused>(authoritativeResult)
        assertEquals(2, authoritativeTrace.size)
        assertTrue(authoritativeTrace.last().diagnostics.isNotEmpty())
        assertManagedTargetsRemainUnapplied(world)
        assertEquals(emptyList(), world.log.listRecordsReadOnly())
    }

    // REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-003: 18
    @Given("authoritative baseline and staged evaluation have accepted exact {string} with {string} and exact {string} with {string}")
    fun acceptedBaselineAndStaged(
        baselineName: String,
        baselineDiagnostics: String,
        stagedName: String,
        stagedDiagnostics: String,
    ) {
        assertEquals(listOf("S0", "D0", "S1", "D1"), listOf(baselineName, baselineDiagnostics, stagedName, stagedDiagnostics))
        val world = createModelWorkspace("post-apply-mismatch")
        modelWorkspace = world
        assertEquals(world.s0, rehydrateSemanticModel(world.s0))
        assertEquals(world.s1, rehydrateSemanticModel(world.c1))
        assertEquals(world.d0, diagnosticsFor(world.s0))
        assertEquals(world.d1, diagnosticsFor(world.s1))
    }

    // 19
    @Given("committed evaluation will return {string} while restored evaluation will reproduce exact {string} and {string}")
    fun configurePostApplyMismatch(postApplyResult: String, restoredHash: String, restoredDiagnostics: String) {
        assertEquals("S0.hash", restoredHash)
        assertEquals("D0", restoredDiagnostics)
        val behavior = POST_APPLY_RESULTS[postApplyResult] ?: fail("Unrecognized post-apply result: $postApplyResult")
        providerBehaviorText = postApplyResult
        configureAuthoritativeProvider(behavior, postApplyResult)
    }

    // 20
    @When("the caller invokes the real public PatchEngine.apply for the approved model-changing plan")
    fun invokeApprovedModelChangingPlan() {
        invokeAuthoritativeApply()
    }

    // 21
    @Then("{string} is detected after the sole record reached APPLIED and is reported as post-apply failure rather than pre-WAL refusal")
    fun mismatchDetectedPostApply(mismatch: String) {
        val expectedBehavior = MISMATCHES[mismatch] ?: fail("Unrecognized mismatch: $mismatch")
        assertEquals(expectedBehavior, providerBehavior)
        val refused = assertIs<ApplyResult.Refused>(authoritativeResult)
        assertTrue(refused.diagnostics.any { it.code?.contains("postApply", ignoreCase = true) == true })
        val record = requireModelWorkspace().log.listRecordsReadOnly().single()
        assertTrue(record.history.any { it.state == JournalState.APPLIED })
        assertEquals(JournalState.ROLLED_BACK, record.state)
    }

    // 22
    @Then("that same schema-v8 record advances through {string}")
    fun sameRecordAutomaticallyRollsBack(expectedStates: String) {
        assertEquals("PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK", expectedStates)
        val record = requireModelWorkspace().log.listRecordsReadOnly().single()
        assertEquals(8, record.schemaVersion)
        assertEquals(
            listOf(
                JournalState.PREPARED,
                JournalState.APPLYING,
                JournalState.APPLIED,
                JournalState.ROLLING_BACK,
                JournalState.ROLLED_BACK,
            ),
            record.history.map { it.state },
        )
    }

    // 23
    @Then("automatic rollback restores every tracked byte and created-directory fact to the exact {string} image")
    fun automaticRollbackRestoresExactImage(snapshotName: String) {
        assertEquals("S0", snapshotName)
        val world = requireModelWorkspace()
        assertWorkspaceMatchesSnapshot(world.root, world.s0)
        assertFalse(Files.exists(world.root.resolve(CREATED_FILE), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(world.root.resolve(CREATED_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        assertEquals(world.baselineBytes, captureWorkspaceBytes(world.root, world.s0.trackedFiles.map(SourceFile::path)))
    }

    // 24
    @Then("the restored workspace is reevaluated as exact {string} with the complete {string} multiset before the refusal returns")
    fun restoredWorkspaceReevaluated(snapshotHashName: String, diagnosticsName: String) {
        assertEquals("S0.hash", snapshotHashName)
        assertEquals("D0", diagnosticsName)
        val world = requireModelWorkspace()
        assertEquals(4, authoritativeTrace.size)
        val restored = authoritativeTrace.last()
        assertEquals("restored", restored.phase)
        assertEquals(world.s0, restored.input)
        assertEquals(world.s0.hash, restored.outputSnapshot.hash)
        assertEquals(world.d0, restored.diagnostics)
        assertTrue(restored.workspaceLockHeld)
        assertIs<ApplyResult.Refused>(authoritativeResult)
    }

    // 25
    @Then("TransactionLog.listRecordsReadOnly contains exactly that one transaction and no second transaction")
    fun exactlyOneRolledBackTransaction() {
        val records = requireModelWorkspace().log.listRecordsReadOnly()
        assertEquals(1, records.size)
        assertEquals(JournalState.ROLLED_BACK, records.single().state)
        assertEquals(1, records.map { it.transaction.id }.distinct().size)
    }

    // REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-004: 26
    @Given("two otherwise identical diagnostics-only plans cannot change hash-bound modules, classpathEvidence, or buildModels and start from the same immutable {string}")
    fun twoDiagnosticsOnlyPlans(snapshotName: String) {
        assertEquals("S0", snapshotName)
        val enabled = createDiagnosticsWorkspace("compatibility-enabled")
        val disabled = createDiagnosticsWorkspace("compatibility-disabled")
        assertEquals(enabled.s0.hash, disabled.s0.hash)
        assertEquals(enabled.s0.modules, disabled.s0.modules)
        assertEquals(enabled.s0.classpathEvidence, disabled.s0.classpathEvidence)
        assertEquals(enabled.s0.buildModels, disabled.s0.buildModels)
        assertEquals(enabled.c1.modules, enabled.s0.modules)
        assertEquals(enabled.c1.classpathEvidence, enabled.s0.classpathEvidence)
        assertEquals(enabled.c1.buildModels, enabled.s0.buildModels)
        compatibility = CompatibilityWorld(enabled, disabled)
    }

    // 27
    @Given("the caller constructs existing DiagnosticsGate.enabled and DiagnosticsGate.disabled values without selecting authoritative evaluation")
    fun constructExistingGates() {
        val world = requireCompatibility()
        world.enabledGate = DiagnosticsGate.enabled("compatibility-enabled") { candidate ->
            world.enabledInvocations += candidate
            compatibilityDiagnostics(candidate)
        }
        world.disabledGate = DiagnosticsGate.disabled("compatibility-disabled")
        world.providerCallsAtConstruction = world.enabledInvocations.size
        assertEquals(0, world.providerCallsAtConstruction)
    }

    // 28
    @When("each plan is applied through the real public PatchEngine.apply and inspected through TransactionLog")
    fun applyBothExistingGates() {
        val world = requireCompatibility()
        world.enabledResult = applyDiagnosticsWorkspace(world.enabled, requireNotNull(world.enabledGate))
        world.disabledResult = applyDiagnosticsWorkspace(world.disabled, requireNotNull(world.disabledGate))
        world.enabledRecord = world.enabled.log.listRecordsReadOnly().single()
        world.disabledRecord = world.disabled.log.listRecordsReadOnly().single()
    }

    // 29
    @Then("their exact compatibility traces are:")
    fun exactCompatibilityTraces(table: DataTable) {
        assertEquals(
            listOf(
                listOf("gate", "provider invocation count and order", "exact provider inputs", "journal postSnapshotHash"),
                listOf(
                    "enabled",
                    "3: baseline, staged, committed",
                    "S0; simulator snapshot for C1 retaining S0 semantic metadata; committed rehydration of that snapshot",
                    "existing simulator-derived staged hash",
                ),
                listOf("disabled", "0", "none", "existing simulator-derived staged hash"),
            ),
            table.asLists(),
        )
        val world = requireCompatibility()
        assertIs<ApplyResult.Applied>(world.enabledResult)
        assertIs<ApplyResult.Applied>(world.disabledResult)
        assertEquals(3, world.enabledInvocations.size)
        assertEquals(world.enabled.s0, world.enabledInvocations[0])
        assertEquals(world.enabled.c1, world.enabledInvocations[1])
        assertEquals(world.enabled.c1, world.enabledInvocations[2])
        assertEquals(world.enabled.c1.hash, requireNotNull(world.enabledRecord).postSnapshotHash)
        assertEquals(world.disabled.c1.hash, requireNotNull(world.disabledRecord).postSnapshotHash)
    }

    // 30
    @Then("constructing either gate invokes no provider; enabled invokes its provider lazily only in the listed apply order and disabled never invokes one")
    fun existingGateLazinessAndOrder() {
        val world = requireCompatibility()
        assertEquals(0, world.providerCallsAtConstruction)
        assertEquals(3, world.enabledInvocations.size)
        assertEquals(
            listOf(world.enabled.s0.hash, world.enabled.c1.hash, world.enabled.c1.hash),
            world.enabledInvocations.map(ProjectSnapshot::hash),
        )
        assertIs<ApplyResult.Applied>(world.disabledResult)
    }

    // 31
    @Then("every enabled provider input retains the exact diagnostics-only ProjectSnapshot identity and hash used by the existing gate")
    fun exactEnabledProviderInputs() {
        val world = requireCompatibility()
        world.enabledInvocations.forEachIndexed { index, input ->
            val expected = if (index == 0) world.enabled.s0 else world.enabled.c1
            assertEquals(expected, input)
            assertEquals(expected.hash, input.hash)
            assertEquals(world.enabled.s0.modules, input.modules)
            assertEquals(world.enabled.s0.classpathEvidence, input.classpathEvidence)
            assertEquals(world.enabled.s0.buildModels, input.buildModels)
        }
    }

    // 32
    @Then("enabled retains existing diagnostic identity, error-multiset regression, approved-regression, unavailability, post-apply, and automatic-rollback semantics")
    fun enabledCompatibilitySemantics() {
        verifyErrorMultisetAndIdentityRefusal()
        verifyApprovedRegression()
        verifyProviderUnavailability()
        verifyDiagnosticsPostApplyRollback()
    }

    // 33
    @Then("disabled retains its existing apply, result, hash, and rollback semantics without diagnostic evaluation")
    fun disabledCompatibilitySemantics() {
        val world = requireCompatibility()
        val applied = assertIs<ApplyResult.Applied>(world.disabledResult)
        val recordBefore = world.disabled.log.listRecordsReadOnly().single()
        assertEquals(world.disabled.c1.hash, recordBefore.postSnapshotHash)
        assertWorkspaceMatchesSnapshot(world.disabled.root, world.disabled.c1)
        val rollback = world.disabled.engine.rollback(applied.transaction, RollbackMode.NORMAL)
        assertIs<ApplyResult.Applied>(rollback)
        val recordAfter = world.disabled.log.listRecordsReadOnly().single()
        assertEquals(JournalState.ROLLED_BACK, recordAfter.state)
        assertEquals(recordBefore.postSnapshotHash, recordAfter.postSnapshotHash)
        assertWorkspaceMatchesSnapshot(world.disabled.root, world.disabled.s0)
    }

    // 34
    @Then("existing records remain schema version 8 with unchanged preSnapshotHash, postSnapshotHash, image, checksum, idempotency, and exact-result meanings")
    fun existingSchemaV8MeaningsRemain() {
        val world = requireCompatibility()
        verifyExistingRecordMeanings(world.enabled, assertIs<ApplyResult.Applied>(world.enabledResult))
        verifyExistingRecordMeanings(world.disabled, assertIs<ApplyResult.Applied>(world.disabledResult))
    }

    // 35
    @Then("the additive authoritative path introduces no schema bump, historical rewrite, new persistence, serialized evaluator state, scanner registry, or generic orchestration service")
    fun additivePathAddsNoPersistence() {
        val world = requireCompatibility()
        listOf(world.enabled, world.disabled).forEach { workspace ->
            val records = workspace.log.listRecordsReadOnly()
            assertEquals(1, records.size)
            assertEquals(8, records.single().schemaVersion)
            val raw = Files.readString(journalPath(workspace.log, records.single()))
            FORBIDDEN_SERIALIZED_EVALUATOR_TERMS.forEach { term ->
                assertFalse(raw.contains(term, ignoreCase = true), "Journal serialized evaluator term '$term'")
            }
            val metadataPaths = Files.walk(workspace.root.resolve(".refactorkit")).use { paths ->
                paths.map { workspace.root.relativize(it).toString().replace('\\', '/') }.sorted().toList()
            }
            assertEquals(
                listOf(
                    ".refactorkit",
                    ".refactorkit/transactions",
                    ".refactorkit/transactions/${records.single().transaction.id.value}.json",
                    ".refactorkit/workspace.lock",
                ),
                metadataPaths,
            )
        }
    }

    // 36
    @Then("core gains no language-specific type, while a provider gains no edit, approval, lock, WAL, transaction, apply, or rollback authority")
    fun languageNeutralProviderHasNoWriteAuthority() {
        val world = requireCompatibility()
        assertTrue(authoritativeProvider == null, "Compatibility constructed no authoritative provider")
        assertEquals(3, world.enabledInvocations.size)
        assertEquals(1, world.enabled.log.listRecordsReadOnly().size)
        assertEquals(1, world.disabled.log.listRecordsReadOnly().size)
        assertTrue(world.enabledInvocations.all { snapshot -> snapshot.trackedFiles.all { !it.path.isAbsolute } })
        assertTrue(world.enabledInvocations.all { it::class == ProjectSnapshot::class })
    }

    private fun constructMutableEvaluation(
        authoritative: ProjectSnapshot,
        diagnostics: List<Diagnostic>,
        phase: String,
    ): AuthoritativeDiagnosticsEvaluation {
        val expectedSnapshot = freezeSnapshot(authoritative)
        val expectedDiagnostics = freezeDiagnostics(diagnostics)
        val probes = mutableListOf<MutationProbe>()
        val mutableSnapshot = mutableSnapshot(authoritative, probes)
        val mutableDiagnostics = mutableDiagnostics(diagnostics, probes)
        val evaluation = AuthoritativeDiagnosticsEvaluation(mutableSnapshot, mutableDiagnostics)
        assertEquals(expectedSnapshot, evaluation.snapshot, "$phase construction snapshot")
        assertEquals(expectedDiagnostics, evaluation.diagnostics, "$phase construction diagnostics")
        assertEquals(expectedSnapshot.hash, evaluation.snapshot.hash, "$phase construction hash")

        val attempts = buildList {
            probes.forEach { probe ->
                add(attemptMutation(
                    evaluation,
                    expectedSnapshot,
                    expectedDiagnostics,
                    probe.label,
                    MutationAccess.CALLER_ALIAS,
                    probe.mutateCaller,
                    probe.restoreCaller,
                ))
            }
            probes.forEach { probe ->
                add(attemptMutation(
                    evaluation,
                    expectedSnapshot,
                    expectedDiagnostics,
                    probe.label,
                    MutationAccess.EXPOSED_VIEW,
                    { probe.mutateExposed(evaluation) },
                    { probe.restoreExposed(evaluation) },
                ))
            }
        }
        detachmentObservations += DetachmentObservation(
            phase = phase,
            evaluation = evaluation,
            expectedSnapshot = expectedSnapshot,
            expectedDiagnostics = expectedDiagnostics,
            expectedHash = expectedSnapshot.hash,
            attempts = attempts,
        )
        return evaluation
    }

    private fun mutableSnapshot(
        source: ProjectSnapshot,
        probes: MutableList<MutationProbe>,
    ): ProjectSnapshot {
        val files = source.files.toMutableList()
        probes += listProbe(
            "ProjectSnapshot.files",
            files,
            SourceFile(Path.of("detachment-sentinel/source.java"), "final class Sentinel {}\n", "java"),
        ) { it.snapshot.files }
        val auxiliaryFiles = source.auxiliaryFiles.toMutableList()
        probes += listProbe(
            "ProjectSnapshot.auxiliaryFiles",
            auxiliaryFiles,
            SourceFile(Path.of("detachment-sentinel/auxiliary.txt"), "sentinel\n", "text"),
        ) { it.snapshot.auxiliaryFiles }
        val sourceExtensions = source.sourceExtensions.toMutableSet()
        probes += setProbe("ProjectSnapshot.sourceExtensions", sourceExtensions, "detachment-source-extension") {
            it.snapshot.sourceExtensions
        }
        val ignoredDirectories = source.ignoredDirectories.toMutableSet()
        probes += setProbe("ProjectSnapshot.ignoredDirectories", ignoredDirectories, "detachment-ignored-directory") {
            it.snapshot.ignoredDirectories
        }
        val modules = source.modules.mapIndexed { index, module -> mutableModule(module, index, probes) }.toMutableList()
        probes += listProbe(
            "ProjectSnapshot.modules",
            modules,
            Module("detachment-sentinel-module", Path.of("detachment-sentinel-module")),
        ) { it.snapshot.modules }
        val classpathEvidence = source.classpathEvidence.toMutableList()
        probes += listProbe(
            "ProjectSnapshot.classpathEvidence",
            classpathEvidence,
            ClasspathEvidence(Path.of("detachment-sentinel/classpath.bin"), ClasspathEvidenceKind.ENTRY, "missing"),
        ) { it.snapshot.classpathEvidence }
        val buildModels = source.buildModels.mapIndexed { index, model ->
            mutableBuildModel(model, index, probes)
        }.toMutableList()
        probes += listProbe(
            "ProjectSnapshot.buildModels",
            buildModels,
            BuildModel("detachment-sentinel-provider", BuildModelStatus.UNAVAILABLE, emptyList()),
        ) { it.snapshot.buildModels }
        return ProjectSnapshot(
            workspace = source.workspace,
            modules = modules,
            files = files,
            sourceExtensions = sourceExtensions,
            ignoredDirectories = ignoredDirectories,
            classpathEvidence = classpathEvidence,
            buildModels = buildModels,
            auxiliaryFiles = auxiliaryFiles,
        )
    }

    private fun mutableModule(
        source: Module,
        moduleIndex: Int,
        probes: MutableList<MutationProbe>,
    ): Module {
        fun path(field: String) = Path.of("detachment-sentinel/module-$moduleIndex/$field")
        fun value(field: String) = "detachment-sentinel-module-$moduleIndex-$field"
        val sourceRoots = source.sourceRoots.toMutableList()
        probes += listProbe("Module[$moduleIndex].sourceRoots", sourceRoots, path("source-roots")) {
            it.snapshot.modules[moduleIndex].sourceRoots
        }
        val mainSourceRoots = source.mainSourceRoots.toMutableList()
        probes += listProbe("Module[$moduleIndex].mainSourceRoots", mainSourceRoots, path("main-source-roots")) {
            it.snapshot.modules[moduleIndex].mainSourceRoots
        }
        val testSourceRoots = source.testSourceRoots.toMutableList()
        probes += listProbe("Module[$moduleIndex].testSourceRoots", testSourceRoots, path("test-source-roots")) {
            it.snapshot.modules[moduleIndex].testSourceRoots
        }
        val generatedSourceRoots = source.generatedSourceRoots.toMutableList()
        probes += listProbe("Module[$moduleIndex].generatedSourceRoots", generatedSourceRoots, path("generated-source-roots")) {
            it.snapshot.modules[moduleIndex].generatedSourceRoots
        }
        val generatedTestSourceRoots = source.generatedTestSourceRoots.toMutableList()
        probes += listProbe("Module[$moduleIndex].generatedTestSourceRoots", generatedTestSourceRoots, path("generated-test-source-roots")) {
            it.snapshot.modules[moduleIndex].generatedTestSourceRoots
        }
        val classpathEntries = source.classpathEntries.toMutableList()
        probes += listProbe("Module[$moduleIndex].classpathEntries", classpathEntries, path("classpath-entries")) {
            it.snapshot.modules[moduleIndex].classpathEntries
        }
        val mainClasspathEntries = source.mainClasspathEntries.toMutableList()
        probes += listProbe("Module[$moduleIndex].mainClasspathEntries", mainClasspathEntries, path("main-classpath-entries")) {
            it.snapshot.modules[moduleIndex].mainClasspathEntries
        }
        val mainRuntimeClasspathEntries = source.mainRuntimeClasspathEntries.toMutableList()
        probes += listProbe(
            "Module[$moduleIndex].mainRuntimeClasspathEntries",
            mainRuntimeClasspathEntries,
            path("main-runtime-classpath-entries"),
        ) { it.snapshot.modules[moduleIndex].mainRuntimeClasspathEntries }
        val testClasspathEntries = source.testClasspathEntries.toMutableList()
        probes += listProbe("Module[$moduleIndex].testClasspathEntries", testClasspathEntries, path("test-classpath-entries")) {
            it.snapshot.modules[moduleIndex].testClasspathEntries
        }
        val dependencies = source.dependencies.toMutableList()
        probes += listProbe("Module[$moduleIndex].dependencies", dependencies, value("dependencies")) {
            it.snapshot.modules[moduleIndex].dependencies
        }
        val mainDependencies = source.mainDependencies.toMutableList()
        probes += listProbe("Module[$moduleIndex].mainDependencies", mainDependencies, value("main-dependencies")) {
            it.snapshot.modules[moduleIndex].mainDependencies
        }
        val testDependencies = source.testDependencies.toMutableList()
        probes += listProbe("Module[$moduleIndex].testDependencies", testDependencies, value("test-dependencies")) {
            it.snapshot.modules[moduleIndex].testDependencies
        }
        val mainOutputDirectories = source.mainOutputDirectories.toMutableList()
        probes += listProbe("Module[$moduleIndex].mainOutputDirectories", mainOutputDirectories, path("main-output")) {
            it.snapshot.modules[moduleIndex].mainOutputDirectories
        }
        val testOutputDirectories = source.testOutputDirectories.toMutableList()
        probes += listProbe("Module[$moduleIndex].testOutputDirectories", testOutputDirectories, path("test-output")) {
            it.snapshot.modules[moduleIndex].testOutputDirectories
        }
        val languageSettings = source.languageSettings.toMutableMap()
        probes += mapProbe(
            "Module[$moduleIndex].languageSettings",
            languageSettings,
            value("language-setting-key"),
            value("language-setting-value"),
        ) { it.snapshot.modules[moduleIndex].languageSettings }
        return Module(
            name = source.name,
            root = source.root,
            sourceRoots = sourceRoots,
            classpathEntries = classpathEntries,
            dependencies = dependencies,
            languageSettings = languageSettings,
            mainSourceRoots = mainSourceRoots,
            testSourceRoots = testSourceRoots,
            generatedSourceRoots = generatedSourceRoots,
            generatedTestSourceRoots = generatedTestSourceRoots,
            mainClasspathEntries = mainClasspathEntries,
            mainRuntimeClasspathEntries = mainRuntimeClasspathEntries,
            testClasspathEntries = testClasspathEntries,
            mainDependencies = mainDependencies,
            testDependencies = testDependencies,
            mainOutputDirectories = mainOutputDirectories,
            testOutputDirectories = testOutputDirectories,
        )
    }

    private fun mutableBuildModel(
        source: BuildModel,
        modelIndex: Int,
        probes: MutableList<MutationProbe>,
    ): BuildModel {
        val modules = source.modules.mapIndexed { moduleIndex, module ->
            mutableBuildModule(module, modelIndex, moduleIndex, probes)
        }.toMutableList()
        probes += listProbe(
            "BuildModel[$modelIndex].modules",
            modules,
            BuildModule(
                "detachment-sentinel-build-module-$modelIndex",
                "detachment-sentinel-build-module-$modelIndex",
                Path.of("detachment-sentinel-build-module-$modelIndex"),
                emptyList(),
            ),
        ) { it.snapshot.buildModels[modelIndex].modules }
        val diagnostics = source.diagnostics.toMutableList()
        probes += listProbe(
            "BuildModel[$modelIndex].diagnostics",
            diagnostics,
            BuildModelDiagnostic("detachment.sentinel", "detachment sentinel", severity = Diagnostic.Severity.INFO),
        ) { it.snapshot.buildModels[modelIndex].diagnostics }
        val attributes = source.attributes.toMutableMap()
        probes += mapProbe(
            "BuildModel[$modelIndex].attributes",
            attributes,
            "detachment.sentinel.model.$modelIndex",
            "sentinel",
        ) { it.snapshot.buildModels[modelIndex].attributes }
        return BuildModel(source.providerId, source.status, modules, diagnostics, attributes)
    }

    private fun mutableBuildModule(
        source: BuildModule,
        modelIndex: Int,
        moduleIndex: Int,
        probes: MutableList<MutationProbe>,
    ): BuildModule {
        val sourceSets = source.sourceSets.mapIndexed { sourceSetIndex, sourceSet ->
            mutableBuildSourceSet(sourceSet, modelIndex, moduleIndex, sourceSetIndex, probes)
        }.toMutableList()
        probes += listProbe(
            "BuildModule[$modelIndex,$moduleIndex].sourceSets",
            sourceSets,
            BuildSourceSet("detachment-sentinel-source-set-$modelIndex-$moduleIndex", SourceSetKind.CUSTOM),
        ) { it.snapshot.buildModels[modelIndex].modules[moduleIndex].sourceSets }
        val attributes = source.attributes.toMutableMap()
        probes += mapProbe(
            "BuildModule[$modelIndex,$moduleIndex].attributes",
            attributes,
            "detachment.sentinel.buildModule.$modelIndex.$moduleIndex",
            "sentinel",
        ) { it.snapshot.buildModels[modelIndex].modules[moduleIndex].attributes }
        return BuildModule(source.id, source.name, source.root, sourceSets, attributes)
    }

    private fun mutableBuildSourceSet(
        source: BuildSourceSet,
        modelIndex: Int,
        moduleIndex: Int,
        sourceSetIndex: Int,
        probes: MutableList<MutationProbe>,
    ): BuildSourceSet {
        val prefix = "$modelIndex,$moduleIndex,$sourceSetIndex"
        fun path(field: String) = Path.of("detachment-sentinel/source-set-$modelIndex-$moduleIndex-$sourceSetIndex/$field")
        fun <T> exposed(selector: (BuildSourceSet) -> List<T>): (AuthoritativeDiagnosticsEvaluation) -> List<T> = {
            selector(it.snapshot.buildModels[modelIndex].modules[moduleIndex].sourceSets[sourceSetIndex])
        }
        val sourceRoots = source.sourceRoots.toMutableList()
        probes += listProbe("BuildSourceSet[$prefix].sourceRoots", sourceRoots, path("source-roots"), exposed { it.sourceRoots })
        val generatedSourceRoots = source.generatedSourceRoots.toMutableList()
        probes += listProbe(
            "BuildSourceSet[$prefix].generatedSourceRoots",
            generatedSourceRoots,
            path("generated-source-roots"),
            exposed { it.generatedSourceRoots },
        )
        val outputDirectories = source.outputDirectories.toMutableList()
        probes += listProbe(
            "BuildSourceSet[$prefix].outputDirectories",
            outputDirectories,
            path("output-directories"),
            exposed { it.outputDirectories },
        )
        val classpathEntries = source.classpathEntries.toMutableList()
        probes += listProbe(
            "BuildSourceSet[$prefix].classpathEntries",
            classpathEntries,
            path("classpath-entries"),
            exposed { it.classpathEntries },
        )
        val runtimeClasspathEntries = source.runtimeClasspathEntries.toMutableList()
        probes += listProbe(
            "BuildSourceSet[$prefix].runtimeClasspathEntries",
            runtimeClasspathEntries,
            path("runtime-classpath-entries"),
            exposed { it.runtimeClasspathEntries },
        )
        val moduleDependencies = source.moduleDependencies.toMutableList()
        probes += listProbe(
            "BuildSourceSet[$prefix].moduleDependencies",
            moduleDependencies,
            BuildDependency("detachment-sentinel-target-$modelIndex-$moduleIndex-$sourceSetIndex", DependencyScope.CUSTOM),
            exposed { it.moduleDependencies },
        )
        val attributes = source.attributes.toMutableMap()
        probes += mapProbe(
            "BuildSourceSet[$prefix].attributes",
            attributes,
            "detachment.sentinel.sourceSet.$modelIndex.$moduleIndex.$sourceSetIndex",
            "sentinel",
        ) { it.snapshot.buildModels[modelIndex].modules[moduleIndex].sourceSets[sourceSetIndex].attributes }
        return BuildSourceSet(
            id = source.id,
            kind = source.kind,
            sourceRoots = sourceRoots,
            generatedSourceRoots = generatedSourceRoots,
            outputDirectories = outputDirectories,
            classpathEntries = classpathEntries,
            runtimeClasspathEntries = runtimeClasspathEntries,
            moduleDependencies = moduleDependencies,
            attributes = attributes,
        )
    }

    private fun mutableDiagnostics(
        source: List<Diagnostic>,
        probes: MutableList<MutationProbe>,
    ): MutableList<Diagnostic> {
        val diagnostics = source.mapIndexed { index, diagnostic ->
            val fields = diagnostic.details.fields.toMutableMap()
            probes += mapProbe(
                "Diagnostic[$index].details.fields",
                fields,
                "detachment.sentinel.diagnostic.$index",
                "sentinel",
            ) { it.diagnostics[index].details.fields }
            diagnostic.copy(details = DiagnosticDetails(fields))
        }.toMutableList()
        probes += listProbe(
            "AuthoritativeDiagnosticsEvaluation.diagnostics",
            diagnostics,
            Diagnostic(
                "detachment sentinel diagnostic",
                Diagnostic.Severity.INFO,
                code = "detachment.sentinel",
                details = DiagnosticDetails(mapOf("sentinel" to "true")),
            ),
        ) { it.diagnostics }
        return diagnostics
    }

    private fun freezeSnapshot(source: ProjectSnapshot): ProjectSnapshot = ProjectSnapshot(
        workspace = source.workspace,
        modules = immutableList(source.modules.map(::freezeModule)),
        files = immutableList(source.files),
        sourceExtensions = immutableSet(source.sourceExtensions),
        ignoredDirectories = immutableSet(source.ignoredDirectories),
        classpathEvidence = immutableList(source.classpathEvidence),
        buildModels = immutableList(source.buildModels.map(::freezeBuildModel)),
        auxiliaryFiles = immutableList(source.auxiliaryFiles),
    )

    private fun freezeModule(source: Module): Module = Module(
        name = source.name,
        root = source.root,
        sourceRoots = immutableList(source.sourceRoots),
        classpathEntries = immutableList(source.classpathEntries),
        dependencies = immutableList(source.dependencies),
        languageSettings = immutableMap(source.languageSettings),
        mainSourceRoots = immutableList(source.mainSourceRoots),
        testSourceRoots = immutableList(source.testSourceRoots),
        generatedSourceRoots = immutableList(source.generatedSourceRoots),
        generatedTestSourceRoots = immutableList(source.generatedTestSourceRoots),
        mainClasspathEntries = immutableList(source.mainClasspathEntries),
        mainRuntimeClasspathEntries = immutableList(source.mainRuntimeClasspathEntries),
        testClasspathEntries = immutableList(source.testClasspathEntries),
        mainDependencies = immutableList(source.mainDependencies),
        testDependencies = immutableList(source.testDependencies),
        mainOutputDirectories = immutableList(source.mainOutputDirectories),
        testOutputDirectories = immutableList(source.testOutputDirectories),
    )

    private fun freezeBuildModel(source: BuildModel): BuildModel = BuildModel(
        providerId = source.providerId,
        status = source.status,
        modules = immutableList(source.modules.map(::freezeBuildModule)),
        diagnostics = immutableList(source.diagnostics),
        attributes = immutableMap(source.attributes),
    )

    private fun freezeBuildModule(source: BuildModule): BuildModule = BuildModule(
        id = source.id,
        name = source.name,
        root = source.root,
        sourceSets = immutableList(source.sourceSets.map(::freezeBuildSourceSet)),
        attributes = immutableMap(source.attributes),
    )

    private fun freezeBuildSourceSet(source: BuildSourceSet): BuildSourceSet = BuildSourceSet(
        id = source.id,
        kind = source.kind,
        sourceRoots = immutableList(source.sourceRoots),
        generatedSourceRoots = immutableList(source.generatedSourceRoots),
        outputDirectories = immutableList(source.outputDirectories),
        classpathEntries = immutableList(source.classpathEntries),
        runtimeClasspathEntries = immutableList(source.runtimeClasspathEntries),
        moduleDependencies = immutableList(source.moduleDependencies),
        attributes = immutableMap(source.attributes),
    )

    private fun freezeDiagnostics(source: List<Diagnostic>): List<Diagnostic> = immutableList(
        source.map { diagnostic ->
            diagnostic.copy(details = DiagnosticDetails(immutableMap(diagnostic.details.fields)))
        },
    )

    private fun attemptMutation(
        evaluation: AuthoritativeDiagnosticsEvaluation,
        expectedSnapshot: ProjectSnapshot,
        expectedDiagnostics: List<Diagnostic>,
        label: String,
        access: MutationAccess,
        mutation: () -> Unit,
        restore: () -> Unit,
    ): MutationAttempt {
        val failure = runCatching(mutation).exceptionOrNull()
        val changed = !evaluationIsExact(evaluation, expectedSnapshot, expectedDiagnostics)
        val cleanupFailure = if (failure == null || changed) runCatching(restore).exceptionOrNull() else null
        return MutationAttempt(
            label = label,
            access = access,
            rejected = failure != null,
            failureType = failure?.javaClass?.name,
            changedDuringAttempt = changed,
            cleanupFailure = cleanupFailure?.javaClass?.name,
            exactAfterCleanup = evaluationIsExact(evaluation, expectedSnapshot, expectedDiagnostics),
        )
    }

    private fun evaluationIsExact(
        evaluation: AuthoritativeDiagnosticsEvaluation,
        expectedSnapshot: ProjectSnapshot,
        expectedDiagnostics: List<Diagnostic>,
    ): Boolean = evaluation.snapshot == expectedSnapshot &&
        evaluation.diagnostics == expectedDiagnostics &&
        evaluation.snapshot.hash == expectedSnapshot.hash &&
        freshSnapshotHash(evaluation.snapshot) == expectedSnapshot.hash

    private fun freshSnapshotHash(snapshot: ProjectSnapshot): String = ProjectSnapshot.hashSnapshot(
        snapshot.modules,
        snapshot.files,
        snapshot.sourceExtensions,
        snapshot.ignoredDirectories,
        snapshot.classpathEvidence,
        snapshot.buildModels,
        snapshot.auxiliaryFiles,
    )

    private fun <T> listProbe(
        label: String,
        caller: MutableList<T>,
        sentinel: T,
        exposed: (AuthoritativeDiagnosticsEvaluation) -> List<T>,
    ): MutationProbe = MutationProbe(
        label = label,
        mutateCaller = { caller.add(sentinel) },
        restoreCaller = { caller.removeAt(caller.lastIndex) },
        mutateExposed = { mutableListView(exposed(it)).add(sentinel) },
        restoreExposed = { view ->
            val mutable = mutableListView(exposed(view))
            mutable.removeAt(mutable.lastIndex)
        },
    )

    private fun <T> setProbe(
        label: String,
        caller: MutableSet<T>,
        sentinel: T,
        exposed: (AuthoritativeDiagnosticsEvaluation) -> Set<T>,
    ): MutationProbe = MutationProbe(
        label = label,
        mutateCaller = { caller.add(sentinel) },
        restoreCaller = { caller.remove(sentinel) },
        mutateExposed = { mutableSetView(exposed(it)).add(sentinel) },
        restoreExposed = { mutableSetView(exposed(it)).remove(sentinel) },
    )

    private fun <K, V> mapProbe(
        label: String,
        caller: MutableMap<K, V>,
        sentinelKey: K,
        sentinelValue: V,
        exposed: (AuthoritativeDiagnosticsEvaluation) -> Map<K, V>,
    ): MutationProbe = MutationProbe(
        label = label,
        mutateCaller = { caller[sentinelKey] = sentinelValue },
        restoreCaller = { caller.remove(sentinelKey) },
        mutateExposed = { mutableMapView(exposed(it))[sentinelKey] = sentinelValue },
        restoreExposed = { mutableMapView(exposed(it)).remove(sentinelKey) },
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> mutableListView(view: List<T>): MutableList<T> = view as MutableList<T>

    @Suppress("UNCHECKED_CAST")
    private fun <T> mutableSetView(view: Set<T>): MutableSet<T> = view as MutableSet<T>

    @Suppress("UNCHECKED_CAST")
    private fun <K, V> mutableMapView(view: Map<K, V>): MutableMap<K, V> = view as MutableMap<K, V>

    private fun configureAuthoritativeProvider(behavior: ProviderBehavior, description: String) {
        val world = requireModelWorkspace()
        providerBehavior = behavior
        authoritativeTrace.clear()
        detachmentObservations.clear()
        val provider = AuthoritativeDiagnosticsProvider { candidate: ProjectSnapshot ->
            evaluateAuthoritativeCandidate(world, behavior, description, candidate, authoritativeTrace)
        }
        authoritativeProvider = provider
        authoritativeGate = DiagnosticsGate.authoritative(AUTHORITATIVE_GATE_ID, provider)
    }

    private fun evaluateAuthoritativeCandidate(
        world: ModelWorkspace,
        behavior: ProviderBehavior,
        description: String,
        candidate: ProjectSnapshot,
        trace: MutableList<AuthoritativeInvocation>,
    ): AuthoritativeDiagnosticsEvaluation {
        val invocation = trace.size + 1
        var authoritative = rehydrateSemanticModel(candidate)
        var diagnostics = diagnosticsFor(authoritative)
        if (invocation == 2) {
            when (behavior) {
                ProviderBehavior.WORKSPACE_ROOT -> {
                    val other = temporaryBoundary.resolve("different-bounded-workspace")
                    Files.createDirectories(other)
                    authoritative = authoritative.copy(workspace = Workspace(other))
                }
                ProviderBehavior.SOURCE_AUXILIARY_SPLIT -> {
                    val moved = authoritative.files.first()
                    authoritative = authoritative.copy(
                        files = immutableList(authoritative.files.drop(1)),
                        auxiliaryFiles = immutableList(authoritative.auxiliaryFiles + moved),
                    )
                }
                ProviderBehavior.TRACKED_PATH -> {
                    val changed = authoritative.files.first().copy(path = Path.of("src/main/java/example/OtherModel.java"))
                    authoritative = authoritative.copy(files = immutableList(listOf(changed) + authoritative.files.drop(1)))
                }
                ProviderBehavior.LANGUAGE_ID -> {
                    val changed = authoritative.files.first().copy(languageId = "synthetic-java")
                    authoritative = authoritative.copy(files = immutableList(listOf(changed) + authoritative.files.drop(1)))
                }
                ProviderBehavior.TRACKED_CONTENT -> {
                    val changed = authoritative.files.first().copy(content = authoritative.files.first().content + "// provider drift\n")
                    authoritative = authoritative.copy(files = immutableList(listOf(changed) + authoritative.files.drop(1)))
                }
                ProviderBehavior.SOURCE_EXTENSIONS -> authoritative = authoritative.copy(
                    sourceExtensions = immutableSet(authoritative.sourceExtensions + "kt"),
                )
                ProviderBehavior.IGNORED_DIRECTORIES -> authoritative = authoritative.copy(
                    ignoredDirectories = immutableSet(authoritative.ignoredDirectories + "vendor"),
                )
                ProviderBehavior.MODULE_ROOT_OUTSIDE -> {
                    val outside = outsideWorkspacePath(world, "module-root")
                    authoritative = authoritative.copy(modules = immutableList(
                        authoritative.modules.mapIndexed { index, module ->
                            if (index == 0) module.copy(root = outside) else module
                        },
                    ))
                }
                ProviderBehavior.MODULE_SOURCE_ROOT_OUTSIDE -> {
                    val outside = outsideWorkspacePath(world, "module-source-root")
                    authoritative = authoritative.copy(modules = immutableList(
                        authoritative.modules.mapIndexed { index, module ->
                            if (index == 0) module.copy(sourceRoots = immutableList(module.sourceRoots + listOf(outside))) else module
                        },
                    ))
                }
                ProviderBehavior.BUILD_MODULE_ROOT_OUTSIDE -> {
                    val outside = outsideWorkspacePath(world, "build-module-root")
                    authoritative = authoritative.copy(buildModels = immutableList(
                        authoritative.buildModels.mapIndexed { modelIndex, model ->
                            if (modelIndex != 0) model else model.copy(modules = immutableList(
                                model.modules.mapIndexed { moduleIndex, module ->
                                    if (moduleIndex == 0) module.copy(root = outside) else module
                                },
                            ))
                        },
                    ))
                }
                ProviderBehavior.FABRICATED_EXTERNAL_CLASSPATH_FINGERPRINT -> {
                    val expected = exactExternalEvidence(authoritative)
                    val fabricated = if (expected.fingerprint == "0".repeat(64)) "1".repeat(64) else "0".repeat(64)
                    authoritative = authoritative.copy(classpathEvidence = immutableList(
                        authoritative.classpathEvidence.map { evidence ->
                            if (evidence == expected) evidence.copy(fingerprint = fabricated) else evidence
                        },
                    ))
                }
                ProviderBehavior.LIVE_WORKSPACE_MUTATION -> {
                    Files.writeString(world.root.resolve(SOURCE_PATH), SOURCE_CONTENT + EXTERNAL_DRIFT_SUFFIX)
                }
                else -> Unit
            }
        }
        if (invocation == 3 && behavior == ProviderBehavior.POST_SEMANTIC_MISMATCH) {
            authoritative = authoritative.copy(
                modules = semanticModules("committed-mismatch", exactExternalEvidence(authoritative).path),
            )
            assertNotEquals(world.s1.hash, authoritative.hash)
            diagnostics = world.d1
        }
        if (invocation == 3 && behavior == ProviderBehavior.POST_DIAGNOSTIC_MISMATCH) {
            assertEquals(world.s1.hash, authoritative.hash)
            diagnostics = immutableList(world.d1 + mismatchDiagnostic())
        }

        val evaluation = if (deepDetachmentRequired && mutationExerciseRequired) {
            assertEquals(ProviderBehavior.EXACT, behavior)
            constructMutableEvaluation(authoritative, diagnostics, phaseFor(invocation, behavior))
        } else {
            AuthoritativeDiagnosticsEvaluation(
                snapshot = authoritative,
                diagnostics = immutableList(diagnostics),
            )
        }
        assertEquals(authoritative, evaluation.snapshot)
        assertEquals(diagnostics, evaluation.diagnostics)
        trace += AuthoritativeInvocation(
            phase = phaseFor(invocation, behavior),
            description = description,
            input = candidate,
            outputSnapshot = evaluation.snapshot,
            diagnostics = immutableList(evaluation.diagnostics),
            workspaceLockHeld = workspaceLockIsHeld(world.root),
        )
        return evaluation
    }

    private fun invokeAuthoritativeApply() {
        val world = requireModelWorkspace()
        val gate = authoritativeGate ?: fail("The authoritative DiagnosticsGate was not configured")
        assertEquals(0, authoritativeTrace.size, "Provider must not run before PatchEngine.apply")
        authoritativeResult = world.engine.apply(
            world.plan,
            world.s0,
            ApplyAuthorization.explicit("cucumber-authoritative-evaluation", "story-bdd"),
            gate,
        )
    }

    private fun verifyPostProviderLeaseRevalidationBeforeWal() {
        val probe = createModelWorkspace("post-provider-lease-revalidation")
        val trace = mutableListOf<AuthoritativeInvocation>()
        val provider = AuthoritativeDiagnosticsProvider { candidate: ProjectSnapshot ->
            if (trace.size == 1) {
                Files.writeString(probe.root.resolve(LEASE_PATH), LEASE_CONTENT + "external-drift\n")
            }
            evaluateAuthoritativeCandidate(probe, ProviderBehavior.EXACT, "lease revalidation", candidate, trace)
        }
        val gate = DiagnosticsGate.authoritative("authoritative-lease-revalidation", provider)
        val result = probe.engine.apply(
            probe.plan,
            probe.s0,
            ApplyAuthorization.explicit("cucumber-authoritative-revalidation", "story-bdd"),
            gate,
        )
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any {
            it.code == "authorityLease.evidenceDrift" || it.code == "snapshot.scopeChanged"
        })
        assertEquals(2, trace.size)
        assertTrue(trace.all(AuthoritativeInvocation::workspaceLockHeld))
        assertEquals(emptyList(), probe.log.listRecordsReadOnly())
        assertManagedTargetsRemainUnapplied(probe)
    }

    private fun verifyErrorMultisetAndIdentityRefusal() {
        val probe = createDiagnosticsWorkspace("compatibility-error-multiset")
        var calls = 0
        val baseline = exactError(0)
        val moved = exactError(1)
        val gate = DiagnosticsGate.enabled("compatibility-error-multiset") {
            calls += 1
            if (calls == 1) immutableList(baseline) else immutableList(baseline, baseline, moved)
        }
        val result = applyDiagnosticsWorkspace(probe, gate)
        val refused = assertIs<ApplyResult.Refused>(result)
        val regression = refused.diagnostics.single { it.code == "diagnostics.regression" }
        assertTrue(regression.message.contains("2 unapproved new error"))
        assertEquals(2, calls)
        assertEquals(emptyList(), probe.log.listRecordsReadOnly())
        assertWorkspaceMatchesSnapshot(probe.root, probe.s0)
    }

    private fun verifyApprovedRegression() {
        val baseline = exactError(0)
        val moved = exactError(1)
        val probe = createDiagnosticsWorkspace(
            "compatibility-approved-regression",
            approvedAfterDiagnostics = immutableList(baseline, moved),
        )
        var calls = 0
        val gate = DiagnosticsGate.enabled("compatibility-approved-regression") {
            calls += 1
            if (calls == 1) immutableList(baseline) else immutableList(baseline, baseline, moved)
        }
        val result = applyDiagnosticsWorkspace(probe, gate)
        assertIs<ApplyResult.Applied>(result)
        assertEquals(3, calls)
        assertEquals(JournalState.APPLIED, probe.log.listRecordsReadOnly().single().state)
        assertWorkspaceMatchesSnapshot(probe.root, probe.c1)
    }

    private fun verifyProviderUnavailability() {
        val probe = createDiagnosticsWorkspace("compatibility-unavailable")
        var calls = 0
        val gate = DiagnosticsGate.enabled("compatibility-unavailable") {
            calls += 1
            if (calls == 2) error("synthetic staged provider unavailable")
            compatibilityDiagnostics(it)
        }
        val result = applyDiagnosticsWorkspace(probe, gate)
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "diagnostics.unavailable" })
        assertEquals(2, calls)
        assertEquals(emptyList(), probe.log.listRecordsReadOnly())
        assertWorkspaceMatchesSnapshot(probe.root, probe.s0)
    }

    private fun verifyDiagnosticsPostApplyRollback() {
        val probe = createDiagnosticsWorkspace("compatibility-post-apply-rollback")
        var calls = 0
        val baseline = immutableList(exactError(0))
        val mismatch = immutableList(exactError(0), exactError(1))
        val gate = DiagnosticsGate.enabled("compatibility-post-apply-rollback") {
            calls += 1
            when (calls) {
                1, 2, 4 -> baseline
                3 -> mismatch
                else -> fail("Unexpected compatibility provider invocation $calls")
            }
        }
        val result = applyDiagnosticsWorkspace(probe, gate)
        val refused = assertIs<ApplyResult.Refused>(result)
        assertTrue(refused.diagnostics.any { it.code == "diagnostics.postApplyMismatch" })
        assertEquals(4, calls)
        val record = probe.log.listRecordsReadOnly().single()
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertEquals(
            listOf(
                JournalState.PREPARED,
                JournalState.APPLYING,
                JournalState.APPLIED,
                JournalState.ROLLING_BACK,
                JournalState.ROLLED_BACK,
            ),
            record.history.map { it.state },
        )
        assertWorkspaceMatchesSnapshot(probe.root, probe.s0)
    }

    private fun verifyExistingRecordMeanings(workspace: DiagnosticsWorkspace, applied: ApplyResult.Applied) {
        val first = workspace.log.listRecordsReadOnly().single()
        val journal = journalPath(workspace.log, first)
        val bytesBefore = Files.readAllBytes(journal)
        val second = workspace.log.listRecordsReadOnly().single()
        val bytesAfter = Files.readAllBytes(journal)
        assertTrue(bytesBefore.contentEquals(bytesAfter), "Read-only inspection rewrote the record")
        assertEquals(first, second)
        assertEquals(8, second.schemaVersion)
        assertEquals(workspace.s0.hash, second.preSnapshotHash)
        assertEquals(workspace.c1.hash, second.postSnapshotHash)
        assertEquals(workspace.plan.id, second.transaction.planId)
        assertEquals(applied.transaction.id, second.transaction.id)
        assertEquals(applied.transaction, second.transaction)
        assertEquals(workspace.plan.workspaceEdit, second.forwardEdit)
        assertEquals(expectedImages(workspace.s0, workspace.plan.workspaceEdit.affectedFiles()), second.preImages.associate {
            it.path.normalize() to it.content
        })
        assertEquals(expectedImages(workspace.c1, workspace.plan.workspaceEdit.affectedFiles()), second.postImages.associate {
            it.path.normalize() to it.content
        })
        val checksum = CHECKSUM_PATTERN.find(Files.readString(journal))?.groupValues?.get(1)
        assertNotNull(checksum)
        assertTrue(SHA256_PATTERN.matches(checksum))
    }

    private fun createModelWorkspace(name: String): ModelWorkspace {
        val root = createWorkspaceRoot(name)
        writeBaselineWorkspace(root)
        val s0 = baselineSnapshot(root)
        val edit = WorkspaceEdit(immutableList(
            FileEdit.Modify(
                MODEL_PATH,
                immutableList(TextEdit(
                    SourceRange(SourcePosition(0, MODEL_VALUE_START), SourcePosition(0, MODEL_VALUE_END)),
                    "candidate",
                )),
            ),
            FileEdit.Create(CREATED_FILE, CREATED_CONTENT),
        ))
        val c1 = WorkspaceEditSimulator.apply(s0, edit)
        val s1 = rehydrateSemanticModel(c1)
        val leaseEvidence = OperationAuthorityFileEvidence(
            kind = "synthetic-model-input",
            path = LEASE_PATH,
            expectedContentSha256 = sha256(LEASE_CONTENT.toByteArray()),
            attributes = immutableMap(mapOf("scope" to "authoritative-evaluation")),
        )
        val operation = "syntheticModelChangingEdit"
        val lease = OperationAuthorityLease(
            kind = "synthetic-build-model",
            operation = operation,
            snapshotHash = s0.hash,
            evidenceHash = sha256("$operation\u0000${s0.hash}".toByteArray()),
            requiredFileEvidence = immutableList(leaseEvidence),
            attributes = immutableMap(mapOf("semanticFields" to "modules,classpathEvidence,buildModels")),
        )
        val d0 = diagnosticsFor(s0)
        val d1 = diagnosticsFor(s1)
        val plan = PatchPlan(
            operation = operation,
            status = PatchStatus.PREVIEW,
            snapshotHash = s0.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Change one synthetic build model through an authoritative candidate evaluation",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = d0,
            diagnosticsAfterPreview = emptyList(),
            evidence = RefactoringEvidence.STRUCTURAL,
            authorityLease = lease,
        )
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        return ModelWorkspace(
            root = root,
            s0 = s0,
            c1 = c1,
            s1 = s1,
            d0 = d0,
            d1 = d1,
            plan = plan,
            log = log,
            engine = PatchEngine(root, log),
            baselineBytes = captureWorkspaceBytes(root, s0.trackedFiles.map(SourceFile::path)),
        )
    }

    private fun createDiagnosticsWorkspace(
        name: String,
        approvedAfterDiagnostics: List<Diagnostic> = emptyList(),
    ): DiagnosticsWorkspace {
        val root = createWorkspaceRoot(name)
        writeBaselineWorkspace(root)
        val s0 = baselineSnapshot(root)
        val valueOffset = SOURCE_CONTENT.indexOf("return 0") + "return ".length
        val edit = WorkspaceEdit(immutableList(FileEdit.Modify(
            SOURCE_PATH,
            immutableList(TextEdit(
                SourceRange(SourcePosition(0, valueOffset), SourcePosition(0, valueOffset + 1)),
                "1",
            )),
        )))
        val c1 = WorkspaceEditSimulator.apply(s0, edit)
        val plan = PatchPlan(
            operation = "syntheticDiagnosticsOnlyEdit",
            status = PatchStatus.PREVIEW,
            snapshotHash = s0.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Change one source byte without changing semantic build metadata",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = compatibilityDiagnostics(s0),
            diagnosticsAfterPreview = immutableList(approvedAfterDiagnostics),
            evidence = RefactoringEvidence.STRUCTURAL,
        )
        val log = TransactionLog(root.resolve(".refactorkit/transactions"))
        return DiagnosticsWorkspace(root, s0, c1, plan, log, PatchEngine(root, log))
    }

    private fun createWorkspaceRoot(name: String): Path {
        val root = temporaryBoundary.resolve(name).normalize()
        require(root.parent == temporaryBoundary && root.startsWith(temporaryBoundary))
        Files.createDirectory(root)
        return root
    }

    private fun writeBaselineWorkspace(root: Path) {
        val files = mapOf(
            SOURCE_PATH to SOURCE_CONTENT,
            MODEL_PATH to MODEL_BASELINE_CONTENT,
            LEASE_PATH to LEASE_CONTENT,
            PLATFORM_PATH to PLATFORM_CONTENT,
        )
        files.forEach { (relative, content) ->
            val target = root.resolve(relative).normalize()
            require(target.startsWith(root))
            Files.createDirectories(target.parent)
            Files.writeString(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        }
    }

    private fun baselineSnapshot(root: Path): ProjectSnapshot {
        val source = SourceFile(SOURCE_PATH, SOURCE_CONTENT, "java")
        val auxiliary = immutableList(
            SourceFile(MODEL_PATH, MODEL_BASELINE_CONTENT, "properties"),
            SourceFile(LEASE_PATH, LEASE_CONTENT, "text"),
            SourceFile(PLATFORM_PATH, PLATFORM_CONTENT, "signature"),
        )
        val externalEvidence = createExternalClasspathEvidence(root)
        return ProjectSnapshot(
            workspace = Workspace(root),
            modules = semanticModules("baseline", externalEvidence.path),
            files = immutableList(source),
            sourceExtensions = immutableSet(setOf("java")),
            ignoredDirectories = immutableSet(ProjectSnapshot.DEFAULT_IGNORED_DIRECTORIES),
            classpathEvidence = immutableList(externalEvidence),
            buildModels = semanticBuildModels("baseline", externalEvidence.path),
            auxiliaryFiles = auxiliary,
        )
    }

    private fun createExternalClasspathEvidence(workspaceRoot: Path): ClasspathEvidence {
        val external = temporaryBoundary.resolve("external-classpath-evidence.bin").toAbsolutePath().normalize()
        assertFalse(external.startsWith(workspaceRoot.toAbsolutePath().normalize()))
        if (!Files.exists(external, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(
                external,
                EXTERNAL_CLASSPATH_CONTENT,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        }
        assertTrue(Files.isRegularFile(external, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(external))
        assertEquals(EXTERNAL_CLASSPATH_CONTENT, Files.readString(external))
        return ClasspathEvidence.capture(workspaceRoot, external, ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT)
    }

    private fun exactExternalEvidence(snapshot: ProjectSnapshot): ClasspathEvidence =
        snapshot.classpathEvidence.single { it.path.isAbsolute }

    private fun rehydrateSemanticModel(candidate: ProjectSnapshot): ProjectSnapshot {
        val model = candidate.auxiliaryFiles.single { it.path.normalize() == MODEL_PATH }.content
        val revision = when (model) {
            MODEL_BASELINE_CONTENT -> "baseline"
            MODEL_CANDIDATE_CONTENT -> "candidate"
            else -> "unknown-${sha256(model.toByteArray()).take(12)}"
        }
        val external = exactExternalEvidence(candidate).path
        return candidate.copy(
            modules = semanticModules(revision, external),
            classpathEvidence = immutableList(candidate.classpathEvidence),
            buildModels = semanticBuildModels(revision, external),
        )
    }

    private fun semanticModules(revision: String, externalClasspath: Path): List<Module> = immutableList(Module(
        name = "synthetic-$revision",
        root = Path.of("."),
        sourceRoots = immutableList(Path.of("src/main/java")),
        classpathEntries = immutableList(externalClasspath),
        dependencies = immutableList("synthetic-dependency-$revision"),
        languageSettings = immutableMap(mapOf("modelRevision" to revision, "release" to "21")),
        mainSourceRoots = immutableList(Path.of("src/main/java")),
        testSourceRoots = immutableList(Path.of("src/test/java")),
        generatedSourceRoots = immutableList(Path.of("build/generated/sources/main")),
        generatedTestSourceRoots = immutableList(Path.of("build/generated/sources/test")),
        mainClasspathEntries = immutableList(externalClasspath),
        mainRuntimeClasspathEntries = immutableList(externalClasspath),
        testClasspathEntries = immutableList(externalClasspath),
        mainDependencies = immutableList("synthetic-main-dependency-$revision"),
        testDependencies = immutableList("synthetic-test-dependency-$revision"),
        mainOutputDirectories = immutableList(Path.of("build/classes/main")),
        testOutputDirectories = immutableList(Path.of("build/classes/test")),
    ))

    private fun semanticBuildModels(revision: String, externalClasspath: Path): List<BuildModel> {
        val moduleId = "synthetic-$revision"
        val generated = Path.of("build/generated/sources/main")
        return immutableList(BuildModel(
            providerId = "synthetic-build",
            status = BuildModelStatus.AVAILABLE,
            modules = immutableList(BuildModule(
                id = moduleId,
                name = moduleId,
                root = Path.of("."),
                sourceSets = immutableList(BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = immutableList(Path.of("src/main/java"), generated),
                    generatedSourceRoots = immutableList(generated),
                    outputDirectories = immutableList(Path.of("build/classes/main")),
                    classpathEntries = immutableList(externalClasspath),
                    runtimeClasspathEntries = immutableList(externalClasspath),
                    moduleDependencies = immutableList(BuildDependency(moduleId, DependencyScope.COMPILE)),
                    attributes = immutableMap(mapOf("modelRevision" to revision, "release" to "21")),
                )),
                attributes = immutableMap(mapOf("modelRevision" to revision, "kind" to "synthetic")),
            )),
            diagnostics = immutableList(BuildModelDiagnostic(
                code = "synthetic.build.$revision",
                message = "synthetic build model $revision",
                moduleId = moduleId,
                severity = Diagnostic.Severity.INFO,
            )),
            attributes = immutableMap(mapOf("modelRevision" to revision, "synthetic" to "true")),
        ))
    }

    private fun diagnosticsFor(snapshot: ProjectSnapshot): List<Diagnostic> {
        val revision = snapshot.buildModels.single().attributes.getValue("modelRevision")
        return if (revision == "baseline") {
            immutableList(Diagnostic(
                message = "synthetic baseline model is available",
                severity = Diagnostic.Severity.INFO,
                location = SourceLocation(SOURCE_PATH, SourceRange(SourcePosition(0, 0), SourcePosition(0, 1))),
                code = "synthetic.model.baseline",
                evidence = DiagnosticEvidence.COMPILER,
                category = DiagnosticCategory.PROJECT_STRUCTURE,
                details = DiagnosticDetails(immutableMap(mapOf(
                    "modelRevision" to revision,
                    "semanticBoundary" to "baseline",
                ))),
            ))
        } else {
            val diagnostic = Diagnostic(
                message = "synthetic candidate model is available",
                severity = Diagnostic.Severity.WARNING,
                location = SourceLocation(SOURCE_PATH, SourceRange(SourcePosition(0, 1), SourcePosition(0, 2))),
                code = "synthetic.model.candidate",
                evidence = DiagnosticEvidence.COMPILER,
                category = DiagnosticCategory.PROJECT_STRUCTURE,
                details = DiagnosticDetails(immutableMap(mapOf(
                    "modelRevision" to revision,
                    "semanticBoundary" to "candidate",
                ))),
            )
            immutableList(diagnostic, diagnostic)
        }
    }

    private fun outsideWorkspacePath(world: ModelWorkspace, role: String): Path {
        val outside = temporaryBoundary.resolve("provider-outside-${world.root.fileName}-$role").toAbsolutePath().normalize()
        assertFalse(outside.startsWith(world.root.toAbsolutePath().normalize()))
        return outside
    }

    private fun compatibilityDiagnostics(snapshot: ProjectSnapshot): List<Diagnostic> = immutableList(Diagnostic(
        message = "diagnostics-only candidate ${snapshot.hash}",
        severity = Diagnostic.Severity.WARNING,
        code = "synthetic.compatibility",
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    ))

    private fun mismatchDiagnostic(): Diagnostic = Diagnostic(
        message = "post-apply diagnostic mismatch",
        severity = Diagnostic.Severity.WARNING,
        code = "synthetic.post-apply-mismatch",
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.SAFETY,
    )

    private fun exactError(line: Int): Diagnostic = Diagnostic(
        message = "same compiler error",
        severity = Diagnostic.Severity.ERROR,
        location = SourceLocation(
            SOURCE_PATH,
            SourceRange(SourcePosition(line, 0), SourcePosition(line, 1)),
        ),
        code = "synthetic.same-error",
        evidence = DiagnosticEvidence.COMPILER,
        category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun applyDiagnosticsWorkspace(workspace: DiagnosticsWorkspace, gate: DiagnosticsGate): ApplyResult =
        workspace.engine.apply(
            workspace.plan,
            workspace.s0,
            ApplyAuthorization.explicit("cucumber-diagnostics-compatibility", "story-bdd"),
            gate,
        )

    private fun assertCandidateShellEquals(candidate: ProjectSnapshot, authoritative: ProjectSnapshot) {
        assertEquals(candidate.workspace.root.toAbsolutePath().normalize(), authoritative.workspace.root.toAbsolutePath().normalize())
        assertEquals(candidate.files.map(SourceFile::path), authoritative.files.map(SourceFile::path))
        assertEquals(candidate.auxiliaryFiles.map(SourceFile::path), authoritative.auxiliaryFiles.map(SourceFile::path))
        assertEquals(candidate.files, authoritative.files)
        assertEquals(candidate.auxiliaryFiles, authoritative.auxiliaryFiles)
        assertEquals(candidate.sourceExtensions, authoritative.sourceExtensions)
        assertEquals(candidate.ignoredDirectories, authoritative.ignoredDirectories)
    }

    private fun assertSnapshotCollectionsRejectMutation(snapshot: ProjectSnapshot) {
        fun rejected(action: () -> Unit) {
            val failure = runCatching(action).exceptionOrNull()
            assertNotNull(failure, "Synthetic snapshot collection unexpectedly accepted mutation")
        }
        @Suppress("UNCHECKED_CAST")
        rejected { (snapshot.files as MutableList<SourceFile>).clear() }
        @Suppress("UNCHECKED_CAST")
        rejected { (snapshot.sourceExtensions as MutableSet<String>).add("mutable") }
        @Suppress("UNCHECKED_CAST")
        rejected { (snapshot.buildModels as MutableList<BuildModel>).clear() }
        assertEquals(snapshot.hash, rehydrateSemanticModel(snapshot).hash)
    }

    private fun assertManagedTargetsRemainUnapplied(world: ModelWorkspace) {
        assertEquals(MODEL_BASELINE_CONTENT, Files.readString(world.root.resolve(MODEL_PATH)))
        assertFalse(Files.exists(world.root.resolve(CREATED_FILE), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(world.root.resolve(CREATED_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
    }

    private fun assertWorkspaceMatchesSnapshot(root: Path, snapshot: ProjectSnapshot) {
        snapshot.trackedFiles.forEach { expected ->
            val actual = root.resolve(expected.path).normalize()
            assertTrue(actual.startsWith(root))
            assertTrue(Files.isRegularFile(actual, LinkOption.NOFOLLOW_LINKS), "Missing tracked file ${expected.path}")
            assertEquals(expected.content, Files.readString(actual), "Tracked content differs for ${expected.path}")
        }
        val expectedPaths = snapshot.trackedFiles.map { it.path.normalize() }.toSet()
        val knownPaths = listOf(SOURCE_PATH, MODEL_PATH, LEASE_PATH, PLATFORM_PATH, CREATED_FILE)
        knownPaths.filterNot(expectedPaths::contains).forEach { absent ->
            assertFalse(Files.exists(root.resolve(absent), LinkOption.NOFOLLOW_LINKS), "Unexpected tracked file $absent")
        }
    }

    private fun expectedImages(snapshot: ProjectSnapshot, paths: Collection<Path>): Map<Path, String?> {
        val tracked = snapshot.trackedFiles.associateBy { it.path.normalize() }
        return paths.map(Path::normalize).distinct().associateWith { tracked[it]?.content }
    }

    private fun captureWorkspaceBytes(root: Path, paths: Collection<Path>): Map<Path, String> =
        paths.map(Path::normalize).distinct().associateWith { relative ->
            sha256(Files.readAllBytes(root.resolve(relative)))
        }

    private fun diagnosticDescribesBoundary(diagnostic: Diagnostic, boundary: String): Boolean {
        if (diagnostic.severity != Diagnostic.Severity.ERROR) return false
        val text = buildString {
            append(diagnostic.code.orEmpty())
            append(' ')
            append(diagnostic.message)
            diagnostic.details.fields.forEach { (key, value) -> append(" $key $value") }
        }.lowercase().replace(Regex("[^a-z0-9]+"), " ")
        val requiredTokens = boundary.lowercase().replace(Regex("[^a-z0-9]+"), " ")
            .split(' ').filter { it.length > 2 && it !in setOf("and", "policy") }
        return requiredTokens.all(text::contains)
    }

    private fun workspaceLockIsHeld(root: Path): Boolean {
        val lockPath = root.resolve(".refactorkit/workspace.lock")
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return false
        return FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
            try {
                val probe = channel.tryLock()
                if (probe == null) true else {
                    probe.release()
                    false
                }
            } catch (_: OverlappingFileLockException) {
                true
            }
        }
    }

    private fun phaseFor(invocation: Int, behavior: ProviderBehavior): String = when (invocation) {
        1 -> "baseline"
        2 -> "staged"
        3 -> "committed"
        4 -> if (behavior in POST_APPLY_BEHAVIORS) "restored" else "unexpected-fourth"
        else -> "unexpected-$invocation"
    }

    private fun journalPath(log: TransactionLog, record: TransactionJournalRecord): Path =
        log.logDir.resolve("${record.transaction.id.value}.json")

    private fun requireModelWorkspace(): ModelWorkspace = modelWorkspace ?: fail("Model-changing workspace is not initialized")

    private fun requireCompatibility(): CompatibilityWorld = compatibility ?: fail("Compatibility world is not initialized")

    private fun deleteTreeNoFollow(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(file.normalize().startsWith(root))
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                require(dir.normalize().startsWith(root))
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private data class ModelWorkspace(
        val root: Path,
        val s0: ProjectSnapshot,
        val c1: ProjectSnapshot,
        val s1: ProjectSnapshot,
        val d0: List<Diagnostic>,
        val d1: List<Diagnostic>,
        val plan: PatchPlan,
        val log: TransactionLog,
        val engine: PatchEngine,
        val baselineBytes: Map<Path, String>,
    )

    private data class DiagnosticsWorkspace(
        val root: Path,
        val s0: ProjectSnapshot,
        val c1: ProjectSnapshot,
        val plan: PatchPlan,
        val log: TransactionLog,
        val engine: PatchEngine,
    )

    private data class AuthoritativeInvocation(
        val phase: String,
        val description: String,
        val input: ProjectSnapshot,
        val outputSnapshot: ProjectSnapshot,
        val diagnostics: List<Diagnostic>,
        val workspaceLockHeld: Boolean,
    )

    private data class DetachmentObservation(
        val phase: String,
        val evaluation: AuthoritativeDiagnosticsEvaluation,
        val expectedSnapshot: ProjectSnapshot,
        val expectedDiagnostics: List<Diagnostic>,
        val expectedHash: String,
        val attempts: List<MutationAttempt>,
    )

    private data class MutationProbe(
        val label: String,
        val mutateCaller: () -> Unit,
        val restoreCaller: () -> Unit,
        val mutateExposed: (AuthoritativeDiagnosticsEvaluation) -> Unit,
        val restoreExposed: (AuthoritativeDiagnosticsEvaluation) -> Unit,
    )

    private data class MutationAttempt(
        val label: String,
        val access: MutationAccess,
        val rejected: Boolean,
        val failureType: String?,
        val changedDuringAttempt: Boolean,
        val cleanupFailure: String?,
        val exactAfterCleanup: Boolean,
    )

    private enum class MutationAccess { CALLER_ALIAS, EXPOSED_VIEW }

    private class CompatibilityWorld(
        val enabled: DiagnosticsWorkspace,
        val disabled: DiagnosticsWorkspace,
    ) {
        var enabledGate: DiagnosticsGate? = null
        var disabledGate: DiagnosticsGate? = null
        val enabledInvocations = mutableListOf<ProjectSnapshot>()
        var providerCallsAtConstruction: Int = -1
        var enabledResult: ApplyResult? = null
        var disabledResult: ApplyResult? = null
        var enabledRecord: TransactionJournalRecord? = null
        var disabledRecord: TransactionJournalRecord? = null
    }

    private enum class ProviderBehavior {
        EXACT,
        WORKSPACE_ROOT,
        SOURCE_AUXILIARY_SPLIT,
        TRACKED_PATH,
        LANGUAGE_ID,
        TRACKED_CONTENT,
        SOURCE_EXTENSIONS,
        IGNORED_DIRECTORIES,
        MODULE_ROOT_OUTSIDE,
        MODULE_SOURCE_ROOT_OUTSIDE,
        BUILD_MODULE_ROOT_OUTSIDE,
        FABRICATED_EXTERNAL_CLASSPATH_FINGERPRINT,
        LIVE_WORKSPACE_MUTATION,
        POST_SEMANTIC_MISMATCH,
        POST_DIAGNOSTIC_MISMATCH,
    }

    private companion object {
        const val REQ_TAG_EXPRESSION =
            "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-001 or " +
                "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-002 or " +
                "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-003 or " +
                "@REQ-AUTHORITATIVE-DIAGNOSTICS-EVALUATION-004"
        const val AUTHORITATIVE_GATE_ID = "synthetic-authoritative-evaluation"
        const val SOURCE_CONTENT = "package example; final class Model { int value() { return 0; } }\n"
        const val MODEL_BASELINE_CONTENT = "model=baseline\n"
        const val MODEL_CANDIDATE_CONTENT = "model=candidate\n"
        const val LEASE_CONTENT = "lease=exact-s0\n"
        const val PLATFORM_CONTENT = "synthetic-platform-signature\n"
        const val EXTERNAL_CLASSPATH_CONTENT = "external-classpath-evidence\n"
        const val CREATED_CONTENT = "candidate-evidence\n"
        const val EXTERNAL_DRIFT_SUFFIX = "// external provider drift\n"
        const val MODEL_VALUE_START = 6
        const val MODEL_VALUE_END = 14

        val SOURCE_PATH: Path = Path.of("src/main/java/example/Model.java")
        val MODEL_PATH: Path = Path.of("build-model/model.properties")
        val LEASE_PATH: Path = Path.of("authority/lease.txt")
        val PLATFORM_PATH: Path = Path.of("evidence/platform.sig")
        val CREATED_DIRECTORY: Path = Path.of("generated/evaluation")
        val CREATED_FILE: Path = CREATED_DIRECTORY.resolve("candidate.txt")

        val DETACHMENT_BOUNDARIES = listOf(
            "ProjectSnapshot files, auxiliaryFiles, sourceExtensions, and ignoredDirectories",
            "ProjectSnapshot modules",
            "each Module's sourceRoots, mainSourceRoots, testSourceRoots, generatedSourceRoots, and generatedTestSourceRoots collections",
            "each Module's classpathEntries, mainClasspathEntries, mainRuntimeClasspathEntries, and testClasspathEntries collections",
            "each Module's dependencies, mainDependencies, testDependencies, mainOutputDirectories, testOutputDirectories, and languageSettings collections",
            "ProjectSnapshot classpathEvidence and buildModels",
            "each BuildModel's modules, diagnostics, and attributes collections",
            "each BuildModule's sourceSets and attributes collections",
            "each BuildSourceSet's sourceRoots, generatedSourceRoots, outputDirectories, classpathEntries, runtimeClasspathEntries, moduleDependencies, and attributes collections",
            "the diagnostics input list and every caller-owned fields map supplied to DiagnosticDetails",
        )
        val REQUIRED_MUTATION_LABELS = listOf(
            "ProjectSnapshot.files",
            "ProjectSnapshot.auxiliaryFiles",
            "ProjectSnapshot.sourceExtensions",
            "ProjectSnapshot.ignoredDirectories",
            "ProjectSnapshot.modules",
            "Module[0].sourceRoots",
            "Module[0].mainSourceRoots",
            "Module[0].testSourceRoots",
            "Module[0].generatedSourceRoots",
            "Module[0].generatedTestSourceRoots",
            "Module[0].classpathEntries",
            "Module[0].mainClasspathEntries",
            "Module[0].mainRuntimeClasspathEntries",
            "Module[0].testClasspathEntries",
            "Module[0].dependencies",
            "Module[0].mainDependencies",
            "Module[0].testDependencies",
            "Module[0].mainOutputDirectories",
            "Module[0].testOutputDirectories",
            "Module[0].languageSettings",
            "ProjectSnapshot.classpathEvidence",
            "ProjectSnapshot.buildModels",
            "BuildModel[0].modules",
            "BuildModel[0].diagnostics",
            "BuildModel[0].attributes",
            "BuildModule[0,0].sourceSets",
            "BuildModule[0,0].attributes",
            "BuildSourceSet[0,0,0].sourceRoots",
            "BuildSourceSet[0,0,0].generatedSourceRoots",
            "BuildSourceSet[0,0,0].outputDirectories",
            "BuildSourceSet[0,0,0].classpathEntries",
            "BuildSourceSet[0,0,0].runtimeClasspathEntries",
            "BuildSourceSet[0,0,0].moduleDependencies",
            "BuildSourceSet[0,0,0].attributes",
            "AuthoritativeDiagnosticsEvaluation.diagnostics",
            "Diagnostic[0].details.fields",
        )

        val PROVIDER_BEHAVIORS = mapOf(
            "returns a snapshot rooted at a different bounded workspace" to ProviderBehavior.WORKSPACE_ROOT,
            "repartitions one tracked file between source and auxiliary inventories" to ProviderBehavior.SOURCE_AUXILIARY_SPLIT,
            "returns one tracked file under a different normalized path" to ProviderBehavior.TRACKED_PATH,
            "returns one tracked file with a different language ID" to ProviderBehavior.LANGUAGE_ID,
            "returns one tracked file with different content" to ProviderBehavior.TRACKED_CONTENT,
            "returns a different sourceExtensions set" to ProviderBehavior.SOURCE_EXTENSIONS,
            "returns a different ignoredDirectories set" to ProviderBehavior.IGNORED_DIRECTORIES,
            "sets one Module.root outside C1.workspace.root after normalized workspace resolution" to ProviderBehavior.MODULE_ROOT_OUTSIDE,
            "sets one Module.sourceRoots entry outside C1.workspace.root after normalized workspace resolution" to ProviderBehavior.MODULE_SOURCE_ROOT_OUTSIDE,
            "sets one BuildModule.root outside C1.workspace.root after normalized workspace resolution" to ProviderBehavior.BUILD_MODULE_ROOT_OUTSIDE,
            "claims a fingerprint for explicit external regular-file classpathEvidence that differs from the engine-recomputed current no-follow byte fingerprint for its path and kind" to ProviderBehavior.FABRICATED_EXTERNAL_CLASSPATH_FINGERPRINT,
            "changes a live tracked file while evaluating and otherwise returns an exact candidate" to ProviderBehavior.LIVE_WORKSPACE_MUTATION,
        )
        val VIOLATED_BOUNDARIES = mapOf(
            "returns a snapshot rooted at a different bounded workspace" to "normalized workspace root",
            "repartitions one tracked file between source and auxiliary inventories" to "source and auxiliary partition",
            "returns one tracked file under a different normalized path" to "tracked path inventory",
            "returns one tracked file with a different language ID" to "tracked language identity",
            "returns one tracked file with different content" to "tracked content",
            "returns a different sourceExtensions set" to "source extension scope",
            "returns a different ignoredDirectories set" to "ignored-directory policy",
            "sets one Module.root outside C1.workspace.root after normalized workspace resolution" to "normalized workspace containment of Module.root",
            "sets one Module.sourceRoots entry outside C1.workspace.root after normalized workspace resolution" to "normalized workspace containment of Module.sourceRoots",
            "sets one BuildModule.root outside C1.workspace.root after normalized workspace resolution" to "normalized workspace containment of BuildModule.root",
            "claims a fingerprint for explicit external regular-file classpathEvidence that differs from the engine-recomputed current no-follow byte fingerprint for its path and kind" to "no-follow fingerprint authenticity of classpathEvidence",
            "changes a live tracked file while evaluating and otherwise returns an exact candidate" to "post-provider live S0 revalidation",
        )
        val POST_APPLY_RESULTS = mapOf(
            "a hash different from S1.hash with diagnostics D1" to ProviderBehavior.POST_SEMANTIC_MISMATCH,
            "exact S1.hash with diagnostics different from D1" to ProviderBehavior.POST_DIAGNOSTIC_MISMATCH,
        )
        val MISMATCHES = mapOf(
            "semantic snapshot mismatch" to ProviderBehavior.POST_SEMANTIC_MISMATCH,
            "diagnostic multiset mismatch" to ProviderBehavior.POST_DIAGNOSTIC_MISMATCH,
        )
        val POST_APPLY_BEHAVIORS = setOf(
            ProviderBehavior.POST_SEMANTIC_MISMATCH,
            ProviderBehavior.POST_DIAGNOSTIC_MISMATCH,
        )
        val NEW_SEMANTIC_BOUNDARY_BEHAVIORS = setOf(
            ProviderBehavior.MODULE_ROOT_OUTSIDE,
            ProviderBehavior.MODULE_SOURCE_ROOT_OUTSIDE,
            ProviderBehavior.BUILD_MODULE_ROOT_OUTSIDE,
            ProviderBehavior.FABRICATED_EXTERNAL_CLASSPATH_FINGERPRINT,
        )
        val FORBIDDEN_SERIALIZED_EVALUATOR_TERMS = listOf(
            "AuthoritativeDiagnosticsEvaluation",
            "AuthoritativeDiagnosticsProvider",
            AUTHORITATIVE_GATE_ID,
            "scannerRegistry",
            "orchestrationService",
        )
        val CHECKSUM_PATTERN = Regex("\\\"checksum\\\"\\s*:\\s*\\\"([a-f0-9]{64})\\\"")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun <T> immutableList(vararg values: T): List<T> =
            Collections.unmodifiableList(values.toList())

        fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))

        fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
            Collections.unmodifiableMap(LinkedHashMap(values))
    }
}

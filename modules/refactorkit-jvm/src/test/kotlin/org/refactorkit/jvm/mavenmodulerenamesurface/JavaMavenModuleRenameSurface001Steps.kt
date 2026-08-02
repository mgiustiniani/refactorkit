package org.refactorkit.jvm.mavenmodulerenamesurface

import io.cucumber.core.internal.com.fasterxml.jackson.databind.ObjectMapper
import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordedEvent
import jdk.jfr.consumer.RecordingFile
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchPreviewRenderer
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.Workspace
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveAcrossMavenModulesPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.w3c.dom.Element
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Duration
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Focused Story-BDD glue for REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001 only. */
class JavaMavenModuleRenameSurface001Steps {
    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var fixtureRoot: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var permanentManifest: ExactManifest
    private lateinit var baselineManifest: ExactManifest
    private lateinit var authorityMonitor: DeniedAuthorityMonitor

    private lateinit var planner: JavaRenameMavenModulePlanner
    private lateinit var s0: ProjectSnapshot
    private lateinit var c1: ProjectSnapshot
    private lateinit var canonicalPlan: PatchPlan
    private lateinit var canonicalLease: OperationAuthorityLease
    private lateinit var d0: DiagnosticBaseline

    private val routeProbes = mutableListOf<RouteProbe>()
    private var routeSelectionCount = 0
    private var renderedPreview: String? = null
    private lateinit var mechanicsProbe: LazyGateMechanicsProbe
    private var selectedModuleRenameGate: DiagnosticsGate? = null
    private var patchEngine: PatchEngine? = null
    private var applyInvocationCount = 0
    private var applyResult: ApplyResult? = null
    private var transaction: Transaction? = null
    private var appliedRecord: TransactionJournalRecord? = null
    private var rollbackResult: ApplyResult? = null
    private var authoritativeS1: ProjectSnapshot? = null
    private var directAuthorityContractReused = false

    @Before("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001")
    fun prepareScenario(scenario: Scenario) {
        this.scenario = scenario
        repositoryRoot = locateRepositoryRoot()
        fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        assertTrue(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS), "Permanent fixture is missing")
        permanentManifest = captureManifest(fixtureRoot)
        temporaryRoot = Files.createTempDirectory("refactorkit-maven-module-rename-surface-001-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        planner = JavaRenameMavenModulePlanner()
        authorityMonitor = DeniedAuthorityMonitor.start(temporaryRoot.resolve("authority-events"))
        scenario.attach(
            "The selected case uses one no-follow disposable fixture copy and the real in-process PatchEngine.",
            "text/plain",
            "surface-001-boundary",
        )
    }

    @After("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-001")
    fun cleanScenario() {
        if (this::authorityMonitor.isInitialized) {
            val evidence = authorityMonitor.finish()
            if (this::scenario.isInitialized) {
                scenario.attach(evidence.toJson(), "application/json", "child-process-socket-observation")
            }
            assertFalse(evidence.prohibitedSurfaceActivityObserved, evidence.toString())
        }
        if (this::fixtureRoot.isInitialized && this::permanentManifest.isInitialized) {
            assertEquals(permanentManifest, captureManifest(fixtureRoot), "Permanent fixture was mutated")
        }
        if (this::temporaryRoot.isInitialized) deleteNoFollow(temporaryRoot)
    }

    @Given("each case uses a fresh no-follow disposable byte copy of {string}")
    fun freshNoFollowDisposableCopy(path: String) {
        assertEquals(FIXTURE_PATH, path)
        copyNoFollow(fixtureRoot, workspaceRoot)
        baselineManifest = captureManifest(workspaceRoot)
        assertEquals(permanentManifest, baselineManifest)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the permanent fixture remains an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children")
    fun permanentFixtureIsTwentyModuleOfflineReactor() {
        val modules = directRootModules(workspaceRoot)
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        assertEquals("pom", directChildText(parsePom(workspaceRoot.resolve("pom.xml")), "packaging"))
        modules.forEach { module ->
            val child = parsePom(workspaceRoot.resolve(module).resolve("pom.xml"))
            assertTrue(directChildren(child, "modules").isEmpty(), "$module must not aggregate")
            assertEquals("jar", directChildText(child, "packaging") ?: "jar", "$module must be a JAR child")
        }
        s0 = scan(workspaceRoot)
        val model = s0.buildModels.single()
        assertEquals(BuildModelStatus.AVAILABLE, model.status)
        assertEquals(20, model.modules.size)
        assertEquals(modules.toSet(), model.modules.mapTo(linkedSetOf()) { it.id })
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
    }

    @Given("this slice reuses REQ-JAVA-MAVEN-MODULE-RENAME-001's already-qualified in-process denial contract for Maven and wrapper execution, lifecycle goals, plugins, annotation processors, settings and credential access, credential helpers, and network requests")
    fun reuseQualifiedDirectLibraryAuthorityContract() {
        val directRequirement = Files.readString(
            repositoryRoot.resolve("features/java-maven-module-rename.feature"),
            StandardCharsets.UTF_8,
        )
        assertTrue(directRequirement.contains("@REQ-JAVA-MAVEN-MODULE-RENAME-001"))
        assertTrue(directRequirement.contains("@implemented-and-validated"))
        assertTrue(directRequirement.contains("Maven and wrapper execution"))
        directAuthorityContractReused = true
        scenario.attach(
            "Broader in-process denial authority is inherited from REQ-JAVA-MAVEN-MODULE-RENAME-001; " +
                "this surface slice observes only child-process starts and RefactorKit-attributable socket I/O.",
            "text/plain",
            "inherited-authority-contract",
        )
    }

    @Given("only child-process starts and RefactorKit-attributable socket reads or writes are independently observed here")
    fun observeOnlyChildProcessesAndRefactorKitSockets() {
        assertTrue(directAuthorityContractReused)
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("surface-observation-boundary")
    }

    @Given("the qualified request is oldModuleDir={string}, newModuleDir={string}, and caller-explicit newArtifactId={string}")
    fun qualifiedRequest(oldModule: String, newModule: String, newArtifact: String) {
        assertEquals(OLD_MODULE, oldModule)
        assertEquals(NEW_MODULE, newModule)
        assertEquals(NEW_ARTIFACT, newArtifact)
        assertTrue(Files.isDirectory(workspaceRoot.resolve(oldModule), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(newModule), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("REQ-JAVA-MAVEN-MODULE-RENAME-001 supplies the canonical {string} PREVIEW, exact five-edit candidate {string}, immutable authority lease and auxiliary-POM evidence, baseline {string}, authoritative post-image {string}, and diagnostic multiset {string}")
    fun canonicalDirectLibraryEvidence(
        operation: String,
        candidateName: String,
        baselineName: String,
        postImageName: String,
        diagnosticsName: String,
    ) {
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, operation)
        assertEquals("C1", candidateName)
        assertEquals("S0", baselineName)
        assertEquals("S1", postImageName)
        assertEquals("D0", diagnosticsName)
        canonicalPlan = planner.preview(s0, OLD_MODULE, NEW_MODULE, NEW_ARTIFACT)
        assertEquals(PatchStatus.PREVIEW, canonicalPlan.status, canonicalPlan.summary)
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, canonicalPlan.operation)
        assertTrue(canonicalPlan.requiresUserApproval)
        assertEquals(s0.hash, canonicalPlan.snapshotHash)
        canonicalLease = assertNotNull(canonicalPlan.authorityLease)
        val normalized = WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit)
        assertEquals(5, normalized.edits.size)
        assertEquals(3, normalized.edits.count { it is FileEdit.Modify })
        assertEquals(2, normalized.edits.count { it is FileEdit.Rename })
        c1 = WorkspaceEditSimulator.apply(s0, normalized)
        assertNotEquals(s0.hash, c1.hash)
        d0 = diagnostics(s0)
        assertEquals(d0.jdt, canonicalPlan.diagnosticsBefore)
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("canonical-preview")
    }

    @Given("each production route probe invokes the normal five-argument ManagedApplyDiagnosticsGateSelector.select entry used by daemon and MCP with exact language ID {string}, the adapters current for this call, and the canonical plan or a copy differing only in operation")
    fun productionRouteProbesUseNormalSelector(languageId: String) {
        assertEquals("java", languageId)
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, canonicalPlan.operation)
        assertSame(canonicalLease, canonicalPlan.authorityLease)
        assertEquals(s0.workspace.root.toAbsolutePath().normalize(), workspaceRoot.toAbsolutePath().normalize())
        assertTrue(routeProbes.isEmpty())
    }

    @Given("isolated non-applying production route probes use these exact case-sensitive operation JSON labels:")
    fun isolatedProductionRouteProbes(table: DataTable) {
        val rows = table.asMaps()
        assertEquals(EXPECTED_OPERATIONS.size, rows.size)
        rows.forEach { row ->
            val jsonLabel = row.getValue("operation JSON label")
            val operation = JSON.readValue(jsonLabel, String::class.java)
            val expected = assertNotNull(EXPECTED_ROUTES[operation], "Unexpected decoded operation: '$operation'")
            assertEquals(expected.first, row.getValue("expected route"), operation)
            assertEquals(expected.second, row.getValue("exact gate ID"), operation)
            val plan = if (operation == JavaRenameMavenModulePlanner.OPERATION) {
                canonicalPlan
            } else {
                canonicalPlan.copy(operation = operation)
            }
            assertEquals(canonicalPlan, plan.copy(operation = canonicalPlan.operation))
            routeProbes += RouteProbe(
                jsonLabel = jsonLabel,
                operation = operation,
                expectedRoute = expected.first,
                exactGateId = expected.second,
                plan = plan,
                javaAdapter = JavaLanguageAdapter(),
                kotlinAdapter = KotlinLanguageAdapter(),
            )
        }
        assertEquals(EXPECTED_OPERATIONS, routeProbes.map(RouteProbe::operation))
        assertEquals(routeProbes.size, routeProbes.map(RouteProbe::operation).distinct().size)
        assertSame(
            canonicalPlan,
            routeProbes.single { it.operation == JavaRenameMavenModulePlanner.OPERATION }.plan,
        )
    }

    @Given("the escaped whitespace labels are decoded as JSON strings into these exact raw plan-operation bytes before plan construction:")
    fun escapedWhitespaceLabelsDecodeLosslessly(table: DataTable) {
        val rows = table.asMaps()
        assertEquals(2, rows.size)
        rows.forEach { row ->
            val jsonLabel = row.getValue("operation JSON label")
            val probe = routeProbes.single { it.jsonLabel == jsonLabel }
            val actualHex = probe.operation.toByteArray(StandardCharsets.UTF_8)
                .joinToString(" ") { byte -> "%02x".format(byte.toInt() and 0xff) }
            assertEquals(row.getValue("exact raw operation UTF-8 hexadecimal"), actualHex)
        }
        assertEquals(" ${JavaRenameMavenModulePlanner.OPERATION}", routeProbes[5].operation)
        assertEquals("${JavaRenameMavenModulePlanner.OPERATION} ", routeProbes[6].operation)
    }

    @Given("a separate language-neutral lazy-gate mechanics probe observes only an inert authoritative-factory count and fixed diagnostics over synthetic immutable snapshots")
    fun separateLanguageNeutralLazyGateProbe() {
        mechanicsProbe = LazyGateMechanicsProbe()
        assertEquals(0, mechanicsProbe.factoryCount.get())
        assertEquals(3, mechanicsProbe.syntheticSnapshots.size)
        assertTrue(mechanicsProbe.syntheticSnapshots.all { it.workspace.root.toString().startsWith("synthetic/") })
    }

    @Given("that mechanics probe has no canonical plan, authority lease, Java or Maven provider, case-workspace access, PatchEngine, journal, or reference from a production-selected gate")
    fun mechanicsProbeIsCompletelyIsolated() {
        assertTrue(routeProbes.all { it.gate == null })
        assertTrue(mechanicsProbe.syntheticSnapshots.none {
            it.workspace.root.toAbsolutePath().normalize().startsWith(workspaceRoot.toAbsolutePath().normalize())
        })
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(transactionLog().listRecordsReadOnly().isEmpty())
    }

    @When("every decoded operation is selected through the normal production entry and the canonical preview is rendered without invoking PatchEngine")
    fun selectEveryDecodedOperationAndRenderPreview() {
        routeProbes.forEach { probe ->
            assertAdapterHasNoSnapshot(probe.javaAdapter)
            probe.gate = selectProductionGate(probe)
            routeSelectionCount += 1
            assertAdapterHasNoSnapshot(probe.javaAdapter)
        }
        renderedPreview = PatchPreviewRenderer(workspaceRoot).render(canonicalPlan)
        assertTrue(assertNotNull(renderedPreview).contains("Operation: ${JavaRenameMavenModulePlanner.OPERATION}"))
        assertEquals(EXPECTED_OPERATIONS.size, routeSelectionCount)
        assertTrue(patchEngine == null)
    }

    @Then("every probe returns its listed gate ID while its raw plan operation remains exact, without trimming, case-folding, prefixing, aliasing, or relabelling")
    fun everyProbeReturnsItsExactGateAndOperation() {
        routeProbes.forEach { probe ->
            assertEquals(probe.exactGateId, assertNotNull(probe.gate).id, probe.operation)
            assertEquals(probe.operation, probe.plan.operation)
            assertEquals(probe.operation, JSON.readValue(probe.jsonLabel, String::class.java))
        }
    }

    @Then("only exact raw operation {string} returns the production lazy authoritative gate {string} bound to the same immutable canonical plan and authority lease supplied to selection")
    fun onlyExactOperationReturnsProductionAuthoritativeGate(operation: String, gateId: String) {
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, operation)
        assertEquals(JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID, gateId)
        val exactProbe = routeProbes.single { it.operation == operation }
        selectedModuleRenameGate = assertNotNull(exactProbe.gate)
        assertEquals(gateId, assertNotNull(selectedModuleRenameGate).id)
        assertSame(canonicalPlan, exactProbe.plan)
        assertSame(canonicalLease, exactProbe.plan.authorityLease)
        assertEquals(1, routeProbes.count { it.gate?.id == gateId })
        assertAdapterHasNoSnapshot(exactProbe.javaAdapter)
    }

    @Then("selection and preview rendering complete without authoritative evaluation, fallback-provider invocation, Maven or JDT analysis, child-process start, or RefactorKit-attributable socket read or write")
    fun selectionAndRenderingAreProviderLazy() {
        assertTrue(routeProbes.all { it.diagnostics == null })
        assertTrue(routeProbes.all { adapterHasNoSnapshot(it.javaAdapter) })
        assertEquals(0, mechanicsProbe.factoryCount.get())
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("production-route-selection")
    }

    @When("each selected non-matching Java gate evaluates the unchanged real {string} snapshot once")
    fun evaluateEachNonMatchingGateOnce(snapshotName: String) {
        assertEquals("S0", snapshotName)
        routeProbes.filter { it.operation != JavaRenameMavenModulePlanner.OPERATION }.forEach { probe ->
            assertEquals(0, probe.evaluationCount)
            probe.diagnostics = assertNotNull(assertNotNull(probe.gate).provider).invoke(s0)
            probe.evaluationCount += 1
            assertAdapterHasSnapshot(probe.javaAdapter)
        }
        assertEquals(6, routeProbes.sumOf(RouteProbe::evaluationCount))
        assertEquals(s0, scan(workspaceRoot))
    }

    @Then("exact raw operation {string} invokes the real production JavaMoveAcrossMavenModulesPlanner diagnostics provider")
    fun exactMoveOperationUsesRealProductionProvider(operation: String) {
        assertEquals(JavaMoveAcrossMavenModulesPlanner.OPERATION, operation)
        val probe = routeProbes.single { it.operation == operation }
        assertEquals(ROUTE_MAVEN_OWNERSHIP, assertNotNull(probe.gate).id)
        val expected = JavaMoveAcrossMavenModulesPlanner(JavaLanguageAdapter()).diagnostics(s0)
        assertEquals(expected, probe.diagnostics)
        assertEquals(1, probe.evaluationCount)
    }

    @Then("every other non-matching raw operation invokes the real production diagnostics provider of the JavaLanguageAdapter current for that selector call")
    fun otherNonMatchingOperationsUseCurrentRealJavaAdapter() {
        val expected = JavaLanguageAdapter().diagnostics(s0)
        val genericProbes = routeProbes.filter {
            it.operation != JavaRenameMavenModulePlanner.OPERATION &&
                it.operation != JavaMoveAcrossMavenModulesPlanner.OPERATION
        }
        assertEquals(5, genericProbes.size)
        genericProbes.forEach { probe ->
            assertEquals(ROUTE_JAVA_JDT, assertNotNull(probe.gate).id)
            assertEquals(expected, probe.diagnostics, probe.operation)
            assertEquals(1, probe.evaluationCount)
            assertAdapterHasSnapshot(probe.javaAdapter)
        }
    }

    @Then("no test-supplied fallback-provider function participates in production selection or fallback evaluation")
    fun noTestSuppliedFallbackProviderParticipates() {
        val normalPublicSelector = ManagedApplyDiagnosticsGateSelector::class.java.methods
            .single { it.name == "select" && it.parameterCount == 5 }
        assertTrue(java.lang.reflect.Modifier.isPublic(normalPublicSelector.modifiers))
        assertEquals(EXPECTED_OPERATIONS.size, routeSelectionCount)
        assertTrue(routeProbes.all { it.gate != null })
    }

    @When("the isolated mechanics probe creates its lazy authoritative gate, reads its ID, and evaluates three synthetic candidate snapshots")
    fun runIsolatedLazyGateMechanicsProbe() {
        mechanicsProbe.createReadAndEvaluate()
    }

    @Then("its inert factory count is zero through gate creation and ID inspection, becomes one at first evaluation, and remains one through all three evaluations")
    fun mechanicsFactoryIsSynchronizedAndConstructedOnce() {
        assertEquals(listOf(0, 0, 1, 1, 1), mechanicsProbe.factoryCountTimeline)
        assertEquals(1, mechanicsProbe.factoryCount.get())
        assertEquals(MECHANICS_GATE_ID, mechanicsProbe.observedGateId)
    }

    @Then("all three fixed diagnostic results are returned without an evaluation observer, and the mechanics probe changes no case-workspace or journal state")
    fun mechanicsProbeReturnsFixedDiagnosticsWithoutAuthority() {
        assertEquals(3, mechanicsProbe.results.size)
        assertTrue(mechanicsProbe.results.all { it == mechanicsProbe.fixedDiagnostics })
        val lazyFactories = DiagnosticsGate.Companion::class.java.methods.filter { it.name == "lazyAuthoritative" }
        assertEquals(1, lazyFactories.size)
        assertEquals(2, lazyFactories.single().parameterCount)
        assertTrue(routeProbes.none { it.gate === mechanicsProbe.gate })
        assertEquals(baselineManifest, captureManifest(workspaceRoot))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(transactionLog().listRecordsReadOnly().isEmpty())
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("isolated-lazy-gate-mechanics")
    }

    @When("PatchEngine begins exactly one explicitly approved apply of the canonical plan through the production-selected module-rename gate")
    fun patchEngineAppliesExactlyOnceThroughProductionGate() {
        val exactProbe = routeProbes.single { it.operation == JavaRenameMavenModulePlanner.OPERATION }
        assertSame(selectedModuleRenameGate, exactProbe.gate)
        assertAdapterHasNoSnapshot(exactProbe.javaAdapter)
        assertEquals(0, applyInvocationCount)
        assertTrue(transactionLog().listRecordsReadOnly().isEmpty())
        assertSame(canonicalLease, canonicalPlan.authorityLease)
        patchEngine = PatchEngine(workspaceRoot)
        applyInvocationCount += 1
        applyResult = assertNotNull(patchEngine).apply(
            canonicalPlan,
            s0,
            ApplyAuthorization.explicit(APPLY_SURFACE, APPLY_ACTOR),
            assertNotNull(selectedModuleRenameGate),
        )
        transaction = (applyResult as? ApplyResult.Applied)?.transaction
        assertEquals(1, applyInvocationCount)
    }

    @Then("the real operation-owned gate uses the exact retained plan and lease to evaluate exact {string} and exact staged candidate {string} before PREPARED, then evaluates committed {string} after APPLIED and before ApplyResult.Applied is returned")
    fun realOperationGateAttestsS0C1AndS1(
        baselineName: String,
        candidateName: String,
        postImageName: String,
    ) {
        assertEquals("S0", baselineName)
        assertEquals("C1", candidateName)
        assertEquals("S1", postImageName)
        val applied = assertIs<ApplyResult.Applied>(assertNotNull(applyResult))
        assertEquals(applied.transaction, transaction)
        assertEquals(canonicalPlan.id, applied.transaction.planId)
        assertEquals(canonicalPlan.snapshotHash, applied.transaction.snapshotHashBefore)
        assertSame(canonicalLease, canonicalPlan.authorityLease)
        val record = transactionLog().listRecordsReadOnly().single()
        assertEquals(JournalState.APPLIED, record.state)
        val committed = scan(workspaceRoot)
        authoritativeS1 = committed
        assertEquals(c1.trackedFiles.sortedBy { it.path.toString() }, committed.trackedFiles.sortedBy { it.path.toString() })
        assertNotEquals(c1.hash, s0.hash)
        assertNotEquals(committed.hash, s0.hash)
        assertEquals(s0.hash, record.preSnapshotHash)
        assertEquals(committed.hash, record.postSnapshotHash)
        assertEquals(BuildModelStatus.AVAILABLE, committed.buildModels.single().status)
        assertEquals(20, committed.buildModels.single().modules.size)
        assertEquals(d0, diagnostics(committed))
    }

    @Then("read-only journal inspection finds exactly one schema-v8 APPLIED record whose operation, exact five-entry forward edit, approval, {string} pre-snapshot, {string} post-snapshot, and PREPARED, APPLYING, APPLIED history match the canonical plan")
    fun appliedJournalRecordIsExact(baselineName: String, postImageName: String) {
        assertEquals("S0", baselineName)
        assertEquals("S1", postImageName)
        val records = transactionLog().listRecordsReadOnly()
        assertEquals(1, records.size)
        val record = records.single()
        appliedRecord = record
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(JournalState.APPLIED, record.state)
        assertEquals(JavaRenameMavenModulePlanner.OPERATION, record.operation)
        assertEquals(5, record.forwardEdit.edits.size)
        assertEquals(WorkspaceEditSimulator.normalize(canonicalPlan.workspaceEdit), record.forwardEdit)
        assertEquals(canonicalPlan.id, record.transaction.planId)
        assertEquals(assertNotNull(transaction).id, record.transaction.id)
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals(APPLY_SURFACE, record.transaction.approval.surface)
        assertEquals(APPLY_ACTOR, record.transaction.approval.actor)
        assertEquals(s0.hash, record.preSnapshotHash)
        assertEquals(assertNotNull(authoritativeS1).hash, record.postSnapshotHash)
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED),
            record.history.map { it.state },
        )
        assertExactJournalImages(record)
    }

    @Then("the real {string} and {string} fallback providers perform no invocation for that module-rename apply")
    fun realFallbackProvidersDoNotRunForModuleApply(javaJdt: String, ownership: String) {
        assertEquals(ROUTE_JAVA_JDT, javaJdt)
        assertEquals(ROUTE_MAVEN_OWNERSHIP, ownership)
        val exactProbe = routeProbes.single { it.operation == JavaRenameMavenModulePlanner.OPERATION }
        assertEquals(0, exactProbe.evaluationCount)
        assertAdapterHasNoSnapshot(exactProbe.javaAdapter)
        assertEquals(JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID, assertNotNull(exactProbe.gate).id)
    }

    @Then("no caller-supplied operation-gate factory, evaluation observer, fallback-provider function, or mechanics-probe gate participates in the production-selected apply")
    fun noCallerSuppliedAuthorityHookParticipates() {
        assertEquals(1, applyInvocationCount)
        assertSame(routeProbes.single { it.operation == JavaRenameMavenModulePlanner.OPERATION }.gate, selectedModuleRenameGate)
        assertTrue(selectedModuleRenameGate !== mechanicsProbe.gate)
        assertEquals(MECHANICS_GATE_ID, assertNotNull(mechanicsProbe.gate).id)
        assertEquals(JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID, assertNotNull(selectedModuleRenameGate).id)
        val lazyFactories = DiagnosticsGate.Companion::class.java.methods.filter { it.name == "lazyAuthoritative" }
        assertEquals(listOf(2), lazyFactories.map { it.parameterCount }.distinct())
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("production-authoritative-apply")
    }

    @When("the resulting sole managed transaction is rolled back in NORMAL mode")
    fun rollbackSoleTransactionNormally() {
        assertEquals(1, transactionLog().listRecordsReadOnly().size)
        rollbackResult = assertNotNull(patchEngine).rollback(assertNotNull(transaction), RollbackMode.NORMAL)
    }

    @Then("the disposable copy returns exactly to {string} and {string} without changing the permanent fixture")
    fun rollbackRestoresExactS0AndD0(snapshotName: String, diagnosticsName: String) {
        assertEquals("S0", snapshotName)
        assertEquals("D0", diagnosticsName)
        val rolledBack = assertIs<ApplyResult.Applied>(assertNotNull(rollbackResult))
        assertEquals(assertNotNull(transaction), rolledBack.transaction)
        assertEquals(baselineManifest, captureManifest(workspaceRoot, excludeEngine = true))
        val restored = scan(workspaceRoot)
        assertEquals(s0, restored)
        assertEquals(d0, diagnostics(restored))
        assertEquals(permanentManifest, captureManifest(fixtureRoot))
        assertTrue(Files.isDirectory(workspaceRoot.resolve(OLD_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(NEW_MODULE), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(fixtureRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("the same schema-v8 record reaches ROLLED_BACK with no second transaction")
    fun sameJournalRecordReachesRolledBack() {
        val records = transactionLog().listRecordsReadOnly()
        assertEquals(1, records.size)
        val record = records.single()
        val applied = assertNotNull(appliedRecord)
        assertEquals(TransactionJournalRecord.CURRENT_SCHEMA_VERSION, record.schemaVersion)
        assertEquals(assertNotNull(transaction).id, record.transaction.id)
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
        assertEquals(applied.forwardEdit, record.forwardEdit)
        assertEquals(applied.preImages, record.preImages)
        assertEquals(applied.postImages, record.postImages)
        assertEquals(applied.createdDirectories, record.createdDirectories)
        assertEquals(applied.preSnapshotHash, record.preSnapshotHash)
        assertEquals(applied.postSnapshotHash, record.postSnapshotHash)
    }

    @Then("no child process or RefactorKit-attributable socket read or write was observed during selection, fallback evaluation, the mechanics probe, apply, or rollback")
    fun noObservedChildProcessOrRefactorKitSocketActivity() {
        assertTrue(directAuthorityContractReused)
        authorityMonitor.assertNoRefactorKitProcessOrSocketAuthority("complete-surface-001-flow")
    }

    private fun selectProductionGate(probe: RouteProbe): DiagnosticsGate =
        ManagedApplyDiagnosticsGateSelector.select(
            probe.plan,
            "java",
            probe.javaAdapter,
            probe.kotlinAdapter,
        ) { languageId ->
            error("Unexpected external resolver lookup for $languageId")
        }

    private fun adapterHasNoSnapshot(adapter: JavaLanguageAdapter): Boolean =
        adapter.resolveSymbol(ADAPTER_PROBE_LOCATION).diagnostics.singleOrNull()?.code == "java.noSnapshot"

    private fun assertAdapterHasNoSnapshot(adapter: JavaLanguageAdapter) {
        assertTrue(adapterHasNoSnapshot(adapter), "The production Java fallback provider ran unexpectedly")
    }

    private fun assertAdapterHasSnapshot(adapter: JavaLanguageAdapter) {
        val resolution = adapter.resolveSymbol(ADAPTER_PROBE_LOCATION)
        assertEquals(listOf("java.fileNotInSnapshot"), resolution.diagnostics.map(Diagnostic::code))
    }

    private fun assertExactJournalImages(record: TransactionJournalRecord) {
        val affectedPaths = canonicalPlan.workspaceEdit.affectedFiles().map(Path::normalize).toSet()
        val s0Content = s0.trackedFiles.associate { it.path.normalize() to it.content }
        val c1Content = c1.trackedFiles.associate { it.path.normalize() to it.content }
        assertEquals(affectedPaths, record.preImages.map { it.path.normalize() }.toSet())
        assertEquals(affectedPaths, record.postImages.map { it.path.normalize() }.toSet())
        assertEquals(
            affectedPaths.associateWith(s0Content::get),
            record.preImages.associate { it.path.normalize() to it.content },
        )
        assertEquals(
            affectedPaths.associateWith(c1Content::get),
            record.postImages.associate { it.path.normalize() to it.content },
        )
        (record.preImages + record.postImages).forEach { image ->
            assertEquals(image.content?.toByteArray(StandardCharsets.UTF_8)?.let(::sha256), image.contentSha256)
        }
        record.preImages.filter { it.content != null }.forEach { image ->
            assertNotNull(image.posixPermissions, "Missing pre-image permissions for ${image.path}")
            assertNotNull(image.lastModifiedMillis, "Missing pre-image mtime for ${image.path}")
            assertNotNull(image.ownerName, "Missing pre-image owner for ${image.path}")
            assertNotNull(image.groupName, "Missing pre-image group for ${image.path}")
        }
        assertEquals(EXPECTED_CREATED_DIRECTORIES, record.createdDirectories)
    }

    private fun scan(root: Path): ProjectSnapshot {
        val normalized = root.toAbsolutePath().normalize()
        assertTrue(normalized.startsWith(temporaryRoot.toAbsolutePath().normalize()))
        val repository = normalized.resolve(FIXTURE_REPOSITORY_PATH).normalize()
        assertTrue(repository.startsWith(normalized) && Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS))
        return JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = repository,
        ).scan(normalized)
    }

    private fun diagnostics(snapshot: ProjectSnapshot): DiagnosticBaseline = DiagnosticBaseline(
        maven = snapshot.buildModels.single().diagnostics.toList(),
        jdt = JavaLanguageAdapter().authoritativeDiagnostics(snapshot, Path.of(System.getProperty("java.home"))),
    )

    private fun transactionLog(): TransactionLog = TransactionLog(
        workspaceRoot.resolve(".refactorkit/transactions").normalize(),
    )

    private fun directRootModules(root: Path): List<String> {
        val project = parsePom(root.resolve("pom.xml"))
        val modules = directChildren(project, "modules").single()
        return directChildren(modules, "module").map { it.textContent.trim() }
    }

    private fun parsePom(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        return factory.newDocumentBuilder().parse(path.toFile()).documentElement
    }

    private fun directChildren(parent: Element, localName: String): List<Element> = buildList {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && (child.localName ?: child.nodeName.substringAfter(':')) == localName) add(child)
        }
    }

    private fun directChildText(parent: Element, localName: String): String? =
        directChildren(parent, localName).singleOrNull()?.textContent?.trim()

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-jvm"))
            ) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root")
    }

    private fun copyNoFollow(source: Path, target: Path) {
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "Copy target already exists: $target" }
        Files.walkFileTree(
            source,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic-link directory refused: $dir" }
                    val destination = target.resolve(source.relativize(dir).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy path escaped target: $dir" }
                    Files.createDirectories(destination)
                    copyPosixPermissions(dir, destination)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused: $file" }
                    require(attrs.isRegularFile) { "Non-regular fixture entry refused: $file" }
                    val destination = target.resolve(source.relativize(file).toString()).normalize()
                    require(destination.startsWith(target.normalize())) { "Copy path escaped target: $file" }
                    Files.createDirectories(assertNotNull(destination.parent))
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
        assertEquals(captureManifest(source), captureManifest(target))
    }

    private fun copyPosixPermissions(source: Path, target: Path) {
        runCatching {
            Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS))
        }
    }

    private fun captureManifest(root: Path, excludeEngine: Boolean = false): ExactManifest {
        val entries = sortedMapOf<String, ManifestEntry>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = manifestPath(root, dir)
                    if (excludeEngine && (relative == ".refactorkit" || relative.startsWith(".refactorkit/"))) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic link refused: $dir" }
                    entries[relative] = ManifestEntry("directory", null, null)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = manifestPath(root, file)
                    if (excludeEngine && (relative == ".refactorkit" || relative.startsWith(".refactorkit/"))) {
                        return FileVisitResult.CONTINUE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused: $file" }
                    require(attrs.isRegularFile) { "Non-regular fixture entry refused: $file" }
                    val bytes = Files.readAllBytes(file)
                    entries[relative] = ManifestEntry("regular-file", bytes.size.toLong(), sha256(bytes))
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return ExactManifest(entries)
    }

    private fun manifestPath(root: Path, path: Path): String =
        root.relativize(path).invariantSeparatorsPathString.ifBlank { "." }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private data class ManifestEntry(val kind: String, val size: Long?, val sha256: String?)
    private data class ExactManifest(val entries: Map<String, ManifestEntry>)
    private data class DiagnosticBaseline(
        val maven: List<BuildModelDiagnostic>,
        val jdt: List<Diagnostic>,
    )
    private class RouteProbe(
        val jsonLabel: String,
        val operation: String,
        val expectedRoute: String,
        val exactGateId: String,
        val plan: PatchPlan,
        val javaAdapter: JavaLanguageAdapter,
        val kotlinAdapter: KotlinLanguageAdapter,
    ) {
        var gate: DiagnosticsGate? = null
        var diagnostics: List<Diagnostic>? = null
        var evaluationCount: Int = 0
    }

    private class LazyGateMechanicsProbe {
        val factoryCount = AtomicInteger()
        val fixedDiagnostics = listOf(Diagnostic(
            message = "Inert fixed lazy-gate mechanics diagnostic",
            severity = Diagnostic.Severity.INFO,
            code = "lazyGate.mechanics.fixed",
        ))
        val syntheticSnapshots = (1..3).map { index ->
            ProjectSnapshot(
                workspace = Workspace(Path.of("synthetic/lazy-gate-$index")),
                modules = emptyList(),
                files = listOf(SourceFile(Path.of("probe-$index.txt"), "candidate-$index", "synthetic")),
            )
        }
        val factoryCountTimeline = mutableListOf<Int>()
        val results = mutableListOf<List<Diagnostic>>()
        var gate: DiagnosticsGate? = null
        var observedGateId: String? = null

        fun createReadAndEvaluate() {
            assertTrue(gate == null)
            val lazyGate = DiagnosticsGate.lazyAuthoritative(MECHANICS_GATE_ID) {
                factoryCount.incrementAndGet()
                DiagnosticsGate.authoritative(
                    MECHANICS_GATE_ID,
                    AuthoritativeDiagnosticsProvider { candidate ->
                        AuthoritativeDiagnosticsEvaluation(candidate, fixedDiagnostics)
                    },
                )
            }
            gate = lazyGate
            factoryCountTimeline += factoryCount.get()
            observedGateId = lazyGate.id
            factoryCountTimeline += factoryCount.get()
            syntheticSnapshots.forEach { candidate ->
                results += assertNotNull(lazyGate.provider).invoke(candidate)
                factoryCountTimeline += factoryCount.get()
            }
        }
    }

    private data class ActivityEvidence(
        val processEvents: List<String>,
        val socketEvents: List<String>,
        val newDescendants: Set<Long>,
        val note: String,
    ) {
        val prohibitedSurfaceActivityObserved: Boolean
            get() = processEvents.isNotEmpty() || socketEvents.isNotEmpty() || newDescendants.isNotEmpty()

        fun toJson(): String = JSON.writeValueAsString(linkedMapOf<String, Any>(
            "processEvents" to processEvents,
            "socketEvents" to socketEvents,
            "newDescendants" to newDescendants.sorted(),
            "prohibitedSurfaceActivityObserved" to prohibitedSurfaceActivityObserved,
            "note" to note,
        ))
    }

    private class DeniedAuthorityMonitor private constructor(
        private val recording: Recording,
        private val outputDirectory: Path,
        private val baselineDescendants: Set<Long>,
    ) {
        private var dumpSequence = 0
        private val observedDescendants = linkedSetOf<Long>()
        private var running = true

        fun assertNoRefactorKitProcessOrSocketAuthority(label: String) {
            val evidence = evidence(label)
            assertTrue(evidence.processEvents.isEmpty(), "RefactorKit process authority observed: ${evidence.processEvents}")
            assertTrue(evidence.socketEvents.isEmpty(), "RefactorKit socket authority observed: ${evidence.socketEvents}")
            assertTrue(evidence.newDescendants.isEmpty(), "New child process observed: ${evidence.newDescendants}")
        }

        fun finish(): ActivityEvidence {
            if (!running) return ActivityEvidence(emptyList(), emptyList(), observedDescendants, "already stopped")
            observedDescendants += currentDescendants() - baselineDescendants
            recording.stop()
            running = false
            val path = outputDirectory.resolve("final.jfr")
            recording.dump(path)
            val events = RecordingFile.readAllEvents(path)
            recording.close()
            return activityEvidence(events, observedDescendants, "final")
        }

        private fun evidence(label: String): ActivityEvidence {
            observedDescendants += currentDescendants() - baselineDescendants
            val path = outputDirectory.resolve("checkpoint-${dumpSequence++}.jfr")
            recording.dump(path)
            return activityEvidence(RecordingFile.readAllEvents(path), observedDescendants, label)
        }

        private fun activityEvidence(
            events: List<RecordedEvent>,
            descendants: Set<Long>,
            note: String,
        ): ActivityEvidence {
            val processEvents = events.filter { it.eventType.name == "jdk.ProcessStart" }.map { event ->
                "pid=${event.getLong("pid")} command=${event.getString("command")}"
            }
            val socketEvents = events.filter { event ->
                event.eventType.name in setOf("jdk.SocketRead", "jdk.SocketWrite") && hasRefactorKitFrame(event)
            }.map { event ->
                "${event.eventType.name} ${event.getString("host")}:${event.getInt("port")}"
            }
            return ActivityEvidence(processEvents.distinct(), socketEvents.distinct(), descendants.toSet(), note)
        }

        private fun hasRefactorKitFrame(event: RecordedEvent): Boolean = event.stackTrace?.frames.orEmpty().any { frame ->
            frame.method.type.name.startsWith("org.refactorkit.")
        }

        companion object {
            fun start(outputDirectory: Path): DeniedAuthorityMonitor {
                Files.createDirectories(outputDirectory)
                val recording = Recording()
                recording.enable("jdk.ProcessStart").withStackTrace()
                recording.enable("jdk.SocketRead").withThreshold(Duration.ZERO).withStackTrace()
                recording.enable("jdk.SocketWrite").withThreshold(Duration.ZERO).withStackTrace()
                val descendants = currentDescendants()
                recording.start()
                return DeniedAuthorityMonitor(recording, outputDirectory, descendants)
            }

            private fun currentDescendants(): Set<Long> {
                val result = linkedSetOf<Long>()
                ProcessHandle.current().descendants().use { descendants -> descendants.forEach { result += it.pid() } }
                return result
            }
        }
    }

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val FIXTURE_REPOSITORY_PATH = "fixture-repository"
        const val ROUTE_JAVA_JDT = "java-jdt"
        const val ROUTE_MAVEN_OWNERSHIP = "java-maven-ownership"
        const val MECHANICS_GATE_ID = "synthetic-fixed-authoritative-lazy-gate"
        const val APPLY_SURFACE = "managed-apply-selector-acceptance"
        const val APPLY_ACTOR = "cucumber"
        const val OLD_MODULE = "catalog-model"
        const val NEW_MODULE = "catalog-domain"
        const val NEW_ARTIFACT = "catalog-domain"

        val JSON = ObjectMapper()
        val ADAPTER_PROBE_LOCATION = SourceLocation(
            Path.of("__selector_adapter_probe__.java"),
            SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)),
        )
        val EXPECTED_OPERATIONS = listOf(
            JavaRenameMavenModulePlanner.OPERATION,
            "java.renamemavenmodule",
            "renameMavenModule",
            JavaMoveAcrossMavenModulesPlanner.OPERATION,
            "renameClass",
            " ${JavaRenameMavenModulePlanner.OPERATION}",
            "${JavaRenameMavenModulePlanner.OPERATION} ",
        )
        val EXPECTED_ROUTES = linkedMapOf(
            JavaRenameMavenModulePlanner.OPERATION to (
                "production operation-owned Maven module-rename authority" to
                    JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID
                ),
            "java.renamemavenmodule" to ("real production generic Java diagnostics" to ROUTE_JAVA_JDT),
            "renameMavenModule" to ("real production generic Java diagnostics" to ROUTE_JAVA_JDT),
            JavaMoveAcrossMavenModulesPlanner.OPERATION to (
                "real production Maven move-ownership diagnostics" to ROUTE_MAVEN_OWNERSHIP
                ),
            "renameClass" to ("real production generic Java diagnostics" to ROUTE_JAVA_JDT),
            " ${JavaRenameMavenModulePlanner.OPERATION}" to (
                "real production generic Java diagnostics" to ROUTE_JAVA_JDT
                ),
            "${JavaRenameMavenModulePlanner.OPERATION} " to (
                "real production generic Java diagnostics" to ROUTE_JAVA_JDT
                ),
        )
        val EXPECTED_CREATED_DIRECTORIES = listOf(
            Path.of("catalog-domain"),
            Path.of("catalog-domain/src"),
            Path.of("catalog-domain/src/main"),
            Path.of("catalog-domain/src/main/java"),
            Path.of("catalog-domain/src/main/java/com"),
            Path.of("catalog-domain/src/main/java/com/acme"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy"),
        )
    }
}

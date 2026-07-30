package org.refactorkit.cli.mavenmoveauthority

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtJavaSemanticAnalysisResult
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dedicated acceptance glue boundary for REQ-JAVA-MAVEN-MOVE-AUTH-001. */
class JavaMavenMoveClassAvailableAuthoritySteps {
    private lateinit var scenario: Scenario
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var baselineSnapshot: ProjectSnapshot
    private lateinit var targetBindingKey: String
    private var baselineState: WorkspaceState? = null
    private var baselineDiagnostics: List<Diagnostic> = emptyList()
    private var observerClosure: Set<String> = emptySet()
    private var previewObservations: List<PreviewObservation> = emptyList()
    private var preApplyState: WorkspaceState? = null
    private var expectedStagedSnapshot: ProjectSnapshot? = null
    private var transaction: Transaction? = null
    private var afterSnapshot: ProjectSnapshot? = null
    private var afterState: WorkspaceState? = null
    private var afterDiagnostics: List<Diagnostic> = emptyList()
    private var rollbackSnapshot: ProjectSnapshot? = null
    private var rollbackState: WorkspaceState? = null
    private var rollbackDiagnostics: List<Diagnostic> = emptyList()
    private var workspaceLockObserved = false
    private var applyCount = 0
    private var rollbackCount = 0
    private var prohibitedActivityPolicyAttested = false

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-001")
    fun prepareAvailableAuthorityScenario(scenario: Scenario) {
        this.scenario = scenario
        val repositoryRoot = locateRepositoryRoot()
        fixtureTemplate = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        assertTrue(Files.isDirectory(fixtureTemplate), "Missing permanent Maven authority fixture")
        temporaryRoot = Files.createTempDirectory("refactorkit-move-auth-001-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyRecursively(fixtureTemplate, workspaceRoot)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        scenario.attach(
            "The dedicated REQ-JAVA-MAVEN-MOVE-AUTH-001 runner uses an isolated byte copy of the " +
                "permanent 20-module fixture. No feature text or requirement status is changed.",
            "text/plain",
            "available-authority-isolation",
        )
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-001")
    fun removeScenarioWorkspace() {
        if (!this::temporaryRoot.isInitialized || !Files.exists(temporaryRoot)) return
        Files.walk(temporaryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Given("the declared workspace root is the permanent fixture {string}")
    fun theDeclaredWorkspaceRootIsThePermanentFixture(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertEquals(fixtureTemplate, locateRepositoryRoot().resolve(path).normalize())
    }

    @Given("the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules")
    fun theFixtureIsAPluginFreeOfflineMavenReactor() {
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        val modules = MODULE_PATTERN.findAll(rootPom).map { it.groupValues[1].trim() }.toList()
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        assertTrue(rootPom.contains("<packaging>pom</packaging>"))

        val pomPaths = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }.toList()
        }
        assertEquals(21, pomPaths.size, "Expected one root POM and 20 child POMs")
        modules.forEach { module ->
            val childPom = workspaceRoot.resolve(module).resolve("pom.xml")
            assertTrue(Files.isRegularFile(childPom), "Missing active child POM: $module")
            val content = Files.readString(childPom)
            assertFalse(content.contains("<modules>"), "$module must not aggregate another module")
            assertFalse(content.contains("<packaging>pom</packaging>"), "$module must produce a JAR")
        }

        baselineSnapshot = scanWorkspace()
        val model = baselineSnapshot.buildModels.single()
        assertEquals(BuildModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        assertEquals(MAVEN_BUILD_MODEL_PROVIDER, model.providerId)
        assertEquals(20, baselineSnapshot.modules.size)
        assertEquals(20, model.modules.size)
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun itsActiveGraphHasRequiredTopologyAndMaterializedInputs() {
        assertPomDependency("catalog-acceptance", "catalog-storefront", testOnly = true)
        assertPomDependency("catalog-storefront", "catalog-pricing")
        assertPomDependency("catalog-pricing", "catalog-model")

        val externalArtifact = workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH)
        assertTrue(Files.isRegularFile(externalArtifact), "The local external artifact must be materialized")
        assertEquals(EXTERNAL_ARTIFACT_SHA256, sha256(Files.readAllBytes(externalArtifact)))

        val generatedSource = workspaceRoot.resolve(GENERATED_SOURCE_PATH)
        assertTrue(Files.isRegularFile(generatedSource), "The generated source must already be materialized")
        assertTrue(
            baselineSnapshot.files.any { it.path == Path.of(GENERATED_SOURCE_PATH) },
            "The materialized generated Java source must belong to the scanner inventory",
        )
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryAndAnalysisDenyExecutableOrExternalInputs() {
        val pomContents = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .map(Files::readString)
                .toList()
        }
        FORBIDDEN_POM_MARKERS.forEach { marker ->
            assertTrue(pomContents.none { marker in it }, "Fixture POMs must not contain $marker")
        }
        listOf("mvnw", ".mvn", "settings.xml").forEach { relative ->
            assertFalse(Files.exists(workspaceRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS))
        }

        val attributes = baselineSnapshot.buildModels.single().attributes
        assertEquals("embedded-effective-model", attributes["strategy"])
        assertEquals("denied", attributes["buildCodeExecution"])
        assertEquals("denied", attributes["credentialsAccess"])
        assertEquals("denied", attributes["networkDefault"])
        assertEquals("denied", attributes["networkAccess"])
        assertEquals("", attributes["activeProfiles"])
        prohibitedActivityPolicyAttested = true
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun theRequestMovesTheWritableSoleTopLevelClass(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        val source = workspaceRoot.resolve(sourcePath)
        val target = workspaceRoot.resolve(targetPath)
        assertTrue(Files.isRegularFile(source) && Files.isWritable(source))
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, PRODUCT_DECLARATION_PATTERN.findAll(Files.readString(source)).count())
        val ownership = baselineSnapshot.owningBuildSourceRoots(Path.of(sourcePath))
        assertEquals(1, ownership.size)
        assertEquals(TARGET_OWNER_SOURCE_SET, ownership.single().displayName())
    }

    @Given("the full effective reactor graph and complete reverse-observer closure are available and hash-bound")
    fun theFullEffectiveGraphAndObserverClosureAreAvailableAndHashBound() {
        baselineSnapshot = scanWorkspace()
        val model = baselineSnapshot.buildModels.single()
        assertEquals(BuildModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        assertTrue(model.diagnostics.none { it.severity == Diagnostic.Severity.ERROR })
        assertTrue(baselineSnapshot.modules.all {
            it.languageSettings["java.dependencyGraph.status"] == "complete"
        })
        assertEquals(21, baselineSnapshot.auxiliaryFiles.count { it.path.fileName.toString() == "pom.xml" })
        assertTrue(SHA256_PATTERN.matches(baselineSnapshot.hash))
        assertTrue(baselineSnapshot.classpathEvidence.isNotEmpty())
        baselineSnapshot.classpathEvidence.forEach { evidence ->
            assertEquals(
                evidence,
                ClasspathEvidence.capture(workspaceRoot, evidence.path, evidence.kind),
                "Classpath/model evidence drifted before preview: ${evidence.path}",
            )
        }

        assertDependency(model.modules.single { it.id == "catalog-pricing" }.sourceSets.single { it.id == "main" }, "catalog-model", DependencyScope.COMPILE)
        assertDependency(model.modules.single { it.id == "catalog-storefront" }.sourceSets.single { it.id == "main" }, "catalog-pricing", DependencyScope.COMPILE)
        assertDependency(model.modules.single { it.id == "catalog-acceptance" }.sourceSets.single { it.id == "test" }, "catalog-storefront", DependencyScope.TEST)

        val analysis = JdtJavaSemanticAnalyzer().analyze(baselineSnapshot)
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        targetBindingKey = assertNotNull(target.bindingKey)
        assertFalse(target.recovered)
        observerClosure = observerClosure(baselineSnapshot, analysis, targetBindingKey, Path.of(PRODUCT_SOURCE_PATH))
        assertEquals(EXPECTED_OBSERVER_SOURCE_SETS, observerClosure)
        baselineDiagnostics = JavaLanguageAdapter().diagnostics(baselineSnapshot)
        assertTrue(baselineDiagnostics.isEmpty(), baselineDiagnostics.toString())
        baselineState = captureWorkspaceState(baselineSnapshot)
    }

    @Given("every observer has current dependency-bounded source paths, classpaths, source inventories, Java platform signatures, and provider evidence")
    fun everyObserverHasCurrentAnalysisEvidence() {
        val snapshot = baselineSnapshot
        val model = snapshot.buildModels.single()
        val requiredSourceSets = observerClosure + TARGET_OWNER_SOURCE_SET
        requiredSourceSets.forEach { displayName ->
            val sourceSet = buildSourceSet(snapshot, displayName)
            assertTrue(sourceSet.sourceRoots.isNotEmpty(), "$displayName must have a source path")
            assertEquals("available", sourceSet.attributes["java.classpath.status"], displayName)
            assertEquals("8", sourceSet.attributes["java.sourceLevel"], displayName)
            assertEquals("available", sourceSet.attributes["java.sourceLevel.status"], displayName)
            assertEquals("8", sourceSet.attributes["java.release"], displayName)
            assertEquals("maven-release", sourceSet.attributes["java.platformSelection"], displayName)
            assertTrue(snapshot.files.any { file -> sourceSetDisplayName(snapshot, file.path) == displayName })
        }

        EXPECTED_MANAGED_SOURCE_PATHS.forEach { path ->
            assertTrue(
                assertNotNull(baselineState).sourceInventory.containsKey(path.invariantSeparatorsPathString),
                "Missing managed source inventory entry $path; available entries are " +
                    assertNotNull(baselineState).sourceInventory.keys,
            )
            val ownership = snapshot.owningBuildSourceRoots(path)
            assertEquals(1, ownership.size, "$path must have one provider-owned source root")
            assertEquals(MAVEN_BUILD_MODEL_PROVIDER, ownership.single().providerId)
            assertEquals(BuildModelStatus.AVAILABLE, ownership.single().modelStatus)
        }
        assertEquals("denied", model.attributes["buildCodeExecution"])
        assertEquals("denied", model.attributes["credentialsAccess"])
        assertEquals("denied", model.attributes["networkAccess"])
        val repeat = scanWorkspace()
        assertEquals(snapshot.hash, repeat.hash)
        assertEquals(snapshot.classpathEvidence, repeat.classpathEvidence)
        assertEquals(snapshot.auxiliaryFiles, repeat.auxiliaryFiles)
    }

    @Given("the target declaration and every managed Java edit site resolve to the same exact non-recovered JDT binding")
    fun targetAndManagedEditSitesResolveToOneExactBinding() {
        val analysis = JdtJavaSemanticAnalyzer().analyze(baselineSnapshot)
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        assertEquals(targetBindingKey, target.bindingKey)
        assertFalse(target.recovered)

        val targetReferences = analysis.references.filter { it.symbolQualifiedName == PRODUCT_FQN }
        assertTrue(targetReferences.isNotEmpty())
        assertTrue(targetReferences.all { it.bindingKey == targetBindingKey && !it.recovered })
        assertEquals(EXPECTED_OBSERVER_PATHS, targetReferences.mapTo(linkedSetOf()) { it.path })

        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            baselineSnapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence, plan.warnings.toString())
        assertBindingDerivedEditBoundary(plan, analysis)
    }

    @When("the move is previewed twice from identical snapshot and evidence hashes")
    fun theMoveIsPreviewedTwiceFromIdenticalEvidence() {
        previewObservations = List(2) { capturePreviewObservation() }
    }

    @Then("each result is a {string} with evidence {string} and managed-write eligibility {string}")
    fun eachResultIsAnEligibleSemanticPreview(resultType: String, evidence: String, eligibility: String) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals(RefactoringEvidence.JDT_BINDING.name, evidence)
        assertEquals("ELIGIBLE", eligibility)
        assertEquals(2, previewObservations.size)
        previewObservations.forEach { observation ->
            val plan = observation.plan
            assertEquals(PatchStatus.PREVIEW, plan.status)
            assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence)
            assertTrue(plan.requiresUserApproval)
            assertEquals(BuildModelStatus.AVAILABLE, observation.snapshot.buildModels.single().status)
            val validation = PatchEngine(workspaceRoot).validate(plan, observation.snapshot.hash)
            assertTrue(validation.none { it.severity == Diagnostic.Severity.ERROR }, validation.toString())
            assertEquals(0, observation.cli.exitCode, observation.cli.failureMessage("preview"))
            assertTrue(observation.cli.stderr.isEmpty(), observation.cli.failureMessage("preview"))
            assertTrue(observation.cli.stdout.contains("Operation: moveClass"))
            assertTrue(observation.cli.stdout.contains("Status: PREVIEW"))
            assertTrue(observation.cli.stdout.contains("Evidence: JDT_BINDING"))
            assertTrue(observation.cli.stdout.contains("Use --apply to apply this change."))
            EXPECTED_AFFECTED_PATHS.forEach { path ->
                assertTrue(
                    observation.cli.stdout.contains("- ${path.invariantSeparatorsPathString}"),
                    observation.cli.failureMessage("affected-file preview"),
                )
            }
        }
        scenario.attach(
            previewObservations.first().cli.stdout,
            "text/plain",
            "source-built-cli-semantic-preview",
        )
    }

    @Then("the normalized edits, observer closure, evidence counts, and diagnostics are identical between the previews")
    fun repeatPreviewsHaveIdenticalNormalizedEvidence() {
        val first = previewObservations[0]
        val second = previewObservations[1]
        assertEquals(first.snapshot.hash, second.snapshot.hash)
        assertEquals(first.snapshot.classpathEvidence, second.snapshot.classpathEvidence)
        assertEquals(first.normalizedEdit, second.normalizedEdit)
        assertEquals(first.observerClosure, second.observerClosure)
        assertEquals(EXPECTED_OBSERVER_SOURCE_SETS, first.observerClosure)
        assertEquals(first.evidenceCounts, second.evidenceCounts)
        assertEquals(first.diagnosticsBefore, second.diagnosticsBefore)
        assertEquals(first.diagnosticsStaged, second.diagnosticsStaged)
        assertEquals(first.plan.diagnosticsBefore, second.plan.diagnosticsBefore)
        assertEquals(first.plan.diagnosticsAfterPreview, second.plan.diagnosticsAfterPreview)
        assertEquals(first.plan.affectedFiles, second.plan.affectedFiles)
        assertEquals(first.plan.summary, second.plan.summary)
        assertEquals(first.plan.warnings, second.plan.warnings)
        assertEquals(first.cli.stdout, second.cli.stdout)

        val counts = first.evidenceCounts
        assertEquals(20, counts.reactorModules)
        assertEquals(40, counts.reactorSourceSets)
        assertEquals(7, counts.sourceFiles)
        assertEquals(21, counts.auxiliaryFiles)
        assertTrue(counts.classpathEvidence > 0)
        assertTrue(counts.jdtSymbols > 0)
        assertTrue(counts.targetReferences > counts.observerSourceSets)
        assertEquals(3, counts.observerSourceSets)
        assertEquals(5, counts.normalizedFileEdits)
        assertEquals(5, counts.normalizedTextEdits)
        assertEquals(0, counts.diagnosticsBefore)
        assertEquals(0, counts.diagnosticsStaged)
        scenario.attach(
            "Both previews used snapshot ${first.snapshot.hash}. The evidence counts are " +
                "${counts.reactorModules} reactor modules, ${counts.reactorSourceSets} source sets, " +
                "${counts.sourceFiles} Java sources, ${counts.auxiliaryFiles} effective POM inputs, " +
                "${counts.classpathEvidence} classpath evidence records, ${counts.targetReferences} exact target " +
                "references, ${counts.normalizedFileEdits} normalized file edits, and " +
                "${counts.normalizedTextEdits} normalized text edits.",
            "text/plain",
            "deterministic-preview-evidence",
        )
    }

    @Then("exact staged diagnostics introduce no errors relative to authoritative before diagnostics")
    fun exactStagedDiagnosticsIntroduceNoErrors() {
        val first = previewObservations[0]
        val second = previewObservations[1]
        previewObservations.forEach { observation ->
            assertTrue(introducedErrors(observation.diagnosticsBefore, observation.diagnosticsStaged).isEmpty())
            assertTrue(observation.diagnosticsStaged.none { it.severity == Diagnostic.Severity.ERROR })
            assertEquals(observation.plan.diagnosticsBefore, observation.diagnosticsBefore)
            assertEquals(observation.plan.diagnosticsAfterPreview, observation.diagnosticsStaged)
            assertTrue(observation.stagedSnapshot.files.none { it.path == Path.of(PRODUCT_SOURCE_PATH) })
            val stagedTarget = observation.stagedSnapshot.files.single { it.path == Path.of(PRODUCT_TARGET_PATH) }
            assertTrue(stagedTarget.content.contains("package $PRODUCT_TARGET_PACKAGE;"))
            assertFalse(stagedTarget.content.contains("package com.acme.catalog.legacy;"))
        }
        assertEquals(first.stagedSnapshot.hash, second.stagedSnapshot.hash)
        assertEquals(first.diagnosticsStaged, second.diagnosticsStaged)
    }

    @Then("no prohibited build or network activity was attempted")
    fun noProhibitedBuildOrNetworkActivityWasAttempted() {
        assertTrue(prohibitedActivityPolicyAttested)
        assertEquals(assertNotNull(baselineState), captureWorkspaceState(scanWorkspace()))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        previewObservations.forEach { observation ->
            assertTrue(observation.cli.stderr.isEmpty())
            assertFalse(observation.cli.stdout.contains("mvn "))
            assertFalse(observation.cli.stdout.contains("BUILD SUCCESS"))
        }
        val attributes = baselineSnapshot.buildModels.single().attributes
        scenario.attach(
            "Maven discovery used strategy ${attributes["strategy"]}. Build-code execution, settings/credential " +
                "access, and network access are denied; the fixture contains no plugins, annotation-processor " +
                "configuration, Maven wrapper, .mvn directory, or settings.xml.",
            "text/plain",
            "prohibited-activity-attestation",
        )
    }

    @When("one approved preview is applied under the workspace lock")
    fun oneApprovedPreviewIsAppliedUnderTheWorkspaceLock() {
        val approved = previewObservations.first()
        val current = scanWorkspace()
        assertEquals(approved.snapshot.hash, current.hash)
        preApplyState = captureWorkspaceState(current)
        assertEquals(assertNotNull(baselineState), preApplyState)
        expectedStagedSnapshot = approved.stagedSnapshot
        workspaceLockObserved = false
        val injector = PatchFaultInjector { point, _, _ ->
            if (point == PatchFaultPoint.AFTER_STAGED_FILE_FORCE) {
                workspaceLockObserved = workspaceLockObserved || workspaceLockIsHeld()
            }
        }
        val result = PatchEngine(workspaceRoot, faultInjector = injector).apply(
            approved.plan,
            current,
            ApplyAuthorization.explicit("cucumber", "REQ-JAVA-MAVEN-MOVE-AUTH-001"),
            DiagnosticsGate.enabled("java-maven-move-class-available", JavaLanguageAdapter()::diagnostics),
        )
        transaction = assertIs<ApplyResult.Applied>(result).transaction
        applyCount += 1
        afterSnapshot = scanWorkspace()
        afterState = captureWorkspaceState(assertNotNull(afterSnapshot))
        afterDiagnostics = JavaLanguageAdapter().diagnostics(assertNotNull(afterSnapshot))
    }

    @Then("its exact staged post-image is committed in one managed transaction")
    fun theExactStagedPostImageIsCommittedOnce() {
        val approved = previewObservations.first()
        val expected = assertNotNull(expectedStagedSnapshot)
        val actual = assertNotNull(afterSnapshot)
        val appliedTransaction = assertNotNull(transaction)
        assertTrue(workspaceLockObserved, "The managed commit must execute while the workspace lock is held")
        assertEquals(1, applyCount)
        assertEquals(approved.plan.id, appliedTransaction.planId)
        assertEquals(expected.hash, actual.hash)
        assertEquals(expected.files, actual.files)
        assertEquals(expected.auxiliaryFiles, actual.auxiliaryFiles)
        assertEquals(sourceInventory(expected), assertNotNull(afterState).sourceInventory)
        assertApplyChangedOnlyExpectedFiles(assertNotNull(preApplyState), assertNotNull(afterState))
        assertLexicalBoundaryUnchanged(assertNotNull(afterState))

        val records = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(1, records.size)
        assertEquals(appliedTransaction.id, records.single().transaction.id)
        assertEquals("APPLIED", records.single().state.name)
    }

    @Then("authoritative after diagnostics attest the committed snapshot with no introduced errors")
    fun authoritativeAfterDiagnosticsAttestTheCommittedSnapshot() {
        val expectedObservation = previewObservations.first()
        val snapshot = assertNotNull(afterSnapshot)
        assertEquals(assertNotNull(expectedStagedSnapshot).hash, snapshot.hash)
        assertEquals(expectedObservation.diagnosticsStaged, afterDiagnostics)
        assertTrue(introducedErrors(expectedObservation.diagnosticsBefore, afterDiagnostics).isEmpty())
        assertTrue(afterDiagnostics.none { it.severity == Diagnostic.Severity.ERROR })
        assertEquals(BuildModelStatus.AVAILABLE, snapshot.buildModels.single().status)

        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
        assertTrue(analysis.symbols.none { it.qualifiedName == PRODUCT_FQN })
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_TARGET_FQN && it.path == Path.of(PRODUCT_TARGET_PATH)
        }
        val binding = assertNotNull(target.bindingKey)
        assertFalse(target.recovered)
        assertTrue(analysis.references.filter { it.symbolQualifiedName == PRODUCT_TARGET_FQN }.all {
            it.bindingKey == binding && !it.recovered
        })
        scenario.attach(
            "The committed snapshot is ${snapshot.hash}; authoritative after diagnostics contain " +
                "${afterDiagnostics.size} entries and introduce zero errors. Transaction " +
                "${assertNotNull(transaction).id.value} is the sole managed record.",
            "text/plain",
            "committed-post-image-attestation",
        )
    }

    @When("that transaction is rolled back")
    fun thatTransactionIsRolledBackNormally() {
        val result = PatchEngine(workspaceRoot).rollback(assertNotNull(transaction))
        assertIs<ApplyResult.Applied>(result)
        rollbackCount += 1
        rollbackSnapshot = scanWorkspace()
        rollbackState = captureWorkspaceState(assertNotNull(rollbackSnapshot))
        rollbackDiagnostics = JavaLanguageAdapter().diagnostics(assertNotNull(rollbackSnapshot))
    }

    @Then("every file byte, path, inventory entry, and snapshot hash equals the pre-apply image")
    fun everyWorkspaceIdentityDimensionEqualsThePreApplyImage() {
        val expected = assertNotNull(preApplyState)
        val actual = assertNotNull(rollbackState)
        assertEquals(1, rollbackCount)
        assertEquals(expected.pathKinds, actual.pathKinds, "Workspace path identity changed across rollback")
        assertEquals(expected.fileHashes, actual.fileHashes, "Workspace bytes changed across rollback")
        assertEquals(expected.sourceInventory, actual.sourceInventory, "Source inventory changed across rollback")
        assertEquals(expected.snapshotHash, actual.snapshotHash, "Snapshot identity changed across rollback")
        assertEquals(baselineSnapshot.hash, assertNotNull(rollbackSnapshot).hash)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(PRODUCT_SOURCE_PATH)))
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        assertLexicalBoundaryUnchanged(actual)

        val records = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(1, records.size, "Rollback must not create a second transaction")
        assertEquals(assertNotNull(transaction).id, records.single().transaction.id)
        assertEquals("ROLLED_BACK", records.single().state.name)
    }

    @Then("authoritative rollback diagnostics attest the restored snapshot")
    fun authoritativeRollbackDiagnosticsAttestTheRestoredSnapshot() {
        val snapshot = assertNotNull(rollbackSnapshot)
        assertEquals(baselineDiagnostics, rollbackDiagnostics)
        assertTrue(rollbackDiagnostics.none { it.severity == Diagnostic.Severity.ERROR })
        assertEquals(BuildModelStatus.AVAILABLE, snapshot.buildModels.single().status)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
        assertTrue(analysis.symbols.none { it.qualifiedName == PRODUCT_TARGET_FQN })
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        assertEquals(targetBindingKey, target.bindingKey)
        assertFalse(target.recovered)
        scenario.attach(
            "Normal rollback restored snapshot ${snapshot.hash} byte-for-byte and path-for-path. " +
                "Authoritative rollback diagnostics exactly equal the ${baselineDiagnostics.size} before entries.",
            "text/plain",
            "rollback-identity-attestation",
        )
    }

    private fun capturePreviewObservation(): PreviewObservation {
        val snapshot = scanWorkspace()
        assertEquals(baselineSnapshot.hash, snapshot.hash)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        val binding = assertNotNull(target.bindingKey)
        assertEquals(targetBindingKey, binding)
        assertFalse(target.recovered)
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertBindingDerivedEditBoundary(plan, analysis)
        val normalizedEdit = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        val stagedSnapshot = WorkspaceEditSimulator.apply(snapshot, normalizedEdit)
        val diagnosticsBefore = JavaLanguageAdapter().diagnostics(snapshot)
        val diagnosticsStaged = JavaLanguageAdapter().diagnostics(stagedSnapshot)
        val closure = observerClosure(snapshot, analysis, binding, Path.of(PRODUCT_SOURCE_PATH))
        val cli = runCli(
            listOf(
                "move-class",
                "--symbol",
                PRODUCT_FQN,
                "--to-package",
                PRODUCT_TARGET_PACKAGE,
                workspaceRoot.toString(),
                "--preview",
            ),
        )
        val afterCliSnapshot = scanWorkspace()
        assertEquals(snapshot.hash, afterCliSnapshot.hash, "CLI preview must be read-only")
        assertEquals(snapshot.classpathEvidence, afterCliSnapshot.classpathEvidence)
        return PreviewObservation(
            snapshot = snapshot,
            plan = plan,
            normalizedEdit = normalizedEdit,
            stagedSnapshot = stagedSnapshot,
            observerClosure = closure,
            evidenceCounts = evidenceCounts(
                snapshot,
                analysis,
                binding,
                closure,
                normalizedEdit,
                diagnosticsBefore,
                diagnosticsStaged,
            ),
            diagnosticsBefore = diagnosticsBefore,
            diagnosticsStaged = diagnosticsStaged,
            cli = cli,
        )
    }

    private fun assertBindingDerivedEditBoundary(plan: PatchPlan, analysis: JdtJavaSemanticAnalysisResult) {
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence, plan.warnings.toString())
        val normalized = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        val modifications = normalized.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(EXPECTED_MANAGED_SOURCE_PATHS, modifications.mapTo(linkedSetOf()) { it.path })
        val rename = normalized.edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(Path.of(PRODUCT_SOURCE_PATH), rename.path)
        assertEquals(Path.of(PRODUCT_TARGET_PATH), rename.newPath)
        assertTrue(normalized.edits.none { it is FileEdit.Create || it is FileEdit.Delete })
        assertEquals(EXPECTED_AFFECTED_PATHS, plan.affectedFiles)
        assertTrue(plan.warnings.any {
            it.contains("JDT type binding selected 3 referencing file(s)") && it.contains("binding-derived")
        }, plan.warnings.toString())
        assertTrue(plan.warnings.any {
            it.contains("Authoritative dependency-bounded reverse-observer closure") &&
                EXPECTED_OBSERVER_SOURCE_SETS.all(it::contains)
        }, plan.warnings.toString())

        EXPECTED_OBSERVER_PATHS.forEach { path ->
            val references = analysis.references.filter { it.path == path && it.symbolQualifiedName == PRODUCT_FQN }
            assertTrue(references.isNotEmpty(), "$path must contain an exact target reference")
            assertTrue(references.all { it.bindingKey == targetBindingKey && !it.recovered })
        }
        LEXICAL_BOUNDARY_PATHS.forEach { boundary ->
            assertTrue(Files.isRegularFile(workspaceRoot.resolve(boundary)), "Missing lexical boundary fixture: $boundary")
            assertTrue(boundary !in plan.affectedFiles, "$boundary must remain outside the managed Java edit boundary")
        }
    }

    private fun evidenceCounts(
        snapshot: ProjectSnapshot,
        analysis: JdtJavaSemanticAnalysisResult,
        bindingKey: String,
        closure: Set<String>,
        normalizedEdit: WorkspaceEdit,
        before: List<Diagnostic>,
        staged: List<Diagnostic>,
    ): EvidenceCounts = EvidenceCounts(
        reactorModules = snapshot.buildModels.single().modules.size,
        reactorSourceSets = snapshot.buildModels.single().modules.sumOf { it.sourceSets.size },
        sourceFiles = snapshot.files.size,
        auxiliaryFiles = snapshot.auxiliaryFiles.size,
        classpathEvidence = snapshot.classpathEvidence.size,
        jdtSymbols = analysis.symbols.size,
        targetReferences = analysis.references.count { it.bindingKey == bindingKey && !it.recovered },
        observerSourceSets = closure.size,
        normalizedFileEdits = normalizedEdit.edits.size,
        normalizedTextEdits = normalizedEdit.edits.filterIsInstance<FileEdit.Modify>().sumOf { it.textEdits.size },
        diagnosticsBefore = before.size,
        diagnosticsStaged = staged.size,
    )

    private fun observerClosure(
        snapshot: ProjectSnapshot,
        analysis: JdtJavaSemanticAnalysisResult,
        bindingKey: String,
        declarationPath: Path,
    ): Set<String> = analysis.references.asSequence()
        .filter { it.bindingKey == bindingKey && !it.recovered && it.path != declarationPath }
        .map { sourceSetDisplayName(snapshot, it.path) }
        .toSet()

    private fun buildSourceSet(snapshot: ProjectSnapshot, displayName: String): BuildSourceSet {
        val (moduleId, sourceSetId) = displayName.split(':', limit = 2)
        return snapshot.buildModels.single().modules.single { it.id == moduleId }
            .sourceSets.single { it.id == sourceSetId }
    }

    private fun sourceSetDisplayName(snapshot: ProjectSnapshot, path: Path): String {
        val ownership = snapshot.owningBuildSourceRoots(path)
        assertEquals(1, ownership.size, "$path must have one authoritative source-root owner")
        return ownership.single().displayName()
    }

    private fun org.refactorkit.core.BuildSourceRootOwnership.displayName(): String = "${module.id}:${sourceSet.id}"

    private fun assertDependency(sourceSet: BuildSourceSet, targetModule: String, scope: DependencyScope) {
        assertTrue(sourceSet.moduleDependencies.any {
            it.targetModuleId == targetModule && it.scope == scope
        }, "${sourceSet.id} must depend on $targetModule at $scope scope")
    }

    private fun introducedErrors(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val remaining = before.filter { it.severity == Diagnostic.Severity.ERROR }.groupingBy { it }.eachCount().toMutableMap()
        return after.filter { it.severity == Diagnostic.Severity.ERROR }.filter { diagnostic ->
            val count = remaining[diagnostic] ?: 0
            if (count == 0) true else {
                remaining[diagnostic] = count - 1
                false
            }
        }
    }

    private fun assertApplyChangedOnlyExpectedFiles(before: WorkspaceState, after: WorkspaceState) {
        val removed = before.fileHashes.keys - after.fileHashes.keys
        val added = after.fileHashes.keys - before.fileHashes.keys
        val changed = before.fileHashes.keys.intersect(after.fileHashes.keys).filterTo(linkedSetOf()) {
            before.fileHashes.getValue(it) != after.fileHashes.getValue(it)
        }
        assertEquals(setOf(PRODUCT_SOURCE_PATH), removed)
        assertEquals(setOf(PRODUCT_TARGET_PATH), added)
        assertEquals(EXPECTED_OBSERVER_PATHS.mapTo(linkedSetOf()) { it.invariantSeparatorsPathString }, changed)
    }

    private fun assertLexicalBoundaryUnchanged(state: WorkspaceState) {
        val before = assertNotNull(baselineState)
        LEXICAL_BOUNDARY_PATHS.forEach { path ->
            val relative = path.invariantSeparatorsPathString
            assertEquals(before.fileHashes[relative], state.fileHashes[relative], "$relative changed")
        }
    }

    private fun captureWorkspaceState(snapshot: ProjectSnapshot): WorkspaceState {
        val pathKinds = linkedMapOf<String, String>()
        val fileHashes = linkedMapOf<String, String>()
        Files.walk(workspaceRoot).use { paths ->
            paths.sorted().forEach { path ->
                if (path == workspaceRoot) return@forEach
                val relative = workspaceRoot.relativize(path).invariantSeparatorsPathString
                if (relative == ".refactorkit" || relative.startsWith(".refactorkit/")) return@forEach
                when {
                    Files.isSymbolicLink(path) -> pathKinds[relative] = "symlink:${Files.readSymbolicLink(path)}"
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> pathKinds[relative] = "directory"
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        pathKinds[relative] = "file"
                        fileHashes[relative] = sha256(Files.readAllBytes(path))
                    }
                    else -> pathKinds[relative] = "other"
                }
            }
        }
        return WorkspaceState(pathKinds, fileHashes, sourceInventory(snapshot), snapshot.hash)
    }

    private fun sourceInventory(snapshot: ProjectSnapshot): Map<String, SourceInventoryEntry> = snapshot.files
        .sortedBy { it.path.invariantSeparatorsPathString }
        .associate { source ->
            source.path.invariantSeparatorsPathString to SourceInventoryEntry(
                source.languageId,
                sha256(source.content.toByteArray(Charsets.UTF_8)),
            )
        }

    private fun workspaceLockIsHeld(): Boolean {
        val lockPath = workspaceRoot.resolve(".refactorkit/workspace.lock")
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
                try {
                    val competing = channel.tryLock()
                    if (competing == null) {
                        true
                    } else {
                        competing.release()
                        false
                    }
                } catch (_: OverlappingFileLockException) {
                    true
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun scanWorkspace(): ProjectSnapshot = JavaProjectScanner(
        allowNetworkDependencyResolution = false,
        localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
    ).scan(workspaceRoot)

    private fun runCli(arguments: List<String>): CliResult = synchronized(CLI_OUTPUT_MONITOR) {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            val exitCode = RefactorKitCli(
                scanner = JavaProjectScanner(
                    allowNetworkDependencyResolution = false,
                    localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
                ),
            ).run(arguments)
            CliResult(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun assertPomDependency(module: String, dependency: String, testOnly: Boolean = false) {
        val pom = Files.readString(workspaceRoot.resolve(module).resolve("pom.xml"))
        assertTrue(pom.contains("<artifactId>$dependency</artifactId>"))
        if (testOnly) assertTrue(pom.contains("<scope>test</scope>"))
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(assertNotNull(destination.parent))
                    Files.copy(
                        path,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES,
                    )
                }
            }
        }
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate the repository root")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class PreviewObservation(
        val snapshot: ProjectSnapshot,
        val plan: PatchPlan,
        val normalizedEdit: WorkspaceEdit,
        val stagedSnapshot: ProjectSnapshot,
        val observerClosure: Set<String>,
        val evidenceCounts: EvidenceCounts,
        val diagnosticsBefore: List<Diagnostic>,
        val diagnosticsStaged: List<Diagnostic>,
        val cli: CliResult,
    )

    private data class EvidenceCounts(
        val reactorModules: Int,
        val reactorSourceSets: Int,
        val sourceFiles: Int,
        val auxiliaryFiles: Int,
        val classpathEvidence: Int,
        val jdtSymbols: Int,
        val targetReferences: Int,
        val observerSourceSets: Int,
        val normalizedFileEdits: Int,
        val normalizedTextEdits: Int,
        val diagnosticsBefore: Int,
        val diagnosticsStaged: Int,
    )

    private data class WorkspaceState(
        val pathKinds: Map<String, String>,
        val fileHashes: Map<String, String>,
        val sourceInventory: Map<String, SourceInventoryEntry>,
        val snapshotHash: String,
    )

    private data class SourceInventoryEntry(
        val languageId: String,
        val sha256: String,
    )

    private data class CliResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        fun failureMessage(command: String): String = buildString {
            appendLine("Unexpected $command result (exit=$exitCode)")
            appendLine("stdout:")
            appendLine(stdout)
            appendLine("stderr:")
            append(stderr)
        }
    }

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val FIXTURE_REPOSITORY_PATH = "fixture-repository"
        const val MAVEN_BUILD_MODEL_PROVIDER = "maven-effective-v1"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val PRODUCT_TARGET_PACKAGE = "com.acme.catalog.api"
        const val PRODUCT_TARGET_FQN = "$PRODUCT_TARGET_PACKAGE.Product"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        const val PRICING_SOURCE_PATH = "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java"
        const val STOREFRONT_SOURCE_PATH = "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java"
        const val PRODUCT_STEPS_PATH =
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
        const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val EXTERNAL_ARTIFACT_SHA256 =
            "7f2e71601326da5129cb90435fb5442b958137f05fd28e4fec5192227268a3a2"
        const val TARGET_OWNER_SOURCE_SET = "catalog-model:main"
        val EXPECTED_OBSERVER_SOURCE_SETS = setOf(
            "catalog-pricing:main",
            "catalog-storefront:main",
            "catalog-acceptance:test",
        )
        val EXPECTED_OBSERVER_PATHS = setOf(
            Path.of(PRICING_SOURCE_PATH),
            Path.of(STOREFRONT_SOURCE_PATH),
            Path.of(PRODUCT_STEPS_PATH),
        )
        val EXPECTED_MANAGED_SOURCE_PATHS = EXPECTED_OBSERVER_PATHS + setOf(Path.of(PRODUCT_SOURCE_PATH))
        val EXPECTED_AFFECTED_PATHS = EXPECTED_MANAGED_SOURCE_PATHS + setOf(Path.of(PRODUCT_TARGET_PATH))
        val LEXICAL_BOUNDARY_PATHS = setOf(
            Path.of("catalog-decoy/src/main/java/com/acme/decoy/Product.java"),
            Path.of("reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java"),
            Path.of("catalog-acceptance/src/test/resources/features/product-lifecycle.feature"),
        )
        val FORBIDDEN_POM_MARKERS = listOf(
            "<build>",
            "<plugins>",
            "<plugin>",
            "<pluginRepositories>",
            "<repositories>",
            "<annotationProcessorPaths>",
        )
        val MODULE_PATTERN = Regex("""<module>\s*([^<]+)\s*</module>""")
        val PRODUCT_DECLARATION_PATTERN = Regex("""\b(?:class|interface|enum|record)\s+Product\b""")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        val CLI_OUTPUT_MONITOR = Any()
    }
}

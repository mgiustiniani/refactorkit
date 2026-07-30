package org.refactorkit.cli.mavenmoveobserverauthority

import io.cucumber.datatable.DataTable
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
import org.refactorkit.java.JavaLexer
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
import java.util.Comparator
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dedicated executable acceptance glue for REQ-JAVA-MAVEN-MOVE-AUTH-002 only. */
class JavaMavenMoveClassObserverAuthoritySteps {
    private lateinit var scenario: Scenario
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var baselineSnapshot: ProjectSnapshot
    private lateinit var baselineAnalysis: JdtJavaSemanticAnalysisResult
    private lateinit var targetBindingKey: String
    private lateinit var previewPlan: PatchPlan
    private lateinit var normalizedEdit: WorkspaceEdit
    private lateinit var stagedSnapshot: ProjectSnapshot
    private lateinit var cliPreview: CliResult
    private var baselineState: WorkspaceState? = null
    private var preApplyState: WorkspaceState? = null
    private var afterSnapshot: ProjectSnapshot? = null
    private var afterState: WorkspaceState? = null
    private var transaction: Transaction? = null
    private var candidateHashes: Map<Path, String> = emptyMap()
    private var observerRows: List<ObserverRow> = emptyList()
    private var workspaceLockObserved = false
    private var applyCount = 0
    private var rollbackCount = 0

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-002")
    fun prepareObserverAuthorityScenario(scenario: Scenario) {
        this.scenario = scenario
        val repositoryRoot = locateRepositoryRoot()
        fixtureTemplate = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        assertTrue(Files.isDirectory(fixtureTemplate), "Missing permanent Maven authority fixture")
        temporaryRoot = Files.createTempDirectory("refactorkit-move-auth-002-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyRecursively(fixtureTemplate, workspaceRoot)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        scenario.attach(
            "REQ-JAVA-MAVEN-MOVE-AUTH-002 runs alone against an isolated byte copy of the base 20-module " +
                "fixture. Feature text, requirement status, and every prior authority runner remain unchanged.",
            "text/plain",
            "observer-authority-isolation",
        )
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-002")
    fun removeScenarioWorkspace() {
        if (transaction != null && rollbackCount == 0 && this::workspaceRoot.isInitialized && Files.exists(workspaceRoot)) {
            runCatching {
                val result = PatchEngine(workspaceRoot).rollback(assertNotNull(transaction))
                if (result is ApplyResult.Applied) rollbackCount += 1
            }
        }
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
    fun theFixtureIsTheBaseTwentyModuleReactor() {
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        val modules = MODULE_PATTERN.findAll(rootPom).map { it.groupValues[1].trim() }.toList()
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        assertTrue(rootPom.contains("<packaging>pom</packaging>"))
        val poms = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }.toList()
        }
        assertEquals(21, poms.size)
        modules.forEach { module ->
            val childPom = workspaceRoot.resolve(module).resolve("pom.xml")
            assertTrue(Files.isRegularFile(childPom), "Missing child POM for $module")
            val content = Files.readString(childPom)
            assertFalse(content.contains("<modules>"), "$module must not aggregate modules")
            assertFalse(content.contains("<packaging>pom</packaging>"), "$module must be non-aggregating")
        }
        baselineSnapshot = scanWorkspace()
        assertEquals(20, baselineSnapshot.modules.size)
        assertEquals(BuildModelStatus.AVAILABLE, baselineSnapshot.buildModels.single().status)
        assertEquals(20, baselineSnapshot.buildModels.single().modules.size)
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun theFixtureHasTheRequiredTopologyAndMaterializedInputs() {
        assertPomDependency("catalog-pricing", "catalog-model")
        assertPomDependency("catalog-storefront", "catalog-pricing")
        assertPomDependency("catalog-acceptance", "catalog-storefront", testOnly = true)
        val artifact = workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH)
        assertTrue(Files.isRegularFile(artifact))
        assertEquals(EXTERNAL_ARTIFACT_SHA256, sha256(Files.readAllBytes(artifact)))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(GENERATED_SOURCE_PATH)))
        assertTrue(baselineSnapshot.files.any { it.path == Path.of(GENERATED_SOURCE_PATH) })
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryAndAnalysisDenyExecutableAndExternalInputs() {
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
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun theRequestMovesTheFixtureProduct(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(sourcePath)))
        assertTrue(Files.isWritable(workspaceRoot.resolve(sourcePath)))
        assertFalse(Files.exists(workspaceRoot.resolve(targetPath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, PRODUCT_DECLARATION_PATTERN.findAll(Files.readString(workspaceRoot.resolve(sourcePath))).count())
        assertEquals(TARGET_OWNER_SOURCE_SET, sourceSetDisplayName(baselineSnapshot, Path.of(sourcePath)))
    }

    @Given("the complete reverse-observer closure contains these binding-proven uses:")
    fun theCompleteClosureContainsOnlyTheBindingProvenUses(table: DataTable) {
        observerRows = table.asMaps().map { row ->
            ObserverRow(
                row.getValue("source set"),
                row.getValue("dependency relation"),
                row.getValue("Java use forms"),
            )
        }
        assertEquals(EXPECTED_OBSERVER_ROWS, observerRows)
        baselineAnalysis = JdtJavaSemanticAnalyzer().analyze(baselineSnapshot)
        assertTrue(baselineAnalysis.warnings.isEmpty(), baselineAnalysis.warnings.toString())
        val target = baselineAnalysis.symbols.single {
            it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        targetBindingKey = assertNotNull(target.bindingKey)
        assertFalse(target.recovered)
        val targetReferences = baselineAnalysis.references.filter {
            it.symbolQualifiedName == PRODUCT_FQN && it.bindingKey == targetBindingKey && !it.recovered
        }
        assertEquals(EXPECTED_OBSERVER_PATHS, targetReferences.mapTo(linkedSetOf()) { it.path })
        assertEquals(
            EXPECTED_OBSERVER_SOURCE_SETS,
            targetReferences.mapTo(linkedSetOf()) { sourceSetDisplayName(baselineSnapshot, it.path) },
        )
        assertObserverUseForms(baselineSnapshot, baselineAnalysis, PRODUCT_FQN, targetBindingKey)

        val model = baselineSnapshot.buildModels.single()
        EXPECTED_DIRECT_DEPENDENCIES.forEach { (sourceSet, expected) ->
            assertEquals(expected, moduleDependencyRows(buildSourceSet(model, sourceSet)), sourceSet)
        }
        assertFalse(buildSourceSet(model, "catalog-storefront:main").moduleDependencies.any {
            it.targetModuleId == "catalog-model"
        })
        baselineState = captureWorkspaceState(baselineSnapshot)
    }

    @Given("the effective reactor graph proves that {string} and {string} cannot observe the target")
    fun theGraphExcludesTheDecoyAndReportingSourceSets(decoy: String, reporting: String) {
        assertEquals("catalog-decoy:main", decoy)
        assertEquals("reporting-unrelated:main", reporting)
        val excludedGraph = mapOf(
            "catalog-decoy" to setOf("catalog-common"),
            "catalog-common" to emptySet(),
            "reporting-unrelated" to setOf("reporting-core"),
            "reporting-core" to setOf("order-processing"),
            "order-processing" to setOf("order-model"),
            "order-model" to setOf("customer-model"),
            "customer-model" to emptySet(),
        )
        excludedGraph.forEach { (module, expectedDependencies) ->
            assertEquals(expectedDependencies, mainModuleDependencies(baselineSnapshot, module), module)
            assertFalse("catalog-model" in expectedDependencies, "$module must not select catalog-model")
        }
    }

    @Given("the candidate inventory classifies these lexical matches:")
    fun theCandidateInventorySeparatesBoundOtherAndResidualMatches(table: DataTable) {
        val rows = table.asMaps().associate {
            Path.of(it.getValue("path")) to it.getValue("classification")
        }
        assertEquals(EXPECTED_CANDIDATE_CLASSIFICATIONS, rows)
        candidateHashes = rows.keys.associateWith { relative ->
            val absolute = workspaceRoot.resolve(relative)
            assertTrue(Files.isRegularFile(absolute), "Missing lexical candidate $relative")
            sha256(Files.readAllBytes(absolute))
        }

        val decoy = baselineAnalysis.symbols.single {
            it.qualifiedName == DECOY_FQN && it.path == DECOY_PATH
        }
        assertNotNull(decoy.bindingKey)
        assertFalse(decoy.recovered)
        assertTrue(decoy.bindingKey != targetBindingKey)
        assertEquals("catalog-decoy:main", sourceSetDisplayName(baselineSnapshot, DECOY_PATH))

        val reporting = baselineSnapshot.files.single { it.path == REPORTING_PATH }
        assertEquals(1, countOccurrences(reporting.content, PRODUCT_FQN))
        assertTrue(JavaLexer.findOccurrences(reporting.content, PRODUCT_FQN).isEmpty())
        assertTrue(baselineAnalysis.references.none {
            it.path == REPORTING_PATH && it.symbolQualifiedName == PRODUCT_FQN
        })
        assertEquals("reporting-unrelated:main", sourceSetDisplayName(baselineSnapshot, REPORTING_PATH))

        val feature = Files.readString(workspaceRoot.resolve(NON_JAVA_FEATURE_PATH))
        assertTrue(countOccurrences(feature, PRODUCT_FQN) >= 1)
        assertTrue(NON_JAVA_FEATURE_PATH !in baselineSnapshot.trackedFiles.map { it.path })
    }

    @When("the eligible binding-clean move is previewed and applied")
    fun theEligibleMoveIsPreviewedAndApplied() {
        val current = scanWorkspace()
        assertEquals(baselineSnapshot.hash, current.hash)
        assertEquals(assertNotNull(baselineState), captureWorkspaceState(current))
        previewPlan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            current,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertEquals(PatchStatus.PREVIEW, previewPlan.status, previewPlan.summary)
        assertEquals(RefactoringEvidence.JDT_BINDING, previewPlan.evidence, previewPlan.warnings.toString())
        assertTrue(previewPlan.requiresUserApproval)
        normalizedEdit = WorkspaceEditSimulator.normalize(previewPlan.workspaceEdit)
        assertManagedEditBoundary(normalizedEdit)
        stagedSnapshot = WorkspaceEditSimulator.apply(current, normalizedEdit)
        assertTrue(JavaLanguageAdapter().diagnostics(current).isEmpty())
        assertTrue(JavaLanguageAdapter().diagnostics(stagedSnapshot).isEmpty())

        cliPreview = runCli(
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
        assertEquals(0, cliPreview.exitCode, cliPreview.failureMessage("preview"))
        assertTrue(cliPreview.stderr.isEmpty(), cliPreview.failureMessage("preview"))
        assertEquals(current.hash, scanWorkspace().hash, "CLI preview must be read-only")

        preApplyState = captureWorkspaceState(current)
        workspaceLockObserved = false
        val injector = PatchFaultInjector { point, _, _ ->
            if (point == PatchFaultPoint.AFTER_STAGED_FILE_FORCE) {
                workspaceLockObserved = workspaceLockObserved || workspaceLockIsHeld()
            }
        }
        val result = PatchEngine(workspaceRoot, faultInjector = injector).apply(
            previewPlan,
            current,
            ApplyAuthorization.explicit("cucumber", REQUIREMENT_ID),
            DiagnosticsGate.enabled("java-maven-move-class-observers", JavaLanguageAdapter()::diagnostics),
        )
        transaction = assertIs<ApplyResult.Applied>(result).transaction
        applyCount += 1
        afterSnapshot = scanWorkspace()
        afterState = captureWorkspaceState(assertNotNull(afterSnapshot))
    }

    @Then("every listed Java use is updated exactly once and resolves to {string}")
    fun everyListedJavaUseIsUpdatedExactlyOnceAndResolves(newFqn: String) {
        assertEquals(PRODUCT_TARGET_FQN, newFqn)
        assertEquals(1, applyCount)
        assertTrue(workspaceLockObserved, "Managed apply must commit while the workspace lock is held")
        val transactionRecords = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(1, transactionRecords.size)
        assertEquals(assertNotNull(transaction).id, transactionRecords.single().transaction.id)
        assertEquals("APPLIED", transactionRecords.single().state.name)
        assertEquals(stagedSnapshot.hash, assertNotNull(afterSnapshot).hash)
        assertEquals(stagedSnapshot.files, assertNotNull(afterSnapshot).files)

        val modifications = normalizedEdit.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(EXPECTED_TEXT_EDIT_COUNTS, modifications.associate { it.path to it.textEdits.size })
        val rename = normalizedEdit.edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(Path.of(PRODUCT_SOURCE_PATH), rename.path)
        assertEquals(Path.of(PRODUCT_TARGET_PATH), rename.newPath)
        assertTrue(normalizedEdit.edits.none { it is FileEdit.Create || it is FileEdit.Delete })
        assertApplyChangedOnlyExpectedFiles(assertNotNull(preApplyState), assertNotNull(afterState))

        val expectedReplacementCounts = mapOf(
            PRICING_PATH to 1,
            STOREFRONT_PATH to 1,
            ACCEPTANCE_STEPS_PATH to 2,
        )
        expectedReplacementCounts.forEach { (path, count) ->
            val before = baselineSnapshot.files.single { it.path == path }.content
            val after = assertNotNull(afterSnapshot).files.single { it.path == path }.content
            assertEquals(count, countOccurrences(before, PRODUCT_FQN), "$path old-FQN edit count")
            assertEquals(0, countOccurrences(after, PRODUCT_FQN), "$path retained old FQN")
            assertEquals(count, countOccurrences(after, PRODUCT_TARGET_FQN), "$path new-FQN edit count")
        }

        val analysis = JdtJavaSemanticAnalyzer().analyze(assertNotNull(afterSnapshot))
        assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
        val target = analysis.symbols.single {
            it.qualifiedName == PRODUCT_TARGET_FQN && it.path == Path.of(PRODUCT_TARGET_PATH)
        }
        val binding = assertNotNull(target.bindingKey)
        assertFalse(target.recovered)
        assertObserverUseForms(assertNotNull(afterSnapshot), analysis, PRODUCT_TARGET_FQN, binding)
        val references = analysis.references.filter {
            it.symbolQualifiedName == PRODUCT_TARGET_FQN && it.bindingKey == binding && !it.recovered
        }
        assertEquals(EXPECTED_OBSERVER_PATHS, references.mapTo(linkedSetOf()) { it.path })
    }

    @Then("no resolvable reference to {string} remains in the authority scope")
    fun noOldResolvableReferenceRemainsInAuthorityScope(oldFqn: String) {
        assertEquals(PRODUCT_FQN, oldFqn)
        val snapshot = assertNotNull(afterSnapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val authorityPaths = EXPECTED_OBSERVER_PATHS + setOf(Path.of(PRODUCT_TARGET_PATH))
        assertTrue(analysis.symbols.none { it.qualifiedName == oldFqn })
        assertTrue(analysis.references.none {
            it.path in authorityPaths && (it.symbolQualifiedName == oldFqn || it.symbolQualifiedName.substringBefore('#') == oldFqn)
        })
        assertTrue(analysis.bindingUses.none { use ->
            use.path in authorityPaths &&
                (use.symbolQualifiedName == oldFqn || use.symbolQualifiedName?.substringBefore('#') == oldFqn)
        })
        authorityPaths.forEach { path ->
            assertFalse(snapshot.files.single { it.path == path }.content.contains(oldFqn), "$path retains old authority FQN")
        }
        assertTrue(snapshot.files.single { it.path == REPORTING_PATH }.content.contains(oldFqn))
        assertTrue(Files.readString(workspaceRoot.resolve(NON_JAVA_FEATURE_PATH)).contains(oldFqn))
        assertTrue(JavaLanguageAdapter().diagnostics(snapshot).none { it.severity == Diagnostic.Severity.ERROR })
    }

    @Then("no unrelated source set was added to an observer's JDT source path")
    fun noUnrelatedSourceSetWasAddedToObserverSourcePaths() {
        val after = assertNotNull(afterSnapshot)
        assertEquals(baselineSnapshot.buildModels, after.buildModels, "Authoritative build/source-path model drifted")
        assertEquals(baselineSnapshot.modules, after.modules, "Scanner module/source-root metadata drifted")
        val beforeModel = baselineSnapshot.buildModels.single()
        val afterModel = after.buildModels.single()
        val unrelatedRoots = setOf("catalog-decoy", "reporting-unrelated").flatMapTo(linkedSetOf()) { module ->
            afterModel.modules.single { it.id == module }.sourceSets.flatMap(BuildSourceSet::sourceRoots)
        }
        EXPECTED_DIRECT_DEPENDENCIES.forEach { (observer, expectedDependencies) ->
            val beforeSourceSet = buildSourceSet(beforeModel, observer)
            val afterSourceSet = buildSourceSet(afterModel, observer)
            assertEquals(beforeSourceSet, afterSourceSet, "$observer JDT source-set evidence drifted")
            assertEquals(expectedDependencies, moduleDependencyRows(afterSourceSet), observer)
            assertTrue(afterSourceSet.sourceRoots.none(unrelatedRoots::contains), "$observer acquired unrelated roots")
        }
        assertTrue(previewPlan.warnings.any { warning ->
            warning.contains("Authoritative dependency-bounded reverse-observer closure") &&
                EXPECTED_OBSERVER_SOURCE_SETS.all(warning::contains) &&
                warning.contains(TARGET_OWNER_SOURCE_SET)
        }, previewPlan.warnings.toString())
        scenario.attach(
            EXPECTED_OBSERVER_SOURCE_SETS.sorted().joinToString("\n") { observer ->
                val sourceSet = buildSourceSet(afterModel, observer)
                "$observer roots=${sourceSet.sourceRoots.joinToString()} dependencies=${moduleDependencyRows(sourceSet)}"
            },
            "text/plain",
            "dependency-bounded-jdt-source-paths",
        )
    }

    @Then("every listed lexical candidate remains byte-for-byte unchanged")
    fun everyLexicalCandidateRemainsByteIdentical() {
        assertEquals(EXPECTED_CANDIDATE_CLASSIFICATIONS.keys, candidateHashes.keys)
        candidateHashes.forEach { (path, expectedHash) ->
            assertEquals(expectedHash, sha256(Files.readAllBytes(workspaceRoot.resolve(path))), "$path changed")
            assertTrue(path !in previewPlan.affectedFiles, "$path entered the managed edit boundary")
        }
        val after = assertNotNull(afterState)
        listOf(DECOY_PATH, REPORTING_PATH).forEach { path ->
            val relative = path.invariantSeparatorsPathString
            assertEquals(assertNotNull(baselineState).fileHashes[relative], after.fileHashes[relative], "$path changed")
        }
    }

    @Then("the preview reports the same-name and unrelated Java candidates as excluded and the non-Java match as a residual review risk")
    fun thePreviewReportsExcludedCandidatesAndResidualRisk() {
        val warnings = previewPlan.warnings
        val failures = buildList {
            val totals = warnings.singleOrNull { it.startsWith("Candidate-total JDT-classified Java evidence:") }
            val expectedTotals =
                "BOUND_TARGET=12, BOUND_OTHER=1, UNRESOLVED=0; " +
                    "JAVA_NON_CODE_RESIDUAL=1; NON_JAVA_RESIDUAL=2."
            if (totals == null || !totals.contains(expectedTotals)) {
                add("missing exact binding/residual totals: $expectedTotals")
            }
            val decoy = warnings.singleOrNull { it.contains(DECOY_PATH.invariantSeparatorsPathString) }
            if (decoy == null || !decoy.contains("classification=BOUND_OTHER") ||
                !decoy.contains("excluded", ignoreCase = true)
            ) {
                add("missing exact BOUND_OTHER/excluded report for $DECOY_PATH")
            }
            val reporting = warnings.singleOrNull { it.contains(REPORTING_PATH.invariantSeparatorsPathString) }
            if (reporting == null || !reporting.contains("classification=JAVA_NON_CODE_RESIDUAL") ||
                reporting.contains("classification=BOUND_OTHER") ||
                !reporting.contains("residual review risk", ignoreCase = true) ||
                !reporting.contains("managedEdit=false") ||
                !reporting.contains("outside the dependency-bounded reverse-observer closure")
            ) {
                add("missing outside-closure JAVA_NON_CODE_RESIDUAL report for $REPORTING_PATH")
            }
            val nonJava = warnings.singleOrNull { it.contains(NON_JAVA_FEATURE_PATH.invariantSeparatorsPathString) }
            if (nonJava == null || !nonJava.contains("classification=NON_JAVA_RESIDUAL") ||
                !nonJava.contains("residual review risk", ignoreCase = true) ||
                !nonJava.contains("managedEdit=false")
            ) {
                add("missing NON_JAVA_RESIDUAL review-risk report for $NON_JAVA_FEATURE_PATH")
            }
            if (warnings.none {
                    it.contains("completeness-and-veto", ignoreCase = true) &&
                        it.contains("never selects", ignoreCase = true)
                }
            ) {
                add("missing attestation that lexical evidence never selects managed edits")
            }
            listOf(DECOY_PATH, REPORTING_PATH, NON_JAVA_FEATURE_PATH).forEach { path ->
                if (!cliPreview.stdout.contains(path.invariantSeparatorsPathString)) {
                    add("source-built CLI preview omitted $path")
                }
            }
        }

        rollbackAndAssertExactRestoration()
        scenario.attach(warnings.joinToString("\n"), "text/plain", "candidate-total-preview-report")
        scenario.attach(
            "One managed apply and one normal rollback restored snapshot ${assertNotNull(baselineState).snapshotHash}. " +
                "The exact-binding Java exclusion, Java non-code residual, and non-Java residual stayed byte-identical.",
            "text/plain",
            "observer-authority-rollback",
        )
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    private fun assertManagedEditBoundary(edit: WorkspaceEdit) {
        val modifications = edit.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(EXPECTED_TEXT_EDIT_COUNTS.keys, modifications.mapTo(linkedSetOf()) { it.path })
        assertEquals(EXPECTED_TEXT_EDIT_COUNTS, modifications.associate { it.path to it.textEdits.size })
        assertEquals(5, modifications.sumOf { it.textEdits.size })
        val rename = edit.edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(Path.of(PRODUCT_SOURCE_PATH), rename.path)
        assertEquals(Path.of(PRODUCT_TARGET_PATH), rename.newPath)
        assertEquals(EXPECTED_AFFECTED_PATHS, previewPlan.affectedFiles)
        assertTrue(EXPECTED_CANDIDATE_CLASSIFICATIONS.keys.none { it in previewPlan.affectedFiles })
    }

    private fun assertObserverUseForms(
        snapshot: ProjectSnapshot,
        analysis: JdtJavaSemanticAnalysisResult,
        targetFqn: String,
        bindingKey: String,
    ) {
        val pricing = snapshot.files.single { it.path == PRICING_PATH }.content
        assertTrue(pricing.contains("import $targetFqn;"), pricing)
        assertTrue(pricing.contains("private final Product product = new Product();"), pricing)
        assertTrue(pricing.contains("public Product product()"), pricing)

        val storefront = snapshot.files.single { it.path == STOREFRONT_PATH }.content
        assertTrue(storefront.contains("import $targetFqn;"), storefront)
        assertTrue(storefront.contains("candidate instanceof Product"), storefront)
        assertTrue(storefront.contains("Product.class.isInstance(candidate)"), storefront)

        val acceptance = snapshot.files.single { it.path == ACCEPTANCE_STEPS_PATH }.content
        assertTrue(acceptance.contains("import $targetFqn;"), acceptance)
        assertTrue(acceptance.contains("private Product importedProduct;"), acceptance)
        assertTrue(acceptance.contains("private $targetFqn fullyQualifiedProduct;"), acceptance)

        EXPECTED_OBSERVER_PATHS.forEach { path ->
            val uses = analysis.bindingUses.filter { use ->
                use.path == path && !use.recovered &&
                    (use.bindingKey == bindingKey || use.symbolQualifiedName == targetFqn ||
                        use.symbolQualifiedName?.substringBefore('#') == targetFqn)
            }
            assertTrue(uses.isNotEmpty(), "$path has no exact JDT Product binding uses")
            assertTrue(uses.all { it.evidence.name == "JDT_BINDING" })
        }
    }

    private fun rollbackAndAssertExactRestoration() {
        val result = PatchEngine(workspaceRoot).rollback(assertNotNull(transaction))
        assertIs<ApplyResult.Applied>(result)
        rollbackCount += 1
        assertEquals(1, rollbackCount)
        val restoredSnapshot = scanWorkspace()
        val restoredState = captureWorkspaceState(restoredSnapshot)
        assertEquals(assertNotNull(preApplyState), restoredState)
        assertEquals(baselineSnapshot.hash, restoredSnapshot.hash)
        assertTrue(JavaLanguageAdapter().diagnostics(restoredSnapshot).isEmpty())
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(PRODUCT_SOURCE_PATH)))
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        candidateHashes.forEach { (path, expectedHash) ->
            assertEquals(expectedHash, sha256(Files.readAllBytes(workspaceRoot.resolve(path))), "$path changed after rollback")
        }
        val records = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(1, records.size)
        assertEquals(assertNotNull(transaction).id, records.single().transaction.id)
        assertEquals("ROLLED_BACK", records.single().state.name)
    }

    private fun mainModuleDependencies(snapshot: ProjectSnapshot, moduleId: String): Set<String> {
        val module = snapshot.buildModels.single().modules.single { it.id == moduleId }
        val main = module.sourceSets.single { it.id == "main" }
        return main.moduleDependencies.mapTo(linkedSetOf()) { it.targetModuleId }
    }

    private fun moduleDependencyRows(sourceSet: BuildSourceSet): Set<Pair<String, DependencyScope>> =
        sourceSet.moduleDependencies.mapTo(linkedSetOf()) { it.targetModuleId to it.scope }

    private fun buildSourceSet(model: org.refactorkit.core.BuildModel, displayName: String): BuildSourceSet {
        val (moduleId, sourceSetId) = displayName.split(':', limit = 2)
        return model.modules.single { it.id == moduleId }.sourceSets.single { it.id == sourceSetId }
    }

    private fun sourceSetDisplayName(snapshot: ProjectSnapshot, path: Path): String {
        val owner = snapshot.owningBuildSourceRoots(path).singleOrNull()
        assertNotNull(owner, "$path must have one authoritative source-set owner")
        return "${owner.module.id}:${owner.sourceSet.id}"
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
        val sourceInventory = snapshot.files.sortedBy { it.path.invariantSeparatorsPathString }.associate { file ->
            file.path.invariantSeparatorsPathString to sha256(file.content.toByteArray(Charsets.UTF_8))
        }
        return WorkspaceState(pathKinds, fileHashes, sourceInventory, snapshot.hash)
    }

    private fun workspaceLockIsHeld(): Boolean {
        val lockPath = workspaceRoot.resolve(".refactorkit/workspace.lock")
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
                try {
                    val competing = channel.tryLock()
                    if (competing == null) true else {
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
        error("Cannot locate repository root")
    }

    private fun countOccurrences(content: String, text: String): Int {
        var count = 0
        var offset = 0
        while (true) {
            val found = content.indexOf(text, offset)
            if (found < 0) return count
            count += 1
            offset = found + text.length
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class ObserverRow(
        val sourceSet: String,
        val dependencyRelation: String,
        val javaUseForms: String,
    )

    private data class WorkspaceState(
        val pathKinds: Map<String, String>,
        val fileHashes: Map<String, String>,
        val sourceInventory: Map<String, String>,
        val snapshotHash: String,
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
        const val REQUIREMENT_ID = "REQ-JAVA-MAVEN-MOVE-AUTH-002"
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val FIXTURE_REPOSITORY_PATH = "fixture-repository"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val PRODUCT_TARGET_PACKAGE = "com.acme.catalog.api"
        const val PRODUCT_TARGET_FQN = "$PRODUCT_TARGET_PACKAGE.Product"
        const val DECOY_FQN = "com.acme.decoy.Product"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        const val PRICING_SOURCE_PATH = "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java"
        const val STOREFRONT_SOURCE_PATH = "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java"
        const val ACCEPTANCE_STEPS_SOURCE_PATH =
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
        const val DECOY_SOURCE_PATH = "catalog-decoy/src/main/java/com/acme/decoy/Product.java"
        const val REPORTING_SOURCE_PATH =
            "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java"
        const val NON_JAVA_FEATURE_SOURCE_PATH =
            "catalog-acceptance/src/test/resources/features/product-lifecycle.feature"
        const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val EXTERNAL_ARTIFACT_SHA256 =
            "7f2e71601326da5129cb90435fb5442b958137f05fd28e4fec5192227268a3a2"
        const val TARGET_OWNER_SOURCE_SET = "catalog-model:main"
        val PRICING_PATH: Path = Path.of(PRICING_SOURCE_PATH)
        val STOREFRONT_PATH: Path = Path.of(STOREFRONT_SOURCE_PATH)
        val ACCEPTANCE_STEPS_PATH: Path = Path.of(ACCEPTANCE_STEPS_SOURCE_PATH)
        val DECOY_PATH: Path = Path.of(DECOY_SOURCE_PATH)
        val REPORTING_PATH: Path = Path.of(REPORTING_SOURCE_PATH)
        val NON_JAVA_FEATURE_PATH: Path = Path.of(NON_JAVA_FEATURE_SOURCE_PATH)
        val EXPECTED_OBSERVER_ROWS = listOf(
            ObserverRow("catalog-pricing:main", "direct", "import, constructor, and declared type"),
            ObserverRow("catalog-storefront:main", "transitive", "instanceof and class literal"),
            ObserverRow(
                "catalog-acceptance:test",
                "test-only",
                "Cucumber step-definition import and FQN use",
            ),
        )
        val EXPECTED_OBSERVER_SOURCE_SETS = linkedSetOf(
            "catalog-pricing:main",
            "catalog-storefront:main",
            "catalog-acceptance:test",
        )
        val EXPECTED_OBSERVER_PATHS = linkedSetOf(PRICING_PATH, STOREFRONT_PATH, ACCEPTANCE_STEPS_PATH)
        val EXPECTED_DIRECT_DEPENDENCIES = linkedMapOf(
            "catalog-pricing:main" to setOf("catalog-model" to DependencyScope.COMPILE),
            "catalog-storefront:main" to setOf("catalog-pricing" to DependencyScope.COMPILE),
            "catalog-acceptance:test" to setOf("catalog-storefront" to DependencyScope.TEST),
        )
        val EXPECTED_CANDIDATE_CLASSIFICATIONS = linkedMapOf(
            DECOY_PATH to "same-simple-name decoy",
            REPORTING_PATH to "unrelated source-set candidate",
            NON_JAVA_FEATURE_PATH to "non-Java lexical candidate",
        )
        val EXPECTED_TEXT_EDIT_COUNTS = linkedMapOf(
            Path.of(PRODUCT_SOURCE_PATH) to 1,
            PRICING_PATH to 1,
            STOREFRONT_PATH to 1,
            ACCEPTANCE_STEPS_PATH to 2,
        )
        val EXPECTED_AFFECTED_PATHS = EXPECTED_TEXT_EDIT_COUNTS.keys + setOf(Path.of(PRODUCT_TARGET_PATH))
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
        val CLI_OUTPUT_MONITOR = Any()
    }
}

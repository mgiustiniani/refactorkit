package org.refactorkit.cli.packagedmavenmoveauthority

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Packaged-process-only acceptance glue for existing REQ-JAVA-MAVEN-MOVE-AUTH-001. */
class PackagedJavaMavenMoveClassAuthoritySteps {
    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var packageRoot: Path
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var isolatedHome: Path
    private lateinit var isolatedTemp: Path
    private lateinit var poisonDirectory: Path
    private lateinit var poisonMarker: Path
    private lateinit var launcher: Path
    private lateinit var embeddedJava: Path
    private lateinit var harness: PackagedProcessHarness

    private lateinit var templateManifestBefore: TreeManifest
    private lateinit var packageManifestBefore: TreeManifest
    private lateinit var baselineWorkspaceManifest: TreeManifest
    private lateinit var baselineScan: ScanObservation
    private lateinit var baselineDiagnostics: String
    private lateinit var referenceObservation: ReferenceObservation
    private lateinit var definitionObservation: String
    private var previewResults: List<BoundedProcessResult> = emptyList()
    private var canonicalPreview: String? = null
    private var preApplyManifest: TreeManifest? = null
    private var lockRefusalObservation: LockRefusalObservation? = null
    private var successfulApplyInvocations: Int = 0
    private var lockContentionApplyInvocations: Int = 0
    private var applyResult: BoundedProcessResult? = null
    private var transactionId: String? = null
    private var postApplyManifest: TreeManifest? = null
    private var postApplyScan: ScanObservation? = null
    private var postApplyDiagnostics: String? = null
    private var appliedRecord: TransactionJournalRecord? = null
    private var rollbackResult: BoundedProcessResult? = null
    private var rollbackManifest: TreeManifest? = null
    private var rollbackScan: ScanObservation? = null
    private var rollbackDiagnostics: String? = null
    private val processEvidence = mutableListOf<ProcessEvidence>()

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-001")
    fun preparePackagedAuthorityScenario(scenario: Scenario) {
        this.scenario = scenario
        repositoryRoot = requiredDirectoryProperty(REPOSITORY_ROOT_PROPERTY)
        packageRoot = requiredDirectoryProperty(PACKAGE_ROOT_PROPERTY)
        fixtureTemplate = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        assertTrue(Files.isDirectory(fixtureTemplate, LinkOption.NOFOLLOW_LINKS), "Permanent fixture is missing")
        assertFalse(Files.isSymbolicLink(fixtureTemplate), "Permanent fixture root must not be a link")

        templateManifestBefore = captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true)
        packageManifestBefore = captureTreeManifest(packageRoot, rejectUnsafeEntries = true)

        temporaryRoot = Files.createTempDirectory("refactorkit-packaged-move-auth-001-${UUID.randomUUID()}-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        isolatedHome = Files.createDirectories(temporaryRoot.resolve("subject-home"))
        isolatedTemp = Files.createDirectories(temporaryRoot.resolve("subject-tmp"))
        poisonDirectory = Files.createDirectories(temporaryRoot.resolve("poison-path"))
        poisonMarker = poisonDirectory.resolve(POISON_MARKER_NAME)
        installPoisonJavaTripwires(poisonDirectory)

        copyTreeNoFollow(fixtureTemplate, workspaceRoot)
        baselineWorkspaceManifest = captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true)
        assertEquals(templateManifestBefore, baselineWorkspaceManifest, "Fixture copy is not byte/path-kind exact")
        assertEquals(templateManifestBefore, captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true))
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))

        val windows = isWindows()
        launcher = packageRoot.resolve(if (windows) "bin/refactorkit.bat" else "bin/refactorkit")
        embeddedJava = packageRoot.resolve(if (windows) "runtime/bin/java.exe" else "runtime/bin/java")
        harness = PackagedProcessHarness(
            launcher = launcher,
            embeddedJava = embeddedJava,
            poisonDirectory = poisonDirectory,
            isolatedHome = isolatedHome,
            isolatedTemp = isolatedTemp,
            poisonMarker = poisonMarker,
        )
        assertWindowsBannerNormalizationContract()
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-001")
    fun cleanPackagedAuthorityScenario() {
        var cleanupAttempts = 0
        var finalObservedDirectProcessCount: Int? = null
        var finalPoisonMarkerObserved: Boolean? = null
        try {
            if (this::harness.isInitialized) {
                finalObservedDirectProcessCount = harness.activeDirectProcessCount()
                finalPoisonMarkerObserved = harness.poisonMarkerWasObserved()
                assertEquals(0, finalObservedDirectProcessCount, "A packaged direct child process is still active")
                assertEquals(false, finalPoisonMarkerObserved, "A poison java marker shim was invoked")
                assertEquals(0, harness.timeoutCount, "A packaged child timed out")
                assertEquals(0, harness.truncatedStreamCount, "A packaged child exceeded an output cap")
            }
            if (this::fixtureTemplate.isInitialized) {
                assertEquals(
                    templateManifestBefore,
                    captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true),
                    "The permanent fixture changed",
                )
            }
            if (this::packageRoot.isInitialized) {
                assertEquals(
                    packageManifestBefore,
                    captureTreeManifest(packageRoot, rejectUnsafeEntries = true),
                    "The unpacked package changed during validation",
                )
            }
        } finally {
            if (this::temporaryRoot.isInitialized) {
                cleanupAttempts = deleteTreeNoFollowOnce(temporaryRoot)
                assertFalse(Files.exists(temporaryRoot, LinkOption.NOFOLLOW_LINKS), "Temporary workspace cleanup failed")
            }
            if (this::scenario.isInitialized) {
                val commands = if (this::harness.isInitialized) harness.commandCount else 0
                val maximumMillis = if (this::harness.isInitialized) harness.maximumDurationMillis else 0
                val maximumStdout = processEvidence.maxOfOrNull(ProcessEvidence::stdoutBytes) ?: 0
                val maximumStderr = processEvidence.maxOfOrNull(ProcessEvidence::stderrBytes) ?: 0
                val terminatedDescendants = processEvidence.sumOf(ProcessEvidence::descendantsTerminated)
                scenario.attach(
                    "commands=$commands; timeoutSeconds=${PackagedProcessHarness.CHILD_TIMEOUT.seconds}; " +
                        "stdoutCap=${PackagedProcessHarness.OUTPUT_LIMIT_BYTES}; " +
                        "stderrCap=${PackagedProcessHarness.OUTPUT_LIMIT_BYTES}; " +
                        "maximumStdoutObserved=$maximumStdout; maximumStderrObserved=$maximumStderr; " +
                        "timeouts=${if (this::harness.isInitialized) harness.timeoutCount else 0}; " +
                        "truncatedStreams=${if (this::harness.isInitialized) harness.truncatedStreamCount else 0}; " +
                        "maximumCommandMillis=$maximumMillis; terminatedDescendants=$terminatedDescendants; " +
                        "gracefulDestroyCalls=${if (this::harness.isInitialized) harness.gracefulDestroyCount else 0}; " +
                        "forcedDestroyCalls=${if (this::harness.isInitialized) harness.forcedDestroyCount else 0}; " +
                        "finalObservedDirectProcessCount=${finalObservedDirectProcessCount ?: "unavailable"}; " +
                        "poisonMarkerObserved=${finalPoisonMarkerObserved ?: "unavailable"}; " +
                        "directProcessObservationScope=successfully-started-handles-registered-by-harness; " +
                        "unobservedAdverseTerminationPaths=before-handle-registration-or-after-abrupt-test-JVM-termination; " +
                        "cleanupAttempts=$cleanupAttempts; cleanupComplete=true; " +
                        "fixtureManifestSha256=${if (this::templateManifestBefore.isInitialized) templateManifestBefore.sha256 else "unavailable"}; " +
                        "copyManifestSha256=${if (this::baselineWorkspaceManifest.isInitialized) baselineWorkspaceManifest.sha256 else "unavailable"}; " +
                        "packageManifestSha256=${if (this::packageManifestBefore.isInitialized) packageManifestBefore.sha256 else "unavailable"}",
                    "text/plain",
                    "bounded-process-and-cleanup-attestation",
                )
            }
        }
    }

    @Given("the declared workspace root is the permanent fixture {string}")
    fun theDeclaredWorkspaceRootIsThePermanentFixture(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertEquals(fixtureTemplate.toRealPath(), repositoryRoot.resolve(path).toRealPath())
        assertPackageAndRuntimeIdentity()
        val probe = observeProcess(
            "embedded-runtime-properties",
            harness.runEmbeddedJava(listOf("-XshowSettings:properties", "-version"), packageRoot),
            expectedExit = 0,
        )
        val javaHome = settingsProperty(probe.stderr, "java.home").toRealPath()
        val javaTemp = settingsProperty(probe.stderr, "java.io.tmpdir").toAbsolutePath().normalize()
        val userHome = settingsProperty(probe.stderr, "user.home").toAbsolutePath().normalize()
        val runtime = packageRoot.resolve("runtime").toRealPath()
        assertEquals(runtime, javaHome, "The child java.home is not the packaged runtime")
        assertTrue(embeddedJava.toRealPath().startsWith(javaHome), "Embedded java is outside child java.home")
        assertEquals(isolatedTemp.toAbsolutePath().normalize(), javaTemp, "Child temp is not isolated")
        assertEquals(isolatedHome.toAbsolutePath().normalize(), userHome, "Child user.home is not isolated")
        assertFalse(harness.poisonMarkerWasObserved())
        scenario.attach(
            "launcherSha256=${sha256(launcher)}; runtimeJava=runtime/bin/${embeddedJava.fileName}; " +
                "java.home=runtime; user.home=isolated; java.io.tmpdir=isolated; " +
                "host=${System.getProperty("os.name")}; architecture=${System.getProperty("os.arch")}",
            "text/plain",
            "packaged-runtime-identity",
        )
    }

    @Given("the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules")
    fun theFixtureIsAPluginFreeOfflineMavenReactor() {
        val rootPom = parseXml(workspaceRoot.resolve("pom.xml"))
        val modules = elements(rootPom, "module").map(Element::getTextContent).map(String::trim)
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        assertEquals("pom", firstElementText(rootPom, "packaging"))
        assertEquals(21, baselineWorkspaceManifest.filePaths.count { it == "pom.xml" || it.endsWith("/pom.xml") })
        modules.forEach { module ->
            val childPomPath = workspaceRoot.resolve(module).resolve("pom.xml")
            assertTrue(Files.isRegularFile(childPomPath, LinkOption.NOFOLLOW_LINKS), "Active child POM is missing")
            val child = parseXml(childPomPath)
            assertTrue(elements(child, "module").isEmpty(), "An active child unexpectedly aggregates modules")
            assertNotEquals("pom", firstElementText(child, "packaging"), "An active child must produce a JAR")
        }
        FORBIDDEN_POM_ELEMENTS.forEach { element ->
            assertTrue(allPomDocuments().all { elements(it, element).isEmpty() }, "Fixture POM contains $element")
        }
        baselineScan = runScan("baseline-scan")
        assertEquals(20, baselineScan.modules)
        assertEquals(7, baselineScan.files)
        assertTrue(SHA256.matches(baselineScan.snapshot))
        assertWorkspaceStillBaseline("packaged scan")
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun itsActiveGraphHasRequiredTopologyAndMaterializedInputs() {
        assertPomDependency("catalog-acceptance", "catalog-storefront", "test")
        assertPomDependency("catalog-storefront", "catalog-pricing", null)
        assertPomDependency("catalog-pricing", "catalog-model", null)
        assertEquals(EXTERNAL_ARTIFACT_SHA256, baselineWorkspaceManifest.fileSha256(EXTERNAL_ARTIFACT_PATH))
        assertTrue(GENERATED_SOURCE_PATH in baselineWorkspaceManifest.filePaths)
        assertTrue(GENERATED_INVENTORY_PATH in baselineWorkspaceManifest.filePaths)
        assertEquals(7, scannerJavaInventory(baselineWorkspaceManifest).size)
        assertEquals(20, baselineScan.modules)
        assertEquals(7, baselineScan.files)
        assertWorkspaceStillBaseline("materialized-input observation")
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryAndAnalysisDenyExecutableOrExternalInputs() {
        listOf("mvnw", "mvnw.cmd", ".mvn", "settings.xml").forEach { relative ->
            assertFalse(Files.exists(workspaceRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS))
        }
        val environment = harness.environmentAttestation
        assertTrue(environment.inheritedEnvironmentCleared)
        assertTrue(environment.requiredVariablesAbsent)
        assertTrue(environment.proxyVariablesAbsent)
        assertTrue(environment.poisonPathFirst)
        assertTrue(environment.isolatedHomeConfigured)
        assertTrue(environment.isolatedTempConfigured)
        assertTrue(environment.controlledJavaToolOptions)
        if (isWindows()) {
            assertTrue(environment.windowsInvalidJavaExecutableSentinelInstalled)
            assertTrue(environment.windowsBatchAndCmdMarkerShimsInstalled)
        }
        baselineDiagnostics = runDiagnostics("baseline-diagnostics")
        assertEquals("No diagnostics.\n", baselineDiagnostics)
        assertFalse(harness.poisonMarkerWasObserved())
        assertWorkspaceStillBaseline("packaged diagnostics")
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun theRequestMovesTheWritableSoleTopLevelClass(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        val source = workspaceRoot.resolve(sourcePath)
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(source))
        assertFalse(Files.exists(workspaceRoot.resolve(targetPath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, PRODUCT_DECLARATION.findAll(Files.readString(source)).count())
        assertEquals("catalog-model", sourcePath.substringBefore('/'))
        assertEquals("catalog-model", targetPath.substringBefore('/'))
        assertEquals(20, baselineScan.modules)
        assertEquals(7, baselineScan.files)
        assertWorkspaceStillBaseline("canonical request observation")
    }

    @Given("the full effective reactor graph and complete reverse-observer closure are available and hash-bound")
    fun theFullEffectiveGraphAndObserverClosureAreAvailableAndHashBound() {
        val result = observeProcess(
            "packaged-references",
            harness.runLauncher(
                listOf("references", "--symbol", PRODUCT_FQN, workspaceRoot.toString()),
                workspaceRoot,
            ),
            expectedExit = 0,
        )
        assertCleanCliStderr(result, "packaged references")
        val lines = normalizeExternalOutput(result.stdout).lineSequence().filter(String::isNotBlank).toList()
        val paths = lines.map { it.substringBeforeLast(':') }
        assertTrue(paths.size >= 12, "Expected bounded exact target references")
        assertEquals(EXPECTED_REFERENCE_PATHS, paths.toSet())
        referenceObservation = ReferenceObservation(paths, paths.filter { it != PRODUCT_SOURCE_PATH }.toSet())
        assertEquals(EXPECTED_OBSERVER_PATHS, referenceObservation.observerPaths)
        val repeatedScan = runScan("hash-bound-repeat-scan")
        assertEquals(baselineScan, repeatedScan)
        assertWorkspaceStillBaseline("reference and repeated-scan observation")
    }

    @Given("every observer has current dependency-bounded source paths, classpaths, source inventories, Java platform signatures, and provider evidence")
    fun everyObserverHasCurrentAnalysisEvidence() {
        assertEquals(EXPECTED_OBSERVER_PATHS, referenceObservation.observerPaths)
        EXPECTED_OBSERVER_PATHS.forEach { path -> assertTrue(path in baselineWorkspaceManifest.filePaths) }
        assertPomDependency("catalog-acceptance", "catalog-storefront", "test")
        assertPomDependency("catalog-storefront", "catalog-pricing", null)
        assertPomDependency("catalog-pricing", "catalog-model", null)
        assertTrue(EXPECTED_SOURCE_INVENTORY_PATH in baselineWorkspaceManifest.filePaths)
        assertTrue(GENERATED_INVENTORY_PATH in baselineWorkspaceManifest.filePaths)
        assertEquals("8", firstElementText(parseXml(workspaceRoot.resolve("pom.xml")), "maven.compiler.release"))
        assertTrue(Files.isRegularFile(packageRoot.resolve("runtime/release"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(packageRoot.resolve("runtime/lib/ct.sym"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("No diagnostics.\n", baselineDiagnostics)
        assertWorkspaceStillBaseline("observer evidence observation")
    }

    @Given("the target declaration and every managed Java edit site resolve to the same exact non-recovered JDT binding")
    fun targetAndManagedEditSitesResolveToOneExactBinding() {
        val result = observeProcess(
            "packaged-definition",
            harness.runLauncher(
                listOf("definition", "--symbol", PRODUCT_FQN, workspaceRoot.toString()),
                workspaceRoot,
            ),
            expectedExit = 0,
        )
        assertCleanCliStderr(result, "packaged definition")
        definitionObservation = normalizeExternalOutput(result.stdout)
        val definitionLine = definitionObservation.lineSequence().single(String::isNotBlank)
        assertEquals(PRODUCT_SOURCE_PATH, definitionLine.substringBeforeLast(':'))
        assertTrue(definitionLine.substringAfterLast(':').toInt() > 0)
        assertEquals(EXPECTED_REFERENCE_PATHS, referenceObservation.paths.toSet())
        assertFalse(referenceObservation.paths.any { it in LEXICAL_BOUNDARY_PATHS })
        assertWorkspaceStillBaseline("definition observation")
    }

    @When("the move is previewed twice from identical snapshot and evidence hashes")
    fun theMoveIsPreviewedTwiceFromIdenticalEvidence() {
        previewResults = List(2) { index ->
            val result = observeProcess(
                "packaged-preview-${index + 1}",
                harness.runLauncher(moveClassArguments("--preview"), workspaceRoot),
                expectedExit = 0,
            )
            assertCleanCliStderr(result, "packaged preview ${index + 1}")
            assertWorkspaceStillBaseline("packaged preview ${index + 1}")
            assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
            result
        }
    }

    @Then("each result is a {string} with evidence {string} and managed-write eligibility {string}")
    fun eachResultIsAnEligibleSemanticPreview(resultType: String, evidence: String, eligibility: String) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals("JDT_BINDING", evidence)
        assertEquals("ELIGIBLE", eligibility)
        assertEquals(2, previewResults.size)
        previewResults.forEach { result ->
            val output = normalizeExternalOutput(result.stdout)
            assertTrue(output.startsWith("Operation: moveClass\nStatus: PREVIEW\n"))
            assertTrue("Evidence: JDT_BINDING\n" in output)
            assertTrue("Use --apply to apply this change.\n" in output)
            assertFalse("Refusal code:" in output)
            assertFalse("Post-edit diagnostics:" in output)
            assertEquals(EXPECTED_AFFECTED_PATHS, affectedPaths(output))
            assertEquals(EXPECTED_OBSERVER_SOURCE_SETS, observerSourceSets(output))
            assertTrue("JDT type binding selected 3 referencing file(s)" in output)
            assertTrue("BOUND_TARGET=12, BOUND_OTHER=1, UNRESOLVED=0" in output)
        }
        canonicalPreview = normalizeExternalOutput(previewResults.first().stdout)
        scenario.attach(
            assertNotNull(canonicalPreview),
            "text/plain",
            "packaged-cli-semantic-preview",
        )
    }

    @Then("the normalized edits, observer closure, evidence counts, and diagnostics are identical between the previews")
    fun repeatPreviewsHaveIdenticalNormalizedEvidence() {
        val first = normalizeExternalOutput(previewResults[0].stdout)
        val second = normalizeExternalOutput(previewResults[1].stdout)
        assertEquals(first, second, "Packaged preview output is not canonical")
        assertEquals(4, Regex("(?m)^--- a/").findAll(first).count())
        assertEquals(1, Regex("(?m)^rename ").findAll(first).count())
        assertEquals(5, affectedPaths(first).size)
        assertEquals(EXPECTED_OBSERVER_SOURCE_SETS, observerSourceSets(first))
        assertTrue("JAVA_NON_CODE_RESIDUAL=1; NON_JAVA_RESIDUAL=2" in first)
        assertFalse("Post-edit diagnostics:" in first)
        assertEquals(baselineWorkspaceManifest, captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true))
        assertEquals(templateManifestBefore, captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true))
        assertEquals(packageManifestBefore, captureTreeManifest(packageRoot, rejectUnsafeEntries = true))
        scenario.attach(
            "snapshot=${baselineScan.snapshot}; modules=20; sources=7; observers=3; " +
                "affectedPaths=5; forwardFileEdits=5; boundTarget=12; boundOther=1; unresolved=0; " +
                "diagnosticsBefore=0; diagnosticsStaged=0; canonicalPreviewSha256=${sha256(first.toByteArray())}",
            "text/plain",
            "packaged-preview-evidence-counts",
        )
    }

    @Then("exact staged diagnostics introduce no errors relative to authoritative before diagnostics")
    fun exactStagedDiagnosticsIntroduceNoErrors() {
        assertEquals("No diagnostics.\n", baselineDiagnostics)
        previewResults.forEach { result ->
            val output = normalizeExternalOutput(result.stdout)
            assertFalse("Post-edit diagnostics:" in output)
            assertFalse(Regex("(?m)^  \\[ERROR]").containsMatchIn(output))
        }
        assertEquals(normalizeExternalOutput(previewResults[0].stdout), normalizeExternalOutput(previewResults[1].stdout))
    }

    @Then("no prohibited build or network activity was attempted")
    fun noProhibitedBuildOrNetworkActivityWasAttempted() {
        val environment = harness.environmentAttestation
        val poisonMarkerObserved = harness.poisonMarkerWasObserved()
        assertFalse(poisonMarkerObserved)
        assertEquals(baselineWorkspaceManifest, captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true))
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        previewResults.forEach { result ->
            val combined = normalizeExternalOutput(result.stdout + operationalStderr(result))
            assertFalse("BUILD SUCCESS" in combined)
            assertFalse(Regex("(?i)\\bmvn(?:w)?(?:\\.cmd)?\\b").containsMatchIn(combined))
        }
        val windowsDirectTripwire = if (isWindows()) "invalid-java.exe-sentinel-installed" else "not-applicable"
        val windowsShellTripwires = if (isWindows()) "java.bat-and-java.cmd-marker-shims-installed" else "not-applicable"
        scenario.attach(
            "subjectEnvironment=allowlisted; locale=C; JAVA_HOME/JDK_JAVA_OPTIONS/CLASSPATH/MAVEN_OPTS/" +
                "GRADLE_OPTS/proxies=absent; userHome=isolated; temp=isolated; " +
                "poisonPathFirst=${environment.poisonPathFirst}; poisonMarkerObserved=$poisonMarkerObserved; " +
                "windowsDirectJavaLookupTripwire=$windowsDirectTripwire; " +
                "windowsShellJavaLookupTripwires=$windowsShellTripwires; " +
                "tripwireProofBoundary=invalid-java.exe-attempts-fail-without-marker-and-batch-cmd-attempts-write-marker; " +
                "windowsJavaToolOptionsBannerComparison=observed-and-expected-normalized-equally; " +
                "fixturePlugins=0; wrappers=0; settingsFiles=0; previewMutation=none",
            "text/plain",
            "packaged-prohibited-activity-attestation",
        )
    }

    @When("one approved preview is applied under the workspace lock")
    fun oneApprovedPreviewIsAppliedUnderTheWorkspaceLock() {
        val baseline = baselineWorkspaceManifest
        preApplyManifest = baseline
        val engineRoot = Files.createDirectory(workspaceRoot.resolve(ENGINE_DIRECTORY))
        val lockPath = engineRoot.resolve("workspace.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                lockContentionApplyInvocations += 1
                val refused = observeProcess(
                    "packaged-lock-contention-apply",
                    harness.runLauncher(moveClassArguments("--apply"), workspaceRoot),
                    expectedExit = 1,
                )
                val observation = observeLockRefusal(refused)
                lockRefusalObservation = observation
                assertFalse(observation.cliDiagnosticCodeProjected)
                assertEquals(
                    renderedPreviewPortion(assertNotNull(canonicalPreview), PREVIEW_TERMINAL),
                    observation.normalizedStdout,
                )
                assertEquals(baseline, captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true).withoutEngineMetadata())
                assertFalse(Files.exists(engineRoot.resolve("transactions"), LinkOption.NOFOLLOW_LINKS))
                assertEquals(setOf("$ENGINE_DIRECTORY/workspace.lock"), engineMetadataFiles())
            }
        }
        Files.delete(lockPath)
        Files.delete(engineRoot)
        assertEquals(baseline, captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true))
        val restoredScan = runScan("post-lock-probe-scan")
        assertEquals(baselineScan, restoredScan)
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))

        successfulApplyInvocations += 1
        val applied = observeProcess(
            "packaged-successful-apply",
            harness.runLauncher(moveClassArguments("--apply"), workspaceRoot),
            expectedExit = 0,
        )
        assertCleanCliStderr(applied, "packaged apply")
        applyResult = applied
        val applyOutput = normalizeExternalOutput(applied.stdout)
        val previewPortion = renderedPreviewPortion(assertNotNull(canonicalPreview), PREVIEW_TERMINAL)
        assertEquals(previewPortion, renderedPreviewPortion(applyOutput, APPLY_TERMINAL))
        val ids = TRANSACTION_ID.findAll(applyOutput).map(MatchResult::value).toSet()
        assertEquals(1, ids.size, "Apply output must expose one transaction identity")
        transactionId = ids.single()
        assertEquals(1, Regex("(?m)^Applied\\. Transaction: ").findAll(applyOutput).count())
        assertTrue("To rollback: refactorkit patch rollback ${assertNotNull(transactionId)}" in applyOutput)
        postApplyManifest = captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true).withoutEngineMetadata()
        postApplyScan = runScan("post-apply-scan")
        postApplyDiagnostics = runDiagnostics("post-apply-diagnostics")
        assertFalse(harness.poisonMarkerWasObserved())
        val refusal = assertNotNull(lockRefusalObservation)
        scenario.attach(
            refusal.operationalStderr,
            "text/plain",
            "packaged-lock-refusal-operational-stderr",
        )
        scenario.attach(
            "lockProbeApplyInvocations=$lockContentionApplyInvocations; lockRefusalObservation=exact-two-line-stderr; " +
                "lockRefusalOperationalStderrSha256=${sha256(refusal.operationalStderr.toByteArray())}; " +
                "cliDiagnosticCodeProjected=${refusal.cliDiagnosticCodeProjected}; " +
                "coreDiagnosticCodeCorrelation=not-performed; probeJournalRecords=0; " +
                "successfulApplyInvocations=$successfulApplyInvocations; applyReplannedInChild=true; " +
                "crossProcessPlanIdClaim=false; renderedPreviewPortionEqual=true",
            "text/plain",
            "packaged-lock-and-apply-attestation",
        )
    }

    @Then("its exact staged post-image is committed in one managed transaction")
    fun theExactStagedPostImageIsCommittedOnce() {
        assertEquals(1, lockContentionApplyInvocations)
        assertEquals(1, successfulApplyInvocations)
        val before = assertNotNull(preApplyManifest)
        val after = assertNotNull(postApplyManifest)
        val mutation = fileMutation(before, after)
        assertEquals(setOf(PRODUCT_SOURCE_PATH), mutation.removed)
        assertEquals(setOf(PRODUCT_TARGET_PATH), mutation.added)
        assertEquals(EXPECTED_OBSERVER_PATHS, mutation.changed)
        assertEquals(EXPECTED_AFFECTED_PATHS, mutation.allPaths)
        assertProtectedInputsUnchanged(before, after)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_SOURCE_PATH), LinkOption.NOFOLLOW_LINKS))

        val log = transactionLog()
        assertFalse(log.hasOrphanedTempsReadOnly())
        val records = log.listRecordsReadOnly()
        assertEquals(1, records.size)
        val record = records.single()
        appliedRecord = record
        assertEquals(assertNotNull(transactionId), record.transaction.id.value)
        assertEquals(8, record.schemaVersion)
        assertEquals("moveClass", record.operation)
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals("cli", record.transaction.approval.surface)
        assertEquals("caller", record.transaction.approval.actor)
        assertEquals(baselineScan.snapshot, record.transaction.snapshotHashBefore)
        assertEquals(baselineScan.snapshot, record.preSnapshotHash)
        assertEquals(assertNotNull(postApplyScan).snapshot, record.postSnapshotHash)
        assertEquals(JournalState.APPLIED, record.state)
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED),
            record.history.map { it.state },
        )
        assertEquals(5, record.forwardEdit.edits.size)
        val modifies = record.forwardEdit.edits.filterIsInstance<FileEdit.Modify>()
        val rename = record.forwardEdit.edits.filterIsInstance<FileEdit.Rename>().single()
        assertEquals(EXPECTED_MANAGED_MODIFY_PATHS, modifies.mapTo(linkedSetOf()) { normalizedPath(it.path) })
        assertEquals(
            mapOf(
                PRODUCT_SOURCE_PATH to 1,
                PRODUCT_STEPS_PATH to 2,
                PRICING_SOURCE_PATH to 1,
                STOREFRONT_SOURCE_PATH to 1,
            ),
            modifies.associate { normalizedPath(it.path) to it.textEdits.size },
        )
        assertEquals(PRODUCT_SOURCE_PATH, normalizedPath(rename.path))
        assertEquals(PRODUCT_TARGET_PATH, normalizedPath(rename.newPath))
        assertTrue(record.forwardEdit.edits.none { it is FileEdit.Create || it is FileEdit.Delete })
        assertJournalDirectoryHasOneRecordOnly(log)
    }

    @Then("authoritative after diagnostics attest the committed snapshot with no introduced errors")
    fun authoritativeAfterDiagnosticsAttestTheCommittedSnapshot() {
        val scan = assertNotNull(postApplyScan)
        assertEquals(20, scan.modules)
        assertEquals(7, scan.files)
        assertNotEquals(baselineScan.snapshot, scan.snapshot)
        assertEquals("No diagnostics.\n", assertNotNull(postApplyDiagnostics))
        assertEquals(baselineDiagnostics, postApplyDiagnostics)
        assertEquals(scan.snapshot, assertNotNull(appliedRecord).postSnapshotHash)
        assertProtectedInputsUnchanged(baselineWorkspaceManifest, assertNotNull(postApplyManifest))
        assertEquals(packageManifestBefore, captureTreeManifest(packageRoot, rejectUnsafeEntries = true))
        assertEquals(templateManifestBefore, captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true))
        scenario.attach(
            "postApplySnapshot=${scan.snapshot}; modules=20; sources=7; diagnostics=0; " +
                "expectedMutatedPaths=5; protectedPomsJarsManifestsGeneratedAndResidualInputs=unchanged; " +
                "journalSchema=8; journalState=APPLIED; journalRecords=1",
            "text/plain",
            "packaged-committed-post-image",
        )
    }

    @When("that transaction is rolled back")
    fun thatTransactionIsRolledBackNormally() {
        val id = assertNotNull(transactionId)
        val result = observeProcess(
            "packaged-normal-rollback",
            harness.runLauncher(listOf("patch", "rollback", id, "--root", workspaceRoot.toString()), workspaceRoot),
            expectedExit = 0,
        )
        assertCleanCliStderr(result, "packaged rollback")
        assertEquals("Rolled back transaction $id.\n", normalizeExternalOutput(result.stdout))
        rollbackResult = result
        rollbackManifest = captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true).withoutEngineMetadata()
        rollbackScan = runScan("rollback-scan")
        rollbackDiagnostics = runDiagnostics("rollback-diagnostics")
    }

    @Then("every file byte, path, inventory entry, and snapshot hash equals the pre-apply image")
    fun everyWorkspaceIdentityDimensionEqualsThePreApplyImage() {
        assertNotNull(rollbackResult)
        assertEquals(baselineWorkspaceManifest, assertNotNull(rollbackManifest))
        assertEquals(baselineScan, assertNotNull(rollbackScan))
        assertEquals(7, assertNotNull(rollbackScan).files)
        assertEquals(7, scannerJavaInventory(assertNotNull(rollbackManifest)).size)
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(PRODUCT_SOURCE_PATH), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        assertProtectedInputsUnchanged(baselineWorkspaceManifest, assertNotNull(rollbackManifest))

        val log = transactionLog()
        assertFalse(log.hasOrphanedTempsReadOnly())
        val records = log.listRecordsReadOnly()
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals(assertNotNull(transactionId), record.transaction.id.value)
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
        assertJournalDirectoryHasOneRecordOnly(log)
    }

    @Then("authoritative rollback diagnostics attest the restored snapshot")
    fun authoritativeRollbackDiagnosticsAttestTheRestoredSnapshot() {
        assertEquals(baselineDiagnostics, assertNotNull(rollbackDiagnostics))
        assertEquals("No diagnostics.\n", rollbackDiagnostics)
        assertEquals(baselineScan.snapshot, assertNotNull(rollbackScan).snapshot)
        assertFalse(harness.poisonMarkerWasObserved())
        assertEquals(templateManifestBefore, captureTreeManifest(fixtureTemplate, rejectUnsafeEntries = true))
        assertEquals(packageManifestBefore, captureTreeManifest(packageRoot, rejectUnsafeEntries = true))
        scenario.attach(
            "transaction=${assertNotNull(transactionId)}; state=ROLLED_BACK; history=" +
                "PREPARED->APPLYING->APPLIED->ROLLING_BACK->ROLLED_BACK; records=1; orphanTemps=0; " +
                "quarantine=absent; restoredSnapshot=${baselineScan.snapshot}; sources=7; diagnostics=0; " +
                "nonEngineManifestSha256=${baselineWorkspaceManifest.sha256}",
            "text/plain",
            "packaged-rollback-identity",
        )
    }

    private fun assertPackageAndRuntimeIdentity() {
        val packageReal = packageRoot.toRealPath()
        assertTrue(packageReal.startsWith(repositoryRoot.toRealPath()), "Packaged root is outside the supplied repository")
        assertTrue(Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS), "Real package launcher is missing")
        assertTrue(Files.isRegularFile(embeddedJava, LinkOption.NOFOLLOW_LINKS), "Embedded runtime java is missing")
        assertFalse(Files.isSymbolicLink(launcher))
        assertFalse(Files.isSymbolicLink(embeddedJava))
        val launcherText = Files.readString(launcher)
        if (isWindows()) {
            assertTrue("%APP_HOME%\\runtime\\bin\\java.exe" in launcherText)
        } else {
            assertTrue("\$APP_HOME/runtime/bin/java" in launcherText)
        }
        assertEquals(packageRoot.resolve("runtime/bin/${embeddedJava.fileName}").toRealPath(), embeddedJava.toRealPath())
        assertEquals(packageManifestBefore, captureTreeManifest(packageRoot, rejectUnsafeEntries = true))
    }

    private fun runScan(label: String): ScanObservation {
        val result = observeProcess(
            label,
            harness.runLauncher(listOf("scan", workspaceRoot.toString()), workspaceRoot),
            expectedExit = 0,
        )
        assertCleanCliStderr(result, label)
        val output = normalizeExternalOutput(result.stdout)
        val project = output.lineSequence().single { it.startsWith("Project") }.substringAfter(':').trim()
        assertEquals(workspaceRoot.toRealPath(), Path.of(project).toRealPath())
        return ScanObservation(
            files = numericOutputField(output, "Files"),
            modules = numericOutputField(output, "Modules"),
            snapshot = output.lineSequence().single { it.startsWith("Snapshot") }.substringAfter(':').trim(),
            canonicalOutput = output,
        )
    }

    private fun runDiagnostics(label: String): String {
        val result = observeProcess(
            label,
            harness.runLauncher(listOf("diagnostics", workspaceRoot.toString()), workspaceRoot),
            expectedExit = 0,
        )
        assertCleanCliStderr(result, label)
        return normalizeExternalOutput(result.stdout)
    }

    private fun observeProcess(label: String, result: BoundedProcessResult, expectedExit: Int): BoundedProcessResult {
        assertFalse(result.timedOut, "$label exceeded the 180-second timeout")
        assertFalse(result.stdoutTruncated, "$label exceeded the stdout cap")
        assertFalse(result.stderrTruncated, "$label exceeded the stderr cap")
        assertTrue(result.stdoutBytesObserved <= PackagedProcessHarness.OUTPUT_LIMIT_BYTES.toLong())
        assertTrue(result.stderrBytesObserved <= PackagedProcessHarness.OUTPUT_LIMIT_BYTES.toLong())
        assertEquals(expectedExit, result.exitCode, "$label returned an unexpected exit code")
        assertFalse(harness.poisonMarkerWasObserved(), "$label observed a poison java marker")
        processEvidence += ProcessEvidence(
            label,
            assertNotNull(result.exitCode),
            result.stdoutBytesObserved,
            result.stderrBytesObserved,
            result.durationMillis,
            result.descendantsTerminated,
        )
        return result
    }

    private fun assertCleanCliStderr(result: BoundedProcessResult, label: String) {
        assertEquals("", operationalStderr(result), "$label emitted operational stderr")
    }

    private fun operationalStderr(result: BoundedProcessResult): String {
        val normalizedStderr = normalizeExternalOutput(result.stderr)
        val normalizedBanner = normalizeExternalOutput(
            "Picked up JAVA_TOOL_OPTIONS: ${harness.controlledJavaToolOptions}\n",
        )
        assertTrue(
            normalizedStderr.startsWith(normalizedBanner),
            "Embedded Java did not report the controlled user-home option",
        )
        return normalizedStderr.removePrefix(normalizedBanner)
    }

    private fun observeLockRefusal(result: BoundedProcessResult): LockRefusalObservation {
        val normalizedStdout = normalizeExternalOutput(result.stdout)
        val normalizedStderr = normalizeExternalOutput(result.stderr)
        val operationalStderr = operationalStderr(result)
        assertEquals(EXPECTED_LOCK_REFUSAL_OPERATIONAL_STDERR, operationalStderr)
        val stdoutProjectsCode = UNPROJECTED_CORE_DIAGNOSTIC_CODE in normalizedStdout
        val stderrProjectsCode = UNPROJECTED_CORE_DIAGNOSTIC_CODE in normalizedStderr
        assertFalse(stdoutProjectsCode, "Packaged stdout projected a core diagnostic code")
        assertFalse(stderrProjectsCode, "Packaged stderr projected a core diagnostic code")
        return LockRefusalObservation(
            normalizedStdout = normalizedStdout,
            operationalStderr = operationalStderr,
            cliDiagnosticCodeProjected = stdoutProjectsCode || stderrProjectsCode,
        )
    }

    private fun assertWindowsBannerNormalizationContract() {
        val simulatedOptions = "-Duser.home=C:\\refactorkit\\subject-home " +
            "-Djava.io.tmpdir=C:\\refactorkit\\subject-tmp"
        val observedWindowsBanner = "Picked up JAVA_TOOL_OPTIONS: $simulatedOptions\r\n"
        val expectedWindowsBanner = "Picked up JAVA_TOOL_OPTIONS: $simulatedOptions\n"
        assertEquals(
            normalizeExternalOutput(expectedWindowsBanner, '\\'),
            normalizeExternalOutput(observedWindowsBanner, '\\'),
            "Expected and observed Windows JAVA_TOOL_OPTIONS banners must use identical normalization",
        )
    }

    private fun settingsProperty(output: String, name: String): Path {
        val normalized = normalizeExternalOutput(output)
        val value = normalized.lineSequence()
            .map(String::trim)
            .single { it.startsWith("$name = ") }
            .substringAfter(" = ")
        return Path.of(value)
    }

    private fun moveClassArguments(mode: String): List<String> = listOf(
        "move-class",
        "--symbol",
        PRODUCT_FQN,
        "--to-package",
        PRODUCT_TARGET_PACKAGE,
        workspaceRoot.toString(),
        mode,
    )

    private fun affectedPaths(output: String): Set<String> {
        val block = output.substringAfter("Affected files:\n").substringBefore("\n\nPatch:")
        return block.lineSequence().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }.toSet()
    }

    private fun observerSourceSets(output: String): Set<String> {
        val line = output.lineSequence().single {
            it.startsWith("- Authoritative dependency-bounded reverse-observer closure:")
        }
        return line.substringAfter("; observers ").removeSuffix(".").split(", ").toSet()
    }

    private fun renderedPreviewPortion(output: String, terminal: String): String {
        val normalized = normalizeExternalOutput(output)
        val marker = normalized.indexOf(terminal)
        assertTrue(marker >= 0, "Packaged output is missing its terminal line")
        return normalized.substring(0, marker)
    }

    private fun assertWorkspaceStillBaseline(observation: String) {
        assertEquals(
            baselineWorkspaceManifest,
            captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true),
            "$observation changed workspace bytes or path kinds",
        )
        assertFalse(Files.exists(workspaceRoot.resolve(ENGINE_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        assertFalse(harness.poisonMarkerWasObserved())
    }

    private fun scannerJavaInventory(manifest: TreeManifest): Set<String> = manifest.filePaths.filterTo(linkedSetOf()) { path ->
        path.endsWith(".java") && (
            "/src/main/java/" in path ||
                "/src/test/java/" in path ||
                path.startsWith(GENERATED_ROOT_PREFIX)
            )
    }

    private fun assertProtectedInputsUnchanged(before: TreeManifest, after: TreeManifest) {
        val protected = before.filePaths.filterTo(linkedSetOf()) { path ->
            path == "pom.xml" ||
                path.endsWith("/pom.xml") ||
                path.endsWith(".jar") ||
                path in LEXICAL_BOUNDARY_PATHS ||
                path == EXPECTED_SOURCE_INVENTORY_PATH ||
                path == GENERATED_INVENTORY_PATH ||
                path.startsWith(GENERATED_ROOT_PREFIX)
        }
        assertTrue(protected.isNotEmpty())
        protected.forEach { path -> assertEquals(before.entry(path), after.entry(path), "$path changed") }
    }

    private fun transactionLog(): TransactionLog =
        TransactionLog(workspaceRoot.resolve("$ENGINE_DIRECTORY/transactions"))

    private fun assertJournalDirectoryHasOneRecordOnly(log: TransactionLog) {
        assertFalse(log.hasOrphanedTempsReadOnly())
        val children = Files.list(log.logDir).use { stream -> stream.toList() }
        assertEquals(1, children.size, "Transaction directory contains temp, quarantine, or second records")
        val recordFile = children.single()
        assertTrue(Files.isRegularFile(recordFile, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(recordFile))
        assertEquals("${assertNotNull(transactionId)}.json", recordFile.fileName.toString())
        assertFalse(Files.exists(log.logDir.resolve(".quarantine"), LinkOption.NOFOLLOW_LINKS))
    }

    private fun engineMetadataFiles(): Set<String> = captureTreeManifest(workspaceRoot, rejectUnsafeEntries = true)
        .filePaths.filterTo(linkedSetOf()) { it == ENGINE_DIRECTORY || it.startsWith("$ENGINE_DIRECTORY/") }

    private fun assertPomDependency(module: String, artifactId: String, expectedScope: String?) {
        val document = parseXml(workspaceRoot.resolve(module).resolve("pom.xml"))
        val dependencies = elements(document, "dependency")
        val matching = dependencies.singleOrNull { childText(it, "artifactId") == artifactId }
        assertNotNull(matching, "$module does not depend on $artifactId")
        val scope = childText(matching, "scope")
        if (expectedScope == null) {
            assertTrue(scope == null || scope == "compile")
        } else {
            assertEquals(expectedScope, scope)
        }
    }

    private fun allPomDocuments(): List<Document> = baselineWorkspaceManifest.filePaths
        .filter { it == "pom.xml" || it.endsWith("/pom.xml") }
        .map { parseXml(workspaceRoot.resolve(it)) }

    private fun parseXml(path: Path): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        return Files.newInputStream(path).use { input -> factory.newDocumentBuilder().parse(input) }
    }

    private fun elements(document: Document, localName: String): List<Element> =
        (0 until document.getElementsByTagNameNS("*", localName).length).map { index ->
            document.getElementsByTagNameNS("*", localName).item(index) as Element
        }

    private fun firstElementText(document: Document, localName: String): String? =
        elements(document, localName).firstOrNull()?.textContent?.trim()

    private fun childText(element: Element, localName: String): String? =
        (0 until element.childNodes.length)
            .map(element.childNodes::item)
            .filterIsInstance<Element>()
            .firstOrNull { it.localName == localName || it.nodeName == localName }
            ?.textContent
            ?.trim()

    private fun numericOutputField(output: String, field: String): Int = output.lineSequence()
        .single { it.substringBefore(':').trim() == field }
        .substringAfter(':')
        .trim()
        .toInt()

    private fun requiredDirectoryProperty(name: String): Path {
        val raw = System.getProperty(name)?.takeIf(String::isNotBlank)
            ?: error("Required lazy Gradle system property is missing: $name")
        val path = Path.of(raw).toAbsolutePath().normalize()
        assertTrue(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS), "$name does not name a directory")
        assertFalse(Files.isSymbolicLink(path), "$name must not name a symbolic link")
        return path
    }

    private fun installPoisonJavaTripwires(directory: Path) {
        if (isWindows()) {
            Files.writeString(
                directory.resolve("java.exe"),
                PackagedProcessHarness.INVALID_WINDOWS_JAVA_EXE_SENTINEL,
                StandardOpenOption.CREATE_NEW,
            )
            val content = "@echo off\r\n>\"%~dp0$POISON_MARKER_NAME\" echo invoked\r\nexit /b 97\r\n"
            Files.writeString(directory.resolve("java.bat"), content, StandardOpenOption.CREATE_NEW)
            Files.writeString(directory.resolve("java.cmd"), content, StandardOpenOption.CREATE_NEW)
        } else {
            val shim = directory.resolve("java")
            Files.writeString(
                shim,
                "#!/bin/sh\nprintf invoked > \"\${0%/*}/$POISON_MARKER_NAME\"\nexit 97\n",
                StandardOpenOption.CREATE_NEW,
            )
            assertTrue(shim.toFile().setExecutable(true, true), "Cannot make poison java executable")
        }
    }

    private fun copyTreeNoFollow(source: Path, target: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                assertTrue(attributes.isDirectory && !attributes.isSymbolicLink, "Fixture contains an unsafe directory")
                val destination = target.resolve(source.relativize(directory).toString())
                Files.createDirectory(destination)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                assertTrue(attributes.isRegularFile && !attributes.isSymbolicLink, "Fixture contains a link or special file")
                val destination = target.resolve(source.relativize(file).toString())
                Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult = throw failure
        })
    }

    private fun captureTreeManifest(root: Path, rejectUnsafeEntries: Boolean): TreeManifest {
        assertTrue(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS), "Manifest root is not a directory")
        assertFalse(Files.isSymbolicLink(root), "Manifest root is a symbolic link")
        val entries = linkedMapOf<String, ManifestEntry>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (rejectUnsafeEntries) {
                    assertTrue(attributes.isDirectory && !attributes.isSymbolicLink, "Manifest contains an unsafe directory")
                }
                if (directory != root) {
                    entries[relativePath(root, directory)] = ManifestEntry("directory", 0, null)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (rejectUnsafeEntries) {
                    assertTrue(attributes.isRegularFile && !attributes.isSymbolicLink, "Manifest contains a link or special file")
                }
                val relative = relativePath(root, file)
                entries[relative] = if (attributes.isRegularFile && !attributes.isSymbolicLink) {
                    ManifestEntry("file", attributes.size(), sha256(file))
                } else if (attributes.isSymbolicLink) {
                    ManifestEntry("symlink", 0, Files.readSymbolicLink(file).toString())
                } else {
                    ManifestEntry("special", attributes.size(), null)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult = throw failure
        })
        return TreeManifest(entries.toSortedMap())
    }

    private fun deleteTreeNoFollowOnce(root: Path): Int {
        deleteTreeNoFollow(root)
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Bounded no-follow cleanup did not remove the temporary tree")
        }
        return 1
    }

    private fun deleteTreeNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult = throw failure

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                Files.delete(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun fileMutation(before: TreeManifest, after: TreeManifest): FileMutation {
        val beforeFiles = before.filePaths
        val afterFiles = after.filePaths
        val removed = beforeFiles - afterFiles
        val added = afterFiles - beforeFiles
        val changed = beforeFiles.intersect(afterFiles).filterTo(linkedSetOf()) { before.entry(it) != after.entry(it) }
        return FileMutation(removed, added, changed)
    }

    private fun normalizeExternalOutput(
        value: String,
        fileSeparator: Char = java.io.File.separatorChar,
    ): String {
        val lineNormalized = value.replace("\r\n", "\n").replace('\r', '\n')
        return if (fileSeparator == '/') lineNormalized else lineNormalized.replace(fileSeparator, '/')
    }

    private fun normalizedPath(path: Path): String = path.normalize().invariantSeparatorsPathString

    private fun relativePath(root: Path, path: Path): String = root.relativize(path).invariantSeparatorsPathString

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            DigestInputStream(input, digest).use { stream ->
                val buffer = ByteArray(8192)
                while (stream.read(buffer) >= 0) {
                    // DigestInputStream updates the digest.
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private data class ScanObservation(
        val files: Int,
        val modules: Int,
        val snapshot: String,
        val canonicalOutput: String,
    )

    private data class ReferenceObservation(
        val paths: List<String>,
        val observerPaths: Set<String>,
    )

    private data class LockRefusalObservation(
        val normalizedStdout: String,
        val operationalStderr: String,
        val cliDiagnosticCodeProjected: Boolean,
    )

    private data class ProcessEvidence(
        val label: String,
        val exitCode: Int,
        val stdoutBytes: Long,
        val stderrBytes: Long,
        val durationMillis: Long,
        val descendantsTerminated: Int,
    )

    private data class ManifestEntry(
        val kind: String,
        val size: Long,
        val sha256: String?,
    )

    private data class TreeManifest(
        val entries: Map<String, ManifestEntry>,
    ) {
        val filePaths: Set<String> get() = entries.filterValues { it.kind == "file" }.keys
        val sha256: String get() {
            val canonical = entries.entries.joinToString("\n", postfix = "\n") { (path, entry) ->
                "$path\t${entry.kind}\t${entry.size}\t${entry.sha256.orEmpty()}"
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }

        fun entry(path: String): ManifestEntry? = entries[path]

        fun fileSha256(path: String): String = assertNotNull(entries[path]?.sha256, "Manifest file is missing: $path")

        fun withoutEngineMetadata(): TreeManifest = TreeManifest(
            entries.filterKeys { it != ENGINE_DIRECTORY && !it.startsWith("$ENGINE_DIRECTORY/") },
        )
    }

    private data class FileMutation(
        val removed: Set<String>,
        val added: Set<String>,
        val changed: Set<String>,
    ) {
        val allPaths: Set<String> get() = removed + added + changed
    }

    private companion object {
        const val REPOSITORY_ROOT_PROPERTY = "refactorkit.repository.root"
        const val PACKAGE_ROOT_PROPERTY = "refactorkit.packaged.root"
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val ENGINE_DIRECTORY = ".refactorkit"
        const val POISON_MARKER_NAME = "poison-java-invoked.marker"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val PRODUCT_TARGET_PACKAGE = "com.acme.catalog.api"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        const val PRICING_SOURCE_PATH = "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java"
        const val STOREFRONT_SOURCE_PATH = "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java"
        const val PRODUCT_STEPS_PATH =
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
        const val GENERATED_ROOT_PREFIX = "catalog-generated-support/target/generated-sources/catalog-metadata/"
        const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        const val GENERATED_INVENTORY_PATH =
            "catalog-generated-support/.refactorkit-generated-root-inventory.properties"
        const val EXPECTED_SOURCE_INVENTORY_PATH =
            "catalog-acceptance/.refactorkit-expected-source-inventory.properties"
        const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val EXTERNAL_ARTIFACT_SHA256 =
            "7f2e71601326da5129cb90435fb5442b958137f05fd28e4fec5192227268a3a2"
        const val PREVIEW_TERMINAL = "Use --apply to apply this change."
        const val APPLY_TERMINAL = "Applied. Transaction:"
        const val UNPROJECTED_CORE_DIAGNOSTIC_CODE = "workspace.locked"
        const val EXPECTED_LOCK_REFUSAL_OPERATIONAL_STDERR =
            "Apply refused:\n  ERROR: Workspace is locked by another RefactorKit writer\n"

        val EXPECTED_OBSERVER_SOURCE_SETS = setOf(
            "catalog-pricing:main",
            "catalog-storefront:main",
            "catalog-acceptance:test",
        )
        val EXPECTED_OBSERVER_PATHS = setOf(PRICING_SOURCE_PATH, STOREFRONT_SOURCE_PATH, PRODUCT_STEPS_PATH)
        val EXPECTED_REFERENCE_PATHS = EXPECTED_OBSERVER_PATHS + PRODUCT_SOURCE_PATH
        val EXPECTED_MANAGED_MODIFY_PATHS = linkedSetOf(
            PRODUCT_SOURCE_PATH,
            PRODUCT_STEPS_PATH,
            PRICING_SOURCE_PATH,
            STOREFRONT_SOURCE_PATH,
        )
        val EXPECTED_AFFECTED_PATHS = EXPECTED_MANAGED_MODIFY_PATHS + PRODUCT_TARGET_PATH
        val LEXICAL_BOUNDARY_PATHS = setOf(
            "catalog-decoy/src/main/java/com/acme/decoy/Product.java",
            "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java",
            "catalog-acceptance/src/test/resources/features/product-lifecycle.feature",
            "README.md",
        )
        val FORBIDDEN_POM_ELEMENTS = setOf(
            "build",
            "plugins",
            "plugin",
            "pluginRepositories",
            "repositories",
            "annotationProcessorPaths",
        )
        val PRODUCT_DECLARATION = Regex("""\b(?:class|interface|enum|record)\s+Product\b""")
        val SHA256 = Regex("[a-f0-9]{64}")
        val TRANSACTION_ID = Regex(
            "transaction-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        )
    }
}

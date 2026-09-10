package org.refactorkit.cli.packagedmavenmodulerename

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions", "LargeClass")
class PackagedJavaMavenModuleRenameQualificationSteps {
    private lateinit var scenario: Scenario
    private lateinit var surface: Surface
    private lateinit var repositoryRoot: Path
    private lateinit var archive: Path
    private lateinit var checksumSidecar: Path
    private lateinit var candidateRoot: Path
    private lateinit var logsDirectory: Path
    private lateinit var manifestsDirectory: Path
    private lateinit var failureDiagnosticsDirectory: Path
    private lateinit var fixtureRoot: Path
    private lateinit var caseContainer: Path
    private lateinit var workspaceRoot: Path
    private lateinit var sandbox: SubjectSandbox
    private lateinit var host: HostIdentity
    private lateinit var repositoryIdentity: RepositoryIdentity
    private lateinit var candidateArchiveIdentity: CandidateArchiveIdentity
    private lateinit var buildJdkIdentity: BuildJdkIdentity
    private lateinit var candidateRuntimeIdentity: CandidateRuntimeIdentity
    private lateinit var candidateArchiveProbeEvidence: CandidateArchiveIdentityProbeEvidence
    private lateinit var processMonitorProbeEvidence: ProcessObservationMonitorProbeEvidence
    private lateinit var fixtureM0: TreeManifest
    private lateinit var candidateBefore: TreeManifest
    private lateinit var candidateReadOnlyEvidence: CandidateReadOnlyEvidence
    private lateinit var gateSelectorEvidence: PackagedGateSelectorEvidence
    private lateinit var copyInitialManifest: TreeManifest
    private lateinit var workspaceOracle: WorkspaceOracleState
    private lateinit var launcher: Path
    private lateinit var embeddedJava: Path
    private lateinit var launcherArchiveEvidence: ArchiveEntryEvidence
    private lateinit var embeddedJavaArchiveEvidence: ArchiveEntryEvidence
    private lateinit var launcherExtractedMode: String
    private lateinit var copyIdentity: String
    private lateinit var interactionSpec: InteractionSpec
    private lateinit var processHarness: PackagedSubjectProcessHarness
    private var persistentProcess: PersistentNdjsonProcess? = null
    private var previewResult: BoundedProcessResult? = null
    private var applyResult: BoundedProcessResult? = null
    private var firstDiagnosticsResult: BoundedProcessResult? = null
    private var rollbackResult: BoundedProcessResult? = null
    private var restoredDiagnosticsResult: BoundedProcessResult? = null
    private var projectOpenResponse: JsonObject? = null
    private var previewResponse: JsonObject? = null
    private var applyResponse: JsonObject? = null
    private var diagnosticsResponse: JsonElement? = null
    private var rollbackResponse: JsonObject? = null
    private var restoredDiagnosticsResponse: JsonElement? = null
    private var mcpInitializeResponse: JsonObject? = null
    private var mcpTools: Set<String> = emptySet()
    private var mcpPreviewText: String? = null
    private var mcpApplyText: String? = null
    private var mcpRollbackText: String? = null
    private var planId: String? = null
    private var transactionId: String? = null
    private var productS0: String? = null
    private var productS1: String? = null
    private var appliedJournal: JsonObject? = null
    private var rolledBackJournal: JsonObject? = null
    private var startFailure: SubjectStartRefused? = null
    private var requestedOperation: String? = null
    private var publicCanonicalOperationObserved: String? = null
    private var normalizedForwardEditSha256: String? = null
    private var gateIdentityVerified = false
    private var s1ObservedBeforeJournal = false
    private var journalInspectionStarted = false
    private var launcherStartAttempted = false
    private var launcherStarted = false
    private var previewVerified = false
    private var applyVerified = false
    private var diagnosticsVerified = false
    private var rollbackVerified = false
    private var processContractVerified = false
    private var cleanupVerified = false
    private var reportsEmitted = false
    private var failureDiagnostic: String? = null
    private var requestSequence = 0
    private var absoluteDeadlineNanos = 0L
    private val cleanupProblems = mutableListOf<String>()

    @Before(order = 0)
    fun establishScenarioContext(scenario: Scenario) {
        this.scenario = scenario
        surface = Surface.fromScenarioName(scenario.name)
        repositoryRoot = requiredPropertyPath("refactorkit.packaged.module.rename.repository.root")
        archive = requiredPropertyPath("refactorkit.packaged.module.rename.candidate.archive")
        checksumSidecar = requiredPropertyPath("refactorkit.packaged.module.rename.candidate.checksum")
        candidateRoot = requiredPropertyPath("refactorkit.packaged.module.rename.candidate.root")
        logsDirectory = requiredPropertyPath("refactorkit.packaged.module.rename.reports.logs")
        manifestsDirectory = requiredPropertyPath("refactorkit.packaged.module.rename.reports.manifests")
        failureDiagnosticsDirectory = requiredPropertyPath(
            "refactorkit.packaged.module.rename.reports.failureDiagnostics",
        )
        fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        require(fixtureRoot.startsWith(repositoryRoot))
        caseContainer = Files.createTempDirectory(
            qualificationTemporaryRoot(),
            "refactorkit-packaged-${surface.slug}-",
        )
        require(!caseContainer.startsWith(repositoryRoot)) {
            "Disposable qualification roots must stay outside the source/build workspace"
        }
        absoluteDeadlineNanos = System.nanoTime() + SCENARIO_TIMEOUT.toNanos()
    }

    @After(order = 100)
    fun preserveEvidenceAndCleanUp(completedScenario: Scenario) {
        runCatching {
            persistentProcess?.let {
                processHarness.finishPersistent()
                persistentProcess = null
            }
        }.onFailure { cleanupProblems += "process-close: ${it.message}" }
        runCatching {
            if (::fixtureM0.isInitialized) assertEquals(fixtureM0, captureTreeManifest(fixtureRoot))
            if (::candidateBefore.isInitialized) assertEquals(candidateBefore, captureTreeManifest(candidateRoot))
            if (Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) && ::candidateBefore.isInitialized) {
                assertEquals(boundArchiveSha256(), sha256(archive))
            }
        }.onFailure { cleanupProblems += "permanent-integrity: ${it.message}" }
        runCatching { emitArtifacts(if (completedScenario.isFailed) "FAILED" else "PASSED") }
            .onFailure { cleanupProblems += "evidence-before-cleanup: ${it.message}" }
        runCatching {
            if (::sandbox.isInitialized) sandbox.cleanup()
            if (::workspaceRoot.isInitialized) deleteTreeNoFollow(workspaceRoot)
            if (Files.exists(caseContainer, LinkOption.NOFOLLOW_LINKS)) deleteTreeNoFollow(caseContainer)
            cleanupVerified = Files.notExists(caseContainer, LinkOption.NOFOLLOW_LINKS)
            check(cleanupVerified) { "Case container survived no-follow cleanup" }
        }.onFailure { cleanupProblems += "no-follow-cleanup: ${it.message}" }
        runCatching { emitArtifacts(if (completedScenario.isFailed) "FAILED" else "PASSED") }
            .onFailure { cleanupProblems += "evidence-after-cleanup: ${it.message}" }

        if (cleanupProblems.isNotEmpty() && !completedScenario.isFailed) {
            throw AssertionError(cleanupProblems.joinToString(" | "))
        }
    }

    // 1
    @Given("immutable baseline {string} has SHA-256 {string}")
    fun immutableBaselineHasExpectedHash(relativePath: String, expectedHash: String) {
        assertEquals(QualificationOracle.baselineSha256, expectedHash)
        val path = resolveRepositoryFile(relativePath)
        assertEquals(expectedHash, sha256(path))
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains("Initial status: `@absent`"))
    }

    // 2
    @Given("approved change {string} has SHA-256 {string} and replaces the external-reactor and omitted-artifact clauses")
    fun approvedChangeHasExpectedHash(relativePath: String, expectedHash: String) {
        assertEquals(QualificationOracle.approvedChangeSha256, expectedHash)
        val path = resolveRepositoryFile(relativePath)
        assertEquals(expectedHash, sha256(path))
        val text = Files.readString(path, StandardCharsets.UTF_8)
        assertTrue(text.contains("AC-PACKAGED-006 and AC-PACKAGED-007 are replaced"))
        assertTrue(text.contains("newArtifactId=catalog-domain"))
        assertTrue(text.contains("No external repository"))
    }

    // 3
    @Given("the native runner identifies exactly one current host from this closed matrix while retaining its exact runner, job, run, and attempt identity:")
    fun currentHostIsOneClosedMatrixEntry(table: DataTable) {
        assertEquals(HOST_MATRIX, normalizedTable(table))
        host = HostIdentity.current()
        assertTrue(listOf(host.operatingSystem, host.architecture) in HOST_MATRIX.drop(1))
        assertTrue(host.runner.isNotBlank())
        assertTrue(host.job.isNotBlank())
        assertTrue(host.run.isNotBlank())
        assertTrue(host.attempt.isNotBlank())
    }

    // 4
    @Given("all four host runners bind the same full repository commit and tree plus clean\\/version state, while each runner binds its own platform candidate archive name and SHA-256 to that common revision before any case starts")
    fun bindRevisionVersionAndCandidate() {
        repositoryIdentity = observeRepositoryIdentity(repositoryRoot)
        QualificationRunRegistry.bindRepository(repositoryIdentity)
        assertTrue(repositoryIdentity.commit.matches(SHA1))
        assertTrue(repositoryIdentity.tree.matches(SHA1))
        assertEquals("refactorkit-runtime.zip", archive.fileName.toString())
        candidateArchiveIdentity = observeCandidateArchiveIdentity(
            archive,
            checksumSidecar,
            host.verifierPlatform,
        )
        buildJdkIdentity = observeBuildJdkIdentity()
        assertEquals(archive.fileName.toString(), candidateArchiveIdentity.archiveName)
        assertEquals(sha256(archive), candidateArchiveIdentity.archiveSha256)
        assertEquals(sha256(checksumSidecar), candidateArchiveIdentity.checksumSha256)
        assertEquals(host.verifierPlatform, candidateArchiveIdentity.verifierPlatform)
        assertTrue(candidateArchiveIdentity.verifierRecordSha256.matches(SHA256))
        assertTrue(candidateArchiveIdentity.verifierCompleteLogSha256.matches(SHA256))
        assertTrue(buildJdkIdentity.vendor.isNotBlank())
        assertTrue(buildJdkIdentity.version.startsWith("21."))
        assertTrue(buildJdkIdentity.releaseSha256.matches(SHA256))
        assertTrue(buildJdkIdentity.javaExecutableSha256.matches(SHA256))
        assertTrue(buildJdkIdentity.modulesImageSha256.matches(SHA256))
        assertEquals(QualificationOracle.version, "0.7.0-SNAPSHOT")
        if (repositoryIdentity.cleanExactCandidateRequired) {
            assertEquals("CLEAN", repositoryIdentity.worktreeState)
            assertTrue(repositoryIdentity.cleanExactCandidateSatisfied)
            assertTrue(repositoryIdentity.githubShaPresent)
            assertEquals(repositoryIdentity.commit, repositoryIdentity.githubSha)
        }
    }

    // 5
    @Given("the candidate archive is extracted once into an isolated read-only subject image whose complete no-follow tree hash is recorded before and after the case")
    fun retainExtractedCandidateManifest() {
        assertTrue(Files.isDirectory(candidateRoot, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(candidateRoot))
        val hardened = hardenCandidateImageReadOnly(candidateRoot, archive)
        candidateBefore = hardened.manifest
        candidateReadOnlyEvidence = hardened.evidence
        assertTrue(candidateBefore.entries.values.none { it.kind == ManifestPathKind.SYMBOLIC_LINK })
        assertTrue(candidateReadOnlyEvidence.allRegularFilesReadOnly)
        assertTrue(candidateReadOnlyEvidence.allDirectoriesReadOnly)
        assertTrue(candidateReadOnlyEvidence.executeBitsPreserved)
        assertTrue(candidateReadOnlyEvidence.regularFileMutationDenied)
        assertTrue(candidateReadOnlyEvidence.directoryMutationDenied)
        assertEquals(
            candidateReadOnlyEvidence.bytePathIdentityBefore,
            candidateReadOnlyEvidence.bytePathIdentityAfter,
        )
        assertEquals(
            candidateReadOnlyEvidence.archiveSha256Before,
            candidateReadOnlyEvidence.archiveSha256After,
        )
        assertEquals(candidateArchiveIdentity.archiveSha256, candidateReadOnlyEvidence.archiveSha256Before)
        assertEquals(candidateArchiveIdentity.archiveSha256, candidateReadOnlyEvidence.archiveSha256After)
        QualificationRunRegistry.bindCandidate(
            candidateBefore,
            candidateArchiveIdentity,
            candidateReadOnlyEvidence,
        )
    }

    // 6
    @Given("^the runner resolves \"([^\"]+)\" only as `bin/([^`]+)` on POSIX or `bin/([^`]+)\\.bat` on Windows inside that extracted image and will start no other subject executable$")
    fun resolveOnlySelectedLauncher(launcherName: String, posixLauncher: String, windowsLauncher: String) {
        assertEquals(surface.launcher, launcherName)
        assertEquals(launcherName, posixLauncher)
        assertEquals(launcherName, windowsLauncher)
        val fileName = if (host.operatingSystem == "Windows") "$launcherName.bat" else launcherName
        launcher = candidateRoot.resolve("bin").resolve(fileName).normalize()
        assertTrue(launcher.startsWith(candidateRoot.resolve("bin").normalize()))
        assertEquals(candidateRoot.resolve("bin/$fileName").normalize(), launcher)
        assertTrue(Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(launcher))
    }

    // 7
    @Given("the runner records the selected launcher entry type, archive mode, and SHA-256 without repairing its permissions; POSIX will invoke that path without a shell or interpreter, Windows will use the corresponding public batch launcher, and the embedded `runtime\\/bin\\/java` or `runtime\\/bin\\/java.exe` is a regular executable archive entry")
    fun retainLauncherAndEmbeddedJavaEvidence() {
        val launcherRelative = candidateRoot.relativize(launcher).invariantSeparatorsPathString
        val javaRelative = if (host.operatingSystem == "Windows") "runtime/bin/java.exe" else "runtime/bin/java"
        embeddedJava = candidateRoot.resolve(javaRelative).normalize()
        launcherArchiveEvidence = inspectArchiveEntry(archive, "refactorkit/$launcherRelative")
        embeddedJavaArchiveEvidence = inspectArchiveEntry(archive, "refactorkit/$javaRelative")
        assertTrue(launcherArchiveEvidence.regularFile)
        assertFalse(launcherArchiveEvidence.symbolicLink)
        assertTrue(embeddedJavaArchiveEvidence.regularFile)
        assertFalse(embeddedJavaArchiveEvidence.symbolicLink)
        assertTrue(Files.isRegularFile(embeddedJava, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(embeddedJava))
        launcherExtractedMode = posixMode(launcher) ?: launcherArchiveEvidence.mode
        assertEquals(launcherArchiveEvidence.sha256, sha256(launcher))
        assertEquals(embeddedJavaArchiveEvidence.sha256, sha256(embeddedJava))
        candidateRuntimeIdentity = observeCandidateRuntimeIdentity(
            candidateRoot,
            embeddedJava,
            buildJdkIdentity,
        )
        assertEquals(buildJdkIdentity.version, candidateRuntimeIdentity.javaVersion)
        assertTrue(candidateRuntimeIdentity.buildJdkVersionMatched)
        assertEquals(embeddedJavaArchiveEvidence.sha256, candidateRuntimeIdentity.javaExecutableSha256)
        assertEquals(candidateBefore.entries.getValue(javaRelative).sha256, candidateRuntimeIdentity.javaExecutableSha256)
        assertEquals(candidateBefore.entries.getValue("runtime/release").sha256, candidateRuntimeIdentity.releaseSha256)
        assertEquals(
            candidateBefore.entries.getValue("runtime/lib/modules").sha256,
            candidateRuntimeIdentity.modulesImageSha256,
        )
        QualificationRunRegistry.bindToolchain(buildJdkIdentity, candidateRuntimeIdentity)
        candidateArchiveProbeEvidence = CandidateArchiveIdentityContractProbe.verify(
            archive,
            checksumSidecar,
            candidateRoot,
            host.verifierPlatform,
        )
        assertEquals(candidateArchiveIdentity.archiveSha256, candidateArchiveProbeEvidence.archiveSha256)
        assertEquals(candidateArchiveIdentity.checksumSha256, candidateArchiveProbeEvidence.checksumSha256)
        assertEquals(candidateArchiveIdentity.verifierRecordSha256, candidateArchiveProbeEvidence.verifierRecordSha256)
        assertTrue(candidateArchiveProbeEvidence.missingChecksumRejected)
        assertTrue(candidateArchiveProbeEvidence.missingVerifierRejected)
        if (host.operatingSystem != "Windows") {
            val expectedReadOnlyMode = "%04o".format(
                launcherArchiveEvidence.mode.toInt(8) and 0b101_101_101,
            )
            assertEquals(expectedReadOnlyMode, launcherExtractedMode)
            assertTrue(modeHasExecute(launcherArchiveEvidence.mode))
            assertTrue(modeHasExecute(launcherExtractedMode))
            assertTrue(modeHasExecute(embeddedJavaArchiveEvidence.mode))
            assertTrue(Files.isExecutable(embeddedJava))
        }
        if (host.operatingSystem == "Linux" && host.architecture == "x86-64") {
            val launcherOracle = QualificationOracle.launcherEntries.getValue(surface.launcher)
            assertEquals(launcherOracle.posixMode, launcherArchiveEvidence.mode)
            assertEquals(launcherOracle.sha256, launcherArchiveEvidence.sha256)
        }
        gateSelectorEvidence = inspectPackagedGateSelector(
            candidateRoot,
            archive,
            QualificationOracle.operation,
            QualificationOracle.gate,
        )
        assertEquals(gateSelectorEvidence.jvmJarSha256, gateSelectorEvidence.archiveEntrySha256)
        assertEquals(QualificationOracle.operation, gateSelectorEvidence.publicOperation)
        assertEquals(QualificationOracle.gate, gateSelectorEvidence.diagnosticsGateId)
    }

    // 8
    @Given("the subject environment removes inherited `JAVA_HOME`, `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, classpath, Maven, Gradle, credential, proxy, and user-settings authority")
    fun createScrubbedSubjectEnvironment() {
        sandbox = SubjectSandbox.create(caseContainer, surface.slug)
        val evidence = sandbox.attestation
        assertTrue(evidence.inheritedEnvironmentCleared)
        assertTrue(evidence.inheritedJavaOptionsRemoved)
        assertTrue(evidence.controlledJavaOptionsInstalled)
        assertTrue(evidence.authorityVariablesAbsent)
        assertTrue(evidence.proxyVariablesAbsent)
        assertTrue(evidence.credentialVariablesAbsent)
        assertEquals(4, evidence.settingsTrapHashes.size)
        assertEquals(21, evidence.testRuntimeJavaFeature)
        assertEquals(SubjectSandbox.CHILD_GUARD_POLICY_VERSION, evidence.childSecurityGuardPolicyVersion)
        assertTrue(evidence.childSecurityGuardAgentSha256.matches(SHA256))
        assertTrue(evidence.installedTestRuntimePathSha256.matches(SHA256))
    }

    // 9
    @Given("isolated home and temporary directories plus a minimal poison `PATH` put a failing decoy `java` ahead of every external tool, while launcher and process evidence proves only the archive's hash-bound embedded Java executes")
    fun isolatedDirectoriesAndPoisonPathAreReady() {
        assertTrue(Files.isDirectory(sandbox.home, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(sandbox.temporary, LinkOption.NOFOLLOW_LINKS))
        assertTrue(sandbox.attestation.poisonPathFirst)
        assertTrue("java" in sandbox.attestation.poisonExecutables)
        assertTrue(Files.exists(sandbox.poison.resolve(if (sandbox.windows) "java.exe" else "java")))
        assertFalse(Files.exists(sandbox.marker, LinkOption.NOFOLLOW_LINKS))
    }

    // 10
    @Given("no Gradle application classpath, source-built or in-process session, developer installation, globally installed Java, or fallback launcher is available to the subject")
    fun subjectHasNoSourceBuiltFallback() {
        assertTrue(launcher.startsWith(candidateRoot))
        assertTrue(embeddedJava.startsWith(candidateRoot))
        assertFalse(sandbox.environment.containsKey("CLASSPATH"))
        assertFalse(sandbox.environment.containsKey("JAVA_HOME"))
        assertFalse(sandbox.environment.getValue("PATH").contains(repositoryRoot.toString()))
        processHarness = PackagedSubjectProcessHarness(
            launcher,
            embeddedJava,
            candidateRoot,
            sandbox,
            absoluteDeadlineNanos,
        )
    }

    // 11
    @Given("startup, each request, complete untruncated stdout and stderr, memory where enforceable, total runtime, descendants, output files, and no-follow cleanup are bounded fail-closed, so a timeout, truncated stream, live descendant, cleanup failure, or missing report fails the case")
    fun boundedContractsAreConfigured() {
        assertTrue(PersistentNdjsonProcess.STARTUP_TIMEOUT <= Duration.ofSeconds(30))
        assertTrue(PersistentNdjsonProcess.REQUEST_TIMEOUT <= Duration.ofSeconds(180))
        assertTrue(PersistentNdjsonProcess.IDLE_TIMEOUT <= Duration.ofSeconds(60))
        assertTrue(PackagedSubjectProcessHarness.OUTPUT_LIMIT_BYTES == 32 * 1024 * 1024)
        assertTrue(System.nanoTime() < absoluteDeadlineNanos)
        processMonitorProbeEvidence = ProcessObservationMonitorContractProbe.verify()
        assertTrue(processMonitorProbeEvidence.cleanPathAccepted)
        assertTrue(processMonitorProbeEvidence.liveDescendantRejected)
        assertTrue(processMonitorProbeEvidence.liveDescendantIdentityRecorded)
        assertTrue(processMonitorProbeEvidence.rootTerminated)
        assertTrue(processMonitorProbeEvidence.descendantTerminated)
        assertEquals(0, processMonitorProbeEvidence.survivorCount)
        listOf(logsDirectory, manifestsDirectory, failureDiagnosticsDirectory).forEach {
            assertTrue(Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS))
        }
    }

    // 12
    @Given("process and network tripwires deny Maven or Gradle wrappers and lifecycles, plugins, annotation processors, generators, project scripts, user or global settings, mirrors, proxies, servers, credentials, credential helpers, and every network request during discovery, preview, staged evaluation, apply, diagnostics, and rollback")
    fun deniedAuthorityTripwiresAreReady() {
        val poison = sandbox.attestation.poisonExecutables
        assertTrue(poison.containsAll(setOf("mvn", "mvnw", "gradle", "gradlew", "git", "curl", "wget")))
        assertEquals("JDK21_GUARD_DENY", sandbox.environment["REFACTOR_KIT_QUALIFICATION_NETWORK"])
        assertTrue(sandbox.attestation.proxyVariablesAbsent)
        assertTrue(Files.notExists(fixtureRoot.resolve("mvnw"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.notExists(fixtureRoot.resolve("gradlew"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.notExists(fixtureRoot.resolve("settings.xml"), LinkOption.NOFOLLOW_LINKS))
    }

    // 13
    @Given("the only permanent project is {string}, with one root aggregator, exactly 20 unique active direct non-aggregating JAR children, and absent direct child {string}")
    fun permanentFixtureIsExact(relativePath: String, absentChild: String) {
        assertEquals(FIXTURE_PATH, relativePath)
        assertEquals("catalog-domain", absentChild)
        fixtureM0 = captureTreeManifest(fixtureRoot)
        assertTrue(fixtureM0.entries.values.none { it.kind == ManifestPathKind.SYMBOLIC_LINK })
        val permanent = retainWorkspaceOracles(fixtureRoot)
        val reactor = inspectReactor(permanent.s0Bytes, staged = false)
        assertEquals(QualificationOracle.modules, reactor.directModules)
        assertEquals(20, reactor.packagingByModule.size)
        assertTrue(reactor.packagingByModule.values.all { it == "jar" })
        assertFalse(absentChild in reactor.directModules)
        assertTrue(permanent.s0Bytes.getValue("pom.xml").toString(StandardCharsets.UTF_8)
            .contains("<packaging>pom</packaging>"))
        QualificationRunRegistry.bindFixture(fixtureM0)
    }

    // 14
    @Given("^this (packaged CLI|packaged daemon|packaged MCP) receives one fresh pairwise-distinct no-follow disposable byte copy that refuses every symbolic link, preserves every regular-file byte and directory path kind, and cannot write through or back to the permanent project, extracted image, or another case$")
    fun createFreshPairwiseCopy(surfaceName: String) {
        assertEquals(surface.displayName, surfaceName)
        workspaceRoot = caseContainer.resolve("workspace")
        copyInitialManifest = copyTreeNoFollow(fixtureRoot, workspaceRoot)
        assertEquals(fixtureM0, copyInitialManifest)
        assertFalse(workspaceRoot.startsWith(fixtureRoot))
        assertFalse(workspaceRoot.startsWith(candidateRoot))
        assertFalse(candidateRoot.startsWith(workspaceRoot))
    }

    // 15
    @Given("before the subject starts, the harness independently retains these immutable identities without reading a preview, response, or journal record:")
    fun retainIndependentIdentityClasses(table: DataTable) {
        assertEquals(IDENTITY_TABLE, normalizedTable(table))
        assertFalse(launcherStartAttempted)
        assertNull(previewResponse)
        assertNull(appliedJournal)
        workspaceOracle = retainWorkspaceOracles(workspaceRoot)
        assertEquals(fixtureM0, workspaceOracle.s0Manifest)
        assertEquals(emptyList(), workspaceOracle.d0)
        inspectReactor(workspaceOracle.s0Bytes, staged = false)
        inspectReactor(workspaceOracle.c1Bytes, staged = true)
        copyIdentity = sha256(
            "${surface.displayName}\u0000${workspaceRoot.toAbsolutePath().normalize()}\u0000${UUID.randomUUID()}"
                .toByteArray(StandardCharsets.UTF_8),
        )
        QualificationRunRegistry.registerCopy(surface.displayName, workspaceRoot, copyIdentity)
        assertTrue(QualificationRunRegistry.copyRootsArePairwiseDistinct())
        assertTrue(QualificationRunRegistry.copyIdentitiesArePairwiseDistinct())
        QualificationRunRegistry.writeGlobalManifests(
            manifestsDirectory,
            fixtureM0,
            candidateBefore,
            candidateReadOnlyEvidence,
            repositoryIdentity,
            candidateArchiveIdentity,
            buildJdkIdentity,
            candidateRuntimeIdentity,
        )
    }

    // 16
    @Given("the affected fixture bytes and their independently computed post-images are exactly:")
    fun fixtureImagesEqualIndependentOracle(table: DataTable) {
        assertEquals(QualificationOracle.imageTable, normalizedTable(table))
        QualificationOracle.images.forEach { image ->
            val pre = workspaceOracle.s0Bytes.getValue(image.prePath)
            val post = workspaceOracle.c1Bytes.getValue(image.postPath)
            assertEquals(image.preBytes, pre.size)
            assertEquals(image.preSha256, sha256(pre))
            assertEquals(image.postBytes, post.size)
            assertEquals(image.postSha256, sha256(post))
        }
    }

    // 17
    @Given("every request is explicitly oldModuleDir={string}, newModuleDir={string}, and newArtifactId={string}")
    fun exactRequestIsRetained(oldModuleDir: String, newModuleDir: String, newArtifactId: String) {
        assertEquals(QualificationOracle.request, RenameRequestOracle(oldModuleDir, newModuleDir, newArtifactId))
    }

    // 18
    @Given("^the harness is restricted to (.+) and these exact public interactions in order:$")
    fun interactionsAreExactlySurfaceBounded(boundary: String, table: DataTable) {
        interactionSpec = InteractionSpec.forSurface(surface)
        assertEquals(interactionSpec.boundary, boundary)
        assertEquals(interactionSpec.table, normalizedTable(table))
    }

    // 19
    @When("^the harness performs (.+) and then (.+) with \"([^\"]+)\" only from the extracted image through (.+)$")
    fun establishAndPreview(
        establishInteraction: String,
        previewInteraction: String,
        launcherName: String,
        boundary: String,
    ) {
        assertEquals(interactionSpec.establish, establishInteraction)
        assertEquals(interactionSpec.preview, previewInteraction)
        assertEquals(surface.launcher, launcherName)
        assertEquals(interactionSpec.boundary, boundary)
        launcherStartAttempted = true
        try {
            when (surface) {
                Surface.CLI -> previewThroughCli()
                Surface.DAEMON -> previewThroughDaemon()
                Surface.MCP -> previewThroughMcp()
            }
            launcherStarted = true
        } catch (refusal: SubjectStartRefused) {
            processHarness.recordStartRefusal(refusal)
            startFailure = refusal
            if (surface != Surface.MCP || host.operatingSystem == "Windows" || modeHasExecute(launcherArchiveEvidence.mode)) {
                throw refusal
            }
        }
    }

    // 20
    @Then("the selected launcher has started from the extracted image through its public POSIX or Windows form and, on POSIX, direct execution proves its recorded archive mode contains execute bits; across the three examples this executes and qualifies all three shipped launchers")
    fun selectedLauncherStartsWithRequiredArchiveMode() {
        if (host.operatingSystem != "Windows" && !modeHasExecute(launcherArchiveEvidence.mode)) {
            val refusal = assertNotNull(startFailure, "Direct POSIX execution did not report its refusal")
            assertTrue(refusal.cause?.message.orEmpty().contains("13"), "Direct POSIX refusal was not EACCES")
            failureDiagnostic = buildString {
                append(QualificationOracle.requirementId)
                append(": extracted bin/").append(surface.launcher)
                append(" archive mode ").append(launcherArchiveEvidence.mode)
                append(" has no execute bit; direct POSIX execution refused with SubjectStartRefused (EACCES/error 13)")
            }
            assertTrue(false, failureDiagnostic)
        }
        assertNull(startFailure, "Executable archive launcher was refused")
        assertTrue(launcherStarted)
        assertTrue(launcher.startsWith(candidateRoot))
    }

    // 21
    @Then("the preview succeeds read-only with status {string}, requires explicit approval, and exposes canonical operation {string}")
    fun previewIsSuccessfulAndCanonical(status: String, operation: String) {
        assertEquals("PREVIEW", status)
        assertEquals(QualificationOracle.operation, operation)
        when (surface) {
            Surface.CLI -> {
                val result = assertNotNull(previewResult)
                assertSuccessful(result)
                val stdout = result.stdoutText()
                assertTrue(stdout.startsWith("Operation: $operation\nStatus: $status\n"))
                assertTrue(stdout.endsWith("Use --apply to apply this change.\n"))
            }
            Surface.DAEMON -> {
                val response = assertNotNull(previewResponse)
                assertEquals(status, response.string("status"))
                assertEquals(operation, response.string("operation"))
                assertTrue(response.string("planId").startsWith("plan-"))
                assertEquals(true, response.getValue("snapshot").jsonObject.getValue("validatedOnApply").jsonPrimitive.boolean)
            }
            Surface.MCP -> {
                val text = assertNotNull(mcpPreviewText)
                assertTrue(text.contains("Operation: $operation"))
                assertTrue(text.contains("Status   : $status"))
                assertTrue(text.contains("To apply: use tool apply_refactoring"))
            }
        }
        publicCanonicalOperationObserved = operation
        previewVerified = true
    }

    // 22
    @Then("the daemon preview uses only the existing input operation {string} before returning canonical {string}, while MCP supplies canonical {string} and no new alias, method, field, protocol shape, or selector semantics is introduced")
    fun aliasesAndCanonicalOperationRemainExisting(
        daemonAlias: String,
        canonical: String,
        mcpCanonical: String,
    ) {
        assertEquals("renameMavenModule", daemonAlias)
        assertEquals(QualificationOracle.operation, canonical)
        assertEquals(QualificationOracle.operation, mcpCanonical)
        when (surface) {
            Surface.CLI -> assertNull(requestedOperation)
            Surface.DAEMON -> assertEquals(daemonAlias, requestedOperation)
            Surface.MCP -> {
                assertEquals(mcpCanonical, requestedOperation)
                assertTrue(mcpTools.containsAll(setOf("project_scan", "preview_refactoring", "apply_refactoring", "diagnostics", "rollback_refactoring")))
            }
        }
    }

    // 23
    @Then("^(preview and apply use distinct launcher processes; apply replans, and no preview PlanId crosses a process boundary|one daemon process retains the returned canonical plan from preview through apply; only successful apply and rollback refresh session state|one initialized MCP process retains the canonical plan from preview through apply and retains correlation only inside that session)$")
    fun planLifecycleIsSurfaceExact(lifecycle: String) {
        assertEquals(interactionSpec.planLifecycle, lifecycle)
        when (surface) {
            Surface.CLI -> {
                assertNull(planId)
                assertEquals(1, processHarness.transcripts.size)
            }
            Surface.DAEMON, Surface.MCP -> {
                assertNotNull(planId)
                assertNotNull(persistentProcess)
                assertEquals(0, processHarness.transcripts.size)
            }
        }
    }

    // 24
    @Then("the preview's normalized WorkspaceEdit equals exactly these five ordered non-overlapping FileEdit entries, using zero-based end-exclusive ranges, and contains no other edit:")
    fun previewWorkspaceEditIsExact(table: DataTable) {
        assertEquals(QualificationOracle.editTable, normalizedTable(table))
        QualificationOracle.edits.filter { it.kind == "Modify" }.forEach { edit ->
            assertTrue(edit.range.matches(Regex("\\d+:\\d+-\\d+:\\d+")))
            assertEquals("catalog-domain", edit.newText)
        }
        when (surface) {
            Surface.CLI -> {
                val result = assertNotNull(previewResult)
                assertEquals(QualificationOracle.cliPreviewStdoutBytes.toLong(), result.stdoutBytesObserved)
                assertEquals(QualificationOracle.cliPreviewStdoutSha256, sha256(result.stdout))
            }
            Surface.DAEMON -> assertStructuredDiff(assertNotNull(previewResponse).getValue("structuredDiff").jsonArray)
            Surface.MCP -> {
                val text = assertNotNull(mcpPreviewText)
                assertEquals(1, Regex("Affected : 6 file\\(s\\)").findAll(text).count())
            }
        }
    }

    // 25
    @Then("its normalized affected-path set is exactly `pom.xml`, `catalog-model\\/pom.xml`, `catalog-pricing\\/pom.xml`, both Product source paths, and `catalog-domain\\/pom.xml`, with the repeated child-POM source counted once")
    fun affectedPathsAreExact() {
        val expected = QualificationOracle.affectedPaths.toSet()
        val actual = when (surface) {
            Surface.CLI -> parseCliAffectedPaths(assertNotNull(previewResult).stdoutText())
            Surface.DAEMON -> assertNotNull(previewResponse).getValue("affectedFiles").jsonArray
                .mapTo(linkedSetOf()) { it.jsonPrimitive.content }
            Surface.MCP -> parseMcpAffectedPaths(assertNotNull(mcpPreviewText))
        }
        assertEquals(expected, actual)
        assertEquals(6, actual.size)
    }

    // 26
    @Then("only the three listed element-text ranges change bytes; every parent coordinate, groupId, version, packaging, profile, dependency-management entry, transitive consumer, XML construct, whitespace, line ending, comment, processing instruction, unknown element, and Java source byte remains exact")
    fun onlyDeclaredRangesChangeBytes() {
        val removed = workspaceOracle.s0Bytes.keys - workspaceOracle.c1Bytes.keys
        val added = workspaceOracle.c1Bytes.keys - workspaceOracle.s0Bytes.keys
        val commonChanged = workspaceOracle.s0Bytes.keys.intersect(workspaceOracle.c1Bytes.keys)
            .filterTo(linkedSetOf()) { path ->
                !workspaceOracle.s0Bytes.getValue(path).contentEquals(workspaceOracle.c1Bytes.getValue(path))
            }
        assertEquals(
            setOf(
                "catalog-model/pom.xml",
                "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
            ),
            removed,
        )
        assertEquals(
            setOf(
                "catalog-domain/pom.xml",
                "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
            ),
            added,
        )
        assertEquals(setOf("pom.xml", "catalog-pricing/pom.xml"), commonChanged)
        assertContentEquals(
            workspaceOracle.s0Bytes.getValue("catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"),
            workspaceOracle.c1Bytes.getValue("catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java"),
        )
        QualificationOracle.images.forEach { image ->
            val before = workspaceOracle.s0Bytes.getValue(image.prePath).toString(StandardCharsets.UTF_8)
            val after = workspaceOracle.c1Bytes.getValue(image.postPath).toString(StandardCharsets.UTF_8)
            assertFalse(before.contains("\r\n"), image.prePath)
            assertFalse(after.contains("\r\n"), image.postPath)
            assertEquals(before.endsWith('\n'), after.endsWith('\n'), image.postPath)
        }
    }

    // 27
    @Then("C1 retains exactly 20 direct children, replaces the root module in its original list position with `catalog-domain`, resolves `com.acme.refactorkit.fixture:catalog-domain:1.0.0` as a JAR, and makes `catalog-pricing` depend directly on that exact coordinate")
    fun c1ReactorIsExact() {
        val reactor = inspectReactor(workspaceOracle.c1Bytes, staged = true)
        assertEquals(20, reactor.directModules.size)
        assertEquals("catalog-domain", reactor.directModules[15])
        assertEquals("jar", reactor.packagingByModule.getValue("catalog-domain"))
        assertEquals("catalog-domain", reactor.artifactByModule.getValue("catalog-domain"))
        assertEquals(
            Triple("com.acme.refactorkit.fixture", "catalog-domain", "${'$'}{project.version}"),
            reactor.pricingDependency,
        )
    }

    // 28
    @Then("C1 creates only this ordered destination-directory hierarchy while preserving the now-empty pre-existing `catalog-model` hierarchy:")
    fun c1CreatedDirectoriesAreExact(table: DataTable) {
        assertEquals(QualificationOracle.createdDirectoryTable, normalizedTable(table))
        QualificationOracle.createdDirectories.forEach { directory ->
            assertEquals(ManifestPathKind.DIRECTORY, workspaceOracle.c1Manifest.entries.getValue(directory).kind)
        }
        val oldDirectories = workspaceOracle.s0Manifest.entries.filter { (path, entry) ->
            (path == "catalog-model" || path.startsWith("catalog-model/")) &&
                entry.kind == ManifestPathKind.DIRECTORY
        }
        oldDirectories.forEach { (path, entry) ->
            assertEquals(entry, workspaceOracle.c1Manifest.entries[path])
        }
        assertTrue(workspaceOracle.c1Manifest.entries.none { (path, entry) ->
            path.startsWith("catalog-model/") && entry.kind == ManifestPathKind.REGULAR_FILE
        })
    }

    // 29
    @Then("preview leaves the disposable copy exact S0 with no workspace lock, `.refactorkit` path, WAL record, transaction, target directory, or mutation of M0 or the extracted image")
    fun previewIsReadOnly() {
        assertEquals(workspaceOracle.s0Manifest, captureTreeManifest(workspaceRoot).withoutEngine())
        assertTrue(Files.notExists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.notExists(workspaceRoot.resolve("catalog-domain"), LinkOption.NOFOLLOW_LINKS))
        assertEquals(fixtureM0, captureTreeManifest(fixtureRoot))
        assertEquals(candidateBefore, captureTreeManifest(candidateRoot))
        assertEquals(boundArchiveSha256(), sha256(archive))
    }

    // 30
    @When("^the harness performs (.+) according to (.+) with only the surface's generated correlation token where its existing lifecycle provides one$")
    fun applyThroughPublicSurface(applyInteraction: String, applyLifecycle: String) {
        assertEquals(interactionSpec.apply, applyInteraction)
        assertEquals(interactionSpec.applyLifecycle, applyLifecycle)
        when (surface) {
            Surface.CLI -> applyThroughCli()
            Surface.DAEMON -> applyThroughDaemon()
            Surface.MCP -> applyThroughMcp()
        }
    }

    // 31
    @Then("fresh no-follow filesystem observation before journal inspection proves that the committed non-engine workspace is exact S1 and differs from S0 only by the declared post-image and path-kind oracle")
    fun committedWorkspaceIsExactS1() {
        assertFalse(journalInspectionStarted)
        assertNull(appliedJournal)
        val observed = captureTreeManifest(workspaceRoot).withoutEngine()
        assertEquals(workspaceOracle.c1Manifest, observed)
        assertEquals(workspaceOracle.s1Identity, observed.sha256)
        assertNotEquals(workspaceOracle.s0Identity, observed.sha256)
        assertTrue(Files.isDirectory(workspaceRoot.resolve("catalog-model"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isDirectory(workspaceRoot.resolve("catalog-domain"), LinkOption.NOFOLLOW_LINKS))
        s1ObservedBeforeJournal = true
    }

    // 32
    @Then("the applied plan equals the independent five-entry oracle, and only the existing operation-owned gate {string} authorizes PatchEngine after evaluating exact S0 and C1 before PREPARED, then authoritative S1 with unchanged D0 after APPLIED and before public success, without generic `java-jdt` substitution")
    fun appliedPlanAndGateAreExact(gate: String) {
        assertTrue(s1ObservedBeforeJournal)
        assertFalse(journalInspectionStarted)
        journalInspectionStarted = true
        assertEquals(QualificationOracle.gate, gate)
        assertTrue(previewVerified)
        assertEquals(QualificationOracle.operation, publicCanonicalOperationObserved)
        assertEquals(QualificationOracle.operation, gateSelectorEvidence.publicOperation)
        assertEquals(gate, gateSelectorEvidence.diagnosticsGateId)
        assertEquals(gateSelectorEvidence.jvmJarSha256, gateSelectorEvidence.archiveEntrySha256)
        assertTrue(gateSelectorEvidence.operationToGateMappingProofSha256.matches(SHA256))

        val journal = inspectSoleJournal("APPLIED")
        appliedJournal = journal
        validateJournalCore(journal, "APPLIED")
        val forwardEdit = journal.getValue("forwardEdit").jsonObject
        validateForwardEdit(forwardEdit)
        normalizedForwardEditSha256 = canonicalJsonSha256(forwardEdit)
        assertEquals(canonicalJsonSha256(normalizedForwardEditOracle()), normalizedForwardEditSha256)
        validateJournalImages(journal)
        assertEquals(QualificationOracle.createdDirectories, journal.getValue("createdDirectories").jsonArray.strings())
        assertFalse(Json.encodeToString(journal).contains("java-jdt"))
        assertTrue(journal.keys.none { it.contains("gate", ignoreCase = true) })
        assertTrue(journal.getValue("transaction").jsonObject.keys.none { it.contains("gate", ignoreCase = true) })
        assertEquals(listOf("PREPARED", "APPLYING", "APPLIED"), history(journal))
        assertEquals(workspaceOracle.c1Manifest, captureTreeManifest(workspaceRoot).withoutEngine())
        assertNotEquals(workspaceOracle.s0Identity, workspaceOracle.s1Identity)
        assertTrue(assertNotNull(productS0).matches(SHA256))
        assertTrue(assertNotNull(productS1).matches(SHA256))
        assertEquals(emptyList(), workspaceOracle.d0)
        gateIdentityVerified = true
        applyVerified = true
    }

    // 33
    @Then("PatchEngine remains the sole lock, managed-write, diagnostics-gate, WAL, recovery, and rollback authority; the process harness and protocol responses manufacture no approval or write authority")
    fun patchEngineRemainsSoleAuthority() {
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(".refactorkit/workspace.lock")))
        assertEquals(1, journalPaths().size)
        assertEquals("java.renameMavenModule", assertNotNull(appliedJournal).string("operation"))
        assertEquals("EXPLICIT_APPLY", assertNotNull(appliedJournal)
            .getValue("transaction").jsonObject.getValue("approval").jsonObject.string("kind"))
        assertFalse(Files.exists(workspaceRoot.resolve(".qualification-approval"), LinkOption.NOFOLLOW_LINKS))
    }

    // 34
    @Then("^the harness performs (.+) and receives a clean fresh packaged-surface result whose authoritative Maven and JDT diagnostic multiset is exactly D0 for S1$")
    fun packagedDiagnosticsAreClean(diagnosticsInteraction: String) {
        assertEquals(interactionSpec.diagnostics, diagnosticsInteraction)
        when (surface) {
            Surface.CLI -> {
                firstDiagnosticsResult = processHarness.runOneShot(
                    "cli-diagnostics-after-apply",
                    listOf("diagnostics", workspaceRoot.toAbsolutePath().normalize().toString()),
                    workspaceRoot,
                )
                assertCliDiagnosticsClean(assertNotNull(firstDiagnosticsResult))
            }
            Surface.DAEMON -> {
                diagnosticsResponse = daemonRequest("diagnostics", buildJsonObject {})
                assertEquals(JsonArray(emptyList()), diagnosticsResponse)
            }
            Surface.MCP -> {
                val text = mcpTool("diagnostics", buildJsonObject {})
                assertTrue(text.contains("No diagnostics", ignoreCase = true))
                diagnosticsResponse = JsonArray(emptyList())
            }
        }
        assertEquals(emptyList(), workspaceOracle.d0)
        diagnosticsVerified = true
    }

    // 35
    @Then("read-only journal inspection finds exactly one record and no second journal, with these exact facts:")
    fun appliedJournalFactsAreExact(table: DataTable) {
        assertEquals(expectedJournalTable("APPLIED"), normalizedTable(table))
        val journal = inspectSoleJournal("APPLIED")
        assertEquals(assertNotNull(appliedJournal), journal)
        validateJournalCore(journal, "APPLIED")
        assertEquals(listOf("PREPARED", "APPLYING", "APPLIED"), history(journal))
        assertEquals(1, journalPaths().size)
    }

    // 36
    @When("^the harness performs (.+) to request normal rollback of that sole transaction according to (.+)$")
    fun rollbackThroughPublicSurface(rollbackInteraction: String, rollbackLifecycle: String) {
        assertEquals(interactionSpec.rollback, rollbackInteraction)
        assertEquals(interactionSpec.rollbackLifecycle, rollbackLifecycle)
        when (surface) {
            Surface.CLI -> rollbackThroughCli()
            Surface.DAEMON -> rollbackThroughDaemon()
            Surface.MCP -> rollbackThroughMcp()
        }
    }

    // 37
    @Then("rollback succeeds without force, recovery, compensation, or a second transaction and advances the same schema-v8 record to {string}")
    fun rollbackSucceedsOnSameRecord(state: String) {
        assertEquals("ROLLED_BACK", state)
        val journal = inspectSoleJournal(state)
        rolledBackJournal = journal
        assertEquals(transactionId, journal.getValue("transaction").jsonObject.string("id"))
        assertEquals(1, journalPaths().size)
        when (surface) {
            Surface.CLI -> assertSuccessful(assertNotNull(rollbackResult))
            Surface.DAEMON -> assertTrue(assertNotNull(rollbackResponse).getValue("rolledBack").jsonPrimitive.boolean)
            Surface.MCP -> assertTrue(assertNotNull(mcpRollbackText).contains("Rolled back transaction"))
        }
        rollbackVerified = true
    }

    // 38
    @Then("its ordered history is exactly {string} while its operation, approval, forward edit, images, created directories, S0, and S1 facts remain unchanged")
    fun rolledBackHistoryAndEvidenceRemainExact(expectedHistory: String) {
        assertEquals("PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK", expectedHistory)
        val applied = assertNotNull(appliedJournal)
        val rolledBack = assertNotNull(rolledBackJournal)
        assertEquals(expectedHistory.split(", "), history(rolledBack))
        listOf(
            "operation",
            "forwardEdit",
            "preImages",
            "postImages",
            "createdDirectories",
            "preSnapshotHash",
            "postSnapshotHash",
        ).forEach { field -> assertEquals(applied[field], rolledBack[field], field) }
        assertEquals(applied.getValue("transaction").jsonObject.getValue("approval"),
            rolledBack.getValue("transaction").jsonObject.getValue("approval"))
    }

    // 39
    @Then("fresh packaged diagnostics and no-follow observation prove exact restoration of every non-engine byte, path kind, permission, source and auxiliary inventory, reactor fact, snapshot identity S0, and diagnostic D0")
    fun freshDiagnosticsAndObservationProveS0() {
        when (surface) {
            Surface.CLI -> {
                restoredDiagnosticsResult = processHarness.runOneShot(
                    "cli-diagnostics-after-rollback",
                    listOf("diagnostics", workspaceRoot.toAbsolutePath().normalize().toString()),
                    workspaceRoot,
                )
                assertCliDiagnosticsClean(assertNotNull(restoredDiagnosticsResult))
            }
            Surface.DAEMON -> {
                restoredDiagnosticsResponse = daemonRequest("diagnostics", buildJsonObject {})
                assertEquals(JsonArray(emptyList()), restoredDiagnosticsResponse)
            }
            Surface.MCP -> {
                val text = mcpTool("diagnostics", buildJsonObject {})
                assertTrue(text.contains("No diagnostics", ignoreCase = true))
                restoredDiagnosticsResponse = JsonArray(emptyList())
            }
        }
        val restored = captureTreeManifest(workspaceRoot).withoutEngine()
        assertEquals(workspaceOracle.s0Manifest, restored)
        assertEquals(workspaceOracle.s0Identity, restored.sha256)
        assertEquals(emptyList(), workspaceOracle.d0)
        inspectReactor(readWorkspaceNonEngineBytes(), staged = false)
        if (surface == Surface.DAEMON) {
            assertEquals(productS0, assertNotNull(rollbackResponse).string("snapshotHash"))
        }
    }

    // 40
    @Then("`catalog-model` again contains its two exact original files, the complete `catalog-domain` hierarchy is absent, and only the workspace lock plus the one advanced journal record remain as expected engine residue")
    fun restoredModuleAndEngineResidueAreExact() {
        val oldFiles = Files.walk(workspaceRoot.resolve("catalog-model")).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { workspaceRoot.resolve("catalog-model").relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
        assertEquals(listOf("pom.xml", "src/main/java/com/acme/catalog/legacy/Product.java"), oldFiles)
        assertTrue(Files.notExists(workspaceRoot.resolve("catalog-domain"), LinkOption.NOFOLLOW_LINKS))
        val engineRoot = workspaceRoot.resolve(".refactorkit")
        val engineFiles = Files.walk(engineRoot).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { engineRoot.relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
        assertEquals(listOf("transactions/${transactionId}.json", "workspace.lock").sorted(), engineFiles)
        assertTrue(Files.notExists(engineRoot.resolve("transactions/.quarantine"), LinkOption.NOFOLLOW_LINKS))
    }

    // 41
    @Then("M0, every other disposable copy, the candidate archive, and the extracted subject image remain byte-identical and path-kind-identical to their pre-case identities")
    fun permanentSubjectsAndOtherCopiesRemainExact() {
        assertEquals(fixtureM0, captureTreeManifest(fixtureRoot))
        assertEquals(candidateBefore, captureTreeManifest(candidateRoot))
        assertEquals(boundArchiveSha256(), sha256(archive))
        assertTrue(QualificationRunRegistry.everyOtherCopyIsAbsent(surface.displayName))
        assertTrue(QualificationRunRegistry.copyRootsArePairwiseDistinct())
    }

    // 42
    @Then("no denied process, plugin, script, setting, credential helper, external Java, or network authority was requested, and all supervised processes, streams, descendants, temporary outputs, and copies satisfy their bounded cleanup contract")
    fun processAndAuthorityContractsAreClean() {
        persistentProcess?.let {
            processHarness.finishPersistent()
            persistentProcess = null
        }
        processHarness.assertAllCompletedCleanly()
        assertFalse(Files.exists(sandbox.marker, LinkOption.NOFOLLOW_LINKS))
        assertTrue(processHarness.processEvidence.all { it.embeddedJavaSha256 == embeddedJavaArchiveEvidence.sha256 })
        assertTrue(processHarness.processEvidence.all { it.socketInodes.isEmpty() })
        assertTrue(processHarness.processEvidence.all { evidence ->
            evidence.networkObservationMode.startsWith("jdk21-child-security-guard-fail-closed") &&
                evidence.networkObservationSucceeded && evidence.childGuard.networkDenied
        })
        assertTrue(processHarness.processEvidence.all { it.childGuard.expectedSecurityManagerWarningsObserved })
        assertTrue(processHarness.processEvidence.all { it.guardProcessMatchedEmbeddedJava })
        assertTrue(processHarness.processEvidence.all { it.childGuard.processExecDenied })
        assertTrue(processHarness.processEvidence.all { it.childGuard.outsideWriteDenied })
        assertTrue(processHarness.processEvidence.all { it.childGuard.readOnlyCandidateWriteDenied })
        assertTrue(processHarness.processEvidence.all { it.childGuard.readOnlyCandidateReportsNotWritable })
        assertTrue(processHarness.processEvidence.all { it.childGuard.installedRuntimeReadDenied })
        assertTrue(processHarness.transcripts.all { !it.stdoutTruncated && !it.stderrTruncated && !it.timedOut })
        processContractVerified = true
    }

    // 43
    @Then("the always-emitted operation-specific native evidence binds these exact identity classes and their SHA-256 values where byte-addressable:")
    fun evidenceBindsEveryIdentityClass(table: DataTable) {
        val expandedEvidenceTable = EVIDENCE_TABLE.map { row ->
            row.map { cell -> cell.replace("<surface>", surface.displayName) }
        }
        assertEquals(expandedEvidenceTable, normalizedTable(table))
        assertNotNull(transactionId)
        assertNotNull(productS0)
        assertNotNull(productS1)
        assertNotNull(normalizedForwardEditSha256)
        assertTrue(gateIdentityVerified)
        emitArtifacts("PASSED")
        val caseManifest = readJsonObject(manifestsDirectory.resolve("${surface.slug}-case-manifest.json"))
        val packageEvidence = caseManifest.getValue("package").jsonObject
        assertEquals(candidateArchiveIdentity.archiveSha256, packageEvidence.string("archiveSha256"))
        assertEquals(candidateArchiveIdentity.checksumSha256, packageEvidence.string("archiveChecksumSidecarSha256"))
        assertEquals(candidateArchiveIdentity.verifierRecordSha256, packageEvidence.string("verifierRecordSha256"))
        assertEquals(candidateArchiveIdentity.verifierCompleteLogSha256, packageEvidence.string("verifierCompleteLogSha256"))
        assertEquals(buildJdkIdentity.toJson(), packageEvidence.getValue("buildJdk"))
        assertEquals(candidateRuntimeIdentity.toJson(), packageEvidence.getValue("candidateRuntime"))
        assertTrue(reportsEmitted)
    }

    // 44
    @Then("the native job publishes the Cucumber JSON, JUnit XML, complete logs, manifest, archive checksum, and failure diagnostics under an always-run condition, and any absent or unreconciled artifact fails that host row")
    fun reportContractIsAlwaysMaterialized() {
        emitArtifacts("PASSED")
        val slug = surface.slug
        listOf(
            logsDirectory.resolve("$slug-complete.log"),
            logsDirectory.resolve("$slug-stdout.log"),
            logsDirectory.resolve("$slug-stderr.log"),
            manifestsDirectory.resolve("$slug-case-manifest.json"),
            failureDiagnosticsDirectory.resolve("$slug-failure-diagnostic.json"),
            manifestsDirectory.resolve("candidate-archive-checksum.json"),
            manifestsDirectory.resolve("candidate-extracted-no-follow-manifest.json"),
            manifestsDirectory.resolve("fixture-m0-no-follow-manifest.json"),
            manifestsDirectory.resolve("pairwise-disposable-copies.json"),
        ).forEach { path -> assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), path.toString()) }
        val checksumManifest = readJsonObject(manifestsDirectory.resolve("candidate-archive-checksum.json"))
        assertEquals(candidateArchiveIdentity.archiveSha256, checksumManifest.string("sha256"))
        assertEquals(candidateArchiveIdentity.checksumSha256, checksumManifest.string("checksumSha256"))
        assertEquals(candidateArchiveIdentity.verifierRecordSha256, checksumManifest.string("verifierRecordSha256"))
        val extractedManifest = readJsonObject(
            manifestsDirectory.resolve("candidate-extracted-no-follow-manifest.json"),
        )
        assertEquals(candidateArchiveIdentity.toJson(), extractedManifest.getValue("archiveIdentity"))
        assertEquals(buildJdkIdentity.toJson(), extractedManifest.getValue("buildJdk"))
        assertEquals(candidateRuntimeIdentity.toJson(), extractedManifest.getValue("candidateRuntime"))
    }

    // 45
    @Then("this scenario does not qualify or introduce any of these excluded scopes:")
    fun excludedScopesRemainExcluded(table: DataTable) {
        assertEquals(EXCLUDED_SCOPE_TABLE, normalizedTable(table))
        assertEquals(8, table.asMaps().size)
    }

    // 46
    @Then("no J1, support-matrix, package, native-host, or release row is promoted until the three cases pass independently on all four hosts and their four immutable native reports reconcile at one candidate revision")
    fun noStatusIsPromotedByRedHarness() {
        val feature = resolveRepositoryFile("features/java-maven-module-rename-packaged.feature")
        assertEquals(QualificationOracle.featureSha256, sha256(feature))
        val text = Files.readString(feature, StandardCharsets.UTF_8)
        assertTrue(text.contains("@REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 @functional-requirement @non-functional-requirement @absent"))
        assertFalse(text.contains("@REQ-JAVA-MAVEN-MODULE-RENAME-PACKAGED-001 @functional-requirement @non-functional-requirement @implemented-and-validated"))
        assertTrue(QualificationRunRegistry.registeredCopyCount() in 1..3)
        assertTrue(QualificationRunRegistry.copyRootsArePairwiseDistinct())
    }

    private fun previewThroughCli() {
        previewResult = processHarness.runOneShot(
            "cli-preview",
            renameCliArguments(apply = false),
            workspaceRoot,
        )
    }

    private fun previewThroughDaemon() {
        persistentProcess = processHarness.startNdjson(
            "daemon-session",
            "RefactorKit daemon ready (JSON-RPC / NDJSON)",
            workspaceRoot,
        )
        projectOpenResponse = daemonRequest(
            "project.open",
            buildJsonObject { put("root", workspaceRoot.toAbsolutePath().normalize().toString()) },
        ).jsonObject
        productS0 = assertNotNull(projectOpenResponse).string("snapshotHash")
        requestedOperation = "renameMavenModule"
        previewResponse = daemonRequest(
            "refactor.preview",
            buildJsonObject {
                put("operation", requestedOperation!!)
                put("languageId", "java")
                put("arguments", renameArgumentsJson())
            },
        ).jsonObject
        planId = assertNotNull(previewResponse).string("planId")
        assertEquals(productS0, assertNotNull(previewResponse)
            .getValue("snapshot").jsonObject.string("hash"))
    }

    private fun previewThroughMcp() {
        persistentProcess = processHarness.startNdjson(
            "mcp-session",
            "RefactorKit MCP server ready (protocol 2024-11-05)",
            workspaceRoot,
        )
        mcpInitializeResponse = mcpRequest(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", buildJsonObject {})
                put("clientInfo", buildJsonObject {
                    put("name", "refactorkit-packaged-module-rename-qualification")
                    put("version", "1")
                })
            },
        ).jsonObject
        assertEquals("2024-11-05", assertNotNull(mcpInitializeResponse).string("protocolVersion"))
        assertEquals(QualificationOracle.releaseVersion, assertNotNull(mcpInitializeResponse)
            .getValue("serverInfo").jsonObject.string("version"))
        assertNotNull(persistentProcess).notification("notifications/initialized", buildJsonObject {})
        val listed = mcpRequest("tools/list", buildJsonObject {}).jsonObject
        mcpTools = listed.getValue("tools").jsonArray.mapTo(linkedSetOf()) {
            it.jsonObject.string("name")
        }
        mcpTool("project_scan", buildJsonObject {
            put("root", workspaceRoot.toAbsolutePath().normalize().toString())
        })
        requestedOperation = QualificationOracle.operation
        mcpPreviewText = mcpTool("preview_refactoring", buildJsonObject {
            put("operation", requestedOperation!!)
            put("languageId", "java")
            put("arguments", renameArgumentsJson())
        })
        planId = Regex("Plan ID  :\\s*(\\S+)").find(assertNotNull(mcpPreviewText))?.groupValues?.get(1)
            ?: error("MCP preview omitted its retained plan ID")
    }

    private fun applyThroughCli() {
        applyResult = processHarness.runOneShot(
            "cli-apply",
            renameCliArguments(apply = true),
            workspaceRoot,
        )
        val result = assertNotNull(applyResult)
        assertSuccessful(result)
        transactionId = Regex("^Applied\\. Transaction: (transaction-[0-9a-f-]+)$", RegexOption.MULTILINE)
            .find(result.stdoutText())?.groupValues?.get(1)
            ?: error("CLI apply did not return one transaction ID")
    }

    private fun applyThroughDaemon() {
        val retainedPlan = assertNotNull(planId)
        applyResponse = daemonRequest(
            "refactor.apply",
            buildJsonObject { put("planId", retainedPlan) },
        ).jsonObject
        assertEquals("applied", assertNotNull(applyResponse).string("status"))
        assertEquals(retainedPlan, assertNotNull(applyResponse).string("planId"))
        transactionId = assertNotNull(applyResponse).string("transactionId")
        productS1 = assertNotNull(applyResponse).string("snapshotHash")
    }

    private fun applyThroughMcp() {
        mcpApplyText = mcpTool(
            "apply_refactoring",
            buildJsonObject { put("planId", assertNotNull(planId)) },
        )
        transactionId = Regex("Transaction ID: (transaction-[0-9a-f-]+)")
            .find(assertNotNull(mcpApplyText))?.groupValues?.get(1)
            ?: error("MCP apply omitted its transaction ID")
    }

    private fun rollbackThroughCli() {
        rollbackResult = processHarness.runOneShot(
            "cli-normal-rollback",
            listOf(
                "patch",
                "rollback",
                assertNotNull(transactionId),
                "--root",
                workspaceRoot.toAbsolutePath().normalize().toString(),
            ),
            workspaceRoot,
        )
        assertTrue(assertNotNull(rollbackResult).stdoutText()
            .contains("Rolled back transaction ${assertNotNull(transactionId)}."))
    }

    private fun rollbackThroughDaemon() {
        rollbackResponse = daemonRequest(
            "patch.rollback",
            buildJsonObject { put("transactionId", assertNotNull(transactionId)) },
        ).jsonObject
    }

    private fun rollbackThroughMcp() {
        mcpRollbackText = mcpTool(
            "rollback_refactoring",
            buildJsonObject { put("transactionId", assertNotNull(transactionId)) },
        )
    }

    private fun daemonRequest(method: String, params: JsonElement): JsonElement =
        assertNotNull(persistentProcess).request(nextRequestId("daemon", method), method, params)

    private fun mcpRequest(method: String, params: JsonElement): JsonElement =
        assertNotNull(persistentProcess).request(nextRequestId("mcp", method), method, params)

    private fun mcpTool(name: String, arguments: JsonObject): String {
        val result = mcpRequest(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        ).jsonObject
        assertFalse(result["isError"]?.jsonPrimitive?.content == "true", "MCP tool $name failed: $result")
        return result.getValue("content").jsonArray.joinToString("\n") { content ->
            content.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
        }
    }

    private fun nextRequestId(protocol: String, method: String): String {
        requestSequence += 1
        val methodToken = method.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
        return "${surface.slug}-$protocol-$methodToken-${requestSequence.toString().padStart(2, '0')}"
    }

    private fun renameCliArguments(apply: Boolean): List<String> = buildList {
        addAll(listOf(
            "java",
            "rename-module",
            "--old-module-dir",
            QualificationOracle.request.oldModuleDir,
            "--new-module-dir",
            QualificationOracle.request.newModuleDir,
            "--new-artifact-id",
            QualificationOracle.request.newArtifactId,
            "--root",
            workspaceRoot.toAbsolutePath().normalize().toString(),
        ))
        if (apply) add("--apply")
    }

    private fun renameArgumentsJson(): JsonObject = buildJsonObject {
        put("oldModuleDir", QualificationOracle.request.oldModuleDir)
        put("newModuleDir", QualificationOracle.request.newModuleDir)
        put("newArtifactId", QualificationOracle.request.newArtifactId)
    }

    private fun inspectSoleJournal(expectedState: String): JsonObject {
        val paths = journalPaths()
        assertEquals(1, paths.size, "Expected exactly one schema-v8 journal record")
        val journal = readJsonObject(paths.single())
        assertEquals(8, journal.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(expectedState, journal.string("state"))
        return journal
    }

    private fun journalPaths(): List<Path> {
        val directory = workspaceRoot.resolve(".refactorkit/transactions")
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
    }

    private fun validateJournalCore(journal: JsonObject, state: String) {
        assertEquals(8, journal.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(QualificationOracle.releaseVersion, journal.string("implementationVersion"))
        assertEquals(QualificationOracle.operation, journal.string("operation"))
        assertEquals(state, journal.string("state"))
        val transaction = journal.getValue("transaction").jsonObject
        assertEquals(assertNotNull(transactionId), transaction.string("id"))
        val approval = transaction.getValue("approval").jsonObject
        assertEquals("EXPLICIT_APPLY", approval.string("kind"))
        assertEquals(surface.approvalSurface, approval.string("surface"))
        assertEquals("caller", approval.string("actor"))
        productS0 = journal.string("preSnapshotHash")
        productS1 = journal.string("postSnapshotHash")
        assertEquals(productS0, transaction.string("snapshotHashBefore"))
        assertTrue(assertNotNull(productS0).matches(SHA256))
        assertTrue(assertNotNull(productS1).matches(SHA256))
        assertNotEquals(productS0, productS1)
        if (surface == Surface.DAEMON) {
            assertEquals(assertNotNull(projectOpenResponse).string("snapshotHash"), productS0)
            assertEquals(assertNotNull(applyResponse).string("snapshotHash"), productS1)
            assertEquals(assertNotNull(planId), transaction.string("planId"))
        }
    }

    private fun validateForwardEdit(forward: JsonObject) {
        assertEquals(setOf("edits"), forward.keys)
        val edits = forward.getValue("edits").jsonArray
        assertEquals(5, edits.size)
        edits.zip(QualificationOracle.edits).forEach { (element, expected) ->
            val edit = element.jsonObject
            when (expected.kind) {
                "Modify" -> {
                    assertEquals(setOf("type", "path", "textEdits"), edit.keys)
                    assertEquals("modify", edit.string("type"))
                    assertEquals(expected.path, edit.string("path"))
                    val textEdits = edit.getValue("textEdits").jsonArray
                    assertEquals(1, textEdits.size)
                    val text = textEdits.single().jsonObject
                    assertEquals(
                        setOf("startLine", "startChar", "endLine", "endChar", "newText"),
                        text.keys,
                    )
                    val expectedRange = parseRange(expected.range)
                    assertEquals(expectedRange[0], text.getValue("startLine").jsonPrimitive.int)
                    assertEquals(expectedRange[1], text.getValue("startChar").jsonPrimitive.int)
                    assertEquals(expectedRange[2], text.getValue("endLine").jsonPrimitive.int)
                    assertEquals(expectedRange[3], text.getValue("endChar").jsonPrimitive.int)
                    assertEquals(expected.newText, text.string("newText"))
                }
                "Rename" -> {
                    assertEquals(setOf("type", "path", "newPath"), edit.keys)
                    assertEquals("rename", edit.string("type"))
                    assertEquals(expected.path, edit.string("path"))
                    assertEquals(expected.newPath, edit.string("newPath"))
                }
                else -> error("Unexpected edit kind ${expected.kind}")
            }
        }
    }

    private fun validateJournalImages(journal: JsonObject) {
        val preImages = journal.getValue("preImages").jsonArray.map { it.jsonObject }
        val postImages = journal.getValue("postImages").jsonArray.map { it.jsonObject }
        assertEquals(QualificationOracle.affectedPaths.toSet(), preImages.mapTo(linkedSetOf()) { it.string("path") })
        assertEquals(QualificationOracle.affectedPaths.toSet(), postImages.mapTo(linkedSetOf()) { it.string("path") })
        val s0Entries = workspaceOracle.s0Manifest.entries
        val c1Entries = workspaceOracle.c1Manifest.entries
        validateImageList(preImages, workspaceOracle.s0Bytes, s0Entries)
        validateImageList(postImages, workspaceOracle.c1Bytes, c1Entries)
    }

    private fun validateImageList(
        images: List<JsonObject>,
        expectedBytes: Map<String, ByteArray>,
        expectedEntries: Map<String, ManifestEntry>,
    ) {
        images.forEach { image ->
            val path = image.string("path")
            val bytes = expectedBytes[path]
            if (bytes == null) {
                assertEquals(setOf("path"), image.keys)
            } else {
                assertEquals(sha256(bytes), image.string("contentSha256"))
                assertContentEquals(bytes, image.string("content").toByteArray(StandardCharsets.UTF_8))
                val expectedMode = expectedEntries.getValue(path).mode
                if (expectedMode != null) {
                    assertEquals(
                        permissionNames(expectedMode),
                        image.getValue("posixPermissions").jsonArray.strings().toSet(),
                    )
                }
            }
        }
    }

    private fun history(journal: JsonObject): List<String> = journal.getValue("history").jsonArray
        .map { it.jsonObject.string("state") }

    private fun assertStructuredDiff(diff: JsonArray) {
        assertEquals(5, diff.size)
        diff.zip(QualificationOracle.edits).forEach { (element, expected) ->
            val item = element.jsonObject
            if (expected.kind == "Modify") {
                assertEquals(setOf("type", "path"), item.keys)
                assertEquals("modifyFile", item.string("type"))
                assertEquals(expected.path, item.string("path"))
            } else {
                assertEquals(setOf("type", "path", "newPath"), item.keys)
                assertEquals("renameFile", item.string("type"))
                assertEquals(expected.path, item.string("path"))
                assertEquals(expected.newPath, item.string("newPath"))
            }
        }
    }

    private fun parseCliAffectedPaths(stdout: String): Set<String> {
        val section = stdout.substringAfter("Affected files:\n").substringBefore("\n\nPatch:\n")
        val lines = section.lines().filter(String::isNotBlank)
        assertTrue(lines.all { it.startsWith("- ") })
        return lines.mapTo(linkedSetOf()) { it.removePrefix("- ") }
    }

    private fun parseMcpAffectedPaths(text: String): Set<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("Affected : ") }
        assertTrue(start >= 0)
        return lines.drop(start + 1).takeWhile { it.startsWith("  ") }
            .mapTo(linkedSetOf()) { it.trim() }
    }

    private fun assertSuccessful(result: BoundedProcessResult) {
        assertFalse(result.timedOut, result.label)
        assertFalse(result.stdoutTruncated, result.label)
        assertFalse(result.stderrTruncated, result.label)
        assertEquals(0, result.exitCode, result.stderrText())
    }

    private fun assertCliDiagnosticsClean(result: BoundedProcessResult) {
        assertSuccessful(result)
        assertEquals("No diagnostics.\n", result.stdoutText())
        assertFalse(result.stderrText().contains("ERROR", ignoreCase = true))
    }

    private fun readWorkspaceNonEngineBytes(): Map<String, ByteArray> {
        val manifest = captureTreeManifest(workspaceRoot).withoutEngine()
        return manifest.entries.filterValues { it.kind == ManifestPathKind.REGULAR_FILE }
            .mapValues { (path, _) -> Files.readAllBytes(workspaceRoot.resolve(path)) }
    }

    private fun expectedJournalTable(state: String): List<List<String>> = listOf(
        listOf("field", "exact value"),
        listOf("schemaVersion", "integer literal 8"),
        listOf("operation", QualificationOracle.operation),
        listOf("state", state),
        listOf("approval", "EXPLICIT_APPLY, ${surface.approvalSurface}, caller"),
        listOf("forwardEdit", "the exact normalized five-entry WorkspaceEdit above"),
        listOf("snapshot identities", "pre-image S0 and authoritative post-image S1"),
        listOf("images", "the independently retained complete pre-images, post-images, permissions, and created-directory facts"),
        listOf("ordered history", "PREPARED, APPLYING, APPLIED"),
    )

    private fun emitArtifacts(status: String) {
        if (!::logsDirectory.isInitialized) return
        if (status == "PASSED") {
            check(::candidateArchiveIdentity.isInitialized) { "Passing evidence omitted candidate archive identity" }
            check(::buildJdkIdentity.isInitialized) { "Passing evidence omitted exact build JDK identity" }
            check(::candidateRuntimeIdentity.isInitialized) { "Passing evidence omitted candidate runtime identity" }
            check(::candidateArchiveProbeEvidence.isInitialized) { "Passing evidence omitted archive identity probe" }
            check(::processMonitorProbeEvidence.isInitialized) { "Passing evidence omitted process monitor probe" }
            check(s1ObservedBeforeJournal) { "Passing evidence inspected the journal before independent S1" }
        }
        Files.createDirectories(logsDirectory)
        Files.createDirectories(manifestsDirectory)
        Files.createDirectories(failureDiagnosticsDirectory)
        val transcripts = if (::processHarness.isInitialized) processHarness.transcripts else emptyList()
        val stdoutText = buildString {
            transcripts.forEach { transcript ->
                appendLine("===== ${transcript.label} stdout =====")
                append(sanitize(decodeUtf8Strict(transcript.stdout, "${transcript.label} stdout")))
                if (isNotEmpty() && !endsWith('\n')) appendLine()
            }
        }
        val stderrText = buildString {
            transcripts.forEach { transcript ->
                appendLine("===== ${transcript.label} stderr =====")
                append(sanitize(decodeUtf8Strict(transcript.stderr, "${transcript.label} stderr")))
                if (isNotEmpty() && !endsWith('\n')) appendLine()
            }
            startFailure?.let {
                appendLine("===== direct-start-refusal =====")
                appendLine(sanitize("${it::class.java.name}: ${it.message}"))
            }
        }
        val stdoutPath = logsDirectory.resolve("${surface.slug}-stdout.log")
        val stderrPath = logsDirectory.resolve("${surface.slug}-stderr.log")
        writeUtf8(stdoutPath, stdoutText)
        writeUtf8(stderrPath, stderrText)
        val completePath = logsDirectory.resolve("${surface.slug}-complete.log")
        val complete = buildString {
            appendLine("requirement=${QualificationOracle.requirementId}")
            appendLine("surface=${surface.displayName}")
            appendLine("status=$status")
            appendLine("host=${if (::host.isInitialized) "${host.operatingSystem}/${host.architecture}" else "unbound"}")
            appendLine("repositoryCommit=${if (::repositoryIdentity.isInitialized) repositoryIdentity.commit else "unavailable"}")
            appendLine("repositoryTree=${if (::repositoryIdentity.isInitialized) repositoryIdentity.tree else "unavailable"}")
            appendLine("repositoryWorktreeState=${if (::repositoryIdentity.isInitialized) repositoryIdentity.worktreeState else "unavailable"}")
            appendLine("repositoryStatusSha256=${if (::repositoryIdentity.isInitialized) repositoryIdentity.statusSha256 else "unavailable"}")
            appendLine("qualificationMode=${if (::repositoryIdentity.isInitialized) repositoryIdentity.qualificationMode else "unavailable"}")
            appendLine("archiveSha256=${if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.archiveSha256 else "unavailable"}")
            appendLine("checksumSha256=${if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.checksumSha256 else "unavailable"}")
            appendLine("verifierRecordSha256=${if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.verifierRecordSha256 else "unavailable"}")
            appendLine("verifierCompleteLogSha256=${if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.verifierCompleteLogSha256 else "unavailable"}")
            appendLine("buildJdkVendor=${if (::buildJdkIdentity.isInitialized) buildJdkIdentity.vendor else "unavailable"}")
            appendLine("buildJdkVersion=${if (::buildJdkIdentity.isInitialized) buildJdkIdentity.version else "unavailable"}")
            appendLine("buildJdkRuntimeReleaseSha256=${if (::buildJdkIdentity.isInitialized) buildJdkIdentity.releaseSha256 else "unavailable"}")
            appendLine("buildJdkRuntimeJavaSha256=${if (::buildJdkIdentity.isInitialized) buildJdkIdentity.javaExecutableSha256 else "unavailable"}")
            appendLine("buildJdkRuntimeModulesSha256=${if (::buildJdkIdentity.isInitialized) buildJdkIdentity.modulesImageSha256 else "unavailable"}")
            appendLine("candidateRuntimeReleaseSha256=${if (::candidateRuntimeIdentity.isInitialized) candidateRuntimeIdentity.releaseSha256 else "unavailable"}")
            appendLine("candidateRuntimeModulesSha256=${if (::candidateRuntimeIdentity.isInitialized) candidateRuntimeIdentity.modulesImageSha256 else "unavailable"}")
            appendLine("archiveIdentityProbeSha256=${if (::candidateArchiveProbeEvidence.isInitialized) canonicalJsonSha256(candidateArchiveProbeEvidence.toJson()) else "unavailable"}")
            appendLine("processMonitorProbeSha256=${if (::processMonitorProbeEvidence.isInitialized) canonicalJsonSha256(processMonitorProbeEvidence.toJson()) else "unavailable"}")
            appendLine("launcherMode=${if (::launcherArchiveEvidence.isInitialized) launcherArchiveEvidence.mode else "unavailable"}")
            appendLine("launcherSha256=${if (::launcherArchiveEvidence.isInitialized) launcherArchiveEvidence.sha256 else "unavailable"}")
            appendLine("embeddedJavaSha256=${if (::embeddedJavaArchiveEvidence.isInitialized) embeddedJavaArchiveEvidence.sha256 else "unavailable"}")
            appendLine("candidateReadOnly=${if (::candidateReadOnlyEvidence.isInitialized) candidateReadOnlyEvidence.allRegularFilesReadOnly && candidateReadOnlyEvidence.allDirectoriesReadOnly else false}")
            appendLine("jvmJarSha256=${if (::gateSelectorEvidence.isInitialized) gateSelectorEvidence.jvmJarSha256 else "unavailable"}")
            appendLine("gateMappingProofSha256=${if (::gateSelectorEvidence.isInitialized) gateSelectorEvidence.operationToGateMappingProofSha256 else "unavailable"}")
            appendLine("copyIdentity=${if (::copyIdentity.isInitialized) copyIdentity else "unavailable"}")
            appendLine("S0=${if (::workspaceOracle.isInitialized) workspaceOracle.s0Identity else "unavailable"}")
            appendLine("C1=${if (::workspaceOracle.isInitialized) workspaceOracle.c1Manifest.sha256 else "unavailable"}")
            appendLine("S1=${if (::workspaceOracle.isInitialized) workspaceOracle.s1Identity else "unavailable"}")
            appendLine("productS0=${productS0 ?: "unavailable"}")
            appendLine("productS1=${productS1 ?: "unavailable"}")
            appendLine("transactionId=${transactionId ?: "unavailable"}")
            appendLine("normalizedForwardEditSha256=${normalizedForwardEditSha256 ?: "unavailable"}")
            appendLine("gateIdentityVerified=$gateIdentityVerified")
            appendLine("cleanupVerified=$cleanupVerified")
            appendLine("failure=${failureDiagnostic ?: cleanupProblems.joinToString(" | ").ifBlank { "none" }}")
            appendLine()
            append(stdoutText)
            append(stderrText)
        }
        writeUtf8(completePath, complete)

        val processEvidence = if (::processHarness.isInitialized) processHarness.processEvidence else emptyList()
        val failureDiagnosticPath = failureDiagnosticsDirectory.resolve("${surface.slug}-failure-diagnostic.json")
        writeJson(
            failureDiagnosticPath,
            buildJsonObject {
                put("requirementId", QualificationOracle.requirementId)
                put("surface", surface.displayName)
                put("status", status)
                if (failureDiagnostic == null) {
                    put("firstFailure", JsonNull)
                } else {
                    put("firstFailure", failureDiagnostic!!)
                }
                put(
                    "startFailure",
                    startFailure?.let { JsonPrimitive(sanitize("${it::class.java.name}: ${it.message}")) } ?: JsonNull,
                )
                if (::launcherArchiveEvidence.isInitialized) {
                    put("launcherArchiveMode", launcherArchiveEvidence.mode)
                    put("launcherExtractedMode", launcherExtractedMode)
                    put("launcherExecuteBits", modeHasExecute(launcherArchiveEvidence.mode))
                }
                put("cleanupProblems", jsonStrings(cleanupProblems))
                put("cleanupVerified", cleanupVerified)
            },
        )
        val stableRunLevelHashInputs = buildJsonObject {
            put("requirementId", QualificationOracle.requirementId)
            put("surface", surface.displayName)
            put("status", status)
            put("revision", if (::repositoryIdentity.isInitialized) repositoryIdentity.toJson() else buildJsonObject {
                put("status", "UNAVAILABLE")
            })
            if (::host.isInitialized) put("nativeRunner", host.toJson())
            put("archiveName", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.archiveName else archive.fileName.toString())
            put("archiveSha256", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.archiveSha256 else "UNAVAILABLE")
            put("archiveChecksumSidecarSha256", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.checksumSha256 else "UNAVAILABLE")
            put("verifierPlatform", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.verifierPlatform else "UNAVAILABLE")
            put("verifierRecordSha256", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.verifierRecordSha256 else "UNAVAILABLE")
            put("verifierCompleteLogSha256", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.verifierCompleteLogSha256 else "UNAVAILABLE")
            put("buildJdk", if (::buildJdkIdentity.isInitialized) buildJdkIdentity.toJson() else buildJsonObject {
                put("status", "UNAVAILABLE")
            })
            put("candidateRuntime", if (::candidateRuntimeIdentity.isInitialized) candidateRuntimeIdentity.toJson() else buildJsonObject {
                put("status", "UNAVAILABLE")
            })
            put("archiveIdentityProbe", if (::candidateArchiveProbeEvidence.isInitialized) {
                candidateArchiveProbeEvidence.toJson()
            } else {
                buildJsonObject { put("status", "UNAVAILABLE") }
            })
            put("processMonitorProbe", if (::processMonitorProbeEvidence.isInitialized) {
                processMonitorProbeEvidence.toJson()
            } else {
                buildJsonObject { put("status", "UNAVAILABLE") }
            })
            put("extractedTreeSha256", if (::candidateBefore.isInitialized) candidateBefore.sha256 else "UNAVAILABLE")
            put("readOnlyAttestationSha256", if (::candidateReadOnlyEvidence.isInitialized) {
                canonicalJsonSha256(candidateReadOnlyEvidence.toJson())
            } else {
                "UNAVAILABLE"
            })
            put("jvmJarSha256", if (::gateSelectorEvidence.isInitialized) gateSelectorEvidence.jvmJarSha256 else "UNAVAILABLE")
            put(
                "operationToGateMappingProofSha256",
                if (::gateSelectorEvidence.isInitialized) {
                    gateSelectorEvidence.operationToGateMappingProofSha256
                } else {
                    "UNAVAILABLE"
                },
            )
            put("featureSha256", QualificationOracle.featureSha256)
            put("oracleResourceSha256", QualificationOracle.resourceSha256)
            put("forwardEditOracleSha256", canonicalJsonSha256(normalizedForwardEditOracle()))
            put("M0", if (::fixtureM0.isInitialized) fixtureM0.sha256 else "UNAVAILABLE")
            put("copyIdentity", if (::copyIdentity.isInitialized) copyIdentity else "UNAVAILABLE")
            put("independentS0", if (::workspaceOracle.isInitialized) workspaceOracle.s0Identity else "UNAVAILABLE")
            put("D0", if (::workspaceOracle.isInitialized) diagnosticMultisetSha256(workspaceOracle.d0) else "UNAVAILABLE")
            put("C1", if (::workspaceOracle.isInitialized) workspaceOracle.c1Manifest.sha256 else "UNAVAILABLE")
            put("independentS1", if (::workspaceOracle.isInitialized) workspaceOracle.s1Identity else "UNAVAILABLE")
            put("transactionId", transactionId ?: "UNAVAILABLE")
            put("transactionSchemaVersion", 8)
            put("transactionOperation", QualificationOracle.operation)
            put("approvalSurface", surface.approvalSurface)
            put("normalizedForwardEditSha256", normalizedForwardEditSha256 ?: "UNAVAILABLE")
            put("authoritativeS0", productS0 ?: "UNAVAILABLE")
            put("authoritativeS1", productS1 ?: "UNAVAILABLE")
            put("processCount", processEvidence.size)
            put("processGuardEvidenceSha256", canonicalJsonSha256(buildJsonArray {
                processEvidence.forEach { evidence ->
                    add(buildJsonObject {
                        put("embeddedJavaSha256", evidence.embeddedJavaSha256)
                        put("guardProcessMatchedEmbeddedJava", evidence.guardProcessMatchedEmbeddedJava)
                        put("networkObservationMode", evidence.networkObservationMode)
                        put("networkObservationSucceeded", evidence.networkObservationSucceeded)
                        put("childSecurityGuard", evidence.childGuard.toJson())
                    })
                }
            }))
            put(
                "appliedHistory",
                appliedJournal?.let(::history)?.let(::jsonStrings) ?: jsonStrings(emptyList()),
            )
            put(
                "finalHistory",
                rolledBackJournal?.let(::history)?.let(::jsonStrings) ?: jsonStrings(emptyList()),
            )
            put("completeLogSha256", sha256(completePath))
            put("stdoutLogSha256", sha256(stdoutPath))
            put("stderrLogSha256", sha256(stderrPath))
            put("failureDiagnosticSha256", sha256(failureDiagnosticPath))
            put("cucumberJsonArtifact", "packaged-maven-module-rename-qualification.json")
            put("junitXmlArtifact", "TEST-org.refactorkit.cli.packagedmavenmodulerename.PackagedJavaMavenModuleRenameQualificationCucumberTest.xml")
            put("runLevelBindingOwner", "build-ci")
        }
        val caseManifest = buildJsonObject {
            put("requirementId", QualificationOracle.requirementId)
            put("scenarioOutline", "<surface> renames the proven module through the extracted runtime and restores its own workspace")
            put("surface", surface.displayName)
            put("status", status)
            put("revision", buildJsonObject {
                if (::repositoryIdentity.isInitialized) {
                    repositoryIdentity.toJson().forEach { (name, value) -> put(name, value) }
                } else {
                    put("commit", "UNAVAILABLE")
                    put("tree", "UNAVAILABLE")
                    put("worktreeState", "UNAVAILABLE")
                    put("statusSha256", "UNAVAILABLE")
                    put("qualificationMode", "UNAVAILABLE")
                }
                put("version", QualificationOracle.releaseVersion)
                put("historicalOracleVersion", QualificationOracle.version)
            })
            if (::host.isInitialized) put("nativeRunner", host.toJson())
            put("package", buildJsonObject {
                put("archiveName", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.archiveName else archive.fileName.toString())
                put("archiveSha256", if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.archiveSha256 else "unavailable")
                put(
                    "archiveChecksumSidecarSha256",
                    if (::candidateArchiveIdentity.isInitialized) candidateArchiveIdentity.checksumSha256 else "unavailable",
                )
                if (::candidateArchiveIdentity.isInitialized) {
                    put("verifierPlatform", candidateArchiveIdentity.verifierPlatform)
                    put("verifierRecordSha256", candidateArchiveIdentity.verifierRecordSha256)
                    put("verifierCompleteLogSha256", candidateArchiveIdentity.verifierCompleteLogSha256)
                }
                if (::buildJdkIdentity.isInitialized) put("buildJdk", buildJdkIdentity.toJson())
                if (::candidateRuntimeIdentity.isInitialized) put("candidateRuntime", candidateRuntimeIdentity.toJson())
                if (::candidateBefore.isInitialized) put("extractedTreeSha256", candidateBefore.sha256)
                if (::candidateReadOnlyEvidence.isInitialized) {
                    put("readOnlyAttestation", candidateReadOnlyEvidence.toJson())
                }
                if (::gateSelectorEvidence.isInitialized) {
                    put("gateSelector", gateSelectorEvidence.toJson())
                }
                if (::launcherArchiveEvidence.isInitialized) {
                    put("launcherPath", "bin/${launcher.fileName}")
                    put("launcherMode", launcherArchiveEvidence.mode)
                    put("launcherSha256", launcherArchiveEvidence.sha256)
                }
                if (::embeddedJavaArchiveEvidence.isInitialized) {
                    put("embeddedJavaPath", if (::host.isInitialized && host.operatingSystem == "Windows") "runtime/bin/java.exe" else "runtime/bin/java")
                    put("embeddedJavaMode", embeddedJavaArchiveEvidence.mode)
                    put("embeddedJavaSha256", embeddedJavaArchiveEvidence.sha256)
                }
            })
            put("requirement", buildJsonObject {
                put("featureSha256", QualificationOracle.featureSha256)
                put("oracleResourceSha256", QualificationOracle.resourceSha256)
                put("expandedExample", surface.displayName)
                put("canonicalOperation", QualificationOracle.operation)
                put("request", buildJsonObject {
                    put("oldModuleDir", QualificationOracle.request.oldModuleDir)
                    put("newModuleDir", QualificationOracle.request.newModuleDir)
                    put("newArtifactId", QualificationOracle.request.newArtifactId)
                })
                put("fiveEditOracleSha256", canonicalJsonSha256(normalizedForwardEditOracle()))
            })
            put("fixture", buildJsonObject {
                if (::fixtureM0.isInitialized) put("M0", fixtureM0.sha256)
                if (::copyIdentity.isInitialized) put("copyIdentity", copyIdentity)
                if (::workspaceOracle.isInitialized) {
                    put("S0", workspaceOracle.s0Identity)
                    put("D0", diagnosticMultisetSha256(workspaceOracle.d0))
                    put("C1", workspaceOracle.c1Manifest.sha256)
                    put("S1", workspaceOracle.s1Identity)
                    put("sourceInventorySha256", canonicalJsonSha256(jsonStrings(workspaceOracle.sourceInventory.sorted())))
                    put("auxiliaryInventorySha256", canonicalJsonSha256(jsonStrings(workspaceOracle.auxiliaryInventory.sorted())))
                }
                put("pairwiseRootCount", QualificationRunRegistry.registeredCopyCount())
                put("pairwiseRootsDistinct", QualificationRunRegistry.copyRootsArePairwiseDistinct())
            })
            put("transaction", buildJsonObject {
                put("schemaVersion", 8)
                put("id", transactionId ?: "UNAVAILABLE")
                put("operation", QualificationOracle.operation)
                put("publicCanonicalOperation", publicCanonicalOperationObserved ?: "UNAVAILABLE")
                put("approval", buildJsonObject {
                    put("kind", "EXPLICIT_APPLY")
                    put("surface", surface.approvalSurface)
                    put("actor", "caller")
                })
                put("normalizedForwardEditSha256", normalizedForwardEditSha256 ?: "UNAVAILABLE")
                put("forwardEditOracleSha256", canonicalJsonSha256(normalizedForwardEditOracle()))
                put("authoritativeSnapshotS0", productS0 ?: "UNAVAILABLE")
                put("authoritativeSnapshotS1", productS1 ?: "UNAVAILABLE")
                if (::workspaceOracle.isInitialized) {
                    put("independentTreeS0", workspaceOracle.s0Identity)
                    put("independentTreeS1", workspaceOracle.s1Identity)
                    put("D0", diagnosticMultisetSha256(workspaceOracle.d0))
                } else {
                    put("independentTreeS0", "UNAVAILABLE")
                    put("independentTreeS1", "UNAVAILABLE")
                    put("D0", "UNAVAILABLE")
                }
                put(
                    "appliedHistory",
                    appliedJournal?.let(::history)?.let(::jsonStrings) ?: jsonStrings(emptyList()),
                )
                put(
                    "finalHistory",
                    rolledBackJournal?.let(::history)?.let(::jsonStrings) ?: jsonStrings(emptyList()),
                )
                put("gateIdentityVerified", gateIdentityVerified)
                put("s1ObservedBeforeJournal", s1ObservedBeforeJournal)
                put("gateIdentitySource", "hash-bound-packaged-selector-bytecode-plus-public-operation-and-lifecycle")
                put("journalGateIdentityClaimed", false)
            })
            put("contractProbes", buildJsonObject {
                if (::candidateArchiveProbeEvidence.isInitialized) {
                    put("archiveIdentity", candidateArchiveProbeEvidence.toJson())
                }
                if (::processMonitorProbeEvidence.isInitialized) {
                    put("processObservationMonitor", processMonitorProbeEvidence.toJson())
                }
            })
            put("process", buildJsonArray {
                processEvidence.forEachIndexed { index, evidence ->
                    add(buildJsonObject {
                        put("label", transcripts.getOrNull(index)?.label ?: "process-${index + 1}")
                        put("embeddedJavaObserved", evidence.embeddedJavaObserved)
                        put("embeddedJavaSha256", evidence.embeddedJavaSha256)
                        put("guardProcessMatchedEmbeddedJava", evidence.guardProcessMatchedEmbeddedJava)
                        put("observedCommands", jsonStrings(evidence.observedCommands.sorted()))
                        put("deniedCommands", jsonStrings(evidence.deniedCommands.sorted()))
                        put("unexpectedDescendantCommands", jsonStrings(evidence.unexpectedDescendantCommands.sorted()))
                        put("networkObservationMode", evidence.networkObservationMode)
                        put("networkObservationSucceeded", evidence.networkObservationSucceeded)
                        put("linuxSocketObservationSucceeded", evidence.linuxSocketObservationSucceeded)
                        put("socketInodes", jsonStrings(evidence.socketInodes.sorted()))
                        put("maximumResidentBytes", evidence.maximumResidentBytes)
                        put("monitorFailure", evidence.monitorFailure?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("childSecurityGuard", evidence.childGuard.toJson())
                    })
                }
            })
            put("stableRunLevelHashInputs", stableRunLevelHashInputs)
            put("stableRunLevelHashInputsSha256", canonicalJsonSha256(stableRunLevelHashInputs))
            put("reports", buildJsonObject {
                put("completeLogSha256", sha256(completePath))
                put("stdoutLogSha256", sha256(stdoutPath))
                put("stderrLogSha256", sha256(stderrPath))
                put("failureDiagnosticSha256", sha256(failureDiagnosticPath))
                put(
                    "candidateArchiveChecksumManifestSha256",
                    manifestsDirectory.resolve("candidate-archive-checksum.json").takeIf {
                        Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
                    }?.let(::sha256) ?: "UNAVAILABLE",
                )
                put("cucumberJsonRunLevelSha256Status", "BUILD_CI_OWNED_AFTER_TEST_COMPLETION")
                put("junitXmlRunLevelSha256Status", "BUILD_CI_OWNED_AFTER_TEST_COMPLETION")
                put("archiveChecksumRunLevelSha256Status", "BUILD_CI_OWNED_AFTER_TEST_COMPLETION")
                put("failureDiagnosticsRunLevelSha256Status", "BUILD_CI_OWNED_AFTER_TEST_COMPLETION")
                put("completeRunManifestStatus", "BUILD_CI_OWNED_AFTER_HOST_ARTIFACT_RECONCILIATION")
                put("cleanupVerified", cleanupVerified)
            })
        }
        writeJson(manifestsDirectory.resolve("${surface.slug}-case-manifest.json"), caseManifest)
        QualificationRunRegistry.writePairwiseReport(manifestsDirectory)
        reportsEmitted = true
        runCatching {
            scenario.attach(
                "surface=${surface.displayName}; status=$status; manifest=${sha256(manifestsDirectory.resolve("${surface.slug}-case-manifest.json"))}",
                "text/plain",
                "packaged-module-rename-evidence",
            )
        }
    }

    private fun sanitize(value: String): String {
        val replacements = buildList {
            if (::workspaceRoot.isInitialized) add(workspaceRoot.toAbsolutePath().normalize().toString() to "<disposable-workspace>")
            if (::caseContainer.isInitialized) add(caseContainer.toAbsolutePath().normalize().toString() to "<case-container>")
            if (::candidateRoot.isInitialized) add(candidateRoot.toAbsolutePath().normalize().toString() to "<candidate-root>")
            if (::repositoryRoot.isInitialized) add(repositoryRoot.toAbsolutePath().normalize().toString() to "<repository-root>")
            if (::sandbox.isInitialized) {
                add(sandbox.home.toAbsolutePath().normalize().toString() to "<isolated-home>")
                add(sandbox.temporary.toAbsolutePath().normalize().toString() to "<isolated-temp>")
                add(sandbox.root.toAbsolutePath().normalize().toString() to "<subject-sandbox>")
                add(sandbox.installedTestRuntime.toAbsolutePath().normalize().toString() to "<installed-test-runtime>")
            }
        }.sortedByDescending { it.first.length }
        return replacements.fold(value) { text, (path, token) -> text.replace(path, token) }
    }

    private fun boundArchiveSha256(): String {
        check(::candidateArchiveIdentity.isInitialized) { "Candidate archive identity was not bound" }
        return candidateArchiveIdentity.archiveSha256
    }

    private fun qualificationTemporaryRoot(): Path {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val configured = buildList {
            System.getenv("RUNNER_TEMP")?.let(::add)
            System.getenv("TMPDIR")?.let(::add)
            System.getenv("TEMP")?.let(::add)
            if (!windows) add("/tmp")
        }.map { Path.of(it).toAbsolutePath().normalize() }
            .firstOrNull { !it.startsWith(repositoryRoot) }
            ?: error("Qualification requires a temporary root outside the source/build workspace")
        Files.createDirectories(configured)
        require(Files.isDirectory(configured, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(configured))
        return configured
    }

    private fun requiredPropertyPath(name: String): Path {
        val value = System.getProperty(name) ?: error("Missing qualification system property $name")
        val path = Path.of(value)
        require(path.isAbsolute) { "$name must be absolute" }
        return path.toAbsolutePath().normalize()
    }

    private fun resolveRepositoryFile(relative: String): Path {
        val candidate = repositoryRoot.resolve(relative).normalize()
        require(candidate.startsWith(repositoryRoot)) { "Repository evidence path escaped the repository" }
        require(Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) { "Repository evidence file is missing" }
        require(!Files.isSymbolicLink(candidate)) { "Repository evidence symbolic link refused" }
        return candidate
    }

    private fun writeUtf8(path: Path, text: String) {
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            text,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun normalizedTable(table: DataTable): List<List<String>> = table.cells().map { row ->
        row.map { cell -> cell ?: "" }
    }

    private fun parseRange(value: String): List<Int> = Regex("(\\d+):(\\d+)-(\\d+):(\\d+)")
        .matchEntire(value)?.groupValues?.drop(1)?.map(String::toInt)
        ?: error("Invalid oracle range $value")

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private data class HostIdentity(
        val operatingSystem: String,
        val architecture: String,
        val runner: String,
        val job: String,
        val run: String,
        val attempt: String,
    ) {
        val verifierPlatform: String
            get() = when (operatingSystem to architecture) {
                "Linux" to "x86-64" -> "linux-x86_64"
                "Windows" to "x86-64" -> "windows-x86_64"
                "macOS" to "x86-64" -> "macos-x86_64"
                "macOS" to "arm64" -> "macos-aarch64"
                else -> error("Host has no verifier platform identity")
            }

        fun toJson(): JsonObject = buildJsonObject {
            put("operatingSystem", operatingSystem)
            put("architecture", architecture)
            put("runner", runner)
            put("job", job)
            put("run", run)
            put("attempt", attempt)
        }

        companion object {
            fun current(): HostIdentity {
                val os = when {
                    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "Windows"
                    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macOS"
                    System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "Linux"
                    else -> error("Unsupported native qualification operating system")
                }
                val architecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
                    "amd64", "x86_64", "x64" -> "x86-64"
                    "aarch64", "arm64" -> "arm64"
                    else -> error("Unsupported native qualification architecture")
                }
                return HostIdentity(
                    os,
                    architecture,
                    System.getenv("RUNNER_NAME") ?: "local-jdk21-runner",
                    System.getenv("GITHUB_JOB") ?: "local-focused-red",
                    System.getenv("GITHUB_RUN_ID") ?: "local-run",
                    System.getenv("GITHUB_RUN_ATTEMPT") ?: "1",
                )
            }
        }
    }

    private enum class Surface(
        val displayName: String,
        val slug: String,
        val launcher: String,
        val approvalSurface: String,
    ) {
        CLI("packaged CLI", "packaged-cli", "refactorkit", "cli"),
        DAEMON("packaged daemon", "packaged-daemon", "refactorkit-daemon", "daemon-json-rpc"),
        MCP("packaged MCP", "packaged-mcp", "refactorkit-mcp", "mcp-tool"),
        ;

        companion object {
            fun fromScenarioName(name: String): Surface = entries.singleOrNull { name.startsWith(it.displayName) }
                ?: error("Cannot identify packaged surface from scenario name: $name")
        }
    }

    private data class InteractionSpec(
        val boundary: String,
        val establish: String,
        val preview: String,
        val apply: String,
        val diagnostics: String,
        val rollback: String,
        val planLifecycle: String,
        val applyLifecycle: String,
        val rollbackLifecycle: String,
    ) {
        val table: List<List<String>> = listOf(
            listOf("phase", "interaction"),
            listOf("establish", establish),
            listOf("preview", preview),
            listOf("apply", apply),
            listOf("diagnostics", diagnostics),
            listOf("rollback", rollback),
        )

        companion object {
            fun forSurface(surface: Surface): InteractionSpec = when (surface) {
                Surface.CLI -> InteractionSpec(
                    boundary = "public argv",
                    establish = "no session open; every command receives the same normalized `--root {copy}`",
                    preview = "`java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root {copy}`",
                    apply = "`java rename-module --old-module-dir catalog-model --new-module-dir catalog-domain --new-artifact-id catalog-domain --root {copy} --apply`",
                    diagnostics = "`diagnostics {copy}`",
                    rollback = "`patch rollback {transactionId} --root {copy}`",
                    planLifecycle = "preview and apply use distinct launcher processes; apply replans, and no preview PlanId crosses a process boundary",
                    applyLifecycle = "the apply process replans the same explicit request and returns its own transaction ID",
                    rollbackLifecycle = "a fresh CLI process uses only the returned transaction ID",
                )
                Surface.DAEMON -> InteractionSpec(
                    boundary = "newline-delimited JSON-RPC 2.0 over stdio",
                    establish = "one process receives `project.open root={copy}`",
                    preview = "`refactor.preview operation=renameMavenModule languageId=java arguments={oldModuleDir=catalog-model,newModuleDir=catalog-domain,newArtifactId=catalog-domain}`",
                    apply = "`refactor.apply planId={same-session-planId}`",
                    diagnostics = "`diagnostics`",
                    rollback = "`patch.rollback transactionId={same-session-transactionId}`",
                    planLifecycle = "one daemon process retains the returned canonical plan from preview through apply; only successful apply and rollback refresh session state",
                    applyLifecycle = "the same process applies only its retained returned plan ID and returns one transaction ID",
                    rollbackLifecycle = "the same process uses only that transaction ID before shutdown",
                )
                Surface.MCP -> InteractionSpec(
                    boundary = "MCP JSON-RPC 2.0 over newline-delimited stdio using `tools/call`",
                    establish = "one process completes `initialize`, `notifications/initialized`, and `tools/call project_scan root={copy}`",
                    preview = "`tools/call preview_refactoring operation=java.renameMavenModule languageId=java arguments={oldModuleDir=catalog-model,newModuleDir=catalog-domain,newArtifactId=catalog-domain}`",
                    apply = "`tools/call apply_refactoring planId={same-session-planId}`",
                    diagnostics = "`tools/call diagnostics`",
                    rollback = "`tools/call rollback_refactoring transactionId={same-session-transactionId}`",
                    planLifecycle = "one initialized MCP process retains the canonical plan from preview through apply and retains correlation only inside that session",
                    applyLifecycle = "the same initialized process applies only its retained returned plan ID and returns one transaction ID",
                    rollbackLifecycle = "the same initialized process uses only that transaction ID before shutdown",
                )
            }
        }
    }

    companion object {
        private const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        private val SCENARIO_TIMEOUT: Duration = Duration.ofMinutes(10)
        private val SHA1 = Regex("[0-9a-f]{40}")
        private val SHA256 = Regex("[0-9a-f]{64}")

        private val HOST_MATRIX = listOf(
            listOf("operating system", "architecture"),
            listOf("Linux", "x86-64"),
            listOf("Windows", "x86-64"),
            listOf("macOS", "x86-64"),
            listOf("macOS", "arm64"),
        )

        private val IDENTITY_TABLE = listOf(
            listOf("identity", "exact meaning"),
            listOf("M0", "the permanent fixture's complete no-follow relative path, path-kind, byte-length, permission, and SHA-256 manifest"),
            listOf("S0", "this copy's exact non-engine baseline bytes, path kinds, source and auxiliary inventories, 20-child reactor facts, and authoritative snapshot identity"),
            listOf("D0", "the exact clean canonical authoritative Maven and JDT diagnostic multiset for S0"),
            listOf("C1", "the complete staged byte, path-kind, inventory, and reactor image produced from S0 by only the independently declared five-entry edit"),
            listOf("S1", "the authoritative committed snapshot identity and complete non-engine post-image required to equal C1 after a fresh scan"),
        )

        private val EVIDENCE_TABLE = listOf(
            listOf("identity class", "required bound subjects"),
            listOf("revision", "repository identity, full commit, tree, clean state, and reported RefactorKit version"),
            listOf("native runner", "operating system, architecture, runner, job, run, attempt, and immutable candidate revision"),
            listOf("package", "archive name and SHA-256, extracted-tree hash, launcher path, mode and hash, and embedded-Java path, version and hash"),
            listOf("requirement", "feature hash, requirement ID, Scenario Outline, expanded <surface> example, exact request, and five-edit oracle hash"),
            listOf("fixture", "permanent M0, disposable-copy identity, S0, D0, C1, S1, and proof that all three surface roots are pairwise distinct"),
            listOf("transaction", "sole transaction ID, schema-v8 operation, approval surface, forward-edit hash, snapshot identities, and APPLIED then ROLLED_BACK history"),
            listOf("reports", "Cucumber JSON, JUnit XML, complete stdout, stderr and execution log, manifest, archive checksum, and failure-diagnostic hashes"),
        )

        private val EXCLUDED_SCOPE_TABLE = listOf(
            listOf("explicitly excluded scope"),
            listOf("an external or new sample, a second real repository, Magrathea, or any project other than the existing permanent 20-module fixture"),
            listOf("an omitted newArtifactId, directory-only intent, inferred coordinate, groupId or version migration, arbitrary dependency-origin handling, JPMS, package movement, or new planner semantics"),
            listOf("a command-catalogue schema, operation-result schema, new daemon or MCP alias, new protocol field, or changed public lifecycle"),
            listOf("a recipe, LSP or external editor path, source-built or in-process package evidence, or duplicate runner-specific feature text"),
            listOf("Maven or Gradle lifecycle execution, plugins, annotation processors, project scripts, settings credentials, credential helpers, network access, or an external Java runtime"),
            listOf("a concurrency, crash-restart, corruption, refusal, forced-rollback, recovery, or compensation matrix beyond this exact normal rollback path"),
            listOf("a new aggregate, entity, repository, projection, domain event, event store, outbox, report repository, result store, second journal, or write authority outside PatchEngine"),
            listOf("general Maven populations, broad module-rename support, aggregate numerical coverage, I1 publication or downloaded-asset evidence, signing, notarization, installer UX, or release-wide support"),
        )
    }
}

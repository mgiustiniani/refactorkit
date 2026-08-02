package org.refactorkit.java.mavenmodulerename

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
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JournalState
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchPreviewRenderer
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RollbackMode
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMavenModuleContract
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.util.EnumSet
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Dedicated direct-library Story-BDD glue for REQ-JAVA-MAVEN-MODULE-RENAME-001. */
class JavaMavenModuleRenameSteps {
    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var fixtureRoot: Path
    private lateinit var temporaryRoot: Path
    private lateinit var pristineRoot: Path
    private lateinit var permanentM0: ExactManifest
    private lateinit var pristineM0: ExactManifest
    private lateinit var activityMonitor: DeniedAuthorityMonitor

    private val probeRoots = linkedMapOf<String, Path>()
    private val probePreCallImages = linkedMapOf<String, ExactManifest>()
    private val probeObservations = linkedMapOf<String, ProbeObservation>()
    private val scannerRoots = linkedSetOf<Path>()
    private val plannerRoots = linkedSetOf<Path>()
    private val diagnosticRoots = linkedSetOf<Path>()

    private var expectedSourceInventory: Set<Path> = emptySet()
    private var expectedAuxiliaryInventory: Set<Path> = emptySet()
    private var omittedArtifactPlan: PatchPlan? = null
    private var editableOrigins: List<OriginExpectation> = emptyList()

    private lateinit var s0: ProjectSnapshot
    private lateinit var r0: ReactorAttestation
    private lateinit var d0: DiagnosticBaseline
    private lateinit var positivePlanner: JavaRenameMavenModulePlanner
    private var positivePreviews: List<PreviewObservation> = emptyList()

    private var stagedMirrorRoot: Path? = null
    private var stagedManifest: ExactManifest? = null
    private var stagedScannerSnapshot: ProjectSnapshot? = null
    private var stagedReactor: ReactorAttestation? = null
    private var stagedDiagnostics: DiagnosticBaseline? = null

    private var patchEngine: PatchEngine? = null
    private var applyResult: ApplyResult? = null
    private var selectedTransaction: Transaction? = null
    private var applyCount = 0
    private var rollbackCount = 0
    private var underLockObserved = false
    private var underLockPreconditionsExact = false
    private var preApplyManifest: ExactManifest? = null
    private var s1: ProjectSnapshot? = null
    private var committedReactor: ReactorAttestation? = null
    private var committedDiagnostics: DiagnosticBaseline? = null
    private var journalAfterApply: TransactionJournalRecord? = null
    private var rollbackResult: ApplyResult? = null

    @Before("@REQ-JAVA-MAVEN-MODULE-RENAME-001")
    fun prepareScenario(scenario: Scenario) {
        this.scenario = scenario
        repositoryRoot = locateRepositoryRoot()
        fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).normalize()
        assertTrue(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS), "Permanent fixture is missing")
        permanentM0 = captureExactManifest(fixtureRoot)
        temporaryRoot = Files.createTempDirectory("refactorkit-maven-module-rename-001-")
        pristineRoot = temporaryRoot.resolve("pristine")
        copyNoFollow(fixtureRoot, pristineRoot)
        pristineM0 = captureExactManifest(pristineRoot)
        assertEquals(permanentM0, pristineM0, "The pristine disposable copy differs from the permanent fixture")

        PROBE_NAMES.forEach { probe ->
            val root = temporaryRoot.resolve("refusal-${probe.replace(' ', '-')}")
            copyNoFollow(fixtureRoot, root)
            assertEquals(permanentM0, captureExactManifest(root), "Fresh probe copy differs for $probe")
            probeRoots[probe] = root
        }
        activityMonitor = DeniedAuthorityMonitor.start(temporaryRoot.resolve("authority-events"))
        scenario.attach(
            "REQ-JAVA-MAVEN-MODULE-RENAME-001 runs only through the JVM library boundary over no-follow " +
                "disposable fixture copies. The permanent fixture and feature text are read-only.",
            "text/plain",
            "module-rename-isolation",
        )
    }

    @After("@REQ-JAVA-MAVEN-MODULE-RENAME-001")
    fun cleanScenario() {
        if (this::activityMonitor.isInitialized) {
            val evidence = runCatching { activityMonitor.finish() }.getOrElse {
                ActivityEvidence(emptyList(), emptyList(), emptySet(), "monitor failure: ${it.message}")
            }
            if (this::scenario.isInitialized) {
                scenario.attach(evidence.toJson(), "application/json", "denied-authority-observation")
            }
        }
        if (this::fixtureRoot.isInitialized && this::permanentM0.isInitialized) {
            assertEquals(permanentM0, captureExactManifest(fixtureRoot), "Permanent fixture was mutated")
        }
        if (this::temporaryRoot.isInitialized) deleteNoFollow(temporaryRoot)
    }

    // 1
    @Given("the declared permanent fixture is {string}")
    fun declaredPermanentFixture(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertEquals(fixtureRoot, repositoryRoot.resolve(path).normalize())
        assertFalse(Files.isSymbolicLink(fixtureRoot))
    }

    // 2
    @Given("its root POM is the sole aggregator with exactly 20 unique direct literal module entries, each naming a non-aggregator JAR child with one POM")
    fun soleTwentyChildAggregator() {
        val modules = directRootModules(fixtureRoot)
        assertEquals(EXPECTED_MODULES, modules)
        assertEquals(20, modules.size)
        assertEquals(20, modules.toSet().size)
        val rootProject = parseXml(fixtureRoot.resolve("pom.xml"))
        assertEquals("pom", directChildText(rootProject, "packaging"))
        assertEquals(1, directChildren(rootProject, "modules").size)

        modules.forEach { module ->
            val childRoot = fixtureRoot.resolve(module)
            val poms = Files.list(childRoot).use { paths ->
                paths.filter { it.fileName.toString() == "pom.xml" && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .toList()
            }
            assertEquals(1, poms.size, "$module must have one direct POM")
            val child = parseXml(poms.single())
            assertTrue(directChildren(child, "modules").isEmpty(), "$module must not aggregate")
            assertEquals("jar", directChildText(child, "packaging") ?: "jar", "$module must be a JAR child")
        }
    }

    // 3
    @Given("{string} is one exact direct child, {string} is absent without following links, and {string} contains exactly these two regular files:")
    fun exactSourceChildAndAbsentDestination(
        oldModule: String,
        destination: String,
        repeatedOldModule: String,
        table: DataTable,
    ) {
        assertEquals(OLD_MODULE, oldModule)
        assertEquals(NEW_MODULE, destination)
        assertEquals(oldModule, repeatedOldModule)
        assertEquals(
            listOf(
                listOf("relative file"),
                listOf("pom.xml"),
                listOf("src/main/java/com/acme/catalog/legacy/Product.java"),
            ),
            table.asLists(),
        )
        assertTrue(oldModule in directRootModules(fixtureRoot))
        assertFalse(Files.exists(fixtureRoot.resolve(destination), LinkOption.NOFOLLOW_LINKS))
        val regularFiles = Files.walk(fixtureRoot.resolve(oldModule)).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                .map { fixtureRoot.resolve(oldModule).relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
        assertEquals(listOf("pom.xml", PRODUCT_RELATIVE_PATH), regularFiles)
    }

    // 4
    @Given("the harness creates one pristine workspace and one fresh refusal-probe workspace per probe as no-follow disposable byte copies of the permanent fixture")
    fun disposableNoFollowCopiesExist() {
        assertEquals(permanentM0, pristineM0)
        assertEquals(PROBE_NAMES, probeRoots.keys.toList())
        probeRoots.forEach { (probe, root) ->
            assertEquals(permanentM0, captureExactManifest(root), "Probe $probe was not initially pristine")
            assertTrue(root.startsWith(temporaryRoot))
        }
    }

    // 5
    @Given("copying refuses every symbolic link, preserves every relative regular-file byte and directory path kind, and never writes through or back to the permanent fixture")
    fun copiesAreExactAndPermanentIsolated() {
        assertTrue(permanentM0.entries.values.none { it.kind == PathKind.SYMLINK })
        assertEquals(permanentM0, pristineM0)
        probeRoots.values.forEach { assertEquals(permanentM0, captureExactManifest(it)) }
        assertEquals(permanentM0, captureExactManifest(fixtureRoot))
        assertTrue(pristineRoot.startsWith(temporaryRoot) && !fixtureRoot.startsWith(temporaryRoot))
    }

    // 6
    @Given("before library use the harness records manifest {string}, the expected source and auxiliary path inventories, and the absence of {string}")
    fun recordM0AndInventories(manifestName: String, enginePath: String) {
        assertEquals("M0", manifestName)
        assertEquals(".refactorkit", enginePath)
        pristineM0 = captureExactManifest(pristineRoot)
        expectedSourceInventory = EXPECTED_SOURCE_PATHS.mapTo(linkedSetOf(), Path::of)
        expectedAuxiliaryInventory = buildSet {
            add(Path.of("pom.xml"))
            EXPECTED_MODULES.forEach { add(Path.of(it, "pom.xml")) }
            add(Path.of("catalog-acceptance/.refactorkit-expected-source-inventory.properties"))
            add(Path.of("catalog-generated-support/.refactorkit-generated-root-inventory.properties"))
        }
        (expectedSourceInventory + expectedAuxiliaryInventory).forEach { relative ->
            assertTrue(Files.isRegularFile(pristineRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS), "Missing $relative")
        }
        assertFalse(Files.exists(pristineRoot.resolve(enginePath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(7, expectedSourceInventory.size)
        assertEquals(23, expectedAuxiliaryInventory.size)
    }

    // 7
    @Given("every scanner, planner, diagnostic, apply, journal, and rollback call is confined to its declared disposable root and explicitly fixture-local repository")
    fun callsAreConfinedToDisposableRoots() {
        val allowed = setOf(pristineRoot) + probeRoots.values
        assertTrue(allowed.all { it.startsWith(temporaryRoot) })
        allowed.forEach { root ->
            val localRepository = root.resolve(FIXTURE_REPOSITORY_PATH).normalize()
            assertTrue(localRepository.startsWith(root))
            assertTrue(Files.isDirectory(localRepository, LinkOption.NOFOLLOW_LINKS))
        }
        assertFalse(fixtureRoot.startsWith(temporaryRoot))
    }

    // 8
    @Given("Maven and wrapper execution, lifecycle goals, plugins, annotation processors, generators, user or global settings, mirrors, proxies, servers, credentials, credential helpers, and network requests are denied and instrumented")
    fun deniedAuthoritiesAreInstrumented() {
        assertTrue(activityMonitor.isRunning)
        val activePoms = listOf(Path.of("pom.xml")) + EXPECTED_MODULES.map { Path.of(it, "pom.xml") }
        activePoms.forEach { relative ->
            val project = parseXml(pristineRoot.resolve(relative))
            FORBIDDEN_ACTIVE_POM_ELEMENTS.forEach { forbidden ->
                assertEquals(0, project.getElementsByTagNameNS("*", forbidden).length, "$relative contains <$forbidden>")
            }
        }
        FORBIDDEN_WORKSPACE_INPUTS.forEach { relative ->
            assertFalse(Files.exists(pristineRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS), "Forbidden input exists: $relative")
        }
        activityMonitor.checkpoint("authority-policy-established")
    }

    // 9
    @When("a direct caller independently invokes the real public JavaProjectScanner scan and JavaRenameMavenModulePlanner preview for each fresh refusal probe:")
    fun invokeRefusalProbes(table: DataTable) {
        val rows = table.asLists()
        assertEquals(EXPECTED_PROBE_TABLE, rows)
        rows.drop(1).forEach { row ->
            val probe = row[0]
            val root = requireNotNull(probeRoots[probe])
            when (probe) {
                "inferred artifact coordinate" -> Unit
                "property-managed dependency origin" -> createPropertyManagedProbe(root)
                "ambiguous dependency origin" -> createDuplicateOriginProbe(root)
                else -> fail("Unexpected probe: $probe")
            }
            assertFalse(Files.exists(root.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
            val before = captureExactManifest(root)
            probePreCallImages[probe] = before
            val snapshot = scan(root)
            val planner = JavaRenameMavenModulePlanner()
            val explicitArtifact = when (probe) {
                "inferred artifact coordinate" -> ""
                else -> NEW_ARTIFACT
            }
            val plan = preview(planner, snapshot, OLD_MODULE, NEW_MODULE, explicitArtifact)
            val after = captureExactManifest(root)
            assertEquals(before, after, "Refusal probe call mutated '$probe'")
            assertFalse(Files.exists(root.resolve(WORKSPACE_LOCK), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(root.resolve(TRANSACTION_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(root.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
            probeObservations[probe] = ProbeObservation(
                name = probe,
                condition = row[1],
                arguments = row[2],
                requiredBlocker = row[3],
                root = root,
                snapshot = snapshot,
                plan = plan,
                before = before,
                after = after,
            )
            activityMonitor.checkpoint("refusal-$probe")
        }
        assertEquals(PROBE_NAMES, probeObservations.keys.toList())
        activityMonitor.assertNoRefactorKitProcessOrSocketAuthority("all refusal probe calls")
    }

    // 10
    @Then("every probe returns PatchStatus {string} before publishing a writable preview and identifies its listed blocker without guessing an origin or coordinate")
    fun everyProbeRefusesWithItsBlocker(expectedStatus: String) {
        assertEquals(PatchStatus.REFUSED.name, expectedStatus)
        probeObservations.values.forEach { observation ->
            assertEquals(
                PatchStatus.REFUSED,
                observation.plan.status,
                "Probe '${observation.name}' expected REFUSED because '${observation.requiredBlocker}', " +
                    "but JavaRenameMavenModulePlanner returned ${observation.plan.status}: ${observation.plan.summary}",
            )
            val evidence = buildString {
                append(observation.plan.refusalCode.orEmpty()).append(' ')
                append(observation.plan.summary).append(' ')
                append(observation.plan.warnings.joinToString(" "))
            }.lowercase()
            requiredBlockerTokens(observation.name).forEach { alternatives ->
                assertTrue(
                    alternatives.any(evidence::contains),
                    "Probe '${observation.name}' did not identify '${observation.requiredBlocker}': $evidence",
                )
            }
        }
    }

    // 11
    @Then("every refusal has an empty WorkspaceEdit, no affected path, no authority lease, no apply identity, and no user-approval capability")
    fun refusalsCarryNoWriteAuthority() {
        probeObservations.values.forEach { observation ->
            val plan = observation.plan
            assertEquals(PatchStatus.REFUSED, plan.status)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            assertTrue(plan.affectedFiles.isEmpty())
            assertNull(plan.authorityLease)
            assertFalse(plan.requiresUserApproval)
            assertFalse(Files.exists(observation.root.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        }
        assertEquals(0, applyCount)
        assertNull(selectedTransaction)
    }

    // 12
    @Then("omission is distinct from the inferred-coordinate probe: an omitted newArtifactId means preserve artifact {string} and never authorizes a child or dependency coordinate edit")
    fun omissionPreservesArtifactIdentity(oldArtifact: String) {
        assertEquals(OLD_ARTIFACT, oldArtifact)
        val planner = JavaRenameMavenModulePlanner()
        val snapshot = scan(pristineRoot)
        val plan = preview(planner, snapshot, OLD_MODULE, NEW_MODULE, null)
        omittedArtifactPlan = plan
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val normalized = WorkspaceEditSimulator.normalize(plan.workspaceEdit)
        val modifications = normalized.edits.filterIsInstance<FileEdit.Modify>()
        assertEquals(listOf(Path.of("pom.xml")), modifications.map(FileEdit.Modify::path))
        assertTrue(modifications.single().textEdits.all { it.newText == NEW_MODULE })
        assertTrue(normalized.edits.none { edit ->
            edit is FileEdit.Modify && edit.path in setOf(Path.of(OLD_POM), Path.of(PRICING_POM))
        })
        assertTrue(plan.summary.contains(OLD_ARTIFACT))
    }

    // 13
    @Then("every refusal leaves its pre-call probe image exact and creates no workspace lock, {string} path, write-ahead journal, or managed transaction")
    fun refusalsAreReadOnly(enginePath: String) {
        assertEquals(".refactorkit", enginePath)
        probeObservations.values.forEach { observation ->
            assertEquals(observation.before, observation.after, "Probe ${observation.name} mutated its workspace")
            assertEquals(observation.before, captureExactManifest(observation.root))
            assertFalse(Files.exists(observation.root.resolve(WORKSPACE_LOCK), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(observation.root.resolve(enginePath), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(observation.root.resolve(TRANSACTION_DIRECTORY), LinkOption.NOFOLLOW_LINKS))
        }
        assertEquals(0, applyCount)
        activityMonitor.assertNoRefactorKitProcessOrSocketAuthority("refusal probes")
    }

    // 14
    @Then("the pristine workspace and permanent fixture still equal {string} byte for byte and path-kind for path-kind")
    fun pristineAndPermanentRemainM0(manifestName: String) {
        assertEquals("M0", manifestName)
        assertEquals(pristineM0, captureExactManifest(pristineRoot))
        assertEquals(permanentM0, captureExactManifest(fixtureRoot))
    }

    // 15
    @When("JavaProjectScanner with network dependency resolution disabled scans only the declared pristine workspace root")
    fun scanOnlyPristineWorkspace() {
        s0 = scan(pristineRoot)
        assertEquals(pristineRoot.toAbsolutePath().normalize(), s0.workspace.root.toAbsolutePath().normalize())
        activityMonitor.checkpoint("positive-scan-s0")
    }

    // 16
    @Then("snapshot {string} contains exactly one applicable authoritative offline Maven effective reactor with status {string}")
    fun s0HasOneAvailableMavenReactor(snapshotName: String, status: String) {
        assertEquals("S0", snapshotName)
        assertEquals(BuildModelStatus.AVAILABLE.name, status)
        assertEquals(1, s0.buildModels.size)
        val model = s0.buildModels.single()
        assertEquals(MAVEN_PROVIDER, model.providerId)
        assertEquals(BuildModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        assertEquals("embedded-effective-model", model.attributes["strategy"])
        assertEquals("denied", model.attributes["networkAccess"])
        assertEquals(expectedSourceInventory, s0.files.mapTo(linkedSetOf()) { it.path.normalize() })
        assertEquals(expectedAuxiliaryInventory, s0.auxiliaryFiles.mapTo(linkedSetOf()) { it.path.normalize() })
    }

    // 17
    @Then("reactor {string} proves the sole root aggregator and all 20 exact direct non-aggregator children without implicit ancestor, sibling, profile, or outward discovery")
    fun r0IsExactlyTheDeclaredDirectReactor(reactorName: String) {
        assertEquals("R0", reactorName)
        r0 = attestReactor(s0, pristineRoot)
        assertEquals(EXPECTED_MODULES, r0.directModules)
        assertEquals(EXPECTED_MODULES.toSet(), r0.modules.keys)
        assertEquals(BuildModelStatus.AVAILABLE, r0.status)
        assertEquals(20, r0.modules.size)
        assertEquals("", s0.buildModels.single().attributes["activeProfiles"])
        r0.modules.values.forEach { module ->
            assertEquals("jar", module.packaging)
            assertFalse(module.root.startsWith(".."))
            assertEquals(setOf("main", "test"), module.sourceSets.mapTo(linkedSetOf()) { it.id })
        }
        assertTrue(scannerRoots.all { it.startsWith(temporaryRoot) })
    }

    // 18
    @Then("the only editable origins for the positive request are these exact zero-based element-text ranges from hash-bound raw POMs:")
    fun exactPositiveEditableOrigins(table: DataTable) {
        assertEquals(EXPECTED_ORIGIN_TABLE, table.asLists())
        editableOrigins = table.asMaps().map { row ->
            OriginExpectation(
                role = row.getValue("role"),
                path = Path.of(row.getValue("raw POM")),
                sha256 = row.getValue("raw POM SHA-256"),
                range = parseRange(row.getValue("element-text range")),
                literal = row.getValue("literal"),
                effectiveProof = row.getValue("effective proof"),
                count = row.getValue("exact origin count").toInt(),
            )
        }
        editableOrigins.forEach { origin ->
            val absolute = pristineRoot.resolve(origin.path)
            assertEquals(origin.sha256, sha256(Files.readAllBytes(absolute)), origin.path.toString())
            val content = Files.readString(absolute)
            assertEquals(origin.literal, textAt(content, origin.range), origin.role)
            assertEquals(origin.count, exactTextRangeCount(content, origin.literal, origin.range), origin.role)
        }

        val child = parseXml(pristineRoot.resolve(OLD_POM))
        assertEquals(OLD_ARTIFACT, directChildText(child, "artifactId"))
        val rootModules = directRootModules(pristineRoot)
        assertEquals(1, rootModules.count { it == OLD_MODULE })
        val pricing = parseXml(pristineRoot.resolve(PRICING_POM))
        val dependency = directDependencies(pricing).single { directChildText(it, "artifactId") == OLD_ARTIFACT }
        assertEquals(GROUP_ID, directChildText(dependency, "groupId"))
        assertEquals("${'$'}{project.version}", directChildText(dependency, "version"))
        assertModuleDependency(s0.buildModels.single(), "catalog-pricing", OLD_MODULE)
    }

    // 19
    @Then("the child POM parent artifact {string}, transitive consumers, profiles, dependency management, and lexical same-name tags are not editable origin evidence")
    fun excludedOriginsRemainOutsideEvidence(parentArtifact: String) {
        assertEquals(ROOT_ARTIFACT, parentArtifact)
        val authorizedPaths = editableOrigins.mapTo(linkedSetOf()) { it.path }
        assertEquals(setOf(Path.of(OLD_POM), Path.of("pom.xml"), Path.of(PRICING_POM)), authorizedPaths)
        val child = parseXml(pristineRoot.resolve(OLD_POM))
        val parent = directChildren(child, "parent").single()
        assertEquals(ROOT_ARTIFACT, directChildText(parent, "artifactId"))
        val pricing = parseXml(pristineRoot.resolve(PRICING_POM))
        assertTrue(directChildren(pricing, "profiles").isNotEmpty())
        assertTrue(directChildren(parseXml(pristineRoot.resolve("pom.xml")), "dependencyManagement").isNotEmpty())
        assertFalse(Path.of("catalog-storefront/pom.xml") in authorizedPaths)
        assertFalse(Path.of("catalog-acceptance/pom.xml") in authorizedPaths)
        assertEquals(ROOT_POM_SHA256, sha256(Files.readAllBytes(pristineRoot.resolve("pom.xml"))))
        assertEquals(CHILD_POM_SHA256, sha256(Files.readAllBytes(pristineRoot.resolve(OLD_POM))))
        assertEquals(PRICING_POM_SHA256, sha256(Files.readAllBytes(pristineRoot.resolve(PRICING_POM))))
    }

    // 20
    @Then("the direct caller records the reactor's Maven diagnostics and JavaLanguageAdapter authoritative JDT diagnostics as the exact canonical baseline {string}")
    fun recordD0(name: String) {
        assertEquals("D0", name)
        d0 = diagnostics(s0)
        val repeated = diagnostics(s0)
        assertEquals(d0, repeated)
        assertEquals(canonicalMavenDiagnostics(s0.buildModels.single().diagnostics), d0.maven)
        assertEquals(canonicalJavaDiagnostics(authoritativeJavaDiagnostics(s0)), d0.jdt)
    }

    // 21
    @When("the caller invokes JavaRenameMavenModulePlanner.preview twice over the same immutable {string}, raw-POM evidence, reactor {string}, diagnostic providers, and exact arguments oldModuleDir={string}, newModuleDir={string}, newArtifactId={string}")
    fun previewPositiveTwice(
        snapshotName: String,
        reactorName: String,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String,
    ) {
        assertEquals("S0", snapshotName)
        assertEquals("R0", reactorName)
        assertEquals(OLD_MODULE, oldModuleDir)
        assertEquals(NEW_MODULE, newModuleDir)
        assertEquals(NEW_ARTIFACT, newArtifactId)
        val before = captureExactManifest(pristineRoot)
        positivePlanner = JavaRenameMavenModulePlanner()
        positivePreviews = List(2) {
            capturePreview(positivePlanner, s0, oldModuleDir, newModuleDir, newArtifactId)
        }
        assertEquals(before, captureExactManifest(pristineRoot), "Positive preview mutated the pristine workspace")
        activityMonitor.checkpoint("positive-previews")
    }

    // 22
    @Then("both results are PatchStatus {string} for operation {string}, require separate explicit user approval, and carry immutable snapshot-bound origin, destination-absence, staged-reactor, and diagnostic evidence")
    fun positivePreviewsCarryCompleteEvidence(status: String, operation: String) {
        assertEquals(PatchStatus.PREVIEW.name, status)
        assertEquals(OPERATION, operation)
        assertEquals(2, positivePreviews.size)
        positivePreviews.forEach { observation ->
            val plan = observation.plan
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertEquals(OPERATION, plan.operation)
            assertTrue(plan.requiresUserApproval)
            assertEquals(s0.hash, plan.snapshotHash)
            assertNull(observation.normalizationFailure, observation.normalizationFailure?.message)
            assertNull(observation.simulationFailure, observation.simulationFailure?.message)
            assertNull(observation.renderFailure, observation.renderFailure?.message)
            val lease = assertNotNull(plan.authorityLease, "Module-rename preview requires immutable apply evidence")
            assertEquals(OPERATION, lease.operation)
            assertEquals(s0.hash, lease.snapshotHash)
            val evidencePaths = lease.requiredFileEvidence.mapTo(linkedSetOf()) { it.path }
            editableOrigins.map(OriginExpectation::path).forEach { assertTrue(it in evidencePaths) }
            val evidenceText = (lease.attributes.entries.map { "${it.key}=${it.value}" } +
                lease.requiredFileEvidence.flatMap { evidence -> evidence.attributes.entries.map { "${it.key}=${it.value}" } })
                .joinToString(" ").lowercase()
            assertTrue(evidenceText.contains(NEW_MODULE) && evidenceText.contains("absent"), evidenceText)
            assertTrue(evidenceText.contains("staged") && evidenceText.contains("reactor"), evidenceText)
            assertTrue(evidenceText.contains("diagnostic"), evidenceText)
            assertEquals(d0.jdt, canonicalJavaDiagnostics(plan.diagnosticsBefore))
        }
    }

    @Then("caller-held and exposed collection mutation attempts cannot alter transient StageFacts, GateExpectation, ReactorView, ModuleView, or SourceSetView evidence")
    fun transientContractCollectionEvidenceIsImmutable() {
        val observation = positivePreviews.first()
        val stagedSnapshot = assertNotNull(observation.stagedSnapshot, "The positive staged snapshot was not captured")
        val baselineModel = s0.buildModels.single()
        val stagedModel = stagedSnapshot.buildModels.single()

        val baselineMavenAlias = canonicalMavenDiagnostics(baselineModel.diagnostics).toMutableList()
        val stagedMavenAlias = canonicalMavenDiagnostics(stagedModel.diagnostics).toMutableList()
        val baselineJdtAlias = observation.diagnosticsBefore.toMutableList()
        val stagedJdtAlias = assertNotNull(observation.diagnosticsStaged).toMutableList()
        val baselineJdtCanonicalAlias = canonicalJavaDiagnostics(baselineJdtAlias).toMutableList()
        val stagedJdtCanonicalAlias = canonicalJavaDiagnostics(stagedJdtAlias).toMutableList()
        val baselineTrackedHash = trackedEvidenceHash(s0)
        val stagedTrackedHash = trackedEvidenceHash(stagedSnapshot)
        val baselineMavenHash = JavaRenameMavenModuleContract.hashStrings(baselineMavenAlias)
        val stagedMavenHash = JavaRenameMavenModuleContract.hashStrings(stagedMavenAlias)
        val baselineJdtHash = JavaRenameMavenModuleContract.hashStrings(baselineJdtCanonicalAlias)
        val stagedJdtHash = JavaRenameMavenModuleContract.hashStrings(stagedJdtCanonicalAlias)
        val baselineDiagnosticsHash = JavaRenameMavenModuleContract.hashStrings(
            baselineMavenAlias + baselineJdtCanonicalAlias,
        )
        val stagedDiagnosticsHash = JavaRenameMavenModuleContract.hashStrings(
            stagedMavenAlias + stagedJdtCanonicalAlias,
        )
        val stageFacts = JavaRenameMavenModuleContract.StageFacts(
            baselineManifestHash = exactManifestEvidenceHash(pristineM0),
            stagedManifestHash = trackedEvidenceHash(stagedSnapshot),
            baselineTrackedHash = baselineTrackedHash,
            stagedTrackedHash = stagedTrackedHash,
            stagedCandidateSnapshotHash = stagedSnapshot.hash,
            baselineSnapshot = s0,
            stagedSnapshot = stagedSnapshot,
            baselineModel = baselineModel,
            stagedModel = stagedModel,
            baselineMavenDiagnostics = baselineMavenAlias,
            stagedMavenDiagnostics = stagedMavenAlias,
            baselineJdt = baselineJdtAlias,
            stagedJdt = stagedJdtAlias,
            baselineJdtCanonical = baselineJdtCanonicalAlias,
            stagedJdtCanonical = stagedJdtCanonicalAlias,
            baselineDiagnosticsHash = baselineDiagnosticsHash,
            stagedDiagnosticsHash = stagedDiagnosticsHash,
        )

        val admittedCandidateHashesAlias = linkedSetOf(stagedSnapshot.hash)
        val gateExpectation = JavaRenameMavenModuleContract.GateExpectation(
            authoritativeSnapshotHash = s0.hash,
            admittedCandidateSnapshotHashes = admittedCandidateHashesAlias,
            manifestHash = stageFacts.baselineManifestHash,
            reactorHash = buildModelEvidenceHash(baselineModel),
            mavenDiagnosticsHash = baselineMavenHash,
            jdtDiagnosticsHash = baselineJdtHash,
            diagnosticsHash = baselineDiagnosticsHash,
            destinationPresent = false,
        )

        val buildModule = baselineModel.modules.single { it.id == OLD_MODULE }
        val buildSourceSet = buildModule.sourceSets.single { it.id == "main" }
        val sourceRootsAlias = buildSourceSet.sourceRoots
            .mapTo(mutableListOf()) { JavaRenameMavenModuleContract.pathString(it) }
        val generatedSourceRootsAlias = buildSourceSet.generatedSourceRoots
            .mapTo(mutableListOf()) { JavaRenameMavenModuleContract.pathString(it) }
        val outputDirectoriesAlias = buildSourceSet.outputDirectories
            .mapTo(mutableListOf()) { JavaRenameMavenModuleContract.pathString(it) }
        val classpathEntriesAlias = buildSourceSet.classpathEntries
            .mapTo(mutableListOf()) { JavaRenameMavenModuleContract.pathString(it) }
        val runtimeClasspathEntriesAlias = buildSourceSet.runtimeClasspathEntries
            .mapTo(mutableListOf()) { JavaRenameMavenModuleContract.pathString(it) }
        val dependenciesAlias = buildSourceSet.moduleDependencies
            .mapTo(mutableListOf()) { it.targetModuleId to it.scope.name }
        val sourceSetAttributesAlias = buildSourceSet.attributes.toMutableMap()
        val sourceSetView = JavaRenameMavenModuleContract.SourceSetView(
            id = buildSourceSet.id,
            kind = buildSourceSet.kind.name,
            sourceRoots = sourceRootsAlias,
            generatedSourceRoots = generatedSourceRootsAlias,
            outputDirectories = outputDirectoriesAlias,
            classpathEntries = classpathEntriesAlias,
            runtimeClasspathEntries = runtimeClasspathEntriesAlias,
            dependencies = dependenciesAlias,
            attributes = sourceSetAttributesAlias,
        )
        val moduleSourceSetsAlias = mutableListOf(sourceSetView)
        val moduleRoot = buildModule.root.normalize().let { root ->
            if (root.isAbsolute) {
                val workspace = pristineRoot.toAbsolutePath().normalize()
                assertTrue(root.startsWith(workspace), "S0 module root escaped the disposable workspace: $root")
                workspace.relativize(root)
            } else {
                root
            }
        }
        val moduleView = JavaRenameMavenModuleContract.ModuleView(
            id = buildModule.id,
            name = buildModule.name,
            root = moduleRoot.invariantSeparatorsPathString,
            groupId = buildModule.attributes.getValue("java.maven.groupId"),
            artifactId = buildModule.attributes.getValue("java.maven.artifactId"),
            version = buildModule.attributes.getValue("java.maven.version"),
            packaging = buildModule.attributes.getValue("java.maven.packaging"),
            sourceSets = moduleSourceSetsAlias,
        )
        val reactorModulesAlias = linkedMapOf(moduleView.id to moduleView)
        val reactorView = JavaRenameMavenModuleContract.ReactorView(reactorModulesAlias)

        val constructionTime = transientCollectionSnapshot(
            stageFacts,
            gateExpectation,
            reactorView,
            moduleView,
            sourceSetView,
        )
        assertEquals(16, constructionTime.size, "Every direct transient-evidence collection view must be covered")

        val callerDiagnosticSentinel = Diagnostic(
            "caller alias mutation sentinel",
            Diagnostic.Severity.ERROR,
        )
        baselineMavenAlias.add("caller-alias-baseline-maven")
        stagedMavenAlias.add("caller-alias-staged-maven")
        baselineJdtAlias.add(callerDiagnosticSentinel)
        stagedJdtAlias.add(callerDiagnosticSentinel.copy(message = "caller alias staged JDT sentinel"))
        baselineJdtCanonicalAlias.add("caller-alias-baseline-jdt-canonical")
        stagedJdtCanonicalAlias.add("caller-alias-staged-jdt-canonical")
        admittedCandidateHashesAlias.add("caller-alias-candidate-hash")
        sourceRootsAlias.add("caller-alias/source-root")
        generatedSourceRootsAlias.add("caller-alias/generated-source-root")
        outputDirectoriesAlias.add("caller-alias/output")
        classpathEntriesAlias.add("caller-alias/classpath")
        runtimeClasspathEntriesAlias.add("caller-alias/runtime-classpath")
        dependenciesAlias.add("caller-alias-module" to "TEST")
        sourceSetAttributesAlias["caller-alias-attribute"] = "mutated"
        moduleSourceSetsAlias.add(sourceSetView.copy(id = "caller-alias-source-set"))
        reactorModulesAlias["caller-alias-module"] = moduleView.copy(
            id = "caller-alias-module",
            name = "caller-alias-module",
        )

        val afterCallerMutation = transientCollectionSnapshot(
            stageFacts,
            gateExpectation,
            reactorView,
            moduleView,
            sourceSetView,
        )
        val exposedDiagnosticSentinel = Diagnostic(
            "exposed view mutation sentinel",
            Diagnostic.Severity.ERROR,
        )
        val attempts = listOf(
            attemptListMutation(
                "StageFacts.baselineMavenDiagnostics",
                stageFacts.baselineMavenDiagnostics,
                "exposed-baseline-maven",
            ),
            attemptListMutation(
                "StageFacts.stagedMavenDiagnostics",
                stageFacts.stagedMavenDiagnostics,
                "exposed-staged-maven",
            ),
            attemptListMutation("StageFacts.baselineJdt", stageFacts.baselineJdt, exposedDiagnosticSentinel),
            attemptListMutation(
                "StageFacts.stagedJdt",
                stageFacts.stagedJdt,
                exposedDiagnosticSentinel.copy(message = "exposed staged JDT sentinel"),
            ),
            attemptListMutation(
                "StageFacts.baselineJdtCanonical",
                stageFacts.baselineJdtCanonical,
                "exposed-baseline-jdt-canonical",
            ),
            attemptListMutation(
                "StageFacts.stagedJdtCanonical",
                stageFacts.stagedJdtCanonical,
                "exposed-staged-jdt-canonical",
            ),
            attemptSetMutation(
                "GateExpectation.admittedCandidateSnapshotHashes",
                gateExpectation.admittedCandidateSnapshotHashes,
                "exposed-candidate-hash",
            ),
            attemptMapMutation(
                "ReactorView.modules",
                reactorView.modules,
                "exposed-module",
                moduleView.copy(id = "exposed-module", name = "exposed-module"),
                ::detachedModuleView,
            ),
            attemptListMutation(
                "ModuleView.sourceSets",
                moduleView.sourceSets,
                sourceSetView.copy(id = "exposed-source-set"),
                ::detachedSourceSetView,
            ),
            attemptListMutation("SourceSetView.sourceRoots", sourceSetView.sourceRoots, "exposed/source-root"),
            attemptListMutation(
                "SourceSetView.generatedSourceRoots",
                sourceSetView.generatedSourceRoots,
                "exposed/generated-source-root",
            ),
            attemptListMutation(
                "SourceSetView.outputDirectories",
                sourceSetView.outputDirectories,
                "exposed/output",
            ),
            attemptListMutation(
                "SourceSetView.classpathEntries",
                sourceSetView.classpathEntries,
                "exposed/classpath",
            ),
            attemptListMutation(
                "SourceSetView.runtimeClasspathEntries",
                sourceSetView.runtimeClasspathEntries,
                "exposed/runtime-classpath",
            ),
            attemptListMutation(
                "SourceSetView.dependencies",
                sourceSetView.dependencies,
                "exposed-module" to "TEST",
            ),
            attemptMapMutation(
                "SourceSetView.attributes",
                sourceSetView.attributes,
                "exposed-attribute",
                "mutated",
            ),
        )
        assertEquals(constructionTime.keys.toList(), attempts.map(CollectionMutationAttempt::label))

        val afterExposedMutation = transientCollectionSnapshot(
            stageFacts,
            gateExpectation,
            reactorView,
            moduleView,
            sourceSetView,
        )
        fun exactCount(actual: Map<String, Any>): Int = constructionTime.keys.count { label ->
            constructionTime.getValue(label) == actual.getValue(label)
        }
        val actualCounts = CollectionBoundaryCounts(
            total = constructionTime.size,
            callerAliasesIsolated = exactCount(afterCallerMutation),
            exposedMutationsRejected = attempts.count(CollectionMutationAttempt::rejected),
            rejectedWithoutPartialChange = attempts.count { it.rejected && it.unchanged },
            constructionTimeValuesExact = exactCount(afterExposedMutation),
        )
        val expectedCounts = CollectionBoundaryCounts(
            total = constructionTime.size,
            callerAliasesIsolated = constructionTime.size,
            exposedMutationsRejected = constructionTime.size,
            rejectedWithoutPartialChange = constructionTime.size,
            constructionTimeValuesExact = constructionTime.size,
        )
        assertEquals(
            expectedCounts,
            actualCounts,
            buildString {
                appendLine("Transient evidence collection immutability defect:")
                appendLine("caller alias leaks=${changedCollectionLabels(constructionTime, afterCallerMutation)}")
                appendLine("exposed views accepting mutation=${attempts.filterNot { it.rejected }.map { it.label }}")
                appendLine("exposed mutation partial changes=${attempts.filterNot { it.unchanged }.map { it.label }}")
                append("construction-time values changed=${changedCollectionLabels(constructionTime, afterExposedMutation)}")
            },
        )
    }

    @Then("every real JavaRenameMavenModulePlanner call in this scenario obtains POM structure and exact element-text ranges only through a maintained non-executing XML parser with DTD processing and external general and parameter entity resolution disabled")
    fun maintainedNonExecutingXmlParserIsAttestedByPreviewAuthority() {
        previewsWithAuthority().forEach { plan ->
            val lease = assertNotNull(plan.authorityLease)
            val attributes = lease.attributes
            val actualPolicy = EXPECTED_XML_PARSER_POLICY.keys.associateWith(attributes::get)
            assertEquals(
                EXPECTED_XML_PARSER_POLICY,
                actualPolicy,
                "The real preview authority does not attest maintained non-executing XML parsing and exact DTD/entity denial; " +
                    "parser attributes=${attributes.filterKeys { it.contains("xml", ignoreCase = true) }}",
            )
            assertMaintainedParserIdentity(attributes)
            assertImmutableMap(attributes, "operation-authority parser attestation")
            lease.requiredFileEvidence.filter { it.path.fileName.toString() == "pom.xml" }.forEach { evidence ->
                assertImmutableMap(evidence.attributes, "raw-POM evidence ${evidence.path}")
            }
        }
    }

    @Then("no such planner call uses a hand-written XML tokenizer\\/parser or serializes a POM; parser-reported ranges authorize only the hash-bound raw element-text replacements listed below")
    fun parserRangesAloneAuthorizeRangeOnlyPomMutation() {
        positivePreviews.forEach { observation ->
            val plan = observation.plan
            val lease = assertNotNull(plan.authorityLease)
            assertMaintainedParserIdentity(lease.attributes)
            assertEquals("RANGE_ONLY_NO_SERIALIZATION", lease.attributes["xmlMutation"])
            assertEquals(
                "PARSER_REPORTED_ELEMENT_TEXT",
                lease.attributes["xmlRangeAuthority"],
                "The immutable authority must identify parser-reported element-text ranges rather than lexical guesses",
            )

            val normalized = assertNotNull(observation.normalizedEdit)
            val modifiedRanges = normalized.edits.filterIsInstance<FileEdit.Modify>().flatMap { modify ->
                modify.textEdits.map { text -> modify.path.normalize() to formatRange(text.range) }
            }.toSet()
            assertEquals(EXPECTED_XML_RANGE_AUTHORITY, modifiedRanges)

            val attestedRanges = lease.requiredFileEvidence.flatMap { evidence ->
                evidence.attributes["originRanges"].orEmpty().split(',').filter(String::isNotBlank).map { range ->
                    evidence.path.normalize() to range
                }
            }.toSet()
            assertEquals(
                modifiedRanges,
                attestedRanges,
                "Only immutable hash-bound raw-POM evidence may authorize parser-reported replacements",
            )
            modifiedRanges.map(Pair<Path, String>::first).forEach { path ->
                val evidence = lease.requiredFileEvidence.single { it.path.normalize() == path }
                assertTrue(SHA256_PATTERN.matches(evidence.expectedContentSha256), "Missing raw-POM identity for $path")
            }
            assertTrue(normalized.edits.filterIsInstance<FileEdit.Modify>().all { it.textEdits.size == 1 })
            assertTrue(normalized.edits.none { it is FileEdit.Create || it is FileEdit.Delete })
        }
    }

    // 23
    @Then("each normalized non-overlapping WorkspaceEdit has exactly these five ordered FileEdit entries and no others:")
    fun exactFiveOrderedEdits(table: DataTable) {
        val normalizedRows = table.asLists().map { row -> row.map { cell -> cell ?: "" } }
        assertEquals(EXPECTED_EDIT_TABLE, normalizedRows)
        val headers = normalizedRows.first()
        val expectedRows = normalizedRows.drop(1).map { row -> headers.zip(row).toMap() }
        positivePreviews.forEach { observation ->
            val normalized = assertNotNull(observation.normalizedEdit)
            assertEquals(5, normalized.edits.size)
            expectedRows.forEachIndexed { index, row ->
                assertFileEditRow(index + 1, normalized.edits[index], row)
            }
            normalized.edits.filterIsInstance<FileEdit.Modify>().forEach { modify ->
                val sorted = modify.textEdits.sortedBy { it.range.start }
                assertTrue(sorted.zipWithNext().none { (left, right) -> left.range.overlaps(right.range) })
            }
            assertEquals(normalized, WorkspaceEditSimulator.normalize(normalized))
            assertTrue(assertNotNull(observation.renderedPreview).contains("Operation: $OPERATION"))
        }
    }

    // 24
    @Then("the normalized affected-path set contains exactly the three modification paths, both rename sources, and both rename destinations, with the repeated child POM source counted once")
    fun exactAffectedPathSet() {
        positivePreviews.forEach { observation ->
            val normalized = assertNotNull(observation.normalizedEdit)
            assertEquals(EXPECTED_AFFECTED_PATHS, normalized.affectedFiles())
            assertEquals(EXPECTED_AFFECTED_PATHS, observation.plan.affectedFiles)
            assertEquals(6, observation.plan.affectedFiles.size)
        }
    }

    // 25
    @Then("the exact staged changed-or-moved file image is:")
    fun exactStagedFileImage(table: DataTable) {
        assertEquals(EXPECTED_IMAGE_TABLE, table.asLists())
        val rows = table.asMaps()
        positivePreviews.forEach { observation ->
            val staged = assertNotNull(observation.stagedSnapshot)
            rows.forEach { row -> assertImageRow(staged, row) }
        }
        ensureStagedMirror()
        val stagedSnapshot = assertNotNull(positivePreviews.first().stagedSnapshot)
        val stagedCopySnapshot = scan(assertNotNull(stagedMirrorRoot))
        assertEquals(
            stagedSnapshot.files.map { it.path to it.content }.toMap(),
            stagedCopySnapshot.files.map { it.path to it.content }.toMap(),
        )
        assertEquals(
            stagedSnapshot.auxiliaryFiles.map { it.path to it.content }.toMap(),
            stagedCopySnapshot.auxiliaryFiles.map { it.path to it.content }.toMap(),
        )
    }

    // 26
    @Then("only the three authorized element-text ranges differ; every other XML and source byte, including the parent artifact, namespace syntax, whitespace, line endings, comments, processing instructions, unknown elements, and `${'$'}\\{project.version\\}` text, is preserved")
    fun onlyAuthorizedElementTextDiffers() {
        val staged = assertNotNull(positivePreviews.first().stagedSnapshot)
        val expectedReplacement = mapOf(
            Path.of(OLD_POM) to Path.of(NEW_POM),
            Path.of("pom.xml") to Path.of("pom.xml"),
            Path.of(PRICING_POM) to Path.of(PRICING_POM),
        )
        editableOrigins.forEach { origin ->
            val before = s0.trackedFiles.single { it.path == origin.path }.content
            val afterPath = expectedReplacement.getValue(origin.path)
            val after = staged.trackedFiles.single { it.path == afterPath }.content
            val expected = TextEdits.apply(before, listOf(TextEdit(origin.range, NEW_ARTIFACT)))
            assertEquals(expected, after, "Bytes outside ${origin.range} changed in ${origin.path}")
        }
        val childAfter = staged.auxiliaryFiles.single { it.path == Path.of(NEW_POM) }.content
        assertTrue(childAfter.contains("<artifactId>$ROOT_ARTIFACT</artifactId>"))
        val pricingAfter = staged.auxiliaryFiles.single { it.path == Path.of(PRICING_POM) }.content
        assertTrue(pricingAfter.contains("<version>${'$'}{project.version}</version>"))
        assertEquals(
            s0.files.single { it.path == Path.of(OLD_PRODUCT) }.content,
            staged.files.single { it.path == Path.of(NEW_PRODUCT) }.content,
        )
    }

    // 27
    @Then("the staged path-kind image preserves the pre-existing now-empty {string} directory hierarchy, creates only the corresponding {string} hierarchy for the two moved regular files, and changes no other non-engine path kind")
    fun exactStagedPathKinds(oldDirectory: String, newDirectory: String) {
        assertEquals(OLD_MODULE, oldDirectory)
        assertEquals(NEW_MODULE, newDirectory)
        ensureStagedMirror()
        val manifest = assertNotNull(stagedManifest)
        val oldDirectories = pristineM0.entries.filter { (path, entry) ->
            (path == OLD_MODULE || path.startsWith("$OLD_MODULE/")) && entry.kind == PathKind.DIRECTORY
        }.keys
        oldDirectories.forEach { path -> assertEquals(PathKind.DIRECTORY, manifest.entries.getValue(path).kind) }
        val oldRegularFiles = manifest.entries.filter { (path, entry) ->
            (path == OLD_MODULE || path.startsWith("$OLD_MODULE/")) && entry.kind == PathKind.REGULAR_FILE
        }
        assertTrue(oldRegularFiles.isEmpty(), oldRegularFiles.toString())
        val changedKinds = changedManifestPaths(pristineM0, manifest)
        assertEquals(EXPECTED_STAGED_MANIFEST_CHANGES, changedKinds)
    }

    // 28
    @Then("the ordered edit produces one complete staged reactor {string} with 20 direct children, root {string} at coordinate com.acme.refactorkit.fixture:catalog-domain:1.0.0 with JAR packaging, one root module entry {string} in the original list position, and the catalog-pricing dependency resolved to that exact coordinate")
    fun orderedEditProducesR1(reactorName: String, rootModule: String, moduleEntry: String) {
        assertEquals("R1", reactorName)
        assertEquals(NEW_MODULE, rootModule)
        assertEquals(NEW_MODULE, moduleEntry)
        ensureStagedMirror()
        val snapshot = scan(assertNotNull(stagedMirrorRoot))
        stagedScannerSnapshot = snapshot
        val reactor = attestReactor(snapshot, assertNotNull(stagedMirrorRoot))
        stagedReactor = reactor
        assertEquals(BuildModelStatus.AVAILABLE, reactor.status)
        assertEquals(20, reactor.modules.size)
        assertFalse(OLD_MODULE in reactor.modules)
        val renamed = reactor.modules.getValue(NEW_MODULE)
        assertEquals("$GROUP_ID:$NEW_ARTIFACT:1.0.0", renamed.coordinate)
        assertEquals("jar", renamed.packaging)
        val expectedModules = EXPECTED_MODULES.toMutableList().also { it[it.indexOf(OLD_MODULE)] = NEW_MODULE }
        assertEquals(expectedModules, reactor.directModules)
        assertEquals(1, reactor.directModules.count { it == NEW_MODULE })
        assertModuleDependency(snapshot.buildModels.single(), "catalog-pricing", NEW_MODULE)
    }

    // 29
    @Then("staged source and auxiliary inventories are complete, every other reactor module, coordinate, dependency, source set, and child-relative path equals {string}, and transitive consumers receive no POM edit")
    fun stagedInventoriesAndOtherModulesEqualR0(reactorName: String) {
        assertEquals("R0", reactorName)
        val stagedSnapshot = assertNotNull(stagedScannerSnapshot)
        val expectedSources = expectedSourceInventory.mapTo(linkedSetOf()) { renamePrefix(it, OLD_MODULE, NEW_MODULE) }
        val expectedAuxiliary = expectedAuxiliaryInventory.mapTo(linkedSetOf()) { renamePrefix(it, OLD_MODULE, NEW_MODULE) }
        assertEquals(expectedSources, stagedSnapshot.files.mapTo(linkedSetOf()) { it.path })
        assertEquals(expectedAuxiliary, stagedSnapshot.auxiliaryFiles.mapTo(linkedSetOf()) { it.path })

        val normalizedR1 = assertNotNull(stagedReactor).normalizedForModuleRename(NEW_MODULE, OLD_MODULE)
        assertEquals(r0, normalizedR1)
        val modifiedPaths = assertNotNull(positivePreviews.first().normalizedEdit).edits
            .filterIsInstance<FileEdit.Modify>().mapTo(linkedSetOf()) { it.path }
        assertEquals(setOf(Path.of(OLD_POM), Path.of("pom.xml"), Path.of(PRICING_POM)), modifiedPaths)
        assertFalse(Path.of("catalog-storefront/pom.xml") in modifiedPaths)
        assertFalse(Path.of("catalog-acceptance/pom.xml") in modifiedPaths)
        assertEquals(
            relativeFileInventory(pristineRoot.resolve(OLD_MODULE)),
            relativeFileInventory(assertNotNull(stagedMirrorRoot).resolve(NEW_MODULE)),
        )
    }

    // 30
    @Then("authoritative staged Maven and JDT diagnostics equal {string} exactly, with no new, changed, unavailable, or suppressed diagnostic")
    fun stagedDiagnosticsEqualD0(name: String) {
        assertEquals("D0", name)
        val snapshot = assertNotNull(stagedScannerSnapshot)
        val actual = diagnostics(snapshot)
        stagedDiagnostics = actual
        assertEquals(d0, actual)
        assertEquals(BuildModelStatus.AVAILABLE, snapshot.buildModels.single().status)
        assertTrue(actual.maven.none { it.contains("unavailable", ignoreCase = true) || it.contains("suppressed", ignoreCase = true) })
        assertTrue(actual.jdt.none { it.contains("unavailable", ignoreCase = true) || it.contains("suppressed", ignoreCase = true) })
    }

    // 31
    @Then("both previews have equal normalized edits, affected paths, summaries, warnings, risk and approval facts, origin and reactor evidence, and before and staged diagnostics when their generated PlanId values are excluded")
    fun semanticPreviewsEqualWithoutPlanId() {
        val first = positivePreviews[0]
        val second = positivePreviews[1]
        assertEquals(semanticPlan(first), semanticPlan(second))
        assertEquals(first.normalizedEdit, second.normalizedEdit)
        assertEquals(first.stagedSnapshot, second.stagedSnapshot)
        assertEquals(first.diagnosticsBefore, second.diagnosticsBefore)
        assertEquals(first.diagnosticsStaged, second.diagnosticsStaged)
    }

    // 32
    @Then("a generated PlanId is opaque correlation data and is neither compared nor accepted as semantic determinism evidence")
    fun generatedPlanIdIsExcludedFromSemanticEvidence() {
        positivePreviews.forEach { observation ->
            assertTrue(PLAN_ID_PATTERN.matches(observation.plan.id.value), observation.plan.id.value)
            assertFalse(semanticPlan(observation).toString().contains(observation.plan.id.value))
        }
        // Deliberately no cross-preview PlanId equality assertion: semanticPlan omits the field.
    }

    // 33
    @Then("both scans and previews are read-only: the pristine workspace still equals {string}, {string} and {string} remain absent, and the permanent fixture is unchanged")
    fun scansAndPreviewsAreReadOnly(manifestName: String, destination: String, enginePath: String) {
        assertEquals("M0", manifestName)
        assertEquals(NEW_MODULE, destination)
        assertEquals(".refactorkit", enginePath)
        assertEquals(pristineM0, captureExactManifest(pristineRoot))
        assertFalse(Files.exists(pristineRoot.resolve(destination), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(pristineRoot.resolve(enginePath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(permanentM0, captureExactManifest(fixtureRoot))
    }

    // 34
    @Then("no denied process, plugin, settings, credential, helper, or network authority was requested during scan, refusal, staging, diagnostics, or preview")
    fun noDeniedAuthorityWasRequested() {
        (probeObservations.values.map { it.snapshot } + listOf(s0) + listOfNotNull(stagedScannerSnapshot)).forEach { snapshot ->
            val attributes = snapshot.buildModels.single().attributes
            assertEquals("denied", attributes["buildCodeExecution"])
            assertEquals("denied", attributes["credentialsAccess"])
            assertEquals("denied", attributes["networkAccess"])
        }
        assertTrue(scannerRoots.all { it.startsWith(temporaryRoot) })
        assertTrue(plannerRoots.all { it.startsWith(temporaryRoot) })
        assertTrue(diagnosticRoots.all { it.startsWith(temporaryRoot) })
        activityMonitor.assertNoRefactorKitProcessOrSocketAuthority("scan/refusal/staging/diagnostics/preview")
    }

    // 35
    @When("the caller selects one preview and invokes the existing public PatchEngine.apply exactly once with snapshot {string}, ApplyAuthorization.explicit for surface {string} and actor {string}, and the staged-reactor diagnostics gate")
    fun applySelectedPreviewOnce(snapshotName: String, surface: String, actor: String) {
        assertEquals("S0", snapshotName)
        assertEquals("direct-library", surface)
        assertEquals("acceptance-caller", actor)
        assertEquals(0, applyCount)
        val selected = positivePreviews.first().plan
        preApplyManifest = captureExactManifest(pristineRoot)
        assertEquals(pristineM0, preApplyManifest)
        val injector = PatchFaultInjector { point, _, _ ->
            if (point == PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) {
                underLockObserved = workspaceLockIsHeld(pristineRoot)
                underLockPreconditionsExact =
                    captureExactManifest(pristineRoot, excludeEngine = true) == pristineM0 &&
                    Files.notExists(pristineRoot.resolve(NEW_MODULE)) &&
                    editableOrigins.all { origin ->
                        sha256(Files.readAllBytes(pristineRoot.resolve(origin.path))) == origin.sha256
                    } &&
                    attestReactor(scan(pristineRoot), pristineRoot) == r0
            }
        }
        val engine = PatchEngine(pristineRoot, faultInjector = injector)
        patchEngine = engine
        applyResult = engine.apply(
            selected,
            s0,
            ApplyAuthorization.explicit(surface, actor),
            positivePlanner.diagnosticsGate(selected),
        )
        applyCount += 1
        selectedTransaction = (applyResult as? ApplyResult.Applied)?.transaction
    }

    // 36
    @Then("ApplyResult.Applied returns one transaction {string} only after under-lock snapshot, destination, raw-origin, effective-model, staged-image, and diagnostic revalidation succeeds")
    fun applyReturnsOneTransactionAfterRevalidation(name: String) {
        assertEquals("T1", name)
        val applied = assertIs<ApplyResult.Applied>(assertNotNull(applyResult))
        assertEquals(1, applyCount)
        assertEquals(applied.transaction, selectedTransaction)
        assertEquals(positivePreviews.first().plan.id, applied.transaction.planId)
        assertEquals(s0.hash, applied.transaction.snapshotHashBefore)
        assertTrue(underLockObserved, "The apply authority must execute while the workspace lock is held")
        assertTrue(underLockPreconditionsExact, "Under-lock module-rename preconditions were not exact")
        val records = transactionLog().listRecordsReadOnly()
        assertEquals(1, records.size)
        assertEquals(applied.transaction.id, records.single().transaction.id)
    }

    // 37
    @Then("the committed non-engine workspace is exactly the staged file and path-kind image and reactor {string}, with no POM or regular file remaining under {string} and no path difference beyond that staged image")
    fun committedWorkspaceEqualsStagedImage(reactorName: String, oldDirectory: String) {
        assertEquals("R1", reactorName)
        assertEquals(OLD_MODULE, oldDirectory)
        val expected = assertNotNull(stagedManifest)
        assertEquals(expected, captureExactManifest(pristineRoot, excludeEngine = true))
        val oldRegular = Files.walk(pristineRoot.resolve(oldDirectory)).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.toList()
        }
        assertTrue(oldRegular.isEmpty(), oldRegular.toString())
        assertTrue(Files.isRegularFile(pristineRoot.resolve(NEW_POM), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(pristineRoot.resolve(NEW_PRODUCT), LinkOption.NOFOLLOW_LINKS))
    }

    // 38
    @Then("a fresh JavaProjectScanner scan and authoritative Maven and JDT diagnostic run attest the exact committed reactor {string}, post-image snapshot identity {string}, and diagnostic multiset {string}")
    fun freshCommittedAttestation(reactorName: String, snapshotName: String, diagnosticName: String) {
        assertEquals("R1", reactorName)
        assertEquals("S1", snapshotName)
        assertEquals("D0", diagnosticName)
        val snapshot = scan(pristineRoot)
        s1 = snapshot
        val reactor = attestReactor(snapshot, pristineRoot)
        committedReactor = reactor
        assertEquals(assertNotNull(stagedReactor), reactor)
        val actualDiagnostics = diagnostics(snapshot)
        committedDiagnostics = actualDiagnostics
        assertEquals(d0, actualDiagnostics)
        assertNotEquals(s0.hash, snapshot.hash)
    }

    // 39
    @Then("a fresh TransactionLog.listRecordsReadOnly journal inspection returns exactly one schema-v8 record with:")
    fun exactAppliedJournalRecord(table: DataTable) {
        assertEquals(EXPECTED_JOURNAL_TABLE, table.asLists())
        val record = transactionLog().listRecordsReadOnly().single()
        journalAfterApply = record
        val transaction = assertNotNull(selectedTransaction)
        assertEquals(8, record.schemaVersion)
        assertEquals(transaction.id, record.transaction.id)
        assertEquals(positivePreviews.first().plan.id, record.transaction.planId)
        assertEquals(OPERATION, record.operation)
        assertEquals(JournalState.APPLIED, record.state)
        assertEquals(assertNotNull(positivePreviews.first().normalizedEdit), record.forwardEdit)
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals("direct-library", record.transaction.approval.surface)
        assertEquals("acceptance-caller", record.transaction.approval.actor)
        assertEquals(s0.hash, record.preSnapshotHash)
        assertEquals(assertNotNull(s1).hash, record.postSnapshotHash)
        assertJournalImages(record)
        assertEquals(EXPECTED_CREATED_DIRECTORIES, record.createdDirectories.toSet())
        assertEquals(
            listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED),
            record.history.map { it.state },
        )
    }

    // 40
    @Then("the permanent fixture remains byte-identical, path-kind-identical, and free of {string}")
    fun permanentFixtureRemainsExact(enginePath: String) {
        assertEquals(".refactorkit", enginePath)
        assertEquals(permanentM0, captureExactManifest(fixtureRoot))
        assertFalse(Files.exists(fixtureRoot.resolve(enginePath), LinkOption.NOFOLLOW_LINKS))
    }

    // 41
    @When("the caller invokes the existing public PatchEngine.rollback for transaction {string} in RollbackMode.NORMAL exactly once")
    fun rollbackNormallyOnce(name: String) {
        assertEquals("T1", name)
        assertEquals(0, rollbackCount)
        rollbackResult = assertNotNull(patchEngine).rollback(assertNotNull(selectedTransaction), RollbackMode.NORMAL)
        rollbackCount += 1
    }

    // 42
    @Then("rollback succeeds for the same transaction identity without force, recovery, compensation, or a second transaction")
    fun rollbackSucceedsOnSameTransaction() {
        val applied = assertIs<ApplyResult.Applied>(assertNotNull(rollbackResult))
        assertEquals(assertNotNull(selectedTransaction), applied.transaction)
        assertEquals(1, rollbackCount)
        val records = transactionLog().listRecordsReadOnly()
        assertEquals(1, records.size)
        assertEquals(applied.transaction.id, records.single().transaction.id)
        assertEquals(JournalState.ROLLED_BACK, records.single().state)
        assertNull(records.single().failure)
    }

    // 43
    @Then("fresh read-only journal inspection still returns exactly one record, now {string}, with ordered history {string}")
    fun rolledBackJournalHistory(state: String, history: String) {
        assertEquals(JournalState.ROLLED_BACK.name, state)
        assertEquals("PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK", history)
        val record = transactionLog().listRecordsReadOnly().single()
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
        assertEquals(assertNotNull(selectedTransaction).id, record.transaction.id)
    }

    // 44
    @Then("every pristine-workspace non-engine byte, path kind, source and auxiliary inventory, reactor identity, snapshot identity, and authoritative diagnostic equals {string}, {string}, {string}, and {string} respectively")
    fun rollbackRestoresEveryIdentity(m0Name: String, r0Name: String, s0Name: String, d0Name: String) {
        assertEquals("M0", m0Name)
        assertEquals("R0", r0Name)
        assertEquals("S0", s0Name)
        assertEquals("D0", d0Name)
        assertEquals(pristineM0, captureExactManifest(pristineRoot, excludeEngine = true))
        val restored = scan(pristineRoot)
        assertEquals(expectedSourceInventory, restored.files.mapTo(linkedSetOf()) { it.path })
        assertEquals(expectedAuxiliaryInventory, restored.auxiliaryFiles.mapTo(linkedSetOf()) { it.path })
        assertEquals(r0, attestReactor(restored, pristineRoot))
        assertEquals(s0.hash, restored.hash)
        assertEquals(d0, diagnostics(restored))
    }

    // 45
    @Then("{string} again contains its exact two original files, the complete created {string} hierarchy is absent, and the permanent fixture remains exact")
    fun originalModuleRestored(oldModule: String, newModule: String) {
        assertEquals(OLD_MODULE, oldModule)
        assertEquals(NEW_MODULE, newModule)
        assertEquals(setOf("pom.xml", PRODUCT_RELATIVE_PATH), relativeFileInventory(pristineRoot.resolve(oldModule)))
        assertFalse(Files.exists(pristineRoot.resolve(newModule), LinkOption.NOFOLLOW_LINKS))
        assertEquals(permanentM0, captureExactManifest(fixtureRoot))
    }

    // 46
    @Then("the only expected engine residue is the workspace lock file plus the one advanced transaction journal record; no staging, temporary, backup, quarantine, recovery-required, second-journal, or other unexpected residue exists")
    fun onlyLockAndOneJournalRemain() {
        val engineRoot = pristineRoot.resolve(".refactorkit")
        val regularFiles = Files.walk(engineRoot).use { paths ->
            paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                .map { engineRoot.relativize(it).invariantSeparatorsPathString }
                .sorted()
                .toList()
        }
        val transaction = assertNotNull(selectedTransaction)
        assertEquals(
            listOf("transactions/${transaction.id.value}.json", "workspace.lock").sorted(),
            regularFiles,
        )
        assertFalse(transactionLog().hasOrphanedTempsReadOnly())
        val record = transactionLog().listRecordsReadOnly().single()
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertFalse(Files.exists(engineRoot.resolve("transactions/.quarantine"), LinkOption.NOFOLLOW_LINKS))
        Files.walk(pristineRoot).use { paths ->
            val unexpected = paths.filter { path ->
                val name = path.fileName?.toString().orEmpty()
                name.startsWith(".refactorkit-stage-") || name.contains("backup", ignoreCase = true) ||
                    name.contains("recovery-required", ignoreCase = true)
            }.toList()
            assertTrue(unexpected.isEmpty(), unexpected.toString())
        }
    }

    // 47
    @Then("this bounded row makes no support or qualification claim for:")
    fun exactExcludedScope(table: DataTable) {
        assertEquals(EXPECTED_EXCLUDED_SCOPE_TABLE, table.asLists())
        assertEquals(10, table.asMaps().size)
        scenario.attach(
            table.asMaps().joinToString("\n") { "- ${it.getValue("explicitly excluded scope")}" },
            "text/plain",
            "bounded-module-rename-exclusions",
        )
    }

    private fun scan(root: Path): ProjectSnapshot {
        val normalized = root.toAbsolutePath().normalize()
        assertTrue(normalized.startsWith(temporaryRoot.toAbsolutePath().normalize()), "Scanner root escaped disposable scope")
        val repository = normalized.resolve(FIXTURE_REPOSITORY_PATH).normalize()
        assertTrue(repository.startsWith(normalized) && Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS))
        scannerRoots.add(normalized)
        return JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = repository,
        ).scan(normalized)
    }

    private fun preview(
        planner: JavaRenameMavenModulePlanner,
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String?,
    ): PatchPlan {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        assertTrue(root.startsWith(temporaryRoot.toAbsolutePath().normalize()), "Planner snapshot escaped disposable scope")
        plannerRoots.add(root)
        return planner.preview(snapshot, oldModuleDir, newModuleDir, newArtifactId)
    }

    private fun authoritativeJavaDiagnostics(snapshot: ProjectSnapshot): List<Diagnostic> {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        assertTrue(root.startsWith(temporaryRoot.toAbsolutePath().normalize()), "Diagnostic root escaped disposable scope")
        diagnosticRoots.add(root)
        return JavaLanguageAdapter().authoritativeDiagnostics(snapshot, Path.of(System.getProperty("java.home")))
    }

    private fun diagnostics(snapshot: ProjectSnapshot): DiagnosticBaseline = DiagnosticBaseline(
        maven = canonicalMavenDiagnostics(snapshot.buildModels.single().diagnostics),
        jdt = canonicalJavaDiagnostics(authoritativeJavaDiagnostics(snapshot)),
    )

    private fun capturePreview(
        planner: JavaRenameMavenModulePlanner,
        snapshot: ProjectSnapshot,
        oldModuleDir: String,
        newModuleDir: String,
        newArtifactId: String,
    ): PreviewObservation {
        val plan = preview(planner, snapshot, oldModuleDir, newModuleDir, newArtifactId)
        val normalizedResult = runCatching { WorkspaceEditSimulator.normalize(plan.workspaceEdit) }
        val normalized = normalizedResult.getOrNull()
        val stagedResult = normalized?.let { runCatching { WorkspaceEditSimulator.apply(snapshot, it) } }
        val staged = stagedResult?.getOrNull()
        val beforeDiagnostics = authoritativeJavaDiagnostics(snapshot)
        val stagedDiagnostics = staged?.let(::authoritativeJavaDiagnostics)
        val rendered = runCatching { PatchPreviewRenderer(pristineRoot).render(plan) }
        return PreviewObservation(
            plan = plan,
            normalizedEdit = normalized,
            stagedSnapshot = staged,
            renderedPreview = rendered.getOrNull(),
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsStaged = stagedDiagnostics,
            normalizationFailure = normalizedResult.exceptionOrNull(),
            simulationFailure = stagedResult?.exceptionOrNull(),
            renderFailure = rendered.exceptionOrNull(),
        )
    }

    private fun semanticPlan(observation: PreviewObservation): SemanticPlan = SemanticPlan(
        operation = observation.plan.operation,
        status = observation.plan.status,
        snapshotHash = observation.plan.snapshotHash,
        confidence = observation.plan.confidence,
        requiresUserApproval = observation.plan.requiresUserApproval,
        summary = observation.plan.summary,
        affectedFiles = observation.plan.affectedFiles,
        normalizedEdit = observation.normalizedEdit,
        diagnosticsBefore = observation.plan.diagnosticsBefore,
        diagnosticsAfter = observation.plan.diagnosticsAfterPreview,
        warnings = observation.plan.warnings,
        risk = observation.plan.riskLevel.name,
        evidence = observation.plan.evidence.name,
        refusalCode = observation.plan.refusalCode,
        authorityLease = observation.plan.authorityLease,
        observedBeforeDiagnostics = observation.diagnosticsBefore,
        observedStagedDiagnostics = observation.diagnosticsStaged,
    )

    private fun previewsWithAuthority(): List<PatchPlan> =
        listOf(assertNotNull(omittedArtifactPlan, "The preserve-artifact preview was not captured")) +
            positivePreviews.map(PreviewObservation::plan)

    private fun assertMaintainedParserIdentity(attributes: Map<String, String>) {
        val implementation = assertNotNull(
            attributes["xmlParserImplementation"],
            "The immutable authority does not identify the selected XML parser implementation",
        )
        val version = assertNotNull(
            attributes["xmlParserVersion"],
            "The immutable authority does not identify the selected maintained XML parser version",
        )
        val normalized = implementation.lowercase()
        assertTrue(implementation.isNotBlank() && version.isNotBlank())
        assertFalse(normalized.contains("xmllexicaltree"), "Hand-written XmlLexicalTree was attested: $implementation")
        assertFalse(normalized.contains("hand-written") || normalized.contains("handwritten"), implementation)
        assertFalse(
            normalized.startsWith("org.refactorkit."),
            "Operation-owned hand-written parser code is not maintained parser authority: $implementation",
        )
    }

    private fun assertImmutableMap(values: Map<String, String>, label: String) {
        val before = values.toMap()
        @Suppress("UNCHECKED_CAST")
        val mutableView = values as MutableMap<String, String>
        val failure = runCatching { mutableView["cucumber-mutation-sentinel"] = "forbidden" }.exceptionOrNull()
        assertNotNull(failure, "$label accepted mutation")
        assertEquals(before, values, "$label changed after a rejected mutation")
    }

    private fun exactManifestEvidenceHash(manifest: ExactManifest): String =
        JavaRenameMavenModuleContract.hashStrings(
            manifest.entries.toSortedMap().map { (path, entry) ->
                listOf(path, entry.kind.name, entry.size.orEmpty(), entry.sha256.orEmpty()).joinToString("\u0000")
            },
        )

    private fun trackedEvidenceHash(snapshot: ProjectSnapshot): String =
        JavaRenameMavenModuleContract.hashStrings(
            snapshot.trackedFiles.sortedBy { it.path.invariantSeparatorsPathString }.map { file ->
                "${file.path.invariantSeparatorsPathString}\u0000${sha256(file.content.toByteArray(Charsets.UTF_8))}"
            },
        )

    private fun buildModelEvidenceHash(model: BuildModel): String =
        JavaRenameMavenModuleContract.hashStrings(
            model.modules.sortedBy { it.id }.map(Any::toString) + canonicalMavenDiagnostics(model.diagnostics),
        )

    private fun transientCollectionSnapshot(
        stageFacts: JavaRenameMavenModuleContract.StageFacts,
        gateExpectation: JavaRenameMavenModuleContract.GateExpectation,
        reactorView: JavaRenameMavenModuleContract.ReactorView,
        moduleView: JavaRenameMavenModuleContract.ModuleView,
        sourceSetView: JavaRenameMavenModuleContract.SourceSetView,
    ): Map<String, Any> = linkedMapOf(
        "StageFacts.baselineMavenDiagnostics" to stageFacts.baselineMavenDiagnostics.toList(),
        "StageFacts.stagedMavenDiagnostics" to stageFacts.stagedMavenDiagnostics.toList(),
        "StageFacts.baselineJdt" to stageFacts.baselineJdt.toList(),
        "StageFacts.stagedJdt" to stageFacts.stagedJdt.toList(),
        "StageFacts.baselineJdtCanonical" to stageFacts.baselineJdtCanonical.toList(),
        "StageFacts.stagedJdtCanonical" to stageFacts.stagedJdtCanonical.toList(),
        "GateExpectation.admittedCandidateSnapshotHashes" to
            gateExpectation.admittedCandidateSnapshotHashes.toSet(),
        "ReactorView.modules" to reactorView.modules.mapValues { (_, module) -> detachedModuleView(module) }.toMap(),
        "ModuleView.sourceSets" to moduleView.sourceSets.map(::detachedSourceSetView),
        "SourceSetView.sourceRoots" to sourceSetView.sourceRoots.toList(),
        "SourceSetView.generatedSourceRoots" to sourceSetView.generatedSourceRoots.toList(),
        "SourceSetView.outputDirectories" to sourceSetView.outputDirectories.toList(),
        "SourceSetView.classpathEntries" to sourceSetView.classpathEntries.toList(),
        "SourceSetView.runtimeClasspathEntries" to sourceSetView.runtimeClasspathEntries.toList(),
        "SourceSetView.dependencies" to sourceSetView.dependencies.toList(),
        "SourceSetView.attributes" to sourceSetView.attributes.toMap(),
    )

    private fun detachedModuleView(
        value: JavaRenameMavenModuleContract.ModuleView,
    ): JavaRenameMavenModuleContract.ModuleView = value.copy(
        sourceSets = value.sourceSets.map(::detachedSourceSetView),
    )

    private fun detachedSourceSetView(
        value: JavaRenameMavenModuleContract.SourceSetView,
    ): JavaRenameMavenModuleContract.SourceSetView = value.copy(
        sourceRoots = value.sourceRoots.toList(),
        generatedSourceRoots = value.generatedSourceRoots.toList(),
        outputDirectories = value.outputDirectories.toList(),
        classpathEntries = value.classpathEntries.toList(),
        runtimeClasspathEntries = value.runtimeClasspathEntries.toList(),
        dependencies = value.dependencies.toList(),
        attributes = value.attributes.toMap(),
    )

    private fun <T> attemptListMutation(
        label: String,
        values: List<T>,
        sentinel: T,
        detach: (T) -> Any = { it as Any },
    ): CollectionMutationAttempt = attemptCollectionMutation(
        label = label,
        snapshot = { values.map(detach) },
        mutation = {
            @Suppress("UNCHECKED_CAST")
            val mutable = values as MutableList<T>
            mutable.add(sentinel)
        },
    )

    private fun <T> attemptSetMutation(
        label: String,
        values: Set<T>,
        sentinel: T,
    ): CollectionMutationAttempt = attemptCollectionMutation(
        label = label,
        snapshot = { values.toSet() },
        mutation = {
            @Suppress("UNCHECKED_CAST")
            val mutable = values as MutableSet<T>
            mutable.add(sentinel)
        },
    )

    private fun <K, V> attemptMapMutation(
        label: String,
        values: Map<K, V>,
        key: K,
        value: V,
        detachValue: (V) -> Any = { it as Any },
    ): CollectionMutationAttempt = attemptCollectionMutation(
        label = label,
        snapshot = { values.mapValues { (_, entryValue) -> detachValue(entryValue) }.toMap() },
        mutation = {
            @Suppress("UNCHECKED_CAST")
            val mutable = values as MutableMap<K, V>
            mutable[key] = value
        },
    )

    private fun attemptCollectionMutation(
        label: String,
        snapshot: () -> Any,
        mutation: () -> Unit,
    ): CollectionMutationAttempt {
        val before = snapshot()
        val failure = runCatching(mutation).exceptionOrNull()
        val after = snapshot()
        return CollectionMutationAttempt(
            label = label,
            rejected = failure != null,
            unchanged = before == after,
            rejectionType = failure?.javaClass?.name,
        )
    }

    private fun changedCollectionLabels(
        expected: Map<String, Any>,
        actual: Map<String, Any>,
    ): List<String> = expected.keys.filter { label -> expected.getValue(label) != actual.getValue(label) }

    private fun Long?.orEmpty(): String = this?.toString().orEmpty()

    private fun ensureStagedMirror() {
        if (stagedMirrorRoot != null) return
        val normalized = assertNotNull(positivePreviews.first().normalizedEdit)
        val mirror = temporaryRoot.resolve("staged-attestation")
        copyNoFollow(fixtureRoot, mirror)
        normalized.edits.forEach { edit ->
            when (edit) {
                is FileEdit.Modify -> {
                    val path = mirror.resolve(edit.path)
                    Files.writeString(path, TextEdits.apply(Files.readString(path), edit.textEdits))
                }
                is FileEdit.Rename -> {
                    val source = mirror.resolve(edit.path)
                    val destination = mirror.resolve(edit.newPath)
                    Files.createDirectories(assertNotNull(destination.parent))
                    Files.move(source, destination)
                }
                is FileEdit.Create -> {
                    val destination = mirror.resolve(edit.path)
                    Files.createDirectories(assertNotNull(destination.parent))
                    Files.writeString(destination, edit.content)
                }
                is FileEdit.Delete -> Files.delete(mirror.resolve(edit.path))
            }
        }
        stagedMirrorRoot = mirror
        stagedManifest = captureExactManifest(mirror)
    }

    private fun attestReactor(snapshot: ProjectSnapshot, root: Path): ReactorAttestation {
        val model = snapshot.buildModels.single()
        val modules = model.modules.associate { module ->
            val attributes = module.attributes
            module.id to ReactorModule(
                id = module.id,
                root = module.root.toAbsolutePath().normalize().let { absolute ->
                    root.toAbsolutePath().normalize().relativize(absolute)
                },
                coordinate = listOf(
                    attributes.getValue("java.maven.groupId"),
                    attributes.getValue("java.maven.artifactId"),
                    attributes.getValue("java.maven.version"),
                ).joinToString(":"),
                packaging = attributes.getValue("java.maven.packaging"),
                sourceSets = module.sourceSets.map(::sourceSetFact),
            )
        }
        return ReactorAttestation(
            status = model.status,
            directModules = directRootModules(root),
            modules = modules,
        )
    }

    private fun sourceSetFact(sourceSet: BuildSourceSet): SourceSetFact = SourceSetFact(
        id = sourceSet.id,
        kind = sourceSet.kind.name,
        sourceRoots = sourceSet.sourceRoots.map(Path::normalize),
        generatedSourceRoots = sourceSet.generatedSourceRoots.map(Path::normalize),
        dependencies = sourceSet.moduleDependencies.map { it.targetModuleId to it.scope.name },
        sourceLevel = sourceSet.attributes["java.sourceLevel"],
        release = sourceSet.attributes["java.release"],
    )

    private fun assertModuleDependency(model: BuildModel, consumer: String, target: String) {
        val main = model.modules.single { it.id == consumer }.sourceSets.single { it.id == "main" }
        assertTrue(main.moduleDependencies.any { it.targetModuleId == target && it.scope.name == "COMPILE" })
    }

    private fun assertFileEditRow(order: Int, edit: FileEdit, row: Map<String, String>) {
        assertEquals(order.toString(), row.getValue("order"))
        assertEquals(Path.of(row.getValue("path")), edit.path)
        when (row.getValue("FileEdit")) {
            "Modify" -> {
                val modify = assertIs<FileEdit.Modify>(edit)
                val textEdit = modify.textEdits.single()
                assertEquals(row.getValue("exact source range"), formatRange(textEdit.range))
                assertEquals(row.getValue("exact new text"), textEdit.newText)
                assertEquals("", row.getValue("new path"))
            }
            "Rename" -> {
                val rename = assertIs<FileEdit.Rename>(edit)
                assertEquals("", row.getValue("exact source range"))
                assertEquals("", row.getValue("exact new text"))
                assertEquals(Path.of(row.getValue("new path")), rename.newPath)
            }
            else -> fail("Unexpected FileEdit table kind ${row.getValue("FileEdit")}")
        }
    }

    private fun assertImageRow(staged: ProjectSnapshot, row: Map<String, String>) {
        val prePath = Path.of(row.getValue("pre-image path"))
        val postPath = Path.of(row.getValue("post-image path"))
        val (preSize, preHash) = parseSizeAndHash(row.getValue("pre-image bytes and SHA-256"))
        val (postSize, postHash) = parseSizeAndHash(row.getValue("post-image bytes and SHA-256"))
        val preBytes = Files.readAllBytes(pristineRoot.resolve(prePath))
        assertEquals(preSize, preBytes.size)
        assertEquals(preHash, sha256(preBytes))
        val post = staged.trackedFiles.single { it.path == postPath }.content.toByteArray(Charsets.UTF_8)
        assertEquals(postSize, post.size)
        assertEquals(postHash, sha256(post))
        if (prePath != postPath) assertTrue(staged.trackedFiles.none { it.path == prePath })
    }

    private fun assertJournalImages(record: TransactionJournalRecord) {
        assertEquals(EXPECTED_AFFECTED_PATHS, record.preImages.mapTo(linkedSetOf()) { it.path })
        assertEquals(EXPECTED_AFFECTED_PATHS, record.postImages.mapTo(linkedSetOf()) { it.path })
        val staged = assertNotNull(positivePreviews.first().stagedSnapshot)
        val beforeByPath = s0.trackedFiles.associateBy { it.path }
        val afterByPath = staged.trackedFiles.associateBy { it.path }
        record.preImages.forEach { image ->
            assertEquals(beforeByPath[image.path]?.content, image.content, "Pre-image mismatch for ${image.path}")
            assertEquals(image.content?.toByteArray(Charsets.UTF_8)?.let(::sha256), image.contentSha256)
            if (image.content != null) assertNotNull(image.posixPermissions)
        }
        record.postImages.forEach { image ->
            assertEquals(afterByPath[image.path]?.content, image.content, "Post-image mismatch for ${image.path}")
            assertEquals(image.content?.toByteArray(Charsets.UTF_8)?.let(::sha256), image.contentSha256)
            if (image.content != null) assertNotNull(image.posixPermissions)
        }
        val prePermissions = record.preImages.associate { it.path to it.posixPermissions }
        val postPermissions = record.postImages.associate { it.path to it.posixPermissions }
        assertEquals(prePermissions[Path.of(OLD_POM)], postPermissions[Path.of(NEW_POM)])
        assertEquals(prePermissions[Path.of(OLD_PRODUCT)], postPermissions[Path.of(NEW_PRODUCT)])
    }

    private fun transactionLog(): TransactionLog {
        val path = pristineRoot.resolve(TRANSACTION_DIRECTORY).normalize()
        assertTrue(path.startsWith(pristineRoot))
        return TransactionLog(path)
    }

    private fun workspaceLockIsHeld(root: Path): Boolean {
        val path = root.resolve(WORKSPACE_LOCK)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                try {
                    val competitor = channel.tryLock()
                    if (competitor == null) true else {
                        competitor.release()
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

    private fun createPropertyManagedProbe(root: Path) {
        val path = root.resolve(PRICING_POM)
        val original = Files.readString(path)
        val withProperty = replaceExactlyOnce(
            original,
            "  <artifactId>catalog-pricing</artifactId>\n  <dependencies>",
            "  <artifactId>catalog-pricing</artifactId>\n  <properties>\n" +
                "    <catalog.model.artifact>catalog-model</catalog.model.artifact>\n" +
                "  </properties>\n  <dependencies>",
        )
        val changed = replaceExactlyOnce(
            withProperty,
            "      <artifactId>catalog-model</artifactId>\n      <version>${'$'}{project.version}</version>",
            "      <artifactId>${'$'}{catalog.model.artifact}</artifactId>\n      <version>${'$'}{project.version}</version>",
        )
        Files.writeString(path, changed)
        val project = parseXml(path)
        val property = directChildren(directChildren(project, "properties").single(), "catalog.model.artifact").single()
        assertEquals(OLD_ARTIFACT, property.textContent.trim())
        val dependency = directDependencies(project).single { directChildText(it, "groupId") == GROUP_ID }
        assertEquals("${'$'}{catalog.model.artifact}", directChildText(dependency, "artifactId"))
    }

    private fun createDuplicateOriginProbe(root: Path) {
        val path = root.resolve(PRICING_POM)
        val original = Files.readString(path)
        val dependency = """    <dependency>
      <groupId>$GROUP_ID</groupId>
      <artifactId>$OLD_ARTIFACT</artifactId>
      <version>${'$'}{project.version}</version>
    </dependency>"""
        val changed = replaceExactlyOnce(original, dependency, "$dependency\n$dependency")
        Files.writeString(path, changed)
        val project = parseXml(path)
        assertEquals(
            2,
            directDependencies(project).count {
                directChildText(it, "groupId") == GROUP_ID && directChildText(it, "artifactId") == OLD_ARTIFACT
            },
        )
    }

    private fun requiredBlockerTokens(probe: String): List<Set<String>> = when (probe) {
        "inferred artifact coordinate" -> listOf(setOf("explicit"), setOf("coordinate", "artifact"), setOf("blank", "absent", "nonblank"))
        "property-managed dependency origin" -> listOf(setOf("property"), setOf("origin"))
        "ambiguous dependency origin" -> listOf(setOf("duplicate", "ambiguous"), setOf("origin", "mapping"))
        else -> error("Unknown probe $probe")
    }

    private fun directRootModules(root: Path): List<String> {
        val project = parseXml(root.resolve("pom.xml"))
        val modules = directChildren(project, "modules").single()
        return directChildren(modules, "module").map { it.textContent.trim() }
    }

    private fun directDependencies(project: Element): List<Element> =
        directChildren(project, "dependencies").singleOrNull()?.let { directChildren(it, "dependency") }.orEmpty()

    private fun parseXml(path: Path): Element {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            factory.newDocumentBuilder().parse(input).documentElement
        }
    }

    private fun directChildren(parent: Element, name: String): List<Element> = parent.childNodes.asSequence()
        .filterIsInstance<Element>()
        .filter { (it.localName ?: it.nodeName.substringAfter(':')) == name }
        .toList()

    private fun directChildText(parent: Element, name: String): String? =
        directChildren(parent, name).singleOrNull()?.textContent?.trim()

    private fun NodeList.asSequence(): Sequence<Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }

    private fun parseRange(value: String): SourceRange {
        val match = RANGE_PATTERN.matchEntire(value) ?: fail("Invalid range: $value")
        return SourceRange(
            org.refactorkit.core.SourcePosition(match.groupValues[1].toInt(), match.groupValues[2].toInt()),
            org.refactorkit.core.SourcePosition(match.groupValues[3].toInt(), match.groupValues[4].toInt()),
        )
    }

    private fun formatRange(range: SourceRange): String =
        "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"

    private fun textAt(content: String, range: SourceRange): String {
        assertEquals(range.start.line, range.end.line, "Fixture origin must occupy one line")
        val line = content.split('\n')[range.start.line]
        return line.substring(range.start.character, range.end.character)
    }

    private fun exactTextRangeCount(content: String, literal: String, range: SourceRange): Int {
        assertEquals(literal, textAt(content, range))
        return listOf(range).count { textAt(content, it) == literal }
    }

    private fun parseSizeAndHash(value: String): Pair<Int, String> {
        val parts = value.split(';').map(String::trim)
        assertEquals(2, parts.size)
        assertTrue(SHA256_PATTERN.matches(parts[1]))
        return parts[0].toInt() to parts[1]
    }

    private fun canonicalMavenDiagnostics(diagnostics: List<BuildModelDiagnostic>): List<String> = diagnostics.map { diagnostic ->
        listOf(
            diagnostic.severity.name,
            diagnostic.code,
            diagnostic.moduleId.orEmpty(),
            diagnostic.message,
        ).joinToString("\u0000")
    }.sorted()

    private fun canonicalJavaDiagnostics(diagnostics: List<Diagnostic>): List<String> = diagnostics.map { diagnostic ->
        val location = diagnostic.location
        listOf(
            diagnostic.severity.name,
            diagnostic.code.orEmpty(),
            diagnostic.evidence?.name.orEmpty(),
            diagnostic.category?.name.orEmpty(),
            diagnostic.locationPrecision.name,
            location?.path?.invariantSeparatorsPathString.orEmpty(),
            location?.range?.let(::formatRange).orEmpty(),
            diagnostic.details.fields.entries.joinToString("|") { "${it.key}=${it.value}" },
            diagnostic.message,
        ).joinToString("\u0000")
    }.sorted()

    private fun relativeFileInventory(root: Path): Set<String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .map { root.relativize(it).invariantSeparatorsPathString }
            .collect(java.util.stream.Collectors.toCollection(::linkedSetOf))
    }

    private fun renamePrefix(path: Path, from: String, to: String): Path =
        if (path.nameCount > 0 && path.getName(0).toString() == from) {
            Path.of(to).resolve(Path.of(from).relativize(path)).normalize()
        } else path

    private fun changedManifestPaths(before: ExactManifest, after: ExactManifest): Set<String> =
        (before.entries.keys + after.entries.keys).filterTo(linkedSetOf()) { before.entries[it] != after.entries[it] }

    private fun replaceExactlyOnce(content: String, old: String, new: String): String {
        assertEquals(1, Regex(Regex.escape(old)).findAll(content).count(), "Probe pre-image did not contain one exact origin")
        return content.replace(old, new)
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-java"))
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
        assertEquals(captureExactManifest(source), captureExactManifest(target))
    }

    private fun copyPosixPermissions(source: Path, target: Path) {
        runCatching {
            val permissions = Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)
            Files.setPosixFilePermissions(target, permissions)
        }
    }

    private fun captureExactManifest(root: Path, excludeEngine: Boolean = false): ExactManifest {
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
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "Symbolic link refused in manifest: $dir" }
                    entries[relative] = ManifestEntry(PathKind.DIRECTORY, null, null)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = manifestPath(root, file)
                    if (excludeEngine && (relative == ".refactorkit" || relative.startsWith(".refactorkit/"))) {
                        return FileVisitResult.CONTINUE
                    }
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "Symbolic link refused in manifest: $file" }
                    require(attrs.isRegularFile) { "Non-regular manifest entry refused: $file" }
                    val bytes = Files.readAllBytes(file)
                    entries[relative] = ManifestEntry(PathKind.REGULAR_FILE, bytes.size.toLong(), sha256(bytes))
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

    private data class CollectionMutationAttempt(
        val label: String,
        val rejected: Boolean,
        val unchanged: Boolean,
        val rejectionType: String?,
    )

    private data class CollectionBoundaryCounts(
        val total: Int,
        val callerAliasesIsolated: Int,
        val exposedMutationsRejected: Int,
        val rejectedWithoutPartialChange: Int,
        val constructionTimeValuesExact: Int,
    )

    private data class ProbeObservation(
        val name: String,
        val condition: String,
        val arguments: String,
        val requiredBlocker: String,
        val root: Path,
        val snapshot: ProjectSnapshot,
        val plan: PatchPlan,
        val before: ExactManifest,
        val after: ExactManifest,
    )

    private data class PreviewObservation(
        val plan: PatchPlan,
        val normalizedEdit: WorkspaceEdit?,
        val stagedSnapshot: ProjectSnapshot?,
        val renderedPreview: String?,
        val diagnosticsBefore: List<Diagnostic>,
        val diagnosticsStaged: List<Diagnostic>?,
        val normalizationFailure: Throwable?,
        val simulationFailure: Throwable?,
        val renderFailure: Throwable?,
    )

    private data class SemanticPlan(
        val operation: String,
        val status: PatchStatus,
        val snapshotHash: String,
        val confidence: Double,
        val requiresUserApproval: Boolean,
        val summary: String,
        val affectedFiles: Set<Path>,
        val normalizedEdit: WorkspaceEdit?,
        val diagnosticsBefore: List<Diagnostic>,
        val diagnosticsAfter: List<Diagnostic>,
        val warnings: List<String>,
        val risk: String,
        val evidence: String,
        val refusalCode: String?,
        val authorityLease: org.refactorkit.core.OperationAuthorityLease?,
        val observedBeforeDiagnostics: List<Diagnostic>,
        val observedStagedDiagnostics: List<Diagnostic>?,
    )

    private data class OriginExpectation(
        val role: String,
        val path: Path,
        val sha256: String,
        val range: SourceRange,
        val literal: String,
        val effectiveProof: String,
        val count: Int,
    )

    private data class DiagnosticBaseline(
        val maven: List<String>,
        val jdt: List<String>,
    )

    private data class ReactorAttestation(
        val status: BuildModelStatus,
        val directModules: List<String>,
        val modules: Map<String, ReactorModule>,
    ) {
        fun normalizedForModuleRename(from: String, to: String): ReactorAttestation {
            val normalizedDirect = directModules.map { if (it == from) to else it }
            val normalizedModules = modules.map { (id, module) ->
                val normalized = module.normalizedForModuleRename(from, to)
                (if (id == from) to else id) to normalized
            }.toMap()
            return copy(directModules = normalizedDirect, modules = normalizedModules)
        }
    }

    private data class ReactorModule(
        val id: String,
        val root: Path,
        val coordinate: String,
        val packaging: String,
        val sourceSets: List<SourceSetFact>,
    ) {
        fun normalizedForModuleRename(from: String, to: String): ReactorModule = copy(
            id = if (id == from) to else id,
            root = renamePathPrefix(root, from, to),
            coordinate = coordinate.split(':').let { parts ->
                if (parts.size == 3 && parts[1] == from) "${parts[0]}:$to:${parts[2]}" else coordinate
            },
            sourceSets = sourceSets.map { it.normalizedForModuleRename(from, to) },
        )
    }

    private data class SourceSetFact(
        val id: String,
        val kind: String,
        val sourceRoots: List<Path>,
        val generatedSourceRoots: List<Path>,
        val dependencies: List<Pair<String, String>>,
        val sourceLevel: String?,
        val release: String?,
    ) {
        fun normalizedForModuleRename(from: String, to: String): SourceSetFact = copy(
            sourceRoots = sourceRoots.map { renamePathPrefix(it, from, to) },
            generatedSourceRoots = generatedSourceRoots.map { renamePathPrefix(it, from, to) },
            dependencies = dependencies.map { (target, scope) -> (if (target == from) to else target) to scope },
        )
    }

    private enum class PathKind { DIRECTORY, REGULAR_FILE, SYMLINK }

    private data class ManifestEntry(
        val kind: PathKind,
        val size: Long?,
        val sha256: String?,
    )

    private data class ExactManifest(val entries: Map<String, ManifestEntry>)

    private data class ActivityEvidence(
        val processEvents: List<String>,
        val socketEvents: List<String>,
        val newDescendants: Set<Long>,
        val note: String,
    ) {
        val deniedAuthorityObserved: Boolean
            get() = processEvents.isNotEmpty() || socketEvents.isNotEmpty() || newDescendants.isNotEmpty()

        fun toJson(): String = """{
  "processEvents": ${jsonArray(processEvents)},
  "socketEvents": ${jsonArray(socketEvents)},
  "newDescendants": ${newDescendants.sorted().joinToString(prefix = "[", postfix = "]")},
  "deniedAuthorityObserved": $deniedAuthorityObserved,
  "note": "${jsonEscape(note)}"
}"""

        private fun jsonArray(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]") { "\"${jsonEscape(it)}\"" }

        private fun jsonEscape(value: String): String = value
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    }

    private class DeniedAuthorityMonitor private constructor(
        private val recording: Recording,
        private val outputDirectory: Path,
        private val baselineDescendants: Set<Long>,
    ) {
        private var dumpSequence = 0
        private val observedDescendants = linkedSetOf<Long>()
        var isRunning: Boolean = true
            private set

        fun checkpoint(label: String) {
            require(isRunning)
            observedDescendants += currentDescendants() - baselineDescendants
            evidence(label)
        }

        fun assertNoRefactorKitProcessOrSocketAuthority(label: String) {
            val evidence = evidence(label)
            assertTrue(evidence.processEvents.isEmpty(), "RefactorKit process authority observed: ${evidence.processEvents}")
            assertTrue(evidence.socketEvents.isEmpty(), "RefactorKit socket authority observed: ${evidence.socketEvents}")
            assertTrue(evidence.newDescendants.isEmpty(), "New child process observed: ${evidence.newDescendants}")
        }

        fun finish(): ActivityEvidence {
            if (!isRunning) return ActivityEvidence(emptyList(), emptyList(), observedDescendants, "already stopped")
            observedDescendants += currentDescendants() - baselineDescendants
            recording.stop()
            isRunning = false
            val path = outputDirectory.resolve("final.jfr")
            recording.dump(path)
            val events = RecordingFile.readAllEvents(path)
            recording.close()
            return activityEvidence(events, observedDescendants, "final")
        }

        private fun evidence(label: String): ActivityEvidence {
            val path = outputDirectory.resolve("checkpoint-${dumpSequence++}.jfr")
            recording.dump(path)
            return activityEvidence(RecordingFile.readAllEvents(path), observedDescendants, label)
        }

        private fun activityEvidence(
            events: List<RecordedEvent>,
            descendants: Set<Long>,
            note: String,
        ): ActivityEvidence {
            val relevant = events.filter(::hasRefactorKitFrame)
            val processEvents = relevant.filter { it.eventType.name == "jdk.ProcessStart" }.map { event ->
                "pid=${event.getLong("pid")} command=${event.getString("command")}"
            }
            val socketEvents = relevant.filter { it.eventType.name in setOf("jdk.SocketRead", "jdk.SocketWrite") }.map { event ->
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
        const val MAVEN_PROVIDER = "maven-effective-v1"
        const val OPERATION = "java.renameMavenModule"
        const val OLD_MODULE = "catalog-model"
        const val NEW_MODULE = "catalog-domain"
        const val OLD_ARTIFACT = "catalog-model"
        const val NEW_ARTIFACT = "catalog-domain"
        const val GROUP_ID = "com.acme.refactorkit.fixture"
        const val ROOT_ARTIFACT = "java-maven-move-class-authority-reactor"
        const val OLD_POM = "catalog-model/pom.xml"
        const val NEW_POM = "catalog-domain/pom.xml"
        const val PRICING_POM = "catalog-pricing/pom.xml"
        const val PRODUCT_RELATIVE_PATH = "src/main/java/com/acme/catalog/legacy/Product.java"
        const val OLD_PRODUCT = "$OLD_MODULE/$PRODUCT_RELATIVE_PATH"
        const val NEW_PRODUCT = "$NEW_MODULE/$PRODUCT_RELATIVE_PATH"
        const val WORKSPACE_LOCK = ".refactorkit/workspace.lock"
        const val TRANSACTION_DIRECTORY = ".refactorkit/transactions"
        const val ROOT_POM_SHA256 = "f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c"
        const val CHILD_POM_SHA256 = "d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e"
        const val PRICING_POM_SHA256 = "88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc"

        val EXPECTED_MODULES = listOf(
            "catalog-common",
            "catalog-generated-support",
            "catalog-inventory",
            "catalog-shipping",
            "catalog-search",
            "catalog-recommendations",
            "catalog-admin",
            "catalog-batch",
            "customer-model",
            "customer-notifications",
            "order-model",
            "order-processing",
            "payment-contract",
            "reporting-core",
            "reporting-unrelated",
            OLD_MODULE,
            "catalog-pricing",
            "catalog-storefront",
            "catalog-acceptance",
            "catalog-decoy",
        )

        val PROBE_NAMES = listOf(
            "inferred artifact coordinate",
            "property-managed dependency origin",
            "ambiguous dependency origin",
        )

        val EXPECTED_SOURCE_PATHS = setOf(
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java",
            "catalog-decoy/src/main/java/com/acme/decoy/Product.java",
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java",
            OLD_PRODUCT,
            "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java",
            "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java",
            "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java",
        )

        val EXPECTED_AFFECTED_PATHS = setOf(
            Path.of(OLD_POM),
            Path.of("pom.xml"),
            Path.of(PRICING_POM),
            Path.of(NEW_POM),
            Path.of(OLD_PRODUCT),
            Path.of(NEW_PRODUCT),
        )

        val EXPECTED_CREATED_DIRECTORIES = setOf(
            Path.of("catalog-domain"),
            Path.of("catalog-domain/src"),
            Path.of("catalog-domain/src/main"),
            Path.of("catalog-domain/src/main/java"),
            Path.of("catalog-domain/src/main/java/com"),
            Path.of("catalog-domain/src/main/java/com/acme"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy"),
        )

        val EXPECTED_STAGED_MANIFEST_CHANGES = buildSet {
            add(OLD_POM)
            add("pom.xml")
            add(PRICING_POM)
            add(OLD_PRODUCT)
            add(NEW_POM)
            add(NEW_PRODUCT)
            EXPECTED_CREATED_DIRECTORIES.forEach { add(it.invariantSeparatorsPathString) }
        }

        val EXPECTED_PROBE_TABLE = listOf(
            listOf("probe", "isolated raw-POM condition", "planner arguments", "required refusal evidence"),
            listOf(
                "inferred artifact coordinate",
                "every probe raw POM byte equals the permanent fixture",
                "oldModuleDir=\"catalog-model\", newModuleDir=\"catalog-domain\", and a blank newArtifactId that asks the planner to infer it",
                "nonblank caller-explicit coordinate intent is absent",
            ),
            listOf(
                "property-managed dependency origin",
                "the catalog-pricing dependency artifact text is `${'$'}{catalog.model.artifact}` and that property effectively resolves to `catalog-model`",
                "oldModuleDir=\"catalog-model\", newModuleDir=\"catalog-domain\", newArtifactId=\"catalog-domain\"",
                "property-managed edited origin",
            ),
            listOf(
                "ambiguous dependency origin",
                "catalog-pricing has two direct literal dependency artifact origins whose effective identities both resolve to the exact catalog-model reactor child",
                "oldModuleDir=\"catalog-model\", newModuleDir=\"catalog-domain\", newArtifactId=\"catalog-domain\"",
                "duplicate effective-to-raw origin mapping",
            ),
        )

        val EXPECTED_ORIGIN_TABLE = listOf(
            listOf("role", "raw POM", "raw POM SHA-256", "element-text range", "literal", "effective proof", "exact origin count"),
            listOf(
                "child project artifact",
                OLD_POM,
                CHILD_POM_SHA256,
                "9:14-9:27",
                OLD_ARTIFACT,
                "own project coordinate com.acme.refactorkit.fixture:catalog-model:1.0.0 with JAR packaging",
                "1",
            ),
            listOf(
                "root direct module entry",
                "pom.xml",
                ROOT_POM_SHA256,
                "74:12-74:25",
                OLD_MODULE,
                "the selected root module declaration resolves directly to workspace child directory catalog-model",
                "1",
            ),
            listOf(
                "direct dependency artifact",
                PRICING_POM,
                PRICING_POM_SHA256,
                "13:18-13:31",
                OLD_ARTIFACT,
                "catalog-pricing directly compile-depends on com.acme.refactorkit.fixture:catalog-model:1.0.0",
                "1",
            ),
        )

        val EXPECTED_EDIT_TABLE = listOf(
            listOf("order", "FileEdit", "path", "exact source range", "exact new text", "new path"),
            listOf("1", "Modify", OLD_POM, "9:14-9:27", NEW_ARTIFACT, ""),
            listOf("2", "Modify", "pom.xml", "74:12-74:25", NEW_MODULE, ""),
            listOf("3", "Modify", PRICING_POM, "13:18-13:31", NEW_ARTIFACT, ""),
            listOf("4", "Rename", OLD_POM, "", "", NEW_POM),
            listOf("5", "Rename", OLD_PRODUCT, "", "", NEW_PRODUCT),
        )

        val EXPECTED_IMAGE_TABLE = listOf(
            listOf("pre-image path", "post-image path", "pre-image bytes and SHA-256", "post-image bytes and SHA-256"),
            listOf(
                "pom.xml",
                "pom.xml",
                "2717; $ROOT_POM_SHA256",
                "2718; 1c0fe9b9ca5383628f346877afff52fd6afe00d1223ef391e736c5a3b5ceb23f",
            ),
            listOf(
                OLD_POM,
                NEW_POM,
                "397; $CHILD_POM_SHA256",
                "398; e9a34b04e5d408a9f2f6444a30ed118d8dd2d88bf880e67ff297f296092ca17b",
            ),
            listOf(
                PRICING_POM,
                PRICING_POM,
                "3337; $PRICING_POM_SHA256",
                "3338; 10831298857e61c7f054200c046a277a29467f5d0cddc34f17c800c776fe307f",
            ),
            listOf(
                OLD_PRODUCT,
                NEW_PRODUCT,
                "94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663",
                "94; 7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663",
            ),
        )

        val EXPECTED_JOURNAL_TABLE = listOf(
            listOf("journal field", "exact value"),
            listOf("transaction", "T1, with one opaque transaction ID bound to the selected preview"),
            listOf("operation", OPERATION),
            listOf("state", "APPLIED"),
            listOf("forward edit", "the exact normalized five-entry WorkspaceEdit above"),
            listOf("approval", "EXPLICIT_APPLY, direct-library, acceptance-caller"),
            listOf("snapshot identities", "pre-image S0 and post-image S1"),
            listOf("images", "exact complete pre-images, post-images, permissions, and destination-directory creation facts"),
            listOf("ordered history", "PREPARED, APPLYING, APPLIED"),
        )

        val EXPECTED_EXCLUDED_SCOPE_TABLE = listOf(
            listOf("explicitly excluded scope"),
            listOf("creating an additional Maven module or POM"),
            listOf("nested modules or a module not declared directly by the selected root aggregator"),
            listOf("positive editing of profile-declared, property-managed, inherited, duplicate, or ambiguous origins"),
            listOf("groupId, version, packaging, type, classifier, parent-coordinate, or other coordinate changes"),
            listOf("JPMS descriptors or module-path reactors"),
            listOf("CLI, daemon, LSP, MCP, or other protocol surfaces"),
            listOf("recipe execution or recipe composition"),
            listOf("packaged-runtime, native-host, operating-system, architecture, or cross-platform qualification"),
            listOf("disposable real-project acceptance or general Maven project support"),
            listOf("completion of the broad Java module rename or move roadmap item"),
        )

        val FORBIDDEN_ACTIVE_POM_ELEMENTS = setOf(
            "pluginRepositories",
            "repositories",
            "plugins",
            "plugin",
            "annotationProcessorPaths",
        )

        val FORBIDDEN_WORKSPACE_INPUTS = setOf(
            "mvnw",
            "mvnw.cmd",
            ".mvn",
            "settings.xml",
        )

        val RANGE_PATTERN = Regex("(\\d+):(\\d+)-(\\d+):(\\d+)")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        val PLAN_ID_PATTERN = Regex("plan-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        val EXPECTED_XML_PARSER_POLICY = linkedMapOf(
            "xmlParserSelection" to "MAINTAINED_NON_EXECUTING",
            "xmlDtdProcessing" to "DISABLED",
            "xmlExternalGeneralEntities" to "DISABLED",
            "xmlExternalParameterEntities" to "DISABLED",
            "xmlExternalDtdAccess" to "DENIED",
        )
        val EXPECTED_XML_RANGE_AUTHORITY = setOf(
            Path.of(OLD_POM) to "9:14-9:27",
            Path.of("pom.xml") to "74:12-74:25",
            Path.of(PRICING_POM) to "13:18-13:31",
        )

        private fun renamePathPrefix(path: Path, from: String, to: String): Path =
            if (path.nameCount > 0 && path.getName(0).toString() == from) {
                Path.of(to).resolve(Path.of(from).relativize(path)).normalize()
            } else path
    }
}

package org.refactorkit.cli.acceptance

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.owningBuildSourceRoots
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaLexer
import org.refactorkit.java.JavaMoveClassCandidateClassification
import org.refactorkit.java.JavaMoveClassGuidanceBlocker
import org.refactorkit.java.JavaMoveClassGuidanceCandidateCompleteness
import org.refactorkit.java.JavaMoveClassGuidanceJavaCandidate
import org.refactorkit.java.JavaMoveClassGuidanceOccurrence
import org.refactorkit.java.JavaMoveClassGuidanceOmissionKind
import org.refactorkit.java.JavaMoveClassGuidanceResidual
import org.refactorkit.java.JavaMoveClassGuidanceResidualKind
import org.refactorkit.java.JavaMoveClassGuidanceRestorationKind
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPreview
import org.refactorkit.java.JavaMoveClassReviewOnlyGuidance
import org.refactorkit.java.JavaMoveClassSelectedMissingBinaryRecord
import org.refactorkit.java.JavaMoveClassTargetAuthorityLease
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.MavenSelectedDescriptorAuthorityContext
import org.refactorkit.java.MavenSelectedDescriptorLayer
import org.refactorkit.java.captureMavenSelectedDescriptorAuthorityContext
import org.refactorkit.java.JdtJavaDiagnosticCategory
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.jar.JarFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaMavenMoveClassApplyAuthoritySteps {
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private var recordedState: WorkspaceState? = null
    private var scanResult: CliResult? = null
    private var previewResult: CliResult? = null
    private var applyResult: CliResult? = null
    private var previewEvidence: RefactoringEvidence? = null
    private var previewGateCodes: Set<String> = emptySet()
    private var authoritySnapshot: ProjectSnapshot? = null
    private var expectedObserverSourceSets: Set<String> = emptySet()
    private var closurePreviewPlan: PatchPlan? = null
    private var closurePreviewGateCodes: Set<String> = emptySet()
    private var offlineArtifactVariant = false
    private var offlineAuthoritySnapshot: ProjectSnapshot? = null
    private var offlineAuthoritySetupPreview: JavaMoveClassPreview? = null
    private var offlineAuthorityPreviews: List<JavaMoveClassPreview> = emptyList()
    private var approvedOfflinePreview: JavaMoveClassPreview? = null
    private var offlinePreApplyState: WorkspaceState? = null
    private var offlineTransaction: Transaction? = null
    private var offlineApplyResult: ApplyResult? = null
    private var offlineAfterSnapshot: ProjectSnapshot? = null
    private var offlineRollbackSnapshot: ProjectSnapshot? = null
    private var explicitTransitiveScopeVariant = false
    private var authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-007"
    private var explicitTestChildren: List<FixtureDependency> = emptyList()
    private var fixtureRepositoryPomHashes: Map<Path, String> = emptyMap()
    private var descriptorPruningVariant = false
    private var alternateDescriptorPathVariant = false
    private var descriptorChildren: List<DescriptorChild> = emptyList()
    private var descriptorRequiredInputHashes: Map<Path, String> = emptyMap()
    private var descriptorSecondSnapshot: ProjectSnapshot? = null
    private var descriptorSelectorEvidenceHash: String? = null
    private var alternateDescriptorSnapshot: ProjectSnapshot? = null
    private var alternateDescriptorPreview: JavaMoveClassPreview? = null
    private var alternateDescriptorPreState: WorkspaceState? = null
    private var ordinaryMissingVariant = false
    private var ordinaryDriftResults: List<OrdinaryDriftResult> = emptyList()
    private var selectedDescriptorVariant = false
    private var selectedDescriptorProfile = AUTH_011_PROFILE
    private var selectedDescriptorAuthorityContext: MavenSelectedDescriptorAuthorityContext? = null
    private var selectedDescriptorSnapshot: ProjectSnapshot? = null
    private var selectedDescriptorPlan: PatchPlan? = null
    private var selectedDescriptorRecordedState: DescriptorAuthorityState? = null
    private var selectedDescriptorLayer: String? = null
    private var selectedDescriptorCondition: String? = null
    private var selectedDescriptorMutation: String? = null
    private var selectedDescriptorPermanentHashes: Map<Path, String> = emptyMap()
    private var guidanceRows: List<Map<String, String>> = emptyList()
    private var guidanceCases: List<GuidanceCase> = emptyList()
    private val artifactEvidenceOutput: Req003ArtifactEvidenceVariantOutputPort =
        NioReq003ArtifactEvidenceVariantOutputAdapter

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-003")
    fun prepareReviewOnlyGuidanceScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-003-")
        val generatedInventory = workspaceRoot.resolve(GENERATED_INVENTORY_EVIDENCE_PATH)
        val sourceInventory = workspaceRoot.resolve(SOURCE_INVENTORY_EVIDENCE_PATH)
        assertTrue(Files.isRegularFile(generatedInventory, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(sourceInventory, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        scenario.attach(
            "REQ-JAVA-MAVEN-MOVE-AUTH-003 evaluates four independent, safely readable authority-loss " +
                "variants in isolated copies. The permanent fixture and its checked-in expected source and " +
                "generated-root inventories remain unchanged.",
            "text/plain",
            "review-only-guidance-isolation",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-005")
    fun prepareLexicalFallbackScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-005-")

        // This no-residue scenario needs a real review-only plan while the permanent
        // fixture remains the binding-clean baseline for the unselected positive slice.
        val relevantObserver = workspaceRoot.resolve(PRODUCT_STEPS_PATH)
        val source = Files.readString(relevantObserver)
        assertTrue(source.endsWith("}\n"), "The observer fixture must end with one class-closing brace")
        Files.writeString(relevantObserver, source.removeSuffix("}\n"))
        scenario.attach(
            "The command runs against an isolated fixture copy with one relevant parse failure " +
                "so the current CLI emits LEXICAL_FALLBACK; the permanent positive fixture is unchanged.",
            "text/plain",
            "lexical-fallback-variant",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-007")
    fun prepareOfflineMissingAuthorityScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-007-")
        offlineArtifactVariant = true
        authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-007"
        val artifact = workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH)
        assertEquals(EXTERNAL_ARTIFACT_SHA256, sha256(Files.readAllBytes(artifact)))
        assertEquals(
            setOf("com.acme.fixture.external.PriceAuthority"),
            JarFile(artifact.toFile(), false).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }
                    .toSet()
            },
            "The expected artifact identity must provide only the manifest-inventoried unrelated type",
        )
        Files.delete(artifact)
        assertFalse(Files.exists(artifact, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)))
        scenario.attach(
            "The scenario runs in an isolated permanent-fixture copy. The catalog-price-contract JAR " +
                "was hash-checked and removed; its hash-bound identity manifest remains as negative-presence evidence.",
            "text/plain",
            "offline-missing-leaf-variant",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-009")
    fun prepareExplicitTransitiveTestScopeScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-009-")
        explicitTransitiveScopeVariant = true
        authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-009"
        val repository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH)
        assertTrue(Files.isDirectory(repository), "The isolated Maven fixture repository must exist")
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml")), "The fixture must not supply Maven settings")
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")), "The fixture must not supply Maven user configuration")
        scenario.attach(
            "The acceptance harness activates only profile $AUTH_009_PROFILE and supplies the copied " +
                "fixture-repository path directly to JavaProjectScanner with network resolution disabled; " +
                "no user/global Maven settings or credentials are read.",
            "text/plain",
            "bounded-maven-discovery-seam",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-010")
    fun prepareDescriptorPruningScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-010-")
        descriptorPruningVariant = true
        authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-010"
        EXPECTED_AUTH_010_CHILDREN.forEach { child ->
            val base = repositoryPath(child.groupId, child.artifactId, child.version)
            val pom = workspaceRoot.resolve("$base/${child.artifactId}-${child.version}.pom")
            val jar = workspaceRoot.resolve("$base/${child.artifactFileName()}")
            Files.deleteIfExists(pom)
            Files.deleteIfExists(jar)
            assertFalse(Files.exists(pom, LinkOption.NOFOLLOW_LINKS), "REQ-010 child POM remains: $pom")
            assertFalse(Files.exists(jar, LinkOption.NOFOLLOW_LINKS), "REQ-010 child JAR remains: $jar")
        }
        assertTrue(
            AUTH_009_REPOSITORY_POMS.all { Files.isRegularFile(fixtureTemplate.resolve(it)) },
            "The static REQ-009 fixture assets must remain materialized",
        )
        scenario.attach(
            "The scenario runs against an isolated fixture copy with REQ-010 selected through the scanner seam. " +
                "All six child POM/JAR variants are absent only in that copy; static REQ-009 assets are unchanged.",
            "text/plain",
            "descriptor-pruning-variant",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-011")
    fun prepareOrdinaryMissingLeafScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-011-")
        ordinaryMissingVariant = true
        authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-011"
        val pom = workspaceRoot.resolve(ORDINARY_MISSING_POM)
        val jar = workspaceRoot.resolve(ORDINARY_MISSING_JAR)
        val sidecar = jar.resolveSibling("${jar.fileName}.refactorkit-evidence")
        assertTrue(Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS), "REQ-011 selected POM must be present")
        assertFalse(Files.exists(jar, LinkOption.NOFOLLOW_LINKS), "REQ-011 selected JAR must be absent")
        assertFalse(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS), "REQ-011 must remain sidecar-free")
        scenario.attach(
            "The acceptance harness activates only profile $AUTH_011_PROFILE and supplies the isolated " +
                "fixture-repository directly to the offline scanner. The selected POM is present while the " +
                "ordinary JAR and every adjacent identity/type sidecar remain absent under no-follow checks.",
            "text/plain",
            "ordinary-missing-leaf-variant",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-012")
    fun prepareSelectedDescriptorRefusalScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-012-")
        selectedDescriptorVariant = true
        ordinaryMissingVariant = true
        authorityRequirementId = "REQ-JAVA-MAVEN-MOVE-AUTH-012"
        selectedDescriptorPermanentHashes = AUTH_012_STATIC_PATHS.associate { relative ->
            val path = fixtureTemplate.resolve(relative)
            assertTrue(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Missing REQ-012 static asset: $relative")
            path to sha256(Files.readAllBytes(path))
        }
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        scenario.attach(
            "Each outline row receives a fresh isolated fixture copy. Profile selection and .pom template swaps " +
                "are confined to that copy; permanent fixture assets are hash-guarded.",
            "text/plain",
            "selected-descriptor-isolation",
        )
    }

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-006")
    fun prepareUnrelatedWarningScenario(scenario: Scenario) {
        prepareIsolatedFixture("refactorkit-move-auth-006-")
        introduceMissingTypeWarning(
            REPORTING_SOURCE_PATH,
            "MissingReportingType",
            "unrelatedWarning",
        )
        scenario.attach(
            "The preview runs against an isolated fixture copy with one type-resolution warning " +
                "introduced only in reporting-unrelated:main; the permanent fixture is unchanged.",
            "text/plain",
            "unrelated-warning-variant",
        )
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-003 or @REQ-JAVA-MAVEN-MOVE-AUTH-005 or @REQ-JAVA-MAVEN-MOVE-AUTH-006 or @REQ-JAVA-MAVEN-MOVE-AUTH-007 or @REQ-JAVA-MAVEN-MOVE-AUTH-009 or @REQ-JAVA-MAVEN-MOVE-AUTH-010 or @REQ-JAVA-MAVEN-MOVE-AUTH-011 or @REQ-JAVA-MAVEN-MOVE-AUTH-012")
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
        assertEquals(20, modules.size, "The root aggregator must declare exactly 20 active modules")
        assertEquals(20, modules.toSet().size, "Every active module path must be unique")
        assertTrue(rootPom.contains("<packaging>pom</packaging>"))

        val pomPaths = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }.toList()
        }
        assertEquals(21, pomPaths.size, "Expected one root POM and 20 child POMs")
        modules.forEach { module ->
            val childPom = workspaceRoot.resolve(module).resolve("pom.xml")
            assertTrue(Files.isRegularFile(childPom), "Missing active child POM: $childPom")
            val child = Files.readString(childPom)
            assertFalse(child.contains("<modules>"), "$module must be a non-aggregator module")
            assertFalse(child.contains("<packaging>pom</packaging>"), "$module must produce a JAR")
        }

        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        assertEquals(20, snapshot.modules.size, "Offline discovery must expose all 20 active modules")
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun itsActiveGraphHasRequiredTopologyAndMaterializedInputs() {
        assertPomDependency("catalog-acceptance", "catalog-storefront", testOnly = true)
        assertPomDependency("catalog-storefront", "catalog-pricing")
        assertPomDependency("catalog-pricing", "catalog-model")

        val externalArtifact = workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH)
        if (offlineArtifactVariant) {
            val permanentArtifact = fixtureTemplate.resolve("fixture-libs/catalog-price-contract-1.0.0.jar")
            assertTrue(Files.isRegularFile(permanentArtifact), "The permanent fixture artifact must be materialized")
            assertEquals(EXTERNAL_ARTIFACT_SHA256, sha256(Files.readAllBytes(permanentArtifact)))
            assertFalse(Files.exists(externalArtifact), "The isolated OFFLINE_MISSING variant must remove the artifact")
        } else {
            assertTrue(Files.isRegularFile(externalArtifact), "The offline external artifact must be materialized")
            assertEquals(EXTERNAL_ARTIFACT_SHA256, sha256(Files.readAllBytes(externalArtifact)))
        }

        val generatedSource = workspaceRoot.resolve(
            "catalog-generated-support/target/generated-sources/catalog-metadata/" +
                "com/acme/catalog/generated/GeneratedCatalogMarker.java",
        )
        assertTrue(Files.isRegularFile(generatedSource), "The generated source must already be materialized")
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryAndAnalysisHaveNoExecutableBuildOrNetworkInputs() {
        val pomContents = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .map(Files::readString)
                .toList()
        }
        val forbiddenPomElements = listOf(
            "<build>",
            "<plugins>",
            "<plugin>",
            "<pluginRepositories>",
            "<repositories>",
            "<annotationProcessorPaths>",
        )
        forbiddenPomElements.forEach { forbidden ->
            assertTrue(pomContents.none { it.contains(forbidden) }, "Fixture POMs must not contain $forbidden")
        }
        assertFalse(Files.exists(workspaceRoot.resolve("mvnw")))
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")))
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun theRequestMovesTheWritableSoleTopLevelClass(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)

        val source = workspaceRoot.resolve(sourcePath)
        val target = workspaceRoot.resolve(targetPath)
        assertTrue(Files.isRegularFile(source) && Files.isWritable(source), "The Product source must be writable")
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS), "The move target must be unused")
        val content = Files.readString(source)
        assertEquals(1, PRODUCT_DECLARATION_PATTERN.findAll(content).count())
    }

    @Given("each preview-time authority defect is evaluated independently for the otherwise supported canonical move request")
    fun eachGuidanceDefectIsEvaluatedIndependently() {
        assertTrue(guidanceCases.isEmpty())
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(PRODUCT_SOURCE_PATH), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        assertDefaultScannerMavenSourceRootSemantics()
    }

    @Given("every listed input and its affected scope remains readable, safely contained, and enumerable enough for complete bounded guidance:")
    fun guidanceInputsRemainReadableAndSafelyEnumerable(table: DataTable) {
        guidanceRows = table.asMaps()
        assertEquals(EXPECTED_GUIDANCE_BLOCKERS, guidanceRows.map { row ->
            GuidanceBlockerRow(
                row.getValue("Maven module"),
                row.getValue("source set"),
                row.getValue("stable blocker code"),
            )
        })
        guidanceCases = guidanceRows.mapIndexed { index, row ->
            val root = temporaryRoot.resolve("guidance-$index")
            copyRecursively(fixtureTemplate, root)
            val blockerCode = row.getValue("stable blocker code")
            when (blockerCode) {
                SOURCE_INVENTORY_BLOCKER -> {
                    val source = root.resolve(PRODUCT_STEPS_PATH)
                    assertTrue(Files.isReadable(source) && Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                    changeCatalogAcceptanceTestSourceRoot(root)
                    assertTrue(Files.isRegularFile(root.resolve(SOURCE_INVENTORY_EVIDENCE_PATH), LinkOption.NOFOLLOW_LINKS))
                }
                CLASSPATH_FINGERPRINT_BLOCKER -> {
                    val artifact = root.resolve(EXTERNAL_ARTIFACT_PATH)
                    val manifest = root.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)
                    assertTrue(PROVIDED_TYPE_PATTERN.matches(SECOND_UNRELATED_PROVIDED_TYPE))
                    artifactEvidenceOutput.appendProvidedType(manifest, SECOND_UNRELATED_PROVIDED_TYPE)
                    addUnrelatedStaleSystemPathEvidence(root)
                    val original = Files.readAllBytes(artifact)
                    Files.write(artifact, original + byteArrayOf(0))
                    assertTrue(JarFile(artifact.toFile(), false).use { it.entries().hasMoreElements() })
                    assertFalse(sha256(Files.readAllBytes(artifact)) == EXTERNAL_ARTIFACT_SHA256)
                    assertEquals(
                        listOf("com.acme.fixture.external.PriceAuthority", SECOND_UNRELATED_PROVIDED_TYPE),
                        Files.readAllLines(manifest).filter { it.startsWith("providedType=") }
                            .map { it.substringAfter('=') },
                    )
                }
                RECOVERED_BINDING_BLOCKER -> {
                    val source = root.resolve(STOREFRONT_SOURCE_PATH)
                    val content = Files.readString(source)
                    assertTrue(content.contains("import $PRODUCT_FQN;"))
                    Files.writeString(
                        source,
                        content.replace("import $PRODUCT_FQN;\n", ""),
                    )
                }
                GENERATED_INVENTORY_BLOCKER -> {
                    val generated = root.resolve(GENERATED_SOURCE_PATH)
                    Files.writeString(generated, Files.readString(generated) + "// isolated inventory drift\n")
                }
                else -> error("Unexpected REQ-003 blocker: $blockerCode")
            }
            val snapshot = JavaProjectScanner().scan(root)
            if (blockerCode == SOURCE_INVENTORY_BLOCKER) {
                assertTrue(Files.isRegularFile(root.resolve(PRODUCT_STEPS_PATH), LinkOption.NOFOLLOW_LINKS))
                val acceptance = snapshot.modules.single { it.name == "catalog-acceptance" }
                assertTrue(Path.of(CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT) in acceptance.testSourceRoots)
                assertTrue(Path.of(DEFAULT_ACCEPTANCE_TEST_SOURCE_ROOT) !in acceptance.testSourceRoots)
                assertTrue(snapshot.files.none { it.path == Path.of(PRODUCT_STEPS_PATH) })
            }
            if (blockerCode == CLASSPATH_FINGERPRINT_BLOCKER) {
                val unrelatedArtifact = root.resolve(UNRELATED_SYSTEM_ARTIFACT_PATH).toAbsolutePath().normalize()
                assertTrue(snapshot.classpathEvidence.any { evidence ->
                    val evidencePath = if (evidence.path.isAbsolute) evidence.path else root.resolve(evidence.path)
                    evidence.kind == ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT &&
                        evidencePath.toAbsolutePath().normalize() == unrelatedArtifact
                }, snapshot.classpathEvidence.toString())
            }
            GuidanceCase(
                row = row,
                root = root,
                snapshot = snapshot,
                recordedState = captureGuidanceWorkspaceState(root),
            )
        }
        assertEquals(4, guidanceCases.size)
        assertTrue(guidanceCases.map(GuidanceCase::root).distinct().size == guidanceCases.size)
        assertUnsupportedMoveRequestsAreRefusedBeforeGuidance()
        assertDuplicateRelevantClasspathSingletonIsRefused()
    }

    @Given("the materialized generated source root is owned by {string}")
    fun materializedGeneratedSourceRootHasExactOwner(owner: String) {
        assertEquals("catalog-generated-support:main", owner)
        guidanceCases.forEach { case ->
            val model = case.snapshot.buildModels.single { it.providerId == MAVEN_BUILD_MODEL_PROVIDER }
            val generatedSupport = model.modules.single { it.id == "catalog-generated-support" }
            val main = generatedSupport.sourceSets.single { it.id == "main" }
            assertTrue(Path.of(GENERATED_ROOT_PATH) in main.generatedSourceRoots)
        }
    }

    @Given("each defect is present in the freshly observed evidence used to construct its preview, and no listed input changes after the request, snapshot, and evidence identities are bound")
    fun eachGuidanceDefectIsFreshAndStable() {
        guidanceCases.forEach { case ->
            assertEquals(case.snapshot, JavaProjectScanner().scan(case.root))
            assertEquals(case.recordedState, captureGuidanceWorkspaceState(case.root))
            listOf(SOURCE_INVENTORY_EVIDENCE_PATH, GENERATED_INVENTORY_EVIDENCE_PATH).forEach { relative ->
                val path = Path.of(relative)
                val auxiliary = case.snapshot.auxiliaryFiles.single { it.path == path }
                assertEquals(Files.readString(case.root.resolve(path)), auxiliary.content)
                val changedEvidence = case.snapshot.copy(auxiliaryFiles = case.snapshot.auxiliaryFiles.map { file ->
                    if (file.path == path) file.copy(content = file.content + "# snapshot identity proof\n") else file
                })
                assertFalse(case.snapshot.hash == changedEvidence.hash)
            }
        }
    }

    @Given("no {string} directory exists and the workspace bytes, paths, inventories, and snapshot hash are recorded before each evaluation")
    fun noManagedMetadataExistsBeforeEachGuidanceEvaluation(directory: String) {
        assertEquals(".refactorkit", directory)
        guidanceCases.forEach { case ->
            assertFalse(Files.exists(case.root.resolve(directory), LinkOption.NOFOLLOW_LINKS))
            assertEquals(case.recordedState, captureGuidanceWorkspaceState(case.root))
        }
    }

    @Given("lost, unreadable, unsafe, or unbounded required structural input and every change after snapshot or evidence binding are refused under {string} as structural loss or post-preview evidence drift, not represented by this guidance")
    fun structuralLossAndPostBindingDriftRemainOutsideThisSlice(requirementId: String) {
        assertEquals("REQ-JAVA-MAVEN-MOVE-AUTH-008", requirementId)
        assertTrue(guidanceCases.all { case ->
            case.root.startsWith(temporaryRoot) && Files.isReadable(case.root)
        })
        assertUnsafeExpectedEvidenceIsNotLoadedAndFailsClosed()
    }

    @When("the unchanged move is previewed twice for each authority defect")
    fun unchangedMoveIsPreviewedTwiceForEachGuidanceDefect() {
        guidanceCases.forEach { case ->
            val dispatcher = JavaMoveClassOperationDispatcher()
            case.guidanceResults = listOf(
                dispatcher.preview(case.snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
                dispatcher.preview(case.snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
            ).map { outcome ->
                assertIs<JavaMoveClassOperationOutcome.Guidance>(outcome).guidance
            }
            case.cliPreview = runCli(moveClassArguments(case.root, apply = false))
        }
    }

    @Then("each pair returns the same immutable {string} result")
    fun eachGuidancePairIsEqualAndImmutable(resultType: String) {
        assertEquals("REVIEW_ONLY_GUIDANCE", resultType)
        guidanceCases.forEach { case ->
            val first = case.guidanceResults[0]
            val second = case.guidanceResults[1]
            assertEquals(first, second)
            assertEquals(resultType, first.resultType.name)
            listOf(
                first.blockers,
                first.candidateGroups.boundTarget,
                first.restorationActions,
                first.vcsChecklist,
            ).forEach { values ->
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (values as MutableList<Any?>).clear()
                }
            }
            if (first.omissions.isNotEmpty()) {
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (first.omissions as MutableList<Any?>).clear()
                }
            }
            val cliPreview = assertNotNull(case.cliPreview)
            assertEquals(0, cliPreview.exitCode, cliPreview.failureMessage("guidance preview"))
            assertTrue(cliPreview.stdout.contains("\"resultType\": \"$resultType\""), cliPreview.stdout)
            assertFalse(cliPreview.stdout.contains("planId", ignoreCase = true), cliPreview.stdout)
        }
    }

    @Then("each result binds the same canonical request identity, exact workspace snapshot SHA-256, and deterministic canonical evidence SHA-256")
    fun eachGuidanceBindsDeterministicRequestSnapshotAndEvidence() {
        guidanceCases.forEach { case ->
            case.guidanceResults.forEach { guidance ->
                assertEquals("moveClass", guidance.request.operation)
                assertEquals(PRODUCT_FQN, guidance.request.symbolFqn)
                assertEquals(PRODUCT_TARGET_PACKAGE, guidance.request.targetPackage)
                assertTrue(SHA256_PATTERN.matches(guidance.requestIdentitySha256))
                assertEquals(case.snapshot.hash, guidance.snapshotSha256)
                assertEquals(1, guidance.schemaVersion)
                assertEquals(JavaMoveClassReviewOnlyGuidance.SCHEMA_VERSION, guidance.schemaVersion)
                assertEquals(1, guidance.checklistVersion)
                assertEquals(JavaMoveClassReviewOnlyGuidance.CHECKLIST_VERSION, guidance.checklistVersion)
                assertTrue(SHA256_PATTERN.matches(guidance.canonicalEvidenceSha256))
            }
            assertEquals(
                case.guidanceResults[0].canonicalEvidenceSha256,
                case.guidanceResults[1].canonicalEvidenceSha256,
            )
        }
    }

    @Then("each result carries the row's stable blocker code and reports the affected Maven module and source set as separate structured fields")
    fun eachGuidanceHasTheExpectedTypedBlocker() {
        guidanceCases.forEach { case ->
            val guidance = case.guidanceResults.singleDistinct()
            val blocker = guidance.blockers.single()
            assertEquals(case.row.getValue("stable blocker code"), blocker.code)
            assertEquals(case.row.getValue("Maven module"), blocker.mavenModule)
            assertEquals(case.row.getValue("source set"), blocker.sourceSet)
            when (blocker.code) {
                SOURCE_INVENTORY_BLOCKER -> {
                    val source = assertIs<JavaMoveClassGuidanceBlocker.MissingReadableSourceInventoryEntry>(blocker)
                    assertEquals(Path.of(SOURCE_INVENTORY_EVIDENCE_PATH), source.manifestPath)
                    assertEquals(
                        sha256(Files.readAllBytes(case.root.resolve(source.manifestPath))),
                        source.manifestContentSha256,
                    )
                    assertEquals(EXPECTED_PRODUCT_STEPS_SHA256, source.expectedContentSha256)
                    assertEquals(EXPECTED_PRODUCT_STEPS_SHA256, source.observedContentSha256)
                    assertEquals("MISSING", source.observedInventoryStatus.name)
                }
                CLASSPATH_FINGERPRINT_BLOCKER -> {
                    val classpath = assertIs<JavaMoveClassGuidanceBlocker.SystemPathArtifactFingerprintMismatch>(blocker)
                    assertEquals(Path.of(EXTERNAL_ARTIFACT_EVIDENCE_PATH), classpath.manifestPath)
                    assertEquals(
                        sha256(Files.readAllBytes(case.root.resolve(classpath.manifestPath))),
                        classpath.manifestContentSha256,
                    )
                }
                RECOVERED_BINDING_BLOCKER -> assertIs<JavaMoveClassGuidanceBlocker.RecoveredTargetUse>(blocker)
                GENERATED_INVENTORY_BLOCKER -> {
                    val generated = assertIs<JavaMoveClassGuidanceBlocker.MaterializedGeneratedRootInventoryFingerprintMismatch>(blocker)
                    assertEquals(Path.of(GENERATED_INVENTORY_EVIDENCE_PATH), generated.manifestPath)
                    assertEquals(
                        sha256(Files.readAllBytes(case.root.resolve(generated.manifestPath))),
                        generated.manifestContentSha256,
                    )
                }
            }
            val cliPreview = assertNotNull(case.cliPreview)
            if (blocker.code != RECOVERED_BINDING_BLOCKER) {
                assertTrue(cliPreview.stdout.contains("\"manifestPath\""), cliPreview.stdout)
                assertTrue(cliPreview.stdout.contains("\"manifestContentSha256\""), cliPreview.stdout)
            }
        }
    }

    @Then("each result keeps exact non-recovered {string} facts, exact non-recovered {string} facts, {string} Java candidates, {string} occurrences, and {string} paths in separate typed groups")
    fun eachGuidanceKeepsTypedCandidateAndResidualGroups(
        boundTarget: String,
        boundOther: String,
        unresolved: String,
        javaResidual: String,
        nonJavaResidual: String,
    ) {
        assertEquals("BOUND_TARGET", boundTarget)
        assertEquals("BOUND_OTHER", boundOther)
        assertEquals("UNRESOLVED", unresolved)
        assertEquals(JavaMoveClassGuidanceResidualKind.JAVA_NON_CODE_RESIDUAL.name, javaResidual)
        assertEquals(JavaMoveClassGuidanceResidualKind.NON_JAVA_RESIDUAL.name, nonJavaResidual)
        guidanceCases.forEach { case ->
            val groups = case.guidanceResults.singleDistinct().candidateGroups
            assertTrue(groups.boundTarget.isNotEmpty())
            assertTrue(groups.boundOther.isNotEmpty())
            assertTrue(groups.boundTarget.all {
                it.classification.name == boundTarget && !it.recovered && it.recoveredRange == null &&
                    !it.bindingKey.isNullOrBlank()
            })
            assertTrue(groups.boundOther.all {
                it.classification.name == boundOther && !it.recovered && it.recoveredRange == null &&
                    !it.bindingKey.isNullOrBlank()
            })
            assertTrue(groups.unresolved.all {
                it.classification.name == unresolved && it.bindingKey == null
            })
            assertTrue(groups.javaNonCodeResiduals.isNotEmpty())
            assertTrue(groups.javaNonCodeResiduals.all { it.kind.name == javaResidual })
            assertTrue(groups.nonJavaResiduals.isNotEmpty())
            assertTrue(groups.nonJavaResiduals.all { it.kind.name == nonJavaResidual })
        }
    }

    @Then("a recovered binding appears only as an {string} Java candidate")
    fun recoveredBindingsAppearOnlyAsUnresolvedCandidates(classification: String) {
        assertEquals("UNRESOLVED", classification)
        guidanceCases.forEach { case ->
            val guidance = case.guidanceResults.singleDistinct()
            val groups = guidance.candidateGroups
            val recovered = groups.unresolved.filter { it.recoveredRange != null }
            assertTrue(groups.boundTarget.none { it.recovered || it.recoveredRange != null })
            assertTrue(groups.boundOther.none { it.recovered || it.recoveredRange != null })
            assertTrue(groups.unresolved.filter { it.recovered }.all { it.recoveredRange != null })
            if (case.row.getValue("stable blocker code") == RECOVERED_BINDING_BLOCKER) {
                val blocker = assertIs<JavaMoveClassGuidanceBlocker.RecoveredTargetUse>(guidance.blockers.single())
                assertEquals(Path.of(STOREFRONT_SOURCE_PATH), blocker.path)
                assertTrue(recovered.isNotEmpty(), groups.unresolved.toString())
                assertTrue(recovered.all { it.path == blocker.path })
                assertTrue(recovered.any { it.recoveredRange == blocker.sourceRange })
                assertTrue(groups.boundTarget.none { target ->
                    recovered.any { it.path == target.path && it.sourceRange == target.sourceRange }
                })
            }
        }
    }

    @Then("every candidate or residual is bound to its snapshot, normalized path, exact range, and content hash without being described as an edit")
    fun everyGuidanceOccurrenceIsHashAndRangeBoundButNotAnEdit() {
        guidanceCases.forEach { case ->
            case.guidanceResults.singleDistinct().candidateGroups.allOccurrences.forEach { occurrence ->
                assertGuidanceOccurrence(case, occurrence)
            }
            assertGuidanceJsonContract(case)
        }
    }

    @Then("candidate-list completeness has an explicit typed status and every known omission is a typed record with bounded identity")
    fun guidanceCompletenessAndOmissionsAreTyped() {
        guidanceCases.forEach { case ->
            val guidance = case.guidanceResults.singleDistinct()
            if (case.row.getValue("stable blocker code") == SOURCE_INVENTORY_BLOCKER) {
                assertEquals(JavaMoveClassGuidanceCandidateCompleteness.COMPLETE_WITH_TYPED_OMISSIONS, guidance.candidateCompleteness)
                val omission = guidance.omissions.single()
                assertEquals(JavaMoveClassGuidanceOmissionKind.SOURCE_INVENTORY_ENTRY, omission.kind)
                assertEquals(Path.of(PRODUCT_STEPS_PATH), omission.path)
                assertEquals("catalog-acceptance", omission.mavenModule)
                assertEquals("test", omission.sourceSet)
                assertTrue(SHA256_PATTERN.matches(omission.contentSha256))
            } else {
                assertEquals(JavaMoveClassGuidanceCandidateCompleteness.COMPLETE, guidance.candidateCompleteness)
                assertTrue(guidance.omissions.isEmpty())
            }
        }
    }

    @Then("its ordered typed restoration actions respectively restore the source inventory, refresh classpath evidence, re-establish exact bindings, or externally restore the generated root, then require a full reactor rescan and a new preview")
    fun restorationActionsAreTypedAndOrdered() {
        val expectedFirst = mapOf(
            SOURCE_INVENTORY_BLOCKER to JavaMoveClassGuidanceRestorationKind.RESTORE_SOURCE_INVENTORY,
            CLASSPATH_FINGERPRINT_BLOCKER to JavaMoveClassGuidanceRestorationKind.REFRESH_CLASSPATH_EVIDENCE,
            RECOVERED_BINDING_BLOCKER to JavaMoveClassGuidanceRestorationKind.REESTABLISH_EXACT_BINDINGS,
            GENERATED_INVENTORY_BLOCKER to JavaMoveClassGuidanceRestorationKind.EXTERNALLY_RESTORE_GENERATED_ROOT,
        )
        guidanceCases.forEach { case ->
            val actions = case.guidanceResults.singleDistinct().restorationActions
            assertEquals(
                listOf(
                    expectedFirst.getValue(case.row.getValue("stable blocker code")),
                    JavaMoveClassGuidanceRestorationKind.FULL_REACTOR_RESCAN,
                    JavaMoveClassGuidanceRestorationKind.NEW_PREVIEW,
                ),
                actions.map { it.kind },
            )
            assertEquals(listOf(1, 2, 3), actions.map { it.order })
        }
    }

    @Then("every result presents this fixed human and VCS-owned checklist in order:")
    fun everyGuidancePresentsTheFixedVcsChecklist(table: DataTable) {
        val expected = table.asMaps().map { row ->
            row.getValue("order").toInt() to row.getValue("verification")
        }
        assertEquals(EXPECTED_VCS_CHECKLIST, expected)
        guidanceCases.forEach { case ->
            assertEquals(expected, case.guidanceResults.singleDistinct().vcsChecklist.map { it.order to it.verification })
        }
    }

    @Then("no result contains a {string}, {string}, managed edit or replacement text, applyable plan ID, pending-plan entry, managed transaction or transaction identity, or RefactorKit rollback capability")
    fun guidanceIsStructurallyNonApplyable(firstForbiddenType: String, secondForbiddenType: String) {
        assertEquals("PatchPlan", firstForbiddenType)
        assertEquals("WorkspaceEdit", secondForbiddenType)
        val forbidden = listOf(
            firstForbiddenType,
            secondForbiddenType,
            "PlanId",
            "TextEdit",
            "replacement",
            "pendingPlan",
            "Transaction",
            "Rollback",
        )
        guidanceCases.forEach { case ->
            val guidance = case.guidanceResults.singleDistinct()
            val structuralSurface = guidance.javaClass.declaredFields.map { it.genericType.typeName } +
                guidance.javaClass.methods.map { "${it.name}:${it.genericReturnType.typeName}" }
            forbidden.forEach { token ->
                assertTrue(structuralSurface.none { it.contains(token, ignoreCase = true) }, structuralSurface.toString())
            }
            assertFalse(PatchPlan::class.java.isInstance(guidance))
            assertTrue(guidance.candidateGroups.allOccurrences.all { !it.managedEdit })
        }
    }

    @Then("no result can be converted into or promoted to a semantic plan")
    fun guidanceHasNoPlanConversionSurface() {
        guidanceCases.forEach { case ->
            val methodNames = case.guidanceResults.singleDistinct().javaClass.methods.map { it.name.lowercase() }
            assertTrue(methodNames.none { it.contains("plan") || it.contains("promote") || it.contains("apply") })
        }
    }

    @When("the same `refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --apply` command is invoked for each unchanged guidance condition")
    fun sameMoveClassApplyCommandIsInvokedForEachGuidanceCondition() {
        guidanceCases.forEach { case ->
            case.cliApply = runCli(moveClassArguments(case.root, apply = true))
        }
    }

    @Then("the CLI refuses with {string} before workspace-lock acquisition and before write-ahead-log creation")
    fun cliRefusesGuidanceBeforeLockAndWal(code: String) {
        assertEquals("guidance.nonManaged", code)
        guidanceCases.forEach { case ->
            val result = assertNotNull(case.cliApply)
            assertEquals(1, result.exitCode, result.failureMessage("guidance apply refusal"))
            assertTrue(result.stderr.contains(code), result.failureMessage("typed guidance refusal"))
            assertFalse(Files.exists(case.root.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(case.root.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Then("no {string} directory, lock file, pending-plan record, write-ahead log, or managed transaction is created")
    fun guidanceApplyCreatesNoManagedResidue(directory: String) {
        assertEquals(".refactorkit", directory)
        guidanceCases.forEach { case ->
            assertFalse(Files.exists(case.root.resolve(directory), LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Then("every workspace byte, path, inventory entry, and snapshot hash equals the state recorded before its evaluation")
    fun guidanceEvaluationPreservesEveryWorkspaceIdentityDimension() {
        guidanceCases.forEach { case ->
            assertEquals(case.recordedState, captureGuidanceWorkspaceState(case.root))
        }
    }

    @Given("a fresh isolated fixture starts from every eligible precondition of {string} for the fixed selected ordinary JAR leaf {string}")
    fun selectedDescriptorFixtureStartsFromEligibleOrdinaryMissingLeaf(
        requirementId: String,
        selectedCoordinate: String,
    ) {
        assertTrue(selectedDescriptorVariant)
        assertEquals("REQ-JAVA-MAVEN-MOVE-AUTH-011", requirementId)
        assertEquals(ORDINARY_MISSING_COORDINATE, selectedCoordinate)
        selectedDescriptorProfile = AUTH_011_PROFILE
        selectedDescriptorAuthorityContext = null
        val snapshot = scanWorkspace()
        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertEquals(PatchStatus.PREVIEW, preview.plan.status, preview.plan.summary)
        assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence, preview.plan.warnings.toString())
        val record = assertNotNull(preview.targetAuthorityLease).selectedMissingBinaryRecords.single()
        assertEquals(ORDINARY_MISSING_COORDINATE, "${record.selectedCoordinate}:${record.selectedVersion}")
        assertTrue(SHA256_PATTERN.matches(record.parsedDescriptorIdentityHash))
        assertEquals(setOf("COMPILE", "RUNTIME", "TEST"), record.selectingPaths.mapTo(sortedSetOf()) { it.projection })
        offlineAuthoritySnapshot = snapshot
        offlineAuthoritySetupPreview = preview
        approvedOfflinePreview = preview
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the absent JAR remains a binary-availability fact that can be considered only after complete selected descriptor closure")
    fun selectedDescriptorClosurePrecedesBinaryAvailability() {
        val record = ordinaryMissingRecord()
        assertFalse(Files.exists(workspaceRoot.resolve(record.deterministicJarPath), LinkOption.NOFOLLOW_LINKS))
        assertTrue(record.effectiveInputContentHashes.isNotEmpty())
        assertTrue(record.effectiveInputContentHashes.all { (path, hash) ->
            Files.isRegularFile(workspaceRoot.resolve(path), LinkOption.NOFOLLOW_LINKS) &&
                sha256(Files.readAllBytes(workspaceRoot.resolve(path))) == hash
        })
        assertEquals("SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0", record.graphLeafProof)
        assertTrue(assertNotNull(offlineAuthoritySnapshot).modules.all {
            it.languageSettings["java.dependencyGraph.status"] == "complete"
        })
    }

    @Given("where an example requires a parent, BOM, management, mediation, or relocation input, a bounded fixture variant adds exactly one fixture-owned required input while preserving the selected coordinate, ordinary {string} variant, direct dependency path, and {string}, {string}, and {string} projections")
    fun selectedDescriptorVariantsAreBoundedAndFixtureOwned(
        variant: String,
        compileProjection: String,
        runtimeProjection: String,
        testProjection: String,
    ) {
        assertEquals("jar", variant)
        assertEquals(listOf("COMPILE", "RUNTIME", "TEST"), listOf(compileProjection, runtimeProjection, testProjection))
        AUTH_012_STATIC_PATHS.forEach { relative ->
            assertTrue(Files.isRegularFile(fixtureTemplate.resolve(relative), LinkOption.NOFOLLOW_LINKS), relative)
            assertTrue(Files.isRegularFile(workspaceRoot.resolve(relative), LinkOption.NOFOLLOW_LINKS), relative)
        }
        val managementProfile = profileBlock(
            Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml")),
            AUTH_012_MANAGEMENT_PROFILE,
        )
        assertTrue(managementProfile.contains("<artifactId>ordinary-missing-management-bom</artifactId>"))
        assertTrue(managementProfile.contains("<artifactId>ordinary-missing-leaf</artifactId>"))
        assertFalse(managementProfile.substringAfter("<artifactId>ordinary-missing-leaf</artifactId>")
            .substringBefore("</dependency>").contains("<version>"))
    }

    @Given("every selected descriptor input other than the one named by the example is present, well-formed, current, and hash-bound")
    fun everyOtherSelectedDescriptorInputStartsComplete() {
        val record = ordinaryMissingRecord()
        assertTrue(record.effectiveInputContentHashes.all { (path, expectedHash) ->
            val absolute = workspaceRoot.resolve(path)
            Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS) &&
                sha256(Files.readAllBytes(absolute)) == expectedHash
        })
        assertTrue(selectedDescriptorPermanentHashes.all { (path, expectedHash) ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && sha256(Files.readAllBytes(path)) == expectedHash
        })
    }

    @When("{string} is introduced independently in the {string} before authority evaluation")
    fun selectedDescriptorMutationIsIntroduced(descriptorMutation: String, descriptorLayer: String) {
        val expectedCase = AUTH_012_CASES.single { it.mutation == descriptorMutation }
        assertEquals(expectedCase.layer, descriptorLayer)
        selectedDescriptorMutation = descriptorMutation
        selectedDescriptorLayer = descriptorLayer
        selectedDescriptorCondition = expectedCase.condition
        selectedDescriptorAuthorityContext = null
        selectedDescriptorProfile = if (
            descriptorLayer == MavenSelectedDescriptorLayer.DEPENDENCY_MANAGEMENT_MEDIATION_DECLARATION.displayName
        ) AUTH_012_MANAGEMENT_PROFILE else AUTH_011_PROFILE

        when (descriptorLayer) {
            MavenSelectedDescriptorLayer.REQUIRED_PARENT_POM.displayName -> copyFixtureVariant(
                AUTH_012_PARENT_BACKED_TEMPLATE,
                ORDINARY_MISSING_POM,
            )
            MavenSelectedDescriptorLayer.REQUIRED_IMPORTED_BOM.displayName -> copyFixtureVariant(
                AUTH_012_IMPORTED_BOM_TEMPLATE,
                ORDINARY_MISSING_POM,
            )
        }

        val baselineSnapshot = scanWorkspace()
        val baselinePreview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            baselineSnapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertEquals(PatchStatus.PREVIEW, baselinePreview.plan.status, baselinePreview.plan.summary)
        assertEquals(RefactoringEvidence.JDT_BINDING, baselinePreview.plan.evidence, baselinePreview.plan.warnings.toString())
        val baselineRecord = assertNotNull(baselinePreview.targetAuthorityLease)
            .selectedMissingBinaryRecords.single()
        assertEquals(ORDINARY_MISSING_COORDINATE, "${baselineRecord.selectedCoordinate}:${baselineRecord.selectedVersion}")
        assertEquals(setOf("COMPILE", "RUNTIME", "TEST"), baselineRecord.selectingPaths.mapTo(sortedSetOf()) { it.projection })

        var authorityContext = baselineSnapshot.captureMavenSelectedDescriptorAuthorityContext(
            ORDINARY_MISSING_COORDINATE,
        )
        expectedCase.expectedFactLayer?.let { expectedLayer ->
            assertTrue(authorityContext.descriptorExpectations.any { it.layer == expectedLayer }, authorityContext.toString())
        }
        assertTrue(SHA256_PATTERN.matches(assertNotNull(authorityContext.parsedDescriptorIdentityHash)))

        when (descriptorMutation) {
            "remove the deterministic selected leaf POM regular file" ->
                Files.delete(workspaceRoot.resolve(ORDINARY_MISSING_POM))
            "replace the selected leaf POM with malformed XML that cannot produce a complete raw or effective model" ->
                copyFixtureVariant(AUTH_012_MALFORMED_TEMPLATE, ORDINARY_MISSING_POM)
            "remove the one fixture-owned parent POM referenced by the selected leaf" ->
                Files.delete(workspaceRoot.resolve(AUTH_012_PARENT_POM))
            "change the one fixture-owned imported BOM after its content hash is bound" ->
                copyFixtureVariant(AUTH_012_DRIFTED_BOM_TEMPLATE, AUTH_012_IMPORTED_BOM_POM)
            "change the required declaration that supplies the mediated selected version after hash binding" ->
                copyFixtureVariant(AUTH_012_DRIFTED_MANAGEMENT_BOM_TEMPLATE, AUTH_012_MANAGEMENT_BOM_POM)
            "remove parsed-model proof that the selected leaf has no relocation while descriptor bytes remain present" ->
                authorityContext = authorityContext.withoutParsedDescriptorIdentity()
            else -> error("Unexpected selected-descriptor mutation: $descriptorMutation")
        }
        selectedDescriptorAuthorityContext = authorityContext
        assertFalse(Files.exists(workspaceRoot.resolve(ORDINARY_MISSING_JAR), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(selectedDescriptorPermanentHashes.all { (path, expectedHash) ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && sha256(Files.readAllBytes(path)) == expectedHash
        })
    }

    @When("the resulting workspace bytes, paths, inventories, snapshot hash, descriptor facts, evidence records, and evidence hashes are recorded")
    fun selectedDescriptorPostMutationStateIsRecorded() {
        val snapshot = scanWorkspace()
        selectedDescriptorSnapshot = snapshot
        offlineAuthoritySnapshot = snapshot
        selectedDescriptorRecordedState = captureDescriptorAuthorityState(snapshot)
    }

    @Then("descriptor-closed Maven traversal returns typed structural {string} guidance with managed-write eligibility {string}")
    fun selectedDescriptorTraversalReturnsStructuralRefusal(resultType: String, eligibility: String) {
        assertEquals("REFUSED", resultType)
        assertEquals("INELIGIBLE", eligibility)
        val snapshot = assertNotNull(selectedDescriptorSnapshot)
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        ).plan
        selectedDescriptorPlan = plan
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
        val expectedCode = when (assertNotNull(selectedDescriptorCondition)) {
            "MISSING" -> "java.maven.selectedDescriptor.missing"
            "MALFORMED" -> "java.maven.selectedDescriptor.malformed"
            "DRIFTED" -> "java.maven.selectedDescriptor.drifted"
            else -> error("Unexpected descriptor condition")
        }
        assertEquals(expectedCode, plan.refusalCode, plan.summary)
        assertEquals(null, plan.authorityLease)
    }

    @Then("the stable blocker names the selected coordinate, its direct dependency path from {string}, every affected source-set projection, and the {string} as {string}")
    fun selectedDescriptorBlockerIsStableAndComplete(
        sourceSet: String,
        descriptorLayer: String,
        descriptorCondition: String,
    ) {
        assertEquals(PRICING_SOURCE_SET, sourceSet)
        assertEquals(selectedDescriptorLayer, descriptorLayer)
        assertEquals(selectedDescriptorCondition, descriptorCondition)
        val plan = assertNotNull(selectedDescriptorPlan)
        val summary = plan.summary
        assertTrue(summary.contains("coordinate=$ORDINARY_MISSING_COORDINATE"), summary)
        assertTrue(summary.contains("origin=$sourceSet"), summary)
        assertTrue(summary.contains("path="), summary)
        assertTrue(summary.contains("ordinary-missing-leaf"), summary)
        assertTrue(summary.contains("descriptorLayer=$descriptorLayer"), summary)
        assertTrue(summary.contains("descriptorCondition=$descriptorCondition"), summary)
        listOf("COMPILE", "RUNTIME", "TEST").forEach { projection ->
            assertTrue(summary.contains("projection=$projection"), summary)
        }
        val repeatSnapshot = scanWorkspace()
        val repeat = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            repeatSnapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        assertEquals(plan.status, repeat.status)
        assertEquals(plan.evidence, repeat.evidence)
        assertEquals(plan.refusalCode, repeat.refusalCode)
        assertEquals(plan.summary, repeat.summary)
        assertEquals(plan.workspaceEdit, repeat.workspaceEdit)
    }

    @Then("no absent, malformed, incomplete, or drifted selected POM or effective-model input is reclassified as an admissible missing binary or a proven graph leaf")
    fun selectedDescriptorFailureIsNeverMissingBinaryEvidence() {
        val snapshot = assertNotNull(selectedDescriptorSnapshot)
        val pricingSettings = snapshot.modules.single { it.name == "catalog-pricing" }.languageSettings
        assertEquals("incomplete", pricingSettings["java.dependencyGraph.status"])
        assertTrue(pricingSettings.filterKeys { it.contains("missing.evidence") }.values.none {
            it.contains("ordinary-missing-leaf") || it == "LOCAL_REPOSITORY_SELECTED_LEAF"
        }, pricingSettings.toString())
        assertTrue(pricingSettings.filterKeys { it.endsWith("Classpath.message") }.values.none {
            it.contains("ordinary-missing-leaf")
        }, pricingSettings.toString())
        val plan = assertNotNull(selectedDescriptorPlan)
        assertFalse(plan.summary.contains("SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0"), plan.summary)
        assertEquals(null, plan.authorityLease)
    }

    @Then("no clean target lookup, likely leaf shape, approval, caller-supplied classpath, or caller attestation compensates for the descriptor failure")
    fun selectedDescriptorFailureCannotBeCompensated() {
        val plan = assertNotNull(selectedDescriptorPlan)
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertFalse(plan.requiresUserApproval)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(plan.affectedFiles.isEmpty())
        assertEquals(null, plan.authorityLease)
        assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
    }

    @Then("refusal occurs before semantic edit selection, applyable plan ID issuance, workspace-lock acquisition, and write-ahead-log creation")
    fun selectedDescriptorRefusalPrecedesSemanticPlanLockAndWal() {
        val plan = assertNotNull(selectedDescriptorPlan)
        assertEquals(PatchStatus.REFUSED, plan.status)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertFalse(plan.requiresUserApproval)
        assertEquals(null, plan.authorityLease)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("no caller classpath contributes authority, and {string} remains review-only and managed-write ineligible")
    fun selectedDescriptorRefusalCannotBePromotedByCallerEvidence(evidence: String) {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK.name, evidence)
        val plan = assertNotNull(selectedDescriptorPlan)
        val lexicalAttempt = plan.copy(status = PatchStatus.PREVIEW, evidence = RefactoringEvidence.LEXICAL_FALLBACK)
        assertTrue(PatchEngine(workspaceRoot).validate(lexicalAttempt, lexicalAttempt.snapshotHash).any {
            it.code == "evidence.insufficient"
        })
        assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
    }

    @Then("no {string} directory, lock file, write-ahead log, managed transaction, or RefactorKit workspace mutation is created")
    fun selectedDescriptorRefusalCreatesNoManagedResidue(directory: String) {
        assertEquals(".refactorkit", directory)
        assertFalse(Files.exists(workspaceRoot.resolve(directory), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
        assertNotNull(selectedDescriptorRecordedState)
    }

    @Then("every workspace byte, path, inventory entry, snapshot hash, descriptor fact, evidence record, and evidence hash equals the state recorded before authority evaluation")
    fun selectedDescriptorRefusalPreservesEveryRecordedStateDimension() {
        val expected = assertNotNull(selectedDescriptorRecordedState)
        val actualSnapshot = scanWorkspace()
        val actual = captureDescriptorAuthorityState(actualSnapshot)
        assertEquals(expected, actual)
        assertTrue(selectedDescriptorPermanentHashes.all { (path, expectedHash) ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && sha256(Files.readAllBytes(path)) == expectedHash
        })
        selectedDescriptorRefusalPrecedesSemanticPlanLockAndWal()
    }

    @Given("the fixture's inactive profile {string} is enabled in an isolated copy without changing its root aggregator or 20 active non-aggregator modules")
    fun descriptorPruningProfileIsEnabled(profile: String) {
        val expectedArtifact = when (profile) {
            AUTH_010_PROFILE -> {
                assertTrue(descriptorPruningVariant)
                assertFalse(alternateDescriptorPathVariant)
                "descriptor-pruning-parent"
            }
            AUTH_011_PROFILE -> {
                assertTrue(ordinaryMissingVariant)
                "ordinary-missing-leaf"
            }
            else -> error("Unexpected authority profile: $profile")
        }
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        assertEquals(20, MODULE_PATTERN.findAll(rootPom).count())
        val pricingPom = Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml"))
        assertTrue(profileBlock(pricingPom, profile).contains("<artifactId>$expectedArtifact</artifactId>"))
    }

    @Given("{string}, which belongs to the before-and-staged operation authority closure, selects exactly one fixture-owned non-reactor dependency from the explicitly supplied local-only repository {string}:")
    fun ordinaryMissingLeafIsSelectedFromLocalRepository(
        affectedSourceSet: String,
        repositoryName: String,
        table: DataTable,
    ) {
        assertEquals(PRICING_SOURCE_SET, affectedSourceSet)
        assertEquals(FIXTURE_REPOSITORY_PATH, repositoryName)
        val row = table.asMaps().single()
        assertEquals("com.acme.fixture.missing", row.getValue("groupId"))
        assertEquals("ordinary-missing-leaf", row.getValue("artifactId"))
        assertEquals("1.0.0", row.getValue("version"))
        assertEquals("jar", row.getValue("type"))
        assertEquals("empty", row.getValue("classifier"))
        assertEquals("jar", row.getValue("extension"))
        assertEquals("compile", row.getValue("effective scope"))
        val snapshot = scanWorkspace()
        offlineAuthoritySnapshot = snapshot
        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        offlineAuthoritySetupPreview = preview
        approvedOfflinePreview = preview
        assertEquals(1, ordinaryMissingRecord().let(::listOf).size)
    }

    @Given("the selected version is fixed and non-snapshot, the dependency has no relocation, and this bounded row admits no classified or {string} variant")
    fun ordinaryMissingLeafVariantIsBounded(testJarType: String) {
        assertEquals("test-jar", testJarType)
        val record = ordinaryMissingRecord()
        assertEquals("1.0.0", record.selectedVersion)
        assertFalse(record.selectedVersion.endsWith("-SNAPSHOT"))
        assertFalse(record.selectedVersion.contains('[') || record.selectedVersion.contains('('))
        assertEquals("jar", record.type)
        assertEquals("", record.classifier)
        assertEquals("jar", record.extension)
    }

    @Given("the repository's provider, Maven-2 layout, normalized canonical root, and no-settings local-only policy form one hash-bound repository identity")
    fun ordinaryMissingRepositoryIdentityIsHashBound() {
        val record = ordinaryMissingRecord()
        assertEquals(MAVEN_BUILD_MODEL_PROVIDER, record.repositoryProvider)
        assertEquals("MAVEN_2", record.repositoryLayout)
        assertEquals(Path.of(FIXTURE_REPOSITORY_PATH), record.repositoryRoot)
        assertEquals("LOCAL_ONLY_NO_SETTINGS", record.repositoryPolicy)
        assertTrue(SHA256_PATTERN.matches(record.repositoryIdentityHash))
        assertEquals("denied", assertNotNull(offlineAuthoritySnapshot).buildModels.single().attributes["networkAccess"])
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn"), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the deterministic repository POM path {string} is a present regular file")
    fun ordinaryMissingPomPathIsPresent(relativePath: String) {
        assertEquals(ORDINARY_MISSING_POM.removePrefix("$FIXTURE_REPOSITORY_PATH/"), relativePath)
        val record = ordinaryMissingRecord()
        assertEquals(Path.of(ORDINARY_MISSING_POM), record.deterministicPomPath)
        assertTrue(
            Files.isRegularFile(workspaceRoot.resolve(record.deterministicPomPath), LinkOption.NOFOLLOW_LINKS),
            "The selected leaf POM must be a no-follow regular file",
        )
    }

    @Given("the selected effective POM and every required parent, imported BOM, dependency-management, and mediation input are present, complete, current, and hash-bound")
    fun ordinaryMissingEffectiveInputsAreHashBound() {
        val record = ordinaryMissingRecord()
        assertTrue(SHA256_PATTERN.matches(record.selectedPomContentHash))
        assertEquals(record.selectedPomContentHash, record.effectiveInputContentHashes[record.deterministicPomPath])
        assertTrue(record.effectiveInputContentHashes.isNotEmpty())
        assertTrue(record.effectiveInputContentHashes.all { (path, hash) ->
            Files.isRegularFile(workspaceRoot.resolve(path), LinkOption.NOFOLLOW_LINKS) &&
                SHA256_PATTERN.matches(hash) && sha256(Files.readAllBytes(workspaceRoot.resolve(path))) == hash
        })
        assertTrue(assertNotNull(offlineAuthoritySnapshot).classpathEvidence.any { evidence ->
            evidence.path == record.deterministicPomPath &&
                evidence.kind == org.refactorkit.core.ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT &&
                evidence.fingerprint != "missing"
        })
    }

    @Given("descriptor-closed traversal classifies every outgoing declaration and proves that the selected graph subtree has no selected child and consists only of this leaf in every affected projection")
    fun ordinaryMissingGraphIsASelectedLeaf() {
        val record = ordinaryMissingRecord()
        assertEquals("SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0", record.graphLeafProof)
        assertTrue(SHA256_PATTERN.matches(record.graphLeafProofHash))
        assertEquals(setOf("COMPILE", "RUNTIME", "TEST"), record.selectingPaths.mapTo(sortedSetOf()) { it.projection })
        assertTrue(record.selectingPaths.all { it.dependencyPath.contains("ordinary-missing-leaf") })
    }

    @Given("the normalized repository JAR path {string} is absent in the before and staged images under no-follow filesystem observations")
    fun ordinaryMissingJarIsAbsentBeforeAndStaged(relativePath: String) {
        assertEquals(ORDINARY_MISSING_JAR.removePrefix("$FIXTURE_REPOSITORY_PATH/"), relativePath)
        val record = ordinaryMissingRecord()
        assertEquals(Path.of(ORDINARY_MISSING_JAR), record.deterministicJarPath)
        assertFalse(Files.exists(workspaceRoot.resolve(record.deterministicJarPath), LinkOption.NOFOLLOW_LINKS))
        listOf(record.beforeJarAbsence, record.stagedJarAbsence).forEach { observation ->
            assertEquals("ABSENT", observation.status)
            assertEquals(record.deterministicJarPath, observation.path)
            assertTrue(observation.fingerprint.startsWith("absent-nofollow:"))
            assertTrue(SHA256_PATTERN.matches(observation.factHash))
        }
    }

    @Given("no JAR-adjacent {string} sidecar, expected JAR content hash, or provided-type inventory exists or is supplied")
    fun ordinaryMissingLeafHasNoCallerSidecar(sidecarSuffix: String) {
        assertEquals(".refactorkit-evidence", sidecarSuffix)
        val record = ordinaryMissingRecord()
        val sidecar = workspaceRoot.resolve(record.deterministicJarPath)
            .resolveSibling("${record.deterministicJarPath.fileName}$sidecarSuffix")
        assertFalse(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS))
        assertEquals(null, record.expectedIdentityManifest)
        assertEquals(null, record.expectedJarContentHash)
        assertTrue(record.providedTypes.isEmpty())
    }

    @Given("all other full-reactor structure, target closure, source inventory, Java platform, provider, diagnostic, and freshness evidence required by the move is complete and hash-bound")
    fun allOtherOrdinaryMissingAuthorityEvidenceIsComplete() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        assertEquals(20, snapshot.modules.size)
        assertEquals(BuildModelStatus.OFFLINE_MISSING, snapshot.buildModels.single().status)
        assertTrue(snapshot.modules.all { it.languageSettings["java.dependencyGraph.status"] == "complete" })
        assertEquals(
            setOf(TARGET_OWNER_SOURCE_SET) + EXPECTED_OBSERVER_SOURCE_SETS,
            ordinaryMissingRecord().affectedAuthoritySourceSets,
        )
        assertTrue(snapshot.files.filter { it.languageId == "java" }.all {
            snapshot.owningBuildSourceRoots(it.path).size == 1
        })
        assertEquals(snapshot.hash, ordinaryLease().beforeSnapshotHash)
    }

    @Given("one exact non-recovered target declaration and the full-reactor Java candidate inventory classify every before-and-staged target-capable range exactly once as {string} or {string}, with zero {string} candidates")
    fun ordinaryMissingCandidatesAreTotal(boundTarget: String, boundOther: String, unresolved: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_TARGET.name, boundTarget)
        assertEquals(JavaMoveClassCandidateClassification.BOUND_OTHER.name, boundOther)
        assertEquals(JavaMoveClassCandidateClassification.UNRESOLVED.name, unresolved)
        val lease = ordinaryLease()
        listOf(lease.candidatesBefore, lease.candidatesStaged).forEach { candidates ->
            assertTrue(candidates.isNotEmpty())
            assertEquals(candidates.size, candidates.map { it.path to it.sourceRange }.distinct().size)
            assertTrue(candidates.none { it.classification == JavaMoveClassCandidateClassification.UNRESOLVED })
        }
        assertNotNull(lease.targetBindingKey)
    }

    @Given("apart from the declaration, every simple-name {string} use has exactly one resolved non-static single-type import of the applicable target FQN and every other target use is the exact applicable target FQN")
    fun ordinaryMissingTargetLookupUsesAreProtected(classification: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_TARGET.name, classification)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        assertLookupProtected(snapshot, ordinaryLease().candidatesBefore, PRODUCT_FQN, Path.of(PRODUCT_SOURCE_PATH))
        val staged = WorkspaceEditSimulator.apply(snapshot, assertNotNull(offlineAuthoritySetupPreview).plan.workspaceEdit)
        val lease = ordinaryLease()
        assertLookupProtected(staged, lease.candidatesStaged, lease.newFqn, lease.newDeclarationPath)
    }

    @Given("no relevant type or static import-on-demand, target-named or unresolved static import, same-package lookup, inherited lookup, or enclosing-type lookup can change the selected target name")
    fun ordinaryMissingTargetNameHasNoAmbiguity() {
        fixtureHasNoTargetNameLookupAmbiguity()
        val lease = ordinaryLease()
        assertTrue((lease.candidatesBefore + lease.candidatesStaged).none {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
    }

    @Given("no recovered, problem, or ambiguous binding and no candidate, diagnostic, or edit overlap is present")
    fun ordinaryMissingAuthorityHasNoBindingOrRangeOverlap() {
        val lease = ordinaryLease()
        assertTrue(lease.allDiagnosticsBefore.isEmpty())
        assertTrue(lease.allDiagnosticsStaged.isEmpty())
        assertTrue((lease.candidatesBefore + lease.candidatesStaged).all { it.bindingKey != null })
        val edits = assertNotNull(offlineAuthoritySetupPreview).plan.workspaceEdit.edits
            .filterIsInstance<FileEdit.Modify>()
        assertTrue(edits.all { edit -> edit.textEdits.zipWithNext().none { (left, right) ->
            left.range.overlaps(right.range)
        } })
    }

    @When("the move is previewed twice from identical snapshot, repository, descriptor, candidate, and negative-presence evidence hashes")
    fun ordinaryMissingMoveIsPreviewedTwice() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val planner = JavaMoveClassPlanner(JavaLanguageAdapter())
        offlineAuthorityPreviews = listOf(
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
        )
        approvedOfflinePreview = offlineAuthorityPreviews.last()
    }

    @Then("each preview contains exactly one RefactorKit-enumerated selected-missing-binary record with this ordinary identity and path evidence:")
    fun ordinaryMissingPreviewContainsOneEngineRecord(table: DataTable) {
        val record = ordinaryMissingRecordFromApprovedPreview()
        val expected = table.asMaps().associate { it.getValue("evidence field") to it.getValue("expected evidence") }
        assertEquals("com.acme.fixture.missing:ordinary-missing-leaf", record.selectedCoordinate)
        assertEquals(expected.getValue("selected coordinate"), record.selectedCoordinate)
        assertEquals(expected.getValue("selected version"), record.selectedVersion)
        assertEquals("type jar, empty classifier, extension jar", expected.getValue("selected variant"))
        assertEquals("jar", record.type)
        assertEquals("", record.classifier)
        assertEquals("jar", record.extension)
        assertEquals(
            "provider, Maven-2 layout, normalized canonical fixture-repository root, and local-only policy",
            expected.getValue("repository identity"),
        )
        assertEquals(expected.getValue("deterministic POM path"), record.deterministicPomPath
            .invariantSeparatorsPathString.removePrefix("$FIXTURE_REPOSITORY_PATH/"))
        assertEquals(expected.getValue("deterministic JAR path"), record.deterministicJarPath
            .invariantSeparatorsPathString.removePrefix("$FIXTURE_REPOSITORY_PATH/"))
        assertTrue(offlineAuthorityPreviews.all {
            assertNotNull(it.targetAuthorityLease).selectedMissingBinaryRecords.single() == record
        })
    }

    @Then("that record enumerates every selecting dependency path and projection and the exact affected operation-authority-closure source sets")
    fun ordinaryMissingRecordEnumeratesSelectionsAndClosure() {
        val record = ordinaryMissingRecordFromApprovedPreview()
        assertEquals(setOf("COMPILE", "RUNTIME", "TEST"), record.selectingPaths.mapTo(sortedSetOf()) { it.projection })
        assertEquals(3, record.selectingPaths.size)
        assertTrue(record.selectingPaths.all { it.dependencyPath.contains("ordinary-missing-leaf") })
        assertEquals(setOf(TARGET_OWNER_SOURCE_SET) + EXPECTED_OBSERVER_SOURCE_SETS, record.affectedAuthoritySourceSets)
    }

    @Then("that record contains and hash-binds the selected-POM content hash, every effective-input content hash, the graph-leaf proof, and the before-and-staged no-follow {string} observations with their evidence-fact hashes")
    fun ordinaryMissingRecordHashBindsEveryFact(status: String) {
        assertEquals("ABSENT", status)
        val record = ordinaryMissingRecordFromApprovedPreview()
        assertTrue(SHA256_PATTERN.matches(record.selectedPomContentHash))
        assertTrue(record.effectiveInputContentHashes.values.all(SHA256_PATTERN::matches))
        assertTrue(SHA256_PATTERN.matches(record.graphLeafProofHash))
        assertTrue(SHA256_PATTERN.matches(record.beforeJarAbsence.factHash))
        assertTrue(SHA256_PATTERN.matches(record.stagedJarAbsence.factHash))
        assertEquals(status, record.beforeJarAbsence.status)
        assertEquals(status, record.stagedJarAbsence.status)
    }

    @Then("that record has no expected JAR content hash and no provided-type inventory instead of inventing either")
    fun ordinaryMissingRecordInventsNoBinaryOrTypeEvidence() {
        val record = ordinaryMissingRecordFromApprovedPreview()
        assertEquals(null, record.expectedJarContentHash)
        assertEquals(null, record.expectedIdentityManifest)
        assertTrue(record.providedTypes.isEmpty())
    }

    @Then("candidate-total exact JDT bindings and target-name lookup protection positively prove that the absent unrelated binary cannot conceal the selected target or any target edit")
    fun ordinaryMissingCandidateEvidenceProvesNonConcealment() {
        val lease = ordinaryLeaseFromApprovedPreview()
        assertTrue((lease.candidatesBefore + lease.candidatesStaged).none {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
        assertTrue(assertNotNull(approvedOfflinePreview).plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().all {
            it.path == Path.of(PRODUCT_SOURCE_PATH) || it.path in EXPECTED_OBSERVER_PATHS
        })
    }

    @Then("before-and-staged diagnostics contain zero unresolved-type diagnostics attributable to unknown provided types from the absent JAR")
    fun ordinaryMissingDiagnosticsHaveZeroUnknownProvidedTypes() {
        val lease = ordinaryLeaseFromApprovedPreview()
        assertTrue(lease.retainedDiagnosticsBefore.isEmpty())
        assertTrue(lease.retainedDiagnosticsStaged.isEmpty())
        assertTrue(lease.allDiagnosticsBefore.isEmpty())
        assertTrue(lease.allDiagnosticsStaged.isEmpty())
    }

    @Then("the selected-missing record, target closure, candidate records, normalized edits, and diagnostics are identical between previews")
    fun ordinaryMissingPreviewsAreDeterministic() {
        val first = offlineAuthorityPreviews[0]
        val second = offlineAuthorityPreviews[1]
        assertEquals(first.targetAuthorityLease, second.targetAuthorityLease)
        assertEquals(first.plan.workspaceEdit, second.plan.workspaceEdit)
        assertEquals(first.plan.diagnosticsBefore, second.plan.diagnosticsBefore)
        assertEquals(first.plan.diagnosticsAfterPreview, second.plan.diagnosticsAfterPreview)
    }

    @Then("no Maven lifecycle, plugin, settings file, mirror, proxy, server, credential, credential helper, or network request is executed or consulted")
    fun ordinaryMissingUsesOnlyLocalPluginFreeDiscovery() {
        discoveryAndAnalysisHaveNoExecutableBuildOrNetworkInputs()
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("denied", assertNotNull(offlineAuthoritySnapshot).buildModels.single().attributes["networkAccess"])
        assertTrue(Files.walk(workspaceRoot).use { paths -> paths.noneMatch {
            it.fileName.toString().contains("refactorkit-download") ||
                it.fileName.toString().contains("refactorkit-sha256")
        } })
    }

    @Then("no caller-attested classpath or lexical evidence supplies authority, and {string} remains review-only and managed-write ineligible")
    fun ordinaryMissingAuthorityIsEngineOwned(evidence: String) {
        lexicalFallbackRemainsReviewOnlyForOfflineAuthority(evidence)
        val lease = ordinaryLeaseFromApprovedPreview()
        assertTrue(lease.coreLease.requiredClasspathEvidence.any {
            it.path == Path.of(ORDINARY_MISSING_JAR) &&
                it.kind == org.refactorkit.core.ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT
        })
        assertTrue(lease.selectedMissingBinaryRecords.single().providedTypes.isEmpty())
    }

    @Then("the lease revalidates the canonical repository identity, POM and effective-input hashes, leaf proof, and matching under-lock no-follow {string} JAR observation before write-ahead-log creation")
    fun ordinaryMissingLeaseWasRevalidatedBeforeWal(status: String) {
        assertEquals("ABSENT", status)
        assertIs<ApplyResult.Applied>(offlineApplyResult)
        val record = ordinaryMissingRecordFromApprovedPreview()
        val required = ordinaryLeaseFromApprovedPreview().coreLease.requiredClasspathEvidence
        assertTrue(required.any { it.path == record.deterministicJarPath && it.fingerprint == record.beforeJarAbsence.fingerprint })
        assertTrue(required.any { it.path == record.deterministicPomPath && it.fingerprint != "missing" })
        assertTrue(TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords().isNotEmpty())
    }

    @Then("authoritative after diagnostics attest the committed snapshot with zero unresolved-type diagnostics attributable to the absent JAR")
    fun ordinaryMissingAfterDiagnosticsAttestWithoutUnknownTypes() {
        val attestation = ordinaryLeaseFromApprovedPreview().attest(assertNotNull(offlineAfterSnapshot))
        assertTrue(attestation.valid, attestation.blockers.toString())
        assertEquals("STAGED", attestation.phase)
        assertTrue(attestation.retainedDiagnostics.isEmpty())
    }

    @When("each evidence drift is introduced independently for a fresh eligible preview after its workspace lock is acquired and before write-ahead-log creation:")
    fun ordinaryMissingEvidenceDriftsAreInjectedUnderLock(table: DataTable) {
        val savedWorkspace = workspaceRoot
        val savedSnapshot = offlineAuthoritySnapshot
        val savedSetupPreview = offlineAuthoritySetupPreview
        val savedPreviews = offlineAuthorityPreviews
        val savedApproved = approvedOfflinePreview
        val results = mutableListOf<OrdinaryDriftResult>()
        try {
            table.asMaps().forEachIndexed { index, row ->
                val drift = row.getValue("evidence drift")
                val driftRoot = Files.createTempDirectory("refactorkit-move-auth-011-drift-$index-")
                try {
                    workspaceRoot = driftRoot.resolve("workspace")
                    copyRecursively(fixtureTemplate, workspaceRoot)
                    val snapshot = scanWorkspace()
                    val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
                        snapshot,
                        PRODUCT_FQN,
                        PRODUCT_TARGET_PACKAGE,
                    )
                    assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence, preview.plan.warnings.toString())
                    val lease = assertNotNull(preview.targetAuthorityLease)
                    var lockObserved = false
                    var postDriftState: FilesystemState? = null
                    val injector = PatchFaultInjector { point, _, _ ->
                        if (point != PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) return@PatchFaultInjector
                        lockObserved = Files.isRegularFile(
                            workspaceRoot.resolve(".refactorkit/workspace.lock"),
                            LinkOption.NOFOLLOW_LINKS,
                        )
                        when (index) {
                            0 -> Files.write(workspaceRoot.resolve(ORDINARY_MISSING_JAR), byteArrayOf(1, 1))
                            1 -> Files.copy(
                                workspaceRoot.resolve("pom.xml"),
                                workspaceRoot.resolve(ORDINARY_MISSING_POM),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            2 -> {
                                val repository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH)
                                val moved = workspaceRoot.resolve("fixture-repository-drifted")
                                Files.move(repository, moved)
                                Files.createSymbolicLink(repository, moved.fileName)
                            }
                            else -> error("Unexpected REQ-011 drift row: $drift")
                        }
                        postDriftState = captureFilesystemState()
                    }
                    val result = PatchEngine(workspaceRoot, faultInjector = injector).apply(
                        preview.plan,
                        snapshot,
                        ApplyAuthorization.explicit("cucumber", "REQ-JAVA-MAVEN-MOVE-AUTH-011-drift-$index"),
                        DiagnosticsGate.enabled("java-move-class-ordinary-missing", lease::managedDiagnostics),
                    )
                    results += OrdinaryDriftResult(
                        description = drift,
                        result = assertIs<ApplyResult.Refused>(result),
                        lockObserved = lockObserved,
                        postDriftState = assertNotNull(postDriftState),
                        postApplyState = captureFilesystemState(),
                        walExists = Files.exists(
                            workspaceRoot.resolve(".refactorkit/transactions"),
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                    )
                } finally {
                    deleteRecursively(driftRoot)
                }
            }
        } finally {
            workspaceRoot = savedWorkspace
            offlineAuthoritySnapshot = savedSnapshot
            offlineAuthoritySetupPreview = savedSetupPreview
            offlineAuthorityPreviews = savedPreviews
            approvedOfflinePreview = savedApproved
        }
        ordinaryDriftResults = results
    }

    @Then("each apply is {string} with managed-write eligibility {string} and a stable evidence-drift blocker naming the coordinate, path, and changed evidence")
    fun ordinaryMissingDriftsAreRefused(resultType: String, eligibility: String) {
        assertEquals("REFUSED", resultType)
        assertEquals("INELIGIBLE", eligibility)
        assertEquals(3, ordinaryDriftResults.size)
        ordinaryDriftResults.forEach { result ->
            assertTrue(result.lockObserved, result.description)
            assertTrue(result.result.diagnostics.any { diagnostic ->
                diagnostic.code == "authorityLease.evidenceDrift" &&
                    diagnostic.message.contains(ORDINARY_MISSING_COORDINATE) &&
                    diagnostic.message.contains("ordinary-missing-leaf-1.0.0")
            }, result.result.toString())
        }
    }

    @Then("each refusal occurs while the workspace lock is held but before write-ahead-log creation")
    fun ordinaryMissingDriftRefusalsOccurBeforeWal() {
        assertTrue(ordinaryDriftResults.all { it.lockObserved && !it.walExists }, ordinaryDriftResults.toString())
    }

    @Then("no write-ahead log, managed edit, managed transaction, or RefactorKit workspace mutation is created")
    fun ordinaryMissingDriftsCreateNoManagedMutation() {
        ordinaryDriftResults.forEach { result ->
            assertFalse(result.walExists, result.description)
            assertEquals(result.postDriftState, result.postApplyState, result.description)
        }
    }

    @Given("the profile selects one fixture-owned external parent through a compile-visible target-closure dependency path")
    fun descriptorPruningParentIsCompileVisible() {
        val profile = profileBlock(Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml")), AUTH_010_PROFILE)
        assertTrue(profile.contains("<groupId>com.acme.fixture.external</groupId>"))
        assertTrue(profile.contains("<artifactId>descriptor-pruning-parent</artifactId>"))
        assertTrue(profile.contains("<scope>compile</scope>"))
        assertTrue(profile.contains("<exclusions>"))
    }

    @Given("the selected parent's effective POM, parents, BOMs, dependency-management, mediation, repository, and declaration inputs are present, current, and hash-bound")
    fun descriptorPruningParentInputsAreHashBound() {
        val inputs = listOf(
            workspaceRoot.resolve(DESCRIPTOR_PRUNING_PARENT_POM),
            workspaceRoot.resolve(DESCRIPTOR_PRUNING_PARENT_JAR),
            workspaceRoot.resolve("pom.xml"),
            workspaceRoot.resolve("catalog-pricing/pom.xml"),
        )
        assertTrue(inputs.all(Files::isRegularFile), "Required REQ-010 parent inputs must be materialized")
        descriptorRequiredInputHashes = inputs.associateWith { sha256(Files.readAllBytes(it)) }
        assertTrue(descriptorRequiredInputHashes.values.all(SHA256_PATTERN::matches))
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml")))
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")))
    }

    @Given("the selected parent POM explicitly declares these field-shaped external children:")
    fun descriptorPruningParentDeclaresChildren(table: DataTable) {
        val parentPom = Files.readString(workspaceRoot.resolve(DESCRIPTOR_PRUNING_PARENT_POM))
        descriptorChildren = table.asMaps().map { row ->
            val child = DescriptorChild(
                groupId = row.getValue("groupId"),
                artifactId = row.getValue("artifactId"),
                version = row.getValue("version"),
                type = row.getValue("type"),
                classifier = row.getValue("classifier"),
                declaredScope = row.getValue("declared scope"),
                optional = row.getValue("optional").toBooleanStrict(),
                inheritedPathExclusion = row.getValue("inherited path exclusion"),
                expectedReason = when (row.getValue("exact downstream prune")) {
                    "transitive test scope" -> "SCOPE_TEST"
                    "transitive provided scope" -> "SCOPE_PROVIDED"
                    "transitive optional declaration" -> "OPTIONAL"
                    "exact inherited group/artifact exclusion" -> "EXCLUSION"
                    else -> error("Unexpected REQ-010 prune reason: ${row.getValue("exact downstream prune")}")
                },
            )
            val declaration = DEPENDENCY_BLOCK_PATTERN.findAll(parentPom).map(MatchResult::value).single { block ->
                block.contains("<groupId>${child.groupId}</groupId>") &&
                    block.contains("<artifactId>${child.artifactId}</artifactId>")
            }
            assertTrue(declaration.contains("<version>${child.version}</version>"))
            assertTrue(declaration.contains("<type>${child.type}</type>"))
            if (child.classifier == "empty") assertFalse(declaration.contains("<classifier>"))
            else assertTrue(declaration.contains("<classifier>${child.classifier}</classifier>"))
            assertTrue(declaration.contains("<scope>${child.declaredScope}</scope>"))
            assertEquals(child.optional, declaration.contains("<optional>true</optional>"))
            child
        }
        assertEquals(EXPECTED_AUTH_010_CHILDREN, descriptorChildren.toSet())
        val profile = profileBlock(Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml")), AUTH_010_PROFILE)
        assertTrue(profile.contains("<groupId>org.springframework</groupId>"))
        assertTrue(profile.contains("<artifactId>spring-context-support</artifactId>"))
    }

    @Given("every listed version is fixed and non-snapshot and every type and classifier has an unambiguous supported {string} or {string} variant mapping")
    fun descriptorChildVersionsAndVariantsAreDeterministic(jarType: String, testJarType: String) {
        assertEquals("jar", jarType)
        assertEquals("test-jar", testJarType)
        descriptorChildren.forEach { child ->
            assertFalse(child.version.contains('[') || child.version.contains('(') || child.version.endsWith("-SNAPSHOT"))
            assertTrue(child.type in setOf(jarType, testJarType))
            assertEquals(if (child.type == "test-jar") "tests" else "empty", child.classifier)
        }
    }

    @Given("type and classifier participate only in dependency-management and variant identity and never authorize pruning")
    fun typeAndClassifierDoNotAuthorizePruning() {
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        descriptorChildren.forEach { child ->
            val managed = DEPENDENCY_BLOCK_PATTERN.findAll(rootPom).map(MatchResult::value).single { block ->
                block.contains("<groupId>${child.groupId}</groupId>") &&
                    block.contains("<artifactId>${child.artifactId}</artifactId>")
            }
            assertTrue(managed.contains("<type>jar</type>"))
            assertTrue(child.expectedReason.startsWith("SCOPE_") || child.expectedReason in setOf("OPTIONAL", "EXCLUSION"))
        }
    }

    @Given("every listed child's repository POM and normalized JAR path is deliberately absent")
    fun descriptorChildArtifactsAreAbsent() {
        descriptorChildren.forEach { child ->
            val base = workspaceRoot.resolve(repositoryPath(child.groupId, child.artifactId, child.version))
            assertFalse(Files.exists(base.resolve("${child.artifactId}-${child.version}.pom"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(base.resolve(child.artifactFileName()), LinkOption.NOFOLLOW_LINKS))
        }
        assertTrue(AUTH_009_REPOSITORY_POMS.all { Files.isRegularFile(fixtureTemplate.resolve(it)) })
    }

    @Given("all other full-reactor structure, target-closure, candidate-total JDT, target-name lookup, platform, provider, diagnostic, and freshness evidence required by the move is complete and hash-bound")
    fun allOtherDescriptorPruningAuthorityInputsAreComplete() {
        assertEquals(21, Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }.count()
        })
        assertTrue(descriptorRequiredInputHashes.all { (path, hash) ->
            Files.isRegularFile(path) && sha256(Files.readAllBytes(path)) == hash
        })
        EXPECTED_OBSERVER_PATHS.forEach { assertTrue(Files.isRegularFile(workspaceRoot.resolve(it))) }
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("every before and staged target-closure main and test analysis environment is {string}")
    fun descriptorPruningAuthorityEnvironmentsAreExpectedAvailable(status: String) {
        assertEquals("AVAILABLE", status)
        expectedObserverSourceSets = EXPECTED_OBSERVER_SOURCE_SETS
    }

    @When("descriptor-closed Maven traversal builds every active main and test projection and the move is previewed twice from identical evidence hashes")
    fun descriptorClosedTraversalAndMoveArePreviewedTwice() {
        val first = scanWorkspace()
        val second = scanWorkspace()
        val planner = JavaMoveClassPlanner(JavaLanguageAdapter())
        offlineAuthoritySnapshot = first
        descriptorSecondSnapshot = second
        offlineAuthorityPreviews = listOf(
            planner.previewWithAuthority(first, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
            planner.previewWithAuthority(first, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
        )
        approvedOfflinePreview = offlineAuthorityPreviews.last()
        descriptorSelectorEvidenceHash = selectorEvidenceHash(first)
    }

    @Then("every outgoing child declaration and its selector inputs are hash-bound with the declaring POM, consumer module, source set, projection, dependency path, normalized variant, exact {string} outcome, and one exact scope, optional, or exclusion reason")
    fun descriptorSelectorOutcomesAreHashBound(outcome: String) {
        assertEquals("PRUNED", outcome)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val records = descriptorChildSelectorRecords(snapshot)
        val graphMessages = snapshot.modules.mapNotNull { it.languageSettings["java.dependencyGraph.message"] }
        assertTrue(
            records.isNotEmpty(),
            "No hash-bound selector records were exposed; traversal reported: ${graphMessages.joinToString(" | ")}",
        )
        assertEquals(18, records.size, records.toString())
        assertEquals(setOf("COMPILE", "RUNTIME", "TEST"), records.mapTo(sortedSetOf(), SelectorRecord::projection))
        assertEquals(setOf("main", "test"), records.mapTo(sortedSetOf(), SelectorRecord::sourceSet))
        records.forEach { record ->
            val expected = descriptorChildren.single { it.groupId == record.groupId && it.artifactId == record.artifactId }
            assertEquals(outcome, record.outcome)
            assertEquals(expected.expectedReason, record.reason)
            assertEquals(expected.normalizedVariant(), record.variant)
            assertEquals(expected.version, record.declaredVersion)
            assertEquals(expected.declaredScope, record.declaredScope)
            assertEquals(expected.optional, record.optional)
            assertTrue(record.declaringPom.endsWith("descriptor-pruning-parent-1.0.0.pom"))
            assertEquals("com.acme.refactorkit.fixture:catalog-pricing:1.0.0", record.consumer)
            assertTrue(record.dependencyPath.contains("descriptor-pruning-parent"))
            assertTrue(record.dependencyPath.contains(record.artifactId))
            assertEquals("BEFORE_VERSION_RANGE_REPOSITORY_POM_JAR_NETWORK", record.lookupBoundary)
        }
        assertTrue(SHA256_PATTERN.matches(assertNotNull(descriptorSelectorEvidenceHash)))
        assertEquals(snapshot.hash, assertNotNull(descriptorSecondSnapshot).hash)
    }

    @Then("every listed edge is pruned before child version or range resolution, repository or path selection, POM or effective-model lookup, JAR lookup, or any network request")
    fun descriptorChildrenArePrunedBeforeLookup() {
        val records = descriptorChildSelectorRecords(assertNotNull(offlineAuthoritySnapshot))
        assertTrue(records.all { it.lookupBoundary == "BEFORE_VERSION_RANGE_REPOSITORY_POM_JAR_NETWORK" })
        descriptorChildren.forEach { child ->
            assertEquals(3, records.count { it.groupId == child.groupId && it.artifactId == child.artifactId })
        }
        assertTrue(Files.walk(workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH)).use { paths ->
            paths.noneMatch { it.fileName.toString().contains("refactorkit-download") }
        })
    }

    @Then("no listed child contributes a selected node, subtree, compile or test analysis-classpath entry, missing descriptor, missing binary, or graph failure on that path")
    fun descriptorChildrenDoNotContributeToSelectedGraph() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        assertTrue(descriptorChildSelectorRecords(snapshot).all { it.outcome == "PRUNED" })
        val childNames = descriptorChildren.map(DescriptorChild::artifactId).toSet()
        val pricing = snapshot.modules.single { it.name == "catalog-pricing" }
        assertTrue((pricing.mainClasspathEntries + pricing.testClasspathEntries).none { path ->
            childNames.any { child -> path.fileName?.toString()?.startsWith("$child-") == true }
        })
        assertEquals("complete", pricing.languageSettings["java.dependencyGraph.status"], pricing.languageSettings.toString())
        assertEquals("available", pricing.languageSettings["java.mainClasspath.status"])
        assertEquals("available", pricing.languageSettings["java.testClasspath.status"])
        val graphText = snapshot.modules.mapNotNull { it.languageSettings["java.dependencyGraph.message"] }.joinToString("; ")
        descriptorChildren.forEach { child -> assertFalse(graphText.contains("${child.groupId}:${child.artifactId}"), graphText) }
    }

    @Then("reactor structure status remains {string} and every target-closure environment remains {string}")
    fun descriptorPruningStructureAndEnvironmentsRemainAvailable(structure: String, environment: String) {
        assertEquals("COMPLETE", structure)
        assertEquals("AVAILABLE", environment)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        assertTrue(snapshot.modules.all { it.languageSettings["java.dependencyGraph.status"] == "complete" })
        val model = snapshot.buildModels.single()
        assertEquals(BuildModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        val closureIds = setOf("catalog-model", "catalog-pricing", "catalog-storefront", "catalog-acceptance")
        assertTrue(model.modules.filter { it.id in closureIds }.flatMap { it.sourceSets }.all {
            it.attributes["java.classpath.status"] == "available"
        })
    }

    @Then("the selected and pruned edge records, target closure, candidate records, normalized edits, and diagnostics are identical between previews")
    fun descriptorPruningPreviewsAreDeterministic() {
        val firstSnapshot = assertNotNull(offlineAuthoritySnapshot)
        val secondSnapshot = assertNotNull(descriptorSecondSnapshot)
        assertEquals(selectorAttributes(firstSnapshot), selectorAttributes(secondSnapshot))
        assertEquals(selectorEvidenceHash(firstSnapshot), selectorEvidenceHash(secondSnapshot))
        assertEquals(firstSnapshot.hash, secondSnapshot.hash)
        val first = offlineAuthorityPreviews[0].plan
        val second = offlineAuthorityPreviews[1].plan
        assertEquals(first.workspaceEdit, second.workspaceEdit)
        assertEquals(first.evidence, second.evidence)
        assertEquals(first.diagnosticsBefore, second.diagnosticsBefore)
        assertEquals(first.diagnosticsAfterPreview, second.diagnosticsAfterPreview)
    }

    @Then("no Maven lifecycle, plugin, annotation processor, credential helper, snapshot repository, or network activity is used")
    fun descriptorPruningUsesNoExecutableOrNetworkBuildInput() {
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")))
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml")))
        assertTrue(descriptorRequiredInputHashes.all { (path, hash) -> sha256(Files.readAllBytes(path)) == hash })
        assertTrue(Files.walk(workspaceRoot).use { paths ->
            paths.filter(Files::isRegularFile).noneMatch { path ->
                path.fileName.toString().contains("refactorkit-download") ||
                    path.fileName.toString().contains("refactorkit-sha256")
            }
        })
    }

    @When("the restored fixture is evaluated with any listed coordinate also selected through an authority-relevant alternate path at effective {string} or {string} scope without an optional or exact-exclusion prune")
    fun restoredFixtureIsEvaluatedThroughAlternatePath(compileScope: String, runtimeScope: String) {
        assertEquals("compile", compileScope)
        assertEquals("runtime", runtimeScope)
        val metadata = workspaceRoot.resolve(".refactorkit")
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(metadata).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
        assertFalse(Files.exists(metadata, LinkOption.NOFOLLOW_LINKS))
        alternateDescriptorPathVariant = true
        alternateDescriptorPreState = captureWorkspaceState(includeManagedMetadata = false)
        val snapshot = scanWorkspace()
        alternateDescriptorSnapshot = snapshot
        alternateDescriptorPreview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
    }

    @Then("that alternate edge is classified {string} and the child's complete effective POM, parent, BOM, management, mediation, and relocation closure becomes required before binary availability can be classified")
    fun alternateDescriptorEdgeIsSelectedBeforeBinaryClassification(outcome: String) {
        assertEquals("SELECTED", outcome)
        val snapshot = assertNotNull(alternateDescriptorSnapshot)
        val selected = selectorRecords(snapshot).filter { record ->
            record.groupId == "org.springframework" &&
                record.artifactId == "spring-context-support" &&
                record.outcome == outcome &&
                record.reason == "DIRECT"
        }
        assertTrue(selected.isNotEmpty(), selectorRecords(snapshot).toString())
        assertTrue(selected.any { it.dependencyPath.contains("spring-context-support") })
        val pricing = snapshot.modules.single { it.name == "catalog-pricing" }
        assertEquals("incomplete", pricing.languageSettings["java.dependencyGraph.status"])
        assertEquals("0", pricing.languageSettings["java.mainClasspath.missing.count"])
        assertEquals("0", pricing.languageSettings["java.testClasspath.missing.count"])
    }

    @Then("the absent child POM produces a typed {string} result with managed-write eligibility {string} and a stable structural blocker naming the coordinate, dependency path, projection, and missing descriptor")
    fun absentAlternateDescriptorProducesTypedStructuralRefusal(resultType: String, eligibility: String) {
        assertEquals("REFUSED", resultType)
        assertEquals("INELIGIBLE", eligibility)
        val preview = assertNotNull(alternateDescriptorPreview).plan
        assertEquals(PatchStatus.REFUSED, preview.status)
        assertEquals("java.maven.dependencyDescriptor.missing", preview.refusalCode)
        assertEquals(RefactoringEvidence.STRUCTURAL, preview.evidence)
        assertTrue(preview.summary.contains(AUTH_010_ALTERNATE_COORDINATE), preview.summary)
        assertTrue(preview.summary.contains("path="), preview.summary)
        assertTrue(preview.summary.contains("projection="), preview.summary)
        assertTrue(preview.summary.contains("missing descriptor"), preview.summary)
    }

    @Then("refusal occurs before workspace-lock acquisition and before write-ahead-log creation")
    fun alternateDescriptorRefusalOccursBeforeLockAndWal() {
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("no applyable plan ID, managed edit, lock file, write-ahead log, managed transaction, or workspace mutation is created")
    fun alternateDescriptorRefusalCreatesNoManagedWriteResidue() {
        val preview = assertNotNull(alternateDescriptorPreview).plan
        assertEquals(PatchStatus.REFUSED, preview.status)
        assertTrue(preview.workspaceEdit.edits.isEmpty())
        assertTrue(preview.affectedFiles.isEmpty())
        assertFalse(preview.requiresUserApproval)
        assertEquals(null, preview.authorityLease)
        val expected = assertNotNull(alternateDescriptorPreState)
        val actual = captureWorkspaceState(includeManagedMetadata = false)
        assertEquals(expected.pathKinds, actual.pathKinds)
        assertEquals(expected.fileHashes, actual.fileHashes)
        assertEquals(expected.sourceInventory, actual.sourceInventory)
        assertEquals(expected.snapshotHash, actual.snapshotHash)
        alternateDescriptorRefusalOccursBeforeLockAndWal()
    }

    @Given("every POM, parent, BOM, mediation input, and transitive edge in the fixture's full effective reactor graph is complete, current, and hash-bound")
    fun explicitScopeFixtureGraphInputsAreMaterialized() {
        val repositoryPoms = AUTH_009_REPOSITORY_POMS.map(workspaceRoot::resolve)
        assertTrue(repositoryPoms.all(Files::isRegularFile), "Every REQ-009 repository POM must be materialized")
        fixtureRepositoryPomHashes = repositoryPoms.associateWith { pom -> sha256(Files.readAllBytes(pom)) }
        assertEquals(repositoryPoms.size, fixtureRepositoryPomHashes.size)
        assertTrue(fixtureRepositoryPomHashes.values.all(SHA256_PATTERN::matches))
    }

    @Given("a compile-visible fixture dependency has a fixture-owned POM that explicitly declares these field-shaped transitive test children while dependency management repeats each same groupId, artifactId, type, and classifier:")
    fun explicitTransitiveTestChildrenMatchDependencyManagement(table: DataTable) {
        val parentPom = Files.readString(workspaceRoot.resolve(FIELD_SCOPE_PARENT_POM))
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        explicitTestChildren = table.asMaps().map { row ->
            val child = FixtureDependency(
                groupId = row.getValue("groupId"),
                artifactId = row.getValue("artifactId"),
                type = row.getValue("type"),
                classifier = row.getValue("classifier"),
            )
            assertEquals("jar", child.type)
            assertEquals("empty", child.classifier)
            val declaration = dependencyBlock(parentPom, child)
            assertTrue(declaration.contains("<scope>test</scope>"), "$child must be explicitly test-scoped")
            assertTrue(declaration.contains("<version>$AUTH_009_CHILD_VERSION</version>"))
            val managed = dependencyBlock(rootPom, child)
            assertTrue(managed.contains("<version>$AUTH_009_CHILD_VERSION</version>"))
            when (row.getValue("matching managed scope")) {
                "omitted, defaulting to compile" -> assertFalse(
                    managed.contains("<scope>"),
                    "$child must exercise omitted managed scope",
                )
                "compile" -> assertTrue(managed.contains("<scope>compile</scope>"))
                else -> error("Unexpected managed scope fixture row: ${row.getValue("matching managed scope")}")
            }
            child
        }
        assertEquals(EXPECTED_AUTH_009_CHILDREN, explicitTestChildren.toSet())
        val pricingPom = Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml"))
        val profile = profileBlock(pricingPom, AUTH_009_PROFILE)
        assertTrue(profile.contains("<artifactId>field-scope-parent</artifactId>"))
        assertTrue(profile.contains("<scope>compile</scope>"))
    }

    @Given("every listed POM, coordinate, version, repository entry, and source set belongs to the permanent fixture")
    fun everyExplicitScopeInputBelongsToTheFixture() {
        val repositoryRoot = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH).toRealPath()
        fixtureRepositoryPomHashes.keys.forEach { pom ->
            assertTrue(pom.toRealPath().startsWith(repositoryRoot), "$pom must remain inside the fixture repository")
        }
        assertEquals(FIELD_SCOPE_PARENT_SHA256, sha256(Files.readAllBytes(workspaceRoot.resolve(FIELD_SCOPE_PARENT_JAR))))
        explicitTestChildren.forEach { child ->
            val base = repositoryPath(child.groupId, child.artifactId, AUTH_009_CHILD_VERSION)
            assertTrue(Files.isRegularFile(workspaceRoot.resolve("$base/${child.artifactId}-$AUTH_009_CHILD_VERSION.pom")))
            assertFalse(
                Files.exists(workspaceRoot.resolve("$base/${child.artifactId}-$AUTH_009_CHILD_VERSION.jar")),
                "$child JAR must be intentionally absent",
            )
        }
    }

    @Given("the complete graph proves the target's before-and-staged authority closure and every main and test source-set analysis environment in that closure is {string}")
    fun fixtureDeclaresAnAvailableProductAuthorityClosure(status: String) {
        assertEquals("AVAILABLE", status)
        expectedObserverSourceSets = EXPECTED_OBSERVER_SOURCE_SETS
        val requiredRoots = mapOf(
            TARGET_OWNER_SOURCE_SET to "catalog-model/src/main/java",
            PRICING_SOURCE_SET to "catalog-pricing/src/main/java",
            "catalog-storefront:main" to "catalog-storefront/src/main/java",
            "catalog-acceptance:test" to "catalog-acceptance/src/test/java",
        )
        requiredRoots.forEach { (sourceSet, root) ->
            assertTrue(Files.isDirectory(workspaceRoot.resolve(root)), "$sourceSet source root must be materialized")
        }
    }

    @Given("ordinary binary absence is confined to structurally excluded modules outside that closure, so global external classpath status is {string}")
    fun ordinaryBinaryAbsenceIsOutsideTheProductClosure(status: String) {
        assertEquals(BuildModelStatus.OFFLINE_MISSING.name, status)
        val outsidePom = workspaceRoot.resolve(OUTSIDE_CLOSURE_POM)
        val outsideJar = workspaceRoot.resolve(OUTSIDE_CLOSURE_JAR)
        assertTrue(Files.isRegularFile(outsidePom), "The outside-closure dependency POM must be present")
        assertFalse(Files.exists(outsideJar), "The outside-closure dependency JAR must be absent")
        val reportingPom = Files.readString(workspaceRoot.resolve("reporting-unrelated/pom.xml"))
        val profile = profileBlock(reportingPom, AUTH_009_PROFILE)
        assertTrue(profile.contains("<groupId>com.acme.fixture.missing</groupId>"))
        assertTrue(profile.contains("<artifactId>outside-product-closure</artifactId>"))
    }

    @Given("one exact non-recovered target declaration and the full-reactor Java candidate inventory classify every target-capable range exactly once as {string} or {string}, with zero {string} candidates")
    fun fixtureHasExactTargetAndDecoyBindings(boundTarget: String, boundOther: String, unresolved: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_TARGET.name, boundTarget)
        assertEquals(JavaMoveClassCandidateClassification.BOUND_OTHER.name, boundOther)
        assertEquals(JavaMoveClassCandidateClassification.UNRESOLVED.name, unresolved)
        val snapshot = scanWorkspace()
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val target = analysis.symbols.single { symbol ->
            symbol.qualifiedName == PRODUCT_FQN && symbol.path == Path.of(PRODUCT_SOURCE_PATH)
        }
        assertNotNull(target.bindingKey)
        assertFalse(target.recovered)
        val decoy = analysis.symbols.single { it.qualifiedName == "com.acme.decoy.Product" }
        assertNotNull(decoy.bindingKey)
        assertFalse(decoy.recovered)
        assertFalse(target.bindingKey == decoy.bindingKey)
    }

    @Given("apart from the declaration, every simple-name {string} use has exactly one resolved non-static single-type import of the target FQN and every other target use is the exact target FQN")
    fun fixtureTargetUsesHaveExactSingleTypeImports(classification: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_TARGET.name, classification)
        EXPECTED_OBSERVER_PATHS.forEach { path ->
            val imports = JavaLexer.extractImports(Files.readString(workspaceRoot.resolve(path)))
            assertEquals(1, imports.count { !it.isStatic && it.name == PRODUCT_FQN }, "$path target import")
        }
        assertTrue(Files.readString(workspaceRoot.resolve(PRODUCT_STEPS_PATH)).contains(PRODUCT_FQN))
    }

    @Given("no relevant type or static wildcard, unresolved static import, enclosing type, or supertype ambiguity can change target-name lookup")
    fun fixtureHasNoTargetNameLookupAmbiguity() {
        EXPECTED_OBSERVER_PATHS.forEach { path ->
            val source = Files.readString(workspaceRoot.resolve(path))
            assertTrue(JavaLexer.extractImports(source).none { it.name.endsWith(".*") }, "$path has a wildcard import")
            assertFalse(source.contains("extends Product"), "$path must not inherit Product lookup")
        }
    }

    @When("effective Maven traversal derives the consumer's main and test analysis classpaths and the move is previewed")
    fun effectiveTraversalAndMoveArePreviewed() {
        val snapshot = scanWorkspace()
        val planner = JavaMoveClassPlanner(JavaLanguageAdapter())
        val previews = listOf(
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
        )
        offlineAuthoritySnapshot = snapshot
        offlineAuthoritySetupPreview = previews.first()
        offlineAuthorityPreviews = previews
        approvedOfflinePreview = previews.last()
    }

    @Then("each listed child's explicit {string} scope is preserved instead of the matching default or {string} managed scope")
    fun explicitChildScopeWinsOverManagedScope(explicitScope: String, managedScope: String) {
        assertEquals("test", explicitScope)
        assertEquals("compile", managedScope)
        val pricing = assertNotNull(offlineAuthoritySnapshot).modules.single { it.name == "catalog-pricing" }
        val reports = listOfNotNull(
            pricing.languageSettings["java.mainClasspath.message"],
            pricing.languageSettings["java.runtimeClasspath.message"],
            pricing.languageSettings["java.testClasspath.message"],
        )
        val promoted = explicitTestChildren.filter { child ->
            reports.any { report -> report.contains("${child.groupId}:${child.artifactId}:$AUTH_009_CHILD_VERSION") }
        }
        assertTrue(
            promoted.isEmpty(),
            "Explicit transitive test children were promoted by dependency management and poisoned Product authority: $promoted; $reports",
        )
    }

    @Then("every listed child is excluded from the consumer's compile and test analysis classpaths")
    fun explicitTestChildrenAreExcludedFromConsumerClasspaths() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val pricing = snapshot.modules.single { it.name == "catalog-pricing" }
        val childArtifacts = explicitTestChildren.map { child ->
            workspaceRoot.resolve(
                repositoryPath(child.groupId, child.artifactId, AUTH_009_CHILD_VERSION) +
                    "/${child.artifactId}-$AUTH_009_CHILD_VERSION.jar",
            ).normalize()
        }.toSet()
        assertTrue(pricing.mainClasspathEntries.none { it.toAbsolutePath().normalize() in childArtifacts })
        assertTrue(pricing.testClasspathEntries.none { it.toAbsolutePath().normalize() in childArtifacts })
        val sourceSets = snapshot.buildModels.single().modules.single { it.id == "catalog-pricing" }.sourceSets
        assertTrue(sourceSets.all { it.attributes["java.classpath.status"] != "unavailable" }, sourceSets.toString())
    }

    @Then("no listed child is reported as a missing consumer or target-closure binary")
    fun explicitTestChildrenAreNotReportedMissing() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val closureModules = setOf("catalog-model", "catalog-pricing", "catalog-storefront", "catalog-acceptance")
        val closureDiagnostics = snapshot.buildModels.single().diagnostics.filter { it.moduleId in closureModules }
        explicitTestChildren.forEach { child ->
            assertTrue(closureDiagnostics.none { it.message.contains("${child.groupId}:${child.artifactId}") })
        }
        assertTrue(closureDiagnostics.isEmpty(), closureDiagnostics.toString())
    }

    @Then("reactor structure status remains {string} while global external classpath status remains {string} solely for the structurally excluded outside-closure modules")
    fun globalOfflineMissingIsConfinedOutsideTheClosure(structure: String, external: String) {
        assertEquals("COMPLETE", structure)
        assertEquals(BuildModelStatus.OFFLINE_MISSING.name, external)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val model = snapshot.buildModels.single()
        assertEquals(BuildModelStatus.OFFLINE_MISSING, model.status)
        assertEquals(
            setOf("reporting-unrelated"),
            model.diagnostics.filter { it.code == "classpath.offlineMissing" }.mapNotNull { it.moduleId }.toSet(),
            model.diagnostics.toString(),
        )
        assertTrue(model.modules.all { it.attributes["java.dependencyGraph.status"] == "complete" })
        val lease = offlineLeaseFromApprovedPreview()
        assertEquals(structure, lease.coreLease.attributes["reactorStructureStatus"])
        assertEquals(external, lease.coreLease.attributes["externalClasspathStatus"])
        val effectiveInputs = snapshot.classpathEvidence
            .filter { it.kind == ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT }
            .map { it.path.normalize() }
            .toSet()
        val structurallyRequiredInputs = setOf(FIELD_SCOPE_PARENT_POM, OUTSIDE_CLOSURE_POM).map(Path::of).toSet()
        assertTrue(
            effectiveInputs.containsAll(structurallyRequiredInputs),
            "Unbound structurally selected fixture POMs: ${structurallyRequiredInputs - effectiveInputs}",
        )
        val prunedChildPoms = AUTH_009_REPOSITORY_POMS.drop(2).map(Path::of).toSet()
        assertTrue(effectiveInputs.intersect(prunedChildPoms).isEmpty())
        assertTrue(fixtureRepositoryPomHashes.all { (path, hash) -> sha256(Files.readAllBytes(path)) == hash })
    }

    @Then("managed eligibility requires no per-binary coordinate\\/path, provided-type, or retained-diagnostic inventory for missing binaries outside the closure after structural and candidate exclusion is proved")
    fun outsideClosureMissingBinariesNeedNoOperationInventory() {
        val lease = offlineLeaseFromApprovedPreview()
        assertTrue(lease.offlineMissingEntries.isEmpty())
        assertTrue(lease.retainedDiagnosticsBefore.isEmpty())
        assertTrue(lease.coreLease.requiredClasspathEvidence.none { evidence ->
            evidence.path.toString().replace('\\', '/').contains("outside-product-closure-1.0.0.jar")
        })
    }

    @Then("the result is a {string} with evidence {string} and managed-write eligibility {string}")
    fun explicitScopeResultIsEligibleSemanticPreview(resultType: String, evidence: String, eligibility: String) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals(RefactoringEvidence.JDT_BINDING.name, evidence)
        assertEquals("ELIGIBLE", eligibility)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        offlineAuthorityPreviews.forEach { preview ->
            assertEquals(PatchStatus.PREVIEW, preview.plan.status)
            assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
            assertNotNull(preview.targetAuthorityLease)
            assertTrue(PatchEngine(workspaceRoot).validate(preview.plan, snapshot.hash).none {
                it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR
            })
        }
    }

    @Then("exact staged analysis preserves candidate-total JDT authority and target-name lookup protection without a new or changed error")
    fun stagedAnalysisPreservesCandidateAndLookupAuthority() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val preview = assertNotNull(approvedOfflinePreview)
        if (descriptorPruningVariant) {
            val before = JdtJavaSemanticAnalyzer().analyze(snapshot)
            val staged = WorkspaceEditSimulator.apply(snapshot, preview.plan.workspaceEdit)
            val stagedDiagnostics = JavaLanguageAdapter().diagnostics(staged)
            assertTrue(before.warnings.isEmpty(), before.warnings.toString())
            assertTrue(stagedDiagnostics.none {
                it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR
            }, stagedDiagnostics.toString())
            val beforeTarget = before.symbols.single { it.qualifiedName == PRODUCT_FQN }
            assertNotNull(beforeTarget.bindingKey)
            assertFalse(beforeTarget.recovered)
            assertTrue(preview.plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().all { edit ->
                edit.path == Path.of(PRODUCT_SOURCE_PATH) || edit.path in EXPECTED_OBSERVER_PATHS
            })
            val residualOldFqn = staged.files.filter { file ->
                file.languageId == "java" && JavaLexer.findOccurrences(file.content, PRODUCT_FQN).isNotEmpty()
            }
            assertTrue(residualOldFqn.isEmpty(), residualOldFqn.map { it.path }.toString())
            return
        }
        val lease = offlineLeaseFromApprovedPreview()
        assertTrue((lease.candidatesBefore + lease.candidatesStaged).none {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
        assertLookupProtected(snapshot, lease.candidatesBefore, PRODUCT_FQN, Path.of(PRODUCT_SOURCE_PATH))
        val staged = WorkspaceEditSimulator.apply(snapshot, preview.plan.workspaceEdit)
        assertLookupProtected(staged, lease.candidatesStaged, lease.newFqn, lease.newDeclarationPath)
        assertEquals(lease.allDiagnosticsBefore, lease.allDiagnosticsStaged)
        assertTrue(lease.attest(staged).valid, lease.attest(staged).blockers.toString())
    }

    @Then("authoritative after diagnostics attest the committed snapshot")
    fun authoritativeAfterDiagnosticsAttestTheCommittedSnapshot() {
        if (descriptorPruningVariant) {
            val analysis = JdtJavaSemanticAnalyzer().analyze(assertNotNull(offlineAfterSnapshot))
            assertTrue(analysis.warnings.isEmpty(), analysis.warnings.toString())
            val target = analysis.symbols.single { it.qualifiedName == "com.acme.catalog.api.Product" }
            assertNotNull(target.bindingKey)
            assertFalse(target.recovered)
            assertTrue(analysis.references.filter { it.symbolQualifiedName == target.qualifiedName }.none { it.recovered })
            return
        }
        val attestation = offlineLeaseFromApprovedPreview().attest(assertNotNull(offlineAfterSnapshot))
        assertTrue(attestation.valid, attestation.blockers.toString())
        assertEquals("STAGED", attestation.phase)
    }

    @Then("authoritative rollback diagnostics attest the restored snapshot")
    fun authoritativeRollbackDiagnosticsAttestTheRestoredSnapshot() {
        if (descriptorPruningVariant) {
            val diagnostics = JdtJavaSemanticAnalyzer().analyze(assertNotNull(offlineRollbackSnapshot)).warnings
            assertTrue(diagnostics.isEmpty(), diagnostics.toString())
            return
        }
        val attestation = offlineLeaseFromApprovedPreview().attest(assertNotNull(offlineRollbackSnapshot))
        assertTrue(attestation.valid, attestation.blockers.toString())
        assertEquals("BEFORE", attestation.phase)
    }

    @Given("the complete effective reactor proves that {string} owns the target and has this dependency-bounded reverse-observer closure:")
    fun theEffectiveReactorProvesTheDependencyBoundedObserverClosure(
        ownerSourceSet: String,
        observerTable: DataTable,
    ) {
        assertEquals(TARGET_OWNER_SOURCE_SET, ownerSourceSet)
        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        authoritySnapshot = snapshot
        val model = assertNotNull(
            snapshot.buildModels.singleOrNull { it.providerId == MAVEN_BUILD_MODEL_PROVIDER },
            "Exactly one effective Maven build model is required",
        )
        assertEquals(BuildModelStatus.AVAILABLE, model.status, model.diagnostics.toString())
        val ownership = snapshot.owningBuildSourceRoots(Path.of(PRODUCT_SOURCE_PATH))
        assertEquals(1, ownership.size, "The target must have one authoritative source-root owner")
        assertEquals(MAVEN_BUILD_MODEL_PROVIDER, ownership.single().providerId)
        assertEquals(BuildModelStatus.AVAILABLE, ownership.single().modelStatus)
        assertEquals(TARGET_OWNER_SOURCE_SET, ownership.single().sourceSetDisplayName())

        val rows = observerTable.asMaps()
        expectedObserverSourceSets = rows.map { row -> row.getValue("source set") }.toSet()
        assertEquals(EXPECTED_OBSERVER_SOURCE_SETS, expectedObserverSourceSets)
        assertEquals(rows.size, expectedObserverSourceSets.size, "Observer source sets must be unique")
        rows.forEach { row ->
            assertTrue(row.getValue("dependency relation") in setOf("direct", "transitive", "test-only"))
            val (moduleId, sourceSetId) = row.getValue("source set").split(':', limit = 2)
            val sourceSet = model.modules.single { it.id == moduleId }.sourceSets.single { it.id == sourceSetId }
            assertTrue(sourceSet.sourceRoots.isNotEmpty(), "${row.getValue("source set")} must be materialized")
        }
    }

    @Given("the target owner and every listed observer are binding-clean for the move")
    fun theAuthorityClosureIsBindingClean() {
        val snapshot = assertNotNull(authoritySnapshot)
        val protectedSourceSets = expectedObserverSourceSets + TARGET_OWNER_SOURCE_SET
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val scopedWarnings = analysis.warnings.filter { warning ->
            warningSourceSet(snapshot, warning.path) in protectedSourceSets
        }
        assertTrue(scopedWarnings.isEmpty(), "Authority-closure JDT warnings: $scopedWarnings")
        val target = assertNotNull(
            analysis.symbols.singleOrNull { it.qualifiedName == PRODUCT_FQN },
            "The target must have one JDT declaration",
        )
        assertNotNull(target.bindingKey, "The target declaration must have an exact JDT binding")
        val observerSourceSets = analysis.references
            .filter { it.bindingKey == target.bindingKey && it.path != Path.of(PRODUCT_SOURCE_PATH) }
            .mapNotNull { warningSourceSet(snapshot, it.path) }
            .toSet()
        assertEquals(expectedObserverSourceSets, observerSourceSets)
    }

    @Given("{string} is outside that closure and has unrelated JDT type-resolution warnings")
    fun theUnrelatedSourceSetHasOnlyExcludedTypeResolutionWarnings(sourceSet: String) {
        assertEquals(REPORTING_SOURCE_SET, sourceSet)
        assertTrue(sourceSet !in expectedObserverSourceSets + TARGET_OWNER_SOURCE_SET)
        val snapshot = assertNotNull(authoritySnapshot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(analysis.warnings.isNotEmpty(), "The isolated fixture variant must introduce a JDT warning")
        assertTrue(
            analysis.warnings.all { warning ->
                warning.category == JdtJavaDiagnosticCategory.TYPE_RESOLUTION &&
                    warningSourceSet(snapshot, warning.path) == REPORTING_SOURCE_SET
            },
            "Only reporting-unrelated:main may contain baseline warnings: ${analysis.warnings}",
        )
    }

    @When("the move is previewed")
    fun theAuthorityScopedMoveIsPreviewed() {
        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        authoritySnapshot = snapshot
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        closurePreviewPlan = plan
        closurePreviewGateCodes = PatchEngine(workspaceRoot).validate(plan, snapshot.hash)
            .mapNotNull { it.code }
            .toSet()
    }

    @Then("the result remains a {string} with evidence {string}")
    fun theResultRemainsAnEligibleSemanticPreview(resultType: String, evidence: String) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals("JDT_BINDING", evidence)
        val plan = assertNotNull(closurePreviewPlan)
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence, plan.warnings.toString())
        assertTrue("evidence.insufficient" !in closurePreviewGateCodes, closurePreviewGateCodes.toString())
    }

    @Then("exactly the three listed binding-proven observers are selected")
    fun exactlyTheThreeBindingProvenObserversAreSelected() {
        assertEquals(3, expectedObserverSourceSets.size)
        val plan = assertNotNull(closurePreviewPlan)
        val actualPaths = plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
            .map(FileEdit.Modify::path)
            .filterNot { it == Path.of(PRODUCT_SOURCE_PATH) }
            .toSet()
        assertEquals(EXPECTED_OBSERVER_PATHS, actualPaths)
        assertTrue(
            plan.warnings.any { it.contains("JDT type binding selected 3 referencing file(s)") },
            plan.warnings.toString(),
        )
        EXPECTED_OBSERVER_PATHS.forEach { path ->
            assertTrue(
                warningSourceSet(assertNotNull(authoritySnapshot), path) in expectedObserverSourceSets,
                "$path is not owned by a listed observer",
            )
        }
    }

    @Then("the preview reports {string} as excluded from the dependency-bounded reverse-observer closure")
    fun thePreviewReportsTheUnrelatedSourceSetAsExcluded(sourceSet: String) {
        assertEquals(REPORTING_SOURCE_SET, sourceSet)
        val warnings = assertNotNull(closurePreviewPlan).warnings
        assertTrue(
            warnings.any { warning ->
                warning.contains(sourceSet) &&
                    warning.contains("excluded from the dependency-bounded reverse-observer closure")
            },
            warnings.toString(),
        )
    }

    @Then("its unrelated warnings do not demote the move's semantic authority")
    fun unrelatedWarningsDoNotDemoteSemanticAuthority() {
        assertEquals(RefactoringEvidence.JDT_BINDING, assertNotNull(closurePreviewPlan).evidence)
        assertTrue("evidence.insufficient" !in closurePreviewGateCodes, closurePreviewGateCodes.toString())
    }

    @Then("a JDT warning in the target owner or any selected observer source set makes managed-write eligibility {string}")
    fun aWarningInsideTheClosureDemotesManagedWriteEligibility(eligibility: String) {
        assertEquals("INELIGIBLE", eligibility)
        introduceMissingTypeWarning(
            PRICING_SOURCE_PATH,
            "MissingPricingType",
            "closureWarning",
        )
        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        assertTrue(
            analysis.warnings.any { warning ->
                warning.category == JdtJavaDiagnosticCategory.TYPE_RESOLUTION &&
                    warningSourceSet(snapshot, warning.path) == PRICING_SOURCE_SET
            },
            "The selected-observer variant must contain a catalog-pricing:main type-resolution warning",
        )
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        val gateCodes = PatchEngine(workspaceRoot).validate(plan, snapshot.hash).mapNotNull { it.code }.toSet()
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, plan.evidence, plan.warnings.toString())
        assertTrue("evidence.insufficient" in gateCodes, gateCodes.toString())
    }

    @Given("no {string} directory exists")
    fun noManagedMetadataDirectoryExists(directory: String) {
        assertEquals(".refactorkit", directory)
        assertFalse(
            Files.exists(workspaceRoot.resolve(directory), LinkOption.NOFOLLOW_LINKS),
            "Managed metadata must be absent before a read-only or refused command",
        )
    }

    @Given("every source byte, path, source-inventory entry, and workspace snapshot hash is recorded")
    fun everyWorkspaceIdentityDimensionIsRecorded() {
        recordedState = captureWorkspaceState()
    }

    @When("`refactorkit scan .` is invoked")
    fun refactorKitScanIsInvoked() {
        scanResult = runCli(listOf("scan", workspaceRoot.toString()))
    }

    @When("`refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --preview` is invoked")
    fun lexicalFallbackMovePreviewIsInvoked() {
        previewResult = runCli(
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
        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        val plan = JavaMoveClassPlanner(JavaLanguageAdapter()).preview(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        previewEvidence = plan.evidence
        previewGateCodes = PatchEngine(workspaceRoot).validate(plan, snapshot.hash)
            .mapNotNull { it.code }
            .toSet()
    }

    @Then("the scan and preview are read-only")
    fun theScanAndPreviewAreReadOnly() {
        val scan = assertNotNull(scanResult)
        val preview = assertNotNull(previewResult)
        assertEquals(0, scan.exitCode, scan.failureMessage("scan"))
        assertEquals(0, preview.exitCode, preview.failureMessage("preview"))
        assertWorkspaceStateEqualsRecorded()
    }

    @Then("the preview reports {string} and managed-write eligibility {string}")
    fun thePreviewReportsReviewOnlyEvidence(evidence: String, eligibility: String) {
        assertEquals("LEXICAL_FALLBACK", evidence)
        assertEquals("INELIGIBLE", eligibility)
        val preview = assertNotNull(previewResult)
        val envelope = Json.parseToJsonElement(preview.stdout).jsonObject
        assertEquals(evidence, envelope.getValue("evidenceKind").jsonPrimitive.content)
        assertEquals(eligibility, envelope.getValue("managedWriteEligibility").jsonPrimitive.content)
        assertEquals("LEXICAL_FALLBACK_REVIEW", envelope.getValue("resultType").jsonPrimitive.content)
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, previewEvidence)
        assertTrue(
            "evidence.insufficient" in previewGateCodes,
            "LEXICAL_FALLBACK must be structurally ineligible for managed apply",
        )
    }

    @When("`refactorkit move-class --symbol com.acme.catalog.legacy.Product --to-package com.acme.catalog.api --apply` is invoked")
    fun lexicalFallbackMoveApplyIsInvoked() {
        applyResult = runCli(
            listOf(
                "move-class",
                "--symbol",
                PRODUCT_FQN,
                "--to-package",
                PRODUCT_TARGET_PACKAGE,
                workspaceRoot.toString(),
                "--apply",
            ),
        )
    }

    @Then("RefactorKit refuses with {string} before write-ahead-log creation")
    fun refactorKitRefusesWithTypedEvidenceBeforeWal(code: String) {
        assertEquals("evidence.insufficient", code)
        val apply = assertNotNull(applyResult)
        assertEquals(1, apply.exitCode, apply.failureMessage("apply refusal"))
        assertTrue(apply.stderr.contains("Apply refused [$code]"), apply.failureMessage("apply refusal"))
        assertTrue(
            apply.stderr.contains("lexical fallback review is non-managed"),
            apply.failureMessage("typed lexical refusal"),
        )
        assertTrue(code in previewGateCodes, "The central apply validator must expose the typed refusal code")
        assertFalse(
            Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS),
            "Refusal must happen before write-ahead-log creation",
        )
    }

    @Then("no {string} directory exists, including no zero-byte {string}")
    fun noManagedMetadataOrLockExists(directory: String, lockPath: String) {
        assertEquals(".refactorkit", directory)
        assertEquals(".refactorkit/workspace.lock", lockPath)
        val lock = workspaceRoot.resolve(lockPath)
        assertFalse(
            Files.exists(lock, LinkOption.NOFOLLOW_LINKS),
            "Refused lexical apply left $lockPath" +
                if (Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) " (${Files.size(lock)} bytes)" else "",
        )
        assertFalse(
            Files.exists(workspaceRoot.resolve(directory), LinkOption.NOFOLLOW_LINKS),
            "Refused lexical apply left managed metadata",
        )
    }

    @Then("no managed transaction exists")
    fun noManagedTransactionExists() {
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("every source byte, path, source-inventory entry, and workspace snapshot hash equals the recorded pre-command state")
    fun everyWorkspaceIdentityDimensionEqualsTheRecordedState() {
        assertWorkspaceStateEqualsRecorded()
    }

    @Given("target-scoped operation authority reports this complete structural row:")
    fun targetScopedAuthorityReportsCompleteStructuralRow(table: DataTable) {
        val row = table.asMaps().single()
        assertEquals("COMPLETE", row.getValue("reactorStructureStatus"))
        assertEquals("COMPLETE", row.getValue("observerClosureStatus"))
        assertEquals("OFFLINE_MISSING", row.getValue("externalClasspathStatus"))
        assertEquals(EXTERNAL_ARTIFACT_IDENTITY, row.getValue("offline-missing entry"))
        assertEquals(PRICING_SOURCE_SET, row.getValue("affected source set"))

        val snapshot = JavaProjectScanner().scan(workspaceRoot)
        assertEquals(BuildModelStatus.OFFLINE_MISSING, snapshot.buildModels.single().status)
        val preview = JavaMoveClassPlanner(JavaLanguageAdapter()).previewWithAuthority(
            snapshot,
            PRODUCT_FQN,
            PRODUCT_TARGET_PACKAGE,
        )
        val lease = assertNotNull(preview.targetAuthorityLease, preview.plan.warnings.toString())
        assertEquals("COMPLETE", lease.coreLease.attributes["reactorStructureStatus"])
        assertEquals("COMPLETE", lease.coreLease.attributes["observerClosureStatus"])
        assertEquals("OFFLINE_MISSING", lease.coreLease.attributes["externalClasspathStatus"])
        offlineAuthoritySnapshot = snapshot
        offlineAuthoritySetupPreview = preview
    }

    @Given("the missing entry is an enumerated leaf external artifact with hash-bound expected identity and negative-presence evidence")
    fun missingEntryHasExpectedIdentityAndNegativePresenceEvidence() {
        val lease = offlineLease()
        val entry = lease.offlineMissingEntries.single()
        assertEquals(EXTERNAL_ARTIFACT_IDENTITY, entry.identity)
        assertEquals(PRICING_SOURCE_SET, entry.affectedSourceSet)
        assertEquals(Path.of(EXTERNAL_ARTIFACT_PATH), entry.expectedPath)
        assertEquals(Path.of(EXTERNAL_ARTIFACT_EVIDENCE_PATH), entry.expectedIdentityManifest)
        assertEquals(EXTERNAL_ARTIFACT_SHA256, entry.expectedSha256)
        assertEquals(setOf("com.acme.fixture.external.PriceAuthority"), entry.providedTypes)
        assertEquals("missing", entry.negativePresenceFingerprint)
        assertTrue(entry.leaf)
        assertTrue(lease.coreLease.requiredClasspathEvidence.any {
            it.path == Path.of(EXTERNAL_ARTIFACT_PATH) && it.fingerprint == "missing"
        })
    }

    @Given("every POM, parent, BOM, mediation, variant, reactor edge, ownership, source root, materialized generated source, Java platform, and provider input needed by the closure is complete and hash-bound")
    fun allStructuralAuthorityInputsAreCompleteAndHashBound() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val model = snapshot.buildModels.single()
        assertEquals(MAVEN_BUILD_MODEL_PROVIDER, model.providerId)
        assertEquals(20, model.modules.size)
        assertEquals(21, snapshot.auxiliaryFiles.count { it.path.fileName.toString() == "pom.xml" })
        assertTrue(model.modules.all { module ->
            module.sourceSets.size == 2 && module.sourceSets.all { it.sourceRoots.isNotEmpty() }
        })
        assertTrue(snapshot.files.filter { it.languageId == "java" }.all {
            snapshot.owningBuildSourceRoots(it.path).size == 1
        })
        assertTrue(model.modules.flatMap { it.sourceSets }.all {
            it.attributes["java.sourceLevel"] == "8"
        })
        assertTrue(snapshot.files.any { it.path.toString().replace('\\', '/').contains("target/generated-sources/") })
        assertEquals(snapshot.hash, offlineLease().beforeSnapshotHash)
        assertEquals(64, offlineLease().evidenceHash.length)
        assertTrue(offlineLease().coreLease.requiredClasspathEvidence.size >= 2)
    }

    @Given("the target declaration has one exact non-recovered binding at its expected path and range")
    fun targetDeclarationHasOneExactNonRecoveredBinding() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val lease = offlineLease()
        val analysis = JdtJavaSemanticAnalyzer().analyze(snapshot)
        val target = analysis.symbols.single { it.qualifiedName == PRODUCT_FQN && it.path == Path.of(PRODUCT_SOURCE_PATH) }
        assertEquals(lease.targetBindingKey, target.bindingKey)
        assertEquals(lease.targetRange, target.sourceRange)
        assertFalse(target.recovered)
    }

    @Given("the bounded lexical Java scan inventories every old-FQN and matching simple-name candidate and classifies every exact range once:")
    fun boundedLexicalScanInventoriesEveryCandidate(table: DataTable) {
        val candidates = offlineLease().candidatesBefore
        assertEquals(candidates.size, candidates.map { it.path to it.sourceRange }.distinct().size)
        val expected = table.asMaps().associate { row ->
            row.getValue("candidate record") to JavaMoveClassCandidateClassification.valueOf(row.getValue("classification"))
        }
        expected.forEach { (sourceSet, classification) ->
            val records = candidates.filter { it.sourceSet == sourceSet }
            assertTrue(records.isNotEmpty(), "No lexical Product candidates for $sourceSet")
            assertTrue(records.all { it.classification == classification }, records.toString())
        }
        assertTrue(candidates.filter { it.sourceSet == TARGET_OWNER_SOURCE_SET }.all {
            it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
        })
    }

    @Given("no Java lexical candidate is {string}, including no target-relevant unresolved candidate")
    fun noJavaLexicalCandidateIsUnresolved(classification: String) {
        assertEquals(JavaMoveClassCandidateClassification.UNRESOLVED.name, classification)
        assertTrue(offlineLease().candidatesBefore.none {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
    }

    @Given("the lexical scan is completeness-and-veto evidence only and never selects an edit")
    fun lexicalScanNeverSelectsAnEdit() {
        val preview = assertNotNull(offlineAuthoritySetupPreview)
        assertTrue(preview.plan.warnings.any {
            it.contains("completeness-and-veto") && it.contains("no lexical range selected an edit")
        }, preview.plan.warnings.toString())
    }

    @Given("every Java edit is selected solely by an exact {string} binding equal to the selected declaration binding")
    fun everyJavaEditIsBindingDerived(classification: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_TARGET.name, classification)
        val preview = assertNotNull(offlineAuthoritySetupPreview)
        assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
        val observerEdits = preview.plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
            .filterNot { it.path == Path.of(PRODUCT_SOURCE_PATH) }
        assertEquals(EXPECTED_OBSERVER_PATHS, observerEdits.mapTo(linkedSetOf(), FileEdit.Modify::path))
        assertTrue(observerEdits.flatMap(FileEdit.Modify::textEdits).all { edit ->
            edit.newText.isEmpty() || edit.newText.contains("com.acme.catalog.api.Product")
        })
    }

    @Given("every unrelated retained baseline diagnostic has exact unchanged before\\/staged identity and positive non-concealment proof, including:")
    fun retainedDiagnosticsHaveExactIdentityAndNonConcealment(table: DataTable) {
        val row = table.asMaps().single()
        assertEquals(PRICING_SOURCE_SET, row.getValue("affected source set"))
        assertEquals("com.acme.fixture.external.PriceAuthority", row.getValue("missing external type"))
        val lease = offlineLease()
        assertEquals(lease.retainedDiagnosticsBefore, lease.retainedDiagnosticsStaged)
        assertTrue(lease.retainedDiagnosticsBefore.isNotEmpty())
        assertTrue(lease.retainedDiagnosticsBefore.all { diagnostic ->
            diagnostic.path == Path.of(PRICING_SOURCE_PATH) &&
                diagnostic.missingExternalType == "com.acme.fixture.external.PriceAuthority" &&
                diagnostic.providerConfigurationHash.length == 64 &&
                diagnostic.positiveNonConcealmentReason.contains("intersects no lexical Product candidate") &&
                diagnostic.positiveNonConcealmentReason.contains("exact non-recovered binding")
        })
    }

    @When("the move is previewed twice from identical snapshot, candidate-inventory, and negative-presence evidence hashes")
    fun moveIsPreviewedTwiceFromIdenticalAuthorityEvidence() {
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        val planner = JavaMoveClassPlanner(JavaLanguageAdapter())
        offlineAuthorityPreviews = listOf(
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE),
        )
        approvedOfflinePreview = offlineAuthorityPreviews.last()
    }

    @Then("each result is a {string} with evidence {string} and managed-write eligibility {string}")
    fun eachOfflineResultIsEligibleSemanticPreview(resultType: String, evidence: String, eligibility: String) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals("JDT_BINDING", evidence)
        assertEquals("ELIGIBLE", eligibility)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        assertEquals(2, offlineAuthorityPreviews.size)
        offlineAuthorityPreviews.forEach { preview ->
            assertEquals(PatchStatus.PREVIEW, preview.plan.status)
            assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
            if (!descriptorPruningVariant) assertNotNull(preview.targetAuthorityLease)
            assertTrue(PatchEngine(workspaceRoot).validate(preview.plan, snapshot.hash).none {
                it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR
            })
        }
    }

    @Then("each result continues to report global external classpath status {string}")
    fun eachResultContinuesToReportGlobalOfflineMissing(status: String) {
        assertEquals(BuildModelStatus.OFFLINE_MISSING.name, status)
        assertEquals(BuildModelStatus.OFFLINE_MISSING, assertNotNull(offlineAuthoritySnapshot).buildModels.single().status)
        assertTrue(offlineAuthorityPreviews.all {
            it.targetAuthorityLease?.coreLease?.attributes?.get("externalClasspathStatus") == status
        })
    }

    @Then("the target identity, observer closure, candidate records, normalized edits, retained diagnostic multiset, and staged diagnostic delta are identical between previews")
    fun repeatPreviewsHaveIdenticalAuthorityEvidence() {
        val first = offlineAuthorityPreviews[0]
        val second = offlineAuthorityPreviews[1]
        val firstLease = assertNotNull(first.targetAuthorityLease)
        val secondLease = assertNotNull(second.targetAuthorityLease)
        assertEquals(firstLease, secondLease)
        assertEquals(first.plan.workspaceEdit, second.plan.workspaceEdit)
        assertEquals(first.plan.diagnosticsBefore, second.plan.diagnosticsBefore)
        assertEquals(first.plan.diagnosticsAfterPreview, second.plan.diagnosticsAfterPreview)
    }

    @Then("exact staged analysis preserves every {string} classification and introduces no new or changed errors")
    fun stagedAnalysisPreservesBoundOtherWithoutErrors(classification: String) {
        assertEquals(JavaMoveClassCandidateClassification.BOUND_OTHER.name, classification)
        val lease = offlineLeaseFromApprovedPreview()
        assertEquals(
            lease.candidatesBefore.filter { it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER },
            lease.candidatesStaged.filter { it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER },
        )
        assertEquals(lease.allDiagnosticsBefore, lease.allDiagnosticsStaged)
        val staged = WorkspaceEditSimulator.apply(
            assertNotNull(offlineAuthoritySnapshot),
            assertNotNull(approvedOfflinePreview).plan.workspaceEdit,
        )
        val attestation = lease.attest(staged)
        assertTrue(attestation.valid, attestation.blockers.toString())
    }

    @Then("exact staged analysis preserves every {string} classification and introduces no new or changed error")
    fun stagedAnalysisPreservesBoundOtherWithoutAnyError(classification: String) {
        stagedAnalysisPreservesBoundOtherWithoutErrors(classification)
    }

    @Then("{string} remains review-only and managed-write ineligible")
    fun lexicalFallbackRemainsReviewOnlyForOfflineAuthority(evidence: String) {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK.name, evidence)
        val preview = assertNotNull(approvedOfflinePreview)
        val lexicalCopy = preview.plan.copy(evidence = RefactoringEvidence.LEXICAL_FALLBACK, authorityLease = null)
        assertTrue(PatchEngine(workspaceRoot).validate(lexicalCopy, lexicalCopy.snapshotHash).any {
            it.code == "evidence.insufficient"
        })
    }

    @When("the approved preview is applied under the workspace lock")
    fun theApprovedOfflinePreviewIsAppliedUnderLock() {
        oneApprovedOfflinePreviewIsAppliedUnderLock()
    }

    @When("one approved preview is applied under the workspace lock")
    fun oneApprovedOfflinePreviewIsAppliedUnderLock() {
        val preview = assertNotNull(approvedOfflinePreview)
        val snapshot = assertNotNull(offlineAuthoritySnapshot)
        offlinePreApplyState = captureWorkspaceState(includeManagedMetadata = false)
        val diagnosticsGate = if (descriptorPruningVariant) {
            DiagnosticsGate.enabled("java-move-class-descriptor-pruning") { staged ->
                JavaLanguageAdapter().diagnostics(staged)
            }
        } else {
            val lease = offlineLeaseFromApprovedPreview()
            DiagnosticsGate.enabled("java-move-class-target-authority", lease::managedDiagnostics)
        }
        offlineApplyResult = PatchEngine(workspaceRoot).apply(
            preview.plan,
            snapshot,
            ApplyAuthorization.explicit("cucumber", authorityRequirementId),
            diagnosticsGate,
        )
        offlineTransaction = assertIs<ApplyResult.Applied>(offlineApplyResult).transaction
        offlineAfterSnapshot = scanWorkspace()
    }

    @Then("its exact staged post-image is committed in exactly one managed transaction")
    fun exactStagedPostImageIsCommittedOnce() {
        val preview = assertNotNull(approvedOfflinePreview)
        val transaction = assertNotNull(offlineTransaction)
        assertEquals(preview.plan.id, transaction.planId)
        val expectedStagedHash = if (descriptorPruningVariant) {
            WorkspaceEditSimulator.apply(assertNotNull(offlineAuthoritySnapshot), preview.plan.workspaceEdit).hash
        } else {
            offlineLeaseFromApprovedPreview().stagedSnapshotHash
        }
        assertEquals(expectedStagedHash, assertNotNull(offlineAfterSnapshot).hash)
        val records = TransactionLog(workspaceRoot.resolve(".refactorkit/transactions")).listRecords()
        assertEquals(1, records.size)
        assertEquals(transaction.id, records.single().transaction.id)
        assertEquals("APPLIED", records.single().state.name)
    }

    @Then("authoritative after diagnostics attest the committed snapshot without concealing the retained baseline diagnostic")
    fun authoritativeAfterDiagnosticsAttestCommittedSnapshot() {
        val attestation = offlineLeaseFromApprovedPreview().attest(assertNotNull(offlineAfterSnapshot))
        assertTrue(attestation.valid, attestation.blockers.toString())
        assertEquals("STAGED", attestation.phase)
        assertEquals(offlineLeaseFromApprovedPreview().retainedDiagnosticsStaged, attestation.retainedDiagnostics)
    }

    @When("that transaction is rolled back")
    fun offlineTransactionIsRolledBack() {
        val result = PatchEngine(workspaceRoot).rollback(assertNotNull(offlineTransaction))
        assertIs<ApplyResult.Applied>(result)
        offlineRollbackSnapshot = scanWorkspace()
    }

    @Then("every workspace byte, path, source-inventory entry, and snapshot hash is exactly equal to the pre-apply image")
    fun offlineRollbackRestoresExactPreApplyImage() {
        val expected = assertNotNull(offlinePreApplyState)
        val actual = captureWorkspaceState(includeManagedMetadata = false)
        assertEquals(expected.pathKinds, actual.pathKinds, "Non-engine workspace paths changed")
        assertEquals(expected.fileHashes, actual.fileHashes, "Non-engine workspace bytes changed")
        assertEquals(expected.sourceInventory, actual.sourceInventory, "Source inventory changed")
        assertEquals(expected.snapshotHash, actual.snapshotHash, "Workspace snapshot hash changed")
        assertEquals(assertNotNull(offlineAuthoritySnapshot).hash, assertNotNull(offlineRollbackSnapshot).hash)
    }

    @Then("authoritative rollback diagnostics attest the byte-exact restored snapshot")
    fun authoritativeRollbackDiagnosticsAttestRestoredSnapshot() {
        val attestation = offlineLeaseFromApprovedPreview().attest(assertNotNull(offlineRollbackSnapshot))
        assertTrue(attestation.valid, attestation.blockers.toString())
        assertEquals("BEFORE", attestation.phase)
        assertEquals(offlineLeaseFromApprovedPreview().retainedDiagnosticsBefore, attestation.retainedDiagnostics)
    }

    private fun scanWorkspace(): ProjectSnapshot = when {
        selectedDescriptorVariant -> JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
            activeMavenProfiles = setOf(selectedDescriptorProfile),
            selectedMavenDescriptorAuthority = selectedDescriptorAuthorityContext,
        ).scan(workspaceRoot)
        descriptorPruningVariant -> JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
            activeMavenProfiles = if (alternateDescriptorPathVariant) {
                setOf(AUTH_010_PROFILE, AUTH_010_ALTERNATE_PROFILE)
            } else {
                setOf(AUTH_010_PROFILE)
            },
        ).scan(workspaceRoot)
        ordinaryMissingVariant -> JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
            activeMavenProfiles = setOf(AUTH_011_PROFILE),
        ).scan(workspaceRoot)
        explicitTransitiveScopeVariant -> JavaProjectScanner(
            allowNetworkDependencyResolution = false,
            localMavenRepository = workspaceRoot.resolve(FIXTURE_REPOSITORY_PATH),
            activeMavenProfiles = setOf(AUTH_009_PROFILE),
        ).scan(workspaceRoot)
        else -> JavaProjectScanner().scan(workspaceRoot)
    }

    private fun selectorAttributes(snapshot: ProjectSnapshot): Map<String, String> = snapshot.modules
        .single { it.name == "catalog-pricing" }
        .languageSettings
        .filterKeys { it.startsWith("java.maven.selector.") }
        .toSortedMap()

    private fun selectorEvidenceHash(snapshot: ProjectSnapshot): String = sha256(
        selectorAttributes(snapshot).entries.joinToString("\u0000") { (key, value) -> "$key\u0000$value" }
            .toByteArray(Charsets.UTF_8),
    )

    private fun selectorRecords(snapshot: ProjectSnapshot): List<SelectorRecord> {
        val attributes = selectorAttributes(snapshot)
        val count = attributes["java.maven.selector.count"]?.toIntOrNull() ?: 0
        return (0 until count).map { index ->
            val prefix = "java.maven.selector.$index"
            fun required(name: String): String = assertNotNull(attributes["$prefix.$name"], "$prefix.$name")
            SelectorRecord(
                declaringPom = required("declaringPom"),
                consumer = required("consumer"),
                sourceSet = required("sourceSet"),
                projection = required("projection"),
                dependencyPath = required("dependencyPath"),
                groupId = required("groupId"),
                artifactId = required("artifactId"),
                declaredVersion = required("declaredVersion"),
                declaredScope = required("declaredScope"),
                optional = required("optional").toBooleanStrict(),
                variant = required("variant"),
                outcome = required("outcome"),
                reason = required("reason"),
                lookupBoundary = required("lookupBoundary"),
            )
        }
    }

    private fun descriptorChildSelectorRecords(snapshot: ProjectSnapshot): List<SelectorRecord> {
        val identities = descriptorChildren.map { it.groupId to it.artifactId }.toSet()
        return selectorRecords(snapshot).filter { (it.groupId to it.artifactId) in identities }
    }

    private fun dependencyBlock(pom: String, dependency: FixtureDependency): String =
        DEPENDENCY_BLOCK_PATTERN.findAll(pom).map(MatchResult::value).single { block ->
            block.contains("<groupId>${dependency.groupId}</groupId>") &&
                block.contains("<artifactId>${dependency.artifactId}</artifactId>") &&
                block.contains("<type>${dependency.type}</type>") &&
                !block.contains("<classifier>")
        }

    private fun profileBlock(pom: String, profileId: String): String =
        PROFILE_BLOCK_PATTERN.findAll(pom).map(MatchResult::value).single { block ->
            block.contains("<id>$profileId</id>")
        }

    private fun repositoryPath(groupId: String, artifactId: String, version: String): String =
        "$FIXTURE_REPOSITORY_PATH/${groupId.replace('.', '/')}/$artifactId/$version"

    private fun assertLookupProtected(
        snapshot: ProjectSnapshot,
        candidates: List<org.refactorkit.java.JavaMoveClassCandidateRecord>,
        targetFqn: String,
        declarationPath: Path,
    ) {
        val simpleName = targetFqn.substringAfterLast('.')
        val targetCandidates = candidates.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET
        }
        targetCandidates.filter { candidate ->
            candidate.path != declarationPath && candidate.lexicalText == simpleName
        }.groupBy { it.path }.forEach { (path, _) ->
            val file = snapshot.files.single { it.path == path }
            val imports = JavaLexer.extractImports(file.content)
            assertEquals(1, imports.count { !it.isStatic && it.name == targetFqn }, "$path target import")
            assertTrue(imports.none { it.name.endsWith(".*") }, "$path wildcard import")
            assertTrue(targetCandidates.any { it.path == path && it.lexicalText == targetFqn })
        }
        assertTrue(targetCandidates.none { candidate ->
            candidate.path != declarationPath &&
                candidate.lexicalText != simpleName && candidate.lexicalText != targetFqn
        })
    }

    private fun offlineLease(): JavaMoveClassTargetAuthorityLease =
        assertNotNull(assertNotNull(offlineAuthoritySetupPreview).targetAuthorityLease)

    private fun ordinaryLease(): JavaMoveClassTargetAuthorityLease = offlineLease()

    private fun ordinaryMissingRecord(): JavaMoveClassSelectedMissingBinaryRecord =
        ordinaryLease().selectedMissingBinaryRecords.single()

    private fun ordinaryLeaseFromApprovedPreview(): JavaMoveClassTargetAuthorityLease =
        offlineLeaseFromApprovedPreview()

    private fun ordinaryMissingRecordFromApprovedPreview(): JavaMoveClassSelectedMissingBinaryRecord =
        ordinaryLeaseFromApprovedPreview().selectedMissingBinaryRecords.single()

    private fun offlineLeaseFromApprovedPreview(): JavaMoveClassTargetAuthorityLease {
        val preview = assertNotNull(approvedOfflinePreview)
        return assertNotNull(preview.targetAuthorityLease, preview.plan.warnings.joinToString("\n"))
    }

    private fun prepareIsolatedFixture(prefix: String) {
        val repositoryRoot = locateRepositoryRoot()
        fixtureTemplate = repositoryRoot.resolve(FIXTURE_PATH)
        assertTrue(Files.isDirectory(fixtureTemplate), "Missing fixture template: $fixtureTemplate")
        temporaryRoot = Files.createTempDirectory(prefix)
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyRecursively(fixtureTemplate, workspaceRoot)
    }

    private fun copyFixtureVariant(sourceRelativePath: String, targetRelativePath: String) {
        val source = workspaceRoot.resolve(sourceRelativePath)
        val target = workspaceRoot.resolve(targetRelativePath)
        assertTrue(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS), "Missing fixture variant: $sourceRelativePath")
        assertTrue(target.startsWith(workspaceRoot), "Fixture variant target must remain inside the isolated workspace")
        Files.createDirectories(assertNotNull(target.parent))
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
    }

    private fun captureDescriptorAuthorityState(snapshot: ProjectSnapshot): DescriptorAuthorityState {
        val context = assertNotNull(selectedDescriptorAuthorityContext)
        val descriptorFacts = buildMap {
            context.descriptorExpectations.forEach { expectation ->
                val descriptor = workspaceRoot.resolve(expectation.path)
                val value = if (Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                    sha256(Files.readAllBytes(descriptor))
                } else {
                    "MISSING"
                }
                put("${expectation.layer.name}:${expectation.path.invariantSeparatorsPathString}", value)
            }
            put("RELOCATION_MODEL_PARSE_EVIDENCE", context.parsedDescriptorIdentityHash ?: "MISSING")
        }
        val evidenceRecords = snapshot.modules.single { it.name == "catalog-pricing" }.languageSettings
            .filterKeys { key ->
                key == "java.dependencyGraph.status" || key == "java.dependencyGraph.message" ||
                    key.contains(".missing.") || key.startsWith("java.maven.selector.")
            }
            .toSortedMap()
        val evidenceHashes = snapshot.classpathEvidence.associate { evidence ->
            "${evidence.kind}:${evidence.path.invariantSeparatorsPathString}" to evidence.fingerprint
        }.toSortedMap()
        return DescriptorAuthorityState(
            workspace = captureWorkspaceState(snapshot = snapshot),
            descriptorFacts = descriptorFacts.toSortedMap(),
            evidenceRecords = evidenceRecords,
            evidenceHashes = evidenceHashes,
        )
    }

    private fun introduceMissingTypeWarning(relativePath: String, typeName: String, fieldName: String) {
        val sourcePath = workspaceRoot.resolve(relativePath)
        val source = Files.readString(sourcePath)
        assertTrue(source.endsWith("}\n"), "$relativePath must end with one class-closing brace")
        Files.writeString(sourcePath, source.removeSuffix("}\n") + "    private $typeName $fieldName;\n}\n")
    }

    private fun warningSourceSet(snapshot: ProjectSnapshot, path: Path): String? {
        val ownerships = snapshot.owningBuildSourceRoots(path)
        return ownerships.singleOrNull()
            ?.takeIf { it.modelStatus == BuildModelStatus.AVAILABLE }
            ?.sourceSetDisplayName()
    }

    private fun org.refactorkit.core.BuildSourceRootOwnership.sourceSetDisplayName(): String =
        "${module.id}:${sourceSet.id}"

    private fun assertPomDependency(module: String, dependency: String, testOnly: Boolean = false) {
        val pom = Files.readString(workspaceRoot.resolve(module).resolve("pom.xml"))
        assertTrue(pom.contains("<artifactId>$dependency</artifactId>"), "$module must depend on $dependency")
        if (testOnly) assertTrue(pom.contains("<scope>test</scope>"), "$module dependency must be test-only")
    }

    private fun assertDefaultScannerMavenSourceRootSemantics() {
        val root = temporaryRoot.resolve("guidance-maven-source-root-semantics")
        copyRecursively(fixtureTemplate, root)
        listOf(
            DEFAULT_ACCEPTANCE_MAIN_SOURCE_ROOT,
            CONVENTIONAL_ACCEPTANCE_MAIN_KOTLIN_ROOT,
            CONVENTIONAL_ACCEPTANCE_TEST_KOTLIN_ROOT,
            ADDITIVE_ACCEPTANCE_MAIN_SOURCE_ROOT,
            ADDITIVE_ACCEPTANCE_TEST_SOURCE_ROOT,
            CUSTOM_ACCEPTANCE_MAIN_SOURCE_ROOT,
            CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT,
        ).forEach { relative -> Files.createDirectories(root.resolve(relative)) }
        addCatalogAcceptanceAdditiveSourceRoots(root)

        val additiveSnapshot = JavaProjectScanner().scan(root)
        val additiveModule = additiveSnapshot.modules.single { it.name == "catalog-acceptance" }
        listOf(
            DEFAULT_ACCEPTANCE_MAIN_SOURCE_ROOT,
            CONVENTIONAL_ACCEPTANCE_MAIN_KOTLIN_ROOT,
            ADDITIVE_ACCEPTANCE_MAIN_SOURCE_ROOT,
        ).forEach { relative -> assertTrue(Path.of(relative) in additiveModule.mainSourceRoots, relative) }
        listOf(
            DEFAULT_ACCEPTANCE_TEST_SOURCE_ROOT,
            CONVENTIONAL_ACCEPTANCE_TEST_KOTLIN_ROOT,
            ADDITIVE_ACCEPTANCE_TEST_SOURCE_ROOT,
        ).forEach { relative -> assertTrue(Path.of(relative) in additiveModule.testSourceRoots, relative) }
        assertTrue(additiveSnapshot.files.any { it.path == Path.of(PRODUCT_STEPS_PATH) })

        addInheritedPrimarySourceRootOverrides(root)
        val replacementSnapshot = JavaProjectScanner().scan(root)
        val replacementModule = replacementSnapshot.modules.single { it.name == "catalog-acceptance" }
        assertTrue(Path.of(DEFAULT_ACCEPTANCE_MAIN_SOURCE_ROOT) !in replacementModule.mainSourceRoots)
        assertTrue(Path.of(CUSTOM_ACCEPTANCE_MAIN_SOURCE_ROOT) in replacementModule.mainSourceRoots)
        assertTrue(Path.of(CONVENTIONAL_ACCEPTANCE_MAIN_KOTLIN_ROOT) in replacementModule.mainSourceRoots)
        assertTrue(Path.of(ADDITIVE_ACCEPTANCE_MAIN_SOURCE_ROOT) in replacementModule.mainSourceRoots)
        assertTrue(Path.of(DEFAULT_ACCEPTANCE_TEST_SOURCE_ROOT) !in replacementModule.testSourceRoots)
        assertTrue(Path.of(CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT) in replacementModule.testSourceRoots)
        assertTrue(Path.of(CONVENTIONAL_ACCEPTANCE_TEST_KOTLIN_ROOT) in replacementModule.testSourceRoots)
        assertTrue(Path.of(ADDITIVE_ACCEPTANCE_TEST_SOURCE_ROOT) in replacementModule.testSourceRoots)
        assertTrue(replacementSnapshot.files.none { it.path == Path.of(PRODUCT_STEPS_PATH) })
    }

    private fun assertUnsafeExpectedEvidenceIsNotLoadedAndFailsClosed() {
        listOf("oversized", "symbolic-link").forEachIndexed { index, variant ->
            val root = temporaryRoot.resolve("guidance-unsafe-expected-evidence-$index")
            copyRecursively(fixtureTemplate, root)
            val manifest = root.resolve(SOURCE_INVENTORY_EVIDENCE_PATH)
            when (variant) {
                "oversized" -> Files.write(
                    manifest,
                    ByteArray(MAX_EXPECTED_EVIDENCE_MANIFEST_BYTES + 1) { 'x'.code.toByte() },
                )
                "symbolic-link" -> {
                    val target = manifest.resolveSibling("unsafe-expected-source-inventory.properties")
                    Files.move(manifest, target)
                    Files.createSymbolicLink(manifest, target.fileName)
                }
            }
            val snapshot = JavaProjectScanner().scan(root)
            assertTrue(snapshot.auxiliaryFiles.none { it.path == Path.of(SOURCE_INVENTORY_EVIDENCE_PATH) })
            val outcome = JavaMoveClassOperationDispatcher().preview(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE)
            val plan = assertIs<JavaMoveClassOperationOutcome.Plan>(outcome).preview.plan
            assertEquals(PatchStatus.REFUSED, plan.status, "$variant expected-evidence input")
            assertEquals("java.maven.moveClass.expectedEvidence.snapshot.unbound", plan.refusalCode)
        }
    }

    private fun addCatalogAcceptanceAdditiveSourceRoots(root: Path) {
        transformMavenPom(root.resolve(CATALOG_ACCEPTANCE_POM_PATH)) { document ->
            val project = document.documentElement
            val namespace = project.namespaceURI
            assertEquals(0, project.getElementsByTagNameNS(namespace, "build").length)
            fun element(name: String, value: String? = null) =
                document.createElementNS(namespace, name).apply { value?.let { textContent = it } }
            val build = element("build")
            val plugins = element("plugins")
            val plugin = element("plugin")
            plugin.appendChild(element("groupId", "org.codehaus.mojo"))
            plugin.appendChild(element("artifactId", "build-helper-maven-plugin"))
            val executions = element("executions")
            fun appendExecution(goal: String, sourceRoot: String) {
                val execution = element("execution")
                val goals = element("goals")
                goals.appendChild(element("goal", goal))
                execution.appendChild(goals)
                val configuration = element("configuration")
                val sources = element("sources")
                sources.appendChild(element("source", sourceRoot.removePrefix("catalog-acceptance/")))
                configuration.appendChild(sources)
                execution.appendChild(configuration)
                executions.appendChild(execution)
            }
            appendExecution("add-source", ADDITIVE_ACCEPTANCE_MAIN_SOURCE_ROOT)
            appendExecution("add-test-source", ADDITIVE_ACCEPTANCE_TEST_SOURCE_ROOT)
            plugin.appendChild(executions)
            plugins.appendChild(plugin)
            build.appendChild(plugins)
            project.appendChild(build)
        }
    }

    private fun addInheritedPrimarySourceRootOverrides(root: Path) {
        transformMavenPom(root.resolve("pom.xml")) { document ->
            val project = document.documentElement
            val namespace = project.namespaceURI
            assertEquals(0, project.getElementsByTagNameNS(namespace, "build").length)
            val build = document.createElementNS(namespace, "build")
            build.appendChild(document.createElementNS(namespace, "sourceDirectory").apply {
                textContent = CUSTOM_ACCEPTANCE_MAIN_SOURCE_ROOT.removePrefix("catalog-acceptance/")
            })
            build.appendChild(document.createElementNS(namespace, "testSourceDirectory").apply {
                textContent = CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT.removePrefix("catalog-acceptance/")
            })
            project.appendChild(build)
        }
    }

    private fun changeCatalogAcceptanceTestSourceRoot(root: Path) {
        val pom = root.resolve(CATALOG_ACCEPTANCE_POM_PATH)
        transformMavenPom(pom) { document ->
            val project = document.documentElement
            assertEquals(0, project.getElementsByTagNameNS(project.namespaceURI, "build").length)
            val build = document.createElementNS(project.namespaceURI, "build")
            val testSourceDirectory = document.createElementNS(project.namespaceURI, "testSourceDirectory")
            testSourceDirectory.textContent = CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT.removePrefix("catalog-acceptance/")
            build.appendChild(testSourceDirectory)
            project.appendChild(build)
        }
    }

    private fun addUnrelatedStaleSystemPathEvidence(root: Path) {
        val artifact = root.resolve(UNRELATED_SYSTEM_ARTIFACT_PATH)
        val manifest = root.resolve(UNRELATED_SYSTEM_ARTIFACT_EVIDENCE_PATH)
        Files.copy(root.resolve(EXTERNAL_ARTIFACT_PATH), artifact, StandardCopyOption.REPLACE_EXISTING)
        Files.write(artifact, byteArrayOf(0), StandardOpenOption.APPEND)
        Files.copy(root.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH), manifest, StandardCopyOption.REPLACE_EXISTING)
        assertTrue(JarFile(artifact.toFile(), false).use { it.entries().hasMoreElements() })
        assertFalse(EXTERNAL_ARTIFACT_SHA256 == sha256(Files.readAllBytes(artifact)))

        transformMavenPom(root.resolve(REPORTING_UNRELATED_POM_PATH)) { document ->
            val project = document.documentElement
            val namespace = project.namespaceURI
            val dependencies = project.getElementsByTagNameNS(namespace, "dependencies").item(0)
            val dependency = document.createElementNS(namespace, "dependency")
            fun append(name: String, value: String) {
                dependency.appendChild(document.createElementNS(namespace, name).apply { textContent = value })
            }
            append("groupId", "com.acme.fixture.unrelated")
            append("artifactId", "unrelated-system-contract")
            append("version", "1.0.0")
            append("scope", "system")
            append("systemPath", "\${project.basedir}/../$UNRELATED_SYSTEM_ARTIFACT_PATH")
            dependencies.appendChild(dependency)
        }
    }

    private fun transformMavenPom(pom: Path, mutation: (Document) -> Unit) {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
        val document = Files.newInputStream(pom).use(factory.newDocumentBuilder()::parse)
        mutation(document)
        val transformerFactory = TransformerFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }
        transformerFactory.newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, Charsets.UTF_8.name())
            setOutputProperty(OutputKeys.INDENT, "yes")
        }.transform(DOMSource(document), StreamResult(pom.toFile()))
    }

    private fun assertUnsupportedMoveRequestsAreRefusedBeforeGuidance() {
        val sourceDefect = guidanceCases.single { it.row.getValue("stable blocker code") == SOURCE_INVENTORY_BLOCKER }
        val generatedDefect = guidanceCases.single {
            it.row.getValue("stable blocker code") == GENERATED_INVENTORY_BLOCKER
        }
        assertPreflightRefused(sourceDefect.snapshot, PRODUCT_FQN, "com.acme.catalog.legacy", "same")
        assertPreflightRefused(sourceDefect.snapshot, PRODUCT_FQN, "bad-package", "Invalid target package")
        assertPreflightRefused(
            sourceDefect.snapshot,
            "com.acme.catalog.legacy.MissingProduct",
            PRODUCT_TARGET_PACKAGE,
            "not found or not a moveable type",
        )
        assertPreflightRefused(
            sourceDefect.snapshot,
            "$PRODUCT_FQN#<init>",
            PRODUCT_TARGET_PACKAGE,
            "not found or not a moveable type",
        )
        assertPreflightRefused(
            generatedDefect.snapshot,
            GENERATED_DECLARATION_FQN,
            "com.acme.catalog.generated.api",
            "Generated source cannot be rewritten",
        )
        assertPreflightRefused(sourceDefect.snapshot, PRODUCT_FQN, "com.acme.decoy", "already exists")
        val pathCollision = sourceDefect.snapshot.copy(
            files = sourceDefect.snapshot.files + SourceFile(
                Path.of(PRODUCT_TARGET_PATH),
                "package com.acme.catalog.api; final class Occupied {}\n",
                "java",
            ),
        )
        assertPreflightRefused(pathCollision, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE, "already exists")
    }

    private fun assertPreflightRefused(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
        summaryFragment: String,
    ) {
        val outcome = JavaMoveClassOperationDispatcher().preview(snapshot, symbolFqn, targetPackage)
        val plan = assertIs<JavaMoveClassOperationOutcome.Plan>(outcome).preview.plan
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertTrue(plan.summary.contains(summaryFragment, ignoreCase = true), plan.summary)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
    }

    private fun assertDuplicateRelevantClasspathSingletonIsRefused() {
        val root = temporaryRoot.resolve("guidance-relevant-duplicate-singleton")
        copyRecursively(fixtureTemplate, root)
        val manifest = root.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)
        artifactEvidenceOutput.appendDuplicatePacket(manifest)
        val snapshot = JavaProjectScanner().scan(root)
        val outcome = JavaMoveClassOperationDispatcher().preview(snapshot, PRODUCT_FQN, PRODUCT_TARGET_PACKAGE)
        val plan = assertIs<JavaMoveClassOperationOutcome.Plan>(outcome).preview.plan
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertEquals("java.maven.moveClass.classpathEvidence.manifest.invalid", plan.refusalCode)
    }

    private fun captureGuidanceWorkspaceState(root: Path): WorkspaceState {
        val savedRoot = workspaceRoot
        return try {
            workspaceRoot = root
            captureWorkspaceState(snapshot = JavaProjectScanner().scan(root))
        } finally {
            workspaceRoot = savedRoot
        }
    }

    private fun moveClassArguments(root: Path, apply: Boolean): List<String> = buildList {
        add("move-class")
        add("--symbol")
        add(PRODUCT_FQN)
        add("--to-package")
        add(PRODUCT_TARGET_PACKAGE)
        add(root.toString())
        add(if (apply) "--apply" else "--preview")
    }

    private fun List<JavaMoveClassReviewOnlyGuidance>.singleDistinct(): JavaMoveClassReviewOnlyGuidance {
        assertEquals(2, size)
        assertEquals(first(), last())
        return first()
    }

    private fun assertGuidanceOccurrence(case: GuidanceCase, occurrence: JavaMoveClassGuidanceOccurrence) {
        assertEquals(case.snapshot.hash, occurrence.snapshotSha256)
        assertFalse(occurrence.path.isAbsolute)
        assertFalse(occurrence.path.normalize().startsWith(".."))
        assertEquals(occurrence.path.normalize(), occurrence.path)
        assertFalse(occurrence.managedEdit)
        val absolute = case.root.resolve(occurrence.path)
        assertTrue(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS), occurrence.path.toString())
        val bytes = Files.readAllBytes(absolute)
        assertEquals(sha256(bytes), occurrence.contentSha256)
        val content = String(bytes, Charsets.UTF_8)
        val start = TextEdits.offsetOf(content, occurrence.sourceRange.start)
        val end = TextEdits.offsetOf(content, occurrence.sourceRange.end)
        assertTrue(start in 0..content.length)
        assertTrue(end in start..content.length)
        assertTrue(end > start)
        assertEquals(content.substring(start, end), occurrence.lexicalText)
    }

    private fun assertGuidanceJsonContract(case: GuidanceCase) {
        val guidance = case.guidanceResults.singleDistinct()
        val output = assertNotNull(case.cliPreview).stdout
        val root = Json.parseToJsonElement(output).jsonObject
        assertEquals(guidance.schemaVersion, root.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals(guidance.checklistVersion, root.getValue("checklistVersion").jsonPrimitive.int)
        val actualGroups = root.getValue("candidateGroups").jsonObject
        val expectedGroups = linkedMapOf<String, List<JavaMoveClassGuidanceOccurrence>>(
            "BOUND_TARGET" to guidance.candidateGroups.boundTarget,
            "BOUND_OTHER" to guidance.candidateGroups.boundOther,
            "UNRESOLVED" to guidance.candidateGroups.unresolved,
            "JAVA_NON_CODE_RESIDUAL" to guidance.candidateGroups.javaNonCodeResiduals,
            "NON_JAVA_RESIDUAL" to guidance.candidateGroups.nonJavaResiduals,
        )
        expectedGroups.forEach { (groupName, expectedOccurrences) ->
            val actualOccurrences = actualGroups.getValue(groupName).jsonArray
            assertEquals(expectedOccurrences.size, actualOccurrences.size, groupName)
            expectedOccurrences.zip(actualOccurrences).forEach { (expected, actualElement) ->
                val actual = actualElement.jsonObject
                assertEquals(expected.lexicalText, actual.getValue("lexicalText").jsonPrimitive.content)
                when (expected) {
                    is JavaMoveClassGuidanceJavaCandidate -> {
                        assertEquals(
                            expected.classification.name,
                            actual.getValue("classification").jsonPrimitive.content,
                        )
                        if (expected.classification == JavaMoveClassCandidateClassification.UNRESOLVED) {
                            assertFalse("bindingKey" in actual)
                        } else {
                            assertEquals(
                                expected.bindingKey,
                                actual.getValue("bindingKey").jsonPrimitive.content,
                            )
                        }
                        expected.recoveredRange?.let { recoveredRange ->
                            assertJsonRange(recoveredRange, actual.getValue("recoveredRange"))
                        } ?: assertFalse("recoveredRange" in actual)
                    }
                    is JavaMoveClassGuidanceResidual -> {
                        assertFalse("bindingKey" in actual)
                        assertFalse("recoveredRange" in actual)
                    }
                }
            }
        }
    }

    private fun assertJsonRange(expected: SourceRange, actualElement: JsonElement) {
        val actual = actualElement.jsonObject
        val start = actual.getValue("start").jsonObject
        val end = actual.getValue("end").jsonObject
        assertEquals(expected.start.line, start.getValue("line").jsonPrimitive.int)
        assertEquals(expected.start.character, start.getValue("character").jsonPrimitive.int)
        assertEquals(expected.end.line, end.getValue("line").jsonPrimitive.int)
        assertEquals(expected.end.character, end.getValue("character").jsonPrimitive.int)
    }

    private fun captureFilesystemState(): FilesystemState {
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
        return FilesystemState(pathKinds, fileHashes)
    }

    private fun captureWorkspaceState(
        includeManagedMetadata: Boolean = true,
        snapshot: ProjectSnapshot = scanWorkspace(),
    ): WorkspaceState {
        val pathKinds = linkedMapOf<String, String>()
        val fileHashes = linkedMapOf<String, String>()
        Files.walk(workspaceRoot).use { paths ->
            paths.sorted().forEach { path ->
                if (path == workspaceRoot) return@forEach
                val relative = workspaceRoot.relativize(path).invariantSeparatorsPathString
                if (!includeManagedMetadata &&
                    (relative == ".refactorkit" || relative.startsWith(".refactorkit/"))
                ) return@forEach
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
        val sourceInventory = snapshot.files
            .sortedBy { it.path.invariantSeparatorsPathString }
            .associate { source ->
                source.path.invariantSeparatorsPathString to SourceInventoryEntry(
                    source.languageId,
                    sha256(source.content.toByteArray(Charsets.UTF_8)),
                )
            }
        return WorkspaceState(pathKinds, fileHashes, sourceInventory, snapshot.hash)
    }

    private fun assertWorkspaceStateEqualsRecorded() {
        val expected = assertNotNull(recordedState)
        val actual = captureWorkspaceState()
        assertEquals(expected.pathKinds, actual.pathKinds, "Workspace paths changed")
        assertEquals(expected.fileHashes, actual.fileHashes, "Workspace bytes changed")
        assertEquals(expected.sourceInventory, actual.sourceInventory, "Source inventory changed")
        assertEquals(expected.snapshotHash, actual.snapshotHash, "Workspace snapshot hash changed")
    }

    private fun runCli(
        arguments: List<String>,
        cli: RefactorKitCli = RefactorKitCli(),
    ): CliResult = synchronized(CLI_OUTPUT_MONITOR) {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            val exitCode = cli.run(arguments)
            CliResult(exitCode, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) return candidate
            candidate = candidate.parent
        }
        error("Cannot locate repository root from ${Path.of("").toAbsolutePath()}")
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class GuidanceCase(
        val row: Map<String, String>,
        val root: Path,
        val snapshot: ProjectSnapshot,
        val recordedState: WorkspaceState,
        var guidanceResults: List<JavaMoveClassReviewOnlyGuidance> = emptyList(),
        var cliPreview: CliResult? = null,
        var cliApply: CliResult? = null,
    )

    private data class GuidanceBlockerRow(
        val mavenModule: String,
        val sourceSet: String,
        val blockerCode: String,
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

    private data class FilesystemState(
        val pathKinds: Map<String, String>,
        val fileHashes: Map<String, String>,
    )

    private data class DescriptorAuthorityState(
        val workspace: WorkspaceState,
        val descriptorFacts: Map<String, String>,
        val evidenceRecords: Map<String, String>,
        val evidenceHashes: Map<String, String>,
    )

    private data class DescriptorMutationCase(
        val layer: String,
        val mutation: String,
        val condition: String,
        val expectedFactLayer: MavenSelectedDescriptorLayer?,
    )

    private data class OrdinaryDriftResult(
        val description: String,
        val result: ApplyResult.Refused,
        val lockObserved: Boolean,
        val postDriftState: FilesystemState,
        val postApplyState: FilesystemState,
        val walExists: Boolean,
    )

    private data class FixtureDependency(
        val groupId: String,
        val artifactId: String,
        val type: String,
        val classifier: String,
    )

    private data class DescriptorChild(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val type: String,
        val classifier: String,
        val declaredScope: String,
        val optional: Boolean,
        val inheritedPathExclusion: String,
        val expectedReason: String,
    ) {
        fun normalizedVariant(): String = if (type == "test-jar") "jar:tests" else "jar:"
        fun artifactFileName(): String = "$artifactId-$version" +
            (if (type == "test-jar") "-tests" else "") + ".jar"
    }

    private data class SelectorRecord(
        val declaringPom: String,
        val consumer: String,
        val sourceSet: String,
        val projection: String,
        val dependencyPath: String,
        val groupId: String,
        val artifactId: String,
        val declaredVersion: String,
        val declaredScope: String,
        val optional: Boolean,
        val variant: String,
        val outcome: String,
        val reason: String,
        val lookupBoundary: String,
    )

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val PRODUCT_TARGET_PACKAGE = "com.acme.catalog.api"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        const val PRODUCT_STEPS_PATH =
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
        const val PRICING_SOURCE_PATH =
            "catalog-pricing/src/main/java/com/acme/catalog/pricing/CatalogPrice.java"
        const val STOREFRONT_SOURCE_PATH =
            "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java"
        const val REPORTING_SOURCE_PATH =
            "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java"
        const val REPORTING_UNRELATED_POM_PATH = "reporting-unrelated/pom.xml"
        const val GENERATED_DECLARATION_FQN = "com.acme.catalog.generated.GeneratedCatalogMarker"
        const val GENERATED_ROOT_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata"
        const val GENERATED_SOURCE_PATH =
            "$GENERATED_ROOT_PATH/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        const val GENERATED_INVENTORY_EVIDENCE_PATH =
            "catalog-generated-support/.refactorkit-generated-root-inventory.properties"
        const val SOURCE_INVENTORY_EVIDENCE_PATH =
            "catalog-acceptance/.refactorkit-expected-source-inventory.properties"
        const val CATALOG_ACCEPTANCE_POM_PATH = "catalog-acceptance/pom.xml"
        const val DEFAULT_ACCEPTANCE_MAIN_SOURCE_ROOT = "catalog-acceptance/src/main/java"
        const val DEFAULT_ACCEPTANCE_TEST_SOURCE_ROOT = "catalog-acceptance/src/test/java"
        const val CONVENTIONAL_ACCEPTANCE_MAIN_KOTLIN_ROOT = "catalog-acceptance/src/main/kotlin"
        const val CONVENTIONAL_ACCEPTANCE_TEST_KOTLIN_ROOT = "catalog-acceptance/src/test/kotlin"
        const val ADDITIVE_ACCEPTANCE_MAIN_SOURCE_ROOT = "catalog-acceptance/src/additional-main/java"
        const val ADDITIVE_ACCEPTANCE_TEST_SOURCE_ROOT = "catalog-acceptance/src/additional-test/java"
        const val CUSTOM_ACCEPTANCE_MAIN_SOURCE_ROOT = "catalog-acceptance/src/authority-main/java"
        const val CUSTOM_ACCEPTANCE_TEST_SOURCE_ROOT = "catalog-acceptance/src/authority-test/java"
        const val MAX_EXPECTED_EVIDENCE_MANIFEST_BYTES = 16 * 1024
        const val SOURCE_INVENTORY_BLOCKER = "java.maven.moveClass.sourceInventory.missingEntry"
        const val CLASSPATH_FINGERPRINT_BLOCKER = "java.maven.moveClass.classpathFingerprint.mismatch"
        const val RECOVERED_BINDING_BLOCKER = "java.maven.moveClass.targetUse.recoveredBinding"
        const val GENERATED_INVENTORY_BLOCKER =
            "java.maven.moveClass.materializedGeneratedRootInventory.fingerprintMismatch"
        val EXPECTED_GUIDANCE_BLOCKERS = listOf(
            GuidanceBlockerRow("catalog-acceptance", "test", SOURCE_INVENTORY_BLOCKER),
            GuidanceBlockerRow("catalog-pricing", "main", CLASSPATH_FINGERPRINT_BLOCKER),
            GuidanceBlockerRow("catalog-storefront", "main", RECOVERED_BINDING_BLOCKER),
            GuidanceBlockerRow("catalog-generated-support", "main", GENERATED_INVENTORY_BLOCKER),
        )
        val EXPECTED_VCS_CHECKLIST = listOf(
            1 to "create a VCS checkpoint",
            2 to "inspect every candidate and omission",
            3 to "restore authority or make only confirmed manual changes",
            4 to "review Java non-code and non-Java residual risks",
            5 to "run appropriate supplemental builds and tests",
            6 to "inspect the final diff",
            7 to "use VCS for recovery",
        )
        const val MAVEN_BUILD_MODEL_PROVIDER = "maven-effective-v1"
        const val TARGET_OWNER_SOURCE_SET = "catalog-model:main"
        const val PRICING_SOURCE_SET = "catalog-pricing:main"
        const val REPORTING_SOURCE_SET = "reporting-unrelated:main"
        val EXPECTED_OBSERVER_SOURCE_SETS = setOf(
            PRICING_SOURCE_SET,
            "catalog-storefront:main",
            "catalog-acceptance:test",
        )
        val EXPECTED_OBSERVER_PATHS = setOf(
            Path.of(PRICING_SOURCE_PATH),
            Path.of(STOREFRONT_SOURCE_PATH),
            Path.of(PRODUCT_STEPS_PATH),
        )
        const val AUTH_009_PROFILE = "req-java-maven-move-auth-009"
        const val AUTH_010_PROFILE = "req-java-maven-move-auth-010"
        const val AUTH_010_ALTERNATE_PROFILE = "req-java-maven-move-auth-010-alternate-path"
        const val AUTH_011_PROFILE = "req-java-maven-move-auth-011"
        const val AUTH_012_MANAGEMENT_PROFILE = "req-java-maven-move-auth-012-management"
        const val AUTH_010_ALTERNATE_COORDINATE =
            "org.springframework:spring-context-support:1.0.0-refactorkit-fixture"
        const val FIXTURE_REPOSITORY_PATH = "fixture-repository"
        const val ORDINARY_MISSING_COORDINATE = "com.acme.fixture.missing:ordinary-missing-leaf:1.0.0"
        const val ORDINARY_MISSING_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.pom"
        const val ORDINARY_MISSING_JAR =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/missing/ordinary-missing-leaf/1.0.0/ordinary-missing-leaf-1.0.0.jar"
        const val AUTH_012_VARIANT_DIRECTORY = "fixture-variants/req-java-maven-move-auth-012"
        const val AUTH_012_PARENT_BACKED_TEMPLATE =
            "$AUTH_012_VARIANT_DIRECTORY/ordinary-missing-leaf-parent-backed.pom"
        const val AUTH_012_IMPORTED_BOM_TEMPLATE =
            "$AUTH_012_VARIANT_DIRECTORY/ordinary-missing-leaf-imported-bom.pom"
        const val AUTH_012_MALFORMED_TEMPLATE =
            "$AUTH_012_VARIANT_DIRECTORY/ordinary-missing-leaf-malformed.pom"
        const val AUTH_012_DRIFTED_BOM_TEMPLATE =
            "$AUTH_012_VARIANT_DIRECTORY/ordinary-missing-bom-drifted.pom"
        const val AUTH_012_DRIFTED_MANAGEMENT_BOM_TEMPLATE =
            "$AUTH_012_VARIANT_DIRECTORY/ordinary-missing-management-bom-drifted.pom"
        const val AUTH_012_PARENT_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/authority/ordinary-missing-parent/1.0.0/ordinary-missing-parent-1.0.0.pom"
        const val AUTH_012_IMPORTED_BOM_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/authority/ordinary-missing-bom/1.0.0/ordinary-missing-bom-1.0.0.pom"
        const val AUTH_012_MANAGEMENT_BOM_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/authority/ordinary-missing-management-bom/1.0.0/ordinary-missing-management-bom-1.0.0.pom"
        val AUTH_012_STATIC_PATHS = listOf(
            "catalog-pricing/pom.xml",
            ORDINARY_MISSING_POM,
            AUTH_012_PARENT_BACKED_TEMPLATE,
            AUTH_012_IMPORTED_BOM_TEMPLATE,
            AUTH_012_MALFORMED_TEMPLATE,
            AUTH_012_DRIFTED_BOM_TEMPLATE,
            AUTH_012_DRIFTED_MANAGEMENT_BOM_TEMPLATE,
            AUTH_012_PARENT_POM,
            AUTH_012_IMPORTED_BOM_POM,
            AUTH_012_MANAGEMENT_BOM_POM,
        )
        val AUTH_012_CASES = listOf(
            DescriptorMutationCase(
                "selected leaf POM",
                "remove the deterministic selected leaf POM regular file",
                "MISSING",
                MavenSelectedDescriptorLayer.SELECTED_LEAF_POM,
            ),
            DescriptorMutationCase(
                "selected leaf POM model",
                "replace the selected leaf POM with malformed XML that cannot produce a complete raw or effective model",
                "MALFORMED",
                MavenSelectedDescriptorLayer.SELECTED_LEAF_POM,
            ),
            DescriptorMutationCase(
                "required parent POM",
                "remove the one fixture-owned parent POM referenced by the selected leaf",
                "MISSING",
                MavenSelectedDescriptorLayer.REQUIRED_PARENT_POM,
            ),
            DescriptorMutationCase(
                "required imported BOM",
                "change the one fixture-owned imported BOM after its content hash is bound",
                "DRIFTED",
                MavenSelectedDescriptorLayer.REQUIRED_IMPORTED_BOM,
            ),
            DescriptorMutationCase(
                "dependency-management/mediation declaration",
                "change the required declaration that supplies the mediated selected version after hash binding",
                "DRIFTED",
                MavenSelectedDescriptorLayer.DEPENDENCY_MANAGEMENT_MEDIATION_DECLARATION,
            ),
            DescriptorMutationCase(
                "relocation/model parse evidence",
                "remove parsed-model proof that the selected leaf has no relocation while descriptor bytes remain present",
                "MISSING",
                MavenSelectedDescriptorLayer.SELECTED_LEAF_POM,
            ),
        )
        const val AUTH_009_CHILD_VERSION = "1.0.0-refactorkit-fixture"
        const val FIELD_SCOPE_PARENT_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/external/field-scope-parent/1.0.0/field-scope-parent-1.0.0.pom"
        const val FIELD_SCOPE_PARENT_JAR =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/external/field-scope-parent/1.0.0/field-scope-parent-1.0.0.jar"
        const val FIELD_SCOPE_PARENT_SHA256 =
            "f54a2d563edf27ccd13bba1d26fcb845580cb2420e6aa3b1a4ed730bc8d038ed"
        const val OUTSIDE_CLOSURE_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/missing/outside-product-closure/1.0.0/outside-product-closure-1.0.0.pom"
        const val OUTSIDE_CLOSURE_JAR =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/missing/outside-product-closure/1.0.0/outside-product-closure-1.0.0.jar"
        val EXPECTED_AUTH_009_CHILDREN = setOf(
            FixtureDependency("ch.qos.reload4j", "reload4j", "jar", "empty"),
            FixtureDependency("org.apache.logging.log4j", "log4j-api-test", "jar", "empty"),
            FixtureDependency("org.apache.logging.log4j", "log4j-core-test", "jar", "empty"),
            FixtureDependency("org.apache.logging.log4j", "log4j-core", "jar", "empty"),
            FixtureDependency("org.projectlombok", "lombok", "jar", "empty"),
            FixtureDependency("org.springframework", "spring-context-support", "jar", "empty"),
        )
        val AUTH_009_REPOSITORY_POMS = listOf(
            FIELD_SCOPE_PARENT_POM,
            OUTSIDE_CLOSURE_POM,
        ) + EXPECTED_AUTH_009_CHILDREN.map { child ->
            val base = child.groupId.replace('.', '/') + "/${child.artifactId}/$AUTH_009_CHILD_VERSION"
            "$FIXTURE_REPOSITORY_PATH/$base/${child.artifactId}-$AUTH_009_CHILD_VERSION.pom"
        }
        const val DESCRIPTOR_PRUNING_PARENT_POM =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/external/descriptor-pruning-parent/1.0.0/descriptor-pruning-parent-1.0.0.pom"
        const val DESCRIPTOR_PRUNING_PARENT_JAR =
            "$FIXTURE_REPOSITORY_PATH/com/acme/fixture/external/descriptor-pruning-parent/1.0.0/descriptor-pruning-parent-1.0.0.jar"
        val EXPECTED_AUTH_010_CHILDREN = setOf(
            DescriptorChild(
                "ch.qos.reload4j", "reload4j", AUTH_009_CHILD_VERSION, "jar", "empty",
                "test", false, "none", "SCOPE_TEST",
            ),
            DescriptorChild(
                "org.apache.logging.log4j", "log4j-api-test", AUTH_009_CHILD_VERSION, "test-jar", "tests",
                "test", false, "none", "SCOPE_TEST",
            ),
            DescriptorChild(
                "org.apache.logging.log4j", "log4j-core-test", AUTH_009_CHILD_VERSION, "test-jar", "tests",
                "provided", false, "none", "SCOPE_PROVIDED",
            ),
            DescriptorChild(
                "org.apache.logging.log4j", "log4j-core", AUTH_009_CHILD_VERSION, "jar", "empty",
                "provided", false, "none", "SCOPE_PROVIDED",
            ),
            DescriptorChild(
                "org.projectlombok", "lombok", AUTH_009_CHILD_VERSION, "jar", "empty",
                "compile", true, "none", "OPTIONAL",
            ),
            DescriptorChild(
                "org.springframework", "spring-context-support", AUTH_009_CHILD_VERSION, "jar", "empty",
                "compile", false,
                "org.springframework:spring-context-support on this path", "EXCLUSION",
            ),
        )
        val DEPENDENCY_BLOCK_PATTERN = Regex("(?s)<dependency>.*?</dependency>")
        val PROFILE_BLOCK_PATTERN = Regex("(?s)<profile>.*?</profile>")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        const val EXTERNAL_ARTIFACT_IDENTITY =
            "com.acme.fixture.external:catalog-price-contract:1.0.0"
        const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val EXTERNAL_ARTIFACT_EVIDENCE_PATH = "$EXTERNAL_ARTIFACT_PATH.refactorkit-evidence"
        const val UNRELATED_SYSTEM_ARTIFACT_PATH = "fixture-libs/unrelated-system-contract-1.0.0.jar"
        const val UNRELATED_SYSTEM_ARTIFACT_EVIDENCE_PATH =
            "$UNRELATED_SYSTEM_ARTIFACT_PATH.refactorkit-evidence"
        const val SECOND_UNRELATED_PROVIDED_TYPE = "com.acme.fixture.external.UnrelatedPriceMetadata"
        const val EXTERNAL_ARTIFACT_SHA256 =
            "7f2e71601326da5129cb90435fb5442b958137f05fd28e4fec5192227268a3a2"
        const val EXPECTED_PRODUCT_STEPS_SHA256 =
            "46f0575b61cf83bc2cf6802f5170c9e49897545688e4a52a63e6eaf5370b849d"
        val MODULE_PATTERN = Regex("""<module>\s*([^<]+)\s*</module>""")
        val PRODUCT_DECLARATION_PATTERN = Regex("""\b(?:class|interface|enum|record)\s+Product\b""")
        val PROVIDED_TYPE_PATTERN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val CLI_OUTPUT_MONITOR = Any()
    }
}

private interface Req003ArtifactEvidenceVariantOutputPort {
    fun appendProvidedType(manifest: Path, providedType: String)
    fun appendDuplicatePacket(manifest: Path)
}

/** Test infrastructure adapter for bounded REQ-003 evidence variants. */
private object NioReq003ArtifactEvidenceVariantOutputAdapter : Req003ArtifactEvidenceVariantOutputPort {
    // @TODO: replace with proper .refactorkit-evidence parser/encoder
    override fun appendProvidedType(manifest: Path, providedType: String) {
        Files.write(
            manifest,
            listOf("providedType=$providedType"),
            Charsets.UTF_8,
            StandardOpenOption.APPEND,
        )
    }

    override fun appendDuplicatePacket(manifest: Path) {
        Files.write(manifest, Files.readAllBytes(manifest), StandardOpenOption.APPEND)
    }
}

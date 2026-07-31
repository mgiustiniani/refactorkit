package org.refactorkit.cli.req008authority

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.DiagnosticDetails
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TextEdits
import org.refactorkit.java.JavaMoveClassCandidateClassification
import org.refactorkit.java.JavaMoveClassGuidanceAuthorityLayer
import org.refactorkit.java.JavaMoveClassGuidanceBlocker
import org.refactorkit.java.JavaMoveClassGuidanceCandidateBindingState
import org.refactorkit.java.JavaMoveClassGuidanceCandidateCompleteness
import org.refactorkit.java.JavaMoveClassGuidanceClosureMembership
import org.refactorkit.java.JavaMoveClassGuidanceDiagnosticChangedField
import org.refactorkit.java.JavaMoveClassGuidanceDiagnosticPhase
import org.refactorkit.java.JavaMoveClassGuidanceLookupPrerequisiteKind
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPreview
import org.refactorkit.java.JavaMoveClassReviewOnlyGuidance
import org.refactorkit.java.JavaMoveClassStructuralRefusalAuthorityLayer
import org.refactorkit.java.JavaMoveClassStructuralRefusalCondition
import org.refactorkit.java.JavaMoveClassStructuralRefusalInputKind
import org.refactorkit.java.JavaProjectScanner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaMavenMoveClassAuthorityFailureSteps {
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var caseName: String
    private lateinit var defectKind: String
    private lateinit var baselineSnapshot: ProjectSnapshot
    private lateinit var baselinePreview: JavaMoveClassOperationOutcome.Plan
    private lateinit var evaluationSnapshot: ProjectSnapshot
    private lateinit var postMutationState: WorkspaceState
    private var operationOutcome: JavaMoveClassOperationOutcome? = null
    private var applyOutcome: ApplyResult? = null
    private var observedSnapshot: ProjectSnapshot? = null
    private var cliApply: CliResult? = null

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-008")
    fun prepareFreshVerifiedFixture() {
        fixtureTemplate = locateRepositoryRoot().resolve(FIXTURE_PATH).normalize()
        temporaryRoot = Files.createTempDirectory("refactorkit-move-auth-008-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyVerifiedPermanentFixture()
        establishReq007DiskBaseline()
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-008")
    fun removeScenarioWorkspace() {
        if (this::temporaryRoot.isInitialized) deleteTree(temporaryRoot)
    }

    @Given("the declared workspace root is the permanent fixture {string}")
    fun theDeclaredWorkspaceRootIsThePermanentFixture(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertTrue(Files.isDirectory(fixtureTemplate, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(fixtureTemplate))
    }

    @Given("the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules")
    fun theFixtureIsAPluginFreeOfflineMavenReactor() {
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        val declarations = MODULE_PATTERN.findAll(rootPom).map { it.groupValues[1].trim() }.toList()
        assertEquals(20, declarations.size)
        assertEquals(20, declarations.toSet().size)
        declarations.forEach { module ->
            assertTrue(Files.isRegularFile(workspaceRoot.resolve(module).resolve("pom.xml")))
        }
        assertEquals(20, JavaProjectScanner().scan(workspaceRoot).modules.size)
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun itsActiveGraphHasRequiredTopology() {
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-acceptance/pom.xml")).contains("catalog-storefront"))
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-storefront/pom.xml")).contains("catalog-pricing"))
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml")).contains("catalog-model"))
        assertTrue(Files.isRegularFile(fixtureTemplate.resolve(EXTERNAL_ARTIFACT_PATH)))
        assertFalse(Files.exists(workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(GENERATED_SOURCE_PATH)))
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryAndAnalysisAreOfflineAndPluginFree() {
        val poms = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .map(Files::readString)
                .toList()
        }
        listOf("<build>", "<plugins>", "<pluginRepositories>", "<repositories>", "<annotationProcessorPaths>")
            .forEach { forbidden -> assertTrue(poms.none { forbidden in it }, "Fixture contains $forbidden") }
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")))
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml")))
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun theRequestIsTheCanonicalProductMove(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        assertTrue(Files.isWritable(workspaceRoot.resolve(sourcePath)))
        assertFalse(Files.exists(workspaceRoot.resolve(targetPath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, Regex("\\bclass\\s+Product\\b").findAll(Files.readString(workspaceRoot.resolve(sourcePath))).count())
    }

    @Given("a fresh isolated authority case {string} starts from every eligible target-scoped precondition of {string}")
    fun aFreshCaseStartsFromReq007(case: String, requirement: String) {
        assertEquals("REQ-JAVA-MAVEN-MOVE-AUTH-007", requirement)
        caseName = case
        establishEligibleBaseline()
    }

    @Given("no {string} directory exists before the case-specific mutation or preview")
    fun noMetadataDirectoryExists(path: String) {
        assertEquals(".refactorkit", path)
        assertFalse(Files.exists(workspaceRoot.resolve(path), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("every authority input except the isolated {string} remains readable, safely contained, completely enumerable, non-truncated, current, and hash-bound")
    fun everyOtherAuthorityInputRemainsBound(defect: String) {
        defectKind = defect
        assertTrue(defect.isNotBlank())
        baselineSnapshot.trackedFiles.forEach { source ->
            val absolute = workspaceRoot.resolve(source.path).normalize()
            assertTrue(absolute.startsWith(workspaceRoot))
            assertTrue(Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.isSymbolicLink(absolute))
        }
        assertEquals(baselineSnapshot.hash, JavaProjectScanner().scan(workspaceRoot).hash)
    }

    @Given("the bounded lexical Java scan inventories exact ranges for completeness and veto only and never selects an edit")
    fun lexicalInventoryIsVetoOnly() {
        val preview = baselinePreview.preview
        val lease = assertNotNull(preview.targetAuthorityLease)
        assertTrue(lease.candidatesBefore.isNotEmpty())
        assertTrue(lease.candidatesBefore.all { it.sourceRange.start < it.sourceRange.end })
        val boundOtherPaths = lease.candidatesBefore.filter {
            it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        }.map { it.path }.toSet()
        assertTrue(Path.of(DECOY_SOURCE_PATH) in boundOtherPaths)
        assertTrue(preview.plan.workspaceEdit.edits.none { edit -> edit.path in boundOtherPaths })
    }

    @When("the case performs this isolated mutation and evaluation: {string}")
    fun theCasePerformsItsMutationAndEvaluation(description: String) {
        assertTrue(description.isNotBlank())
        mutateCase()
        postMutationState = captureWorkspaceState(workspaceRoot)
        evaluateCase()
    }

    @Then("the phase outcome is {string} with managed-write eligibility {string}")
    fun thePhaseOutcomeHasNoManagedEligibility(expectedOutcome: String, eligibility: String) {
        assertEquals("INELIGIBLE", eligibility)
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE -> {
                assertEquals("immutable REVIEW_ONLY_GUIDANCE", expectedOutcome)
                val guidance = guidance()
                assertEquals(JavaMoveClassGuidanceCandidateCompleteness.COMPLETE, guidance.candidateCompleteness)
                assertTrue(guidance.omissions.isEmpty())
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (guidance.blockers as MutableList<JavaMoveClassGuidanceBlocker>).clear()
                }
            }
            MISSING_REACTOR_POM_CASE -> {
                assertEquals("PatchStatus.REFUSED with RefactoringEvidence.STRUCTURAL", expectedOutcome)
                val plan = refusedPlan()
                assertEquals(PatchStatus.REFUSED, plan.status)
                assertEquals(RefactoringEvidence.STRUCTURAL, plan.evidence)
                assertFalse(plan.requiresUserApproval)
            }
            EVIDENCE_DRIFT_CASE -> {
                assertEquals("typed ApplyResult.Refused after the eligible semantic preview", expectedOutcome)
                assertIs<ApplyResult.Refused>(applyOutcome)
                assertEquals(PatchStatus.PREVIEW, baselinePreview.preview.plan.status)
                assertEquals(RefactoringEvidence.JDT_BINDING, baselinePreview.preview.plan.evidence)
                assertNotNull(baselinePreview.preview.plan.authorityLease)
            }
            else -> error("Unexpected REQ-008 case: $caseName")
        }
    }

    @Then("its primary blocker is {string} with authority layer {string}")
    fun itsPrimaryBlockerHasExpectedLayer(blockerCode: String, authorityLayer: String) {
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE -> {
                val blockers = guidance().blockers
                assertEquals(1, blockers.size, "Each isolated guidance case must have one primary blocker")
                assertEquals(blockerCode, blockers.single().code)
                assertEquals(authorityLayer, blockers.single().authorityLayer?.name)
            }
            MISSING_REACTOR_POM_CASE -> {
                assertEquals("java.maven.reactorDescriptor.missing", blockerCode)
                assertEquals("STRUCTURAL_CLOSURE", authorityLayer)
                val refusal = assertNotNull(refusedPreview().structuralRefusal)
                assertEquals(blockerCode, refusal.code)
                assertEquals(JavaMoveClassStructuralRefusalAuthorityLayer.STRUCTURAL_CLOSURE, refusal.authorityLayer)
                assertEquals(blockerCode, refusedPlan().refusalCode)
                assertTrue(evaluationSnapshot.buildModels.single { it.providerId == MAVEN_PROVIDER }
                    .diagnostics.any { it.code == blockerCode && it.moduleId == "catalog-shipping" })
            }
            EVIDENCE_DRIFT_CASE -> {
                assertEquals("authorityLease.evidenceDrift", blockerCode)
                assertEquals("EVIDENCE_FRESHNESS", authorityLayer)
                val refused = assertIs<ApplyResult.Refused>(applyOutcome)
                assertEquals(blockerCode, refused.diagnostics.first().code)
                assertTrue(refused.diagnostics.drop(1).any { it.code == "snapshot.scopeChanged" })
            }
        }
    }

    @Then("its stable typed data is {string}")
    fun itsStableTypedDataMatchesContract(contract: String) {
        assertTrue(contract.isNotBlank())
        when (caseName) {
            AMBIGUOUS_CASE -> {
                val blocker = assertIs<JavaMoveClassGuidanceBlocker.UnresolvedCandidate>(primaryGuidanceBlocker())
                assertEquals("catalog-storefront", blocker.mavenModule)
                assertEquals("main", blocker.sourceSet)
                assertEquals(Path.of(STOREFRONT_SOURCE_PATH), blocker.path)
                assertEquals(JavaMoveClassGuidanceCandidateBindingState.AMBIGUOUS, blocker.bindingState)
                assertEquals(
                    listOf(PRODUCT_FQN, "com.acme.catalog.storefront.decoy.Product"),
                    blocker.competingFqns,
                )
                assertEquals(JavaMoveClassCandidateClassification.UNRESOLVED, blocker.classification)
                assertFalse(blocker.recovered)
                assertFalse(blocker.truncated)
                assertEquals(
                    guidance().candidateGroups.allOccurrences.filter { it.path == blocker.path }.size,
                    blocker.candidateRanges.size,
                )
                assertEquals(blocker.candidateRanges.distinct(), blocker.candidateRanges)
                assertEquals(
                    blocker.candidateRanges.sortedWith(
                        compareBy({ it.start.line }, { it.start.character }, { it.end.line }, { it.end.character }),
                    ),
                    blocker.candidateRanges,
                )
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (blocker.candidateRanges as MutableList<Any?>).clear()
                }
                val canonicalRange = blocker.candidateRanges.first()
                assertFailsWith<IllegalArgumentException> {
                    JavaMoveClassGuidanceBlocker.UnresolvedCandidate(
                        blocker.mavenModule,
                        blocker.sourceSet,
                        blocker.path,
                        blocker.contentSha256,
                        listOf(canonicalRange, canonicalRange),
                        JavaMoveClassGuidanceCandidateBindingState.AMBIGUOUS,
                        blocker.competingFqns,
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    JavaMoveClassGuidanceBlocker.UnresolvedCandidate(
                        blocker.mavenModule,
                        blocker.sourceSet,
                        blocker.path,
                        blocker.contentSha256,
                        listOf(canonicalRange),
                        JavaMoveClassGuidanceCandidateBindingState.AMBIGUOUS,
                        listOf(PRODUCT_FQN),
                    )
                }
                val mutableProblemRanges = arrayListOf(canonicalRange)
                val mutableProblemFqns = arrayListOf<String>()
                val problem = JavaMoveClassGuidanceBlocker.UnresolvedCandidate(
                    blocker.mavenModule,
                    blocker.sourceSet,
                    blocker.path,
                    blocker.contentSha256,
                    mutableProblemRanges,
                    JavaMoveClassGuidanceCandidateBindingState.PROBLEM,
                    mutableProblemFqns,
                )
                mutableProblemRanges.clear()
                mutableProblemFqns += PRODUCT_FQN
                assertEquals(listOf(canonicalRange), problem.candidateRanges)
                assertTrue(problem.competingFqns.isEmpty())
                assertTrue(contract.contains("candidateCompleteness=COMPLETE"))
            }
            TARGET_LOOKUP_CASE -> {
                val blocker = assertIs<JavaMoveClassGuidanceBlocker.UnresolvedTargetLookupPrerequisite>(
                    primaryGuidanceBlocker(),
                )
                assertEquals(JavaMoveClassGuidanceLookupPrerequisiteKind.STATIC_IMPORT_ON_DEMAND, blocker.prerequisiteKind)
                assertEquals(Path.of(STOREFRONT_SOURCE_PATH), blocker.path)
                assertEquals("com.acme.fixture.missing.ProductProvider", blocker.unresolvedOwner)
                assertEquals("Product", blocker.targetSimpleName)
                assertSha256(blocker.contentSha256)
                assertSha256(blocker.affectedCandidateRangeHash)
                assertTrue(guidance().candidateGroups.unresolved.isEmpty())
                assertTrue(guidance().candidateGroups.boundTarget.isNotEmpty())
                assertTrue(guidance().candidateGroups.boundOther.isNotEmpty())
            }
            OUTSIDE_CLOSURE_CASE -> {
                val blocker = assertIs<JavaMoveClassGuidanceBlocker.ExplicitOldFqnOutsideClosure>(
                    primaryGuidanceBlocker(),
                )
                assertEquals(Path.of(REPORTING_SOURCE_PATH), blocker.path)
                assertEquals(PRODUCT_FQN, blocker.fqn)
                assertEquals("reporting-unrelated:main", "${blocker.mavenModule}:${blocker.sourceSet}")
                assertEquals(JavaMoveClassGuidanceClosureMembership.OUTSIDE, blocker.closureMembership)
                assertEquals("NONE", blocker.dependencyPath)
                assertEquals(JavaMoveClassCandidateClassification.UNRESOLVED, blocker.observedClassification)
                assertSha256(blocker.closureEvidenceHash)
                assertTrue(guidance().blockers.none {
                    it.code == JavaMoveClassGuidanceBlocker.CANDIDATE_UNRESOLVED
                })
            }
            DIAGNOSTIC_DRIFT_CASE -> {
                val blocker = assertIs<JavaMoveClassGuidanceBlocker.RetainedDiagnosticIdentityDrift>(
                    primaryGuidanceBlocker(),
                )
                assertEquals(JavaMoveClassGuidanceDiagnosticPhase.BEFORE_VS_STAGED, blocker.phase)
                assertEquals(
                    listOf(
                        JavaMoveClassGuidanceDiagnosticChangedField.PROBLEM_ID,
                        JavaMoveClassGuidanceDiagnosticChangedField.MESSAGE,
                    ),
                    blocker.changedFields,
                )
                assertEquals(blocker.before.category, blocker.staged.category)
                assertEquals(blocker.before.severity, blocker.staged.severity)
                assertEquals(blocker.before.path, blocker.staged.path)
                assertEquals(blocker.before.sourceRange, blocker.staged.sourceRange)
                assertFalse(blocker.diskDrift)
                assertSha256(blocker.before.providerConfigurationHash)
                assertSha256(blocker.staged.providerConfigurationHash)
                assertSha256(blocker.beforeDiagnosticMultisetSha256)
                assertSha256(blocker.stagedDiagnosticMultisetSha256)
                assertTrue(blocker.before.problemId != blocker.staged.problemId)
                assertTrue(blocker.before.message != blocker.staged.message)
            }
            MISSING_REACTOR_POM_CASE -> {
                val preview = refusedPreview()
                val refusal = assertNotNull(preview.structuralRefusal)
                assertEquals(JavaMoveClassStructuralRefusalAuthorityLayer.STRUCTURAL_CLOSURE, refusal.authorityLayer)
                assertEquals(JavaMoveClassStructuralRefusalInputKind.ACTIVE_REACTOR_POM, refusal.inputKind)
                assertEquals("catalog-shipping", refusal.module)
                assertEquals(Path.of("pom.xml"), refusal.declaringPom)
                assertSha256(refusal.declaringPomContentSha256)
                val declaringPomContent = Files.readString(workspaceRoot.resolve(refusal.declaringPom))
                val declarationStart = TextEdits.offsetOf(declaringPomContent, refusal.moduleDeclarationRange.start)
                val declarationEnd = TextEdits.offsetOf(declaringPomContent, refusal.moduleDeclarationRange.end)
                assertEquals("catalog-shipping", declaringPomContent.substring(declarationStart, declarationEnd))
                assertEquals(Path.of("catalog-shipping/pom.xml"), refusal.expectedPath)
                assertEquals(JavaMoveClassStructuralRefusalCondition.MISSING, refusal.condition)
                assertSha256(refusal.noFollowAbsenceFactSha256)
                val plan = preview.plan
                assertTrue(plan.affectedFiles.isEmpty())
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                assertEquals(null, plan.authorityLease)
                assertFailsWith<IllegalArgumentException> {
                    preview.copy(plan = plan.copy(status = PatchStatus.PREVIEW))
                }
                assertFailsWith<IllegalArgumentException> {
                    preview.copy(plan = plan.copy(refusalCode = "java.maven.reactorDescriptor.other"))
                }
                assertNoFollowDescriptorObservationSemantics(refusal.noFollowAbsenceFactSha256)
            }
            EVIDENCE_DRIFT_CASE -> {
                assertEvidenceDriftContractReferencesCorrectEvidence(contract)
                val lease = assertNotNull(baselinePreview.preview.plan.authorityLease)
                val evidence = lease.requiredFileEvidence.single { it.path == Path.of(DECOY_SOURCE_PATH) }
                assertEquals("CANDIDATE_INVENTORY", evidence.kind)
                assertFalse(evidence.path.isAbsolute)
                assertEquals(evidence.path, evidence.path.normalize())
                assertFalse(evidence.path.startsWith(".."))
                assertSha256(evidence.expectedContentSha256)
                assertEquals("BOUND_OTHER", evidence.attributes["classifications"])
                assertEquals("false", evidence.attributes["changedSourceManaged"])
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (lease.requiredFileEvidence as MutableList<Any?>).clear()
                }
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (evidence.attributes as MutableMap<String, String>)["classification"] = "UNRESOLVED"
                }
                val diagnostic = assertIs<ApplyResult.Refused>(applyOutcome).diagnostics.first()
                assertTrue(diagnostic.message.contains("path=$DECOY_SOURCE_PATH"))
                assertTrue(diagnostic.message.contains("expectedContentSha256=${evidence.expectedContentSha256}"))
                assertTrue(diagnostic.message.contains("observedContentSha256="))
                assertTrue(diagnostic.message.contains("expectedRequiredFileEvidenceSha256=${lease.requiredFileEvidenceSha256}"))
                val fields = diagnostic.details.fields
                assertEquals("EVIDENCE_FRESHNESS", fields["authorityLayer"])
                assertEquals("CANDIDATE_INVENTORY", fields["evidenceKind"])
                assertEquals(DECOY_SOURCE_PATH, fields["path"])
                assertEquals(evidence.expectedContentSha256, fields["expectedContentSha256"])
                assertEquals(sha256(DECOY_DRIFT_SOURCE.toByteArray()), fields["observedContentSha256"])
                assertEquals(lease.requiredFileEvidenceSha256, fields["expectedRequiredFileEvidenceSha256"])
                assertSha256(fields.getValue("observedRequiredFileEvidenceSha256"))
                assertTrue(
                    fields["expectedRequiredFileEvidenceSha256"] !=
                        fields["observedRequiredFileEvidenceSha256"],
                )
                assertEquals(lease.snapshotHash, fields["previewSnapshotSha256"])
                val independentlyObservedSnapshotSha256 = assertNotNull(observedSnapshot).hash
                assertEquals(independentlyObservedSnapshotSha256, fields["observedSnapshotSha256"])
                assertTrue(independentlyObservedSnapshotSha256 != lease.snapshotHash)
                assertEquals(
                    lease.attributes.getValue("candidateInventoryHash"),
                    fields["expectedCandidateInventorySha256"],
                )
                assertEquals("false", fields["changedSourceManaged"])
                assertFalse("observedCandidateInventorySha256" in fields)
                assertEquals(fields.keys.sorted(), fields.keys.toList())
                assertFailsWith<UnsupportedOperationException> {
                    @Suppress("UNCHECKED_CAST")
                    (fields as MutableMap<String, String>)["authorityLayer"] = "MUTATED"
                }
                val mutableDetailsInput = linkedMapOf("z" to "last", "a" to "first")
                val defensiveDetails = DiagnosticDetails(mutableDetailsInput)
                mutableDetailsInput.clear()
                assertEquals(listOf("a", "z"), defensiveDetails.fields.keys.toList())
                assertEquals("first", defensiveDetails.fields["a"])
                assertEquals(diagnostic, diagnostic.copy())
                assertSha256(lease.attributes.getValue("candidateInventoryHash"))
                assertSha256(lease.attributes.getValue("candidateInventoryEvidenceHash"))
                assertFalse(baselinePreview.preview.plan.workspaceEdit.affectedFiles().contains(Path.of(DECOY_SOURCE_PATH)))
            }
        }
    }

    @Then("its request, snapshot, overlay, candidate, and evidence identities satisfy {string}")
    fun identitiesAreBound(contract: String) {
        assertTrue(contract.isNotBlank())
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE -> {
                val guidance = guidance()
                assertSha256(guidance.requestIdentitySha256)
                assertEquals(evaluationSnapshot.hash, guidance.snapshotSha256)
                assertSha256(guidance.canonicalEvidenceSha256)
                guidance.candidateGroups.allOccurrences.forEach { occurrence ->
                    assertEquals(guidance.snapshotSha256, occurrence.snapshotSha256)
                    assertSha256(occurrence.contentSha256)
                }
                if (caseName == DIAGNOSTIC_DRIFT_CASE) {
                    val overlay = assertNotNull(guidance.stagedOverlaySha256)
                    assertSha256(overlay)
                    assertTrue(overlay != guidance.snapshotSha256)
                    assertEquals(
                        overlay,
                        assertIs<JavaMoveClassGuidanceBlocker.RetainedDiagnosticIdentityDrift>(
                            primaryGuidanceBlocker(),
                        ).stagedOverlaySha256,
                    )
                } else {
                    assertEquals(null, guidance.stagedOverlaySha256)
                }
            }
            MISSING_REACTOR_POM_CASE -> {
                assertEquals(evaluationSnapshot.hash, refusedPlan().snapshotHash)
                assertNotNull(refusedPreview().structuralRefusal)
                assertSha256(evaluationSnapshot.hash)
                assertTrue(evaluationSnapshot.auxiliaryFiles.any { it.path == Path.of("pom.xml") })
            }
            EVIDENCE_DRIFT_CASE -> {
                assertEvidenceDriftContractReferencesCorrectEvidence(contract)
                val lease = assertNotNull(baselinePreview.preview.plan.authorityLease)
                assertEquals(baselineSnapshot.hash, lease.snapshotHash)
                assertSha256(lease.evidenceHash)
                assertSha256(lease.attributes.getValue("stagedSnapshotHash"))
                val observed = assertNotNull(observedSnapshot)
                assertSha256(observed.hash)
                assertTrue(observed.hash != baselineSnapshot.hash)
            }
        }
    }

    @Then("its exact timing and residue boundary is {string}")
    fun timingAndResidueMatchContract(contract: String) {
        assertTrue(contract.isNotBlank())
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE -> {
                assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
                cliApply = runCliApply()
                assertTrue(assertNotNull(cliApply).exitCode != 0)
                assertTrue(assertNotNull(cliApply).stderr.contains("guidance.nonManaged"))
                assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
            }
            MISSING_REACTOR_POM_CASE -> {
                assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
                assertTrue(refusedPlan().workspaceEdit.edits.isEmpty())

                val cliResult = runCliApply()
                cliApply = cliResult
                val cliOutput = cliResult.stdout + cliResult.stderr
                assertTrue(cliResult.exitCode != 0, "CLI apply unexpectedly succeeded:\n$cliOutput")
                assertTrue(cliResult.stdout.contains("Status: REFUSED"), cliOutput)
                assertTrue(
                    cliResult.stdout.contains("Refusal code: java.maven.reactorDescriptor.missing"),
                    cliOutput,
                )
                assertTrue(cliResult.stdout.contains("Evidence: STRUCTURAL"), cliOutput)

                assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
                assertFalse(
                    Files.exists(workspaceRoot.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS),
                )
                assertFalse(hasWriteAheadLog())
                assertFalse(
                    Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS),
                )
                assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
                assertEquals(postMutationState, captureWorkspaceState(workspaceRoot))
            }
            EVIDENCE_DRIFT_CASE -> {
                assertIs<ApplyResult.Refused>(applyOutcome)
                assertTrue(Files.isRegularFile(workspaceRoot.resolve(".refactorkit/workspace.lock")))
                assertFalse(hasWriteAheadLog())
            }
        }
    }

    @Then("its exact preserved state is {string}")
    fun preservedStateMatchesContract(contract: String) {
        assertTrue(contract.isNotBlank())
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE, MISSING_REACTOR_POM_CASE -> {
                assertEquals(postMutationState, captureWorkspaceState(workspaceRoot))
                assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
            }
            EVIDENCE_DRIFT_CASE -> {
                assertEquals(DECOY_DRIFT_SOURCE, Files.readString(workspaceRoot.resolve(DECOY_SOURCE_PATH)))
                assertFalse(hasWriteAheadLog())
                assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
                assertEquals(
                    Files.readString(fixtureTemplate.resolve(PRODUCT_SOURCE_PATH)),
                    Files.readString(workspaceRoot.resolve(PRODUCT_SOURCE_PATH)),
                )
            }
        }
    }

    @Then("repeat evaluation in a fresh isolated copy with the same canonical inputs returns the same outcome, primary blocker, authority layer, identities, and ordered typed data")
    fun repeatEvaluationIsCanonical() {
        val firstGuidance = operationOutcome as? JavaMoveClassOperationOutcome.Guidance
        val firstPreview = (operationOutcome as? JavaMoveClassOperationOutcome.Plan)?.preview
        val firstPlan = firstPreview?.plan
        val firstStructuralRefusal = firstPreview?.structuralRefusal
        val firstApplyDiagnostic = (applyOutcome as? ApplyResult.Refused)?.diagnostics?.firstOrNull()
        val firstApplyDiagnosticDetails = firstApplyDiagnostic?.details
        resetWorkspaceAtSameCanonicalRoot()
        establishEligibleBaseline()
        mutateCase()
        postMutationState = captureWorkspaceState(workspaceRoot)
        evaluateCase()
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE ->
                assertEquals(assertNotNull(firstGuidance).guidance, guidance())
            MISSING_REACTOR_POM_CASE -> {
                val before = assertNotNull(firstPlan)
                val repeated = refusedPlan()
                assertEquals(before.status, repeated.status)
                assertEquals(before.evidence, repeated.evidence)
                assertEquals(before.refusalCode, repeated.refusalCode)
                assertEquals(before.snapshotHash, repeated.snapshotHash)
                assertEquals(before.workspaceEdit, repeated.workspaceEdit)
                assertEquals(
                    assertNotNull(firstStructuralRefusal),
                    assertNotNull(refusedPreview().structuralRefusal),
                )
            }
            EVIDENCE_DRIFT_CASE -> {
                val repeated = assertIs<ApplyResult.Refused>(applyOutcome).diagnostics.first()
                val firstDiagnostic = assertNotNull(firstApplyDiagnostic)
                assertEquals(firstDiagnostic.code, repeated.code)
                assertEquals(firstDiagnostic.message, repeated.message)
                val firstDetails = assertNotNull(firstApplyDiagnosticDetails)
                val repeatedObservedSnapshotSha256 = assertNotNull(observedSnapshot).hash
                assertEquals(repeatedObservedSnapshotSha256, repeated.details["observedSnapshotSha256"])
                assertTrue(repeatedObservedSnapshotSha256 != baselineSnapshot.hash)
                assertEquals(
                    firstDetails["observedSnapshotSha256"],
                    repeated.details["observedSnapshotSha256"],
                )
                assertEquals(firstDetails, repeated.details)
            }
        }
    }

    @Then("no scanner-only lexical range is reported as {string}, selected as {string}, or represented as an edit")
    fun noScannerOnlyRangeBecomesBindingOrEdit(evidence: String, classification: String) {
        assertEquals("JDT_BINDING", evidence)
        assertEquals("BOUND_TARGET", classification)
        when (val outcome = operationOutcome) {
            is JavaMoveClassOperationOutcome.Guidance -> {
                outcome.guidance.candidateGroups.unresolved.forEach { candidate ->
                    assertEquals(null, candidate.bindingKey)
                    assertFalse(candidate.managedEdit)
                }
            }
            is JavaMoveClassOperationOutcome.Plan -> {
                assertTrue(outcome.preview.plan.workspaceEdit.edits.none { it.path == Path.of(DECOY_SOURCE_PATH) })
            }
            null -> {
                val plan = baselinePreview.preview.plan
                assertTrue(plan.workspaceEdit.edits.none { it.path == Path.of(DECOY_SOURCE_PATH) })
            }
            is JavaMoveClassOperationOutcome.LexicalReview -> error("REQ-008 must not produce lexical review")
        }
    }

    @Then("no case returns {string}, promotes lexical evidence, or converts guidance or refusal into a semantic plan")
    fun noCaseReturnsLexicalFallbackReview(type: String) {
        assertEquals("LEXICAL_FALLBACK_REVIEW", type)
        assertFalse(operationOutcome is JavaMoveClassOperationOutcome.LexicalReview)
        when (val outcome = operationOutcome) {
            is JavaMoveClassOperationOutcome.Guidance -> assertTrue(outcome.guidance.blockers.isNotEmpty())
            is JavaMoveClassOperationOutcome.Plan -> if (caseName == MISSING_REACTOR_POM_CASE) {
                assertEquals(PatchStatus.REFUSED, outcome.preview.plan.status)
                assertTrue(outcome.preview.plan.workspaceEdit.edits.isEmpty())
            }
            else -> Unit
        }
    }

    @Then("recovered-binding guidance and preview-construction fingerprint mismatch remain {string}, broad lexical fallback remains {string}, selected-path or selected-descriptor failures remain {string} and {string}, and selected JAR, POM, effective-input, repository, or deterministic-path drift remains {string}")
    fun requirementBoundariesRemainDistinct(
        req003: String,
        req004: String,
        req010: String,
        req012: String,
        req011: String,
    ) {
        assertEquals(
            listOf(
                "REQ-JAVA-MAVEN-MOVE-AUTH-003",
                "REQ-JAVA-MAVEN-MOVE-AUTH-004",
                "REQ-JAVA-MAVEN-MOVE-AUTH-010",
                "REQ-JAVA-MAVEN-MOVE-AUTH-012",
                "REQ-JAVA-MAVEN-MOVE-AUTH-011",
            ),
            listOf(req003, req004, req010, req012, req011),
        )
    }

    private fun establishEligibleBaseline() {
        baselineSnapshot = JavaProjectScanner().scan(workspaceRoot)
        assertEquals(20, baselineSnapshot.modules.size)
        assertEquals(BuildModelStatus.OFFLINE_MISSING, baselineSnapshot.buildModels.single {
            it.providerId == MAVEN_PROVIDER
        }.status)
        baselinePreview = assertIs<JavaMoveClassOperationOutcome.Plan>(
            JavaMoveClassOperationDispatcher().preview(
                baselineSnapshot,
                PRODUCT_FQN,
                TARGET_PACKAGE,
            ),
        )
        val preview = baselinePreview.preview
        assertEquals(PatchStatus.PREVIEW, preview.plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
        val lease = assertNotNull(preview.targetAuthorityLease)
        assertEquals(lease.coreLease, preview.plan.authorityLease)
        assertTrue(lease.candidatesBefore.any {
            it.path == Path.of(DECOY_SOURCE_PATH) &&
                it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER
        })
        assertTrue(lease.candidatesBefore.none {
            it.classification == JavaMoveClassCandidateClassification.UNRESOLVED
        })
    }

    private fun mutateCase() {
        operationOutcome = null
        applyOutcome = null
        observedSnapshot = null
        cliApply = null
        when (caseName) {
            AMBIGUOUS_CASE -> {
                val decoy = workspaceRoot.resolve(AMBIGUOUS_PRODUCT_PATH)
                Files.createDirectories(decoy.parent)
                Files.writeString(decoy, AMBIGUOUS_PRODUCT_SOURCE)
                replaceOnce(
                    STOREFRONT_SOURCE_PATH,
                    "import $PRODUCT_FQN;\n",
                    "import $PRODUCT_FQN;\nimport com.acme.catalog.storefront.decoy.Product;\n",
                )
            }
            TARGET_LOOKUP_CASE -> replaceOnce(
                STOREFRONT_SOURCE_PATH,
                "import $PRODUCT_FQN;\n",
                "import $PRODUCT_FQN;\nimport static com.acme.fixture.missing.ProductProvider.*;\n",
            )
            OUTSIDE_CLOSURE_CASE -> replaceOnce(
                REPORTING_SOURCE_PATH,
                "    private static final String LEGACY_TYPE_NAME = \"$PRODUCT_FQN\";",
                "    private $PRODUCT_FQN observedProduct;",
            )
            DIAGNOSTIC_DRIFT_CASE -> {
                replaceOnce(
                    "catalog-model/pom.xml",
                    "</project>\n",
                    "  <dependencies>\n" +
                        "    <dependency>\n" +
                        "      <groupId>com.acme.fixture.external</groupId>\n" +
                        "      <artifactId>catalog-price-contract</artifactId>\n" +
                        "      <version>1.0.0</version>\n" +
                        "      <scope>system</scope>\n" +
                        "      <systemPath>${'$'}{project.basedir}/../fixture-libs/catalog-price-contract-1.0.0.jar</systemPath>\n" +
                        "    </dependency>\n" +
                        "  </dependencies>\n" +
                        "</project>\n",
                )
                val destinationType = workspaceRoot.resolve(DESTINATION_PRICE_AUTHORITY_PATH)
                Files.createDirectories(destinationType.parent)
                Files.writeString(destinationType, DESTINATION_PRICE_AUTHORITY_SOURCE)
                replaceOnce(PRODUCT_SOURCE_PATH, "public final class Product", "@PriceAuthority\npublic final class Product")
            }
            MISSING_REACTOR_POM_CASE -> {
                Files.delete(workspaceRoot.resolve("catalog-shipping/pom.xml"))
                assertFalse(Files.exists(workspaceRoot.resolve("catalog-shipping/pom.xml"), LinkOption.NOFOLLOW_LINKS))
            }
            EVIDENCE_DRIFT_CASE -> Unit
            else -> error("Unknown REQ-008 case: $caseName")
        }
    }

    private fun evaluateCase() {
        when (caseName) {
            AMBIGUOUS_CASE, TARGET_LOOKUP_CASE, OUTSIDE_CLOSURE_CASE, DIAGNOSTIC_DRIFT_CASE, MISSING_REACTOR_POM_CASE -> {
                evaluationSnapshot = JavaProjectScanner().scan(workspaceRoot)
                operationOutcome = JavaMoveClassOperationDispatcher().preview(
                    evaluationSnapshot,
                    PRODUCT_FQN,
                    TARGET_PACKAGE,
                )
            }
            EVIDENCE_DRIFT_CASE -> {
                evaluationSnapshot = baselineSnapshot
                var injected = false
                val engine = PatchEngine(
                    workspaceRoot,
                    faultInjector = PatchFaultInjector { point, _, _ ->
                        if (!injected && point == PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) {
                            injected = true
                            Files.writeString(workspaceRoot.resolve(DECOY_SOURCE_PATH), DECOY_DRIFT_SOURCE)
                        }
                    },
                )
                applyOutcome = engine.apply(
                    baselinePreview.preview.plan,
                    baselineSnapshot,
                    ApplyAuthorization.explicit("cucumber", "REQ-JAVA-MAVEN-MOVE-AUTH-008"),
                    DiagnosticsGate.disabled("req-008-evidence-drift"),
                )
                assertTrue(injected, "The real pre-lease-validation hook must execute under the workspace lock")
                observedSnapshot = JavaProjectScanner().scan(workspaceRoot)
            }
        }
    }

    private fun guidance(): JavaMoveClassReviewOnlyGuidance =
        assertIs<JavaMoveClassOperationOutcome.Guidance>(operationOutcome).guidance

    private fun primaryGuidanceBlocker(): JavaMoveClassGuidanceBlocker = guidance().blockers.single()

    private fun refusedPreview(): JavaMoveClassPreview =
        assertIs<JavaMoveClassOperationOutcome.Plan>(operationOutcome).preview

    private fun refusedPlan(): PatchPlan = refusedPreview().plan

    private fun assertNoFollowDescriptorObservationSemantics(expectedOrdinaryHash: String) {
        val deterministicRoot = temporaryRoot.resolve("no-follow-deterministic")
        copyTree(fixtureTemplate, deterministicRoot)
        Files.delete(deterministicRoot.resolve("catalog-shipping/pom.xml"))
        assertEquals(
            expectedOrdinaryHash,
            assertNotNull(previewStructuralRefusal(deterministicRoot).structuralRefusal)
                .noFollowAbsenceFactSha256,
            "The no-follow fact must not depend on the absolute temporary workspace root",
        )

        val absentModuleRoot = temporaryRoot.resolve("no-follow-absent-module")
        copyTree(fixtureTemplate, absentModuleRoot)
        deleteTree(absentModuleRoot.resolve("catalog-shipping"))
        val absentModulePreview = previewStructuralRefusal(absentModuleRoot)
        assertEquals("java.maven.reactorDescriptor.missing", absentModulePreview.plan.refusalCode)
        val absentModuleFact = assertNotNull(absentModulePreview.structuralRefusal).noFollowAbsenceFactSha256
        assertTrue(
            absentModuleFact != expectedOrdinaryHash,
            "An absent module directory and an absent final POM must have distinct no-follow facts",
        )

        val repeatedAbsentModuleRoot = temporaryRoot.resolve("no-follow-absent-module-repeated")
        copyTree(fixtureTemplate, repeatedAbsentModuleRoot)
        deleteTree(repeatedAbsentModuleRoot.resolve("catalog-shipping"))
        assertEquals(
            absentModuleFact,
            assertNotNull(previewStructuralRefusal(repeatedAbsentModuleRoot).structuralRefusal)
                .noFollowAbsenceFactSha256,
            "The absent-module no-follow fact must not depend on the absolute temporary workspace root",
        )

        val symlinkComponentRoot = temporaryRoot.resolve("no-follow-symlink-component")
        copyTree(fixtureTemplate, symlinkComponentRoot)
        Files.delete(symlinkComponentRoot.resolve("catalog-shipping/pom.xml"))
        Files.move(
            symlinkComponentRoot.resolve("catalog-shipping"),
            symlinkComponentRoot.resolve("catalog-shipping-observed"),
        )
        Files.createSymbolicLink(
            symlinkComponentRoot.resolve("catalog-shipping"),
            Path.of("catalog-shipping-observed"),
        )
        assertDescriptorIsNotClassifiedMissing(symlinkComponentRoot)

        val finalSymlinkRoot = temporaryRoot.resolve("no-follow-final-symlink")
        copyTree(fixtureTemplate, finalSymlinkRoot)
        Files.delete(finalSymlinkRoot.resolve("catalog-shipping/pom.xml"))
        Files.createSymbolicLink(
            finalSymlinkRoot.resolve("catalog-shipping/pom.xml"),
            Path.of("absent-descriptor-target.xml"),
        )
        assertDescriptorIsNotClassifiedMissing(finalSymlinkRoot)

        val finalDirectoryRoot = temporaryRoot.resolve("no-follow-final-directory")
        copyTree(fixtureTemplate, finalDirectoryRoot)
        Files.delete(finalDirectoryRoot.resolve("catalog-shipping/pom.xml"))
        Files.createDirectory(finalDirectoryRoot.resolve("catalog-shipping/pom.xml"))
        assertDescriptorIsNotClassifiedMissing(finalDirectoryRoot)
    }

    private fun previewStructuralRefusal(root: Path): JavaMoveClassPreview {
        val snapshot = JavaProjectScanner().scan(root)
        return assertIs<JavaMoveClassOperationOutcome.Plan>(
            JavaMoveClassOperationDispatcher().preview(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
        ).preview
    }

    private fun assertDescriptorIsNotClassifiedMissing(root: Path) {
        val snapshot = JavaProjectScanner().scan(root)
        assertTrue(snapshot.modules.none {
            it.languageSettings["java.maven.reactorDescriptor.code"] ==
                "java.maven.reactorDescriptor.missing"
        })
        assertTrue(snapshot.buildModels.flatMap { it.diagnostics }.none {
            it.code == "java.maven.reactorDescriptor.missing"
        })
    }

    private fun runCliApply(): CliResult {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        return try {
            System.setOut(PrintStream(out, true, Charsets.UTF_8))
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            val exit = RefactorKitCli().run(listOf(
                "move-class",
                "--symbol", PRODUCT_FQN,
                "--to-package", TARGET_PACKAGE,
                "--apply",
                workspaceRoot.toString(),
            ))
            CliResult(exit, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
    }

    private fun copyVerifiedPermanentFixture() {
        val expected = captureWorkspaceState(fixtureTemplate)
        copyTree(fixtureTemplate, workspaceRoot)
        assertEquals(expected, captureWorkspaceState(workspaceRoot))
    }

    private fun establishReq007DiskBaseline() {
        val artifact = workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH)
        assertTrue(Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS))
        assertEquals(sha256(Files.readAllBytes(fixtureTemplate.resolve(EXTERNAL_ARTIFACT_PATH))), sha256(Files.readAllBytes(artifact)))
        Files.delete(artifact)
        assertFalse(Files.exists(artifact, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    private fun resetWorkspaceAtSameCanonicalRoot() {
        deleteTree(workspaceRoot)
        copyVerifiedPermanentFixture()
        establishReq007DiskBaseline()
    }

    private fun replaceOnce(relative: String, old: String, new: String) {
        val path = workspaceRoot.resolve(relative)
        val content = Files.readString(path)
        assertEquals(1, Regex(Regex.escape(old)).findAll(content).count(), "Expected one mutation site in $relative")
        Files.writeString(path, content.replace(old, new))
    }

    private fun hasWriteAheadLog(): Boolean {
        val transactions = workspaceRoot.resolve(".refactorkit/transactions")
        if (!Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) return false
        return Files.walk(transactions).use { paths -> paths.anyMatch { Files.isRegularFile(it) } }
    }

    private fun captureWorkspaceState(root: Path): WorkspaceState {
        val entries = linkedMapOf<String, String>()
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                if (path == root) return@forEach
                val relative = root.relativize(path).invariantSeparatorsPathString
                entries[relative] = when {
                    Files.isSymbolicLink(path) -> "L:${Files.readSymbolicLink(path)}"
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "D"
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> "F:${sha256(Files.readAllBytes(path))}"
                    else -> "O"
                }
            }
        }
        return WorkspaceState(entries)
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.sorted().forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                when {
                    Files.isSymbolicLink(path) -> error("Permanent fixture must not contain symbolic links: $path")
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        Files.createDirectories(requireNotNull(destination.parent))
                        Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun locateRepositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) &&
                Files.isDirectory(current.resolve("testdata/acceptance"))
            ) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate RefactorKit repository root")
    }

    private fun assertSha256(value: String) {
        assertTrue(SHA256.matches(value), "Expected lowercase SHA-256, got: $value")
    }

    private fun assertEvidenceDriftContractReferencesCorrectEvidence(contract: String) {
        val normalized = contract.lowercase().replace('-', ' ')
        assertTrue(normalized.contains("expected candidate inventory sha 256"))
        assertTrue(normalized.contains("expected and observed required file evidence sha 256"))
        assertFalse(normalized.contains("observed candidate inventory"))
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class WorkspaceState(val entries: Map<String, String>)
    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    companion object {
        private const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        private const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        private const val TARGET_PACKAGE = "com.acme.catalog.api"
        private const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        private const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        private const val STOREFRONT_SOURCE_PATH =
            "catalog-storefront/src/main/java/com/acme/catalog/storefront/ProductTile.java"
        private const val REPORTING_SOURCE_PATH =
            "reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java"
        private const val DECOY_SOURCE_PATH = "catalog-decoy/src/main/java/com/acme/decoy/Product.java"
        private const val AMBIGUOUS_PRODUCT_PATH =
            "catalog-storefront/src/main/java/com/acme/catalog/storefront/decoy/Product.java"
        private const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        private const val EXTERNAL_ARTIFACT_EVIDENCE_PATH =
            "fixture-libs/catalog-price-contract-1.0.0.jar.refactorkit-evidence"
        private const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        private const val DESTINATION_PRICE_AUTHORITY_PATH =
            "catalog-model/src/main/java/com/acme/catalog/api/PriceAuthority.java"
        private const val MAVEN_PROVIDER = "maven-effective-v1"
        private const val AMBIGUOUS_CASE = "inside-closure ambiguous target candidate"
        private const val TARGET_LOOKUP_CASE = "unresolved target-name lookup prerequisite"
        private const val OUTSIDE_CLOSURE_CASE = "old FQN outside observer closure"
        private const val DIAGNOSTIC_DRIFT_CASE = "retained diagnostic identity drift"
        private const val MISSING_REACTOR_POM_CASE = "missing active reactor module POM"
        private const val EVIDENCE_DRIFT_CASE = "non-managed candidate inventory drift"
        private val MODULE_PATTERN = Regex("<module>\\s*([^<]+)\\s*</module>")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private const val AMBIGUOUS_PRODUCT_SOURCE =
            "package com.acme.catalog.storefront.decoy;\n\npublic final class Product {}\n"
        private const val DESTINATION_PRICE_AUTHORITY_SOURCE =
            "package com.acme.catalog.api;\n\npublic final class PriceAuthority {}\n"
        private const val DECOY_DRIFT_SOURCE =
            "package com.acme.decoy;\n\nfinal class ProductDecoy {}\n"
    }
}

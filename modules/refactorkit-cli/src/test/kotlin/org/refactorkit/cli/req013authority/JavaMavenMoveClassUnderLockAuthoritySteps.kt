package org.refactorkit.cli.req013authority

import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityEvidenceCompleteness
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPreview
import org.refactorkit.java.JavaMoveClassTargetAuthorityLease
import org.refactorkit.java.JavaProjectScanner
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Direct source-built acceptance for the pre-WAL REQ-JAVA-MAVEN-MOVE-AUTH-013 matrix. */
class JavaMavenMoveClassUnderLockAuthoritySteps {
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var preview: JavaMoveClassPreview
    private lateinit var beforeNonEngineState: WorkspaceState
    private lateinit var expectedNonEngineState: WorkspaceState
    private lateinit var condition: String
    private lateinit var applyResult: ApplyResult
    private var underLockHookObserved = false
    private var stagedDiagnosticsObserved = false

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-013")
    fun prepareFreshFixture() {
        fixtureTemplate = locateRepositoryRoot().resolve(FIXTURE_PATH).normalize()
        temporaryRoot = Files.createTempDirectory("refactorkit-move-auth-013-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyTree(fixtureTemplate, workspaceRoot)
        Files.delete(workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH))
        assertFalse(Files.exists(workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH), LinkOption.NOFOLLOW_LINKS))
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-013")
    fun deleteScenarioWorkspace() {
        if (this::temporaryRoot.isInitialized) deleteTree(temporaryRoot)
    }

    @Given("the declared workspace root is the permanent fixture {string}")
    fun permanentFixture(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertTrue(Files.isDirectory(fixtureTemplate, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(fixtureTemplate))
    }

    @Given("the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules")
    fun exactReactorShape() {
        val modules = MODULE_PATTERN.findAll(Files.readString(workspaceRoot.resolve("pom.xml")))
            .map { it.groupValues[1].trim() }
            .toList()
        assertEquals(20, modules.size)
        assertEquals(20, modules.distinct().size)
        modules.forEach { assertTrue(Files.isRegularFile(workspaceRoot.resolve(it).resolve("pom.xml"))) }
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun fixtureAuthorityInputsExist() {
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-acceptance/pom.xml")).contains("catalog-storefront"))
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-storefront/pom.xml")).contains("catalog-pricing"))
        assertTrue(Files.readString(workspaceRoot.resolve("catalog-pricing/pom.xml")).contains("catalog-model"))
        assertTrue(Files.isRegularFile(fixtureTemplate.resolve(EXTERNAL_ARTIFACT_PATH)))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(EXTERNAL_ARTIFACT_EVIDENCE_PATH)))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(GENERATED_SOURCE_PATH)))
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun executionAndNetworkRemainDenied() {
        val poms = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .map(Files::readString)
                .toList()
        }
        listOf("<build>", "<plugins>", "<pluginRepositories>", "<repositories>", "<annotationProcessorPaths>")
            .forEach { forbidden -> assertTrue(poms.none { forbidden in it }) }
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn")))
        assertFalse(Files.exists(workspaceRoot.resolve("settings.xml")))
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun canonicalRequest(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        assertTrue(Files.isWritable(workspaceRoot.resolve(sourcePath)))
        assertFalse(Files.exists(workspaceRoot.resolve(targetPath), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("a fresh isolated REQ-013 case starts from an eligible candidate-total Maven move-class semantic preview")
    fun eligibleCandidateTotalPreview() {
        snapshot = JavaProjectScanner().scan(workspaceRoot)
        assertEquals(20, snapshot.modules.size)
        preview = assertIs<JavaMoveClassOperationOutcome.Plan>(
            JavaMoveClassOperationDispatcher().preview(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
        ).preview
        assertEquals(PatchStatus.PREVIEW, preview.plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, preview.plan.evidence)
        val lease = coreLease()
        assertEquals(OperationAuthorityEvidenceCompleteness.COMPLETE, lease.evidenceCompleteness)
        assertEquals(WorkspaceEditIdentity.sha256(preview.plan.workspaceEdit), lease.workspaceEditSha256)
        assertTrue(lease.requiredFileEvidence.any { it.path == Path.of(DECOY_SOURCE_PATH) })
        assertTrue(lease.requiredClasspathEvidence.any { it.path == Path.of(EXTERNAL_ARTIFACT_PATH) })
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (lease.requiredFileEvidence as MutableList<OperationAuthorityFileEvidence>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (lease.attributes as MutableMap<String, String>)["authorityMode"] = "MUTATED"
        }
        beforeNonEngineState = captureNonEngineState()
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @When("approved managed apply evaluates the isolated condition {string} while holding the workspace lock")
    fun evaluateUnderLock(isolatedCondition: String) {
        condition = isolatedCondition
        underLockHookObserved = false
        stagedDiagnosticsObserved = false
        var plan = preview.plan
        when (condition) {
            INCOMPLETE_EVIDENCE -> plan = plan.copy(authorityLease = copyLease(
                evidenceCompleteness = OperationAuthorityEvidenceCompleteness.TRUNCATED,
            ))
            MIXED_LEXICAL_EDIT -> plan = plan.copy(workspaceEdit = widenedWorkspaceEdit(plan))
            MISSING_SOURCE_EVIDENCE -> plan = plan.copy(authorityLease = copyLease(
                requiredFileEvidence = coreLease().requiredFileEvidence + OperationAuthorityFileEvidence(
                    kind = "CANDIDATE_INVENTORY",
                    path = Path.of("catalog-decoy/src/main/java/com/acme/decoy/MissingEvidence.java"),
                    expectedContentSha256 = "0".repeat(64),
                ),
            ))
            MISSING_CLASSPATH_EVIDENCE -> plan = plan.copy(authorityLease = copyLease(
                requiredClasspathEvidence = coreLease().requiredClasspathEvidence + ClasspathEvidence(
                    Path.of("fixture-libs/never-selected.jar"),
                    ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT,
                    "missing",
                ),
            ))
        }

        val injector = PatchFaultInjector { point, _, _ ->
            if (point == PatchFaultPoint.BEFORE_AUTHORITY_LEASE_VALIDATION) {
                assertFalse(underLockHookObserved, "The pre-WAL authority hook must execute once")
                underLockHookObserved = true
                assertTrue(workspaceLockIsHeld(), "Authority validation must execute under the workspace lock")
                introduceExternalCondition()
                expectedNonEngineState = captureNonEngineState()
            }
        }
        val targetLease = targetLease()
        val gate = DiagnosticsGate.enabled("java-move-class-target-authority-req-013") { candidate ->
            val diagnostics = targetLease.managedDiagnostics(candidate)
            if (condition == STAGED_DIAGNOSTIC_REGRESSION && candidate.hash == targetLease.stagedSnapshotHash) {
                stagedDiagnosticsObserved = true
                diagnostics + Diagnostic(
                    "REQ-013 controlled compiler regression observation",
                    Diagnostic.Severity.ERROR,
                    code = "java.jdt.problem.req013",
                    evidence = DiagnosticEvidence.COMPILER,
                    category = DiagnosticCategory.TYPE_RESOLUTION,
                )
            } else diagnostics
        }
        applyResult = PatchEngine(workspaceRoot, faultInjector = injector).apply(
            plan,
            snapshot,
            ApplyAuthorization.explicit("cucumber", "REQ-JAVA-MAVEN-MOVE-AUTH-013"),
            gate,
        )
    }

    @Then("managed apply is refused with primary blocker {string} before any write-ahead log")
    fun refusedBeforeWal(expectedBlocker: String) {
        assertTrue(underLockHookObserved)
        val refused = assertIs<ApplyResult.Refused>(applyResult)
        assertEquals(expectedBlocker, refused.diagnostics.first().code, refused.diagnostics.toString())
        if (condition == AFFECTED_FILE_CONFLICT) {
            assertTrue(refused.diagnostics.any { it.code == "file.preconditionChanged" })
        }
        if (condition == STAGED_DIAGNOSTIC_REGRESSION) {
            assertTrue(stagedDiagnosticsObserved, "The exact staged post-image must reach the diagnostics provider")
        } else {
            assertFalse(stagedDiagnosticsObserved)
        }
        assertFalse(hasWriteAheadLog())
    }

    @Then("the exact deliberate external condition is preserved and every other non-engine path and byte remains unchanged")
    fun preserveOnlyExternalCondition() {
        assertEquals(expectedNonEngineState, captureNonEngineState())
        if (condition in setOf(
                INCOMPLETE_EVIDENCE,
                MIXED_LEXICAL_EDIT,
                MISSING_SOURCE_EVIDENCE,
                MISSING_CLASSPATH_EVIDENCE,
                STAGED_DIAGNOSTIC_REGRESSION,
            )
        ) {
            assertEquals(beforeNonEngineState, expectedNonEngineState)
        }
        when (condition) {
            UNREADABLE_SOURCE_PATH -> assertTrue(Files.isDirectory(
                workspaceRoot.resolve(DECOY_SOURCE_PATH),
                LinkOption.NOFOLLOW_LINKS,
            ))
            DRIFTED_SOURCE_BYTES -> assertEquals(
                DECOY_DRIFT_SOURCE,
                Files.readString(workspaceRoot.resolve(DECOY_SOURCE_PATH)),
            )
            DRIFTED_CLASSPATH_EVIDENCE -> assertTrue(Files.isRegularFile(
                workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH),
                LinkOption.NOFOLLOW_LINKS,
            ))
            AFFECTED_FILE_CONFLICT -> assertTrue(
                Files.readString(workspaceRoot.resolve(PRODUCT_SOURCE_PATH)).endsWith(EXTERNAL_CONFLICT_SUFFIX),
            )
        }
    }

    @Then("no managed target edit, destination, transaction, rollback claim, recovery record, or engine residue other than the workspace lock exists")
    fun noManagedMutationOrJournal() {
        assertFalse(Files.exists(workspaceRoot.resolve(PRODUCT_TARGET_PATH), LinkOption.NOFOLLOW_LINKS))
        assertFalse(hasWriteAheadLog())
        val metadata = workspaceRoot.resolve(".refactorkit")
        assertTrue(Files.isRegularFile(metadata.resolve("workspace.lock"), LinkOption.NOFOLLOW_LINKS))
        val entries = Files.walk(metadata).use { paths ->
            paths.filter { it != metadata }
                .map { metadata.relativize(it).invariantSeparatorsPathString }
                .toList()
        }
        assertEquals(listOf("workspace.lock"), entries.sorted())
    }

    private fun introduceExternalCondition() {
        when (condition) {
            UNREADABLE_SOURCE_PATH -> {
                val path = workspaceRoot.resolve(DECOY_SOURCE_PATH)
                Files.delete(path)
                Files.createDirectory(path)
            }
            DRIFTED_SOURCE_BYTES -> Files.writeString(workspaceRoot.resolve(DECOY_SOURCE_PATH), DECOY_DRIFT_SOURCE)
            DRIFTED_CLASSPATH_EVIDENCE -> Files.write(
                workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH),
                "externally-materialized".toByteArray(),
            )
            AFFECTED_FILE_CONFLICT -> {
                val path = workspaceRoot.resolve(PRODUCT_SOURCE_PATH)
                Files.writeString(path, Files.readString(path) + EXTERNAL_CONFLICT_SUFFIX)
            }
            INCOMPLETE_EVIDENCE,
            MIXED_LEXICAL_EDIT,
            MISSING_SOURCE_EVIDENCE,
            MISSING_CLASSPATH_EVIDENCE,
            STAGED_DIAGNOSTIC_REGRESSION -> Unit
            else -> error("Unknown REQ-013 condition: $condition")
        }
    }

    private fun widenedWorkspaceEdit(plan: PatchPlan): WorkspaceEdit {
        var widened = false
        val edits = plan.workspaceEdit.edits.map { edit ->
            if (!widened && edit is FileEdit.Modify && edit.path == Path.of(PRODUCT_SOURCE_PATH)) {
                widened = true
                edit.copy(textEdits = listOf(
                    TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 0)), "// lexical widening\n"),
                ) + edit.textEdits)
            } else edit
        }
        assertTrue(widened)
        return WorkspaceEdit(edits)
    }

    private fun copyLease(
        evidenceCompleteness: OperationAuthorityEvidenceCompleteness = coreLease().evidenceCompleteness,
        workspaceEditSha256: String? = coreLease().workspaceEditSha256,
        requiredClasspathEvidence: Collection<ClasspathEvidence> = coreLease().requiredClasspathEvidence,
        requiredFileEvidence: Collection<OperationAuthorityFileEvidence> = coreLease().requiredFileEvidence,
    ): OperationAuthorityLease {
        val original = coreLease()
        return OperationAuthorityLease(
            kind = original.kind,
            operation = original.operation,
            snapshotHash = original.snapshotHash,
            evidenceHash = original.evidenceHash,
            evidenceCompleteness = evidenceCompleteness,
            workspaceEditSha256 = workspaceEditSha256,
            requiredClasspathEvidence = requiredClasspathEvidence,
            requiredFileEvidence = requiredFileEvidence,
            attributes = original.attributes,
        )
    }

    private fun coreLease(): OperationAuthorityLease = assertNotNull(preview.plan.authorityLease)

    private fun targetLease(): JavaMoveClassTargetAuthorityLease = assertNotNull(preview.targetAuthorityLease)

    private fun workspaceLockIsHeld(): Boolean {
        val lockPath = workspaceRoot.resolve(".refactorkit/workspace.lock")
        if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) return false
        return FileChannel.open(lockPath, StandardOpenOption.WRITE).use { channel ->
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
    }

    private fun hasWriteAheadLog(): Boolean {
        val transactions = workspaceRoot.resolve(".refactorkit/transactions")
        if (!Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) return false
        return Files.walk(transactions).use { paths -> paths.anyMatch { Files.isRegularFile(it) } }
    }

    private fun captureNonEngineState(): WorkspaceState {
        val entries = linkedMapOf<String, String>()
        Files.walk(workspaceRoot).use { paths ->
            paths.sorted().forEach { path ->
                if (path == workspaceRoot) return@forEach
                val relative = workspaceRoot.relativize(path).invariantSeparatorsPathString
                if (relative == ".refactorkit" || relative.startsWith(".refactorkit/")) return@forEach
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
                    Files.isSymbolicLink(path) -> error("Permanent fixture contains a symbolic link: $path")
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        Files.createDirectories(assertNotNull(destination.parent))
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class WorkspaceState(val entries: Map<String, String>)

    companion object {
        private const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        private const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        private const val TARGET_PACKAGE = "com.acme.catalog.api"
        private const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        private const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        private const val DECOY_SOURCE_PATH = "catalog-decoy/src/main/java/com/acme/decoy/Product.java"
        private const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        private const val EXTERNAL_ARTIFACT_EVIDENCE_PATH =
            "fixture-libs/catalog-price-contract-1.0.0.jar.refactorkit-evidence"
        private const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/com/acme/catalog/generated/GeneratedCatalogMarker.java"
        private const val INCOMPLETE_EVIDENCE = "incomplete semantic evidence"
        private const val MIXED_LEXICAL_EDIT = "mixed lexical edit"
        private const val MISSING_SOURCE_EVIDENCE = "missing required source evidence"
        private const val UNREADABLE_SOURCE_PATH = "unreadable required source path"
        private const val DRIFTED_SOURCE_BYTES = "drifted required source bytes"
        private const val MISSING_CLASSPATH_EVIDENCE = "missing required classpath evidence"
        private const val DRIFTED_CLASSPATH_EVIDENCE = "drifted classpath/source-root evidence"
        private const val AFFECTED_FILE_CONFLICT = "affected-file conflict"
        private const val STAGED_DIAGNOSTIC_REGRESSION = "staged diagnostic regression"
        private const val DECOY_DRIFT_SOURCE = "package com.acme.decoy;\n\nfinal class ProductDecoy {}\n"
        private const val EXTERNAL_CONFLICT_SUFFIX = "\n// external affected-file conflict\n"
        private val MODULE_PATTERN = Regex("<module>\\s*([^<]+)\\s*</module>")
    }
}

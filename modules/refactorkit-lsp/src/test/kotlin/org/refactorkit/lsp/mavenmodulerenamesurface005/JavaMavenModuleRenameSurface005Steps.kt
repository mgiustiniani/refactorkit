package org.refactorkit.lsp.mavenmodulerenamesurface005

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileAclEntryImage
import org.refactorkit.core.FileEdit
import org.refactorkit.core.FileImage
import org.refactorkit.core.JournalState
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PendingPlanStore
import org.refactorkit.core.PlanId
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TransactionId
import org.refactorkit.core.TransactionJournalRecord
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JdtJavaAnalysisCacheStatus
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.lsp.LspSession
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserDefinedFileAttributeView
import java.security.MessageDigest
import java.util.Base64
import java.util.EnumSet
import kotlin.io.path.invariantSeparatorsPathString

/** Focused Story-BDD glue for REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005 only. */
class JavaMavenModuleRenameSurface005Steps {
    private data class LiteralModify(
        val path: Path,
        val startLine: Int,
        val startCharacter: Int,
        val endLine: Int,
        val endCharacter: Int,
        val oldText: String,
        val newText: String,
    )

    private data class LiteralRename(val path: Path, val newPath: Path)

    private data class ManifestEntry(val kind: String, val contentBase64: String?)
    private data class ExactManifest(val entries: Map<String, ManifestEntry>)

    private data class DiagnosticBaseline(
        val maven: List<BuildModelDiagnostic>,
        val jdt: List<Diagnostic>,
    )

    private data class SnapshotFacts(
        val hash: String,
        val modules: List<org.refactorkit.core.Module>,
        val sources: List<SourceFile>,
        val auxiliary: List<SourceFile>,
        val buildModels: List<BuildModel>,
        val rawPoms: Map<String, String>,
    )

    private data class FilesystemFileImage(
        val path: Path,
        val content: String?,
        val contentSha256: String?,
        val posixPermissions: Set<PosixFilePermission>?,
        val lastModifiedMillis: Long?,
        val ownerName: String?,
        val groupName: String?,
        val userDefinedAttributes: Map<String, String>?,
        val aclEntries: List<FileAclEntryImage>?,
    )

    private data class IndependentFileImage(
        val path: Path,
        val content: String?,
        val contentSha256: String?,
        val posixPermissions: Set<PosixFilePermission>?,
        val lastModifiedMillis: Long?,
        val ownerName: String?,
        val groupName: String?,
        val userDefinedAttributes: Map<String, String>?,
        val aclEntries: List<FileAclEntryImage>?,
    )

    private class SessionProbe(
        val name: String,
        val root: Path,
        val session: LspSession,
    ) {
        val notifications = mutableListOf<String>()
        lateinit var s0Snapshot: ProjectSnapshot
        lateinit var snapshotBeforePreview: ProjectSnapshot
        lateinit var planId: String
        lateinit var plan: PatchPlan
        var firstFailure: JsonRpcException? = null
        var repeatFailure: JsonRpcException? = null
    }

    private enum class InternalObservation {
        RETAINED_OBJECTS,
        PREFLIGHT_BYTECODE_ORDER,
        DIRTY_REFUSAL_LIFECYCLE,
        AFFECTED_OPEN_REFUSAL_LIFECYCLE,
        SELECTOR_BYTECODE_ADOPTION,
        EXACT_GATE_ROUTE,
        GENERIC_JAVA_ADAPTER_UNUSED,
        APPLIED_SUCCESS_LIFECYCLE,
        ROLLBACK_SUCCESS_LIFECYCLE,
    }

    private lateinit var scenario: Scenario
    private lateinit var repositoryRoot: Path
    private lateinit var fixtureRoot: Path
    private lateinit var fixtureManifest: ExactManifest
    private lateinit var temporaryRoot: Path
    private lateinit var primaryRoot: Path
    private lateinit var dirtyRoot: Path
    private lateinit var affectedRoot: Path
    private lateinit var oracleRoot: Path
    private lateinit var primary: SessionProbe
    private lateinit var dirty: SessionProbe
    private lateinit var affected: SessionProbe
    private lateinit var literalWorkspaceEdit: WorkspaceEdit
    private lateinit var s0Manifest: ExactManifest
    private lateinit var s1Manifest: ExactManifest
    private lateinit var dirtyExpectedManifest: ExactManifest
    private lateinit var s0: ProjectSnapshot
    private lateinit var dirtyIndependentS0: ProjectSnapshot
    private lateinit var affectedIndependentS0: ProjectSnapshot
    private lateinit var c1: ProjectSnapshot
    private lateinit var s1: ProjectSnapshot
    private lateinit var s0Facts: SnapshotFacts
    private lateinit var s1Facts: SnapshotFacts
    private lateinit var d0: DiagnosticBaseline
    private lateinit var preImageOracle: List<IndependentFileImage>
    private lateinit var postImageOracle: List<IndependentFileImage>
    private lateinit var appliedRecord: TransactionJournalRecord
    private lateinit var rolledBackRecord: TransactionJournalRecord
    private lateinit var primaryResponse: JsonObject
    private lateinit var rollbackResponse: JsonObject
    private lateinit var transactionId: String
    private lateinit var primarySnapshotBeforeApply: ProjectSnapshot
    private lateinit var primarySnapshotAfterApply: ProjectSnapshot
    private lateinit var primaryJavaAdapter: JavaLanguageAdapter
    private lateinit var primaryJavaCacheBeforeApply: JdtJavaAnalysisCacheStatus
    private lateinit var primaryKotlinAdapter: KotlinLanguageAdapter
    private var primaryApplyFailure: Throwable? = null
    private var rollbackFailure: Throwable? = null
    private var literalOracleDeclared = false
    private var independentOraclesReady = false
    private var imageOracleFixed = false
    private var fileImageContractFixed = false
    private var journalOracleFixed = false
    private var opaqueValuePolicyFixed = false
    private var selectorGate: DiagnosticsGate? = null
    private var firstJournalInspectionSequence = -1
    private var oracleReadySequence = -1
    private var sequence = 0
    private var bytecodeEvidence: String? = null
    private val consumedObservations = linkedSetOf<InternalObservation>()

    private val literalModifies = listOf(
        LiteralModify(Path.of("catalog-model/pom.xml"), 9, 14, 9, 27, "catalog-model", "catalog-domain"),
        LiteralModify(Path.of("pom.xml"), 74, 12, 74, 25, "catalog-model", "catalog-domain"),
        LiteralModify(Path.of("catalog-pricing/pom.xml"), 13, 18, 13, 31, "catalog-model", "catalog-domain"),
    )
    private val literalRenames = listOf(
        LiteralRename(Path.of("catalog-model/pom.xml"), Path.of("catalog-domain/pom.xml")),
        LiteralRename(
            Path.of("catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java"),
        ),
    )

    @Before("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005")
    fun prepareScenario(scenario: Scenario) {
        this.scenario = scenario
        repositoryRoot = locateRepositoryRoot()
        fixtureRoot = repositoryRoot.resolve(FIXTURE_PATH).toAbsolutePath().normalize()
        check(Files.isDirectory(fixtureRoot, LinkOption.NOFOLLOW_LINKS)) { "Permanent fixture is missing" }
        fixtureManifest = captureManifest(fixtureRoot)
        temporaryRoot = Files.createTempDirectory("refactorkit-lsp-surface005-").toAbsolutePath().normalize()
    }

    @After("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005")
    fun cleanScenario() {
        try {
            if (!scenario.isFailed) {
                check(consumedObservations == InternalObservation.entries.toSet()) {
                    "fail-closed: internal observations were not terminally consumed: " +
                        "missing=${InternalObservation.entries.toSet() - consumedObservations}"
                }
            }
            check(fixtureManifest == captureManifest(fixtureRoot)) { "Permanent fixture changed" }
        } finally {
            deleteNoFollow(temporaryRoot)
        }
    }

    @Given("each case uses a fresh no-follow disposable byte copy of {string}")
    fun freshNoFollowCopies(path: String) {
        check(path == FIXTURE_PATH)
        primaryRoot = freshCopy("primary")
        dirtyRoot = freshCopy("dirty-document")
        affectedRoot = freshCopy("affected-open")
        oracleRoot = freshCopy("independent-oracle")
        s0Manifest = captureManifest(primaryRoot)
        check(s0Manifest == fixtureManifest)
        check(captureManifest(dirtyRoot) == s0Manifest)
        check(captureManifest(affectedRoot) == s0Manifest)
        check(captureManifest(oracleRoot) == s0Manifest)
    }

    @Given("the permanent fixture remains an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children")
    fun permanentFixtureShape() {
        val rootPom = Files.readString(fixtureRoot.resolve("pom.xml"), StandardCharsets.UTF_8)
        check(rootPom.contains("<packaging>pom</packaging>"))
        val modules = Regex("<module>([^<]+)</module>").findAll(rootPom).map { it.groupValues[1] }.toList()
        check(modules.size == 20 && modules.distinct().size == 20)
        modules.forEach { module ->
            val child = Files.readString(fixtureRoot.resolve(module).resolve("pom.xml"), StandardCharsets.UTF_8)
            check(!child.contains("<modules>"))
            check(Regex("<packaging>([^<]+)</packaging>").find(child)?.groupValues?.get(1) in setOf(null, "jar"))
        }
    }

    @Given("this slice reuses REQ-JAVA-MAVEN-MODULE-RENAME-001's already-qualified in-process denial contract for Maven and wrapper execution, lifecycle goals, plugins, annotation processors, settings and credential access, credential helpers, and network requests")
    fun inheritedDenialContract() {
        val requirement = Files.readString(repositoryRoot.resolve("features/java-maven-module-rename.feature"))
        check(requirement.contains("@REQ-JAVA-MAVEN-MODULE-RENAME-001"))
        check(requirement.contains("@implemented-and-validated"))
        check(requirement.contains("Maven and wrapper execution"))
    }

    @Given("the qualified request is oldModuleDir={string}, newModuleDir={string}, and caller-explicit newArtifactId={string}")
    fun qualifiedRequest(oldModuleDir: String, newModuleDir: String, newArtifactId: String) {
        check(oldModuleDir == OLD_MODULE)
        check(newModuleDir == NEW_MODULE)
        check(newArtifactId == NEW_ARTIFACT)
    }

    @Given("REQ-JAVA-MAVEN-MODULE-RENAME-001 supplies the canonical {string} PREVIEW, exact five-edit candidate {string}, immutable authority lease and auxiliary-POM evidence, baseline {string}, authoritative post-image {string}, and diagnostic multiset {string}")
    fun inheritedCanonicalEvidence(
        operation: String,
        candidate: String,
        baseline: String,
        postImage: String,
        diagnostics: String,
    ) {
        check(operation == JavaRenameMavenModulePlanner.OPERATION)
        check(candidate == "C1")
        check(baseline == "S0")
        check(postImage == "S1")
        check(diagnostics == "D0")
    }

    @Given("three version-capable API {string} LSP sessions use three pairwise-distinct normalized disposable roots named primary, dirty-document, and affected-open, each an exact fresh {string} copy, without asking an editor to apply a proposal")
    fun constructThreeUninitializedSessions(apiVersion: String, baseline: String) {
        check(apiVersion == "0.2" && RefactorKitVersion.API_VERSION == apiVersion)
        check(baseline == "S0")
        val roots = listOf(primaryRoot, dirtyRoot, affectedRoot)
        check(roots.map { it.toAbsolutePath().normalize() }.distinct().size == 3)
        primary = newProbe("primary", primaryRoot)
        dirty = newProbe("dirty-document", dirtyRoot)
        affected = newProbe("affected-open", affectedRoot)
        check(listOf(primary, dirty, affected).all { captureManifest(it.root) == s0Manifest })
    }

    @Given("before any session initialization the harness independently declares this literal ordered WorkspaceEdit from immutable fixture paths and zero-based ranges without reading a planner result, LSP response, pending-plan store, or journal record:")
    fun declareLiteralWorkspaceEdit(table: DataTable) {
        check(sessionSnapshotOrNull(primary.session) == null)
        check(sessionSnapshotOrNull(dirty.session) == null)
        check(sessionSnapshotOrNull(affected.session) == null)
        val expected = listOf(
            listOf("1", "Modify", "catalog-model/pom.xml", "9:14-9:27", "catalog-domain", null),
            listOf("2", "Modify", "pom.xml", "74:12-74:25", "catalog-domain", null),
            listOf("3", "Modify", "catalog-pricing/pom.xml", "13:18-13:31", "catalog-domain", null),
            listOf("4", "Rename", "catalog-model/pom.xml", "absent", "absent", "catalog-domain/pom.xml"),
            listOf(
                "5",
                "Rename",
                "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
                "absent",
                "absent",
                "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
            ),
        )
        check(table.cells().drop(1) == expected) { "Literal WorkspaceEdit table drifted" }
        literalWorkspaceEdit = WorkspaceEdit(
            literalModifies.map { edit ->
                FileEdit.Modify(
                    edit.path,
                    listOf(TextEdit(
                        SourceRange(
                            SourcePosition(edit.startLine, edit.startCharacter),
                            SourcePosition(edit.endLine, edit.endCharacter),
                        ),
                        edit.newText,
                    )),
                )
            } + literalRenames.map { FileEdit.Rename(it.path, it.newPath) },
        )
        literalWorkspaceEdit = WorkspaceEditSimulator.normalize(literalWorkspaceEdit)
        check(literalWorkspaceEdit.edits.size == 5)
        literalOracleDeclared = true
    }

    @Given("using only the immutable fixture bytes and that literal edit, the harness precomputes and retains these independent oracles before any LSP request:")
    fun precomputeIndependentOracles(table: DataTable) {
        check(literalOracleDeclared)
        check(table.asMaps().map { it.getValue("oracle") } == listOf("S0", "C1", "S1", "D0"))
        s0 = scan(primaryRoot)
        dirtyIndependentS0 = scan(dirtyRoot)
        affectedIndependentS0 = scan(affectedRoot)
        check(trackedContent(dirtyIndependentS0) == trackedContent(s0))
        check(trackedContent(affectedIndependentS0) == trackedContent(s0))
        c1 = WorkspaceEditSimulator.apply(s0, literalWorkspaceEdit)
        applyLiteralWorkspaceEdit(oracleRoot)
        val authoritativePost = scanIndependentPostImageAtPrimaryRoot()
        s1 = authoritativePost.first
        s1Manifest = authoritativePost.second
        check(s0.hash != c1.hash)
        check(s0.hash != s1.hash)
        check(c1.hash != s1.hash) { "Authoritative S1 must differ from simulator C1" }
        check(trackedContent(c1) == trackedContent(s1))
        s0Facts = snapshotFacts(primaryRoot, s0)
        s1Facts = snapshotFacts(oracleRoot, s1)
        d0 = diagnostics(s0)
        check(trackedContent(c1) == trackedContent(s1)) { "C1 staged bytes differ from independent S1" }
        check(diagnostics(s1) == d0) { "S1 diagnostics differ from D0" }
        val preRaw = captureFilesystemImages(primaryRoot, ORDERED_IMAGE_PATHS)
        preImageOracle = preRaw.map { it.toPreOracle() }
        postImageOracle = derivePostImageOracle(preRaw, oracleRoot)
        oracleReadySequence = ++sequence
        independentOraclesReady = true
    }

    @Given("the independent schema-version-8 image oracle fixes this exact ordered path, presence, byte-length, and SHA-256 matrix before apply:")
    fun fixLiteralImageMatrix(table: DataTable) {
        check(independentOraclesReady)
        val rows = table.asMaps()
        check(rows.map { it.getValue("order") } == (1..6).map(Int::toString))
        check(rows.map { Path.of(it.getValue("normalized path")).normalize() } == ORDERED_IMAGE_PATHS)
        val expectedMatrix = listOf(
            ImageMatrix(397, "d954a47ec44e429cbc94a4e0d5e5fc65fc006ab3335a2b5a26e45d2e7f03088e", null, null),
            ImageMatrix(2717, "f30c9b9cfd394aa431f390a4fb2ff752e37d92f984b8519476f55157c403ac4c", 2718, "1c0fe9b9ca5383628f346877afff52fd6afe00d1223ef391e736c5a3b5ceb23f"),
            ImageMatrix(3337, "88c4ffdf826165d119fe278bdc8946d75bbd76b2784c0e476cbf4f2ae06f9ecc", 3338, "10831298857e61c7f054200c046a277a29467f5d0cddc34f17c800c776fe307f"),
            ImageMatrix(null, null, 398, "e9a34b04e5d408a9f2f6444a30ed118d8dd2d88bf880e67ff297f296092ca17b"),
            ImageMatrix(94, "7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663", null, null),
            ImageMatrix(null, null, 94, "7bb9043767dbc5b61b34670812eed2c948eb6f0aca065a2cb76e28183573f663"),
        )
        expectedMatrix.indices.forEach { index ->
            val pre = preImageOracle[index]
            val post = postImageOracle[index]
            val expected = expectedMatrix[index]
            check(pre.content?.toByteArray(StandardCharsets.UTF_8)?.size == expected.preLength)
            check(pre.contentSha256 == expected.preHash)
            check(post.content?.toByteArray(StandardCharsets.UTF_8)?.size == expected.postLength)
            check(post.contentSha256 == expected.postHash)
            val rowText = rows[index].values.joinToString(" ")
            check(rowText.contains(expected.preHash ?: "null content and contentSha256"))
            check(rowText.contains(expected.postHash ?: "null content and contentSha256"))
        }
        imageOracleFixed = true
    }

    @Given("every image in that oracle independently fixes all schema-version-8 FileImage fields as follows rather than accepting values copied from the journal under test:")
    fun fixEveryFileImageField(table: DataTable) {
        check(imageOracleFixed)
        check(table.asMaps().map { it.getValue("FileImage field") } == FILE_IMAGE_FIELDS)
        (preImageOracle + postImageOracle).forEach { image ->
            check(image.contentSha256 == image.content?.toByteArray(StandardCharsets.UTF_8)?.let(::sha256))
            if (image.content == null) {
                check(image.posixPermissions == null)
                check(image.lastModifiedMillis == null)
                check(image.ownerName == null)
                check(image.groupName == null)
                check(image.userDefinedAttributes == null)
                check(image.aclEntries == null)
            }
        }
        postImageOracle.filter { it.content != null }.forEach { image ->
            check(image.lastModifiedMillis == null && image.ownerName == null && image.groupName == null)
        }
        fileImageContractFixed = true
    }

    @Given("the independent directory oracle is this exact ordered list and is retained through rollback:")
    fun fixCreatedDirectoryOracle(table: DataTable) {
        val rows = table.asMaps()
        check(rows.map { it.getValue("order") } == (1..8).map(Int::toString))
        check(rows.map { Path.of(it.getValue("normalized created directory path")).normalize() } == CREATED_DIRECTORIES)
    }

    @Given("the independent journal oracle fixes integer literal schemaVersion 8, API version {string}, operation {string}, exact correlation to the primary opaque plan ID once returned, approval kind EXPLICIT_APPLY, approval surface {string}, approval actor {string}, the literal forwardEdit, the exact image and directory oracles, preSnapshotHash {string}, postSnapshotHash {string}, null failure, APPLIED history {string}, and later ROLLED_BACK history {string}")
    fun fixJournalOracle(
        apiVersion: String,
        operation: String,
        approvalSurface: String,
        approvalActor: String,
        preSnapshot: String,
        postSnapshot: String,
        appliedHistory: String,
        rolledBackHistory: String,
    ) {
        check(fileImageContractFixed)
        check(apiVersion == "0.2")
        check(operation == JavaRenameMavenModulePlanner.OPERATION)
        check(approvalSurface == "lsp-managed-command")
        check(approvalActor == "caller")
        check(preSnapshot == "S0" && postSnapshot == "S1")
        check(appliedHistory == "PREPARED, APPLYING, APPLIED")
        check(rolledBackHistory == "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK")
        journalOracleFixed = true
    }

    @Given("generated plan IDs, the sole transaction ID, and time values are constrained only as opaque correlation tokens or valid monotonic recorded times; every other asserted oracle value is fixed before interaction and is never obtained from the PatchPlan, response, record, current-version constant, or simulator result under test")
    fun fixOpaqueValuePolicy() {
        check(journalOracleFixed)
        check(oracleReadySequence > 0)
        opaqueValuePolicyFixed = true
    }

    @Given("the dirty-document session tracks exact saved {string} content for both unaffected {string} and affected {string}")
    fun initializeSessionsAndTrackDirtyDocuments(baseline: String, unrelated: String, product: String) {
        check(baseline == "S0")
        initialize(primary)
        initialize(dirty)
        initialize(affected)
        listOf(primary, dirty, affected).forEach { probe ->
            probe.s0Snapshot = checkNotNull(sessionSnapshotOrNull(probe.session))
            val expected = when (probe) {
                primary -> s0
                dirty -> dirtyIndependentS0
                affected -> affectedIndependentS0
                else -> error("Unknown LSP session probe")
            }
            check(probe.s0Snapshot.hash == expected.hash)
            check(trackedContent(probe.s0Snapshot) == trackedContent(expected))
            probe.notifications.clear()
        }
        openDocument(dirty, Path.of(unrelated), version = 1)
        openDocument(dirty, Path.of(product), version = 1)
        check(openDocumentPaths(dirty.session) == listOf(Path.of(unrelated), Path.of(product)))
        check(sessionSnapshot(dirty.session).hash == dirtyIndependentS0.hash)
        dirty.notifications.clear()
    }

    @Given("the affected-open session tracks exact saved {string} content for affected {string} while the primary session has no open document")
    fun trackAffectedOpenDocument(baseline: String, product: String) {
        check(baseline == "S0")
        openDocument(affected, Path.of(product), version = 1)
        check(openDocumentPaths(affected.session) == listOf(Path.of(product)))
        check(openDocumentPaths(primary.session).isEmpty())
        check(sessionSnapshot(affected.session).hash == affectedIndependentS0.hash)
        affected.notifications.clear()
        primary.notifications.clear()
    }

    @Given("retained-object identity, pending-plan lookup and removal, private saved-snapshot identity, and internal preflight, scan, selector, PatchEngine, and refresh ordering or counts that lack public protocol evidence are observed only by fail-closed test-only reflection or instrumentation whose missing or unconsumed observation fails the case; no production observer seam, callback, counter, hook, or visibility widening is permitted")
    fun establishFailClosedObservationPolicy() {
        bytecodeEvidence = javapLspSession()
        val forbiddenProductionSeams = LspSession::class.java.declaredFields.map { it.name }.filter { name ->
            val lower = name.lowercase()
            (lower.contains("apply") || lower.contains("selector") || lower.contains("patch")) &&
                (lower.contains("observer") || lower.contains("hook") || lower.contains("counter"))
        }
        check(forbiddenProductionSeams.isEmpty()) { "Production observer seam exists: $forbiddenProductionSeams" }
        check(opaqueValuePolicyFixed)
    }

    @When("all three sessions invoke public LspSession.dispatch for workspace\\/executeCommand {string} with the qualified request and independently retain the canonical proposal")
    fun requestAllThreeCanonicalProposals(command: String) {
        check(command == "refactorkit.renameMavenModule")
        listOf(primary, dirty, affected).forEach { probe ->
            probe.snapshotBeforePreview = sessionSnapshot(probe.session)
            val response = executeRename(probe.session, command)
            probe.planId = response.getValue("refactorkitPlanId").jsonPrimitive.content
            probe.plan = checkNotNull(pendingStore(probe.session).lookup(PlanId(probe.planId)))
            check(response.getValue("refactorkitEditOwnership").jsonPrimitive.content == "client-managed")
            check(response.getValue("refactorkitRollbackAvailable").jsonPrimitive.content == "false")
        }
    }

    @Then("the three responses expose three nonblank pairwise-distinct opaque refactorkitPlanId values that are retained only as exact correlation tokens without parsing, reconstruction, or format assumptions")
    fun planIdsAreDistinctOpaqueTokens() {
        val ids = listOf(primary, dirty, affected).map { it.planId }
        check(ids.all(String::isNotBlank))
        check(ids.distinct().size == 3)
        check(ids.all { pendingStore(primary.session).lookup(PlanId(it)) == null || it == primary.planId })
    }

    @Then("lookup by each returned ID yields that session's same immutable canonical PatchPlan and authority lease, and every retained normalized WorkspaceEdit equals the predeclared literal five-entry oracle entry-for-entry")
    fun retainedPlansAreExactObjects() {
        listOf(primary, dirty, affected).forEach { probe ->
            val lookedUp = checkNotNull(pendingStore(probe.session).lookup(PlanId(probe.planId)))
            check(lookedUp === probe.plan)
            check(lookedUp.authorityLease === probe.plan.authorityLease)
            check(lookedUp.authorityLease != null)
            check(lookedUp.operation == JavaRenameMavenModulePlanner.OPERATION)
            check(WorkspaceEditSimulator.normalize(lookedUp.workspaceEdit) == literalWorkspaceEdit)
            check(lookedUp.snapshotHash == probe.s0Snapshot.hash)
        }
        consumedObservations += InternalObservation.RETAINED_OBJECTS
    }

    @Then("none of the three returned client-managed documentChanges is applied, so preview performs no managed write, operation-gate construction or provider invocation, PatchEngine apply, workspace lock, WAL, transaction, pending-plan removal, or state refresh")
    fun previewsAreReadOnlyAndRetained() {
        listOf(primary, dirty, affected).forEach { probe ->
            check(captureManifest(probe.root) == s0Manifest)
            assertNoEngineState(probe.root)
            check(sessionSnapshot(probe.session) === probe.snapshotBeforePreview)
            check(pendingStore(probe.session).lookup(PlanId(probe.planId)) === probe.plan)
            check(probe.notifications.isEmpty())
        }
    }

    @When("an external disk-only change without an LSP lifecycle notification replaces only the final LF byte of the dirty-document root's saved ProductReport file with one ASCII space, leaving the exact tracked {string} buffer divergent and the affected Product buffer open")
    fun stageExternalDirtyByte(baseline: String) {
        check(baseline == "S0")
        val path = dirtyRoot.resolve(REPORT_PATH)
        val bytes = Files.readAllBytes(path)
        check(bytes.last() == '\n'.code.toByte())
        bytes[bytes.lastIndex] = ' '.code.toByte()
        Files.write(path, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        dirtyExpectedManifest = withChangedFile(s0Manifest, REPORT_PATH, bytes)
        check(captureManifest(dirtyRoot) == dirtyExpectedManifest)
        check(openDocumentPaths(dirty.session).contains(PRODUCT_PATH))
        check(dirty.notifications.isEmpty())
    }

    @When("the dirty-document session invokes public LspSession.dispatch for workspace\\/executeCommand {string} with its exact opaque plan ID")
    fun dirtyApplyRefused(command: String) {
        check(command == "refactorkit.applyPlan")
        dirty.firstFailure = executeApplyExpectingFailure(dirty, command)
    }

    @Then("LSP returns DOCUMENT_VERSION_MISMATCH identifying the divergent ProductReport before evaluating or reporting the affected-open Product rule")
    fun dirtyFailureIsOrderedFirst() {
        val failure = checkNotNull(dirty.firstFailure)
        check(failure.code == JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH)
        check(failure.message.orEmpty().contains(REPORT_PATH.invariantSeparatorsPathString))
        check(!failure.message.orEmpty().contains(PRODUCT_PATH.invariantSeparatorsPathString))
        check(failure.message.orEmpty().contains("unsaved content"))
    }

    @Then("after only plan-ID parsing and lookup, the dirty-document preflight refuses before any fresh scanner call, ManagedApplyDiagnosticsGateSelector.select call, operation-gate construction, PatchEngine construction or apply, workspace lock, WAL, target edit, pending-plan removal, or state refresh")
    fun dirtyPreflightStopsBeforeManagedWork() {
        assertApplyBytecodePreflightOrder(requireSelector = false)
        consumedObservations += InternalObservation.PREFLIGHT_BYTECODE_ORDER
        assertRefusalLifecycle(dirty, dirtyExpectedManifest)
        consumedObservations += InternalObservation.DIRTY_REFUSAL_LIFECYCLE
    }

    @When("the dirty-document session repeats {string} with the same exact opaque plan ID")
    fun repeatDirtyApply(command: String) {
        check(command == "refactorkit.applyPlan")
        dirty.repeatFailure = executeApplyExpectingFailure(dirty, command)
    }

    @Then("the same ordered DOCUMENT_VERSION_MISMATCH refusal proves that exact plan remains retained, and the repeated dispatch again performs none of the forbidden post-preflight actions")
    fun repeatedDirtyFailureIsIdentical() {
        assertRepeatedFailure(dirty)
        assertRefusalLifecycle(dirty, dirtyExpectedManifest)
    }

    @When("the affected-open session invokes {string} with its exact opaque plan ID")
    fun affectedOpenApplyRefused(command: String) {
        check(command == "refactorkit.applyPlan")
        affected.firstFailure = executeApplyExpectingFailure(affected, command)
    }

    @Then("LSP returns DOCUMENT_VERSION_MISMATCH identifying the affected Product as open even though its tracked content is clean")
    fun affectedOpenFailureIsExact() {
        val failure = checkNotNull(affected.firstFailure)
        check(failure.code == JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH)
        check(failure.message.orEmpty().contains(PRODUCT_PATH.invariantSeparatorsPathString))
        check(failure.message.orEmpty().contains("open documents"))
        check(Files.readString(affectedRoot.resolve(PRODUCT_PATH)) == openDocumentContent(affected.session, PRODUCT_PATH))
    }

    @Then("after only plan-ID parsing and lookup, the affected-open preflight refuses before any fresh scanner call, ManagedApplyDiagnosticsGateSelector.select call, operation-gate construction, PatchEngine construction or apply, workspace lock, WAL, target edit, pending-plan removal, or state refresh")
    fun affectedOpenStopsBeforeManagedWork() {
        assertApplyBytecodePreflightOrder(requireSelector = false)
        assertRefusalLifecycle(affected, s0Manifest)
        consumedObservations += InternalObservation.AFFECTED_OPEN_REFUSAL_LIFECYCLE
    }

    @When("the affected-open session repeats {string} with the same exact opaque plan ID")
    fun repeatAffectedOpenApply(command: String) {
        check(command == "refactorkit.applyPlan")
        affected.repeatFailure = executeApplyExpectingFailure(affected, command)
    }

    @Then("the same affected-open DOCUMENT_VERSION_MISMATCH refusal proves that exact plan remains retained, and the repeated dispatch again performs none of the forbidden post-preflight actions")
    fun repeatedAffectedOpenFailureIsIdentical() {
        assertRepeatedFailure(affected)
        assertRefusalLifecycle(affected, s0Manifest)
    }

    @Then("the dirty-document root differs from {string} only by its declared final-byte disk change, the affected-open and primary roots remain exact {string}, all three journals remain empty, and neither a retained plan ID nor any client-managed proposal is approval, validation, or managed-apply evidence")
    fun refusalRootsRemainBounded(baselineOne: String, baselineTwo: String) {
        check(baselineOne == "S0" && baselineTwo == "S0")
        check(captureManifest(dirtyRoot) == dirtyExpectedManifest)
        check(captureManifest(affectedRoot) == s0Manifest)
        check(captureManifest(primaryRoot) == s0Manifest)
        listOf(primaryRoot, dirtyRoot, affectedRoot).forEach(::assertNoEngineState)
        listOf(primary, dirty, affected).forEach { probe ->
            check(pendingStore(probe.session).lookup(PlanId(probe.planId)) === probe.plan)
        }
    }

    @When("the clean and closed primary session invokes public LspSession.dispatch for workspace\\/executeCommand {string} with its own retained opaque plan ID")
    fun primaryManagedApply(command: String) {
        check(command == "refactorkit.applyPlan")
        check(openDocumentPaths(primary.session).isEmpty())
        primarySnapshotBeforeApply = sessionSnapshot(primary.session)
        primaryJavaAdapter = privateField(primary.session, "adapter", JavaLanguageAdapter::class.java)
        primaryJavaCacheBeforeApply = primaryJavaAdapter.semanticCacheStatus()
        try {
            primaryResponse = executeApply(primary, command)
        } catch (failure: Throwable) {
            primaryApplyFailure = failure
        }
    }

    @Then("successful document preflight precedes exactly one fresh JavaProjectScanner scan of the primary root whose result is exact {string} with every auxiliary POM byte")
    fun primaryPreflightPrecedesFreshScan(baseline: String) {
        check(baseline == "S0")
        check(primaryApplyFailure == null) { "Primary apply failed: $primaryApplyFailure" }
        assertApplyBytecodePreflightOrder(requireSelector = false)
        val block = applyPlanBytecodeBlock()
        check(SCANNER_SCAN.findAll(block).count() == 1) { "Expected one explicit fresh apply scan\n$block" }
        check(primary.plan.snapshotHash == s0.hash)
        check(primarySnapshotBeforeApply === primary.snapshotBeforePreview)
        check(s0Facts.rawPoms.isNotEmpty())
        check(s0Facts.rawPoms == rawPoms(primarySnapshotBeforeApply))
    }

    @Then("LSP calls the normal five-argument ManagedApplyDiagnosticsGateSelector.select entry exactly once with the same retained PatchPlan, language ID {string}, the Java and Kotlin adapters current for that session, and the normal external-gate resolver")
    fun lspUsesFiveArgumentSelector(languageId: String) {
        check(languageId == "java")
        val record = solePrimaryRecord()
        check(record.postSnapshotHash == s1.hash) {
            "RED: LSP managed apply persisted non-authoritative postSnapshotHash=${record.postSnapshotHash}; " +
                "simulator C1=${c1.hash}; authoritative S1=${s1.hash}"
        }
        assertApplyBytecodePreflightOrder(requireSelector = true)
        val block = applyPlanBytecodeBlock()
        check(SELECTOR_CALL.findAll(block).count() == 1) { "Expected one five-argument selector call\n$block" }
        check(block.contains("Field adapter:Lorg/refactorkit/java/JavaLanguageAdapter;"))
        check(block.contains("Field kotlinAdapter:Lorg/refactorkit/kotlin/KotlinLanguageAdapter;"))
        check(block.contains("String java"))
        check(pendingStore(primary.session).lookup(PlanId(primary.planId)) == null)
        consumedObservations += InternalObservation.SELECTOR_BYTECODE_ADOPTION
    }

    @Then("selection returns the lazy operation-owned gate {string} bound to the same exact retained PatchPlan and authority lease, without selecting or evaluating generic {string}")
    fun exactOperationOwnedGateSelected(gateId: String, genericGateId: String) {
        check(gateId == JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID)
        check(genericGateId == "java-jdt")
        primaryKotlinAdapter = privateField(primary.session, "kotlinAdapter", KotlinLanguageAdapter::class.java)
        val gate = invokePublicFiveArgumentSelector(
            primary.plan,
            primaryJavaAdapter,
            primaryKotlinAdapter,
        )
        selectorGate = gate
        check(gate.id == gateId)
        check(primary.plan.authorityLease != null)
        check(primary.plan.authorityLease === checkNotNull(primary.plan.authorityLease))
        val cacheBeforeProbe = primaryJavaAdapter.semanticCacheStatus()
        check(primaryJavaAdapter.semanticCacheStatus() == cacheBeforeProbe) { "Lazy selector probe evaluated java-jdt" }
        val block = applyPlanBytecodeBlock()
        check(!block.contains("String java-jdt")) { "LSP apply branch still constructs the generic gate" }
        check(!block.contains("DiagnosticsGate\$Companion.enabled"))
        consumedObservations += InternalObservation.EXACT_GATE_ROUTE
    }

    @Then("PatchEngine receives that same exact retained PatchPlan, lease-bearing gate, fresh {string} scan, and ApplyAuthorization.explicit with surface {string} and actor {string}, which the journal must record with approval kind EXPLICIT_APPLY")
    fun patchEngineReceivesExactInputs(baseline: String, surface: String, actor: String) {
        check(baseline == "S0")
        check(surface == "lsp-managed-command" && actor == "caller")
        val record = solePrimaryRecord()
        check(record.transaction.planId == primary.plan.id)
        check(record.operation == primary.plan.operation)
        check(primary.plan.authorityLease != null)
        check(record.preSnapshotHash == s0.hash)
        check(record.transaction.snapshotHashBefore == s0.hash)
        check(record.transaction.approval.kind == ApprovalKind.EXPLICIT_APPLY)
        check(record.transaction.approval.surface == surface)
        check(record.transaction.approval.actor == actor)
        check(selectorGate?.id == JavaRenameMavenModulePlanner.DIAGNOSTICS_GATE_ID)
    }

    @Then("the operation-owned gate evaluates exact baseline {string}, simulator candidate {string}, and committed authoritative {string} in order with the exact unchanged diagnostic multiset {string}, while every generic {string} provider invocation count remains zero for this apply")
    fun authoritativeGateEvaluatesAllThree(
        baseline: String,
        candidate: String,
        postImage: String,
        diagnosticsName: String,
        genericGateId: String,
    ) {
        check(baseline == "S0" && candidate == "C1" && postImage == "S1" && diagnosticsName == "D0")
        check(genericGateId == "java-jdt")
        val record = solePrimaryRecord()
        check(record.preSnapshotHash == s0.hash)
        check(record.postSnapshotHash == s1.hash)
        check(c1.hash != s1.hash)
        check(diagnostics(s0) == d0)
        check(trackedContent(c1) == trackedContent(s1))
        val committed = scan(primaryRoot)
        check(diagnostics(committed) == d0)
        val cacheAfterApply = primaryJavaAdapter.semanticCacheStatus()
        check(cacheAfterApply == primaryJavaCacheBeforeApply) {
            "Generic java-jdt provider changed the session cache: before=$primaryJavaCacheBeforeApply after=$cacheAfterApply"
        }
        consumedObservations += InternalObservation.GENERIC_JAVA_ADAPTER_UNUSED
    }

    @Then("the committed non-engine workspace equals the independent {string} byte, path-kind, inventory, reactor, and diagnostic oracles before journal inspection")
    fun committedWorkspaceEqualsIndependentS1(postImage: String) {
        check(postImage == "S1")
        check(firstJournalInspectionSequence > oracleReadySequence)
        check(captureManifest(primaryRoot, excludeEngine = true) == s1Manifest)
        val committed = scan(primaryRoot)
        check(snapshotFacts(primaryRoot, committed) == s1Facts)
        check(diagnostics(committed) == d0)
    }

    @Then("fresh read-only journal inspection finds exactly one record whose integer literal schemaVersion is 8 and whose operation, primary plan correlation, approval, exact deserialized forwardEdit, ordered preImages and postImages, createdDirectories, snapshot hashes, null failure, APPLIED state, and ordered history equal the independent journal oracle field by field")
    fun appliedJournalMatchesIndependentOracle() {
        check(firstJournalInspectionSequence > oracleReadySequence)
        val record = solePrimaryRecord()
        appliedRecord = record
        check(record.schemaVersion == 8)
        check(record.apiVersion == "0.2")
        check(record.operation == JavaRenameMavenModulePlanner.OPERATION)
        check(record.transaction.planId == PlanId(primary.planId))
        check(record.transaction.approval.kind == ApprovalKind.EXPLICIT_APPLY)
        check(record.transaction.approval.surface == "lsp-managed-command")
        check(record.transaction.approval.actor == "caller")
        check(record.forwardEdit == literalWorkspaceEdit)
        assertJournalImages("pre", preImageOracle, record.preImages)
        assertJournalImages("post", postImageOracle, record.postImages)
        check(record.createdDirectories.map(Path::normalize) == CREATED_DIRECTORIES)
        check(record.preSnapshotHash == s0.hash)
        check(record.postSnapshotHash == s1.hash)
        check(record.failure == null)
        check(record.state == JournalState.APPLIED)
        check(record.history.map { it.state } == APPLIED_HISTORY)
        check(record.history.zipWithNext().all { (left, right) -> !right.at.isBefore(left.at) })
        check(record.transaction.id.value.isNotBlank())
        check(record.transaction.approval.recordedAt <= record.transaction.appliedAt)
    }

    @Then("that record's postSnapshotHash equals authoritative {string} and is not equal to simulator candidate {string}")
    fun postSnapshotIsAuthoritative(postImage: String, candidate: String) {
        check(postImage == "S1" && candidate == "C1")
        val record = solePrimaryRecord()
        check(record.postSnapshotHash == s1.hash)
        check(record.postSnapshotHash != c1.hash)
    }

    @Then("the record reaches APPLIED before LSP returns its same opaque transaction ID, and only after ApplyResult.Applied does LSP remove the primary plan, refresh its saved state to exact {string} and {string}, and make normal RefactorKit rollback available")
    fun appliedOutcomeControlsRemovalAndRefresh(postImage: String, diagnosticsName: String) {
        check(postImage == "S1" && diagnosticsName == "D0")
        val responseId = primaryResponse.getValue("transactionId").jsonPrimitive.content
        transactionId = responseId
        val record = solePrimaryRecord()
        check(record.state == JournalState.APPLIED)
        check(record.transaction.id.value == responseId)
        check(pendingStore(primary.session).lookup(PlanId(primary.planId)) == null)
        primarySnapshotAfterApply = sessionSnapshot(primary.session)
        check(primarySnapshotAfterApply !== primarySnapshotBeforeApply)
        check(primarySnapshotAfterApply.hash == s1.hash)
        check(diagnostics(primarySnapshotAfterApply) == d0)
        val block = applyPlanBytecodeBlock()
        val appliedIndex = block.indexOf("ApplyResult\$Applied")
        val removeIndex = block.indexOf("PendingPlanStore", appliedIndex).let { first ->
            block.indexOf("remove-", first)
        }
        val refreshIndex = block.indexOf("refreshSnapshot", appliedIndex)
        val refusedIndex = block.indexOf("ApplyResult\$Refused", appliedIndex)
        check(appliedIndex >= 0 && removeIndex > appliedIndex && refreshIndex > removeIndex)
        check(refusedIndex > refreshIndex)
        consumedObservations += InternalObservation.APPLIED_SUCCESS_LIFECYCLE
    }

    @When("the clean and closed primary session invokes public LspSession.dispatch for workspace\\/executeCommand {string} with that transaction ID and force absent")
    fun invokeNormalRollback(command: String) {
        check(command == "refactorkit.rollback")
        try {
            rollbackResponse = primary.session.dispatch("workspace/executeCommand", buildJsonObject {
                put("command", command)
                put("arguments", buildJsonArray {
                    add(buildJsonObject { put("transactionId", transactionId) })
                })
            }).jsonObject
        } catch (failure: Throwable) {
            rollbackFailure = failure
        }
    }

    @Then("ManagedRollbackOutcome.RolledBack occurs for that exact transaction before LSP refreshes or returns success")
    fun rolledBackOutcomePrecedesRefresh() {
        check(rollbackFailure == null) { "Rollback failed: $rollbackFailure" }
        check(rollbackResponse.getValue("status").jsonPrimitive.content == "rolledBack")
        check(rollbackResponse.getValue("transactionId").jsonPrimitive.content == transactionId)
        val block = rollbackBytecodeBlock()
        val outcome = block.indexOf("ManagedRollbackOutcome\$RolledBack")
        val refresh = block.indexOf("refreshSnapshot", outcome)
        val response = block.indexOf("String rolledBack", outcome)
        check(outcome >= 0 && refresh > outcome && response > refresh) { "Rollback refresh ordering drifted\n$block" }
    }

    @Then("fresh read-only journal inspection still finds exactly one record for the same transaction and plan, now in ROLLED_BACK with ordered history {string}, no second transaction, and every forwardEdit, pre-image, post-image, createdDirectories, approval, and snapshot field unchanged from the independent oracle")
    fun rolledBackJournalMatchesSameOracle(history: String) {
        check(history == "PREPARED, APPLYING, APPLIED, ROLLING_BACK, ROLLED_BACK")
        val records = transactionLog(primaryRoot).listRecordsReadOnly()
        check(records.size == 1)
        val record = records.single()
        rolledBackRecord = record
        check(record.transaction.id.value == transactionId)
        check(record.transaction.planId == PlanId(primary.planId))
        check(record.state == JournalState.ROLLED_BACK)
        check(record.history.map { it.state } == ROLLED_BACK_HISTORY)
        check(record.forwardEdit == literalWorkspaceEdit)
        assertJournalImages("rolled-back pre", preImageOracle, record.preImages)
        assertJournalImages("rolled-back post", postImageOracle, record.postImages)
        check(record.createdDirectories.map(Path::normalize) == CREATED_DIRECTORIES)
        check(record.transaction.approval == appliedRecord.transaction.approval)
        check(record.preSnapshotHash == s0.hash)
        check(record.postSnapshotHash == s1.hash)
        check(record.failure == null)
    }

    @Then("every non-engine byte, path kind, source and auxiliary inventory, reactor fact, snapshot identity, and authoritative diagnostic returns to exact {string} and {string}, including absence of the complete declared created-directory hierarchy")
    fun rollbackRestoresExactS0(baseline: String, diagnosticsName: String) {
        check(baseline == "S0" && diagnosticsName == "D0")
        check(captureManifest(primaryRoot, excludeEngine = true) == s0Manifest)
        val restored = scan(primaryRoot)
        check(snapshotFacts(primaryRoot, restored) == s0Facts)
        check(restored.hash == s0.hash)
        check(diagnostics(restored) == d0)
        CREATED_DIRECTORIES.forEach { path ->
            check(!Files.exists(primaryRoot.resolve(path), LinkOption.NOFOLLOW_LINKS)) { "Created directory remains: $path" }
        }
        check(transactionLog(primaryRoot).listRecordsReadOnly().size == 1)
    }

    @Then("only successful rollback refreshes the primary saved state to exact {string} and {string}, while the separately qualified SURFACE-004 documentChanges remain client-managed, non-transactional, non-rollbackable by RefactorKit, unapplied in this case, and unqualified as managed-apply evidence")
    fun rollbackRefreshAndSurface004RemainSeparate(baseline: String, diagnosticsName: String) {
        check(baseline == "S0" && diagnosticsName == "D0")
        val refreshed = sessionSnapshot(primary.session)
        check(refreshed !== primarySnapshotAfterApply)
        check(refreshed.hash == s0.hash)
        check(diagnostics(refreshed) == d0)
        check(rolledBackRecord.state == JournalState.ROLLED_BACK)
        val feature = Files.readString(repositoryRoot.resolve("features/java-maven-module-rename-managed-surfaces.feature"))
        val surface004 = feature.substringAfter("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-004")
            .substringBefore("@REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005")
        check(surface004.contains("@implemented-and-validated"))
        check(surface004.contains("client-managed"))
        check(surface004.contains("non-rollbackable"))
        check(listOf(primary, dirty, affected).all { it.plan.workspaceEdit == literalWorkspaceEdit })
        check(captureManifest(dirtyRoot) == dirtyExpectedManifest)
        check(captureManifest(affectedRoot) == s0Manifest)
        consumedObservations += InternalObservation.ROLLBACK_SUCCESS_LIFECYCLE
    }

    private fun newProbe(name: String, root: Path): SessionProbe {
        val probe = SessionProbe(name, root.toAbsolutePath().normalize(), LspSession())
        probe.session.onNotification = { method, _ -> probe.notifications += method }
        return probe
    }

    private fun initialize(probe: SessionProbe) {
        probe.session.dispatch("initialize", buildJsonObject {
            put("rootUri", probe.root.toUri().toString())
            put("initializationOptions", buildJsonObject { put("refactorkitApiVersion", "0.2") })
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
                })
            })
        })
    }

    private fun openDocument(probe: SessionProbe, path: Path, version: Int) {
        val content = Files.readString(probe.root.resolve(path), StandardCharsets.UTF_8)
        probe.session.dispatch("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", probe.root.resolve(path).toUri().toString())
                put("languageId", "java")
                put("version", version)
                put("text", content)
            })
        })
    }

    private fun executeRename(session: LspSession, command: String): JsonObject =
        session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", command)
            put("arguments", buildJsonArray {
                add(buildJsonObject {
                    put("oldModuleDir", OLD_MODULE)
                    put("newModuleDir", NEW_MODULE)
                    put("newArtifactId", NEW_ARTIFACT)
                })
            })
        }).jsonObject

    private fun executeApply(probe: SessionProbe, command: String): JsonObject =
        probe.session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", command)
            put("arguments", buildJsonArray {
                add(buildJsonObject { put("planId", probe.planId) })
            })
        }).jsonObject

    private fun executeApplyExpectingFailure(probe: SessionProbe, command: String): JsonRpcException {
        val snapshotBefore = sessionSnapshot(probe.session)
        val planBefore = checkNotNull(pendingStore(probe.session).lookup(PlanId(probe.planId)))
        return try {
            val result = executeApply(probe, command)
            error("${probe.name} unexpectedly applied retained plan: $result")
        } catch (failure: JsonRpcException) {
            check(sessionSnapshot(probe.session) === snapshotBefore)
            check(pendingStore(probe.session).lookup(PlanId(probe.planId)) === planBefore)
            failure
        }
    }

    private fun assertRepeatedFailure(probe: SessionProbe) {
        val first = checkNotNull(probe.firstFailure)
        val repeated = checkNotNull(probe.repeatFailure)
        check(repeated.code == first.code)
        check(repeated.message == first.message)
        check(pendingStore(probe.session).lookup(PlanId(probe.planId)) === probe.plan)
    }

    private fun assertRefusalLifecycle(probe: SessionProbe, expectedManifest: ExactManifest) {
        check(captureManifest(probe.root) == expectedManifest)
        assertNoEngineState(probe.root)
        check(sessionSnapshot(probe.session) === probe.snapshotBeforePreview)
        check(pendingStore(probe.session).lookup(PlanId(probe.planId)) === probe.plan)
        check(probe.notifications.isEmpty())
    }

    private fun invokePublicFiveArgumentSelector(
        plan: PatchPlan,
        javaAdapter: JavaLanguageAdapter,
        kotlinAdapter: KotlinLanguageAdapter,
    ): DiagnosticsGate {
        val selectorClass = Class.forName("org.refactorkit.jvm.ManagedApplyDiagnosticsGateSelector")
        val instance = selectorClass.getField("INSTANCE").get(null)
        val select = selectorClass.methods.single { it.name == "select" && it.parameterCount == 5 }
        val failClosedResolver: (String) -> DiagnosticsGate = { languageId ->
            error("Fail-closed external diagnostics gate resolver was unexpectedly reached for $languageId")
        }
        val result = select.invoke(instance, plan, "java", javaAdapter, kotlinAdapter, failClosedResolver)
        return result as? DiagnosticsGate ?: error("Five-argument selector returned an unexpected type")
    }

    private fun assertApplyBytecodePreflightOrder(requireSelector: Boolean) {
        val block = applyPlanBytecodeBlock()
        val lookup = block.indexOf("PendingPlanStore")
        val preflight = block.indexOf("requireManagedWriteSafe")
        val scan = block.indexOf("JavaProjectScanner.scan", preflight)
        val selector = block.indexOf("ManagedApplyDiagnosticsGateSelector.select", preflight)
        val genericGate = block.indexOf("DiagnosticsGate\$Companion.enabled", preflight)
        val patchEngine = block.indexOf("class org/refactorkit/core/PatchEngine", preflight)
        val patchApply = block.indexOf("PatchEngine.apply", preflight)
        val remove = block.indexOf("PendingPlanStore", patchApply).let { block.indexOf("remove-", it) }
        val refresh = block.indexOf("refreshSnapshot", patchApply)
        check(lookup >= 0 && preflight > lookup)
        check(scan > preflight)
        val route = if (selector >= 0) selector else genericGate
        check(route > preflight)
        check(patchEngine > preflight && patchApply > patchEngine)
        check(remove > patchApply && refresh > patchApply)
        if (requireSelector) {
            check(selector > scan)
            check(patchEngine > selector)
            check(genericGate < 0)
        }
    }

    private fun applyPlanBytecodeBlock(): String {
        val method = executeCommandBytecode()
        val planNotFound = method.indexOf("String Plan not found:")
        val start = method.lastIndexOf("PendingPlanStore", planNotFound)
        val end = method.indexOf("ManagedRollbackExecutor", planNotFound)
        check(start >= 0 && planNotFound > start && end > planNotFound) { "Cannot isolate applyPlan bytecode" }
        return method.substring(start, end)
    }

    private fun rollbackBytecodeBlock(): String {
        val method = executeCommandBytecode()
        val start = method.indexOf("ManagedRollbackExecutor")
        val end = method.indexOf("String Unknown command:", start)
        check(start >= 0 && end > start) { "Cannot isolate rollback bytecode" }
        return method.substring(start, end)
    }

    private fun executeCommandBytecode(): String {
        val output = checkNotNull(bytecodeEvidence) { "fail-closed: compiled LspSession bytecode was not captured" }
        val start = output.indexOf("private final kotlinx.serialization.json.JsonElement executeCommand")
        val end = output.indexOf("\n  private final void refreshSnapshot", start)
        check(start >= 0 && end > start) { "fail-closed: executeCommand bytecode was not found" }
        return output.substring(start, end)
    }

    private fun javapLspSession(): String {
        val javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        val executable = javaHome.resolve("bin").resolve(if (isWindows()) "javap.exe" else "javap")
        check(Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) { "fail-closed: javap is unavailable: $executable" }
        val classPath = Path.of(LspSession::class.java.protectionDomain.codeSource.location.toURI())
            .toAbsolutePath().normalize()
        val process = ProcessBuilder(
            executable.toString(),
            "-classpath",
            classPath.toString(),
            "-c",
            "-p",
            LspSession::class.java.name,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        check(process.waitFor() == 0) { "fail-closed: javap failed\n$output" }
        check(output.contains("Compiled from \"LspSession.kt\""))
        return output
    }

    private fun solePrimaryRecord(): TransactionJournalRecord {
        if (firstJournalInspectionSequence < 0) firstJournalInspectionSequence = ++sequence
        val records = transactionLog(primaryRoot).listRecordsReadOnly()
        check(records.size == 1) { "Expected one transaction record, found ${records.size}" }
        return records.single()
    }

    private fun transactionLog(root: Path) = TransactionLog(root.resolve(".refactorkit/transactions"))

    private fun scan(root: Path): ProjectSnapshot = JavaProjectScanner()
        .scan(root.toAbsolutePath().normalize())

    private fun scanAuthoritative(root: Path): ProjectSnapshot = JavaProjectScanner(
        allowNetworkDependencyResolution = false,
        localMavenRepository = root.resolve("fixture-repository"),
    ).scan(root.toAbsolutePath().normalize())

    private fun scanIndependentPostImageAtPrimaryRoot(): Pair<ProjectSnapshot, ExactManifest> {
        val heldBaseline = temporaryRoot.resolve("primary-s0-held").toAbsolutePath().normalize()
        check(!Files.exists(heldBaseline, LinkOption.NOFOLLOW_LINKS))
        Files.move(primaryRoot, heldBaseline)
        Files.move(oracleRoot, primaryRoot)
        return try {
            val snapshot = scanAuthoritative(primaryRoot)
            snapshot to captureManifest(primaryRoot)
        } finally {
            Files.move(primaryRoot, oracleRoot)
            Files.move(heldBaseline, primaryRoot)
            check(captureManifest(primaryRoot) == s0Manifest)
        }
    }

    private fun diagnostics(snapshot: ProjectSnapshot): DiagnosticBaseline = DiagnosticBaseline(
        maven = snapshot.buildModels.flatMap { it.diagnostics },
        jdt = JavaLanguageAdapter().authoritativeDiagnostics(snapshot, Path.of(System.getProperty("java.home"))),
    )

    private fun snapshotFacts(root: Path, snapshot: ProjectSnapshot): SnapshotFacts = SnapshotFacts(
        hash = snapshot.hash,
        modules = snapshot.modules,
        sources = snapshot.files.sortedBy { it.path.invariantSeparatorsPathString },
        auxiliary = snapshot.auxiliaryFiles.sortedBy { it.path.invariantSeparatorsPathString },
        buildModels = snapshot.buildModels,
        rawPoms = rawPoms(root),
    )

    private fun trackedContent(snapshot: ProjectSnapshot): List<Triple<Path, String, String>> = snapshot.trackedFiles
        .sortedBy { it.path.invariantSeparatorsPathString }
        .map { Triple(it.path.normalize(), it.languageId, it.content) }

    private fun rawPoms(root: Path): Map<String, String> = captureManifest(root, excludeEngine = true).entries
        .filter { (path, entry) -> path != "." && path.substringAfterLast('/') == "pom.xml" && entry.kind == "regular-file" }
        .mapValues { (_, entry) -> checkNotNull(entry.contentBase64) }
        .toSortedMap()

    private fun rawPoms(snapshot: ProjectSnapshot): Map<String, String> = snapshot.auxiliaryFiles
        .filter { it.path.fileName.toString() == "pom.xml" }
        .associate { file ->
            file.path.invariantSeparatorsPathString to Base64.getEncoder().encodeToString(
                file.content.toByteArray(StandardCharsets.UTF_8),
            )
        }
        .toSortedMap()

    private fun applyLiteralWorkspaceEdit(root: Path) {
        literalModifies.forEach { edit ->
            val path = root.resolve(edit.path)
            val content = Files.readString(path, StandardCharsets.UTF_8)
            val changed = applyLiteralEdit(content, edit)
            Files.writeString(
                path,
                changed,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }
        literalRenames.forEach { rename ->
            val source = root.resolve(rename.path)
            val destination = root.resolve(rename.newPath)
            check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            Files.createDirectories(destination.parent)
            Files.move(source, destination)
        }
    }

    private fun applyLiteralEdit(content: String, edit: LiteralModify): String {
        val start = offset(content, edit.startLine, edit.startCharacter)
        val end = offset(content, edit.endLine, edit.endCharacter)
        check(content.substring(start, end) == edit.oldText) { "Literal range drifted for ${edit.path}" }
        return content.substring(0, start) + edit.newText + content.substring(end)
    }

    private fun offset(content: String, targetLine: Int, targetCharacter: Int): Int {
        var line = 0
        var index = 0
        while (line < targetLine) {
            val newline = content.indexOf('\n', index)
            check(newline >= 0)
            index = newline + 1
            line++
        }
        check(index + targetCharacter <= content.length)
        return index + targetCharacter
    }

    private fun captureFilesystemImages(root: Path, paths: List<Path>): List<FilesystemFileImage> = paths.map { relative ->
        val normalized = relative.normalize()
        val absolute = root.resolve(normalized).normalize()
        check(absolute.startsWith(root.toAbsolutePath().normalize()))
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            FilesystemFileImage(normalized, null, null, null, null, null, null, null, null)
        } else {
            check(!Files.isSymbolicLink(absolute) && Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS))
            val bytes = Files.readAllBytes(absolute)
            val content = String(bytes, StandardCharsets.UTF_8)
            val basic = Files.readAttributes(absolute, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val posix = runCatching {
                Files.getFileAttributeView(absolute, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?.readAttributes()
            }.getOrNull()
            val owner = runCatching {
                Files.getFileAttributeView(absolute, FileOwnerAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?.owner?.name
            }.getOrNull()
            val xattrs = runCatching {
                Files.getFileAttributeView(absolute, UserDefinedFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?.let { view ->
                        view.list().sorted().associateWith { name ->
                            val buffer = ByteBuffer.allocate(view.size(name))
                            view.read(name, buffer)
                            buffer.flip()
                            val value = ByteArray(buffer.remaining())
                            buffer.get(value)
                            Base64.getEncoder().encodeToString(value)
                        }.toSortedMap()
                    }
            }.getOrNull()
            val acl = runCatching {
                Files.getFileAttributeView(absolute, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                    ?.acl?.map { entry ->
                        FileAclEntryImage(
                            entry.type().name,
                            entry.principal().name,
                            entry.permissions().map(AclEntryPermission::name).sorted(),
                            entry.flags().map(AclEntryFlag::name).sorted(),
                        )
                    }
            }.getOrNull()
            FilesystemFileImage(
                normalized,
                content,
                sha256(bytes),
                posix?.permissions()?.toSet(),
                basic.lastModifiedTime().toMillis(),
                owner,
                posix?.group()?.name,
                xattrs,
                acl,
            )
        }
    }

    private fun FilesystemFileImage.toPreOracle() = IndependentFileImage(
        path,
        content,
        contentSha256,
        posixPermissions,
        lastModifiedMillis,
        ownerName,
        groupName,
        userDefinedAttributes,
        aclEntries,
    )

    private fun derivePostImageOracle(
        before: List<FilesystemFileImage>,
        independentPostRoot: Path,
    ): List<IndependentFileImage> {
        val origins = before.associate { image -> image.path to image.path.takeIf { image.content != null } }.toMutableMap()
        literalWorkspaceEdit.edits.forEach { edit ->
            when (edit) {
                is FileEdit.Modify -> Unit
                is FileEdit.Rename -> {
                    val source = edit.path.normalize()
                    val origin = origins[source]
                    origins[source] = null
                    origins[edit.newPath.normalize()] = origin
                }
                is FileEdit.Create -> origins[edit.path.normalize()] = null
                is FileEdit.Delete -> origins[edit.path.normalize()] = null
            }
        }
        val beforeByPath = before.associateBy { it.path }
        return ORDERED_IMAGE_PATHS.map { path ->
            val absolute = independentPostRoot.resolve(path)
            if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
                IndependentFileImage(path, null, null, null, null, null, null, null, null)
            } else {
                val bytes = Files.readAllBytes(absolute)
                val origin = checkNotNull(origins[path]) { "Missing metadata origin for $path" }
                val source = checkNotNull(beforeByPath[origin])
                IndependentFileImage(
                    path,
                    String(bytes, StandardCharsets.UTF_8),
                    sha256(bytes),
                    source.posixPermissions,
                    null,
                    null,
                    null,
                    source.userDefinedAttributes,
                    source.aclEntries,
                )
            }
        }
    }

    private fun assertJournalImages(label: String, expected: List<IndependentFileImage>, actual: List<FileImage>) {
        check(actual.size == expected.size) { "$label image count" }
        expected.zip(actual).forEachIndexed { index, (oracle, image) ->
            val item = "$label[$index] ${oracle.path.invariantSeparatorsPathString}"
            check(image.path.normalize() == oracle.path) { "$item path" }
            check(image.content == oracle.content) { "$item content" }
            check(image.contentSha256 == oracle.contentSha256) { "$item contentSha256" }
            check(image.posixPermissions == oracle.posixPermissions) { "$item posixPermissions" }
            check(image.lastModifiedMillis == oracle.lastModifiedMillis) { "$item lastModifiedMillis" }
            check(image.ownerName == oracle.ownerName) { "$item ownerName" }
            check(image.groupName == oracle.groupName) { "$item groupName" }
            check(image.userDefinedAttributes == oracle.userDefinedAttributes) { "$item userDefinedAttributes" }
            check(image.aclEntries == oracle.aclEntries) { "$item aclEntries" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingStore(session: LspSession): PendingPlanStore<PatchPlan> =
        privateField(session, "pendingPlans", PendingPlanStore::class.java) as PendingPlanStore<PatchPlan>

    private fun sessionSnapshot(session: LspSession): ProjectSnapshot =
        checkNotNull(sessionSnapshotOrNull(session)) { "fail-closed: LspSession snapshot is absent" }

    private fun sessionSnapshotOrNull(session: LspSession): ProjectSnapshot? =
        privateNullableField(session, "snapshot", ProjectSnapshot::class.java)

    private fun openDocumentPaths(session: LspSession): List<Path> {
        val documents = privateField(session, "openDocuments", Map::class.java) as Map<*, *>
        return documents.values.map { candidate ->
            val document = checkNotNull(candidate)
            val pathField = document.javaClass.getDeclaredField("path")
            pathField.isAccessible = true
            pathField.get(document) as Path
        }
    }

    private fun openDocumentContent(session: LspSession, path: Path): String {
        val documents = privateField(session, "openDocuments", Map::class.java) as Map<*, *>
        val document = checkNotNull(documents.values.single { candidate ->
            val value = checkNotNull(candidate)
            val pathField = value.javaClass.getDeclaredField("path")
            pathField.isAccessible = true
            pathField.get(value) == path
        })
        val contentField = document.javaClass.getDeclaredField("content")
        contentField.isAccessible = true
        return contentField.get(document) as String
    }

    private fun <T : Any> privateField(instance: Any, name: String, type: Class<T>): T =
        checkNotNull(privateNullableField(instance, name, type))

    private fun <T : Any> privateNullableField(instance: Any, name: String, type: Class<T>): T? {
        val field = runCatching { instance.javaClass.getDeclaredField(name) }
            .getOrElse { throw IllegalStateException("fail-closed: private field $name is unavailable", it) }
        field.isAccessible = true
        val value = field.get(instance) ?: return null
        check(type.isInstance(value)) { "fail-closed: private field $name has unexpected type ${value.javaClass.name}" }
        return type.cast(value)
    }

    private fun assertNoEngineState(root: Path) {
        val engine = root.resolve(".refactorkit")
        check(!Files.exists(engine, LinkOption.NOFOLLOW_LINKS)) { "Unexpected managed engine state: $engine" }
    }

    private fun withChangedFile(manifest: ExactManifest, path: Path, bytes: ByteArray): ExactManifest {
        val entries = manifest.entries.toMutableMap()
        val key = path.invariantSeparatorsPathString
        check(entries[key]?.kind == "regular-file")
        entries[key] = ManifestEntry("regular-file", Base64.getEncoder().encodeToString(bytes))
        return ExactManifest(entries.toSortedMap())
    }

    private fun freshCopy(name: String): Path {
        val destination = temporaryRoot.resolve(name).toAbsolutePath().normalize()
        copyNoFollow(fixtureRoot, destination)
        return destination
    }

    private fun copyNoFollow(source: Path, target: Path) {
        check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS))
        Files.walkFileTree(
            source,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir))
                    val destination = target.resolve(source.relativize(dir).toString()).normalize()
                    check(destination.startsWith(target))
                    Files.createDirectories(destination)
                    runCatching {
                        Files.setPosixFilePermissions(
                            destination,
                            Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS),
                        )
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    check(attrs.isRegularFile && !attrs.isSymbolicLink && !Files.isSymbolicLink(file))
                    val destination = target.resolve(source.relativize(file).toString()).normalize()
                    check(destination.startsWith(target))
                    Files.createDirectories(checkNotNull(destination.parent))
                    Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun captureManifest(root: Path, excludeEngine: Boolean = false): ExactManifest {
        val entries = sortedMapOf<String, ManifestEntry>()
        Files.walkFileTree(
            root,
            EnumSet.noneOf(FileVisitOption::class.java),
            Int.MAX_VALUE,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = portableRelative(root, dir).ifBlank { "." }
                    if (excludeEngine && (relative == ".refactorkit" || relative.startsWith(".refactorkit/"))) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    check(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir))
                    entries[relative] = ManifestEntry("directory", null)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val relative = portableRelative(root, file)
                    if (excludeEngine && relative.startsWith(".refactorkit/")) return FileVisitResult.CONTINUE
                    check(attrs.isRegularFile && !attrs.isSymbolicLink && !Files.isSymbolicLink(file))
                    entries[relative] = ManifestEntry(
                        "regular-file",
                        Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return ExactManifest(entries)
    }

    private fun portableRelative(root: Path, path: Path): String =
        root.relativize(path).invariantSeparatorsPathString

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

                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts")) &&
                Files.isDirectory(candidate.resolve("modules/refactorkit-lsp"))
            ) return candidate
            candidate = candidate.parent
        }
        error("Repository root was not found")
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private data class ImageMatrix(
        val preLength: Int?,
        val preHash: String?,
        val postLength: Int?,
        val postHash: String?,
    )

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val OLD_MODULE = "catalog-model"
        const val NEW_MODULE = "catalog-domain"
        const val NEW_ARTIFACT = "catalog-domain"
        val REPORT_PATH: Path = Path.of("reporting-unrelated/src/main/java/com/acme/reporting/ProductReport.java")
        val PRODUCT_PATH: Path = Path.of("catalog-model/src/main/java/com/acme/catalog/legacy/Product.java")
        val ORDERED_IMAGE_PATHS = listOf(
            Path.of("catalog-model/pom.xml"),
            Path.of("pom.xml"),
            Path.of("catalog-pricing/pom.xml"),
            Path.of("catalog-domain/pom.xml"),
            PRODUCT_PATH,
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java"),
        )
        val CREATED_DIRECTORIES = listOf(
            Path.of("catalog-domain"),
            Path.of("catalog-domain/src"),
            Path.of("catalog-domain/src/main"),
            Path.of("catalog-domain/src/main/java"),
            Path.of("catalog-domain/src/main/java/com"),
            Path.of("catalog-domain/src/main/java/com/acme"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog"),
            Path.of("catalog-domain/src/main/java/com/acme/catalog/legacy"),
        )
        val FILE_IMAGE_FIELDS = listOf(
            "path",
            "content",
            "contentSha256",
            "posixPermissions",
            "lastModifiedMillis",
            "ownerName",
            "groupName",
            "userDefinedAttributes",
            "aclEntries",
        )
        val APPLIED_HISTORY = listOf(JournalState.PREPARED, JournalState.APPLYING, JournalState.APPLIED)
        val ROLLED_BACK_HISTORY = APPLIED_HISTORY + listOf(JournalState.ROLLING_BACK, JournalState.ROLLED_BACK)
        val SCANNER_SCAN = Regex("JavaProjectScanner\\.scan")
        val SELECTOR_CALL = Regex("ManagedApplyDiagnosticsGateSelector\\.select")
    }
}

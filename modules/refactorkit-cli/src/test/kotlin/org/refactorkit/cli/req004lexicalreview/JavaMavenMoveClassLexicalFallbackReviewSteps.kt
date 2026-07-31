package org.refactorkit.cli.req004lexicalreview

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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.errorResponse
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaMoveClassForceRequest
import org.refactorkit.java.JavaMoveClassLexicalFallbackReviewEnvelope
import org.refactorkit.java.JavaMoveClassLexicalFallbackReviewJsonProjection
import org.refactorkit.java.JavaMoveClassLexicalReviewAuditCache
import org.refactorkit.java.JavaMoveClassLexicalReviewAuthorityStatus
import org.refactorkit.java.JavaMoveClassLexicalReviewEvidenceKind
import org.refactorkit.java.JavaMoveClassLexicalReviewNextAction
import org.refactorkit.java.JavaMoveClassLexicalReviewRecordCompleteness
import org.refactorkit.java.JavaMoveClassLexicalReviewSemanticCompleteness
import org.refactorkit.java.JavaMoveClassLexicalReviewManagedWriteEligibility
import org.refactorkit.java.JavaMoveClassLexicalReviewResidualGuidance
import org.refactorkit.java.JavaMoveClassOperationDispatcher
import org.refactorkit.java.JavaMoveClassOperationOutcome
import org.refactorkit.java.JavaMoveClassPlanner
import org.refactorkit.java.JavaMoveClassPromotionApproval
import org.refactorkit.java.JavaMoveClassPromotionAttemptMetadata
import org.refactorkit.java.JavaMoveClassPromotionConfidence
import org.refactorkit.java.JavaMoveClassWarningAcknowledgement
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JdtJavaDiagnosticCategory
import org.refactorkit.java.JdtJavaSemanticAnalyzer
import org.refactorkit.java.recipe.RecipeDefinition
import org.refactorkit.java.recipe.RecipeEngine
import org.refactorkit.java.recipe.RecipeResult
import org.refactorkit.java.recipe.StepDef
import org.refactorkit.lsp.LspSession
import org.refactorkit.mcp.McpSession
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dedicated executable acceptance glue for REQ-JAVA-MAVEN-MOVE-AUTH-004. */
class JavaMavenMoveClassLexicalFallbackReviewSteps {
    private val json = Json { ignoreUnknownKeys = false }
    private lateinit var fixtureTemplate: Path
    private lateinit var temporaryRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var recordedState: WorkspaceState
    private lateinit var permanentFixtureIdentity: String
    private lateinit var auditCache: JavaMoveClassLexicalReviewAuditCache
    private lateinit var dispatcher: JavaMoveClassOperationDispatcher
    private lateinit var compatibilityPreviews: List<org.refactorkit.java.JavaMoveClassPreview>
    private lateinit var directEnvelopes: List<JavaMoveClassLexicalFallbackReviewEnvelope>
    private val previewProjections = linkedMapOf<String, List<JsonObject>>()
    private val metadataProjections = linkedMapOf<String, JsonObject>()
    private var metadataOutcome: JavaMoveClassOperationOutcome? = null
    private var recipeMetadataOutcome: RecipeResult? = null
    private var invalidRecipeMetadataResults: List<RecipeResult.Failed> = emptyList()
    private var compatibilityApply: ApplyResult? = null
    private var recipeDefinitions: List<RecipeDefinition> = emptyList()
    private var recipeResults: List<RecipeResult.NonManaged> = emptyList()
    private var cliApply: CliResult? = null
    private var daemonKnownRefusal: JsonRpcException? = null
    private var lspKnownRefusal: JsonRpcException? = null
    private var mcpKnownRefusal: JsonObject? = null
    private var daemonUnknownRefusal: JsonRpcException? = null
    private var lspUnknownRefusal: JsonRpcException? = null
    private var mcpUnknownRefusal: JsonRpcException? = null
    private var daemonSession: DaemonSession? = null
    private var lspSession: LspSession? = null
    private var mcpSession: McpSession? = null

    @Before("@REQ-JAVA-MAVEN-MOVE-AUTH-004")
    fun prepareLexicalReviewFixture(scenario: Scenario) {
        fixtureTemplate = locateRepositoryRoot().resolve(FIXTURE_PATH).normalize()
        permanentFixtureIdentity = treeIdentity(fixtureTemplate)
        temporaryRoot = Files.createTempDirectory("refactorkit-move-auth-004-")
        workspaceRoot = temporaryRoot.resolve("workspace")
        copyRecursively(fixtureTemplate, workspaceRoot)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        scenario.attach(
            "REQ-JAVA-MAVEN-MOVE-AUTH-004 runs every surface against one isolated copy of the permanent " +
                "20-module fixture. Only that copy receives the broad observer syntax failure.",
            "text/plain",
            "lexical-review-isolation",
        )
    }

    @After("@REQ-JAVA-MAVEN-MOVE-AUTH-004")
    fun removeLexicalReviewFixture() {
        runCatching { daemonSession?.close() }
        runCatching { mcpSession?.close() }
        if (!this::temporaryRoot.isInitialized || !Files.exists(temporaryRoot)) return
        Files.walk(temporaryRoot).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    @Given("the declared workspace root is the permanent fixture {string}")
    fun declaredWorkspaceRoot(path: String) {
        assertEquals(FIXTURE_PATH, path)
        assertEquals(fixtureTemplate, locateRepositoryRoot().resolve(path).normalize())
    }

    @Given("the fixture is a plugin-free offline Maven reactor with one root aggregator and 20 active non-aggregator modules")
    fun fixtureIsTheExpectedOfflineReactor() {
        val rootPom = Files.readString(workspaceRoot.resolve("pom.xml"))
        val modules = MODULE_PATTERN.findAll(rootPom).map { it.groupValues[1].trim() }.toList()
        assertEquals(20, modules.size)
        assertEquals(20, modules.distinct().size)
        assertTrue(rootPom.contains("<packaging>pom</packaging>"))
        modules.forEach { module ->
            val pom = workspaceRoot.resolve(module).resolve("pom.xml")
            assertTrue(Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS), module)
            assertFalse(Files.readString(pom).contains("<modules>"), module)
        }
    }

    @Given("its active graph has at least three dependency levels, one materialized local external dependency, and one safely materialized generated Java source root")
    fun fixtureHasRequiredGraphAndMaterializedInputs() {
        assertPomDependency("catalog-acceptance", "catalog-storefront")
        assertPomDependency("catalog-storefront", "catalog-pricing")
        assertPomDependency("catalog-pricing", "catalog-model")
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(EXTERNAL_ARTIFACT_PATH), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isRegularFile(workspaceRoot.resolve(GENERATED_SOURCE_PATH), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("discovery and analysis cannot run Maven lifecycle goals, plugins, annotation processors, credential helpers, or network requests")
    fun discoveryHasNoExecutableOrNetworkInputs() {
        val pomContents = Files.walk(workspaceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString() == "pom.xml" }
                .map(Files::readString)
                .toList()
        }
        listOf(
            "<build>", "<plugins>", "<plugin>", "<pluginRepositories>", "<repositories>",
            "<annotationProcessorPaths>",
        ).forEach { forbidden ->
            assertTrue(pomContents.none { it.contains(forbidden) }, forbidden)
        }
        assertFalse(Files.exists(workspaceRoot.resolve("mvnw"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".mvn"), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the request moves the writable sole top-level class {string} from {string} to the unused path {string} within the same main source set")
    fun requestIdentifiesTheSupportedMove(symbol: String, sourcePath: String, targetPath: String) {
        assertEquals(PRODUCT_FQN, symbol)
        assertEquals(PRODUCT_SOURCE_PATH, sourcePath)
        assertEquals(PRODUCT_TARGET_PATH, targetPath)
        val source = workspaceRoot.resolve(sourcePath)
        assertTrue(Files.isRegularFile(source) && Files.isWritable(source))
        assertFalse(Files.exists(workspaceRoot.resolve(targetPath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(1, PRODUCT_DECLARATION.findAll(Files.readString(source)).count())
    }

    @Given("each entry path is evaluated independently against an isolated fixture copy with a broad syntax failure in the required observer {string}")
    fun broadSyntaxFailure(path: String) {
        assertEquals(OBSERVER_PATH, path)
        val observer = workspaceRoot.resolve(path)
        val content = Files.readString(observer)
        assertTrue(content.endsWith("}\n"), "observer fixture must end with one class-closing brace")
        Files.writeString(observer, content.removeSuffix("}\n"))
        snapshot = JavaProjectScanner().scan(workspaceRoot)
        val syntaxWarnings = JdtJavaSemanticAnalyzer().analyze(snapshot).warnings.filter {
            it.category == JdtJavaDiagnosticCategory.SYNTAX && it.path.normalize() == Path.of(path)
        }
        assertTrue(syntaxWarnings.isNotEmpty(), "broad observer syntax failure must be freshly observed")
        auditCache = JavaMoveClassLexicalReviewAuditCache()
        dispatcher = JavaMoveClassOperationDispatcher(JavaLanguageAdapter(), auditCache)
    }

    @Given("the unchanged move cannot establish complete JDT binding authority and is classified with legacy evidence {string}")
    fun unchangedMoveHasLegacyLexicalEvidence(evidence: String) {
        assertEquals(RefactoringEvidence.LEXICAL_FALLBACK.name, evidence)
        val planner = JavaMoveClassPlanner(JavaLanguageAdapter())
        compatibilityPreviews = listOf(
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
            planner.previewWithAuthority(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
        )
        compatibilityPreviews.forEach { preview ->
            assertEquals(PatchStatus.PREVIEW, preview.plan.status)
            assertEquals(RefactoringEvidence.LEXICAL_FALLBACK, preview.plan.evidence)
        }
        assertNotEquals(compatibilityPreviews[0].plan.id, compatibilityPreviews[1].plan.id)
    }

    @Given("no {string} directory exists and the workspace bytes, paths, source inventory, and snapshot SHA-256 are recorded before each evaluation")
    fun recordUnchangedWorkspace(directory: String) {
        assertEquals(".refactorkit", directory)
        assertFalse(Files.exists(workspaceRoot.resolve(directory), LinkOption.NOFOLLOW_LINKS))
        recordedState = captureWorkspaceState(snapshot)
    }

    @When("the move is previewed twice from the same normalized request and snapshot through each entry path:")
    fun previewTwiceThroughEveryEntryPath(table: DataTable) {
        assertEquals(
            listOf("Java move-class outcome API", "CLI", "daemon JSON-RPC", "managed LSP", "MCP"),
            table.asMaps().map { it.getValue("entry path") },
        )

        val outcomes = listOf(
            dispatcher.preview(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
            dispatcher.preview(snapshot, PRODUCT_FQN, TARGET_PACKAGE),
        )
        directEnvelopes = outcomes.map { assertIs<JavaMoveClassOperationOutcome.LexicalReview>(it).envelope }
        previewProjections["Java move-class outcome API"] = directEnvelopes.map(
            JavaMoveClassLexicalFallbackReviewJsonProjection::toJson,
        )

        previewProjections["CLI"] = List(2) {
            parseEnvelope(runCli(moveClassCliArguments(apply = false)).also { result ->
                assertEquals(0, result.exitCode, result.failureMessage("CLI lexical preview"))
                assertTrue(result.stderr.isBlank(), result.stderr)
            }.stdout)
        }

        daemonSession = DaemonSession().also { session ->
            session.dispatch("project.open", buildJsonObject { put("root", workspaceRoot.toString()) })
        }
        previewProjections["daemon JSON-RPC"] = List(2) {
            daemonSession!!.dispatch("refactor.preview", daemonPreviewParams()).jsonObject
        }

        lspSession = LspSession().also { session ->
            session.onNotification = { _, _ -> }
            session.dispatch("initialize", lspInitializeParams())
        }
        previewProjections["managed LSP"] = List(2) {
            lspSession!!.dispatch("workspace/executeCommand", lspMoveClassParams()).jsonObject
        }

        mcpSession = McpSession().also { session ->
            session.dispatch("initialize", buildJsonObject {})
            val scan = mcpToolCall(session, "project_scan", buildJsonObject {
                put("root", workspaceRoot.toString())
            })
            assertFalse(scan.jsonObject.getValue("isError").jsonPrimitive.content.toBoolean())
        }
        previewProjections["MCP"] = List(2) {
            val result = mcpToolCall(mcpSession!!, "preview_refactoring", mcpPreviewArguments())
            assertFalse(result.getValue("isError").jsonPrimitive.content.toBoolean())
            result.getValue("structuredContent").jsonObject
        }
    }

    @Then("every path projects the same immutable canonical {string} envelope")
    fun everyPathProjectsTheSameImmutableEnvelope(resultType: String) {
        assertEquals("LEXICAL_FALLBACK_REVIEW", resultType)
        val canonical = JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(directEnvelopes.first())
        previewProjections.forEach { (surface, projections) ->
            assertEquals(2, projections.size, surface)
            projections.forEach { assertEquals(canonical, it, surface) }
        }
        assertEquals(directEnvelopes[0], directEnvelopes[1])
        assertEquals(directEnvelopes[0].hashCode(), directEnvelopes[1].hashCode())
        assertEquals(
            JavaMoveClassLexicalReviewSemanticCompleteness.NOT_SEMANTICALLY_PROVEN,
            directEnvelopes.first().semanticCompleteness,
        )
        assertEquals(
            listOf(
                JavaMoveClassLexicalReviewNextAction.INSPECT_RESIDUALS,
                JavaMoveClassLexicalReviewNextAction.RESTORE_SEMANTIC_EVIDENCE,
                JavaMoveClassLexicalReviewNextAction.FULL_REACTOR_RESCAN,
                JavaMoveClassLexicalReviewNextAction.REQUEST_NEW_PREVIEW,
            ),
            directEnvelopes.first().nextActions,
        )
        val semanticCompletenessGetter = assertNotNull(
            directEnvelopes.first().javaClass.methods.singleOrNull { it.name == "getSemanticCompleteness" },
        )
        assertTrue(semanticCompletenessGetter.returnType.isEnum)
        assertEquals("NOT_SEMANTICALLY_PROVEN", semanticCompletenessGetter.invoke(directEnvelopes.first()).toString())
        val nextActionsGetter = assertNotNull(
            directEnvelopes.first().javaClass.methods.singleOrNull { it.name == "getNextActions" },
        )
        assertTrue(nextActionsGetter.genericReturnType.typeName.contains("JavaMoveClassLexicalReviewNextAction"))
        @Suppress("UNCHECKED_CAST")
        val typedNextActions = nextActionsGetter.invoke(directEnvelopes.first()) as List<Any?>
        assertEquals(
            listOf(
                "INSPECT_RESIDUALS",
                "RESTORE_SEMANTIC_EVIDENCE",
                "FULL_REACTOR_RESCAN",
                "REQUEST_NEW_PREVIEW",
            ),
            typedNextActions.map(Any?::toString),
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (typedNextActions as MutableList<Any?>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (directEnvelopes.first().evidenceFacts as MutableList<Any?>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (directEnvelopes.first().residualGuidance.records as MutableList<Any?>).clear()
        }
        directEnvelopes.first().residualGuidance.records.firstOrNull()?.let { record ->
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (record.riskFacts as MutableList<Any?>).clear()
            }
        }
    }

    @Then("every envelope reports schema version 1, canonicalization {string}, and hash algorithm {string}")
    fun envelopeReportsCanonicalSchema(canonicalization: String, hashAlgorithm: String) {
        allEnvelopeJson().forEach { envelope ->
            assertEquals(1, envelope.int("schemaVersion"))
            assertEquals(canonicalization, envelope.string("canonicalization"))
            assertEquals("refactorkit.lexicalFallbackReview.canonical.v1", canonicalization)
            assertEquals(hashAlgorithm, envelope.string("hashAlgorithm"))
            assertEquals("SHA-256", hashAlgorithm)
        }
    }

    @Then("every envelope binds the normalized request, exact snapshot, bounded review artifact, and sorted typed evidence facts with {string}, {string}, {string}, and {string}")
    fun envelopeBindsCanonicalIdentities(
        requestField: String,
        snapshotField: String,
        artifactField: String,
        evidenceField: String,
    ) {
        val envelope = directEnvelopes.first()
        assertEquals("moveClass", envelope.request.operation)
        assertEquals(PRODUCT_FQN, envelope.request.symbolFqn)
        assertEquals(TARGET_PACKAGE, envelope.request.targetPackage)
        assertEquals(TARGET_FQN, envelope.request.targetFqn)
        assertEquals(snapshot.hash, envelope.snapshotSha256)
        listOf(requestField, snapshotField, artifactField, evidenceField).forEach { field ->
            assertTrue(SHA256.matches(canonicalEnvelope().string(field)), field)
        }
        assertEquals(expectedEvidenceSha256(canonicalEnvelope()), envelope.evidenceSha256)
        assertEquals(
            listOf(
                "schemaVersion",
                "canonicalization",
                "hashAlgorithm",
                "resultType",
                "semanticCompleteness",
                "request",
                "requestSha256",
                "snapshotSha256",
                "reviewArtifactSha256",
                "evidenceSha256",
                "operationId",
                "authorityStatus",
                "evidenceKind",
                "managedWriteEligibility",
                "blockerCode",
                "nextActions",
                "evidenceFacts",
                "residualGuidance",
            ),
            canonicalEnvelope().keys.toList(),
        )
        assertEquals(
            envelope.evidenceFacts.sortedWith(compareBy({ it.type.name }, { it.normalizedPath.orEmpty() },
                { it.problemId ?: Int.MIN_VALUE }, { it.currentContentSha256.orEmpty() })),
            envelope.evidenceFacts,
        )
        assertTrue(envelope.evidenceFacts.any { it.type.name == "BROAD_SYNTAX_FAILURE" })

        val compatibilityArtifactHashes = compatibilityPreviews.map { preview ->
            JavaMoveClassLexicalFallbackReviewJsonProjection.reviewArtifactSha256(snapshot, preview.plan)
        }
        assertEquals(1, compatibilityArtifactHashes.toSet().size, "random PatchPlan IDs must be identity-neutral")

        val baselinePlan = compatibilityPreviews.first().plan
        var replacementChanged = false
        val changedReplacementEdit = WorkspaceEdit(baselinePlan.workspaceEdit.edits.map { edit ->
            if (!replacementChanged && edit is FileEdit.Modify && edit.textEdits.isNotEmpty()) {
                replacementChanged = true
                edit.copy(textEdits = edit.textEdits.mapIndexed { index, textEdit ->
                    if (index == 0) textEdit.copy(newText = textEdit.newText + " ") else textEdit
                })
            } else {
                edit
            }
        })
        assertTrue(replacementChanged, "lexical compatibility artifact must contain a text replacement")
        val changedReplacementPlan = baselinePlan.copy(workspaceEdit = changedReplacementEdit)
        fun replacementCoordinates(edit: WorkspaceEdit) = edit.edits
            .filterIsInstance<FileEdit.Modify>()
            .flatMap { modify -> modify.textEdits.map { textEdit -> modify.path to textEdit.range } }
        assertEquals(
            replacementCoordinates(baselinePlan.workspaceEdit),
            replacementCoordinates(changedReplacementPlan.workspaceEdit),
            "replacement identity probe must preserve every path and range",
        )
        assertNotEquals(
            compatibilityArtifactHashes.first(),
            JavaMoveClassLexicalFallbackReviewJsonProjection.reviewArtifactSha256(
                snapshot,
                changedReplacementPlan,
            ),
            "fixed-path/fixed-range replacement bytes must participate in the hidden artifact identity",
        )

        val createPath = Path.of("catalog-model/src/main/java/com/acme/catalog/api/ReviewArtifactProbe.java")
        val firstCreate = baselinePlan.copy(workspaceEdit = WorkspaceEdit(listOf(
            FileEdit.Create(createPath, "final class ReviewArtifactProbe {}\n"),
        )))
        val secondCreate = baselinePlan.copy(workspaceEdit = WorkspaceEdit(listOf(
            FileEdit.Create(createPath, "final class ReviewArtifactProbe { }\n"),
        )))
        assertNotEquals(
            JavaMoveClassLexicalFallbackReviewJsonProjection.reviewArtifactSha256(snapshot, firstCreate),
            JavaMoveClassLexicalFallbackReviewJsonProjection.reviewArtifactSha256(snapshot, secondCreate),
            "fixed-path Create content bytes must participate in the hidden artifact identity",
        )
    }

    @Then("every envelope has the same deterministic non-capability {string} derived from those identities and the fixed disposition, not a {string}")
    fun operationIdIsDeterministicAndNotAPlanId(operationField: String, rejectedType: String) {
        assertEquals("operationId", operationField)
        assertEquals("PlanId", rejectedType)
        val operationIds = allEnvelopeJson().map { it.string(operationField) }.toSet()
        assertEquals(1, operationIds.size)
        assertTrue(operationIds.single().matches(Regex("lexical-review-[a-f0-9]{64}")))
        compatibilityPreviews.forEach { preview ->
            assertFalse(canonicalEnvelopeText().contains(preview.plan.id.value))
        }
    }

    @Then("every envelope reports authority status {string}, evidence kind {string}, managed-write eligibility {string}, and blocker code {string}")
    fun envelopeHasFixedReviewDisposition(
        authority: String,
        evidence: String,
        eligibility: String,
        blocker: String,
    ) {
        assertEquals(JavaMoveClassLexicalReviewAuthorityStatus.REVIEW_ONLY.name, authority)
        assertEquals(JavaMoveClassLexicalReviewEvidenceKind.LEXICAL_FALLBACK.name, evidence)
        assertEquals(JavaMoveClassLexicalReviewManagedWriteEligibility.INELIGIBLE.name, eligibility)
        assertEquals(JavaMoveClassLexicalFallbackReviewEnvelope.BLOCKER_CODE, blocker)
        allEnvelopeJson().forEach { envelope ->
            assertEquals(authority, envelope.string("authorityStatus"))
            assertEquals(evidence, envelope.string("evidenceKind"))
            assertEquals(eligibility, envelope.string("managedWriteEligibility"))
            assertEquals(blocker, envelope.string("blockerCode"))
        }
    }

    @Then(
        "every envelope reports semantic completeness {string} and the fixed ordered actions {string}, " +
            "{string}, {string}, and {string}, while its edit-free residual guidance is capped at 200 records " +
            "and 262144 canonical UTF-8 record bytes, exposes explicit {string} and {string} fields, reports " +
            "deterministic {string}, and sets {string} to false on every record without claiming " +
            "semantic-reference or managed-edit capability",
    )
    fun residualGuidanceIsBoundedAndEditFree(
        semanticCompleteness: String,
        inspectResiduals: String,
        restoreSemanticEvidence: String,
        fullReactorRescan: String,
        requestNewPreview: String,
        recordCompletenessField: String,
        truncatedField: String,
        returnedRiskCategoryCountsField: String,
        managedEditField: String,
    ) {
        val expectedActions = listOf(
            "INSPECT_RESIDUALS",
            "RESTORE_SEMANTIC_EVIDENCE",
            "FULL_REACTOR_RESCAN",
            "REQUEST_NEW_PREVIEW",
        )
        assertEquals("NOT_SEMANTICALLY_PROVEN", semanticCompleteness)
        assertEquals(
            expectedActions,
            listOf(inspectResiduals, restoreSemanticEvidence, fullReactorRescan, requestNewPreview),
        )
        assertEquals("recordCompleteness", recordCompletenessField)
        assertEquals("truncated", truncatedField)
        assertEquals("returnedRiskCategoryCounts", returnedRiskCategoryCountsField)
        assertEquals("managedEdit", managedEditField)

        val envelope = canonicalEnvelope()
        assertEquals(semanticCompleteness, envelope.string("semanticCompleteness"))
        assertEquals(
            expectedActions,
            envelope.getValue("nextActions").jsonArray.map { it.jsonPrimitive.content },
        )

        val guidance = directEnvelopes.first().residualGuidance
        val guidanceJson = envelope.getValue("residualGuidance").jsonObject
        assertTrue(guidance.returnedRecordCount <= JavaMoveClassLexicalReviewResidualGuidance.MAX_RECORDS)
        assertTrue(guidance.canonicalRecordBytes <=
            JavaMoveClassLexicalReviewResidualGuidance.MAX_CANONICAL_RECORD_BYTES)
        assertEquals(guidance.returnedRecordCount, guidance.records.size)
        assertFalse(guidanceJson.containsKey("completeness"), "record enumeration must not imply semantics")
        assertEquals(guidance.truncated, guidanceJson.string(recordCompletenessField) == "TRUNCATED")
        assertEquals(
            if (guidance.truncated) {
                JavaMoveClassLexicalReviewRecordCompleteness.TRUNCATED
            } else {
                JavaMoveClassLexicalReviewRecordCompleteness.COMPLETE
            },
            guidance.recordCompleteness,
        )
        assertEquals(
            listOf(
                recordCompletenessField,
                truncatedField,
                "totalRecordCount",
                "returnedRecordCount",
                returnedRiskCategoryCountsField,
                "canonicalRecordBytes",
                "maxRecords",
                "maxCanonicalRecordBytes",
                "records",
            ),
            guidanceJson.keys.toList(),
        )

        val expectedRiskCategoryOrder = listOf(
            "DECLARATION_CANDIDATE",
            "JAVA_LEXICAL_CANDIDATE",
            "REVIEW_ARTIFACT_SOURCE",
            "SYNTAX_FAILURE_SITE",
        )
        val recordsJson = guidanceJson.getValue("records").jsonArray.map(JsonElement::jsonObject)
        val expectedCounts = expectedRiskCategoryOrder.associateWith { category ->
            recordsJson.count { record ->
                record.getValue("riskFacts").jsonArray.any { it.jsonPrimitive.content == category }
            }
        }
        val returnedCounts = guidanceJson.getValue(returnedRiskCategoryCountsField).jsonObject
        assertEquals(expectedRiskCategoryOrder, returnedCounts.keys.toList())
        assertEquals(expectedCounts, returnedCounts.mapValues { (_, count) -> count.jsonPrimitive.content.toInt() })
        assertEquals(expectedRiskCategoryOrder, guidance.returnedRiskCategoryCounts.keys.map { it.name })
        assertEquals(expectedCounts.values.toList(), guidance.returnedRiskCategoryCounts.values.toList())

        val recordCompletenessGetter = assertNotNull(
            guidance.javaClass.methods.singleOrNull { it.name == "getRecordCompleteness" },
        )
        assertTrue(recordCompletenessGetter.returnType.isEnum)
        assertEquals(guidanceJson.string(recordCompletenessField), recordCompletenessGetter.invoke(guidance).toString())
        val riskCountsGetter = assertNotNull(
            guidance.javaClass.methods.singleOrNull { it.name == "getReturnedRiskCategoryCounts" },
        )
        assertTrue(riskCountsGetter.genericReturnType.typeName.contains("JavaMoveClassLexicalReviewRiskFact"))
        @Suppress("UNCHECKED_CAST")
        val typedRiskCounts = riskCountsGetter.invoke(guidance) as Map<Any?, Any?>
        assertEquals(expectedRiskCategoryOrder, typedRiskCounts.keys.map(Any?::toString))
        assertEquals(expectedCounts.values.toList(), typedRiskCounts.values.toList())
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (typedRiskCounts as MutableMap<Any?, Any?>).clear()
        }

        assertTrue(guidance.records.isNotEmpty())
        guidance.records.zip(recordsJson).forEach { (record, recordJson) ->
            assertEquals(record.normalizedPath, Path.of(record.normalizedPath).normalize().invariantSeparatorsPathString)
            assertTrue(SHA256.matches(record.currentContentSha256))
            assertFalse(record.managedEdit)
            assertTrue(record.riskFacts.isNotEmpty())
            assertEquals(
                listOf("normalizedPath", "currentContentSha256", managedEditField, "riskFacts"),
                recordJson.keys.toList(),
            )
            assertFalse(recordJson.getValue(managedEditField).jsonPrimitive.content.toBooleanStrict())
        }
        val keys = recursiveKeys(envelope).map(String::lowercase)
        assertTrue(keys.none { it.contains("reference") || it == "edit" })
    }

    @Then("repeat preview with the same canonical inputs returns the same envelope and {string}")
    fun repeatPreviewIsStable(operationField: String) {
        previewProjections.forEach { (surface, projections) ->
            assertEquals(projections[0], projections[1], surface)
            assertEquals(projections[0].string(operationField), projections[1].string(operationField), surface)
        }
    }

    @Then("no envelope contains a {string}, {string}, {string}, pending-plan handle, {string}, affected edit, diff, replacement text, actionable apply token, transaction ID, or RefactorKit rollback capability")
    fun envelopeContainsNoManagedCapability(
        patchPlan: String,
        planId: String,
        refactorkitPlanId: String,
        workspaceEdit: String,
    ) {
        val forbiddenFragments = setOf(
            patchPlan, planId, refactorkitPlanId, "pendingPlan", workspaceEdit, "edit", "range", "diff",
            "replacement", "newText", "apply", "transactionId", "rollback",
        ).map(String::lowercase).toSet()
        allEnvelopeJson().forEach { envelope ->
            val keys = recursiveKeys(envelope).map(String::lowercase)
            assertTrue(keys.none { key ->
                key != "managededit" && forbiddenFragments.any { fragment -> key.contains(fragment) }
            }, keys.toString())
            envelope.getValue("residualGuidance").jsonObject.getValue("records").jsonArray.forEach { record ->
                assertFalse(record.jsonObject.getValue("managedEdit").jsonPrimitive.content.toBooleanStrict())
            }
        }
        val structuralSurface = directEnvelopes.first().javaClass.declaredFields.map { it.genericType.typeName } +
            directEnvelopes.first().javaClass.methods.map { "${it.name}:${it.genericReturnType.typeName}" }
        listOf("PatchPlan", "PlanId", "WorkspaceEdit", "Transaction", "Rollback").forEach { token ->
            assertTrue(structuralSurface.none { it.contains(token, ignoreCase = true) }, structuralSurface.toString())
        }
    }

    @Then("daemon JSON-RPC, managed LSP, and MCP preview create no pending managed plan, while managed LSP returns neither {string} nor {string}")
    fun protocolPreviewsExposeNoPendingPlanOrLspEdit(changes: String, documentChanges: String) {
        listOf("daemon JSON-RPC", "managed LSP", "MCP").forEach { surface ->
            previewProjections.getValue(surface).forEach { projection ->
                assertFalse(projection.containsKey("planId"), surface)
                assertFalse(projection.containsKey("refactorkitPlanId"), surface)
            }
        }
        previewProjections.getValue("managed LSP").forEach { projection ->
            assertFalse(projection.containsKey(changes))
            assertFalse(projection.containsKey(documentChanges))
        }
    }

    @Then("any retained {string} correlation is bounded, edit-free, audit-only, and separate from pending managed plans")
    fun retainedCorrelationIsBoundedAuditOnly(operationField: String) {
        val first = directEnvelopes.first()
        assertEquals(canonicalEnvelope().string(operationField), first.operationId)
        assertEquals(1, auditCache.entryCount())
        assertTrue(auditCache.entryCount() <= JavaMoveClassLexicalReviewAuditCache.MAX_ENTRIES)
        assertTrue(auditCache.canonicalByteCount() <= JavaMoveClassLexicalReviewAuditCache.MAX_CANONICAL_BYTES)
        assertEquals(first, auditCache.find(first.operationId))

        val alternateDispatcher = JavaMoveClassOperationDispatcher()
        val alternateEnvelopes = List(2) {
            assertIs<JavaMoveClassOperationOutcome.LexicalReview>(
                alternateDispatcher.preview(snapshot, PRODUCT_FQN, ALTERNATE_TARGET_PACKAGE),
            ).envelope
        }
        assertEquals(alternateEnvelopes[0], alternateEnvelopes[1])
        val second = alternateEnvelopes.first()
        assertNotEquals(first.operationId, second.operationId)

        val byteCap = maxOf(
            JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(first).size,
            JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(second).size,
        )
        val oneEntryCache = JavaMoveClassLexicalReviewAuditCache(
            maxEntries = 1,
            maxCanonicalBytes = byteCap,
        )
        oneEntryCache.put(first)
        assertEquals(first, oneEntryCache.find(first.operationId))
        oneEntryCache.put(second)
        assertEquals(null, oneEntryCache.find(first.operationId), "the eldest lexical operation must be evicted")
        assertEquals(second, oneEntryCache.find(second.operationId))
        assertEquals(1, oneEntryCache.entryCount())
        assertEquals(
            JavaMoveClassLexicalFallbackReviewJsonProjection.canonicalBytes(second).size,
            oneEntryCache.canonicalByteCount(),
        )
        assertTrue(oneEntryCache.canonicalByteCount() <= oneEntryCache.maxCanonicalBytes)
        val retainedKeys = recursiveKeys(
            JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(assertNotNull(oneEntryCache.find(second.operationId))),
        ).map(String::lowercase)
        assertTrue(retainedKeys.none { it in setOf("patchplan", "planid", "workspaceedit", "edits", "newtext") })
    }

    @Then("CLI preview renders the envelope without an actionable apply token")
    fun cliPreviewRendersOnlyTheEnvelope() {
        previewProjections.getValue("CLI").forEach { assertEquals(canonicalEnvelope(), it) }
        val preview = runCli(moveClassCliArguments(apply = false))
        assertEquals(0, preview.exitCode)
        assertEquals(canonicalEnvelope(), parseEnvelope(preview.stdout))
        assertFalse(preview.stdout.contains("Use --apply"))
        assertFalse(preview.stdout.contains("applyToken", ignoreCase = true))
    }

    @When("approval, warning acknowledgement, confidence, or force metadata is attached without changing the canonical inputs")
    fun attachPromotionAttemptMetadata() {
        val metadata = JavaMoveClassPromotionAttemptMetadata(
            approval = JavaMoveClassPromotionApproval.APPROVED,
            warningAcknowledgement = JavaMoveClassWarningAcknowledgement.ACKNOWLEDGED,
            confidence = JavaMoveClassPromotionConfidence(0.99),
            force = JavaMoveClassForceRequest.REQUESTED,
        )
        metadataOutcome = dispatcher.preview(snapshot, PRODUCT_FQN, TARGET_PACKAGE, metadata)
        metadataProjections["Java move-class outcome API"] = JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(
            assertIs<JavaMoveClassOperationOutcome.LexicalReview>(metadataOutcome).envelope,
        )
        metadataProjections["CLI"] = parseEnvelope(runCli(
            moveClassCliArguments(apply = false) +
                listOf("--approve", "--acknowledge-warning", "--force", "--confidence", "0.99"),
        ).also { assertEquals(0, it.exitCode, it.failureMessage("CLI metadata preview")) }.stdout)
        metadataProjections["daemon JSON-RPC"] = daemonSession!!.dispatch(
            "refactor.preview",
            daemonPreviewParams(withMetadata = true),
        ).jsonObject
        metadataProjections["managed LSP"] = lspSession!!.dispatch(
            "workspace/executeCommand",
            lspMoveClassParams(withMetadata = true),
        ).jsonObject
        val mcp = mcpToolCall(mcpSession!!, "preview_refactoring", mcpPreviewArguments(withMetadata = true))
        assertFalse(mcp.getValue("isError").jsonPrimitive.content.toBoolean())
        metadataProjections["MCP"] = mcp.getValue("structuredContent").jsonObject

        val validRecipeMetadata = mapOf(
            "symbol" to PRODUCT_FQN,
            "to" to TARGET_PACKAGE,
            "approval" to "true",
            "warningAcknowledgement" to "true",
            "confidence" to "0.99",
            "force" to "true",
        )
        recipeMetadataOutcome = RecipeEngine().run(
            RecipeDefinition(
                "req-004-valid-metadata",
                "REQ-004 valid promotion metadata",
                steps = listOf(StepDef("moveClass", validRecipeMetadata)),
            ),
            emptyMap(),
            workspaceRoot,
        )
        metadataProjections["recipe"] = JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(
            assertIs<RecipeResult.NonManaged>(recipeMetadataOutcome).envelope,
        )
        invalidRecipeMetadataResults = listOf("not-a-number", "-0.01", "1.01").map { supplied ->
            assertIs<RecipeResult.Failed>(RecipeEngine().run(
                RecipeDefinition(
                    "req-004-invalid-confidence-${supplied.replace('.', '-')}",
                    "REQ-004 invalid promotion confidence",
                    steps = listOf(StepDef("moveClass", validRecipeMetadata + ("confidence" to supplied))),
                ),
                emptyMap(),
                workspaceRoot,
            ))
        }
    }

    @Then("the envelope, every SHA-256 identity, the {string}, and the review-only disposition remain unchanged")
    fun promotionMetadataDoesNotChangeEnvelope(operationField: String) {
        metadataProjections.forEach { (surface, projection) ->
            assertEquals(canonicalEnvelope(), projection, surface)
            assertEquals(canonicalEnvelope().string(operationField), projection.string(operationField), surface)
            listOf("requestSha256", "snapshotSha256", "reviewArtifactSha256", "evidenceSha256").forEach { field ->
                assertEquals(canonicalEnvelope().string(field), projection.string(field), "$surface:$field")
            }
        }
    }

    @Then("no metadata converts the envelope to a semantic preview or managed plan")
    fun metadataCannotPromoteLexicalReview() {
        assertIs<JavaMoveClassOperationOutcome.LexicalReview>(metadataOutcome)
        assertIs<RecipeResult.NonManaged>(recipeMetadataOutcome)
        metadataProjections.values.forEach { projection ->
            assertEquals("LEXICAL_FALLBACK_REVIEW", projection.string("resultType"))
            assertEquals("INELIGIBLE", projection.string("managedWriteEligibility"))
            assertFalse(projection.containsKey("planId"))
        }
        assertEquals(3, invalidRecipeMetadataResults.size)
        invalidRecipeMetadataResults.forEach { failure ->
            assertTrue(failure.reason.contains("confidence"), failure.reason)
            assertTrue(failure.stepPlans.isEmpty())
            assertEquals(null, failure.recipePlan)
        }
        assertEquals(recordedState, captureWorkspaceState(JavaProjectScanner().scan(workspaceRoot)))
    }

    @Then("any direct compatibility-plan apply is refused by the core lexical-evidence gate before workspace-lock acquisition and write-ahead-log creation")
    fun compatibilityPlanApplyIsRejectedAtCoreGate() {
        compatibilityApply = PatchEngine(workspaceRoot).apply(
            compatibilityPreviews.first().plan,
            snapshot,
            ApplyAuthorization.explicit("req-004-compatibility-defense"),
            DiagnosticsGate.disabled("must-not-run"),
        )
        val refusal = assertIs<ApplyResult.Refused>(compatibilityApply)
        assertEquals("evidence.insufficient", refusal.diagnostics.single().code)
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @When("a recipe reaches the lexical moveClass step in each position:")
    fun recipeReachesLexicalMoveStepInEachPosition(table: DataTable) {
        assertEquals(
            listOf(
                "as its first step",
                "after an earlier valid step has produced only an in-memory staged image",
            ),
            table.asMaps().map { it.getValue("recipe position") },
        )
        val move = StepDef("moveClass", mapOf("symbol" to PRODUCT_FQN, "to" to TARGET_PACKAGE))
        val later = StepDef("summarizePatch")
        recipeDefinitions = listOf(
            RecipeDefinition("req-004-first", "REQ-004 first", steps = listOf(move, later)),
            RecipeDefinition(
                "req-004-after-staged",
                "REQ-004 after staged",
                steps = listOf(
                    StepDef("movePackage", mapOf("from" to "com.acme.decoy", "to" to "com.acme.decoy.staged")),
                    move,
                    later,
                ),
            ),
        )
        recipeResults = recipeDefinitions.map { recipe ->
            assertIs<RecipeResult.NonManaged>(RecipeEngine().run(recipe, emptyMap(), workspaceRoot, dryRun = false))
        }
    }

    @Then("recipe evaluation stops at that lexical step and returns its {string} directly instead of a recipe aggregate or plan")
    fun recipeStopsWithTypedNonManagedEnvelope(resultType: String) {
        assertEquals(2, recipeResults.size)
        recipeResults.forEach { result ->
            assertEquals(resultType, result.envelope.resultType.name)
            assertEquals(null, result.recipePlan)
            assertEquals("moveClass", result.stepPlans.last().stepType)
            assertEquals(resultType, result.stepPlans.last().message)
            assertTrue(result.stepPlans.all { it.plan == null })

            val expectedSteps = result.stepPlans.toList()
            val callerOwnedSteps = result.stepPlans.toMutableList()
            val immutabilityProbe = RecipeResult.NonManaged(callerOwnedSteps, result.envelope)
            val planBearingStep = result.stepPlans.last().copy(plan = compatibilityPreviews.first().plan)
            assertFailsWith<UnsupportedOperationException> {
                @Suppress("UNCHECKED_CAST")
                (immutabilityProbe.stepPlans as MutableList<Any?>).add(planBearingStep)
            }
            assertEquals(expectedSteps, immutabilityProbe.stepPlans)
            assertTrue(immutabilityProbe.stepPlans.all { it.plan == null })

            callerOwnedSteps += planBearingStep
            assertEquals(expectedSteps, immutabilityProbe.stepPlans)
            assertTrue(immutabilityProbe.stepPlans.all { it.plan == null })
        }
        assertEquals(canonicalEnvelope(), JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(
            recipeResults.first().envelope,
        ))
    }

    @Then("any earlier staged image is discarded, no later step runs, and no pending plan or transaction is created")
    fun recipeDiscardsStagedImageAndCreatesNoTransaction() {
        assertEquals(
            listOf(
                listOf("moveClass", "summarizePatch"),
                listOf("movePackage", "moveClass", "summarizePatch"),
            ),
            recipeDefinitions.map { recipe -> recipe.steps.map { it.type } },
        )
        assertEquals(listOf("moveClass"), recipeResults[0].stepPlans.map { it.stepType })
        assertEquals(listOf("movePackage", "moveClass"), recipeResults[1].stepPlans.map { it.stepType })
        val earlier = recipeResults[1].stepPlans.first()
        assertEquals(null, earlier.plan)
        assertEquals(
            "Move package com.acme.decoy -> com.acme.decoy.staged: units=1, class=1, package-info=0.",
            earlier.message,
        )
        assertTrue(recipeResults.all { result -> result.stepPlans.all { it.plan == null } })
        assertTrue(recipeResults.all { result -> result.stepPlans.none { it.stepType == "summarizePatch" } })
        assertEquals(recordedState, captureWorkspaceState(JavaProjectScanner().scan(workspaceRoot)))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @When("the CLI apply command is invoked for the unchanged move")
    fun invokeCliApply() {
        cliApply = runCli(moveClassCliArguments(apply = true))
    }

    @Then("the CLI exits non-zero with {string} before {string}, workspace-lock acquisition, and write-ahead-log creation")
    fun cliApplyFailsBeforeManagedWrite(blocker: String, patchEngine: String) {
        assertEquals("PatchEngine", patchEngine)
        val result = assertNotNull(cliApply)
        assertTrue(result.exitCode != 0, result.failureMessage("CLI lexical apply"))
        assertTrue(result.stderr.contains(blocker), result.failureMessage("CLI lexical blocker"))
        assertEquals(canonicalEnvelope(), parseEnvelope(result.stdout))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @When("each still-known {string} returned by daemon JSON-RPC, managed LSP, or MCP preview is submitted to that surface's apply entry point")
    fun submitKnownOperationIdToEveryApplyEntry(operationField: String) {
        assertEquals("operationId", operationField)
        val operationId = canonicalEnvelope().string(operationField)

        val applyTool = mcpSession!!.dispatch("tools/list", null).jsonObject.getValue("tools").jsonArray
            .map(JsonElement::jsonObject).single { it.string("name") == "apply_refactoring" }
        val applySchema = applyTool.getValue("inputSchema").jsonObject
        assertTrue(applySchema.getValue("properties").jsonObject.keys.containsAll(setOf("operationId", "planId")))
        assertTrue(applySchema.getValue("required").jsonArray.isEmpty())
        val identityAlternatives = applySchema.getValue("oneOf").jsonArray.map { alternative ->
            alternative.jsonObject.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
        }.toSet()
        assertEquals(setOf(setOf("operationId"), setOf("planId")), identityAlternatives)

        daemonKnownRefusal = assertFailsWith {
            daemonSession!!.dispatch("refactor.apply", buildJsonObject { put(operationField, operationId) })
        }
        lspKnownRefusal = assertFailsWith {
            lspSession!!.dispatch("workspace/executeCommand", lspApplyParams(operationField to operationId))
        }
        mcpKnownRefusal = mcpToolCall(mcpSession!!, "apply_refactoring", buildJsonObject {
            put(operationField, operationId)
        })
    }

    @Then("each surface returns the same envelope with typed {string} before {string}, workspace-lock acquisition, write-ahead-log creation, editor application, or mutation")
    fun knownOperationRefusalsReturnTheSameEnvelope(blocker: String, patchEngine: String) {
        assertEquals("PatchEngine", patchEngine)
        listOf(assertNotNull(daemonKnownRefusal), assertNotNull(lspKnownRefusal)).forEach { failure ->
            assertEquals(blocker, failure.message)
            assertEquals(canonicalEnvelope(), assertNotNull(failure.data).jsonObject
                .getValue("envelope").jsonObject)
        }
        val mcpStructured = assertNotNull(mcpKnownRefusal).getValue("structuredContent").jsonObject
        assertEquals(canonicalEnvelope(), mcpStructured.getValue("envelope").jsonObject)
        assertEquals(recordedState, captureWorkspaceState(JavaProjectScanner().scan(workspaceRoot)))
    }

    @Then("daemon JSON-RPC and managed LSP return {string} code -32008 with structured data containing the blocker and envelope")
    fun daemonAndLspReturnTypedStructuredRefusal(errorName: String) {
        assertEquals("PLAN_VALIDATION_FAILED", errorName)
        listOf(assertNotNull(daemonKnownRefusal), assertNotNull(lspKnownRefusal)).forEach { failure ->
            assertEquals(JsonRpcErrorCodes.PLAN_VALIDATION_FAILED, failure.code)
            val data = assertNotNull(failure.data).jsonObject
            assertEquals("evidence.insufficient", data.getValue("blocker").jsonObject.string("code"))
            assertEquals(canonicalEnvelope(), data.getValue("envelope").jsonObject)
            assertEquals(data, errorResponse(JsonPrimitive("req-004"), failure).error?.data)
        }
    }

    @Then("MCP returns {string} true with structured data containing the blocker and envelope")
    fun mcpReturnsStructuredToolError(errorField: String) {
        val result = assertNotNull(mcpKnownRefusal)
        assertEquals("isError", errorField)
        assertTrue(result.getValue(errorField).jsonPrimitive.content.toBoolean())
        val data = result.getValue("structuredContent").jsonObject
        assertEquals("evidence.insufficient", data.getValue("blocker").jsonObject.string("code"))
        assertEquals(canonicalEnvelope(), data.getValue("envelope").jsonObject)
    }

    @Then("an arbitrary unknown {string}, including {string}, remains {string} code -32602 and is never treated as known lexical evidence")
    fun unknownPlanIdRemainsInvalidParams(field: String, unknown: String, errorName: String) {
        assertEquals("planId", field)
        assertEquals("plan-lexical", unknown)
        assertEquals("INVALID_PARAMS", errorName)
        daemonUnknownRefusal = assertFailsWith {
            daemonSession!!.dispatch("refactor.apply", buildJsonObject { put(field, unknown) })
        }
        lspUnknownRefusal = assertFailsWith {
            lspSession!!.dispatch("workspace/executeCommand", lspApplyParams(field to unknown))
        }
        mcpUnknownRefusal = assertFailsWith {
            mcpToolCall(mcpSession!!, "apply_refactoring", buildJsonObject { put(field, unknown) })
        }
        listOf(daemonUnknownRefusal, lspUnknownRefusal, mcpUnknownRefusal).forEach { failure ->
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, assertNotNull(failure).code)
            assertEquals(null, failure.data)
        }

        val unknownOperationId = "lexical-review-${"0".repeat(64)}"
        assertNotEquals(canonicalEnvelope().string("operationId"), unknownOperationId)
        val unknownOperationFailures = listOf(
            assertFailsWith<JsonRpcException> {
                daemonSession!!.dispatch("refactor.apply", buildJsonObject {
                    put("operationId", unknownOperationId)
                })
            },
            assertFailsWith<JsonRpcException> {
                lspSession!!.dispatch(
                    "workspace/executeCommand",
                    lspApplyParams("operationId" to unknownOperationId),
                )
            },
            assertFailsWith<JsonRpcException> {
                mcpToolCall(mcpSession!!, "apply_refactoring", buildJsonObject {
                    put("operationId", unknownOperationId)
                })
            },
        )
        unknownOperationFailures.forEach { failure ->
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, failure.code)
            assertEquals(null, failure.data)
        }

        val bothFields = buildJsonObject {
            put("operationId", canonicalEnvelope().string("operationId"))
            put("planId", unknown)
        }
        val exclusiveIdentityFailures = listOf(
            assertFailsWith<JsonRpcException> {
                daemonSession!!.dispatch("refactor.apply", buildJsonObject {})
            },
            assertFailsWith<JsonRpcException> {
                daemonSession!!.dispatch("refactor.apply", bothFields)
            },
            assertFailsWith<JsonRpcException> {
                lspSession!!.dispatch("workspace/executeCommand", lspApplyParams())
            },
            assertFailsWith<JsonRpcException> {
                lspSession!!.dispatch(
                    "workspace/executeCommand",
                    lspApplyParams(
                        "operationId" to canonicalEnvelope().string("operationId"),
                        "planId" to unknown,
                    ),
                )
            },
            assertFailsWith<JsonRpcException> {
                mcpToolCall(mcpSession!!, "apply_refactoring", buildJsonObject {})
            },
            assertFailsWith<JsonRpcException> {
                mcpToolCall(mcpSession!!, "apply_refactoring", bothFields)
            },
        )
        exclusiveIdentityFailures.forEach { failure ->
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, failure.code)
            assertEquals(null, failure.data)
        }
    }

    @Then("after every preview or refused apply, every workspace byte, path, inventory entry, and snapshot hash equals the recorded state")
    fun everyPreviewAndRefusalPreservesWorkspace() {
        assertEquals(recordedState, captureWorkspaceState(JavaProjectScanner().scan(workspaceRoot)))

        val operationId = directEnvelopes.first().operationId
        daemonSession!!.dispatch("project.open", buildJsonObject { put("root", workspaceRoot.toString()) })
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, assertFailsWith<JsonRpcException> {
            daemonSession!!.dispatch("refactor.apply", buildJsonObject { put("operationId", operationId) })
        }.code)
        lspSession!!.dispatch("initialize", lspInitializeParams())
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, assertFailsWith<JsonRpcException> {
            lspSession!!.dispatch("workspace/executeCommand", lspApplyParams("operationId" to operationId))
        }.code)
        mcpToolCall(mcpSession!!, "project_scan", buildJsonObject { put("root", workspaceRoot.toString()) })
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, assertFailsWith<JsonRpcException> {
            mcpToolCall(mcpSession!!, "apply_refactoring", buildJsonObject { put("operationId", operationId) })
        }.code)
        auditCache.clear()
        assertEquals(0, auditCache.entryCount())
        assertEquals(0, auditCache.canonicalByteCount())
        assertEquals(recordedState, captureWorkspaceState(JavaProjectScanner().scan(workspaceRoot)))
    }

    @Then("no {string} directory, lock file, write-ahead log, managed transaction, or RefactorKit rollback claim is created")
    fun noManagedWriteResidue(directory: String) {
        assertEquals(".refactorkit", directory)
        assertFalse(Files.exists(workspaceRoot.resolve(directory), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/workspace.lock"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(workspaceRoot.resolve(".refactorkit/transactions"), LinkOption.NOFOLLOW_LINKS))
    }

    @Then("{string} remains distinct from REQ-003 {string} and never uses its {string} contract")
    fun lexicalReviewRemainsDistinctFromReq003(
        lexicalResult: String,
        guidanceResult: String,
        guidanceBlocker: String,
    ) {
        assertEquals("LEXICAL_FALLBACK_REVIEW", lexicalResult)
        assertEquals("REVIEW_ONLY_GUIDANCE", guidanceResult)
        assertEquals("guidance.nonManaged", guidanceBlocker)
        assertEquals(lexicalResult, directEnvelopes.first().resultType.name)
        assertNotEquals(guidanceResult, directEnvelopes.first().resultType.name)
        assertEquals("evidence.insufficient", directEnvelopes.first().blockerCode)
        assertFalse(canonicalEnvelopeText().contains(guidanceBlocker))
    }

    @Then("a fresh full-reactor scan and new preview with complete exact bindings retain the existing {string}, {string}, {string} managed-plan behavior")
    fun cleanBindingsRetainExistingManagedPlan(
        resultType: String,
        evidence: String,
        eligibility: String,
    ) {
        assertEquals("SEMANTIC_PREVIEW", resultType)
        assertEquals("JDT_BINDING", evidence)
        assertEquals("ELIGIBLE", eligibility)
        val cleanSnapshot = JavaProjectScanner().scan(fixtureTemplate)
        val cleanOutcome = JavaMoveClassOperationDispatcher().preview(
            cleanSnapshot,
            PRODUCT_FQN,
            TARGET_PACKAGE,
        )
        val plan = assertIs<JavaMoveClassOperationOutcome.Plan>(cleanOutcome).preview.plan
        assertEquals(PatchStatus.PREVIEW, plan.status)
        assertEquals(RefactoringEvidence.JDT_BINDING, plan.evidence)
        assertTrue(plan.workspaceEdit.edits.isNotEmpty())
        assertEquals(permanentFixtureIdentity, treeIdentity(fixtureTemplate))
        assertFalse(Files.exists(fixtureTemplate.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    private fun expectedEvidenceSha256(envelope: JsonObject): String = sha256(
        json.encodeToString(buildJsonObject {
            put("schemaVersion", envelope.getValue("schemaVersion"))
            put("canonicalization", envelope.getValue("canonicalization"))
            put("hashAlgorithm", envelope.getValue("hashAlgorithm"))
            put("resultType", envelope.getValue("resultType"))
            put("semanticCompleteness", envelope.getValue("semanticCompleteness"))
            put("authorityStatus", envelope.getValue("authorityStatus"))
            put("evidenceKind", envelope.getValue("evidenceKind"))
            put("managedWriteEligibility", envelope.getValue("managedWriteEligibility"))
            put("blockerCode", envelope.getValue("blockerCode"))
            put("nextActions", envelope.getValue("nextActions"))
            put("requestSha256", envelope.getValue("requestSha256"))
            put("snapshotSha256", envelope.getValue("snapshotSha256"))
            put("reviewArtifactSha256", envelope.getValue("reviewArtifactSha256"))
            put("evidenceFacts", envelope.getValue("evidenceFacts"))
            put("residualGuidance", envelope.getValue("residualGuidance"))
        }).toByteArray(Charsets.UTF_8),
    )

    private fun allEnvelopeJson(): List<JsonObject> = previewProjections.values.flatten()

    private fun canonicalEnvelope(): JsonObject = JavaMoveClassLexicalFallbackReviewJsonProjection.toJson(
        directEnvelopes.first(),
    )

    private fun canonicalEnvelopeText(): String = json.encodeToString(canonicalEnvelope())

    private fun daemonPreviewParams(withMetadata: Boolean = false): JsonObject = buildJsonObject {
        put("operation", "moveClass")
        put("symbol", PRODUCT_FQN)
        put("arguments", buildJsonObject {
            put("targetPackage", TARGET_PACKAGE)
            if (withMetadata) {
                put("approval", true)
                put("warningAcknowledgement", true)
                put("confidence", 0.99)
                put("force", true)
            }
        })
    }

    private fun lspInitializeParams(): JsonObject = buildJsonObject {
        put("rootUri", workspaceRoot.toUri().toString())
        put("capabilities", buildJsonObject {
            put("workspace", buildJsonObject {
                put("workspaceEdit", buildJsonObject { put("documentChanges", true) })
            })
        })
    }

    private fun lspMoveClassParams(withMetadata: Boolean = false): JsonObject = buildJsonObject {
        put("command", "refactorkit.moveClass")
        put("arguments", buildJsonArray {
            add(buildJsonObject {
                put("symbol", PRODUCT_FQN)
                put("targetPackage", TARGET_PACKAGE)
                if (withMetadata) {
                    put("approval", true)
                    put("warningAcknowledgement", true)
                    put("confidence", 0.99)
                    put("force", true)
                }
            })
        })
    }

    private fun lspApplyParams(vararg identities: Pair<String, String>): JsonObject = buildJsonObject {
        put("command", "refactorkit.applyPlan")
        put("arguments", buildJsonArray {
            add(buildJsonObject {
                identities.forEach { (field, value) -> put(field, value) }
            })
        })
    }

    private fun mcpPreviewArguments(withMetadata: Boolean = false): JsonObject = buildJsonObject {
        put("operation", "moveClass")
        put("symbol", PRODUCT_FQN)
        put("arguments", buildJsonObject {
            put("targetPackage", TARGET_PACKAGE)
            if (withMetadata) {
                put("approval", true)
                put("warningAcknowledgement", true)
                put("confidence", 0.99)
                put("force", true)
            }
        })
    }

    private fun mcpToolCall(session: McpSession, name: String, arguments: JsonObject): JsonObject =
        session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject

    private fun moveClassCliArguments(apply: Boolean): List<String> = buildList {
        add("move-class")
        add("--symbol")
        add(PRODUCT_FQN)
        add("--to-package")
        add(TARGET_PACKAGE)
        add(if (apply) "--apply" else "--preview")
        add(workspaceRoot.toString())
    }

    private fun runCli(arguments: List<String>): CliResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        return try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            val exit = RefactorKitCli().run(arguments)
            CliResult(exit, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private fun parseEnvelope(text: String): JsonObject = json.parseToJsonElement(text.trim()).jsonObject

    private fun captureWorkspaceState(currentSnapshot: ProjectSnapshot): WorkspaceState {
        val paths = Files.walk(workspaceRoot).use { stream ->
            stream.map { path ->
                val relative = workspaceRoot.relativize(path).invariantSeparatorsPathString.ifBlank { "." }
                when {
                    Files.isSymbolicLink(path) -> PathState(relative, "SYMLINK", Files.readSymbolicLink(path).toString())
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> PathState(relative, "DIRECTORY", "")
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                        PathState(relative, "FILE", sha256(Files.readAllBytes(path)))
                    else -> PathState(relative, "OTHER", "")
                }
            }.sorted { left, right -> left.path.compareTo(right.path) }.toList()
        }
        val inventory = currentSnapshot.files.sortedBy { it.path.invariantSeparatorsPathString }.map { file ->
            InventoryState(file.path.invariantSeparatorsPathString, sha256(file.content.toByteArray(Charsets.UTF_8)))
        }
        return WorkspaceState(paths, inventory, currentSnapshot.hash)
    }

    private fun treeIdentity(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { stream ->
            stream.sorted().forEach { path ->
                val relative = root.relativize(path).invariantSeparatorsPathString
                digest.update(relative.toByteArray(Charsets.UTF_8))
                digest.update(0)
                when {
                    Files.isSymbolicLink(path) -> digest.update(Files.readSymbolicLink(path).toString().toByteArray(Charsets.UTF_8))
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> digest.update(Files.readAllBytes(path))
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> digest.update("DIRECTORY".toByteArray(Charsets.UTF_8))
                }
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun recursiveKeys(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.entries.flatMap { (key, value) -> listOf(key) + recursiveKeys(value) }
        is JsonArray -> element.flatMap(::recursiveKeys)
        else -> emptyList()
    }

    private fun assertPomDependency(module: String, dependency: String) {
        val pom = Files.readString(workspaceRoot.resolve(module).resolve("pom.xml"))
        assertTrue(pom.contains("<artifactId>$dependency</artifactId>"), "$module -> $dependency")
    }

    private fun locateRepositoryRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("settings.gradle.kts")) &&
                Files.isDirectory(current.resolve(FIXTURE_PATH))
            ) return current
            current = current.parent ?: return@repeat
        }
        error("Cannot locate RefactorKit repository root")
    }

    private fun copyRecursively(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(destination)
                else {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.int(name: String): Int = string(name).toInt()

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String) {
        fun failureMessage(context: String): String = "$context exit=$exitCode stdout=[$stdout] stderr=[$stderr]"
    }

    private data class WorkspaceState(
        val paths: List<PathState>,
        val inventory: List<InventoryState>,
        val snapshotSha256: String,
    )

    private data class PathState(val path: String, val kind: String, val identity: String)
    private data class InventoryState(val path: String, val contentSha256: String)

    private companion object {
        const val FIXTURE_PATH = "testdata/acceptance/java-maven-move-class-authority-20-modules"
        const val OBSERVER_PATH =
            "catalog-acceptance/src/test/java/com/acme/catalog/acceptance/ProductLifecycleSteps.java"
        const val PRODUCT_FQN = "com.acme.catalog.legacy.Product"
        const val TARGET_PACKAGE = "com.acme.catalog.api"
        const val ALTERNATE_TARGET_PACKAGE = "com.acme.catalog.alt"
        const val TARGET_FQN = "com.acme.catalog.api.Product"
        const val PRODUCT_SOURCE_PATH = "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java"
        const val PRODUCT_TARGET_PATH = "catalog-model/src/main/java/com/acme/catalog/api/Product.java"
        const val EXTERNAL_ARTIFACT_PATH = "fixture-libs/catalog-price-contract-1.0.0.jar"
        const val GENERATED_SOURCE_PATH =
            "catalog-generated-support/target/generated-sources/catalog-metadata/" +
                "com/acme/catalog/generated/GeneratedCatalogMarker.java"
        val MODULE_PATTERN = Regex("<module>([^<]+)</module>")
        val PRODUCT_DECLARATION = Regex("\\bclass\\s+Product\\b")
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}

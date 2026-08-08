package org.refactorkit.lsp.mavenmodulerenamesurface004

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.FileEdit
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.PendingPlanStore
import org.refactorkit.core.PlanId
import org.refactorkit.core.RefactorKitVersion
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.lsp.LspSession
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64

/**
 * Story-BDD glue for the bounded SURFACE-004 LSP proposal contract.
 *
 * The protocol oracle and expected client-owned post-image are constructed from literal fixture
 * paths and ranges before a proposal is requested. Production is exercised only through
 * [LspSession.dispatch]. The editor simulation performs ordinary filesystem operations from the
 * returned LSP documentChanges; it never calls a RefactorKit apply, transaction, or lifecycle API.
 */
class JavaMavenModuleRenameSurface004Steps {
    private data class Node(val kind: String, val contentBase64: String? = null)

    private data class LiteralModify(
        val path: String,
        val startLine: Int,
        val startCharacter: Int,
        val endLine: Int,
        val endCharacter: Int,
        val oldText: String,
        val newText: String,
    )

    private data class LiteralRename(val oldPath: String, val newPath: String)

    private val literalModifies = listOf(
        LiteralModify("catalog-model/pom.xml", 9, 14, 9, 27, "catalog-model", "catalog-domain"),
        LiteralModify("pom.xml", 74, 12, 74, 25, "catalog-model", "catalog-domain"),
        LiteralModify("catalog-pricing/pom.xml", 13, 18, 13, 31, "catalog-model", "catalog-domain"),
    )
    private val literalRenames = listOf(
        LiteralRename("catalog-model/pom.xml", "catalog-domain/pom.xml"),
        LiteralRename(
            "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
            "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
        ),
    )

    private lateinit var fixtureRoot: Path
    private lateinit var versionRoot: Path
    private lateinit var legacyRoot: Path
    private lateinit var fixtureS0: Map<String, Node>
    private lateinit var versionS0: Map<String, Node>
    private lateinit var legacyS0: Map<String, Node>
    private lateinit var expectedClientPostImage: Map<String, Node>
    private lateinit var protocolOracle: JsonArray
    private lateinit var versionSession: LspSession
    private lateinit var legacySession: LspSession
    private lateinit var proposal: JsonObject
    private var retainedPlan: PatchPlan? = null
    private var legacyFailure: JsonRpcException? = null
    private var legacyResult: JsonElement? = null
    private val versionNotifications = mutableListOf<String>()
    private val legacyNotifications = mutableListOf<String>()
    private val executeCommands = mutableListOf<String>()
    private val temporaryRoots = mutableListOf<Path>()
    private var clientSimulationCompleted = false

    @Given("each case uses a fresh no-follow disposable byte copy of {string}")
    fun freshNoFollowCopies(fixture: String) {
        check(fixture == "testdata/acceptance/java-maven-move-class-authority-20-modules")
        fixtureRoot = repositoryRoot().resolve(fixture).toAbsolutePath().normalize()
        fixtureS0 = snapshotNoFollow(fixtureRoot)
        versionRoot = freshCopy("rk-lsp-surface004-version-")
        legacyRoot = freshCopy("rk-lsp-surface004-legacy-")
        versionS0 = snapshotNoFollow(versionRoot)
        legacyS0 = snapshotNoFollow(legacyRoot)
        check(versionS0 == fixtureS0) { "version-capable fixture copy is not exact S0" }
        check(legacyS0 == fixtureS0) { "legacy fixture copy is not exact S0" }
    }

    @Given("the permanent fixture remains an immutable offline reactor with one root aggregator and exactly 20 direct non-aggregator JAR children")
    fun permanentFixtureShape() {
        val rootPom = Files.readString(fixtureRoot.resolve("pom.xml"), StandardCharsets.UTF_8)
        check(Regex("<packaging>pom</packaging>").containsMatchIn(rootPom))
        val modules = Regex("<module>([^<]+)</module>").findAll(rootPom).map { it.groupValues[1] }.toList()
        check(modules.size == 20) { "expected 20 direct children, found ${modules.size}" }
        check(modules.distinct().size == modules.size)
        modules.forEach { module ->
            val childPom = Files.readString(fixtureRoot.resolve(module).resolve("pom.xml"), StandardCharsets.UTF_8)
            check(!childPom.contains("<modules>")) { "$module is unexpectedly an aggregator" }
            val packaging = Regex("<packaging>([^<]+)</packaging>").find(childPom)?.groupValues?.get(1) ?: "jar"
            check(packaging == "jar") { "$module is not a JAR child" }
        }
    }

    @Given("this slice reuses REQ-JAVA-MAVEN-MODULE-RENAME-001's already-qualified in-process denial contract for Maven and wrapper execution, lifecycle goals, plugins, annotation processors, settings and credential access, credential helpers, and network requests")
    fun inheritedOfflineContract() {
        check(Files.isRegularFile(fixtureRoot.resolve("pom.xml"), LinkOption.NOFOLLOW_LINKS))
        check(!Files.exists(versionRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
        check(!Files.exists(legacyRoot.resolve(".refactorkit"), LinkOption.NOFOLLOW_LINKS))
    }

    @Given("the qualified request is oldModuleDir={string}, newModuleDir={string}, and caller-explicit newArtifactId={string}")
    fun qualifiedRequest(oldModuleDir: String, newModuleDir: String, newArtifactId: String) {
        check(oldModuleDir == "catalog-model")
        check(newModuleDir == "catalog-domain")
        check(newArtifactId == "catalog-domain")
    }

    @Given("REQ-JAVA-MAVEN-MODULE-RENAME-001 supplies the canonical {string} PREVIEW, exact five-edit candidate {string}, immutable authority lease and auxiliary-POM evidence, baseline {string}, authoritative post-image {string}, and diagnostic multiset {string}")
    fun inheritedCanonicalEvidence(operation: String, candidate: String, baseline: String, postImage: String, diagnostics: String) {
        check(operation == "java.renameMavenModule")
        check(candidate == "C1")
        check(baseline == "S0")
        check(postImage == "S1")
        check(diagnostics == "D0")
    }

    @Given("one API {string} LSP session and one independent legacy session are exercised in process only through public LspSession.dispatch; the first negotiates versioned documentChanges over the case workspace and the second does not over its own fresh {string} copy")
    fun initializeIndependentSessions(apiVersion: String, baseline: String) {
        check(apiVersion == "0.2")
        check(RefactorKitVersion.API_VERSION == apiVersion)
        check(baseline == "S0")

        versionSession = LspSession().also { session ->
            session.onNotification = { method, _ -> versionNotifications += method }
            session.dispatch("initialize", initializeParams(versionRoot, apiVersion, true))
        }
        legacySession = LspSession().also { session ->
            session.onNotification = { method, _ -> legacyNotifications += method }
            session.dispatch("initialize", initializeParams(legacyRoot, apiVersion, false))
        }
        versionNotifications.clear()
        legacyNotifications.clear()
    }

    @Given("both sessions have exact {string}, all fixture documents closed, no pending plan, and an empty RefactorKit transaction journal, and before either request the harness independently constructs this ordered five-entry protocol oracle from literal fixture paths and ranges without reading a planner result, response, pending-plan store, or journal:")
    fun establishIndependentOracle(baseline: String, table: DataTable) {
        check(baseline == "S0")
        check(snapshotNoFollow(versionRoot) == versionS0)
        check(snapshotNoFollow(legacyRoot) == legacyS0)
        check(privateCollectionSize(versionSession, "openDocuments") == 0)
        check(privateCollectionSize(legacySession, "openDocuments") == 0)
        check(pendingPlanCount(versionSession) == 0)
        check(pendingPlanCount(legacySession) == 0)
        assertNoEngineState(versionRoot)
        assertNoEngineState(legacyRoot)
        assertLiteralOracleTable(table)

        protocolOracle = expectedDocumentChanges(versionRoot)
        expectedClientPostImage = expectedPostImage(versionS0)
    }

    @When("the version-capable client invokes workspace\\/executeCommand {string} with the qualified request")
    fun requestVersionedProposal(command: String) {
        check(command == "refactorkit.renameMavenModule")
        val before = snapshotNoFollow(versionRoot)
        proposal = executeRename(versionSession, command)
        check(snapshotNoFollow(versionRoot) == before) { "proposal changed workspace bytes or path kinds" }
    }

    @Then("the real planner returns the canonical plan and the response contains these exact ownership facts:")
    fun canonicalPlannerAndOwnership(table: DataTable) {
        val expectedFacts = table.asMaps().associate { it.getValue("response fact") to it.getValue("exact value") }
        check(expectedFacts == mapOf(
            "operation" to "java.renameMavenModule",
            "status" to "PREVIEW",
            "refactorkitEditOwnership" to "client-managed",
            "refactorkitRollbackAvailable" to "false",
            "refactorkitDocumentVersionsChecked" to "true",
            "edit shape" to "documentChanges",
        ))
        expectedFacts.filterKeys { it != "edit shape" }.forEach { (field, value) ->
            check(proposal.getValue(field).jsonPrimitive.content == value) { "unexpected $field in $proposal" }
        }
        check(proposal.containsKey("documentChanges"))
        check(!proposal.containsKey("changes")) { "version-capable response exposed legacy changes" }
        check(!proposal.containsKey("transactionId"))

        val planId = proposal.getValue("refactorkitPlanId").jsonPrimitive.content
        retainedPlan = pendingStore(versionSession).lookup(PlanId(planId))
        val plan = checkNotNull(retainedPlan) { "proposal plan ID did not correlate to retained planner output" }
        check(plan.id.value == planId)
        check(plan.operation == "java.renameMavenModule")
        check(plan.status == PatchStatus.PREVIEW)
        check(plan.requiresUserApproval)
        check(plan.authorityLease != null) { "canonical Maven authority lease is absent" }
        check(WorkspaceEditSimulator.normalize(plan.workspaceEdit) == literalWorkspaceEdit()) {
            "retained real planner result differs from the independent literal five-entry oracle"
        }
    }

    @Then("the response's ordered documentChanges equals that independent oracle entry-for-entry after resolving each literal path under the disposable root to its file URI; all three text-document versions are JSON null because all fixture documents are closed, and neither rename operation fabricates a document version")
    fun exactVersionedDocumentChanges() {
        val returned = proposal.getValue("documentChanges").jsonArray
        check(returned == protocolOracle) {
            "documentChanges mismatch\nexpected=$protocolOracle\nactual=$returned"
        }
        returned.take(3).forEach { entry ->
            check(entry.jsonObject.getValue("textDocument").jsonObject.getValue("version") === JsonNull)
        }
        returned.drop(3).forEach { entry ->
            check(!entry.jsonObject.containsKey("textDocument"))
            check(!entry.jsonObject.containsKey("version"))
        }
    }

    @Then("refactorkitPlanId correlates a retained canonical plan for the distinct REQ-JAVA-MAVEN-MODULE-RENAME-SURFACE-005 managed command without requiring private retained-object identity, approval, transaction identity, or managed-write authority in this row")
    fun correlationOnlyPlanId() {
        val plan = checkNotNull(retainedPlan)
        check(plan.id.value == proposal.getValue("refactorkitPlanId").jsonPrimitive.content)
        check(pendingPlanCount(versionSession) == 1)
        check(proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content == "client-managed")
        check(!proposal.containsKey("approval"))
        check(!proposal.containsKey("transactionId"))
    }

    @Then("proposal creation invokes no operation apply gate, PatchEngine apply, workspace lock, WAL, transaction, state refresh, or rollback")
    fun proposalIsReadOnly() {
        check(snapshotNoFollow(versionRoot) == versionS0)
        check(versionNotifications.isEmpty()) { "proposal emitted lifecycle/refresh notifications: $versionNotifications" }
        check(executeCommands == listOf("refactorkit.renameMavenModule"))
        assertNoEngineState(versionRoot)
        check(snapshotNoFollow(fixtureRoot) == fixtureS0) { "permanent fixture changed during proposal" }
    }

    @When("a harness-side editor\\/client simulation applies only those five returned documentChanges in order without invoking {string}, PatchEngine, a transaction-journal helper, or any LSP lifecycle notification")
    fun editorAppliesReturnedOperations(forbiddenCommand: String) {
        check(forbiddenCommand == "refactorkit.applyPlan")
        check(proposal.getValue("documentChanges").jsonArray == protocolOracle)
        applyReturnedDocumentChanges(versionRoot, proposal.getValue("documentChanges").jsonArray)
        clientSimulationCompleted = true
    }

    @Then("the editor owns those writes and RefactorKit still has no journal record, transaction ID, recovery claim, or rollback capability for them")
    fun editorOwnedPostImage() {
        check(clientSimulationCompleted)
        check(snapshotNoFollow(versionRoot) == expectedClientPostImage) {
            "client-owned filesystem post-image differs from the independent literal oracle"
        }
        check(versionNotifications.isEmpty()) { "client simulation caused an LSP lifecycle notification" }
        check(pendingPlanCount(versionSession) == 1) { "client filesystem writes unexpectedly changed session retention" }
        check(!proposal.containsKey("transactionId"))
        check(proposal.getValue("refactorkitRollbackAvailable").jsonPrimitive.content == "false")
        assertNoEngineState(versionRoot)
        check(snapshotNoFollow(fixtureRoot) == fixtureS0) { "permanent fixture changed during client simulation" }
    }

    @Then("semantic, versioned, diff, or client-application evidence from the proposal is never counted as managed-apply validation")
    fun proposalIsNotManagedApplyEvidence() {
        check(proposal.getValue("status").jsonPrimitive.content == "PREVIEW")
        check(proposal.getValue("refactorkitEditOwnership").jsonPrimitive.content == "client-managed")
        check(executeCommands.none { it == "refactorkit.applyPlan" || it == "refactorkit.rollback" })
        assertNoEngineState(versionRoot)
    }

    @When("the legacy client requests the same structural proposal through public LspSession.dispatch without versioned documentChanges support")
    fun requestLegacyProposal() {
        val before = snapshotNoFollow(legacyRoot)
        try {
            legacyResult = executeRename(legacySession, "refactorkit.renameMavenModule")
        } catch (failure: JsonRpcException) {
            legacyFailure = failure
        }
        check(snapshotNoFollow(legacyRoot) == before) { "legacy refusal changed workspace bytes or path kinds" }
    }

    @Then("LSP refuses with DOCUMENT_VERSION_MISMATCH before returning any edit or retaining any plan, whether or not canonical read-only planning evaluations have already occurred, and changes no workspace byte or journal state")
    fun legacyRefusalIsFailClosed() {
        check(legacyResult == null) { "legacy client received an edit: $legacyResult" }
        val failure = checkNotNull(legacyFailure) { "legacy client was not refused" }
        check(failure.code == JsonRpcErrorCodes.DOCUMENT_VERSION_MISMATCH) { "unexpected code ${failure.code}" }
        check(pendingPlanCount(legacySession) == 0) { "legacy refusal retained a pending plan" }
        check(snapshotNoFollow(legacyRoot) == legacyS0)
        check(legacyNotifications.isEmpty()) { "legacy proposal emitted lifecycle/refresh notifications: $legacyNotifications" }
        assertNoEngineState(legacyRoot)
        check(snapshotNoFollow(fixtureRoot) == fixtureS0) { "permanent fixture changed" }
        check(executeCommands == listOf("refactorkit.renameMavenModule", "refactorkit.renameMavenModule"))
    }

    @After
    fun cleanUp() {
        temporaryRoots.asReversed().forEach(::deleteNoFollow)
    }

    private fun executeRename(session: LspSession, command: String): JsonObject {
        executeCommands += command
        return session.dispatch("workspace/executeCommand", buildJsonObject {
            put("command", command)
            put("arguments", buildJsonArray {
                add(buildJsonObject {
                    put("oldModuleDir", "catalog-model")
                    put("newModuleDir", "catalog-domain")
                    put("newArtifactId", "catalog-domain")
                })
            })
        }).jsonObject
    }

    private fun initializeParams(root: Path, apiVersion: String, documentChanges: Boolean): JsonObject =
        buildJsonObject {
            put("rootUri", root.toUri().toString())
            put("initializationOptions", buildJsonObject { put("refactorkitApiVersion", apiVersion) })
            put("capabilities", buildJsonObject {
                put("workspace", buildJsonObject {
                    put("workspaceEdit", buildJsonObject { put("documentChanges", documentChanges) })
                })
            })
        }

    private fun expectedDocumentChanges(root: Path): JsonArray = JsonArray(
        literalModifies.map { edit ->
            buildJsonObject {
                put("textDocument", buildJsonObject {
                    put("uri", literalUri(root, edit.path))
                    put("version", JsonNull)
                })
                put("edits", JsonArray(listOf(buildJsonObject {
                    put("range", rangeJson(edit.startLine, edit.startCharacter, edit.endLine, edit.endCharacter))
                    put("newText", edit.newText)
                })))
            }
        } + literalRenames.map { rename ->
            buildJsonObject {
                put("kind", "rename")
                put("oldUri", literalUri(root, rename.oldPath))
                put("newUri", literalUri(root, rename.newPath))
            }
        },
    )

    private fun literalWorkspaceEdit(): WorkspaceEdit = WorkspaceEdit(
        literalModifies.map { edit ->
            FileEdit.Modify(
                Paths.get(edit.path),
                listOf(TextEdit(
                    SourceRange(
                        SourcePosition(edit.startLine, edit.startCharacter),
                        SourcePosition(edit.endLine, edit.endCharacter),
                    ),
                    edit.newText,
                )),
            )
        } + literalRenames.map { rename -> FileEdit.Rename(Paths.get(rename.oldPath), Paths.get(rename.newPath)) },
    )

    private fun expectedPostImage(initial: Map<String, Node>): Map<String, Node> {
        val expected = initial.toMutableMap()
        literalModifies.forEach { edit ->
            val node = checkNotNull(expected[edit.path])
            check(node.kind == "file")
            val content = String(Base64.getDecoder().decode(checkNotNull(node.contentBase64)), StandardCharsets.UTF_8)
            val changed = applyLiteralEdit(content, edit)
            expected[edit.path] = Node("file", Base64.getEncoder().encodeToString(changed.toByteArray(StandardCharsets.UTF_8)))
        }
        literalRenames.forEach { rename ->
            val node = checkNotNull(expected.remove(rename.oldPath))
            addParentDirectories(expected, rename.newPath)
            check(expected.put(rename.newPath, node) == null) { "literal destination already exists: ${rename.newPath}" }
        }
        return expected.toSortedMap()
    }

    private fun applyReturnedDocumentChanges(root: Path, changes: JsonArray) {
        changes.forEach { element ->
            val operation = element.jsonObject
            val textDocument = operation["textDocument"] as? JsonObject
            if (textDocument != null) {
                val path = safeClientPath(root, textDocument.getValue("uri").jsonPrimitive.content)
                check(textDocument.getValue("version") === JsonNull)
                var content = Files.readString(path, StandardCharsets.UTF_8)
                val edits = operation.getValue("edits").jsonArray.map { it.jsonObject }
                    .sortedWith(compareByDescending<JsonObject> {
                        it.getValue("range").jsonObject.getValue("start").jsonObject.getValue("line").jsonPrimitive.content.toInt()
                    }.thenByDescending {
                        it.getValue("range").jsonObject.getValue("start").jsonObject.getValue("character").jsonPrimitive.content.toInt()
                    })
                edits.forEach { edit -> content = applyProtocolEdit(content, edit) }
                Files.writeString(
                    path,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            } else {
                check(operation.getValue("kind").jsonPrimitive.content == "rename")
                val oldPath = safeClientPath(root, operation.getValue("oldUri").jsonPrimitive.content)
                val newPath = safeClientPath(root, operation.getValue("newUri").jsonPrimitive.content)
                check(!Files.exists(newPath, LinkOption.NOFOLLOW_LINKS))
                Files.createDirectories(newPath.parent)
                Files.move(oldPath, newPath)
            }
        }
    }

    private fun applyProtocolEdit(content: String, edit: JsonObject): String {
        val range = edit.getValue("range").jsonObject
        val start = range.getValue("start").jsonObject
        val end = range.getValue("end").jsonObject
        val startOffset = offset(
            content,
            start.getValue("line").jsonPrimitive.content.toInt(),
            start.getValue("character").jsonPrimitive.content.toInt(),
        )
        val endOffset = offset(
            content,
            end.getValue("line").jsonPrimitive.content.toInt(),
            end.getValue("character").jsonPrimitive.content.toInt(),
        )
        return content.substring(0, startOffset) + edit.getValue("newText").jsonPrimitive.content + content.substring(endOffset)
    }

    private fun applyLiteralEdit(content: String, edit: LiteralModify): String {
        val start = offset(content, edit.startLine, edit.startCharacter)
        val end = offset(content, edit.endLine, edit.endCharacter)
        check(content.substring(start, end) == edit.oldText) {
            "literal range ${edit.path}:${edit.startLine}:${edit.startCharacter}-${edit.endLine}:${edit.endCharacter} did not select ${edit.oldText}"
        }
        return content.substring(0, start) + edit.newText + content.substring(end)
    }

    private fun offset(content: String, targetLine: Int, targetCharacter: Int): Int {
        var line = 0
        var index = 0
        while (line < targetLine) {
            val newline = content.indexOf('\n', index)
            check(newline >= 0) { "line $targetLine is outside document" }
            index = newline + 1
            line++
        }
        check(index + targetCharacter <= content.length)
        return index + targetCharacter
    }

    private fun assertLiteralOracleTable(table: DataTable) {
        val expected = listOf(
            listOf("1", "text document edit", "catalog-model/pom.xml", "9:14-9:27", "catalog-domain", null, "JSON null"),
            listOf("2", "text document edit", "pom.xml", "74:12-74:25", "catalog-domain", null, "JSON null"),
            listOf("3", "text document edit", "catalog-pricing/pom.xml", "13:18-13:31", "catalog-domain", null, "JSON null"),
            listOf("4", "rename file operation", "catalog-model/pom.xml", "absent", "absent", "catalog-domain/pom.xml", "absent"),
            listOf(
                "5",
                "rename file operation",
                "catalog-model/src/main/java/com/acme/catalog/legacy/Product.java",
                "absent",
                "absent",
                "catalog-domain/src/main/java/com/acme/catalog/legacy/Product.java",
                "absent",
            ),
        )
        val rows = table.cells().drop(1)
        check(rows == expected) { "feature protocol table drifted: $rows" }
    }

    private fun rangeJson(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int): JsonObject =
        buildJsonObject {
            put("start", buildJsonObject { put("line", startLine); put("character", startCharacter) })
            put("end", buildJsonObject { put("line", endLine); put("character", endCharacter) })
        }

    private fun literalUri(root: Path, path: String): String =
        root.resolve(path).toAbsolutePath().normalize().toUri().toString()

    private fun safeClientPath(root: Path, uri: String): Path {
        val path = Paths.get(URI(uri)).toAbsolutePath().normalize()
        check(path.startsWith(root)) { "client operation escaped disposable workspace: $uri" }
        return path
    }

    private fun addParentDirectories(nodes: MutableMap<String, Node>, path: String) {
        var parent = path.substringBeforeLast('/', "")
        val parents = mutableListOf<String>()
        while (parent.isNotEmpty()) {
            parents += parent
            parent = parent.substringBeforeLast('/', "")
        }
        parents.asReversed().forEach { nodes.putIfAbsent(it, Node("directory")) }
    }

    private fun assertNoEngineState(root: Path) {
        val engine = root.resolve(".refactorkit")
        check(!Files.exists(engine, LinkOption.NOFOLLOW_LINKS)) {
            "unexpected RefactorKit lock/WAL/journal/transaction/recovery state: $engine"
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun pendingStore(session: LspSession): PendingPlanStore<PatchPlan> {
        val field = runCatching { LspSession::class.java.getDeclaredField("pendingPlans") }
            .getOrElse { throw IllegalStateException("fail-closed: LspSession pendingPlans observation is unavailable", it) }
        field.isAccessible = true
        return field.get(session) as? PendingPlanStore<PatchPlan>
            ?: error("fail-closed: LspSession pendingPlans has an unexpected runtime type")
    }

    private fun pendingPlanCount(session: LspSession): Int {
        val store = pendingStore(session)
        val field = runCatching { store.javaClass.getDeclaredField("retained") }
            .getOrElse { throw IllegalStateException("fail-closed: PendingPlanStore retained-map observation is unavailable", it) }
        field.isAccessible = true
        return (field.get(store) as? Map<*, *>)?.size
            ?: error("fail-closed: PendingPlanStore retained-map has an unexpected runtime type")
    }

    private fun privateCollectionSize(session: LspSession, fieldName: String): Int {
        val field = runCatching { LspSession::class.java.getDeclaredField(fieldName) }
            .getOrElse { throw IllegalStateException("fail-closed: LspSession $fieldName observation is unavailable", it) }
        field.isAccessible = true
        return (field.get(session) as? Collection<*>)?.size
            ?: (field.get(session) as? Map<*, *>)?.size
            ?: error("fail-closed: LspSession $fieldName has an unexpected runtime type")
    }

    private fun repositoryRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("testdata"), LinkOption.NOFOLLOW_LINKS)) return candidate
            candidate = candidate.parent
        }
        error("repository root containing testdata was not found")
    }

    private fun freshCopy(prefix: String): Path {
        val parent = Files.createTempDirectory(prefix)
        temporaryRoots.add(parent)
        val destination = parent.resolve("workspace")
        copyNoFollow(fixtureRoot, destination)
        return destination.toAbsolutePath().normalize()
    }

    private fun copyNoFollow(source: Path, destination: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(!attrs.isSymbolicLink) { "fixture contains a symbolic-link directory: $dir" }
                Files.createDirectories(destination.resolve(source.relativize(dir)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(attrs.isRegularFile && !attrs.isSymbolicLink) { "fixture contains a non-regular file: $file" }
                val target = destination.resolve(source.relativize(file))
                Files.createDirectories(target.parent)
                Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun snapshotNoFollow(root: Path): Map<String, Node> {
        val nodes = linkedMapOf<String, Node>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(!attrs.isSymbolicLink) { "symbolic-link directory is forbidden: $dir" }
                if (dir != root) nodes[portableRelative(root, dir)] = Node("directory")
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(attrs.isRegularFile && !attrs.isSymbolicLink) { "non-regular workspace entry is forbidden: $file" }
                nodes[portableRelative(root, file)] = Node(
                    "file",
                    Base64.getEncoder().encodeToString(Files.readAllBytes(file)),
                )
                return FileVisitResult.CONTINUE
            }
        })
        return nodes.toSortedMap()
    }

    private fun portableRelative(root: Path, path: Path): String =
        root.relativize(path).joinToString("/") { it.toString() }

    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}

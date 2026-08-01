package org.refactorkit.cli.previewcommand

import io.cucumber.datatable.DataTable
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcErrorCodes
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.mcp.McpSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JavaRefactoringPreviewCommandSurfaceSteps {
    private data class Fixture(
        val root: Path,
        val baseline: Map<String, String>,
    )

    private data class DaemonPreviewEvidence(
        val operation: String,
        val snapshotBefore: String,
        val snapshotAfter: String,
        val adapterBefore: JavaLanguageAdapter,
        val adapterAfter: JavaLanguageAdapter,
        val response: JsonObject,
    )

    private data class McpPreviewEvidence(
        val operation: String,
        val snapshotBefore: String,
        val snapshotAfter: String,
        val adapterBefore: JavaLanguageAdapter,
        val adapterAfter: JavaLanguageAdapter,
        val result: JsonObject,
        val text: String,
        val planId: String,
    )

    private val admittedOperations = listOf(
        "renameClass",
        "renameMember",
        "moveSourceRoot",
        "organizeImports",
        "safeDelete",
    )
    private val temporaryRoots = mutableListOf<Path>()
    private val daemonPreviews = mutableListOf<DaemonPreviewEvidence>()
    private val mcpPreviews = mutableListOf<McpPreviewEvidence>()

    private lateinit var daemonFixture: Fixture
    private lateinit var mcpFixture: Fixture
    private lateinit var daemonSession: DaemonSession
    private lateinit var mcpSession: McpSession
    private lateinit var daemonOpenedSnapshot: String
    private lateinit var mcpOpenedSnapshot: String
    private lateinit var daemonSessionAdapter: JavaLanguageAdapter
    private lateinit var mcpSessionAdapter: JavaLanguageAdapter
    private var pairLabels: Pair<String, String>? = null
    private var surfacePolicies: List<Map<String, String>> = emptyList()
    private var realCallsCompleted = false

    private lateinit var daemonMissing: JsonRpcException
    private lateinit var daemonUnknown: JsonRpcException
    private lateinit var daemonRefusal: JsonRpcException
    private lateinit var mcpMissing: JsonObject
    private lateinit var mcpUnknown: JsonObject
    private lateinit var mcpRefusal: JsonObject
    private lateinit var mcpRefusalPlanId: String
    private lateinit var mcpRefusalApply: JsonRpcException

    private lateinit var repositoryRoot: Path
    private var daemonPreviewSource: String = ""
    private var mcpPreviewSource: String = ""
    private var commandBoundarySource: String = ""

    @After
    fun closeSessionsAndDeleteFixtures() {
        if (::daemonSession.isInitialized) runCatching { daemonSession.close() }
        if (::mcpSession.isInitialized) runCatching { mcpSession.close() }
        temporaryRoots.asReversed().forEach(::deleteRecursively)
        temporaryRoots.clear()
    }

    @Given("daemon JSON-RPC and MCP each receive every admitted Java operation {string}")
    fun openRealSurfacesForEveryAdmittedOperation(rawOperations: String) {
        assertEquals(admittedOperations, csv(rawOperations))
        daemonFixture = createMavenReactorFixture("daemon")
        mcpFixture = createMavenReactorFixture("mcp")

        daemonSession = DaemonSession()
        mcpSession = McpSession()
        daemonSessionAdapter = javaAdapterIdentity(daemonSession)
        mcpSessionAdapter = javaAdapterIdentity(mcpSession)
        assertFalse(daemonSessionAdapter === mcpSessionAdapter)

        val daemonOpen = daemonSession.dispatch(
            "project.open",
            strings("root" to daemonFixture.root.toString()),
        ).jsonObject
        daemonOpenedSnapshot = daemonOpen.getValue("snapshotHash").jsonPrimitive.content
        assertEquals(daemonFixture.root.toAbsolutePath().normalize().toString(), daemonOpen.getValue("root").jsonPrimitive.content)

        val mcpScan = mcpCall(mcpSession, "project_scan", strings("root" to mcpFixture.root.toString()))
        assertFalse(toolIsError(mcpScan), toolText(mcpScan))
        mcpOpenedSnapshot = firstValueAfter("Snapshot:", toolText(mcpScan))

        assertSame(daemonSessionAdapter, javaAdapterIdentity(daemonSession))
        assertSame(mcpSessionAdapter, javaAdapterIdentity(mcpSession))
        assertWorkspaceUnchanged(daemonFixture)
        assertWorkspaceUnchanged(mcpFixture)
    }

    @Given("before constructing a command each surface retains this existing policy:")
    fun retainSurfacePolicies(table: DataTable) {
        surfacePolicies = table.asMaps()
        assertEquals(listOf("daemon JSON-RPC", "MCP"), surfacePolicies.map { it.getValue("surface") })
        surfacePolicies.forEach { row ->
            assertTrue(row.getValue("raw work before the boundary").contains("parse raw"))
            assertTrue(row.getValue("refused-plan policy").isNotBlank())
            assertTrue(row.getValue("pending-plan admission").isNotBlank())
            assertTrue(row.getValue("rendering after planning").isNotBlank())
        }
        assertTrue(surfacePolicies[0].getValue("refused-plan policy").contains("PLAN_REFUSED"))
        assertTrue(surfacePolicies[1].getValue("rendering after planning").contains("MCP tool content text"))
    }

    @Given("two consecutive validated calls have exact current pairs {string} and {string}")
    fun recordCurrentSurfacePairs(first: String, second: String) {
        assertEquals("S-1, A-1", first)
        assertEquals("S-2, A-2", second)
        pairLabels = first to second

        assertEquals(daemonOpenedSnapshot, daemonCurrentSnapshot())
        assertEquals(mcpOpenedSnapshot, mcpCurrentSnapshot())
        assertSame(daemonSessionAdapter, javaAdapterIdentity(daemonSession))
        assertSame(mcpSessionAdapter, javaAdapterIdentity(mcpSession))
    }

    @When("each surface constructs and previews its complete typed command")
    fun invokeEveryOperationAndNegativePathThroughPublicDispatch() {
        assertNotNull(pairLabels, "current surface pairs must be established before preview")
        admittedOperations.forEach { operation ->
            val daemonSnapshotBefore = daemonCurrentSnapshot()
            val daemonAdapterBefore = javaAdapterIdentity(daemonSession)
            val daemonResult = daemonSession.dispatch("refactor.preview", previewRequest(operation)).jsonObject
            val daemonAdapterAfter = javaAdapterIdentity(daemonSession)
            val daemonSnapshotAfter = daemonCurrentSnapshot()
            daemonPreviews += DaemonPreviewEvidence(
                operation,
                daemonSnapshotBefore,
                daemonSnapshotAfter,
                daemonAdapterBefore,
                daemonAdapterAfter,
                daemonResult,
            )

            val mcpSnapshotBefore = mcpCurrentSnapshot()
            val mcpAdapterBefore = javaAdapterIdentity(mcpSession)
            val mcpResult = mcpCall(mcpSession, "preview_refactoring", previewRequest(operation))
            val mcpAdapterAfter = javaAdapterIdentity(mcpSession)
            val mcpSnapshotAfter = mcpCurrentSnapshot()
            val text = toolText(mcpResult)
            mcpPreviews += McpPreviewEvidence(
                operation,
                mcpSnapshotBefore,
                mcpSnapshotAfter,
                mcpAdapterBefore,
                mcpAdapterAfter,
                mcpResult,
                text,
                firstValueAfter("Plan ID  :", text),
            )
        }

        daemonMissing = assertFailsWith {
            daemonSession.dispatch("refactor.preview", missingRenameInput())
        }
        daemonUnknown = assertFailsWith {
            daemonSession.dispatch("refactor.preview", unknownPreviewOperation())
        }
        daemonRefusal = assertFailsWith {
            daemonSession.dispatch("refactor.preview", overlappingMoveSourceRoot())
        }

        mcpMissing = mcpCall(mcpSession, "preview_refactoring", missingRenameInput())
        mcpUnknown = mcpCall(mcpSession, "preview_refactoring", unknownPreviewOperation())
        mcpRefusal = mcpCall(mcpSession, "preview_refactoring", overlappingMoveSourceRoot())
        mcpRefusalPlanId = firstValueAfter("Plan ID  :", toolText(mcpRefusal))
        mcpRefusalApply = assertFailsWith {
            mcpCall(mcpSession, "apply_refactoring", strings("planId" to mcpRefusalPlanId))
        }
        realCallsCompleted = true
    }

    @Then("each call supplies its own exact current snapshot and Java adapter pair to the fieldless boundary")
    fun eachResultIsBoundToItsRealCurrentSurfacePair() {
        assertTrue(realCallsCompleted)
        assertEquals(admittedOperations, daemonPreviews.map(DaemonPreviewEvidence::operation))
        assertEquals(admittedOperations, mcpPreviews.map(McpPreviewEvidence::operation))

        daemonPreviews.forEach { evidence ->
            val response = evidence.response
            assertEquals(evidence.operation, response.getValue("operation").jsonPrimitive.content)
            assertEquals("PREVIEW", response.getValue("status").jsonPrimitive.content)
            assertTrue(response.getValue("planId").jsonPrimitive.content.isNotBlank())
            assertEquals(evidence.snapshotBefore, evidence.snapshotAfter)
            assertEquals(evidence.snapshotBefore, response.getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content)
            assertEquals(daemonOpenedSnapshot, evidence.snapshotBefore)
            assertEquals("refactorkit-java", response.getValue("provider").jsonObject.getValue("name").jsonPrimitive.content)
            assertTrue(response.getValue("structuredDiff").jsonArray.isNotEmpty())
        }
        assertEquals(
            daemonPreviews.size,
            daemonPreviews.map { it.response.getValue("planId").jsonPrimitive.content }.toSet().size,
        )

        mcpPreviews.forEach { evidence ->
            assertFalse(toolIsError(evidence.result), evidence.text)
            assertEquals(evidence.snapshotBefore, evidence.snapshotAfter)
            assertEquals(mcpOpenedSnapshot, evidence.snapshotBefore)
            assertTrue(evidence.planId.isNotBlank())
            assertTrue(evidence.text.contains("Status   : PREVIEW"), evidence.text)
            assertTrue(evidence.text.contains(summaryMarker(evidence.operation)), evidence.text)
            assertTrue(
                evidence.text.contains("To apply: use tool apply_refactoring with planId=${evidence.planId}"),
                evidence.text,
            )
        }
        assertEquals(mcpPreviews.size, mcpPreviews.map(McpPreviewEvidence::planId).toSet().size)

        loadProductionSources()
        assertTrue(daemonPreviewSource.contains("val snap = requireSnapshot()"))
        assertTrue(mcpPreviewSource.contains("val snap = requireSnapshot()"))
        assertEquals(5, currentPairDispatchCount(daemonPreviewSource))
        assertEquals(5, currentPairDispatchCount(mcpPreviewSource))
    }

    @Then("no startup, previous-call, replacement, or other surface's Java adapter is used")
    fun sessionAdapterIdentityRemainsExactAndSurfaceLocal() {
        assertEquals("S-1, A-1" to "S-2, A-2", pairLabels)
        daemonPreviews.take(2).forEach { evidence ->
            assertSame(daemonSessionAdapter, evidence.adapterBefore)
            assertSame(daemonSessionAdapter, evidence.adapterAfter)
        }
        mcpPreviews.take(2).forEach { evidence ->
            assertSame(mcpSessionAdapter, evidence.adapterBefore)
            assertSame(mcpSessionAdapter, evidence.adapterAfter)
        }
        daemonPreviews.forEach { evidence ->
            assertSame(daemonSessionAdapter, evidence.adapterBefore)
            assertSame(daemonSessionAdapter, evidence.adapterAfter)
        }
        mcpPreviews.forEach { evidence ->
            assertSame(mcpSessionAdapter, evidence.adapterBefore)
            assertSame(mcpSessionAdapter, evidence.adapterAfter)
        }
        assertSame(daemonSessionAdapter, javaAdapterIdentity(daemonSession))
        assertSame(mcpSessionAdapter, javaAdapterIdentity(mcpSession))
        assertFalse(daemonSessionAdapter === mcpSessionAdapter)
    }

    @Then("no missing or unknown raw request constructs a typed command or invokes a Java planner")
    fun missingAndUnknownInputsRemainSurfaceErrorsBeforeTypedDispatch() {
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, daemonMissing.code)
        assertEquals("Missing required field: arguments.newName", daemonMissing.message)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, daemonUnknown.code)
        assertEquals("Unknown operation: unknownJavaPreview", daemonUnknown.message)

        assertTrue(toolIsError(mcpMissing))
        assertEquals("Error: Missing required field: arguments.newName", toolText(mcpMissing))
        assertTrue(toolIsError(mcpUnknown))
        assertEquals("Error: Unknown operation: unknownJavaPreview", toolText(mcpUnknown))
        listOf(toolText(mcpMissing), toolText(mcpUnknown)).forEach { text ->
            assertFalse(text.contains("Plan ID"), text)
            assertFalse(text.contains("To apply:"), text)
        }

        loadProductionSources()
        listOf(daemonPreviewSource, mcpPreviewSource).forEach { source ->
            assertEquals(5, dispatcherCallCount(source))
            expectedPlannerClasses().forEach { planner -> assertFalse(source.contains(planner), planner) }
            assertTrue(source.contains("else -> throw JsonRpcException"))
        }
        assertDecodeBeforeCommand(daemonPreviewSource)
        assertDecodeBeforeCommand(mcpPreviewSource)
    }

    @Then("after the exact PatchPlan returns each surface keeps its listed refusal, admission, and rendering policy")
    fun realRefusalAdmissionAndRenderingPoliciesRemainDistinct() {
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, daemonRefusal.code)
        val daemonRefusalData = assertNotNull(daemonRefusal.data).jsonObject
        assertEquals("sourceRoot.overlap", daemonRefusalData.getValue("refusalCode").jsonPrimitive.content)
        assertEquals(setOf("refusalCode"), daemonRefusalData.keys)
        assertFalse(daemonRefusal.message.contains("planId", ignoreCase = true))
        assertFalse(daemonRefusal.message.contains("apply", ignoreCase = true))

        val refusalText = toolText(mcpRefusal)
        assertFalse(toolIsError(mcpRefusal), refusalText)
        assertTrue(refusalText.contains("Status   : REFUSED"), refusalText)
        assertTrue(refusalText.contains("Refusal  : sourceRoot.overlap"), refusalText)
        assertTrue(refusalText.contains("Refused. Do NOT apply."), refusalText)
        assertFalse(refusalText.contains("To apply:"), refusalText)
        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, mcpRefusalApply.code)
        assertEquals("Plan not found: $mcpRefusalPlanId", mcpRefusalApply.message)

        loadProductionSources()
        assertTrue(daemonPreviewSource.contains("plan.status == PatchStatus.REFUSED"))
        assertTrue(daemonPreviewSource.contains("JsonRpcErrorCodes.PLAN_REFUSED"))
        assertTrue(daemonPreviewSource.contains("pendingPlans.insert"))
        assertTrue(daemonPreviewSource.contains("return planToJson(plan)"))
        assertTrue(mcpPreviewSource.contains("plan.status == PatchStatus.PREVIEW"))
        assertTrue(mcpPreviewSource.contains("pendingPlans.insert"))
        assertTrue(mcpPreviewSource.contains("PreviewToolResult.Text"))
        assertEquals(2, surfacePolicies.size)

        assertWorkspaceUnchanged(daemonFixture)
        assertWorkspaceUnchanged(mcpFixture)
    }

    @Then("daemon and MCP converge only on typed Java planner dispatch, not on protocol parsing, error wording, admission, or projection")
    fun actualSurfacesConvergeOnlyAtTypedDispatch() {
        assertTrue(realCallsCompleted, "source inspection must only supplement completed real calls")
        assertEquals(admittedOperations, daemonPreviews.map(DaemonPreviewEvidence::operation))
        assertEquals(admittedOperations, mcpPreviews.map(McpPreviewEvidence::operation))
        assertFalse(toolIsError(mcpRefusal))
        assertTrue(toolIsError(mcpMissing))
        assertTrue(toolIsError(mcpUnknown))
        assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, daemonRefusal.code)

        loadProductionSources()
        assertEquals(5, dispatcherCallCount(daemonPreviewSource))
        assertEquals(5, dispatcherCallCount(mcpPreviewSource))
        expectedCommandTypes().forEach { command ->
            assertTrue(daemonPreviewSource.contains("JavaRefactoringPreviewCommand.$command"), "daemon missing $command")
            assertTrue(mcpPreviewSource.contains("JavaRefactoringPreviewCommand.$command"), "MCP missing $command")
        }
        assertTrue(daemonPreviewSource.contains("planToJson"))
        assertFalse(mcpPreviewSource.contains("planToJson"))
        assertTrue(mcpPreviewSource.contains("PreviewToolResult.Text"))
        assertFalse(daemonPreviewSource.contains("PreviewToolResult.Text"))
        assertFalse(commandBoundarySource.contains("JsonPrimitive"))
        assertFalse(commandBoundarySource.contains("pendingPlans"))
        assertWorkspaceUnchanged(daemonFixture)
        assertWorkspaceUnchanged(mcpFixture)
    }

    private fun previewRequest(operation: String): JsonObject = buildJsonObject {
        put("operation", operation)
        when (operation) {
            "renameClass" -> {
                put("symbol", "fixture.preview.Invoice")
                put("arguments", buildJsonObject { put("newName", "Statement") })
            }
            "renameMember" -> {
                put("symbol", "fixture.preview.Invoice#total()")
                put("arguments", buildJsonObject { put("newName", "grandTotal") })
            }
            "moveSourceRoot" -> put("arguments", buildJsonObject {
                put("from", "source/src/main/java")
                put("to", "target/src/main/java")
            })
            "organizeImports" -> put("arguments", buildJsonObject {
                put("file", "source/src/main/java/fixture/preview/Invoice.java")
            })
            "safeDelete" -> {
                put("symbol", "fixture.preview.ObsoleteTax")
                put("arguments", buildJsonObject { put("force", false) })
            }
            else -> error("unexpected admitted operation: $operation")
        }
    }

    private fun missingRenameInput(): JsonObject = buildJsonObject {
        put("operation", "renameClass")
        put("symbol", "fixture.preview.Invoice")
        put("arguments", buildJsonObject {})
    }

    private fun unknownPreviewOperation(): JsonObject = buildJsonObject {
        put("operation", "unknownJavaPreview")
        put("symbol", "fixture.preview.Invoice")
        put("arguments", buildJsonObject {})
    }

    private fun overlappingMoveSourceRoot(): JsonObject = buildJsonObject {
        put("operation", "moveSourceRoot")
        put("arguments", buildJsonObject {
            put("from", "source/src/main/java")
            put("to", "source/src/main/java")
        })
    }

    private fun summaryMarker(operation: String): String = when (operation) {
        "renameClass" -> "Rename Invoice"
        "renameMember" -> "Rename method 'total()'"
        "moveSourceRoot" -> "Java compilation unit(s)"
        "organizeImports" -> "Organize imports"
        "safeDelete" -> "Delete fixture.preview.ObsoleteTax"
        else -> error("unexpected admitted operation: $operation")
    }

    private fun daemonCurrentSnapshot(): String = daemonSession.dispatch("project.summary", null)
        .jsonObject.getValue("snapshotHash").jsonPrimitive.content

    private fun mcpCurrentSnapshot(): String {
        val result = mcpCall(mcpSession, "project_summary", buildJsonObject {})
        assertFalse(toolIsError(result), toolText(result))
        return firstValueAfter("Snapshot:", toolText(result))
    }

    private fun mcpCall(session: McpSession, name: String, arguments: JsonObject): JsonObject =
        session.dispatch("tools/call", buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }).jsonObject

    private fun toolText(result: JsonObject): String = result.getValue("content").jsonArray
        .single().jsonObject.getValue("text").jsonPrimitive.content

    private fun toolIsError(result: JsonObject): Boolean =
        result.getValue("isError").jsonPrimitive.content.toBooleanStrict()

    private fun firstValueAfter(prefix: String, text: String): String =
        text.lineSequence().first { it.trimStart().startsWith(prefix) }
            .trimStart().removePrefix(prefix).trim()

    private fun javaAdapterIdentity(session: Any): JavaLanguageAdapter {
        val field = session.javaClass.getDeclaredField("adapter")
        assertEquals(JavaLanguageAdapter::class.java, field.type)
        assertTrue(field.trySetAccessible(), "session Java adapter identity is not publicly exposed")
        return field.get(session) as JavaLanguageAdapter
    }

    private fun createMavenReactorFixture(surface: String): Fixture {
        val root = Files.createTempDirectory("refactorkit-preview-$surface-")
            .toAbsolutePath().normalize().also(temporaryRoots::add)
        writeFixtureFile(root, "pom.xml", """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId>
              <artifactId>preview-reactor</artifactId>
              <version>1</version>
              <packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>source</module><module>target</module></modules>
            </project>
        """.trimIndent() + "\n")
        writeFixtureFile(root, "source/pom.xml", childPom("source"))
        writeFixtureFile(root, "target/pom.xml", childPom("target"))
        writeFixtureFile(root, "source/src/main/java/fixture/preview/Invoice.java", """
            package fixture.preview;

            import java.util.Set;
            import java.util.List;

            public class Invoice {
                public int total() { return 7; }
            }
        """.trimIndent() + "\n")
        writeFixtureFile(root, "source/src/main/java/fixture/preview/ObsoleteTax.java", """
            package fixture.preview;

            public class ObsoleteTax {
            }
        """.trimIndent() + "\n")
        return Fixture(root, captureTree(root))
    }

    private fun childPom(artifactId: String): String = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <parent>
            <groupId>fixture</groupId>
            <artifactId>preview-reactor</artifactId>
            <version>1</version>
            <relativePath>../pom.xml</relativePath>
          </parent>
          <artifactId>$artifactId</artifactId>
        </project>
    """.trimIndent() + "\n"

    private fun writeFixtureFile(root: Path, relative: String, content: String) {
        val file = root.resolve(relative)
        Files.createDirectories(assertNotNull(file.parent))
        file.writeText(content)
    }

    private fun assertWorkspaceUnchanged(fixture: Fixture) {
        assertEquals(fixture.baseline, captureTree(fixture.root))
        assertFalse(fixture.root.resolve(".refactorkit").exists(), "preview left .refactorkit residue")
    }

    private fun captureTree(root: Path): Map<String, String> {
        val tree = linkedMapOf<String, String>()
        Files.walk(root).use { paths ->
            paths.sorted().forEach { path ->
                val relative = root.relativize(path).toString().replace('\\', '/').ifBlank { "." }
                tree[relative] = if (Files.isDirectory(path)) {
                    "directory"
                } else {
                    val bytes = Files.readAllBytes(path)
                    "file:${bytes.size}:${sha256(bytes)}"
                }
            }
        }
        return tree
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    private fun loadProductionSources() {
        if (::repositoryRoot.isInitialized) return
        repositoryRoot = locateRepositoryRoot()
        val daemonSource = Files.readString(repositoryRoot.resolve(
            "modules/refactorkit-daemon/src/main/kotlin/org/refactorkit/daemon/DaemonSession.kt",
        ))
        val mcpSource = Files.readString(repositoryRoot.resolve(
            "modules/refactorkit-mcp/src/main/kotlin/org/refactorkit/mcp/McpSession.kt",
        ))
        daemonPreviewSource = functionText(daemonSource, "refactorPreview")
        mcpPreviewSource = functionText(mcpSource, "toolPreviewRefactoring")
        commandBoundarySource = Files.readString(repositoryRoot.resolve(
            "modules/refactorkit-java/src/main/kotlin/org/refactorkit/java/JavaRefactoringPreviewCommand.kt",
        ))
    }

    private fun functionText(source: String, name: String): String {
        val declaration = Regex("(?m)^    (?:private |internal |override )?fun ${Regex.escape(name)}[ \\t]*\\(")
        val match = declaration.find(source) ?: error("function $name not found")
        val next = Regex("(?m)^    (?:private |internal |override )?fun ").find(source, match.range.last + 1)
        return source.substring(match.range.first, next?.range?.first ?: source.length)
    }

    private fun locateRepositoryRoot(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath().normalize()
        while (candidate != null) {
            if (candidate.resolve("settings.gradle.kts").exists() && candidate.resolve("modules/refactorkit-cli").exists()) {
                return candidate
            }
            candidate = candidate.parent
        }
        error("repository root not found")
    }

    private fun assertDecodeBeforeCommand(source: String) {
        val missing = source.indexOf("missing(\"arguments.newName\")")
        val command = source.indexOf("JavaRefactoringPreviewCommand.RenameClass")
        assertTrue(missing >= 0 && missing < command, "required input must be decoded before command construction")
    }

    private fun dispatcherCallCount(source: String): Int =
        Regex("JavaRefactoringPreviewDispatcher\\s*\\(\\s*\\)\\s*\\.preview\\s*\\(").findAll(source).count()

    private fun currentPairDispatchCount(source: String): Int =
        Regex("JavaRefactoringPreviewDispatcher\\s*\\(\\s*\\)\\s*\\.preview\\s*\\(\\s*snap\\s*,\\s*adapter\\s*,")
            .findAll(source).count()

    private fun expectedCommandTypes(): List<String> = listOf(
        "RenameClass",
        "RenameMember",
        "MoveSourceRoot",
        "OrganizeImports",
        "SafeDelete",
    )

    private fun expectedPlannerClasses(): List<String> = listOf(
        "JavaRenameClassPlanner",
        "JavaRenameMemberPlanner",
        "JavaMoveSourceRootPlanner",
        "JavaOrganizeImportsPlanner",
        "JavaSafeDeletePlanner",
    )

    private fun strings(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
        entries.forEach { (key, value) -> put(key, value) }
    }

    private fun csv(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotBlank)

    private fun deleteRecursively(root: Path) {
        if (!root.exists()) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
        }
    }
}

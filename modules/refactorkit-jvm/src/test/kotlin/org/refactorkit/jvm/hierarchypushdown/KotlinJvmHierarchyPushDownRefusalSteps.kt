package org.refactorkit.jvm.hierarchypushdown

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.jvm.refusals.KotlinJvmRefusalScenarioContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Story BDD glue for features/java-hierarchy-push-down-refusal.feature
 * (REQ-JAVA-HIERARCHY-PUSH-DOWN-REFUSAL-001, row N-PUSH-DOWN of the approved J1 catalogue).
 *
 * It drives the real production JavaLanguageAdapter.applyRefactoring with a pushDownMember
 * request on a minimal Java workspace and asserts that the DECLARED typed refusal code
 * java.hierarchy.pushDown.unsupported EQUALS the ACTUAL refusalCode the adapter returned.
 * Before the production fix this suite is RED: the adapter has no pushDownMember branch and
 * falls through to the generic notImplemented fallback, so refusalCode stays null and the
 * declared-code assertion fails. After the minimal production fix (an explicit pushDownMember
 * branch returning REFUSED with the stable typed refusal code java.hierarchy.pushDown.unsupported,
 * an empty WorkspaceEdit, empty affected-file set, no approval, and no managed-write lease) the
 * suite passes GREEN.
 *
 * Scenario 3 proves the refusal leaves no persistent side effect by snapshotting the workspace
 * root content identity (SHA-256 per regular file) before the first preview and
 * asserting the set is byte-for-byte unchanged afterward (no WAL/transaction/lock/pending-plan
 * artifact and no file create/modify/delete). Observed declared-to-actual mappings are appended
 * to build/reports/cucumber/java-hierarchy-push-down-refusal-codes.txt for reconciliation.
 *
 * Slice glue-shared-refusal-r004 (migration to shared refusal glue): the five Scenario 2
 * inspection/eligibility steps — the caller inspects the refusal plan, the refusal carries an
 * empty WorkspaceEdit, the refusal carries an empty affected-file set, the refusal grants no
 * approval, and the refusal grants no managed-write eligibility — moved verbatim into the
 * shared glue package org.refactorkit.jvm.refusals (KotlinJvmSharedRefusalSteps over the
 * KotlinJvmRefusalScenarioContext port). previewPushDownMember publishes the produced plan
 * into the injected scenario context, and the leaf plan field remains for the leaf Scenario 1
 * and Scenario 3 steps. Step semantics and the @After report/cleanup behavior are unchanged.
 */
class KotlinJvmHierarchyPushDownRefusalSteps(private val context: KotlinJvmRefusalScenarioContext) {
    private val temporaryDirectories = mutableListOf<Path>()
    private lateinit var fixtureRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private var request: RefactoringRequest? = null
    private var plan: PatchPlan? = null
    private var workspaceBaseline: Map<Path, String>? = null
    private var memberMoveDaemon: DaemonSession? = null
    private var memberMoveSnapshotHash: String? = null
    private var memberMoveContext: JsonObject? = null
    private var memberMovePreview: JsonObject? = null
    private var memberMoveSourceImage: Map<String, String>? = null
    private var memberMoveSdkHome: Path? = null
    private val observedRefusals = mutableListOf<ObservedRefusal>()
    private val refusalCode = "java.hierarchy.pushDown.unsupported"

    // ---------------------------------------------------------- Scenario 1 Given

    @Given("^the Java adapter receives a pushDownMember refactoring request on a Java workspace$")
    fun javaAdapterReceivesPushDownMemberRequest() {
        setupFixture()
        request = RefactoringRequest(
            operation = "pushDownMember",
            snapshot = snapshot,
        )
    }

    // -------------------------------------------------- Scenarios 2 & 3 Given

    @Given("^the Java adapter returns a REFUSED patch plan for a pushDownMember request$")
    fun javaAdapterReturnsRefusedPlan() {
        setupFixture()
        previewPushDownMember()
    }

    // ---------------------------------------------------------------- shared When

    @When("^the Java adapter previews the refactoring request$")
    fun javaAdapterPreviewsRequest() {
        previewPushDownMember()
    }

    @When("^the caller checks for persistent side effects$")
    fun callerChecksPersistentSideEffects() {
        // Never overwrite the baseline after the Given has already produced the refusal.
        verifyNoWorkspaceMutation()
    }

    // ------------------------------------------------------- Scenario 1 Thens

    @Then("^the adapter returns a REFUSED patch plan carrying the typed refusal code \"([^\"]+)\"$")
    fun adapterReturnsRefusedPlanWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    @Then("^the refusal operation name is deterministically \"([^\"]+)\"$")
    fun refusalOperationNameIs(declaredOperation: String) {
        val p = requireNotNull(plan)
        assertEquals(declaredOperation, p.operation, p.toString())
    }

    @Then("^the refusal is deterministic for the same request and snapshot$")
    fun refusalIsDeterministic() {
        val first = requireNotNull(plan)
        previewPushDownMember()
        val second = requireNotNull(plan)
        // Deterministic contract: identical operation, status, refusal code, empty edit/affected
        // set, no approval, no authority lease. The PatchPlan id is a fresh random PlanId per
        // preview and is not part of the deterministic refusal contract.
        assertEquals(first.operation, second.operation, "operation must be deterministic")
        assertEquals(first.status, second.status, "status must be deterministic")
        assertEquals(first.refusalCode, second.refusalCode, "refusalCode must be deterministic")
        assertEquals(first.workspaceEdit, second.workspaceEdit, "workspaceEdit must be deterministic")
        assertEquals(first.affectedFiles, second.affectedFiles, "affectedFiles must be deterministic")
        assertEquals(first.requiresUserApproval, second.requiresUserApproval, "approval must be deterministic")
    }

    // ------------------------------------------------------- Scenario 3 Thens

    @Then("^the refusal leaves no pending actionable plan$")
    fun refusalLeavesNoPendingActionablePlan() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no pending actionable plan awaiting approval: ${p.toString()}")
    }

    @Then("^the refusal records no lock$")
    fun refusalRecordsNoLock() {
        val p = requireNotNull(plan)
        assertTrue(p.authorityLease == null, "expected no lock/authority lease on the refusal: ${p.toString()}")
        verifyNoWorkspaceMutation()
    }

    @Then("^the refusal records no WAL$")
    fun refusalRecordsNoWal() {
        verifyNoWorkspaceMutation()
    }

    @Then("^the refusal records no transaction entry$")
    fun refusalRecordsNoTransaction() {
        verifyNoWorkspaceMutation()
    }

    @Then("^the refusal mutates no file on disk$")
    fun refusalMutatesNoFileOnDisk() {
        verifyNoWorkspaceMutation()
    }

    // -------------------------------------------------------- Bounded member move

    @Given("an available Java 8 Maven module has a package-private static int literal method, a same-package target type, and a direct caller")
    fun availableJava8ModuleForMemberMove() {
        val root = temporaryDirectory("rk-jvm-member-move")
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>member-move</artifactId><version>1.0</version>
              <properties><maven.compiler.release>8</maven.compiler.release></properties>
              <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version><configuration><release>8</release><proc>none</proc></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent() + "\n")
        val types = root.resolve("src/main/java/fixture/Types.java")
        types.parent.createDirectories()
        types.writeText("""
            package fixture;

            class Source {
                static int answer() {
                    return 42;
                }
            }

            class Target {
            }
        """.trimIndent() + "\n")
        val client = root.resolve("src/main/java/fixture/Client.java")
        client.parent.createDirectories()
        client.writeText("""
            package fixture;

            public final class Client {
                public int answer() {
                    return Source.answer();
                }
            }
        """.trimIndent() + "\n")
        fixtureRoot = root
        memberMoveSourceImage = javaSourceImage(root)
        snapshot = JavaProjectScanner().scan(root)
        assertTrue(requireNotNull(snapshot).buildModels.isNotEmpty(), "fixture must produce a real Maven source-set model")

        val daemon = DaemonSession()
        memberMoveDaemon = daemon
        val opened = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        memberMoveSnapshotHash = opened.getValue("snapshotHash").jsonPrimitive.content
        memberMoveSdkHome = Path.of(System.getProperty("java.home")).toRealPath()
        memberMoveContext = daemon.dispatch("project.context", buildJsonObject {
            put("expectedSnapshotHash", requireNotNull(memberMoveSnapshotHash))
            put("sdkHome", requireNotNull(memberMoveSdkHome).toString())
        }).jsonObject.also {
            assertEquals("available", it.getValue("status").jsonPrimitive.content, it.toString())
        }
    }

    @When("the IDE asks the daemon to move the saved method selection to the exact target type")
    fun daemonPreviewsBoundStaticMemberMove() {
        val sourcePath = "src/main/java/fixture/Types.java"
        val method = selectMemberMove(sourcePath, "static int answer", "answer")
        assertEquals("method", method.getValue("symbolKind").jsonPrimitive.content)
        val target = selectMemberMove(sourcePath, "class Target", "Target")
        assertEquals("class", target.getValue("symbolKind").jsonPrimitive.content)
        memberMovePreview = requireNotNull(memberMoveDaemon).dispatch("refactor.preview", buildJsonObject {
            put("operation", "moveMember")
            put("languageId", "java")
            put("symbol", method.getValue("symbolId").jsonPrimitive.content)
            put("expectedSnapshotHash", requireNotNull(memberMoveSnapshotHash))
            put("javaContext", memberMoveContextRequest())
            put("javaSelection", method)
            put("arguments", buildJsonObject {
                put("memberKind", "method")
                put("file", sourcePath)
                put("targetType", target.getValue("symbolId").jsonPrimitive.content)
                put("targetBindingKey", target.getValue("bindingKey").jsonPrimitive.content)
            })
        }).jsonObject
    }

    @Then("RefactorKit advertises compiler-backed managed moveMember and previews the exact bound invocation update without new JDT errors")
    fun memberMoveCapabilityAndPreviewAreExact() {
        val daemon = requireNotNull(memberMoveDaemon)
        val capabilities = daemon.dispatch("server.capabilities", buildJsonObject { }).jsonObject
        val adapters = capabilities.getValue("languageKernel").jsonObject.getValue("adapters").jsonArray
        val javaAdapter = adapters.single { it.jsonObject.getValue("languageId").jsonPrimitive.content == "java" }.jsonObject
        val capability = javaAdapter.getValue("capabilities").jsonArray.single {
            it.jsonObject.getValue("operation").jsonPrimitive.content == "moveMember"
        }.jsonObject
        assertEquals("stable", capability.getValue("stability").jsonPrimitive.content)
        assertEquals("compiler", capability.getValue("evidence").jsonPrimitive.content)
        assertEquals("managed-stable", capability.getValue("mutationAuthority").jsonPrimitive.content)

        val preview = requireNotNull(memberMovePreview)
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, preview.toString())
        assertEquals("moveMember", preview.getValue("operation").jsonPrimitive.content)
        assertEquals("JDT_BINDING", preview.getValue("evidence").jsonPrimitive.content)
        assertEquals("HIGH", preview.getValue("riskLevel").jsonPrimitive.content)
        assertTrue(preview.getValue("warnings").jsonArray.isNotEmpty(), "bounded move must disclose consumers outside its proven reference closure")
        assertEquals(
            setOf("src/main/java/fixture/Types.java", "src/main/java/fixture/Client.java"),
            preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        val diff = preview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(diff.contains("fixture.Target.answer()"), diff)
        assertTrue(diff.contains("+    static int answer() {\n+        return 42;\n+    }"), diff)
        assertTrue(preview.getValue("diagnosticsAfterPreview").jsonArray.none {
            it.jsonObject.getValue("severity").jsonPrimitive.content == "ERROR"
        }, preview.toString())
    }

    @And("the staged project compiles and daemon Apply followed by rollback restores every captured source byte and path")
    fun memberMoveAppliesCompilesAndRollsBackExactly() {
        val daemon = requireNotNull(memberMoveDaemon)
        val root = requireNotNull(fixtureRoot)
        val preview = requireNotNull(memberMovePreview)
        val originalHash = requireNotNull(memberMoveSnapshotHash)
        val applied = daemon.dispatch("refactor.apply", buildJsonObject {
            put("planId", preview.getValue("planId").jsonPrimitive.content)
            put("expectedSnapshotHash", originalHash)
            put("javaContext", memberMoveContextRequest())
        }).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content.lowercase(), applied.toString())
        val caller = Files.readString(root.resolve("src/main/java/fixture/Client.java"))
        assertTrue(caller.contains("fixture.Target.answer()"), caller)
        val types = Files.readString(root.resolve("src/main/java/fixture/Types.java"))
        val sourceBody = types.substringAfter("class Source {").substringBefore("\n}")
        val targetBody = types.substringAfter("class Target {").substringBefore("\n}")
        assertTrue(!sourceBody.contains("static int answer()"), types)
        assertTrue(targetBody.contains("static int answer()"), types)
        compileJava8Fixture(root)

        val transactionId = applied.getValue("transactionId").jsonPrimitive.content
        val postHash = applied.getValue("snapshotHash").jsonPrimitive.content
        val rolledBack = daemon.dispatch("patch.rollback", buildJsonObject {
            put("transactionId", transactionId)
            put("expectedSnapshotHash", postHash)
            put("javaContext", memberMoveContextRequest())
        }).jsonObject
        assertEquals("rolledback", rolledBack.getValue("status").jsonPrimitive.content.lowercase().replace("_", "").replace("-", ""), rolledBack.toString())
        assertEquals(requireNotNull(memberMoveSourceImage), javaSourceImage(root))
    }

    private fun selectMemberMove(path: String, lineMarker: String, selectedName: String): JsonObject {
        val root = requireNotNull(fixtureRoot)
        val text = Files.readString(root.resolve(path))
        val lines = text.lines()
        val line = lines.indexOfFirst { lineMarker in it }
        assertTrue(line >= 0, text)
        val column = lines[line].indexOf(selectedName) + 1
        val response = requireNotNull(memberMoveDaemon).dispatch("intelligence.query", buildJsonObject {
            put("requestId", "move-member-${selectedName.lowercase()}-selection")
            put("kind", "mutationSelection")
            put("languageId", "java")
            put("path", path)
            put("position", buildJsonObject { put("line", line); put("character", column) })
            put("expectedSnapshotHash", requireNotNull(memberMoveSnapshotHash))
            put("javaContext", memberMoveContextRequest())
            put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
        }).jsonObject
        assertEquals("ready", response.getValue("status").jsonPrimitive.content, response.toString())
        return response.getValue("selection").jsonObject
    }

    private fun memberMoveContextRequest() = buildJsonObject {
        put("sdkHome", requireNotNull(memberMoveSdkHome).toString())
        put("expectedContextHash", requireNotNull(memberMoveContext).getValue("contextHash").jsonPrimitive.content)
        memberMoveContext?.get("schemaVersion")?.jsonPrimitive?.content?.let { put("contextSchemaVersion", it) }
    }

    private fun javaSourceImage(root: Path): Map<String, String> {
        val sourceRoot = root.resolve("src/main/java")
        return Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted().toList()
                .associate { path -> root.relativize(path).toString().replace('\\', '/') to sha256(Files.readAllBytes(path)) }
        }
    }

    private fun compileJava8Fixture(root: Path) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("A JDK compiler is required for this Story")
        val sourceRoot = root.resolve("src/main/java")
        val outputDirectory = root.resolve("build/member-move-classes")
        outputDirectory.createDirectories()
        val sources = Files.walk(sourceRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java") }
                .sorted().toList()
        }
        val output = ByteArrayOutputStream()
        val arguments = listOf("--release", "8", "-proc:none", "-d", outputDirectory.toString()) + sources.map(Path::toString)
        val result = compiler.run(null, output, output, *arguments.toTypedArray())
        assertEquals(0, result, output.toString(Charsets.UTF_8))
    }

    // ------------------------------------------------------------------ helpers

    private fun setupFixture() {
        val root = temporaryDirectory("rk-jvm-hierarchy-push-down-refusal")
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>java-refusal</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
            </project>
        """.trimIndent())
        root.resolve("src/main/java/fixture/api/Calculator.java").apply {
            parent.createDirectories()
            writeText(
                "package fixture.api;\n" +
                    "public class Calculator {\n" +
                    "    public int add(int a, int b) { return a + b; }\n" +
                    "}\n",
            )
        }
        fixtureRoot = root
        snapshot = JavaProjectScanner().scan(root)
        workspaceBaseline = snapshotWorkspaceContent(root)
    }

    private fun previewPushDownMember() {
        val snap = snapshot
        val adapter = JavaLanguageAdapter()
        val req = request ?: RefactoringRequest(
            operation = "pushDownMember",
            snapshot = snap,
        )
        request = req
        plan = adapter.applyRefactoring(req)
        context.plan = plan
    }

    private fun snapshotWorkspaceContent(root: Path): Map<Path, String> {
        if (!Files.isDirectory(root)) return emptyMap()
        val files = Files.walk(root).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) }.toList()
        }
        return files.associate { path -> path.normalize() to sha256(Files.readAllBytes(path)) }
    }

    private fun verifyNoWorkspaceMutation() {
        val baseline = requireNotNull(workspaceBaseline) { "persistent-side-effect baseline was not captured" }
        val current = snapshotWorkspaceContent(fixtureRoot)
        assertTrue(!Files.exists(fixtureRoot.resolve(".refactorkit")), "refusal must not create transaction/lock metadata")
        assertEquals(
            baseline, current,
            "expected no filesystem mutation on the workspace root after the refusal (no file " +
                "created, modified, or deleted; no WAL/transaction/lock/pending-plan artifact written): " +
                "${current - baseline}",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun temporaryDirectory(prefix: String): Path {
        val base = Path.of(System.getProperty("user.dir")).resolve("build/test-tmp").toAbsolutePath().normalize()
        Files.createDirectories(base)
        return Files.createTempDirectory(base, prefix).also(temporaryDirectories::add)
    }

    private fun deleteNoFollow(root: Path) {
        if (!root.exists()) return
        val paths = Files.walk(root).use { stream -> stream.toList() }
        paths.sortedByDescending(Path::getNameCount).forEach { path ->
            require(!Files.isSymbolicLink(path)) { "Temporary test cleanup refuses symbolic link: $path" }
            Files.delete(path)
        }
    }

    @After
    fun cleanup(scenario: Scenario) {
        memberMoveDaemon?.close()
        memberMoveDaemon = null
        val reportDir = Path.of(System.getProperty("user.dir")).resolve("build/reports/cucumber")
        reportDir.createDirectories()
        val report = reportDir.resolve("java-hierarchy-push-down-refusal-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredCode} -> ${it.actualCode} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} refusal=${it.refusalCode ?: "-"} operation=${it.operation}" }
        Files.write(report, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)

        var cleanupFailure: Throwable? = null
        temporaryDirectories.asReversed().forEach { directory ->
            try {
                deleteNoFollow(directory)
            } catch (failure: Throwable) {
                cleanupFailure?.addSuppressed(failure) ?: run { cleanupFailure = failure }
            }
        }
        temporaryDirectories.clear()
        cleanupFailure?.let { throw it }
    }

    private data class ObservedRefusal(
        val declaredCode: String,
        val actualCode: String?,
        val status: PatchStatus,
    )
}

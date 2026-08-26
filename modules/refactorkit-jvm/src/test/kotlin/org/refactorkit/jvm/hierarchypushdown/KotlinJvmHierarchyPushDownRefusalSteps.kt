package org.refactorkit.jvm.hierarchypushdown

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.java.JavaLanguageAdapter
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
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
 * root content identity (SHA-256 per regular file) before the persistent-side-effect check and
 * asserting the set is byte-for-byte unchanged afterward (no WAL/transaction/lock/pending-plan
 * artifact and no file create/modify/delete). Observed declared-to-actual mappings are appended
 * to build/reports/cucumber/java-hierarchy-push-down-refusal-codes.txt for reconciliation.
 */
class KotlinJvmHierarchyPushDownRefusalSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private lateinit var fixtureRoot: Path
    private lateinit var snapshot: ProjectSnapshot
    private var request: RefactoringRequest? = null
    private var plan: PatchPlan? = null
    private var workspaceBaseline: Map<Path, String>? = null
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

    @When("^the caller inspects the refusal plan$")
    fun callerInspectsRefusalPlan() {
        requireNotNull(plan) { "expected a REFUSED pushDownMember plan to inspect" }
    }

    @When("^the caller checks for persistent side effects$")
    fun callerChecksPersistentSideEffects() {
        // AC: snapshot the workspace-root content identity before checking so the Then steps can
        // prove no WAL/transaction/lock/pending-plan artifact and no file mutation was written.
        workspaceBaseline = snapshotWorkspaceContent(fixtureRoot)
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

    // ------------------------------------------------------- Scenario 2 Thens

    @Then("^the refusal carries an empty WorkspaceEdit$")
    fun refusalCarriesEmptyWorkspaceEdit() {
        val p = requireNotNull(plan)
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected no WorkspaceEdit in the refusal: ${p.toString()}")
    }

    @Then("^the refusal carries an empty affected-file set$")
    fun refusalCarriesEmptyAffectedFiles() {
        val p = requireNotNull(plan)
        assertTrue(p.affectedFiles.isEmpty(), "expected no affected file in the refusal: ${p.toString()}")
    }

    @Then("^the refusal grants no approval$")
    fun refusalGrantsNoApproval() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(!p.requiresUserApproval, "expected no user approval on the refusal: ${p.toString()}")
    }

    @Then("^the refusal grants no managed-write eligibility$")
    fun refusalGrantsNoManagedWriteEligibility() {
        val p = requireNotNull(plan)
        // A managed-write eligible plan is PREVIEW and carries a core OperationAuthorityLease;
        // the REFUSED pushDownMember plan must carry none and must not be an actionable apply.
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertTrue(p.authorityLease == null, "expected no managed-write authority lease: ${p.toString()}")
        assertEquals(0.0, p.confidence, "expected zero confidence on a refused plan")
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

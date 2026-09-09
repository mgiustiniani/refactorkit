package org.refactorkit.jvm.movedeclaration

import io.cucumber.java.After
import io.cucumber.java.Scenario
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SymbolId
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.jvm.KotlinJvmMoveDeclarationPlanner
import org.refactorkit.kotlin.KotlinCompilerDiagnostics
import org.refactorkit.kotlin.KotlinCompilerSymbolsResult
import org.refactorkit.kotlin.KotlinJvmBuildModelIntegration
import org.refactorkit.kotlin.KotlinLanguageAdapter
import org.refactorkit.kotlin.KotlinSemanticToolchain
import org.refactorkit.kotlin.KotlinToolchainDiscoverer
import org.refactorkit.kotlin.KotlinToolchainDiscovery
import org.refactorkit.kotlin.KotlinToolchainRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Story BDD glue for the standalone companion-object refusal
 * (REQ-KOTLIN-MOVE-COMPANION-REFUSAL-001, AC-COMPANION-REFUSAL-001..004).
 *
 * It replicates the K2 toolchain fixture from
 * KotlinJavaPublicTypeRenamePlannerTest (kotlin-compiler-embeddable-2.0.21,
 * jvmTarget 21, jdkToolchain 21) and drives the real KotlinJvmMoveDeclarationPlanner.
 * The feature file declares the ACTUAL production refusal code kotlin.moveCompanionStandaloneUnsupported
 * observed by the glue for the standalone companion selection, so the refusal step asserts that the
 * DECLARED code EQUALS the ACTUAL refusalCode the planner returned; the suite FAILS on a typed-code
 * regression. Every observed declared-to-actual mapping is written to
 * build/reports/cucumber/kotlin-jvm-move-companion-refusal-codes.txt for reconciliation.
 *
 * The companion evidence comes from the K2 compiler PSI (declaration.isCompanion), and the planner
 * refuses target.kind == OBJECT with declaration.isCompanion via kotlin.moveCompanionStandaloneUnsupported
 * (KotlinJvmMoveDeclarationPlanner line ~79). Missing approval keeps its earlier fail-closed
 * precedence (kotlin.moveExternalConsumerApprovalRequired) because the approval check runs before the
 * companion refusal. The nested non-companion object is asserted to NOT receive the companion code and
 * to follow its own object shape handling (actual refusal kotlin.moveDeclarationUnsupported).
 */
class KotlinJvmMoveCompanionRefusalSteps {
    private val temporaryDirectories = mutableListOf<Path>()
    private val observedRefusals = mutableListOf<ObservedRefusal>()

    private var fixtureRoot: Path? = null
    private var snapshot: ProjectSnapshot? = null
    private lateinit var toolchain: KotlinSemanticToolchain
    private var selectedName: String? = null
    private var acceptExternalConsumerRisk = false
    private var plan: PatchPlan? = null

    // AC-COMPANION-REFUSAL-002 real observable probe: the content identity of every regular file on
    // the workspace root immediately before the preview. The refusal Then must prove the set is
    // byte-for-byte unchanged afterward (no file created, modified, or deleted), which also proves
    // no WAL/transaction record, pending managed-plan, or lock artifact was written into the workspace.
    private var workspaceBaseline: Map<Path, String>? = null

    private val companionCode = "kotlin.moveCompanionStandaloneUnsupported"
    private val missingApprovalCode = "kotlin.moveExternalConsumerApprovalRequired"
    private val nestedObjectCode = "kotlin.moveDeclarationUnsupported"

    // ------------------------------------------------------------------ AC-COMPANION-REFUSAL-001

    @Given("^the caller explicitly accepts unknown external-consumer risk$")
    fun callerAcceptsExternalConsumerRisk() {
        acceptExternalConsumerRisk = true
    }

    @Given(
        "^the selected declaration is a compiler-proven companion object with exact K2 PSI evidence explicitly identifying the declaration as a companion object$",
    )
    fun selectedCompanionObject() {
        val root = temporaryDirectory("rk-jvm-move-companion")
        writePom(root)
        // Real compiler-proven companion source: the K2 compiler PSI marks the standalone
        // companion object (name "Companion") with companion evidence and the nested object
        // NestedRegistry with non-companion evidence. The planner refuses an independently moved
        // companion with kotlin.moveCompanionStandaloneUnsupported before any patch.
        root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture.api\npublic class PublicGreeting {\n" +
                    "    public companion /* compiler-PSI evidence */ object { public fun create() = PublicGreeting() }\n" +
                    "    public object NestedRegistry\n" +
                    "}\n",
            )
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        selectedName = "Companion"
        // The companion selection is otherwise exact and approved for the external-consumer risk
        // (Scenario 1), so the planner reaches the companion refusal path.
        acceptExternalConsumerRisk = true
        val catalogue = compilerCatalogue()
        val target = assertNotNull(
            catalogue.index.symbols.singleOrNull { it.name == "Companion" },
            "expected the K2 catalogue to identify the standalone companion object",
        )
        assertTrue(
            catalogue.declarations.getValue(target.id).isCompanion,
            "expected exact K2 PSI evidence to identify the declaration as a companion object",
        )
    }

    @Given("^the selected declaration is a nested object that is not a companion object$")
    fun selectedNestedNonCompanionObject() {
        val root = temporaryDirectory("rk-jvm-move-nested")
        writePom(root)
        root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture.api\npublic class PublicGreeting {\n" +
                    "    public companion /* compiler-PSI evidence */ object { public fun create() = PublicGreeting() }\n" +
                    "    public object NestedRegistry\n" +
                    "}\n",
            )
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        selectedName = "NestedRegistry"
        // Scenario 2 omits the external-consumer approval precondition; the glue accepts the risk so
        // the planner reaches the nested-object shape handling rather than the earlier approval refusal.
        acceptExternalConsumerRisk = true
        val catalogue = compilerCatalogue()
        val target = assertNotNull(
            catalogue.index.symbols.singleOrNull { it.name == "NestedRegistry" },
            "expected the K2 catalogue to identify the nested non-companion object",
        )
        assertTrue(
            !catalogue.declarations.getValue(target.id).isCompanion,
            "expected exact K2 PSI evidence to NOT identify the nested object as a companion object",
        )
    }

    @Given("^exact K2 PSI evidence does not identify the declaration as a companion object$")
    fun k2EvidenceDoesNotIdentifyCompanion() {
        val catalogue = compilerCatalogue()
        val target = assertNotNull(
            catalogue.index.symbols.singleOrNull { it.name == "NestedRegistry" },
            "expected the K2 catalogue to identify the nested non-companion object",
        )
        assertTrue(
            !catalogue.declarations.getValue(target.id).isCompanion,
            "expected exact K2 PSI evidence to NOT identify the declaration as a companion object",
        )
    }

    // ------------------------------------------------------------------ AC-COMPANION-REFUSAL-004

    @Given(
        "^the selected declaration is an otherwise exact approved companion selection with no explicit approval of unknown external-consumer risk$",
    )
    fun selectedCompanionWithoutExternalApproval() {
        val root = temporaryDirectory("rk-jvm-move-companion-no-approval")
        writePom(root)
        root.resolve("src/main/kotlin/fixture/api/PublicGreeting.kt").apply {
            parent.createDirectories()
            writeText(
                "package fixture.api\npublic class PublicGreeting {\n" +
                    "    public companion /* compiler-PSI evidence */ object { public fun create() = PublicGreeting() }\n" +
                    "    public object NestedRegistry\n" +
                    "}\n",
            )
        }
        fixtureRoot = root
        toolchain = toolchain(root)
        snapshot = KotlinJvmBuildModelIntegration.attach(JavaProjectScanner().scan(root), toolchain)
        selectedName = "Companion"
        // No explicit approval: the caller does NOT accept unknown external-consumer risk, so the
        // planner retains its fail-closed precedence and refuses for missing approval BEFORE the
        // companion refusal code applies.
        acceptExternalConsumerRisk = false
        val catalogue = compilerCatalogue()
        val target = assertNotNull(
            catalogue.index.symbols.singleOrNull { it.name == "Companion" },
            "expected the K2 catalogue to identify the standalone companion object",
        )
        assertTrue(
            catalogue.declarations.getValue(target.id).isCompanion,
            "expected exact K2 PSI evidence to identify the companion selection",
        )
    }

    // ------------------------------------------------------------------ shared When

    @When("^moveDeclaration previews the selection$")
    fun moveDeclarationPreviewsSelection() {
        // AC-COMPANION-REFUSAL-002: snapshot the workspace-root content identity before the preview so
        // the refusal Then can prove no filesystem mutation (create/modify/delete) and no WAL/transaction,
        // pending managed-plan, or lock artifact was written into the workspace.
        workspaceBaseline = snapshotWorkspaceContent(requireNotNull(fixtureRoot))
        val snap = requireNotNull(snapshot)
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        val catalogue = compilerCatalogue()
        val targetId = requireNotNull(
            catalogue.index.symbols.singleOrNull { it.name == requireNotNull(selectedName) },
        ) { "expected the K2 catalogue to contain the selected declaration '${selectedName}'" }.id
        plan = KotlinJvmMoveDeclarationPlanner(adapter).preview(
            snap, targetId, "fixture.accounting", acceptExternalConsumerRisk = acceptExternalConsumerRisk,
        )
    }

    // ------------------------------------------------------------------ AC-COMPANION-REFUSAL-001 refusal Then

    @Then("^the selection is refused with stable typed code \"([^\"]+)\"$")
    fun selectionRefusedWithCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        assertEquals(
            declaredCode, p.refusalCode,
            "declared refusal code '$declaredCode' did not equal the actual code '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    @Then("^the refusal contains no WorkspaceEdit, affected file, pending managed plan, lock, WAL, transaction, or filesystem mutation$")
    fun refusalContainsNoMutation() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        // AC-002 surface 1: no WorkspaceEdit.
        assertTrue(p.workspaceEdit.edits.isEmpty(), "expected no WorkspaceEdit in the refusal: ${p.toString()}")
        // AC-002 surface 2: no affected file.
        assertTrue(p.affectedFiles.isEmpty(), "expected no affected file in the refusal: ${p.toString()}")
        // AC-002 surface 3: no pending managed plan. A managed plan awaiting apply would be PREVIEW,
        // require explicit user approval, and carry a core lock-boundary authority lease; the refusal
        // is REFUSED with requiresUserApproval=false and no lease.
        assertTrue(!p.requiresUserApproval, "expected no pending managed plan awaiting approval in the refusal: ${p.toString()}")
        assertTrue(p.authorityLease == null, "expected no managed under-lock authority lease in the refusal: ${p.toString()}")
        // AC-002 surface 4: no lock acquired/held. Core materializes the workspace lock only as an
        // OperationAuthorityLease on a managed preview; a REFUSED plan carries none (asserted above).
        // AC-002 surfaces 5/6: no WAL or transaction record. Preview constructs no Transaction and
        // writes no transaction/WAL file; the workspace-root content probe below proves no such
        // artifact appeared.
        // AC-002 surface 7: no filesystem mutation (create/modify/delete) on the workspace root.
        verifyNoWorkspaceMutation()
    }

    // ------------------------------------------------------------------ AC-COMPANION-REFUSAL-001 second sentence (nested non-companion)

    @Then("^the selection does not receive stable typed code \"([^\"]+)\"$")
    fun selectionDoesNotReceiveCode(declaredCode: String) {
        val p = requireNotNull(plan)
        assertTrue(
            p.refusalCode != declaredCode,
            "the nested non-companion object must not receive the companion code '$declaredCode': ${p.toString()}",
        )
        observedRefusals += ObservedRefusal(declaredCode, p.refusalCode, p.status)
    }

    @Then("^the selection follows its own object shape handling under the independently qualified whole-file contract$")
    fun selectionFollowsOwnObjectShapeHandling() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        // Anti-fake: assert the ACTUAL production code the planner returned for the nested
        // non-companion object (kotlin.moveDeclarationUnsupported), not an invented granular code.
        assertEquals(
            nestedObjectCode, p.refusalCode,
            "expected the nested non-companion object to follow its own object shape handling, got '${p.refusalCode}'",
        )
        assertTrue(p.workspaceEdit.edits.isEmpty(), p.toString())
        assertTrue(p.affectedFiles.isEmpty(), p.toString())
        observedRefusals += ObservedRefusal(nestedObjectCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ AC-COMPANION-REFUSAL-004 fail-closed precedence

    @Then("^the selection is refused for missing approval before the companion refusal code applies$")
    fun selectionRefusedForMissingApprovalBeforeCompanionCode() {
        val p = requireNotNull(plan)
        assertEquals(PatchStatus.REFUSED, p.status, p.toString())
        // Anti-fake: assert the ACTUAL production code (kotlin.moveExternalConsumerApprovalRequired);
        // the approval check runs before the companion refusal, so the companion code must not apply.
        assertEquals(
            missingApprovalCode, p.refusalCode,
            "expected the missing-approval refusal code '$missingApprovalCode', got '${p.refusalCode}'",
        )
        assertTrue(
            p.refusalCode != companionCode,
            "the companion refusal code must not apply before the missing-approval refusal",
        )
        observedRefusals += ObservedRefusal(missingApprovalCode, p.refusalCode, p.status)
    }

    // ------------------------------------------------------------------ helpers

    private fun snapshotWorkspaceContent(root: Path): Map<Path, String> {
        if (!Files.isDirectory(root)) return emptyMap()
        val files = Files.walk(root).use { stream ->
            stream.filter { path -> Files.isRegularFile(path) }.toList()
        }
        return files.associate { path -> path.normalize() to sha256(Files.readAllBytes(path)) }
    }

    private fun verifyNoWorkspaceMutation() {
        val root = requireNotNull(fixtureRoot)
        val baseline = requireNotNull(workspaceBaseline)
        val current = snapshotWorkspaceContent(root)
        assertEquals(
            baseline, current,
            "expected no filesystem mutation on the workspace root after the refusal (no file created, " +
                "modified, or deleted; no WAL/transaction/plan/lock artifact written); changed: ${current - baseline}",
        )
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun compilerCatalogue(): KotlinCompilerSymbolsResult.Available {
        val adapter = KotlinLanguageAdapter(KotlinCompilerDiagnostics(toolchain))
        return assertIs<KotlinCompilerSymbolsResult.Available>(
            adapter.compilerSymbols(requireNotNull(snapshot)),
            "expected the K2 compiler symbols catalogue to be available",
        )
    }

    private fun writePom(root: Path) {
        root.resolve("pom.xml").writeText("""
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>mixed</artifactId><version>1</version>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <build><plugins><plugin>
                <groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-maven-plugin</artifactId><version>2.0.21</version>
                <configuration><jvmTarget>21</jvmTarget><jdkToolchain><version>21</version></jdkToolchain></configuration>
              </plugin></plugins></build>
            </project>
        """.trimIndent())
    }

    private fun toolchain(workspace: Path): KotlinSemanticToolchain {
        val requiredRuntimePrefixes = listOf(
            "kotlin-compiler-embeddable-2.0.21", "kotlin-stdlib-2.0.21",
            "kotlin-script-runtime-2.0.21", "kotlin-reflect-1.6.10",
            "kotlin-daemon-embeddable-2.0.21", "trove4j-1.0.20200330",
            "kotlinx-coroutines-core-jvm-1.6.4", "annotations-13.0",
        )
        val runtime = System.getProperty("kotlin.compiler.test.classpath")
            .split(File.pathSeparator).map(Path::of)
            .filter { path -> Files.isRegularFile(path) && requiredRuntimePrefixes.any {
                path.fileName.toString().startsWith(it)
            } }
        val compilerSource = runtime.single { it.fileName.toString().startsWith("kotlin-compiler-embeddable-2.0.21") }
        // Borrow Gradle-resolved artifacts as read-only inputs; cleanup owns only fixture workspaces.
        val compiler = compilerSource
        val classpath = runtime.filterNot { it == compilerSource }.distinctBy { it.fileName.toString() }
        val discovery = KotlinToolchainDiscoverer().discover(KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = Path.of(System.getProperty("java.home")),
            compilerJar = compiler,
            compilerClasspath = classpath,
        ))
        return assertIs<KotlinToolchainDiscovery.Available>(discovery).toolchain
    }

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
        val report = reportDir.resolve("kotlin-jvm-move-companion-refusal-codes.txt")
        val lines = mutableListOf<String>()
        lines += "scenario:${scenario.name}"
        observedRefusals.forEach { lines += "  ${it.declaredCode} -> ${it.actualCode} (${it.status})" }
        plan?.let { lines += "  plan:${it.status} refusal=${it.refusalCode ?: "-"}" }
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

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CExplicitCallArgument
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CSignaturePlanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterInsertionApplySteps {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var gate: DiagnosticsGate
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var privateBefore: Set<Path>
    private var case = ""
    private var result: ApplyResult? = null

    @Given("a compiler-bound C parameter insertion awaits {string}")
    fun prepare(condition: String) {
        case = condition
        val workspace = Files.createTempDirectory("refactorkit-c-add-apply-")
        root = workspace
        original = "int required[REQUIRED - 16];\nstatic int weighted(int x, int y);\n" +
            "static int weighted(int a, int b) { return a * 10 + b; }\nint main(void) { return weighted(9, 8); }\n"
        expectedSource = "int required[REQUIRED - 16];\nstatic int weighted(int x, int limit, int y);\n" +
            "static int weighted(int a, int limit, int b) { return a * 10 + b; }\nint main(void) { return weighted(9, (7), 8); }\n"
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        manager = ExternalSemanticProcessManager()
        facade = CRefactoringFacade(toolchain, requireNotNull(manager))
        val before = assertIs<CFunctionFactsResult.Available>(CFunctionFactsService(toolchain, requireNotNull(manager)).analyze(snapshot, Path.of("main.c"), "weighted")).facts
        CSignaturePlanner(toolchain, requireNotNull(manager)).use { planner ->
            plan = planner.addParameter(snapshot, Path.of("main.c"), "weighted", "limit", 1, listOf(CExplicitCallArgument(before.calls.single().span, "7")))
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val runtimeIdentity = assertNotNull(toolchain.provenance.nativeRuntimeIdentity)
        assertEquals(runtimeIdentity, plan.authorityLease?.attributes?.get("c.nativeRuntimeIdentity"))
        gate = requireNotNull(facade).diagnosticsGate(snapshot, plan)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        privateBefore = privateDirectories()
    }

    @When("library apply evaluates that explicit parameter insertion")
    fun apply() {
        if (case == "database changed") { database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database) }
        if (case == "preview lease removed") plan = plan.copy(authorityLease = null)
        if (case == "native runtime identity changed") {
            val lease = assertNotNull(plan.authorityLease)
            plan = plan.copy(authorityLease = OperationAuthorityLease(
                kind = lease.kind,
                operation = lease.operation,
                snapshotHash = lease.snapshotHash,
                evidenceHash = lease.evidenceHash,
                evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                requiredFileEvidence = lease.requiredFileEvidence,
                attributes = lease.attributes + ("c.nativeRuntimeIdentity" to "0".repeat(64)),
            ))
        }
        if (case == "caller expression tampered") {
            val old = assertNotNull(plan.authorityLease)
            val changed = old.attributes.getValue("c.callArguments").replace("\"expression\":\"7\"", "\"expression\":\"99\"")
            assertTrue(changed != old.attributes.getValue("c.callArguments"))
            plan = plan.copy(authorityLease = OperationAuthorityLease(old.kind, old.operation, old.snapshotHash, old.evidenceHash,
                workspaceEditSha256 = old.workspaceEditSha256, attributes = old.attributes + ("c.callArguments" to changed)))
        }
        val callerGate = if (case == "approved retained gate") gate else DiagnosticsGate.disabled("caller-disabled")
        val authorization = if (case == "approval missing") ApplyAuthorization.missing("c-add-story", "bdd-caller") else ApplyAuthorization.explicit("c-add-story", "bdd-caller")
        result = requireNotNull(facade).apply(snapshot, plan, authorization, callerGate)
    }

    @Then("the managed parameter insertion has outcome {string}")
    fun outcome(expected: String) {
        val workspace = requireNotNull(root)
        if (expected == "applied and rolled back") {
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
            assertEquals("c-add-story", applied.transaction.approval.surface)
            assertEquals("bdd-caller", applied.transaction.approval.actor)
            assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
            assertTrue(CProjectScanner().scan(workspace).hash != snapshot.hash)
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(original, Files.readString(workspace.resolve("main.c")))
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            val refused = assertIs<ApplyResult.Refused>(result, result.toString())
            assertTrue(refused.diagnostics.any { diagnostic -> when (expected) {
                "approval refused" -> diagnostic.code == "approval.required"
                "missing authority refused", "changed authority refused" -> diagnostic.message.contains("c.signatureAdd.authorityInvalid")
                "native runtime identity refused" -> diagnostic.message.contains("c.signatureAdd.authorityInvalid")
                "changed input refused" -> diagnostic.code in setOf("snapshot.scopeChanged", "snapshot.changed", "authorityLease.snapshotMismatch") || diagnostic.message.contains("c.signatureAdd.authorityInvalid")
                else -> error("Unexpected outcome: $expected")
            } }, refused.diagnostics.toString())
            assertEquals(original, Files.readString(workspace.resolve("main.c")))
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    @Then("managed parameter insertion retains configuration and releases compiler material")
    fun intact() {
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
        assertEquals(privateBefore, privateDirectories())
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() {
        facade?.close(); manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

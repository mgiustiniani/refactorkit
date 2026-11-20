package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterReorderingApplySteps {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var gate: DiagnosticsGate
    private lateinit var savedSource: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var privateBefore: Set<Path>
    private var case = ""
    private var result: ApplyResult? = null

    @Given("a compiler-bound C parameter reorder awaits {string}")
    fun prepare(condition: String) {
        case = condition
        val workspace = Files.createTempDirectory("refactorkit-c-reorder-apply-")
        root = workspace
        savedSource = "int required[REQUIRED - 16];\n" +
            "static int weighted(int x, int y, int z);\n" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(9, 8, 7); }\n"
        expectedSource = "int required[REQUIRED - 16];\n" +
            "static int weighted(int z, int y, int x);\n" +
            "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(7, 8, 9); }\n"
        Files.writeString(workspace.resolve("main.c"), savedSource)
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
        CSignaturePlanner(toolchain, requireNotNull(manager)).use { planner ->
            plan = planner.reorderParameters(snapshot, Path.of("main.c"), "weighted", listOf(2, 1, 0))
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        gate = requireNotNull(facade).diagnosticsGate(snapshot, plan)
        assertEquals(savedSource, Files.readString(workspace.resolve("main.c")))
        privateBefore = privateDirectories()
    }

    @When("library apply evaluates that parameter reorder authorization")
    fun apply() {
        if (case == "database changed") {
            database += " \n"
            Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database)
        }
        if (case == "unrelated body edit added") {
            val index = savedSource.lines()[2].indexOf("100")
            plan = plan.copy(workspaceEdit = WorkspaceEdit(plan.workspaceEdit.edits + FileEdit.Modify(Path.of("main.c"),
                listOf(TextEdit(SourceRange(SourcePosition(2, index), SourcePosition(2, index + 3)), "101")))))
        }
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
        val callerGate = if (case == "approved retained gate") gate else DiagnosticsGate.disabled("caller-disabled")
        val authorization = if (case == "approval missing") ApplyAuthorization.missing("c-reorder-story", "bdd-caller")
            else ApplyAuthorization.explicit("c-reorder-story", "bdd-caller")
        result = requireNotNull(facade).apply(snapshot, plan, authorization, callerGate)
    }

    @Then("the managed parameter reorder has outcome {string}")
    fun outcome(expected: String) {
        val workspace = requireNotNull(root)
        if (expected == "applied and rolled back") {
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals("c.signature-reorder-v1", plan.authorityLease?.kind)
            assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
            assertEquals("c-reorder-story", applied.transaction.approval.surface)
            assertEquals("bdd-caller", applied.transaction.approval.actor)
            assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
            assertTrue(CProjectScanner().scan(workspace).hash != snapshot.hash)
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(savedSource, Files.readString(workspace.resolve("main.c")))
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            val refused = assertIs<ApplyResult.Refused>(result, result.toString())
            assertTrue(refused.diagnostics.any { diagnostic -> when (expected) {
                "approval refused" -> diagnostic.code == "approval.required"
                "changed authority refused" -> diagnostic.code in setOf("snapshot.changed", "authorityLease.snapshotMismatch", "snapshot.underLockChanged", "snapshot.scopeChanged", "diagnostics.unavailable")
                "changed edit refused" -> diagnostic.code == "authorityLease.workspaceEditMismatch" || diagnostic.message.contains("c.signatureReorder.authorityInvalid")
                "missing authority refused" -> diagnostic.message.contains("c.signatureReorder.authorityInvalid")
                "native runtime identity refused" -> diagnostic.message.contains("c.signatureReorder.authorityInvalid")
                else -> error("Unexpected outcome: $expected")
            } }, refused.diagnostics.toString())
            assertEquals(savedSource, Files.readString(workspace.resolve("main.c")))
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    @Then("managed parameter reordering retains configuration bytes and releases compiler processes")
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

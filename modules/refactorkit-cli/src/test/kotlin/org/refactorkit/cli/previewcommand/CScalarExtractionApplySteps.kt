package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CFunctionByteSpan
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CScalarExtractionPlanner
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

class CScalarExtractionApplySteps {
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
    private var condition = ""
    private var result: ApplyResult? = null

    @Given("a compiler-bound C scalar temporary awaits {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-scalar-apply-")
        root = workspace
        val prefix = "int required[REQUIRED - 16];\n"
        val tail = "int main(void) { return calculate(5, 2) ? 0 : 1; }\n"
        original = prefix + "int calculate(int a, int b) {\n    double ratio = a / b;\n    return ratio == 2.0;\n}\n" + tail
        expectedSource = prefix + "int calculate(int a, int b) {\n    const int quotient = a / b;\n    double ratio = quotient;\n    return ratio == 2.0;\n}\n" + tail
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
        val start = original.indexOf("a / b")
        plan = CScalarExtractionPlanner(toolchain, requireNotNull(manager)).preview(snapshot, Path.of("main.c"), CFunctionByteSpan(start, start + 5), "quotient")
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        val runtimeIdentity = assertNotNull(toolchain.provenance.nativeRuntimeIdentity)
        assertEquals(runtimeIdentity, plan.authorityLease?.attributes?.get("c.nativeRuntimeIdentity"))
        gate = requireNotNull(facade).diagnosticsGate(snapshot, plan)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        privateBefore = privateDirectories()
    }

    @When("library apply evaluates that scalar extraction authority")
    fun apply() {
        if (condition == "database changed") {
            database += " \n"
            Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database)
        }
        if (condition == "preview lease removed") plan = plan.copy(authorityLease = null)
        if (condition == "temporary identity changed") {
            val old = assertNotNull(plan.authorityLease)
            plan = plan.copy(authorityLease = OperationAuthorityLease(old.kind, old.operation, old.snapshotHash, old.evidenceHash,
                workspaceEditSha256 = old.workspaceEditSha256, attributes = old.attributes + ("c.tempName" to "differentTemporary")))
        }
        if (condition == "native runtime identity changed") {
            val old = assertNotNull(plan.authorityLease)
            plan = plan.copy(authorityLease = OperationAuthorityLease(
                kind = old.kind,
                operation = old.operation,
                snapshotHash = old.snapshotHash,
                evidenceHash = old.evidenceHash,
                evidenceCompleteness = old.evidenceCompleteness,
                workspaceEditSha256 = old.workspaceEditSha256,
                requiredClasspathEvidence = old.requiredClasspathEvidence,
                requiredFileEvidence = old.requiredFileEvidence,
                attributes = old.attributes + ("c.nativeRuntimeIdentity" to "0".repeat(64)),
            ))
        }
        val callerGate = if (condition == "approved retained gate") gate else DiagnosticsGate.disabled("caller-disabled")
        val approval = if (condition == "approval missing") ApplyAuthorization.missing("c-scalar-story", "bdd-caller")
            else ApplyAuthorization.explicit("c-scalar-story", "bdd-caller")
        result = requireNotNull(facade).apply(snapshot, plan, approval, callerGate)
    }

    @Then("managed scalar extraction apply has outcome {string}")
    fun report(expected: String) {
        val workspace = requireNotNull(root)
        if (expected == "applied and rolled back") {
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
            assertEquals("c-scalar-story", applied.transaction.approval.surface)
            assertEquals("bdd-caller", applied.transaction.approval.actor)
            assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
            assertTrue(CProjectScanner().scan(workspace).hash != snapshot.hash)
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            val refused = assertIs<ApplyResult.Refused>(result, result.toString())
            assertTrue(refused.diagnostics.any { diagnostic -> when (expected) {
                "approval refused" -> diagnostic.code == "approval.required"
                "missing authority refused", "changed authority refused" -> diagnostic.message.contains("c.scalarExtract.authorityInvalid")
                "native runtime identity refused" -> diagnostic.message.contains("c.scalarExtract.authorityInvalid")
                "changed input refused" -> diagnostic.code in setOf("snapshot.scopeChanged", "snapshot.changed", "authorityLease.snapshotMismatch") ||
                    diagnostic.message.contains("c.scalarExtract.authorityInvalid")
                else -> error("Unexpected outcome: $expected")
            } }, refused.diagnostics.toString())
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
    }

    @Then("managed scalar extraction retains configuration and releases compiler material")
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

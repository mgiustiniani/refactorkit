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
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JournalState
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CManagedVariableRenameSteps {
    private val fixture = CManagedVariableRenameFixture()

    @Given("a captured {string} C {string} variable rename has {string} authority")
    fun capture(standard: String, binding: String, authority: String) = fixture.capture(standard, binding, authority)

    @When("the selected C variable rename is previewed and managed Apply is requested")
    fun apply() = fixture.execute()

    @Then("the managed C variable rename reports {string} with exact binding images")
    fun result(expected: String) = fixture.assertResult(expected)

    @Then("the C variable rename preserves captured inputs and expected transaction state")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class CManagedVariableRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var database: String
    private lateinit var authority: String
    private lateinit var plan: PatchPlan
    private lateinit var result: ApplyResult
    private var rolledBack = false
    private lateinit var binding: String
    private lateinit var original: String
    private lateinit var expected: String
    private var line = 0
    private var character = 0
    private fun program(global: String, parameter: String, local: String) =
        "static int $global = 4;\nstatic int calculate(int $parameter) {\n" +
        "  int result = $parameter;\n  { int $local = 3; result += $local; }\n" +
        "  return result + $parameter;\n}\nstatic int read(void) { return $global; }\n" +
        "int main(void) { int value = 1; const char *label = \"value\"; " +
        "return calculate(value) + read() + (label[0] == 'v' ? 0 : 1); }\n// value remains a comment\n"

    fun capture(standard: String, selectedBinding: String, variant: String) {
        require(standard in setOf("c11", "c17"))
        require(variant in setOf("matching", "missing approval", "database changed", "forged lease", "source changed"))
        authority = variant
        require(selectedBinding in setOf("local", "parameter", "internal object"))
        binding = selectedBinding
        original = program("value", "value", "value")
        expected = when (binding) {
            "local" -> program("value", "value", "renamed")
            "parameter" -> program("value", "renamed", "value")
            else -> program("renamed", "value", "value")
        }
        line = when (binding) { "local" -> 3; "parameter" -> 1; else -> 0 }
        character = when (binding) { "local" -> 8; "parameter" -> 25; else -> 11 }
        val workspace = Files.createTempDirectory("refactorkit-c-managed-variable-").also { root = it }
        Files.writeString(workspace.resolve("main.c"), original)
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        val processes = ExternalSemanticProcessManager().also { manager = it }
        facade = CRefactoringFacade(toolchain, processes)
    }

    fun execute() {
        val workspace = requireNotNull(root)
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf(
            "file" to "main.c", "line" to line.toString(), "character" to character.toString(), "symbol" to "value", "newName" to "renamed",
        ))
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        val requested = if (authority == "forged lease") {
            val lease = plan.authorityLease
            if (lease == null) plan else plan.copy(authorityLease = OperationAuthorityLease(
                kind = lease.kind, operation = lease.operation, snapshotHash = lease.snapshotHash,
                evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.renameVariable.selectedName" to "result"),
            ))
        } else plan
        if (authority == "database changed") Files.writeString(workspace.resolve("compile_commands.json"), database + "\n")
        if (authority == "source changed") Files.writeString(workspace.resolve("main.c"), original + "// drift\n")
        result = adapter.apply(snapshot, requested,
            if (authority == "missing approval") ApplyAuthorization.missing("c06-story") else ApplyAuthorization.explicit("c06-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-symbol-rename"))
    }

    fun assertResult(outcome: String) {
        val workspace = requireNotNull(root)
        assertEquals("renameSymbol", plan.operation)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertTrue(plan.requiresUserApproval)
        assertEquals("c.rename-symbol-variable-v1", assertNotNull(plan.authorityLease, "Managed variable binding authority is absent").kind)
        assertTrue(plan.diagnosticsBefore.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        if (outcome == "applied and rolled back") {
            assertEquals("matching", authority)
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals(expected, Files.readString(workspace.resolve("main.c")))
            assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(applied.transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            assertEquals("refused", outcome)
            assertIs<ApplyResult.Refused>(result)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertEquals(original + if (authority == "source changed") "// drift\n" else "", Files.readString(workspace.resolve("main.c")))
        assertEquals(database + if (authority == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        facade?.close(); manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

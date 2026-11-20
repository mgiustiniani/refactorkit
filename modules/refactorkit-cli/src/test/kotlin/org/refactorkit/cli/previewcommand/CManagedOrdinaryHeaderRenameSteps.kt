package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.*
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CManagedOrdinaryHeaderRenameSteps {
    private val fixture = CManagedOrdinaryHeaderRenameFixture()
    @Given("a captured {string} ordinary header {string} rename has {string} authority")
    fun capture(standard: String, family: String, condition: String) = fixture.capture(standard, family, condition)
    @When("the ordinary header rename is previewed and managed Apply is requested")
    fun execute() = fixture.execute()
    @Then("the ordinary header lifecycle reports {string} with exact declaration and shadowing images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the ordinary header sources and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CManagedOrdinaryHeaderRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var family: String
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var rolledBack = false

    private fun names(renamed: Boolean): Triple<String, String, String> = Triple(
        if (family == "function" && renamed) { if (condition == "tag only") "stage" else "compute" } else "calculate",
        if (family == "global" && renamed) "request_count" else "item_count",
        if (family == "enumerator" && renamed) "ORDER_OPEN" else "ORDER_PENDING",
    )
    private fun material(renamed: Boolean): Map<Path, String> {
        val (function, variable, constant) = names(renamed)
        return mapOf(
            Path.of("include/api.h") to """typedef int value_t;
enum stage { $constant = 1, ORDER_DONE = 2 };
struct meter { value_t item_count; };
extern value_t $variable;
value_t $function(value_t amount);
""",
            Path.of("src/api.c") to """#include "api.h"
value_t $variable = $constant;
value_t $function(value_t amount) { return amount + $variable; }
""",
            Path.of("src/main.c") to """#include "api.h"
static int shadow(void) {
    int item_count = 7;
    int calculate = 3;
    int ORDER_PENDING = 5;
    return item_count + calculate + ORDER_PENDING;
}
int main(void) {
    struct meter reading = { .item_count = 9 };
    const char *label = "calculate item_count ORDER_PENDING";
    /* calculate item_count ORDER_PENDING */
    return $function($variable) + $constant + shadow() + reading.item_count + label[0];
}
""",
        )
    }
    fun capture(standard: String, selectedFamily: String, variant: String) {
        require(standard in setOf("c11", "c17"))
        require(selectedFamily in setOf("function", "global", "enumerator"))
        require(variant in setOf("matching", "incomplete consumer", "database changed", "forged lease", "collision", "tag only"))
        require(variant != "tag only" || selectedFamily == "function")
        family = selectedFamily; condition = variant
        val workspace = Files.createTempDirectory("rk-c-ordinary-header-").also { root = it }
        original = material(false); expected = material(true)
        original.forEach { (p, text) -> Files.createDirectories(workspace.resolve(p).parent); Files.writeString(workspace.resolve(p), text) }
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val units = if (variant == "incomplete consumer") listOf("src/api.c") else listOf("src/api.c", "src/main.c")
        database = JsonArray(units.map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude", "-c", file).map(::JsonPrimitive)))
        } }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        facade = CRefactoringFacade(toolchain, ExternalSemanticProcessManager().also { manager = it })
    }
    fun execute() {
        val adapter = requireNotNull(facade)
        val (oldName, newName, position) = when (family) {
            "function" -> Triple("calculate", "compute", 4 to 8)
            "global" -> Triple("item_count", "request_count", 3 to 15)
            else -> Triple("ORDER_PENDING", "ORDER_OPEN", 1 to 13)
        }
        val destination = when (condition) {
            "collision" -> when (family) { "function" -> "main"; "global" -> "ORDER_DONE"; else -> "calculate" }
            "tag only" -> "stage"
            else -> newName
        }
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf("symbol" to oldName, "newName" to destination,
            "file" to "include/api.h", "line" to position.first.toString(), "character" to position.second.toString()))
        assertSource(original)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database + "\n")
        val requested = if (condition == "forged lease" && plan.authorityLease != null) {
            val lease = requireNotNull(plan.authorityLease)
            plan.copy(authorityLease = OperationAuthorityLease(kind = lease.kind, operation = lease.operation,
                snapshotHash = lease.snapshotHash, evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.ordinary.selectedName" to "not_selected")))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c06-ordinary-header-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-ordinary-header-rename"))
    }
    fun assertOutcome(outcome: String) {
        val workspace = requireNotNull(root)
        if (condition == "collision") {
            assertEquals("refused", outcome)
            assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
            assertEquals("c.renameTargetIdentifierPresent", plan.refusalCode)
            assertNull(plan.authorityLease)
            assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        if (condition != "incomplete consumer") {
            val lease = assertNotNull(plan.authorityLease, "Complete ordinary declaration family authority is absent")
            assertEquals("c.rename-symbol-ordinary-header-v1", lease.kind)
            assertEquals("2", lease.attributes["c.ordinary.translationUnitCount"])
            assertEquals(if (family == "global") "4" else "3", lease.attributes["c.ordinary.physicalSiteCount"])
            assertEquals(when (family) { "function" -> "FUNCTION"; "global" -> "GLOBAL_OBJECT"; else -> "ENUMERATOR" },
                lease.attributes["c.ordinary.familyKind"])
            assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
            assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        }
        if (outcome == "applied and rolled back") {
            assertTrue(condition in setOf("matching", "tag only"))
            val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
            assertSource(expected)
            assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction)); rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            assertEquals("refused", outcome); assertIs<ApplyResult.Refused>(applied)
            if (condition == "incomplete consumer") assertNull(plan.authorityLease)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }
    fun assertIntact() {
        val workspace = requireNotNull(root); assertSource(original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(workspace.resolve(".refactorkit/transactions")))
    }
    private fun assertSource(image: Map<Path, String>) = image.forEach { (p, text) ->
        assertEquals(text, Files.readString(requireNotNull(root).resolve(p)), p.toString())
    }
    override fun close() {
        facade?.close(); manager?.close()
        root?.let { path -> Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

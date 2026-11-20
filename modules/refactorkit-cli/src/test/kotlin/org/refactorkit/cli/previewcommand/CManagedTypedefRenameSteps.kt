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

class CManagedTypedefRenameSteps {
    private val fixture = CManagedTypedefRenameFixture()
    @Given("a captured {string} shared header typedef rename has {string} authority")
    fun capture(standard: String, condition: String) = fixture.capture(standard, condition)
    @When("the shared header typedef rename is previewed and managed Apply is requested")
    fun execute() = fixture.execute()
    @Then("the shared header typedef lifecycle reports {string} with exact type and preserved namespace images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the shared header typedef inputs and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CManagedTypedefRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var rolledBack = false

    private fun material(renamed: Boolean): Map<Path, String> {
        val alias = if (renamed) "quantity_t" else "value_t"
        val extraTag = if (condition == "tag destination") "struct quantity_t { int payload; };\n" else ""
        val aliasDeclaration = when (condition) {
            "macro declaration" -> "#define DECLARE_ALIAS(name) typedef int name\nDECLARE_ALIAS($alias);"
            "GNU source" -> "typedef __typeof__(int) $alias;"
            else -> "typedef int $alias;"
        }
        val initializer = if (condition == "opaque designator") ".value_t = 2" else "2"
        val shadow = if (condition == "shadow alias")
            "static int hidden(void) { typedef char value_t; value_t letter = 1; return letter; }\n" else ""
        return mapOf(Path.of("include/api.h") to """$aliasDeclaration
typedef $alias alias_t;
struct value_t { $alias value_t; };
${extraTag}$alias transform($alias input);
""", Path.of("src/api.c") to """#include "api.h"
$alias transform($alias input) {
    $alias result = ($alias)input;
    return result;
}
""", Path.of("src/main.c") to """#include "api.h"
${shadow}int main(void) {
    struct value_t reading = { $initializer };
    $alias count = ($alias)transform(3);
    int size = sizeof($alias) + _Alignof($alias);
    alias_t copy = count;
value_t: ;
    int value_t = 7;
    if (value_t < 0) goto value_t;
    /* typedef value_t, label value_t, member value_t and struct value_t */
    const char *label = "value_t";
    return reading.value_t + value_t + copy + size + label[0];
}
""")
    }

    fun capture(standard: String, variant: String) {
        require(standard in setOf("c11", "c17"))
        require(variant in setOf("matching", "tag destination", "ordinary collision", "database changed", "forged lease",
            "incomplete consumer", "shadow alias", "macro declaration", "source changed", "GNU source", "opaque designator"))
        condition = variant
        val workspace = Files.createTempDirectory("rk-c-typedef-rename-").also { root = it }
        original = material(false); expected = material(true)
        original.forEach { (p, text) -> Files.createDirectories(workspace.resolve(p).parent); Files.writeString(workspace.resolve(p), text) }
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf("src/api.c", "src/main.c").map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-Iinclude", "-c", file).map(::JsonPrimitive)))
        } }.let { rows -> if (variant == "incomplete consumer") rows.take(1) else rows }).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        facade = CRefactoringFacade(toolchain, ExternalSemanticProcessManager().also { manager = it })
    }

    fun execute() {
        val selected = CTokenizer().tokenize(original.getValue(Path.of("include/api.h")))
            .first { it.type == CTokenType.IDENTIFIER && it.text == "value_t" }
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf("symbol" to "value_t",
            "newName" to if (condition == "ordinary collision") "alias_t" else "quantity_t",
            "file" to "include/api.h", "line" to (selected.line - 1).toString(), "character" to selected.column.toString()))
        assertSource(original)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        if (plan.status == PatchStatus.PREVIEW && condition in setOf("matching", "tag destination", "database changed", "forged lease", "source changed")) {
            val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
            assertEquals(expected, staged.files.associate { it.path to it.content }, "Preview must change only compiler-bound typedef sites")
        }
        if (condition == "database changed") Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database + "\n")
        if (condition == "source changed") Files.writeString(requireNotNull(root).resolve("src/main.c"),
            original.getValue(Path.of("src/main.c")) + "// caller edit\n")
        val requested = if (condition == "forged lease") {
            val lease = assertNotNull(plan.authorityLease, "A clean typedef lease is required before forgery")
            plan.copy(authorityLease = OperationAuthorityLease(kind = lease.kind, operation = lease.operation,
                snapshotHash = lease.snapshotHash, evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.typedef.underlyingCanonical" to "double")))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c06-managed-typedef-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-typedef-rename"))
    }

    fun assertOutcome(outcome: String) {
        val workspace = requireNotNull(root)
        if (condition in setOf("incomplete consumer", "shadow alias", "macro declaration", "GNU source", "opaque designator")) {
            assertEquals("refused", outcome)
            assertTrue(plan.status in setOf(PatchStatus.PREVIEW, PatchStatus.REFUSED), plan.toString())
            assertNull(plan.authorityLease); assertIs<ApplyResult.Refused>(applied)
            if (plan.status == PatchStatus.PREVIEW) {
                val code = when (condition) {
                    "incomplete consumer" -> "c.typedefFacts.incompleteTUs"
                    "shadow alias" -> "c.typedefFacts.ambiguous_type_alias"
                    "opaque designator" -> "c.typedefFacts.unbound_source_token"
                    else -> "c.typedefFacts.sourceUnsupported"
                }
                assertTrue(plan.diagnosticsAfterPreview.any { it.code == code }, plan.toString())
            } else assertTrue(!plan.refusalCode.isNullOrBlank(), plan.toString())
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        if (condition == "ordinary collision") {
            assertEquals("refused", outcome); assertEquals(PatchStatus.REFUSED, plan.status)
            assertEquals("c.renameTypedefTargetPresent", plan.refusalCode)
            assertNull(plan.authorityLease); assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        val lease = assertNotNull(plan.authorityLease, "Complete compiler typedef/type authority is absent")
        assertEquals("c.rename-symbol-typedef-v1", lease.kind)
        assertEquals("2", lease.attributes["c.typedef.translationUnitCount"])
        assertEquals("13", lease.attributes["c.typedef.physicalSiteCount"])
        assertEquals(if (condition == "tag destination") "10" else "9", lease.attributes["c.typedef.preservedSiteCount"])
        assertEquals("int", lease.attributes["c.typedef.underlyingCanonical"])
        assertEquals("iso-pedantic-errors-v1", lease.attributes["c.typedef.validationPolicy"])
        assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        if (outcome == "applied and rolled back") {
            assertTrue(condition in setOf("matching", "tag destination"))
            val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
            assertSource(expected)
            assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction)); rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            assertEquals("refused", outcome)
            assertTrue(condition in setOf("database changed", "forged lease", "source changed"))
            assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    fun assertIntact() {
        assertSource(if (condition == "source changed") original.mapValues { (path, text) ->
            text + if (path == Path.of("src/main.c")) "// caller edit\n" else ""
        } else original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }
    private fun assertSource(image: Map<Path, String>) = image.forEach { (p, text) -> assertEquals(text, Files.readString(requireNotNull(root).resolve(p)), p.toString()) }
    override fun close() {
        facade?.close(); manager?.close()
        root?.let { p -> Files.walk(p).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

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

class CManagedTagRenameSteps {
    private val fixture = CManagedTagRenameFixture()
    @Given("a captured {string} shared header {string} tag rename has {string} authority")
    fun capture(standard: String, tag: String, condition: String) = fixture.capture(standard, tag, condition)
    @When("the shared header tag rename is previewed and managed Apply is requested")
    fun execute() = fixture.execute()
    @Then("the shared header tag lifecycle reports {string} with exact type and ordinary namespace images")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the shared header tag inputs and transaction history retain their expected state")
    fun intact() = fixture.assertIntact()
    @Given("a captured {string} source {string} tag rename has {string} authority")
    fun captureSource(standard: String, tag: String, condition: String) =
        fixture.capture(standard, tag, condition, sourceSelection = true)
    @When("the source tag rename is previewed and managed Apply is requested")
    fun executeSource() = fixture.execute()
    @Then("the source tag lifecycle reports {string} with exact type and ordinary namespace images")
    fun sourceOutcome(expected: String) = fixture.assertOutcome(expected)
    @Then("the source tag inputs and transaction history retain their expected state")
    fun sourceIntact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CManagedTagRenameFixture : AutoCloseable {
    private var root: Path? = null
    private var foreignRoot: Path? = null
    private var foreignHeader: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var tag: String
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var original: Map<Path, String>
    private lateinit var expected: Map<Path, String>
    private lateinit var plan: PatchPlan
    private lateinit var applied: ApplyResult
    private var rolledBack = false
    private var sourceSelection = false
    private val exposures = setOf("external return", "external parameter", "external object", "source alias",
        "containing record", "pointer erasure")

    private fun names(renamed: Boolean) = Triple(
        if (renamed && tag == "struct") { if (condition == "ordinary namespace") "transform" else "sensor" } else "meter",
        if (renamed && tag == "union") "message" else "payload",
        if (renamed && tag == "enum") "phase" else "stage",
    )
    private fun material(renamed: Boolean): Map<Path, String> {
        if (sourceSelection) return sourceMaterial(renamed)
        val (record, union, enum) = names(renamed)
        val recordDeclaration = when (condition) {
            "macro declaration" -> "#define DECLARE_TAG(name) struct name { value_t raw_count; }\nDECLARE_TAG($record);"
            "GNU source" -> "struct $record { __typeof__(int) raw_count; };"
            else -> "struct $record { value_t raw_count; };"
        }
        val shadow = if (condition == "shadow tag")
            "static int hidden(void) { struct meter { int raw_count; }; struct meter local = { 1 }; return local.raw_count; }\n" else ""
        val prelude = when (condition) {
            "comment include" -> "/* prefix */ #include \"${requireNotNull(foreignHeader)}\"\n"
            "digraph include" -> "%:include \"${requireNotNull(foreignHeader)}\"\n"
            else -> ""
        }
        return mapOf(Path.of("include/api.h") to """${prelude}typedef int value_t;
$recordDeclaration
union $union { value_t amount; };
enum $enum { ORDER_PENDING = 1 };
value_t transform(value_t input);
""", Path.of("src/api.c") to """#include "api.h"
value_t transform(value_t input) { return input; }
""", Path.of("src/main.c") to """#include "api.h"
${shadow}int main(void) {
    struct $record reading = { .raw_count = 2 };
    union $union item = { .amount = 3 };
    enum $enum status = ORDER_PENDING;
    int meter = 7;
    int payload = 8;
    int stage = 9;
    /* struct meter, union payload and enum stage */
    const char *label = "struct meter union payload enum stage";
    return transform(meter + payload + stage) + ((struct $record *)&reading)->raw_count
        + ((union $union *)&item)->amount + (int)((enum $enum)status)
        + sizeof(struct $record) + sizeof(union $union) + sizeof(enum $enum) + label[0];
}
""")
    }
    private fun sourceMaterial(renamed: Boolean): Map<Path, String> {
        if (condition in exposures) return exposureMaterial(renamed)
        val (record, union, enum) = names(renamed)
        return mapOf(Path.of("include/api.h") to "#pragma once\nint calculate(int input);\n",
            Path.of("src/api.c") to """#include "api.h"
struct $record { int raw_count; };
union $union { int amount; };
enum $enum { PENDING = 1 };
int calculate(int input) {
    struct $record reading = { .raw_count = input };
    union $union item = { .amount = input };
    enum $enum status = PENDING;
    int meter = 7, payload = 8, stage = 9;
    const char *label = "struct meter union payload enum stage";
    /* struct meter union payload enum stage */
    return ((struct $record *)&reading)->raw_count + ((union $union *)&item)->amount
        + (int)((enum $enum)status) + sizeof(struct $record) + sizeof(union $union)
        + sizeof(enum $enum) + meter + payload + stage + label[0];
}
""", Path.of("src/main.c") to """#include "api.h"
struct meter { double other; };
union payload { double other; };
enum stage { OTHER = 2 };
int main(void) {
    struct meter reading = { .other = 1.0 };
    union payload item = { .other = reading.other };
    enum stage status = OTHER;
    return calculate((int)item.other) + status;
}
""")
    }
    private fun exposureMaterial(renamed: Boolean): Map<Path, String> {
        val (record, union, enum) = names(renamed)
        val producer = "#include \"api.h\"\nstruct $record { int value; };\nunion $union { int value; };\nenum $enum { PENDING = 1 };\n"
        val consumer = "#include \"api.h\"\nstruct meter { int value; };\nunion payload { int value; };\nenum stage { PENDING = 1 };\n"
        val (definition, use) = when (condition) {
            "external return" -> "struct $record make(void) { struct $record result = { 1 }; return result; }\n" to
                "struct meter make(void);\nint main(void) { struct meter result = make(); return result.value; }\n"
            "external parameter" -> "int consume(union $union input) { return input.value; }\n" to
                "int consume(union payload input);\nint main(void) { union payload input = { 1 }; return consume(input); }\n"
            "external object" -> "enum $enum current = PENDING;\n" to
                "extern enum stage current;\nint main(void) { return (int)current; }\n"
            "source alias" -> "typedef struct $record value_t;\nvalue_t make(void) { value_t result = { 1 }; return result; }\n" to
                "typedef struct meter value_t;\nvalue_t make(void);\nint main(void) { value_t result = make(); return result.value; }\n"
            "containing record" -> "struct box { union $union item; };\nstruct box make(void) { struct box result = { { 1 } }; return result; }\n" to
                "struct box { union payload item; };\nstruct box make(void);\nint main(void) { struct box result = make(); return result.item.value; }\n"
            "pointer erasure" -> "static struct $record current = { 1 };\nvoid *expose(void) { return &current; }\n" to
                "void *expose(void);\nint main(void) { struct meter *result = expose(); return result->value; }\n"
            else -> error("Unknown exposure fixture")
        }
        return mapOf(Path.of("include/api.h") to "#pragma once\n", Path.of("src/api.c") to producer + definition,
            Path.of("src/main.c") to consumer + use)
    }
    fun capture(standard: String, selected: String, variant: String, sourceSelection: Boolean = false) {
        this.sourceSelection = sourceSelection
        require(standard in setOf("c11", "c17") && selected in setOf("struct", "union", "enum"))
        require(variant in setOf("matching", "ordinary namespace", "tag collision", "database changed", "incomplete consumer",
            "shadow tag", "macro declaration", "source changed", "forged lease", "GNU source", "comment include", "digraph include") + exposures)
        if (variant in exposures) {
            require(sourceSelection)
            require(selected == when (variant) { "external parameter", "containing record" -> "union"; "external object" -> "enum"; else -> "struct" })
        }
        require(variant != "ordinary namespace" || selected == "struct")
        require(variant != "tag collision" || selected == "enum")
        tag = selected; condition = variant
        val workspace = Files.createTempDirectory("rk-c-tag-rename-").also { root = it }
        if (variant in setOf("comment include", "digraph include")) {
            val outside = Files.createTempDirectory("rk-c-tag-foreign-").also { foreignRoot = it }
            foreignHeader = outside.resolve("foreign.h").also { Files.writeString(it, "#pragma once\n") }
        }
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
        val (oldName, newName) = when (tag) {
            "struct" -> "meter" to if (condition == "ordinary namespace") "transform" else "sensor"
            "union" -> "payload" to "message"
            else -> "stage" to if (condition == "tag collision") "payload" else "phase"
        }
        val selectedFile = if (sourceSelection) "src/api.c" else "include/api.h"
        val selected = CTokenizer().tokenize(original.getValue(Path.of(selectedFile)))
            .first { it.type == CTokenType.IDENTIFIER && it.text == oldName }
        val adapter = requireNotNull(facade)
        plan = adapter.previewForManagedApply(snapshot, "renameSymbol", mapOf("symbol" to oldName, "newName" to newName,
            "file" to selectedFile, "line" to (selected.line - 1).toString(), "character" to selected.column.toString()))
        assertSource(original)
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        if (condition == "database changed") Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database + "\n")
        if (condition == "source changed") Files.writeString(requireNotNull(root).resolve("src/main.c"),
            original.getValue(Path.of("src/main.c")) + "// caller edit\n")
        val requested = if (condition == "forged lease") {
            val lease = assertNotNull(plan.authorityLease, "Clean original tag preview must have authority before forgery")
            plan.copy(authorityLease = OperationAuthorityLease(kind = lease.kind, operation = lease.operation,
                snapshotHash = lease.snapshotHash, evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredFileEvidence = lease.requiredFileEvidence,
                requiredClasspathEvidence = lease.requiredClasspathEvidence,
                attributes = lease.attributes + ("c.tag.kind" to "UNION")))
        } else plan
        applied = adapter.apply(snapshot, requested, ApplyAuthorization.explicit("c06-managed-tag-story"),
            DiagnosticsGate.disabled("caller-cannot-authorize-tag-rename"))
    }
    fun assertOutcome(outcome: String) {
        val workspace = requireNotNull(root)
        if (condition in exposures) {
            assertEquals("exposure refused", outcome)
            assertEquals(PatchStatus.REFUSED, plan.status, plan.toString())
            assertEquals("c.tagFacts.source_type_exposure_unqualified", plan.refusalCode, plan.toString())
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
            assertTrue(plan.diagnosticsAfterPreview.any { it.code == plan.refusalCode && it.evidence == DiagnosticEvidence.COMPILER &&
                it.severity == Diagnostic.Severity.ERROR }, plan.toString())
            assertEquals(WorkspaceEdit(), plan.workspaceEdit)
            assertTrue(plan.affectedFiles.isEmpty())
            assertNull(plan.authorityLease)
            assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        if (condition in setOf("incomplete consumer", "shadow tag", "macro declaration", "GNU source", "comment include", "digraph include")) {
            assertEquals("refused", outcome)
            assertTrue(plan.status in setOf(PatchStatus.PREVIEW, PatchStatus.REFUSED), plan.toString())
            assertNull(plan.authorityLease)
            assertIs<ApplyResult.Refused>(applied)
            if (condition in setOf("comment include", "digraph include")) assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
            if (plan.status == PatchStatus.PREVIEW) {
                val code = when (condition) {
                    "incomplete consumer" -> "c.tagFacts.incompleteTUs"
                    "shadow tag" -> "c.tagFacts.ambiguous_tag_family"
                    else -> "c.tagFacts.sourceUnsupported"
                }
                assertTrue(plan.diagnosticsAfterPreview.any { it.code == code }, plan.toString())
            } else assertTrue(!plan.refusalCode.isNullOrBlank(), plan.toString())
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        if (condition == "tag collision") {
            assertEquals("refused", outcome); assertEquals(PatchStatus.REFUSED, plan.status)
            assertEquals("c.renameTagTargetPresent", plan.refusalCode)
            assertNull(plan.authorityLease); assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            return
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        val lease = assertNotNull(plan.authorityLease, "Complete compiler tag/type authority is absent")
        assertEquals("c.rename-symbol-tag-v1", lease.kind)
        assertEquals(tag.uppercase(), lease.attributes["c.tag.kind"])
        assertEquals("iso-pedantic-errors-v1", lease.attributes["c.tag.validationPolicy"])
        assertEquals("2", lease.attributes["c.tag.translationUnitCount"])
        assertEquals("4", lease.attributes["c.tag.physicalSiteCount"])
        if (sourceSelection) {
            assertEquals(setOf(Path.of("src/api.c")), plan.affectedFiles)
            assertEquals("C source tag rename from compiler-owned TU declaration and type bindings", plan.summary)
            assertTrue(plan.warnings.any { it.contains("captured Clang source-tag bindings") })
        }
        assertTrue(plan.diagnosticsBefore.none { it.severity == Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == Diagnostic.Severity.ERROR })
        if (outcome == "applied and rolled back") {
            assertTrue(condition in setOf("matching", "ordinary namespace"))
            val transaction = assertIs<ApplyResult.Applied>(applied, applied.toString()).transaction
            assertSource(expected)
            assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(transaction)); rolledBack = true
            assertEquals(JournalState.ROLLED_BACK, assertNotNull(TransactionLog(workspace.resolve(".refactorkit/transactions"))
                .loadRecord(transaction.id)).state)
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            assertTrue(condition in setOf("database changed", "source changed", "forged lease")); assertEquals("refused", outcome)
            assertIs<ApplyResult.Refused>(applied)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }
    fun assertIntact() {
        val workspace = requireNotNull(root)
        assertSource(if (condition == "source changed") original.mapValues { (path, text) ->
            text + if (path == Path.of("src/main.c")) "// caller edit\n" else ""
        } else original)
        assertEquals(database + if (condition == "database changed") "\n" else "", Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(rolledBack, Files.exists(workspace.resolve(".refactorkit/transactions")))
        foreignHeader?.let { assertEquals("#pragma once\n", Files.readString(it), "Uncaptured first-party header bytes changed") }
    }
    private fun assertSource(image: Map<Path, String>) = image.forEach { (p, text) -> assertEquals(text, Files.readString(requireNotNull(root).resolve(p)), p.toString()) }
    override fun close() {
        facade?.close(); manager?.close()
        root?.let { p -> Files.walk(p).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
        foreignRoot?.let { p -> Files.walk(p).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.JournalState
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.Transaction
import org.refactorkit.core.TransactionLog
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CPrefixLibraryAuthoritySteps {
    private val fixture = CPrefixLibraryAuthorityFixture()

    @Given("the C library prefix preview has {string} inputs under {string}")
    fun capture(condition: String, standard: String) = fixture.capture(condition, standard)

    @When("the direct library requests both mapped C API families")
    fun preview() = fixture.preview()

    @Then("the direct library prefix result is {string} with retained compiler authority")
    fun outcome(expected: String) = fixture.assertOutcome(expected)

    @Then("the direct library leaves inputs intact or restores its exact approved transaction")
    fun restored() = fixture.restoreAndAssert()

    @Given("a retained C API preview acquires {string} authority before a {string} consumer appears")
    fun captureLate(authority: String, consumer: String) = fixture.captureLate(authority, consumer)

    @When("that retained authority attempts the C API transaction")
    fun applyLate() = fixture.applyLate()

    @Then("the retained C API authority reports {string} with exact transaction state")
    fun lateOutcome(expected: String) = fixture.assertLateOutcome(expected)

    @Then("the original C sources and the concurrent foreign consumer are preserved")
    fun lateIntact() = fixture.assertLateIntact()

    @After
    fun close() = fixture.close()
}

/** Native library acquisition and scenario-owned independent source images. */
private class CPrefixLibraryAuthorityFixture : AutoCloseable {
    private var root: Path? = null
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var originals: Map<String, String>
    private lateinit var expected: Map<String, String>
    private lateinit var mapping: Map<String, String>
    private lateinit var plan: PatchPlan
    private lateinit var privateBefore: Set<Path>
    private var transaction: Transaction? = null
    private lateinit var lateAuthority: String
    private lateinit var consumerPath: Path
    private lateinit var consumerSource: String
    private lateinit var retainedGate: DiagnosticsGate
    private var lateResult: ApplyResult? = null
    private var committedObserved = false

    fun capture(condition: String, standard: String) {
        require(standard in setOf("c11", "c17"))
        require(condition in setOf("complete", "cpp consumer", "objective-c consumer", "duplicate destination", "absent database"))
        val workspace = Files.createTempDirectory("refactorkit-c-prefix-library-").also { root = it }
        val tools = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val database = JsonArray(listOf("api.c", "main.c").map { file -> buildJsonObject {
            put("directory", workspace.toString()); put("file", file)
            put("arguments", JsonArray(listOf(tools.resolve("usr/bin/clang-22").toString(),
                "-std=$standard", "-I", ".", "-c", file).map(::JsonPrimitive)))
        } }).toString()
        val extra = when (condition) {
            "cpp consumer" -> mapOf("consumer.cpp" to "extern \"C\" {\n#include \"api.h\"\n}\n" +
                "int consume(void) { return calculate(1) + load_item(2); }\n")
            "objective-c consumer" -> mapOf("consumer.m" to "#include \"api.h\"\n" +
                "int consume(void) { return calculate(1) + load_item(2); }\n")
            else -> emptyMap()
        }
        originals = mapOf(
            "api.h" to "#ifndef API_H\n#define API_H\nint calculate(int value);\nint load_item(int value);\n#endif\n",
            "api.c" to "#include \"api.h\"\nint calculate(int value) { return value * 2; }\n" +
                "int load_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
                "/* calculate load_item remain comments */\n",
            "main.c" to "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
                "int main(void) { return calculate(21) + load_item(1) == 44 ? 0 : 1; }\n",
        ) + extra + if (condition == "absent database") emptyMap() else mapOf("compile_commands.json" to database)
        expected = mapOf(
            "api.h" to "#ifndef API_H\n#define API_H\nint compute(int value);\nint read_item(int value);\n#endif\n",
            "api.c" to "#include \"api.h\"\nint compute(int value) { return value * 2; }\n" +
                "int read_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
                "/* calculate load_item remain comments */\n",
            "main.c" to "#include \"api.h\"\nconst char *description = \"calculate load_item\";\n" +
                "int main(void) { return compute(21) + read_item(1) == 44 ? 0 : 1; }\n",
            "compile_commands.json" to database,
        )
        originals.forEach { (name, content) -> Files.writeString(workspace.resolve(name), content) }
        snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, tools.resolve("usr/bin/clang-22"), tools.resolve("usr/bin/clangd"),
                tools.resolve("usr/bin/clang-format")),
        )).toolchain
        mapping = mapOf("rename.calculate" to "compute", "rename.load_item" to
            if (condition == "duplicate destination") "compute" else "read_item")
        privateBefore = privateDirectories()
    }

    fun preview() {
        CRefactoringFacade(toolchain).use { facade ->
            facade.startFor(snapshot, "renamePrefix")
            plan = facade.preview(snapshot, "renamePrefix", mapping)
        }
        originals.forEach { (name, content) -> assertEquals(content, Files.readString(requireNotNull(root).resolve(name))) }
        assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
    }

    fun captureLate(authority: String, consumer: String) {
        require(authority in setOf("facade", "retained gate", "post-commit"))
        require(consumer in setOf("cpp", "objective-c"))
        capture("complete", if (consumer == "cpp") "c11" else "c17")
        preview()
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertNotNull(plan.authorityLease)
        lateAuthority = authority
        consumerPath = requireNotNull(root).resolve(if (consumer == "cpp") "consumer.cpp" else "consumer.m")
        consumerSource = (if (consumer == "cpp") "extern \"C\" {\n#include \"api.h\"\n}\n" else "#include \"api.h\"\n") +
            "int consume(void) { return calculate(1) + load_item(2); }\n"
        assertFalse(Files.exists(consumerPath))
        if (authority != "facade") retainedGate = CRefactoringFacade(toolchain).use {
            it.diagnosticsGate(snapshot, plan)
        }
    }

    fun applyLate() {
        val workspace = requireNotNull(root)
        val authorization = ApplyAuthorization.explicit("c-prefix-late-story", "bdd-caller")
        if (lateAuthority == "facade") {
            Files.writeString(consumerPath, consumerSource)
            lateResult = CRefactoringFacade(toolchain).use {
                it.apply(snapshot, plan, authorization, DiagnosticsGate.disabled("caller-disabled"))
            }
        } else {
            if (lateAuthority == "retained gate") Files.writeString(consumerPath, consumerSource)
            val injection = PatchFaultInjector { point, path, sequence ->
                if (lateAuthority == "post-commit" && point == PatchFaultPoint.AFTER_COMMITTED_IMAGE && !committedObserved) {
                    assertEquals(1, sequence)
                    assertEquals(expected.getValue(path.toString()), Files.readString(workspace.resolve(path)))
                    committedObserved = true
                    Files.writeString(consumerPath, consumerSource)
                }
            }
            lateResult = PatchEngine(workspace, faultInjector = injection).apply(plan, snapshot, authorization, retainedGate)
        }
    }

    fun assertLateOutcome(outcome: String) {
        val refused = assertIs<ApplyResult.Refused>(lateResult, "Late consumer escaped $lateAuthority: $lateResult")
        assertTrue(refused.diagnostics.any { it.message.contains("c.renamePrefix.unsupportedConsumer") },
            refused.diagnostics.toString())
        if (outcome == "refused before WAL") {
            assertFalse(committedObserved)
            assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        } else {
            assertEquals("automatic rollback and refusal", outcome)
            assertTrue(committedObserved, "The existing fault hook must observe an actual committed source image")
            assertTrue(refused.diagnostics.any { it.code == "diagnostics.postApplyUnavailable" })
            assertTrue(refused.diagnostics.any { it.code == "diagnostics.rollbackUnavailable" })
            val record = TransactionLog(requireNotNull(root).resolve(".refactorkit/transactions")).listRecords().single()
            assertEquals(JournalState.ROLLED_BACK, record.state)
            assertEquals(plan.operation, record.operation)
            assertEquals("c-prefix-late-story", record.transaction.approval.surface)
            assertTrue(record.history.any { it.state == JournalState.APPLIED })
            assertTrue(record.history.any { it.state == JournalState.ROLLING_BACK })
            for (name in listOf("api.h", "api.c", "main.c")) {
                assertEquals(originals.getValue(name), record.preImages.single { it.path == Path.of(name) }.content)
                assertEquals(expected.getValue(name), record.postImages.single { it.path == Path.of(name) }.content)
            }
        }
    }

    fun assertLateIntact() {
        originals.forEach { (name, content) -> assertEquals(content, Files.readString(requireNotNull(root).resolve(name)), name) }
        assertEquals(consumerSource, Files.readString(consumerPath), "Concurrent foreign source must remain untouched")
        assertTrue(CProjectScanner().scan(requireNotNull(root)).hash != snapshot.hash,
            "Preserving the concurrent input must not pretend to restore the earlier whole-project hash")
        assertEquals(privateBefore, privateDirectories())
    }

    fun assertOutcome(outcome: String) {
        if (outcome != "managed preview") {
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertEquals(outcome, plan.refusalCode, plan.summary)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            assertNull(plan.authorityLease)
            return
        }
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertTrue(plan.requiresUserApproval)
        val lease = assertNotNull(plan.authorityLease, "Public library preview must retain its compiler authority")
        assertEquals("c.rename-prefix-v1", lease.kind)
        assertEquals(snapshot.hash, lease.snapshotHash)
        assertTrue(plan.diagnosticsBefore.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        expected.forEach { (name, content) ->
            assertEquals(content, staged.trackedFiles.single { it.path == Path.of(name) }.content, name)
        }
        assertTrue(plan.warnings.any { it.contains("external ABI consumers") })
        val applied = CRefactoringFacade(toolchain).use { facade ->
            facade.apply(snapshot, plan, ApplyAuthorization.explicit("c-library-story", "bdd-caller"), DiagnosticsGate.disabled("caller-disabled"))
        }
        transaction = assertIs<ApplyResult.Applied>(applied).transaction
        expected.forEach { (name, content) -> assertEquals(content, Files.readString(requireNotNull(root).resolve(name)), name) }
        assertEquals("c-library-story", requireNotNull(transaction).approval.surface)
        assertJournal("APPLIED")
    }

    fun restoreAndAssert() {
        transaction?.let { assertIs<ApplyResult.Applied>(PatchEngine(requireNotNull(root)).rollback(it)) }
        originals.forEach { (name, content) -> assertEquals(content, Files.readString(requireNotNull(root).resolve(name)), name) }
        assertEquals(snapshot.hash, CProjectScanner().scan(requireNotNull(root)).hash)
        if (transaction == null) assertFalse(Files.exists(requireNotNull(root).resolve(".refactorkit/transactions")))
        else assertJournal("ROLLED_BACK")
        assertEquals(privateBefore, privateDirectories())
    }

    private fun assertJournal(state: String) {
        val id = requireNotNull(transaction).id.value
        val directory = requireNotNull(root).resolve(".refactorkit/transactions")
        val records = Files.list(directory).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }.toList() }
        assertEquals(listOf(directory.resolve("$id.json")), records)
        val record = Json.parseToJsonElement(Files.readString(records.single())).jsonObject
        assertEquals(state, record.getValue("state").jsonPrimitive.content)
        assertEquals(plan.id.value, record.getValue("transaction").jsonObject.getValue("planId").jsonPrimitive.content)
    }

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    override fun close() {
        root?.let { workspace -> Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

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
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CInlineFunctionEffectsSteps {
    private val managed = ManagedCFunctionInlineFixture()

    @Given("a captured {string} C inline-function fixture has {string} semantics")
    fun capturedFunction(standard: String, shape: String) = managed.capture(standard, shape)

    @When("the compiler-bound function is inlined through the managed lifecycle")
    fun managedInline() = managed.execute()

    @Then("the inlined program matches its independently expected semantic image")
    fun managedImage() = managed.assertImage()

    @Then("exact rollback restores the captured C inline-function inputs")
    fun managedRollback() = managed.rollback()

    private val legacy = CInlineFunctionCounterexampleFixture()

    @Given("an isolated C inline-function source with {string}")
    fun source(case: String) = legacy.source(case)

    @When("the C inline-function preview is requested for its helper")
    fun preview() = legacy.preview()

    @Then("the {word} inline-function outcome is {string} and no unrelated token is rewritten")
    fun outcome(case: String, disposition: String) = legacy.outcome(case, disposition)

    @Then("the inline-function source bytes and transaction journal remain unchanged")
    fun unchanged() = legacy.unchanged()

    @After
    fun cleanup() {
        managed.close()
        legacy.close()
    }
}

private class CInlineFunctionCounterexampleFixture : AutoCloseable {
    private var sandbox: Path? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var helper: String
    private lateinit var plan: PatchPlan
    private var refusedApply: ApplyResult? = null

    fun source(case: String) {
        val (name, code) = when (case) {
            "precedence" -> "add" to "static int add(int a, int b) { return a + b; }\nint main(void) { return 2 * add(3, 4); }\n"
            "capture" -> "readg" to "int g = 1;\nstatic int readg(void) { return g; }\nint main(void) { int g = 2; return readg(); }\n"
            "conversion" -> "half" to "static double half(double x) { return x / 2; }\nint main(void) { return half(3) * 2; }\n"
            "string" -> "code" to "static int code(int n) { return \"n\"[0] + n; }\nint main(void) { return code(1); }\n"
            "increment" -> "next" to "static int next(int x) { return x++; }\nint main(void) { return next(2); }\n"
            "argument-increment" -> "twice" to "static int twice(int x) { return x + x; }\nint main(void) { int n = 1; return twice(n++); }\n"
            "lifetime" -> "expired" to "static int *expired(void) { int n = 1; return &n; }\nint main(void) { return *expired(); }\n"
            "recursion" -> "again" to "static int again(int x) { return again(x); }\nint main(void) { return again(1); }\n"
            "macro" -> "identity" to "#define VALUE(x) (x)\nstatic int identity(int x) { return VALUE(x); }\nint main(void) { return identity(1); }\n"
            "control-flow" -> "choose" to "static int choose(int x) { if (x) return 1; return 0; }\nint main(void) { return choose(1); }\n"
            "aliasing" -> "load" to "static int load(int *p) { return *p; }\nint main(void) { int n = 1; return load(&n); }\n"
            else -> error("Unknown case $case")
        }
        helper = name
        sandbox = Files.createTempDirectory("refactorkit-c-inline-function-")
        workspace = Files.createDirectory(sandbox!!.resolve("workspace"))
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString())
        snapshot = CProjectScanner().scan(workspace)
    }

    fun preview() {
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        ExternalSemanticProcessManager().use { manager ->
            CRefactoringFacade(toolchain, manager).use { facade ->
                plan = facade.preview(snapshot, "inlineFunction", mapOf("file" to "main.c", "symbol" to helper))
                if (plan.status == PatchStatus.REFUSED) {
                    refusedApply = facade.apply(snapshot, plan, ApplyAuthorization.explicit("c12-inline-refusal-story"),
                        DiagnosticsGate.disabled("caller-cannot-override-inline-refusal"))
                }
            }
        }
    }

    fun outcome(case: String, disposition: String) {
        when (disposition) {
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                assertTrue(plan.affectedFiles.isEmpty())
                assertTrue(assertNotNull(plan.refusalCode).startsWith("c.inlineFunction."), plan.toString())
                assertIs<ApplyResult.Refused>(refusedApply)
            }
            "equivalent-preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
                assertNotNull(plan.authorityLease)
                val result = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.single().content
                assertFalse(result.contains("$helper("), "helper call/definition should be replaced: $result")
                when (case) {
                    "precedence" -> assertTrue(result.contains("2 * (3 + 4)"), result)
                    "string" -> {
                        assertTrue(result.contains("\"n\""), "string contents must be unchanged: $result")
                        assertFalse(result.contains("\"1\""), "parameter substitution must skip the string literal: $result")
                    }
                    else -> error("No image oracle for $case")
                }
            }
            else -> error("Unexpected disposition $disposition")
        }
    }

    fun unchanged() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        val metadata = workspace.resolve(".refactorkit")
        assertFalse(Files.exists(metadata.resolve("transactions")))
        if (refusedApply == null) assertFalse(Files.exists(metadata)) else {
            // An explicit Apply attempt takes the core writer lock before refusing;
            // the empty lock is not a transaction journal or a source mutation.
            val retained = Files.list(metadata).use { paths -> paths.map { it.fileName.toString() }.toList() }
            assertEquals(listOf("workspace.lock"), retained)
            assertEquals(0L, Files.size(metadata.resolve("workspace.lock")))
        }
    }

    override fun close() {
        sandbox?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

/** Scenario-owned resources and independent images; the public glue only delegates. */
private class ManagedCFunctionInlineFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var original: ByteArray
    private lateinit var database: ByteArray
    private lateinit var expected: String
    private lateinit var function: String
    private lateinit var plan: PatchPlan
    private var noApproval: ApplyResult? = null
    private var stale: ApplyResult? = null
    private var forged: ApplyResult? = null
    private var actual: ApplyResult? = null
    private var restored = false
    private val file = Path.of("main.c")

    fun capture(standard: String, shape: String) {
        require(standard in setOf("c11", "c17"))
        val add = "static int add(int a, int b) { return a + b; }\n"
        val locals = "int main(void) { int left = 3; int right = 4; return 2 * add(left, right); }\n"
        val code = when (shape) {
            "precedence" -> add + "int main(void) { return 2 * add(3, 4); }\n"
            "local arguments" -> add + locals
            "prototype family" -> "static int add(int left, int right);\n" + add + locals
            "string operand" -> "static int code(int n) { return \"n\"[0] + n; }\nint main(void) { return code(1); }\n"
            else -> error("Unknown captured inline shape: $shape")
        }
        function = if (shape == "string operand") "code" else "add"
        expected = when (shape) {
            "precedence" -> "int main(void) { return 2 * (3 + 4); }\n"
            "local arguments", "prototype family" -> "int main(void) { int left = 3; int right = 4; return 2 * (left + right); }\n"
            else -> "int main(void) { return (\"n\"[0] + 1); }\n"
        }
        val workspace = Files.createTempDirectory("refactorkit-c-managed-inline-function-").also { root = it }
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve(file), original)
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", file.toString())
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-c", file.toString()).map(::JsonPrimitive)))
        })).toString().toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        val processes = ExternalSemanticProcessManager().also { manager = it }
        facade = CRefactoringFacade(toolchain, processes)
    }

    fun execute() {
        val workspace = requireNotNull(root)
        val engine = requireNotNull(facade)
        val explicit = ApplyAuthorization.explicit("c12-managed-inline-story")
        val callerGate = DiagnosticsGate.disabled("caller-cannot-supply-inline-authority")
        plan = engine.preview(snapshot, "inlineFunction", mapOf("file" to file.toString(), "symbol" to function))
        noApproval = engine.apply(snapshot, plan, ApplyAuthorization.missing("c12-managed-inline-story"), callerGate)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        assertOriginal()
        Files.write(workspace.resolve(file), original + "/* changed after preview */\n".toByteArray(Charsets.UTF_8))
        try {
            stale = engine.apply(snapshot, plan, explicit, callerGate)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        } finally { Files.write(workspace.resolve(file), original) }
        val lease = plan.authorityLease
        if (lease != null) {
            val altered = OperationAuthorityLease(kind = lease.kind, operation = lease.operation, snapshotHash = lease.snapshotHash,
                evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                workspaceEditSha256 = lease.workspaceEditSha256, requiredClasspathEvidence = lease.requiredClasspathEvidence,
                requiredFileEvidence = lease.requiredFileEvidence,
                attributes = lease.attributes + ("c.afterSemanticSha256" to "0".repeat(64)))
            forged = engine.apply(snapshot, plan.copy(authorityLease = altered), explicit, callerGate)
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            assertOriginal()
        }
        actual = engine.apply(snapshot, plan, explicit, callerGate)
    }

    fun assertImage() {
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertEquals(1.0, plan.confidence)
        assertTrue(plan.requiresUserApproval)
        val lease = assertNotNull(plan.authorityLease)
        assertEquals("c.inline-function-int-expression-v1", lease.kind)
        assertEquals("int-eager-source-binding-v1", lease.attributes["c.inlineFunctionModelPolicy"])
        assertEquals(toolchain.provenance.nativeRuntimeIdentity, lease.attributes["c.nativeRuntimeIdentity"])
        assertEquals((toolchain.resources as ClangResourceCaptureResult.Captured).snapshot.identity,
            lease.attributes["c.resourceDirectoryIdentity"])
        for (key in listOf("c.beforeSemanticSha256", "c.afterSemanticSha256")) {
            assertTrue(assertNotNull(lease.attributes[key]).matches(Regex("[0-9a-f]{64}")), "Missing bound semantic model: $key")
        }
        assertIs<ApplyResult.Refused>(noApproval)
        assertIs<ApplyResult.Refused>(stale)
        assertIs<ApplyResult.Refused>(forged)
        assertIs<ApplyResult.Applied>(actual, actual.toString())
        assertEquals(expected, Files.readString(requireNotNull(root).resolve(file)))
        assertContentEquals(database, Files.readAllBytes(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(plan.diagnosticsBefore.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
        assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
    }

    fun rollback() {
        val applied = assertIs<ApplyResult.Applied>(actual)
        assertIs<ApplyResult.Applied>(PatchEngine(requireNotNull(root)).rollback(applied.transaction))
        restored = true
        assertOriginal()
        assertEquals(snapshot.hash, CProjectScanner().scan(requireNotNull(root)).hash)
    }

    private fun assertOriginal() {
        assertContentEquals(original, Files.readAllBytes(requireNotNull(root).resolve(file)))
        assertContentEquals(database, Files.readAllBytes(requireNotNull(root).resolve("compile_commands.json")))
    }

    override fun close() {
        val applied = actual as? ApplyResult.Applied
        if (!restored && applied != null) PatchEngine(requireNotNull(root)).rollback(applied.transaction)
        facade?.close()
        manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

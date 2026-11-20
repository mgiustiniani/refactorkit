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
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceCurrency
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.ApprovalKind
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JournalState
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TextEdits
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CResourceBoundPreviewSteps {
    private var ownRoot: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var oldFacade: CRefactoringFacade? = null
    private var currentFacade: CRefactoringFacade? = null
    private lateinit var workspace: Path
    private lateinit var resources: Path
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var sdkBefore: ClangResourceCaptureResult.Captured
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private var expectedIdentity = ""
    private var condition = ""
    private var operation = ""
    private var result: ApplyResult? = null
    private var database = ""
    private val prefix = "int required[REQUIRED - 16];\n"
    private val function = "static int calculate(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n"
    private val main = "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }\n"
    private val original = prefix + function + main

    @Given("an approved resource-bound C preview has {string}")
    fun prepare(case: String) {
        condition = case
        operation = when {
            case.startsWith("scalar") || case == "unchanged scalar control" -> "extractExpression"
            case.startsWith("helper") || case == "unchanged helper control" -> "extractStatements"
            case.startsWith("reordered") -> "reorderParameters"
            else -> "addParameter"
        }
        val root = Files.createTempDirectory("refactorkit-c-resource-preview-"); ownRoot = root
        workspace = Files.createDirectories(root.resolve("workspace")); resources = root.resolve("resources")
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val sdkResources = sdk.resolve("usr/lib/clang/22")
        sdkBefore = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(sdkResources))
        Files.walk(sdkResources).use { paths -> paths.forEach { path ->
            val target = resources.resolve(sdkResources.relativize(path))
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        val discovered = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))).toolchain
        val captured = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(resources))
        toolchain = discovered.copy(provenance = discovered.provenance.copy(resourceDir = resources.toString()), resources = captured)
        expectedIdentity = captured.snapshot.identity
        Files.writeString(workspace.resolve("main.c"), original)
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(discovered.clangExecutable.toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database); snapshot = CProjectScanner().scan(workspace)
        manager = ExternalSemanticProcessManager(); oldFacade = CRefactoringFacade(toolchain, requireNotNull(manager))
        val args = buildMap {
            put("file", "main.c"); put("symbol", "calculate")
            when (operation) {
                "reorderParameters" -> put("originalIndices", "1,0")
                "addParameter" -> { put("parameterName", "offset"); put("parameterOrdinal", "1"); put("callerExpression", "3") }
                "extractStatements" -> {
                    put("helperName", "compute"); put("startByte", original.indexOf("int sum").toString())
                    put("endByte", (original.indexOf(';', original.indexOf("int doubled")) + 1).toString())
                }
                "extractExpression" -> {
                    put("tempName", "total"); val offset = original.indexOf("a + b")
                    val from = TextEdits.positionForOffset(original, offset); val to = TextEdits.positionForOffset(original, offset + 5)
                    put("startLine", from.line.toString()); put("startChar", from.character.toString())
                    put("endLine", to.line.toString()); put("endChar", to.character.toString())
                }
            }
        }
        plan = requireNotNull(oldFacade).preview(snapshot, operation, args)
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary); assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
    }

    @When("it is applied with the currently captured resource toolchain")
    fun apply() {
        val currentToolchain = if (condition.endsWith("rediscovered")) {
            Files.writeString(resources.resolve("include/stddef.h"), "\n/* concurrent resource replacement */\n", StandardOpenOption.APPEND)
            val refreshed = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(resources))
            assertTrue(refreshed.snapshot.identity != expectedIdentity)
            toolchain.copy(resources = refreshed)
        } else toolchain
        currentFacade = CRefactoringFacade(currentToolchain, requireNotNull(manager))
        result = requireNotNull(currentFacade).apply(snapshot, plan, ApplyAuthorization.explicit("resource-preview-story", "bdd-caller"),
            DiagnosticsGate.disabled("caller-disabled"))
    }

    @Then("resource-bound preview application reports {string}")
    fun report(expected: String) {
        if (expected == "old resource preview refused") {
            val refused = assertIs<ApplyResult.Refused>(result, result.toString())
            assertEquals(expectedIdentity, assertNotNull(plan.authorityLease).attributes["c.resourceDirectoryIdentity"])
            assertTrue(refused.diagnostics.any { it.message.contains("authorityInvalid") }, refused.diagnostics.toString())
            assertTrue(TransactionLog(workspace.resolve(".refactorkit/transactions")).listRecords().isEmpty())
        } else {
            assertEquals("applied and rolled back", expected); val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals(expectedIdentity, assertNotNull(plan.authorityLease).attributes["c.resourceDirectoryIdentity"])
            val after = if (operation == "extractExpression") prefix +
                "static int calculate(int a, int b) {\n    const int total = a + b;\n    int sum = total;\n    int doubled = sum * 2;\n    return doubled;\n}\n" + main
                else prefix + "static int compute(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n\n" +
                    "static int calculate(int a, int b) {\n    int doubled = compute(a, b);\n    return doubled;\n}\n" + main
            assertEquals(after, Files.readString(workspace.resolve("main.c")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            val record = TransactionLog(workspace.resolve(".refactorkit/transactions")).listRecords().single()
            assertEquals(JournalState.ROLLED_BACK, record.state); assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
            assertEquals(original, record.preImages.single { it.path == Path.of("main.c") }.content)
            assertEquals(after, record.postImages.single { it.path == Path.of("main.c") }.content)
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
    }

    @Then("resource preview validation preserves SDK configuration and compiler ownership")
    fun intact() {
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(sdkBefore.snapshot))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
    }

    @After
    fun cleanup() { currentFacade?.close(); oldFacade?.close(); manager?.close(); ownRoot?.let { root ->
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    } }
}

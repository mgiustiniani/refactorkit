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
import org.refactorkit.core.PatchFaultInjector
import org.refactorkit.core.PatchFaultPoint
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TextEdits
import org.refactorkit.core.TransactionLog
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CNativeManagedPostCommitSteps {
    private var root: Path? = null
    private var facade: CRefactoringFacade? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var gate: DiagnosticsGate
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var originalDatabase: String
    private lateinit var expectedDatabase: String
    private lateinit var privateBefore: Set<Path>
    private var condition = ""
    private var operation = ""
    private var committedObserved = false
    private var result: ApplyResult? = null
    private var resourceHeader: Path? = null
    private var resourceFixtureRoot: Path? = null
    private var expectedResourceBytes: ByteArray? = null
    private var sdkResources: ClangResourceCaptureResult.Captured? = null

    @Given("an approved native C {string} transaction awaits {string}")
    fun prepare(requestedOperation: String, case: String) {
        operation = requestedOperation; condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-native-post-commit-"); root = workspace
        val prefix = "int required[REQUIRED - 16];\n"
        val function = "static int calculate(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n"
        val main = "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }\n"
        original = prefix + function + main
        expectedSource = when (operation) {
            "reorderParameters" -> prefix + "static int calculate(int b, int a) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n" +
                "int main(void) { return calculate(14, 7) == 42 ? 0 : 1; }\n"
            "addParameter" -> prefix + "static int calculate(int a, int offset, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n" +
                "int main(void) { return calculate(7, (3), 14) == 42 ? 0 : 1; }\n"
            "extractStatements" -> prefix + "static int compute(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n\n" +
                "static int calculate(int a, int b) {\n    int doubled = compute(a, b);\n    return doubled;\n}\n" + main
            "extractExpression" -> prefix + "static int calculate(int a, int b) {\n    const int total = a + b;\n    int sum = total;\n    int doubled = sum * 2;\n    return doubled;\n}\n" + main
            else -> error("Unknown operation: $operation")
        }
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        originalDatabase = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        expectedDatabase = if (condition == "database revoked") originalDatabase.replace("-DREQUIRED=17", "-DREQUIRED=18") else originalDatabase
        Files.writeString(workspace.resolve("compile_commands.json"), originalDatabase)
        snapshot = CProjectScanner().scan(workspace)
        var toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        if (condition in setOf("resources revoked", "resources unchanged")) {
            val originalResources = assertIs<ClangResourceCaptureResult.Captured>(toolchain.resources); sdkResources = originalResources
            val destination = Files.createTempDirectory("refactorkit-c-post-commit-resources-"); resourceFixtureRoot = destination
            Files.walk(originalResources.snapshot.root).use { paths -> paths.forEach { path ->
                val target = destination.resolve(originalResources.snapshot.root.relativize(path))
                if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else Files.copy(path, target)
            } }
            val captured = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(destination))
            toolchain = toolchain.copy(provenance = toolchain.provenance.copy(resourceDir = destination.toString()), resources = captured)
            resourceHeader = destination.resolve("include/stddef.h")
            val originalBytes = Files.readAllBytes(requireNotNull(resourceHeader))
            expectedResourceBytes = if (condition == "resources revoked") originalBytes + "\n/* concurrent resource change */\n".toByteArray()
                else originalBytes
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash, "Private resource fixtures are outside source snapshot authority")
        }
        manager = ExternalSemanticProcessManager(); facade = CRefactoringFacade(toolchain, requireNotNull(manager))
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
                    put("tempName", "total")
                    val start = original.indexOf("a + b"); val from = TextEdits.positionForOffset(original, start)
                    val to = TextEdits.positionForOffset(original, start + 5)
                    put("startLine", from.line.toString()); put("startChar", from.character.toString())
                    put("endLine", to.line.toString()); put("endChar", to.character.toString())
                }
            }
        }
        plan = requireNotNull(facade).preview(snapshot, operation, args)
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence); assertNotNull(plan.authorityLease)
        gate = requireNotNull(facade).diagnosticsGate(snapshot, plan)
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        privateBefore = privateDirectories()
    }

    @When("its committed source is checked against the retained compiler gate")
    fun commit() {
        val workspace = requireNotNull(root)
        val injection = PatchFaultInjector { point, path, sequence ->
            if (point == PatchFaultPoint.AFTER_COMMITTED_IMAGE && !committedObserved) {
                assertEquals(Path.of("main.c"), path); assertEquals(1, sequence)
                assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
                assertEquals(originalDatabase, Files.readString(workspace.resolve("compile_commands.json")))
                committedObserved = true
                if (condition == "database revoked") Files.writeString(workspace.resolve("compile_commands.json"), expectedDatabase)
                if (condition == "resources revoked") Files.write(requireNotNull(resourceHeader), requireNotNull(expectedResourceBytes))
            }
        }
        result = PatchEngine(workspace, faultInjector = injection).apply(plan, snapshot,
            ApplyAuthorization.explicit("c-native-post-commit-story", "bdd-caller"), gate)
    }

    @Then("the native C transaction reports {string} with an exact source journal")
    fun report(expected: String) {
        val workspace = requireNotNull(root); assertTrue(committedObserved, "The configured hook must observe the committed candidate")
        if (expected == "automatic rollback and refusal") {
            val refused = assertIs<ApplyResult.Refused>(result, result.toString())
            assertTrue(refused.diagnostics.any { it.code == "diagnostics.postApplyUnavailable" }, refused.diagnostics.toString())
            assertTrue(refused.diagnostics.any { it.code == "diagnostics.rollbackUnavailable" }, refused.diagnostics.toString())
        } else {
            assertEquals("applied and manually rolled back", expected)
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        val record = TransactionLog(workspace.resolve(".refactorkit/transactions")).listRecords().single()
        assertEquals(JournalState.ROLLED_BACK, record.state)
        assertEquals(operation, record.operation)
        assertEquals(ApprovalKind.EXPLICIT_APPLY, record.transaction.approval.kind)
        assertEquals("c-native-post-commit-story", record.transaction.approval.surface)
        assertTrue(record.history.any { it.state == JournalState.APPLIED })
        assertTrue(record.history.any { it.state == JournalState.ROLLING_BACK })
        assertEquals(original, record.preImages.single { it.path == Path.of("main.c") }.content)
        assertEquals(expectedSource, record.postImages.single { it.path == Path.of("main.c") }.content)
    }

    @Then("the native C transaction preserves concurrent configuration and releases compiler material")
    fun intact() {
        val workspace = requireNotNull(root)
        assertEquals(expectedDatabase, Files.readString(workspace.resolve("compile_commands.json")))
        if (condition == "database revoked") assertTrue(CProjectScanner().scan(workspace).hash != snapshot.hash,
            "A concurrent configuration must remain changed even after exact source rollback")
        resourceHeader?.let { header ->
            assertTrue(Files.readAllBytes(header).contentEquals(requireNotNull(expectedResourceBytes)), "Concurrent resource bytes must be preserved")
            assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash, "Exact source/configuration rollback must restore the project hash")
            assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(requireNotNull(sdkResources).snapshot))
        }
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty()); assertEquals(privateBefore, privateDirectories())
    }

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    @After
    fun cleanup() {
        facade?.close(); manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        resourceFixtureRoot?.let { directory -> Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

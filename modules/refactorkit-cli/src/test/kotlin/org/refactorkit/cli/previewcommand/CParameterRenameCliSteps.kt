package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CSignaturePlanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.cli.RefactorKitCli
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
import org.refactorkit.core.WorkspaceEdit
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterRenameCliSteps {
    private var root: Path? = null
    private lateinit var source: String
    private lateinit var database: String
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private lateinit var command: List<String>
    private var case = ""
    private var exit = -1
    private var output = ""
    private var managedManager: ExternalSemanticProcessManager? = null
    private var managedFacade: CRefactoringFacade? = null
    private lateinit var managedSnapshot: ProjectSnapshot
    private lateinit var managedPlan: PatchPlan
    private lateinit var managedGate: DiagnosticsGate
    private lateinit var managedExpectedSource: String
    private var managedResult: ApplyResult? = null

    @Given("a captured C parameter rename CLI invocation has {string}")
    fun invocation(condition: String) {
        case = condition
        val workspace = Files.createTempDirectory("refactorkit-c-rename-cli-")
        root = workspace
        val unicode = condition.contains("Unicode")
        val newline = if (unicode) "\r\n" else "\n"
        val prefix = if (unicode) "/* é 😀 */$newline" else ""
        source = if (condition == "external function consumers") {
            prefix + "int required[REQUIRED - 16];$newline" +
                "int exposed(int amount) { return amount; }$newline" +
                "int main(void) { return exposed(4); }$newline"
        } else {
            prefix + "int required[REQUIRED - 16];$newline" +
                "static int weighted(int amount, int right);$newline" +
                "static int weighted(int amount, int right) {$newline" +
                "    return amount + right;$newline" +
                "}$newline" +
                "static int unrelated(int amount) { return amount; }$newline" +
                "int main(void) { return weighted(5, 2); }$newline"
        }
        Files.writeString(workspace.resolve("main.c"), source)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = """{"directory":"$workspace","file":"main.c","arguments":["${reference.resolve("usr/bin/clang-22")}","-std=c17","-DREQUIRED=17","-c","main.c"]}"""
        database = "[$unit]"
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        command = listOf(
            "c", "change-signature", workspace.toString(), "--operation", "rename-parameter",
            "--file", "main.c", "--symbol", if (condition == "external function consumers") "exposed" else "weighted",
            "--old-param", "amount", "--new-param", "total",
            "--clang", reference.resolve("usr/bin/clang-22").toString(),
            "--clangd", reference.resolve("usr/bin/clangd").toString(),
            "--clang-format", reference.resolve("usr/bin/clang-format").toString(), "--preview",
        )
        beforeHash = CProjectScanner().scan(workspace).hash
        privateBefore = privateDirectories()
    }

    @When("the C parameter rename CLI command is previewed")
    fun preview() {
        val oldOutput = System.out
        val oldError = System.err
        val capturedOutput = ByteArrayOutputStream()
        val capturedError = ByteArrayOutputStream()
        exit = try {
            System.setOut(PrintStream(capturedOutput, true, Charsets.UTF_8))
            System.setErr(PrintStream(capturedError, true, Charsets.UTF_8))
            RefactorKitCli().run(command)
        } finally {
            System.setOut(oldOutput)
            System.setErr(oldError)
        }
        output = capturedOutput.toString(Charsets.UTF_8) + capturedError.toString(Charsets.UTF_8)
    }

    @Then("the C parameter rename CLI reports {string}")
    fun report(expected: String) {
        val workspace = requireNotNull(root)
        when (expected) {
            "selected family preview" -> {
                assertEquals(0, exit, output)
                val response = Json.parseToJsonElement(output).jsonObject
                assertEquals("PREVIEW", response.getValue("status").jsonPrimitive.content)
                assertEquals("changeSignature", response.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", response.getValue("evidence").jsonPrimitive.content)
                assertEquals(1, response.getValue("affectedFiles").jsonArray.size)
                val diff = response.getValue("renderedDiff").jsonPrimitive.content
                assertTrue(diff.contains("+static int weighted(int total, int right);"), diff)
                assertTrue(diff.contains("+static int weighted(int total, int right) {"), diff)
                assertTrue(diff.contains("+    return total + right;"), diff)
                assertFalse(diff.contains("+static int unrelated(int total)"), diff)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "family refused" -> {
                assertEquals(1, exit, output)
                assertTrue(output.contains("external consumers", ignoreCase = true), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            else -> error("Unexpected outcome: $expected")
        }
        assertEquals(source, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
        assertEquals(privateBefore, privateDirectories())
    }

    @Given("a compiler-bound C parameter rename awaits {string}")
    fun prepareManagedRename(condition: String) {
        case = condition
        val workspace = Files.createTempDirectory("refactorkit-c-rename-apply-")
        root = workspace
        source = "int required[REQUIRED - 16];\n" +
            "static int weighted(int amount, int right);\n" +
            "static int weighted(int amount, int right) {\n" +
            "    return amount + right;\n" +
            "}\n" +
            "int main(void) { return weighted(5, 2); }\n"
        managedExpectedSource = "int required[REQUIRED - 16];\n" +
            "static int weighted(int total, int right);\n" +
            "static int weighted(int total, int right) {\n" +
            "    return total + right;\n" +
            "}\n" +
            "int main(void) { return weighted(5, 2); }\n"
        Files.writeString(workspace.resolve("main.c"), source)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        managedSnapshot = CProjectScanner().scan(workspace)
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        managedManager = ExternalSemanticProcessManager()
        managedFacade = CRefactoringFacade(toolchain, requireNotNull(managedManager))
        CSignaturePlanner(toolchain, requireNotNull(managedManager)).use { planner ->
            planner.start(managedSnapshot)
            managedPlan = planner.renameParameter(managedSnapshot, Path.of("main.c"), "weighted", "amount", "total")
        }
        assertEquals(PatchStatus.PREVIEW, managedPlan.status, managedPlan.summary)
        assertEquals("changeSignature", managedPlan.operation)
        assertEquals("c.signature-rename-v1", managedPlan.authorityLease?.kind)
        managedGate = requireNotNull(managedFacade).diagnosticsGate(managedSnapshot, managedPlan)
        beforeHash = managedSnapshot.hash
        privateBefore = privateDirectories()
    }

    @When("the managed C parameter rename is applied")
    fun applyManagedRename() {
        val workspace = requireNotNull(root)
        if (case == "compilation database changed") {
            database += " \n"
            Files.writeString(workspace.resolve("compile_commands.json"), database)
        }
        if (case == "preview edits changed") {
            val edit = managedPlan.workspaceEdit.edits.single() as FileEdit.Modify
            val edits = edit.textEdits.toMutableList()
            edits[0] = edits.first().copy(newText = "tampered")
            managedPlan = managedPlan.copy(workspaceEdit = WorkspaceEdit(listOf(edit.copy(textEdits = edits))))
        }
        if (case == "preview lease removed") managedPlan = managedPlan.copy(authorityLease = null)
        if (case == "native runtime identity changed") {
            val lease = assertNotNull(managedPlan.authorityLease)
            managedPlan = managedPlan.copy(authorityLease = OperationAuthorityLease(
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
        val authorization = if (case == "approval missing") ApplyAuthorization.missing("c-rename-story", "bdd-caller")
            else ApplyAuthorization.explicit("c-rename-story", "bdd-caller")
        managedResult = requireNotNull(managedFacade).apply(
            managedSnapshot, managedPlan, authorization, DiagnosticsGate.disabled("caller-disabled"),
        )
    }

    @Then("the managed C parameter rename has outcome {string}")
    fun managedRenameOutcome(expected: String) {
        val workspace = requireNotNull(root)
        if (expected == "applied and rolled back") {
            val applied = assertIs<ApplyResult.Applied>(managedResult, managedResult.toString())
            assertEquals(ApprovalKind.EXPLICIT_APPLY, applied.transaction.approval.kind)
            assertEquals("c-rename-story", applied.transaction.approval.surface)
            assertEquals("bdd-caller", applied.transaction.approval.actor)
            assertEquals(managedExpectedSource, Files.readString(workspace.resolve("main.c")))
            assertTrue(CProjectScanner().scan(workspace).hash != managedSnapshot.hash)
            assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
            assertEquals(source, Files.readString(workspace.resolve("main.c")))
            assertEquals(managedSnapshot.hash, CProjectScanner().scan(workspace).hash)
        } else {
            val refused = assertIs<ApplyResult.Refused>(managedResult, managedResult.toString())
            assertTrue(refused.diagnostics.any { diagnostic -> when (expected) {
                "approval refused" -> diagnostic.code == "approval.required"
                "changed authority refused" -> diagnostic.code in setOf("snapshot.changed", "authorityLease.snapshotMismatch", "snapshot.underLockChanged", "snapshot.scopeChanged", "diagnostics.unavailable") || diagnostic.message.contains("c.signatureRename.authorityInvalid")
                "changed edit refused" -> diagnostic.code == "authorityLease.workspaceEditMismatch" || diagnostic.message.contains("c.signatureRename.authorityInvalid")
                "missing authority refused", "runtime identity refused" -> diagnostic.message.contains("c.signatureRename.authorityInvalid")
                else -> error("Unexpected outcome: $expected")
            } }, refused.diagnostics.toString())
            assertEquals(source, Files.readString(workspace.resolve("main.c")))
            assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        }
    }

    @And("the rename preserves source and compiler inputs after its outcome")
    fun managedRenameInputsRemain() {
        val workspace = requireNotNull(root)
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertTrue(requireNotNull(managedManager).activeProvenance().isEmpty())
        assertEquals(privateBefore, privateDirectories())
    }

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter {
            it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
                it.fileName.toString().startsWith("refactorkit-semantic-overlay-")
        }.toList().toSet()
    }

    @After
    fun cleanup() {
        managedFacade?.close()
        managedManager?.close()
        root?.let { workspace ->
            Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}

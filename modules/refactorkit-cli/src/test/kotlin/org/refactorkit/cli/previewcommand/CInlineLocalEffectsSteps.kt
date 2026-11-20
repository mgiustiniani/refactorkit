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
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CInlineLocalEffectsSteps {
    private var sandbox: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private var facade: CRefactoringFacade? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var expectedAfter: String
    private var condition = ""

    @Given("an isolated C inline-local source with {string}")
    fun source(case: String) {
        condition = case
        val code = when (case) {
            "alias" -> "int main(void) { int y = 3; int *p = &y; int x = y; *p = 4; return x; }\n"
            "dereference" -> "int main(void) { int y = 3; int *p = &y; int x = *p; y = 4; return x; }\n"
            "conversion" -> "int main(void) { double x = 3; return x / 2 == 1; }\n"
            "volatile" -> "int main(void) { volatile int x = 5; return x; }\n"
            "compiler literal" -> "int main(void) { int x = 5; return x; }\n"
            else -> error("Unknown case: $case")
        }
        sandbox = Files.createTempDirectory("refactorkit-c-inline-local-")
        workspace = Files.createDirectory(sandbox!!.resolve("workspace"))
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        expectedAfter = "int main(void) { return 5; }\n"
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())
        snapshot = CProjectScanner().scan(workspace)
        val discovered = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"),
            reference.resolve("usr/bin/clang-format"),
        )))
        assertEquals("22.1.8", discovered.toolchain.provenance.clangVersion)
        manager = ExternalSemanticProcessManager()
        facade = CRefactoringFacade(discovered.toolchain, requireNotNull(manager))
    }

    @When("the C inline-local preview is requested for x")
    fun preview() {
        plan = requireNotNull(facade).preview(snapshot, "inlineLocalVariable", mapOf("file" to "main.c", "symbol" to "x"))
    }

    @Then("inline-local is refused without any edit because preservation is unproven")
    fun refused() {
        assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
        assertEquals("c.inlineLocal.unsupported_shape", plan.refusalCode, plan.toString())
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertEquals(DiagnosticEvidence.COMPILER, plan.diagnosticsAfterPreview.single().evidence)
        assertTrue(plan.workspaceEdit.edits.isEmpty())
        assertTrue(plan.affectedFiles.isEmpty())
    }

    @Then("the inline-local source bytes and transaction journal remain unchanged")
    fun unchanged() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    @Then("the compiler-proven inline-local preview applies and rolls back with exact source restoration")
    fun appliesAndRollsBack() {
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
        assertNotNull(plan.authorityLease)
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))

        val result = requireNotNull(facade).apply(snapshot, plan,
            ApplyAuthorization.explicit("c-inline-local-story", "bdd-caller"), DiagnosticsGate.disabled("caller-disabled"))
        val applied = assertIs<ApplyResult.Applied>(result, result.toString())
        assertEquals(expectedAfter, Files.readString(workspace.resolve("main.c")))
        assertIs<ApplyResult.Applied>(PatchEngine(workspace).rollback(applied.transaction))
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
    }

    @After
    fun cleanup() {
        facade?.close()
        manager?.close()
        sandbox?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

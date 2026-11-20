package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CRefactoringFacade
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
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

class CExtractTypePlacementSteps {
    private val fixture = CExtractTypePlacementFixture()

    @Given("an isolated C extract-expression source with {string}")
    fun source(case: String) = fixture.source(case)

    @When("the selected C expression is previewed as a temporary")
    fun preview() = fixture.preview()

    @Then("the {word} extract preview is {string} without an out-of-scope temporary")
    fun outcome(case: String, disposition: String) = fixture.outcome(case, disposition)

    @Then("the extract source bytes and transaction journal remain unchanged")
    fun unchanged() = fixture.unchanged()

    @After
    fun cleanup() = fixture.cleanup()
}

private class CExtractTypePlacementFixture {
    private var sandbox: Path? = null
    private lateinit var workspace: Path
    private lateinit var original: ByteArray
    private lateinit var database: ByteArray
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var selection: Map<String, String>

    fun source(case: String) {
        val code = when (case) {
            "conversion" -> "int main(void) {\n    double y = 0;\n    y = 5 / 2;\n    return y == 2;\n}\n"
            "local-scope" -> "int compute(int x) { int y = x + 1; return y; }\n"
            else -> error("Unknown case $case")
        }
        selection = if (case == "conversion") mapOf(
            "startLine" to "2", "startChar" to "8", "endLine" to "2", "endChar" to "9",
        ) else mapOf("startLine" to "0", "startChar" to "29", "endLine" to "0", "endChar" to "34")
        sandbox = Files.createTempDirectory("refactorkit-c-extract-")
        workspace = Files.createDirectory(sandbox!!.resolve("workspace"))
        original = code.toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("main.c"), original)
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString().toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("compile_commands.json"), database)
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
                plan = facade.preview(snapshot, "extractExpression", selection + mapOf("file" to "main.c", "tempName" to "tmp"))
            }
        }
    }

    fun outcome(case: String, disposition: String) {
        when (disposition) {
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                assertEquals("c.scalarFacts.unsupported_context", plan.refusalCode, plan.summary)
                assertEquals(null, plan.authorityLease)
                assertTrue(plan.diagnosticsAfterPreview.any { it.code == plan.refusalCode })
            }
            "in-scope-preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                val result = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit).files.single().content
                assertEquals("int compute(int x) { const int tmp = x + 1; int y = tmp; return y; }\n", result)
                assertEquals(RefactoringEvidence.COMPILER_PROVEN, plan.evidence)
                assertEquals("c.scalar-extract-v1", assertNotNull(plan.authorityLease).kind)
                assertTrue(plan.requiresUserApproval)
                assertTrue(plan.diagnosticsBefore.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
                assertTrue(plan.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
                assertFalse(result.startsWith("const int tmp"), result)
            }
            else -> error("Unknown outcome for $case: $disposition")
        }
    }

    fun unchanged() {
        assertContentEquals(original, Files.readAllBytes(workspace.resolve("main.c")))
        assertContentEquals(database, Files.readAllBytes(workspace.resolve("compile_commands.json")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        assertFalse(Files.exists(workspace.resolve(".refactorkit")))
    }

    fun cleanup() {
        sandbox?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

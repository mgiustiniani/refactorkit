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
import org.refactorkit.c.CSignaturePlanner
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CParameterReorderingPreviewSteps {
    private var root: Path? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var saved: Map<Path, String>
    private lateinit var privateBefore: Set<Path>
    private var order = listOf(2, 1, 0)
    private var dialect = "-std=c17"
    private var plan: PatchPlan? = null
    private var managerEmpty = false

    @Given("a captured C parameter reorder has {string}")
    fun capture(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-reorder-preview-")
        root = workspace
        val unicode = case == "C11 Unicode CRLF family"
        val newline = if (unicode) "\r\n" else "\n"
        dialect = if (unicode) "-std=c11" else "-std=c17"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */ " else ""
        val pointer = if (case == "function address taken") "static int (*consumer)(int, int, int) = weighted;$newline" else ""
        val first = if (case == "observable argument") "weighted(n++, 8, 7)" else "weighted(9, 8, 7)"
        original = prefix + "static int weighted(int x, int y, int z);$newline" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }$newline" + pointer +
            "int main(void) { int n = 0; return ($first == 987 && weighted(3, 2, 1) == 321) ? 0 : 1; }$newline" +
            "int unrelated(void) { int weighted = 11; return weighted; }$newline"
        expectedSource = prefix + "static int weighted(int z, int y, int x);$newline" +
            "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }$newline" +
            "int main(void) { int n = 0; return (weighted(7, 8, 9) == 987 && weighted(1, 2, 3) == 321) ? 0 : 1; }$newline" +
            "int unrelated(void) { int weighted = 11; return weighted; }$newline"
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val arguments = listOf(reference.resolve("usr/bin/clang-22").toString(), dialect, "-DREQUIRED=17", "-c", "main.c", "-o", "never-write.o")
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        val database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        if (case == "duplicate original index") order = listOf(2, 2, 0)
        if (case == "changed captured database") Files.writeString(workspace.resolve("compile_commands.json"), database.replace("-DREQUIRED=17", "-DREQUIRED=18"))
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        saved = savedFiles(workspace)
        privateBefore = privateDirectories()
    }

    @When("its complete original-index mapping is previewed")
    fun preview() {
        ExternalSemanticProcessManager().use { manager ->
            CSignaturePlanner(toolchain, manager).use { planner ->
                plan = planner.reorderParameters(snapshot, Path.of("main.c"), "weighted", order)
            }
            managerEmpty = manager.activeProvenance().isEmpty()
        }
    }

    @Then("the parameter reorder preview has disposition {string}")
    fun disposition(expected: String) {
        val result = assertNotNull(plan)
        assertEquals("reorderParameters", result.operation)
        assertEquals(snapshot.hash, result.snapshotHash)
        if (expected == "complete preview") {
            assertEquals(PatchStatus.PREVIEW, result.status)
            assertTrue(result.requiresUserApproval)
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, result.evidence)
            assertEquals(setOf(Path.of("main.c")), result.affectedFiles)
            val edit = assertIs<FileEdit.Modify>(result.workspaceEdit.edits.single())
            assertEquals(8, edit.textEdits.size)
            val candidate = TextEdits.apply(original, edit.textEdits)
            assertEquals(expectedSource, candidate)
            assertTrue(result.diagnosticsBefore.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
            assertTrue(result.diagnosticsAfterPreview.none { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR })
            assertEquals(0, executeFixture(original))
            assertEquals(0, executeFixture(candidate))
        } else {
            assertEquals(PatchStatus.REFUSED, result.status)
            assertTrue(result.workspaceEdit.edits.isEmpty())
            assertTrue(result.affectedFiles.isEmpty())
            assertFalse(result.requiresUserApproval)
            assertEquals(when (expected) {
                "observable argument refused" -> "c.signatureReorder.observable_effect"
                "indirect consumers refused" -> "c.signatureReorder.indirect_calls"
                "invalid mapping refused" -> "c.signatureReorder.invalid_mapping"
                "stale authority refused" -> "c.functionFacts.snapshotChanged"
                else -> error("Unexpected disposition: $expected")
            }, result.refusalCode)
        }
    }

    @Then("the parameter reorder preview preserves saved bytes and releases private compiler material")
    fun untouched() {
        assertEquals(saved, savedFiles(requireNotNull(root)))
        assertFalse(Files.exists(requireNotNull(root).resolve("never-write.o")))
        assertTrue(managerEmpty)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun executeFixture(content: String): Int {
        val directory = Files.createTempDirectory("refactorkit-authored-c-oracle-")
        try {
            val source = directory.resolve("main.c")
            val executable = directory.resolve("oracle")
            Files.writeString(source, content)
            val output = directory.resolve("compile.log")
            val compiler = ProcessBuilder(toolchain.clangExecutable.toString(), dialect, "-DREQUIRED=17", source.toString(), "-o", executable.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(compiler.waitFor(30, TimeUnit.SECONDS), "Authored fixture compilation timed out")
                assertEquals(0, compiler.exitValue(), Files.readString(output))
            } finally { if (compiler.isAlive) compiler.destroyForcibly().waitFor() }
            val execution = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try {
                assertTrue(execution.waitFor(10, TimeUnit.SECONDS), "Authored fixture execution timed out")
                return execution.exitValue()
            } finally { if (execution.isAlive) execution.destroyForcibly().waitFor() }
        } finally { deleteTree(directory) }
    }

    private fun savedFiles(workspace: Path) = Files.walk(workspace).use { paths -> paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) } }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    private fun deleteTree(directory: Path) = Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }

    @After
    fun cleanup() { root?.let(::deleteTree) }
}

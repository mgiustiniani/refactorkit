package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CExplicitCallArgument
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
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

class CParameterInsertionPreviewSteps {
    private var root: Path? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var saved: Map<Path, String>
    private lateinit var privateBefore: Set<Path>
    private lateinit var arguments: List<CExplicitCallArgument>
    private var dialect = "-std=c17"
    private var parameter = "limit"
    private var ordinal = 1
    private var plan: PatchPlan? = null
    private var managerEmpty = false

    @Given("a captured C parameter insertion has {string}")
    fun capture(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-add-preview-")
        root = workspace
        val prefix = "int required[REQUIRED - 16];\nint seed = 7;\nint n = 0;\n"
        if (case == "C11 explicit void family") {
            dialect = "-std=c11"; ordinal = 0
            original = prefix + "static int weighted(/* left */ void /* right */);\n" +
                "static int weighted(void) { return 7; }\nint main(void) { return weighted() == 7 ? 0 : 1; }\n"
            expectedSource = prefix + "static int weighted(/* left */ int limit /* right */);\n" +
                "static int weighted(int limit) { return 7; }\nint main(void) { return weighted((seed)) == 7 ? 0 : 1; }\n"
        } else {
            val body = if (case == "global binding captured") "a * 10 + b + seed" else "a * 10 + b"
            val address = if (case == "function address taken") "static int (*callback)(int, int) = weighted;\n" else ""
            original = prefix + "static int weighted(int x, int y);\nstatic int weighted(int a, int b) { return $body; }\n" + address +
                "int main(void) { return weighted(9, 8) == 98 && weighted(3, 2) == 32 ? 0 : 1; }\n"
            expectedSource = prefix + "static int weighted(int x, int limit, int y);\nstatic int weighted(int a, int limit, int b) { return a * 10 + b; }\n" +
                "int main(void) { return weighted(9, (5), 8) == 98 && weighted(3, (7), 2) == 32 ? 0 : 1; }\n"
            if (case == "global binding captured") parameter = "seed"
        }
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), dialect, "-DREQUIRED=17", "-c", "main.c", "-o", "never-write.o").map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())
        snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        val before = ExternalSemanticProcessManager().use { manager ->
            assertIs<CFunctionFactsResult.Available>(CFunctionFactsService(toolchain, manager).analyze(snapshot, Path.of("main.c"), "weighted")).facts
        }
        val values = if (case == "C11 explicit void family") listOf("seed") else listOf(when (case) {
            "observable added argument" -> "n++"
            "escaped caller expression" -> "5), 8); } int injected(void) { return weighted(9, (5"
            else -> "5"
        }, "7")
        arguments = before.calls.zip(values) { call, expression -> CExplicitCallArgument(call.span, expression) }
        if (case == "escaped caller expression") {
            // Independently authored valid C: compilation alone must not authorize this escaped argument.
            val escaped = prefix + "static int weighted(int x, int limit, int y);\nstatic int weighted(int a, int limit, int b) { return a * 10 + b; }\n" +
                "int main(void) { return weighted(9, (5), 8); } int injected(void) { return weighted(9, (5), 8) == 98 && weighted(3, (7), 2) == 32 ? 0 : 1; }\n"
            assertEquals(0, checkFixture(escaped, false))
        }
        saved = savedFiles(workspace)
        privateBefore = privateDirectories()
    }

    @When("its named parameter and explicit caller expressions are previewed")
    fun preview() {
        ExternalSemanticProcessManager().use { manager ->
            CSignaturePlanner(toolchain, manager).use { planner ->
                plan = planner.addParameter(snapshot, Path.of("main.c"), "weighted", parameter, ordinal, arguments)
            }
            managerEmpty = manager.activeProvenance().isEmpty()
        }
    }

    @Then("the C parameter insertion preview has disposition {string}")
    fun disposition(expected: String) {
        val result = assertNotNull(plan)
        assertEquals("addParameter", result.operation)
        assertEquals(snapshot.hash, result.snapshotHash)
        if (expected == "complete insertion preview") {
            assertEquals(PatchStatus.PREVIEW, result.status, result.summary)
            assertTrue(result.requiresUserApproval)
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, result.evidence)
            assertEquals("c.signature-add-v1", result.authorityLease?.kind)
            assertEquals(setOf(Path.of("main.c")), result.affectedFiles)
            val edits = assertIs<FileEdit.Modify>(result.workspaceEdit.edits.single()).textEdits
            assertEquals(arguments.size + 2, edits.size)
            val candidate = TextEdits.apply(original, edits)
            assertEquals(expectedSource, candidate)
            assertEquals(0, checkFixture(original, true))
            assertEquals(0, checkFixture(candidate, true))
        } else {
            assertEquals(PatchStatus.REFUSED, result.status)
            assertTrue(result.workspaceEdit.edits.isEmpty() && result.affectedFiles.isEmpty())
            assertFalse(result.requiresUserApproval)
            assertEquals(when (expected) {
                "name capture refused" -> "c.signatureAdd.name_capture"
                "indirect consumers refused" -> "c.signatureAdd.indirect_calls"
                "observable effect refused" -> "c.signatureAdd.observable_effect"
                "escaped expression refused" -> "c.signatureAdd.invalid_candidate"
                else -> error("Unexpected disposition: $expected")
            }, result.refusalCode, result.summary)
        }
    }

    @Then("C parameter insertion preview preserves saved bytes and releases compiler material")
    fun intact() {
        assertEquals(saved, savedFiles(requireNotNull(root)))
        assertFalse(Files.exists(requireNotNull(root).resolve("never-write.o")))
        assertTrue(managerEmpty)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun checkFixture(content: String, execute: Boolean): Int {
        val directory = Files.createTempDirectory("refactorkit-authored-c-insertion-")
        try {
            val source = directory.resolve("main.c"); val executable = directory.resolve("oracle"); val output = directory.resolve("compile.log")
            Files.writeString(source, content)
            val flags = if (execute) listOf("-o", executable.toString()) else listOf("-fsyntax-only")
            val compiler = ProcessBuilder(listOf(toolchain.clangExecutable.toString(), dialect, "-DREQUIRED=17", source.toString()) + flags)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try { assertTrue(compiler.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, compiler.exitValue(), Files.readString(output)) }
            finally { if (compiler.isAlive) compiler.destroyForcibly().waitFor() }
            if (!execute) return 0
            val execution = ProcessBuilder(executable.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            try { assertTrue(execution.waitFor(10, TimeUnit.SECONDS)); return execution.exitValue() }
            finally { if (execution.isAlive) execution.destroyForcibly().waitFor() }
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private fun savedFiles(workspace: Path) = Files.walk(workspace).use { paths -> paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) } }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() { root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } } }
}

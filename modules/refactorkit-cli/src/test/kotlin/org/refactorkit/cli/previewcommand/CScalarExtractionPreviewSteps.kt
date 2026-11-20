package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CFunctionByteSpan
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CScalarExtractionPlanner
import org.refactorkit.c.ClangSemanticToolchain
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CScalarExtractionPreviewSteps {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var selection: CFunctionByteSpan
    private lateinit var privateBefore: Set<Path>
    private var condition = ""
    private var dialect = "c17"
    private var tempName = "quotient"
    private var plan: PatchPlan? = null

    @Given("a captured C scalar extraction request has condition {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-scalar-preview-"); root = workspace
        val unicode = case == "C11 subtree Unicode CRLF"; val inline = case == "inline void const parameters"
        dialect = if (unicode) "c11" else "c17"; val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];${newline}int seed = 7;$newline" + if (unicode) "/* é 😀 */$newline" else ""
        val expression = if (case == "initializer increments") "a++ / b" else "a / b"
        val initializer = if (unicode) "-($expression)" else expression
        if (inline) {
            val tail = "int main(void) { calculate(5, 2); return 0; }$newline"
            original = prefix + "void calculate(const int a, const int b) { double ratio = $initializer; return; }$newline" + tail
            expectedSource = prefix + "void calculate(const int a, const int b) { const int quotient = $expression; double ratio = quotient; return; }$newline" + tail
        } else {
            val tail = "int main(void) { return calculate(5, 2) ? 0 : 1; }$newline"
            original = prefix + "int calculate(int a, int b) {$newline    double ratio = $initializer;$newline" +
                "    return ratio == ${if (unicode) "-2.0" else "2.0"};$newline}$newline" + tail
            expectedSource = prefix + "int calculate(int a, int b) {$newline    const int quotient = $expression;$newline" +
                "    double ratio = ${if (unicode) "-(quotient)" else "quotient"};$newline" +
                "    return ratio == ${if (unicode) "-2.0" else "2.0"};$newline}$newline" + tail
        }
        Files.writeString(workspace.resolve("main.c"), original)
        val start = original.indexOf(expression)
        selection = CFunctionByteSpan(original.substring(0, start).toByteArray(Charsets.UTF_8).size,
            original.substring(0, start + expression.length).toByteArray(Charsets.UTF_8).size)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=$dialect", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database); snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        if (case == "temporary captures global") tempName = "seed"
        manager = ExternalSemanticProcessManager(); privateBefore = privateDirectories()
    }

    @When("the engine previews that selected scalar temporary")
    fun preview() {
        if (condition == "saved database changed") { database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database) }
        plan = CScalarExtractionPlanner(toolchain, requireNotNull(manager)).preview(snapshot, Path.of("main.c"), selection, tempName)
    }

    @Then("the scalar extraction preview reports {string}")
    fun report(expected: String) {
        val proposal = assertNotNull(plan); assertEquals("extractExpression", proposal.operation); assertEquals(snapshot.hash, proposal.snapshotHash)
        if (expected == "exact scalar preview") {
            assertEquals(PatchStatus.PREVIEW, proposal.status, proposal.summary)
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, proposal.evidence); assertTrue(proposal.requiresUserApproval)
            val lease = assertNotNull(proposal.authorityLease); assertEquals("c.scalar-extract-v1", lease.kind)
            assertEquals("calculate", lease.attributes["c.functionName"])
            val candidate = WorkspaceEditSimulator.apply(snapshot, proposal.workspaceEdit)
            assertEquals(expectedSource, candidate.files.single().content); assertEquals(setOf(Path.of("main.c")), proposal.affectedFiles)
            assertEquals(0, runtime(original)); assertEquals(0, runtime(candidate.files.single().content))
        } else {
            assertEquals(PatchStatus.REFUSED, proposal.status, proposal.summary)
            assertTrue(proposal.workspaceEdit.edits.isEmpty() && proposal.affectedFiles.isEmpty())
            assertEquals(when (expected) {
                "effects refused" -> "c.scalarFacts.unsupported_expression"
                "name collision refused" -> "c.scalarExtract.name_collision"
                "changed input refused" -> "c.scalarFacts.snapshotChanged"
                else -> error("Unexpected result: $expected")
            }, proposal.refusalCode)
        }
    }

    @Then("the scalar extraction preview preserves saved inputs and releases compiler material")
    fun intact() {
        assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty()); assertEquals(privateBefore, privateDirectories())
    }
    private fun runtime(source: String): Int {
        val directory = Files.createTempDirectory("refactorkit-scalar-runtime-")
        try {
            val input = directory.resolve("program.c"); val executable = directory.resolve("program"); Files.writeString(input, source)
            val compiler = ProcessBuilder(toolchain.clangExecutable.toString(), "-std=$dialect", "-DREQUIRED=17", input.toString(), "-o", executable.toString()).redirectErrorStream(true).start()
            if (!compiler.waitFor(30, TimeUnit.SECONDS)) { compiler.destroyForcibly(); error("Independent compiler timeout") }
            assertEquals(0, compiler.exitValue(), compiler.inputStream.bufferedReader().readText())
            val process = ProcessBuilder(executable.toString()).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); error("Independent fixture timeout") }
            return process.exitValue()
        } finally { Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() {
        manager?.close(); root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

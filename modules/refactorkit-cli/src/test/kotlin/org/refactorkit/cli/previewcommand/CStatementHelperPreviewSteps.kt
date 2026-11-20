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
import org.refactorkit.c.CStatementExtractionPlanner
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

class CStatementHelperPreviewSteps {
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
    private var helperName = "computeTotal"
    private var plan: PatchPlan? = null

    @Given("a captured C statement helper request has condition {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-helper-preview-")
        root = workspace
        val zero = case == "C11 zero inputs Unicode CRLF"
        dialect = if (zero) "c11" else "c17"
        val newline = if (zero) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];${newline}int seed = 7;$newline" + if (zero) "/* é 😀 */$newline" else ""
        val parameters = if (zero) "void" else "int a, int b"
        val declarations = if (zero) "int first = 5;$newline    int total = first * 2;" else
            "int sum = a + b;$newline    int doubled = ${if (case == "initializer increments local") "sum++" else "sum * 2"};"
        val result = if (zero) "total" else "doubled"
        val prototype = "static int calculate(${if (zero) "void" else "int left, int right"});$newline"
        val tail = "int main(void) { return calculate(${if (zero) "" else "7, 14"}) == ${if (zero) 10 else 42} ? 0 : 1; }$newline"
        original = prefix + prototype + "static int calculate($parameters) {$newline    $declarations$newline    return $result${if (case == "nonfinal live local") " + sum" else ""};$newline}$newline" + tail
        expectedSource = prefix + prototype + "static int computeTotal($parameters) {$newline    $declarations$newline    return $result;$newline}$newline$newline" +
            "static int calculate($parameters) {$newline    int $result = computeTotal(${if (zero) "" else "a, b"});$newline    return $result;$newline}$newline" + tail
        Files.writeString(workspace.resolve("main.c"), original)
        val start = original.indexOf(if (zero) "int first =" else "int sum =")
        val end = original.indexOf(';', original.indexOf(if (zero) "int total =" else "int doubled =")) + 1
        selection = CFunctionByteSpan(original.substring(0, start).toByteArray().size, original.substring(0, end).toByteArray().size)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=$dialect", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        if (case == "helper name captures global") helperName = "seed"
        manager = ExternalSemanticProcessManager()
        privateBefore = privateDirectories()
    }

    @When("the engine previews that explicit statement helper")
    fun preview() {
        if (condition == "saved database changed") { database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database) }
        plan = CStatementExtractionPlanner(toolchain, requireNotNull(manager)).preview(snapshot, Path.of("main.c"), "calculate", selection, helperName)
    }

    @Then("the statement helper preview reports {string}")
    fun report(expected: String) {
        val proposal = assertNotNull(plan)
        assertEquals("extractStatements", proposal.operation)
        assertEquals(snapshot.hash, proposal.snapshotHash)
        if (expected == "exact helper preview") {
            assertEquals(PatchStatus.PREVIEW, proposal.status, proposal.summary)
            assertEquals(RefactoringEvidence.COMPILER_PROVEN, proposal.evidence)
            assertTrue(proposal.requiresUserApproval)
            assertEquals("c.statement-extract-v1", assertNotNull(proposal.authorityLease).kind)
            val candidate = WorkspaceEditSimulator.apply(snapshot, proposal.workspaceEdit)
            assertEquals(expectedSource, candidate.files.single().content)
            assertEquals(setOf(Path.of("main.c")), proposal.affectedFiles)
            assertEquals(0, runtime(original))
            assertEquals(0, runtime(candidate.files.single().content))
        } else {
            assertEquals(PatchStatus.REFUSED, proposal.status, proposal.summary)
            assertTrue(proposal.workspaceEdit.edits.isEmpty() && proposal.affectedFiles.isEmpty())
            assertEquals(when (expected) {
                "multiple outputs refused" -> "c.statementFacts.nonfinal_live_output"
                "effects refused" -> "c.statementFacts.unsupported_expression"
                "name collision refused" -> "c.statementExtract.name_collision"
                "changed input refused" -> "c.statementFacts.snapshotChanged"
                else -> error("Unexpected result: $expected")
            }, proposal.refusalCode)
        }
    }

    @Then("the statement helper preview preserves saved inputs and releases compiler material")
    fun intact() {
        assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
        assertEquals(privateBefore, privateDirectories())
    }
    private fun runtime(source: String): Int {
        val directory = Files.createTempDirectory("refactorkit-helper-runtime-")
        try {
            val input = directory.resolve("program.c"); val executable = directory.resolve("program")
            Files.writeString(input, source)
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
        manager?.close()
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

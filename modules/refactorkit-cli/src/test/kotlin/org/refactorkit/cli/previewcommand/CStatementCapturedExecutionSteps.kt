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
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CStatementExtractionFactsResult
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CStatementCapturedExecutionSteps {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var candidate: ProjectSnapshot
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var original: String
    private lateinit var database: String
    private lateinit var privateBefore: Set<Path>
    private var condition = ""
    private var result: CStatementExtractionFactsResult? = null

    @Given("a captured C statement selection has condition {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-statement-facts-")
        root = workspace
        val unicode = case == "C11 Unicode CRLF selection"
        val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */$newline" else ""
        original = prefix + "static int calculate(int left, int right);$newline" +
            "static int calculate(int a, int b) {$newline    int sum = a + b;$newline" +
            "    int doubled = ${if (case == "initializer increments local") "sum++" else "sum * 2"};$newline    return doubled;$newline}$newline" +
            "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }$newline"
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), if (unicode) "-std=c11" else "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace)
        candidate = snapshot
        if (case == "source-only staged helper") {
            val helper = "static int computeTotal(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n\n"
            val after = prefix + "static int calculate(int left, int right);\n" + helper +
                "static int calculate(int a, int b) {\n    int doubled = computeTotal(a, b);\n    return doubled;\n}\n" +
                "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }\n"
            candidate = snapshot.copy(files = snapshot.files.map { it.copy(content = after) })
        }
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        manager = ExternalSemanticProcessManager()
        privateBefore = privateDirectories()
    }

    @When("the statement facts are evaluated in the admitted compiler context")
    fun evaluate() {
        if (condition == "saved database changed") {
            database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database)
        }
        if (condition == "candidate database changed") candidate = snapshot.copy(auxiliaryFiles = snapshot.auxiliaryFiles.map { it.copy(content = it.content + " ") })
        val source = candidate.files.single().content
        val start = source.substring(0, source.indexOf("int sum =")).toByteArray().size
        val endChar = source.indexOf(';', source.indexOf("int doubled =")) + 1
        val selection = CFunctionByteSpan(start, source.substring(0, endChar).toByteArray().size)
        val service = CFunctionFactsService(toolchain, requireNotNull(manager))
        result = if (condition in setOf("source-only staged helper", "candidate database changed")) service.analyzeStatementsPreview(snapshot, candidate,
            Path.of("main.c"), if (condition == "source-only staged helper") "computeTotal" else "calculate", selection)
        else service.analyzeStatements(snapshot, Path.of("main.c"), "calculate", selection)
    }

    @Then("the captured statement analysis reports {string}")
    fun report(expected: String) {
        if (expected == "available facts") {
            val available = assertIs<CStatementExtractionFactsResult.Available>(result, result.toString())
            assertEquals(candidate.hash, available.snapshotHash)
            val source = candidate.files.single().content.toByteArray()
            assertEquals(MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }, available.facts.sourceSha256)
            assertEquals(requireNotNull(root).resolve("main.c"), available.facts.sourcePath)
            assertEquals(listOf("a" to 0, "b" to 1), available.facts.inputs.map { it.name to it.parameterOrdinal })
            assertEquals(listOf("sum", "doubled"), available.facts.localNames)
            assertEquals("doubled", available.facts.outputName)
            assertEquals(listOf("int sum = a + b;", "int doubled = sum * 2;"), available.facts.statements.map { source.copyOfRange(it.start, it.endExclusive).toString(Charsets.UTF_8) })
            assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256, available.processProvenance.executableSha256)
        } else {
            val unavailable = assertIs<CStatementExtractionFactsResult.Unavailable>(result)
            assertEquals(when (expected) {
                "changed input" -> "c.statementFacts.snapshotChanged"
                "unavailable effects" -> "c.statementFacts.unsupported_expression"
                "invalid boundary" -> "c.statementFacts.previewBoundaryInvalid"
                else -> error("Unexpected result: $expected")
            }, unavailable.diagnostic.code)
        }
    }

    @Then("statement fact evaluation preserves saved bytes and releases compiler material")
    fun intact() {
        assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
        assertEquals(privateBefore, privateDirectories())
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

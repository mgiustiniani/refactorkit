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
import org.refactorkit.c.CScalarExpressionFactsResult
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

class CScalarCapturedExecutionSteps {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var candidate: ProjectSnapshot
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var original: String
    private lateinit var database: String
    private lateinit var privateBefore: Set<Path>
    private var condition = ""
    private var result: CScalarExpressionFactsResult? = null

    @Given("a captured C scalar selection has condition {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-scalar-facts-"); root = workspace
        val unicode = case == "C11 Unicode CRLF selection"; val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */$newline" else ""
        original = prefix + "static int calculate(int a, int b) {$newline" +
            "    double ratio = ${if (case == "initializer increments") "a++ / b" else "a / b"};$newline" +
            "    return ratio == 2.0;$newline}$newline" + "int main(void) { return calculate(5, 2) ? 0 : 1; }$newline"
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), if (unicode) "-std=c11" else "-std=c17",
                "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace); candidate = snapshot
        if (case == "source-only staged const") {
            val after = original.replace("    double ratio = a / b;", "    const int quotient = a / b;\n    double ratio = quotient;")
            candidate = snapshot.copy(files = snapshot.files.map { it.copy(content = after) })
        }
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        manager = ExternalSemanticProcessManager(); privateBefore = privateDirectories()
    }

    @When("the scalar facts are evaluated in the admitted compiler context")
    fun evaluate() {
        if (condition == "saved database changed") { database += " \n"; Files.writeString(requireNotNull(root).resolve("compile_commands.json"), database) }
        if (condition == "candidate database changed") candidate = snapshot.copy(auxiliaryFiles = snapshot.auxiliaryFiles.map { it.copy(content = it.content + " ") })
        val source = candidate.files.single().content
        val expression = if (condition == "initializer increments") "a++ / b" else "a / b"
        val startChar = source.indexOf(expression)
        val selection = CFunctionByteSpan(source.substring(0, startChar).toByteArray(Charsets.UTF_8).size,
            source.substring(0, startChar + expression.length).toByteArray(Charsets.UTF_8).size)
        val service = CFunctionFactsService(toolchain, requireNotNull(manager))
        result = if (condition in setOf("source-only staged const", "candidate database changed")) {
            service.analyzeScalarExpressionPreview(snapshot, candidate, Path.of("main.c"), "calculate", selection)
        } else service.analyzeScalarExpression(snapshot, Path.of("main.c"), "calculate", selection)
    }

    @Then("the captured scalar analysis reports {string}")
    fun report(expected: String) {
        if (expected == "available facts") {
            val available = assertIs<CScalarExpressionFactsResult.Available>(result, result.toString())
            assertEquals(candidate.hash, available.snapshotHash)
            val source = candidate.files.single().content.toByteArray(Charsets.UTF_8)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }, available.facts.sourceSha256)
            assertEquals(requireNotNull(root).resolve("main.c"), available.facts.sourcePath)
            assertEquals("int", available.facts.expressionType)
            assertEquals(if (condition == "source-only staged const") "const int" else "double", available.facts.assignmentTargetType)
            assertEquals(listOf("a", "b"), available.facts.inputs.map { it.name })
            fun fragment(span: CFunctionByteSpan) = source.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
            assertEquals("a / b", fragment(available.facts.selection))
            assertEquals(if (condition == "source-only staged const") "const int quotient = a / b;" else "double ratio = a / b;", fragment(available.facts.enclosingStatement))
            assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256, available.processProvenance.executableSha256)
        } else {
            val unavailable = assertIs<CScalarExpressionFactsResult.Unavailable>(result)
            assertEquals(when (expected) {
                "changed input" -> "c.scalarFacts.snapshotChanged"
                "unavailable effects" -> "c.scalarFacts.unsupported_expression"
                "invalid boundary" -> "c.scalarFacts.previewBoundaryInvalid"
                else -> error("Unexpected result: $expected")
            }, unavailable.diagnostic.code)
        }
    }

    @Then("scalar fact evaluation preserves saved bytes and releases compiler material")
    fun intact() {
        assertEquals(original, Files.readString(requireNotNull(root).resolve("main.c")))
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty()); assertEquals(privateBefore, privateDirectories())
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() {
        manager?.close(); root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

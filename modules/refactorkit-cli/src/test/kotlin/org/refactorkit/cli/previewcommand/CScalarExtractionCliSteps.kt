package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.TextEdits
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CScalarExtractionCliSteps {
    private var root: Path? = null
    private lateinit var command: List<String>
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private var exit = -1
    private var output = ""

    @Given("a captured C scalar CLI invocation has {string}")
    fun invocation(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-scalar-cli-"); root = workspace
        val unicode = case == "C11 Unicode CRLF preview"
        val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */$newline" else ""
        val expression = if (case == "initializer changes state") "a++ / b" else "a / b"
        val comment = if (unicode) "/* π 😀 */ " else ""
        val header = "int calculate(int a, int b) {$newline"
        val tail = "    return ratio == 2.0;${newline}}$newline" + "int main(void) { return calculate(5, 2) ? 0 : 1; }$newline"
        original = prefix + header + "    double ratio = $comment$expression;$newline" + tail
        expectedSource = prefix + header + "    const int quotient = $expression;$newline    double ratio = ${comment}quotient;$newline" + tail
        Files.writeString(workspace.resolve("main.c"), original)
        val start = original.indexOf(expression)
        val from = TextEdits.positionForOffset(original, start); val to = TextEdits.positionForOffset(original, start + expression.length)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), if (unicode) "-std=c11" else "-std=c17",
                "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        command = listOf("c", "extract", workspace.toString(), "--file", "main.c", "--temp-name", "quotient",
            "--clang", reference.resolve("usr/bin/clang-22").toString(), "--clangd", reference.resolve("usr/bin/clangd").toString(),
            "--clang-format", reference.resolve("usr/bin/clang-format").toString()) +
            (if (case == "selection omitted") emptyList() else listOf("--start-line", from.line.toString(), "--start-char", from.character.toString(),
                "--end-line", to.line.toString(), "--end-char", to.character.toString())) +
            (if (case == "mixed expression and helper") listOf("--helper-name", "helper") else emptyList()) +
            (if (case == "approved apply") listOf("--operation", "extract-expression", "--apply") else listOf("--preview"))
        beforeHash = CProjectScanner().scan(workspace).hash; privateBefore = privateDirectories()
    }

    @When("the scalar extraction CLI command is executed")
    fun execute() { val result = cli(command); exit = result.first; output = result.second }

    @Then("the scalar extraction CLI reports {string}")
    fun report(expected: String) {
        val workspace = requireNotNull(root)
        when (expected) {
            "preview without writes" -> {
                assertEquals(0, exit, output); val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content)
                assertEquals("extractExpression", result.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", result.getValue("evidence").jsonPrimitive.content)
                assertTrue(result.getValue("summary").jsonPrimitive.content.contains("native int expression"), result.toString())
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "applied and rolled back" -> {
                assertEquals(0, exit, output); val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("applied", result.getValue("status").jsonPrimitive.content)
                assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
                val transaction = result.getValue("transactionId").jsonPrimitive.content
                val undo = cli(listOf("patch", "rollback", transaction, "--root", workspace.toString()))
                assertEquals(0, undo.first, undo.second)
                assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
            }
            "missing range refused", "effects refused" -> {
                assertEquals(1, exit, output)
                assertTrue(output.contains(if (expected == "missing range refused") "c.invalidRange" else "c.scalarFacts.unsupported_expression"), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "mixed options refused" -> {
                assertEquals(2, exit, output); assertTrue(output.contains("cannot mix"), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            else -> error("Unexpected outcome: $expected")
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c"))); assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
    }

    @Then("the scalar extraction CLI preserves saved configuration and releases compiler material")
    fun intact() {
        assertEquals(database, Files.readString(requireNotNull(root).resolve("compile_commands.json")))
        assertEquals(privateBefore, privateDirectories())
    }
    private fun cli(args: List<String>): Pair<Int, String> {
        val previousOutput = System.out; val previousError = System.err
        val capturedOutput = ByteArrayOutputStream(); val capturedError = ByteArrayOutputStream()
        val code = try {
            System.setOut(PrintStream(capturedOutput, true, Charsets.UTF_8)); System.setErr(PrintStream(capturedError, true, Charsets.UTF_8))
            RefactorKitCli().run(args)
        } finally { System.setOut(previousOutput); System.setErr(previousError) }
        return code to (capturedOutput.toString(Charsets.UTF_8) + capturedError.toString(Charsets.UTF_8))
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }
    @After
    fun cleanup() { root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } } }
}

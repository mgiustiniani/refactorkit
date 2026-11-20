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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CStatementHelperCliSteps {
    private var root: Path? = null
    private lateinit var command: List<String>
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private var exit = -1
    private var output = ""

    @Given("a captured C statement helper CLI invocation has {string}")
    fun invocation(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-helper-cli-"); root = workspace
        val unicode = case == "C11 Unicode CRLF preview"
        val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */$newline" else ""
        val selected = "int sum = ${if (case == "initializer changes state") "a++ + b" else "a + b"};${newline}    int doubled = sum * 2;"
        original = prefix + "static int calculate(int a, int b) {$newline    $selected${newline}    return doubled;${newline}}$newline" +
            "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }$newline"
        expectedSource = prefix + "static int compute(int a, int b) {$newline    $selected${newline}    return doubled;${newline}}$newline$newline" +
            "static int calculate(int a, int b) {$newline    int doubled = compute(a, b);${newline}    return doubled;${newline}}$newline" +
            "int main(void) { return calculate(7, 14) == 42 ? 0 : 1; }$newline"
        Files.writeString(workspace.resolve("main.c"), original)
        val startChar = original.indexOf("int sum"); val endChar = original.indexOf(';', original.indexOf("int doubled")) + 1
        val start = original.substring(0, startChar).toByteArray(Charsets.UTF_8).size
        val end = original.substring(0, endChar).toByteArray(Charsets.UTF_8).size
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(), if (unicode) "-std=c11" else "-std=c17",
                "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        command = listOf("c", "extract", workspace.toString(), "--operation", "extract-statements", "--file", "main.c",
            "--symbol", "calculate", "--helper-name", "compute",
            "--clang", reference.resolve("usr/bin/clang-22").toString(), "--clangd", reference.resolve("usr/bin/clangd").toString(),
            "--clang-format", reference.resolve("usr/bin/clang-format").toString()) +
            (if (case == "selection omitted") emptyList() else listOf("--start-byte", start.toString(), "--end-byte", end.toString())) +
            (if (case == "mixed expression and helper") listOf("--temp-name", "temporary") else emptyList()) +
            (if (case == "approved apply") listOf("--apply") else listOf("--preview"))
        beforeHash = CProjectScanner().scan(workspace).hash; privateBefore = privateDirectories()
    }

    @When("the statement helper CLI command is executed")
    fun execute() { val result = cli(command); exit = result.first; output = result.second }

    @Then("the statement helper CLI reports {string}")
    fun report(expected: String) {
        val workspace = requireNotNull(root)
        when (expected) {
            "preview without writes" -> {
                assertEquals(0, exit, output); val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content)
                assertEquals("extractStatements", result.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", result.getValue("evidence").jsonPrimitive.content)
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
            "missing argument refused", "effects refused" -> {
                assertEquals(1, exit, output)
                assertTrue(output.contains(if (expected == "missing argument refused") "c.missingArgument" else "c.statementFacts.unsupported_expression"), output)
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

    @Then("the statement helper CLI preserves saved configuration and releases compiler material")
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

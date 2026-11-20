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

class CParameterReorderingCliSteps {
    private var root: Path? = null
    private lateinit var command: List<String>
    private lateinit var original: String
    private lateinit var expectedSource: String
    private lateinit var database: String
    private lateinit var beforeHash: String
    private lateinit var privateBefore: Set<Path>
    private var exit = -1
    private var output = ""
    private var case = ""

    @Given("a captured C parameter reorder CLI invocation has {string}")
    fun invocation(condition: String) {
        case = condition
        val workspace = Files.createTempDirectory("refactorkit-c-reorder-cli-")
        root = workspace
        val unicode = condition == "C11 Unicode CRLF preview"
        val oldStyle = condition in setOf("C11 old-style definition", "C17 old-style definition")
        val variadic = condition in setOf("C11 variadic family", "C17 variadic family")
        val newline = if (unicode) "\r\n" else "\n"
        val prefix = "int required[REQUIRED - 16];$newline" + if (unicode) "/* é 😀 */ " else ""
        original = if (oldStyle) {
            "static int weighted(left, right) int left; int right; { return left * 10 + right; }$newline" +
                "int main(void) { return weighted(1, 2); }$newline"
        } else if (variadic) {
            "static int weighted(int first, ...);$newline" +
                "static int weighted(int first, ...) { return first; }$newline" +
                "int main(void) { return weighted(9, 8, 7); }$newline"
        } else prefix + "static int weighted(int x, int y, int z);$newline" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }$newline" +
            "int main(void) { return weighted(9, 8, 7); }$newline"
        expectedSource = prefix + "static int weighted(int z, int y, int x);$newline" +
            "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }$newline" +
            "int main(void) { return weighted(7, 8, 9); }$newline"
        Files.writeString(workspace.resolve("main.c"), original)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(reference.resolve("usr/bin/clang-22").toString(),
                if (unicode || condition in setOf("C11 old-style definition", "C11 variadic family")) "-std=c11" else "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        val mapping = if (variadic) "0" else if (oldStyle) "1,0" else when (condition) { "duplicate original index" -> "2,2,0"; "nonnumeric original index" -> "2,no,0"; else -> "2,1,0" }
        command = listOf("c", "change-signature", workspace.toString(), "--operation", "reorder-parameters",
            "--file", "main.c", "--symbol", "weighted", "--order", mapping,
            "--clang", reference.resolve("usr/bin/clang-22").toString(),
            "--clangd", reference.resolve("usr/bin/clangd").toString(),
            "--clang-format", reference.resolve("usr/bin/clang-format").toString()) +
            if (condition == "approved apply") listOf("--apply") else if (condition == "mixed rename reorder inputs") listOf("--old-param", "a", "--new-param", "first") else listOf("--preview")
        beforeHash = CProjectScanner().scan(workspace).hash
        privateBefore = privateDirectories()
    }

    @When("the parameter reorder CLI command is executed")
    fun execute() { val result = cli(command); exit = result.first; output = result.second }

    @Then("the parameter reorder CLI reports {string}")
    fun report(expected: String) {
        val workspace = requireNotNull(root)
        when (expected) {
            "preview without writes" -> {
                assertEquals(0, exit, output)
                val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content)
                assertEquals("reorderParameters", result.getValue("operation").jsonPrimitive.content)
                assertEquals("COMPILER_PROVEN", result.getValue("evidence").jsonPrimitive.content)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "applied and rolled back" -> {
                assertEquals(0, exit, output)
                val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("applied", result.getValue("status").jsonPrimitive.content)
                assertEquals(expectedSource, Files.readString(workspace.resolve("main.c")))
                val transaction = result.getValue("transactionId").jsonPrimitive.content
                val undo = cli(listOf("patch", "rollback", transaction, "--root", workspace.toString()))
                assertEquals(0, undo.first, undo.second)
                assertTrue(Files.isDirectory(workspace.resolve(".refactorkit/transactions")))
            }
            "old-style family refused", "variadic family refused" -> {
                assertEquals(1, exit, output)
                assertTrue(output.contains("c.functionFacts.astUnavailable"), output)
                assertTrue(output.contains("UNSUPPORTED_SIGNATURE"), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "mapping refused" -> {
                assertEquals(1, exit, output)
                assertTrue(output.contains("c.signatureReorder.invalid_mapping"), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            "ambiguous options refused" -> {
                assertEquals(2, exit, output)
                assertTrue(output.contains("cannot mix"), output)
                assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
            }
            else -> error("Unexpected outcome: $expected")
        }
        assertEquals(original, Files.readString(workspace.resolve("main.c")))
        assertEquals(beforeHash, CProjectScanner().scan(workspace).hash)
    }

    @Then("the parameter reorder CLI preserves saved configuration and disposes private compiler material")
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

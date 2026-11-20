package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.en.And
import kotlinx.serialization.json.*
import org.refactorkit.c.CCompilationDatabaseDiscovery
import org.refactorkit.c.CCompilationDatabaseParser
import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CCompilationRecordFormatSteps {
    private val fixture = CompilationRecordFormatFixture()

    @Given("a raw compilation record fixture {string}")
    fun prepare(record: String) = fixture.prepare(record)

    @When("the public C compilation parser receives the exact captured bytes")
    fun parse() = fixture.parse()

    @Then("raw compilation discovery reports {string} without a coerced or partial record")
    fun verify(outcome: String) = fixture.verify(outcome)

    @And("raw compilation parsing preserves all input bytes without executing commands")
    fun preserved() = fixture.verifyConfinement()

    @After
    fun close() = fixture.close()
}

private class CompilationRecordFormatFixture : AutoCloseable {
    private var root: Path? = null
    private lateinit var input: ByteArray
    private lateinit var original: ByteArray
    private lateinit var expectedArguments: List<String>
    private var result: CCompilationDatabaseDiscovery? = null
    private var expectedOutput: String? = null
    private lateinit var expectedFile: Path
    private var expectedIncludes: List<Path> = emptyList()
    private var parseFailure: Throwable? = null
    private var baseline: Map<Path, ByteArray> = emptyMap()

    fun prepare(record: String) {
        val workspace = Files.createTempDirectory(Files.createDirectories(Path.of("build/test-tmp")), "raw-compilation-record-").toAbsolutePath()
        root = workspace
        expectedFile = workspace.resolve("main.c")
        for (name in listOf("first.c", "main.c")) Files.writeString(workspace.resolve(name), "int ${name.substringBefore('.')} (void) { return 0; }\n")
        val compiler = workspace.resolve("project-compiler")
        val marker = workspace.resolve("command-executed").toString()
        val quotedMarker = "'" + marker.replace("'", "'\\''") + "'"
        Files.writeString(compiler, "#!/bin/sh\nprintf executed > $quotedMarker\n")
        assertTrue(compiler.toFile().setExecutable(true, true))
        fun arguments(file: String) = listOf(compiler.toString(), "-std=c17", "-DNAME=café", "-c", file)
        expectedArguments = arguments("main.c")
        fun unit(file: String) = buildJsonObject {
            put("file", file)
            put("directory", workspace.toString())
            put("arguments", JsonArray(arguments(file).map(::JsonPrimitive)))
            put("note", "uninterpreted-marker")
        }
        val second = unit("main.c").toMutableMap()
        val values = expectedArguments.map(::JsonPrimitive).toMutableList<JsonElement>()
        when (record) {
            "valid Unicode arguments", "malformed UTF8 in ignored note" -> Unit
            "number in arguments" -> { values.add(2, JsonPrimitive(17)); second["arguments"] = JsonArray(values) }
            "object in arguments" -> { values.add(2, buildJsonObject { put("value", "ignored") }); second["arguments"] = JsonArray(values) }
            "null in arguments" -> { values.add(2, JsonNull); second["arguments"] = JsonArray(values) }
            "nonarray arguments with command" -> {
                second["arguments"] = JsonPrimitive("invalid-vector")
                second["command"] = JsonPrimitive(expectedArguments.joinToString(" "))
            }
            "valid string fields" -> {
                second.remove("arguments")
                second["command"] = JsonPrimitive(expectedArguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" })
                expectedOutput = "out/café.o"
                second["output"] = JsonPrimitive(expectedOutput)
            }
            "numeric file" -> second["file"] = JsonPrimitive(17)
            "boolean directory" -> second["directory"] = JsonPrimitive(true)
            "boolean command" -> { second.remove("arguments"); second["command"] = JsonPrimitive(true) }
            "numeric output" -> second["output"] = JsonPrimitive(17)
            "object output" -> second["output"] = buildJsonObject { put("path", "out/main.o") }
            "space-prefixed source filename" -> {
                expectedFile = workspace.resolve(" main.c")
                Files.writeString(expectedFile, "int main(void) { return 0; }\n")
                second["file"] = JsonPrimitive(" main.c")
                expectedArguments = arguments(" main.c")
                second["arguments"] = JsonArray(expectedArguments.map(::JsonPrimitive))
            }
            "space-suffixed include directory" -> {
                val include = Files.createDirectory(workspace.resolve("include "))
                expectedIncludes = listOf(include)
                expectedArguments = expectedArguments + listOf("-I", "include ")
                second["arguments"] = JsonArray(expectedArguments.map(::JsonPrimitive))
            }
            "NUL in source filename" -> second["file"] = JsonPrimitive("invalid\u0000.c")
            "NUL in working directory" -> second["directory"] = JsonPrimitive("invalid\u0000directory")
            "NUL in include operand" -> second["arguments"] = JsonArray((expectedArguments + listOf("-I", "invalid\u0000include")).map(::JsonPrimitive))
            "NUL in sysroot operand" -> second["arguments"] = JsonArray((expectedArguments + "--sysroot=invalid\u0000sysroot").map(::JsonPrimitive))
            else -> error("Unknown raw compilation fixture: $record")
        }
        val text = JsonArray(listOf(unit("first.c"), JsonObject(second))).toString()
        input = text.toByteArray(Charsets.UTF_8)
        if (record == "malformed UTF8 in ignored note") {
            val offset = text.substring(0, text.lastIndexOf("uninterpreted-marker")).toByteArray(Charsets.UTF_8).size
            input[offset] = 0xff.toByte()
        }
        original = input.copyOf()
        Files.write(workspace.resolve("compile_commands.json"), input)
        baseline = listOf("first.c", "main.c", "project-compiler", "compile_commands.json", expectedFile.fileName.toString()).distinct()
            .associate { Path.of(it) to Files.readAllBytes(workspace.resolve(it)) }
    }

    fun parse() {
        result = null
        parseFailure = null
        runCatching { CCompilationDatabaseParser().parse(requireNotNull(root), input) }
            .onSuccess { result = it }.onFailure { parseFailure = it }
    }

    fun verify(outcome: String) {
        assertNull(parseFailure, "Invalid record data must yield typed refusal rather than escape as an exception")
        if (outcome == "available") {
            val database = assertIs<CCompilationDatabaseDiscovery.Available>(result).database
            assertEquals(2, database.units.size)
            val selected = assertNotNull(database.units.singleOrNull { it.file == expectedFile },
                "Available database must preserve the exact captured filename")
            assertEquals(root, selected.directory)
            assertEquals(expectedArguments, selected.arguments)
            assertEquals("c17", selected.standard)
            assertEquals(listOf("NAME=café"), selected.defines)
            assertEquals(expectedOutput, selected.output)
            assertEquals(expectedIncludes, selected.includeDirectories)
        } else {
            val refusal = assertIs<CCompilationDatabaseDiscovery.Refused>(result, "Invalid second record must not become a repaired or partial available database")
            assertTrue(refusal.diagnostics.isNotEmpty())
            assertTrue(refusal.diagnostics.all { it.code == outcome && it.severity == Diagnostic.Severity.ERROR })
        }
    }

    fun verifyConfinement() {
        assertContentEquals(original, input)
        baseline.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root!!.resolve(path))) }
        assertFalse(Files.exists(root!!.resolve("command-executed")))
        assertFalse(Files.exists(root!!.resolve("main.o")))
        assertFalse(Files.exists(root!!.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

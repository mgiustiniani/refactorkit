package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.cli.RefactorKitCli
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CGoldenNativeAuthoritySteps {
    private val fixture = CGoldenNativeAuthorityFixture()

    @Given("a native golden qualification request has {string}")
    fun capture(case: String) = fixture.capture(case)

    @When("the public golden command runs with its explicit fixture selectors")
    fun execute() = fixture.execute()

    @Then("the native golden command reports {string}")
    fun outcome(expected: String) = fixture.assertOutcome(expected)

    @Then("golden qualification preserves source fixtures and selected compiler inputs")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

/** Authored before/after/request images exercise the real public golden command. */
private class CGoldenNativeAuthorityFixture : AutoCloseable {
    private var root: Path? = null
    private lateinit var tools: Path
    private lateinit var fixtureRoot: Path
    private lateinit var originalFiles: Map<Path, String>
    private lateinit var toolInputs: Map<Path, String>
    private lateinit var privateBefore: Set<Path>
    private lateinit var selectedCase: String
    private var exit = -1
    private var output = ""

    fun capture(case: String) {
        require(case in setOf("C11 prefix", "C17 prefix", "missing toolchain", "missing compiler context",
            "invalid C compiler input", "Java control", "repository C11 prefix", "repository C17 prefix",
            "repository extract expression", "repository inline function", "repository organize includes",
            "repository relocate component", "repository symbol rename", "repository formatting",
            "repository parameter rename", "repository parameter insertion", "repository parameter reorder",
            "repository inline local", "repository safe delete", "repository statement extraction"))
        selectedCase = case
        val workspace = Files.createTempDirectory("refactorkit-native-golden-story-").also { root = it }
        tools = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        if (case.startsWith("repository ")) {
            val repository = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
                .first { Files.isRegularFile(it.resolve("AGENTS.md")) && Files.isRegularFile(it.resolve("settings.gradle.kts")) }
            val standard = if (case == "repository C11 prefix") "c11" else "c17"
            val name = when (case) {
                "repository extract expression" -> "c-extract"
                "repository inline function" -> "c-inline"
                "repository organize includes" -> "c-organize-includes"
                "repository relocate component" -> "c-relocate"
                "repository symbol rename" -> "c-rename-symbol"
                "repository formatting" -> "c-format-file"
                "repository parameter rename" -> "c-change-signature-rename-parameter"
                "repository parameter insertion" -> "c-change-signature-add-parameter"
                "repository parameter reorder" -> "c-change-signature-reorder-parameters"
                "repository inline local" -> "c-inline-local"
                "repository safe delete" -> "c-safe-delete"
                "repository statement extraction" -> "c-extract-statements"
                else -> "c-rename-prefix-$standard"
            }
            fixtureRoot = repository.resolve("testdata/golden/$name")
            oracle(standard, invalid = false)
            captureInputs()
            return
        }
        fixtureRoot = workspace.resolve("goldens/case")
        Files.createDirectories(fixtureRoot.resolve("before")); Files.createDirectories(fixtureRoot.resolve("after"))
        val standard = if (case == "C11 prefix") "c11" else "c17"
        if (case == "Java control") {
            write("before/src/main/java/sample/Old.java", "package sample;\n\npublic class Old {\n}\n")
            write("after/src/main/java/sample/New.java", "package sample;\n\npublic class New {\n}\n")
            write("request.json", """{"operation":"renameClass","symbol":"sample.Old","arguments":{"newName":"New"}}""")
            write("expected-plan.json", """{"operation":"renameClass","status":"preview"}""")
        } else if (case in setOf("missing toolchain", "missing compiler context")) {
            write("before/main.c", "#include <stdlib.h>\n#include <stdio.h>\nint main(void) { return 0; }\n")
            write("after/main.c", "#include <stdio.h>\n#include <stdlib.h>\nint main(void) { return 0; }\n")
            write("request.json", """{"operation":"organizeIncludes","arguments":{"file":"main.c"}}""")
            write("expected-plan.json", """{"operation":"organizeIncludes","status":"preview"}""")
        } else {
            val main = if (case == "invalid C compiler input") "return missing_value;" else
                "return calculate(21) + load_item(1) == 44 ? 0 : 1;"
            val afterMain = if (case == "invalid C compiler input") "return missing_value;" else
                "return compute(21) + read_item(1) == 44 ? 0 : 1;"
            write("before/main.c", "int calculate(int value) { return value * 2; }\n" +
                "int load_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
                "/* calculate load_item stay in comments */\nint main(void) { $main }\n")
            write("after/main.c", "int compute(int value) { return value * 2; }\n" +
                "int read_item(int value) { return value + 1; }\nint calculate_suffix(void) { return 9; }\n" +
                "/* calculate load_item stay in comments */\nint main(void) { $afterMain }\n")
            val command = JsonArray(listOf(buildJsonObject {
                put("directory", "__GOLDEN_WORKSPACE__"); put("file", "main.c")
                put("arguments", JsonArray(listOf("__GOLDEN_CLANG__", "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
            })).toString()
            write("before/compile_commands.json", command); write("after/compile_commands.json", command)
            write("request.json", """{"operation":"renamePrefix","arguments":{"mapping":"calculate=compute,load_item=read_item"}}""")
            write("expected-plan.json", """{"operation":"renameCSymbolPrefix","status":"preview"}""")
            oracle(standard, case == "invalid C compiler input")
        }
        captureInputs()
    }

    private fun captureInputs() {
        originalFiles = fixtureFiles()
        toolInputs = listOf("usr/bin/clang-22", "usr/bin/clangd", "usr/bin/clang-format")
            .associate { tools.resolve(it) to hash(tools.resolve(it)) }
        privateBefore = privateDirectories()
    }

    private fun oracle(standard: String, invalid: Boolean) {
        for (state in listOf("before", "after")) {
            val sources = Files.walk(fixtureRoot.resolve(state)).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".c") }.sorted().toList()
            }
            assertTrue(sources.isNotEmpty(), "No authored C translation units in $state")
            for ((index, source) in sources.withIndex()) {
                val log = requireNotNull(root).resolve("oracle-$state-$index.log")
                val process = ProcessBuilder(tools.resolve("usr/bin/clang-22").toString(), "--no-default-config",
                    "-std=$standard", "-fsyntax-only", source.toString())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start()
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.descendants().forEach { it.destroyForcibly() }; process.destroyForcibly()
                    error("Selected compiler oracle timed out")
                }
                assertEquals(if (invalid) 1 else 0, process.exitValue(), Files.readString(log))
                if (invalid) assertTrue(Files.readString(log).contains("missing_value"))
            }
        }
    }

    fun execute() = synchronized(RefactorKitCli::class.java) {
        val arguments = listOf("test-golden", fixtureRoot.fileName.toString(), "--golden-dir", fixtureRoot.parent.toString()) +
            if (selectedCase == "missing toolchain") emptyList() else listOf(
                "--clang", tools.resolve("usr/bin/clang-22").toString(),
                "--clangd", tools.resolve("usr/bin/clangd").toString(),
                "--clang-format", tools.resolve("usr/bin/clang-format").toString())
        val oldOut = System.out; val oldErr = System.err
        val stdout = ByteArrayOutputStream(); val stderr = ByteArrayOutputStream()
        PrintStream(stdout, true, Charsets.UTF_8).use { out -> PrintStream(stderr, true, Charsets.UTF_8).use { err ->
            try { System.setOut(out); System.setErr(err); exit = RefactorKitCli().run(arguments) }
            finally { System.setOut(oldOut); System.setErr(oldErr) }
        } }
        output = stdout.toString(Charsets.UTF_8) + stderr.toString(Charsets.UTF_8)
    }

    fun assertOutcome(expected: String) {
        if (expected in setOf("native C pass", "Java pass")) {
            assertEquals(0, exit, output)
            assertTrue(output.contains("1 case(s): 1 passed, 0 failed"), output)
            if (expected == "native C pass") {
                assertTrue(output.contains("C native compiler 22.1.8") && output.contains("rollback verified"), output)
            } else assertFalse(output.contains("C native compiler"), output)
        } else {
            assertEquals(1, exit, output)
            assertTrue(output.contains(expected), output)
            assertFalse(output.contains("rollback verified"), output)
        }
    }

    fun assertIntact() {
        assertEquals(originalFiles, fixtureFiles())
        assertEquals(toolInputs, toolInputs.keys.associateWith(::hash))
        assertEquals(privateBefore, privateDirectories())
    }

    private fun fixtureFiles(): Map<Path, String> = Files.walk(fixtureRoot).use { paths ->
        paths.filter(Files::isRegularFile).sorted().toList().associate { fixtureRoot.relativize(it) to hash(it) }
    }
    private fun hash(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
    private fun write(name: String, content: String) {
        val path = fixtureRoot.resolve(name); Files.createDirectories(path.parent); Files.writeString(path, content)
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") || it.fileName.toString().startsWith("golden-case-") }
            .toList().toSet()
    }
    override fun close() { root?.let { workspace -> Files.walk(workspace).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    } } }
}

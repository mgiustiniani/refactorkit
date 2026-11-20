package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertTrue

class CSafeDeleteCliSteps {
    private var workspace: Path? = null
    private lateinit var source: String
    private lateinit var database: String
    private lateinit var snapshotHash: String
    private lateinit var privateDirectoriesBefore: Set<Path>
    private lateinit var command: List<String>
    private var exitCode = -1
    private var output = ""
    private var applyExitCode = -1
    private var applyOutput = ""
    private var applyError = ""
    private var previewWorkspaceHash = ""
    private var previewSnapshotHash = ""
    private var sourceAfterApply: String? = null
    private var snapshotAfterApply: String? = null
    private var rollbackExitCode = -1
    private var rollbackOutput = ""
    private var rollbackError = ""
    private var transactionId: String? = null

    @Given("a C CLI safe-delete workspace with {string}")
    fun workspaceWith(case: String) {
        require(case in setOf("isolated unused private function", "referenced private function", "unused file-static object"))
        val root = Files.createTempDirectory("refactorkit-c-safe-delete-cli-")
        workspace = root
        source = when (case) {
            "referenced private function" ->
                "static int unused(void) { return 3; }\nint main(void) { return unused(); }\n"
            "unused file-static object" ->
                "static int unused = 3;\nint main(void) { return 0; }\n"
            else ->
                "static int unused(void) { return 3; }\nint main(void) { return 0; }\n"
        }
        Files.writeString(root.resolve("main.c"), source)
        val toolchainRoot = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val clang = toolchainRoot.resolve("usr/bin/clang-22")
        val unit = buildJsonObject {
            put("directory", root.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(clang.toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        }
        database = JsonArray(listOf(unit)).toString()
        Files.writeString(root.resolve("compile_commands.json"), database)
        snapshotHash = CProjectScanner().scan(root).hash
        privateDirectoriesBefore = privateDirectories()
        command = listOf(
            "c", "safe-delete", root.toString(), "--symbol", "unused",
            "--clang", clang.toString(),
            "--clangd", toolchainRoot.resolve("usr/bin/clangd").toString(),
            "--clang-format", toolchainRoot.resolve("usr/bin/clang-format").toString(),
            "--preview",
        )
    }

    @When("the C safe-delete CLI command is previewed")
    fun preview() {
        val result = captureCli(command)
        exitCode = result.exitCode
        output = result.stdout + result.stderr
    }

    @When("the C safe-delete CLI command is applied")
    fun apply() {
        val root = requireNotNull(workspace)
        val preview = captureCli(command)
        exitCode = preview.exitCode
        output = preview.stdout + preview.stderr
        previewWorkspaceHash = CProjectScanner().scan(root).hash
        previewSnapshotHash = runCatching {
            Json.parseToJsonElement(preview.stdout).jsonObject
                .getValue("snapshot").jsonObject.getValue("hash").jsonPrimitive.content
        }.getOrDefault("")

        val apply = captureCli(command.filterNot { it == "--preview" } + "--apply")
        applyExitCode = apply.exitCode
        applyOutput = apply.stdout
        applyError = apply.stderr
        if (applyExitCode != 0) return

        val response = runCatching { Json.parseToJsonElement(applyOutput).jsonObject }.getOrNull()
        transactionId = response?.get("transactionId")?.jsonPrimitive?.content
        sourceAfterApply = Files.readString(root.resolve("main.c"))
        snapshotAfterApply = CProjectScanner().scan(root).hash
    }

    @Then("the C safe-delete CLI reports the {string} outcome")
    fun expectedOutcome(expected: String) {
        when (expected) {
            "preview" -> {
                val result = Json.parseToJsonElement(output).jsonObject
                assertEquals("PREVIEW", result.getValue("status").jsonPrimitive.content, output)
                assertEquals("safeDelete", result.getValue("operation").jsonPrimitive.content, output)
                assertEquals(0, exitCode, output)
                assertEquals("COMPILER_PROVEN", result.getValue("evidence").jsonPrimitive.content, output)
                val affected = result.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }
                assertEquals(listOf("main.c"), affected)
                val diff = result.getValue("renderedDiff").jsonPrimitive.content
                assertTrue(diff.contains("-static int unused(void) { return 3; }"), diff)
            }
            "refusal" -> {
                assertEquals(1, exitCode, output)
                assertTrue(output.contains("C command refused"), output)
                assertTrue(output.contains("semantic reference"), output)
            }
            else -> error("Unexpected safe-delete CLI expectation: $expected")
        }
    }

    @Then("the C safe-delete CLI reports an applied compiler-proven deletion transaction")
    fun appliedOutcome() {
        val root = requireNotNull(workspace)
        assertEquals(0, exitCode, output)
        val preview = Json.parseToJsonElement(output).jsonObject
        assertEquals("PREVIEW", preview.getValue("status").jsonPrimitive.content, output)
        assertEquals("COMPILER_PROVEN", preview.getValue("evidence").jsonPrimitive.content, output)
        val renderedDiff = preview.getValue("renderedDiff").jsonPrimitive.content
        assertTrue(renderedDiff.contains("-${source.lineSequence().first()}"), renderedDiff)
        assertEquals(snapshotHash, previewWorkspaceHash)
        assertTrue(previewSnapshotHash.matches(Regex("[a-f0-9]{64}")), output)

        assertEquals(0, applyExitCode, applyError.ifBlank { applyOutput })
        val applied = Json.parseToJsonElement(applyOutput).jsonObject
        assertEquals("applied", applied.getValue("status").jsonPrimitive.content, applyOutput)
        val id = applied.getValue("transactionId").jsonPrimitive.content
        assertTrue(id.isNotBlank(), applyOutput)
        assertEquals(id, transactionId)
        val after = requireNotNull(sourceAfterApply)
        assertFalse(after.contains("unused"), after)
        assertTrue(after.contains("int main(void)"), after)
        assertEquals(database, Files.readString(root.resolve("compile_commands.json")))
        val afterHash = requireNotNull(snapshotAfterApply)
        assertTrue(afterHash != snapshotHash, "managed Apply must change the captured source snapshot")
        val appliedSnapshotHash = applied.getValue("snapshotHash").jsonPrimitive.content
        assertTrue(appliedSnapshotHash.matches(Regex("[a-f0-9]{64}")), applyOutput)
        assertTrue(appliedSnapshotHash != previewSnapshotHash, "Apply must report a changed daemon workspace snapshot")
    }

    @And("rolling back the C safe-delete transaction restores the workspace exactly")
    fun rollbackRestoresWorkspace() {
        val root = requireNotNull(workspace)
        val id = requireNotNull(transactionId)
        val rollback = captureCli(listOf("patch", "rollback", id, "--root", root.toString()))
        rollbackExitCode = rollback.exitCode
        rollbackOutput = rollback.stdout
        rollbackError = rollback.stderr
        assertEquals(0, rollbackExitCode, rollbackError.ifBlank { rollbackOutput })
        assertTrue(rollbackOutput.contains("Rolled back transaction $id"), rollbackOutput)
        assertEquals(source, Files.readString(root.resolve("main.c")))
        assertEquals(database, Files.readString(root.resolve("compile_commands.json")))
        assertEquals(snapshotHash, CProjectScanner().scan(root).hash)
        assertEquals(privateDirectoriesBefore, privateDirectories())
        assertTrue(Files.isDirectory(root.resolve(".refactorkit/transactions")))
    }

    @Then("the C CLI safe-delete leaves its workspace and transaction journal unchanged")
    fun remainsReadOnly() {
        val root = requireNotNull(workspace)
        assertEquals(source, Files.readString(root.resolve("main.c")))
        assertEquals(database, Files.readString(root.resolve("compile_commands.json")))
        assertEquals(snapshotHash, CProjectScanner().scan(root).hash)
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertEquals(privateDirectoriesBefore, privateDirectories())
    }

    @After
    fun cleanup() {
        workspace?.let { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { path ->
            path.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
                path.fileName.toString().startsWith("refactorkit-semantic-overlay-")
        }.toList().toSet()
    }

    private fun captureCli(arguments: List<String>): CliCapture = synchronized(CAPTURE_MONITOR) {
        val previousOut = System.out
        val previousErr = System.err
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val out = PrintStream(stdout, true, Charsets.UTF_8)
        val err = PrintStream(stderr, true, Charsets.UTF_8)
        try {
            System.setOut(out)
            System.setErr(err)
            val code = RefactorKitCli().run(arguments)
            CliCapture(code, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            out.close()
            err.close()
        }
    }

    private data class CliCapture(val exitCode: Int, val stdout: String, val stderr: String)

    private companion object {
        val CAPTURE_MONITOR = Any()
    }
}

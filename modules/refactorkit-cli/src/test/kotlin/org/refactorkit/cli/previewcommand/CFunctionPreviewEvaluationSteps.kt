package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CFunctionPreviewEvaluationSteps {
    private var root: Path? = null
    private lateinit var before: ProjectSnapshot
    private lateinit var candidate: ProjectSnapshot
    private lateinit var candidateText: String
    private lateinit var saved: Map<Path, String>
    private lateinit var privateBefore: Set<Path>
    private var result: CFunctionFactsResult? = null
    private var managerEmpty = false

    @Given("a captured function preview contains {string}")
    fun preview(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-function-preview-")
        root = workspace
        val prefix = "int required[REQUIRED - 16];\n"
        val oldText = prefix + "static int weighted(int x, int y, int z);\n" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(9, 8, 7); }\n"
        candidateText = prefix + "static int weighted(int z, int y, int x);\n" +
            "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { return weighted(7, 8, 9); }\n"
        Files.writeString(workspace.resolve("main.c"), oldText)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val arguments = listOf(reference.resolve("usr/bin/clang-22").toString(),
            if (case == "reordered C11 source") "-std=c11" else "-std=c17", "-DREQUIRED=17", "-c", "main.c", "-o", "never-write.o")
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        val database = JsonArray(listOf(unit)).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        before = CProjectScanner().scan(workspace)
        if (case == "invalid candidate syntax") candidateText += "{"
        candidate = before.copy(files = listOf(before.files.single().copy(content = candidateText)))
        if (case == "candidate database changed") candidate = candidate.copy(auxiliaryFiles = before.auxiliaryFiles.map { it.copy(content = it.content.replace("-DREQUIRED=17", "-DREQUIRED=18")) })
        if (case == "saved database changed") Files.writeString(workspace.resolve("compile_commands.json"), database.replace("-DREQUIRED=17", "-DREQUIRED=18"))
        if (case == "candidate adds another source") candidate = candidate.copy(files = candidate.files + SourceFile(Path.of("extra.c"), "int extra(void) { return 1; }\n", "c"))
        saved = savedFiles(workspace)
        privateBefore = privateDirectories()
    }

    @When("its staged source is evaluated with the captured compiler context")
    fun evaluate() {
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(requireNotNull(root),
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(discovered).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        ExternalSemanticProcessManager().use { manager ->
            result = CFunctionFactsService(toolchain, manager).analyzePreview(before, candidate, Path.of("main.c"), "weighted")
            managerEmpty = manager.activeProvenance().isEmpty()
        }
    }

    @Then("staged function facts have disposition {string}")
    fun disposition(expected: String) {
        if (expected == "staged facts available") {
            val available = assertIs<CFunctionFactsResult.Available>(result, result.toString())
            assertEquals(candidate.hash, available.snapshotHash)
            assertTrue(candidate.hash != before.hash)
            val hash = MessageDigest.getInstance("SHA-256").digest(candidateText.toByteArray()).joinToString("") { "%02x".format(it) }
            assertEquals(hash, available.facts.sourceSha256)
            assertEquals(listOf(listOf("z", "y", "x"), listOf("c", "b", "a")), available.facts.declarations.map { it.parameters.map { parameter -> parameter.name } })
            assertEquals(listOf("7", "8", "9"), available.facts.calls.single().arguments.map { argument ->
                candidateText.toByteArray().copyOfRange(argument.span.start, argument.span.endExclusive).toString(Charsets.UTF_8)
            })
            assertFalse(available.processProvenance.workingDirectory.startsWith(requireNotNull(root)))
            assertFalse(Files.exists(available.processProvenance.workingDirectory))
            assertFalse(ProcessHandle.of(available.processProvenance.pid).map { it.isAlive }.orElse(false))
        } else {
            val unavailable = assertIs<CFunctionFactsResult.Unavailable>(result)
            assertEquals(when (expected) {
                "boundary refused" -> "c.functionFacts.previewBoundaryInvalid"
                "stale authority refused" -> "c.functionFacts.snapshotChanged"
                "compiler unavailable" -> "c.functionFacts.compilerUnavailable"
                else -> error("Unexpected disposition: $expected")
            }, unavailable.diagnostic.code)
        }
    }

    @Then("staged function evaluation leaves saved bytes intact and owns no private process")
    fun intact() {
        assertEquals(saved, savedFiles(requireNotNull(root)))
        assertFalse(Files.exists(requireNotNull(root).resolve("never-write.o")))
        assertTrue(managerEmpty)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun savedFiles(workspace: Path) = Files.walk(workspace).use { paths -> paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) } }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") || it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    @After
    fun cleanup() {
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

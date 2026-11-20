package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CArgumentEffectEvidence
import org.refactorkit.c.CDirectCallFamilyEvidence
import org.refactorkit.c.CFunctionByteSpan
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CFunctionCapturedExecutionSteps {
    private var root: Path? = null
    private lateinit var source: String
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var originals: Map<Path, String>
    private lateinit var privateBefore: Set<Path>
    private var result: CFunctionFactsResult? = null
    private var managerEmpty = false

    @Given("a captured C function project contains {string}")
    fun capturedProject(case: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-function-project-")
        root = workspace
        val firstArgument = if (case == "postincrement argument") "a++" else "a"
        source = "static int weighted(int x, int y, int z);\n" +
            "static int weighted(int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { int a = 9; return weighted($firstArgument, 8, 7) + weighted(1, 2, 3); }\n" +
            "static int unrelated(void) { int weighted = 4; return weighted; }\n"
        if (case == "address of the function") source += "int (*pointer)(int, int, int) = weighted;\n"
        if (case == "included function declaration") {
            Files.writeString(workspace.resolve("api.h"), "static int weighted(int x, int y, int z);\n")
            source = "#include \"api.h\"\n" + source.substringAfter('\n')
        }
        Files.writeString(workspace.resolve("main.c"), source)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val flags = if (case == "compiler plugin flag") listOf("-fplugin=never-load.so") else emptyList()
        val arguments = listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17") + flags + listOf("-c", "main.c", "-o", "never-emit.o")
        val unit = buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())
        snapshot = CProjectScanner().scan(workspace)
        if (case == "database changed after scan") {
            Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(buildJsonObject {
                put("directory", workspace.toString()); put("file", "main.c")
                put("arguments", JsonArray((arguments + "-DRECONFIGURED=1").map(::JsonPrimitive)))
            })).toString())
        }
        originals = projectFiles(workspace)
        privateBefore = privateDirectories()
    }

    @When("its configured compiler analyzes the captured function family")
    fun analyze() {
        val workspace = requireNotNull(root)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format")))
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(discovered).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        ExternalSemanticProcessManager().use { manager ->
            result = CFunctionFactsService(toolchain, manager).analyze(snapshot, Path.of("main.c"), "weighted")
            managerEmpty = manager.activeProvenance().isEmpty()
        }
    }

    @Then("compiler function evidence has disposition {string}")
    fun disposition(expected: String) {
        when (expected) {
            "complete direct", "address taken", "observable argument" -> {
                val available = assertIs<CFunctionFactsResult.Available>(result, result.toString())
                assertEquals(snapshot.hash, available.snapshotHash)
                assertTrue(snapshot.auxiliaryFiles.any { it.path == Path.of("compile_commands.json") })
                val facts = available.facts
                assertEquals(requireNotNull(root).resolve("main.c"), facts.sourcePath)
                assertEquals(listOf(false, true), facts.declarations.map { it.isDefinition })
                assertEquals(listOf(listOf("int x", "int y", "int z"), listOf("int a", "int b", "int c")),
                    facts.declarations.map { it.parameters.map { parameter -> slice(parameter.span) } })
                assertEquals(listOf(listOf(if (expected == "observable argument") "a++" else "a", "8", "7"), listOf("1", "2", "3")),
                    facts.calls.map { it.arguments.map { argument -> slice(argument.span) } })
                assertEquals(if (expected == "address taken") CDirectCallFamilyEvidence.ADDRESS_TAKEN else CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC, facts.family)
                assertEquals(if (expected == "observable argument") CArgumentEffectEvidence.OBSERVABLE_EFFECT else CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT,
                    facts.calls.first().arguments.first().effect)
                assertFalse(available.processProvenance.workingDirectory.startsWith(requireNotNull(root)))
                assertFalse(Files.exists(available.processProvenance.workingDirectory))
                assertFalse(ProcessHandle.of(available.processProvenance.pid).map { it.isAlive }.orElse(false))
            }
            "stale input refused", "configuration refused", "source refused" -> {
                val refused = assertIs<CFunctionFactsResult.Unavailable>(result)
                assertEquals(when (expected) {
                    "stale input refused" -> "c.functionFacts.snapshotChanged"
                    "configuration refused" -> "c.functionFacts.configurationRefused"
                    else -> "c.functionFacts.sourceUnsupported"
                }, refused.diagnostic.code)
            }
            else -> error("Unexpected disposition: $expected")
        }
    }

    @Then("captured function analysis preserves workspace bytes and reaps private processes")
    fun preserved() {
        assertEquals(originals, projectFiles(requireNotNull(root)))
        assertFalse(Files.exists(requireNotNull(root).resolve("never-emit.o")))
        assertTrue(managerEmpty)
        assertEquals(privateBefore, privateDirectories())
    }

    private fun slice(span: CFunctionByteSpan) = source.toByteArray().copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    private fun projectFiles(workspace: Path) = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }
    private fun privateDirectories(): Set<Path> = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") }.toList().toSet()
    }

    @After
    fun cleanup() {
        root?.let { workspace -> Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

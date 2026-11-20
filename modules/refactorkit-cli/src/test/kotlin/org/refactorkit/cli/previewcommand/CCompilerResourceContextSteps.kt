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
import org.refactorkit.c.CCompilerDiagnostics
import org.refactorkit.c.CDiagnosticsResult
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceCurrency
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CCompilerResourceContextSteps {
    private var ownRoot: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var workspace: Path
    private lateinit var resourceRoot: Path
    private lateinit var discovered: ClangSemanticToolchain
    private lateinit var selected: ClangSemanticToolchain
    private lateinit var sdkBefore: ClangResourceCaptureResult.Captured
    private lateinit var snapshot: ProjectSnapshot
    private var condition = ""
    private var result: CFunctionFactsResult? = null
    private var syntaxResult: CDiagnosticsResult? = null
    private var source = "int adjust(int a, int b) { return a - b; }\nint main(void) { return adjust(9, 4); }\n"
    private var database = ""

    @Given("a native C compiler resource context has {string}")
    fun prepare(case: String) {
        condition = case
        val root = Files.createTempDirectory("refactorkit-c-resource-context-"); ownRoot = root
        workspace = Files.createDirectories(root.resolve("workspace")); resourceRoot = root.resolve("resources")
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val sdkResources = sdk.resolve("usr/lib/clang/22")
        sdkBefore = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(sdkResources))
        Files.walk(sdkResources).use { paths -> paths.forEach { path ->
            val target = resourceRoot.resolve(sdkResources.relativize(path))
            if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else Files.copy(path, target)
        } }
        discovered = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))).toolchain
        val declaration = discovered.provenance.copy(resourceDir = resourceRoot.toString())
        selected = if (case == "resource capture missing") ClangSemanticToolchain(discovered.clangExecutable,
            discovered.clangdExecutable, discovered.clangFormatExecutable, declaration, runtime = discovered.runtime)
            else discovered.copy(provenance = declaration, resources = assertIs<ClangResourceCaptureResult.Captured>(
                ClangResourceDirectoryCapture().capture(resourceRoot)))
        assertEquals(ClangToolchainRuntimeCurrency.Current, ClangToolchainRuntimeCapture().checkCurrent(selected),
            "Resource refusal cases must retain their current native runtime authority")
        Files.writeString(workspace.resolve("main.c"), source)
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(discovered.clangExecutable.toString(), "-std=c17", "-DREQUIRED=17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        snapshot = CProjectScanner().scan(workspace); manager = ExternalSemanticProcessManager()
    }

    @When("function facts are requested with the retained resource context")
    fun analyze() {
        changeResources()
        result = CFunctionFactsService(selected, requireNotNull(manager)).analyze(snapshot, Path.of("main.c"), "adjust")
    }

    private fun changeResources() {
        when (condition) {
            "captured header bytes change" -> Files.writeString(resourceRoot.resolve("include/stddef.h"),
                "\n/* fixture changed resource */\n", StandardOpenOption.APPEND)
            "empty resource file added" -> Files.createFile(resourceRoot.resolve("include/added.h"))
            "captured header removed" -> Files.delete(resourceRoot.resolve("include/stddef.h"))
            "resource link inserted" -> {
                val script = requireNotNull(ownRoot).resolve("script"); Files.writeString(script,
                    "#!/bin/sh\nprintf executed > \"${requireNotNull(ownRoot).resolve("executed")}\"\n")
                Files.createSymbolicLink(resourceRoot.resolve("include/indirect.h"), script)
            }
        }
    }

    @When("syntax diagnostics are requested with the retained resource context")
    fun syntaxDiagnostics() {
        source = "#include <stddef.h>\n_Static_assert(sizeof(size_t) > 0, \"resource typedef required\");\n" + source
        Files.writeString(workspace.resolve("main.c"), source)
        snapshot = CProjectScanner().scan(workspace)
        changeResources()
        val flags = if (condition == "caller resource override") listOf("-std=c17", "-resource-dir", sdkBefore.snapshot.root.toString())
            else listOf("-std=c17")
        syntaxResult = CCompilerDiagnostics(selected, requireNotNull(manager)).analyze(snapshot, Path.of("main.c"), flags)
    }

    @Then("syntax resource authority reports {string}")
    fun syntaxReport(expected: String) {
        if (expected != "pinned clean diagnostics") {
            val refused = assertIs<CDiagnosticsResult.Unavailable>(syntaxResult, syntaxResult.toString())
            val code = if (expected == "resource override refused") "clang.compilerDiagnosticsResourceOverride"
                else { assertEquals("compiler context refused", expected); "clang.compilerResourcesUnavailable" }
            assertEquals(code, refused.diagnostic.code)
            assertTrue(refused.diagnostic.message.contains("resource")); return
        }
        val available = assertIs<CDiagnosticsResult.Available>(syntaxResult, syntaxResult.toString())
        assertTrue(available.diagnostics.isEmpty(), available.diagnostics.toString())
        val provenance = assertNotNull(available.processProvenance)
        val args = listOf("-fsyntax-only", "--no-default-config", "-std=c17", "-resource-dir", resourceRoot.toString(), provenance.workingDirectory.resolve("main.c").toString())
        assertEquals(hashOracle(args.joinToString("\u0000", postfix = "\u0000").toByteArray()), provenance.argumentsSha256,
            "Syntax analysis must pin the captured resource root")
        assertEquals(discovered.clangExecutable, provenance.executable)
        assertFalse(ProcessHandle.of(provenance.pid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Then("compiler resource authority reports {string}")
    fun report(expected: String) {
        if (expected == "compiler context refused") {
            val refused = assertIs<CFunctionFactsResult.Unavailable>(result, result.toString())
            assertEquals("c.functionFacts.compilerUnavailable", refused.diagnostic.code)
            assertTrue(refused.diagnostic.message.contains("resource")); return
        }
        assertEquals("pinned compiler facts", expected)
        val available = assertIs<CFunctionFactsResult.Available>(result, result.toString())
        assertEquals(snapshot.hash, available.snapshotHash); assertEquals("adjust", available.facts.name)
        val discoveredResources = assertIs<ClangResourceCaptureResult.Captured>(discovered.resources)
        assertEquals(sdkBefore.snapshot, discoveredResources.snapshot)
        val args = listOf("-fsyntax-only", "--no-default-config", "-resource-dir", resourceRoot.toString(), "-Xclang", "-ast-dump=json",
            "-std=c17", "-DREQUIRED=17", available.processProvenance.workingDirectory.resolve("main.c").toString())
        assertEquals(hashOracle(args.joinToString("\u0000", postfix = "\u0000").toByteArray()), available.processProvenance.argumentsSha256)
        assertEquals(discovered.clangExecutable, available.processProvenance.executable)
        assertFalse(ProcessHandle.of(available.processProvenance.pid).map(ProcessHandle::isAlive).orElse(false))
    }

    @Then("resource context validation preserves source SDK and process ownership")
    fun intact() {
        assertEquals(source, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(sdkBefore.snapshot))
        assertFalse(Files.exists(requireNotNull(ownRoot).resolve("executed")))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
    }

    private fun hashOracle(bytes: ByteArray): String {
        val process = ProcessBuilder("/usr/bin/sha256sum").redirectErrorStream(true).start()
        try {
            process.outputStream.use { it.write(bytes) }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val output = process.inputStream.use { it.readNBytes(1025).toString(Charsets.UTF_8) }
            assertTrue(output.length <= 1024); assertEquals(0, process.exitValue(), output)
            return output.substringBefore(' ')
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @After
    fun cleanup() { manager?.close(); ownRoot?.let { root -> Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    } } }
}

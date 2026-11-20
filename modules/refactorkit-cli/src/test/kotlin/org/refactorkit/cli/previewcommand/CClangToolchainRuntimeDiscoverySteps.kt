package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.c.ClangToolchainRuntimeProvider
import org.refactorkit.c.ClangVersionProbe
import org.refactorkit.c.ManagedClangVersionProbe
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Qualification fixtures/instrumentation only; discovery and runtime rules stay in the C service. */
class CClangToolchainRuntimeDiscoverySteps {
    private var root: Path? = null
    private var toolRoot: Path? = null
    private lateinit var reference: Path
    private lateinit var clang: Path
    private lateinit var clangd: Path
    private lateinit var formatter: Path
    private var condition = ""
    private var versionProbes = 0
    private var mutableInput: Path? = null
    private var originals = emptyMap<Path, String>()
    private var owned = emptyMap<Path, String>()
    private var result: ClangToolchainDiscovery? = null
    private val nativeProvider = ClangToolchainRuntimeCapture()
    private var provider: ClangToolchainRuntimeProvider = nativeProvider

    @Given("C toolchain runtime discovery has {string}")
    fun discoveryInputs(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-runtime-discovery-"); root = workspace
        Files.writeString(workspace.resolve("main.c"), "int main(void) { return 0; }\n")
        Files.writeString(workspace.resolve("marker"), "unexecuted\n")
        reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        clang = reference.resolve("usr/bin/clang-22"); clangd = reference.resolve("usr/bin/clangd"); formatter = reference.resolve("usr/bin/clang-format")
        originals = listOf(clang, clangd, formatter, reference.resolve("usr/lib/libclang-cpp.so.22.1"),
            Path.of("/usr/lib/libffi.so.8"), Path.of("/etc/ld.so.cache")).associateWith(::hash)
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", originals.getValue(clang))
        assertEquals("aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324", originals.getValue(clangd))
        assertEquals("b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf", originals.getValue(formatter))
        if (case in setOf("library changes", "executable changes")) {
            val tools = Files.createTempDirectory("refactorkit-c-runtime-tools-"); toolRoot = tools
            val relativeInputs = listOf("usr/bin/clang-22", "usr/bin/clangd", "usr/bin/clang-format", "usr/lib/libclang-cpp.so.22.1")
            for (relative in relativeInputs) {
                val source = reference.resolve(relative)
                val target = tools.resolve(relative); Files.createDirectories(target.parent); Files.copy(source, target)
                assertEquals(-1L, Files.mismatch(source, target), "Copied native input differs: $relative")
            }
            val resources = reference.resolve("usr/lib/clang/22")
            Files.walk(resources).use { paths -> paths.forEach { source ->
                val target = tools.resolve("usr/lib/clang/22").resolve(resources.relativize(source))
                if (Files.isDirectory(source, NOFOLLOW_LINKS)) Files.createDirectories(target) else {
                    assertTrue(Files.isRegularFile(source, NOFOLLOW_LINKS)); Files.copy(source, target)
                }
            } }
            Files.copy(Path.of("/usr/lib/libffi.so.8"), tools.resolve("usr/lib/libffi.so.8"))
            for (relative in relativeInputs.take(3)) {
                val patcher = ProcessBuilder(
                    System.getenv("REFACTORKIT_TEST_PATCHELF") ?: "patchelf",
                    "--add-needed", "libffi.so.8",
                    tools.resolve(relative).toString(),
                ).redirectErrorStream(true).start()
                val output = patcher.inputStream.bufferedReader().readText()
                assertTrue(patcher.waitFor(10, TimeUnit.SECONDS), "patchelf timed out for $relative")
                assertEquals(0, patcher.exitValue(), "Could not add private runtime edge for $relative: $output")
            }
            clang = tools.resolve("usr/bin/clang-22"); clangd = tools.resolve("usr/bin/clangd"); formatter = tools.resolve("usr/bin/clang-format")
            mutableInput = if (case == "library changes") tools.resolve("usr/lib/libffi.so.8") else clangd
        }
        if (case == "non-ELF executable") {
            val tools = Files.createTempDirectory("refactorkit-c-runtime-script-"); toolRoot = tools
            clang = tools.resolve("script-clang")
            Files.writeString(clang, "#!/bin/sh\nprintf executed > '${workspace.resolve("marker")}'\nprintf 'clang version 22.1.8\\n'\n")
            assertTrue(clang.toFile().setExecutable(true))
        }
        if (case in setOf("alternate cache", "mismatched inventory")) {
            val selected = if (case == "alternate cache") {
                val alternate = workspace.resolve("cache"); Files.copy(Path.of("/etc/ld.so.cache"), alternate)
                nativeProvider.inspect(clang, clangd, formatter, alternate, workspace.resolve("absent-preload"))
            } else nativeProvider.capture(clang, clangd, formatter)
            val retained = assertIs<ClangToolchainRuntimeCaptureResult.Captured>(selected)
            if (case == "mismatched inventory") {
                val tools = Files.createTempDirectory("refactorkit-c-runtime-other-"); toolRoot = tools
                val other = tools.resolve("clang-22"); Files.copy(clang, other); clang = other
            }
            provider = object : ClangToolchainRuntimeProvider {
                override fun capture(clang: Path, clangd: Path, formatter: Path) = retained
                override fun checkCurrent(captured: ClangToolchainRuntimeCaptureResult.Captured) = nativeProvider.checkCurrent(captured)
            }
        }
        owned = ownFiles()
    }

    @When("the configured C tools are discovered with retained runtime inputs")
    fun discoverTools() {
        val actualProbe = ManagedClangVersionProbe()
        val probe = ClangVersionProbe { executable ->
            versionProbes++
            val output = actualProbe.probe(executable)
            if (versionProbes == 1 && condition in setOf("library changes", "executable changes")) {
                assertTrue(output.isSuccess, "First actual 22.1.8 probe must complete before owned mutation")
                val file = assertNotNull(mutableInput); Files.write(file, byteArrayOf(1), StandardOpenOption.APPEND)
                owned = owned + (file to hash(file))
            }
            output
        }
        result = ClangToolchainDiscoverer(versionProbe = probe, runtimeProvider = provider).discover(
            ClangToolchainRequest(assertNotNull(root), clang, clangd, formatter))
    }

    @Then("C runtime toolchain discovery is {string} after {string} version probes")
    fun discoveryOutcome(outcome: String, expectedProbes: String) {
        assertEquals(expectedProbes.toInt(), versionProbes)
        if (outcome == "current") {
            val toolchain = assertIs<ClangToolchainDiscovery.Available>(result).toolchain
            val runtime = assertIs<ClangToolchainRuntimeCaptureResult.Captured>(toolchain.runtime)
            assertEquals(ClangToolchainRuntimeCurrency.Current, nativeProvider.checkCurrent(runtime))
            assertEquals(runtime.facts.identity, toolchain.provenance.nativeRuntimeIdentity)
            assertEquals(listOf("22.1.8", "22.1.8", "22.1.8"), listOf(toolchain.provenance.clangVersion, toolchain.provenance.clangdVersion, toolchain.provenance.clangFormatVersion))
            assertEquals(listOf(clang, clangd, formatter), runtime.facts.runtimes.map { it.manifest.executable.requestedPath })
            assertEquals(listOf(7, 7, 7), runtime.facts.runtimes.map { it.manifest.objects.size })
            assertEquals(listOf(16, 16, 16), runtime.facts.runtimes.map { it.manifest.bindings.size })
            assertIs<ClangResourceCaptureResult.Captured>(toolchain.resources)
            assertEquals(64, toolchain.provenance.toolchainIdentity().length)
        } else {
            val code = when (condition) {
                "non-ELF executable" -> "clang.toolchainRuntimeUnavailable"
                "alternate cache" -> "clang.toolchainRuntimeProfileUnsupported"
                "mismatched inventory" -> "clang.toolchainRuntimeMismatch"
                else -> "clang.toolchainRuntimeChanged"
            }
            assertEquals(listOf(code), assertIs<ClangToolchainDiscovery.Refused>(result).diagnostics.map { it.code })
        }
        val destination = Path.of(assertNotNull(System.getProperty("refactorkit.c.runtimeDiscoveryEvidence")))
        Files.createDirectories(destination)
        val available = result as? ClangToolchainDiscovery.Available
        val evidence = buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "Native discovery runtime fences only; no complete semantic/host/package/licence admission")
            put("condition", condition); put("outcome", outcome); put("versionProbes", versionProbes)
            put("nativeRuntimeIdentity", available?.toolchain?.provenance?.nativeRuntimeIdentity ?: "unavailable")
            put("toolchainIdentity", available?.toolchain?.provenance?.toolchainIdentity() ?: "unavailable")
        }
        Files.writeString(destination.resolve(condition.replace(' ', '-') + ".json"), evidence.toString() + "\n")
    }

    @Then("runtime discovery preserves source and closes native probe processes")
    fun preservesAndCloses() {
        assertEquals(owned, ownFiles())
        originals.forEach { (path, expected) -> assertEquals(expected, hash(path)) }
        assertEquals("unexecuted\n", Files.readString(assertNotNull(root).resolve("marker")))
        // Each actual managed constant probe awaits exit and closes its private manager before returning.
        // Owned copied executables are unique to this scenario, so their process command lines provide an independent closure oracle.
        val tools = toolRoot
        if (tools != null) {
            val running = ProcessHandle.allProcesses().use { processes -> processes.filter { process ->
                process.info().command().orElse("").startsWith(tools.toString() + "/") }.map { it.pid() }.toList() }
            assertTrue(running.isEmpty(), "Owned native probe process remains alive: $running")
        }
    }

    private fun ownFiles(): Map<Path, String> = (listOfNotNull(root, toolRoot)).flatMap { directory ->
        Files.walk(directory).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList() } }.associateWith(::hash)
    private fun hash(path: Path): String {
        val builder = ProcessBuilder("/usr/bin/sha256sum", path.toString()).directory(assertNotNull(root).toFile()).redirectErrorStream(true)
        builder.environment().clear(); val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(4097) } }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); val bytes = output.get(1, TimeUnit.SECONDS); assertTrue(bytes.size <= 4096)
            assertEquals(0, process.exitValue()); return bytes.toString(Charsets.UTF_8).substringBefore(' ')
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS) }
    }
    @After
    fun cleanup() {
        for (directory in listOfNotNull(toolRoot, root)) Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        root = null; toolRoot = null
    }
}

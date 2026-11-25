package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CCompilerDiagnostics
import org.refactorkit.c.CFormatResult
import org.refactorkit.c.CFormatter
import org.refactorkit.c.CClangdSemanticResult
import org.refactorkit.c.CDiagnosticsResult
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangdSemanticClient
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceCurrency
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCaptureResult
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.TextEdits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Owned native fixtures and independent oracles; consumer rules stay in the C services. */
class CCompilerRuntimeCurrencySteps {
    private var ownedRoot: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var workspace: Path
    private lateinit var tools: Path
    private lateinit var selected: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var sdkResources: ClangResourceCaptureResult.Captured
    private var originalInputs = emptyMap<Path, String>()
    private var ownedInputs = emptyMap<Path, String>()
    private var privateBefore = emptySet<Path>()
    private var condition = ""
    private var facts: CFunctionFactsResult? = null
    private var syntax: CDiagnosticsResult? = null
    private var formatting: CFormatResult? = null
    private var clangdResult: CClangdSemanticResult? = null
    private var clangdFailure: Throwable? = null
    private var clangdPid: Long? = null
    private var mutateRuntimeAfterLaunch: Path? = null

    @Given("a native compiler consumer runtime has {string}")
    fun prepare(case: String) = prepareNative(case, formatter = false)

    @Given("a native formatter consumer runtime has {string}")
    fun prepareFormatter(case: String) = prepareNative(case, formatter = true)

    @Given("a native clangd consumer runtime has {string}")
    fun prepareClangd(case: String) = prepareNative(case, formatter = false, clangdConsumer = true)

    private fun prepareNative(case: String, formatter: Boolean, clangdConsumer: Boolean = false) {
        condition = case
        val owned = Files.createTempDirectory("refactorkit-c-compiler-runtime-"); ownedRoot = owned
        workspace = Files.createDirectories(owned.resolve("workspace"))
        tools = Files.createDirectories(owned.resolve("tools"))
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val relativeInputs = listOf("usr/bin/clang-22", "usr/bin/clangd", "usr/bin/clang-format",
            "usr/lib/libclang-cpp.so.22.1")
        val libffi = nativeFixtureLibffi()
        originalInputs = (relativeInputs.map(reference::resolve) + listOf(libffi, Path.of("/etc/ld.so.cache"))).associateWith(::hash)
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113", originalInputs.getValue(reference.resolve("usr/bin/clang-22")))
        assertEquals("aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324", originalInputs.getValue(reference.resolve("usr/bin/clangd")))
        assertEquals("b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf", originalInputs.getValue(reference.resolve("usr/bin/clang-format")))
        for (relative in relativeInputs) {
            val target = tools.resolve(relative); Files.createDirectories(target.parent); Files.copy(reference.resolve(relative), target)
            assertEquals(-1L, Files.mismatch(reference.resolve(relative), target))
        }
        Files.copy(libffi, tools.resolve("usr/lib/libffi.so.8"))
        // Give the copied, otherwise host-linked tools one owned runtime edge so
        // mutation tests can change a captured library without touching /usr/lib.
        for (relative in relativeInputs.take(3)) {
            val executable = tools.resolve(relative)
            val patcher = ProcessBuilder(
                System.getenv("REFACTORKIT_TEST_PATCHELF") ?: "patchelf",
                "--add-needed", "libffi.so.8",
                executable.toString(),
            ).redirectErrorStream(true).start()
            val output = patcher.inputStream.bufferedReader().readText()
            assertTrue(patcher.waitFor(10, TimeUnit.SECONDS), "patchelf timed out for $relative")
            assertEquals(0, patcher.exitValue(), "Could not add private runtime edge for $relative: $output")
        }
        val resources = reference.resolve("usr/lib/clang/22")
        sdkResources = assertIs<ClangResourceCaptureResult.Captured>(ClangResourceDirectoryCapture().capture(resources))
        Files.walk(resources).use { paths -> paths.forEach { source ->
            val target = tools.resolve("usr/lib/clang/22").resolve(resources.relativize(source))
            if (Files.isDirectory(source, NOFOLLOW_LINKS)) Files.createDirectories(target) else {
                assertTrue(Files.isRegularFile(source, NOFOLLOW_LINKS)); Files.copy(source, target)
                assertEquals(-1L, Files.mismatch(source, target))
            }
        } }
        selected = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            tools.resolve("usr/bin/clang-22"), tools.resolve("usr/bin/clangd"), tools.resolve("usr/bin/clang-format")))).toolchain
        assertEquals(listOf("22.1.8", "22.1.8", "22.1.8"), listOf(selected.provenance.clangVersion, selected.provenance.clangdVersion, selected.provenance.clangFormatVersion))
        assertIs<ClangToolchainRuntimeCaptureResult.Captured>(selected.runtime)
        val source = if (formatter) "int main(void){return 0;}\n" else
            "static int adjust(int a, int b);\nstatic int adjust(int a, int b) { return a - b; }\nint main(void) { return adjust(9, 4); }\n"
        Files.writeString(workspace.resolve("main.c"), source)
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(selected.clangExecutable.toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString())
        snapshot = CProjectScanner().scan(workspace)
        when (case) {
            "runtime capture missing" -> selected = selected.copy(runtime = ClangToolchainRuntimeCaptureResult.NotCaptured)
            "runtime identity changed" -> selected = selected.copy(provenance = selected.provenance.copy(nativeRuntimeIdentity = "a".repeat(64)))
            "executable selector changed" -> {
                val original = when {
                    formatter -> selected.clangFormatExecutable
                    clangdConsumer -> selected.clangdExecutable
                    else -> selected.clangExecutable
                }
                val alternative = tools.resolve("usr/bin/other-tool"); Files.copy(original, alternative)
                selected = when {
                    formatter -> selected.copy(clangFormatExecutable = alternative)
                    clangdConsumer -> selected.copy(clangdExecutable = alternative)
                    else -> selected.copy(clangExecutable = alternative)
                }
            }
            "captured library changed" -> if (clangdConsumer) {
                mutateRuntimeAfterLaunch = tools.resolve("usr/lib/libffi.so.8")
            } else Files.write(tools.resolve("usr/lib/libffi.so.8"), byteArrayOf(1), APPEND)
            "executable became canary" -> Files.writeString(when {
                formatter -> selected.clangFormatExecutable
                clangdConsumer -> selected.clangdExecutable
                else -> selected.clangExecutable
            },
                "#!/bin/sh\nprintf executed > '${owned.resolve("executed")}'\n" +
                    (if (formatter) "printf 'int main(void) { return 0; }\\n'\n" else "") + "exit 0\n")
        }
        ownedInputs = ownFiles(); privateBefore = privateDirectories(); manager = ExternalSemanticProcessManager()
    }

    @When("AST facts and syntax diagnostics are requested from that runtime")
    fun evaluate() {
        facts = CFunctionFactsService(selected, assertNotNull(manager)).analyze(snapshot, Path.of("main.c"), "adjust")
        syntax = CCompilerDiagnostics(selected, assertNotNull(manager)).analyze(snapshot, Path.of("main.c"), listOf("-std=c17"))
    }

    @When("formatting and idempotence are requested from that runtime")
    fun evaluateFormatter() {
        formatting = CFormatter(selected, assertNotNull(manager), style = "LLVM").formatWholeFile(snapshot, Path.of("main.c"))
    }

    @When("clangd resolves a bound C function definition from that runtime")
    fun resolveClangdDefinition() {
        val source = workspace.resolve("main.c")
        val client = ClangdSemanticClient(selected, assertNotNull(manager))
        try {
            try {
                client.start(workspace)
                clangdPid = client.processProvenance()?.pid
                check(client.didOpen(source, Files.readString(source))) { "clangd did not accept the captured C source" }
                mutateRuntimeAfterLaunch?.let { changed ->
                    Files.write(changed, byteArrayOf(1), APPEND)
                    ownedInputs = ownFiles()
                }
                clangdResult = client.definition(source, 1, 14)
            } catch (failure: Throwable) {
                clangdFailure = failure
            }
        } finally {
            clangdPid = clangdPid ?: client.processProvenance()?.pid
            client.close()
        }
    }

    @Then("clangd native runtime admission is {string}")
    fun clangdRuntimeOutcome(outcome: String) {
        when (outcome) {
            "definition found" -> {
                assertEquals(null, clangdFailure, "current native runtime should permit a clangd session")
                val found = assertIs<CClangdSemanticResult.Found>(clangdResult, clangdResult.toString())
                assertEquals(workspace.resolve("main.c"), found.definition.file)
                val definition = found.definition
                val definitionLine = Files.readAllLines(definition.file).getOrNull(definition.startLine)
                assertEquals(definition.startLine, definition.endLine)
                assertEquals("adjust", definitionLine?.substring(definition.startCharacter, definition.endCharacter))
                assertNotNull(clangdPid)
            }
            "runtime refused" -> {
                val failure = assertIs<org.refactorkit.c.CClangdConfigurationException>(clangdFailure, clangdFailure.toString())
                val expected = if (condition == "runtime capture missing") "c.clangdRuntimeUnavailable" else "c.clangdRuntimeChanged"
                assertEquals(expected, failure.diagnostic.code)
                assertEquals(null, clangdPid, "stale clangd runtime must refuse before process launch")
                assertEquals(null, clangdResult)
            }
            "runtime revoked" -> {
                assertEquals(null, clangdFailure)
                val refused = assertIs<CClangdSemanticResult.Refused>(clangdResult, clangdResult.toString())
                assertEquals(listOf("c.clangdRuntimeChanged"), refused.diagnostics.map { it.code })
                val pid = assertNotNull(clangdPid, "clangd must start before the owned runtime input changes")
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "stale clangd process remained alive")
                assertTrue(assertNotNull(manager).activeProvenance().isEmpty())
            }
            else -> error("Unexpected clangd runtime outcome: $outcome")
        }
        val evidenceRoot = System.getProperty("refactorkit.c.compilerRuntimeEvidence")
            ?: System.getenv("REFACTORKIT_C_COMPILER_RUNTIME_EVIDENCE")
        val destination = Path.of(assertNotNull(evidenceRoot, "compiler runtime evidence path was not configured"))
        Files.createDirectories(destination)
        Files.writeString(destination.resolve("clangd-${condition.replace(' ', '-')}.json"), buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "clangd launch/query native runtime currency only; no full C02 host or source/header admission")
            put("condition", condition); put("outcome", outcome)
            put("nativeRuntimeIdentity", selected.provenance.nativeRuntimeIdentity ?: "unavailable")
        }.toString() + "\n")
    }

    @Then("formatter runtime authority reports {string}")
    fun formatterResults(outcome: String) {
        if (outcome == "idempotent edits") {
            val accepted = assertIs<CFormatResult.Accepted>(formatting, formatting.toString())
            assertTrue(accepted.edits.isNotEmpty()); assertTrue(accepted.idempotent)
            assertEquals("int main(void) { return 0; }\n", TextEdits.apply(snapshot.files.single().content, accepted.edits))
        } else {
            assertEquals("runtime refused", outcome)
            assertEquals(listOf("clang.formatRuntimeUnavailable"), assertIs<CFormatResult.Refused>(formatting, formatting.toString()).diagnostics.map { it.code })
        }
        val destination = Path.of(assertNotNull(System.getProperty("refactorkit.c.formatterRuntimeEvidence")))
        Files.createDirectories(destination)
        Files.writeString(destination.resolve(condition.replace(' ', '-') + ".json"), buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "Formatter native currency only; no complete style, race, lease or semantic admission")
            put("condition", condition); put("outcome", outcome)
            put("nativeRuntimeIdentity", selected.provenance.nativeRuntimeIdentity ?: "unavailable")
        }.toString() + "\n")
    }

    @Then("formatter runtime evaluation preserves source and releases private processes")
    fun formatterPreserved() = preserved()

    @Then("compiler runtime consumers report {string}")
    fun results(outcome: String) {
        if (outcome == "available results") {
            val ast = assertIs<CFunctionFactsResult.Available>(facts, facts.toString())
            val diagnostics = assertIs<CDiagnosticsResult.Available>(syntax, syntax.toString())
            assertEquals(snapshot.hash, ast.snapshotHash); assertEquals("adjust", ast.facts.name)
            assertEquals(2, ast.facts.declarations.size); assertEquals(1, ast.facts.calls.size)
            assertTrue(diagnostics.diagnostics.isEmpty(), diagnostics.diagnostics.toString())
            for (process in listOf(ast.processProvenance, assertNotNull(diagnostics.processProvenance))) {
                assertEquals(selected.clangExecutable, process.executable)
                assertEquals(hash(selected.clangExecutable), process.executableSha256)
                assertFalse(process.workingDirectory.startsWith(workspace))
                assertFalse(Files.exists(process.workingDirectory))
                assertFalse(ProcessHandle.of(process.pid).map(ProcessHandle::isAlive).orElse(false))
            }
        } else {
            assertEquals("unavailable results", outcome)
            assertEquals("c.functionFacts.compilerUnavailable", assertIs<CFunctionFactsResult.Unavailable>(facts, facts.toString()).diagnostic.code)
            assertEquals("clang.compilerRuntimeUnavailable", assertIs<CDiagnosticsResult.Unavailable>(syntax, syntax.toString()).diagnostic.code)
        }
        val destination = Path.of(assertNotNull(System.getProperty("refactorkit.c.compilerRuntimeEvidence")))
        Files.createDirectories(destination)
        Files.writeString(destination.resolve(condition.replace(' ', '-') + ".json"), buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "AST/syntax runtime currency only; no complete host, race, lease or semantic admission")
            put("condition", condition); put("outcome", outcome)
            put("nativeRuntimeIdentity", selected.provenance.nativeRuntimeIdentity ?: "unavailable")
        }.toString() + "\n")
    }

    @Then("compiler runtime evaluation preserves source and releases private processes")
    fun preserved() {
        assertEquals(ownedInputs, ownFiles()); originalInputs.forEach { (path, expected) -> assertEquals(expected, hash(path)) }
        assertEquals(ClangResourceCurrency.Current, ClangResourceDirectoryCapture().checkCurrent(sdkResources.snapshot))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        assertFalse(Files.exists(assertNotNull(ownedRoot).resolve("executed")), "A stale executable canary was executed")
        assertTrue(assertNotNull(manager).activeProvenance().isEmpty()); assertEquals(privateBefore, privateDirectories())
        val running = ProcessHandle.allProcesses().use { processes -> processes.filter {
            it.info().command().orElse("").startsWith(tools.toString() + "/") }.map { it.pid() }.toList() }
        assertTrue(running.isEmpty(), "Owned compiler process remains alive: $running")
    }

    private fun ownFiles() = Files.walk(assertNotNull(ownedRoot)).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(::hash) }
    private fun privateDirectories() = Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("refactorkit-clangd-launch-") ||
            it.fileName.toString().startsWith("refactorkit-semantic-overlay-") ||
            it.fileName.toString().startsWith("refactorkit-clang-format-") }.toList().toSet() }
    private fun hash(path: Path): String {
        val builder = ProcessBuilder("/usr/bin/sha256sum", path.toString()).redirectErrorStream(true)
        builder.environment().clear(); val process = builder.start()
        val future = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(4097) } }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS)); val bytes = future.get(1, TimeUnit.SECONDS)
            assertTrue(bytes.size <= 4096); assertEquals(0, process.exitValue())
            return bytes.toString(Charsets.UTF_8).substringBefore(' ')
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS) }
    }
    @After
    fun cleanup() {
        manager?.close(); ownedRoot?.let { owned -> Files.walk(owned).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
        manager = null; ownedRoot = null
    }
}

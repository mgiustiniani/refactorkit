package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.*
import org.refactorkit.core.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.*

class CCompilerSdkContextSteps {
    private val fixture = CCompilerSdkContextFixture()

    @Given("a native C compiler SDK fixture uses {string} in {string} mode")
    fun capture(condition: String, standard: String) = fixture.capture(condition, standard)

    @When("source facts and current plus staged syntax consume that SDK")
    fun evaluate() = fixture.evaluate()

    @Then("compiler SDK authority reports {string} with exact native context")
    fun outcome(expected: String) = fixture.outcome(expected)

    @Then("compiler SDK checks preserve saved inputs and close private processes")
    fun intact() = fixture.intact()

    @After
    fun close() = fixture.close()
}

private class CCompilerSdkContextFixture : AutoCloseable {
    private var root: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var workspace: Path
    private lateinit var sdkRoot: Path
    private lateinit var selected: ClangSemanticToolchain
    private lateinit var standard: String
    private lateinit var condition: String
    private lateinit var database: String
    private lateinit var savedSdk: Map<Path, String>
    private var facts: CFunctionFactsResult? = null
    private var current: CDiagnosticsResult? = null
    private var staged: CDiagnosticsResult? = null
    private val factsText = "int adjust(int a, int b) { return a - b; }\nint main(void) { return adjust(9, 4); }\n"
    private val syntaxText = "#include \"api.h\"\nint read_api(struct sdk_value value) { return value.amount; }\n"

    fun capture(variant: String, dialect: String) {
        require(dialect in setOf("c11", "c17")); standard = dialect; condition = variant
        val directory = Files.createTempDirectory("rk-c-sdk-compiler-").also { root = it }
        workspace = Files.createDirectory(directory.resolve("workspace"))
        sdkRoot = Files.createDirectories(directory.resolve("readonly-sdk/usr/include")).parent.parent
        Files.writeString(sdkRoot.resolve("usr/include/api.h"), "#pragma once\nstruct sdk_value { int amount; };\n")
        Files.writeString(workspace.resolve("main.c"), factsText)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        selected = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspace, reference.resolve("usr/bin/clang-22"), reference.resolve("usr/bin/clangd"), reference.resolve("usr/bin/clang-format"),
            readOnlySdks = listOf(CReadOnlySdkRequest(sdkRoot, "fixture:compiler-sdk", "LicenseRef-RefactorKitFixture")),
        ))).toolchain
        assertEquals("31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113",
            selected.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        assertEquals(selected.readOnlySdkInputs.identity, selected.provenance.readOnlySdkIdentity)
        assertTrue(selected.readOnlySdkInputs.isCurrent())
        database = JsonArray(listOf(buildJsonObject {
            put("directory", workspace.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(selected.clangExecutable.toString(), "-std=$standard", "--sysroot=$sdkRoot", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        Files.writeString(workspace.resolve("compile_commands.json"), database)
        manager = ExternalSemanticProcessManager()
    }

    fun evaluate() {
        when (condition) {
            "intact SDK" -> Unit
            "SDK header changes" -> Files.writeString(sdkRoot.resolve("usr/include/api.h"), "\n/* changed SDK bytes */\n", StandardOpenOption.APPEND)
            "SDK header appears" -> Files.writeString(sdkRoot.resolve("usr/include/added.h"), "#pragma once\n")
            "SDK identity differs" -> selected = selected.copy(provenance = selected.provenance.copy(readOnlySdkIdentity = "f".repeat(64)))
            "SDK header removed" -> Files.delete(sdkRoot.resolve("usr/include/api.h"))
            else -> error("Unexpected SDK condition: $condition")
        }
        savedSdk = files(sdkRoot)
        val processes = requireNotNull(manager)
        facts = CFunctionFactsService(selected, processes).analyze(CProjectScanner().scan(workspace), Path.of("main.c"), "adjust")
        assertEquals(factsText, Files.readString(workspace.resolve("main.c")))
        Files.writeString(workspace.resolve("main.c"), syntaxText)
        val before = CProjectScanner().scan(workspace)
        val draft = before.copy(files = before.files.map { source ->
            if (source.path == Path.of("main.c")) source.copy(content = syntaxText.replace("value.amount", "value.missing")) else source
        })
        val compiler = CCompilerDiagnostics(selected, processes)
        val flags = listOf("-std=$standard", "--sysroot", sdkRoot.toString())
        current = compiler.analyze(before, Path.of("main.c"), flags)
        staged = compiler.analyze(draft, Path.of("main.c"), flags)
    }

    fun outcome(expected: String) {
        if (expected == "unavailable results") {
            for (result in listOf(current, staged)) {
                assertEquals("clang.compilerSdkUnavailable", assertIs<CDiagnosticsResult.Unavailable>(result, result.toString()).diagnostic.code)
            }
            assertEquals("c.functionFacts.compilerUnavailable", assertIs<CFunctionFactsResult.Unavailable>(facts, facts.toString()).diagnostic.code)
            return
        }
        assertEquals("available results", expected)
        val ast = assertIs<CFunctionFactsResult.Available>(facts, facts.toString())
        assertEquals("adjust", ast.facts.name)
        verify(ast.processProvenance, listOf("-fsyntax-only", "--no-default-config", "-resource-dir", requireNotNull(selected.provenance.resourceDir),
            "-Xclang", "-ast-dump=json", "-std=$standard", "--sysroot", sdkRoot.toString(), ast.processProvenance.workingDirectory.resolve("main.c").toString()))
        val clean = assertIs<CDiagnosticsResult.Available>(current, current.toString())
        assertTrue(clean.diagnostics.isEmpty(), clean.diagnostics.toString())
        val changed = assertIs<CDiagnosticsResult.Available>(staged, staged.toString())
        assertTrue(changed.diagnostics.any { it.severity == Diagnostic.Severity.ERROR && "missing" in it.message && "sdk_value" in it.message }, changed.diagnostics.toString())
        assertTrue(changed.diagnostics.all { it.location?.path == workspace.resolve("main.c") }, changed.diagnostics.toString())
        for (result in listOf(clean, changed)) {
            val provenance = assertNotNull(result.processProvenance)
            verify(provenance, listOf("-fsyntax-only", "--no-default-config", "-std=$standard", "--sysroot", sdkRoot.toString(),
                "-resource-dir", requireNotNull(selected.provenance.resourceDir), provenance.workingDirectory.resolve("main.c").toString()))
        }
    }

    private fun verify(provenance: SemanticProcessProvenance, args: List<String>) {
        assertEquals(selected.clangExecutable, provenance.executable)
        val expected = MessageDigest.getInstance("SHA-256").digest(args.joinToString("\u0000", postfix = "\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, provenance.argumentsSha256, "Native compiler must retain genuine SDK sysroot and resources")
        assertFalse(ProcessHandle.of(provenance.pid).map(ProcessHandle::isAlive).orElse(false))
        assertFalse(Files.exists(provenance.workingDirectory), "Owned compiler overlay must be removed")
    }

    fun intact() {
        assertEquals(syntaxText, Files.readString(workspace.resolve("main.c")))
        assertEquals(database, Files.readString(workspace.resolve("compile_commands.json")))
        assertEquals(savedSdk, files(sdkRoot))
        assertTrue(requireNotNull(manager).activeProvenance().isEmpty())
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
        val observed = files(workspace)
        assertEquals(setOf(Path.of("main.c"), Path.of("compile_commands.json")), observed.keys)
    }

    private fun files(directory: Path): Map<Path, String> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associate { directory.relativize(it) to Files.readString(it) }
    }

    override fun close() {
        manager?.close()
        root?.let { directory -> Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

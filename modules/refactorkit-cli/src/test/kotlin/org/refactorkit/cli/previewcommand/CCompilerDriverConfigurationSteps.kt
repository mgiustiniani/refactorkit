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
import org.refactorkit.c.CDiagnosticsResult
import org.refactorkit.c.CFunctionFactsResult
import org.refactorkit.c.CFunctionFactsService
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangToolchainRuntimeCapture
import org.refactorkit.c.ClangToolchainRuntimeCurrency
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Thin scenario adapter; mutable resources belong to its scenario-owned fixture. */
class CCompilerDriverConfigurationSteps {
    private val fixture = CCompilerDriverConfigurationFixture()

    @Given("a captured C compiler context has {string} initial diagnostics")
    fun capture(initial: String) = fixture.capture(initial)

    @When("a benign default configuration is added beside the captured compiler")
    fun addDefaultConfig() = fixture.addDefaultConfig()

    @When("RefactorKit evaluates those same captured compiler inputs again")
    fun evaluateAgain() = fixture.evaluateAgain()

    @Then("the original {string} diagnostics and compiler input identities are preserved without writes")
    fun assertPreserved(initial: String) = fixture.assertPreserved(initial)

    @Given("a captured C function family uses {string}")
    fun captureFamily(standard: String) = fixture.captureFamily(standard)

    @When("RefactorKit reads those same captured C function facts again")
    fun readFamilyAgain() = fixture.readFamilyAgain()

    @Then("compiler-bound declarations calls and input identities remain unchanged")
    fun assertFamilyPreserved() = fixture.assertFamilyPreserved()

    @Given("captured Clang metadata is retained before implicit {string} configuration")
    fun captureMetadata(field: String) = fixture.captureMetadata(field)

    @When("RefactorKit rediscovers those same configured compiler tools")
    fun rediscover() = fixture.rediscover()

    @Then("the captured compiler versions target and resource identity remain unchanged")
    fun assertMetadataPreserved() = fixture.assertMetadataPreserved()

    @After
    fun close() = fixture.close()
}

private class CCompilerDriverConfigurationFixture : AutoCloseable {
    private var owner: Path? = null
    private var manager: ExternalSemanticProcessManager? = null
    private lateinit var sdk: Path
    private lateinit var workspace: Path
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var before: CDiagnosticsResult.Available
    private lateinit var after: CDiagnosticsResult
    private lateinit var sourceBytes: ByteArray
    private lateinit var databaseBytes: ByteArray
    private lateinit var initialMode: String
    private var familyMode = false
    private var metadataField: String? = null
    private var alternateResource: Path? = null
    private lateinit var rediscovered: ClangToolchainDiscovery
    private lateinit var beforeFamily: CFunctionFactsResult.Available
    private lateinit var afterFamily: CFunctionFactsResult
    private val compilerSha256 = "31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113"

    fun capture(initial: String) {
        require(initial in setOf("clean", "error"))
        initialMode = initial
        val guard = if (initial == "clean") "ifdef" else "ifndef"
        sourceBytes = "#$guard RK_DRIVER_CONFIG\n#error captured compiler diagnostic must stay visible\n#endif\nint main(void) { return 0; }\n".toByteArray(Charsets.UTF_8)
        initialize("c17")
        before = assertIs<CDiagnosticsResult.Available>(diagnostics())
        assertResult(before)
    }

    fun captureFamily(standard: String) {
        require(standard in setOf("c11", "c17"))
        familyMode = true
        sourceBytes = "static int adjust(int n);\nstatic int adjust(int n) { return n + 1; }\nint main(void) { return adjust(2); }\n".toByteArray(Charsets.UTF_8)
        initialize(standard)
        beforeFamily = assertIs<CFunctionFactsResult.Available>(readFamily())
        assertEquals("adjust", beforeFamily.facts.name)
        assertEquals(2, beforeFamily.facts.declarations.size)
        assertEquals(1, beforeFamily.facts.calls.size)
        beforeFamily.facts.declarations.forEach { declaration ->
            assertEquals(listOf("n"), declaration.parameters.map { it.name })
            assertEquals("adjust", sourceBytes.copyOfRange(declaration.nameSpan.start, declaration.nameSpan.endExclusive).toString(Charsets.UTF_8))
        }
        assertEquals("adjust(2)", beforeFamily.facts.calls.single().span.let { sourceBytes.copyOfRange(it.start, it.endExclusive).toString(Charsets.UTF_8) })
    }

    private fun initialize(standard: String) {
        val root = Files.createTempDirectory("rk-driver-config-story-").also { owner = it }
        sdk = Files.createDirectory(root.resolve("sdk"))
        workspace = Files.createDirectory(root.resolve("workspace"))
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        Files.walk(reference.resolve("usr")).use { paths -> paths.forEach { source ->
            assertFalse(Files.isSymbolicLink(source), "Qualification SDK must have direct inputs: $source")
            val target = sdk.resolve(reference.relativize(source))
            if (Files.isDirectory(source, NOFOLLOW_LINKS)) Files.createDirectories(target)
            else {
                assertTrue(Files.isRegularFile(source, NOFOLLOW_LINKS))
                Files.copy(source, target, COPY_ATTRIBUTES)
            }
        } }
        assertFalse(Files.exists(sdk.resolve("usr/bin/clang.cfg")))
        Files.write(workspace.resolve("main.c"), sourceBytes)
        val unit = buildJsonObject {
            put("directory", workspace.toString())
            put("file", "main.c")
            put("arguments", JsonArray(listOf(sdk.resolve("usr/bin/clang-22").toString(), "-std=$standard", "-c", "main.c").map(::JsonPrimitive)))
        }
        databaseBytes = JsonArray(listOf(unit)).toString().toByteArray(Charsets.UTF_8)
        Files.write(workspace.resolve("compile_commands.json"), databaseBytes)
        snapshot = CProjectScanner().scan(workspace)
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(workspace, sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")),
        )).toolchain
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        assertEquals(compilerSha256, toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
        manager = ExternalSemanticProcessManager()
    }

    fun addDefaultConfig() {
        val config = when (metadataField) {
            "target" -> "--target=aarch64-unknown-linux-gnu\n"
            "resources" -> "-resource-dir=${requireNotNull(alternateResource)}\n"
            else -> if (familyMode) "-Dadjust=untracked\n" else "-DRK_DRIVER_CONFIG=1\n"
        }
        Files.writeString(sdk.resolve("usr/bin/clang.cfg"), config)
        assertEquals(ClangToolchainRuntimeCurrency.Current, ClangToolchainRuntimeCapture().checkCurrent(toolchain))
    }

    fun evaluateAgain() { after = diagnostics() }

    fun assertPreserved(initial: String) {
        assertEquals(initialMode, initial)
        val repeated = assertIs<CDiagnosticsResult.Available>(after, after.toString())
        assertResult(repeated)
        assertEquals(before.diagnostics.map { Triple(it.severity, it.code, it.message) },
            repeated.diagnostics.map { Triple(it.severity, it.code, it.message) })
        assertPhysicalInputsPreserved()
    }

    private fun assertPhysicalInputsPreserved() {
        assertContentEquals(sourceBytes, Files.readAllBytes(workspace.resolve("main.c")))
        assertContentEquals(databaseBytes, Files.readAllBytes(workspace.resolve("compile_commands.json")))
        assertEquals(snapshot.hash, CProjectScanner().scan(workspace).hash)
        val files = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it) }.map(workspace::relativize).toList().toSet() }
        assertEquals(setOf(Path.of("main.c"), Path.of("compile_commands.json")), files)
        assertFalse(Files.exists(workspace.resolve(".refactorkit/transactions")))
    }

    fun captureMetadata(field: String) {
        require(field in setOf("target", "resources"))
        metadataField = field
        sourceBytes = "int main(void) { return 0; }\n".toByteArray(Charsets.UTF_8)
        initialize("c17")
        if (field == "resources") {
            val original = sdk.resolve("usr/lib/clang/22")
            val alternative = requireNotNull(owner).resolve("untracked-resources").also { alternateResource = it }
            Files.walk(original).use { paths -> paths.forEach { source ->
                val target = alternative.resolve(original.relativize(source))
                if (Files.isDirectory(source, NOFOLLOW_LINKS)) Files.createDirectories(target)
                else Files.copy(source, target, COPY_ATTRIBUTES)
            } }
        }
    }

    fun rediscover() {
        rediscovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(workspace,
            sdk.resolve("usr/bin/clang-22"), sdk.resolve("usr/bin/clangd"), sdk.resolve("usr/bin/clang-format")))
    }

    fun assertMetadataPreserved() {
        val repeated = assertIs<ClangToolchainDiscovery.Available>(rediscovered, rediscovered.toString()).toolchain
        assertEquals(toolchain.provenance, repeated.provenance)
        assertEquals(toolchain.provenance.toolchainIdentity(), repeated.provenance.toolchainIdentity())
        assertEquals(ClangToolchainRuntimeCurrency.Current, ClangToolchainRuntimeCapture().checkCurrent(repeated))
        assertEquals(sdk.resolve("usr/lib/clang/22").toString(), repeated.provenance.resourceDir)
        assertPhysicalInputsPreserved()
    }

    fun readFamilyAgain() { afterFamily = readFamily() }

    fun assertFamilyPreserved() {
        val repeated = assertIs<CFunctionFactsResult.Available>(afterFamily, afterFamily.toString())
        assertEquals(beforeFamily.snapshotHash, repeated.snapshotHash)
        assertEquals(beforeFamily.facts, repeated.facts)
        assertEquals(compilerSha256, repeated.processProvenance.executableSha256)
        assertPhysicalInputsPreserved()
    }

    private fun readFamily(): CFunctionFactsResult = CFunctionFactsService(toolchain, requireNotNull(manager))
        .analyze(snapshot, Path.of("main.c"), "adjust")

    private fun diagnostics(): CDiagnosticsResult = CCompilerDiagnostics(toolchain, requireNotNull(manager))
        .analyze(snapshot, Path.of("main.c"), listOf("-std=c17"))

    private fun assertResult(result: CDiagnosticsResult.Available) {
        assertEquals(if (initialMode == "clean") 0 else 1,
            result.diagnostics.count { it.severity == Diagnostic.Severity.ERROR }, result.toString())
        if (initialMode == "error") assertTrue(result.diagnostics.any {
            it.message.contains("captured compiler diagnostic must stay visible")
        }, result.toString())
        assertEquals(compilerSha256, assertNotNull(result.processProvenance).executableSha256)
    }

    override fun close() {
        manager?.close()
        owner?.let { root -> Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } }
    }
}

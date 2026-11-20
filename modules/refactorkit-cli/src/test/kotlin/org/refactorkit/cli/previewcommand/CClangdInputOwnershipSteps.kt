package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.en.And
import kotlinx.serialization.json.*
import org.refactorkit.c.*
import org.refactorkit.core.ExternalSemanticProcessManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class CClangdInputOwnershipSteps {
    private val fixture = ClangdInputOwnershipFixture()

    @Given("a native clangd database ownership fixture {string}")
    fun prepare(input: String) = fixture.prepare(input)

    @When("the C semantic session observes its configured database ownership boundary")
    fun observe() = fixture.observe()

    @Then("database ownership produces {string} without unavailable analysis becoming a result")
    fun verify(outcome: String) = fixture.verify(outcome)

    @And("database ownership preserves fixture bytes and closes native processes")
    fun confined() = fixture.verifyConfinement()

    @After
    fun close() = fixture.close()
}

private class ClangdInputOwnershipFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var workspace: Path
    private lateinit var physicalWorkspace: Path
    private lateinit var input: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var databaseBefore: ByteArray
    private val source = "int compute(void) { return 17; }\nint main(void) { return compute(); }\n"
    private var failure: Throwable? = null
    private var result: CClangdSemanticResult? = null
    private var processPid: Long? = null
    private var managerEmpty = false
    private var processClosed = false

    fun prepare(selectedInput: String) {
        input = selectedInput
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build/test-tmp")), "clangd-owned-input-").toAbsolutePath()
        base = directory
        physicalWorkspace = Files.createDirectories(directory.resolve("physical/workspace"))
        workspace = physicalWorkspace
        Files.writeString(physicalWorkspace.resolve("main.c"), source)
        val reference = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = physicalWorkspace,
            clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"),
            clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
        ))).toolchain
        assertTrue(toolchain.provenance.clangdVersion.contains("22.1.8"))
        if (input == "root ancestor link at start") {
            Files.createSymbolicLink(directory.resolve("alias"), directory.resolve("physical"))
            workspace = directory.resolve("alias/workspace")
        }
        val unit = buildJsonObject {
            put("file", "main.c")
            put("directory", workspace.toString())
            put("arguments", JsonArray(listOf("/untrusted/compiler-not-authorized", if (input == "exact 8 MiB C11 database") "-std=c11" else "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
            put("note", "uninterpreted-marker")
        }
        val text = JsonArray(listOf(unit)).toString()
        databaseBefore = when (input) {
            "exact 8 MiB C11 database" -> text.toByteArray().let { bytes -> bytes + ByteArray(8 * 1024 * 1024 - bytes.size) { 32 } }
            "malformed UTF8 ignored note" -> text.toByteArray().also { it[text.indexOf("uninterpreted-marker")] = 0xff.toByte() }
            else -> text.toByteArray()
        }
        Files.write(physicalWorkspace.resolve("compile_commands.json"), databaseBefore)
        if (input == "absent alternate under link") {
            val outside = Files.createDirectory(directory.resolve("outside"))
            Files.createSymbolicLink(physicalWorkspace.resolve("build"), outside)
        }
    }

    fun observe() {
        ExternalSemanticProcessManager().use { manager ->
            ClangdSemanticClient(toolchain, manager).use { client ->
                failure = runCatching { client.start(workspace) }.exceptionOrNull()
                if (failure == null) {
                    processPid = assertNotNull(client.processProvenance()).pid
                    assertTrue(client.didOpen(workspace.resolve("main.c"), source))
                    when (input) {
                        "alternate parent linked live" -> {
                            val outside = Files.createDirectory(base!!.resolve("outside"))
                            Files.createSymbolicLink(physicalWorkspace.resolve("build"), outside)
                        }
                        "root ancestor linked live" -> {
                            val retained = base!!.resolve("retained")
                            Files.move(base!!.resolve("physical"), retained)
                            Files.createSymbolicLink(base!!.resolve("physical"), retained)
                            physicalWorkspace = retained.resolve("workspace")
                        }
                    }
                    result = client.definition(workspace.resolve("main.c"), 1, source.lines()[1].indexOf("compute"))
                }
            }
            managerEmpty = manager.activeProvenance().isEmpty()
        }
        processClosed = processPid?.let { !ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) } ?: true
    }

    fun verify(outcome: String) {
        when (outcome) {
            "definition" -> {
                assertNull(failure)
                val found = assertIs<CClangdSemanticResult.Found>(result).definition
                assertEquals(workspace.resolve("main.c"), found.file)
                assertEquals(0, found.startLine)
                assertEquals(4, found.startCharacter)
                assertNotNull(processPid)
            }
            "startup refused" -> {
                val refused = assertIs<CClangdConfigurationException>(failure, "Unowned or malformed input must refuse before clangd starts; result=$result")
                assertEquals("c.clangdConfigurationRefused", refused.diagnostic.code)
                assertNull(processPid)
                assertNull(result)
            }
            "currency refused" -> {
                assertNull(failure)
                assertNotNull(processPid)
                assertTrue(assertIs<CClangdSemanticResult.Refused>(result).diagnostics.any { it.code == "c.clangdConfigurationChanged" })
            }
            else -> error("Unexpected ownership outcome: $outcome")
        }
    }

    fun verifyConfinement() {
        assertContentEquals(databaseBefore, Files.readAllBytes(physicalWorkspace.resolve("compile_commands.json")))
        assertEquals(source, Files.readString(physicalWorkspace.resolve("main.c")))
        assertTrue(managerEmpty)
        assertTrue(processClosed)
        assertFalse(Files.exists(physicalWorkspace.resolve("main.o")))
        assertFalse(Files.exists(physicalWorkspace.resolve(".refactorkit/transactions")))
        base!!.resolve("outside").takeIf(Files::isDirectory)?.let { outside ->
            Files.list(outside).use { assertEquals(0L, it.count()) }
        }
    }

    override fun close() {
        base?.let { directory -> Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

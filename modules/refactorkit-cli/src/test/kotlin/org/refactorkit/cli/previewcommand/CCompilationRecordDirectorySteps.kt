package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CClangdSemanticResult
import org.refactorkit.c.CCompilationDatabaseDiscovery
import org.refactorkit.c.CCompilationDatabaseParser
import org.refactorkit.c.CCompilationDatabaseProvider
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.c.ClangdSemanticClient
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildCompilationUnit
import org.refactorkit.core.BuildLanguageEvidence
import org.refactorkit.core.ExternalSemanticProcessManager
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CCompilationRecordDirectorySteps {
    private var root: Path? = null
    private lateinit var source: Path
    private lateinit var header: Path
    private lateinit var workingDirectory: Path
    private lateinit var originals: Map<Path, String>
    private lateinit var captured: CCompilationDatabaseDiscovery
    private lateinit var model: BuildModel
    private var launchFailure: Throwable? = null
    private var definition: CClangdSemanticResult? = null
    private var pid: Long? = null
    private lateinit var expectedCompilationUnits: List<ExpectedCompilationUnit>

    @Given("a captured C compilation database has two differently configured translation units")
    fun twoConfiguredTranslationUnits() {
        val workspace = Files.createTempDirectory("refactorkit-c-build-units-")
        root = workspace
        workingDirectory = workspace.resolve("module")
        val expected = listOf(
            ExpectedCompilationUnit(
                sourceFile = Path.of("module/src/alpha.c"),
                workingDirectory = Path.of("module"),
                arguments = listOf(
                    "clang", "-std=c11", "-DVARIANT=alpha", "-Iinclude/alpha",
                    "--target=x86_64-unknown-linux-gnu", "--sysroot=sysroots/alpha",
                    "-c", "src/alpha.c", "-o", "build/alpha.o",
                ),
                output = "build/alpha.o",
                standard = "c11",
                target = "x86_64-unknown-linux-gnu",
            ),
            ExpectedCompilationUnit(
                sourceFile = Path.of("module/src/beta.c"),
                workingDirectory = Path.of("module"),
                arguments = listOf(
                    "clang", "-std=c17", "-DVARIANT=beta", "-Iinclude/beta",
                    "--target=aarch64-unknown-linux-gnu", "--sysroot=sysroots/beta",
                    "-c", "src/beta.c", "-o", "build/beta.o",
                ),
                output = "build/beta.o",
                standard = "c17",
                target = "aarch64-unknown-linux-gnu",
            ),
        )
        expectedCompilationUnits = expected
        expected.forEach { unit ->
            val source = workspace.resolve(unit.sourceFile)
            Files.createDirectories(source.parent)
            Files.writeString(source, "int ${source.fileName.toString().substringBefore('.')}(void) { return 0; }\n")
            Files.createDirectories(workingDirectory.resolve(unit.arguments.first { it.startsWith("-I") }.removePrefix("-I")))
            Files.createDirectories(workingDirectory.resolve(unit.arguments.first { it.startsWith("--sysroot=") }.substringAfter('=')))
        }
        val rows = expected.map { unit ->
            buildJsonObject {
                put("directory", workingDirectory.toString())
                put("file", workingDirectory.relativize(workspace.resolve(unit.sourceFile)).toString())
                put("arguments", JsonArray(unit.arguments.map(::JsonPrimitive)))
                put("output", unit.output)
            }
        }
        Files.createDirectories(workingDirectory)
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(rows).toString())
        originals = projectFiles(workspace)
    }

    @When("the C Build Model provider projects the compilation database")
    fun projectCompilationDatabase() {
        val workspace = requireNotNull(root)
        model = CCompilationDatabaseProvider().discover(BuildModelRequest(workspace))
    }

    @Then("each Build Model compilation unit retains its exact file directory arguments and language configuration")
    fun compilationUnitsRetainTheirConfiguration() {
        val workspace = requireNotNull(root)
        val sourceSet = model.modules.single().sourceSets.single()
        assertEquals(expectedCompilationUnits.size, sourceSet.compilationUnits.size)
        expectedCompilationUnits.forEach { expected ->
            val actual = sourceSet.compilationUnits.single { it.sourceFile == expected.sourceFile }
            assertEquals(expected.workingDirectory, actual.workingDirectory)
            assertEquals(expected.arguments, actual.arguments)
            assertEquals(expected.output, actual.output)
            val facet = actual.languageFacets.single()
            assertEquals("c", facet.languageId)
            assertEquals(expected.standard, facet.sourceVersion)
            assertEquals(expected.target, facet.targetVersion)
            assertEquals(BuildLanguageEvidence.DECLARED, facet.evidence)
            assertEquals(
                BuildCompilationUnit.fingerprint(expected.sourceFile, expected.workingDirectory, expected.arguments, expected.output),
                actual.configurationFingerprint,
            )
            assertTrue(Regex("[a-f0-9]{64}").matches(actual.configurationFingerprint))
            assertEquals(workspace.resolve(expected.sourceFile).normalize(), workspace.resolve(actual.sourceFile).normalize())
        }
        assertEquals(2, sourceSet.compilationUnits.map(BuildCompilationUnit::id).distinct().size)
    }

    @Then("the partial aggregate reports the conflict without executing commands or changing project inputs")
    fun conflictIsPartialAndReadOnly() {
        val workspace = requireNotNull(root)
        assertEquals(BuildModelStatus.PARTIAL, model.status)
        assertTrue(model.diagnostics.any { it.code == "c.compilationConfigurationConflict" })
        assertEquals(BuildLanguageEvidence.PARTIAL, model.modules.single().sourceSets.single().languageFacets.single().evidence)
        assertEquals("declared", model.attributes["perUnitFlags"])
        assertEquals(originals, projectFiles(workspace))
        expectedCompilationUnits.forEach { unit ->
            assertFalse(Files.exists(workingDirectory.resolve(unit.output)))
        }
    }

    @Given("a C compilation record uses {string} paths")
    fun record(layout: String) {
        val workspace = Files.createTempDirectory("refactorkit-c-compile-directory-")
        root = workspace
        workingDirectory = when (layout) {
            "nested directory", "absolute translation unit" -> workspace.resolve("module")
            "parent-relative inputs" -> workspace.resolve("build/debug")
            else -> workspace
        }
        Files.createDirectories(workingDirectory)
        val sourceText = when (layout) {
            "parent-relative inputs" -> "../../src/main.c"
            "escaping translation unit" -> "../outside/main.c"
            "absolute translation unit" -> workingDirectory.resolve("src/main.c").toString()
            else -> "src/main.c"
        }
        val includeText = if (layout == "parent-relative inputs") "../../include" else "include"
        source = if (layout == "escaping translation unit") workspace.resolve("src/main.c")
            else workingDirectory.resolve(sourceText).normalize()
        header = workingDirectory.resolve(includeText).resolve("api.h").normalize()
        Files.createDirectories(source.parent); Files.createDirectories(header.parent)
        Files.writeString(source, "#include <api.h>\nint main(void) { return api_value(); }\n")
        Files.writeString(header, "static inline int api_value(void) { return 7; }\n")
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val args = listOf(reference.resolve("usr/bin/clang-22").toString(), "-std=c17", "-I$includeText", "-c", sourceText, "-o", "unowned.o")
        val unit = buildJsonObject {
            put("directory", if (layout == "escaping directory") workspace.resolve("../outside").normalize().toString() else workingDirectory.toString())
            put("file", sourceText); put("arguments", JsonArray(args.map(::JsonPrimitive)))
        }
        Files.writeString(workspace.resolve("compile_commands.json"), JsonArray(listOf(unit)).toString())
        originals = projectFiles(workspace)
    }

    @When("the captured C model drives a real semantic header lookup")
    fun capturedHeaderLookup() {
        val workspace = requireNotNull(root)
        captured = CCompilationDatabaseParser().parse(workspace, Files.readAllBytes(workspace.resolve("compile_commands.json")))
        model = CCompilationDatabaseProvider().discover(BuildModelRequest(workspace))
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = workspace, clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"), clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain unavailable: ${discovered.diagnostics}")
        }
        assertTrue(toolchain.provenance.clangVersion.contains("22.1.8"))
        ExternalSemanticProcessManager().use { manager ->
            ClangdSemanticClient(toolchain, manager).use { client ->
                launchFailure = runCatching { client.start(workspace) }.exceptionOrNull()
                if (launchFailure == null) {
                    pid = assertNotNull(client.processProvenance()).pid
                    assertTrue(client.didOpen(source, Files.readString(source)))
                    definition = client.definition(source, 1, 24)
                }
            }
            assertTrue(manager.activeProvenance().isEmpty())
        }
    }

    @Then("translation unit and header paths are {string} relative to the record directory")
    fun expectedIdentity(disposition: String) {
        when (disposition) {
            "admitted" -> {
                val unit = assertIs<CCompilationDatabaseDiscovery.Available>(captured).database.units.single()
                assertEquals(source, unit.file)
                assertEquals(workingDirectory, unit.directory)
                assertEquals(listOf(header.parent), unit.includeDirectories)
                assertEquals(BuildModelStatus.AVAILABLE, model.status)
                val rootPath = requireNotNull(root)
                assertEquals(listOf(rootPath.relativize(source.parent)), model.modules.single().sourceSets.single().sourceRoots)
                assertNull(launchFailure)
                val found = assertIs<CClangdSemanticResult.Found>(definition)
                assertEquals(header, found.definition.file)
                assertEquals(0, found.definition.startLine)
                assertEquals(18, found.definition.startCharacter)
            }
            "refused" -> {
                val refused = assertIs<CCompilationDatabaseDiscovery.Refused>(captured)
                assertTrue(refused.diagnostics.any { it.code == "c.pathOutsideWorkspace" })
                assertEquals(BuildModelStatus.UNAVAILABLE, model.status)
                assertTrue(model.modules.isEmpty())
                assertNotNull(launchFailure)
                assertTrue(launchFailure?.message.orEmpty().contains("c.pathOutsideWorkspace"))
                assertNull(pid); assertNull(definition)
            }
            else -> error("Unexpected disposition: $disposition")
        }
    }

    @Then("captured path queries preserve all project bytes and own no running process")
    fun readOnlyAndReaped() {
        val workspace = requireNotNull(root)
        assertEquals(originals, projectFiles(workspace))
        assertFalse(Files.exists(workspace.resolve(".refactorkit")))
        pid?.let { assertFalse(ProcessHandle.of(it).map { process -> process.isAlive }.orElse(false)) }
    }

    private fun projectFiles(workspace: Path): Map<Path, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }

    private data class ExpectedCompilationUnit(
        val sourceFile: Path,
        val workingDirectory: Path,
        val arguments: List<String>,
        val output: String,
        val standard: String,
        val target: String,
    )

    @After
    fun cleanup() {
        root?.let { workspace ->
            Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

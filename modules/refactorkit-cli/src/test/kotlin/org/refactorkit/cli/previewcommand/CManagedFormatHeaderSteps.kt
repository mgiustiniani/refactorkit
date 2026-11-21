package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CManagedFormatHeaderSteps {
    private var fixture: ManagedFormatHeaderFixture? = null

    @Given("a managed C formatting fixture with {string}")
    fun configure(configuration: String) {
        fixture = ManagedFormatHeaderFixture(configuration)
    }

    @When("its captured C formatting plan is previewed and applied")
    fun exercise() = requireNotNull(fixture).exercise()

    @Then("the ordered-header formatting outcome is {string} with exact project images")
    fun verify(outcome: String) = requireNotNull(fixture).verify(outcome)

    @After
    fun close() {
        fixture?.close()
    }
}

private class ManagedFormatHeaderFixture(private val configuration: String) : AutoCloseable {
    private val configuredTools = Path.of(requireNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        .resolve("usr/bin")
    private val root = Files.createTempDirectory("rk-ordered-format-")
    private val sourcePath = Path.of("main.c")
    private val headerPath = Path.of("z.h")
    private val journal = root.resolve(".refactorkit/transactions")
    private val standard = if (configuration.startsWith("C11")) "c11" else "c17"
    private val includeBlock = "#include \"z.h\"\n#include \"a.h\"\n"
    private val source = includeBlock + "int main(void){return PICKED_VALUE!=7;}\n"
    private val formatted = includeBlock + "int main(void) { return PICKED_VALUE != 7; }\n"
    private var initialImages: Map<Path, String>
    private lateinit var preview: PatchPlan
    private lateinit var applied: ApplyResult

    init {
        val database = buildJsonArray {
            add(buildJsonObject {
                put("directory", root.toString())
                put("file", sourcePath.toString())
                put("arguments", buildJsonArray {
                    listOf(configuredTools.resolve("clang-22").toString(), "-std=$standard", "-c", sourcePath.toString())
                        .forEach { add(it) }
                })
            })
        }
        initialImages = mapOf(
            sourcePath to source,
            headerPath to "#define PICKED_VALUE 7\n",
            Path.of("a.h") to "#ifndef PICKED_VALUE\n#error include order changed\n#endif\n",
            Path.of("compile_commands.json") to database.toString(),
            Path.of(".clang-format") to "BasedOnStyle: LLVM\nSortIncludes: CaseSensitive\n",
        )
        initialImages = when (configuration) {
            "C11 ordered local headers", "C17 ordered local headers", "changed captured header" -> initialImages
            "computed include" -> initialImages + (sourcePath to
                source.replaceFirst("#include \"z.h\"", "#define HEADER \"z.h\"\n#include HEADER"))
            "missing local header" -> initialImages - headerPath
            else -> error("Unknown C formatting fixture $configuration")
        }
        initialImages.forEach { (path, content) -> Files.writeString(root.resolve(path), content) }
    }

    fun exercise() {
        val toolchain = assertIs<ClangToolchainDiscovery.Available>(ClangToolchainDiscoverer().discover(
            ClangToolchainRequest(root, configuredTools.resolve("clang-22"), configuredTools.resolve("clangd"),
                configuredTools.resolve("clang-format")),
        )).toolchain
        val snapshot = CProjectScanner().scan(root)
        CRefactoringFacade(toolchain).use { facade ->
            preview = facade.preview(snapshot, "formatFile", mapOf("file" to sourcePath.toString()))
            assertEquals(PatchStatus.PREVIEW, preview.status, preview.summary)
            assertEquals(initialImages, savedImages())
            assertFalse(Files.exists(journal))
            if (configuration == "changed captured header") {
                Files.writeString(root.resolve(headerPath), "#define PICKED_VALUE 9\n")
            }
            applied = facade.apply(snapshot, preview, ApplyAuthorization.explicit("ordered-format-story"),
                facade.diagnosticsGate(snapshot, preview))
        }
    }

    fun verify(outcome: String) {
        when (outcome) {
            "applied and rolled back" -> verifyRoundTrip()
            "refused before write" -> {
                val refusal = assertIs<ApplyResult.Refused>(applied)
                assertTrue(refusal.diagnostics.any { "c.formatCompilationContextUnavailable" in it.message })
                assertEquals(initialImages, savedImages())
                assertFalse(Files.exists(journal))
            }
            "stale input refused" -> {
                val lease = assertNotNull(preview.authorityLease, preview.warnings.toString())
                assertTrue(lease.requiredFileEvidence.any { it.path == headerPath })
                val refusal = assertIs<ApplyResult.Refused>(applied)
                assertTrue(refusal.diagnostics.any { it.code == "authorityLease.evidenceDrift" })
                assertEquals(initialImages + (headerPath to "#define PICKED_VALUE 9\n"), savedImages())
                assertFalse(Files.exists(journal))
            }
            else -> error("Unknown C formatting outcome $outcome")
        }
    }

    private fun verifyRoundTrip() {
        assertTrue(preview.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") }, preview.warnings.toString())
        assertTrue((preview.diagnosticsBefore + preview.diagnosticsAfterPreview).none {
            it.severity == Diagnostic.Severity.ERROR
        })
        val transaction = assertIs<ApplyResult.Applied>(applied).transaction
        assertEquals(initialImages + (sourcePath to formatted), savedImages())
        assertIs<ApplyResult.Applied>(PatchEngine(root).rollback(transaction))
        assertEquals(initialImages, savedImages())
    }

    private fun savedImages(): Map<Path, String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && !root.relativize(it).startsWith(".refactorkit") }.toList()
            .associate { root.relativize(it) to Files.readString(it) }
    }

    override fun close() {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

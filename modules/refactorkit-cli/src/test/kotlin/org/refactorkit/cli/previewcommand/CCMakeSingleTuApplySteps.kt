package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.opentest4j.TestAbortedException
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CCMakeSingleTuApplySteps {
    private var root: Path? = null
    private lateinit var context: String
    private lateinit var originalSource: ByteArray
    private lateinit var originalManifest: ByteArray
    private lateinit var databaseBefore: ByteArray
    private lateinit var databaseAtApply: ByteArray
    private lateinit var outcome: ApplyResult

    @Given("an isolated literal CMake executable with a {string} compilation context")
    fun project(mode: String) {
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val clang = reference.resolve("usr/bin/clang-22")
        val clangd = reference.resolve("usr/bin/clangd")
        val format = reference.resolve("usr/bin/clang-format")
        if (listOf(clang, clangd, format).any { !Files.isExecutable(it) }) {
            throw TestAbortedException("Configured reference Clang 22.1.8 toolchain is incomplete; CMake apply case unexercised")
        }
        context = mode
        val dir = Files.createTempDirectory("refactorkit-cmake-single-tu-")
        root = dir
        originalSource = "#ifndef ENABLED\n#error captured define missing\n#endif\nint main(void) { return 0; }\n".toByteArray()
        originalManifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app main.c)
""".toByteArray()
        val dialect = if (mode == "c11") "c11" else "c17"
        val flag = if (mode == "response-file") "\"@${dir.resolve("danger.rsp")}\"," else ""
        databaseBefore = """[{"directory":"$dir","file":"main.c","arguments":["$clang","-std=$dialect","-D","ENABLED=1",${flag}"-c","main.c"]}]""".toByteArray()
        Files.write(dir.resolve("main.c"), originalSource)
        Files.write(dir.resolve("CMakeLists.txt"), originalManifest)
        Files.write(dir.resolve("compile_commands.json"), databaseBefore)
        Files.writeString(dir.resolve("danger.rsp"), "-Xclang -load /nonexistent/plugin.so\n")
        databaseAtApply = databaseBefore
    }

    @When("the source move is previewed and explicitly approved with its captured context")
    fun apply() {
        val dir = requireNotNull(root)
        val reference = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val toolchain = when (val discovered = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir, clangExecutable = reference.resolve("usr/bin/clang-22"),
            clangdExecutable = reference.resolve("usr/bin/clangd"), clangFormatExecutable = reference.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> discovered.toolchain
            is ClangToolchainDiscovery.Refused -> error(
                "Configured reference Clang toolchain refused: ${discovered.diagnostics}")
        }
        val snapshot = CProjectScanner().scan(dir)
        CRefactoringFacade(toolchain).use { facade ->
            val plan = facade.preview(snapshot, "moveSource", mapOf("file" to "main.c", "target" to "src/main.c"))
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            assertTrue(plan.workspaceEdit.affectedFiles().contains(Path.of("CMakeLists.txt")))
            val applyPlan = if (context == "runtime-identity") {
                val lease = assertNotNull(plan.authorityLease)
                val runtimeIdentity = assertNotNull(toolchain.provenance.nativeRuntimeIdentity)
                assertEquals(runtimeIdentity, lease.attributes["c.nativeRuntimeIdentity"])
                plan.copy(authorityLease = OperationAuthorityLease(
                    kind = lease.kind,
                    operation = lease.operation,
                    snapshotHash = lease.snapshotHash,
                    evidenceHash = lease.evidenceHash,
                    evidenceCompleteness = lease.evidenceCompleteness,
                    workspaceEditSha256 = lease.workspaceEditSha256,
                    requiredClasspathEvidence = lease.requiredClasspathEvidence,
                    requiredFileEvidence = lease.requiredFileEvidence,
                    attributes = lease.attributes + ("c.nativeRuntimeIdentity" to "0".repeat(64)),
                ))
            } else plan
            val gate = facade.diagnosticsGate(snapshot, applyPlan)
            when (context) {
                "changed-db" -> {
                    databaseAtApply = databaseBefore + " \n".toByteArray()
                    Files.write(dir.resolve("compile_commands.json"), databaseAtApply)
                }
                "nested" -> {
                    Files.createDirectories(dir.resolve("src/other"))
                    Files.writeString(dir.resolve("src/other/CMakeLists.txt"),
                        "add_library(other STATIC ../../main.c)\n")
                }
                "alternate-db" -> {
                    Files.createDirectories(dir.resolve("build"))
                    Files.write(dir.resolve("build/compile_commands.json"), databaseBefore)
                }
            }
            outcome = facade.apply(snapshot, applyPlan, ApplyAuthorization.explicit("c-single-tu-story"), gate)
        }
    }

    @Then("managed apply is {string} without executing the recorded build command")
    fun result(disposition: String) {
        val dir = requireNotNull(root)
        when (disposition) {
            "applied" -> {
                assertIs<ApplyResult.Applied>(outcome)
                assertFalse(Files.exists(dir.resolve("main.c")))
                assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("src/main.c")))
                assertEquals("""cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_executable(app src/main.c)
""", Files.readString(dir.resolve("CMakeLists.txt")))
            }
            "refused" -> {
                val refusal = assertIs<ApplyResult.Refused>(outcome)
                val code = when (context) {
                    "runtime-identity" -> "c.nativeRuntimeIdentityChanged"
                    "response-file" -> "c.compilationCommandUnsupported"
                    "changed-db" -> "c.compilationDatabaseChanged"
                    else -> "c.compilationContextUnavailable"
                }
                val expectedRefusal = if (context == "changed-db") {
                    refusal.diagnostics.any { it.code in setOf("snapshot.scopeChanged", "c.compilationDatabaseChanged") }
                } else {
                    refusal.diagnostics.any { it.code == "diagnostics.unavailable" && it.message.contains(code) }
                }
                assertTrue(expectedRefusal, refusal.diagnostics.toString())
            }
            else -> error("Unknown disposition: $disposition")
        }
        assertFalse(Files.exists(dir.resolve("main.o")), "captured build command must never run")
        assertContentEquals(databaseAtApply, Files.readAllBytes(dir.resolve("compile_commands.json")))
    }

    @Then("applied source and manifest bytes roll back exactly or refusal leaves no transaction")
    fun rollbackOrRefusal() {
        val dir = requireNotNull(root)
        if (outcome is ApplyResult.Applied) {
            val result = PatchEngine(dir).rollback((outcome as ApplyResult.Applied).transaction)
            assertIs<ApplyResult.Applied>(result)
            assertFalse(Files.exists(dir.resolve("src/main.c")))
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
        } else {
            assertContentEquals(originalSource, Files.readAllBytes(dir.resolve("main.c")))
            assertContentEquals(originalManifest, Files.readAllBytes(dir.resolve("CMakeLists.txt")))
            assertFalse(Files.exists(dir.resolve("src/main.c")))
            assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
        }
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

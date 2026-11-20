package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CCompilerDiagnostics
import org.refactorkit.c.CDiagnosticsResult
import org.refactorkit.c.CMovePlanner
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.CTokenType
import org.refactorkit.c.CTokenizer
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CCMakeTokenPasteSteps {
    private var root: Path? = null
    private lateinit var state: String
    private lateinit var disposition: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var staged: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var expectedManifest: String
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private val sources = listOf("src/app/main.c", "src/app/helper.c")

    @Given("an isolated CMake token-paste fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("no-paste-trivia", "classic-paste", "spliced-paste",
            "digraph-paste", "chained-paste", "unused-moved-paste"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-token-paste-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        write("src/app/main.c", """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_paste_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201112L, "main_dialect_not_exact");
${if (state == "unused-moved-paste") "#define RK_UNUSED(a,b) a ## b\n" else ""}int main(void) { return 0; }
""")
        val body = if (state in setOf("no-paste-trivia", "unused-moved-paste")) """#
#
#define RK_NORMAL(x) ((x) + 1)
/* ## %:%: __has_include are only comment trivia. */
static const char *paste_hint = "## %:%: __has_include_next";
#pragma message("rk_paste_no_probe")
int helper(void) { (void)paste_hint; return RK_NORMAL(HELPER_ONLY); }
""" else {
            val operator = when (state) {
                "spliced-paste" -> "#\\\n#"
                "digraph-paste" -> "%:%:"
                else -> "##"
            }
            val definitions = if (state == "chained-paste")
                "#define RK_JOIN_I(a,b) a ## b\n#define RK_JOIN(a,b) RK_JOIN_I(a,b)\n#define RK_PREFIX __has_\n"
            else "#define RK_JOIN(a,b) a $operator b\n"
            val prefix = if (state == "chained-paste") "RK_PREFIX" else "__has_"
            definitions + "#if RK_JOIN($prefix, include)(\"main.c\")\n" +
                "#pragma message(\"rk_paste_present\")\nint helper(void) { return HELPER_ONLY; }\n#else\n" +
                "#pragma message(\"rk_paste_absent\")\nint helper(void) { return HELPER_ONLY + 1; }\n#endif\n"
        }
        write("src/app/helper.c", """#include <stdio.h>
#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_paste_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
$body""")
        fun manifest(main: String) = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n" +
            "add_executable(app $main)\nadd_library(support STATIC src/app/helper.c)\n"
        write("CMakeLists.txt", manifest("src/app/main.c"))
        expectedManifest = manifest("src/app/moved/main.c")
        val qualifiedToolchainRoot = Path.of(assertNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
            "The configured LLVM 22.1.8 qualification toolchain is required",
        ))
        toolchain = when (val discovery = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-22"),
            clangdExecutable = qualifiedToolchainRoot.resolve("usr/bin/clangd"),
            clangFormatExecutable = qualifiedToolchainRoot.resolve("usr/bin/clang-format"),
        ))) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain required: ${discovery.diagnostics}")
        }
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        write("compile_commands.json", buildJsonArray {
            sources.forEach { source -> add(buildJsonObject {
                put("directory", dir.toString()); put("file", source)
                put("arguments", buildJsonArray {
                    add(toolchain.clangExecutable.toString())
                    flags(source).filter { it != "-pedantic-errors" }.forEach { add(it) }
                    add("-c"); add(source)
                })
            }) }
        }.toString())
    }

    @When("that token-paste fixture previews its main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(sources.toSet(), snapshot.files.map { it.path.toString() }.toSet())
        (snapshot.files.map { it.content } + sources.map { flags(it).joinToString("\n") }).forEach { text ->
            assertFalse(CTokenizer().tokenize(text).any { it.type == CTokenType.IDENTIFIER &&
                it.text in setOf("__has_include", "__has_include_next") }, "operator must come from paste, not a direct token")
        }
        val structural = CMovePlanner().preview(snapshot, Path.of("src/app/main.c"), Path.of("src/app/moved/main.c"))
        assertEquals(PatchStatus.PREVIEW, structural.status, structural.summary)
        staged = WorkspaceEditSimulator.apply(snapshot, structural.workspaceEdit)
        val compiler = CCompilerDiagnostics(toolchain)
        val observations = listOf(snapshot, staged).mapIndexed { index, candidate ->
            val diagnostics = sources.map { original ->
                val source = if (index == 1 && original.endsWith("main.c")) "src/app/moved/main.c" else original
                val checked = assertIs<CDiagnosticsResult.Available>(compiler.analyze(candidate, Path.of(source), flags(original)))
                assertFalse(checked.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }, checked.diagnostics.toString())
                assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                    checked.processProvenance?.executableSha256)
                checked.diagnostics
            }.flatten()
            val marker = expectedMarker(index == 1)
            assertTrue(diagnostics.any { it.message.contains(marker) &&
                it.location?.path == dir.resolve("src/app/helper.c") }, diagnostics.toString())
            buildJsonObject {
                put("phase", if (index == 0) "baseline" else "staged"); put("expectedObservedMarker", marker)
                put("compilerSha256", toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
                put("diagnostics", buildJsonArray { diagnostics.forEach { d -> add(buildJsonObject {
                    put("message", d.message); put("severity", d.severity.name); put("path", d.location?.path.toString())
                }) } })
            }
        }
        System.getenv("RK_C_CONSUMER_OBSERVATIONS")?.let { folder ->
            val target = Path.of(folder).resolve("$state.json")
            assertFalse(Files.exists(target), "do not overwrite prior observations")
            Files.createDirectories(target.parent)
            Files.writeString(target, buildJsonObject {
                put("state", state); put("directSourceAndFlagOperatorAbsent", true)
                put("observations", buildJsonArray { observations.forEach { add(it) } })
            }.toString())
        }
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(snapshot, "moveSource", mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @Then("its source token-paste disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertTrue(plan.diagnosticsBefore.any { it.message.contains(expectedMarker(false)) })
        if (disposition == "applicable") {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("source-token-paste-refused-v1", lease.attributes["c.consumerPastePolicy"])
            assertEquals("availability-define-tokens-refused-v1", lease.attributes["c.consumerDefinePolicy"])
            assertEquals("direct-availability-tokens-refused-v1", lease.attributes["c.consumerProbePolicy"])
            val fields = listOf("source-token-paste-refused-v1", "captured-project-tus-v1", "2", snapshot.hash, staged.hash,
                hash(originalFiles.getValue("compile_commands.json")),
                toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                assertNotNull(lease.attributes["c.targetOwnershipSha256"]))
            assertEquals(hash(fields.joinToString("|").toByteArray(Charsets.UTF_8)),
                lease.attributes["c.consumerPastePolicySha256"], "paste policy must bind actual inputs")
            assertTrue(plan.diagnosticsAfterPreview.any { it.message.contains(expectedMarker(true)) })
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals("refused-staged", disposition)
            assertEquals(PatchStatus.REFUSED, plan.status, "compiler-clean unmodeled token paste must not get authority")
            assertEquals("c.previewDiagnosticsUnavailable", plan.refusalCode)
            assertNull(plan.authorityLease)
            assertFalse(plan.requiresUserApproval)
            assertTrue(plan.workspaceEdit.edits.isEmpty() && plan.affectedFiles.isEmpty())
            assertTrue(plan.diagnosticsAfterPreview.any { it.severity == Diagnostic.Severity.ERROR &&
                it.message.contains("c.compilationConsumerUnavailable") && it.message.contains("TU '${responsibleSource()}'") &&
                it.message.contains("source token-paste expansion is not admitted") }, plan.diagnosticsAfterPreview.toString())
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained token-paste policy permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-token-paste-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-token-paste-policy")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.consumerPastePolicy",
                    lease.attributes + ("c.consumerPastePolicy" to "source-token-paste-refused-v0"),
                    lease.attributes + ("c.consumerPastePolicySha256" to "0".repeat(64))).forEach { attributes ->
                    val altered = plan.copy(authorityLease = OperationAuthorityLease(
                        kind = lease.kind, operation = lease.operation, snapshotHash = lease.snapshotHash,
                        evidenceHash = lease.evidenceHash, evidenceCompleteness = lease.evidenceCompleteness,
                        workspaceEditSha256 = lease.workspaceEditSha256,
                        requiredClasspathEvidence = lease.requiredClasspathEvidence,
                        requiredFileEvidence = lease.requiredFileEvidence, attributes = attributes,
                    ))
                    val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, altered, authorization, callerGate))
                    assertTrue(denied.diagnostics.any { it.code == "diagnostics.unavailable" &&
                        it.message.contains("captured identity") }, denied.diagnostics.toString())
                    assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
                    assertOriginalFiles()
                }
                val applied = assertIs<ApplyResult.Applied>(facade.apply(snapshot, plan, authorization, callerGate))
                try {
                    assertTrue(Files.exists(dir.resolve(".refactorkit/transactions")))
                    assertFalse(Files.exists(dir.resolve("src/app/main.c")))
                    assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(dir.resolve("src/app/moved/main.c")))
                    assertEquals(expectedManifest, Files.readString(dir.resolve("CMakeLists.txt")))
                    assertContentEquals(originalFiles.getValue("src/app/helper.c"), Files.readAllBytes(dir.resolve("src/app/helper.c")))
                } finally {
                    assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
                }
            } else {
                val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, plan, authorization, callerGate))
                assertTrue(denied.diagnostics.any { it.code == "plan.notPreview" }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
    }

    private fun flags(source: String): List<String> = if (source.endsWith("main.c"))
        listOf("-std=c11", "-pedantic-errors", "-DMAIN_ONLY=3")
    else listOf("-std=c17", "-pedantic-errors", "-DHELPER_ONLY=7")

    private fun expectedMarker(isStaged: Boolean): String = if (state in setOf("no-paste-trivia", "unused-moved-paste"))
        "rk_paste_no_probe" else if (isStaged) "rk_paste_absent" else "rk_paste_present"

    private fun responsibleSource(): String = if (state == "unused-moved-paste") "src/app/moved/main.c" else "src/app/helper.c"

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun assertOriginalFiles() {
        val dir = requireNotNull(root)
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)), path) }
        if (::snapshot.isInitialized) assertEquals(snapshot.hash, CProjectScanner().scan(dir).hash)
    }

    @After
    fun cleanup() {
        root?.let { dir -> Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } }
    }
}

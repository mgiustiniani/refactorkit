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

class CCMakePathMacroSteps {
    private var root: Path? = null
    private lateinit var state: String
    private lateinit var disposition: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var staged: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var expectedManifest: String
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private val old = "src/app/main.c"
    private val next = "src/app/moved/entry.c"
    private val helper = "src/app/helper.c"
    private val pathIdentifiers = setOf("__FILE__", "__FILE_NAME__", "__BASE_FILE__", "__builtin_FILE")

    @Given("an isolated CMake moved-path-macro fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("ordinary-spliced-path-trivia", "lf-file-macro", "crlf-base-file-macro",
            "cr-file-name-macro", "chained-file-macro", "unused-builtin-file-macro"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-path-macro-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        val body = when (state) {
            "ordinary-spliced-path-trivia" ->
                "/* __FI\\\nLE__ __BASE_FI\\\r\nLE__ __builtin_FI\\\rLE are trivia. */\n" +
                "static const char hint[] = \"__FILE_NA\\\r\nME__\";\n" +
                "int __FILEISH__ = MAIN_ONLY;\n_Static_assert(sizeof(hint) > 1, \"trivia_must_stay_string\");\n" +
                "#pragma message(\"rk_path_trivia\")\n"
            "unused-builtin-file-macro" ->
                "#define RK_UNEXPANDED() __builtin_FI\\\r\nLE()\n#pragma message(\"rk_path_unused_builtin\")\n"
            else -> {
                val operand = when (state) {
                    "lf-file-macro" -> "__FI\\\nLE__"
                    "crlf-base-file-macro" -> "__BASE_FI\\\r\nLE__"
                    "cr-file-name-macro" -> "__FILE_NA\\\rME__"
                    else -> "RK_CHAIN"
                }
                (if (state == "chained-file-macro") "#define RK_PATH __FI\\\nLE__\n#define RK_CHAIN RK_PATH\n" else "") +
                    "#define RK_EMIT_STEP(x) _Pragma(#x)\n#define RK_EMIT(x) RK_EMIT_STEP(x)\nRK_EMIT(message($operand))\n"
            }
        }
        val version = if (state == "crlf-base-file-macro") "201710L" else "201112L"
        write(old, """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_path_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == $version, "main_dialect_not_exact");
${body}int main(void) { return 0; }
""")
        write(helper, """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_path_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
int helper(void) { return HELPER_ONLY; }
""")
        fun manifest(main: String) = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n" +
            "add_executable(app $main)\nadd_library(support STATIC $helper)\n"
        write("CMakeLists.txt", manifest(old))
        expectedManifest = manifest(next)
        val qualifiedToolchainRoot = Path.of(requireNotNull(
            System.getProperty("refactorkit.c.qualificationToolchain"),
        ) { "The configured LLVM 22.1.8 qualification toolchain is required" })
        val qualifiedClang = qualifiedToolchainRoot.resolve("usr/bin/clang-22")
        val qualifiedClangd = qualifiedToolchainRoot.resolve("usr/bin/clangd")
        val qualifiedClangFormat = qualifiedToolchainRoot.resolve("usr/bin/clang-format")
        toolchain = when (val discovery = ClangToolchainDiscoverer().discover(ClangToolchainRequest(
            workspaceRoot = dir,
            clangExecutable = qualifiedClang,
            clangdExecutable = qualifiedClangd,
            clangFormatExecutable = qualifiedClangFormat,
        ))) {
            is ClangToolchainDiscovery.Available -> discovery.toolchain
            is ClangToolchainDiscovery.Refused -> error("Reference toolchain required: ${discovery.diagnostics}")
        }
        assertEquals(qualifiedClang.toRealPath(), toolchain.clangExecutable.toRealPath())
        assertEquals(qualifiedClangd.toRealPath(), toolchain.clangdExecutable.toRealPath())
        assertEquals(qualifiedClangFormat.toRealPath(), toolchain.clangFormatExecutable.toRealPath())
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        write("compile_commands.json", buildJsonArray {
            listOf(old, helper).forEach { source -> add(buildJsonObject {
                put("directory", dir.toString()); put("file", source)
                put("arguments", buildJsonArray {
                    add(toolchain.clangExecutable.toString())
                    flags(source).filter { it != "-pedantic-errors" }.forEach { add(it) }
                    add("-c"); add(source)
                })
            }) }
        }.toString())
    }

    @When("that moved-path-macro fixture previews its main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(setOf(old, helper), snapshot.files.map { it.path.toString() }.toSet())
        val main = snapshot.files.single { it.path == Path.of(old) }.content
        assertFalse(Regex("\\b(__FILE__|__FILE_NAME__|__BASE_FILE__|__builtin_FILE|__has_include)\\b")
            .containsMatchIn(main), "fixture must evade the original raw path guard")
        val prepared = main.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", "")
        val detected = CTokenizer().tokenize(prepared).filter { it.type == CTokenType.IDENTIFIER && it.text in pathIdentifiers }
        assertEquals(if (state == "ordinary-spliced-path-trivia") 0 else 1, detected.size)
        assertEquals(when (state) {
            "ordinary-spliced-path-trivia" -> emptyList()
            "crlf-base-file-macro" -> listOf("__BASE_FILE__")
            "cr-file-name-macro" -> listOf("__FILE_NAME__")
            "unused-builtin-file-macro" -> listOf("__builtin_FILE")
            else -> listOf("__FILE__")
        }, detected.map { it.text })
        val structural = CMovePlanner().preview(snapshot, Path.of(old), Path.of(next))
        assertEquals(PatchStatus.PREVIEW, structural.status, structural.summary)
        staged = WorkspaceEditSimulator.apply(snapshot, structural.workspaceEdit)
        val compiler = CCompilerDiagnostics(toolchain)
        val observations = listOf(snapshot, staged).mapIndexed { index, candidate ->
            val isStaged = index == 1
            val moved = if (isStaged) next else old
            val diagnostics = listOf(old, helper).map { original ->
                val file = if (isStaged && original == old) next else original
                val checked = assertIs<CDiagnosticsResult.Available>(compiler.analyze(candidate, Path.of(file), flags(original)))
                assertFalse(checked.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }, checked.diagnostics.toString())
                assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                    checked.processProvenance?.executableSha256)
                checked.diagnostics
            }.flatten()
            val marker = expectedMarker(isStaged)
            val observed = diagnostics.single { it.severity == Diagnostic.Severity.WARNING &&
                it.message == marker || it.severity == Diagnostic.Severity.WARNING && it.message.endsWith(marker) }
            assertEquals(dir.resolve(moved), observed.location?.path, "path-expansion warning belongs to rebased workspace TU")
            buildJsonObject {
                put("phase", if (isStaged) "staged" else "baseline"); put("expectedValueSuffixOrMarker", marker)
                put("observedValueOrMarker", observed.message.removeSuffix(" [-W#pragma-messages]"))
                put("workspaceSource", moved)
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
                put("state", state); put("rawPathIdentifierAbsent", true); put("preparedPathIdentifierCount", detected.size)
                put("exercisedPathExpansion", state !in setOf("ordinary-spliced-path-trivia", "unused-builtin-file-macro"))
                put("observations", buildJsonArray { observations.forEach { add(it) } })
            }.toString())
        }
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(snapshot, "moveSource", mapOf("file" to old, "target" to next))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve(next)))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @Then("its managed moved-path-macro disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(plan.requiresUserApproval && plan.workspaceEdit.edits.size == 2 && plan.affectedFiles.isNotEmpty(),
            "structural preview remains inspectable, not falsely relabeled staged REFUSED")
        if (disposition == "applicable") {
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("prepared-moved-source-path-identifiers-refused-v1", lease.attributes["c.movedPathMacroPolicy"])
            assertEquals("lf-crlf-cr-before-splice-lex-v1", lease.attributes["c.consumerNewlinePolicy"])
            val fields = listOf("prepared-moved-source-path-identifiers-refused-v1", "captured-project-tus-v1", "2",
                snapshot.hash, staged.hash, hash(originalFiles.getValue("compile_commands.json")),
                toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                assertNotNull(lease.attributes["c.targetOwnershipSha256"]))
            assertEquals(hash(fields.joinToString("|").toByteArray(Charsets.UTF_8)), lease.attributes["c.movedPathMacroPolicySha256"])
            assertTrue(plan.diagnosticsBefore.any { it.message == expectedMarker(false) })
            assertTrue(plan.diagnosticsAfterPreview.any { it.message == expectedMarker(true) })
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals("unavailable-context", disposition)
            assertNull(plan.authorityLease, "hidden prepared path identifier must never lease moved-source authority")
            assertTrue(plan.warnings.any {
                it == "c.previewDiagnosticsUnavailable: c.compilationContextUnavailable: moved source/header 'src/app/main.c' has include or path-sensitive behavior"
            }, plan.warnings.toString())
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty(),
                "capture refusal is not evaluated compiler/staged evidence")
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained moved-path-macro policy permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-path-macro-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-moved-path-macro-policy")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.movedPathMacroPolicy",
                    lease.attributes + ("c.movedPathMacroPolicy" to "prepared-moved-source-path-identifiers-refused-v0"),
                    lease.attributes + ("c.movedPathMacroPolicySha256" to "0".repeat(64))).forEach { attributes ->
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
                    assertFalse(Files.exists(dir.resolve(old)))
                    assertContentEquals(originalFiles.getValue(old), Files.readAllBytes(dir.resolve(next)))
                    assertEquals(expectedManifest, Files.readString(dir.resolve("CMakeLists.txt")))
                    assertContentEquals(originalFiles.getValue(helper), Files.readAllBytes(dir.resolve(helper)))
                    assertContentEquals(originalFiles.getValue("compile_commands.json"), Files.readAllBytes(dir.resolve("compile_commands.json")))
                } finally {
                    assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
                }
            } else {
                val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, plan, authorization, callerGate))
                assertTrue(denied.diagnostics.any {
                    it.code == "diagnostics.unavailable" &&
                        it.message == "Diagnostics gate 'clang-cmake-single-tu-v1' failed: c.compilationContextUnavailable: moved source/header 'src/app/main.c' has include or path-sensitive behavior"
                }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve(next)))
    }

    private fun flags(source: String): List<String> = if (source == old)
        listOf(if (state == "crlf-base-file-macro") "-std=c17" else "-std=c11", "-pedantic-errors", "-DMAIN_ONLY=3")
    else listOf("-std=c17", "-pedantic-errors", "-DHELPER_ONLY=7")

    private fun expectedMarker(isStaged: Boolean): String = (when (state) {
        "ordinary-spliced-path-trivia" -> "rk_path_trivia"
        "unused-builtin-file-macro" -> "rk_path_unused_builtin"
        "cr-file-name-macro" -> if (isStaged) "entry.c" else "main.c"
        else -> "/" + if (isStaged) next else old
    }) + " [-W#pragma-messages]"

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

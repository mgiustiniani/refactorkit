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

class CCMakePathDefineMacroSteps {
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

    @Given("an isolated CMake compiler-path-define fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("ordinary-define-path-trivia", "joined-file-alias", "separated-base-alias",
            "chained-file-name-alias", "unused-moved-builtin-define", "unused-other-owner-file-define"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-path-define-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        val body = when (state) {
            "ordinary-define-path-trivia" ->
                "// PATH_HINT and __FILEISH__ are ordinary trivia.\n" +
                "static const char hint[] = \"PATH_HINT __FILEISH__\";\n" +
                "_Static_assert(PATH_COUNT == 11 && sizeof(hint) > 1, \"main_extra_flags_not_exact\");\n" +
                "#pragma message(\"rk_path_define_ordinary\")\n"
            "unused-moved-builtin-define" -> "#pragma message(\"rk_path_define_unused_moved\")\n"
            "unused-other-owner-file-define" -> "#pragma message(\"rk_path_define_unused_other\")\n"
            else -> "#define RK_EMIT_STEP(x) _Pragma(#x)\n#define RK_EMIT(x) RK_EMIT_STEP(x)\nRK_EMIT(message(PATH_ALIAS))\n"
        }
        val version = if (state == "separated-base-alias") "201710L" else "201112L"
        write(old, """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_path_define_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == $version, "main_dialect_not_exact");
${body}int main(void) { return 0; }
""")
        val helperExtra = if (state == "ordinary-define-path-trivia")
            "_Static_assert(HELPER_COUNT == 17, \"helper_extra_flags_not_exact\");\n" else ""
        write(helper, """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_path_define_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
${helperExtra}int helper(void) { return HELPER_ONLY; }
""")
        fun manifest(main: String) = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n" +
            "add_executable(app $main)\nadd_library(support STATIC $helper)\n"
        write("CMakeLists.txt", manifest(old))
        expectedManifest = manifest(next)
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
            listOf(old, helper).forEach { source -> add(buildJsonObject {
                put("directory", dir.toString()); put("file", source)
                put("arguments", buildJsonArray {
                    add(toolchain.clangExecutable.toString())
                    flags(source).filter { it != "-pedantic-errors" }.forEach { flag ->
                        if (state == "separated-base-alias" && flag == "-DPATH_ALIAS=__BASE_FILE__") {
                            add("-D"); add("PATH_ALIAS=__BASE_FILE__")
                        } else add(flag)
                    }
                    add("-c"); add(source)
                })
            }) }
        }.toString())
    }

    @When("that compiler-path-define fixture previews its main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(setOf(old, helper), snapshot.files.map { it.path.toString() }.toSet())
        val sourceCount = snapshot.files.sumOf { watchedTokens(it.content) }
        assertEquals(0, sourceCount, "no source-path guard must substitute for the captured define origin")
        val contexts = listOf(old, helper).associateWith { flags(it) }
        val defineCount = contexts.values.flatten().filter { it.startsWith("-D") }.sumOf { watchedTokens(it.substring(2)) }
        assertEquals(if (state == "ordinary-define-path-trivia") 0 else 1, defineCount)
        val owner = if (state == "unused-other-owner-file-define") helper else old
        if (defineCount == 1) {
            assertEquals(1, contexts.getValue(owner).filter { it.startsWith("-D") }.sumOf { watchedTokens(it.substring(2)) })
        }
        if (state == "separated-base-alias") {
            val raw = originalFiles.getValue("compile_commands.json").toString(Charsets.UTF_8)
            assertTrue(raw.contains("\"-D\",\"PATH_ALIAS=__BASE_FILE__\""))
            assertFalse(raw.contains("\"-DPATH_ALIAS=__BASE_FILE__\""))
        }
        val structural = CMovePlanner().preview(snapshot, Path.of(old), Path.of(next))
        assertEquals(PatchStatus.PREVIEW, structural.status, structural.summary)
        staged = WorkspaceEditSimulator.apply(snapshot, structural.workspaceEdit)
        val compiler = CCompilerDiagnostics(toolchain)
        val observations = listOf(snapshot, staged).mapIndexed { index, candidate ->
            val isStaged = index == 1
            val moved = if (isStaged) next else old
            val diagnostics = contexts.map { (original, flags) ->
                val file = if (isStaged && original == old) next else original
                val checked = assertIs<CDiagnosticsResult.Available>(compiler.analyze(candidate, Path.of(file), flags))
                assertFalse(checked.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }, checked.diagnostics.toString())
                assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                    checked.processProvenance?.executableSha256)
                checked.diagnostics
            }.flatten()
            val marker = expectedMarker(isStaged)
            val observed = diagnostics.single { it.severity == Diagnostic.Severity.WARNING && it.message.endsWith(marker) }
            assertEquals(dir.resolve(moved), observed.location?.path)
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
                put("state", state); put("sourcePathIdentifierCount", sourceCount); put("definePathIdentifierCount", defineCount)
                put("pathDefineOwner", if (defineCount == 0) "none" else owner)
                put("exercisedPathExpansion", state in setOf("joined-file-alias", "separated-base-alias", "chained-file-name-alias"))
                put("separatedRawDefine", state == "separated-base-alias")
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

    @Then("its managed compiler-path-define disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertTrue(plan.diagnosticsBefore.any { it.message.endsWith(expectedMarker(false)) })
        if (disposition == "applicable") {
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("captured-define-path-identifiers-refused-v1", lease.attributes["c.consumerPathDefinePolicy"])
            assertEquals("prepared-moved-source-path-identifiers-refused-v1", lease.attributes["c.movedPathMacroPolicy"])
            val fields = listOf("captured-define-path-identifiers-refused-v1", "captured-project-tus-v1", "2",
                snapshot.hash, staged.hash, hash(originalFiles.getValue("compile_commands.json")),
                toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                assertNotNull(lease.attributes["c.targetOwnershipSha256"]))
            assertEquals(hash(fields.joinToString("|").toByteArray(Charsets.UTF_8)), lease.attributes["c.consumerPathDefinePolicySha256"])
            assertTrue(plan.diagnosticsAfterPreview.any { it.message.endsWith(expectedMarker(true)) })
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals("refused-staged", disposition)
            assertEquals(PatchStatus.REFUSED, plan.status, "compiler-clean captured path define must not lease authority")
            assertEquals("c.previewDiagnosticsUnavailable", plan.refusalCode)
            assertNull(plan.authorityLease)
            assertFalse(plan.requiresUserApproval)
            assertTrue(plan.workspaceEdit.edits.isEmpty() && plan.affectedFiles.isEmpty())
            val owner = if (state == "unused-other-owner-file-define") helper else next
            assertTrue(plan.diagnosticsAfterPreview.any { it.severity == Diagnostic.Severity.ERROR &&
                it.message.contains("c.compilationConsumerUnavailable") && it.message.contains("TU '$owner'") &&
                it.message.contains("captured compiler-define path macro") }, plan.diagnosticsAfterPreview.toString())
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained compiler-path-define policy permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-path-define-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-captured-path-define-policy")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.consumerPathDefinePolicy",
                    lease.attributes + ("c.consumerPathDefinePolicy" to "captured-define-path-identifiers-refused-v0"),
                    lease.attributes + ("c.consumerPathDefinePolicySha256" to "0".repeat(64))).forEach { attributes ->
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
                assertTrue(denied.diagnostics.any { it.code == "plan.notPreview" }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve(next)))
    }

    private fun flags(source: String): List<String> {
        val base = if (source == old)
            listOf(if (state == "separated-base-alias") "-std=c17" else "-std=c11", "-pedantic-errors", "-DMAIN_ONLY=3")
        else listOf("-std=c17", "-pedantic-errors", "-DHELPER_ONLY=7")
        val extra = if (source == old) when (state) {
            "ordinary-define-path-trivia" -> listOf("-DPATH_COUNT=11", "-DPATH_HINT=__FILEISH__")
            "joined-file-alias" -> listOf("-DPATH_ALIAS=__FILE__")
            "separated-base-alias" -> listOf("-DPATH_ALIAS=__BASE_FILE__")
            "chained-file-name-alias" -> listOf("-DPATH_FIRST=__FILE_NAME__", "-DPATH_ALIAS=PATH_FIRST")
            "unused-moved-builtin-define" -> listOf("-DPATH_ALIAS=__builtin_FILE")
            else -> emptyList()
        } else when (state) {
            "ordinary-define-path-trivia" -> listOf("-DHELPER_COUNT=17", "-DHELPER_HINT=__BASE_FILEISH__")
            "unused-other-owner-file-define" -> listOf("-DHELPER_PATH=__FILE__")
            else -> emptyList()
        }
        return base + extra
    }

    private fun watchedTokens(content: String): Int {
        val prepared = content.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", "")
        return CTokenizer().tokenize(prepared).count { it.type == CTokenType.IDENTIFIER && it.text in pathIdentifiers }
    }

    private fun expectedMarker(isStaged: Boolean): String = (when (state) {
        "ordinary-define-path-trivia" -> "rk_path_define_ordinary"
        "unused-moved-builtin-define" -> "rk_path_define_unused_moved"
        "unused-other-owner-file-define" -> "rk_path_define_unused_other"
        "chained-file-name-alias" -> if (isStaged) "entry.c" else "main.c"
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

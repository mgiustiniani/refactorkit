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
import org.refactorkit.c.CIncludeDirectiveParser
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

class CCMakeDirectiveTokenSteps {
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
    private val oldContext = "src/app/context.c"
    private val nextContext = "src/app/moved/context.c"
    private val sources = listOf(old, oldContext, nextContext)

    @Given("an isolated CMake directive-token fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("ordinary-directive-token-trivia", "spaced-include", "digraph-include",
            "introducer-comment-include", "operand-comment-include", "digraph-line-directive"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-directive-token-")
        root = dir
        fun write(path: String, content: String) {
            val file = dir.resolve(path)
            Files.createDirectories(file.parent)
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(file, bytes)
        }
        val body = when (state) {
            "ordinary-directive-token-trivia" -> """#include <stddef.h>
/* # include, %:include and %:line are placed trivia. */
static const char hint[] = "#/**/include context.c %:line 90";
#define RK_TEXT(include) #include
#define RK_PARTS % : include
#
%:
int includeish = MAIN_ONLY;
_Static_assert(sizeof(size_t) > 0 && sizeof(hint) > 1, "ordinary_types");
#pragma message("rk_directive_token_ordinary")
"""
            "digraph-line-directive" -> "%:line 90\n#pragma message(\"rk_directive_token_line_containment\")\n"
            else -> {
                val directive = when (state) {
                    "spaced-include" -> "# include \"context.c\""
                    "digraph-include" -> "%:include \"context.c\""
                    "introducer-comment-include" -> "#/**/include \"context.c\""
                    else -> "#include/**/\"context.c\""
                }
                directive + "\n#if RK_ORIGIN == 1\n#pragma message(\"rk_directive_token_old_origin\")\n" +
                    "#elif RK_ORIGIN == 2\n#pragma message(\"rk_directive_token_next_origin\")\n" +
                    "#else\n#error rk_unexpected_directive_token_origin\n#endif\n"
            }
        }
        val version = if (mainIsC17()) "201710L" else "201112L"
        write(old, """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(OLD_ONLY) || defined(NEXT_ONLY)
#error rk_directive_token_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == $version, "main_dialect_not_exact");
${body}int main(void) { return MAIN_ONLY; }
""")
        listOf(oldContext, nextContext).forEachIndexed { index, source ->
            val define = if (index == 0) "OLD_ONLY" else "NEXT_ONLY"
            val other = if (index == 0) "NEXT_ONLY" else "OLD_ONLY"
            val value = if (index == 0) 7 else 9
            val function = if (index == 0) "old_context" else "next_context"
            write(source, """#if !defined(MAIN_ONLY)
#if !defined($define) || $define != $value || defined($other)
#error rk_directive_token_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "context_dialect_not_exact");
#endif
#define RK_ORIGIN ${index + 1}
int $function(void) { return RK_ORIGIN; }
""")
        }
        fun manifest(main: String) = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n" +
            "add_executable(app $main)\nadd_library(origins STATIC $oldContext $nextContext)\n"
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

    @When("that directive-token fixture previews its main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(sources.toSet(), snapshot.files.map { it.path.toString() }.toSet())
        val main = snapshot.files.single { it.path == Path.of(old) }.content
        val prepared = main.replace("\r\n", "\n").replace('\r', '\n').replace("\\\n", "")
        val control = state == "ordinary-directive-token-trivia"
        val lineDirective = state == "digraph-line-directive"
        val rawCount = CIncludeDirectiveParser().parse(main).size
        val preparedCount = CIncludeDirectiveParser().parse(prepared).size
        assertEquals(if (control) 1 else 0, rawCount)
        assertEquals(rawCount, preparedCount, "these spellings evade even the prepared parser")
        assertFalse(Regex("(?m)^\\s*#\\s*(include_next|import|line)\\b").containsMatchIn(main))
        val tokens = CTokenizer().tokenize(prepared)
        assertFalse(tokens.any { it.type == CTokenType.IDENTIFIER && it.text in
            setOf("__FILE__", "__FILE_NAME__", "__BASE_FILE__", "__builtin_FILE", "__has_include", "__has_include_next", "__has_embed") })
        val keyword = tokens.first { it.type == CTokenType.IDENTIFIER && it.text in setOf("include", "line") }
        assertEquals(if (lineDirective) "line" else "include", keyword.text)
        val introducer = tokens.first { it.line == keyword.line && it.type == CTokenType.PUNCTUATION }
        val digraph = state in setOf("digraph-include", "digraph-line-directive")
        assertEquals(if (digraph) "%" else "#", introducer.text)
        if (digraph) {
            val colon = tokens[tokens.indexOf(introducer) + 1]
            assertEquals(":", colon.text)
            assertEquals(introducer.line, colon.line)
            assertEquals(introducer.column + 1, colon.column)
        }
        val structural = CMovePlanner().preview(snapshot, Path.of(old), Path.of(next))
        assertEquals(PatchStatus.PREVIEW, structural.status, structural.summary)
        assertEquals(2, structural.workspaceEdit.edits.size)
        staged = WorkspaceEditSimulator.apply(snapshot, structural.workspaceEdit)
        val compiler = CCompilerDiagnostics(toolchain)
        val observations = listOf(snapshot, staged).mapIndexed { index, candidate ->
            val isStaged = index == 1
            val moved = if (isStaged) next else old
            val diagnostics = sources.map { original ->
                val file = if (isStaged && original == old) next else original
                val checked = assertIs<CDiagnosticsResult.Available>(compiler.analyze(candidate, Path.of(file), flags(original)))
                assertFalse(checked.diagnostics.any { it.severity == Diagnostic.Severity.ERROR }, checked.diagnostics.toString())
                assertEquals(toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                    checked.processProvenance?.executableSha256)
                checked.diagnostics
            }.flatten()
            val marker = expectedMarker(isStaged)
            val observed = diagnostics.single { it.severity == Diagnostic.Severity.WARNING && it.message == marker }
            assertEquals(dir.resolve(moved), observed.location?.path)
            if (lineDirective) assertEquals(89, observed.location?.range?.start?.line, "configured digraph #line remap, not branch change")
            buildJsonObject {
                put("phase", if (isStaged) "staged" else "baseline"); put("expectedMarker", marker)
                put("workspaceSource", moved); put("observedMarker", observed.message)
                put("compilerSha256", toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256)
                put("diagnostics", buildJsonArray { diagnostics.forEach { d -> add(buildJsonObject {
                    put("message", d.message); put("severity", d.severity.name); put("path", d.location?.path.toString())
                    put("line", d.location?.range?.start?.line)
                }) } })
            }
        }
        System.getenv("RK_C_CONSUMER_OBSERVATIONS")?.let { folder ->
            val target = Path.of(folder).resolve("$state.json")
            assertFalse(Files.exists(target), "do not overwrite prior observations")
            Files.createDirectories(target.parent)
            Files.writeString(target, buildJsonObject {
                put("state", state); put("rawIncludeCount", rawCount); put("preparedIncludeCount", preparedCount)
                put("directiveKeyword", keyword.text); put("directiveIntroducer", if (digraph) "%:" else "#")
                put("lineDirective", lineDirective); put("translationUnitCount", sources.size)
                put("branchChangeObserved", !control && !lineDirective)
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

    @Then("its managed directive-token disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertTrue(plan.requiresUserApproval && plan.workspaceEdit.edits.size == 2 && plan.affectedFiles.isNotEmpty(),
            "capture refusal retains structural inspectability, not staged REFUSED")
        if (disposition == "applicable") {
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("unparsed-moved-directive-tokens-refused-v1", lease.attributes["c.movedDirectiveTokenPolicy"])
            assertEquals("lf-crlf-cr-splice-moved-directives-v1", lease.attributes["c.movedDirectivePolicy"])
            assertEquals("3", lease.attributes["c.translationUnitCount"])
            val fields = listOf("unparsed-moved-directive-tokens-refused-v1", "captured-project-tus-v1", "3",
                snapshot.hash, staged.hash, hash(originalFiles.getValue("compile_commands.json")),
                toolchain.provenance.evidence.single { it.role == "clang-executable" }.sha256,
                assertNotNull(lease.attributes["c.targetOwnershipSha256"]))
            assertEquals(hash(fields.joinToString("|").toByteArray(Charsets.UTF_8)), lease.attributes["c.movedDirectiveTokenPolicySha256"])
            assertTrue(plan.diagnosticsBefore.any { it.message == expectedMarker(false) })
            assertTrue(plan.diagnosticsAfterPreview.any { it.message == expectedMarker(true) })
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals("unavailable-context", disposition)
            assertNull(plan.authorityLease, "unparsed directive entry cannot lease source-move authority")
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsUnavailable: c.compilationContextUnavailable:") &&
                it.contains("moved source/header '${old}' has include or path-sensitive behavior") }, plan.warnings.toString())
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained directive-token policy permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-directive-token-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-moved-directive-token-policy")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.movedDirectiveTokenPolicy",
                    lease.attributes + ("c.movedDirectiveTokenPolicy" to "unparsed-moved-directive-tokens-refused-v0"),
                    lease.attributes + ("c.movedDirectiveTokenPolicySha256" to "0".repeat(64))).forEach { attributes ->
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
                    listOf(oldContext, nextContext, "compile_commands.json").forEach { source ->
                        assertContentEquals(originalFiles.getValue(source), Files.readAllBytes(dir.resolve(source)))
                    }
                } finally {
                    assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
                }
            } else {
                val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, plan, authorization, callerGate))
                assertTrue(denied.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains("moved source/header '${old}' has include or path-sensitive behavior") }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve(next)))
    }

    private fun mainIsC17(): Boolean = state in setOf("digraph-include", "operand-comment-include")

    private fun flags(source: String): List<String> = when (source) {
        old -> listOf(if (mainIsC17()) "-std=c17" else "-std=c11", "-pedantic-errors", "-DMAIN_ONLY=3")
        oldContext -> listOf("-std=c17", "-pedantic-errors", "-DOLD_ONLY=7")
        else -> listOf("-std=c17", "-pedantic-errors", "-DNEXT_ONLY=9")
    }

    private fun expectedMarker(isStaged: Boolean): String = (when (state) {
        "ordinary-directive-token-trivia" -> "rk_directive_token_ordinary"
        "digraph-line-directive" -> "rk_directive_token_line_containment"
        else -> "rk_directive_token_" + (if (isStaged) "next" else "old") + "_origin"
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

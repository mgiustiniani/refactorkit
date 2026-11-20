package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRefactoringFacade
import org.refactorkit.c.ClangSemanticToolchain
import org.refactorkit.c.ClangToolchainDiscoverer
import org.refactorkit.c.ClangToolchainDiscovery
import org.refactorkit.c.ClangToolchainRequest
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
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

class CCMakeTuOwnershipSteps {
    private var root: Path? = null
    private lateinit var state: String
    private lateinit var disposition: String
    private lateinit var toolchain: ClangSemanticToolchain
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private val originalFiles = linkedMapOf<String, ByteArray>()
    private lateinit var sources: List<String>
    private lateinit var expectedManifest: String

    @Given("an isolated literal CMake ownership fixture in state {string}")
    fun project(state: String) {
        require(state in setOf("same-target", "private-distinct-owners", "unowned-helper",
            "duplicate-helper", "multiple-helper-owners", "unsupported-helper-owner"))
        this.state = state
        val dir = Files.createTempDirectory("refactorkit-cmake-tu-ownership-")
        root = dir
        Files.createDirectories(dir.resolve("src/app"))
        fun write(path: String, content: String) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            originalFiles[path] = bytes
            Files.write(dir.resolve(path), bytes)
        }
        write("src/app/main.c", """#if !defined(MAIN_ONLY) || MAIN_ONLY != 3 || defined(HELPER_ONLY)
#error rk_ownership_main_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201112L, "main_dialect_not_exact");
int main(void) { return 0; }
""")
        write("src/app/helper.c", """#if !defined(HELPER_ONLY) || HELPER_ONLY != 7 || defined(MAIN_ONLY)
#error rk_ownership_helper_context_not_exact
#endif
_Static_assert(__STDC_VERSION__ == 201710L, "helper_dialect_not_exact");
int helper(void) { return 1; }
""")
        sources = listOf("src/app/main.c", "src/app/helper.c") +
            if (state == "private-distinct-owners") listOf("src/app/extra.c") else emptyList()
        if (state == "private-distinct-owners") write("src/app/extra.c",
            "_Static_assert(__STDC_VERSION__ == 201710L, \"extra_dialect_not_exact\");\nint extra(void) { return 2; }\n")
        fun declarations(main: String): String = when (state) {
            "same-target" -> "add_executable(app $main src/app/helper.c)\n"
            "private-distinct-owners" -> "add_executable(app $main)\nadd_library(support STATIC src/app/helper.c)\ntarget_sources(support PRIVATE src/app/extra.c)\n"
            "unowned-helper" -> "add_executable(app $main)\n"
            "duplicate-helper" -> "add_executable(app $main src/app/helper.c)\ntarget_sources(app PRIVATE ./src/app/helper.c)\n"
            "multiple-helper-owners" -> "add_executable(app $main src/app/helper.c)\nadd_library(support STATIC src/app/helper.c)\n"
            "unsupported-helper-owner" -> "add_executable(app $main)\nadd_library(support SHARED src/app/helper.c)\n"
            else -> error("Unexpected ownership state")
        }
        val prefix = "cmake_minimum_required(VERSION 3.20)\nproject(demo LANGUAGES C)\n"
        write("CMakeLists.txt", prefix + declarations("src/app/main.c"))
        expectedManifest = prefix + declarations("src/app/moved/main.c")
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
            sources.forEach { source ->
                add(buildJsonObject {
                    put("directory", dir.toString())
                    put("file", source)
                    put("arguments", buildJsonArray {
                        add(toolchain.clangExecutable.toString())
                        add(if (source.endsWith("main.c")) "-std=c11" else "-std=c17")
                        when (source) {
                            "src/app/main.c" -> add("-DMAIN_ONLY=3")
                            "src/app/helper.c" -> add("-DHELPER_ONLY=7")
                        }
                        add("-c")
                        add(source)
                    })
                })
            }
        }.toString())
    }

    @When("that ownership fixture previews the main translation unit move")
    fun preview() {
        val dir = requireNotNull(root)
        snapshot = CProjectScanner().scan(dir)
        assertEquals(sources.toSet(), snapshot.files.map { it.path.toString() }.toSet())
        CRefactoringFacade(toolchain).use { facade ->
            plan = facade.preview(snapshot, "moveSource", mapOf("file" to "src/app/main.c", "target" to "src/app/moved/main.c"))
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
        assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
    }

    @Then("its literal target ownership disposition is {string}")
    fun evidence(disposition: String) {
        this.disposition = disposition
        assertEquals(PatchStatus.PREVIEW, plan.status, "ownership-unavailable structural preview remains inspectable")
        if (disposition == "applicable") {
            val lease = assertNotNull(plan.authorityLease)
            assertEquals("literal-root-tu-ownership-v1", lease.attributes["c.targetOwnershipScope"])
            assertEquals(expectedOwnershipHash(), lease.attributes["c.targetOwnershipSha256"],
                "lease must bind known before/staged path-to-target/type ownership, not a constant hash")
            assertEquals("captured-project-tus-v1", lease.attributes["c.diagnosticsScope"])
            assertEquals(sources.size.toString(), lease.attributes["c.translationUnitCount"])
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        } else {
            assertEquals("unavailable", disposition)
            assertNull(plan.authorityLease, "unsupported ownership must not lease managed write authority")
            assertTrue(plan.diagnosticsBefore.isEmpty() && plan.diagnosticsAfterPreview.isEmpty())
            val reason = if (state == "unsupported-helper-owner")
                "owner 'support' of TU 'src/app/helper.c' is not an executable or explicit STATIC library"
            else "TU 'src/app/helper.c' must have exactly one literal source occurrence/owner"
            assertTrue(plan.warnings.any { it.startsWith("c.previewDiagnosticsUnavailable:") &&
                it.contains("c.cmakeTargetOwnershipUnavailable") && it.contains(reason) }, plan.warnings.toString())
            assertFalse(plan.warnings.any { it.startsWith("c.previewDiagnosticsVerified:") })
        }
    }

    @Then("its retained ownership authority permits exact rollback or no WAL")
    fun applyOrRefuse() {
        val dir = requireNotNull(root)
        CRefactoringFacade(toolchain).use { facade ->
            val authorization = ApplyAuthorization.explicit("cmake-tu-ownership-story")
            val callerGate = DiagnosticsGate.disabled("caller-cannot-bypass-ownership")
            if (disposition == "applicable") {
                val lease = assertNotNull(plan.authorityLease)
                listOf(lease.attributes - "c.targetOwnershipScope",
                    lease.attributes + ("c.targetOwnershipSha256" to "0".repeat(64))).forEach { attributes ->
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
                val outcome = facade.apply(snapshot, plan, authorization, callerGate)
                val applied = assertIs<ApplyResult.Applied>(outcome)
                try {
                    assertTrue(Files.exists(dir.resolve(".refactorkit/transactions")))
                    assertFalse(Files.exists(dir.resolve("src/app/main.c")))
                    assertContentEquals(originalFiles.getValue("src/app/main.c"), Files.readAllBytes(dir.resolve("src/app/moved/main.c")))
                    assertEquals(expectedManifest, Files.readString(dir.resolve("CMakeLists.txt")))
                    sources.filter { it != "src/app/main.c" }.forEach {
                        assertContentEquals(originalFiles.getValue(it), Files.readAllBytes(dir.resolve(it)))
                    }
                } finally {
                    assertIs<ApplyResult.Applied>(PatchEngine(dir).rollback(applied.transaction))
                }
            } else {
                val denied = assertIs<ApplyResult.Refused>(facade.apply(snapshot, plan, authorization, callerGate))
                assertTrue(denied.diagnostics.any { it.code == "diagnostics.unavailable" &&
                    it.message.contains("c.cmakeTargetOwnershipUnavailable") }, denied.diagnostics.toString())
                assertFalse(Files.exists(dir.resolve(".refactorkit/transactions")))
            }
        }
        assertOriginalFiles()
        assertFalse(Files.exists(dir.resolve("src/app/moved/main.c")))
    }

    /** Independent fixture oracle for the documented deterministic before/staged ownership identity. */
    private fun expectedOwnershipHash(): String {
        fun model(moved: Boolean): JsonArray {
            val paths = sources.map { if (moved && it == "src/app/main.c") "src/app/moved/main.c" else it }.sorted()
            return JsonArray(paths.map { source ->
                val support = state == "private-distinct-owners" && !source.endsWith("main.c")
                JsonArray(listOf(JsonPrimitive(source), JsonPrimitive(if (support) "support" else "app"),
                    JsonPrimitive(if (support) "add_library" else "add_executable"),
                    if (support) JsonPrimitive("STATIC") else JsonNull))
            })
        }
        fun movedOwner(path: String) = JsonArray(listOf(JsonPrimitive(path), JsonPrimitive("app"),
            JsonPrimitive("add_executable"), JsonNull))
        val movedBefore = JsonArray(listOf(movedOwner("src/app/main.c")))
        val movedAfter = JsonArray(listOf(movedOwner("src/app/moved/main.c")))
        return hash(JsonArray(listOf(model(false), model(true), movedBefore, movedAfter))
            .toString().toByteArray(Charsets.UTF_8))
    }

    private fun assertOriginalFiles() {
        val dir = requireNotNull(root)
        originalFiles.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path)), path) }
        if (::snapshot.isInitialized) assertEquals(snapshot.hash, CProjectScanner().scan(dir).hash)
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @After
    fun cleanup() {
        root?.let { dir -> Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } } }
    }
}

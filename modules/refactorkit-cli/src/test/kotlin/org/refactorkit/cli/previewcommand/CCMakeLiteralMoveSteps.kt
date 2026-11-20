package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CMovePlanner
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CCMakeLiteralMoveSteps {
    private var workspace: Path? = null
    private lateinit var original: Map<String, ByteArray>
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var manifestForm: String
    private lateinit var oldPath: Path
    private lateinit var newPath: Path
    private lateinit var plan: PatchPlan

    @Given("an isolated C project with a {string} root CMake manifest")
    fun project(form: String) {
        manifestForm = form
        val root = Files.createTempDirectory("refactorkit-cmake-move-")
        workspace = root
        Files.createDirectories(root.resolve("src/component"))
        val prefix = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
"""
        val literal = """add_library(component STATIC src/component/a.c)
target_sources(component PRIVATE src/component/b.c)
target_include_directories(component PRIVATE src/component)
"""
        val cmake = when (form) {
            "add-library", "target-sources", "uncaptured-include" -> prefix + literal
            "variable" -> prefix + "add_library(component STATIC ${'$'}{SOURCES})\n"
            "generator" -> prefix + "add_library(component STATIC ${'$'}<TARGET_OBJECTS:other>)\n"
            "included-script" -> prefix + literal + "include(extra.cmake)\n"
            "conditional" -> prefix + "if(ENABLE_FEATURE)\n" + literal + "endif()\n"
            "nested-only" -> prefix + "add_library(component STATIC a.c)\n"
            else -> error("Unknown manifest form: $form")
        }
        val files = mutableMapOf(
            "src/component/a.c" to if (form == "uncaptured-include") {
                "#include \"config.inc\"\nint component(void) { return 7; }\n"
            } else "int component(void) { return 7; }\n",
            "src/component/b.c" to "int extra(void) { return 2; }\n",
            (if (form == "nested-only") "src/component/CMakeLists.txt" else "CMakeLists.txt") to cmake,
            "extra.cmake" to "execute_process(COMMAND /usr/bin/touch ${root.resolve("must-not-execute")})\n",
        )
        if (form == "uncaptured-include") {
            files["src/component/config.inc"] = "#define COMPONENT_CONFIG 1\n"
        }
        original = files.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        files.forEach { (path, content) -> Files.writeString(root.resolve(path), content) }
        oldPath = Path.of(if (form == "target-sources") "src/component/b.c" else "src/component/a.c")
        newPath = Path.of(if (form == "target-sources") "src/other/b.c" else "src/other/a.c")
        snapshot = CProjectScanner().scan(root)
    }

    @Given("an isolated C project with a nested CMakeLists but no root manifest")
    fun nestedOnly() {
        project("nested-only")
    }

    @When("the selected C source is moved to a different directory in preview")
    fun movePreview() {
        plan = CMovePlanner().preview(snapshot, oldPath, newPath)
    }

    @Then("the CMake move is {string} with exact target binding and no unowned edits")
    fun outcome(disposition: String) {
        when (disposition) {
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.isEmpty(), plan.summary)
                if (manifestForm == "uncaptured-include") {
                    assertTrue(plan.summary.contains("uncaptured quoted include", ignoreCase = true), plan.summary)
                }
            }
            "preview" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
                assertTrue(plan.workspaceEdit.edits.any { it is FileEdit.Rename && it.path == oldPath && it.newPath == newPath })
                assertEquals(listOf(Path.of("CMakeLists.txt")), plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>().map { it.path })
                val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
                val manifest = staged.auxiliaryFiles.single { it.path == Path.of("CMakeLists.txt") }.content
                val expected = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC ${if (manifestForm == "add-library") "src/other/a.c" else "src/component/a.c"})
target_sources(component PRIVATE ${if (manifestForm == "target-sources") "src/other/b.c" else "src/component/b.c"})
target_include_directories(component PRIVATE src/component)
"""
                assertEquals(expected, manifest, "literal target binding must be updated exactly once")
                assertTrue(staged.files.any { it.path == newPath })
                assertFalse(staged.files.any { it.path == oldPath })
            }
            else -> error("Unexpected disposition: $disposition")
        }
    }

    @Then("the CMake project bytes and transaction journal remain unchanged")
    fun unchanged() {
        val root = requireNotNull(workspace)
        original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(path))) }
        assertFalse(Files.exists(root.resolve("must-not-execute")), "CMake scripts must never run")
        assertFalse(Files.exists(root.resolve(".refactorkit")), "preview must not create WAL/transaction")
    }

    @After
    fun cleanup() {
        workspace?.let { root ->
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

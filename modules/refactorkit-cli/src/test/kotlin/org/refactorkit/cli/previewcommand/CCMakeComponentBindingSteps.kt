package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanner
import org.refactorkit.c.CRelocateComponentPlanner
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CCMakeComponentBindingSteps {
    private var root: Path? = null
    private lateinit var manifestForm: String
    private lateinit var original: Map<String, ByteArray>
    private lateinit var snapshot: org.refactorkit.core.ProjectSnapshot
    private lateinit var plan: PatchPlan

    @Given("an isolated C component with {string} CMake bindings")
    fun component(form: String) {
        manifestForm = form
        val dir = Files.createTempDirectory("refactorkit-cmake-component-")
        root = dir
        Files.createDirectories(dir.resolve("src/component"))
        val visibility = when (form) {
            "literal-public", "public-script", "public-dynamic-root" -> "PUBLIC"
            "interface-root" -> "INTERFACE"
            "missing-visibility" -> ""
            else -> "PRIVATE"
        }
        val includePath = if (form == "public-dynamic-root") "${'$'}{ROOT_DIR}" else "src/component"
        val manifest = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC src/component/a.c)
""" + when (form) {
            "unbound-header" -> ""
            "generated-source" -> """target_sources(component PRIVATE src/component/a.h src/component/gen.generated.c)
target_include_directories(component PRIVATE src/component)
"""
            else -> """target_sources(component PRIVATE src/component/a.h)
target_include_directories(component $visibility $includePath)
"""
        } + if (form in setOf("included-script", "public-script")) "include(extra.cmake)\n" else ""
        val files = mutableMapOf(
            "src/component/a.c" to if (form == "uncaptured-include") {
                "#include \"a.h\"\n#include \"config.inc\"\nint component(void) { return ANSWER; }\n"
            } else "#include \"a.h\"\nint component(void) { return ANSWER; }\n",
            "src/component/a.h" to "#define ANSWER 7\n",
            "CMakeLists.txt" to manifest,
            "extra.cmake" to "execute_process(COMMAND /usr/bin/touch ${dir.resolve("must-not-execute")})\n",
        )
        if (form == "uncaptured-include") {
            files["src/component/config.inc"] = "#define COMPONENT_OFFSET 2\n"
        }
        if (form == "generated-source") {
            files["src/component/gen.generated.c"] = "int generated_component(void) { return 3; }\n"
        }
        original = files.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        files.forEach { (path, content) -> Files.writeString(dir.resolve(path), content) }
        snapshot = CProjectScanner().scan(dir)
    }

    @When("the C component relocation is previewed")
    fun preview() {
        plan = CRelocateComponentPlanner().preview(snapshot, Path.of("src/component"), Path.of("src/newpart"))
    }

    @Then("the component binding is {string} with an exact staged CMake manifest")
    fun result(disposition: String) {
        if (disposition == "refused") {
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
            if (manifestForm == "generated-source") {
                assertTrue(plan.summary.contains("generated", ignoreCase = true), plan.summary)
            }
            if (manifestForm == "uncaptured-include") {
                assertTrue(plan.summary.contains("uncaptured literal include", ignoreCase = true), plan.summary)
            }
            return
        }
        assertEquals("preview", disposition)
        assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
        assertEquals(setOf(Path.of("src/component/a.c"), Path.of("src/component/a.h")),
            plan.workspaceEdit.edits.filterIsInstance<FileEdit.Rename>().map { it.path }.toSet())
        assertEquals(3, plan.workspaceEdit.edits.filterIsInstance<FileEdit.Modify>()
            .single { it.path == Path.of("CMakeLists.txt") }.textEdits.size)
        val staged = WorkspaceEditSimulator.apply(snapshot, plan.workspaceEdit)
        assertEquals("""cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component STATIC src/newpart/a.c)
target_sources(component PRIVATE src/newpart/a.h)
target_include_directories(component ${if (manifestForm == "literal-public") "PUBLIC" else "PRIVATE"} src/newpart)
""", staged.auxiliaryFiles.single { it.path == Path.of("CMakeLists.txt") }.content)
        assertTrue(staged.files.any { it.path == Path.of("src/newpart/a.c") })
        assertTrue(staged.files.any { it.path == Path.of("src/newpart/a.h") })
        assertFalse(staged.files.any { it.path.startsWith(Path.of("src/component")) })
    }

    @Then("the relocation preview does not write files or a transaction")
    fun noWrites() {
        val dir = requireNotNull(root)
        original.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(dir.resolve(path))) }
        assertFalse(Files.exists(dir.resolve("must-not-execute")))
        assertFalse(Files.exists(dir.resolve(".refactorkit")))
    }

    @After
    fun cleanup() {
        root?.let { dir ->
            Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

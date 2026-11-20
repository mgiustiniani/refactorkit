package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CCMakeSnapshotCaptureSteps {
    private var workspace: Path? = null
    private lateinit var original: ByteArray
    private lateinit var manifestBefore: String
    private lateinit var manifestAfter: String
    private lateinit var marker: Path
    private lateinit var first: ProjectSnapshot
    private lateinit var second: ProjectSnapshot

    @Given("an isolated C source and a CMake manifest with an executable marker command")
    fun workspaceWithManifest() {
        val root = Files.createTempDirectory("refactorkit-cmake-capture-")
        workspace = root
        marker = root.resolve("must-not-execute")
        Files.createDirectories(root.resolve("src/component"))
        original = "int component(void) { return 7; }\n".toByteArray(Charsets.UTF_8)
        Files.write(root.resolve("src/component/a.c"), original)
        manifestBefore = """cmake_minimum_required(VERSION 3.20)
project(demo LANGUAGES C)
add_library(component src/component/a.c)
execute_process(COMMAND /usr/bin/touch $marker)
"""
        Files.writeString(root.resolve("CMakeLists.txt"), manifestBefore)
    }

    @When("the C project scanner captures the manifest and rescans after its bytes change")
    fun scanTwice() {
        val root = requireNotNull(workspace)
        first = CProjectScanner().scan(root)
        manifestAfter = manifestBefore + "# changed without evaluating CMake\n"
        Files.writeString(root.resolve("CMakeLists.txt"), manifestAfter)
        second = CProjectScanner().scan(root)
    }

    @Then("the manifest is auxiliary rather than C source and the snapshot hash changes")
    fun auxiliaryAndHash() {
        assertEquals(listOf(Path.of("src/component/a.c")), first.files.map { it.path })
        assertEquals(listOf(Path.of("src/component/a.c")), second.files.map { it.path })
        assertEquals(manifestBefore, first.auxiliaryFiles.single { it.path == Path.of("CMakeLists.txt") }.content)
        assertEquals(manifestAfter, second.auxiliaryFiles.single { it.path == Path.of("CMakeLists.txt") }.content)
        assertNotEquals(first.hash, second.hash)
    }

    @Then("no captured CMake command or user source is executed or modified")
    fun noExecution() {
        val root = requireNotNull(workspace)
        assertFalse(Files.exists(marker), "captured CMake commands must never run")
        assertContentEquals(original, Files.readAllBytes(root.resolve("src/component/a.c")))
        assertTrue(Files.notExists(root.resolve(".refactorkit")), "scan creates no transaction")
    }

    @After
    fun cleanup() {
        workspace?.let { root ->
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
}

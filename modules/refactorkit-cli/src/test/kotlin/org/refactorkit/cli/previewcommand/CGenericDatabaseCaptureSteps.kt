package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CCompilationSnapshotCapture
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CGenericDatabaseCaptureSteps {
    private val fixture = CGenericDatabaseCaptureFixture()

    @Given("a generic C workspace database snapshot has {string} evidence")
    fun capture(condition: String) = fixture.capture(condition)

    @When("the C adapter captures that existing database input")
    fun classify() = fixture.classify()

    @Then("database capture reports {string} without refreshing or hiding the original evidence")
    fun result(outcome: String) = fixture.assertResult(outcome)

    @Then("database capture changes no source configuration symlink or execution marker")
    fun intact() = fixture.assertIntact()

    @After
    fun close() = fixture.close()
}

private class CGenericDatabaseCaptureFixture : AutoCloseable {
    private var owned: Path? = null
    private lateinit var root: Path
    private lateinit var condition: String
    private lateinit var original: String
    private lateinit var physical: String
    private var before: ProjectSnapshot? = null
    private var result: ProjectSnapshot? = null
    private var rejected: IllegalArgumentException? = null
    private val source = "int main(void) { return 0; }\n"

    fun capture(variant: String) {
        require(variant in setOf("current JSON", "stale JSON", "duplicate JSON", "missing JSON", "symlink JSON", "already typed"))
        condition = variant
        val directory = Files.createTempDirectory("rk-cdb-capture-").also { owned = it }
        root = Files.createDirectory(directory.resolve("workspace"))
        Files.writeString(root.resolve("main.c"), source)
        val marker = directory.resolve("unexpected-execution")
        val executable = directory.resolve("compiler-canary.sh")
        Files.writeString(executable, "#!/bin/sh\nprintf executed > '$marker'\n")
        assertTrue(executable.toFile().setExecutable(true))
        original = JsonArray(listOf(buildJsonObject {
            put("directory", root.toString()); put("file", "main.c")
            put("arguments", JsonArray(listOf(executable.toString(), "-std=c17", "-c", "main.c").map(::JsonPrimitive)))
        })).toString()
        physical = original + if (condition == "stale JSON") "\n" else ""
        if (condition == "symlink JSON") {
            val target = directory.resolve("outside.json")
            Files.writeString(target, physical)
            Files.createSymbolicLink(root.resolve("compile_commands.json"), target)
        } else if (condition != "missing JSON") Files.writeString(root.resolve("compile_commands.json"), physical)
        val input = SourceFile(Path.of("compile_commands.json"), original,
            if (condition == "already typed") "c-compilation-database" else "json")
        try {
            before = ProjectSnapshot(Workspace(root), emptyList(), listOf(SourceFile(Path.of("main.c"), source, "c")),
                auxiliaryFiles = if (condition == "duplicate JSON") listOf(input, input.copy()) else listOf(input))
        } catch (failure: IllegalArgumentException) { rejected = failure }
    }

    fun classify() {
        if (rejected == null) result = CCompilationSnapshotCapture.capture(assertNotNull(before))
    }

    fun assertResult(outcome: String) {
        if (outcome == "snapshot refused") {
            assertEquals("duplicate JSON", condition)
            assertTrue(assertNotNull(rejected).message.orEmpty().contains("auxiliary workspace file paths must be unique"))
            assertNull(before); assertNull(result)
            return
        }
        assertNull(rejected)
        val previous = assertNotNull(before)
        val observed = assertNotNull(result)
        assertEquals(previous.workspace, observed.workspace)
        assertEquals(previous.files, observed.files)
        assertEquals(previous.classpathEvidence, observed.classpathEvidence)
        assertEquals(previous.buildModels, observed.buildModels)
        val database = observed.auxiliaryFiles.single()
        assertEquals(Path.of("compile_commands.json"), database.path)
        assertEquals(original, database.content, "Capture must not replace its original evidence with current disk bytes")
        when (outcome) {
            "C database captured" -> {
                assertEquals("current JSON", condition)
                assertEquals("c-compilation-database", database.languageId)
            }
            "C database retained" -> {
                assertEquals("already typed", condition)
                assertEquals(previous, observed); assertEquals(previous.hash, observed.hash)
            }
            "generic retained" -> {
                assertTrue(condition in setOf("stale JSON", "missing JSON", "symlink JSON"))
                assertEquals("json", database.languageId)
                assertEquals(previous, observed); assertEquals(previous.hash, observed.hash)
            }
            else -> error("Unknown capture outcome $outcome")
        }
    }

    fun assertIntact() {
        val directory = assertNotNull(owned)
        assertEquals(source, Files.readString(root.resolve("main.c")))
        val database = root.resolve("compile_commands.json")
        if (condition == "missing JSON") assertFalse(Files.exists(database))
        else assertEquals(physical, Files.readString(database))
        assertEquals(condition == "symlink JSON", Files.isSymbolicLink(database))
        if (condition == "symlink JSON") {
            assertEquals(directory.resolve("outside.json"), Files.readSymbolicLink(database))
            assertEquals(physical, Files.readString(directory.resolve("outside.json")))
        }
        assertFalse(Files.exists(directory.resolve("unexpected-execution")), "A captured command is data, never execution authorization")
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    override fun close() {
        owned?.let { directory -> Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
    }
}

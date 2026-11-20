package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.cli.RefactorKitCli
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.TextEdits
import org.refactorkit.java.JavaOrganizeImportsPlanner
import org.refactorkit.java.JavaProjectScanner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaOrganizeImportsPathSteps {
    private var root: Path? = null
    private val sourcePath = Path.of("src/main/java/pkg/Demo.java")
    private val before = "package pkg;\nimport java.util.Map;\nimport java.util.List;\n" +
        "public class Demo { List<String> values; Map<String, String> names; }\n"
    private val after = "package pkg;\nimport java.util.List;\nimport java.util.Map;\n" +
        "public class Demo { List<String> values; Map<String, String> names; }\n"
    private lateinit var snapshot: ProjectSnapshot
    private lateinit var selected: List<Path>
    private lateinit var plan: PatchPlan
    private lateinit var baseline: Map<Path, String>
    private var exit = -1
    private var output = ""

    @Given("an unsorted Java import fixture selected with {string}")
    fun fixture(selection: String) {
        val workspace = Files.createTempDirectory("rk-java-import-path-")
        root = workspace
        Files.createDirectories(workspace.resolve(sourcePath).parent)
        Files.writeString(workspace.resolve(sourcePath), before)
        Files.writeString(workspace.resolve("pom.xml"),
            "<project><modelVersion>4.0.0</modelVersion><groupId>pkg</groupId>" +
                "<artifactId>app</artifactId><version>1</version>" +
                "<properties><maven.compiler.release>8</maven.compiler.release></properties></project>\n")
        baseline = fixtureContents(workspace)
        snapshot = JavaProjectScanner().scan(workspace)
        selected = when (selection) {
            "absolute" -> listOf(workspace.resolve(sourcePath))
            "normalized relative" -> listOf(Path.of("src/main/java/pkg/../pkg/Demo.java"))
            "duplicate spellings" -> listOf(sourcePath, workspace.resolve(sourcePath), Path.of("./$sourcePath"))
            "missing" -> listOf(Path.of("src/main/java/pkg/Missing.java"))
            "outside workspace" -> listOf(workspace.resolve("../Outside.java").normalize())
            "valid and missing" -> listOf(sourcePath, Path.of("src/main/java/pkg/Missing.java"))
            else -> error("Unknown fixture selection: $selection")
        }
    }

    @When("the Java planner and CLI preview the selected import paths")
    fun preview() {
        plan = JavaOrganizeImportsPlanner().preview(snapshot, selected)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        try {
            System.setOut(PrintStream(stdout, true, Charsets.UTF_8))
            System.setErr(PrintStream(stderr, true, Charsets.UTF_8))
            exit = RefactorKitCli().run(listOf("organize-imports") + selected.map(Path::toString) +
                listOf("--root", requireNotNull(root).toString(), "--preview"))
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
        }
        output = stdout.toString(Charsets.UTF_8) + stderr.toString(Charsets.UTF_8)
    }

    @Then("the import selection returns {string} with diagnostic {string}")
    fun outcome(outcome: String, diagnostic: String) {
        assertEquals(snapshot.hash, plan.snapshotHash)
        when (outcome) {
            "changed" -> {
                assertEquals(PatchStatus.PREVIEW, plan.status)
                assertEquals(0, exit, output)
                assertEquals(setOf(sourcePath), plan.affectedFiles)
                val edit = assertIs<FileEdit.Modify>(plan.workspaceEdit.edits.single())
                assertEquals(sourcePath, edit.path)
                assertEquals(after, TextEdits.apply(before, edit.textEdits))
                assertEquals("none", diagnostic)
                assertTrue(output.contains("+++ b/$sourcePath"), output)
                assertTrue(output.contains("+import java.util.List;"), output)
                assertTrue(output.contains("Organize imports in 1 file(s)."), output)
            }
            "refused" -> {
                assertEquals(PatchStatus.REFUSED, plan.status)
                assertEquals(1, exit, output)
                assertEquals(diagnostic, plan.refusalCode)
                assertTrue(plan.diagnosticsBefore.any { it.code == diagnostic })
                assertTrue(plan.affectedFiles.isEmpty())
                assertTrue(plan.workspaceEdit.edits.isEmpty())
                assertFalse(plan.requiresUserApproval)
                assertTrue(output.contains(diagnostic), output)
                assertFalse(output.contains("+++ b/"), output)
            }
            else -> error("Unknown expected outcome: $outcome")
        }
    }

    @Then("import path selection preserves all fixture bytes and the saved snapshot")
    fun unchangedFixture() {
        val workspace = requireNotNull(root)
        assertEquals(baseline, fixtureContents(workspace))
        assertEquals(snapshot.hash, JavaProjectScanner().scan(workspace).hash)
        assertEquals(before, snapshot.files.single { it.path == sourcePath }.content)
        assertFalse(Files.exists(workspace.resolve(".refactorkit")))
    }

    @After
    fun cleanup() {
        root?.let { workspace ->
            Files.walk(workspace).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun fixtureContents(workspace: Path): Map<Path, String> = Files.walk(workspace).use { paths ->
        paths.filter(Files::isRegularFile).toList().associate { workspace.relativize(it) to Files.readString(it) }
    }
}

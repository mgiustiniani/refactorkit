package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaScanOutputDirectorySteps {
    private var workspace: Path? = null
    private lateinit var copiedProject: Path
    private lateinit var realFiles: Map<Path, String>
    private lateinit var before: ProjectSnapshot
    private lateinit var after: ProjectSnapshot

    @Given("a Java workspace with a real module and a copied project under {string}")
    fun workspaceWithCopy(outputDirectory: String) {
        val root = Files.createTempDirectory("refactorkit-java-output-scan-")
        workspace = root
        realFiles = mapOf(
            Path.of("pom.xml") to pom("real-module"),
            Path.of("src/main/java/example/Live.java") to "package example; public class Live {}\n",
            Path.of("target/generated-sources/annotations/example/Generated.java") to
                "package example; public class Generated {}\n",
        )
        realFiles.forEach { (path, content) -> write(root.resolve(path), content) }
        copiedProject = root.resolve(outputDirectory).resolve("copied-project")
        write(copiedProject.resolve("pom.xml"), pom("copied-module"))
        write(copiedProject.resolve("build.gradle.kts"), "// copied build descriptor\n")
        write(copiedProject.resolve("src/main/java/example/Foreign.java"),
            "package example; public class Foreign {}\n")
    }

    @When("Java discovery scans the workspace before and after the copied project changes")
    fun scanAroundCopyChanges() {
        val root = requireNotNull(workspace)
        val scanner = JavaProjectScanner()
        before = scanner.scan(root)
        write(copiedProject.resolve("pom.xml"), pom("changed-copied-module"))
        write(copiedProject.resolve("build.gradle.kts"), "// changed copied build descriptor\n")
        write(copiedProject.resolve("src/main/java/example/Foreign.java"),
            "package example; public class Foreign { int changed; }\n")
        after = scanner.scan(root)
    }

    @Then("discovery retains only the real module and its materialized generated sources")
    fun onlyRealModuleAndSources() {
        val root = requireNotNull(workspace)
        val expectedSources = realFiles.keys.filter { it.toString().endsWith(".java") }.toSet()
        for (snapshot in listOf(before, after)) {
            assertEquals(listOf(root), snapshot.modules.map { it.root })
            assertEquals(expectedSources, snapshot.files.map { it.path }.toSet())
            assertEquals(listOf(Path.of("pom.xml")), snapshot.auxiliaryFiles.map { it.path })
            assertEquals(1, snapshot.buildModels.sumOf { it.modules.size })
            assertTrue(Path.of("target/generated-sources/annotations") in snapshot.modules.single().sourceRoots)
            assertFalse(snapshot.classpathEvidence.any { root.resolve(it.path).startsWith(copiedProject) })
        }
    }

    @Then("changes inside the build output copy leave the workspace snapshot and build evidence unchanged")
    fun copyHasNoAuthority() {
        assertEquals(before.hash, after.hash)
        assertEquals(before.modules, after.modules)
        assertEquals(before.buildModels, after.buildModels)
        assertEquals(before.classpathEvidence, after.classpathEvidence)
        assertEquals(before.auxiliaryFiles, after.auxiliaryFiles)
        val root = requireNotNull(workspace)
        realFiles.forEach { (path, content) -> assertEquals(content, Files.readString(root.resolve(path))) }
        assertFalse(Files.exists(root.resolve(".refactorkit")), "discovery must not create a transaction")
    }

    @After
    fun cleanup() {
        workspace?.let { root ->
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun pom(artifactId: String): String = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>example</groupId>
          <artifactId>$artifactId</artifactId>
          <version>1.0.0</version>
          <properties><maven.compiler.release>8</maven.compiler.release></properties>
        </project>
    """.trimIndent() + "\n"
}

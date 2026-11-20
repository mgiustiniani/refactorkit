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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JavaScanDeclaredOutputModuleSteps {
    private var workspace: Path? = null
    private lateinit var module: Path
    private lateinit var copiedProject: Path
    private lateinit var scanner: JavaProjectScanner
    private lateinit var before: ProjectSnapshot
    private lateinit var after: ProjectSnapshot

    @Given("a Maven reactor declares {string} through {string}")
    fun declaredReactor(modulePath: String, declaration: String) {
        val root = Files.createTempDirectory("refactorkit-java-declared-output-")
        workspace = root
        module = root.resolve(modulePath)
        copiedProject = module.resolve("target/copied-project")
        val selected = declaration == "selected profile"
        scanner = JavaProjectScanner(activeMavenProfiles = if (selected) setOf("with-output-module") else emptySet())
        val childName = module.fileName.toString()
        val rootDeclaration = when (declaration) {
            "direct" -> "<modules><module>$modulePath</module></modules>"
            "nested" -> "<modules><module>parent</module></modules>"
            "selected profile", "inactive profile" -> """
                <profiles><profile><id>with-output-module</id>
                  <modules><module>$modulePath</module></modules>
                </profile></profiles>
            """.trimIndent()
            else -> error("Unknown declaration: $declaration")
        }
        write(root.resolve("pom.xml"), pom("reactor", "pom", rootDeclaration))
        if (declaration == "nested") {
            write(root.resolve("parent/pom.xml"), pom("parent", "pom", "<modules><module>$childName</module></modules>"))
        }
        write(module.resolve("pom.xml"), pom("declared-module"))
        write(module.resolve("src/main/java/example/Declared.java"), "package example; public class Declared {}\n")
        write(copiedProject.resolve("pom.xml"), pom("copied-module"))
        write(copiedProject.resolve("src/main/java/example/Copied.java"), "package example; public class Copied {}\n")
    }

    @When("Java discovery captures the module before and after its descriptor changes")
    fun captureDescriptorChange() {
        val root = requireNotNull(workspace)
        before = scanner.scan(root)
        write(module.resolve("pom.xml"), pom("changed-declared-module"))
        after = scanner.scan(root)
    }

    @Then("module membership and descriptor evidence are {string}")
    fun assertDeclaredAuthority(authority: String) {
        val root = requireNotNull(workspace)
        val relativePom = root.relativize(module.resolve("pom.xml"))
        val relativeSource = root.relativize(module.resolve("src/main/java/example/Declared.java"))
        val admitted = authority == "admitted"
        for (snapshot in listOf(before, after)) {
            assertEquals(admitted, snapshot.modules.any { it.root == module })
            assertEquals(admitted, snapshot.files.any { it.path == relativeSource })
            assertEquals(admitted, snapshot.auxiliaryFiles.any { it.path == relativePom })
            assertEquals(admitted, snapshot.buildModels.flatMap { it.modules }.any { it.root == module })
            if (admitted) {
                assertTrue(snapshot.modules.single { it.root == module }.sourceRoots.contains(root.relativize(module.resolve("src/main/java"))))
                assertTrue(snapshot.classpathEvidence.any { it.path == relativePom })
            }
        }
        if (admitted) assertNotEquals(before.hash, after.hash) else assertEquals(before.hash, after.hash)
    }

    @Then("undeclared build copies never gain discovery authority")
    fun assertCopiesRemainExcluded() {
        val root = requireNotNull(workspace)
        write(copiedProject.resolve("pom.xml"), pom("changed-copied-module"))
        write(copiedProject.resolve("src/main/java/example/Copied.java"), "package example; public class Copied { int changed; }\n")
        val afterCopyChange = scanner.scan(root)
        assertEquals(after.hash, afterCopyChange.hash)
        assertEquals(after.modules, afterCopyChange.modules)
        assertEquals(after.buildModels, afterCopyChange.buildModels)
        assertEquals(after.classpathEvidence, afterCopyChange.classpathEvidence)
        assertEquals(after.auxiliaryFiles, afterCopyChange.auxiliaryFiles)
        assertFalse(afterCopyChange.modules.any { it.root.startsWith(copiedProject) })
        assertFalse(afterCopyChange.files.any { root.resolve(it.path).startsWith(copiedProject) })
        assertFalse(Files.exists(root.resolve(".refactorkit")))
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

    private fun pom(artifactId: String, packaging: String = "jar", declarations: String = ""): String = """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>example</groupId><artifactId>$artifactId</artifactId><version>1.0.0</version>
          <packaging>$packaging</packaging>
          <properties><maven.compiler.release>8</maven.compiler.release></properties>
          $declarations
        </project>
    """.trimIndent() + "\n"
}

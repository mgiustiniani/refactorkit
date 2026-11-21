package org.refactorkit.java.mavenmodulerename

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.ApplyAuthorization
import org.refactorkit.core.ApplyResult
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.PatchEngine
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RollbackMode
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.java.JavaRenameMavenModulePlanner
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaMavenNestedModuleMoveSteps {
    private val scratch = Files.createTempDirectory("rk-nested-module-move-")
    private val reactor = Files.createDirectory(scratch.resolve("reactor"))
    private val repository = Files.createDirectory(scratch.resolve("offline-maven"))
    private val outside = Files.createDirectory(scratch.resolve("outside"))
    private val planner = JavaRenameMavenModulePlanner()
    private lateinit var baseline: Map<String, String>
    private lateinit var original: ProjectSnapshot
    private lateinit var plan: PatchPlan
    private lateinit var target: String
    private lateinit var parentPath: String

    @Given("the Maven directory move fixture has {string} and {string}")
    fun prepare(parentPath: String, layout: String) {
        this.parentPath = parentPath
        write("pom.xml", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version>
              <packaging>pom</packaging>
              <properties><maven.compiler.release>21</maven.compiler.release></properties>
              <modules><module>library</module><module>app</module></modules>
            </project>
        """.trimIndent() + "\n")
        write("library/pom.xml", modulePom("library", parentPath == "explicit"))
        write("app/pom.xml", modulePom("app", false,
            "<dependencies><dependency><groupId>fixture</groupId><artifactId>library</artifactId>" +
                "<version>1</version></dependency></dependencies>"))
        write("library/src/main/java/pkg/Library.java",
            "package pkg; public final class Library { public static int value() { return 27; } }\n")
        write("app/src/main/java/consumer/Main.java",
            "package consumer; import pkg.Library; public final class Main {" +
                " public static void main(String[] args) { System.out.println(Library.value()); } }\n")
        Files.writeString(outside.resolve("marker"), "outside directory")
        when (layout) {
            "absent parent directory" -> Unit
            "existing directory" -> write("components/keep.txt", "unrelated destination-parent content")
            "linked ancestor" -> Files.createSymbolicLink(reactor.resolve("components"), outside)
            "blocking file" -> write("components", "blocking file")
            else -> error("Unknown destination layout $layout")
        }
        Files.createDirectories(reactor.resolve("library/empty/original"))
        baseline = capture()
        original = scan()
        assertEquals(BuildModelStatus.AVAILABLE, original.buildModels.single().status)
    }

    @When("I preview relocating the Maven library to {string} with its GAV preserved")
    fun preview(destination: String) {
        target = destination
        plan = planner.preview(original, "library", target)
    }

    @Then("the Maven directory move {string} with exact workspace evidence")
    fun verify(outcome: String) {
        assertEquals(baseline, capture())
        assertTrue(Files.notExists(reactor.resolve(".refactorkit"), NOFOLLOW_LINKS))
        if (outcome == "refuses an unsafe destination") {
            assertEquals(PatchStatus.REFUSED, plan.status, plan.summary)
            assertEquals("mavenOwnership.destinationUnrecognized", plan.refusalCode)
            assertTrue(plan.workspaceEdit.edits.isEmpty())
        } else {
            assertEquals("applies, retires the source, and rolls back", outcome)
            assertEquals(PatchStatus.PREVIEW, plan.status, plan.summary)
            val engine = PatchEngine(reactor)
            val result = engine.apply(
                plan, original, ApplyAuthorization.explicit("nested-maven-fixture", "acceptance"),
                planner.diagnosticsGate(plan),
            )
            val applied = assertIs<ApplyResult.Applied>(result, result.toString())
            val expected = baseline.filterValues { it != "DIRECTORY" }.toMutableMap()
            expected["pom.xml"] = expected.getValue("pom.xml").replace("<module>library</module>", "<module>$target</module>")
            val originalPom = expected.remove("library/pom.xml")!!
            expected["$target/pom.xml"] = if (parentPath == "explicit") {
                originalPom.replace("<relativePath>../pom.xml</relativePath>", "<relativePath>../../pom.xml</relativePath>")
            } else {
                originalPom.replace("  </parent>", "    <relativePath>../../pom.xml</relativePath>\n  </parent>")
            }
            expected["$target/src/main/java/pkg/Library.java"] = expected.remove("library/src/main/java/pkg/Library.java")!!
            assertEquals(expected, capture().filterValues { it != "DIRECTORY" })
            val model = scan().buildModels.single()
            assertEquals(BuildModelStatus.AVAILABLE, model.status)
            val library = model.modules.single { it.id == target.replace('/', ':') }
            assertEquals(reactor.resolve(target), library.root)
            val coordinates = listOf("groupId", "artifactId", "version").map { library.attributes["java.maven.$it"] }
            assertEquals(listOf("fixture", "library", "1"), coordinates)
            val oldSourceStillVisible = Files.exists(reactor.resolve("library"), NOFOLLOW_LINKS)
            if (!oldSourceStillVisible && target.startsWith("components/") && "components/keep.txt" in baseline) {
                write("library/foreign.txt", "unrelated caller content")
                val conflicted = capture()
                val rejected = assertIs<ApplyResult.Refused>(engine.rollback(applied.transaction, RollbackMode.NORMAL))
                assertTrue(rejected.diagnostics.any { it.code == "rollback.conflict" })
                assertEquals(conflicted, capture())
                Files.delete(reactor.resolve("library/foreign.txt"))
                Files.delete(reactor.resolve("library"))
            }
            assertIs<ApplyResult.Applied>(engine.rollback(applied.transaction, RollbackMode.NORMAL))
            assertFalse(oldSourceStillVisible, "Applied nested module move must retire the original source directory")
            assertEquals(baseline, capture())
            assertEquals(original.hash, scan().hash)
        }
        assertEquals(listOf("marker"), Files.list(outside).use { files -> files.map { it.fileName.toString() }.toList() })
        assertEquals("outside directory", Files.readString(outside.resolve("marker")))
    }

    private fun modulePom(artifact: String, explicitPath: Boolean, dependencies: String = ""): String =
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
            "  <modelVersion>4.0.0</modelVersion>\n  <parent>\n" +
            "    <groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version>\n" +
            (if (explicitPath) "    <relativePath>../pom.xml</relativePath>\n" else "") +
            "  </parent>\n  <artifactId>$artifact</artifactId>\n$dependencies\n</project>\n"

    private fun write(relative: String, content: String) {
        val path = reactor.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun scan(): ProjectSnapshot =
        JavaProjectScanner(allowNetworkDependencyResolution = false, localMavenRepository = repository).scan(reactor)

    private fun capture(): Map<String, String> = Files.walk(reactor).use { paths ->
        paths.filter { it != reactor && !reactor.relativize(it).startsWith(".refactorkit") }
            .toList().associate { path ->
                reactor.relativize(path).invariantSeparatorsPathString to when {
                    Files.isSymbolicLink(path) -> "LINK:" + Files.readSymbolicLink(path)
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> "DIRECTORY"
                    else -> Files.readString(path)
                }
            }
    }

    @After
    fun close() {
        Files.walk(scratch).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
}

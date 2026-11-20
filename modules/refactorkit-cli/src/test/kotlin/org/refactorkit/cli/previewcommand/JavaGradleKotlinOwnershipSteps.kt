package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.java.JavaGradleContextSession
import org.refactorkit.java.GradleJavaSyncRequest
import org.refactorkit.java.GradleJavaSyncResult
import org.refactorkit.java.JavaProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JavaGradleKotlinOwnershipSteps {
    private lateinit var fixture: Path
    private lateinit var root: Path
    private lateinit var main: BuildSourceSet
    private lateinit var expectedInputs: Map<Path, String>

    @Given("an evaluated Gradle compiler fixture is {string}")
    fun setUp(kind: String) {
        fixture = Files.createTempDirectory("rk-gradle-kotlin-ownership-")
        root = Files.createDirectory(fixture.resolve("workspace"))
        val input = Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_FIXTURE")))
        val sources = when (kind) {
            "Java Kotlin" -> listOf("settings.gradle", "build.gradle", "src/main/java/fixture/Main.java", "src/main/kotlin/fixture/Greeter.kt")
                .associateWith { Files.readString(input.resolve(it)) }
            "Java only" -> mapOf(
                "settings.gradle" to "rootProject.name = 'java-only'\n",
                "build.gradle" to "plugins { id 'java' }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n",
                "src/main/java/fixture/Main.java" to "package fixture; public final class Main {}\n",
            )
            else -> error(kind)
        }
        expectedInputs = sources.mapKeys { (name, _) -> root.resolve(name) }
        expectedInputs.forEach { (path, value) ->
            Files.createDirectories(path.parent)
            Files.writeString(path, value)
        }
    }

    @When("the explicit Gradle model synchronizes the compiler fixture")
    fun synchronize() {
        val sdk = Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_JDK")))
        val executable = Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_EXECUTABLE")))
        val scanner = JavaProjectScanner()
        val snapshot = scanner.scan(root)
        val result = JavaGradleContextSession().synchronize(
            GradleJavaSyncRequest(snapshot.hash, sdk, executable, allowBuildExecution = true,
                gradleUserHome = Files.createDirectory(fixture.resolve("gradle-home"))),
            { scanner.scan(root) }, SemanticCancellationToken.NONE,
        )
        main = assertIs<GradleJavaSyncResult.Available>(result).model.modules.single().sourceSets.single { it.id == "main" }
    }

    @Then("the Gradle source set reports {string} and keeps its Java source ownership")
    fun verify(outcome: String) {
        assertTrue(Path.of("src/main/java") in main.sourceRoots)
        assertFalse(Path.of("src/main/kotlin") in main.sourceRoots)
        assertTrue(main.languageFacets.any { it.languageId == "java" })
        when (outcome) {
            "owned Kotlin compiler inputs" -> {
                val compiler = assertNotNull(main.languageFacets.singleOrNull { it.languageId == "kotlin" })
                assertEquals("jvm", compiler.platformId)
                assertEquals("21", compiler.targetVersion)
                assertEquals("21", compiler.targetRuntimeVersion)
                assertEquals(":compileKotlin", main.attributes["kotlin.compileTaskPath"])
                assertEquals("mixed-jvm", main.attributes["kotlin.moduleName"])
                assertEquals("build/classes/kotlin/main", main.attributes["kotlin.outputDirectory"])
                val count = assertNotNull(main.attributes["kotlin.sourceRoot.count"]).toInt()
                assertEquals(setOf("src/main/kotlin", "src/main/java"), (0 until count)
                    .map { main.attributes["kotlin.sourceRoot.$it"] }.toSet())
            }
            "Java compiler inputs only" -> {
                assertFalse(main.languageFacets.any { it.languageId == "kotlin" })
                assertFalse(main.attributes.keys.any { it.startsWith("kotlin.") })
            }
            else -> error(outcome)
        }
        expectedInputs.forEach { (path, bytes) -> assertEquals(bytes, Files.readString(path), path.toString()) }
    }

    @After
    fun close() {
        if (::fixture.isInitialized) fixture.toFile().deleteRecursively()
    }
}

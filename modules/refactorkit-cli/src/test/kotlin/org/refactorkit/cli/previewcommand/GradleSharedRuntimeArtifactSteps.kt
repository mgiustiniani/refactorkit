package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.And
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleSharedRuntimeArtifactSteps {
    private var fixture: GradleSharedRuntimeArtifactFixture? = null

    @Given("a Gradle Java app selects {string} from projects with one shared JAR output")
    fun prepare(dependencies: String) {
        fixture = GradleSharedRuntimeArtifactFixture(dependencies)
    }

    @When("the shared-output Gradle workspace is explicitly synchronized and aggregate context requested")
    fun synchronize() = requireNotNull(fixture).synchronize()

    @Then("the shared runtime JAR outcome is {string}")
    fun outcome(expected: String) = requireNotNull(fixture).verify(expected)

    @And("the shared-output Gradle workspace retains its Java and build input bytes")
    fun preserve() = requireNotNull(fixture).verifyInputs()

    @After
    fun close() {
        fixture?.close()
        fixture = null
    }
}

private class GradleSharedRuntimeArtifactFixture(dependencies: String) : AutoCloseable {
    private val gradle = Path.of(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE")
        ?: requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE"))).toRealPath()
    private val sdk = Path.of(System.getProperty("java.home")).toRealPath()
    private val directory = Files.createTempDirectory("rk-gradle-shared-runtime-artifact-")
    private val root = directory.resolve("project")
    private val gradleHome = directory.resolve("gradle-home")
    private val session = newGradleContextRpcSession()
    private val inputs = linkedMapOf<String, String>()
    private lateinit var sync: JsonObject
    private lateinit var context: JsonObject

    init {
        assertTrue(Files.isExecutable(gradle))
        write("settings.gradle", "rootProject.name='shared-output'; include 'app', 'library', 'shadow'\n")
        write("gradle.properties", "org.gradle.jvmargs=-Xmx256m\n")
        write("build.gradle", """
            subprojects {
                apply plugin: 'java-library'
                tasks.withType(JavaCompile).configureEach { options.release = 21 }
            }
            configure([project(':library'), project(':shadow')]) {
                tasks.named('jar') {
                    destinationDirectory = rootProject.layout.buildDirectory.dir('shared')
                    archiveFileName = 'shared.jar'
                }
            }
        """.trimIndent() + "\n")
        val declaration = when (dependencies) {
            "library" -> "implementation project(':library')"
            "shadow" -> "runtimeOnly project(':shadow')"
            "both" -> "implementation project(':library'); runtimeOnly project(':shadow')"
            else -> error("Unknown selected dependency fixture $dependencies")
        }
        write("app/build.gradle", "dependencies { $declaration }\n")
        write("app/src/main/java/app/App.java", "package app; public class App {}\n")
        for (module in listOf("library", "shadow")) {
            write("$module/src/main/java/shared/Identity.java",
                "package shared; public class Identity { public static String name() { return \"$module\"; } }\n")
        }
        session.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
    }

    fun synchronize() {
        val initial = session.dispatch("project.summary", null).jsonObject.getValue("snapshotHash").jsonPrimitive.content
        sync = session.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", initial)
            put("gradleExecutable", gradle.toString())
            put("gradleUserHome", gradleHome.toString())
            put("sdkHome", sdk.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        context = session.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", sync["snapshotHash"]?.jsonPrimitive?.content ?: initial)
            put("sdkHome", sdk.toString())
        }).jsonObject
    }

    fun verify(expected: String) {
        if (expected == "ambiguous") {
            assertEquals("refused", sync.getValue("status").jsonPrimitive.content, sync.toString())
            assertEquals("gradle.sync.runtimeArtifactOwnerAmbiguous",
                sync.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertFalse(context["providers"]?.jsonArray.orEmpty().any {
                val provider = it.jsonObject
                provider["providerId"]?.jsonPrimitive?.content == "gradle-effective-v1" &&
                    provider["status"]?.jsonPrimitive?.content == "available"
            }, context.toString())
            return
        }
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
        val app = context.getValue("modules").jsonArray.map { it.jsonObject }.single {
            it.getValue("id").jsonPrimitive.content == ":app"
        }
        val main = app.getValue("sourceSets").jsonArray.map { it.jsonObject }.single {
            it.getValue("id").jsonPrimitive.content == "main"
        }
        val entries = main.getValue("runtimeClasspath").jsonArray.map { it.jsonObject }
        val jar = entries.single { it.getValue("path").jsonPrimitive.content == "build/shared/shared.jar" }
        assertEquals(expected, jar.getValue("moduleId").jsonPrimitive.content, jar.toString())
        assertEquals("main", jar.getValue("sourceSetId").jsonPrimitive.content)
        assertEquals(if (expected == ":library") "compile" else "runtime",
            jar.getValue("dependencyScope").jsonPrimitive.content, jar.toString())
    }

    fun verifyInputs() {
        inputs.forEach { (path, content) -> assertEquals(content, Files.readString(root.resolve(path)), path) }
        assertFalse(Files.exists(root.resolve("build/shared/shared.jar")), "Synchronization must not build the JAR")
    }

    private fun write(relative: String, content: String) {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
        inputs[relative] = content
    }

    override fun close() {
        try {
            session.close()
        } finally {
            check(directory.toFile().deleteRecursively())
        }
    }
}

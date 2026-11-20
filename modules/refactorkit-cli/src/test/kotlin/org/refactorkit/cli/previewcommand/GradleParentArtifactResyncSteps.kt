package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleParentArtifactResyncSteps {
    private var fixture: GradleParentArtifactResyncFixture? = null

    @Given("a Gradle nested Java app substitutes a library owned by its parent build")
    fun prepare() {
        fixture = GradleParentArtifactResyncFixture()
    }

    @When("the composite is synchronized before and after running both applications")
    fun synchronize() = requireNotNull(fixture).synchronize()

    @Then("the refreshed composite keeps the evaluated task path and the built parent library owner")
    fun verify() = requireNotNull(fixture).verify()

    @After
    fun close() {
        fixture?.close()
        fixture = null
    }
}

private class GradleParentArtifactResyncFixture : AutoCloseable {
    private val gradle = Path.of(System.getenv("REFACTORKIT_GRADLE_9_8_1_EXECUTABLE")
        ?: error("Set REFACTORKIT_GRADLE_9_8_1_EXECUTABLE for the qualified composite")).toRealPath()
    private val sdk = Path.of(System.getProperty("java.home")).toRealPath()
    private val directory = Files.createTempDirectory("rk-gradle-parent-artifact-resync-")
    private val root = directory.resolve("composite")
    private val gradleHome = directory.resolve("gradle-home")
    private val jarPath = "library/build/libs/library-1.0.jar"
    private val session = newGradleContextRpcSession()
    private val inputs = mapOf(
        "settings.gradle" to "rootProject.name='composite'; includeBuild 'included-build'",
        "gradle.properties" to "org.gradle.parallel=false\norg.gradle.daemon=false\n",
        "included-build/settings.gradle" to """
            rootProject.name = 'included-build'
            include 'app', 'library'
            project(':app').projectDir = file('../app')
            project(':library').projectDir = file('../library')
            includeBuild('second-build') { name = 'nested-app' }
        """.trimIndent(),
        "included-build/build.gradle" to """
            subprojects {
                apply plugin: 'java-library'
                group = 'fixture'
                version = '1.0'
                tasks.withType(JavaCompile).configureEach { options.release = 17 }
            }
        """.trimIndent(),
        "app/build.gradle" to """
            apply plugin: 'application'
            application { mainClass = 'fixture.ParentApp' }
            dependencies { implementation project(':library') }
        """.trimIndent(),
        "app/src/main/java/fixture/ParentApp.java" to """
            package fixture;
            public final class ParentApp {
                public static void main(String[] args) {
                    System.out.println("RK_PARENT " + Library.value());
                }
            }
        """.trimIndent(),
        "library/build.gradle" to "",
        "library/src/main/java/fixture/Library.java" to """
            package fixture;
            public final class Library {
                public static String value() { return "parent-library"; }
            }
        """.trimIndent(),
        "included-build/second-build/settings.gradle" to "rootProject.name='leaf'; include 'app'",
        "included-build/second-build/app/build.gradle" to """
            plugins { id 'application' }
            application { mainClass = 'fixture.NestedApp' }
            dependencies { implementation 'fixture:library:1.0' }
        """.trimIndent(),
        "included-build/second-build/app/src/main/java/fixture/NestedApp.java" to """
            package fixture;
            public final class NestedApp {
                public static void main(String[] args) {
                    System.out.println("RK_NESTED " + Library.value());
                }
            }
        """.trimIndent(),
    )
    private lateinit var cold: JsonObject
    private lateinit var warm: JsonObject
    private lateinit var builtJarHash: String

    init {
        assertTrue(Files.isExecutable(gradle))
        for ((path, body) in inputs) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, body)
        }
    }

    fun synchronize() {
        val open = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        cold = sync(open.getValue("snapshotHash"))
        assertFalse(Files.exists(root.resolve(jarPath)), "Cold metadata export must not build the library")
        val log = directory.resolve("run.log").toFile()
        val process = ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--console=plain", "--max-workers=1",
            ":included-build:nested-app:app:run", ":included-build:app:run")
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log).apply {
                environment()["JAVA_HOME"] = sdk.toString()
                environment()["GRADLE_USER_HOME"] = gradleHome.toString()
            }.start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Gradle composite run timed out")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.lineSequence().any { it == "RK_NESTED parent-library" }, output)
        assertTrue(output.lineSequence().any { it == "RK_PARENT parent-library" }, output)
        builtJarHash = jarHash()
        val saved = session.dispatch("workspace.refresh", buildJsonObject {}).jsonObject
        warm = sync(saved.getValue("snapshotHash"))
    }

    private fun sync(snapshotHash: JsonElement): JsonObject {
        val synced = session.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", sdk.toString())
            put("gradleExecutable", gradle.toString())
            put("gradleUserHome", gradleHome.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", synced.getValue("status").jsonPrimitive.content, synced.toString())
        assertEquals("9.8.1", synced.getValue("gradleVersion").jsonPrimitive.content)
        return session.dispatch("project.context.aggregate", buildJsonObject {
            put("expectedSnapshotHash", synced.getValue("snapshotHash"))
            put("sdkHome", sdk.toString())
            put("includeGradleBuildTreeProjectPaths", true)
        }).jsonObject
    }

    fun verify() {
        val path = ":included-build:nested-app:app"
        for (context in listOf(cold, warm)) {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            val modules = context.getValue("modules").jsonArray.map { it.jsonObject }
            val nested = modules.single { it.getValue("buildTreeProjectPath").jsonPrimitive.content == path }
            val library = modules.single { it.getValue("buildTreeProjectPath").jsonPrimitive.content == ":included-build:library" }
            assertEquals("included-build", library.getValue("buildRoot").jsonPrimitive.content)
            assertEquals("library", library.getValue("root").jsonPrimitive.content)
            val main = nested.getValue("sourceSets").jsonArray.map { it.jsonObject }.single {
                it.getValue("id").jsonPrimitive.content == "main"
            }
            val entry = main.getValue("runtimeClasspath").jsonArray.map { it.jsonObject }.single {
                it.getValue("path").jsonPrimitive.content == jarPath
            }
            assertEquals(library.getValue("id"), entry.getValue("moduleId"))
            assertEquals("main", entry.getValue("sourceSetId").jsonPrimitive.content)
            assertEquals("compile", entry.getValue("dependencyScope").jsonPrimitive.content)
        }
        assertEquals(builtJarHash, jarHash(), "Warm synchronization must preserve the built artifact bytes")
        for ((name, content) in inputs) assertEquals(content, Files.readString(root.resolve(name)), name)
    }

    private fun jarHash(): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(jarPath)))
        .joinToString("") { "%02x".format(it) }

    override fun close() {
        try {
            session.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

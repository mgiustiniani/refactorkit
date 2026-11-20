package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GradleBuildTreeProjectPathSteps {
    private var fixture: GradleBuildTreeProjectPathFixture? = null

    @Given("a transitive Gradle application composite with custom aliases on {string}")
    fun prepare(version: String) {
        fixture = GradleBuildTreeProjectPathFixture(version)
    }

    @When("the aliased composite is synchronized and build tree paths are explicitly requested")
    fun synchronize() = requireNotNull(fixture).synchronize()

    @Then("the exported build tree project path launches the substituted library from the composite root")
    fun verify() = requireNotNull(fixture).verify()

    @Then("selected Gradle v7 and v3 contexts retain the build tree path before and after compilation")
    fun verifySelected() = requireNotNull(fixture).verifySelected()

    @After
    fun close() {
        fixture?.close()
        fixture = null
    }
}

private class GradleBuildTreeProjectPathFixture(private val version: String) : AutoCloseable {
    private val checkout = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val gradle = (System.getenv("REFACTORKIT_GRADLE_${version.replace('.', '_')}_EXECUTABLE")
        ?.let(Path::of) ?: checkout.resolve(".tools/gradle-$version/bin/gradle")).toRealPath()
    private val sdk = Path.of(System.getProperty("java.home")).toRealPath()
    private val directory = Files.createTempDirectory("rk-gradle-build-tree-path-")
    private val root = directory.resolve("composite")
    private val gradleHome = directory.resolve("gradle-home")
    private val session = newGradleContextRpcSession()
    private val inputs = mapOf(
        "settings.gradle" to """
            rootProject.name = 'composite'
            includeBuild('parent-dir') { name = 'parentAlias' }
            includeBuild('library-dir') { name = 'libraryAlias' }
        """.trimIndent(),
        "build.gradle" to "plugins { id 'java-library' }",
        "gradle.properties" to "org.gradle.jvmargs=-Xmx256m",
        "parent-dir/settings.gradle" to """
            rootProject.name = 'parent'
            includeBuild('leaf-dir') { name = 'leafAlias' }
        """.trimIndent(),
        "parent-dir/leaf-dir/settings.gradle" to "rootProject.name = 'leaf'; include ':app'",
        "parent-dir/leaf-dir/app/build.gradle" to """
            plugins { id 'application' }
            application { mainClass = 'fixture.App' }
            dependencies { implementation 'fixture:library:1.0' }
        """.trimIndent(),
        "parent-dir/leaf-dir/app/src/main/java/fixture/App.java" to """
            package fixture;
            public class App {
                public static void main(String[] args) {
                    System.out.println("RK_RUNTIME " + Library.value());
                }
            }
        """.trimIndent(),
        "library-dir/settings.gradle" to "rootProject.name = 'library'",
        "library-dir/build.gradle" to "plugins { id 'java-library' }; group = 'fixture'; version = '1.0'",
        "library-dir/src/main/java/fixture/Library.java" to """
            package fixture;
            public class Library {
                public static String value() { return "included-library"; }
            }
        """.trimIndent(),
    )
    private lateinit var defaultContext: JsonObject
    private lateinit var optedInContext: JsonObject

    init {
        assertTrue(Files.isExecutable(gradle))
        for ((name, content) in inputs) {
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            Files.writeString(path, content)
        }
    }

    fun synchronize() {
        val opened = session.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
        val sync = session.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", opened.getValue("snapshotHash"))
            put("sdkHome", sdk.toString())
            put("gradleExecutable", gradle.toString())
            put("gradleUserHome", gradleHome.toString())
            put("allowBuildExecution", true)
        }).jsonObject
        assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
        assertEquals(version, sync.getValue("gradleVersion").jsonPrimitive.content)
        val params = buildJsonObject {
            put("expectedSnapshotHash", sync.getValue("snapshotHash"))
            put("sdkHome", sdk.toString())
        }
        defaultContext = session.dispatch("project.context.aggregate", params).jsonObject
        optedInContext = session.dispatch("project.context.aggregate", buildJsonObject {
            params.forEach { (name, value) -> put(name, value) }
            put("includeGradleBuildTreeProjectPaths", true)
        }).jsonObject
    }

    fun verify() {
        for (context in listOf(defaultContext, optedInContext)) {
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            assertEquals("java-project-context-v3", context.getValue("schemaVersion").jsonPrimitive.content)
        }
        val modules = optedInContext.getValue("modules").jsonArray.map { it.jsonObject }
        assertEquals(
            mapOf(
                ("." to ":") to ":",
                ("parent-dir/leaf-dir" to ":app") to ":parentAlias:leafAlias:app",
                ("library-dir" to ":") to ":libraryAlias",
            ),
            modules.associate { row ->
                (row.getValue("buildRoot").jsonPrimitive.content to row.getValue("projectPath").jsonPrimitive.content) to
                    row.getValue("buildTreeProjectPath").jsonPrimitive.content
            },
        )
        assertEquals(defaultContext.getValue("modules").jsonArray.map { it.jsonObject },
            modules.map { JsonObject(it - "buildTreeProjectPath") })
        val capability = session.dispatch("server.capabilities", null).jsonObject.getValue("methods").jsonArray.single {
            it.jsonObject.getValue("name").jsonPrimitive.content == "project.context.aggregate"
        }.jsonObject
        assertEquals("true", capability.getValue("features").jsonObject
            .getValue("gradleBuildTreeProjectPath").jsonPrimitive.content)
        val app = modules.single { it.getValue("projectPath").jsonPrimitive.content == ":app" }
        val appPath = app.getValue("buildTreeProjectPath").jsonPrimitive.content
        val log = directory.resolve("run.log").toFile()
        val process = ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--console=plain", "--max-workers=1",
            "$appPath:run").directory(root.toFile()).redirectErrorStream(true).redirectOutput(log).apply {
            environment()["JAVA_HOME"] = sdk.toString()
            environment()["GRADLE_USER_HOME"] = gradleHome.toString()
        }.start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Gradle composite task timed out")
        }
        assertEquals(0, process.exitValue(), log.readText())
        assertTrue(log.readText().lineSequence().any { it == "RK_RUNTIME included-library" }, log.readText())
        for ((name, content) in inputs) assertEquals(content, Files.readString(root.resolve(name)), name)
    }

    fun verifySelected() {
        val application = optedInContext.getValue("modules").jsonArray.single {
            it.jsonObject.getValue("projectPath").jsonPrimitive.content == ":app"
        }.jsonObject
        val expected = application.getValue("buildTreeProjectPath").jsonPrimitive.content
        val methods = session.dispatch("server.capabilities", null).jsonObject.getValue("methods").jsonArray
        val selectedMethods = mapOf("project.context.v7" to "java-project-context-v7",
            "project.context" to "java-project-context-v3")
        fun verifyReadback() {
            val snapshot = session.dispatch("project.summary", null).jsonObject.getValue("snapshotHash")
            for ((method, version) in selectedMethods) {
                val arguments = buildJsonObject {
                    put("expectedSnapshotHash", snapshot)
                    put("sdkHome", sdk.toString())
                    put("providerId", application.getValue("providerId"))
                    put("moduleId", application.getValue("id"))
                    put("sourceSetId", "main")
                }
                val legacy = session.dispatch(method, arguments).jsonObject
                val optedIn = session.dispatch(method, buildJsonObject {
                    arguments.forEach { (name, value) -> put(name, value) }
                    put("includeGradleBuildTreeProjectPaths", true)
                }).jsonObject
                assertEquals("available", optedIn.getValue("status").jsonPrimitive.content, optedIn.toString())
                assertEquals(version, optedIn.getValue("schemaVersion").jsonPrimitive.content)
                val selected = optedIn.getValue("modules").jsonArray.map { it.jsonObject }
                val actual = selected.single { it.getValue("id") == application.getValue("id") }
                assertEquals(expected, actual.getValue("buildTreeProjectPath").jsonPrimitive.content)
                assertEquals(legacy.getValue("modules").jsonArray.map { it.jsonObject },
                    selected.map { JsonObject(it - "buildTreeProjectPath") })
                val capability = methods.single { it.jsonObject.getValue("name").jsonPrimitive.content == method }
                    .jsonObject.getValue("features").jsonObject
                assertEquals("true", capability.getValue("gradleBuildTreeProjectPath").jsonPrimitive.content)
            }
        }
        verifyReadback()
        verify()
        verifyReadback()
    }

    override fun close() {
        try {
            session.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

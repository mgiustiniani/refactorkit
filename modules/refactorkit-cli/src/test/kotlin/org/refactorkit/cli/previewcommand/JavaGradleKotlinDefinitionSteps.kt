package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaGradleKotlinDefinitionSteps {
    private lateinit var fixture: Path
    private lateinit var root: Path
    private lateinit var gradleHome: Path
    private lateinit var before: Map<String, List<Byte>>
    private lateinit var result: JsonObject
    private lateinit var condition: String
    private val javaPath = "src/main/java/fixture/Main.java"
    private val kotlinPath = "src/main/kotlin/fixture/Greeter.kt"
    private val sdk by lazy { Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_JDK"))) }
    private val gradle by lazy { Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_EXECUTABLE"))) }

    @Given("the daemon advertises optional Kotlin definition tooling before opening a project")
    fun verifyCapabilityDiscovery() {
        DaemonSession().use { daemon ->
            val methods = daemon.dispatch("server.capabilities", buildJsonObject {}).jsonObject.getValue("methods").jsonArray
            val query = methods.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "intelligence.query" }
            assertEquals(JsonPrimitive(true), query.getValue("features").jsonObject["kotlinDefinitionToolchain"])
        }
    }

    @Given("the saved Gradle JVM definition fixture is {string}")
    fun prepare(value: String) {
        condition = value
        fixture = Files.createTempDirectory("rk-gradle-jvm-definition-")
        root = Files.createDirectory(fixture.resolve("workspace"))
        gradleHome = Files.createDirectory(fixture.resolve("gradle-home"))
        val inputs = Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_GRADLE_FIXTURE")))
        for (name in listOf("settings.gradle", "build.gradle", javaPath, kotlinPath)) {
            val output = root.resolve(name)
            Files.createDirectories(output.parent)
            Files.copy(inputs.resolve(name), output)
        }
        val log = fixture.resolve("gradle.log")
        val build = ProcessBuilder(gradle.toString(), "--offline", "--no-daemon", "--no-watch-fs", "--max-workers=1", "--console=plain", ":run")
            .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).apply {
                environment()["JAVA_HOME"] = sdk.toString()
                environment()["GRADLE_USER_HOME"] = gradleHome.toString()
            }.start()
        try {
            assertTrue(build.waitFor(100, TimeUnit.SECONDS), "Gradle fixture did not finish")
            assertEquals(0, build.exitValue(), Files.readString(log))
            assertTrue(Files.readAllLines(log).contains("java-kotlin"))
        } finally {
            if (build.isAlive) build.destroyForcibly()
        }
        val compiled = root.resolve("build/classes/kotlin/main/fixture/Greeter.class")
        assertTrue(Files.isRegularFile(compiled))
        when (condition) {
            "stale bytecode" -> {
                val kotlin = root.resolve(kotlinPath)
                val old = Files.readString(kotlin)
                val changed = old.replace("-kotlin", "-changed")
                assertTrue(old != changed)
                Files.writeString(kotlin, changed)
            }
            "ambiguous binary" -> {
                JarOutputStream(Files.newOutputStream(root.resolve("shadow.jar"))).use { jar ->
                    jar.putNextEntry(JarEntry("fixture/Greeter.class"))
                    Files.copy(compiled, jar)
                    jar.closeEntry()
                }
                Files.writeString(root.resolve("build.gradle"), "\ndependencies { implementation files('shadow.jar') }\n",
                    java.nio.file.StandardOpenOption.APPEND)
            }
        }
    }

    @When("the daemon resolves the selected Java token with its bound context")
    fun query() {
        DaemonSession().use { daemon ->
            val open = daemon.dispatch("project.open", buildJsonObject { put("root", root.toString()) }).jsonObject
            val sync = daemon.dispatch("project.sync", buildJsonObject {
                put("buildSystem", "gradle"); put("allowBuildExecution", true)
                put("expectedSnapshotHash", open.getValue("snapshotHash"))
                put("gradleExecutable", gradle.toString()); put("gradleUserHome", gradleHome.toString())
                put("sdkHome", sdk.toString())
            }).jsonObject
            assertEquals("synchronized", sync.getValue("status").jsonPrimitive.content, sync.toString())
            val context = daemon.dispatch("project.context.aggregate", buildJsonObject {
                put("sdkHome", sdk.toString()); put("expectedSnapshotHash", sync.getValue("snapshotHash"))
            }).jsonObject
            assertEquals("available", context.getValue("status").jsonPrimitive.content, context.toString())
            before = scopedImages()
            val token = when (condition) {
                "Kotlin type" -> "Greeter"
                "Java control" -> "fromJava"
                else -> "message"
            }
            val lines = Files.readAllLines(root.resolve(javaPath))
            val line = lines.indexOfFirst { token in it }
            val character = lines[line].indexOf(token) + 1
            result = daemon.dispatch("intelligence.query", buildJsonObject {
                put("requestId", "gradle-jvm-definition"); put("kind", "definition"); put("languageId", "java")
                put("path", javaPath); put("expectedSnapshotHash", sync.getValue("snapshotHash"))
                put("position", buildJsonObject { put("line", line); put("character", character) })
                put("sourceAuthority", buildJsonObject { put("kind", "saved-snapshot") })
                put("javaContext", buildJsonObject {
                    put("sdkHome", sdk.toString()); put("expectedContextHash", context.getValue("contextHash"))
                    put("contextSchemaVersion", context.getValue("schemaVersion"))
                })
                if (condition != "missing compiler") {
                    val compiler = Path.of(requireNotNull(System.getenv("REFACTORKIT_IDE_KOTLIN_DEFINITION_COMPILER")))
                    put("kotlinDefinitionToolchain", Json.parseToJsonElement(Files.readString(compiler)))
                }
            }).jsonObject
        }
    }

    @Then("the definition returns {string} and preserves the saved workspace and binaries")
    fun verify(expected: String) {
        when (expected) {
            "exact Kotlin method" -> exact(kotlinPath, "message")
            "exact Kotlin object" -> exact(kotlinPath, "Greeter")
            "exact Java method" -> exact(javaPath, "fromJava")
            "compiled Kotlin drift" -> refused("java.definitionKotlinBinaryDrift")
            "Kotlin binary ambiguity" -> refused("java.definitionKotlinBinaryAmbiguous")
            "Kotlin authority required" -> refused("java.definitionKotlinSourceUnqualified")
            else -> error(expected)
        }
        assertEquals(before, scopedImages())
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    private fun exact(path: String, token: String) {
        assertEquals("ready", result.getValue("status").jsonPrimitive.content, result.toString())
        val location = result.getValue("locations").jsonArray.single().jsonObject
        assertEquals(path, location.getValue("path").jsonPrimitive.content)
        val text = Files.readAllLines(root.resolve(path))
        val line = text.indexOfFirst { token in it }
        val character = text[line].indexOf(token)
        val expectedRange = buildJsonObject {
            put("start", buildJsonObject { put("line", line); put("character", character) })
            put("end", buildJsonObject { put("line", line); put("character", character + token.length) })
        }
        assertEquals(expectedRange, location.getValue("range"))
    }

    private fun refused(code: String) {
        assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
        assertEquals(code, result.getValue("error").jsonObject.getValue("code").jsonPrimitive.content, result.toString())
        assertFalse("locations" in result)
    }

    private fun scopedImages(): Map<String, List<Byte>> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) }.filter { path ->
            val relative = root.relativize(path)
            relative.startsWith("src") || relative.startsWith("build/classes") || relative.nameCount == 1
        }.toList().associate { path ->
            root.relativize(path).toString() to MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toList()
        }
    }

    @After
    fun cleanup() {
        if (::fixture.isInitialized) fixture.toFile().deleteRecursively()
    }
}

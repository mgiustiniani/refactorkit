package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.cucumber.java.en.And
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class GradleExportFailureSteps {
    private val fixture = GradleExportFailureFixture()

    @Given("an explicit Gradle export failure fixture {string}")
    fun prepare(kind: String) = fixture.prepare(kind)

    @When("the failing exporter is synchronized through the public project API")
    fun synchronize() = fixture.synchronize()

    @Then("the sync refusal retains {string} and bounded cause {string}")
    fun refusal(code: String, cause: String) = fixture.verify(code, cause)

    @And("the export refusal reveals no raw process secrets and publishes no model")
    fun confined() = fixture.verifyConfinement()

    @After
    fun close() = fixture.close()
}

private class GradleExportFailureFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var root: Path
    private lateinit var userHome: Path
    private lateinit var executable: Path
    private var session: GradleContextRpcSession? = null
    private lateinit var snapshotHash: String
    private lateinit var result: JsonObject
    private var baseline: Map<Path, ByteArray> = emptyMap()

    fun prepare(kind: String) {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of("build/test-tmp")), "gradle-export-failure-").toAbsolutePath()
        base = directory
        root = Files.createDirectory(directory.resolve("workspace"))
        userHome = Files.createDirectory(directory.resolve("cache"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='failure-fixture'\n")
        Files.writeString(root.resolve("gradle.properties"), "org.gradle.jvmargs=-Xmx256m\n")
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\nrepositories { mavenCentral() }\ndependencies { implementation 'org.refactorkit.unavailable:missing:0.0.0-fixture' }\n")
        val source = root.resolve("src/main/java/Example.java")
        Files.createDirectories(source.parent)
        Files.writeString(source, "public class Example {}\n")
        baseline = listOf("settings.gradle", "gradle.properties", "build.gradle", "src/main/java/Example.java")
            .associate { Path.of(it) to Files.readAllBytes(root.resolve(it)) }
        executable = if (kind in setOf("real offline dependency", "real cache permission denied", "real empty cache denied")) {
            Path.of(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE")))
                .also { assertTrue(Files.isExecutable(it)) }
        } else {
            val peer = directory.resolve("peer/bin/gradle")
            Files.createDirectories(peer.parent)
            val payload = when (kind) {
                "cache read only" -> "java.io.FileNotFoundException: $userHome/native/libnative-platform.so.lock (Read-only file system)"
                "cache permission denied" -> "java.io.FileNotFoundException: $userHome/caches/journal-1/journal.lock (Permission denied)"
                "unrelated path denied" -> "java.io.FileNotFoundException: ${userHome}-unrelated/native/file.lock (Permission denied)"
                "unclassified secret log" -> "Build failed for an unclassified reason"
                "fast oversized log" -> ""
                else -> error("Unknown Gradle export fixture: $kind")
            }
            val body = if (kind == "fast oversized log") "head -c 1048577 /dev/zero\n" else
                "cat <<'REFACTORKIT_TEST_OUTPUT'\n$payload\npassword=fixture-secret-never-return https://fixture-user:fixture-password@example.invalid/repository\nREFACTORKIT_TEST_OUTPUT\n"
            Files.writeString(peer, "#!/bin/sh\n" + body + "exit 1\n")
            assertTrue(peer.toFile().setExecutable(true, true))
            peer
        }
        if (kind in setOf("real cache permission denied", "real empty cache denied")) {
            val realGradle = executable
            if (kind == "real cache permission denied") {
                val warmup = ProcessBuilder(realGradle.toString(), "--offline", "--version")
                    .redirectErrorStream(true).redirectOutput(directory.resolve("warmup.log").toFile()).apply {
                        environment()["GRADLE_USER_HOME"] = userHome.toString()
                        environment()["JAVA_HOME"] = System.getProperty("java.home")
                    }.start()
                assertTrue(warmup.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(0, warmup.exitValue(), "Native cache initialization must succeed before access is denied")
                val libraries = Files.walk(userHome.resolve("native")).use { paths ->
                    paths.filter { it.fileName.toString() == "libnative-platform.so" }.toList()
                }
                assertTrue(libraries.isNotEmpty())
                for (library in libraries) {
                    Files.delete(library)
                    Files.writeString(library.resolveSibling("libnative-platform.so.lock"), "")
                }
                Files.walk(userHome).use { paths -> paths.forEach { path ->
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(if (Files.isDirectory(path)) "r-x------" else "r--------"))
                } }
            }
            val observation = System.getenv("REFACTORKIT_GRADLE_FAILURE_EVIDENCE_DIR")?.let(Path::of)
                ?: directory.resolve("process-evidence")
            Files.createDirectories(observation)
            val output = observation.resolve(if (kind == "real cache permission denied") "cache-permission-process.log" else "empty-cache-process.log")
            val peer = directory.resolve("native-peer/bin/gradle")
            Files.createDirectories(peer.parent)
            fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
            Files.writeString(peer, "#!/bin/sh\n" + quote(realGradle.toString()) + " \"${'$'}@\" > " + quote(output.toString()) +
                " 2>&1\nstatus=${'$'}?\ncat " + quote(output.toString()) + "\nexit ${'$'}status\n")
            assertTrue(peer.toFile().setExecutable(true, true))
            executable = peer
            Files.setPosixFilePermissions(userHome, PosixFilePermissions.fromString("r-x------"))
            val denied = runCatching { Files.writeString(userHome.resolve("write-probe"), "must refuse") }.exceptionOrNull()
            assertIs<AccessDeniedException>(denied, "The native cache-permission fixture must actually deny writes")
        }
        session = newGradleContextRpcSession()
        snapshotHash = session!!.dispatch("project.open", buildJsonObject { put("root", root.toString()) })
            .jsonObject.getValue("snapshotHash").jsonPrimitive.content
    }

    fun synchronize() {
        result = session!!.dispatch("project.sync", buildJsonObject {
            put("buildSystem", "gradle")
            put("expectedSnapshotHash", snapshotHash)
            put("sdkHome", System.getProperty("java.home"))
            put("gradleExecutable", executable.toString())
            put("gradleUserHome", userHome.toString())
            put("allowBuildExecution", true)
        }).jsonObject
    }

    fun verify(code: String, cause: String) {
        assertEquals("java-gradle-sync-v1", result.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("refused", result.getValue("status").jsonPrimitive.content, result.toString())
        val error = result.getValue("error").jsonObject
        assertEquals(code, error.getValue("code").jsonPrimitive.content, result.toString())
        if (cause == "absent") assertNull(error["cause"], result.toString()) else {
            val detail = assertNotNull(error["cause"], result.toString()).jsonObject
            assertEquals(cause, detail.getValue("code").jsonPrimitive.content)
            assertTrue(detail.getValue("message").jsonPrimitive.content.length in 1..256)
            assertEquals("process-output", detail.getValue("evidence").jsonPrimitive.content)
        }
    }

    fun verifyConfinement() {
        val serialized = result.toString()
        assertTrue(serialized.length < 1024, serialized)
        for (forbidden in listOf("fixture-secret-never-return", "fixture-user", "fixture-password", "example.invalid", root.toString(), userHome.toString()))
            assertFalse(serialized.contains(forbidden), "Raw process evidence escaped the refusal")
        assertNull(result["snapshotHash"])
        assertNull(result["providerId"])
        assertNull(result["modules"])
        baseline.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(root.resolve(path))) }
        assertFalse(Files.exists(root.resolve("build/classes")), "Metadata sync must not compile Java sources")
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
    }

    override fun close() {
        session?.close()
        if (::userHome.isInitialized && Files.isDirectory(userHome)) {
            Files.walk(userHome).use { paths -> paths.filter(Files::isDirectory).forEach { path ->
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            } }
        }
        base?.let { directory -> Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

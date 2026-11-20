package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Owned real Gradle project: no fake resolution identifiers or production hooks. */
internal class RuntimeComponentManifestFixture(private val condition: String) : AutoCloseable {
    private val repository = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    private val base = repository.resolve(".refactorkit/runs/runtime-component-fixtures").also { Files.createDirectories(it) }
    private val owned = Files.createTempDirectory(base, "case-")
    private val root = Files.createDirectory(owned.resolve("source"))
    private val output = root.resolve(".artifacts/components.json")
    private val second = root.resolve(".artifacts/components-again.json")
    private val canary = owned.resolve("outside-output.txt")
    private val logs = mutableListOf<Path>()
    private var process: Process? = null
    private var exit = -1
    private var sourceBefore = emptyMap<String, String>()

    init {
        Files.createDirectories(output.parent)
        Files.writeString(canary, "owned outside-output canary\n")
        write("settings.gradle", "rootProject.name = 'manifest-cli'\ninclude 'library'\n")
        write("build.gradle", """
            plugins { id 'application' }
            allprojects { group = 'org.refactorkit.fixture'; version = '0.0.0-fixture' }
            application { mainClass = 'App' }
            repositories { mavenCentral(); maven { url = uri('repository') } }
            dependencies { implementation project(':library'); implementation 'javax.inject:javax.inject:1' }
            apply from: 'scripts/runtime-component-manifest.gradle'
        """.trimIndent() + "\n")
        write("library/build.gradle", "plugins { id 'java-library' }\n")
        write("src/main/java/App.java", "public final class App { public static void main(String[] args) {} }\n")
        write("library/src/main/java/Library.java", "public final class Library {}\n")
        for (name in listOf("runtime-component-manifest.gradle", "qualify-runtime-components.py")) {
            write("scripts/$name", Files.readString(repository.resolve("scripts/$name")))
        }
        when (condition) {
            "actual current resolved runtime", "repeated capture in a fresh destination" -> Unit
            "untyped file runtime dependency" -> {
                emptyJar(root.resolve("untyped.jar"))
                Files.writeString(root.resolve("build.gradle"), "\ndependencies { runtimeOnly files('untyped.jar') }\n", java.nio.file.StandardOpenOption.APPEND)
            }
            "colliding artifact destination names" -> {
                for (group in listOf("fixture.first", "fixture.second")) {
                    val directory = "repository/${group.replace('.', '/')}/collision/1"
                    emptyJar(root.resolve("$directory/collision-1.jar"))
                    write("$directory/collision-1.pom", "<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>collision</artifactId><version>1</version></project>\n")
                }
                Files.writeString(root.resolve("build.gradle"), "\ndependencies { runtimeOnly 'fixture.first:collision:1'; runtimeOnly 'fixture.second:collision:1' }\n", java.nio.file.StandardOpenOption.APPEND)
            }
            "symbolic output destination" -> Files.createSymbolicLink(output, canary)
            "preexisting output destination" -> Files.writeString(output, "existing output canary\n")
            else -> error("Unknown owned manifest fixture")
        }
        sourceBefore = sourceImage()
    }

    fun execute() {
        exit = invoke(output)
        if (condition == "repeated capture in a fresh destination" && exit == 0) exit = invoke(second)
    }

    fun outcome(expected: String) {
        if (expected in setOf("verified component identities", "byte-identical component record")) {
            assertEquals(0, exit, logs.joinToString("\n") { Files.readString(it).takeLast(6000) })
            val record = Json.parseToJsonElement(Files.readString(output)).jsonObject
            assertEquals("refactorkit.resolved-runtime-components/v1", record.getValue("kind").jsonPrimitive.content)
            assertEquals("0.0.0-fixture", record.getValue("sourceVersion").jsonPrimitive.content)
            assertEquals(":runtimeClasspath", record.getValue("configuration").jsonPrimitive.content)
            val rows = record.getValue("components").jsonArray.map { it.jsonObject }
            assertEquals(3, rows.size)
            val names = rows.map { it.getValue("path").jsonPrimitive.content }
            assertEquals(names.sorted(), names)
            val byName = rows.associateBy { it.getValue("path").jsonPrimitive.content }
            assertEquals(setOf("lib/manifest-cli-0.0.0-fixture.jar", "lib/library-0.0.0-fixture.jar", "lib/javax.inject-1.jar"), byName.keys)
            for (entry in listOf(
                listOf("javax.inject-1.jar", "javax.inject", "javax.inject", "1", "module", ""),
                listOf("manifest-cli-0.0.0-fixture.jar", "org.refactorkit.fixture", "manifest-cli", "0.0.0-fixture", "project", ":"),
                listOf("library-0.0.0-fixture.jar", "org.refactorkit.fixture", "library", "0.0.0-fixture", "project", ":library"),
            )) {
                val (name, group, artifact, version, kind) = entry
                val path = entry[5]
                val coordinates = byName.getValue("lib/$name").getValue("coordinates").jsonObject
                assertEquals(group, coordinates.getValue("group").jsonPrimitive.content)
                assertEquals(artifact, coordinates.getValue("artifact").jsonPrimitive.content)
                assertEquals(version, coordinates.getValue("version").jsonPrimitive.content)
                assertEquals(kind, coordinates.getValue("kind").jsonPrimitive.content)
                if (path.isNotEmpty()) assertEquals(path, coordinates.getValue("projectPath").jsonPrimitive.content)
            }
            assertEquals("91c77044a50c481636c32d916fd89c9118a72195390452c81065080f957de7ff",
                byName.getValue("lib/javax.inject-1.jar").getValue("sha256").jsonPrimitive.content)
            if (expected == "byte-identical component record") assertEquals(Files.readString(output), Files.readString(second))
        } else {
            assertTrue(exit != 0, "Expected refusal $expected")
            assertTrue(logs.any { Files.readString(it).contains("\"code\": \"$expected\"") },
                logs.joinToString("\n") { Files.readString(it).takeLast(6000) })
            assertTrue(logs.any { Files.readString(it).contains(":writeRuntimeComponentManifest FAILED") })
        }
    }

    fun invariants() {
        assertEquals(sourceBefore, sourceImage())
        assertEquals("owned outside-output canary\n", Files.readString(canary))
        when (condition) {
            "preexisting output destination" -> assertEquals("existing output canary\n", Files.readString(output))
            "symbolic output destination" -> { assertTrue(Files.isSymbolicLink(output)); assertEquals(canary, Files.readSymbolicLink(output)) }
            "actual current resolved runtime", "repeated capture in a fresh destination" -> {
                val lib = root.resolve("build/install/manifest-cli/lib")
                val files = Files.list(lib).use { it.filter { path -> Files.isRegularFile(path) }.toList() }
                val record = Json.parseToJsonElement(Files.readString(output)).jsonObject
                val rows = record.getValue("components").jsonArray.map { it.jsonObject }
                assertEquals(files.map { "lib/${it.fileName}" }.toSet(), rows.map { it.getValue("path").jsonPrimitive.content }.toSet())
                for (row in rows) {
                    val file = lib.resolve(row.getValue("path").jsonPrimitive.content.removePrefix("lib/"))
                    assertEquals(hash(file), row.getValue("sha256").jsonPrimitive.content)
                    assertEquals(hash(file, "SHA-1"), row.getValue("sha1").jsonPrimitive.content)
                }
            }
            else -> assertFalse(Files.exists(output))
        }
        assertFalse(Files.exists(root.resolve(".refactorkit/transactions")))
        assertTrue(logs.all { Files.size(it) < 2 * 1024 * 1024 })
    }

    private fun invoke(destination: Path): Int {
        val log = owned.resolve("gradle-${logs.size}.log"); logs.add(log)
        val command = mutableListOf(requireNotNull(System.getenv("REFACTORKIT_GRADLE_EXECUTABLE")),
            "--offline", "--no-daemon", "--no-watch-fs", "--max-workers=1", "--console=plain", "-Dorg.gradle.jvmargs=-Xmx256m",
            "-Prefactorkit.runtime.components.output=$destination")
        if (condition in setOf("actual current resolved runtime", "repeated capture in a fresh destination")) command.add("installDist")
        command.add("writeRuntimeComponentManifest")
        val child = ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        process = child
        assertTrue(child.waitFor(180, TimeUnit.SECONDS), "Owned Gradle fixture timed out: $log")
        process = null
        return child.exitValue()
    }

    private fun sourceImage(): Map<String, String> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && root.relativize(it).none { part -> part.toString() in setOf("build", ".gradle", ".artifacts") } }
            .toList().associate { root.relativize(it).toString() to hash(it) }
    }

    private fun hash(path: Path, algorithm: String = "SHA-256"): String =
        MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    private fun write(relative: String, text: String) { val path = root.resolve(relative); Files.createDirectories(path.parent); Files.writeString(path, text) }
    private fun emptyJar(path: Path) { Files.createDirectories(path.parent); ZipOutputStream(Files.newOutputStream(path)).use { } }

    override fun close() {
        process?.let { child -> child.descendants().forEach { it.destroyForcibly() }; child.destroyForcibly(); child.waitFor(10, TimeUnit.SECONDS) }
        process = null
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.*
import org.refactorkit.c.CCompilationDatabaseProvider
import org.refactorkit.c.CCompilationSnapshotCapture
import org.refactorkit.c.CMakeManifestCapture
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceFile
import org.refactorkit.core.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.*

class CCompilationInputCaptureSteps {
    private val fixture = CCompilationInputFixture()
    private val cmake = CMakeInputFixture()
    @Given("the C compilation-input fixture prepares {string}")
    fun prepare(condition: String) = fixture.prepare(condition)
    @When("the C database provider and snapshot capture read their inputs")
    fun capture() = fixture.capture()
    @Then("C compilation input reports {string} with {string} snapshot authority")
    fun result(status: String, authority: String) = fixture.assertResult(status, authority)
    @Then("C compilation input discovery preserves bytes and creates no outputs")
    fun intact() = fixture.assertIntact()
    @Given("the CMake input fixture prepares {string}")
    fun cmakePrepare(condition: String) = cmake.prepare(condition)
    @When("CMake snapshot capture acquires the root manifest")
    fun cmakeCapture() = cmake.capture()
    @Then("CMake input retains {string} authority without changing sources")
    fun cmakeResult(authority: String) = cmake.assertResult(authority)
    @Then("CMake input capture preserves its declared files and creates no outputs")
    fun cmakeIntact() = cmake.assertIntact()
    @After
    fun close() { fixture.close(); cmake.close() }
}

private class CMakeInputFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var root: Path
    private lateinit var bytes: ByteArray
    private val images = linkedMapOf<Path, ByteArray>()
    private val links = linkedMapOf<Path, Path>()
    private lateinit var snapshot: ProjectSnapshot

    fun prepare(condition: String) {
        require(condition in setOf("exact 512 KiB", "ancestor root link", "manifest file link",
            "over 512 KiB", "invalid UTF8", "absent manifest"))
        val ownedBase = Files.createTempDirectory("rk-cmake-owned-input-").also { base = it }
        val physical = Files.createDirectories(ownedBase.resolve("physical/workspace"))
        root = physical
        write(physical.resolve("a.c"), "int owned;\n".toByteArray())
        val header = "cmake_minimum_required(VERSION 3.16)\nproject(owned C)\nadd_executable(app a.c)\n".toByteArray()
        bytes = when (condition) {
            "exact 512 KiB", "over 512 KiB" -> ByteArray(512 * 1024 + if (condition == "over 512 KiB") 1 else 0) { 32 }
                .also { header.copyInto(it) }
            "invalid UTF8" -> byteArrayOf(0xc3.toByte(), 0x28)
            else -> header
        }
        if (condition == "manifest file link") {
            val outside = ownedBase.resolve("outside/CMakeLists.txt"); write(outside, bytes)
            val alias = physical.resolve("CMakeLists.txt"); Files.createSymbolicLink(alias, outside); links[alias] = outside
        } else if (condition != "absent manifest") write(physical.resolve("CMakeLists.txt"), bytes)
        if (condition == "ancestor root link") {
            val alias = ownedBase.resolve("alias"); val target = ownedBase.resolve("physical")
            Files.createSymbolicLink(alias, target); links[alias] = target
            root = alias.resolve("workspace")
        }
    }

    private fun write(path: Path, content: ByteArray) {
        Files.createDirectories(path.parent); Files.write(path, content); images[path] = content
    }

    fun capture() {
        snapshot = CMakeManifestCapture.capture(ProjectSnapshot(Workspace(root), emptyList(),
            listOf(SourceFile(Path.of("a.c"), "int owned;\n", "c"))))
    }

    fun assertResult(authority: String) {
        val inputs = snapshot.auxiliaryFiles.filter { it.languageId == "cmake" }
        if (authority == "captured") {
            assertEquals(listOf(Path.of("CMakeLists.txt")), inputs.map { it.path })
            assertTrue(bytes.contentEquals(inputs.single().content.toByteArray()), "CMake input must stay byte exact")
        } else assertTrue(inputs.isEmpty(), "Unavailable manifest must not become captured authority")
        assertEquals(mapOf(Path.of("a.c") to "int owned;\n"), snapshot.files.associate { it.path to it.content })
    }

    fun assertIntact() {
        images.forEach { (path, content) -> assertTrue(content.contentEquals(Files.readAllBytes(path)), "Input changed: $path") }
        links.forEach { (path, target) -> assertEquals(target, Files.readSymbolicLink(path)) }
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    override fun close() {
        base?.let { directory -> Files.walk(directory).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

private class CCompilationInputFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var root: Path
    private lateinit var variant: String
    private lateinit var databaseBytes: ByteArray
    private var databasePath = Path.of("compile_commands.json")
    private val images = linkedMapOf<Path, ByteArray>()
    private val links = linkedMapOf<Path, Path>()
    private lateinit var model: BuildModel
    private lateinit var snapshot: ProjectSnapshot

    fun prepare(condition: String) {
        require(condition in setOf("exact eight MiB", "build database", "database file link",
            "database parent link", "invalid UTF8 note", "over eight MiB"))
        variant = condition
        val ownedBase = Files.createTempDirectory("rk-c-compilation-input-").also { base = it }
        root = Files.createDirectory(ownedBase.resolve("workspace"))
        write(root.resolve("a.c"), "int owned;\n".toByteArray())
        val json = buildJsonArray { add(buildJsonObject {
            put("directory", root.toString()); put("file", "a.c")
            put("arguments", JsonArray(listOf("clang", "-std=c17", "-c", "a.c").map(::JsonPrimitive)))
            put("note", "!NOTE!")
        }) }.toString()
        val encoded = json.toByteArray(Charsets.UTF_8)
        databaseBytes = when (condition) {
            "exact eight MiB", "over eight MiB" -> ByteArray(8 * 1024 * 1024 + if (condition == "over eight MiB") 1 else 0) { 32 }
                .also { encoded.copyInto(it) }
            "invalid UTF8 note" -> {
                val offset = json.indexOf("!NOTE!")
                json.substring(0, offset).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) +
                    json.substring(offset + "!NOTE!".length).toByteArray()
            }
            else -> encoded
        }
        when (condition) {
            "build database" -> { databasePath = Path.of("build/compile_commands.json"); write(root.resolve(databasePath), databaseBytes) }
            "database file link" -> {
                val outside = ownedBase.resolve("outside/compile_commands.json"); write(outside, databaseBytes)
                val alias = root.resolve(databasePath); Files.createSymbolicLink(alias, outside); links[alias] = outside
            }
            "database parent link" -> {
                val outside = ownedBase.resolve("outside"); write(outside.resolve("compile_commands.json"), databaseBytes)
                val alias = root.resolve("build"); Files.createSymbolicLink(alias, outside); links[alias] = outside
            }
            else -> write(root.resolve(databasePath), databaseBytes)
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent); Files.write(path, bytes); images[path] = bytes
    }

    fun capture() {
        model = CCompilationDatabaseProvider().discover(BuildModelRequest(root))
        snapshot = CCompilationSnapshotCapture.capture(ProjectSnapshot(Workspace(root), emptyList(),
            listOf(SourceFile(Path.of("a.c"), "int owned;\n", "c"))))
    }

    fun assertResult(status: String, authority: String) {
        assertEquals(status, model.status.name)
        listOf("buildCodeExecution", "credentialsAccess", "networkAccess").forEach {
            assertEquals("denied", model.attributes[it])
        }
        val inputs = snapshot.auxiliaryFiles.filter { it.languageId == "c-compilation-database" }
        if (authority == "captured") {
            assertEquals(1, model.modules.size); assertEquals("1", model.attributes["unitCount"])
            assertEquals(listOf(databasePath), inputs.map { it.path })
            assertTrue(databaseBytes.contentEquals(inputs.single().content.toByteArray(Charsets.UTF_8)), "Database capture must preserve every byte")
        } else {
            assertTrue(model.modules.isEmpty()); assertTrue(model.diagnostics.isNotEmpty())
            assertTrue(inputs.isEmpty(), "Unavailable database input must not become captured authority")
            if (variant == "over eight MiB") assertTrue(model.diagnostics.any { it.code == "c.compilationDatabaseLimit" })
        }
        assertEquals(mapOf(Path.of("a.c") to "int owned;\n"), snapshot.files.associate { it.path to it.content })
    }

    fun assertIntact() {
        images.forEach { (path, bytes) -> assertTrue(bytes.contentEquals(Files.readAllBytes(path)), "Input bytes changed: $path") }
        links.forEach { (path, target) -> assertEquals(target, Files.readSymbolicLink(path)) }
        assertFalse(Files.exists(root.resolve(".refactorkit")))
        Files.walk(root).use { files -> assertFalse(files.anyMatch { it.fileName.toString().endsWith(".o") }) }
    }

    override fun close() {
        base?.let { directory -> Files.walk(directory).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

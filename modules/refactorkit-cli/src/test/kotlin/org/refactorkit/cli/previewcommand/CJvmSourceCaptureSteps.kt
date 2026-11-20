package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.core.OwnedSourceInventory
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.SourceCapturePolicy
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.WorkspaceSnapshotComposer
import org.refactorkit.java.JavaProjectScanner
import org.refactorkit.treesitter.GenericProjectScanner
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.test.*

class CJvmSourceCaptureSteps {
    private val fixture = CJvmSourceCaptureFixture()
    private val selected = SelectedSourceRootFixture()
    @Given("the JVM source acquisition fixture prepares {string}")
    fun prepare(condition: String) = fixture.prepare(condition)
    @When("owned inventory completes and JVM source state changes before base scanning")
    fun capture() = fixture.capture()
    @Then("JVM source acquisition reports {string} without a partial snapshot")
    fun result(expected: String) = fixture.assertResult(expected)
    @Then("JVM source acquisition preserves declared final files and its external canary")
    fun intact() = fixture.assertIntact()
    @Given("the selected source-root fixture prepares {string}")
    fun selectedPrepare(condition: String) = selected.prepare(condition)
    @When("the core reader captures the explicitly selected source roots")
    fun selectedCapture() = selected.capture()
    @Then("selected source-root capture reports {string} without partial sources")
    fun selectedResult(expected: String) = selected.assertResult(expected)
    @Then("selected source-root capture preserves its declared inputs")
    fun selectedIntact() = selected.assertIntact()
    @After
    fun close() { fixture.close(); selected.close() }
}

private class SelectedSourceRootFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var root: Path
    private var roots = listOf(Path.of("a"))
    private var policy = SourceCapturePolicy()
    private val images = linkedMapOf<Path, ByteArray>()
    private val links = linkedMapOf<Path, Path>()
    private var result: OwnedSourceInventory? = null
    private var failure: Throwable? = null

    fun prepare(condition: String) {
        val ownedBase = Files.createTempDirectory("rk-selected-source-roots-").also { base = it }
        root = Files.createDirectory(ownedBase.resolve("workspace"))
        write(root.resolve("a/nested/Main.java"), "class Main {}\n".toByteArray())
        write(ownedBase.resolve("outside/nested/Outside.java"), "class Outside {}\n".toByteArray())
        when (condition) {
            "overlapping roots" -> {
                roots = listOf(Path.of("a"), Path.of("a"), Path.of("a/../a"), Path.of("a/nested"))
                policy = SourceCapturePolicy(maxSources = 1, maxTotalBytes = images.getValue(root.resolve("a/nested/Main.java")).size)
            }
            "outside workspace" -> roots = listOf(Path.of("../outside"))
            "symbolic root ancestor" -> {
                val alias = root.resolve("alias"); val outside = ownedBase.resolve("outside")
                Files.createSymbolicLink(alias, outside); links[alias] = outside
                roots = listOf(Path.of("alias/nested"))
            }
            "missing modeled root" -> roots = listOf(Path.of("absent"), Path.of("a"))
            "aggregate source bytes" -> {
                write(root.resolve("b/Other.java"), "class Other {}\n".toByteArray())
                roots = listOf(Path.of("a"), Path.of("b"))
                policy = SourceCapturePolicy(maxTotalBytes = 20)
            }
            "invalid source UTF8" -> write(root.resolve("a/nested/Main.java"), byteArrayOf(0xc3.toByte(), 0x28))
            else -> error("Unknown selected-root fixture $condition")
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent); Files.write(path, bytes); images[path] = bytes
    }

    fun capture() {
        try { result = OwnedSourceInventoryReader(mapOf("java" to "java"), policy).captureSourceRoots(root, roots) }
        catch (caught: Throwable) { failure = caught }
    }

    fun assertResult(expected: String) {
        if (expected == "captured") {
            assertNull(failure, failure?.toString())
            val captured = assertNotNull(result)
            assertEquals(mapOf(Path.of("a/nested/Main.java") to "class Main {}\n"), captured.sources.associate { it.path to it.content })
            assertTrue(captured.sources.all { it.languageId == "java" })
        } else {
            assertNull(result, "A selected-root failure must not expose the already-read roots")
            assertEquals(expected, assertIs<OwnedSourceCaptureException>(failure).code.name)
        }
    }

    fun assertIntact() {
        images.forEach { (path, bytes) -> assertContentEquals(bytes, Files.readAllBytes(path)) }
        links.forEach { (path, target) -> assertEquals(target, Files.readSymbolicLink(path)) }
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    override fun close() {
        base?.let { directory -> Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

private class CJvmSourceCaptureFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var root: Path
    private lateinit var canary: Path
    private lateinit var source: Path
    private lateinit var condition: String
    private val images = linkedMapOf<Path, ByteArray>()
    private val links = linkedMapOf<Path, Path>()
    private var inventoryCalls = 0
    private var baseCalls = 0
    private var inventory: ProjectSnapshot? = null
    private var result: ProjectSnapshot? = null
    private var failure: Throwable? = null

    fun prepare(value: String) {
        require(value in setOf("stable Java sources", "modeled generated sources", "replaced Java file",
            "replaced Kotlin file", "replaced source directory", "grown Java source"))
        condition = value
        val ownedBase = Files.createTempDirectory("rk-jvm-owned-source-").also { base = it }
        root = Files.createDirectory(ownedBase.resolve("workspace"))
        canary = ownedBase.resolve("outside.java")
        write(canary, "class OutsideCanary {}\n")
        Files.setPosixFilePermissions(canary, emptySet())
        check(!Files.isReadable(canary)) { "Native qualification requires a protected first-party canary" }
        write(root.resolve("a.c"), "int owned;\n")
        source = root.resolve(if (value == "replaced Kotlin file") "src/main/kotlin/Demo.kt" else "src/main/java/Demo.java")
        write(source, if (value == "replaced Kotlin file") "class Demo\n" else "class Demo {}\n")
        if (value == "modeled generated sources")
            write(root.resolve("build/generated/sources/annotationProcessor/java/main/generated/Generated.java"), "class Generated {}\n")
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        val bytes = content.toByteArray(Charsets.UTF_8)
        Files.write(path, bytes); images[path] = bytes
    }

    private fun mutate() {
        when (condition) {
            "replaced Java file", "replaced Kotlin file" -> {
                Files.delete(source); images.remove(source)
                Files.createSymbolicLink(source, canary); links[source] = canary
            }
            "replaced source directory" -> {
                val directory = source.parent
                val moved = requireNotNull(base).resolve("outside-source-root")
                Files.move(directory, moved)
                val movedImages = images.filterKeys { it.startsWith(directory) }
                movedImages.forEach { (path, bytes) ->
                    images.remove(path); images[moved.resolve(directory.relativize(path))] = bytes
                }
                Files.createSymbolicLink(directory, moved); links[directory] = moved
            }
            "grown Java source" -> write(source, " ".repeat(2 * 1024 * 1024 + 1) + "class Demo {}\n")
        }
    }

    fun capture() {
        try {
            result = WorkspaceSnapshotComposer().composeInventoryFirst(
                root,
                authoritativeBaseScanner = { workspace -> baseCalls++; JavaProjectScanner().scan(workspace) },
                sourceInventoryScanner = { workspace ->
                    inventoryCalls++
                    GenericProjectScanner(mapOf("c" to "c", "h" to "c")).scan(workspace).also {
                        inventory = it; mutate()
                    }
                },
                conditionalEvidenceAttacher = { it },
            )
        } catch (caught: Throwable) { failure = caught }
    }

    fun assertResult(expected: String) {
        assertEquals(1, inventoryCalls); assertEquals(1, baseCalls)
        assertEquals(listOf(Path.of("a.c")), assertNotNull(inventory).files.map { it.path })
        if (expected == "captured") {
            assertNull(failure, failure?.toString())
            val captured = assertNotNull(result)
            val expectedSources = images.filterKeys { it.startsWith(root) && it.fileName.toString().substringAfterLast('.') in setOf("c", "java", "kt") }
                .mapKeys { root.relativize(it.key) }.mapValues { it.value.toString(Charsets.UTF_8) }
            assertEquals(expectedSources, captured.files.associate { it.path to it.content })
            assertFalse(captured.files.any { it.content.contains("OutsideCanary") })
            if (condition == "modeled generated sources") assertTrue(captured.modules.flatMap { it.generatedSourceRoots }
                .any { it.toString().replace('\\', '/').startsWith("build/generated/") })
        } else {
            assertNull(result, "An unavailable JVM acquisition must not return partial C or JVM sources")
            assertEquals(expected, assertIs<OwnedSourceCaptureException>(failure).code.name)
        }
    }

    fun assertIntact() {
        images.forEach { (path, bytes) ->
            if (path == canary) {
                assertEquals(emptySet(), Files.getPosixFilePermissions(path))
                Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
                try { assertContentEquals(bytes, Files.readAllBytes(path)) }
                finally { Files.setPosixFilePermissions(path, emptySet()) }
            } else assertContentEquals(bytes, Files.readAllBytes(path))
        }
        links.forEach { (path, target) -> assertEquals(target, Files.readSymbolicLink(path)) }
        assertFalse(Files.exists(root.resolve(".refactorkit")))
    }

    override fun close() {
        base?.let { directory -> Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

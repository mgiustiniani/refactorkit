package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.ClangResourceCaptureResult
import org.refactorkit.c.ClangResourceCurrency
import org.refactorkit.c.ClangResourceDirectoryCapture
import org.refactorkit.c.ClangResourceFile
import org.refactorkit.c.ClangResourceLimits
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CClangResourceDirectoryCaptureSteps {
    private var ownRoot: Path? = null
    private lateinit var reference: Path
    private lateinit var selected: Path
    private lateinit var referenceImage: ResourceImage
    private lateinit var expectedImage: ResourceImage
    private lateinit var service: ClangResourceDirectoryCapture
    private var condition = ""
    private var result: ClangResourceCaptureResult? = null
    private var current: ClangResourceCurrency? = null

    @Given("a configured Clang resource directory has {string}")
    fun prepare(case: String) {
        condition = case
        val root = Files.createTempDirectory("refactorkit-clang-resources-"); ownRoot = root
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        reference = sdk.resolve("usr/lib/clang/22"); referenceImage = image(reference)
        assertEquals(385, referenceImage.files.size, "Qualification SDK resource set changed")
        selected = if (case in listOf("intact configured resources", "entry budget too small")) reference
            else root.resolve("resources").also { destination -> Files.walk(reference).use { paths ->
                paths.forEach { path -> val target = destination.resolve(reference.relativize(path))
                    if (Files.isDirectory(path, NOFOLLOW_LINKS)) Files.createDirectories(target) else Files.copy(path, target)
                }
            } }
        if (case == "symbolic link to script") {
            val script = root.resolve("script"); Files.writeString(script,
                "#!/bin/sh\nprintf executed > \"${root.resolve("executed")}\"\n")
            Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            Files.createSymbolicLink(selected.resolve("include/indirect.h"), script)
        }
        expectedImage = image(selected)
        service = ClangResourceDirectoryCapture(if (case == "entry budget too small")
            ClangResourceLimits(maxEntries = 8) else ClangResourceLimits())
    }

    @When("the library captures and rechecks the complete resource directory")
    fun capture() {
        result = service.capture(selected)
        when (condition) {
            "captured file bytes changed" -> Files.writeString(selected.resolve("include/stddef.h"),
                "\n/* fixture changed resource */\n", StandardOpenOption.APPEND)
            "resource file added" -> Files.createFile(selected.resolve("include/added.h"))
            "resource file removed" -> Files.delete(selected.resolve("include/stddef.h"))
        }
        (result as? ClangResourceCaptureResult.Captured)?.let { current = service.checkCurrent(it.snapshot) }
    }

    @Then("Clang resource acquisition reports {string}")
    fun report(expected: String) {
        if (expected in listOf("indirect inputs refused", "input limit refused")) {
            val refused = assertIs<ClangResourceCaptureResult.Unavailable>(result, result.toString())
            assertEquals(if (expected == "input limit refused") "clang.resources.inputLimit" else "clang.resources.pathInvalid",
                refused.diagnostic.code); return
        }
        val snapshot = assertIs<ClangResourceCaptureResult.Captured>(result, result.toString()).snapshot
        assertEquals(selected.toAbsolutePath().normalize(), snapshot.root)
        assertEquals(expectedImage.files, snapshot.files.associateBy(ClangResourceFile::path))
        assertEquals(expectedImage.directories, snapshot.directories)
        assertEquals(expectedImage.totalBytes, snapshot.totalBytes); assertTrue(Regex("[a-f0-9]{64}").matches(snapshot.identity))
        if (expected == "captured and current") assertIs<ClangResourceCurrency.Current>(current)
        else {
            assertEquals("changed resources refused", expected)
            assertEquals("clang.resources.changed", assertIs<ClangResourceCurrency.NotCurrent>(current).diagnostic.code)
            assertTrue(image(selected) != expectedImage)
        }
    }

    @Then("resource inspection preserves the configured SDK and executes no content")
    fun intact() {
        assertFalse(Files.exists(requireNotNull(ownRoot).resolve("executed")))
        assertEquals(referenceImage, image(reference))
        if (condition in listOf("intact configured resources", "entry budget too small", "symbolic link to script")) {
            assertEquals(expectedImage, image(selected))
        }
    }

    private data class ResourceImage(val files: Map<Path, ClangResourceFile>, val directories: List<Path>, val totalBytes: Long)

    private fun image(root: Path): ResourceImage {
        val nodes = Files.walk(root).use { it.toList() }
        val files = nodes.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.sortedBy(Path::toString)
        val hashes = if (files.isEmpty()) emptyMap() else shaOracle(files).split('\u0000').filter(String::isNotEmpty)
            .associate { Path.of(it.substring(66)) to it.substring(0, 64) }
        assertEquals(files.toSet(), hashes.keys)
        val records = files.associate { file -> val relative = root.relativize(file)
            relative to ClangResourceFile(relative, Files.size(file), requireNotNull(hashes[file])) }
        val directories = nodes.filter { it != root && Files.isDirectory(it, NOFOLLOW_LINKS) }
            .map(root::relativize).sortedBy(Path::toString)
        return ResourceImage(records, directories, records.values.sumOf { it.size })
    }

    private fun shaOracle(paths: List<Path>): String {
        val process = ProcessBuilder(listOf("/usr/bin/sha256sum", "--zero", "--") + paths.map(Path::toString))
            .redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val read = executor.submit<ByteArray> { process.inputStream.use { it.readNBytes(1_048_577) } }
            val bytes = read.get(15, TimeUnit.SECONDS)
            assertTrue(bytes.size <= 1_048_576, "Resource oracle output exceeded its bound")
            assertTrue(process.waitFor(1, TimeUnit.SECONDS), "Resource oracle did not terminate")
            assertEquals(0, process.exitValue(), bytes.toString(Charsets.UTF_8)); return bytes.toString(Charsets.UTF_8)
        } finally { if (process.isAlive) process.destroyForcibly(); executor.shutdownNow() }
    }

    @After
    fun cleanup() { ownRoot?.let { root -> Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    } } }
}

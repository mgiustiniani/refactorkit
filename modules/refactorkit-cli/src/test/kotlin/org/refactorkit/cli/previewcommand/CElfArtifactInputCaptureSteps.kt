package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.ClangArtifactCaptureResult
import org.refactorkit.c.ClangArtifactCurrency
import org.refactorkit.c.ClangArtifactInputCapture
import org.refactorkit.c.ClangArtifactRole
import org.refactorkit.c.ClangNativePathAlias
import org.refactorkit.c.ClangNativePathCapture
import org.refactorkit.c.ClangNativePathCaptureResult
import org.refactorkit.c.ClangNativePathCurrency
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CElfArtifactInputCaptureSteps {
    private var root: Path? = null
    private lateinit var selected: Path
    private lateinit var reference: Path
    private lateinit var role: ClangArtifactRole
    private lateinit var originalReferenceHash: String
    private lateinit var expectedHash: String
    private var originalSize = 0L
    private var oracle = ""
    private var condition = ""
    private val service = ClangArtifactInputCapture()
    private var captured: ClangArtifactCaptureResult? = null
    private var current: ClangArtifactCurrency? = null
    private var nativePathMode = false
    private var pathCapture: ClangNativePathCaptureResult? = null
    private var pathCurrency: ClangNativePathCurrency? = null
    private var expectedAliases: List<ClangNativePathAlias> = emptyList()
    private var preservedFiles: Map<Path, String> = emptyMap()
    private var preservedLinks: Map<Path, Path> = emptyMap()

    @Given("a selected C runtime path has {string} aliases")
    fun runtimePath(case: String) {
        nativePathMode = true; condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-native-path-"); root = workspace
        Files.writeString(workspace.resolve("main.c"), "int main(void) { return 0; }\n")
        val toolchain = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val copiedReference = toolchain.resolve("usr/bin/clang-format")
        when (case) {
            "direct target" -> { selected = toolchain.resolve("usr/bin/clangd"); reference = selected; role = ClangArtifactRole.CLANGD }
            "directory alias" -> {
                selected = Path.of("/lib64/ld-linux-x86-64.so.2"); reference = Path.of(command("/usr/bin/readlink", "-f", selected.toString()).trim())
                role = ClangArtifactRole.INTERPRETER
                expectedAliases = listOf(ClangNativePathAlias(Path.of("/lib64"), Path.of(command("/usr/bin/readlink", "/lib64").trim())))
            }
            "leaf alias" -> {
                selected = Path.of("/usr/lib/libstdc++.so.6"); reference = Path.of(command("/usr/bin/readlink", "-f", selected.toString()).trim())
                role = ClangArtifactRole.SHARED_LIBRARY
                expectedAliases = listOf(ClangNativePathAlias(selected, Path.of(command("/usr/bin/readlink", selected.toString()).trim())))
            }
            "retargeted alias" -> {
                reference = copiedReference; role = ClangArtifactRole.FORMATTER
                Files.copy(reference, workspace.resolve("first.bin")); Files.copy(reference, workspace.resolve("second.bin"))
                selected = workspace.resolve("selected.bin"); Files.createSymbolicLink(selected, Path.of("first.bin"))
                expectedAliases = listOf(ClangNativePathAlias(selected, Path.of("first.bin")))
            }
            "cyclic alias" -> {
                reference = copiedReference; role = ClangArtifactRole.FORMATTER; selected = workspace.resolve("first.bin")
                Files.createSymbolicLink(selected, Path.of("second.bin")); Files.createSymbolicLink(workspace.resolve("second.bin"), Path.of("first.bin"))
            }
            "script alias" -> {
                reference = copiedReference; role = ClangArtifactRole.FORMATTER
                val script = workspace.resolve("script"); Files.writeString(script,
                    "#!/bin/sh\nprintf executed > \"${workspace.resolve("executed")}\"\n")
                check(script.toFile().setExecutable(true)); selected = workspace.resolve("selected.bin")
                Files.createSymbolicLink(selected, Path.of("script"))
            }
            else -> error("Unexpected native path fixture: $case")
        }
        originalReferenceHash = hashOracle(reference)
        if (case !in setOf("cyclic alias", "script alias")) {
            expectedHash = hashOracle(selected); originalSize = Files.size(selected)
            oracle = command("/usr/bin/readelf", "-W", "-l", "-d", selected.toString())
        }
        preservedFiles = Files.walk(workspace).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
            .toList().associate { it to hashOracle(it) } }
        preservedLinks = Files.walk(workspace).use { paths -> paths.filter(Files::isSymbolicLink)
            .toList().associate { it to Files.readSymbolicLink(it) } }
    }

    @When("the library captures and rechecks that explicit runtime path")
    fun captureRuntimePath() {
        val nativePaths = ClangNativePathCapture(); pathCapture = nativePaths.capture(selected, role)
        if (condition == "retargeted alias") {
            assertIs<ClangNativePathCaptureResult.Captured>(pathCapture)
            assertEquals(hashOracle(requireNotNull(root).resolve("first.bin")), hashOracle(requireNotNull(root).resolve("second.bin")))
            Files.delete(selected); Files.createSymbolicLink(selected, Path.of("second.bin"))
            preservedLinks = preservedLinks + (selected to Path.of("second.bin"))
        }
        (pathCapture as? ClangNativePathCaptureResult.Captured)?.let { pathCurrency = nativePaths.checkCurrent(it) }
    }

    @Then("native runtime path disposition is {string}")
    fun runtimePathDisposition(expected: String) {
        if (expected == "unavailable path refused") {
            val unavailable = assertIs<ClangNativePathCaptureResult.Unavailable>(pathCapture)
            assertEquals(if (condition == "cyclic alias") "clang.nativePath.cycle" else "clang.nativePath.artifactUnavailable", unavailable.diagnostic.code)
            return
        }
        val result = assertIs<ClangNativePathCaptureResult.Captured>(pathCapture, pathCapture.toString())
        assertEquals(selected.toAbsolutePath().normalize(), result.requestedPath); assertEquals(expectedAliases, result.aliases)
        val resolved = Path.of(command("/usr/bin/readlink", "-f", selected.toString()).trim())
        assertEquals(if (condition == "retargeted alias") requireNotNull(root).resolve("first.bin") else resolved, result.artifact.evidence.path)
        assertEquals(role, result.artifact.role); assertEquals(role.evidenceRole, result.artifact.evidence.role)
        assertEquals(originalSize, result.artifact.evidence.size); assertEquals(expectedHash, result.artifact.evidence.sha256)
        assertEquals(result.artifact.evidence.sha256, result.artifact.facts.sha256)
        assertEquals(Regex("\\(NEEDED\\)[^\\n]*\\[([^]]+)]").findAll(oracle).map { it.groupValues[1] }.toList(), result.artifact.facts.needed)
        assertEquals(Regex("Requesting program interpreter: ([^]]+)]").find(oracle)?.groupValues?.get(1), result.artifact.facts.interpreter)
        fun declaration(tag: String) = Regex("\\($tag\\)[^\\n]*\\[([^]]*)]").find(oracle)?.groupValues?.get(1)
        assertEquals(declaration("SONAME"), result.artifact.facts.soname)
        assertEquals(declaration("RPATH"), result.artifact.facts.rpath); assertEquals(declaration("RUNPATH"), result.artifact.facts.runpath)
        if (expected == "changed alias refused") {
            assertEquals("clang.nativePath.changed", assertIs<ClangNativePathCurrency.NotCurrent>(pathCurrency).diagnostic.code)
        } else { assertEquals("captured and current", expected); assertIs<ClangNativePathCurrency.Current>(pathCurrency) }
    }

    @Given("a configured C ELF artifact capture has {string}")
    fun prepare(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-elf-artifact-"); root = workspace
        val toolchain = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val target = when (case) {
            "configured clang driver" -> "usr/bin/clang-22" to ClangArtifactRole.CLANG
            "configured clangd server" -> "usr/bin/clangd" to ClangArtifactRole.CLANGD
            "configured LLVM library" -> "usr/lib/libclang-cpp.so.22.1" to ClangArtifactRole.SHARED_LIBRARY
            else -> "usr/bin/clang-format" to ClangArtifactRole.FORMATTER
        }
        reference = toolchain.resolve(target.first); role = target.second
        originalReferenceHash = hashOracle(reference)
        selected = when (case) {
            "captured artifact changed" -> workspace.resolve("formatter.bin").also { Files.copy(reference, it) }
            "symbolic link to script" -> {
                val script = workspace.resolve("script"); Files.writeString(script,
                    "#!/bin/sh\nprintf executed > \"${workspace.resolve("executed")}\"\n")
                Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
                workspace.resolve("indirect.bin").also { Files.createSymbolicLink(it, script) }
            }
            else -> reference
        }
        expectedHash = hashOracle(selected); originalSize = Files.size(selected)
        if (case != "symbolic link to script") oracle = command("/usr/bin/readelf", "-W", "-l", "-d", selected.toString())
    }

    @When("the library captures and rechecks that native artifact")
    fun capture() {
        captured = service.capture(selected, role)
        if (condition == "captured artifact changed") {
            Files.write(selected, byteArrayOf(1), StandardOpenOption.APPEND); expectedHash = hashOracle(selected)
        }
        (captured as? ClangArtifactCaptureResult.Captured)?.let { current = service.checkCurrent(it) }
    }

    @Then("native artifact capture reports {string}")
    fun report(expected: String) {
        if (expected == "indirect artifact refused") {
            val refused = assertIs<ClangArtifactCaptureResult.Unavailable>(captured, captured.toString())
            assertEquals("clang.artifact.pathInvalid", refused.diagnostic.code); return
        }
        val result = assertIs<ClangArtifactCaptureResult.Captured>(captured, captured.toString())
        assertEquals(role, result.role); assertEquals(role.evidenceRole, result.evidence.role)
        assertEquals(selected.toAbsolutePath().normalize(), result.evidence.path)
        assertEquals(originalSize, result.evidence.size)
        assertEquals(result.evidence.sha256, result.facts.sha256)
        assertEquals(Regex("\\(NEEDED\\)[^\\n]*\\[([^]]+)]").findAll(oracle).map { it.groupValues[1] }.toList(), result.facts.needed)
        fun declaration(tag: String) = Regex("\\($tag\\)[^\\n]*\\[([^]]*)]").find(oracle)?.groupValues?.get(1)
        assertEquals(Regex("Requesting program interpreter: ([^]]+)]").find(oracle)?.groupValues?.get(1), result.facts.interpreter)
        assertEquals(declaration("SONAME"), result.facts.soname)
        assertEquals(declaration("RPATH"), result.facts.rpath); assertEquals(declaration("RUNPATH"), result.facts.runpath)
        assertTrue(result.facts.hasDynamicSegment)
        if (expected == "changed bytes refused") {
            val refused = assertIs<ClangArtifactCurrency.NotCurrent>(current)
            assertEquals("clang.artifact.changed", refused.diagnostic.code)
            assertTrue(result.evidence.sha256 != expectedHash)
        } else {
            assertEquals("captured and current", expected); assertIs<ClangArtifactCurrency.Current>(current)
            assertEquals(expectedHash, result.evidence.sha256)
        }
    }

    @Then("native artifact inspection preserves source and executes no artifact")
    fun intact() {
        assertFalse(Files.exists(requireNotNull(root).resolve("executed")))
        if (nativePathMode) {
            for ((path, hash) in preservedFiles) assertEquals(hash, hashOracle(path))
            for ((path, target) in preservedLinks) assertEquals(target, Files.readSymbolicLink(path))
            assertEquals(preservedFiles.keys + preservedLinks.keys, Files.walk(requireNotNull(root)).use { paths ->
                paths.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }.toList().toSet() })
            assertEquals(originalReferenceHash, hashOracle(reference)); return
        }
        assertEquals(expectedHash, hashOracle(selected)); assertEquals(originalReferenceHash, hashOracle(reference))
    }

    private fun hashOracle(file: Path): String = command("/usr/bin/sha256sum", file.toString()).substringBefore(' ')
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Read-only artifact oracle did not terminate")
            val output = process.inputStream.use { it.readNBytes(65_537).toString(Charsets.UTF_8) }
            assertTrue(output.toByteArray().size <= 65_536, "Artifact oracle output exceeded its bound")
            assertEquals(0, process.exitValue(), output); return output
        } finally { if (process.isAlive) process.destroyForcibly() }
    }

    @After
    fun cleanup() { root?.let { workspace -> Files.walk(workspace).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    } } }
}

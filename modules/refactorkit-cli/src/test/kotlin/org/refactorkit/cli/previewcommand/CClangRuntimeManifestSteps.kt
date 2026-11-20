package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CElfObjectType
import org.refactorkit.c.ClangArtifactRole
import org.refactorkit.c.ClangNativePathAlias
import org.refactorkit.c.ClangRuntimeDependencyBinding
import org.refactorkit.c.ClangRuntimeManifestCapture
import org.refactorkit.c.ClangRuntimeManifestCaptureResult
import org.refactorkit.c.ClangRuntimeManifestCurrency
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Scenario fixtures and independent read-only oracles; production graph rules stay in the C domain. */
class CClangRuntimeManifestSteps {
    private var root: Path? = null
    private lateinit var executable: Path
    private lateinit var interpreter: Path
    private lateinit var role: ClangArtifactRole
    private var libraries = emptyList<Path>()
    private var bindings = emptyList<ClangRuntimeDependencyBinding>()
    private var condition = ""
    private val service = ClangRuntimeManifestCapture()
    private var captured: ClangRuntimeManifestCaptureResult? = null
    private var currency: ClangRuntimeManifestCurrency? = null
    private var oracles = emptyMap<Path, ObjectOracle>()
    private var originalHashes = emptyMap<Path, String>()
    private var ownedHashes = emptyMap<Path, String>()
    private var ownedLinks = emptyMap<Path, Path>()
    private var mutableLibrary: Path? = null
    private var libraryAlias: Path? = null

    @Given("a configured C runtime graph with {string}")
    fun configuredRuntime(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-runtime-graph-"); root = workspace
        Files.writeString(workspace.resolve("main.c"), "int main(void) { return 0; }\n")
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val tool = when (case) { "compiler" -> "clang-22"; "formatter" -> "clang-format"; else -> "clangd" }
        executable = sdk.resolve("usr/bin/$tool")
        role = when (tool) { "clang-22" -> ClangArtifactRole.CLANG; "clang-format" -> ClangArtifactRole.FORMATTER; else -> ClangArtifactRole.CLANGD }
        val expectedToolHash = when (tool) {
            "clang-22" -> "31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113"
            "clangd" -> "aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324"
            else -> "b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf"
        }
        assertEquals(expectedToolHash, hash(executable), "Official LLVM 22.1.8 Linux X64 tool from attested archive df0e1ecf16caf3489a272a5eea4eec9b0d82878f6477fa309504f918a0006384")
        val targetOracle = oracle(executable)
        interpreter = Path.of(assertNotNull(targetOracle.interpreter))
        val systemLoader = Path.of("/lib64/ld-linux-x86-64.so.2")
        val realInterpreter = realPath(interpreter)
        assertEquals(realPath(systemLoader), realInterpreter, "Only the host's standard x86-64 loader is admitted for read-only --list")
        val originalPaths = runtimeObjects(executable, systemLoader)
        originalHashes = (originalPaths + listOf(executable, interpreter)).distinct().associateWith(::hash)
        libraries = originalPaths.filter { realPath(it) != realInterpreter }
        if (case in setOf("changed-library", "retargeted-library")) {
            val initialOracles = (libraries + listOf(executable, interpreter)).distinct().map(::oracle).associateBy { it.resolved }
            val initialBindings = graphBindings(initialOracles)
            val transitiveTarget = initialBindings.firstOrNull {
                it.requester != targetOracle.resolved && it.target != realInterpreter
            }?.target ?: error("Selected toolchain has no transitive runtime library to exercise")
            val reference = libraries.first { realPath(it) == transitiveTarget }
            val first = workspace.resolve("first.so"); Files.copy(reference, first); mutableLibrary = first
            val owned = if (case == "retargeted-library") {
                Files.copy(reference, workspace.resolve("second.so"))
                workspace.resolve(reference.fileName.toString()).also { Files.createSymbolicLink(it, Path.of("first.so")); libraryAlias = it }
            } else first
            val referencePath = realPath(reference)
            libraries = libraries.map { if (realPath(it) == referencePath) owned else it }
        }
        oracles = (libraries + listOf(executable, interpreter)).distinct().map(::oracle).associateBy { it.resolved }
        bindings = graphBindings(oracles)
        if (case == "missing-transitive-library") {
            val target = bindings.firstOrNull { it.requester != targetOracle.resolved && it.target != realInterpreter }?.target
                ?: error("Selected toolchain has no transitive runtime library to remove")
            libraries = libraries.filterNot { realPath(it) == target }
        }
        ownedHashes = Files.walk(workspace).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(::hash) }
        ownedLinks = Files.walk(workspace).use { files -> files.filter(Files::isSymbolicLink).toList().associateWith(Files::readSymbolicLink) }
    }

    @When("the complete selected C runtime is captured and checked")
    fun captureRuntime() {
        captured = service.capture(executable, role, interpreter, libraries, bindings)
        val complete = captured as? ClangRuntimeManifestCaptureResult.Captured ?: return
        assertEquals(ClangRuntimeManifestCurrency.Current, service.checkCurrent(complete))
        if (condition == "changed-library") {
            val file = assertNotNull(mutableLibrary)
            Files.write(file, byteArrayOf(1), StandardOpenOption.APPEND)
            ownedHashes = ownedHashes + (file to hash(file))
        }
        if (condition == "retargeted-library") {
            val alias = assertNotNull(libraryAlias); Files.delete(alias); Files.createSymbolicLink(alias, Path.of("second.so"))
            ownedLinks = ownedLinks + (alias to Path.of("second.so"))
        }
        currency = service.checkCurrent(complete)
    }

    @Then("the C runtime manifest outcome is {string}")
    fun manifestOutcome(outcome: String) {
        if (outcome == "incomplete-refusal") {
            assertEquals("clang.runtimeManifest.incomplete_graph", assertIs<ClangRuntimeManifestCaptureResult.Unavailable>(captured).diagnostic.code)
            assertEquals(null, currency); return
        }
        val manifest = assertIs<ClangRuntimeManifestCaptureResult.Captured>(captured).manifest
        assertEquals(oracles.keys, manifest.objects.map { it.artifact.evidence.path }.toSet())
        assertEquals(bindings.toSet(), manifest.bindings.toSet())
        assertEquals(oracles.size, manifest.objects.size)
        assertEquals(64, manifest.identity.length)
        for (obj in manifest.objects) {
            val expected = oracles.getValue(obj.artifact.evidence.path); val facts = obj.artifact.facts
            assertEquals(expected.requested, obj.requestedPath); assertEquals(expected.aliases, obj.aliases)
            assertEquals(expected.sha256, obj.artifact.evidence.sha256); assertEquals(expected.size, obj.artifact.evidence.size)
            assertEquals(expected.needed, facts.needed); assertEquals(expected.interpreter, facts.interpreter)
            assertEquals(expected.soname, facts.soname); assertEquals(expected.rpath, facts.rpath); assertEquals(expected.runpath, facts.runpath)
            assertEquals(CElfObjectType.DYNAMIC, facts.objectType)
        }
        if (outcome == "captured") assertEquals(ClangRuntimeManifestCurrency.Current, currency)
        else assertEquals("clang.runtimeManifest.changed", assertIs<ClangRuntimeManifestCurrency.NotCurrent>(currency).diagnostic.code)
        val evidence = buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "Native-data capture only; fixed host loader --list and readelf derive expected graph; no configured tool code is executed")
            put("condition", condition); put("outcome", outcome); put("identity", manifest.identity)
            put("objectCount", manifest.objects.size); put("edgeCount", manifest.bindings.size)
            put("objects", buildJsonArray {
                for (obj in manifest.objects) add(buildJsonObject {
                    put("requested", obj.requestedPath.toString()); put("resolved", obj.artifact.evidence.path.toString())
                    put("sha256", obj.artifact.evidence.sha256); put("size", obj.artifact.evidence.size)
                })
            })
        }
        val destination = Path.of(assertNotNull(System.getProperty("refactorkit.c.runtimeManifestEvidence")))
        Files.createDirectories(destination); Files.writeString(destination.resolve("$condition.json"), evidence.toString() + "\n")
    }

    @Then("runtime manifest capture preserves source inputs and executes no configured artifact")
    fun preservesInputs() {
        val workspace = assertNotNull(root)
        assertEquals(ownedHashes, Files.walk(workspace).use { files -> files.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(::hash) })
        assertEquals(ownedLinks, Files.walk(workspace).use { files -> files.filter(Files::isSymbolicLink).toList().associateWith(Files::readSymbolicLink) })
        originalHashes.forEach { (path, expected) -> assertEquals(expected, hash(path)) }
        assertFalse(Files.exists(workspace.resolve("executed")))
    }

    private fun oracle(path: Path): ObjectOracle {
        val resolved = realPath(path)
        val text = command("/usr/bin/readelf", "-W", "-l", "-d", path.toString())
        fun field(tag: String) = Regex("\\($tag\\).*?\\[([^]]*)]").find(text)?.groupValues?.get(1)
        val aliases = mutableListOf<ClangNativePathAlias>()
        if (path.toString().startsWith("/lib64/")) aliases += ClangNativePathAlias(Path.of("/lib64"), Path.of(command("/usr/bin/readlink", "/lib64").trim()))
        if (Files.isSymbolicLink(path)) aliases += ClangNativePathAlias(path, Path.of(command("/usr/bin/readlink", path.toString()).trim()))
        assertTrue(text.contains("Elf file type is DYN"))
        return ObjectOracle(path, resolved, hash(path), Files.size(path),
            Regex("\\(NEEDED\\).*?\\[([^]]*)]").findAll(text).map { it.groupValues[1] }.toList(),
            Regex("Requesting program interpreter: ([^]]+)]").find(text)?.groupValues?.get(1),
            field("SONAME"), field("RPATH"), field("RUNPATH"), aliases)
    }

    /** Independent host-runtime oracle: invokes only the fixed system loader's `--list` mode. */
    private fun runtimeObjects(executable: Path, systemLoader: Path): List<Path> {
        val output = command(systemLoader.toString(), "--list", executable.toString())
        assertFalse(output.contains("not found"), "A dynamic dependency is unresolved: $output")
        val paths = linkedSetOf<Path>()
        val dependency = Regex("^\\s*([^\\s]+)\\s+=>\\s+(/[^\\s]+)\\s+\\(0x[0-9a-fA-F]+\\)\\s*$")
        val direct = Regex("^\\s*(/[^\\s]+)\\s+\\(0x[0-9a-fA-F]+\\)\\s*$")
        for (line in output.lineSequence()) {
            val mapped = dependency.matchEntire(line)
            if (mapped != null) {
                if (mapped.groupValues[1] != "linux-vdso.so.1") paths.add(Path.of(mapped.groupValues[2]))
                continue
            }
            direct.matchEntire(line)?.let { paths.add(Path.of(it.groupValues[1])) }
        }
        val loaderPath = realPath(systemLoader)
        assertTrue(paths.any { realPath(it) == loaderPath }, "The loader did not include its own interpreter object")
        assertTrue(paths.size <= 256, "The native runtime graph exceeds the independently recorded 256-object capture limit")
        return paths.toList()
    }

    private fun graphBindings(objects: Map<Path, ObjectOracle>): List<ClangRuntimeDependencyBinding> {
        val targetByName = objects.values.flatMap { obj ->
            listOfNotNull(obj.soname, obj.requested.fileName.toString()).distinct().map { it to obj.resolved }
        }.toMap()
        return objects.values.flatMap { obj -> obj.needed.mapIndexed { index, name ->
            val target = targetByName[name] ?: error("Read-only loader output has no object for DT_NEEDED '$name'")
            ClangRuntimeDependencyBinding(obj.resolved, index, target)
        } }
    }

    private fun realPath(path: Path): Path = Path.of(command("/usr/bin/readlink", "-f", path.toString()).trim())

    private fun hash(path: Path) = command("/usr/bin/sha256sum", path.toString()).substringBefore(' ')
    private fun command(vararg args: String): String {
        val builder = ProcessBuilder(*args).redirectErrorStream(true).directory(assertNotNull(root).toFile())
        builder.environment().clear()
        val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(65537) } }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Read-only native oracle timed out")
            val bytes = output.get(1, TimeUnit.SECONDS); assertTrue(bytes.size <= 65536)
            assertEquals(0, process.exitValue(), "Read-only native oracle failed: ${args.toList()}: ${bytes.toString(Charsets.UTF_8)}")
            return bytes.toString(Charsets.UTF_8)
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS) }
    }

    @After
    fun cleanup() {
        val workspace = root ?: return
        Files.walk(workspace).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        root = null
    }

    private data class ObjectOracle(val requested: Path, val resolved: Path, val sha256: String, val size: Long,
        val needed: List<String>, val interpreter: String?, val soname: String?, val rpath: String?, val runpath: String?,
        val aliases: List<ClangNativePathAlias>)
}

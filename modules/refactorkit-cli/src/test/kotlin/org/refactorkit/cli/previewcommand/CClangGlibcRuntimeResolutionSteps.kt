package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.c.ClangArtifactRole
import org.refactorkit.c.ClangGlibcRuntimeCurrency
import org.refactorkit.c.ClangGlibcRuntimeResolution
import org.refactorkit.c.ClangGlibcRuntimeResolver
import org.refactorkit.c.ClangGlibcSearchKind
import org.refactorkit.c.ClangRuntimeDependencyBinding
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Owned cache/library fixtures and independent read-only native oracles; no runtime rule lives in glue. */
class CClangGlibcRuntimeResolutionSteps {
    private var root: Path? = null
    private var condition = ""
    private lateinit var executable: Path
    private lateinit var role: ClangArtifactRole
    private lateinit var cache: Path
    private lateinit var preload: Path
    private var mutableLibrary: Path? = null
    private var originalHashes = emptyMap<Path, String>()
    private var ownedHashes = emptyMap<Path, String>()
    private var expected = emptyMap<Path, Oracle>()
    private var bindings = emptySet<ClangRuntimeDependencyBinding>()
    private val service = ClangGlibcRuntimeResolver()
    private var captured: ClangGlibcRuntimeResolution? = null
    private var currency: ClangGlibcRuntimeCurrency? = null
    private var loaderTrace = ""
    private var selectedPathsForEvidence = emptyList<Path>()

    @Given("a C glibc runtime resolution input with {string}")
    fun configuredRuntime(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-glibc-resolution-"); root = workspace
        Files.writeString(workspace.resolve("main.c"), "int main(void) { return 0; }\n")
        val sdk = Path.of(assertNotNull(System.getProperty("refactorkit.c.qualificationToolchain")))
        val tool = when (case) { "compiler" -> "clang-22"; "formatter" -> "clang-format"; else -> "clangd" }
        executable = sdk.resolve("usr/bin/$tool")
        role = when (tool) { "clang-22" -> ClangArtifactRole.CLANG; "clang-format" -> ClangArtifactRole.FORMATTER; else -> ClangArtifactRole.CLANGD }
        val selectedPaths = loaderTracePaths(executable)
        originalHashes = (selectedPaths + listOf(executable, Path.of("/etc/ld.so.cache"))).distinct().associateWith(::hash)
        assertTrue(!Files.exists(Path.of("/etc/ld.so.preload"), NOFOLLOW_LINKS))
        val toolHash = when (tool) {
            "clang-22" -> "31a40dc31f3c15a47aa119aa148f339bf363d8f61202ddbdacbd3f24b71ba113"
            "clangd" -> "aacb4651e1886b98c5413369fc1789f4ca2d4fa5b94fdf0de4f48c98a52d4324"
            else -> "b60fe916a9d98bfd0f317a210f6a06dfde1217ebb0b605da697ef211208d3bcf"
        }
        assertEquals(toolHash, originalHashes.getValue(executable))
        cache = Path.of("/etc/ld.so.cache"); preload = Path.of("/etc/ld.so.preload")
        var expectedPaths = selectedPaths
        if (case in setOf("changed cache", "changed library", "absent cache candidate")) {
            cache = workspace.resolve("cache"); preload = workspace.resolve("preload")
            if (case == "changed cache") Files.copy(Path.of("/etc/ld.so.cache"), cache)
            else {
                val native = selectedPaths.filterNot { it.startsWith(sdk) }.map { it.fileName.toString() to it }.toMutableList()
                if (case == "changed library") {
                    val zlib = selectedPaths.single { it.fileName.toString() == "libz.so.1" }
                    val owned = workspace.resolve("zlib.so"); Files.copy(zlib, owned); mutableLibrary = owned
                    expectedPaths = expectedPaths.map { if (it == zlib) owned else it }
                    val index = native.indexOfFirst { it.first == "libz.so.1" }; native[index] = "libz.so.1" to owned
                } else native.removeAll { it.first == "libz.so.1" }
                Files.write(cache, cacheFixture(native))
            }
        }
        assertTrue(command("/usr/bin/ldconfig", "-p", "-C", cache.toString()).contains("libs found in cache"))
        expected = (expectedPaths + listOf(executable)).map(::oracle).associateBy { it.physical }
        val names = expected.values.flatMap { obj -> listOfNotNull(obj.soname, obj.requested.fileName.toString()).distinct()
            .map { it to obj.physical } }.toMap()
        bindings = expected.values.flatMap { obj -> obj.needed.mapIndexed { index, name ->
            ClangRuntimeDependencyBinding(obj.physical, index, names.getValue(name)) } }.toSet()
        ownedHashes = files()
    }

    @When("the C glibc runtime graph is resolved and checked")
    fun resolveRuntime() {
        captured = service.capture(executable, role, cache, preload)
        val complete = captured as? ClangGlibcRuntimeResolution.Captured ?: return
        assertEquals(ClangGlibcRuntimeCurrency.Current, service.checkCurrent(complete))
        if (condition == "changed cache") {
            Files.write(cache, byteArrayOf(1), StandardOpenOption.APPEND); ownedHashes = ownedHashes + (cache to hash(cache))
        }
        if (condition == "changed library") {
            val owned = assertNotNull(mutableLibrary)
            Files.write(owned, byteArrayOf(1), StandardOpenOption.APPEND); ownedHashes = ownedHashes + (owned to hash(owned))
        }
        currency = service.checkCurrent(complete)
    }

    @Then("the C glibc runtime resolution outcome is {string}")
    fun resolutionOutcome(outcome: String) {
        if (outcome == "refused") {
            assertEquals("clang.glibcResolution.defaultSearchUnavailable", assertIs<ClangGlibcRuntimeResolution.Unavailable>(captured).diagnostic.code)
            assertEquals(null, currency)
        } else {
            val complete = assertIs<ClangGlibcRuntimeResolution.Captured>(captured)
            val manifest = complete.manifest
            assertEquals(expected.size, manifest.objects.size); assertEquals(expected.keys, manifest.objects.map { it.artifact.evidence.path }.toSet())
            assertEquals(bindings, manifest.bindings.toSet())
            assertEquals(bindings.size, manifest.bindings.size)
            for (obj in manifest.objects) {
                val reference = expected.getValue(obj.artifact.evidence.path); val facts = obj.artifact.facts
                assertEquals(reference.sha256, obj.artifact.evidence.sha256); assertEquals(reference.size, obj.artifact.evidence.size)
                assertEquals(reference.needed, facts.needed); assertEquals(reference.soname, facts.soname)
                assertEquals(reference.interpreter, facts.interpreter); assertEquals(reference.rpath, facts.rpath); assertEquals(reference.runpath, facts.runpath)
            }
            val selectedObservations = complete.observations.filter {
                it.kind in setOf(ClangGlibcSearchKind.RUNPATH, ClangGlibcSearchKind.CACHE, ClangGlibcSearchKind.LOADED)
            }
            val selectedOccurrences = selectedObservations.map { it.requester to it.neededIndex }.toSet()
            assertEquals(bindings.map { it.requester to it.neededIndex }.toSet(), selectedOccurrences,
                "Every indexed DT_NEEDED edge must have one retained successful search observation")
            for (observation in selectedObservations) {
                val edge = bindings.single { it.requester == observation.requester && it.neededIndex == observation.neededIndex }
                assertEquals(edge.target, Path.of(command("/usr/bin/readlink", "-f", observation.path.toString()).trim()))
            }
            if (outcome == "current") assertEquals(ClangGlibcRuntimeCurrency.Current, currency)
            else assertEquals("clang.glibcResolution.changed", assertIs<ClangGlibcRuntimeCurrency.NotCurrent>(currency).diagnostic.code)
        }
        val destination = System.getProperty("refactorkit.c.glibcResolutionEvidence")?.let(Path::of) ?: return
        Files.createDirectories(destination)
        val complete = captured as? ClangGlibcRuntimeResolution.Captured
        val evidence = buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "Static reference-profile resolution only; no actual native launch or semantic admission")
            put("condition", condition); put("outcome", outcome); put("productionArtifactExecuted", false)
            put("nativeLoaderTrace", loaderTrace)
            put("selectedPaths", buildJsonArray { selectedPathsForEvidence.forEach { add(JsonPrimitive(it.toString())) } })
            put("objectCount", complete?.manifest?.objects?.size ?: 0); put("edgeCount", complete?.manifest?.bindings?.size ?: 0)
            put("manifestIdentity", complete?.manifest?.identity ?: "unavailable")
            put("observations", buildJsonArray {
                for (observation in complete?.observations.orEmpty()) add(buildJsonObject {
                    put("requester", observation.requester.toString()); put("index", observation.neededIndex)
                    put("kind", observation.kind.name); put("path", observation.path.toString())
                })
            })
        }
        Files.writeString(destination.resolve(condition.replace(' ', '-') + ".json"), evidence.toString() + "\n")
    }

    @Then("glibc resolution preserves source and executes no selected artifact")
    fun preservesInputs() {
        assertEquals(ownedHashes, files())
        originalHashes.forEach { (path, expected) -> assertEquals(expected, hash(path)) }
        assertTrue(!Files.exists(Path.of("/etc/ld.so.preload"), NOFOLLOW_LINKS))
    }

    private fun files() = Files.walk(assertNotNull(root)).use { paths -> paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(::hash) }
    private fun oracle(path: Path): Oracle {
        val physical = Path.of(command("/usr/bin/readlink", "-f", path.toString()).trim())
        val output = command("/usr/bin/readelf", "-W", "-l", "-d", path.toString())
        fun field(name: String) = Regex("\\($name\\).*?\\[([^]]*)]").find(output)?.groupValues?.get(1)
        return Oracle(path, physical, hash(path), Files.size(path), Regex("\\(NEEDED\\).*?\\[([^]]*)]").findAll(output).map { it.groupValues[1] }.toList(),
            Regex("Requesting program interpreter: ([^]]+)]").find(output)?.groupValues?.get(1), field("SONAME"), field("RPATH"), field("RUNPATH"))
    }
    private fun loaderTracePaths(tool: Path): List<Path> {
        val programHeaders = command("/usr/bin/readelf", "-W", "-l", tool.toString())
        val interpreter = Regex("Requesting program interpreter: ([^]]+)]")
            .find(programHeaders)?.groupValues?.get(1) ?: error("Selected tool has no readable ELF interpreter")
        loaderTrace = command(interpreter, "--list", tool.toString())
        assertTrue(!loaderTrace.contains("not found"), "The system loader must resolve every selected dependency")
        val pathLine = Regex("^\\s*(?:\\S+\\s+=>\\s+)?(/\\S+)\\s+\\(0x[0-9a-fA-F]+\\)\\s*$")
        val paths = loaderTrace.lineSequence().mapNotNull { line ->
            pathLine.matchEntire(line)?.groupValues?.get(1)?.let(Path::of)
        }.distinct().toList()
        assertTrue(paths.isNotEmpty(), "The system loader must return selected ELF paths")
        selectedPathsForEvidence = paths
        return paths
    }
    private fun hash(path: Path) = command("/usr/bin/sha256sum", path.toString()).substringBefore(' ')
    private fun command(vararg args: String): String {
        val builder = ProcessBuilder(*args).directory(assertNotNull(root).toFile()).redirectErrorStream(true); builder.environment().clear()
        val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(1024 * 1024 + 1) } }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Read-only native oracle timed out")
            val bytes = output.get(1, TimeUnit.SECONDS); assertTrue(bytes.size <= 1024 * 1024)
            assertEquals(0, process.exitValue(), "Read-only native oracle failed: ${args.toList()}")
            return bytes.toString(Charsets.UTF_8)
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS) }
    }
    private fun cacheFixture(records: List<Pair<String, Path>>): ByteArray {
        val encoded = records.map { it.first.toByteArray() to it.second.toString().toByteArray() }
        val size = 48 + encoded.size * 24 + encoded.sumOf { it.first.size + it.second.size + 2 }
        val bytes = ByteArray(size); val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes); bytes[28] = 2
        output.putInt(20, records.size); output.putInt(24, size - 48 - records.size * 24)
        var cursor = 48 + records.size * 24
        encoded.forEachIndexed { index, (name, path) ->
            val offset = 48 + index * 24; output.putInt(offset, 0x303)
            output.putInt(offset + 4, cursor); name.copyInto(bytes, cursor); cursor += name.size + 1
            output.putInt(offset + 8, cursor); path.copyInto(bytes, cursor); cursor += path.size + 1
        }
        return bytes
    }
    @After
    fun cleanup() {
        val workspace = root ?: return
        Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }; root = null
    }
    private data class Oracle(val requested: Path, val physical: Path, val sha256: String, val size: Long, val needed: List<String>,
        val interpreter: String?, val soname: String?, val rpath: String?, val runpath: String?)
}

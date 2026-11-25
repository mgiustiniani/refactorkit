package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.c.CGlibcCacheLookup
import org.refactorkit.c.ClangGlibcRuntimeInputCapture
import org.refactorkit.c.ClangGlibcRuntimeInputCaptureResult
import org.refactorkit.c.ClangGlibcRuntimeInputCurrency
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Owned fixtures and independent read-only native oracles; runtime rules remain in the C module. */
class CClangGlibcRuntimeInputSteps {
    private var root: Path? = null
    private var condition = ""
    private lateinit var cache: Path
    private lateinit var preload: Path
    private lateinit var referenceHash: String
    private var referenceSize = 0L
    private var entryCount = 0
    private var nativeRecords = emptyList<Pair<String, String>>()
    private var ownedHashes = emptyMap<Path, String>()
    private var ownedLinks = emptyMap<Path, Path>()
    private val service = ClangGlibcRuntimeInputCapture()
    private var captured: ClangGlibcRuntimeInputCaptureResult? = null
    private var currency: ClangGlibcRuntimeInputCurrency? = null

    @Given("C glibc runtime inputs have {string}")
    fun configuredInputs(case: String) {
        condition = case
        val workspace = Files.createTempDirectory("refactorkit-c-glibc-inputs-"); root = workspace
        Files.writeString(workspace.resolve("main.c"), "int main(void) { return 0; }\n")
        val reference = Path.of("/etc/ld.so.cache")
        assertTrue(!Files.exists(Path.of("/etc/ld.so.preload"), NOFOLLOW_LINKS), "Reference host requires absent preload")
        referenceHash = hash(reference); referenceSize = Files.size(reference)
        if (case == "host inputs") {
            cache = reference; preload = Path.of("/etc/ld.so.preload")
        } else {
            preload = workspace.resolve("ld.so.preload")
            cache = workspace.resolve("ld.so.cache")
            if (case != "missing cache") {
                if (case == "indirect cache") {
                    Files.copy(reference, workspace.resolve("actual.cache"))
                    Files.createSymbolicLink(cache, Path.of("actual.cache"))
                } else Files.copy(reference, cache)
            }
        }
        val oraclePath = if (case in setOf("missing cache", "indirect cache")) reference else cache
        val output = command("/usr/sbin/ldconfig", "-p", "-C", oraclePath.toString())
        entryCount = Regex("^(\\d+) libs found in cache").find(output)?.groupValues?.get(1)?.toInt()
            ?: error("Native oracle did not report the cache record count")
        nativeRecords = Regex("(?m)^\\s+(\\S+) \\(libc6,x86-64\\) => (.+)$").findAll(output)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(entryCount > 0 && nativeRecords.isNotEmpty())
        assertEquals(referenceHash, hash(oraclePath), "Read-only ldconfig must inspect the same cache bytes")
        ownedHashes = files(); ownedLinks = links()
    }

    @When("the C runtime cache and preload inputs are captured and checked")
    fun captureInputs() {
        captured = service.capture(cache, preload)
        val complete = captured as? ClangGlibcRuntimeInputCaptureResult.Captured ?: return
        assertEquals(ClangGlibcRuntimeInputCurrency.Current, service.checkCurrent(complete))
        if (condition == "changed cache") {
            val bytes = Files.readAllBytes(cache)
            // Alter owned generator text, retaining a valid cache with a different complete input identity.
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(cache, bytes); ownedHashes = ownedHashes + (cache to hash(cache))
        }
        if (condition == "preload appears") {
            Files.writeString(preload, "owned preload presence control\n")
            ownedHashes = ownedHashes + (preload to hash(preload))
        }
        currency = service.checkCurrent(complete)
    }

    @Then("the C glibc runtime input outcome is {string}")
    fun inputOutcome(outcome: String) {
        if (outcome == "refused") {
            val code = if (condition == "indirect cache") "pathInvalid" else "unavailable"
            assertEquals("clang.glibcRuntime.$code", assertIs<ClangGlibcRuntimeInputCaptureResult.Unavailable>(captured).diagnostic.code)
            assertEquals(null, currency)
        } else {
            val complete = assertIs<ClangGlibcRuntimeInputCaptureResult.Captured>(captured)
            assertEquals(cache, complete.cacheEvidence.path); assertEquals(preload, complete.preloadPath)
            assertEquals("glibc-loader-cache", complete.cacheEvidence.role)
            assertEquals(referenceHash, complete.cacheEvidence.sha256); assertEquals(referenceSize, complete.cacheEvidence.size)
            val facts = complete.cacheFacts
            assertEquals(referenceHash, facts.sha256); assertEquals(entryCount, facts.entries.size)
            assertEquals(nativeRecords, facts.entries.filter { it.flags == 0x303 }.map { it.name to it.path })
            val ffi = nativeRecords.filter { it.first == "libffi.so.8" }.map { it.second }.distinct().single()
            assertEquals(CGlibcCacheLookup.Candidate(ffi), facts.nativeCandidate("libffi.so.8"))
            if (outcome == "current") assertEquals(ClangGlibcRuntimeInputCurrency.Current, currency)
            else {
                val code = if (condition == "preload appears") "preloadPresent" else "changed"
                assertEquals("clang.glibcRuntime.$code", assertIs<ClangGlibcRuntimeInputCurrency.NotCurrent>(currency).diagnostic.code)
            }
        }
        val destination = System.getProperty("refactorkit.c.glibcInputEvidence")?.let(Path::of) ?: return
        Files.createDirectories(destination)
        val evidence = buildJsonObject {
            put("authority", "docs/arc42/appendix-requirements.adoc")
            put("boundary", "Selected cache/absence native-data capture; no actual loader search or execution admission")
            put("condition", condition); put("outcome", outcome)
            put("cachePath", cache.toString()); put("preloadPath", preload.toString())
            put("referenceSha256", referenceHash); put("referenceSize", referenceSize)
            put("entryCount", entryCount); put("nativeRecordCount", nativeRecords.size)
            put("productionToolExecuted", false)
        }
        Files.writeString(destination.resolve(condition.replace(' ', '-') + ".json"), evidence.toString() + "\n")
    }

    @Then("glibc runtime inspection preserves source and executes no selected tool")
    fun preservesInputs() {
        assertEquals(ownedHashes, files()); assertEquals(ownedLinks, links())
        assertEquals(referenceHash, hash(Path.of("/etc/ld.so.cache")))
        assertTrue(!Files.exists(Path.of("/etc/ld.so.preload"), NOFOLLOW_LINKS))
    }

    private fun files() = Files.walk(assertNotNull(root)).use { paths ->
        paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith(::hash) }
    private fun links() = Files.walk(assertNotNull(root)).use { paths ->
        paths.filter(Files::isSymbolicLink).toList().associateWith(Files::readSymbolicLink) }
    private fun hash(path: Path) = command("/usr/bin/sha256sum", path.toString()).substringBefore(' ')
    private fun command(vararg arguments: String): String {
        val builder = ProcessBuilder(*arguments).directory(assertNotNull(root).toFile()).redirectErrorStream(true)
        builder.environment().clear()
        val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(1024 * 1024 + 1) } }
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Read-only native oracle timed out")
            val bytes = output.get(1, TimeUnit.SECONDS); assertTrue(bytes.size <= 1024 * 1024)
            assertEquals(0, process.exitValue(), "Read-only native oracle failed: ${arguments.toList()}")
            return bytes.toString(Charsets.UTF_8)
        } finally { if (process.isAlive) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS) }
    }

    @After
    fun cleanup() {
        val workspace = root ?: return
        Files.walk(workspace).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        root = null
    }
}

package org.refactorkit.cli.previewcommand

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.refactorkit.c.CProjectScanException
import org.refactorkit.c.CProjectScanPolicy
import org.refactorkit.c.CProjectScanner
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourceCapturePolicy
import org.refactorkit.core.OwnedSourceCaptureException
import org.refactorkit.treesitter.GenericProjectScanner
import org.refactorkit.daemon.DaemonSession
import org.refactorkit.mcp.McpSession
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.JsonRpcErrorCodes
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import kotlin.test.*

class CStructuralCaptureSteps {
    private val fixture = CStructuralCaptureFixture()
    @Given("the C structural capture fixture has {string} inputs")
    fun given(condition: String) = fixture.prepare(condition)
    @When("the structural C scanner captures that workspace with its declared limits")
    fun capture() = fixture.capture()
    @When("the generic inventory captures that C workspace with its declared limits")
    fun captureGeneric() = fixture.captureGeneric()
    @When("the {string} surface opens that C workspace through its generic inventory")
    fun captureManaged(surface: String) = fixture.captureManaged(surface)
    @Then("structural C capture reports {string} without a partial snapshot")
    fun outcome(expected: String) = fixture.assertOutcome(expected)
    @Then("structural C capture preserves the workspace and external canary bytes")
    fun intact() = fixture.assertIntact()
    @After
    fun close() = fixture.close()
}

private class CStructuralCaptureFixture : AutoCloseable {
    private var base: Path? = null
    private lateinit var workspace: Path
    private lateinit var scanRoot: Path
    private lateinit var canary: Path
    private var policy = CProjectScanPolicy()
    private val originals = linkedMapOf<Path, ByteArray>()
    private val links = linkedMapOf<Path, Path>()
    private val expectedLanguages = linkedMapOf("a.c" to "c")
    private var snapshot: ProjectSnapshot? = null
    private var failure: Throwable? = null
    private var generic = false
    private var managed: String? = null
    private var daemon: DaemonSession? = null
    private var mcp: McpSession? = null
    private var managedResponse: JsonObject? = null
    private var protectedCanary = false
    private var rootBoundary: Path? = null
    private var boundarySnapshot: ProjectSnapshot? = null
    private var boundaryFailure: Throwable? = null

    fun prepare(condition: String) {
        val ownedBase = Files.createTempDirectory("rk-c-structural-capture-").also { base = it }
        workspace = Files.createDirectory(ownedBase.resolve("workspace"))
        scanRoot = workspace
        canary = ownedBase.resolve("outside.c")
        write(canary, "int outside_canary(void) { return 23; }\n".toByteArray())
        write(workspace.resolve("a.c"), "int owned(void) { return 7; }\n".toByteArray())
        when (condition) {
            "excessive root ancestry" -> {
                var selectedRoot = workspace
                while (selectedRoot.nameCount < 256) selectedRoot = Files.createDirectory(selectedRoot.resolve("d"))
                check(selectedRoot.nameCount == 256)
                rootBoundary = selectedRoot
                write(selectedRoot.resolve("at-limit.c"), "int at_limit;\n".toByteArray())
                scanRoot = Files.createDirectory(selectedRoot.resolve("d"))
                check(scanRoot.nameCount == 257)
            }
            "unreadable Java source link", "unreadable Kotlin source link" -> {
                val source = if (condition == "unreadable Java source link") "src/main/java/Leak.java"
                    else "src/main/kotlin/Leak.kt"
                val path = workspace.resolve(source)
                Files.createDirectories(path.parent)
                link(path, canary)
                Files.setPosixFilePermissions(canary, emptySet())
                check(!Files.isReadable(canary)) { "Native qualification requires an unreadable first-party canary" }
                protectedCanary = true
            }
            "managed regular sources" -> {
                write(workspace.resolve("include/api.h"), "int owned(void);\n".toByteArray())
                write(workspace.resolve("web/app.ts"), "export const value = 7;\n".toByteArray())
                expectedLanguages.putAll(mapOf("include/api.h" to "c", "web/app.ts" to "typescript"))
            }
            "managed oversized source" -> write(workspace.resolve("a.c"), ByteArray(2 * 1024 * 1024 + 1) { 120 })
            "regular sources" -> {
                write(workspace.resolve("include/api.h"), "#pragma once\nint owned(void);\n".toByteArray())
                write(workspace.resolve("objc/sample.m"), "int sample = 0;\n".toByteArray())
                write(workspace.resolve("cpp/sample.cpp"), "int sample = 0;\n".toByteArray())
                expectedLanguages.putAll(mapOf("include/api.h" to "c", "objc/sample.m" to "objective-c", "cpp/sample.cpp" to "cpp"))
            }
            "external file link" -> link(workspace.resolve("linked.c"), canary)
            "linked root" -> { scanRoot = ownedBase.resolve("alias"); link(scanRoot, workspace) }
            "oversized source" -> policy = CProjectScanPolicy(maxFileBytes = 16)
            "total source bytes" -> {
                write(workspace.resolve("b.c"), "int second(void) { return 9; }\n".toByteArray())
                policy = CProjectScanPolicy(maxTotalBytes = 40)
            }
            "too many entries" -> {
                write(workspace.resolve("notes.txt"), "first party\n".toByteArray())
                policy = CProjectScanPolicy(maxEntries = 2)
            }
            "directory link" -> link(workspace.resolve("vendor"), ownedBase)
            "invalid UTF8" -> write(workspace.resolve("a.c"), byteArrayOf(0xc3.toByte(), 0x28))
            "too many sources" -> {
                write(workspace.resolve("b.c"), "int another;\n".toByteArray())
                policy = CProjectScanPolicy(maxSources = 1)
            }
            "deep source tree" -> {
                write(workspace.resolve("d/e/deep.c"), "int deep;\n".toByteArray())
                policy = CProjectScanPolicy(maxDepth = 1)
            }
            "ignored source tree" -> {
                write(workspace.resolve("build/generated.c"), byteArrayOf(0xff.toByte()))
                link(workspace.resolve("build/external.c"), canary)
            }
            "exact budgets" -> {
                val size = originals.getValue(workspace.resolve("a.c")).size
                policy = CProjectScanPolicy(maxFileBytes = size, maxTotalBytes = size,
                    maxSources = 1, maxEntries = 2, maxDepth = 1)
            }
            else -> error("Unknown fixture: $condition")
        }
    }

    private fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        Files.write(path, bytes)
        originals[path] = bytes
    }

    private fun link(path: Path, target: Path) {
        Files.createSymbolicLink(path, target)
        links[path] = target
    }

    fun capture() {
        try { snapshot = CProjectScanner(policy).scan(scanRoot) }
        catch (caught: Throwable) { failure = caught }
    }

    fun captureGeneric() {
        generic = true
        val limits = SourceCapturePolicy(policy.maxFileBytes, policy.maxTotalBytes,
            policy.maxSources, policy.maxEntries, policy.maxDepth)
        val scanner = GenericProjectScanner(mapOf("c" to "c", "h" to "c",
            "m" to "objective-c", "cpp" to "cpp"), limits)
        rootBoundary?.let { boundary ->
            try { boundarySnapshot = scanner.scan(boundary) }
            catch (caught: Throwable) { boundaryFailure = caught }
        }
        try { snapshot = scanner.scan(scanRoot) }
        catch (caught: Throwable) { failure = caught }
    }

    private fun mcpCall(name: String) = assertNotNull(mcp).dispatch("tools/call", buildJsonObject {
        put("name", name); put("arguments", buildJsonObject { put("root", scanRoot.toString()) })
    }).jsonObject

    private fun text(response: JsonObject) = response.getValue("content").jsonArray.joinToString("\n") {
        it.jsonObject.getValue("text").jsonPrimitive.content
    }

    fun captureManaged(surface: String) {
        require(surface in setOf("daemon", "MCP")); managed = surface
        try {
            managedResponse = if (surface == "daemon") {
                DaemonSession().also { daemon = it }.dispatch("project.open", buildJsonObject {
                    put("root", scanRoot.toString())
                }).jsonObject
            } else {
                mcp = McpSession(); mcpCall("project_scan")
            }
        } catch (caught: Throwable) { failure = caught }
    }

    private fun assertManaged(expected: String) {
        if (expected == "captured") {
            assertNull(failure, failure?.toString())
            val response = assertNotNull(managedResponse)
            if (managed == "daemon") {
                assertEquals(expectedLanguages.size, response.getValue("fileCount").jsonPrimitive.int)
                assertEquals(expectedLanguages.size, response.getValue("indexedSourceCount").jsonPrimitive.int)
                val status = assertNotNull(daemon).dispatch("index.status", null).jsonObject
                assertEquals(expectedLanguages.values.toSet(), status.getValue("languages").jsonArray.map { it.jsonPrimitive.content }.toSet())
                assertEquals(response.getValue("snapshotHash"), status.getValue("snapshotHash"))
            } else {
                assertNotEquals(true, response["isError"]?.jsonPrimitive?.booleanOrNull, text(response))
                assertTrue(text(response).contains("\nFiles: ${expectedLanguages.size}\n"), text(response))
                val summary = mcpCall("project_summary")
                assertNotEquals(true, summary["isError"]?.jsonPrimitive?.booleanOrNull, text(summary))
                assertTrue(text(summary).contains("\nFiles  : ${expectedLanguages.size}\n"), text(summary))
                assertEquals(text(response).substringAfter("Snapshot: ").trim(), text(summary).substringAfter("Snapshot: ").trim())
            }
        } else if (managed == "daemon") {
            assertNull(managedResponse)
            val refused = assertIs<JsonRpcException>(failure)
            assertEquals(JsonRpcErrorCodes.PLAN_REFUSED, refused.code)
            val data = assertNotNull(refused.data).jsonObject
            assertEquals("workspace.sourceCapture.unavailable", data.getValue("refusalCode").jsonPrimitive.content)
            assertEquals(expected, data.getValue("sourceCaptureFailure").jsonPrimitive.content)
            val unopened = assertFailsWith<JsonRpcException> { assertNotNull(daemon).dispatch("project.summary", null) }
            assertEquals(JsonRpcErrorCodes.PROJECT_NOT_OPEN, unopened.code)
        } else {
            assertNull(failure)
            val response = assertNotNull(managedResponse)
            assertEquals(true, response["isError"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(text(response).contains("workspace.sourceCapture.unavailable: $expected"), text(response))
            val summary = mcpCall("project_summary")
            assertEquals(true, summary["isError"]?.jsonPrimitive?.booleanOrNull)
            assertTrue(text(summary).contains("No project open"), text(summary))
        }
    }

    fun assertOutcome(expected: String) {
        if (managed != null) { assertManaged(expected); return }
        rootBoundary?.let { boundary ->
            assertNull(boundaryFailure, boundaryFailure?.toString())
            val captured = assertNotNull(boundarySnapshot)
            assertEquals(boundary, captured.workspace.root)
            assertEquals(mapOf(Path.of("at-limit.c") to "int at_limit;\n"), captured.files.associate { it.path to it.content })
        }
        if (expected == "captured") {
            assertNull(failure, failure?.toString())
            val captured = assertNotNull(snapshot)
            assertEquals(workspace, captured.workspace.root)
            val expectedSources = expectedLanguages.keys.associate { path ->
                Path.of(path) to originals.getValue(workspace.resolve(path)).toString(Charsets.UTF_8)
            }
            assertEquals(expectedSources, captured.files.associate { it.path to it.content })
            assertEquals(expectedLanguages.keys.sorted(),
                captured.files.map { it.path.toString() })
            assertEquals(expectedLanguages,
                captured.files.associate { it.path.toString() to it.languageId })
            assertFalse(captured.files.any { it.content.contains("outside_canary") })
        } else {
            assertNull(snapshot, "Unavailable capture must never return a partial snapshot")
            val code = if (generic) assertIs<OwnedSourceCaptureException>(failure).code.name
                else assertIs<CProjectScanException>(failure).code.name
            assertEquals(expected, code, failure.toString())
        }
    }

    fun assertIntact() {
        originals.forEach { (path, bytes) ->
            if (protectedCanary && path == canary) {
                assertEquals(emptySet(), Files.getPosixFilePermissions(path))
                Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ))
                try { assertContentEquals(bytes, Files.readAllBytes(path)) }
                finally { Files.setPosixFilePermissions(path, emptySet()) }
            } else assertContentEquals(bytes, Files.readAllBytes(path))
        }
        links.forEach { (path, target) -> assertEquals(target, Files.readSymbolicLink(path)) }
        assertFalse(Files.exists(workspace.resolve(if (managed == null) ".refactorkit" else ".refactorkit/transactions")))
        Files.walk(workspace).use { stream ->
            assertFalse(stream.anyMatch { it.fileName.toString().endsWith(".o") })
        }
    }

    override fun close() {
        daemon?.close(); mcp?.close()
        base?.let { root -> Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        } }
    }
}

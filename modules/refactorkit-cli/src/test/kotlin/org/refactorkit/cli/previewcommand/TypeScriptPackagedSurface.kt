package org.refactorkit.cli.previewcommand

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.JsonRpcException
import org.refactorkit.core.ManagedSemanticProcess
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real wire boundary for authored Stories; source-child mode is explicit and never packaged evidence. */
internal class TypeScriptPackagedSurface(
    private val runtime: Path,
    private val workspace: Path,
    private val sourceClasspath: String? = null,
) : AutoCloseable {
    private val manager = ExternalSemanticProcessManager()
    private val io = Executors.newSingleThreadExecutor()
    private val processes = mutableListOf<ManagedSemanticProcess>()
    private var persistent: ManagedSemanticProcess? = null
    private var reader: java.io.BufferedReader? = null
    private var sequence = 0
    private var recordSequence = 0
    private val evidence = (System.getProperty("refactorkit.ts.packaged.evidence")?.let(Path::of)
        ?: run { require(sourceClasspath != null); Files.createTempDirectory("rk-source-wire-") })
        .resolve(UUID.randomUUID().toString())
    private val javaExecutable = runtime.resolve(if (sourceClasspath == null) "runtime/bin/java" else "bin/java")

    init {
        require(runtime.isAbsolute && Files.isExecutable(javaExecutable))
        if (sourceClasspath == null) require(Files.isExecutable(runtime.resolve("bin/refactorkit")))
        Files.createDirectories(evidence)
        if (sourceClasspath != null) {
            // An argument file retains the exact test classpath without relaxing process argument bounds.
            val quotedClasspath = sourceClasspath.replace("\\", "\\\\").replace("\"", "\\\"")
            Files.writeString(evidence.resolve("source-java.args"), "-cp\n\"$quotedClasspath\"\norg.refactorkit.daemon.RefactorKitDaemonKt\n")
            println("Source-child wire evidence (not packaged): $evidence")
        }
    }

    fun record(kind: String, data: JsonElement) {
        Files.writeString(evidence.resolve("${++recordSequence}-$kind.json"), data.toString() + "\n")
    }

    private fun launch(name: String, arguments: List<String> = emptyList()): ManagedSemanticProcess {
        if (sourceClasspath != null) require(name == "refactorkit-daemon" && arguments.isEmpty())
        val child = manager.launch(SemanticProcessSpec(
            "public-${processes.size}", if (sourceClasspath == null) runtime.resolve("bin/$name") else javaExecutable,
            if (sourceClasspath == null) arguments else listOf("@${evidence.resolve("source-java.args")}"), workspace,
            // The launcher must use its embedded Java, not injected Java options or a global JAVA_HOME.
            environment = mapOf("PATH" to "/usr/bin:/bin", "HOME" to workspace.toString()),
            limits = SemanticProcessLimits(maxStdoutBytes = 16L * 1024 * 1024, maxStderrBytes = 64 * 1024),
        ))
        processes += child
        record("launch", buildJsonObject {
            put("mode", if (sourceClasspath == null) "packaged" else "source-child"); put("pid", child.provenance.pid)
            put("executable", child.provenance.executable.toString())
            put("executableSha256", child.provenance.executableSha256)
            put("argumentsSha256", child.provenance.argumentsSha256)
            put("workingDirectory", workspace.toString()); put("runtime", runtime.toString())
        })
        return child
    }

    private fun <T> bounded(child: ManagedSemanticProcess, action: () -> T): T {
        val result = io.submit<T> { action() }
        return try { result.get(120, TimeUnit.SECONDS) } catch (failure: Exception) {
            // Closing the child unblocks both writes and partial response reads before executor shutdown.
            child.close()
            result.cancel(true)
            throw AssertionError("Packaged surface exchange failed: ${child.stderrText()}", failure)
        }
    }

    fun cli(arguments: List<String>, expectedExit: Int): String {
        val child = launch("refactorkit", arguments)
        try {
            val observedJava = bounded(child) {
                val expected = javaExecutable.toRealPath()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                var observed: Path? = null
                while (observed != expected) {
                    check(child.isAlive && System.nanoTime() < deadline) { "CLI embedded Java was not observed before exit/deadline" }
                    observed = ProcessHandle.of(child.provenance.pid).orElseThrow().info().command().orElse(null)
                        ?.let { Path.of(it).toRealPath() }
                    if (observed != expected) Thread.sleep(1)
                }
                requireNotNull(observed)
            }
            record("embedded-java", buildJsonObject { put("pid", child.provenance.pid); put("executable", observedJava.toString()) })
            val output = bounded(child) {
                child.input.close()
                child.output.bufferedReader(Charsets.UTF_8).readText().also {
                    check(child.awaitExit(10_000)) { "Packaged CLI did not exit after EOF" }
                }
            }
            record("cli", buildJsonObject {
                put("stdout", output); put("stderr", child.stderrText()); put("exit", child.exitCode)
            })
            assertEquals(expectedExit, child.exitCode, "stdout=$output\nstderr=${child.stderrText()}")
            assertFalse(child.stderrTruncated())
            return output + if (expectedExit == 0) "" else child.stderrText()
        } finally { child.close() }
    }

    fun dispatch(surface: String, method: String, params: JsonObject): JsonElement {
        val child = persistent ?: launch(if (surface == "daemon") "refactorkit-daemon" else "refactorkit-mcp").also {
            persistent = it; reader = it.output.bufferedReader(Charsets.UTF_8)
        }
        val id = ++sequence
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params)
        }
        record("request", request)
        val response = bounded(child) {
            child.input.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            child.input.flush()
            Json.parseToJsonElement(requireNotNull(reader).readLine() ?: error("EOF before response $id")).jsonObject
        }
        record("response", response)
        assertEquals("2.0", response.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals(id, response.getValue("id").jsonPrimitive.int)
        val executable = ProcessHandle.of(child.provenance.pid).orElseThrow().info().command().orElseThrow()
        assertEquals(javaExecutable.toRealPath(), Path.of(executable).toRealPath())
        response["error"]?.takeIf { it != JsonNull }?.jsonObject?.let { error ->
            throw JsonRpcException(error.getValue("code").jsonPrimitive.int,
                error.getValue("message").jsonPrimitive.content, error["data"])
        }
        return response.getValue("result")
    }

    fun killApplying(planId: String, committed: () -> Boolean) {
        val child = requireNotNull(persistent)
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", ++sequence); put("method", "refactor.apply")
            put("params", buildJsonObject { put("planId", planId) })
        }
        record("request", request)
        bounded(child) {
            child.input.write((request.toString() + "\n").toByteArray(Charsets.UTF_8)); child.input.flush()
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        while (!committed()) {
            check(child.isAlive && System.nanoTime() < deadline) { "No committed module image before exit/deadline" }
            Thread.sleep(1)
        }
        val handle = ProcessHandle.of(child.provenance.pid).orElseThrow()
        val descendants = handle.descendants().use { it.toList() }
        // This is an external crash, not a graceful session shutdown or a production test seam.
        descendants.asReversed().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
        check(child.awaitExit(10_000))
        child.close()
        val descendantDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (descendants.any { it.isAlive } && System.nanoTime() < descendantDeadline) Thread.sleep(1)
        assertTrue(descendants.none { it.isAlive }, "Forced descendants did not exit before the deadline")
        record("killed", buildJsonObject { put("pid", child.provenance.pid); put("exit", child.exitCode) })
        persistent = null; reader = null
    }

    fun notifyInitialized() {
        val child = requireNotNull(persistent)
        val request = buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }
        record("notification", request)
        bounded(child) {
            child.input.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
            child.input.flush()
        }
    }

    override fun close() {
        try {
            persistent?.let { child ->
                bounded(child) { child.input.close(); check(child.awaitExit(10_000)) { "Packaged server did not close after EOF" } }
                assertEquals(0, child.exitCode, child.stderrText())
                assertFalse(child.stderrTruncated())
            }
        } finally {
            processes.forEach(ManagedSemanticProcess::close)
            manager.close()
            io.shutdownNow()
            assertTrue(io.awaitTermination(10, TimeUnit.SECONDS))
            record("closed", buildJsonObject {
                put("allChildrenExited", processes.all { !it.isAlive })
                put("launchCount", processes.size)
            })
            assertTrue(processes.all { !it.isAlive })
        }
    }
}

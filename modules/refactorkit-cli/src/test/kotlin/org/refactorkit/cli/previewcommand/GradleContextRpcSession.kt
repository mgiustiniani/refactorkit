package org.refactorkit.cli.previewcommand

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.refactorkit.core.JsonRpcException
import org.refactorkit.daemon.DaemonSession
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal interface GradleContextRpcSession : AutoCloseable {
    fun dispatch(method: String, params: JsonObject?): kotlinx.serialization.json.JsonElement
}

internal fun newGradleContextRpcSession(): GradleContextRpcSession {
    val packagedRoot = System.getProperty("refactorkit.gradle.composite.package.root")
        ?: return InProcessGradleContextRpcSession()
    return PackagedGradleContextRpcSession(Path.of(packagedRoot))
}

private class InProcessGradleContextRpcSession : GradleContextRpcSession {
    private val delegate = DaemonSession()

    override fun dispatch(method: String, params: JsonObject?): kotlinx.serialization.json.JsonElement = delegate.dispatch(method, params)

    override fun close() = delegate.close()
}

private class PackagedGradleContextRpcSession(packageRoot: Path) : GradleContextRpcSession {
    private val requestIds = AtomicLong()
    private val stderrFile = Files.createTempFile("refactorkit-packaged-gradle-context-", ".stderr")
    private val lines = LinkedBlockingQueue<DaemonLine>()
    private val process = ProcessBuilder(launcherCommand(packageRoot))
        .redirectError(stderrFile.toFile())
        .start()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)

    init {
        Thread({
            runCatching {
                process.inputStream.bufferedReader(Charsets.UTF_8).useLines { output ->
                    output.forEach { lines.put(DaemonLine(it)) }
                }
            }.onFailure { lines.offer(DaemonLine(null)) }
            lines.offer(DaemonLine(null))
        }, "refactorkit-packaged-daemon-stdout").apply {
            isDaemon = true
            start()
        }
    }

    override fun dispatch(method: String, params: JsonObject?): kotlinx.serialization.json.JsonElement {
        val id = requestIds.incrementAndGet()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        writer.write(Json.encodeToString(JsonObject.serializer(), request))
        writer.newLine()
        writer.flush()

        val line = lines.poll(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)?.value
            ?: error("Packaged daemon emitted no response for $method; stderr: ${stderrText()}")
        val response = Json.parseToJsonElement(line).jsonObject
        check(response["jsonrpc"]?.jsonPrimitive?.content == "2.0") { "Invalid packaged daemon response for $method" }
        check(response["id"]?.jsonPrimitive?.longOrNull == id) { "Mismatched packaged daemon response ID for $method" }
        response["error"]?.takeUnless { it is JsonNull }?.let { error ->
            val errorObject = error.jsonObject
            throw JsonRpcException(
                errorObject["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -32603,
                errorObject["message"]?.jsonPrimitive?.content ?: "Packaged daemon RPC failed: $method",
                errorObject["data"],
            )
        }
        return requireNotNull(response["result"]) { "Packaged daemon omitted result for $method" }
    }

    override fun close() {
        writer.close()
        if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Packaged daemon did not stop after stdin EOF; stderr: ${stderrText()}")
        }
        check(process.exitValue() == 0) { "Packaged daemon exited ${process.exitValue()}; stderr: ${stderrText()}" }
        Files.deleteIfExists(stderrFile)
    }

    private fun stderrText(): String = Files.readString(stderrFile, Charsets.UTF_8).takeLast(4_096)

    private fun launcherCommand(packageRoot: Path): List<String> {
        val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        val launcher = packageRoot.resolve("bin").resolve(if (windows) "refactorkit-daemon.bat" else "refactorkit-daemon")
        require(Files.isRegularFile(launcher)) { "Packaged daemon launcher is missing: $launcher" }
        return if (windows) listOf("cmd.exe", "/d", "/c", "call", launcher.toString()) else listOf(launcher.toString())
    }

    private data class DaemonLine(val value: String?)

    private companion object {
        const val RESPONSE_TIMEOUT_SECONDS = 180L
        const val SHUTDOWN_TIMEOUT_SECONDS = 20L
    }
}

package org.refactorkit.core

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Defensive lifecycle limits for an external semantic engine process. */
data class SemanticProcessLimits(
    val maxStdoutBytes: Long = ProtocolLimits.MAX_SEMANTIC_PROCESS_STDOUT_BYTES,
    val maxStderrBytes: Int = ProtocolLimits.MAX_SEMANTIC_PROCESS_STDERR_BYTES,
    val gracefulShutdownMillis: Long = 2_000,
) {
    init {
        require(maxStdoutBytes in 1..MAX_STREAM_BYTES) { "stdout limit is outside the safe range" }
        require(maxStderrBytes in 1..MAX_STREAM_BYTES.toInt()) { "stderr limit is outside the safe range" }
        require(gracefulShutdownMillis in 1..30_000) { "shutdown timeout is outside the safe range" }
    }

    companion object {
        private const val MAX_STREAM_BYTES = 512L * 1024L * 1024L
    }
}

data class SemanticProcessSpec(
    val id: String,
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val workingDirectory: Path,
    val environment: Map<String, String> = emptyMap(),
    val limits: SemanticProcessLimits = SemanticProcessLimits(),
) {
    init {
        require(ID.matches(id)) { "semantic process ID must be canonical" }
        require(arguments.size <= ProtocolLimits.MAX_SEMANTIC_PROCESS_ARGUMENTS) { "semantic process argument count exceeds limit" }
        require(arguments.all { it.length <= 4_096 && '\u0000' !in it }) { "semantic process argument is invalid or too large" }
        require(environment.size <= 32) { "semantic process environment exceeds limit" }
        require(environment.all { (key, value) ->
            ENVIRONMENT_KEY.matches(key) && value.length <= 8_192 && '\u0000' !in value &&
                SECRET_KEYWORDS.none { key.contains(it, ignoreCase = true) }
        }) { "semantic process environment contains invalid or secret-shaped entries" }
    }

    companion object {
        private val ID = Regex("[a-z][a-z0-9._-]{0,63}")
        private val ENVIRONMENT_KEY = Regex("[A-Za-z_][A-Za-z0-9_]{0,127}")
        private val SECRET_KEYWORDS = setOf("TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTH", "PRIVATE_KEY")
    }
}

data class SemanticProcessProvenance(
    val id: String,
    val executable: Path,
    val executableSha256: String,
    val argumentsSha256: String,
    val workingDirectory: Path,
    val pid: Long,
    val startedAt: Instant,
)

class SemanticProcessLimitException(message: String) : IOException(message)

/** A launched process whose complete process tree is owned by RefactorKit. */
class ManagedSemanticProcess internal constructor(
    private val process: Process,
    val provenance: SemanticProcessProvenance,
    private val limits: SemanticProcessLimits,
    private val onClosed: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val stdoutBytes = AtomicLong(0)
    private val stderrBuffer = ByteArrayOutputStream()
    private val stderrOverflow = AtomicBoolean(false)
    private val stdout = BoundedInputStream(process.inputStream, limits.maxStdoutBytes, stdoutBytes) {
        terminateForLimit("stdout exceeded ${limits.maxStdoutBytes} bytes")
    }
    private val stderrThread = Thread({ drainStderr() }, "refactorkit-semantic-stderr-${provenance.id}").apply {
        isDaemon = true
        start()
    }

    val input: OutputStream get() = process.outputStream
    val output: InputStream get() = stdout
    val isAlive: Boolean get() = process.isAlive
    val exitCode: Int? get() = if (process.isAlive) null else process.exitValue()

    fun stderrText(): String = synchronized(stderrBuffer) {
        stderrBuffer.toByteArray().toString(Charsets.UTF_8)
    }

    fun stderrTruncated(): Boolean = stderrOverflow.get()

    fun awaitExit(timeoutMillis: Long): Boolean {
        require(timeoutMillis in 1..300_000) { "await timeout is outside the safe range" }
        return process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun cancel() = close()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        terminateTree(process, limits.gracefulShutdownMillis)
        runCatching { input.close() }
        runCatching { output.close() }
        runCatching { stderrThread.join(limits.gracefulShutdownMillis) }
        onClosed()
    }

    private fun drainStderr() {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        process.errorStream.use { stream ->
            while (true) {
                val count = runCatching { stream.read(buffer) }.getOrDefault(-1)
                if (count < 0) return
                synchronized(stderrBuffer) {
                    val remaining = limits.maxStderrBytes - stderrBuffer.size()
                    if (remaining > 0) stderrBuffer.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) stderrOverflow.set(true)
                }
                if (stderrOverflow.get()) {
                    terminateTree(process, limits.gracefulShutdownMillis)
                    return
                }
            }
        }
    }

    private fun terminateForLimit(message: String): Nothing {
        terminateTree(process, limits.gracefulShutdownMillis)
        throw SemanticProcessLimitException(message)
    }

    companion object {
        private fun terminateTree(process: Process, gracefulMillis: Long) {
            val handle = process.toHandle()
            val descendants = runCatching { handle.descendants().toList().asReversed() }.getOrDefault(emptyList())
            descendants.forEach { runCatching { it.destroy() } }
            runCatching { handle.destroy() }
            runCatching { process.waitFor(gracefulMillis, TimeUnit.MILLISECONDS) }
            descendants.filter(ProcessHandle::isAlive).forEach { runCatching { it.destroyForcibly() } }
            if (handle.isAlive) runCatching { handle.destroyForcibly() }
            runCatching { process.waitFor(gracefulMillis, TimeUnit.MILLISECONDS) }
        }
    }
}

/** Launches a bounded number of provenance-recorded external semantic engines. */
class ExternalSemanticProcessManager(
    private val maxProcesses: Int = ProtocolLimits.MAX_SEMANTIC_PROCESSES,
) : AutoCloseable {
    private val processes = ConcurrentHashMap<String, ManagedSemanticProcess>()

    init {
        require(maxProcesses in 1..64) { "semantic process count limit is outside the safe range" }
    }

    @Synchronized
    fun launch(spec: SemanticProcessSpec): ManagedSemanticProcess {
        require(processes.size < maxProcesses) { "semantic process manager is at capacity" }
        require(!processes.containsKey(spec.id)) { "semantic process ID is already active: ${spec.id}" }
        val executable = spec.executable.toAbsolutePath().normalize()
        require(executable.isAbsolute && Files.isRegularFile(executable)) { "semantic executable must be an absolute regular file" }
        val executableReal = executable.toRealPath()
        val workingDirectory = spec.workingDirectory.toAbsolutePath().normalize()
        require(Files.isDirectory(workingDirectory)) { "semantic working directory must exist" }
        val workingReal = workingDirectory.toRealPath()
        val executableHash = hashFile(executableReal)
        val command = listOf(executableReal.toString()) + spec.arguments
        val builder = ProcessBuilder(command).directory(workingReal.toFile()).redirectErrorStream(false)
        builder.environment().clear()
        builder.environment().putAll(spec.environment.toSortedMap())
        val process = builder.start()
        val provenance = SemanticProcessProvenance(
            id = spec.id,
            executable = executableReal,
            executableSha256 = executableHash,
            argumentsSha256 = hashArguments(spec.arguments),
            workingDirectory = workingReal,
            pid = process.pid(),
            startedAt = Instant.now(),
        )
        lateinit var managed: ManagedSemanticProcess
        managed = ManagedSemanticProcess(process, provenance, spec.limits) {
            processes.remove(spec.id, managed)
        }
        if (hashFile(executableReal) != executableHash) {
            managed.close()
            error("semantic executable changed during launch")
        }
        val previous = processes.putIfAbsent(spec.id, managed)
        if (previous != null) {
            managed.close()
            error("semantic process ID was concurrently registered: ${spec.id}")
        }
        Thread({
            runCatching { process.waitFor() }
            processes.remove(spec.id, managed)
        }, "refactorkit-semantic-exit-${spec.id}").apply {
            isDaemon = true
            start()
        }
        return managed
    }

    fun activeProvenance(): List<SemanticProcessProvenance> = processes.values
        .map(ManagedSemanticProcess::provenance).sortedBy(SemanticProcessProvenance::id)

    fun cancel(id: String): Boolean = processes[id]?.let { it.cancel(); true } ?: false

    override fun close() {
        processes.values.toList().forEach(ManagedSemanticProcess::close)
        processes.clear()
    }

    private fun hashFile(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun hashArguments(arguments: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        arguments.forEach { argument ->
            digest.update(argument.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

private class BoundedInputStream(
    input: InputStream,
    private val limit: Long,
    private val count: AtomicLong,
    private val overflow: () -> Nothing,
) : FilterInputStream(input) {
    override fun read(): Int {
        val value = super.read()
        if (value >= 0 && count.incrementAndGet() > limit) overflow()
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0 && count.addAndGet(read.toLong()) > limit) overflow()
        return read
    }
}

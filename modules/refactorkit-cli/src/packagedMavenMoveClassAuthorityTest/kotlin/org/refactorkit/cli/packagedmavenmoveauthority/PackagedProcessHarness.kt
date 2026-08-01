package org.refactorkit.cli.packagedmavenmoveauthority

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.min

/** Bounded child-process adapter used only by the packaged CLI acceptance mode. */
internal class PackagedProcessHarness(
    private val launcher: Path,
    private val embeddedJava: Path,
    private val poisonDirectory: Path,
    private val isolatedHome: Path,
    private val isolatedTemp: Path,
    private val poisonMarker: Path,
) {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private val comspec: Path? = if (windows) absoluteComspec() else null
    private val activeProcesses = linkedSetOf<ProcessHandle>()

    var commandCount: Int = 0
        private set
    var timeoutCount: Int = 0
        private set
    var truncatedStreamCount: Int = 0
        private set
    var gracefulDestroyCount: Int = 0
        private set
    var forcedDestroyCount: Int = 0
        private set
    var maximumDurationMillis: Long = 0
        private set

    val controlledJavaToolOptions: String =
        "-Duser.home=${javaToolOptionValue(isolatedHome.toAbsolutePath().normalize().toString())} " +
            "-Djava.io.tmpdir=${javaToolOptionValue(isolatedTemp.toAbsolutePath().normalize().toString())}"

    val environmentAttestation: SubjectEnvironmentAttestation

    init {
        require(Files.isRegularFile(launcher)) { "Packaged launcher is not a regular file" }
        require(Files.isRegularFile(embeddedJava)) { "Embedded java is not a regular file" }
        require(Files.isDirectory(poisonDirectory)) { "Poison PATH directory is missing" }
        require(Files.isDirectory(isolatedHome)) { "Isolated user home is missing" }
        require(Files.isDirectory(isolatedTemp)) { "Isolated temp directory is missing" }
        if (!windows) {
            require(Files.isExecutable(launcher)) { "Packaged Unix launcher is not executable" }
            require(Files.isExecutable(embeddedJava)) { "Embedded Unix java is not executable" }
        }
        val environment = subjectEnvironment()
        environmentAttestation = SubjectEnvironmentAttestation(
            inheritedEnvironmentCleared = true,
            requiredVariablesAbsent = REQUIRED_ABSENT_VARIABLES.all { required ->
                environment.keys.none { it.equals(required, ignoreCase = windows) }
            },
            proxyVariablesAbsent = environment.keys.none(::isProxyVariable),
            poisonPathFirst = environment.getValue("PATH").split(java.io.File.pathSeparator).first() ==
                poisonDirectory.toAbsolutePath().normalize().toString(),
            isolatedHomeConfigured = HOME_VARIABLES.all { name ->
                environment[name]?.let(Path::of)?.toAbsolutePath()?.normalize() == isolatedHome.toAbsolutePath().normalize()
            },
            isolatedTempConfigured = TEMP_VARIABLES.all { name ->
                environment[name]?.let(Path::of)?.toAbsolutePath()?.normalize() == isolatedTemp.toAbsolutePath().normalize()
            },
            controlledJavaToolOptions = environment["JAVA_TOOL_OPTIONS"] == controlledJavaToolOptions,
            windowsInvalidJavaExecutableSentinelInstalled = !windows || (
                Files.isRegularFile(poisonDirectory.resolve("java.exe")) &&
                    Files.readString(poisonDirectory.resolve("java.exe")) == INVALID_WINDOWS_JAVA_EXE_SENTINEL
            ),
            windowsBatchAndCmdMarkerShimsInstalled = !windows || listOf("java.bat", "java.cmd").all { fileName ->
                val shim = poisonDirectory.resolve(fileName)
                Files.isRegularFile(shim) && Files.readString(shim).contains(poisonMarker.fileName.toString())
            },
        )
    }

    fun runLauncher(arguments: List<String>, workingDirectory: Path): BoundedProcessResult =
        run(executable = launcher, arguments = arguments, workingDirectory = workingDirectory, batchFile = windows)

    fun runEmbeddedJava(arguments: List<String>, workingDirectory: Path): BoundedProcessResult =
        run(executable = embeddedJava, arguments = arguments, workingDirectory = workingDirectory, batchFile = false)

    fun activeDirectProcessCount(): Int = synchronized(activeProcesses) {
        activeProcesses.removeIf { !it.isAlive }
        activeProcesses.size
    }

    fun poisonMarkerWasObserved(): Boolean = Files.exists(poisonMarker)

    private fun run(
        executable: Path,
        arguments: List<String>,
        workingDirectory: Path,
        batchFile: Boolean,
    ): BoundedProcessResult {
        require(Files.isDirectory(workingDirectory)) { "Child working directory is missing" }
        require(arguments.none { '\u0000' in it }) { "A child argument contains NUL" }
        val command = if (batchFile) {
            listOf(requireNotNull(comspec).toString(), "/d", "/s", "/c", executable.toString()) + arguments
        } else {
            listOf(executable.toString()) + arguments
        }
        val builder = ProcessBuilder(command)
            .directory(workingDirectory.toFile())
            .redirectErrorStream(false)
        builder.environment().apply {
            clear()
            putAll(subjectEnvironment())
        }

        val startedAt = System.nanoTime()
        val process = builder.start()
        synchronized(activeProcesses) { activeProcesses.add(process.toHandle()) }
        commandCount += 1
        val drains = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "refactorkit-packaged-process-drain").apply { isDaemon = true }
        }
        val stdoutFuture = drains.submit(Callable { drain(process.inputStream) })
        val stderrFuture = drains.submit(Callable { drain(process.errorStream) })
        var timedOut = false
        var descendantsTerminated = 0
        try {
            if (!process.waitFor(CHILD_TIMEOUT.seconds, TimeUnit.SECONDS)) {
                timedOut = true
                timeoutCount += 1
                descendantsTerminated = terminateProcessTree(process)
            }
            val stdout = awaitDrain(stdoutFuture, process)
            val stderr = awaitDrain(stderrFuture, process)
            if (stdout.truncated) truncatedStreamCount += 1
            if (stderr.truncated) truncatedStreamCount += 1
            val durationMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()
            maximumDurationMillis = maxOf(maximumDurationMillis, durationMillis)
            return BoundedProcessResult(
                exitCode = if (process.isAlive) null else process.exitValue(),
                stdout = stdout.bytes.toString(StandardCharsets.UTF_8),
                stderr = stderr.bytes.toString(StandardCharsets.UTF_8),
                stdoutBytesObserved = stdout.totalBytes,
                stderrBytesObserved = stderr.totalBytes,
                stdoutTruncated = stdout.truncated,
                stderrTruncated = stderr.truncated,
                timedOut = timedOut,
                durationMillis = durationMillis,
                descendantsTerminated = descendantsTerminated,
            )
        } catch (interrupted: InterruptedException) {
            descendantsTerminated += terminateProcessTree(process)
            Thread.currentThread().interrupt()
            throw interrupted
        } catch (failure: Throwable) {
            descendantsTerminated += terminateProcessTree(process)
            throw failure
        } finally {
            drains.shutdownNow()
            synchronized(activeProcesses) { activeProcesses.remove(process.toHandle()) }
        }
    }

    private fun awaitDrain(future: Future<BoundedBytes>, process: Process): BoundedBytes = try {
        future.get(DRAIN_COMPLETION_TIMEOUT.seconds, TimeUnit.SECONDS)
    } catch (timeout: TimeoutException) {
        terminateProcessTree(process)
        throw IllegalStateException("A bounded child output drain did not finish", timeout)
    }

    private fun drain(stream: InputStream): BoundedBytes = stream.use { input ->
        val retained = ByteArrayOutputStream(min(OUTPUT_LIMIT_BYTES, DRAIN_BUFFER_BYTES))
        val buffer = ByteArray(DRAIN_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            val remaining = OUTPUT_LIMIT_BYTES - retained.size()
            if (remaining > 0) retained.write(buffer, 0, min(count, remaining))
        }
        BoundedBytes(
            bytes = retained.toByteArray(),
            totalBytes = total,
            truncated = total > OUTPUT_LIMIT_BYTES,
        )
    }

    private fun terminateProcessTree(process: Process): Int {
        val descendants = process.toHandle().descendants().toList().asReversed()
        val handles = (descendants + process.toHandle()).distinct()
        handles.filter(ProcessHandle::isAlive).forEach { handle ->
            if (handle.destroy()) gracefulDestroyCount += 1
        }
        awaitTermination(handles, GRACEFUL_TERMINATION_TIMEOUT)
        handles.filter(ProcessHandle::isAlive).forEach { handle ->
            if (handle.destroyForcibly()) forcedDestroyCount += 1
        }
        awaitTermination(handles, FORCED_TERMINATION_TIMEOUT)
        check(handles.none(ProcessHandle::isAlive)) { "A packaged child process survived bounded termination" }
        return descendants.count { !it.isAlive }
    }

    private fun awaitTermination(handles: List<ProcessHandle>, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (handles.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(25)
        }
    }

    private fun subjectEnvironment(): Map<String, String> = linkedMapOf<String, String>().apply {
        val home = isolatedHome.toAbsolutePath().normalize().toString()
        val temp = isolatedTemp.toAbsolutePath().normalize().toString()
        HOME_VARIABLES.forEach { put(it, home) }
        TEMP_VARIABLES.forEach { put(it, temp) }
        put("XDG_CACHE_HOME", isolatedHome.resolve(".cache").toAbsolutePath().normalize().toString())
        put("XDG_CONFIG_HOME", isolatedHome.resolve(".config").toAbsolutePath().normalize().toString())
        put("XDG_DATA_HOME", isolatedHome.resolve(".local/share").toAbsolutePath().normalize().toString())
        put("GRADLE_USER_HOME", isolatedHome.resolve(".gradle").toAbsolutePath().normalize().toString())
        put("LANG", "C")
        put("LC_ALL", "C")
        put("JAVA_TOOL_OPTIONS", controlledJavaToolOptions)
        put("PATH", minimalPath())
        if (windows) {
            val systemRoot = requireNotNull(parentEnvironmentValue("SystemRoot")) {
                "SystemRoot is required for the Windows packaged-process test"
            }
            put("SystemRoot", systemRoot)
            put("WINDIR", parentEnvironmentValue("WINDIR") ?: systemRoot)
            put("COMSPEC", requireNotNull(comspec).toString())
            parentEnvironmentValue("PATHEXT")?.let { put("PATHEXT", it) }
            val absoluteHome = isolatedHome.toAbsolutePath().normalize()
            val root = absoluteHome.root?.toString().orEmpty()
            put("HOMEDRIVE", root.removeSuffix("\\"))
            put("HOMEPATH", "\\" + absoluteHome.toString().removePrefix(root).trimStart('\\'))
            put("APPDATA", isolatedHome.resolve("AppData/Roaming").toAbsolutePath().normalize().toString())
            put("LOCALAPPDATA", isolatedHome.resolve("AppData/Local").toAbsolutePath().normalize().toString())
        }
        check(keys.none(::isProxyVariable))
        check(REQUIRED_ABSENT_VARIABLES.all { absent -> keys.none { it.equals(absent, ignoreCase = windows) } })
    }

    private fun minimalPath(): String {
        val poison = poisonDirectory.toAbsolutePath().normalize().toString()
        val systemEntries = if (windows) {
            val root = Path.of(requireNotNull(parentEnvironmentValue("SystemRoot"))).toAbsolutePath().normalize()
            listOf(root.resolve("System32").toString(), root.toString())
        } else {
            listOf("/usr/bin", "/bin")
        }
        return (listOf(poison) + systemEntries).distinct().joinToString(java.io.File.pathSeparator)
    }

    private fun absoluteComspec(): Path {
        val raw = requireNotNull(parentEnvironmentValue("COMSPEC")) {
            "COMSPEC is required for the Windows packaged-process test"
        }
        val path = Path.of(raw)
        require(path.isAbsolute && Files.isRegularFile(path)) { "COMSPEC must name an absolute regular file" }
        return path.toAbsolutePath().normalize()
    }

    private fun parentEnvironmentValue(name: String): String? = System.getenv().entries
        .firstOrNull { it.key.equals(name, ignoreCase = windows) }
        ?.value

    private fun isProxyVariable(name: String): Boolean = name.uppercase() in PROXY_VARIABLES

    private fun javaToolOptionValue(value: String): String {
        require('"' !in value && '\n' !in value && '\r' !in value) { "Unsafe isolated-home path" }
        return if (value.any(Char::isWhitespace)) "\"$value\"" else value
    }

    private data class BoundedBytes(
        val bytes: ByteArray,
        val totalBytes: Long,
        val truncated: Boolean,
    )

    companion object {
        val CHILD_TIMEOUT: Duration = Duration.ofSeconds(180)
        val GRACEFUL_TERMINATION_TIMEOUT: Duration = Duration.ofSeconds(3)
        val FORCED_TERMINATION_TIMEOUT: Duration = Duration.ofSeconds(5)
        val DRAIN_COMPLETION_TIMEOUT: Duration = Duration.ofSeconds(10)
        const val OUTPUT_LIMIT_BYTES: Int = 1024 * 1024
        const val DRAIN_BUFFER_BYTES: Int = 8192
        const val INVALID_WINDOWS_JAVA_EXE_SENTINEL: String =
            "RefactorKit packaged acceptance poison: deliberately invalid PE executable.\r\n"

        val REQUIRED_ABSENT_VARIABLES: Set<String> = setOf(
            "JAVA_HOME",
            "JDK_JAVA_OPTIONS",
            "CLASSPATH",
            "MAVEN_OPTS",
            "MAVEN_ARGS",
            "GRADLE_OPTS",
            "_JAVA_OPTIONS",
        )
        val PROXY_VARIABLES: Set<String> = setOf(
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "FTP_PROXY",
            "ALL_PROXY",
            "NO_PROXY",
        )
        val HOME_VARIABLES: Set<String> = setOf("HOME", "USERPROFILE")
        val TEMP_VARIABLES: Set<String> = setOf("TMPDIR", "TMP", "TEMP")
    }
}

internal data class SubjectEnvironmentAttestation(
    val inheritedEnvironmentCleared: Boolean,
    val requiredVariablesAbsent: Boolean,
    val proxyVariablesAbsent: Boolean,
    val poisonPathFirst: Boolean,
    val isolatedHomeConfigured: Boolean,
    val isolatedTempConfigured: Boolean,
    val controlledJavaToolOptions: Boolean,
    val windowsInvalidJavaExecutableSentinelInstalled: Boolean,
    val windowsBatchAndCmdMarkerShimsInstalled: Boolean,
)

internal data class BoundedProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val stdoutBytesObserved: Long,
    val stderrBytesObserved: Long,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val timedOut: Boolean,
    val durationMillis: Long,
    val descendantsTerminated: Int,
)

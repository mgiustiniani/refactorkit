package org.refactorkit.cli.testharness

import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Launches a test-only child JDK with the JDK 21 SecurityManager compatibility option scoped to that child.
 * Caller-provided JVM option variables are removed so the harness configuration is explicit and reproducible.
 */
internal object GuardedChildJvm {
    data class Output(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
    )

    fun launch(
        workingDirectory: Path,
        mainClass: Class<*>,
        arguments: List<String>,
        requiredClasses: List<Class<*>>,
        timeoutSeconds: Long = 30L,
    ): Output {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize()
        check(Files.isExecutable(javaExecutable)) { "active JDK java executable is unavailable: $javaExecutable" }
        check(Runtime.version().feature() == 21) { "guarded child harness requires a JDK 21 parent" }

        val process = ProcessBuilder(
            buildList {
                add(javaExecutable.toString())
                add("-Djava.security.manager=allow")
                add("-cp")
                add(currentTestClasspath(requiredClasses + mainClass))
                add(mainClass.name)
                addAll(arguments)
            },
        )
            .directory(workingDirectory.toFile())
            .apply {
                environment().remove("JAVA_TOOL_OPTIONS")
                environment().remove("JDK_JAVA_OPTIONS")
            }
            .start()

        val stdoutFuture = CompletableFuture.supplyAsync { process.inputStream.readBytes() }
        val stderrFuture = CompletableFuture.supplyAsync { process.errorStream.readBytes() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        check(completed) { "guarded child harness timed out after $timeoutSeconds seconds" }

        return Output(
            exitCode = process.exitValue(),
            stdout = stdoutFuture.get(timeoutSeconds, TimeUnit.SECONDS),
            stderr = stderrFuture.get(timeoutSeconds, TimeUnit.SECONDS),
        )
    }

    fun assertOnlySecurityManagerDeprecationNotice(stderr: ByteArray, callerClass: Class<*>) {
        val text = stderr.toString(Charsets.UTF_8)
        val lines = text.lineSequence().filter(String::isNotBlank).toList()
        check(lines.size == 4) { "guarded child harness emitted unexpected stderr: $text" }
        check(lines[0] == "WARNING: A terminally deprecated method in java.lang.System has been called")
        check(
            lines[1].startsWith("WARNING: System::setSecurityManager has been called by ${callerClass.name} (file:") &&
                lines[1].endsWith("/)")
        ) { lines[1] }
        check(lines[2] == "WARNING: Please consider reporting this to the maintainers of ${callerClass.name}")
        check(lines[3] == "WARNING: System::setSecurityManager will be removed in a future release")
    }

    private fun currentTestClasspath(requiredClasses: List<Class<*>>): String {
        val entries = linkedSetOf<String>()
        System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .forEach(entries::add)

        var loader: ClassLoader? = Thread.currentThread().contextClassLoader
        while (loader != null) {
            if (loader is URLClassLoader) {
                loader.urLs
                    .filter { it.protocol == "file" }
                    .map { Path.of(it.toURI()).toAbsolutePath().normalize().toString() }
                    .forEach(entries::add)
            }
            loader = loader.parent
        }

        requiredClasses.distinct().forEach { requiredClass ->
            val location = Path.of(checkNotNull(requiredClass.protectionDomain.codeSource?.location).toURI())
                .toAbsolutePath().normalize().toString()
            check(location in entries) { "${requiredClass.name} classes are absent from child classpath: $location" }
        }
        return entries.joinToString(File.pathSeparator)
    }
}

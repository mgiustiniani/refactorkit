package org.refactorkit.jvm

import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SemanticProcessLimits
import org.refactorkit.core.SemanticProcessSpec
import org.refactorkit.core.SemanticWorkspaceOverlay
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

sealed interface JavaEphemeralCompilationResult {
    data class Available(val outputHash: String) : JavaEphemeralCompilationResult
    data class Refused(val code: String, val message: String) : JavaEphemeralCompilationResult
}

/** Bounded, annotation-processing-free ECJ compilation into a disposable overlay. */
class JavaEphemeralCompiler(
    private val timeoutMillis: Long = 30_000,
) {
    fun compile(
        snapshot: ProjectSnapshot,
        consumer: (Path) -> Unit,
    ): JavaEphemeralCompilationResult {
        val sources = snapshot.files.filter { it.languageId == "java" && it.path.fileName.toString().endsWith(".java") }
            .sortedBy { it.path.toString() }
        if (sources.isEmpty() || sources.size > MAX_SOURCES) return refused(
            "jvm.javaCompilationSourceLimit", "Ephemeral Java compilation requires 1..$MAX_SOURCES source files",
        )
        val sourceLevels = snapshot.modules.mapNotNull { it.languageSettings["java.sourceLevel"]?.toIntOrNull() }.distinct()
        val sourceLevel = sourceLevels.singleOrNull() ?: 21
        if (sourceLevel !in 8..21) return refused(
            "jvm.javaCompilationTargetUnsupported", "Ephemeral Java compilation requires one Java source level from 8 through 21",
        )
        val javaHome = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()
        val executable = javaHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        if (!Files.isRegularFile(executable)) return refused(
            "jvm.javaCompilerRuntimeUnavailable", "Packaged Java runtime executable is unavailable",
        )
        val runtimeClasspath = runCatching {
            Path.of(Class.forName("org.eclipse.jdt.internal.compiler.batch.Main")
                .protectionDomain.codeSource.location.toURI()).toRealPath().toString()
        }.getOrElse {
            return refused("jvm.javaCompilerRuntimeUnavailable", "ECJ runtime classpath is unavailable")
        }
        val projectClasspath = snapshot.modules.flatMap { it.classpathEntries }.map { entry ->
            snapshot.workspace.root.resolve(entry).toAbsolutePath().normalize()
        }.filter(Files::exists).distinct()
        val overlay = runCatching { SemanticWorkspaceOverlay.create(snapshot) }.getOrElse {
            return refused("jvm.javaCompilationOverlayFailed", "Ephemeral Java compilation overlay could not be created")
        }
        return try {
            val output = overlay.root.resolve(".refactorkit-java-output")
            Files.createDirectory(output)
            val overlaySources = sources.map { source ->
                overlay.toOverlayPath(source.path)
                    ?: return refused("jvm.javaCompilationPathInvalid", "Java source cannot be mapped into the compiler overlay")
            }
            val arguments = buildList {
                add("-cp"); add(runtimeClasspath)
                add("org.eclipse.jdt.internal.compiler.batch.Main")
                add("-proc:none")
                add("-encoding"); add("UTF-8")
                add("-source"); add(sourceLevel.toString())
                add("-target"); add(sourceLevel.toString())
                add("-d"); add(output.toString())
                if (projectClasspath.isNotEmpty()) {
                    add("-classpath"); add(projectClasspath.joinToString(System.getProperty("path.separator")))
                }
                addAll(overlaySources.map(Path::toString))
            }
            val manager = ExternalSemanticProcessManager(1)
            try {
                val process = manager.launch(SemanticProcessSpec(
                    id = "java-ephemeral-${SEQUENCE.incrementAndGet()}",
                    executable = executable,
                    arguments = arguments,
                    workingDirectory = overlay.root,
                    limits = SemanticProcessLimits(MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES.toInt(), 1_000),
                ))
                process.use {
                    val reader = Executors.newSingleThreadExecutor { task ->
                        Thread(task, "refactorkit-java-ephemeral-output").apply { isDaemon = true }
                    }
                    try {
                        val stdoutFuture = reader.submit<ByteArray> { it.output.readNBytes(MAX_OUTPUT_BYTES.toInt() + 1) }
                        if (!it.awaitExit(timeoutMillis)) return refused(
                            "jvm.javaCompilationTimeout", "Ephemeral Java compilation exceeded its deadline",
                        )
                        val stdout = stdoutFuture.get(1, TimeUnit.SECONDS)
                        if (stdout.size > MAX_OUTPUT_BYTES) return refused(
                            "jvm.javaCompilationOutputLimit", "Ephemeral Java compiler output exceeded its bound",
                        )
                        if (it.exitCode != 0 || it.stderrTruncated()) return refused(
                            "jvm.javaCompilationFailed", "Ephemeral Java compilation failed without publishing partial evidence",
                        )
                    } finally {
                        reader.shutdownNow()
                    }
                }
            } finally {
                manager.close()
            }
            val mutations = overlay.verifySourcesUnchanged()
            if (mutations.isNotEmpty()) return refused(
                "jvm.javaCompilationSourceMutation", "Ephemeral Java compilation changed source evidence",
            )
            val classesHash = hashOutput(output) ?: return refused(
                "jvm.javaCompilationOutputInvalid", "Ephemeral Java compilation produced invalid class evidence",
            )
            val evidenceJar = overlay.root.resolve(".refactorkit-java-evidence.jar")
            createEvidenceJar(output, evidenceJar)
            val outputHash = hashFile(evidenceJar)
            try {
                consumer(evidenceJar)
            } catch (_: Exception) {
                return refused("jvm.javaCompilationConsumerFailed", "Ephemeral Java class consumer failed")
            }
            if (hashOutput(output) != classesHash || hashFile(evidenceJar) != outputHash ||
                overlay.verifySourcesUnchanged().isNotEmpty()) return refused(
                "jvm.javaCompilationEvidenceMutated", "Ephemeral Java class evidence changed during consumption",
            )
            JavaEphemeralCompilationResult.Available(outputHash)
        } catch (_: Exception) {
            refused("jvm.javaCompilationFailed", "Ephemeral Java compilation failed without publishing partial evidence")
        } finally {
            overlay.close()
        }
    }

    private fun createEvidenceJar(classes: Path, target: Path) {
        JarOutputStream(Files.newOutputStream(target)).use { output ->
            Files.walk(classes).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
                    require(!Files.isSymbolicLink(path) && path.fileName.toString().endsWith(".class"))
                    val entry = JarEntry(classes.relativize(path).toString().replace('\\', '/')).apply { time = 0L }
                    output.putNextEntry(entry)
                    Files.copy(path, output)
                    output.closeEntry()
                }
            }
        }
    }

    private fun hashFile(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashOutput(root: Path): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var files = 0
        var bytes = 0L
        Files.walk(root).use { stream ->
            stream.filter { it != root }.sorted().forEach { path ->
                require(!Files.isSymbolicLink(path))
                if (Files.isDirectory(path)) return@forEach
                require(Files.isRegularFile(path) && path.fileName.toString().endsWith(".class"))
                files++
                bytes += Files.size(path)
                require(files <= MAX_CLASSES && bytes <= MAX_CLASS_BYTES)
                digest.update(root.relativize(path).toString().replace('\\', '/').toByteArray(Charsets.UTF_8))
                Files.newInputStream(path).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
        }
        require(files > 0)
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun refused(code: String, message: String) = JavaEphemeralCompilationResult.Refused(code, message)
    private fun isWindows() = System.getProperty("os.name").startsWith("Windows")

    companion object {
        private const val MAX_SOURCES = 256
        private const val MAX_CLASSES = 10_000
        private const val MAX_CLASS_BYTES = 128L * 1024L * 1024L
        private const val MAX_OUTPUT_BYTES = 2L * 1024L * 1024L
        private val SEQUENCE = AtomicLong()
    }
}

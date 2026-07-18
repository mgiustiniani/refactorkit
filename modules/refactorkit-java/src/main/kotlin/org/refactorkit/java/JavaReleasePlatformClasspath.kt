package org.refactorkit.java

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Disposable classpath projection of one attested `ct.sym` release. */
internal class JavaReleasePlatformClasspath private constructor(
    val path: Path,
    private val disposable: Boolean,
) : AutoCloseable {
    override fun close() {
        if (disposable && Files.exists(path)) Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private const val MAXIMUM_ENTRIES = 100_000
        private const val MAXIMUM_UNCOMPRESSED_BYTES = 512L * 1024L * 1024L

        fun materialize(authority: JavaReleasePlatformAuthority): JavaReleasePlatformClasspath {
            if (authority.release == authority.platformRelease) {
                check(authority.currentSystemModules != null && authority.currentSystemModulesSha256 != null) {
                    "Current-release system module evidence is unavailable"
                }
                return JavaReleasePlatformClasspath(authority.platformHome.resolve("lib/jrt-fs.jar"), false)
            }
            val target = Files.createTempDirectory("refactorkit-java-platform-${authority.release}-")
            try {
                val token = releaseToken(authority.release)
                val names = hashSetOf<String>()
                var count = 0
                var totalBytes = 0L
                ZipFile(authority.signatureArchive.toFile()).use { source ->
                    val entries = source.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val parts = entry.name.split('/')
                        if (entry.isDirectory || parts.size < 3 || token !in parts[0] || !entry.name.endsWith(".sig")) continue
                        val relative = parts.drop(2).joinToString("/").removeSuffix(".sig") + ".class"
                        if (relative == "module-info.class") continue
                        check(names.add(relative)) { "Duplicate Java platform signature: $relative" }
                        check(++count <= MAXIMUM_ENTRIES) { "Java platform signature entry limit exceeded" }
                        check(entry.size >= 0) { "Java platform signature has unknown size: ${entry.name}" }
                        check(totalBytes + entry.size <= MAXIMUM_UNCOMPRESSED_BYTES) {
                            "Java platform signature byte limit exceeded"
                        }
                        val destination = target.resolve(relative).normalize()
                        check(destination.startsWith(target)) { "Java platform signature path escapes projection" }
                        Files.createDirectories(requireNotNull(destination.parent))
                        Files.newOutputStream(destination).use { output ->
                            source.getInputStream(entry).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    totalBytes += read
                                    check(totalBytes <= MAXIMUM_UNCOMPRESSED_BYTES) {
                                        "Java platform signature byte limit exceeded"
                                    }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                }
                check(count > 0) { "Java platform signatures are empty" }
                return JavaReleasePlatformClasspath(target, true)
            } catch (failure: Exception) {
                Files.walk(target).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
                throw failure
            }
        }

        private fun releaseToken(release: Int): Char = when (release) {
            8, 9 -> ('0'.code + release).toChar()
            else -> ('A'.code + release - 10).toChar()
        }
    }
}

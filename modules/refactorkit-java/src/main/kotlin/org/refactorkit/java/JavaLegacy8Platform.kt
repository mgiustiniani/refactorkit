package org.refactorkit.java

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipFile

/** The OpenJDK 8 symbol JAR predates the modular release layout used by JEP 247. */
internal object JavaLegacy8Platform {
    const val RELEASE = 8
    private const val PREFIX = "META-INF/sym/rt.jar/"
    private const val ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val ENTRY_BYTES = 1024 * 1024
    private const val ENTRY_LIMIT = 250_000
    private val classHeader = byteArrayOf(0xca.toByte(), 0xfe.toByte(), 0xba.toByte(), 0xbe.toByte())

    fun supplementalArchives(home: Path): List<Path> = listOf("jsse.jar", "jce.jar").map { name ->
        home.resolve("jre/lib").resolve(name)
    }

    fun safeInput(home: Path, path: Path): Boolean =
        Files.isRegularFile(path, NOFOLLOW_LINKS) && path.toRealPath() == path &&
            path.startsWith(home) && Files.size(path) in 1..ARCHIVE_BYTES.toLong()

    fun hasPlatformSignatures(archive: ZipFile): Boolean =
        listOf("java/lang/Object.class", "java/lang/String.class", "java/time/LocalDate.class").all { name ->
            val entry = archive.getEntry(PREFIX + name) ?: return@all false
            val bytes = archive.getInputStream(entry).use { it.readNBytes(8) }
            bytes.size == 8 && bytes.copyOfRange(0, 4).contentEquals(classHeader) &&
                bytes[6].toInt() == 0 && bytes[7].toInt() == 52
        }

    /** Projects only class signatures from a private copy of the captured archive. */
    fun materialize(authority: JavaReleasePlatformAuthority): Path {
        check(safeInput(authority.platformHome, authority.signatureArchive)) { "Attested Java 8 signatures are unavailable" }
        val bytes = Files.newInputStream(authority.signatureArchive, NOFOLLOW_LINKS).use { it.readNBytes(ARCHIVE_BYTES + 1) }
        check(bytes.size <= ARCHIVE_BYTES && MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } == authority.signatureArchiveSha256) {
            "Attested Java 8 signatures changed before projection"
        }
        val captured = Files.createTempFile("refactorkit-java8-symbols-", ".zip")
        try {
            Files.write(captured, bytes)
            val projected = Files.createTempFile("refactorkit-java8-platform-", ".jar")
            try {
                ZipFile(captured.toFile()).use { archive ->
                    check(archive.size() <= ENTRY_LIMIT && hasPlatformSignatures(archive)) { "Invalid Java 8 signature inventory" }
                    JarOutputStream(Files.newOutputStream(projected)).use { jar ->
                        jar.setLevel(Deflater.NO_COMPRESSION)
                        var total = 0L
                        archive.entries().asSequence().filter { !it.isDirectory && it.name.startsWith(PREFIX) }.forEach { entry ->
                            val name = entry.name.removePrefix(PREFIX)
                            check(name.endsWith(".class") && '\\' !in name &&
                                name.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                                "Invalid Java 8 signature entry"
                            }
                            val content = archive.getInputStream(entry).use { it.readNBytes(ENTRY_BYTES + 1) }
                            total += content.size
                            check(content.size in 8..ENTRY_BYTES && total <= ARCHIVE_BYTES) {
                                "Java 8 signature projection exceeds its byte bound"
                            }
                            jar.putNextEntry(JarEntry(name))
                            jar.write(content)
                            jar.closeEntry()
                        }
                    }
                }
                return projected
            } catch (failure: Throwable) {
                Files.deleteIfExists(projected)
                throw failure
            }
        } finally {
            Files.deleteIfExists(captured)
        }
    }
}

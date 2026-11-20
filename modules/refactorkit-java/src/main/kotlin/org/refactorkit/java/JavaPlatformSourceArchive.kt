package org.refactorkit.java

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.refactorkit.core.SemanticCancellationToken

internal class JavaPlatformSourceFailure(val code: String, message: String) : RuntimeException(message)
internal fun platformSourceFailure(code: String, message: String): Nothing = throw JavaPlatformSourceFailure("java.platformSources.$code", message)
internal fun platformSourceHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun platformSourceCancellation(token: SemanticCancellationToken) {
    if (token.isCancellationRequested()) platformSourceFailure("cancelled", "Platform source query was cancelled")
}

internal data class JavaPlatformArchiveEntry(val path: Path, val archiveSha256: String, val sourceSha256: String, val content: String)

/** Independent SDK source budgets. Dependency archive budgets remain unchanged. */
internal class JavaPlatformSourceArchive {
    companion object {
        const val MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
        const val MAX_ENTRIES = 32768
        const val MAX_INFLATED_BYTES = 512L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 512 * 1024
        const val MAX_CLASS_BYTES = 2 * 1024 * 1024
    }
    fun classBytes(platform: JavaReleasePlatformAuthority, binary: JdtJavaPlatformBinary): ByteArray {
        require(platform.release == platform.platformRelease)
        return FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to platform.platformHome.toString())).use { fs ->
            val path = fs.getPath("/modules/${binary.moduleName}/${binary.classEntry}")
            boundedBytes(path, MAX_CLASS_BYTES)
        }
    }

    fun isCurrent(home: Path, source: JavaPlatformArchiveEntry): Boolean = runCatching {
        val standard = home.resolve("lib/src.zip")
        standard.toRealPath() == source.path && platformSourceHash(boundedBytes(source.path, MAX_ARCHIVE_BYTES)) == source.archiveSha256
    }.getOrDefault(false)

    fun source(home: Path, entry: String, cancellation: SemanticCancellationToken): JavaPlatformArchiveEntry? {
        val standard = home.resolve("lib/src.zip")
        if (!Files.isRegularFile(standard)) return null
        val resolved = standard.toRealPath()
        val bytes = boundedBytes(resolved, MAX_ARCHIVE_BYTES)
        // SDK layouts under this contract are standard single-disk ZIP, not ZIP64 or trailing data.
        val central = centralInventory(bytes, cancellation)
        var result: ByteArray? = null
        var inflated = 0L
        val seen = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                platformSourceCancellation(cancellation)
                val item = zip.nextEntry ?: break
                if (!seen.add(item.name)) platformSourceFailure("archiveUnsafe", "SDK source ZIP has duplicate local entries")
                if (seen.size > MAX_ENTRIES) platformSourceFailure("limitExceeded", "SDK source ZIP exceeds its entry budget")
                val selected = item.name == entry
                if (selected && item.isDirectory) platformSourceFailure("archiveUnsafe", "Selected source entry is a directory")
                val output = if (selected) ByteArrayOutputStream() else null
                while (true) {
                    platformSourceCancellation(cancellation)
                    val count = zip.read(buffer)
                    if (count < 0) break
                    inflated += count
                    if (inflated > MAX_INFLATED_BYTES) platformSourceFailure("limitExceeded", "SDK source ZIP exceeds its full inflation budget")
                    if (output != null) {
                        if (output.size() + count > MAX_SOURCE_BYTES) platformSourceFailure("limitExceeded", "Selected SDK source exceeds 512 KiB")
                        output.write(buffer, 0, count)
                    }
                }
                if (output != null) result = output.toByteArray()
                zip.closeEntry()
                val evidence = central[item.name]
                if (evidence == null || item.crc != evidence.crc || item.compressedSize != evidence.compressedSize ||
                    item.size != evidence.size || item.method != evidence.method)
                    platformSourceFailure("archiveUnsafe", "SDK source ZIP central checksum/size/method differs from the decoded local entry")
            }
        }
        if (seen != central.keys) platformSourceFailure("archiveUnsafe", "SDK source ZIP local/central inventories differ")
        val source = result ?: return null
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(source)).toString()
        if (standard.toRealPath() != resolved) platformSourceFailure("associationStale", "SDK source archive symlink target changed during read")
        return JavaPlatformArchiveEntry(resolved, platformSourceHash(bytes), platformSourceHash(source), text)
    }

    private fun boundedBytes(path: Path, maximum: Int): ByteArray {
        if (!Files.isRegularFile(path) || Files.size(path) > maximum) platformSourceFailure("limitExceeded", "Selected platform artifact exceeds its byte budget")
        return Files.newInputStream(path).use { input -> input.readNBytes(maximum + 1).also {
            if (it.size > maximum) platformSourceFailure("limitExceeded", "Selected platform artifact grew beyond its byte budget")
        } }
    }

    private data class CentralEntry(val crc: Long, val compressedSize: Long, val size: Long, val method: Int)
    private fun centralInventory(bytes: ByteArray, cancellation: SemanticCancellationToken): Map<String, CentralEntry> {
        fun u16(offset: Int): Int { if (offset < 0 || offset + 2 > bytes.size) platformSourceFailure("archiveUnsafe", "Truncated SDK ZIP metadata")
            return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) }
        fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: platformSourceFailure("archiveUnsafe", "SDK source ZIP has no bounded end record")
        val count = u16(end + 10)
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != count || count == 65535)
            platformSourceFailure("archiveUnsafe", "Split or ZIP64 SDK source archives are not qualified")
        if (count > MAX_ENTRIES) platformSourceFailure("limitExceeded", "SDK source ZIP exceeds its entry budget")
        val start = u32(end + 16); val size = u32(end + 12)
        if (start + size != end.toLong()) platformSourceFailure("archiveUnsafe", "SDK source ZIP central directory is inconsistent")
        var offset = start.toInt(); var inflated = 0L; val entries = LinkedHashMap<String, CentralEntry>(); val localOffsets = HashSet<Long>()
        repeat(count) {
            platformSourceCancellation(cancellation)
            if (u32(offset) != 0x02014b50L) platformSourceFailure("archiveUnsafe", "Malformed SDK source ZIP central entry")
            val nameSize = u16(offset + 28); val next = offset.toLong() + 46 + nameSize + u16(offset + 30) + u16(offset + 32)
            if (next > end || u16(offset + 34) != 0 || u16(offset + 8) and 1 != 0 || u32(offset + 42) >= start)
                platformSourceFailure("archiveUnsafe", "SDK source ZIP entry bounds/encryption/disk are unsupported")
            inflated += u32(offset + 24)
            if (inflated > MAX_INFLATED_BYTES) platformSourceFailure("limitExceeded", "SDK source ZIP declared inflation exceeds 512 MiB")
            val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset + 46, nameSize)).toString()
            if (name.isEmpty() || name.startsWith('/') || '\\' in name || ':' in name || '\u0000' in name ||
                name.trimEnd('/').split('/').any { it.isEmpty() || it == "." || it == ".." } || name in entries)
                platformSourceFailure("archiveUnsafe", "SDK source ZIP has unsafe or duplicate central entries")
            val local = u32(offset + 42)
            if (!localOffsets.add(local) || u32(local.toInt()) != 0x04034b50L || u16(local.toInt() + 6) != u16(offset + 8) ||
                u16(local.toInt() + 8) != u16(offset + 10) || u16(local.toInt() + 26) != nameSize ||
                local + 30 + nameSize + u16(local.toInt() + 28) + u32(offset + 20) > start ||
                !bytes.copyOfRange(local.toInt() + 30, local.toInt() + 30 + nameSize).contentEquals(bytes.copyOfRange(offset + 46, offset + 46 + nameSize)))
                platformSourceFailure("archiveUnsafe", "SDK source ZIP local header differs from central module/source evidence")
            entries[name] = CentralEntry(u32(offset + 16), u32(offset + 20), u32(offset + 24), u16(offset + 10))
            offset = next.toInt()
        }
        if (offset != end) platformSourceFailure("archiveUnsafe", "SDK source ZIP central directory has unqualified trailing records")
        return entries
    }
}

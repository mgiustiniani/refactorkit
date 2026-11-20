package org.refactorkit.c

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections

data class CGlibcCacheEntry(val name: String, val path: String, val flags: Int, val osVersion: Long, val hwcap: Long)
data class CGlibcCacheExtension(val tag: Long, val flags: Long, val offset: Long, val size: Long, val sha256: String)

enum class CGlibcCacheRefusal { INVALID_CACHE, UNSUPPORTED_PROFILE, INPUT_LIMIT }

sealed interface CGlibcCacheReadout {
    data class Found(val facts: CGlibcLoaderCacheFacts) : CGlibcCacheReadout
    data class Refused(val reason: CGlibcCacheRefusal) : CGlibcCacheReadout
}

sealed interface CGlibcCacheLookup {
    data class Candidate(val path: String) : CGlibcCacheLookup
    data object Absent : CGlibcCacheLookup
    data class Ambiguous(val paths: List<String>) : CGlibcCacheLookup
    data object UnsupportedSelection : CGlibcCacheLookup
    data object InvalidName : CGlibcCacheLookup
}

/** Supplied metadata only; a candidate is not an inspected file or actual loader selection. */
class CGlibcLoaderCacheFacts internal constructor(val sha256: String, val headerFlags: Int,
    entries: List<CGlibcCacheEntry>, extensions: List<CGlibcCacheExtension>) {
    val entries: List<CGlibcCacheEntry> = Collections.unmodifiableList(ArrayList(entries))
    val extensions: List<CGlibcCacheExtension> = Collections.unmodifiableList(ArrayList(extensions))

    fun nativeCandidate(name: String): CGlibcCacheLookup {
        if (!literalName(name)) return CGlibcCacheLookup.InvalidName
        if (extensions.any { it.tag !in 0L..1L || it.flags != 0L }) return CGlibcCacheLookup.UnsupportedSelection
        val matching = entries.filter { it.name == name && it.flags == X86_64_LIBC6 }
        if (matching.any { it.osVersion != 0L || it.hwcap != 0L }) return CGlibcCacheLookup.UnsupportedSelection
        val paths = matching.map { it.path }.distinct()
        return when (paths.size) {
            0 -> CGlibcCacheLookup.Absent
            1 -> CGlibcCacheLookup.Candidate(paths.single())
            else -> CGlibcCacheLookup.Ambiguous(Collections.unmodifiableList(ArrayList(paths)))
        }
    }

    private fun literalName(value: String): Boolean {
        if (value.isEmpty() || value.length > 4096 || value in setOf(".", "..") ||
            value.any { it.isISOControl() || it == '/' || it == '\\' || it == '$' }) return false
        return runCatching { Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value)).remaining() <= 4096 }.getOrDefault(false)
    }

    companion object { private const val X86_64_LIBC6 = 0x303 }
}

internal data class CGlibcCacheLimits(val maxBytes: Int = 8 * 1024 * 1024, val maxEntries: Int = 16384,
    val maxStringBytes: Int = 4096, val maxExtensions: Int = 64) {
    init { require(maxBytes in 48..8 * 1024 * 1024 && maxEntries in 1..16384 && maxStringBytes in 1..4096 && maxExtensions in 1..64) }
}

/** Locally authored bounded decoder, not a cache generator or executable resolver. */
object CGlibcLoaderCache {
    fun read(source: ByteArray): CGlibcCacheReadout = read(source, CGlibcCacheLimits())

    internal fun read(source: ByteArray, limits: CGlibcCacheLimits): CGlibcCacheReadout = try {
        if (source.size > limits.maxBytes) refuse(CGlibcCacheRefusal.INPUT_LIMIT)
        CGlibcCacheReadout.Found(Decoder(source.copyOf(), limits).decode())
    } catch (failure: Refused) {
        CGlibcCacheReadout.Refused(failure.reason)
    } catch (_: IllegalArgumentException) {
        CGlibcCacheReadout.Refused(CGlibcCacheRefusal.INVALID_CACHE)
    } catch (_: java.nio.charset.CharacterCodingException) {
        CGlibcCacheReadout.Refused(CGlibcCacheRefusal.INVALID_CACHE)
    }

    private class Decoder(private val bytes: ByteArray, private val limits: CGlibcCacheLimits) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private fun invalid(): Nothing = refuse(CGlibcCacheRefusal.INVALID_CACHE)
        private fun u32(offset: Int) = input.getInt(offset).toLong() and 0xffffffffL
        private fun range(offset: Long, size: Long): Int {
            if (offset < 0 || size < 0 || offset > bytes.size || size > bytes.size - offset) invalid()
            return offset.toInt()
        }

        fun decode(): CGlibcLoaderCacheFacts {
            if (bytes.size < 48) invalid()
            if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) refuse(CGlibcCacheRefusal.UNSUPPORTED_PROFILE)
            val flags = bytes[28].toInt() and 0xff
            if (flags != 0 && flags and 3 != 2) refuse(CGlibcCacheRefusal.UNSUPPORTED_PROFILE)
            val count = u32(20)
            if (count > limits.maxEntries) refuse(CGlibcCacheRefusal.INPUT_LIMIT)
            val strings = 48L + count * 24
            val stringBytes = u32(24)
            if (stringBytes > limits.maxBytes) refuse(CGlibcCacheRefusal.INPUT_LIMIT)
            range(48, count * 24); range(strings, stringBytes)
            val endStrings = strings + stringBytes
            fun text(offset: Long, name: Boolean): String {
                if (offset < strings || offset >= endStrings) invalid()
                val start = offset.toInt(); val available = endStrings - offset
                var end = start
                val bound = minOf(available, limits.maxStringBytes.toLong() + 1).toInt()
                while (end < start + bound && bytes[end] != 0.toByte()) end++
                if (end == start + bound) {
                    if (available > limits.maxStringBytes) refuse(CGlibcCacheRefusal.INPUT_LIMIT)
                    invalid()
                }
                if (end == start) invalid()
                val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, start, end - start)).toString()
                if (value.any(Char::isISOControl) || '$' in value) invalid()
                if (name) {
                    if (value in setOf(".", "..") || '/' in value || '\\' in value) invalid()
                } else {
                    val path = Path.of(value)
                    if (!path.isAbsolute || path.fileName == null) invalid()
                }
                return value
            }
            val entries = (0 until count.toInt()).map { index ->
                val offset = 48 + index * 24
                CGlibcCacheEntry(text(u32(offset + 4), true), text(u32(offset + 8), false),
                    input.getInt(offset), u32(offset + 12), input.getLong(offset + 16))
            }
            val extensionOffset = u32(32)
            val extensions = if (extensionOffset == 0L) emptyList() else {
                if (extensionOffset % 4 != 0L || extensionOffset < endStrings) invalid()
                val start = range(extensionOffset, 8)
                if (u32(start) != EXTENSION_MAGIC) invalid()
                val extensionCount = u32(start + 4)
                if (extensionCount > limits.maxExtensions) refuse(CGlibcCacheRefusal.INPUT_LIMIT)
                val directoryEnd = extensionOffset + 8 + extensionCount * 16
                range(extensionOffset, directoryEnd - extensionOffset)
                val ranges = mutableListOf<Pair<Long, Long>>()
                val tags = mutableSetOf<Long>()
                (0 until extensionCount.toInt()).map { index ->
                    val offset = start + 8 + index * 16
                    val tag = u32(offset); val sectionFlags = u32(offset + 4); val dataOffset = u32(offset + 8); val size = u32(offset + 12)
                    val data = range(dataOffset, size)
                    if (dataOffset < directoryEnd || !tags.add(tag) || ranges.any { dataOffset < it.second && it.first < dataOffset + size }) invalid()
                    if (tag == 1L && (size == 0L || size % 4 != 0L || dataOffset % 4 != 0L)) invalid()
                    ranges += dataOffset to dataOffset + size
                    CGlibcCacheExtension(tag, sectionFlags, dataOffset, size, hash(bytes.copyOfRange(data, data + size.toInt())))
                }
            }
            return CGlibcLoaderCacheFacts(hash(bytes), flags, entries, extensions)
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private class Refused(val reason: CGlibcCacheRefusal) : RuntimeException()
    private fun refuse(reason: CGlibcCacheRefusal): Nothing = throw Refused(reason)
    private val MAGIC = "glibc-ld.so.cache1.1".toByteArray(Charsets.US_ASCII)
    private const val EXTENSION_MAGIC = 0xeaa42174L
}

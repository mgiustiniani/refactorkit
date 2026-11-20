package org.refactorkit.c

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

enum class CElfObjectType { EXECUTABLE, DYNAMIC }

data class CElfDynamicInputFacts(
    val sha256: String,
    val interpreter: String?,
    val needed: List<String>,
    val soname: String?,
    val rpath: String?,
    val runpath: String?,
    val dynamicFlags: Long,
    val dynamicFlags1: Long,
    val hasDynamicSegment: Boolean,
    val objectType: CElfObjectType,
)

internal enum class CElfInputRefusal {
    INVALID_ELF, UNSUPPORTED_PROFILE, INPUT_LIMIT, UNSUPPORTED_LOADER_DIRECTIVE,
}

internal sealed interface CElfDynamicInputReadout {
    data class Found(val facts: CElfDynamicInputFacts) : CElfDynamicInputReadout
    data class Refused(val reason: CElfInputRefusal) : CElfDynamicInputReadout
}

internal data class CElfInputLimits(
    val maxFileBytes: Int = 512 * 1024 * 1024,
    val maxProgramHeaders: Int = 128,
    val maxDynamicEntries: Int = 4096,
    val maxNeeded: Int = 512,
    val maxStringTableBytes: Int = 32 * 1024 * 1024,
    val maxStringBytes: Int = 4096,
) {
    init {
        require(maxFileBytes in 64..512 * 1024 * 1024 && maxProgramHeaders in 1..128 &&
            maxDynamicEntries in 1..4096 && maxNeeded in 1..512 &&
            maxStringTableBytes in 1..32 * 1024 * 1024 && maxStringBytes in 1..4096)
    }
}

/** Pure declarations only; native artifact capture and actual loader resolution are separate. */
internal object CElfDynamicInputs {
    fun read(source: ByteArray, limits: CElfInputLimits = CElfInputLimits()): CElfDynamicInputReadout {
        if (source.size > limits.maxFileBytes) return CElfDynamicInputReadout.Refused(CElfInputRefusal.INPUT_LIMIT)
        return readOwned(source.copyOf(), limits)
    }

    /** Decodes a privately acquired buffer. The caller must keep it unshared until the read finishes. */
    fun readOwned(source: ByteArray, limits: CElfInputLimits): CElfDynamicInputReadout = try {
        if (source.size > limits.maxFileBytes) reject(CElfInputRefusal.INPUT_LIMIT)
        CElfDynamicInputReadout.Found(Decoder(source, limits).decode())
    } catch (failure: Rejected) { CElfDynamicInputReadout.Refused(failure.reason) }
      catch (_: IllegalArgumentException) { CElfDynamicInputReadout.Refused(CElfInputRefusal.INVALID_ELF) }

    private class Decoder(private val bytes: ByteArray, private val limits: CElfInputLimits) {
        private val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private fun invalid(): Nothing = reject(CElfInputRefusal.INVALID_ELF)
        private fun u16(offset: Int) = input.getShort(offset).toInt() and 0xffff
        private fun u32(offset: Int) = input.getInt(offset).toLong() and 0xffffffffL
        private fun bounded64(offset: Int): Long = input.getLong(offset).also { if (it < 0) invalid() }
        private fun range(offset: Long, length: Long): Int {
            if (offset < 0 || length < 0 || offset > bytes.size || length > bytes.size - offset) invalid()
            return offset.toInt()
        }

        fun decode(): CElfDynamicInputFacts {
            if (bytes.size < 64 || bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte() ||
                bytes[2] != 'L'.code.toByte() || bytes[3] != 'F'.code.toByte()) invalid()
            if (bytes[4] != 2.toByte() || bytes[5] != 1.toByte() || bytes[7].toInt() !in setOf(0, 3) ||
                bytes[8] != 0.toByte() || u16(16) !in setOf(2, 3) || u16(18) != 62 || u32(48) != 0L) {
                reject(CElfInputRefusal.UNSUPPORTED_PROFILE)
            }
            if (bytes[6] != 1.toByte() || u32(20) != 1L || u16(52) != 64 || u16(54) != 56) invalid()
            val count = u16(56)
            if (count > limits.maxProgramHeaders) reject(CElfInputRefusal.INPUT_LIMIT)
            val table = bounded64(32)
            if (count == 0 || table < 64) invalid()
            val start = range(table, count.toLong() * 56)
            val segments = (0 until count).map { index ->
                val offset = start + index * 56
                val type = u32(offset)
                if (type == 5L) reject(CElfInputRefusal.UNSUPPORTED_PROFILE)
                if (type in setOf(1L, 2L, 3L)) {
                    Segment(type, bounded64(offset + 8), bounded64(offset + 16), bounded64(offset + 32)).also {
                        range(it.offset, it.size)
                        if (type == 1L && it.size > bounded64(offset + 40)) invalid()
                    }
                } else Segment(type, 0, 0, 0)
            }
            val loads = segments.filter { it.type == 1L }
            if (loads.isEmpty()) invalid()
            fun mapped(address: Long, size: Long): Int {
                val load = loads.singleOrNull { size <= it.size && address >= it.address && address - it.address <= it.size - size }
                    ?: invalid()
                return range(load.offset + (address - load.address), size)
            }
            val interpreters = segments.filter { it.type == 3L }
            val dynamics = segments.filter { it.type == 2L }
            if (interpreters.size > 1 || dynamics.size > 1) invalid()
            val interpreter = interpreters.singleOrNull()?.let { string(range(it.offset, it.size), it.size, false) }
            val dynamic = dynamics.singleOrNull()
            if (dynamic == null) {
                if (interpreter != null) invalid()
                return facts(null, emptyList(), null, null, null, 0, 0, false)
            }
            if (dynamic.size == 0L || dynamic.size % 16 != 0L || mapped(dynamic.address, dynamic.size).toLong() != dynamic.offset) invalid()
            val entryCount = dynamic.size / 16
            if (entryCount > limits.maxDynamicEntries) reject(CElfInputRefusal.INPUT_LIMIT)
            val metadata = linkedMapOf<Long, Long>()
            val needed = mutableListOf<Long>()
            var terminated = false
            for (index in 0 until entryCount.toInt()) {
                val offset = dynamic.offset.toInt() + index * 16
                val tag = input.getLong(offset)
                if (tag == 0L) { terminated = true; break }
                if (tag in HIDDEN_LOADER_INPUTS) reject(CElfInputRefusal.UNSUPPORTED_LOADER_DIRECTIVE)
                if (tag == 1L) {
                    needed += bounded64(offset + 8)
                    if (needed.size > limits.maxNeeded) reject(CElfInputRefusal.INPUT_LIMIT)
                } else if (tag in SINGLETON_TAGS) {
                    if (metadata.put(tag, bounded64(offset + 8)) != null) invalid()
                }
            }
            if (!terminated) invalid()
            val stringAddress = metadata[5L] ?: invalid()
            val stringSize = metadata[10L] ?: invalid()
            if (stringSize > limits.maxStringTableBytes) reject(CElfInputRefusal.INPUT_LIMIT)
            if (stringSize == 0L) invalid()
            val strings = mapped(stringAddress, stringSize)
            if (bytes[strings] != 0.toByte()) invalid()
            fun declaration(index: Long, allowEmpty: Boolean): String {
                if (index >= stringSize) invalid()
                return string(strings + index.toInt(), stringSize - index, allowEmpty)
            }
            return facts(interpreter, needed.map { declaration(it, false) },
                metadata[14L]?.let { declaration(it, false) }, metadata[15L]?.let { declaration(it, true) },
                metadata[29L]?.let { declaration(it, true) }, metadata[30L] ?: 0, metadata[FLAGS_1] ?: 0, true)
        }

        private fun string(start: Int, available: Long, allowEmpty: Boolean): String {
            range(start.toLong(), available)
            val count = minOf(available, limits.maxStringBytes.toLong() + 1).toInt()
            var end = start
            while (end < start + count && bytes[end] != 0.toByte()) end++
            if (end == start + count) {
                if (available > limits.maxStringBytes) reject(CElfInputRefusal.INPUT_LIMIT)
                invalid()
            }
            if (!allowEmpty && end == start) invalid()
            val value = runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, end - start)).toString()
            }.getOrElse { invalid() }
            if (value.any(Char::isISOControl)) invalid()
            return value
        }

        private fun facts(interpreter: String?, needed: List<String>, soname: String?, rpath: String?, runpath: String?,
            flags: Long, flags1: Long, dynamic: Boolean) = CElfDynamicInputFacts(
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, interpreter,
            Collections.unmodifiableList(ArrayList(needed)), soname, rpath, runpath, flags, flags1, dynamic,
            if (u16(16) == 2) CElfObjectType.EXECUTABLE else CElfObjectType.DYNAMIC,
        )
    }

    private data class Segment(val type: Long, val offset: Long, val address: Long, val size: Long)
    private class Rejected(val reason: CElfInputRefusal) : IllegalArgumentException()
    private fun reject(reason: CElfInputRefusal): Nothing = throw Rejected(reason)
    private const val FLAGS_1 = 0x6ffffffbL
    private val SINGLETON_TAGS = setOf(5L, 10L, 14L, 15L, 29L, 30L, FLAGS_1)
    private val HIDDEN_LOADER_INPUTS = setOf(0x6ffffefaL, 0x6ffffefbL, 0x6ffffefcL, 0x7ffffffdL, 0x7fffffffL)
}

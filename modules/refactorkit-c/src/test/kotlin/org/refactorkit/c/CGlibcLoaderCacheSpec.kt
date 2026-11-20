package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** REQ-C-GLIBC-CACHE-001; own in-memory byte fixtures, no filesystem/cache generator/native execution. */
class CGlibcLoaderCacheSpec : StringSpec({
    fun found(bytes: ByteArray) = CGlibcLoaderCache.read(bytes).shouldBeInstanceOf<CGlibcCacheReadout.Found>().facts
    fun refused(bytes: ByteArray, reason: CGlibcCacheRefusal, limits: CGlibcCacheLimits = CGlibcCacheLimits()) {
        CGlibcLoaderCache.read(bytes, limits) shouldBe CGlibcCacheReadout.Refused(reason)
    }

    "native foreign and ordered duplicate records retain byte identity and extension data" {
        val facts = found(cacheFixture())
        facts.sha256 shouldBe "29317a6a84bcbd53e2dca6b949aeba582880364bcc166f068ff4dc9faee2c6d7"
        facts.headerFlags shouldBe 2
        facts.entries shouldBe cacheRecords()
        val extension = facts.extensions.single()
        extension.tag shouldBe 0
        extension.flags shouldBe 0
        extension.size shouldBe "own-cache-writer".toByteArray().size.toLong()
        extension.sha256.length shouldBe 64
        found(cacheFixture(cacheRecords() + cacheRecords().first())).entries shouldBe cacheRecords() + cacheRecords().first()
        found(cacheFixture(emptyList(), emptyList())).entries shouldBe emptyList()
    }
    "native lookup distinguishes unique absent duplicate identical and ambiguous paths" {
        val facts = found(cacheFixture())
        facts.nativeCandidate("libfixture.so.1") shouldBe CGlibcCacheLookup.Candidate("/usr/lib/libfixture.so.1")
        facts.nativeCandidate("missing.so") shouldBe CGlibcCacheLookup.Absent
        found(cacheFixture(listOf(cacheRecords()[1]))).nativeCandidate("libfixture.so.1") shouldBe CGlibcCacheLookup.Absent
        found(cacheFixture(cacheRecords() + cacheRecords().first())).nativeCandidate("libfixture.so.1") shouldBe
            CGlibcCacheLookup.Candidate("/usr/lib/libfixture.so.1")
        val alternative = cacheRecords().first().copy(path = "/opt/libfixture.so.1")
        val ambiguous = found(cacheFixture(cacheRecords() + alternative)).nativeCandidate("libfixture.so.1")
            .shouldBeInstanceOf<CGlibcCacheLookup.Ambiguous>()
        ambiguous.paths shouldBe listOf("/usr/lib/libfixture.so.1", "/opt/libfixture.so.1")
        shouldThrow<UnsupportedOperationException> { (ambiguous.paths as MutableList).clear() }
    }
    "native hardware OS and unknown extension qualifiers refuse selection instead of choosing a base" {
        for (changed in listOf(cacheRecords().first().copy(osVersion = 1), cacheRecords().first().copy(hwcap = 1),
            cacheRecords().first().copy(hwcap = 1L shl 62), cacheRecords().first().copy(hwcap = Long.MIN_VALUE))) {
            found(cacheFixture(cacheRecords() + changed)).nativeCandidate("libfixture.so.1") shouldBe CGlibcCacheLookup.UnsupportedSelection
        }
        found(cacheFixture(listOf(cacheRecords()[1].copy(hwcap = 1)) + cacheRecords().first()))
            .nativeCandidate("libfixture.so.1") shouldBe CGlibcCacheLookup.Candidate("/usr/lib/libfixture.so.1")
        found(cacheFixture(extensions = listOf(8L to byteArrayOf(1)))).nativeCandidate("libfixture.so.1") shouldBe
            CGlibcCacheLookup.UnsupportedSelection
        found(cacheFixture().apply { littleCache(this).putInt(extensionStart(this) + 12, 1) })
            .nativeCandidate("libfixture.so.1") shouldBe CGlibcCacheLookup.UnsupportedSelection
    }
    "truncation overflow string extents and malformed or overlapping extensions refuse" {
        refused(cacheFixture().copyOf(30), CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(24, 8 * 1024 * 1024) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(52, 47) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(56, Int.MAX_VALUE) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { this[littleCache(this).getInt(52)] = 0 }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { this[littleCache(this).getInt(52)] = 0xff.toByte() }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(32, extensionStart(this) + 1) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(extensionStart(this), 0) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(extensionStart(this) + 16, 48) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture().apply { littleCache(this).putInt(extensionStart(this) + 20, Int.MAX_VALUE) }, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture(extensions = listOf(0L to byteArrayOf(1), 0L to byteArrayOf(2))), CGlibcCacheRefusal.INVALID_CACHE)
        val overlapping = cacheFixture(extensions = listOf(0L to byteArrayOf(1), 8L to byteArrayOf(2)))
        littleCache(overlapping).putInt(extensionStart(overlapping) + 32, littleCache(overlapping).getInt(extensionStart(overlapping) + 16))
        refused(overlapping, CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture(extensions = listOf(1L to byteArrayOf(1))), CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture(cacheRecords().map { it.copy(path = "relative/lib.so") }), CGlibcCacheRefusal.INVALID_CACHE)
        refused(cacheFixture(cacheRecords().map { it.copy(name = "${'$'}ORIGIN/lib.so") }), CGlibcCacheRefusal.INVALID_CACHE)
    }
    "byte entry string extension budgets and unsupported format or endianness stay explicit" {
        val bytes = cacheFixture()
        refused(bytes, CGlibcCacheRefusal.INPUT_LIMIT, CGlibcCacheLimits(maxBytes = bytes.size - 1))
        refused(bytes, CGlibcCacheRefusal.INPUT_LIMIT, CGlibcCacheLimits(maxEntries = 2))
        refused(bytes, CGlibcCacheRefusal.INPUT_LIMIT, CGlibcCacheLimits(maxStringBytes = 4))
        found(cacheFixture(List(16384) { cacheRecords().first() })).entries.size shouldBe 16384
        refused(cacheFixture(List(16385) { cacheRecords().first() }), CGlibcCacheRefusal.INPUT_LIMIT)
        refused(cacheFixture().apply { littleCache(this).putInt(20, -1) }, CGlibcCacheRefusal.INPUT_LIMIT)
        found(cacheFixture(listOf(cacheRecords().first().copy(path = "/" + "x".repeat(4095)))))
        refused(cacheFixture(listOf(cacheRecords().first().copy(path = "/" + "x".repeat(4096)))), CGlibcCacheRefusal.INPUT_LIMIT)
        found(cacheFixture(extensions = (0L until 64L).map { it to byteArrayOf(1, 0, 0, 0) })).extensions.size shouldBe 64
        refused(cacheFixture(extensions = (0L until 65L).map { it to byteArrayOf(1) }), CGlibcCacheRefusal.INPUT_LIMIT)
        refused(cacheFixture().apply { this[0] = 0 }, CGlibcCacheRefusal.UNSUPPORTED_PROFILE)
        refused(cacheFixture().apply { this[18] = '2'.code.toByte() }, CGlibcCacheRefusal.UNSUPPORTED_PROFILE)
        for (flag in listOf(1, 3, 0x80)) refused(cacheFixture().apply { this[28] = flag.toByte() }, CGlibcCacheRefusal.UNSUPPORTED_PROFILE)
        found(cacheFixture().apply { this[28] = 0 }).headerFlags shouldBe 0
        found(cacheFixture().apply { this[28] = 0x82.toByte() }).headerFlags shouldBe 0x82
    }
    "snapshots preserve original bytes strict lookup text and immutable record collections" {
        val bytes = cacheFixture(); val original = found(bytes)
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 1).toByte()
        val changed = found(bytes)
        changed.sha256 shouldNotBe original.sha256
        changed.extensions.single().sha256 shouldNotBe original.extensions.single().sha256
        original.entries shouldBe cacheRecords()
        shouldThrow<UnsupportedOperationException> { (original.entries as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (original.extensions as MutableList).clear() }
        for (name in listOf("", ".", "..", "a/b", "${'$'}ORIGIN", "a\n", "\uD800", "é".repeat(2049))) {
            original.nativeCandidate(name) shouldBe CGlibcCacheLookup.InvalidName
        }
    }
})

private fun cacheRecords() = listOf(
    CGlibcCacheEntry("libfixture.so.1", "/usr/lib/libfixture.so.1", 0x303, 0, 0),
    CGlibcCacheEntry("libfixture.so.1", "/usr/lib32/libfixture.so.1", 3, 0, 0),
    CGlibcCacheEntry("libother.so.1", "/usr/lib/libother.so.1", 0x303, 0, 0))
private fun littleCache(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
private fun extensionStart(bytes: ByteArray) = littleCache(bytes).getInt(32)

/** Own binary cache image, neither generated by ldconfig nor acquired from the host. */
private fun cacheFixture(records: List<CGlibcCacheEntry> = cacheRecords(),
    extensions: List<Pair<Long, ByteArray>> = listOf(0L to "own-cache-writer".toByteArray())): ByteArray {
    val strings = ByteArrayOutputStream(); val base = 48 + records.size * 24
    fun text(value: String): Int { val offset = base + strings.size(); strings.write(value.toByteArray()); strings.write(0); return offset }
    val offsets = records.map { text(it.name) to text(it.path) }
    val extensionOffset = (base + strings.size() + 3) and -4
    val payloadStart = extensionOffset + 8 + extensions.size * 16
    val payloads = mutableListOf<Int>(); var length = payloadStart
    for ((_, data) in extensions) { length = (length + 3) and -4; payloads += length; length += data.size }
    val bytes = ByteArray(if (extensions.isEmpty()) base + strings.size() else length); val out = littleCache(bytes)
    "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes); out.putInt(20, records.size); out.putInt(24, strings.size()); bytes[28] = 2
    if (extensions.isNotEmpty()) out.putInt(32, extensionOffset)
    records.forEachIndexed { i, record ->
        val at = 48 + i * 24; out.putInt(at, record.flags); out.putInt(at + 4, offsets[i].first); out.putInt(at + 8, offsets[i].second)
        out.putInt(at + 12, record.osVersion.toInt()); out.putLong(at + 16, record.hwcap)
    }
    strings.toByteArray().copyInto(bytes, base)
    if (extensions.isNotEmpty()) {
        out.putInt(extensionOffset, 0xeaa42174.toInt()); out.putInt(extensionOffset + 4, extensions.size)
        extensions.forEachIndexed { i, (tag, data) ->
            val at = extensionOffset + 8 + i * 16; out.putInt(at, tag.toInt()); out.putInt(at + 8, payloads[i]); out.putInt(at + 12, data.size)
            data.copyInto(bytes, payloads[i])
        }
    }
    return bytes
}

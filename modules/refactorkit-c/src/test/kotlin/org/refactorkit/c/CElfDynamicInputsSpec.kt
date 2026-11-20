package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** REQ-C-ELF-DYNAMIC-INPUTS-001; own byte fixtures, no execution or filesystem. */
class CElfDynamicInputsSpec : StringSpec({
    fun found(bytes: ByteArray) = CElfDynamicInputs.read(bytes).shouldBeInstanceOf<CElfDynamicInputReadout.Found>().facts
    fun refused(bytes: ByteArray, reason: CElfInputRefusal, limits: CElfInputLimits = CElfInputLimits()) {
        CElfDynamicInputs.read(bytes, limits) shouldBe CElfDynamicInputReadout.Refused(reason)
    }

    "a native executable retains its interpreter and ordered repeated dependencies" {
        val material = elfFixture()
        val facts = found(material)
        facts.objectType shouldBe CElfObjectType.EXECUTABLE
        facts.interpreter shouldBe "/lib64/ld-linux-x86-64.so.2"
        facts.needed shouldBe listOf("libz.so.1", "liba.so.1", "libz.so.1")
        facts.soname shouldBe null
        facts.rpath shouldBe "${'$'}ORIGIN/../lib"
        facts.runpath shouldBe null
        facts.dynamicFlags shouldBe 8L
        facts.dynamicFlags1 shouldBe 1L
        facts.hasDynamicSegment shouldBe true
        facts.sha256.length shouldBe 64
        material[1000] = 1
        facts.sha256 shouldNotBe found(material).sha256
        shouldThrow<UnsupportedOperationException> { (facts.needed as MutableList).add("injected.so") }
    }
    "a shared object retains SONAME and both search declarations without choosing resolution" {
        val facts = found(elfFixture(shared = true, runpath = true))
        facts.objectType shouldBe CElfObjectType.DYNAMIC
        facts.interpreter shouldBe null
        facts.soname shouldBe "libfixture.so.1"
        facts.needed shouldBe listOf("libz.so.1", "liba.so.1", "libz.so.1")
        facts.rpath shouldBe "${'$'}ORIGIN/../lib"
        facts.runpath shouldBe "${'$'}ORIGIN/qualified"
    }
    "a static executable has an explicit empty dynamic result bound to its bytes" {
        val facts = found(elfFixture(static = true))
        facts.objectType shouldBe CElfObjectType.EXECUTABLE
        facts.hasDynamicSegment shouldBe false
        facts.needed shouldBe emptyList()
        facts.interpreter shouldBe null
        facts.soname shouldBe null
        facts.rpath shouldBe null
        facts.runpath shouldBe null
        facts.dynamicFlags shouldBe 0L
        facts.dynamicFlags1 shouldBe 0L
    }
    "unsupported profiles malformed extents and ambiguous virtual mappings refuse" {
        refused(elfFixture().apply { this[4] = 1 }, CElfInputRefusal.UNSUPPORTED_PROFILE)
        refused(elfFixture().apply { this[5] = 2 }, CElfInputRefusal.UNSUPPORTED_PROFILE)
        refused(elfFixture().apply { little(this).putShort(18, 183) }, CElfInputRefusal.UNSUPPORTED_PROFILE)
        refused(elfFixture().copyOf(80), CElfInputRefusal.INVALID_ELF)
        refused(elfFixture().apply { little(this).putLong(32, Long.MAX_VALUE) }, CElfInputRefusal.INVALID_ELF)
        refused(elfFixture().apply { little(this).putLong(512 + 8, 0x700000) }, CElfInputRefusal.INVALID_ELF)
        refused(elfFixture(ambiguous = true), CElfInputRefusal.INVALID_ELF)
    }
    "source tables entries dependencies and strings obey independent bounds" {
        val material = elfFixture()
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxFileBytes = material.size - 1))
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxProgramHeaders = 2))
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxDynamicEntries = 2))
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxNeeded = 2))
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxStringTableBytes = 16))
        refused(material, CElfInputRefusal.INPUT_LIMIT, CElfInputLimits(maxStringBytes = 4))
    }
    "incomplete arrays strings singleton metadata and hidden loader directives refuse" {
        refused(elfFixture(terminated = false), CElfInputRefusal.INVALID_ELF)
        refused(elfFixture(duplicateTable = true), CElfInputRefusal.INVALID_ELF)
        refused(elfFixture().apply { this[320 + "/lib64/ld-linux-x86-64.so.2".length] = 1 }, CElfInputRefusal.INVALID_ELF)
        refused(elfFixture(unterminatedName = true), CElfInputRefusal.INVALID_ELF)
        for (tag in listOf(0x6ffffefaL, 0x6ffffefbL, 0x6ffffefcL, 0x7ffffffdL, 0x7fffffffL)) {
            refused(elfFixture(extraTag = tag), CElfInputRefusal.UNSUPPORTED_LOADER_DIRECTIVE)
        }
    }
})

private fun little(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

/** Minimal own ELF64 metadata image; it is data and is never executed. */
private fun elfFixture(shared: Boolean = false, static: Boolean = false, runpath: Boolean = false,
    ambiguous: Boolean = false, terminated: Boolean = true, duplicateTable: Boolean = false,
    unterminatedName: Boolean = false, extraTag: Long? = null): ByteArray {
    val bytes = ByteArray(1024); val out = little(bytes)
    bytes[0] = 0x7f; bytes[1] = 'E'.code.toByte(); bytes[2] = 'L'.code.toByte(); bytes[3] = 'F'.code.toByte()
    bytes[4] = 2; bytes[5] = 1; bytes[6] = 1
    out.putShort(16, if (shared) 3 else 2); out.putShort(18, 62); out.putInt(20, 1)
    out.putLong(32, 64); out.putShort(52, 64); out.putShort(54, 56)
    val interpreter = "/lib64/ld-linux-x86-64.so.2\u0000".toByteArray()
    val types = if (static) listOf(1) else (if (shared) emptyList() else listOf(3)) + listOf(1, 2) + if (ambiguous) listOf(1) else emptyList()
    out.putShort(56, types.size.toShort())
    val strings = ArrayList<Byte>(); strings += 0
    fun string(value: String): Long {
        val offset = strings.size.toLong(); strings.addAll(value.toByteArray().toList()); strings += 0; return offset
    }
    val z = string("libz.so.1"); val a = string("liba.so.1")
    val rpath = string("${'$'}ORIGIN/../lib"); val soname = string("libfixture.so.1"); val search = string("${'$'}ORIGIN/qualified")
    val needed = if (unterminatedName) string("last.so").also { strings[strings.lastIndex] = 1 } else z
    val entries = mutableListOf(5L to 0x400300L, 10L to strings.size.toLong(), 1L to needed, 1L to a, 1L to z, 15L to rpath, 30L to 8L, 0x6ffffffbL to 1L)
    if (shared) entries += 14L to soname
    if (runpath) entries += 29L to search
    if (duplicateTable) entries += 5L to 0x400300L
    extraTag?.let { entries += it to z }
    if (terminated) entries += 0L to 0L
    types.forEachIndexed { index, type ->
        val position = 64 + index * 56
        val offset = when (type) { 3 -> 320; 2 -> 512; else -> 0 }
        val size = when (type) { 3 -> interpreter.size; 2 -> entries.size * 16; else -> bytes.size }
        out.putInt(position, type); out.putInt(position + 4, 4)
        out.putLong(position + 8, offset.toLong()); out.putLong(position + 16, 0x400000L + offset)
        out.putLong(position + 32, size.toLong()); out.putLong(position + 40, size.toLong()); out.putLong(position + 48, 1)
    }
    interpreter.copyInto(bytes, 320)
    entries.forEachIndexed { index, (tag, value) -> out.putLong(512 + index * 16, tag); out.putLong(520 + index * 16, value) }
    strings.toByteArray().copyInto(bytes, 768)
    return bytes
}

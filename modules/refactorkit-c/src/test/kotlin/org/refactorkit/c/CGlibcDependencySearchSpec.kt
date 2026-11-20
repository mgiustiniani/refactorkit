package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** Pure metadata fixtures; no native process, file access, or production resolver oracle. */
class CGlibcDependencySearchSpec : StringSpec({
    "REQ-C-GLIBC-SEARCH-001 preserves each object's ordered RUNPATH and dependency occurrence" {
        val root = objectInput(runpath = "\$ORIGIN/../lib:/absolute/lib:\${ORIGIN}:\$ORIGIN/../lib", needed = listOf("libanswer.so", "libanswer.so"))
        val proposal = propose(root, index = 1)
        proposal.requester shouldBe Path.of("/sdk/bin/tool")
        proposal.neededIndex shouldBe 1; proposal.neededName shouldBe "libanswer.so"
        proposal.runpathCandidates shouldBe listOf(Path.of("/sdk/bin/../lib/libanswer.so"), Path.of("/absolute/lib/libanswer.so"),
            Path.of("/sdk/bin/libanswer.so"), Path.of("/sdk/bin/../lib/libanswer.so"))
        val child = objectInput(path = Path.of("/sdk/lib/libchild.so"), runpath = "\$ORIGIN")
        propose(child).runpathCandidates shouldBe listOf(Path.of("/sdk/lib/libanswer.so"))
        propose(child.copy(artifact = child.artifact.copy(facts = child.artifact.facts.copy(runpath = null)))).runpathCandidates shouldBe emptyList()
    }

    "REQ-C-GLIBC-SEARCH-001 refuses relative empty unknown or malformed RUNPATH entries" {
        listOf("", ":/lib", "/lib:", "/lib::/other", "relative/lib", "\$LIB", "\${PLATFORM}/lib", "\$ORIGINsuffix",
            "\${ORIGIN}suffix", "\$ORIGIN/\$LIB", "/lib/\$UNKNOWN", "\$UNKNOWN/lib").forEach { path ->
            refused(objectInput(runpath = path)) shouldBe if (path.isEmpty()) CGlibcSearchRefusal.INVALID_INPUT else CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        }
        refused(objectInput(runpath = "/lib\n/other")) shouldBe CGlibcSearchRefusal.INVALID_INPUT
        refused(objectInput(runpath = "/lib/\uD800")) shouldBe CGlibcSearchRefusal.INVALID_INPUT
    }

    "REQ-C-GLIBC-SEARCH-001 retains typed cache fallback after valid RUNPATH candidates" {
        val root = objectInput(runpath = "/first")
        propose(root).cacheFallback shouldBe CGlibcCacheLookup.Candidate("/system/libanswer.so")
        propose(root, cache = cache(emptyList())).cacheFallback shouldBe CGlibcCacheLookup.Absent
        val ambiguous = cache(listOf(Entry("libanswer.so", "/one/libanswer.so"), Entry("libanswer.so", "/two/libanswer.so")))
        val selected = propose(root, cache = ambiguous)
        selected.runpathCandidates shouldBe listOf(Path.of("/first/libanswer.so"))
        selected.cacheFallback shouldBe CGlibcCacheLookup.Ambiguous(listOf("/one/libanswer.so", "/two/libanswer.so"))
        propose(root, cache = cache(listOf(Entry("libanswer.so", "/system/libanswer.so", hwcap = 1)))).cacheFallback shouldBe CGlibcCacheLookup.UnsupportedSelection
        propose(root, cache = cache(listOf(Entry("libanswer.so", "/foreign/libanswer.so", flags = 3)))).cacheFallback shouldBe CGlibcCacheLookup.Absent
    }

    "REQ-C-GLIBC-SEARCH-001 refuses ambiguous origins without changing literal absolute search" {
        val aliased = objectInput(runpath = "\$ORIGIN").copy(aliases = listOf(ClangNativePathAlias(Path.of("/sdk/bin/tool"), Path.of("real-tool"))))
        refused(aliased) shouldBe CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        propose(objectInput(runpath = "\$ORIGIN").copy(requestedPath = Path.of("/sdk/bin/../bin/tool"))).runpathCandidates shouldBe
            listOf(Path.of("/sdk/bin/libanswer.so"))
        refused(objectInput(runpath = "\$ORIGIN").copy(requestedPath = Path.of("/different/tool"))) shouldBe CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        refused(objectInput(path = Path.of("relative/tool"))) shouldBe CGlibcSearchRefusal.INVALID_INPUT
        refused(objectInput(path = Path.of("/sdk/bin/../bin/tool"))) shouldBe CGlibcSearchRefusal.INVALID_INPUT
        propose(aliased.copy(artifact = aliased.artifact.copy(facts = aliased.artifact.facts.copy(runpath = "/absolute")))).runpathCandidates shouldBe
            listOf(Path.of("/absolute/libanswer.so"))
    }

    "REQ-C-GLIBC-SEARCH-001 refuses inherited RPATH NODEFLIB and nonliteral indexed dependencies" {
        refused(objectInput(rpath = "/old", runpath = "/new")) shouldBe CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        refused(objectInput(rpath = "")) shouldBe CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        refused(objectInput(flags1 = 0x800)) shouldBe CGlibcSearchRefusal.UNSUPPORTED_PROFILE
        listOf("", ".", "..", "lib/answer.so", "lib\\answer.so", "\$ORIGIN/libanswer.so", "lib\nanswer.so").forEach { name ->
            refused(objectInput(needed = listOf(name))) shouldBe CGlibcSearchRefusal.INVALID_INPUT
        }
        val root = objectInput()
        CGlibcDependencySearch.propose(root, -1, cache()).shouldBeInstanceOf<CGlibcDependencySearchResult.Refused>().reason shouldBe CGlibcSearchRefusal.INVALID_INPUT
        CGlibcDependencySearch.propose(root, 1, cache()).shouldBeInstanceOf<CGlibcDependencySearchResult.Refused>().reason shouldBe CGlibcSearchRefusal.INVALID_INPUT
        propose(objectInput(flags1 = 1)).cacheFallback shouldBe CGlibcCacheLookup.Candidate("/system/libanswer.so")
    }

    "REQ-C-GLIBC-SEARCH-001 bounds strict UTF-8 expansions and returns immutable candidate lists" {
        val sixtyFour = List(64) { "/d" }.joinToString(":")
        propose(objectInput(runpath = sixtyFour)).runpathCandidates.size shouldBe 64
        refused(objectInput(runpath = sixtyFour + ":/last")) shouldBe CGlibcSearchRefusal.INPUT_LIMIT
        refused(objectInput(runpath = "/" + "a".repeat(4096))) shouldBe CGlibcSearchRefusal.INPUT_LIMIT
        refused(objectInput(runpath = "/" + "é".repeat(2048))) shouldBe CGlibcSearchRefusal.INPUT_LIMIT
        refused(objectInput(runpath = "/" + "a".repeat(4095))) shouldBe CGlibcSearchRefusal.INPUT_LIMIT
        propose(objectInput(runpath = "/" + "a".repeat(4081))).runpathCandidates.single().toString().length shouldBe 4095
        val proposal = propose(objectInput(runpath = "/first:/second"))
        shouldThrow<UnsupportedOperationException> { (proposal.runpathCandidates as MutableList<Path>).clear() }
        proposal.runpathCandidates shouldBe listOf(Path.of("/first/libanswer.so"), Path.of("/second/libanswer.so"))
    }
}) {
    companion object {
        private fun objectInput(path: Path = Path.of("/sdk/bin/tool"), runpath: String? = null, rpath: String? = null,
            flags1: Long = 0, needed: List<String> = listOf("libanswer.so")): ClangNativePathCaptureResult.Captured {
            val hash = "a".repeat(64)
            val facts = CElfDynamicInputFacts(hash, "/loader", needed, null, rpath, runpath, 0, flags1, true, CElfObjectType.DYNAMIC)
            return ClangNativePathCaptureResult.Captured(path, emptyList(), ClangArtifactCaptureResult.Captured(
                ClangArtifactRole.CLANG, ClangToolchainFileEvidence("clang-executable", path, hash, 128), facts))
        }
        private fun propose(input: ClangNativePathCaptureResult.Captured, index: Int = 0, cache: CGlibcLoaderCacheFacts = cache()) =
            CGlibcDependencySearch.propose(input, index, cache).shouldBeInstanceOf<CGlibcDependencySearchResult.Proposed>().search
        private fun refused(input: ClangNativePathCaptureResult.Captured) =
            CGlibcDependencySearch.propose(input, 0, cache()).shouldBeInstanceOf<CGlibcDependencySearchResult.Refused>().reason
        private data class Entry(val name: String, val path: String, val flags: Int = 0x303, val hwcap: Long = 0)
        private fun cache(entries: List<Entry> = listOf(Entry("libanswer.so", "/system/libanswer.so"))): CGlibcLoaderCacheFacts {
            val strings = entries.flatMap { listOf(it.name.toByteArray(), it.path.toByteArray()) }
            val size = 48 + entries.size * 24 + strings.sumOf { it.size + 1 }
            val bytes = ByteArray(size); val output = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes)
            output.putInt(20, entries.size); output.putInt(24, size - 48 - entries.size * 24); bytes[28] = 2
            var cursor = 48 + entries.size * 24
            entries.forEachIndexed { index, entry ->
                val offset = 48 + index * 24; output.putInt(offset, entry.flags)
                output.putInt(offset + 4, cursor); entry.name.toByteArray().copyInto(bytes, cursor); cursor += entry.name.toByteArray().size + 1
                output.putInt(offset + 8, cursor); entry.path.toByteArray().copyInto(bytes, cursor); cursor += entry.path.toByteArray().size + 1
                output.putLong(offset + 16, entry.hwcap)
            }
            return CGlibcLoaderCache.read(bytes).shouldBeInstanceOf<CGlibcCacheReadout.Found>().facts
        }
    }
}

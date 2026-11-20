package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** REQ-C-CLANG-RESOURCE-FACTS-001; own binary facts, no filesystem or execution. */
class ClangResourceDirectoryFactsSpec : StringSpec({
    val root = Path.of("/sdk/usr/lib/clang/22")
    fun files() = listOf(
        ClangResourceFile(Path.of("include/stdint.h"), 30, "298eb47d11cde3c313a7baf0fa3ebc36306df08830984dca3eb83d28d0cb74ee"),
        ClangResourceFile(Path.of("include/empty.h"), 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
        ClangResourceFile(Path.of("lib/linux/runtime.a"), 4, "89273d2f70b93285bb7ddb4bcee86a5347ca7159352e3cbdd20c23e9d1e507d3"),
    )
    fun dirs() = listOf(Path.of("include"), Path.of("lib"), Path.of("lib/linux"))
    fun admitted(f: List<ClangResourceFile> = files(), d: List<Path> = dirs(), r: Path = root) =
        ClangResourceDirectoryFacts.evaluate(r, f, d).shouldBeInstanceOf<ClangResourceFactsResult.Admitted>().snapshot
    fun refused(f: List<ClangResourceFile> = files(), d: List<Path> = dirs(), r: Path = root,
        limits: ClangResourceLimits = ClangResourceLimits(), reason: ClangResourceRefusal) {
        ClangResourceDirectoryFacts.evaluate(r, f, d, limits) shouldBe ClangResourceFactsResult.Refused(reason)
    }

    "complete binary tree facts have independently fixed content and tree digests" {
        val facts = admitted()
        facts.identity shouldBe "c1803326b043aa281e0c6d0edb1025b872a971c0707b2ad245caae1b0792b16e"
        facts.totalBytes shouldBe 34L
        facts.files.size shouldBe 3
        ClangResourceDirectoryFacts.contentHash(byteArrayOf(0, 127, -128, -1)) shouldBe files().last().sha256
        ClangResourceDirectoryFacts.contentHash(byteArrayOf()) shouldBe files()[1].sha256
        ClangResourceDirectoryFacts.contentHash("typedef unsigned long size_t;\n".toByteArray()) shouldBe files().first().sha256
    }
    "permutation is stable while roots paths sizes and content hashes each bind identity" {
        val original = admitted()
        admitted(files().reversed(), dirs().reversed()).identity shouldBe original.identity
        admitted(r = Path.of("/other/resource")).identity shouldNotBe original.identity
        val first = files().first()
        for (changed in listOf(first.copy(path = Path.of("include/types.h")), first.copy(size = 31),
            first.copy(sha256 = "f".repeat(64)))) {
            admitted(listOf(changed) + files().drop(1)).identity shouldNotBe original.identity
        }
    }
    "invalid roots escaping paths collisions duplicates and absent ancestors refuse" {
        refused(r = Path.of("relative/root"), reason = ClangResourceRefusal.INVALID_ROOT)
        refused(r = Path.of("/sdk/../resource"), reason = ClangResourceRefusal.INVALID_ROOT)
        val first = files().first()
        for (bad in listOf(first.copy(path = Path.of("../outside")), first.copy(path = Path.of("/outside")),
            first.copy(path = Path.of("include/../file")), first.copy(path = Path.of("")),
            first.copy(size = -1), first.copy(sha256 = "unknown"))) {
            refused(f = listOf(bad) + files().drop(1), reason = ClangResourceRefusal.INVALID_TREE)
        }
        refused(f = files() + first, reason = ClangResourceRefusal.INVALID_TREE)
        refused(d = dirs() + Path.of("include"), reason = ClangResourceRefusal.INVALID_TREE)
        refused(d = dirs() + first.path, reason = ClangResourceRefusal.INVALID_TREE)
        refused(d = dirs().filter { it != Path.of("lib") }, reason = ClangResourceRefusal.INVALID_TREE)
    }
    "entry depth path per-file and total-byte budgets are independently enforced" {
        admitted()
        for (limits in listOf(ClangResourceLimits(maxEntries = 5), ClangResourceLimits(maxDepth = 1),
            ClangResourceLimits(maxPathBytes = 4), ClangResourceLimits(maxFileBytes = 3),
            ClangResourceLimits(maxTotalBytes = 32))) {
            refused(limits = limits, reason = ClangResourceRefusal.INPUT_LIMIT)
        }
        shouldThrow<IllegalArgumentException> { ClangResourceLimits(maxEntries = 8193) }
    }
    "the bounded file budget admits the official LLVM 22.1.8 Flang resource archive" {
        val flangRuntime = ClangResourceFile(
            Path.of("lib/x86_64-unknown-linux-gnu/libflang_rt.runtime.a"),
            27_987_190,
            "a".repeat(64),
        )
        val flangDirectories = listOf(Path.of("lib"), Path.of("lib/x86_64-unknown-linux-gnu"))
        admitted(listOf(flangRuntime), flangDirectories).totalBytes shouldBe 27_987_190L
        admitted(listOf(flangRuntime.copy(size = 32L * 1024 * 1024)), flangDirectories).totalBytes shouldBe
            32L * 1024 * 1024
        refused(
            f = listOf(flangRuntime.copy(size = 32L * 1024 * 1024 + 1)),
            d = flangDirectories,
            reason = ClangResourceRefusal.INPUT_LIMIT,
        )
        shouldThrow<IllegalArgumentException> { ClangResourceLimits(maxFileBytes = 32 * 1024 * 1024 + 1) }
    }
    "explicit empty roots and empty directories remain part of complete facts" {
        val empty = admitted(emptyList(), emptyList())
        empty.totalBytes shouldBe 0L
        empty.files shouldBe emptyList()
        admitted(emptyList(), listOf(Path.of("empty"))).identity shouldNotBe empty.identity
        admitted(emptyList(), listOf(Path.of("empty"), Path.of("empty/nested"))).identity shouldNotBe
            admitted(emptyList(), listOf(Path.of("empty"))).identity
    }
    "admitted file and directory lists cannot mutate or retain caller-owned collections" {
        val callerFiles = files().toMutableList(); val callerDirs = dirs().toMutableList()
        val facts = admitted(callerFiles, callerDirs); val identity = facts.identity
        callerFiles.clear(); callerDirs.clear()
        facts.files.size shouldBe 3; facts.directories.size shouldBe 3; facts.identity shouldBe identity
        shouldThrow<UnsupportedOperationException> { (facts.files as MutableList).clear() }
        shouldThrow<UnsupportedOperationException> { (facts.directories as MutableList).clear() }
    }

    "resource snapshots defend direct constructor and copy inputs while retaining data-class behavior" {
        val callerFiles = files().toMutableList()
        val callerDirectories = dirs().toMutableList()
        val snapshot = ClangResourceDirectorySnapshot(root, callerFiles, callerDirectories, "identity", 34)

        callerFiles.clear()
        callerDirectories.clear()

        snapshot.files shouldBe files()
        snapshot.directories shouldBe dirs()
        snapshot.copy() shouldBe snapshot
        snapshot.hashCode() shouldBe snapshot.copy().hashCode()
        snapshot.toString() shouldBe
            "ClangResourceDirectorySnapshot(root=$root, files=${files()}, directories=${dirs()}, identity=identity, totalBytes=34)"
        val (destructuredRoot, destructuredFiles, destructuredDirectories, identity, totalBytes) = snapshot
        destructuredRoot shouldBe root
        destructuredFiles shouldBe files()
        destructuredDirectories shouldBe dirs()
        identity shouldBe "identity"
        totalBytes shouldBe 34L

        val copyFiles = files().toMutableList()
        val copyDirectories = dirs().toMutableList()
        val copied = snapshot.copy(files = copyFiles, directories = copyDirectories)
        copyFiles.clear()
        copyDirectories.clear()
        copied.files shouldBe files()
        copied.directories shouldBe dirs()
        shouldThrow<UnsupportedOperationException> { (snapshot.files as MutableList<ClangResourceFile>).clear() }
        shouldThrow<UnsupportedOperationException> { (snapshot.directories as MutableList<Path>).clear() }
    }
})

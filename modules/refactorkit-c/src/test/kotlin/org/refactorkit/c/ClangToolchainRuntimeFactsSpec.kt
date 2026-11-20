package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

/** Own supplied metadata only; no filesystem, native execution, or freshness/admission oracle. */
class ClangToolchainRuntimeFactsSpec : StringSpec({
    "REQ-C-CLANG-RUNTIME-IDENTITY-001 fixes independent manifest resolution and bundle digests" {
        val c = fixture(ClangArtifactRole.CLANG); val d = fixture(ClangArtifactRole.CLANGD); val f = fixture(ClangArtifactRole.FORMATTER)
        c.manifest.identity shouldBe "63d8bbb7fb6d47da6ecf51ba5e5687caeca215551b4deb9117c53380db471137"
        d.manifest.identity shouldBe "2b37053a3f31b49128c1b012072368978edcfdba959e9bf85607e6e5289d6e54"
        f.manifest.identity shouldBe "4f7500f14a7ca332005c0d797e497d3be24dba205d82c6af35883145c65ab7b6"
        ClangGlibcRuntimeIdentity.of(c) shouldBe "327d69fe2bd4f757b48a115ecab0e1e2b7eb1804599e677c28e56ffcae00fd78"
        ClangGlibcRuntimeIdentity.of(d) shouldBe "1c00e5c90d3f7203cd371b486c548816381c14d842ddb4088a1cfe769ec27d8d"
        ClangGlibcRuntimeIdentity.of(f) shouldBe "1379b7f55b45f80b93cf228c025637919461da301855b12399fcd8fda3e12c3b"
        compose(c, d, f).identity shouldBe "15d447c938669b1a52f5854d9f7bfc3116023ab5d91c7b40f21b2430554bc7fc"
    }

    "REQ-C-CLANG-RUNTIME-IDENTITY-001 binds whole graph bytes physical roles aliases and raw spelling" {
        val c = fixture(ClangArtifactRole.CLANG)
        for (changed in listOf(fixture(ClangArtifactRole.CLANG, rootRequested = Path.of("/sdk/bin/./clang")),
            fixture(ClangArtifactRole.CLANG, libraryHash = "f".repeat(64)),
            fixture(ClangArtifactRole.CLANG, libraryRunpath = "/other"),
            fixture(ClangArtifactRole.CLANG, libraryAliases = listOf(ClangNativePathAlias(Path.of("/sdk/lib/alias.so"), Path.of("libanswer.so")))))) {
            ClangGlibcRuntimeIdentity.of(changed) shouldNotBe ClangGlibcRuntimeIdentity.of(c)
        }
        val changed = compose(fixture(ClangArtifactRole.CLANG, libraryHash = "f".repeat(64)),
            fixture(ClangArtifactRole.CLANGD, libraryHash = "f".repeat(64)), fixture(ClangArtifactRole.FORMATTER, libraryHash = "f".repeat(64)))
        changed.identity shouldNotBe compose(c, fixture(ClangArtifactRole.CLANGD), fixture(ClangArtifactRole.FORMATTER)).identity
    }

    "REQ-C-CLANG-RUNTIME-IDENTITY-001 binds every cache preload and parsed metadata field" {
        val c = fixture(ClangArtifactRole.CLANG); val input = c.inputs; val evidence = input.cacheEvidence
        val changes = listOf(inputWith(input, evidence = evidence.copy(role = "other")), inputWith(input, evidence = evidence.copy(path = Path.of("/other/cache"))),
            inputWith(input, evidence = evidence.copy(size = 49)), inputWith(input, evidence = evidence.copy(sha256 = "f".repeat(64))),
            inputWith(input, preload = Path.of("/other/preload")), inputWith(input, facts = cacheFacts(input.cacheFacts, sha = "f".repeat(64))),
            inputWith(input, facts = cacheFacts(input.cacheFacts, flags = 3)))
        for (changed in changes) ClangGlibcRuntimeIdentity.of(withInputs(c, changed)) shouldNotBe ClangGlibcRuntimeIdentity.of(c)
        val entry = CGlibcCacheEntry("libanswer.so", "/sdk/lib/libanswer.so", 0x303, 0, 0)
        val base = withInputs(c, inputWith(input, facts = cacheFacts(input.cacheFacts, entries = listOf(entry))))
        for (changed in listOf(entry.copy(name = "libother.so"), entry.copy(path = "/other/libanswer.so"), entry.copy(flags = 3),
            entry.copy(osVersion = 1), entry.copy(hwcap = Long.MIN_VALUE))) {
            ClangGlibcRuntimeIdentity.of(withInputs(c, inputWith(input, facts = cacheFacts(input.cacheFacts, entries = listOf(changed))))) shouldNotBe ClangGlibcRuntimeIdentity.of(base)
        }
        val extension = CGlibcCacheExtension(0, 0, 64, 8, "a".repeat(64))
        val extended = withInputs(c, inputWith(input, facts = cacheFacts(input.cacheFacts, extensions = listOf(extension))))
        for (changed in listOf(extension.copy(tag = 1), extension.copy(flags = 1), extension.copy(offset = 68),
            extension.copy(size = 12), extension.copy(sha256 = "b".repeat(64)))) {
            ClangGlibcRuntimeIdentity.of(withInputs(c, inputWith(input, facts = cacheFacts(input.cacheFacts, extensions = listOf(changed))))) shouldNotBe ClangGlibcRuntimeIdentity.of(extended)
        }
    }

    "REQ-C-CLANG-RUNTIME-IDENTITY-001 preserves observation and cache order spelling and multiplicity" {
        val c = fixture(ClangArtifactRole.CLANG); val first = c.observations.first()
        for (changed in listOf(first.copy(requester = Path.of("/other/requester")), first.copy(neededIndex = 1),
            first.copy(kind = ClangGlibcSearchKind.CANDIDATE_ABSENT), first.copy(path = Path.of("/sdk/lib/./glibc-hwcaps")))) {
            ClangGlibcRuntimeIdentity.of(withObservations(c, listOf(changed) + c.observations.drop(1))) shouldNotBe ClangGlibcRuntimeIdentity.of(c)
        }
        ClangGlibcRuntimeIdentity.of(withObservations(c, c.observations.reversed())) shouldNotBe ClangGlibcRuntimeIdentity.of(c)
        ClangGlibcRuntimeIdentity.of(withObservations(c, c.observations + first)) shouldNotBe ClangGlibcRuntimeIdentity.of(c)
        val a = CGlibcCacheEntry("é", "/sdk/lib/a", 0x303, 0, 0); val b = a.copy(name = "e\u0301")
        fun encoded(entries: List<CGlibcCacheEntry>) = ClangGlibcRuntimeIdentity.of(withInputs(c,
            inputWith(c.inputs, facts = cacheFacts(c.inputs.cacheFacts, entries = entries))))
        encoded(listOf(a, b)) shouldNotBe encoded(listOf(b, a))
        encoded(listOf(a)) shouldNotBe encoded(listOf(a, a))
        encoded(listOf(a)) shouldNotBe encoded(listOf(b))
    }

    "REQ-C-CLANG-RUNTIME-IDENTITY-001 refuses role cache preload and shared-object incoherence" {
        val c = fixture(ClangArtifactRole.CLANG); val d = fixture(ClangArtifactRole.CLANGD); val f = fixture(ClangArtifactRole.FORMATTER)
        refused(d, c, f) shouldBe ClangToolchainRuntimeRefusal.ROLE_MISMATCH
        refused(c, fixture(ClangArtifactRole.CLANGD, physicalRoot = c.manifest.executable.artifact.evidence.path), f) shouldBe ClangToolchainRuntimeRefusal.ROLE_MISMATCH
        refused(c, withInputs(d, inputWith(d.inputs, preload = Path.of("/other/preload"))), f) shouldBe ClangToolchainRuntimeRefusal.INCONSISTENT_INPUTS
        refused(c, withInputs(d, inputWith(d.inputs, evidence = d.inputs.cacheEvidence.copy(path = Path.of("/other/cache")))), f) shouldBe ClangToolchainRuntimeRefusal.INCONSISTENT_INPUTS
        refused(c, withInputs(d, inputWith(d.inputs, facts = cacheFacts(d.inputs.cacheFacts, flags = 3))), f) shouldBe ClangToolchainRuntimeRefusal.INCONSISTENT_INPUTS
        refused(c, fixture(ClangArtifactRole.CLANGD, libraryHash = "f".repeat(64)), f) shouldBe ClangToolchainRuntimeRefusal.INCONSISTENT_OBJECT
        refused(c, fixture(ClangArtifactRole.CLANGD, libraryRunpath = "/different"), f) shouldBe ClangToolchainRuntimeRefusal.INCONSISTENT_OBJECT
    }

    "REQ-C-CLANG-RUNTIME-IDENTITY-001 retains immutable bounded captures and explicit unknown runtime" {
        val c = fixture(ClangArtifactRole.CLANG); val d = fixture(ClangArtifactRole.CLANGD); val f = fixture(ClangArtifactRole.FORMATTER)
        val mutable = c.observations.toMutableList(); val owned = withObservations(c, mutable); mutable.clear()
        val facts = compose(owned, d, f)
        facts.clang shouldBe owned; facts.clangd shouldBe d; facts.formatter shouldBe f
        owned.observations shouldBe c.observations
        shouldThrow<UnsupportedOperationException> { (facts.runtimes as MutableList<ClangGlibcRuntimeResolution.Captured>).clear() }
        shouldThrow<UnsupportedOperationException> { (facts.clang.observations as MutableList<ClangGlibcSearchObservation>).clear() }
        compose(withObservations(c, List(8192) { c.observations.first() }), d, f).runtimes.size shouldBe 3
        refused(withObservations(c, List(8193) { c.observations.first() }), d, f) shouldBe ClangToolchainRuntimeRefusal.INPUT_LIMIT
        for (bad in listOf(c.inputs.cacheEvidence.copy(role = "other"), c.inputs.cacheEvidence.copy(size = 47),
            c.inputs.cacheEvidence.copy(sha256 = "f".repeat(64)), c.inputs.cacheEvidence.copy(path = Path.of("relative/cache")))) {
            refused(withInputs(c, inputWith(c.inputs, evidence = bad)), d, f) shouldBe ClangToolchainRuntimeRefusal.INVALID_INPUT
        }
        val p = ClangToolchainProvenance(clangVersion = "22.1.8", clangdVersion = "22.1.8", clangFormatVersion = "22.1.8",
            targetTriple = null, resourceDir = null, evidence = emptyList())
        ClangSemanticToolchain(Path.of("/clang"), Path.of("/clangd"), Path.of("/format"), p).runtime shouldBe ClangToolchainRuntimeCaptureResult.NotCaptured
        p.nativeRuntimeIdentity shouldBe null
        p.copy(nativeRuntimeIdentity = facts.identity).toolchainIdentity() shouldNotBe p.toolchainIdentity()
    }
}) {
    companion object {
        private fun fixture(role: ClangArtifactRole, physicalRoot: Path? = null, rootRequested: Path? = null,
            libraryHash: String = "e".repeat(64), libraryRunpath: String? = null, libraryAliases: List<ClangNativePathAlias> = emptyList()): ClangGlibcRuntimeResolution.Captured {
            val tool = when (role) { ClangArtifactRole.CLANG -> "clang"; ClangArtifactRole.CLANGD -> "clangd"; else -> "clang-format" }
            val rootPath = physicalRoot ?: Path.of("/sdk/bin/$tool"); val loaderPath = Path.of("/loader"); val libraryPath = Path.of("/sdk/lib/libanswer.so")
            val rootHash = when (role) { ClangArtifactRole.CLANG -> "a"; ClangArtifactRole.CLANGD -> "b"; else -> "c" }.repeat(64)
            fun objectInput(path: Path, requested: Path, artifactRole: ClangArtifactRole, hash: String, size: Long,
                interp: String?, needed: List<String>, soname: String?, runpath: String?, aliases: List<ClangNativePathAlias> = emptyList()) =
                ClangNativePathCaptureResult.Captured(requested, aliases, ClangArtifactCaptureResult.Captured(artifactRole,
                    ClangToolchainFileEvidence(artifactRole.evidenceRole, path, hash, size),
                    CElfDynamicInputFacts(hash, interp, needed, soname, null, runpath, 0, 0, true, CElfObjectType.DYNAMIC)))
            val executable = objectInput(rootPath, rootRequested ?: rootPath, role, rootHash, 128, "/loader", listOf("libanswer.so"), null, "\$ORIGIN/../lib")
            val loader = objectInput(loaderPath, loaderPath, ClangArtifactRole.INTERPRETER, "d".repeat(64), 128, null, emptyList(), "ld-linux-x86-64.so.2", null)
            val requestedLibrary = Path.of("/sdk/bin/../lib/libanswer.so")
            val library = objectInput(libraryPath, requestedLibrary, ClangArtifactRole.SHARED_LIBRARY, libraryHash, 256, null, emptyList(), "libanswer.so", libraryRunpath, libraryAliases)
            val manifest = ClangRuntimeManifest.compose(executable, loader, listOf(library), listOf(ClangRuntimeDependencyBinding(rootPath, 0, libraryPath)))
                .shouldBeInstanceOf<ClangRuntimeManifestComposition.Composed>().manifest
            val bytes = ByteArray(48); "glibc-ld.so.cache1.1".toByteArray().copyInto(bytes); bytes[28] = 2
            val cache = CGlibcLoaderCache.read(bytes).shouldBeInstanceOf<CGlibcCacheReadout.Found>().facts
            val input = ClangGlibcRuntimeInputCaptureResult.Captured(ClangToolchainFileEvidence("glibc-loader-cache", Path.of("/etc/ld.so.cache"), cache.sha256, 48),
                cache, Path.of("/etc/ld.so.preload"))
            return ClangGlibcRuntimeResolution.Captured(manifest, input, listOf(
                ClangGlibcSearchObservation(rootPath, 0, ClangGlibcSearchKind.HARDWARE_ABSENT, Path.of("/sdk/lib/glibc-hwcaps")),
                ClangGlibcSearchObservation(rootPath, 0, ClangGlibcSearchKind.RUNPATH, requestedLibrary)))
        }
        private fun cacheFacts(input: CGlibcLoaderCacheFacts, sha: String = input.sha256, flags: Int = input.headerFlags,
            entries: List<CGlibcCacheEntry> = input.entries, extensions: List<CGlibcCacheExtension> = input.extensions) = CGlibcLoaderCacheFacts(sha, flags, entries, extensions)
        private fun inputWith(input: ClangGlibcRuntimeInputCaptureResult.Captured, evidence: ClangToolchainFileEvidence = input.cacheEvidence,
            facts: CGlibcLoaderCacheFacts = input.cacheFacts, preload: Path = input.preloadPath) = ClangGlibcRuntimeInputCaptureResult.Captured(evidence, facts, preload)
        private fun withInputs(input: ClangGlibcRuntimeResolution.Captured, facts: ClangGlibcRuntimeInputCaptureResult.Captured) =
            ClangGlibcRuntimeResolution.Captured(input.manifest, facts, input.observations)
        private fun withObservations(input: ClangGlibcRuntimeResolution.Captured, observations: List<ClangGlibcSearchObservation>) =
            ClangGlibcRuntimeResolution.Captured(input.manifest, input.inputs, observations)
        private fun compose(c: ClangGlibcRuntimeResolution.Captured, d: ClangGlibcRuntimeResolution.Captured, f: ClangGlibcRuntimeResolution.Captured) =
            ClangToolchainRuntimeFacts.compose(c, d, f).shouldBeInstanceOf<ClangToolchainRuntimeComposition.Composed>().facts
        private fun refused(c: ClangGlibcRuntimeResolution.Captured, d: ClangGlibcRuntimeResolution.Captured, f: ClangGlibcRuntimeResolution.Captured) =
            ClangToolchainRuntimeFacts.compose(c, d, f).shouldBeInstanceOf<ClangToolchainRuntimeComposition.Refused>().reason
    }
}

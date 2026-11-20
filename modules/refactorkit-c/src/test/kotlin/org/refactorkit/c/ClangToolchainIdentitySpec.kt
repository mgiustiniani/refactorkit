package org.refactorkit.c

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Path
import java.nio.charset.CharacterCodingException

/** REQ-C-CLANG-IDENTITY-001; own facts, no filesystem or process access. */
class ClangToolchainIdentitySpec : StringSpec({
    "complete provenance has the independently fixed versioned digest" {
        val p = identityFixture()
        p.toolchainIdentity() shouldBe "185a9bd0e838fcd37ff966aabf1b353f6cbb39f7425dbca3232bcd0b68d28361"
        p.toolchainIdentity() shouldBe p.copy().toolchainIdentity()
    }
    "role path size and hash each revoke a previous identity" {
        val p = identityFixture(); val e = p.evidence.first()
        for (changed in listOf(e.copy(role = "runtime-library"), e.copy(path = Path.of("/other/clang-22")),
            e.copy(size = e.size + 1), e.copy(sha256 = "d".repeat(64)))) {
            p.copy(evidence = listOf(changed) + p.evidence.drop(1)).toolchainIdentity() shouldNotBe p.toolchainIdentity()
        }
    }
    "provider every tool version target resource directory and native runtime each bind authority" {
        val p = identityFixture()
        for (changed in listOf(p.copy(providerId = "other-provider"), p.copy(clangVersion = "22.1.9"),
            p.copy(clangdVersion = "22.1.9"), p.copy(clangFormatVersion = "22.1.9"),
            p.copy(targetTriple = "aarch64-linux-gnu"), p.copy(resourceDir = "/other/resource"),
            p.copy(nativeRuntimeIdentity = "f".repeat(64)))) {
            changed.toolchainIdentity() shouldNotBe p.toolchainIdentity()
        }
    }
    "embedded field separators cannot alias another provenance tuple" {
        val p = identityFixture()
        p.copy(providerId = "provider|a", clangVersion = "b").toolchainIdentity() shouldNotBe
            p.copy(providerId = "provider", clangVersion = "a|b").toolchainIdentity()
        p.copy(targetTriple = "a|b", resourceDir = "c").toolchainIdentity() shouldNotBe
            p.copy(targetTriple = "a", resourceDir = "b|c").toolchainIdentity()
    }
    "evidence permutation is stable while multiplicity and normalized paths remain explicit" {
        val p = identityFixture(); val e = p.evidence.first()
        p.copy(evidence = p.evidence.reversed()).toolchainIdentity() shouldBe p.toolchainIdentity()
        p.copy(evidence = listOf(e.copy(path = Path.of("/sdk/usr/./bin/../bin/clang-22"))) + p.evidence.drop(1))
            .toolchainIdentity() shouldBe p.toolchainIdentity()
        p.copy(evidence = p.evidence + e).toolchainIdentity() shouldNotBe p.toolchainIdentity()
        p.copy(evidence = emptyList()).toolchainIdentity() shouldNotBe p.toolchainIdentity()
    }
    "null literal placeholders Unicode and relative paths cannot collapse" {
        val p = identityFixture(); val e = p.evidence.first()
        p.copy(targetTriple = null).toolchainIdentity() shouldNotBe p.copy(targetTriple = "-").toolchainIdentity()
        p.copy(resourceDir = null).toolchainIdentity() shouldNotBe p.copy(resourceDir = "").toolchainIdentity()
        p.copy(providerId = "\u00e9").toolchainIdentity() shouldNotBe p.copy(providerId = "e\u0301").toolchainIdentity()
        for (malformed in listOf("\uD800", "\uDC00", "x\uD800")) {
            shouldThrow<CharacterCodingException> { p.copy(providerId = malformed).toolchainIdentity() }
        }
        p.copy(evidence = listOf(e.copy(path = Path.of("sdk/usr/bin/clang-22"))) + p.evidence.drop(1))
            .toolchainIdentity() shouldNotBe p.toolchainIdentity()
    }
})

private fun identityFixture() = ClangToolchainProvenance(clangVersion = "22.1.8", clangdVersion = "22.1.8",
    clangFormatVersion = "22.1.8", targetTriple = "x86_64-pc-linux-gnu", resourceDir = "/sdk/usr/lib/clang/22",
    evidence = listOf(
        ClangToolchainFileEvidence("clang-executable", Path.of("/sdk/usr/bin/clang-22"), "a".repeat(64), 11),
        ClangToolchainFileEvidence("clangd-executable", Path.of("/sdk/usr/bin/clangd"), "b".repeat(64), 12),
        ClangToolchainFileEvidence("clang-format-executable", Path.of("/sdk/usr/bin/clang-format"), "c".repeat(64), 13),
    ))

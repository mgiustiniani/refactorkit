package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClangToolchainDiscoveryTest {
    @Test
    fun discoversExplicitToolchainWithoutExecutingProjectCode() {
        val fixture = fixture()
        var probes = 0
        val discoverer = ClangToolchainDiscoverer(
            versionProbe = ClangVersionProbe {
                probes++
                Result.success("$it version 22.1.8\nTarget: x86_64-pc-linux-gnu")
            },
            resourceDirProbe = ClangResourceDirProbe { Result.success("/usr/lib/clang/22") },
        )

        val result = discoverer.discover(fixture.request())

        val toolchain = assertIs<ClangToolchainDiscovery.Available>(result).toolchain
        assertEquals(3, probes)
        assertEquals("clang-explicit-v1", toolchain.provenance.providerId)
        assertEquals("22.1.8", toolchain.provenance.clangVersion)
        assertEquals("22.1.8", toolchain.provenance.clangdVersion)
        assertEquals("22.1.8", toolchain.provenance.clangFormatVersion)
        assertEquals("x86_64-pc-linux-gnu", toolchain.provenance.targetTriple)
        assertEquals("/usr/lib/clang/22", toolchain.provenance.resourceDir)
        assertEquals(
            listOf("clang-format-executable", "clang-executable", "clangd-executable").sorted(),
            toolchain.provenance.evidence.map { it.role },
        )
        assertTrue(toolchain.provenance.evidence.all { it.sha256.length == 64 && it.size > 0 })
        assertEquals("not executed", Files.readString(fixture.marker))
    }

    @Test
    fun evidenceChangesWhenExecutableChanges() {
        val fixture = fixture()
        val discoverer = discoverer()
        val first = assertIs<ClangToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain
        val firstHash = first.provenance.evidence.single { it.role == "clang-executable" }.sha256
        fixture.clang.writeText("changed clang")

        val second = assertIs<ClangToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain
        val secondHash = second.provenance.evidence.single { it.role == "clang-executable" }.sha256
        assertNotEquals(firstHash, secondHash)
    }

    @Test
    fun explicitConfigurationIsRequiredByDefault() {
        val workspace = Files.createTempDirectory("refactorkit-clang-workspace")
        val result = discoverer().discover(ClangToolchainRequest(workspace))
        assertCodes(result, "clang.toolchainNotConfigured")
    }

    @Test
    fun workspaceLocalExecutablesRequireExplicitPolicy() {
        val fixture = fixture(executablesInsideWorkspace = true)
        val refused = discoverer().discover(fixture.request())
        assertCodes(refused, "clang.workspaceToolchainRefused")

        val allowed = ClangToolchainDiscoverer(
            ClangToolchainDiscoveryPolicy(allowWorkspaceLocalToolchain = true),
            ClangVersionProbe { Result.success("$it version 22.1.8") },
            ClangResourceDirProbe { Result.success("/usr/lib/clang/22") },
        ).discover(fixture.request())
        assertIs<ClangToolchainDiscovery.Available>(allowed)
    }

    @Test
    fun refusesProbeFailureAndUnsupportedMajor() {
        val fixture = fixture()
        assertCodes(ClangToolchainDiscoverer(versionProbe = ClangVersionProbe {
            Result.failure(IllegalStateException("probe failed"))
        }, resourceDirProbe = resourceDirProbe()).discover(fixture.request()), "clang.versionProbeFailed")

        assertCodes(ClangToolchainDiscoverer(versionProbe = ClangVersionProbe {
            Result.success("$it version 19.1.0")
        }, resourceDirProbe = resourceDirProbe()).discover(fixture.request()), "clang.versionUnsupported")

        assertCodes(ClangToolchainDiscoverer(versionProbe = ClangVersionProbe {
            Result.success("$it version not-a-version")
        }, resourceDirProbe = resourceDirProbe()).discover(fixture.request()), "clang.versionInvalid")
    }

    @Test
    fun refusesMismatchedToolchainVersions() {
        val fixture = fixture()
        val discoverer = ClangToolchainDiscoverer(
            versionProbe = ClangVersionProbe { executable ->
                val label = executable.fileName.toString()
                if (label == "clangd") Result.success("clangd version 21.0.0") else Result.success("$label version 22.1.8")
            },
            resourceDirProbe = resourceDirProbe(),
        )
        assertCodes(discoverer.discover(fixture.request()), "clang.toolchainMismatch")
    }

    @Test
    fun refusesSymlinkedExecutableAndOversizedToolchain() {
        val symlinkFixture = fixture()
        val outside = Files.createTempFile("refactorkit-clang-outside", ".bin")
        Files.delete(symlinkFixture.clang)
        runCatching { Files.createSymbolicLink(symlinkFixture.clang, outside) }.onSuccess {
            assertCodes(discoverer().discover(symlinkFixture.request()), "clang.toolchainPathInvalid")
        }

        val oversized = fixture()
        val smallPolicy = ClangToolchainDiscoveryPolicy(maxExecutableBytes = 32)
        oversized.clang.writeText(" ".repeat(33))
        assertCodes(ClangToolchainDiscoverer(smallPolicy, versionProbe = ClangVersionProbe {
            Result.success("$it version 22.1.8")
        }, resourceDirProbe = resourceDirProbe()).discover(oversized.request()), "clang.toolchainFileLimit")
    }

    @Test
    fun managedVersionProbeRetriesOneTransientFailureWithinFixedBound() {
        var attempts = 0
        val recovered = ManagedClangVersionProbe(ClangVersionProbe {
            attempts++
            if (attempts == 1) Result.failure(IllegalStateException("transient")) else Result.success("clang version 22.1.8")
        }).probe(Path.of("explicit-clang"))
        assertEquals("clang version 22.1.8", recovered.getOrThrow())
        assertEquals(ManagedClangVersionProbe.MAX_PROBE_ATTEMPTS, attempts)

        attempts = 0
        val failed = ManagedClangVersionProbe(ClangVersionProbe {
            attempts++
            Result.failure(IllegalStateException("still failing"))
        }).probe(Path.of("explicit-clang"))
        assertTrue(failed.isFailure)
        assertEquals(ManagedClangVersionProbe.MAX_PROBE_ATTEMPTS, attempts)
    }

    @Test
    fun parsesClangVersionOutputAndMajor() {
        assertEquals("22.1.8", parseVersion("clang version 22.1.8\nTarget: x86_64-pc-linux-gnu"))
        assertEquals("22.1.8", parseVersion("clangd version 22.1.8\nFeatures: linux"))
        assertEquals("22.1.8", parseVersion("clang-format version 22.1.8"))
        assertEquals(null, parseVersion("unexpected output"))
        assertEquals(22, parseMajor("22.1.8"))
        assertEquals(null, parseMajor("not-a-version"))
        assertEquals("x86_64-pc-linux-gnu", parseTargetTriple("clang version 22.1.8\nTarget: x86_64-pc-linux-gnu\nThread model: posix"))
        assertEquals(null, parseTargetTriple("clang version 22.1.8"))
    }

    @Test
    fun refusesResourceDirProbeFailureAndBuildsIdentity() {
        val fixture = fixture()
        assertCodes(ClangToolchainDiscoverer(
            versionProbe = ClangVersionProbe { Result.success("$it version 22.1.8") },
            resourceDirProbe = ClangResourceDirProbe { Result.failure(IllegalStateException("resource-dir failed")) },
        ).discover(fixture.request()), "clang.resourceDirProbeFailed")

        val first = assertIs<ClangToolchainDiscovery.Available>(discoverer().discover(fixture.request())).toolchain
        val identity = first.provenance.toolchainIdentity()
        assertTrue(identity.length == 64)
        assertEquals(identity, assertIs<ClangToolchainDiscovery.Available>(discoverer().discover(fixture.request())).toolchain.provenance.toolchainIdentity())

        fixture.clang.writeText("changed clang")
        val changed = assertIs<ClangToolchainDiscovery.Available>(discoverer().discover(fixture.request())).toolchain
        assertNotEquals(identity, changed.provenance.toolchainIdentity())
    }

    private fun discoverer() = ClangToolchainDiscoverer(
        versionProbe = ClangVersionProbe { Result.success("$it version 22.1.8\nTarget: x86_64-pc-linux-gnu") },
        resourceDirProbe = resourceDirProbe(),
    )

    private fun resourceDirProbe() = ClangResourceDirProbe { Result.success("/usr/lib/clang/22") }

    private fun assertCodes(result: ClangToolchainDiscovery, vararg expected: String) {
        val refused = assertIs<ClangToolchainDiscovery.Refused>(result)
        val actual = refused.diagnostics.mapNotNull(Diagnostic::code)
        expected.forEach { assertTrue(it in actual, "missing $it in $actual") }
    }

    private fun fixture(executablesInsideWorkspace: Boolean = false): Fixture {
        val workspace = Files.createTempDirectory("refactorkit-clang-workspace")
        val external = Files.createTempDirectory("refactorkit-clang-toolchain")
        val base = if (executablesInsideWorkspace) workspace.createDirectories() else external
        val clang = base.resolve("clang")
        val clangd = base.resolve("clangd")
        val clangFormat = base.resolve("clang-format")
        clang.writeText("fake clang")
        clangd.writeText("fake clangd")
        clangFormat.writeText("fake clang-format")
        clang.toFile().setExecutable(true)
        clangd.toFile().setExecutable(true)
        clangFormat.toFile().setExecutable(true)
        val marker = workspace.resolve("marker.txt")
        marker.writeText("not executed")
        return Fixture(workspace, clang, clangd, clangFormat, marker)
    }

    private data class Fixture(
        val workspace: Path,
        val clang: Path,
        val clangd: Path,
        val clangFormat: Path,
        val marker: Path,
    ) {
        fun request() = ClangToolchainRequest(
            workspaceRoot = workspace,
            clangExecutable = clang,
            clangdExecutable = clangd,
            clangFormatExecutable = clangFormat,
        )
    }
}

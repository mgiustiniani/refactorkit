package org.refactorkit.typescript

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TypeScriptToolchainDiscoveryTest {
    @Test
    fun discoversExplicitToolchainWithoutExecutingPackageCode() {
        val fixture = fixture()
        var probes = 0
        val discoverer = TypeScriptToolchainDiscoverer(nodeVersionProbe = NodeVersionProbe {
            probes++
            Result.success("v22.12.0")
        })

        val result = discoverer.discover(fixture.request())

        val toolchain = assertIs<TypeScriptToolchainDiscovery.Available>(result).toolchain
        assertEquals(1, probes)
        assertEquals("typescript-lsp-explicit-v1", toolchain.provenance.providerId)
        assertEquals("22.12.0", toolchain.provenance.nodeVersion)
        assertEquals("4.3.3", toolchain.provenance.languageServerVersion)
        assertEquals("5.7.2", toolchain.provenance.typeScriptVersion)
        assertEquals(
            listOf("node-executable", "typescript-package", "typescript-server-entrypoint", "language-server-entrypoint", "language-server-package").sorted(),
            toolchain.provenance.evidence.map { it.role },
        )
        assertTrue(toolchain.provenance.evidence.all { it.sha256.length == 64 && it.size > 0 })
        assertEquals(listOf(toolchain.nodeExecutable.toString(), toolchain.languageServerEntrypoint.toString(), "--stdio"), toolchain.command)
        assertEquals("not executed", Files.readString(fixture.marker))
    }

    @Test
    fun evidenceChangesWhenEntrypointChanges() {
        val fixture = fixture()
        val discoverer = discoverer()
        val first = assertIs<TypeScriptToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain
        val firstHash = first.provenance.evidence.single { it.role == "language-server-entrypoint" }.sha256
        fixture.languageServerRoot.resolve("lib/cli.mjs").writeText("console.log('changed')")

        val second = assertIs<TypeScriptToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain
        val secondHash = second.provenance.evidence.single { it.role == "language-server-entrypoint" }.sha256
        assertNotEquals(firstHash, secondHash)
    }

    @Test
    fun explicitConfigurationIsRequiredByDefault() {
        val workspace = Files.createTempDirectory("refactorkit-ts-workspace")
        val result = discoverer().discover(TypeScriptToolchainRequest(workspace))
        assertCodes(result, "typescript.toolchainNotConfigured")
    }

    @Test
    fun workspaceLocalExecutablesAndPackagesRequireExplicitPolicy() {
        val fixture = fixture(packagesInsideWorkspace = true, nodeInsideWorkspace = true)
        val refused = discoverer().discover(fixture.request())
        assertCodes(refused, "typescript.workspaceToolchainRefused")

        val allowed = TypeScriptToolchainDiscoverer(
            TypeScriptToolchainDiscoveryPolicy(allowWorkspaceLocalToolchain = true),
            NodeVersionProbe { Result.success("20.11.1") },
        ).discover(fixture.request())
        assertIs<TypeScriptToolchainDiscovery.Available>(allowed)
    }

    @Test
    fun refusesUnsupportedOrUnverifiedNodeVersions() {
        val fixture = fixture()
        assertCodes(TypeScriptToolchainDiscoverer(nodeVersionProbe = NodeVersionProbe {
            Result.failure(IllegalStateException("probe failed"))
        }).discover(fixture.request()), "typescript.nodeProbeFailed")

        assertCodes(TypeScriptToolchainDiscoverer(nodeVersionProbe = NodeVersionProbe {
            Result.success("v16.20.0")
        }).discover(fixture.request()), "typescript.nodeVersionUnsupported")

        assertCodes(TypeScriptToolchainDiscoverer(nodeVersionProbe = NodeVersionProbe {
            Result.success("unexpected output")
        }).discover(fixture.request()), "typescript.nodeVersionUnsupported")
    }

    @Test
    fun refusesPackageIdentityCompilerVersionAndEntrypointEscape() {
        val wrongIdentity = fixture(languageServerName = "malicious-server")
        assertCodes(discoverer().discover(wrongIdentity.request()), "typescript.packageIdentityInvalid")

        val oldCompiler = fixture(typeScriptVersion = "4.9.5")
        assertCodes(discoverer().discover(oldCompiler.request()), "typescript.compilerVersionUnsupported")

        val escaping = fixture(languageServerBin = "../outside.mjs")
        escaping.languageServerRoot.parent.resolve("outside.mjs").writeText("escape")
        assertCodes(discoverer().discover(escaping.request()), "typescript.packageEntrypointEscape")

        val malformedBin = fixture()
        malformedBin.languageServerRoot.resolve("package.json").writeText(
            """{"name":"typescript-language-server","version":"4.3.3","bin":{"typescript-language-server":{"path":"lib/cli.mjs"}}}""",
        )
        assertCodes(discoverer().discover(malformedBin.request()), "typescript.packageEntrypointMissing")
    }

    @Test
    fun refusesSymlinkedEntrypointAndOversizedManifest() {
        val symlinkFixture = fixture()
        val entrypoint = symlinkFixture.languageServerRoot.resolve("lib/cli.mjs")
        val outside = Files.createTempFile("refactorkit-ts-outside", ".mjs")
        Files.delete(entrypoint)
        runCatching { Files.createSymbolicLink(entrypoint, outside) }.onSuccess {
            assertCodes(discoverer().discover(symlinkFixture.request()), "typescript.toolchainPathInvalid")
        }

        val oversized = fixture()
        oversized.languageServerRoot.resolve("package.json").writeText(" ".repeat(65_537))
        assertCodes(discoverer().discover(oversized.request()), "typescript.toolchainFileLimit")
    }

    @Test
    fun managedVersionProbeExecutesOnlyConstantVersionArgument() {
        val executable = Path.of(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        ).toAbsolutePath().normalize()
        val result = ManagedNodeVersionProbe().probe(executable)
        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        assertTrue(result.getOrThrow().isNotBlank())
    }

    @Test
    fun semanticVersionIsStrictAndBounded() {
        assertEquals("5.7.2-beta.1", SemanticVersion.parse("5.7.2-beta.1").toString())
        assertEquals(null, SemanticVersion.parse("v5.7.2"))
        assertEquals(null, SemanticVersion.parse("5.7"))
        assertEquals(null, SemanticVersion.parse("05.7.2"))
        assertEquals(null, SemanticVersion.parse("5.7.2-${"x".repeat(65)}"))
    }

    private fun discoverer() = TypeScriptToolchainDiscoverer(
        nodeVersionProbe = NodeVersionProbe { Result.success("v22.12.0") },
    )

    private fun assertCodes(result: TypeScriptToolchainDiscovery, vararg expected: String) {
        val refused = assertIs<TypeScriptToolchainDiscovery.Refused>(result)
        val actual = refused.diagnostics.mapNotNull(Diagnostic::code)
        expected.forEach { assertTrue(it in actual, "missing $it in $actual") }
    }

    private fun fixture(
        packagesInsideWorkspace: Boolean = false,
        nodeInsideWorkspace: Boolean = false,
        languageServerName: String = "typescript-language-server",
        languageServerBin: String = "lib/cli.mjs",
        typeScriptVersion: String = "5.7.2",
    ): Fixture {
        val workspace = Files.createTempDirectory("refactorkit-ts-workspace")
        val external = Files.createTempDirectory("refactorkit-ts-toolchain")
        val packageBase = if (packagesInsideWorkspace) workspace.resolve("node_modules").createDirectories() else external
        val nodeBase = if (nodeInsideWorkspace) workspace.resolve("bin").createDirectories() else external
        val node = nodeBase.resolve(if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node")
        node.writeText("fake explicit node")
        node.toFile().setExecutable(true)

        val languageServerRoot = packageBase.resolve("typescript-language-server").createDirectories()
        languageServerRoot.resolve("lib").createDirectories()
        languageServerRoot.resolve("lib/cli.mjs").writeText("marker must remain untouched")
        languageServerRoot.resolve("package.json").writeText(
            """{"name":"$languageServerName","version":"4.3.3","bin":{"typescript-language-server":"$languageServerBin"},"scripts":{"postinstall":"touch SHOULD_NOT_EXIST"}}""",
        )
        val marker = languageServerRoot.resolve("marker.txt")
        marker.writeText("not executed")

        val typeScriptRoot = packageBase.resolve("typescript").createDirectories()
        typeScriptRoot.resolve("lib").createDirectories()
        typeScriptRoot.resolve("lib/tsserver.js").writeText("// compiler server")
        typeScriptRoot.resolve("package.json").writeText(
            """{"name":"typescript","version":"$typeScriptVersion","scripts":{"prepare":"touch SHOULD_NOT_EXIST"}}""",
        )
        return Fixture(workspace, node, languageServerRoot, typeScriptRoot, marker)
    }

    private data class Fixture(
        val workspace: Path,
        val node: Path,
        val languageServerRoot: Path,
        val typeScriptRoot: Path,
        val marker: Path,
    ) {
        fun request() = TypeScriptToolchainRequest(
            workspaceRoot = workspace,
            nodeExecutable = node,
            languageServerPackageRoot = languageServerRoot,
            typeScriptPackageRoot = typeScriptRoot,
        )
    }
}

package org.refactorkit.kotlin

import org.refactorkit.core.Diagnostic
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KotlinToolchainDiscoveryTest {
    @Test
    fun discoversExplicitQualifiedToolchainWithoutExecutingInputs() {
        val fixture = fixture()

        val result = KotlinToolchainDiscoverer().discover(fixture.request())

        val toolchain = assertIs<KotlinToolchainDiscovery.Available>(result).toolchain
        assertEquals("kotlin-compiler-explicit-v1", toolchain.provenance.providerId)
        assertEquals("kotlin-compiler-embeddable-k2", toolchain.provenance.backend)
        assertEquals("21.0.11", toolchain.provenance.javaVersion)
        assertEquals("2.0.21", toolchain.provenance.kotlinVersion)
        assertEquals("2.0.21-release-482", toolchain.provenance.compilerDistributionVersion)
        assertEquals(64, toolchain.provenance.projectionHash.length)
        assertEquals(
            listOf(fixture.compilerJar.toRealPath(), fixture.dependencyJar.toRealPath()),
            toolchain.compilerClasspath,
        )
        assertEquals(
            listOf("compiler-classpath-000", "java-executable", "jdk-release", "kotlin-compiler"),
            toolchain.provenance.evidence.map { it.role },
        )
        assertTrue(toolchain.provenance.evidence.all { it.sha256.length == 64 && it.size > 0 })
        assertTrue(!fixture.executionMarker.exists(), "discovery executed an explicitly configured toolchain input")
    }

    @Test
    fun projectionAndEvidenceChangeWhenCompilerContentChanges() {
        val fixture = fixture()
        val discoverer = KotlinToolchainDiscoverer()
        val first = assertIs<KotlinToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain

        compilerJar(fixture.compilerJar, "2.0.21-release-482", payload = "changed compiler payload")
        val second = assertIs<KotlinToolchainDiscovery.Available>(discoverer.discover(fixture.request())).toolchain

        assertNotEquals(first.provenance.projectionHash, second.provenance.projectionHash)
        assertNotEquals(
            first.provenance.evidence.single { it.role == "kotlin-compiler" }.sha256,
            second.provenance.evidence.single { it.role == "kotlin-compiler" }.sha256,
        )
    }

    @Test
    fun explicitConfigurationAndQualifiedVersionsAreRequired() {
        val workspace = Files.createTempDirectory("refactorkit-kotlin-workspace")
        assertCodes(
            KotlinToolchainDiscoverer().discover(KotlinToolchainRequest(workspace)),
            "kotlin.toolchainNotConfigured",
        )

        val oldJdk = fixture(javaVersion = "17.0.12")
        assertCodes(KotlinToolchainDiscoverer().discover(oldJdk.request()), "kotlin.jdkVersionUnsupported")

        val unsupportedCompiler = fixture(kotlinDistributionVersion = "2.1.0")
        assertCodes(
            KotlinToolchainDiscoverer(KotlinToolchainDiscoveryPolicy(supportedKotlinVersions = setOf("2.0.21")))
                .discover(unsupportedCompiler.request()),
            "kotlin.compilerVersionUnsupported",
        )

        val malformedJdk = fixture()
        malformedJdk.jdkHome.resolve("release").writeText("JAVA_VERSION=21\n")
        assertCodes(KotlinToolchainDiscoverer().discover(malformedJdk.request()), "kotlin.jdkMetadataInvalid")

        val classpathLimited = fixture()
        assertCodes(
            KotlinToolchainDiscoverer(KotlinToolchainDiscoveryPolicy(maxClasspathEntries = 0))
                .discover(classpathLimited.request()),
            "kotlin.compilerClasspathLimit",
        )
    }

    @Test
    fun workspaceLocalToolchainRequiresExplicitPolicy() {
        val fixture = fixture(toolchainInsideWorkspace = true)
        assertCodes(KotlinToolchainDiscoverer().discover(fixture.request()), "kotlin.workspaceToolchainRefused")

        val allowed = KotlinToolchainDiscoverer(
            KotlinToolchainDiscoveryPolicy(allowWorkspaceLocalToolchain = true),
        ).discover(fixture.request())
        assertIs<KotlinToolchainDiscovery.Available>(allowed)
    }

    @Test
    fun compilerIdentityAndClasspathAreValidatedWithoutLoadingClasses() {
        val wrongTitle = fixture(compilerTitle = "not-kotlin")
        assertCodes(KotlinToolchainDiscoverer().discover(wrongTitle.request()), "kotlin.compilerIdentityInvalid")

        val missingSentinel = fixture(includeSentinel = false)
        assertCodes(KotlinToolchainDiscoverer().discover(missingSentinel.request()), "kotlin.compilerIdentityInvalid")

        val invalidClasspath = fixture()
        invalidClasspath.dependencyJar.writeText("not a jar")
        assertCodes(
            KotlinToolchainDiscoverer().discover(invalidClasspath.request()),
            "kotlin.compilerClasspathInvalid",
        )

        val duplicate = fixture()
        assertCodes(
            KotlinToolchainDiscoverer().discover(duplicate.request(compilerClasspath = listOf(duplicate.compilerJar))),
            "kotlin.compilerClasspathInvalid",
        )
    }

    @Test
    fun refusesSymlinkedAndOversizedInputsWhenSupportedByHost() {
        val symlinkFixture = fixture()
        val outside = Files.createTempFile("refactorkit-kotlin-outside", ".jar")
        Files.delete(symlinkFixture.compilerJar)
        runCatching { Files.createSymbolicLink(symlinkFixture.compilerJar, outside) }.onSuccess {
            assertCodes(KotlinToolchainDiscoverer().discover(symlinkFixture.request()), "kotlin.toolchainPathInvalid")
        }

        val limited = fixture()
        assertCodes(
            KotlinToolchainDiscoverer(KotlinToolchainDiscoveryPolicy(maxJarBytes = 64, maxTotalBytes = 128))
                .discover(limited.request()),
            "kotlin.toolchainFileLimit",
        )
    }

    private fun assertCodes(result: KotlinToolchainDiscovery, vararg expected: String) {
        val refused = assertIs<KotlinToolchainDiscovery.Refused>(result)
        val actual = refused.diagnostics.mapNotNull(Diagnostic::code)
        expected.forEach { assertTrue(it in actual, "missing $it in $actual") }
    }

    private fun fixture(
        javaVersion: String = "21.0.11",
        kotlinDistributionVersion: String = "2.0.21-release-482",
        compilerTitle: String = "kotlin-compiler-embeddable",
        includeSentinel: Boolean = true,
        toolchainInsideWorkspace: Boolean = false,
    ): Fixture {
        val workspace = Files.createTempDirectory("refactorkit-kotlin-workspace")
        val toolchainRoot = if (toolchainInsideWorkspace) {
            workspace.resolve(".toolchain").createDirectories()
        } else Files.createTempDirectory("refactorkit-kotlin-toolchain")
        val jdkHome = toolchainRoot.resolve("jdk").createDirectories()
        val bin = jdkHome.resolve("bin").createDirectories()
        val marker = toolchainRoot.resolve("EXECUTED")
        val java = bin.resolve(if (isWindows()) "java.exe" else "java")
        java.writeText(if (isWindows()) {
            "@echo off\r\necho executed>${marker}\r\n"
        } else {
            "#!/bin/sh\nprintf executed > '${marker}'\n"
        })
        java.toFile().setExecutable(true)
        jdkHome.resolve("release").writeText(
            "IMPLEMENTOR=\"Test JDK\"\nJAVA_VERSION=\"$javaVersion\"\nOS_ARCH=\"test\"\n",
        )

        val compiler = toolchainRoot.resolve("kotlin-compiler-embeddable.jar")
        compilerJar(compiler, kotlinDistributionVersion, compilerTitle, includeSentinel)
        val dependency = toolchainRoot.resolve("kotlin-stdlib.jar")
        simpleJar(dependency, "kotlin/Unit.class", "dependency")
        return Fixture(workspace, jdkHome, compiler, dependency, marker)
    }

    private fun compilerJar(
        path: Path,
        distributionVersion: String,
        title: String = "kotlin-compiler-embeddable",
        includeSentinel: Boolean = true,
        payload: String = "compiler payload",
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Implementation-Vendor", "JetBrains")
            mainAttributes.putValue("Implementation-Title", title)
            mainAttributes.putValue("Implementation-Version", distributionVersion)
        }
        JarOutputStream(Files.newOutputStream(path), manifest).use { output ->
            if (includeSentinel) {
                output.putNextEntry(JarEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"))
                output.write(payload.toByteArray())
                output.closeEntry()
            } else {
                output.putNextEntry(JarEntry("example/Other.class"))
                output.write(payload.toByteArray())
                output.closeEntry()
            }
        }
    }

    private fun simpleJar(path: Path, entry: String, content: String) {
        JarOutputStream(Files.newOutputStream(path)).use { output ->
            output.putNextEntry(JarEntry(entry))
            output.write(content.toByteArray())
            output.closeEntry()
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows")

    private data class Fixture(
        val workspace: Path,
        val jdkHome: Path,
        val compilerJar: Path,
        val dependencyJar: Path,
        val executionMarker: Path,
    ) {
        fun request(compilerClasspath: List<Path> = listOf(dependencyJar)) = KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = jdkHome,
            compilerJar = compilerJar,
            compilerClasspath = compilerClasspath,
        )
    }
}

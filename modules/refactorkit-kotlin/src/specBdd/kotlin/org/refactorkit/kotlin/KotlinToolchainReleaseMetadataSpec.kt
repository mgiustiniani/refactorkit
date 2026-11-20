package org.refactorkit.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class KotlinToolchainReleaseMetadataSpec : StringSpec({
    "accepts a qualified Java version alongside a bounded opaque vendor field" {
        val fixture = fixture(
            """
            IMPLEMENTOR="GraalVM Community"
            JAVA_VERSION="21.0.11"
            COMMIT_INFO={"compiler":{"commit.rev":"0123456789abcdef"}}
            MODULES="java.base java.compiler"
            """.trimIndent() + "\n",
        )

        KotlinToolchainDiscoverer().discover(fixture.request())
            .shouldBeInstanceOf<KotlinToolchainDiscovery.Available>()
    }

    "still refuses an unquoted Java version even when vendor metadata is present" {
        val fixture = fixture(
            """
            IMPLEMENTOR="GraalVM Community"
            JAVA_VERSION=21.0.11
            COMMIT_INFO={"compiler":{"commit.rev":"0123456789abcdef"}}
            """.trimIndent() + "\n",
        )

        val refusal = KotlinToolchainDiscoverer().discover(fixture.request())
            .shouldBeInstanceOf<KotlinToolchainDiscovery.Refused>()
        refusal.diagnostics.mapNotNull { it.code } shouldContain "kotlin.jdkMetadataInvalid"
    }
}) {
    private companion object {
        fun fixture(releaseMetadata: String): Fixture {
            val workspace = Files.createTempDirectory("refactorkit-kotlin-release-workspace")
            val toolchainRoot = Files.createTempDirectory("refactorkit-kotlin-release-toolchain")
            val jdkHome = toolchainRoot.resolve("jdk").createDirectories()
            val javaExecutable = jdkHome.resolve("bin").createDirectories()
                .resolve(if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            javaExecutable.writeText("#!/bin/sh\nexit 0\n")
            javaExecutable.toFile().setExecutable(true)
            jdkHome.resolve("release").writeText(releaseMetadata)

            val compilerJar = toolchainRoot.resolve("kotlin-compiler-embeddable.jar")
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes.putValue("Implementation-Vendor", "JetBrains")
                mainAttributes.putValue("Implementation-Title", "kotlin-compiler-embeddable")
                mainAttributes.putValue("Implementation-Version", "2.0.21-release-482")
            }
            JarOutputStream(Files.newOutputStream(compilerJar), manifest).use { output ->
                output.putNextEntry(JarEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }

            val stdlib = toolchainRoot.resolve("kotlin-stdlib.jar")
            JarOutputStream(Files.newOutputStream(stdlib)).use { output ->
                output.putNextEntry(JarEntry("kotlin/Unit.class"))
                output.write(byteArrayOf(1, 2, 3))
                output.closeEntry()
            }
            return Fixture(workspace, jdkHome, compilerJar, stdlib)
        }
    }

    private data class Fixture(
        val workspace: Path,
        val jdkHome: Path,
        val compilerJar: Path,
        val stdlib: Path,
    ) {
        fun request() = KotlinToolchainRequest(
            workspaceRoot = workspace,
            jdkHome = jdkHome,
            compilerJar = compilerJar,
            compilerClasspath = listOf(stdlib),
        )
    }
}

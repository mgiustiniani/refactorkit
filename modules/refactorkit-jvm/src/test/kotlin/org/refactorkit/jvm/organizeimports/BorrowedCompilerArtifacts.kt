package org.refactorkit.jvm.organizeimports

import org.refactorkit.kotlin.KotlinSemanticToolchain
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Observes the real fixture inputs without copying, changing permissions or backing up JARs. */
internal class BorrowedCompilerArtifacts(private val toolchain: KotlinSemanticToolchain) {
    fun assertSharedAndUnchanged() {
        val configured = System.getProperty("kotlin.compiler.test.classpath").split(File.pathSeparator)
            .map { Path.of(it).toAbsolutePath().normalize() }.toSet()
        assertEquals(8, toolchain.compilerClasspath.size)
        assertTrue(toolchain.compilerJar in toolchain.compilerClasspath)
        for (artifact in toolchain.compilerClasspath) {
            assertTrue(artifact.toAbsolutePath().normalize() in configured,
                "Fixture copied a compiler artifact instead of borrowing the configured input: $artifact")
            val evidence = toolchain.provenance.evidence.single { it.path == artifact }
            assertEquals(evidence.size, Files.size(artifact))
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(artifact).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    digest.update(buffer, 0, size)
                }
            }
            assertEquals(evidence.sha256, digest.digest().joinToString("") { "%02x".format(it) },
                "Shared compiler input was modified: $artifact")
        }
    }

    fun assertPreservedBy(cleanup: () -> Unit) {
        assertSharedAndUnchanged()
        cleanup()
        assertSharedAndUnchanged()
    }
}

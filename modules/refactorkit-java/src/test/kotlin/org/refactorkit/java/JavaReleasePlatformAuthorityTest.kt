package org.refactorkit.java

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class JavaReleasePlatformAuthorityTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `attests exact configured release signatures with stable identity`() {
        val jdk = syntheticJdk("8", "java.base/java/lang/Object.sig")
        val resolver = JavaReleasePlatformAuthorityResolver()

        val first = assertIs<JavaReleasePlatformResolution.Available>(resolver.resolve(jdk, 8)).authority
        val second = assertIs<JavaReleasePlatformResolution.Available>(resolver.resolve(jdk, 8)).authority

        assertEquals(8, first.release)
        assertEquals(jdk.toRealPath(), first.platformHome)
        assertEquals(64, first.jdkReleaseMetadataSha256.length)
        assertEquals(64, first.signatureArchiveSha256.length)
        assertEquals(first.identitySha256, second.identitySha256)
    }

    @Test
    fun `release identity changes when immutable signature evidence changes`() {
        val jdk = syntheticJdk("L", "java.base/java/lang/Object.sig")
        val resolver = JavaReleasePlatformAuthorityResolver()
        val before = assertIs<JavaReleasePlatformResolution.Available>(resolver.resolve(jdk, 21)).authority
        writeCtSym(jdk, "L", "java.base/java/lang/String.sig")

        val after = assertIs<JavaReleasePlatformResolution.Available>(resolver.resolve(jdk, 21)).authority

        assertNotEquals(before.signatureArchiveSha256, after.signatureArchiveSha256)
        assertNotEquals(before.identitySha256, after.identitySha256)
    }

    @Test
    fun `refuses a release absent from configured signatures`() {
        val jdk = syntheticJdk("8", "java.base/java/lang/Object.sig")

        val refused = assertIs<JavaReleasePlatformResolution.Refused>(
            JavaReleasePlatformAuthorityResolver().resolve(jdk, 17),
        )

        assertEquals("java.platform.releaseSignaturesUnavailable", refused.code)
    }

    @Test
    fun `refuses malformed and over-limit signature archives`() {
        val malformed = temporary.resolve("malformed")
        Files.createDirectories(malformed.resolve("lib"))
        Files.writeString(malformed.resolve("release"), "JAVA_VERSION=\"21\"\n")
        Files.writeString(malformed.resolve("lib/ct.sym"), "not a zip")
        val malformedResult = assertIs<JavaReleasePlatformResolution.Refused>(
            JavaReleasePlatformAuthorityResolver().resolve(malformed, 21),
        )
        assertEquals("java.platform.signaturesMalformed", malformedResult.code)

        val bounded = syntheticJdk("L", "java.base/java/lang/Object.sig")
        val boundedResult = assertIs<JavaReleasePlatformResolution.Refused>(
            JavaReleasePlatformAuthorityResolver(maximumArchiveEntries = 0).resolve(bounded, 21),
        )
        assertEquals("java.platform.evidenceLimitExceeded", boundedResult.code)
    }

    @Test
    fun `refuses implicit unsupported or incomplete platform inputs`() {
        val unsupported = assertIs<JavaReleasePlatformResolution.Refused>(
            JavaReleasePlatformAuthorityResolver().resolve(temporary, 26),
        )
        assertEquals("java.platform.releaseUnsupported", unsupported.code)

        val absent = assertIs<JavaReleasePlatformResolution.Refused>(
            JavaReleasePlatformAuthorityResolver().resolve(temporary.resolve("absent"), 21),
        )
        assertEquals("java.platform.homeUnavailable", absent.code)
    }

    private fun syntheticJdk(token: String, entry: String): Path {
        val jdk = temporary.resolve("jdk-$token-${System.nanoTime()}")
        Files.createDirectories(jdk.resolve("lib"))
        Files.writeString(
            jdk.resolve("release"),
            "JAVA_VERSION=\"21\"\nIMPLEMENTOR=\"fixture\"\nMODULES=\"java.base java.se\"\n",
        )
        Files.write(jdk.resolve("lib/modules"), byteArrayOf(4, 5, 6))
        Files.write(jdk.resolve("lib/jrt-fs.jar"), byteArrayOf(7, 8, 9))
        writeCtSym(jdk, token, entry)
        return jdk
    }

    private fun writeCtSym(jdk: Path, token: String, entry: String) {
        ZipOutputStream(Files.newOutputStream(jdk.resolve("lib/ct.sym"))).use { archive ->
            archive.putNextEntry(ZipEntry("$token/$entry"))
            archive.write(byteArrayOf(1, 2, 3))
            archive.closeEntry()
        }
    }
}

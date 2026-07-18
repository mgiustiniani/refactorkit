package org.refactorkit.java

import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Immutable evidence for one explicitly configured Java release platform.
 *
 * This evidence is deliberately not JDT authority by itself. A semantic
 * environment must consume and bind this identity before advertising
 * authoritative diagnostics or mutation.
 */
data class JavaReleasePlatformAuthority(
    val release: Int,
    val platformHome: Path,
    val jdkReleaseMetadataSha256: String,
    val signatureArchive: Path,
    val signatureArchiveSha256: String,
    val platformRelease: Int,
    val currentSystemModules: Path?,
    val currentSystemModulesSha256: String?,
    val identitySha256: String,
)

sealed interface JavaReleasePlatformResolution {
    data class Available(val authority: JavaReleasePlatformAuthority) : JavaReleasePlatformResolution
    data class Refused(val code: String, val message: String) : JavaReleasePlatformResolution
}

/** Resolves bounded, hash-attested `ct.sym` evidence without executing the JDK. */
class JavaReleasePlatformAuthorityResolver(
    private val maximumArchiveEntries: Int = 250_000,
) {
    fun resolve(configuredJdkHome: Path, release: Int): JavaReleasePlatformResolution {
        if (release !in 8..25) return refused(
            "java.platform.releaseUnsupported",
            "Java release $release is outside the qualified 8 through 25 range",
        )
        val home = try {
            configuredJdkHome.toAbsolutePath().normalize().toRealPath()
        } catch (_: Exception) {
            return refused("java.platform.homeUnavailable", "Configured JDK home is unavailable")
        }
        if (!Files.isDirectory(home)) return refused(
            "java.platform.homeUnavailable",
            "Configured JDK home is not a directory",
        )
        val releaseMetadata = home.resolve("release")
        val signatures = home.resolve("lib").resolve("ct.sym")
        val releaseText = runCatching { Files.readString(releaseMetadata) }.getOrNull()
            ?: return refused("java.platform.releaseMetadataUnavailable", "Configured JDK release metadata could not be read")
        val platformRelease = Regex("(?m)^JAVA_VERSION=\\\"?(\\d+)").find(releaseText)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: return refused("java.platform.releaseMetadataMalformed", "Configured JDK release metadata has no Java version")
        if (!Files.isRegularFile(releaseMetadata)) return refused(
            "java.platform.releaseMetadataUnavailable",
            "Configured JDK release metadata is unavailable",
        )
        if (!Files.isRegularFile(signatures)) return refused(
            "java.platform.signaturesUnavailable",
            "Configured JDK ct.sym signatures are unavailable",
        )
        val metadataHash = stableSha256(releaseMetadata) ?: return refused(
            "java.platform.evidenceDrift",
            "Configured JDK release metadata changed while it was being attested",
        )
        val signatureHash = stableSha256(signatures) ?: return refused(
            "java.platform.evidenceDrift",
            "Configured JDK signatures changed while they were being attested",
        )
        val releaseToken = releaseToken(release)
        val containsRelease = try {
            ZipFile(signatures.toFile()).use { archive ->
                val entries = archive.entries()
                var count = 0
                var found = false
                while (entries.hasMoreElements()) {
                    if (++count > maximumArchiveEntries) return refused(
                        "java.platform.evidenceLimitExceeded",
                        "Configured JDK signature archive exceeds the bounded entry limit",
                    )
                    val name = entries.nextElement().name
                    val slash = name.indexOf('/')
                    if (slash > 0 && releaseToken in name.substring(0, slash)) found = true
                }
                found
            }
        } catch (_: ZipException) {
            return refused("java.platform.signaturesMalformed", "Configured JDK ct.sym is malformed")
        } catch (_: Exception) {
            return refused("java.platform.signaturesUnavailable", "Configured JDK ct.sym could not be inspected")
        }
        if (!containsRelease) return refused(
            "java.platform.releaseSignaturesUnavailable",
            "Configured JDK ct.sym does not contain Java $release signatures",
        )
        val currentRelease = release == platformRelease
        val systemModules = home.resolve("lib/modules").takeIf { currentRelease }
        val systemModulesHash = if (currentRelease) {
            if (!releaseText.lineSequence().firstOrNull { it.startsWith("MODULES=") }.orEmpty()
                    .removePrefix("MODULES=").contains("java.se")) return refused(
                "java.platform.currentModulesIncomplete",
                "Configured current-release runtime is not a complete java.se image",
            )
            if (!Files.isRegularFile(systemModules) || !Files.isRegularFile(home.resolve("lib/jrt-fs.jar"))) return refused(
                "java.platform.currentModulesUnavailable",
                "Configured current-release system module image is unavailable",
            )
            stableSha256(requireNotNull(systemModules)) ?: return refused(
                "java.platform.evidenceDrift",
                "Configured current-release system modules changed while they were being attested",
            )
        } else null
        val identity = sha256(
            ("java-release-platform-v2\n$release\n$platformRelease\n${home.toString().replace('\\', '/')}\n" +
                "$metadataHash\n$signatureHash\n${systemModulesHash.orEmpty()}\n").toByteArray(Charsets.UTF_8),
        )
        return JavaReleasePlatformResolution.Available(
            JavaReleasePlatformAuthority(
                release = release,
                platformHome = home,
                jdkReleaseMetadataSha256 = metadataHash,
                signatureArchive = signatures.toRealPath(),
                signatureArchiveSha256 = signatureHash,
                platformRelease = platformRelease,
                currentSystemModules = systemModules?.toRealPath(),
                currentSystemModulesSha256 = systemModulesHash,
                identitySha256 = identity,
            ),
        )
    }

    private fun stableSha256(path: Path): String? {
        val first = hashFile(path) ?: return null
        val second = hashFile(path) ?: return null
        return first.takeIf { it == second }
    }

    private fun hashFile(path: Path): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(Files.newInputStream(path)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().toHex()
    } catch (_: Exception) {
        null
    }

    private fun releaseToken(release: Int): Char = when (release) {
        8, 9 -> ('0'.code + release).toChar()
        else -> ('A'.code + release - 10).toChar()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun refused(code: String, message: String) = JavaReleasePlatformResolution.Refused(code, message)
}

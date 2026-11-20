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
    val legacyBootArchives: Map<Path, String> = emptyMap(),
)

sealed interface JavaReleasePlatformResolution {
    data class Available(val authority: JavaReleasePlatformAuthority) : JavaReleasePlatformResolution
    data class Refused(val code: String, val message: String) : JavaReleasePlatformResolution
}

/** Metadata-only eligibility deliberately does not consult the DOM parser ceiling. */
internal object JavaMetadataReleasePlatformEligibilityPolicy {
    fun decide(
        release: Int,
        compilerSupportsRelease: Boolean,
        platformRelease: Int,
        containsExactSignature: Boolean,
    ): JavaReleasePlatformEligibility {
        if (release < JavaParserReleaseSupport.MINIMUM_RELEASE || !compilerSupportsRelease) {
            return JavaReleasePlatformEligibility.Refused(
                "java.platform.releaseUnsupported",
                "Java release $release is outside the installed compiler metadata range",
            )
        }
        if (release > platformRelease || !containsExactSignature) {
            return JavaReleasePlatformEligibility.Refused(
                "java.platform.releaseSignaturesUnavailable",
                "Configured JDK release $platformRelease does not attest Java $release in ct.sym",
            )
        }
        return JavaReleasePlatformEligibility.Eligible
    }
}

/** Resolves hash-attested modular or legacy platform evidence without executing the JDK. */
class JavaReleasePlatformAuthorityResolver(
    private val maximumArchiveEntries: Int = 250_000,
) {
    fun resolve(configuredJdkHome: Path, release: Int): JavaReleasePlatformResolution =
        resolve(configuredJdkHome, release, requireParserSupport = true)

    /** Attests compiler/platform metadata without granting source parser authority. */
    fun resolveMetadata(configuredJdkHome: Path, release: Int): JavaReleasePlatformResolution =
        resolve(configuredJdkHome, release, requireParserSupport = false)

    /** Native API authority is independent of source grammar, but requires the exact current SDK. */
    fun resolveNativeCompilerApis(configuredJdkHome: Path, release: Int): JavaReleasePlatformResolution {
        if (!JavaReleasePlatformEligibilityPolicy.isQualifiedNativeApi(release)) return refused(
            "java.platform.compilerApiUnqualified", "The native compiler API profile is not qualified",
        )
        return when (val resolved = resolveMetadata(configuredJdkHome, release)) {
            is JavaReleasePlatformResolution.Refused -> resolved
            is JavaReleasePlatformResolution.Available -> when (val eligibility = JavaReleasePlatformEligibilityPolicy.decide(
                release, parserSupportsRelease = false, resolved.authority.platformRelease,
                // Available metadata has already attested the exact signature archive and current modules.
                containsExactSignature = true, use = JavaPlatformUse.NATIVE_COMPILER_API,
            )) {
                JavaReleasePlatformEligibility.Eligible -> resolved
                is JavaReleasePlatformEligibility.Refused -> refused(eligibility.code, eligibility.message)
            }
        }
    }

    /** Re-attest the captured profile without treating a native API number as source grammar. */
    fun revalidate(authority: JavaReleasePlatformAuthority): JavaReleasePlatformResolution =
        if (authority.release == authority.platformRelease &&
            JavaReleasePlatformEligibilityPolicy.isQualifiedNativeApi(authority.release)) {
            resolveNativeCompilerApis(authority.platformHome, authority.release)
        } else resolve(authority.platformHome, authority.release)

    private fun resolve(
        configuredJdkHome: Path,
        release: Int,
        requireParserSupport: Boolean,
    ): JavaReleasePlatformResolution {
        val parserSupportsRelease = JavaParserReleaseSupport.supportsRelease(release)
        val compilerSupportsRelease = JavaProjectBuildMetadataSourceLevelPolicy.supportsRelease(release)
        if (requireParserSupport && !parserSupportsRelease) return refused(
            "java.platform.releaseUnsupported",
            "Java release $release is outside the installed JDT parser/compiler range " +
                "${JavaParserReleaseSupport.MINIMUM_RELEASE} through ${JavaParserReleaseSupport.maximumSupportedRelease}",
        )
        if (!requireParserSupport && !compilerSupportsRelease) return refused(
            "java.platform.releaseUnsupported",
            "Java release $release is outside the installed compiler metadata range " +
                "${JavaProjectBuildMetadataSourceLevelPolicy.MIN_SUPPORTED} through " +
                JavaProjectBuildMetadataSourceLevelPolicy.MAX_SUPPORTED,
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
        val releaseValues = Regex("(?m)^JAVA_VERSION=(?:\"([^\"\r\n]+)\"|([0-9][^\"\r\n]*))\r?$")
            .findAll(releaseText).toList()
        val version = releaseValues.singleOrNull()?.groupValues?.let { if (it[1].isNotEmpty()) it[1] else it[2] }
        val legacyVersion = version?.startsWith("1.") == true
        val platformRelease = version?.removePrefix("1.")?.takeWhile(Char::isDigit)?.toIntOrNull()
            ?.takeIf { !legacyVersion || it <= JavaLegacy8Platform.RELEASE }
            ?: return refused("java.platform.releaseMetadataMalformed", "Configured JDK release metadata has no Java version")
        val legacyPlatform = platformRelease == JavaLegacy8Platform.RELEASE
        if (!Files.isRegularFile(releaseMetadata)) return refused(
            "java.platform.releaseMetadataUnavailable",
            "Configured JDK release metadata is unavailable",
        )
        if (!Files.isRegularFile(signatures)) return refused(
            "java.platform.signaturesUnavailable",
            "Configured JDK ct.sym signatures are unavailable",
        )
        if (legacyPlatform && !runCatching { JavaLegacy8Platform.safeInput(home, signatures) }.getOrDefault(false)) {
            return refused("java.platform.signaturesUnavailable", "Configured Java 8 symbol archive is unavailable")
        }
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
                if (legacyPlatform) release == JavaLegacy8Platform.RELEASE && JavaLegacy8Platform.hasPlatformSignatures(archive)
                else found
            }
        } catch (_: ZipException) {
            return refused("java.platform.signaturesMalformed", "Configured JDK ct.sym is malformed")
        } catch (_: Exception) {
            return refused("java.platform.signaturesUnavailable", "Configured JDK ct.sym could not be inspected")
        }
        val eligibility = if (requireParserSupport) JavaReleasePlatformEligibilityPolicy.decide(
            release = release,
            parserSupportsRelease = parserSupportsRelease,
            platformRelease = platformRelease,
            containsExactSignature = containsRelease,
        ) else JavaMetadataReleasePlatformEligibilityPolicy.decide(
            release = release,
            compilerSupportsRelease = compilerSupportsRelease,
            platformRelease = platformRelease,
            containsExactSignature = containsRelease,
        )
        when (eligibility) {
            JavaReleasePlatformEligibility.Eligible -> Unit
            is JavaReleasePlatformEligibility.Refused -> return refused(eligibility.code, eligibility.message)
        }
        val currentRelease = release == platformRelease && !legacyPlatform
        val systemModules = home.resolve("lib/modules").takeIf { currentRelease }
        val systemModulesHash = if (currentRelease) {
            val configuredModules = releaseText.lineSequence().firstOrNull { it.startsWith("MODULES=") }.orEmpty()
                .removePrefix("MODULES=")
            if (listOf("java.se", "jdk.httpserver").any { it !in configuredModules.split(' ', '\"') }) return refused(
                "java.platform.currentModulesIncomplete",
                "Configured current-release runtime lacks required java.se or jdk.httpserver modules",
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
        val legacyBoot = linkedMapOf<Path, String>()
        if (legacyPlatform) {
            for (path in JavaLegacy8Platform.supplementalArchives(home)) {
                if (!runCatching { JavaLegacy8Platform.safeInput(home, path) }.getOrDefault(false)) return refused(
                    "java.platform.signaturesUnavailable", "Configured Java 8 security APIs are unavailable",
                )
                legacyBoot[path] = stableSha256(path) ?: return refused(
                    "java.platform.evidenceDrift", "Configured Java 8 boot archive changed while it was being attested",
                )
            }
        }
        val legacyIdentity = legacyBoot.entries.joinToString("") { (path, hash) -> "${path.fileName}:$hash\n" }
        val identity = sha256(
            ("java-release-platform-v2\n$release\n$platformRelease\n${home.toString().replace('\\', '/')}\n" +
                "$metadataHash\n$signatureHash\n${systemModulesHash.orEmpty()}\n$legacyIdentity").toByteArray(Charsets.UTF_8),
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
                legacyBootArchives = java.util.Collections.unmodifiableMap(legacyBoot),
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

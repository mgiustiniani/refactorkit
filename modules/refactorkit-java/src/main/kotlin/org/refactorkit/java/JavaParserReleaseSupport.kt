package org.refactorkit.java

import org.eclipse.jdt.core.JavaCore
import org.eclipse.jdt.core.dom.AST

/** Release support is bounded by both the installed DOM grammar and ECJ's source-level support. */
internal object JavaParserReleaseSupport {
    const val MINIMUM_RELEASE = 8

    val maximumSupportedRelease: Int
        get() {
            val ceiling = minOf(AST.getJLSLatest(), JavaCore.latestSupportedJavaVersion().toIntOrNull() ?: 0)
            return (ceiling downTo MINIMUM_RELEASE).firstOrNull(::supportsRelease) ?: MINIMUM_RELEASE - 1
        }

    fun supportsRelease(release: Int): Boolean {
        if (release !in MINIMUM_RELEASE..AST.getJLSLatest()) return false
        val version = if (release == 8) JavaCore.VERSION_1_8 else release.toString()
        return runCatching { JavaCore.isJavaSourceVersionSupportedByCompiler(version) }.getOrDefault(false)
    }
}

internal sealed interface JavaReleasePlatformEligibility {
    data object Eligible : JavaReleasePlatformEligibility
    data class Refused(val code: String, val message: String) : JavaReleasePlatformEligibility
}

internal enum class JavaPlatformUse { SOURCE_RELEASE, NATIVE_COMPILER_API }

/** Pure conjunction used before publishing platform authority. */
internal object JavaReleasePlatformEligibilityPolicy {
    fun isQualifiedNativeApi(release: Int): Boolean = release in setOf(21, 25, 26, 27)

    fun decide(
        release: Int,
        parserSupportsRelease: Boolean,
        platformRelease: Int,
        containsExactSignature: Boolean,
        use: JavaPlatformUse = JavaPlatformUse.SOURCE_RELEASE,
    ): JavaReleasePlatformEligibility {
        if (use == JavaPlatformUse.NATIVE_COMPILER_API &&
            (!isQualifiedNativeApi(release) || release != platformRelease)) {
            return JavaReleasePlatformEligibility.Refused(
                "java.platform.compilerApiUnqualified",
                "The native compiler API requires a separately qualified same-release SDK",
            )
        }
        if (release < JavaParserReleaseSupport.MINIMUM_RELEASE ||
            use == JavaPlatformUse.SOURCE_RELEASE && !parserSupportsRelease) {
            return JavaReleasePlatformEligibility.Refused(
                "java.platform.releaseUnsupported",
                "Java release $release is not supported by the installed JDT parser and compiler",
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

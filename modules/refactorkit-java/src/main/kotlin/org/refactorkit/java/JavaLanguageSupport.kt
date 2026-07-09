package org.refactorkit.java

/**
 * Java project compatibility policy.
 *
 * RefactorKit must accept Java 8-era projects while its Java adapter evolves to
 * understand source constructs and type forms through Java 25.
 */
object JavaLanguageSupport {
    const val MIN_SOURCE_VERSION: Int = 8
    const val MAX_SOURCE_VERSION: Int = 25

    val supportedSourceVersions: IntRange = MIN_SOURCE_VERSION..MAX_SOURCE_VERSION

    fun supportsSourceVersion(version: Int): Boolean = version in supportedSourceVersions
}

package org.refactorkit.java

import org.refactorkit.core.SourceFile

/** Conservative boundary: generated Java is analyzed but never rewritten. */
object JavaGeneratedSourcePolicy {
    fun reason(file: SourceFile): String? {
        val normalizedPath = file.path.toString().replace('\\', '/').lowercase()
        if (GENERATED_PATH_SEGMENTS.any(normalizedPath::contains) || isBuildOutputPath(normalizedPath)) {
            return "path is inside a generated-source or build-output location"
        }
        val prefix = file.content.take(4096)
        if (GENERATED_ANNOTATION.containsMatchIn(prefix)) {
            return "source declares a generated-code annotation"
        }
        if (GENERATED_HEADER.containsMatchIn(prefix)) {
            return "source header identifies generated code"
        }
        return null
    }

    private val GENERATED_PATH_SEGMENTS = listOf(
        "generated/",
        "generated-sources/",
        "generated-test-sources/",
        "build/generated/",
        "target/generated-sources/",
    )

    private fun isBuildOutputPath(path: String): Boolean {
        val sourceRoot = Regex("(?:^|/)src/(?:main|test)/java/").find(path)?.range?.first ?: Int.MAX_VALUE
        val output = Regex("(?:^|/)(?:target|build)/").find(path)?.range?.first ?: return false
        return output < sourceRoot
    }
    private val GENERATED_ANNOTATION = Regex(
        """@(?:javax\.annotation\.|javax\.annotation\.processing\.|jakarta\.annotation\.)?Generated\b""",
    )
    private val GENERATED_HEADER = Regex(
        """(?im)^\s*(?://|/\*+|\*)\s*(?:generated|auto-generated|do not edit)\b""",
    )
}

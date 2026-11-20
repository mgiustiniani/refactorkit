package org.refactorkit.java

import javax.lang.model.SourceVersion

internal object JavaSdkCompilerArguments {
    private val standardOptions = Regex(
        "(?:-Xlint(?::[A-Za-z0-9_,+\\-]+)?|-g(?::[A-Za-z,]+)?|-parameters|-deprecation|-Werror|-nowarn|-verbose|-proc:(?:none|only|full))",
    )

    // Annotation processor options are metadata. Diagnostics run with processing disabled.
    fun isApiNeutral(argument: String): Boolean {
        if (standardOptions.matches(argument)) return true
        if (!argument.startsWith("-A") || argument.length > 16_384 ||
            argument.any { it == '\u0000' || it == '\r' || it == '\n' }) return false

        val key = argument.substring(2).substringBefore('=')
        return key.length <= 512 && key.split('.').all { SourceVersion.isIdentifier(it) }
    }
}

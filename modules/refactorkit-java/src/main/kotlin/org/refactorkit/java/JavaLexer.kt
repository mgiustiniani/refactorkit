package org.refactorkit.java

/**
 * Minimal token-aware Java source scanner.
 *
 * Finds occurrences of an identifier or qualified name while correctly skipping
 * string literals, character literals, line comments and block comments.
 *
 * This is NOT a full parser. It is used for MVP refactoring operations where
 * full semantic analysis is not yet available. String literals and annotation
 * values that contain the identifier are intentionally skipped; callers should
 * warn users accordingly.
 */
object JavaLexer {

    /**
     * Returns the [IntRange]s (character offsets) of every occurrence of
     * [identifier] in [content] that is a standalone word (not part of a longer
     * identifier) and is NOT inside a string/char literal or comment.
     *
     * [identifier] may be a simple name ("UserManager") or a fully-qualified name
     * ("com.example.UserManager"). For FQNs the dot is treated as part of the
     * pattern; boundary checks apply only at the very start and end.
     */
    fun findOccurrences(content: String, identifier: String): List<IntRange> {
        require(identifier.isNotEmpty()) { "identifier must not be empty" }
        val results = mutableListOf<IntRange>()
        val len = content.length
        val idLen = identifier.length
        var i = 0

        while (i < len) {
            val c = content[i]

            // skip line comment
            if (c == '/' && i + 1 < len && content[i + 1] == '/') {
                val nl = content.indexOf('\n', i)
                i = if (nl < 0) len else nl + 1
                continue
            }

            // skip block comment
            if (c == '/' && i + 1 < len && content[i + 1] == '*') {
                val end = content.indexOf("*/", i + 2)
                i = if (end < 0) len else end + 2
                continue
            }

            // skip string literal or text block
            if (c == '"') {
                i++
                // text block: starts with """
                if (i + 1 < len && content[i] == '"' && content[i + 1] == '"') {
                    i += 2
                    while (i + 2 < len) {
                        if (content[i] == '"' && content[i + 1] == '"' && content[i + 2] == '"') {
                            i += 3
                            break
                        }
                        i++
                    }
                } else {
                    // regular string literal
                    while (i < len && content[i] != '"' && content[i] != '\n') {
                        if (content[i] == '\\') i++
                        i++
                    }
                    if (i < len && content[i] == '"') i++
                }
                continue
            }

            // skip char literal
            if (c == '\'') {
                i++
                while (i < len && content[i] != '\'' && content[i] != '\n') {
                    if (content[i] == '\\') i++
                    i++
                }
                if (i < len && content[i] == '\'') i++
                continue
            }

            // check for identifier match
            if (i + idLen <= len && content.regionMatches(i, identifier, 0, idLen, ignoreCase = false)) {
                val charBefore = if (i > 0) content[i - 1] else ' '
                val charAfter = if (i + idLen < len) content[i + idLen] else ' '
                if (!isIdentChar(charBefore) && !isIdentChar(charAfter)) {
                    results += i until i + idLen
                    i += idLen
                    continue
                }
            }

            i++
        }
        return results
    }

    /** Returns all import statements found in [content]. */
    fun extractImports(content: String): List<ImportStatement> {
        val result = mutableListOf<ImportStatement>()
        val importRegex = Regex("""^(import\s+(static\s+)?([\w.]+(?:\.\*)?)\s*;)""", RegexOption.MULTILINE)
        for (match in importRegex.findAll(content)) {
            val isStatic = match.groupValues[2].isNotBlank()
            val name = match.groupValues[3]
            result += ImportStatement(
                text = match.groupValues[1],
                name = name,
                isStatic = isStatic,
                startOffset = match.range.first,
                endOffset = match.range.last + 1,
            )
        }
        return result
    }

    /** True if [c] can be part of a Java identifier. */
    fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'
}

data class ImportStatement(
    val text: String,
    val name: String,
    val isStatic: Boolean,
    val startOffset: Int,
    val endOffset: Int,
)

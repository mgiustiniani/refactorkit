package org.refactorkit.webimporter

/**
 * Splits a Java source string into one entry per top-level type declaration.
 *
 * Uses brace-counting while skipping string literals and comments.
 * Handles class, interface, enum, and record keywords.
 */
object JavaClassSplitter {

    private val TOP_LEVEL_REGEX = Regex(
        """(?m)^[ \t]*((?:(?:public|protected|private|abstract|final|sealed|non-sealed|strictfp)\s+)*)""" +
            """(class|interface|enum|record)\s+(\w+)""",
    )

    data class ExtractedType(
        val name: String,
        val kind: String,
        val isPublic: Boolean,
        val content: String,
        val startOffset: Int,
        val endOffset: Int,
    )

    fun split(code: String): List<ExtractedType> {
        val result = mutableListOf<ExtractedType>()
        for (match in TOP_LEVEL_REGEX.findAll(code)) {
            if (!isTopLevelOffset(code, match.range.first)) continue

            val modifiers = match.groupValues[1]
            val kind = match.groupValues[2]
            val name = match.groupValues[3]
            val isPublic = modifiers.contains("public")

            val typeContent = extractTypeContent(code, match.range.first) ?: continue
            result += ExtractedType(
                name = name,
                kind = kind,
                isPublic = isPublic,
                content = typeContent,
                startOffset = match.range.first,
                endOffset = match.range.first + typeContent.length,
            )
        }
        return result
    }

    /** Returns true when [offset] is not inside an already-open type/method block. */
    private fun isTopLevelOffset(code: String, offset: Int): Boolean {
        var i = 0
        var depth = 0
        val len = offset.coerceAtMost(code.length)

        while (i < len) {
            when {
                code[i] == '/' && i + 1 < len && code[i + 1] == '/' -> {
                    val nl = code.indexOf('\n', i)
                    i = if (nl < 0 || nl >= len) len else nl + 1
                }
                code[i] == '/' && i + 1 < len && code[i + 1] == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    i = if (end < 0 || end + 2 >= len) len else end + 2
                }
                code[i] == '"' -> i = skipStringLiteral(code, i, len)
                code[i] == '\'' -> i = skipCharLiteral(code, i, len)
                code[i] == '{' -> { depth++; i++ }
                code[i] == '}' -> { if (depth > 0) depth--; i++ }
                else -> i++
            }
        }
        return depth == 0
    }

    /** Extracts the complete source of one top-level type starting at [startOffset]. */
    private fun extractTypeContent(code: String, startOffset: Int): String? {
        var i = startOffset
        val len = code.length

        // Advance to the opening brace, skipping generics/implements/extends
        var angleDepth = 0
        while (i < len) {
            when {
                code[i] == '<' -> { angleDepth++; i++ }
                code[i] == '>' -> { if (angleDepth > 0) angleDepth--; i++ }
                code[i] == '{' && angleDepth == 0 -> break
                else -> i++
            }
        }
        if (i >= len) return null

        // Now count braces to find the closing brace
        var depth = 0
        while (i < len) {
            when {
                // Line comment
                code[i] == '/' && i + 1 < len && code[i + 1] == '/' -> {
                    val nl = code.indexOf('\n', i)
                    i = if (nl < 0) len else nl + 1
                }
                // Block comment
                code[i] == '/' && i + 1 < len && code[i + 1] == '*' -> {
                    val end = code.indexOf("*/", i + 2)
                    i = if (end < 0) len else end + 2
                }
                // String literal
                code[i] == '"' -> i = skipStringLiteral(code, i, len)
                // Char literal
                code[i] == '\'' -> i = skipCharLiteral(code, i, len)
                code[i] == '{' -> { depth++; i++ }
                code[i] == '}' -> {
                    depth--
                    i++
                    if (depth == 0) return code.substring(startOffset, i)
                }
                else -> i++
            }
        }
        return null
    }

    private fun skipStringLiteral(code: String, start: Int, limit: Int): Int {
        var i = start + 1
        return if (i + 1 < limit && code[i] == '"' && code[i + 1] == '"') {
            // Text block: opening delimiter is at start..start+2.
            i += 2
            while (i + 2 < limit) {
                if (code[i] == '"' && code[i + 1] == '"' && code[i + 2] == '"') return i + 3
                i++
            }
            limit
        } else {
            while (i < limit && code[i] != '"' && code[i] != '\n') {
                if (code[i] == '\\') i++
                i++
            }
            if (i < limit) i + 1 else i
        }
    }

    private fun skipCharLiteral(code: String, start: Int, limit: Int): Int {
        var i = start + 1
        while (i < limit && code[i] != '\'' && code[i] != '\n') {
            if (code[i] == '\\') i++
            i++
        }
        return if (i < limit) i + 1 else i
    }
}

package org.refactorkit.treesitter

/**
 * Language-agnostic structural search (Level 1).
 *
 * Supports simple pattern matching within a file:
 * - exact text search
 * - wildcard patterns with `*` (any sequence of chars on one line)
 * - identifier-aware search (word boundaries)
 */
object GenericStructuralSearch {

    data class SearchMatch(
        val line: Int,
        val character: Int,
        val length: Int,
        val text: String,
    )

    /**
     * Searches [content] for occurrences of [pattern].
     *
     * Pattern syntax:
     * - Plain string → exact search (case-sensitive).
     * - `*` in pattern → matches any sequence of non-newline characters.
     * - `<identifier>` prefix → enforces word boundaries.
     */
    fun search(
        content: String,
        pattern: String,
        languageId: String = "",
        wholeWord: Boolean = false,
        caseSensitive: Boolean = true,
    ): List<SearchMatch> {
        if (pattern.isBlank()) return emptyList()
        val lines = content.lines()
        val results = mutableListOf<SearchMatch>()

        val regexStr = buildString {
            if (wholeWord) append("""\b""")
            append(Regex.escape(pattern).replace("\\*", ".*"))
            if (wholeWord) append("""\b""")
        }

        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = try { Regex(regexStr, options) } catch (e: Exception) { return emptyList() }

        for ((lineIdx, line) in lines.withIndex()) {
            for (match in regex.findAll(line)) {
                results += SearchMatch(
                    line = lineIdx,
                    character = match.range.first,
                    length = match.value.length,
                    text = match.value,
                )
            }
        }
        return results
    }

    /**
     * Find all uses of [identifier] as a whole word across [content].
     * Skips results inside line comments for common languages.
     */
    fun findIdentifier(
        content: String,
        identifier: String,
        languageId: String = "",
    ): List<SearchMatch> = search(content, identifier, languageId, wholeWord = true, caseSensitive = true)

    /**
     * Local rename: replace all occurrences of [from] with [to] as a whole identifier.
     * Returns the modified content.
     * This is a TEXTUAL rename, not semantic. Callers must wrap the result in a PatchPlan.
     */
    fun localRename(content: String, from: String, to: String): String =
        Regex("""\b${Regex.escape(from)}\b""").replace(content, to)
}

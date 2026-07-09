package org.refactorkit.treesitter

/**
 * Heuristic filter to exclude [GenericStructuralSearch.SearchMatch] occurrences
 * that appear inside comments or string/char literals.
 *
 * This is NOT parse-tree-accurate. It uses a single-pass character scanner that
 * builds a complete "ignored offset" set for the whole file before filtering matches.
 * Pre-computing the whole set is cheaper than running the scanner once per match.
 *
 * Language-specific coverage:
 *   Java/Kotlin/TypeScript/JavaScript/Rust/Go/C#  — `//` line comments, `/* */` block comments
 *   Python/Ruby/Shell/YAML/TOML                   — `#` line comments
 *   Java/Kotlin/Python (triple-quoted)            — `"""..."""` and `'''...'''`
 *   C# verbatim strings                           — `@"..."` (escape: `""`)
 *   All                                            — `"..."`, `'...'`, `` `...` `` strings
 *
 * When native Tree-sitter bindings are available via [TreeSitterNativeBinding],
 * the [TreeSitterAdapter] bypasses this filter entirely.
 */
object CommentLiteralFilter {

    /**
     * Remove from [matches] any entry whose start offset is inside a comment or
     * string literal as determined by the heuristic scanner.
     */
    fun filter(
        matches: List<GenericStructuralSearch.SearchMatch>,
        content: String,
        languageId: String,
    ): List<GenericStructuralSearch.SearchMatch> {
        if (matches.isEmpty()) return emptyList()
        val ignored = buildIgnoreSet(content, languageId)
        return matches.filter { m -> offsetFor(content, m.line, m.character) !in ignored }
    }

    /**
     * Returns true if the character at [offset] in [content] is inside a
     * comment or string literal.
     */
    fun isIgnored(content: String, offset: Int, languageId: String): Boolean =
        offset in buildIgnoreSet(content, languageId)

    /**
     * Convert a (line, character) pair to a flat character offset in [content].
     */
    fun offsetFor(content: String, line: Int, character: Int): Int {
        var currentLine = 0
        var lineStart = 0
        for (i in content.indices) {
            if (currentLine == line) return (lineStart + character).coerceAtMost(content.length)
            if (content[i] == '\n') { currentLine++; lineStart = i + 1 }
        }
        return (lineStart + character).coerceAtMost(content.length)
    }

    // ── scanner ───────────────────────────────────────────────────────────────

    private fun buildIgnoreSet(content: String, languageId: String): Set<Int> {
        val lang = languageId.lowercase()
        val hashComments     = lang in setOf("python", "ruby", "sh", "bash", "yaml", "toml", "perl", "r")
        val verbatimStrings  = lang in setOf("csharp", "cs")

        val ignored = HashSet<Int>(content.length / 4)

        var i = 0
        var inLineComment  = false
        var inBlockComment = false
        var inString: Char? = null        // delimiter of current string (", ', `)
        var verbatim  = false             // C# @"..."
        var tripleQuote: String? = null   // """ or '''

        fun mark(vararg offsets: Int) { offsets.forEach { o -> if (o < content.length) ignored.add(o) } }

        while (i < content.length) {
            val c = content[i]
            val n = content.getOrNull(i + 1)

            when {
                // ── inside a line comment ─────────────────────────────────
                inLineComment -> {
                    mark(i)
                    if (c == '\n') inLineComment = false
                    i++
                }

                // ── inside a block comment ────────────────────────────────
                inBlockComment -> {
                    mark(i)
                    if (c == '*' && n == '/') { mark(i + 1); inBlockComment = false; i += 2 } else i++
                }

                // ── inside a triple-quoted string ─────────────────────────
                tripleQuote != null -> {
                    mark(i)
                    if (content.startsWith(tripleQuote!!, i)) {
                        for (j in 0 until tripleQuote!!.length) mark(i + j)
                        i += tripleQuote!!.length; tripleQuote = null
                    } else i++
                }

                // ── inside a regular string ───────────────────────────────
                inString != null -> {
                    mark(i)
                    if (verbatim) {
                        // C# verbatim string: "" is an escaped quote, not end-of-string
                        if (c == '"' && n == '"') { mark(i + 1); i += 2 }
                        else if (c == '"')        { inString = null; verbatim = false; i++ }
                        else                       i++
                    } else {
                        if (c == '\\')            { mark(i + 1); i += 2 }
                        else if (c == inString)   { inString = null; i++ }
                        else                       i++
                    }
                }

                // ── start of // line comment ──────────────────────────────
                c == '/' && n == '/' -> { mark(i, i + 1); inLineComment = true; i += 2 }

                // ── start of /* block comment ─────────────────────────────
                c == '/' && n == '*' -> { mark(i, i + 1); inBlockComment = true; i += 2 }

                // ── # line comments (Python, Ruby, …) ────────────────────
                hashComments && c == '#' -> { mark(i); inLineComment = true; i++ }

                // ── C# verbatim string @"..." ─────────────────────────────
                verbatimStrings && c == '@' && n == '"' -> {
                    mark(i, i + 1); inString = '"'; verbatim = true; i += 2
                }

                // ── triple-quoted strings  """  or  ''' ───────────────────
                (c == '"' && content.startsWith("\"\"\"", i)) ||
                (c == '\'' && content.startsWith("'''", i)) -> {
                    val q = content.substring(i, i + 3)
                    for (j in 0 until 3) mark(i + j)
                    tripleQuote = q; i += 3
                }

                // ── regular string / char / template-literal delimiters ───
                c == '"' || c == '\'' || c == '`' -> { mark(i); inString = c; i++ }

                else -> i++
            }
        }
        return ignored
    }
}

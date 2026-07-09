package org.refactorkit.treesitter

/**
 * Language-agnostic file outline using per-language regex patterns.
 *
 * This is the Level 1 "structural" multi-language support described in AGENTS.md §23.1.
 * It does NOT require Tree-sitter binaries; it is purely regex-based and is intentionally
 * kept simple. A future Tree-sitter or LSP-backed version will replace per-language regexes
 * with a proper parse tree.
 */
object GenericOutline {

    data class OutlineItem(
        val name: String,
        val kind: Kind,
        val line: Int,           // 0-based
        val character: Int = 0,
    ) {
        enum class Kind {
            CLASS, INTERFACE, ENUM, RECORD,
            FUNCTION, METHOD, PROPERTY,
            STRUCT, TRAIT, IMPL,
            MODULE, NAMESPACE, PACKAGE,
            CONSTANT, VARIABLE,
            UNKNOWN,
        }
    }

    private data class Pattern(
        val regex: Regex,
        val nameGroup: Int,
        val kind: OutlineItem.Kind,
    )

    private val JAVA_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:public\s+|protected\s+|private\s+)?(?:abstract\s+|final\s+)?class\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*(?:public\s+|protected\s+|private\s+)?interface\s+(\w+)"""), 1, OutlineItem.Kind.INTERFACE),
        Pattern(Regex("""(?m)^\s*(?:public\s+|protected\s+|private\s+)?enum\s+(\w+)"""), 1, OutlineItem.Kind.ENUM),
        Pattern(Regex("""(?m)^\s*(?:public\s+|protected\s+|private\s+)?record\s+(\w+)"""), 1, OutlineItem.Kind.RECORD),
    )

    private val TYPESCRIPT_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:export\s+)?(?:abstract\s+)?class\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?interface\s+(\w+)"""), 1, OutlineItem.Kind.INTERFACE),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?(?:async\s+)?function\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*=\s*(?:async\s*)?\("""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*="""), 1, OutlineItem.Kind.CONSTANT),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?enum\s+(\w+)"""), 1, OutlineItem.Kind.ENUM),
        Pattern(Regex("""(?m)^\s*(?:export\s+)?type\s+(\w+)\s*="""), 1, OutlineItem.Kind.UNKNOWN),
    )

    private val PYTHON_PATTERNS = listOf(
        Pattern(Regex("""(?m)^class\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^(?:async\s+)?def\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^    (?:async\s+)?def\s+(\w+)"""), 1, OutlineItem.Kind.METHOD),
    )

    private val RUST_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:pub(?:\([\w]+\))?\s+)?struct\s+(\w+)"""), 1, OutlineItem.Kind.STRUCT),
        Pattern(Regex("""(?m)^\s*(?:pub(?:\([\w]+\))?\s+)?enum\s+(\w+)"""), 1, OutlineItem.Kind.ENUM),
        Pattern(Regex("""(?m)^\s*(?:pub(?:\([\w]+\))?\s+)?trait\s+(\w+)"""), 1, OutlineItem.Kind.TRAIT),
        Pattern(Regex("""(?m)^\s*(?:pub(?:\([\w]+\))?\s+)?fn\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^\s*impl\s+(?:<[^>]+>\s+)?(\w+)"""), 1, OutlineItem.Kind.IMPL),
    )

    private val GO_PATTERNS = listOf(
        Pattern(Regex("""(?m)^type\s+(\w+)\s+struct"""), 1, OutlineItem.Kind.STRUCT),
        Pattern(Regex("""(?m)^type\s+(\w+)\s+interface"""), 1, OutlineItem.Kind.INTERFACE),
        Pattern(Regex("""(?m)^type\s+(\w+)"""), 1, OutlineItem.Kind.UNKNOWN),
        Pattern(Regex("""(?m)^func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
    )

    private val KOTLIN_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|abstract|sealed|data|open|inline)\s+)*class\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|functional)\s+)*interface\s+(\w+)"""), 1, OutlineItem.Kind.INTERFACE),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal)\s+)*object\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|suspend|inline|operator|override|open)\s+)*fun\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^\s*enum\s+class\s+(\w+)"""), 1, OutlineItem.Kind.ENUM),
    )

    private val CSHARP_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|abstract|sealed|static|partial)\s+)*class\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal)\s+)*interface\s+(\w+)"""), 1, OutlineItem.Kind.INTERFACE),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|static)\s+)*enum\s+(\w+)"""), 1, OutlineItem.Kind.ENUM),
        Pattern(Regex("""(?m)^\s*(?:(?:public|private|protected|internal|static|virtual|override|async)\s+)+\w+\s+(\w+)\s*\("""), 1, OutlineItem.Kind.METHOD),
    )

    private val GENERIC_PATTERNS = listOf(
        Pattern(Regex("""(?m)^\s*(?:class|struct)\s+(\w+)"""), 1, OutlineItem.Kind.CLASS),
        Pattern(Regex("""(?m)^\s*function\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
        Pattern(Regex("""(?m)^\s*def\s+(\w+)"""), 1, OutlineItem.Kind.FUNCTION),
    )

    /**
     * Extracts outline items from [content] for the given [languageId].
     * Returned items are sorted by line number.
     */
    fun outline(content: String, languageId: String): List<OutlineItem> {
        val patterns = when (languageId.lowercase()) {
            "java"             -> JAVA_PATTERNS
            "typescript", "ts" -> TYPESCRIPT_PATTERNS
            "javascript", "js" -> TYPESCRIPT_PATTERNS
            "python", "py"     -> PYTHON_PATTERNS
            "rust", "rs"       -> RUST_PATTERNS
            "go"               -> GO_PATTERNS
            "kotlin", "kt"     -> KOTLIN_PATTERNS
            "csharp", "cs"     -> CSHARP_PATTERNS
            else               -> GENERIC_PATTERNS
        }

        val lineStarts = computeLineStarts(content)
        val items = mutableListOf<OutlineItem>()

        for (p in patterns) {
            for (match in p.regex.findAll(content)) {
                val name = match.groupValues.getOrElse(p.nameGroup) { "" }
                if (name.isBlank()) continue
                val offset = match.range.first
                val line = lineForOffset(lineStarts, offset)
                val character = offset - lineStarts[line]
                items += OutlineItem(name = name, kind = p.kind, line = line, character = character)
            }
        }

        return items.distinctBy { "${it.line}:${it.name}" }.sortedBy { it.line }
    }

    private fun computeLineStarts(content: String): List<Int> {
        val starts = mutableListOf(0)
        content.forEachIndexed { i, c -> if (c == '\n') starts += i + 1 }
        return starts
    }

    private fun lineForOffset(lineStarts: List<Int>, offset: Int): Int {
        var lo = 0; var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

package org.refactorkit.c

/**
 * Conservative rename-target check. In C only, a spelling used exclusively as
 * a struct/union/enum tag is in a distinct lookup namespace from ordinary
 * identifiers. Unknown, preprocessor, C++ and mixed ordinary occurrences keep
 * the existing fail-closed behavior.
 */
internal object CTargetNameCollisionPolicy {
    private val cTagIntroducers = setOf("struct", "union", "enum")

    fun hasPotentialConflict(languageId: String, source: String, targetName: String): Boolean {
        if (targetName.isBlank()) return false
        val tokens = CTokenizer().tokenize(source)
        val directiveLines = preprocessorDirectiveLines(source, tokens)
        return tokens.indices.any { index ->
            val token = tokens[index]
            token.type == CTokenType.IDENTIFIER && token.text == targetName &&
                !isDefinitelyCtag(languageId, tokens, index, directiveLines)
        }
    }

    private fun isDefinitelyCtag(
        languageId: String,
        tokens: List<CToken>,
        index: Int,
        directiveLines: Set<Int>,
    ): Boolean {
        if (languageId != "c") return false
        val token = tokens[index]
        if (token.line in directiveLines) return false
        val introducer = tokens.getOrNull(index - 1) ?: return false
        return introducer.type == CTokenType.IDENTIFIER && introducer.text in cTagIntroducers &&
            introducer.line !in directiveLines
    }

    private fun preprocessorDirectiveLines(source: String, tokens: List<CToken>): Set<Int> {
        val firstTokenByLine = tokens.groupBy(CToken::line).mapValues { (_, lineTokens) -> lineTokens.first() }
        val lines = source.split('\n')
        val result = mutableSetOf<Int>()
        var continuedDirective = false
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1
            val startsDirective = firstTokenByLine[lineNumber]?.let {
                it.type == CTokenType.PUNCTUATION && it.text == "#"
            } == true
            val inDirective = continuedDirective || startsDirective
            if (inDirective) result += lineNumber
            val trailingBackslashes = line.trimEnd().takeLastWhile { it == '\\' }.length
            continuedDirective = inDirective && trailingBackslashes % 2 == 1
        }
        return result
    }
}

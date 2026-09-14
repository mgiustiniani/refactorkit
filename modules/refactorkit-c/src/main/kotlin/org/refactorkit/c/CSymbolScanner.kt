package org.refactorkit.c

/** Bounded C source tokenizer. Non-regex: lexical scanning with bounded output. */
class CTokenizer {
    fun tokenize(text: String): List<CToken> {
        val tokens = mutableListOf<CToken>()
        var i = 0
        var line = 1
        val n = text.length
        while (i < n) {
            val ch = text[i]
            when {
                ch.isWhitespace() -> {
                    if (ch == '\n') line++
                    i++
                }
                ch == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                    line++
                }
                ch == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(text[i] == '*' && i + 1 < n && text[i + 1] == '/')) {
                        if (text[i] == '\n') line++
                        i++
                    }
                    i += 2
                }
                ch == '"' || ch == '\'' -> {
                    val start = i
                    val quote = ch
                    i++
                    while (i < n && text[i] != quote && text[i] != '\n') {
                        if (text[i] == '\\') i++
                        i++
                    }
                    i++
                    tokens += CToken(CTokenType.STRING, text.substring(start, minOf(i, n)), line)
                }
                ch.isLetter() || ch == '_' -> {
                    val start = i
                    while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    tokens += CToken(CTokenType.IDENTIFIER, text.substring(start, i), line)
                }
                ch.isDigit() -> {
                    val start = i
                    while (i < n && (text[i].isDigit() || text[i] == '.' || text[i] == 'x' || text[i] == 'X' ||
                        text[i] in 'a'..'f' || text[i] in 'A'..'F')) i++
                    tokens += CToken(CTokenType.NUMBER, text.substring(start, i), line)
                }
                else -> {
                    tokens += CToken(CTokenType.PUNCTUATION, ch.toString(), line)
                    i++
                }
            }
        }
        return tokens
    }
}

enum class CTokenType { IDENTIFIER, NUMBER, STRING, PUNCTUATION }
data class CToken(val type: CTokenType, val text: String, val line: Int)

/** Bounded structural C symbol scanner. Produces per-TU symbol identities. */
class CSymbolScanner(
    private val tokenizer: CTokenizer = CTokenizer(),
    private val maxSymbols: Int = CTranslationUnitIndex.MAX_SYMBOLS,
) {
    fun scan(file: String, text: String): CTranslationUnitIndex {
        val tokens = tokenizer.tokenize(text)
        val symbols = mutableListOf<CSymbolIdentity>()
        var i = 0
        var scope = "file"
        var visibility = CVisibility.GLOBAL
        var pendingVisibility: CVisibility? = null
        var inTypedef = false
        var lastIdentifier: String? = null
        val n = tokens.size
        var depth = 0
        while (i < n && symbols.size < maxSymbols) {
            val token = tokens[i]
            when (token.type) {
                CTokenType.IDENTIFIER -> {
                    when (token.text) {
                        "struct", "union", "enum" -> {
                            val next = tokens.getOrNull(i + 1)
                            if (next?.type == CTokenType.IDENTIFIER) {
                                symbols += CSymbolIdentity(CIdentifierNamespace.TAG, next.text, "tag", visibility)
                                i += 2
                                continue
                            }
                        }
                        "typedef" -> { inTypedef = true; i++; continue }
                        "static" -> { pendingVisibility = CVisibility.FILE_STATIC; i++; continue }
                        "extern" -> { pendingVisibility = CVisibility.EXPORTED; i++; continue }
                        "int", "char", "long", "short", "unsigned", "signed", "float", "double", "void", "_Bool", "const", "volatile", "size_t" -> {
                            val next = tokens.getOrNull(i + 1)
                            if (next?.type == CTokenType.IDENTIFIER && !isKeyword(next.text)) {
                                if (inTypedef) {
                                    lastIdentifier = next.text
                                } else {
                                    val effective = pendingVisibility ?: visibility
                                    symbols += CSymbolIdentity(CIdentifierNamespace.ORDINARY, next.text, scope, effective)
                                    pendingVisibility = null
                                }
                                i += 2
                                continue
                            }
                        }
                    }
                    if (!isKeyword(token.text)) lastIdentifier = token.text
                    i++
                }
                CTokenType.PUNCTUATION -> {
                    when (token.text) {
                        "{" -> { depth++; scope = "block-$depth"; visibility = CVisibility.LOCAL }
                        "}" -> { if (depth > 0) { depth--; scope = if (depth == 0) "file" else "block-$depth" } }
                        ";" -> {
                            if (depth == 0) {
                                if (inTypedef && lastIdentifier != null) {
                                    symbols += CSymbolIdentity(CIdentifierNamespace.TYPEDEF, lastIdentifier!!, scope, visibility)
                                    inTypedef = false
                                }
                                scope = "file"; visibility = CVisibility.GLOBAL; pendingVisibility = null; lastIdentifier = null
                            }
                        }
                    }
                    i++
                }
                else -> i++
            }
        }
        return CTranslationUnitIndex(file, symbols.distinctBy { it.key() }.take(maxSymbols))
    }

    private fun isKeyword(text: String): Boolean = text in KEYWORDS

    private companion object {
        val KEYWORDS = setOf(
            "struct", "union", "enum", "typedef", "static", "extern", "int", "char", "long", "short",
            "unsigned", "signed", "float", "double", "void", "_Bool", "const", "volatile", "sizeof", "if",
            "else", "for", "while", "do", "return", "break", "continue", "goto", "switch", "case", "default",
            "register", "auto", "inline", "restrict", "_Complex", "_Imaginary", "_Atomic", "_Noreturn",
            "_Static_assert", "_Thread_local", "_Generic", "_Alignas", "_Alignof", "_Bool", "sizeof",
        )
    }
}

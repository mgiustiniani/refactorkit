package org.refactorkit.c

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CSymbolScannerTest {
    @Test
    fun tokenizesCommentsStringsAndIdentifiers() {
        val tokens = CTokenizer().tokenize("""
            // comment
            int x = 42; // inline
            char *s = "hello";
        """.trimIndent())
        assertTrue(tokens.any { it.type == CTokenType.IDENTIFIER && it.text == "x" })
        assertTrue(tokens.any { it.type == CTokenType.NUMBER && it.text == "42" })
        assertTrue(tokens.any { it.type == CTokenType.STRING && it.text == "\"hello\"" })
        assertTrue(tokens.none { it.text.contains("comment") })
    }

    @Test
    fun scansStructTagAndTypedef() {
        val index = CSymbolScanner().scan("src/point.c", """
            struct point { int x; int y; };
            typedef struct point point_t;
            typedef unsigned long size_t;
        """.trimIndent())
        assertTrue(index.symbols.any { it.namespace == CIdentifierNamespace.TAG && it.name == "point" })
        assertTrue(index.symbols.any { it.namespace == CIdentifierNamespace.TYPEDEF && it.name == "point_t" })
        assertTrue(index.symbols.any { it.namespace == CIdentifierNamespace.TYPEDEF && it.name == "size_t" })
    }

    @Test
    fun distinguishesStaticAndExternVisibility() {
        val index = CSymbolScanner().scan("src/counter.c", """
            static int counter = 0;
            extern int api_value;
            int global = 1;
        """.trimIndent())
        assertTrue(index.symbols.any { it.name == "counter" && it.visibility == CVisibility.FILE_STATIC })
        assertTrue(index.symbols.any { it.name == "api_value" && it.visibility == CVisibility.EXPORTED })
        assertTrue(index.symbols.any { it.name == "global" && it.visibility == CVisibility.GLOBAL })
    }

    @Test
    fun enforcesBoundedSymbolCount() {
        val text = (0 until 100).joinToString("\n") { "int v$it = $it;" }
        val index = CSymbolScanner(maxSymbols = 20).scan("f.c", text)
        assertTrue(index.symbols.size <= 20)
    }
}

package org.refactorkit.treesitter

import org.refactorkit.core.Symbol
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LspDocumentSymbolParserTest {
    @Test
    fun parsesNestedDocumentSymbolsWithSelectionRanges() {
        val symbols = LspDocumentSymbolParser.parse(
            """[
              {"name":"Service","kind":5,"range":{"start":{"line":0,"character":0},"end":{"line":4,"character":1}},"selectionRange":{"start":{"line":0,"character":13},"end":{"line":0,"character":20}},"children":[
                {"name":"run","kind":6,"range":{"start":{"line":1,"character":2},"end":{"line":3,"character":3}},"selectionRange":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}}}
              ]}
            ]""",
            Path.of("src/service.ts"), "typescript",
        ) { it }

        assertEquals(listOf("Service", "run"), symbols.map(Symbol::name))
        assertEquals(listOf(Symbol.Kind.CLASS, Symbol.Kind.METHOD), symbols.map(Symbol::kind))
        assertEquals(13, symbols.first().location.range.start.character)
        assertEquals("src/service.ts::Service@0:13", symbols.first().id.value)
    }

    @Test
    fun parsesSymbolInformationAndRemapsFileUri() {
        val symbols = LspDocumentSymbolParser.parse(
            """[{"name":"value","kind":13,"location":{"uri":"file:///overlay/src/value.ts","range":{"start":{"line":2,"character":6},"end":{"line":2,"character":11}}}}]""",
            Path.of("ignored.ts"), "typescript",
        ) { Path.of("src/value.ts") }

        assertEquals(Path.of("src/value.ts"), symbols.single().location.path)
        assertEquals(Symbol.Kind.FIELD, symbols.single().kind)
    }

    @Test
    fun malformedRangesAndUnboundedNamesAreIgnored() {
        val symbols = LspDocumentSymbolParser.parse(
            """[
              {"name":"bad","kind":5,"selectionRange":{"start":{"line":2,"character":0},"end":{"line":1,"character":0}}},
              {"name":"${"x".repeat(1025)}","kind":5,"selectionRange":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}},
              {"name":"valid","kind":11,"selectionRange":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}}}
            ]""",
            Path.of("file.ts"), "typescript",
        ) { it }
        assertEquals(listOf("valid"), symbols.map(Symbol::name))
        assertTrue(LspDocumentSymbolParser.parse("not-json", Path.of("x.ts"), "typescript") { it }.isEmpty())
    }
}

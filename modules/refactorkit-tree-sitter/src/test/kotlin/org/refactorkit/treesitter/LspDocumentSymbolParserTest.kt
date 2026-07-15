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
              ]},
              {"name":"Registry","kind":19,"range":{"start":{"line":5,"character":0},"end":{"line":5,"character":15}},"selectionRange":{"start":{"line":5,"character":7},"end":{"line":5,"character":15}}}
            ]""",
            Path.of("src/service.ts"), "typescript",
        ) { it }

        assertEquals(listOf("Service", "run", "Registry"), symbols.map(Symbol::name))
        assertEquals(listOf(Symbol.Kind.CLASS, Symbol.Kind.METHOD, Symbol.Kind.OBJECT), symbols.map(Symbol::kind))
        assertEquals(13, symbols.first().location.range.start.character)
        assertTrue(symbols.first().id.value.startsWith("lsp-symbol-v1:"))
    }

    @Test
    fun semanticIdsRemainStableAcrossLineMovementAndSeparateHierarchies() {
        fun parse(line: Int, parent: String) = LspDocumentSymbolParser.parse(
            """[{"name":"$parent","kind":5,"selectionRange":{"start":{"line":$line,"character":0},"end":{"line":$line,"character":6}},"children":[{"name":"run","detail":"(): void","kind":6,"selectionRange":{"start":{"line":${line + 1},"character":2},"end":{"line":${line + 1},"character":5}}}]}]""",
            Path.of("src/service.ts"), "typescript",
        ) { it }

        val original = parse(0, "First")
        val moved = parse(20, "First")
        val otherParent = parse(0, "Second")

        assertEquals(original.map { it.id }, moved.map { it.id })
        assertTrue(original.last().id != otherParent.last().id)
    }

    @Test
    fun parsesSymbolInformationAndRemapsFileUri() {
        val symbols = LspDocumentSymbolParser.parse(
            """[{"name":"value","kind":13,"location":{"uri":"file:///overlay/src/value.ts","range":{"start":{"line":2,"character":6},"end":{"line":2,"character":11}}}}]""",
            Path.of("ignored.ts"), "typescript",
        ) { Path.of("src/value.ts") }

        assertEquals(Path.of("src/value.ts"), symbols.single().location.path)
        assertEquals(Symbol.Kind.VARIABLE, symbols.single().kind)
        val moved = LspDocumentSymbolParser.parse(
            """[{"name":"value","kind":13,"containerName":"scope","location":{"uri":"file:///overlay/src/value.ts","range":{"start":{"line":40,"character":6},"end":{"line":40,"character":11}}}}]""",
            Path.of("ignored.ts"), "typescript",
        ) { Path.of("src/value.ts") }
        val scoped = LspDocumentSymbolParser.parse(
            """[{"name":"value","kind":13,"containerName":"scope","location":{"uri":"file:///overlay/src/value.ts","range":{"start":{"line":2,"character":6},"end":{"line":2,"character":11}}}}]""",
            Path.of("ignored.ts"), "typescript",
        ) { Path.of("src/value.ts") }
        assertEquals(scoped.single().id, moved.single().id)
        assertTrue(symbols.single().id != scoped.single().id)
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

package org.refactorkit.treesitter

import org.refactorkit.core.SemanticInsertTextFormat
import org.refactorkit.core.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspCompletionParserTest {
    @Test
    fun parsesCompletionListWithTypedEditsAndBoundsItems() {
        val parsed = LspCompletionParser.parse("""{
          "isIncomplete":false,
          "items":[
            {"label":"UserService","kind":7,"detail":"class UserService","documentation":{"kind":"markdown","value":"A service"},
             "sortText":"1","filterText":"User","insertTextFormat":2,"commitCharacters":[".","("],"textEdit":{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"newText":"UserService"},
             "additionalTextEdits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"import { UserService } from './service';\n"}]},
            {"label":"helper","kind":3}
          ]
        }""", 1)!!
        assertEquals(1, parsed.items.size)
        assertTrue(parsed.incomplete)
        val item = parsed.items.single()
        assertEquals(Symbol.Kind.CLASS, item.kind)
        assertEquals("UserService", item.insertText)
        assertEquals(SemanticInsertTextFormat.SNIPPET, item.insertTextFormat)
        assertEquals(listOf(".", "("), item.commitCharacters)
        assertEquals(5, item.replacementRange?.end?.character)
        assertEquals(1, item.additionalTextEdits.size)
    }

    @Test
    fun parsesArrayAndRejectsMalformedOrOverlappingEdits() {
        val array = LspCompletionParser.parse("""[{"label":"run","kind":2,"insertText":"run()"}]""", 10)!!
        assertFalse(array.incomplete)
        assertEquals(Symbol.Kind.METHOD, array.items.single().kind)
        assertNull(LspCompletionParser.parse("""{"isIncomplete":false,"items":[{"kind":2}]}""", 10))
        assertNull(LspCompletionParser.parse("""{"isIncomplete":false,"items":[{"label":"x","additionalTextEdits":[
          {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":2}},"newText":"a"},
          {"range":{"start":{"line":0,"character":1},"end":{"line":0,"character":3}},"newText":"b"}
        ]}]}""", 10))
    }
}

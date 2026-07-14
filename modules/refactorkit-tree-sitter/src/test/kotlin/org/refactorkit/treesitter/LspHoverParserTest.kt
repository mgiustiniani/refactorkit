package org.refactorkit.treesitter

import org.refactorkit.core.SemanticHoverSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspHoverParserTest {
    @Test
    fun parsesMarkupMarkedStringsAndExactRange() {
        val parsed = LspHoverParser.parse("""{
          "contents":[{"language":"ts","value":"class User"},{"kind":"plaintext","value":"Documentation"}],
          "range":{"start":{"line":1,"character":2},"end":{"line":1,"character":6}}
        }""")!!
        assertTrue(parsed.sections[0].value.contains("```ts"))
        assertEquals(SemanticHoverSection.Format.PLAIN_TEXT, parsed.sections[1].format)
        assertEquals(1, parsed.range?.start?.line)
        assertEquals(6, parsed.range?.end?.character)
    }

    @Test
    fun rejectsMalformedHoverAndBoundsContent() {
        assertNull(LspHoverParser.parse("[]"))
        assertNull(LspHoverParser.parse("""{"contents":"x","range":{"start":{"line":2,"character":0},"end":{"line":1,"character":0}}}"""))
        val parsed = LspHoverParser.parse("""{"contents":{"kind":"markdown","value":"${"x".repeat(70_000)}"}}""")!!
        assertTrue(parsed.sections.sumOf { it.value.length } <= 65_536)
    }
}

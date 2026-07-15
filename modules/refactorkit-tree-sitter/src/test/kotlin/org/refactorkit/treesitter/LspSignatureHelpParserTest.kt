package org.refactorkit.treesitter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LspSignatureHelpParserTest {
    @Test
    fun parsesNumericAndStringParameterLabelsWithActiveState() {
        val parsed = LspSignatureHelpParser.parse("""{
          "signatures":[
            {"label":"greet(name: string, count: number): string","documentation":{"kind":"markdown","value":"Greets repeatedly"},
             "parameters":[
               {"label":[6,18],"documentation":"name to greet"},
               {"label":"count: number"}
             ],"activeParameter":1}
          ],
          "activeSignature":0
        }""")!!
        assertEquals(0, parsed.activeSignature)
        assertEquals(1, parsed.activeParameter)
        assertEquals("Greets repeatedly", parsed.signatures.single().documentation)
        assertEquals("name: string", parsed.signatures.single().label.substring(
            parsed.signatures.single().parameters[0].labelStart,
            parsed.signatures.single().parameters[0].labelEnd,
        ))
        assertEquals("count: number", parsed.signatures.single().label.substring(
            parsed.signatures.single().parameters[1].labelStart,
            parsed.signatures.single().parameters[1].labelEnd,
        ))
    }

    @Test
    fun acceptsNullAndRejectsInvalidActiveOrParameterSpans() {
        val empty = LspSignatureHelpParser.parse("null")!!
        assertEquals(emptyList(), empty.signatures)
        assertNull(LspSignatureHelpParser.parse("""{"signatures":[{"label":"f()"}],"activeSignature":2}"""))
        assertNull(LspSignatureHelpParser.parse("""{"signatures":[{"label":"f(x)","parameters":[{"label":[2,9]}]}]}"""))
    }
}

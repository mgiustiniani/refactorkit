package org.refactorkit.treesitter

import org.refactorkit.core.ExternalFileEditProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExternalLspWorkspaceEditParserTest {
    @Test
    fun parsesChangesWithUtf16Coordinates() {
        val parsed = ExternalLspWorkspaceEditParser.parse(
            """{"changes":{"file:///workspace/a.ts":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":4}},"newText":"β"}]}}""",
            "lsp-typescript", "5.9",
        )
        val proposal = assertIs<ExternalLspWorkspaceEditParsing.Accepted>(parsed).proposal
        val modify = assertIs<ExternalFileEditProposal.Modify>(proposal.edits.single())
        assertEquals("β", modify.edits.single().newText)
        assertEquals(2, modify.edits.single().range.start.character)
        assertEquals(null, modify.documentVersion)
    }

    @Test
    fun parsesVersionedDocumentAndSafeResourceOperationsInOrder() {
        val parsed = ExternalLspWorkspaceEditParser.parse(
            """{"documentChanges":[
              {"textDocument":{"uri":"file:///workspace/a.ts","version":7},"edits":[]},
              {"kind":"create","uri":"file:///workspace/new.ts"},
              {"kind":"rename","oldUri":"file:///workspace/new.ts","newUri":"file:///workspace/renamed.ts","options":{"overwrite":false}},
              {"kind":"delete","uri":"file:///workspace/old.ts","options":{"recursive":false}}
            ]}""",
            "lsp-typescript", "5.9",
        )
        val edits = assertIs<ExternalLspWorkspaceEditParsing.Accepted>(parsed).proposal.edits
        assertEquals(4, edits.size)
        assertEquals(7, assertIs<ExternalFileEditProposal.Modify>(edits[0]).documentVersion)
        assertIs<ExternalFileEditProposal.Create>(edits[1])
        assertIs<ExternalFileEditProposal.Rename>(edits[2])
        assertIs<ExternalFileEditProposal.Delete>(edits[3])
    }

    @Test
    fun refusesAmbiguousUnknownUnsafeAndNonFileSchemas() {
        assertCode("""{"changes":{},"documentChanges":[]}""", "externalEdit.schemaAmbiguous")
        assertCode("""{"unexpected":true}""", "externalEdit.schemaInvalid")
        assertCode("""{"documentChanges":[{"kind":"create","uri":"file:///x","options":{"overwrite":true}}]}""", "externalEdit.unsafeOptions")
        assertCode("""{"changes":{"https://example.invalid/a.ts":[]}}""", "externalEdit.uriUnsupported")
        assertCode("""{"documentChanges":[{"textDocument":{"uri":"file:///x","version":"7"},"edits":[]}]}""", "externalEdit.documentVersionInvalid")
    }

    @Test
    fun refusesMalformedCoordinatesAndUnknownNestedFields() {
        assertCode("""{"changes":{"file:///x":[{"range":{"start":{"line":-1,"character":0},"end":{"line":0,"character":0}},"newText":""}]}}""", "externalEdit.schemaInvalid")
        assertCode("""{"changes":{"file:///x":[{"range":{"start":{"line":0,"character":0,"extra":1},"end":{"line":0,"character":0}},"newText":""}]}}""", "externalEdit.schemaInvalid")
        assertCode("not-json", "externalEdit.schemaInvalid")
    }

    private fun assertCode(value: String, expected: String) {
        val refused = assertIs<ExternalLspWorkspaceEditParsing.Refused>(
            ExternalLspWorkspaceEditParser.parse(value, "lsp-test", null),
        )
        assertTrue(refused.diagnostics.any { it.code == expected }, "missing $expected in ${refused.diagnostics}")
    }
}

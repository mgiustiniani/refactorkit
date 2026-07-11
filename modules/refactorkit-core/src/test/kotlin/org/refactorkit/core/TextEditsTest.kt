package org.refactorkit.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextEditsTest {
    @Test
    fun acceptsCharacterAtLineEnd() {
        val edit = TextEdit(
            SourceRange(SourcePosition(0, 3), SourcePosition(0, 3)),
            "!",
        )

        assertEquals("abc!\ndef\n", TextEdits.apply("abc\ndef\n", listOf(edit)))
    }

    @Test
    fun rejectsCharacterBeyondLineInsteadOfCrossingIntoNextLine() {
        val error = assertFailsWith<IllegalArgumentException> {
            TextEdits.apply(
                "abc\ndef\n",
                listOf(TextEdit(
                    SourceRange(SourcePosition(0, 4), SourcePosition(0, 4)),
                    "!",
                )),
            )
        }

        assertTrue(error.message.orEmpty().contains("outside line 0 length 3"))
    }

    @Test
    fun acceptsEmptyLineAfterTrailingNewlineOnlyAtCharacterZero() {
        assertEquals(4, TextEdits.offsetOf("abc\n", SourcePosition(1, 0)))
        assertFailsWith<IllegalArgumentException> {
            TextEdits.offsetOf("abc\n", SourcePosition(1, 1))
        }
    }
}

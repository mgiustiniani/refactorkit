package org.refactorkit.treesitter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommentLiteralFilterTest {

    private fun matches(content: String, identifier: String, lang: String = "java") =
        CommentLiteralFilter.filter(
            GenericStructuralSearch.findIdentifier(content, identifier, lang),
            content, lang,
        )

    // ── line comments ─────────────────────────────────────────────────────────

    @Test
    fun slashSlashCommentIsSkipped() {
        val code = "// Foo is a comment\nFoo foo;"
        val m = matches(code, "Foo")
        assertEquals(1, m.size, "Only 'Foo' in 'Foo foo;' should match")
        assertEquals(1, m[0].line)
    }

    @Test
    fun hashCommentIsSkippedForPython() {
        val code = "# Foo comment\nclass Foo:\n    pass\n"
        val m = matches(code, "Foo", "python")
        assertEquals(1, m.size)
        assertEquals(1, m[0].line)
    }

    @Test
    fun hashCommentIsNOTSkippedForJava() {
        // '#' is NOT a comment in Java
        val code = "#Foo not-a-comment\nFoo foo;"
        val m = matches(code, "Foo", "java")
        // Both occurrences should match (Java has no # comments)
        assertEquals(2, m.size)
    }

    // ── block comments ────────────────────────────────────────────────────────

    @Test
    fun blockCommentIsSkipped() {
        val code = "/* Foo inside block */ Foo real;"
        val m = matches(code, "Foo")
        assertEquals(1, m.size)
        assertEquals(0, m[0].line)
        assertTrue(m[0].character > 22, "Should be the second Foo, not the one in the comment")
    }

    @Test
    fun multiLineBlockCommentIsSkipped() {
        val code = "/*\n * Foo comment\n */\nFoo real;"
        val m = matches(code, "Foo")
        assertEquals(1, m.size)
        assertEquals(3, m[0].line)
    }

    // ── string literals ───────────────────────────────────────────────────────

    @Test
    fun doubleQuotedStringIsSkipped() {
        // String s = "Foo inside string"; Foo real;
        // Positions: opening-" at 11, closing-" at 29, second Foo at 32
        val code = "String s = \"Foo inside string\"; Foo real;"
        val m = matches(code, "Foo")
        assertEquals(1, m.size)
        assertEquals(32, m[0].character)
    }

    @Test
    fun singleQuotedStringIsSkipped() {
        val code = "val s = 'Foo inside'; Foo real"
        val m = matches(code, "Foo", "kotlin")
        // 'Foo inside' is a char/string literal; only 'Foo real' remains
        assertEquals(1, m.size)
        assertTrue(m[0].character > 20)
    }

    @Test
    fun backtickTemplateIsSkipped() {
        val code = "const s = `Foo inside`; Foo real"
        val m = matches(code, "Foo", "typescript")
        assertEquals(1, m.size)
    }

    @Test
    fun escapedQuoteDoesNotEndString() {
        val code = """String s = "Foo \" Foo"; Foo real;"""
        val m = matches(code, "Foo")
        assertEquals(1, m.size)
    }

    // ── triple-quoted strings ─────────────────────────────────────────────────

    @Test
    fun tripleDoubleQuoteIsSkipped() {
        val code = "val s = \"\"\"\n  Foo multi\n\"\"\"\nFoo real"
        val m = matches(code, "Foo", "kotlin")
        assertEquals(1, m.size)
        assertEquals(3, m[0].line)
    }

    @Test
    fun tripleSingleQuoteIsSkipped() {
        val code = "s = '''\nFoo multi\n'''\nFoo real"
        val m = matches(code, "Foo", "python")
        assertEquals(1, m.size)
        assertEquals(3, m[0].line)
    }

    // ── C# verbatim strings ───────────────────────────────────────────────────

    @Test
    fun csharpVerbatimStringIsSkipped() {
        val code = """string s = @"Foo inside verbatim"; Foo real;"""
        val m = matches(code, "Foo", "csharp")
        assertEquals(1, m.size)
        assertTrue(m[0].character > 33)
    }

    @Test
    fun csharpVerbatimEscapeDoesNotEndString() {
        // In C# verbatim strings, "" is the escaped quote, not end of string
        val code = """string s = @"Foo "" Foo"; Foo real;"""
        val m = matches(code, "Foo", "csharp")
        assertEquals(1, m.size)
    }

    @Test
    fun csharpAtSignOutsideStringIsNotSkipped() {
        // '@' not followed by '"' should not trigger verbatim string mode
        val code = "@Foo annotation\nFoo real;"
        val m = matches(code, "Foo", "csharp")
        assertEquals(2, m.size)
    }

    // ── identifiers outside comments ──────────────────────────────────────────

    @Test
    fun identifierInCodeIsIncluded() {
        val code = "class Foo { Foo() {} }"
        val m = matches(code, "Foo")
        assertEquals(2, m.size)
    }

    @Test
    fun partialNameIsNotMatchedByFilter() {
        // GenericStructuralSearch.findIdentifier uses whole-word matching
        val code = "class FooBar { Foo x; }"
        val m = matches(code, "Foo")
        assertEquals(1, m.size)
    }

    // ── TreeSitterAdapter integration ─────────────────────────────────────────

    @Test
    fun packagedNativeAdapterIsAvailableByDefault() {
        val adapter = TreeSitterAdapter()
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun adapterIsAvailableWithNativeBinding() {
        val binding = object : TreeSitterNativeBinding {
            override fun supports(languageId: String) = true
            override fun outline(content: String, languageId: String) = emptyList<GenericOutline.OutlineItem>()
            override fun findIdentifier(content: String, identifier: String, languageId: String) =
                GenericStructuralSearch.findIdentifier(content, identifier, languageId)
        }
        val adapter = TreeSitterAdapter(binding)
        assertTrue(adapter.isAvailable())
    }

    @Test
    fun adapterUsesNativeBindingForFindIdentifier() {
        var called = false
        val binding = object : TreeSitterNativeBinding {
            override fun supports(languageId: String) = languageId == "java"
            override fun outline(content: String, languageId: String) = emptyList<GenericOutline.OutlineItem>()
            override fun findIdentifier(content: String, identifier: String, languageId: String): List<GenericStructuralSearch.SearchMatch> {
                called = true
                return emptyList()
            }
        }
        val adapter = TreeSitterAdapter(binding)
        adapter.findIdentifier("Foo foo;", "Foo", "java")
        assertTrue(called, "Native binding findIdentifier should be called")
    }

    @Test
    fun adapterFallsBackToHeuristicForUnsupportedLanguage() {
        var called = false
        val binding = object : TreeSitterNativeBinding {
            override fun supports(languageId: String) = false // nothing supported
            override fun outline(content: String, languageId: String) = emptyList<GenericOutline.OutlineItem>()
            override fun findIdentifier(content: String, identifier: String, languageId: String): List<GenericStructuralSearch.SearchMatch> {
                called = true
                return emptyList()
            }
        }
        val adapter = TreeSitterAdapter(binding)
        val results = adapter.findIdentifier("Foo foo;", "Foo", "java")
        assertFalse(called, "Native binding should NOT be called for unsupported language")
        assertTrue(results.isNotEmpty(), "Heuristic fallback should find Foo")
    }

}

package org.refactorkit.java

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaLexerTest {

    @Test
    fun findsSimpleIdentifier() {
        val content = "class UserManager { UserManager() {} }"
        val ranges = JavaLexer.findOccurrences(content, "UserManager")
        assertEquals(2, ranges.size)
    }

    @Test
    fun skipsIdentifierInsideStringLiteral() {
        val content = """String s = "UserManager"; class UserManager {}"""
        val ranges = JavaLexer.findOccurrences(content, "UserManager")
        // Should find only the class declaration, not inside the string
        assertEquals(1, ranges.size)
        assertTrue(content.substring(ranges[0]).startsWith("UserManager"))
    }

    @Test
    fun skipsIdentifierInsideLineComment() {
        val content = "// UserManager is renamed\nclass Other {}"
        val ranges = JavaLexer.findOccurrences(content, "UserManager")
        assertEquals(0, ranges.size)
    }

    @Test
    fun skipsIdentifierInsideBlockComment() {
        val content = "/* UserManager */ class Other {}"
        val ranges = JavaLexer.findOccurrences(content, "UserManager")
        assertEquals(0, ranges.size)
    }

    @Test
    fun doesNotMatchPartialIdentifier() {
        val content = "class UserManagerImpl {}"
        val ranges = JavaLexer.findOccurrences(content, "UserManager")
        assertEquals(0, ranges.size)
    }

    @Test
    fun findsFqnOccurrence() {
        val content = "import com.example.UserManager; com.example.UserManager x;"
        val ranges = JavaLexer.findOccurrences(content, "com.example.UserManager")
        assertEquals(2, ranges.size)
    }

    @Test
    fun extractsImports() {
        val content = """
            package com.example;
            import java.util.List;
            import static java.util.Collections.emptyList;
            import com.example.Other;
            class Foo {}
        """.trimIndent()
        val imports = JavaLexer.extractImports(content)
        assertEquals(3, imports.size)
        assertTrue(imports.any { it.name == "java.util.List" && !it.isStatic })
        assertTrue(imports.any { it.name == "java.util.Collections.emptyList" && it.isStatic })
        assertTrue(imports.any { it.name == "com.example.Other" && !it.isStatic })
    }
}

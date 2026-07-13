package org.refactorkit.treesitter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspJsonTest {

    // ── extractField ──────────────────────────────────────────────────────────

    @Test
    fun extractStringField() {
        val json = """{"jsonrpc":"2.0","method":"initialize"}"""
        assertEquals(""""2.0"""", LspJson.extractField(json, "jsonrpc"))
        assertEquals(""""initialize"""", LspJson.extractField(json, "method"))
    }

    @Test
    fun extractIntField() {
        val json = """{"id":42,"result":null}"""
        assertEquals("42", LspJson.extractField(json, "id"))
    }

    @Test
    fun extractNullField() {
        val json = """{"result":null}"""
        assertEquals("null", LspJson.extractField(json, "result"))
    }

    @Test
    fun extractObjectField() {
        val json = """{"range":{"start":{"line":1,"character":5}}}"""
        val range = LspJson.extractField(json, "range")
        assertNotNull(range)
        assertTrue(range.startsWith('{'))
        assertNull(LspJson.extractField(range, "line"), "nested fields must not match top-level extraction")
        val start = assertNotNull(LspJson.extractField(range, "start"))
        assertEquals("1", LspJson.extractField(start, "line"))
    }

    @Test
    fun extractArrayField() {
        val json = """{"items":[1,2,3]}"""
        val arr = LspJson.extractField(json, "items")
        assertNotNull(arr)
        assertTrue(arr.startsWith('['))
    }

    @Test
    fun missingFieldReturnsNull() {
        assertNull(LspJson.extractField("{}", "missing"))
    }

    @Test
    fun extractFieldIgnoresKeyInsideString() {
        // The word "uri" inside the string value must not be extracted as a field
        val json = """{"message":"uri is here","uri":"file:///real"}"""
        assertEquals(""""file:///real"""", LspJson.extractField(json, "uri"))
    }

    // ── unquote ───────────────────────────────────────────────────────────────

    @Test
    fun unquoteBasicString() {
        assertEquals("hello", LspJson.unquote(""""hello""""))
    }

    @Test
    fun unquoteEscapedSlash() {
        assertEquals("file:///path/to/file", LspJson.unquote(""""file:\/\/\/path\/to\/file""""))
    }

    @Test
    fun unquoteEscapedQuote() {
        assertEquals("""say "hi"""", LspJson.unquote(""""say \"hi\"""""))
    }

    // ── parseLocation ─────────────────────────────────────────────────────────

    @Test
    fun parseSingleLocation() {
        val json = """{"uri":"file:///src/Foo.java","range":{"start":{"line":5,"character":10},"end":{"line":5,"character":20}}}"""
        val loc = LspJson.parseLocation(json)
        assertNotNull(loc)
        assertEquals("file:///src/Foo.java", loc.uri)
        assertEquals(5, loc.startLine)
        assertEquals(10, loc.startChar)
        assertEquals(20, loc.endChar)
    }

    @Test
    fun parseLocationMissingRangeReturnsNull() {
        val json = """{"uri":"file:///Foo.java"}"""
        assertNull(LspJson.parseLocation(json))
    }

    // ── parseLocations ────────────────────────────────────────────────────────

    @Test
    fun parseLocationsNull() {
        assertTrue(LspJson.parseLocations("null").isEmpty())
    }

    @Test
    fun parseLocationsSingleObject() {
        val json = """{"uri":"file:///Foo.java","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}}}"""
        val locs = LspJson.parseLocations(json)
        assertEquals(1, locs.size)
    }

    @Test
    fun parseLocationsArray() {
        val json = """[
            {"uri":"file:///A.java","range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}}},
            {"uri":"file:///B.java","range":{"start":{"line":2,"character":0},"end":{"line":2,"character":1}}}
        ]"""
        val locs = LspJson.parseLocations(json)
        assertEquals(2, locs.size)
        assertEquals("file:///A.java", locs[0].uri)
        assertEquals("file:///B.java", locs[1].uri)
    }

    @Test
    fun parseLocationsEmptyArray() {
        assertTrue(LspJson.parseLocations("[]").isEmpty())
    }

    @Test
    fun parseLocationsLocationLink() {
        // LocationLink uses targetUri + targetRange
        val json = """{"targetUri":"file:///Foo.java","targetRange":{"start":{"line":3,"character":0},"end":{"line":3,"character":5}}}"""
        val locs = LspJson.parseLocations(json)
        assertEquals(1, locs.size)
        assertEquals("file:///Foo.java", locs[0].uri)
        assertEquals(3, locs[0].startLine)
    }

    // ── uriToPath ─────────────────────────────────────────────────────────────

    @Test
    fun uriToPathStripsFileScheme() {
        val path = LspJson.uriToPath("file:///home/user/project/Foo.java")
        assertTrue(path.toString().endsWith("Foo.java"))
    }
}

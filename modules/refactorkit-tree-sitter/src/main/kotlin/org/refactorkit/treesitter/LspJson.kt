package org.refactorkit.treesitter

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Minimal JSON field extractor for LSP protocol responses.
 *
 * Avoids an external JSON library dependency by implementing a small recursive-descent
 * extractor that handles strings, numbers, booleans, null, objects, and arrays.
 *
 * Correctness scope: LSP `Location`, `Location[]`, and `LocationLink[]` responses.
 */
internal object LspJson {

    data class Location(
        val uri: String,
        val startLine: Int,
        val startChar: Int,
        val endLine: Int,
        val endChar: Int,
    )

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Extract the raw JSON value string for the first occurrence of [field]
     * at the top level of [json].
     *
     * Returns the raw value (`"42"`, `"null"`, `"""hello"""`, `"{...}"`, `"[...]"`)
     * or null if the field is absent.
     */
    fun extractField(json: String, field: String): String? {
        val keyPat = Regex(""""${Regex.escape(field)}"\s*:\s*""")
        val match = keyPat.find(json) ?: return null
        return extractValue(json, match.range.last + 1)
    }

    /** Remove surrounding `"` and unescape a JSON string value. */
    fun unquote(json: String): String = json.trim()
        .removeSurrounding("\"")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

    /** Parse a single LSP `Location` object from [json]. */
    fun parseLocation(json: String): Location? {
        val uriField   = extractField(json, "uri")   ?: return null
        val uri        = unquote(uriField)
        val rangeField = extractField(json, "range") ?: return null
        val startField = extractField(rangeField, "start") ?: return null
        val endField   = extractField(rangeField, "end")   ?: return null
        val startLine  = extractField(startField, "line")?.toIntOrNull()      ?: return null
        val startChar  = extractField(startField, "character")?.toIntOrNull() ?: return null
        val endLine    = extractField(endField, "line")?.toIntOrNull()        ?: return null
        val endChar    = extractField(endField, "character")?.toIntOrNull()   ?: return null
        return Location(uri, startLine, startChar, endLine, endChar)
    }

    /**
     * Parse an LSP result that may be `null`, a single `Location`, or `Location[]`.
     * Also handles `LocationLink[]` via the `targetUri` / `targetRange` fields.
     */
    fun parseLocations(resultJson: String): List<Location> {
        val trimmed = resultJson.trim()
        if (trimmed == "null" || trimmed.isEmpty()) return emptyList()
        return if (trimmed.startsWith('[')) {
            parseArray(trimmed).mapNotNull { element ->
                parseLocation(element) ?: parseLinkAsLocation(element)
            }
        } else {
            (parseLocation(trimmed) ?: parseLinkAsLocation(trimmed))?.let { listOf(it) } ?: emptyList()
        }
    }

    /** Convert a `file://` URI to a [Path]. */
    fun uriToPath(uri: String): Path {
        val raw = uri
            .removePrefix("file://")
            .removePrefix("file:")
            .let { if (it.startsWith("//") && !it.startsWith("///")) it.substring(2) else it }
        return Paths.get(raw)
    }

    /** Build a `file://` URI from a [Path]. */
    fun pathToUri(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()

    // ── LSP LocationLink support ──────────────────────────────────────────────

    private fun parseLinkAsLocation(json: String): Location? {
        // LocationLink uses targetUri + targetRange instead of uri + range
        val uriField   = extractField(json, "targetUri")   ?: return null
        val uri        = unquote(uriField)
        val rangeField = extractField(json, "targetRange") ?: return null
        val startField = extractField(rangeField, "start") ?: return null
        val endField   = extractField(rangeField, "end")   ?: return null
        val startLine  = extractField(startField, "line")?.toIntOrNull()      ?: return null
        val startChar  = extractField(startField, "character")?.toIntOrNull() ?: return null
        val endLine    = extractField(endField, "line")?.toIntOrNull()        ?: return null
        val endChar    = extractField(endField, "character")?.toIntOrNull()   ?: return null
        return Location(uri, startLine, startChar, endLine, endChar)
    }

    // ── value extraction ──────────────────────────────────────────────────────

    internal fun extractValue(json: String, start: Int): String? {
        var i = start
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        return when (val c = json[i]) {
            '"'  -> extractString(json, i)
            '[', '{' -> extractBalanced(json, i)
            'n'  -> if (json.startsWith("null",  i)) "null"  else null
            't'  -> if (json.startsWith("true",  i)) "true"  else null
            'f'  -> if (json.startsWith("false", i)) "false" else null
            else -> if (c.isDigit() || c == '-') extractNumber(json, i) else null
        }
    }

    private fun extractString(json: String, start: Int): String? {
        if (start >= json.length || json[start] != '"') return null
        var i = start + 1
        while (i < json.length) {
            when (json[i]) {
                '\\' -> i += 2
                '"'  -> return json.substring(start, i + 1)
                else -> i++
            }
        }
        return null
    }

    private fun extractBalanced(json: String, start: Int): String? {
        val open  = json[start]
        val close = if (open == '[') ']' else '}'
        var depth = 0
        var i = start
        var inStr = false
        while (i < json.length) {
            when {
                inStr  && json[i] == '\\' -> i += 2
                inStr  && json[i] == '"'  -> { inStr = false; i++ }
                !inStr && json[i] == '"'  -> { inStr = true;  i++ }
                !inStr && json[i] == open  -> { depth++; i++ }
                !inStr && json[i] == close -> { depth--; i++; if (depth == 0) return json.substring(start, i) }
                else -> i++
            }
        }
        return null
    }

    private fun extractNumber(json: String, start: Int): String? {
        var i = start
        while (i < json.length && (json[i].isDigit() || json[i] in "-+.eE")) i++
        return if (i > start) json.substring(start, i) else null
    }

    private fun parseArray(json: String): List<String> {
        if (!json.startsWith('[')) return emptyList()
        val elements = mutableListOf<String>()
        var i = 1
        while (i < json.length - 1) {
            while (i < json.length && (json[i].isWhitespace() || json[i] == ',')) i++
            if (i >= json.length - 1) break
            val value = extractValue(json, i) ?: break
            elements += value
            i += value.length
        }
        return elements
    }
}

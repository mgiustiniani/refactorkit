package org.refactorkit.testkit

/**
 * Minimal JSON parser for golden test files (request.json, expected-plan.json).
 *
 * Only handles the simple, flat structures used in those files:
 * - top-level string fields
 * - one nested object for `arguments`
 *
 * Not general-purpose; use [LspJson] in refactorkit-tree-sitter for LSP responses.
 */
internal object GoldenJson {

    fun parseRequest(json: String): GoldenRequest {
        val top = stringFields(json)
        val args = nestedStringFields(json, "arguments")
        return GoldenRequest(
            operation = top["operation"] ?: error("request.json missing 'operation'"),
            symbol = top["symbol"],
            arguments = args,
        )
    }

    fun parseExpectedPlan(json: String): GoldenExpectedPlan {
        val fields = stringFields(json)
        val minFiles = fields["minAffectedFiles"]?.toIntOrNull() ?: 0
        return GoldenExpectedPlan(
            status = fields["status"],
            operation = fields["operation"],
            summaryContains = fields["summary"],
            minAffectedFiles = minFiles,
            warningContains = fields["warningContains"],
        )
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Extract all top-level `"key": "value"` pairs (string values only). */
    private fun stringFields(json: String): Map<String, String> =
        Regex(""""(\w+)"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            .findAll(json)
            .associate { m -> m.groupValues[1] to unescape(m.groupValues[2]) }

    /** Extract string key-value pairs from one named nested object. */
    private fun nestedStringFields(json: String, field: String): Map<String, String> {
        val obj = extractObject(json, field) ?: return emptyMap()
        return stringFields(obj)
    }

    /** Extract the raw JSON object for [field] in [json]. */
    private fun extractObject(json: String, field: String): String? {
        val start = Regex(""""$field"\s*:\s*\{""").find(json)?.range?.last ?: return null
        val sb = StringBuilder()
        var depth = 0
        var i = start
        while (i < json.length) {
            val c = json[i++]
            when {
                c == '{' -> { depth++; sb.append(c) }
                c == '}' -> { depth--; sb.append(c); if (depth == 0) break }
                else     -> sb.append(c)
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    private fun unescape(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/",  "/")
        .replace("\\n",  "\n")
        .replace("\\t",  "\t")
}

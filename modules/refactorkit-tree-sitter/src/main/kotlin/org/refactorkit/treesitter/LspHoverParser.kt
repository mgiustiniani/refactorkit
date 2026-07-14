package org.refactorkit.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.SemanticHoverSection
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange

internal data class ParsedLspHover(
    val range: SourceRange?,
    val sections: List<SemanticHoverSection>,
)

internal object LspHoverParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }
    private const val MAX_SECTIONS = 16
    private const val MAX_SECTION_CHARS = 16_384
    private const val MAX_TOTAL_CHARS = 65_536

    fun parse(resultJson: String): ParsedLspHover? {
        if (resultJson.trim() == "null") return ParsedLspHover(null, emptyList())
        val value = runCatching { json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        val contents = value["contents"] ?: return null
        val sections = mutableListOf<SemanticHoverSection>()
        collect(contents, sections)
        var total = 0
        val bounded = sections.take(MAX_SECTIONS).mapNotNull { section ->
            val remaining = MAX_TOTAL_CHARS - total
            if (remaining <= 0) null else section.copy(value = section.value.take(minOf(MAX_SECTION_CHARS, remaining))).also {
                total += it.value.length
            }
        }
        val range = parseRange(value["range"])
        if ("range" in value && range == null) return null
        return ParsedLspHover(range, bounded)
    }

    private fun collect(element: JsonElement?, output: MutableList<SemanticHoverSection>) {
        if (output.size >= MAX_SECTIONS || element == null) return
        when (element) {
            is JsonPrimitive -> if (element.isString) output += SemanticHoverSection(
                SemanticHoverSection.Format.MARKDOWN, element.content,
            )
            is JsonArray -> element.forEach { collect(it, output) }
            is JsonObject -> {
                val value = element.string("value") ?: return
                when (element.string("kind")) {
                    "plaintext" -> output += SemanticHoverSection(SemanticHoverSection.Format.PLAIN_TEXT, value)
                    "markdown" -> output += SemanticHoverSection(SemanticHoverSection.Format.MARKDOWN, value)
                    else -> {
                        val language = element.string("language")?.takeIf { Regex("[A-Za-z0-9_+.-]{1,32}").matches(it) }
                        output += SemanticHoverSection(
                            SemanticHoverSection.Format.MARKDOWN,
                            if (language == null) value else "```$language\n$value\n```",
                        )
                    }
                }
            }
        }
    }

    private fun parseRange(element: JsonElement?): SourceRange? {
        val range = element as? JsonObject ?: return null
        val start = parsePosition(range["start"]) ?: return null
        val end = parsePosition(range["end"]) ?: return null
        if (end < start) return null
        return SourceRange(start, end)
    }

    private fun parsePosition(element: JsonElement?): SourcePosition? {
        val value = element as? JsonObject ?: return null
        val line = (value["line"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: return null
        val character = (value["character"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: return null
        if (line < 0 || character < 0) return null
        return SourcePosition(line, character)
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content
}

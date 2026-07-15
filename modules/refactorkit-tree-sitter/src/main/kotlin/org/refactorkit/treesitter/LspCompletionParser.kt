package org.refactorkit.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.SemanticCompletionItem
import org.refactorkit.core.SemanticInsertTextFormat
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import org.refactorkit.core.TextEdit

internal data class ParsedLspCompletion(
    val items: List<SemanticCompletionItem>,
    val incomplete: Boolean,
)

/** Strict, bounded normalization of LSP CompletionList and CompletionItem[]. */
internal object LspCompletionParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }
    private const val MAX_ITEMS = 200
    private const val MAX_ADDITIONAL_EDITS = 32
    private const val MAX_COMMIT_CHARACTERS = 16
    private const val MAX_LABEL_CHARS = 512
    private const val MAX_DETAIL_CHARS = 4_096
    private const val MAX_DOCUMENTATION_CHARS = 16_384
    private const val MAX_TEXT_CHARS = 65_536

    fun parse(resultJson: String, requestedLimit: Int): ParsedLspCompletion? {
        if (requestedLimit !in 1..MAX_ITEMS) return null
        if (resultJson.trim() == "null") return ParsedLspCompletion(emptyList(), false)
        val root = runCatching { json.parseToJsonElement(resultJson) }.getOrNull() ?: return null
        val list = root as? JsonObject
        val values = when (root) {
            is JsonArray -> root
            is JsonObject -> root["items"] as? JsonArray ?: return null
            else -> return null
        }
        val serverIncomplete = list?.get("isIncomplete")?.let {
            (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: return null
        } ?: false
        val defaults = list?.get("itemDefaults") as? JsonObject
        if (list?.containsKey("itemDefaults") == true && defaults == null) return null
        val defaultRange = defaults?.get("editRange")?.let(::parseEditRange) ?: if (defaults?.containsKey("editRange") == true) return null else null
        val defaultFormat = parseInsertTextFormat(defaults?.get("insertTextFormat"))
            ?: if (defaults?.containsKey("insertTextFormat") == true) return null else SemanticInsertTextFormat.PLAIN_TEXT
        val defaultCommitCharacters = parseCommitCharacters(defaults?.get("commitCharacters"))
            ?: if (defaults?.containsKey("commitCharacters") == true) return null else emptyList()
        val returned = values.take(requestedLimit).map {
            parseItem(it, defaultRange, defaultFormat, defaultCommitCharacters) ?: return null
        }
        return ParsedLspCompletion(returned, serverIncomplete || values.size > returned.size)
    }

    private fun parseItem(
        element: JsonElement,
        defaultRange: SourceRange?,
        defaultFormat: SemanticInsertTextFormat,
        defaultCommitCharacters: List<String>,
    ): SemanticCompletionItem? {
        val item = element as? JsonObject ?: return null
        val label = item.string("label")?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_CHARS } ?: return null
        val detail = item.optionalString("detail", MAX_DETAIL_CHARS) ?: if ("detail" in item) return null else null
        val documentation = parseDocumentation(item["documentation"]) ?: if ("documentation" in item) return null else null
        val sortText = item.optionalString("sortText", MAX_TEXT_CHARS) ?: if ("sortText" in item) return null else null
        val filterText = item.optionalString("filterText", MAX_TEXT_CHARS) ?: if ("filterText" in item) return null else null
        val explicitInsertText = item.optionalString("insertText", MAX_TEXT_CHARS) ?: if ("insertText" in item) return null else null
        val textEditText = item.optionalString("textEditText", MAX_TEXT_CHARS) ?: if ("textEditText" in item) return null else null
        val insertTextFormat = parseInsertTextFormat(item["insertTextFormat"])
            ?: if ("insertTextFormat" in item) return null else defaultFormat
        val commitCharacters = parseCommitCharacters(item["commitCharacters"])
            ?: if ("commitCharacters" in item) return null else defaultCommitCharacters

        var replacementRange = defaultRange
        var editText: String? = null
        item["textEdit"]?.let { value ->
            val edit = parseCompletionTextEdit(value) ?: return null
            replacementRange = edit.first
            editText = edit.second
        }
        val additional = item["additionalTextEdits"]?.let { value ->
            val edits = value as? JsonArray ?: return null
            if (edits.size > MAX_ADDITIONAL_EDITS) return null
            edits.map { parseAdditionalEdit(it) ?: return null }
        } ?: emptyList()
        if (additional.indices.any { left -> additional.indices.any { right -> left < right && additional[left].range.overlaps(additional[right].range) } }) {
            return null
        }
        val kindValue = item["kind"]
        val kindNumber = (kindValue as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull
        if (kindValue != null && kindNumber == null) return null
        val kind = kindNumber?.let(::symbolKind) ?: Symbol.Kind.UNKNOWN
        return SemanticCompletionItem(
            label = label,
            kind = kind,
            detail = detail,
            documentation = documentation,
            sortText = sortText,
            filterText = filterText,
            insertText = editText ?: textEditText ?: explicitInsertText,
            insertTextFormat = insertTextFormat,
            commitCharacters = commitCharacters,
            replacementRange = replacementRange,
            additionalTextEdits = additional,
        )
    }

    private fun parseCompletionTextEdit(element: JsonElement): Pair<SourceRange, String>? {
        val value = element as? JsonObject ?: return null
        val newText = value.string("newText")?.takeIf { it.length <= MAX_TEXT_CHARS } ?: return null
        val range = value["range"]?.let(::parseRange) ?: value["replace"]?.let(::parseRange) ?: return null
        return range to newText
    }

    private fun parseAdditionalEdit(element: JsonElement): TextEdit? {
        val value = element as? JsonObject ?: return null
        val range = parseRange(value["range"]) ?: return null
        val newText = value.string("newText")?.takeIf { it.length <= MAX_TEXT_CHARS } ?: return null
        return TextEdit(range, newText)
    }

    private fun parseEditRange(element: JsonElement): SourceRange? {
        val value = element as? JsonObject ?: return null
        return if ("start" in value) parseRange(value) else parseRange(value["replace"])
    }

    private fun parseInsertTextFormat(element: JsonElement?): SemanticInsertTextFormat? {
        val value = (element as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: return null
        return when (value) {
            1 -> SemanticInsertTextFormat.PLAIN_TEXT
            2 -> SemanticInsertTextFormat.SNIPPET
            else -> null
        }
    }

    private fun parseCommitCharacters(element: JsonElement?): List<String>? {
        val values = element as? JsonArray ?: return null
        if (values.size > MAX_COMMIT_CHARACTERS) return null
        return values.map { value ->
            (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                ?.takeIf { it.isNotEmpty() && it.length <= 8 } ?: return null
        }
    }

    private fun parseDocumentation(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.takeIf(JsonPrimitive::isString)?.content?.takeIf { it.length <= MAX_DOCUMENTATION_CHARS }
        is JsonObject -> element.string("value")?.takeIf { it.length <= MAX_DOCUMENTATION_CHARS }
        else -> null
    }

    private fun JsonObject.optionalString(name: String, maxChars: Int): String? =
        string(name)?.takeIf { it.length <= maxChars }

    private fun parseRange(element: JsonElement?): SourceRange? {
        val range = element as? JsonObject ?: return null
        val start = parsePosition(range["start"]) ?: return null
        val end = parsePosition(range["end"]) ?: return null
        return if (end < start) null else SourceRange(start, end)
    }

    private fun parsePosition(element: JsonElement?): SourcePosition? {
        val value = element as? JsonObject ?: return null
        val line = (value["line"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: return null
        val character = (value["character"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull ?: return null
        return if (line < 0 || character < 0) null else SourcePosition(line, character)
    }

    private fun symbolKind(kind: Int): Symbol.Kind = when (kind) {
        2 -> Symbol.Kind.METHOD
        3, 24 -> Symbol.Kind.FUNCTION
        4 -> Symbol.Kind.CONSTRUCTOR
        5 -> Symbol.Kind.FIELD
        6, 12 -> Symbol.Kind.VARIABLE
        7 -> Symbol.Kind.CLASS
        8 -> Symbol.Kind.INTERFACE
        9, 17 -> Symbol.Kind.MODULE
        10 -> Symbol.Kind.PROPERTY
        13 -> Symbol.Kind.ENUM
        19 -> Symbol.Kind.PACKAGE
        20, 21 -> Symbol.Kind.CONSTANT
        22 -> Symbol.Kind.RECORD
        25 -> Symbol.Kind.TYPE_PARAMETER
        else -> Symbol.Kind.UNKNOWN
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content
}

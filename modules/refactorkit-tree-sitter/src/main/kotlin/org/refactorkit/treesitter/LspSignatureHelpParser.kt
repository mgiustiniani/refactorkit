package org.refactorkit.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.SemanticSignature
import org.refactorkit.core.SemanticSignatureParameter

internal data class ParsedLspSignatureHelp(
    val signatures: List<SemanticSignature>,
    val activeSignature: Int?,
    val activeParameter: Int?,
)

/** Strict bounded normalization of LSP SignatureHelp. */
internal object LspSignatureHelpParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }
    private const val MAX_SIGNATURES = 16
    private const val MAX_PARAMETERS = 64
    private const val MAX_LABEL_CHARS = 8_192
    private const val MAX_DOCUMENTATION_CHARS = 16_384

    fun parse(resultJson: String): ParsedLspSignatureHelp? {
        if (resultJson.trim() == "null") return ParsedLspSignatureHelp(emptyList(), null, null)
        val root = runCatching { json.parseToJsonElement(resultJson) as? JsonObject }.getOrNull() ?: return null
        val values = root["signatures"] as? JsonArray ?: return null
        if (values.size > MAX_SIGNATURES) return null
        val parsed = values.map { parseSignature(it) ?: return null }
        val activeSignature = optionalIndex(root, "activeSignature") ?: if ("activeSignature" in root) return null else {
            if (parsed.isEmpty()) null else 0
        }
        if (activeSignature != null && activeSignature !in parsed.indices) return null
        val selected = activeSignature?.let(parsed::get)
        val selectedObject = activeSignature?.let { values[it] as JsonObject }
        val signatureActiveParameter = selectedObject?.let { optionalIndex(it, "activeParameter") }
            ?: if (selectedObject?.containsKey("activeParameter") == true) return null else null
        val activeParameter = optionalIndex(root, "activeParameter") ?: if ("activeParameter" in root) return null else signatureActiveParameter
        if (activeParameter != null && (selected == null || activeParameter !in selected.parameters.indices)) return null
        return ParsedLspSignatureHelp(parsed, activeSignature, activeParameter)
    }

    private fun parseSignature(element: JsonElement): SemanticSignature? {
        val value = element as? JsonObject ?: return null
        val label = value.string("label")?.takeIf { it.isNotBlank() && it.length <= MAX_LABEL_CHARS } ?: return null
        val documentation = parseDocumentation(value["documentation"])
            ?: if ("documentation" in value) return null else null
        val parameters = value["parameters"]?.let { raw ->
            val array = raw as? JsonArray ?: return null
            if (array.size > MAX_PARAMETERS) return null
            var searchStart = 0
            array.map { parameter ->
                val parsed = parseParameter(parameter, label, searchStart) ?: return null
                searchStart = parsed.labelEnd
                parsed
            }
        } ?: emptyList()
        return SemanticSignature(label, documentation, parameters)
    }

    private fun parseParameter(
        element: JsonElement,
        signatureLabel: String,
        searchStart: Int,
    ): SemanticSignatureParameter? {
        val value = element as? JsonObject ?: return null
        val span = when (val label = value["label"]) {
            is JsonArray -> {
                if (label.size != 2) return null
                val start = label[0].integer() ?: return null
                val end = label[1].integer() ?: return null
                if (start < 0 || end < start || end > signatureLabel.length) return null
                start to end
            }
            is JsonPrimitive -> {
                if (!label.isString || label.content.isEmpty()) return null
                val start = signatureLabel.indexOf(label.content, searchStart).takeIf { it >= 0 }
                    ?: signatureLabel.indexOf(label.content).takeIf { it >= 0 } ?: return null
                start to start + label.content.length
            }
            else -> return null
        }
        val documentation = parseDocumentation(value["documentation"])
            ?: if ("documentation" in value) return null else null
        return SemanticSignatureParameter(span.first, span.second, documentation)
    }

    private fun parseDocumentation(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonPrimitive -> element.takeIf(JsonPrimitive::isString)?.content
            ?.takeIf { it.length <= MAX_DOCUMENTATION_CHARS }
        is JsonObject -> element.string("value")?.takeIf { it.length <= MAX_DOCUMENTATION_CHARS }
        else -> null
    }

    private fun optionalIndex(value: JsonObject, name: String): Int? =
        (value[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull?.takeIf { it >= 0 }

    private fun JsonElement.integer(): Int? = (this as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content
}

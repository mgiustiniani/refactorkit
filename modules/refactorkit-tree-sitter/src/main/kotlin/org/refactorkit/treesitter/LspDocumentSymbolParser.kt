package org.refactorkit.treesitter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.SourceLocation
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.Symbol
import java.net.URI
import java.nio.file.Path

internal object LspDocumentSymbolParser {
    private val json = Json { isLenient = false; ignoreUnknownKeys = true }
    const val MAX_SYMBOLS = 10_000
    const val MAX_DEPTH = 64

    fun parse(
        resultJson: String,
        requestedPath: Path,
        languageId: String,
        remapPath: (Path) -> Path?,
    ): List<Symbol> {
        val array = runCatching { json.parseToJsonElement(resultJson) as? JsonArray }.getOrNull() ?: return emptyList()
        val output = mutableListOf<Symbol>()
        array.forEach { element ->
            if (output.size >= MAX_SYMBOLS) return@forEach
            parseElement(element, requestedPath, languageId, remapPath, output, 0, emptyList())
        }
        return output.sortedWith(compareBy({ it.location.path.toString() }, { it.location.range.start.line }, { it.location.range.start.character }, { it.name }))
    }

    private fun parseElement(
        element: JsonElement,
        requestedPath: Path,
        languageId: String,
        remapPath: (Path) -> Path?,
        output: MutableList<Symbol>,
        depth: Int,
        parentHierarchy: List<LspSemanticSymbolId.Component>,
    ) {
        if (depth > MAX_DEPTH || output.size >= MAX_SYMBOLS) return
        val value = element as? JsonObject ?: return
        val name = value.string("name")?.takeIf { it.isNotBlank() && it.length <= 1_024 } ?: return
        val kind = value.int("kind") ?: return
        val locationObject = value["location"] as? JsonObject
        val path: Path
        val range: SourceRange
        if (locationObject != null) {
            val uri = locationObject.string("uri") ?: return
            val parsed = runCatching { Path.of(URI(uri)).toAbsolutePath().normalize() }.getOrNull() ?: return
            path = remapPath(parsed) ?: return
            range = parseRange(locationObject["range"]) ?: return
        } else {
            path = requestedPath
            range = parseRange(value["selectionRange"] ?: value["range"]) ?: return
        }
        val detail = value.string("detail")?.takeIf { it.length <= MAX_DETAIL_LENGTH }
        val container = value.string("containerName")?.takeIf { it.isNotBlank() && it.length <= MAX_DETAIL_LENGTH }
        val hierarchy = if (locationObject != null) {
            listOfNotNull(container?.let { LspSemanticSymbolId.Component(3, it) }) +
                LspSemanticSymbolId.Component(kind, name, detail)
        } else {
            parentHierarchy + LspSemanticSymbolId.Component(kind, name, detail)
        }
        output += Symbol(
            id = LspSemanticSymbolId.create(languageId, path, hierarchy),
            name = name,
            kind = symbolKind(kind),
            location = SourceLocation(path, range),
            languageId = languageId,
        )
        (value["children"] as? JsonArray)?.forEach { child ->
            parseElement(child, path, languageId, remapPath, output, depth + 1, hierarchy)
        }
    }

    private fun parseRange(element: JsonElement?): SourceRange? {
        val range = element as? JsonObject ?: return null
        val start = parsePosition(range["start"]) ?: return null
        val end = parsePosition(range["end"]) ?: return null
        if (end.line < start.line || end.line == start.line && end.character < start.character) return null
        return SourceRange(start, end)
    }

    private fun parsePosition(element: JsonElement?): SourcePosition? {
        val position = element as? JsonObject ?: return null
        val line = position.int("line") ?: return null
        val character = position.int("character") ?: return null
        if (line < 0 || character < 0) return null
        return SourcePosition(line, character)
    }

    private fun symbolKind(kind: Int): Symbol.Kind = when (kind) {
        2 -> Symbol.Kind.MODULE
        3 -> Symbol.Kind.NAMESPACE
        4 -> Symbol.Kind.PACKAGE
        5 -> Symbol.Kind.CLASS
        6 -> Symbol.Kind.METHOD
        7 -> Symbol.Kind.PROPERTY
        8 -> Symbol.Kind.FIELD
        9 -> Symbol.Kind.CONSTRUCTOR
        10 -> Symbol.Kind.ENUM
        11 -> Symbol.Kind.INTERFACE
        12 -> Symbol.Kind.FUNCTION
        13 -> Symbol.Kind.VARIABLE
        14 -> Symbol.Kind.CONSTANT
        26 -> Symbol.Kind.TYPE_PARAMETER
        else -> Symbol.Kind.UNKNOWN
    }

    private const val MAX_DETAIL_LENGTH = 4_096

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)?.intOrNull
}

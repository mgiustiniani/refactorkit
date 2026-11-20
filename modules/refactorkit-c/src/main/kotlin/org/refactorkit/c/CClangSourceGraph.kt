package org.refactorkit.c

import kotlinx.serialization.json.*
import java.nio.file.Path

internal enum class CClangSourceGraphRefusal { INPUT_LIMIT, INVALID_AST, MACRO_OR_UNOWNED_RANGE }
internal class CClangSourceGraphFailure(val reason: CClangSourceGraphRefusal) : RuntimeException()
internal data class CClangSourceCoordinate(val file: Path, val start: Int, val end: Int)
internal data class CClangSourceEntry(val node: JsonObject, val path: String, val parent: String,
    val location: CClangSourceCoordinate?, val begin: CClangSourceCoordinate?, val end: CClangSourceCoordinate?)

/** Ordered Clang JSON source stream. includedFrom describes the includer, never the owner. */
internal object CClangSourceGraph {
    fun read(ast: JsonObject, tu: Path, sources: Map<Path, String>): List<CClangSourceEntry> {
        fun reject(reason: CClangSourceGraphRefusal): Nothing = throw CClangSourceGraphFailure(reason)
        fun string(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun number(node: JsonObject, key: String) = (node[key] as? JsonPrimitive)?.content?.toIntOrNull()
        if (string(ast, "kind") != "TranslationUnitDecl") reject(CClangSourceGraphRefusal.INVALID_AST)
        val entries = mutableListOf<CClangSourceEntry>()
        var currentFile = tu
        fun coordinate(raw: JsonElement?): CClangSourceCoordinate? {
            if (raw == null) return null
            val node = raw as? JsonObject ?: reject(CClangSourceGraphRefusal.INVALID_AST)
            if (node.containsKey("spellingLoc") || node.containsKey("expansionLoc") ||
                node.containsKey("presumedFile") || node.containsKey("presumedLine")) {
                reject(CClangSourceGraphRefusal.MACRO_OR_UNOWNED_RANGE)
            }
            string(node, "file")?.let { currentFile = Path.of(it).normalize() }
            val start = number(node, "offset") ?: return null
            val length = number(node, "tokLen") ?: reject(CClangSourceGraphRefusal.MACRO_OR_UNOWNED_RANGE)
            val source = sources[currentFile] ?: reject(CClangSourceGraphRefusal.MACRO_OR_UNOWNED_RANGE)
            if (start < 0 || length < 1 || start.toLong() + length > source.length.toLong()) {
                reject(CClangSourceGraphRefusal.MACRO_OR_UNOWNED_RANGE)
            }
            return CClangSourceCoordinate(currentFile, start, start + length)
        }
        fun visit(node: JsonObject, path: String, parent: String, depth: Int) {
            if (depth > 192 || entries.size >= 100_000) reject(CClangSourceGraphRefusal.INPUT_LIMIT)
            val index = entries.size
            entries += CClangSourceEntry(node, path, parent, null, null, null)
            var location: CClangSourceCoordinate? = null
            var begin: CClangSourceCoordinate? = null
            var end: CClangSourceCoordinate? = null
            node.forEach { (key, value) -> when (key) {
                "loc" -> location = coordinate(value)
                "range" -> {
                    val range = value as? JsonObject ?: reject(CClangSourceGraphRefusal.INVALID_AST)
                    begin = coordinate(range["begin"]); end = coordinate(range["end"])
                }
                "inner" -> (value as? JsonArray ?: reject(CClangSourceGraphRefusal.INVALID_AST)).forEachIndexed { childIndex, child ->
                    visit(child as? JsonObject ?: reject(CClangSourceGraphRefusal.INVALID_AST), "$path/$childIndex", path, depth + 1)
                }
            } }
            entries[index] = CClangSourceEntry(node, path, parent, location, begin, end)
        }
        visit(ast, "TU", "", 0)
        return entries.toList()
    }
}

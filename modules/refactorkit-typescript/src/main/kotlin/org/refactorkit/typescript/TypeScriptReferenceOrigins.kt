package org.refactorkit.typescript

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.refactorkit.core.FileEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Path

/** Exact JSONC reference tokens, not compiler-server edits or a textual path search. */
internal object TypeScriptReferenceOrigins {
    fun edits(snapshot: ProjectSnapshot, model: TypeScriptProjectModel, from: Path, to: Path): List<FileEdit.Modify> {
        val files = snapshot.trackedFiles.associateBy { it.path.normalize() }
        return model.projects.mapNotNull { project ->
            val source = files.getValue(project.configPath)
            val origins = Tokens(source.content).references()
            val directory = project.configPath.parent ?: Path.of("")
            val resolved = origins.map { origin ->
                require(origin.value.isNotBlank() && origin.value.none { it == '\\' || it.code < 32 }) { "Non-portable reference token" }
                val raw = Path.of(origin.value)
                require(!raw.isAbsolute) { "Absolute project reference" }
                val target = directory.resolve(raw).normalize()
                if (origin.value.endsWith(".json")) target else target.resolve("tsconfig.json")
            }
            require(resolved.distinct().size == resolved.size && resolved.toSet() == project.references.toSet()) {
                "JSONC reference origins are ambiguous or differ from the effective model"
            }
            val changes = origins.zip(resolved).mapNotNull { (origin, target) ->
                if (!target.startsWith(from)) return@mapNotNull null
                val moved = to.resolve(from.relativize(target))
                val destination = if (origin.value.endsWith(".json")) moved else moved.parent
                var replacement = directory.relativize(destination).toString().replace('\\', '/')
                if (origin.value.startsWith("./") && !replacement.startsWith(".")) replacement = "./$replacement"
                TextEdit(TextEdits.rangeForOffset(source.content, origin.start, origin.end - origin.start), JsonPrimitive(replacement).toString())
            }
            changes.takeIf { it.isNotEmpty() }?.let { FileEdit.Modify(project.configPath, it) }
        }
    }

    private data class Origin(val start: Int, val end: Int, val value: String)

    /** The existing sanitizer preserves UTF-16 offsets; strict JSON validates grammar first. */
    private class Tokens(private val original: String) {
        private val text = JsoncSanitizer.sanitize(original).getOrThrow()
        private var index = 0
        private var nodes = 0
        private val found = mutableListOf<Origin>()

        fun references(): List<Origin> {
            require(text.length == original.length && text.length <= 1_048_576)
            Json.parseToJsonElement(text)
            value(emptyList())
            whitespace()
            require(index == text.length)
            return found
        }

        private fun value(path: List<String>) {
            require(path.size <= 64 && ++nodes <= 100_000) { "JSONC origin traversal limit" }
            whitespace()
            when (text[index]) {
                '{' -> {
                    index++; whitespace()
                    val keys = mutableSetOf<String>()
                    if (text[index] != '}') while (true) {
                        val key = string().value
                        require(keys.add(key)) { "Duplicate JSONC property" }
                        whitespace(); require(text[index++] == ':')
                        value(path + key); whitespace()
                        if (text[index] != ',') break
                        index++; whitespace()
                    }
                    require(text[index++] == '}')
                }
                '[' -> {
                    index++; whitespace()
                    var item = 0
                    if (text[index] != ']') while (true) {
                        value(path + (item++).toString()); whitespace()
                        if (text[index] != ',') break
                        index++; whitespace()
                    }
                    require(text[index++] == ']')
                }
                '"' -> {
                    val token = string()
                    if (path.size == 3 && path[0] == "references" && path[1].toIntOrNull() != null && path[2] == "path") found += token
                }
                else -> while (index < text.length && text[index] !in ",]} \r\n\t") index++
            }
        }

        private fun string(): Origin {
            val start = index
            require(text[index++] == '"')
            while (text[index] != '"') {
                if (text[index] == '\\') index++
                index++
            }
            index++
            return Origin(start, index, (Json.parseToJsonElement(text.substring(start, index)) as JsonPrimitive).content)
        }

        private fun whitespace() { while (index < text.length && text[index].isWhitespace()) index++ }
    }
}

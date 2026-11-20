package org.refactorkit.c

import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.net.URI
import java.nio.file.Path

internal data class CClangdSymbolInfoSite(val file: Path, val range: SourceRange)

/** Native payload identity is not family authority without original TU and source ownership. */
internal data class CClangdSymbolInfo(
    val name: String,
    val containerName: String?,
    val usr: String,
    val id: String,
    val declaration: CClangdSymbolInfoSite,
    val definition: CClangdSymbolInfoSite?,
)

internal enum class CClangdSymbolInfoRefusal {
    INVALID_RESPONSE, AMBIGUOUS, INPUT_LIMIT, UNOWNED_SOURCE, INVALID_RANGE, SOURCE_MISMATCH,
}

internal sealed interface CClangdSymbolInfoReadout {
    data class Found(val info: CClangdSymbolInfo) : CClangdSymbolInfoReadout
    data object NotFound : CClangdSymbolInfoReadout
    data class Refused(val reason: CClangdSymbolInfoRefusal) : CClangdSymbolInfoReadout
}

private class SymbolInfoFailure(val reason: CClangdSymbolInfoRefusal) : RuntimeException()

/** Pure decoder of the installed clangd 22 symbolInfo response body. No inferred definitions. */
internal object CClangdSymbolInfoDecoder {
    fun decode(body: String, sources: Map<Path, String>): CClangdSymbolInfoReadout = try {
        check(body.length <= 1024 * 1024 && body.toByteArray(Charsets.UTF_8).size <= 1024 * 1024 &&
            sources.size <= 32 && sources.values.sumOf { it.length.toLong() } <= 16 * 1024 * 1024,
            CClangdSymbolInfoRefusal.INPUT_LIMIT)
        check(sources.isNotEmpty() && sources.keys.all { it.isAbsolute && it == it.normalize() },
            CClangdSymbolInfoRefusal.UNOWNED_SOURCE)
        boundDepth(body)
        val result = Json.parseToJsonElement(body) as? JsonArray ?: fail(CClangdSymbolInfoRefusal.INVALID_RESPONSE)
        if (result.isEmpty()) CClangdSymbolInfoReadout.NotFound else {
            check(result.size == 1, CClangdSymbolInfoRefusal.AMBIGUOUS)
            val value = result.single() as? JsonObject ?: fail(CClangdSymbolInfoRefusal.INVALID_RESPONSE)
            val name = string(value["name"], 512)
            check(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
            val usr = string(value["usr"], 4096)
            val id = string(value["id"], 16)
            check(id.matches(Regex("[A-Fa-f0-9]{16}")), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
            val container = when (val raw = value["containerName"]) {
                null, JsonNull -> null
                else -> string(raw, 512, allowEmpty = true)
            }
            val declaration = site(value["declarationRange"], sources, name)
            val definition = if ("definitionRange" in value) site(value["definitionRange"], sources, name) else null
            CClangdSymbolInfoReadout.Found(CClangdSymbolInfo(name, container, usr, id, declaration, definition))
        }
    } catch (failure: SymbolInfoFailure) {
        CClangdSymbolInfoReadout.Refused(failure.reason)
    } catch (_: IllegalArgumentException) {
        CClangdSymbolInfoReadout.Refused(CClangdSymbolInfoRefusal.INVALID_RESPONSE)
    }

    private fun site(raw: JsonElement?, sources: Map<Path, String>, name: String): CClangdSymbolInfoSite {
        val value = raw as? JsonObject ?: fail(CClangdSymbolInfoRefusal.INVALID_RESPONSE)
        val encoded = string(value["uri"], 16 * 1024)
        val file = try {
            val uri = URI(encoded)
            check(uri.scheme == "file" && uri.rawAuthority.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null,
                CClangdSymbolInfoRefusal.UNOWNED_SOURCE)
            Path.of(uri)
        } catch (failure: SymbolInfoFailure) { throw failure }
        catch (_: Exception) { fail(CClangdSymbolInfoRefusal.UNOWNED_SOURCE) }
        check(file.isAbsolute && file == file.normalize() && file in sources, CClangdSymbolInfoRefusal.UNOWNED_SOURCE)
        val range = value["range"] as? JsonObject ?: fail(CClangdSymbolInfoRefusal.INVALID_RANGE)
        val start = position(range["start"])
        val end = position(range["end"])
        val source = sources.getValue(file)
        val offsets = try { TextEdits.offsetOf(source, start) to TextEdits.offsetOf(source, end) }
            catch (_: IllegalArgumentException) { fail(CClangdSymbolInfoRefusal.INVALID_RANGE) }
        check(offsets.first < offsets.second, CClangdSymbolInfoRefusal.INVALID_RANGE)
        check(source.substring(offsets.first, offsets.second) == name, CClangdSymbolInfoRefusal.SOURCE_MISMATCH)
        return CClangdSymbolInfoSite(file, SourceRange(start, end))
    }

    private fun position(raw: JsonElement?): SourcePosition {
        val value = raw as? JsonObject ?: fail(CClangdSymbolInfoRefusal.INVALID_RANGE)
        fun integer(key: String) = (value[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
            ?.takeIf { it >= 0 } ?: fail(CClangdSymbolInfoRefusal.INVALID_RANGE)
        return SourcePosition(integer("line"), integer("character"))
    }

    private fun string(raw: JsonElement?, maximum: Int, allowEmpty: Boolean = false): String {
        val value = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: fail(CClangdSymbolInfoRefusal.INVALID_RESPONSE)
        check(value.length <= maximum && (allowEmpty || value.isNotBlank()) && value.none { it.isISOControl() },
            CClangdSymbolInfoRefusal.INVALID_RESPONSE)
        return value
    }

    private fun boundDepth(body: String) {
        var quoted = false
        var escaped = false
        var depth = 0
        for (character in body) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '[', '{' -> { depth++; check(depth <= 32, CClangdSymbolInfoRefusal.INPUT_LIMIT) }
                ']', '}' -> depth--
            }
        }
    }

    private fun check(condition: Boolean, reason: CClangdSymbolInfoRefusal) { if (!condition) fail(reason) }
    private fun fail(reason: CClangdSymbolInfoRefusal): Nothing = throw SymbolInfoFailure(reason)
}

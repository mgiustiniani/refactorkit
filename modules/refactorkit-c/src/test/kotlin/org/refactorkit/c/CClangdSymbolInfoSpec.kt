package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.net.URI
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: retained native responses, never manufactured positive AST facts. */
class CClangdSymbolInfoSpec : StringSpec({
    val fixture = requireNotNull(CClangdSymbolInfoSpec::class.java
        .getResourceAsStream("/clangd-symbol-info/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }
    val modes = fixture.getValue("results").jsonArray.map { it.jsonObject }
    fun sources(mode: JsonObject): Map<Path, String> {
        val root = Path.of(mode.getValue("command").jsonArray.last().jsonPrimitive.content.substringAfter("="))
        return mode.getValue("sources").jsonObject.map { (file, text) -> root.resolve(file) to text.jsonPrimitive.content }.toMap()
    }
    fun observations(mode: JsonObject) = mode.getValue("observations").jsonArray.map { it.jsonObject }
    fun body(observation: JsonObject) = observation.getValue("symbolInfo").jsonObject.getValue("result").toString()
    val sampleMode = modes.first()
    val captured = sources(sampleMode)
    val sample = observations(sampleMode).first().getValue("symbolInfo").jsonObject
        .getValue("result").jsonArray.single().jsonObject
    fun altered(key: String, value: JsonElement) = JsonArray(listOf(JsonObject(sample + (key to value)))).toString()
    fun refuse(body: String, reason: CClangdSymbolInfoRefusal, inputs: Map<Path, String> = captured) {
        CClangdSymbolInfoDecoder.decode(body, inputs)
            .shouldBeInstanceOf<CClangdSymbolInfoReadout.Refused>().reason shouldBe reason
    }

    for (mode in modes) {
        "${mode.getValue("standard").jsonPrimitive.content} native prototypes retain absent definitions and distinct source identities" {
            val inputs = sources(mode)
            val all = observations(mode).associate { raw ->
                raw.getValue("label").jsonPrimitive.content to CClangdSymbolInfoDecoder.decode(body(raw), inputs)
                    .shouldBeInstanceOf<CClangdSymbolInfoReadout.Found>().info
            }
            val first = all.getValue("prototype-first")
            val second = all.getValue("prototype-second")
            val other = all.getValue("other-prototype")
            val global = all.getValue("global")
            val peer = all.getValue("peer-global")
            first shouldBe second
            first.containerName shouldBe "inspect"
            first.name shouldBe "opaque"
            first.declaration.range shouldBe SourceRange(SourcePosition(1, 19), SourcePosition(1, 25))
            first.definition shouldBe null
            other.containerName shouldBe "inspect_other"
            (first.usr == other.usr) shouldBe false
            (first.id == other.id) shouldBe false
            other.definition shouldBe null
            global.containerName shouldBe null
            peer.containerName shouldBe null
            global.usr shouldBe peer.usr
            global.id shouldBe peer.id
            (global.declaration.file == peer.declaration.file) shouldBe false
            global.definition shouldBe global.declaration
            peer.definition shouldBe peer.declaration
            first.declaration.file shouldBe global.declaration.file
            first.declaration.file.fileName.toString() shouldBe "api.c"
            peer.declaration.file.fileName.toString() shouldBe "main.c"
        }
    }

    "an empty native array is an absent symbol" {
        CClangdSymbolInfoDecoder.decode("[]", captured) shouldBe CClangdSymbolInfoReadout.NotFound
    }
    "null objects and malformed JSON are unavailable rather than absent" {
        listOf("null", "{}", "[", "false").forEach { refuse(it, CClangdSymbolInfoRefusal.INVALID_RESPONSE) }
    }
    "multiple native identities require explicit disambiguation" {
        refuse(JsonArray(listOf(sample, sample)).toString(), CClangdSymbolInfoRefusal.AMBIGUOUS)
    }
    "native name USR and ID must be bounded strings" {
        for (key in listOf("name", "usr", "id")) for (value in listOf(JsonNull, JsonPrimitive(42), JsonPrimitive("")))
            refuse(altered(key, value), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
    }
    "a present container must be a string or the native null" {
        refuse(altered("containerName", JsonPrimitive(42)), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
    }
    "declaration ranges are required and a present definition cannot be null" {
        refuse(JsonArray(listOf(JsonObject(sample - "declarationRange"))).toString(), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
        refuse(altered("definitionRange", JsonNull), CClangdSymbolInfoRefusal.INVALID_RESPONSE)
    }
    "a location outside captured source images is unowned" {
        val location = sample.getValue("declarationRange").jsonObject
        refuse(altered("declarationRange", JsonObject(location + ("uri" to JsonPrimitive("file:///tmp/not-captured.c")))),
            CClangdSymbolInfoRefusal.UNOWNED_SOURCE)
    }
    "unsupported or decorated URIs cannot acquire source ownership" {
        val location = sample.getValue("declarationRange").jsonObject
        for (uri in listOf("https://example.invalid/api.c", location.getValue("uri").jsonPrimitive.content + "?query=1", "file://remote/tmp/api.c"))
            refuse(altered("declarationRange", JsonObject(location + ("uri" to JsonPrimitive(uri)))),
                CClangdSymbolInfoRefusal.UNOWNED_SOURCE)
    }
    "negative fractional inverted and out of bounds coordinates are invalid" {
        val location = sample.getValue("declarationRange").jsonObject
        val range = location.getValue("range").jsonObject
        val start = range.getValue("start").jsonObject
        for (value in listOf(JsonPrimitive(-1), JsonPrimitive(1.5), JsonPrimitive("1"), JsonPrimitive(999999))) {
            val bad = JsonObject(range + ("start" to JsonObject(start + ("line" to value))))
            refuse(altered("declarationRange", JsonObject(location + ("range" to bad))), CClangdSymbolInfoRefusal.INVALID_RANGE)
        }
        val inverted = JsonObject(range + ("start" to range.getValue("end")))
        refuse(altered("declarationRange", JsonObject(location + ("range" to inverted))), CClangdSymbolInfoRefusal.INVALID_RANGE)
    }
    "native ranges must name the exact captured source token" {
        val file = Path.of(URI(sample.getValue("declarationRange").jsonObject.getValue("uri").jsonPrimitive.content))
        refuse(JsonArray(listOf(sample)).toString(), CClangdSymbolInfoRefusal.SOURCE_MISMATCH,
            captured + (file to captured.getValue(file).replace("opaque", "rivalx")))
    }
    "oversized native output is refused before JSON traversal" {
        refuse(" ".repeat(1024 * 1024 + 1), CClangdSymbolInfoRefusal.INPUT_LIMIT)
    }
    "oversized source sets cannot establish range authority" {
        val file = captured.keys.first()
        refuse("[]", CClangdSymbolInfoRefusal.INPUT_LIMIT, (0..32).associate { file.resolveSibling("input$it.c") to "" })
    }
})

package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Negative-only corruption of real native input; never positive compiler evidence. */
class CMalformedRecordScopeSpec : StringSpec({
    val modes = requireNotNull(CMalformedRecordScopeSpec::class.java.getResourceAsStream("/scoped-record-navigation/native-fixture.json.gz"))
        .use { stream -> GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        } }.getValue("modes").jsonObject
    val api = Path.of("src/api.c")
    for (mode in listOf("c11", "c17")) for (corruption in listOf("missing", "inverted", "detached")) {
        "$mode $corruption compound range refuses instead of inventing unused local type" {
            val data = modes.getValue(mode).jsonObject.getValue("shadow").jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
            var changed = false
            fun corrupt(value: JsonElement): JsonElement = when (value) {
                is JsonObject -> if (!changed && value["kind"]?.jsonPrimitive?.content == "CompoundStmt") {
                    changed = true
                    if (corruption == "missing") JsonObject(value - "range") else {
                        val range = value.getValue("range").jsonObject
                        val altered = if (corruption == "inverted") range + mapOf("begin" to range.getValue("end"), "end" to range.getValue("begin"))
                        else {
                            val source = sources.getValue(api)
                            val marker = "    struct meter {"
                            val start = source.indexOf(marker).also { require(it >= 0) } + marker.indexOf("meter") + "meter".length
                            range + ("begin" to buildJsonObject { put("offset", start); put("tokLen", 1) })
                        }
                        JsonObject(value + ("range" to JsonObject(altered)))
                    }
                } else JsonObject(value.mapValues { corrupt(it.value) })
                is JsonArray -> JsonArray(value.map(::corrupt))
                else -> value
            }
            val bad = asts + (api to corrupt(asts.getValue(api)).jsonObject)
            changed shouldBe true
            val source = sources.getValue(api)
            val marker = "    struct meter {"
            val at = source.indexOf(marker).also { require(it >= 0) } + marker.indexOf("meter")
            val result = CRecordNavigationFactsDecoder.decode(bad, sources, api, at, api)
            if (corruption != "detached" && result is CRecordNavigationReadout.Found) result.facts.references shouldBe emptyList()
            result.shouldBeInstanceOf<CRecordNavigationReadout.Refused>().reason shouldBe CRecordNavigationRefusal.INVALID_AST
        }
    }
})

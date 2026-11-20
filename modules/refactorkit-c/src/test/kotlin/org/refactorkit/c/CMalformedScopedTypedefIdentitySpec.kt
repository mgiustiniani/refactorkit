package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: explicitly corrupted native identity fields are negative-only evidence. */
class CMalformedScopedTypedefIdentitySpec : StringSpec({
    val modes = requireNotNull(CMalformedScopedTypedefIdentitySpec::class.java
        .getResourceAsStream("/scoped-typedef-navigation/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }.getValue("modes").jsonObject
    val api = Path.of("src/api.c")
    for (mode in listOf("c11", "c17")) for ((shape, bad) in listOf("numeric" to JsonPrimitive(42), "explicit null" to JsonNull)) {
        "$mode a $shape native alias identity cannot become an absent optional binding" {
            val data = modes.getValue(mode).jsonObject.getValue("block").jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
            var changes = 0
            fun corrupt(value: JsonElement): JsonElement = when (value) {
                is JsonArray -> JsonArray(value.map(::corrupt))
                is JsonObject -> {
                    val node = JsonObject(value.mapValues { corrupt(it.value) })
                    val type = node["type"] as? JsonObject
                    if (node["loc"] is JsonObject && node["kind"]?.jsonPrimitive?.content == "VarDecl" && node["name"]?.jsonPrimitive?.content == "value" &&
                        type?.get("qualType")?.jsonPrimitive?.content == "count_t") {
                        val capturedType = requireNotNull(type)
                        check(capturedType.containsKey("typeAliasDeclId"))
                        changes++
                        JsonObject(node + ("type" to JsonObject(capturedType + ("typeAliasDeclId" to bad))))
                    } else node
                }
                else -> value
            }
            val asts = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to
                (if (Path.of(p) == api) corrupt(a).jsonObject else a.jsonObject) }.toMap()
            changes shouldBe 1
            val marker = "    typedef int count_t;"
            val offset = sources.getValue(api).indexOf(marker).also { require(it >= 0) } + marker.indexOf("count_t")
            CScopedTypedefNavigationFactsDecoder.decode(asts, sources, api, offset, api)
                .shouldBeInstanceOf<CScopedTypedefNavigationReadout.Refused>().reason shouldBe CRecordNavigationRefusal.INVALID_AST
        }
    }
})

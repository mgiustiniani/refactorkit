package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: native tag families retain C block/prototype identity. */
class CScopedRecordNavigationFactsSpec : StringSpec({
    val modes = requireNotNull(CScopedRecordNavigationFactsSpec::class.java.getResourceAsStream("/scoped-record-navigation/native-fixture.json.gz"))
        .use { stream -> GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        } }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c")
    fun sources(data: JsonObject) = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
    fun trees(data: JsonObject) = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
    fun span(text: String, marker: String, name: String): CFunctionByteSpan {
        val at = text.indexOf(marker).also { require(it >= 0) { "Missing first-party marker: $marker" } } + marker.indexOf(name)
        return CFunctionByteSpan(at, at + name.length)
    }
    fun physical(sites: List<CRecordNavigationSite>) = sites.map { it.file to it.span }.toSet()
    for ((mode, rawProfiles) in modes) {
        for ((profile, raw) in rawProfiles.jsonObject.filterKeys { it != "prototype-tag" }) {
            for ((keyword, name) in listOf("struct" to "meter", "union" to "payload")) {
                "$mode $profile $keyword reads the selected block family and excludes outer and peer homonyms" {
                    val data = raw.jsonObject; val text = sources(data); val asts = trees(data); val source = text.getValue(api)
                    val forward = profile == "forward-block"
                    val indent = if (forward) "        " else "    "
                    val declaration = span(source, "$indent$keyword $name" + if (forward) ";" else " {", name)
                    val definition = span(source, "$indent$keyword $name {", name)
                    val found = CRecordNavigationFactsDecoder.decode(asts, text, api, declaration.start, api)
                        .shouldBeInstanceOf<CRecordNavigationReadout.Found>().facts
                    physical(found.definitions) shouldBe setOf(api to definition)
                    physical(found.declarations) shouldBe if (forward) setOf(api to declaration, api to definition) else setOf(api to definition)
                    val localMarkers = if (forward) listOf("$indent$keyword $name *before", "$indent$keyword $name " + if (keyword == "struct") "item" else "box")
                        else listOf("$indent$keyword $name " + if (keyword == "struct") "item" else "box")
                    physical(found.references) shouldBe localMarkers.map { api to span(source, it, name) }.toSet()
                    found.references.map { it.translationUnit }.toSet() shouldBe setOf(api)
                    if (profile != "block") {
                        val global = span(source, "$keyword $name {", name)
                        val outer = CRecordNavigationFactsDecoder.decode(asts, text, api, global.start, api)
                            .shouldBeInstanceOf<CRecordNavigationReadout.Found>().facts
                        physical(outer.definitions) shouldBe setOf(api to global)
                        physical(outer.declarations) shouldBe setOf(api to global)
                        val markers = if (profile == "shadow") listOf("$keyword $name " + if (keyword == "struct") "item = {3}" else "box = {4}")
                            else if (keyword == "struct") listOf("struct meter outside", "struct meter after") else listOf("union payload outer_box", "union payload after_box")
                        physical(outer.references) shouldBe markers.map { api to span(source, it, name) }.toSet()
                    }
                    CRecordNavigationFactsDecoder.decode(asts.filterKeys { it != peer }, text, api, declaration.start, api)
                        .shouldBeInstanceOf<CRecordNavigationReadout.Refused>().reason shouldBe CRecordNavigationRefusal.INCOMPLETE_TUS
                }
            }
        }
        "$mode prototype tag identity absent from native declaration nodes cannot attach to a later global tag" {
            val data = rawProfiles.jsonObject.getValue("prototype-tag").jsonObject
            val text = sources(data); val asts = trees(data)
            val global = span(text.getValue(api), "struct opaque {", "opaque")
            CRecordNavigationFactsDecoder.decode(asts, text, api, global.start, api)
                .shouldBeInstanceOf<CRecordNavigationReadout.Refused>()
        }
    }
})

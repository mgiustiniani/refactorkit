package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: native prototype facts retain source/TU ownership without fabricated AST nodes. */
class CPrototypeTagNavigationFactsSpec : StringSpec({
    val fixture = requireNotNull(CPrototypeTagNavigationFactsSpec::class.java
        .getResourceAsStream("/prototype-tag-navigation/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    val observations = Json.parseToJsonElement(fixture.getValue("observationBytes").jsonPrimitive.content)
        .jsonObject.getValue("results").jsonArray.map { it.jsonObject }
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c")
    fun modeName(mode: JsonObject) = mode.getValue("standard").jsonPrimitive.content
    fun sources(mode: JsonObject) = mode.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    fun asts(mode: JsonObject) = fixture.getValue("asts").jsonObject.getValue(modeName(mode)).jsonObject
        .map { (p, v) -> Path.of(p) to Json.parseToJsonElement(v.jsonPrimitive.content).jsonObject }.toMap()
    fun root(mode: JsonObject) = Path.of(mode.getValue("command").jsonArray.last().jsonPrimitive.content.substringAfter("="))
    fun responses(mode: JsonObject): Map<CPrototypeTagCursor, String> = mode.getValue("observations").jsonArray
        .map { it.jsonObject }.filter { it.getValue("label").jsonPrimitive.content in setOf("prototype-first", "prototype-second", "other-prototype") }
        .associate { raw ->
            val point = raw.getValue("position").jsonObject
            val line = point.getValue("line").jsonPrimitive.int; val ch = point.getValue("character").jsonPrimitive.int
            CPrototypeTagCursor(api, api, SourceRange(SourcePosition(line, ch), SourcePosition(line, ch + 6))) to
                raw.getValue("symbolInfo").jsonObject.getValue("result").toString()
        }
    fun index(mode: JsonObject) = CCompilerDeclarationIndex.analyze(asts(mode), sources(mode))
        .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
    fun alter(body: String, change: (JsonObject) -> JsonObject): String =
        JsonArray(listOf(change(Json.parseToJsonElement(body).jsonArray.single().jsonObject))).toString()
    val sample = observations.first(); val rawResponses = responses(sample)
    fun refused(values: Map<CPrototypeTagCursor, String>, inputs: Map<Path, String> = sources(sample)) {
        CPrototypeTagFactsDecoder.decode(asts(sample), inputs, root(sample), values)
            .shouldBeInstanceOf<CPrototypeTagReadout.Refused>()
    }

    for (mode in observations) {
        "${modeName(mode)} composed prototype reads separate native scopes and source-private global tags" {
            val trees = asts(mode); val inputs = sources(mode)
            val queries = CPrototypeTagFactsDecoder.queries(trees, inputs)
                .shouldBeInstanceOf<CPrototypeTagQueriesReadout.Found>().queries
            queries.size shouldBe 3
            queries.map { it.cursor }.toSet() shouldBe responses(mode).keys
            val facts = CPrototypeTagFactsDecoder.decode(trees, inputs, root(mode), responses(mode))
                .shouldBeInstanceOf<CPrototypeTagReadout.Found>().facts
            val composed = index(mode).withPrototypeTags(facts)
            val tags = composed.search("opaque").items.filter { it.kind == CCompilerDeclarationKind.STRUCT }
            tags.size shouldBe 4
            tags.map { it.id }.toSet().size shouldBe 4
            facts.declarations.size shouldBe 2
            for (query in queries) {
                val selected = CRecordCursorBinding.resolve(trees, inputs, composed, query.cursor.file,
                    query.cursor.range.start, prototypeTags = facts).shouldBeInstanceOf<CRecordCursorReadout.Found>().declaration
                val family = CCompilerNavigationFactsDecoder.decode(trees, inputs, selected.id, prototypeTags = facts)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                family.definitions shouldBe emptyList()
                family.declarations.size shouldBe 1
                family.references.size shouldBe if (query.functionName == "inspect") 1 else 0
                family.selected.translationUnit shouldBe api
                family.selected.scopeId shouldBe query.scopeId
                family.declarations.single().file shouldBe api
                family.references.forEach { it.translationUnits shouldBe setOf(api) }
            }
            val first = facts.declarations.single { it.range.start.line == 1 }
            first.range.start shouldBe SourcePosition(1, 19)
            val globals = tags.filter { it !in facts.declarations }
            globals.map { it.file }.toSet() shouldBe setOf(api, peer)
            for (global in globals) {
                val read = CCompilerNavigationFactsDecoder.decode(trees, inputs, global.id, prototypeTags = facts)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                read.definitions.size shouldBe 1
                read.definitions.single().file shouldBe global.file
                read.references.size shouldBe if (global.file == api) 1 else 0
            }
        }
    }
    "missing native cursor evidence cannot become an empty prototype family" {
        refused(rawResponses - rawResponses.keys.first())
    }
    "extra cursor evidence cannot gain authority outside compiler candidates" {
        val extra = rawResponses.keys.first().copy(file = peer, translationUnit = peer)
        refused(rawResponses + (extra to rawResponses.values.first()))
    }
    "a native container from another prototype cannot bind the selected scope" {
        refused(rawResponses.mapValues { (_, body) -> alter(body) { JsonObject(it + ("containerName" to JsonPrimitive("foreign"))) } })
    }
    "prototype-only declarations never acquire an invented complete definition" {
        refused(rawResponses.mapValues { (_, body) -> alter(body) { JsonObject(it + ("definitionRange" to it.getValue("declarationRange"))) } })
    }
    "different prototype scopes cannot share one native identity" {
        val first = Json.parseToJsonElement(rawResponses.values.first()).jsonArray.single().jsonObject
        refused(rawResponses.mapValues { (_, body) -> alter(body) { JsonObject(it + mapOf("usr" to first.getValue("usr"), "id" to first.getValue("id"))) } })
    }
    "native evidence must still name the captured source token" {
        refused(rawResponses, sources(sample) + (api to sources(sample).getValue(api).replace("opaque", "rivalx")))
    }
    "another source file declaration cannot introduce a source-private prototype tag" {
        val peerObservation = sample.getValue("observations").jsonArray.map { it.jsonObject }
            .single { it.getValue("label").jsonPrimitive.content == "peer-global" }
        val other = peerObservation.getValue("symbolInfo").jsonObject.getValue("result").toString()
        refused(rawResponses.mapValues { other })
    }
    "prototype proof cannot be reused after its captured source changes" {
        val trees = asts(sample); val inputs = sources(sample)
        val facts = CPrototypeTagFactsDecoder.decode(trees, inputs, root(sample), rawResponses)
            .shouldBeInstanceOf<CPrototypeTagReadout.Found>().facts
        val changed = inputs + (api to inputs.getValue(api).replace("int later", "int other"))
        val global = index(sample).declarations.single { it.file == api && it.kind == CCompilerDeclarationKind.STRUCT && it.name == "opaque" }
        CCompilerNavigationFactsDecoder.decode(trees, changed, global.id, prototypeTags = facts)
            .shouldBeInstanceOf<CCompilerNavigationReadout.Refused>()
    }
})

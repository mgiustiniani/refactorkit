package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Complete original compiler/server observations, without synthesized positive ASTs. */
class CFunctionParameterTagNavigationFactsSpec : StringSpec({
    fun fixture(profile: String) = requireNotNull(CFunctionParameterTagNavigationFactsSpec::class.java
        .getResourceAsStream("/function-parameter-tag-navigation/$profile.json.gz")).use { input ->
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    fun mode(data: JsonObject, standard: String) = Json.parseToJsonElement(data.getValue("observationBytes").jsonPrimitive.content)
        .jsonObject.getValue("results").jsonArray.map { it.jsonObject }.single { it.getValue("standard").jsonPrimitive.content == standard }
    fun sources(mode: JsonObject) = mode.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    fun asts(data: JsonObject, standard: String) = data.getValue("asts").jsonObject.getValue(standard).jsonObject
        .map { (p, v) -> Path.of(p) to Json.parseToJsonElement(v.jsonPrimitive.content).jsonObject }.toMap()
    fun root(mode: JsonObject) = Path.of(mode.getValue("command").jsonArray.last().jsonPrimitive.content.substringAfter("="))
    val api = Path.of("src/api.c")
    fun responses(mode: JsonObject): Map<CPrototypeTagCursor, String> = mode.getValue("observations").jsonArray.map { it.jsonObject }
        .filter { it.getValue("label").jsonPrimitive.content !in setOf("global", "peer-global") }.associate { row ->
            val position = row.getValue("position").jsonObject
            val line = position.getValue("line").jsonPrimitive.int; val ch = position.getValue("character").jsonPrimitive.int
            CPrototypeTagCursor(api, api, SourceRange(SourcePosition(line, ch), SourcePosition(line, ch + 6))) to
                row.getValue("symbolInfo").jsonObject.getValue("result").toString()
        }
    for (standard in listOf("c11", "c17")) {
        for ((profile, count) in listOf("simple" to 4, "type-uses" to 6)) {
            "$standard $profile function parameter tag retains every native type use and independent globals" {
                val data = fixture(profile); val observation = mode(data, standard)
                val trees = asts(data, standard); val inputs = sources(observation); val native = responses(observation)
                val queries = CPrototypeTagFactsDecoder.queries(trees, inputs)
                    .shouldBeInstanceOf<CPrototypeTagQueriesReadout.Found>().queries
                queries.size shouldBe count
                queries.map { it.cursor }.toSet() shouldBe native.keys
                val functionQueries = queries.filter { it.functionName == "inspect" }
                functionQueries.size shouldBe count - 1
                functionQueries.map { it.scopeId }.distinct().size shouldBe 1
                functionQueries.single { it.cursor.range.start.character == 19 }.scopeId.contains("#function:") shouldBe true
                val facts = CPrototypeTagFactsDecoder.decode(trees, inputs, root(observation), native)
                    .shouldBeInstanceOf<CPrototypeTagReadout.Found>().facts
                facts.matchesNativeInputs(trees, inputs) shouldBe true
                facts.declarations.size shouldBe 2
                val tags = CCompilerDeclarationIndex.analyze(trees, inputs).shouldBeInstanceOf<CCompilerDeclarationReadout.Found>()
                    .index.withPrototypeTags(facts).search("opaque").items.filter { it.kind == CCompilerDeclarationKind.STRUCT }
                tags.size shouldBe 4
                tags.map { it.id }.toSet().size shouldBe 4
                val selected = facts.declarations.single { it.range.start.line == 1 }
                val family = CCompilerNavigationFactsDecoder.decode(trees, inputs, selected.id, prototypeTags = facts)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                family.definitions shouldBe emptyList()
                family.declarations.map { CPrototypeTagCursor(api, it.file, it.range) }.toSet() shouldBe
                    setOf(functionQueries.single { it.cursor.range.start.character == 19 }.cursor)
                family.references.map { CPrototypeTagCursor(api, it.file, it.range) }.toSet() shouldBe
                    functionQueries.map { it.cursor }.filter { it.range.start.character != 19 }.toSet()
                family.references.size shouldBe count - 2
                family.references.forEach { it.translationUnits shouldBe setOf(api) }
                val other = facts.declarations.single { it.range.start.line == 2 }
                facts.family(other.id)!!.references shouldBe emptyList()
                for (global in tags.filter { it.id !in facts.declarations.map { d -> d.id } }) {
                    val globalFamily = CCompilerNavigationFactsDecoder.decode(trees, inputs, global.id, prototypeTags = facts)
                        .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                    globalFamily.definitions.single().file shouldBe global.file
                    globalFamily.references.size shouldBe if (global.file == api) 1 else 0
                }
            }
        }
        "$standard a missing body type-use witness cannot produce a partial family" {
            val data = fixture("type-uses"); val observation = mode(data, standard)
            val native = responses(observation).filterKeys { it.range.start.character != 66 }
            CPrototypeTagFactsDecoder.decode(asts(data, standard), sources(observation), root(observation), native)
                .shouldBeInstanceOf<CPrototypeTagReadout.Refused>().reason shouldBe CPrototypeTagRefusal.NATIVE_EVIDENCE_UNAVAILABLE
        }
        "$standard an unqualified compound type spelling refuses instead of omitting a native reference" {
            val data = fixture("compound"); val observation = mode(data, standard)
            CPrototypeTagFactsDecoder.queries(asts(data, standard), sources(observation))
                .shouldBeInstanceOf<CPrototypeTagQueriesReadout.Refused>().reason shouldBe CPrototypeTagRefusal.UNSUPPORTED_SOURCE
        }
    }
})

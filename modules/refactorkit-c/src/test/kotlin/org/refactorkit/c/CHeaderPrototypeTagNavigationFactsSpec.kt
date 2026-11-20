package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Actual header identities are constrained by each original compiler TU, never by USR alone. */
class CHeaderPrototypeTagNavigationFactsSpec : StringSpec({
    fun fixture(name: String) = requireNotNull(CHeaderPrototypeTagNavigationFactsSpec::class.java
        .getResourceAsStream("/header-prototype-navigation/$name.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    val flat = fixture("native-fixture"); val disagreement = fixture("context-disagreement")
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c"); val header = Path.of("include/api.h")
    fun observation(data: JsonObject, standard: String) = Json.parseToJsonElement(data.getValue("observationBytes").jsonPrimitive.content)
        .jsonObject.getValue("results").jsonArray.map { it.jsonObject }.single { it.getValue("standard").jsonPrimitive.content == standard }
    fun sources(mode: JsonObject) = mode.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    fun asts(data: JsonObject, standard: String) = data.getValue("asts").jsonObject.getValue(standard).jsonObject
        .map { (p, v) -> Path.of(p) to Json.parseToJsonElement(v.jsonPrimitive.content).jsonObject }.toMap()
    fun root(mode: JsonObject) = Path.of(mode.getValue("command").jsonArray.last().jsonPrimitive.content.substringAfter("="))
    fun responses(mode: JsonObject): Map<CPrototypeTagCursor, String> {
        val native = mode.getValue("observations").jsonArray.map { it.jsonObject }
            .filter { it.getValue("label").jsonPrimitive.content in setOf("prototype-first", "prototype-second", "other-prototype") }
        return listOf(api, peer).flatMap { tu -> native.map { row ->
            val point = row.getValue("position").jsonObject
            val line = point.getValue("line").jsonPrimitive.int; val ch = point.getValue("character").jsonPrimitive.int
            CPrototypeTagCursor(tu, header, SourceRange(SourcePosition(line, ch), SourcePosition(line, ch + 6))) to
                row.getValue("symbolInfo").jsonObject.getValue("result").toString()
        } }.toMap()
    }
    for (standard in listOf("c11", "c17")) {
        "$standard compiler-owned header parameter cursors retain both original TUs" {
            val mode = observation(flat, standard)
            val queries = CPrototypeTagFactsDecoder.queries(asts(flat, standard), sources(mode))
                .shouldBeInstanceOf<CPrototypeTagQueriesReadout.Found>().queries
            queries.size shouldBe 6
            queries.map { it.cursor }.toSet() shouldBe responses(mode).keys
            queries.map { it.cursor.file }.toSet() shouldBe setOf(header)
            queries.groupingBy { it.cursor.translationUnit }.eachCount() shouldBe mapOf(api to 3, peer to 3)
        }
        "$standard native header facts preserve prototype families and exclude them from private globals" {
            val mode = observation(flat, standard); val trees = asts(flat, standard); val inputs = sources(mode)
            val facts = CPrototypeTagFactsDecoder.decode(trees, inputs, root(mode), responses(mode))
                .shouldBeInstanceOf<CPrototypeTagReadout.Found>().facts
            facts.matchesNativeInputs(trees, inputs) shouldBe true
            facts.declarations.size shouldBe 4
            facts.declarations.map { it.file }.toSet() shouldBe setOf(header)
            facts.declarations.map { it.id }.toSet().size shouldBe 4
            val index = CCompilerDeclarationIndex.analyze(trees, inputs).shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
                .withPrototypeTags(facts)
            val tags = index.search("opaque").items.filter { it.kind == CCompilerDeclarationKind.STRUCT }
            tags.size shouldBe 6
            for (declaration in facts.declarations) {
                val family = CCompilerNavigationFactsDecoder.decode(trees, inputs, declaration.id, prototypeTags = facts)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                family.definitions shouldBe emptyList()
                family.declarations.size shouldBe 1
                family.references.size shouldBe if (declaration.range.start.line == 1) 1 else 0
                family.declarations.single().file shouldBe header
                family.declarations.single().translationUnits shouldBe setOf(declaration.translationUnit)
                family.references.forEach { it.file shouldBe header; it.translationUnits shouldBe setOf(declaration.translationUnit) }
            }
            val globals = tags.filter { it.file != header }
            globals.map { it.file }.toSet() shouldBe setOf(api, peer)
            for (declaration in globals) {
                val family = CCompilerNavigationFactsDecoder.decode(trees, inputs, declaration.id, prototypeTags = facts)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                family.definitions.size shouldBe 1
                family.definitions.single().file shouldBe declaration.file
                family.references.size shouldBe if (declaration.file == api) 1 else 0
            }
        }
        "$standard standalone header identity cannot override an earlier tag in another includer" {
            val mode = observation(disagreement, standard)
            CPrototypeTagFactsDecoder.decode(asts(disagreement, standard), sources(mode), root(mode), responses(mode))
                .shouldBeInstanceOf<CPrototypeTagReadout.Refused>().reason shouldBe CPrototypeTagRefusal.BINDING_MISMATCH
        }
    }
})

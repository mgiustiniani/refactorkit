package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: all accepted boundary facts originate in actual compiler/server captures. */
class CPrototypeComposedLimitSpec : StringSpec({
    for (standard in listOf("c11", "c17")) for (profile in listOf("at-cap", "over-cap")) {
        "$standard $profile native prototype proof respects the complete declaration limit" {
            val data = requireNotNull(CPrototypeComposedLimitSpec::class.java
                .getResourceAsStream("/prototype-composed-limit/$standard-$profile.json.gz")).use { stream ->
                GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
            }
            val sources = data.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, v) ->
                Path.of(p) to Json.parseToJsonElement(v.jsonPrimitive.content).jsonObject
            }.toMap()
            val index = CCompilerDeclarationIndex.analyze(asts, sources)
                .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
            index.declarations.size shouldBe data.getValue("expectedRawDeclarations").jsonPrimitive.int
            val root = Path.of(data.getValue("command").jsonArray.last().jsonPrimitive.content.substringAfter("="))
            val api = Path.of("src/api.c")
            val responses = data.getValue("observations").jsonArray.map { it.jsonObject }.associate { row ->
                val position = row.getValue("position").jsonObject
                val line = position.getValue("line").jsonPrimitive.int
                val ch = position.getValue("character").jsonPrimitive.int
                CPrototypeTagCursor(api, api, SourceRange(SourcePosition(line, ch), SourcePosition(line, ch + 6))) to
                    row.getValue("symbolInfo").jsonObject.getValue("result").toString()
            }
            responses.size shouldBe 3
            val result = CPrototypeTagFactsDecoder.decode(asts, sources, root, responses)
            if (profile == "over-cap") {
                index.declarations.size shouldBe 16383
                result.shouldBeInstanceOf<CPrototypeTagReadout.Refused>().reason shouldBe CPrototypeTagRefusal.INPUT_LIMIT
            } else {
                val facts = result.shouldBeInstanceOf<CPrototypeTagReadout.Found>().facts
                facts.declarations.size shouldBe 2
                index.withPrototypeTags(facts).declarations.size shouldBe 16384
                facts.declarations.forEach { declaration ->
                    val family = requireNotNull(facts.family(declaration.id))
                    family.definitions shouldBe emptyList()
                    family.declarations.size shouldBe 1
                    family.selected.translationUnit shouldBe api
                }
            }
        }
    }
})

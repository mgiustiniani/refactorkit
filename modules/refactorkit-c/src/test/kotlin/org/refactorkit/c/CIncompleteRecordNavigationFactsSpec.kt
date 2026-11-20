package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: references remain known when a native record has no complete definition. */
class CIncompleteRecordNavigationFactsSpec : StringSpec({
    val material = requireNotNull(CIncompleteRecordNavigationFactsSpec::class.java
        .getResourceAsStream("/record-navigation/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c"); val header = Path.of("include/api.h")
    for ((mode, rawCases) in material) {
        for ((variant, raw) in rawCases.jsonObject.filterKeys { it.contains("incomplete") }) {
            "$mode $variant has exact references and a known unavailable complete definition" {
                val data = raw.jsonObject
                val sources = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
                val asts = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
                val shared = data.getValue("profile").jsonPrimitive.content == "header"
                val file = if (shared) header else api
                val name = data.getValue("name").jsonPrimitive.content
                val keyword = data.getValue("keyword").jsonPrimitive.content
                data.getValue("hasCompleteDefinition").jsonPrimitive.boolean shouldBe false
                val declarationOffset = sources.getValue(file).indexOf("$keyword $name;") + keyword.length + 1
                val declarationRange = TextEdits.rangeForOffset(sources.getValue(file), declarationOffset, name.length)
                val index = CCompilerDeclarationIndex.analyze(asts, sources)
                    .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
                val selected = index.declarations.single { it.file == file && it.translationUnit == api &&
                    it.range == declarationRange && it.name == name && it.lookupSpace == CCompilerLookupSpace.TAG }
                val found = CCompilerNavigationFactsDecoder.decode(asts, sources, selected.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                found.selected.id shouldBe selected.id
                found.definitions shouldBe emptyList()
                found.declarations.single().let { site ->
                    site.file shouldBe file; site.range shouldBe declarationRange
                    site.translationUnits shouldBe if (shared) setOf(api, peer) else setOf(api)
                }
                val expected = sources.flatMap { (path, source) ->
                    if (path == peer && !shared) emptyList() else source.lines().mapIndexedNotNull { line, text ->
                        val at = text.indexOf("$keyword $name")
                        if (at < 0 || text.trim() == "$keyword $name;" || text.contains("$keyword $name {")) null
                        else Triple(path, line, at + keyword.length + 1)
                    }
                }.toSet()
                expected.size shouldBe if (shared) 3 else 2
                found.references.map { Triple(it.file, it.range.start.line, it.range.start.character) }.toSet() shouldBe expected
                found.references.forEach { site ->
                    site.translationUnits shouldBe setOf(site.file)
                    site.range.end.line shouldBe site.range.start.line
                    site.range.end.character shouldBe site.range.start.character + name.length
                    sources.getValue(site.file).lines()[site.range.start.line].substring(site.range.start.character, site.range.end.character) shouldBe name
                }
                val missing = CCompilerNavigationFactsDecoder.decode(asts.filterKeys { it != peer }, sources,
                    selected.id).shouldBeInstanceOf<CCompilerNavigationReadout.Refused>()
                missing.reason shouldBe CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE
            }
        }
    }
})

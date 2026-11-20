package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: read declarations/references independently of record mutation authority. */
class CRecordNavigationFactsSpec : StringSpec({
    val material = requireNotNull(CRecordNavigationFactsSpec::class.java
        .getResourceAsStream("/record-navigation/native-fixture.json.gz")).use { input ->
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c"); val header = Path.of("include/api.h")
    fun input(mode: String, variant: String) = material.getValue(mode).jsonObject.getValue(variant).jsonObject
    fun text(mode: String, variant: String) = input(mode, variant).getValue("sources").jsonObject
        .map { (path, source) -> Path.of(path) to source.jsonPrimitive.content }.toMap()
    fun trees(mode: String, variant: String) = input(mode, variant).getValue("asts").jsonObject
        .map { (path, ast) -> Path.of(path) to ast.jsonObject }.toMap()
    fun offset(source: String, keyword: String, name: String, complete: Boolean = false) =
        source.indexOf("$keyword $name" + if (complete) " {" else "").also { require(it >= 0) } + keyword.length + 1
    fun physical(sites: List<CRecordNavigationSite>) = sites.map { it.file to it.span }.toSet()
    for (mode in listOf("c11", "c17")) {
        for ((variant, raw) in material.getValue(mode).jsonObject) {
            "$mode $variant retains canonical declaration family exact uses and definition availability" {
                val data = raw.jsonObject
                val sources = text(mode, variant); val asts = trees(mode, variant)
                val name = data.getValue("name").jsonPrimitive.content
                val keyword = data.getValue("keyword").jsonPrimitive.content
                val complete = data.getValue("hasCompleteDefinition").jsonPrimitive.boolean
                val shared = data.getValue("profile").jsonPrimitive.content == "header"
                val file = if (shared) header else api
                val start = offset(sources.getValue(file), keyword, name)
                val found = CRecordNavigationFactsDecoder.decode(asts, sources, file, start, api)
                    .shouldBeInstanceOf<CRecordNavigationReadout.Found>().facts
                val expectedDeclarations = mutableSetOf(file to CFunctionByteSpan(start, start + name.length))
                if (complete) {
                    val definition = offset(sources.getValue(api), keyword, name, true)
                    val expected = api to CFunctionByteSpan(definition, definition + name.length)
                    physical(found.definitions) shouldBe setOf(expected)
                    expectedDeclarations += expected
                    val fromDefinition = CRecordNavigationFactsDecoder.decode(asts, sources, api, definition, api)
                        .shouldBeInstanceOf<CRecordNavigationReadout.Found>().facts
                    physical(fromDefinition.definitions) shouldBe physical(found.definitions)
                    physical(fromDefinition.declarations) shouldBe physical(found.declarations)
                    physical(fromDefinition.references) shouldBe physical(found.references)
                } else found.definitions shouldBe emptyList()
                physical(found.declarations) shouldBe expectedDeclarations
                val expectedUses = sources.flatMap { (path, source) ->
                    if (path == peer && !shared) emptyList() else source.lines().flatMapIndexed { lineIndex, line ->
                        val marker = "$keyword $name"
                        val at = line.indexOf(marker)
                        if (at < 0) emptyList() else {
                            val spanStart = source.lines().take(lineIndex).sumOf { it.length + 1 } + at + keyword.length + 1
                            val point = path to CFunctionByteSpan(spanStart, spanStart + name.length)
                            if (point in expectedDeclarations) emptyList() else listOf(point)
                        }
                    }
                }.toSet()
                physical(found.references) shouldBe expectedUses
                expectedUses.size shouldBe if (shared) 3 else 2
                (found.definitions + found.declarations + found.references).forEach { site ->
                    sources.getValue(site.file).substring(site.span.start, site.span.endExclusive) shouldBe name
                }
                found.references.map { it.translationUnit }.toSet() shouldBe if (shared) setOf(api, peer) else setOf(api)
                val mutation = if (shared) CTagRenameFactsDecoder.decode(asts, sources, file, start, asts.keys)
                    else CTagRenameFactsDecoder.decodeSource(asts, sources, file, start, asts.keys)
                mutation.shouldBeInstanceOf<CTagReadout.Refused>()
            }
        }
        "$mode record read closure refuses a missing captured translation unit" {
            val variant = "source-forward-struct"; val sources = text(mode, variant)
            CRecordNavigationFactsDecoder.decode(trees(mode, variant).filterKeys { it != peer }, sources, api,
                offset(sources.getValue(api), "struct", "meter"), api) shouldBe
                CRecordNavigationReadout.Refused(CRecordNavigationRefusal.INCOMPLETE_TUS)
        }
    }
})

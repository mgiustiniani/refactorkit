package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: read-only alias families preserve scope and native identity. */
class CScopedTypedefNavigationFactsSpec : StringSpec({
    val modes = requireNotNull(CScopedTypedefNavigationFactsSpec::class.java
        .getResourceAsStream("/scoped-typedef-navigation/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c")
    for ((mode, profiles) in modes) for ((profile, raw) in profiles.jsonObject) {
        "$mode $profile typedef reads preserve native aliases and exclude ordinary or peer homonyms" {
            val data = raw.jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, v) -> Path.of(p) to v.jsonObject }.toMap()
            val index = CCompilerDeclarationIndex.analyze(asts, sources)
                .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
            fun position(file: Path, marker: String) = TextEdits.positionForOffset(sources.getValue(file),
                sources.getValue(file).indexOf(marker).also { require(it >= 0) { marker } } + marker.indexOf("count_t"))
            fun declaration(file: Path, marker: String, occurrence: Int = 0): CCompilerDeclaration {
                val source = sources.getValue(file)
                var at = source.indexOf(marker).also { require(it >= 0) }
                repeat(occurrence) { at = source.indexOf(marker, at + marker.length).also { require(it >= 0) } }
                val point = TextEdits.positionForOffset(source, at + marker.indexOf("count_t"))
                return index.declarations.single { it.translationUnit == file && it.file == file &&
                    it.kind == CCompilerDeclarationKind.TYPEDEF && it.name == "count_t" && it.range.start == point }
            }
            fun read(selected: CCompilerDeclaration, canonical: CCompilerDeclaration,
                declared: List<CCompilerDeclaration>, markers: List<String>) {
                val facts = CCompilerNavigationFactsDecoder.decode(asts, sources, selected.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                facts.selected.id shouldBe selected.id
                facts.definitions.size shouldBe 1
                facts.definitions.single().file shouldBe canonical.file
                facts.definitions.single().range shouldBe canonical.range
                facts.definitions.single().translationUnits shouldBe setOf(canonical.translationUnit)
                facts.declarations.size shouldBe declared.size
                facts.declarations.map { it.file to it.range }.toSet() shouldBe declared.map { it.file to it.range }.toSet()
                facts.references.size shouldBe markers.size
                facts.references.map { it.file to it.range.start }.toSet() shouldBe
                    markers.map { selected.file to position(selected.file, it) }.toSet()
                facts.references.forEach { site ->
                    site.translationUnits shouldBe setOf(selected.translationUnit)
                    site.range.end.line shouldBe site.range.start.line
                    site.range.end.character - site.range.start.character shouldBe "count_t".length
                    CRecordCursorBinding.resolve(asts, sources, index, site.file, site.range.start)
                        .shouldBeInstanceOf<CRecordCursorReadout.Found>().declaration.id shouldBe canonical.id
                }
            }
            val ordinary = profile == "ordinary-shadow"
            val marker = when (profile) {
                "pointer-shadow" -> "    typedef int *count_t;"
                "chain-shadow" -> "    typedef base_t count_t;"
                "ordinary-shadow" -> "typedef int count_t;"
                "nested" -> "        typedef int count_t;"
                else -> "    typedef int count_t;"
            }
            val local = declaration(api, marker)
            val firstPeer = declaration(peer, if (profile in setOf("block", "shadow", "nested")) "typedef double count_t;" else "typedef long count_t;")
            (local.id == firstPeer.id) shouldBe false
            local.lookupSpace shouldBe CCompilerLookupSpace.ORDINARY
            val uses = when (profile) {
                "pointer-shadow" -> listOf("    count_t value = &number")
                "ordinary-shadow" -> listOf("count_t value = 2", "count_t value = 4")
                "nested" -> listOf("        count_t value = 4")
                else -> listOf("    count_t value = 2")
            }
            val family = if (profile == "repeated-alias") listOf(local, declaration(api, marker, 1)) else listOf(local)
            for (selected in family) read(selected, local, family, uses)
            read(firstPeer, firstPeer, listOf(firstPeer), listOf(if (profile in setOf("block", "shadow", "nested"))
                "count_t value = 1" else "count_t value = 7"))
            if (profile !in setOf("block", "ordinary-shadow")) {
                val outer = declaration(api, if (profile in setOf("shadow", "nested")) "typedef long count_t;" else "typedef double count_t;")
                (outer.id == local.id) shouldBe false
                read(outer, outer, listOf(outer), when (profile) {
                    "shadow" -> listOf("    count_t value = 4")
                    "nested" -> listOf("    count_t outside_count = 2", "    count_t after_count = 5")
                    else -> listOf("count_t value = 3")
                })
            }
            if (ordinary) {
                val point = position(api, "int count_t = 3")
                CRecordCursorBinding.resolve(asts, sources, index, api, point) shouldBe CRecordCursorReadout.NotRecordCursor
                val value = index.declarations.single { it.kind == CCompilerDeclarationKind.VARIABLE && it.file == api &&
                    it.translationUnit == api && it.name == "count_t" && it.range.start == point }
                val facts = CCompilerNavigationFactsDecoder.decode(asts, sources, value.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                facts.references.size shouldBe 1
            } else CTypedefRenameFactsDecoder.decodeSource(asts, sources, api,
                TextEdits.offsetOf(sources.getValue(api), local.range.start), asts.keys)
                .shouldBeInstanceOf<CTypedefReadout.Refused>()
            CCompilerNavigationFactsDecoder.decode(asts.filterKeys { it != peer }, sources, local.id)
                .shouldBeInstanceOf<CCompilerNavigationReadout.Refused>().reason shouldBe CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE
        }
    }
})

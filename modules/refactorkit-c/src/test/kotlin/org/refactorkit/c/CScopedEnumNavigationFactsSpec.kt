package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-QUERY-001: pure native enum reads preserve block and TU binding. */
class CScopedEnumNavigationFactsSpec : StringSpec({
    val modes = requireNotNull(CScopedEnumNavigationFactsSpec::class.java
        .getResourceAsStream("/scoped-type-navigation/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c")
    for ((mode, profiles) in modes) for ((profile, raw) in profiles.jsonObject) {
        "$mode $profile enum reads and cursors exclude outer and peer tags without granting mutation authority" {
            val data = raw.jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, v) -> Path.of(p) to v.jsonObject }.toMap()
            val index = CCompilerDeclarationIndex.analyze(asts, sources)
                .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
            fun position(file: Path, marker: String) = TextEdits.positionForOffset(sources.getValue(file),
                sources.getValue(file).indexOf(marker).also { require(it >= 0) } + marker.indexOf("stage"))
            fun declaration(file: Path, marker: String): CCompilerDeclaration {
                val point = position(file, marker)
                return index.declarations.single { it.translationUnit == file && it.file == file &&
                    it.kind == CCompilerDeclarationKind.ENUM && it.name == "stage" && it.range.start == point }
            }
            val indent = if (profile == "nested") "        " else "    "
            val local = declaration(api, "${indent}enum stage {")
            val other = declaration(peer, "enum stage {")
            (local.id == other.id) shouldBe false
            local.lookupSpace shouldBe CCompilerLookupSpace.TAG
            fun read(selected: CCompilerDeclaration, markers: List<String>) {
                val facts = CCompilerNavigationFactsDecoder.decode(asts, sources, selected.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                facts.selected.id shouldBe selected.id
                facts.definitions.size shouldBe 1
                facts.definitions.single().file shouldBe selected.file
                facts.definitions.single().range shouldBe selected.range
                facts.definitions.single().translationUnits shouldBe setOf(selected.translationUnit)
                facts.declarations shouldBe facts.definitions
                facts.references.size shouldBe markers.size
                facts.references.map { it.file to it.range.start }.toSet() shouldBe
                    markers.map { selected.file to position(selected.file, it) }.toSet()
                facts.references.forEach { site ->
                    site.translationUnits shouldBe setOf(selected.translationUnit)
                    site.range.end.line shouldBe site.range.start.line
                    site.range.end.character - site.range.start.character shouldBe "stage".length
                    CRecordCursorBinding.resolve(asts, sources, index, site.file, site.range.start)
                        .shouldBeInstanceOf<CRecordCursorReadout.Found>().declaration.id shouldBe selected.id
                }
            }
            read(local, listOf("${indent}enum stage state = READY"))
            read(other, listOf("enum stage state = PEER"))
            if (profile != "block") {
                val outer = declaration(api, "enum stage { OUTER")
                (outer.id == local.id) shouldBe false
                read(outer, if (profile == "shadow") listOf("    enum stage state = OUTER")
                    else listOf("    enum stage outside = OUTER", "    enum stage after = OUTER"))
            }
            CCompilerNavigationFactsDecoder.decode(asts.filterKeys { it != peer }, sources, local.id)
                .shouldBeInstanceOf<CCompilerNavigationReadout.Refused>().reason shouldBe CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE
            CTagRenameFactsDecoder.decodeSource(asts, sources, api,
                TextEdits.offsetOf(sources.getValue(api), local.range.start), asts.keys)
                .shouldBeInstanceOf<CTagReadout.Refused>()
        }
    }
})

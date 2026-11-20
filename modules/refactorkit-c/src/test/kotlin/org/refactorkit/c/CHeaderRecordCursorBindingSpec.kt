package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-LSP-QUERY-001: direct headers retain actual native inclusion contexts. */
class CHeaderRecordCursorBindingSpec : StringSpec({
    val modes = requireNotNull(CHeaderRecordCursorBindingSpec::class.java.getResourceAsStream("/record-navigation/native-fixture.json.gz"))
        .use { stream -> GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        } }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c"); val header = Path.of("include/api.h")
    for ((mode, variants) in modes) for ((variant, raw) in variants.jsonObject.filterKeys { it.startsWith("header-") }) {
        "$mode $variant direct header cursor retains native family and context closure" {
            val data = raw.jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
            val name = data.getValue("name").jsonPrimitive.content
            val keyword = data.getValue("keyword").jsonPrimitive.content
            val text = sources.getValue(header)
            val start = text.indexOf("$keyword $name;").also { require(it >= 0) } + keyword.length + 1
            val range = TextEdits.rangeForOffset(text, start, name.length)
            val index = CCompilerDeclarationIndex.analyze(asts, sources)
                .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
            val declaration = CRecordCursorBinding.resolve(asts, sources, index, header, range.start)
                .shouldBeInstanceOf<CRecordCursorReadout.Found>().declaration
            declaration.file shouldBe header; declaration.translationUnit shouldBe api
            declaration.range shouldBe range; declaration.lookupSpace shouldBe CCompilerLookupSpace.TAG
            val family = CCompilerNavigationFactsDecoder.decode(asts, sources, declaration.id)
                .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
            family.definitions.size shouldBe if (data.getValue("hasCompleteDefinition").jsonPrimitive.boolean) 1 else 0
            family.references.size shouldBe 3
            family.references.map { it.file }.toSet() shouldBe setOf(api, peer)
            family.declarations.single { it.file == header }.translationUnits shouldBe setOf(api, peer)
            CRecordCursorBinding.resolve(asts.filterKeys { it != peer }, sources, index, header, range.start)
                .shouldBeInstanceOf<CRecordCursorReadout.Refused>()
            val ordinary = text.indexOf("once").also { require(it >= 0) }
            CRecordCursorBinding.resolve(asts, sources, index, header, TextEdits.rangeForOffset(text, ordinary, 4).start) shouldBe
                CRecordCursorReadout.NotRecordCursor
        }
    }
})

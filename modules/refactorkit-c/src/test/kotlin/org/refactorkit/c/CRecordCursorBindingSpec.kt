package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C04-LSP-QUERY-001: native cursor identity precedes unrelated-TU navigation targets. */
class CRecordCursorBindingSpec : StringSpec({
    val modes = requireNotNull(CRecordCursorBindingSpec::class.java.getResourceAsStream("/record-navigation/native-fixture.json.gz"))
        .use { stream -> GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use {
            Json.parseToJsonElement(it.readText()).jsonObject
        } }.getValue("modes").jsonObject
    val api = Path.of("src/api.c"); val peer = Path.of("src/main.c"); val header = Path.of("include/api.h")
    for ((mode, variants) in modes) for ((variant, raw) in variants.jsonObject) {
        "$mode $variant cursor retains its original native TU record family" {
            val data = raw.jsonObject
            val sources = data.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
            val asts = data.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
            val name = data.getValue("name").jsonPrimitive.content
            val keyword = data.getValue("keyword").jsonPrimitive.content
            val shared = data.getValue("profile").jsonPrimitive.content == "header"
            val text = sources.getValue(api)
            val useLine = text.lines().indexOfFirst { it.contains("$keyword $name") &&
                !it.contains("$keyword $name {") && it.trim() != "$keyword $name;" }.also { require(it >= 0) }
            val offset = text.lines().take(useLine).sumOf { it.length + 1 } + text.lines()[useLine].indexOf("$keyword $name") + keyword.length + 1
            val position = TextEdits.rangeForOffset(text, offset, name.length).start
            val index = CCompilerDeclarationIndex.analyze(asts, sources)
                .shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
            val declaration = CRecordCursorBinding.resolve(asts, sources, index, api, position)
                .shouldBeInstanceOf<CRecordCursorReadout.Found>().declaration
            declaration.name shouldBe name
            declaration.translationUnit shouldBe api
            val origin = if (shared) header else api
            declaration.file shouldBe origin
            val originText = sources.getValue(origin)
            val start = originText.indexOf("$keyword $name;").also { require(it >= 0) } + keyword.length + 1
            declaration.range shouldBe TextEdits.rangeForOffset(originText, start, name.length)
            declaration.lookupSpace shouldBe CCompilerLookupSpace.TAG
            val family = CCompilerNavigationFactsDecoder.decode(asts, sources, declaration.id)
                .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
            family.definitions.size shouldBe if (data.getValue("hasCompleteDefinition").jsonPrimitive.boolean) 1 else 0
            family.references.size shouldBe if (shared) 3 else 2
            if (!shared) {
                family.references.map { it.file }.toSet() shouldBe setOf(api)
                index.declarations.single { it.file == peer && it.name == name && it.lookupSpace == CCompilerLookupSpace.TAG }.id
                    .let { unrelated -> (declaration.id == unrelated) shouldBe false }
            }
            CRecordCursorBinding.resolve(asts.filterKeys { it != peer }, sources, index, api, position)
                .shouldBeInstanceOf<CRecordCursorReadout.Refused>()
            val ordinary = if (data.getValue("hasCompleteDefinition").jsonPrimitive.boolean) "before" else "read_value"
            val ordinaryAt = text.indexOf(ordinary).also { require(it >= 0) }
            CRecordCursorBinding.resolve(asts, sources, index, api, TextEdits.rangeForOffset(text, ordinaryAt, ordinary.length).start) shouldBe
                CRecordCursorReadout.NotRecordCursor
        }
    }
})

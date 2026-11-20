package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C-SEMANTIC-RENAME-001: source-owned tag transitions retain native identity and TU isolation. */
class CSourceTagRenameFactsSpec : StringSpec({
    val fixture = requireNotNull(CSourceTagRenameFactsSpec::class.java
        .getResourceAsStream("/source-tag-rename/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getValue("modes").jsonObject
    val selectedFile = Path.of("src/api.c")
    val unrelatedFile = Path.of("src/main.c")
    val header = Path.of("include/api.h")
    val units = setOf(selectedFile, unrelatedFile)
    val names = listOf(Triple("struct", "meter", "sensor"), Triple("union", "payload", "message"),
        Triple("enum", "stage", "phase"))
    fun phase(mode: String, name: String) = fixture.getValue(mode).jsonObject.getValue(name).jsonObject
    fun sources(phase: JsonObject) = phase.getValue("sources").jsonObject.map { (path, text) ->
        Path.of(path) to text.jsonPrimitive.content
    }.toMap()
    fun decode(mode: String, phaseName: String, keyword: String, name: String): CTagReadout {
        val input = phase(mode, phaseName)
        val text = sources(input)
        val asts = input.getValue("asts").jsonObject.map { (path, ast) -> Path.of(path) to ast.jsonObject }.toMap()
        val offset = text.getValue(selectedFile).indexOf("$keyword $name") + keyword.length + 1
        return CTagRenameFactsDecoder.decodeSource(asts, text, selectedFile, offset, units)
    }
    for (mode in listOf("c11", "c17")) {
        for ((keyword, oldName, newName) in names) {
            "$mode $keyword native rename preserves shape exact source sites and the unrelated TU homonym" {
                val before = decode(mode, "before", keyword, oldName).shouldBeInstanceOf<CTagReadout.Found>().facts
                val after = decode(mode, keyword, keyword, newName).shouldBeInstanceOf<CTagReadout.Found>().facts
                CTagRenameBinding.check(before, after, newName) shouldBe CTagTransition.Verified
                before.name shouldBe oldName
                after.name shouldBe newName
                before.translationUnits shouldBe units
                before.sites.size shouldBe 4
                before.sites.count { it.kind == CTagSiteKind.DECLARATION } shouldBe 1
                before.sites.count { it.kind == CTagSiteKind.TYPE_USE } shouldBe 3
                before.sites.map { it.file }.toSet() shouldBe setOf(selectedFile)
                before.observations.map { it.translationUnit }.toSet() shouldBe setOf(selectedFile)
                val oldText = sources(phase(mode, "before"))
                val newText = sources(phase(mode, keyword))
                before.sites.forEach { oldText.getValue(it.file).substring(it.span.start, it.span.endExclusive) shouldBe oldName }
                after.sites.forEach { newText.getValue(it.file).substring(it.span.start, it.span.endExclusive) shouldBe newName }
                newText.getValue(unrelatedFile) shouldBe oldText.getValue(unrelatedFile)
                newText.getValue(header) shouldBe oldText.getValue(header)
                before.sourceHashes.filter { (path, value) -> after.sourceHashes[path] != value }.keys shouldBe setOf(selectedFile)
            }
        }
        "$mode source block shadow refuses the single tag family" {
            decode(mode, "shadow", "struct", "meter") shouldBe
                CTagReadout.Refused(CTagFactsRefusal.AMBIGUOUS_TAG_FAMILY)
        }
        "$mode source macro cannot acquire tag mutation authority" {
            decode(mode, "macro", "struct", "meter") shouldBe
                CTagReadout.Refused(CTagFactsRefusal.UNSUPPORTED_SOURCE)
        }
        "$mode a real compiler post model with changed record layout refuses a rename transition" {
            val before = decode(mode, "before", "struct", "meter").shouldBeInstanceOf<CTagReadout.Found>().facts
            val after = decode(mode, "struct-layout-changed", "struct", "sensor")
                .shouldBeInstanceOf<CTagReadout.Found>().facts
            CTagRenameBinding.check(before, after, "sensor") shouldBe
                CTagTransition.Refused(CTagTransitionRefusal.DECLARATION_IDENTITY)
        }
    }
})

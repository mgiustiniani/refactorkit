package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C-SEMANTIC-RENAME-001: source alias transitions preserve native type and TU ownership. */
class CSourceTypedefRenameFactsSpec : StringSpec({
    val material = requireNotNull(CSourceTypedefRenameFactsSpec::class.java
        .getResourceAsStream("/source-typedef-rename/native-fixture.json.gz")).use { stream ->
        GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    }.getValue("modes").jsonObject
    val selected = Path.of("src/api.c")
    val peer = Path.of("src/main.c")
    val units = setOf(selected, peer)
    fun input(mode: String, form: String, phase: String) = material.getValue(mode).jsonObject
        .getValue(form).jsonObject.getValue(phase).jsonObject
    fun sources(mode: String, form: String, phase: String) = input(mode, form, phase)
        .getValue("sources").jsonObject.map { (path, text) -> Path.of(path) to text.jsonPrimitive.content }.toMap()
    fun trees(mode: String, form: String, phase: String) = input(mode, form, phase)
        .getValue("asts").jsonObject.map { (path, tree) -> Path.of(path) to tree.jsonObject }.toMap()
    fun decode(mode: String, form: String, phase: String, asts: Map<Path, JsonObject> = trees(mode, form, phase)): CTypedefReadout {
        val text = sources(mode, form, phase)
        val name = if (phase == "before") "scalar_t" else "quantity_t"
        val source = text.getValue(selected)
        val typedefStart = source.indexOf("typedef ").also { require(it >= 0) }
        val offset = source.indexOf(name, typedefStart).also { require(it >= 0) } + 1
        return CTypedefRenameFactsDecoder.decodeSource(asts, text, selected, offset, units, preservedSpelling = "scalar_t")
    }
    fun facts(mode: String, form: String, phase: String) = decode(mode, form, phase)
        .shouldBeInstanceOf<CTypedefReadout.Found>().facts
    for (mode in listOf("c11", "c17")) {
        for (form in listOf("scalar", "pointer", "chain")) {
            "$mode $form source alias transition retains exact sites underlying type and unrelated TU homonym" {
                val before = facts(mode, form, "before")
                val after = facts(mode, form, "after")
                CTypedefRenameBinding.check(before, after, "quantity_t") shouldBe CTypedefTransition.Verified
                before.name shouldBe "scalar_t"
                after.name shouldBe "quantity_t"
                before.translationUnits shouldBe units
                before.sites.size shouldBe 2
                before.sites.count { it.kind == CTypedefSiteKind.DECLARATION } shouldBe 1
                before.sites.count { it.kind == CTypedefSiteKind.TYPE_USE } shouldBe 1
                before.sites.map { it.file }.toSet() shouldBe setOf(selected)
                before.observations.map { it.translationUnit }.toSet() shouldBe setOf(selected)
                val oldText = sources(mode, form, "before")
                val newText = sources(mode, form, "after")
                before.sites.forEach { oldText.getValue(it.file).substring(it.span.start, it.span.endExclusive) shouldBe "scalar_t" }
                after.sites.forEach { newText.getValue(it.file).substring(it.span.start, it.span.endExclusive) shouldBe "quantity_t" }
                oldText.getValue(peer) shouldBe newText.getValue(peer)
                before.sourceHashes.filter { (path, hash) -> after.sourceHashes[path] != hash }.keys shouldBe setOf(selected)
                before.otherObservations.any { it.space == CTypedefOtherSpace.MEMBER_DECLARATION } shouldBe true
                before.underlyingQualified shouldBe after.underlyingQualified
                before.underlyingCanonical shouldBe after.underlyingCanonical
            }
        }
        "$mode source alias transition refuses an actual changed underlying type" {
            CTypedefRenameBinding.check(facts(mode, "scalar", "before"), facts(mode, "scalar", "changed-underlying"), "quantity_t") shouldBe
                CTypedefTransition.Refused(CTypedefTransitionRefusal.DECLARATION_IDENTITY)
        }
        "$mode source alias transition refuses edits to the unrelated TU homonym" {
            CTypedefRenameBinding.check(facts(mode, "scalar", "before"), facts(mode, "scalar", "changed-peer"), "quantity_t") shouldBe
                CTypedefTransition.Refused(CTypedefTransitionRefusal.SOURCE_IDENTITY)
        }
        "$mode source alias closure refuses a missing captured TU" {
            decode(mode, "scalar", "before", trees(mode, "scalar", "before").filterKeys { it != peer }) shouldBe
                CTypedefReadout.Refused(CTypedefFactsRefusal.INCOMPLETE_TUS)
        }
    }
})

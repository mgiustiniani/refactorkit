package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** REQ-C-SEMANTIC-RENAME-001: complete compiler type closure with distinct C lookup spaces. */
class CTypedefRenameFactsSpec : StringSpec({
    val fixture = Json.parseToJsonElement(requireNotNull(CTypedefRenameFactsSpec::class.java
        .getResource("/typedef-family-rename/native-fixture.json")).readText()).jsonObject.getValue("phases").jsonObject
    fun sources(phase: String) = fixture.getValue(phase).jsonObject.getValue("sources").jsonObject
        .map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
    fun trees(phase: String) = fixture.getValue(phase).jsonObject.getValue("asts").jsonObject
        .map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
    val header = Path.of("include/api.h")
    val api = Path.of("src/api.c")
    val main = Path.of("src/main.c")
    val units = setOf(api, main)
    fun selection(phase: String) = sources(phase).getValue(header).indexOf(if (phase in setOf("after", "changed-underlying")) "quantity_t" else "value_t")
    fun decode(phase: String = "before", asts: Map<Path, JsonObject> = trees(phase), text: Map<Path, String> = sources(phase)) =
        CTypedefRenameFactsDecoder.decode(asts, text, header, selection(phase) + 1, units, preservedSpelling = "value_t")
    fun facts(phase: String = "before", asts: Map<Path, JsonObject> = trees(phase)) =
        decode(phase, asts).shouldBeInstanceOf<CTypedefReadout.Found>().facts
    fun rewrite(value: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> transform(JsonObject(value.mapValues { rewrite(it.value, transform) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, transform) })
        else -> value
    }
    fun kind(n: JsonObject) = n["kind"]?.jsonPrimitive?.content
    fun name(n: JsonObject) = n["name"]?.jsonPrimitive?.content
    fun mutate(transform: (JsonObject) -> JsonObject) = trees("before").mapValues { (_, t) -> rewrite(t, transform).jsonObject }

    "compiler closure contains exactly the declaration and twelve source type uses" {
        val f = facts()
        f.name shouldBe "value_t"
        f.sites.size shouldBe 13
        f.sites.count { it.kind == CTypedefSiteKind.DECLARATION } shouldBe 1
        f.sites.groupingBy { it.file }.eachCount() shouldBe mapOf(header to 5, api to 4, main to 4)
        f.observations.size shouldBe 18
    }
    "ordinary member tag and label namespace bindings remain explicit non-renamed observations" {
        val f = facts()
        f.otherObservations.map { it.space }.toSet() shouldBe CTypedefOtherSpace.entries.toSet()
        f.otherObservations.map { it.file to it.span }.distinct().size shouldBe 9
    }
    "real native post-model preserves types and shifted renamed and non-renamed binding ranges" {
        CTypedefRenameBinding.check(facts(), facts("after"), "quantity_t") shouldBe CTypedefTransition.Verified
    }
    "the selected alias retains its canonical int underlying type" {
        facts().underlyingQualified shouldBe "int"
        facts().underlyingCanonical shouldBe "int"
    }
    "an alias chain is a source type use while its separate ordinary declaration stays intact" {
        val f = facts()
        ("alias_t" in f.otherOrdinaryNames) shouldBe true
        val text = sources("before").getValue(header)
        val start = text.indexOf("typedef value_t alias_t") + "typedef ".length
        (CTypedefSite(header, CFunctionByteSpan(start, start + 7), CTypedefSiteKind.TYPE_USE) in f.sites) shouldBe true
    }
    "sizeof and alignof argument types retain compiler alias evidence" {
        facts().typeObservations.count { it.property == "argType" } shouldBe 2
    }
    "shared native type nodes do not masquerade as duplicate source declarations" {
        val ids = mutableListOf<String>()
        fun collect(n: JsonObject) {
            if (kind(n)?.endsWith("Type") == true) n["id"]?.jsonPrimitive?.content?.let(ids::add)
            n["inner"]?.jsonArray?.forEach { collect(it.jsonObject) }
        }
        collect(trees("before").getValue(main))
        ids.groupingBy { it }.eachCount().any { it.value > 1 } shouldBe true
        decode().shouldBeInstanceOf<CTypedefReadout.Found>()
    }
    "a real local typedef shadow refuses unique-alias authority" {
        decode("shadow") shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.AMBIGUOUS_TYPE_ALIAS)
    }
    "an opaque same-spelled initializer designator cannot silently disappear from source closure" {
        decode("opaque-designator") shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
    }
    "missing translation units are unavailable rather than zero consumers" {
        decode(asts = trees("before").filterKeys { it != api }) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.INCOMPLETE_TUS)
    }
    "macro-origin typedef coordinates refuse source ownership" {
        val asts = mutate { if (kind(it) == "TypedefDecl" && name(it) == "value_t" && it.containsKey("loc"))
            JsonObject(it + ("loc" to buildJsonObject { put("spellingLoc", it.getValue("loc")); put("expansionLoc", it.getValue("loc")) })) else it }
        decode(asts = asts) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.MACRO_OR_UNOWNED_RANGE)
    }
    "unknown compiler alias declaration identities refuse type-reference authority" {
        val asts = mutate { if (it["qualType"]?.jsonPrimitive?.content == "value_t" && it.containsKey("typeAliasDeclId"))
            JsonObject(it + ("typeAliasDeclId" to JsonPrimitive("unknown-alias"))) else it }
        decode(asts = asts) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_TYPE_REFERENCE)
    }
    "unbound ordinary same-spelled value references cannot be mistaken for type uses" {
        val asts = mutate { if (kind(it) == "DeclRefExpr" && it["referencedDecl"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "value_t") {
            val reference = it.getValue("referencedDecl").jsonObject
            JsonObject(it + ("referencedDecl" to JsonObject(reference + ("id" to JsonPrimitive("unknown-value")))))
        } else it }
        decode(asts = asts) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_REFERENCE)
    }
    "unbound same-spelled member references cannot be edited as typedef syntax" {
        val asts = mutate { if (kind(it) == "MemberExpr" && name(it) == "value_t") JsonObject(it - "referencedMemberDecl") else it }
        decode(asts = asts) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_REFERENCE)
    }
    "goto targets retain their separate compiler label identity" {
        val asts = mutate { if (kind(it) == "GotoStmt") JsonObject(it + ("targetLabelDeclId" to JsonPrimitive("unknown-label"))) else it }
        decode(asts = asts) shouldBe CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_REFERENCE)
    }
    "a source alias token in an uncompiled header refuses closure" {
        decode(text = sources("before") + (Path.of("include/unused.h") to "value_t external;\n")) shouldBe
            CTypedefReadout.Refused(CTypedefFactsRefusal.UNBOUND_SOURCE_TOKEN)
    }
    "an actually compiled staged underlying type change fails declaration identity" {
        CTypedefRenameBinding.check(facts(), facts("changed-underlying"), "quantity_t") shouldBe
            CTypedefTransition.Refused(CTypedefTransitionRefusal.DECLARATION_IDENTITY)
    }
    "retained type and namespace evidence is immutable" {
        val f = facts()
        runCatching { (f.sites as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.observations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.typeObservations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.otherObservations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.otherOrdinaryNames as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.translationUnits as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.sourceHashes as MutableMap).clear() }.isFailure shouldBe true
    }
    "existing destination tag declarations are retained before a typedef rename" {
        val phases = Json.parseToJsonElement(requireNotNull(CTypedefRenameFactsSpec::class.java
            .getResource("/typedef-family-rename/destination-tags.json")).readText()).jsonObject.getValue("phases").jsonObject
        val image = phases.getValue("before").jsonObject
        val text = image.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
        val asts = image.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
        val f = CTypedefRenameFactsDecoder.decode(asts, text, header, text.getValue(header).indexOf("value_t"), units)
            .shouldBeInstanceOf<CTypedefReadout.Found>().facts
        f.otherObservations.map { it.file to it.span }.distinct().size shouldBe 10
    }
    "a real C tag at the destination preserves its separate binding across typedef rename" {
        val phases = Json.parseToJsonElement(requireNotNull(CTypedefRenameFactsSpec::class.java
            .getResource("/typedef-family-rename/destination-tags.json")).readText()).jsonObject.getValue("phases").jsonObject
        fun destinationFacts(phase: String): CTypedefRenameFacts {
            val image = phases.getValue(phase).jsonObject
            val text = image.getValue("sources").jsonObject.map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
            val asts = image.getValue("asts").jsonObject.map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
            val alias = if (phase == "before") "value_t" else "quantity_t"
            return CTypedefRenameFactsDecoder.decode(asts, text, header, text.getValue(header).indexOf(alias), units,
                preservedSpelling = "value_t").shouldBeInstanceOf<CTypedefReadout.Found>().facts
        }
        CTypedefRenameBinding.check(destinationFacts("before"), destinationFacts("after"), "quantity_t") shouldBe CTypedefTransition.Verified
    }
})

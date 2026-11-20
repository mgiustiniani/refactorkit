package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** REQ-C-SEMANTIC-RENAME-001: unique compiler tag families, typed source anchors and namespace closure. */
class CTagRenameFactsSpec : StringSpec({
    val fixture = Json.parseToJsonElement(requireNotNull(CTagRenameFactsSpec::class.java
        .getResource("/tag-family-rename/native-fixture.json")).readText()).jsonObject.getValue("phases").jsonObject
    fun sources(phase: String) = fixture.getValue(phase).jsonObject.getValue("sources").jsonObject
        .map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
    fun trees(phase: String) = fixture.getValue(phase).jsonObject.getValue("asts").jsonObject
        .map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
    val header = Path.of("include/api.h")
    val api = Path.of("src/api.c")
    val main = Path.of("src/main.c")
    val units = setOf(api, main)
    val oldNames = mapOf(CTagKind.STRUCT to "meter", CTagKind.UNION to "payload", CTagKind.ENUM to "stage")
    val newNames = mapOf(CTagKind.STRUCT to "sensor", CTagKind.UNION to "message", CTagKind.ENUM to "phase")
    fun selected(kind: CTagKind, phase: String) = sources(phase).getValue(header).indexOf(kind.keyword + " ") + kind.keyword.length + 1
    fun decode(kind: CTagKind, phase: String = "before", asts: Map<Path, JsonObject> = trees(phase),
        text: Map<Path, String> = sources(phase)) = CTagRenameFactsDecoder.decode(asts, text, header, selected(kind, phase) + 1, units)
    fun facts(kind: CTagKind, phase: String = "before", asts: Map<Path, JsonObject> = trees(phase)) =
        decode(kind, phase, asts).shouldBeInstanceOf<CTagReadout.Found>().facts
    fun rewrite(value: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> transform(JsonObject(value.mapValues { rewrite(it.value, transform) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, transform) })
        else -> value
    }
    fun nodeKind(n: JsonObject) = n["kind"]?.jsonPrimitive?.content
    fun nodeName(n: JsonObject) = n["name"]?.jsonPrimitive?.content
    for (kind in CTagKind.entries) {
        "${kind.keyword} closes the shared header declaration and typed declaration cast and sizeof uses" {
            val f = facts(kind)
            f.name shouldBe oldNames.getValue(kind)
            f.kind shouldBe kind
            f.translationUnits shouldBe units
            f.sites.size shouldBe 4
            f.sites.count { it.kind == CTagSiteKind.DECLARATION } shouldBe 1
            f.observations.count { it.site.file == header } shouldBe 2
            f.sites.filter { it.file == main }.map { it.kind } shouldBe List(3) { CTagSiteKind.TYPE_USE }
        }
        "${kind.keyword} native post-model preserves declaration shape and normalized compiler types" {
            CTagRenameBinding.check(facts(kind), facts(kind, kind.keyword), newNames.getValue(kind)) shouldBe CTagTransition.Verified
        }
    }
    "ordinary local names comments and string literals do not enter the tag lookup space" {
        for (kind in CTagKind.entries) {
            val f = facts(kind)
            val content = sources("before").getValue(main)
            val touchedLines = f.sites.filter { it.file == main }.map { content.substring(0, it.span.start).count { c -> c == '\n' } }.toSet()
            (touchedLines intersect setOf(5, 6, 7, 8, 9)).isEmpty() shouldBe true
        }
    }
    "all other tag names remain explicit destination collision evidence" {
        val all = oldNames.values.toSet()
        for (kind in CTagKind.entries) facts(kind).otherTagNames shouldBe all - oldNames.getValue(kind)
    }
    "a real block-scoped same-spelled tag refuses the unique-family authority" {
        decode(CTagKind.STRUCT, "shadow") shouldBe CTagReadout.Refused(CTagFactsRefusal.AMBIGUOUS_TAG_FAMILY)
    }
    "a missing configured translation unit cannot shrink the family" {
        decode(CTagKind.STRUCT, asts = trees("before").filterKeys { it != api }) shouldBe
            CTagReadout.Refused(CTagFactsRefusal.INCOMPLETE_TUS)
    }
    "macro-origin tag coordinates refuse source ownership" {
        val asts = trees("before").mapValues { (_, tree) -> rewrite(tree) {
            if (nodeKind(it) == "RecordDecl" && nodeName(it) == "meter")
                JsonObject(it + ("loc" to buildJsonObject { put("spellingLoc", it.getValue("loc")); put("expansionLoc", it.getValue("loc")) })) else it
        }.jsonObject }
        decode(CTagKind.STRUCT, asts = asts) shouldBe CTagReadout.Refused(CTagFactsRefusal.MACRO_OR_UNOWNED_RANGE)
    }
    "a keyword pair in an uncompiled header has no compiler type witness" {
        decode(CTagKind.STRUCT, text = sources("before") + (Path.of("include/unused.h") to "struct meter *foreign;\n")) shouldBe
            CTagReadout.Refused(CTagFactsRefusal.UNBOUND_TYPE_USE)
    }
    "source type tokens cannot stand in for a missing native type witness" {
        val asts = trees("before").toMutableMap().apply { this[main] = rewrite(getValue(main)) {
            if (nodeKind(it) == "VarDecl" && nodeName(it) == "reading")
                JsonObject(it + ("type" to buildJsonObject { put("qualType", "struct unrelated") })) else it
        }.jsonObject }
        decode(CTagKind.STRUCT, asts = asts) shouldBe CTagReadout.Refused(CTagFactsRefusal.UNBOUND_TYPE_USE)
    }
    "a staged record member type change fails declaration identity" {
        val asts = trees("struct").mapValues { (_, tree) -> rewrite(tree) {
            if (nodeKind(it) == "FieldDecl" && nodeName(it) == "raw_count")
                JsonObject(it + ("type" to buildJsonObject { put("qualType", "double") })) else it
        }.jsonObject }
        CTagRenameBinding.check(facts(CTagKind.STRUCT), facts(CTagKind.STRUCT, "struct", asts), "sensor") shouldBe
            CTagTransition.Refused(CTagTransitionRefusal.DECLARATION_IDENTITY)
    }
    "a retained staged type observation cannot disappear from the binding transition" {
        val before = facts(CTagKind.STRUCT)
        val after = facts(CTagKind.STRUCT, "struct")
        val incomplete = CTagRenameFacts(after.name, after.kind, after.observations, after.typeObservations.dropLast(1),
            after.declarationShape, after.otherTagNames, after.translationUnits, after.sourceHashes)
        CTagRenameBinding.check(before, incomplete, "sensor") shouldBe CTagTransition.Refused(CTagTransitionRefusal.REFERENCE_FAMILY)
    }
    "all retained tag fact collections are immutable" {
        val f = facts(CTagKind.STRUCT)
        runCatching { (f.sites as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.observations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.typeObservations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.declarationShape as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.otherTagNames as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.translationUnits as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.sourceHashes as MutableMap).clear() }.isFailure shouldBe true
    }
    "GNU attributes accepted by the compiler do not imply an admitted ISO tag source" {
        decode(CTagKind.STRUCT, "gnu-attribute") shouldBe CTagReadout.Refused(CTagFactsRefusal.UNSUPPORTED_SOURCE)
    }
    "GNU alternate typeof spellings remain outside the admitted source profile" {
        decode(CTagKind.STRUCT, "gnu-typeof") shouldBe CTagReadout.Refused(CTagFactsRefusal.UNSUPPORTED_SOURCE)
    }
})

package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** REQ-C-SEMANTIC-RENAME-001: compiler-owned ordinary header families and linkage. */
class COrdinaryFamilyRenameFactsSpec : StringSpec({
    val fixture = Json.parseToJsonElement(requireNotNull(COrdinaryFamilyRenameFactsSpec::class.java
        .getResource("/ordinary-family-rename/native-fixture.json")).readText()).jsonObject.getValue("phases").jsonObject
    fun sources(phase: String) = fixture.getValue(phase).jsonObject.getValue("sources").jsonObject
        .map { (p, s) -> Path.of(p) to s.jsonPrimitive.content }.toMap()
    fun trees(phase: String) = fixture.getValue(phase).jsonObject.getValue("asts").jsonObject
        .map { (p, a) -> Path.of(p) to a.jsonObject }.toMap()
    val header = Path.of("include/api.h")
    val api = Path.of("src/api.c")
    val main = Path.of("src/main.c")
    val units = setOf(api, main)
    val oldNames = mapOf("function" to "calculate", "global" to "item_count", "enumerator" to "ORDER_PENDING")
    val newNames = mapOf("function" to "compute", "global" to "request_count", "enumerator" to "ORDER_OPEN")
    val expectedKinds = mapOf("function" to COrdinaryFamilyKind.FUNCTION, "global" to COrdinaryFamilyKind.GLOBAL_OBJECT,
        "enumerator" to COrdinaryFamilyKind.ENUMERATOR)
    fun selection(family: String, phase: String) = sources(phase).getValue(header).let { text ->
        val name = if (phase == "before") oldNames.getValue(family) else newNames.getValue(family)
        val marker = when (family) { "global" -> "extern value_t "; "function" -> "value_t "; else -> "enum stage { " }
        if (family == "function") text.indexOf(marker + name) + marker.length else text.indexOf(marker) + marker.length
    }
    fun decode(family: String, phase: String = "before", asts: Map<Path, JsonObject> = trees(phase)) =
        COrdinaryFamilyRenameFactsDecoder.decode(asts, sources(phase), header, selection(family, phase) + 1, units)
    fun facts(family: String, phase: String = "before", asts: Map<Path, JsonObject> = trees(phase)) =
        decode(family, phase, asts).shouldBeInstanceOf<COrdinaryFamilyReadout.Found>().facts
    fun rewrite(value: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> transform(JsonObject(value.mapValues { rewrite(it.value, transform) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, transform) })
        else -> value
    }
    fun kind(node: JsonObject) = node["kind"]?.jsonPrimitive?.content
    fun name(node: JsonObject) = node["name"]?.jsonPrimitive?.content
    fun change(phase: String, unit: Path, transform: (JsonObject) -> JsonObject) = trees(phase).toMutableMap().apply {
        this[unit] = rewrite(getValue(unit), transform).jsonObject
    }
    for ((family, expected) in expectedKinds) {
        "$family resolves the canonical declaration family across both native translation units" {
            val f = facts(family)
            f.name shouldBe oldNames.getValue(family)
            f.kind shouldBe expected
            f.translationUnits shouldBe units
            f.sites.size shouldBe if (family == "global") 4 else 3
            f.observations.count { it.site.file == header } shouldBe 2
            f.sites.count { it.kind == COrdinarySiteKind.DECLARATION } shouldBe if (family == "enumerator") 1 else 2
        }
        "$family native staged model preserves exact declarations, reference paths and shifted bytes" {
            COrdinaryFamilyRenameBinding.check(facts(family), facts(family, family), newNames.getValue(family)) shouldBe
                COrdinaryFamilyTransition.Verified
        }
    }
    "same-spelled locals, fields, comments and strings are excluded by compiler identity" {
        for (family in expectedKinds.keys) {
            val f = facts(family)
            val mainUses = f.sites.filter { it.file == main }
            mainUses.size shouldBe 1
            val text = sources("before").getValue(main)
            val line = text.substring(0, mainUses.single().span.start).count { it == '\n' }
            line shouldBe 11
        }
    }
    "an unknown previous declaration cannot manufacture an external family" {
        val asts = change("before", api) {
            if (kind(it) == "FunctionDecl" && it.containsKey("previousDecl"))
                JsonObject(it + ("previousDecl" to JsonPrimitive("missing-compiler-declaration"))) else it
        }
        decode("function", asts = asts) shouldBe COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
    }
    "a missing relevant TU refuses rather than shrinking the consumer family" {
        decode("function", asts = trees("before").filterKeys { it != main }) shouldBe
            COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.INCOMPLETE_TUS)
    }
    "a native reference without an owned declaration cannot count as zero uses" {
        val asts = change("before", main) {
            if (kind(it) == "DeclRefExpr" && it["referencedDecl"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "item_count")
                JsonObject(it - "referencedDecl") else it
        }
        decode("global", asts = asts) shouldBe COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.UNBOUND_REFERENCE)
    }
    "macro expansion coordinates refuse source ownership" {
        val asts = change("before", api) {
            if (kind(it) == "FunctionDecl" && it.containsKey("previousDecl"))
                JsonObject(it + ("loc" to buildJsonObject { put("spellingLoc", it.getValue("loc")); put("expansionLoc", it.getValue("loc")) })) else it
        }
        decode("function", asts = asts) shouldBe COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.MACRO_OR_UNOWNED_RANGE)
    }
    "an externally declared function with no captured definition remains unavailable" {
        val asts = change("before", api) {
            if (kind(it) == "FunctionDecl" && name(it) == "calculate") JsonObject(it + ("inner" to
                JsonArray(it["inner"]?.jsonArray.orEmpty().filterNot { n -> kind(n.jsonObject) == "CompoundStmt" }))) else it
        }
        decode("function", asts = asts) shouldBe COrdinaryFamilyReadout.Refused(COrdinaryFamilyRefusal.DEFINITION_UNAVAILABLE)
    }
    "staged type drift fails the ordinary declaration identity" {
        val asts = change("global", api) {
            if (kind(it) == "VarDecl" && name(it) == "request_count" && it.containsKey("previousDecl"))
                JsonObject(it + ("type" to buildJsonObject { put("qualType", "double") })) else it
        }
        val altered = decode("global", "global", asts)
        // Both an internally inconsistent family and an explicit transition refusal are fail-closed.
        when (altered) {
            is COrdinaryFamilyReadout.Refused -> altered.reason shouldBe COrdinaryFamilyRefusal.INCONSISTENT_DECLARATION
            is COrdinaryFamilyReadout.Found -> COrdinaryFamilyRenameBinding.check(facts("global"), altered.facts, "request_count") shouldBe
                COrdinaryFamilyTransition.Refused(COrdinaryTransitionRefusal.DECLARATION_IDENTITY)
        }
    }
    "a missing staged enumerator observation cannot pass the family transition" {
        val asts = change("enumerator", main) {
            if (kind(it) == "DeclRefExpr" && it["referencedDecl"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "ORDER_OPEN")
                JsonObject(it + ("kind" to JsonPrimitive("OpaqueValueExpr"))) else it
        }
        COrdinaryFamilyRenameBinding.check(facts("enumerator"), facts("enumerator", "enumerator", asts), "ORDER_OPEN") shouldBe
            COrdinaryFamilyTransition.Refused(COrdinaryTransitionRefusal.REFERENCE_FAMILY)
    }
    "retained facts expose no mutable collection or compiler-pointer identity" {
        val f = facts("function")
        runCatching { (f.observations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.sites as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.translationUnits as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.sourceHashes as MutableMap).clear() }.isFailure shouldBe true
    }
})

package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** REQ-C-SEMANTIC-RENAME-001: exact header/member/designator family across translation units. */
class CHeaderFieldRenameFactsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CHeaderFieldRenameFactsSpec::class.java
        .getResource("/header-field-rename/$name")).readText()
    val inputs = Json.parseToJsonElement(resource("fixture-inputs.json")).jsonObject
    fun sources(phase: String) = inputs.getValue("sources").jsonObject.getValue(phase).jsonObject
        .map { (file, value) -> Path.of(file) to value.jsonPrimitive.content }.toMap()
    fun trees(phase: String) = listOf("read_meter", "main").associate {
        Path.of("src/$it.c") to Json.parseToJsonElement(resource("$phase-$it-ast.json")).jsonObject
    }
    val header = Path.of("include/api.h")
    val read = Path.of("src/read_meter.c")
    val main = Path.of("src/main.c")
    val units = setOf(read, main)
    fun span(file: Path, line: Int, needle: String, prefix: Int): CFunctionByteSpan {
        val lines = sources("before").getValue(file).lines()
        val start = lines.take(line).sumOf { it.length + 1 } + lines[line].indexOf(needle).also { require(it >= 0) } + prefix
        return CFunctionByteSpan(start, start + "raw_count".length)
    }
    val selected = span(header, 1, "int raw_count", 4)
    fun decode(phase: String = "before", ast: Map<Path, JsonObject> = trees(phase)) =
        CHeaderFieldRenameFactsDecoder.decode(ast, sources(phase), header, selected.start + 2, units)
    fun facts(phase: String = "before", ast: Map<Path, JsonObject> = trees(phase)) =
        decode(phase, ast).shouldBeInstanceOf<CHeaderFieldReadout.Found>().facts
    fun rewrite(value: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> transform(JsonObject(value.mapValues { rewrite(it.value, transform) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, transform) })
        else -> value
    }
    fun kind(node: JsonObject) = (node["kind"] as? JsonPrimitive)?.content
    fun change(tu: Path, transform: (JsonObject) -> JsonObject) = trees("before").toMutableMap().apply {
        this[tu] = rewrite(getValue(tu), transform).jsonObject
    }

    "compiler closure includes the header, out-of-order designator and member use" {
        val f = facts()
        f.name shouldBe "raw_count"
        f.recordName shouldBe "meter_reading"
        f.recordKind shouldBe "struct"
        f.fieldIndex shouldBe 0
        f.fields shouldBe listOf(CHeaderRecordField("raw_count", "int"), CHeaderRecordField("calibration", "int"))
        f.sites shouldBe listOf(
            CHeaderFieldSite(header, selected, CHeaderFieldSiteKind.DECLARATION),
            CHeaderFieldSite(main, span(main, 6, "reading.raw_count", 8), CHeaderFieldSiteKind.MEMBER),
            CHeaderFieldSite(read, span(read, 2, ".raw_count", 1), CHeaderFieldSiteKind.DESIGNATOR),
        )
    }

    "separate translation-unit observations deduplicate only the physical header edit" {
        val f = facts()
        f.translationUnits shouldBe units
        f.observations.size shouldBe 4
        f.observations.count { it.site.kind == CHeaderFieldSiteKind.DECLARATION } shouldBe 2
        f.sites.size shouldBe 3
        f.sites.count { it.file == header } shouldBe 1
    }

    "real compiler post-model preserves record layout and exact binding family" {
        CHeaderFieldRenameBinding.check(facts(), facts("after"), "scaled_count") shouldBe CHeaderFieldTransition.Verified
    }

    "missing member identity is unavailable rather than an unused field" {
        val ast = change(main) { if (kind(it) == "MemberExpr") JsonObject(it - "referencedMemberDecl") else it }
        decode(ast = ast) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.UNBOUND_REFERENCE)
    }

    "an absent configured consumer refuses the whole family" {
        // Path is Iterable<Path>; Map.minus(path) can select the iterable overload.
        val incomplete = trees("before").filterKeys { it != read }
        incomplete.keys shouldBe setOf(main)
        decode(ast = incomplete) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.INCOMPLETE_TUS)
    }

    "macro-origin member coordinates never gain source ownership" {
        val ast = change(main) {
            if (kind(it) != "MemberExpr") it else {
                val range = it.getValue("range").jsonObject
                JsonObject(it + ("range" to JsonObject(range + ("end" to buildJsonObject {
                    put("spellingLoc", range.getValue("end")); put("expansionLoc", range.getValue("end"))
                }))))
            }
        }
        decode(ast = ast) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
    }

    "designators require their corresponding semantic field value ranges" {
        val ast = change(read) {
            if (kind(it) == "InitListExpr" && it["type"]?.jsonObject?.get("qualType")?.jsonPrimitive?.content == "struct meter_reading")
                JsonObject(it + ("inner" to JsonArray(it.getValue("inner").jsonArray.reversed()))) else it
        }
        decode(ast = ast) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.DESIGNATOR_BINDING)
    }

    "a post-model changing another field type loses declaration identity" {
        val ast = trees("after").mapValues { (_, tree) -> rewrite(tree) {
            if (kind(it) == "FieldDecl" && it["name"]?.jsonPrimitive?.content == "calibration")
                JsonObject(it + ("type" to buildJsonObject { put("qualType", "double") })) else it
        }.jsonObject }
        CHeaderFieldRenameBinding.check(facts(), facts("after", ast), "scaled_count") shouldBe
            CHeaderFieldTransition.Refused(CHeaderFieldTransitionRefusal.DECLARATION_IDENTITY)
    }

    "a missing staged member observation cannot satisfy the binding transition" {
        val ast = trees("after").mapValues { (_, tree) -> rewrite(tree) {
            if (kind(it) == "MemberExpr") JsonObject(it + ("kind" to JsonPrimitive("OpaqueValueExpr"))) else it
        }.jsonObject }
        CHeaderFieldRenameBinding.check(facts(), facts("after", ast), "scaled_count") shouldBe
            CHeaderFieldTransition.Refused(CHeaderFieldTransitionRefusal.REFERENCE_FAMILY)
    }

    "native offsetof lacking a complete member binding refuses" {
        val counter = inputs.getValue("counterexamples").jsonObject.getValue("opaque-offset").jsonObject
        val tu = Path.of(counter.getValue("translationUnit").jsonPrimitive.content)
        val ast = trees("before") + (tu to Json.parseToJsonElement(counter.getValue("astText").jsonPrimitive.content).jsonObject)
        val material = sources("before") + (tu to counter.getValue("source").jsonPrimitive.content)
        CHeaderFieldRenameFactsDecoder.decode(ast, material, header, selected.start, units) shouldBe
            CHeaderFieldReadout.Refused(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
    }

    "native overwritten designators do not disappear from the closure" {
        val counter = inputs.getValue("counterexamples").jsonObject.getValue("overridden-designator").jsonObject
        val tu = Path.of(counter.getValue("translationUnit").jsonPrimitive.content)
        val ast = trees("before") + (tu to Json.parseToJsonElement(counter.getValue("astText").jsonPrimitive.content).jsonObject)
        val material = sources("before") + (tu to counter.getValue("source").jsonPrimitive.content)
        CHeaderFieldRenameFactsDecoder.decode(ast, material, header, selected.start, units) shouldBe
            CHeaderFieldReadout.Refused(CHeaderFieldRefusal.DESIGNATOR_BINDING)
    }

    "retained physical and translation-unit evidence is immutable" {
        val f = facts()
        runCatching { (f.sites as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.observations as MutableList).clear() }.isFailure shouldBe true
        runCatching { (f.translationUnits as MutableSet).clear() }.isFailure shouldBe true
        runCatching { (f.sourceHashes as MutableMap).clear() }.isFailure shouldBe true
    }

    val sdkHeader = Path.of("/readonly-sdk/usr/include/types.h")
    val sdkText = "typedef int sdk_count;\n"
    fun sdkTrees(phase: String = "before") = trees(phase).mapValues { (tu, tree) ->
        val sdkDeclaration = buildJsonObject {
            put("id", "sdk-count-$tu"); put("kind", "TypedefDecl"); put("name", "sdk_count")
            put("loc", buildJsonObject { put("file", sdkHeader.toString()); put("offset", 12); put("tokLen", 9) })
            put("range", buildJsonObject {
                put("begin", buildJsonObject { put("file", sdkHeader.toString()); put("offset", 0); put("tokLen", 7) })
                put("end", buildJsonObject { put("file", sdkHeader.toString()); put("offset", 12); put("tokLen", 9) })
            })
            put("type", buildJsonObject { put("qualType", "int") })
        }
        JsonObject(tree + ("inner" to JsonArray(tree.getValue("inner").jsonArray + sdkDeclaration)))
    }
    fun sdkDecode(phase: String = "before", material: Map<Path, String> = sources(phase),
        readOnly: Map<Path, String> = mapOf(sdkHeader to sdkText), selectedFile: Path = header,
        asts: Map<Path, JsonObject> = sdkTrees(phase)) = CHeaderFieldRenameFactsDecoder.decode(
            asts, material, selectedFile, selected.start + 2, units, readOnly)

    "readonly SDK declarations decode without acquiring source edit ownership" {
        val before = sdkDecode().shouldBeInstanceOf<CHeaderFieldReadout.Found>().facts
        before.sites shouldBe facts().sites
        before.sourceHashes shouldBe facts().sourceHashes
        before.sourceHashes.containsKey(sdkHeader) shouldBe false
        val after = sdkDecode("after").shouldBeInstanceOf<CHeaderFieldReadout.Found>().facts
        CHeaderFieldRenameBinding.check(before, after, "scaled_count") shouldBe CHeaderFieldTransition.Verified
    }

    "missing readonly SDK bytes cannot become a complete source graph" {
        sdkDecode(readOnly = emptyMap()) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
    }

    "a readonly SDK input cannot also claim writable source ownership" {
        sdkDecode(material = sources("before") + (sdkHeader to sdkText)) shouldBe
            CHeaderFieldReadout.Refused(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
    }

    "readonly SDK declarations cannot be selected for field mutation" {
        sdkDecode(selectedFile = sdkHeader) shouldBe CHeaderFieldReadout.Refused(CHeaderFieldRefusal.SELECTION_UNAVAILABLE)
    }

    "readonly reference sites never acquire workspace field edit authority" {
        val copied = Path.of("/readonly-sdk/usr/include/consumer.h")
        val ast = trees("before").toMutableMap().apply {
            this[main] = rewrite(getValue(main)) { node ->
                if (node["file"]?.jsonPrimitive?.content == main.toString()) JsonObject(node + ("file" to JsonPrimitive(copied.toString()))) else node
            }.jsonObject
        }
        CHeaderFieldRenameFactsDecoder.decode(ast, sources("before"), header, selected.start, units,
            mapOf(copied to sources("before").getValue(main))) shouldBe
            CHeaderFieldReadout.Refused(CHeaderFieldRefusal.MACRO_OR_UNOWNED_RANGE)
    }

    "readonly SDK syntax retains the existing macro and unsupported-source refusal" {
        sdkDecode(readOnly = mapOf(sdkHeader to "#define SDK_COUNT int\ntypedef SDK_COUNT sdk_count;\n")) shouldBe
            CHeaderFieldReadout.Refused(CHeaderFieldRefusal.UNSUPPORTED_SOURCE)
    }
})

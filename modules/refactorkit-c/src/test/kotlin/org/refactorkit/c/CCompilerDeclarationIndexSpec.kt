package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

/** Pure C04 compiler projection. Native first-party datasets supply declarations, never lexical guesses. */
class CCompilerDeclarationIndexSpec : StringSpec({
    fun resource(path: String) = requireNotNull(CCompilerDeclarationIndexSpec::class.java.getResource(path)).readText()
    val single = Path.of("fixture.c")
    val singleSource = resource("/variable-rename/fixture.c")
    val singleAst = Json.parseToJsonElement(resource("/variable-rename/fixture-ast.json")).jsonObject
    val inputs = Json.parseToJsonElement(resource("/header-field-rename/fixture-inputs.json")).jsonObject
    val headerSources = inputs.getValue("sources").jsonObject.getValue("before").jsonObject
        .map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    val headerAsts = listOf("read_meter", "main").associate { name ->
        Path.of("src/$name.c") to Json.parseToJsonElement(resource("/header-field-rename/before-$name-ast.json")).jsonObject
    }
    val typedef = Json.parseToJsonElement(resource("/typedef-family-rename/native-fixture.json")).jsonObject
        .getValue("phases").jsonObject.getValue("before").jsonObject
    val typedefSources = typedef.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
    val typedefAsts = typedef.getValue("asts").jsonObject.map { (p, v) -> Path.of(p) to v.jsonObject }.toMap()
    fun index(asts: Map<Path, JsonObject> = mapOf(single to singleAst), sources: Map<Path, String> = mapOf(single to singleSource)) =
        CCompilerDeclarationIndex.analyze(asts, sources).shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index
    fun rewrite(value: JsonElement, change: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> change(JsonObject(value.mapValues { rewrite(it.value, change) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, change) })
        else -> value
    }
    fun refused(asts: Map<Path, JsonObject>, sources: Map<Path, String>, expected: CCompilerDeclarationRefusal) {
        CCompilerDeclarationIndex.analyze(asts, sources) shouldBe CCompilerDeclarationReadout.Refused(expected)
    }

    "native variable and function declarations project exact source names and ranges" {
        val declarations = index().declarations
        declarations.size shouldBe 9
        declarations.filter { it.kind == CCompilerDeclarationKind.FUNCTION }.map { it.name }.toSet() shouldBe setOf("calculate", "read", "main")
        declarations.forEach { d ->
            val lines = singleSource.lines()
            lines[d.range.start.line].substring(d.range.start.character, d.range.end.character) shouldBe d.name
        }
    }
    "shadowed ordinary identifiers retain distinct physical identities and scopes" {
        val values = index().search("value").items
        values.size shouldBe 4
        values.map { it.id }.toSet().size shouldBe 4
        values.map { it.scopeId }.toSet().size shouldBe 4
        values.map { it.lookupSpace }.toSet() shouldBe setOf(CCompilerLookupSpace.ORDINARY)
    }
    "file-static declarations and local variables retain different C linkage" {
        val declarations = index().declarations
        declarations.filter { it.name in setOf("calculate", "read") }.map { it.linkage }.toSet() shouldBe setOf(CCompilerLinkage.INTERNAL)
        declarations.single { it.name == "main" }.linkage shouldBe CCompilerLinkage.EXTERNAL
        declarations.single { it.kind == CCompilerDeclarationKind.PARAMETER }.linkage shouldBe CCompilerLinkage.NONE
        declarations.filter { it.name == "value" }.groupingBy { it.linkage }.eachCount() shouldBe
            mapOf(CCompilerLinkage.INTERNAL to 1, CCompilerLinkage.NONE to 3)
    }
    "shared header fields retain each translation unit and distinct record owners" {
        val fields = index(headerAsts, headerSources).search("raw_count").items
        fields.size shouldBe 4
        fields.map { it.translationUnit }.toSet() shouldBe headerAsts.keys
        fields.map { it.scopeId }.toSet().size shouldBe 4
        fields.map { it.file }.toSet() shouldBe setOf(Path.of("include/api.h"))
        fields.map { it.kind }.toSet() shouldBe setOf(CCompilerDeclarationKind.FIELD)
    }
    "typedefs and variables use ordinary space while tags members and labels remain distinct" {
        val facts = index(typedefAsts, typedefSources)
        val sameName = facts.search("value_t").items
        sameName.map { it.lookupSpace }.toSet() shouldBe CCompilerLookupSpace.entries.toSet()
        sameName.filter { it.kind == CCompilerDeclarationKind.TYPEDEF }.map { it.lookupSpace }.toSet() shouldBe setOf(CCompilerLookupSpace.ORDINARY)
        sameName.single { it.kind == CCompilerDeclarationKind.LABEL }.file shouldBe Path.of("src/main.c")
    }
    "compiler pointer reallocation does not change durable declaration IDs" {
        val changed = rewrite(singleAst) { n ->
            if (n["id"] is JsonPrimitive) JsonObject(n + ("id" to JsonPrimitive("reallocated:" + n.getValue("id").jsonPrimitive.content))) else n
        }.jsonObject
        index(mapOf(single to changed)).declarations.map { it.id } shouldBe index().declarations.map { it.id }
    }
    "search preserves explicit totals truncation and translation-unit filters" {
        val facts = index(headerAsts, headerSources)
        val result = facts.search("raw_count", limit = 1)
        result.items.size shouldBe 1; result.total shouldBe 4; result.truncated shouldBe true
        facts.search("raw_count", translationUnit = Path.of("src/main.c")).total shouldBe 2
        facts.search("not_a_declaration").total shouldBe 0
        shouldThrow<IllegalArgumentException> { facts.search("", limit = 0) }
    }
    "published declaration and search collections cannot mutate the captured index" {
        val facts = index()
        shouldThrow<UnsupportedOperationException> { (facts.declarations as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (facts.search("").items as MutableList<*>).clear() }
    }
    "native unions and enumerators project their own C declaration kinds and lookup spaces" {
        val phase = Json.parseToJsonElement(resource("/tag-family-rename/native-fixture.json")).jsonObject
            .getValue("phases").jsonObject.getValue("before").jsonObject
        val sources = phase.getValue("sources").jsonObject.map { (p, v) -> Path.of(p) to v.jsonPrimitive.content }.toMap()
        val asts = phase.getValue("asts").jsonObject.map { (p, v) -> Path.of(p) to v.jsonObject }.toMap()
        val facts = index(asts, sources)
        val payloads = facts.search("payload").items
        payloads.map { it.kind }.toSet() shouldBe setOf(CCompilerDeclarationKind.UNION, CCompilerDeclarationKind.VARIABLE)
        payloads.filter { it.kind == CCompilerDeclarationKind.UNION }.map { it.lookupSpace }.toSet() shouldBe setOf(CCompilerLookupSpace.TAG)
        payloads.filter { it.kind == CCompilerDeclarationKind.VARIABLE }.map { it.lookupSpace }.toSet() shouldBe setOf(CCompilerLookupSpace.ORDINARY)
        facts.search("ORDER_PENDING").items.size shouldBe 2
        facts.search("ORDER_PENDING").items.map { it.kind }.toSet() shouldBe setOf(CCompilerDeclarationKind.ENUMERATOR)
        facts.search("ORDER_PENDING").items.map { it.lookupSpace }.toSet() shouldBe setOf(CCompilerLookupSpace.ORDINARY)
    }
    "a malformed native tree is unavailable rather than an empty index" {
        refused(mapOf(single to buildJsonObject { put("kind", "NotATranslationUnit") }), mapOf(single to singleSource), CCompilerDeclarationRefusal.INVALID_AST)
    }
    "a missing translation unit cannot become a complete symbol search" {
        refused(headerAsts.filterKeys { it != Path.of("src/main.c") }, headerSources, CCompilerDeclarationRefusal.INCOMPLETE_CONTEXT)
    }
    "an escaping logical source mapping refuses before producing any symbol" {
        CCompilerDeclarationIndex.analyze(mapOf(single to singleAst), mapOf(single to singleSource), mapOf(single to Path.of("../outside.c"))) shouldBe
            CCompilerDeclarationReadout.Refused(CCompilerDeclarationRefusal.UNOWNED_SOURCE)
    }
    "a native declaration range must spell the actual captured name" {
        val changed = rewrite(singleAst) { n ->
            if (n["kind"]?.jsonPrimitive?.content == "ParmVarDecl") JsonObject(n + ("name" to JsonPrimitive("wrong"))) else n
        }.jsonObject
        refused(mapOf(single to changed), mapOf(single to singleSource), CCompilerDeclarationRefusal.SOURCE_MISMATCH)
    }
    "macro expansion coordinates cannot be presented as owned declarations" {
        val changed = rewrite(singleAst) { n ->
            if (n["kind"]?.jsonPrimitive?.content == "ParmVarDecl" && n["loc"] is JsonObject) {
                val loc = n.getValue("loc").jsonObject
                JsonObject(n + ("loc" to JsonObject(loc + ("spellingLoc" to loc))))
            } else n
        }.jsonObject
        refused(mapOf(single to changed), mapOf(single to singleSource), CCompilerDeclarationRefusal.MACRO_OR_UNOWNED_RANGE)
    }
    "unsupported source encoding and over-limit context sets refuse explicitly" {
        refused(mapOf(single to singleAst), mapOf(single to singleSource + "// é\n"), CCompilerDeclarationRefusal.SOURCE_UNSUPPORTED)
        val sources = (0..16).associate { Path.of("src/$it.c") to singleSource }
        refused(sources.keys.associateWith { singleAst }, sources, CCompilerDeclarationRefusal.INPUT_LIMIT)
    }
    for (linkage in listOf("external", "internal")) {
        "block-scope extern retains the native $linkage linkage of its visible prior declaration" {
            val fixture = Json.parseToJsonElement(resource("/navigation-extern/native-fixture.json")).jsonObject
            for (record in fixture.getValue("records").jsonArray.map { it.jsonObject }.filter { it.getValue("linkage").jsonPrimitive.content == linkage }) {
                val declarations = index(mapOf(single to record.getValue("ast").jsonObject),
                    mapOf(single to record.getValue("source").jsonPrimitive.content)).declarations.filter { it.name == "value" }
                declarations.size shouldBe 2
                declarations.map { it.linkage }.toSet() shouldBe setOf(if (linkage == "external") CCompilerLinkage.EXTERNAL else CCompilerLinkage.INTERNAL)
            }
        }
    }
})

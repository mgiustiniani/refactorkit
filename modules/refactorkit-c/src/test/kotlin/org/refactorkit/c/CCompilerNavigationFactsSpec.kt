package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.*
import java.nio.file.Path

class CCompilerNavigationFactsSpec : StringSpec({
    fun resource(path: String) = requireNotNull(CCompilerNavigationFactsSpec::class.java.getResource(path)).readText()
    fun phase(name: String) = Json.parseToJsonElement(resource("/$name/native-fixture.json")).jsonObject.getValue("phases").jsonObject.getValue("before").jsonObject
    fun sources(p: JsonObject) = p.getValue("sources").jsonObject.map { (k, v) -> Path.of(k) to v.jsonPrimitive.content }.toMap()
    fun trees(p: JsonObject) = p.getValue("asts").jsonObject.map { (k, v) -> Path.of(k) to v.jsonObject }.toMap()
    val ordinary = phase("ordinary-family-rename"); val tags = phase("tag-family-rename"); val aliases = phase("typedef-family-rename")
    val fieldInputs = Json.parseToJsonElement(resource("/header-field-rename/fixture-inputs.json")).jsonObject
    val fieldSources = fieldInputs.getValue("sources").jsonObject.getValue("before").jsonObject.map { (k, v) -> Path.of(k) to v.jsonPrimitive.content }.toMap()
    val fieldTrees = listOf("read_meter", "main").associate { Path.of("src/$it.c") to Json.parseToJsonElement(resource("/header-field-rename/before-$it-ast.json")).jsonObject }
    val single = Path.of("fixture.c")
    val localSources = mapOf(single to resource("/variable-rename/fixture.c"))
    val localTrees = mapOf(single to Json.parseToJsonElement(resource("/variable-rename/fixture-ast.json")).jsonObject)
    val header = Path.of("include/api.h"); val api = Path.of("src/api.c"); val main = Path.of("src/main.c")
    fun selected(ast: Map<Path, JsonObject>, text: Map<Path, String>, name: String, kind: CCompilerDeclarationKind,
        file: Path = header, tu: Path = ast.keys.sortedBy { it.toString() }.first(), line: Int? = null) =
        CCompilerDeclarationIndex.analyze(ast, text).shouldBeInstanceOf<CCompilerDeclarationReadout.Found>().index.declarations.single {
            it.name == name && it.kind == kind && it.file == file && it.translationUnit == tu && (line == null || it.range.start.line == line)
        }
    fun facts(ast: Map<Path, JsonObject>, text: Map<Path, String>, name: String, kind: CCompilerDeclarationKind,
        file: Path = header, tu: Path = ast.keys.sortedBy { it.toString() }.first(), line: Int? = null): CCompilerNavigationFacts =
        CCompilerNavigationFactsDecoder.decode(ast, text, selected(ast, text, name, kind, file, tu, line).id)
            .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
    fun rewrite(v: JsonElement, change: (JsonObject) -> JsonObject): JsonElement = when (v) {
        is JsonObject -> change(JsonObject(v.mapValues { rewrite(it.value, change) }))
        is JsonArray -> JsonArray(v.map { rewrite(it, change) })
        else -> v
    }

    "header function prototypes resolve to their source definition and only binding-matched calls" {
        val f = facts(trees(ordinary), sources(ordinary), "calculate", CCompilerDeclarationKind.FUNCTION)
        f.definitions.single().file shouldBe api; f.declarations.size shouldBe 2
        f.references.single().file shouldBe main; f.references.single().range.start.line shouldBe 11
    }
    "source function definitions retain the same shared-header family" {
        val f = facts(trees(ordinary), sources(ordinary), "calculate", CCompilerDeclarationKind.FUNCTION, api, api)
        f.definitions.single().file shouldBe api; f.references.size shouldBe 1
    }
    "global object declaration and definition do not absorb local or member homonyms" {
        val f = facts(trees(ordinary), sources(ordinary), "item_count", CCompilerDeclarationKind.VARIABLE)
        f.definitions.single().file shouldBe api; f.references.size shouldBe 2
        f.references.map { it.file }.toSet() shouldBe setOf(api, main)
    }
    "enumerators exclude same-spelled local values" {
        val f = facts(trees(ordinary), sources(ordinary), "ORDER_PENDING", CCompilerDeclarationKind.ENUMERATOR)
        f.definitions.single().file shouldBe header; f.references.size shouldBe 2
    }
    for ((name, kind) in listOf("meter" to CCompilerDeclarationKind.STRUCT, "payload" to CCompilerDeclarationKind.UNION, "stage" to CCompilerDeclarationKind.ENUM)) {
        "$kind tag navigation reuses native type-use authority rather than ordinary spellings" {
            val f = facts(trees(tags), sources(tags), name, kind)
            f.definitions.single().file shouldBe header; f.references.size shouldBe 3
            f.references.map { it.file }.toSet() shouldBe setOf(main)
            f.definitions.single().translationUnits shouldBe setOf(api, main)
        }
    }
    "typedef navigation preserves tag member label and ordinary homonyms" {
        val f = facts(trees(aliases), sources(aliases), "value_t", CCompilerDeclarationKind.TYPEDEF)
        f.definitions.single().file shouldBe header; f.references.size shouldBe 12
    }
    "named struct members include source-owned designators and member accesses" {
        val f = facts(fieldTrees, fieldSources, "raw_count", CCompilerDeclarationKind.FIELD, line = 1)
        f.definitions.single().file shouldBe header; f.references.size shouldBe 2
        f.references.map { it.file }.toSet() shouldBe setOf(Path.of("src/read_meter.c"), main)
    }
    "source parameter references preserve nested local shadowing" {
        val f = facts(localTrees, localSources, "value", CCompilerDeclarationKind.PARAMETER, single, single)
        f.references.size shouldBe 2; f.definitions.single().range.start.line shouldBe 1
        f.references.map { it.range.start.line }.toSet() shouldBe setOf(2, 4)
    }
    "a nested local value does not absorb the parameter global or main variable" {
        val f = facts(localTrees, localSources, "value", CCompilerDeclarationKind.VARIABLE, single, single, 3)
        f.references.size shouldBe 1; f.references.single().range.start.line shouldBe 3
    }
    "internal objects and source functions keep exact native IDs" {
        facts(localTrees, localSources, "value", CCompilerDeclarationKind.VARIABLE, single, single, 0).references.single().range.start.line shouldBe 6
        facts(localTrees, localSources, "calculate", CCompilerDeclarationKind.FUNCTION, single, single).references.single().range.start.line shouldBe 7
    }
    "label navigation excludes its same-spelled typedef tag member and value" {
        val f = facts(trees(aliases), sources(aliases), "value_t", CCompilerDeclarationKind.LABEL, main, main)
        f.definitions.single().file shouldBe main; f.references.size shouldBe 1
        f.references.single().range.start.line shouldBe 8
    }
    "an existing unused declaration is distinct from an unknown symbol ID" {
        facts(localTrees, localSources, "main", CCompilerDeclarationKind.FUNCTION, single, single).references shouldBe emptyList()
        CCompilerNavigationFactsDecoder.decode(localTrees, localSources, "c-decl:" + "0".repeat(64)) shouldBe CCompilerNavigationReadout.NotFound
    }
    "missing native contexts are unavailable before unknown-symbol handling" {
        val selected = selected(trees(ordinary), sources(ordinary), "calculate", CCompilerDeclarationKind.FUNCTION)
        CCompilerNavigationFactsDecoder.decode(trees(ordinary).filterKeys { it != main }, sources(ordinary), selected.id)
            .shouldBeInstanceOf<CCompilerNavigationReadout.Refused>().reason shouldBe CCompilerNavigationRefusal.PROJECTION_UNAVAILABLE
    }
    "an unbound native reference cannot manufacture an unused function" {
        val changed = localTrees.mapValues { (_, ast) -> rewrite(ast) { n ->
            if (n["kind"]?.jsonPrimitive?.content == "DeclRefExpr") JsonObject(n - "referencedDecl") else n
        }.jsonObject }
        val id = selected(localTrees, localSources, "main", CCompilerDeclarationKind.FUNCTION, single, single).id
        CCompilerNavigationFactsDecoder.decode(changed, localSources, id).shouldBeInstanceOf<CCompilerNavigationReadout.Refused>().reason shouldBe
            CCompilerNavigationRefusal.UNBOUND_REFERENCE
    }
    "published navigation collections and observation contexts are immutable" {
        val f = facts(trees(ordinary), sources(ordinary), "calculate", CCompilerDeclarationKind.FUNCTION)
        shouldThrow<UnsupportedOperationException> { (f.references as MutableList<*>).clear() }
        shouldThrow<UnsupportedOperationException> { (f.definitions.single().translationUnits as MutableSet<*>).clear() }
    }
    "a named union member includes its native designated initializer and member access" {
        val f = facts(trees(tags), sources(tags), "amount", CCompilerDeclarationKind.FIELD)
        f.definitions.single().file shouldBe header; f.references.size shouldBe 2
        f.references.map { it.file }.toSet() shouldBe setOf(main)
    }
    "an unbound union initializer cannot hide a spelled field reference" {
        val changed = trees(tags).mapValues { (_, ast) -> rewrite(ast) { n ->
            if (n["kind"]?.jsonPrimitive?.content == "InitListExpr" && n["field"] is JsonObject)
                JsonObject(n + ("field" to JsonObject(n.getValue("field").jsonObject + ("id" to JsonPrimitive("unbound-field"))))) else n
        }.jsonObject }
        val id = selected(trees(tags), sources(tags), "amount", CCompilerDeclarationKind.FIELD).id
        CCompilerNavigationFactsDecoder.decode(changed, sources(tags), id).shouldBeInstanceOf<CCompilerNavigationReadout.Refused>().reason shouldBe
            CCompilerNavigationRefusal.UNBOUND_REFERENCE
    }
    for (linkage in listOf("external", "internal")) {
        "block-scope extern navigates to its $linkage definition without inventing a local object" {
            val fixture = Json.parseToJsonElement(resource("/navigation-extern/native-fixture.json")).jsonObject
            for (record in fixture.getValue("records").jsonArray.map { it.jsonObject }.filter { it.getValue("linkage").jsonPrimitive.content == linkage }) {
                val text = mapOf(single to record.getValue("source").jsonPrimitive.content)
                val ast = mapOf(single to record.getValue("ast").jsonObject)
                val f = facts(ast, text, "value", CCompilerDeclarationKind.VARIABLE, single, single, 1)
                f.definitions.single().range.start.line shouldBe 0; f.declarations.size shouldBe 2; f.references.size shouldBe 1
                f.selected.linkage shouldBe if (linkage == "external") CCompilerLinkage.EXTERNAL else CCompilerLinkage.INTERNAL
            }
        }
    }

    val sourceTypes = Json.parseToJsonElement(resource("/source-type-navigation/native-fixture.json"))
        .jsonObject.getValue("modes").jsonObject
    val sourceCells = listOf(
        Triple("c11", "meter", CCompilerDeclarationKind.STRUCT),
        Triple("c17", "meter", CCompilerDeclarationKind.STRUCT),
        Triple("c11", "payload", CCompilerDeclarationKind.UNION),
        Triple("c17", "phase", CCompilerDeclarationKind.ENUM),
        Triple("c11", "scalar_t", CCompilerDeclarationKind.TYPEDEF),
        Triple("c17", "scalar_t", CCompilerDeclarationKind.TYPEDEF),
    )
    sourceCells.forEach { (mode, name, kind) ->
        "REQ-C04-SOURCE-TYPE-FACTS-001 $mode $kind source family excludes the other TU homonym" {
            val input = sourceTypes.getValue(mode).jsonObject
            val ast = trees(input); val text = sources(input)
            val first = selected(ast, text, name, kind, api, api)
            val other = selected(ast, text, name, kind, main, main)
            (first.id == other.id) shouldBe false
            listOf(first to api, other to main).forEach { (declaration, file) ->
                val f = CCompilerNavigationFactsDecoder.decode(ast, text, declaration.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                f.definitions.single().file shouldBe file
                f.definitions.single().range shouldBe declaration.range
                f.definitions.single().translationUnits shouldBe setOf(file)
                f.references.size shouldBe 1
                f.references.single().file shouldBe file
                f.references.single().translationUnits shouldBe setOf(file)
                val range = f.references.single().range
                text.getValue(file).lines()[range.start.line].substring(range.start.character, range.end.character) shouldBe name
            }
            // New read admission must not silently enable the existing header rename API.
            val offset = org.refactorkit.core.TextEdits.offsetOf(text.getValue(api), first.range.start)
            if (kind == CCompilerDeclarationKind.TYPEDEF)
                CTypedefRenameFactsDecoder.decode(ast, text, api, offset, ast.keys)
                    .shouldBeInstanceOf<CTypedefReadout.Refused>().reason shouldBe CTypedefFactsRefusal.SELECTION_UNAVAILABLE
            else CTagRenameFactsDecoder.decode(ast, text, api, offset, ast.keys)
                .shouldBeInstanceOf<CTagReadout.Refused>().reason shouldBe CTagFactsRefusal.SELECTION_UNAVAILABLE
        }
    }


    val sourceMemberCells = listOf(
        Triple("c11", "member", api to 2), Triple("c17", "member", api to 2),
        Triple("c11", "amount", api to 3), Triple("c17", "amount", api to 3),
        Triple("c11", "other", main to 2), Triple("c17", "other", main to 3),
    )
    sourceMemberCells.forEach { (mode, name, point) ->
        "REQ-C04-SOURCE-MEMBER-FACTS-001 $mode $name line ${point.second} source members retain record identity" {
            val input = sourceTypes.getValue(mode).jsonObject
            val ast = trees(input); val text = sources(input); val file = point.first
            val declaration = selected(ast, text, name, CCompilerDeclarationKind.FIELD, file, file, point.second)
            val f = CCompilerNavigationFactsDecoder.decode(ast, text, declaration.id)
                .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
            f.definitions.single().file shouldBe file
            f.definitions.single().range shouldBe declaration.range
            f.definitions.single().translationUnits shouldBe setOf(file)
            f.references.size shouldBe 2
            f.references.forEach { use ->
                use.file shouldBe file; use.translationUnits shouldBe setOf(file)
                text.getValue(file).lines()[use.range.start.line].substring(use.range.start.character, use.range.end.character) shouldBe name
            }
            if (name == "other") {
                val other = selected(ast, text, name, CCompilerDeclarationKind.FIELD, file, file,
                    if (point.second == 2) 3 else 2)
                (declaration.id == other.id) shouldBe false
                val peer = CCompilerNavigationFactsDecoder.decode(ast, text, other.id)
                    .shouldBeInstanceOf<CCompilerNavigationReadout.Found>().facts
                peer.references.size shouldBe 2
                f.references.map { it.range }.toSet().intersect(peer.references.map { it.range }.toSet()) shouldBe emptySet()
            }
            val offset = org.refactorkit.core.TextEdits.offsetOf(text.getValue(file), declaration.range.start)
            CHeaderFieldRenameFactsDecoder.decode(ast, text, file, offset, ast.keys)
                .shouldBeInstanceOf<CHeaderFieldReadout.Refused>()
        }
    }

})

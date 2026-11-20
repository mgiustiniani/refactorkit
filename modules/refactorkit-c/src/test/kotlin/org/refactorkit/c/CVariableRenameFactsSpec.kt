package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path
import java.security.MessageDigest

/** REQ-C-SEMANTIC-RENAME-001; binding facts for the planned variable/parameter targets. */
class CVariableRenameFactsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CVariableRenameFactsSpec::class.java
        .getResource("/variable-rename/$name")).readText()
    val source = resource("fixture.c")
    val bytes = source.toByteArray(Charsets.UTF_8)
    val ast = Json.parseToJsonElement(resource("fixture-ast.json")) as JsonObject
    fun at(line: Int, text: String, last: Boolean = false): CFunctionByteSpan {
        val lines = source.lines()
        val column = if (last) lines[line].lastIndexOf(text) else lines[line].indexOf(text)
        require(column >= 0)
        val start = lines.take(line).sumOf { it.length + 1 } + column
        return CFunctionByteSpan(start, start + text.length)
    }
    fun decode(selected: CFunctionByteSpan, tree: JsonObject = ast, material: ByteArray = bytes, inside: Int = 0) =
        CVariableRenameFactsDecoder.decode(tree, Path.of("fixture.c"), material, selected.start + inside)
    fun rewrite(value: JsonElement, change: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> change(JsonObject(value.mapValues { (_, nested) -> rewrite(nested, change) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, change) })
        else -> value
    }

    "parameter identity includes its own uses and excludes nested shadowing" {
        val selected = at(1, "value")
        val facts = decode(selected, inside = 2).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        facts.kind shouldBe CVariableRenameKind.PARAMETER
        facts.declaration shouldBe selected
        facts.references shouldBe listOf(at(2, "value"), at(4, "value"))
        facts.type shouldBe "int"
        facts.name shouldBe "value"
        facts.sourcePath shouldBe Path.of("fixture.c")
        facts.sourceSha256 shouldBe MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        runCatching { (facts.references as MutableList).clear() }.isFailure shouldBe true
        runCatching { (facts.referenceAstPaths as MutableList).clear() }.isFailure shouldBe true
    }

    "a selected local excludes both the outer parameter and the other function local" {
        val facts = decode(at(3, "value")).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        facts.kind shouldBe CVariableRenameKind.LOCAL
        facts.references shouldBe listOf(at(3, "value", last = true))
        val main = decode(at(7, "value")).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        main.kind shouldBe CVariableRenameKind.LOCAL
        main.references shouldBe listOf(at(7, "value", last = true))
        (facts.scopeAstPath != main.scopeAstPath) shouldBe true
    }

    "an internal file object has a closed source binding while external linkage refuses" {
        val selected = at(0, "value")
        val facts = decode(selected).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        facts.kind shouldBe CVariableRenameKind.INTERNAL_OBJECT
        facts.references shouldBe listOf(at(6, "value"))
        val external = rewrite(ast) { node ->
            if ((node["kind"] as? JsonPrimitive)?.content == "VarDecl" &&
                (node["storageClass"] as? JsonPrimitive)?.content == "static") JsonObject(node - "storageClass") else node
        } as JsonObject
        decode(selected, external) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.EXTERNAL_LINKAGE)
    }

    "an extern declaration inside a function does not become a closed local object" {
        val selected = at(7, "value")
        val externalLocal = rewrite(ast) { node ->
            val location = node["loc"] as? JsonObject
            if ((node["kind"] as? JsonPrimitive)?.content == "VarDecl" &&
                (location?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull() == selected.start)
                JsonObject(node + ("storageClass" to JsonPrimitive("extern"))) else node
        } as JsonObject
        decode(selected, externalLocal) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.EXTERNAL_LINKAGE)
    }

    "a second declaration linked to the selected object cannot be hidden as a separate family" {
        val selected = at(0, "value")
        val global = (ast["inner"] as JsonArray).filterIsInstance<JsonObject>().single { node ->
            (node["kind"] as? JsonPrimitive)?.content == "VarDecl" && (node["storageClass"] as? JsonPrimitive)?.content == "static"
        }
        val redeclared = rewrite(ast) { node ->
            val location = node["loc"] as? JsonObject
            if ((node["kind"] as? JsonPrimitive)?.content == "VarDecl" &&
                (location?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull() == at(7, "value").start)
                JsonObject(node + ("previousDecl" to requireNotNull(global["id"]))) else node
        } as JsonObject
        decode(selected, redeclared) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.UNSUPPORTED_DECLARATION)
    }

    "transient compiler ids do not define persisted identity and unbound uses never become zero references" {
        val selected = at(1, "value")
        val original = decode(selected).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        fun rebase(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.mapValues { (_, child) -> rebase(child) })
            is JsonArray -> JsonArray(value.map(::rebase))
            is JsonPrimitive -> if (value.isString && value.content.startsWith("0x")) JsonPrimitive("rebased-${value.content}") else value
            else -> value
        }
        val rebased = decode(selected, rebase(ast) as JsonObject).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        rebased.declarationAstPath shouldBe original.declarationAstPath
        rebased.scopeAstPath shouldBe original.scopeAstPath
        rebased.references shouldBe original.references
        rebased.referenceAstPaths shouldBe original.referenceAstPaths
        val broken = rewrite(ast) { node ->
            if ((node["kind"] as? JsonPrimitive)?.content == "DeclRefExpr") {
                val reference = node["referencedDecl"] as JsonObject
                JsonObject(node + ("referencedDecl" to JsonObject(reference + ("id" to JsonPrimitive("missing-native-id")))))
            } else node
        } as JsonObject
        decode(selected, broken) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.UNBOUND_REFERENCE)
    }

    "macro foreign and contradictory declaration evidence cannot select an owned binding" {
        val selected = at(1, "value")
        fun changedLocation(replacement: JsonObject) = rewrite(ast) { node ->
            val location = node["loc"] as? JsonObject
            if ((location?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull() == selected.start)
                JsonObject(node + ("loc" to replacement)) else node
        } as JsonObject
        val coordinate = JsonObject(mapOf("offset" to JsonPrimitive(selected.start), "tokLen" to JsonPrimitive(5)))
        decode(selected, changedLocation(JsonObject(mapOf("spellingLoc" to coordinate, "expansionLoc" to coordinate)))) shouldBe
            CVariableRenameReadout.Refused(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        decode(selected, changedLocation(JsonObject(coordinate + ("file" to JsonPrimitive("foreign.h"))))) shouldBe
            CVariableRenameReadout.Refused(CVariableRenameRefusal.UNOWNED_OR_MACRO_RANGE)
        val duplicated = JsonObject(ast + ("inner" to JsonArray((ast["inner"] as JsonArray).let { it + it.last() })))
        decode(selected, duplicated) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.INVALID_AST)
    }

    "inline assembly and linkage aliases cannot masquerade as a closed variable reference family" {
        val assembly = "static int value = 4;\nint main(void) { __asm__ volatile (\"nop\"); return value; }\n"
        val tree = Json.parseToJsonElement(resource("asm-fixture-ast.json")) as JsonObject
        decode(at(0, "value"), tree, assembly.toByteArray()) shouldBe
            CVariableRenameReadout.Refused(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        for (kind in listOf("MSAsmStmt", "FileScopeAsmDecl", "AsmLabelAttr", "AliasAttr", "IFuncAttr")) {
            val tagged = JsonObject(ast + ("inner" to JsonArray((ast["inner"] as JsonArray) + JsonObject(mapOf("kind" to JsonPrimitive(kind))))))
            decode(at(0, "value"), tagged) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        }
    }

    "staged alpha rename retains the exact declaration scope type and binding-matched reference paths" {
        val original = decode(at(1, "value")).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        val afterSource = resource("after-fixture.c").toByteArray()
        val afterTree = Json.parseToJsonElement(resource("after-fixture-ast.json")) as JsonObject
        val candidate = decode(at(1, "value"), afterTree, afterSource).shouldBeInstanceOf<CVariableRenameReadout.Found>().facts
        CVariableRenameBinding.check(original, candidate, "renamed") shouldBe CVariableRenameTransition.Verified
        fun altered(type: String = candidate.type, scope: String = candidate.scopeAstPath,
            references: List<CFunctionByteSpan> = candidate.references, paths: List<String> = candidate.referenceAstPaths) =
            CVariableRenameFacts(candidate.sourcePath, candidate.sourceSha256, candidate.name, type, candidate.kind,
                candidate.declaration, candidate.declarationAstPath, scope, references, paths)
        CVariableRenameBinding.check(original, altered(type = "long"), "renamed") shouldBe
            CVariableRenameTransition.Refused(CVariableRenameTransitionRefusal.DECLARATION_IDENTITY)
        CVariableRenameBinding.check(original, altered(scope = "TU/other"), "renamed") shouldBe
            CVariableRenameTransition.Refused(CVariableRenameTransitionRefusal.DECLARATION_IDENTITY)
        CVariableRenameBinding.check(original, altered(paths = candidate.referenceAstPaths.dropLast(1)), "renamed") shouldBe
            CVariableRenameTransition.Refused(CVariableRenameTransitionRefusal.REFERENCE_FAMILY)
        CVariableRenameBinding.check(original, altered(references = candidate.references.map { it.copy(start = it.start + 1) }), "renamed") shouldBe
            CVariableRenameTransition.Refused(CVariableRenameTransitionRefusal.REFERENCE_RANGES)
        CVariableRenameBinding.check(original, candidate, "value") shouldBe
            CVariableRenameTransition.Refused(CVariableRenameTransitionRefusal.REPLACEMENT_NAME)
    }

    "missing selection unsupported source and bounded input exhaustion remain typed refusals" {
        decode(at(1, "calculate")) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.SELECTION_UNAVAILABLE)
        decode(at(1, "value"), tree = JsonObject(emptyMap())) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.INVALID_AST)
        decode(at(1, "value"), material = ByteArray(1_048_577)) shouldBe CVariableRenameReadout.Refused(CVariableRenameRefusal.INPUT_LIMIT)
        decode(at(1, "value"), material = "#define X 1\n$source".toByteArray()) shouldBe
            CVariableRenameReadout.Refused(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
        decode(at(1, "value"), material = byteArrayOf(0xc3.toByte(), 0x28)) shouldBe
            CVariableRenameReadout.Refused(CVariableRenameRefusal.UNSUPPORTED_SOURCE)
    }
})

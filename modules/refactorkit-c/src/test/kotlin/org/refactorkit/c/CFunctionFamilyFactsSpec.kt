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

/** REQ-C-FUNCTION-FACTS-001; captured local AST, Kotlin Spec BDD. */
class CFunctionFamilyFactsSpec : StringSpec({
    val source = requireNotNull(CFunctionFamilyFactsSpec::class.java.getResource("/function-family/fixture.c")).readText()
    val original = Json.parseToJsonElement(requireNotNull(CFunctionFamilyFactsSpec::class.java.getResource("/function-family/fixture-ast.json")).readText()) as JsonObject
    fun decode(ast: JsonObject = original, name: String = "weighted") =
        CFunctionFamilyFactsDecoder.decode(ast, Path.of("fixture.c"), source.toByteArray(Charsets.UTF_8), name)
    fun slice(span: CFunctionByteSpan) = source.toByteArray().copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    fun kind(node: JsonObject) = (node["kind"] as? JsonPrimitive)?.content
    fun rewrite(element: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (element) {
        is JsonObject -> transform(JsonObject(element.mapValues { (_, value) -> rewrite(value, transform) }))
        is JsonArray -> JsonArray(element.map { rewrite(it, transform) })
        else -> element
    }

    "prototype and definition share identity while unrelated same-name variable uses stay outside the call family" {
        val facts = decode().shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
        facts.family shouldBe CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC
        facts.declarations.map { it.parameters.map { parameter -> slice(parameter.span) } } shouldBe
            listOf(listOf("int x", "int y", "int z"), listOf("int a", "int b", "int c"))
        facts.declarations.map { it.isDefinition } shouldBe listOf(false, true)
        facts.calls.map { it.arguments.map { argument -> slice(argument.span) } } shouldBe
            listOf(listOf("a", "8", "7"), listOf("1", "2", "3"))
        facts.calls.flatMap { it.arguments }.map { it.effect }.toSet() shouldBe setOf(CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT)
        facts.sourceSha256.length shouldBe 64
    }

    "missing ancestor and same-name unconnected declarations refuse instead of becoming partial families" {
        val missing = rewrite(original) { node ->
            if (node.containsKey("previousDecl")) JsonObject(node + ("previousDecl" to JsonPrimitive("missing-identity"))) else node
        } as JsonObject
        decode(missing) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
        val unlinked = rewrite(original) { node ->
            if (node.containsKey("previousDecl")) JsonObject(node - "previousDecl") else node
        } as JsonObject
        decode(unlinked) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.AMBIGUOUS_FAMILY)
    }

    "both earlier and later declaration identities bind direct calls without relying on spelling" {
        val declarations = (original["inner"] as JsonArray).filterIsInstance<JsonObject>().filter { kind(it) == "FunctionDecl" }
        val first = declarations.first()["id"]!!
        val ast = rewrite(original) { node ->
            if (kind(node) == "DeclRefExpr" && (node["referencedDecl"] as? JsonObject)?.get("kind") == JsonPrimitive("FunctionDecl")) {
                JsonObject(node + ("referencedDecl" to JsonObject((node["referencedDecl"] as JsonObject) + ("id" to first))))
            } else node
        } as JsonObject
        decode(ast).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.calls.size shouldBe 2
    }

    "a noncallee function reference and external linkage keep their separate family uncertainty" {
        var reference: JsonObject? = null
        rewrite(original) { node ->
            if (kind(node) == "DeclRefExpr" && (node["referencedDecl"] as? JsonObject)?.get("kind") == JsonPrimitive("FunctionDecl")) reference = node
            node
        }
        val extra = JsonObject(requireNotNull(reference) + ("id" to JsonPrimitive("extra-address-reference")))
        val address = JsonObject(original + ("inner" to JsonArray((original["inner"] as JsonArray) + extra)))
        decode(address).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.family shouldBe CDirectCallFamilyEvidence.ADDRESS_TAKEN
        val external = rewrite(original) { node ->
            if (kind(node) == "FunctionDecl" && node["name"] == JsonPrimitive("weighted")) JsonObject(node - "storageClass") else node
        } as JsonObject
        decode(external).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.family shouldBe CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS
    }

    "observable and unsupported argument forms retain effects instead of inheriting a clean call" {
        fun changed(newKind: String, opcode: String? = null): JsonObject = rewrite(original) { node ->
            if (kind(node) == "IntegerLiteral" && node["value"] == JsonPrimitive("8")) {
                JsonObject(node + ("kind" to JsonPrimitive(newKind)) + if (opcode == null) emptyMap() else mapOf("opcode" to JsonPrimitive(opcode)))
            } else node
        } as JsonObject
        decode(changed("UnaryOperator", "++")).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.calls.first().arguments[1].effect shouldBe CArgumentEffectEvidence.OBSERVABLE_EFFECT
        decode(changed("FutureUnknownExpression")).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.calls.first().arguments[1].effect shouldBe CArgumentEffectEvidence.UNAVAILABLE
    }

    "macro quoted numeric and out-of-source coordinates refuse without source guessing" {
        val base = (original["inner"] as JsonArray).filterIsInstance<JsonObject>().first { kind(it) == "FunctionDecl" }
        val cases = listOf(JsonObject(mapOf("offset" to JsonPrimitive("11"), "tokLen" to JsonPrimitive(8))),
            JsonObject(mapOf("offset" to JsonPrimitive(source.length + 10), "tokLen" to JsonPrimitive(8))),
            JsonObject(mapOf("spellingLoc" to base["loc"]!!, "expansionLoc" to base["loc"]!!)))
        cases.forEach { location ->
            val ast = rewrite(original) { node -> if (node["id"] == base["id"]) JsonObject(node + ("loc" to location)) else node } as JsonObject
            decode(ast) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
        }
    }

    "absent functions and invalid dialect families have explicit different dispositions" {
        decode(name = "missing") shouldBe CFunctionFamilyFactsReadout.NotFound
        val variadic = rewrite(original) { node ->
            if (kind(node) == "FunctionDecl" && node["name"] == JsonPrimitive("weighted")) JsonObject(node + ("variadic" to JsonPrimitive(true))) else node
        } as JsonObject
        decode(variadic) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
    }

    "captured declaration and call collections cannot be mutated into different evidence" {
        val facts = decode().shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
        runCatching { (facts.declarations as MutableList<*>).clear() }.isFailure shouldBe true
        runCatching { (facts.calls.first().arguments as MutableList<*>).clear() }.isFailure shouldBe true
        facts.declarations.size shouldBe 2
        facts.calls.size shouldBe 2
    }

    "cyclic declaration ancestry and duplicate declarations cannot claim a closed family" {
        val cyclic = rewrite(original) { node ->
            if (node.containsKey("previousDecl")) JsonObject(node + ("previousDecl" to node["id"]!!)) else node
        } as JsonObject
        decode(cyclic) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
        val first = (original["inner"] as JsonArray).filterIsInstance<JsonObject>().first { kind(it) == "FunctionDecl" }
        val duplicate = JsonObject(original + ("inner" to JsonArray((original["inner"] as JsonArray) + first)))
        decode(duplicate) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INCOMPLETE_FAMILY)
    }

    "included source coordinates are not silently attributed to the owned source" {
        val foreign = rewrite(original) { node ->
            if (kind(node) == "FunctionDecl" && node["name"] == JsonPrimitive("weighted") && node.containsKey("loc")) {
                JsonObject(node + ("loc" to JsonObject((node["loc"] as JsonObject) + ("file" to JsonPrimitive("other.h")))))
            } else node
        } as JsonObject
        decode(foreign) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.UNOWNED_OR_MACRO_RANGE)
    }

    "excessive depth and node count remain unavailable rather than a partial analysis" {
        var deep: JsonObject = JsonObject(mapOf("kind" to JsonPrimitive("UnknownNode")))
        repeat(258) { deep = JsonObject(mapOf("kind" to JsonPrimitive("UnknownNode"), "inner" to JsonArray(listOf(deep)))) }
        decode(JsonObject(original + ("inner" to JsonArray(listOf(deep))))) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INPUT_LIMIT)
        val many = JsonArray(List(100_001) { JsonObject(mapOf("kind" to JsonPrimitive("UnknownNode"))) })
        decode(JsonObject(original + ("inner" to many))) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.INPUT_LIMIT)
    }

    "assembly and symbolic linker aliases cannot inherit a closed source-only call family" {
        listOf("GCCAsmStmt", "MSAsmStmt", "AliasAttr", "AsmLabelAttr", "IFuncAttr").forEach { kind ->
            val evidence = JsonObject(mapOf("kind" to JsonPrimitive(kind)))
            val ast = JsonObject(original + ("inner" to JsonArray((original["inner"] as JsonArray) + evidence)))
            decode(ast).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.family shouldBe CDirectCallFamilyEvidence.UNAVAILABLE
        }
    }

    "an unbound indirect call cannot be excluded from the selected family's possible consumers" {
        val callee = JsonObject(mapOf("kind" to JsonPrimitive("DeclRefExpr"), "id" to JsonPrimitive("indirect-reference"),
            "referencedDecl" to JsonObject(mapOf("id" to JsonPrimitive("pointer-variable"), "kind" to JsonPrimitive("VarDecl")))))
        val evidence = JsonObject(mapOf("kind" to JsonPrimitive("CallExpr"), "inner" to JsonArray(listOf(callee))))
        val ast = JsonObject(original + ("inner" to JsonArray((original["inner"] as JsonArray) + evidence)))
        decode(ast).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts.family shouldBe CDirectCallFamilyEvidence.UNAVAILABLE
    }
})

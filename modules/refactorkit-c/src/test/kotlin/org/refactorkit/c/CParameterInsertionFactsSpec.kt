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

/** REQ-C-PARAMETER-INSERTION-FACTS-001; pure decoder, approved Kotest. */
class CParameterInsertionFactsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CParameterInsertionFactsSpec::class.java.getResource("/function-family/$name")).readText()
    fun captured(stem: String, function: String): CFunctionFamilyFacts = CFunctionFamilyFactsDecoder.decode(
        Json.parseToJsonElement(resource("$stem-ast.json")) as JsonObject, Path.of("$stem.c"), resource("$stem.c").toByteArray(), function,
    ).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
    fun slice(source: String, span: CFunctionByteSpan) = source.toByteArray().copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    fun rewrite(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (key == "typeAliasDeclId") JsonPrimitive("missing-typedef") else rewrite(value) })
        is JsonArray -> JsonArray(element.map(::rewrite))
        else -> element
    }

    "nonempty clauses belong to each selected declaration and preserve independent prototype names" {
        val facts = captured("fixture", "weighted")
        facts.declarations.map { slice(resource("fixture.c"), it.parameterClause.shouldBeInstanceOf<CFunctionParameterClauseEvidence.NonEmpty>().span) } shouldBe
            listOf("int x, int y, int z", "int a, int b, int c")
    }

    "explicit void clauses replace only the compiler-owned token and preserve comments" {
        val facts = captured("insertion", "answer")
        facts.declarations.map { slice(resource("insertion.c"), it.parameterClause.shouldBeInstanceOf<CFunctionParameterClauseEvidence.ExplicitVoid>().span) } shouldBe listOf("void", "void")
        facts.calls.single().arguments shouldBe emptyList()
    }

    "scope names contain parameter bindings and value uses but no unrelated local names" {
        val facts = captured("fixture", "weighted")
        facts.declarations.map { it.parameterScope.shouldBeInstanceOf<CFunctionParameterScopeEvidence.Available>().ordinaryNames } shouldBe
            listOf(setOf("x", "y", "z"), setOf("a", "b", "c"))
    }

    "body references include globals local declarations and typedef identities including sizeof type uses" {
        val facts = captured("insertion", "answer")
        facts.declarations.first().parameterScope.shouldBeInstanceOf<CFunctionParameterScopeEvidence.Available>().ordinaryNames shouldBe emptySet()
        facts.declarations.last().parameterScope.shouldBeInstanceOf<CFunctionParameterScopeEvidence.Available>().ordinaryNames shouldBe setOf("Width", "value", "seed")
    }

    "a missing referenced typedef identity retains unavailable scope evidence" {
        val source = resource("insertion.c")
        val ast = rewrite(Json.parseToJsonElement(resource("insertion-ast.json"))) as JsonObject
        val facts = CFunctionFamilyFactsDecoder.decode(ast, Path.of("insertion.c"), source.toByteArray(), "answer")
            .shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
        facts.declarations.last().parameterScope shouldBe CFunctionParameterScopeEvidence.Unavailable
    }

    "available namespace collections cannot change after compiler decoding" {
        val names = captured("insertion", "answer").declarations.last().parameterScope
            .shouldBeInstanceOf<CFunctionParameterScopeEvidence.Available>().ordinaryNames
        runCatching { (names as MutableSet<String>).add("surprise") }.isFailure shouldBe true
        names shouldBe setOf("Width", "value", "seed")
    }
})

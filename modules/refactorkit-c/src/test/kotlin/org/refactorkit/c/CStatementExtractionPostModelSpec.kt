package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path

/** REQ-C-STATEMENT-POST-MODEL-001; pure validation of supplied native models. */
class CStatementExtractionPostModelSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CStatementExtractionPostModelSpec::class.java.getResource("/statement-extraction/$name")).readText()
    val source = resource("fixture.c")
    val candidate = resource("fixture-after.c")
    fun capture(text: String, json: String, function: String): CStatementExtractionFacts {
        val start = text.indexOf("int sum =", text.indexOf("static int $function(int a"))
        val end = text.indexOf(';', text.indexOf("int doubled =", start)) + 1
        return CStatementExtractionFactsDecoder.decode(Json.parseToJsonElement(resource(json)) as JsonObject, Path.of("fixture.c"), text.toByteArray(), function,
            CFunctionByteSpan(start, end)).shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
    }
    val before = capture(source, "fixture-ast.json", "calculate")
    val after = capture(candidate, "fixture-after-ast.json", "computeTotal")
    val family = CFunctionFamilyFactsDecoder.decode(Json.parseToJsonElement(resource("fixture-after-ast.json")) as JsonObject,
        Path.of("fixture.c"), candidate.toByteArray(), "computeTotal").shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
    fun validate(text: String = candidate, facts: CStatementExtractionFacts = after, calls: CFunctionFamilyFacts = family) =
        CStatementExtractionEdits.validateCandidate(source, before, text, facts, calls, "computeTotal")
    val refused = CStatementExtractionValidation.Refused(CStatementExtractionEditRefusal.INVALID_CANDIDATE)

    "independently captured helper statements and binding-matched caller validate the exact candidate" {
        validate() shouldBe CStatementExtractionValidation.Accepted
    }
    "a compiler-valid arithmetic change or extra unrelated edit cannot become equivalent source" {
        validate(candidate.replaceFirst("sum * 2", "sum * 3")) shouldBe refused
        validate(candidate + "int extra = 0;\n") shouldBe refused
    }
    "stale source identity or moved statement selection refuses native post authority" {
        validate(facts = after.copy(sourceSha256 = before.sourceSha256)) shouldBe refused
        validate(facts = after.copy(selection = after.selection.copy(start = after.selection.start + 1))) shouldBe refused
        validate(calls = family.copy(sourceSha256 = before.sourceSha256)) shouldBe refused
    }
    "output or parameter remapping cannot hide behind unchanged compilation" {
        validate(facts = after.copy(outputName = "sum")) shouldBe refused
        validate(facts = after.copy(inputs = after.inputs.reversed())) shouldBe refused
        validate(calls = family.copy(declarations = family.declarations.map { it.copy(parameters = it.parameters.reversed()) })) shouldBe refused
    }
    "missing extra indirect or displaced helper callers cannot satisfy the closed model" {
        val call = family.calls.single()
        listOf(family.copy(calls = emptyList()), family.copy(calls = family.calls + call), family.copy(family = CDirectCallFamilyEvidence.ADDRESS_TAKEN),
            family.copy(calls = listOf(call.copy(span = call.span.copy(endExclusive = call.span.endExclusive - 1))))).forEach { validate(calls = it) shouldBe refused }
    }
    "argument effects and operand source identity stay explicit despite an unchanged helper body" {
        val call = family.calls.single()
        val argument = call.arguments.first()
        validate(calls = family.copy(calls = listOf(call.copy(arguments = listOf(argument.copy(effect = CArgumentEffectEvidence.OBSERVABLE_EFFECT)) + call.arguments.drop(1))))) shouldBe refused
        validate(calls = family.copy(calls = listOf(call.copy(arguments = call.arguments.reversed())))) shouldBe refused
        validate(facts = after.copy(localNames = after.localNames.reversed())) shouldBe refused
    }
})

package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** REQ-C-FUNCTION-FACTS-001: native scalar types do not invent argument-effect authority. */
class CScalarFunctionFamilyFactsSpec : StringSpec({
    val fixtures = GZIPInputStream(requireNotNull(CScalarFunctionFamilyFactsSpec::class.java
        .getResourceAsStream("/function-family/scalar-signatures/native-fixture.json.gz")))
        .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
    for (dialect in listOf("c11", "c17")) for (shape in listOf("int", "long", "double")) {
        "$dialect $shape scalar signatures retain complete typed families and conservative effects" {
            val capture = fixtures.getValue("dialects").jsonObject.getValue(dialect).jsonObject.getValue(shape).jsonObject
            val source = capture.getValue("source").jsonPrimitive.content.toByteArray(Charsets.UTF_8)
            val ast = Json.parseToJsonElement(capture.getValue("astJson").jsonPrimitive.content).jsonObject
            fun decode(value: JsonObject) = CFunctionFamilyFactsDecoder.decode(value, Path.of("fixture.c"), source, "weighted")
            fun slice(span: CFunctionByteSpan) = source.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
            val facts = decode(ast).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
            facts.family shouldBe CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC
            facts.declarations.size shouldBe 2
            facts.declarations.map { it.isDefinition } shouldBe listOf(false, true)
            val expectedParameters = capture.getValue("parameterSlices").jsonArray.map { it.jsonPrimitive.content }
            facts.declarations.map { declaration -> declaration.parameters.map { slice(it.span) } } shouldBe
                listOf(expectedParameters, expectedParameters)
            facts.declarations.all { it.parameterClause is CFunctionParameterClauseEvidence.NonEmpty } shouldBe true
            facts.calls.size shouldBe 1
            facts.calls.single().arguments.map { slice(it.span) } shouldBe
                capture.getValue("argumentSlices").jsonArray.map { it.jsonPrimitive.content }
            val noEffect = CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT
            val unavailable = CArgumentEffectEvidence.UNAVAILABLE
            facts.calls.single().arguments.map { it.effect } shouldBe when (shape) {
                "int" -> listOf(noEffect, noEffect)
                "long" -> listOf(unavailable, noEffect)
                else -> listOf(unavailable, unavailable)
            }
            // A forged compiler chain must not combine individually admitted, incompatible return types.
            val inner = ast.getValue("inner").jsonArray
            val definitionIndex = inner.indexOfLast { element ->
                val node = element.jsonObject
                node["kind"] == JsonPrimitive("FunctionDecl") && node["name"] == JsonPrimitive("weighted")
            }
            val declaration = inner[definitionIndex].jsonObject
            val alternateReturn = if (shape == "int") "long" else "int"
            val parameterTypes = capture.getValue("parameterTypes").jsonArray.joinToString(", ") { it.jsonPrimitive.content }
            val changedType = JsonObject(declaration.getValue("type").jsonObject +
                ("qualType" to JsonPrimitive("$alternateReturn ($parameterTypes)")))
            val forged = JsonObject(ast + ("inner" to JsonArray(inner.mapIndexed { index, element ->
                if (index == definitionIndex) JsonObject(declaration + ("type" to changedType)) else element
            })))
            decode(forged) shouldBe CFunctionFamilyFactsReadout.Refused(CFunctionFactsRefusal.UNSUPPORTED_SIGNATURE)
        }
    }
})

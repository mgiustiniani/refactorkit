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

/** REQ-C-SCALAR-EXPRESSION-FACTS-001; pure specification of supplied native facts. */
class CScalarExpressionFactsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CScalarExpressionFactsSpec::class.java.getResource("/scalar-expression/$name")).readText()
    val source = resource("fixture.c")
    val bytes = source.toByteArray(Charsets.UTF_8)
    val ast = Json.parseToJsonElement(resource("fixture-ast.json")) as JsonObject
    fun selected(function: String, text: String): CFunctionByteSpan {
        val start = source.indexOf(text, source.indexOf("static int $function("))
        require(start >= 0)
        return CFunctionByteSpan(start, start + text.length)
    }
    fun decode(function: String = "converted", text: String = "a / b", tree: JsonObject = ast,
        range: CFunctionByteSpan = selected(function, text), material: ByteArray = bytes) =
        CScalarExpressionFactsDecoder.decode(tree, Path.of("fixture.c"), material, function, range)
    fun fragment(span: CFunctionByteSpan) = bytes.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    fun rewrite(value: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (value) {
        is JsonObject -> transform(JsonObject(value.mapValues { (_, nested) -> rewrite(nested, transform) }))
        is JsonArray -> JsonArray(value.map { rewrite(it, transform) })
        else -> value
    }

    "integer division retains its compiler type when assigned to a double target" {
        val facts = decode().shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        facts.expressionType shouldBe "int"
        facts.assignmentTargetType shouldBe "double"
        fragment(facts.selection) shouldBe "a / b"
        fragment(facts.enclosingStatement) shouldBe "double ratio = a / b;"
        fragment(facts.functionDefinitionSpan) shouldBe "static int converted(int a, int b) {\n    double ratio = a / b;\n    return ratio == 2.0;\n}"
        facts.inputs.map { it.name } shouldBe listOf("a", "b")
        facts.inputs.map { fragment(it.declarationSpan) } shouldBe listOf("int a", "int b")
        facts.sourcePath shouldBe Path.of("fixture.c")
        facts.sourceSha256 shouldBe MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val constant = decode("constantTarget", "a / b").shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        constant.expressionType shouldBe "int"
        constant.assignmentTargetType shouldBe "const double"
    }

    "a complete eager arithmetic subtree retains the owning statement and native inputs" {
        val facts = decode("nested", "a + b").shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        facts.expressionType shouldBe "int"
        facts.assignmentTargetType shouldBe "int"
        fragment(facts.enclosingStatement) shouldBe "int total = (a + b) * 2;"
        facts.inputs.map { it.name } shouldBe listOf("a", "b")
        decode("nested", "2", range = selected("nested", "* 2").let { it.copy(start = it.start + 2) })
            .shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts.inputs shouldBe emptyList()
        facts.ordinaryNames.containsAll(setOf("converted", "nested", "total", "ratio", "a", "b", "seed")) shouldBe true
        runCatching { (facts.inputs as MutableList).clear() }.isFailure shouldBe true
        runCatching { (facts.ordinaryNames as MutableSet).clear() }.isFailure shouldBe true
        val readonly = decode("readonly", "base + b").shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        readonly.inputs.map { it.name } shouldBe listOf("b", "base")
        readonly.inputs.map { fragment(it.declarationSpan) } shouldBe listOf("int b", "const int base = a")
    }

    "partial expression and return contexts cannot become an initializer temporary" {
        val selection = selected("converted", "a / b")
        decode(range = selection.copy(start = selection.start + 1)) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_SELECTION)
        decode("converted", "ratio == 2.0") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_SELECTION)
        decode(range = selection.copy(endExclusive = bytes.size + 1)) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_SELECTION)
    }

    "global and volatile reads refuse the ordinary local input boundary" {
        decode("outside", "seed + b") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
        decode("qualified", "value + b") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
    }

    "writes conditional evaluation and labels refuse pure eager scalar placement" {
        decode("effects", "a++ + b") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNSUPPORTED_EXPRESSION)
        decode("conditional", "a + b") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
        decode("branching", "a + b") shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNSUPPORTED_CONTEXT)
    }

    "unavailable native identity malformed encoding and exhausted input budgets refuse" {
        val broken = rewrite(ast) { node ->
            val referenced = node["referencedDecl"] as? JsonObject
            if ((node["kind"] as? JsonPrimitive)?.content == "DeclRefExpr" && (referenced?.get("name") as? JsonPrimitive)?.content == "a") {
                JsonObject(node + ("referencedDecl" to JsonObject(requireNotNull(referenced) + ("id" to JsonPrimitive("missing-native-id")))))
            } else node
        } as JsonObject
        decode(tree = broken) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.UNAVAILABLE_INPUT)
        decode(material = byteArrayOf(0xc3.toByte(), 0x28)) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INVALID_ENCODING)
        decode(material = ByteArray(1_048_577)) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INPUT_LIMIT)
        val crowded = rewrite(ast) { node ->
            val nested = node["inner"] as? JsonArray
            val firstVariable = (((nested?.firstOrNull() as? JsonObject)?.get("inner") as? JsonArray)?.firstOrNull() as? JsonObject)
            if ((node["kind"] as? JsonPrimitive)?.content == "CompoundStmt" &&
                (firstVariable?.get("name") as? JsonPrimitive)?.content == "ratio") {
                JsonObject(node + ("inner" to JsonArray(List(65) { requireNotNull(nested).first() } + requireNotNull(nested).last())))
            } else node
        } as JsonObject
        decode(tree = crowded) shouldBe CScalarExpressionReadout.Refused(CScalarExpressionRefusal.INPUT_LIMIT)
    }
})

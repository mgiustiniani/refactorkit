package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** REQ-C-SCALAR-EXPRESSION-ORIGIN-001; native owning definition and value context. */
class CScalarExpressionOriginSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CScalarExpressionOriginSpec::class.java.getResource("/scalar-expression/$name")).readText()
    val source = resource("fixture-origin.c"); val bytes = source.toByteArray(Charsets.UTF_8)
    val ast = Json.parseToJsonElement(resource("fixture-origin-ast.json")) as JsonObject
    fun selected(text: String, after: String = "") = source.indexOf(text, source.indexOf(after)).let { CFunctionByteSpan(it, it + text.length) }
    fun decode(span: CFunctionByteSpan = selected("a / b"), tree: JsonObject = ast, material: ByteArray = bytes) =
        CScalarExpressionFactsDecoder.decodeSelection(tree, Path.of("fixture-origin.c"), material, span)
    fun refused(reason: CScalarExpressionRefusal) = CScalarExpressionReadout.Refused(reason)

    "an external integer function keeps its native origin and integer value type" {
        val facts = decode().shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        facts.functionName shouldBe "converted"
        facts.expressionType shouldBe "int"
        facts.assignmentTargetType shouldBe "double"
        facts.inputs.map { it.name } shouldBe listOf("a", "b")
    }
    "a void function retains pure value reads from const integer parameters" {
        val facts = decode(selected("a + b", "void notify")).shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
        facts.functionName shouldBe "notify"
        facts.inputs.map { it.name } shouldBe listOf("a", "b")
        facts.inputs.map { bytes.copyOfRange(it.declarationSpan.start, it.declarationSpan.endExclusive).toString(Charsets.UTF_8) } shouldBe
            listOf("const int a", "const int b")
    }
    "same parameter spelling in other definitions never selects their ownership" {
        decode(selected("a + b", "int other")).shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts.functionName shouldBe "other"
        decode(selected("1 + 2")).shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts.functionName shouldBe "main"
    }
    "an initializer outside any function and a partial expression cannot select an owner" {
        decode(selected("9")) shouldBe refused(CScalarExpressionRefusal.INVALID_SELECTION)
        decode(selected("a / b").let { it.copy(start = it.start + 1) }) shouldBe refused(CScalarExpressionRefusal.INVALID_SELECTION)
    }
    "overlapping supplied native definitions are ambiguous rather than first matched" {
        val children = ast.getValue("inner") as JsonArray
        val function = children.filterIsInstance<JsonObject>().single { (it["name"] as? JsonPrimitive)?.content == "converted" }
        val ambiguous = JsonObject(ast + ("inner" to JsonArray(children + function)))
        decode(tree = ambiguous) shouldBe refused(CScalarExpressionRefusal.AMBIGUOUS_OWNER)
    }
    "malformed encoding ranges AST and exhausted source bounds remain unavailable" {
        decode(material = byteArrayOf(0xc3.toByte(), 0x28)) shouldBe refused(CScalarExpressionRefusal.INVALID_ENCODING)
        decode(span = CFunctionByteSpan(-1, 5)) shouldBe refused(CScalarExpressionRefusal.INVALID_SELECTION)
        decode(material = ByteArray(1_048_577)) shouldBe refused(CScalarExpressionRefusal.INPUT_LIMIT)
        decode(tree = JsonObject(mapOf("inner" to JsonPrimitive("not an AST")))) shouldBe refused(CScalarExpressionRefusal.INVALID_AST)
    }
})

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

/** REQ-C-STATEMENT-EXTRACTION-FACTS-001; pure captured-AST specification. */
class CStatementExtractionFactsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CStatementExtractionFactsSpec::class.java.getResource("/statement-extraction/$name")).readText()
    val source = resource("fixture.c")
    val bytes = source.toByteArray(Charsets.UTF_8)
    val ast = Json.parseToJsonElement(resource("fixture-ast.json")) as JsonObject
    fun selected(function: String): CFunctionByteSpan {
        val declaration = if (function == "qualified") "volatile int sum =" else "int sum ="
        val start = source.indexOf(declaration, source.indexOf("static int $function(int a"))
        val end = source.indexOf(';', source.indexOf("int doubled =", start)) + 1
        return CFunctionByteSpan(start, end)
    }
    fun decoded(function: String = "calculate", tree: JsonObject = ast, range: CFunctionByteSpan = selected(function), material: ByteArray = bytes) =
        CStatementExtractionFactsDecoder.decode(tree, Path.of("fixture.c"), material, function, range)
    fun fragment(range: CFunctionByteSpan) = bytes.copyOfRange(range.start, range.endExclusive).toString(Charsets.UTF_8)
    fun rewrite(v: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement = when (v) {
        is JsonObject -> transform(JsonObject(v.mapValues { (_, value) -> rewrite(value, transform) }))
        is JsonArray -> JsonArray(v.map { rewrite(it, transform) })
        else -> v
    }

    "an exact straight line selection retains independent source statements and one live result" {
        val facts = decoded().shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
        facts.selection shouldBe selected("calculate")
        facts.statements.map(::fragment) shouldBe listOf("int sum = a + b;", "int doubled = sum * 2;")
        facts.localNames shouldBe listOf("sum", "doubled")
        facts.outputName shouldBe "doubled"
        fragment(facts.functionDefinitionSpan) shouldBe "static int calculate(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}"
    }

    "inputs bind to definition parameters in ordinal order rather than differently named prototypes" {
        val facts = decoded().shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
        facts.inputs.map { it.name to it.parameterOrdinal } shouldBe listOf("a" to 0, "b" to 1)
        facts.inputs.map { fragment(it.declarationSpan) } shouldBe listOf("int a", "int b")
        facts.sourceSha256 shouldBe MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        facts.sourcePath shouldBe Path.of("fixture.c")
        facts.ordinaryNames.containsAll(setOf("calculate", "main", "seed", "sum", "doubled", "a", "b")) shouldBe true
    }

    "partial or return-containing selections are unavailable rather than guessed" {
        val range = selected("calculate")
        decoded(range = range.copy(start = range.start + 1)) shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.INVALID_SELECTION)
        decoded(range = range.copy(endExclusive = source.indexOf("return doubled;", range.endExclusive) + "return doubled;".length)) shouldBe
            CStatementExtractionReadout.Refused(CStatementExtractionRefusal.INVALID_SELECTION)
    }

    "a nonfinal selected local used later refuses a single-output helper" {
        decoded("escaped") shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.NONFINAL_LIVE_OUTPUT)
    }

    "native increment evidence refuses an initializer that mutates a selected local" {
        decoded("effects") shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.UNSUPPORTED_EXPRESSION)
    }

    "volatile storage cannot become ordinary helper locals" {
        decoded("qualified") shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.UNSUPPORTED_DECLARATION)
    }

    "a global integer read is outside the explicit parameter input boundary" {
        decoded("outside") shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
    }

    "goto and labels refuse control flow that could enter the selection" {
        decoded("branching") shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.CONTROL_FLOW_UNAVAILABLE)
    }

    "missing native variable identity cannot become a same-name input" {
        val broken = rewrite(ast) { node ->
            if ((node["kind"] as? JsonPrimitive)?.content == "DeclRefExpr" &&
                ((node["referencedDecl"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content == "a") {
                JsonObject(node + ("referencedDecl" to JsonObject((node.getValue("referencedDecl") as JsonObject) + ("id" to JsonPrimitive("missing-native-id")))))
            } else node
        } as JsonObject
        decoded(tree = broken) shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.UNAVAILABLE_INPUT)
    }

    "decoded collections stay immutable and malformed UTF8 remains unavailable" {
        val facts = decoded().shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
        runCatching { (facts.inputs as MutableList).clear() }.isFailure shouldBe true
        runCatching { (facts.statements as MutableList).clear() }.isFailure shouldBe true
        runCatching { (facts.localNames as MutableList).clear() }.isFailure shouldBe true
        runCatching { (facts.ordinaryNames as MutableSet).clear() }.isFailure shouldBe true
        decoded(material = byteArrayOf(0xc3.toByte(), 0x28)) shouldBe CStatementExtractionReadout.Refused(CStatementExtractionRefusal.INVALID_ENCODING)
    }
})

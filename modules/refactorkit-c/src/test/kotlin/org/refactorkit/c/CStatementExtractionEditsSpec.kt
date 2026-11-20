package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.refactorkit.core.TextEdits
import java.nio.file.Path

/** REQ-C-STATEMENT-EXTRACTION-EDITS-001; renderer is not compiler execution authority. */
class CStatementExtractionEditsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CStatementExtractionEditsSpec::class.java.getResource("/statement-extraction/$name")).readText()
    val source = resource("fixture.c")
    val ast = Json.parseToJsonElement(resource("fixture-ast.json")) as JsonObject
    fun facts(function: String = "calculate"): CStatementExtractionFacts {
        val begin = source.indexOf(if (function == "constant") "int first =" else "int sum =", source.indexOf("static int $function(", if (function == "calculate") source.indexOf("static int calculate(") + 1 else 0))
        val end = source.indexOf(';', source.indexOf(if (function == "constant") "int total =" else "int doubled =", begin)) + 1
        return CStatementExtractionFactsDecoder.decode(ast, Path.of("fixture.c"), source.toByteArray(), function, CFunctionByteSpan(begin, end))
            .shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
    }
    fun render(f: CStatementExtractionFacts = facts(), text: String = source, name: String = "computeTotal") = CStatementExtractionEdits.render(text, f, name)
    fun final(result: CStatementExtractionEditsResult, text: String = source) = TextEdits.apply(text, result.shouldBeInstanceOf<CStatementExtractionEditsResult.Accepted>().edits)
    fun shifted(v: JsonElement, delta: Int): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.mapValues { (key, value) -> if (key == "offset") JsonPrimitive(requireNotNull((value as JsonPrimitive).intOrNull) + delta) else shifted(value, delta) })
        is JsonArray -> JsonArray(v.map { shifted(it, delta) })
        else -> v
    }

    "the two original declarations become one result declaration and one verbatim helper" {
        val helper = "static int computeTotal(int a, int b) {\n    int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\n\n"
        val expected = source.replaceFirst("static int calculate(int a, int b)", helper + "static int calculate(int a, int b)")
            .replaceFirst("int sum = a + b;\n    int doubled = sum * 2;\n    return doubled;\n}\nstatic int escaped", "int doubled = computeTotal(a, b);\n    return doubled;\n}\nstatic int escaped")
        final(render()) shouldBe expected
    }

    "zero proven inputs use an explicit void helper and no implicit argument" {
        val helper = "static int fixedTotal(void) {\n    int first = 5;\n    int total = first * 2;\n    return total;\n}\n\n"
        val expected = source.replaceFirst("static int constant(void)", helper + "static int constant(void)")
            .replaceFirst("static int constant(void) {\n    int first = 5;\n    int total = first * 2;", "static int constant(void) {\n    int total = fixedTotal();")
        final(render(facts("constant"), name = "fixedTotal")) shouldBe expected
    }

    "staged helper and call ranges identify exact independent candidate fragments" {
        val result = render().shouldBeInstanceOf<CStatementExtractionEditsResult.Accepted>()
        val candidate = final(result).toByteArray()
        candidate.copyOfRange(result.helperSelection.start, result.helperSelection.endExclusive).toString(Charsets.UTF_8) shouldBe
            "int sum = a + b;\n    int doubled = sum * 2;"
        candidate.copyOfRange(result.callSpan.start, result.callSpan.endExclusive).toString(Charsets.UTF_8) shouldBe "computeTotal(a, b)"
        result.edits.size shouldBe 2
        runCatching { (result.edits as MutableList).clear() }.isFailure shouldBe true
    }

    "Unicode prefixes and CRLF source coordinates preserve unrelated bytes and use UTF16 positions" {
        val prefix = "/* é 😀 */\r\n"
        val text = prefix + source
        val delta = prefix.toByteArray().size
        val originalFacts = facts()
        val newFacts = CStatementExtractionFactsDecoder.decode(shifted(ast, delta) as JsonObject, Path.of("fixture.c"), text.toByteArray(), "calculate",
            CFunctionByteSpan(originalFacts.selection.start + delta, originalFacts.selection.endExclusive + delta))
            .shouldBeInstanceOf<CStatementExtractionReadout.Found>().facts
        val result = render(newFacts, text).shouldBeInstanceOf<CStatementExtractionEditsResult.Accepted>()
        final(result, text).startsWith(prefix) shouldBe true
        result.edits.first().range.start.line shouldBe 3
        result.edits.last().range.start.character shouldBe 4
        val out = final(result, text).toByteArray()
        out.copyOfRange(result.callSpan.start, result.callSpan.endExclusive).toString(Charsets.UTF_8) shouldBe "computeTotal(a, b)"
    }

    "changed source and malformed surrogate encoding cannot reuse captured facts" {
        render(text = source + " ") shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.SOURCE_CHANGED)
        render(text = "\uD800") shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INVALID_ENCODING)
    }

    "reserved or colliding helper names cannot capture an ordinary C binding" {
        listOf("int", "return", "_helper", "__helper", "bad-name", "").forEach { name -> render(name = name) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INVALID_NAME) }
        listOf("calculate", "seed", "main", "sum", "a").forEach { name -> render(name = name) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.NAME_COLLISION) }
    }

    "input ordinals and output identities must form a complete bounded renderer contract" {
        val f = facts()
        listOf(f.copy(inputs = f.inputs.reversed()), f.copy(inputs = f.inputs + f.inputs.first()), f.copy(outputName = "missing"), f.copy(localNames = emptyList())).forEach {
            render(it) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INVALID_FACTS)
        }
    }

    "source ownership ranges cannot escape overlap or reverse selected declarations" {
        val f = facts()
        listOf(f.copy(selection = f.selection.copy(endExclusive = source.toByteArray().size + 1)),
            f.copy(statements = f.statements.reversed()), f.copy(functionDefinitionSpan = f.functionDefinitionSpan.copy(start = f.selection.start))).forEach {
            render(it) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INVALID_RANGE)
        }
    }

    "source and statement budgets refuse before unbounded copying" {
        render(text = "x".repeat(1_048_577)) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INPUT_LIMIT)
        val f = facts()
        render(f.copy(statements = List(17) { f.statements.first() }, localNames = List(17) { "v$it" })) shouldBe CStatementExtractionEditsResult.Refused(CStatementExtractionEditRefusal.INPUT_LIMIT)
    }
})

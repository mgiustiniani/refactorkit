package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.security.MessageDigest

/** REQ-C-SCALAR-EXTRACTION-EDITS-001; pure minimal-edit and native candidate-model specification. */
class CScalarExtractionEditsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CScalarExtractionEditsSpec::class.java.getResource("/scalar-expression/$name")).readText()
    fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val source = resource("fixture.c")
    val candidate = resource("fixture-after.c")
    fun decoded(material: String, astName: String): CScalarExpressionFacts {
        val start = material.indexOf("a / b")
        return CScalarExpressionFactsDecoder.decode(Json.parseToJsonElement(resource(astName)) as JsonObject,
            Path.of("fixture.c"), material.toByteArray(Charsets.UTF_8), "converted", CFunctionByteSpan(start, start + 5))
            .shouldBeInstanceOf<CScalarExpressionReadout.Found>().facts
    }
    val before = decoded(source, "fixture-ast.json")
    val after = decoded(candidate, "fixture-after-ast.json")
    fun render(material: String = source, facts: CScalarExpressionFacts = before, name: String = "quotient") =
        CScalarExtractionEdits.render(material, facts, name)
    fun expectedRefusal(reason: CScalarExtractionEditRefusal) = CScalarExtractionEditsResult.Refused(reason)
    fun fragment(material: String, span: CFunctionByteSpan) = material.toByteArray(Charsets.UTF_8)
        .copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)

    "two immutable edits preserve integer division independently of the double target" {
        val edits = render().shouldBeInstanceOf<CScalarExtractionEditsResult.Accepted>()
        edits.edits.size shouldBe 2
        TextEdits.apply(source, edits.edits) shouldBe candidate
        fragment(candidate, edits.initializerSelection) shouldBe "a / b"
        fragment(candidate, edits.temporaryStatement) shouldBe "const int quotient = a / b;"
        runCatching { (edits.edits as MutableList).clear() }.isFailure shouldBe true
    }

    "independently captured candidate facts retain integer type const context and original inputs" {
        CScalarExtractionEdits.validateCandidate(source, before, candidate, after, "quotient") shouldBe CScalarExtractionValidation.Accepted
        after.expressionType shouldBe "int"
        after.assignmentTargetType shouldBe "const int"
        after.inputs shouldBe before.inputs
        after.ordinaryNames shouldBe before.ordinaryNames + "quotient"
    }

    "Unicode CRLF coordinates and inline statement placement preserve remaining bytes" {
        val prefix = "/* π 😀 */\r\n"
        val unicode = prefix + source.replace("\n", "\r\n")
        fun offset(old: Int) = (prefix + source.substring(0, old).replace("\n", "\r\n")).toByteArray(Charsets.UTF_8).size
        fun mapped(span: CFunctionByteSpan) = CFunctionByteSpan(offset(span.start), offset(span.endExclusive))
        val facts = before.copy(sourceSha256 = sha(unicode), functionDefinitionSpan = mapped(before.functionDefinitionSpan),
            selection = mapped(before.selection), enclosingStatement = mapped(before.enclosingStatement),
            inputs = before.inputs.map { it.copy(declarationSpan = mapped(it.declarationSpan)) })
        val edits = render(unicode, facts).shouldBeInstanceOf<CScalarExtractionEditsResult.Accepted>()
        TextEdits.apply(unicode, edits.edits) shouldBe prefix + candidate.replace("\n", "\r\n")
        val inline = source.replace("\n", " ")
        val inlineEdits = render(inline, before.copy(sourceSha256 = sha(inline))).shouldBeInstanceOf<CScalarExtractionEditsResult.Accepted>()
        TextEdits.apply(inline, inlineEdits.edits) shouldBe candidate.replace("\n", " ").replace(
            "const int quotient = a / b;     double ratio = quotient;", "const int quotient = a / b; double ratio = quotient;")
    }

    "source encoding type range and input budgets refuse unavailable edit authority" {
        render(source + " ") shouldBe expectedRefusal(CScalarExtractionEditRefusal.SOURCE_CHANGED)
        render("\uD800") shouldBe expectedRefusal(CScalarExtractionEditRefusal.INVALID_ENCODING)
        render(" ".repeat(1_048_577)) shouldBe expectedRefusal(CScalarExtractionEditRefusal.INPUT_LIMIT)
        render(facts = before.copy(expressionType = "double")) shouldBe expectedRefusal(CScalarExtractionEditRefusal.INVALID_FACTS)
        render(facts = before.copy(selection = before.selection.copy(start = before.enclosingStatement.start))) shouldBe
            expectedRefusal(CScalarExtractionEditRefusal.INVALID_RANGE)
        render(facts = before.copy(inputs = before.inputs + before.inputs.first())) shouldBe expectedRefusal(CScalarExtractionEditRefusal.INVALID_FACTS)
    }

    "keywords reserved identifiers and any existing ordinary declaration refuse fresh names" {
        render(name = "return") shouldBe expectedRefusal(CScalarExtractionEditRefusal.INVALID_NAME)
        render(name = "__temporary") shouldBe expectedRefusal(CScalarExtractionEditRefusal.INVALID_NAME)
        render(name = "seed") shouldBe expectedRefusal(CScalarExtractionEditRefusal.NAME_COLLISION)
        render(name = "ratio") shouldBe expectedRefusal(CScalarExtractionEditRefusal.NAME_COLLISION)
    }

    "valid C changes or altered native type placement inputs and identity cannot validate another candidate" {
        fun validate(material: String = candidate, facts: CScalarExpressionFacts = after) =
            CScalarExtractionEdits.validateCandidate(source, before, material, facts, "quotient")
        val refused = CScalarExtractionValidation.Refused(CScalarExtractionEditRefusal.INVALID_CANDIDATE)
        validate(candidate.replace("quotient = a / b", "quotient = a * b")) shouldBe refused
        validate(candidate + " ") shouldBe refused
        validate(facts = after.copy(expressionType = "double")) shouldBe refused
        validate(facts = after.copy(assignmentTargetType = "int")) shouldBe refused
        validate(facts = after.copy(selection = after.selection.copy(start = after.selection.start + 1))) shouldBe refused
        validate(facts = after.copy(inputs = after.inputs.reversed())) shouldBe refused
        validate(facts = after.copy(sourceSha256 = before.sourceSha256)) shouldBe refused
        validate(facts = after.copy(sourcePath = Path.of("another.c"))) shouldBe refused
        validate(facts = after.copy(ordinaryNames = before.ordinaryNames)) shouldBe refused
    }
})

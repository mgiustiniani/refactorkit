package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.security.MessageDigest

/** REQ-C-PARAMETER-REORDER-EDITS-001; Kotlin pure rendering, approved Kotest. */
class CParameterReorderingEditsSpec : StringSpec({
    val source = requireNotNull(CParameterReorderingEditsSpec::class.java.getResource("/function-family/fixture.c")).readText()
    val ast = Json.parseToJsonElement(requireNotNull(CParameterReorderingEditsSpec::class.java.getResource("/function-family/fixture-ast.json")).readText()) as JsonObject
    val facts = CFunctionFamilyFactsDecoder.decode(ast, Path.of("fixture.c"), source.toByteArray(), "weighted")
        .shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
    val order = listOf(2, 1, 0)
    val expected = "static int weighted(int z, int y, int x);\n" +
        "static int weighted(int c, int b, int a) { return a * 100 + b * 10 + c; }\n" +
        "int main(void) { int a = 9; return weighted(7, 8, a) + weighted(3, 2, 1); }\n" +
        "static int unrelated(void) { int weighted = 4; return weighted; }\n"
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun shifted(prefix: String): CFunctionFamilyFacts {
        val count = prefix.toByteArray().size
        fun shift(span: CFunctionByteSpan) = CFunctionByteSpan(span.start + count, span.endExclusive + count)
        return facts.copy(sourceSha256 = hash(prefix + source), declarations = facts.declarations.map { declaration ->
            declaration.copy(nameSpan = shift(declaration.nameSpan), parameters = declaration.parameters.map { it.copy(span = shift(it.span)) })
        }, calls = facts.calls.map { call -> call.copy(span = shift(call.span), arguments = call.arguments.map { it.copy(span = shift(it.span)) }) })
    }
    fun render(text: String = source, captured: CFunctionFamilyFacts = facts, mapping: List<Int> = order) =
        CParameterReorderingEdits.render(text, captured, mapping)

    "one mapping updates every prototype definition and call while body and unrelated names remain byte-for-byte intact" {
        val accepted = render().shouldBeInstanceOf<CParameterReorderingEditsResult.Accepted>()
        accepted.edits.size shouldBe 8
        TextEdits.apply(source, accepted.edits) shouldBe expected
    }

    "astral Unicode and CRLF before an edited token use UTF-16 columns rather than UTF-8 bytes" {
        val prefix = "/* 🧩 città */ "
        val accepted = render(prefix + source, shifted(prefix)).shouldBeInstanceOf<CParameterReorderingEditsResult.Accepted>()
        TextEdits.apply(prefix + source, accepted.edits) shouldBe prefix + expected
        accepted.edits.first().range.start.line shouldBe 0
        accepted.edits.first().range.start.character shouldBe prefix.length + "static int weighted(".length
        val multiline = "// 🧩 città\r\n"
        val lines = render(multiline + source, shifted(multiline)).shouldBeInstanceOf<CParameterReorderingEditsResult.Accepted>()
        TextEdits.apply(multiline + source, lines.edits) shouldBe multiline + expected
        lines.edits.first().range.start.line shouldBe 1
    }

    "separator comments and unchanged slots retain their exact source bytes" {
        val text = "static int weighted(int a /* slot zero */, int b /* slot one */, int c) { return a; }\n"
        val positions = listOf("int a", "int b", "int c").map { token -> CFunctionByteSpan(text.indexOf(token), text.indexOf(token) + token.length) }
        val name = text.indexOf("weighted")
        val local = facts.copy(sourceSha256 = hash(text), declarations = listOf(CFunctionDeclarationFact(CFunctionByteSpan(name, name + 8),
            listOf("a", "b", "c").zip(positions).map { (name, span) -> CFunctionParameterFact(name, span) }, true)), calls = emptyList())
        val accepted = render(text, local).shouldBeInstanceOf<CParameterReorderingEditsResult.Accepted>()
        TextEdits.apply(text, accepted.edits) shouldBe "static int weighted(int c /* slot zero */, int b /* slot one */, int a) { return a; }\n"
    }

    "source identity and malformed UTF-16 cannot be silently normalized into edits" {
        render(source + "\n") shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.SourceChanged)
        render("\uD800" + source) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.InvalidEncoding)
    }

    "a bad UTF-8 boundary and out-of-bounds ranges refuse the entire edit set" {
        val prefix = "/* é */ "
        val shifted = shifted(prefix)
        val invalid = shifted.copy(declarations = shifted.declarations.mapIndexed { index, declaration ->
            if (index == 0) declaration.copy(parameters = declaration.parameters.mapIndexed { parameterIndex, parameter ->
                if (parameterIndex == 0) parameter.copy(span = CFunctionByteSpan(4, parameter.span.endExclusive)) else parameter
            }) else declaration
        })
        render(prefix + source, invalid) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.InvalidRange)
        val beyond = facts.copy(calls = facts.calls.mapIndexed { index, call ->
            if (index == 0) call.copy(arguments = call.arguments.mapIndexed { argumentIndex, argument ->
                if (argumentIndex == 0) argument.copy(span = CFunctionByteSpan(0, source.length + 1)) else argument
            }) else call
        })
        render(captured = beyond) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.InvalidRange)
    }

    "overlapping ranges and inconsistent declaration arity cannot produce a partial plan" {
        val overlap = facts.copy(calls = facts.calls.mapIndexed { index, call ->
            if (index == 0) call.copy(arguments = call.arguments.mapIndexed { argumentIndex, argument ->
                if (argumentIndex == 1) argument.copy(span = call.arguments.first().span) else argument
            }) else call
        })
        render(captured = overlap) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.OverlappingRanges)
        val arity = facts.copy(declarations = facts.declarations.mapIndexed { index, declaration ->
            if (index == 0) declaration.copy(parameters = declaration.parameters.dropLast(1)) else declaration
        })
        render(captured = arity) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.InvalidFacts)
    }

    "admission failures keep their typed policy reason" {
        render(mapping = listOf(0, 0, 2)) shouldBe CParameterReorderingEditsResult.Refused(
            CParameterReorderingEditError.PolicyDenied(CParameterReorderingRefusal.INVALID_MAPPING))
        render(captured = facts.copy(family = CDirectCallFamilyEvidence.ADDRESS_TAKEN)) shouldBe CParameterReorderingEditsResult.Refused(
            CParameterReorderingEditError.PolicyDenied(CParameterReorderingRefusal.INDIRECT_CALLS))
    }

    "the bounded patch protocol does not receive a partial oversized edit set" {
        render(captured = facts.copy(calls = List(5_001) { facts.calls.first() })) shouldBe CParameterReorderingEditsResult.Refused(CParameterReorderingEditError.EditLimit)
    }

    "accepted edit collections cannot be mutated after rendering" {
        val accepted = render().shouldBeInstanceOf<CParameterReorderingEditsResult.Accepted>()
        runCatching { (accepted.edits as MutableList<*>).clear() }.isFailure shouldBe true
        TextEdits.apply(source, accepted.edits) shouldBe expected
    }
})

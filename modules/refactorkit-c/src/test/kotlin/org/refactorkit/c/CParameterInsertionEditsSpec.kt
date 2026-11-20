package org.refactorkit.c

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import java.nio.file.Path
import java.security.MessageDigest

/** REQ-C-PARAMETER-INSERTION-001; independently authored source oracle, approved Kotest. */
class CParameterInsertionEditsSpec : StringSpec({
    fun resource(name: String) = requireNotNull(CParameterInsertionEditsSpec::class.java.getResource("/function-family/$name")).readText()
    fun facts(stem: String, name: String) = CFunctionFamilyFactsDecoder.decode(Json.parseToJsonElement(resource("$stem-ast.json")) as JsonObject,
        Path.of("$stem.c"), resource("$stem.c").toByteArray(), name).shouldBeInstanceOf<CFunctionFamilyFactsReadout.Found>().facts
    fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    val source = resource("fixture.c")
    val before = facts("fixture", "weighted")
    val added = resource("added.c")
    val after = facts("added", "weighted")
    val arguments = before.calls.zip(listOf("5", "3")) { call, text -> CExplicitCallArgument(call.span, text) }
    fun render(captured: CFunctionFamilyFacts = before, name: String = "limit", position: Int = 1,
        expressions: List<CExplicitCallArgument> = arguments, text: String = source) = CParameterInsertionEdits.render(text, captured, name, position, expressions)

    "one explicit span-bound argument per call inserts the same ordinal in prototypes definition and calls" {
        val accepted = render().shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        accepted.edits.size shouldBe 4
        TextEdits.apply(source, accepted.edits) shouldBe added
        CParameterInsertionEdits.validateCandidate(source, before, added, after, "limit", 1, arguments) shouldBe CParameterInsertionValidation.Accepted
    }

    "insertion before the first and after the last parameter preserves all original associations" {
        val first = render(position = 0).shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        TextEdits.apply(source, first.edits) shouldBe "static int weighted(int limit, int x, int y, int z);\n" +
            "static int weighted(int limit, int a, int b, int c) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { int a = 9; return weighted((5), a, 8, 7) + weighted((3), 1, 2, 3); }\n" +
            "static int unrelated(void) { int weighted = 4; return weighted; }\n"
        val last = render(position = 3).shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        TextEdits.apply(source, last.edits) shouldBe "static int weighted(int x, int y, int z, int limit);\n" +
            "static int weighted(int a, int b, int c, int limit) { return a * 100 + b * 10 + c; }\n" +
            "int main(void) { int a = 9; return weighted(a, 8, 7, (5)) + weighted(1, 2, 3, (3)); }\n" +
            "static int unrelated(void) { int weighted = 4; return weighted; }\n"
    }

    "an explicit void clause is replaced without consuming comments or inventing a default argument" {
        val old = resource("insertion.c")
        val captured = facts("insertion", "answer")
        val requests = listOf(CExplicitCallArgument(captured.calls.single().span, "seed"))
        val accepted = CParameterInsertionEdits.render(old, captured, "extra", 0, requests).shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        TextEdits.apply(old, accepted.edits) shouldBe "typedef int Width;\nint seed = 7;\n" +
            "static int answer(/* left */ int extra /* right */);\n" +
            "static int answer(int extra) { Width value = seed; return value + sizeof(Width); }\n" +
            "int main(void) { return answer((seed)); }\n"
    }

    "binding capture and unavailable ordinary scope refuse before source edits" {
        render(name = "a") shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.NAME_CAPTURE)
        val unknown = before.copy(declarations = before.declarations.map { it.copy(parameterScope = CFunctionParameterScopeEvidence.Unavailable) })
        render(captured = unknown) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.SCOPE_UNAVAILABLE)
        val old = resource("insertion.c"); val captured = facts("insertion", "answer")
        listOf("Width", "seed", "value").forEach { name ->
            CParameterInsertionEdits.render(old, captured, name, 0, listOf(CExplicitCallArgument(captured.calls.single().span, "seed"))) shouldBe
                CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.NAME_CAPTURE)
        }
    }

    "keyword reserved names invalid positions and incomplete caller mappings never produce partial edits" {
        listOf("int", "_Atomic", "_Upper", "__private", "x); injected(").forEach { name ->
            render(name = name) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.INVALID_NAME)
        }
        render(position = 4) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.INVALID_POSITION)
        render(expressions = arguments.take(1)) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.ARGUMENT_MAPPING)
        render(expressions = listOf(arguments.first(), arguments.first())) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.ARGUMENT_MAPPING)
        render(expressions = arguments.map { it.copy(expression = " ") }) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.ARGUMENT_MAPPING)
    }

    "callbacks external consumers and unavailable or observable old arguments keep typed refusal evidence" {
        listOf(CDirectCallFamilyEvidence.ADDRESS_TAKEN to CParameterInsertionRefusal.INDIRECT_CALLS,
            CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS to CParameterInsertionRefusal.EXTERNAL_CONSUMERS,
            CDirectCallFamilyEvidence.UNAVAILABLE to CParameterInsertionRefusal.FAMILY_UNAVAILABLE).forEach { (family, reason) ->
            render(captured = before.copy(family = family)) shouldBe CParameterInsertionEditsResult.Refused(reason)
        }
        listOf(CArgumentEffectEvidence.OBSERVABLE_EFFECT to CParameterInsertionRefusal.OBSERVABLE_EFFECT,
            CArgumentEffectEvidence.UNAVAILABLE to CParameterInsertionRefusal.EFFECT_UNAVAILABLE).forEach { (effect, reason) ->
            render(captured = before.copy(calls = before.calls.map { call -> call.copy(arguments = call.arguments.map { it.copy(effect = effect) }) })) shouldBe
                CParameterInsertionEditsResult.Refused(reason)
        }
    }

    "candidate argument extent and effect evidence reject escaped expression text and partial compiler families" {
        val changed = after.copy(calls = after.calls.mapIndexed { index, call -> if (index == 0) call.copy(arguments = call.arguments.mapIndexed { argumentIndex, argument ->
            if (argumentIndex == 1) argument.copy(span = argument.span.copy(endExclusive = argument.span.endExclusive + 1)) else argument
        }) else call })
        CParameterInsertionEdits.validateCandidate(source, before, added, changed, "limit", 1, arguments) shouldBe CParameterInsertionValidation.Refused(CParameterInsertionRefusal.INVALID_CANDIDATE)
        val observable = after.copy(calls = after.calls.map { call -> call.copy(arguments = call.arguments.mapIndexed { index, argument ->
            if (index == 1) argument.copy(effect = CArgumentEffectEvidence.OBSERVABLE_EFFECT) else argument
        }) })
        CParameterInsertionEdits.validateCandidate(source, before, added, observable, "limit", 1, arguments) shouldBe CParameterInsertionValidation.Refused(CParameterInsertionRefusal.OBSERVABLE_EFFECT)
        CParameterInsertionEdits.validateCandidate(source, before, added, after.copy(calls = after.calls.take(1)), "limit", 1, arguments) shouldBe CParameterInsertionValidation.Refused(CParameterInsertionRefusal.INVALID_CANDIDATE)
    }

    "source identity Unicode positions encoding and bounded expression sizes stay explicit" {
        render(text = source + "\n") shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.SOURCE_CHANGED)
        render(text = "\uD800" + source) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.INVALID_ENCODING)
        render(expressions = arguments.map { it.copy(expression = "1".repeat(4097)) }) shouldBe CParameterInsertionEditsResult.Refused(CParameterInsertionRefusal.INPUT_LIMIT)
        val prefix = "/* 🧩 città */\r\n"
        val count = prefix.toByteArray().size
        fun span(value: CFunctionByteSpan) = CFunctionByteSpan(value.start + count, value.endExclusive + count)
        val captured = before.copy(sourceSha256 = hash(prefix + source), declarations = before.declarations.map { declaration -> declaration.copy(
            nameSpan = span(declaration.nameSpan), parameters = declaration.parameters.map { it.copy(span = span(it.span)) },
            parameterClause = CFunctionParameterClauseEvidence.NonEmpty(span((declaration.parameterClause as CFunctionParameterClauseEvidence.NonEmpty).span)),
        ) }, calls = before.calls.map { call -> call.copy(span = span(call.span), arguments = call.arguments.map { it.copy(span = span(it.span)) }) })
        val accepted = render(text = prefix + source, captured = captured, expressions = arguments.map { it.copy(callSpan = span(it.callSpan)) })
            .shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        TextEdits.apply(prefix + source, accepted.edits) shouldBe prefix + added
        accepted.edits.first().range.start.line shouldBe 1
    }

    "accepted edits are immutable independent of mutable request collections" {
        val requests = arguments.toMutableList()
        val accepted = render(expressions = requests).shouldBeInstanceOf<CParameterInsertionEditsResult.Accepted>()
        requests.clear()
        runCatching { (accepted.edits as MutableList<*>).clear() }.isFailure shouldBe true
        TextEdits.apply(source, accepted.edits) shouldBe added

        val edit = TextEdit(SourceRange(SourcePosition(0, 0), SourcePosition(0, 1)), "x")
        val constructorInput = mutableListOf(edit)
        val direct = CParameterInsertionEditsResult.Accepted(constructorInput)
        constructorInput.clear()
        direct.edits shouldBe listOf(edit)
        direct.copy() shouldBe direct
        direct.hashCode() shouldBe direct.copy().hashCode()
        direct.toString() shouldBe "Accepted(edits=[$edit])"
        val (destructuredEdits) = direct
        destructuredEdits shouldBe listOf(edit)

        val copyInput = mutableListOf(edit)
        val copied = direct.copy(copyInput)
        copyInput.clear()
        copied.edits shouldBe listOf(edit)
        runCatching { (copied.edits as MutableList<TextEdit>).clear() }.isFailure shouldBe true
    }
})

package org.refactorkit.c

import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.ProtocolLimits
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Collections

/** Each caller expression names one captured compiler-owned call, never a default parameter. */
data class CExplicitCallArgument(val callSpan: CFunctionByteSpan, val expression: String)
internal enum class CParameterInsertionRefusal {
    INPUT_LIMIT, INVALID_ENCODING, SOURCE_CHANGED, INVALID_NAME, INVALID_POSITION,
    NAME_CAPTURE, SCOPE_UNAVAILABLE, CLAUSE_UNAVAILABLE, FAMILY_UNAVAILABLE,
    INDIRECT_CALLS, EXTERNAL_CONSUMERS, ARGUMENT_ARITY, ARGUMENT_MAPPING,
    OBSERVABLE_EFFECT, EFFECT_UNAVAILABLE, INVALID_RANGE, INVALID_CANDIDATE,
}
internal sealed interface CParameterInsertionEditsResult {
    class Accepted(edits: List<TextEdit>) : CParameterInsertionEditsResult {
        val edits: List<TextEdit> = immutableParameterInsertionEdits(edits)

        operator fun component1(): List<TextEdit> = edits

        fun copy(edits: List<TextEdit> = this.edits) = Accepted(edits)

        override fun equals(other: Any?): Boolean = other === this || other is Accepted && edits == other.edits

        override fun hashCode(): Int = edits.hashCode()

        override fun toString(): String = "Accepted(edits=$edits)"
    }

    data class Refused(val reason: CParameterInsertionRefusal) : CParameterInsertionEditsResult
}

private fun immutableParameterInsertionEdits(values: Collection<TextEdit>): List<TextEdit> =
    Collections.unmodifiableList(ArrayList(values))
internal sealed interface CParameterInsertionValidation {
    data object Accepted : CParameterInsertionValidation
    data class Refused(val reason: CParameterInsertionRefusal) : CParameterInsertionValidation
}

internal object CParameterInsertionEdits {
    private val KEYWORDS = setOf("auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum", "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary", "_Noreturn", "_Static_assert", "_Thread_local")

    internal fun isKeyword(name: String): Boolean = name in KEYWORDS

    fun render(source: String, facts: CFunctionFamilyFacts, name: String, ordinal: Int, arguments: List<CExplicitCallArgument>): CParameterInsertionEditsResult = try {
        val bytes = ownedBytes(source, facts)
        if (facts.declarations.isEmpty() || facts.declarations.size > 64 || facts.calls.size > 8192 || arguments.size > 8192) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        val arity = facts.declarations.first().parameters.size
        if (arity !in 0..63 || facts.declarations.any { it.parameters.size != arity }) reject(CParameterInsertionRefusal.ARGUMENT_ARITY)
        if (ordinal !in 0..arity) reject(CParameterInsertionRefusal.INVALID_POSITION)
        if (!name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) || name.length > 128 || name in KEYWORDS || name.startsWith("__") ||
            name.length >= 2 && name[0] == '_' && name[1] in 'A'..'Z') reject(CParameterInsertionRefusal.INVALID_NAME)
        when (facts.family) {
            CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC -> Unit
            CDirectCallFamilyEvidence.UNAVAILABLE -> reject(CParameterInsertionRefusal.FAMILY_UNAVAILABLE)
            CDirectCallFamilyEvidence.ADDRESS_TAKEN -> reject(CParameterInsertionRefusal.INDIRECT_CALLS)
            CDirectCallFamilyEvidence.EXTERNAL_CONSUMERS -> reject(CParameterInsertionRefusal.EXTERNAL_CONSUMERS)
        }
        facts.declarations.forEach { declaration ->
            val scope = declaration.parameterScope as? CFunctionParameterScopeEvidence.Available ?: reject(CParameterInsertionRefusal.SCOPE_UNAVAILABLE)
            if (name in scope.ordinaryNames) reject(CParameterInsertionRefusal.NAME_CAPTURE)
            if (declaration.parameterClause == CFunctionParameterClauseEvidence.Unavailable) reject(CParameterInsertionRefusal.CLAUSE_UNAVAILABLE)
        }
        if (facts.calls.any { it.arguments.size != arity }) reject(CParameterInsertionRefusal.ARGUMENT_ARITY)
        facts.calls.flatMap(CFunctionCallFact::arguments).forEach { requireEffect(it.effect) }
        val expressions = arguments.associateBy(CExplicitCallArgument::callSpan)
        if (expressions.size != arguments.size || expressions.keys != facts.calls.map(CFunctionCallFact::span).toSet() ||
            arguments.any { it.expression.isBlank() }) reject(CParameterInsertionRefusal.ARGUMENT_MAPPING)
        if (arguments.any { it.expression.length > 4096 }) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        val encodedExpressionBytes = arguments.sumOf { encoded(it.expression).size.toLong() }
        if (encodedExpressionBytes > 1_048_576 || facts.declarations.size + facts.calls.size > ProtocolLimits.MAX_EXTERNAL_TEXT_EDITS) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        val replacements = mutableListOf<Pair<CFunctionByteSpan, String>>()
        facts.declarations.forEach { declaration ->
            when (val clause = declaration.parameterClause) {
                CFunctionParameterClauseEvidence.Unavailable -> reject(CParameterInsertionRefusal.CLAUSE_UNAVAILABLE)
                is CFunctionParameterClauseEvidence.ExplicitVoid -> {
                    if (arity != 0 || fragment(bytes, clause.span) != "void") reject(CParameterInsertionRefusal.INVALID_RANGE)
                    replacements += clause.span to "int $name"
                }
                is CFunctionParameterClauseEvidence.NonEmpty -> {
                    fragment(bytes, clause.span)
                    if (arity == 0 || declaration.parameters.any { it.span.start < clause.span.start || it.span.endExclusive > clause.span.endExclusive }) reject(CParameterInsertionRefusal.INVALID_RANGE)
                    declaration.parameters.forEach { fragment(bytes, it.span) }
                    val offset = if (ordinal == arity) declaration.parameters.last().span.endExclusive else declaration.parameters[ordinal].span.start
                    replacements += CFunctionByteSpan(offset, offset) to if (ordinal == arity) ", int $name" else "int $name, "
                }
            }
        }
        facts.calls.forEach { call ->
            val callText = fragment(bytes, call.span)
            if (!callText.endsWith(")") || call.arguments.any { it.span.start < call.span.start || it.span.endExclusive >= call.span.endExclusive }) reject(CParameterInsertionRefusal.INVALID_RANGE)
            call.arguments.forEach { fragment(bytes, it.span) }
            val expression = "(${expressions.getValue(call.span).expression})"
            val offset = if (arity == 0) call.span.endExclusive - 1 else if (ordinal == arity) call.arguments.last().span.endExclusive else call.arguments[ordinal].span.start
            replacements += CFunctionByteSpan(offset, offset) to if (arity == 0) expression else if (ordinal == arity) ", $expression" else "$expression, "
        }
        val sorted = replacements.sortedBy { it.first.start }
        if (sorted.zipWithNext().any { (left, right) -> left.first.endExclusive > right.first.start || left.first.start == right.first.start }) reject(CParameterInsertionRefusal.INVALID_RANGE)
        if (bytes.size.toLong() + sorted.sumOf { (span, text) -> text.toByteArray().size.toLong() - (span.endExclusive - span.start) } > 1_048_576) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        val positions = positions(source, sorted.flatMap { listOf(it.first.start, it.first.endExclusive) }.toSet())
        val edits = sorted.map { (span, text) -> TextEdit(SourceRange(positions.getValue(span.start), positions.getValue(span.endExclusive)), text) }
        CParameterInsertionEditsResult.Accepted(edits)
    } catch (failure: Rejected) { CParameterInsertionEditsResult.Refused(failure.reason) }

    fun validateCandidate(source: String, before: CFunctionFamilyFacts, candidate: String, after: CFunctionFamilyFacts,
        name: String, ordinal: Int, arguments: List<CExplicitCallArgument>): CParameterInsertionValidation = try {
        val rendering = render(source, before, name, ordinal, arguments)
        if (rendering is CParameterInsertionEditsResult.Refused) reject(rendering.reason)
        if (TextEdits.apply(source, (rendering as CParameterInsertionEditsResult.Accepted).edits) != candidate) reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
        val oldBytes = ownedBytes(source, before); val newBytes = ownedBytes(candidate, after)
        if (after.name != before.name || after.family != CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC ||
            after.declarations.size != before.declarations.size || after.calls.size != before.calls.size) reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
        before.declarations.zip(after.declarations).forEach { (old, new) ->
            if (new.isDefinition != old.isDefinition || new.parameters.size != old.parameters.size + 1 || new.parameters[ordinal].name != name ||
                new.parameters.filterIndexed { index, _ -> index != ordinal }.map(CFunctionParameterFact::name) != old.parameters.map(CFunctionParameterFact::name)) reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
            old.parameters.zip(new.parameters.filterIndexed { index, _ -> index != ordinal }).forEach { (oldParameter, newParameter) ->
                if (fragment(oldBytes, oldParameter.span) != fragment(newBytes, newParameter.span)) reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
            }
        }
        val expressions = arguments.associateBy(CExplicitCallArgument::callSpan)
        before.calls.zip(after.calls).forEach { (old, new) ->
            if (new.arguments.size != old.arguments.size + 1 || fragment(newBytes, new.arguments[ordinal].span) != "(${expressions.getValue(old.span).expression})") reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
            requireEffect(new.arguments[ordinal].effect)
            old.arguments.zip(new.arguments.filterIndexed { index, _ -> index != ordinal }).forEach { (oldArgument, newArgument) ->
                if (fragment(oldBytes, oldArgument.span) != fragment(newBytes, newArgument.span)) reject(CParameterInsertionRefusal.INVALID_CANDIDATE)
                requireEffect(newArgument.effect)
            }
        }
        CParameterInsertionValidation.Accepted
    } catch (failure: Rejected) { CParameterInsertionValidation.Refused(failure.reason) }

    private fun requireEffect(effect: CArgumentEffectEvidence) {
        when (effect) {
            CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT -> Unit
            CArgumentEffectEvidence.OBSERVABLE_EFFECT -> reject(CParameterInsertionRefusal.OBSERVABLE_EFFECT)
            CArgumentEffectEvidence.UNAVAILABLE -> reject(CParameterInsertionRefusal.EFFECT_UNAVAILABLE)
        }
    }

    private fun encoded(source: String): ByteArray = try {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
        ByteArray(buffer.remaining()).also(buffer::get)
    } catch (_: Exception) { reject(CParameterInsertionRefusal.INVALID_ENCODING) }

    private fun ownedBytes(source: String, facts: CFunctionFamilyFacts): ByteArray {
        if (source.length > 1_048_576) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        val bytes = encoded(source)
        if (bytes.size > 1_048_576) reject(CParameterInsertionRefusal.INPUT_LIMIT)
        if (MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } != facts.sourceSha256) reject(CParameterInsertionRefusal.SOURCE_CHANGED)
        return bytes
    }

    private fun fragment(source: ByteArray, span: CFunctionByteSpan): String {
        if (span.start < 0 || span.endExclusive <= span.start || span.endExclusive > source.size ||
            source[span.start].toInt() and 0xc0 == 0x80 || span.endExclusive < source.size && source[span.endExclusive].toInt() and 0xc0 == 0x80) reject(CParameterInsertionRefusal.INVALID_RANGE)
        return source.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    }

    private fun positions(source: String, requested: Set<Int>): Map<Int, SourcePosition> {
        val result = mutableMapOf<Int, SourcePosition>()
        var offset = 0; var byteOffset = 0; var line = 0; var column = 0
        while (true) {
            if (byteOffset in requested) result[byteOffset] = SourcePosition(line, column)
            if (offset == source.length) break
            val point = source.codePointAt(offset); val units = Character.charCount(point)
            byteOffset += when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            offset += units
            if (point == 10) { line++; column = 0 } else column += units
        }
        if (result.size != requested.size) reject(CParameterInsertionRefusal.INVALID_RANGE)
        return result
    }

    private class Rejected(val reason: CParameterInsertionRefusal) : IllegalArgumentException()
    private fun reject(reason: CParameterInsertionRefusal): Nothing = throw Rejected(reason)
}

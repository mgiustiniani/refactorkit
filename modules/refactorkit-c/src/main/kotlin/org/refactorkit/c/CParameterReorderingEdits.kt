package org.refactorkit.c

import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

internal sealed interface CParameterReorderingEditError {
    data object SourceChanged : CParameterReorderingEditError
    data object InvalidEncoding : CParameterReorderingEditError
    data object InvalidFacts : CParameterReorderingEditError
    data object InvalidRange : CParameterReorderingEditError
    data object OverlappingRanges : CParameterReorderingEditError
    data object InputLimit : CParameterReorderingEditError
    data object EditLimit : CParameterReorderingEditError
    data class PolicyDenied(val reason: CParameterReorderingRefusal) : CParameterReorderingEditError
}

internal sealed interface CParameterReorderingEditsResult {
    data class Accepted(val edits: List<TextEdit>) : CParameterReorderingEditsResult
    data class Refused(val reason: CParameterReorderingEditError) : CParameterReorderingEditsResult
}

/** Pure rendering from captured facts; caller retains compiler and transaction authority. */
internal object CParameterReorderingEdits {
    fun render(source: String, facts: CFunctionFamilyFacts, originalIndices: List<Int>): CParameterReorderingEditsResult {
        if (source.length > 1_048_576 || facts.declarations.size > 64 || facts.calls.size > 8_192) return refused(CParameterReorderingEditError.InputLimit)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: Exception) { return refused(CParameterReorderingEditError.InvalidEncoding) }
        if (bytes.size > 1_048_576) return refused(CParameterReorderingEditError.InputLimit)
        if (MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } != facts.sourceSha256) return refused(CParameterReorderingEditError.SourceChanged)
        val parameterCount = facts.declarations.firstOrNull()?.parameters?.size ?: return refused(CParameterReorderingEditError.InvalidFacts)
        if (parameterCount > 64) return refused(CParameterReorderingEditError.InputLimit)
        if (facts.declarations.any { it.parameters.size != parameterCount }) return refused(CParameterReorderingEditError.InvalidFacts)
        val decision = CParameterReorderingPolicy.decide(parameterCount, originalIndices, facts.family,
            facts.calls.map { call -> call.arguments.map(CFunctionArgumentFact::effect) })
        val order = when (decision) {
            is CParameterReorderingDecision.Refused -> return refused(CParameterReorderingEditError.PolicyDenied(decision.reason))
            is CParameterReorderingDecision.Accepted -> decision.originalIndices
        }
        val changedSlots = order.indices.count { order[it] != it }
        if ((facts.declarations.size + facts.calls.size) * changedSlots > ProtocolLimits.MAX_EXTERNAL_TEXT_EDITS) return refused(CParameterReorderingEditError.EditLimit)
        val groups = facts.declarations.map { declaration -> declaration.parameters.map(CFunctionParameterFact::span) } +
            facts.calls.map { call -> call.arguments.map(CFunctionArgumentFact::span) }
        val spans = groups.flatten().sortedBy(CFunctionByteSpan::start)
        if (spans.any { !validBoundaryRange(it, bytes) }) return refused(CParameterReorderingEditError.InvalidRange)
        if (spans.zipWithNext().any { (left, right) -> left.endExclusive > right.start }) return refused(CParameterReorderingEditError.OverlappingRanges)
        // Every group must keep its original slot order before any replacement is emitted.
        if (groups.any { group -> group.zipWithNext().any { (left, right) -> left.start >= right.start } }) return refused(CParameterReorderingEditError.InvalidFacts)
        val replacements = groups.flatMap { group -> order.indices.mapNotNull { index ->
            val target = group[index]
            val from = group[order[index]]
            val replacement = bytes.copyOfRange(from.start, from.endExclusive).toString(Charsets.UTF_8)
            if (replacement == bytes.copyOfRange(target.start, target.endExclusive).toString(Charsets.UTF_8)) null
            else target to replacement
        } }.sortedBy { it.first.start }
        val offsets = replacements.flatMap { (span, _) -> listOf(span.start, span.endExclusive) }.toSet()
        val positions = positions(source, offsets)
        val edits = replacements.map { (span, replacement) -> TextEdit(
            SourceRange(positions.getValue(span.start), positions.getValue(span.endExclusive)), replacement,
        ) }
        return CParameterReorderingEditsResult.Accepted(Collections.unmodifiableList(edits))
    }

    private fun validBoundaryRange(span: CFunctionByteSpan, source: ByteArray): Boolean =
        span.start >= 0 && span.endExclusive > span.start && span.endExclusive <= source.size &&
            source[span.start].toInt() and 0xc0 != 0x80 &&
            (span.endExclusive == source.size || source[span.endExclusive].toInt() and 0xc0 != 0x80)

    /** One pass over source; only requested endpoints allocate positions. */
    private fun positions(source: String, offsets: Set<Int>): Map<Int, SourcePosition> {
        val result = mutableMapOf<Int, SourcePosition>()
        var characterOffset = 0
        var byteOffset = 0
        var line = 0
        var column = 0
        while (true) {
            if (byteOffset in offsets) result[byteOffset] = SourcePosition(line, column)
            if (characterOffset == source.length) break
            val point = source.codePointAt(characterOffset)
            val units = Character.charCount(point)
            byteOffset += when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
            characterOffset += units
            if (point == 10) { line++; column = 0 } else column += units
        }
        return result
    }

    private fun refused(reason: CParameterReorderingEditError) = CParameterReorderingEditsResult.Refused(reason)
}

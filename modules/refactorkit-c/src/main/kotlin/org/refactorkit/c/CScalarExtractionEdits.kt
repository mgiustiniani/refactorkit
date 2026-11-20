package org.refactorkit.c

import org.refactorkit.core.TextEdit
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

internal enum class CScalarExtractionEditRefusal { SOURCE_CHANGED, INVALID_ENCODING, INPUT_LIMIT, INVALID_FACTS, INVALID_RANGE, INVALID_NAME, NAME_COLLISION, INVALID_CANDIDATE }
internal sealed interface CScalarExtractionEditsResult {
    data class Accepted(val edits: List<TextEdit>, val initializerSelection: CFunctionByteSpan,
        val temporaryStatement: CFunctionByteSpan) : CScalarExtractionEditsResult
    data class Refused(val reason: CScalarExtractionEditRefusal) : CScalarExtractionEditsResult
}
internal sealed interface CScalarExtractionValidation {
    data object Accepted : CScalarExtractionValidation
    data class Refused(val reason: CScalarExtractionEditRefusal) : CScalarExtractionValidation
}

/** Pure minimal edits and post-model rules; native execution and transaction authority are separate. */
internal object CScalarExtractionEdits {
    fun render(source: String, facts: CScalarExpressionFacts, tempName: String): CScalarExtractionEditsResult {
        if (source.length > MAX_BYTES || facts.inputs.size > 128 || facts.ordinaryNames.size > 100_000) return refused(CScalarExtractionEditRefusal.INPUT_LIMIT)
        val bytes = try {
            val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) { return refused(CScalarExtractionEditRefusal.INVALID_ENCODING) }
        if (bytes.size > MAX_BYTES) return refused(CScalarExtractionEditRefusal.INPUT_LIMIT)
        if (sha(bytes) != facts.sourceSha256) return refused(CScalarExtractionEditRefusal.SOURCE_CHANGED)
        if (!tempName.matches(Regex("[A-Za-z][A-Za-z_0-9]{0,127}")) || CParameterInsertionEdits.isKeyword(tempName)) return refused(CScalarExtractionEditRefusal.INVALID_NAME)
        val inputs = facts.inputs.map { it.name }
        if (facts.expressionType != "int" || facts.assignmentTargetType !in setOf("int", "const int", "double", "const double") ||
            inputs.distinct().size != inputs.size || inputs.any { it.length !in 1..128 } ||
            facts.ordinaryNames.any { it.length !in 1..128 } || !facts.ordinaryNames.containsAll(inputs)) return refused(CScalarExtractionEditRefusal.INVALID_FACTS)
        if (tempName in facts.ordinaryNames) return refused(CScalarExtractionEditRefusal.NAME_COLLISION)
        val definition = facts.functionDefinitionSpan
        val statement = facts.enclosingStatement
        val selection = facts.selection
        if (!valid(definition, bytes) || !valid(statement, bytes) || !valid(selection, bytes) ||
            definition.start >= statement.start || statement.endExclusive >= definition.endExclusive ||
            selection.start <= statement.start || selection.endExclusive >= statement.endExclusive ||
            facts.inputs.any { !valid(it.declarationSpan, bytes) || it.declarationSpan.start <= definition.start || it.declarationSpan.endExclusive > statement.start }) {
            return refused(CScalarExtractionEditRefusal.INVALID_RANGE)
        }
        val expression = fragment(bytes, selection)
        val characterStart = fragment(bytes, CFunctionByteSpan(0, statement.start)).length
        val margin = source.substring(source.lastIndexOf('\n', characterStart - 1) + 1, characterStart)
        if (margin.length > 128 && margin.all { it == ' ' || it == '\t' }) return refused(CScalarExtractionEditRefusal.INPUT_LIMIT)
        val newlineIndex = source.indexOf('\n')
        val newline = if (newlineIndex > 0 && source[newlineIndex - 1] == '\r') "\r\n" else "\n"
        val suffix = if (margin.all { it == ' ' || it == '\t' }) newline + margin else " "
        val header = "const int $tempName = "
        val declaration = "$header$expression;"
        val insertion = declaration + suffix
        val insertionBytes = insertion.toByteArray(Charsets.UTF_8).size
        if (bytes.size + insertionBytes - (selection.endExclusive - selection.start) + tempName.length > MAX_BYTES) return refused(CScalarExtractionEditRefusal.INPUT_LIMIT)
        fun position(offset: Int) = TextEdits.positionForOffset(source, bytes.copyOfRange(0, offset).toString(Charsets.UTF_8).length)
        val start = position(statement.start)
        val edits = listOf(TextEdit(SourceRange(start, start), insertion), TextEdit(SourceRange(position(selection.start), position(selection.endExclusive)), tempName))
        val initializerStart = statement.start + header.toByteArray(Charsets.UTF_8).size
        return CScalarExtractionEditsResult.Accepted(Collections.unmodifiableList(edits),
            CFunctionByteSpan(initializerStart, initializerStart + selection.endExclusive - selection.start),
            CFunctionByteSpan(statement.start, statement.start + declaration.toByteArray(Charsets.UTF_8).size))
    }

    fun validateCandidate(source: String, before: CScalarExpressionFacts, candidate: String,
        after: CScalarExpressionFacts, tempName: String): CScalarExtractionValidation {
        fun invalid() = CScalarExtractionValidation.Refused(CScalarExtractionEditRefusal.INVALID_CANDIDATE)
        val rendered = when (val result = render(source, before, tempName)) {
            is CScalarExtractionEditsResult.Accepted -> result
            is CScalarExtractionEditsResult.Refused -> return CScalarExtractionValidation.Refused(result.reason)
        }
        if (TextEdits.apply(source, rendered.edits) != candidate) return invalid()
        val bytes = candidate.toByteArray(Charsets.UTF_8)
        val original = source.toByteArray(Charsets.UTF_8)
        if (after.sourceSha256 != sha(bytes) || after.sourcePath.normalize() != before.sourcePath.normalize() ||
            after.functionDefinitionSpan.start != before.functionDefinitionSpan.start ||
            after.functionDefinitionSpan.endExclusive != before.functionDefinitionSpan.endExclusive + bytes.size - original.size ||
            after.selection != rendered.initializerSelection || after.enclosingStatement != rendered.temporaryStatement ||
            after.expressionType != "int" || after.assignmentTargetType != "const int" || after.inputs != before.inputs ||
            after.ordinaryNames != before.ordinaryNames + tempName || !valid(after.selection, bytes) || !valid(after.enclosingStatement, bytes) ||
            fragment(original, before.selection) != fragment(bytes, after.selection) ||
            fragment(bytes, after.enclosingStatement) != "const int $tempName = ${fragment(original, before.selection)};") return invalid()
        return CScalarExtractionValidation.Accepted
    }

    private const val MAX_BYTES = 1_048_576
    private fun valid(span: CFunctionByteSpan, bytes: ByteArray) = span.start >= 0 && span.endExclusive > span.start && span.endExclusive <= bytes.size &&
        bytes[span.start].toInt() and 0xc0 != 0x80 && (span.endExclusive == bytes.size || bytes[span.endExclusive].toInt() and 0xc0 != 0x80)
    private fun fragment(bytes: ByteArray, span: CFunctionByteSpan) = bytes.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun refused(reason: CScalarExtractionEditRefusal) = CScalarExtractionEditsResult.Refused(reason)
}

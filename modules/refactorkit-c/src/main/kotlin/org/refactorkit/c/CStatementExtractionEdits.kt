package org.refactorkit.c

import org.refactorkit.core.TextEdit
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Collections

internal enum class CStatementExtractionEditRefusal { SOURCE_CHANGED, INVALID_ENCODING, INPUT_LIMIT, INVALID_FACTS, INVALID_RANGE, INVALID_NAME, NAME_COLLISION, INVALID_CANDIDATE }
internal sealed interface CStatementExtractionValidation {
    data object Accepted : CStatementExtractionValidation
    data class Refused(val reason: CStatementExtractionEditRefusal) : CStatementExtractionValidation
}
internal sealed interface CStatementExtractionEditsResult {
    data class Accepted(val edits: List<TextEdit>, val helperSelection: CFunctionByteSpan, val callSpan: CFunctionByteSpan) : CStatementExtractionEditsResult
    data class Refused(val reason: CStatementExtractionEditRefusal) : CStatementExtractionEditsResult
}

/** Pure edit generation; exact compiler execution and transaction authority are separate. */
internal object CStatementExtractionEdits {
    fun validateCandidate(source: String, before: CStatementExtractionFacts, candidate: String, after: CStatementExtractionFacts,
        family: CFunctionFamilyFacts, helperName: String): CStatementExtractionValidation {
        fun invalid() = CStatementExtractionValidation.Refused(CStatementExtractionEditRefusal.INVALID_CANDIDATE)
        val rendered = when (val result = render(source, before, helperName)) {
            is CStatementExtractionEditsResult.Accepted -> result
            is CStatementExtractionEditsResult.Refused -> return CStatementExtractionValidation.Refused(result.reason)
        }
        if (TextEdits.apply(source, rendered.edits) != candidate) return invalid()
        val bytes = candidate.toByteArray(Charsets.UTF_8)
        val beforeBytes = source.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val names = before.inputs.map { it.name }
        if (after.sourceSha256 != hash || family.sourceSha256 != hash || after.sourcePath.normalize() != before.sourcePath.normalize() ||
            family.sourcePath.normalize() != before.sourcePath.normalize() || after.selection != rendered.helperSelection ||
            after.functionDefinitionSpan.start != before.functionDefinitionSpan.start || after.localNames != before.localNames ||
            after.outputName != before.outputName || after.inputs.map { it.name } != names ||
            after.inputs.map { it.parameterOrdinal } != names.indices.toList() || after.statements.size != before.statements.size ||
            family.name != helperName || family.family != CDirectCallFamilyEvidence.COMPLETE_FILE_STATIC || family.declarations.size != 1 ||
            !family.declarations.single().isDefinition || family.declarations.single().parameters.map { it.name } != names || family.calls.size != 1) return invalid()
        fun fragment(material: ByteArray, span: CFunctionByteSpan): String? =
            if (span.start < 0 || span.endExclusive <= span.start || span.endExclusive > material.size ||
                material[span.start].toInt() and 0xc0 == 0x80 || span.endExclusive < material.size && material[span.endExclusive].toInt() and 0xc0 == 0x80) null
            else material.copyOfRange(span.start, span.endExclusive).toString(Charsets.UTF_8)
        if (before.statements.zip(after.statements).any { (old, new) ->
                fragment(beforeBytes, old) != fragment(bytes, new) || new.start < after.selection.start || new.endExclusive > after.selection.endExclusive
            }) return invalid()
        val call = family.calls.single()
        if (call.span != rendered.callSpan || call.arguments.size != names.size ||
            call.arguments.map { fragment(bytes, it.span) } != names ||
            call.arguments.any { it.effect != CArgumentEffectEvidence.NO_OBSERVABLE_EFFECT }) return invalid()
        return CStatementExtractionValidation.Accepted
    }

    fun render(source: String, facts: CStatementExtractionFacts, helperName: String): CStatementExtractionEditsResult {
        if (source.length > MAX_BYTES || facts.statements.size > 16 || facts.localNames.size > 16 || facts.inputs.size > 64 ||
            facts.ordinaryNames.size > 100_000) return refused(CStatementExtractionEditRefusal.INPUT_LIMIT)
        val bytes = try {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(source))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        } catch (_: java.nio.charset.CharacterCodingException) { return refused(CStatementExtractionEditRefusal.INVALID_ENCODING) }
        if (bytes.size > MAX_BYTES) return refused(CStatementExtractionEditRefusal.INPUT_LIMIT)
        if (MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } != facts.sourceSha256) {
            return refused(CStatementExtractionEditRefusal.SOURCE_CHANGED)
        }
        if (!helperName.matches(Regex("[A-Za-z][A-Za-z_0-9]{0,127}")) || CParameterInsertionEdits.isKeyword(helperName)) {
            return refused(CStatementExtractionEditRefusal.INVALID_NAME)
        }
        val names = facts.inputs.map { it.name }
        if (facts.localNames.isEmpty() || facts.statements.size != facts.localNames.size || facts.localNames.distinct().size != facts.localNames.size ||
            facts.outputName != facts.localNames.last() || names.distinct().size != names.size ||
            (names + facts.localNames).any { it.length !in 1..128 || !it.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")) || CParameterInsertionEdits.isKeyword(it) } ||
            names.any { it in facts.localNames } ||
            facts.inputs.map { it.parameterOrdinal } != facts.inputs.map { it.parameterOrdinal }.distinct().sorted() ||
            facts.inputs.any { it.parameterOrdinal !in 0..63 } || !facts.ordinaryNames.containsAll(names + facts.localNames)) {
            return refused(CStatementExtractionEditRefusal.INVALID_FACTS)
        }
        if (helperName in facts.ordinaryNames) return refused(CStatementExtractionEditRefusal.NAME_COLLISION)
        val definition = facts.functionDefinitionSpan
        val selection = facts.selection
        fun valid(span: CFunctionByteSpan): Boolean = span.start >= 0 && span.endExclusive > span.start && span.endExclusive <= bytes.size &&
            bytes[span.start].toInt() and 0xc0 != 0x80 && (span.endExclusive == bytes.size || bytes[span.endExclusive].toInt() and 0xc0 != 0x80)
        if (!valid(definition) || !valid(selection) || definition.start >= selection.start || selection.endExclusive >= definition.endExclusive ||
            facts.statements.any { !valid(it) || it.start < selection.start || it.endExclusive > selection.endExclusive } ||
            facts.statements.first().start != selection.start || facts.statements.last().endExclusive != selection.endExclusive ||
            facts.statements.zipWithNext().any { (left, right) -> left.endExclusive > right.start } ||
            facts.inputs.any { !valid(it.declarationSpan) || it.declarationSpan.start <= definition.start || it.declarationSpan.endExclusive > selection.start }) {
            return refused(CStatementExtractionEditRefusal.INVALID_RANGE)
        }
        val newlineIndex = source.indexOf('\n')
        val newline = if (newlineIndex > 0 && source[newlineIndex - 1] == '\r') "\r\n" else "\n"
        val selected = bytes.copyOfRange(selection.start, selection.endExclusive).toString(Charsets.UTF_8)
        val characterStart = bytes.copyOfRange(0, selection.start).toString(Charsets.UTF_8).length
        val lineStart = source.lastIndexOf('\n', characterStart - 1) + 1
        val originalIndent = source.substring(lineStart, characterStart)
        val indent = if (originalIndent.length in 1..128 && originalIndent.all { it == ' ' || it == '\t' }) originalIndent else "    "
        val clause = if (names.isEmpty()) "void" else names.joinToString(", ") { "int $it" }
        val header = "static int $helperName($clause) {$newline$indent"
        val helper = "$header$selected$newline${indent}return ${facts.outputName};$newline}$newline$newline"
        val call = "$helperName(${names.joinToString(", ")})"
        val resultPrefix = "int ${facts.outputName} = "
        val replacement = "$resultPrefix$call;"
        val helperBytes = helper.toByteArray(Charsets.UTF_8).size
        val replacementBytes = replacement.toByteArray(Charsets.UTF_8).size
        if (bytes.size - (selection.endExclusive - selection.start) + helperBytes + replacementBytes > MAX_BYTES) {
            return refused(CStatementExtractionEditRefusal.INPUT_LIMIT)
        }
        fun position(offset: Int) = TextEdits.positionForOffset(source, bytes.copyOfRange(0, offset).toString(Charsets.UTF_8).length)
        val insertion = position(definition.start)
        val edits = listOf(TextEdit(SourceRange(insertion, insertion), helper),
            TextEdit(SourceRange(position(selection.start), position(selection.endExclusive)), replacement))
        val helperStart = definition.start + header.toByteArray(Charsets.UTF_8).size
        val callStart = selection.start + helperBytes + resultPrefix.toByteArray(Charsets.UTF_8).size
        return CStatementExtractionEditsResult.Accepted(Collections.unmodifiableList(edits),
            CFunctionByteSpan(helperStart, helperStart + selection.endExclusive - selection.start),
            CFunctionByteSpan(callStart, callStart + call.toByteArray(Charsets.UTF_8).size))
    }

    private const val MAX_BYTES = 1_048_576
    private fun refused(reason: CStatementExtractionEditRefusal) = CStatementExtractionEditsResult.Refused(reason)
}

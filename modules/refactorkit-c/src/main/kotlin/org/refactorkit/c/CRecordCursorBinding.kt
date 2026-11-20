package org.refactorkit.c

import kotlinx.serialization.json.JsonObject
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import java.nio.file.Path

internal enum class CRecordCursorRefusal { INVALID_INPUT, INPUT_LIMIT, QUERY_CONTEXT_UNAVAILABLE, FAMILY_UNAVAILABLE, AMBIGUOUS_BINDING }
internal sealed interface CRecordCursorReadout {
    data class Found(val declaration: CCompilerDeclaration) : CRecordCursorReadout
    data object NotRecordCursor : CRecordCursorReadout
    data class Refused(val reason: CRecordCursorRefusal) : CRecordCursorReadout
}

/** Proves a record cursor in its original captured TU before external navigation proposals. */
internal object CRecordCursorBinding {
    fun resolve(asts: Map<Path, JsonObject>, sources: Map<Path, String>, index: CCompilerDeclarationIndex,
        file: Path, position: SourcePosition, logicalPaths: Map<Path, Path> = sources.keys.associateWith { it },
        prototypeTags: CPrototypeTagNavigationFacts? = null): CRecordCursorReadout {
        return try {
            if (prototypeTags != null && (!prototypeTags.matchesNativeInputs(asts, sources) || !prototypeTags.matchesLogicalPaths(logicalPaths)))
                return CRecordCursorReadout.Refused(CRecordCursorRefusal.INVALID_INPUT)
            val physical = logicalPaths.entries.singleOrNull { it.value == file }?.key
                ?: return CRecordCursorReadout.Refused(CRecordCursorRefusal.INVALID_INPUT)
            val source = sources[physical] ?: return CRecordCursorReadout.Refused(CRecordCursorRefusal.INVALID_INPUT)
            val tokens = CTokenizer().tokenize(source)
            val at = tokens.indexOfFirst { token -> token.type == CTokenType.IDENTIFIER &&
                token.line - 1 == position.line && position.character >= token.column && position.character < token.column + token.text.length }
            if (at < 0) return CRecordCursorReadout.NotRecordCursor
            val tag = tokens.getOrNull(at - 1)?.text?.takeIf { it in setOf("struct", "union", "enum") }
            if (tag == null && index.declarations.none { it.kind == CCompilerDeclarationKind.TYPEDEF && it.name == tokens[at].text })
                return CRecordCursorReadout.NotRecordCursor
            val sourceContext = asts.keys.any { logicalPaths[it] == file }
            if (!sourceContext && !file.toString().endsWith(".h"))
                return CRecordCursorReadout.Refused(CRecordCursorRefusal.QUERY_CONTEXT_UNAVAILABLE)
            val kind = when (tag) {
                "struct" -> CCompilerDeclarationKind.STRUCT
                "union" -> CCompilerDeclarationKind.UNION
                "enum" -> CCompilerDeclarationKind.ENUM
                else -> CCompilerDeclarationKind.TYPEDEF
            }
            val candidates = index.declarations.filter { (!sourceContext || it.translationUnit == file) &&
                it.name == tokens[at].text && it.kind == kind }
            if (candidates.size > 32) return CRecordCursorReadout.Refused(CRecordCursorRefusal.INPUT_LIMIT)
            val matches = mutableListOf<Pair<CCompilerDeclaration, Set<Pair<Path, SourceRange>>>>()
            for (candidate in candidates) {
                val facts = when (val read = CCompilerNavigationFactsDecoder.decode(asts, sources, candidate.id, logicalPaths, prototypeTags)) {
                    is CCompilerNavigationReadout.Found -> read.facts
                    else -> continue
                }
                val containsCursor = (facts.declarations + facts.references).any { site ->
                    site.file == file && site.range.start.line == position.line && site.range.end.line == position.line &&
                        position.character >= site.range.start.character && position.character < site.range.end.character
                }
                if (containsCursor) matches += candidate to facts.declarations.map { it.file to it.range }.toSet()
            }
            val families = matches.groupBy { it.second }
            when (families.size) {
                0 -> if (kind == CCompilerDeclarationKind.TYPEDEF) CRecordCursorReadout.NotRecordCursor
                    else CRecordCursorReadout.Refused(CRecordCursorRefusal.FAMILY_UNAVAILABLE)
                1 -> CRecordCursorReadout.Found(families.values.single().map { it.first }.minWith(
                    compareBy({ if (!sourceContext && it.file == file) 0 else 1 },
                        { it.file.toString() }, { it.range.start.line }, { it.range.start.character }, { it.translationUnit.toString() })))
                else -> CRecordCursorReadout.Refused(CRecordCursorRefusal.AMBIGUOUS_BINDING)
            }
        } catch (_: Exception) {
            CRecordCursorReadout.Refused(CRecordCursorRefusal.INVALID_INPUT)
        }
    }
}

package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ProjectSnapshot
import java.nio.file.Path

/** Refuses clangd rename edits whose spelling belongs to a captured macro origin. */
internal object CMacroOriginRenameGuard {
    const val REFUSAL_CODE = "c.renameMacroGeneratedSymbolUnsupported"

    private val functionLikeDefine = Regex("(?m)^[\\t ]*#[\\t ]*define[\\t ]+([A-Za-z_][A-Za-z0-9_]*)\\(")
    private val literalInclude = Regex("(?m)^[\\t ]*#[\\t ]*include[\\t ]*[<\"]([^>\"]+)[>\"]")

    fun refusal(snapshot: ProjectSnapshot, edits: List<CRenameEdit>): Diagnostic? {
        if (edits.isEmpty()) return null
        val sourceFiles = snapshot.files.filter { it.languageId in C_FAMILY_LANGUAGES }
        val byRelativePath = sourceFiles.associateBy { it.path.normalize() }
        val byAbsolutePath = sourceFiles.associateBy {
            snapshot.workspace.root.resolve(it.path).toAbsolutePath().normalize()
        }
        val macroNamesCache = mutableMapOf<Path, Set<String>>()

        val hasMacroOrigin = edits.any { edit ->
            val editPath = edit.file.toAbsolutePath().normalize()
            val source = byAbsolutePath[editPath] ?: return@any false
            val macroNames = macroNamesCache.getOrPut(source.path.normalize()) {
                visibleFunctionLikeMacros(source.path.normalize(), byRelativePath)
            }
            editTouchesMacroOrigin(edit, source.content, macroNames)
        }
        if (!hasMacroOrigin) return null

        return Diagnostic(
            message = "Rename touches a function-like macro declaration or expansion; macro-origin edits are not qualified",
            severity = Diagnostic.Severity.ERROR,
            code = REFUSAL_CODE,
            evidence = DiagnosticEvidence.STRUCTURAL,
            category = DiagnosticCategory.SAFETY,
        )
    }

    private fun visibleFunctionLikeMacros(
        sourcePath: Path,
        files: Map<Path, org.refactorkit.core.SourceFile>,
    ): Set<String> {
        val pending = ArrayDeque<Path>()
        val visited = linkedSetOf<Path>()
        val names = linkedSetOf<String>()
        pending.add(sourcePath)
        while (pending.isNotEmpty() && visited.size < MAX_INCLUDED_FILES) {
            val currentPath = pending.removeFirst().normalize()
            if (!visited.add(currentPath)) continue
            val source = files[currentPath] ?: continue
            functionLikeDefine.findAll(source.content).forEach { match -> names += match.groupValues[1] }
            literalInclude.findAll(source.content).forEach { match ->
                resolveIncludedFile(currentPath, match.groupValues[1], files)?.let(pending::addLast)
            }
        }
        return names
    }

    private fun resolveIncludedFile(
        sourcePath: Path,
        includeText: String,
        files: Map<Path, org.refactorkit.core.SourceFile>,
    ): Path? {
        val includePath = runCatching { Path.of(includeText).normalize() }.getOrNull() ?: return null
        val relativeCandidate = sourcePath.parent?.resolve(includePath)?.normalize()
        val candidates = listOfNotNull(relativeCandidate, includePath).distinct()
        candidates.firstOrNull(files::containsKey)?.let { return it }
        val suffixMatches = files.keys.filter { it.endsWith(includePath) }
        return suffixMatches.singleOrNull()
    }

    private fun editTouchesMacroOrigin(edit: CRenameEdit, source: String, macroNames: Set<String>): Boolean {
        val directiveLines = directiveLines(source)
        if (edit.startLine in directiveLines || edit.endLine in directiveLines) return true
        if (macroNames.isEmpty()) return false

        val tokens = CTokenizer().tokenize(source)
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.line - 1 in directiveLines || token.type != CTokenType.IDENTIFIER || token.text !in macroNames) continue
            val opening = tokens.getOrNull(index + 1)?.takeIf { it.text == "(" } ?: continue
            if (opening.line - 1 in directiveLines) continue
            val closingIndex = matchingClosingParenthesis(tokens, index + 1) ?: continue
            val closing = tokens[closingIndex]
            val invocationStart = Position(token.line - 1, token.column)
            val invocationEnd = Position(closing.line - 1, closing.column + closing.text.length)
            val editStart = Position(edit.startLine, edit.startCharacter)
            val editEnd = Position(edit.endLine, edit.endCharacter)
            if (overlaps(editStart, editEnd, invocationStart, invocationEnd)) return true
        }
        return false
    }

    private fun matchingClosingParenthesis(tokens: List<CToken>, openingIndex: Int): Int? {
        var depth = 0
        for (index in openingIndex until tokens.size) {
            when (tokens[index].text) {
                "(" -> depth++
                ")" -> {
                    depth--
                    if (depth == 0) return index
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    private fun directiveLines(source: String): Set<Int> {
        val lines = linkedSetOf<Int>()
        var continued = false
        source.split('\n').forEachIndexed { index, line ->
            val startsDirective = !continued && line.trimStart().startsWith('#')
            if (continued || startsDirective) {
                lines += index
                continued = line.trimEnd().endsWith('\\')
            }
        }
        return lines
    }

    private fun overlaps(start: Position, end: Position, otherStart: Position, otherEnd: Position): Boolean {
        if (start == end) return start >= otherStart && start < otherEnd
        return start < otherEnd && otherStart < end
    }

    private data class Position(val line: Int, val character: Int) : Comparable<Position> {
        override fun compareTo(other: Position): Int =
            compareValuesBy(this, other, Position::line, Position::character)
    }

    private const val MAX_INCLUDED_FILES = 512
    private val C_FAMILY_LANGUAGES = setOf("c", "cpp", "objective-c")
}

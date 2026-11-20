package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Bounded C include organization: one repeated quoted include may be removed only
 * when its direct captured local header starts with `#pragma once`. A narrow
 * standard-header pair or a group of direct-captured, declaration-only local
 * headers may be reordered under explicit constraints. Other headers still need
 * per-TU preprocessing evidence; this is containment, not full C14 qualification.
 */
class COrganizeIncludesPlanner {
    fun preview(snapshot: ProjectSnapshot, file: Path): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refused(snapshot, "Source file not found")
        if (source.languageId != "c") {
            return refused(snapshot, "Organize includes currently supports C sources only")
        }
        val directives = CIncludeDirectiveParser().parse(source.content)
        if (directives.any { it.kind == CIncludeKind.MACRO || isMacroTarget(it.target) }) {
            return refused(snapshot, "Macro-computed includes are refused; cannot prove safe rewriting")
        }
        if (inConditionalBlock(source.content, directives)) {
            return refused(snapshot, "Includes inside conditional-compilation blocks are refused")
        }
        val conflict = detectConflict(directives)
        if (conflict != null) {
            return refused(snapshot, "Unresolved include conflict: '$conflict'")
        }
        val repeatedGroups = directives.groupBy { it.kind to it.target }.values.filter { it.size > 1 }
        if (repeatedGroups.isNotEmpty()) {
            previewPragmaOnceDuplicate(snapshot, source, directives)?.let { return it }
            if (repeatedGroups.size > 1 || repeatedGroups.any { it.size > 2 }) {
                return refused(
                    snapshot,
                    "Only one repeated pair in one include group can be removed per plan; multiple groups or larger groups are refused",
                )
            }
            return refused(snapshot, "Repeated includes require proof of no macro effect; deduplication is refused")
        }
        // Quoted headers are order-sensitive in general. Admit only the strict local
        // declaration-only subset whose inputs and C17 command are later captured by
        // the managed diagnostics lease before Apply is authorized.
        if (directives.any { it.kind == CIncludeKind.QUOTED }) {
            previewDeclarationOnlyLocalGroup(snapshot, source, directives)?.let { return it }
            return refused(snapshot, "Quoted includes can be order-sensitive; reordering is refused without proof")
        }
        // <...> is also used for application headers via -I; its delimiter does
        // not prove independence. Even admitted standard headers are refused if
        // source-level macros can alter their behavior. Full per-TU/configuration
        // preprocessing evidence is needed before expanding this subset.
        if (directives.any { it.target !in ORDER_INDEPENDENT_SUBSET } ||
            source.content.lineSequence().any { MACRO_DIRECTIVE.containsMatchIn(it) }) {
            return refused(snapshot, "Include order independence is unproven for custom headers or source macro directives")
        }
        // A header is only removed with proof of non-use. The basename heuristic is
        // not proof (printf/stdio, FILE), so every include is kept and only sorted.
        val kept = directives.sortedWith(compareBy({ it.kind }, { it.target }))
        if (kept.size == directives.size && directives.zip(kept).all { (a, b) -> a.line == b.line && a.target == b.target }) {
            return refused(snapshot, "Includes are already organized; no change needed")
        }
        // Reordering replaces the first..last include span with the sorted block. If a
        // non-blank, non-include line sits inside that span it would be dropped, so the
        // operation is refused instead of silently deleting intervening code.
        includeBlockPreservationRefusal(source.content, directives)?.let { message ->
            return refused(snapshot, message)
        }
        val edits = buildEdits(source.content, directives, kept)
        return PatchPlan(
            operation = "organizeIncludes",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.5,
            requiresUserApproval = true,
            summary = "Organize includes in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Only the admitted standard-header pair was sorted. Full C14 redundancy and preprocessing independence remain unqualified."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    /**
     * Admit one repeated pair of identical quoted directives whose target is a
     * direct captured workspace header with a first-line pragma-once marker.
     * Other unique includes remain untouched. The second repeated directive's
     * token span is removed while its indentation, same-line comment, and newline
     * remain byte-for-byte in place.
     */
    private fun previewPragmaOnceDuplicate(
        snapshot: ProjectSnapshot,
        source: SourceFile,
        directives: List<CIncludeDirective>,
    ): PatchPlan? {
        val repeatedGroups = directives.groupBy { it.kind to it.target }.values.filter { it.size > 1 }
        if (repeatedGroups.size != 1 || repeatedGroups.single().size != 2) return null
        val repeatedPair = repeatedGroups.single().sortedBy { it.line }
        val first = repeatedPair[0]
        val duplicate = repeatedPair[1]
        if (first.kind != CIncludeKind.QUOTED || duplicate.kind != CIncludeKind.QUOTED ||
            first.target != duplicate.target) return null

        val includePath = runCatching { Path.of(duplicate.target) }.getOrNull() ?: return null
        if (includePath.isAbsolute || includePath.startsWith("..") || duplicate.target.contains('\\')) return null
        val sourcePath = source.path.normalize()
        if (sourcePath.isAbsolute || sourcePath.startsWith("..")) return null
        val headerPath = (sourcePath.parent ?: Path.of("")).resolve(includePath).normalize()
        if (headerPath.isAbsolute || headerPath.startsWith("..") || headerPath.fileName?.toString()?.endsWith(".h") != true) return null
        val header = snapshot.files.singleOrNull {
            it.path.normalize() == headerPath && it.languageId == "c"
        } ?: return null
        if (header.content.lineSequence().firstOrNull()?.trim() != "#pragma once") return null

        val lines = source.content.lines()
        val firstLine = lines.getOrNull(first.line - 1) ?: return null
        val duplicateLine = lines.getOrNull(duplicate.line - 1) ?: return null
        val exactIncludePattern = Regex(
            """^([ \t]*)#[ \t]*include[ \t]+"([^"]+)"([ \t]*(?://.*|/\*.*\*/[ \t]*)?)$""",
        )
        val exactFirstInclude = exactIncludePattern.matchEntire(firstLine) ?: return null
        val exactQuotedInclude = exactIncludePattern.matchEntire(duplicateLine) ?: return null
        if (exactFirstInclude.groupValues[2] != first.target || exactQuotedInclude.groupValues[2] != duplicate.target) return null

        val targetRange = exactQuotedInclude.groups[2]?.range ?: return null
        val edit = TextEdit(
            SourceRange(
                SourcePosition(duplicate.line - 1, exactQuotedInclude.groupValues[1].length),
                SourcePosition(duplicate.line - 1, targetRange.last + 2),
            ),
            "",
        )
        return PatchPlan(
            operation = "organizeIncludes",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.72,
            requiresUserApproval = true,
            summary = "Remove the repeated include of '${duplicate.target}' because its captured local header starts with #pragma once",
            affectedFiles = setOf(source.path.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), listOf(edit)))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("The duplicate include was removed because the captured local header begins with #pragma once."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    /**
     * Reorder only a small captured group whose headers contain no preprocessing
     * effects or type dependencies: each direct local .h has at most one leading
     * `#pragma once` and one or more distinct `BUILTIN name(void);` declarations,
     * where each return type is one of the explicitly recognized
     * built-in spellings (`void` or one of the listed scalar types).
     * The managed preview additionally requires every captured C11 or C17 TU
     * (up to the managed limit), with only distinct literal numeric command-line
     * definitions and no include paths or target overrides, before it grants Apply.
     */
    private fun previewDeclarationOnlyLocalGroup(
        snapshot: ProjectSnapshot,
        source: SourceFile,
        directives: List<CIncludeDirective>,
    ): PatchPlan? {
        if (source.languageId != "c" || source.path.fileName?.toString()?.endsWith(".c") != true ||
            directives.size !in 2..16 || directives.any { it.kind != CIncludeKind.QUOTED } ||
            source.content.lineSequence().any { MACRO_DIRECTIVE.containsMatchIn(it) }) return null

        val sourcePath = source.path.normalize()
        if (sourcePath.isAbsolute || sourcePath.startsWith("..")) return null
        val byLine = directives.sortedBy { it.line }
        if (byLine.zipWithNext().any { (left, right) -> right.line != left.line + 1 }) return null
        if (includeBlockPreservationRefusal(source.content, directives) != null) return null

        val declaredNames = mutableSetOf<String>()
        for (directive in directives) {
            if (directive.target.contains('\\')) return null
            val includePath = runCatching { Path.of(directive.target) }.getOrNull() ?: return null
            if (includePath.isAbsolute || includePath.startsWith("..") ||
                includePath.fileName?.toString()?.endsWith(".h") != true) return null
            val headerPath = (sourcePath.parent ?: Path.of("")).resolve(includePath).normalize()
            if (headerPath.isAbsolute || headerPath.startsWith("..")) return null
            val header = snapshot.files.singleOrNull {
                it.path.normalize() == headerPath && it.languageId == "c"
            } ?: return null
            if (header.content.toByteArray(Charsets.UTF_8).size > MAX_DECLARATION_HEADER_BYTES) return null
            val statements = header.content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val declarationLines = if (statements.firstOrNull() == "#pragma once") statements.drop(1) else statements
            if (declarationLines.isEmpty()) return null
            for (declaration in declarationLines) {
                val declarationMatch = DECLARATION_ONLY_FUNCTION.matchEntire(declaration) ?: return null
                if (declarationMatch.groupValues[1] !in BUILTIN_PROTOTYPE_RETURN_TYPES) return null
                val name = declarationMatch.groupValues[2]
                if (name in C_KEYWORDS || name.startsWith("_") || !declaredNames.add(name)) return null
            }
        }

        val sorted = directives.sortedBy { it.target }
        if (directives.zip(sorted).all { (left, right) -> left.target == right.target }) return null
        return PatchPlan(
            operation = "organizeIncludes",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.68,
            requiresUserApproval = true,
            summary = "Reorder directly captured local headers containing independent built-in return-type function prototypes",
            affectedFiles = setOf(source.path.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(source.path.normalize(), buildEdits(source.content, directives, sorted)))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Only contiguous captured local headers with distinct recognized built-in return-type prototypes are admitted; typedefs, other declarations and broader C14 preprocessing independence remain unqualified."),
            riskLevel = RiskLevel.HIGH,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    /**
     * Returns a refusal message when blank spacing or another non-include line sits
     * between the first and last include directive; replacing that span would drop
     * original source text. Otherwise returns null.
     */
    private fun includeBlockPreservationRefusal(content: String, directives: List<CIncludeDirective>): String? {
        if (directives.isEmpty()) return null
        val lines = content.lines()
        val includeLines = directives.map { it.line - 1 }.toHashSet()
        val first = includeLines.minOrNull() ?: return null
        val last = includeLines.maxOrNull() ?: return null
        for (index in first..last) {
            if (index in includeLines) continue
            val line = lines.getOrNull(index) ?: continue
            if (line.isBlank()) return "Include directives are separated by a blank line; refusing to remove original spacing"
            val trimmed = line.trim()
            return "Include directives are not contiguous; line '$trimmed' would be dropped by reordering"
        }
        return null
    }

    private fun isMacroTarget(target: String): Boolean =
        !target.contains('.') || target.all { !it.isLowerCase() }

    private fun inConditionalBlock(content: String, directives: List<CIncludeDirective>): Boolean {
        val lines = content.lines()
        var depth = 0
        for (directive in directives) {
            // scan lines up to the directive for #if/#ifdef/#ifndef/#else/#endif
            for (i in 0 until directive.line) {
                val trimmed = lines.getOrNull(i)?.trim() ?: continue
                when {
                    trimmed.startsWith("#if ") || trimmed.startsWith("#ifdef ") || trimmed.startsWith("#ifndef ") -> depth++
                    trimmed.startsWith("#endif") -> depth--
                    trimmed.startsWith("#else") -> if (depth > 0) return true
                }
            }
            if (depth > 0) return true
        }
        return false
    }

    private fun detectConflict(directives: List<CIncludeDirective>): String? {
        val seen = mutableMapOf<String, String>()
        for (d in directives) {
            val key = d.target
            val existing = seen[key]
            val resolved = d.resolved?.toString() ?: ""
            if (existing != null && existing != "${d.kind}:$resolved") {
                return "${d.target} included as $existing and ${d.kind}:$resolved"
            }
            seen[key] = "${d.kind}:$resolved"
        }
        return null
    }

    private fun buildEdits(content: String, directives: List<CIncludeDirective>, kept: List<CIncludeDirective>): List<TextEdit> {
        val lines = content.lines()
        val edits = mutableListOf<TextEdit>()
        // Reorder by replacing the whole contiguous include block (first..last include
        // line) with the sorted block, so no kept include line is left duplicated.
        val includeLines = directives.map { it.line - 1 }.sorted()
        if (includeLines.isEmpty()) return edits
        val firstInclude = includeLines.first()
        val lastInclude = includeLines.last()
        val lastLineText = lines.getOrNull(lastInclude) ?: return edits
        val sortedBlock = kept.joinToString("\n") { directive ->
            lines.getOrNull(directive.line - 1) ?: ""
        }
        edits += TextEdit(
            SourceRange(SourcePosition(firstInclude, 0), SourcePosition(lastInclude, lastLineText.length)),
            sortedBlock,
        )
        return edits
    }

    private companion object {
        val ORDER_INDEPENDENT_SUBSET = setOf("stdio.h", "stdlib.h")
        val MACRO_DIRECTIVE = Regex("""^\s*#\s*(define|undef|pragma|include_next|line)\b""")
        val DECLARATION_ONLY_FUNCTION = Regex("""^([A-Za-z_][A-Za-z0-9_]*(?:[ \t]+[A-Za-z_][A-Za-z0-9_]*)*)[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*\([ \t]*void[ \t]*\);$""")
        val BUILTIN_PROTOTYPE_RETURN_TYPES = setOf(
            "void", "_Bool", "char", "signed char", "unsigned char",
            "short", "short int", "signed short", "signed short int", "unsigned short", "unsigned short int",
            "int", "signed", "signed int", "unsigned", "unsigned int",
            "long", "long int", "signed long", "signed long int", "unsigned long", "unsigned long int",
            "long long", "long long int", "signed long long", "signed long long int",
            "unsigned long long", "unsigned long long int", "float", "double", "long double",
        )
        val C_KEYWORDS = setOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
            "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "restrict", "return",
            "short", "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void",
            "volatile", "while", "_Alignas", "_Alignof", "_Atomic", "_Bool", "_Complex", "_Generic", "_Imaginary",
            "_Noreturn", "_Static_assert", "_Thread_local",
        )
        const val MAX_DECLARATION_HEADER_BYTES = 16 * 1024
    }

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "organizeIncludes",
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = message,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(message),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = "c.includeOrder.refused",
    )
}

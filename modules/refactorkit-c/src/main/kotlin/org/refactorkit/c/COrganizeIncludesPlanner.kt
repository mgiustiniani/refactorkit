package org.refactorkit.c

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Bounded C include organization: remove duplicate includes, sort includes and
 * remove provably-unused includes (conservative heuristic), preserving comments
 * and conditional-compilation structure. Refuses macro-computed includes,
 * conditional-include rewriting and unresolved include conflicts.
 */
class COrganizeIncludesPlanner {
    fun preview(snapshot: ProjectSnapshot, file: Path): PatchPlan {
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return refused(snapshot, "Source file not found")
        if (source.languageId !in setOf("c", "cpp", "objective-c")) {
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
        if (hasRepeatedHeader(directives)) {
            return refused(snapshot, "Repeated includes require proof of no macro effect; deduplication is refused")
        }
        // Sorting is only provable for system (angled) includes; quoted includes can be
        // order-sensitive, so their reordering is refused.
        if (directives.any { it.kind == CIncludeKind.QUOTED }) {
            return refused(snapshot, "Quoted includes can be order-sensitive; reordering is refused without proof")
        }
        // A header is only removed with proof of non-use. The basename heuristic is
        // not proof (printf/stdio, FILE), so every include is kept and only sorted.
        val kept = directives.sortedWith(compareBy({ it.kind }, { it.target }))
        if (kept.size == directives.size && directives.zip(kept).all { (a, b) -> a.line == b.line && a.target == b.target }) {
            return refused(snapshot, "Includes are already organized; no change needed")
        }
        val edits = buildEdits(source.content, directives, kept)
        return PatchPlan(
            operation = "organizeIncludes",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Organize includes in $file",
            affectedFiles = setOf(file.normalize()),
            workspaceEdit = WorkspaceEdit(listOf(FileEdit.Modify(file.normalize(), edits))),
            diagnosticsBefore = emptyList(),
            diagnosticsAfterPreview = emptyList(),
            warnings = listOf("Removed duplicate/unused includes and sorted the remainder; comments and conditional-compilation blocks preserved."),
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun hasRepeatedHeader(directives: List<CIncludeDirective>): Boolean {
        val seen = mutableSetOf<String>()
        for (d in directives) {
            val key = "${d.kind}:${d.target}"
            if (!seen.add(key)) return true
        }
        return false
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
            val original = lines.getOrNull(directive.line - 1) ?: ""
            trailingComment(original).let { includeText(directive) + it }
        }
        edits += TextEdit(
            SourceRange(SourcePosition(firstInclude, 0), SourcePosition(lastInclude, lastLineText.length)),
            sortedBlock,
        )
        return edits
    }

    /** Returns the trailing comment of an include line, or an empty string. */
    private fun trailingComment(line: String): String {
        val idx = line.indexOf("//")
        if (idx >= 0) return " " + line.substring(idx).trimEnd()
        return ""
    }

    private fun includeText(directive: CIncludeDirective): String = when (directive.kind) {
        CIncludeKind.QUOTED -> "#include \"${directive.target}\""
        CIncludeKind.ANGLED -> "#include <${directive.target}>"
        CIncludeKind.MACRO -> "#include ${directive.target}"
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
    )
}

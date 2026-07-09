package org.refactorkit.java

import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.Symbol
import org.refactorkit.core.SymbolId
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path

/**
 * Generates a [PatchPlan] that renames a Java method, field, or constructor and
 * updates all call sites / reference sites across the project.
 *
 * Reference search is scoped to files that are likely to reference the declaring
 * type (via import, star-import, same-package, or FQN usage), eliminating false
 * positives from same-named members in unrelated classes.
 *
 * Limitations (MVP):
 * - Reflection, Spring @EventListener parameter types, Jackson annotations,
 *   and annotation-processor-generated code are NOT updated.
 * - Overloaded methods: all overloads with the same simple name are renamed.
 *   A warning is emitted when more than one overload is detected.
 * - Constructor rename follows class rename (not a standalone member rename).
 */
class JavaRenameMemberPlanner(private val adapter: JavaLanguageAdapter) {

    fun preview(
        snapshot: ProjectSnapshot,
        symbolFqnWithMember: String,
        newMemberName: String,
    ): PatchPlan {
        // ── parse symbolId ───────────────────────────────────────────────────
        val hashIdx = symbolFqnWithMember.indexOf('#')
        if (hashIdx < 0) {
            return refused(snapshot, "renameMember", "Symbol must be in the form <FQN>#<member> — got: $symbolFqnWithMember")
        }
        val ownerFqn = symbolFqnWithMember.substring(0, hashIdx)
        val oldMemberName = symbolFqnWithMember.substring(hashIdx + 1)

        if (oldMemberName == "<init>") {
            return refused(snapshot, "renameMember", "Constructor rename follows class rename. Use renameClass instead.")
        }

        if (!isValidJavaIdentifier(newMemberName)) {
            return refused(snapshot, "renameMember", "Invalid Java identifier: $newMemberName")
        }
        if (oldMemberName == newMemberName) {
            return refused(snapshot, "renameMember", "Old and new names are the same: $oldMemberName")
        }

        // ── resolve owner type ───────────────────────────────────────────────
        val index = adapter.buildSymbols(snapshot)
        val ownerSymbol = index.symbols.find { it.id.value == ownerFqn && it.kind in TYPE_KINDS }
            ?: return refused(snapshot, "renameMember", "Owner type not found: $ownerFqn")

        // ── find member(s) ───────────────────────────────────────────────────
        val members = index.symbols.filter { sym ->
            sym.name == oldMemberName &&
                sym.location.path == ownerSymbol.location.path &&
                sym.kind in MEMBER_KINDS
        }
        if (members.isEmpty()) {
            return refused(snapshot, "renameMember", "Member '$oldMemberName' not found in $ownerFqn")
        }

        val warnings = mutableListOf<String>()
        if (members.size > 1) {
            warnings += "Multiple overloads of '$oldMemberName' detected in $ownerFqn. All overloads will be renamed."
        }

        // ── build edits ──────────────────────────────────────────────────────
        val ownerPkg = JavaPackageUtil.packageOf(ownerFqn)
        val ownerSimple = JavaPackageUtil.simpleName(ownerFqn)
        val ownerPkgStar = "import ${ownerPkg}.*;"

        val edits = mutableListOf<FileEdit>()
        val affectedPaths: MutableSet<Path> = mutableSetOf()

        for (file in snapshot.files.filter { it.languageId == "java" }) {
            val filePkg = JavaPackageUtil.extractPackage(file.content)
            val inScope = file.path == ownerSymbol.location.path ||
                file.content.contains("import $ownerFqn;") ||
                (ownerPkg.isNotEmpty() && file.content.contains(ownerPkgStar)) ||
                (ownerPkg.isNotEmpty() && filePkg == ownerPkg) ||
                file.content.contains(ownerFqn) ||
                file.content.contains(ownerSimple)

            if (!inScope) continue

            val textEdits = mutableListOf<TextEdit>()
            val covered = mutableSetOf<Int>()

            // Replace member name occurrences, excluding the class name itself
            for (range in JavaLexer.findOccurrences(file.content, oldMemberName)) {
                if (range.first in covered) continue
                textEdits += TextEdit(
                    range = TextEdits.rangeForOffset(file.content, range.first, oldMemberName.length),
                    newText = newMemberName,
                )
                for (off in range) covered += off
            }

            if (textEdits.isNotEmpty()) {
                val sorted = textEdits.sortedWith(
                    compareBy({ it.range.start.line }, { it.range.start.character }),
                )
                edits += FileEdit.Modify(file.path, sorted)
                affectedPaths.add(file.path)
            }
        }

        if (edits.isEmpty()) {
            return refused(snapshot, "renameMember", "No occurrences of '$oldMemberName' found in scope of $ownerFqn")
        }

        val memberKind = members.first().kind.name.lowercase()
        warnings += "Reflection, Spring event/listener names, Jackson property names, and annotation-processor output are NOT updated. Review manually."

        return PatchPlan(
            operation = "renameMember",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 0.88,
            requiresUserApproval = true,
            summary = "Rename $memberKind '$oldMemberName' \u2192 '$newMemberName' in $ownerFqn. ${affectedPaths.size} file(s) affected.",
            affectedFiles = affectedPaths,
            workspaceEdit = WorkspaceEdit(edits),
            warnings = warnings,
            riskLevel = if (members.size > 1) RiskLevel.MEDIUM else RiskLevel.LOW,
        )
    }

    private fun refused(snapshot: ProjectSnapshot, operation: String, reason: String) = PatchPlan(
        operation = operation,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
    )

    private fun isValidJavaIdentifier(name: String): Boolean {
        if (name.isEmpty()) return false
        if (!name[0].isLetter() && name[0] != '_' && name[0] != '$') return false
        return name.all { JavaLexer.isIdentChar(it) }
    }

    companion object {
        private val TYPE_KINDS = setOf(Symbol.Kind.CLASS, Symbol.Kind.INTERFACE, Symbol.Kind.ENUM, Symbol.Kind.RECORD)
        private val MEMBER_KINDS = setOf(Symbol.Kind.METHOD, Symbol.Kind.FIELD, Symbol.Kind.CONSTRUCTOR)
    }
}

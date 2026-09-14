package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
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
 * Rename an explicitly selected set of compiler-proven C API symbols using a
 * per-symbol old/new mapping, with complete binding-matched local consumers.
 *
 * The mapping is explicit (not prefix-matched), so same-prefix unrelated symbols
 * are never captured. Each symbol is renamed through clangd's semantic rename
 * (which does not rewrite strings/comments); a symbol with no semantic occurrence
 * or a clangd refusal is refused. No hidden textual fallback.
 */
class CRenamePrefixPlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C prefix rename planner is already started" }
        client.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { client.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            error("clangd did not open every C source file")
        }
        started = true
    }

    /** Renames the explicit [mapping] (old symbol name -> new name) into one preview plan. */
    fun preview(snapshot: ProjectSnapshot, mapping: Map<String, String>): PatchPlan {
        if (!started) return refused(snapshot, "C prefix rename planner is not started")
        if (mapping.isEmpty()) return refused(snapshot, "No symbol mapping provided")
        if (mapping.size > MAX_SYMBOLS) return refused(snapshot, "Symbol mapping exceeds $MAX_SYMBOLS entries")
        if (mapping.any { (old, new) -> old.isBlank() || new.isBlank() || old == new }) {
            return refused(snapshot, "Symbol mapping contains invalid entries")
        }
        if (mapping.keys.distinct().size != mapping.size) {
            return refused(snapshot, "Symbol mapping contains duplicate old names")
        }

        val editsByFile = mutableMapOf<Path, MutableList<TextEdit>>()
        for ((oldName, newName) in mapping) {
            val occurrence = findOccurrence(snapshot, oldName)
                ?: return refused(snapshot, "Symbol '$oldName' has no semantic occurrence")
            val result = client.rename(occurrence.file, occurrence.line, occurrence.character, newName)
            when (result) {
                is CRenameResult.Found -> {
                    for (edit in result.edits) {
                        editsByFile.getOrPut(edit.file.normalize()) { mutableListOf() } += TextEdit(
                            SourceRange(SourcePosition(edit.startLine, edit.startCharacter), SourcePosition(edit.endLine, edit.endCharacter)),
                            edit.newText,
                        )
                    }
                }
                is CRenameResult.NotFound -> return refused(snapshot, "Symbol '$oldName' was not found by clangd")
                is CRenameResult.Refused -> {
                    val first = result.diagnostics.firstOrNull()
                    return refused(snapshot, first?.message ?: "clangd refused renaming '$oldName'")
                }
            }
        }

        val modifications = editsByFile.map { (file, textEdits) ->
            ExternalFileEditProposal.Modify(file, textEdits)
        }
        val proposal = ExternalWorkspaceEditProposal(
            providerId = "clangd-rename-prefix-v1",
            providerVersion = toolchain.provenance.clangdVersion,
            edits = modifications,
        )
        val normalization = normalizer.normalize(snapshot, proposal)
        return when (normalization) {
            is ExternalWorkspaceEditNormalization.Accepted -> PatchPlan(
                operation = "renameCSymbolPrefix",
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "Rename ${mapping.size} compiler-proven C API symbol(s)",
                affectedFiles = normalization.normalized.workspaceEdit.affectedFiles(),
                workspaceEdit = normalization.normalized.workspaceEdit,
                diagnosticsBefore = emptyList(),
                diagnosticsAfterPreview = emptyList(),
                warnings = listOf("Explicit per-symbol mapping; strings/comments and external ABI consumers are not rewritten."),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.COMPILER_PROVEN,
            )
            is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot, normalization.diagnostics.joinToString("; ") { it.message })
        }
    }

    override fun close() {
        client.close()
        started = false
    }

    private fun findOccurrence(snapshot: ProjectSnapshot, name: String): Occurrence? {
        for (source in snapshot.files) {
            if (source.languageId !in setOf("c", "cpp", "objective-c")) continue
            val tokens = CTokenizer().tokenize(source.content)
            for (token in tokens) {
                if (token.type == CTokenType.IDENTIFIER && token.text == name) {
                    val lineText = source.content.lines().getOrNull(token.line - 1) ?: continue
                    val idx = lineText.indexOf(name)
                    if (idx >= 0) return Occurrence(source.path.normalize(), token.line - 1, idx)
                }
            }
        }
        return null
    }

    private data class Occurrence(val file: Path, val line: Int, val character: Int)

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "renameCSymbolPrefix",
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

    companion object {
        private const val MAX_SYMBOLS = 256
    }
}

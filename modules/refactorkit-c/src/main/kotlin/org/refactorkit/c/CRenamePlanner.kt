package org.refactorkit.c

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.DiagnosticCategory
import org.refactorkit.core.DiagnosticEvidence
import org.refactorkit.core.ExternalFileEditProposal
import org.refactorkit.core.ExternalSemanticProcessManager
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.ExternalWorkspaceEditNormalizer
import org.refactorkit.core.ExternalWorkspaceEditProposal
import org.refactorkit.core.NormalizedExternalWorkspaceEdit
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.SourcePosition
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import java.nio.file.Path

/** C rename planner result: accepted normalized edit or an explicit refusal. */
sealed interface CRenamePlannerResult {
    data class Accepted(val normalized: NormalizedExternalWorkspaceEdit) : CRenamePlannerResult
    data class Refused(val diagnostics: List<Diagnostic>) : CRenamePlannerResult
}

/**
 * Semantic C symbol rename through clangd's `textDocument/rename`, normalized into
 * the core edit proposal and validated by [ExternalWorkspaceEditNormalizer].
 *
 * clangd resolves the exact declaration and binding-matched uses, including
 * multifile header consumers. Refusals cover collision, shadowing/linkage,
 * stale inputs, macro-generated symbols and incomplete consumer sets — clangd
 * either returns binding-matched edits or a refusal; there is no textual fallback.
 */
class CRenamePlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false

    /** Launches clangd and opens every C source file in the snapshot. */
    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C rename planner is already started" }
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

    fun rename(file: Path, line: Int, character: Int, newName: String, snapshot: ProjectSnapshot): CRenamePlannerResult {
        if (!started) return refused(listOf(notStartedDiagnostic()))
        val result = client.rename(file, line, character, newName)
        return when (result) {
            is CRenameResult.Found -> {
                val proposal = buildProposal(result.edits)
                val normalization = normalizer.normalize(snapshot, proposal)
                when (normalization) {
                    is ExternalWorkspaceEditNormalization.Accepted -> CRenamePlannerResult.Accepted(normalization.normalized)
                    is ExternalWorkspaceEditNormalization.Refused -> CRenamePlannerResult.Refused(normalization.diagnostics)
                }
            }
            is CRenameResult.NotFound -> CRenamePlannerResult.Refused(listOf(
                Diagnostic(message = "No symbol matched for rename", severity = Diagnostic.Severity.ERROR, code = "clang.renameNotFound", evidence = DiagnosticEvidence.COMPILER, category = DiagnosticCategory.TYPE_RESOLUTION),
            ))
            is CRenameResult.Refused -> CRenamePlannerResult.Refused(result.diagnostics)
        }
    }

    override fun close() {
        client.close()
        started = false
    }

    private fun buildProposal(edits: List<CRenameEdit>): ExternalWorkspaceEditProposal {
        val byFile = edits.groupBy { it.file }
        val modifications = byFile.map { (file, fileEdits) ->
            ExternalFileEditProposal.Modify(file, fileEdits.map { edit ->
                TextEdit(SourceRange(
                    SourcePosition(edit.startLine, edit.startCharacter),
                    SourcePosition(edit.endLine, edit.endCharacter),
                ), edit.newText)
            })
        }
        return ExternalWorkspaceEditProposal(
            providerId = "clangd-rename-v1",
            providerVersion = toolchain.provenance.clangdVersion,
            edits = modifications,
        )
    }

    private fun notStartedDiagnostic() = Diagnostic(
        message = "C rename planner is not started", severity = Diagnostic.Severity.ERROR, code = "clang.renameNotStarted",
        evidence = DiagnosticEvidence.STRUCTURAL, category = DiagnosticCategory.TYPE_RESOLUTION,
    )

    private fun refused(diagnostics: List<Diagnostic>) = CRenamePlannerResult.Refused(diagnostics)
}

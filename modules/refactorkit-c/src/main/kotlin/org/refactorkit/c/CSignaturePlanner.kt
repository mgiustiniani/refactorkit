package org.refactorkit.c

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
 * Bounded C function-signature changes for fully prototyped, non-variadic
 * direct-call families.
 *
 * First positive target: rename a parameter, updating the prototype, definition
 * and body references through clangd's semantic rename. Refusals cover variadics,
 * old-style declarations, address-taking/callback families, side-effectful
 * argument reordering and unavailable binary consumers.
 */
class CSignaturePlanner(
    private val toolchain: ClangSemanticToolchain,
    private val processManager: ExternalSemanticProcessManager = ExternalSemanticProcessManager(),
    private val normalizer: ExternalWorkspaceEditNormalizer = ExternalWorkspaceEditNormalizer(),
) : AutoCloseable {
    private val client = ClangdSemanticClient(toolchain, processManager)
    private var started = false

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C signature planner is already started" }
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

    /** Renames a parameter, updating the prototype, definition and body references. */
    fun renameParameter(snapshot: ProjectSnapshot, file: Path, oldParam: String, newParam: String): PatchPlan {
        if (!started) return refused(snapshot, "C signature planner is not started")
        if (oldParam.isBlank() || newParam.isBlank() || oldParam == newParam) {
            return refused(snapshot, "Parameter rename mapping is invalid")
        }
        val occurrence = findParameterOccurrence(snapshot, file, oldParam)
            ?: return refused(snapshot, "Parameter '$oldParam' was not found in the function signature")
        val result = client.rename(occurrence.file, occurrence.line, occurrence.character, newParam)
        return when (result) {
            is CRenameResult.Found -> {
                val proposal = ExternalWorkspaceEditProposal(
                    providerId = "clangd-rename-parameter-v1",
                    providerVersion = toolchain.provenance.clangdVersion,
                    edits = result.edits.groupBy { it.file.normalize() }.map { (file, edits) ->
                        ExternalFileEditProposal.Modify(file, edits.map { edit ->
                            TextEdit(SourceRange(SourcePosition(edit.startLine, edit.startCharacter), SourcePosition(edit.endLine, edit.endCharacter)), edit.newText)
                        })
                    },
                )
                val normalization = normalizer.normalize(snapshot, proposal)
                when (normalization) {
                    is ExternalWorkspaceEditNormalization.Accepted -> PatchPlan(
                        operation = "changeSignature",
                        status = PatchStatus.PREVIEW,
                        snapshotHash = snapshot.hash,
                        confidence = 1.0,
                        requiresUserApproval = true,
                        summary = "Rename parameter '$oldParam' to '$newParam' in $file",
                        affectedFiles = normalization.normalized.workspaceEdit.affectedFiles(),
                        workspaceEdit = normalization.normalized.workspaceEdit,
                        diagnosticsBefore = emptyList(),
                        diagnosticsAfterPreview = emptyList(),
                        warnings = listOf("Parameter rename applied to prototype, definition and body references; call sites are positional and unchanged."),
                        riskLevel = RiskLevel.MEDIUM,
                        evidence = RefactoringEvidence.COMPILER_PROVEN,
                    )
                    is ExternalWorkspaceEditNormalization.Refused -> refused(snapshot, normalization.diagnostics.joinToString("; ") { it.message })
                }
            }
            is CRenameResult.NotFound -> refused(snapshot, "Parameter '$oldParam' was not found by clangd")
            is CRenameResult.Refused -> refused(snapshot, result.diagnostics.firstOrNull()?.message ?: "clangd refused renaming '$oldParam'")
        }
    }

    override fun close() {
        client.close()
        started = false
    }

    private fun findParameterOccurrence(snapshot: ProjectSnapshot, file: Path, name: String): Occurrence? {
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return null
        val tokens = CTokenizer().tokenize(source.content)
        for (token in tokens) {
            if (token.type == CTokenType.IDENTIFIER && token.text == name) {
                val lineText = source.content.lines().getOrNull(token.line - 1) ?: continue
                val idx = lineText.indexOf(name)
                if (idx >= 0) return Occurrence(source.path.normalize(), token.line - 1, idx)
            }
        }
        return null
    }

    private data class Occurrence(val file: Path, val line: Int, val character: Int)

    private fun refused(snapshot: ProjectSnapshot, message: String) = PatchPlan(
        operation = "changeSignature",
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

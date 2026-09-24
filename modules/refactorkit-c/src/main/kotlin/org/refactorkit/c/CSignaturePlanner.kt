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
    private var startFailure: String? = null

    fun start(snapshot: ProjectSnapshot) {
        require(!started) { "C signature planner is already started" }
        client.start(snapshot.workspace.root)
        val opened = snapshot.files
            .filter { it.languageId in setOf("c", "cpp", "objective-c") }
            .sortedBy { it.path.toString() }
            .all { client.didOpen(snapshot.workspace.root.resolve(it.path), it.content) }
        if (!opened) {
            close()
            startFailure = "clangd did not open every C source file"
            return
        }
        started = true
    }

    /** Returns the typed start failure, or null when the planner started successfully. */
    fun startFailure(): String? = startFailure

    /** Renames a parameter, updating the prototype, definition and body references. */
    fun renameParameter(snapshot: ProjectSnapshot, file: Path, oldParam: String, newParam: String): PatchPlan {
        if (!started) return refused(snapshot, startFailure ?: "C signature planner is not started")
        if (oldParam.isBlank() || newParam.isBlank() || oldParam == newParam) {
            return refused(snapshot, "Parameter rename mapping is invalid")
        }
        val analysis = analyzeSignature(snapshot, file, oldParam)
        val occurrence = when (analysis) {
            is SignatureAnalysis.Found -> Occurrence(file.normalize(), analysis.line, analysis.character)
            is SignatureAnalysis.Refused -> return refused(snapshot, analysis.message)
        }
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

    /**
     * Validates a parameter rename against the function signature in [file] and
     * locates the parameter inside the parameter list, not the first same-name
     * identifier in the body.
     *
     * Refusals cover variadic and old-style (K&R) declarations, which cannot be
     * renamed safely, and a parameter that is absent from the signature.
     */
    internal fun analyzeSignature(snapshot: ProjectSnapshot, file: Path, paramName: String): SignatureAnalysis {
        val source = snapshot.files.singleOrNull { it.path.normalize() == file.normalize() }
            ?: return SignatureAnalysis.Refused("File '$file' is not part of the snapshot")
        val text = source.content
        val lines = text.lines()
        // Collect every candidate signature line that declares paramName as a parameter.
        // A single unambiguous signature is required; silently renaming the first match
        // could target the wrong function when the same parameter name recurs.
        val candidates = mutableListOf<SignatureAnalysis>()
        for (lineIndex in lines.indices) {
            val line = lines[lineIndex]
            val open = line.indexOf('(')
            if (open < 0) continue
            val close = line.indexOf(')', open)
            if (close < 0) continue
            val paramsText = line.substring(open + 1, close).trim()
            if (paramsText.isEmpty() || paramsText == "void") continue
            val params = paramsText.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (params.isEmpty()) continue
            val isVariadic = params.any { it == "..." || it.startsWith("...") }
            val isOldStyle = params.any { it.split(' ').size < 2 && !it.startsWith("...") }
            val paramIndex = params.indexOfFirst { param ->
                param.split(' ').lastOrNull()?.replace("*", "")?.trim() == paramName
            }
            if (paramIndex < 0) continue
            val char = line.indexOf(paramName, open + 1)
            if (char < 0) continue
            candidates += when {
                isVariadic -> SignatureAnalysis.Refused("Variadic function signature cannot be safely renamed")
                isOldStyle -> SignatureAnalysis.Refused("Old-style (K&R) signature cannot be safely renamed")
                else -> SignatureAnalysis.Found(lineIndex, char, paramIndex, params.size)
            }
        }
        if (candidates.isEmpty()) {
            return SignatureAnalysis.Refused("Parameter '$paramName' was not found in the function signature")
        }
        if (candidates.size > 1) {
            return SignatureAnalysis.Refused(
                "Parameter '$paramName' appears in ${candidates.size} function signatures; the target is ambiguous",
            )
        }
        return candidates.single()
    }

    internal sealed interface SignatureAnalysis {
        data class Found(val line: Int, val character: Int, val paramIndex: Int, val paramCount: Int) : SignatureAnalysis
        data class Refused(val message: String) : SignatureAnalysis
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

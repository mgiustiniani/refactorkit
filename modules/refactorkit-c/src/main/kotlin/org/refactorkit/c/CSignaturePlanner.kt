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
            val region = line.substring(open + 1, close)
            val segments = topLevelParamSegments(region)
            if (segments.isEmpty()) continue
            val segTexts = segments.map { region.substring(it).trim() }
            // The parameter name is the declarator token: the last whitespace-separated
            // token of the segment, with pointer/type qualifiers stripped.
            val names = segTexts.map { it.split(' ').lastOrNull()?.replace("*", "")?.trim() ?: "" }
            val paramIndex = names.indexOf(paramName)
            if (paramIndex < 0) continue
            val isVariadic = segTexts.any { it == "..." || it.startsWith("...") }
            val isOldStyle = segTexts.any { !it.startsWith("...") && it.split(' ').size < 2 }
            // Seed at the parameter's own declarator position within its segment, not the
            // first substring of paramName in the line: an earlier parameter whose type or
            // name contains paramName (e.g. 'ext' before 'x') would otherwise bind clangd
            // to the wrong declarator and rename the wrong parameter.
            val seg = segments[paramIndex]
            val localIdx = region.substring(seg).lastIndexOf(paramName)
            if (localIdx < 0) continue
            val char = open + 1 + seg.first + localIdx
            candidates += when {
                isVariadic -> SignatureAnalysis.Refused("Variadic function signature cannot be safely renamed")
                isOldStyle -> SignatureAnalysis.Refused("Old-style (K&R) signature cannot be safely renamed")
                else -> SignatureAnalysis.Found(lineIndex, char, paramIndex, segments.size)
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

    /**
     * Splits a parameter region into top-level segments (respecting nested () and [])
     * and drops blank segments, returning their ranges within [region].
     */
    private fun topLevelParamSegments(region: String): List<IntRange> {
        val segments = mutableListOf<IntRange>()
        var depth = 0
        var start = 0
        for (k in region.indices) {
            when (region[k]) {
                '(', '[' -> depth++
                ']', ')' -> depth--
                ',' -> if (depth == 0) {
                    if (region.substring(start, k).isNotBlank()) segments += start until k
                    start = k + 1
                }
            }
        }
        if (region.substring(start, region.length).isNotBlank()) segments += start until region.length
        return segments
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

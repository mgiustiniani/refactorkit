package org.refactorkit.typescript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SemanticProcessProvenance
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import java.security.MessageDigest

/** An observed compiler exchange, not authority inferred from successful normalization. */
data class TypeScriptCompilerMutationEvidence(
    val command: String,
    val snapshotHash: String,
    val workspaceEditSha256: String,
    val arguments: Map<String, String>,
    val process: SemanticProcessProvenance,
    val returnedActionSha256: String? = null,
)

/** TypeScript application policy shared by compiler-owned advanced previews. */
internal class TypeScriptCompilerPreview(
    private val toolchain: TypeScriptSemanticToolchain,
    private val model: TypeScriptProjectModel,
    private val sessionId: String?,
    private val diagnostics: (ProjectSnapshot) -> ExternalSemanticDiagnostics,
    private val ownership: (ProjectSnapshot, WorkspaceEdit) -> List<Diagnostic>,
    private val evidence: () -> TypeScriptCompilerMutationEvidence?,
    private val approve: (String) -> Unit,
) {
    fun preview(
        snapshot: ProjectSnapshot,
        operation: String,
        command: String,
        arguments: Map<String, String>,
        proposal: () -> PatchPlan,
    ): PatchPlan {
        val before = when (val result = diagnostics(snapshot)) {
            is ExternalSemanticDiagnostics.Unavailable -> return refused(snapshot, operation, result.diagnostic)
            is ExternalSemanticDiagnostics.Available -> result.diagnostics
        }
        if (before.any { it.severity == Diagnostic.Severity.ERROR }) return unclean(snapshot, operation, before, emptyList())
        val draft = proposal()
        if (draft.status != PatchStatus.PREVIEW) return draft
        ownership(snapshot, draft.workspaceEdit).firstOrNull()?.let { return refused(snapshot, operation, it) }
        val staged = runCatching { WorkspaceEditSimulator.apply(snapshot, draft.workspaceEdit) }.getOrElse {
            return refused(snapshot, operation, error("typescript.previewSimulationFailed", "Compiler edits cannot be simulated"))
        }
        val after = when (val result = diagnostics(staged)) {
            is ExternalSemanticDiagnostics.Unavailable -> return refused(snapshot, operation, result.diagnostic)
            is ExternalSemanticDiagnostics.Available -> result.diagnostics
        }
        if (after.any { it.severity == Diagnostic.Severity.ERROR }) return unclean(snapshot, operation, before, after)
        val observed = evidence()
        val editHash = WorkspaceEditIdentity.sha256(draft.workspaceEdit)
        if (sessionId == null || observed == null || observed.command != command ||
            observed.snapshotHash != snapshot.hash || observed.workspaceEditSha256 != editHash ||
            observed.arguments != arguments ||
            (command == "getEditsForRefactor" && observed.returnedActionSha256 == null) ||
            observed.process.workingDirectory != snapshot.workspace.root.toAbsolutePath().normalize()) {
            return refused(snapshot, operation, error("typescript.compilerAuthorityUnavailable", "Exact compiler exchange authority is missing or mismatched"))
        }
        val attestation = toolchain.compilerAttestation()
        val attributes = arguments + mapOf(
            "command" to command,
            "returnedActionSha256" to observed.returnedActionSha256.orEmpty(),
            "semanticSession" to sessionId,
            "compilerProcess" to observed.process.id,
            "compilerPid" to observed.process.pid.toString(),
            "compilerStartedAt" to observed.process.startedAt.toString(),
            "executableSha256" to observed.process.executableSha256,
            "processArgumentsSha256" to observed.process.argumentsSha256,
            "toolchainEvidenceSha256" to attestation.toolchainEvidenceSha256,
            "compilerSha256" to attestation.compilerSha256,
            "modelProjectionHash" to model.projectionHash,
            "stagedSnapshotHash" to staged.hash,
            "formattingAndPreferences" to if ("formatOptions" in arguments) "explicit-configure" else "pinned-server-defaults",
        )
        val identity = buildJsonObject {
            put("operation", operation); put("snapshotHash", snapshot.hash); put("workspaceEditSha256", editHash)
            attributes.toSortedMap().forEach { (name, value) -> put(name, value) }
        }.toString()
        val lease = OperationAuthorityLease(
            kind = "typescript-compiler-exact-v1", operation = operation, snapshotHash = snapshot.hash,
            evidenceHash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) },
            workspaceEditSha256 = editHash,
            requiredFileEvidence = model.evidence.map {
                OperationAuthorityFileEvidence("typescript-project-input", it.path.normalize(), it.sha256)
            },
            attributes = attributes,
        )
        approve(staged.hash)
        return draft.copy(diagnosticsBefore = before, diagnosticsAfterPreview = after, authorityLease = lease)
    }

    private fun unclean(snapshot: ProjectSnapshot, operation: String, before: List<Diagnostic>, after: List<Diagnostic>) =
        refused(snapshot, operation, error("typescript.diagnosticsNotClean", "TypeScript compiler diagnostics are not clean"))
            .copy(diagnosticsBefore = before, diagnosticsAfterPreview = after)

    private fun refused(snapshot: ProjectSnapshot, operation: String, diagnostic: Diagnostic) = PatchPlan(
        operation = operation, status = PatchStatus.REFUSED, snapshotHash = snapshot.hash,
        confidence = 0.0, requiresUserApproval = false, summary = diagnostic.message,
        affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(), diagnosticsAfterPreview = listOf(diagnostic),
        warnings = listOf(diagnostic.message), riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.LANGUAGE_SERVER, refusalCode = diagnostic.code,
    )

    private fun error(code: String, message: String) = Diagnostic(message, Diagnostic.Severity.ERROR, code = code)
}

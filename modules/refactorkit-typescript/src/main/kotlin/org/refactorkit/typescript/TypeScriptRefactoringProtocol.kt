package org.refactorkit.typescript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit

/** Shared public-session binding for advanced previews; transports do not choose an apply gate. */
object TypeScriptRefactoringProtocol {
    fun catalogue(adapter: TypeScriptSemanticAdapter): JsonArray = JsonArray(adapter.refactoringCatalogue().map { descriptor ->
        buildJsonObject { put("id", descriptor.id); put("label", descriptor.label); put("riskLevel", descriptor.riskLevel.name) }
    })

    fun preview(
        adapter: TypeScriptSemanticAdapter,
        request: RefactoringRequest,
        expectedSnapshotHash: String?,
        suppliedLease: String?,
        owningLease: String?,
    ): PatchPlan {
        if (owningLease.isNullOrBlank() || suppliedLease != owningLease || expectedSnapshotHash != request.snapshot.hash ||
            adapter.activeSnapshotHash() != request.snapshot.hash) {
            val code = "typescript.refactoringAuthorityStale"
            val message = "Advanced preview requires the owning semantic lease and exact current snapshot hash"
            return PatchPlan(operation = request.operation, status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash,
                confidence = 0.0, requiresUserApproval = false, summary = message, affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
                diagnosticsAfterPreview = listOf(Diagnostic(message, Diagnostic.Severity.ERROR, code = code)),
                riskLevel = RiskLevel.HIGH, refusalCode = code)
        }
        return adapter.applyRefactoring(request)
    }
}

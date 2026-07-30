package org.refactorkit.java

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEdit

/** Operation-specific dispatch keeps managed plans and structurally non-managed guidance disjoint. */
sealed interface JavaMoveClassOperationOutcome {
    class Plan internal constructor(val preview: JavaMoveClassPreview) : JavaMoveClassOperationOutcome
    class Guidance internal constructor(val guidance: JavaMoveClassReviewOnlyGuidance) : JavaMoveClassOperationOutcome
}

/**
 * Application outcome boundary for `REQ-JAVA-MAVEN-MOVE-AUTH-003`.
 * Known safely enumerable defects become guidance; invalid expected evidence fails closed.
 */
class JavaMoveClassOperationDispatcher(
    private val javaAdapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun preview(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
    ): JavaMoveClassOperationOutcome {
        val planner = JavaMoveClassPlanner(javaAdapter)
        return when (val preflight = JavaMoveClassRequestValidator.validate(
            snapshot,
            javaAdapter,
            symbolFqn,
            targetPackage,
        )) {
            is JavaMoveClassRequestValidation.Refused -> JavaMoveClassOperationOutcome.Plan(
                planner.previewWithAuthority(snapshot, preflight),
            )
            is JavaMoveClassRequestValidation.Supported -> when (
                val collection = JavaMoveClassGuidanceCollector.collect(snapshot, symbolFqn, targetPackage)
            ) {
                is JavaMoveClassGuidanceCollectionResult.Guidance ->
                    JavaMoveClassOperationOutcome.Guidance(collection.value)
                is JavaMoveClassGuidanceCollectionResult.Refused -> JavaMoveClassOperationOutcome.Plan(
                    JavaMoveClassPreview(
                        PatchPlan(
                            operation = "moveClass",
                            status = PatchStatus.REFUSED,
                            snapshotHash = snapshot.hash,
                            confidence = 0.0,
                            requiresUserApproval = false,
                            summary = collection.summary,
                            affectedFiles = emptySet(),
                            workspaceEdit = WorkspaceEdit(),
                            evidence = RefactoringEvidence.STRUCTURAL,
                            refusalCode = collection.code,
                        ),
                        targetAuthorityLease = null,
                    ),
                )
                JavaMoveClassGuidanceCollectionResult.NotApplicable -> JavaMoveClassOperationOutcome.Plan(
                    planner.previewWithAuthority(snapshot, preflight),
                )
            }
        }
    }
}

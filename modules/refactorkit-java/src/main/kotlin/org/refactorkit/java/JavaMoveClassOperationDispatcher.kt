package org.refactorkit.java

import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.WorkspaceEdit

/** Operation-specific dispatch keeps managed plans and structurally non-managed results disjoint. */
sealed interface JavaMoveClassOperationOutcome {
    class Plan internal constructor(val preview: JavaMoveClassPreview) : JavaMoveClassOperationOutcome {
        init {
            require(preview.plan.evidence != RefactoringEvidence.LEXICAL_FALLBACK) {
                "dispatched managed-plan outcomes cannot carry lexical fallback evidence"
            }
        }
    }

    class Guidance internal constructor(val guidance: JavaMoveClassReviewOnlyGuidance) : JavaMoveClassOperationOutcome

    class LexicalReview internal constructor(
        val envelope: JavaMoveClassLexicalFallbackReviewEnvelope,
    ) : JavaMoveClassOperationOutcome
}

/**
 * Application outcome boundary for REQ-003 guidance, REQ-004 lexical review, and managed plans.
 * Promotion-attempt metadata is intentionally excluded from every authority and identity input.
 */
class JavaMoveClassOperationDispatcher(
    private val javaAdapter: JavaLanguageAdapter = JavaLanguageAdapter(),
    private val lexicalReviewAuditCache: JavaMoveClassLexicalReviewAuditCache =
        JavaMoveClassLexicalReviewAuditCache(),
) {
    fun preview(
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
        @Suppress("UNUSED_PARAMETER")
        promotionAttempt: JavaMoveClassPromotionAttemptMetadata = JavaMoveClassPromotionAttemptMetadata.NONE,
    ): JavaMoveClassOperationOutcome {
        val planner = JavaMoveClassPlanner(javaAdapter)
        return when (val preflight = JavaMoveClassRequestValidator.validate(
            snapshot,
            javaAdapter,
            symbolFqn,
            targetPackage,
        )) {
            is JavaMoveClassRequestValidation.Refused -> planOutcome(
                planner.previewWithAuthority(snapshot, preflight),
                snapshot,
                symbolFqn,
                targetPackage,
            )
            is JavaMoveClassRequestValidation.Supported -> when (
                val collection = JavaMoveClassGuidanceCollector.collect(snapshot, symbolFqn, targetPackage)
            ) {
                is JavaMoveClassGuidanceCollectionResult.Guidance ->
                    JavaMoveClassOperationOutcome.Guidance(collection.value)
                is JavaMoveClassGuidanceCollectionResult.Refused -> planOutcome(
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
                    snapshot,
                    symbolFqn,
                    targetPackage,
                )
                JavaMoveClassGuidanceCollectionResult.NotApplicable -> planOutcome(
                    planner.previewWithAuthority(snapshot, preflight),
                    snapshot,
                    symbolFqn,
                    targetPackage,
                )
            }
        }
    }

    fun findLexicalReview(operationId: String): JavaMoveClassLexicalFallbackReviewEnvelope? =
        lexicalReviewAuditCache.find(operationId)

    fun clearLexicalReviewAudit() = lexicalReviewAuditCache.clear()

    private fun planOutcome(
        preview: JavaMoveClassPreview,
        snapshot: ProjectSnapshot,
        symbolFqn: String,
        targetPackage: String,
    ): JavaMoveClassOperationOutcome {
        if (preview.plan.evidence != RefactoringEvidence.LEXICAL_FALLBACK) {
            return JavaMoveClassOperationOutcome.Plan(preview)
        }
        val envelope = JavaMoveClassLexicalFallbackReviewFactory.create(
            snapshot,
            symbolFqn,
            targetPackage,
            preview,
        )
        lexicalReviewAuditCache.put(envelope)
        return JavaMoveClassOperationOutcome.LexicalReview(envelope)
    }
}

package org.refactorkit.java

import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourceRange
import java.nio.file.Path

/** Immutable result type for safely enumerable, non-managed move-class guidance. */
enum class JavaMoveClassGuidanceResultType {
    REVIEW_ONLY_GUIDANCE,
}

data class JavaMoveClassGuidanceRequest(
    val operation: String,
    val symbolFqn: String,
    val targetPackage: String,
) {
    init {
        require(operation == "moveClass") { "guidance request must be moveClass" }
        require(symbolFqn.isNotBlank()) { "guidance symbol must not be blank" }
        require(targetPackage.isNotBlank()) { "guidance target package must not be blank" }
    }
}

enum class JavaMoveClassGuidanceCandidateCompleteness {
    COMPLETE,
    COMPLETE_WITH_TYPED_OMISSIONS,
}

enum class JavaMoveClassGuidanceOmissionKind {
    SOURCE_INVENTORY_ENTRY,
}

enum class JavaMoveClassGuidanceResidualKind {
    JAVA_NON_CODE_RESIDUAL,
    NON_JAVA_RESIDUAL,
}

enum class JavaMoveClassGuidanceRestorationKind {
    RESTORE_SOURCE_INVENTORY,
    REFRESH_CLASSPATH_EVIDENCE,
    REESTABLISH_EXACT_BINDINGS,
    RESTORE_TARGET_NAME_LOOKUP,
    RESTORE_OBSERVER_CLOSURE,
    RESTORE_DIAGNOSTIC_IDENTITY,
    EXTERNALLY_RESTORE_GENERATED_ROOT,
    FULL_REACTOR_RESCAN,
    NEW_PREVIEW,
}

enum class JavaMoveClassGuidanceAuthorityLayer {
    CANDIDATE_TOTALITY,
    TARGET_NAME_LOOKUP,
    CLOSURE_CONSISTENCY,
    DIAGNOSTIC_IDENTITY,
}

enum class JavaMoveClassGuidanceCandidateBindingState {
    AMBIGUOUS,
    PROBLEM,
}

enum class JavaMoveClassGuidanceLookupPrerequisiteKind {
    STATIC_IMPORT_ON_DEMAND,
}

enum class JavaMoveClassGuidanceClosureMembership {
    OUTSIDE,
}

enum class JavaMoveClassGuidanceDiagnosticPhase {
    BEFORE_VS_STAGED,
}

enum class JavaMoveClassGuidanceDiagnosticChangedField(val wireName: String) {
    PROBLEM_ID("problemId"),
    MESSAGE("message"),
}

enum class JavaMoveClassSourceInventoryObservation {
    MISSING,
}

sealed interface JavaMoveClassGuidanceBlocker {
    val code: String
    val mavenModule: String
    val sourceSet: String
    val path: Path
    val authorityLayer: JavaMoveClassGuidanceAuthorityLayer? get() = null

    data class MissingReadableSourceInventoryEntry(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val manifestPath: Path,
        val manifestContentSha256: String,
        val expectedContentSha256: String,
        val observedContentSha256: String,
        val observedInventoryStatus: JavaMoveClassSourceInventoryObservation,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = SOURCE_INVENTORY_MISSING_ENTRY
        val contentSha256: String get() = observedContentSha256

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(javaMoveGuidanceIsSafeRelative(manifestPath)) {
                "source-inventory manifest path must be normalized and workspace-relative"
            }
            javaMoveGuidanceRequireSha256(manifestContentSha256, "source-inventory manifest content hash")
            javaMoveGuidanceRequireSha256(expectedContentSha256, "expected source content hash")
            javaMoveGuidanceRequireSha256(observedContentSha256, "observed source content hash")
            require(expectedContentSha256 == observedContentSha256) {
                "readable omitted source must match its expected content identity"
            }
            require(observedInventoryStatus == JavaMoveClassSourceInventoryObservation.MISSING) {
                "missing source-inventory blocker requires a missing observed inventory fact"
            }
        }
    }

    data class SystemPathArtifactFingerprintMismatch(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val manifestPath: Path,
        val manifestContentSha256: String,
        val expectedFingerprint: String,
        val observedFingerprint: String,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = CLASSPATH_FINGERPRINT_MISMATCH

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(javaMoveGuidanceIsSafeRelative(manifestPath)) {
                "classpath evidence manifest path must be normalized and workspace-relative"
            }
            javaMoveGuidanceRequireSha256(manifestContentSha256, "classpath evidence manifest content hash")
            javaMoveGuidanceRequireSha256(expectedFingerprint, "expected system-path artifact fingerprint")
            javaMoveGuidanceRequireSha256(observedFingerprint, "observed system-path artifact fingerprint")
            require(expectedFingerprint != observedFingerprint) { "artifact fingerprints must differ" }
        }
    }

    data class RecoveredTargetUse(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val sourceRange: SourceRange,
        val contentSha256: String,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = TARGET_USE_RECOVERED_BINDING

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(sourceRange.start < sourceRange.end) { "recovered target-use range must not be empty" }
            javaMoveGuidanceRequireSha256(contentSha256, "recovered target-use content hash")
        }
    }

    data class MaterializedGeneratedRootInventoryFingerprintMismatch(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val manifestPath: Path,
        val manifestContentSha256: String,
        val expectedFingerprint: String,
        val observedFingerprint: String,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = GENERATED_ROOT_INVENTORY_FINGERPRINT_MISMATCH

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(javaMoveGuidanceIsSafeRelative(manifestPath)) {
                "generated-root manifest path must be normalized and workspace-relative"
            }
            javaMoveGuidanceRequireSha256(manifestContentSha256, "generated-root manifest content hash")
            javaMoveGuidanceRequireSha256(expectedFingerprint, "expected generated-root inventory fingerprint")
            javaMoveGuidanceRequireSha256(observedFingerprint, "observed generated-root inventory fingerprint")
            require(expectedFingerprint != observedFingerprint) { "generated-root fingerprints must differ" }
        }
    }

    class UnresolvedCandidate(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val contentSha256: String,
        candidateRanges: Collection<SourceRange>,
        val bindingState: JavaMoveClassGuidanceCandidateBindingState,
        competingFqns: Collection<String>,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = CANDIDATE_UNRESOLVED
        override val authorityLayer: JavaMoveClassGuidanceAuthorityLayer =
            JavaMoveClassGuidanceAuthorityLayer.CANDIDATE_TOTALITY
        private val candidateRangeValues = javaMoveGuidanceImmutableList(
            ArrayList(candidateRanges).also { copiedRanges ->
                require(copiedRanges.distinct().size == copiedRanges.size) {
                    "unresolved-candidate ranges must have unique path/range identities"
                }
            }.sortedWith(
                compareBy<SourceRange> { it.start.line }
                    .thenBy { it.start.character }
                    .thenBy { it.end.line }
                    .thenBy { it.end.character },
            ),
        )
        private val competingFqnValues = javaMoveGuidanceImmutableList(competingFqns.distinct().sorted())
        val candidateRanges: List<SourceRange> get() = javaMoveGuidanceImmutableList(candidateRangeValues)
        val competingFqns: List<String> get() = javaMoveGuidanceImmutableList(competingFqnValues)
        val classification: JavaMoveClassCandidateClassification = JavaMoveClassCandidateClassification.UNRESOLVED
        val recovered: Boolean = false
        val truncated: Boolean = false

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            javaMoveGuidanceRequireSha256(contentSha256, "unresolved-candidate content hash")
            require(candidateRangeValues.isNotEmpty() && candidateRangeValues.all { it.start < it.end }) {
                "unresolved-candidate ranges must be complete and non-empty"
            }
            require(competingFqnValues.all(String::isNotBlank)) {
                "unresolved-candidate competing FQNs must not be blank"
            }
            if (bindingState == JavaMoveClassGuidanceCandidateBindingState.AMBIGUOUS) {
                require(competingFqnValues.size >= 2) {
                    "ambiguous unresolved candidates require at least two competing FQNs"
                }
            }
        }

        override fun equals(other: Any?): Boolean = other is UnresolvedCandidate &&
            mavenModule == other.mavenModule && sourceSet == other.sourceSet && path == other.path &&
            contentSha256 == other.contentSha256 && candidateRangeValues == other.candidateRangeValues &&
            bindingState == other.bindingState && competingFqnValues == other.competingFqnValues

        override fun hashCode(): Int {
            var result = mavenModule.hashCode()
            result = 31 * result + sourceSet.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + contentSha256.hashCode()
            result = 31 * result + candidateRangeValues.hashCode()
            result = 31 * result + bindingState.hashCode()
            result = 31 * result + competingFqnValues.hashCode()
            return result
        }
    }

    data class UnresolvedTargetLookupPrerequisite(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val prerequisiteKind: JavaMoveClassGuidanceLookupPrerequisiteKind,
        val importRange: SourceRange,
        val contentSha256: String,
        val unresolvedOwner: String,
        val targetSimpleName: String,
        val affectedCandidateRangeHash: String,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = TARGET_LOOKUP_UNRESOLVED_PREREQUISITE
        override val authorityLayer: JavaMoveClassGuidanceAuthorityLayer =
            JavaMoveClassGuidanceAuthorityLayer.TARGET_NAME_LOOKUP

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(importRange.start < importRange.end) { "target-lookup import range must not be empty" }
            javaMoveGuidanceRequireSha256(contentSha256, "target-lookup content hash")
            javaMoveGuidanceRequireSha256(affectedCandidateRangeHash, "affected candidate-range hash")
            require(unresolvedOwner.isNotBlank() && targetSimpleName.isNotBlank()) {
                "target-lookup owner and target simple name must not be blank"
            }
        }
    }

    data class ExplicitOldFqnOutsideClosure(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val sourceRange: SourceRange,
        val contentSha256: String,
        val fqn: String,
        val closureMembership: JavaMoveClassGuidanceClosureMembership,
        val dependencyPath: String,
        val closureEvidenceHash: String,
        val observedClassification: JavaMoveClassCandidateClassification,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = EXPLICIT_OLD_FQN_OUTSIDE_CLOSURE
        override val authorityLayer: JavaMoveClassGuidanceAuthorityLayer =
            JavaMoveClassGuidanceAuthorityLayer.CLOSURE_CONSISTENCY

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            require(sourceRange.start < sourceRange.end) { "outside-closure FQN range must not be empty" }
            javaMoveGuidanceRequireSha256(contentSha256, "outside-closure content hash")
            javaMoveGuidanceRequireSha256(closureEvidenceHash, "reverse-observer-closure evidence hash")
            require(fqn.isNotBlank() && dependencyPath == "NONE") {
                "outside-closure blocker requires an explicit FQN and no dependency path"
            }
            require(closureMembership == JavaMoveClassGuidanceClosureMembership.OUTSIDE &&
                observedClassification == JavaMoveClassCandidateClassification.UNRESOLVED
            ) { "outside-closure blocker requires OUTSIDE/UNRESOLVED evidence" }
        }
    }

    data class DiagnosticIdentity(
        val providerConfigurationHash: String,
        val problemId: Int,
        val category: JdtJavaDiagnosticCategory,
        val severity: Diagnostic.Severity,
        val path: Path,
        val sourceRange: SourceRange,
        val message: String,
    ) {
        init {
            javaMoveGuidanceRequireSha256(providerConfigurationHash, "diagnostic provider identity")
            require(problemId >= 0) { "diagnostic problem ID must be non-negative" }
            require(javaMoveGuidanceIsSafeRelative(path)) { "diagnostic path must be workspace-relative" }
            require(sourceRange.start < sourceRange.end) { "diagnostic range must not be empty" }
            require(message.isNotBlank()) { "diagnostic message must not be blank" }
        }
    }

    class RetainedDiagnosticIdentityDrift(
        override val mavenModule: String,
        override val sourceSet: String,
        override val path: Path,
        val phase: JavaMoveClassGuidanceDiagnosticPhase,
        val before: DiagnosticIdentity,
        val staged: DiagnosticIdentity,
        val beforeDiagnosticMultisetSha256: String,
        val stagedDiagnosticMultisetSha256: String,
        changedFields: Collection<JavaMoveClassGuidanceDiagnosticChangedField>,
        val stagedOverlaySha256: String,
        val diskDrift: Boolean,
    ) : JavaMoveClassGuidanceBlocker {
        override val code: String = RETAINED_DIAGNOSTIC_IDENTITY_DRIFT
        override val authorityLayer: JavaMoveClassGuidanceAuthorityLayer =
            JavaMoveClassGuidanceAuthorityLayer.DIAGNOSTIC_IDENTITY
        private val changedFieldValues = javaMoveGuidanceImmutableList(changedFields.distinct().sortedBy { it.ordinal })
        val changedFields: List<JavaMoveClassGuidanceDiagnosticChangedField>
            get() = javaMoveGuidanceImmutableList(changedFieldValues)

        init {
            javaMoveGuidanceValidateBlockerIdentity(mavenModule, sourceSet, path)
            javaMoveGuidanceRequireSha256(beforeDiagnosticMultisetSha256, "before diagnostic multiset hash")
            javaMoveGuidanceRequireSha256(stagedDiagnosticMultisetSha256, "staged diagnostic multiset hash")
            javaMoveGuidanceRequireSha256(stagedOverlaySha256, "staged overlay identity")
            require(beforeDiagnosticMultisetSha256 != stagedDiagnosticMultisetSha256) {
                "diagnostic identity drift requires different multiset identities"
            }
            require(before.path == path && staged.path == path && before.sourceRange == staged.sourceRange &&
                before.category == staged.category && before.severity == staged.severity
            ) { "diagnostic drift identities must preserve mapped path, range, category, and severity" }
            require(changedFieldValues.isNotEmpty() && !diskDrift) {
                "same-snapshot diagnostic drift requires changed identity fields and no disk drift"
            }
        }

        override fun equals(other: Any?): Boolean = other is RetainedDiagnosticIdentityDrift &&
            mavenModule == other.mavenModule && sourceSet == other.sourceSet && path == other.path &&
            phase == other.phase && before == other.before && staged == other.staged &&
            beforeDiagnosticMultisetSha256 == other.beforeDiagnosticMultisetSha256 &&
            stagedDiagnosticMultisetSha256 == other.stagedDiagnosticMultisetSha256 &&
            changedFieldValues == other.changedFieldValues && stagedOverlaySha256 == other.stagedOverlaySha256 &&
            diskDrift == other.diskDrift

        override fun hashCode(): Int {
            var result = mavenModule.hashCode()
            result = 31 * result + sourceSet.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + phase.hashCode()
            result = 31 * result + before.hashCode()
            result = 31 * result + staged.hashCode()
            result = 31 * result + beforeDiagnosticMultisetSha256.hashCode()
            result = 31 * result + stagedDiagnosticMultisetSha256.hashCode()
            result = 31 * result + changedFieldValues.hashCode()
            result = 31 * result + stagedOverlaySha256.hashCode()
            result = 31 * result + diskDrift.hashCode()
            return result
        }
    }

    companion object {
        const val SOURCE_INVENTORY_MISSING_ENTRY = "java.maven.moveClass.sourceInventory.missingEntry"
        const val CLASSPATH_FINGERPRINT_MISMATCH = "java.maven.moveClass.classpathFingerprint.mismatch"
        const val TARGET_USE_RECOVERED_BINDING = "java.maven.moveClass.targetUse.recoveredBinding"
        const val GENERATED_ROOT_INVENTORY_FINGERPRINT_MISMATCH =
            "java.maven.moveClass.materializedGeneratedRootInventory.fingerprintMismatch"
        const val CANDIDATE_UNRESOLVED = "java.maven.moveClass.candidate.unresolved"
        const val TARGET_LOOKUP_UNRESOLVED_PREREQUISITE =
            "java.maven.moveClass.targetLookup.unresolvedPrerequisite"
        const val EXPLICIT_OLD_FQN_OUTSIDE_CLOSURE =
            "java.maven.moveClass.reverseObserverClosure.explicitOldFqnOutside"
        const val RETAINED_DIAGNOSTIC_IDENTITY_DRIFT =
            "java.maven.moveClass.retainedDiagnostic.identityDrift"
    }
}

sealed interface JavaMoveClassGuidanceOccurrence {
    val snapshotSha256: String
    val path: Path
    val sourceRange: SourceRange
    val contentSha256: String
    val lexicalText: String
    val managedEdit: Boolean get() = false
}

data class JavaMoveClassGuidanceJavaCandidate(
    override val snapshotSha256: String,
    override val path: Path,
    override val sourceRange: SourceRange,
    override val contentSha256: String,
    override val lexicalText: String,
    val mavenModule: String,
    val sourceSet: String,
    val classification: JavaMoveClassCandidateClassification,
    val bindingKey: String?,
    val recoveredRange: SourceRange?,
) : JavaMoveClassGuidanceOccurrence {
    val recovered: Boolean get() = recoveredRange != null

    init {
        javaMoveGuidanceValidateOccurrenceIdentity(snapshotSha256, path, sourceRange, contentSha256)
        require(lexicalText.isNotBlank()) { "candidate lexical text must not be blank" }
        require(mavenModule.isNotBlank()) { "candidate Maven module must not be blank" }
        require(sourceSet.isNotBlank()) { "candidate source set must not be blank" }
        when (classification) {
            JavaMoveClassCandidateClassification.BOUND_TARGET,
            JavaMoveClassCandidateClassification.BOUND_OTHER -> {
                require(!bindingKey.isNullOrBlank()) { "bound guidance candidates require an exact binding key" }
                require(recoveredRange == null) { "bound guidance candidates must not expose a recovered range" }
            }
            JavaMoveClassCandidateClassification.UNRESOLVED -> {
                require(bindingKey == null) { "unresolved guidance candidates must not expose a binding key" }
                recoveredRange?.let { range ->
                    require(range.start < range.end) { "recovered candidate range must not be empty" }
                    require(range.start >= sourceRange.start && range.end <= sourceRange.end) {
                        "recovered candidate range must be contained by its lexical range"
                    }
                }
            }
        }
    }
}

data class JavaMoveClassGuidanceResidual(
    override val snapshotSha256: String,
    override val path: Path,
    override val sourceRange: SourceRange,
    override val contentSha256: String,
    override val lexicalText: String,
    val kind: JavaMoveClassGuidanceResidualKind,
    val mavenModule: String?,
    val sourceSet: String?,
) : JavaMoveClassGuidanceOccurrence {
    init {
        javaMoveGuidanceValidateOccurrenceIdentity(snapshotSha256, path, sourceRange, contentSha256)
        require(lexicalText.isNotBlank()) { "residual lexical text must not be blank" }
        require((mavenModule == null) == (sourceSet == null)) {
            "residual Maven module and source set must both be present or both be absent"
        }
        require(kind != JavaMoveClassGuidanceResidualKind.JAVA_NON_CODE_RESIDUAL || mavenModule != null) {
            "Java non-code residuals require Maven ownership"
        }
    }
}

data class JavaMoveClassGuidanceOmission(
    val kind: JavaMoveClassGuidanceOmissionKind,
    val snapshotSha256: String,
    val mavenModule: String,
    val sourceSet: String,
    val path: Path,
    val sourceRange: SourceRange,
    val contentSha256: String,
) {
    init {
        javaMoveGuidanceValidateOccurrenceIdentity(snapshotSha256, path, sourceRange, contentSha256)
        require(mavenModule.isNotBlank()) { "omission Maven module must not be blank" }
        require(sourceSet.isNotBlank()) { "omission source set must not be blank" }
    }
}

data class JavaMoveClassGuidanceRestorationAction(
    val order: Int,
    val kind: JavaMoveClassGuidanceRestorationKind,
    val blockerCode: String?,
    val mavenModule: String?,
    val sourceSet: String?,
) {
    init {
        require(order > 0) { "restoration action order must be positive" }
        require((mavenModule == null) == (sourceSet == null)) {
            "restoration Maven module and source set must both be present or both be absent"
        }
    }
}

data class JavaMoveClassVcsChecklistItem(
    val order: Int,
    val verification: String,
) {
    init {
        require(order > 0) { "checklist order must be positive" }
        require(verification.isNotBlank()) { "checklist verification must not be blank" }
    }
}

/** Canonically partitioned immutable guidance facts. */
class JavaMoveClassGuidanceCandidateGroups internal constructor(
    boundTarget: Collection<JavaMoveClassGuidanceJavaCandidate>,
    boundOther: Collection<JavaMoveClassGuidanceJavaCandidate>,
    unresolved: Collection<JavaMoveClassGuidanceJavaCandidate>,
    javaNonCodeResiduals: Collection<JavaMoveClassGuidanceResidual>,
    nonJavaResiduals: Collection<JavaMoveClassGuidanceResidual>,
) {
    private val boundTargetValues = javaMoveGuidanceImmutableSorted(boundTarget, JAVA_MOVE_GUIDANCE_CANDIDATE_ORDER)
    private val boundOtherValues = javaMoveGuidanceImmutableSorted(boundOther, JAVA_MOVE_GUIDANCE_CANDIDATE_ORDER)
    private val unresolvedValues = javaMoveGuidanceImmutableSorted(unresolved, JAVA_MOVE_GUIDANCE_CANDIDATE_ORDER)
    private val javaNonCodeResidualValues =
        javaMoveGuidanceImmutableSorted(javaNonCodeResiduals, JAVA_MOVE_GUIDANCE_RESIDUAL_ORDER)
    private val nonJavaResidualValues =
        javaMoveGuidanceImmutableSorted(nonJavaResiduals, JAVA_MOVE_GUIDANCE_RESIDUAL_ORDER)
    private val allOccurrenceValues: List<JavaMoveClassGuidanceOccurrence> = javaMoveGuidanceImmutableList(
        boundTargetValues + boundOtherValues + unresolvedValues +
            javaNonCodeResidualValues + nonJavaResidualValues,
    )

    val boundTarget: List<JavaMoveClassGuidanceJavaCandidate> get() = javaMoveGuidanceImmutableList(boundTargetValues)
    val boundOther: List<JavaMoveClassGuidanceJavaCandidate> get() = javaMoveGuidanceImmutableList(boundOtherValues)
    val unresolved: List<JavaMoveClassGuidanceJavaCandidate> get() = javaMoveGuidanceImmutableList(unresolvedValues)
    val javaNonCodeResiduals: List<JavaMoveClassGuidanceResidual>
        get() = javaMoveGuidanceImmutableList(javaNonCodeResidualValues)
    val nonJavaResiduals: List<JavaMoveClassGuidanceResidual>
        get() = javaMoveGuidanceImmutableList(nonJavaResidualValues)
    val allOccurrences: List<JavaMoveClassGuidanceOccurrence> get() = javaMoveGuidanceImmutableList(allOccurrenceValues)

    init {
        require(boundTargetValues.all { it.classification == JavaMoveClassCandidateClassification.BOUND_TARGET })
        require(boundOtherValues.all { it.classification == JavaMoveClassCandidateClassification.BOUND_OTHER })
        require(unresolvedValues.all { it.classification == JavaMoveClassCandidateClassification.UNRESOLVED })
        require(javaNonCodeResidualValues.all {
            it.kind == JavaMoveClassGuidanceResidualKind.JAVA_NON_CODE_RESIDUAL
        })
        require(nonJavaResidualValues.all {
            it.kind == JavaMoveClassGuidanceResidualKind.NON_JAVA_RESIDUAL
        })
        require(allOccurrenceValues.distinctBy(::javaMoveGuidanceOccurrenceKey).size == allOccurrenceValues.size) {
            "guidance occurrences must have unique typed path/range identities"
        }
    }

    override fun equals(other: Any?): Boolean = other is JavaMoveClassGuidanceCandidateGroups &&
        boundTargetValues == other.boundTargetValues && boundOtherValues == other.boundOtherValues &&
        unresolvedValues == other.unresolvedValues &&
        javaNonCodeResidualValues == other.javaNonCodeResidualValues &&
        nonJavaResidualValues == other.nonJavaResidualValues

    override fun hashCode(): Int {
        var result = boundTargetValues.hashCode()
        result = 31 * result + boundOtherValues.hashCode()
        result = 31 * result + unresolvedValues.hashCode()
        result = 31 * result + javaNonCodeResidualValues.hashCode()
        result = 31 * result + nonJavaResidualValues.hashCode()
        return result
    }
}

/**
 * Immutable review guidance. Its structural surface intentionally has no plan, edit, transaction,
 * approval, pending-plan, or rollback representation.
 */
class JavaMoveClassReviewOnlyGuidance internal constructor(
    val request: JavaMoveClassGuidanceRequest,
    val requestIdentitySha256: String,
    val snapshotSha256: String,
    val stagedOverlaySha256: String? = null,
    val canonicalEvidenceSha256: String,
    blockers: Collection<JavaMoveClassGuidanceBlocker>,
    val candidateGroups: JavaMoveClassGuidanceCandidateGroups,
    val candidateCompleteness: JavaMoveClassGuidanceCandidateCompleteness,
    omissions: Collection<JavaMoveClassGuidanceOmission>,
    restorationActions: Collection<JavaMoveClassGuidanceRestorationAction>,
    vcsChecklist: Collection<JavaMoveClassVcsChecklistItem>,
) {
    private val blockerValues = javaMoveGuidanceImmutableSorted(blockers, JAVA_MOVE_GUIDANCE_BLOCKER_ORDER)
    private val omissionValues = javaMoveGuidanceImmutableSorted(omissions, JAVA_MOVE_GUIDANCE_OMISSION_ORDER)
    private val restorationActionValues = javaMoveGuidanceImmutableList(
        restorationActions.sortedBy(JavaMoveClassGuidanceRestorationAction::order),
    )
    private val vcsChecklistValues = javaMoveGuidanceImmutableList(vcsChecklist.sortedBy(JavaMoveClassVcsChecklistItem::order))

    val resultType: JavaMoveClassGuidanceResultType = JavaMoveClassGuidanceResultType.REVIEW_ONLY_GUIDANCE
    val schemaVersion: Int = SCHEMA_VERSION
    val checklistVersion: Int = CHECKLIST_VERSION
    val blockers: List<JavaMoveClassGuidanceBlocker> get() = javaMoveGuidanceImmutableList(blockerValues)
    val omissions: List<JavaMoveClassGuidanceOmission> get() = javaMoveGuidanceImmutableList(omissionValues)
    val restorationActions: List<JavaMoveClassGuidanceRestorationAction>
        get() = javaMoveGuidanceImmutableList(restorationActionValues)
    val vcsChecklist: List<JavaMoveClassVcsChecklistItem> get() = javaMoveGuidanceImmutableList(vcsChecklistValues)

    init {
        javaMoveGuidanceRequireSha256(requestIdentitySha256, "guidance request identity")
        javaMoveGuidanceRequireSha256(snapshotSha256, "guidance snapshot identity")
        stagedOverlaySha256?.let { javaMoveGuidanceRequireSha256(it, "guidance staged-overlay identity") }
        javaMoveGuidanceRequireSha256(canonicalEvidenceSha256, "guidance evidence identity")
        require(blockerValues.isNotEmpty()) { "review-only guidance requires at least one blocker" }
        require(
            (candidateCompleteness == JavaMoveClassGuidanceCandidateCompleteness.COMPLETE) == omissionValues.isEmpty(),
        ) { "guidance completeness and typed omissions must agree" }
        require(restorationActionValues.map { it.order } == (1..restorationActionValues.size).toList()) {
            "restoration actions must have contiguous canonical order"
        }
        require(vcsChecklistValues == JAVA_MOVE_GUIDANCE_FIXED_VCS_CHECKLIST) {
            "guidance checklist must be the fixed VCS checklist"
        }
    }

    override fun equals(other: Any?): Boolean = other is JavaMoveClassReviewOnlyGuidance &&
        schemaVersion == other.schemaVersion && checklistVersion == other.checklistVersion &&
        request == other.request && requestIdentitySha256 == other.requestIdentitySha256 &&
        snapshotSha256 == other.snapshotSha256 && stagedOverlaySha256 == other.stagedOverlaySha256 &&
        canonicalEvidenceSha256 == other.canonicalEvidenceSha256 &&
        blockerValues == other.blockerValues && candidateGroups == other.candidateGroups &&
        candidateCompleteness == other.candidateCompleteness && omissionValues == other.omissionValues &&
        restorationActionValues == other.restorationActionValues && vcsChecklistValues == other.vcsChecklistValues

    override fun hashCode(): Int {
        var result = schemaVersion
        result = 31 * result + checklistVersion
        result = 31 * result + request.hashCode()
        result = 31 * result + requestIdentitySha256.hashCode()
        result = 31 * result + snapshotSha256.hashCode()
        result = 31 * result + (stagedOverlaySha256?.hashCode() ?: 0)
        result = 31 * result + canonicalEvidenceSha256.hashCode()
        result = 31 * result + blockerValues.hashCode()
        result = 31 * result + candidateGroups.hashCode()
        result = 31 * result + candidateCompleteness.hashCode()
        result = 31 * result + omissionValues.hashCode()
        result = 31 * result + restorationActionValues.hashCode()
        result = 31 * result + vcsChecklistValues.hashCode()
        return result
    }

    companion object {
        const val SCHEMA_VERSION: Int = 1
        const val CHECKLIST_VERSION: Int = 1
    }
}

package org.refactorkit.cli

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.SourceRange
import org.refactorkit.java.JavaMoveClassGuidanceBlocker
import org.refactorkit.java.JavaMoveClassGuidanceJavaCandidate
import org.refactorkit.java.JavaMoveClassGuidanceOccurrence
import org.refactorkit.java.JavaMoveClassGuidanceResidual
import org.refactorkit.java.JavaMoveClassReviewOnlyGuidance
import kotlin.io.path.invariantSeparatorsPathString

/** Serialized-output port used by the CLI adapter for non-managed move-class guidance. */
fun interface JavaMoveClassGuidanceOutputPort {
    fun render(guidance: JavaMoveClassReviewOnlyGuidance): String
}

/** JSON infrastructure adapter; kotlinx.serialization owns quoting and wire-format production. */
class JavaMoveClassGuidanceJsonRenderer(
    private val json: Json = Json { prettyPrint = true },
) : JavaMoveClassGuidanceOutputPort {
    override fun render(guidance: JavaMoveClassReviewOnlyGuidance): String = json.encodeToString(
        buildJsonObject {
            put("resultType", guidance.resultType.name)
            put("schemaVersion", guidance.schemaVersion)
            put("checklistVersion", guidance.checklistVersion)
            put("operation", guidance.request.operation)
            put("symbol", guidance.request.symbolFqn)
            put("targetPackage", guidance.request.targetPackage)
            put("requestIdentitySha256", guidance.requestIdentitySha256)
            put("snapshotSha256", guidance.snapshotSha256)
            guidance.stagedOverlaySha256?.let { put("stagedOverlaySha256", it) }
            put("canonicalEvidenceSha256", guidance.canonicalEvidenceSha256)
            put("managed", false)
            put("applyable", false)
            put("blockers", buildJsonArray {
                guidance.blockers.forEach { add(blockerJson(it)) }
            })
            put("candidateCompleteness", guidance.candidateCompleteness.name)
            put("candidateGroups", buildJsonObject {
                put("BOUND_TARGET", occurrencesJson(guidance.candidateGroups.boundTarget))
                put("BOUND_OTHER", occurrencesJson(guidance.candidateGroups.boundOther))
                put("UNRESOLVED", occurrencesJson(guidance.candidateGroups.unresolved))
                put("JAVA_NON_CODE_RESIDUAL", occurrencesJson(guidance.candidateGroups.javaNonCodeResiduals))
                put("NON_JAVA_RESIDUAL", occurrencesJson(guidance.candidateGroups.nonJavaResiduals))
            })
            put("omissions", buildJsonArray {
                guidance.omissions.forEach { omission ->
                    add(buildJsonObject {
                        put("kind", omission.kind.name)
                        put("snapshotSha256", omission.snapshotSha256)
                        put("mavenModule", omission.mavenModule)
                        put("sourceSet", omission.sourceSet)
                        put("path", omission.path.invariantSeparatorsPathString)
                        put("range", rangeJson(omission.sourceRange))
                        put("contentSha256", omission.contentSha256)
                    })
                }
            })
            put("restorationActions", buildJsonArray {
                guidance.restorationActions.forEach { action ->
                    add(buildJsonObject {
                        put("order", action.order)
                        put("kind", action.kind.name)
                        action.blockerCode?.let { put("blockerCode", it) }
                        action.mavenModule?.let { put("mavenModule", it) }
                        action.sourceSet?.let { put("sourceSet", it) }
                    })
                }
            })
            put("vcsChecklist", buildJsonArray {
                guidance.vcsChecklist.forEach { item ->
                    add(buildJsonObject {
                        put("order", item.order)
                        put("verification", item.verification)
                    })
                }
            })
        },
    )

    private fun blockerJson(blocker: JavaMoveClassGuidanceBlocker): JsonElement = buildJsonObject {
        put("code", blocker.code)
        put("mavenModule", blocker.mavenModule)
        put("sourceSet", blocker.sourceSet)
        put("path", blocker.path.invariantSeparatorsPathString)
        blocker.authorityLayer?.let { put("authorityLayer", it.name) }
        when (blocker) {
            is JavaMoveClassGuidanceBlocker.MissingReadableSourceInventoryEntry -> {
                put("manifestPath", blocker.manifestPath.invariantSeparatorsPathString)
                put("manifestContentSha256", blocker.manifestContentSha256)
                put("expectedContentSha256", blocker.expectedContentSha256)
                put("observedContentSha256", blocker.observedContentSha256)
                put("observedInventoryStatus", blocker.observedInventoryStatus.name)
            }
            is JavaMoveClassGuidanceBlocker.SystemPathArtifactFingerprintMismatch -> {
                put("manifestPath", blocker.manifestPath.invariantSeparatorsPathString)
                put("manifestContentSha256", blocker.manifestContentSha256)
                put("expectedFingerprint", blocker.expectedFingerprint)
                put("observedFingerprint", blocker.observedFingerprint)
            }
            is JavaMoveClassGuidanceBlocker.RecoveredTargetUse -> {
                put("range", rangeJson(blocker.sourceRange))
                put("contentSha256", blocker.contentSha256)
            }
            is JavaMoveClassGuidanceBlocker.MaterializedGeneratedRootInventoryFingerprintMismatch -> {
                put("manifestPath", blocker.manifestPath.invariantSeparatorsPathString)
                put("manifestContentSha256", blocker.manifestContentSha256)
                put("expectedFingerprint", blocker.expectedFingerprint)
                put("observedFingerprint", blocker.observedFingerprint)
            }
            is JavaMoveClassGuidanceBlocker.UnresolvedCandidate -> {
                put("contentSha256", blocker.contentSha256)
                put("candidateRanges", buildJsonArray {
                    blocker.candidateRanges.forEach { add(rangeJson(it)) }
                })
                put("bindingState", blocker.bindingState.name)
                put("competingFqns", buildJsonArray {
                    blocker.competingFqns.forEach { add(JsonPrimitive(it)) }
                })
                put("classification", blocker.classification.name)
                put("candidateCompleteness", "COMPLETE")
                put("recovered", blocker.recovered)
                put("truncated", blocker.truncated)
            }
            is JavaMoveClassGuidanceBlocker.UnresolvedTargetLookupPrerequisite -> {
                put("prerequisiteKind", blocker.prerequisiteKind.name)
                put("importRange", rangeJson(blocker.importRange))
                put("contentSha256", blocker.contentSha256)
                put("unresolvedOwner", blocker.unresolvedOwner)
                put("targetSimpleName", blocker.targetSimpleName)
                put("affectedCandidateRangeHash", blocker.affectedCandidateRangeHash)
            }
            is JavaMoveClassGuidanceBlocker.ExplicitOldFqnOutsideClosure -> {
                put("range", rangeJson(blocker.sourceRange))
                put("contentSha256", blocker.contentSha256)
                put("fqn", blocker.fqn)
                put("affectedSourceSet", "${blocker.mavenModule}:${blocker.sourceSet}")
                put("closureMembership", blocker.closureMembership.name)
                put("dependencyPath", blocker.dependencyPath)
                put("closureEvidenceHash", blocker.closureEvidenceHash)
                put("observedClassification", blocker.observedClassification.name)
            }
            is JavaMoveClassGuidanceBlocker.RetainedDiagnosticIdentityDrift -> {
                put("phase", blocker.phase.name)
                put("before", diagnosticIdentityJson(blocker.before))
                put("staged", diagnosticIdentityJson(blocker.staged))
                put("beforeDiagnosticMultisetSha256", blocker.beforeDiagnosticMultisetSha256)
                put("stagedDiagnosticMultisetSha256", blocker.stagedDiagnosticMultisetSha256)
                put("changedFields", buildJsonArray {
                    blocker.changedFields.forEach { add(JsonPrimitive(it.wireName)) }
                })
                put("stagedOverlaySha256", blocker.stagedOverlaySha256)
                put("diskDrift", blocker.diskDrift)
            }
        }
    }

    private fun diagnosticIdentityJson(
        identity: JavaMoveClassGuidanceBlocker.DiagnosticIdentity,
    ): JsonElement = buildJsonObject {
        put("providerConfigurationHash", identity.providerConfigurationHash)
        put("problemId", identity.problemId)
        put("category", identity.category.name)
        put("severity", identity.severity.name)
        put("path", identity.path.invariantSeparatorsPathString)
        put("range", rangeJson(identity.sourceRange))
        put("message", identity.message)
    }

    private fun occurrencesJson(occurrences: List<JavaMoveClassGuidanceOccurrence>): JsonArray =
        buildJsonArray { occurrences.forEach { add(occurrenceJson(it)) } }

    private fun occurrenceJson(occurrence: JavaMoveClassGuidanceOccurrence): JsonElement = buildJsonObject {
        put("snapshotSha256", occurrence.snapshotSha256)
        put("path", occurrence.path.invariantSeparatorsPathString)
        put("range", rangeJson(occurrence.sourceRange))
        put("contentSha256", occurrence.contentSha256)
        put("lexicalText", occurrence.lexicalText)
        put("managedEdit", occurrence.managedEdit)
        when (occurrence) {
            is JavaMoveClassGuidanceJavaCandidate -> {
                put("mavenModule", occurrence.mavenModule)
                put("sourceSet", occurrence.sourceSet)
                put("classification", occurrence.classification.name)
                occurrence.bindingKey?.let { put("bindingKey", it) }
                put("recovered", occurrence.recovered)
                occurrence.recoveredRange?.let { put("recoveredRange", rangeJson(it)) }
            }
            is JavaMoveClassGuidanceResidual -> {
                put("kind", occurrence.kind.name)
                occurrence.mavenModule?.let { put("mavenModule", it) }
                occurrence.sourceSet?.let { put("sourceSet", it) }
            }
        }
    }

    private fun rangeJson(range: SourceRange): JsonElement = buildJsonObject {
        put("start", buildJsonObject {
            put("line", range.start.line)
            put("character", range.start.character)
        })
        put("end", buildJsonObject {
            put("line", range.end.line)
            put("character", range.end.character)
        })
    }
}

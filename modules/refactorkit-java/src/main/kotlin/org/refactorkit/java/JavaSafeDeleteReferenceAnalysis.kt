package org.refactorkit.java

import org.refactorkit.core.ProjectSnapshot

/**
 * Snapshot-bound evidence for the bounded literal-text scan used by Java safeDelete.
 *
 * A complete result means only that every input in workspace-text-v1's declared,
 * bounded capture scope was read stably. It does not prove project-wide reference
 * closure and never grants managed Apply authority.
 */
data class JavaSafeDeleteReferenceAnalysis(
    val schemaVersion: String,
    val operation: String,
    val scope: String,
    val status: Status,
    val inputCount: Int?,
    val verifiedBytes: Long?,
    val snapshotHash: String,
    val managedApplyAuthority: String,
    val completeProject: Boolean,
) {
    enum class Status(val wire: String) {
        COMPLETE("complete"),
        INCOMPLETE("incomplete"),
    }

    companion object {
        const val SCHEMA_VERSION = "java-safe-delete-reference-analysis-v1"
        const val OPERATION = "safeDelete"
        const val SCOPE = "workspace-text-v1"
        const val MANAGED_APPLY_AUTHORITY = "none"

        fun from(snapshot: ProjectSnapshot): JavaSafeDeleteReferenceAnalysis {
            val models = snapshot.buildModels
            val versions = models.map { it.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_VERSION_KEY] }.distinct()
            val statuses = models.map { it.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY] }.distinct()
            val inputCounts = models.map {
                it.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_INPUTS_KEY]
                    ?.toIntOrNull()?.takeIf { count -> count >= 0 }
            }.distinct()
            val byteCounts = models.map {
                it.attributes[JAVA_SAFE_DELETE_TEXT_SCAN_BYTES_KEY]
                    ?.toLongOrNull()?.takeIf { count -> count >= 0L }
            }.distinct()
            val inputCount = inputCounts.singleOrNull()
            val verifiedBytes = byteCounts.singleOrNull()
            val metadataConsistent = models.isNotEmpty() &&
                versions == listOf(SCOPE) &&
                statuses.size == 1 && statuses.single() in setOf("complete", "incomplete") &&
                inputCount != null && verifiedBytes != null
            val complete = metadataConsistent && statuses.single() == "complete"
            return JavaSafeDeleteReferenceAnalysis(
                schemaVersion = SCHEMA_VERSION,
                operation = OPERATION,
                scope = SCOPE,
                status = if (complete) Status.COMPLETE else Status.INCOMPLETE,
                inputCount = inputCount.takeIf { metadataConsistent },
                verifiedBytes = verifiedBytes.takeIf { metadataConsistent },
                snapshotHash = snapshot.hash,
                managedApplyAuthority = MANAGED_APPLY_AUTHORITY,
                completeProject = false,
            )
        }
    }
}

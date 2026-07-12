package org.refactorkit.daemon

import kotlinx.serialization.Serializable

@Serializable
data class ProtocolProviderDto(val name: String, val version: String)

@Serializable
data class ProtocolDiagnosticDto(
    val severity: String,
    val message: String,
    val code: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val evidence: String? = null,
    val category: String? = null,
)

@Serializable
data class ProtocolDiffHunkDto(
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<String>,
    val truncated: Boolean,
)

@Serializable
data class ProtocolFileDiffDto(
    val path: String,
    val change: String,
    val previousPath: String? = null,
    val hunks: List<ProtocolDiffHunkDto>,
    val truncated: Boolean,
)

@Serializable
data class ProtocolDiffLimitsDto(
    val maxBytes: Int,
    val maxFiles: Int,
    val maxHunksPerFile: Int,
    val maxLinesPerHunk: Int,
)

@Serializable
data class ProtocolFileChangeDto(
    val change: String,
    val path: String,
    val previousPath: String? = null,
    val primary: Boolean = false,
)

@Serializable
data class PlacementDto(
    val moduleName: String? = null,
    val sourceRoot: String? = null,
    val sourceSet: String? = null,
    val packageName: String,
)

@Serializable
data class PackageChangeDto(val from: String, val to: String)

@Serializable
data class ImportProvenanceDto(
    val sourceKind: String? = null,
    val legacySourceKind: String? = null,
    val sourceUrl: String? = null,
    val retrievedAt: String? = null,
    val detectedLicense: String? = null,
    val licenseDetected: String? = null,
    val licenseRisk: String? = null,
    val licensePolicy: String,
    val originalHash: String? = null,
    val notices: List<String> = emptyList(),
)

@Serializable
data class ApplyEligibilityDto(
    val eligible: Boolean,
    val blockers: List<String>,
    val acknowledgementRequirements: List<String>,
)

@Serializable
data class StalenessDto(val stale: Boolean, val reasons: List<String>)

@Serializable
data class SnapshotEvidenceDto(val hash: String, val validatedOnApply: Boolean)

@Serializable
data class ImportPreviewResponseDto(
    val planId: String,
    val operation: String,
    val status: String,
    val legacyStatus: String,
    val summary: String,
    val confidence: Double,
    val riskLevel: String,
    val legacyRiskLevel: String,
    val evidence: List<String>,
    val legacyEvidence: String,
    val affectedFiles: List<ProtocolFileChangeDto>,
    val affectedFilePaths: List<String>,
    val primaryFile: String? = null,
    val placement: PlacementDto,
    val resolvedModule: String? = null,
    val resolvedSourceRoot: String? = null,
    val sourceSet: String? = null,
    val resolvedPackage: String,
    val packageChanges: List<PackageChangeDto>,
    val renderedDiff: String,
    val structuredDiff: List<ProtocolFileDiffDto>,
    val diffTruncated: Boolean,
    val diffTruncationReasons: List<String>,
    val diffLimits: ProtocolDiffLimitsDto,
    val warnings: List<String>,
    val diagnosticsAfterPreview: List<ProtocolDiagnosticDto>,
    val diagnosticsTruncated: Boolean,
    val provenance: ImportProvenanceDto,
    val unresolvedDependencies: List<String>,
    val conflicts: List<String>,
    val refusalReasons: List<String>,
    val applyEligibility: ApplyEligibilityDto,
    val applyEligible: Boolean,
    val staleness: StalenessDto,
    val snapshot: SnapshotEvidenceDto,
    val provider: ProtocolProviderDto,
)

@Serializable
data class ApplyResponseDto(
    val status: String,
    val planId: String,
    val transactionId: String,
    val changedFiles: List<ProtocolFileChangeDto>,
    val changedFilePaths: List<String>,
    val primaryFile: String? = null,
    val diagnostics: List<ProtocolDiagnosticDto>,
    val diagnosticsTruncated: Boolean,
    val snapshotHash: String,
    val provider: ProtocolProviderDto,
)

@Serializable
data class RollbackResponseDto(
    val status: String,
    val transactionId: String,
    val rolledBack: Boolean,
    val changedFiles: List<ProtocolFileChangeDto>,
    val changedFilePaths: List<String>,
    val diagnostics: List<ProtocolDiagnosticDto>,
    val diagnosticsTruncated: Boolean,
    val snapshotHash: String,
    val provider: ProtocolProviderDto,
)

@Serializable
data class DiscardResponseDto(val planId: String, val discarded: Boolean)

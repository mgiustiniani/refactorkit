package org.refactorkit.daemon

import kotlinx.serialization.Serializable

@Serializable
data class BuildModelSummaryLimitsDto(
    val maxModels: Int,
    val maxModules: Int,
    val maxSourceSetsPerModule: Int,
    val maxRootsPerSourceSet: Int,
    val maxModuleDependenciesPerSourceSet: Int,
    val maxDiagnostics: Int,
)

@Serializable
data class BuildModelDiagnosticSummaryDto(
    val code: String,
    val severity: String,
    val moduleId: String? = null,
)

@Serializable
data class BuildModuleDependencySummaryDto(
    val moduleId: String,
    val scope: String,
)

@Serializable
data class BuildSourceSetSummaryDto(
    val id: String,
    val kind: String,
    val sourceRoots: List<String>,
    val generatedSourceRoots: List<String>,
    val outputDirectories: List<String>,
    val moduleDependencies: List<BuildModuleDependencySummaryDto>,
    val truncated: Boolean,
)

@Serializable
data class BuildModuleSummaryDto(
    val id: String,
    val name: String,
    val root: String,
    val sourceSets: List<BuildSourceSetSummaryDto>,
    val truncated: Boolean,
)

@Serializable
data class BuildModelSummaryDto(
    val providerId: String,
    val status: String,
    val ecosystem: String,
    val strategy: String,
    val providers: String,
    val buildCodeExecution: String,
    val credentialsAccess: String,
    val networkDefault: String,
    val networkAccess: String,
    val activeProfiles: List<String>,
    val inactiveProfiles: List<String>,
    val diagnostics: List<BuildModelDiagnosticSummaryDto>,
    val modules: List<BuildModuleSummaryDto>,
    val truncated: Boolean,
)

@Serializable
data class ProjectModuleSummaryDto(val name: String, val root: String)

@Serializable
data class ProjectSummaryResponseDto(
    val root: String,
    val fileCount: Int,
    val snapshotHash: String,
    val buildModels: List<BuildModelSummaryDto>,
    val buildModelsTruncated: Boolean,
    val buildModelLimits: BuildModelSummaryLimitsDto,
    val modules: List<ProjectModuleSummaryDto>,
    val modulesTruncated: Boolean,
)

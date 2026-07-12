package org.refactorkit.core

import java.nio.file.Path

/** Controls side effects available to a build-model provider. */
data class BuildModelDiscoveryPolicy(
    val networkAccess: NetworkAccess = NetworkAccess.DENY,
    val buildCodeExecution: BuildCodeExecution = BuildCodeExecution.DENY,
    val credentialsAccess: CredentialsAccess = CredentialsAccess.DENY,
) {
    enum class NetworkAccess { DENY, ALLOW_ANONYMOUS }
    enum class BuildCodeExecution { DENY, ALLOW_EXPLICIT }
    enum class CredentialsAccess { DENY, ALLOW_EXPLICIT_REDACTED }
}

data class BuildModelRequest(
    val workspaceRoot: Path,
    val policy: BuildModelDiscoveryPolicy = BuildModelDiscoveryPolicy(),
)

/**
 * Provider boundary for Maven, Gradle, BSP, compilation databases, and future
 * ecosystem models. Providers discover metadata only; they never write source
 * files or apply refactorings.
 */
interface BuildModelProvider {
    val id: String
    fun discover(request: BuildModelRequest): BuildModel
}

enum class BuildModelStatus {
    AVAILABLE,
    PARTIAL,
    UNAVAILABLE,
    EXECUTION_REFUSED,
}

enum class SourceSetKind {
    MAIN,
    TEST,
    INTEGRATION_TEST,
    CUSTOM,
}

enum class DependencyScope {
    COMPILE,
    PROVIDED,
    RUNTIME,
    TEST,
    SYSTEM,
    CUSTOM,
}

data class BuildModelDiagnostic(
    val code: String,
    val message: String,
    val moduleId: String? = null,
    val severity: Diagnostic.Severity = Diagnostic.Severity.ERROR,
)

data class BuildDependency(
    val targetModuleId: String,
    val scope: DependencyScope,
)

data class BuildSourceSet(
    val id: String,
    val kind: SourceSetKind,
    val sourceRoots: List<Path> = emptyList(),
    val generatedSourceRoots: List<Path> = emptyList(),
    val outputDirectories: List<Path> = emptyList(),
    val classpathEntries: List<Path> = emptyList(),
    val moduleDependencies: List<BuildDependency> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "source-set ID must not be blank" }
        require((sourceRoots + generatedSourceRoots + outputDirectories).all(::isSafeRelativeBuildPath)) {
            "source/generated/output paths must be safe workspace-relative paths"
        }
        require(generatedSourceRoots.all { it in sourceRoots }) {
            "generated roots must also be declared source roots"
        }
    }
}

data class BuildModule(
    val id: String,
    val name: String,
    val root: Path,
    val sourceSets: List<BuildSourceSet>,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "build module ID must not be blank" }
        require(name.isNotBlank()) { "build module name must not be blank" }
        require(sourceSets.map(BuildSourceSet::id).distinct().size == sourceSets.size) {
            "source-set IDs must be unique within module $id"
        }
    }
}

private fun isSafeRelativeBuildPath(path: Path): Boolean =
    !path.isAbsolute && !path.normalize().startsWith("..")

data class BuildModel(
    val providerId: String,
    val status: BuildModelStatus,
    val modules: List<BuildModule>,
    val diagnostics: List<BuildModelDiagnostic> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(providerId.isNotBlank()) { "build-model provider ID must not be blank" }
        require(modules.map(BuildModule::id).distinct().size == modules.size) {
            "build module IDs must be unique"
        }
        val moduleIds = modules.map(BuildModule::id).toSet()
        val unknownEdges = modules.flatMap(BuildModule::sourceSets)
            .flatMap(BuildSourceSet::moduleDependencies)
            .map(BuildDependency::targetModuleId)
            .filterNot(moduleIds::contains)
            .distinct()
        require(unknownEdges.isEmpty()) { "build model contains unknown module dependencies: $unknownEdges" }
    }
}

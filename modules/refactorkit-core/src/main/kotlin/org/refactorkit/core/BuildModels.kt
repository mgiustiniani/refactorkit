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

data class BuildModelSelection(
    val activeProfiles: Set<String> = emptySet(),
    val inactiveProfiles: Set<String> = emptySet(),
) {
    init {
        require(activeProfiles.intersect(inactiveProfiles).isEmpty()) {
            "build profiles cannot be both active and inactive"
        }
        require(activeProfiles.size + inactiveProfiles.size <= 64) {
            "build profile selection exceeds the bounded limit"
        }
        require((activeProfiles + inactiveProfiles).all { it.isNotBlank() && it.length <= 128 }) {
            "build profile IDs must be non-blank and at most 128 characters"
        }
    }
}

data class BuildModelRequest(
    val workspaceRoot: Path,
    val policy: BuildModelDiscoveryPolicy = BuildModelDiscoveryPolicy(),
    val selections: Map<String, BuildModelSelection> = emptyMap(),
) {
    init {
        require(selections.size <= 16) { "build provider selections exceed the bounded limit" }
        require(selections.keys.all { it.isNotBlank() && it.length <= 128 }) {
            "build provider selection IDs must be non-blank and at most 128 characters"
        }
    }
}

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
    OFFLINE_MISSING,
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

enum class BuildLanguageEvidence {
    DECLARED,
    DERIVED,
    PARTIAL,
    UNSUPPORTED,
}

/** Typed, defensively detached language projection inside a language-neutral build source set. */
class BuildLanguageFacet(
    val languageId: String,
    val platformId: String,
    val compilerId: String? = null,
    val sourceVersion: String? = null,
    val targetVersion: String? = null,
    val targetRuntimeVersion: String? = null,
    compilerPluginIds: List<String> = emptyList(),
    val evidence: BuildLanguageEvidence,
) {
    private val compilerPluginIdsValue = compilerPluginIds.toList()
    val compilerPluginIds: List<String> get() = compilerPluginIdsValue.toList()

    init {
        require(ID.matches(languageId)) { "build language ID is invalid" }
        require(ID.matches(platformId)) { "build language platform ID is invalid" }
        require(compilerId == null || ID.matches(compilerId)) { "build language compiler ID is invalid" }
        require(listOfNotNull(sourceVersion, targetVersion, targetRuntimeVersion).all {
            it.isNotBlank() && it.length <= 128 && '\u0000' !in it
        }) { "build language version evidence is invalid" }
        require(compilerPluginIdsValue.size <= 64 &&
            compilerPluginIdsValue.distinct().size == compilerPluginIdsValue.size &&
            compilerPluginIdsValue.all(ID::matches)) { "build language compiler-plugin evidence is invalid" }
    }

    fun copy(
        languageId: String = this.languageId,
        platformId: String = this.platformId,
        compilerId: String? = this.compilerId,
        sourceVersion: String? = this.sourceVersion,
        targetVersion: String? = this.targetVersion,
        targetRuntimeVersion: String? = this.targetRuntimeVersion,
        compilerPluginIds: List<String> = this.compilerPluginIdsValue,
        evidence: BuildLanguageEvidence = this.evidence,
    ): BuildLanguageFacet = BuildLanguageFacet(
        languageId, platformId, compilerId, sourceVersion, targetVersion, targetRuntimeVersion,
        compilerPluginIds, evidence,
    )

    override fun equals(other: Any?): Boolean = other is BuildLanguageFacet &&
        languageId == other.languageId && platformId == other.platformId && compilerId == other.compilerId &&
        sourceVersion == other.sourceVersion && targetVersion == other.targetVersion &&
        targetRuntimeVersion == other.targetRuntimeVersion &&
        compilerPluginIdsValue == other.compilerPluginIdsValue && evidence == other.evidence

    override fun hashCode(): Int = listOf(
        languageId, platformId, compilerId, sourceVersion, targetVersion, targetRuntimeVersion,
        compilerPluginIdsValue, evidence,
    ).hashCode()

    override fun toString(): String = "BuildLanguageFacet(" +
        "languageId=$languageId, platformId=$platformId, compilerId=$compilerId, " +
        "sourceVersion=$sourceVersion, targetVersion=$targetVersion, " +
        "targetRuntimeVersion=$targetRuntimeVersion, compilerPluginIds=$compilerPluginIdsValue, " +
        "evidence=$evidence)"

    companion object {
        private val ID = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")
    }
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
    val runtimeClasspathEntries: List<Path> = emptyList(),
    val moduleDependencies: List<BuildDependency> = emptyList(),
    val attributes: Map<String, String> = emptyMap(),
    val languageFacets: List<BuildLanguageFacet> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "source-set ID must not be blank" }
        require((sourceRoots + generatedSourceRoots + outputDirectories).all(::isSafeRelativeBuildPath)) {
            "source/generated/output paths must be safe workspace-relative paths"
        }
        require(generatedSourceRoots.all { it in sourceRoots }) {
            "generated roots must also be declared source roots"
        }
        require(languageFacets.size <= 32) { "language facet count exceeds the bounded source-set limit" }
        require(languageFacets.map(BuildLanguageFacet::languageId).distinct().size == languageFacets.size) {
            "language facets must have unique language IDs within a source set"
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

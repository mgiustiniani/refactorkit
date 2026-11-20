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

/** Availability of an optional build fact; absence is never conflated with an empty value. */
enum class BuildFactAvailability {
    COMPLETE,
    NOT_DECLARED,
    PARTIAL,
    UNAVAILABLE,
    AMBIGUOUS,
}

enum class BuildSourceRootOrigin {
    CONVENTIONAL,
    BUILD_DESCRIPTOR,
    BUILD_PLUGIN,
    GENERATED,
}

data class BuildSourceRootEvidence(
    val path: Path,
    val origin: BuildSourceRootOrigin,
    val providerId: String,
    val descriptorPath: Path? = null,
    val profileId: String? = null,
    val pluginId: String? = null,
    val executionId: String? = null,
    val goal: String? = null,
    val phase: String? = null,
    val availability: BuildFactAvailability = BuildFactAvailability.COMPLETE,
) {
    init {
        require(isSafeRelativeBuildPath(path)) { "source-root evidence must use a safe workspace-relative path" }
        require(descriptorPath == null || isSafeRelativeBuildPath(descriptorPath)) {
            "source-root descriptor evidence must use a safe workspace-relative path"
        }
        require(providerId.isNotBlank() && providerId.length <= 128) { "source-root provider ID is invalid" }
        require(listOfNotNull(profileId, pluginId, executionId, goal, phase).all {
            it.isNotBlank() && it.length <= 256 && '\u0000' !in it
        }) { "source-root provenance detail is invalid" }
    }
}

data class BuildArtifactCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String,
) {
    init {
        require(listOf(groupId, artifactId, version).all { it.isNotBlank() && it.length <= 512 && '\u0000' !in it }) {
            "artifact coordinates must be non-blank and bounded"
        }
    }
}

data class BuildResolvedArtifact(
    val path: Path,
    val coordinates: BuildArtifactCoordinates,
    val extension: String = "jar",
    val classifier: String? = null,
    val scope: DependencyScope? = null,
    val transitive: Boolean = false,
) {
    init {
        require(extension.isNotBlank() && extension.length <= 64 && '\u0000' !in extension) {
            "artifact extension is invalid"
        }
        require(classifier == null || classifier.isNotBlank() && classifier.length <= 256 && '\u0000' !in classifier) {
            "artifact classifier is invalid"
        }
    }
}

data class BuildCompilerOption(
    val name: String,
    val value: String,
    val origin: String,
    val redacted: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name.length <= 256 && '\u0000' !in name) { "compiler option name is invalid" }
        require(value.length <= 16_384 && '\u0000' !in value) { "compiler option value is invalid" }
        require(origin.isNotBlank() && origin.length <= 512 && '\u0000' !in origin) { "compiler option origin is invalid" }
    }
}

/** Effective Gradle/Javac module-path inference for one source set. */
data class BuildJpmsModulePathFact(
    val availability: BuildFactAvailability,
    val inferenceEnabled: Boolean? = null,
    val entries: List<Path> = emptyList(),
    val origin: String? = null,
) {
    init {
        require(entries.size <= 256 && entries.distinct().size == entries.size) {
            "inferred JPMS module-path entries must be bounded and unique"
        }
        require(availability != BuildFactAvailability.COMPLETE || inferenceEnabled != null) {
            "complete JPMS module-path facts require an effective inference setting"
        }
        require(inferenceEnabled != false || entries.isEmpty()) {
            "disabled JPMS module-path inference cannot contain inferred entries"
        }
        require(origin == null || origin.isNotBlank() && origin.length <= 512 && '\u0000' !in origin) {
            "JPMS module-path provenance is invalid"
        }
    }
}

/** Snapshot of one compile-database translation unit; arguments are inert captured data. */
data class BuildCompilationUnit(
    val id: String,
    val sourceFile: Path,
    val workingDirectory: Path,
    val arguments: List<String>,
    val output: String? = null,
    val languageFacets: List<BuildLanguageFacet> = emptyList(),
    val configurationFingerprint: String,
) {
    init {
        require(ID.matches(id)) { "compilation-unit ID is invalid" }
        require(isSafeRelativeBuildPath(sourceFile) && isSafeRelativeBuildPath(workingDirectory) &&
            ProtocolPath.serialize(sourceFile).length <= MAX_PATH_LENGTH &&
            ProtocolPath.serialize(workingDirectory).length <= MAX_PATH_LENGTH) {
            "compilation-unit paths must be safe workspace-relative paths"
        }
        require(arguments.isNotEmpty() && arguments.size <= MAX_ARGUMENTS) {
            "compilation-unit arguments must be non-empty and bounded"
        }
        require(arguments.all { it.length <= MAX_ARGUMENT_LENGTH && '\u0000' !in it } &&
            capturedBytes <= MAX_CAPTURE_BYTES) {
            "compilation-unit arguments exceed the bounded capture limit"
        }
        require(output == null || output.length <= MAX_ARGUMENT_LENGTH && '\u0000' !in output) {
            "compilation-unit output is invalid"
        }
        require(languageFacets.size <= 32 && languageFacets.map(BuildLanguageFacet::languageId).distinct().size == languageFacets.size) {
            "compilation-unit language facets must be unique and bounded"
        }
        require(FINGERPRINT.matches(configurationFingerprint)) {
            "compilation-unit configuration fingerprint must be a SHA-256 digest"
        }
        require(configurationFingerprint == fingerprint(sourceFile, workingDirectory, arguments, output)) {
            "compilation-unit configuration fingerprint does not match its captured invocation"
        }
    }

    val capturedBytes: Long
        get() = arguments.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
            (output?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L)

    companion object {
        private val ID = Regex("[A-Za-z][A-Za-z0-9._:-]{0,255}")
        private val FINGERPRINT = Regex("[a-f0-9]{64}")
        const val MAX_ARGUMENTS = 512
        const val MAX_PATH_LENGTH = 4_096
        const val MAX_ARGUMENT_LENGTH = 8 * 1024 * 1024
        const val MAX_CAPTURE_BYTES = 8 * 1024 * 1024

        /** Hashes the exact inert invocation and its workspace-relative identity using length-delimited UTF-8 fields. */
        fun fingerprint(
            sourceFile: Path,
            workingDirectory: Path,
            arguments: List<String>,
            output: String?,
        ): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            fun field(value: String?) {
                val bytes = value?.toByteArray(Charsets.UTF_8)
                val length = bytes?.size ?: -1
                digest.update(byteArrayOf(
                    (length ushr 24).toByte(), (length ushr 16).toByte(),
                    (length ushr 8).toByte(), length.toByte(),
                ))
                if (bytes != null) digest.update(bytes)
            }
            field("refactorkit.build-compilation-unit.v1")
            field(ProtocolPath.serialize(sourceFile))
            field(ProtocolPath.serialize(workingDirectory))
            field(output)
            field(arguments.size.toString())
            arguments.forEach(::field)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/** Explicit annotation-processing facts only; providers must not execute processors to discover them. */
data class BuildAnnotationProcessorConfiguration(
    val availability: BuildFactAvailability,
    val processorPath: List<BuildResolvedArtifact> = emptyList(),
    val processorPathEntries: List<Path> = emptyList(),
    val processorNames: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap(),
    val mode: String? = null,
    val refusalCode: String? = null,
) {
    init {
        require(processorPath.size <= 256 && processorPathEntries.size <= 256 && processorNames.size <= 256 && options.size <= 256) {
            "annotation processor metadata exceeds its bounded limit"
        }
        require(processorNames.all { it.isNotBlank() && it.length <= 512 && '\u0000' !in it }) {
            "annotation processor name is invalid"
        }
        require(options.all { (key, value) ->
            key.isNotBlank() && key.length <= 512 && '\u0000' !in key && value.length <= 16_384 && '\u0000' !in value
        }) { "annotation processor option is invalid" }
        require(mode == null || mode.isNotBlank() && mode.length <= 128 && '\u0000' !in mode) {
            "annotation processor mode is invalid"
        }
        require(refusalCode == null || refusalCode.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,127}"))) {
            "annotation processor refusal code is invalid"
        }
    }
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
    val sourceRootEvidence: List<BuildSourceRootEvidence> = emptyList(),
    val compilerOptions: List<BuildCompilerOption> = emptyList(),
    val compilerOptionsAvailability: BuildFactAvailability = BuildFactAvailability.UNAVAILABLE,
    val compilerOptionsRefusalCode: String? = null,
    val resolvedArtifacts: List<BuildResolvedArtifact> = emptyList(),
    val resolvedArtifactAvailability: BuildFactAvailability = BuildFactAvailability.UNAVAILABLE,
    val resolvedArtifactRefusalCode: String? = null,
    val annotationProcessors: BuildAnnotationProcessorConfiguration? = null,
    val compilationUnits: List<BuildCompilationUnit> = emptyList(),
    val jpmsModulePath: BuildJpmsModulePathFact? = null,
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
        require(compilationUnits.size <= 4_096 &&
            compilationUnits.map(BuildCompilationUnit::id).distinct().size == compilationUnits.size &&
            compilationUnits.map(BuildCompilationUnit::sourceFile).distinct().size == compilationUnits.size) {
            "compilation units must be bounded and unique by ID and source file"
        }
        require(compilationUnits.sumOf(BuildCompilationUnit::capturedBytes) <= BuildCompilationUnit.MAX_CAPTURE_BYTES) {
            "source-set compilation-unit arguments exceed the bounded capture limit"
        }
        require(sourceRootEvidence.size <= 4_096 && sourceRootEvidence.all { it.path in sourceRoots }) {
            "source-root evidence must refer only to declared roots and remain bounded"
        }
        require(compilerOptions.size <= 1_024) { "compiler option count exceeds the bounded source-set limit" }
        require(compilerOptionsRefusalCode == null || compilerOptionsRefusalCode.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,127}"))) {
            "compiler option refusal code is invalid"
        }
        require(resolvedArtifactRefusalCode == null || resolvedArtifactRefusalCode.matches(Regex("[A-Za-z][A-Za-z0-9._-]{0,127}"))) {
            "artifact-coordinate refusal code is invalid"
        }
        require(resolvedArtifacts.size <= 16_384 && resolvedArtifacts.map(BuildResolvedArtifact::path).distinct().size == resolvedArtifacts.size) {
            "resolved artifact evidence must be unique by path and remain bounded"
        }
        require(resolvedArtifacts.all { it.path in classpathEntries || it.path in runtimeClasspathEntries ||
            annotationProcessors?.processorPath?.any { processor -> processor.path == it.path } == true }) {
            "resolved artifacts must be owned by a compile, runtime or processor path"
        }
        require(jpmsModulePath == null || jpmsModulePath.entries.all { it in classpathEntries }) {
            "inferred JPMS module-path entries must be owned by the source-set compile classpath"
        }
    }
}

data class BuildModule(
    val id: String,
    val name: String,
    val root: Path,
    val sourceSets: List<BuildSourceSet>,
    val attributes: Map<String, String> = emptyMap(),
    val coordinates: BuildArtifactCoordinates? = null,
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

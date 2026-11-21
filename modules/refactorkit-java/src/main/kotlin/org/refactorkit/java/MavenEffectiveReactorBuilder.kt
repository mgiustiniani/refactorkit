package org.refactorkit.java

import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
import org.apache.maven.model.Plugin
import org.apache.maven.model.Repository
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.DefaultModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.model.building.ModelBuildingException
import org.apache.maven.model.resolution.ModelResolver
import org.apache.maven.model.resolution.UnresolvableModelException
import org.apache.maven.model.building.FileModelSource
import org.apache.maven.model.building.ModelSource
import org.apache.maven.artifact.versioning.DefaultArtifactVersion
import org.apache.maven.artifact.versioning.VersionRange
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.refactorkit.core.BuildAnnotationProcessorConfiguration
import org.refactorkit.core.BuildArtifactCoordinates
import org.refactorkit.core.BuildCompilerOption
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildResolvedArtifact
import org.refactorkit.core.BuildSourceRootOrigin
import org.refactorkit.core.BuildSourceRootEvidence
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class MavenCoordinate(val groupId: String, val artifactId: String, val version: String) {
    val key: String get() = "$groupId:$artifactId:$version"
}

internal enum class MavenMissingArtifactEvidenceKind {
    SYSTEM_PATH_SIDECAR,
    LOCAL_REPOSITORY_SELECTED_LEAF,
}

internal data class MavenSelectedDescriptorFact(
    val path: Path,
    val layer: MavenSelectedDescriptorLayer,
    val contentSha256: String,
)

internal data class MavenMissingArtifactSelection(
    val sourceSet: String,
    val projection: String,
    val dependencyPath: String,
    val effectiveScope: String,
)

internal data class MavenMissingArtifactEvidence(
    val identity: String,
    val expectedPath: Path,
    val expectedIdentityManifest: Path?,
    val expectedSha256: String?,
    val providedTypes: Set<String>,
    val leaf: Boolean,
    val kind: MavenMissingArtifactEvidenceKind = MavenMissingArtifactEvidenceKind.SYSTEM_PATH_SIDECAR,
    val groupId: String? = null,
    val artifactId: String? = null,
    val version: String? = null,
    val type: String? = null,
    val classifier: String? = null,
    val extension: String? = null,
    val repositoryRoot: Path? = null,
    val repositoryProvider: String? = null,
    val repositoryLayout: String? = null,
    val repositoryPolicy: String? = null,
    val selectedPom: Path? = null,
    val effectiveModelInputs: Set<Path> = emptySet(),
    val selections: Set<MavenMissingArtifactSelection> = emptySet(),
    val descriptorFacts: Set<MavenSelectedDescriptorFact> = emptySet(),
    val parsedDescriptorIdentityHash: String? = null,
    val fixedRelease: Boolean = false,
    val noRelocation: Boolean = false,
)

internal data class MavenReactorDescriptorFailure(
    val module: String,
    val declaringPom: Path,
    val declaringPomContentSha256: String,
    val moduleDeclarationRange: SourceRange,
    val expectedPath: Path,
    val condition: String,
    val noFollowAbsenceFactHash: String,
) {
    fun structuralMessage(workspaceRoot: Path): String {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val declaring = root.relativize(declaringPom.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val expected = root.relativize(expectedPath.toAbsolutePath().normalize()).toString().replace('\\', '/')
        val range = "${moduleDeclarationRange.start.line}:${moduleDeclarationRange.start.character}-" +
            "${moduleDeclarationRange.end.line}:${moduleDeclarationRange.end.character}"
        return "MAVEN_REACTOR_DESCRIPTOR_MISSING module=$module declaringPom=$declaring " +
            "declaringPomSha256=$declaringPomContentSha256 moduleDeclarationRange=$range " +
            "expectedPath=$expected condition=$condition noFollowAbsenceFactHash=$noFollowAbsenceFactHash"
    }
}

internal data class MavenDependencySelectorRecord(
    val declaringPom: Path,
    val consumer: String,
    val sourceSet: String,
    val projection: String,
    val dependencyPath: String,
    val groupId: String,
    val artifactId: String,
    val declaredVersion: String,
    val declaredScope: String,
    val effectiveScope: String,
    val optional: Boolean,
    val type: String,
    val classifier: String,
    val normalizedVariant: String,
    val outcome: String,
    val reason: String,
    val lookupBoundary: String,
)

internal data class MavenModuleModel(
    val root: Path,
    val coordinate: MavenCoordinate,
    val packaging: String,
    val sourceLevel: Int?,
    val releaseLevel: Int?,
    val primaryMainSourceDirectory: Path?,
    val primaryTestSourceDirectory: Path?,
    val additionalMainSourceDirectories: List<Path>,
    val additionalTestSourceDirectories: List<Path>,
    val sourceRootDeclarations: List<MavenSourceRootDeclaration> = emptyList(),
    val sourceRootFailure: String? = null,
    val mainCompilerOptions: List<BuildCompilerOption> = emptyList(),
    val testCompilerOptions: List<BuildCompilerOption> = emptyList(),
    val mainCompilerOptionsAvailability: BuildFactAvailability = BuildFactAvailability.UNAVAILABLE,
    val testCompilerOptionsAvailability: BuildFactAvailability = BuildFactAvailability.UNAVAILABLE,
    val mainAnnotationProcessors: BuildAnnotationProcessorConfiguration = BuildAnnotationProcessorConfiguration(
        BuildFactAvailability.UNAVAILABLE, refusalCode = "java.context.annotationProcessorContextUnavailable",
    ),
    val testAnnotationProcessors: BuildAnnotationProcessorConfiguration = BuildAnnotationProcessorConfiguration(
        BuildFactAvailability.UNAVAILABLE, refusalCode = "java.context.annotationProcessorContextUnavailable",
    ),
    val testJvmContextFact: MavenTestJvmContextFact = MavenTestJvmContextFact(
        BuildFactAvailability.UNAVAILABLE, "unknown", "java.context.mavenTestJvmMetadataUnavailable",
    ),
    val integrationTestJvmContextFact: MavenTestJvmContextFact = MavenTestJvmContextFact(
        BuildFactAvailability.UNAVAILABLE, "unknown", "java.context.mavenTestJvmMetadataUnavailable",
    ),
    val mainResolvedArtifacts: List<BuildResolvedArtifact> = emptyList(),
    val runtimeResolvedArtifacts: List<BuildResolvedArtifact> = emptyList(),
    val testResolvedArtifacts: List<BuildResolvedArtifact> = emptyList(),
    val mainDependencies: List<MavenCoordinate>,
    val testDependencies: List<MavenCoordinate>,
    val mainDependencyScopes: Map<MavenCoordinate, String>,
    val testDependencyScopes: Map<MavenCoordinate, String>,
    val mainArtifacts: List<Path>,
    val runtimeArtifacts: List<Path>,
    val testArtifacts: List<Path>,
    val systemPathArtifacts: Set<Path>,
    val modelInputs: Set<Path>,
    val importedBoms: Set<Path>,
    val dependencyGraphFailures: List<String>,
    val dependencySelectorRecords: List<MavenDependencySelectorRecord>,
    val missingArtifacts: List<String>,
    val mainMissingArtifacts: List<String>,
    val runtimeMissingArtifacts: List<String>,
    val testMissingArtifacts: List<String>,
    val missingArtifactEvidence: List<MavenMissingArtifactEvidence>,
    val mainMissingArtifactEvidence: List<MavenMissingArtifactEvidence>,
    val testMissingArtifactEvidence: List<MavenMissingArtifactEvidence>,
    val testGeneratedPathHints: Set<String>,
    val kotlinPluginConfigured: Boolean = false,
    val kotlinJvmTarget: String? = null,
    val kotlinTargetJdk: String? = null,
    val kotlinCompilerPlugins: List<String> = emptyList(),
    val modelFailure: String? = null,
    val reactorDescriptorFailure: MavenReactorDescriptorFailure? = null,
    val mainOutputDirectory: Path? = null,
    val testOutputDirectory: Path? = null,
    val unsupportedReleaseValue: String? = null,
    val unsupportedSourceLevelValue: String? = null,
)

internal data class MavenSourceRootDeclaration(
    val path: Path,
    val kind: String,
    val sourceSetId: String,
    val origin: BuildSourceRootOrigin,
    val declaringPom: Path?,
    val pluginId: String,
    val executionId: String?,
    val goal: String?,
    val phase: String?,
    val profileId: String?,
    val availability: BuildFactAvailability,
    val integrationTest: Boolean = false,
    val failsafeFact: MavenFailsafeContextFact? = null,
)

internal data class MavenFailsafeConfiguredValue(
    val name: String,
    val value: String?,
    val origin: String,
    val availability: BuildFactAvailability,
)

internal data class MavenFailsafeExecutionMetadata(
    val pluginId: String,
    val pluginVersion: String?,
    val executionId: String?,
    val goals: List<String>,
    val phase: String?,
    val profileId: String?,
    val selectors: List<MavenFailsafeConfiguredValue>,
    val reports: List<MavenFailsafeConfiguredValue>,
    val availability: BuildFactAvailability,
    val refusalCode: String?,
)

internal data class MavenFailsafeContextFact(
    val availability: BuildFactAvailability,
    val refusalCode: String? = null,
    val executions: List<MavenFailsafeExecutionMetadata> = emptyList(),
)

internal data class MavenTestJvmSetting(
    val name: String,
    val value: String?,
    val origin: String,
    val availability: BuildFactAvailability,
    val redacted: Boolean = false,
)

internal data class MavenTestJvmSelection(
    val kind: String,
    val availability: BuildFactAvailability,
    val origin: String? = null,
    val executable: String? = null,
    val requirements: Map<String, String> = emptyMap(),
    val redacted: Boolean = false,
    val refusalCode: String? = null,
    val toolchainPrerequisite: MavenTestJvmToolchainPrerequisite? = null,
)

internal data class MavenTestJvmToolchainPrerequisite(
    val origin: String,
    val refusalCode: String,
    val requirements: Map<String, String> = emptyMap(),
    val redacted: Boolean = false,
)

internal data class MavenTestJvmExecutionMetadata(
    val pluginId: String,
    val pluginVersion: String?,
    val executionId: String?,
    val goals: List<String>,
    val phase: String?,
    val mode: String,
    val forkSettings: List<MavenTestJvmSetting>,
    val argLineLayers: List<MavenTestJvmSetting>,
    val effectiveArgLineTemplate: String?,
    val availability: BuildFactAvailability,
    val refusalCode: String?,
    val jvmSelection: MavenTestJvmSelection,
)

internal data class MavenTestJvmContextFact(
    val availability: BuildFactAvailability,
    val mode: String,
    val refusalCode: String? = null,
    val executions: List<MavenTestJvmExecutionMetadata> = emptyList(),
    val modelInputs: Set<Path> = emptySet(),
)

private data class MavenTestJvmPluginVersionOrigin(
    val version: String,
    val pom: Path,
    val layer: String,
)

private data class MavenTestJvmInheritedPluginDeclaration(
    val pom: Path,
    val plugin: org.apache.maven.model.Plugin,
    val versionOrigin: MavenTestJvmPluginVersionOrigin,
)

private data class MavenPluginJvmDescriptorDefaults(
    val pluginId: String,
    val version: String,
    val goal: String,
    val jar: Path,
    val jarSha256: String,
    val forkCount: String?,
    val reuseForks: String?,
    val argLineDefault: String?,
    val argLineExpression: String?,
    val jvmDefault: String?,
    val jvmExpression: String?,
)

private data class MavenManagedDependency(val version: String, val scope: String)

private fun String.toBuildDependencyScope(): DependencyScope = when (normalizedScope()) {
    "compile" -> DependencyScope.COMPILE
    "provided" -> DependencyScope.PROVIDED
    "runtime" -> DependencyScope.RUNTIME
    "test" -> DependencyScope.TEST
    "system" -> DependencyScope.SYSTEM
    else -> DependencyScope.CUSTOM
}

internal data class MavenReactorModel(
    val modules: Map<Path, MavenModuleModel>,
    val reactorPomFiles: Set<Path>,
)

/**
 * Builds Maven effective models without executing plugins. Resolution is local-repository-only
 * unless [allowNetwork] is explicitly enabled; even then only anonymous HTTPS Maven Central
 * reads are allowed and no Maven settings or credentials are loaded.
 */
internal class MavenEffectiveReactorBuilder(
    private val localRepository: Path = Path.of(System.getProperty("user.home"), ".m2", "repository"),
    private val allowNetwork: Boolean = false,
    activeProfiles: Set<String> = emptySet(),
    inactiveProfiles: Set<String> = emptySet(),
    private val artifactTransport: MavenArtifactTransport = MavenCentralHttpsTransport,
    private val selectedDescriptorAuthorityContext: MavenSelectedDescriptorAuthorityContext? = null,
    private val mavenRuntime: MavenRuntimeSelection? = null,
) {
    private val activeProfiles = validateProfileIds(activeProfiles)
    private val inactiveProfiles = validateProfileIds(inactiveProfiles)
    init {
        require(this.activeProfiles.intersect(this.inactiveProfiles).isEmpty()) {
            "Maven profiles cannot be both active and inactive"
        }
    }
    private val modelBuilder = DefaultModelBuilderFactory().newInstance()
    private val effectiveCache = ConcurrentHashMap<Path, EffectiveBuild>()

    fun build(workspaceRoot: Path, pomFiles: Collection<Path>): MavenReactorModel {
        val lifecycleCompiler = mavenRuntime?.let(MavenLifecycleCompilerBinding::read)
        val normalizedPoms = pomFiles.map(Path::toAbsolutePath).map(Path::normalize)
            .filter { it.exists() && it.isRegularFile() }.toMutableSet()
        val rawCoordinates = normalizedPoms.mapNotNull(::rawCoordinate).toMap().toMutableMap()
        val resolver = LocalOnlyModelResolver(localRepository, rawCoordinates, allowNetwork, artifactTransport)
        val effective = linkedMapOf<Path, EffectiveBuild>()
        val pending = ArrayDeque(normalizedPoms)
        while (pending.isNotEmpty()) {
            val pom = pending.removeFirst()
            val result = buildEffective(pom, resolver)
            effective[pom] = result
            // Effective declarations authorize individual descriptors, not recursive scanning
            // of the build-output directory that happens to contain a real reactor module.
            result.model?.modules.orEmpty().forEach { module ->
                val childPom = declaredReactorPom(workspaceRoot, pom, module) ?: return@forEach
                if (normalizedPoms.add(childPom)) {
                    rawCoordinate(childPom)?.let { (coordinate, path) -> rawCoordinates[coordinate] = path }
                    pending.addLast(childPom)
                }
            }
            resolver.reactorModels = rawCoordinates + effective.mapNotNull { (path, built) ->
                built.model?.coordinate()?.let { it to path }
            }.toMap()
        }
        val effectiveCoordinates = effective.mapNotNull { (pom, result) -> result.model?.coordinate()?.let { it to pom } }.toMap()
        resolver.reactorModels = rawCoordinates + effectiveCoordinates

        val reactorProcessorModules = effective.mapNotNull { (pom, result) ->
            val model = result.model ?: return@mapNotNull null
            val coordinate = model.coordinate() ?: return@mapNotNull null
            val hasRuntimeDependencies = model.dependencies.orEmpty().any { dependency ->
                dependency.type != "pom" && dependency.scope.normalizedScope() in
                    setOf("compile", "provided", "runtime", "system")
            }
            coordinate to MavenReactorProcessorModule(
                packaging = model.packaging?.takeIf(String::isNotBlank) ?: "jar",
                outputDirectory = outputDirectories(workspaceRoot, model, pom).main,
                hasRuntimeDependencies = hasRuntimeDependencies,
            )
        }.groupBy({ it.first }, { it.second })

        val discoveredModules = effective.mapValues { (pom, result) ->
            val model = result.model
            if (model == null) {
                val fallback = rawCoordinate(pom)?.first ?: MavenCoordinate("unknown", pom.parent.fileName.toString(), "unknown")
                return@mapValues MavenModuleModel(
                    root = pom.parent,
                    coordinate = fallback,
                    packaging = "jar",
                    sourceLevel = null,
                    releaseLevel = null,
                    primaryMainSourceDirectory = null,
                    primaryTestSourceDirectory = null,
                    additionalMainSourceDirectories = emptyList(),
                    additionalTestSourceDirectories = emptyList(),
                    mainDependencies = emptyList(),
                    testDependencies = emptyList(),
                    mainDependencyScopes = emptyMap(),
                    testDependencyScopes = emptyMap(),
                    mainArtifacts = emptyList(),
                    runtimeArtifacts = emptyList(),
                    testArtifacts = emptyList(),
                    systemPathArtifacts = emptySet(),
                    modelInputs = result.inputs + setOf(pom),
                    importedBoms = result.importedBoms,
                    dependencyGraphFailures = listOf(concise(result.failure ?: "effective Maven model unavailable")),
                    dependencySelectorRecords = emptyList(),
                    missingArtifacts = emptyList(),
                    mainMissingArtifacts = emptyList(),
                    runtimeMissingArtifacts = emptyList(),
                    testMissingArtifacts = emptyList(),
                    missingArtifactEvidence = emptyList(),
                    mainMissingArtifactEvidence = emptyList(),
                    testMissingArtifactEvidence = emptyList(),
                    testGeneratedPathHints = emptySet(),
                    modelFailure = concise(result.failure ?: "effective Maven model unavailable"),
                )
            }
            resolveModule(workspaceRoot, model, pom, resolver, effectiveCoordinates.keys, reactorProcessorModules, result,
                lifecycleCompiler?.takeIf { admitsDefaultCompiler(model, pom) })
        }.mapKeys { it.key.parent.toAbsolutePath().normalize() }
        val rootPom = workspaceRoot.toAbsolutePath().normalize().resolve("pom.xml")
        val rootModel = effective[rootPom]?.model
        val missingActiveDescriptors = activeRootDescriptorFailures(workspaceRoot, rootPom, rootModel)
        val missingModules = missingActiveDescriptors.associate { failure ->
            val moduleRoot = failure.expectedPath.parent.toAbsolutePath().normalize()
            val rootCoordinate = rootModel?.coordinate()
            val message = failure.structuralMessage(workspaceRoot)
            moduleRoot to MavenModuleModel(
                root = moduleRoot,
                coordinate = MavenCoordinate(
                    rootCoordinate?.groupId ?: "unknown",
                    moduleRoot.fileName?.toString() ?: failure.module.substringAfterLast('/'),
                    rootCoordinate?.version ?: "unknown",
                ),
                packaging = "jar",
                sourceLevel = rootModel?.let(::sourceLevel),
                releaseLevel = rootModel?.let(::releaseLevel),
                unsupportedReleaseValue = rootModel?.let(::unsupportedReleaseValue),
                unsupportedSourceLevelValue = rootModel?.let(::unsupportedSourceLevelValue),
                primaryMainSourceDirectory = null,
                primaryTestSourceDirectory = null,
                additionalMainSourceDirectories = emptyList(),
                additionalTestSourceDirectories = emptyList(),
                mainDependencies = emptyList(),
                testDependencies = emptyList(),
                mainDependencyScopes = emptyMap(),
                testDependencyScopes = emptyMap(),
                mainArtifacts = emptyList(),
                runtimeArtifacts = emptyList(),
                testArtifacts = emptyList(),
                systemPathArtifacts = emptySet(),
                modelInputs = setOf(rootPom),
                importedBoms = emptySet(),
                dependencyGraphFailures = listOf(message),
                dependencySelectorRecords = emptyList(),
                missingArtifacts = emptyList(),
                mainMissingArtifacts = emptyList(),
                runtimeMissingArtifacts = emptyList(),
                testMissingArtifacts = emptyList(),
                missingArtifactEvidence = emptyList(),
                mainMissingArtifactEvidence = emptyList(),
                testMissingArtifactEvidence = emptyList(),
                testGeneratedPathHints = emptySet(),
                modelFailure = message,
                reactorDescriptorFailure = failure,
            )
        }
        return MavenReactorModel(discoveredModules + missingModules, normalizedPoms)
    }

    private fun declaredReactorPom(workspaceRoot: Path, declaringPom: Path, module: String): Path? {
        val relative = runCatching { Path.of(module) }.getOrNull()
            ?.takeIf { !it.isAbsolute && module.isNotBlank() } ?: return null
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val candidate = declaringPom.parent.resolve(relative).resolve("pom.xml").normalize()
        if (!candidate.startsWith(workspace)) return null
        val workspaceRelative = workspace.relativize(candidate)
        if (workspaceRelative.any { it.toString() in setOf(".git", ".gradle", ".refactorkit") }) return null
        val observation = noFollowPathObservation(workspace, workspaceRelative) ?: return null
        return candidate.takeIf { observation.finalState == "FILE" }
    }

    private fun activeRootDescriptorFailures(
        workspaceRoot: Path,
        rootPom: Path,
        rootModel: Model?,
    ): List<MavenReactorDescriptorFailure> {
        if (rootModel == null || !Files.isRegularFile(rootPom, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val content = Files.readString(rootPom)
        val contentHash = sha256(rootPom)
        return rootModel.modules.orEmpty().mapNotNull { declaredModule ->
            val relativeModule = runCatching { Path.of(declaredModule).normalize() }.getOrNull()
                ?.takeIf { !it.isAbsolute && !it.startsWith("..") } ?: return@mapNotNull null
            val expected = workspace.resolve(relativeModule).resolve("pom.xml").normalize()
            if (!expected.startsWith(workspace)) return@mapNotNull null
            val relativeExpectedPath = workspace.relativize(expected)
            val noFollowObservation = noFollowPathObservation(workspace, relativeExpectedPath)
                ?: return@mapNotNull null
            if (noFollowObservation.finalState != "ABSENT") return@mapNotNull null
            val declarationPattern = Regex("<module>\\s*(${Regex.escape(declaredModule)})\\s*</module>")
            val declaration = declarationPattern.findAll(content).toList().singleOrNull() ?: return@mapNotNull null
            val valueRange = requireNotNull(declaration.groups[1]).range
            val sourceRange = TextEdits.rangeForOffset(
                content,
                valueRange.first,
                valueRange.last - valueRange.first + 1,
            )
            MavenReactorDescriptorFailure(
                module = declaredModule,
                declaringPom = rootPom,
                declaringPomContentSha256 = contentHash,
                moduleDeclarationRange = sourceRange,
                expectedPath = expected,
                condition = "MISSING",
                noFollowAbsenceFactHash = noFollowObservation.factHash,
            )
        }.sortedBy(MavenReactorDescriptorFailure::module)
    }

    private data class NoFollowPathObservation(
        val finalState: String,
        val factHash: String,
    )

    private fun literalPluginVersion(value: String?): Boolean {
        val version = value?.trim() ?: return false
        return version.length <= 128 && version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]*"))
    }

    private fun inheritedTestJvmPluginDeclaration(
        workspaceRoot: Path,
        pom: Path,
        childModel: Model?,
        effectiveModel: Model,
        effectiveInputs: Set<Path>,
        pluginArtifactId: String,
    ): MavenTestJvmInheritedPluginDeclaration? {
        val child = childModel ?: return null
        val parent = child.parent ?: return null
        if (parent.relativePath == "") return null
        val relativePath = parent.relativePath ?: "../pom.xml"
        if (relativePath.isBlank()) return null
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val parentPom = pom.toAbsolutePath().normalize().parent?.resolve(relativePath)?.toAbsolutePath()?.normalize()
            ?: return null
        if (!parentPom.startsWith(workspace)) return null
        val workspaceRelative = workspace.relativize(parentPom)
        if (noFollowPathObservation(workspace, workspaceRelative)?.finalState != "FILE") return null
        if (effectiveInputs.map { it.toAbsolutePath().normalize() }.none { it == parentPom }) return null
        val parentModel = readRawModel(parentPom) ?: return null
        if (parentModel.parent != null) return null

        val expectedParentCoordinate = listOf(parent.groupId, parent.artifactId, parent.version)
        val actualParentCoordinate = listOf(parentModel.groupId, parentModel.artifactId, parentModel.version)
        if (expectedParentCoordinate.any { it.isNullOrBlank() } || expectedParentCoordinate != actualParentCoordinate) return null

        fun isTarget(plugin: org.apache.maven.model.Plugin): Boolean =
            plugin.artifactId == pluginArtifactId && plugin.groupId in setOf(null, "org.apache.maven.plugins")
        fun hasTargetConfiguration(model: Model): Boolean =
            model.build?.plugins.orEmpty().any(::isTarget) ||
                model.build?.pluginManagement?.plugins.orEmpty().any(::isTarget) ||
                model.profiles.orEmpty().any { profile ->
                    profile.build?.plugins.orEmpty().any(::isTarget) ||
                        profile.build?.pluginManagement?.plugins.orEmpty().any(::isTarget)
                }

        // Only the simple one-parent shape has a source POM we can attest without
        // applying Maven's profile, pluginManagement, or merge rules ourselves.
        if (hasTargetConfiguration(child) || parentModel.build?.pluginManagement?.plugins.orEmpty().any(::isTarget) ||
            parentModel.profiles.orEmpty().any { profile ->
                profile.build?.plugins.orEmpty().any(::isTarget) ||
                    profile.build?.pluginManagement?.plugins.orEmpty().any(::isTarget)
            }) return null
        val declarations = parentModel.build?.plugins.orEmpty().filter(::isTarget)
        if (declarations.size != 1) return null
        val declaration = declarations.single()
        if (declaration.inherited?.equals("false", ignoreCase = true) == true ||
            declaration.executions.orEmpty().any { it.inherited?.equals("false", ignoreCase = true) == true }) return null
        val version = declaration.version?.trim()?.takeIf(::literalPluginVersion) ?: return null
        val effectivePlugins = effectiveModel.build?.plugins.orEmpty().filter(::isTarget)
        if (effectivePlugins.size != 1 ||
            effectivePlugins.single().version?.trim()?.takeIf(::literalPluginVersion) != version) return null
        return MavenTestJvmInheritedPluginDeclaration(
            pom = parentPom,
            plugin = declaration,
            versionOrigin = MavenTestJvmPluginVersionOrigin(version, parentPom, "parent-plugin"),
        )
    }

    private fun inheritedTestJvmPluginVersionOrigin(
        workspaceRoot: Path,
        pom: Path,
        childModel: Model?,
        effectiveModel: Model,
        effectiveInputs: Set<Path>,
        pluginArtifactId: String,
    ): MavenTestJvmPluginVersionOrigin? {
        val child = childModel ?: return null
        val parent = child.parent ?: return null
        if (parent.relativePath == "") return null
        val relativePath = parent.relativePath ?: "../pom.xml"
        if (relativePath.isBlank()) return null
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val parentPom = pom.toAbsolutePath().normalize().parent?.resolve(relativePath)?.toAbsolutePath()?.normalize()
            ?: return null
        if (!parentPom.startsWith(workspace)) return null
        val workspaceRelative = workspace.relativize(parentPom)
        if (noFollowPathObservation(workspace, workspaceRelative)?.finalState != "FILE") return null
        if (effectiveInputs.map { it.toAbsolutePath().normalize() }.none { it == parentPom }) return null
        val parentModel = readRawModel(parentPom) ?: return null
        if (parentModel.parent != null) return null
        val expectedParentCoordinate = listOf(parent.groupId, parent.artifactId, parent.version)
        val actualParentCoordinate = listOf(parentModel.groupId, parentModel.artifactId, parentModel.version)
        if (expectedParentCoordinate.any { it.isNullOrBlank() } || expectedParentCoordinate != actualParentCoordinate) return null

        fun isTarget(plugin: org.apache.maven.model.Plugin): Boolean =
            plugin.artifactId == pluginArtifactId && plugin.groupId in setOf(null, "org.apache.maven.plugins")
        val managed = parentModel.build?.pluginManagement?.plugins.orEmpty().filter(::isTarget)
        val active = parentModel.build?.plugins.orEmpty().filter(::isTarget)
        val profiled = parentModel.profiles.orEmpty().any { profile ->
            profile.build?.plugins.orEmpty().any(::isTarget) ||
                profile.build?.pluginManagement?.plugins.orEmpty().any(::isTarget)
        }
        val declaration = managed.singleOrNull() ?: return null
        if (active.isNotEmpty() || profiled || declaration.configuration != null ||
            declaration.executions.orEmpty().isNotEmpty() ||
            declaration.inherited?.equals("false", ignoreCase = true) == true) return null
        val version = declaration.version?.trim()?.takeIf(::literalPluginVersion) ?: return null

        val effectivePlugins = effectiveModel.build?.plugins.orEmpty().filter(::isTarget)
        val effectiveManaged = effectiveModel.build?.pluginManagement?.plugins.orEmpty().filter(::isTarget)
        val effectiveVersion = when {
            effectivePlugins.size == 1 -> effectivePlugins.single().version?.trim()?.takeIf(::literalPluginVersion)
            effectivePlugins.isNotEmpty() -> null
            effectiveManaged.size == 1 -> effectiveManaged.single().version?.trim()?.takeIf(::literalPluginVersion)
            else -> null
        }
        if (effectiveVersion != version) return null
        return MavenTestJvmPluginVersionOrigin(version, parentPom, "parent-pluginManagement")
    }

    private fun hasActiveJacocoArgLineContributor(effectiveModel: Model): Boolean =
        effectiveModel.build?.plugins.orEmpty().any { plugin ->
            plugin.groupId == "org.jacoco" && plugin.artifactId == "jacoco-maven-plugin" &&
                plugin.executions.orEmpty().any { execution ->
                    execution.goals.orEmpty().any { it in setOf("prepare-agent", "prepare-agent-integration") }
                }
        }

    private fun readPluginJvmDescriptorDefaults(
        pluginArtifactId: String,
        version: String,
        goal: String,
    ): MavenPluginJvmDescriptorDefaults? = runCatching {
        if (pluginArtifactId !in setOf("maven-surefire-plugin", "maven-failsafe-plugin") ||
            !literalPluginVersion(version) || goal !in setOf("test", "integration-test")) return null
        val groupId = "org.apache.maven.plugins"
        val repository = localRepository.toAbsolutePath().normalize()
        val artifactDirectory = repository.resolve(groupId.replace('.', '/')).resolve(pluginArtifactId).resolve(version).normalize()
        val jar = artifactDirectory.resolve("$pluginArtifactId-$version.jar").normalize()
        if (!jar.startsWith(repository) || !isNoFollowRegularFile(jar)) return null
        val before = Files.readAttributes(jar, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (before.size() !in 1L..MAX_TEST_PLUGIN_JAR_BYTES) return null
        val bytes = Files.newInputStream(jar, LinkOption.NOFOLLOW_LINKS).use {
            readBounded(it, MAX_TEST_PLUGIN_JAR_BYTES)
        } ?: return null
        if (bytes.size.toLong() != before.size()) return null
        val after = Files.readAttributes(jar, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (after.isSymbolicLink || !after.isRegularFile || after.size() != before.size() ||
            (before.fileKey() != null && after.fileKey() != before.fileKey())) return null

        var descriptorBytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "META-INF/maven/plugin.xml") {
                    if (descriptorBytes != null || entry.isDirectory || entry.size > MAX_TEST_PLUGIN_DESCRIPTOR_BYTES) return null
                    descriptorBytes = readBounded(zip, MAX_TEST_PLUGIN_DESCRIPTOR_BYTES) ?: return null
                }
                zip.closeEntry()
            }
        }
        val xmlBytes = descriptorBytes ?: return null
        val root = parseMavenMetadataDocument(xmlBytes)
        fun children(parent: org.w3c.dom.Element?, name: String): List<org.w3c.dom.Element> {
            if (parent == null) return emptyList()
            val found = mutableListOf<org.w3c.dom.Element>()
            for (index in 0 until parent.childNodes.length) {
                val child = parent.childNodes.item(index) as? org.w3c.dom.Element ?: continue
                if (child.tagName == name) found += child
            }
            return found
        }
        fun child(parent: org.w3c.dom.Element?, name: String): org.w3c.dom.Element? =
            children(parent, name).singleOrNull()
        fun text(parent: org.w3c.dom.Element?, name: String): String? =
            child(parent, name)?.textContent?.trim()
        if (root.tagName != "plugin" || text(root, "groupId") != groupId ||
            text(root, "artifactId") != pluginArtifactId || text(root, "version") != version) return null
        val mojosNode = child(root, "mojos") ?: return null
        val mojo = children(mojosNode, "mojo").singleOrNull { text(it, "goal") == goal } ?: return null
        val configuration = child(mojo, "configuration") ?: return null
        fun default(name: String): String? {
            val node = child(configuration, name) ?: return null
            return node.getAttributeNode("default-value")?.value?.trim()
        }
        fun expression(name: String): String? = child(configuration, name)?.textContent?.trim()
        val forkCount = default("forkCount") ?: return null
        val reuseForks = default("reuseForks") ?: return null
        MavenPluginJvmDescriptorDefaults(
            pluginId = "$groupId:$pluginArtifactId",
            version = version,
            goal = goal,
            jar = jar,
            jarSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            forkCount = forkCount,
            reuseForks = reuseForks,
            argLineDefault = default("argLine"),
            argLineExpression = expression("argLine"),
            jvmDefault = default("jvm"),
            jvmExpression = expression("jvm"),
        )
    }.getOrNull()

    private fun isNoFollowRegularFile(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        var current = normalized.root ?: return false
        val components = normalized.iterator().asSequence().toList()
        if (components.isEmpty()) return false
        for ((index, component) in components.withIndex()) {
            current = current.resolve(component)
            val attributes = try {
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                return false
            }
            if (attributes.isSymbolicLink) return false
            val last = index == components.lastIndex
            if (last && !attributes.isRegularFile || !last && !attributes.isDirectory) return false
        }
        return true
    }

    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream(minOf(maxBytes, 8_192L).toInt())
        val buffer = ByteArray(8_192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * Captures only deterministic, workspace-relative NOFOLLOW path state. No
     * real paths, timestamps, file keys, permissions, or absolute roots enter
     * the identity.
     */
    private fun noFollowPathObservation(
        workspaceRoot: Path,
        relativePath: Path,
    ): NoFollowPathObservation? {
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val normalizedRelative = relativePath.normalize()
        if (normalizedRelative.isAbsolute || normalizedRelative.startsWith("..") || normalizedRelative.nameCount == 0) {
            return null
        }
        val stateParts = mutableListOf<String>()
        var currentRelative = Path.of("")
        var finalState = ""
        var ancestorAbsent = false
        for ((index, component) in normalizedRelative.withIndex()) {
            currentRelative = currentRelative.resolve(component).normalize()
            val absolute = workspace.resolve(currentRelative).normalize()
            if (!absolute.startsWith(workspace)) return null
            val attributes = if (ancestorAbsent) {
                null
            } else {
                try {
                    Files.readAttributes(
                        absolute,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: NoSuchFileException) {
                    null
                } catch (_: Exception) {
                    return null
                }
            }
            finalState = when {
                attributes == null -> "ABSENT"
                attributes.isDirectory -> "DIRECTORY"
                attributes.isRegularFile -> "FILE"
                attributes.isSymbolicLink -> {
                    val rawTarget = runCatching { Files.readSymbolicLink(absolute) }.getOrNull() ?: return null
                    val resolvedTarget = if (rawTarget.isAbsolute) {
                        rawTarget.toAbsolutePath().normalize()
                    } else {
                        absolute.parent.resolve(rawTarget).normalize()
                    }
                    if (!resolvedTarget.startsWith(workspace)) return null
                    val targetIdentity = workspace.relativize(resolvedTarget)
                        .toString().replace('\\', '/').ifBlank { "." }
                    "SYMLINK:$targetIdentity"
                }
                else -> "OTHER"
            }
            stateParts += "component=${currentRelative.toString().replace('\\', '/')}"
            stateParts += "state=$finalState"
            if (index < normalizedRelative.nameCount - 1) {
                when (finalState) {
                    "DIRECTORY" -> Unit
                    "ABSENT" -> ancestorAbsent = true
                    else -> return null
                }
            }
        }
        return NoFollowPathObservation(
            finalState = finalState,
            factHash = hashParts(listOf(
                "domain=refactorkit.maven.activeReactorDescriptor.noFollowState",
                "version=1",
                "path=${normalizedRelative.toString().replace('\\', '/')}",
            ) + stateParts),
        )
    }

    private fun resolveModule(
        workspaceRoot: Path,
        model: Model,
        pom: Path,
        resolver: LocalOnlyModelResolver,
        reactorCoordinates: Set<MavenCoordinate>,
        reactorProcessorModules: Map<MavenCoordinate, List<MavenReactorProcessorModule>>,
        effective: EffectiveBuild,
        lifecycleCompiler: MavenLifecycleCompilerBinding?,
    ): MavenModuleModel {
        val moduleCoordinate = requireNotNull(model.coordinate())
        val mainDirect = model.dependencies.filter { it.scope.normalizedScope() in MAIN_SCOPES && it.type != "pom" }
        val testDirect = model.dependencies.filter { it.scope.normalizedScope() in TEST_SCOPES && it.type != "pom" }
        val systemDirect = model.dependencies.filter { it.scope.normalizedScope() == "system" && it.type != "pom" }
        val mainRepositoryDirect = mainDirect.filterNot { it.scope.normalizedScope() == "system" }
        val runtimeRepositoryDirect = model.dependencies.filter {
            it.scope.normalizedScope() in TRANSITIVE_SCOPES && it.type != "pom"
        }
        val mainReactorDirect = model.dependencies.filter {
            it.scope.normalizedScope() in setOf("compile", "provided", "runtime") && it.type != "pom"
        }
        val testRepositoryDirect = testDirect.filterNot { it.scope.normalizedScope() == "system" }
        val managedDependencies = model.dependencyManagement?.dependencies.orEmpty().mapNotNull { dependency ->
            dependency.coordinate()?.let { coordinate ->
                dependency.managementKey()?.let { key ->
                    key to MavenManagedDependency(coordinate.version, dependency.scope.normalizedScope())
                }
            }
        }.toMap()
        val mainMissing = linkedSetOf<String>()
        val runtimeMissing = linkedSetOf<String>()
        val testOnlyMissing = linkedSetOf<String>()
        val mainMissingEvidence = linkedSetOf<MavenMissingArtifactEvidence>()
        val selectedMissingEvidence = linkedMapOf<String, MavenMissingArtifactEvidence>()
        val modelInputs = linkedSetOf<Path>().apply { addAll(effective.inputs); add(pom) }
        val importedBoms = linkedSetOf<Path>().apply { addAll(effective.importedBoms) }
        val dependencyGraphFailures = linkedSetOf<String>()
        val dependencySelectorRecords = linkedSetOf<MavenDependencySelectorRecord>()
        val sourceDirectories = sourceDirectories(workspaceRoot, model, pom, effective.inputs)
        val outputDirectories = outputDirectories(workspaceRoot, model, pom)
        val systemPathArtifacts = resolveSystemPaths(systemDirect, pom, mainMissing, mainMissingEvidence)
        val mainResolvedArtifacts = mutableListOf<BuildResolvedArtifact>()
        val runtimeResolvedArtifacts = mutableListOf<BuildResolvedArtifact>()
        val testOnlyResolvedArtifacts = mutableListOf<BuildResolvedArtifact>()
        val mainArtifacts = (systemPathArtifacts + resolveGraph(
            mainRepositoryDirect, MAIN_REPOSITORY_SCOPES, resolver, reactorCoordinates,
            mainMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "main", "COMPILE",
            dependencySelectorRecords, selectedMissingEvidence, mainResolvedArtifacts,
        )).distinct()
        val runtimeArtifacts = resolveGraph(
            runtimeRepositoryDirect, RUNTIME_SCOPES, resolver, reactorCoordinates,
            runtimeMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "main", "RUNTIME",
            dependencySelectorRecords, selectedMissingEvidence, runtimeResolvedArtifacts,
        ).distinct()
        val testArtifacts = (mainArtifacts + runtimeArtifacts + resolveGraph(
            testRepositoryDirect, TEST_REPOSITORY_SCOPES, resolver, reactorCoordinates,
            testOnlyMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "test", "TEST",
            dependencySelectorRecords, selectedMissingEvidence, testOnlyResolvedArtifacts,
        )).distinct()
        val (mainCompilerOptions, mainCompilerOptionsAvailability) = compilerOptions(model, test = false, lifecycleCompiler)
        val (testCompilerOptions, testCompilerOptionsAvailability) = compilerOptions(model, test = true, lifecycleCompiler)
        lifecycleCompiler?.let { modelInputs.add(it.coreJar) }
        val testJvmContextFact = mavenTestJvmContext(
            workspaceRoot, pom, model, effective.inputs, "maven-surefire-plugin",
        )
        val integrationTestJvmContextFact = mavenTestJvmContext(
            workspaceRoot, pom, model, effective.inputs, "maven-failsafe-plugin",
            sourceDirectories.declarations.filter(MavenSourceRootDeclaration::integrationTest)
                .map(MavenSourceRootDeclaration::profileId).distinct().singleOrNull(),
        )
        modelInputs += testJvmContextFact.modelInputs
        modelInputs += integrationTestJvmContextFact.modelInputs
        systemDirect.forEach { dependency ->
            val path = dependency.systemPath?.takeIf(String::isNotBlank)
                ?.let { runCatching { Path.of(it).toAbsolutePath().normalize() }.getOrNull() }
                ?.takeIf { it in systemPathArtifacts }
            val coordinates = dependency.coordinate()
            if (path != null && coordinates != null) mainResolvedArtifacts += BuildResolvedArtifact(
                path = path,
                coordinates = BuildArtifactCoordinates(coordinates.groupId, coordinates.artifactId, coordinates.version),
                extension = dependency.type.ifBlank { "jar" },
                classifier = dependency.classifier?.takeIf(String::isNotBlank),
                scope = DependencyScope.SYSTEM,
            )
        }
        val testMissing = (mainMissing + runtimeMissing + testOnlyMissing).toList()
        val selectedMissing = selectedMissingEvidence.values.toList()
        val selectedMainMissing = selectedMissing.filter { evidence ->
            evidence.selections.any { it.projection == "COMPILE" }
        }
        val selectedTestMissing = selectedMissing.filter { evidence ->
            evidence.selections.any { it.projection == "TEST" }
        }
        return MavenModuleModel(
            root = pom.parent,
            coordinate = moduleCoordinate,
            packaging = model.packaging?.takeIf(String::isNotBlank) ?: "jar",
            sourceLevel = sourceLevel(model),
            releaseLevel = releaseLevel(model),
            unsupportedReleaseValue = unsupportedReleaseValue(model),
            unsupportedSourceLevelValue = unsupportedSourceLevelValue(model),
            primaryMainSourceDirectory = sourceDirectories.primaryMain,
            primaryTestSourceDirectory = sourceDirectories.primaryTest,
            additionalMainSourceDirectories = sourceDirectories.additionalMain,
            additionalTestSourceDirectories = sourceDirectories.additionalTest,
            sourceRootDeclarations = sourceDirectories.declarations,
            sourceRootFailure = sourceDirectories.failure,
            mainCompilerOptions = mainCompilerOptions,
            testCompilerOptions = testCompilerOptions,
            mainCompilerOptionsAvailability = mainCompilerOptionsAvailability,
            testCompilerOptionsAvailability = testCompilerOptionsAvailability,
            mainAnnotationProcessors = annotationProcessorConfiguration(
                model, test = false, resolver = resolver, moduleRoot = pom.parent,
                reactorProcessorModules = reactorProcessorModules,
            ),
            testAnnotationProcessors = annotationProcessorConfiguration(
                model, test = true, resolver = resolver, moduleRoot = pom.parent,
                reactorProcessorModules = reactorProcessorModules,
            ),
            testJvmContextFact = testJvmContextFact,
            integrationTestJvmContextFact = integrationTestJvmContextFact,
            mainResolvedArtifacts = mainResolvedArtifacts.distinctBy { it.path },
            runtimeResolvedArtifacts = runtimeResolvedArtifacts.distinctBy { it.path },
            testResolvedArtifacts = (mainResolvedArtifacts + runtimeResolvedArtifacts + testOnlyResolvedArtifacts)
                .distinctBy { it.path },
            mainDependencies = mainReactorDirect.filter(::isReactorSourceDependency)
                .mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            testDependencies = testRepositoryDirect.filter(::isReactorSourceDependency)
                .mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            mainDependencyScopes = reactorDependencyScopes(mainReactorDirect, reactorCoordinates),
            testDependencyScopes = reactorDependencyScopes(testRepositoryDirect, reactorCoordinates),
            mainArtifacts = mainArtifacts,
            runtimeArtifacts = runtimeArtifacts,
            testArtifacts = testArtifacts,
            systemPathArtifacts = systemPathArtifacts.toSet(),
            modelInputs = modelInputs,
            importedBoms = importedBoms,
            dependencyGraphFailures = dependencyGraphFailures.toList(),
            dependencySelectorRecords = dependencySelectorRecords.toList(),
            missingArtifacts = testMissing,
            mainMissingArtifacts = mainMissing.toList(),
            runtimeMissingArtifacts = runtimeMissing.toList(),
            testMissingArtifacts = testMissing,
            missingArtifactEvidence = (mainMissingEvidence + selectedMissing).distinct(),
            mainMissingArtifactEvidence = (mainMissingEvidence + selectedMainMissing).distinct(),
            testMissingArtifactEvidence = (mainMissingEvidence + selectedTestMissing).distinct(),
            testGeneratedPathHints = model.build?.plugins.orEmpty()
                .filter { plugin -> plugin.executions.any { it.phase?.contains("test", ignoreCase = true) == true } }
                .map { it.artifactId.removeSuffix("-maven-plugin").removeSuffix("-plugin") }
                .filter(String::isNotBlank).toSet(),
            kotlinPluginConfigured = kotlinPlugin(model) != null,
            kotlinJvmTarget = kotlinJvmTarget(model),
            kotlinTargetJdk = kotlinTargetJdk(model),
            kotlinCompilerPlugins = kotlinCompilerPlugins(model),
            modelFailure = listOfNotNull(sourceDirectories.failure, outputDirectories.failure)
                .joinToString("; ").takeIf(String::isNotBlank),
            mainOutputDirectory = outputDirectories.main,
            testOutputDirectory = outputDirectories.test,
        )
    }

    private data class OutputDirectories(val main: Path?, val test: Path?, val failure: String?)

    private data class MavenReactorProcessorModule(
        val packaging: String,
        val outputDirectory: Path?,
        val hasRuntimeDependencies: Boolean,
    )

    private fun outputDirectories(workspaceRoot: Path, model: Model, pom: Path): OutputDirectories {
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val workspaceReal = runCatching { workspace.toRealPath() }.getOrDefault(workspace)
        var unsafe = 0
        fun normalize(raw: String?): Path? {
            val path = runCatching {
                require(!raw.isNullOrBlank() && !raw.contains("\${"))
                val parsed = Path.of(raw)
                val absolute = (if (parsed.isAbsolute) parsed else pom.parent.resolve(parsed)).toAbsolutePath().normalize()
                require(absolute.startsWith(workspace))
                var current = workspace
                for (component in workspace.relativize(absolute)) {
                    current = current.resolve(component)
                    if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                        require(Files.isDirectory(current) && current.toRealPath().startsWith(workspaceReal))
                    }
                }
                absolute
            }.getOrNull()
            if (path == null) unsafe++
            return path
        }
        val main = normalize(model.build?.outputDirectory)
        val test = normalize(model.build?.testOutputDirectory)
        return OutputDirectories(main, test,
            if (unsafe == 0) null else "$unsafe Maven output directory declaration(s) escape or cannot be validated inside the workspace")
    }

    private data class SourceDirectories(
        val primaryMain: Path?,
        val primaryTest: Path?,
        val additionalMain: List<Path>,
        val additionalTest: List<Path>,
        val declarations: List<MavenSourceRootDeclaration>,
        val failure: String?,
    )

    private fun kotlinPlugin(model: Model) = model.build?.plugins.orEmpty().firstOrNull { plugin ->
        plugin.artifactId == "kotlin-maven-plugin" && plugin.groupId in setOf(null, "org.jetbrains.kotlin")
    }

    private fun kotlinJvmTarget(model: Model): String? = kotlinConfigurations(model).firstNotNullOfOrNull { configuration ->
        configuration.getChild("jvmTarget")?.value?.normalizeJvmLevel()
            ?: configuration.getChild("compilerOptions")?.getChild("jvmTarget")?.value?.normalizeJvmLevel()
    }

    private fun kotlinTargetJdk(model: Model): String? = kotlinConfigurations(model).firstNotNullOfOrNull { configuration ->
        configuration.getChild("jdkToolchain")?.getChild("version")?.value?.normalizeJvmLevel()
    } ?: model.properties.getProperty("maven.compiler.release")?.normalizeJvmLevel()

    private fun kotlinCompilerPlugins(model: Model): List<String> {
        val plugin = kotlinPlugin(model) ?: return emptyList()
        fun normalized(value: String): String = when (val id = value.trim().lowercase()) {
            "allopen", "all-open" -> "all-open"
            "noarg", "no-arg" -> "no-arg"
            else -> id
        }
        val configured = kotlinConfigurations(model).flatMap { configuration ->
            val plugins = configuration.getChild("compilerPlugins")?.children.orEmpty()
                .filter { it.name == "plugin" }
                .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }
            val options = configuration.getChild("pluginOptions")?.children.orEmpty()
                .filter { it.name in setOf("option", "pluginOption") }
                .mapNotNull { it.value?.substringBefore(':')?.trim()?.takeIf(String::isNotBlank) }
            val argsNode = configuration.getChild("args")
            val compilerArgs = (argsNode?.children.orEmpty().mapNotNull { it.value } +
                listOfNotNull(argsNode?.value))
                .map(String::trim)
                .filter(String::isNotBlank)
                .mapNotNull { argument ->
                    when {
                        argument.startsWith("-Xplugin") -> "external-xplugin"
                        argument.startsWith("plugin:") -> argument.removePrefix("plugin:").substringBefore(':')
                        else -> null
                    }
                }
            plugins + options + compilerArgs
        }
        val dependencies = plugin.dependencies.orEmpty().mapNotNull { dependency ->
            dependency.artifactId?.removePrefix("kotlin-maven-")
                ?.takeIf { it.isNotBlank() && it != "plugin" }
        }
        val goals = plugin.executions.flatMap { it.goals }.mapNotNull { goal ->
            when {
                goal.contains("kapt", ignoreCase = true) -> "kapt"
                goal.contains("ksp", ignoreCase = true) -> "ksp"
                else -> null
            }
        }
        val separatePlugins = model.build?.plugins.orEmpty().mapNotNull { candidate ->
            candidate.artifactId?.lowercase()?.let { artifact ->
                when {
                    "ksp" in artifact || "symbol-processing" in artifact -> "ksp"
                    "kapt" in artifact -> "kapt"
                    else -> null
                }
            }
        }
        return (configured + dependencies + goals + separatePlugins)
            .map(::normalized).filter(String::isNotBlank).distinct().sorted()
    }

    private fun kotlinConfigurations(model: Model): List<org.codehaus.plexus.util.xml.Xpp3Dom> {
        val plugin = kotlinPlugin(model) ?: return emptyList()
        return listOfNotNull(plugin.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom) +
            plugin.executions.mapNotNull { it.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom }
    }

    private fun String.normalizeJvmLevel(): String? = trim().removePrefix("1.").toIntOrNull()
        ?.takeIf { it >= 1 }?.toString()

    private fun sourceDirectories(
        workspaceRoot: Path,
        model: Model,
        pom: Path,
        effectiveInputs: Set<Path>,
    ): SourceDirectories {
        val primaryMain = model.build?.sourceDirectory?.takeIf(String::isNotBlank)
        val primaryTest = model.build?.testSourceDirectory?.takeIf(String::isNotBlank)
        val rawModel = readRawModel(pom)
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val workspaceReal = runCatching { workspace.toRealPath() }.getOrDefault(workspace)
        var unsafe = 0
        val failures = mutableListOf<String>()
        fun normalize(raw: String): Path? {
            val parsed = runCatching { Path.of(raw) }.getOrNull() ?: run { unsafe++; return null }
            val base = pom.parent ?: return null
            val absolute = (if (parsed.isAbsolute) parsed else base.resolve(parsed)).toAbsolutePath().normalize()
            if (!absolute.startsWith(workspace)) { unsafe++; return null }
            if (absolute.exists()) {
                val real = runCatching { absolute.toRealPath() }.getOrNull()
                if (real == null || !real.startsWith(workspaceReal)) { unsafe++; return null }
            }
            return absolute
        }
        val declarations = mutableListOf<MavenSourceRootDeclaration>()
        fun sourceSetId(role: String, pluginId: String, executionId: String?, goal: String?, profileId: String?): String =
            "maven-$role-${hashParts(listOf(pluginId, executionId.orEmpty(), goal.orEmpty(), profileId.orEmpty())).take(16)}"

        fun addRoot(
            raw: String,
            kind: String,
            role: String,
            origin: BuildSourceRootOrigin,
            pluginId: String,
            executionId: String? = null,
            goal: String? = null,
            phase: String? = null,
            profileId: String? = null,
            availability: BuildFactAvailability = BuildFactAvailability.COMPLETE,
            integrationTest: Boolean = false,
            failsafeFact: MavenFailsafeContextFact? = null,
        ) {
            val path = normalize(raw) ?: return
            declarations += MavenSourceRootDeclaration(
                path = path,
                kind = kind,
                sourceSetId = if (role == "main" || role == "test") role
                    else sourceSetId(role, pluginId, executionId, goal, profileId),
                origin = origin,
                declaringPom = pom.takeIf { availability == BuildFactAvailability.COMPLETE },
                pluginId = pluginId,
                executionId = executionId,
                goal = goal,
                phase = phase,
                profileId = profileId,
                availability = availability,
                integrationTest = integrationTest,
                failsafeFact = failsafeFact,
            )
        }

        fun primaryOrigin(raw: String, test: Boolean): Pair<BuildSourceRootOrigin, BuildFactAvailability> {
            val rawConfigured = if (test) rawModel?.build?.testSourceDirectory else rawModel?.build?.sourceDirectory
            if (rawConfigured.isNullOrBlank()) {
                val conventional = pom.parent.resolve(if (test) "src/test/java" else "src/main/java")
                    .toAbsolutePath().normalize()
                val effective = normalize(raw)
                return if (effective == conventional) BuildSourceRootOrigin.CONVENTIONAL to BuildFactAvailability.COMPLETE
                else BuildSourceRootOrigin.BUILD_DESCRIPTOR to BuildFactAvailability.PARTIAL
            }
            val configuredPath = runCatching {
                val path = Path.of(rawConfigured)
                (if (path.isAbsolute) path else pom.parent.resolve(path)).toAbsolutePath().normalize()
            }.getOrNull()
            val effectivePath = normalize(raw)
            return if (configuredPath != null && configuredPath == effectivePath) {
                BuildSourceRootOrigin.BUILD_DESCRIPTOR to BuildFactAvailability.COMPLETE
            } else BuildSourceRootOrigin.BUILD_DESCRIPTOR to BuildFactAvailability.PARTIAL
        }
        primaryMain?.let { raw ->
            val (origin, availability) = primaryOrigin(raw, test = false)
            addRoot(raw, "main", "main", origin, "maven-build", availability = availability)
        }
        primaryTest?.let { raw ->
            val (origin, availability) = primaryOrigin(raw, test = true)
            addRoot(raw, "test", "test", origin, "maven-build", availability = availability)
        }

        fun roots(configuration: Any?, nodeName: String): List<String> {
            val xml = configuration as? org.codehaus.plexus.util.xml.Xpp3Dom ?: return emptyList()
            return xml.getChild(nodeName)?.children.orEmpty()
                .filter { it.name in setOf("source", "sourceDir") }
                .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }
        }

        fun profileFor(pluginId: String, executionId: String?, goal: String?, rawRoot: String): Pair<String?, BuildFactAvailability> {
            val (group, artifact) = pluginId.split(':', limit = 2).let { it.first() to it.getOrElse(1) { "" } }
            fun matchingExecutions(plugin: org.apache.maven.model.Plugin, execution: org.apache.maven.model.PluginExecution?): Boolean {
                if (plugin.artifactId != artifact || plugin.groupId !in setOf(null, group)) return false
                if (executionId == null) return execution == null
                val candidateId = execution?.id?.takeIf(String::isNotBlank) ?: "default"
                val selectedId = executionId.takeIf(String::isNotBlank) ?: "default"
                return execution != null && candidateId == selectedId && (goal == null || goal in execution.goals)
            }
            data class Origin(val profileId: String?, val rootMatches: Boolean)
            val base = rawModel?.build?.plugins.orEmpty().flatMap { plugin ->
                buildList {
                    if (matchingExecutions(plugin, null)) {
                        val pluginRoots = roots(plugin.configuration, "sources") + roots(plugin.configuration, "sourceDirs")
                        if (rawRoot in pluginRoots) add(Origin(null, true))
                    }
                    plugin.executions.filter { matchingExecutions(plugin, it) }.forEach { execution ->
                        val executionRoots = roots(execution.configuration, "sources") + roots(execution.configuration, "sourceDirs")
                        if (rawRoot in executionRoots) add(Origin(null, true))
                    }
                }
            }
            val profileOrigins = rawModel?.profiles.orEmpty().flatMap { profile ->
                profile.build?.plugins.orEmpty().flatMap { plugin ->
                    buildList {
                        if (matchingExecutions(plugin, null)) {
                            val pluginRoots = roots(plugin.configuration, "sources") + roots(plugin.configuration, "sourceDirs")
                            if (rawRoot in pluginRoots) add(Origin(profile.id, true))
                        }
                        plugin.executions.filter { matchingExecutions(plugin, it) }.forEach { execution ->
                            val executionRoots = roots(execution.configuration, "sources") + roots(execution.configuration, "sourceDirs")
                            if (rawRoot in executionRoots) add(Origin(profile.id, true))
                        }
                    }
                }
            }
            val exact = base + profileOrigins
            return when (exact.size) {
                0 -> null to BuildFactAvailability.PARTIAL
                1 -> exact.single().profileId to BuildFactAvailability.COMPLETE
                else -> null to BuildFactAvailability.AMBIGUOUS
            }
        }

        fun failsafeIntegrationExecutions(profileId: String?): List<Pair<org.apache.maven.model.Plugin, org.apache.maven.model.PluginExecution>> {
            val localPlugins = if (profileId == null) {
                rawModel?.build?.plugins.orEmpty()
            } else {
                rawModel?.profiles.orEmpty().singleOrNull { it.id == profileId }?.build?.plugins.orEmpty()
            }
            val inheritedPlugins = if (profileId == null && localPlugins.none {
                    it.artifactId == "maven-failsafe-plugin" && it.groupId in setOf(null, "org.apache.maven.plugins")
                }) {
                inheritedTestJvmPluginDeclaration(
                    workspaceRoot, pom, rawModel, model, effectiveInputs, "maven-failsafe-plugin",
                )?.let { listOf(it.plugin) }.orEmpty()
            } else emptyList()
            return (localPlugins + inheritedPlugins)
                .filter { it.artifactId == "maven-failsafe-plugin" && it.groupId in setOf(null, "org.apache.maven.plugins") }
                .flatMap { plugin -> plugin.executions.orEmpty()
                    .filter { "integration-test" in it.goals.orEmpty() }
                    .map { plugin to it }
                }
        }

        fun configuredValues(configuration: Any?, origin: String, names: List<Pair<String, String?>>): List<MavenFailsafeConfiguredValue> {
            val xml = configuration as? org.codehaus.plexus.util.xml.Xpp3Dom ?: return emptyList()
            val values = mutableListOf<MavenFailsafeConfiguredValue>()
            names.forEach { (name, itemName) ->
                val node = xml.getChild(name) ?: return@forEach
                val rawValues = if (itemName == null) {
                    listOfNotNull(node.value?.trim()?.takeIf(String::isNotBlank))
                } else {
                    node.children.orEmpty().filter { it.name == itemName }
                        .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }
                }
                rawValues.take(128).forEach { value ->
                    val resolvable = value.length <= 8_192 && !Regex("\\$\\{[^}]+}").containsMatchIn(value)
                    values += MavenFailsafeConfiguredValue(
                        name, value.takeIf { resolvable }, origin,
                        if (resolvable) BuildFactAvailability.COMPLETE else BuildFactAvailability.UNAVAILABLE,
                    )
                }
            }
            return values
        }

        fun failsafeContext(profileId: String?, candidates: List<Pair<org.apache.maven.model.Plugin, org.apache.maven.model.PluginExecution>>): MavenFailsafeContextFact {
            if (candidates.isEmpty()) return MavenFailsafeContextFact(BuildFactAvailability.NOT_DECLARED)
            if (candidates.size > 1) return MavenFailsafeContextFact(
                BuildFactAvailability.AMBIGUOUS, "java.context.mavenFailsafeExecutionAmbiguous",
            )
            val (plugin, execution) = candidates.single()
            val origin = if (profileId == null) "base-execution" else "profile-execution:$profileId"
            val settingNames = listOf(
                "test" to null, "includes" to "include", "excludes" to "exclude",
                "includesFile" to null, "excludesFile" to null, "suiteXmlFiles" to "suiteXmlFile",
                "reportsDirectory" to null, "reportFormat" to null, "reportNameSuffix" to null,
                "summaryFile" to null,
            )
            val selectors = configuredValues(execution.configuration, origin, settingNames.take(6))
            val reports = configuredValues(execution.configuration, origin, settingNames.drop(6))
            val inheritedSettings = configuredValues(plugin.configuration, "plugin-configuration", settingNames).isNotEmpty()
            val version = plugin.version?.trim()?.takeIf(::literalPluginVersion)
                ?: if (profileId == null) inheritedTestJvmPluginVersionOrigin(
                    workspaceRoot, pom, rawModel, model, effectiveInputs, "maven-failsafe-plugin",
                )?.version else null
            val unresolvedSetting = (selectors + reports).any { it.availability != BuildFactAvailability.COMPLETE }
            val complete = version != null && execution.id?.isNotBlank() == true &&
                execution.phase?.isNotBlank() == true && !inheritedSettings && !unresolvedSetting
            val refusal = when {
                inheritedSettings || unresolvedSetting -> "java.context.mavenFailsafeConfigurationIncomplete"
                !complete -> "java.context.mavenFailsafeExecutionIncomplete"
                else -> null
            }
            val executionFact = MavenFailsafeExecutionMetadata(
                pluginId = "${plugin.groupId ?: "org.apache.maven.plugins"}:${plugin.artifactId}",
                pluginVersion = version,
                executionId = execution.id?.takeIf(String::isNotBlank),
                goals = execution.goals.orEmpty().take(32),
                phase = execution.phase?.takeIf(String::isNotBlank),
                profileId = profileId,
                selectors = selectors,
                reports = reports,
                availability = if (complete) BuildFactAvailability.COMPLETE else BuildFactAvailability.PARTIAL,
                refusalCode = refusal,
            )
            return MavenFailsafeContextFact(
                availability = if (complete) BuildFactAvailability.COMPLETE else BuildFactAvailability.PARTIAL,
                refusalCode = refusal,
                executions = listOf(executionFact),
            )
        }

        model.build?.plugins.orEmpty()
            .filter { it.groupId in setOf(null, "org.codehaus.mojo") && it.artifactId == "build-helper-maven-plugin" }
            .forEach { plugin ->
                val pluginId = "${plugin.groupId ?: "org.codehaus.mojo"}:${plugin.artifactId}"
                plugin.executions.forEach { execution ->
                    val sourceGoal = "add-source" in execution.goals
                    val testGoal = "add-test-source" in execution.goals
                    if (!sourceGoal && !testGoal) return@forEach
                    if (sourceGoal && testGoal) {
                        failures += "build-helper execution '${execution.id}' configures both add-source and add-test-source"
                        return@forEach
                    }
                    val goal = if (sourceGoal) "add-source" else "add-test-source"
                    val kind = if (sourceGoal) "main" else "test"
                    val role = if (sourceGoal) "custom-main" else "custom-test"
                    val configuration = execution.configuration
                    val configuredRoots = roots(configuration, "sources")
                    if (configuredRoots.isEmpty()) {
                        failures += "build-helper execution '${execution.id}' has no recognized <sources> declaration"
                    }
                    configuredRoots.forEach { raw ->
                        val (profileId, availability) = profileFor(pluginId, execution.id, goal, raw)
                        val failsafeExecutions = failsafeIntegrationExecutions(profileId)
                        val ambiguousFailsafeOwnership = testGoal && availability == BuildFactAvailability.COMPLETE &&
                            failsafeExecutions.size > 1
                        val declarationAvailability = if (ambiguousFailsafeOwnership) {
                            BuildFactAvailability.AMBIGUOUS
                        } else availability
                        val integration = testGoal && availability == BuildFactAvailability.COMPLETE &&
                            failsafeExecutions.size == 1
                        val failsafeFact = when {
                            !testGoal -> null
                            availability != BuildFactAvailability.COMPLETE -> MavenFailsafeContextFact(
                                BuildFactAvailability.UNAVAILABLE, "java.context.mavenFailsafeAssociationUnavailable",
                            )
                            else -> failsafeContext(profileId, failsafeExecutions)
                        }
                        addRoot(raw, kind, role, BuildSourceRootOrigin.BUILD_PLUGIN, pluginId,
                            execution.id, goal, execution.phase, profileId, declarationAvailability,
                            integration, failsafeFact)
                    }
                }
            }

        kotlinPlugin(model)?.let { plugin ->
            val pluginId = "${plugin.groupId ?: "org.jetbrains.kotlin"}:${plugin.artifactId}"
            fun addConfiguredRoots(configuration: Any?, executionId: String?, goal: String?, phase: String?) {
                roots(configuration, "sourceDirs").forEach { raw ->
                    val test = goal?.contains("test", ignoreCase = true) == true
                    val kind = if (test) "test" else "main"
                    val (profileId, availability) = profileFor(pluginId, executionId, goal, raw)
                    addRoot(raw, kind, if (test) "custom-test" else "custom-main",
                        BuildSourceRootOrigin.BUILD_PLUGIN, pluginId, executionId, goal, phase,
                        profileId, availability)
                }
            }
            addConfiguredRoots(plugin.configuration, null, null, null)
            plugin.executions.forEach { execution ->
                val goal = execution.goals.singleOrNull()
                addConfiguredRoots(execution.configuration, execution.id, goal, execution.phase)
            }
        }

        val additionalMain = declarations.filter { it.origin == BuildSourceRootOrigin.BUILD_PLUGIN && it.kind == "main" }.map { it.path }.distinct()
        val additionalTest = declarations.filter { it.origin == BuildSourceRootOrigin.BUILD_PLUGIN && it.kind == "test" }.map { it.path }.distinct()
        return SourceDirectories(
            primaryMain = primaryMain?.let(::normalize),
            primaryTest = primaryTest?.let(::normalize),
            additionalMain = additionalMain.sortedBy(Path::toString),
            additionalTest = additionalTest.sortedBy(Path::toString),
            declarations = declarations.distinct().sortedWith(compareBy<MavenSourceRootDeclaration> { it.sourceSetId }.thenBy { it.path.toString() }),
            failure = buildList {
                if (unsafe > 0) add("$unsafe Maven source root declaration(s) escape or cannot be validated inside the workspace")
                addAll(failures)
            }.takeIf(List<String>::isNotEmpty)?.joinToString("; "),
        )
    }

    private fun mavenTestJvmSelection(
        model: Model,
        layers: List<Pair<org.codehaus.plexus.util.xml.Xpp3Dom, String>>,
        mode: String,
        forkOrigin: String?,
        descriptor: MavenPluginJvmDescriptorDefaults?,
        defaultOrigin: String?,
        uncertain: Boolean,
    ): MavenTestJvmSelection {
        fun unresolved(
            origin: String? = null,
            redacted: Boolean = false,
            prerequisite: MavenTestJvmToolchainPrerequisite? = null,
        ) = MavenTestJvmSelection(
            "unresolved", BuildFactAvailability.UNAVAILABLE, origin, redacted = redacted,
            refusalCode = "java.context.mavenTestJvmSelectionUnavailable",
            toolchainPrerequisite = prerequisite,
        )
        fun toolchain(
            origin: String,
            requirements: Map<String, String> = emptyMap(),
            prerequisite: MavenTestJvmToolchainPrerequisite? = null,
        ) = MavenTestJvmSelection(
            "toolchain", BuildFactAvailability.UNAVAILABLE, origin, requirements = requirements,
            refusalCode = "java.context.mavenTestJvmToolchainUnresolved",
            toolchainPrerequisite = prerequisite,
        )
        if (uncertain || mode !in setOf("forked", "in-process")) return unresolved()
        val names = setOf("jvm", "jdkToolchain")
        if (layers.any { (xml, _) ->
                xml.attributeNames.any { it.startsWith("combine.") } ||
                    names.any { name -> xml.getChildren(name).size > 1 }
            }) return unresolved()

        fun declared(name: String) = layers.mapNotNull { (xml, origin) ->
            xml.getChild(name)?.let { it to "$origin/$name" }
        }
        val toolchains = declared("jdkToolchain")
        val prerequisite = toolchains.lastOrNull()?.let { (node, origin) ->
            if (toolchains.size == 1) mavenTestJvmToolchainPrerequisite(node, origin)
            else MavenTestJvmToolchainPrerequisite(origin, "java.context.mavenTestJvmSelectionUnavailable")
        }
        val executable = declared("jvm").lastOrNull()
        // An explicit fork executable still leaves Maven's toolchain lookup prerequisite in place.
        if (executable != null) {
            val (node, origin) = executable
            val value = node.value?.trim().orEmpty()
            if (safeBuildOption("jvm", value).second) return unresolved(origin, redacted = true, prerequisite = prerequisite)
            if (node.attributeNames.isNotEmpty() || node.childCount != 0 || !isLiteralTestJvmValue(value, 4096)) {
                return unresolved(origin, prerequisite = prerequisite)
            }
            val path = runCatching { Path.of(value) }.getOrNull()
            if (path == null || !path.isAbsolute || path != path.normalize() ||
                path.parent?.fileName?.toString() != "bin" || path.fileName?.toString() !in setOf("java", "java.exe")) {
                return unresolved(origin, prerequisite = prerequisite)
            }
            return if (mode == "in-process") {
                MavenTestJvmSelection("build-jvm", BuildFactAvailability.COMPLETE, forkOrigin, toolchainPrerequisite = prerequisite)
            } else {
                MavenTestJvmSelection("explicit-executable", BuildFactAvailability.COMPLETE, origin, executable = value,
                    toolchainPrerequisite = prerequisite)
            }
        }

        if (prerequisite != null) {
            if (prerequisite.requirements.isEmpty() || mode == "in-process") {
                return unresolved(prerequisite.origin, prerequisite.redacted, prerequisite)
            }
            return toolchain(prerequisite.origin, prerequisite.requirements, prerequisite)
        }

        val buildToolchain = model.build?.plugins.orEmpty().any { plugin ->
            plugin.groupId in setOf(null, "org.apache.maven.plugins") && plugin.artifactId == "maven-toolchains-plugin" &&
                plugin.executions.orEmpty().any { execution ->
                    execution.goals.any { it in setOf("toolchain", "select-jdk-toolchain") }
                }
        }

        if (model.properties.containsKey("jvm")) {
            val property = model.properties.getProperty("jvm").orEmpty()
            return unresolved("effective-model:properties/jvm", safeBuildOption("jvm", property).second)
        }
        if (buildToolchain) return toolchain("effective-model:build/plugins/org.apache.maven.plugins:maven-toolchains-plugin")
        if (mode == "in-process") return MavenTestJvmSelection("build-jvm", BuildFactAvailability.COMPLETE, forkOrigin)
        if (defaultOrigin != null && descriptor?.jvmExpression == "\${jvm}" && descriptor.jvmDefault == null) {
            return MavenTestJvmSelection("build-jvm", BuildFactAvailability.COMPLETE, defaultOrigin)
        }
        return unresolved()
    }

    private fun mavenTestJvmToolchainPrerequisite(
        node: org.codehaus.plexus.util.xml.Xpp3Dom,
        origin: String,
    ): MavenTestJvmToolchainPrerequisite {
        fun unavailable(redacted: Boolean = false) = MavenTestJvmToolchainPrerequisite(
            origin, "java.context.mavenTestJvmSelectionUnavailable", redacted = redacted,
        )
        if (node.attributeNames.isNotEmpty() || node.childCount !in 1..16) return unavailable()
        val requirements = linkedMapOf<String, String>()
        for (child in node.children) {
            val key = child.name
            val value = child.value?.trim().orEmpty()
            if (safeBuildOption(key, value).second) return unavailable(redacted = true)
            if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,127}")) ||
                child.childCount != 0 || child.attributeNames.isNotEmpty() || !isLiteralTestJvmValue(value, 1024) ||
                requirements.put(key, value) != null) return unavailable()
        }
        return MavenTestJvmToolchainPrerequisite(origin, "java.context.mavenTestJvmToolchainUnresolved", requirements)
    }

    private fun isLiteralTestJvmValue(value: String, limit: Int): Boolean =
        value.isNotEmpty() && value.length <= limit && "\${" !in value && "@{" !in value &&
            value.none { it.isISOControl() }

    private fun matchesProfiledFailsafeExecution(declared: Plugin, effective: Plugin): Boolean {
        val version = declared.version?.trim()
        val declaredExecution = declared.executions.orEmpty().singleOrNull() ?: return false
        val effectiveExecution = effective.executions.orEmpty().singleOrNull() ?: return false
        return literalPluginVersion(version) && version == effective.version?.trim() &&
            !declaredExecution.id.isNullOrBlank() && declaredExecution.id == effectiveExecution.id &&
            declaredExecution.phase == "integration-test" && declaredExecution.phase == effectiveExecution.phase &&
            "integration-test" in declaredExecution.goals && declaredExecution.goals == effectiveExecution.goals &&
            matchesProfiledJvmSettings(declared.configuration, effective.configuration) &&
            matchesProfiledJvmSettings(declaredExecution.configuration, effectiveExecution.configuration)
    }

    private fun matchesProfiledJvmSettings(declared: Any?, effective: Any?): Boolean {
        if (declared == null || effective == null) return declared == effective
        val raw = declared as? Xpp3Dom ?: return false
        val merged = effective as? Xpp3Dom ?: return false
        if (raw.attributeNames.isNotEmpty() || merged.attributeNames.isNotEmpty()) return false
        val jvmSettings = setOf("forkCount", "forkMode", "reuseForks", "argLine", "jvm", "jdkToolchain")
        val expected = raw.children.filter { it.name in jvmSettings }
        return expected.map(Xpp3Dom::getName).distinct().size == expected.size &&
            expected == merged.children.filter { it.name in jvmSettings }
    }

    /** Projects JVM facts from a proven declaration without resolving or executing Maven plugins. */
    private fun mavenTestJvmContext(
        workspaceRoot: Path,
        pom: Path,
        effectiveModel: Model,
        effectiveInputs: Set<Path>,
        pluginArtifactId: String,
        integrationProfileId: String? = null,
    ): MavenTestJvmContextFact {
        val model = readRawModel(pom) ?: return MavenTestJvmContextFact(
            BuildFactAvailability.UNAVAILABLE,
            mode = "unknown",
            refusalCode = "java.context.mavenTestJvmMetadataUnavailable",
        )
        fun isTarget(plugin: org.apache.maven.model.Plugin): Boolean =
            plugin.artifactId == pluginArtifactId && plugin.groupId in setOf(null, "org.apache.maven.plugins")
        val directPlugins = model.build?.plugins.orEmpty().filter(::isTarget)
        val managedPlugins = model.build?.pluginManagement?.plugins.orEmpty().filter(::isTarget)
        val profiledPlugins = model.profiles.orEmpty().flatMap { profile ->
            profile.build?.plugins.orEmpty().filter(::isTarget) +
                profile.build?.pluginManagement?.plugins.orEmpty().filter(::isTarget)
        }
        val parentVersionOrigin = inheritedTestJvmPluginVersionOrigin(
            workspaceRoot, pom, model, effectiveModel, effectiveInputs, pluginArtifactId,
        )
        val inheritedPluginDeclaration = inheritedTestJvmPluginDeclaration(
            workspaceRoot, pom, model, effectiveModel, effectiveInputs, pluginArtifactId,
        )
        fun hasTargetTestJvmConfiguration(candidate: Model): Boolean =
            candidate.build?.plugins.orEmpty().any(::isTarget) ||
                candidate.build?.pluginManagement?.plugins.orEmpty().any(::isTarget) ||
                candidate.profiles.orEmpty().any { profile ->
                    profile.build?.plugins.orEmpty().any(::isTarget) ||
                        profile.build?.pluginManagement?.plugins.orEmpty().any(::isTarget)
                }
        fun inheritedTestJvmConfiguration(): Boolean {
            var currentPom = pom.toAbsolutePath().normalize()
            var currentModel = model
            repeat(32) {
                val parent = currentModel.parent ?: return false
                val relativePath = when (parent.relativePath) {
                    "" -> return true
                    null -> "../pom.xml"
                    else -> parent.relativePath
                }
                val candidatePom = currentPom.parent?.resolve(relativePath)?.toAbsolutePath()?.normalize() ?: return true
                val workspace = workspaceRoot.toAbsolutePath().normalize()
                if (!candidatePom.startsWith(workspace)) return true
                val relative = workspace.relativize(candidatePom)
                if (noFollowPathObservation(workspace, relative)?.finalState != "FILE") return true
                if (effectiveInputs.map { it.toAbsolutePath().normalize() }.none { it == candidatePom }) return true
                val candidateModel = readRawModel(candidatePom) ?: return true
                if (hasTargetTestJvmConfiguration(candidateModel)) return true
                currentPom = candidatePom
                currentModel = candidateModel
            }
            return currentModel.parent != null
        }
        val inheritedConfiguration = inheritedTestJvmConfiguration()
        val uncertainInheritedOrigin = inheritedConfiguration &&
            parentVersionOrigin == null && inheritedPluginDeclaration == null
        val profiledPlugin = if (pluginArtifactId == "maven-failsafe-plugin" && integrationProfileId != null &&
            SAFE_PROFILE_ID.matches(integrationProfileId) && directPlugins.isEmpty() && managedPlugins.isEmpty() &&
            !inheritedConfiguration && profiledPlugins.size == 1 &&
            effectiveModel.build?.pluginManagement?.plugins.orEmpty().none(::isTarget)
        ) {
            val effectivePlugin = effectiveModel.build?.plugins.orEmpty().singleOrNull(::isTarget)
            model.profiles.orEmpty().singleOrNull { it.id == integrationProfileId }?.build?.plugins.orEmpty()
                .singleOrNull(::isTarget)?.takeIf { effectivePlugin != null && matchesProfiledFailsafeExecution(it, effectivePlugin) }
        } else null
        val uncertainOrigin = uncertainInheritedOrigin || managedPlugins.isNotEmpty() ||
            (profiledPlugins.isNotEmpty() && profiledPlugin == null)
        val refusalCode = "java.context.mavenTestJvmConfigurationIncomplete"
        val pluginId = "org.apache.maven.plugins:$pluginArtifactId"
        val pluginCandidates = when {
            directPlugins.isNotEmpty() -> directPlugins
            inheritedPluginDeclaration != null -> listOf(inheritedPluginDeclaration.plugin)
            profiledPlugin != null -> listOf(profiledPlugin)
            else -> emptyList()
        }

        fun descriptorOrigin(origin: MavenTestJvmPluginVersionOrigin, descriptor: MavenPluginJvmDescriptorDefaults): String {
            val workspace = workspaceRoot.toAbsolutePath().normalize()
            val source = origin.pom.toAbsolutePath().normalize().takeIf { it.startsWith(workspace) }
                ?.let { workspace.relativize(it).toString().replace('\\', '/') } ?: return ""
            return "plugin-default:${origin.layer}:$source:$pluginId@${origin.version}:${descriptor.goal}:jar-sha256=${descriptor.jarSha256}"
        }
        fun settingFromDescriptor(name: String, rawValue: String, origin: String): MavenTestJvmSetting {
            val readable = rawValue.length <= 16_384 && !rawValue.contains("\${")
            val (safe, redacted) = safeBuildOption(name, rawValue)
            val available = readable && !redacted
            return MavenTestJvmSetting(
                name = name,
                value = safe.takeIf { available },
                origin = origin,
                availability = if (available) BuildFactAvailability.COMPLETE else BuildFactAvailability.UNAVAILABLE,
                redacted = redacted,
            )
        }
        fun applyPluginDefaults(
            settings: MutableList<MavenTestJvmSetting>,
            origin: MavenTestJvmPluginVersionOrigin?,
            goal: String,
        ): Pair<MavenPluginJvmDescriptorDefaults?, String?> {
            if (uncertainOrigin || origin == null || settings.groupBy(MavenTestJvmSetting::name).values.any { it.size > 1 }) {
                return null to null
            }
            val descriptor = readPluginJvmDescriptorDefaults(pluginArtifactId, origin.version, goal)
                ?: return null to null
            val defaultOrigin = descriptorOrigin(origin, descriptor).takeIf(String::isNotBlank) ?: return null to null
            val properties = effectiveModel.properties
            if (settings.none { it.name == "forkCount" } && !properties.containsKey("forkCount")) {
                descriptor.forkCount?.let { settings += settingFromDescriptor("forkCount", it, defaultOrigin) }
            }
            if (settings.none { it.name == "reuseForks" } && !properties.containsKey("reuseForks")) {
                descriptor.reuseForks?.let { settings += settingFromDescriptor("reuseForks", it, defaultOrigin) }
            }
            if (settings.none { it.name == "argLine" }) {
                val hasArgLineProperty = properties.containsKey("argLine")
                val hasJacocoContributor = hasActiveJacocoArgLineContributor(effectiveModel)
                val isKnownEmpty = !hasArgLineProperty && !hasJacocoContributor &&
                    descriptor.argLineDefault == null && descriptor.argLineExpression == "\${argLine}"
                settings += if (isKnownEmpty) {
                    MavenTestJvmSetting("argLine", "", defaultOrigin, BuildFactAvailability.COMPLETE)
                } else {
                    MavenTestJvmSetting("argLine", null, defaultOrigin, BuildFactAvailability.UNAVAILABLE)
                }
            }
            return descriptor to defaultOrigin
        }

        if (pluginCandidates.isEmpty()) {
            val defaultSurefireLifecycleExecution = pluginArtifactId == "maven-surefire-plugin" &&
                (model.packaging.isNullOrBlank() || model.packaging.equals("jar", ignoreCase = true))
            if (defaultSurefireLifecycleExecution) {
                val origin = parentVersionOrigin
                val version = origin?.version
                val settings = mutableListOf<MavenTestJvmSetting>()
                val (descriptor, defaultOrigin) = applyPluginDefaults(settings, origin, "test")
                val forkSettings = settings.filter { it.name in setOf("forkCount", "forkMode", "reuseForks") }
                val forkCount = forkSettings.singleOrNull { it.name == "forkCount" }
                val forkMode = forkSettings.singleOrNull { it.name == "forkMode" }
                val mode = when {
                    forkCount != null && forkMode != null -> "ambiguous"
                    forkCount?.availability != null && forkCount.availability != BuildFactAvailability.COMPLETE -> "unknown"
                    forkMode?.availability != null && forkMode.availability != BuildFactAvailability.COMPLETE -> "unknown"
                    forkCount?.value == "0" -> "in-process"
                    forkCount?.value?.matches(Regex("[1-9][0-9]*(?:\\.[0-9]+)?C?")) == true -> "forked"
                    forkMode?.value?.lowercase() == "never" -> "in-process"
                    forkMode?.value?.lowercase() in setOf("once", "always", "perthread") -> "forked"
                    else -> "unknown"
                }
                val argLineLayers = settings.filter { it.name == "argLine" }
                val effectiveArgLineTemplate = argLineLayers.singleOrNull()
                    ?.takeIf { it.availability == BuildFactAvailability.COMPLETE }
                    ?.value
                val complete = !uncertainOrigin && descriptor != null && version != null &&
                    forkSettings.size == 2 && forkSettings.all { it.availability == BuildFactAvailability.COMPLETE } &&
                    mode == "forked" && argLineLayers.singleOrNull()?.availability == BuildFactAvailability.COMPLETE
                val execution = MavenTestJvmExecutionMetadata(
                    pluginId = pluginId,
                    pluginVersion = version,
                    executionId = "default-test",
                    goals = listOf("test"),
                    phase = "test",
                    mode = mode,
                    forkSettings = forkSettings,
                    argLineLayers = argLineLayers,
                    effectiveArgLineTemplate = effectiveArgLineTemplate,
                    availability = if (complete) BuildFactAvailability.COMPLETE else BuildFactAvailability.PARTIAL,
                    refusalCode = if (complete) null else refusalCode,
                    jvmSelection = mavenTestJvmSelection(
                        effectiveModel, emptyList(), mode, forkCount?.origin ?: forkMode?.origin,
                        descriptor, defaultOrigin, uncertainOrigin || version == null,
                    ),
                )
                val modelInputs = buildSet {
                    origin?.pom?.let(::add)
                    descriptor?.jar?.let(::add)
                }
                return MavenTestJvmContextFact(
                    availability = if (complete) BuildFactAvailability.COMPLETE else BuildFactAvailability.PARTIAL,
                    mode = mode,
                    refusalCode = if (complete) null else refusalCode,
                    executions = listOf(execution),
                    modelInputs = modelInputs,
                )
            }
            return if (uncertainOrigin) MavenTestJvmContextFact(
                BuildFactAvailability.PARTIAL,
                mode = "unknown",
                refusalCode = refusalCode,
            ) else MavenTestJvmContextFact(BuildFactAvailability.NOT_DECLARED, mode = "unknown")
        }
        if (pluginCandidates.groupBy { it.artifactId }.values.any { it.size > 1 }) {
            return MavenTestJvmContextFact(
                BuildFactAvailability.AMBIGUOUS,
                mode = "ambiguous",
                refusalCode = "java.context.mavenTestJvmExecutionAmbiguous",
            )
        }

        val executions = mutableListOf<MavenTestJvmExecutionMetadata>()
        val modelInputs = linkedSetOf<Path>()
        for (plugin in pluginCandidates.take(8)) {
            val isSurefire = pluginArtifactId == "maven-surefire-plugin"
            val inheritedPlugin = directPlugins.isEmpty() && inheritedPluginDeclaration != null
            val profileId = integrationProfileId.takeIf { plugin === profiledPlugin }
            val targetExecutions = plugin.executions.orEmpty().filter { execution ->
                if (isSurefire) "test" in execution.goals else "integration-test" in execution.goals || "verify" in execution.goals
            }
            if (targetExecutions.size > 1) {
                return MavenTestJvmContextFact(
                    BuildFactAvailability.AMBIGUOUS,
                    mode = "ambiguous",
                    refusalCode = "java.context.mavenTestJvmExecutionAmbiguous",
                )
            }
            val selectedExecution = targetExecutions.singleOrNull()
            val defaultSurefireLifecycleExecution = isSurefire && selectedExecution == null &&
                (model.packaging.isNullOrBlank() || model.packaging.equals("jar", ignoreCase = true))
            val settings = mutableListOf<MavenTestJvmSetting>()
            val configurationLayers = mutableListOf<Pair<org.codehaus.plexus.util.xml.Xpp3Dom, String>>()
            var malformedConfiguration = false
            fun collect(configuration: Any?, origin: String) {
                if (configuration == null) return
                val xml = configuration as? org.codehaus.plexus.util.xml.Xpp3Dom
                if (xml == null) {
                    malformedConfiguration = true
                    return
                }
                configurationLayers += xml to origin
                for (name in listOf("forkCount", "forkMode", "reuseForks", "argLine")) {
                    val node = xml.getChild(name) ?: continue
                    val raw = node.value?.trim()?.takeIf(String::isNotEmpty)
                    val readable = raw != null && raw.length <= 16_384 && !raw.contains("\${")
                    val (safe, redacted) = raw?.let { safeBuildOption(name, it) } ?: ("" to false)
                    val available = readable && !redacted
                    settings += MavenTestJvmSetting(
                        name = name,
                        value = safe.takeIf { available },
                        origin = origin,
                        availability = if (available) BuildFactAvailability.COMPLETE else BuildFactAvailability.UNAVAILABLE,
                        redacted = redacted,
                    )
                }
            }
            val pluginDisplayId = "${plugin.groupId ?: "org.apache.maven.plugins"}:${plugin.artifactId}"
            val pluginConfigurationOrigin = if (inheritedPlugin) {
                val parentPath = requireNotNull(inheritedPluginDeclaration).pom.toAbsolutePath().normalize()
                val relativeParent = workspaceRoot.toAbsolutePath().normalize().relativize(parentPath)
                    .toString().replace('\\', '/')
                "inherited-parent:$relativeParent:build/plugins/$pluginDisplayId/configuration"
            } else if (profileId != null) {
                "pom.xml:profiles/$profileId/build/plugins/$pluginDisplayId/configuration"
            } else "pom.xml:build/plugins/$pluginDisplayId/configuration"
            collect(plugin.configuration, pluginConfigurationOrigin)
            selectedExecution?.let { execution ->
                val executionLabel = execution.id?.takeIf(String::isNotBlank) ?: "<anonymous>"
                val executionOrigin = pluginConfigurationOrigin.removeSuffix("/configuration") +
                    "/executions/$executionLabel/configuration"
                collect(execution.configuration, executionOrigin)
            }
            val declaredVersion = plugin.version?.trim()?.takeIf(String::isNotEmpty)
            val directVersion = declaredVersion?.takeIf(::literalPluginVersion)
            val versionOrigin = when {
                inheritedPlugin -> requireNotNull(inheritedPluginDeclaration).versionOrigin
                profileId != null && directVersion != null -> MavenTestJvmPluginVersionOrigin(
                    directVersion, pom, "module-profile:$profileId",
                )
                directVersion != null -> MavenTestJvmPluginVersionOrigin(directVersion, pom, "module-plugin")
                declaredVersion != null -> null
                else -> parentVersionOrigin
            }
            val version = versionOrigin?.version
            val goal = if (isSurefire) "test" else "integration-test"
            val (descriptor, defaultOrigin) = applyPluginDefaults(settings, versionOrigin, goal)
            descriptor?.jar?.let(modelInputs::add)
            versionOrigin?.pom?.let(modelInputs::add)
            val duplicateSettings = settings.groupBy(MavenTestJvmSetting::name).values.any { it.size > 1 }
            val forkSettings = settings.filter { it.name in setOf("forkCount", "forkMode", "reuseForks") }
            val forkCount = forkSettings.singleOrNull { it.name == "forkCount" }
            val forkMode = forkSettings.singleOrNull { it.name == "forkMode" }
            val settingUnavailable = settings.any { it.availability != BuildFactAvailability.COMPLETE }
            val mode = when {
                forkCount != null && forkMode != null -> "ambiguous"
                forkCount?.availability != null && forkCount.availability != BuildFactAvailability.COMPLETE -> "unknown"
                forkMode?.availability != null && forkMode.availability != BuildFactAvailability.COMPLETE -> "unknown"
                forkCount?.value == "0" -> "in-process"
                forkCount?.value?.matches(Regex("[1-9][0-9]*(?:\\.[0-9]+)?C?")) == true -> "forked"
                forkMode?.value?.lowercase() == "never" -> "in-process"
                forkMode?.value?.lowercase() in setOf("once", "always", "perthread") -> "forked"
                else -> "unknown"
            }
            val argLineLayers = settings.filter { it.name == "argLine" }
            val effectiveArgLineTemplate = argLineLayers.singleOrNull()
                ?.takeIf { it.availability == BuildFactAvailability.COMPLETE && !duplicateSettings }
                ?.value
            val executionAvailability = when {
                mode == "ambiguous" || duplicateSettings -> BuildFactAvailability.AMBIGUOUS
                uncertainOrigin || malformedConfiguration || settingUnavailable || mode == "unknown" || version == null ->
                    BuildFactAvailability.PARTIAL
                else -> BuildFactAvailability.COMPLETE
            }
            val executionRefusal = when (executionAvailability) {
                BuildFactAvailability.AMBIGUOUS -> "java.context.mavenTestJvmExecutionAmbiguous"
                BuildFactAvailability.COMPLETE -> null
                else -> refusalCode
            }
            executions += MavenTestJvmExecutionMetadata(
                pluginId = pluginDisplayId,
                pluginVersion = version,
                executionId = selectedExecution?.id?.takeIf(String::isNotBlank)
                    ?: "default-test".takeIf { defaultSurefireLifecycleExecution },
                goals = selectedExecution?.goals.orEmpty().ifEmpty {
                    listOf("test").takeIf { defaultSurefireLifecycleExecution }.orEmpty()
                }.take(32),
                phase = selectedExecution?.phase?.takeIf(String::isNotBlank)
                    ?: "test".takeIf { defaultSurefireLifecycleExecution },
                mode = mode,
                forkSettings = forkSettings,
                argLineLayers = argLineLayers,
                effectiveArgLineTemplate = effectiveArgLineTemplate,
                availability = executionAvailability,
                refusalCode = executionRefusal,
                jvmSelection = mavenTestJvmSelection(
                    effectiveModel, configurationLayers, mode, forkCount?.origin ?: forkMode?.origin,
                    descriptor, defaultOrigin, uncertainOrigin || malformedConfiguration || duplicateSettings || version == null ||
                        (!isSurefire && selectedExecution?.goals?.contains("integration-test") != true),
                ),
            )
        }
        val availability = when {
            executions.isEmpty() -> BuildFactAvailability.PARTIAL
            executions.any { it.availability == BuildFactAvailability.AMBIGUOUS } -> BuildFactAvailability.AMBIGUOUS
            uncertainOrigin || executions.any { it.availability != BuildFactAvailability.COMPLETE } -> BuildFactAvailability.PARTIAL
            else -> BuildFactAvailability.COMPLETE
        }
        val modes = executions.map(MavenTestJvmExecutionMetadata::mode).distinct()
        val mode = when {
            availability == BuildFactAvailability.AMBIGUOUS -> "ambiguous"
            modes.size == 1 -> modes.single()
            modes.isEmpty() -> "unknown"
            else -> "mixed"
        }
        val refusal = when (availability) {
            BuildFactAvailability.AMBIGUOUS -> "java.context.mavenTestJvmExecutionAmbiguous"
            BuildFactAvailability.COMPLETE -> null
            else -> refusalCode
        }
        return MavenTestJvmContextFact(availability, mode, refusal, executions.take(16), modelInputs)
    }

    private fun admitsDefaultCompiler(model: Model, pom: Path): Boolean {
        if (model.packaging != "jar") return false
        val build = model.build
        if (build?.extensions.orEmpty().isNotEmpty() ||
            build?.plugins.orEmpty().any { it.isExtensions || it.artifactId in setOf("maven-compiler-plugin", "maven-toolchains-plugin") } ||
            build?.pluginManagement?.plugins.orEmpty().any { it.artifactId == "maven-compiler-plugin" }) return false
        val properties = model.properties
        if (properties.getProperty("maven.compiler.release") != null ||
            properties.getProperty("maven.compiler.source")?.trim()?.let(::parsedJavaLevel) == null ||
            properties.getProperty("maven.compiler.target")?.trim()?.let(::parsedJavaLevel) == null ||
            listOf("encoding", "maven.compiler.encoding", "maven.main.skip", "maven.test.skip").any(properties::containsKey)) return false
        val launchFiles = listOf("extensions.xml", "maven.config", "jvm.config")
        return generateSequence(pom.parent, Path::getParent).all { ancestor ->
            val config = ancestor.resolve(".mvn")
            Files.notExists(config, LinkOption.NOFOLLOW_LINKS) ||
                Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS) &&
                launchFiles.all { Files.notExists(config.resolve(it), LinkOption.NOFOLLOW_LINKS) }
        }
    }

    private fun compilerOptions(
        model: Model,
        test: Boolean,
        lifecycleCompiler: MavenLifecycleCompilerBinding?,
    ): Pair<List<BuildCompilerOption>, BuildFactAvailability> {
        val properties = model.properties
        val declaredProperties = linkedMapOf<String, String>()
        val recognizedProperties = setOf("release", "source", "target", "encoding", "parameters", "compilerArgs")
        listOf("release", "source", "target", "encoding", "parameters", "compilerArgs").forEach { name ->
            properties.getProperty("maven.compiler.$name")?.takeIf(String::isNotBlank)?.let {
                declaredProperties[name] = it.trim()
            }
        }
        val unrecognizedCompilerProperties = properties.stringPropertyNames().filter {
            it.startsWith("maven.compiler.") && it.removePrefix("maven.compiler.") !in recognizedProperties
        }.sorted()
        unrecognizedCompilerProperties.forEach { property ->
            properties.getProperty(property)?.let { declaredProperties["property.${property.removePrefix("maven.compiler.")}"] = it.trim() }
        }
        properties.getProperty("project.build.sourceEncoding")?.takeIf(String::isNotBlank)?.let {
            declaredProperties.putIfAbsent("encoding", it.trim())
        }
        properties.getProperty("java.version")?.takeIf(String::isNotBlank)?.let {
            if ("release" !in declaredProperties && "source" !in declaredProperties) declaredProperties["source"] = it.trim()
        }
        val plugins = model.build?.plugins.orEmpty().filter {
            it.artifactId == "maven-compiler-plugin" && it.groupId in setOf(null, "org.apache.maven.plugins")
        }
        if (plugins.size > 1 || plugins.isEmpty() && lifecycleCompiler == null) {
            val options = declaredProperties.map { (name, value) ->
                val (safe, redacted) = safeBuildOption(name, value)
                BuildCompilerOption(name, safe, "maven-project-property", redacted)
            }
            return options to when {
                plugins.size > 1 -> BuildFactAvailability.AMBIGUOUS
                else -> BuildFactAvailability.PARTIAL
            }
        }
        val plugin = plugins.singleOrNull()
        val version = plugin?.version ?: lifecycleCompiler?.compilerVersion
        val selectedGoal = if (test) "testCompile" else "compile"
        val executions = plugin?.executions.orEmpty().filter { selectedGoal in it.goals }
        if (executions.size > 1) {
            return emptyList<BuildCompilerOption>() to BuildFactAvailability.AMBIGUOUS
        }
        val effective = linkedMapOf<String, BuildCompilerOption>()
        declaredProperties.forEach { (name, value) ->
            val (safe, redacted) = safeBuildOption(name, value)
            effective[name] = BuildCompilerOption(name, safe, "maven-project-property", redacted)
        }
        var availability = if (version.isNullOrBlank() || unrecognizedCompilerProperties.isNotEmpty()) {
            BuildFactAvailability.PARTIAL
        } else BuildFactAvailability.COMPLETE
        val compilerPluginVersion = version?.let {
            runCatching { DefaultArtifactVersion(it) }.getOrNull()
        }
        val compilerPluginSupportsModulePath = compilerPluginVersion?.let {
            it >= DefaultArtifactVersion("3.11.0")
        } == true
        if (!version.isNullOrBlank() && compilerPluginVersion == null) {
            availability = BuildFactAvailability.PARTIAL
        }
        version?.takeIf(String::isNotBlank)?.let {
            effective["pluginVersion"] = BuildCompilerOption("pluginVersion", it, lifecycleCompiler?.origin ?: "maven-compiler-plugin")
        }
        fun collect(configuration: Any?, origin: String) {
            val xml = configuration as? org.codehaus.plexus.util.xml.Xpp3Dom ?: return
            val knownScalarOptions = setOf(
                "release", "source", "target", "encoding", "parameters", "debug", "debuglevel",
                "showWarnings", "showDeprecation", "proc", "compilerId", "fork", "executable",
                "meminitial", "maxmem", "useIncrementalCompilation", "useModulePath", "annotationProcessorPathsUseDepMgmt",
            )
            xml.children.orEmpty().filter { it.name in knownScalarOptions }.forEach { child ->
                val value = child.value?.trim() ?: child.children.orEmpty().joinToString(",") { it.value.orEmpty().trim() }
                val (safe, redacted) = safeBuildOption(child.name, value)
                if (child.name == "useModulePath" && safe.toBooleanStrictOrNull() == null) {
                    availability = BuildFactAvailability.PARTIAL
                } else {
                    effective[child.name] = BuildCompilerOption(child.name, safe, origin, redacted)
                }
            }
            xml.getChild("compilerArgs")?.children.orEmpty().filter { it.name == "arg" }.forEach { child ->
                val value = child.value?.trim().orEmpty()
                val (safe, redacted) = safeBuildOption("compilerArg", value)
                effective["compilerArg:${effective.size}"] = BuildCompilerOption("compilerArg", safe, origin, redacted)
            }
            xml.getChild("compilerArgument")?.value?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                val (safe, redacted) = safeBuildOption("compilerArgument", value)
                effective["compilerArgument"] = BuildCompilerOption("compilerArgument", safe, origin, redacted)
            }
            val unknown = xml.children.orEmpty().map { it.name }.filterNot { it in knownScalarOptions + setOf(
                "compilerArgs", "compilerArgument", "annotationProcessorPaths", "annotationProcessors",
            ) }
            if (unknown.isNotEmpty()) availability = BuildFactAvailability.PARTIAL
        }
        collect(plugin?.configuration, "maven-compiler-plugin:plugin")
        executions.singleOrNull()?.let { execution -> collect(execution.configuration, "maven-compiler-plugin:${execution.id}") }
        if (compilerPluginSupportsModulePath) {
            effective.putIfAbsent(
                "useModulePath",
                BuildCompilerOption("useModulePath", "true", "maven-compiler-plugin:default"),
            )
        } else if (effective.remove("useModulePath") != null) {
            // The parameter was introduced in 3.11. An older plugin's similarly
            // named XML element is not evidence that the compiler used a module path.
            availability = BuildFactAvailability.PARTIAL
        }
        if (effective.keys.none { it in setOf("release", "source", "target") }) availability = BuildFactAvailability.PARTIAL
        return effective.values.toList() to availability
    }

    private fun safeBuildOption(name: String, rawValue: String): Pair<String, Boolean> {
        val sensitiveName = Regex("(?i)(password|secret|token|api[-_]?key|credential|authorization)")
            .containsMatchIn(name)
        val sensitiveArgument = Regex("(?i)(password|secret|token|api[-_]?key|credential|authorization)\\s*[=:]")
            .containsMatchIn(rawValue)
        return if (sensitiveName || sensitiveArgument) "<redacted>" to true else rawValue.take(16_384) to false
    }

    private fun annotationProcessorConfiguration(
        model: Model,
        test: Boolean,
        resolver: LocalOnlyModelResolver,
        moduleRoot: Path,
        reactorProcessorModules: Map<MavenCoordinate, List<MavenReactorProcessorModule>>,
    ): BuildAnnotationProcessorConfiguration {
        val plugins = model.build?.plugins.orEmpty().filter {
            it.artifactId == "maven-compiler-plugin" && it.groupId in setOf(null, "org.apache.maven.plugins")
        }
        if (plugins.size != 1) return BuildAnnotationProcessorConfiguration(
            if (plugins.size > 1) BuildFactAvailability.AMBIGUOUS else BuildFactAvailability.UNAVAILABLE,
            refusalCode = if (plugins.size > 1) "java.context.annotationProcessorConfigurationAmbiguous"
            else "java.context.annotationProcessorContextUnavailable",
        )
        val plugin = plugins.single()
        val goal = if (test) "testCompile" else "compile"
        val executions = plugin.executions.filter { goal in it.goals }
        if (executions.size > 1) return BuildAnnotationProcessorConfiguration(
            BuildFactAvailability.AMBIGUOUS,
            refusalCode = "java.context.annotationProcessorConfigurationAmbiguous",
        )
        val configs = listOfNotNull(plugin.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom) +
            executions.mapNotNull { it.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom }
        val pathEntries = mutableListOf<Path>()
        val resolved = mutableListOf<BuildResolvedArtifact>()
        val processorNames = mutableListOf<String>()
        val processorOptions = linkedMapOf<String, String>()
        var mode: String? = null
        var availability = BuildFactAvailability.COMPLETE
        var explicitPath = false
        var ambiguous = false
        val configuredPathDeclarations = mutableSetOf<List<String>>()
        fun setMode(value: String) {
            if (mode != null && !mode.equals(value, ignoreCase = true)) ambiguous = true
            mode = value
        }
        val managed = model.dependencyManagement?.dependencies.orEmpty().mapNotNull { dependency ->
            dependency.coordinate()?.let { coordinate -> dependency.managementKey()?.let { it to coordinate.version } }
        }.toMap()
        configs.forEach { config ->
            config.getChild("proc")?.value?.trim()?.takeIf(String::isNotBlank)?.let { configuredMode ->
                setMode(configuredMode)
            }
            val processorsNode = config.getChild("annotationProcessors")
            processorsNode?.children.orEmpty().filter { it.name in setOf("annotationProcessor", "processor") }
                .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }.forEach(processorNames::add)
            val paths = config.getChild("annotationProcessorPaths")?.children.orEmpty().filter { it.name == "path" }
            if (paths.isNotEmpty()) {
                explicitPath = true
                val declaration = paths.map { entry -> listOf("groupId", "artifactId", "version", "type", "classifier")
                    .joinToString(":") { key -> entry.getChild(key)?.value?.trim().orEmpty() } }.sorted()
                if (configuredPathDeclarations.isNotEmpty() && declaration !in configuredPathDeclarations) ambiguous = true
                configuredPathDeclarations += declaration
            }
            paths.forEach { entry ->
                fun value(name: String): String? = entry.getChild(name)?.value?.trim()?.takeIf(String::isNotBlank)
                val group = value("groupId")
                val artifact = value("artifactId")
                val classifier = value("classifier")
                val type = value("type") ?: "jar"
                val rawVersion = value("version")
                if (group == null || artifact == null) {
                    availability = BuildFactAvailability.PARTIAL
                    return@forEach
                }
                val managedVersion = managed["$group:$artifact:$type:${classifier.orEmpty()}"]
                val version = rawVersion ?: managedVersion
                if (version == null) {
                    availability = BuildFactAvailability.PARTIAL
                    return@forEach
                }
                val coordinate = resolver.resolveVersion(MavenCoordinate(group, artifact, version))
                    ?: MavenCoordinate(group, artifact, version)
                val reactorMatches = reactorProcessorModules[coordinate]
                if (reactorMatches != null) {
                    val reactorModule = reactorMatches.singleOrNull()
                    if (reactorModule == null) {
                        availability = BuildFactAvailability.AMBIGUOUS
                        return@forEach
                    }
                    val output = reactorModule.outputDirectory
                        ?.takeIf { reactorModule.packaging == "jar" && type == "jar" && classifier == null }
                        ?.takeIf { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it) }
                    if (output == null) {
                        availability = BuildFactAvailability.UNAVAILABLE
                        return@forEach
                    }
                    pathEntries.add(output)
                    resolved += BuildResolvedArtifact(
                        output,
                        BuildArtifactCoordinates(coordinate.groupId, coordinate.artifactId, coordinate.version),
                        extension = "classes",
                        scope = DependencyScope.CUSTOM,
                    )
                    if (reactorModule.hasRuntimeDependencies) availability = BuildFactAvailability.PARTIAL
                    return@forEach
                }
                val resolvedPath = resolver.artifactPath(coordinate, type, classifier)
                if (resolvedPath == null) {
                    availability = BuildFactAvailability.UNAVAILABLE
                    return@forEach
                }
                pathEntries.add(resolvedPath)
                resolved += BuildResolvedArtifact(
                    resolvedPath,
                    BuildArtifactCoordinates(coordinate.groupId, coordinate.artifactId, coordinate.version),
                    extension = if (type == "test-jar") "jar" else type,
                    classifier = classifier ?: if (type == "test-jar") "tests" else null,
                    scope = DependencyScope.CUSTOM,
                )
                // Processor artifact transitives are intentionally not inferred from the jar filename.
                availability = BuildFactAvailability.PARTIAL
            }
            val compilerArgs = config.getChild("compilerArgs")?.children.orEmpty()
                .filter { it.name == "arg" }.map { it.value?.trim().orEmpty() }
            var argumentIndex = 0
            while (argumentIndex < compilerArgs.size) {
                val value = compilerArgs[argumentIndex]
                when {
                    value.startsWith("-A") -> {
                        val option = value.removePrefix("-A")
                        val key = option.substringBefore('=')
                        val raw = option.substringAfter('=', "")
                        val (safe, _) = safeBuildOption(key, raw)
                        val previous = processorOptions.put(key, safe)
                        if (previous != null && previous != safe) ambiguous = true
                    }
                    value == "-proc:none" -> setMode("none")
                    value == "-proc:only" -> setMode("only")
                    value == "-proc:full" -> setMode("full")
                    value in setOf("-processor", "--processor") -> {
                        val next = compilerArgs.getOrNull(argumentIndex + 1)?.takeIf(String::isNotBlank)
                        if (next == null || next.startsWith('-')) availability = BuildFactAvailability.PARTIAL
                        else { processorNames += next.split(',').filter(String::isNotBlank); argumentIndex++ }
                    }
                    value in setOf("-processorpath", "--processor-path") -> {
                        val next = compilerArgs.getOrNull(argumentIndex + 1)?.takeIf(String::isNotBlank)
                        if (next == null || next.startsWith('-')) availability = BuildFactAvailability.PARTIAL
                        else {
                            explicitPath = true
                            next.split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).forEach { rawPath ->
                                val path = runCatching { Path.of(rawPath) }.getOrNull()
                                if (path == null) availability = BuildFactAvailability.PARTIAL
                                else {
                                    val absolute = if (path.isAbsolute) path.normalize() else moduleRoot.resolve(path).normalize()
                                    if (Files.isRegularFile(absolute) || Files.isDirectory(absolute)) pathEntries.add(absolute)
                                    else availability = BuildFactAvailability.UNAVAILABLE
                                }
                            }
                            argumentIndex++
                        }
                    }
                    value.startsWith("-processor:") || value.startsWith("--processor=") ->
                        processorNames += value.substringAfter(if (value.startsWith("-processor:")) ':' else '=')
                    value.startsWith("-processorpath:") || value.startsWith("--processor-path=") -> {
                        val rawPaths = value.substringAfter(if (value.startsWith("-processorpath:")) ':' else '=')
                        explicitPath = true
                        rawPaths.split(java.io.File.pathSeparatorChar).filter(String::isNotBlank).forEach { rawPath ->
                            val path = runCatching { Path.of(rawPath) }.getOrNull()
                            if (path == null) availability = BuildFactAvailability.PARTIAL
                            else {
                                val absolute = if (path.isAbsolute) path.normalize() else moduleRoot.resolve(path).normalize()
                                if (Files.isRegularFile(absolute) || Files.isDirectory(absolute)) pathEntries.add(absolute)
                                else availability = BuildFactAvailability.UNAVAILABLE
                            }
                        }
                    }
                }
                argumentIndex++
            }
        }
        if (ambiguous) availability = BuildFactAvailability.AMBIGUOUS
        if (mode?.equals("none", ignoreCase = true) == true && !explicitPath && processorNames.isEmpty()) {
            availability = BuildFactAvailability.COMPLETE
        } else if (mode == null && !explicitPath && processorNames.isEmpty()) {
            // Maven's implicit processor discovery uses the compile classpath. The
            // effective processor owner set is not knowable from declarations alone.
            availability = BuildFactAvailability.PARTIAL
        } else if (processorNames.isNotEmpty() && !explicitPath && mode?.equals("none", ignoreCase = true) != true) {
            availability = BuildFactAvailability.PARTIAL
        }
        val refusalCode = when (availability) {
            BuildFactAvailability.COMPLETE -> null
            BuildFactAvailability.AMBIGUOUS -> "java.context.annotationProcessorConfigurationAmbiguous"
            BuildFactAvailability.UNAVAILABLE -> "java.context.annotationProcessorPathUnavailable"
            else -> "java.context.annotationProcessorContextIncomplete"
        }
        return BuildAnnotationProcessorConfiguration(
            availability = availability,
            processorPath = resolved.distinctBy { it.path },
            processorPathEntries = pathEntries.distinct(),
            processorNames = processorNames.distinct(),
            options = processorOptions,
            mode = mode,
            refusalCode = refusalCode,
        )
    }

    private fun resolveSystemPaths(
        dependencies: List<Dependency>,
        pom: Path,
        missing: MutableSet<String>,
        missingEvidence: MutableSet<MavenMissingArtifactEvidence>,
    ): List<Path> = dependencies.mapNotNull { dependency ->
        val label = listOfNotNull(dependency.groupId, dependency.artifactId, dependency.version).joinToString(":")
            .ifBlank { "system dependency" }
        val raw = dependency.systemPath?.takeIf(String::isNotBlank)
        if (raw == null) {
            missing += "$label has no systemPath"
            return@mapNotNull null
        }
        val path = runCatching { Path.of(raw).normalize() }.getOrNull()
        if (path == null || !path.isAbsolute || !path.exists() || !path.isRegularFile()) {
            missing += "$label systemPath is unavailable: ${conciseSystemPath(raw, pom)}"
            if (path != null && path.isAbsolute) {
                val expectedPath = path.toAbsolutePath().normalize()
                val manifest = expectedPath.resolveSibling("${expectedPath.fileName}.refactorkit-evidence")
                    .takeIf { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                val identity = dependency.coordinate()?.key
                if (identity != null) {
                    val expected = manifest?.let(::readMissingArtifactIdentity)
                    missingEvidence += MavenMissingArtifactEvidence(
                        identity = identity,
                        expectedPath = expectedPath,
                        expectedIdentityManifest = manifest,
                        expectedSha256 = expected?.first,
                        providedTypes = expected?.second.orEmpty(),
                        leaf = true,
                    )
                }
            }
            return@mapNotNull null
        }
        path.toAbsolutePath().normalize()
    }.distinct().sortedBy(Path::toString)

    private fun readMissingArtifactIdentity(manifest: Path): Pair<String?, Set<String>>? = runCatching {
        if (Files.size(manifest) > MAX_MISSING_IDENTITY_BYTES) return@runCatching null
        var sha256: String? = null
        val providedTypes = linkedSetOf<String>()
        Files.readAllLines(manifest, Charsets.UTF_8).forEach { line ->
            when {
                line.startsWith("artifactSha256=") -> {
                    val value = line.substringAfter('=').trim().lowercase()
                    if (SHA256.matches(value) && sha256 == null) sha256 = value else return@runCatching null
                }
                line.startsWith("providedType=") -> {
                    val value = line.substringAfter('=').trim()
                    if (!JAVA_FQN.matches(value) || providedTypes.size >= MAX_PROVIDED_TYPES) return@runCatching null
                    providedTypes += value
                }
                line.isNotBlank() -> return@runCatching null
            }
        }
        sha256 to providedTypes.toSet()
    }.getOrNull()

    private fun conciseSystemPath(raw: String, pom: Path): String {
        val normalized = runCatching { Path.of(raw).toAbsolutePath().normalize() }.getOrNull()
        val pomParent = pom.parent?.toAbsolutePath()?.normalize()
        return if (normalized != null && pomParent != null && normalized.startsWith(pomParent)) {
            pomParent.relativize(normalized).toString()
        } else {
            normalized?.fileName?.toString() ?: "invalid"
        }
    }

    private fun isReactorSourceDependency(dependency: Dependency): Boolean =
        dependency.type.ifBlank { "jar" } == "jar" && dependency.classifier.isNullOrBlank()

    private fun reactorDependencyScopes(
        dependencies: List<Dependency>,
        reactorCoordinates: Set<MavenCoordinate>,
    ): Map<MavenCoordinate, String> = buildMap {
        dependencies.filter(::isReactorSourceDependency).forEach { dependency ->
            dependency.coordinate()?.takeIf(reactorCoordinates::contains)?.let { coordinate ->
                putIfAbsent(coordinate, dependency.scope.normalizedScope())
            }
        }
    }

    private fun resolveGraph(
        roots: List<Dependency>,
        includedEffectiveScopes: Set<String>,
        resolver: LocalOnlyModelResolver,
        reactorCoordinates: Set<MavenCoordinate>,
        missing: MutableSet<String>,
        modelInputs: MutableSet<Path>,
        importedBoms: MutableSet<Path>,
        managedDependencies: Map<String, MavenManagedDependency>,
        dependencyGraphFailures: MutableSet<String>,
        workspaceRoot: Path,
        consumerImportedBoms: Set<Path>,
        consumerPom: Path,
        consumerCoordinate: MavenCoordinate,
        sourceSet: String,
        projection: String,
        selectorRecords: MutableSet<MavenDependencySelectorRecord>,
        selectedMissingEvidence: MutableMap<String, MavenMissingArtifactEvidence>,
        resolvedArtifacts: MutableList<BuildResolvedArtifact>,
    ): List<Path> {
        data class Pending(
            val dependency: Dependency,
            val inheritedExclusions: Set<String>,
            val direct: Boolean,
            val depth: Int,
            val effectiveScope: String,
            val dependencyPath: List<String>,
        )
        data class ResolvedDependencyModel(
            val pom: Path,
            val effective: EffectiveBuild,
            val descriptorFacts: Set<MavenSelectedDescriptorFact>,
            val parsedDescriptorIdentityHash: String,
        )

        val artifacts = linkedSetOf<Path>()
        val visitedArtifacts = mutableSetOf<String>()
        val selectedArtifacts = mutableSetOf<String>()
        val unresolvedTransitive = linkedMapOf<String, String>()
        val pending = ArrayDeque<Pending>()

        fun normalizedVariant(dependency: Dependency): String {
            val type = dependency.type.ifBlank { "jar" }
            val extension = if (type == "test-jar") "jar" else type
            val classifier = dependency.classifier?.takeIf(String::isNotBlank)
                ?: if (type == "test-jar") "tests" else ""
            return "$extension:$classifier"
        }

        fun pathSegment(dependency: Dependency): String = listOf(
            dependency.groupId?.trim().orEmpty().ifBlank { "<unresolved-group>" },
            dependency.artifactId?.trim().orEmpty().ifBlank { "<unresolved-artifact>" },
            dependency.version?.trim().orEmpty().ifBlank { "<unresolved-version>" },
            normalizedVariant(dependency),
        ).joinToString(":")

        fun recordSelector(
            dependency: Dependency,
            declaringPom: Path,
            dependencyPath: List<String>,
            effectiveScope: String?,
            outcome: String,
            reason: String,
            lookupBoundary: String,
        ) {
            val record = MavenDependencySelectorRecord(
                declaringPom = declaringPom.toAbsolutePath().normalize(),
                consumer = consumerCoordinate.key,
                sourceSet = sourceSet,
                projection = projection,
                dependencyPath = dependencyPath.joinToString(" -> "),
                groupId = dependency.groupId?.trim().orEmpty(),
                artifactId = dependency.artifactId?.trim().orEmpty(),
                declaredVersion = dependency.version?.trim().orEmpty(),
                declaredScope = dependency.explicitNormalizedScope() ?: dependency.scope.normalizedScope(),
                effectiveScope = effectiveScope.orEmpty(),
                optional = dependency.isOptional,
                type = dependency.type.ifBlank { "jar" },
                classifier = dependency.classifier?.takeIf(String::isNotBlank).orEmpty(),
                normalizedVariant = normalizedVariant(dependency),
                outcome = outcome,
                reason = reason,
                lookupBoundary = lookupBoundary,
            )
            if (record !in selectorRecords && selectorRecords.size >= MAX_SELECTOR_RECORDS) {
                dependencyGraphFailures += "Dependency selector evidence exceeds the bounded record limit"
            } else {
                selectorRecords += record
            }
        }

        fun selectedDescriptorFailure(
            coordinate: MavenCoordinate,
            node: Pending,
            layer: String,
            condition: String,
            detail: String,
        ): String = "MAVEN_SELECTED_DESCRIPTOR_$condition coordinate=" +
            "${selectedDescriptorAuthorityContext?.coordinate ?: coordinate.key} " +
            "consumer=${consumerCoordinate.key} origin=${consumerCoordinate.artifactId}:main " +
            "sourceSet=${consumerCoordinate.artifactId}:$sourceSet projection=$projection " +
            "path=${node.dependencyPath.joinToString(" -> ")} descriptorLayer=$layer " +
            "descriptorCondition=$condition $detail"

        fun selectedDescriptorPreflight(coordinate: MavenCoordinate, node: Pending): String? {
            val authority = selectedDescriptorAuthorityContext
                ?.takeIf { it.groupId == coordinate.groupId && it.artifactId == coordinate.artifactId }
                ?: return null
            val workspace = workspaceRoot.toAbsolutePath().normalize()
            val expectations = authority.descriptorExpectations.sortedWith(
                compareBy<MavenSelectedDescriptorExpectation> { it.layer.ordinal }
                    .thenBy { it.path.toString() },
            )
            val selectedPom = expectations.single { it.layer == MavenSelectedDescriptorLayer.SELECTED_LEAF_POM }
            val selectedPomPath = workspace.resolve(selectedPom.path).normalize()
            if (!Files.isRegularFile(selectedPomPath, LinkOption.NOFOLLOW_LINKS)) {
                return selectedDescriptorFailure(
                    coordinate,
                    node,
                    MavenSelectedDescriptorLayer.SELECTED_LEAF_POM.displayName,
                    "MISSING",
                    "descriptorPath=${selectedPom.path}",
                )
            }
            if (readRawModel(selectedPomPath) == null) {
                return selectedDescriptorFailure(
                    coordinate,
                    node,
                    "selected leaf POM model",
                    "MALFORMED",
                    "descriptorPath=${selectedPom.path}",
                )
            }
            expectations.forEach { expectation ->
                val descriptor = workspace.resolve(expectation.path).normalize()
                if (!Files.isRegularFile(descriptor, LinkOption.NOFOLLOW_LINKS)) {
                    return selectedDescriptorFailure(
                        coordinate,
                        node,
                        expectation.layer.displayName,
                        "MISSING",
                        "descriptorPath=${expectation.path}",
                    )
                }
                val actualHash = sha256(descriptor)
                if (actualHash != expectation.contentSha256) {
                    return selectedDescriptorFailure(
                        coordinate,
                        node,
                        expectation.layer.displayName,
                        "DRIFTED",
                        "descriptorPath=${expectation.path} expectedHash=${expectation.contentSha256} actualHash=$actualHash",
                    )
                }
            }
            if (coordinate.version != authority.version) {
                return selectedDescriptorFailure(
                    coordinate,
                    node,
                    MavenSelectedDescriptorLayer.DEPENDENCY_MANAGEMENT_MEDIATION_DECLARATION.displayName,
                    "DRIFTED",
                    "expectedVersion=${authority.version} actualVersion=${coordinate.version}",
                )
            }
            if (authority.parsedDescriptorIdentityHash == null) {
                return selectedDescriptorFailure(
                    coordinate,
                    node,
                    "relocation/model parse evidence",
                    "MISSING",
                    "descriptorPath=${selectedPom.path}",
                )
            }
            return null
        }

        fun descriptorFacts(
            dependencyPom: Path,
            effectiveModel: EffectiveBuild,
        ): Set<MavenSelectedDescriptorFact> {
            val facts = linkedSetOf<MavenSelectedDescriptorFact>()
            fun add(path: Path?, layer: MavenSelectedDescriptorLayer) {
                val normalized = path?.toAbsolutePath()?.normalize() ?: return
                if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    facts += MavenSelectedDescriptorFact(normalized, layer, sha256(normalized))
                }
            }
            add(dependencyPom, MavenSelectedDescriptorLayer.SELECTED_LEAF_POM)
            val rawModel = readRawModel(dependencyPom)
            rawModel?.parent?.let { parent ->
                val relative = parent.relativePath
                val relativePath = if (relative != null && relative.isBlank()) null else {
                    dependencyPom.parent?.resolve(relative ?: "../pom.xml")?.toAbsolutePath()?.normalize()
                }
                val repositoryPath = resolver.expectedPomPath(
                    MavenCoordinate(parent.groupId, parent.artifactId, parent.version),
                )
                val parentPath = listOfNotNull(relativePath, repositoryPath).firstOrNull { candidate ->
                    candidate in effectiveModel.inputs
                }
                add(parentPath, MavenSelectedDescriptorLayer.REQUIRED_PARENT_POM)
            }
            rawModel?.dependencyManagement?.dependencies.orEmpty()
                .filter { it.type == "pom" && it.scope == "import" }
                .mapNotNull { it.coordinate(rawModel?.properties ?: Properties()) }
                .mapNotNull(resolver::expectedPomPath)
                .filter { it in effectiveModel.inputs }
                .forEach { add(it, MavenSelectedDescriptorLayer.REQUIRED_IMPORTED_BOM) }
            consumerImportedBoms.forEach { input ->
                add(input, MavenSelectedDescriptorLayer.DEPENDENCY_MANAGEMENT_MEDIATION_DECLARATION)
            }
            return facts
        }

        fun effectiveDependencyModel(coordinate: MavenCoordinate, node: Pending): ResolvedDependencyModel? {
            selectedDescriptorPreflight(coordinate, node)?.let { failure ->
                dependencyGraphFailures += failure
                return null
            }
            val context = "coordinate=${coordinate.key} consumer=${consumerCoordinate.key} sourceSet=$sourceSet " +
                "projection=$projection path=${node.dependencyPath.joinToString(" -> ")}"
            val dependencyPom = resolver.pomPath(coordinate)
            if (dependencyPom == null) {
                dependencyGraphFailures += "MAVEN_DEPENDENCY_DESCRIPTOR_MISSING $context missing descriptor"
                return null
            }
            modelInputs.add(dependencyPom)
            val effectiveModel = buildEffective(dependencyPom, resolver)
            modelInputs.addAll(effectiveModel.inputs)
            importedBoms.addAll(effectiveModel.importedBoms)
            val parsedModel = effectiveModel.model
            if (parsedModel == null) {
                dependencyGraphFailures += "MAVEN_EFFECTIVE_DEPENDENCY_MODEL_UNAVAILABLE $context: " +
                    concise(effectiveModel.failure ?: "unknown model failure")
                return null
            }
            val parsedIdentityHash = parsedDescriptorIdentityHash(parsedModel) ?: run {
                dependencyGraphFailures += "MAVEN_EFFECTIVE_DEPENDENCY_MODEL_UNAVAILABLE $context: " +
                    "parsed descriptor identity is absent"
                return null
            }
            selectedDescriptorAuthorityContext
                ?.takeIf { it.groupId == coordinate.groupId && it.artifactId == coordinate.artifactId }
                ?.parsedDescriptorIdentityHash
                ?.takeIf { it != parsedIdentityHash }
                ?.let { expected ->
                    dependencyGraphFailures += selectedDescriptorFailure(
                        coordinate,
                        node,
                        "relocation/model parse evidence",
                        "DRIFTED",
                        "expectedIdentityHash=$expected actualIdentityHash=$parsedIdentityHash",
                    )
                    return null
                }
            return ResolvedDependencyModel(
                dependencyPom,
                effectiveModel,
                descriptorFacts(dependencyPom, effectiveModel),
                parsedIdentityHash,
            )
        }

        roots.forEach { dependency ->
            val dependencyPath = listOf(pathSegment(dependency))
            recordSelector(
                dependency,
                consumerPom,
                dependencyPath,
                dependency.scope.normalizedScope(),
                "SELECTED",
                "DIRECT",
                "VERSION_RANGE_REPOSITORY_POM_JAR_NETWORK_REQUIRED",
            )
            pending += Pending(
                dependency,
                dependency.exclusions.map { "${it.groupId}:${it.artifactId}" }.toSet(),
                direct = true,
                depth = 0,
                effectiveScope = dependency.scope.normalizedScope(),
                dependencyPath = dependencyPath,
            )
        }
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.depth > MAX_DEPENDENCY_DEPTH || visitedArtifacts.size >= MAX_DEPENDENCIES) {
                val failure = "dependency graph exceeds safe offline analysis limits"
                missing += failure
                dependencyGraphFailures += failure
                continue
            }
            val rawRequested = node.dependency.coordinate()
            if (rawRequested == null) {
                if (node.direct) missing += "dependency with unresolved coordinates"
                dependencyGraphFailures += "Dependency graph contains unresolved coordinates"
                continue
            }
            val requested = node.dependency.managementKey()?.let(managedDependencies::get)
                ?.let { rawRequested.copy(version = it.version) } ?: rawRequested
            if (requested.version.isBlank()) {
                if (node.direct) missing += "dependency with unresolved coordinates"
                dependencyGraphFailures += "Dependency graph contains a blank effective version"
                continue
            }
            val coordinate = resolver.resolveVersion(requested) ?: requested
            val group = coordinate.ga()
            val type = node.dependency.type.ifBlank { "jar" }
            val classifier = node.dependency.classifier?.takeIf(String::isNotBlank)
            if (type !in SUPPORTED_DEPENDENCY_TYPES) {
                val failure = "dependency type '$type' is unsupported: $group"
                missing += failure
                dependencyGraphFailures += failure
                continue
            }
            val artifactIdentity = "$group:$type:${classifier.orEmpty()}"
            val visitIdentity = "${coordinate.key}:$type:${classifier.orEmpty()}"
            val representedByReactorSources = coordinate in reactorCoordinates && isReactorSourceDependency(node.dependency)
            if (artifactIdentity in selectedArtifacts || !visitedArtifacts.add(visitIdentity) || representedByReactorSources) {
                continue
            }

            val selectedAfterPrunedPath = selectorRecords.any { record ->
                record.outcome == "PRUNED" &&
                    record.groupId == coordinate.groupId &&
                    record.artifactId == coordinate.artifactId &&
                    record.normalizedVariant == normalizedVariant(node.dependency)
            }
            val selectedDescriptorAuthorityRequired = selectedDescriptorAuthorityContext?.let { authority ->
                authority.groupId == coordinate.groupId && authority.artifactId == coordinate.artifactId
            } == true
            // An explicit descriptor-authority baseline and an alternate path after a deterministic prune
            // both require descriptor closure before binary availability can be classified.
            val descriptorFirstModel = if (selectedAfterPrunedPath || selectedDescriptorAuthorityRequired) {
                effectiveDependencyModel(coordinate, node) ?: continue
            } else {
                null
            }
            if (descriptorFirstModel?.effective?.model?.distributionManagement?.relocation != null) {
                val failure = "artifact relocation is unsupported: $group"
                missing += failure
                dependencyGraphFailures += failure
                continue
            }

            val artifact = resolver.artifactPath(coordinate, type, classifier)
            if (artifact == null) {
                val missingIdentity = "${requested.key}:$type:${classifier.orEmpty()}"
                if (node.direct) missing += missingIdentity
                else unresolvedTransitive.putIfAbsent(artifactIdentity, missingIdentity)
            } else {
                selectedArtifacts += artifactIdentity
                unresolvedTransitive.remove(artifactIdentity)
                if (node.dependency.type != "pom") {
                    artifacts.add(artifact)
                    resolvedArtifacts += BuildResolvedArtifact(
                        path = artifact,
                        coordinates = BuildArtifactCoordinates(coordinate.groupId, coordinate.artifactId, coordinate.version),
                        extension = if (type == "test-jar") "jar" else type,
                        classifier = classifier ?: if (type == "test-jar") "tests" else null,
                        scope = node.effectiveScope.toBuildDependencyScope(),
                        transitive = !node.direct,
                    )
                }
            }

            val resolvedModel = descriptorFirstModel ?: effectiveDependencyModel(coordinate, node) ?: continue
            val transitive = resolvedModel.effective
            if (descriptorFirstModel == null && transitive.model?.distributionManagement?.relocation != null) {
                artifact?.let { relocated ->
                    artifacts.remove(relocated)
                    resolvedArtifacts.removeAll { it.path == relocated }
                }
                selectedArtifacts.remove(artifactIdentity)
                val failure = "artifact relocation is unsupported: $group"
                missing += failure
                dependencyGraphFailures += failure
                continue
            }
            if (artifact == null) {
                val effectiveModel = requireNotNull(transitive.model)
                val repositoryRoot = resolver.authorityRepositoryRoot()
                val expectedPom = resolver.expectedPomPath(coordinate)
                val expectedArtifact = resolver.expectedArtifactPath(coordinate, type, classifier)
                val effectiveInputs = (transitive.inputs + setOf(resolvedModel.pom))
                    .map { it.toAbsolutePath().normalize() }
                    .toSet()
                val fixedRelease = requested.version == coordinate.version &&
                    !coordinate.version.startsWith("[") && !coordinate.version.startsWith("(") &&
                    !coordinate.version.endsWith("-SNAPSHOT", ignoreCase = true)
                val selectedLeaf = type == "jar" && classifier == null && fixedRelease &&
                    effectiveModel.distributionManagement?.relocation == null &&
                    effectiveModel.dependencies.orEmpty().isEmpty()
                if (repositoryRoot != null && expectedPom == resolvedModel.pom.toAbsolutePath().normalize() &&
                    expectedArtifact != null && selectedLeaf &&
                    Files.isRegularFile(expectedPom, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.exists(expectedArtifact, LinkOption.NOFOLLOW_LINKS) &&
                    effectiveInputs.all { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                ) {
                    val selection = MavenMissingArtifactSelection(
                        sourceSet = sourceSet,
                        projection = projection,
                        dependencyPath = node.dependencyPath.joinToString(" -> "),
                        effectiveScope = node.effectiveScope,
                    )
                    val evidenceKey = "${coordinate.key}|$type|${classifier.orEmpty()}|$expectedArtifact"
                    val prior = selectedMissingEvidence[evidenceKey]
                    selectedMissingEvidence[evidenceKey] = if (prior == null) {
                        MavenMissingArtifactEvidence(
                            identity = "${coordinate.key}:$type:${classifier.orEmpty()}",
                            expectedPath = expectedArtifact,
                            expectedIdentityManifest = null,
                            expectedSha256 = null,
                            providedTypes = emptySet(),
                            leaf = true,
                            kind = MavenMissingArtifactEvidenceKind.LOCAL_REPOSITORY_SELECTED_LEAF,
                            groupId = coordinate.groupId,
                            artifactId = coordinate.artifactId,
                            version = coordinate.version,
                            type = type,
                            classifier = classifier.orEmpty(),
                            extension = "jar",
                            repositoryRoot = repositoryRoot,
                            repositoryProvider = "maven-effective-v1",
                            repositoryLayout = "MAVEN_2",
                            repositoryPolicy = "LOCAL_ONLY_NO_SETTINGS",
                            selectedPom = expectedPom,
                            effectiveModelInputs = effectiveInputs,
                            selections = setOf(selection),
                            descriptorFacts = resolvedModel.descriptorFacts,
                            parsedDescriptorIdentityHash = resolvedModel.parsedDescriptorIdentityHash,
                            fixedRelease = true,
                            noRelocation = true,
                        )
                    } else {
                        prior.copy(
                            effectiveModelInputs = prior.effectiveModelInputs + effectiveInputs,
                            selections = prior.selections + selection,
                            descriptorFacts = prior.descriptorFacts + resolvedModel.descriptorFacts,
                        )
                    }
                }
            }
            val exclusions = node.inheritedExclusions +
                node.dependency.exclusions.map { "${it.groupId}:${it.artifactId}" }
            transitive.model?.dependencies.orEmpty().forEach { child ->
                val childPath = node.dependencyPath + pathSegment(child)
                val childGroup = child.groupId?.trim()?.takeIf(String::isNotBlank)
                val childArtifact = child.artifactId?.trim()?.takeIf(String::isNotBlank)
                val childGa = if (childGroup != null && childArtifact != null) "$childGroup:$childArtifact" else null
                val managed = child.managementKey()?.let(managedDependencies::get)
                val childScope = child.explicitNormalizedScope()
                    ?: managed?.scope
                    ?: child.scope.normalizedScope()
                val effectiveScope = deriveTransitiveScope(node.effectiveScope, childScope)
                val pruneReason = when {
                    childGa != null && childGa in exclusions -> "EXCLUSION"
                    child.isOptional -> "OPTIONAL"
                    effectiveScope == null || effectiveScope !in includedEffectiveScopes ->
                        "SCOPE_${childScope.uppercase()}"
                    else -> null
                }
                if (pruneReason != null) {
                    recordSelector(
                        child,
                        resolvedModel.pom,
                        childPath,
                        effectiveScope,
                        "PRUNED",
                        pruneReason,
                        "BEFORE_VERSION_RANGE_REPOSITORY_POM_JAR_NETWORK",
                    )
                    return@forEach
                }
                recordSelector(
                    child,
                    resolvedModel.pom,
                    childPath,
                    effectiveScope,
                    "SELECTED",
                    "TRANSITIVE",
                    "VERSION_RANGE_REPOSITORY_POM_JAR_NETWORK_REQUIRED",
                )
                pending += Pending(
                    child,
                    exclusions,
                    direct = false,
                    depth = node.depth + 1,
                    effectiveScope = requireNotNull(effectiveScope),
                    dependencyPath = childPath,
                )
            }
        }
        missing.addAll(unresolvedTransitive.values)
        // Breadth-first resolution preserves declared order and nearest-wins mediation.
        return artifacts.toList()
    }

    private fun buildEffective(pom: Path, resolver: LocalOnlyModelResolver): EffectiveBuild =
        effectiveCache.computeIfAbsent(pom.toAbsolutePath().normalize()) { normalized ->
            val before = resolver.resolvedModels.toSet()
            val relativeParents = relativeParentInputs(normalized)
            try {
                val rawModel = readRawModel(normalized)
                val declaredBomDependencies = rawModel?.let { model ->
                    model.dependencyManagement?.dependencies.orEmpty() +
                        model.profiles.orEmpty()
                            .filter { it.id in activeProfiles }
                            .flatMap { it.dependencyManagement?.dependencies.orEmpty() }
                }.orEmpty()
                val declaredBoms = declaredBomDependencies
                    .filter { it.type == "pom" && it.scope == "import" }
                    .mapNotNull { it.coordinate(rawModel?.properties ?: Properties()) }
                    .mapNotNull(resolver::pomPath).toSet()
                resolver.importedBoms.addAll(declaredBoms)
                val request = DefaultModelBuildingRequest()
                    .setPomFile(normalized.toFile())
                    .setModelResolver(resolver.newCopy())
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                    .setLocationTracking(true)
                    .setProcessPlugins(false)
                    .setActiveProfileIds(activeProfiles.toList())
                    .setInactiveProfileIds(inactiveProfiles.toList())
                    .setSystemProperties(safeSystemProperties())
                    .setUserProperties(Properties())
                val model = modelBuilder.build(request).effectiveModel
                val inputs = resolver.resolvedModels.toSet() - before + setOf(normalized) + relativeParents
                EffectiveBuild(model, inputs, resolver.importedBoms.toSet(), null)
            } catch (error: Exception) {
                val failure = (error as? ModelBuildingException)?.problems
                    ?.joinToString("; ") { it.message }
                    ?: error.message
                EffectiveBuild(null, resolver.resolvedModels.toSet() - before + setOf(normalized) + relativeParents, resolver.importedBoms.toSet(), failure)
            }
        }

    private fun validateProfileIds(profiles: Set<String>): Set<String> {
        require(profiles.size <= 64) { "Maven profile selection exceeds the bounded limit" }
        require(profiles.all { SAFE_PROFILE_ID.matches(it) }) { "Maven profile IDs contain unsupported characters" }
        return profiles.toSortedSet()
    }

    private fun safeSystemProperties(): Properties = Properties().apply {
        setProperty("user.home", System.getProperty("user.home"))
        setProperty("java.home", System.getProperty("java.home"))
        setProperty("java.version", System.getProperty("java.version"))
        setProperty("java.vendor", System.getProperty("java.vendor"))
        setProperty("file.separator", System.getProperty("file.separator"))
    }

    private fun declaredReleaseValue(model: Model): String? = listOf(
        model.build?.plugins?.firstOrNull { it.artifactId == "maven-compiler-plugin" }
            ?.configuration?.let { it as? org.codehaus.plexus.util.xml.Xpp3Dom }?.getChild("release")?.value,
        model.properties.getProperty("maven.compiler.release"),
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)

    private fun parsedJavaLevel(value: String): Int? = value.removePrefix("1.").toIntOrNull()

    private fun unsupportedReleaseValue(model: Model): String? = declaredReleaseValue(model)?.takeIf { value ->
        parsedJavaLevel(value)?.let { !JavaParserReleaseSupport.supportsRelease(it) } == true
    }

    private fun declaredSourceLevelValue(model: Model): String? = listOf(
        model.properties.getProperty("maven.compiler.source"),
        model.properties.getProperty("java.version"),
        model.build?.plugins?.firstOrNull { it.artifactId == "maven-compiler-plugin" }
            ?.configuration?.let { it as? org.codehaus.plexus.util.xml.Xpp3Dom }?.getChild("source")?.value,
    ).filterNotNull().map(String::trim).firstOrNull(String::isNotBlank)

    private fun unsupportedSourceLevelValue(model: Model): String? {
        if (declaredReleaseValue(model) != null) return null
        return declaredSourceLevelValue(model)?.takeIf { value ->
            parsedJavaLevel(value)?.let { !JavaParserReleaseSupport.supportsRelease(it) } == true
        }
    }

    private fun releaseLevel(model: Model): Int? = declaredReleaseValue(model)?.let(::parsedJavaLevel)
        ?.takeIf(JavaParserReleaseSupport::supportsRelease)

    private fun sourceLevel(model: Model): Int? {
        declaredReleaseValue(model)?.let { release ->
            return parsedJavaLevel(release)
        }
        val declared = declaredSourceLevelValue(model) ?: return 8
        return parsedJavaLevel(declared)
    }

    private fun relativeParentInputs(pom: Path): Set<Path> {
        val inputs = linkedSetOf<Path>()
        var current = pom
        repeat(32) {
            val parent = readRawModel(current)?.parent ?: return inputs
            if (parent.relativePath != null && parent.relativePath.isBlank()) return inputs
            val relative = parent.relativePath ?: "../pom.xml"
            val currentParent = current.parent ?: return inputs
            val candidate = currentParent.resolve(relative).toAbsolutePath().normalize()
            if (!candidate.exists() || !candidate.isRegularFile() || !inputs.add(candidate)) return inputs
            current = candidate
        }
        return inputs
    }

    private fun readRawModel(pom: Path): Model? = runCatching {
        val reader = org.apache.maven.model.io.xpp3.MavenXpp3Reader()
        Files.newBufferedReader(pom).use(reader::read)
    }.getOrNull()

    private fun parsedDescriptorIdentityHash(model: Model): String? {
        val coordinate = model.coordinate() ?: return null
        val relocation = model.distributionManagement?.relocation
        return hashParts(listOf(
            coordinate.key,
            model.packaging?.takeIf(String::isNotBlank) ?: "jar",
            relocation?.groupId ?: "ABSENT",
            relocation?.artifactId ?: "ABSENT",
            relocation?.version ?: "ABSENT",
        ))
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hashParts(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun rawCoordinate(pom: Path): Pair<MavenCoordinate, Path>? = try {
        readRawModel(pom)?.let { model ->
            val parent = model.parent
            val group = model.groupId ?: parent?.groupId ?: return null
            val version = model.version ?: parent?.version ?: return null
            MavenCoordinate(group, model.artifactId ?: return null, version) to pom
        }
    } catch (_: Exception) { null }

    private data class EffectiveBuild(
        val model: Model?,
        val inputs: Set<Path>,
        val importedBoms: Set<Path>,
        val failure: String?,
    )

    private fun concise(message: String): String = message.lineSequence().firstOrNull()?.take(300) ?: "unavailable"

    companion object {
        private val SAFE_PROFILE_ID = Regex("[A-Za-z0-9_.-]{1,128}")
        private val MAIN_SCOPES = setOf("compile", "provided", "system")
        private val MAIN_REPOSITORY_SCOPES = setOf("compile", "provided")
        private val RUNTIME_SCOPES = setOf("compile", "runtime")
        private val TEST_SCOPES = setOf("compile", "provided", "system", "runtime", "test")
        private val TEST_REPOSITORY_SCOPES = setOf("compile", "provided", "runtime", "test")
        private val TRANSITIVE_SCOPES = setOf("compile", "runtime")
        private val SUPPORTED_DEPENDENCY_TYPES = setOf("jar", "test-jar")
        private const val MAX_DEPENDENCIES = 4096
        private const val MAX_SELECTOR_RECORDS = 16_384
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val JAVA_FQN = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        private const val MAX_MISSING_IDENTITY_BYTES = 64L * 1024L
        private const val MAX_PROVIDED_TYPES = 4_096
        private const val MAX_DEPENDENCY_DEPTH = 128
        private const val MAX_TEST_PLUGIN_JAR_BYTES = 16L * 1024L * 1024L
        private const val MAX_TEST_PLUGIN_DESCRIPTOR_BYTES = 4L * 1024L * 1024L
    }
}

internal fun interface MavenArtifactTransport {
    fun download(uri: URI, target: Path, maxBytes: Long)
}

private object MavenCentralHttpsTransport : MavenArtifactTransport {
    override fun download(uri: URI, target: Path, maxBytes: Long) {
        require(uri.scheme == "https" && uri.host == "repo.maven.apache.org" && uri.userInfo == null) {
            "Only anonymous HTTPS Maven Central downloads are allowed"
        }
        val connection = uri.toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.readTimeout = NETWORK_TIMEOUT_MILLIS
        connection.requestMethod = "GET"
        try {
            require(connection.responseCode == HttpsURLConnection.HTTP_OK) {
                "Maven Central returned HTTP ${connection.responseCode}"
            }
            require(connection.contentLengthLong < 0 || connection.contentLengthLong <= maxBytes) {
                "Maven Central response exceeds the bounded limit"
            }
            connection.inputStream.use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= maxBytes) { "Maven Central response exceeds the bounded limit" }
                        output.write(buffer, 0, count)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val NETWORK_TIMEOUT_MILLIS = 15_000
}

private class LocalOnlyModelResolver(
    private val repository: Path,
    reactor: Map<MavenCoordinate, Path>,
    private val allowNetwork: Boolean,
    private val artifactTransport: MavenArtifactTransport,
) : ModelResolver {
    @Volatile var reactorModels: Map<MavenCoordinate, Path> = reactor
    val resolvedModels: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    val importedBoms: MutableSet<Path> = ConcurrentHashMap.newKeySet()

    override fun resolveModel(groupId: String, artifactId: String, version: String): ModelSource =
        resolve(MavenCoordinate(groupId, artifactId, version), false)

    override fun resolveModel(parent: org.apache.maven.model.Parent): ModelSource =
        resolve(MavenCoordinate(parent.groupId, parent.artifactId, parent.version), false)

    override fun resolveModel(dependency: Dependency): ModelSource =
        resolve(requireNotNull(dependency.coordinate()), true)

    private fun resolve(coordinate: MavenCoordinate, bom: Boolean): ModelSource {
        val path = pomPath(coordinate)
            ?: throw UnresolvableModelException("Model ${coordinate.key} is unavailable offline", coordinate.groupId, coordinate.artifactId, coordinate.version)
        resolvedModels.add(path)
        if (bom) importedBoms.add(path)
        return FileModelSource(path.toFile())
    }

    fun resolveVersion(coordinate: MavenCoordinate): MavenCoordinate? {
        if (!coordinate.version.startsWith("[") && !coordinate.version.startsWith("(")) return coordinate
        val base = coordinate.groupId.split('.').fold(repository.toAbsolutePath().normalize(), Path::resolve).resolve(coordinate.artifactId)
        if (!base.exists() || !base.isDirectory()) return null
        val range = runCatching { VersionRange.createFromVersionSpec(coordinate.version) }.getOrNull() ?: return null
        val versions = Files.list(base).use { stream ->
            stream.filter(Files::isDirectory).limit(MAX_RANGE_VERSIONS + 1L)
                .map { it.fileName.toString() }.toList()
        }
        if (versions.size > MAX_RANGE_VERSIONS) return null
        val selected = versions.map(::DefaultArtifactVersion).filter(range::containsVersion).maxOrNull() ?: return null
        return coordinate.copy(version = selected.toString())
    }

    fun authorityRepositoryRoot(): Path? {
        if (allowNetwork) return null
        val normalized = repository.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) return null
        var current = normalized.root ?: return null
        for (component in normalized) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return null
        }
        return runCatching { normalized.toRealPath(LinkOption.NOFOLLOW_LINKS) }.getOrNull()
    }

    fun expectedPomPath(coordinate: MavenCoordinate): Path? =
        repositoryPath(coordinate, "pom", null)?.toAbsolutePath()?.normalize()

    fun expectedArtifactPath(coordinate: MavenCoordinate, type: String, classifier: String?): Path? {
        val extension = when (type) { "test-jar" -> "jar"; else -> type.ifBlank { "jar" } }
        val effectiveClassifier = classifier?.takeIf(String::isNotBlank) ?: if (type == "test-jar") "tests" else null
        return repositoryPath(coordinate, extension, effectiveClassifier)?.toAbsolutePath()?.normalize()
    }

    fun pomPath(coordinate: MavenCoordinate): Path? = reactorModels[coordinate]
        ?: expectedPomPath(coordinate)?.let { obtain(it, coordinate, "pom", null) }

    fun artifactPath(coordinate: MavenCoordinate, type: String, classifier: String?): Path? {
        val extension = when (type) { "test-jar" -> "jar"; else -> type.ifBlank { "jar" } }
        val effectiveClassifier = classifier?.takeIf(String::isNotBlank) ?: if (type == "test-jar") "tests" else null
        return expectedArtifactPath(coordinate, type, classifier)
            ?.let { obtain(it, coordinate, extension, effectiveClassifier) }
    }

    private fun repositoryPath(coordinate: MavenCoordinate, extension: String, classifier: String?): Path? {
        val parts = (coordinate.groupId.split('.') + coordinate.artifactId + coordinate.version)
        if (parts.any { !SAFE_COMPONENT.matches(it) }) return null
        val suffix = classifier?.let { "-$it" }.orEmpty()
        return parts.fold(repository.toAbsolutePath().normalize(), Path::resolve)
            .resolve("${coordinate.artifactId}-${coordinate.version}$suffix.$extension")
    }

    private fun obtain(target: Path, coordinate: MavenCoordinate, extension: String, classifier: String?): Path? {
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return target
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !allowNetwork) return null
        val name = target.fileName?.toString() ?: return null
        val temporary = target.resolveSibling(".$name.refactorkit-download")
        val checksumTemporary = target.resolveSibling(".$name.refactorkit-sha256")
        return runCatching {
            Files.createDirectories(target.parent ?: return null)
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(checksumTemporary)
            val suffix = classifier?.let { "-$it" }.orEmpty()
            val relative = coordinate.groupId.replace('.', '/') + "/${coordinate.artifactId}/${coordinate.version}/" +
                "${coordinate.artifactId}-${coordinate.version}$suffix.$extension"
            val artifactUri = URI("https://repo.maven.apache.org/maven2/$relative")
            artifactTransport.download(artifactUri, temporary, MAX_NETWORK_ARTIFACT_BYTES)
            artifactTransport.download(URI("$artifactUri.sha256"), checksumTemporary, MAX_CHECKSUM_BYTES)
            val expected = Files.readString(checksumTemporary, Charsets.US_ASCII).trim()
                .substringBefore(' ').lowercase()
            require(SHA256.matches(expected)) { "Maven Central SHA-256 sidecar is invalid" }
            val actual = sha256(temporary)
            require(actual == expected) { "Maven Central SHA-256 verification failed" }
            runCatching {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            target
        }.getOrNull().also {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(checksumTemporary)
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun addRepository(repository: Repository) = Unit
    override fun addRepository(repository: Repository, replace: Boolean) = Unit
    override fun newCopy(): ModelResolver = this

    companion object {
        private val SAFE_COMPONENT = Regex("[A-Za-z0-9_.-]+")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private const val MAX_CHECKSUM_BYTES = 4L * 1024L
        private const val MAX_NETWORK_ARTIFACT_BYTES = 256L * 1024L * 1024L
        private const val MAX_RANGE_VERSIONS = 1_024
    }
}

private fun Model.coordinate(): MavenCoordinate? {
    val group = groupId ?: parent?.groupId ?: return null
    val ver = version ?: parent?.version ?: return null
    return MavenCoordinate(group, artifactId ?: return null, ver)
}

private fun Dependency.coordinate(): MavenCoordinate? = coordinate(Properties())

private fun Dependency.coordinate(properties: Properties): MavenCoordinate? {
    fun resolve(value: String?): String? {
        val candidate = value?.takeIf(String::isNotBlank) ?: return null
        val key = Regex("^\\$\\{([^}]+)}$").matchEntire(candidate)?.groupValues?.get(1) ?: return candidate
        return properties.getProperty(key)?.takeIf(String::isNotBlank)
    }
    val group = resolve(groupId) ?: return null
    val artifact = resolve(artifactId) ?: return null
    val ver = resolve(version) ?: return null
    return MavenCoordinate(group, artifact, ver)
}

private fun MavenCoordinate.ga(): String = "$groupId:$artifactId"

private fun Dependency.managementKey(): String? {
    val group = groupId?.takeIf(String::isNotBlank) ?: return null
    val artifact = artifactId?.takeIf(String::isNotBlank) ?: return null
    val effectiveType = type.takeIf(String::isNotBlank) ?: "jar"
    val effectiveClassifier = classifier?.takeIf(String::isNotBlank).orEmpty()
    return "$group:$artifact:$effectiveType:$effectiveClassifier"
}

/** Location tracking distinguishes a declared scope from Maven's synthesized compile default. */
private fun Dependency.explicitNormalizedScope(): String? = scope
    ?.takeIf { it.isNotBlank() && getLocation("scope") != null }
    ?.normalizedScope()

private fun deriveTransitiveScope(parentScope: String, childScope: String): String? = when (parentScope) {
    "compile" -> when (childScope) {
        "compile" -> "compile"
        "runtime" -> "runtime"
        else -> null
    }
    "provided" -> when (childScope) {
        "compile", "runtime" -> "provided"
        else -> null
    }
    "runtime" -> when (childScope) {
        "compile", "runtime" -> "runtime"
        else -> null
    }
    "test" -> when (childScope) {
        "compile", "runtime" -> "test"
        else -> null
    }
    else -> null
}

private fun String?.normalizedScope(): String = this?.takeIf(String::isNotBlank) ?: "compile"

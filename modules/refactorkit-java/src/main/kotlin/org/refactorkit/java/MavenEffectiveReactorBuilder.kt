package org.refactorkit.java

import org.apache.maven.model.Dependency
import org.apache.maven.model.Model
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
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdits
import java.net.URI
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
)

private data class MavenManagedDependency(val version: String, val scope: String)

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
        val normalizedPoms = pomFiles.map(Path::toAbsolutePath).map(Path::normalize)
            .filter { it.exists() && it.isRegularFile() }.toSet()
        val rawCoordinates = normalizedPoms.mapNotNull(::rawCoordinate).toMap()
        val resolver = LocalOnlyModelResolver(localRepository, rawCoordinates, allowNetwork, artifactTransport)
        val effective = normalizedPoms.associateWith { pom -> buildEffective(pom, resolver) }
        val effectiveCoordinates = effective.mapNotNull { (pom, result) -> result.model?.coordinate()?.let { it to pom } }.toMap()
        resolver.reactorModels = rawCoordinates + effectiveCoordinates

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
            resolveModule(workspaceRoot, model, pom, resolver, effectiveCoordinates.keys, result)
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
        effective: EffectiveBuild,
    ): MavenModuleModel {
        val moduleCoordinate = requireNotNull(model.coordinate())
        val mainDirect = model.dependencies.filter { it.scope.normalizedScope() in MAIN_SCOPES && it.type != "pom" }
        val testDirect = model.dependencies.filter { it.scope.normalizedScope() in TEST_SCOPES && it.type != "pom" }
        val systemDirect = model.dependencies.filter { it.scope.normalizedScope() == "system" && it.type != "pom" }
        val mainRepositoryDirect = mainDirect.filterNot { it.scope.normalizedScope() == "system" }
        val runtimeRepositoryDirect = model.dependencies.filter {
            it.scope.normalizedScope() in TRANSITIVE_SCOPES && it.type != "pom"
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
        val sourceDirectories = sourceDirectories(workspaceRoot, model, pom)
        val systemPathArtifacts = resolveSystemPaths(systemDirect, pom, mainMissing, mainMissingEvidence)
        val mainArtifacts = (systemPathArtifacts + resolveGraph(
            mainRepositoryDirect, MAIN_REPOSITORY_SCOPES, resolver, reactorCoordinates,
            mainMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "main", "COMPILE",
            dependencySelectorRecords, selectedMissingEvidence,
        )).distinct()
        val runtimeArtifacts = resolveGraph(
            runtimeRepositoryDirect, RUNTIME_SCOPES, resolver, reactorCoordinates,
            runtimeMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "main", "RUNTIME",
            dependencySelectorRecords, selectedMissingEvidence,
        ).distinct()
        val testArtifacts = (mainArtifacts + runtimeArtifacts + resolveGraph(
            testRepositoryDirect, TEST_REPOSITORY_SCOPES, resolver, reactorCoordinates,
            testOnlyMissing, modelInputs, importedBoms, managedDependencies, dependencyGraphFailures,
            workspaceRoot, effective.importedBoms, pom, moduleCoordinate, "test", "TEST",
            dependencySelectorRecords, selectedMissingEvidence,
        )).distinct()
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
            primaryMainSourceDirectory = sourceDirectories.primaryMain,
            primaryTestSourceDirectory = sourceDirectories.primaryTest,
            additionalMainSourceDirectories = sourceDirectories.additionalMain,
            additionalTestSourceDirectories = sourceDirectories.additionalTest,
            mainDependencies = mainRepositoryDirect.filter(::isReactorSourceDependency)
                .mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            testDependencies = testRepositoryDirect.filter(::isReactorSourceDependency)
                .mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            mainDependencyScopes = reactorDependencyScopes(mainRepositoryDirect, reactorCoordinates),
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
            modelFailure = sourceDirectories.failure,
        )
    }

    private data class SourceDirectories(
        val primaryMain: Path?,
        val primaryTest: Path?,
        val additionalMain: List<Path>,
        val additionalTest: List<Path>,
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
            plugins + options
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
        ?.takeIf { it in 8..25 }?.toString()

    private fun sourceDirectories(workspaceRoot: Path, model: Model, pom: Path): SourceDirectories {
        val primaryMain = model.build?.sourceDirectory?.takeIf(String::isNotBlank)
        val primaryTest = model.build?.testSourceDirectory?.takeIf(String::isNotBlank)
        val additionalMain = mutableListOf<String>()
        val additionalTest = mutableListOf<String>()
        model.build?.plugins.orEmpty()
            .filter { it.groupId in setOf(null, "org.codehaus.mojo") && it.artifactId == "build-helper-maven-plugin" }
            .flatMap { it.executions }
            .forEach { execution ->
                val target = when {
                    "add-source" in execution.goals -> additionalMain
                    "add-test-source" in execution.goals -> additionalTest
                    else -> return@forEach
                }
                val configuration = execution.configuration as? org.codehaus.plexus.util.xml.Xpp3Dom
                    ?: return@forEach
                configuration.getChild("sources")?.children.orEmpty()
                    .filter { it.name == "source" }
                    .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }
                    .forEach(target::add)
            }
        kotlinPlugin(model)?.let { plugin ->
            fun addConfiguredRoots(configuration: Any?, target: MutableList<String>) {
                val xml = configuration as? org.codehaus.plexus.util.xml.Xpp3Dom ?: return
                xml.getChild("sourceDirs")?.children.orEmpty()
                    .filter { it.name in setOf("source", "sourceDir") }
                    .mapNotNull { it.value?.trim()?.takeIf(String::isNotBlank) }
                    .forEach(target::add)
            }
            addConfiguredRoots(plugin.configuration, additionalMain)
            plugin.executions.forEach { execution ->
                val target = if (execution.goals.any { it.contains("test", ignoreCase = true) }) {
                    additionalTest
                } else {
                    additionalMain
                }
                addConfiguredRoots(execution.configuration, target)
            }
        }
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        val workspaceReal = runCatching { workspace.toRealPath() }.getOrDefault(workspace)
        var unsafe = 0
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
        return SourceDirectories(
            primaryMain = primaryMain?.let(::normalize),
            primaryTest = primaryTest?.let(::normalize),
            additionalMain = additionalMain.mapNotNull(::normalize).distinct().sortedBy(Path::toString),
            additionalTest = additionalTest.mapNotNull(::normalize).distinct().sortedBy(Path::toString),
            failure = if (unsafe == 0) null else "$unsafe Maven source root declaration(s) escape or cannot be validated inside the workspace",
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
                if (node.dependency.type != "pom") artifacts.add(artifact)
            }

            val resolvedModel = descriptorFirstModel ?: effectiveDependencyModel(coordinate, node) ?: continue
            val transitive = resolvedModel.effective
            if (descriptorFirstModel == null && transitive.model?.distributionManagement?.relocation != null) {
                artifact?.let(artifacts::remove)
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
        return artifacts.toList().sortedBy(Path::toString)
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

    private fun releaseLevel(model: Model): Int? = listOf(
        model.properties.getProperty("maven.compiler.release"),
        model.build?.plugins?.firstOrNull { it.artifactId == "maven-compiler-plugin" }
            ?.configuration?.let { it as? org.codehaus.plexus.util.xml.Xpp3Dom }?.getChild("release")?.value,
    ).filterNotNull().map(String::trim).filter(String::isNotBlank)
        .firstNotNullOfOrNull { it.removePrefix("1.").toIntOrNull()?.takeIf { level -> level in 8..25 } }

    private fun sourceLevel(model: Model): Int? {
        val values = listOf(
            model.properties.getProperty("maven.compiler.release"),
            model.properties.getProperty("maven.compiler.source"),
            model.properties.getProperty("java.version"),
            model.build?.plugins?.firstOrNull { it.artifactId == "maven-compiler-plugin" }
                ?.configuration?.let { it as? org.codehaus.plexus.util.xml.Xpp3Dom }?.getChild("release")?.value,
            model.build?.plugins?.firstOrNull { it.artifactId == "maven-compiler-plugin" }
                ?.configuration?.let { it as? org.codehaus.plexus.util.xml.Xpp3Dom }?.getChild("source")?.value,
        )
        val declared = values.filterNotNull().map(String::trim).filter(String::isNotBlank)
        return declared.firstNotNullOfOrNull { it.removePrefix("1.").toIntOrNull()?.takeIf { level -> level in 8..25 } }
            ?: if (declared.isEmpty()) 8 else null
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

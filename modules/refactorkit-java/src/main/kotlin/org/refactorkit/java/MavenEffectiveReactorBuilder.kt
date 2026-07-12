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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

internal data class MavenCoordinate(val groupId: String, val artifactId: String, val version: String) {
    val key: String get() = "$groupId:$artifactId:$version"
}

internal data class MavenModuleModel(
    val root: Path,
    val coordinate: MavenCoordinate,
    val packaging: String,
    val sourceLevel: Int?,
    val mainDependencies: List<MavenCoordinate>,
    val testDependencies: List<MavenCoordinate>,
    val mainArtifacts: List<Path>,
    val testArtifacts: List<Path>,
    val modelInputs: Set<Path>,
    val importedBoms: Set<Path>,
    val missingArtifacts: List<String>,
    val testGeneratedPathHints: Set<String>,
    val modelFailure: String? = null,
)

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
) {
    private val modelBuilder = DefaultModelBuilderFactory().newInstance()
    private val effectiveCache = ConcurrentHashMap<Path, EffectiveBuild>()

    fun build(workspaceRoot: Path, pomFiles: Collection<Path>): MavenReactorModel {
        val normalizedPoms = pomFiles.map(Path::toAbsolutePath).map(Path::normalize)
            .filter { it.exists() && it.isRegularFile() }.toSet()
        val rawCoordinates = normalizedPoms.mapNotNull(::rawCoordinate).toMap()
        val resolver = LocalOnlyModelResolver(localRepository, rawCoordinates, allowNetwork)
        val effective = normalizedPoms.associateWith { pom -> buildEffective(pom, resolver) }
        val effectiveCoordinates = effective.mapNotNull { (pom, result) -> result.model?.coordinate()?.let { it to pom } }.toMap()
        resolver.reactorModels = rawCoordinates + effectiveCoordinates

        val modules = effective.mapValues { (pom, result) ->
            val model = result.model
            if (model == null) {
                val fallback = rawCoordinate(pom)?.first ?: MavenCoordinate("unknown", pom.parent.fileName.toString(), "unknown")
                return@mapValues MavenModuleModel(
                    root = pom.parent,
                    coordinate = fallback,
                    packaging = "jar",
                    sourceLevel = null,
                    mainDependencies = emptyList(),
                    testDependencies = emptyList(),
                    mainArtifacts = emptyList(),
                    testArtifacts = emptyList(),
                    modelInputs = result.inputs + setOf(pom),
                    importedBoms = result.importedBoms,
                    missingArtifacts = emptyList(),
                    testGeneratedPathHints = emptySet(),
                    modelFailure = concise(result.failure ?: "effective Maven model unavailable"),
                )
            }
            resolveModule(model, pom, resolver, effectiveCoordinates.keys, result)
        }.mapKeys { it.key.parent.toAbsolutePath().normalize() }
        return MavenReactorModel(modules, normalizedPoms)
    }

    private fun resolveModule(
        model: Model,
        pom: Path,
        resolver: LocalOnlyModelResolver,
        reactorCoordinates: Set<MavenCoordinate>,
        effective: EffectiveBuild,
    ): MavenModuleModel {
        val mainDirect = model.dependencies.filter { it.scope.normalizedScope() in MAIN_SCOPES && it.type != "pom" }
        val testDirect = model.dependencies.filter { it.scope.normalizedScope() in TEST_SCOPES && it.type != "pom" }
        val managedVersions = model.dependencyManagement?.dependencies.orEmpty().mapNotNull { dependency ->
            dependency.coordinate()?.let { it.ga() to it.version }
        }.toMap()
        val missing = linkedSetOf<String>()
        val modelInputs = linkedSetOf<Path>().apply { addAll(effective.inputs); add(pom) }
        val importedBoms = linkedSetOf<Path>().apply { addAll(effective.importedBoms) }
        val mainArtifacts = resolveGraph(mainDirect, resolver, reactorCoordinates, missing, modelInputs, importedBoms, managedVersions)
        val testArtifacts = (mainArtifacts + resolveGraph(
            testDirect, resolver, reactorCoordinates, missing, modelInputs, importedBoms, managedVersions,
        )).distinct()
        return MavenModuleModel(
            root = pom.parent,
            coordinate = requireNotNull(model.coordinate()),
            packaging = model.packaging?.takeIf(String::isNotBlank) ?: "jar",
            sourceLevel = sourceLevel(model),
            mainDependencies = mainDirect.mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            testDependencies = testDirect.mapNotNull(Dependency::coordinate).filter { it in reactorCoordinates }.distinct(),
            mainArtifacts = mainArtifacts,
            testArtifacts = testArtifacts,
            modelInputs = modelInputs,
            importedBoms = importedBoms,
            missingArtifacts = missing.toList(),
            testGeneratedPathHints = model.build?.plugins.orEmpty()
                .filter { plugin -> plugin.executions.any { it.phase?.contains("test", ignoreCase = true) == true } }
                .map { it.artifactId.removeSuffix("-maven-plugin").removeSuffix("-plugin") }
                .filter(String::isNotBlank).toSet(),
        )
    }

    private fun resolveGraph(
        roots: List<Dependency>,
        resolver: LocalOnlyModelResolver,
        reactorCoordinates: Set<MavenCoordinate>,
        missing: MutableSet<String>,
        modelInputs: MutableSet<Path>,
        importedBoms: MutableSet<Path>,
        managedVersions: Map<String, String>,
    ): List<Path> {
        val artifacts = linkedSetOf<Path>()
        val visitedCoordinates = mutableSetOf<String>()
        val selectedGroups = mutableSetOf<String>()
        val unresolvedTransitive = linkedMapOf<String, String>()
        fun visit(dependency: Dependency, inheritedExclusions: Set<String>, direct: Boolean, depth: Int) {
            if (depth > MAX_DEPENDENCY_DEPTH || visitedCoordinates.size >= MAX_DEPENDENCIES) {
                missing += "dependency graph exceeds safe offline analysis limits"
                return
            }
            val rawRequested = dependency.coordinate() ?: run {
                if (direct) missing += "dependency with unresolved coordinates"
                return
            }
            val requested = managedVersions[rawRequested.ga()]?.let { rawRequested.copy(version = it) } ?: rawRequested
            if (requested.version.isBlank()) {
                if (direct) missing += "dependency with unresolved coordinates"
                return
            }
            val coordinate = resolver.resolveVersion(requested) ?: requested
            val group = coordinate.ga()
            if (group in selectedGroups || !visitedCoordinates.add(coordinate.key) ||
                coordinate in reactorCoordinates || group in inheritedExclusions) return
            val artifact = resolver.artifactPath(coordinate, dependency.type.ifBlank { "jar" }, dependency.classifier)
            if (artifact == null) {
                if (direct) missing += requested.key else unresolvedTransitive.putIfAbsent(group, requested.key)
                return
            }
            selectedGroups += group
            unresolvedTransitive.remove(group)
            if (dependency.type != "pom") artifacts.add(artifact)
            val pom = resolver.pomPath(coordinate) ?: return
            modelInputs.add(pom)
            val transitive = buildEffective(pom, resolver)
            modelInputs.addAll(transitive.inputs)
            importedBoms.addAll(transitive.importedBoms)
            val exclusions = inheritedExclusions + dependency.exclusions.map { "${it.groupId}:${it.artifactId}" }
            transitive.model?.dependencies.orEmpty()
                .filter { !it.isOptional && it.scope.normalizedScope() in TRANSITIVE_SCOPES }
                .forEach { visit(it, exclusions, false, depth + 1) }
        }
        roots.forEach { visit(it, emptySet(), true, 0) }
        missing.addAll(unresolvedTransitive.values)
        return artifacts.toList().sortedBy(Path::toString)
    }

    private fun buildEffective(pom: Path, resolver: LocalOnlyModelResolver): EffectiveBuild =
        effectiveCache.computeIfAbsent(pom.toAbsolutePath().normalize()) { normalized ->
            val before = resolver.resolvedModels.toSet()
            val relativeParents = relativeParentInputs(normalized)
            try {
                val rawModel = readRawModel(normalized)
                val declaredBoms = rawModel?.dependencyManagement?.dependencies.orEmpty()
                    .filter { it.type == "pom" && it.scope == "import" }
                    .mapNotNull { it.coordinate(rawModel?.properties ?: Properties()) }
                    .mapNotNull(resolver::pomPath).toSet()
                resolver.importedBoms.addAll(declaredBoms)
                val request = DefaultModelBuildingRequest()
                    .setPomFile(normalized.toFile())
                    .setModelResolver(resolver.newCopy())
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL)
                    .setProcessPlugins(false)
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

    private fun safeSystemProperties(): Properties = Properties().apply {
        setProperty("user.home", System.getProperty("user.home"))
        setProperty("java.home", System.getProperty("java.home"))
        setProperty("java.version", System.getProperty("java.version"))
        setProperty("java.vendor", System.getProperty("java.vendor"))
        setProperty("file.separator", System.getProperty("file.separator"))
    }

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
            val candidate = current.parent.resolve(relative).toAbsolutePath().normalize()
            if (!candidate.exists() || !candidate.isRegularFile() || !inputs.add(candidate)) return inputs
            current = candidate
        }
        return inputs
    }

    private fun readRawModel(pom: Path): Model? = runCatching {
        val reader = org.apache.maven.model.io.xpp3.MavenXpp3Reader()
        Files.newBufferedReader(pom).use(reader::read)
    }.getOrNull()

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
        private val MAIN_SCOPES = setOf("compile", "provided", "system")
        private val TEST_SCOPES = setOf("compile", "provided", "system", "runtime", "test")
        private val TRANSITIVE_SCOPES = setOf("compile", "runtime")
        private const val MAX_DEPENDENCIES = 4096
        private const val MAX_DEPENDENCY_DEPTH = 128
    }
}

private class LocalOnlyModelResolver(
    private val repository: Path,
    reactor: Map<MavenCoordinate, Path>,
    private val allowNetwork: Boolean,
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
        val versions = Files.list(base).use { stream -> stream.filter(Files::isDirectory).map { it.fileName.toString() }.toList() }
        val selected = versions.map(::DefaultArtifactVersion).filter(range::containsVersion).maxOrNull() ?: return null
        return coordinate.copy(version = selected.toString())
    }

    fun pomPath(coordinate: MavenCoordinate): Path? = reactorModels[coordinate]
        ?: repositoryPath(coordinate, "pom", null)?.let { obtain(it, coordinate, "pom", null) }

    fun artifactPath(coordinate: MavenCoordinate, type: String, classifier: String?): Path? {
        val extension = when (type) { "test-jar" -> "jar"; else -> type.ifBlank { "jar" } }
        val effectiveClassifier = classifier?.takeIf(String::isNotBlank) ?: if (type == "test-jar") "tests" else null
        return repositoryPath(coordinate, extension, effectiveClassifier)?.let { obtain(it, coordinate, extension, effectiveClassifier) }
    }

    private fun repositoryPath(coordinate: MavenCoordinate, extension: String, classifier: String?): Path? {
        val parts = (coordinate.groupId.split('.') + coordinate.artifactId + coordinate.version)
        if (parts.any { !SAFE_COMPONENT.matches(it) }) return null
        val suffix = classifier?.let { "-$it" }.orEmpty()
        return parts.fold(repository.toAbsolutePath().normalize(), Path::resolve)
            .resolve("${coordinate.artifactId}-${coordinate.version}$suffix.$extension")
    }

    private fun obtain(target: Path, coordinate: MavenCoordinate, extension: String, classifier: String?): Path? {
        if (target.exists() && target.isRegularFile()) return target
        if (!allowNetwork) return null
        val temporary = target.resolveSibling(".${target.fileName}.refactorkit-download")
        return runCatching {
            Files.createDirectories(target.parent)
            val suffix = classifier?.let { "-$it" }.orEmpty()
            val relative = coordinate.groupId.replace('.', '/') + "/${coordinate.artifactId}/${coordinate.version}/" +
                "${coordinate.artifactId}-${coordinate.version}$suffix.$extension"
            val connection = URI("https://repo.maven.apache.org/maven2/$relative").toURL().openConnection().apply {
                connectTimeout = NETWORK_TIMEOUT_MILLIS
                readTimeout = NETWORK_TIMEOUT_MILLIS
            }
            if (connection.contentLengthLong > MAX_NETWORK_ARTIFACT_BYTES) error("Artifact exceeds network limit")
            connection.getInputStream().use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_NETWORK_ARTIFACT_BYTES) error("Artifact exceeds network limit")
                        output.write(buffer, 0, count)
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull().also { resolved -> if (resolved == null) Files.deleteIfExists(temporary) }
    }

    override fun addRepository(repository: Repository) = Unit
    override fun addRepository(repository: Repository, replace: Boolean) = Unit
    override fun newCopy(): ModelResolver = this

    companion object {
        private val SAFE_COMPONENT = Regex("[A-Za-z0-9_.-]+")
        private const val NETWORK_TIMEOUT_MILLIS = 15_000
        private const val MAX_NETWORK_ARTIFACT_BYTES = 256L * 1024L * 1024L
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
private fun String?.normalizedScope(): String = this?.takeIf(String::isNotBlank) ?: "compile"

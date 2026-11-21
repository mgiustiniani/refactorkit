package org.refactorkit.java

import org.refactorkit.core.ClasspathEvidence
import org.refactorkit.core.ClasspathEvidenceKind
import org.refactorkit.core.Module
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.OwnedSourceInventoryReader
import org.refactorkit.core.SourceFile
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.Workspace
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

internal const val JAVA_SAFE_DELETE_TEXT_SCAN_VERSION_KEY = "java.safeDelete.textReferenceScan.version"
internal const val JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY = "java.safeDelete.textReferenceScan.status"
internal const val JAVA_MAVEN_MODEL_POM_INPUT_COUNT_KEY = "java.maven.modelPomInput.count"
internal const val JAVA_MAVEN_MODEL_POM_INPUT_PREFIX = "java.maven.modelPomInput."
internal const val JAVA_SAFE_DELETE_TEXT_SCAN_INPUTS_KEY = "java.safeDelete.textReferenceScan.inputs"
internal const val JAVA_SAFE_DELETE_TEXT_SCAN_BYTES_KEY = "java.safeDelete.textReferenceScan.bytes"
internal const val JAVA_SAFE_DELETE_TEXT_SCAN_BINARY_INPUTS_KEY = "java.safeDelete.textReferenceScan.binaryInputs"
internal const val JAVA_SAFE_DELETE_TEXT_SCAN_UNMODELED_INPUTS_KEY = "java.safeDelete.textReferenceScan.unmodeledInputs"
private const val JAVA_SAFE_DELETE_TEXT_SCAN_VERSION = "workspace-text-v1"
private const val SAFE_DELETE_TEXT_LANGUAGE_ID = "java-safe-delete-text"

class JavaProjectScanner(
    private val allowNetworkDependencyResolution: Boolean = false,
    private val localMavenRepository: Path = Path.of(System.getProperty("user.home"), ".m2", "repository"),
    private val activeMavenProfiles: Set<String> = emptySet(),
    private val inactiveMavenProfiles: Set<String> = emptySet(),
    private val selectedMavenDescriptorAuthority: MavenSelectedDescriptorAuthorityContext? = null,
    private val effectiveGradleModelProvider: ((Path) -> org.refactorkit.core.BuildModel?)? = null,
    private val effectiveGradleEvidenceProvider: ((Path) -> List<ClasspathEvidence>)? = null,
) {
    fun scan(root: Path): ProjectSnapshot = scanInternal(root, includeBuildModels = true)

    fun scan(root: Path, mavenRuntime: MavenRuntimeSelection?): ProjectSnapshot =
        scanInternal(root, includeBuildModels = true, mavenRuntime = mavenRuntime)

    internal fun scanWithoutBuildModels(root: Path): ProjectSnapshot =
        scanInternal(root, includeBuildModels = false)

    private fun scanInternal(
        root: Path,
        includeBuildModels: Boolean,
        mavenRuntime: MavenRuntimeSelection? = null,
    ): ProjectSnapshot {
        val normalizedRoot = root.toAbsolutePath().normalize()
        DeclaredJavaProjectProvider().captureIfDeclared(normalizedRoot, includeBuildModels)?.let { return it }
        val discoveredModuleRoots = detectModuleRoots(normalizedRoot)
        val pomFiles = findBuildFiles(normalizedRoot, "pom.xml")
        val gradleBuildModel = if (
            findBuildFiles(normalizedRoot, "build.gradle").isNotEmpty() ||
            findBuildFiles(normalizedRoot, "build.gradle.kts").isNotEmpty()
        ) effectiveGradleModelProvider?.invoke(normalizedRoot) ?: GradleDeclarativeModelBuilder().build(normalizedRoot) else null
        val mavenReactor = if (pomFiles.isNotEmpty()) {
            MavenEffectiveReactorBuilder(
                localMavenRepository,
                allowNetworkDependencyResolution,
                activeMavenProfiles,
                inactiveMavenProfiles,
                selectedDescriptorAuthorityContext = selectedMavenDescriptorAuthority,
                mavenRuntime = mavenRuntime,
            ).build(normalizedRoot, pomFiles)
        } else null
        val mavenByRoot = mavenReactor?.modules.orEmpty()
        val moduleRoots = (
            discoveredModuleRoots +
                mavenByRoot.values.filter { it.packaging != "pom" }.map(MavenModuleModel::root) +
                gradleBuildModel?.modules.orEmpty().map(org.refactorkit.core.BuildModule::root)
        )
            .distinct().sortedBy(Path::toString).ifEmpty { listOf(normalizedRoot) }
        val moduleNames = moduleRoots.associateWith { moduleRoot ->
            gradleBuildModel?.takeIf { it.providerId == "gradle-effective-v1" }?.modules
                ?.firstOrNull { it.root == moduleRoot }?.id ?: moduleName(normalizedRoot, moduleRoot)
        }
        val coordinateNames = mavenByRoot.values
            .filter { it.root in moduleRoots }
            .associate { it.coordinate to moduleNames.getValue(it.root) }
        val legacyIdentities = buildMap {
            moduleNames.forEach { (moduleRoot, name) ->
                put(name, name)
                mavenArtifactId(moduleRoot)?.let { put(it, name) }
            }
        }

        val initialModules = moduleRoots.map { moduleRoot ->
            val maven = mavenByRoot[moduleRoot]
            val gradle = gradleBuildModel?.modules?.firstOrNull { it.root == moduleRoot }
            val mainRoots = if (maven != null) effectiveMavenSourceRoots(
                conventionalSourceDirectories = conventionalMainSourceRoots(moduleRoot),
                defaultJavaSourceDirectory = moduleRoot.resolve("src/main/java"),
                primarySourceDirectory = maven.primaryMainSourceDirectory,
                additionalSourceDirectories = maven.additionalMainSourceDirectories,
            ) else conventionalMainSourceRoots(moduleRoot)
            val testRoots = if (maven != null) effectiveMavenSourceRoots(
                conventionalSourceDirectories = conventionalTestSourceRoots(moduleRoot),
                defaultJavaSourceDirectory = moduleRoot.resolve("src/test/java"),
                primarySourceDirectory = maven.primaryTestSourceDirectory,
                additionalSourceDirectories = maven.additionalTestSourceDirectories,
            ) else conventionalTestSourceRoots(moduleRoot)
            val gradleMainRoots = gradle?.sourceSets.orEmpty()
                .filter { it.kind == SourceSetKind.MAIN }.flatMap { it.sourceRoots }.map(normalizedRoot::resolve)
            val gradleTestRoots = gradle?.sourceSets.orEmpty()
                .filter { it.kind == SourceSetKind.TEST }.flatMap { it.sourceRoots }.map(normalizedRoot::resolve)
            val gradleCustomRoots = gradle?.sourceSets.orEmpty()
                .filter { it.kind !in setOf(SourceSetKind.MAIN, SourceSetKind.TEST) }
                .flatMap { it.sourceRoots }.map(normalizedRoot::resolve)
            val effectiveMainRoots = (mainRoots + gradleMainRoots).distinct()
            val effectiveTestRoots = (testRoots + gradleTestRoots).distinct()
            val explicitGeneratedTest = (generatedSourceRoots(moduleRoot, test = true) +
                effectiveTestRoots.filter { declaredGeneratedSourceRoot(moduleRoot, it) }).distinct()
            val discoveredGeneratedMain = (generatedSourceRoots(moduleRoot, test = false) +
                effectiveMainRoots.filter { declaredGeneratedSourceRoot(moduleRoot, it) }).distinct()
            val pluginTestGenerated = discoveredGeneratedMain.filter { generatedRoot ->
                maven?.testGeneratedPathHints.orEmpty().any { hint ->
                    generatedRoot.any { component -> component.toString().contains(hint, ignoreCase = true) }
                }
            }
            val effectiveGradleSets = gradle?.sourceSets.orEmpty().takeIf { gradleBuildModel?.providerId == "gradle-effective-v1" }.orEmpty()
            val generatedMain = (discoveredGeneratedMain - pluginTestGenerated.toSet() + effectiveGradleSets
                .filter { it.kind == SourceSetKind.MAIN }.flatMap { it.generatedSourceRoots }.map(normalizedRoot::resolve)).distinct()
            val generatedTest = (explicitGeneratedTest + pluginTestGenerated + effectiveGradleSets
                .filter { it.kind == SourceSetKind.TEST }.flatMap { it.generatedSourceRoots }.map(normalizedRoot::resolve)).distinct()
            val sourceRoots = (effectiveMainRoots + effectiveTestRoots + gradleCustomRoots + generatedMain + generatedTest)
                .map(normalizedRoot::relativize).distinct()
            val declaredMainOutputs = if (maven != null) listOfNotNull(maven.mainOutputDirectory) else listOf(
                moduleRoot.resolve("target/classes"),
                moduleRoot.resolve("build/classes/java/main"),
                moduleRoot.resolve("build/classes/kotlin/main"),
            )
            val declaredTestOutputs = if (maven != null) listOfNotNull(maven.testOutputDirectory) else listOf(
                moduleRoot.resolve("target/test-classes"),
                moduleRoot.resolve("build/classes/java/test"),
                moduleRoot.resolve("build/classes/kotlin/test"),
            )
            val conventionalMainOutputs = declaredMainOutputs.filter { it.exists() && it.isDirectory() }
            val conventionalTestOutputs = declaredTestOutputs.filter { it.exists() && it.isDirectory() }
            val localEntries = localJarEntries(moduleRoot) + declaredClasspathEntries(moduleRoot)
            val mainClasspath = if (maven?.modelFailure == null && maven != null) {
                (conventionalMainOutputs.map(normalizedRoot::relativize) + maven.mainArtifacts).distinct()
            } else if (gradleBuildModel?.providerId == "gradle-effective-v1" && gradle != null) {
                gradle.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.classpathEntries.orEmpty()
            } else {
                (conventionalMainOutputs + localEntries).map(normalizedRoot::relativize).distinct()
            }
            val mainRuntimeClasspath = if (maven?.modelFailure == null && maven != null) {
                (conventionalMainOutputs.map(normalizedRoot::relativize) + maven.runtimeArtifacts).distinct()
            } else if (gradleBuildModel?.providerId == "gradle-effective-v1" && gradle != null) {
                gradle.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.runtimeClasspathEntries.orEmpty()
            } else mainClasspath
            val testClasspath = if (maven?.modelFailure == null && maven != null) {
                ((conventionalTestOutputs + conventionalMainOutputs).map(normalizedRoot::relativize) + maven.testArtifacts).distinct()
            } else if (gradleBuildModel?.providerId == "gradle-effective-v1" && gradle != null) {
                gradle.sourceSets.firstOrNull { it.kind == SourceSetKind.TEST }?.classpathEntries.orEmpty()
            } else {
                (mainClasspath + conventionalTestOutputs.map(normalizedRoot::relativize)).distinct()
            }
            val gradleMainDependencies = gradle?.sourceSets.orEmpty()
                .filter { it.kind == SourceSetKind.MAIN }.flatMap { it.moduleDependencies }.map { it.targetModuleId }
            val gradleTestDependencies = gradle?.sourceSets.orEmpty()
                .filter { it.kind != SourceSetKind.MAIN }.flatMap { it.moduleDependencies }.map { it.targetModuleId }
            val mainDependencies = maven?.takeIf { it.modelFailure == null }?.mainDependencies?.mapNotNull(coordinateNames::get)
                ?: gradleMainDependencies.ifEmpty { detectModuleDependencies(moduleRoot, legacyIdentities) }
            val testDependencies = maven?.takeIf { it.modelFailure == null }?.testDependencies?.mapNotNull(coordinateNames::get)
                ?: gradleTestDependencies.ifEmpty { mainDependencies }
            val mavenSourceLevelRefusal = maven?.unsupportedReleaseValue?.let { release ->
                JavaProjectSourceLevelPolicy.resolve(release, null) as? JavaProjectSourceLevelDecision.Refused
            } ?: maven?.unsupportedSourceLevelValue?.let { sourceLevel ->
                JavaProjectSourceLevelPolicy.resolve(null, sourceLevel) as? JavaProjectSourceLevelDecision.Refused
            }
            val sourceLevel = maven?.takeIf { it.modelFailure == null }?.sourceLevel
                ?: gradle?.sourceSets?.firstOrNull { it.kind == SourceSetKind.MAIN }
                    ?.attributes?.get("java.sourceLevel")?.toIntOrNull()
                ?: detectJavaSourceLevel(moduleRoot)
            val sourceLevelRefusal = mavenSourceLevelRefusal ?:
                (JavaProjectSourceLevelPolicy.resolve(null, sourceLevel.toString()) as? JavaProjectSourceLevelDecision.Refused)
            val languageSettings = buildMap {
                put("java.sourceLevel", sourceLevel.toString())
                sourceLevelRefusal?.let { refusal ->
                    put("java.sourceLevel.status", "unavailable")
                    put("java.sourceLevel.refusalCode", refusal.code)
                    put("java.sourceLevel.message", refusal.message)
                }
                if (maven != null) {
                    val reactorPoms = mavenReactor?.reactorPomFiles.orEmpty()
                        .mapTo(hashSetOf()) { it.toAbsolutePath().normalize() }
                    val effectiveModelPomInputs = maven.modelInputs.asSequence()
                        .map { it.toAbsolutePath().normalize() }
                        .filter { it in reactorPoms && it.startsWith(normalizedRoot) }
                        .filterNot { it == moduleRoot.resolve("pom.xml").toAbsolutePath().normalize() }
                        .map { normalizedRoot.relativize(it).toString().replace('\\', '/') }
                        .distinct()
                        .sorted()
                        .toList()
                    put("java.buildSystem", "maven")
                    put("java.maven.groupId", maven.coordinate.groupId)
                    put("java.maven.artifactId", maven.coordinate.artifactId)
                    put("java.maven.version", maven.coordinate.version)
                    put("java.maven.packaging", maven.packaging)
                    maven.reactorDescriptorFailure?.let { failure ->
                        val range = failure.moduleDeclarationRange
                        put("java.maven.reactorDescriptor.code", "java.maven.reactorDescriptor.missing")
                        put("java.maven.reactorDescriptor.inputKind", "ACTIVE_REACTOR_POM")
                        put("java.maven.reactorDescriptor.module", failure.module)
                        put(
                            "java.maven.reactorDescriptor.declaringPom",
                            evidencePath(normalizedRoot, failure.declaringPom).toString().replace('\\', '/'),
                        )
                        put(
                            "java.maven.reactorDescriptor.declaringPomSha256",
                            failure.declaringPomContentSha256,
                        )
                        put(
                            "java.maven.reactorDescriptor.moduleDeclarationRange",
                            "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}",
                        )
                        put(
                            "java.maven.reactorDescriptor.expectedPath",
                            evidencePath(normalizedRoot, failure.expectedPath).toString().replace('\\', '/'),
                        )
                        put("java.maven.reactorDescriptor.condition", failure.condition)
                        put("java.maven.reactorDescriptor.noFollow", "true")
                        put(
                            "java.maven.reactorDescriptor.noFollowAbsenceFactHash",
                            failure.noFollowAbsenceFactHash,
                        )
                    }
                    put(
                        "java.dependencyGraph.status",
                        if (maven.dependencyGraphFailures.isEmpty()) "complete" else "incomplete",
                    )
                    if (maven.dependencyGraphFailures.isNotEmpty()) {
                        put(
                            "java.dependencyGraph.message",
                            maven.dependencyGraphFailures.take(8).joinToString("; "),
                        )
                    }
                    val selectorRecords = maven.dependencySelectorRecords.sortedWith(
                        compareBy<MavenDependencySelectorRecord> { it.consumer }
                            .thenBy { it.sourceSet }
                            .thenBy { it.projection }
                            .thenBy { it.dependencyPath }
                            .thenBy { it.outcome }
                            .thenBy { it.reason },
                    )
                    put("java.maven.selector.count", selectorRecords.size.toString())
                    selectorRecords.forEachIndexed { index, record ->
                        val key = "java.maven.selector.$index"
                        put(
                            "$key.declaringPom",
                            evidencePath(normalizedRoot, record.declaringPom).toString().replace('\\', '/'),
                        )
                        put("$key.consumer", record.consumer)
                        put("$key.sourceSet", record.sourceSet)
                        put("$key.projection", record.projection)
                        put("$key.dependencyPath", record.dependencyPath)
                        put("$key.groupId", record.groupId)
                        put("$key.artifactId", record.artifactId)
                        put("$key.declaredVersion", record.declaredVersion)
                        put("$key.declaredScope", record.declaredScope)
                        put("$key.effectiveScope", record.effectiveScope)
                        put("$key.optional", record.optional.toString())
                        put("$key.type", record.type)
                        put("$key.classifier", record.classifier)
                        put("$key.variant", record.normalizedVariant)
                        put("$key.outcome", record.outcome)
                        put("$key.reason", record.reason)
                        put("$key.lookupBoundary", record.lookupBoundary)
                    }
                    put(
                        "java.maven.pomPath",
                        normalizedRoot.relativize(moduleRoot.resolve("pom.xml")).toString().replace('\\', '/'),
                    )
                    put(JAVA_MAVEN_MODEL_POM_INPUT_COUNT_KEY, effectiveModelPomInputs.size.toString())
                    effectiveModelPomInputs.forEachIndexed { index, path ->
                        put("$JAVA_MAVEN_MODEL_POM_INPUT_PREFIX$index", path)
                    }
                    put("java.buildModel.status", when {
                        maven.modelFailure != null -> "unavailable"
                        maven.sourceRootFailure != null -> "partial"
                        else -> "available"
                    })
                    maven.modelFailure?.let { put("java.buildModel.message", it) }
                    maven.sourceRootFailure?.let { put("java.buildModel.message", it) }
                    when {
                        sourceLevelRefusal != null -> Unit
                        maven.sourceLevel == null -> {
                            put("java.sourceLevel.status", "unavailable")
                            put("java.sourceLevel.message", "Effective Maven source level could not be resolved")
                        }
                        else -> put("java.sourceLevel.status", "available")
                    }
                    maven.releaseLevel?.let { put("java.release", it.toString()) }
                    put("java.platformSelection", when {
                        sourceLevelRefusal != null -> "unsupported"
                        maven.releaseLevel == null -> "explicit-jdk-required"
                        else -> "maven-release"
                    })
                    put("kotlin.platform", if (maven.kotlinPluginConfigured) "jvm" else "unconfigured")
                    maven.kotlinJvmTarget?.let { put("kotlin.jvmTarget", it) }
                    maven.kotlinTargetJdk?.let { put("kotlin.targetJdk", it) }
                    if (maven.kotlinCompilerPlugins.isNotEmpty()) {
                        put("kotlin.compilerPlugins", maven.kotlinCompilerPlugins.joinToString(","))
                    }
                    maven.mainDependencyScopes.forEach { (coordinate, scope) ->
                        coordinateNames[coordinate]?.let { dependency ->
                            put("java.moduleDependency.main.$dependency.scope", scope)
                        }
                    }
                    maven.testDependencyScopes.forEach { (coordinate, scope) ->
                        coordinateNames[coordinate]?.let { dependency ->
                            put("java.moduleDependency.test.$dependency.scope", scope)
                        }
                    }
                    put("java.classpath.status", if (maven.missingArtifacts.isEmpty()) "available" else "unavailable")
                    fun recordMissingArtifactEvidence(
                        prefix: String,
                        missingArtifacts: List<String>,
                        evidence: List<MavenMissingArtifactEvidence>,
                    ) {
                        put("$prefix.missing.count", missingArtifacts.size.toString())
                        put("$prefix.missing.evidence.count", evidence.size.toString())
                        evidence.sortedWith(compareBy<MavenMissingArtifactEvidence> { it.identity }.thenBy { it.kind.name })
                            .forEachIndexed { index, entry ->
                                val key = "$prefix.missing.evidence.$index"
                                put("$key.kind", entry.kind.name)
                                put("$key.identity", entry.identity)
                                put("$key.path", evidencePath(normalizedRoot, entry.expectedPath).toString().replace('\\', '/'))
                                entry.expectedIdentityManifest?.let { manifest ->
                                    put("$key.manifest", evidencePath(normalizedRoot, manifest).toString().replace('\\', '/'))
                                }
                                entry.expectedSha256?.let { put("$key.sha256", it) }
                                put("$key.providedTypes", entry.providedTypes.sorted().joinToString(","))
                                put("$key.leaf", entry.leaf.toString())
                                if (entry.kind == MavenMissingArtifactEvidenceKind.LOCAL_REPOSITORY_SELECTED_LEAF) {
                                    val groupId = requireNotNull(entry.groupId)
                                    val artifactId = requireNotNull(entry.artifactId)
                                    val version = requireNotNull(entry.version)
                                    val repositoryRoot = requireNotNull(entry.repositoryRoot)
                                    val selectedPom = requireNotNull(entry.selectedPom)
                                    put("$key.groupId", groupId)
                                    put("$key.artifactId", artifactId)
                                    put("$key.version", version)
                                    put("$key.type", requireNotNull(entry.type))
                                    put("$key.classifier", entry.classifier.orEmpty())
                                    put("$key.extension", requireNotNull(entry.extension))
                                    put("$key.fixedRelease", entry.fixedRelease.toString())
                                    put("$key.noRelocation", entry.noRelocation.toString())
                                    put("$key.repository.provider", requireNotNull(entry.repositoryProvider))
                                    put("$key.repository.layout", requireNotNull(entry.repositoryLayout))
                                    put("$key.repository.policy", requireNotNull(entry.repositoryPolicy))
                                    put(
                                        "$key.repository.root",
                                        evidencePath(normalizedRoot, repositoryRoot).toString().replace('\\', '/'),
                                    )
                                    put(
                                        "$key.repository.identityHash",
                                        sha256(
                                            listOf(
                                                entry.repositoryProvider,
                                                entry.repositoryLayout,
                                                repositoryRoot.toAbsolutePath().normalize(),
                                                entry.repositoryPolicy,
                                            ).joinToString("\u0000").toByteArray(Charsets.UTF_8),
                                        ),
                                    )
                                    put(
                                        "$key.pomPath",
                                        evidencePath(normalizedRoot, selectedPom).toString().replace('\\', '/'),
                                    )
                                    put("$key.pomSha256", sha256(Files.readAllBytes(selectedPom)))
                                    val effectiveInputs = entry.effectiveModelInputs.sortedBy(Path::toString)
                                    put("$key.effectiveInput.count", effectiveInputs.size.toString())
                                    effectiveInputs.forEachIndexed { inputIndex, input ->
                                        val inputKey = "$key.effectiveInput.$inputIndex"
                                        put("$inputKey.path", evidencePath(normalizedRoot, input).toString().replace('\\', '/'))
                                        put("$inputKey.sha256", sha256(Files.readAllBytes(input)))
                                    }
                                    val descriptorFacts = entry.descriptorFacts.sortedWith(
                                        compareBy<MavenSelectedDescriptorFact> { it.layer.ordinal }
                                            .thenBy { it.path.toString() },
                                    )
                                    put("$key.descriptorFact.count", descriptorFacts.size.toString())
                                    descriptorFacts.forEachIndexed { factIndex, fact ->
                                        val factKey = "$key.descriptorFact.$factIndex"
                                        put(
                                            "$factKey.path",
                                            evidencePath(normalizedRoot, fact.path).toString().replace('\\', '/'),
                                        )
                                        put("$factKey.layer", fact.layer.name)
                                        put("$factKey.sha256", fact.contentSha256)
                                    }
                                    entry.parsedDescriptorIdentityHash?.let {
                                        put("$key.parsedDescriptorIdentityHash", it)
                                    }
                                    val selections = entry.selections.sortedWith(
                                        compareBy<MavenMissingArtifactSelection> { it.sourceSet }
                                            .thenBy { it.projection }
                                            .thenBy { it.dependencyPath },
                                    )
                                    put("$key.selection.count", selections.size.toString())
                                    selections.forEachIndexed { selectionIndex, selection ->
                                        val selectionKey = "$key.selection.$selectionIndex"
                                        put("$selectionKey.sourceSet", selection.sourceSet)
                                        put("$selectionKey.projection", selection.projection)
                                        put("$selectionKey.dependencyPath", selection.dependencyPath)
                                        put("$selectionKey.effectiveScope", selection.effectiveScope)
                                    }
                                    put("$key.leafProof", "SELECTED_SUBTREE_SIZE_1_OUTGOING_SELECTED_0")
                                }
                            }
                    }
                    recordMissingArtifactEvidence(
                        "java.mainClasspath",
                        maven.mainMissingArtifacts,
                        maven.mainMissingArtifactEvidence,
                    )
                    recordMissingArtifactEvidence(
                        "java.testClasspath",
                        maven.testMissingArtifacts,
                        maven.testMissingArtifactEvidence,
                    )
                    fun missingMessage(missingArtifacts: List<String>): String {
                        val missing = missingArtifacts.sorted()
                        val suffix = if (missing.size > 8) " (+${missing.size - 8} more)" else ""
                        return "Offline artifacts unavailable: ${missing.take(8).joinToString(", ")}$suffix"
                    }
                    put("java.mainClasspath.status", if (maven.mainMissingArtifacts.isEmpty()) "available" else "unavailable")
                    put("java.runtimeClasspath.status", if (maven.runtimeMissingArtifacts.isEmpty()) "available" else "unavailable")
                    put("java.testClasspath.status", if (maven.testMissingArtifacts.isEmpty()) "available" else "unavailable")
                    if (maven.mainMissingArtifacts.isNotEmpty()) {
                        put("java.mainClasspath.message", missingMessage(maven.mainMissingArtifacts))
                    }
                    if (maven.runtimeMissingArtifacts.isNotEmpty()) {
                        put("java.runtimeClasspath.message", missingMessage(maven.runtimeMissingArtifacts))
                    }
                    if (maven.testMissingArtifacts.isNotEmpty()) {
                        put("java.testClasspath.message", missingMessage(maven.testMissingArtifacts))
                    }
                    if (maven.missingArtifacts.isNotEmpty()) {
                        put("java.classpath.message", missingMessage(maven.missingArtifacts))
                    }
                } else if (gradle != null) {
                    putAll(gradle.attributes)
                } else {
                    put("java.buildSystem", "conventional")
                    put("java.buildModel.status", "partial")
                    put("java.buildModel.message", "No effective build descriptor model is available")
                }
            }
            Module(
                name = moduleNames.getValue(moduleRoot),
                root = moduleRoot,
                sourceRoots = sourceRoots,
                classpathEntries = (mainClasspath + testClasspath).distinct(),
                dependencies = (mainDependencies + testDependencies).distinct().sorted(),
                languageSettings = languageSettings,
                mainSourceRoots = (effectiveMainRoots + generatedMain).map(normalizedRoot::relativize),
                testSourceRoots = effectiveTestRoots.map(normalizedRoot::relativize),
                generatedSourceRoots = generatedMain.map(normalizedRoot::relativize),
                generatedTestSourceRoots = generatedTest.map(normalizedRoot::relativize),
                mainClasspathEntries = mainClasspath,
                mainRuntimeClasspathEntries = mainRuntimeClasspath,
                testClasspathEntries = testClasspath,
                mainDependencies = mainDependencies.distinct().sorted(),
                testDependencies = (mainDependencies + testDependencies).distinct().sorted(),
                mainOutputDirectories = declaredMainOutputs.map(normalizedRoot::relativize),
                testOutputDirectories = declaredTestOutputs.map(normalizedRoot::relativize),
            )
        }
        val initialByName = initialModules.associateBy(Module::name)
        fun unavailableDependency(module: Module, seen: Set<String> = emptySet()): String? {
            if (module.name in seen) return null
            return (module.mainDependencies + module.testDependencies).distinct().firstNotNullOfOrNull { dependencyName ->
                val dependency = initialByName[dependencyName] ?: return@firstNotNullOfOrNull null
                if (dependency.languageSettings["java.buildModel.status"] == "unavailable" ||
                    dependency.languageSettings["java.classpath.status"] == "unavailable") dependencyName
                else unavailableDependency(dependency, seen + module.name)
            }
        }
        val modules = initialModules.map { module ->
            val unavailable = unavailableDependency(module)
            if (unavailable == null || module.languageSettings["java.classpath.status"] == "unavailable") module
            else module.copy(languageSettings = module.languageSettings + mapOf(
                "java.classpath.status" to "unavailable",
                "java.classpath.message" to "Upstream reactor module '$unavailable' has unavailable analysis inputs",
            ))
        }

        val sourceRoots = modules.flatMap { module -> module.sourceRoots.map(normalizedRoot::resolve) }.distinct()
        val files = OwnedSourceInventoryReader(
            JVM_LANGUAGE_IDS, ignoredDirectories = emptySet(), allowMissingRoot = true,
        ).captureSourceRoots(normalizedRoot, sourceRoots.map {
            normalizedRoot.relativize(it.toAbsolutePath().normalize())
        }).sources

        val systemPathArtifacts = mavenReactor?.modules?.values.orEmpty()
            .flatMap(MavenModuleModel::systemPathArtifacts).map { it.toAbsolutePath().normalize() }.toSet()
        val classpathEvidence = buildList {
            addAll(effectiveGradleEvidenceProvider?.invoke(normalizedRoot).orEmpty())
            gradleBuildModel?.takeIf { it.providerId == "gradle-effective-v1" }?.modules.orEmpty().flatMap { it.sourceSets }
                .flatMap { it.classpathEntries + it.runtimeClasspathEntries }.distinct().forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.ENTRY))
                }
            gradleBuildModel?.takeIf { it.providerId == "gradle-effective-v1" }?.modules.orEmpty().forEach { module ->
                module.sourceSets.flatMap { sourceSet ->
                    compilerModulePathEntries(sourceSet.compilerOptions, module.root)
                }.distinct().forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.ENTRY))
                }
                module.sourceSets.flatMap { sourceSet ->
                    sourceSet.annotationProcessors?.let { processors ->
                        processors.processorPathEntries + processors.processorPath.map { it.path }
                    }.orEmpty()
                }.distinct().forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.ENTRY))
                }
            }
            modules.flatMap(Module::classpathEntries).forEach { path ->
                val kind = when {
                    path.isAbsolute && path.toAbsolutePath().normalize() in systemPathArtifacts ->
                        ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT
                    path.isAbsolute -> ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT
                    else -> ClasspathEvidenceKind.ENTRY
                }
                add(ClasspathEvidence.capture(normalizedRoot, path, kind))
            }
            mavenReactor?.modules?.values.orEmpty().forEach { maven ->
                (maven.mainAnnotationProcessors.processorPathEntries + maven.testAnnotationProcessors.processorPathEntries +
                    maven.mainAnnotationProcessors.processorPath.map { it.path } +
                    maven.testAnnotationProcessors.processorPath.map { it.path } +
                    compilerModulePathEntries(maven.mainCompilerOptions, maven.root) +
                    compilerModulePathEntries(maven.testCompilerOptions, maven.root))
                    .distinct().forEach { path ->
                        add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.ENTRY))
                    }
            }
            moduleRoots.forEach { moduleRoot ->
                val declaredOutputs = mavenByRoot[moduleRoot]?.let { maven ->
                    listOfNotNull(maven.mainOutputDirectory, maven.testOutputDirectory)
                } ?: conventionalOutputDirectories(moduleRoot)
                declaredOutputs.forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.ENTRY))
                }
                localJarDirectories(moduleRoot).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.JAR_DIRECTORY))
                }
                (classpathDeclarationFiles(moduleRoot) + buildDescriptorFiles(moduleRoot) + formatterConfigurationFiles(moduleRoot)).forEach { path ->
                    add(ClasspathEvidence.capture(normalizedRoot, normalizedRoot.relativize(path), ClasspathEvidenceKind.DECLARATION_FILE))
                }
            }
            val reactorPoms = mavenReactor?.reactorPomFiles.orEmpty()
            // Aggregator and parent POMs remain authoritative even without a source module.
            reactorPoms.forEach { pom ->
                add(ClasspathEvidence.capture(normalizedRoot, evidencePath(normalizedRoot, pom),
                    ClasspathEvidenceKind.DECLARATION_FILE))
            }
            mavenReactor?.modules?.values.orEmpty().flatMap { it.modelInputs }
                .filterNot { it.toAbsolutePath().normalize() in reactorPoms }.forEach { input ->
                    val path = evidencePath(normalizedRoot, input)
                    add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.EFFECTIVE_MODEL_INPUT))
                }
            mavenReactor?.modules?.values.orEmpty().flatMap { it.importedBoms }.forEach { bom ->
                val path = evidencePath(normalizedRoot, bom)
                add(ClasspathEvidence.capture(normalizedRoot, path, ClasspathEvidenceKind.IMPORTED_BOM))
            }
            mavenReactor?.modules?.values.orEmpty().flatMap { it.missingArtifactEvidence }.forEach { missing ->
                val path = evidencePath(normalizedRoot, missing.expectedPath)
                val kind = when (missing.kind) {
                    MavenMissingArtifactEvidenceKind.SYSTEM_PATH_SIDECAR ->
                        ClasspathEvidenceKind.SYSTEM_PATH_ARTIFACT
                    MavenMissingArtifactEvidenceKind.LOCAL_REPOSITORY_SELECTED_LEAF ->
                        ClasspathEvidenceKind.LOCAL_REPOSITORY_ARTIFACT
                }
                add(ClasspathEvidence.capture(normalizedRoot, path, kind))
                missing.expectedIdentityManifest?.let { manifest ->
                    val manifestPath = evidencePath(normalizedRoot, manifest)
                    add(ClasspathEvidence.capture(normalizedRoot, manifestPath, ClasspathEvidenceKind.DECLARATION_FILE))
                }
            }
        }.distinctBy { it.path.normalize() to it.kind }.sortedWith(
            compareBy<ClasspathEvidence> { it.path.toString() }.thenBy { it.kind.name },
        )

        val expectedEvidenceFiles = moduleRoots.flatMap { moduleRoot ->
            EXPECTED_EVIDENCE_MANIFEST_NAMES.map(moduleRoot::resolve).mapNotNull { evidence ->
                readExpectedEvidence(evidence)?.let { content -> evidence to content }
            }
        }.distinctBy { it.first }.sortedBy { it.first.toString() }
        val gradleKotlinScripts = listOf("settings.gradle.kts", "build.gradle.kts")
            .flatMap { name -> findBuildFiles(normalizedRoot, name) }
            .distinct().sortedBy(Path::toString)
        val baseAuxiliaryFiles = (
            (mavenReactor?.reactorPomFiles ?: pomFiles.toSet()).filter { it.startsWith(normalizedRoot) }.map { pom ->
                SourceFile(normalizedRoot.relativize(pom), pom.readText(), "maven-pom")
            } + gradleKotlinScripts.map { script ->
                // Gradle Kotlin DSL is captured as a script host input, never as Kotlin/JVM source.
                SourceFile(normalizedRoot.relativize(script), script.readText(), "kotlin")
            } + expectedEvidenceFiles.map { (evidence, content) ->
                SourceFile(normalizedRoot.relativize(evidence), content, "refactorkit-expected-evidence")
            }
        ).sortedBy { it.path.toString() }
        val safeDeleteTextScan = scanSafeDeleteTextInputs(
            normalizedRoot,
            files.mapTo(mutableSetOf()) { it.path.normalize() },
            baseAuxiliaryFiles.mapTo(mutableSetOf()) { it.path.normalize() },
            baseAuxiliaryFiles.filter { it.languageId == "maven-pom" || it.languageId == "kotlin" },
        )
        val auxiliaryFiles = (baseAuxiliaryFiles + safeDeleteTextScan.files)
            .distinctBy { it.path.normalize() }
            .sortedBy { it.path.toString() }
        val safeDeleteTextScanAttributes = mapOf(
            JAVA_SAFE_DELETE_TEXT_SCAN_VERSION_KEY to JAVA_SAFE_DELETE_TEXT_SCAN_VERSION,
            JAVA_SAFE_DELETE_TEXT_SCAN_STATUS_KEY to if (safeDeleteTextScan.complete) "complete" else "incomplete",
            JAVA_SAFE_DELETE_TEXT_SCAN_INPUTS_KEY to safeDeleteTextScan.inputCount.toString(),
            JAVA_SAFE_DELETE_TEXT_SCAN_BYTES_KEY to safeDeleteTextScan.bytes.toString(),
            JAVA_SAFE_DELETE_TEXT_SCAN_BINARY_INPUTS_KEY to safeDeleteTextScan.binaryInputCount.toString(),
            JAVA_SAFE_DELETE_TEXT_SCAN_UNMODELED_INPUTS_KEY to safeDeleteTextScan.unmodeledInputCount.toString(),
        )
        val projectBuildModels = if (includeBuildModels) {
            buildModels(modules, gradleBuildModel, mavenByRoot, normalizedRoot).map { model ->
                model.copy(attributes = model.attributes + safeDeleteTextScanAttributes)
            }
        } else {
            emptyList()
        }
        return ProjectSnapshot(
            workspace = Workspace(normalizedRoot),
            modules = modules,
            files = files,
            sourceExtensions = ProjectSnapshot.inferSourceExtensions(files),
            ignoredDirectories = ProjectSnapshot.DEFAULT_IGNORED_DIRECTORIES,
            classpathEvidence = classpathEvidence,
            buildModels = projectBuildModels,
            auxiliaryFiles = auxiliaryFiles,
        )
    }

    private fun scanSafeDeleteTextInputs(
        workspaceRoot: Path,
        sourcePaths: Set<Path>,
        alreadyCapturedPaths: Set<Path>,
        baseTextInputs: List<SourceFile>,
    ): SafeDeleteTextScan {
        val walk = runCatching {
            Files.walk(workspaceRoot).use { stream ->
                val paths = stream.filter { path ->
                    path != workspaceRoot && !isIgnoredDirectory(workspaceRoot, path)
                }.sorted().collect(Collectors.toList())
                val hasSymbolicLinks = paths.any(Files::isSymbolicLink)
                val candidates = paths.filter { path ->
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                }.filter { path ->
                    val relative = workspaceRoot.relativize(path).normalize()
                    if (relative in sourcePaths || relative in alreadyCapturedPaths) return@filter false
                    // Reserved snapshot-bound evidence is read only by its dedicated
                    // bounded verifier. Never admit a rejected manifest through the
                    // broader text scan as a generic auxiliary file.
                    if (path.fileName.toString() in EXPECTED_EVIDENCE_MANIFEST_NAMES) return@filter false
                    isResourcePath(relative) ||
                        path.fileName.toString().substringAfterLast('.', "").lowercase() in
                        SAFE_DELETE_TEXT_EXTENSIONS
                }
                val unmodeledInputCount = paths.count { path ->
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return@count false
                    val relative = workspaceRoot.relativize(path).normalize()
                    relative !in sourcePaths && relative !in alreadyCapturedPaths &&
                        path.fileName.toString() !in EXPECTED_EVIDENCE_MANIFEST_NAMES &&
                        !isResourcePath(relative) &&
                        path.fileName.toString().substringAfterLast('.', "").lowercase() !in SAFE_DELETE_TEXT_EXTENSIONS
                }
                SafeDeleteTextWalk(candidates, hasSymbolicLinks, unmodeledInputCount)
            }
        }.getOrElse {
            return SafeDeleteTextScan(
                emptyList(), complete = false, inputCount = 0, bytes = 0, binaryInputCount = 0,
                unmodeledInputCount = 0,
            )
        }

        val candidates = walk.paths
        var complete = candidates.size + baseTextInputs.size <= MAX_SAFE_DELETE_TEXT_INPUTS && !walk.hasSymbolicLinks
        var totalBytes = 0L
        var binaryInputCount = 0
        val captured = mutableListOf<SourceFile>()
        val orderedBaseInputs = baseTextInputs.sortedBy { it.path.toString() }
        val baseInputsToVerify = orderedBaseInputs.take(MAX_SAFE_DELETE_TEXT_INPUTS)
        for (input in baseInputsToVerify) {
            val path = workspaceRoot.resolve(input.path).normalize()
            val before = runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (before == null || before.size() > MAX_SAFE_DELETE_TEXT_INPUT_BYTES ||
                totalBytes + before.size() > MAX_SAFE_DELETE_TEXT_TOTAL_BYTES
            ) {
                complete = false
                continue
            }
            val read = readStableUtf8Text(path)
            if (read == null) {
                complete = false
                continue
            }
            if (read.containsNul || read.content != input.content || totalBytes + read.bytes.size > MAX_SAFE_DELETE_TEXT_TOTAL_BYTES) {
                complete = false
                continue
            }
            totalBytes += read.bytes.size
        }
        val remainingInputSlots = (MAX_SAFE_DELETE_TEXT_INPUTS - baseInputsToVerify.size).coerceAtLeast(0)
        for (path in candidates.take(remainingInputSlots)) {
            val relative = workspaceRoot.relativize(path).normalize()
            val before = runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (before == null || !before.isRegularFile || Files.isSymbolicLink(path)) {
                complete = false
                continue
            }
            if (before.size() > MAX_SAFE_DELETE_TEXT_INPUT_BYTES ||
                totalBytes + before.size() > MAX_SAFE_DELETE_TEXT_TOTAL_BYTES
            ) {
                complete = false
                continue
            }
            val read = readStableUtf8Text(path)
            if (read == null) {
                complete = false
                continue
            }
            if (totalBytes + read.bytes.size > MAX_SAFE_DELETE_TEXT_TOTAL_BYTES) {
                complete = false
                continue
            }
            totalBytes += read.bytes.size
            if (read.containsNul) {
                // Binary resources are outside the textual reference model, but their input bytes
                // still count against the scan budget. Keep their count in the snapshot so a
                // semantic refactoring cannot mistake an unparsed binary resource for closure.
                binaryInputCount++
                continue
            }
            captured += SourceFile(relative, read.content, SAFE_DELETE_TEXT_LANGUAGE_ID)
        }
        val scannedInputCount = baseInputsToVerify.size + candidates.take(remainingInputSlots).size
        return SafeDeleteTextScan(
            captured.sortedBy { it.path.toString() }, complete, scannedInputCount, totalBytes, binaryInputCount,
            walk.unmodeledInputCount,
        )
    }

    private fun readStableUtf8Text(path: Path): SafeDeleteTextRead? {
        val before = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!before.isRegularFile || Files.isSymbolicLink(path) || before.size() > MAX_SAFE_DELETE_TEXT_INPUT_BYTES) {
            return null
        }
        val bytes = runCatching {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                input.readNBytes(MAX_SAFE_DELETE_TEXT_INPUT_BYTES + 1)
            }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_SAFE_DELETE_TEXT_INPUT_BYTES) return null
        val after = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()
        ) return null
        val content = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        return SafeDeleteTextRead(bytes, content, bytes.any { it == 0.toByte() })
    }

    private fun isResourcePath(path: Path): Boolean = path.any { it.toString().equals("resources", ignoreCase = true) }

    private data class SafeDeleteTextScan(
        val files: List<SourceFile>,
        val complete: Boolean,
        val inputCount: Int,
        val bytes: Long,
        val binaryInputCount: Int,
        val unmodeledInputCount: Int,
    )

    private data class SafeDeleteTextWalk(
        val paths: List<Path>,
        val hasSymbolicLinks: Boolean,
        val unmodeledInputCount: Int,
    )

    private data class SafeDeleteTextRead(
        val bytes: ByteArray,
        val content: String,
        val containsNul: Boolean,
    )

    private fun buildModels(
        modules: List<Module>,
        gradleBuildModel: org.refactorkit.core.BuildModel?,
        mavenModules: Map<Path, MavenModuleModel>,
        workspaceRoot: Path,
    ) = modules
        .groupBy { it.languageSettings["java.buildSystem"] ?: "conventional" }
        .toSortedMap()
        .map { (buildSystem, providerModules) ->
            when (buildSystem) {
                "maven" -> MavenBuildModelProvider(localMavenRepository)
                    .project(
                        providerModules,
                        allowNetworkDependencyResolution,
                        activeMavenProfiles,
                        inactiveMavenProfiles,
                        effectiveMavenModules = mavenModules,
                        workspaceRoot = workspaceRoot,
                    )
                "gradle" -> gradleBuildModel?.let { if (it.providerId == "gradle-effective-v1") it else enrichGradleModel(it, providerModules) }
                    ?: GradleDeclarativeBuildModelProvider().project(providerModules)
                else -> ConventionalJavaBuildModelProvider().project(providerModules)
            }
        }

    private fun enrichGradleModel(
        model: org.refactorkit.core.BuildModel,
        modules: List<Module>,
    ): org.refactorkit.core.BuildModel {
        val compatibility = modules.associateBy(Module::name)
        return model.copy(modules = model.modules.map { buildModule ->
            val module = compatibility[buildModule.id] ?: return@map buildModule
            buildModule.copy(
                attributes = buildModule.attributes + module.languageSettings,
                sourceSets = buildModule.sourceSets.map { sourceSet ->
                    val testVisibility = sourceSet.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) ||
                        sourceSet.attributes["visibility"] == "test"
                    val materializedGenerated = when (sourceSet.kind) {
                        SourceSetKind.MAIN -> module.generatedSourceRoots
                        SourceSetKind.TEST -> module.generatedTestSourceRoots
                        else -> emptyList()
                    }
                    sourceSet.copy(
                        sourceRoots = (sourceSet.sourceRoots + materializedGenerated).distinct(),
                        generatedSourceRoots = (sourceSet.generatedSourceRoots + materializedGenerated).distinct(),
                        classpathEntries = if (testVisibility) module.testClasspathEntries else module.mainClasspathEntries,
                    )
                },
            )
        })
    }

    fun detectSourceRoots(root: Path): List<Path> {
        val conventionalRoots = conventionalSourceRoots(root)
        if (conventionalRoots.isNotEmpty()) return conventionalRoots
        return if (root.exists()) Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it) && (it.endsWith("src/main/java") || it.endsWith("src/test/java")) }
                .collect(Collectors.toList())
        } else emptyList()
    }

    fun detectModuleRoots(root: Path): List<Path> {
        if (!root.exists()) return emptyList()
        val scansMavenReactor = Files.isRegularFile(root.resolve("pom.xml"))
        return Files.walk(root).use { stream ->
            stream.filter { candidate ->
                val conventionalRoots = conventionalSourceRoots(candidate)
                val isEmptyOrphanedMavenHierarchy = scansMavenReactor &&
                    candidate != root &&
                    !Files.isRegularFile(candidate.resolve("pom.xml")) &&
                    conventionalRoots.none(::containsJvmSource)
                Files.isDirectory(candidate) &&
                    conventionalRoots.isNotEmpty() &&
                    !isEmptyOrphanedMavenHierarchy
            }
                .filter { !isIgnoredDirectory(root, it) }
                .collect(Collectors.toList()).distinct().sortedBy { it.toString() }
        }
    }

    private fun conventionalMainSourceRoots(root: Path): List<Path> = listOf("java", "kotlin")
        .map { language -> root.resolve("src/main/$language") }
        .filter { it.exists() && it.isDirectory() }
    private fun conventionalTestSourceRoots(root: Path): List<Path> = listOf("java", "kotlin")
        .map { language -> root.resolve("src/test/$language") }
        .filter { it.exists() && it.isDirectory() }
    private fun conventionalSourceRoots(root: Path): List<Path> = conventionalMainSourceRoots(root) + conventionalTestSourceRoots(root)

    private fun effectiveMavenSourceRoots(
        conventionalSourceDirectories: List<Path>,
        defaultJavaSourceDirectory: Path,
        primarySourceDirectory: Path?,
        additionalSourceDirectories: List<Path>,
    ): List<Path> {
        val defaultJava = defaultJavaSourceDirectory.toAbsolutePath().normalize()
        val primary = primarySourceDirectory?.toAbsolutePath()?.normalize()
        val primaryReplacesDefaultJava = primary != null && primary != defaultJava
        return buildList {
            addAll(conventionalSourceDirectories.filterNot { sourceDirectory ->
                primaryReplacesDefaultJava && sourceDirectory.toAbsolutePath().normalize() == defaultJava
            })
            if (!primaryReplacesDefaultJava) add(defaultJava)
            primary?.let(::add)
            addAll(additionalSourceDirectories)
        }.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun declaredGeneratedSourceRoot(moduleRoot: Path, sourceRoot: Path): Boolean {
        val module = moduleRoot.toAbsolutePath().normalize()
        val source = sourceRoot.toAbsolutePath().normalize()
        return source.startsWith(module.resolve("target")) ||
            source.startsWith(module.resolve("build/generated")) ||
            source.startsWith(module.resolve("build/tmp/kapt3"))
    }

    private fun generatedSourceRoots(root: Path, test: Boolean): List<Path> {
        val bases = if (test) listOf(
            root.resolve("target/generated-test-sources"),
            root.resolve("build/generated/sources/annotationProcessor/java/test"),
            root.resolve("build/generated/ksp/test/kotlin"),
            root.resolve("build/generated/source/kapt/test"),
        ) else listOf(
            root.resolve("target/generated-sources"),
            root.resolve("build/generated/sources/annotationProcessor/java/main"),
            root.resolve("build/generated/ksp/main/kotlin"),
            root.resolve("build/generated/source/kapt/main"),
        )
        return bases.filter { it.exists() && it.isDirectory() }.flatMap { base ->
            val normalized = base.toString().replace('\\', '/')
            if ("/build/generated/ksp/" in normalized || "/build/generated/source/kapt/" in normalized) {
                listOf(base).filter(::containsJvmSource)
            } else {
                val children = Files.list(base).use { stream -> stream.filter(Files::isDirectory).collect(Collectors.toList()) }
                    .filter(::containsJvmSource).sortedBy(Path::toString)
                if (children.isNotEmpty()) children else listOf(base).filter(::containsJvmSource)
            }
        }.fold(mutableListOf()) { roots, candidate ->
            if (roots.none { candidate.startsWith(it) || it.startsWith(candidate) }) roots.add(candidate)
            roots
        }
    }

    private fun containsJvmSource(root: Path): Boolean = Files.walk(root).use { stream ->
        stream.anyMatch { path ->
            Files.isRegularFile(path) && path.fileName.toString().substringAfterLast('.', "") in JVM_SOURCE_EXTENSIONS
        }
    }

    private fun conventionalOutputDirectories(root: Path): List<Path> = listOf(
        root.resolve("target/classes"), root.resolve("target/test-classes"),
        root.resolve("build/classes/java/main"), root.resolve("build/classes/java/test"),
        root.resolve("build/classes/kotlin/main"), root.resolve("build/classes/kotlin/test"),
    )
    private fun localJarDirectories(root: Path): List<Path> = listOf(root.resolve("lib"), root.resolve("libs"))
    private fun buildDescriptorFiles(root: Path): List<Path> = listOf(root.resolve("pom.xml"), root.resolve("build.gradle"), root.resolve("build.gradle.kts"))
    private fun formatterConfigurationFiles(root: Path): List<Path> = listOf(
        root.resolve(".settings/org.eclipse.jdt.core.prefs"),
        root.resolve(".editorconfig"),
        root.resolve("gradle.properties"),
    ).filter { it.exists() && Files.isRegularFile(it) }

    private fun findBuildFiles(root: Path, name: String): List<Path> = Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString() == name && !isIgnoredDirectory(root, it) }
            .collect(Collectors.toList()).sortedBy(Path::toString)
    }

    private fun evidencePath(workspaceRoot: Path, path: Path): Path =
        if (path.startsWith(workspaceRoot)) workspaceRoot.relativize(path) else path.toAbsolutePath().normalize()

    private fun readExpectedEvidence(path: Path): String? {
        val before = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!before.isRegularFile || Files.isSymbolicLink(path) || before.size() > MAX_EXPECTED_EVIDENCE_BYTES) {
            return null
        }
        val bytes = runCatching {
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                input.readNBytes(MAX_EXPECTED_EVIDENCE_BYTES + 1)
            }
        }.getOrNull() ?: return null
        if (bytes.size > MAX_EXPECTED_EVIDENCE_BYTES) return null
        val after = runCatching {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull() ?: return null
        if (!after.isRegularFile || Files.isSymbolicLink(path) ||
            before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() ||
            before.fileKey() != after.fileKey() || bytes.size.toLong() != after.size()
        ) return null
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun mavenArtifactId(root: Path): String? {
        val pom = root.resolve("pom.xml").takeIf { it.exists() && Files.isRegularFile(it) } ?: return null
        val text = runCatching { pom.readText() }.getOrDefault("")
        val withoutParent = text.replace(Regex("(?s)<parent>.*?</parent>"), "")
        return Regex("<artifactId>\\s*([^<]+)\\s*</artifactId>").find(withoutParent)?.groupValues?.get(1)?.trim()
    }

    private fun detectModuleDependencies(root: Path, identities: Map<String, String>): List<String> {
        val dependencies = linkedSetOf<String>()
        val pom = root.resolve("pom.xml")
        if (pom.exists() && Files.isRegularFile(pom)) {
            Regex("(?s)<dependency>.*?<artifactId>\\s*([^<]+)\\s*</artifactId>.*?</dependency>")
                .findAll(runCatching { pom.readText() }.getOrDefault(""))
                .map { it.groupValues[1].trim() }.mapNotNull(identities::get).forEach(dependencies::add)
        }
        listOf(root.resolve("build.gradle"), root.resolve("build.gradle.kts"))
            .filter { it.exists() && Files.isRegularFile(it) }.forEach { buildFile ->
                Regex("project\\(\\s*['\"]:([^'\"]+)['\"]\\s*\\)").findAll(runCatching { buildFile.readText() }.getOrDefault(""))
                    .map { it.groupValues[1] }.mapNotNull(identities::get).forEach(dependencies::add)
            }
        mavenArtifactId(root)?.let(identities::get)?.let(dependencies::remove)
        return dependencies.sorted()
    }

    private fun detectJavaSourceLevel(root: Path): Int {
        val descriptors = buildDescriptorFiles(root).filter { it.exists() && Files.isRegularFile(it) }
        val candidates = descriptors.flatMap { descriptor ->
            val text = runCatching { descriptor.readText() }.getOrDefault("")
            when (descriptor.fileName.toString()) {
                "pom.xml" -> listOf(
                    Regex("<maven\\.compiler\\.release>\\s*([^<]+)\\s*</maven\\.compiler\\.release>"),
                    Regex("<maven\\.compiler\\.source>\\s*([^<]+)\\s*</maven\\.compiler\\.source>"),
                    Regex("<release>\\s*([^<]+)\\s*</release>"), Regex("<source>\\s*([^<]+)\\s*</source>"),
                ).mapNotNull { it.find(text)?.groupValues?.get(1)?.resolveMavenValue(text)?.toJavaLevelOrNull() }
                else -> listOf(
                    Regex("JavaLanguageVersion\\.of\\(\\s*(\\d+)\\s*\\)"), Regex("JavaVersion\\.VERSION_(\\d+)"),
                    Regex("(?:sourceCompatibility|targetCompatibility|options\\.release(?:\\.set)?)\\D{0,20}(\\d+)"),
                ).mapNotNull { it.find(text)?.groupValues?.get(1)?.toJavaLevelOrNull() }
            }
        }
        return candidates.firstOrNull() ?: 8
    }

    private fun String.resolveMavenValue(pom: String): String {
        val token = trim()
        val property = Regex("^\\$\\{([^}]+)}$").matchEntire(token)?.groupValues?.get(1) ?: return token
        return Regex("<$property>\\s*([^<]+)\\s*</$property>").find(pom)?.groupValues?.get(1)?.trim() ?: token
    }
    private fun String.toJavaLevelOrNull(): Int? = trim().removePrefix("1.").toIntOrNull()

    private fun classpathDeclarationFiles(root: Path): List<Path> = listOf(
        root.resolve(".refactorkit/classpath"), root.resolve("target/classpath.txt"), root.resolve("build/classpath.txt"),
    )
    private fun localJarEntries(root: Path): List<Path> = localJarDirectories(root).filter { it.exists() && it.isDirectory() }.flatMap { directory ->
        Files.walk(directory).use { stream -> stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.collect(Collectors.toList()) }
    }
    private fun declaredClasspathEntries(root: Path): List<Path> = classpathDeclarationFiles(root)
        .filter { it.exists() && Files.isRegularFile(it) }.flatMap { classpathFile ->
            runCatching {
                classpathFile.readText().lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }
                    .flatMap { it.split(File.pathSeparatorChar).asSequence() }.map(String::trim).filter(String::isNotEmpty)
                    .map { Path.of(it).let { path -> if (path.isAbsolute) path.normalize() else root.resolve(path).normalize() } }
                    .filter { it.exists() && (it.isDirectory() || it.fileName.toString().endsWith(".jar")) }.toList()
            }.getOrDefault(emptyList())
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun moduleName(workspaceRoot: Path, moduleRoot: Path): String {
        val relative = workspaceRoot.relativize(moduleRoot)
        return if (relative.toString().isBlank()) workspaceRoot.fileName?.toString() ?: "root"
        else relative.toString().replace('\\', ':').replace('/', ':')
    }

    private fun isIgnoredDirectory(workspaceRoot: Path, path: Path): Boolean {
        val relative = workspaceRoot.relativize(path).toString().replace('\\', '/')
        return relative.split('/').any { component ->
            component in setOf(".gradle", ".git", ".refactorkit", "build", "target")
        }
    }

    private fun compilerModulePathEntries(options: List<org.refactorkit.core.BuildCompilerOption>, moduleRoot: Path): List<Path> {
        val values = options.filter { it.name == "compilerArg" }.map(org.refactorkit.core.BuildCompilerOption::value)
        val raw = buildList {
            var index = 0
            while (index < values.size) {
                val value = values[index]
                when {
                    value in setOf("--module-path", "-p") -> {
                        values.getOrNull(index + 1)?.let(::add)
                        index++
                    }
                    value.startsWith("--module-path=") -> add(value.substringAfter('='))
                    value.startsWith("-p=") -> add(value.substringAfter('='))
                }
                index++
            }
            options.filter { it.name == "compilerArgument" }.forEach { option ->
                Regex("(?:--module-path|-p)\\s*(?:=|\\s)\\s*([^\\s]+)").find(option.value)?.groupValues?.getOrNull(1)?.let(::add)
            }
        }
        return raw.flatMap { value -> value.split(java.io.File.pathSeparatorChar) }
            .mapNotNull { value -> runCatching { Path.of(value) }.getOrNull() }
            .map { path -> (if (path.isAbsolute) path else moduleRoot.resolve(path)).toAbsolutePath().normalize() }
            .distinct()
    }

    companion object {
        private val JVM_LANGUAGE_IDS = mapOf("java" to "java", "kt" to "kotlin", "kts" to "kotlin")
        private val JVM_SOURCE_EXTENSIONS = JVM_LANGUAGE_IDS.keys
        private val EXPECTED_EVIDENCE_MANIFEST_NAMES = listOf(
            ".refactorkit-expected-source-inventory.properties",
            ".refactorkit-generated-root-inventory.properties",
        )
        private const val MAX_EXPECTED_EVIDENCE_BYTES = 16 * 1024
        private const val MAX_SAFE_DELETE_TEXT_INPUTS = 8_192
        private const val MAX_SAFE_DELETE_TEXT_INPUT_BYTES = 1_048_576
        private const val MAX_SAFE_DELETE_TEXT_TOTAL_BYTES = 33_554_432L
        private val SAFE_DELETE_TEXT_EXTENSIONS = setOf(
            "cfg", "conf", "config", "cql", "ddl", "dml", "gradle", "groovy", "graphql",
            "gql", "hql", "htm", "html", "ini", "java", "jpql", "js", "json", "kt", "kts",
            "properties", "sql", "toml", "txt", "xml", "yaml", "yml",
        )
    }
}

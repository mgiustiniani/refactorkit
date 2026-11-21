package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildAnnotationProcessorConfiguration
import org.refactorkit.core.BuildCompilerOption
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildResolvedArtifact
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.BuildSourceRootEvidence
import org.refactorkit.core.BuildSourceRootOwnership
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.SemanticCancellationToken
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.buildSourceRootOwnerships
import java.nio.file.Path
import java.security.MessageDigest

internal sealed interface JavaProjectSourceLevelDecision {
    data class Supported(val sourceLevel: Int) : JavaProjectSourceLevelDecision
    data class Refused(val code: String, val message: String) : JavaProjectSourceLevelDecision
}

/** Refuses unsupported declared levels instead of silently clamping them to a different language. */
internal object JavaProjectSourceLevelPolicy {
    const val MIN_SUPPORTED = 8

    val MAX_SUPPORTED: Int get() = JavaParserReleaseSupport.maximumSupportedRelease
    val DEFAULT_SOURCE_LEVEL: Int get() = minOf(25, MAX_SUPPORTED)

    fun resolve(release: String?, sourceLevel: String?): JavaProjectSourceLevelDecision {
        val declared = release ?: sourceLevel ?: return JavaProjectSourceLevelDecision.Supported(DEFAULT_SOURCE_LEVEL)
        val value = declared.removePrefix("1.").toIntOrNull()
            ?: return JavaProjectSourceLevelDecision.Refused(
                "java.context.sourceLevelUnavailable",
                "The declared Java release or source level '$declared' could not be resolved",
            )
        if (value !in MIN_SUPPORTED..MAX_SUPPORTED || !JavaParserReleaseSupport.supportsRelease(value)) {
            val fact = if (release != null) "release" else "source level"
            return JavaProjectSourceLevelDecision.Refused(
                "java.context.sourceLevelUnsupported",
                "Declared Java $fact '$declared' is outside the installed JDT parser/compiler range " +
                    "$MIN_SUPPORTED-$MAX_SUPPORTED",
            )
        }
        return JavaProjectSourceLevelDecision.Supported(value)
    }
}

/**
 * Current-GA build metadata is a separate contract from parser qualification.
 * This bound comes from REQ-JAVA-PROJECT-CONTEXT-001, not the installed JDK.
 */
internal object JavaProjectBuildMetadataSourceLevelPolicy {
    const val MIN_SUPPORTED = JavaProjectSourceLevelPolicy.MIN_SUPPORTED
    const val FIRST_UNQUALIFIED_GA_RELEASE = 26
    const val MAX_SUPPORTED = 27

    fun supportsRelease(release: Int): Boolean = release in MIN_SUPPORTED..MAX_SUPPORTED

    fun resolve(release: String?, sourceLevel: String?): JavaProjectSourceLevelDecision {
        val declared = release ?: sourceLevel ?: return JavaProjectSourceLevelDecision.Supported(
            JavaProjectSourceLevelPolicy.DEFAULT_SOURCE_LEVEL,
        )
        val value = declared.removePrefix("1.").toIntOrNull()
            ?: return JavaProjectSourceLevelDecision.Refused(
                "java.context.sourceLevelUnavailable",
                "The declared Java release or source level '$declared' could not be resolved",
            )
        if (!supportsRelease(value)) {
            val fact = if (release != null) "release" else "source level"
            return JavaProjectSourceLevelDecision.Refused(
                "java.context.sourceLevelUnsupported",
                "Declared Java $fact '$declared' is outside the installed compiler metadata range " +
                    "$MIN_SUPPORTED-$MAX_SUPPORTED",
            )
        }
        return JavaProjectSourceLevelDecision.Supported(value)
    }
}

/** Schema-specific semantic admission; compiler support never qualifies an arbitrary future release. */
internal object JavaProjectContextSemanticReleasePolicy {
    fun supportsApiProfile(release: Int, schema: String): Boolean =
        schema in (2..7).map { "java-project-context-v$it" } &&
            (release in 8..25 || release == 27 || release == 26 &&
                schema in setOf("java-project-context-v6", "java-project-context-v7"))

    fun requiresVersionedSourceContext(sourceLevel: Int, schema: String): Boolean =
        sourceLevel > 25 && schema !in setOf("java-project-context-v6", "java-project-context-v7")

    fun requiresMetadataOnly(release: Int, schema: String, parserSupportsRelease: Boolean,
        sourceAndSymbolSelected: Boolean): Boolean {
        if (schema !in setOf("java-project-context-v6", "java-project-context-v7") ||
            release < JavaProjectBuildMetadataSourceLevelPolicy.FIRST_UNQUALIFIED_GA_RELEASE) return false
        if (schema == "java-project-context-v7" && release == 26 && parserSupportsRelease) return false
        // Preserve the separately bounded, explicitly selected Java 27 parser probe.
        // General Java 27 queries remain metadata-only even on a newer backend.
        return !(schema == "java-project-context-v7" && release == 27 &&
            sourceAndSymbolSelected && parserSupportsRelease)
    }
}

/** Java queries retain the global snapshot while projecting their own provider family. */
internal fun javaContextBuildModels(snapshot: ProjectSnapshot): List<BuildModel> = snapshot.buildModels.filter { model ->
    model.providerId in setOf("maven-effective-v1", "gradle-effective-v1") || model.providerId.startsWith("java-") ||
        model.modules.any { module -> module.sourceSets.any { set ->
            set.attributes.keys.any { it.startsWith("java.") } || set.languageFacets.any { it.languageId == "java" }
        } }
}

/** Apply the shared longest-root rule after selecting the owning language family. */
internal fun ProjectSnapshot.owningJavaBuildSourceRoots(path: Path): List<BuildSourceRootOwnership> {
    val normalized = path.normalize()
    if (normalized.isAbsolute || normalized.startsWith("..")) return emptyList()
    val providers = javaContextBuildModels(this).map { it.providerId }.toSet()
    val candidates = buildSourceRootOwnerships().filter { owner -> owner.providerId in providers &&
        (owner.root.toString().isEmpty() || normalized.startsWith(owner.root)) }
    fun depth(owner: BuildSourceRootOwnership) = if (owner.root.toString().isEmpty()) 0 else owner.root.nameCount
    val longest = candidates.maxOfOrNull(::depth) ?: return emptyList()
    return candidates.filter { depth(it) == longest }
}

/** Selects the attested API input independently of parser/bytecode compliance. */
internal object JavaSourceSetPlatformContext {
    fun acquire(set: BuildSourceSet, home: Path, metadataOnly: Boolean): JavaReleasePlatformResolution =
        Acquisition(home).acquire(set, metadataOnly)

    class Acquisition(private val home: Path) {
        private val resolver = JavaReleasePlatformAuthorityResolver()
        private val evidence = linkedMapOf<Pair<Int, Boolean>, JavaReleasePlatformResolution>()
        private val nativeEvidence = linkedMapOf<Int, JavaReleasePlatformResolution>()
        private fun resolve(release: Int, metadataOnly: Boolean): JavaReleasePlatformResolution =
            evidence.getOrPut(release to metadataOnly) {
                if (metadataOnly) resolver.resolveMetadata(home, release) else resolver.resolve(home, release)
            }

        fun acquire(set: BuildSourceSet, metadataOnly: Boolean): JavaReleasePlatformResolution {
            val source = when (val decision = JavaProjectBuildMetadataSourceLevelPolicy.resolve(
                set.attributes["java.release"], set.attributes["java.sourceLevel"],
            )) {
                is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
                is JavaProjectSourceLevelDecision.Refused -> return JavaReleasePlatformResolution.Refused(decision.code, decision.message)
            }
            val seed = resolve(source, metadataOnly)
            if (seed !is JavaReleasePlatformResolution.Available || set.attributes["java.release"] != null || metadataOnly) return seed
            // Incomplete legacy metadata is retained as metadata, never promoted to native API authority.
            if (set.compilerOptionsAvailability != BuildFactAvailability.COMPLETE) return seed
            val target = try { requireSourceTarget(set) } catch (failure: JavaContextAuthorityException) {
                return JavaReleasePlatformResolution.Refused(failure.code, failure.message.orEmpty())
            }
            if (target > seed.authority.platformRelease) return JavaReleasePlatformResolution.Refused(
                "java.platform.compilerApiUnqualified", "The selected compiler SDK cannot emit the declared bytecode target",
            )
            // A selected Java 8 JDK attests its own pre-modular API signatures.
            if (seed.authority.legacyBootArchives.isNotEmpty()) return seed
            if (!JavaReleasePlatformEligibilityPolicy.isQualifiedNativeApi(seed.authority.platformRelease)) return JavaReleasePlatformResolution.Refused(
                "java.platform.compilerApiUnqualified", "Native compiler API profiles are qualified separately; this SDK profile is not qualified",
            )
            return nativeEvidence.getOrPut(seed.authority.platformRelease) {
                resolver.resolveNativeCompilerApis(home, seed.authority.platformRelease)
            }
        }
    }

    fun profile(set: BuildSourceSet, authorities: Map<Int, JavaReleasePlatformAuthority>): Int {
        set.attributes["java.release"]?.let { return requireSupportedJavaProjectSourceLevel(it, null) }
        if (authorities.isEmpty()) throw JavaContextAuthorityException(
            "java.platform.explicitJdkRequired", "Source/target compilation requires an explicit qualified JDK platform",
        )
        val target = requireSourceTarget(set)
        val profile = authorities.values.singleOrNull { it.release == it.platformRelease }?.release
            ?: throw JavaContextAuthorityException("java.platform.explicitJdkRequired",
                "Source/target compilation requires the explicitly attested compiler system modules, not historical signatures")
        val legacy = authorities[profile]?.legacyBootArchives?.isNotEmpty() == true
        if ((!legacy && !JavaReleasePlatformEligibilityPolicy.isQualifiedNativeApi(profile)) || target > profile) throw JavaContextAuthorityException(
            "java.platform.compilerApiUnqualified", "The native compiler API/bytecode profile is unqualified",
        )
        return profile
    }

    private fun requireSourceTarget(set: BuildSourceSet): Int {
        fun refuse(detail: String): Nothing = throw JavaContextAuthorityException(
            "java.platform.compilerApiUnqualified", detail,
        )
        if (set.compilerOptionsAvailability != BuildFactAvailability.COMPLETE) throw JavaContextAuthorityException(
            set.compilerOptionsRefusalCode ?: "java.context.compilerOptionsUnavailable",
            "Source/target compilation requires complete effective compiler options for source set ${set.id}",
        )
        val options = set.compilerOptions
        if (options.any { it.redacted || it.name in setOf("release", "compilerArgFile", "compilerArgs", "compilerArgument", "compilerArguments", "bootclasspath", "system", "enablePreview", "executable") ||
                it.name == "compilerId" && it.value != "javac" || it.name == "fork" && it.value != "false" }) {
            refuse("Compiler options do not prove the unmodified SDK system API profile")
        }
        val source = set.attributes["java.sourceLevel"]?.removePrefix("1.")?.toIntOrNull()
            ?: refuse("The effective source level is unavailable")
        val sourceOption = options.singleOrNull { it.name == "source" }?.value?.removePrefix("1.")?.toIntOrNull()
        val target = (options.singleOrNull { it.name == "target" }?.value ?: set.attributes["java.targetLevel"])
            ?.removePrefix("1.")?.toIntOrNull() ?: refuse("The effective bytecode target is unavailable")
        if (sourceOption == null && set.attributes["java.compilerHome"] == null ||
            sourceOption != null && sourceOption != source || target < source || !JavaParserReleaseSupport.supportsRelease(target)) {
            refuse("Source and target compiler facts are inconsistent or unqualified")
        }
        if (options.any { it.name == "compilerArg" && !JavaSdkCompilerArguments.isApiNeutral(it.value) }) {
            refuse("Custom compiler arguments may change the API environment and require separate qualification")
        }
        return target
    }
}

/** Caller SDK authority is distinct from the launcher's legacy release fallback. */
data class JavaDiagnosticsPlatformSelection(val projectSdkHome: Path? = null, val releaseFallbackHome: Path? = null)

internal data class JavaSourceSetDiagnosticsFailure(val moduleId: String, val sourceSetId: String, val code: String, val message: String)
internal data class JavaDiagnosticsPlatformEvaluation(
    val analysis: JdtJavaSemanticAnalysisResult?, val failures: List<JavaSourceSetDiagnosticsFailure>,
)

/** Application-owned authority acquisition; transport and language ports only delegate. */
internal object JavaDiagnosticsPlatformContext {
    fun evaluate(project: ProjectSnapshot, selection: JavaDiagnosticsPlatformSelection,
        analyze: (Map<Int, JavaReleasePlatformAuthority>) -> JdtAuthoritativeJavaAnalysisResolution,
    ): JavaDiagnosticsPlatformEvaluation {
        val owners = linkedMapOf<Triple<String, String, String>, BuildSourceRootOwnership>()
        val failures = mutableListOf<JavaSourceSetDiagnosticsFailure>()
        for (file in project.files.filter { it.languageId == "java" }) {
            val owner = project.owningJavaBuildSourceRoots(file.path).singleOrNull()
            if (owner == null) failures += JavaSourceSetDiagnosticsFailure("<workspace>", "<unknown>",
                "java.diagnostics.sourceSetUnavailable", "Java source has no unique build source-set owner")
            else owners[Triple(owner.providerId, owner.module.id, owner.sourceSet.id)] = owner
        }
        val authorities = linkedMapOf<Int, JavaReleasePlatformAuthority>()
        val acquisitions = linkedMapOf<Path, JavaSourceSetPlatformContext.Acquisition>()
        fun fail(owner: BuildSourceRootOwnership, code: String, message: String) {
            failures += JavaSourceSetDiagnosticsFailure(owner.module.id, owner.sourceSet.id, code, message)
        }
        for (owner in owners.values) {
            val set = owner.sourceSet
            val release = set.attributes["java.release"]
            if (release == null && set.attributes["java.sourceLevel.refusalCode"] != null) {
                fail(owner, set.attributes.getValue("java.sourceLevel.refusalCode"),
                    set.attributes["java.sourceLevel.message"] ?: "The declared source level is unavailable")
                continue
            }
            val home = selection.projectSdkHome ?: selection.releaseFallbackHome.takeIf { release != null }
            if (home == null) {
                fail(owner, if (release == null) "java.platform.explicitJdkRequired" else "java.platform.authorityUnavailable",
                    if (release == null) "Source/target compilation requires an explicitly selected project SDK"
                    else "No explicit Java $release platform authority was configured")
                continue
            }
            val configuredHome = set.attributes["java.compilerHome"]
            if (configuredHome != null) {
                val matches = try { Path.of(configuredHome).toRealPath() == home.toRealPath() } catch (_: Exception) { false }
                if (!matches) { fail(owner, "java.context.sdkMismatch", "Selected SDK differs from the effective compiler SDK"); continue }
            }
            when (val captured = acquisitions.getOrPut(home.toAbsolutePath().normalize()) {
                JavaSourceSetPlatformContext.Acquisition(home)
            }.acquire(set, false)) {
                is JavaReleasePlatformResolution.Available -> authorities[captured.authority.release] = captured.authority
                is JavaReleasePlatformResolution.Refused -> fail(owner, captured.code, captured.message)
            }
        }
        if (failures.isEmpty()) for (owner in owners.values) {
            try { JavaSourceSetPlatformContext.profile(owner.sourceSet, authorities) }
            catch (failure: JavaContextAuthorityException) { fail(owner, failure.code, failure.message.orEmpty()) }
        }
        if (failures.isNotEmpty()) return JavaDiagnosticsPlatformEvaluation(null, failures.distinct())
        return when (val evaluated = analyze(authorities)) {
            is JdtAuthoritativeJavaAnalysisResolution.Available -> JavaDiagnosticsPlatformEvaluation(evaluated.analysis, emptyList())
            is JdtAuthoritativeJavaAnalysisResolution.Refused -> JavaDiagnosticsPlatformEvaluation(null,
                owners.values.map { JavaSourceSetDiagnosticsFailure(it.module.id, it.sourceSet.id, evaluated.code, evaluated.message) })
        }
    }
}

internal fun requireSupportedJavaProjectSourceLevel(release: String?, sourceLevel: String?): Int =
    when (val decision = JavaProjectSourceLevelPolicy.resolve(release, sourceLevel)) {
        is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
        is JavaProjectSourceLevelDecision.Refused -> throw JavaContextAuthorityException(decision.code, decision.message)
    }

data class JavaContextClasspathEntry(
    val path: Path,
    val moduleId: String,
    val sourceSetId: String,
    val dependencyScope: DependencyScope?,
    val artifact: BuildResolvedArtifact? = null,
)

/** The very same ordered source-set environment used by the JDT analyzer. */
data class JavaBuildSourceSetContext(
    val testSource: Boolean,
    val sourceRoots: List<Path>,
    val compileClasspath: List<JavaContextClasspathEntry>,
    val runtimeClasspath: List<JavaContextClasspathEntry>,
    val sourceLevel: Int,
    val compilerOptions: List<BuildCompilerOption> = emptyList(),
    val compilerOptionsAvailability: BuildFactAvailability = BuildFactAvailability.UNAVAILABLE,
    val annotationProcessors: BuildAnnotationProcessorConfiguration? = null,
    val jpms: JavaJpmsSourceSetContext = JavaJpmsSourceSetContext.NOT_APPLICABLE,
) {
    val classpathEntries: List<Path> get() = compileClasspath.map { it.path }
}

private fun BuildModule.matchesContextSelector(selector: String): Boolean =
    id == selector || attributes["gradle.projectPath"] == selector

object JavaBuildSourceSetContextResolver {
    fun resolve(
        model: BuildModel,
        owner: BuildModule,
        selected: BuildSourceSet,
        allowMetadataOnlySourceLevel: Boolean = false,
    ): JavaBuildSourceSetContext {
        if (model.providerId == "gradle-effective-v1") {
            return resolvedGradleContext(model, owner, selected, allowMetadataOnlySourceLevel)
        }
        if (model.providerId == DeclaredJavaProjectProvider.ID) {
            return resolvedDeclaredContext(model, owner, selected, allowMetadataOnlySourceLevel)
        }
        val test = selected.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) ||
            selected.attributes["visibility"] == "test"
        val byId = model.modules.associateBy { it.id }
        fun mainSourceSets(module: BuildModule): List<BuildSourceSet> = module.sourceSets.filter { sourceSet ->
            sourceSet.kind == SourceSetKind.MAIN || sourceSet.attributes["visibility"] == "main"
        }
        fun testSourceSets(module: BuildModule): List<BuildSourceSet> = module.sourceSets.filter { sourceSet ->
            sourceSet.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST) ||
                sourceSet.attributes["visibility"] == "test"
        }
        data class Pending(val moduleId: String, val scope: DependencyScope)
        fun visible(runtime: Boolean): List<Pair<BuildModule, DependencyScope>> {
            val found = linkedMapOf<String, Pair<BuildModule, DependencyScope>>()
            val pending = ArrayDeque(selected.moduleDependencies.map { Pending(it.targetModuleId, it.scope) })
            while (pending.isNotEmpty()) {
                val next = pending.removeFirst()
                val admitted = if (test) next.scope != DependencyScope.CUSTOM else if (runtime) {
                    next.scope in setOf(DependencyScope.COMPILE, DependencyScope.RUNTIME)
                } else next.scope in setOf(DependencyScope.COMPILE, DependencyScope.PROVIDED, DependencyScope.SYSTEM)
                if (!admitted || next.moduleId == owner.id || next.moduleId in found) continue
                val module = byId[next.moduleId] ?: continue
                found[module.id] = module to next.scope
                module.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.moduleDependencies.orEmpty()
                    .forEach { child -> transitiveScope(next.scope, child.scope)?.let {
                        pending.add(Pending(child.targetModuleId, it))
                    } }
            }
            return found.values.toList()
        }
        val compileVisible = visible(runtime = false)
        val ownerMainSets = mainSourceSets(owner)
        val ownerTestSets = testSourceSets(owner)
        val localCompileSets = buildList {
            addAll(ownerMainSets)
            if (test) addAll(ownerTestSets)
            if (selected !in ownerMainSets && selected !in ownerTestSets) add(selected)
        }
        val roots = buildList {
            localCompileSets.forEach { addAll(it.sourceRoots) }
            compileVisible.forEach { (module, _) ->
                mainSourceSets(module).forEach { addAll(it.sourceRoots) }
            }
        }.distinct()
        fun entries(runtime: Boolean): List<JavaContextClasspathEntry> = buildList {
            val own = if (runtime) selected.runtimeClasspathEntries else selected.classpathEntries
            addAll(own.map { path -> JavaContextClasspathEntry(
                path, owner.id, selected.id, null,
                selected.resolvedArtifacts.firstOrNull { it.path == path },
            ) })
            (if (runtime) visible(runtime = true) else compileVisible).forEach { (module, scope) ->
                module.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.let { dependencyMain ->
                    val useRuntime = runtime || test || scope in setOf(
                        DependencyScope.PROVIDED, DependencyScope.RUNTIME, DependencyScope.TEST,
                    )
                    val paths = if (useRuntime) dependencyMain.runtimeClasspathEntries else dependencyMain.classpathEntries
                    addAll(paths.map { path -> JavaContextClasspathEntry(
                        path, module.id, dependencyMain.id, scope,
                        dependencyMain.resolvedArtifacts.firstOrNull { it.path == path },
                    ) })
                }
            }
        }.distinctBy { it.path }
        return JavaBuildSourceSetContext(test, roots, entries(false), entries(true),
            contextSourceLevel(
                selected.attributes["java.release"], selected.attributes["java.sourceLevel"],
                allowMetadataOnlySourceLevel,
            ),
            selected.compilerOptions, selected.compilerOptionsAvailability, selected.annotationProcessors)
    }

    private fun resolvedDeclaredContext(
        model: BuildModel,
        owner: BuildModule,
        selected: BuildSourceSet,
        allowMetadataOnlySourceLevel: Boolean,
    ): JavaBuildSourceSetContext {
        val workspace = Path.of(model.attributes.getValue("java.declared.workspaceRoot"))
        fun absolute(path: Path): Path = workspace.resolve(path).normalize()
        val outputs = model.modules.flatMap { module -> module.sourceSets.flatMap { set ->
            set.outputDirectories.map { absolute(it) to (module to set) }
        } }.toMap()
        val compileOwners = selected.classpathEntries.mapNotNull { outputs[absolute(it)] }.distinct()
        val roots = (selected.sourceRoots + compileOwners.flatMap { it.second.sourceRoots }).distinct()
        fun entries(paths: List<Path>) = paths.map { path ->
            val outputOwner = outputs[absolute(path)]
            JavaContextClasspathEntry(path, outputOwner?.first?.id ?: owner.id, outputOwner?.second?.id ?: selected.id, null)
        }
        return JavaBuildSourceSetContext(selected.kind in setOf(SourceSetKind.TEST, SourceSetKind.INTEGRATION_TEST),
            roots, entries(selected.classpathEntries), entries(selected.runtimeClasspathEntries),
            contextSourceLevel(selected.attributes["java.release"], selected.attributes["java.sourceLevel"], allowMetadataOnlySourceLevel),
            selected.compilerOptions, selected.compilerOptionsAvailability, selected.annotationProcessors)
    }

    private fun resolvedGradleContext(
        model: BuildModel,
        owner: BuildModule,
        selected: BuildSourceSet,
        allowMetadataOnlySourceLevel: Boolean,
    ): JavaBuildSourceSetContext {
        // Gradle exports complete variant-resolved closures; Maven scope traversal must not expand them.
        val workspace = Path.of(model.attributes.getValue("gradle.workspaceRoot"))
        fun absolute(path: Path) = workspace.resolve(path).normalize()
        val moduleById = model.modules.associateBy { it.id }
        val outputDirectories = buildMap<Path, BuildModule> {
            model.modules.forEach { module ->
                module.sourceSets.forEach { set -> set.outputDirectories.forEach { put(absolute(it), module) } }
            }
        }
        val outputs = buildMap {
            putAll(outputDirectories)
            model.modules.forEach { module ->
                module.attributes["gradle.mainArtifact"]?.takeIf(String::isNotBlank)?.let { put(absolute(Path.of(it)), module) }
            }
        }
        val compileOwners = selected.classpathEntries.mapNotNull { outputs[absolute(it)] }.distinctBy { it.id }
        // JDT filename lookup stops at the first matching source root. A test file
        // may declare only secondary types while sharing a visible main filename.
        // Put own main sources first only when Gradle's actual closure admits them.
        val ownMainRoots = compileOwners.firstOrNull { it.id == owner.id }?.sourceSets
            ?.firstOrNull { it.kind == SourceSetKind.MAIN }?.sourceRoots.orEmpty()
        val roots = (ownMainRoots + selected.sourceRoots + compileOwners.flatMap { module ->
            module.sourceSets.firstOrNull { it.kind == SourceSetKind.MAIN }?.sourceRoots.orEmpty()
        }).distinct()
        fun entries(paths: List<Path>, runtime: Boolean) = paths.map { path ->
            val dependency = if (runtime) {
                val resolvedOwner = selected.attributes[GRADLE_RUNTIME_ARTIFACT_OWNER_PREFIX + path]?.let(moduleById::get)
                resolvedOwner ?: outputDirectories[absolute(path)]
            } else {
                outputs[absolute(path)]
            }
            val scope = selected.moduleDependencies.firstOrNull { it.targetModuleId == dependency?.id }?.scope
            val dependencySet = dependency?.sourceSets?.firstOrNull { path in it.outputDirectories }
                ?: dependency?.takeIf { module ->
                    // Gradle runtime classpaths may expose a project dependency as its main JAR,
                    // which is owned by the main source set but is not one of its class directories.
                    module.attributes["gradle.mainArtifact"]?.takeIf(String::isNotBlank)?.let { artifact ->
                        absolute(Path.of(artifact)) == absolute(path)
                    } == true
                }?.sourceSets?.firstOrNull { it.kind == SourceSetKind.MAIN }
            val entryOwner = dependency ?: owner
            val entrySet = dependencySet ?: selected
            JavaContextClasspathEntry(path, entryOwner.id, entrySet.id, scope,
                entrySet.resolvedArtifacts.firstOrNull { it.path == path })
        }
        return JavaBuildSourceSetContext(selected.kind != SourceSetKind.MAIN, roots,
            entries(selected.classpathEntries, runtime = false), entries(selected.runtimeClasspathEntries, runtime = true),
            contextSourceLevel(
                selected.attributes["java.release"], selected.attributes["java.sourceLevel"],
                allowMetadataOnlySourceLevel,
            ),
            selected.compilerOptions, selected.compilerOptionsAvailability, selected.annotationProcessors)
    }

    private fun transitiveScope(parent: DependencyScope, child: DependencyScope): DependencyScope? = when (parent) {
        DependencyScope.COMPILE -> when (child) {
            DependencyScope.COMPILE -> DependencyScope.COMPILE
            DependencyScope.RUNTIME -> DependencyScope.RUNTIME
            else -> null
        }
        DependencyScope.PROVIDED, DependencyScope.RUNTIME, DependencyScope.TEST ->
            parent.takeIf { child in setOf(DependencyScope.COMPILE, DependencyScope.RUNTIME) }
        else -> null
    }
}

private fun contextSourceLevel(
    release: String?,
    sourceLevel: String?,
    allowMetadataOnlySourceLevel: Boolean,
): Int = when (val decision = if (allowMetadataOnlySourceLevel) {
    JavaProjectBuildMetadataSourceLevelPolicy.resolve(release, sourceLevel)
} else {
    JavaProjectSourceLevelPolicy.resolve(release, sourceLevel)
}) {
    is JavaProjectSourceLevelDecision.Supported -> decision.sourceLevel
    is JavaProjectSourceLevelDecision.Refused -> throw JavaContextAuthorityException(decision.code, decision.message)
}

data class JavaProjectContextRequest(
    val expectedSnapshotHash: String,
    val sdkHome: Path?,
    val moduleId: String? = null,
    val sourceSetId: String? = null,
    val sourceSetKind: SourceSetKind? = null,
    val providerId: String? = null,
    val sourceRelativePath: Path? = null,
    val symbolQualifiedName: String? = null,
    val expectedContextHash: String? = null,
    val requiredFacts: Set<JavaProjectContextFact> = emptySet(),
    val contextSchemaVersion: String = DEFAULT_CONTEXT_SCHEMA_VERSION,
) {
    init {
        require(expectedSnapshotHash.matches(Regex("[a-f0-9]{64}"))) { "snapshotHash must be SHA-256" }
        require(expectedContextHash == null || expectedContextHash.matches(Regex("[a-f0-9]{64}"))) {
            "contextHash must be SHA-256"
        }
        require(contextSchemaVersion in SUPPORTED_CONTEXT_SCHEMA_VERSIONS) {
            "contextSchemaVersion is unsupported"
        }
        require(sourceSetId == null || sourceSetKind == null) {
            "sourceSetId and sourceSetKind are alternative selectors"
        }
        require((moduleId == null) == (sourceSetId == null && sourceSetKind == null)) {
            "moduleId requires exactly one sourceSetId or sourceSetKind selector"
        }
        require(providerId == null || moduleId != null) { "providerId requires moduleId and a source-set selector" }
        require(sdkHome == null || sdkHome.isAbsolute) { "sdkHome must be an absolute configured JDK path" }
        require(listOfNotNull(providerId, moduleId, sourceSetId, symbolQualifiedName).all {
            it.isNotBlank() && it.length <= 1_024 && '\u0000' !in it
        }) { "project context selector is invalid" }
        require(sourceRelativePath == null || (!sourceRelativePath.isAbsolute &&
            sourceRelativePath.toString().isNotBlank() && !sourceRelativePath.normalize().startsWith(".."))) {
            "sourceRelativePath must be a safe root-relative path"
        }
        require(requiredFacts.size <= JavaProjectContextFact.values().size) { "required project facts exceed the bounded limit" }
    }

    companion object {
        const val DEFAULT_CONTEXT_SCHEMA_VERSION = "java-project-context-v2"
        val SUPPORTED_CONTEXT_SCHEMA_VERSIONS = setOf(
            DEFAULT_CONTEXT_SCHEMA_VERSION,
            "java-project-context-v3",
            "java-project-context-v4",
            "java-project-context-v5",
            "java-project-context-v6",
            "java-project-context-v7",
        )
    }
}

enum class JavaProjectContextFact {
    SOURCE_ROOT_PROVENANCE,
    COMPILER_OPTIONS,
    ARTIFACT_COORDINATES,
    ANNOTATION_PROCESSORS,
    JPMS,
}

data class JavaContextSourceAssociation(
    val path: Path,
    val symbolQualifiedName: String?,
    val providerId: String,
    val moduleId: String,
    val sourceSetId: String,
    val sourceRoot: Path,
    val generated: Boolean,
    val bindingKey: String?,
    val evidence: String,
)

data class JavaProjectContextView(
    val snapshot: ProjectSnapshot,
    val contextHash: String,
    val sdk: List<JavaReleasePlatformAuthority>,
    val environments: Map<String, JavaBuildSourceSetContext>,
    val selectedSource: JavaContextSourceAssociation?,
    val analysis: JdtAuthoritativeJavaAnalysisResolution.Available?,
    val semanticAnalysisRefusalCode: String? = null,
) {
    val buildModels: List<BuildModel> = javaContextBuildModels(snapshot)
    // Metadata availability is independent from optional facts. Semantic operations
    // additionally require JDT analysis, which may be unavailable for partial JPMS.
    val metadataAvailable: Boolean get() = buildModels.isNotEmpty() && buildModels.all { model ->
        val onlyParserLevelRefusal = semanticAnalysisRefusalCode == "java.context.sourceAnalysisUnqualified" &&
            model.status == BuildModelStatus.PARTIAL && model.diagnostics.isNotEmpty() &&
            model.diagnostics.all { it.code == "sourceLevel.unavailable" }
        model.providerId in setOf("maven-effective-v1", "gradle-effective-v1", DeclaredJavaProjectProvider.ID) &&
            (model.status == BuildModelStatus.AVAILABLE || onlyParserLevelRefusal)
    }

    val effective: Boolean get() = metadataAvailable && analysis != null
}

sealed interface JavaProjectContextResolution {
    data class Available(val view: JavaProjectContextView) : JavaProjectContextResolution
    data class Refused(val snapshotHash: String?, val code: String, val message: String) : JavaProjectContextResolution
}

/** Read-only acquisition. Fresh scans bracket analysis; no context is a mutation lease. */
class JavaEffectiveProjectContextProvider {
    fun read(
        request: JavaProjectContextRequest,
        scan: () -> ProjectSnapshot,
        cancellation: SemanticCancellationToken = SemanticCancellationToken.NONE,
    ): JavaProjectContextResolution = try {
        acquire(request, scan, cancellation)
    } catch (_: JdtJavaAnalysisCancelledException) {
        refused(null, "java.context.cancelled", "Project context acquisition was cancelled")
    } catch (_: Exception) {
        refused(null, "java.context.acquisitionUnavailable", "Project context inputs could not be acquired safely")
    }

    private fun acquire(
        request: JavaProjectContextRequest,
        scan: () -> ProjectSnapshot,
        cancellation: SemanticCancellationToken,
    ): JavaProjectContextResolution {
        if (cancellation.isCancellationRequested()) return refused(null, "java.context.cancelled", "Project context acquisition was cancelled")
        val snapshot = scan()
        if (snapshot.hash != request.expectedSnapshotHash) return refused(snapshot.hash,
            "java.context.snapshotStale", "Project inputs changed; reopen or refresh the project and reacquire context")
        snapshot.buildModels.filter { it.providerId == DeclaredJavaProjectProvider.ID }
            .flatMap { it.diagnostics }.firstOrNull { it.severity == org.refactorkit.core.Diagnostic.Severity.ERROR }?.let {
                return refused(snapshot.hash, it.code, it.message)
            }
        val models = javaContextBuildModels(snapshot)
        val modules = models.flatMap { it.modules }
        val sets = modules.flatMap { it.sourceSets }
        val metadataContextRequested = request.contextSchemaVersion in setOf(
            "java-project-context-v6", "java-project-context-v7",
        )
        val metadataOnlyReleases = linkedSetOf<Int>()
        for (set in sets) {
            val decision = if (metadataContextRequested) JavaProjectBuildMetadataSourceLevelPolicy.resolve(
                set.attributes["java.release"], set.attributes["java.sourceLevel"],
            ) else JavaProjectSourceLevelPolicy.resolve(
                set.attributes["java.release"], set.attributes["java.sourceLevel"],
            )
            when (decision) {
                is JavaProjectSourceLevelDecision.Supported -> {
                    val declaredMetadataOnly = set.attributes["java.buildSystem"] == "declared" && decision.sourceLevel > 25
                    if (JavaProjectContextSemanticReleasePolicy.requiresVersionedSourceContext(
                            decision.sourceLevel, request.contextSchemaVersion)) return refused(snapshot.hash,
                        "java.context.sourceLevelUnsupported",
                        "Source level ${decision.sourceLevel} requires a versioned metadata schema; legacy context grammar remains 8..25")
                    if (declaredMetadataOnly || JavaProjectContextSemanticReleasePolicy.requiresMetadataOnly(
                            decision.sourceLevel, request.contextSchemaVersion,
                            JavaParserReleaseSupport.supportsRelease(decision.sourceLevel),
                            request.sourceRelativePath != null && request.symbolQualifiedName != null)) {
                        metadataOnlyReleases += decision.sourceLevel
                    }
                }
                is JavaProjectSourceLevelDecision.Refused -> return refused(snapshot.hash, decision.code, decision.message)
            }
        }
        val unsupportedSourceLevelModule = snapshot.modules.firstOrNull { module ->
            module.languageSettings["java.sourceLevel.refusalCode"] != null
        }
        if (unsupportedSourceLevelModule != null) {
            val refusalCode = unsupportedSourceLevelModule.languageSettings["java.sourceLevel.refusalCode"]
            val scannerRelease = unsupportedSourceLevelModule.languageSettings["java.sourceLevel"]?.toIntOrNull()
            val metadataOnlyScannerRefusal = metadataContextRequested &&
                refusalCode == "java.context.sourceLevelUnsupported" && scannerRelease != null &&
                scannerRelease in metadataOnlyReleases &&
                JavaProjectBuildMetadataSourceLevelPolicy.supportsRelease(scannerRelease)
            if (!metadataOnlyScannerRefusal) return refused(
                snapshot.hash,
                refusalCode ?: "java.context.sourceLevelUnsupported",
                unsupportedSourceLevelModule.languageSettings["java.sourceLevel.message"]
                    ?: "The project declares a Java release outside the installed JDT parser/compiler range",
            )
        }
        val selectedSourceSetId = request.sourceSetKind?.let { requestedKind ->
            val matchingRows = models.asSequence()
                .filter { request.providerId == null || it.providerId == request.providerId }
                .flatMap { model -> model.modules.asSequence()
                    .filter { module -> module.matchesContextSelector(requireNotNull(request.moduleId)) }
                    .flatMap { module -> module.sourceSets.asSequence()
                        .filter { it.kind == requestedKind }
                        .map { Triple(model.providerId, module.id, it.id) }
                    }
                }
                .distinct()
                .toList()
            when (matchingRows.size) {
                0 -> return refused(snapshot.hash, "java.context.sourceSetUnavailable",
                    "Requested provider/module/source-set kind is absent from the effective project model")
                1 -> matchingRows.single().third
                else -> {
                    val projectRows = matchingRows.map { it.first to it.second }.distinct()
                    if (projectRows.size > 1) return refused(snapshot.hash, "java.context.sourceAssociationAmbiguous",
                        "Requested module/source-set kind matches multiple provider rows; supply providerId")
                    return refused(snapshot.hash, "java.context.sourceSetKindAmbiguous",
                        "Requested source-set kind matches multiple rows; select the exact sourceSetId")
                }
            }
        } ?: request.sourceSetId
        if (snapshot.buildModels.size > ProtocolLimits.MAX_BUILD_MODELS || modules.size > ProtocolLimits.MAX_BUILD_MODULES ||
            modules.any { it.sourceSets.size > ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE } ||
            sets.any { it.sourceRoots.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET ||
                it.classpathEntries.size + it.runtimeClasspathEntries.size > 4_096 ||
                it.moduleDependencies.size > ProtocolLimits.MAX_BUILD_MODULE_DEPENDENCIES_PER_SOURCE_SET } ||
            sets.sumOf { it.classpathEntries.size + it.runtimeClasspathEntries.size } > 16_384 ||
            sets.sumOf { it.sourceRoots.size } > 16_384 ||
            snapshot.files.size > 5_000 || snapshot.files.sumOf { it.content.toByteArray(Charsets.UTF_8).size.toLong() } > 64L * 1_024 * 1_024 ||
            snapshot.classpathEvidence.size > 16_384) return refused(snapshot.hash,
            "java.context.limitExceeded", "Project context exceeds its complete-response limits")
        val selectedScopeRows = models.flatMap { model -> model.modules.flatMap { module ->
            module.sourceSets.filter { set -> request.moduleId == null ||
                module.matchesContextSelector(request.moduleId) && set.id == selectedSourceSetId &&
                    (request.providerId == null || model.providerId == request.providerId) }
                .map { set -> Triple(model.providerId, module.id, set.id) }
        } }.distinct()
        if (request.moduleId != null) {
            if (request.providerId == null && selectedScopeRows.size > 1) return refused(snapshot.hash,
                "java.context.sourceAssociationAmbiguous",
                "Legacy module/source-set selection matches multiple project identities; use the provider-qualified module ID")
            if (selectedScopeRows.isEmpty()) return refused(snapshot.hash,
                "java.context.sourceSetUnavailable", "Requested provider/module/source-set context is absent from the effective project model")
        }
        val metadataOnlySourceOwnerAllowed = metadataOnlyReleases.isNotEmpty() &&
            request.contextSchemaVersion == "java-project-context-v7" &&
            request.sourceRelativePath != null && request.symbolQualifiedName == null
        if (metadataOnlyReleases.isNotEmpty() && (request.symbolQualifiedName != null ||
                request.sourceRelativePath != null && !metadataOnlySourceOwnerAllowed)) {
            return refused(snapshot.hash, "java.context.sourceAnalysisUnqualified",
                "Build metadata is available, but source selection requires qualified semantic analysis in this context schema")
        }
        if (metadataOnlyReleases.isNotEmpty() && JavaProjectContextFact.JPMS in request.requiredFacts) {
            return refused(snapshot.hash, "java.context.sourceAnalysisUnqualified",
                "JPMS source analysis is unavailable because Java source analysis is not qualified for this release")
        }
        val selectedOwners = snapshot.buildSourceRootOwnerships().filter {
            it.providerId in models.map(BuildModel::providerId) &&
            (request.moduleId == null || it.module.matchesContextSelector(request.moduleId) && it.sourceSet.id == selectedSourceSetId) &&
                (request.providerId == null || it.providerId == request.providerId)
        }
        val candidates = request.sourceRelativePath?.let { sourceRelative -> selectedOwners.mapNotNull { owner ->
            val path = owner.root.resolve(sourceRelative).normalize()
            snapshot.files.singleOrNull { it.path == path && it.languageId == "java" }?.let { owner to it }
        }.distinctBy { (owner, file) -> Triple(owner.providerId, owner.sourceSet.id, file.path) } }
        if (candidates != null && request.symbolQualifiedName == null && candidates.size != 1) return refused(snapshot.hash,
            "java.context.sourceAssociationAmbiguous", "Source-relative path requires an unambiguous module, source set and type selection")
        val home = request.sdkHome ?: return refused(snapshot.hash,
            "java.context.sdkRequired", "Configure sdkHome explicitly; the engine runtime is not project SDK authority")
        val configuredCompilerHomes = sets.mapNotNull { it.attributes["java.compilerHome"] }.distinct()
        if (configuredCompilerHomes.any { Path.of(it).toRealPath() != home.toRealPath() }) return refused(snapshot.hash,
            "java.context.sdkMismatch", "Requested SDK differs from the compiler SDK in the effective Gradle model; synchronize explicitly")
        val sdk = mutableListOf<JavaReleasePlatformAuthority>()
        val platformAcquisition = JavaSourceSetPlatformContext.Acquisition(home)
        for (set in sets) {
            val source = set.attributes["java.release"]?.toIntOrNull() ?: set.attributes["java.sourceLevel"]?.toIntOrNull()
            val resolution = platformAcquisition.acquire(set, source in metadataOnlyReleases)
            when (val resolved = resolution) {
                is JavaReleasePlatformResolution.Available -> {
                    if (!JavaProjectContextSemanticReleasePolicy.supportsApiProfile(
                            resolved.authority.release, request.contextSchemaVersion)) return refused(snapshot.hash,
                        "java.platform.compilerApiUnqualified", "The native API profile is not qualified for this context schema")
                    if (sdk.none { it.identitySha256 == resolved.authority.identitySha256 }) sdk.add(resolved.authority)
                }
                is JavaReleasePlatformResolution.Refused -> return refused(snapshot.hash, resolved.code, resolved.message)
            }
        }
        sdk.sortBy { it.release }
        val environments = linkedMapOf<String, JavaBuildSourceSetContext>()
        var expandedEntries = 0L
        for (model in models) for (module in model.modules) for (set in module.sourceSets) {
            val environment = JavaBuildSourceSetContextResolver.resolve(
                model, module, set, allowMetadataOnlySourceLevel = metadataContextRequested,
            ).copy(
                jpms = if (metadataOnlyReleases.isNotEmpty()) JavaJpmsSourceSetContext(
                    BuildFactAvailability.UNAVAILABLE,
                    refusalCode = "java.context.sourceAnalysisUnqualified",
                ) else JavaJpmsProjectContextResolver.resolve(
                    snapshot, module.root, set, set.compilerOptions, set.compilerOptionsAvailability,
                    set.sourceRoots, home,
                ),
            )
            expandedEntries += environment.compileClasspath.size + environment.runtimeClasspath.size + environment.sourceRoots.size
            expandedEntries += environment.jpms.modulePath.size
            if (environment.compileClasspath.size + environment.runtimeClasspath.size > 4_096 || expandedEntries > 65_536) {
                return refused(snapshot.hash, "java.context.limitExceeded", "Expanded project context exceeds complete-response limits")
            }
            environments[key(model.providerId, module.id, set.id)] = environment
        }
        val hash = identity(snapshot, sdk, environments, request.contextSchemaVersion)
        if (request.expectedContextHash != null && request.expectedContextHash != hash) return refused(snapshot.hash,
            "java.context.contextStale", "SDK or project-context evidence changed; reacquire context")
        if (request.requiredFacts.isNotEmpty()) {
            val selectedSets = models.flatMap { model -> model.modules.flatMap { module ->
                module.sourceSets.filter { set -> request.moduleId == null ||
                    module.matchesContextSelector(request.moduleId) && set.id == selectedSourceSetId &&
                        (request.providerId == null || model.providerId == request.providerId) }
                    .map { set -> Triple(model, module, set) }
            } }
            if (selectedSets.isEmpty()) return refused(snapshot.hash, "java.context.sourceSetUnavailable",
                "Requested module/source-set context is absent from the effective project model")
            request.requiredFacts.sortedBy { it.name }.forEach { fact ->
                val failure = selectedSets.firstNotNullOfOrNull { (model, module, set) -> missingFactRefusal(fact, set,
                    environments[key(model.providerId, module.id, set.id)]?.jpms) }
                if (failure != null) return refused(snapshot.hash, failure.first, failure.second)
            }
        }
        val supportedBuildModels = models.all {
            it.providerId in setOf("maven-effective-v1", "gradle-effective-v1", DeclaredJavaProjectProvider.ID) && it.status == BuildModelStatus.AVAILABLE
        }
        val semanticSourceSelectionRequested = request.sourceRelativePath != null || request.symbolQualifiedName != null
        val jpmsAllowsOrdinaryAnalysis = environments.values.all { environment ->
            environment.jpms.availability in setOf(BuildFactAvailability.NOT_DECLARED, BuildFactAvailability.COMPLETE) ||
                environment.jpms.availability == BuildFactAvailability.PARTIAL &&
                    environment.jpms.modulePath.isEmpty() && environment.jpms.descriptors.isEmpty()
        }
        val analysisResolution = if (metadataOnlyReleases.isEmpty() && supportedBuildModels &&
            (semanticSourceSelectionRequested || jpmsAllowsOrdinaryAnalysis)) {
            JdtJavaSemanticAnalyzer().analyzeAuthoritatively(snapshot, sdk.associateBy { it.release }, cancellation)
        } else null
        val analysis = analysisResolution as? JdtAuthoritativeJavaAnalysisResolution.Available
        if (analysisResolution is JdtAuthoritativeJavaAnalysisResolution.Refused) return refused(snapshot.hash,
            analysisResolution.code, analysisResolution.message)
        var association: JavaContextSourceAssociation? = null
        if (request.sourceRelativePath != null || request.symbolQualifiedName != null) {
            if (analysis == null && !metadataOnlySourceOwnerAllowed) {
                return refused(snapshot.hash, "java.context.effectiveModelUnavailable",
                    "Selected source association requires an effective build model and configured Java analysis")
            }
            // The v7 metadata boundary can prove source-root ownership without parsing
            // or resolving a Java declaration. Symbol selection remains refused.
            val symbols = analysis?.analysis?.symbols?.filter { it.kind in setOf(JdtJavaSemanticSymbolKind.CLASS,
                JdtJavaSemanticSymbolKind.INTERFACE, JdtJavaSemanticSymbolKind.ENUM, JdtJavaSemanticSymbolKind.RECORD,
                JdtJavaSemanticSymbolKind.ANNOTATION) && it.evidence == JdtJavaSemanticEvidence.JDT_BINDING && !it.recovered }
                .orEmpty()
            val choices = (candidates ?: selectedOwners.flatMap { owner -> snapshot.files.filter {
                it.languageId == "java" && it.path.startsWith(owner.root)
            }.map { owner to it } }).mapNotNull { (owner, file) ->
                val matching = symbols.filter { it.path == file.path &&
                    (request.symbolQualifiedName == null || it.qualifiedName == request.symbolQualifiedName) }
                if (request.symbolQualifiedName != null && matching.size != 1) null else {
                    val symbol = matching.singleOrNull()
                    JavaContextSourceAssociation(file.path, symbol?.qualifiedName, owner.providerId, owner.module.id,
                        owner.sourceSet.id, owner.root, owner.generated, symbol?.bindingKey,
                        if (symbol == null) "SOURCE_ROOT_OWNERSHIP" else symbol.evidence.name)
                }
            }.distinct()
            if (choices.size != 1) return refused(snapshot.hash, "java.context.sourceAssociationAmbiguous",
                "Source selection has no unique proven association in the selected scope")
            association = choices.single()
        }
        val after = scan()
        if (after.hash != snapshot.hash) return refused(after.hash, "java.context.snapshotStale",
            "Project inputs changed during context acquisition")
        for (authority in sdk) {
            val resolver = JavaReleasePlatformAuthorityResolver()
            val rebound = if (authority.release in metadataOnlyReleases) {
                resolver.resolveMetadata(authority.platformHome, authority.release)
            } else resolver.revalidate(authority)
            if ((rebound as? JavaReleasePlatformResolution.Available)?.authority?.identitySha256 != authority.identitySha256) {
                return refused(after.hash, "java.context.contextStale", "SDK evidence changed during context acquisition")
            }
        }
        if (cancellation.isCancellationRequested()) return refused(after.hash, "java.context.cancelled", "Project context acquisition was cancelled")
        return JavaProjectContextResolution.Available(JavaProjectContextView(snapshot, hash, sdk.toList(),
            environments.toMap(), association, analysis,
            "java.context.sourceAnalysisUnqualified".takeIf { metadataOnlyReleases.isNotEmpty() }))
    }

    private fun identity(snapshot: ProjectSnapshot, sdk: List<JavaReleasePlatformAuthority>,
                         environments: Map<String, JavaBuildSourceSetContext>, contextSchemaVersion: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        field(if (contextSchemaVersion == "java-project-context-v6") contextSchemaVersion else "java-project-context-v2")
        field(snapshot.hash)
        sdk.forEach { field(it.identitySha256) }
        environments.toSortedMap().forEach { (id, environment) ->
            field(id); field(environment.sourceLevel.toString())
            environment.sourceRoots.forEach { field("source"); field(it.toString()) }
            environment.compileClasspath.forEach { field("compile"); field(it.toString()) }
            environment.runtimeClasspath.forEach { field("runtime"); field(it.toString()) }
            environment.compilerOptions.forEach { field("compilerOption"); field(it.name); field(it.value); field(it.origin); field(it.redacted.toString()) }
            field("compilerOptionsAvailability"); field(environment.compilerOptionsAvailability.name)
            environment.annotationProcessors?.let { processors ->
                field("annotationProcessorAvailability"); field(processors.availability.name)
                processors.processorPath.forEach {
                    field("processor"); field(it.path.toString()); field(it.coordinates.groupId)
                    field(it.coordinates.artifactId); field(it.coordinates.version); field(it.extension)
                    field(it.classifier.orEmpty()); field(it.scope?.name.orEmpty()); field(it.transitive.toString())
                }
                processors.processorPathEntries.forEach { field("processorPath"); field(it.toString()) }
                processors.processorNames.sorted().forEach { field("processorName"); field(it) }
                processors.options.toSortedMap().forEach { (key, value) -> field("processorOption"); field(key); field(value) }
            }
            field("jpmsAvailability"); field(environment.jpms.availability.name)
            environment.jpms.modulePath.forEach { entry ->
                field("modulePath"); field(entry.path.toString()); entry.moduleNames.forEach(::field); field(entry.fingerprint.orEmpty())
            }
            environment.jpms.descriptors.forEach { descriptor ->
                field("moduleInfo"); field(descriptor.path.toString()); field(descriptor.moduleName); field(descriptor.contentSha256)
                descriptor.requires.forEach { field("requires"); field(it.moduleName); it.modifiers.forEach(::field) }
                descriptor.exports.forEach { field("exports"); field(it.packageName); it.targets.forEach(::field) }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun refused(hash: String?, code: String, message: String) = JavaProjectContextResolution.Refused(hash, code, message)

    private fun missingFactRefusal(
        fact: JavaProjectContextFact,
        sourceSet: BuildSourceSet,
        jpms: JavaJpmsSourceSetContext?,
    ): Pair<String, String>? = when (fact) {
        JavaProjectContextFact.SOURCE_ROOT_PROVENANCE -> {
            val covered = sourceSet.sourceRootEvidence.map(BuildSourceRootEvidence::path).toSet()
            if (sourceSet.attributes["java.sourceRootProvenance.availability"] in setOf("partial", "ambiguous", "unavailable") ||
                covered != sourceSet.sourceRoots.toSet() ||
                sourceSet.sourceRootEvidence.any { it.availability != BuildFactAvailability.COMPLETE }) {
                "java.context.sourceRootProvenanceIncomplete" to "One or more source roots lack complete, snapshot-bound owner provenance"
            } else null
        }
        JavaProjectContextFact.COMPILER_OPTIONS -> if (sourceSet.compilerOptionsAvailability != BuildFactAvailability.COMPLETE) {
            (sourceSet.compilerOptionsRefusalCode ?: "java.context.compilerOptionsUnavailable") to
                "Effective compiler options are missing, partial or ambiguous for source set ${sourceSet.id}"
        } else null
        JavaProjectContextFact.ARTIFACT_COORDINATES -> when {
            sourceSet.attributes["java.classpath.status"] == "unavailable" ||
                sourceSet.attributes["java.runtimeClasspath.status"] == "unavailable" ->
                "java.context.classpathUnavailable" to
                    "Classpath resolution is incomplete for source set ${sourceSet.id}"
            sourceSet.resolvedArtifactAvailability != BuildFactAvailability.COMPLETE ->
                (sourceSet.resolvedArtifactRefusalCode ?: "java.context.artifactCoordinatesUnavailable") to
                    "Resolved artifact coordinates are missing or ambiguous for source set ${sourceSet.id}"
            else -> null
        }
        JavaProjectContextFact.ANNOTATION_PROCESSORS -> {
            val processors = sourceSet.annotationProcessors
            if (processors == null || processors.availability != BuildFactAvailability.COMPLETE) {
                (processors?.refusalCode ?: "java.context.annotationProcessorContextUnavailable") to
                    "Annotation processor ownership or path is not completely established for source set ${sourceSet.id}"
            } else null
        }
        JavaProjectContextFact.JPMS -> when (jpms?.availability) {
            BuildFactAvailability.COMPLETE, BuildFactAvailability.NOT_DECLARED -> null
            else -> (jpms?.refusalCode ?: "java.context.jpmsContextUnavailable") to
                "JPMS module path or descriptor graph is incomplete for source set ${sourceSet.id}"
        }
    }

    companion object {
        fun key(providerId: String, moduleId: String, sourceSetId: String): String =
            "$providerId/$moduleId/$sourceSetId"
    }
}

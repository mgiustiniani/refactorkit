package org.refactorkit.java

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildAnnotationProcessorConfiguration
import org.refactorkit.core.BuildArtifactCoordinates
import org.refactorkit.core.BuildCompilerOption
import org.refactorkit.core.BuildFactAvailability
import org.refactorkit.core.BuildLanguageEvidence
import org.refactorkit.core.BuildLanguageFacet
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelDiscoveryPolicy
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.BuildSourceRootEvidence
import org.refactorkit.core.BuildSourceRootOrigin
import org.refactorkit.core.BuildResolvedArtifact
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.Module
import org.refactorkit.core.SourceSetKind
import java.nio.file.Path

const val MAVEN_TEST_JVM_CONTEXT_WITH_SELECTION_ATTRIBUTE = "java.maven.test-jvm.context-with-selection"

/** Effective, plugin-free and credential-free Maven build-model provider. */
internal class MavenBuildModelProvider(
    private val localMavenRepository: Path = Path.of(System.getProperty("user.home"), ".m2", "repository"),
) : BuildModelProvider {
    override val id: String = "maven-effective-v1"

    override fun discover(request: BuildModelRequest): BuildModel {
        val allowNetwork = request.policy.networkAccess == BuildModelDiscoveryPolicy.NetworkAccess.ALLOW_ANONYMOUS
        val selection = request.selections[id] ?: org.refactorkit.core.BuildModelSelection()
        val snapshot = JavaProjectScanner(
            allowNetwork,
            localMavenRepository,
            selection.activeProfiles,
            selection.inactiveProfiles,
        ).scan(request.workspaceRoot)
        return snapshot.buildModels.firstOrNull { it.providerId == id } ?: BuildModel(
            providerId = id,
            status = BuildModelStatus.UNAVAILABLE,
            modules = emptyList(),
            diagnostics = listOf(BuildModelDiagnostic(
                code = "buildModel.notApplicable",
                message = "No Maven modules were discovered",
                severity = Diagnostic.Severity.INFO,
            )),
        )
    }

    internal fun project(
        modules: List<Module>,
        allowNetwork: Boolean,
        activeProfiles: Set<String> = emptySet(),
        inactiveProfiles: Set<String> = emptySet(),
        effectiveMavenModules: Map<Path, MavenModuleModel> = emptyMap(),
        workspaceRoot: Path? = null,
    ): BuildModel {
        val projected = JavaModuleBuildModelProjector.project(
            providerId = id,
            modules = modules,
            emptyStatus = BuildModelStatus.UNAVAILABLE,
            emptyDiagnostic = BuildModelDiagnostic(
                code = "buildModel.notApplicable",
                message = "No Maven modules were discovered",
                severity = Diagnostic.Severity.INFO,
            ),
            attributes = mapOf(
                "ecosystem" to "maven",
                "strategy" to "embedded-effective-model",
                "buildCodeExecution" to "denied",
                "credentialsAccess" to "denied",
                "networkDefault" to "denied",
                "networkAccess" to if (allowNetwork) "anonymous-opt-in" else "denied",
                "activeProfiles" to activeProfiles.sorted().joinToString(","),
                "inactiveProfiles" to inactiveProfiles.sorted().joinToString(","),
            ),
        )
        if (effectiveMavenModules.isEmpty() || workspaceRoot == null) return projected
        val moduleByRoot = modules.associateBy { it.root.toAbsolutePath().normalize() }
        return projected.copy(modules = projected.modules.map { buildModule ->
            val legacy = moduleByRoot[buildModule.root.toAbsolutePath().normalize()]
                ?: modules.firstOrNull { it.name == buildModule.name }
                ?: return@map buildModule
            val facts = effectiveMavenModules[legacy.root.toAbsolutePath().normalize()] ?: return@map buildModule
            buildModule.copy(
                coordinates = org.refactorkit.core.BuildArtifactCoordinates(
                    facts.coordinate.groupId, facts.coordinate.artifactId, facts.coordinate.version,
                ),
                sourceSets = MavenBuildSourceSetProjector.project(legacy, facts, workspaceRoot, buildModule.sourceSets),
            )
        })
    }
}

/**
 * Gradle provider limited to deterministic descriptor heuristics. Even an
 * explicit execution allowance does not execute settings, scripts, tasks, or
 * the Tooling API; executable Gradle modeling requires a future provider.
 */
internal class GradleDeclarativeBuildModelProvider : BuildModelProvider {
    override val id: String = "gradle-declarative-v1"

    override fun discover(request: BuildModelRequest): BuildModel =
        GradleDeclarativeModelBuilder().build(request.workspaceRoot)

    internal fun project(modules: List<Module>): BuildModel =
        JavaModuleBuildModelProjector.project(
            providerId = id,
            modules = modules,
            emptyStatus = BuildModelStatus.UNAVAILABLE,
            emptyDiagnostic = BuildModelDiagnostic(
                code = "buildModel.notApplicable",
                message = "No Gradle modules were discovered",
                severity = Diagnostic.Severity.INFO,
            ),
            forcedStatus = BuildModelStatus.PARTIAL,
            attributes = mapOf(
                "ecosystem" to "gradle",
                "strategy" to "declarative-heuristic",
                "buildCodeExecution" to "denied",
                "credentialsAccess" to "denied",
                "networkDefault" to "denied",
                "networkAccess" to "denied",
            ),
        )
}

internal class ConventionalJavaBuildModelProvider : BuildModelProvider {
    override val id: String = "java-conventional-v1"

    override fun discover(request: BuildModelRequest): BuildModel {
        val snapshot = JavaProjectScanner().scanWithoutBuildModels(request.workspaceRoot)
        return project(snapshot.modules.filter { it.languageSettings["java.buildSystem"] == "conventional" })
    }

    internal fun project(modules: List<Module>): BuildModel =
        JavaModuleBuildModelProjector.project(
            providerId = id,
            modules = modules,
            emptyStatus = BuildModelStatus.UNAVAILABLE,
            emptyDiagnostic = BuildModelDiagnostic(
                code = "buildModel.notApplicable",
                message = "No conventional Java modules were discovered",
                severity = Diagnostic.Severity.INFO,
            ),
            forcedStatus = BuildModelStatus.PARTIAL,
            attributes = mapOf(
                "ecosystem" to "java",
                "strategy" to "conventional-layout",
                "buildCodeExecution" to "denied",
                "credentialsAccess" to "denied",
                "networkDefault" to "denied",
                "networkAccess" to "denied",
            ),
        )
}

private object JavaModuleBuildModelProjector {
    fun project(
        providerId: String,
        modules: List<Module>,
        emptyStatus: BuildModelStatus,
        emptyDiagnostic: BuildModelDiagnostic,
        attributes: Map<String, String>,
        forcedStatus: BuildModelStatus? = null,
    ): BuildModel {
        if (modules.isEmpty()) return BuildModel(
            providerId = providerId,
            status = emptyStatus,
            modules = emptyList(),
            diagnostics = listOf(emptyDiagnostic),
            attributes = attributes,
        )
        val moduleIds = modules.map(Module::name).toSet()
        val diagnostics = modules.flatMap(::diagnostics)
        val inferredStatus = when {
            diagnostics.any {
                it.code in setOf("buildModel.unavailable", "java.maven.reactorDescriptor.missing")
            } -> BuildModelStatus.UNAVAILABLE
            diagnostics.any { it.code == "classpath.offlineMissing" } -> BuildModelStatus.OFFLINE_MISSING
            diagnostics.any { it.severity == Diagnostic.Severity.ERROR } -> BuildModelStatus.PARTIAL
            diagnostics.isNotEmpty() -> BuildModelStatus.PARTIAL
            else -> BuildModelStatus.AVAILABLE
        }
        val projectedModules = propagateUnavailableDependencies(
            modules.map { module -> projectModule(module, moduleIds) },
        )
        return BuildModel(
            providerId = providerId,
            status = forcedStatus ?: inferredStatus,
            modules = projectedModules,
            diagnostics = diagnostics,
            attributes = attributes,
        )
    }

    private fun diagnostics(module: Module): List<BuildModelDiagnostic> = buildList {
        when (module.languageSettings["java.buildModel.status"]) {
            "unavailable" -> {
                val reactorDescriptorCode = module.languageSettings["java.maven.reactorDescriptor.code"]
                add(BuildModelDiagnostic(
                    reactorDescriptorCode ?: "buildModel.unavailable",
                    module.languageSettings["java.buildModel.message"] ?: "Build model unavailable",
                    module.languageSettings["java.maven.reactorDescriptor.module"] ?: module.name,
                ))
            }
            "partial" -> add(BuildModelDiagnostic(
                "buildModel.partial",
                module.languageSettings["java.buildModel.message"] ?: "Build model is partial",
                module.name,
                Diagnostic.Severity.WARNING,
            ))
        }
        if (module.languageSettings["java.sourceLevel.status"] == "unavailable") add(BuildModelDiagnostic(
            "sourceLevel.unavailable",
            module.languageSettings["java.sourceLevel.message"] ?: "Source level unavailable",
            module.name,
        ))
        if (module.languageSettings["java.classpath.status"] == "unavailable") {
            val message = module.languageSettings["java.classpath.message"] ?: "Classpath unavailable"
            val code = if (message.startsWith("Offline artifacts unavailable")) {
                "classpath.offlineMissing"
            } else {
                "classpath.unavailable"
            }
            add(BuildModelDiagnostic(code, message, module.name))
        }
    }

    private fun propagateUnavailableDependencies(initial: List<BuildModule>): List<BuildModule> {
        var modules = initial
        repeat(initial.size + 1) {
            val byId = modules.associateBy(BuildModule::id)
            var changed = false
            val next = modules.map { module -> module.copy(sourceSets = module.sourceSets.map { sourceSet ->
                if (sourceSet.attributes["java.classpath.status"] == "unavailable") return@map sourceSet
                val unavailableDependency = sourceSet.moduleDependencies.firstNotNullOfOrNull { dependency ->
                    val targetMain = byId[dependency.targetModuleId]?.sourceSets
                        ?.firstOrNull { it.kind == SourceSetKind.MAIN } ?: return@firstNotNullOfOrNull null
                    dependency.targetModuleId.takeIf {
                        targetMain.attributes["java.classpath.status"] == "unavailable" ||
                            (sourceSet.kind != SourceSetKind.MAIN &&
                                targetMain.attributes["java.runtimeClasspath.status"] == "unavailable")
                    }
                }
                if (unavailableDependency == null) sourceSet else {
                    changed = true
                    sourceSet.copy(attributes = sourceSet.attributes + mapOf(
                        "java.classpath.status" to "unavailable",
                        "java.classpath.message" to "Required dependency source set is unavailable: $unavailableDependency:main",
                        "java.classpath.cause" to "dependency",
                    ))
                }
            }) }
            modules = next
            if (!changed) return modules
        }
        return modules
    }

    private fun projectModule(module: Module, moduleIds: Set<String>): BuildModule {
        val mainDependencies = module.mainDependencies.filter(moduleIds::contains).map { dependency ->
            BuildDependency(
                dependency,
                moduleDependencyScope(module, "main", dependency, DependencyScope.COMPILE),
            )
        }
        val testDependencies = module.testDependencies.filter(moduleIds::contains).map { dependency ->
            BuildDependency(
                dependency,
                moduleDependencyScope(
                    module,
                    "test",
                    dependency,
                    if (dependency in module.mainDependencies) DependencyScope.COMPILE else DependencyScope.TEST,
                ),
            )
        }
        val sourceLevel = module.languageSettings["java.sourceLevel"] ?: "8"
        return BuildModule(
            id = module.name,
            name = module.name,
            root = module.root,
            sourceSets = listOf(
                BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = module.mainSourceRoots,
                    generatedSourceRoots = module.generatedSourceRoots,
                    outputDirectories = module.mainOutputDirectories,
                    classpathEntries = module.mainClasspathEntries,
                    runtimeClasspathEntries = module.mainRuntimeClasspathEntries,
                    moduleDependencies = mainDependencies,
                    attributes = jvmSourceSetAttributes(module, sourceLevel, test = false),
                    languageFacets = languageFacets(module, sourceLevel),
                ),
                BuildSourceSet(
                    id = "test",
                    kind = SourceSetKind.TEST,
                    sourceRoots = (module.testSourceRoots + module.generatedTestSourceRoots).distinct(),
                    generatedSourceRoots = module.generatedTestSourceRoots,
                    outputDirectories = module.testOutputDirectories,
                    classpathEntries = module.testClasspathEntries,
                    runtimeClasspathEntries = module.testClasspathEntries,
                    moduleDependencies = testDependencies,
                    attributes = jvmSourceSetAttributes(module, sourceLevel, test = true),
                    languageFacets = languageFacets(module, sourceLevel),
                ),
            ),
            attributes = module.languageSettings,
        )
    }

    private fun moduleDependencyScope(
        module: Module,
        sourceSet: String,
        dependency: String,
        fallback: DependencyScope,
    ): DependencyScope = module.languageSettings["java.moduleDependency.$sourceSet.$dependency.scope"]
        ?.uppercase()?.let { runCatching { DependencyScope.valueOf(it) }.getOrNull() } ?: fallback

    private fun languageFacets(module: Module, sourceLevel: String): List<BuildLanguageFacet> = buildList {
        add(BuildLanguageFacet(
            languageId = "java",
            platformId = "jvm",
            compilerId = "eclipse-jdt",
            sourceVersion = sourceLevel,
            targetVersion = sourceLevel,
            targetRuntimeVersion = sourceLevel,
            evidence = if (module.languageSettings["java.sourceLevelEvidence"] == "declared" ||
                module.languageSettings["java.sourceLevel.status"] == "available") {
                BuildLanguageEvidence.DECLARED
            } else BuildLanguageEvidence.DERIVED,
        ))
        val kotlinPlatform = module.languageSettings["kotlin.platform"]
        if (kotlinPlatform != null || module.sourceRoots.any { "/kotlin" in "/${it.toString().replace('\\', '/')}/" }) {
            add(BuildLanguageFacet(
                languageId = "kotlin",
                platformId = kotlinPlatform ?: "unproven",
                compilerId = "kotlin-build-plugin",
                targetVersion = module.languageSettings["kotlin.jvmTarget"],
                targetRuntimeVersion = module.languageSettings["kotlin.targetJdk"] ?: sourceLevel.takeIf {
                    module.languageSettings["java.sourceLevelEvidence"] == "declared" ||
                        module.languageSettings["java.sourceLevel.status"] == "available"
                },
                compilerPluginIds = module.languageSettings["kotlin.compilerPlugins"]
                    ?.split(',')?.filter(String::isNotBlank).orEmpty(),
                evidence = when (kotlinPlatform) {
                    "jvm" -> BuildLanguageEvidence.DECLARED
                    "unsupported" -> BuildLanguageEvidence.UNSUPPORTED
                    else -> BuildLanguageEvidence.PARTIAL
                },
            ))
        }
    }

    private fun jvmSourceSetAttributes(module: Module, sourceLevel: String, test: Boolean): Map<String, String> = buildMap {
        put("java.sourceLevel", sourceLevel)
        val classpathPrefix = if (test) "java.testClasspath" else "java.mainClasspath"
        module.languageSettings["$classpathPrefix.status"]?.let { put("java.classpath.status", it) }
        module.languageSettings["$classpathPrefix.message"]?.let { put("java.classpath.message", it) }
        module.languageSettings.toSortedMap().forEach { (key, value) ->
            if (key.startsWith("$classpathPrefix.missing.")) {
                put("java.classpath${key.removePrefix(classpathPrefix)}", value)
            }
        }
        if (!test) {
            module.languageSettings["java.runtimeClasspath.status"]?.let { put("java.runtimeClasspath.status", it) }
            module.languageSettings["java.runtimeClasspath.message"]?.let { put("java.runtimeClasspath.message", it) }
        }
        listOf(
            "java.sourceLevel.status",
            "java.sourceLevelEvidence",
            "java.sourceLevel.refusalCode",
            "java.sourceLevel.message",
            "java.release",
            "java.platformSelection",
            "kotlin.platform",
            "kotlin.jvmTarget",
            "kotlin.targetJdk",
        ).forEach { key ->
            module.languageSettings[key]?.let { put(key, it) }
        }
    }
}

/** Keeps Maven roots and their build-helper execution provenance as separate source-set rows. */
private object MavenBuildSourceSetProjector {
    private val failsafeJson = Json
    private val testJvmJson = Json

    private fun availability(value: org.refactorkit.core.BuildFactAvailability) =
        value.name.lowercase().replace('_', '-')

    private fun failsafeAttribute(fact: MavenFailsafeContextFact): String = failsafeJson.encodeToString(buildJsonObject {
        put("availability", availability(fact.availability))
        put("refusalCode", fact.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
        put("executions", buildJsonArray {
            fact.executions.take(16).forEach { execution -> add(buildJsonObject {
                put("pluginId", execution.pluginId)
                put("pluginVersion", execution.pluginVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("executionId", execution.executionId?.let(::JsonPrimitive) ?: JsonNull)
                put("goals", buildJsonArray { execution.goals.take(32).forEach { add(JsonPrimitive(it)) } })
                put("phase", execution.phase?.let(::JsonPrimitive) ?: JsonNull)
                put("profileId", execution.profileId?.let(::JsonPrimitive) ?: JsonNull)
                put("availability", availability(execution.availability))
                put("refusalCode", execution.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
                fun settings(values: List<MavenFailsafeConfiguredValue>) = buildJsonArray {
                    values.take(128).forEach { value -> add(buildJsonObject {
                        put("name", value.name)
                        put("value", value.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("origin", value.origin)
                        put("availability", availability(value.availability))
                    }) }
                }
                put("selectors", settings(execution.selectors))
                put("reports", settings(execution.reports))
            }) }
        })
    })

    private fun testJvmAttributes(fact: MavenTestJvmContextFact): Map<String, String> = mapOf(
        "java.maven.test-jvm.context" to testJvmAttribute(fact, includeSelection = false),
        MAVEN_TEST_JVM_CONTEXT_WITH_SELECTION_ATTRIBUTE to testJvmAttribute(fact, includeSelection = true),
    )

    private fun testJvmAttribute(fact: MavenTestJvmContextFact, includeSelection: Boolean): String = testJvmJson.encodeToString(buildJsonObject {
        put("availability", availability(fact.availability))
        put("mode", fact.mode)
        put("refusalCode", fact.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
        put("executions", buildJsonArray {
            fact.executions.take(16).forEach { execution -> add(buildJsonObject {
                put("pluginId", execution.pluginId)
                put("pluginVersion", execution.pluginVersion?.let(::JsonPrimitive) ?: JsonNull)
                put("executionId", execution.executionId?.let(::JsonPrimitive) ?: JsonNull)
                put("goals", buildJsonArray { execution.goals.take(32).forEach { add(JsonPrimitive(it)) } })
                put("phase", execution.phase?.let(::JsonPrimitive) ?: JsonNull)
                put("mode", execution.mode)
                put("availability", availability(execution.availability))
                put("refusalCode", execution.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
                fun settings(values: List<MavenTestJvmSetting>) = buildJsonArray {
                    values.take(16).forEach { setting -> add(buildJsonObject {
                        put("name", setting.name)
                        put("value", setting.value?.let(::JsonPrimitive) ?: JsonNull)
                        put("origin", setting.origin)
                        put("availability", availability(setting.availability))
                        put("redacted", setting.redacted)
                    }) }
                }
                put("forkSettings", settings(execution.forkSettings))
                put("argLineLayers", settings(execution.argLineLayers))
                put("effectiveArgLineTemplate", execution.effectiveArgLineTemplate?.let(::JsonPrimitive) ?: JsonNull)
                if (includeSelection) {
                    val selection = execution.jvmSelection
                    put("jvmSelection", buildJsonObject {
                        put("kind", selection.kind)
                        put("availability", availability(selection.availability))
                        put("origin", selection.origin?.let(::JsonPrimitive) ?: JsonNull)
                        put("executable", selection.executable?.let(::JsonPrimitive) ?: JsonNull)
                        put("requirements", buildJsonObject { selection.requirements.forEach { (key, value) -> put(key, value) } })
                        put("redacted", selection.redacted)
                        put("refusalCode", selection.refusalCode?.let(::JsonPrimitive) ?: JsonNull)
                        val prerequisite = selection.toolchainPrerequisite
                        put("toolchainPrerequisite", if (prerequisite == null) JsonNull else buildJsonObject {
                            put("availability", "unavailable")
                            put("origin", prerequisite.origin)
                            put("requirements", buildJsonObject {
                                prerequisite.requirements.forEach { (key, value) -> put(key, value) }
                            })
                            put("redacted", prerequisite.redacted)
                            put("refusalCode", prerequisite.refusalCode)
                        })
                    })
                }
            }) }
        })
    })

    fun project(
        module: Module,
        facts: MavenModuleModel,
        workspaceRoot: Path,
        baseSourceSets: List<BuildSourceSet>,
    ): List<BuildSourceSet> {
        val workspace = workspaceRoot.toAbsolutePath().normalize()
        fun relative(path: Path): Path? {
            val normalized = if (path.isAbsolute) {
                val absolute = path.toAbsolutePath().normalize()
                if (!absolute.startsWith(workspace)) return null
                workspace.relativize(absolute)
            } else path.normalize()
            return normalized.takeIf { !it.isAbsolute && !it.startsWith("..") }
        }
        val declarationPaths = facts.sourceRootDeclarations.mapNotNull { declaration ->
            declaration to relative(declaration.path)
        }.toMap()
        val customDeclarations = facts.sourceRootDeclarations.filter { it.sourceSetId !in setOf("main", "test") }
            .groupBy(MavenSourceRootDeclaration::sourceSetId)
        val customMainRoots = customDeclarations.values.flatten().filter { it.kind == "main" }
            .mapNotNull { declarationPaths[it] }.toSet()
        val customTestRoots = customDeclarations.values.flatten().filter { it.kind == "test" }
            .mapNotNull { declarationPaths[it] }.toSet()
        val conventionalMainRoots = setOf("src/main/java", "src/main/kotlin").map { Path.of(it) }.toSet()
        val conventionalTestRoots = setOf("src/test/java", "src/test/kotlin").map { Path.of(it) }.toSet()
        val primaryMain = facts.primaryMainSourceDirectory?.let(::relative)
        val primaryTest = facts.primaryTestSourceDirectory?.let(::relative)

        fun rootEvidence(
            sourceSetId: String,
            roots: List<Path>,
            generatedRoots: Set<Path>,
            fallbackOrigin: BuildSourceRootOrigin,
        ): List<BuildSourceRootEvidence> = roots.flatMap { root ->
            val declarations = facts.sourceRootDeclarations.filter { declaration ->
                declarationPaths[declaration] == root && (declaration.sourceSetId == sourceSetId ||
                    declaration.sourceSetId !in setOf("main", "test") &&
                    declaration.kind == if (sourceSetId == "main") "main" else "test")
            }
            val generatedEvidence = if (root in generatedRoots && declarations.isEmpty()) listOf(BuildSourceRootEvidence(
                    root, BuildSourceRootOrigin.GENERATED, "maven-effective-v1",
                    availability = BuildFactAvailability.PARTIAL,
                )) else emptyList()
            if (declarations.isNotEmpty()) {
                generatedEvidence + declarations.map { declaration ->
                    BuildSourceRootEvidence(
                        path = root,
                        origin = declaration.origin,
                        providerId = "maven-effective-v1",
                        descriptorPath = declaration.declaringPom?.let(::relative),
                        profileId = declaration.profileId,
                        pluginId = declaration.pluginId,
                        executionId = declaration.executionId,
                        goal = declaration.goal,
                        phase = declaration.phase,
                        availability = declaration.availability,
                    )
                }
            } else generatedEvidence.ifEmpty { listOf(BuildSourceRootEvidence(
                path = root,
                origin = fallbackOrigin,
                providerId = "maven-effective-v1",
                availability = if (fallbackOrigin == BuildSourceRootOrigin.CONVENTIONAL) {
                    BuildFactAvailability.COMPLETE
                } else BuildFactAvailability.PARTIAL,
            )) }
        }.distinct()

        fun sourceSetAttributes(base: BuildSourceSet, test: Boolean, factsComplete: Boolean): Map<String, String> =
            base.attributes + buildMap {
                put("java.sourceLevel", facts.sourceLevel?.toString() ?: base.attributes["java.sourceLevel"].orEmpty())
                facts.releaseLevel?.let { put("java.release", it.toString()) }
                put("visibility", if (test) "test" else "main")
                put("java.compilerOptions.availability", if (factsComplete) "complete" else "partial")
                if (!factsComplete) put("java.compilerOptions.refusalCode", "java.context.compilerOptionsIncomplete")
                if (facts.sourceRootFailure != null) {
                    put("java.sourceRootProvenance.availability", "partial")
                    put("java.sourceRootProvenance.refusalCode", "java.context.sourceRootProvenanceIncomplete")
                }
            }

        fun copySet(
            base: BuildSourceSet,
            roots: List<Path>,
            generated: List<Path>,
            test: Boolean,
            options: List<BuildCompilerOption>,
            optionsAvailability: BuildFactAvailability,
            processors: BuildAnnotationProcessorConfiguration,
            artifacts: List<BuildResolvedArtifact>,
            failsafeFact: MavenFailsafeContextFact? = null,
            testJvmFact: MavenTestJvmContextFact? = null,
            custom: Boolean = false,
        ): BuildSourceSet {
            val rootEvidence = rootEvidence(base.id, roots, generated.toSet(), BuildSourceRootOrigin.CONVENTIONAL)
            val provenanceComplete = rootEvidence.all { it.availability == BuildFactAvailability.COMPLETE }
            val availability = optionsAvailability
            val artifactPaths = artifacts.map(BuildResolvedArtifact::path).toSet()
            val externalArtifacts = (base.classpathEntries + base.runtimeClasspathEntries).filter {
                it.isAbsolute && it.fileName?.toString()?.endsWith(".jar", ignoreCase = true) == true
            }
            val artifactAvailability = if (externalArtifacts.all { it in artifactPaths }) {
                BuildFactAvailability.COMPLETE
            } else BuildFactAvailability.PARTIAL
            val failSafeAttributes = failsafeFact?.let { mapOf("java.maven.failsafe.context" to failsafeAttribute(it)) }.orEmpty()
            val testJvmAttributes = if (test) testJvmFact?.let(::testJvmAttributes).orEmpty() else emptyMap()
            return base.copy(
                kind = if (custom && test && facts.sourceRootDeclarations.any {
                        it.sourceSetId == base.id && it.integrationTest
                    }) SourceSetKind.INTEGRATION_TEST else if (custom) SourceSetKind.CUSTOM else base.kind,
                sourceRoots = roots.distinct(),
                generatedSourceRoots = generated.distinct(),
                compilerOptions = options,
                compilerOptionsAvailability = availability,
                compilerOptionsRefusalCode = if (availability == BuildFactAvailability.COMPLETE) null
                    else "java.context.compilerOptionsIncomplete",
                resolvedArtifacts = artifacts.distinctBy { it.path },
                resolvedArtifactAvailability = artifactAvailability,
                resolvedArtifactRefusalCode = if (artifactAvailability == BuildFactAvailability.COMPLETE) null
                    else "java.context.artifactCoordinatesIncomplete",
                annotationProcessors = processors,
                sourceRootEvidence = rootEvidence,
                attributes = sourceSetAttributes(base, test, availability == BuildFactAvailability.COMPLETE) + failSafeAttributes + testJvmAttributes +
                    if (provenanceComplete) emptyMap() else mapOf(
                        "java.sourceRootProvenance.availability" to "partial",
                        "java.sourceRootProvenance.refusalCode" to "java.context.sourceRootProvenanceIncomplete",
                    ),
            )
        }

        val mainBase = baseSourceSets.single { it.kind == SourceSetKind.MAIN }
        val testBase = baseSourceSets.single { it.kind == SourceSetKind.TEST }
        val mainGenerated = module.generatedSourceRoots.mapNotNull(::relative)
        val testGenerated = module.generatedTestSourceRoots.mapNotNull(::relative)
        val customRootOwnerCounts = customDeclarations.values.flatten().mapNotNull { declaration ->
            declarationPaths[declaration]?.let { it to declaration.sourceSetId }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, owners) -> owners.distinct().size }
        val mainDefault = (module.mainSourceRoots.mapNotNull(::relative) - customMainRoots +
            conventionalMainRoots.filter { it in module.sourceRoots } + listOfNotNull(primaryMain) + mainGenerated).distinct()
        val testDefault = (module.testSourceRoots.mapNotNull(::relative) - customTestRoots +
            conventionalTestRoots.filter { it in module.sourceRoots } + listOfNotNull(primaryTest) + testGenerated).distinct()
        val main = copySet(
            mainBase,
            mainDefault,
            mainGenerated,
            test = false,
            options = facts.mainCompilerOptions,
            optionsAvailability = facts.mainCompilerOptionsAvailability,
            processors = facts.mainAnnotationProcessors,
            artifacts = (facts.mainResolvedArtifacts + facts.runtimeResolvedArtifacts).distinctBy { it.path },
        )
        val test = copySet(
            testBase,
            testDefault,
            testGenerated,
            test = true,
            options = facts.testCompilerOptions,
            optionsAvailability = facts.testCompilerOptionsAvailability,
            processors = facts.testAnnotationProcessors,
            artifacts = facts.testResolvedArtifacts,
            testJvmFact = facts.testJvmContextFact,
        )
        val custom = customDeclarations.mapNotNull { (id, declarations) ->
            val testVisibility = declarations.map(MavenSourceRootDeclaration::kind).distinct().singleOrNull() == "test"
            val mixedKinds = declarations.map(MavenSourceRootDeclaration::kind).distinct().size != 1
            val base = if (testVisibility) testBase else mainBase
            val roots = declarations.mapNotNull { declarationPaths[it] }.distinct()
            val overlappingBase = if (testVisibility) testDefault.toSet() else mainDefault.toSet()
            val exclusiveRoots = roots.filterNot(overlappingBase::contains)
            if (exclusiveRoots.isEmpty()) return@mapNotNull null
            val generated = if (testVisibility) testGenerated else mainGenerated
            val processors = if (testVisibility) facts.testAnnotationProcessors else facts.mainAnnotationProcessors
            val options = if (testVisibility) facts.testCompilerOptions else facts.mainCompilerOptions
            val availability = if (testVisibility) facts.testCompilerOptionsAvailability else facts.mainCompilerOptionsAvailability
            val artifacts = if (testVisibility) facts.testResolvedArtifacts else facts.mainResolvedArtifacts
            val integrationTestScope = declarations.any(MavenSourceRootDeclaration::integrationTest)
            val testJvmFact = when {
                mixedKinds -> MavenTestJvmContextFact(
                    org.refactorkit.core.BuildFactAvailability.AMBIGUOUS,
                    "ambiguous",
                    "java.context.mavenTestJvmExecutionAmbiguous",
                )
                integrationTestScope -> facts.integrationTestJvmContextFact
                else -> facts.testJvmContextFact
            }
            val row = copySet(
                base.copy(id = id),
                exclusiveRoots,
                exclusiveRoots.filter(generated.toSet()::contains),
                testVisibility,
                options,
                availability,
                processors,
                artifacts,
                failsafeFact = when {
                    !testVisibility -> null
                    mixedKinds -> MavenFailsafeContextFact(
                        org.refactorkit.core.BuildFactAvailability.AMBIGUOUS,
                        "java.context.mavenFailsafeAssociationAmbiguous",
                    )
                    else -> declarations.mapNotNull(MavenSourceRootDeclaration::failsafeFact).distinct().singleOrNull()
                        ?: MavenFailsafeContextFact(org.refactorkit.core.BuildFactAvailability.NOT_DECLARED)
                },
                testJvmFact = if (testVisibility) testJvmFact else null,
                custom = true,
            )
            val ambiguousOwnership = roots.any { (customRootOwnerCounts[it] ?: 0) > 1 }
            if (mixedKinds || ambiguousOwnership) row.copy(
                sourceRootEvidence = row.sourceRootEvidence.map { evidence ->
                    if (evidence.path in roots) evidence.copy(availability = BuildFactAvailability.AMBIGUOUS) else evidence
                },
                attributes = row.attributes + mapOf(
                    "java.sourceRootProvenance.availability" to "ambiguous",
                    "java.sourceRootProvenance.refusalCode" to "java.context.sourceRootOwnershipAmbiguous",
                ) + mapOf("java.maven.failsafe.context" to failsafeAttribute(MavenFailsafeContextFact(
                    org.refactorkit.core.BuildFactAvailability.AMBIGUOUS,
                    "java.context.mavenFailsafeAssociationAmbiguous",
                ))) + if (testVisibility) testJvmAttributes(MavenTestJvmContextFact(
                    org.refactorkit.core.BuildFactAvailability.AMBIGUOUS,
                    "ambiguous",
                    "java.context.mavenTestJvmExecutionAmbiguous",
                )) else emptyMap(),
            ) else row
        }
        return listOf(main, test) + custom
    }
}

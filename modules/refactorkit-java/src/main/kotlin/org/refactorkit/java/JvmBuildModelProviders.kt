package org.refactorkit.java

import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelDiscoveryPolicy
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.Module
import org.refactorkit.core.SourceSetKind
import java.nio.file.Path

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
        ).scanWithoutBuildModels(request.workspaceRoot)
        return project(
            snapshot.modules.filter { it.languageSettings["java.buildSystem"] == "maven" },
            allowNetwork,
            selection.activeProfiles,
            selection.inactiveProfiles,
        )
    }

    internal fun project(
        modules: List<Module>,
        allowNetwork: Boolean,
        activeProfiles: Set<String> = emptySet(),
        inactiveProfiles: Set<String> = emptySet(),
    ): BuildModel =
        JavaModuleBuildModelProjector.project(
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
            diagnostics.any { it.code == "buildModel.unavailable" } -> BuildModelStatus.UNAVAILABLE
            diagnostics.any { it.code == "classpath.offlineMissing" } -> BuildModelStatus.OFFLINE_MISSING
            diagnostics.any { it.severity == Diagnostic.Severity.ERROR } -> BuildModelStatus.PARTIAL
            diagnostics.isNotEmpty() -> BuildModelStatus.PARTIAL
            else -> BuildModelStatus.AVAILABLE
        }
        return BuildModel(
            providerId = providerId,
            status = forcedStatus ?: inferredStatus,
            modules = modules.map { module -> projectModule(module, moduleIds) },
            diagnostics = diagnostics,
            attributes = attributes,
        )
    }

    private fun diagnostics(module: Module): List<BuildModelDiagnostic> = buildList {
        when (module.languageSettings["java.buildModel.status"]) {
            "unavailable" -> add(BuildModelDiagnostic(
                "buildModel.unavailable",
                module.languageSettings["java.buildModel.message"] ?: "Build model unavailable",
                module.name,
            ))
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

    private fun projectModule(module: Module, moduleIds: Set<String>): BuildModule {
        val mainDependencies = module.mainDependencies.filter(moduleIds::contains)
            .map { BuildDependency(it, DependencyScope.COMPILE) }
        val testDependencies = module.testDependencies.filter(moduleIds::contains).map { dependency ->
            BuildDependency(
                dependency,
                if (dependency in module.mainDependencies) DependencyScope.COMPILE else DependencyScope.TEST,
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
                    moduleDependencies = mainDependencies,
                    attributes = jvmSourceSetAttributes(module, sourceLevel),
                ),
                BuildSourceSet(
                    id = "test",
                    kind = SourceSetKind.TEST,
                    sourceRoots = (module.testSourceRoots + module.generatedTestSourceRoots).distinct(),
                    generatedSourceRoots = module.generatedTestSourceRoots,
                    outputDirectories = module.testOutputDirectories,
                    classpathEntries = module.testClasspathEntries,
                    moduleDependencies = testDependencies,
                    attributes = jvmSourceSetAttributes(module, sourceLevel),
                ),
            ),
            attributes = module.languageSettings,
        )
    }

    private fun jvmSourceSetAttributes(module: Module, sourceLevel: String): Map<String, String> = buildMap {
        put("java.sourceLevel", sourceLevel)
        listOf(
            "java.sourceLevel.status",
            "java.sourceLevelEvidence",
            "kotlin.platform",
            "kotlin.jvmTarget",
            "kotlin.targetJdk",
        ).forEach { key ->
            module.languageSettings[key]?.let { put(key, it) }
        }
    }
}

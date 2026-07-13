package org.refactorkit.typescript

import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceSetKind
import java.nio.file.Path

/** Non-executable Build Model SPI projection for bounded tsconfig/jsconfig discovery. */
object TypeScriptBuildModelIntegration {
    fun attach(
        snapshot: ProjectSnapshot,
        provider: TypeScriptBuildModelProvider = TypeScriptBuildModelProvider(),
    ): ProjectSnapshot {
        val model = provider.discover(BuildModelRequest(snapshot.workspace.root))
        return snapshot.copy(buildModels = (snapshot.buildModels.filterNot { it.providerId == provider.id } + model)
            .sortedBy(BuildModel::providerId))
    }
}

class TypeScriptBuildModelProvider(
    private val builder: TypeScriptProjectModelBuilder = TypeScriptProjectModelBuilder(),
) : BuildModelProvider {
    override val id: String = TypeScriptProjectModel.PROVIDER_ID

    override fun discover(request: BuildModelRequest): BuildModel {
        val model = builder.build(request.workspaceRoot)
        if (model.status != TypeScriptProjectModelStatus.AVAILABLE) {
            return BuildModel(
                providerId = id,
                status = BuildModelStatus.UNAVAILABLE,
                modules = emptyList(),
                diagnostics = model.diagnostics.map { diagnostic ->
                    BuildModelDiagnostic(
                        code = diagnostic.code ?: "typescript.modelUnavailable",
                        message = diagnostic.message,
                        severity = diagnostic.severity,
                    )
                },
                attributes = providerAttributes(model.projectionHash),
            )
        }
        val moduleIds = model.projects.associate { it.configPath to moduleId(it.configPath) }
        val modules = model.projects.map { project ->
            val sourceRoot = project.compilerOptions.rootDirectory ?: project.configPath.parent.orEmptyPath()
            val outputDirectories = listOfNotNull(project.compilerOptions.outputDirectory).distinct()
            BuildModule(
                id = moduleIds.getValue(project.configPath),
                name = project.configPath.parent?.fileName?.toString()?.takeIf(String::isNotBlank)
                    ?: project.configPath.fileName.toString(),
                root = project.configPath.parent.orEmptyPath(),
                sourceSets = listOf(BuildSourceSet(
                    id = "main",
                    kind = SourceSetKind.MAIN,
                    sourceRoots = listOf(sourceRoot),
                    outputDirectories = outputDirectories,
                    moduleDependencies = project.references.map { reference ->
                        BuildDependency(
                            targetModuleId = requireNotNull(moduleIds[reference]) {
                                "TypeScript model contains unresolved project reference: $reference"
                            },
                            scope = DependencyScope.COMPILE,
                        )
                    },
                    attributes = sortedMapOf(
                        "allowJs" to project.compilerOptions.allowJs.toString(),
                        "checkJs" to project.compilerOptions.checkJs.toString(),
                        "config" to ProtocolPath.serialize(project.configPath),
                        "configKind" to project.kind.name.lowercase(),
                        "packageType" to project.packageType.name.lowercase(),
                    ),
                )),
                attributes = sortedMapOf(
                    "backend" to "declarative-jsonc",
                    "config" to ProtocolPath.serialize(project.configPath),
                    "extendsCount" to project.extendsConfigs.size.toString(),
                    "referenceCount" to project.references.size.toString(),
                ),
            )
        }
        return BuildModel(
            providerId = id,
            status = BuildModelStatus.AVAILABLE,
            modules = modules.sortedBy(BuildModule::id),
            diagnostics = emptyList(),
            attributes = providerAttributes(model.projectionHash) + sortedMapOf(
                "configEvidenceCount" to model.evidence.size.toString(),
                "projectCount" to model.projects.size.toString(),
            ),
        )
    }

    private fun providerAttributes(projectionHash: String): Map<String, String> = sortedMapOf(
        "buildCodeExecution" to "denied",
        "credentialsAccess" to "denied",
        "networkAccess" to "denied",
        "projectionHash" to projectionHash,
        "providerVersion" to "1",
    )

    private fun moduleId(path: Path): String = "typescript:${ProtocolPath.serialize(path)}"
    private fun Path?.orEmptyPath(): Path = this ?: Path.of("")
}

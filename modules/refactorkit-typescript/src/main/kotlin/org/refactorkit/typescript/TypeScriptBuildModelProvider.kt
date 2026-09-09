package org.refactorkit.typescript

import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildLanguageEvidence
import org.refactorkit.core.BuildLanguageFacet
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelProvider
import org.refactorkit.core.BuildModelRequest
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.DependencyScope
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.SourceFile
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.SourceSetKind
import java.nio.file.Path

/** Non-executable Build Model SPI projection for bounded tsconfig/jsconfig discovery. */
object TypeScriptBuildModelIntegration {
    fun attach(
        snapshot: ProjectSnapshot,
        provider: TypeScriptBuildModelProvider = TypeScriptBuildModelProvider(),
    ): ProjectSnapshot = provider.attach(snapshot)
}

class TypeScriptBuildModelProvider(
    private val builder: TypeScriptProjectModelBuilder = TypeScriptProjectModelBuilder(),
) : BuildModelProvider {
    override val id: String = TypeScriptProjectModel.PROVIDER_ID

    override fun discover(request: BuildModelRequest): BuildModel = project(builder.build(request.workspaceRoot))

    internal fun attach(snapshot: ProjectSnapshot): ProjectSnapshot {
        val model = builder.build(snapshot.workspace.root)
        var projection = project(model)
        val inputs = if (model.status == TypeScriptProjectModelStatus.AVAILABLE) runCatching {
            val captured = snapshot.trackedFiles.associateBy { it.path.normalize() }
            val root = snapshot.workspace.root.toAbsolutePath().normalize()
            model.evidence.mapNotNull { evidence ->
                require(!evidence.path.isAbsolute && !evidence.path.startsWith("..") && evidence.size in 0..16_777_216)
                var path = root
                evidence.path.forEach { part -> path = path.resolve(part); require(!Files.isSymbolicLink(path)) }
                val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(evidence.size.toInt() + 1) }
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                require(bytes.size.toLong() == evidence.size && hash == evidence.sha256)
                val text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
                val existing = captured[evidence.path]
                if (existing != null) { require(existing.content == text); null } else SourceFile(evidence.path, text, "jsonc")
            }
        }.getOrElse {
            projection = BuildModel(id, BuildModelStatus.UNAVAILABLE, emptyList(), listOf(BuildModelDiagnostic(
                "typescript.modelEvidenceChanged", "TypeScript model input cannot be captured exactly and safely", severity = Diagnostic.Severity.ERROR,
            )), providerAttributes(""))
            emptyList()
        } else emptyList()
        return snapshot.copy(buildModels = (snapshot.buildModels.filterNot { it.providerId == id } + projection).sortedBy(BuildModel::providerId),
            auxiliaryFiles = (snapshot.auxiliaryFiles + inputs).sortedBy { it.path.toString() })
    }

    private fun project(model: TypeScriptProjectModel): BuildModel {
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
                        "packageExportsDeclared" to project.packageExportsDeclared.toString(),
                        "packageTypesDeclared" to project.packageTypesDeclared.toString(),
                    ),
                    languageFacets = buildList {
                        add(BuildLanguageFacet(
                            languageId = "typescript",
                            platformId = "ecmascript",
                            compilerId = "typescript",
                            evidence = BuildLanguageEvidence.DECLARED,
                        ))
                        if (project.compilerOptions.allowJs == true) add(BuildLanguageFacet(
                            languageId = "javascript",
                            platformId = "ecmascript",
                            compilerId = "typescript",
                            evidence = BuildLanguageEvidence.DECLARED,
                        ))
                    },
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

package org.refactorkit.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.ProtocolLimits
import org.refactorkit.core.BuildResolvedArtifact
import org.refactorkit.core.BuildSourceRootEvidence
import org.refactorkit.java.JavaContextClasspathEntry
import org.refactorkit.java.JavaEffectiveProjectContextProvider
import org.refactorkit.java.JavaProjectContextResolution

@Serializable
data class BuildModelSummaryLimitsDto(
    val maxModels: Int,
    val maxModules: Int,
    val maxSourceSetsPerModule: Int,
    val maxRootsPerSourceSet: Int,
    val maxModuleDependenciesPerSourceSet: Int,
    val maxDiagnostics: Int,
)

@Serializable
data class BuildModelDiagnosticSummaryDto(
    val code: String,
    val severity: String,
    val moduleId: String? = null,
)

@Serializable
data class BuildModuleDependencySummaryDto(
    val moduleId: String,
    val scope: String,
)

@Serializable
data class BuildSourceSetSummaryDto(
    val id: String,
    val kind: String,
    val sourceRoots: List<String>,
    val generatedSourceRoots: List<String>,
    val outputDirectories: List<String>,
    val moduleDependencies: List<BuildModuleDependencySummaryDto>,
    val truncated: Boolean,
)

@Serializable
data class BuildModuleSummaryDto(
    val id: String,
    val name: String,
    val root: String,
    val sourceSets: List<BuildSourceSetSummaryDto>,
    val truncated: Boolean,
    val buildRoot: String? = null,
    val projectPath: String? = null,
)

@Serializable
data class BuildModelSummaryDto(
    val providerId: String,
    val status: String,
    val ecosystem: String,
    val strategy: String,
    val providers: String,
    val buildCodeExecution: String,
    val credentialsAccess: String,
    val networkDefault: String,
    val networkAccess: String,
    val activeProfiles: List<String>,
    val inactiveProfiles: List<String>,
    val diagnostics: List<BuildModelDiagnosticSummaryDto>,
    val modules: List<BuildModuleSummaryDto>,
    val truncated: Boolean,
)

@Serializable
data class ProjectModuleSummaryDto(
    val name: String,
    val root: String,
    /** Stable module selector when this scanned module has one unambiguous build-model owner. */
    val id: String? = null,
    val providerId: String? = null,
    /** Gradle composite identity; absent for non-Gradle or ambiguous ownership. */
    val buildRoot: String? = null,
    val projectPath: String? = null,
)

@Serializable
data class ProjectSummaryResponseDto(
    val root: String,
    val fileCount: Int,
    val snapshotHash: String,
    val buildModels: List<BuildModelSummaryDto>,
    val buildModelsTruncated: Boolean,
    val buildModelLimits: BuildModelSummaryLimitsDto,
    val modules: List<ProjectModuleSummaryDto>,
    val modulesTruncated: Boolean,
)

@Serializable
data class JavaContextRefusalDto(val code: String, val message: String)

@Serializable
data class JavaContextEvidenceDto(val path: String, val kind: String, val fingerprint: String)

@Serializable
data class JavaContextSdkDto(
    val home: String,
    val sourceRelease: Int,
    val platformRelease: Int,
    val identitySha256: String,
    val releaseMetadataSha256: String,
    val signatureArchive: String,
    val signatureArchiveSha256: String,
)

@Serializable
data class JavaContextProviderDto(
    val providerId: String,
    val status: String,
    val strategy: String,
    val buildCodeExecution: String,
    val credentialsAccess: String,
    val networkAccess: String,
    val diagnostics: List<String>,
)

@Serializable
data class JavaContextClasspathEntryDto(
    val path: String,
    val providerId: String,
    val moduleId: String,
    val sourceSetId: String,
    val dependencyScope: String?,
    val fingerprint: String?,
    val artifact: JavaContextResolvedArtifactDto?,
)

@Serializable
data class JavaContextCoordinatesDto(val groupId: String, val artifactId: String, val version: String)

@Serializable
data class JavaContextResolvedArtifactDto(
    val path: String,
    val coordinates: JavaContextCoordinatesDto,
    val extension: String,
    val classifier: String?,
    val scope: String?,
    val transitive: Boolean,
    val fingerprint: String?,
)

@Serializable
data class JavaContextSourceRootEvidenceDto(
    val path: String,
    val origin: String,
    val providerId: String,
    val descriptorPath: String?,
    val profileId: String?,
    val pluginId: String?,
    val executionId: String?,
    val goal: String?,
    val phase: String?,
    val availability: String,
)

@Serializable
data class JavaContextCompilerOptionDto(
    val name: String,
    val value: String,
    val origin: String,
    val redacted: Boolean,
)

@Serializable
data class JavaContextAnnotationProcessorsDto(
    val availability: String,
    val processorPath: List<String>,
    val dependencies: List<JavaContextResolvedArtifactDto>,
    val processorNames: List<String>,
    val options: Map<String, String>,
    val mode: String?,
    val refusalCode: String?,
)

@Serializable
data class JavaContextRequiresDto(val moduleName: String, val modifiers: List<String>)

@Serializable
data class JavaContextExportsDto(val packageName: String, val targets: List<String>)

@Serializable
data class JavaContextOpensDto(val packageName: String, val targets: List<String>)

@Serializable
data class JavaContextProvidesDto(val serviceName: String, val implementations: List<String>)

@Serializable
data class JavaContextModuleDescriptorDto(
    val path: String,
    val moduleName: String,
    val open: Boolean,
    val requires: List<JavaContextRequiresDto>,
    val exports: List<JavaContextExportsDto>,
    val contentSha256: String,
    val opens: List<JavaContextOpensDto>,
    val uses: List<String>,
    val provides: List<JavaContextProvidesDto>,
)

@Serializable
data class JavaContextModulePathEntryDto(val path: String, val moduleNames: List<String>, val fingerprint: String?)

@Serializable
data class JavaContextJpmsDto(
    val availability: String,
    val modulePath: List<JavaContextModulePathEntryDto>,
    val descriptors: List<JavaContextModuleDescriptorDto>,
    val refusalCode: String?,
)

@Serializable
data class JavaContextSourceSetDto(
    val id: String,
    val kind: String,
    val sourceLevel: Int,
    val release: Int?,
    val sourceRoots: List<String>,
    val generatedSourceRoots: List<String>,
    val outputDirectories: List<String>,
    val analysisSourceRoots: List<String>,
    val compileClasspath: List<JavaContextClasspathEntryDto>,
    val runtimeClasspath: List<JavaContextClasspathEntryDto>,
    val moduleDependencies: List<BuildModuleDependencySummaryDto>,
    val sourceRootEvidence: List<JavaContextSourceRootEvidenceDto>,
    val compilerOptionsAvailability: String,
    val compilerOptionsRefusalCode: String?,
    val compilerOptions: List<JavaContextCompilerOptionDto>,
    val resolvedArtifactAvailability: String,
    val resolvedArtifactRefusalCode: String?,
    val resolvedArtifacts: List<JavaContextResolvedArtifactDto>,
    val annotationProcessors: JavaContextAnnotationProcessorsDto,
    val jpms: JavaContextJpmsDto,
)

@Serializable
data class JavaContextModuleDto(
    val providerId: String,
    val id: String,
    val name: String,
    val root: String,
    val coordinates: JavaContextCoordinatesDto?,
    val sourceSets: List<JavaContextSourceSetDto>,
)

@Serializable
data class JavaContextSourceAssociationDto(
    val path: String,
    val symbol: String?,
    val providerId: String?,
    val moduleId: String,
    val sourceSetId: String,
    val sourceRoot: String,
    val generated: Boolean,
    val bindingKey: String?,
    val evidence: String,
)

@Serializable
data class JavaContextAnalysisDto(
    val backend: String,
    val snapshotHash: String,
    val platformIdentitiesByRelease: Map<String, String>,
    val warningCount: Int,
)

/** Additive API 0.2 contract; source contents, credentials and mutation leases are absent. */
@Serializable
data class JavaProjectContextResponseDto(
    val schemaVersion: String = "java-project-context-v2",
    val status: String,
    val snapshotHash: String?,
    val contextHash: String? = null,
    val sdk: List<JavaContextSdkDto> = emptyList(),
    val providers: List<JavaContextProviderDto> = emptyList(),
    val modules: List<JavaContextModuleDto> = emptyList(),
    val buildEvidence: List<JavaContextEvidenceDto> = emptyList(),
    val selectedSource: JavaContextSourceAssociationDto? = null,
    val analysis: JavaContextAnalysisDto? = null,
    val refusal: JavaContextRefusalDto? = null,
)

internal fun javaProjectContextToDto(
    result: JavaProjectContextResolution,
    schemaVersion: String = "java-project-context-v2",
): JavaProjectContextResponseDto = when (result) {
    is JavaProjectContextResolution.Refused -> JavaProjectContextResponseDto(
        schemaVersion = schemaVersion,
        status = "refused", snapshotHash = result.snapshotHash,
        refusal = JavaContextRefusalDto(result.code, result.message),
    )
    is JavaProjectContextResolution.Available -> {
        val view = result.view
        val snapshot = view.snapshot
        fun fingerprint(path: java.nio.file.Path): String? {
            val absolute = if (path.isAbsolute) path.toAbsolutePath().normalize()
            else snapshot.workspace.root.resolve(path).toAbsolutePath().normalize()
            return snapshot.classpathEvidence.firstOrNull { evidence ->
                val evidencePath = if (evidence.path.isAbsolute) evidence.path.toAbsolutePath().normalize()
                else snapshot.workspace.root.resolve(evidence.path).toAbsolutePath().normalize()
                evidencePath == absolute
            }?.fingerprint
        }
        fun artifact(value: BuildResolvedArtifact) = JavaContextResolvedArtifactDto(
            ProtocolPath.serialize(value.path),
            JavaContextCoordinatesDto(value.coordinates.groupId, value.coordinates.artifactId, value.coordinates.version),
            value.extension, value.classifier, value.scope?.name?.lowercase(), value.transitive, fingerprint(value.path),
        )
        fun entry(provider: String, value: JavaContextClasspathEntry) = JavaContextClasspathEntryDto(
            ProtocolPath.serialize(value.path), provider, value.moduleId, value.sourceSetId,
            value.dependencyScope?.name?.lowercase(),
            fingerprint(value.path), value.artifact?.let(::artifact),
        )
        JavaProjectContextResponseDto(
            schemaVersion = schemaVersion,
            status = if (view.metadataAvailable) "available" else "partial",
            snapshotHash = snapshot.hash, contextHash = view.contextHash,
            sdk = view.sdk.map { authority -> JavaContextSdkDto(
                authority.platformHome.toString(), authority.release, authority.platformRelease,
                authority.identitySha256, authority.jdkReleaseMetadataSha256,
                authority.signatureArchive.toString(), authority.signatureArchiveSha256,
            ) },
            providers = view.buildModels.map { model ->
                val sourceAnalysisOnlyRefusal = view.semanticAnalysisRefusalCode == "java.context.sourceAnalysisUnqualified" &&
                    model.status == org.refactorkit.core.BuildModelStatus.PARTIAL && model.diagnostics.isNotEmpty() &&
                    model.diagnostics.all { it.code == "sourceLevel.unavailable" }
                JavaContextProviderDto(
                    model.providerId,
                    if (sourceAnalysisOnlyRefusal) "available" else model.status.name.lowercase().replace('_', '-'),
                    model.attributes["strategy"].orEmpty(), model.attributes["buildCodeExecution"].orEmpty(),
                    model.attributes["credentialsAccess"].orEmpty(), model.attributes["networkAccess"].orEmpty(),
                    model.diagnostics.filterNot { sourceAnalysisOnlyRefusal && it.code == "sourceLevel.unavailable" }
                        .map { it.code },
                )
            },
            modules = view.buildModels.flatMap { model -> model.modules.map { module ->
                JavaContextModuleDto(model.providerId, module.id, module.name,
                    ProtocolPath.serialize(snapshot.workspace.root.relativize(module.root)),
                    module.coordinates?.let { JavaContextCoordinatesDto(it.groupId, it.artifactId, it.version) },
                    module.sourceSets.map { sourceSet ->
                        val environment = view.environments.getValue(JavaEffectiveProjectContextProvider.key(
                            model.providerId, module.id, sourceSet.id,
                        ))
                        val schemaCompilerRelease = if (schemaVersion in
                            setOf("java-project-context-v6", "java-project-context-v7")
                        ) {
                            sourceSet.compilerOptions.asSequence()
                                .filter { it.name.equals("release", ignoreCase = true) && !it.redacted }
                                .mapNotNull { it.value.toIntOrNull() }
                                .filter { it == environment.sourceLevel }
                                .distinct()
                                .singleOrNull()
                        } else null
                        JavaContextSourceSetDto(sourceSet.id, sourceSet.kind.name.lowercase().replace('_', '-'),
                            environment.sourceLevel, sourceSet.attributes["java.release"]?.toIntOrNull() ?: schemaCompilerRelease,
                            sourceSet.sourceRoots.map(ProtocolPath::serialize),
                            sourceSet.generatedSourceRoots.map(ProtocolPath::serialize),
                            sourceSet.outputDirectories.map(ProtocolPath::serialize),
                            environment.sourceRoots.map(ProtocolPath::serialize),
                            environment.compileClasspath.map { entry(model.providerId, it) },
                            environment.runtimeClasspath.map { entry(model.providerId, it) },
                            sourceSet.moduleDependencies.map { BuildModuleDependencySummaryDto(it.targetModuleId, it.scope.name.lowercase()) },
                            sourceSet.sourceRootEvidence.map { it.toJavaContextDto() },
                            sourceSet.compilerOptionsAvailability.name.lowercase().replace('_', '-'),
                            sourceSet.compilerOptionsRefusalCode,
                            sourceSet.compilerOptions.map { JavaContextCompilerOptionDto(it.name, it.value, it.origin, it.redacted) },
                            sourceSet.resolvedArtifactAvailability.name.lowercase().replace('_', '-'),
                            sourceSet.resolvedArtifactRefusalCode,
                            sourceSet.resolvedArtifacts.map(::artifact),
                            sourceSet.annotationProcessors?.let { processors -> JavaContextAnnotationProcessorsDto(
                                processors.availability.name.lowercase().replace('_', '-'),
                                processors.processorPathEntries.map(ProtocolPath::serialize),
                                processors.processorPath.map(::artifact), processors.processorNames,
                                processors.options, processors.mode, processors.refusalCode,
                            ) } ?: JavaContextAnnotationProcessorsDto(
                                "unavailable", emptyList(), emptyList(), emptyList(), emptyMap(), null,
                                "java.context.annotationProcessorContextUnavailable",
                            ),
                            JavaContextJpmsDto(
                                environment.jpms.availability.name.lowercase().replace('_', '-'),
                                environment.jpms.modulePath.map { JavaContextModulePathEntryDto(
                                    ProtocolPath.serialize(it.path), it.moduleNames, it.fingerprint,
                                ) },
                                environment.jpms.descriptors.map { descriptor -> JavaContextModuleDescriptorDto(
                                    ProtocolPath.serialize(descriptor.path), descriptor.moduleName, descriptor.open,
                                    descriptor.requires.map { JavaContextRequiresDto(it.moduleName, it.modifiers) },
                                    descriptor.exports.map { JavaContextExportsDto(it.packageName, it.targets) },
                                    descriptor.contentSha256,
                                    descriptor.opens.map { JavaContextOpensDto(it.packageName, it.targets) },
                                    descriptor.uses,
                                    descriptor.provides.map { JavaContextProvidesDto(it.serviceName, it.implementations) },
                                ) },
                                environment.jpms.refusalCode,
                            ),
                        )
                    })
            } },
            buildEvidence = snapshot.classpathEvidence.map {
                JavaContextEvidenceDto(ProtocolPath.serialize(it.path), it.kind.name.lowercase().replace('_', '-'), it.fingerprint)
            },
            selectedSource = view.selectedSource?.let {
                JavaContextSourceAssociationDto(ProtocolPath.serialize(it.path), it.symbolQualifiedName,
                    it.providerId, it.moduleId, it.sourceSetId, ProtocolPath.serialize(it.sourceRoot), it.generated,
                    it.bindingKey, it.evidence)
            },
            analysis = view.analysis?.let {
                JavaContextAnalysisDto("eclipse-jdt", it.analysis.snapshotHash,
                    it.platformIdentitiesByRelease.mapKeys { entry -> entry.key.toString() }, it.analysis.warnings.size)
            },
        )
    }
}

private fun BuildSourceRootEvidence.toJavaContextDto() = JavaContextSourceRootEvidenceDto(
    ProtocolPath.serialize(path), origin.name.lowercase().replace('_', '-'), providerId,
    descriptorPath?.let(ProtocolPath::serialize), profileId, pluginId, executionId, goal, phase,
    availability.name.lowercase().replace('_', '-'),
)

/** NDJSON response framing limit, aligned with the IDE consumer's 2 MiB budget. */
internal fun encodeJavaProjectContext(
    result: JavaProjectContextResolution,
    json: Json,
    schemaVersion: String = "java-project-context-v2",
    includeGradleBuildTreeProjectPaths: Boolean = false,
    includeMavenTestJvmSelection: Boolean = false,
): JsonElement {
    val mavenJvmContextAttribute = if (includeMavenTestJvmSelection) {
        org.refactorkit.java.MAVEN_TEST_JVM_CONTEXT_WITH_SELECTION_ATTRIBUTE
    } else "java.maven.test-jvm.context"

    fun encode(value: JavaProjectContextResolution): JsonElement {
        var encoded = json.encodeToJsonElement(javaProjectContextToDto(value, schemaVersion))
        if (schemaVersion !in setOf("java-project-context-v5", "java-project-context-v6", "java-project-context-v7")) {
            val available = value as? JavaProjectContextResolution.Available
            if (available != null) {
                val modules = encoded.jsonObject.getValue("modules").jsonArray.map { element ->
                    val module = element.jsonObject
                    val sourceSets = module.getValue("sourceSets").jsonArray.map { sourceSetElement ->
                        val sourceSet = sourceSetElement.jsonObject
                        val jpms = sourceSet.getValue("jpms").jsonObject
                        val descriptors = jpms.getValue("descriptors").jsonArray.map { descriptorElement ->
                            val descriptor = descriptorElement.jsonObject
                            JsonObject(descriptor - "opens" - "uses" - "provides")
                        }
                        val hasUnprojectedDirectives = jpms.getValue("descriptors").jsonArray.any { descriptorElement ->
                            val descriptor = descriptorElement.jsonObject
                            (descriptor["opens"] as? JsonArray)?.isNotEmpty() == true ||
                                (descriptor["uses"] as? JsonArray)?.isNotEmpty() == true ||
                                (descriptor["provides"] as? JsonArray)?.isNotEmpty() == true
                        }
                        var projectedJpms = JsonObject(jpms + ("descriptors" to JsonArray(descriptors)))
                        if (hasUnprojectedDirectives) {
                            projectedJpms = JsonObject(projectedJpms + mapOf(
                                "availability" to JsonPrimitive("partial"),
                                "refusalCode" to JsonPrimitive("java.context.jpmsDescriptorIncomplete"),
                            ))
                        }
                        JsonObject(sourceSet + ("jpms" to projectedJpms))
                    }
                    JsonObject(module + ("sourceSets" to JsonArray(sourceSets)))
                }
                encoded = JsonObject(encoded.jsonObject + ("modules" to JsonArray(modules)))
            }
        }
        if (schemaVersion in setOf(
                "java-project-context-v3", "java-project-context-v4", "java-project-context-v5", "java-project-context-v6", "java-project-context-v7",
            )) {
            val available = value as? JavaProjectContextResolution.Available ?: return encoded
            val attributesByIdentity = available.view.snapshot.buildModels.flatMap { model -> model.modules.map { module ->
                (model.providerId to module.id) to module.attributes
            } }.toMap()
            val sourceSetsByIdentity = available.view.snapshot.buildModels.flatMap { model -> model.modules.flatMap { module ->
                module.sourceSets.map { sourceSet ->
                    Triple(model.providerId, module.id, sourceSet.id) to sourceSet
                }
            } }.toMap()
            val modules = encoded.jsonObject.getValue("modules").jsonArray.map { element ->
                val row = element.jsonObject
                val identity = row.getValue("providerId").jsonPrimitive.content to row.getValue("id").jsonPrimitive.content
                var projected = row
                val attributes = attributesByIdentity[identity].orEmpty()
                val buildRoot = attributes["gradle.buildRoot"]
                val projectPath = attributes["gradle.projectPath"]
                if (buildRoot != null && projectPath != null) {
                    projected = JsonObject(row + buildMap {
                        put("buildRoot", JsonPrimitive(buildRoot))
                        put("projectPath", JsonPrimitive(projectPath))
                        if (includeGradleBuildTreeProjectPaths) {
                            attributes["gradle.buildTreeProjectPath"]?.let { put("buildTreeProjectPath", JsonPrimitive(it)) }
                        }
                    })
                }
        if (schemaVersion in setOf("java-project-context-v4", "java-project-context-v5", "java-project-context-v6", "java-project-context-v7")) {
                    val sourceSets = projected.getValue("sourceSets").jsonArray.map { sourceSetElement ->
                        val sourceSetRow = sourceSetElement.jsonObject
                        val sourceSetId = sourceSetRow.getValue("id").jsonPrimitive.content
                        val sourceSet = sourceSetsByIdentity[Triple(identity.first, identity.second, sourceSetId)]
                        val failsafeFact = when {
                            identity.first != "maven-effective-v1" -> buildJsonObject {
                                put("availability", "not-applicable"); put("refusalCode", JsonNull)
                                put("executions", JsonArray(emptyList()))
                            }
                            sourceSet == null -> buildJsonObject {
                                put("availability", "unavailable"); put("refusalCode", "java.context.mavenFailsafeMetadataUnavailable")
                                put("executions", JsonArray(emptyList()))
                            }
                            sourceSet.attributes["java.maven.failsafe.context"] == null -> buildJsonObject {
                                put("availability", "not-declared"); put("refusalCode", JsonNull)
                                put("executions", JsonArray(emptyList()))
                            }
                            else -> runCatching {
                                Json.parseToJsonElement(requireNotNull(sourceSet.attributes["java.maven.failsafe.context"])).jsonObject
                            }.getOrElse { buildJsonObject {
                                put("availability", "unavailable"); put("refusalCode", "java.context.mavenFailsafeMetadataMalformed")
                                put("executions", JsonArray(emptyList()))
                            } }
                        }
                        val withFailsafe = JsonObject(sourceSetRow + ("mavenFailsafe" to failsafeFact))
                        if (schemaVersion != "java-project-context-v7") return@map withFailsafe
                        val testJvmContext = sourceSet?.attributes?.get(mavenJvmContextAttribute)
                        val testJvmFact = when {
                            identity.first != "maven-effective-v1" -> buildJsonObject {
                                put("availability", "not-applicable"); put("mode", "unknown")
                                put("refusalCode", JsonNull); put("executions", JsonArray(emptyList()))
                            }
                            sourceSet == null -> buildJsonObject {
                                put("availability", "unavailable"); put("mode", "unknown")
                                put("refusalCode", "java.context.mavenTestJvmMetadataUnavailable")
                                put("executions", JsonArray(emptyList()))
                            }
                            sourceSet.attributes["visibility"] == "main" -> buildJsonObject {
                                put("availability", "not-applicable"); put("mode", "unknown")
                                put("refusalCode", JsonNull); put("executions", JsonArray(emptyList()))
                            }
                            testJvmContext == null -> buildJsonObject {
                                put("availability", "unavailable"); put("mode", "unknown")
                                put("refusalCode", "java.context.mavenTestJvmMetadataUnavailable")
                                put("executions", JsonArray(emptyList()))
                            }
                            else -> runCatching {
                                Json.parseToJsonElement(testJvmContext).jsonObject
                            }.getOrElse { buildJsonObject {
                                put("availability", "unavailable"); put("mode", "unknown")
                                put("refusalCode", "java.context.mavenTestJvmMetadataMalformed")
                                put("executions", JsonArray(emptyList()))
                            } }
                        }
                        JsonObject(withFailsafe + ("mavenTestJvm" to testJvmFact))
                    }
                    projected = JsonObject(projected + ("sourceSets" to JsonArray(sourceSets)))
                }
                projected
            }
            return JsonObject(encoded.jsonObject + ("modules" to JsonArray(modules)))
        }
        val selected = encoded.jsonObject["selectedSource"] as? JsonObject ?: return encoded
        return JsonObject(encoded.jsonObject + ("selectedSource" to JsonObject(selected - "providerId")))
    }
    var response = encode(result)
    if (schemaVersion in setOf("java-project-context-v6", "java-project-context-v7")) {
        val semanticAnalysis = when (result) {
            is JavaProjectContextResolution.Available -> {
                val refusalCode = result.view.semanticAnalysisRefusalCode
                if (refusalCode == null) "available" to null else "unqualified" to refusalCode
            }
            is JavaProjectContextResolution.Refused -> if (result.code == "java.context.sourceAnalysisUnqualified") {
                "unqualified" to result.code
            } else "available" to null
        }
        response = JsonObject(response.jsonObject + ("semanticAnalysis" to buildJsonObject {
            put("availability", semanticAnalysis.first)
            put("refusalCode", semanticAnalysis.second?.let { JsonPrimitive(it) } ?: JsonNull)
        }))
    }
    // Reserve the maximum request size for its echoed JSON-RPC ID and envelope.
    // Oversize contexts refuse rather than truncate.
    val maximumPayloadBytes = 2_097_152 - ProtocolLimits.MAX_REQUEST_BYTES - 1_024
    if (response.toString().toByteArray(Charsets.UTF_8).size <= maximumPayloadBytes) return response
    val hash = (result as? JavaProjectContextResolution.Available)?.view?.snapshot?.hash
    return encode(JavaProjectContextResolution.Refused(
        hash, "java.context.transportLimitExceeded", "Complete project context exceeds the 2 MiB transport limit",
    ))
}

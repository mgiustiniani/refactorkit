package org.refactorkit.kotlin

import org.refactorkit.core.BuildDependency
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelDiagnostic
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.BuildSourceSet
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolLimits
import java.nio.file.Path
import java.security.MessageDigest

/** Attaches a hash-bound, non-executable Kotlin/JVM view of existing JVM build models. */
object KotlinJvmBuildModelIntegration {
    fun attach(snapshot: ProjectSnapshot, toolchain: KotlinSemanticToolchain): ProjectSnapshot {
        val base = snapshot.copy(buildModels = snapshot.buildModels.filterNot { it.providerId == KotlinJvmBuildModelProjector.PROVIDER_ID })
        val model = KotlinJvmBuildModelProjector().project(base, toolchain)
        return base.copy(buildModels = (base.buildModels + model).sortedBy(BuildModel::providerId))
    }
}

/**
 * Projects Kotlin source ownership from the language-neutral Build Model SPI.
 * It reads no descriptors and executes no build/compiler code; Maven and Gradle
 * interpretation remains owned by their existing providers.
 */
class KotlinJvmBuildModelProjector {
    fun project(snapshot: ProjectSnapshot, toolchain: KotlinSemanticToolchain): BuildModel {
        val baseModels = snapshot.buildModels.filterNot { it.providerId == PROVIDER_ID }
            .filter(::isJvmBuildModel).sortedBy(BuildModel::providerId)
        if (baseModels.size > ProtocolLimits.MAX_BUILD_MODELS) return refused(
            "kotlin.buildModelLimit",
            "Kotlin/JVM base build-model count exceeds ${ProtocolLimits.MAX_BUILD_MODELS}",
            toolchain,
        )
        if (baseModels.sumOf { it.modules.size } > ProtocolLimits.MAX_BUILD_MODULES) return refused(
            "kotlin.buildModuleLimit",
            "Kotlin/JVM base module count exceeds ${ProtocolLimits.MAX_BUILD_MODULES}",
            toolchain,
        )

        val kotlinFiles = snapshot.files.filter { file ->
            file.languageId == "kotlin" && file.path.fileName.toString().endsWith(".kt")
        }
        val scripts = snapshot.files.filter { file ->
            file.languageId == "kotlin" && file.path.fileName.toString().endsWith(".kts")
        }
        val diagnostics = mutableListOf<BuildModelDiagnostic>()
        val modules = mutableListOf<BuildModule>()
        var strongestStatus = BuildModelStatus.AVAILABLE

        baseModels.forEach { model ->
            val projectedForModel = mutableListOf<BuildModule>()
            val projectedIds = model.modules.associate { module -> module.id to moduleId(model.providerId, module.id) }
            model.modules.forEach { module ->
                if (module.sourceSets.size > ProtocolLimits.MAX_BUILD_SOURCE_SETS_PER_MODULE) {
                    diagnostics += diagnostic(
                        "kotlin.buildSourceSetLimit",
                        "Kotlin/JVM source-set count exceeds the bounded limit",
                        projectedIds.getValue(module.id),
                    )
                    strongestStatus = BuildModelStatus.EXECUTION_REFUSED
                    return@forEach
                }
                val projectedSets = module.sourceSets.mapNotNull { sourceSet ->
                    if (sourceSet.sourceRoots.size > ProtocolLimits.MAX_BUILD_ROOTS_PER_SOURCE_SET) {
                        diagnostics += diagnostic(
                            "kotlin.buildSourceRootLimit",
                            "Kotlin/JVM source-root count exceeds the bounded limit",
                            projectedIds.getValue(module.id),
                        )
                        strongestStatus = BuildModelStatus.EXECUTION_REFUSED
                        return@mapNotNull null
                    }
                    if (sourceSet.moduleDependencies.size > ProtocolLimits.MAX_BUILD_MODULE_DEPENDENCIES_PER_SOURCE_SET) {
                        diagnostics += diagnostic(
                            "kotlin.buildDependencyLimit",
                            "Kotlin/JVM module-dependency count exceeds the bounded limit",
                            projectedIds.getValue(module.id),
                        )
                        strongestStatus = BuildModelStatus.EXECUTION_REFUSED
                        return@mapNotNull null
                    }
                    val roots = sourceSet.sourceRoots.filter { root -> kotlinFiles.any { it.path.normalize().startsWith(root.normalize()) } }
                    if (roots.isEmpty()) return@mapNotNull null
                    val generated = sourceSet.generatedSourceRoots.filter { it in roots }
                    val platform = sourceSet.attributes["kotlin.platform"] ?: module.attributes["kotlin.platform"]
                    val jvmTarget = sourceSet.attributes["kotlin.jvmTarget"] ?: module.attributes["kotlin.jvmTarget"]
                    val sourceLevelProven = sourceSet.attributes["java.sourceLevelEvidence"] == "declared" ||
                        module.attributes["java.sourceLevelEvidence"] == "declared" ||
                        sourceSet.attributes["java.sourceLevel.status"] == "available" ||
                        module.attributes["java.sourceLevel.status"] == "available"
                    val targetJdk = sourceSet.attributes["kotlin.targetJdk"]
                        ?: module.attributes["kotlin.targetJdk"]
                        ?: sourceSet.attributes["java.sourceLevel"]?.takeIf { sourceLevelProven }
                        ?: module.attributes["java.sourceLevel"]?.takeIf { sourceLevelProven }
                    when (platform) {
                        "jvm" -> Unit
                        "unsupported" -> {
                            diagnostics += diagnostic(
                                "kotlin.platformUnsupported",
                                "Kotlin source set belongs to an unsupported non-JVM or mixed platform",
                                projectedIds.getValue(module.id),
                            )
                            strongestStatus = BuildModelStatus.EXECUTION_REFUSED
                        }
                        else -> {
                            diagnostics += diagnostic(
                                "kotlin.pluginModelPartial",
                                "Kotlin/JVM plugin identity is not proven by the declarative build model",
                                projectedIds.getValue(module.id),
                                Diagnostic.Severity.WARNING,
                            )
                            strongestStatus = combineStatus(strongestStatus, BuildModelStatus.PARTIAL)
                        }
                    }
                    if (jvmTarget == null) {
                        diagnostics += diagnostic(
                            "kotlin.jvmTargetUnavailable",
                            "Kotlin JVM bytecode target is not declared in bounded build metadata",
                            projectedIds.getValue(module.id),
                            Diagnostic.Severity.WARNING,
                        )
                        strongestStatus = combineStatus(strongestStatus, BuildModelStatus.PARTIAL)
                    }
                    if (targetJdk == null) {
                        diagnostics += diagnostic(
                            "kotlin.targetJdkUnavailable",
                            "Kotlin target JDK/release is not declared in bounded build metadata",
                            projectedIds.getValue(module.id),
                            Diagnostic.Severity.WARNING,
                        )
                        strongestStatus = combineStatus(strongestStatus, BuildModelStatus.PARTIAL)
                    }
                    BuildSourceSet(
                        id = sourceSet.id,
                        kind = sourceSet.kind,
                        sourceRoots = roots,
                        generatedSourceRoots = generated,
                        outputDirectories = sourceSet.outputDirectories,
                        classpathEntries = sourceSet.classpathEntries,
                        moduleDependencies = sourceSet.moduleDependencies.mapNotNull { dependency ->
                            projectedIds[dependency.targetModuleId]?.let { target ->
                                BuildDependency(target, dependency.scope)
                            }
                        },
                        attributes = sortedMapOf<String, String>().apply {
                            put("analysisJdk", toolchain.provenance.javaVersion)
                            put("baseModelProvider", model.providerId)
                            put("baseModelStatus", model.status.name.lowercase())
                            put("generatedMutation", "refused")
                            put("kotlin.platform", platform ?: "unproven")
                            put("scriptSemantics", "refused")
                            jvmTarget?.let { put("kotlin.jvmTarget", it) }
                            targetJdk?.let { put("kotlin.targetJdk", it) }
                        },
                    )
                }
                projectedForModel += BuildModule(
                    id = projectedIds.getValue(module.id),
                    name = module.name,
                    root = module.root,
                    sourceSets = projectedSets,
                    attributes = sortedMapOf(
                        "baseModelProvider" to model.providerId,
                        "baseModuleId" to module.id,
                        "kotlin.platform" to (module.attributes["kotlin.platform"] ?: "unproven"),
                    ),
                )
            }
            if (projectedForModel.any { it.sourceSets.isNotEmpty() }) {
                modules += projectedForModel
                strongestStatus = combineStatus(strongestStatus, model.status)
                if (model.status != BuildModelStatus.AVAILABLE) diagnostics += BuildModelDiagnostic(
                    code = "kotlin.baseModel${model.status.name.lowercase().replaceFirstChar(Char::uppercase)}",
                    message = "Kotlin/JVM projection inherits ${model.status.name.lowercase()} evidence from ${model.providerId}",
                    severity = if (model.status in setOf(BuildModelStatus.EXECUTION_REFUSED, BuildModelStatus.UNAVAILABLE)) {
                        Diagnostic.Severity.ERROR
                    } else Diagnostic.Severity.WARNING,
                )
            }
        }

        if (scripts.isNotEmpty()) {
            diagnostics += BuildModelDiagnostic(
                code = "kotlin.scriptSemanticsUnsupported",
                message = "Kotlin scripts are present but script semantics remain refused; compiler sessions must exclude .kts inputs",
                severity = Diagnostic.Severity.WARNING,
            )
            strongestStatus = combineStatus(strongestStatus, BuildModelStatus.PARTIAL)
        }
        if (modules.isEmpty()) {
            diagnostics += BuildModelDiagnostic(
                code = "kotlin.jvmSourcesMissing",
                message = "No Kotlin/JVM .kt source is owned by an available JVM build source set",
                severity = Diagnostic.Severity.INFO,
            )
            strongestStatus = combineStatus(strongestStatus, BuildModelStatus.UNAVAILABLE)
        }
        val orderedModules = modules.sortedBy(BuildModule::id)
        val orderedDiagnostics = diagnostics.distinctBy { listOf(it.code, it.moduleId, it.message) }
            .sortedWith(compareBy<BuildModelDiagnostic> { it.moduleId.orEmpty() }.thenBy { it.code })
        val projectionHash = projectionHash(snapshot.hash, toolchain.provenance.projectionHash, orderedModules, orderedDiagnostics)
        return BuildModel(
            providerId = PROVIDER_ID,
            status = strongestStatus,
            modules = orderedModules,
            diagnostics = orderedDiagnostics,
            attributes = providerAttributes(toolchain, projectionHash, baseModels),
        )
    }

    private fun isJvmBuildModel(model: BuildModel): Boolean =
        model.providerId in JVM_PROVIDER_IDS || model.attributes["ecosystem"] in setOf("maven", "gradle", "java", "jvm")

    private fun moduleId(providerId: String, moduleId: String): String = "kotlin:$providerId:$moduleId"

    private fun providerAttributes(
        toolchain: KotlinSemanticToolchain,
        projectionHash: String,
        baseModels: List<BuildModel>,
    ): Map<String, String> = sortedMapOf(
        "backend" to toolchain.provenance.backend,
        "buildCodeExecution" to "denied",
        "compilerDistributionVersion" to toolchain.provenance.compilerDistributionVersion,
        "credentialsAccess" to "denied",
        "ecosystem" to "jvm",
        "kotlinVersion" to toolchain.provenance.kotlinVersion,
        "networkAccess" to "denied",
        "projectionHash" to projectionHash,
        "providers" to baseModels.joinToString(",") { it.providerId },
        "strategy" to "kotlin-jvm-source-set-projection",
        "toolchainProjectionHash" to toolchain.provenance.projectionHash,
        "toolchainProvider" to toolchain.provenance.providerId,
    )

    private fun projectionHash(
        snapshotHash: String,
        toolchainHash: String,
        modules: List<BuildModule>,
        diagnostics: List<BuildModelDiagnostic>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) = digest.update("${value ?: ""}\u0000".toByteArray(Charsets.UTF_8))
        add(snapshotHash)
        add(toolchainHash)
        modules.forEach { module ->
            add(module.id); add(module.name); add(module.root)
            module.attributes.toSortedMap().forEach { (key, value) -> add(key); add(value) }
            module.sourceSets.sortedBy(BuildSourceSet::id).forEach { sourceSet ->
                add(sourceSet.id); add(sourceSet.kind)
                sourceSet.sourceRoots.sortedBy(Path::toString).forEach(::add)
                sourceSet.generatedSourceRoots.sortedBy(Path::toString).forEach(::add)
                sourceSet.outputDirectories.sortedBy(Path::toString).forEach(::add)
                sourceSet.classpathEntries.sortedBy(Path::toString).forEach(::add)
                sourceSet.moduleDependencies.sortedBy { it.targetModuleId }.forEach { dependency ->
                    add(dependency.targetModuleId); add(dependency.scope)
                }
                sourceSet.attributes.toSortedMap().forEach { (key, value) -> add(key); add(value) }
            }
        }
        diagnostics.forEach { diagnostic ->
            add(diagnostic.moduleId); add(diagnostic.code); add(diagnostic.severity); add(diagnostic.message)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun combineStatus(left: BuildModelStatus, right: BuildModelStatus): BuildModelStatus =
        if (statusRank(left) >= statusRank(right)) left else right

    private fun statusRank(status: BuildModelStatus): Int = when (status) {
        BuildModelStatus.AVAILABLE -> 0
        BuildModelStatus.PARTIAL -> 1
        BuildModelStatus.OFFLINE_MISSING -> 2
        BuildModelStatus.UNAVAILABLE -> 3
        BuildModelStatus.EXECUTION_REFUSED -> 4
    }

    private fun diagnostic(
        code: String,
        message: String,
        moduleId: String? = null,
        severity: Diagnostic.Severity = Diagnostic.Severity.ERROR,
    ) = BuildModelDiagnostic(code, message, moduleId, severity)

    private fun refused(code: String, message: String, toolchain: KotlinSemanticToolchain): BuildModel {
        val diagnostics = listOf(diagnostic(code, message))
        val projectionHash = projectionHash("refused", toolchain.provenance.projectionHash, emptyList(), diagnostics)
        return BuildModel(
            providerId = PROVIDER_ID,
            status = BuildModelStatus.EXECUTION_REFUSED,
            modules = emptyList(),
            diagnostics = diagnostics,
            attributes = providerAttributes(toolchain, projectionHash, emptyList()),
        )
    }

    companion object {
        const val PROVIDER_ID = "kotlin-jvm-projection-v1"
        private val JVM_PROVIDER_IDS = setOf("maven-effective-v1", "gradle-declarative-v1", "java-conventional-v1")
    }
}

package org.refactorkit.java

import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.WorkspaceEdit
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import kotlin.io.path.invariantSeparatorsPathString

/** Shared immutable operation facts, refusal identities, bounds, and pure contract helpers. */
internal object JavaRenameMavenModuleContract {
    fun availableMavenModel(snapshot: ProjectSnapshot): BuildModel {
        val model = snapshot.buildModels.singleOrNull()
            ?: refuse(SOURCE_UNRECOGNIZED, "Exactly one authoritative Maven effective build model is required")
        if (model.providerId != MAVEN_PROVIDER || model.status != BuildModelStatus.AVAILABLE) {
            refuse(
                SOURCE_UNRECOGNIZED,
                "Exactly one AVAILABLE '$MAVEN_PROVIDER' effective reactor is required",
            )
        }
        val attributes = model.attributes
        if (attributes["strategy"] != "embedded-effective-model" || attributes["networkAccess"] != "denied" ||
            attributes["buildCodeExecution"] != "denied" || attributes["credentialsAccess"] != "denied"
        ) {
            refuse(SOURCE_UNRECOGNIZED, "The Maven model does not carry denied execution, credential and network authority")
        }
        if (attributes["activeProfiles"].orEmpty().isNotEmpty()) {
            refuse(AMBIGUOUS_ORIGIN, "Profile-selected Maven origins are outside the bounded direct-child rename")
        }
        if (model.modules.isEmpty()) refuse(SOURCE_UNRECOGNIZED, "The Maven effective reactor has no direct child modules")
        return model
    }

    fun directChild(value: String): Path? = runCatching { Path.of(value) }.getOrNull()?.normalize()?.takeIf { path ->
        value.isNotBlank() && value == path.toString() && isDirectChild(path) && MODULE_DIRECTORY.matches(value)
    }

    fun requiredDirectChild(value: String, role: String): Path =
        directChild(value) ?: error("$role is not one bounded direct child")

    fun isDirectChild(path: Path): Boolean = !path.isAbsolute && path.nameCount == 1 &&
        path.toString() !in setOf(".", "..") && !path.startsWith("..")

    fun requiredHash(attributes: Map<String, String>, key: String): String =
        attributes.getValue(key).also { require(SHA256.matches(it)) { "Lease attribute '$key' is not SHA-256" } }

    fun javaPlatformHome(): Path = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    fun hashStrings(values: Iterable<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun pathString(path: Path): String = path.normalize().invariantSeparatorsPathString

    fun formatRange(range: SourceRange): String =
        "${range.start.line}:${range.start.character}-${range.end.line}:${range.end.character}"

    fun refuse(code: String, message: String): Nothing = throw PlannerRefusal(code, message)

    fun refused(snapshot: ProjectSnapshot, code: String, reason: String): PatchPlan = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = emptyList(),
        diagnosticsAfterPreview = emptyList(),
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    class PlannerRefusal(val code: String, message: String) : RuntimeException(message)

    data class RawPom(
        val path: Path,
        val content: String,
        val sha256: String,
        val project: XmlNode,
    )

    data class RawValue(
        val text: String,
        val start: Int,
        val end: Int,
        val range: SourceRange,
    ) {
        val isInterpolated: Boolean get() = "\${" in text
        fun replaceWith(value: String): TextEdit = TextEdit(range, value)
    }

    data class ModuleIdentity(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val packaging: String,
    ) {
        val coordinate: String get() = "$groupId:$artifactId:$version:$packaging"
    }

    data class DependencyOrigin(
        val pom: RawPom,
        val artifact: RawValue,
        val effectiveProof: String,
    )

    data class OriginRecord(
        val role: String,
        val pom: RawPom,
        val value: RawValue,
        val proof: String,
    )

    class StageFacts(
        val baselineManifestHash: String,
        val stagedManifestHash: String,
        val baselineTrackedHash: String,
        val stagedTrackedHash: String,
        val stagedCandidateSnapshotHash: String,
        val baselineSnapshot: ProjectSnapshot,
        val stagedSnapshot: ProjectSnapshot,
        val baselineModel: BuildModel,
        val stagedModel: BuildModel,
        baselineMavenDiagnostics: List<String>,
        stagedMavenDiagnostics: List<String>,
        baselineJdt: List<Diagnostic>,
        stagedJdt: List<Diagnostic>,
        baselineJdtCanonical: List<String>,
        stagedJdtCanonical: List<String>,
        val baselineDiagnosticsHash: String,
        val stagedDiagnosticsHash: String,
    ) {
        private val baselineMavenDiagnosticsValue = immutableList(baselineMavenDiagnostics)
        private val stagedMavenDiagnosticsValue = immutableList(stagedMavenDiagnostics)
        private val baselineJdtValue = immutableList(baselineJdt)
        private val stagedJdtValue = immutableList(stagedJdt)
        private val baselineJdtCanonicalValue = immutableList(baselineJdtCanonical)
        private val stagedJdtCanonicalValue = immutableList(stagedJdtCanonical)

        val baselineMavenDiagnostics: List<String> get() = immutableList(baselineMavenDiagnosticsValue)
        val stagedMavenDiagnostics: List<String> get() = immutableList(stagedMavenDiagnosticsValue)
        val baselineJdt: List<Diagnostic> get() = immutableList(baselineJdtValue)
        val stagedJdt: List<Diagnostic> get() = immutableList(stagedJdtValue)
        val baselineJdtCanonical: List<String> get() = immutableList(baselineJdtCanonicalValue)
        val stagedJdtCanonical: List<String> get() = immutableList(stagedJdtCanonicalValue)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is StageFacts) return false
            return baselineManifestHash == other.baselineManifestHash &&
                stagedManifestHash == other.stagedManifestHash &&
                baselineTrackedHash == other.baselineTrackedHash &&
                stagedTrackedHash == other.stagedTrackedHash &&
                stagedCandidateSnapshotHash == other.stagedCandidateSnapshotHash &&
                baselineSnapshot == other.baselineSnapshot &&
                stagedSnapshot == other.stagedSnapshot &&
                baselineModel == other.baselineModel &&
                stagedModel == other.stagedModel &&
                baselineMavenDiagnostics == other.baselineMavenDiagnostics &&
                stagedMavenDiagnostics == other.stagedMavenDiagnostics &&
                baselineJdt == other.baselineJdt &&
                stagedJdt == other.stagedJdt &&
                baselineJdtCanonical == other.baselineJdtCanonical &&
                stagedJdtCanonical == other.stagedJdtCanonical &&
                baselineDiagnosticsHash == other.baselineDiagnosticsHash &&
                stagedDiagnosticsHash == other.stagedDiagnosticsHash
        }

        override fun hashCode(): Int = valueHash(
            baselineManifestHash,
            stagedManifestHash,
            baselineTrackedHash,
            stagedTrackedHash,
            stagedCandidateSnapshotHash,
            baselineSnapshot,
            stagedSnapshot,
            baselineModel,
            stagedModel,
            baselineMavenDiagnostics,
            stagedMavenDiagnostics,
            baselineJdt,
            stagedJdt,
            baselineJdtCanonical,
            stagedJdtCanonical,
            baselineDiagnosticsHash,
            stagedDiagnosticsHash,
        )

        override fun toString(): String =
            "StageFacts(baselineManifestHash=$baselineManifestHash, stagedManifestHash=$stagedManifestHash, " +
                "baselineTrackedHash=$baselineTrackedHash, stagedTrackedHash=$stagedTrackedHash, " +
                "stagedCandidateSnapshotHash=$stagedCandidateSnapshotHash, baselineSnapshot=$baselineSnapshot, " +
                "stagedSnapshot=$stagedSnapshot, baselineModel=$baselineModel, stagedModel=$stagedModel, " +
                "baselineMavenDiagnostics=$baselineMavenDiagnostics, " +
                "stagedMavenDiagnostics=$stagedMavenDiagnostics, baselineJdt=$baselineJdt, stagedJdt=$stagedJdt, " +
                "baselineJdtCanonical=$baselineJdtCanonical, stagedJdtCanonical=$stagedJdtCanonical, " +
                "baselineDiagnosticsHash=$baselineDiagnosticsHash, stagedDiagnosticsHash=$stagedDiagnosticsHash)"
    }

    data class GateAuthority(
        val baselineSnapshotHash: String,
        val stagedCandidateSnapshotHash: String,
        val stagedSnapshotHash: String,
        val baselineTrackedHash: String,
        val stagedTrackedHash: String,
        val baselineManifestHash: String,
        val stagedManifestHash: String,
        val baselineReactorHash: String,
        val stagedReactorHash: String,
        val baselineMavenDiagnosticsHash: String,
        val stagedMavenDiagnosticsHash: String,
        val baselineJdtDiagnosticsHash: String,
        val stagedJdtDiagnosticsHash: String,
        val baselineDiagnosticsHash: String,
        val stagedDiagnosticsHash: String,
        val oldModule: Path,
        val newModule: Path,
    )

    class GateExpectation(
        val authoritativeSnapshotHash: String,
        admittedCandidateSnapshotHashes: Set<String>,
        val manifestHash: String,
        val reactorHash: String,
        val mavenDiagnosticsHash: String,
        val jdtDiagnosticsHash: String,
        val diagnosticsHash: String,
        val destinationPresent: Boolean,
    ) {
        private val admittedCandidateSnapshotHashesValue = immutableSet(admittedCandidateSnapshotHashes)

        val admittedCandidateSnapshotHashes: Set<String>
            get() = immutableSet(admittedCandidateSnapshotHashesValue)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GateExpectation) return false
            return authoritativeSnapshotHash == other.authoritativeSnapshotHash &&
                admittedCandidateSnapshotHashes == other.admittedCandidateSnapshotHashes &&
                manifestHash == other.manifestHash &&
                reactorHash == other.reactorHash &&
                mavenDiagnosticsHash == other.mavenDiagnosticsHash &&
                jdtDiagnosticsHash == other.jdtDiagnosticsHash &&
                diagnosticsHash == other.diagnosticsHash &&
                destinationPresent == other.destinationPresent
        }

        override fun hashCode(): Int = valueHash(
            authoritativeSnapshotHash,
            admittedCandidateSnapshotHashes,
            manifestHash,
            reactorHash,
            mavenDiagnosticsHash,
            jdtDiagnosticsHash,
            diagnosticsHash,
            destinationPresent,
        )

        override fun toString(): String =
            "GateExpectation(authoritativeSnapshotHash=$authoritativeSnapshotHash, " +
                "admittedCandidateSnapshotHashes=$admittedCandidateSnapshotHashes, manifestHash=$manifestHash, " +
                "reactorHash=$reactorHash, mavenDiagnosticsHash=$mavenDiagnosticsHash, " +
                "jdtDiagnosticsHash=$jdtDiagnosticsHash, diagnosticsHash=$diagnosticsHash, " +
                "destinationPresent=$destinationPresent)"
    }

    class ReactorView(modules: Map<String, ModuleView>) {
        private val modulesValue = immutableMap(modules)

        val modules: Map<String, ModuleView> get() = immutableMap(modulesValue)

        override fun equals(other: Any?): Boolean =
            this === other || other is ReactorView && modules == other.modules

        override fun hashCode(): Int = modules.hashCode()

        override fun toString(): String = "ReactorView(modules=$modules)"
    }

    class ModuleView(
        val id: String,
        val name: String,
        val root: String,
        val groupId: String,
        val artifactId: String,
        val version: String,
        val packaging: String,
        sourceSets: List<SourceSetView>,
    ) {
        private val sourceSetsValue = immutableList(sourceSets)

        val sourceSets: List<SourceSetView> get() = immutableList(sourceSetsValue)

        fun copy(
            id: String = this.id,
            name: String = this.name,
            root: String = this.root,
            groupId: String = this.groupId,
            artifactId: String = this.artifactId,
            version: String = this.version,
            packaging: String = this.packaging,
            sourceSets: List<SourceSetView> = this.sourceSets,
        ): ModuleView = ModuleView(id, name, root, groupId, artifactId, version, packaging, sourceSets)

        fun renamed(oldModule: String, newModule: String, newArtifact: String): ModuleView = copy(
            id = if (id == oldModule) newModule else id,
            name = if (name == oldModule) newModule else name,
            root = renamePath(root, oldModule, newModule),
            artifactId = newArtifact,
            sourceSets = sourceSets.map { it.renamed(oldModule, newModule) },
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ModuleView) return false
            return id == other.id &&
                name == other.name &&
                root == other.root &&
                groupId == other.groupId &&
                artifactId == other.artifactId &&
                version == other.version &&
                packaging == other.packaging &&
                sourceSets == other.sourceSets
        }

        override fun hashCode(): Int = valueHash(
            id,
            name,
            root,
            groupId,
            artifactId,
            version,
            packaging,
            sourceSets,
        )

        override fun toString(): String =
            "ModuleView(id=$id, name=$name, root=$root, groupId=$groupId, artifactId=$artifactId, " +
                "version=$version, packaging=$packaging, sourceSets=$sourceSets)"
    }

    class SourceSetView(
        val id: String,
        val kind: String,
        sourceRoots: List<String>,
        generatedSourceRoots: List<String>,
        outputDirectories: List<String>,
        classpathEntries: List<String>,
        runtimeClasspathEntries: List<String>,
        dependencies: List<Pair<String, String>>,
        attributes: Map<String, String>,
    ) {
        private val sourceRootsValue = immutableList(sourceRoots)
        private val generatedSourceRootsValue = immutableList(generatedSourceRoots)
        private val outputDirectoriesValue = immutableList(outputDirectories)
        private val classpathEntriesValue = immutableList(classpathEntries)
        private val runtimeClasspathEntriesValue = immutableList(runtimeClasspathEntries)
        private val dependenciesValue = immutableList(dependencies)
        private val attributesValue = immutableMap(attributes)

        val sourceRoots: List<String> get() = immutableList(sourceRootsValue)
        val generatedSourceRoots: List<String> get() = immutableList(generatedSourceRootsValue)
        val outputDirectories: List<String> get() = immutableList(outputDirectoriesValue)
        val classpathEntries: List<String> get() = immutableList(classpathEntriesValue)
        val runtimeClasspathEntries: List<String> get() = immutableList(runtimeClasspathEntriesValue)
        val dependencies: List<Pair<String, String>> get() = immutableList(dependenciesValue)
        val attributes: Map<String, String> get() = immutableMap(attributesValue)

        fun copy(
            id: String = this.id,
            kind: String = this.kind,
            sourceRoots: List<String> = this.sourceRoots,
            generatedSourceRoots: List<String> = this.generatedSourceRoots,
            outputDirectories: List<String> = this.outputDirectories,
            classpathEntries: List<String> = this.classpathEntries,
            runtimeClasspathEntries: List<String> = this.runtimeClasspathEntries,
            dependencies: List<Pair<String, String>> = this.dependencies,
            attributes: Map<String, String> = this.attributes,
        ): SourceSetView = SourceSetView(
            id,
            kind,
            sourceRoots,
            generatedSourceRoots,
            outputDirectories,
            classpathEntries,
            runtimeClasspathEntries,
            dependencies,
            attributes,
        )

        fun renamed(oldModule: String, newModule: String): SourceSetView = copy(
            sourceRoots = sourceRoots.map { renamePath(it, oldModule, newModule) },
            generatedSourceRoots = generatedSourceRoots.map { renamePath(it, oldModule, newModule) },
            outputDirectories = outputDirectories.map { renamePath(it, oldModule, newModule) },
            classpathEntries = classpathEntries.map { renamePath(it, oldModule, newModule) },
            runtimeClasspathEntries = runtimeClasspathEntries.map { renamePath(it, oldModule, newModule) },
            dependencies = dependencies.map { (target, scope) ->
                (if (target == oldModule) newModule else target) to scope
            },
            attributes = attributes.mapValues { (_, value) -> renamePath(value, oldModule, newModule) },
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SourceSetView) return false
            return id == other.id &&
                kind == other.kind &&
                sourceRoots == other.sourceRoots &&
                generatedSourceRoots == other.generatedSourceRoots &&
                outputDirectories == other.outputDirectories &&
                classpathEntries == other.classpathEntries &&
                runtimeClasspathEntries == other.runtimeClasspathEntries &&
                dependencies == other.dependencies &&
                attributes == other.attributes
        }

        override fun hashCode(): Int = valueHash(
            id,
            kind,
            sourceRoots,
            generatedSourceRoots,
            outputDirectories,
            classpathEntries,
            runtimeClasspathEntries,
            dependencies,
            attributes,
        )

        override fun toString(): String =
            "SourceSetView(id=$id, kind=$kind, sourceRoots=$sourceRoots, " +
                "generatedSourceRoots=$generatedSourceRoots, outputDirectories=$outputDirectories, " +
                "classpathEntries=$classpathEntries, runtimeClasspathEntries=$runtimeClasspathEntries, " +
                "dependencies=$dependencies, attributes=$attributes)"
    }

    private fun <T> immutableList(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private fun <T> immutableSet(values: Collection<T>): Set<T> =
        Collections.unmodifiableSet(LinkedHashSet(values))

    private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private fun valueHash(vararg values: Any?): Int {
        var result = values.firstOrNull()?.hashCode() ?: 0
        for (index in 1 until values.size) {
            result = 31 * result + (values[index]?.hashCode() ?: 0)
        }
        return result
    }

    fun renamePath(value: String, oldModule: String, newModule: String): String = when {
        value == oldModule -> newModule
        value.startsWith("$oldModule/") -> "$newModule/${value.removePrefix("$oldModule/")}"
        else -> value
    }

    const val OPERATION = "java.renameMavenModule"
    const val MAVEN_PROVIDER = "maven-effective-v1"
    const val LEASE_KIND = "java.renameMavenModule.maven-effective-v1"
    const val DIAGNOSTICS_GATE_ID = "java-rename-maven-module-staged-reactor-v1"
    const val FIXTURE_REPOSITORY = "fixture-repository"
    const val SOURCE_UNRECOGNIZED = "mavenOwnership.sourceUnrecognized"
    const val DESTINATION_UNRECOGNIZED = "mavenOwnership.destinationUnrecognized"
    const val DESCRIPTOR_UNAVAILABLE = "mavenOwnership.descriptorUnavailable"
    const val AMBIGUOUS_ORIGIN = "mavenOwnership.ambiguousPomOrigin"
    const val PROPERTY_MANAGED = "mavenOwnership.propertyManagedCoordinate"
    const val DIAGNOSTICS_REGRESSION = "mavenOwnership.diagnosticsRegression"
    const val OLD_MODULE_ATTRIBUTE = "oldModuleDir"
    const val NEW_MODULE_ATTRIBUTE = "newModuleDir"
    const val BASELINE_SNAPSHOT_HASH = "baselineAuthoritativeSnapshotHash"
    const val STAGED_CANDIDATE_SNAPSHOT_HASH = "stagedCandidateSnapshotHash"
    const val STAGED_SNAPSHOT_HASH = "stagedAuthoritativeSnapshotHash"
    const val BASELINE_TRACKED_HASH = "baselineTrackedInventoryHash"
    const val STAGED_TRACKED_HASH = "stagedTrackedInventoryHash"
    const val BASELINE_MANIFEST_HASH = "baselineWorkspaceManifestHash"
    const val STAGED_MANIFEST_HASH = "stagedWorkspaceManifestHash"
    const val BASELINE_REACTOR_HASH = "baselineReactorHash"
    const val STAGED_REACTOR_HASH = "stagedReactorHash"
    const val BASELINE_MAVEN_DIAGNOSTICS_HASH = "baselineMavenDiagnosticsHash"
    const val STAGED_MAVEN_DIAGNOSTICS_HASH = "stagedMavenDiagnosticsHash"
    const val BASELINE_JDT_DIAGNOSTICS_HASH = "baselineJdtDiagnosticsHash"
    const val STAGED_JDT_DIAGNOSTICS_HASH = "stagedJdtDiagnosticsHash"
    const val BASELINE_DIAGNOSTICS_HASH = "baselineExactDiagnosticsHash"
    const val STAGED_DIAGNOSTICS_HASH = "stagedExactDiagnosticsHash"
    const val EDIT_HASH = "orderedWorkspaceEditHash"
    const val REPOSITORY_IDENTITY_HASH_SUFFIX = ".repository.identityHash"
    const val MAX_COPY_ENTRIES = 100_000
    const val MAX_COPY_FILE_BYTES = 32L * 1024 * 1024
    const val MAX_COPY_TOTAL_BYTES = 512L * 1024 * 1024
    const val TEMP_DELETE_ATTEMPTS = 20
    const val TEMP_DELETE_RETRY_MILLIS = 50L
    val ROOT_POM = Path.of("pom.xml")
    val MODULE_DIRECTORY = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    val ARTIFACT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    val SHA256 = Regex("[a-f0-9]{64}")
}

package org.refactorkit.java

import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.exactBuildSourceRootOwnerships
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/** Exact literal Maven dependency identity accepted by the first ownership-migration row. */
data class MavenDependencyIdentity(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val type: String = "jar",
    val classifier: String? = null,
) {
    init {
        require(groupId.isNotBlank() && artifactId.isNotBlank() && version.isNotBlank()) {
            "Maven dependency groupId, artifactId and version must be explicit"
        }
        require(type.isNotBlank()) { "Maven dependency type must not be blank" }
        require(classifier?.isNotBlank() != false) { "Maven dependency classifier must be null or non-blank" }
    }
}

/** One explicitly authorized literal dependency replacement in one reactor POM. */
data class MavenDependencyRewrite(
    val pomPath: Path,
    val source: MavenDependencyIdentity,
    val destination: MavenDependencyIdentity,
    val allIdenticalOccurrences: Boolean = false,
)

/**
 * Moves one complete non-generated Java source root between existing Maven modules and
 * composes the move with explicitly requested, byte-preserving dependency text edits.
 */
class JavaMoveAcrossMavenModulesPlanner(
    private val adapter: JavaLanguageAdapter = JavaLanguageAdapter(),
) {
    fun preview(
        snapshot: ProjectSnapshot,
        from: Path,
        to: Path,
        dependencyRewrites: List<MavenDependencyRewrite>,
    ): PatchPlan {
        val source = safeRelative(from) ?: return refused(
            snapshot, "mavenOwnership.sourceUnrecognized",
            "Source root must be a safe workspace-relative path: $from",
        )
        val destination = safeRelative(to) ?: return refused(
            snapshot, "mavenOwnership.destinationUnrecognized",
            "Destination root must be a safe workspace-relative path: $to",
        )
        val mavenModel = snapshot.buildModels.singleOrNull { it.providerId == MAVEN_PROVIDER }
            ?: return refused(
                snapshot, "mavenOwnership.sourceUnrecognized",
                "Exactly one authoritative Maven effective reactor is required",
            )
        if (mavenModel.modules.isEmpty()) {
            return refused(snapshot, "mavenOwnership.sourceUnrecognized", "The authoritative Maven effective reactor has no modules")
        }
        val sourceOwner = snapshot.exactBuildSourceRootOwnerships(source).singleOrNull()
            ?: return refused(
                snapshot, "mavenOwnership.sourceUnrecognized",
                "Source is not owned by exactly one effective Maven source set: $source",
            )
        val destinationOwner = snapshot.exactBuildSourceRootOwnerships(destination).singleOrNull()
            ?: return refused(
                snapshot, "mavenOwnership.destinationUnrecognized",
                "Destination is not owned by exactly one effective Maven source set: $destination",
            )
        if (sourceOwner.providerId != MAVEN_PROVIDER || destinationOwner.providerId != MAVEN_PROVIDER) {
            return refused(snapshot, "mavenOwnership.sourceUnrecognized", "Source and destination must belong to the authoritative Maven reactor")
        }
        if (sourceOwner.module.id == destinationOwner.module.id) {
            return refused(snapshot, "mavenOwnership.destinationUnrecognized", "Source and destination must belong to different Maven modules")
        }
        if (sourceOwner.sourceSet.kind != destinationOwner.sourceSet.kind) {
            return refused(snapshot, "mavenOwnership.sourceSetMismatch", "Source-set kind must be preserved")
        }
        if (sourceOwner.generated || destinationOwner.generated) {
            return refused(snapshot, "mavenOwnership.generated", "Generated source roots are read-only")
        }
        val remainingSourceRoots = sourceOwner.module.sourceSets.flatMap { it.sourceRoots }
            .map(Path::normalize).filterNot { it == source }
        val sourceIdentity = moduleIdentity(sourceOwner.module.attributes)
            ?: return refused(snapshot, "mavenOwnership.sourceUnrecognized", "Source Maven module identity is unavailable")
        val destinationIdentity = moduleIdentity(destinationOwner.module.attributes)
            ?: return refused(snapshot, "mavenOwnership.destinationUnrecognized", "Destination Maven module identity is unavailable")
        if (sourceIdentity.type != "jar" || sourceIdentity.classifier != null ||
            destinationIdentity.type != "jar" || destinationIdentity.classifier != null) {
            return refused(snapshot, "mavenOwnership.dependencyRewriteMismatch", "The first ownership row supports jar modules without classifiers")
        }
        if (dependencyRewrites.size > MAX_REWRITES) {
            return refused(snapshot, "mavenOwnership.dependencyRewriteMismatch", "Dependency rewrite count exceeds the bounded limit")
        }
        val auxiliaryByPath = snapshot.auxiliaryFiles.associateBy { it.path.normalize() }
        val pomEdits = mutableListOf<FileEdit.Modify>()
        val authorizedPoms = mutableSetOf<Path>()
        for (rewrite in dependencyRewrites) {
            val pomPath = safeRelative(rewrite.pomPath) ?: return refused(
                snapshot, "mavenOwnership.descriptorUnavailable",
                "POM path must be safe and workspace-relative: ${rewrite.pomPath}",
            )
            if (!authorizedPoms.add(pomPath)) {
                return refused(
                    snapshot, "mavenOwnership.ambiguousPomOrigin",
                    "Only one rewrite request per POM is accepted in the first ownership row: $pomPath",
                )
            }
            val pom = auxiliaryByPath[pomPath]?.takeIf { it.languageId == "maven-pom" }
                ?: return refused(
                    snapshot, "mavenOwnership.descriptorUnavailable",
                    "Snapshot-bound reactor POM is unavailable: $pomPath",
                )
            if (rewrite.source != sourceIdentity || rewrite.destination != destinationIdentity) {
                return refused(
                    snapshot, "mavenOwnership.dependencyRewriteMismatch",
                    "Requested dependency identities do not equal effective module identities",
                )
            }
            when (val result = MavenPomDependencyEditor.rewrite(pom, rewrite)) {
                is PomRewriteResult.Refused -> return refused(snapshot, result.code, result.message)
                is PomRewriteResult.Edits -> pomEdits += FileEdit.Modify(pomPath, result.edits)
            }
        }

        val pomOnlyEdit = WorkspaceEdit(pomEdits)
        val pomStaged = runCatching { WorkspaceEditSimulator.apply(snapshot, pomOnlyEdit) }
            .getOrElse { return refused(snapshot, "mavenOwnership.ambiguousPomOrigin", "POM staging failed: ${it.message}") }
        val before = adapter.diagnostics(snapshot)
        return withMaterializedSnapshot(pomStaged) { rebuiltPomSnapshot ->
            if (rebuiltPomSnapshot.buildModels.singleOrNull { it.providerId == MAVEN_PROVIDER }?.status != BuildModelStatus.AVAILABLE) {
                return@withMaterializedSnapshot refused(
                    snapshot, "mavenOwnership.ambiguousPomOrigin",
                    "The staged Maven effective reactor could not be rebuilt offline", before,
                )
            }
            val rootPlan = JavaMoveSourceRootPlanner(adapter).preview(rebuiltPomSnapshot, source, destination)
            if (rootPlan.status != PatchStatus.PREVIEW) {
                val code = when (rootPlan.refusalCode) {
                    "sourceRoot.generated" -> "mavenOwnership.generated"
                    "sourceRoot.destinationUnrecognized" -> "mavenOwnership.destinationUnrecognized"
                    "sourceRoot.diagnosticsRegression" -> ownershipRegressionCode(
                        diagnosticRegressions(before, rootPlan.diagnosticsAfterPreview),
                        remainingSourceRoots, destination, dependencyRewrites.isEmpty(),
                    )
                    else -> rootPlan.refusalCode?.replace("sourceRoot", "mavenOwnership")
                        ?: "mavenOwnership.diagnosticsRegression"
                }
                return@withMaterializedSnapshot refused(
                    snapshot, code, rootPlan.summary, before, rootPlan.diagnosticsAfterPreview,
                )
            }
            val combined = WorkspaceEdit(pomEdits + rootPlan.workspaceEdit.edits)
            val finalSnapshot = WorkspaceEditSimulator.apply(rebuiltPomSnapshot, rootPlan.workspaceEdit)
            if (hasDependencyCycle(finalSnapshot)) {
                return@withMaterializedSnapshot refused(
                    snapshot, "mavenOwnership.cycle", "The staged Maven reactor contains a dependency cycle", before,
                    rootPlan.diagnosticsAfterPreview,
                )
            }
            val regressions = diagnosticRegressions(before, rootPlan.diagnosticsAfterPreview)
            if (regressions.isNotEmpty()) {
                val code = ownershipRegressionCode(
                    regressions, remainingSourceRoots, destination, dependencyRewrites.isEmpty(),
                )
                return@withMaterializedSnapshot refused(
                    snapshot, code, "Ownership migration introduces ${regressions.size} Java diagnostic(s)",
                    before, rootPlan.diagnosticsAfterPreview,
                )
            }
            val affected = combined.edits.flatMap { edit ->
                when (edit) {
                    is FileEdit.Rename -> listOf(edit.path, edit.newPath)
                    else -> listOf(edit.path)
                }
            }.toSet()
            PatchPlan(
                operation = OPERATION,
                status = PatchStatus.PREVIEW,
                snapshotHash = snapshot.hash,
                confidence = 1.0,
                requiresUserApproval = true,
                summary = "Move ${rootPlan.workspaceEdit.edits.size} Java compilation unit(s) " +
                    "from $source to $destination and apply ${pomEdits.size} explicit Maven dependency rewrite(s).",
                affectedFiles = affected,
                workspaceEdit = combined,
                diagnosticsBefore = before,
                diagnosticsAfterPreview = rootPlan.diagnosticsAfterPreview,
                warnings = listOf(
                    "Java source bytes, package declarations and public binary identities are unchanged; " +
                        "only explicitly authorized POM coordinate text is replaced.",
                ),
                riskLevel = RiskLevel.MEDIUM,
                evidence = RefactoringEvidence.JDT_BINDING,
            )
        }
    }

    /** Diagnostics gate for apply/post-apply: always rebuilds the staged Maven model offline. */
    fun diagnostics(snapshot: ProjectSnapshot): List<Diagnostic> =
        if (snapshot.auxiliaryFiles.none { it.languageId == "maven-pom" }) adapter.diagnostics(snapshot)
        else withMaterializedSnapshot(snapshot, adapter::diagnostics)

    private fun moduleIdentity(attributes: Map<String, String>): MavenDependencyIdentity? {
        val group = attributes["java.maven.groupId"] ?: return null
        val artifact = attributes["java.maven.artifactId"] ?: return null
        val version = attributes["java.maven.version"] ?: return null
        val packaging = attributes["java.maven.packaging"] ?: "jar"
        return MavenDependencyIdentity(group, artifact, version, packaging)
    }

    private fun hasDependencyCycle(snapshot: ProjectSnapshot): Boolean {
        val model = snapshot.buildModels.singleOrNull { it.providerId == MAVEN_PROVIDER } ?: return true
        val edges = model.modules.associate { module ->
            module.id to module.sourceSets.flatMap { it.moduleDependencies }.map { it.targetModuleId }.toSet()
        }
        val active = mutableSetOf<String>()
        val complete = mutableSetOf<String>()
        fun visit(module: String): Boolean {
            if (module in active) return true
            if (!complete.add(module)) return false
            active += module
            val cycle = edges[module].orEmpty().any(::visit)
            active -= module
            return cycle
        }
        return edges.keys.any(::visit)
    }

    private fun ownershipRegressionCode(
        diagnostics: List<Diagnostic>,
        remainingSourceRoots: List<Path>,
        destination: Path,
        dependencyIntentMissing: Boolean,
    ): String = when {
        dependencyIntentMissing -> "mavenOwnership.dependencyRewriteRequired"
        diagnostics.any { it.severity == Diagnostic.Severity.ERROR && it.location?.path?.normalize()?.startsWith(destination) == true } ->
            "mavenOwnership.destinationDependencyMissing"
        diagnostics.any { diagnostic ->
            diagnostic.severity == Diagnostic.Severity.ERROR && diagnostic.location?.path?.normalize()?.let { path ->
                remainingSourceRoots.any(path::startsWith)
            } == true
        } -> "mavenOwnership.remainingSourceDependency"
        else -> "mavenOwnership.diagnosticsRegression"
    }

    private fun diagnosticRegressions(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val baseline = before.filter { it.severity == Diagnostic.Severity.ERROR }
            .groupingBy(::diagnosticIdentity).eachCount().toMutableMap()
        return after.filter { diagnostic ->
            if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
            val key = diagnosticIdentity(diagnostic)
            val available = baseline[key] ?: 0
            if (available > 0) { baseline[key] = available - 1; false } else true
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String = listOf(
        diagnostic.code.orEmpty(), diagnostic.location?.path?.normalize().toString(),
        diagnostic.location?.range?.start?.line.toString(), diagnostic.message,
    ).joinToString("\u0000")

    private fun <T> withMaterializedSnapshot(snapshot: ProjectSnapshot, action: (ProjectSnapshot) -> T): T {
        val temporary = Files.createTempDirectory("refactorkit-maven-ownership-")
        try {
            snapshot.trackedFiles.forEach { file ->
                val target = temporary.resolve(file.path).normalize()
                require(target.startsWith(temporary)) { "Staged file escapes temporary workspace: ${file.path}" }
                Files.createDirectories(target.parent)
                Files.writeString(target, file.content)
            }
            val rebuilt = JavaProjectScanner().scan(temporary)
            return action(rebuilt)
        } finally {
            Files.walk(temporary).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun safeRelative(path: Path): Path? = path.normalize().takeIf {
        path.toString().isNotBlank() && !it.isAbsolute && !it.startsWith("..")
    }

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        reason: String,
        before: List<Diagnostic> = emptyList(),
        after: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = OPERATION,
        status = PatchStatus.REFUSED,
        snapshotHash = snapshot.hash,
        confidence = 0.0,
        requiresUserApproval = false,
        summary = reason,
        affectedFiles = emptySet(),
        workspaceEdit = WorkspaceEdit(),
        diagnosticsBefore = before,
        diagnosticsAfterPreview = after,
        warnings = listOf(reason),
        riskLevel = RiskLevel.HIGH,
        evidence = RefactoringEvidence.JDT_BINDING,
        refusalCode = code,
    )

    companion object {
        const val OPERATION = "java.moveAcrossMavenModules"
        private const val MAVEN_PROVIDER = "maven-effective-v1"
        private const val MAX_REWRITES = 64
    }
}

package org.refactorkit.java

import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.DiagnosticsGate
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceRange
import org.refactorkit.core.TextEdit
import org.refactorkit.core.TextEdits
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Creates one new literal Gradle project beneath the exact synchronized build root. */
class JavaCreateGradleModulePlanner {
    companion object {
        const val OPERATION = "java.createGradleModule"
        const val DIAGNOSTICS_GATE_ID = "java-create-gradle-module-staged-snapshot-v1"
        private const val LEASE_KIND = "java-create-gradle-module-v1"
        private const val INVALID_PATH = "java.createGradleModule.invalidProjectPath"
        private const val BUILD_ROOT_UNAVAILABLE = "java.createGradleModule.buildRootUnavailable"
        private const val CONTEXT_UNAVAILABLE = "java.createGradleModule.synchronizedContextRequired"
        private const val SETTINGS_UNAVAILABLE = "java.createGradleModule.settingsUnavailable"
        private const val DESTINATION_EXISTS = "java.createGradleModule.destinationExists"
    }

    fun preview(snapshot: ProjectSnapshot, buildModel: BuildModel?, buildRootValue: String, projectPath: String): PatchPlan {
        val normalizedRoot = snapshot.workspace.root.toAbsolutePath().normalize()
        val buildRootRelative = parseBuildRoot(buildRootValue)
            ?: return refused(snapshot, INVALID_PATH, "Gradle buildRoot must be a normalized workspace-relative path")
        val buildRoot = normalizedRoot.resolve(buildRootRelative).normalize()
        if (!buildRoot.startsWith(normalizedRoot) || !Files.isDirectory(buildRoot, LinkOption.NOFOLLOW_LINKS) ||
            hasSymlinkComponent(normalizedRoot, buildRoot)
        ) return refused(snapshot, BUILD_ROOT_UNAVAILABLE, "Selected Gradle build root is unavailable or traverses a symbolic link")
        if (runCatching { buildRoot.toRealPath() }.isFailure) return refused(
            snapshot, BUILD_ROOT_UNAVAILABLE, "Selected Gradle build root cannot be resolved safely",
        )

        val model = buildModel?.takeIf {
            it.providerId == "gradle-effective-v1" && it.status == BuildModelStatus.AVAILABLE &&
                snapshot.buildModels.singleOrNull { candidate -> candidate.providerId == it.providerId }
                    ?.let { candidate -> candidate.status == BuildModelStatus.AVAILABLE &&
                        candidate.attributes["gradle.exportSha256"] == it.attributes["gradle.exportSha256"] } == true
        } ?: return refused(snapshot, CONTEXT_UNAVAILABLE, "An explicitly synchronized current Gradle model is required")
        if (model.attributes["gradle.workspaceRoot"] != normalizedRoot.toRealPath().toString()) {
            return refused(snapshot, CONTEXT_UNAVAILABLE, "Synchronized Gradle model belongs to another workspace")
        }
        val buildRootIdentity = buildRootRelative.toString().replace('\\', '/').ifEmpty { "." }
        val selectedModules = model.modules.filter { it.attributes["gradle.buildRoot"] == buildRootIdentity }
        if (selectedModules.isEmpty()) return refused(snapshot, BUILD_ROOT_UNAVAILABLE,
            "Selected buildRoot does not identify a build in the synchronized Gradle model")

        val settingsRows = snapshot.auxiliaryFiles.filter { file ->
            (file.path.normalize().parent ?: Path.of("")) == buildRootRelative &&
                file.path.fileName?.toString() in setOf("settings.gradle", "settings.gradle.kts")
        }
        val settingsFile = settingsRows.singleOrNull()
            ?: return refused(snapshot, SETTINGS_UNAVAILABLE, "Selected build root must have exactly one captured Gradle settings file")
        val settingsAbsolute = normalizedRoot.resolve(settingsFile.path).normalize()
        if (Files.isSymbolicLink(settingsAbsolute) || !Files.isRegularFile(settingsAbsolute, LinkOption.NOFOLLOW_LINKS)) {
            return refused(snapshot, SETTINGS_UNAVAILABLE, "Selected settings file is not a regular no-follow workspace file")
        }

        val proposal = when (val inspected = GradleSettingsModuleEdit.propose(
            settingsFile.path.fileName.toString(), settingsFile.content, projectPath,
        )) {
            is GradleSettingsModuleEditResult.Refused -> return refused(snapshot, inspected.code,
                "Gradle settings or canonical project path is not safe to extend")
            is GradleSettingsModuleEditResult.Supported -> inspected.proposal
        }
        if (selectedModules.any { it.attributes["gradle.projectPath"] == projectPath }) {
            return refused(snapshot, DESTINATION_EXISTS, "Gradle project path already exists in the selected build")
        }

        val moduleDirectory = buildRootRelative.resolve(proposal.projectDirectory).normalize()
        val buildScriptPath = buildRootRelative.resolve(proposal.buildScriptPath).normalize()
        val moduleAbsolute = normalizedRoot.resolve(moduleDirectory).normalize()
        val buildScriptAbsolute = normalizedRoot.resolve(buildScriptPath).normalize()
        if (!moduleAbsolute.startsWith(buildRoot) || !buildScriptAbsolute.startsWith(buildRoot) ||
            Files.exists(moduleAbsolute, LinkOption.NOFOLLOW_LINKS) ||
            Files.exists(buildScriptAbsolute, LinkOption.NOFOLLOW_LINKS) ||
            hasSymlinkComponent(normalizedRoot, moduleAbsolute) ||
            snapshot.trackedFiles.any { it.path.normalize() == buildScriptPath || it.path.normalize().startsWith(moduleDirectory) }
        ) return refused(snapshot, DESTINATION_EXISTS, "Gradle module destination already exists or traverses a symbolic link")

        val position = TextEdits.positionForOffset(settingsFile.content, proposal.insertionOffset)
        val edit = WorkspaceEdit(listOf(
            FileEdit.Modify(settingsFile.path, listOf(TextEdit(SourceRange(position, position), proposal.insertedText))),
            FileEdit.Create(buildScriptPath, when (proposal.dsl) {
                GradleSettingsDsl.KOTLIN -> "plugins { id(\"java\") }\n"
                GradleSettingsDsl.GROOVY -> "plugins { id 'java' }\n"
            }),
        ))
        val normalizedEdit = runCatching { WorkspaceEditSimulator.normalize(edit) }.getOrElse {
            return refused(snapshot, SETTINGS_UNAVAILABLE, "Gradle module edit could not be normalized safely")
        }
        val staged = try { JavaGradleModuleStagedSnapshot.evaluate(snapshot, normalizedEdit) }
        catch (failure: Exception) {
            return refused(snapshot, CONTEXT_UNAVAILABLE,
                "Gradle module staged snapshot could not be proven (${failure::class.simpleName})")
        }
        val baselineDiagnostics = JavaLanguageAdapter().diagnostics(snapshot)
        val stagedDiagnostics = JavaLanguageAdapter().diagnostics(staged)
        val attributes = mapOf(
            "buildRoot" to buildRootRelative.toString().ifEmpty { "." },
            "projectPath" to projectPath,
            "settingsPath" to settingsFile.path.normalize().toString().replace('\\', '/'),
            "settingsDsl" to proposal.dsl.name,
            "gradleExportSha256" to model.attributes.getValue("gradle.exportSha256"),
        )
        val files = listOf(OperationAuthorityFileEvidence(
            kind = "gradle-settings",
            path = settingsFile.path,
            expectedContentSha256 = sha256(settingsFile.content.toByteArray(StandardCharsets.UTF_8)),
            attributes = mapOf("dsl" to proposal.dsl.name),
        ))
        val lease = OperationAuthorityLease(
            kind = LEASE_KIND,
            operation = OPERATION,
            snapshotHash = snapshot.hash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(attributes, files, snapshot.classpathEvidence),
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(normalizedEdit),
            requiredClasspathEvidence = snapshot.classpathEvidence,
            requiredFileEvidence = files,
            attributes = attributes,
        )
        return PatchPlan(
            operation = OPERATION,
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Create Gradle project '$projectPath' beneath ${attributes.getValue("buildRoot")} using ${proposal.dsl.name.lowercase()} DSL",
            affectedFiles = normalizedEdit.affectedFiles(),
            workspaceEdit = normalizedEdit,
            diagnosticsBefore = baselineDiagnostics,
            diagnosticsAfterPreview = stagedDiagnostics,
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
            warnings = listOf("The new project uses the Java plugin. Synchronize Gradle explicitly after Apply."),
            authorityLease = lease,
        )
    }

    fun bindStructuralContext(plan: PatchPlan, view: JavaProjectContextView, schemaVersion: String): PatchPlan {
        if (plan.operation != OPERATION || plan.status != PatchStatus.PREVIEW) throw JavaContextAuthorityException(
            "java.context.operationUnsupported", "Structural Java context can bind only a Gradle Add Module preview")
        if (view.sdk.isEmpty()) throw JavaContextAuthorityException("java.context.sdkRequired",
            "Gradle Add Module requires explicit Java SDK platform evidence")
        if (view.snapshot.hash != plan.snapshotHash) throw JavaContextAuthorityException("java.context.snapshotStale",
            "Gradle Add Module context differs from the exact preview snapshot")
        val sdkHome = runCatching { view.sdk.first().platformHome.toRealPath().toString() }.getOrNull()
            ?: throw JavaContextAuthorityException("java.context.contextStale", "Configured SDK home changed during Gradle Add Module preview")
        val previous = requireNotNull(plan.authorityLease)
        val attributes = previous.attributes + mapOf(
            "javaContextAuthorityKind" to JavaProjectStructuralContextAuthority.AUTHORITY_KIND,
            "retainInTransactionJournal" to "true",
            "javaContextHash" to view.contextHash,
            "javaContextSchemaVersion" to schemaVersion,
            "javaSdkHome" to sdkHome,
            "javaPlatformIdentities" to view.sdk.sortedBy { it.release }.joinToString(",") {
                "${it.release}:${it.identitySha256}"
            },
        )
        val lease = OperationAuthorityLease(
            kind = previous.kind,
            operation = previous.operation,
            snapshotHash = previous.snapshotHash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(
                attributes, previous.requiredFileEvidence, previous.requiredClasspathEvidence,
            ),
            evidenceCompleteness = previous.evidenceCompleteness,
            workspaceEditSha256 = previous.workspaceEditSha256,
            requiredClasspathEvidence = previous.requiredClasspathEvidence,
            requiredFileEvidence = previous.requiredFileEvidence,
            attributes = attributes,
        )
        return plan.copy(authorityLease = lease)
    }

    fun diagnosticsGate(plan: PatchPlan, baseline: ProjectSnapshot): DiagnosticsGate {
        require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW)
        val expectedStaged = JavaGradleModuleStagedSnapshot.evaluate(baseline, plan.workspaceEdit)
        val candidateWithoutModelInvalidation = WorkspaceEditSimulator.apply(baseline, plan.workspaceEdit)
        return DiagnosticsGate.authoritative(DIAGNOSTICS_GATE_ID, AuthoritativeDiagnosticsProvider { candidate ->
            when (candidate.hash) {
                baseline.hash -> AuthoritativeDiagnosticsEvaluation(candidate, JavaLanguageAdapter().diagnostics(candidate))
                candidateWithoutModelInvalidation.hash, expectedStaged.hash ->
                    AuthoritativeDiagnosticsEvaluation(expectedStaged, JavaLanguageAdapter().diagnostics(expectedStaged))
                else -> throw IllegalArgumentException("Gradle module authority received a candidate outside its baseline/staged snapshots")
            }
        })
    }

    private fun parseBuildRoot(value: String): Path? {
        if (value == ".") return Path.of("")
        if (value.isBlank() || value.length > 2_048 || value.startsWith('/') || '\\' in value || ':' in value ||
            value.split('/').any { it.isBlank() || it in setOf(".", "..") }
        ) return null
        val path = runCatching { Path.of(value) }.getOrNull() ?: return null
        return path.normalize().takeIf { !it.isAbsolute && !it.startsWith("..") && it.toString().replace('\\', '/') == value }
    }

    private fun hasSymlinkComponent(root: Path, target: Path): Boolean {
        var current = root
        for (part in root.relativize(target).normalize()) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun refused(snapshot: ProjectSnapshot, code: String, reason: String) = PatchPlan(
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
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = code,
    )
}

package org.refactorkit.java

import org.refactorkit.core.AuthoritativeDiagnosticsEvaluation
import org.refactorkit.core.AuthoritativeDiagnosticsProvider
import org.refactorkit.core.BuildModel
import org.refactorkit.core.BuildModelStatus
import org.refactorkit.core.BuildModule
import org.refactorkit.core.ClasspathEvidenceKind
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

/** Renames one literal Gradle project path while preserving its captured physical/model identity. */
class JavaRenameGradleModulePlanner {
    companion object {
        const val OPERATION = "java.renameGradleModule"
        const val DIAGNOSTICS_GATE_ID = "java-rename-gradle-module-staged-snapshot-v1"
        private const val LEASE_KIND = "java-rename-gradle-module-v1"
        private const val INVALID_PATH = "java.renameGradleModule.invalidProjectPath"
        private const val CONTEXT_UNAVAILABLE = "java.renameGradleModule.synchronizedContextRequired"
        private const val BUILD_ROOT_UNAVAILABLE = "java.renameGradleModule.buildRootUnavailable"
        private const val MODULE_UNAVAILABLE = "java.renameGradleModule.moduleUnavailable"
        private const val DESTINATION_EXISTS = "java.renameGradleModule.destinationExists"
        private const val SETTINGS_UNAVAILABLE = "java.renameGradleModule.settingsUnavailable"
        private const val DEPENDENCY_UNSUPPORTED = "java.renameGradleModule.inboundProjectDependencyUnsupported"
        private const val PHYSICAL_IDENTITY_UNSUPPORTED = "java.renameGradleModule.physicalIdentityUnsupported"
        private const val MODEL_PROJECTION_UNSUPPORTED = "java.renameGradleModule.modelProjectionUnsupported"
        private const val TEXT_INPUT_COUNT = "java.safeDelete.textReferenceScan.inputs"
        private const val TEXT_INPUT_BYTES = "java.safeDelete.textReferenceScan.bytes"
        private const val TEXT_INPUT_STATUS = "java.safeDelete.textReferenceScan.status"
        private const val MAX_TEXT_INPUTS = 8_192
        private const val MAX_TEXT_INPUT_BYTES = 1_048_576L
        private const val MAX_TOTAL_TEXT_BYTES = 33_554_432L
    }

    fun preview(
        snapshot: ProjectSnapshot,
        buildModel: BuildModel?,
        buildRootValue: String,
        oldProjectPath: String,
        newProjectPath: String,
    ): PatchPlan {
        val workspaceRoot = snapshot.workspace.root.toAbsolutePath().normalize()
        val workspaceRealRoot = runCatching { workspaceRoot.toRealPath() }.getOrNull()
            ?: return refused(snapshot, CONTEXT_UNAVAILABLE, "Workspace root cannot be resolved safely")
        val buildRootRelative = parseBuildRoot(buildRootValue)
            ?: return refused(snapshot, INVALID_PATH, "Gradle buildRoot must be a normalized workspace-relative path")
        val buildRoot = workspaceRoot.resolve(buildRootRelative).normalize()
        if (!buildRoot.startsWith(workspaceRoot) || !Files.isDirectory(buildRoot, LinkOption.NOFOLLOW_LINKS) ||
            hasSymlinkComponent(workspaceRoot, buildRoot)
        ) return refused(snapshot, BUILD_ROOT_UNAVAILABLE, "Selected Gradle build root is unavailable or traverses a symbolic link")
        val buildRootReal = runCatching { buildRoot.toRealPath() }.getOrNull()
            ?: return refused(snapshot, BUILD_ROOT_UNAVAILABLE, "Selected Gradle build root cannot be resolved safely")

        val model = buildModel?.takeIf {
            it.providerId == "gradle-effective-v1" && it.status == BuildModelStatus.AVAILABLE &&
                snapshot.buildModels.singleOrNull { candidate -> candidate.providerId == it.providerId }
                    ?.let { candidate -> candidate.status == BuildModelStatus.AVAILABLE &&
                        candidate.attributes["gradle.exportSha256"] == it.attributes["gradle.exportSha256"] } == true &&
                it.attributes["gradle.exportSha256"]?.matches(Regex("[a-f0-9]{64}")) == true &&
                it.attributes["gradle.workspaceRoot"] == workspaceRealRoot.toString()
        } ?: return refused(snapshot, CONTEXT_UNAVAILABLE, "An exact, current synchronized Gradle model is required")

        val buildRootIdentity = buildRootRelative.toString().replace('\\', '/').ifEmpty { "." }
        val selectedModules = model.modules.filter { it.attributes["gradle.buildRoot"] == buildRootIdentity }
        if (selectedModules.isEmpty()) return refused(snapshot, BUILD_ROOT_UNAVAILABLE,
            "Selected buildRoot does not identify a build in the synchronized Gradle model")
        val oldModule = selectedModules.singleOrNull { it.attributes["gradle.projectPath"] == oldProjectPath }
            ?: return refused(snapshot, MODULE_UNAVAILABLE, "Old Gradle project path does not identify exactly one synchronized module")
        if (selectedModules.any { it.attributes["gradle.projectPath"] == newProjectPath }) {
            return refused(snapshot, DESTINATION_EXISTS, "New Gradle project path already exists in the selected build")
        }
        val oldProjectDirectory = projectPathDirectory(oldProjectPath)
            ?: return refused(snapshot, INVALID_PATH, "Old Gradle project path is not canonical")
        val newProjectPathDirectory = projectPathDirectory(newProjectPath)
            ?: return refused(snapshot, INVALID_PATH, "New Gradle project path is not canonical")
        if (oldProjectPath == newProjectPath) return refused(snapshot, INVALID_PATH, "Old and new Gradle project paths must differ")
        val expectedModuleRootRelative = buildRootRelative.resolve(oldProjectDirectory).normalize()
        val expectedModuleRoot = workspaceRoot.resolve(expectedModuleRootRelative).normalize()
        if (!expectedModuleRoot.startsWith(buildRoot) || !Files.isDirectory(expectedModuleRoot, LinkOption.NOFOLLOW_LINKS) ||
            hasSymlinkComponent(workspaceRoot, expectedModuleRoot) ||
            runCatching { expectedModuleRoot.toRealPath() }.getOrNull() != buildRootReal.resolve(
                buildRootRelative.relativize(expectedModuleRootRelative),
            ).normalize()
        ) return refused(snapshot, PHYSICAL_IDENTITY_UNSUPPORTED,
            "The synchronized Gradle module does not occupy its exact conventional physical project directory")
        if (!oldModule.root.isAbsolute || oldModule.root.normalize() != expectedModuleRoot ||
            runCatching { oldModule.root.toRealPath() }.getOrNull() != expectedModuleRoot.toRealPath()
        ) return refused(snapshot, PHYSICAL_IDENTITY_UNSUPPORTED,
            "The synchronized Gradle module root differs from its captured physical directory")

        val newProjectDirectory = buildRootRelative.resolve(newProjectPathDirectory).normalize()
        val newProjectAbsolute = workspaceRoot.resolve(newProjectDirectory).normalize()
        if (!newProjectAbsolute.startsWith(buildRoot) ||
            (Files.exists(newProjectAbsolute, LinkOption.NOFOLLOW_LINKS) && newProjectAbsolute != expectedModuleRoot) ||
            hasSymlinkComponent(workspaceRoot, newProjectAbsolute)
        ) return refused(snapshot, DESTINATION_EXISTS,
            "The conventional directory for the new Gradle path already exists or traverses a symbolic link")

        val settingsRows = snapshot.auxiliaryFiles.filter { file ->
            (file.path.normalize().parent ?: Path.of("")) == buildRootRelative &&
                file.path.fileName?.toString() in setOf("settings.gradle", "settings.gradle.kts")
        }
        val settingsFile = settingsRows.singleOrNull()
            ?: return refused(snapshot, SETTINGS_UNAVAILABLE, "Selected build root must have exactly one captured Gradle settings file")
        val settingsAbsolute = workspaceRoot.resolve(settingsFile.path).normalize()
        if (!settingsAbsolute.startsWith(buildRoot) || Files.isSymbolicLink(settingsAbsolute) ||
            !Files.isRegularFile(settingsAbsolute, LinkOption.NOFOLLOW_LINKS)
        ) return refused(snapshot, SETTINGS_UNAVAILABLE, "Selected settings file is not a regular no-follow workspace file")
        val settingsDsl = when (settingsFile.path.fileName.toString()) {
            "settings.gradle" -> GradleSettingsDsl.GROOVY
            "settings.gradle.kts" -> GradleSettingsDsl.KOTLIN
            else -> return refused(snapshot, SETTINGS_UNAVAILABLE, "Selected settings DSL is unsupported")
        }
        val inspected = when (val result = GradleSettingsModuleEdit.proposeRename(
            settingsFile.path.fileName.toString(), settingsFile.content, oldProjectPath, newProjectPath,
        )) {
            is GradleSettingsModuleRenameResult.Refused -> return refused(snapshot, result.code,
                "Gradle settings do not contain one unconditional literal include or use an unsupported mapping")
            is GradleSettingsModuleRenameResult.Supported -> result.proposal
        }
        if (inspected.dsl != settingsDsl || inspected.physicalProjectDirectory != projectPathDirectory(oldProjectPath)) {
            return refused(snapshot, PHYSICAL_IDENTITY_UNSUPPORTED,
                "Settings mapping does not preserve the synchronized module's conventional physical directory")
        }
        val inbound = JavaGradleInboundProjectEdits.capture(
            snapshot, model, oldModule, settingsFile, oldProjectPath, newProjectPath,
        ) ?: return refused(snapshot, DEPENDENCY_UNSUPPORTED,
            "The captured inbound Gradle project dependencies do not have closed literal origins in local build scripts")

        val projectedModel = projectModel(model, oldModule, newProjectPath, buildRootIdentity)
            ?: return refused(snapshot, MODEL_PROJECTION_UNSUPPORTED,
                "The typed Gradle module/source-set model cannot be projected without changing unqualified ownership")
        val projectedModule = projectedModel.modules.singleOrNull { it.attributes["gradle.projectPath"] == newProjectPath }
            ?: return refused(snapshot, MODEL_PROJECTION_UNSUPPORTED, "Projected Gradle module identity is not unique")
        if (projectedModule.root != oldModule.root || projectedModule.sourceSets != oldModule.sourceSets ||
            projectedModel.modules.size != model.modules.size
        ) return refused(snapshot, MODEL_PROJECTION_UNSUPPORTED,
            "Staged ownership, dependencies, source roots, outputs, or ordered classpaths differ from the synchronized model")

        val literalEdit = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(settingsFile.content, inspected.pathLiteralStart),
                TextEdits.positionForOffset(settingsFile.content, inspected.pathLiteralEnd),
            ),
            newProjectPath,
        )
        val append = TextEdit(
            SourceRange(
                TextEdits.positionForOffset(settingsFile.content, inspected.insertionOffset),
                TextEdits.positionForOffset(settingsFile.content, inspected.insertionOffset),
            ),
            inspected.insertedText,
        )
        val settingsEdit = FileEdit.Modify(settingsFile.path, listOf(literalEdit, append))
        val edit = try {
            WorkspaceEditSimulator.normalize(WorkspaceEdit(listOf(settingsEdit) + inbound.edits))
        } catch (_: IllegalArgumentException) {
            return refused(snapshot, SETTINGS_UNAVAILABLE, "Gradle settings rename edits overlap or cannot be normalized")
        }
        val staged = try { stagedSnapshot(snapshot, edit) }
        catch (failure: Exception) {
            return refused(snapshot, CONTEXT_UNAVAILABLE,
                "Gradle rename staged snapshot could not be proven (${failure::class.simpleName})")
        }
        val beforeDiagnostics = JavaLanguageAdapter().diagnostics(snapshot)
        val afterDiagnostics = JavaLanguageAdapter().diagnostics(staged)
        val attributes = mapOf(
            "buildRoot" to buildRootIdentity,
            "oldProjectPath" to oldProjectPath,
            "newProjectPath" to newProjectPath,
            "physicalProjectDirectory" to expectedModuleRootRelative.toString().replace('\\', '/'),
            "settingsPath" to settingsFile.path.normalize().toString().replace('\\', '/'),
            "settingsDsl" to settingsDsl.name,
            "gradleExportSha256" to model.attributes.getValue("gradle.exportSha256"),
            "projectedGradleModelSha256" to sha256(projectedModel.toString().toByteArray(StandardCharsets.UTF_8)),
        )
        val files = buildList {
            add(OperationAuthorityFileEvidence(
                kind = "gradle-settings",
                path = settingsFile.path,
                expectedContentSha256 = sha256(settingsFile.content.toByteArray(StandardCharsets.UTF_8)),
                attributes = mapOf("dsl" to settingsDsl.name, "oldProjectPath" to oldProjectPath, "newProjectPath" to newProjectPath),
            ))
            inbound.scripts.forEach { script ->
                add(OperationAuthorityFileEvidence(
                    kind = "gradle-build-script",
                    path = script.path,
                    expectedContentSha256 = sha256(script.content.toByteArray(StandardCharsets.UTF_8)),
                ))
            }
        }
        val lease = OperationAuthorityLease(
            kind = LEASE_KIND,
            operation = OPERATION,
            snapshotHash = snapshot.hash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(attributes, files, snapshot.classpathEvidence),
            workspaceEditSha256 = WorkspaceEditIdentity.sha256(edit),
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
            summary = "Rename Gradle project '$oldProjectPath' to '$newProjectPath' while preserving ${attributes.getValue("physicalProjectDirectory")}",
            affectedFiles = edit.affectedFiles(),
            workspaceEdit = edit,
            diagnosticsBefore = beforeDiagnostics,
            diagnosticsAfterPreview = afterDiagnostics,
            riskLevel = RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
            warnings = listOf("Apply changes only after review; synchronize Gradle explicitly to refresh the invalidated build model."),
            authorityLease = lease,
        )
    }

    fun bindStructuralContext(plan: PatchPlan, view: JavaProjectContextView, schemaVersion: String): PatchPlan {
        if (plan.operation != OPERATION || plan.status != PatchStatus.PREVIEW) throw JavaContextAuthorityException(
            "java.context.operationUnsupported", "Structural Java context can bind only a Gradle module rename preview",
        )
        if (view.sdk.isEmpty()) throw JavaContextAuthorityException(
            "java.context.sdkRequired", "Gradle module rename requires explicit Java SDK platform evidence",
        )
        if (view.snapshot.hash != plan.snapshotHash) throw JavaContextAuthorityException(
            "java.context.snapshotStale", "Gradle module rename context differs from the exact preview snapshot",
        )
        val sdkHome = runCatching { view.sdk.first().platformHome.toRealPath().toString() }.getOrNull()
            ?: throw JavaContextAuthorityException("java.context.contextStale", "Configured SDK home changed during Gradle module rename preview")
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
        val files = previous.requiredFileEvidence
        val lease = OperationAuthorityLease(
            kind = previous.kind,
            operation = previous.operation,
            snapshotHash = previous.snapshotHash,
            evidenceHash = JavaRenameMavenModuleEvidence.authorityEvidenceHash(
                attributes, files, previous.requiredClasspathEvidence,
            ),
            evidenceCompleteness = previous.evidenceCompleteness,
            workspaceEditSha256 = previous.workspaceEditSha256,
            requiredClasspathEvidence = previous.requiredClasspathEvidence,
            requiredFileEvidence = files,
            attributes = attributes,
        )
        return plan.copy(authorityLease = lease)
    }

    fun diagnosticsGate(plan: PatchPlan, baseline: ProjectSnapshot): DiagnosticsGate {
        require(plan.operation == OPERATION && plan.status == PatchStatus.PREVIEW)
        val expectedStaged = stagedSnapshot(baseline, plan.workspaceEdit)
        val candidateWithoutModelInvalidation = WorkspaceEditSimulator.apply(baseline, plan.workspaceEdit)
        return DiagnosticsGate.authoritative(DIAGNOSTICS_GATE_ID, AuthoritativeDiagnosticsProvider { candidate ->
            when (candidate.hash) {
                baseline.hash -> AuthoritativeDiagnosticsEvaluation(candidate, JavaLanguageAdapter().diagnostics(candidate))
                candidateWithoutModelInvalidation.hash, expectedStaged.hash ->
                    AuthoritativeDiagnosticsEvaluation(expectedStaged, JavaLanguageAdapter().diagnostics(expectedStaged))
                else -> throw IllegalArgumentException("Gradle rename authority received a candidate outside its baseline/staged snapshots")
            }
        })
    }

    private fun projectModel(
        model: BuildModel,
        oldModule: BuildModule,
        newProjectPath: String,
        buildRootIdentity: String,
    ): BuildModel? {
        val compositeBuildCount = model.attributes["gradle.compositeBuildCount"]?.toIntOrNull() ?: return null
        val newId = if (compositeBuildCount == 1) newProjectPath else {
            val buildIdentity = oldModule.attributes["gradle.buildRoot"]?.takeIf { it == buildRootIdentity } ?: return null
            "gradle-${sha256(buildIdentity.toByteArray(StandardCharsets.UTF_8))}:$newProjectPath"
        }
        if (model.modules.any { it.id == newId && it.id != oldModule.id }) return null
        val newAttributes = oldModule.attributes + ("gradle.projectPath" to newProjectPath)
        val renamed = oldModule.copy(
            id = newId,
            name = newProjectPath.substringAfterLast(':'),
            attributes = newAttributes,
        )
        return runCatching {
            model.copy(modules = model.modules.map { module ->
                if (module.id == oldModule.id) renamed else module.copy(sourceSets = module.sourceSets.map { set ->
                    set.copy(
                        moduleDependencies = set.moduleDependencies.map { edge ->
                            if (edge.targetModuleId == oldModule.id) edge.copy(targetModuleId = newId) else edge
                        },
                        attributes = set.attributes.mapValues { (key, value) ->
                            if (key.startsWith(GRADLE_RUNTIME_ARTIFACT_OWNER_PREFIX) && value == oldModule.id) newId else value
                        },
                    )
                })
            })
        }.getOrNull()
    }

    private fun stagedSnapshot(snapshot: ProjectSnapshot, edit: WorkspaceEdit): ProjectSnapshot {
        val normalized = WorkspaceEditSimulator.normalize(edit)
        val modifications = normalized.edits.filterIsInstance<FileEdit.Modify>()
        require(modifications.size == normalized.edits.size &&
            modifications.count { it.path.fileName.toString() in JavaGradleInboundProjectEdits.settingsNames } == 1 &&
            modifications.all { it.path.fileName.toString() in JavaGradleInboundProjectEdits.settingsNames ||
                it.path.fileName.toString() in JavaGradleInboundProjectEdits.buildNames }
        ) {
            "Gradle rename staging requires one settings modification and only qualified build-script modifications"
        }
        val model = snapshot.buildModels.singleOrNull { it.providerId == "gradle-effective-v1" }
            ?.takeIf { it.status == BuildModelStatus.AVAILABLE }
            ?: throw IllegalArgumentException("Gradle rename staging requires the exact synchronized Gradle model")
        require(model.attributes["gradle.exportSha256"]?.matches(Regex("[a-f0-9]{64}")) == true) {
            "Gradle rename staging requires the exact synchronized Gradle model"
        }
        val staged = WorkspaceEditSimulator.apply(snapshot, normalized)
        val beforeContent = snapshot.auxiliaryFiles.associateBy { it.path.normalize() }
        val afterContent = staged.auxiliaryFiles.associateBy { it.path.normalize() }
        val attributes = model.attributes
        require(attributes[TEXT_INPUT_STATUS] == "complete") { "Complete Gradle text-input inventory is required" }
        val inputCount = attributes[TEXT_INPUT_COUNT]?.toIntOrNull()
            ?: throw IllegalArgumentException("Gradle snapshot lacks a bounded text-input count")
        val inputBytes = attributes[TEXT_INPUT_BYTES]?.toLongOrNull()
            ?: throw IllegalArgumentException("Gradle snapshot lacks a bounded text-input byte count")
        val nextInputBytes = inputBytes + modifications.sumOf { modification ->
            val path = modification.path.normalize()
            val beforeBytes = beforeContent.getValue(path).content.toByteArray(StandardCharsets.UTF_8).size.toLong()
            val afterBytes = afterContent.getValue(path).content.toByteArray(StandardCharsets.UTF_8).size.toLong()
            require(afterBytes <= MAX_TEXT_INPUT_BYTES) { "Updated Gradle script exceeds the bounded text-input size" }
            afterBytes - beforeBytes
        }
        require(inputCount in 1..MAX_TEXT_INPUTS && nextInputBytes in 0..MAX_TOTAL_TEXT_BYTES) {
            "Gradle rename would exceed the complete text-input inventory bounds"
        }
        val invalidated = JavaGradleBuildInputInvalidation.invalidate(model).copy(
            attributes = attributes + mapOf(
                TEXT_INPUT_COUNT to inputCount.toString(),
                TEXT_INPUT_BYTES to nextInputBytes.toString(),
            ),
        )
        val modifiedPaths = modifications.mapTo(hashSetOf()) { it.path.normalize() }
        val declarationEvidence = staged.classpathEvidence.map { evidence ->
            val path = evidence.path.normalize()
            if (evidence.kind == ClasspathEvidenceKind.DECLARATION_FILE && path in modifiedPaths)
                evidence.stagedDeclaration(afterContent.getValue(path).content)
            else evidence
        }
        return staged.copy(
            classpathEvidence = declarationEvidence,
            buildModels = staged.buildModels.map { if (it.providerId == model.providerId) invalidated else it },
        )
    }

    private fun projectPathDirectory(value: String): Path? {
        if (!value.startsWith(':') || value.length < 2 || value.length > 2_048) return null
        val segments = value.drop(1).split(':')
        val safeSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
        val windowsDeviceName = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")
        if (segments.size > 32 || segments.any { segment ->
                !safeSegment.matches(segment) || segment.endsWith('.') || segment in setOf(".", "..") ||
                    windowsDeviceName.matches(segment)
            }
        ) return null
        return runCatching { segments.fold(Path.of(""), Path::resolve) }.getOrNull()
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

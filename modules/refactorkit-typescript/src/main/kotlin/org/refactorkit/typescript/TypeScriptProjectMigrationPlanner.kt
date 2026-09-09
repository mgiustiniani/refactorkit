package org.refactorkit.typescript

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.ExternalWorkspaceEditNormalization
import org.refactorkit.core.FileEdit
import org.refactorkit.core.OperationAuthorityFileEvidence
import org.refactorkit.core.OperationAuthorityLease
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RefactoringRequest
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditIdentity
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.treesitter.ExternalSemanticDiagnostics
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** One bounded sibling-directory migration; configuration origins are never attributed to tsserver. */
internal class TypeScriptProjectMigrationPlanner(
    private val client: TypeScriptSemanticClient,
    private val toolchain: TypeScriptSemanticToolchain,
    private val originalModel: TypeScriptProjectModel,
    private val sessionMatches: (ProjectSnapshot) -> Boolean,
    private val toolchainUnchanged: () -> Boolean,
    private val mutationEligible: () -> Boolean,
) {
    private data class Approved(val base: ProjectSnapshot, val afterHash: String, val afterModel: TypeScriptProjectModel, val session: String)
    private val approved = linkedMapOf<String, Approved>()

    fun preview(request: RefactoringRequest): PatchPlan = try {
        val snapshot = request.snapshot
        requireSafe(sessionMatches(snapshot) && toolchainUnchanged() && mutationEligible(), "typescript.projectMigrationSessionInvalid", "Migration requires its active complete semantic session")
        requireSafe(snapshot.files.none { it.languageId == "javascript" } || originalModel.projects.all { it.compilerOptions.checkJs == true },
            "typescript.semanticCompletenessInsufficient", "Project migration requires checking every captured JavaScript source")
        val from = directory(request.arguments["fromDirectory"])
        val to = directory(request.arguments["toDirectory"])
        requireSafe(from != to && from.parent == to.parent, "typescript.projectMigrationScopeUnsupported", "Only distinct sibling project directories are supported")
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        requireSafe(!Files.exists(root.resolve(to), LinkOption.NOFOLLOW_LINKS) && snapshot.trackedFiles.none { it.path.startsWith(to) },
            "typescript.projectMigrationTargetExists", "Project migration target already exists")
        requireSafe(matchesDisk(snapshot), "typescript.projectMigrationSnapshotChanged", "Migration snapshot differs from disk")
        val beforeModel = model(snapshot)
        requireSafe(beforeModel.status == TypeScriptProjectModelStatus.AVAILABLE && beforeModel.projectionHash == originalModel.projectionHash,
            "typescript.projectMigrationModelChanged", "Exact configuration evidence is missing or changed")
        val moving = beforeModel.projects.filter { it.configPath.startsWith(from) }
        requireSafe(moving.size == 1 && moving.single().configPath == from.resolve("tsconfig.json"),
            "typescript.projectMigrationScopeUnsupported", "Select one complete tsconfig project without nested projects")
        requireSafe(beforeModel.projects.all { it.extendsConfigs.isEmpty() && it.compilerOptions.paths.isEmpty() &&
            it.compilerOptions.baseUrl == null && !it.packageExportsDeclared && !it.packageTypesDeclared &&
            it.packageManifest?.startsWith(from) != true },
            "typescript.projectMigrationScopeUnsupported", "Extends, aliases and package publication require separate migration authority")
        val project = moving.single()
        requireSafe((project.compilerOptions.rootDirectory ?: from) == from && project.compilerOptions.outputDirectory?.startsWith(from) != true &&
            project.files.all { it.startsWith(from) } && (project.include + project.exclude).all { !it.pattern.contains("..") },
            "typescript.projectMigrationScopeUnsupported", "Project roots, output or file patterns exceed the bounded directory move")
        val movedFiles = snapshot.trackedFiles.filter { it.path.startsWith(from) }
        requireSafe(movedFiles.size in 2..256 && movedFiles.all { it.path == project.configPath ||
            it.languageId in setOf("typescript", "javascript") && !it.path.toString().endsWith(".d.ts") },
            "typescript.projectMigrationScopeUnsupported", "Only captured config and non-generated TS/JS sources can move")
        val actualPaths = Files.walk(root.resolve(from)).use { paths -> paths.toList().also { values ->
            requireSafe(values.none(Files::isSymbolicLink), "typescript.projectMigrationUnsafePath", "Symbolic project paths cannot move")
        }.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.map { root.relativize(it) }.toSet() }
        requireSafe(actualPaths == movedFiles.map { it.path }.toSet(), "typescript.projectMigrationSnapshotIncomplete", "Project contains uncaptured files")
        val configuration = try { TypeScriptReferenceOrigins.edits(snapshot, beforeModel, from, to) } catch (failure: Exception) {
            throw Refusal("typescript.projectMigrationOriginInvalid", failure.message ?: "Reference origins are invalid")
        }
        requireSafe(configuration.isNotEmpty(), "typescript.projectMigrationReferenceMissing", "No incoming project-reference edge is proven")
        val before = diagnostics(snapshot, beforeModel)
        val native = when (val result = client.requestProjectDirectoryEdit(from, to, snapshot)) {
            is ExternalWorkspaceEditNormalization.Refused -> throw Refusal(result.diagnostics.firstOrNull()?.code ?: "typescript.projectMigrationCompilerUnavailable", result.diagnostics.joinToString { it.message })
            is ExternalWorkspaceEditNormalization.Accepted -> result.normalized.workspaceEdit
        }
        val observed = client.compilerMutationEvidence()
        val args = mapOf("oldFilePath" to from.toString(), "newFilePath" to to.toString(), "scope" to "project-directory")
        requireSafe(observed != null && observed.command == "getEditsForFileRename" && observed.snapshotHash == snapshot.hash &&
            observed.arguments == args && observed.workspaceEditSha256 == WorkspaceEditIdentity.sha256(native) &&
            observed.process.workingDirectory == root, "typescript.compilerAuthorityUnavailable", "Exact directory-rename exchange is missing")
        val renames = movedFiles.map { FileEdit.Rename(it.path, to.resolve(from.relativize(it.path))) }.toSet()
        requireSafe(native.edits.all { it is FileEdit.Modify || it is FileEdit.Rename } && native.edits.filterIsInstance<FileEdit.Rename>().toSet() == renames,
            "typescript.projectMigrationCompilerInvalid", "Compiler proposal does not preserve the exact file mapping")
        val configPaths = beforeModel.projects.map { it.configPath }.toSet()
        val modifications = native.edits.filterIsInstance<FileEdit.Modify>()
        val sourcePaths = snapshot.trackedFiles.filter { it.languageId in setOf("typescript", "javascript") }.map { it.path }.toSet()
        requireSafe(modifications.all { it.path in configPaths || it.path in sourcePaths }, "typescript.projectMigrationCompilerInvalid", "Compiler edits exceed source/config ownership")
        // tsserver rewrites config-relative paths against the OLD config directory. For a moved
        // config those suggestions are not configuration authority. Preserve the full proposal hash,
        // retain all source edits, and evaluate our separately proven config image/model instead.
        val compilerSources = modifications.filter { it.path !in configPaths }
        val compilerConfigs = modifications.filter { it.path in configPaths }
        val edit = WorkspaceEditSimulator.normalize(WorkspaceEdit(compilerSources + configuration + native.edits.filterIsInstance<FileEdit.Rename>()))
        val staged = WorkspaceEditSimulator.apply(snapshot, edit)
        val afterModel = model(staged)
        requireSafe(afterModel.status == TypeScriptProjectModelStatus.AVAILABLE &&
            afterModel.projects == expectedProjects(beforeModel, from, to), "typescript.projectMigrationModelInvalid", "Staged graph or options differ from the proven migration")
        val after = diagnostics(staged, afterModel)
        val session = requireNotNull(client.provenance?.process?.id)
        val attestation = toolchain.compilerAttestation()
        val attributes = args + mapOf(
            "command" to "getEditsForFileRename", "configurationOrigin" to "jsonc-reference-path-v1",
            "compilerEditsSha256" to WorkspaceEditIdentity.sha256(native),
            "compilerSourceEditsSha256" to WorkspaceEditIdentity.sha256(WorkspaceEdit(compilerSources)),
            "compilerConfigurationProposalSha256" to WorkspaceEditIdentity.sha256(WorkspaceEdit(compilerConfigs)),
            "configurationAuthority" to "sibling-project-paths-and-jsonc-references",
            "configurationEditsSha256" to WorkspaceEditIdentity.sha256(WorkspaceEdit(configuration)),
            "modelProjectionHash" to beforeModel.projectionHash, "stagedModelProjectionHash" to afterModel.projectionHash,
            "stagedSnapshotHash" to staged.hash, "semanticSession" to session,
            "compilerProcess" to requireNotNull(observed).process.id, "compilerPid" to observed.process.pid.toString(),
            "compilerStartedAt" to observed.process.startedAt.toString(), "executableSha256" to observed.process.executableSha256,
            "processArgumentsSha256" to observed.process.argumentsSha256, "compilerSha256" to attestation.compilerSha256,
            "toolchainEvidenceSha256" to attestation.toolchainEvidenceSha256,
        )
        val editHash = WorkspaceEditIdentity.sha256(edit)
        val identity = buildJsonObject {
            put("operation", OPERATION); put("snapshotHash", snapshot.hash); put("workspaceEditSha256", editHash)
            attributes.toSortedMap().forEach { (key, value) -> put(key, value) }
        }.toString()
        val lease = OperationAuthorityLease("typescript-compiler-exact-v1", OPERATION, snapshot.hash, sha(identity.toByteArray()), workspaceEditSha256 = editHash,
            requiredFileEvidence = beforeModel.evidence.map { OperationAuthorityFileEvidence("typescript-project-input", it.path, it.sha256) }, attributes = attributes)
        approved[staged.hash] = Approved(snapshot, staged.hash, afterModel, session)
        while (approved.size > 16) approved.remove(approved.keys.first())
        PatchPlan(operation = OPERATION, status = PatchStatus.PREVIEW, snapshotHash = snapshot.hash, confidence = 1.0,
            requiresUserApproval = true, summary = "Migrate project $from to $to with compiler imports and proven configuration references",
            affectedFiles = edit.affectedFiles(), workspaceEdit = edit, diagnosticsBefore = before, diagnosticsAfterPreview = after,
            warnings = listOf("Configuration references use exact JSONC origins, not tsserver edits.", "External consumers are not rewritten; review the project path change."),
            riskLevel = RiskLevel.MEDIUM, evidence = RefactoringEvidence.COMPILER_PROVEN, authorityLease = lease)
    } catch (failure: Exception) {
        val code = (failure as? Refusal)?.code ?: "typescript.projectMigrationInvalid"
        PatchPlan(operation = OPERATION, status = PatchStatus.REFUSED, snapshotHash = request.snapshot.hash, confidence = 0.0,
            requiresUserApproval = false, summary = failure.message ?: "Project migration refused", affectedFiles = emptySet(), workspaceEdit = WorkspaceEdit(),
            diagnosticsAfterPreview = listOf(Diagnostic(failure.message ?: "Project migration refused", Diagnostic.Severity.ERROR, code = code)),
            warnings = emptyList(), riskLevel = RiskLevel.HIGH, refusalCode = code)
    }

    /** Only retained B0/S0 images of this session are accepted, including the post-apply model. */
    fun approvedDiagnostics(snapshot: ProjectSnapshot): List<Diagnostic>? {
        val record = approved.values.lastOrNull { snapshot.hash == it.base.hash || snapshot.hash == it.afterHash } ?: return null
        check(sessionMatches(record.base) && toolchainUnchanged() && mutationEligible() && client.provenance?.process?.id == record.session)
        check(snapshot.workspace == record.base.workspace && snapshot.modules == record.base.modules &&
            snapshot.buildModels == record.base.buildModels && snapshot.classpathEvidence == record.base.classpathEvidence)
        val disk = TypeScriptProjectModelBuilder().build(snapshot.workspace.root)
        check(disk.status == TypeScriptProjectModelStatus.AVAILABLE && disk.projectionHash in setOf(originalModel.projectionHash, record.afterModel.projectionHash)) {
            "typescript.projectMigrationModelChanged: disk configuration is neither the approved before nor after image"
        }
        return diagnostics(snapshot, if (snapshot.hash == record.afterHash) record.afterModel else originalModel)
    }

    fun clear() = approved.clear()

    private fun model(snapshot: ProjectSnapshot) = TypeScriptProjectModelBuilder().build(snapshot)

    private fun diagnostics(snapshot: ProjectSnapshot, model: TypeScriptProjectModel): List<Diagnostic> {
        val result = TypeScriptCompilerDiagnostics(toolchain, model).analyze(snapshot, emptyList())
        val values = when (result) {
            is ExternalSemanticDiagnostics.Unavailable -> throw Refusal(result.diagnostic.code ?: "typescript.compilerDiagnosticsUnavailable", result.diagnostic.message)
            is ExternalSemanticDiagnostics.Available -> result.diagnostics
        }
        requireSafe(values.none { it.severity == Diagnostic.Severity.ERROR }, "typescript.diagnosticsNotClean", values.joinToString { "${it.code}: ${it.message}" })
        return values
    }

    private fun expectedProjects(model: TypeScriptProjectModel, from: Path, to: Path): List<TypeScriptProject> {
        fun moved(path: Path): Path = if (path.startsWith(from)) to.resolve(from.relativize(path)) else path
        return model.projects.map { p -> p.copy(configPath = moved(p.configPath), references = p.references.map(::moved).sortedBy(Path::toString),
            files = p.files.map(::moved).sortedBy(Path::toString), include = p.include.map { it.copy(baseDirectory = moved(it.baseDirectory)) },
            exclude = p.exclude.map { it.copy(baseDirectory = moved(it.baseDirectory)) }, compilerOptions = p.compilerOptions.copy(
                rootDirectory = p.compilerOptions.rootDirectory?.let(::moved), outputDirectory = p.compilerOptions.outputDirectory?.let(::moved)))
        }.sortedBy { it.configPath.toString() }
    }

    private fun directory(raw: String?): Path {
        requireSafe(!raw.isNullOrBlank() && raw.length <= 1024 && '\\' !in raw && ':' !in raw, "typescript.projectMigrationPathInvalid", "A portable relative directory is required")
        val path = Path.of(requireNotNull(raw))
        requireSafe(!path.isAbsolute && path.normalize() == path && path.nameCount >= 2 && path.none { it.toString().startsWith(".") },
            "typescript.projectMigrationPathInvalid", "A bounded workspace-relative project directory is required")
        return path
    }

    private fun matchesDisk(snapshot: ProjectSnapshot): Boolean = snapshot.trackedFiles.all { source ->
        val path = snapshot.workspace.root.resolve(source.path)
        var current = snapshot.workspace.root
        val safe = source.path.none { part -> current = current.resolve(part); Files.isSymbolicLink(current) }
        safe && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && sha(Files.readAllBytes(path)) == sha(source.content.toByteArray())
    }

    private class Refusal(val code: String, message: String) : IllegalArgumentException(message)
    private fun requireSafe(condition: Boolean, code: String, message: String) { if (!condition) throw Refusal(code, message) }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object { const val OPERATION = "projectReferenceMigration" }
}

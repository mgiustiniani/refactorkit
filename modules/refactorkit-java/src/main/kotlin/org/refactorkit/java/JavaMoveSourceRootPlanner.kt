package org.refactorkit.java

import org.refactorkit.core.BuildSourceRootOwnership
import org.refactorkit.core.Diagnostic
import org.refactorkit.core.FileEdit
import org.refactorkit.core.PatchPlan
import org.refactorkit.core.PatchStatus
import org.refactorkit.core.ProjectSnapshot
import org.refactorkit.core.ProtocolPath
import org.refactorkit.core.RefactoringEvidence
import org.refactorkit.core.RiskLevel
import org.refactorkit.core.SourceSetKind
import org.refactorkit.core.WorkspaceEdit
import org.refactorkit.core.WorkspaceEditSimulator
import org.refactorkit.core.exactBuildSourceRootOwnerships
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Relocates one complete Java source root while preserving package and type identity. */
class JavaMoveSourceRootPlanner(private val adapter: JavaLanguageAdapter = JavaLanguageAdapter()) {
    fun preview(snapshot: ProjectSnapshot, from: Path, to: Path): PatchPlan {
        val source = normalizeRelative(snapshot, from)
            ?: return refused(snapshot, "sourceRoot.missing", "Source root must be a safe workspace-relative path: $from")
        val destination = normalizeRelative(snapshot, to)
            ?: return refused(snapshot, "sourceRoot.destinationUnrecognized", "Destination root must be a safe workspace-relative path: $to")
        if (source == destination || source.startsWith(destination) || destination.startsWith(source)) {
            return refused(snapshot, "sourceRoot.overlap", "Source and destination roots overlap: $source -> $destination")
        }
        val sourceOwner = exactOwners(snapshot, source).singleOrNull()
            ?: return refused(snapshot, "sourceRoot.missing", "Source is not an exact recognized Java source root: $source")
        if (sourceOwner.generated) {
            return refused(snapshot, "sourceRoot.generated", "Generated source roots are read-only: $source")
        }
        val sourceSet = sourceOwner.sourceSetKind
        val destinationOwners = exactOwners(snapshot, destination).ifEmpty {
            prospectiveOwners(snapshot, destination, sourceSet)
        }
        if (destinationOwners.size != 1) {
            return refused(snapshot, "sourceRoot.destinationUnrecognized", "Destination is not owned by exactly one recognized/prospective Java source root: $destination (owners=${destinationOwners.map { it.moduleId }})")
        }
        val destinationOwner = destinationOwners.single()
        if (destinationOwner.generated) {
            return refused(snapshot, "sourceRoot.generated", "Generated destination roots are read-only: $destination")
        }
        val declaredDestinationSet = destinationOwner.sourceSetKind
        if (declaredDestinationSet != sourceSet) {
            return refused(snapshot, "sourceRoot.destinationUnrecognized", "Source-set kind must be preserved: $sourceSet -> $declaredDestinationSet")
        }
        if (hasSymbolicLinkTraversal(snapshot.workspace.root, source) || hasSymbolicLinkTraversal(snapshot.workspace.root, destination)) {
            return refused(snapshot, "sourceRoot.symlinkEscape", "Source-root relocation refuses symbolic-link traversal")
        }
        val movedFiles = snapshot.files.filter { file ->
            file.languageId == "java" && file.path.normalize().startsWith(source)
        }.sortedBy { it.path.toString() }
        if (movedFiles.isEmpty()) {
            return refused(snapshot, "sourceRoot.missing", "Source root contains no Java compilation units: $source")
        }
        movedFiles.firstOrNull { !packageMatches(it.path, it.content, source) }?.let { mismatch ->
            return refused(snapshot, "sourceRoot.packageMismatch", "Package declaration does not match source-root path: ${mismatch.path}")
        }
        val movedPaths = movedFiles.map { it.path }.toSet()
        val symbols = adapter.buildSymbols(snapshot).symbols
        val duplicate = symbols.filter { it.kind in TYPE_KINDS }.groupBy { it.id.value }.entries
            .firstOrNull { (_, sameIdentity) ->
                sameIdentity.any { it.location.path in movedPaths } && sameIdentity.any { it.location.path !in movedPaths }
            }?.key ?: run {
                val movedPrimary = movedFiles.mapNotNull(::primaryTypeIdentity).toSet()
                snapshot.files.filterNot { it.path in movedPaths }.mapNotNull(::primaryTypeIdentity)
                    .firstOrNull(movedPrimary::contains)
            }
        if (duplicate != null) {
            return refused(snapshot, "sourceRoot.duplicateType", "Relocation would retain a duplicate FQCN: $duplicate")
        }
        val renames = movedFiles.map { file ->
            FileEdit.Rename(file.path, destination.resolve(source.relativize(file.path)).normalize())
        }
        val collisions = destinationCollisions(snapshot, destination, renames, movedPaths)
        if (collisions.isNotEmpty()) {
            return refused(snapshot, "sourceRoot.destinationCollision", "Destination collision(s): ${collisions.joinToString()}")
        }
        val edit = WorkspaceEdit(renames)
        val before = adapter.diagnostics(snapshot)
        val after = runCatching { adapter.diagnostics(WorkspaceEditSimulator.apply(snapshot, edit)) }
            .getOrElse { return refused(snapshot, "buildModel.unavailable", "Post-image diagnostics unavailable") }
        val regressions = diagnosticRegressions(before, after)
        if (regressions.isNotEmpty()) {
            val code = if (regressions.any { it.code in setOf("buildModel.unavailable", "classpath.unavailable") }) {
                regressions.firstNotNullOfOrNull(Diagnostic::code) ?: "classpath.unavailable"
            } else "sourceRoot.diagnosticsRegression"
            return refused(snapshot, code, "Relocation introduces ${regressions.size} compiler diagnostic(s)", before, after)
        }
        val affected = renames.flatMap { listOf(it.path, it.newPath) }.toSet()
        return PatchPlan(
            operation = "moveSourceRoot",
            status = PatchStatus.PREVIEW,
            snapshotHash = snapshot.hash,
            confidence = 1.0,
            requiresUserApproval = true,
            summary = "Move ${movedFiles.size} Java compilation unit(s) from ${ProtocolPath.serialize(source)} to ${ProtocolPath.serialize(destination)} without changing bytes or FQCNs.",
            affectedFiles = affected,
            workspaceEdit = edit,
            diagnosticsBefore = before,
            diagnosticsAfterPreview = after,
            warnings = listOf("Package declarations, imports, FQCNs, and source bytes are unchanged."),
            riskLevel = if (sourceOwner.providerId == destinationOwner.providerId && sourceOwner.moduleId == destinationOwner.moduleId) RiskLevel.LOW else RiskLevel.MEDIUM,
            evidence = RefactoringEvidence.STRUCTURAL,
        )
    }

    private fun normalizeRelative(snapshot: ProjectSnapshot, path: Path): Path? {
        if (path.isAbsolute || path.toString().isBlank()) return null
        val normalized = path.normalize()
        if (normalized.startsWith("..")) return null
        val absolute = snapshot.workspace.root.resolve(normalized).normalize()
        return normalized.takeIf { absolute.startsWith(snapshot.workspace.root.toAbsolutePath().normalize()) }
    }

    private data class SourceRootOwner(
        val providerId: String,
        val moduleId: String,
        val sourceSetKind: SourceSetKind,
        val generated: Boolean,
    )

    private fun exactOwners(snapshot: ProjectSnapshot, root: Path): List<SourceRootOwner> {
        if (snapshot.buildModels.isNotEmpty()) {
            return snapshot.exactBuildSourceRootOwnerships(root).map(::modelOwner)
        }
        return snapshot.modules.filter { root in it.sourceRoots }.map { module ->
            SourceRootOwner(
                providerId = "module-compatibility",
                moduleId = module.name,
                sourceSetKind = if (root in module.testSourceRoots) SourceSetKind.TEST else SourceSetKind.MAIN,
                generated = root in module.generatedSourceRoots || root in module.generatedTestSourceRoots,
            )
        }
    }

    private fun prospectiveOwners(
        snapshot: ProjectSnapshot,
        destination: Path,
        sourceSetKind: SourceSetKind,
    ): List<SourceRootOwner> {
        val suffix = when (sourceSetKind) {
            SourceSetKind.MAIN -> "src/main/java"
            SourceSetKind.TEST -> "src/test/java"
            else -> return emptyList()
        }
        if (snapshot.buildModels.isNotEmpty()) {
            return snapshot.buildModels.flatMap { model ->
                model.modules.mapNotNull { module ->
                    val relativeModule = snapshot.workspace.root.toAbsolutePath().normalize()
                        .relativize(module.root.toAbsolutePath().normalize())
                    SourceRootOwner(model.providerId, module.id, sourceSetKind, false)
                        .takeIf { destination == relativeModule.resolve(suffix).normalize() }
                }
            }
        }
        return snapshot.modules.mapNotNull { module ->
            val relativeModule = snapshot.workspace.root.toAbsolutePath().normalize()
                .relativize(module.root.toAbsolutePath().normalize())
            SourceRootOwner("module-compatibility", module.name, sourceSetKind, false)
                .takeIf { destination == relativeModule.resolve(suffix).normalize() }
        }
    }

    private fun modelOwner(owner: BuildSourceRootOwnership) = SourceRootOwner(
        providerId = owner.providerId,
        moduleId = owner.module.id,
        sourceSetKind = owner.sourceSet.kind,
        generated = owner.generated,
    )

    private fun primaryTypeIdentity(file: org.refactorkit.core.SourceFile): String? {
        val name = file.path.fileName.toString().removeSuffix(".java")
        if (name in setOf("module-info", "package-info")) return null
        return JavaPackageUtil.fqn(JavaPackageUtil.extractPackage(file.content), name)
    }

    private fun packageMatches(path: Path, content: String, sourceRoot: Path): Boolean {
        val relative = sourceRoot.relativize(path)
        if (relative.fileName.toString() == "module-info.java") {
            return relative.parent == null && JavaPackageUtil.extractPackage(content).isEmpty()
        }
        val expected = relative.parent?.joinToString(".") ?: ""
        return JavaPackageUtil.extractPackage(content) == expected
    }

    private fun destinationCollisions(
        snapshot: ProjectSnapshot,
        destinationRoot: Path,
        renames: List<FileEdit.Rename>,
        movedPaths: Set<Path>,
    ): List<Path> {
        val root = snapshot.workspace.root.toAbsolutePath().normalize()
        val existing = linkedSetOf<Path>()
        if (Files.exists(root.resolve(destinationRoot), LinkOption.NOFOLLOW_LINKS)) {
            Files.walk(root.resolve(destinationRoot)).use { stream ->
                stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .forEach { existing.add(root.relativize(it)) }
            }
        }
        existing.addAll(snapshot.files.map { it.path }.filterNot { it in movedPaths })
        val existingFolded = existing.associateBy { it.toString().replace('\\', '/').lowercase() }
        val targets = mutableSetOf<String>()
        return renames.mapNotNull { rename ->
            val folded = rename.newPath.toString().replace('\\', '/').lowercase()
            rename.newPath.takeIf { folded in existingFolded || !targets.add(folded) }
        }.distinct()
    }

    private fun hasSymbolicLinkTraversal(workspaceRoot: Path, relative: Path): Boolean {
        var current = workspaceRoot.toAbsolutePath().normalize()
        relative.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun diagnosticRegressions(before: List<Diagnostic>, after: List<Diagnostic>): List<Diagnostic> {
        val counts = before.filter { it.severity == Diagnostic.Severity.ERROR }
            .groupingBy(::diagnosticIdentity).eachCount().toMutableMap()
        return after.filter { diagnostic ->
            if (diagnostic.severity != Diagnostic.Severity.ERROR) return@filter false
            val key = diagnosticIdentity(diagnostic)
            val count = counts[key] ?: 0
            if (count > 0) { counts[key] = count - 1; false } else true
        }
    }

    private fun diagnosticIdentity(diagnostic: Diagnostic): String = "${diagnostic.code}\u0000${diagnostic.message}"

    private fun refused(
        snapshot: ProjectSnapshot,
        code: String,
        reason: String,
        before: List<Diagnostic> = emptyList(),
        after: List<Diagnostic> = emptyList(),
    ) = PatchPlan(
        operation = "moveSourceRoot",
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
        evidence = RefactoringEvidence.STRUCTURAL,
        refusalCode = code,
    )

    companion object {
        private val TYPE_KINDS = setOf(
            org.refactorkit.core.Symbol.Kind.CLASS,
            org.refactorkit.core.Symbol.Kind.INTERFACE,
            org.refactorkit.core.Symbol.Kind.ENUM,
            org.refactorkit.core.Symbol.Kind.RECORD,
            org.refactorkit.core.Symbol.Kind.ANNOTATION,
        )
    }
}
